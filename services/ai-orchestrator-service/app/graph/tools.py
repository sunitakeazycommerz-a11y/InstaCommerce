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
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

import httpx

from app.config import Settings
from app.graph.state import ToolResult

logger = logging.getLogger("ai_orchestrator.tools")


# ---------------------------------------------------------------------------
# Circuit breaker
# ---------------------------------------------------------------------------

class CircuitState(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass
class CircuitBreaker:
    """Simple three-state circuit breaker."""

    failure_threshold: int = 3
    reset_timeout_s: float = 30.0

    _state: CircuitState = CircuitState.CLOSED
    _failure_count: int = 0
    _last_failure_time: float = 0.0

    @property
    def state(self) -> CircuitState:
        if self._state == CircuitState.OPEN:
            if (time.monotonic() - self._last_failure_time) >= self.reset_timeout_s:
                self._state = CircuitState.HALF_OPEN
        return self._state

    def record_success(self) -> None:
        self._failure_count = 0
        self._state = CircuitState.CLOSED

    def record_failure(self) -> None:
        self._failure_count += 1
        self._last_failure_time = time.monotonic()
        if self._failure_count >= self.failure_threshold:
            self._state = CircuitState.OPEN
            logger.warning(
                "circuit_breaker.opened",
                extra={"failure_count": self._failure_count},
            )

    def allow_request(self) -> bool:
        current = self.state
        if current == CircuitState.CLOSED:
            return True
        if current == CircuitState.HALF_OPEN:
            return True  # allow one probe
        return False


# ---------------------------------------------------------------------------
# Tool descriptor
# ---------------------------------------------------------------------------

@dataclass
class ToolDescriptor:
    """Metadata + circuit breaker for a single registered tool."""

    name: str
    description: str
    base_url: str
    path: str
    method: str = "GET"
    timeout_s: float = 2.5
    max_retries: int = 2
    is_write: bool = False
    circuit_breaker: CircuitBreaker = field(default_factory=CircuitBreaker)


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------

class ToolRegistry:
    """Central registry of callable tools backed by Java micro-services.

    Usage::

        registry = ToolRegistry(settings)
        result = await registry.call("catalog.search", params={"q": "milk"})
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client: Optional[httpx.AsyncClient] = None
        self._tools: Dict[str, ToolDescriptor] = {}
        self._register_defaults()

    # -- lifecycle -----------------------------------------------------------

    async def start(self) -> None:
        """Create the shared ``httpx.AsyncClient``."""
        if self._client is None:
            self._client = httpx.AsyncClient(
                timeout=httpx.Timeout(10.0, connect=3.0),
                limits=httpx.Limits(max_connections=50, max_keepalive_connections=10),
            )

    async def close(self) -> None:
        """Shut down the HTTP client."""
        if self._client:
            await self._client.aclose()
            self._client = None

    # -- registration --------------------------------------------------------

    def register(self, tool: ToolDescriptor) -> None:
        self._tools[tool.name] = tool

    def _register_defaults(self) -> None:
        """Wire up the standard InstaCommerce tool set."""
        s = self._settings
        defaults: List[ToolDescriptor] = [
            ToolDescriptor(
                name="catalog.search",
                description="Full-text product search",
                base_url=str(s.catalog_service_url),
                path="/api/v1/products/search",
            ),
            ToolDescriptor(
                name="catalog.get_product",
                description="Fetch a single product by ID",
                base_url=str(s.catalog_service_url),
                path="/api/v1/products/{product_id}",
            ),
            ToolDescriptor(
                name="catalog.list_products",
                description="List products with filters",
                base_url=str(s.catalog_service_url),
                path="/api/v1/products",
            ),
            ToolDescriptor(
                name="pricing.calculate",
                description="Calculate price for a product/cart",
                base_url=str(s.pricing_service_url),
                path="/api/v1/pricing/calculate",
                method="POST",
                is_write=False,
            ),
            ToolDescriptor(
                name="pricing.get_product",
                description="Get pricing for a product",
                base_url=str(s.pricing_service_url),
                path="/api/v1/pricing/products/{product_id}",
            ),
            ToolDescriptor(
                name="inventory.check",
                description="Check stock availability",
                base_url=str(s.inventory_service_url),
                path="/api/v1/inventory/{product_id}",
            ),
            ToolDescriptor(
                name="cart.get",
                description="Get user cart",
                base_url=str(s.cart_service_url),
                path="/api/v1/carts/{user_id}",
            ),
            ToolDescriptor(
                name="order.get",
                description="Get order details",
                base_url=str(s.order_service_url),
                path="/api/v1/orders/{order_id}",
            ),
        ]
        for t in defaults:
            t.timeout_s = s.tool_call_timeout_seconds
            t.circuit_breaker = CircuitBreaker(
                failure_threshold=s.tool_circuit_breaker_failures,
                reset_timeout_s=s.tool_circuit_breaker_reset_seconds,
            )
            self._tools[t.name] = t

    # -- invocation ----------------------------------------------------------

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

        # Resolve path parameters
        path = tool.path
        if path_params:
            for key, value in path_params.items():
                path = path.replace(f"{{{key}}}", value)

        url = f"{tool.base_url.rstrip('/')}{path}"

        # Idempotency key for write operations
        headers: Dict[str, str] = {}
        if tool.is_write:
            headers["X-Idempotency-Key"] = str(uuid.uuid4())

        if self._settings.internal_service_token:
            headers["Authorization"] = f"Bearer {self._settings.internal_service_token}"

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
                        params=params,
                        headers=headers,
                        timeout=tool.timeout_s,
                    )
                else:
                    resp = await self._client.get(
                        url,
                        params=params,
                        headers=headers,
                        timeout=tool.timeout_s,
                    )

                elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000

                if resp.status_code < 400:
                    tool.circuit_breaker.record_success()
                    data = resp.json() if resp.content else {}
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
                # 4xx → don't retry
                if 400 <= resp.status_code < 500:
                    tool.circuit_breaker.record_success()
                    break

                # 5xx → retry
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

    @property
    def available_tools(self) -> List[str]:
        """Return names of all registered tools."""
        return list(self._tools.keys())
