"""ETA Prediction Model — LightGBM gradient boosted regression.

Three-stage prediction: pre-order, post-assignment, in-transit.
Target: ±1.5min accuracy (from ±5min baseline).
Features: distance, items, traffic, store prep time, rider speed, weather, queue depth.
"""
from __future__ import annotations

import logging
import threading
import time
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np
from pydantic import BaseModel, Field
from prometheus_client import Counter, Histogram

logger = logging.getLogger("ai_inference.models.eta")

# ---------------------------------------------------------------------------
# Prometheus metrics
# ---------------------------------------------------------------------------
ETA_PREDICTIONS = Counter(
    "ml_eta_predictions_total",
    "Total ETA predictions",
    ["stage", "model_version", "method"],
)
ETA_LATENCY = Histogram(
    "ml_eta_latency_seconds",
    "ETA model inference latency",
    ["stage", "model_version"],
)
ETA_PREDICTION_VALUE = Histogram(
    "ml_eta_prediction_minutes",
    "Distribution of ETA predictions",
    ["stage"],
    buckets=[5, 10, 15, 20, 25, 30, 45, 60, 90, 120],
)


# ---------------------------------------------------------------------------
# Pydantic schemas
# ---------------------------------------------------------------------------
class ETAStage(str, Enum):
    PRE_ORDER = "pre_order"
    POST_ASSIGN = "post_assign"
    IN_TRANSIT = "in_transit"


class ETAFeatures(BaseModel):
    """Input features for ETA prediction."""
    distance_km: float = Field(..., ge=0, le=200, description="Straight-line distance in km")
    item_count: int = Field(..., ge=0, le=200, description="Number of items in order")
    traffic_factor: float = Field(..., ge=0.5, le=3.0, description="Current traffic multiplier")
    store_prep_time_minutes: float = Field(default=5.0, ge=0, description="Avg store preparation time")
    rider_avg_speed_kmh: float = Field(default=20.0, ge=0, description="Rider average speed")
    weather_multiplier: float = Field(default=1.0, ge=0.5, le=2.0, description="Weather impact factor")
    queue_depth: int = Field(default=0, ge=0, description="Orders ahead in store queue")
    time_of_day_hour: int = Field(default=12, ge=0, le=23, description="Hour of day (0-23)")
    is_weekend: bool = False
    stage: ETAStage = Field(default=ETAStage.PRE_ORDER, description="Prediction stage")


class ETAResponse(BaseModel):
    """ETA prediction result."""
    eta_minutes: float = Field(..., ge=0, description="Predicted ETA in minutes")
    confidence_lower_minutes: float = Field(..., ge=0, description="Lower prediction interval (90%)")
    confidence_upper_minutes: float = Field(..., ge=0, description="Upper prediction interval (90%)")
    stage: str
    model_version: str
    feature_contributions: Dict[str, float] = Field(default_factory=dict)
    method: str = Field(default="rule_based", description="'ml' or 'rule_based'")


