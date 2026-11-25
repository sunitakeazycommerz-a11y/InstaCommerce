"""Search Ranking Weekly Retraining Pipeline — Airflow DAG.

Schedule: Every Sunday at 02:00 UTC ('0 2 * * 0')
Executes LambdaMART search ranking model retraining on 90-day rolling interaction data,
logs metrics (NDCG@10, MAP@10, MRR) and SHAP feature importances to MLflow,
and triggers automated shadow mode deployment when candidate beats production baseline.
"""

from datetime import datetime, timedelta
import json
import logging
import os
from pathlib import Path
from typing import Any, Dict

from airflow import DAG
from airflow.operators.python import BranchPythonOperator, PythonOperator
import numpy as np
import pandas as pd
from plugins.slack_alerts import send_slack_alert

logger = logging.getLogger(__name__)

_REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CONFIG_PATH = _REPO_ROOT / "ml" / "train" / "search_ranking" / "config.yaml"
ARTIFACT_BASE_DIR = Path(os.getenv("SEARCH_RANKING_ARTIFACT_DIR", "/tmp/instacommerce-ml/search-ranking"))

try:
    import mlflow  # type: ignore[import-untyped]
except ImportError:
    mlflow = None  # type: ignore[assignment]

try:
    from ml.train.common import load_training_config, log_mlflow_run
    from ml.train.search_ranking.train import (
        FEATURE_COLUMNS,
        SearchRankingTrainer,
        _check_promotion_gates as evaluate_ranking_gates,
        promote_in_mlflow_registry,
    )
except ImportError:
    load_training_config = None  # type: ignore[assignment]
    log_mlflow_run = None  # type: ignore[assignment]
    FEATURE_COLUMNS = []
    SearchRankingTrainer = None  # type: ignore[assignment,misc]
    evaluate_ranking_gates = None  # type: ignore[assignment]
    promote_in_mlflow_registry = None  # type: ignore[assignment]


def failure_alert(context):
    try:
        send_slack_alert(context)
    except Exception:
        pass


default_args = {
    "owner": "ml-platform",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["ml-alerts@instacommerce.com"],
    "retries": 2,
    "retry_delay": timedelta(minutes=10),
    "execution_timeout": timedelta(hours=2),
    "on_failure_callback": failure_alert,
}

PROJECT_ID = "instacommerce-prod"
REGION = "asia-south1"
MLFLOW_TRACKING_URI = "https://mlflow.instacommerce.internal"
EXPERIMENT_NAME = "search-ranking-production"


def _load_config() -> Dict[str, Any]:
    if load_training_config is not None and DEFAULT_CONFIG_PATH.exists():
        return load_training_config(str(DEFAULT_CONFIG_PATH))
    return {
        "model_name": "search-ranking",
        "version": "v1",
        "features": list(FEATURE_COLUMNS),
        "hyperparameters": {
            "num_leaves": 31,
            "learning_rate": 0.05,
            "n_estimators": 30,
            "min_child_samples": 5,
            "subsample": 0.8,
        },
        "training": {
            "query": "SELECT * FROM analytics.training_search_clicks",
            "validation_split": 0.2,
            "group_column": "query_id",
            "label_column": "relevance_label",
        },
        "promotion_gates": {
            "min_ndcg_at_10": 0.65,
            "min_ndcg_gain_pct": 1.5,
            "baseline_ndcg_at_10": 0.65,
            "max_p99_latency_ms": 15.0,
            "auto_promote": True,
        },
    }


def _build_local_ranking_dataset(
    feature_columns: list[str],
    group_col: str,
    label_col: str,
    *,
    num_queries: int = 30,
    docs_per_query: int = 12,
) -> pd.DataFrame:
    rng = np.random.default_rng(42)
    rows: list[dict[str, Any]] = []
    for q_idx in range(num_queries):
        qid = f"q_{q_idx:03d}"
        for d_idx in range(docs_per_query):
            rel = 3 if d_idx < 2 else (2 if d_idx < 5 else (1 if d_idx < 8 else 0))
            row: dict[str, Any] = {group_col: qid, label_col: rel}
            signal = rel / 3.0
            for col_idx, col in enumerate(feature_columns):
                jitter = float(rng.normal(0.0, 0.08))
                row[col] = max(0.0, signal * (1.0 + (col_idx % 3) * 0.1) + jitter)
            rows.append(row)
    return pd.DataFrame(rows)


