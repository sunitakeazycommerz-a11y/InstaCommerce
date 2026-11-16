"""FastAPI v2 endpoints that invoke the LangGraph agent.

These handlers live under ``/v2/`` and coexist with the legacy endpoints
in ``main.py``.  They are mounted via ``app.include_router(router)``.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.audit import AuditEventPublisher
from app.config import Settings
from app.graph.checkpoints import CheckpointSaver, RedisCheckpointSaver
from app.graph.graph import build_agent_graph
from app.graph.nodes import AgentExecutionRunner, extract_structured_tool_outputs
from app.graph.state import AgentState, RiskLevel
from app.graph.tools import ToolRegistry
from app.guardrails import InjectionDetector, OutputPolicy, PIIVault

logger = logging.getLogger("ai_orchestrator.api")

router = APIRouter(prefix="/v2", tags=["agent-v2"])

# ---------------------------------------------------------------------------
# Module-level singletons (initialised on first use)
# ---------------------------------------------------------------------------
_settings: Optional[Settings] = None
_tool_registry: Optional[ToolRegistry] = None
_compiled_graph: Any = None
_checkpoint_saver: Optional[CheckpointSaver] = None
_audit_publisher: Optional[AuditEventPublisher] = None

_injection_detector = InjectionDetector()
_pii_vault = PIIVault()
_output_policy = OutputPolicy()
_execution_runner = AgentExecutionRunner()


def _get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


async def _get_registry() -> ToolRegistry:
    global _tool_registry
    if _tool_registry is None:
        _tool_registry = ToolRegistry(_get_settings())
        await _tool_registry.start()
    return _tool_registry


def set_checkpoint_saver(saver: Optional[CheckpointSaver]) -> None:
    """Inject the checkpoint saver (e.g., ``RedisCheckpointSaver``) from lifespan."""
    global _checkpoint_saver, _compiled_graph
    _checkpoint_saver = saver
    _compiled_graph = None


def set_compiled_graph(graph: Any) -> None:
    """Set pre-compiled graph explicitly (e.g., from lifespan)."""
    global _compiled_graph
    _compiled_graph = graph


async def _get_graph() -> Any:
    global _compiled_graph, _checkpoint_saver
    if _compiled_graph is None:
        settings = _get_settings()
        registry = await _get_registry()
        if _checkpoint_saver is None:
            _checkpoint_saver = RedisCheckpointSaver(redis_url=settings.redis_url)
        _compiled_graph = build_agent_graph(
            settings=settings,
            tool_registry=registry,
            checkpointer=_checkpoint_saver,
        )
    return _compiled_graph


async def shutdown_handlers() -> None:
    """Close shared v2 handler resources cleanly during lifespan shutdown."""
    global _tool_registry, _checkpoint_saver, _compiled_graph
    if _tool_registry is not None:
        await _tool_registry.close()
        _tool_registry = None
    if _checkpoint_saver is not None and hasattr(_checkpoint_saver, "close"):
        close_fn = getattr(_checkpoint_saver, "close")
        res = close_fn()
        if res is not None:
            await res
    _compiled_graph = None


def set_audit_publisher(publisher: AuditEventPublisher) -> None:
    """Set the module-level audit publisher (called from lifespan)."""
    global _audit_publisher
    _audit_publisher = publisher


def _get_audit_publisher() -> Optional[AuditEventPublisher]:
    return _audit_publisher


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------

class AgentRequest(BaseModel):
    """Inbound request to the agent."""

    query: str = Field(..., min_length=1, max_length=4_000)
    user_id: str = Field(..., min_length=1, max_length=256)
    session_id: Optional[str] = None
    context: Dict[str, Any] = Field(default_factory=dict)
    conversation_history: List[Dict[str, str]] = Field(default_factory=list)
    max_cost_usd: float = Field(default=0.50, ge=0.0, le=0.50)
    max_tokens: int = Field(default=0, ge=0)
    max_latency_ms: float = Field(default=10_000.0, ge=0.0, le=30_000.0)


class AgentResponse(BaseModel):
    """Agent response payload."""

    request_id: str
    response: str
    intent: str
    intent_confidence: float
    risk_level: str
    citations: List[str] = Field(default_factory=list)
    escalated: bool = False
    escalation_reason: Optional[str] = None
    tool_results_summary: List[Dict[str, Any]] = Field(default_factory=list)
    total_tokens_used: int = 0
    total_cost_usd: float = 0.0
    elapsed_ms: float = 0.0
    errors: List[str] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Shared guarded execution helper
# ---------------------------------------------------------------------------

async def _execute_guarded_agent(request: AgentRequest) -> AgentResponse:
    """Run injection detection, PII redaction, budgeted graph invocation, and output policy."""
    start_ns = time.monotonic_ns()

    try:
        # Step 1: Prompt injection check BEFORE invoking graph
        is_injection, inj_conf, inj_reason = _injection_detector.check(request.query)
        if is_injection:
            logger.warning(
                "api.assist.injection_blocked",
                extra={"reason": inj_reason, "confidence": inj_conf, "user_id": request.user_id},
            )
            elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000
            return AgentResponse(
                request_id=f"blocked-{int(time.time() * 1000)}",
                response=(
                    "Your request could not be processed automatically. "
                    "I am connecting you with a support specialist."
                ),
                intent="unknown",
                intent_confidence=0.0,
                risk_level=RiskLevel.CRITICAL.value,
                escalated=True,
                escalation_reason=f"Prompt injection detected: {inj_reason}",
                elapsed_ms=round(elapsed_ms, 1),
            )

        # Step 2: Redact PII in query BEFORE invoking graph
        redacted_query, _ = _pii_vault.redact(request.query)
        redacted_context = _pii_vault.redact_value(request.context)

        graph = await _get_graph()

        initial_state = AgentState(
            query=redacted_query,
            user_id=request.user_id,
            session_id=request.session_id,
            context=redacted_context if isinstance(redacted_context, dict) else {},
            conversation_history=request.conversation_history,
            max_cost_usd=min(request.max_cost_usd, 0.50),  # hard cap
            max_tokens=request.max_tokens,
            max_latency_ms=request.max_latency_ms,
        )

        # Step 3: Enforce request and session budgets via AgentExecutionRunner
        result = await _execution_runner.run(graph, initial_state)

        # Step 4: Pass structured tool outputs into _output_policy.validate(raw_output)
        structured_tools = extract_structured_tool_outputs(
            result.tool_results,
            tool_calls=getattr(result, "tool_calls", None),
            context=result.context,
        )
        raw_output: Dict[str, Any] = {
            "intent": result.intent.value,
            "response": result.response,
            "message": result.response,
            **structured_tools,
        }
        val_result = _output_policy.validate(raw_output, tool_results=result.tool_results)
        final_response_text = result.response
        if val_result.sanitised_output:
            final_response_text = str(
                val_result.sanitised_output.get("response")
                or val_result.sanitised_output.get("message")
                or final_response_text
            )
        escalated = result.needs_escalation
        escalation_reason = result.escalation_reason
        if not val_result.is_valid:
            error_msgs = [v.message for v in val_result.violations if v.severity == "error"]
            escalated = True
            escalation_reason = escalation_reason or f"Output policy violations: {'; '.join(error_msgs)}"
            final_response_text = (
                "I'm connecting you with a support specialist who can better assist you. "
                f"Your reference number is {result.request_id}."
            )

        elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000

        logger.info(
            "api.invoke_agent.complete",
            extra={
                "request_id": result.request_id,
                "intent": result.intent.value,
                "elapsed_ms": round(elapsed_ms, 1),
                "escalated": escalated,
            },
        )

        # Publish audit events (fire-and-forget)
        publisher = _get_audit_publisher()
        if publisher is not None:
            try:
                await publisher.publish_intent_classified(
                    request_id=result.request_id,
                    user_id=request.user_id,
                    session_id=request.session_id,
                    intent=result.intent.value,
                    confidence=result.intent_confidence,
                    risk_level=result.risk_level.value,
                    query_length=len(redacted_query),
                )
                if escalated:
                    await publisher.publish_escalation_triggered(
                        request_id=result.request_id,
                        user_id=request.user_id,
                        session_id=request.session_id,
                        reason=escalation_reason or "unknown",
                        intent=result.intent.value,
                        risk_level=result.risk_level.value,
                    )
                await publisher.publish_agent_invoked(
                    request_id=result.request_id,
                    user_id=request.user_id,
                    session_id=request.session_id,
                    intent=result.intent.value,
                    intent_confidence=result.intent_confidence,
                    risk_level=result.risk_level.value,
                    escalated=escalated,
                    escalation_reason=escalation_reason,
                    tool_results_count=len(result.tool_results),
                    total_cost_usd=result.total_cost_usd,
                    elapsed_ms=elapsed_ms,
                    errors=result.errors,
                )
            except Exception:
                logger.debug("audit.v2_publish_failed", exc_info=True)

        return AgentResponse(
            request_id=result.request_id,
            response=final_response_text,
            intent=result.intent.value,
            intent_confidence=result.intent_confidence,
            risk_level=result.risk_level.value,
            citations=result.response_citations,
            escalated=escalated,
            escalation_reason=escalation_reason,
            tool_results_summary=[
                {
                    "tool": tr.tool_name,
                    "success": tr.success,
                    "latency_ms": round(tr.latency_ms, 1),
                }
                for tr in result.tool_results
            ],
            total_tokens_used=result.total_tokens_used,
            total_cost_usd=result.total_cost_usd,
            elapsed_ms=round(elapsed_ms, 1),
            errors=result.errors,
        )
    except HTTPException:
        raise
    except Exception:
        elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000
        logger.exception(
            "api.invoke_agent.error",
            extra={"elapsed_ms": round(elapsed_ms, 1)},
        )
        raise HTTPException(
            status_code=500,
            detail="Agent invocation failed due to an internal error",
        )


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@router.post("/agent/invoke", response_model=AgentResponse)
async def invoke_agent(request: AgentRequest) -> AgentResponse:
    """Invoke the LangGraph agent for a user query."""
    return await _execute_guarded_agent(request)


@router.post("/assist", response_model=AgentResponse)
async def assist_v2(request: AgentRequest) -> AgentResponse:
    """v2 assist endpoint applying input guardrails, graph execution, and structured output validation."""
    return await _execute_guarded_agent(request)


@router.get("/agent/health")
async def agent_health() -> Dict[str, str]:
    """Health check for the v2 agent subsystem."""
    return {"status": "ok", "version": "v2"}
