"""Feature extraction helpers and feature store factory for AI Inference Service."""

from __future__ import annotations

import time
from typing import Any, Dict, Mapping, Optional, Sequence

from prometheus_client import Counter, Histogram
from pydantic import BaseModel

from app.core.config import Settings
from app.features.redis_client import RedisFeatureStoreClient
from app.features.store import (
    BigQueryFeatureStoreClient,
    FeatureStoreClient,
    UnavailableFeatureStoreClient,
)
from app.schemas import EtaRequest, FraudRequest, RankingRequest

FEATURE_STORE_LATENCY = Histogram(
    "ai_inference_feature_store_latency_seconds",
    "Feature store latency",
    ["backend"],
)
FEATURE_STORE_ERRORS = Counter(
    "ai_inference_feature_store_errors_total",
    "Feature store errors",
    ["backend"],
)


def eta_features(request: EtaRequest) -> Dict[str, float]:
    return {
        "distance_km": request.distance_km,
        "item_count": float(request.item_count),
        "traffic_factor": request.traffic_factor,
    }


def ranking_features(request: RankingRequest) -> Dict[str, float]:
    return {
        "relevance_score": request.relevance_score,
        "price_score": request.price_score,
        "availability_score": request.availability_score,
        "user_affinity": request.user_affinity,
    }


def fraud_features(request: FraudRequest) -> Dict[str, float]:
    return {
        "order_amount": request.order_amount,
        "chargeback_rate": request.chargeback_rate,
        "device_risk": request.device_risk,
        "account_age_days": float(request.account_age_days),
    }


def parse_model_request(model_name: str, payload: Mapping[str, Any]) -> BaseModel:
    if model_name == "eta":
        return EtaRequest.model_validate(payload)
    if model_name == "ranking":
        return RankingRequest.model_validate(payload)
    if model_name == "fraud":
        return FraudRequest.model_validate(payload)
    raise ValueError("unknown_model")


def build_features_for_model(model_name: str, request: BaseModel) -> Dict[str, float]:
    if model_name == "eta":
        assert isinstance(request, EtaRequest)
        return eta_features(request)
    if model_name == "ranking":
        assert isinstance(request, RankingRequest)
        return ranking_features(request)
    if model_name == "fraud":
        assert isinstance(request, FraudRequest)
        return fraud_features(request)
    raise ValueError("unknown_model")


def build_feature_store(
    config: Settings, cache: Optional[Any] = None
) -> FeatureStoreClient:
    backend = config.feature_store_backend.lower().strip()
    if backend == "redis":
        if not config.redis_url:
            return UnavailableFeatureStoreClient("redis", "missing_redis_url")
        try:
            return RedisFeatureStoreClient(
                redis_url=config.redis_url,
                prefix=config.redis_prefix,
                cache=cache,
            )
        except ImportError:
            return UnavailableFeatureStoreClient("redis", "redis_dependency_missing")
    if backend == "bigquery":
        if not config.bigquery_dataset or not config.bigquery_table:
            return UnavailableFeatureStoreClient("bigquery", "missing_bigquery_config")
        try:
            return BigQueryFeatureStoreClient(
                config.bigquery_project, config.bigquery_dataset, config.bigquery_table
            )
        except ImportError:
            return UnavailableFeatureStoreClient("bigquery", "bigquery_dependency_missing")
    return FeatureStoreClient()


def fetch_feature_store_features(
    feature_store: FeatureStoreClient, entity_id: str, feature_names: Sequence[str]
) -> Dict[str, float]:
    start = time.perf_counter()
    try:
        features = feature_store.get_features(entity_id, feature_names)
        FEATURE_STORE_LATENCY.labels(backend=feature_store.backend).observe(
            time.perf_counter() - start
        )
        return features
    except Exception:
        FEATURE_STORE_ERRORS.labels(backend=feature_store.backend).inc()
        raise
