"""Model monitoring: drift detection, performance tracking, alerting.

Provides Prometheus-based instrumentation for every model predictor and
a Population Stability Index (PSI) drift detector that alerts when the
distribution of incoming features diverges from the training baseline.
"""

import logging
import time
from typing import Any, Dict, List, Optional

import numpy as np  # type: ignore[import-untyped]
from prometheus_client import Counter, Gauge, Histogram  # type: ignore[import-untyped]

logger = logging.getLogger(__name__)

# PSI thresholds (standard industry values)
_PSI_THRESHOLD_WARNING: float = 0.1
_PSI_THRESHOLD_ALERT: float = 0.2

# Feature freshness: max age in seconds before considered stale
_FEATURE_MAX_AGE_S: float = 3_600.0  # 1 hour


class ModelMonitor:
    """Monitors production model health.

    Tracks:
    * Prediction count and latency (Prometheus counter + histogram)
    * Feature drift via Population Stability Index (PSI)
    * Feature freshness (staleness detection)
    * Error rate

    Usage::

        monitor = ModelMonitor(model_name="eta")
        result = predictor.predict(features)
        monitor.record_prediction(features, result, latency_ms=result.latency_ms)
    """

    def __init__(self, model_name: str) -> None:
        self.model_name: str = model_name

        # Prometheus metrics (labels: model, version, outcome)
        self.prediction_count: Counter = Counter(
            "model_prediction_total",
            "Total predictions served",
            labelnames=["model", "version", "is_fallback"],
            namespace="ml",
        )
        self.prediction_latency: Histogram = Histogram(
            "model_prediction_latency_seconds",
            "Prediction latency in seconds",
            labelnames=["model"],
            buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0),
            namespace="ml",
        )
        self.drift_score: Gauge = Gauge(
            "model_drift_psi",
            "Population Stability Index (drift score)",
            labelnames=["model"],
            namespace="ml",
        )
        self.error_rate: Gauge = Gauge(
            "model_error_rate",
            "Fraction of predictions that resulted in errors",
            labelnames=["model"],
            namespace="ml",
        )

        # Internal counters for error-rate calculation
        self._total_predictions: int = 0
        self._error_predictions: int = 0

    # ------------------------------------------------------------------
    # Recording
    # ------------------------------------------------------------------

    def record_prediction(
        self,
        features: Dict[str, Any],
        result: Any,
        latency_ms: float,
        error: bool = False,
    ) -> None:
        """Record a single prediction for monitoring.

        Args:
            features: Raw feature dict (used for drift analysis).
            result: :class:`PredictionResult` from the predictor.
            latency_ms: End-to-end prediction latency in milliseconds.
            error: Whether the prediction raised an error.
        """
        version = getattr(result, "model_version", "unknown")
        is_fallback = str(getattr(result, "is_fallback", False))

        self.prediction_count.labels(
            model=self.model_name,
            version=version,
            is_fallback=is_fallback,
        ).inc()

        self.prediction_latency.labels(model=self.model_name).observe(latency_ms / 1_000.0)

        self._total_predictions += 1
        if error:
            self._error_predictions += 1

        if self._total_predictions > 0:
            rate = self._error_predictions / self._total_predictions
            self.error_rate.labels(model=self.model_name).set(rate)

    def record_error(self) -> None:
        """Record a prediction error (convenience wrapper)."""
        self._total_predictions += 1
        self._error_predictions += 1
        if self._total_predictions > 0:
            rate = self._error_predictions / self._total_predictions
            self.error_rate.labels(model=self.model_name).set(rate)

    # ------------------------------------------------------------------
    # Drift Detection
    # ------------------------------------------------------------------

    def check_drift(
        self,
        recent_features: np.ndarray,
        baseline_features: np.ndarray,
        n_bins: int = 10,
    ) -> float:
        """Calculate Population Stability Index (PSI).

        Compares the distribution of ``recent_features`` against
        ``baseline_features`` (the training distribution).

        Args:
            recent_features: 1-D array of a single feature's recent values.
            baseline_features: 1-D array of the same feature from training.
            n_bins: Number of histogram bins.

        Returns:
            PSI value.  Interpretation:
            * < 0.1  — no significant drift
            * 0.1–0.2 — moderate drift (warning)
            * > 0.2  — significant drift (alert / retrain)
        """
        eps = 1e-6
        breakpoints = np.linspace(
            min(baseline_features.min(), recent_features.min()),
            max(baseline_features.max(), recent_features.max()),
            n_bins + 1,
        )

        baseline_counts = np.histogram(baseline_features, bins=breakpoints)[0].astype(float)
        recent_counts = np.histogram(recent_features, bins=breakpoints)[0].astype(float)

        baseline_pct = baseline_counts / (baseline_counts.sum() + eps) + eps
        recent_pct = recent_counts / (recent_counts.sum() + eps) + eps

        psi = float(np.sum((recent_pct - baseline_pct) * np.log(recent_pct / baseline_pct)))

        self.drift_score.labels(model=self.model_name).set(psi)

        if psi > _PSI_THRESHOLD_ALERT:
            logger.warning(
                "Significant feature drift detected",
                extra={"model": self.model_name, "psi": round(psi, 4)},
            )
        elif psi > _PSI_THRESHOLD_WARNING:
            logger.info(
                "Moderate feature drift detected",
                extra={"model": self.model_name, "psi": round(psi, 4)},
            )

        return psi

    # ------------------------------------------------------------------
    # Freshness
    # ------------------------------------------------------------------

    def check_freshness(
        self,
        feature_timestamps: Dict[str, float],
        max_age_s: float = _FEATURE_MAX_AGE_S,
    ) -> bool:
        """Check whether any features are stale.

        Args:
            feature_timestamps: Mapping of feature name → Unix timestamp of
                when the feature value was last computed.
            max_age_s: Maximum acceptable age in seconds (default 1 hour).

        Returns:
            ``True`` if **all** features are fresh, ``False`` if any are stale.
        """
        now = time.time()
        stale: List[str] = []
        for feature_name, ts in feature_timestamps.items():
            age_s = now - ts
            if age_s > max_age_s:
                stale.append(feature_name)

        if stale:
            logger.warning(
                "Stale features detected",
                extra={
                    "model": self.model_name,
                    "stale_features": stale,
                    "max_age_s": max_age_s,
                },
            )
            return False
        return True

    # ------------------------------------------------------------------
    # Summary
    # ------------------------------------------------------------------

    def summary(self) -> Dict[str, Any]:
        """Return a summary of monitoring state."""
        error_rate_val = (
            self._error_predictions / self._total_predictions
            if self._total_predictions > 0
            else 0.0
        )
        return {
            "model": self.model_name,
            "total_predictions": self._total_predictions,
            "error_predictions": self._error_predictions,
            "error_rate": round(error_rate_val, 4),
        }
