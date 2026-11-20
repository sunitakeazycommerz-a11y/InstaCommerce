"""Determine when to escalate to human agents.

Provides a configurable rule engine that evaluates escalation triggers
against the current orchestrator state. Each trigger is a named callable
that receives the agent state and returns ``True`` when escalation is
warranted.
"""

import logging
import os
import re
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
_AUTONOMOUS_REFUND_CAP_CENTS: int = int(
    os.environ.get("ESCALATION_REFUND_CAP_CENTS", "1500")
)
_SENTIMENT_ESCALATION_THRESHOLD: float = float(
    os.environ.get("ESCALATION_SENTIMENT_THRESHOLD", "-0.6")
)
_DISPUTE_RE: re.Pattern[str] = re.compile(
    r"\b(?:chargeback|dispute|fraudulent|unauthorized(?:\s+charge)?|"
    r"unauthorised(?:\s+charge)?|consumer\s+court|ombudsman|lawsuit|legal\s+action)\b",
    re.IGNORECASE,
)
_CURRENCY_SYMBOL: str = os.environ.get("PLATFORM_CURRENCY_SYMBOL", "₹") or "₹"
_CURRENCY_TOKEN_PATTERN: str = rf"(?:{re.escape(_CURRENCY_SYMBOL)}|₹|INR\s*|Rs\.?\s*)"
_QUERY_REFUND_AMOUNT_RE: re.Pattern[str] = re.compile(
    rf"(?:refund|reimburse|credit)\s*(?:of|for|up\s+to)?\s*{_CURRENCY_TOKEN_PATTERN}\s*(\d+(?:\.\d{{1,2}})?)|"
    rf"{_CURRENCY_TOKEN_PATTERN}\s*(\d+(?:\.\d{{1,2}})?)\s*(?:refund|reimbursement)",
    re.IGNORECASE,
)
_NEGATIVE_SENTIMENT_WEIGHTS: Dict[str, float] = {
    "scam": -0.70,
    "fraud": -0.70,
    "stolen": -0.70,
    "thief": -0.70,
    "sue": -0.65,
    "lawyer": -0.65,
    "ripoff": -0.60,
    "rip-off": -0.60,
    "furious": -0.55,
    "outraged": -0.55,
    "disgusted": -0.55,
    "cheated": -0.55,
    "unacceptable": -0.45,
    "worst": -0.45,
    "pathetic": -0.45,
    "terrible": -0.40,
    "horrible": -0.40,
    "useless": -0.40,
    "incompetent": -0.40,
    "ridiculous": -0.35,
}
_WORD_RE: re.Pattern[str] = re.compile(r"[a-z]+(?:-[a-z]+)?")

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


def _extract_refund_cents(state: EscalationState) -> Optional[int]:
    candidates: List[int] = []
    for key in ("refund_amount_cents", "refund_cents"):
        val = state.metadata.get(key)
        if isinstance(val, (int, float)) and val > 0:
            candidates.append(int(val))
    if state.intent in ("refund", "support"):
        val = state.metadata.get("amount_cents")
        if isinstance(val, (int, float)) and val > 0:
            candidates.append(int(val))
    for result in state.tool_results:
        data = getattr(result, "data", None) or (
            result if isinstance(result, dict) else {}
        )
        if isinstance(data, dict):
            for key in ("refund_amount_cents", "refund_cents", "amount_cents"):
                amount = data.get(key)
                if isinstance(amount, (int, float)) and amount > 0:
                    candidates.append(int(amount))
    if state.intent in ("refund", "support") and state.query:
        for match in _QUERY_REFUND_AMOUNT_RE.finditer(state.query):
            raw_amount = match.group(1) or match.group(2)
            if raw_amount:
                candidates.append(int(round(float(raw_amount) * 100)))
    return max(candidates) if candidates else None


def _high_value_refund(state: EscalationState) -> bool:
    """True when any tool result contains a refund above the high-value threshold."""
    for result in state.tool_results:
        data = getattr(result, "data", None) or (
            result if isinstance(result, dict) else {}
        )
        if isinstance(data, dict):
            amount = data.get("amount_cents", 0)
            if isinstance(amount, (int, float)) and amount > _HIGH_VALUE_THRESHOLD_CENTS:
                return True
    return False


def _refund_cap_exceeded(state: EscalationState) -> bool:
    refund_cents = _extract_refund_cents(state)
    return refund_cents is not None and refund_cents > _AUTONOMOUS_REFUND_CAP_CENTS


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
    if bool(state.metadata.get("dispute")) or bool(state.metadata.get("chargeback")):
        return True
    if state.query and _DISPUTE_RE.search(state.query):
        return True
    return False


def compute_sentiment_score(text: str) -> float:
    if not text:
        return 0.0
    words = _WORD_RE.findall(text.lower())
    score = sum(_NEGATIVE_SENTIMENT_WEIGHTS.get(word, 0.0) for word in words)
    return max(-1.0, min(1.0, score))


def _negative_sentiment(state: EscalationState) -> bool:
    raw_score = state.metadata.get("sentiment_score")
    if isinstance(raw_score, (int, float)) and float(raw_score) <= _SENTIMENT_ESCALATION_THRESHOLD:
        return True
    raw_label = str(state.metadata.get("sentiment", "")).strip().lower()
    if raw_label in {"very_negative", "angry", "furious", "hostile"}:
        return True
    return compute_sentiment_score(state.query) <= _SENTIMENT_ESCALATION_THRESHOLD


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
        "refund_cap_exceeded": _refund_cap_exceeded,
        "low_confidence": _low_confidence,
        "user_requested": _user_requested,
        "repeated_failure": _repeated_failure,
        "safety_concern": _safety_concern,
        "payment_dispute": _payment_dispute,
        "negative_sentiment": _negative_sentiment,
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
