"""CLV Prediction — BG/NBD + Gamma-Gamma model.

Segments: Platinum (>$500/yr), Gold ($200-500), Silver ($50-200), Bronze (<$50).
"""
from __future__ import annotations

import logging
import threading
import time
from enum import Enum
from typing import Any, Dict, List, Optional

import numpy as np
from pydantic import BaseModel, Field
from prometheus_client import Counter, Histogram

logger = logging.getLogger("ai_inference.models.clv")

# ---------------------------------------------------------------------------
# Prometheus metrics
# ---------------------------------------------------------------------------
CLV_PREDICTIONS = Counter(
    "ml_clv_predictions_total",
    "Total CLV predictions",
    ["segment", "model_version", "method"],
)
CLV_LATENCY = Histogram(
    "ml_clv_latency_seconds",
    "CLV model inference latency",
    ["model_version"],
)
CLV_VALUE_DIST = Histogram(
    "ml_clv_predicted_value_dollars",
    "Distribution of predicted CLV ($)",
    buckets=[10, 25, 50, 100, 200, 300, 500, 750, 1000, 2000],
)


# ---------------------------------------------------------------------------
# Pydantic schemas
# ---------------------------------------------------------------------------
class CLVSegment(str, Enum):
    PLATINUM = "platinum"   # >$500/yr
    GOLD = "gold"           # $200-500/yr
    SILVER = "silver"       # $50-200/yr
    BRONZE = "bronze"       # <$50/yr


class CLVFeatures(BaseModel):
    """Input features for CLV prediction."""
    user_id: str = Field(..., description="User identifier")
    # RFM features (Recency, Frequency, Monetary)
    recency_days: int = Field(default=0, ge=0, description="Days since last purchase")
    frequency_total: int = Field(default=1, ge=0, description="Lifetime order count")
    frequency_30d: int = Field(default=0, ge=0, description="Orders in last 30 days")
    monetary_avg_cents: int = Field(default=0, ge=0, description="Avg order value in cents")
    monetary_total_cents: int = Field(default=0, ge=0, description="Lifetime spend in cents")
    # Engagement
    tenure_days: int = Field(default=0, ge=0, description="Days since account creation")
    app_opens_30d: int = Field(default=0, ge=0, description="App opens in last 30 days")
    sessions_avg_duration_sec: float = Field(default=0, ge=0, description="Avg session duration")
    # Behavioural
    categories_purchased: int = Field(default=1, ge=0, description="Distinct categories purchased")
    referral_count: int = Field(default=0, ge=0, description="Referrals made")
    has_subscription: bool = False
    churn_risk_score: float = Field(default=0.0, ge=0, le=1, description="Predicted churn risk")
    # Channel
    is_organic_acquisition: bool = True
    preferred_payment: str = Field(default="card", description="Preferred payment method")


class CLVResponse(BaseModel):
    """CLV prediction result."""
    predicted_annual_value_cents: int = Field(..., ge=0, description="Predicted annual CLV in cents")
    predicted_annual_value_dollars: float = Field(..., ge=0)
    segment: CLVSegment
    survival_probability_12m: float = Field(..., ge=0, le=1, description="P(active in 12 months)")
    expected_purchases_12m: float = Field(..., ge=0, description="Expected purchases next 12m")
    user_id: str
    model_version: str
    feature_contributions: Dict[str, float] = Field(default_factory=dict)
    method: str = Field(default="rule_based")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
_SEGMENT_THRESHOLDS = [
    (50000, CLVSegment.PLATINUM),   # >$500
    (20000, CLVSegment.GOLD),       # $200-500
    (5000, CLVSegment.SILVER),      # $50-200
]


def _segment_from_cents(value_cents: int) -> CLVSegment:
    for threshold, segment in _SEGMENT_THRESHOLDS:
        if value_cents >= threshold:
            return segment
    return CLVSegment.BRONZE


