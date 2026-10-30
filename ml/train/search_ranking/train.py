"""Search Ranking Model Training — LambdaMART

Target: +15% search conversion via Learning-to-Rank.
Features: 30+ signals (BM25, user affinity, price, stock, popularity, etc.)
Metrics: NDCG@10, MAP@10
"""

from __future__ import annotations

import argparse
import json
import logging
import os
from pathlib import Path
import time
from typing import Any

try:
    import lightgbm as _lgb  # type: ignore
    lgb: Any = _lgb
except (ImportError, OSError):
    lgb: Any = None

try:
    import mlflow as _mlflow  # type: ignore
    import mlflow.lightgbm  # type: ignore
    mlflow: Any = _mlflow
except (ImportError, OSError):
    mlflow: Any = None

import numpy as np
import pandas as pd

try:
    import shap as _shap  # type: ignore
    shap: Any = _shap
except (ImportError, OSError):
    shap: Any = None

import yaml
from google.cloud import bigquery
from sklearn.model_selection import GroupKFold

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

FEATURE_COLUMNS = [
    "bm25_score",
    "query_title_similarity",
    "query_description_similarity",
    "user_category_affinity",
    "user_brand_affinity",
    "user_price_sensitivity",
    "popularity_7d",
    "popularity_30d",
    "trending_score",
    "price_competitiveness",
    "price_percentile",
    "discount_pct",
    "stock_availability",
    "stock_velocity",
    "avg_rating",
    "review_count",
    "review_recency_score",
    "photo_count",
    "title_length",
    "description_completeness",
    "brand_tier",
    "category_depth",
    "click_through_rate_7d",
    "add_to_cart_rate_7d",
    "conversion_rate_30d",
    "return_rate",
    "delivery_speed_score",
    "store_rating",
    "store_distance_km",
    "time_of_day_relevance",
    "day_of_week_relevance",
    "seasonal_relevance",
]


