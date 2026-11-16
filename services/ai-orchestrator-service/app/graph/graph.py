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
from app.graph.checkpoints import CheckpointSaver
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


class CompiledAgentGraph:
    """Wrapper around compiled LangGraph that persists state via ``CheckpointSaver``."""

    def __init__(self, compiled: Any, checkpointer: Optional[CheckpointSaver] = None) -> None:
        self._compiled = compiled
        self.checkpointer = checkpointer

    async def ainvoke(
        self,
        input_state: Dict[str, Any],
        config: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        thread_id = (
            ((config or {}).get("configurable") or {}).get("thread_id")
            or input_state.get("session_id")
            or input_state.get("request_id")
        )
        state_to_run = dict(input_state)
        if self.checkpointer is not None and thread_id:
            try:
                prior = await self.checkpointer.load(str(thread_id))
                if prior is not None and not state_to_run.get("conversation_history"):
                    state_to_run["conversation_history"] = list(prior.conversation_history)
            except Exception:
                logger.warning("graph.checkpoint_load_failed", extra={"thread_id": str(thread_id)})

        if config is not None:
            result = await self._compiled.ainvoke(state_to_run, config=config)
        else:
            result = await self._compiled.ainvoke(state_to_run)

        if self.checkpointer is not None and thread_id and isinstance(result, dict):
            try:
                await self.checkpointer.save(str(thread_id), AgentState(**result))
            except Exception:
                logger.warning("graph.checkpoint_save_failed", extra={"thread_id": str(thread_id)})
        return result

    def __getattr__(self, name: str) -> Any:
        return getattr(self._compiled, name)


def build_graph(
    settings: Optional[Settings] = None,
    tool_registry: Optional[ToolRegistry] = None,
    checkpointer: Optional[CheckpointSaver] = None,
) -> Any:
    """Construct and compile the LangGraph state machine.

    Parameters
    ----------
    settings:
        Application settings.  A default instance is created when *None*.
    tool_registry:
        Pre-built tool registry.  Nodes that need it receive it via a
        closure.  When *None*, ``execute_tools`` runs in dry-run mode.
    checkpointer:
        Optional ``CheckpointSaver`` (e.g. ``RedisCheckpointSaver``) for
        persisting state across invocations.

    Returns
    -------
    CompiledAgentGraph
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
    logger.info(
        "graph.compiled",
        extra={"nodes": list(graph.nodes.keys()), "has_checkpointer": checkpointer is not None},
    )
    return CompiledAgentGraph(compiled, checkpointer=checkpointer)


build_agent_graph = build_graph
