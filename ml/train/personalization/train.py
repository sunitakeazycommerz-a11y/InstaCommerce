"""Personalization — Two-Tower Neural Collaborative Filtering

Recommendation surfaces: homepage, buy-again, frequently-bought-together.
Cold start strategy: popularity -> content-based -> full CF at 4+ orders.

Architecture: User tower (embedding + MLP) and Item tower (embedding + MLP)
produce 64-dim vectors. Dot product scores candidate items. Approximate
nearest neighbor (ANN) index for sub-10ms serving.
"""

from __future__ import annotations

import json
import logging
import pickle
from pathlib import Path
from typing import Any, List, Optional, Sequence, Tuple, cast

try:
    import mlflow as _mlflow
    mlflow: Optional[MlflowProtocol] = _mlflow
except (ImportError, OSError):
    mlflow = None

try:
    import torch as _torch
    torch: Any = _torch
except (ImportError, OSError):
    torch = None

import numpy as np
import pandas as pd
from google.cloud import bigquery

from ml.train.common import (
    GateRule,
    MlflowProtocol,
    build_training_parser,
    check_metric_gates,
    load_training_config,
    log_mlflow_run,
)
from ml.train.personalization.features import ITEM_FEATURES, USER_FEATURES

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

__all__ = ["ITEM_FEATURES", "PersonalizationTrainer", "USER_FEATURES", "assert_zero_data_leakage"]

_GATE_RULES = (
    GateRule("hit_at_10", "min_hit_at_10", "ge", 0.0, "Hit@10", ".4f"),
    GateRule("ndcg_at_10", "min_ndcg_at_10", "ge", 0.0, "NDCG@10", ".4f"),
)


def _extend_unique(dest: list[str], items: Sequence[str], limit: int) -> None:
    for item in items:
        if len(dest) >= limit:
            break
        if item not in dest:
            dest.append(item)


