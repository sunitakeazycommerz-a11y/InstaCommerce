"""Regression tests for optional MLflow training runs and structured error logging."""

from __future__ import annotations

from contextlib import contextmanager
import logging
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock

import numpy as np
import pandas as pd
import pytest
import yaml

from ml.serving.predictor import BasePredictor, PredictionResult
from ml.serving.shadow_mode import ShadowRunner
from ml.train.clv_prediction import train as clv_train
from ml.train.demand_forecast import train as demand_train
from ml.train.eta_prediction import train as eta_train
from ml.train.fraud_detection import train as fraud_train
from ml.train.personalization import train as pers_train
from ml.train.search_ranking import train as rank_train


def _write_config(path: Path, payload: dict[str, Any]) -> Path:
    path.write_text(yaml.safe_dump(payload), encoding="utf-8")
    return path


def _stub_demand_trainer(events: list[str], export_dir: Path) -> type[Any]:
    class _StubDemandTrainer:
        def __init__(self, config: dict[str, Any]) -> None:
            self.config = config
            self.models = {"s1__sku1": object()}

        def load_data(self, query: str) -> pd.DataFrame:
            del query
            events.append("load")
            return pd.DataFrame(
                {
                    "store_id": ["s1"] * 4,
                    "sku_id": ["sku1"] * 4,
                    "ds": pd.to_datetime(["2026-01-01", "2026-01-02", "2026-01-10", "2026-01-11"]),
                    "y": [10.0, 12.0, 11.0, 13.0],
                }
            )

        def train(self, df: pd.DataFrame) -> dict[str, Any]:
            del df
            events.append("train")
            return self.models

        def evaluate(self, df: pd.DataFrame) -> dict[str, float]:
            del df
            events.append("evaluate")
            return {"mape": 0.05, "accuracy_pct": 95.0}

        def export(self, path: str) -> None:
            events.append("export")
            Path(path).mkdir(parents=True, exist_ok=True)
            (Path(path) / "manifest.json").write_text("{}", encoding="utf-8")
            assert Path(path).parent == export_dir

    return _StubDemandTrainer


def _stub_eta_trainer(events: list[str]) -> type[Any]:
    class _StubETATrainer:
        def __init__(self, config: dict[str, Any]) -> None:
            self.config = config

        def load_data(self, query: str) -> pd.DataFrame:
            del query
            events.append("load")
            return pd.DataFrame({"stage": ["pre_order"] * 4, "f1": [1, 2, 3, 4], "actual_eta_min": [10.0, 11.0, 12.0, 13.0]})

        def train(self, df: pd.DataFrame) -> object:
            del df
            events.append("train")
            return object()

        def evaluate(self, df: pd.DataFrame) -> dict[str, Any]:
            del df
            events.append("evaluate")
            return {"mae_min": 1.1, "within_2min_pct": 0.9, "stage": "pre_order"}

        def feature_importance(self) -> dict[str, float]:
            return {"f1": 1.0}

        def export(self, path: str) -> None:
            events.append("export")
            out = Path(path)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text("lgb", encoding="utf-8")

    return _StubETATrainer


def _stub_fraud_trainer(events: list[str]) -> type[Any]:
    class _StubFraudTrainer:
        def __init__(self, config: dict[str, Any]) -> None:
            self.config = config

        def load_data(self, query: str) -> pd.DataFrame:
            del query
            events.append("load")
            return pd.DataFrame({"f1": np.linspace(0, 1, 10), "is_fraud": [0, 1] * 5})

        def train(self, df: pd.DataFrame) -> object:
            del df
            events.append("train")
            return object()

        def calibrate(self, df: pd.DataFrame) -> None:
            del df
            events.append("calibrate")

        def tune_threshold(self, df: pd.DataFrame, target_recall: float = 0.95) -> float:
            del df, target_recall
            events.append("tune")
            return 0.5

        def evaluate(self, df: pd.DataFrame) -> dict[str, float]:
            del df
            events.append("evaluate")
            return {"auc_roc": 0.99, "precision_at_95_recall": 0.91}

        def feature_importance(self) -> dict[str, float]:
            return {"f1": 1.0}

        def export(self, path: str) -> None:
            events.append("export")
            out = Path(path)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text("xgb", encoding="utf-8")
            (out.parent / "metadata.json").write_text('{"optimal_threshold": 0.5}', encoding="utf-8")

    return _StubFraudTrainer


