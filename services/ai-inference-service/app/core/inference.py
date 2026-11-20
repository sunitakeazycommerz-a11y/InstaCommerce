"""Inference execution, caching, shadow dispatch, and batch processing helpers."""

from __future__ import annotations

import hashlib
import json
import random
import time
from math import exp
from typing import Any, Dict, Mapping, Optional

from fastapi import HTTPException
from prometheus_client import Counter, Histogram
from pydantic import ValidationError

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
    fetch_feature_store_features,
    parse_model_request,
)
from app.features.store import FeatureStoreClient, UnavailableFeatureStoreClient
from app.schemas import BatchInferenceItem, BatchInferenceResult

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
