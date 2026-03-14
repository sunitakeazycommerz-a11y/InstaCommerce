"""Kafka-based audit event publisher for AI orchestrator decisions.

Publishes structured audit events to the ``ai-orchestrator.events`` Kafka
topic so that the centralised audit-trail-service can ingest them into the
tamper-evident compliance chain.

The envelope format mirrors the convention used by all other InstaCommerce
domain services (see DomainEventConsumer in audit-trail-service):

    {
        "eventId": "<uuid>",
        "eventType": "AI_INTENT_CLASSIFIED",
        "schemaVersion": "1.0",
        "userId": "<uuid | null>",
        "actorType": "SYSTEM",
        "aggregateType": "ai_session",
        "aggregateId": "<request_id>",
        "correlationId": "<correlation_id | null>",
        "action": "AI_INTENT_CLASSIFIED",
        "payload": { ... }
    }

Design principles
-----------------
* **Fire-and-forget** -- publishing failures are logged but never propagate
  to the caller.  AI orchestrator latency must not be affected by audit
  infrastructure issues.
* **Async-native** -- uses ``aiokafka`` so the producer never blocks the
  FastAPI event loop.
* **Graceful lifecycle** -- ``start()`` / ``stop()`` are called from the
  FastAPI lifespan context manager.
"""

from __future__ import annotations

import json
import logging
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from uuid import uuid4

logger = logging.getLogger("ai_orchestrator.audit")

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

TOPIC = "ai-orchestrator.events"

# Audit event types emitted by the AI orchestrator
AI_INTENT_CLASSIFIED = "AI_INTENT_CLASSIFIED"
AI_TOOL_EXECUTED = "AI_TOOL_EXECUTED"
AI_ESCALATION_TRIGGERED = "AI_ESCALATION_TRIGGERED"
AI_RESPONSE_GENERATED = "AI_RESPONSE_GENERATED"
AI_AGENT_INVOKED = "AI_AGENT_INVOKED"


# ---------------------------------------------------------------------------
# Envelope builder
# ---------------------------------------------------------------------------

def _build_envelope(
    event_type: str,
    *,
    user_id: Optional[str] = None,
    aggregate_id: Optional[str] = None,
    correlation_id: Optional[str] = None,
    payload: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Build a Kafka event envelope matching the audit-trail-service schema.

    The ``DomainEventConsumer`` in audit-trail-service expects at minimum:
    ``eventType``, and optionally ``userId``, ``actorType``,
    ``aggregateType``, ``aggregateId``, ``correlationId``, ``action``,
    and ``payload``.
    """
    return {
        "eventId": str(uuid4()),
        "eventType": event_type,
        "schemaVersion": "1.0",
        "userId": user_id,
        "actorType": "SYSTEM",
        "aggregateType": "ai_session",
        "aggregateId": aggregate_id,
        "correlationId": correlation_id,
        "action": event_type,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "payload": payload or {},
    }


# ---------------------------------------------------------------------------
# Publisher
# ---------------------------------------------------------------------------

class AuditEventPublisher:
    """Async Kafka producer that publishes AI decision audit events.

    Usage::

        publisher = AuditEventPublisher(bootstrap_servers="kafka:9092")
        await publisher.start()
        ...
        await publisher.publish_intent_classified(...)
        ...
        await publisher.stop()

    If the publisher fails to connect or send, all errors are caught and
    logged -- callers are never impacted.
    """

    def __init__(
        self,
        bootstrap_servers: str = "localhost:9092",
        topic: str = TOPIC,
        client_id: str = "ai-orchestrator-audit",
    ) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._topic = topic
        self._client_id = client_id
        self._producer: Any = None  # aiokafka.AIOKafkaProducer
        self._started = False

    async def start(self) -> None:
        """Initialise and start the Kafka producer.

        Fails gracefully -- if ``aiokafka`` is not installed or the broker
        is unreachable, the publisher degrades to a no-op logger.
        """
        try:
            from aiokafka import AIOKafkaProducer

            self._producer = AIOKafkaProducer(
                bootstrap_servers=self._bootstrap_servers,
                client_id=self._client_id,
                # Use JSON-safe string serialisation
                value_serializer=lambda v: json.dumps(v, default=str).encode("utf-8"),
                key_serializer=lambda k: k.encode("utf-8") if k else None,
                # Durability: wait for leader ack (acks=1 balances latency
                # vs. reliability for audit events that are also logged locally)
                acks=1,
                # Retry transient broker errors
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
                extra={
                    "message": "aiokafka is not installed; audit events will only be logged",
                },
            )
        except Exception:
            logger.exception(
                "audit.publisher.start_failed",
                extra={
                    "bootstrap_servers": self._bootstrap_servers,
                },
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

    # ------------------------------------------------------------------
    # Internal send helper
    # ------------------------------------------------------------------

    async def _send(self, envelope: Dict[str, Any]) -> None:
        """Publish an envelope to Kafka, falling back to structured log."""
        # Always emit a structured log so events are captured even when
        # the broker is unavailable.
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

    # ------------------------------------------------------------------
    # High-level publish methods
    # ------------------------------------------------------------------

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
        envelope = _build_envelope(
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
        envelope = _build_envelope(
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
        envelope = _build_envelope(
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
        envelope = _build_envelope(
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
        envelope = _build_envelope(
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
        await self._send(envelope)


# ---------------------------------------------------------------------------
# No-op fallback
# ---------------------------------------------------------------------------

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
