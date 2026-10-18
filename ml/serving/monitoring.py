"""Model monitoring: drift detection, performance tracking, alerting.

Provides Prometheus-based instrumentation for every model predictor,
Population Stability Index (PSI), and Two-Sample Kolmogorov-Smirnov (KS)
tests that alert when the distribution of incoming inference features diverges
from the training baseline. Includes 5% inference feature vector sampling to BigQuery.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import logging
import random
import time
import uuid
from dataclasses import asdict, dataclass
from typing import Any, Dict, List, Optional

import numpy as np  # type: ignore[import-untyped]
from prometheus_client import REGISTRY, Counter, Gauge, Histogram  # type: ignore[import-untyped]

logger = logging.getLogger(__name__)

# PSI thresholds (standard industry values)
_PSI_THRESHOLD_WARNING: float = 0.1
_PSI_THRESHOLD_ALERT: float = 0.2

# KS-test significance threshold (p < 0.01 indicates significant covariate shift)
_KS_ALPHA_DEFAULT: float = 0.01

# Feature freshness: max age in seconds before considered stale
_FEATURE_MAX_AGE_S: float = 3_600.0  # 1 hour


@dataclass
class KSDriftResult:
    """Result of Two-Sample Kolmogorov-Smirnov test for feature drift."""
    feature_name: str
    statistic: float
    p_value: float
    is_drifted: bool
    sample_size_recent: int
    sample_size_baseline: int
    alpha: float = _KS_ALPHA_DEFAULT

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class ModelMonitor:
    """Monitors production model health.

    Tracks:
    * Prediction count and latency (Prometheus counter + histogram)
    * Feature drift via Population Stability Index (PSI)
    * Feature covariate shift via Two-Sample Kolmogorov-Smirnov (KS) test
    * 5% feature vector sampling to BigQuery for daily drift auditing
    * Feature freshness (staleness detection)
    * Error rate

    Usage::

        monitor = ModelMonitor(model_name="eta")
        result = predictor.predict(features)
        monitor.record_prediction(features, result, latency_ms=result.latency_ms)
        drift = monitor.check_ks_drift(recent_vals, baseline_vals, "basket_size")
    """

    def __init__(self, model_name: str) -> None:
        self.model_name: str = model_name

        # Safe metric registration handling registry re-use
        def _get_or_create(metric_cls, name, doc, labelnames, **kwargs):
            full_name = f"ml_{name}"
            if full_name in REGISTRY._names_to_collectors:
                return REGISTRY._names_to_collectors[full_name]
            return metric_cls(name, doc, labelnames=labelnames, namespace="ml", **kwargs)

        self.prediction_count: Counter = _get_or_create(
            Counter,
            "model_prediction_total",
            "Total predictions served",
            ["model", "version", "is_fallback"],
        )
        self.prediction_latency: Histogram = _get_or_create(
            Histogram,
            "model_prediction_latency_seconds",
            "Prediction latency in seconds",
            ["model"],
            buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0),
        )
        self.drift_score: Gauge = _get_or_create(
            Gauge,
            "model_drift_psi",
            "Population Stability Index (drift score)",
            ["model"],
        )
        self.ks_statistic: Gauge = _get_or_create(
            Gauge,
            "model_drift_ks_statistic",
            "Kolmogorov-Smirnov test D-statistic",
            ["model", "feature"],
        )
        self.ks_pvalue: Gauge = _get_or_create(
            Gauge,
            "model_drift_ks_pvalue",
            "Kolmogorov-Smirnov test p-value",
            ["model", "feature"],
        )
        self.error_rate: Gauge = _get_or_create(
            Gauge,
            "model_error_rate",
            "Fraction of predictions that resulted in errors",
            ["model"],
        )

        # Internal state
        self._total_predictions: int = 0
        self._error_predictions: int = 0
        self._sampled_feature_buffer: List[Dict[str, Any]] = []

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
        """Record a single prediction for monitoring."""
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
        """Record a prediction error."""
        self._total_predictions += 1
        self._error_predictions += 1
        if self._total_predictions > 0:
            rate = self._error_predictions / self._total_predictions
            self.error_rate.labels(model=self.model_name).set(rate)

    # ------------------------------------------------------------------
    # Feature Sampling for BigQuery Drift Auditing
    # ------------------------------------------------------------------

    def sample_feature_vector(
        self,
        features: Dict[str, Any],
        sample_rate: float = 0.05,
        deterministic_key: Optional[str] = None,
    ) -> Optional[Dict[str, Any]]:
        """Sample 5% of production inference feature vectors for BigQuery."""
        if deterministic_key:
            h = int(hashlib.md5(deterministic_key.encode("utf-8")).hexdigest()[:8], 16)
            should_sample = (h % 1000) < int(sample_rate * 1000)
        else:
            should_sample = random.random() < sample_rate

        if not should_sample:
            return None

        record = {
            "sample_id": str(uuid.uuid4()),
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "model_name": self.model_name,
            "features": features,
        }
        self._sampled_feature_buffer.append(record)
        return record

    def export_sampled_features_to_bigquery_rows(self) -> List[Dict[str, Any]]:
        """Export sampled feature records formatted for BigQuery."""
        return [
            {
                "sample_id": r["sample_id"],
                "timestamp": r["timestamp"],
                "model_name": r["model_name"],
                "features_json": json.dumps(r["features"]),
            }
            for r in self._sampled_feature_buffer
        ]

    def clear_sampled_features(self) -> None:
        """Clear the sampled feature vector buffer."""
        self._sampled_feature_buffer.clear()

    # ------------------------------------------------------------------
    # Kolmogorov-Smirnov (KS) Test Drift Detection
    # ------------------------------------------------------------------

    def check_ks_drift(
        self,
        recent_features: np.ndarray,
        baseline_features: np.ndarray,
        feature_name: str = "feature",
        alpha: float = _KS_ALPHA_DEFAULT,
    ) -> KSDriftResult:
        """Calculate Two-Sample Kolmogorov-Smirnov (KS) test for feature drift.

        Compares recent feature distribution against training baseline.
        Alerts when p-value < alpha (default 0.01) indicating significant covariate shift.
        """
        import scipy.stats as stats

        r_clean = np.asarray(recent_features, dtype=float)
        b_clean = np.asarray(baseline_features, dtype=float)
        r_clean = r_clean[~np.isnan(r_clean)]
        b_clean = b_clean[~np.isnan(b_clean)]

        res = stats.ks_2samp(r_clean, b_clean)
        d_stat = float(res.statistic)
        p_val = float(res.pvalue)
        is_drifted = bool(p_val < alpha)

        self.ks_statistic.labels(model=self.model_name, feature=feature_name).set(d_stat)
        self.ks_pvalue.labels(model=self.model_name, feature=feature_name).set(p_val)

        if is_drifted:
            logger.warning(
                "SIGNIFICANT COVARIATE SHIFT DETECTED via KS-test (feature=%s, D=%.4f, p=%.4e < %.2f)",
                feature_name,
                d_stat,
                p_val,
                alpha,
            )
        else:
            logger.info(
                "Feature distribution '%s' stable (KS-test: D=%.4f, p=%.4f >= %.2f)",
                feature_name,
                d_stat,
                p_val,
                alpha,
            )

        return KSDriftResult(
            feature_name=feature_name,
            statistic=d_stat,
            p_value=p_val,
            is_drifted=is_drifted,
            sample_size_recent=len(r_clean),
            sample_size_baseline=len(b_clean),
            alpha=alpha,
        )

    # ------------------------------------------------------------------
    # PSI Drift Detection
    # ------------------------------------------------------------------

    def check_drift(
        self,
        recent_features: np.ndarray,
        baseline_features: np.ndarray,
        n_bins: int = 10,
    ) -> float:
        """Calculate Population Stability Index (PSI)."""
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
                "Significant feature drift detected via PSI",
                extra={"model": self.model_name, "psi": round(psi, 4)},
            )
        elif psi > _PSI_THRESHOLD_WARNING:
            logger.info(
                "Moderate feature drift detected via PSI",
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
        """Check whether any features are stale."""
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
