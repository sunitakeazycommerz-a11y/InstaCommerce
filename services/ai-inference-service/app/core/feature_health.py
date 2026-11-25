"""Feature health assessment and drift detection guardrails."""

from __future__ import annotations

from dataclasses import dataclass
import logging
import math
from typing import Any, Dict, Iterable, List, Mapping, Optional

from prometheus_client import Counter, Gauge

logger = logging.getLogger("ai_inference.guardrails")

FEATURE_MISSING_RATIO = Gauge("ai_inference_feature_missing_ratio", "Missing/non-finite feature ratio", ["model"])
OOD_FEATURE_TOTAL = Counter("ai_inference_ood_features_total", "Out-of-distribution features exceeding z-score threshold", ["model", "feature"])
FEATURE_DRIFT_ALERTS_TOTAL = Counter("ai_inference_feature_drift_alerts_total", "Online feature drift alerts", ["model", "reason"])


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
    "demand": {
        "sales_same_hour_last_week": FeatureBaseline(10.0, 12.0, 0.0, 5000.0),
        "sales_avg_7d": FeatureBaseline(9.0, 10.0, 0.0, 5000.0),
        "sales_avg_30d": FeatureBaseline(8.5, 9.5, 0.0, 5000.0),
        "temperature_celsius": FeatureBaseline(26.0, 7.0, -10.0, 55.0),
        "discount_percent": FeatureBaseline(10.0, 12.0, 0.0, 100.0),
    },
    "personalization": {
        "user_avg_order_value": FeatureBaseline(350.0, 250.0, 0.0, 50000.0),
        "user_order_frequency_7d": FeatureBaseline(2.5, 2.0, 0.0, 100.0),
        "time_of_day_hour": FeatureBaseline(13.0, 6.0, 0.0, 23.0),
    },
    "clv": {
        "recency_days": FeatureBaseline(12.0, 15.0, 0.0, 3650.0),
        "frequency_total": FeatureBaseline(10.0, 12.0, 0.0, 5000.0),
        "frequency_30d": FeatureBaseline(2.5, 2.5, 0.0, 500.0),
        "monetary_avg_cents": FeatureBaseline(3500.0, 2500.0, 0.0, 500000.0),
        "tenure_days": FeatureBaseline(180.0, 150.0, 0.0, 3650.0),
    },
    "pricing": {
        "order_amount_cents": FeatureBaseline(2000.0, 1500.0, 0.0, 500000.0),
        "distance_km": FeatureBaseline(3.5, 2.5, 0.0, 200.0),
        "active_riders_in_zone": FeatureBaseline(10.0, 6.0, 0.0, 500.0),
        "pending_orders_in_zone": FeatureBaseline(6.0, 5.0, 0.0, 500.0),
        "surge_multiplier": FeatureBaseline(1.15, 0.4, 0.5, 5.0),
    },
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