def assert_zero_data_leakage(
    train_df: pd.DataFrame,
    test_df: pd.DataFrame,
    timestamp_col: str = "timestamp",
) -> bool:
    """Verify zero temporal overlap or test label contamination in training data."""
    if len(train_df) == 0 or len(test_df) == 0:
        return True

    train_max_ts = pd.to_datetime(train_df[timestamp_col]).max()
    test_min_ts = pd.to_datetime(test_df[timestamp_col]).min()
    if train_max_ts >= test_min_ts:
        raise ValueError(
            f"Temporal label leakage detected: max train timestamp ({train_max_ts}) "
            f"is not strictly before min test timestamp ({test_min_ts})"
        )

    if "event_id" in train_df.columns and "event_id" in test_df.columns:
        overlap = set(train_df["event_id"]).intersection(set(test_df["event_id"]))
        if overlap:
            raise ValueError(f"Target leakage detected: {len(overlap)} event IDs overlap between train and test")

    logger.info("Zero data leakage verified: train ends %s, test starts %s", train_max_ts, test_min_ts)
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
        df = bigquery.Client().query(query).to_dataframe()
        logger.info("Loaded %d interactions, %d users, %d items", len(df), df["user_id"].nunique(), df["sku_id"].nunique())
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

        weights = self.config.get(
            "interaction_weights",
            {"view": 1.0, "click": 2.0, "add_to_cart": 5.0, "purchase": 10.0},
        )
        df["weight"] = df["interaction_type"].map(weights).fillna(1.0)
        grouped = df.groupby(["user_id", "sku_id"], as_index=False)["weight"].sum()
        return pd.DataFrame(grouped).rename(columns={"weight": "interaction_score"})

    def _get_cold_start_tier(self, order_count: int) -> str:
        """Determine cold start tier based on order history."""
        thresholds = self.config.get(
            "cold_start_thresholds",
            {"popularity": 0, "content_based": 1, "collaborative": 4},
        )
        if order_count >= thresholds.get("collaborative", 4):
            return "collaborative"
        if order_count >= thresholds.get("content_based", 1):
            return "content_based"
        return "popularity"

    def train(self, df: pd.DataFrame):
        """Train Two-Tower NCF model."""
        logger.info("Starting personalization model training")
        hp = self.config["hyperparameters"]
        interaction_df = self._build_interaction_matrix(df)

        pop_counts = pd.Series(interaction_df.groupby("sku_id")["interaction_score"].sum()).sort_values(ascending=False)
        self.popular_items = list(pop_counts.index)

        for cat_col in ["category_l1", "category", "preferred_category"]:
            if cat_col in df.columns:
                self.item_categories = df.drop_duplicates("sku_id").set_index("sku_id")[cat_col].to_dict()
                break

        embedding_dim = int(hp.get("embedding_dim", 64))
        user_ids = sorted(df["user_id"].unique())
        item_ids = sorted(df["sku_id"].unique())
        user_id_map = {uid: idx for idx, uid in enumerate(user_ids)}
        item_id_map = {iid: idx for idx, iid in enumerate(item_ids)}

        np.random.seed(42)
        user_factors = np.random.normal(0, 0.1, (len(user_ids), embedding_dim))
        item_factors = np.random.normal(0, 0.1, (len(item_ids), embedding_dim))

        lr = hp.get("learning_rate", 0.01)
        reg = hp.get("regularization", 0.01)
        n_epochs = hp.get("n_epochs", 20)
        logger.info("Training ALS with %d users, %d items, dim=%d, epochs=%d", len(user_ids), len(item_ids), embedding_dim, n_epochs)

        for epoch in range(n_epochs):
            epoch_loss = 0.0
            for _, row in interaction_df.iterrows():
                u_idx, i_idx = user_id_map.get(row["user_id"]), item_id_map.get(row["sku_id"])
                if u_idx is None or i_idx is None:
                    continue
                error = row["interaction_score"] - np.dot(user_factors[u_idx], item_factors[i_idx])
                epoch_loss += error ** 2
                u_prev, i_prev = user_factors[u_idx].copy(), item_factors[i_idx].copy()
                user_factors[u_idx] += lr * (error * i_prev - reg * u_prev)
                item_factors[i_idx] += lr * (error * u_prev - reg * i_prev)

            if (epoch + 1) % 5 == 0 and len(interaction_df) > 0:
                logger.info("Epoch %d/%d, RMSE: %.4f", epoch + 1, n_epochs, np.sqrt(epoch_loss / len(interaction_df)))

        self.user_embeddings = {uid: user_factors[idx] for uid, idx in user_id_map.items()}
        self.item_embeddings = {iid: item_factors[idx] for iid, idx in item_id_map.items()}
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
        """Generate top_k recommendations with cold-start fallbacks."""
        if top_k <= 0:
            return []
        all_items = list(self.item_embeddings.keys()) or self.popular_items
        default_popular = store_popular_items or self.popular_items or all_items[:top_k]

        if user_id in self.user_embeddings and self.item_embeddings:
            user_vec = self.user_embeddings[user_id]
            scored: list[tuple[str, float]] = []
            seen: set[str] = set()
            for item in candidates or all_items:
                if item in self.item_embeddings and item not in seen:
                    seen.add(item)
                    scored.append((item, float(np.dot(user_vec, self.item_embeddings[item]))))
            scored.sort(key=lambda x: x[1], reverse=True)
            recs = [item for item, _ in scored[:top_k]]
            _extend_unique(recs, default_popular, top_k)
            return recs[:top_k]

        recs: List[str] = []
        if viewed_categories and self.item_categories:
            matching = [iid for iid, cat in self.item_categories.items() if cat in viewed_categories]
            _extend_unique(recs, matching, top_k)

        _extend_unique(recs, default_popular, top_k)
        _extend_unique(recs, all_items, top_k)
        return recs[:top_k]

    def evaluate(self, df: pd.DataFrame) -> dict[str, float]:
        """Evaluate recommendations on held-out test data. Return Hit@K, NDCG@K metrics."""
        if not self.user_embeddings or not self.item_embeddings:
            raise RuntimeError("Model not trained yet")

        k_values = self.config.get("eval_k_values", [5, 10, 20])
        interaction_df = self._build_interaction_matrix(df)
        hits: dict[int, List[float]] = {k: [] for k in k_values}
        ndcg_scores_per_k: dict[int, List[float]] = {k: [] for k in k_values}

        all_items = list(self.item_embeddings.keys())
        item_matrix = np.array([self.item_embeddings[iid] for iid in all_items])

        for user_id_val, user_df in interaction_df.groupby("user_id"):
            user_id = str(user_id_val)
            if user_id not in self.user_embeddings:
                continue
            scores = np.asarray(item_matrix @ self.user_embeddings[user_id], dtype=np.float64)
            ranked_items = [all_items[i] for i in np.argsort(-scores)]
            true_items = set(user_df["sku_id"].values)
            if not true_items:
                continue
            for k in k_values:
                top_k = ranked_items[:k]
                hits[k].append(float(bool(set(top_k) & true_items)))
                dcg = sum((1.0 if item in true_items else 0.0) / np.log2(i + 2) for i, item in enumerate(top_k))
                idcg = sum(1.0 / np.log2(i + 2) for i in range(min(len(true_items), k)))
                ndcg_scores_per_k[k].append(float(dcg / idcg) if idcg > 0 else 0.0)

        metrics: dict[str, float] = {}
        for k in k_values:
            metrics[f"hit_at_{k}"] = float(np.mean(hits[k])) if hits[k] else 0.0
            metrics[f"ndcg_at_{k}"] = float(np.mean(ndcg_scores_per_k[k])) if ndcg_scores_per_k[k] else 0.0
        metrics["users_evaluated"] = len(hits[k_values[0]]) if k_values else 0
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

        user_id_map = {uid: i for i, uid in enumerate(self.user_embeddings.keys())}
        item_id_map = {iid: i for i, iid in enumerate(self.item_embeddings.keys())}
        top_popular = self.popular_items[:50]
        vocab = {"user_id_map": user_id_map, "item_id_map": item_id_map, "popular_items": top_popular}
        for fname, payload in (
            ("user_id_map.json", user_id_map),
            ("item_id_map.json", item_id_map),
            ("popular_items.json", top_popular),
            ("feature_vocab.json", vocab),
        ):
            with open(export_dir / fname, "w") as f:
                json.dump(payload, f)

        state_dict = {"user_tower": self.user_tower, "item_tower": self.item_tower}
        if torch is not None:
            torch.save(state_dict, export_dir / "model.pt")
        else:
            with open(export_dir / "model.pt", "wb") as f:
                pickle.dump(state_dict, f)
        logger.info("Model exported to %s", export_dir)


