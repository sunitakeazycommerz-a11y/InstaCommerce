import asyncio
import hashlib
import json
import logging
import math
import re
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, Dict, List, Literal, Optional, Set, Tuple
from uuid import UUID

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ValidationError

from app.config import Settings
from app.guardrails.rate_limiter import TokenBucketRateLimiter

settings = Settings()
logger = logging.getLogger("ai_orchestrator")


STANDARD_LOG_RECORD_ATTRS = {
    "args",
    "asctime",
    "created",
    "exc_info",
    "exc_text",
    "filename",
    "funcName",
    "levelname",
    "levelno",
    "lineno",
    "message",
    "module",
    "msecs",
    "msg",
    "name",
    "pathname",
    "process",
    "processName",
    "relativeCreated",
    "stack_info",
    "thread",
    "threadName",
}


class PiiRedactor:
    def __init__(self, enabled: bool = True) -> None:
        self._enabled = enabled
        self._patterns: Tuple[Tuple[re.Pattern[str], str], ...] = (
            (re.compile(r"[\w\.-]+@[\w\.-]+\.\w+"), "[REDACTED_EMAIL]"),
            (re.compile(r"\b\d{3}-?\d{2}-?\d{4}\b"), "[REDACTED_SSN]"),
            (re.compile(r"\b(?:\d[ -]*?){13,16}\b"), "[REDACTED_CARD]"),
            (
                re.compile(r"\b(?:\+?\d{1,3}[ -]?)?(?:\(?\d{3}\)?[ -]?)\d{3}[ -]?\d{4}\b"),
                "[REDACTED_PHONE]",
            ),
        )

    def redact_text(self, value: str) -> str:
        if not self._enabled:
            return value
        redacted = value
        for pattern, replacement in self._patterns:
            redacted = pattern.sub(replacement, redacted)
        return redacted

    def redact(self, value: Any) -> Any:
        if not self._enabled:
            return value
        if isinstance(value, str):
            return self.redact_text(value)
        if isinstance(value, dict):
            return {key: self.redact(val) for key, val in value.items()}
        if isinstance(value, list):
            return [self.redact(item) for item in value]
        return value


class JsonLogFormatter(logging.Formatter):
    def __init__(self, redactor: PiiRedactor) -> None:
        super().__init__()
        self._redactor = redactor

    def format(self, record: logging.LogRecord) -> str:
        payload: Dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)
        for key, value in record.__dict__.items():
            if key in STANDARD_LOG_RECORD_ATTRS:
                continue
            payload[key] = value
        payload = self._redactor.redact(payload)
        return json.dumps(payload, default=str)


redactor = PiiRedactor(enabled=settings.pii_redaction_enabled)


def log_event(message: str, level: int = logging.INFO, **fields: Any) -> None:
    logger.log(level, message, extra=redactor.redact(fields))


def configure_logging() -> None:
    level = getattr(logging, settings.log_level.upper(), logging.INFO)
    handler = logging.StreamHandler()
    handler.setFormatter(JsonLogFormatter(redactor))
    logging.basicConfig(level=level, handlers=[handler])


configure_logging()


class ItemQuantity(BaseModel):
    product_id: UUID
    quantity: int = Field(..., ge=1, le=1000)


class AssistContext(BaseModel):
    order_id: Optional[UUID] = None
    product_id: Optional[UUID] = None
    store_id: Optional[str] = Field(None, min_length=1, max_length=64)
    items: List[ItemQuantity] = Field(default_factory=list, max_length=50)
    category: Optional[str] = Field(None, min_length=1, max_length=64)
    coupon_code: Optional[str] = Field(None, min_length=1, max_length=64)


class AssistRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=1000)
    user_id: Optional[UUID] = None
    session_id: Optional[str] = Field(None, min_length=1, max_length=64)
    context: AssistContext = Field(default_factory=AssistContext)
    execute_tools: bool = False
    request_id: Optional[str] = Field(None, min_length=6, max_length=64)


class SubstituteRequest(BaseModel):
    product_id: UUID
    candidate_ids: List[UUID] = Field(default_factory=list, max_length=20)
    store_id: Optional[str] = Field(None, min_length=1, max_length=64)
    limit: int = Field(1, ge=1, le=5)
    execute_tools: bool = False
    request_id: Optional[str] = Field(None, min_length=6, max_length=64)


class RecommendRequest(BaseModel):
    user_id: Optional[UUID] = None
    seed_product_id: Optional[UUID] = None
    recent_product_ids: List[UUID] = Field(default_factory=list, max_length=20)
    category: Optional[str] = Field(None, min_length=1, max_length=64)
    limit: int = Field(5, ge=1, le=20)
    execute_tools: bool = False
    request_id: Optional[str] = Field(None, min_length=6, max_length=64)


class ToolCall(BaseModel):
    name: str = Field(..., min_length=1, max_length=64)
    arguments: Dict[str, Any] = Field(default_factory=dict)


class ToolResult(BaseModel):
    name: str
    success: bool
    status_code: Optional[int] = None
    data: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class AgentResponse(BaseModel):
    request_id: str
    mode: Literal["fallback", "llm"]
    intent: str
    message: str
    tool_calls: List[ToolCall] = Field(default_factory=list)
    tool_results: List[ToolResult] = Field(default_factory=list)


class AssistResponse(AgentResponse):
    pass


class SubstituteResponse(AgentResponse):
    substitute_product_id: Optional[UUID] = None
    candidate_ids: List[UUID] = Field(default_factory=list)


class RecommendResponse(AgentResponse):
    recommended_product_ids: List[UUID] = Field(default_factory=list)


END = "__end__"
State = Dict[str, Any]
StateHandler = Callable[[State], Awaitable[Optional[Dict[str, Any]]]] | Callable[[State], Optional[Dict[str, Any]]]
ConditionHandler = Callable[[State], str]


