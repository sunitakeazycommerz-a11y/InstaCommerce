"""FastAPI v2 endpoints and shared orchestrator runtime singletons."""

from __future__ import annotations

import logging
import time
from typing import Dict, Optional

from fastapi import APIRouter, HTTPException

from app.api.execution import (
    BudgetExceededOutcome,
    ExecutionService,
    PolicyViolationOutcome,
    publish_audit_events,
)
from app.audit import AuditEventPublisher
from app.cache.semantic_cache import SemanticCache
from app.config import Settings
from app.core.llm import CachedRetrievalProvider, LlmClient
from app.graph.checkpoints import CheckpointSaver, RedisCheckpointSaver
from app.graph.graph import build_agent_graph
from app.graph.runner import AgentExecutionRunner
from app.graph.state import RiskLevel
from app.graph.tools import ToolRegistry
from app.guardrails import InjectionDetector, OutputPolicy
from app.schemas import AgentRequest, AgentResponse

logger = logging.getLogger("ai_orchestrator.api")

router = APIRouter(prefix="/v2", tags=["agent-v2"])

_settings: Optional[Settings] = None
_tool_registry: Optional[ToolRegistry] = None
_compiled_graph = None
_checkpoint_saver: Optional[CheckpointSaver] = None
_audit_publisher: Optional[AuditEventPublisher] = None
_llm_client: Optional[LlmClient] = None
_retriever: Optional[CachedRetrievalProvider] = None
_semantic_cache: Optional[SemanticCache] = None

_injection_detector = InjectionDetector()
_output_policy = OutputPolicy()
_execution_runner = AgentExecutionRunner()


def _get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


def get_settings() -> Settings:
    """Return the shared ``Settings`` singleton."""
    return _get_settings()


async def _get_registry() -> ToolRegistry:
    global _tool_registry
    if _tool_registry is None:
        _tool_registry = ToolRegistry(_get_settings())
        await _tool_registry.start()
    return _tool_registry


async def get_registry() -> ToolRegistry:
    """Return the shared ``ToolRegistry`` singleton."""
    return await _get_registry()


def get_execution_runner() -> AgentExecutionRunner:
    """Return the shared ``AgentExecutionRunner`` enforcing bounded session budgets."""
    return _execution_runner


def set_checkpoint_saver(saver: Optional[CheckpointSaver]) -> None:
    """Inject the checkpoint saver (e.g., ``RedisCheckpointSaver``) from lifespan."""
    global _checkpoint_saver, _compiled_graph
    _checkpoint_saver = saver
    _compiled_graph = None


def get_checkpoint_saver() -> Optional[CheckpointSaver]:
    """Return the active checkpoint saver."""
    return _checkpoint_saver


def set_compiled_graph(graph) -> None:
    """Set pre-compiled graph explicitly (e.g., from lifespan or tests)."""
    global _compiled_graph
    _compiled_graph = graph


async def _get_graph():
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
            llm_client=_llm_client,
            retriever=_retriever,
            semantic_cache=_semantic_cache,
        )
    return _compiled_graph


async def get_graph():
    """Return the compiled LangGraph instance."""
    return await _get_graph()


def set_audit_publisher(publisher: Optional[AuditEventPublisher]) -> None:
    """Set the module-level audit publisher (called from lifespan)."""
    global _audit_publisher
    _audit_publisher = publisher


def _get_audit_publisher() -> Optional[AuditEventPublisher]:
    return _audit_publisher


def get_audit_publisher() -> Optional[AuditEventPublisher]:
    """Return the active audit publisher."""
    return _audit_publisher


def set_llm_client(client: Optional[LlmClient]) -> None:
    global _llm_client, _compiled_graph
    _llm_client = client
    _compiled_graph = None


def get_llm_client() -> Optional[LlmClient]:
    return _llm_client


def set_retriever(retriever: Optional[CachedRetrievalProvider]) -> None:
    global _retriever, _compiled_graph
    _retriever = retriever
    _compiled_graph = None


def get_retriever() -> Optional[CachedRetrievalProvider]:
    return _retriever


def set_semantic_cache(cache: Optional[SemanticCache]) -> None:
    global _semantic_cache, _compiled_graph
    _semantic_cache = cache
    _compiled_graph = None


def get_semantic_cache() -> Optional[SemanticCache]:
    return _semantic_cache


