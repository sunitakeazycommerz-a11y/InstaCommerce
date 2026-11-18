"""FastAPI v1 agent endpoints unified over AgentExecutionRunner and ToolRegistry."""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Literal, Optional
from uuid import UUID

from fastapi import APIRouter

from app.api.handlers import (
    get_audit_publisher,
    get_checkpoint_saver,
    get_execution_runner,
    get_graph,
    get_llm_client,
    get_registry,
    get_retriever,
    get_semantic_cache,
)
from app.config import Settings
from app.core.llm import (
    derive_request_id,
    extract_product_ids,
    normalize_candidates,
    parse_tool_calls,
    parse_uuid,
)
from app.core.logging import log_event, sanitize_llm_payload
from app.graph.budgets import token_cost
from app.graph.nodes import _plan_tools
from app.graph.state import AgentState, IntentType
from app.graph.state import ToolResult as GraphToolResult
from app.guardrails import InjectionDetector, PIIVault
from app.schemas import (
    AssistRequest,
    AssistResponse,
    RecommendRequest,
    RecommendResponse,
    SubstituteRequest,
    SubstituteResponse,
    ToolCall,
    ToolResult,
)

logger = logging.getLogger("ai_orchestrator.api.v1")

router = APIRouter(tags=["agent-v1"])

_v1_injection_detector = InjectionDetector()
_v1_pii_vault = PIIVault()
_settings = Settings()


def _to_v1_tool_results(graph_results: List[GraphToolResult]) -> List[ToolResult]:
    return [
        ToolResult(
            name=tr.tool_name,
            success=tr.success,
            data=tr.data or None,
            error=tr.error,
        )
        for tr in graph_results
    ]


async def _publish_v1_audit(
    *,
    request_id: str,
    user_id: Optional[str],
    session_id: Optional[str],
    intent: str,
    mode: str,
    query_length: int,
    tool_calls_count: int,
    tool_results_count: int,
    escalated: bool,
    response_length: int,
) -> None:
    publisher = get_audit_publisher()
    if publisher is None:
        return
    try:
        await publisher.publish_intent_classified(
            request_id=request_id,
            user_id=user_id or None,
            session_id=session_id,
            intent=intent,
            confidence=0.0,
            mode=mode,
            query_length=query_length,
        )
        await publisher.publish_response_generated(
            request_id=request_id,
            user_id=user_id or None,
            session_id=session_id,
            intent=intent,
            mode=mode,
            tool_calls_count=tool_calls_count,
            tool_results_count=tool_results_count,
            escalated=escalated,
            response_length=response_length,
        )
    except Exception:
        logger.debug("audit.v1_publish_failed", exc_info=True)


