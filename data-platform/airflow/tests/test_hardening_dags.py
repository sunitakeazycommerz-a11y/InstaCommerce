from __future__ import annotations

import os
import subprocess
from pathlib import Path
from typing import Any, Dict
from unittest.mock import MagicMock

import pytest

from dags import data_quality, historical_backfill, ml_feature_refresh, search_ranking_retraining


class _DummyTI:
    def __init__(self) -> None:
        self.xcoms: Dict[tuple[str, str], Any] = {}

    def xcom_push(self, key: str, value: Any, task_id: str = "current") -> None:
        self.xcoms[(task_id, key)] = value

    def xcom_pull(self, task_ids: str | None = None, key: str = "return_value") -> Any:
        if (task_ids or "", key) in self.xcoms:
            return self.xcoms[(task_ids or "", key)]
        for (_tid, k), val in self.xcoms.items():
            if k == key:
                return val
        return None


def test_historical_backfill_rejects_injection_and_invalid_dates() -> None:
    with pytest.raises(ValueError, match="Unsupported or invalid backfill model_name"):
        historical_backfill.validate_model_name("mart_daily_revenue; rm -rf /")

    with pytest.raises(ValueError, match="Invalid"):
        historical_backfill.validate_iso_date("2026-02-30", "start_date")

    with pytest.raises(ValueError, match="start_date"):
        historical_backfill.validate_backfill_conf({
            "model_name": "mart_daily_revenue",
            "start_date": "2026-04-10",
            "end_date": "2026-04-01",
        })


def test_historical_backfill_runs_subprocess_without_shell_and_checks_bq_parity(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    ti = _DummyTI()
    ti.xcom_push(key="model_name", value="mart_daily_revenue", task_id="prepare_backfill_params")
    ti.xcom_push(key="start_date", value="2026-04-01", task_id="prepare_backfill_params")
    ti.xcom_push(key="end_date", value="2026-04-03", task_id="prepare_backfill_params")

    calls: list[tuple[Any, Any]] = []

    def fake_run(cmd: Any, **kwargs: Any) -> subprocess.CompletedProcess[str]:
        calls.append((cmd, kwargs))
        return subprocess.CompletedProcess(cmd, 0, stdout="ok", stderr="")

    monkeypatch.setattr(historical_backfill.subprocess, "run", fake_run)
    result = historical_backfill._run_dbt_backfill(ti=ti)
    assert result["model_name"] == "mart_daily_revenue"
    assert len(calls) == 1
    cmd, kwargs = calls[0]
    assert kwargs["shell"] is False
    assert cmd[:4] == ["dbt", "run", "--select", "mart_daily_revenue+"]

    row_ok = MagicMock(source_count=1000, mart_count=1000)
    bq_client_ok = MagicMock()
    bq_client_ok.query.return_value.result.return_value = [row_ok]
    monkeypatch.setattr(historical_backfill.bigquery, "Client", lambda project: bq_client_ok)
    monkeypatch.setattr(
        historical_backfill.bigquery,
        "ScalarQueryParameter",
        lambda name, typ, val: (name, typ, val),
        raising=False,
    )
    monkeypatch.setattr(
        historical_backfill.bigquery,
        "QueryJobConfig",
        lambda **kw: kw,
        raising=False,
    )

    parity_ok = historical_backfill._validate_backfill_parity(ti=ti)
    assert parity_ok["parity_confirmed"] is True
    assert parity_ok["parity_ratio"] == pytest.approx(1.0)

    row_bad = MagicMock(source_count=1000, mart_count=900)
    bq_client_bad = MagicMock()
    bq_client_bad.query.return_value.result.return_value = [row_bad]
    monkeypatch.setattr(historical_backfill.bigquery, "Client", lambda project: bq_client_bad)

    with pytest.raises(ValueError, match="parity validation failed"):
        historical_backfill._validate_backfill_parity(ti=ti)


def test_ml_feature_refresh_paths_region_and_traversal_guard() -> None:
    assert ml_feature_refresh.FEATURE_STORE_REGION == "asia-south1"
    expected_features = {
        "user_features": "user_features.sql",
        "product_features": "product_features.sql",
        "rider_features": "rider_features.sql",
        "search_features": "search_features.sql",
        "store_features": "store_features.sql",
    }
    assert ml_feature_refresh.FEATURE_QUERIES == expected_features
    for filename in expected_features.values():
        resolved = ml_feature_refresh.resolve_feature_sql_path(filename)
        assert resolved.is_file(), f"Missing feature SQL file: {resolved}"

    with pytest.raises(ValueError, match="Invalid feature SQL"):
        ml_feature_refresh.resolve_feature_sql_path("../secret.sql")


def test_search_ranking_retraining_dry_run_pipeline(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    assert search_ranking_retraining.REGION == "asia-south1"
    monkeypatch.setenv("SEARCH_RANKING_DRY_RUN", "true")
    monkeypatch.setattr(search_ranking_retraining, "ARTIFACT_BASE_DIR", tmp_path)

    ti = _DummyTI()
    ds = "2026-04-05"
    ext = search_ranking_retraining._extract_training_features(ds=ds, ti=ti)
    assert ext["source_mode"] == "dry_run"
    assert ext["rows"] > 0

    trained = search_ranking_retraining._train_lambdamart(ds=ds, ti=ti)
    assert Path(trained["model_path"]).is_file()

    metrics = search_ranking_retraining._evaluate_candidate(ds=ds, ti=ti)
    assert "ndcg_at_10" in metrics

    gate_branch = search_ranking_retraining._check_promotion_gates(ds=ds, ti=ti)
    assert gate_branch in {"deploy_shadow_mode", "alert_degradation"}

    ti.xcom_push(key="eval_metrics", value={
        "ndcg_at_10": 0.80,
        "baseline_ndcg_at_10": 0.65,
        "ndcg_gain_pct": 23.08,
        "p99_latency_ms": 5.0,
        "mrr": 0.72,
    })
    assert search_ranking_retraining._check_promotion_gates(ds=ds, ti=ti) == "deploy_shadow_mode"


def test_data_quality_domain_suites_align_with_runner() -> None:
    assert set(data_quality.DOMAIN_SUITES.keys()) == {"orders", "payments", "inventory", "users"}
    for domain, cfg in data_quality.DOMAIN_SUITES.items():
        assert cfg["suite_name"] == f"{domain}_validation"