class StateGraph:
    def __init__(self) -> None:
        self._nodes: Dict[str, StateHandler] = {}
        self._edges: Dict[str, List[str]] = {}
        self._conditional_edges: Dict[str, Tuple[ConditionHandler, Dict[str, str]]] = {}
        self._entrypoint: Optional[str] = None

    def add_node(self, name: str, handler: StateHandler) -> "StateGraph":
        self._nodes[name] = handler
        return self

    def add_edge(self, source: str, destination: str) -> "StateGraph":
        self._edges.setdefault(source, []).append(destination)
        return self

    def add_conditional_edges(
        self, source: str, condition: ConditionHandler, edges: Dict[str, str]
    ) -> "StateGraph":
        self._conditional_edges[source] = (condition, edges)
        return self

    def set_entrypoint(self, name: str) -> "StateGraph":
        self._entrypoint = name
        return self

    async def ainvoke(self, state: State, max_steps: int = 30) -> State:
        current = self._entrypoint
        steps = 0
        while current and current != END:
            steps += 1
            if steps > max_steps:
                raise RuntimeError("StateGraph exceeded max_steps")
            handler = self._nodes[current]
            result = handler(state)
            if asyncio.iscoroutine(result):
                result = await result
            if isinstance(result, dict):
                state.update(result)
            if current in self._conditional_edges:
                condition, edges = self._conditional_edges[current]
                branch = condition(state)
                current = edges.get(branch) or edges.get("default") or END
            else:
                next_nodes = self._edges.get(current, [])
                current = next_nodes[0] if next_nodes else END
        return state


@dataclass(frozen=True)
class ToolGuardrails:
    allowlist: Set[str]
    max_calls: int
    max_total_seconds: float
    timeout_seconds: float


class ToolBudget:
    def __init__(self, max_calls: int, max_total_seconds: float) -> None:
        self._max_calls = max_calls
        self._max_total_seconds = max_total_seconds
        self._start = time.monotonic()
        self._calls = 0

    def reserve(self) -> bool:
        if not self.can_reserve():
            return False
        self._calls += 1
        return True

    def can_reserve(self) -> bool:
        if self._calls >= self._max_calls:
            return False
        if self.elapsed_seconds() > self._max_total_seconds:
            return False
        return True

    def elapsed_seconds(self) -> float:
        return time.monotonic() - self._start


class CircuitBreaker:
    def __init__(self, failure_threshold: int, reset_timeout: float) -> None:
        self._failure_threshold = failure_threshold
        self._reset_timeout = reset_timeout
        self._failure_count = 0
        self._opened_until: Optional[float] = None

    def allow_request(self) -> bool:
        if self._opened_until is None:
            return True
        if time.monotonic() >= self._opened_until:
            self._opened_until = None
            self._failure_count = 0
            return True
        return False

    def record_success(self) -> None:
        self._failure_count = 0
        self._opened_until = None

    def record_failure(self) -> None:
        self._failure_count += 1
        if self._failure_count >= self._failure_threshold:
            self._opened_until = time.monotonic() + self._reset_timeout


class CircuitBreakerRegistry:
    def __init__(self, failure_threshold: int, reset_timeout: float) -> None:
        self._failure_threshold = failure_threshold
        self._reset_timeout = reset_timeout
        self._breakers: Dict[str, CircuitBreaker] = {}

    def _key(self, tool_name: str) -> str:
        return tool_name.split(".", maxsplit=1)[0]

    def get(self, tool_name: str) -> CircuitBreaker:
        key = self._key(tool_name)
        if key not in self._breakers:
            self._breakers[key] = CircuitBreaker(self._failure_threshold, self._reset_timeout)
        return self._breakers[key]


@dataclass
class ToolExecutionContext:
    guardrails: ToolGuardrails
    budget: ToolBudget
    breakers: CircuitBreakerRegistry
    request_id: str
    tracer: Any


@dataclass(frozen=True)
class ExecutionResources:
    clients: "ToolClients"
    llm: "LlmClient"
    retriever: "RetrievalProvider"
    guardrails: ToolGuardrails
    breakers: CircuitBreakerRegistry
    tracer: Any


class RetrievalProvider:
    async def retrieve(self, query: str, context: Dict[str, Any]) -> List[Dict[str, Any]]:
        return []


class CachedRetrievalProvider(RetrievalProvider):
    def __init__(self, ttl_seconds: float, max_entries: int, max_results: int) -> None:
        self._ttl_seconds = ttl_seconds
        self._max_entries = max_entries
        self._max_results = max_results
        self._cache: Dict[str, Tuple[float, List[Dict[str, Any]]]] = {}

    async def retrieve(self, query: str, context: Dict[str, Any]) -> List[Dict[str, Any]]:
        if not query:
            return []
        key = self._cache_key(query, context)
        now = time.monotonic()
        cached = self._cache.get(key)
        if cached and now - cached[0] <= self._ttl_seconds:
            return cached[1]
        data = await self._fetch(query, context)
        if self._max_results:
            data = data[: self._max_results]
        self._cache[key] = (now, data)
        self._prune(now)
        return data

    async def _fetch(self, query: str, context: Dict[str, Any]) -> List[Dict[str, Any]]:
        return []

    def _cache_key(self, query: str, context: Dict[str, Any]) -> str:
        digest = hashlib.sha256(json.dumps({"query": query, "context": context}, sort_keys=True).encode("utf-8"))
        return digest.hexdigest()

    def _prune(self, now: float) -> None:
        expired = [key for key, (timestamp, _) in self._cache.items() if now - timestamp > self._ttl_seconds]
        for key in expired:
            self._cache.pop(key, None)
        if len(self._cache) <= self._max_entries:
            return
        oldest = sorted(self._cache.items(), key=lambda item: item[1][0])
        for key, _ in oldest[: max(len(self._cache) - self._max_entries, 0)]:
            self._cache.pop(key, None)


