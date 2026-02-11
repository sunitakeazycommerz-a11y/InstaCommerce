"""LangGraph-based AI orchestrator state machine for InstaCommerce."""

from app.graph.state import AgentState, IntentType, RiskLevel

__all__ = ["AgentState", "IntentType", "RiskLevel", "build_graph"]


def build_graph(*args, **kwargs):  # type: ignore[no-untyped-def]
    """Lazy wrapper – imports ``langgraph`` only when called."""
    from app.graph.graph import build_graph as _build

    return _build(*args, **kwargs)
