"""Production LangGraph node implementations for InstaCommerce.

Each public function is a graph node: it receives an ``AgentState``,
performs its work, and returns an updated copy (Pydantic ``model_copy``).

Nodes are intentionally **pure-ish** – side-effects are isolated behind
injected collaborators (``ToolRegistry``, ``CheckpointSaver``, etc.).

Error handling philosophy: every node catches its own exceptions and
records them on ``state.errors`` rather than propagating.  This ensures
the graph always reaches ``respond`` or ``escalate``.
"""

from __future__ import annotations

import logging
import re
import time
from typing import Any, Dict, List, Optional, Set

from app.config import Settings
from app.graph.budgets import BudgetExceededError, BudgetTracker
from app.graph.state import (
    AgentState,
    IntentType,
    RetrievalResult,
    RiskLevel,
    ToolResult,
)
from app.graph.tools import ToolRegistry

logger = logging.getLogger("ai_orchestrator.nodes")


# ---------------------------------------------------------------------------
# Keyword maps for deterministic intent classification
# ---------------------------------------------------------------------------

_INTENT_KEYWORDS: Dict[IntentType, List[str]] = {
    IntentType.SUBSTITUTE: [
        "substitute", "replace", "alternative", "swap", "instead of",
        "similar to", "equivalent", "replacement",
    ],
    IntentType.SUPPORT: [
        "help", "support", "issue", "problem", "complaint", "refund",
        "return", "damaged", "wrong item", "missing",
    ],
    IntentType.RECOMMEND: [
        "recommend", "suggest", "best", "top", "popular", "trending",
        "what should", "which one",
    ],
    IntentType.SEARCH: [
        "search", "find", "look for", "where", "show me", "browse",
        "do you have", "available", "in stock",
    ],
    IntentType.ORDER_STATUS: [
        "order status", "track", "tracking", "where is my order",
        "delivery", "shipment", "eta", "arriving", "order number",
    ],
}

_RISK_INTENTS: Dict[IntentType, RiskLevel] = {
    IntentType.SUBSTITUTE: RiskLevel.MEDIUM,
    IntentType.SUPPORT: RiskLevel.MEDIUM,
    IntentType.RECOMMEND: RiskLevel.LOW,
    IntentType.SEARCH: RiskLevel.LOW,
    IntentType.ORDER_STATUS: RiskLevel.LOW,
    IntentType.UNKNOWN: RiskLevel.HIGH,
}

# PII patterns for output validation
_PII_PATTERNS = [
    (re.compile(r"[\w\.-]+@[\w\.-]+\.\w+"), "[REDACTED_EMAIL]"),
    (re.compile(r"\b\d{3}-?\d{2}-?\d{4}\b"), "[REDACTED_SSN]"),
    (re.compile(r"\b(?:\d[ -]*?){13,16}\b"), "[REDACTED_CARD]"),
    (
        re.compile(r"\b(?:\+?\d{1,3}[ -]?)?(?:\(?\d{3}\)?[ -]?)\d{3}[ -]?\d{4}\b"),
        "[REDACTED_PHONE]",
    ),
]


# ---------------------------------------------------------------------------
# Node: classify_intent
# ---------------------------------------------------------------------------

def classify_intent(state: AgentState) -> AgentState:
    """Deterministic keyword-based intent classifier with confidence scoring.

    Scans the query for known keyword patterns and selects the intent
    with the highest match count.  Falls back to ``UNKNOWN`` with low
    confidence when no keywords match.
    """
    try:
        query_lower = state.query.lower()
        scores: Dict[IntentType, int] = {}

        for intent, keywords in _INTENT_KEYWORDS.items():
            score = sum(1 for kw in keywords if kw in query_lower)
            if score > 0:
                scores[intent] = score

        if scores:
            best_intent = max(scores, key=scores.get)  # type: ignore[arg-type]
            total_keywords = sum(len(v) for v in _INTENT_KEYWORDS.values())
            confidence = min(1.0, scores[best_intent] / max(len(_INTENT_KEYWORDS[best_intent]), 1))
        else:
            best_intent = IntentType.UNKNOWN
            confidence = 0.0

        risk = _RISK_INTENTS.get(best_intent, RiskLevel.MEDIUM)

        # Escalate high-risk unknown intents when confidence is very low
        needs_escalation = best_intent == IntentType.UNKNOWN and confidence < 0.1

        logger.info(
            "node.classify_intent",
            extra={
                "request_id": state.request_id,
                "intent": best_intent.value,
                "confidence": round(confidence, 3),
                "risk_level": risk.value,
            },
        )

        return state.model_copy(
            update={
                "intent": best_intent,
                "intent_confidence": confidence,
                "risk_level": risk,
                "needs_escalation": needs_escalation,
                "current_node": "classify_intent",
                "completed_nodes": state.completed_nodes + ["classify_intent"],
            }
        )
    except Exception as exc:
        logger.exception(
            "node.classify_intent.error",
            extra={"request_id": state.request_id},
        )
        return state.model_copy(
            update={
                "errors": state.errors + [f"classify_intent: {exc}"],
                "intent": IntentType.UNKNOWN,
                "risk_level": RiskLevel.HIGH,
                "needs_escalation": True,
                "escalation_reason": "Intent classification failed",
                "current_node": "classify_intent",
                "completed_nodes": state.completed_nodes + ["classify_intent"],
            }
        )