def init_telemetry(app_instance: FastAPI) -> Optional[Any]:
    if not settings.otel_enabled:
        return None
    try:
        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
        from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
    except ImportError:
        logger.warning("OpenTelemetry instrumentation unavailable")
        return None

    resource = Resource.create({"service.name": settings.otel_service_name or settings.app_name})
    provider = TracerProvider(resource=resource)
    endpoint = str(settings.otel_exporter_otlp_endpoint) if settings.otel_exporter_otlp_endpoint else None
    exporter = OTLPSpanExporter(endpoint=endpoint) if endpoint else OTLPSpanExporter()
    provider.add_span_processor(BatchSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    FastAPIInstrumentor.instrument_app(app_instance)
    HTTPXClientInstrumentor().instrument()
    return trace.get_tracer(settings.app_name)

class ToolClients:
    def __init__(self, config: Settings) -> None:
        headers: Dict[str, str] = {
            "X-Internal-Service": config.internal_service_name,
        }
        if config.internal_service_token:
            headers["X-Internal-Token"] = config.internal_service_token
        self._headers = headers
        timeout_seconds = min(config.request_timeout_seconds, config.tool_call_timeout_seconds)
        self._timeout = httpx.Timeout(timeout_seconds)
        self.catalog = httpx.AsyncClient(
            base_url=str(config.catalog_service_url), timeout=self._timeout, headers=self._headers
        )
        self.pricing = httpx.AsyncClient(
            base_url=str(config.pricing_service_url), timeout=self._timeout, headers=self._headers
        )
        self.inventory = httpx.AsyncClient(
            base_url=str(config.inventory_service_url), timeout=self._timeout, headers=self._headers
        )
        self.cart = httpx.AsyncClient(
            base_url=str(config.cart_service_url), timeout=self._timeout, headers=self._headers
        )
        self.order = httpx.AsyncClient(
            base_url=str(config.order_service_url), timeout=self._timeout, headers=self._headers
        )
        self._user_header_name = config.user_id_header_name

    async def close(self) -> None:
        await asyncio.gather(
            self.catalog.aclose(),
            self.pricing.aclose(),
            self.inventory.aclose(),
            self.cart.aclose(),
            self.order.aclose(),
        )

    @staticmethod
    def _safe_json(response: httpx.Response) -> Dict[str, Any]:
        try:
            data = response.json()
        except ValueError:
            return {"raw": response.text}
        if isinstance(data, dict):
            return data
        return {"value": data}

    def _user_headers(self, user_id: Optional[UUID]) -> Dict[str, str]:
        headers = dict(self._headers)
        if user_id:
            headers[self._user_header_name] = str(user_id)
        return headers

    async def _request(self, client: httpx.AsyncClient, method: str, path: str, **kwargs: Any) -> httpx.Response:
        response = await client.request(method, path, **kwargs)
        response.raise_for_status()
        return response

    async def get_product(self, product_id: UUID) -> Dict[str, Any]:
        response = await self._request(self.catalog, "GET", f"/products/{product_id}")
        return self._safe_json(response)

    async def search_catalog(self, query: str, category: Optional[str], limit: int) -> Dict[str, Any]:
        params: Dict[str, Any] = {"q": query, "page": 0, "size": limit}
        if category:
            params["category"] = category
        response = await self._request(self.catalog, "GET", "/search", params=params)
        return self._safe_json(response)

    async def list_products(self, category: Optional[str], limit: int) -> Dict[str, Any]:
        params: Dict[str, Any] = {"page": 0, "size": limit}
        if category:
            params["category"] = category
        response = await self._request(self.catalog, "GET", "/products", params=params)
        return self._safe_json(response)

    async def calculate_price(
        self, items: List[ItemQuantity], user_id: Optional[UUID], coupon_code: Optional[str]
    ) -> Dict[str, Any]:
        payload = {
            "items": [{"productId": item.product_id, "quantity": item.quantity} for item in items],
            "userId": user_id,
            "couponCode": coupon_code,
        }
        response = await self._request(self.pricing, "POST", "/pricing/calculate", json=payload)
        return self._safe_json(response)

    async def get_product_price(self, product_id: UUID) -> Dict[str, Any]:
        response = await self._request(self.pricing, "GET", f"/pricing/products/{product_id}")
        return self._safe_json(response)

    async def check_inventory(self, store_id: str, items: List[ItemQuantity]) -> Dict[str, Any]:
        payload = {
            "storeId": store_id,
            "items": [{"productId": item.product_id, "quantity": item.quantity} for item in items],
        }
        response = await self._request(self.inventory, "POST", "/inventory/check", json=payload)
        return self._safe_json(response)

    async def get_cart(self, user_id: Optional[UUID]) -> Dict[str, Any]:
        response = await self._request(self.cart, "GET", "/cart", headers=self._user_headers(user_id))
        return self._safe_json(response)

    async def get_order(self, order_id: UUID) -> Dict[str, Any]:
        response = await self._request(self.order, "GET", f"/orders/{order_id}")
        return self._safe_json(response)


class LlmClient:
    def __init__(self, config: Settings) -> None:
        self._enabled = config.llm_api_url is not None
        self._url = str(config.llm_api_url) if config.llm_api_url else None
        self._headers = {}
        if config.llm_api_key:
            self._headers["Authorization"] = f"Bearer {config.llm_api_key}"
        self._model = config.llm_model
        self._client = httpx.AsyncClient(timeout=httpx.Timeout(config.request_timeout_seconds)) if self._enabled else None

    async def close(self) -> None:
        if self._client:
            await self._client.aclose()

    async def generate(self, endpoint: str, payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        if not self._enabled or not self._client or not self._url:
            return None
        try:
            response = await self._client.post(
                self._url,
                json={"endpoint": endpoint, "input": payload, "model": self._model},
                headers=self._headers,
            )
            response.raise_for_status()
            data = response.json()
            if isinstance(data, dict):
                return data
        except (httpx.RequestError, httpx.HTTPStatusError, ValueError) as exc:
            logger.warning("LLM hook failed: %s", exc.__class__.__name__)
        return None


def derive_request_id(explicit: Optional[str], payload: Dict[str, Any], prefix: str) -> str:
    if explicit:
        return explicit
    digest = hashlib.sha256(json.dumps(payload, sort_keys=True).encode("utf-8")).hexdigest()
    return f"{prefix}_{digest[:12]}"


def parse_uuid(value: Any) -> Optional[UUID]:
    if not value:
        return None
    try:
        return UUID(str(value))
    except (TypeError, ValueError):
        return None


def determine_intent(query: str) -> str:
    lowered = query.lower()
    if any(keyword in lowered for keyword in ("cart", "basket")):
        return "cart"
    if any(keyword in lowered for keyword in ("order", "status")):
        return "order"
    if any(keyword in lowered for keyword in ("price", "cost", "total")):
        return "pricing"
    if any(keyword in lowered for keyword in ("stock", "availability", "available")):
        return "inventory"
    if any(keyword in lowered for keyword in ("recommend", "suggest")):
        return "recommend"
    return "catalog_search"


def parse_tool_calls(raw: Any) -> List[ToolCall]:
    if not isinstance(raw, list):
        return []
    tool_calls: List[ToolCall] = []
    for item in raw:
        try:
            tool_calls.append(ToolCall.model_validate(item))
        except ValidationError:
            continue
    return tool_calls


def parse_items(raw_items: Any) -> List[ItemQuantity]:
    if not isinstance(raw_items, list):
        return []
    items: List[ItemQuantity] = []
    for raw in raw_items:
        if not isinstance(raw, dict):
            continue
        product_id = parse_uuid(raw.get("product_id") or raw.get("productId"))
        quantity = raw.get("quantity")
        if product_id and isinstance(quantity, int) and quantity > 0:
            items.append(ItemQuantity(product_id=product_id, quantity=quantity))
    return items


def normalize_candidates(product_id: UUID, candidates: List[UUID], limit: int) -> List[UUID]:
    seen = set()
    normalized: List[UUID] = []
    for candidate in candidates:
        if candidate == product_id or candidate in seen:
            continue
        seen.add(candidate)
        normalized.append(candidate)
        if len(normalized) >= limit:
            break
    return normalized


def sanitize_llm_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    return redactor.redact(payload)


def should_trip_breaker(result: ToolResult) -> bool:
    return not result.success and result.error in {"service_error", "request_error", "timeout", "unexpected_error"}


async def _execute_tool_call(tool_call: ToolCall, clients: ToolClients) -> ToolResult:
    name = tool_call.name
    args = tool_call.arguments or {}
    try:
        if name == "catalog.search":
            query = str(args.get("query", "")).strip()
            if not query:
                return ToolResult(name=name, success=False, error="query_required")
            category = args.get("category")
            limit = max(1, min(int(args.get("limit", 10)), 50))
            data = await clients.search_catalog(query, category, limit)
            return ToolResult(name=name, success=True, data=data)
        if name == "catalog.get_product":
            product_id = parse_uuid(args.get("product_id"))
            if not product_id:
                return ToolResult(name=name, success=False, error="product_id_required")
            data = await clients.get_product(product_id)
            return ToolResult(name=name, success=True, data=data)
        if name == "catalog.list_products":
            category = args.get("category")
            limit = max(1, min(int(args.get("limit", 10)), 50))
            data = await clients.list_products(category, limit)
            return ToolResult(name=name, success=True, data=data)
        if name == "pricing.calculate":
            items = parse_items(args.get("items"))
            if not items:
                return ToolResult(name=name, success=False, error="items_required")
            user_id = parse_uuid(args.get("user_id"))
            coupon_code = args.get("coupon_code")
            data = await clients.calculate_price(items, user_id, coupon_code)
            return ToolResult(name=name, success=True, data=data)
        if name == "pricing.get_product":
            product_id = parse_uuid(args.get("product_id"))
            if not product_id:
                return ToolResult(name=name, success=False, error="product_id_required")
            data = await clients.get_product_price(product_id)
            return ToolResult(name=name, success=True, data=data)
        if name == "inventory.check":
            store_id = args.get("store_id")
            items = parse_items(args.get("items"))
            if not store_id or not items:
                return ToolResult(name=name, success=False, error="store_id_and_items_required")
            data = await clients.check_inventory(str(store_id), items)
            return ToolResult(name=name, success=True, data=data)
        if name == "cart.get":
            user_id = parse_uuid(args.get("user_id"))
            data = await clients.get_cart(user_id)
            return ToolResult(name=name, success=True, data=data)
        if name == "order.get":
            order_id = parse_uuid(args.get("order_id"))
            if not order_id:
                return ToolResult(name=name, success=False, error="order_id_required")
            data = await clients.get_order(order_id)
            return ToolResult(name=name, success=True, data=data)
        return ToolResult(name=name, success=False, error="unsupported_tool")
    except httpx.HTTPStatusError as exc:
        return ToolResult(name=name, success=False, status_code=exc.response.status_code, error="service_error")
    except httpx.RequestError:
        return ToolResult(name=name, success=False, error="request_error")
    except Exception:
        return ToolResult(name=name, success=False, error="unexpected_error")


async def execute_tool_call(
    tool_call: ToolCall, clients: ToolClients, context: ToolExecutionContext
) -> ToolResult:
    name = tool_call.name
    if name not in context.guardrails.allowlist:
        return ToolResult(name=name, success=False, error="tool_not_allowed")
    breaker = context.breakers.get(name)
    if not breaker.allow_request():
        return ToolResult(name=name, success=False, error="circuit_open")
    start_time = time.monotonic()
    try:
        if context.tracer:
            with context.tracer.start_as_current_span("tool.call") as span:
                span.set_attribute("tool.name", name)
                result = await asyncio.wait_for(
                    _execute_tool_call(tool_call, clients),
                    timeout=context.guardrails.timeout_seconds,
                )
        else:
            result = await asyncio.wait_for(
                _execute_tool_call(tool_call, clients),
                timeout=context.guardrails.timeout_seconds,
            )
    except asyncio.TimeoutError:
        breaker.record_failure()
        result = ToolResult(name=name, success=False, error="timeout")
    if result.success:
        breaker.record_success()
    elif should_trip_breaker(result):
        breaker.record_failure()
    duration_ms = int((time.monotonic() - start_time) * 1000)
    log_event(
        "tool.call",
        request_id=context.request_id,
        tool=name,
        success=result.success,
        error=result.error,
        status_code=result.status_code,
        duration_ms=duration_ms,
    )
    return result


async def execute_tools(
    tool_calls: List[ToolCall], clients: ToolClients, context: ToolExecutionContext
) -> List[ToolResult]:
    if not tool_calls:
        return []
    results: List[Optional[ToolResult]] = [None] * len(tool_calls)
    tasks: List[Tuple[int, asyncio.Task[ToolResult]]] = []
    for index, call in enumerate(tool_calls):
        if call.name not in context.guardrails.allowlist:
            results[index] = ToolResult(name=call.name, success=False, error="tool_not_allowed")
            continue
        if not context.budget.reserve():
            results[index] = ToolResult(name=call.name, success=False, error="tool_budget_exceeded")
            continue
        tasks.append((index, asyncio.create_task(execute_tool_call(call, clients, context))))
    if tasks:
        for index, task in tasks:
            results[index] = await task
    return [result for result in results if result is not None]


def extract_product_ids(data: Dict[str, Any]) -> List[UUID]:
    results: List[UUID] = []
    candidates = data.get("results") or data.get("content") or []
    if isinstance(candidates, list):
        for item in candidates:
            if isinstance(item, dict):
                product_id = parse_uuid(item.get("id"))
                if product_id:
                    results.append(product_id)
    return results


def build_resources(app_instance: FastAPI) -> ExecutionResources:
    return ExecutionResources(
        clients=app_instance.state.clients,
        llm=app_instance.state.llm,
        retriever=app_instance.state.retriever,
        guardrails=app_instance.state.guardrails,
        breakers=app_instance.state.breakers,
        tracer=app_instance.state.tracer,
    )


def build_tool_context(resources: ExecutionResources, budget: ToolBudget, request_id: str) -> ToolExecutionContext:
    return ToolExecutionContext(
        guardrails=resources.guardrails,
        budget=budget,
        breakers=resources.breakers,
        request_id=request_id,
        tracer=resources.tracer,
    )


def route_by_mode(state: State) -> str:
    return "llm" if state.get("mode") == "llm" else "fallback"


async def assist_prepare(state: State) -> Dict[str, Any]:
    request: AssistRequest = state["request"]
    resources: ExecutionResources = state["resources"]
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "assist")
    retrieval_context = await resources.retriever.retrieve(request.query, payload)
    payload["retrieval_context"] = retrieval_context
    log_event(
        "assist.request",
        request_id=request_id,
        user_id=request.user_id,
        session_id=request.session_id,
        query=request.query,
    )
    return {"request_id": request_id, "payload": payload, "retrieval_context": retrieval_context}


async def assist_call_llm(state: State) -> Dict[str, Any]:
    resources: ExecutionResources = state["resources"]
    payload = state["payload"]
    llm_response = await resources.llm.generate("assist", sanitize_llm_payload(payload))
    message = llm_response.get("message") if isinstance(llm_response, dict) else None
    mode = "llm" if isinstance(message, str) and message.strip() else "fallback"
    return {"llm_response": llm_response, "mode": mode}


def assist_from_llm(state: State) -> Dict[str, Any]:
    llm_response = state.get("llm_response") or {}
    tool_calls = parse_tool_calls(llm_response.get("tool_calls"))
    intent = llm_response.get("intent") or "assist"
    message = llm_response.get("message") or "Ready to assist."
    log_event(
        "assist.llm",
        request_id=state.get("request_id"),
        tool_calls=len(tool_calls),
    )
    return {"tool_calls": tool_calls, "intent": str(intent), "message": str(message), "mode": "llm"}


def assist_fallback(state: State) -> Dict[str, Any]:
    request: AssistRequest = state["request"]
    intent = determine_intent(request.query)
    tool_calls: List[ToolCall] = []
    message = "Ready to assist."
    context = request.context
    if intent == "cart":
        if request.user_id:
            tool_calls.append(ToolCall(name="cart.get", arguments={"user_id": str(request.user_id)}))
            message = "Fetching cart details."
        else:
            message = "Provide a user_id to retrieve cart details."
    elif intent == "order":
        if context.order_id:
            tool_calls.append(ToolCall(name="order.get", arguments={"order_id": str(context.order_id)}))
            message = "Fetching order details."
        else:
            message = "Provide an order_id to retrieve order details."
    elif intent == "pricing":
        if context.items:
            tool_calls.append(
                ToolCall(
                    name="pricing.calculate",
                    arguments={
                        "items": [item.model_dump(mode="json") for item in context.items],
                        "user_id": str(request.user_id) if request.user_id else None,
                        "coupon_code": context.coupon_code,
                    },
                )
            )
            message = "Calculating pricing."
        elif context.product_id:
            tool_calls.append(ToolCall(name="pricing.get_product", arguments={"product_id": str(context.product_id)}))
            message = "Fetching product pricing."
        else:
            message = "Provide items or a product_id for pricing assistance."
    elif intent == "inventory":
        if context.store_id and context.items:
            tool_calls.append(
                ToolCall(
                    name="inventory.check",
                    arguments={
                        "store_id": context.store_id,
                        "items": [item.model_dump(mode="json") for item in context.items],
                    },
                )
            )
            message = "Checking inventory availability."
        else:
            message = "Provide store_id and items to check inventory."
    elif intent == "recommend":
        message = "Use /agent/recommend for recommendation requests."
    else:
        tool_calls.append(
            ToolCall(
                name="catalog.search",
                arguments={"query": request.query, "category": context.category, "limit": 5},
            )
        )
        message = "Searching the catalog."
    return {"intent": intent, "tool_calls": tool_calls, "message": message, "mode": "fallback"}


async def execute_tools_node(state: State) -> Dict[str, Any]:
    request = state["request"]
    resources: ExecutionResources = state["resources"]
    tool_calls = state.get("tool_calls", [])
    tool_results: List[ToolResult] = []
    if request.execute_tools:
        tool_context = build_tool_context(resources, state["budget"], state["request_id"])
        tool_results = await execute_tools(tool_calls, resources.clients, tool_context)
    return {"tool_results": tool_results}


def assist_build_response(state: State) -> Dict[str, Any]:
    response = AssistResponse(
        request_id=state["request_id"],
        mode=state["mode"],
        intent=state["intent"],
        message=state["message"],
        tool_calls=state.get("tool_calls", []),
        tool_results=state.get("tool_results", []),
    )
    log_event(
        "assist.response",
        request_id=state["request_id"],
        mode=state["mode"],
        intent=state["intent"],
        tool_calls=len(state.get("tool_calls", [])),
        tool_results=len(state.get("tool_results", [])),
    )
    return {"response": response}


async def substitute_prepare(state: State) -> Dict[str, Any]:
    request: SubstituteRequest = state["request"]
    resources: ExecutionResources = state["resources"]
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "substitute")
    retrieval_query = f"substitute {request.product_id}"
    retrieval_context = await resources.retriever.retrieve(retrieval_query, payload)
    payload["retrieval_context"] = retrieval_context
    log_event(
        "substitute.request",
        request_id=request_id,
        product_id=request.product_id,
        store_id=request.store_id,
    )
    return {"request_id": request_id, "payload": payload, "retrieval_context": retrieval_context}


