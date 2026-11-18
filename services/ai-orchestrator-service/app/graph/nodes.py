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

from collections import OrderedDict
import logging
import re
import time
from typing import Any, Dict, List, Optional

from app.config import Settings
from app.graph.budgets import BudgetExceededError, BudgetTracker
from app.graph.checkpoints import CheckpointSaver, run_agent_graph
from app.graph.state import (
    AgentState,
    IntentType,
    RetrievalResult,
    RiskLevel,
    ToolResult,
)
from app.graph.tools import ToolRegistry
from app.guardrails import (
    EscalationPolicy,
    InjectionDetector,
    OutputPolicy,
    PIIVault,
)
from app.guardrails.escalation import EscalationState

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
        re.compile(r"(?:\+91[\-\s]?)?[0]?(?:91)?[6789]\d{9}\b"),
        "[REDACTED_PHONE]",
    ),
    (
        re.compile(r"\b(?:\+?\d{1,3}[ -]?)?(?:\(?\d{3}\)?[ -]?)\d{3}[ -]?\d{4}\b"),
        "[REDACTED_PHONE]",
    ),
]


def budget_tracker_from_state(
    state: AgentState,
    settings: Optional[Settings] = None,
) -> BudgetTracker:
    """Construct a synchronized ``BudgetTracker`` from ``AgentState``."""
    elapsed_ms = state.current_elapsed_ms()
    configured_tool_cap = max(
        settings.tool_call_max if settings is not None else 10,
        len(state.tool_results) + max(state.tool_calls_remaining, 0),
    )
    used_tool_calls = max(0, configured_tool_cap - max(state.tool_calls_remaining, 0))
    start_ns = time.monotonic_ns() - int(elapsed_ms * 1_000_000)
    return BudgetTracker(
        max_cost_usd=state.max_cost_usd,
        max_tokens=state.max_tokens,
        max_tool_calls=configured_tool_cap,
        max_latency_ms=state.max_latency_ms,
        total_cost_usd=state.total_cost_usd,
        total_tokens=state.total_tokens_used,
        tool_calls_used=used_tool_calls,
        _start_ns=start_ns,
    )


def apply_budget_to_state(state: AgentState, tracker: BudgetTracker) -> AgentState:
    """Sync tracked budget consumption back onto ``AgentState``."""
    return state.model_copy(
        update={
            "total_cost_usd": tracker.total_cost_usd,
            "total_tokens_used": tracker.total_tokens,
            "tool_calls_remaining": tracker.tool_calls_remaining,
            "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
        }
    )


