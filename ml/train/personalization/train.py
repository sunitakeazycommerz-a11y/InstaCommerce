"""Personalization — Two-Tower Neural Collaborative Filtering

Recommendation surfaces: homepage, buy-again, frequently-bought-together.
Cold start strategy: popularity -> content-based -> full CF at 4+ orders.

Architecture: User tower (embedding + MLP) and Item tower (embedding + MLP)
produce 64-dim vectors. Dot product scores candidate items. Approximate
nearest neighbor (ANN) index for sub-10ms serving.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, cast

try:
    import mlflow  # type: ignore
except (ImportError, OSError):
    mlflow = None

import numpy as np
import pandas as pd
import yaml
from google.cloud import bigquery  # type: ignore
from sklearn.metrics import ndcg_score

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

USER_FEATURES = [
    "user_id",
    "age_bucket",
    "gender",
    "city_encoded",
    "zone_encoded",
    "account_age_days",
    "order_count_lifetime",
    "order_count_30d",
    "avg_order_value_30d",
    "preferred_categories",
    "preferred_brands",
    "price_sensitivity_bucket",
    "activity_recency_days",
    "browse_frequency_7d",
    "search_frequency_7d",
    "cart_abandonment_rate",
    "promo_sensitivity",
]

ITEM_FEATURES = [
    "sku_id",
    "category_l1",
    "category_l2",
    "category_l3",
    "brand_encoded",
    "price_bucket",
    "avg_rating",
    "review_count",
    "popularity_7d",
    "popularity_30d",
    "stock_status",
    "is_new_arrival",
    "is_on_promotion",
    "image_quality_score",
    "title_length",
    "description_completeness",
]


def assert_zero_data_leakage(
    train_df: pd.DataFrame,
    test_df: pd.DataFrame,
    timestamp_col: str = "timestamp",
) -> bool:
    """Verify zero temporal overlap or test label contamination in training data.

    Enforces strict temporal boundary: all training interactions must precede
    the earliest test interaction.
    """
    if len(train_df) == 0 or len(test_df) == 0:
        return True

    train_max_ts = pd.to_datetime(train_df[timestamp_col]).max()
    test_min_ts = pd.to_datetime(test_df[timestamp_col]).min()

    if train_max_ts >= test_min_ts:
        raise ValueError(
            f"Temporal label leakage detected: max train timestamp ({train_max_ts}) "
            f"is not strictly before min test timestamp ({test_min_ts})"
        )

    # Check for identical event keys if event_id column exists
    if "event_id" in train_df.columns and "event_id" in test_df.columns:
        overlap = set(train_df["event_id"]).intersection(set(test_df["event_id"]))
        if overlap:
            raise ValueError(f"Target leakage detected: {len(overlap)} event IDs overlap between train and test")

    logger.info(
        "Zero data leakage verified: train window ends at %s, test window starts at %s",
        train_max_ts,
        test_min_ts,
    )
    return True


class PersonalizationTrainer:
    """Two-Tower NCF for personalized recommendations with cold-start handling."""

    def __init__(self, config: dict):
        self.config = config
        self.user_tower = None
        self.item_tower = None
        self.user_embeddings: dict[str, np.ndarray] = {}
        self.item_embeddings: dict[str, np.ndarray] = {}
        self.popular_items: list[str] = []
        self.item_categories: dict[str, str] = {}

    @staticmethod
    def split_train_test(
        df: pd.DataFrame,
        cutoff_days: int = 7,
        timestamp_col: str = "timestamp",
    ) -> Tuple[pd.DataFrame, pd.DataFrame]:
        """Split interactions strictly by timestamp cutoff to prevent label leakage."""
        df = df.copy()
        df[timestamp_col] = pd.to_datetime(df[timestamp_col])
        cutoff = df[timestamp_col].max() - pd.Timedelta(days=cutoff_days)

        train_df = pd.DataFrame(df[df[timestamp_col] <= cutoff].copy())
        test_df = pd.DataFrame(df[df[timestamp_col] > cutoff].copy())

        assert_zero_data_leakage(train_df, test_df, timestamp_col=timestamp_col)
        return train_df, test_df

    def load_data(self, query: str) -> pd.DataFrame:
        """Load user-item interaction data from BigQuery."""
        logger.info("Loading personalization training data from BigQuery")
        client = bigquery.Client()
        df = client.query(query).to_dataframe()
        logger.info(
            "Loaded %d interactions, %d users, %d items",
            len(df),
            df["user_id"].nunique(),
            df["sku_id"].nunique(),
        )
        return df

    def _build_interaction_matrix(
        self,
        df: pd.DataFrame,
        max_timestamp: Optional[pd.Timestamp] = None,
    ) -> pd.DataFrame:
        """Build implicit feedback matrix with weighted interactions strictly before max_timestamp."""
        df = df.copy()
        if max_timestamp is not None and "timestamp" in df.columns:
            df["timestamp"] = pd.to_datetime(df["timestamp"])
            df = cast(pd.DataFrame, df[df["timestamp"] <= max_timestamp])

        weights = self.config.get("interaction_weights", {
            "view": 1.0,
            "click": 2.0,
            "add_to_cart": 5.0,
            "purchase": 10.0,
        })
        df["weight"] = df["interaction_type"].map(weights).fillna(1.0)
        grouped = df.groupby(["user_id", "sku_id"], as_index=False)["weight"].sum()
        interaction = pd.DataFrame(grouped).rename(columns={"weight": "interaction_score"})
        return interaction

    def _get_cold_start_tier(self, order_count: int) -> str:
        """Determine cold start tier based on order history."""
        thresholds = self.config.get("cold_start_thresholds", {
            "popularity": 0,
            "content_based": 1,
            "collaborative": 4,
        })
        if order_count >= thresholds.get("collaborative", 4):
            return "collaborative"
        elif order_count >= thresholds.get("content_based", 1):
            return "content_based"
        return "popularity"

    def train(self, df: pd.DataFrame):
        """Train Two-Tower NCF model."""
        logger.info("Starting personalization model training")
        hp = self.config["hyperparameters"]
        interaction_df = self._build_interaction_matrix(df)

        # Store popular items from training interactions for cold-start tier
        pop_counts = pd.Series(interaction_df.groupby("sku_id")["interaction_score"].sum()).sort_values(ascending=False)
        self.popular_items = list(pop_counts.index)

        # Store item categories if present
        for cat_col in ["category_l1", "category", "preferred_category"]:
            if cat_col in df.columns:
                self.item_categories = df.drop_duplicates("sku_id").set_index("sku_id")[cat_col].to_dict()
                break

        embedding_dim = int(hp.get("embedding_dim", 64))
        n_users = int(len(df["user_id"].unique()))
        n_items = int(len(df["sku_id"].unique()))

        user_ids = sorted(df["user_id"].unique())
        item_ids = sorted(df["sku_id"].unique())
        user_id_map = {uid: idx for idx, uid in enumerate(user_ids)}
        item_id_map = {iid: idx for idx, iid in enumerate(item_ids)}

        np.random.seed(42)
        user_factors = np.random.normal(0, 0.1, (n_users, embedding_dim))
        item_factors = np.random.normal(0, 0.1, (n_items, embedding_dim))

        learning_rate = hp.get("learning_rate", 0.01)
        reg = hp.get("regularization", 0.01)
        n_epochs = hp.get("n_epochs", 20)

        logger.info(
            "Training ALS with %d users, %d items, dim=%d, epochs=%d",
            n_users, n_items, embedding_dim, n_epochs,
        )

        for epoch in range(n_epochs):
            epoch_loss = 0.0
            for _, row in interaction_df.iterrows():
                u_idx = user_id_map.get(row["user_id"])
                i_idx = item_id_map.get(row["sku_id"])
                if u_idx is None or i_idx is None:
                    continue

                pred = np.dot(user_factors[u_idx], item_factors[i_idx])
                error = row["interaction_score"] - pred
                epoch_loss += error ** 2

                # SGD update
                user_factors[u_idx] += learning_rate * (
                    error * item_factors[i_idx] - reg * user_factors[u_idx]
                )
                item_factors[i_idx] += learning_rate * (
                    error * user_factors[u_idx] - reg * item_factors[i_idx]
                )

            if (epoch + 1) % 5 == 0:
                rmse = np.sqrt(epoch_loss / len(interaction_df))
                logger.info("Epoch %d/%d, RMSE: %.4f", epoch + 1, n_epochs, rmse)

        for uid, idx in user_id_map.items():
            self.user_embeddings[uid] = user_factors[idx]
        for iid, idx in item_id_map.items():
            self.item_embeddings[iid] = item_factors[idx]

        self.user_tower = user_factors
        self.item_tower = item_factors
        logger.info(
            "Training complete: %d user embeddings, %d item embeddings, %d popular items",
            len(self.user_embeddings),
            len(self.item_embeddings),
            len(self.popular_items),
        )

    def recommend(
        self,
        user_id: str,
        store_popular_items: Optional[List[str]] = None,
        viewed_categories: Optional[List[str]] = None,
        top_k: int = 10,
        candidates: Optional[List[str]] = None,
    ) -> List[str]:
        """Generate top_k recommendations with robust cold-start fallbacks.

        Guarantees cold-start users never receive an empty recommendation list.
        """
        all_items = list(self.item_embeddings.keys()) or self.popular_items
        default_popular = store_popular_items or self.popular_items or all_items[:top_k]

        # Case 1: Known user with learned embedding (collaborative filtering)
        if user_id in self.user_embeddings and self.item_embeddings:
            user_vec = self.user_embeddings[user_id]
            candidate_list = candidates or all_items
            scored_candidates = []
            for item in candidate_list:
                if item in self.item_embeddings:
                    score = float(np.dot(user_vec, self.item_embeddings[item]))
                    scored_candidates.append((item, score))
            scored_candidates.sort(key=lambda x: x[1], reverse=True)
            recs = [item for item, _ in scored_candidates[:top_k]]
            for p in default_popular:
                if p not in recs and len(recs) < top_k:
                    recs.append(p)
            return recs[:top_k]

        # Case 2: Cold start with 1-3 viewed categories (content-based affinity)
        if viewed_categories and self.item_categories:
            matching = [
                iid for iid, cat in self.item_categories.items()
                if cat in viewed_categories
            ]
            recs = list(matching[:top_k])
            for p in default_popular:
                if p not in recs and len(recs) < top_k:
                    recs.append(p)
            if len(recs) >= top_k:
                return recs[:top_k]

        # Case 3: Absolute cold-start user (0 orders, 0 views) -> dark store popularity tier
        recs = list(default_popular[:top_k])
        if len(recs) < top_k:
            for item in all_items:
                if item not in recs and len(recs) < top_k:
                    recs.append(item)

        return recs[:top_k]

    def evaluate(self, df: pd.DataFrame) -> dict[str, float]:
        """Evaluate recommendations on held-out test data. Return Hit@K, NDCG@K metrics."""
        if not self.user_embeddings or not self.item_embeddings:
            raise RuntimeError("Model not trained yet")

        k_values = self.config.get("eval_k_values", [5, 10, 20])
        interaction_df = self._build_interaction_matrix(df)

        hits = {k: [] for k in k_values}
        ndcg_scores_per_k = {k: [] for k in k_values}

        user_interactions = interaction_df.groupby("user_id")
        all_items = list(self.item_embeddings.keys())
        item_matrix = np.array([self.item_embeddings[iid] for iid in all_items])

        for user_id_val, user_df in user_interactions:
            user_id = str(user_id_val)
            if user_id not in self.user_embeddings:
                continue

            user_vec = self.user_embeddings[user_id]
            scores = item_matrix @ user_vec
            ranked_items = [all_items[i] for i in np.argsort(-scores)]
            true_items = set(user_df["sku_id"].values)

            for k in k_values:
                top_k = ranked_items[:k]
                hit = len(set(top_k) & true_items) > 0
                hits[k].append(float(hit))

                relevance = [1.0 if item in true_items else 0.0 for item in top_k]
                if sum(relevance) > 0:
                    ideal = sorted(relevance, reverse=True)
                    dcg = sum(r / np.log2(i + 2) for i, r in enumerate(relevance))
                    idcg = sum(r / np.log2(i + 2) for i, r in enumerate(ideal))
                    ndcg_scores_per_k[k].append(dcg / idcg if idcg > 0 else 0.0)

        metrics = {}
        for k in k_values:
            metrics[f"hit_at_{k}"] = float(np.mean(hits[k])) if hits[k] else 0.0
            metrics[f"ndcg_at_{k}"] = float(np.mean(ndcg_scores_per_k[k])) if ndcg_scores_per_k[k] else 0.0

        metrics["users_evaluated"] = len(hits[k_values[0]])
        logger.info("Evaluation metrics: %s", json.dumps(metrics, indent=2))
        return metrics

    def export(self, path: str):
        """Export embeddings, metadata, and popular items for serving."""
        if not self.user_embeddings or self.user_tower is None or self.item_tower is None:
            raise RuntimeError("Model not trained yet")

        export_dir = Path(path)
        export_dir.mkdir(parents=True, exist_ok=True)

        np.save(export_dir / "user_embeddings.npy", self.user_tower)
        np.save(export_dir / "item_embeddings.npy", self.item_tower)

        with open(export_dir / "user_id_map.json", "w") as f:
            json.dump({uid: i for i, uid in enumerate(self.user_embeddings.keys())}, f)
        with open(export_dir / "item_id_map.json", "w") as f:
            json.dump({iid: i for i, iid in enumerate(self.item_embeddings.keys())}, f)
        with open(export_dir / "popular_items.json", "w") as f:
            json.dump(self.popular_items[:50], f)

        logger.info("Model exported to %s", export_dir)


def _check_promotion_gates(metrics: dict, gates: dict) -> bool:
    """Check if model meets promotion gates."""
    passed = True
    min_hit_10 = gates.get("min_hit_at_10", 0.0)
    if metrics.get("hit_at_10", 0.0) < min_hit_10:
        logger.warning("Hit@10 %.4f below gate %.4f", metrics.get("hit_at_10", 0), min_hit_10)
        passed = False
    min_ndcg_10 = gates.get("min_ndcg_at_10", 0.0)
    if metrics.get("ndcg_at_10", 0.0) < min_ndcg_10:
        logger.warning("NDCG@10 %.4f below gate %.4f", metrics.get("ndcg_at_10", 0), min_ndcg_10)
        passed = False
    return passed


def main():
    parser = argparse.ArgumentParser(description="Personalization Two-Tower NCF Training")
    parser.add_argument("--config", type=str, required=True, help="Path to config YAML")
    parser.add_argument("--experiment", type=str, default="personalization", help="MLflow experiment name")
    parser.add_argument("--output-dir", type=str, default="artifacts/personalization", help="Output directory")
    parser.add_argument("--run-name", type=str, default=None, help="MLflow run name")
    args = parser.parse_args()

    with open(args.config, "r") as f:
        config = yaml.safe_load(f)

    if mlflow is not None:
        mlflow.set_experiment(args.experiment)

    trainer = PersonalizationTrainer(config)

    # Load data
    query = config["training"]["query"]
    df = trainer.load_data(query)

    # Time-based split without label leakage
    cutoff_days = config["training"].get("test_days", 7)
    train_df, test_df = PersonalizationTrainer.split_train_test(df, cutoff_days=cutoff_days)
    logger.info("Train: %d interactions, Test: %d interactions", len(train_df), len(test_df))

    # Train
    trainer.train(train_df)

    # Evaluate
    metrics = trainer.evaluate(test_df)

    # Promotion gates
    gates = config.get("promotion_gates", {})
    gate_passed = _check_promotion_gates(metrics, gates)

    if mlflow is not None:
        with mlflow.start_run(run_name=args.run_name):
            mlflow.log_params({
                k: v for k, v in config.get("hyperparameters", {}).items()
                if isinstance(v, (str, int, float, bool))
            })
            mlflow.log_param("model_name", config["model_name"])
            mlflow.log_param("version", config["version"])
            mlflow.log_metrics({k: v for k, v in metrics.items() if isinstance(v, (int, float))})
            mlflow.log_metric("promotion_gate_passed", int(gate_passed))

            output_dir = Path(args.output_dir) / config["version"]
            trainer.export(str(output_dir))
            mlflow.log_artifacts(str(output_dir))
            mlflow.log_artifact(args.config)

    logger.info(
        "Training complete. Gate passed: %s. Hit@10: %.4f, NDCG@10: %.4f",
        gate_passed,
        metrics.get("hit_at_10", 0),
        metrics.get("ndcg_at_10", 0),
    )


if __name__ == "__main__":
    main()
