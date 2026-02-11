"""Determine when to escalate to human agents.

Provides a configurable rule engine that evaluates escalation triggers
against the current orchestrator state. Each trigger is a named callable
that receives the agent state and returns ``True`` when escalation is
warranted.
"""

import logging
import os
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configurable thresholds
# ---------------------------------------------------------------------------
_HIGH_VALUE_THRESHOLD_CENTS: int = int(
    os.environ.get("ESCALATION_HIGH_VALUE_CENTS", "50000")
)
_LOW_CONFIDENCE_THRESHOLD: float = float(
    os.environ.get("ESCALATION_LOW_CONFIDENCE", "0.5")
)
_MAX_ERRORS_BEFORE_ESCALATE: int = int(
    os.environ.get("ESCALATION_MAX_ERRORS", "3")
)

# Phrases that indicate the user wants a human agent
_HUMAN_REQUEST_PHRASES: List[str] = [
    "speak to human",
    "talk to agent",
    "talk to a human",
    "speak to a person",
    "real person",
    "human agent",
    "live agent",
    "transfer me",
]


@dataclass
class EscalationState:
    """Lightweight view of the orchestrator state used by escalation rules.

    Callers should populate this from the broader agent state dict before
    invoking :meth:`EscalationPolicy.should_escalate`.
    """

    query: str = ""
    intent: str = ""
    intent_confidence: float = 1.0
    risk_level: str = "low"
    tool_results: List[Any] = field(default_factory=list)
    errors: List[Any] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)


# Type alias for a single trigger function
EscalationTriggerFn = Callable[[EscalationState], bool]


def _high_value_refund(state: EscalationState) -> bool:
    """True when any tool result contains a refund above the threshold."""
    for result in state.tool_results:
        data = getattr(result, "data", None) or (
            result if isinstance(result, dict) else {}
        )
        if isinstance(data, dict):
            amount = data.get("amount_cents", 0)
            if isinstance(amount, (int, float)) and amount > _HIGH_VALUE_THRESHOLD_CENTS:
                return True
    return False


def _low_confidence(state: EscalationState) -> bool:
    return state.intent_confidence < _LOW_CONFIDENCE_THRESHOLD


def _user_requested(state: EscalationState) -> bool:
    lowered = state.query.lower()
    return any(phrase in lowered for phrase in _HUMAN_REQUEST_PHRASES)


def _repeated_failure(state: EscalationState) -> bool:
    return len(state.errors) >= _MAX_ERRORS_BEFORE_ESCALATE


def _safety_concern(state: EscalationState) -> bool:
    return state.risk_level == "critical"


def _payment_dispute(state: EscalationState) -> bool:
    return state.intent == "support" and "chargeback" in state.query.lower()


class EscalationPolicy:
    """Rules for when AI should hand off to human support.

    Each trigger is a ``(name, callable)`` pair. The policy evaluates all
    triggers in order and escalates on the first match.

    Usage::

        policy = EscalationPolicy()
        should, reason = policy.should_escalate(state)
        if should:
            # route to human queue
            ...

    Custom triggers can be added via :meth:`add_trigger`.
    """

    ESCALATION_TRIGGERS: Dict[str, EscalationTriggerFn] = {
        "high_value_refund": _high_value_refund,
        "low_confidence": _low_confidence,
        "user_requested": _user_requested,
        "repeated_failure": _repeated_failure,
        "safety_concern": _safety_concern,
        "payment_dispute": _payment_dispute,
    }

    def __init__(self) -> None:
        # Copy class-level triggers so per-instance modifications are safe
        self._triggers: Dict[str, EscalationTriggerFn] = dict(
            self.ESCALATION_TRIGGERS
        )

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def should_escalate(
        self, state: EscalationState
    ) -> Tuple[bool, Optional[str]]:
        """Check all triggers against *state*.

        Returns
        -------
        should_escalate : bool
            ``True`` when at least one trigger fires.
        reason : str | None
            Name of the first trigger that matched, or ``None``.
        """
        for name, trigger_fn in self._triggers.items():
            try:
                if trigger_fn(state):
                    logger.info(
                        "Escalation triggered",
                        extra={"trigger": name, "intent": state.intent},
                    )
                    return True, name
            except Exception:
                logger.exception(
                    "Escalation trigger '%s' raised an error — skipping",
                    name,
                )
        return False, None

    def add_trigger(self, name: str, fn: EscalationTriggerFn) -> None:
        """Register a custom escalation trigger."""
        self._triggers[name] = fn

    def remove_trigger(self, name: str) -> None:
        """Remove a trigger by name. No-op if not present."""
        self._triggers.pop(name, None)
