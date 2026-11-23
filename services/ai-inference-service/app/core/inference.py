"""Inference execution, caching, shadow dispatch, and batch processing helpers."""

from __future__ import annotations

import hashlib
import json
import logging
import random
import time
from math import exp
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional

import numpy as np
from fastapi import HTTPException
from prometheus_client import Counter, Histogram
from pydantic import BaseModel, ValidationError

from app.core.config import InferenceCache, ModelConfig, ModelRegistry, settings
from app.core.guardrails import (
    MODEL_OUTPUT_STD,
    assess_feature_health,
    build_cold_start_output,
    compute_confidence_interval,
    enforce_sync_sla,
    is_cold_start_context,
)
from app.core.shadow import dispatch_shadow_prediction
from app.features.extractors import (
    build_features_for_model,
    eta_features,
    fetch_feature_store_features,
    fraud_features,
    parse_model_request,
    ranking_features,
)
from app.features.store import FeatureStoreClient, UnavailableFeatureStoreClient
from app.models import (
    CLVFeatures,
    CLVModel,
    CLVSegment,
    DemandFeatures,
    DemandModel,
    DynamicPricingModel,
    ETAFeatures,
    ETAModel,
    FraudFeatures,
    FraudModel,
    ManagedOnnxSession,
    PersonalizationFeatures,
    PersonalizationModel,
    PricingFeatures,
    RankingFeatures,
    RankingModel,
)
from app.models.eta_model import ETAStage
from app.schemas import (
    BatchInferenceItem,
    BatchInferenceResult,
    EtaRequest,
    FraudRequest,
    RankingRequest,
)

logger = logging.getLogger("ai_inference.serving")

REQUEST_COUNT = Counter(
    "ai_inference_requests_total",
    "Total inference requests",
    ["endpoint", "model", "version", "status"],
)
REQUEST_LATENCY = Histogram(
    "ai_inference_request_latency_seconds",
    "Inference request latency",
    ["endpoint", "model", "version"],
)
CACHE_EVENTS = Counter(
    "ai_inference_cache_events_total",
    "Inference cache events",
    ["model", "version", "result"],
)
SHADOW_REQUESTS = Counter(
    "ai_inference_shadow_requests_total",
    "Shadow inference requests",
    ["model", "version"],
)


def linear_score(
    features: Dict[str, float], weights: Dict[str, float], bias: float
) -> tuple[float, Dict[str, float]]:
    contributions = {
        name: float(features.get(name, 0.0)) * weight for name, weight in weights.items()
    }
    return bias + sum(contributions.values()), contributions


def safe_sigmoid(value: float) -> float:
    if value >= 50:
        return 1.0
    if value <= -50:
        return 0.0
    return 1.0 / (1.0 + exp(-value))


