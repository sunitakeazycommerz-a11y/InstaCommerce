"""Pydantic request and response schemas for AI Inference Service."""

from __future__ import annotations

from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


class InferenceBaseModel(BaseModel):
    model_config = ConfigDict(protected_namespaces=())


class GuardedPredictionResponse(InferenceBaseModel):
    feature_contributions: Dict[str, float]
    bias: float
    model_version: str
    is_cold_start: bool = False
    confidence_lower: Optional[float] = None
    confidence_upper: Optional[float] = None
    drift_detected: bool = False
    sla_breached: bool = False


class EtaRequest(InferenceBaseModel):
    distance_km: float = Field(..., ge=0, le=200)
    item_count: int = Field(..., ge=0, le=200)
    traffic_factor: float = Field(..., ge=0.5, le=3.0)
    model_version: Optional[str] = None


class EtaResponse(GuardedPredictionResponse):
    eta_minutes: float


class RankingRequest(InferenceBaseModel):
    relevance_score: float = Field(..., ge=0, le=1)
    price_score: float = Field(..., ge=0, le=1)
    availability_score: float = Field(..., ge=0, le=1)
    user_affinity: float = Field(..., ge=0, le=1)
    model_version: Optional[str] = None


class RankingResponse(GuardedPredictionResponse):
    ranking_score: float
    raw_score: float


class FraudRequest(InferenceBaseModel):
    order_amount: float = Field(..., ge=0, le=10000)
    chargeback_rate: float = Field(..., ge=0, le=1)
    device_risk: float = Field(..., ge=0, le=1)
    account_age_days: int = Field(..., ge=0, le=36500)
    model_version: Optional[str] = None


class FraudResponse(GuardedPredictionResponse):
    fraud_probability: float
    raw_score: float


class BatchInferenceItem(InferenceBaseModel):
    model_name: Literal["eta", "ranking", "fraud"]
    payload: Dict[str, Any] = Field(default_factory=dict)
    model_version: Optional[str] = None
    entity_id: Optional[str] = None
    use_feature_store: bool = False


class BatchInferenceRequest(InferenceBaseModel):
    items: List[BatchInferenceItem] = Field(..., min_length=1, max_length=100)


class BatchInferenceResult(InferenceBaseModel):
    model_name: str
    model_version: Optional[str] = None
    output: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class BatchInferenceResponse(InferenceBaseModel):
    results: List[BatchInferenceResult]