async def shutdown_handlers() -> None:
    """Close shared handler resources cleanly once during lifespan shutdown."""
    global _tool_registry, _checkpoint_saver, _compiled_graph, _llm_client
    if _tool_registry is not None:
        try:
            await _tool_registry.close()
        except Exception:
            logger.exception("handlers.shutdown.tool_registry_close_failed")
        finally:
            _tool_registry = None

    if _checkpoint_saver is not None and hasattr(_checkpoint_saver, "close"):
        try:
            close_fn = getattr(_checkpoint_saver, "close")
            res = close_fn()
            if res is not None:
                await res
        except Exception:
            logger.exception("handlers.shutdown.checkpoint_saver_close_failed")
        finally:
            _checkpoint_saver = None
    else:
        _checkpoint_saver = None

    if _llm_client is not None:
        try:
            await _llm_client.close()
        except Exception:
            logger.exception("handlers.shutdown.llm_client_close_failed")
        finally:
            _llm_client = None

    try:
        await _execution_runner.close()
    except Exception:
        logger.exception("handlers.shutdown.runner_close_failed")

    _compiled_graph = None


async def _execute_guarded_agent(request: AgentRequest) -> AgentResponse:
    """Run guarded execution via shared ``ExecutionService`` and map to ``AgentResponse``."""
    start_ns = time.monotonic_ns()
    service = ExecutionService(
        injection_detector=_injection_detector,
        output_policy=_output_policy,
    )
    try:
        outcome = await service.execute(
            query=request.query,
            user_id=request.user_id,
            session_id=request.session_id,
            context=request.context,
            conversation_history=request.conversation_history,
            max_cost_usd=request.max_cost_usd,
            max_tokens=request.max_tokens,
            max_latency_ms=request.max_latency_ms,
            graph_provider=_get_graph,
            runner=_execution_runner,
            checkpointer=_checkpoint_saver,
        )

        if isinstance(outcome, PolicyViolationOutcome) and outcome.stage == "injection":
            logger.warning(
                "api.assist.injection_blocked",
                extra={
                    "reason": outcome.reason,
                    "confidence": outcome.confidence,
                    "user_id": request.user_id,
                },
            )
            return AgentResponse(
                request_id=outcome.request_id,
                response=(
                    "Your request could not be processed automatically. "
                    "I am connecting you with a support specialist."
                ),
                intent="unknown",
                intent_confidence=0.0,
                risk_level=RiskLevel.CRITICAL.value,
                escalated=True,
                escalation_reason=f"Prompt injection detected: {outcome.reason}",
                elapsed_ms=round(outcome.elapsed_ms, 1),
            )

        assert outcome.state is not None
        result = outcome.state
        if isinstance(outcome, PolicyViolationOutcome):
            escalated = True
            escalation_reason = outcome.reason
            final_response_text = (
                "I'm connecting you with a support specialist who can better assist you. "
                f"Your reference number is {result.request_id}."
            )
        elif isinstance(outcome, BudgetExceededOutcome):
            escalated = True
            escalation_reason = outcome.reason
            final_response_text = result.response
        else:
            escalated = result.needs_escalation
            escalation_reason = result.escalation_reason
            final_response_text = outcome.response_text

        logger.info(
            "api.invoke_agent.complete",
            extra={
                "request_id": result.request_id,
                "intent": result.intent.value,
                "elapsed_ms": round(outcome.elapsed_ms, 1),
                "escalated": escalated,
            },
        )

        await publish_audit_events(
            _get_audit_publisher(),
            version="v2",
            request_id=result.request_id,
            user_id=request.user_id,
            session_id=request.session_id,
            intent=result.intent.value,
            confidence=result.intent_confidence,
            risk_level=result.risk_level.value,
            query_length=len(result.query),
            response_length=len(final_response_text),
            escalated=escalated,
            escalation_reason=escalation_reason,
            tool_results_count=len(result.tool_results),
            total_cost_usd=result.total_cost_usd,
            elapsed_ms=outcome.elapsed_ms,
            errors=result.errors,
        )

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
            elapsed_ms=round(outcome.elapsed_ms, 1),
            errors=result.errors,
        )
    except HTTPException:
        raise
    except Exception:
        elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000
        logger.exception("api.invoke_agent.error", extra={"elapsed_ms": round(elapsed_ms, 1)})
        raise HTTPException(
            status_code=500,
            detail="Agent invocation failed due to an internal error",
        )


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
