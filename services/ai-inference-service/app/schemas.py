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
    method: str = "linear"


class EtaRequest(InferenceBaseModel):
    distance_km: float = Field(..., ge=0, le=200)
    item_count: int = Field(..., ge=0, le=200)
    traffic_factor: float = Field(..., ge=0.5, le=3.0)
    model_version: Optional[str] = None
    store_prep_time_minutes: Optional[float] = Field(default=None, ge=0)
    rider_avg_speed_kmh: Optional[float] = Field(default=None, ge=0)
    weather_multiplier: Optional[float] = Field(default=None, ge=0.5, le=2.0)
    queue_depth: Optional[int] = Field(default=None, ge=0)
    time_of_day_hour: Optional[int] = Field(default=None, ge=0, le=23)
    is_weekend: Optional[bool] = None
    stage: Optional[str] = None


class EtaResponse(GuardedPredictionResponse):
    eta_minutes: float


class RankingRequest(InferenceBaseModel):
    relevance_score: float = Field(..., ge=0, le=1)
    price_score: float = Field(..., ge=0, le=1)
    availability_score: float = Field(..., ge=0, le=1)
    user_affinity: float = Field(..., ge=0, le=1)
    model_version: Optional[str] = None
    user_purchase_count_30d: Optional[int] = Field(default=None, ge=0)
    popularity_7d: Optional[float] = Field(default=None, ge=0)
    avg_rating: Optional[float] = Field(default=None, ge=0, le=5)
    review_count: Optional[int] = Field(default=None, ge=0)
    time_of_day_hour: Optional[int] = Field(default=None, ge=0, le=23)
    is_promoted: Optional[bool] = None
    freshness_score: Optional[float] = Field(default=None, ge=0, le=1)
    product_id: Optional[str] = None


class RankingResponse(GuardedPredictionResponse):
    ranking_score: float
    raw_score: float


class FraudRequest(InferenceBaseModel):
    order_amount: float = Field(..., ge=0, le=10000)
    chargeback_rate: float = Field(..., ge=0, le=1)
    device_risk: float = Field(..., ge=0, le=1)
    account_age_days: int = Field(..., ge=0, le=36500)
    model_version: Optional[str] = None
    orders_last_1h: Optional[int] = Field(default=None, ge=0)
    orders_last_24h: Optional[int] = Field(default=None, ge=0)
    new_addresses_last_7d: Optional[int] = Field(default=None, ge=0)
    payment_methods_added_30d: Optional[int] = Field(default=None, ge=0)
    basket_vs_user_avg_ratio: Optional[float] = Field(default=None, ge=0)
    high_resale_item_ratio: Optional[float] = Field(default=None, ge=0, le=1)
    is_prepaid_card: Optional[bool] = None
    card_country_mismatch: Optional[bool] = None
    device_fingerprint_age_days: Optional[int] = Field(default=None, ge=0)
    is_vpn_detected: Optional[bool] = None
    is_emulator: Optional[bool] = None
    profile_completeness: Optional[float] = Field(default=None, ge=0, le=1)


class FraudResponse(GuardedPredictionResponse):
    fraud_probability: float
    raw_score: float


class DriftCheckRequest(InferenceBaseModel):
    model_name: Literal["eta", "ranking", "fraud", "demand", "personalization", "clv", "pricing"]
    features: Dict[str, float] = Field(default_factory=dict)
    expected_features: Optional[List[str]] = None
    entity_id: Optional[str] = None
    offline_features: Optional[Dict[str, Dict[str, float]]] = None
    max_drift_pct: float = Field(default=1.0, gt=0, le=100.0)


class DriftCheckResponse(InferenceBaseModel):
    model_name: str
    drift_detected: bool
    missing_count: int
    expected_count: int
    missing_ratio: float
    missing_features: List[str]
    ood_features: Dict[str, float]
    max_z_score: float
    missing_threshold_exceeded: bool
    parity_reports: List[Dict[str, Any]] = Field(default_factory=list)


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