async def substitute_call_llm(state: State) -> Dict[str, Any]:
    resources: ExecutionResources = state["resources"]
    payload = state["payload"]
    llm_response = await resources.llm.generate("substitute", sanitize_llm_payload(payload))
    message = llm_response.get("message") if isinstance(llm_response, dict) else None
    mode = "llm" if isinstance(message, str) and message.strip() else "fallback"
    return {"llm_response": llm_response, "mode": mode}


def substitute_from_llm(state: State) -> Dict[str, Any]:
    llm_response = state.get("llm_response") or {}
    tool_calls = parse_tool_calls(llm_response.get("tool_calls"))
    substitute_id = parse_uuid(llm_response.get("substitute_product_id"))
    candidate_ids = [cid for cid in (parse_uuid(cid) for cid in llm_response.get("candidate_ids", [])) if cid]
    intent = llm_response.get("intent") or "substitute"
    message = llm_response.get("message") or "Processing substitute request."
    log_event(
        "substitute.llm",
        request_id=state.get("request_id"),
        tool_calls=len(tool_calls),
    )
    return {
        "tool_calls": tool_calls,
        "intent": str(intent),
        "message": str(message),
        "candidate_ids": candidate_ids,
        "substitute_product_id": substitute_id,
        "mode": "llm",
    }


