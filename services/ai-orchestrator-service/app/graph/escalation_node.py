from __future__ import annotations

import logging
from typing import List, Optional

from app.config import Settings
from app.graph.budgets import BudgetExceededError, budget_tracker_from_state
from app.graph.state import AgentState, RiskLevel
from app.guardrails import EscalationPolicy
from app.guardrails.escalation import EscalationState

logger = logging.getLogger("ai_orchestrator.escalation")

_DEFAULT_SETTINGS = Settings()


def check_policy(state: AgentState, settings: Optional[Settings] = None) -> AgentState:
    """Pre-execution policy gate backed by ``BudgetTracker`` and ``EscalationPolicy``."""
    try:
        resolved_settings = settings or _DEFAULT_SETTINGS
        tracker = budget_tracker_from_state(state, settings=resolved_settings)
        elapsed_ms = tracker.elapsed_ms
        violations: List[str] = []
        budget_violated = False

        try:
            tracker.check_or_raise()
        except BudgetExceededError:
            budget_violated = True
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

        if state.risk_level == RiskLevel.CRITICAL:
            violations.append(f"Risk level {state.risk_level.value} requires human review")

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
                extra={"request_id": state.request_id, "violations": violations},
            )
            category = state.escalation_category or (
                "budget_exceeded" if budget_violated else "policy_escalation"
            )
            return state.model_copy(
                update={
                    "elapsed_ms": elapsed_ms,
                    "needs_escalation": True,
                    "escalation_reason": "; ".join(violations),
                    "escalation_category": category,
                    "current_node": "check_policy",
                    "completed_nodes": state.completed_nodes + ["check_policy"],
                }
            )

        logger.info("node.check_policy.passed", extra={"request_id": state.request_id})
        return state.model_copy(
            update={
                "elapsed_ms": elapsed_ms,
                "current_node": "check_policy",
                "completed_nodes": state.completed_nodes + ["check_policy"],
            }
        )
    except Exception as exc:
        logger.exception("node.check_policy.error", extra={"request_id": state.request_id})
        return state.model_copy(
            update={
                "elapsed_ms": state.current_elapsed_ms(),
                "errors": state.errors + [f"check_policy: {exc}"],
                "needs_escalation": True,
                "escalation_reason": "Policy check failed unexpectedly",
                "escalation_category": "policy_escalation",
                "current_node": "check_policy",
                "completed_nodes": state.completed_nodes + ["check_policy"],
            }
        )


def escalate(state: AgentState) -> AgentState:
    """Package context for human escalation and build specialist handoff message."""
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
                "escalation_category": state.escalation_category or "policy_escalation",
                "context": {**state.context, "escalation": escalation_context},
                "current_node": "escalate",
                "completed_nodes": state.completed_nodes + ["escalate"],
            }
        )
    except Exception as exc:
        logger.exception("node.escalate.error", extra={"request_id": state.request_id})
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
