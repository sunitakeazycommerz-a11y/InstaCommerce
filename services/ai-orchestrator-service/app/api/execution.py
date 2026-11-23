"""Shared guarded execution facade and typed outcomes for agent APIs."""

from __future__ import annotations

from dataclasses import dataclass
import logging
import time
from typing import Any, Awaitable, Callable, Dict, List, Literal, Optional, Union

from app.audit import AuditEventPublisher
from app.graph.checkpoints import CheckpointSaver
from app.graph.output_guard import evaluate_output_policy
from app.graph.runner import AgentExecutionRunner
from app.graph.state import AgentState
from app.guardrails import InjectionDetector, OutputPolicy, PIIVault

logger = logging.getLogger("ai_orchestrator.api.execution")


@dataclass(frozen=True)
class BudgetExceededOutcome:
    request_id: str
    state: AgentState
    reason: str
    elapsed_ms: float


@dataclass(frozen=True)
class PolicyViolationOutcome:
    request_id: str
    stage: Literal["injection", "output_policy"]
    reason: str
    elapsed_ms: float
    state: Optional[AgentState] = None
    confidence: float = 0.0
    response_text: str = ""


@dataclass(frozen=True)
class CompletedOutcome:
    request_id: str
    state: AgentState
    response_text: str
    citations: List[str]
    elapsed_ms: float


ExecutionOutcome = Union[BudgetExceededOutcome, PolicyViolationOutcome, CompletedOutcome]

GraphWrapperFn = Callable[[Any, str, Dict[str, Any]], Any]


class ExecutionService:
    """Orchestrates injection detection, PII redaction, budgeted graph run, and output policy."""

    def __init__(
        self,
        *,
        injection_detector: Optional[InjectionDetector] = None,
        output_policy: Optional[OutputPolicy] = None,
    ) -> None:
        self._injection_detector = injection_detector or InjectionDetector()
        self._output_policy = output_policy or OutputPolicy()

    async def execute(
        self,
        *,
        query: str,
        user_id: str,
        session_id: Optional[str],
        context: Dict[str, Any],
        conversation_history: Optional[List[Dict[str, str]]] = None,
        request_id: Optional[str] = None,
        execute_tools: bool = True,
        max_cost_usd: float = 0.50,
        max_tokens: int = 0,
        max_latency_ms: float = 10_000.0,
        graph_provider: Callable[[], Awaitable[Any]],
        runner: AgentExecutionRunner,
        checkpointer: Optional[CheckpointSaver] = None,
        graph_wrapper: Optional[GraphWrapperFn] = None,
        restore_pii: bool = True,
    ) -> ExecutionOutcome:
        start_ns = time.monotonic_ns()

        is_injection, inj_conf, inj_reason = self._injection_detector.check(query)
        if is_injection:
            elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000
            return PolicyViolationOutcome(
                request_id=request_id or f"blocked-{int(time.time() * 1000)}",
                stage="injection",
                reason=inj_reason,
                confidence=inj_conf,
                elapsed_ms=elapsed_ms,
            )

        pii_vault = PIIVault()
        redacted_query, _ = pii_vault.redact(query)
        raw_context = pii_vault.redact_value(context)
        redacted_context = raw_context if isinstance(raw_context, dict) else {}
        raw_history = pii_vault.redact_value(conversation_history or [])
        redacted_history = raw_history if isinstance(raw_history, list) else []

        state_kwargs: Dict[str, Any] = {
            "query": redacted_query,
            "user_id": user_id,
            "session_id": session_id,
            "context": redacted_context,
            "conversation_history": redacted_history,
            "execute_tools": execute_tools,
            "max_cost_usd": min(max_cost_usd, 0.50),
            "max_tokens": max_tokens,
            "max_latency_ms": max_latency_ms,
        }
        if request_id is not None:
            state_kwargs["request_id"] = request_id

        initial_state = AgentState(**state_kwargs)
        compiled_graph = await graph_provider()
        target_graph = (
            graph_wrapper(compiled_graph, redacted_query, redacted_context)
            if graph_wrapper is not None
            else compiled_graph
        )

        result = await runner.run(
            target_graph,
            initial_state,
            checkpointer=checkpointer,
        )
        elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000

        if result.needs_escalation and result.escalation_category == "budget_exceeded":
            return BudgetExceededOutcome(
                request_id=result.request_id,
                state=result,
                reason=result.escalation_reason or "Session processing budget exceeded",
                elapsed_ms=elapsed_ms,
            )

        evaluation = evaluate_output_policy(result, output_policy=self._output_policy)
        if not evaluation.is_valid or (
            result.needs_escalation and result.escalation_category == "output_policy"
        ):
            reason = result.escalation_reason or (
                f"Output policy violations: {'; '.join(evaluation.error_messages)}"
            )
            return PolicyViolationOutcome(
                request_id=result.request_id,
                stage="output_policy",
                reason=reason,
                state=result,
                response_text=evaluation.response,
                elapsed_ms=elapsed_ms,
            )

        response_text = evaluation.response
        if restore_pii and bool((user_id or "").strip()):
            response_text = pii_vault.restore(
                response_text,
                allowed_types={"EMAIL", "PHONE", "ADDRESS"},
            )

        return CompletedOutcome(
            request_id=result.request_id,
            state=result,
            response_text=response_text,
            citations=evaluation.citations,
            elapsed_ms=elapsed_ms,
        )


async def publish_audit_events(
    publisher: Optional[AuditEventPublisher],
    *,
    version: str,
    request_id: str,
    user_id: Optional[str],
    session_id: Optional[str],
    intent: str,
    query_length: int,
    response_length: int,
    escalated: bool,
    mode: str = "graph",
    confidence: float = 0.0,
    risk_level: str = "low",
    escalation_reason: Optional[str] = None,
    tool_calls_count: int = 0,
    tool_results_count: int = 0,
    total_cost_usd: float = 0.0,
    elapsed_ms: float = 0.0,
    errors: Optional[List[str]] = None,
) -> None:
    if publisher is None:
        return
    try:
        if version == "v1":
            await publisher.publish_intent_classified(
                request_id=request_id,
                user_id=user_id,
                session_id=session_id,
                intent=intent,
                confidence=confidence,
                mode=mode,
                query_length=query_length,
            )
            await publisher.publish_response_generated(
                request_id=request_id,
                user_id=user_id,
                session_id=session_id,
                intent=intent,
                mode=mode,
                tool_calls_count=tool_calls_count,
                tool_results_count=tool_results_count,
                escalated=escalated,
                response_length=response_length,
            )
            return

        await publisher.publish_intent_classified(
            request_id=request_id,
            user_id=user_id,
            session_id=session_id,
            intent=intent,
            confidence=confidence,
            risk_level=risk_level,
            query_length=query_length,
        )
        if escalated:
            await publisher.publish_escalation_triggered(
                request_id=request_id,
                user_id=user_id,
                session_id=session_id,
                reason=escalation_reason or "unknown",
                intent=intent,
                risk_level=risk_level,
            )
        await publisher.publish_agent_invoked(
            request_id=request_id,
            user_id=user_id,
            session_id=session_id,
            intent=intent,
            intent_confidence=confidence,
            risk_level=risk_level,
            escalated=escalated,
            escalation_reason=escalation_reason,
            tool_results_count=tool_results_count,
            total_cost_usd=total_cost_usd,
            elapsed_ms=elapsed_ms,
            errors=errors or [],
        )
    except Exception:
        logger.debug("audit.%s_publish_failed", version, exc_info=True)