def _stub_clv_trainer(events: list[str]) -> type[Any]:
    class _StubCLVTrainer:
        def __init__(self, config: dict[str, Any]) -> None:
            self.config = config
            self.bgf_params = {"r": 1.0, "alpha": 2.0, "a": 1.0, "b": 1.0}
            self.ggf_params = {"p": 1.0, "q": 2.0, "v": 10.0}

        def load_data(self, query: str) -> pd.DataFrame:
            del query
            events.append("load")
            return pd.DataFrame(
                {
                    "customer_id": ["c1", "c1", "c2", "c2"],
                    "order_date": pd.to_datetime(["2026-01-01", "2026-01-05", "2026-05-01", "2026-05-05"]),
                    "order_value": [10.0, 20.0, 30.0, 40.0],
                }
            )

        def train(self, df: pd.DataFrame) -> None:
            del df
            events.append("train")

        def evaluate(self, train_df: pd.DataFrame, test_df: pd.DataFrame) -> dict[str, float]:
            del train_df, test_df
            events.append("evaluate")
            return {"mae": 10.0, "correlation": 0.8}

        def _build_rfm(self, df: pd.DataFrame, observation_end: pd.Timestamp) -> pd.DataFrame:
            del df, observation_end
            return pd.DataFrame({"customer_id": ["c1"]})

        def predict_clv(self, rfm: pd.DataFrame, horizon_days: int = 365) -> pd.DataFrame:
            del rfm, horizon_days
            return pd.DataFrame({"customer_id": ["c1"], "segment": ["bronze"]})

        def export(self, path: str) -> None:
            events.append("export")
            out = Path(path)
            out.mkdir(parents=True, exist_ok=True)
            (out / "model_params.json").write_text("{}", encoding="utf-8")

    return _StubCLVTrainer


def _stub_pers_trainer(events: list[str]) -> type[Any]:
    class _StubPersTrainer:
        def __init__(self, config: dict[str, Any]) -> None:
            self.config = config

        def load_data(self, query: str) -> pd.DataFrame:
            del query
            events.append("load")
            return pd.DataFrame(
                {
                    "user_id": ["u1", "u2"],
                    "sku_id": ["i1", "i2"],
                    "interaction_type": ["purchase", "view"],
                    "timestamp": pd.to_datetime(["2026-01-01", "2026-01-15"]),
                }
            )

        @staticmethod
        def split_train_test(df: pd.DataFrame, cutoff_days: int = 7) -> tuple[pd.DataFrame, pd.DataFrame]:
            del cutoff_days
            return df.iloc[:1], df.iloc[1:]

        def train(self, df: pd.DataFrame) -> None:
            del df
            events.append("train")

        def evaluate(self, df: pd.DataFrame) -> dict[str, float]:
            del df
            events.append("evaluate")
            return {"hit_at_10": 0.6, "ndcg_at_10": 0.5}

        def export(self, path: str) -> None:
            events.append("export")
            out = Path(path)
            out.mkdir(parents=True, exist_ok=True)
            (out / "feature_vocab.json").write_text("{}", encoding="utf-8")

    return _StubPersTrainer


def _stub_rank_trainer(events: list[str]) -> type[Any]:
    class _StubRankTrainer:
        def __init__(self, config: dict[str, Any]) -> None:
            self.config = config

        def load_data(self, query: str) -> pd.DataFrame:
            del query
            events.append("load")
            return pd.DataFrame({"query_id": ["q1", "q2", "q3", "q4"], "f1": [1, 2, 3, 4], "relevance_label": [1, 0, 1, 0]})

        def train(self, df: pd.DataFrame) -> object:
            del df
            events.append("train")
            return object()

        def evaluate(self, df: pd.DataFrame) -> dict[str, float]:
            del df
            events.append("evaluate")
            return {"ndcg_at_10": 0.72, "p99_latency_ms": 9.0}

        def feature_importance(self) -> dict[str, float]:
            return {"f1": 1.0}

        def export(self, path: str) -> None:
            events.append("export")
            out = Path(path)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text("lgb", encoding="utf-8")

    return _StubRankTrainer


