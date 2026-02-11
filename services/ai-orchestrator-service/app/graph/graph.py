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
    validate_output
      │
      ▼
    respond
      │
      ▼
    END

Conditional routing after ``check_policy`` inspects ``needs_escalation``
and ``risk_level`` to decide whether the request should be processed
autonomously or handed to a human agent.
"""

from __future__ import annotations

import logging
from typing import Any, Dict, Optional

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
from app.graph.state import AgentState, RiskLevel
from app.graph.tools import ToolRegistry

logger = logging.getLogger("ai_orchestrator.graph")


# ---------------------------------------------------------------------------
# Conditional edge helpers
# ---------------------------------------------------------------------------

def _should_escalate(state: AgentState) -> str:
    """Route after ``check_policy``: escalate or continue processing."""
    if state.needs_escalation:
        return "escalate"
    return "retrieve_context"


# ---------------------------------------------------------------------------
# Graph builder
# ---------------------------------------------------------------------------

def build_graph(
    settings: Optional[Settings] = None,
    tool_registry: Optional[ToolRegistry] = None,
) -> StateGraph:
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

    # Wrap async / parameterised nodes so they match the LangGraph
    # ``(state) -> state`` contract.

    def _classify_intent(state: Dict[str, Any]) -> Dict[str, Any]:
        s = AgentState(**state)
        result = classify_intent(s)
        return result.model_dump()

    def _check_policy(state: Dict[str, Any]) -> Dict[str, Any]:
        s = AgentState(**state)
        result = check_policy(s, settings=settings)
        return result.model_dump()

    async def _retrieve_context(state: Dict[str, Any]) -> Dict[str, Any]:
        s = AgentState(**state)
        result = await retrieve_context(s)
        return result.model_dump()

    async def _execute_tools(state: Dict[str, Any]) -> Dict[str, Any]:
        s = AgentState(**state)
        result = await execute_tools(s, registry=tool_registry)
        return result.model_dump()

    def _validate_output(state: Dict[str, Any]) -> Dict[str, Any]:
        s = AgentState(**state)
        result = validate_output(s)
        return result.model_dump()

    def _respond(state: Dict[str, Any]) -> Dict[str, Any]:
        s = AgentState(**state)
        result = respond(s)
        return result.model_dump()

    def _escalate(state: Dict[str, Any]) -> Dict[str, Any]:
        s = AgentState(**state)
        result = escalate(s)
        return result.model_dump()

    def _route_after_policy(state: Dict[str, Any]) -> str:
        s = AgentState(**state)
        return _should_escalate(s)

    # -- Assemble the graph --------------------------------------------------

    graph = StateGraph(dict)

    graph.add_node("classify_intent", _classify_intent)
    graph.add_node("check_policy", _check_policy)
    graph.add_node("retrieve_context", _retrieve_context)
    graph.add_node("execute_tools", _execute_tools)
    graph.add_node("validate_output", _validate_output)
    graph.add_node("respond", _respond)
    graph.add_node("escalate", _escalate)

    # Edges
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
    graph.add_edge("execute_tools", "validate_output")
    graph.add_edge("validate_output", "respond")
    graph.add_edge("respond", END)
    graph.add_edge("escalate", END)

    compiled = graph.compile()
    logger.info("graph.compiled", extra={"nodes": list(graph.nodes.keys())})
    return compiled
