"""Typed Pydantic state definitions for the LangGraph agent orchestrator.

Every node in the graph receives and returns an ``AgentState`` instance.
Fields are grouped by concern (input, classification, retrieval, tool
execution, budget, output, conversation, errors, graph metadata) so each
node only touches the slice it owns.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------

class IntentType(str, Enum):
    """Supported user-intent categories."""

    SUBSTITUTE = "substitute"
    SUPPORT = "support"
    RECOMMEND = "recommend"
    SEARCH = "search"
    ORDER_STATUS = "order_status"
    UNKNOWN = "unknown"


class RiskLevel(str, Enum):
    """Risk classification that drives policy gates."""

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


# ---------------------------------------------------------------------------
# Nested models
# ---------------------------------------------------------------------------

class ToolResult(BaseModel):
    """Outcome of a single tool invocation."""

    tool_name: str
    success: bool
    data: Dict[str, Any] = Field(default_factory=dict)
    error: Optional[str] = None
    latency_ms: float = 0.0


class RetrievalResult(BaseModel):
    """A single document returned by the RAG retrieval step."""

    doc_id: str
    content: str
    score: float
    metadata: Dict[str, Any] = Field(default_factory=dict)


# ---------------------------------------------------------------------------
# Primary graph state
# ---------------------------------------------------------------------------

class AgentState(BaseModel):
    """Complete state object threaded through every LangGraph node.

    Design principles:
    * Every field has a sane default so nodes can be tested in isolation.
    * Budget / cost / latency fields enforce hard ceilings – the
      ``check_policy`` node relies on these for gating decisions.
    * ``completed_nodes`` provides an audit trail of the execution path.
    """

    # -- Identity ------------------------------------------------------------
    request_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    user_id: str = ""
    session_id: Optional[str] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)

    # -- Input ---------------------------------------------------------------
    query: str = ""
    context: Dict[str, Any] = Field(default_factory=dict)

    # -- Classification ------------------------------------------------------
    intent: IntentType = IntentType.UNKNOWN
    intent_confidence: float = 0.0
    risk_level: RiskLevel = RiskLevel.LOW

    # -- Retrieval -----------------------------------------------------------
    retrieval_results: List[RetrievalResult] = Field(default_factory=list)

    # -- Tool execution ------------------------------------------------------
    tool_results: List[ToolResult] = Field(default_factory=list)
    tool_calls_remaining: int = 10

    # -- Budget tracking -----------------------------------------------------
    total_tokens_used: int = 0
    total_cost_usd: float = 0.0
    max_cost_usd: float = 0.50
    max_latency_ms: float = 10000.0
    elapsed_ms: float = 0.0

    # -- Output --------------------------------------------------------------
    response: str = ""
    response_citations: List[str] = Field(default_factory=list)
    needs_escalation: bool = False
    escalation_reason: Optional[str] = None

    # -- Conversation --------------------------------------------------------
    conversation_history: List[Dict[str, str]] = Field(default_factory=list)

    # -- Errors --------------------------------------------------------------
    errors: List[str] = Field(default_factory=list)

    # -- Graph metadata ------------------------------------------------------
    current_node: str = "start"
    completed_nodes: List[str] = Field(default_factory=list)
