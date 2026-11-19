from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Dict

import pytest

from app.core.config import ModelConfig
from app.core.guardrails import (
    OOD_FEATURE_TOTAL,
    SLA_BREACH_TOTAL,
    SLABudgetExceededError,
    assess_feature_health,
    enforce_async_sla,
    enforce_sync_sla,
    is_cold_start_context,
    score_cold_start_fallback,
)
from app.core.inference import perform_inference
from app.core.shadow import ShadowRunner


def test_online_feature_drift_ood_zscore_and_missing_ratio() -> None:
    report_ok = assess_feature_health(
        "eta",
        {"distance_km": 3.5, "item_count": 5.0, "traffic_factor": 1.1},
    )
    assert report_ok.drift_detected is False
    assert report_ok.missing_ratio == 0.0
    assert report_ok.ood_features == {}

    before_ood = OOD_FEATURE_TOTAL.labels(model="eta", feature="distance_km")._value.get()
    report_bad = assess_feature_health(
        "eta",
        {"distance_km": 150.0, "item_count": float("nan")},
    )
    after_ood = OOD_FEATURE_TOTAL.labels(model="eta", feature="distance_km")._value.get()

    assert report_bad.drift_detected is True
    assert report_bad.missing_threshold_exceeded is True
    assert report_bad.missing_count == 2
    assert report_bad.missing_ratio > 0.35
    assert "distance_km" in report_bad.ood_features
    assert report_bad.max_z_score > 4.0
    assert after_ood == before_ood + 1.0


def test_cold_start_fallback_scoring_and_confidence_intervals() -> None:
    is_cold, reason = is_cold_start_context(
        "ranking",
        {},
        missing_ratio=1.0,
        entity_metadata={"is_new_user": True},
    )
    assert is_cold is True
    assert reason == "new_user"

    fallback = score_cold_start_fallback("ranking", reason="new_user")
    assert fallback.is_cold_start is True
    assert 0.0 <= fallback.confidence_lower <= fallback.score <= fallback.confidence_upper <= 1.0

    cfg = ModelConfig(
        version="eta-linear-v1",
        bias=4.5,
        weights={"distance_km": 2.3, "item_count": 0.6, "traffic_factor": 3.1},
    )
    out = perform_inference("eta", {}, cfg, entity_metadata={"is_new_item": True})
    assert out["is_cold_start"] is True
    assert out["cold_start_reason"] == "new_item"
    assert out["confidence_lower"] <= out["eta_minutes"] <= out["confidence_upper"]

    normal = perform_inference(
        "eta",
        {"distance_km": 3.0, "item_count": 4.0, "traffic_factor": 1.2},
        cfg,
    )
    assert normal["is_cold_start"] is False
    assert normal["confidence_lower"] < normal["eta_minutes"] < normal["confidence_upper"]
    assert normal["drift_detected"] is False


def test_sync_and_async_sla_budget_enforcement() -> None:
    def slow_predictor() -> Dict[str, Any]:
        time.sleep(0.06)
        return {"eta_minutes": 15.0}

    before = SLA_BREACH_TOTAL.labels(model="eta", action="fallback")._value.get()
    out, breached, elapsed_ms = enforce_sync_sla(
        "eta",
        slow_predictor,
        budget_ms=10.0,
        strict=True,
        model_version="eta-v1",
        fallback_on_timeout=True,
    )
    after = SLA_BREACH_TOTAL.labels(model="eta", action="fallback")._value.get()

    assert breached is True
    assert out["sla_breached"] is True
    assert out["is_cold_start"] is True
    assert out["cold_start_reason"] == "sla_timeout"
    assert elapsed_ms >= 9.0
    assert after == before + 1.0

    with pytest.raises(SLABudgetExceededError):
        enforce_sync_sla(
            "fraud",
            slow_predictor,
            budget_ms=10.0,
            strict=True,
            fallback_on_timeout=False,
        )


@pytest.mark.asyncio
async def test_async_sla_timeout_and_shadow_comparison_logging(caplog: pytest.LogCaptureFixture) -> None:
    async def slow_async() -> Dict[str, Any]:
        await asyncio.sleep(0.06)
        return {"ranking_score": 0.9}

    out, breached, _ = await enforce_async_sla(
        "ranking",
        slow_async(),
        budget_ms=10.0,
        model_version="ranking-v1",
        fallback_on_timeout=True,
    )
    assert breached is True
    assert out["sla_breached"] is True
    assert 0.0 <= out["ranking_score"] <= 1.0

    runner = ShadowRunner(tolerance=0.1, timeout_seconds=0.05)

    def fast_shadow(val: float) -> Dict[str, Any]:
        return {"ranking_score": val}

    with caplog.at_level(logging.INFO, logger="ai_inference.shadow"):
        task1 = runner.dispatch("ranking", "shadow-v2", fast_shadow, {"ranking_score": 0.80}, 0.96)
        assert task1 is not None
        await task1

        async def hung_shadow() -> Dict[str, Any]:
            await asyncio.sleep(0.2)
            return {"ranking_score": 0.5}

        task2 = runner.dispatch("ranking", "shadow-v2", hung_shadow, {"ranking_score": 0.80})
        assert task2 is not None
        await task2

    assert runner.stats["diverged"] == 1
    assert runner.stats["errors"] == 1
    assert len(runner.recent_comparisons) == 1
    comp = runner.recent_comparisons[0]
    assert comp["model"] == "ranking"
    assert comp["shadow_version"] == "shadow-v2"
    assert comp["agreed"] is False
    assert comp["delta"] == pytest.approx(0.16, abs=1e-4)
    assert any(rec.message == "shadow.comparison" for rec in caplog.records)
