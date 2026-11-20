from __future__ import annotations

import logging
from typing import Any, Dict, List

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


async def retrieve_context(state: AgentState) -> AgentState:
    """RAG retrieval node with deterministic keyword fallback."""
    try:
        results: List[RetrievalResult] = []
        query_lower = state.query.lower()

        for keyword, docs in _KB.items():
            if keyword in query_lower:
                for doc in docs:
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
            return state.model_copy(
                update={
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