def substitute_fallback(state: State) -> Dict[str, Any]:
    request: SubstituteRequest = state["request"]
    candidate_ids = normalize_candidates(request.product_id, request.candidate_ids, request.limit)
    substitute_id = candidate_ids[0] if candidate_ids else None
    tool_calls: List[ToolCall] = []
    message = "No substitute candidates provided."
    if candidate_ids:
        message = "Selected a substitute candidate."
    if request.store_id and candidate_ids:
        tool_calls.append(
            ToolCall(
                name="inventory.check",
                arguments={
                    "store_id": request.store_id,
                    "items": [{"product_id": str(candidate_id), "quantity": 1} for candidate_id in candidate_ids],
                },
            )
        )
    return {
        "tool_calls": tool_calls,
        "message": message,
        "intent": "substitute",
        "candidate_ids": candidate_ids,
        "substitute_product_id": substitute_id,
        "mode": "fallback",
    }


def substitute_post_process(state: State) -> Dict[str, Any]:
    if state.get("mode") != "fallback":
        return {}
    if not state.get("tool_results"):
        return {}
    tool_results: List[ToolResult] = state["tool_results"]
    candidate_ids: List[UUID] = state.get("candidate_ids", [])
    substitute_id: Optional[UUID] = state.get("substitute_product_id")
    first_result = tool_results[0]
    if first_result.success and first_result.data:
        available_ids = []
        for item in first_result.data.get("items", []):
            if isinstance(item, dict) and item.get("sufficient"):
                available_id = parse_uuid(item.get("productId") or item.get("product_id"))
                if available_id:
                    available_ids.append(available_id)
        for candidate in candidate_ids:
            if candidate in available_ids:
                substitute_id = candidate
                break
    return {"substitute_product_id": substitute_id}