@pytest.mark.parametrize(
    ("module", "trainer_attr", "cfg_extra"),
    [
        (
            demand_train,
            "DemandForecastTrainer",
            {"model_name": "demand-forecast", "promotion_gates": {"max_mape": 0.1, "min_accuracy_pct": 90.0}},
        ),
        (
            eta_train,
            "ETAPredictionTrainer",
            {"model_name": "eta-prediction", "stage": "pre_order", "promotion_gates": {"max_mae_min": 1.5, "min_within_2min_pct": 0.85}},
        ),
        (
            fraud_train,
            "FraudDetectionTrainer",
            {"model_name": "fraud-detection", "training": {"query": "SELECT 1", "label_column": "is_fraud"}, "promotion_gates": {"min_auc_roc": 0.9, "min_precision_at_95_recall": 0.8}},
        ),
        (
            clv_train,
            "CLVPredictionTrainer",
            {"model_name": "clv-prediction", "promotion_gates": {"max_mae": 50.0, "min_correlation": 0.5}},
        ),
        (
            pers_train,
            "PersonalizationTrainer",
            {"model_name": "personalization", "promotion_gates": {"min_hit_at_10": 0.3, "min_ndcg_at_10": 0.2}},
        ),
        (
            rank_train,
            "SearchRankingTrainer",
            {"model_name": "search-ranking", "training": {"query": "SELECT 1", "group_column": "query_id"}, "promotion_gates": {"min_ndcg_at_10": 0.65, "baseline_ndcg_at_10": 0.7, "min_ndcg_gain_pct": 1.5, "auto_promote": False}},
        ),
    ],
)
def test_training_main_succeeds_when_mlflow_is_none(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    module: Any,
    trainer_attr: str,
    cfg_extra: dict[str, Any],
) -> None:
    events: list[str] = []
    out_dir = tmp_path / "artifacts"
    cfg: dict[str, Any] = {
        "version": "v1",
        "hyperparameters": {"lr": 0.05},
        "training": {"query": "SELECT 1"},
    }
    for key, value in cfg_extra.items():
        if key == "training" and isinstance(value, dict):
            cfg["training"] = {**cfg["training"], **value}
        else:
            cfg[key] = value
    cfg_path = _write_config(tmp_path / "config.yaml", cfg)

    stub_map = {
        "DemandForecastTrainer": _stub_demand_trainer(events, out_dir / "v1"),
        "ETAPredictionTrainer": _stub_eta_trainer(events),
        "FraudDetectionTrainer": _stub_fraud_trainer(events),
        "CLVPredictionTrainer": _stub_clv_trainer(events),
        "PersonalizationTrainer": _stub_pers_trainer(events),
        "SearchRankingTrainer": _stub_rank_trainer(events),
    }

    monkeypatch.setattr(module, "mlflow", None)
    monkeypatch.setattr(module, trainer_attr, stub_map[trainer_attr])
    monkeypatch.setattr(pd.DataFrame, "to_parquet", lambda self, p, **_kw: Path(p).write_text("parquet", encoding="utf-8"))
    monkeypatch.setattr("sys.argv", ["train.py", "--config", str(cfg_path), "--output-dir", str(out_dir)])

    module.main()

    assert {"load", "train", "evaluate", "export"} <= set(events)