# ---------------------------------------------------------------------------
# Node: check_policy
# ---------------------------------------------------------------------------

def check_policy(state: AgentState, settings: Optional[Settings] = None) -> AgentState:
    """Pre-execution policy gate.

    Validates:
    * Cost budget not exceeded ($0.50 hard cap).
    * Latency budget has headroom.
    * Tool-call counter below limit.
    * Risk level is acceptable for autonomous processing.

    If any check fails the state is flagged for escalation.
    """
    try:
        settings = settings or Settings()
        violations: List[str] = []

        # Budget checks
        if state.total_cost_usd >= state.max_cost_usd:
            violations.append(
                f"Cost budget exceeded: ${state.total_cost_usd:.4f} >= ${state.max_cost_usd:.2f}"
            )
        if state.elapsed_ms >= state.max_latency_ms:
            violations.append(
                f"Latency budget exceeded: {state.elapsed_ms:.0f}ms >= {state.max_latency_ms:.0f}ms"
            )
        if state.tool_calls_remaining <= 0:
            violations.append("Tool call budget exhausted")

        # Risk gate: CRITICAL always escalates
        if state.risk_level == RiskLevel.CRITICAL:
            violations.append(f"Risk level {state.risk_level.value} requires human review")

        if violations:
            logger.warning(
                "node.check_policy.violations",
                extra={
                    "request_id": state.request_id,
                    "violations": violations,
                },
            )
            return state.model_copy(
                update={
                    "needs_escalation": True,
                    "escalation_reason": "; ".join(violations),
                    "current_node": "check_policy",
                    "completed_nodes": state.completed_nodes + ["check_policy"],
                }
            )

        logger.info(
            "node.check_policy.passed",
            extra={"request_id": state.request_id},
        )
        return state.model_copy(
            update={
                "current_node": "check_policy",
                "completed_nodes": state.completed_nodes + ["check_policy"],
            }
        )
    except Exception as exc:
        logger.exception(
            "node.check_policy.error",
            extra={"request_id": state.request_id},
        )
        return state.model_copy(
            update={
                "errors": state.errors + [f"check_policy: {exc}"],
                "needs_escalation": True,
                "escalation_reason": "Policy check failed unexpectedly",
                "current_node": "check_policy",
                "completed_nodes": state.completed_nodes + ["check_policy"],
            }
        )


# ---------------------------------------------------------------------------
# Node: retrieve_context
# ---------------------------------------------------------------------------

async def retrieve_context(state: AgentState) -> AgentState:
    """RAG retrieval node with keyword fallback.

    In production this would call a vector store (e.g. Pinecone, Weaviate).
    The current implementation provides a deterministic keyword-based
    fallback so the graph is functional without external dependencies.
    """
    try:
        # Keyword-based fallback retrieval
        results: List[RetrievalResult] = []
        query_lower = state.query.lower()

        # Simulated knowledge-base entries keyed by topic
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

        for keyword, docs in _KB.items():
            if keyword in query_lower:
                for doc in docs:
                    results.append(RetrievalResult(**doc))

        # Generic fallback if nothing matched
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
            extra={
                "request_id": state.request_id,
                "results_count": len(results),
            },
        )

        return state.model_copy(
            update={
                "retrieval_results": results,
                "current_node": "retrieve_context",
                "completed_nodes": state.completed_nodes + ["retrieve_context"],
            }
        )
    except Exception as exc:
        logger.exception(
            "node.retrieve_context.error",
            extra={"request_id": state.request_id},
        )
        return state.model_copy(
            update={
                "errors": state.errors + [f"retrieve_context: {exc}"],
                "current_node": "retrieve_context",
                "completed_nodes": state.completed_nodes + ["retrieve_context"],
            }
        )


