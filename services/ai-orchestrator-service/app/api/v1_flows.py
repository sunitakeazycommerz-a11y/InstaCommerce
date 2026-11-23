"""Guarded execution flows for v1 /agent/substitute and /agent/recommend endpoints."""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Literal, Optional, Tuple
from uuid import UUID

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
    get_llm_client,
    get_registry,
    get_retriever,
    get_settings,
)
from app.core.llm import (
    derive_request_id,
    extract_product_ids,
    normalize_candidates,
    parse_tool_calls,
    parse_uuid,
)
from app.core.logging import log_event, sanitize_llm_payload
from app.graph.budgets import token_cost
from app.graph.state import AgentState, IntentType, ToolResult as GraphToolResult
from app.guardrails import InjectionDetector, OutputPolicy, PIIVault
from app.schemas import (
    RecommendRequest,
    RecommendResponse,
    SubstituteRequest,
    SubstituteResponse,
    ToolCall,
    ToolResult,
)

logger = logging.getLogger("ai_orchestrator.api.v1_flows")

_flow_injection_detector = InjectionDetector()
_flow_output_policy = OutputPolicy()


def redact_retrieval_payload(raw_payload: Dict[str, Any], query: str) -> Tuple[str, Dict[str, Any]]:
    """Redact PII and secrets from query and context payload prior to retrieval."""
    vault = PIIVault()
    safe_query, _ = vault.redact(query)
    redacted = vault.redact_value(raw_payload)
    payload = sanitize_llm_payload(redacted if isinstance(redacted, dict) else {})
    payload["query"] = safe_query
    return safe_query, payload


async def retrieve_and_generate(
    task: str,
    retrieval_query: str,
    raw_payload: Dict[str, Any],
    *,
    already_redacted: bool = False,
) -> Optional[Dict[str, Any]]:
    if already_redacted:
        safe_query = retrieval_query
        safe_payload = sanitize_llm_payload(dict(raw_payload))
        safe_payload["query"] = safe_query
    else:
        safe_query, safe_payload = redact_retrieval_payload(raw_payload, retrieval_query)

    retriever = get_retriever()
    if retriever is not None:
        safe_payload["retrieval_context"] = await retriever.retrieve(safe_query, safe_payload)
    llm_client = get_llm_client()
    if llm_client is None:
        return None
    return await llm_client.generate(task, sanitize_llm_payload(safe_payload))


