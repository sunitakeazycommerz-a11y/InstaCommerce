"""Unified test suite for ML training, evaluation, feature store, experiments, and serving."""
from pathlib import Path
from typing import cast
from unittest.mock import MagicMock

import numpy as np
import pandas as pd

from ml.demand_forecast.demand_predictor import DemandPredictor
from ml.eval.evaluate import evaluate_model
from ml.experiments.tests.test_experiment_client import (
    test_deterministic_user_variant_assignment,
    test_murmur3_cross_language_parity_with_java_guava,
)
from ml.feature_store.integrity import format_feature_view_name
from ml.serving.model_registry import ABTestRouter, ModelRegistry
from ml.serving.predictor import BasePredictor, PredictionResult
from ml.serving.shadow_mode import ShadowRunner
from ml.serving.tests.test_integrity import (
    test_compute_sha256,
    test_generate_and_verify_checksums,
    test_parse_checksums_ignores_comments,
    test_verify_detects_tampering,
    test_verify_missing_artifact,
    test_verify_missing_checksums_nonstrict,
    test_verify_missing_checksums_strict,
)
from ml.serving.tests.test_ks_drift import (
    test_ks_drift_detects_distribution_shift,
    test_ks_drift_no_drift_on_identical_distributions,
)
from ml.serving.tests.test_shadow_mode import (
    test_shadow_runner_promotion_gate_threshold,
    test_shadow_runner_streams_and_buffers_logs,
)
from ml.train.clv_prediction.train import CLVPredictionTrainer
from ml.train.personalization.tests.test_personalization_leakage_and_cold_start import (
    test_split_train_test_zero_label_leakage,
)
from ml.train.personalization.train import PersonalizationTrainer
from ml.train.search_ranking.tests.test_promotion_gates import (
    test_promotion_gates_pass_when_all_thresholds_met,
)
from ml.train.search_ranking.train import SearchRankingTrainer


def test_clv_rfm_recency_and_gamma_gamma_monetary():
    trainer = CLVPredictionTrainer({})
    df = pd.DataFrame(
        {
            "customer_id": ["c1", "c1", "c1"],
            "order_date": pd.to_datetime(["2026-01-01", "2026-01-11", "2026-01-21"]),
            "order_value": [100.0, 200.0, 300.0],
        }
    )
    obs_end = cast(pd.Timestamp, pd.Timestamp("2026-01-31"))
    rfm = trainer._build_rfm(df, obs_end)
    assert float(cast(float, rfm.loc[0, "recency_days"])) == 20.0
    assert float(cast(float, rfm.loc[0, "days_since_last_order"])) == 10.0

    trainer.bgf_params = {"r": 1.0, "alpha": 10.0, "a": 1.0, "b": 1.0}
    trainer.ggf_params = {"p": 2.0, "q": 3.0, "v": 50.0}
    preds = trainer.predict_clv(rfm, horizon_days=30)
    # x = 2, m_x = 250 -> ((2 * 250 * 2) + (3 - 1) * 50) / (2 * 2 + 3 - 1) = 1100 / 6
    expected_avg = ((2.0 * 250.0 * 2.0) + 2.0 * 50.0) / 6.0
    assert abs(float(cast(float, preds.loc[0, "predicted_avg_value"])) - expected_avg) < 1e-6


def test_search_ranking_sorts_groups_before_extracting_group_sizes():
    config = {
        "features": ["f1"],
        "training": {"group_column": "query_id", "label_column": "relevance_label"},
    }
    trainer = SearchRankingTrainer(config)
    df = pd.DataFrame(
        {
            "query_id": ["q2", "q1", "q2", "q1"],
            "f1": [0.1, 0.9, 0.2, 0.8],
            "relevance_label": [0, 3, 1, 2],
        }
    )
    train_idx = np.array([0, 1, 2, 3])
    val_idx = np.array([0, 1])
    X_train, y_train, g_train, _, _, _ = trainer._build_datasets(df, (train_idx, val_idx))
    assert list(g_train) == [2, 2]
    assert list(y_train.values) == [3, 2, 0, 1]
    assert len(X_train) == 4


