from __future__ import annotations

import logging
from typing import Dict, List

from app.graph.state import AgentState, IntentType, RiskLevel
from app.guardrails import InjectionDetector

logger = logging.getLogger("ai_orchestrator.intent")

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


def classify_intent(state: AgentState) -> AgentState:
    """Deterministic keyword-based intent classifier with confidence scoring."""
    try:
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
                    "escalation_category": "prompt_injection",
                    "current_node": "classify_intent",
                    "completed_nodes": state.completed_nodes + ["classify_intent"],
                }
            )

        if state.intent != IntentType.UNKNOWN and state.intent_confidence > 0.0:
            best_intent = state.intent
            confidence = state.intent_confidence
        else:
            query_lower = state.query.lower()
            scores: Dict[IntentType, int] = {}
            for intent, keywords in _INTENT_KEYWORDS.items():
                score = sum(1 for kw in keywords if kw in query_lower)
                if score > 0:
                    scores[intent] = score

            if scores:
                best_intent = max(scores, key=lambda item: scores[item])
                confidence = min(1.0, scores[best_intent] / max(len(_INTENT_KEYWORDS[best_intent]), 1))
            else:
                best_intent = IntentType.UNKNOWN
                confidence = 0.0

        risk = _RISK_INTENTS.get(best_intent, RiskLevel.MEDIUM)
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
                "escalation_category": "policy_escalation",
                "current_node": "classify_intent",
                "completed_nodes": state.completed_nodes + ["classify_intent"],
            }
        )
