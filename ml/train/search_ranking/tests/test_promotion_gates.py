"""Unit tests for Search Ranking automated promotion gates and degradation alerting."""

import pytest
from ml.train.search_ranking.train import _check_promotion_gates


def test_promotion_gates_pass_when_all_thresholds_met():
    metrics = {
        "ndcg_at_10": 0.72,
        "map_at_10": 0.65,
        "mrr": 0.80,
        "p99_latency_ms": 11.2,
    }
    gates = {
        "min_ndcg_at_10": 0.65,
        "baseline_ndcg_at_10": 0.70,
        "min_ndcg_gain_pct": 1.5,  # (0.72 - 0.70) / 0.70 = 2.86% > 1.5%
        "max_p99_latency_ms": 15.0,
        "min_shadow_agreement": 0.92,
    }
    shadow_metrics = {"shadow_agreement": 0.94}

    passed, reasons = _check_promotion_gates(metrics, gates, shadow_metrics)
    assert passed is True
    assert len(reasons) == 0


def test_promotion_gates_fail_on_insufficient_ndcg_gain():
    metrics = {
        "ndcg_at_10": 0.705,  # gain is 0.71% (< 1.5%)
        "p99_latency_ms": 10.0,
    }
    gates = {
        "min_ndcg_at_10": 0.65,
        "baseline_ndcg_at_10": 0.70,
        "min_ndcg_gain_pct": 1.5,
        "max_p99_latency_ms": 15.0,
    }

    passed, reasons = _check_promotion_gates(metrics, gates)
    assert passed is False
    assert any("gain" in r for r in reasons)


def test_promotion_gates_fail_on_high_p99_latency():
    metrics = {
        "ndcg_at_10": 0.75,
        "p99_latency_ms": 18.5,  # > 15ms
    }
    gates = {
        "min_ndcg_at_10": 0.65,
        "baseline_ndcg_at_10": 0.70,
        "min_ndcg_gain_pct": 1.5,
        "max_p99_latency_ms": 15.0,
    }

    passed, reasons = _check_promotion_gates(metrics, gates)
    assert passed is False
    assert any("p99 latency" in r for r in reasons)


def test_promotion_gates_fail_on_low_shadow_agreement():
    metrics = {
        "ndcg_at_10": 0.75,
        "p99_latency_ms": 12.0,
    }
    gates = {
        "min_ndcg_at_10": 0.65,
        "baseline_ndcg_at_10": 0.70,
        "min_ndcg_gain_pct": 1.5,
        "max_p99_latency_ms": 15.0,
        "min_shadow_agreement": 0.92,
    }
    shadow_metrics = {"shadow_agreement": 0.89}  # < 92%

    passed, reasons = _check_promotion_gates(metrics, gates, shadow_metrics)
    assert passed is False
    assert any("Shadow agreement" in r for r in reasons)