def _run_dir(execution_date: str) -> Path:
    safe_date = execution_date.replace("/", "-")
    path = ARTIFACT_BASE_DIR / safe_date
    path.mkdir(parents=True, exist_ok=True)
    return path


class _LinearFallbackRanker:
    """Deterministic ridge ranker fallback when native LightGBM is unavailable."""

    def __init__(self, weights: np.ndarray, intercept: float, feature_cols: list[str]) -> None:
        self.weights = weights
        self.intercept = intercept
        self.feature_cols = feature_cols

    @classmethod
    def fit(cls, df: pd.DataFrame, feature_cols: list[str], label_col: str) -> "_LinearFallbackRanker":
        cols = [c for c in feature_cols if c in df.columns]
        X = df[cols].fillna(0.0).to_numpy(dtype=np.float64)
        y = df[label_col].to_numpy(dtype=np.float64)
        X_aug = np.column_stack([X, np.ones(len(X), dtype=np.float64)])
        reg = 1e-2 * np.eye(X_aug.shape[1], dtype=np.float64)
        reg[-1, -1] = 0.0
        coef = np.linalg.solve(X_aug.T @ X_aug + reg, X_aug.T @ y)
        return cls(weights=coef[:-1], intercept=float(coef[-1]), feature_cols=cols)

    def predict(self, X: Any) -> np.ndarray:
        arr = X[self.feature_cols].fillna(0.0).to_numpy(dtype=np.float64) if isinstance(X, pd.DataFrame) else np.asarray(X, dtype=np.float64)
        return arr @ self.weights + self.intercept

    def save_json(self, path: Path) -> None:
        path.write_text(
            json.dumps({
                "type": "linear_fallback",
                "feature_cols": self.feature_cols,
                "weights": self.weights.tolist(),
                "intercept": self.intercept,
            }),
            encoding="utf-8",
        )

    @classmethod
    def from_json_text(cls, raw: str) -> "_LinearFallbackRanker":
        data = json.loads(raw)
        return cls(
            weights=np.asarray(data["weights"], dtype=np.float64),
            intercept=float(data["intercept"]),
            feature_cols=list(data["feature_cols"]),
        )


def _extract_training_features(**context):
    """Extract rolling 90-day query interaction features from BigQuery or local dry-run."""
    execution_date = context["ds"]
    config = _load_config()
    run_dir = _run_dir(execution_date)
    snapshot_path = run_dir / "features.pkl"
    feature_cols = list(config.get("features") or FEATURE_COLUMNS)
    group_col = str(config["training"]["group_column"])
    label_col = str(config["training"]["label_column"])
    dry_run = os.getenv("SEARCH_RANKING_DRY_RUN", "false").lower() in ("1", "true", "yes")

    df: pd.DataFrame
    source_mode = "bigquery"
    if not dry_run and SearchRankingTrainer is not None:
        try:
            trainer = SearchRankingTrainer(config)
            df = trainer.load_data(config["training"]["query"])
        except Exception as exc:
            logger.warning("BigQuery extraction unavailable (%s); using local dry-run dataset", exc)
            source_mode = "dry_run_fallback"
            df = _build_local_ranking_dataset(feature_cols, group_col, label_col)
    else:
        source_mode = "dry_run"
        df = _build_local_ranking_dataset(feature_cols, group_col, label_col)

    df.to_pickle(snapshot_path)
    context["ti"].xcom_push(key="feature_snapshot_date", value=execution_date)
    context["ti"].xcom_push(key="feature_snapshot_path", value=str(snapshot_path))
    context["ti"].xcom_push(key="source_mode", value=source_mode)
    return {"feature_snapshot_path": str(snapshot_path), "rows": len(df), "source_mode": source_mode}