def extract_structured_tool_outputs(
    tool_results: List[ToolResult],
    tool_calls: Optional[List[Any]] = None,
    context: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Extract structured dicts (pricing_lookup, inventory_check, policy_check, order_status)."""
    structured: Dict[str, Any] = {}
    ctx = context or {}

    for key in ("pricing_lookup", "inventory_check", "policy_check", "order_status"):
        val = ctx.get(key)
        if isinstance(val, dict):
            structured[key] = dict(val)

    for tr in tool_results:
        name = getattr(tr, "tool_name", None) or getattr(tr, "name", "")
        data = getattr(tr, "data", None)
        if not isinstance(data, dict):
            continue
        if name.startswith("pricing"):
            structured["pricing_lookup"] = dict(data)
        elif name.startswith("inventory"):
            structured["inventory_check"] = dict(data)
        elif name.startswith("order"):
            structured["order_status"] = dict(data)
        elif "policy" in name:
            structured["policy_check"] = dict(data)

    if tool_calls:
        for tc in tool_calls:
            tc_name = getattr(tc, "name", "") if not isinstance(tc, dict) else tc.get("name", "")
            tc_args = getattr(tc, "arguments", {}) if not isinstance(tc, dict) else tc.get("arguments", {})
            if isinstance(tc_args, dict):
                if tc_name.startswith("pricing") and "pricing_lookup" not in structured:
                    structured["pricing_lookup"] = dict(tc_args)
                elif tc_name.startswith("inventory") and "inventory_check" not in structured:
                    structured["inventory_check"] = dict(tc_args)
                elif tc_name.startswith("order") and "order_status" not in structured:
                    structured["order_status"] = dict(tc_args)
                elif "policy" in tc_name and "policy_check" not in structured:
                    structured["policy_check"] = dict(tc_args)

    return structured


class AgentExecutionRunner:
    """Runs graph executions while enforcing per-request and per-session ``BudgetTracker`` limits."""

    def __init__(
        self,
        max_session_cost_usd: float = 0.50,
        max_session_tokens: int = 0,
        max_session_tool_calls: int = 25,
        max_sessions: int = 10_000,
    ) -> None:
        self.max_session_cost_usd = max_session_cost_usd
        self.max_session_tokens = max_session_tokens
        self.max_session_tool_calls = max_session_tool_calls
        self.max_sessions = max(1, max_sessions)
        self._session_trackers: OrderedDict[str, BudgetTracker] = OrderedDict()

    def get_session_tracker(self, session_id: Optional[str]) -> Optional[BudgetTracker]:
        if not session_id:
            return None
        tracker = self._session_trackers.get(session_id)
        if tracker is not None:
            self._session_trackers.move_to_end(session_id)
            return tracker
        tracker = BudgetTracker(
            max_cost_usd=self.max_session_cost_usd,
            max_tokens=self.max_session_tokens,
            max_tool_calls=self.max_session_tool_calls,
            max_latency_ms=60_000.0,
        )
        self._session_trackers[session_id] = tracker
        while len(self._session_trackers) > self.max_sessions:
            self._session_trackers.popitem(last=False)
        return tracker

    def build_request_tracker(self, state: AgentState) -> BudgetTracker:
        return budget_tracker_from_state(state)

    async def run(
        self,
        graph: Any,
        state: AgentState,
        config: Optional[Dict[str, Any]] = None,
        checkpointer: Optional[CheckpointSaver] = None,
    ) -> AgentState:
        session_id = state.session_id or state.user_id
        session_tracker = self.get_session_tracker(session_id)
        request_tracker = self.build_request_tracker(state)

        try:
            if session_tracker is not None:
                # Re-sync session wall-clock anchor if starting a new request in the session
                session_tracker._start_ns = time.monotonic_ns()
                session_tracker.check_or_raise()
            request_tracker.check_or_raise()
        except BudgetExceededError as exc:
            logger.warning(
                "runner.budget_exceeded",
                extra={"request_id": state.request_id, "reason": str(exc)},
            )
            return state.model_copy(
                update={
                    "needs_escalation": True,
                    "escalation_reason": str(exc),
                    "response": (
                        "This request exceeds the allowed processing budget. "
                        f"Your reference number is {state.request_id}."
                    ),
                }
            )

        effective_state = state
        if session_tracker is not None:
            updates: Dict[str, Any] = {
                "max_cost_usd": min(
                    state.max_cost_usd,
                    max(0.0, session_tracker.cost_remaining),
                ),
                "tool_calls_remaining": min(
                    state.tool_calls_remaining,
                    max(0, session_tracker.tool_calls_remaining),
                ),
            }
            if session_tracker.max_tokens > 0:
                remaining_session_tokens = max(
                    0, session_tracker.max_tokens - session_tracker.total_tokens
                )
                updates["max_tokens"] = (
                    min(state.max_tokens, remaining_session_tokens)
                    if state.max_tokens > 0
                    else remaining_session_tokens
                )
            effective_state = effective_state.model_copy(update=updates)

        result_dict = await run_agent_graph(
            graph,
            effective_state.model_dump(),
            checkpointer=checkpointer,
            config=config,
        )

        result = AgentState(**result_dict) if isinstance(result_dict, dict) else result_dict

        if session_tracker is not None:
            session_tracker.total_cost_usd += max(0.0, result.total_cost_usd - state.total_cost_usd)
            session_tracker.total_tokens += max(0, result.total_tokens_used - state.total_tokens_used)
            session_tracker.tool_calls_used += max(0, len(result.tool_results) - len(state.tool_results))

        return result


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
        # Layer 1: Prompt injection defense
        detector = InjectionDetector()
        is_injection, inj_conf, inj_reason = detector.detect(state.query)
        if is_injection:
            logger.warning(
                "node.classify_intent.injection_detected",
                extra={
                    "request_id": state.request_id,
                    "reason": inj_reason,
                    "confidence": inj_conf,
                },
            )
            return state.model_copy(
                update={
                    "intent": IntentType.UNKNOWN,
                    "intent_confidence": 0.0,
                    "risk_level": RiskLevel.CRITICAL,
                    "needs_escalation": True,
                    "escalation_reason": f"Prompt injection detected: {inj_reason}",
                    "current_node": "classify_intent",
                    "completed_nodes": state.completed_nodes + ["classify_intent"],
                }
            )

        query_lower = state.query.lower()
        scores: Dict[IntentType, int] = {}

        for intent, keywords in _INTENT_KEYWORDS.items():
            score = sum(1 for kw in keywords if kw in query_lower)
            if score > 0:
                scores[intent] = score

        if scores:
            best_intent = max(scores, key=scores.get)  # type: ignore[arg-type]
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
    """Pre-execution policy gate backed by ``BudgetTracker``.

    Validates:
    * Cost budget not exceeded ($0.50 hard cap).
    * Token budget (if configured via ``max_tokens > 0``).
    * Latency budget has headroom.
    * Tool-call counter below limit.
    * Risk level is acceptable for autonomous processing.

    If any check fails the state is flagged for escalation.
    """
    try:
        settings = settings or Settings()
        tracker = budget_tracker_from_state(state, settings=settings)
        elapsed_ms = tracker.elapsed_ms
        violations: List[str] = []

        # Enforce via BudgetTracker
        try:
            tracker.check_or_raise()
        except BudgetExceededError:
            if tracker.is_cost_exceeded():
                violations.append(
                    f"Cost budget exceeded: ${tracker.total_cost_usd:.4f} >= ${tracker.max_cost_usd:.2f}"
                )
            if tracker.is_latency_exceeded():
                violations.append(
                    f"Latency budget exceeded: {elapsed_ms:.0f}ms >= {tracker.max_latency_ms:.0f}ms"
                )
            if tracker.is_tool_calls_exceeded():
                violations.append("Tool call budget exhausted")
            if tracker.is_tokens_exceeded():
                violations.append(
                    f"Token budget exceeded: {tracker.total_tokens} >= {tracker.max_tokens}"
                )

        # Risk gate: CRITICAL always escalates
        if state.risk_level == RiskLevel.CRITICAL:
            violations.append(f"Risk level {state.risk_level.value} requires human review")

        # Configurable escalation rule engine
        esc_state = EscalationState(
            query=state.query,
            intent=state.intent.value,
            intent_confidence=state.intent_confidence,
            risk_level=state.risk_level.value,
            tool_results=state.tool_results,
            errors=state.errors,
            metadata=state.context,
        )
        should_esc, trigger_name = EscalationPolicy().should_escalate(esc_state)
        if should_esc:
            violations.append(f"Escalation policy triggered: {trigger_name}")

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
                    "elapsed_ms": elapsed_ms,
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
                "elapsed_ms": elapsed_ms,
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
                "elapsed_ms": state.current_elapsed_ms(),
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
    """Execute tools based on intent with ``BudgetTracker`` enforcement.

    Tool selection is driven by intent type.  Each invocation increments
    ``BudgetTracker.record_tool_call()`` and decrements
    ``tool_calls_remaining``.  Execution stops immediately if any budget
    ceiling is breached.
    """
    try:
        tracker = budget_tracker_from_state(state, settings=registry.settings if registry else None)

        if not tracker.can_proceed():
            try:
                tracker.check_or_raise()
            except BudgetExceededError as exc:
                logger.warning(
                    "node.execute_tools.budget_exceeded",
                    extra={"request_id": state.request_id, "reason": str(exc)},
                )
            return state.model_copy(
                update={
                    "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        # Determine which tools to call based on intent
        tool_plan: List[Dict[str, Any]] = _plan_tools(state)
        results: List[ToolResult] = list(state.tool_results)

        if not state.execute_tools:
            logger.info(
                "node.execute_tools.skipped",
                extra={"request_id": state.request_id},
            )
            return state.model_copy(
                update={
                    "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        if registry is None:
            # No registry available — record a synthetic skip result
            logger.info(
                "node.execute_tools.no_registry",
                extra={"request_id": state.request_id},
            )
            return state.model_copy(
                update={
                    "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        for plan in tool_plan:
            if not tracker.can_proceed():
                break

            result = await registry.call(
                plan["tool"],
                params=plan.get("params"),
                body=plan.get("body"),
                path_params=plan.get("path_params"),
            )
            results.append(result)
            tracker.record_tool_call()

        logger.info(
            "node.execute_tools",
            extra={
                "request_id": state.request_id,
                "tools_called": len(results) - len(state.tool_results),
                "remaining": tracker.tool_calls_remaining,
            },
        )

        return state.model_copy(
            update={
                "tool_results": results,
                "tool_calls_remaining": tracker.tool_calls_remaining,
                "total_cost_usd": tracker.total_cost_usd,
                "total_tokens_used": tracker.total_tokens,
                "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
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
    plan: List[Dict[str, Any]] = []

    if state.intent == IntentType.SEARCH:
        plan.append({"tool": "catalog.search", "params": {"q": state.query}})
    elif state.intent == IntentType.SUBSTITUTE:
        plan.append({"tool": "catalog.search", "params": {"q": state.query}})
        store_id = state.context.get("store_id")
        items = state.context.get("items")
        if store_id and items:
            plan.append(
                {
                    "tool": "inventory.check",
                    "body": {
                        "storeId": store_id,
                        "items": items,
                    },
                }
            )
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

        vault = PIIVault()
        response, _ = vault.redact(response)

        # Output policy business rules and cap enforcement with structured tool outputs
        output_policy = OutputPolicy()
        structured = extract_structured_tool_outputs(
            state.tool_results,
            context=state.context,
        )
        output_payload: Dict[str, Any] = {
            "intent": state.intent.value,
            "response": response,
            "message": response,
            **structured,
        }
        val_result = output_policy.validate(
            output_payload,
            intent=state.intent.value,
            tool_results=state.tool_results,
        )
        if not val_result.is_valid:
            error_msgs = [v.message for v in val_result.violations if v.severity == "error"]
            logger.warning(
                "node.validate_output.violations",
                extra={"request_id": state.request_id, "violations": error_msgs},
            )
            return state.model_copy(
                update={
                    "response": response,
                    "needs_escalation": True,
                    "escalation_reason": f"Output policy violations: {'; '.join(error_msgs)}",
                    "errors": state.errors + error_msgs,
                    "current_node": "validate_output",
                    "completed_nodes": state.completed_nodes + ["validate_output"],
                }
            )
        if val_result.sanitised_output and "response" in val_result.sanitised_output:
            response = str(val_result.sanitised_output["response"])
        elif val_result.sanitised_output and "message" in val_result.sanitised_output:
            response = str(val_result.sanitised_output["message"])

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
