from __future__ import annotations

import asyncio
import atexit
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeoutError
from dataclasses import dataclass
import logging
import math
import os
import threading
import time
from typing import Any, Awaitable, Callable, Dict, Iterable, List, Mapping, Optional, Tuple

from prometheus_client import Counter, Gauge, Histogram

from app.core.config import DEFAULT_SLA_BUDGETS_MS

logger = logging.getLogger("ai_inference.guardrails")

FEATURE_MISSING_RATIO = Gauge("ai_inference_feature_missing_ratio", "Missing/non-finite feature ratio", ["model"])
OOD_FEATURE_TOTAL = Counter("ai_inference_ood_features_total", "Out-of-distribution features exceeding z-score threshold", ["model", "feature"])
FEATURE_DRIFT_ALERTS_TOTAL = Counter("ai_inference_feature_drift_alerts_total", "Online feature drift alerts", ["model", "reason"])
COLD_START_FALLBACK_TOTAL = Counter("ai_inference_cold_start_fallback_total", "Cold-start fallback predictions served", ["model", "reason"])
SLA_BREACH_TOTAL = Counter("ai_inference_sla_breaches_total", "Per-model SLA timeout breaches", ["model", "action"])
MODEL_LATENCY_MS = Histogram("ai_inference_model_duration_ms", "Per-model inference latency in ms", ["model"])

_SLA_EXECUTOR_LOCK = threading.Lock()
_SLA_EXECUTOR: Optional[ThreadPoolExecutor] = None
_SLA_MAX_WORKERS = max(4, min(32, (os.cpu_count() or 2) * 2))


def get_sla_executor() -> ThreadPoolExecutor:
    global _SLA_EXECUTOR
    with _SLA_EXECUTOR_LOCK:
        if _SLA_EXECUTOR is None:
            _SLA_EXECUTOR = ThreadPoolExecutor(
                max_workers=_SLA_MAX_WORKERS,
                thread_name_prefix="ai-inference-sla",
            )
        return _SLA_EXECUTOR


def shutdown_sla_executor(wait: bool = False) -> None:
    global _SLA_EXECUTOR
    with _SLA_EXECUTOR_LOCK:
        executor = _SLA_EXECUTOR
        _SLA_EXECUTOR = None
    if executor is not None:
        executor.shutdown(wait=wait, cancel_futures=True)


atexit.register(shutdown_sla_executor)


class SLABudgetExceededError(TimeoutError):
    """Raised when strict SLA enforcement aborts an over-budget inference call."""


@dataclass(frozen=True)
class FeatureBaseline:
    mean: float
    std: float
    min_val: Optional[float] = None
    max_val: Optional[float] = None


DEFAULT_FEATURE_BASELINES: Dict[str, Dict[str, FeatureBaseline]] = {
    "eta": {
        "distance_km": FeatureBaseline(3.5, 2.5, 0.0, 200.0),
        "item_count": FeatureBaseline(6.0, 4.5, 0.0, 200.0),
        "traffic_factor": FeatureBaseline(1.2, 0.35, 0.5, 3.0),
    },
    "ranking": {
        "relevance_score": FeatureBaseline(0.65, 0.20, 0.0, 1.0),
        "price_score": FeatureBaseline(0.60, 0.20, 0.0, 1.0),
        "availability_score": FeatureBaseline(0.90, 0.18, 0.0, 1.0),
        "user_affinity": FeatureBaseline(0.45, 0.25, 0.0, 1.0),
    },
    "fraud": {
        "order_amount": FeatureBaseline(45.0, 40.0, 0.0, 10000.0),
        "chargeback_rate": FeatureBaseline(0.02, 0.05, 0.0, 1.0),
        "device_risk": FeatureBaseline(0.10, 0.15, 0.0, 1.0),
        "account_age_days": FeatureBaseline(180.0, 160.0, 0.0, 36500.0),
    },
}

COLD_START_PRIORS: Dict[str, Tuple[float, float, float, Optional[float]]] = {
    "eta": (22.0, 5.0, 1.0, 120.0),
    "ranking": (0.50, 0.15, 0.0, 1.0),
    "fraud": (0.08, 0.05, 0.0, 1.0),
    "personalization": (0.50, 0.18, 0.0, 1.0),
    "demand": (10.0, 4.0, 0.0, None),
    "clv": (240.0, 80.0, 0.0, None),
    "pricing": (499.0, 120.0, 0.0, None),
}

MODEL_OUTPUT_STD: Dict[str, float] = {
    "eta": 3.5,
    "ranking": 0.10,
    "fraud": 0.06,
    "personalization": 0.12,
    "demand": 3.0,
    "clv": 50.0,
    "pricing": 60.0,
}


@dataclass(frozen=True)
class FeatureHealthReport:
    model_name: str
    missing_count: int
    expected_count: int
    missing_ratio: float
    missing_features: List[str]
    ood_features: Dict[str, float]
    max_z_score: float
    drift_detected: bool
    missing_threshold_exceeded: bool


