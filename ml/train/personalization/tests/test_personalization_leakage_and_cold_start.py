"""Unit tests for Personalization target leakage prevention and cold-start fallback."""

import datetime
import pandas as pd
import pytest
from ml.serving.personalization_predictor import PersonalizationPredictor
from ml.train.personalization.train import (
    PersonalizationTrainer,
    assert_zero_data_leakage,
)


def test_split_train_test_zero_label_leakage():
    # 20 days of interaction data
    base_time = pd.Timestamp("2026-10-01 00:00:00")
    records = []
    for day in range(20):
        records.append({
            "event_id": f"evt_{day}",
            "user_id": f"user_{day % 3}",
            "sku_id": f"sku_{day % 5}",
            "interaction_type": "purchase" if day % 2 == 0 else "view",
            "timestamp": base_time + pd.Timedelta(days=day),
        })
    df = pd.DataFrame(records)

    train_df, test_df = PersonalizationTrainer.split_train_test(df, cutoff_days=7)

    assert len(train_df) > 0
    assert len(test_df) > 0
    assert train_df["timestamp"].max() < test_df["timestamp"].min()
    assert set(train_df["event_id"]).isdisjoint(set(test_df["event_id"]))


def test_leakage_detection_raises_on_overlapping_timestamps():
    train_df = pd.DataFrame([{"timestamp": "2026-10-15 10:00:00"}])
    test_df = pd.DataFrame([{"timestamp": "2026-10-14 10:00:00"}])

    with pytest.raises(ValueError, match="Temporal label leakage detected"):
        assert_zero_data_leakage(train_df, test_df)


def test_cold_start_tier_fallback_returns_exact_10_recommendations():
    config = {
        "hyperparameters": {"embedding_dim": 16, "n_epochs": 2},
        "cold_start_thresholds": {"popularity": 0, "content_based": 1, "collaborative": 4},
    }
    trainer = PersonalizationTrainer(config)

    # 10 popular dark store items
    store_popular = [f"sku_top_{i}" for i in range(1, 15)]

    # Brand new customer (user_cold_123, 0 orders, no embeddings)
    recs = trainer.recommend(
        user_id="user_cold_123",
        store_popular_items=store_popular,
        top_k=10,
    )

    assert len(recs) == 10
    assert recs == store_popular[:10]


def test_cold_start_blends_viewed_categories():
    config = {
        "hyperparameters": {"embedding_dim": 16, "n_epochs": 2},
        "cold_start_thresholds": {"popularity": 0, "content_based": 1, "collaborative": 4},
    }
    trainer = PersonalizationTrainer(config)
    trainer.popular_items = [f"sku_pop_{i}" for i in range(1, 15)]
    trainer.item_categories = {
        "sku_pop_1": "dairy",
        "sku_pop_2": "beverages",
        "sku_pop_3": "dairy",
        "sku_pop_4": "snacks",
    }

    # User viewed dairy category
    recs = trainer.recommend(
        user_id="user_cold_dairy",
        viewed_categories=["dairy"],
        top_k=10,
    )

    assert len(recs) == 10
    assert recs[0] in ["sku_pop_1", "sku_pop_3"]  # Dairy items prioritized


def test_personalization_predictor_cold_start_returns_recommendations():
    predictor = PersonalizationPredictor(model_dir="/tmp")
    predictor._popular_items = [f"sku_store_{i}" for i in range(1, 15)]

    # Cold start customer request with empty candidates
    result = predictor.predict({
        "user_id": "new_user_999",
        "candidates": [],
        "limit": 10,
    })

    assert result is not None
    recs = result.output.get("recommendations", [])
    assert len(recs) == 10
    assert recs[0]["item_id"] == "sku_store_1"
