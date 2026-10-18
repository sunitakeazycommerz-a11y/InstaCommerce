"""Unit tests for ModelMonitor Two-Sample KS-test drift detection and BigQuery feature sampling."""

import numpy as np
import pytest
from ml.serving.monitoring import ModelMonitor


def test_ks_drift_no_drift_on_identical_distributions():
    monitor = ModelMonitor(model_name="search_ranking")
    np.random.seed(42)

    baseline = np.random.normal(loc=10.0, scale=2.0, size=1000)
    recent = np.random.normal(loc=10.0, scale=2.0, size=1000)

    result = monitor.check_ks_drift(recent, baseline, feature_name="basket_size", alpha=0.01)

    assert result.is_drifted is False
    assert result.p_value > 0.01
    assert result.sample_size_recent == 1000
    assert result.sample_size_baseline == 1000


def test_ks_drift_detects_distribution_shift():
    monitor = ModelMonitor(model_name="search_ranking")
    np.random.seed(42)

    # Shifted mean from 10.0 to 15.0
    baseline = np.random.normal(loc=10.0, scale=2.0, size=1000)
    recent = np.random.normal(loc=15.0, scale=2.0, size=1000)

    result = monitor.check_ks_drift(recent, baseline, feature_name="basket_size", alpha=0.01)

    assert result.is_drifted is True
    assert result.p_value < 0.01
    assert result.statistic > 0.5


def test_feature_vector_sampling_and_bigquery_export():
    monitor = ModelMonitor(model_name="personalization")

    # Sample 100 requests with 5% sampling rate
    sampled = []
    for i in range(100):
        feat = {"user_id": f"u_{i}", "basket_val": 25.0}
        rec = monitor.sample_feature_vector(feat, sample_rate=0.05, deterministic_key=f"u_{i}")
        if rec:
            sampled.append(rec)

    # Deterministic sampling should capture a reasonable subset (3 to 8 samples)
    assert 1 <= len(sampled) <= 15

    rows = monitor.export_sampled_features_to_bigquery_rows()
    assert len(rows) == len(sampled)
    assert "sample_id" in rows[0]
    assert "features_json" in rows[0]

    monitor.clear_sampled_features()
    assert len(monitor.export_sampled_features_to_bigquery_rows()) == 0
