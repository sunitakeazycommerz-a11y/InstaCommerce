"""Tool registry with circuit breakers for downstream Java services.

Each tool wraps an ``httpx`` call to one of the InstaCommerce Java
micro-services.  Built-in resilience features:

* **Circuit breaker** – opens after ``failure_threshold`` consecutive
  failures, transitions to half-open after ``reset_timeout_s`` seconds.
* **Retry with exponential back-off** – up to ``max_retries`` attempts.
* **Per-tool timeout** – defaults to 2.5 s.
* **Idempotency key** – automatically attached to write operations via an
  ``X-Idempotency-Key`` header.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from typing import Any, Dict, List, Optional

import httpx

from app.config import Settings
from app.graph.circuit_breaker import CircuitBreaker, CircuitState, ToolDescriptor
from app.graph.state import ToolResult
from app.graph.tool_validation import (
    _has_traversal,
    _is_permitted_path,
    _unquote_iteratively,
    _validate_and_build_url,
    _validate_base_url,
)

logger = logging.getLogger("ai_orchestrator.tools")

__all__ = [
    "CircuitBreaker",
    "CircuitState",
    "ToolDescriptor",
    "ToolRegistry",
    "_has_traversal",
    "_is_permitted_path",
    "_unquote_iteratively",
    "_validate_and_build_url",
    "_validate_base_url",
]


_USER_SCOPED_TOOLS = frozenset({"order.get", "cart.get"})


class ToolRegistry:
    """Central registry of callable tools backed by Java micro-services.

    Usage::

        registry = ToolRegistry(settings)
        result = await registry.call("catalog.search", params={"q": "milk"})
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self.settings = settings
        self._client: Optional[httpx.AsyncClient] = None
        self._tools: Dict[str, ToolDescriptor] = {}
        self._register_defaults()

    async def start(self) -> None:
        """Create the shared ``httpx.AsyncClient``."""
        if self._client is None:
            self._client = httpx.AsyncClient(
                timeout=httpx.Timeout(10.0, connect=3.0),
                limits=httpx.Limits(max_connections=50, max_keepalive_connections=10),
                follow_redirects=False,
            )

    async def close(self) -> None:
        """Shut down the HTTP client."""
        if self._client:
            await self._client.aclose()
            self._client = None

    def register(self, tool: ToolDescriptor) -> None:
        self._tools[tool.name] = tool

    def _register_defaults(self) -> None:
        """Wire up the standard InstaCommerce tool set."""
        s = self._settings
        defaults: List[ToolDescriptor] = [
            ToolDescriptor(
                name="catalog.search",
                description="Full-text product search via search-service",
                base_url=str(s.search_service_url),
                path="/search",
            ),
            ToolDescriptor(
                name="catalog.get_product",
                description="Fetch a single product by ID",
                base_url=str(s.catalog_service_url),
                path="/products/{product_id}",
            ),
            ToolDescriptor(
                name="catalog.list_products",
                description="List products with filters",
                base_url=str(s.catalog_service_url),
                path="/products",
            ),
            ToolDescriptor(
                name="pricing.calculate",
                description="Calculate price for a product/cart",
                base_url=str(s.pricing_service_url),
                path="/pricing/calculate",
                method="POST",
                is_write=False,
            ),
            ToolDescriptor(
                name="pricing.get_product",
                description="Get pricing for a product",
                base_url=str(s.pricing_service_url),
                path="/pricing/products/{product_id}",
            ),
            ToolDescriptor(
                name="inventory.check",
                description="Check stock availability",
                base_url=str(s.inventory_service_url),
                path="/inventory/check",
                method="POST",
                is_write=False,
            ),
            ToolDescriptor(
                name="cart.get",
                description="Get user cart",
                base_url=str(s.cart_service_url),
                path="/cart/{user_id}",
            ),
            ToolDescriptor(
                name="order.get",
                description="Get order details",
                base_url=str(s.order_service_url),
                path="/orders/{order_id}",
            ),
        ]
        for t in defaults:
            t.timeout_s = s.tool_call_timeout_seconds
            t.circuit_breaker = CircuitBreaker(
                failure_threshold=s.tool_circuit_breaker_failures,
                reset_timeout_s=s.tool_circuit_breaker_reset_seconds,
            )
            self._tools[t.name] = t

    def is_allowed(self, tool_name: str) -> bool:
        """Check whether a tool is in the runtime allowlist."""
        return tool_name in self._settings.tool_allowlist

    async def call(
        self,
        tool_name: str,
        *,
        params: Optional[Dict[str, Any]] = None,
        body: Optional[Dict[str, Any]] = None,
        path_params: Optional[Dict[str, str]] = None,
        user_id: Optional[str] = None,
    ) -> ToolResult:
        """Invoke a tool with circuit breaker, retry, and timeout.

        Returns a ``ToolResult`` regardless of outcome – callers never see
        raw exceptions from downstream services.
        """
        tool = self._tools.get(tool_name)
        if tool is None:
            return ToolResult(
                tool_name=tool_name,
                success=False,
                error=f"Unknown tool: {tool_name}",
            )

        if not self.is_allowed(tool_name):
            return ToolResult(
                tool_name=tool_name,
                success=False,
                error=f"Tool not in allowlist: {tool_name}",
            )

        if not tool.circuit_breaker.allow_request():
            return ToolResult(
                tool_name=tool_name,
                success=False,
                error="Circuit breaker open",
            )

        caller_uid: Optional[str] = user_id.strip() if user_id is not None else None
        if user_id is not None and tool_name in _USER_SCOPED_TOOLS and not caller_uid:
            return ToolResult(
                tool_name=tool_name,
                success=False,
                error="authorization_denied: user_id required for user-scoped tool call",
            )

        if tool_name == "cart.get":
            raw_uid = (path_params or {}).get("user_id") if path_params else None
            if (raw_uid is None or not str(raw_uid).strip()) and params:
                raw_uid = params.get("user_id")
            if raw_uid is None or not str(raw_uid).strip():
                return ToolResult(
                    tool_name=tool_name,
                    success=False,
                    error="user_id_required: empty user_id rejected before cart lookup",
                )
            target_uid = str(raw_uid).strip()
            if caller_uid is not None and target_uid != caller_uid:
                return ToolResult(
                    tool_name=tool_name,
                    success=False,
                    error="authorization_denied: cart user_id does not match caller",
                )
            path_params = {**(path_params or {}), "user_id": target_uid}

        url, validation_error = _validate_and_build_url(tool, params=params, path_params=path_params)
        if validation_error or url is None:
            return ToolResult(
                tool_name=tool_name,
                success=False,
                error=validation_error or "Security violation: invalid request URL",
            )

        effective_params = dict(params) if params else None
        if tool_name == "catalog.search" and effective_params is not None:
            if "query" not in effective_params and "q" in effective_params:
                effective_params["query"] = effective_params["q"]

        headers: Dict[str, str] = {}
        if tool.is_write:
            headers["X-Idempotency-Key"] = str(uuid.uuid4())

        if self._settings.internal_service_token:
            headers["X-Internal-Service"] = "ai-orchestrator-service"
            headers["X-Internal-Token"] = self._settings.internal_service_token

        if caller_uid:
            headers[self._settings.user_id_header_name] = caller_uid

        if self._client is None:
            await self.start()
        assert self._client is not None

        last_error: Optional[str] = None
        start_ns = time.monotonic_ns()

        for attempt in range(1 + tool.max_retries):
            try:
                if attempt > 0:
                    backoff = min(0.5 * (2 ** (attempt - 1)), 4.0)
                    await asyncio.sleep(backoff)

                if tool.method.upper() == "POST":
                    resp = await self._client.post(
                        url,
                        json=body or {},
                        params=effective_params,
                        headers=headers,
                        timeout=tool.timeout_s,
                    )
                else:
                    resp = await self._client.get(
                        url,
                        params=effective_params,
                        headers=headers,
                        timeout=tool.timeout_s,
                    )

                elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000

                if resp.status_code < 400:
                    tool.circuit_breaker.record_success()
                    data = resp.json() if resp.content else {}
                    if caller_uid is not None and tool_name in _USER_SCOPED_TOOLS:
                        owner_uid = (
                            data.get("userId") or data.get("user_id")
                            if isinstance(data, dict)
                            else None
                        )
                        if not owner_uid or str(owner_uid).strip() != caller_uid:
                            logger.warning(
                                "tool.call.bola_blocked",
                                extra={"tool": tool_name, "caller_uid": caller_uid},
                            )
                            return ToolResult(
                                tool_name=tool_name,
                                success=False,
                                error="authorization_denied: resource ownership mismatch",
                                latency_ms=elapsed_ms,
                            )
                    logger.info(
                        "tool.call.success",
                        extra={
                            "tool": tool_name,
                            "status": resp.status_code,
                            "latency_ms": round(elapsed_ms, 1),
                            "attempt": attempt + 1,
                        },
                    )
                    return ToolResult(
                        tool_name=tool_name,
                        success=True,
                        data=data,
                        latency_ms=elapsed_ms,
                    )

                last_error = f"HTTP {resp.status_code}"
                if 400 <= resp.status_code < 500:
                    tool.circuit_breaker.record_success()
                    break

                tool.circuit_breaker.record_failure()

            except httpx.TimeoutException:
                last_error = "timeout"
                tool.circuit_breaker.record_failure()
                logger.warning(
                    "tool.call.timeout",
                    extra={"tool": tool_name, "attempt": attempt + 1},
                )
            except httpx.HTTPError as exc:
                last_error = str(exc)
                tool.circuit_breaker.record_failure()
                logger.warning(
                    "tool.call.error",
                    extra={
                        "tool": tool_name,
                        "error": last_error,
                        "attempt": attempt + 1,
                    },
                )

        elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000
        logger.error(
            "tool.call.failed",
            extra={
                "tool": tool_name,
                "error": last_error,
                "latency_ms": round(elapsed_ms, 1),
            },
        )
        return ToolResult(
            tool_name=tool_name,
            success=False,
            error=last_error,
            latency_ms=elapsed_ms,
        )

    async def search_catalog(
        self,
        query: str,
        category: Optional[str] = None,
        limit: int = 10,
    ) -> ToolResult:
        """Search products via search-service ``/search``, falling back to catalog ``/products``."""
        params: Dict[str, Any] = {"query": query, "q": query, "size": limit}
        if category:
            params["category"] = category

        result = await self.call("catalog.search", params=params)
        if result.success:
            return result

        fallback_params: Dict[str, Any] = {"q": query, "query": query, "size": limit}
        if category:
            fallback_params["category"] = category
        logger.info("tools.search_catalog.fallback_to_catalog", extra={"error": result.error})
        return await self.call("catalog.list_products", params=fallback_params)

    async def get_cart(self, user_id: Optional[str]) -> ToolResult:
        """Fetch cart from ``{cart_service_url}/cart/{user_id}``, rejecting empty user_id."""
        if not user_id or not str(user_id).strip():
            return ToolResult(
                tool_name="cart.get",
                success=False,
                error="user_id_required: empty user_id rejected before cart lookup",
            )
        return await self.call("cart.get", path_params={"user_id": str(user_id).strip()})

    @property
    def available_tools(self) -> List[str]:
        """Return names of all registered tools."""
        return list(self._tools.keys())