def _check_promotion_gates(metrics: dict, gates: dict) -> bool:
    """Check if model meets promotion gates."""
    passed, _ = check_metric_gates(metrics, gates, _GATE_RULES, log=logger)
    return passed


def main():
    args = build_training_parser(
        "Personalization Two-Tower NCF Training", "personalization", "artifacts/personalization"
    ).parse_args()
    config = load_training_config(args.config)

    trainer = PersonalizationTrainer(config)
    df = trainer.load_data(config["training"]["query"])

    cutoff_days = config["training"].get("test_days", 7)
    train_df, test_df = PersonalizationTrainer.split_train_test(df, cutoff_days=cutoff_days)
    logger.info("Train: %d interactions, Test: %d interactions", len(train_df), len(test_df))

    trainer.train(train_df)
    metrics = trainer.evaluate(test_df)
    gate_passed = _check_promotion_gates(metrics, config.get("promotion_gates", {}))

    output_dir = Path(args.output_dir) / config["version"]
    trainer.export(str(output_dir))

    log_mlflow_run(
        mlflow,
        experiment=args.experiment,
        run_name=args.run_name,
        params={**config.get("hyperparameters", {}), "model_name": config["model_name"], "version": config["version"]},
        metrics=metrics,
        gate_passed=gate_passed,
        artifact_dir=output_dir,
        artifact_paths=(args.config,),
    )
    logger.info(
        "Training complete. Gate passed: %s. Hit@10: %.4f, NDCG@10: %.4f",
        gate_passed,
        metrics.get("hit_at_10", 0),
        metrics.get("ndcg_at_10", 0),
    )


if __name__ == "__main__":
    main()
