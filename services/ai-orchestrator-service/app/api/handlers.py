"""FastAPI v2 endpoints that invoke the LangGraph agent.

These handlers live under ``/v2/`` and coexist with the legacy endpoints
in ``main.py``.  They are mounted via ``app.include_router(router)``.
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from app.config import Settings
from app.graph.graph import build_graph
from app.graph.state import AgentState, IntentType, RiskLevel
from app.graph.tools import ToolRegistry

logger = logging.getLogger("ai_orchestrator.api")

router = APIRouter(prefix="/v2", tags=["agent-v2"])

# ---------------------------------------------------------------------------
# Module-level singletons (initialised on first use)
# ---------------------------------------------------------------------------
_settings: Optional[Settings] = None
_tool_registry: Optional[ToolRegistry] = None
_compiled_graph: Any = None


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


async def _get_graph() -> Any:
    global _compiled_graph
    if _compiled_graph is None:
        registry = await _get_registry()
        _compiled_graph = build_graph(
            settings=_get_settings(),
            tool_registry=registry,
        )
    return _compiled_graph


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
# Endpoints
# ---------------------------------------------------------------------------

@router.post("/agent/invoke", response_model=AgentResponse)
async def invoke_agent(request: AgentRequest) -> AgentResponse:
    """Invoke the LangGraph agent for a user query.

    This is the primary v2 endpoint.  It constructs an ``AgentState``,
    runs the compiled graph, and returns a structured response.
    """
    start_ns = time.monotonic_ns()

    try:
        graph = await _get_graph()

        initial_state = AgentState(
            query=request.query,
            user_id=request.user_id,
            session_id=request.session_id,
            context=request.context,
            conversation_history=request.conversation_history,
            max_cost_usd=min(request.max_cost_usd, 0.50),  # hard cap
            max_latency_ms=request.max_latency_ms,
        )

        result_dict = await graph.ainvoke(initial_state.model_dump())
        result = AgentState(**result_dict)

        elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000

        logger.info(
            "api.invoke_agent.complete",
            extra={
                "request_id": result.request_id,
                "intent": result.intent.value,
                "elapsed_ms": round(elapsed_ms, 1),
                "escalated": result.needs_escalation,
            },
        )

        return AgentResponse(
            request_id=result.request_id,
            response=result.response,
            intent=result.intent.value,
            intent_confidence=result.intent_confidence,
            risk_level=result.risk_level.value,
            citations=result.response_citations,
            escalated=result.needs_escalation,
            escalation_reason=result.escalation_reason,
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
    except Exception as exc:
        elapsed_ms = (time.monotonic_ns() - start_ns) / 1_000_000
        logger.exception(
            "api.invoke_agent.error",
            extra={"elapsed_ms": round(elapsed_ms, 1)},
        )
        raise HTTPException(
            status_code=500,
            detail=f"Agent invocation failed: {exc}",
        )


@router.get("/agent/health")
async def agent_health() -> Dict[str, str]:
    """Health check for the v2 agent subsystem."""
    return {"status": "ok", "version": "v2"}