def perform_inference(
    model_name: str,
    features: Dict[str, float],
    model_config: ModelConfig,
    *,
    entity_metadata: Optional[Mapping[str, Any]] = None,
) -> Dict[str, Any]:
    health = assess_feature_health(
        model_name,
        features,
        expected_features=model_config.weights.keys() if model_config.weights else None,
        z_threshold=settings.ood_z_threshold,
        max_missing_ratio=settings.max_missing_feature_ratio,
    )
    cold_start, cold_reason = is_cold_start_context(
        model_name,
        features,
        missing_ratio=health.missing_ratio,
        entity_metadata=entity_metadata,
    )
    if cold_start:
        return build_cold_start_output(
            model_name,
            model_config.version,
            reason=cold_reason or "insufficient_history",
            drift_detected=health.drift_detected,
        )

    raw_score, contributions = linear_score(features, model_config.weights, model_config.bias)
    if model_name == "eta":
        eta_minutes = max(0.0, raw_score)
        ci_lo, ci_hi = compute_confidence_interval(
            eta_minutes,
            std=MODEL_OUTPUT_STD["eta"],
            lower_bound=0.0,
            upper_bound=None,
        )
        return {
            "eta_minutes": eta_minutes,
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": health.drift_detected,
            "sla_breached": False,
        }
    if model_name == "ranking":
        ranking_score = safe_sigmoid(raw_score)
        ci_lo, ci_hi = compute_confidence_interval(
            ranking_score,
            std=MODEL_OUTPUT_STD["ranking"],
            lower_bound=0.0,
            upper_bound=1.0,
        )
        return {
            "ranking_score": ranking_score,
            "raw_score": raw_score,
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": health.drift_detected,
            "sla_breached": False,
        }
    if model_name == "fraud":
        fraud_probability = safe_sigmoid(raw_score)
        ci_lo, ci_hi = compute_confidence_interval(
            fraud_probability,
            std=MODEL_OUTPUT_STD["fraud"],
            lower_bound=0.0,
            upper_bound=1.0,
        )
        return {
            "fraud_probability": fraud_probability,
            "raw_score": raw_score,
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": health.drift_detected,
            "sla_breached": False,
        }
    if model_name == "demand":
        predicted_units = max(0.0, raw_score)
        ci_lo, ci_hi = compute_confidence_interval(
            predicted_units,
            std=MODEL_OUTPUT_STD["demand"],
            lower_bound=0.0,
            upper_bound=None,
        )
        return {
            "predicted_units": predicted_units,
            "score": predicted_units,
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": health.drift_detected,
            "sla_breached": False,
        }
    if model_name == "personalization":
        score = safe_sigmoid(raw_score)
        ci_lo, ci_hi = compute_confidence_interval(
            score,
            std=MODEL_OUTPUT_STD["personalization"],
            lower_bound=0.0,
            upper_bound=1.0,
        )
        return {
            "score": score,
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": health.drift_detected,
            "sla_breached": False,
        }
    if model_name == "clv":
        clv_cents = max(0, int(round(raw_score)))
        ci_lo, ci_hi = compute_confidence_interval(
            float(clv_cents),
            std=MODEL_OUTPUT_STD["clv"],
            lower_bound=0.0,
            upper_bound=None,
        )
        return {
            "predicted_annual_value_cents": clv_cents,
            "score": float(clv_cents),
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": health.drift_detected,
            "sla_breached": False,
        }
    if model_name == "pricing":
        fee_cents = max(0, int(round(raw_score)))
        ci_lo, ci_hi = compute_confidence_interval(
            float(fee_cents),
            std=MODEL_OUTPUT_STD["pricing"],
            lower_bound=0.0,
            upper_bound=None,
        )
        return {
            "delivery_fee_cents": fee_cents,
            "score": float(fee_cents),
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": health.drift_detected,
            "sla_breached": False,
        }
    raise ValueError("unknown_model")


def build_cache_key(model_name: str, model_version: str, features: Dict[str, float]) -> str:
    payload = {"model": model_name, "version": model_version, "features": features}
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def maybe_run_shadow(
    model_name: str,
    features: Dict[str, float],
    primary_version: str,
    registry: ModelRegistry,
    primary_output: Optional[Dict[str, Any]] = None,
) -> None:
    shadow_version = settings.shadow_models.get(model_name)
    if not shadow_version or shadow_version == primary_version:
        return
    sample_rate = min(max(settings.shadow_sample_rate, 0.0), 1.0)
    if sample_rate < 1.0 and random.random() > sample_rate:
        return
    try:
        shadow_model = registry.get(model_name, shadow_version)
    except KeyError:
        return
    SHADOW_REQUESTS.labels(model=model_name, version=shadow_model.version).inc()
    dispatch_shadow_prediction(
        model_name,
        shadow_model.version,
        perform_inference,
        primary_output,
        model_name,
        features,
        shadow_model,
    )


def execute_inference(
    model_name: str,
    features: Dict[str, float],
    model_config: ModelConfig,
    cache: Optional[InferenceCache],
    registry: ModelRegistry,
) -> tuple[Dict[str, Any], bool]:
    cache_hit = False
    cache_key: Optional[str] = None
    result: Optional[Dict[str, Any]] = None
    if cache is not None:
        cache_key = build_cache_key(model_name, model_config.version, features)
        cached = cache.get(cache_key)
        if cached is not None:
            cache_hit = True
            CACHE_EVENTS.labels(model=model_name, version=model_config.version, result="hit").inc()
            result = {**cached, "sla_breached": False}
        else:
            CACHE_EVENTS.labels(model=model_name, version=model_config.version, result="miss").inc()

    if result is None:
        computed, sla_breached, _ = enforce_sync_sla(
            model_name,
            lambda: perform_inference(model_name, features, model_config),
            budget_ms=settings.sla_budgets_ms.get(model_name),
            strict=settings.strict_sla_timeout,
            model_version=model_config.version,
            fallback_on_timeout=True,
        )
        result = {**computed, "sla_breached": sla_breached}
        if cache is not None and cache_key is not None and not sla_breached:
            cache.set(cache_key, result)

    maybe_run_shadow(model_name, features, model_config.version, registry, primary_output=result)
    return result, cache_hit


