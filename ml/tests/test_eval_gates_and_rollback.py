from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, Optional

import numpy as np
import pandas as pd
import pytest

from ml.eval.evaluate import evaluate_model
from ml.eval.metrics import evaluate_promotion_gates
from ml.serving.model_registry import ModelRegistry
from ml.serving.predictor import BasePredictor, PredictionResult


def test_promotion_gates_fail_closed_on_missing_metric_and_alias_mae_min() -> None:
    passed_missing, failures_missing = evaluate_promotion_gates(
        {"rmse": 0.1},
        {"min_ndcg_at_10": 0.65},
    )
    assert passed_missing is False
    assert any("ndcg_at_10" in msg for msg in failures_missing)

    passed_alias, failures_alias = evaluate_promotion_gates(
        {"mae": 1.2, "within_2min_pct": 0.90},
        {"max_mae_min": 1.5, "min_within_2min_pct": 0.85},
    )
    assert passed_alias is True
    assert failures_alias == []


def test_evaluate_model_computes_eta_fraud_and_ranking_gate_metrics() -> None:
    class _EtaModel:
        def predict(self, X: pd.DataFrame) -> np.ndarray:
            return np.asarray(X["pred"].values, dtype=float)

    eta_df = pd.DataFrame(
        {
            "pred": [10.0, 15.0, 20.0, 25.0],
            "label": [10.5, 14.5, 21.0, 24.5],
        }
    )
    eta_report = evaluate_model(
        _EtaModel(),
        eta_df,
        gates_config={"promotion_gates": {"max_mae_min": 1.0, "min_within_2min_pct": 0.95}},
        model_name="eta_prediction",
        task_type="regression",
    )
    assert eta_report.gates_passed is True
    assert eta_report.metrics["within_2min_pct"] == 1.0
    assert eta_report.metrics["mae_min"] <= 1.0
    assert eta_report.metrics["correlation"] > 0.99

    class _FraudModel:
        def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
            p = np.asarray(X["prob"].values, dtype=float)
            return np.column_stack([1.0 - p, p])

    fraud_df = pd.DataFrame(
        {
            "prob": [0.95, 0.92, 0.10, 0.05, 0.02, 0.01],
            "is_fraud": [1, 1, 0, 0, 0, 0],
        }
    )
    fraud_report = evaluate_model(
        _FraudModel(),
        fraud_df,
        gates_config={
            "promotion_gates": {
                "min_auc_roc": 0.95,
                "min_precision_at_95_recall": 0.90,
                "max_false_positive_rate": 0.05,
            }
        },
        model_name="fraud_detection",
        task_type="classification",
    )
    assert fraud_report.gates_passed is True
    assert fraud_report.metrics["false_positive_rate"] == 0.0
    assert fraud_report.metrics["precision_at_95_recall"] >= 0.95

    class _RankModel:
        def predict(self, X: pd.DataFrame) -> np.ndarray:
            return np.asarray(X["score"].values, dtype=float)

    rank_df = pd.DataFrame(
        {
            "query_id": ["q1", "q1", "q2", "q2"],
            "score": [0.9, 0.2, 0.8, 0.1],
            "relevance_label": [1, 0, 1, 0],
        }
    )
    rank_report = evaluate_model(
        _RankModel(),
        rank_df,
        gates_config={"promotion_gates": {"min_ndcg_at_10": 0.90, "min_hit_at_10": 0.90}},
        model_name="search_ranking",
        task_type="ranking",
    )
    assert rank_report.gates_passed is True
    assert rank_report.metrics["ndcg_at_10"] == pytest.approx(1.0)
    assert rank_report.metrics["hit_at_10"] == pytest.approx(1.0)


def test_model_registry_tracks_and_executes_rollback(tmp_path: Path) -> None:
    for ver in ("v1", "v2"):
        (tmp_path / ver).mkdir(parents=True)

    loaded: list[str] = []

    class _Predictor(BasePredictor):
        def _load_model(self, version: Optional[str] = None) -> None:
            loaded.append(str(version))
            self._model = {"version": version}

        def predict(self, features: Dict[str, Any]) -> PredictionResult:
            return PredictionResult({}, self._version, self.model_name, 1.0)

        def rule_based_fallback(self, features: Dict[str, Any]) -> PredictionResult:
            return self.predict(features)

    registry = ModelRegistry(str(tmp_path))
    pred = _Predictor("eta", str(tmp_path))
    pred._version = "v1"
    registry.register(pred)

    with pytest.raises(ValueError, match="No previous version"):
        registry.rollback("eta")

    registry.get("eta", version="v2")
    assert registry._models["eta"]._version == "v2"
    assert registry.status()["models"]["eta"]["previous_version"] == "v1"

    rolled = registry.rollback("eta")
    assert rolled._version == "v1"
    assert loaded[-2:] == ["v2", "v1"]
