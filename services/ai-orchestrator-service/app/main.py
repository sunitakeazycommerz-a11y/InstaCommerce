import asyncio
import hashlib
import json
import logging
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Literal, Optional
from uuid import UUID

import httpx
from fastapi import FastAPI
from pydantic import BaseModel, Field, ValidationError

from app.config import Settings

settings = Settings()
logger = logging.getLogger("ai_orchestrator")


def configure_logging() -> None:
    level = getattr(logging, settings.log_level.upper(), logging.INFO)
    logging.basicConfig(level=level, format="%(asctime)s %(levelname)s %(name)s %(message)s")


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


class ToolClients:
    def __init__(self, config: Settings) -> None:
        headers: Dict[str, str] = {
            "X-Internal-Service": config.internal_service_name,
        }
        if config.internal_service_token:
            headers["X-Internal-Token"] = config.internal_service_token
        self._headers = headers
        self._timeout = httpx.Timeout(config.request_timeout_seconds)
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


async def execute_tool_call(tool_call: ToolCall, clients: ToolClients) -> ToolResult:
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


async def execute_tools(tool_calls: List[ToolCall], clients: ToolClients) -> List[ToolResult]:
    if not tool_calls:
        return []
    tasks = [execute_tool_call(call, clients) for call in tool_calls]
    return list(await asyncio.gather(*tasks))


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


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    clients = ToolClients(settings)
    llm_client = LlmClient(settings)
    app_instance.state.clients = clients
    app_instance.state.llm = llm_client
    try:
        yield
    finally:
        await clients.close()
        await llm_client.close()


app = FastAPI(title=settings.app_name, lifespan=lifespan)


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "ok"}


@app.post("/agent/assist", response_model=AssistResponse)
async def assist(request: AssistRequest) -> AssistResponse:
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "assist")
    llm_response = await app.state.llm.generate("assist", payload)
    if llm_response:
        message = llm_response.get("message")
        if isinstance(message, str) and message.strip():
            tool_calls = parse_tool_calls(llm_response.get("tool_calls"))
            tool_results = await execute_tools(tool_calls, app.state.clients) if request.execute_tools else []
            intent = llm_response.get("intent") or "assist"
            return AssistResponse(
                request_id=request_id,
                mode="llm",
                intent=str(intent),
                message=message,
                tool_calls=tool_calls,
                tool_results=tool_results,
            )
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
    tool_results = await execute_tools(tool_calls, app.state.clients) if request.execute_tools else []
    return AssistResponse(
        request_id=request_id,
        mode="fallback",
        intent=intent,
        message=message,
        tool_calls=tool_calls,
        tool_results=tool_results,
    )


@app.post("/agent/substitute", response_model=SubstituteResponse)
async def substitute(request: SubstituteRequest) -> SubstituteResponse:
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "substitute")
    llm_response = await app.state.llm.generate("substitute", payload)
    if llm_response:
        message = llm_response.get("message")
        if isinstance(message, str) and message.strip():
            tool_calls = parse_tool_calls(llm_response.get("tool_calls"))
            tool_results = await execute_tools(tool_calls, app.state.clients) if request.execute_tools else []
            substitute_id = parse_uuid(llm_response.get("substitute_product_id"))
            candidate_ids = [cid for cid in (parse_uuid(cid) for cid in llm_response.get("candidate_ids", [])) if cid]
            return SubstituteResponse(
                request_id=request_id,
                mode="llm",
                intent=llm_response.get("intent") or "substitute",
                message=message,
                tool_calls=tool_calls,
                tool_results=tool_results,
                substitute_product_id=substitute_id,
                candidate_ids=candidate_ids,
            )
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
                    "items": [
                        {"product_id": str(candidate_id), "quantity": 1} for candidate_id in candidate_ids
                    ],
                },
            )
        )
    tool_results = await execute_tools(tool_calls, app.state.clients) if request.execute_tools else []
    if request.execute_tools and tool_results:
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
    return SubstituteResponse(
        request_id=request_id,
        mode="fallback",
        intent="substitute",
        message=message,
        tool_calls=tool_calls,
        tool_results=tool_results,
        substitute_product_id=substitute_id,
        candidate_ids=candidate_ids,
    )


@app.post("/agent/recommend", response_model=RecommendResponse)
async def recommend(request: RecommendRequest) -> RecommendResponse:
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "recommend")
    llm_response = await app.state.llm.generate("recommend", payload)
    if llm_response:
        message = llm_response.get("message")
        if isinstance(message, str) and message.strip():
            tool_calls = parse_tool_calls(llm_response.get("tool_calls"))
            tool_results = await execute_tools(tool_calls, app.state.clients) if request.execute_tools else []
            recommended_ids = [
                parsed_id
                for parsed_id in (parse_uuid(pid) for pid in llm_response.get("recommended_product_ids", []))
                if parsed_id
            ]
            return RecommendResponse(
                request_id=request_id,
                mode="llm",
                intent=llm_response.get("intent") or "recommend",
                message=message,
                tool_calls=tool_calls,
                tool_results=tool_results,
                recommended_product_ids=recommended_ids,
            )
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
    tool_results = await execute_tools(tool_calls, app.state.clients) if request.execute_tools else []
    if request.execute_tools and tool_results:
        first_result = tool_results[0]
        if first_result.success and first_result.data:
            for product_id in extract_product_ids(first_result.data):
                if product_id not in seen:
                    recommended_ids.append(product_id)
                if len(recommended_ids) >= request.limit:
                    break
    return RecommendResponse(
        request_id=request_id,
        mode="fallback",
        intent="recommend",
        message=message,
        tool_calls=tool_calls,
        tool_results=tool_results,
        recommended_product_ids=recommended_ids,
    )