class _AssistGraphAdapter:
    """Runs optional semantic-cache / LLM step before the shared compiled LangGraph."""

    def __init__(self, compiled_graph: Any, request: AssistRequest) -> None:
        self._graph = compiled_graph
        self._request = request
        self.mode: Literal["fallback", "llm"] = "fallback"
        self.override_intent: Optional[str] = None

    async def ainvoke(self, state_dict: Dict[str, Any]) -> Dict[str, Any]:
        updated = dict(state_dict)
        semantic_cache = get_semantic_cache()
        llm_client = get_llm_client()
        retriever = get_retriever()

        query = str(updated.get("query", ""))
        if semantic_cache is not None:
            match = semantic_cache.find_match(query)
            if match:
                log_event(
                    "assist.semantic_cache_hit",
                    request_id=updated.get("request_id"),
                    similarity=match["similarity"],
                    category=match.get("category"),
                )
                updated["response"] = str(match["answer"])
                updated["intent"] = IntentType.SUPPORT.value
                self.mode = "llm"
                self.override_intent = IntentType.SUPPORT.value

        if self.mode != "llm" and llm_client is not None:
            payload = self._request.model_dump(mode="json")
            payload["query"] = query
            if retriever is not None:
                payload["retrieval_context"] = await retriever.retrieve(query, payload)
            llm_resp = await llm_client.generate("assist", sanitize_llm_payload(payload))
            message = llm_resp.get("message") if isinstance(llm_resp, dict) else None
            if isinstance(message, str) and message.strip():
                self.mode = "llm"
                updated["response"] = message
                if llm_resp and llm_resp.get("intent"):
                    self.override_intent = str(llm_resp["intent"])
                usage = llm_resp.get("usage") if isinstance(llm_resp, dict) else None
                if isinstance(usage, dict):
                    in_tok = int(usage.get("prompt_tokens", 0) or 0)
                    out_tok = int(usage.get("completion_tokens", 0) or 0)
                else:
                    in_tok = max(1, len(query) // 4)
                    out_tok = max(1, len(message) // 4)
                model_name = _settings.llm_model or "default"
                updated["total_tokens_used"] = int(updated.get("total_tokens_used", 0)) + in_tok + out_tok
                updated["total_cost_usd"] = float(updated.get("total_cost_usd", 0.0)) + token_cost(
                    model_name, in_tok, out_tok
                )

        return await self._graph.ainvoke(updated)


@router.post("/agent/assist", response_model=AssistResponse)
async def assist(request: AssistRequest) -> AssistResponse:
    is_inj, _, inj_reason = _v1_injection_detector.check(request.query)
    if is_inj:
        request_id = derive_request_id(request.request_id, {"query": request.query}, "assist")
        log_event(
            "assist.injection_blocked",
            level=logging.WARNING,
            request_id=request_id,
            reason=inj_reason,
        )
        return AssistResponse(
            request_id=request_id,
            mode="fallback",
            intent="unknown",
            message=(
                "Your request could not be processed automatically. "
                "Connecting you with a support specialist."
            ),
        )

    redacted_query, _ = _v1_pii_vault.redact(request.query)
    sanitized_request = request.model_copy(update={"query": redacted_query})
    payload = sanitized_request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "assist")

    raw_context = sanitized_request.context.model_dump(mode="json", exclude_none=True)
    redacted_context = _v1_pii_vault.redact_value(raw_context)
    context_dict = redacted_context if isinstance(redacted_context, dict) else {}

    initial_state = AgentState(
        request_id=request_id,
        query=redacted_query,
        user_id=str(request.user_id) if request.user_id else "",
        session_id=request.session_id,
        context=context_dict,
        execute_tools=request.execute_tools,
        max_cost_usd=0.50,
    )

    compiled_graph = await get_graph()
    adapter = _AssistGraphAdapter(compiled_graph, sanitized_request)
    runner = get_execution_runner()
    result = await runner.run(
        adapter,
        initial_state,
        checkpointer=get_checkpoint_saver(),
    )

    if result.needs_escalation and "budget" in (result.escalation_reason or "").lower():
        return AssistResponse(
            request_id=request_id,
            mode="fallback",
            intent="budget_exceeded",
            message="Session processing budget exceeded. Connecting you with a support specialist.",
        )

    planned_calls = [
        ToolCall(
            name=str(plan["tool"]),
            arguments=dict(plan.get("params") or plan.get("body") or plan.get("path_params") or {}),
        )
        for plan in _plan_tools(result)
    ]
    v1_results = _to_v1_tool_results(result.tool_results)
    final_intent = adapter.override_intent or result.intent.value
    final_message = result.response
    if result.needs_escalation and "output policy violations" in (result.escalation_reason or "").lower():
        final_message = "Connecting you with a support specialist to review this request."

    response = AssistResponse(
        request_id=result.request_id,
        mode=adapter.mode,
        intent=final_intent,
        message=final_message,
        tool_calls=planned_calls,
        tool_results=v1_results,
    )
    log_event(
        "assist.response",
        request_id=response.request_id,
        mode=response.mode,
        intent=response.intent,
        tool_calls=len(response.tool_calls),
        tool_results=len(response.tool_results),
    )
    await _publish_v1_audit(
        request_id=response.request_id,
        user_id=str(request.user_id) if request.user_id else None,
        session_id=request.session_id,
        intent=response.intent,
        mode=response.mode,
        query_length=len(redacted_query),
        tool_calls_count=len(response.tool_calls),
        tool_results_count=len(response.tool_results),
        escalated=result.needs_escalation,
        response_length=len(response.message),
    )
    return response


@router.post("/agent/substitute", response_model=SubstituteResponse)
async def substitute(request: SubstituteRequest) -> SubstituteResponse:
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "substitute")
    retriever = get_retriever()
    if retriever is not None:
        payload["retrieval_context"] = await retriever.retrieve(
            f"substitute {request.product_id}", payload
        )

    llm_client = get_llm_client()
    llm_resp = (
        await llm_client.generate("substitute", sanitize_llm_payload(payload))
        if llm_client is not None
        else None
    )
    llm_msg = llm_resp.get("message") if isinstance(llm_resp, dict) else None
    mode: Literal["fallback", "llm"] = (
        "llm" if isinstance(llm_msg, str) and llm_msg.strip() else "fallback"
    )

    if mode == "llm" and isinstance(llm_resp, dict):
        tool_calls = parse_tool_calls(llm_resp.get("tool_calls"))
        substitute_id = parse_uuid(llm_resp.get("substitute_product_id"))
        candidate_ids = [
            cid for cid in (parse_uuid(c) for c in llm_resp.get("candidate_ids", [])) if cid
        ]
        intent = str(llm_resp.get("intent") or "substitute")
        message = str(llm_msg)
    else:
        candidate_ids = normalize_candidates(request.product_id, request.candidate_ids, request.limit)
        substitute_id = candidate_ids[0] if candidate_ids else None
        tool_calls = []
        message = "Selected a substitute candidate." if candidate_ids else "No substitute candidates provided."
        intent = "substitute"
        if request.store_id and candidate_ids:
            tool_calls.append(
                ToolCall(
                    name="inventory.check",
                    arguments={
                        "store_id": request.store_id,
                        "items": [{"product_id": str(cid), "quantity": 1} for cid in candidate_ids],
                    },
                )
            )

    tool_results: List[ToolResult] = []
    if request.execute_tools and tool_calls:
        registry = await get_registry()
        for call in tool_calls:
            if call.name == "inventory.check":
                res = await registry.call(
                    "inventory.check",
                    body={
                        "storeId": call.arguments.get("store_id"),
                        "items": [
                            {"productId": item["product_id"], "quantity": item["quantity"]}
                            for item in call.arguments.get("items", [])
                        ],
                    },
                )
            else:
                res = await registry.call(call.name, body=call.arguments)
            tool_results.append(
                ToolResult(
                    name=res.tool_name,
                    success=res.success,
                    data=res.data or None,
                    error=res.error,
                )
            )

    if mode == "fallback" and tool_results and tool_results[0].success and tool_results[0].data:
        available_ids: List[UUID] = []
        for item in tool_results[0].data.get("items", []):
            if isinstance(item, dict) and item.get("sufficient"):
                avail_id = parse_uuid(item.get("productId") or item.get("product_id"))
                if avail_id:
                    available_ids.append(avail_id)
        for cand in candidate_ids:
            if cand in available_ids:
                substitute_id = cand
                break

    response = SubstituteResponse(
        request_id=request_id,
        mode=mode,
        intent=intent,
        message=message,
        tool_calls=tool_calls,
        tool_results=tool_results,
        substitute_product_id=substitute_id,
        candidate_ids=candidate_ids,
    )
    await _publish_v1_audit(
        request_id=request_id,
        user_id=None,
        session_id=None,
        intent=intent,
        mode=mode,
        query_length=0,
        tool_calls_count=len(tool_calls),
        tool_results_count=len(tool_results),
        escalated=False,
        response_length=len(message),
    )
    return response


