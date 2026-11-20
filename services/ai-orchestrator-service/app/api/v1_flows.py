"""Helpers and execution flows for v1 agent endpoints."""

from __future__ import annotations

from typing import Any, Dict, List, Literal, Optional, Tuple
from uuid import UUID

from app.api.execution import publish_audit_events
from app.api.handlers import (
    get_audit_publisher,
    get_llm_client,
    get_registry,
    get_retriever,
)
from app.core.llm import (
    derive_request_id,
    extract_product_ids,
    normalize_candidates,
    parse_tool_calls,
    parse_uuid,
)
from app.core.logging import sanitize_llm_payload
from app.guardrails import PIIVault
from app.schemas import (
    RecommendRequest,
    RecommendResponse,
    SubstituteRequest,
    SubstituteResponse,
    ToolCall,
    ToolResult,
)


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
) -> Optional[Dict[str, Any]]:
    safe_query, safe_payload = redact_retrieval_payload(raw_payload, retrieval_query)
    retriever = get_retriever()
    if retriever is not None:
        safe_payload["retrieval_context"] = await retriever.retrieve(safe_query, safe_payload)
    llm_client = get_llm_client()
    if llm_client is None:
        return None
    return await llm_client.generate(task, sanitize_llm_payload(safe_payload))


async def handle_substitute(request: SubstituteRequest) -> SubstituteResponse:
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "substitute")
    llm_resp = await retrieve_and_generate("substitute", f"substitute {request.product_id}", payload)
    llm_msg = llm_resp.get("message") if isinstance(llm_resp, dict) else None
    mode: Literal["fallback", "llm"] = "llm" if isinstance(llm_msg, str) and llm_msg.strip() else "fallback"

    if mode == "llm" and isinstance(llm_resp, dict):
        tool_calls = parse_tool_calls(llm_resp.get("tool_calls"))
        substitute_id = parse_uuid(llm_resp.get("substitute_product_id"))
        candidate_ids = [cid for cid in (parse_uuid(c) for c in llm_resp.get("candidate_ids", [])) if cid]
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
                ToolResult(name=res.tool_name, success=res.success, data=res.data or None, error=res.error)
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

    await publish_audit_events(
        get_audit_publisher(),
        version="v1",
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
    return SubstituteResponse(
        request_id=request_id,
        mode=mode,
        intent=intent,
        message=message,
        tool_calls=tool_calls,
        tool_results=tool_results,
        substitute_product_id=substitute_id,
        candidate_ids=candidate_ids,
    )


async def handle_recommend(request: RecommendRequest) -> RecommendResponse:
    payload = request.model_dump(mode="json")
    request_id = derive_request_id(request.request_id, payload, "recommend")
    llm_resp = await retrieve_and_generate("recommend", request.category or "recommendations", payload)
    llm_msg = llm_resp.get("message") if isinstance(llm_resp, dict) else None
    mode: Literal["fallback", "llm"] = "llm" if isinstance(llm_msg, str) and llm_msg.strip() else "fallback"

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
                ToolResult(name=res.tool_name, success=res.success, data=res.data or None, error=res.error)
            )

    if mode == "fallback" and tool_results and tool_results[0].success and tool_results[0].data:
        seen_ids = set(recommended_ids)
        for pid in extract_product_ids(tool_results[0].data):
            if pid not in seen_ids:
                seen_ids.add(pid)
                recommended_ids.append(pid)
            if len(recommended_ids) >= request.limit:
                break

    await publish_audit_events(
        get_audit_publisher(),
        version="v1",
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
    return RecommendResponse(
        request_id=request_id,
        mode=mode,
        intent=intent,
        message=message,
        tool_calls=tool_calls,
        tool_results=tool_results,
        recommended_product_ids=recommended_ids,
    )