def resolve_model_config(
    registry: ModelRegistry, model_name: str, requested_version: Optional[str]
) -> ModelConfig:
    try:
        return registry.get(model_name, requested_version)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="model_version_not_found") from exc


def process_batch_item(
    item: BatchInferenceItem,
    registry: ModelRegistry,
    cache: Optional[InferenceCache],
    feature_store: FeatureStoreClient,
) -> BatchInferenceResult:
    start = time.perf_counter()
    status = "error"
    version_label = item.model_version or "unknown"
    try:
        requested_version = item.model_version
        if not requested_version and isinstance(item.payload, Mapping):
            requested_version = item.payload.get("model_version") or item.payload.get("modelVersion")
        model_config = registry.get(item.model_name, requested_version)
        version_label = model_config.version
        payload = dict(item.payload)
        if item.use_feature_store:
            if not item.entity_id:
                return BatchInferenceResult(
                    model_name=item.model_name,
                    model_version=version_label,
                    error="entity_id_required",
                )
            if feature_store.backend == "none" or isinstance(
                feature_store, UnavailableFeatureStoreClient
            ):
                return BatchInferenceResult(
                    model_name=item.model_name,
                    model_version=version_label,
                    error="feature_store_unavailable",
                )
            try:
                store_features = fetch_feature_store_features(
                    feature_store, item.entity_id, list(model_config.weights.keys())
                )
            except Exception:
                return BatchInferenceResult(
                    model_name=item.model_name,
                    model_version=version_label,
                    error="feature_store_error",
                )
            payload = {**store_features, **payload}
        try:
            request = parse_model_request(item.model_name, payload)
        except ValidationError:
            return BatchInferenceResult(
                model_name=item.model_name,
                model_version=version_label,
                error="validation_error",
            )
        features = build_features_for_model(item.model_name, request)
        output, _ = execute_inference(item.model_name, features, model_config, cache, registry)
        status = "success"
        return BatchInferenceResult(
            model_name=item.model_name,
            model_version=model_config.version,
            output=output,
        )
    except KeyError:
        return BatchInferenceResult(
            model_name=item.model_name,
            model_version=version_label,
            error="model_version_not_found",
        )
    finally:
        REQUEST_COUNT.labels(
            endpoint="/inference/batch",
            model=item.model_name,
            version=version_label,
            status=status,
        ).inc()
        REQUEST_LATENCY.labels(
            endpoint="/inference/batch",
            model=item.model_name,
            version=version_label,
        ).observe(time.perf_counter() - start)


_LINEAR_VERSIONS = frozenset({"eta-linear-v1", "ranking-linear-v1", "fraud-logit-v1"})


def create_onnx_session(model_name: str, onnx_dir: str = "") -> ManagedOnnxSession:
    """Load an ONNX Runtime session if configured on disk, else return a managed fallback."""
    if onnx_dir:
        candidate = Path(onnx_dir) / f"{model_name}.onnx"
        if candidate.is_file():
            try:
                import onnxruntime as ort  # type: ignore[import-untyped]

                session = ort.InferenceSession(str(candidate))
                return ManagedOnnxSession(session)
            except Exception:
                logger.warning("Failed to load ONNX artifact %s; using fallback", candidate, exc_info=True)
    return ManagedOnnxSession()


def _resolve_artifact_path(artifact_dir: str, filename: str) -> Optional[str]:
    if not artifact_dir:
        return None
    candidate = Path(artifact_dir) / filename
    return str(candidate) if candidate.is_file() else None


def create_model_instances(artifact_dir: str = "") -> Dict[str, Any]:
    """Instantiate domain ML models with optional on-disk artifacts."""
    return {
        "eta": ETAModel(model_path=_resolve_artifact_path(artifact_dir, "eta.lgb")),
        "ranking": RankingModel(model_path=_resolve_artifact_path(artifact_dir, "ranking.lgb")),
        "fraud": FraudModel(model_path=_resolve_artifact_path(artifact_dir, "fraud.xgb")),
        "demand": DemandModel(model_path=_resolve_artifact_path(artifact_dir, "demand.lgb")),
        "personalization": PersonalizationModel(model_path=_resolve_artifact_path(artifact_dir, "personalization.lgb")),
        "clv": CLVModel(model_path=_resolve_artifact_path(artifact_dir, "clv.lgb")),
        "pricing": DynamicPricingModel(model_path=_resolve_artifact_path(artifact_dir, "pricing.lgb")),
    }


