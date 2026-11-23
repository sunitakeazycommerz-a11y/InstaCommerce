"""LangGraph state-machine definition for the InstaCommerce AI orchestrator.

Graph topology::

    START
      │
      ▼
    classify_intent
      │
      ▼
    check_policy
      │
      ├──(needs_escalation)──► escalate ──► END
      │
      ▼
    retrieve_context
      │
      ▼
    execute_tools
      │
      ▼
    respond
      │
      ▼
    validate_output
      │
      ├──(needs_escalation)──► escalate ──► END
      │
      ▼
    END

Conditional routing after ``check_policy`` and ``validate_output`` inspects
``needs_escalation`` to decide whether the request completes autonomously or
hands off to a human agent.
"""

from __future__ import annotations

import logging
from typing import Any, Awaitable, Callable, Dict, Optional

from langgraph.graph import END, StateGraph

from app.config import Settings
from app.graph.checkpoints import CheckpointSaver, run_agent_graph
from app.graph.nodes import (
    check_policy,
    classify_intent,
    escalate,
    execute_tools,
    respond,
    retrieve_context,
    validate_output,
)
from app.graph.response_node import respond_with_llm
from app.graph.state import AgentState, IntentType
from app.graph.tools import ToolRegistry

logger = logging.getLogger("ai_orchestrator.graph")

_DEFAULT_SETTINGS = Settings()


def _should_escalate(state: AgentState) -> str:
    """Route after ``check_policy``: escalate or continue processing."""
    if state.needs_escalation:
        return "escalate"
    return "retrieve_context"


def _wrap_sync(
    fn: Callable[[AgentState], AgentState],
) -> Callable[[Dict[str, Any]], Dict[str, Any]]:
    def _node(state: Dict[str, Any]) -> Dict[str, Any]:
        s = AgentState(**state)
        result = fn(s)
        return result.model_copy(
            update={"elapsed_ms": result.current_elapsed_ms()}
        ).model_dump()

    return _node


def _wrap_async(
    fn: Callable[[AgentState], Awaitable[AgentState]],
) -> Callable[[Dict[str, Any]], Awaitable[Dict[str, Any]]]:
    async def _node(state: Dict[str, Any]) -> Dict[str, Any]:
        s = AgentState(**state)
        result = await fn(s)
        return result.model_copy(
            update={"elapsed_ms": result.current_elapsed_ms()}
        ).model_dump()

    return _node


def build_graph(
    settings: Optional[Settings] = None,
    tool_registry: Optional[ToolRegistry] = None,
    checkpointer: Optional[CheckpointSaver] = None,
    llm_client: Optional[Any] = None,
    retriever: Optional[Any] = None,
    semantic_cache: Optional[Any] = None,
) -> Any:
    """Construct and compile the LangGraph state machine."""
    resolved_settings = settings or _DEFAULT_SETTINGS

    def _classify_with_cache(s: AgentState) -> AgentState:
        classified = classify_intent(s)
        if (
            semantic_cache is not None
            and not classified.needs_escalation
            and not classified.response.strip()
        ):
            match = semantic_cache.find_match(classified.query)
            if match:
                return classified.model_copy(
                    update={
                        "intent": IntentType.SUPPORT,
                        "intent_confidence": float(match["similarity"]),
                        "response": str(match["answer"]),
                        "generation_mode": "llm",
                        "execute_tools": False,
                    }
                )
        return classified

    async def _retrieve_node(s: AgentState) -> AgentState:
        if retriever is None:
            return await retrieve_context(s)
        return await retrieve_context(s, retriever=retriever)

    async def _respond_node(s: AgentState) -> AgentState:
        return await respond_with_llm(s, llm_client=llm_client, settings=resolved_settings)

    def _route_after_policy(state: Dict[str, Any]) -> str:
        return _should_escalate(AgentState(**state))

    def _route_after_validation(state: Dict[str, Any]) -> str:
        if AgentState(**state).needs_escalation:
            return "escalate"
        return "end"

    graph = StateGraph(dict)

    graph.add_node(
        "classify_intent",
        _wrap_sync(_classify_with_cache if semantic_cache is not None else classify_intent),
    )
    graph.add_node("check_policy", _wrap_sync(lambda s: check_policy(s, settings=resolved_settings)))
    graph.add_node("retrieve_context", _wrap_async(_retrieve_node))
    graph.add_node("execute_tools", _wrap_async(lambda s: execute_tools(s, registry=tool_registry)))
    graph.add_node(
        "respond",
        _wrap_async(_respond_node) if llm_client is not None else _wrap_sync(respond),
    )
    graph.add_node("validate_output", _wrap_sync(validate_output))
    graph.add_node("escalate", _wrap_sync(escalate))

    graph.set_entry_point("classify_intent")
    graph.add_edge("classify_intent", "check_policy")
    graph.add_conditional_edges(
        "check_policy",
        _route_after_policy,
        {
            "retrieve_context": "retrieve_context",
            "escalate": "escalate",
        },
    )
    graph.add_edge("retrieve_context", "execute_tools")
    graph.add_edge("execute_tools", "respond")
    graph.add_edge("respond", "validate_output")
    graph.add_conditional_edges(
        "validate_output",
        _route_after_validation,
        {
            "end": END,
            "escalate": "escalate",
        },
    )
    graph.add_edge("escalate", END)

    compiled = graph.compile()
    setattr(compiled, "_is_unified_orchestrator_graph", True)
    logger.info(
        "graph.compiled",
        extra={"nodes": list(graph.nodes.keys()), "has_checkpointer": checkpointer is not None},
    )
    return compiled


build_agent_graph = build_graph

__all__ = ["build_agent_graph", "build_graph", "run_agent_graph"]
