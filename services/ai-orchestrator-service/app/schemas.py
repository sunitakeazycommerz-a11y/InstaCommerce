"""Shared Pydantic request and response schemas for AI Orchestrator Service."""

from __future__ import annotations

from typing import Any, Dict, List, Literal, Optional
from uuid import UUID

from pydantic import BaseModel, Field


class ItemQuantity(BaseModel):
    product_id: UUID
    quantity: int = Field(..., ge=1, le=1000)


class AssistContext(BaseModel):
    order_id: Optional[UUID] = None
    product_id: Optional[UUID] = None
    store_id: Optional[str] = Field(default=None, min_length=1, max_length=64)
    items: List[ItemQuantity] = Field(default_factory=list, max_length=50)
    category: Optional[str] = Field(default=None, min_length=1, max_length=64)
    coupon_code: Optional[str] = Field(default=None, min_length=1, max_length=64)


class AssistRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=1000)
    user_id: Optional[UUID] = None
    session_id: Optional[str] = Field(None, min_length=1, max_length=64)
    context: AssistContext = Field(default_factory=AssistContext)
    execute_tools: bool = False
    request_id: Optional[str] = Field(None, min_length=6, max_length=64)


class SubstituteRequest(BaseModel):
    product_id: UUID
    candidate_ids: List[UUID] = Field(default_factory=list, max_length=20)
    store_id: Optional[str] = Field(None, min_length=1, max_length=64)
    user_id: Optional[UUID] = None
    session_id: Optional[str] = Field(None, min_length=1, max_length=64)
    limit: int = Field(1, ge=1, le=5)
    execute_tools: bool = False
    request_id: Optional[str] = Field(None, min_length=6, max_length=64)


class RecommendRequest(BaseModel):
    user_id: Optional[UUID] = None
    session_id: Optional[str] = Field(None, min_length=1, max_length=64)
    seed_product_id: Optional[UUID] = None
    recent_product_ids: List[UUID] = Field(default_factory=list, max_length=20)
    category: Optional[str] = Field(None, min_length=1, max_length=64)
    limit: int = Field(5, ge=1, le=20)
    execute_tools: bool = False
    request_id: Optional[str] = Field(None, min_length=6, max_length=64)


class ToolCall(BaseModel):
    name: str = Field(..., min_length=1, max_length=64)
    arguments: Dict[str, Any] = Field(default_factory=dict)


class ToolResult(BaseModel):
    name: str
    success: bool
    status_code: Optional[int] = None
    data: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class LegacyAgentResponse(BaseModel):
    request_id: str
    mode: Literal["fallback", "llm"]
    intent: str
    message: str
    tool_calls: List[ToolCall] = Field(default_factory=list)
    tool_results: List[ToolResult] = Field(default_factory=list)


class AssistResponse(LegacyAgentResponse):
    pass


class SubstituteResponse(LegacyAgentResponse):
    substitute_product_id: Optional[UUID] = None
    candidate_ids: List[UUID] = Field(default_factory=list)


class RecommendResponse(LegacyAgentResponse):
    recommended_product_ids: List[UUID] = Field(default_factory=list)


class AgentRequest(BaseModel):
    """Inbound request to the unified v2 LangGraph agent."""

    query: str = Field(..., min_length=1, max_length=4_000)
    user_id: str = Field(..., min_length=1, max_length=256)
    session_id: Optional[str] = None
    context: Dict[str, Any] = Field(default_factory=dict)
    conversation_history: List[Dict[str, str]] = Field(default_factory=list)
    max_cost_usd: float = Field(default=0.50, ge=0.0, le=0.50)
    max_tokens: int = Field(default=0, ge=0)
    max_latency_ms: float = Field(default=10_000.0, ge=0.0, le=30_000.0)


class AgentResponse(BaseModel):
    """Unified v2 agent response payload."""

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