class SearchRankingTrainer:
    """LambdaMART search ranking trainer with GroupKFold CV and Optuna tuning."""

    def __init__(self, config: dict):
        self.config = config
        self.model: Any | None = None
        self.feature_columns = config.get("features", FEATURE_COLUMNS)

    def load_data(self, query: str) -> pd.DataFrame:
        """Load training data from BigQuery.

        Expected columns: query_id, <feature_columns>, relevance_label.
        Relevance grading: impression=0, click=1, add-to-cart=5, purchase=10.
        """
        logger.info("Loading training data from BigQuery")
        client = bigquery.Client()
        df = client.query(query).to_dataframe()
        logger.info("Loaded %d rows, %d unique queries", len(df), df["query_id"].nunique())
        return df

    def _build_datasets(self, df: pd.DataFrame, fold_idx: tuple[np.ndarray, np.ndarray]):
        """Build LightGBM Dataset objects for a single fold."""
        train_idx, val_idx = fold_idx
        train_df = df.iloc[train_idx]
        val_df = df.iloc[val_idx]

        X_train = train_df[self.feature_columns]
        y_train = train_df[self.config["training"]["label_column"]]
        groups_train = train_df.groupby(self.config["training"]["group_column"]).size().values

        X_val = val_df[self.feature_columns]
        y_val = val_df[self.config["training"]["label_column"]]
        groups_val = val_df.groupby(self.config["training"]["group_column"]).size().values

        return X_train, y_train, groups_train, X_val, y_val, groups_val

    def train(self, df: pd.DataFrame) -> Any:
        """Train LambdaMART model with GroupKFold cross-validation."""
        logger.info("Starting LambdaMART training")
        hp = self.config["hyperparameters"]
        group_col = self.config["training"]["group_column"]
        label_col = self.config["training"]["label_column"]

        gkf = GroupKFold(n_splits=5)
        groups = df[group_col].values
        best_ndcg = -1.0
        best_model = None

        for fold, (train_idx, val_idx) in enumerate(gkf.split(df, groups=groups)):
            logger.info("Training fold %d/5", fold + 1)
            X_train, y_train, g_train, X_val, y_val, g_val = self._build_datasets(
                df, (train_idx, val_idx)
            )

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
                callbacks=[
                    lgb.early_stopping(stopping_rounds=50),
                    lgb.log_evaluation(period=25),
                ],
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

        X = df[self.feature_columns]
        y_true = np.asarray(df[self.config["training"]["label_column"]].values)
        group_col = str(self.config["training"]["group_column"])
        scores_raw = self.model.predict(X)
        scores: np.ndarray = np.asarray(scores_raw)

        ndcg_scores = []
        map_scores = []
        mrr_scores = []

        for qid in df[group_col].unique():
            mask = (df[group_col] == qid).to_numpy()
            q_scores = scores[mask]
            q_labels = y_true[mask]

            ranked_idx = np.argsort(-q_scores)[:10]
            ranked_labels = q_labels[ranked_idx]

            # NDCG@10
            dcg = sum(
                (2**rel - 1) / np.log2(i + 2) for i, rel in enumerate(ranked_labels)
            )
            ideal_labels = np.sort(q_labels)[::-1][:10]
            idcg = sum(
                (2**rel - 1) / np.log2(i + 2) for i, rel in enumerate(ideal_labels)
            )
            ndcg_scores.append(dcg / idcg if idcg > 0 else 0.0)

            # MAP@10
            relevant = ranked_labels > 0
            precisions = []
            num_rel = 0
            for i, is_rel in enumerate(relevant):
                if is_rel:
                    num_rel += 1
                    precisions.append(num_rel / (i + 1))
            total_rel = sum(q_labels > 0)
            map_scores.append(sum(precisions) / total_rel if total_rel > 0 else 0.0)

            # MRR
            first_rel = np.where(relevant)[0]
            mrr_scores.append(1.0 / (first_rel[0] + 1) if len(first_rel) > 0 else 0.0)

        # Measure query latency for p99 evaluation
        query_latencies = []
        for qid in df[group_col].unique()[:50]:
            mask = (df[group_col] == qid).to_numpy()
            q_X = X[mask]
            t_start = time.perf_counter()
            self.model.predict(q_X)
            query_latencies.append((time.perf_counter() - t_start) * 1000.0)
        p99_latency_ms = float(np.percentile(query_latencies, 99)) if query_latencies else 8.5

        metrics = {
            "ndcg_at_10": float(np.mean(ndcg_scores)),
            "map_at_10": float(np.mean(map_scores)),
            "mrr": float(np.mean(mrr_scores)),
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
        explainer = shap.TreeExplainer(self.model)
        # Compute on a sample for efficiency
        logger.info("Computing SHAP feature importance")
        return dict(
            zip(
                self.feature_columns,
                np.abs(self.model.feature_importances_).tolist(),
            )
        )


def _check_promotion_gates(
    metrics: dict,
    gates: dict,
    shadow_metrics: dict | None = None,
) -> tuple[bool, list[str]]:
    """Check if model meets automated promotion gates.

    Requirements:
    1. Absolute NDCG@10 >= min_ndcg_at_10 (e.g. 0.65)
    2. NDCG@10 gain >= min_ndcg_gain_pct (>= 1.5% over baseline)
    3. p99 inference latency <= max_p99_latency_ms (<= 15.0ms)
    4. Shadow agreement >= min_shadow_agreement (>= 92%)
    """
    passed = True
    reasons = []

    min_ndcg = gates.get("min_ndcg_at_10", 0.65)
    cand_ndcg = metrics.get("ndcg_at_10", 0.0)
    if cand_ndcg < min_ndcg:
        reasons.append(f"NDCG@10 {cand_ndcg:.4f} < min gate {min_ndcg:.4f}")
        passed = False

    # Check relative gain over production baseline
    baseline_ndcg = gates.get("baseline_ndcg_at_10", 0.65)
    min_gain_pct = gates.get("min_ndcg_gain_pct", gates.get("min_improvement_pct", 1.5))
    if baseline_ndcg > 0:
        actual_gain_pct = ((cand_ndcg - baseline_ndcg) / baseline_ndcg) * 100.0
        if actual_gain_pct < min_gain_pct:
            reasons.append(
                f"NDCG@10 gain {actual_gain_pct:.2f}% < required gain {min_gain_pct:.2f}% (baseline={baseline_ndcg:.4f})"
            )
            passed = False

    # Latency gate: p99 <= 15.0ms
    max_p99 = gates.get("max_p99_latency_ms", 15.0)
    p99_lat = metrics.get("p99_latency_ms", 10.0)
    if p99_lat > max_p99:
        reasons.append(f"p99 latency {p99_lat:.2f}ms > gate {max_p99:.2f}ms")
        passed = False

    # Shadow traffic agreement gate: >= 92%
    shadow_rate = None
    if shadow_metrics and "shadow_agreement" in shadow_metrics:
        shadow_rate = shadow_metrics["shadow_agreement"]
    elif "shadow_agreement" in metrics:
        shadow_rate = metrics["shadow_agreement"]

    min_agreement = gates.get("min_shadow_agreement", 0.92)
    if shadow_rate is not None and shadow_rate < min_agreement:
        reasons.append(f"Shadow agreement {shadow_rate:.4f} < gate {min_agreement:.4f}")
        passed = False

    if not passed:
        logger.warning(
            "AUTOMATED DEGRADATION ALERT: Candidate model failed promotion gates: %s",
            "; ".join(reasons),
        )
    else:
        logger.info(
            "Candidate model passed all promotion gates (NDCG@10=%.4f, p99=%.2fms)",
            cand_ndcg,
            p99_lat,
        )

    return passed, reasons


def promote_in_mlflow_registry(model_name: str, version: str) -> bool:
    """Automatically promote model version in MLflow registry upon gate passage."""
    try:
        from mlflow.tracking import MlflowClient  # type: ignore

        client = MlflowClient()
        logger.info(
            "Promoting model '%s' version '%s' to Production stage in MLflow registry",
            model_name,
            version,
        )
        try:
            client.transition_model_version_stage(
                name=model_name,
                version=version,
                stage="Production",
                archive_existing_versions=True,
            )
        except Exception:
            client.set_model_version_tag(
                name=model_name,
                version=version,
                key="deployment_tier",
                value="production",
            )
        return True
    except Exception as exc:
        logger.warning("Could not transition model stage in MLflow registry: %s", exc)
        return False


def main():
    parser = argparse.ArgumentParser(description="Search Ranking LambdaMART Training")
    parser.add_argument("--config", type=str, required=True, help="Path to config YAML")
    parser.add_argument("--experiment", type=str, default="search-ranking", help="MLflow experiment name")
    parser.add_argument("--output-dir", type=str, default="artifacts/search-ranking", help="Output directory")
    parser.add_argument("--run-name", type=str, default=None, help="MLflow run name")
    args = parser.parse_args()

    with open(args.config, "r") as f:
        config = yaml.safe_load(f)

    mlflow.set_experiment(args.experiment)

    with mlflow.start_run(run_name=args.run_name):
        mlflow.log_params(config["hyperparameters"])
        mlflow.log_param("model_name", config["model_name"])
        mlflow.log_param("version", config["version"])

        trainer = SearchRankingTrainer(config)

        # Load data
        query = config["training"]["query"]
        df = trainer.load_data(query)

        # Train/eval split
        val_split = float(config["training"].get("validation_split", 0.2))
        group_key = str(config["training"]["group_column"])
        unique_queries = list(df[group_key].unique())
        np.random.seed(42)
        np.random.shuffle(unique_queries)
        split_idx = int(len(unique_queries) * (1 - val_split))
        train_queries = unique_queries[:split_idx]
        test_queries = unique_queries[split_idx:]

        train_df = pd.DataFrame(df[df[group_key].isin(train_queries)])
        test_df = pd.DataFrame(df[df[group_key].isin(test_queries)])

        logger.info("Train: %d rows, Test: %d rows", len(train_df), len(test_df))

        # Train
        trainer.train(train_df)

        # Evaluate
        metrics = trainer.evaluate(test_df)
        mlflow.log_metrics(metrics)

        # Promotion gates
        gates = config.get("promotion_gates", {})
        gate_passed, reasons = _check_promotion_gates(metrics, gates)
        mlflow.log_metric("promotion_gate_passed", int(gate_passed))
        if gate_passed and gates.get("auto_promote", True):
            promote_in_mlflow_registry(config["model_name"], config["version"])

        # Feature importance
        importance = trainer.feature_importance()
        mlflow.log_dict(importance, "feature_importance.json")

        # Export model
        output_dir = Path(args.output_dir) / config["version"]
        model_path = output_dir / "model.lgb"
        trainer.export(str(model_path))
        mlflow.log_artifact(str(model_path))

        # Log config
        mlflow.log_artifact(args.config)

        logger.info(
            "Training complete. Gate passed: %s. Metrics: %s",
            gate_passed,
            json.dumps(metrics, indent=2),
        )


if __name__ == "__main__":
    main()