def test_training_and_export_execute_outside_mlflow_start_run(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    events: list[str] = []
    out_dir = tmp_path / "artifacts"
    cfg_path = _write_config(
        tmp_path / "demand.yaml",
        {
            "model_name": "demand-forecast",
            "version": "v1",
            "hyperparameters": {"changepoint_prior_scale": 0.05},
            "training": {"query": "SELECT 1", "test_days": 7},
            "promotion_gates": {"max_mape": 0.08, "min_accuracy_pct": 92.0},
        },
    )

    class _RecordingMlflow:
        def set_experiment(self, experiment_name: str | None = None, experiment_id: str | None = None) -> None:
            del experiment_id
            events.append(f"set_experiment:{experiment_name}")

        @contextmanager
        def start_run(self, *args: Any, **kwargs: Any):
            del args, kwargs
            events.append("start_run_enter")
            yield self
            events.append("start_run_exit")

        def log_params(self, params: dict[str, Any]) -> None:
            del params
            events.append("log_params")

        def log_metric(self, key: str, value: float | int, step: int | None = None) -> None:
            del key, value, step

        def log_metrics(self, metrics: dict[str, float | int], step: int | None = None) -> None:
            del metrics, step
            events.append("log_metrics")

        def log_artifact(self, local_path: str, artifact_path: str | None = None) -> None:
            del local_path, artifact_path
            events.append("log_artifact")

        def log_artifacts(self, local_dir: str, artifact_path: str | None = None) -> None:
            del local_dir, artifact_path
            events.append("log_artifacts")

        def log_dict(self, dictionary: dict[str, Any], artifact_file: str) -> None:
            del dictionary, artifact_file

    monkeypatch.setattr(demand_train, "mlflow", _RecordingMlflow())
    monkeypatch.setattr(demand_train, "DemandForecastTrainer", _stub_demand_trainer(events, out_dir / "v1"))
    monkeypatch.setattr("sys.argv", ["train.py", "--config", str(cfg_path), "--output-dir", str(out_dir)])

    demand_train.main()

    assert events.index("export") < events.index("set_experiment:demand-forecast")
    assert events.index("export") < events.index("start_run_enter")


def test_shadow_runner_and_search_ranking_registry_log_exceptions(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    class _FailingShadow(BasePredictor):
        def _load_model(self, version: str | None = None) -> None:
            del version

        def predict(self, features: dict[str, Any]) -> PredictionResult:
            del features
            raise RuntimeError("boom shadow")

        def rule_based_fallback(self, features: dict[str, Any]) -> PredictionResult:
            del features
            return PredictionResult({}, "fallback", self.model_name, 0.1, is_fallback=True)

    prod = PredictionResult({"score": 1.0}, "v1", "eta", 2.0)
    runner = ShadowRunner()
    with caplog.at_level(logging.ERROR, logger="ml.serving.shadow_mode"):
        assert runner.run_shadow(prod, _FailingShadow("eta", "."), {}) is None
    assert any(rec.exc_info is not None and "Shadow prediction failed" in rec.message for rec in caplog.records)

    class _OkShadow(BasePredictor):
        def _load_model(self, version: str | None = None) -> None:
            del version

        def predict(self, features: dict[str, Any]) -> PredictionResult:
            del features
            return PredictionResult({"score": 1.0}, "v2", "eta", 2.0)

        def rule_based_fallback(self, features: dict[str, Any]) -> PredictionResult:
            return self.predict(features)

    def _failing_kafka(_topic: str, _payload: dict[str, Any]) -> None:
        raise OSError("kafka down")

    caplog.clear()
    runner_kafka = ShadowRunner(kafka_producer=_failing_kafka)
    with caplog.at_level(logging.WARNING, logger="ml.serving.shadow_mode"):
        assert runner_kafka.run_shadow(prod, _OkShadow("eta", "."), {}) is not None
    assert any(
        rec.exc_info is not None and "Failed to stream shadow prediction to Kafka" in rec.message
        for rec in caplog.records
    )

    mock_client = MagicMock()
    mock_client.transition_model_version_stage.side_effect = RuntimeError("stages deprecated")
    monkeypatch.setattr(rank_train, "MlflowClient", lambda: mock_client)
    caplog.clear()
    with caplog.at_level(logging.WARNING, logger="ml.train.search_ranking.train"):
        assert rank_train.promote_in_mlflow_registry("search-ranking", "v1") is True
    mock_client.set_model_version_tag.assert_called_once()
    assert any(rec.exc_info is not None and "Stage transition failed" in rec.message for rec in caplog.records)