@dataclass(frozen=True)
class ColdStartPrediction:
    model_name: str
    score: float
    confidence_lower: float
    confidence_upper: float
    is_cold_start: bool
    cold_start_reason: str
    method: str = "cold_start_prior"


def assess_feature_health(
    model_name: str,
    features: Mapping[str, Any],
    *,
    expected_features: Optional[Iterable[str]] = None,
    baselines: Optional[Mapping[str, FeatureBaseline]] = None,
    z_threshold: float = 4.0,
    max_missing_ratio: float = 0.35,
) -> FeatureHealthReport:
    model_baselines = baselines or DEFAULT_FEATURE_BASELINES.get(model_name, {})
    expected = (
        list(expected_features)
        if expected_features is not None
        else (list(model_baselines.keys()) if model_baselines else list(features.keys()))
    )
    missing_features: List[str] = []
    ood_features: Dict[str, float] = {}
    max_z = 0.0

    for name in expected:
        val = features.get(name)
        if val is None or isinstance(val, bool) or not isinstance(val, (int, float)) or not math.isfinite(float(val)):
            missing_features.append(name)
            continue

    for name, raw_val in features.items():
        if raw_val is None or isinstance(raw_val, bool) or not isinstance(raw_val, (int, float)):
            continue
        val = float(raw_val)
        if not math.isfinite(val):
            continue
        base = model_baselines.get(name)
        if base is None:
            continue
        if base.std > 0:
            z_score = abs(val - base.mean) / base.std
            if z_score > max_z:
                max_z = z_score
            if z_score > z_threshold:
                ood_features[name] = round(z_score, 4)
                OOD_FEATURE_TOTAL.labels(model=model_name, feature=name).inc()

    expected_count = len(expected)
    missing_count = len(missing_features)
    missing_ratio = (missing_count / expected_count) if expected_count > 0 else 0.0
    FEATURE_MISSING_RATIO.labels(model=model_name).set(missing_ratio)

    missing_exceeded = missing_ratio > max_missing_ratio
    drift_detected = bool(ood_features) or missing_exceeded

    if ood_features:
        FEATURE_DRIFT_ALERTS_TOTAL.labels(model=model_name, reason="ood_z_score").inc()
    if missing_exceeded:
        FEATURE_DRIFT_ALERTS_TOTAL.labels(model=model_name, reason="missing_ratio").inc()
    if drift_detected:
        logger.warning(
            "feature_health.alert",
            extra={
                "model": model_name,
                "missing_ratio": round(missing_ratio, 4),
                "ood_features": ood_features,
                "max_z_score": round(max_z, 4),
            },
        )

    return FeatureHealthReport(
        model_name=model_name,
        missing_count=missing_count,
        expected_count=expected_count,
        missing_ratio=round(missing_ratio, 4),
        missing_features=missing_features,
        ood_features=ood_features,
        max_z_score=round(max_z, 4),
        drift_detected=drift_detected,
        missing_threshold_exceeded=missing_exceeded,
    )


def compute_confidence_interval(
    score: float,
    *,
    std: float,
    z_value: float = 1.645,
    lower_bound: Optional[float] = 0.0,
    upper_bound: Optional[float] = None,
) -> Tuple[float, float]:
    half_width = max(0.0, z_value * max(std, 0.0))
    lower = score - half_width
    upper = score + half_width
    if lower_bound is not None:
        lower = max(lower_bound, lower)
    if upper_bound is not None:
        upper = min(upper_bound, upper)
    return round(lower, 4), round(upper, 4)


def is_cold_start_context(
    model_name: str,
    features: Mapping[str, Any],
    *,
    missing_ratio: float = 0.0,
    entity_metadata: Optional[Mapping[str, Any]] = None,
) -> Tuple[bool, Optional[str]]:
    meta = entity_metadata or {}
    if bool(meta.get("is_cold_start")):
        return True, "explicit_cold_start"
    if bool(meta.get("is_new_user")):
        return True, "new_user"
    if bool(meta.get("is_new_item")):
        return True, "new_item"
    history_count = meta.get("history_count", meta.get("order_count_30d"))
    if isinstance(history_count, (int, float)) and history_count == 0:
        return True, "zero_history"
    if not features:
        return True, "empty_features"
    if missing_ratio >= 0.5:
        return True, "high_missing_features"
    if model_name in ("personalization", "clv") and all(
        features.get(k, 0) in (0, 0.0, None)
        for k in ("frequency_total", "frequency_30d", "user_affinity")
        if k in features
    ) and any(k in features for k in ("frequency_total", "frequency_30d", "user_affinity")):
        return True, "cold_start_entity"
    return False, None


