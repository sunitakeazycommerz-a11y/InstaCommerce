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
from app.graph.nodes import (
    check_policy,
    classify_intent,
    escalate,
    execute_tools,
    respond,
    retrieve_context,
    validate_output,
)
from app.graph.state import AgentState
from app.graph.tools import ToolRegistry

logger = logging.getLogger("ai_orchestrator.graph")


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
) -> Any:
    """Construct and compile the LangGraph state machine.

    Parameters
    ----------
    settings:
        Application settings.  A default instance is created when *None*.
    tool_registry:
        Pre-built tool registry.  Nodes that need it receive it via a
        closure.  When *None*, ``execute_tools`` runs in dry-run mode.

    Returns
    -------
    StateGraph
        A compiled LangGraph ready for ``invoke()`` / ``ainvoke()``.
    """
    settings = settings or Settings()

    def _route_after_policy(state: Dict[str, Any]) -> str:
        return _should_escalate(AgentState(**state))

    def _route_after_validation(state: Dict[str, Any]) -> str:
        if AgentState(**state).needs_escalation:
            return "escalate"
        return "end"

    graph = StateGraph(dict)

    graph.add_node("classify_intent", _wrap_sync(classify_intent))
    graph.add_node("check_policy", _wrap_sync(lambda s: check_policy(s, settings=settings)))
    graph.add_node("retrieve_context", _wrap_async(retrieve_context))
    graph.add_node("execute_tools", _wrap_async(lambda s: execute_tools(s, registry=tool_registry)))
    graph.add_node("respond", _wrap_sync(respond))
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
    logger.info("graph.compiled", extra={"nodes": list(graph.nodes.keys())})
    return compiled