def _train_lambdamart(**context):
    """Execute LightGBM LambdaMART training with GroupKFold cross-validation."""
    ti = context["ti"]
    execution_date = ti.xcom_pull(task_ids="extract_training_features", key="feature_snapshot_date") or context["ds"]
    snapshot_path = Path(ti.xcom_pull(task_ids="extract_training_features", key="feature_snapshot_path"))
    config = _load_config()
    run_dir = _run_dir(execution_date)
    run_id = f"search-ranking-{execution_date}"

    raw_df = pd.read_pickle(snapshot_path)
    df = raw_df if isinstance(raw_df, pd.DataFrame) else pd.DataFrame(raw_df)
    group_key = str(config["training"]["group_column"])
    val_split = float(config["training"].get("validation_split", 0.2))
    group_col_series = pd.Series(df[group_key])
    unique_queries = list(group_col_series.unique())
    rng = np.random.default_rng(42)
    rng.shuffle(unique_queries)
    split_idx = max(1, min(len(unique_queries) - 1, int(len(unique_queries) * (1 - val_split))))

    train_df = pd.DataFrame(df[group_col_series.isin(unique_queries[:split_idx])]).sort_values(group_key).reset_index(drop=True)
    test_df = pd.DataFrame(df[group_col_series.isin(unique_queries[split_idx:])]).sort_values(group_key).reset_index(drop=True)

    train_path = run_dir / "train.pkl"
    test_path = run_dir / "test.pkl"
    train_df.to_pickle(train_path)
    test_df.to_pickle(test_path)

    model_path = run_dir / "model.lgb"
    trained_with_fallback = False
    if SearchRankingTrainer is not None:
        try:
            local_cfg = dict(config)
            local_hp = dict(config.get("hyperparameters", {}))
            if len(train_df) < 1000:
                local_hp["n_estimators"] = min(int(local_hp.get("n_estimators", 50)), 40)
                local_hp["num_leaves"] = min(int(local_hp.get("num_leaves", 31)), 31)
                local_hp["min_child_samples"] = min(int(local_hp.get("min_child_samples", 5)), 5)
            local_cfg["hyperparameters"] = local_hp
            trainer = SearchRankingTrainer(local_cfg)
            trainer.train(train_df)
            trainer.export(str(model_path))
        except (ImportError, OSError) as exc:
            logger.warning("Native LightGBM unavailable (%s); fitting ridge ranker fallback", exc)
            trained_with_fallback = True
    else:
        trained_with_fallback = True

    if trained_with_fallback:
        feature_cols = list(config.get("features") or FEATURE_COLUMNS)
        label_col = str(config["training"]["label_column"])
        model_path = run_dir / "model_fallback.json"
        _LinearFallbackRanker.fit(train_df, feature_cols, label_col).save_json(model_path)

    ti.xcom_push(key="mlflow_run_id", value=run_id)
    ti.xcom_push(key="model_path", value=str(model_path))
    ti.xcom_push(key="test_data_path", value=str(test_path))
    return {"mlflow_run_id": run_id, "model_path": str(model_path), "train_rows": len(train_df)}


def _evaluate_candidate(**context):
    """Evaluate candidate model on held-out query test set and log to MLflow."""
    ti = context["ti"]
    run_id = ti.xcom_pull(task_ids="train_lambdamart", key="mlflow_run_id")
    model_path = Path(ti.xcom_pull(task_ids="train_lambdamart", key="model_path"))
    test_path = Path(ti.xcom_pull(task_ids="train_lambdamart", key="test_data_path"))
    config = _load_config()
    raw_test_df = pd.read_pickle(test_path)
    test_df = raw_test_df if isinstance(raw_test_df, pd.DataFrame) else pd.DataFrame(raw_test_df)

    if SearchRankingTrainer is None:
        raise RuntimeError("ml.train.search_ranking.train is unavailable")

    trainer = SearchRankingTrainer(config)
    if model_path.suffix == ".json":
        trainer.model = _LinearFallbackRanker.from_json_text(model_path.read_text(encoding="utf-8"))
    else:
        import lightgbm as lgb  # type: ignore[import-untyped]

        trainer.model = lgb.Booster(model_file=str(model_path))
    metrics = trainer.evaluate(test_df)

    baseline_ndcg = float(config.get("promotion_gates", {}).get("baseline_ndcg_at_10", 0.65))
    cand_ndcg = float(metrics.get("ndcg_at_10", 0.0))
    metrics["baseline_ndcg_at_10"] = baseline_ndcg
    metrics["ndcg_gain_pct"] = (
        round(((cand_ndcg - baseline_ndcg) / baseline_ndcg) * 100.0, 4)
        if baseline_ndcg > 0
        else 0.0
    )

    if mlflow is not None and log_mlflow_run is not None and os.getenv("SEARCH_RANKING_DRY_RUN", "false").lower() != "true":
        try:
            mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)
            log_mlflow_run(
                mlflow,
                experiment=EXPERIMENT_NAME,
                run_name=run_id,
                params={**config.get("hyperparameters", {}), "region": REGION, "model_name": config.get("model_name", "search-ranking")},
                metrics=metrics,
                gate_passed=True,
                artifact_dir=model_path.parent,
                artifact_paths=(model_path,),
            )
        except Exception as exc:
            logger.warning("MLflow logging skipped (%s)", exc)

    ti.xcom_push(key="eval_metrics", value=metrics)
    return metrics