def _record_llm_usage(
    updated: Dict[str, Any],
    query_text: str,
    message: str,
    llm_resp: Dict[str, Any],
) -> None:
    usage = llm_resp.get("usage")
    if isinstance(usage, dict):
        in_tok = int(usage.get("prompt_tokens", 0) or 0)
        out_tok = int(usage.get("completion_tokens", 0) or 0)
    else:
        in_tok = max(1, len(query_text) // 4)
        out_tok = max(1, len(message) // 4)
    model_name = get_settings().llm_model or "default"
    updated["total_tokens_used"] = int(updated.get("total_tokens_used", 0)) + in_tok + out_tok
    updated["total_cost_usd"] = float(updated.get("total_cost_usd", 0.0)) + token_cost(
        model_name, in_tok, out_tok
    )


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


class _SubstituteGraphAdapter:
    def __init__(self, request: SubstituteRequest, effective_user_id: str) -> None:
        self._request = request
        self._effective_user_id = effective_user_id
        self.tool_calls: List[ToolCall] = []
        self.substitute_id: Optional[UUID] = None
        self.candidate_ids: List[UUID] = []
        self.mode: Literal["fallback", "llm"] = "fallback"
        self.raw_intent: str = "substitute"

    async def ainvoke(self, state_dict: Dict[str, Any]) -> Dict[str, Any]:
        updated = dict(state_dict)
        safe_payload = sanitize_llm_payload(dict(updated.get("context", {})))
        llm_resp = await retrieve_and_generate(
            "substitute",
            str(updated.get("query", "")),
            safe_payload,
            already_redacted=True,
        )
        llm_msg = llm_resp.get("message") if isinstance(llm_resp, dict) else None
        self.mode = "llm" if isinstance(llm_msg, str) and llm_msg.strip() else "fallback"

        if self.mode == "llm" and isinstance(llm_resp, dict):
            self.tool_calls = parse_tool_calls(llm_resp.get("tool_calls"))
            self.substitute_id = parse_uuid(llm_resp.get("substitute_product_id"))
            self.candidate_ids = [
                cid for cid in (parse_uuid(c) for c in llm_resp.get("candidate_ids", [])) if cid
            ]
            self.raw_intent = str(llm_resp.get("intent") or "substitute")
            message = str(llm_msg)
            _record_llm_usage(updated, str(updated.get("query", "")), message, llm_resp)
        else:
            self.candidate_ids = normalize_candidates(
                self._request.product_id,
                self._request.candidate_ids,
                self._request.limit,
            )
            self.substitute_id = self.candidate_ids[0] if self.candidate_ids else None
            self.tool_calls = []
            message = (
                "Selected a substitute candidate."
                if self.candidate_ids
                else "No substitute candidates provided."
            )
            self.raw_intent = "substitute"
            if self._request.store_id and self.candidate_ids:
                self.tool_calls.append(
                    ToolCall(
                        name="inventory.check",
                        arguments={
                            "store_id": self._request.store_id,
                            "items": [
                                {"product_id": str(cid), "quantity": 1}
                                for cid in self.candidate_ids
                            ],
                        },
                    )
                )

        graph_tool_results: List[GraphToolResult] = []
        if self._request.execute_tools and self.tool_calls:
            registry = await get_registry()
            for call in self.tool_calls:
                if call.name == "inventory.check":
                    res = await registry.call(
                        "inventory.check",
                        body={
                            "storeId": call.arguments.get("store_id") or call.arguments.get("storeId"),
                            "items": [
                                {
                                    "productId": item.get("productId") or item.get("product_id"),
                                    "quantity": item.get("quantity", 1),
                                }
                                for item in call.arguments.get("items", [])
                                if isinstance(item, dict)
                            ],
                        },
                        user_id=self._effective_user_id or None,
                    )
                else:
                    res = await registry.call(
                        call.name,
                        body=call.arguments,
                        user_id=self._effective_user_id or None,
                    )
                graph_tool_results.append(res)

        if (
            self.mode == "fallback"
            and graph_tool_results
            and graph_tool_results[0].success
            and graph_tool_results[0].data
        ):
            available_ids: List[UUID] = []
            for item in graph_tool_results[0].data.get("items", []):
                if isinstance(item, dict) and item.get("sufficient"):
                    avail_id = parse_uuid(item.get("productId") or item.get("product_id"))
                    if avail_id:
                        available_ids.append(avail_id)
            for cand in self.candidate_ids:
                if cand in available_ids:
                    self.substitute_id = cand
                    break

        updated["intent"] = IntentType.SUBSTITUTE.value
        updated["generation_mode"] = self.mode
        updated["response"] = message
        updated["tool_results"] = [tr.model_dump() for tr in graph_tool_results]
        return AgentState(**updated).model_dump()


class _RecommendGraphAdapter:
    def __init__(self, request: RecommendRequest, effective_user_id: str) -> None:
        self._request = request
        self._effective_user_id = effective_user_id
        self.tool_calls: List[ToolCall] = []
        self.recommended_ids: List[UUID] = []
        self.mode: Literal["fallback", "llm"] = "fallback"
        self.raw_intent: str = "recommend"

    async def ainvoke(self, state_dict: Dict[str, Any]) -> Dict[str, Any]:
        updated = dict(state_dict)
        safe_payload = sanitize_llm_payload(dict(updated.get("context", {})))
        llm_resp = await retrieve_and_generate(
            "recommend",
            str(updated.get("query", "")),
            safe_payload,
            already_redacted=True,
        )
        llm_msg = llm_resp.get("message") if isinstance(llm_resp, dict) else None
        self.mode = "llm" if isinstance(llm_msg, str) and llm_msg.strip() else "fallback"

        if self.mode == "llm" and isinstance(llm_resp, dict):
            self.tool_calls = parse_tool_calls(llm_resp.get("tool_calls"))
            self.recommended_ids = [
                pid for pid in (parse_uuid(p) for p in llm_resp.get("recommended_product_ids", [])) if pid
            ]
            self.raw_intent = str(llm_resp.get("intent") or "recommend")
            message = str(llm_msg)
            _record_llm_usage(updated, str(updated.get("query", "")), message, llm_resp)
        else:
            seen: set[UUID] = set()
            self.recommended_ids = []
            if self._request.seed_product_id:
                seen.add(self._request.seed_product_id)
                self.recommended_ids.append(self._request.seed_product_id)
            for pid in self._request.recent_product_ids:
                if pid not in seen:
                    seen.add(pid)
                    self.recommended_ids.append(pid)
                if len(self.recommended_ids) >= self._request.limit:
                    break
            self.tool_calls = []
            message = "Generated deterministic recommendations."
            self.raw_intent = "recommend"
            if len(self.recommended_ids) < self._request.limit and self._request.category:
                self.tool_calls.append(
                    ToolCall(
                        name="catalog.list_products",
                        arguments={
                            "category": self._request.category,
                            "limit": self._request.limit,
                            "sort": "createdAt,desc",
                        },
                    )
                )
                message = "Querying catalog recommendations."

        graph_tool_results: List[GraphToolResult] = []
        if self._request.execute_tools and self.tool_calls:
            registry = await get_registry()
            for call in self.tool_calls:
                params = dict(call.arguments)
                if "limit" in params and "size" not in params:
                    params["size"] = params.pop("limit")
                res = await registry.call(
                    call.name,
                    params=params,
                    user_id=self._effective_user_id or None,
                )
                graph_tool_results.append(res)

        if (
            self.mode == "fallback"
            and graph_tool_results
            and graph_tool_results[0].success
            and graph_tool_results[0].data
        ):
            seen_ids = set(self.recommended_ids)
            for pid in extract_product_ids(graph_tool_results[0].data):
                if pid not in seen_ids:
                    seen_ids.add(pid)
                    self.recommended_ids.append(pid)
                if len(self.recommended_ids) >= self._request.limit:
                    break

        updated["intent"] = IntentType.RECOMMEND.value
        updated["generation_mode"] = self.mode
        updated["response"] = message
        updated["tool_results"] = [tr.model_dump() for tr in graph_tool_results]
        return AgentState(**updated).model_dump()


async def handle_substitute(
    request: SubstituteRequest,
    *,
    user_id_header: Optional[str] = None,
) -> SubstituteResponse:
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "substitute")
    effective_user_id = str(request.user_id) if request.user_id else (user_id_header.strip() if user_id_header else "")
    query_parts = [f"substitute {request.product_id}"]
    if request.store_id:
        query_parts.append(request.store_id)
    query_text = " ".join(query_parts)

    adapter_holder: List[_SubstituteGraphAdapter] = []

    def _wrap(_compiled: Any, _safe_q: str, _safe_ctx: Dict[str, Any]) -> _SubstituteGraphAdapter:
        adapter = _SubstituteGraphAdapter(request, effective_user_id)
        adapter_holder.append(adapter)
        return adapter

    async def _noop_graph_provider() -> object:
        return object()

    service = ExecutionService(
        injection_detector=_flow_injection_detector,
        output_policy=_flow_output_policy,
    )
    outcome = await service.execute(
        query=query_text,
        user_id=effective_user_id,
        session_id=request.session_id,
        context=payload,
        request_id=request_id,
        execute_tools=request.execute_tools,
        max_cost_usd=0.50,
        graph_provider=_noop_graph_provider,
        runner=get_execution_runner(),
        checkpointer=get_checkpoint_saver(),
        graph_wrapper=_wrap,
        restore_pii=get_settings().pii_redaction_enabled,
    )

    if isinstance(outcome, PolicyViolationOutcome) and outcome.stage == "injection":
        log_event(
            "substitute.injection_blocked",
            level=logging.WARNING,
            request_id=request_id,
            reason=outcome.reason,
        )
        await publish_audit_events(
            get_audit_publisher(),
            version="v1",
            request_id=request_id,
            user_id=effective_user_id or None,
            session_id=request.session_id,
            intent="unknown",
            mode="fallback",
            query_length=len(query_text),
            escalated=True,
            response_length=0,
        )
        return SubstituteResponse(
            request_id=request_id,
            mode="fallback",
            intent="unknown",
            message=(
                "Your request could not be processed automatically. "
                "Connecting you with a support specialist."
            ),
        )

    if isinstance(outcome, BudgetExceededOutcome):
        await publish_audit_events(
            get_audit_publisher(),
            version="v1",
            request_id=request_id,
            user_id=effective_user_id or None,
            session_id=request.session_id,
            intent="budget_exceeded",
            mode="fallback",
            query_length=len(query_text),
            escalated=True,
            response_length=0,
        )
        return SubstituteResponse(
            request_id=request_id,
            mode="fallback",
            intent="budget_exceeded",
            message="Session processing budget exceeded. Connecting you with a support specialist.",
        )

    assert outcome.state is not None
    result = outcome.state
    adapter = adapter_holder[0] if adapter_holder else None
    mode: Literal["fallback", "llm"] = adapter.mode if adapter is not None else "fallback"
    intent = (adapter.raw_intent if adapter is not None else None) or result.intent.value
    tool_calls = adapter.tool_calls if adapter is not None else []
    tool_results = _to_v1_tool_results(result.tool_results)
    escalated = result.needs_escalation or isinstance(outcome, PolicyViolationOutcome)
    final_message = (
        "Connecting you with a support specialist to review this request."
        if isinstance(outcome, PolicyViolationOutcome)
        else outcome.response_text
    )

    await publish_audit_events(
        get_audit_publisher(),
        version="v1",
        request_id=request_id,
        user_id=effective_user_id or None,
        session_id=request.session_id,
        intent=intent,
        mode=mode,
        query_length=len(result.query),
        tool_calls_count=len(tool_calls),
        tool_results_count=len(tool_results),
        escalated=escalated,
        response_length=len(final_message),
    )
    return SubstituteResponse(
        request_id=request_id,
        mode=mode,
        intent=intent,
        message=final_message,
        tool_calls=tool_calls,
        tool_results=tool_results,
        substitute_product_id=adapter.substitute_id if (adapter and not escalated) else None,
        candidate_ids=adapter.candidate_ids if (adapter and not escalated) else [],
    )


async def handle_recommend(
    request: RecommendRequest,
    *,
    user_id_header: Optional[str] = None,
) -> RecommendResponse:
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "recommend")
    effective_user_id = str(request.user_id) if request.user_id else (user_id_header.strip() if user_id_header else "")
    query_text = request.category or "recommendations"

    adapter_holder: List[_RecommendGraphAdapter] = []

    def _wrap(_compiled: Any, _safe_q: str, _safe_ctx: Dict[str, Any]) -> _RecommendGraphAdapter:
        adapter = _RecommendGraphAdapter(request, effective_user_id)
        adapter_holder.append(adapter)
        return adapter

    async def _noop_graph_provider() -> object:
        return object()

    service = ExecutionService(
        injection_detector=_flow_injection_detector,
        output_policy=_flow_output_policy,
    )
    outcome = await service.execute(
        query=query_text,
        user_id=effective_user_id,
        session_id=request.session_id,
        context=payload,
        request_id=request_id,
        execute_tools=request.execute_tools,
        max_cost_usd=0.50,
        graph_provider=_noop_graph_provider,
        runner=get_execution_runner(),
        checkpointer=get_checkpoint_saver(),
        graph_wrapper=_wrap,
        restore_pii=get_settings().pii_redaction_enabled,
    )

    if isinstance(outcome, PolicyViolationOutcome) and outcome.stage == "injection":
        log_event(
            "recommend.injection_blocked",
            level=logging.WARNING,
            request_id=request_id,
            reason=outcome.reason,
        )
        await publish_audit_events(
            get_audit_publisher(),
            version="v1",
            request_id=request_id,
            user_id=effective_user_id or None,
            session_id=request.session_id,
            intent="unknown",
            mode="fallback",
            query_length=len(query_text),
            escalated=True,
            response_length=0,
        )
        return RecommendResponse(
            request_id=request_id,
            mode="fallback",
            intent="unknown",
            message=(
                "Your request could not be processed automatically. "
                "Connecting you with a support specialist."
            ),
        )

    if isinstance(outcome, BudgetExceededOutcome):
        await publish_audit_events(
            get_audit_publisher(),
            version="v1",
            request_id=request_id,
            user_id=effective_user_id or None,
            session_id=request.session_id,
            intent="budget_exceeded",
            mode="fallback",
            query_length=len(query_text),
            escalated=True,
            response_length=0,
        )
        return RecommendResponse(
            request_id=request_id,
            mode="fallback",
            intent="budget_exceeded",
            message="Session processing budget exceeded. Connecting you with a support specialist.",
        )

    assert outcome.state is not None
    result = outcome.state
    adapter = adapter_holder[0] if adapter_holder else None
    mode: Literal["fallback", "llm"] = adapter.mode if adapter is not None else "fallback"
    intent = (adapter.raw_intent if adapter is not None else None) or result.intent.value
    tool_calls = adapter.tool_calls if adapter is not None else []
    tool_results = _to_v1_tool_results(result.tool_results)
    escalated = result.needs_escalation or isinstance(outcome, PolicyViolationOutcome)
    final_message = (
        "Connecting you with a support specialist to review this request."
        if isinstance(outcome, PolicyViolationOutcome)
        else outcome.response_text
    )

    await publish_audit_events(
        get_audit_publisher(),
        version="v1",
        request_id=request_id,
        user_id=effective_user_id or None,
        session_id=request.session_id,
        intent=intent,
        mode=mode,
        query_length=len(result.query),
        tool_calls_count=len(tool_calls),
        tool_results_count=len(tool_results),
        escalated=escalated,
        response_length=len(final_message),
    )
    return RecommendResponse(
        request_id=request_id,
        mode=mode,
        intent=intent,
        message=final_message,
        tool_calls=tool_calls,
        tool_results=tool_results,
        recommended_product_ids=adapter.recommended_ids if (adapter and not escalated) else [],
    )