def test_personalization_export_writes_vocab_and_model_without_mlflow(tmp_path: Path):
    config = {"hyperparameters": {"embedding_dim": 8, "n_epochs": 2}}
    trainer = PersonalizationTrainer(config)
    df = pd.DataFrame(
        {
            "user_id": ["u1", "u2", "u1"],
            "sku_id": ["i1", "i2", "i2"],
            "interaction_type": ["purchase", "view", "click"],
        }
    )
    trainer.train(df)
    out_dir = tmp_path / "v1"
    trainer.export(str(out_dir))
    assert (out_dir / "feature_vocab.json").exists()
    assert (out_dir / "model.pt").exists()


def test_evaluate_model_respects_task_type_and_excludes_ids():
    test_df = pd.DataFrame(
        {
            "user_id": ["u1", "u2", "u3"],
            "query_id": ["q1", "q1", "q2"],
            "f1": [1.0, 2.0, 3.0],
            "label": [1, 2, 3],
        }
    )
    seen_cols: list[str] = []

    class _RegModel:
        def predict(self, X: pd.DataFrame) -> np.ndarray:
            seen_cols.extend(list(X.columns))
            return np.asarray(X["f1"].values, dtype=float)

    report = evaluate_model(_RegModel(), test_df, task_type="regression")
    assert seen_cols == ["f1"]
    assert "rmse" in report.metrics
    assert "mae" in report.metrics


def test_model_registry_loads_version_and_ab_test_router(tmp_path: Path):
    v2_dir = tmp_path / "v2"
    v2_dir.mkdir(parents=True)
    loaded_versions: list[str] = []

    class _VersionedPredictor(BasePredictor):
        def _load_model(self, version=None):
            loaded_versions.append(str(version))
            self._model = object()

        def predict(self, features):
            return PredictionResult({}, self._version, self.model_name, 1.0)

        def rule_based_fallback(self, features):
            return self.predict(features)

    pred = _VersionedPredictor("eta", str(tmp_path))
    registry = ModelRegistry(str(tmp_path))
    registry.register(pred)

    got = registry.get("eta", version="v2")
    assert got._version == "v2"
    assert loaded_versions == ["v2"]

    router = ABTestRouter({"eta": {"v1": 50.0, "v2": 50.0}})
    v_first = router.route("user-42", "eta")
    assert v_first in ("v1", "v2")
    assert router.route("user-42", "eta") == v_first


def test_shadow_runner_guards_zero_pv_and_checks_primary_metrics():
    runner = ShadowRunner()
    prod = PredictionResult(
        output={"ndcg@10": 0.0, "rmse": 1.0},
        model_version="v1",
        model_name="ranker",
        latency_ms=5.0,
    )
    shadow = MagicMock()
    shadow.model_name = "ranker"
    shadow.predict.return_value = PredictionResult(
        output={"ndcg@10": 0.5, "rmse": 1.0},
        model_version="v2",
        model_name="ranker",
        latency_ms=6.0,
    )
    comp = runner.run_shadow(prod, shadow, {})
    assert comp is not None
    assert comp["agrees"] is False
    assert comp["score_delta"] == 0.5


def test_feature_view_name_and_demand_predictor_hourly_multiplier():
    assert format_feature_view_name("ml_user_features", 1) == "ml_user_features_v1"
    assert format_feature_view_name("user_features", 1) == "ml_user_features_v1"

    predictor = DemandPredictor()
    res = predictor.rule_based_fallback({"zone_id": "z1", "category": "dairy", "hour_of_day": 19, "horizon_hours": 2})
    assert res.output["predicted_demand"] == 50.0 * 1.8


__all__ = [
    "test_compute_sha256",
    "test_deterministic_user_variant_assignment",
    "test_generate_and_verify_checksums",
    "test_ks_drift_detects_distribution_shift",
    "test_ks_drift_no_drift_on_identical_distributions",
    "test_murmur3_cross_language_parity_with_java_guava",
    "test_parse_checksums_ignores_comments",
    "test_promotion_gates_pass_when_all_thresholds_met",
    "test_shadow_runner_promotion_gate_threshold",
    "test_shadow_runner_streams_and_buffers_logs",
    "test_split_train_test_zero_label_leakage",
    "test_verify_detects_tampering",
    "test_verify_missing_artifact",
    "test_verify_missing_checksums_nonstrict",
    "test_verify_missing_checksums_strict",
]