# ---------------------------------------------------------------------------
# Model implementation
# ---------------------------------------------------------------------------
class CLVModel:
    """BG/NBD + Gamma-Gamma CLV prediction model.

    BG/NBD models purchase frequency & churn; Gamma-Gamma models monetary value.
    Falls back to RFM-based heuristic when ML model is not loaded.
    Thread-safe inference.
    """

    MODEL_NAME = "clv"
    DEFAULT_VERSION = "clv-bgnbd-v1"

    FEATURE_COLUMNS: List[str] = [
        "recency_days", "frequency_total", "frequency_30d",
        "monetary_avg_cents", "monetary_total_cents",
        "tenure_days", "app_opens_30d", "sessions_avg_duration_sec",
        "categories_purchased", "referral_count",
        "has_subscription", "churn_risk_score",
        "is_organic_acquisition",
    ]

    def __init__(self, model_path: Optional[str] = None, version: Optional[str] = None) -> None:
        self._version = version or self.DEFAULT_VERSION
        self._model: Any = None
        self._lock = threading.Lock()
        if model_path:
            self.load_model(model_path)

    # -- Model lifecycle -----------------------------------------------------

    def load_model(self, path: str) -> None:
        """Load serialised BG/NBD + Gamma-Gamma model."""
        try:
            import pickle
            with open(path, "rb") as f:
                model = pickle.load(f)
            with self._lock:
                self._model = model
            logger.info("Loaded CLV model from %s (version=%s)", path, self._version)
        except Exception:
            logger.exception("Failed to load CLV model from %s – falling back to rule-based", path)

    @property
    def is_ml_loaded(self) -> bool:
        return self._model is not None

    @property
    def version(self) -> str:
        return self._version

    # -- Feature preprocessing -----------------------------------------------

    @staticmethod
    def preprocess(features: CLVFeatures) -> np.ndarray:
        """Convert pydantic features to a NumPy row."""
        return np.array([[
            float(features.recency_days),
            float(features.frequency_total),
            float(features.frequency_30d),
            float(features.monetary_avg_cents),
            float(features.monetary_total_cents),
            float(features.tenure_days),
            float(features.app_opens_30d),
            features.sessions_avg_duration_sec,
            float(features.categories_purchased),
            float(features.referral_count),
            float(features.has_subscription),
            features.churn_risk_score,
            float(features.is_organic_acquisition),
        ]], dtype=np.float64)

    # -- Prediction -----------------------------------------------------------

    def predict(self, features: CLVFeatures) -> CLVResponse:
        """Run CLV prediction – ML model if available, else rule-based fallback."""
        start = time.perf_counter()
        try:
            if self._model is not None:
                result = self._predict_ml(features)
            else:
                result = self._rule_based_fallback(features)
            CLV_VALUE_DIST.observe(result.predicted_annual_value_dollars)
            return result
        finally:
            CLV_LATENCY.labels(model_version=self._version).observe(
                time.perf_counter() - start
            )

    def _predict_ml(self, features: CLVFeatures) -> CLVResponse:
        """Predict using loaded BG/NBD + Gamma-Gamma model."""
        X = self.preprocess(features)
        with self._lock:
            prediction = self._model.predict(X)

        annual_cents = max(0, int(prediction[0]))
        annual_dollars = round(annual_cents / 100.0, 2)
        segment = _segment_from_cents(annual_cents)

        # Extract expected purchases and survival if model provides them
        expected_purchases = float(prediction[1]) if len(prediction) > 1 else 0.0
        survival_prob = float(prediction[2]) if len(prediction) > 2 else 0.5

        CLV_PREDICTIONS.labels(
            segment=segment.value, model_version=self._version, method="ml"
        ).inc()
        return CLVResponse(
            predicted_annual_value_cents=annual_cents,
            predicted_annual_value_dollars=annual_dollars,
            segment=segment,
            survival_probability_12m=round(min(1.0, max(0.0, survival_prob)), 4),
            expected_purchases_12m=round(max(0.0, expected_purchases), 2),
            user_id=features.user_id,
            model_version=self._version,
            method="ml",
        )

    def _rule_based_fallback(self, features: CLVFeatures) -> CLVResponse:
        """RFM-based heuristic CLV estimation."""
        contributions: Dict[str, float] = {}

        # Estimate annual purchase frequency from recent behaviour
        if features.tenure_days > 0 and features.frequency_total > 0:
            daily_rate = features.frequency_total / max(features.tenure_days, 1)
            expected_annual_orders = daily_rate * 365.0
        else:
            expected_annual_orders = features.frequency_30d * 12.0
        contributions["expected_annual_orders"] = round(expected_annual_orders, 4)

        # Average order value
        avg_order_cents = max(features.monetary_avg_cents, 1)
        contributions["avg_order_cents"] = float(avg_order_cents)

        # Raw annual value
        annual_cents = int(expected_annual_orders * avg_order_cents)

        # Churn risk discount
        survival_prob = max(0.0, 1.0 - features.churn_risk_score)
        # Recency decay: reduce survival if last purchase was long ago
        if features.recency_days > 90:
            survival_prob *= max(0.3, 1.0 - (features.recency_days - 90) / 365.0)
        contributions["survival_probability"] = round(survival_prob, 4)

        annual_cents = int(annual_cents * survival_prob)

        # Subscription bonus
        if features.has_subscription:
            annual_cents = int(annual_cents * 1.3)
            contributions["subscription_bonus"] = 0.3

        # Engagement uplift
        if features.app_opens_30d > 15:
            annual_cents = int(annual_cents * 1.1)
            contributions["high_engagement"] = 0.1

        annual_cents = max(0, annual_cents)
        annual_dollars = round(annual_cents / 100.0, 2)
        segment = _segment_from_cents(annual_cents)

        CLV_PREDICTIONS.labels(
            segment=segment.value, model_version=self._version, method="rule_based"
        ).inc()
        return CLVResponse(
            predicted_annual_value_cents=annual_cents,
            predicted_annual_value_dollars=annual_dollars,
            segment=segment,
            survival_probability_12m=round(survival_prob, 4),
            expected_purchases_12m=round(expected_annual_orders * survival_prob, 2),
            user_id=features.user_id,
            model_version=self._version,
            feature_contributions=contributions,
            method="rule_based",
        )

    # -- Feature importance ---------------------------------------------------

    def feature_importance(self) -> Dict[str, float]:
        """Return feature importance."""
        if self._model is None:
            return {
                "frequency_total": 0.30,
                "monetary_avg_cents": 0.25,
                "recency_days": 0.15,
                "churn_risk_score": 0.10,
                "tenure_days": 0.08,
                "app_opens_30d": 0.05,
                "has_subscription": 0.07,
            }
        try:
            return {k: float(v) for k, v in self._model.feature_importance().items()}
        except Exception:
            logger.debug("Feature importance extraction failed")
            return {}