def create_onnx_sessions(onnx_dir: str = "") -> Dict[str, ManagedOnnxSession]:
    """Create managed ONNX sessions for all served model families."""
    return {
        name: create_onnx_session(name, onnx_dir)
        for name in ("eta", "ranking", "fraud", "demand", "personalization", "clv", "pricing")
    }


def _to_eta_features(request: EtaRequest) -> ETAFeatures:
    stage = ETAStage.PRE_ORDER
    if request.stage:
        try:
            stage = ETAStage(request.stage)
        except ValueError:
            stage = ETAStage.PRE_ORDER
    return ETAFeatures(
        distance_km=request.distance_km,
        item_count=request.item_count,
        traffic_factor=request.traffic_factor,
        store_prep_time_minutes=(
            request.store_prep_time_minutes if request.store_prep_time_minutes is not None else 5.0
        ),
        rider_avg_speed_kmh=request.rider_avg_speed_kmh if request.rider_avg_speed_kmh is not None else 20.0,
        weather_multiplier=request.weather_multiplier if request.weather_multiplier is not None else 1.0,
        queue_depth=request.queue_depth if request.queue_depth is not None else 0,
        time_of_day_hour=request.time_of_day_hour if request.time_of_day_hour is not None else 12,
        is_weekend=bool(request.is_weekend) if request.is_weekend is not None else False,
        stage=stage,
    )


def _to_ranking_features(request: RankingRequest) -> RankingFeatures:
    price_comp = max(0.1, 1.0 - (request.price_score - 0.5) * 0.4)
    return RankingFeatures(
        bm25_score=request.relevance_score * 5.0,
        query_title_similarity=request.relevance_score,
        user_category_affinity=request.user_affinity,
        user_brand_affinity=request.user_affinity,
        user_purchase_count_30d=(
            request.user_purchase_count_30d if request.user_purchase_count_30d is not None else 0
        ),
        popularity_7d=request.popularity_7d if request.popularity_7d is not None else 0.0,
        price_competitiveness=price_comp,
        stock_availability=request.availability_score,
        avg_rating=request.avg_rating if request.avg_rating is not None else 0.0,
        review_count=request.review_count if request.review_count is not None else 0,
        time_of_day_hour=request.time_of_day_hour if request.time_of_day_hour is not None else 12,
        is_promoted=bool(request.is_promoted) if request.is_promoted is not None else False,
        freshness_score=request.freshness_score if request.freshness_score is not None else 0.5,
        product_id=request.product_id,
    )


def _to_fraud_features(request: FraudRequest) -> FraudFeatures:
    return FraudFeatures(
        orders_last_1h=request.orders_last_1h if request.orders_last_1h is not None else 0,
        orders_last_24h=request.orders_last_24h if request.orders_last_24h is not None else 0,
        new_addresses_last_7d=(
            request.new_addresses_last_7d if request.new_addresses_last_7d is not None else 0
        ),
        payment_methods_added_30d=(
            request.payment_methods_added_30d if request.payment_methods_added_30d is not None else 0
        ),
        order_amount_cents=int(round(request.order_amount * 100.0)),
        basket_vs_user_avg_ratio=(
            request.basket_vs_user_avg_ratio if request.basket_vs_user_avg_ratio is not None else 1.0
        ),
        high_resale_item_ratio=(
            request.high_resale_item_ratio
            if request.high_resale_item_ratio is not None
            else min(1.0, request.device_risk * 0.5)
        ),
        is_prepaid_card=bool(request.is_prepaid_card) if request.is_prepaid_card is not None else False,
        card_country_mismatch=(
            bool(request.card_country_mismatch) if request.card_country_mismatch is not None else False
        ),
        device_fingerprint_age_days=(
            request.device_fingerprint_age_days
            if request.device_fingerprint_age_days is not None
            else max(0, int(round((1.0 - request.device_risk) * 365)))
        ),
        is_vpn_detected=(
            request.is_vpn_detected if request.is_vpn_detected is not None else request.device_risk >= 0.7
        ),
        is_emulator=(
            request.is_emulator if request.is_emulator is not None else request.device_risk >= 0.9
        ),
        account_age_days=request.account_age_days,
        profile_completeness=(
            request.profile_completeness if request.profile_completeness is not None else 1.0
        ),
        chargeback_rate=request.chargeback_rate,
    )


