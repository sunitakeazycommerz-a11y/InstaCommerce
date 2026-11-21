"""Kafka-based audit event publisher for AI orchestrator decisions.

Publishes structured audit events to the ``ai-orchestrator.events`` Kafka
topic so that the centralised audit-trail-service can ingest them into the
tamper-evident compliance chain.
"""

from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional
from uuid import uuid4

from app.audit_envelope import (
    AI_AGENT_INVOKED,
    AI_ESCALATION_TRIGGERED,
    AI_INTENT_CLASSIFIED,
    AI_RESPONSE_GENERATED,
    AI_TOOL_EXECUTED,
    TOPIC,
    agent_invoked_envelope,
    build_envelope,
    escalation_triggered_envelope,
    intent_classified_envelope,
    response_generated_envelope,
    tool_executed_envelope,
)

try:
    from aiokafka import AIOKafkaProducer as _AIOKafkaProducer
    AIOKafkaProducer: Any = _AIOKafkaProducer
except ImportError:
    AIOKafkaProducer = None

logger = logging.getLogger("ai_orchestrator.audit")

_build_envelope = build_envelope

__all__ = [
    "AI_AGENT_INVOKED",
    "AI_ESCALATION_TRIGGERED",
    "AI_INTENT_CLASSIFIED",
    "AI_RESPONSE_GENERATED",
    "AI_TOOL_EXECUTED",
    "AuditEventPublisher",
    "NoOpAuditPublisher",
    "TOPIC",
    "_build_envelope",
    "build_envelope",
]


class AuditEventPublisher:
    """Async Kafka producer that publishes AI decision audit events."""

    def __init__(
        self,
        bootstrap_servers: str = "localhost:9092",
        topic: str = TOPIC,
        client_id: str = "ai-orchestrator-audit",
    ) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._topic = topic
        self._client_id = client_id
        self._producer: Any = None
        self._started = False

    async def start(self) -> None:
        """Initialise and start the Kafka producer."""
        if AIOKafkaProducer is None:
            logger.warning(
                "audit.publisher.aiokafka_not_installed",
                extra={"message": "aiokafka is not installed; audit events will only be logged"},
            )
            return

        try:
            self._producer = AIOKafkaProducer(
                bootstrap_servers=self._bootstrap_servers,
                client_id=self._client_id,
                value_serializer=lambda v: json.dumps(v, default=str).encode("utf-8"),
                key_serializer=lambda k: k.encode("utf-8") if k else None,
                acks=1,
                retry_backoff_ms=100,
                request_timeout_ms=5000,
            )
            await self._producer.start()
            self._started = True
            logger.info(
                "audit.publisher.started",
                extra={
                    "bootstrap_servers": self._bootstrap_servers,
                    "topic": self._topic,
                },
            )
        except ImportError:
            logger.warning(
                "audit.publisher.aiokafka_not_installed",
                extra={"message": "aiokafka is not installed; audit events will only be logged"},
            )
        except Exception:
            logger.exception(
                "audit.publisher.start_failed",
                extra={"bootstrap_servers": self._bootstrap_servers},
            )

    async def stop(self) -> None:
        """Flush pending messages and shut down the producer."""
        if self._producer and self._started:
            try:
                await self._producer.stop()
                logger.info("audit.publisher.stopped")
            except Exception:
                logger.exception("audit.publisher.stop_failed")
            finally:
                self._started = False
                self._producer = None

    async def _send(self, envelope: Dict[str, Any]) -> None:
        """Publish an envelope to Kafka, falling back to structured log."""
        logger.info(
            "audit.event",
            extra={
                "event_type": envelope.get("eventType"),
                "aggregate_id": envelope.get("aggregateId"),
                "user_id": envelope.get("userId"),
            },
        )

        if not self._started or self._producer is None:
            return

        try:
            key = envelope.get("aggregateId") or str(uuid4())
            await self._producer.send(
                self._topic,
                value=envelope,
                key=key,
            )
        except Exception:
            logger.exception(
                "audit.publisher.send_failed",
                extra={
                    "event_type": envelope.get("eventType"),
                    "aggregate_id": envelope.get("aggregateId"),
                },
            )

    async def publish_intent_classified(
        self,
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
    ) -> None:
        """Emit an ``AI_INTENT_CLASSIFIED`` audit event."""
        envelope = intent_classified_envelope(
            request_id=request_id,
            user_id=user_id,
            session_id=session_id,
            correlation_id=correlation_id,
            intent=intent,
            confidence=confidence,
            risk_level=risk_level,
            mode=mode,
            query_length=query_length,
        )
        await self._send(envelope)

    async def publish_tool_executed(
        self,
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
    ) -> None:
        """Emit an ``AI_TOOL_EXECUTED`` audit event."""
        envelope = tool_executed_envelope(
            request_id=request_id,
            user_id=user_id,
            session_id=session_id,
            correlation_id=correlation_id,
            tool_name=tool_name,
            success=success,
            error=error,
            duration_ms=duration_ms,
            status_code=status_code,
        )
        await self._send(envelope)

    async def publish_escalation_triggered(
        self,
        *,
        request_id: str,
        user_id: Optional[str] = None,
        session_id: Optional[str] = None,
        correlation_id: Optional[str] = None,
        reason: str,
        intent: str = "unknown",
        risk_level: str = "high",
    ) -> None:
        """Emit an ``AI_ESCALATION_TRIGGERED`` audit event."""
        envelope = escalation_triggered_envelope(
            request_id=request_id,
            user_id=user_id,
            session_id=session_id,
            correlation_id=correlation_id,
            reason=reason,
            intent=intent,
            risk_level=risk_level,
        )
        await self._send(envelope)

    async def publish_response_generated(
        self,
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
    ) -> None:
        """Emit an ``AI_RESPONSE_GENERATED`` audit event."""
        envelope = response_generated_envelope(
            request_id=request_id,
            user_id=user_id,
            session_id=session_id,
            correlation_id=correlation_id,
            intent=intent,
            mode=mode,
            tool_calls_count=tool_calls_count,
            tool_results_count=tool_results_count,
            escalated=escalated,
            response_length=response_length,
        )
        await self._send(envelope)

    async def publish_agent_invoked(
        self,
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
    ) -> None:
        """Emit an ``AI_AGENT_INVOKED`` audit event (v2 endpoint summary)."""
        envelope = agent_invoked_envelope(
            request_id=request_id,
            user_id=user_id,
            session_id=session_id,
            correlation_id=correlation_id,
            intent=intent,
            intent_confidence=intent_confidence,
            risk_level=risk_level,
            escalated=escalated,
            escalation_reason=escalation_reason,
            tool_results_count=tool_results_count,
            total_cost_usd=total_cost_usd,
            elapsed_ms=elapsed_ms,
            errors=errors,
        )
        await self._send(envelope)


class NoOpAuditPublisher(AuditEventPublisher):
    """Publisher that only logs -- used when Kafka is explicitly disabled."""

    async def start(self) -> None:
        logger.info("audit.publisher.noop_mode")

    async def stop(self) -> None:
        pass

    async def _send(self, envelope: Dict[str, Any]) -> None:
        logger.info(
            "audit.event.noop",
            extra={
                "event_type": envelope.get("eventType"),
                "aggregate_id": envelope.get("aggregateId"),
            },
        )