def substitute_build_response(state: State) -> Dict[str, Any]:
    response = SubstituteResponse(
        request_id=state["request_id"],
        mode=state["mode"],
        intent=state["intent"],
        message=state["message"],
        tool_calls=state.get("tool_calls", []),
        tool_results=state.get("tool_results", []),
        substitute_product_id=state.get("substitute_product_id"),
        candidate_ids=state.get("candidate_ids", []),
    )
    log_event(
        "substitute.response",
        request_id=state["request_id"],
        mode=state["mode"],
        intent=state["intent"],
        tool_calls=len(state.get("tool_calls", [])),
        tool_results=len(state.get("tool_results", [])),
    )
    return {"response": response}


async def recommend_prepare(state: State) -> Dict[str, Any]:
    request: RecommendRequest = state["request"]
    resources: ExecutionResources = state["resources"]
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "recommend")
    retrieval_query = request.category or "recommendations"
    retrieval_context = await resources.retriever.retrieve(retrieval_query, payload)
    payload["retrieval_context"] = retrieval_context
    log_event(
        "recommend.request",
        request_id=request_id,
        user_id=request.user_id,
        category=request.category,
    )
    return {"request_id": request_id, "payload": payload, "retrieval_context": retrieval_context}


async def recommend_call_llm(state: State) -> Dict[str, Any]:
    resources: ExecutionResources = state["resources"]
    payload = state["payload"]
    llm_response = await resources.llm.generate("recommend", sanitize_llm_payload(payload))
    message = llm_response.get("message") if isinstance(llm_response, dict) else None
    mode = "llm" if isinstance(message, str) and message.strip() else "fallback"
    return {"llm_response": llm_response, "mode": mode}