def score_cold_start_fallback(
    model_name: str,
    *,
    reason: str = "insufficient_history",
    z_value: float = 1.645,
) -> ColdStartPrediction:
    mean, std, lb, ub = COLD_START_PRIORS.get(model_name, (0.5, 0.2, 0.0, 1.0))
    lower, upper = compute_confidence_interval(
        mean, std=std, z_value=z_value, lower_bound=lb, upper_bound=ub
    )
    COLD_START_FALLBACK_TOTAL.labels(model=model_name, reason=reason).inc()
    return ColdStartPrediction(
        model_name=model_name,
        score=mean,
        confidence_lower=lower,
        confidence_upper=upper,
        is_cold_start=True,
        cold_start_reason=reason,
    )


def build_cold_start_output(
    model_name: str,
    model_version: str,
    *,
    reason: str = "insufficient_history",
    drift_detected: bool = False,
    sla_breached: bool = False,
) -> Dict[str, Any]:
    pred = score_cold_start_fallback(model_name, reason=reason)
    base: Dict[str, Any] = {
        "feature_contributions": {},
        "bias": 0.0,
        "model_version": model_version,
        "is_cold_start": True,
        "cold_start_reason": pred.cold_start_reason,
        "confidence_lower": pred.confidence_lower,
        "confidence_upper": pred.confidence_upper,
        "drift_detected": drift_detected,
        "sla_breached": sla_breached,
    }
    if model_name == "eta":
        base["eta_minutes"] = pred.score
    elif model_name == "ranking":
        base["ranking_score"] = pred.score
        base["raw_score"] = 0.0
    elif model_name == "fraud":
        base["fraud_probability"] = pred.score
        base["raw_score"] = -2.44
    else:
        base["score"] = pred.score
    return base


def _sla_budget_for_model(model_name: str, override_ms: Optional[float] = None) -> float:
    return (
        float(override_ms)
        if override_ms is not None and override_ms > 0
        else DEFAULT_SLA_BUDGETS_MS.get(model_name, 25.0)
    )


def _handle_sla_timeout(
    model_name: str,
    model_version: str,
    budget: float,
    elapsed_ms: float,
    fallback_on_timeout: bool,
    exc: BaseException,
) -> Tuple[Dict[str, Any], bool, float]:
    MODEL_LATENCY_MS.labels(model=model_name).observe(elapsed_ms)
    if not fallback_on_timeout:
        SLA_BREACH_TOTAL.labels(model=model_name, action="rejected").inc()
        raise SLABudgetExceededError(f"Model {model_name} exceeded SLA budget ({budget:.1f}ms)") from exc
    SLA_BREACH_TOTAL.labels(model=model_name, action="fallback").inc()
    logger.warning(
        "sla.timeout_fallback",
        extra={"model": model_name, "budget_ms": budget, "elapsed_ms": round(elapsed_ms, 2)},
    )
    fallback = build_cold_start_output(
        model_name, model_version, reason="sla_timeout", sla_breached=True
    )
    return fallback, True, elapsed_ms


def observe_sla_latency(model_name: str, elapsed_ms: float, budget_ms: float) -> bool:
    MODEL_LATENCY_MS.labels(model=model_name).observe(elapsed_ms)
    if elapsed_ms > budget_ms:
        SLA_BREACH_TOTAL.labels(model=model_name, action="observed_breach").inc()
        logger.warning(
            "sla.observed_breach",
            extra={"model": model_name, "budget_ms": budget_ms, "elapsed_ms": round(elapsed_ms, 2)},
        )
        return True
    return False


def enforce_sync_sla(
    model_name: str,
    fn: Callable[[], Dict[str, Any]],
    *,
    budget_ms: Optional[float] = None,
    strict: bool = False,
    model_version: str = "fallback",
    fallback_on_timeout: bool = True,
) -> Tuple[Dict[str, Any], bool, float]:
    budget = _sla_budget_for_model(model_name, budget_ms)
    start = time.perf_counter()

    if strict:
        future = get_sla_executor().submit(fn)
        try:
            result = future.result(timeout=budget / 1000.0)
        except FuturesTimeoutError as exc:
            future.cancel()
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            return _handle_sla_timeout(
                model_name, model_version, budget, elapsed_ms, fallback_on_timeout, exc
            )
    else:
        result = fn()

    elapsed_ms = (time.perf_counter() - start) * 1000.0
    breached = observe_sla_latency(model_name, elapsed_ms, budget)
    return result, breached, elapsed_ms


async def enforce_async_sla(
    model_name: str,
    awaitable: Awaitable[Dict[str, Any]],
    *,
    budget_ms: Optional[float] = None,
    model_version: str = "fallback",
    fallback_on_timeout: bool = True,
) -> Tuple[Dict[str, Any], bool, float]:
    budget = _sla_budget_for_model(model_name, budget_ms)
    start = time.perf_counter()
    try:
        result = await asyncio.wait_for(awaitable, timeout=budget / 1000.0)
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        MODEL_LATENCY_MS.labels(model=model_name).observe(elapsed_ms)
        return result, False, elapsed_ms
    except asyncio.TimeoutError as exc:
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return _handle_sla_timeout(
            model_name, model_version, budget, elapsed_ms, fallback_on_timeout, exc
        )