# ---------------------------------------------------------------------------
# Model implementation
# ---------------------------------------------------------------------------
class ETAModel:
    """Production ETA model with ensemble of gradient boosted trees.

    Falls back to rule-based estimation if model loading fails.
    Thread-safe: model artefact is loaded once and read-only at inference time.
    """

    MODEL_NAME = "eta"
    DEFAULT_VERSION = "eta-lgbm-v1"

    # Feature order expected by the trained LightGBM model
    FEATURE_COLUMNS: List[str] = [
        "distance_km", "item_count", "traffic_factor",
        "store_prep_time_minutes", "rider_avg_speed_kmh",
        "weather_multiplier", "queue_depth",
        "time_of_day_hour", "is_weekend",
    ]

    def __init__(self, model_path: Optional[str] = None, version: Optional[str] = None) -> None:
        self._version = version or self.DEFAULT_VERSION
        self._model: Any = None
        self._lock = threading.Lock()
        if model_path:
            self.load_model(model_path)

    # -- Model lifecycle -----------------------------------------------------

    def load_model(self, path: str) -> None:
        """Load a LightGBM Booster from disk (thread-safe)."""
        try:
            import lightgbm as lgb
            booster = lgb.Booster(model_file=path)
            with self._lock:
                self._model = booster
            logger.info("Loaded ETA model from %s (version=%s)", path, self._version)
        except Exception:
            logger.exception("Failed to load ETA model from %s – falling back to rule-based", path)

    @property
    def is_ml_loaded(self) -> bool:
        return self._model is not None

    @property
    def version(self) -> str:
        return self._version

    # -- Feature preprocessing -----------------------------------------------

    @staticmethod
    def preprocess(features: ETAFeatures) -> np.ndarray:
        """Convert pydantic features to a NumPy row for LightGBM."""
        return np.array([[
            features.distance_km,
            float(features.item_count),
            features.traffic_factor,
            features.store_prep_time_minutes,
            features.rider_avg_speed_kmh,
            features.weather_multiplier,
            float(features.queue_depth),
            float(features.time_of_day_hour),
            float(features.is_weekend),
        ]], dtype=np.float64)

    # -- Prediction -----------------------------------------------------------

    def predict(self, features: ETAFeatures) -> ETAResponse:
        """Run prediction – ML model if available, else rule-based fallback."""
        start = time.perf_counter()
        stage = features.stage.value if isinstance(features.stage, ETAStage) else features.stage
        try:
            if self._model is not None:
                result = self._predict_ml(features, stage)
            else:
                result = self._rule_based_fallback(features, stage)
            ETA_PREDICTION_VALUE.labels(stage=stage).observe(result.eta_minutes)
            return result
        finally:
            elapsed = time.perf_counter() - start
            ETA_LATENCY.labels(stage=stage, model_version=self._version).observe(elapsed)

    def _predict_ml(self, features: ETAFeatures, stage: str) -> ETAResponse:
        """Predict using the loaded LightGBM model."""
        X = self.preprocess(features)
        with self._lock:
            prediction = float(self._model.predict(X)[0])

        eta = max(0.0, prediction)
        # Prediction intervals (approximate – trained quantile models in production)
        margin = max(1.0, eta * 0.15)

        contributions = self._feature_contributions_ml(X)

        ETA_PREDICTIONS.labels(stage=stage, model_version=self._version, method="ml").inc()
        return ETAResponse(
            eta_minutes=round(eta, 2),
            confidence_lower_minutes=round(max(0.0, eta - margin), 2),
            confidence_upper_minutes=round(eta + margin, 2),
            stage=stage,
            model_version=self._version,
            feature_contributions=contributions,
            method="ml",
        )

    def _feature_contributions_ml(self, X: np.ndarray) -> Dict[str, float]:
        """Extract SHAP-style feature contributions from LightGBM."""
        try:
            contribs = self._model.predict(X, pred_contrib=True)[0]
            # Last element is the bias term
            result: Dict[str, float] = {}
            for i, col in enumerate(self.FEATURE_COLUMNS):
                if i < len(contribs) - 1:
                    result[col] = round(float(contribs[i]), 4)
            result["bias"] = round(float(contribs[-1]), 4)
            return result
        except Exception:
            logger.debug("Feature contribution extraction failed, returning empty")
            return {}

    def _rule_based_fallback(self, features: ETAFeatures, stage: str) -> ETAResponse:
        """Deterministic rule-based ETA when ML model is unavailable."""
        travel_time = (features.distance_km / max(features.rider_avg_speed_kmh, 1.0)) * 60.0
        travel_time *= features.traffic_factor
        travel_time *= features.weather_multiplier

        prep_time = features.store_prep_time_minutes
        # Queue adds ~2 min per order ahead
        queue_time = features.queue_depth * 2.0
        # Items add ~0.5 min each beyond first
        item_time = max(0, features.item_count - 1) * 0.5

        # Weekend / off-peak adjustments
        if features.is_weekend:
            prep_time *= 1.1

        eta = travel_time + prep_time + queue_time + item_time

        # Stage adjustments – later stages have less uncertainty
        if stage == ETAStage.IN_TRANSIT.value:
            margin = max(1.0, eta * 0.10)
        elif stage == ETAStage.POST_ASSIGN.value:
            margin = max(1.5, eta * 0.15)
        else:
            margin = max(2.0, eta * 0.25)

        contributions = {
            "travel_time": round(travel_time, 4),
            "prep_time": round(prep_time, 4),
            "queue_time": round(queue_time, 4),
            "item_time": round(item_time, 4),
        }

        ETA_PREDICTIONS.labels(stage=stage, model_version=self._version, method="rule_based").inc()
        return ETAResponse(
            eta_minutes=round(max(0.0, eta), 2),
            confidence_lower_minutes=round(max(0.0, eta - margin), 2),
            confidence_upper_minutes=round(eta + margin, 2),
            stage=stage,
            model_version=self._version,
            feature_contributions=contributions,
            method="rule_based",
        )

    # -- SHAP support ---------------------------------------------------------

    def feature_importance(self, method: str = "split") -> Dict[str, float]:
        """Return feature importance from the loaded model."""
        if self._model is None:
            return {}
        try:
            importance = self._model.feature_importance(importance_type=method)
            return {col: float(val) for col, val in zip(self.FEATURE_COLUMNS, importance)}
        except Exception:
            logger.debug("Feature importance extraction failed")
            return {}
