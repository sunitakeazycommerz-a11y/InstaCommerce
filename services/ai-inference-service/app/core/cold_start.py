"""Cold-start detection, priors, and confidence interval helpers."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Mapping, Optional, Tuple

from prometheus_client import Counter

COLD_START_FALLBACK_TOTAL = Counter(
    "ai_inference_cold_start_fallback_total",
    "Cold-start fallback predictions served",
    ["model", "reason"],
)

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
class ColdStartPrediction:
    model_name: str
    score: float
    confidence_lower: float
    confidence_upper: float
    is_cold_start: bool
    cold_start_reason: str
    method: str = "cold_start_prior"


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