def extract_numeric_features(model_name: str, request: BaseModel) -> Dict[str, float]:
    """Extract numeric health/shadow/cache features for any served model schema."""
    if model_name == "eta" and isinstance(request, EtaRequest):
        return eta_features(request)
    if model_name == "ranking" and isinstance(request, RankingRequest):
        return ranking_features(request)
    if model_name == "fraud" and isinstance(request, FraudRequest):
        return fraud_features(request)
    if model_name == "demand" and isinstance(request, DemandFeatures):
        return {
            "sales_same_hour_last_week": float(request.sales_same_hour_last_week),
            "sales_avg_7d": float(request.sales_avg_7d),
            "sales_avg_30d": float(request.sales_avg_30d),
            "temperature_celsius": float(request.temperature_celsius),
            "discount_percent": float(request.discount_percent),
        }
    if model_name == "personalization" and isinstance(request, PersonalizationFeatures):
        return {
            "user_avg_order_value": float(request.user_avg_order_value),
            "user_order_frequency_7d": float(request.user_order_frequency_7d),
            "time_of_day_hour": float(request.time_of_day_hour),
        }
    if model_name == "clv" and isinstance(request, CLVFeatures):
        return {
            "recency_days": float(request.recency_days),
            "frequency_total": float(request.frequency_total),
            "frequency_30d": float(request.frequency_30d),
            "monetary_avg_cents": float(request.monetary_avg_cents),
            "tenure_days": float(request.tenure_days),
        }
    if model_name == "pricing" and isinstance(request, PricingFeatures):
        return {
            "order_amount_cents": float(request.order_amount_cents),
            "distance_km": float(request.distance_km),
            "active_riders_in_zone": float(request.active_riders_in_zone),
            "pending_orders_in_zone": float(request.pending_orders_in_zone),
            "surge_multiplier": float(request.surge_multiplier),
        }
    raise ValueError("unknown_model")


def _build_onnx_input_array(model_name: str, request: Any) -> np.ndarray:
    if model_name == "eta" and isinstance(request, EtaRequest):
        return ETAModel.preprocess(_to_eta_features(request)).astype(np.float32)
    if model_name == "ranking" and isinstance(request, RankingRequest):
        return RankingModel.preprocess([_to_ranking_features(request)]).astype(np.float32)
    if model_name == "fraud" and isinstance(request, FraudRequest):
        return FraudModel.preprocess(_to_fraud_features(request)).astype(np.float32)
    if model_name == "demand" and isinstance(request, DemandFeatures):
        return DemandModel.preprocess(request).astype(np.float32)
    if model_name == "personalization" and isinstance(request, PersonalizationFeatures):
        return np.array(
            [[
                float(request.user_avg_order_value),
                float(request.user_order_frequency_7d),
                float(request.time_of_day_hour),
                float(len(request.candidate_product_ids)),
            ]],
            dtype=np.float32,
        )
    if model_name == "clv" and isinstance(request, CLVFeatures):
        return CLVModel.preprocess(request).astype(np.float32)
    if model_name == "pricing" and isinstance(request, PricingFeatures):
        return DynamicPricingModel.preprocess(request).astype(np.float32)
    raise ValueError("unknown_model")