def recommend_from_llm(state: State) -> Dict[str, Any]:
    llm_response = state.get("llm_response") or {}
    tool_calls = parse_tool_calls(llm_response.get("tool_calls"))
    recommended_ids = [
        parsed_id for parsed_id in (parse_uuid(pid) for pid in llm_response.get("recommended_product_ids", [])) if parsed_id
    ]
    intent = llm_response.get("intent") or "recommend"
    message = llm_response.get("message") or "Generating recommendations."
    log_event(
        "recommend.llm",
        request_id=state.get("request_id"),
        tool_calls=len(tool_calls),
    )
    return {
        "tool_calls": tool_calls,
        "intent": str(intent),
        "message": str(message),
        "recommended_product_ids": recommended_ids,
        "mode": "llm",
    }


def recommend_fallback(state: State) -> Dict[str, Any]:
    request: RecommendRequest = state["request"]
    seen = set()
    recommended_ids: List[UUID] = []
    if request.seed_product_id:
        seen.add(request.seed_product_id)
        recommended_ids.append(request.seed_product_id)
    for product_id in request.recent_product_ids:
        if product_id not in seen:
            seen.add(product_id)
            recommended_ids.append(product_id)
        if len(recommended_ids) >= request.limit:
            break
    tool_calls: List[ToolCall] = []
    message = "Generated deterministic recommendations."
    if len(recommended_ids) < request.limit and request.category:
        tool_calls.append(
            ToolCall(
                name="catalog.list_products",
                arguments={"category": request.category, "limit": request.limit},
            )
        )
        message = "Querying catalog recommendations."
    return {
        "tool_calls": tool_calls,
        "intent": "recommend",
        "message": message,
        "recommended_product_ids": recommended_ids,
        "mode": "fallback",
    }


def recommend_post_process(state: State) -> Dict[str, Any]:
    if state.get("mode") != "fallback":
        return {}
    if not state.get("tool_results"):
        return {}
    tool_results: List[ToolResult] = state["tool_results"]
    recommended_ids: List[UUID] = state.get("recommended_product_ids", [])
    seen = set(recommended_ids)
    first_result = tool_results[0]
    if first_result.success and first_result.data:
        for product_id in extract_product_ids(first_result.data):
            if product_id not in seen:
                recommended_ids.append(product_id)
            if len(recommended_ids) >= state["request"].limit:
                break
    return {"recommended_product_ids": recommended_ids}


def recommend_build_response(state: State) -> Dict[str, Any]:
    response = RecommendResponse(
        request_id=state["request_id"],
        mode=state["mode"],
        intent=state["intent"],
        message=state["message"],
        tool_calls=state.get("tool_calls", []),
        tool_results=state.get("tool_results", []),
        recommended_product_ids=state.get("recommended_product_ids", []),
    )
    log_event(
        "recommend.response",
        request_id=state["request_id"],
        mode=state["mode"],
        intent=state["intent"],
        tool_calls=len(state.get("tool_calls", [])),
        tool_results=len(state.get("tool_results", [])),
    )
    return {"response": response}


def build_assist_graph() -> StateGraph:
    graph = StateGraph()
    graph.add_node("prepare", assist_prepare)
    graph.add_node("call_llm", assist_call_llm)
    graph.add_node("llm", assist_from_llm)
    graph.add_node("fallback", assist_fallback)
    graph.add_node("tools", execute_tools_node)
    graph.add_node("response", assist_build_response)
    graph.set_entrypoint("prepare")
    graph.add_edge("prepare", "call_llm")
    graph.add_conditional_edges("call_llm", route_by_mode, {"llm": "llm", "fallback": "fallback"})
    graph.add_edge("llm", "tools")
    graph.add_edge("fallback", "tools")
    graph.add_edge("tools", "response")
    graph.add_edge("response", END)
    return graph


def build_substitute_graph() -> StateGraph:
    graph = StateGraph()
    graph.add_node("prepare", substitute_prepare)
    graph.add_node("call_llm", substitute_call_llm)
    graph.add_node("llm", substitute_from_llm)
    graph.add_node("fallback", substitute_fallback)
    graph.add_node("tools", execute_tools_node)
    graph.add_node("post", substitute_post_process)
    graph.add_node("response", substitute_build_response)
    graph.set_entrypoint("prepare")
    graph.add_edge("prepare", "call_llm")
    graph.add_conditional_edges("call_llm", route_by_mode, {"llm": "llm", "fallback": "fallback"})
    graph.add_edge("llm", "tools")
    graph.add_edge("fallback", "tools")
    graph.add_edge("tools", "post")
    graph.add_edge("post", "response")
    graph.add_edge("response", END)
    return graph


def build_recommend_graph() -> StateGraph:
    graph = StateGraph()
    graph.add_node("prepare", recommend_prepare)
    graph.add_node("call_llm", recommend_call_llm)
    graph.add_node("llm", recommend_from_llm)
    graph.add_node("fallback", recommend_fallback)
    graph.add_node("tools", execute_tools_node)
    graph.add_node("post", recommend_post_process)
    graph.add_node("response", recommend_build_response)
    graph.set_entrypoint("prepare")
    graph.add_edge("prepare", "call_llm")
    graph.add_conditional_edges("call_llm", route_by_mode, {"llm": "llm", "fallback": "fallback"})
    graph.add_edge("llm", "tools")
    graph.add_edge("fallback", "tools")
    graph.add_edge("tools", "post")
    graph.add_edge("post", "response")
    graph.add_edge("response", END)
    return graph


