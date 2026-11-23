"""FastAPI v1 agent endpoints unified over ExecutionService and ToolRegistry."""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Literal, Optional

from fastapi import APIRouter, Header

from app.api.execution import (
    BudgetExceededOutcome,
    ExecutionService,
    PolicyViolationOutcome,
    publish_audit_events,
)
from app.api.handlers import (
    get_audit_publisher,
    get_checkpoint_saver,
    get_execution_runner,
    get_graph,
    get_llm_client,
    get_retriever,
    get_semantic_cache,
    get_settings,
)
from app.api.v1_flows import (
    handle_recommend,
    handle_substitute,
)
from app.core.llm import derive_request_id
from app.core.logging import log_event, sanitize_llm_payload
from app.graph.budgets import token_cost
from app.graph.planning import plan_tools
from app.graph.state import IntentType, ToolResult as GraphToolResult
from app.guardrails import InjectionDetector, OutputPolicy
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
_v1_output_policy = OutputPolicy()


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


class _AssistGraphAdapter:
    """Routes unified graphs directly and bridges stub graphs with cache/retrieval/LLM."""

    def __init__(
        self,
        compiled_graph: Any,
        sanitized_payload: Dict[str, Any],
    ) -> None:
        self._graph = compiled_graph
        self._sanitized_payload = sanitized_payload
        self.mode: Literal["fallback", "llm"] = "fallback"

    async def ainvoke(self, state_dict: Dict[str, Any]) -> Dict[str, Any]:
        if getattr(self._graph, "_is_unified_orchestrator_graph", False):
            return await self._graph.ainvoke(state_dict)

        updated = dict(state_dict)
        semantic_cache = get_semantic_cache()
        llm_client = get_llm_client()
        retriever = get_retriever()

        safe_query = str(updated.get("query", ""))
        safe_payload = sanitize_llm_payload(
            {
                **self._sanitized_payload,
                "query": safe_query,
                "context": updated.get("context", {}),
            }
        )

        if semantic_cache is not None:
            match = semantic_cache.find_match(safe_query)
            if match:
                log_event(
                    "assist.semantic_cache_hit",
                    request_id=updated.get("request_id"),
                    similarity=match["similarity"],
                    category=match.get("category"),
                )
                updated["response"] = str(match["answer"])
                updated["intent"] = IntentType.SUPPORT.value
                updated["intent_confidence"] = float(match["similarity"])
                updated["generation_mode"] = "llm"
                updated["execute_tools"] = False
                self.mode = "llm"

        if self.mode != "llm" and llm_client is not None:
            if retriever is not None:
                safe_payload["retrieval_context"] = await retriever.retrieve(safe_query, safe_payload)
            llm_resp = await llm_client.generate("assist", sanitize_llm_payload(safe_payload))
            message = llm_resp.get("message") if isinstance(llm_resp, dict) else None
            if isinstance(message, str) and message.strip():
                self.mode = "llm"
                updated["response"] = message
                updated["generation_mode"] = "llm"
                if llm_resp and llm_resp.get("intent"):
                    try:
                        updated["intent"] = IntentType(str(llm_resp["intent"])).value
                        updated["intent_confidence"] = max(
                            float(updated.get("intent_confidence", 0.0) or 0.0),
                            0.8,
                        )
                    except ValueError:
                        pass
                usage = llm_resp.get("usage") if isinstance(llm_resp, dict) else None
                if isinstance(usage, dict):
                    in_tok = int(usage.get("prompt_tokens", 0) or 0)
                    out_tok = int(usage.get("completion_tokens", 0) or 0)
                else:
                    in_tok = max(1, len(safe_query) // 4)
                    out_tok = max(1, len(message) // 4)
                model_name = get_settings().llm_model or "default"
                updated["total_tokens_used"] = int(updated.get("total_tokens_used", 0)) + in_tok + out_tok
                updated["total_cost_usd"] = float(updated.get("total_cost_usd", 0.0)) + token_cost(
                    model_name, in_tok, out_tok
                )

        return await self._graph.ainvoke(updated)


@router.post("/agent/assist", response_model=AssistResponse)
async def assist(request: AssistRequest) -> AssistResponse:
    sanitized_payload = sanitize_llm_payload(request.model_dump(mode="json"))
    request_id = derive_request_id(request.request_id, sanitized_payload, "assist")
    raw_context = request.context.model_dump(mode="json", exclude_none=True)

    adapter_holder: List[_AssistGraphAdapter] = []

    def _wrap_graph(compiled_graph: Any, safe_query: str, safe_context: Dict[str, Any]) -> _AssistGraphAdapter:
        adapter_payload = {**sanitized_payload, "query": safe_query, "context": safe_context}
        adapter = _AssistGraphAdapter(compiled_graph, adapter_payload)
        adapter_holder.append(adapter)
        return adapter

    service = ExecutionService(
        injection_detector=_v1_injection_detector,
        output_policy=_v1_output_policy,
    )
    outcome = await service.execute(
        query=request.query,
        user_id=str(request.user_id) if request.user_id else "",
        session_id=request.session_id,
        context=raw_context,
        request_id=request_id,
        execute_tools=request.execute_tools,
        max_cost_usd=0.50,
        graph_provider=get_graph,
        runner=get_execution_runner(),
        checkpointer=get_checkpoint_saver(),
        graph_wrapper=_wrap_graph,
        restore_pii=get_settings().pii_redaction_enabled,
    )

    if isinstance(outcome, PolicyViolationOutcome) and outcome.stage == "injection":
        log_event(
            "assist.injection_blocked",
            level=logging.WARNING,
            request_id=request_id,
            reason=outcome.reason,
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

    if isinstance(outcome, BudgetExceededOutcome):
        return AssistResponse(
            request_id=request_id,
            mode="fallback",
            intent="budget_exceeded",
            message="Session processing budget exceeded. Connecting you with a support specialist.",
        )

    assert outcome.state is not None
    result = outcome.state
    adapter = adapter_holder[0] if adapter_holder else None
    mode: Literal["fallback", "llm"] = (
        "llm"
        if (result.generation_mode == "llm" or (adapter is not None and adapter.mode == "llm"))
        else "fallback"
    )
    final_intent = result.intent.value
    final_message = (
        "Connecting you with a support specialist to review this request."
        if isinstance(outcome, PolicyViolationOutcome)
        else outcome.response_text
    )

    raw_plans = result.planned_tool_calls or plan_tools(result)
    planned_calls = [
        ToolCall(
            name=str(plan["tool"]),
            arguments=dict(plan.get("params") or plan.get("body") or plan.get("path_params") or {}),
        )
        for plan in raw_plans
    ]
    response = AssistResponse(
        request_id=result.request_id,
        mode=mode,
        intent=final_intent,
        message=final_message,
        tool_calls=planned_calls,
        tool_results=_to_v1_tool_results(result.tool_results),
    )
    log_event(
        "assist.response",
        request_id=response.request_id,
        mode=response.mode,
        intent=response.intent,
        tool_calls=len(response.tool_calls),
        tool_results=len(response.tool_results),
    )
    await publish_audit_events(
        get_audit_publisher(),
        version="v1",
        request_id=response.request_id,
        user_id=str(request.user_id) if request.user_id else None,
        session_id=request.session_id,
        intent=response.intent,
        mode=response.mode,
        query_length=len(result.query),
        tool_calls_count=len(response.tool_calls),
        tool_results_count=len(response.tool_results),
        escalated=result.needs_escalation or isinstance(outcome, PolicyViolationOutcome),
        response_length=len(response.message),
    )
    return response


@router.post("/agent/substitute", response_model=SubstituteResponse)
async def substitute(
    request: SubstituteRequest,
    x_user_id: Optional[str] = Header(default=None, alias="X-User-Id"),
) -> SubstituteResponse:
    return await handle_substitute(request, user_id_header=x_user_id)


@router.post("/agent/recommend", response_model=RecommendResponse)
async def recommend(
    request: RecommendRequest,
    x_user_id: Optional[str] = Header(default=None, alias="X-User-Id"),
) -> RecommendResponse:
    return await handle_recommend(request, user_id_header=x_user_id)