@router.post("/agent/recommend", response_model=RecommendResponse)
async def recommend(request: RecommendRequest) -> RecommendResponse:
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "recommend")
    retriever = get_retriever()
    if retriever is not None:
        payload["retrieval_context"] = await retriever.retrieve(
            request.category or "recommendations", payload
        )

    llm_client = get_llm_client()
    llm_resp = (
        await llm_client.generate("recommend", sanitize_llm_payload(payload))
        if llm_client is not None
        else None
    )
    llm_msg = llm_resp.get("message") if isinstance(llm_resp, dict) else None
    mode: Literal["fallback", "llm"] = (
        "llm" if isinstance(llm_msg, str) and llm_msg.strip() else "fallback"
    )

    if mode == "llm" and isinstance(llm_resp, dict):
        tool_calls = parse_tool_calls(llm_resp.get("tool_calls"))
        recommended_ids = [
            pid for pid in (parse_uuid(p) for p in llm_resp.get("recommended_product_ids", [])) if pid
        ]
        intent = str(llm_resp.get("intent") or "recommend")
        message = str(llm_msg)
    else:
        seen: set[UUID] = set()
        recommended_ids = []
        if request.seed_product_id:
            seen.add(request.seed_product_id)
            recommended_ids.append(request.seed_product_id)
        for pid in request.recent_product_ids:
            if pid not in seen:
                seen.add(pid)
                recommended_ids.append(pid)
            if len(recommended_ids) >= request.limit:
                break
        tool_calls = []
        message = "Generated deterministic recommendations."
        intent = "recommend"
        if len(recommended_ids) < request.limit and request.category:
            tool_calls.append(
                ToolCall(
                    name="catalog.list_products",
                    arguments={"category": request.category, "limit": request.limit},
                )
            )
            message = "Querying catalog recommendations."

    tool_results: List[ToolResult] = []
    if request.execute_tools and tool_calls:
        registry = await get_registry()
        for call in tool_calls:
            params = dict(call.arguments)
            if "limit" in params and "size" not in params:
                params["size"] = params.pop("limit")
            res = await registry.call(call.name, params=params)
            tool_results.append(
                ToolResult(
                    name=res.tool_name,
                    success=res.success,
                    data=res.data or None,
                    error=res.error,
                )
            )

    if mode == "fallback" and tool_results and tool_results[0].success and tool_results[0].data:
        seen_ids = set(recommended_ids)
        for pid in extract_product_ids(tool_results[0].data):
            if pid not in seen_ids:
                seen_ids.add(pid)
                recommended_ids.append(pid)
            if len(recommended_ids) >= request.limit:
                break

    response = RecommendResponse(
        request_id=request_id,
        mode=mode,
        intent=intent,
        message=message,
        tool_calls=tool_calls,
        tool_results=tool_results,
        recommended_product_ids=recommended_ids,
    )
    await _publish_v1_audit(
        request_id=request_id,
        user_id=str(request.user_id) if request.user_id else None,
        session_id=None,
        intent=intent,
        mode=mode,
        query_length=0,
        tool_calls_count=len(tool_calls),
        tool_results_count=len(tool_results),
        escalated=False,
        response_length=len(message),
    )
    return response