ASSIST_GRAPH = build_assist_graph()
SUBSTITUTE_GRAPH = build_substitute_graph()
RECOMMEND_GRAPH = build_recommend_graph()


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    clients = ToolClients(settings)
    llm_client = LlmClient(settings)
    retriever = CachedRetrievalProvider(
        ttl_seconds=settings.rag_cache_ttl_seconds,
        max_entries=settings.rag_cache_max_entries,
        max_results=settings.rag_max_results,
    )
    guardrails = ToolGuardrails(
        allowlist=set(settings.tool_allowlist),
        max_calls=settings.tool_call_max,
        max_total_seconds=settings.tool_total_timeout_seconds,
        timeout_seconds=settings.tool_call_timeout_seconds,
    )
    breakers = CircuitBreakerRegistry(
        failure_threshold=settings.tool_circuit_breaker_failures,
        reset_timeout=settings.tool_circuit_breaker_reset_seconds,
    )
    app_instance.state.clients = clients
    app_instance.state.llm = llm_client
    app_instance.state.retriever = retriever
    app_instance.state.guardrails = guardrails
    app_instance.state.breakers = breakers
    app_instance.state.agent_ip_rate_limiter = TokenBucketRateLimiter(
        rate_per_minute=settings.agent_ip_rate_limit_per_minute,
        burst=settings.agent_ip_rate_limit_burst,
    )
    app_instance.state.agent_user_rate_limiter = TokenBucketRateLimiter(
        rate_per_minute=settings.agent_user_rate_limit_per_minute,
        burst=settings.agent_user_rate_limit_burst,
    )
    app_instance.state.agent_request_semaphore = asyncio.Semaphore(settings.agent_max_inflight_requests)
    try:
        yield
    finally:
        await clients.close()
        await llm_client.close()


app = FastAPI(title=settings.app_name, lifespan=lifespan)
app.state.tracer = init_telemetry(app)

from app.auth import InternalServiceAuthMiddleware
app.add_middleware(InternalServiceAuthMiddleware)


def _extract_client_ip(request: Request) -> str:
    if settings.agent_trust_forwarded_for:
        forwarded_for = request.headers.get("x-forwarded-for")
        if forwarded_for:
            return forwarded_for.split(",", maxsplit=1)[0].strip()
    if request.client and request.client.host:
        return request.client.host
    return "unknown"


async def _extract_user_subject(request: Request) -> Optional[str]:
    explicit_user = request.headers.get(settings.user_id_header_name)
    if explicit_user:
        return explicit_user.strip()
    content_type = request.headers.get("content-type", "")
    if "application/json" not in content_type.lower():
        return None
    try:
        payload = await request.json()
    except (json.JSONDecodeError, TypeError, ValueError):
        return None
    if not isinstance(payload, dict):
        return None
    raw_user_id = payload.get("user_id")
    if raw_user_id is None:
        return None
    return str(raw_user_id).strip() or None


def _rate_limited_response(scope: str, principal: str, meta: Dict[str, Any]) -> JSONResponse:
    retry_after_seconds = max(1, math.ceil(float(meta.get("retry_after_seconds", 1.0))))
    principal_hash = hashlib.sha256(principal.encode("utf-8")).hexdigest()[:12]
    log_event(
        "agent_rate_limited",
        level=logging.WARNING,
        scope=scope,
        principal_hash=principal_hash,
        retry_after_seconds=retry_after_seconds,
    )
    return JSONResponse(
        status_code=429,
        content={"detail": "Rate limit exceeded"},
        headers={
            "Retry-After": str(retry_after_seconds),
            "X-Agent-RateLimit-Scope": scope,
            "X-Agent-RateLimit-Limit": str(meta.get("limit", 0)),
            "X-Agent-RateLimit-Remaining": str(int(meta.get("remaining_tokens", 0))),
        },
    )


@app.middleware("http")
async def enforce_agent_guardrails(request: Request, call_next):
    if not request.url.path.startswith("/agent/"):
        return await call_next(request)

    client_ip = _extract_client_ip(request)
    ip_allowed, ip_meta = request.app.state.agent_ip_rate_limiter.allow(client_ip)
    if not ip_allowed:
        return _rate_limited_response("ip", client_ip, ip_meta)

    user_subject = await _extract_user_subject(request)
    user_meta: Optional[Dict[str, Any]] = None
    if user_subject:
        user_allowed, user_meta = request.app.state.agent_user_rate_limiter.allow(user_subject)
        if not user_allowed:
            return _rate_limited_response("user", user_subject, user_meta)

    semaphore = request.app.state.agent_request_semaphore
    acquired = False
    try:
        await asyncio.wait_for(
            semaphore.acquire(),
            timeout=max(settings.agent_queue_acquire_timeout_ms, 1) / 1000.0,
        )
        acquired = True
    except asyncio.TimeoutError:
        log_event("agent_backpressure_rejected", level=logging.WARNING, path=request.url.path)
        return JSONResponse(
            status_code=503,
            content={"detail": "Agent service is saturated; retry shortly"},
            headers={"Retry-After": "1"},
        )

    try:
        response = await call_next(request)
    finally:
        if acquired:
            semaphore.release()

    response.headers["X-Agent-IP-Limit"] = str(ip_meta.get("limit", 0))
    response.headers["X-Agent-IP-Remaining"] = str(int(ip_meta.get("remaining_tokens", 0)))
    if user_meta:
        response.headers["X-Agent-User-Limit"] = str(user_meta.get("limit", 0))
        response.headers["X-Agent-User-Remaining"] = str(int(user_meta.get("remaining_tokens", 0)))
    return response


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/health/ready")
async def readiness() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/health/live")
async def liveness() -> Dict[str, str]:
    return {"status": "ok"}


@app.post("/agent/assist", response_model=AssistResponse)
async def assist(request: AssistRequest) -> AssistResponse:
    resources = build_resources(app)
    budget = ToolBudget(resources.guardrails.max_calls, resources.guardrails.max_total_seconds)
    state = {"request": request, "resources": resources, "budget": budget}
    result = await ASSIST_GRAPH.ainvoke(state)
    return result["response"]


@app.post("/agent/substitute", response_model=SubstituteResponse)
async def substitute(request: SubstituteRequest) -> SubstituteResponse:
    resources = build_resources(app)
    budget = ToolBudget(resources.guardrails.max_calls, resources.guardrails.max_total_seconds)
    state = {"request": request, "resources": resources, "budget": budget}
    result = await SUBSTITUTE_GRAPH.ainvoke(state)
    return result["response"]


@app.post("/agent/recommend", response_model=RecommendResponse)
async def recommend(request: RecommendRequest) -> RecommendResponse:
    resources = build_resources(app)
    budget = ToolBudget(resources.guardrails.max_calls, resources.guardrails.max_total_seconds)
    state = {"request": request, "resources": resources, "budget": budget}
    result = await RECOMMEND_GRAPH.ainvoke(state)
    return result["response"]