# ---------------------------------------------------------------------------
# Node: execute_tools
# ---------------------------------------------------------------------------

async def execute_tools(
    state: AgentState,
    registry: Optional[ToolRegistry] = None,
) -> AgentState:
    """Execute tools based on intent with budget enforcement.

    Tool selection is driven by intent type.  Each invocation decrements
    ``tool_calls_remaining`` and is tracked in ``tool_results``.
    Execution stops immediately if any budget ceiling is breached.
    """
    try:
        if state.tool_calls_remaining <= 0:
            logger.warning(
                "node.execute_tools.budget_exhausted",
                extra={"request_id": state.request_id},
            )
            return state.model_copy(
                update={
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        if state.total_cost_usd >= state.max_cost_usd:
            logger.warning(
                "node.execute_tools.cost_exceeded",
                extra={"request_id": state.request_id},
            )
            return state.model_copy(
                update={
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        # Determine which tools to call based on intent
        tool_plan: List[Dict[str, Any]] = _plan_tools(state)
        results: List[ToolResult] = list(state.tool_results)
        remaining = state.tool_calls_remaining

        if registry is None:
            # No registry available — record a synthetic skip result
            logger.info(
                "node.execute_tools.no_registry",
                extra={"request_id": state.request_id},
            )
            return state.model_copy(
                update={
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        for plan in tool_plan:
            if remaining <= 0:
                break
            if state.total_cost_usd >= state.max_cost_usd:
                break

            result = await registry.call(
                plan["tool"],
                params=plan.get("params"),
                body=plan.get("body"),
                path_params=plan.get("path_params"),
            )
            results.append(result)
            remaining -= 1

        logger.info(
            "node.execute_tools",
            extra={
                "request_id": state.request_id,
                "tools_called": len(results) - len(state.tool_results),
                "remaining": remaining,
            },
        )

        return state.model_copy(
            update={
                "tool_results": results,
                "tool_calls_remaining": remaining,
                "current_node": "execute_tools",
                "completed_nodes": state.completed_nodes + ["execute_tools"],
            }
        )
    except Exception as exc:
        logger.exception(
            "node.execute_tools.error",
            extra={"request_id": state.request_id},
        )
        return state.model_copy(
            update={
                "errors": state.errors + [f"execute_tools: {exc}"],
                "current_node": "execute_tools",
                "completed_nodes": state.completed_nodes + ["execute_tools"],
            }
        )


def _plan_tools(state: AgentState) -> List[Dict[str, Any]]:
    """Return an ordered list of tool calls for the detected intent."""
    query_lower = state.query.lower()
    plan: List[Dict[str, Any]] = []

    if state.intent == IntentType.SEARCH:
        plan.append({"tool": "catalog.search", "params": {"q": state.query}})
    elif state.intent == IntentType.SUBSTITUTE:
        plan.append({"tool": "catalog.search", "params": {"q": state.query}})
        plan.append({"tool": "inventory.check", "params": {"q": state.query}})
    elif state.intent == IntentType.ORDER_STATUS:
        # Extract order id from context if available
        order_id = state.context.get("order_id", "")
        if order_id:
            plan.append({"tool": "order.get", "path_params": {"order_id": order_id}})
    elif state.intent == IntentType.RECOMMEND:
        plan.append({"tool": "catalog.list_products", "params": {"sort": "popularity"}})
    elif state.intent == IntentType.SUPPORT:
        order_id = state.context.get("order_id", "")
        if order_id:
            plan.append({"tool": "order.get", "path_params": {"order_id": order_id}})

    return plan


# ---------------------------------------------------------------------------
# Node: validate_output
# ---------------------------------------------------------------------------

def validate_output(state: AgentState) -> AgentState:
    """Validate and sanitise the response before delivery.

    Checks:
    * PII redaction (email, SSN, credit card, phone).
    * Non-empty response.
    * Citation presence when retrieval results exist.
    """
    try:
        response = state.response
        citations = list(state.response_citations)

        # PII scrubbing
        for pattern, replacement in _PII_PATTERNS:
            response = pattern.sub(replacement, response)

        # Auto-generate citations from retrieval results if missing
        if state.retrieval_results and not citations:
            citations = [r.doc_id for r in state.retrieval_results]

        # Ensure response is non-empty
        if not response.strip():
            response = "I'm sorry, I wasn't able to generate a response. Please try rephrasing your question."

        logger.info(
            "node.validate_output",
            extra={
                "request_id": state.request_id,
                "citations_count": len(citations),
                "response_length": len(response),
            },
        )

        return state.model_copy(
            update={
                "response": response,
                "response_citations": citations,
                "current_node": "validate_output",
                "completed_nodes": state.completed_nodes + ["validate_output"],
            }
        )
    except Exception as exc:
        logger.exception(
            "node.validate_output.error",
            extra={"request_id": state.request_id},
        )
        return state.model_copy(
            update={
                "errors": state.errors + [f"validate_output: {exc}"],
                "response": "An error occurred while preparing your response. Please try again.",
                "current_node": "validate_output",
                "completed_nodes": state.completed_nodes + ["validate_output"],
            }
        )


# ---------------------------------------------------------------------------
# Node: respond
# ---------------------------------------------------------------------------

def respond(state: AgentState) -> AgentState:
    """Format the final response with context and citations.

    Combines retrieval context, tool results, and conversation history
    into a coherent user-facing response.
    """
    try:
        # If response was already built (e.g. by an LLM node), just pass through
        if state.response.strip():
            return state.model_copy(
                update={
                    "current_node": "respond",
                    "completed_nodes": state.completed_nodes + ["respond"],
                }
            )

        parts: List[str] = []

        # Incorporate retrieval context
        if state.retrieval_results:
            context_summary = "; ".join(
                r.content for r in state.retrieval_results[:3]
            )
            parts.append(context_summary)

        # Incorporate successful tool results
        for tr in state.tool_results:
            if tr.success and tr.data:
                parts.append(f"[{tr.tool_name}]: retrieved data successfully.")

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
            extra={
                "request_id": state.request_id,
                "response_length": len(response),
            },
        )

        return state.model_copy(
            update={
                "response": response,
                "response_citations": citations,
                "current_node": "respond",
                "completed_nodes": state.completed_nodes + ["respond"],
            }
        )
    except Exception as exc:
        logger.exception(
            "node.respond.error",
            extra={"request_id": state.request_id},
        )
        return state.model_copy(
            update={
                "errors": state.errors + [f"respond: {exc}"],
                "response": "I encountered an issue generating a response. Please try again.",
                "current_node": "respond",
                "completed_nodes": state.completed_nodes + ["respond"],
            }
        )


# ---------------------------------------------------------------------------
# Node: escalate
# ---------------------------------------------------------------------------

def escalate(state: AgentState) -> AgentState:
    """Package context for human escalation.

    Builds a structured escalation payload and sets the response to
    inform the user that a human agent will follow up.
    """
    try:
        reason = state.escalation_reason or "Automated processing could not resolve this request"

        escalation_context = {
            "request_id": state.request_id,
            "user_id": state.user_id,
            "query": state.query,
            "intent": state.intent.value,
            "intent_confidence": state.intent_confidence,
            "risk_level": state.risk_level.value,
            "errors": state.errors,
            "tool_results_summary": [
                {"tool": tr.tool_name, "success": tr.success, "error": tr.error}
                for tr in state.tool_results
            ],
            "reason": reason,
        }

        response = (
            "I'm connecting you with a support specialist who can better assist you. "
            f"Your reference number is {state.request_id}. "
            "They will have full context of your conversation."
        )

        logger.warning(
            "node.escalate",
            extra={
                "request_id": state.request_id,
                "reason": reason,
                "escalation_context": escalation_context,
            },
        )

        return state.model_copy(
            update={
                "response": response,
                "needs_escalation": True,
                "escalation_reason": reason,
                "context": {**state.context, "escalation": escalation_context},
                "current_node": "escalate",
                "completed_nodes": state.completed_nodes + ["escalate"],
            }
        )
    except Exception as exc:
        logger.exception(
            "node.escalate.error",
            extra={"request_id": state.request_id},
        )
        return state.model_copy(
            update={
                "errors": state.errors + [f"escalate: {exc}"],
                "response": (
                    "We're experiencing difficulties. Please contact support "
                    f"with reference {state.request_id}."
                ),
                "current_node": "escalate",
                "completed_nodes": state.completed_nodes + ["escalate"],
            }
        )
