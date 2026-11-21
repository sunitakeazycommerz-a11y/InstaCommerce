"""Audit event constants and Kafka envelope builders."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from uuid import uuid4

TOPIC = "ai-orchestrator.events"

AI_INTENT_CLASSIFIED = "AI_INTENT_CLASSIFIED"
AI_TOOL_EXECUTED = "AI_TOOL_EXECUTED"
AI_ESCALATION_TRIGGERED = "AI_ESCALATION_TRIGGERED"
AI_RESPONSE_GENERATED = "AI_RESPONSE_GENERATED"
AI_AGENT_INVOKED = "AI_AGENT_INVOKED"


def build_envelope(
    event_type: str,
    *,
    user_id: Optional[str] = None,
    aggregate_id: Optional[str] = None,
    correlation_id: Optional[str] = None,
    payload: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Build a Kafka event envelope matching the audit-trail-service schema."""
    now_iso = datetime.now(timezone.utc).isoformat()
    event_id = str(uuid4())
    return {
        "id": event_id,
        "eventId": event_id,
        "event_id": event_id,
        "eventType": event_type,
        "event_type": event_type,
        "schemaVersion": "v1",
        "schema_version": "v1",
        "sourceService": "ai-orchestrator-service",
        "source_service": "ai-orchestrator-service",
        "userId": user_id,
        "actorType": "SYSTEM",
        "actor_type": "SYSTEM",
        "aggregateType": "ai_session",
        "aggregate_type": "ai_session",
        "aggregateId": aggregate_id,
        "aggregate_id": aggregate_id,
        "correlationId": correlation_id,
        "correlation_id": correlation_id,
        "action": event_type,
        "eventTime": now_iso,
        "event_time": now_iso,
        "timestamp": now_iso,
        "payload": payload or {},
    }


def intent_classified_envelope(
    *,
    request_id: str,
    user_id: Optional[str] = None,
    session_id: Optional[str] = None,
    correlation_id: Optional[str] = None,
    intent: str,
    confidence: float,
    risk_level: str = "low",
    mode: str = "fallback",
    query_length: int = 0,
) -> Dict[str, Any]:
    return build_envelope(
        AI_INTENT_CLASSIFIED,
        user_id=user_id,
        aggregate_id=request_id,
        correlation_id=correlation_id,
        payload={
            "session_id": session_id,
            "intent": intent,
            "confidence": round(confidence, 4),
            "risk_level": risk_level,
            "mode": mode,
            "query_length": query_length,
        },
    )


def tool_executed_envelope(
    *,
    request_id: str,
    user_id: Optional[str] = None,
    session_id: Optional[str] = None,
    correlation_id: Optional[str] = None,
    tool_name: str,
    success: bool,
    error: Optional[str] = None,
    duration_ms: int = 0,
    status_code: Optional[int] = None,
) -> Dict[str, Any]:
    return build_envelope(
        AI_TOOL_EXECUTED,
        user_id=user_id,
        aggregate_id=request_id,
        correlation_id=correlation_id,
        payload={
            "session_id": session_id,
            "tool_name": tool_name,
            "success": success,
            "error": error,
            "duration_ms": duration_ms,
            "status_code": status_code,
        },
    )


def escalation_triggered_envelope(
    *,
    request_id: str,
    user_id: Optional[str] = None,
    session_id: Optional[str] = None,
    correlation_id: Optional[str] = None,
    reason: str,
    intent: str = "unknown",
    risk_level: str = "high",
) -> Dict[str, Any]:
    return build_envelope(
        AI_ESCALATION_TRIGGERED,
        user_id=user_id,
        aggregate_id=request_id,
        correlation_id=correlation_id,
        payload={
            "session_id": session_id,
            "reason": reason,
            "intent": intent,
            "risk_level": risk_level,
        },
    )


def response_generated_envelope(
    *,
    request_id: str,
    user_id: Optional[str] = None,
    session_id: Optional[str] = None,
    correlation_id: Optional[str] = None,
    intent: str,
    mode: str = "fallback",
    tool_calls_count: int = 0,
    tool_results_count: int = 0,
    escalated: bool = False,
    response_length: int = 0,
) -> Dict[str, Any]:
    return build_envelope(
        AI_RESPONSE_GENERATED,
        user_id=user_id,
        aggregate_id=request_id,
        correlation_id=correlation_id,
        payload={
            "session_id": session_id,
            "intent": intent,
            "mode": mode,
            "tool_calls_count": tool_calls_count,
            "tool_results_count": tool_results_count,
            "escalated": escalated,
            "response_length": response_length,
        },
    )


def agent_invoked_envelope(
    *,
    request_id: str,
    user_id: Optional[str] = None,
    session_id: Optional[str] = None,
    correlation_id: Optional[str] = None,
    intent: str,
    intent_confidence: float = 0.0,
    risk_level: str = "low",
    escalated: bool = False,
    escalation_reason: Optional[str] = None,
    tool_results_count: int = 0,
    total_cost_usd: float = 0.0,
    elapsed_ms: float = 0.0,
    errors: Optional[List[str]] = None,
) -> Dict[str, Any]:
    return build_envelope(
        AI_AGENT_INVOKED,
        user_id=user_id,
        aggregate_id=request_id,
        correlation_id=correlation_id,
        payload={
            "session_id": session_id,
            "intent": intent,
            "intent_confidence": round(intent_confidence, 4),
            "risk_level": risk_level,
            "escalated": escalated,
            "escalation_reason": escalation_reason,
            "tool_results_count": tool_results_count,
            "total_cost_usd": round(total_cost_usd, 6),
            "elapsed_ms": round(elapsed_ms, 1),
            "error_count": len(errors) if errors else 0,
        },
    )
