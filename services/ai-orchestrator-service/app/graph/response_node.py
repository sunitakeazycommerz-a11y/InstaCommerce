from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from app.config import Settings
from app.core.logging import sanitize_llm_payload
from app.graph.budgets import token_cost
from app.graph.state import AgentState, IntentType, RetrievalResult
from app.graph.substitution import format_substitution_summary, recommend_substitutions

logger = logging.getLogger("ai_orchestrator.response")

_KB: Dict[str, List[Dict[str, Any]]] = {
    "return": [
        {
            "doc_id": "policy-returns-001",
            "content": "Returns accepted within 7 days of delivery. Items must be unopened.",
            "score": 0.92,
            "metadata": {"source": "policy", "category": "returns"},
        },
    ],
    "delivery": [
        {
            "doc_id": "faq-delivery-001",
            "content": "Standard delivery takes 10-30 minutes. Express delivery available for select areas.",
            "score": 0.89,
            "metadata": {"source": "faq", "category": "delivery"},
        },
    ],
    "substitute": [
        {
            "doc_id": "policy-substitution-001",
            "content": "Substitutions are offered when an item is out of stock. Customers can pre-approve or reject substitutions.",
            "score": 0.91,
            "metadata": {"source": "policy", "category": "substitution"},
        },
    ],
}


async def retrieve_context(
    state: AgentState,
    retriever: Optional[Any] = None,
) -> AgentState:
    """RAG retrieval node combining configured retriever with deterministic KB fallback."""
    try:
        results: List[RetrievalResult] = list(state.retrieval_results)
        seen_ids = {r.doc_id for r in results}

        if retriever is not None:
            try:
                external_docs = await retriever.retrieve(
                    state.query,
                    sanitize_llm_payload(state.context),
                )
                for idx, raw in enumerate(external_docs or []):
                    if not isinstance(raw, dict):
                        continue
                    content = str(raw.get("content") or raw.get("text") or "").strip()
                    if not content:
                        continue
                    doc_id = str(raw.get("doc_id") or raw.get("id") or f"rag-{idx}")
                    if doc_id in seen_ids:
                        continue
                    seen_ids.add(doc_id)
                    score = float(raw.get("score", 0.85))
                    raw_meta = raw.get("metadata")
                    meta: Dict[str, Any] = dict(raw_meta) if isinstance(raw_meta, dict) else {}
                    results.append(
                        RetrievalResult(
                            doc_id=doc_id,
                            content=content,
                            score=score,
                            metadata=meta,
                        )
                    )
            except Exception:
                logger.warning("node.retrieve_context.retriever_failed", exc_info=True)

        query_lower = state.query.lower()
        for keyword, docs in _KB.items():
            if keyword in query_lower:
                for doc in docs:
                    if doc["doc_id"] not in seen_ids:
                        seen_ids.add(doc["doc_id"])
                        results.append(RetrievalResult(**doc))

        if not results:
            results.append(
                RetrievalResult(
                    doc_id="faq-general-001",
                    content="For assistance with your order, please provide your order number or describe your issue.",
                    score=0.50,
                    metadata={"source": "faq", "category": "general"},
                )
            )

        logger.info(
            "node.retrieve_context",
            extra={"request_id": state.request_id, "results_count": len(results)},
        )

        return state.model_copy(
            update={
                "retrieval_results": results,
                "current_node": "retrieve_context",
                "completed_nodes": state.completed_nodes + ["retrieve_context"],
            }
        )
    except Exception as exc:
        logger.exception("node.retrieve_context.error", extra={"request_id": state.request_id})
        return state.model_copy(
            update={
                "errors": state.errors + [f"retrieve_context: {exc}"],
                "current_node": "retrieve_context",
                "completed_nodes": state.completed_nodes + ["retrieve_context"],
            }
        )


def respond(state: AgentState) -> AgentState:
    """Format the final response with retrieval context, tool summaries, and citations."""
    try:
        if state.response.strip():
            citations = list(state.response_citations)
            if not citations and state.retrieval_results:
                citations = [r.doc_id for r in state.retrieval_results]
            return state.model_copy(
                update={
                    "response_citations": citations,
                    "current_node": "respond",
                    "completed_nodes": state.completed_nodes + ["respond"],
                }
            )

        parts: List[str] = []
        if state.retrieval_results:
            parts.append("; ".join(r.content for r in state.retrieval_results[:3]))

        for tr in state.tool_results:
            if tr.success and tr.data:
                parts.append(f"[{tr.tool_name}]: retrieved data successfully.")

        updated_context = dict(state.context)
        if state.intent == IntentType.SUBSTITUTE and state.tool_results:
            substitutions = recommend_substitutions(state, state.tool_results)
            updated_context["substitutions"] = [cand.to_dict() for cand in substitutions]
            parts.append(format_substitution_summary(substitutions))

        if parts:
            response = "Based on available information: " + " ".join(parts)
        else:
            response = (
                "I'm here to help! Could you provide more details so I can "
                "assist you better?"
            )

        citations = [r.doc_id for r in state.retrieval_results] if state.retrieval_results else []

        logger.info(
            "node.respond",
            extra={"request_id": state.request_id, "response_length": len(response)},
        )

        return state.model_copy(
            update={
                "response": response,
                "response_citations": citations,
                "context": updated_context,
                "current_node": "respond",
                "completed_nodes": state.completed_nodes + ["respond"],
            }
        )
    except Exception as exc:
        logger.exception("node.respond.error", extra={"request_id": state.request_id})
        return state.model_copy(
            update={
                "errors": state.errors + [f"respond: {exc}"],
                "response": "I encountered an issue generating a response. Please try again.",
                "current_node": "respond",
                "completed_nodes": state.completed_nodes + ["respond"],
            }
        )


async def respond_with_llm(
    state: AgentState,
    *,
    llm_client: Optional[Any] = None,
    settings: Optional[Settings] = None,
) -> AgentState:
    """Invoke configured ``LlmClient`` inside the graph before deterministic fallback formatting."""
    if state.response.strip() or llm_client is None:
        return respond(state)

    updated = state
    llm_payload = sanitize_llm_payload(
        {
            "query": state.query,
            "intent": state.intent.value,
            "context": state.context,
            "retrieval_context": [r.model_dump() for r in state.retrieval_results],
            "tool_results": [tr.model_dump() for tr in state.tool_results],
        }
    )
    llm_resp = await llm_client.generate("assist", llm_payload)
    message = llm_resp.get("message") if isinstance(llm_resp, dict) else None
    if isinstance(message, str) and message.strip():
        usage = llm_resp.get("usage") if isinstance(llm_resp, dict) else None
        if isinstance(usage, dict):
            in_tok = int(usage.get("prompt_tokens", 0) or 0)
            out_tok = int(usage.get("completion_tokens", 0) or 0)
        else:
            in_tok = max(1, len(state.query) // 4)
            out_tok = max(1, len(message) // 4)
        model_name = (settings.llm_model if settings and settings.llm_model else None) or "default"
        resolved_intent = state.intent
        if state.intent == IntentType.UNKNOWN and llm_resp and llm_resp.get("intent"):
            try:
                resolved_intent = IntentType(str(llm_resp["intent"]))
            except ValueError:
                pass
        updated = state.model_copy(
            update={
                "response": message,
                "generation_mode": "llm",
                "intent": resolved_intent,
                "total_tokens_used": state.total_tokens_used + in_tok + out_tok,
                "total_cost_usd": state.total_cost_usd + token_cost(model_name, in_tok, out_tok),
            }
        )
    return respond(updated)