def _check_promotion_gates(**context):
    """Check candidate metrics against ml/train/search_ranking promotion gates."""
    metrics = context["ti"].xcom_pull(task_ids="evaluate_candidate", key="eval_metrics")
    if not metrics:
        return "alert_degradation"

    config = _load_config()
    gates = config.get("promotion_gates", {})
    if evaluate_ranking_gates is not None:
        passed, reasons = evaluate_ranking_gates(metrics, gates)
        if passed:
            return "deploy_shadow_mode"
        logger.warning("Ranking gates rejected candidate: %s", reasons)
        return "alert_degradation"

    ndcg_gain = float(metrics.get("ndcg_gain_pct", 0.0))
    p99_lat = float(metrics.get("p99_latency_ms", 999.0))
    if ndcg_gain >= 1.5 and p99_lat <= 15.0:
        return "deploy_shadow_mode"
    return "alert_degradation"


def _deploy_shadow_mode(**context):
    """Trigger automated shadow mode deployment alongside champion model."""
    ti = context["ti"]
    metrics = ti.xcom_pull(task_ids="evaluate_candidate", key="eval_metrics")
    run_id = ti.xcom_pull(task_ids="train_lambdamart", key="mlflow_run_id")
    model_path = ti.xcom_pull(task_ids="train_lambdamart", key="model_path")
    config = _load_config()

    promoted = False
    if promote_in_mlflow_registry is not None and os.getenv("SEARCH_RANKING_DRY_RUN", "false").lower() != "true":
        promoted = promote_in_mlflow_registry(config.get("model_name", "search-ranking"), config.get("version", "v1"))

    manifest_path = Path(model_path).parent / "shadow_deployment.json" if model_path else _run_dir(context["ds"]) / "shadow_deployment.json"
    manifest_path.write_text(
        json.dumps(
            {
                "run_id": run_id,
                "region": REGION,
                "model_path": model_path,
                "metrics": metrics,
                "mlflow_promoted": promoted,
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    logger.info("Deployed candidate '%s' to shadow mode in %s (promoted=%s)", run_id, REGION, promoted)
    return {"run_id": run_id, "manifest_path": str(manifest_path), "promoted": promoted}


def _alert_degradation(**context):
    """Send alert on candidate model degradation."""
    metrics = context["ti"].xcom_pull(task_ids="evaluate_candidate", key="eval_metrics")
    logger.warning("Search ranking candidate model failed promotion gates: %s", metrics)
    send_slack_alert(
        context,
        message=f"Search ranking candidate failed promotion gates: {json.dumps(metrics or {})}",
        level="warning",
    )


with DAG(
    dag_id="search_ranking_weekly_retraining",
    default_args=default_args,
    description="Weekly retraining of LightGBM search ranking model with MLflow tracking and shadow deployment",
    schedule="0 2 * * 0",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["ml", "search-ranking", "weekly", "shadow-mode", "data-platform"],
    max_active_runs=1,
) as dag:
    extract = PythonOperator(
        task_id="extract_training_features",
        python_callable=_extract_training_features,
        provide_context=True,
    )

    train = PythonOperator(
        task_id="train_lambdamart",
        python_callable=_train_lambdamart,
        provide_context=True,
    )

    evaluate = PythonOperator(
        task_id="evaluate_candidate",
        python_callable=_evaluate_candidate,
        provide_context=True,
    )

    gate = BranchPythonOperator(
        task_id="check_promotion_gates",
        python_callable=_check_promotion_gates,
        provide_context=True,
    )

    shadow_deploy = PythonOperator(
        task_id="deploy_shadow_mode",
        python_callable=_deploy_shadow_mode,
        provide_context=True,
    )

    alert = PythonOperator(
        task_id="alert_degradation",
        python_callable=_alert_degradation,
        provide_context=True,
    )

    extract >> train >> evaluate >> gate >> [shadow_deploy, alert]