def _build_cache_key_for_request(model_name: str, version: str, request: BaseModel) -> str:
    payload = request.model_dump(mode="json", exclude={"model_version"})
    encoded = json.dumps(
        {"model": model_name, "version": version, "payload": payload},
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _predict_with_domain_model(
    model_name: str,
    request: BaseModel,
    model_instance: Any,
    model_version: str,
    onnx_session: Optional[ManagedOnnxSession],
) -> Dict[str, Any]:
    use_onnx = model_version.endswith("-onnx-v1") and onnx_session is not None
    onnx_scalar: Optional[float] = None
    onnx_method: Optional[str] = None
    if use_onnx and onnx_session is not None:
        tensor = _build_onnx_input_array(model_name, request)
        outputs = onnx_session.run(None, {"input": tensor})
        onnx_method = "onnx" if not onnx_session._is_mock else "onnx_fallback"
        if not onnx_session._is_mock and outputs:
            onnx_scalar = float(np.asarray(outputs[0]).reshape(-1)[0])

    if model_name == "eta" and isinstance(request, EtaRequest):
        res = model_instance.predict(_to_eta_features(request))
        eta_val = max(0.0, onnx_scalar) if onnx_scalar is not None else res.eta_minutes
        ci_lo, ci_hi = compute_confidence_interval(
            eta_val, std=MODEL_OUTPUT_STD["eta"], lower_bound=0.0, upper_bound=None
        )
        return {
            "eta_minutes": round(eta_val, 4),
            "feature_contributions": res.feature_contributions,
            "bias": 0.0,
            "model_version": model_version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": False,
            "sla_breached": False,
            "method": onnx_method or res.method,
        }

    if model_name == "ranking" and isinstance(request, RankingRequest):
        rf = _to_ranking_features(request)
        res = model_instance.predict([rf])
        raw_val = onnx_scalar if onnx_scalar is not None else float(res.ranked_items[0]["score"])
        score_val = safe_sigmoid(raw_val)
        ci_lo, ci_hi = compute_confidence_interval(
            score_val, std=MODEL_OUTPUT_STD["ranking"], lower_bound=0.0, upper_bound=1.0
        )
        return {
            "ranking_score": round(score_val, 6),
            "raw_score": round(raw_val, 6),
            "feature_contributions": {
                "relevance_score": request.relevance_score,
                "price_score": request.price_score,
                "availability_score": request.availability_score,
                "user_affinity": request.user_affinity,
            },
            "bias": 0.0,
            "model_version": model_version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": False,
            "sla_breached": False,
            "method": onnx_method or res.method,
        }

    if model_name == "fraud" and isinstance(request, FraudRequest):
        res = model_instance.predict(_to_fraud_features(request))
        prob = min(1.0, max(0.0, safe_sigmoid(onnx_scalar))) if onnx_scalar is not None else res.fraud_probability
        raw_val = onnx_scalar if onnx_scalar is not None else round(res.fraud_score / 100.0, 6)
        ci_lo, ci_hi = compute_confidence_interval(
            prob, std=MODEL_OUTPUT_STD["fraud"], lower_bound=0.0, upper_bound=1.0
        )
        return {
            "fraud_probability": round(prob, 6),
            "raw_score": raw_val,
            "feature_contributions": res.feature_contributions,
            "bias": 0.0,
            "model_version": model_version,
            "is_cold_start": False,
            "confidence_lower": ci_lo,
            "confidence_upper": ci_hi,
            "drift_detected": False,
            "sla_breached": False,
            "method": onnx_method or res.method,
        }

    res = model_instance.predict(request)
    output: Dict[str, Any] = res.model_dump()
    if onnx_scalar is not None:
        if model_name == "demand":
            units = max(0.0, round(onnx_scalar, 2))
            output["predicted_units"] = units
        elif model_name == "clv":
            cents = max(0, int(round(onnx_scalar)))
            output["predicted_annual_value_cents"] = cents
            output["predicted_annual_value_dollars"] = round(cents / 100.0, 2)
        elif model_name == "pricing":
            cents = max(0, int(round(onnx_scalar)))
            output["delivery_fee_cents"] = cents
            output["delivery_fee_dollars"] = round(cents / 100.0, 2)
    if onnx_method is not None:
        output["method"] = onnx_method
    return output


def _coerce_sla_fallback(
    model_name: str,
    request: BaseModel,
    fallback: Dict[str, Any],
    model_version: str,
    drift_detected: bool,
) -> Dict[str, Any]:
    if model_name == "demand" and isinstance(request, DemandFeatures):
        units = float(fallback.get("score", 10.0))
        return {
            "predicted_units": units,
            "confidence_lower": float(fallback.get("confidence_lower") or 0.0),
            "confidence_upper": float(fallback.get("confidence_upper") or units),
            "store_id": request.store_id,
            "product_id": request.product_id,
            "hour": request.hour,
            "model_version": model_version,
            "feature_contributions": {},
            "method": "sla_timeout_fallback",
            "is_cold_start": True,
            "drift_detected": drift_detected,
            "sla_breached": True,
        }
    if model_name == "personalization" and isinstance(request, PersonalizationFeatures):
        score = float(fallback.get("score", 0.5))
        recs = [
            {"product_id": pid, "score": score, "rank": idx + 1}
            for idx, pid in enumerate(request.candidate_product_ids[: request.max_results])
        ]
        surface = request.surface.value if hasattr(request.surface, "value") else str(request.surface)
        return {
            "recommendations": recs,
            "user_id": request.user_id,
            "surface": surface,
            "model_version": model_version,
            "method": "sla_timeout_fallback",
            "is_cold_start": True,
            "drift_detected": drift_detected,
            "sla_breached": True,
        }
    if model_name == "clv" and isinstance(request, CLVFeatures):
        dollars = float(fallback.get("score", 240.0))
        cents = max(0, int(round(dollars * 100.0)))
        return {
            "predicted_annual_value_cents": cents,
            "predicted_annual_value_dollars": round(cents / 100.0, 2),
            "segment": CLVSegment.GOLD.value,
            "survival_probability_12m": 0.5,
            "expected_purchases_12m": 1.0,
            "user_id": request.user_id,
            "model_version": model_version,
            "feature_contributions": {},
            "method": "sla_timeout_fallback",
            "is_cold_start": True,
            "drift_detected": drift_detected,
            "sla_breached": True,
        }
    if model_name == "pricing" and isinstance(request, PricingFeatures):
        cents = max(0, int(round(float(fallback.get("score", 499.0)))))
        return {
            "delivery_fee_cents": cents,
            "delivery_fee_dollars": round(cents / 100.0, 2),
            "discount_applied_cents": 0,
            "surge_applied": False,
            "guardrail_hit": "sla_timeout",
            "model_version": model_version,
            "feature_contributions": {},
            "method": "sla_timeout_fallback",
            "is_cold_start": True,
            "drift_detected": drift_detected,
            "sla_breached": True,
        }
    return {**fallback, "drift_detected": drift_detected, "sla_breached": True}


def serve_inference_request(
    model_name: str,
    request: BaseModel,
    requested_version: Optional[str],
    *,
    registry: ModelRegistry,
    cache: Optional[InferenceCache],
    models: Mapping[str, Any],
    onnx_sessions: Mapping[str, ManagedOnnxSession],
) -> tuple[Dict[str, Any], str]:
    """Unified serving pipeline: ModelRegistry -> InferenceCache -> health -> SLA -> shadow."""
    model_config = resolve_model_config(registry, model_name, requested_version)
    numeric_features = extract_numeric_features(model_name, request)

    if model_name in {"eta", "ranking", "fraud"} and model_config.version in _LINEAR_VERSIONS:
        output, _ = execute_inference(model_name, numeric_features, model_config, cache, registry)
        return {**output, "method": "linear"}, model_config.version

    health = assess_feature_health(
        model_name,
        numeric_features,
        expected_features=list(numeric_features.keys()),
        z_threshold=settings.ood_z_threshold,
        max_missing_ratio=settings.max_missing_feature_ratio,
    )

    cache_key: Optional[str] = None
    if cache is not None:
        cache_key = _build_cache_key_for_request(model_name, model_config.version, request)
        cached = cache.get(cache_key)
        if cached is not None:
            CACHE_EVENTS.labels(model=model_name, version=model_config.version, result="hit").inc()
            output = {**cached, "sla_breached": False}
            maybe_run_shadow(model_name, numeric_features, model_config.version, registry, primary_output=output)
            return output, model_config.version
        CACHE_EVENTS.labels(model=model_name, version=model_config.version, result="miss").inc()

    model_instance = models[model_name]
    onnx_session = onnx_sessions.get(model_name)

    def _run_model() -> Dict[str, Any]:
        return _predict_with_domain_model(
            model_name, request, model_instance, model_config.version, onnx_session
        )

    computed, sla_breached, _ = enforce_sync_sla(
        model_name,
        _run_model,
        budget_ms=settings.sla_budgets_ms.get(model_name),
        strict=settings.strict_sla_timeout,
        model_version=model_config.version,
        fallback_on_timeout=True,
    )
    if sla_breached:
        output = _coerce_sla_fallback(
            model_name, request, computed, model_config.version, health.drift_detected
        )
    else:
        output = {
            **computed,
            "model_version": model_config.version,
            "drift_detected": health.drift_detected,
            "sla_breached": False,
        }
        if cache is not None and cache_key is not None:
            cache.set(cache_key, output)

    maybe_run_shadow(model_name, numeric_features, model_config.version, registry, primary_output=output)
    return output, model_config.version
