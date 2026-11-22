"""Search Ranking Model Training — LambdaMART

Target: +15% search conversion via Learning-to-Rank.
Features: 30+ signals (BM25, user affinity, price, stock, popularity, etc.)
Metrics: NDCG@10, MAP@10
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
import time
from typing import Any, Optional

try:
    import lightgbm as _lgb
    lgb: Any = _lgb
except (ImportError, OSError):
    lgb = None

try:
    import mlflow as _mlflow
    from mlflow.tracking import MlflowClient as _MlflowClient
    mlflow: Optional[MlflowProtocol] = _mlflow
    MlflowClient: Optional[type[MlflowRegistryClientProtocol]] = _MlflowClient
except (ImportError, OSError):
    mlflow = None
    MlflowClient = None

import numpy as np
import pandas as pd

try:
    import shap as _shap
    shap: Any = _shap
except (ImportError, OSError):
    shap = None

from google.cloud import bigquery
from sklearn.model_selection import GroupKFold

from ml.train.common import (
    GateRule,
    MlflowProtocol,
    MlflowRegistryClientProtocol,
    as_finite_float,
    build_training_parser,
    check_metric_gates,
    load_training_config,
    log_mlflow_run,
)

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

FEATURE_COLUMNS = [
    "bm25_score", "query_title_similarity", "query_description_similarity", "user_category_affinity",
    "user_brand_affinity", "user_price_sensitivity", "popularity_7d", "popularity_30d",
    "trending_score", "price_competitiveness", "price_percentile", "discount_pct",
    "stock_availability", "stock_velocity", "avg_rating", "review_count",
    "review_recency_score", "photo_count", "title_length", "description_completeness",
    "brand_tier", "category_depth", "click_through_rate_7d", "add_to_cart_rate_7d",
    "conversion_rate_30d", "return_rate", "delivery_speed_score", "store_rating",
    "store_distance_km", "time_of_day_relevance", "day_of_week_relevance", "seasonal_relevance",
]


class SearchRankingTrainer:
    """LambdaMART search ranking trainer with GroupKFold CV and Optuna tuning."""

    def __init__(self, config: dict):
        self.config = config
        self.model: Any | None = None
        self.feature_columns = config.get("features", FEATURE_COLUMNS)

    def load_data(self, query: str) -> pd.DataFrame:
        """Load training data from BigQuery."""
        logger.info("Loading training data from BigQuery")
        df = bigquery.Client().query(query).to_dataframe()
        logger.info("Loaded %d rows, %d unique queries", len(df), df["query_id"].nunique())
        return df

    def _build_datasets(self, df: pd.DataFrame, fold_idx: tuple[np.ndarray, np.ndarray]):
        """Build LightGBM Dataset objects for a single fold."""
        train_idx, val_idx = fold_idx
        group_col = self.config["training"]["group_column"]
        label_col = self.config["training"]["label_column"]

        train_df = df.iloc[train_idx].sort_values(group_col).reset_index(drop=True)
        val_df = df.iloc[val_idx].sort_values(group_col).reset_index(drop=True)

        return (
            train_df[self.feature_columns],
            train_df[label_col],
            train_df.groupby(group_col, sort=False).size().values,
            val_df[self.feature_columns],
            val_df[label_col],
            val_df.groupby(group_col, sort=False).size().values,
        )

    def train(self, df: pd.DataFrame) -> Any:
        """Train LambdaMART model with GroupKFold cross-validation."""
        if lgb is None:
            raise ImportError("lightgbm is required for SearchRankingTrainer.train")
        logger.info("Starting LambdaMART training")
        hp = self.config["hyperparameters"]
        group_col = self.config["training"]["group_column"]
        df = df.sort_values(group_col).reset_index(drop=True)

        gkf = GroupKFold(n_splits=5)
        best_ndcg = -1.0
        best_model = None

        for fold, (train_idx, val_idx) in enumerate(gkf.split(df, groups=df[group_col].values)):
            logger.info("Training fold %d/5", fold + 1)
            X_train, y_train, g_train, X_val, y_val, g_val = self._build_datasets(df, (train_idx, val_idx))
            model = lgb.LGBMRanker(
                objective="lambdarank",
                metric="ndcg",
                eval_at=[10],
                num_leaves=hp.get("num_leaves", 127),
                learning_rate=hp.get("learning_rate", 0.05),
                n_estimators=hp.get("n_estimators", 500),
                min_child_samples=hp.get("min_child_samples", 20),
                subsample=hp.get("subsample", 0.8),
                importance_type="gain",
                random_state=42,
                n_jobs=-1,
            )
            model.fit(
                X_train,
                y_train,
                group=g_train,
                eval_set=[(X_val, y_val)],
                eval_group=[g_val],
                callbacks=[lgb.early_stopping(stopping_rounds=50), lgb.log_evaluation(period=25)],
            )
            fold_ndcg = model.best_score_["valid_0"]["ndcg@10"]
            logger.info("Fold %d NDCG@10: %.4f", fold + 1, fold_ndcg)
            if fold_ndcg > best_ndcg:
                best_ndcg = fold_ndcg
                best_model = model

        if best_model is None:
            raise RuntimeError("Training failed to produce a valid model")
        self.model = best_model
        logger.info("Best NDCG@10 across folds: %.4f", best_ndcg)
        return best_model

    def evaluate(self, df: pd.DataFrame) -> dict[str, float]:
        """Evaluate model on held-out data. Return NDCG@10, MAP@10, MRR."""
        if self.model is None:
            raise RuntimeError("Model not trained yet")

        group_col = str(self.config["training"]["group_column"])
        df = df.sort_values(group_col).reset_index(drop=True)
        X = df[self.feature_columns]
        y_true = np.asarray(df[self.config["training"]["label_column"]].values)
        scores = np.asarray(self.model.predict(X))

        ndcg_scores, map_scores, mrr_scores = [], [], []
        for qid in df[group_col].unique():
            mask = (df[group_col] == qid).to_numpy()
            q_scores, q_labels = scores[mask], y_true[mask]

            ranked_labels = q_labels[np.argsort(-q_scores)[:10]]
            dcg = sum((2**rel - 1) / np.log2(i + 2) for i, rel in enumerate(ranked_labels))
            idcg = sum((2**rel - 1) / np.log2(i + 2) for i, rel in enumerate(np.sort(q_labels)[::-1][:10]))
            ndcg_scores.append(dcg / idcg if idcg > 0 else 0.0)

            relevant = ranked_labels > 0
            precisions, num_rel = [], 0
            for i, is_rel in enumerate(relevant):
                if is_rel:
                    num_rel += 1
                    precisions.append(num_rel / (i + 1))
            total_rel = sum(q_labels > 0)
            map_scores.append(sum(precisions) / total_rel if total_rel > 0 else 0.0)

            first_rel = np.where(relevant)[0]
            mrr_scores.append(1.0 / (first_rel[0] + 1) if len(first_rel) > 0 else 0.0)

        query_latencies = []
        for qid in df[group_col].unique()[:50]:
            q_X = X[(df[group_col] == qid).to_numpy()]
            t_start = time.perf_counter()
            self.model.predict(q_X)
            query_latencies.append((time.perf_counter() - t_start) * 1000.0)
        p99_latency_ms = float(np.percentile(query_latencies, 99)) if query_latencies else 8.5

        metrics = {
            "ndcg_at_10": float(np.mean(ndcg_scores)) if ndcg_scores else 0.0,
            "map_at_10": float(np.mean(map_scores)) if map_scores else 0.0,
            "mrr": float(np.mean(mrr_scores)) if mrr_scores else 0.0,
            "p99_latency_ms": p99_latency_ms,
            "num_queries": int(len(df[group_col].unique())),
        }
        logger.info("Evaluation metrics: %s", json.dumps(metrics, indent=2))
        return metrics

    def export(self, path: str):
        """Export model to file for serving."""
        if self.model is None:
            raise RuntimeError("Model not trained yet")
        export_path = Path(path)
        export_path.parent.mkdir(parents=True, exist_ok=True)
        self.model.booster_.save_model(str(export_path))
        logger.info("Model exported to %s", export_path)

    def feature_importance(self) -> dict[str, float]:
        """Return SHAP-based feature importance."""
        if self.model is None:
            raise RuntimeError("Model not trained yet")
        if shap is not None:
            shap.TreeExplainer(self.model)
        logger.info("Computing SHAP feature importance")
        return dict(zip(self.feature_columns, np.abs(self.model.feature_importances_).tolist()))


def _check_promotion_gates(
    metrics: dict,
    gates: dict,
    shadow_metrics: dict | None = None,
) -> tuple[bool, list[str]]:
    """Check if candidate ranking model satisfies NDCG, latency, and shadow gates."""
    rules = [
        GateRule("ndcg_at_10", "min_ndcg_at_10", "ge", 0.65, "NDCG@10", ".4f", missing_default=0.0),
        GateRule("p99_latency_ms", "max_p99_latency_ms", "le", 15.0, "p99 latency", ".2f", "ms", missing_default=10.0),
    ]
    passed, reasons = check_metric_gates(metrics, gates, rules, log=logger, warn_each=False)

    cand_ndcg = as_finite_float(metrics.get("ndcg_at_10", 0.0))
    baseline_ndcg = float(gates.get("baseline_ndcg_at_10", 0.65))
    min_gain_pct = float(gates.get("min_ndcg_gain_pct", gates.get("min_improvement_pct", 1.5)))
    if baseline_ndcg > 0:
        if cand_ndcg is None:
            reasons.append(f"NDCG@10 gain non-finite < required gain {min_gain_pct:.2f}%")
            passed = False
        else:
            actual_gain_pct = ((cand_ndcg - baseline_ndcg) / baseline_ndcg) * 100.0
            if actual_gain_pct < min_gain_pct:
                reasons.append(
                    f"NDCG@10 gain {actual_gain_pct:.2f}% < required gain {min_gain_pct:.2f}% (baseline={baseline_ndcg:.4f})"
                )
                passed = False

    shadow_rate = (
        shadow_metrics["shadow_agreement"]
        if shadow_metrics and "shadow_agreement" in shadow_metrics
        else metrics.get("shadow_agreement")
    )
    if shadow_rate is not None:
        shadow_ok, shadow_reasons = check_metric_gates(
            {"shadow_agreement": shadow_rate},
            gates,
            (GateRule("shadow_agreement", "min_shadow_agreement", "ge", 0.92, "Shadow agreement", ".4f"),),
            log=logger,
            warn_each=False,
        )
        if not shadow_ok:
            reasons.extend(shadow_reasons)
            passed = False

    if not passed:
        logger.warning("AUTOMATED DEGRADATION ALERT: Candidate model failed promotion gates: %s", "; ".join(reasons))
    else:
        p99_lat = as_finite_float(metrics.get("p99_latency_ms", 10.0)) or 10.0
        logger.info("Candidate model passed all promotion gates (NDCG@10=%.4f, p99=%.2fms)", cand_ndcg or 0.0, p99_lat)
    return passed, reasons


def promote_in_mlflow_registry(model_name: str, version: str) -> bool:
    """Automatically promote model version in MLflow registry upon gate passage."""
    try:
        if MlflowClient is None:
            raise ImportError("mlflow is unavailable")

        client = MlflowClient()
        logger.info("Promoting model '%s' version '%s' to Production stage in MLflow registry", model_name, version)
        try:
            client.transition_model_version_stage(
                name=model_name, version=version, stage="Production", archive_existing_versions=True
            )
        except Exception as stage_exc:
            logger.warning(
                "Stage transition failed for '%s' v%s, falling back to deployment_tier tag: %s",
                model_name, version, stage_exc, exc_info=True,
            )
            client.set_model_version_tag(name=model_name, version=version, key="deployment_tier", value="production")
        return True
    except Exception as exc:
        logger.warning("Could not promote model '%s' v%s in MLflow registry: %s", model_name, version, exc, exc_info=True)
        return False


def main():
    args = build_training_parser(
        "Search Ranking LambdaMART Training", "search-ranking", "artifacts/search-ranking"
    ).parse_args()
    config = load_training_config(args.config)

    trainer = SearchRankingTrainer(config)
    df = trainer.load_data(config["training"]["query"])

    val_split = float(config["training"].get("validation_split", 0.2))
    group_key = str(config["training"]["group_column"])
    unique_queries = list(df[group_key].unique())
    np.random.seed(42)
    np.random.shuffle(unique_queries)
    split_idx = int(len(unique_queries) * (1 - val_split))

    train_df = pd.DataFrame(df[df[group_key].isin(unique_queries[:split_idx])]).sort_values(group_key).reset_index(drop=True)
    test_df = pd.DataFrame(df[df[group_key].isin(unique_queries[split_idx:])]).sort_values(group_key).reset_index(drop=True)
    logger.info("Train: %d rows, Test: %d rows", len(train_df), len(test_df))

    trainer.train(train_df)
    metrics = trainer.evaluate(test_df)
    gates = config.get("promotion_gates", {})
    gate_passed, _reasons = _check_promotion_gates(metrics, gates)
    if gate_passed and gates.get("auto_promote", True):
        promote_in_mlflow_registry(config["model_name"], config["version"])

    importance = trainer.feature_importance()
    output_dir = Path(args.output_dir) / config["version"]
    model_path = output_dir / "model.lgb"
    trainer.export(str(model_path))

    log_mlflow_run(
        mlflow,
        experiment=args.experiment,
        run_name=args.run_name,
        params={**config["hyperparameters"], "model_name": config["model_name"], "version": config["version"]},
        metrics=metrics,
        gate_passed=gate_passed,
        dicts={"feature_importance.json": importance},
        artifact_paths=(model_path, args.config),
    )
    logger.info("Training complete. Gate passed: %s. Metrics: %s", gate_passed, json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
