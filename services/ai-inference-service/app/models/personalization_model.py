"""Personalization — Two-Tower Neural Collaborative Filtering.

Recommendation surfaces: homepage, buy-again, frequently-bought-together.
"""
from __future__ import annotations

import logging
import threading
import time
from enum import Enum
from typing import Any, Dict, List, Optional

import numpy as np
from pydantic import BaseModel, Field
from prometheus_client import Counter, Histogram

logger = logging.getLogger("ai_inference.models.personalization")

# ---------------------------------------------------------------------------
# Prometheus metrics
# ---------------------------------------------------------------------------
PERS_PREDICTIONS = Counter(
    "ml_personalization_predictions_total",
    "Total personalization predictions",
    ["surface", "model_version", "method"],
)
PERS_LATENCY = Histogram(
    "ml_personalization_latency_seconds",
    "Personalization model inference latency",
    ["surface", "model_version"],
)


# ---------------------------------------------------------------------------
# Pydantic schemas
# ---------------------------------------------------------------------------
class RecommendationSurface(str, Enum):
    HOMEPAGE = "homepage"
    BUY_AGAIN = "buy_again"
    FREQUENTLY_BOUGHT_TOGETHER = "frequently_bought_together"


class PersonalizationFeatures(BaseModel):
    """Input features for personalization."""
    user_id: str = Field(..., description="User identifier")
    surface: RecommendationSurface = Field(
        default=RecommendationSurface.HOMEPAGE, description="Recommendation surface"
    )
    # User tower features
    user_embedding: Optional[List[float]] = Field(
        default=None, description="Pre-computed user embedding (dim=64)"
    )
    user_purchase_history_ids: List[str] = Field(
        default_factory=list, description="Recent purchased product IDs"
    )
    user_category_preferences: Dict[str, float] = Field(
        default_factory=dict, description="Category -> affinity score"
    )
    user_avg_order_value: float = Field(default=0.0, ge=0, description="Avg order value")
    user_order_frequency_7d: float = Field(default=0.0, ge=0, description="Orders per week")
    # Item tower candidates
    candidate_product_ids: List[str] = Field(
        default_factory=list, description="Products to score"
    )
    candidate_embeddings: Optional[List[List[float]]] = Field(
        default=None, description="Pre-computed product embeddings (dim=64 each)"
    )
    # Context
    time_of_day_hour: int = Field(default=12, ge=0, le=23)
    context_product_id: Optional[str] = Field(
        default=None, description="Anchor product for FBT surface"
    )
    max_results: int = Field(default=20, ge=1, le=100, description="Max items to return")


class PersonalizationResponse(BaseModel):
    """Personalization result."""
    recommendations: List[Dict[str, Any]] = Field(
        default_factory=list,
        description="Ranked list of {product_id, score, rank}",
    )
    user_id: str
    surface: str
    model_version: str
    method: str = Field(default="rule_based")


# ---------------------------------------------------------------------------
# Model implementation
# ---------------------------------------------------------------------------
class PersonalizationModel:
    """Two-Tower NCF model for product recommendations.

    User tower and item tower produce embeddings; dot-product yields scores.
    Falls back to popularity-based ranking when ML model is not loaded.
    Thread-safe inference.
    """

    MODEL_NAME = "personalization"
    DEFAULT_VERSION = "pers-ncf-v1"

    def __init__(self, model_path: Optional[str] = None, version: Optional[str] = None) -> None:
        self._version = version or self.DEFAULT_VERSION
        self._model: Any = None
        self._lock = threading.Lock()
        if model_path:
            self.load_model(model_path)

    # -- Model lifecycle -----------------------------------------------------

    def load_model(self, path: str) -> None:
        """Load a serialised two-tower model."""
        try:
            import pickle
            with open(path, "rb") as f:
                model = pickle.load(f)
            with self._lock:
                self._model = model
            logger.info("Loaded personalization model from %s (version=%s)", path, self._version)
        except Exception:
            logger.exception(
                "Failed to load personalization model from %s – falling back to rule-based", path
            )

    @property
    def is_ml_loaded(self) -> bool:
        return self._model is not None

    @property
    def version(self) -> str:
        return self._version

    # -- Prediction -----------------------------------------------------------

    def predict(self, features: PersonalizationFeatures) -> PersonalizationResponse:
        """Score candidate products for a user."""
        start = time.perf_counter()
        surface = features.surface.value if isinstance(features.surface, RecommendationSurface) else features.surface
        try:
            if self._model is not None and features.user_embedding and features.candidate_embeddings:
                return self._predict_ml(features, surface)
            return self._rule_based_fallback(features, surface)
        finally:
            PERS_LATENCY.labels(surface=surface, model_version=self._version).observe(
                time.perf_counter() - start
            )

    def _predict_ml(self, features: PersonalizationFeatures, surface: str) -> PersonalizationResponse:
        """Score via dot-product of user and item embeddings."""
        user_emb = np.array(features.user_embedding, dtype=np.float64)
        item_embs = np.array(features.candidate_embeddings, dtype=np.float64)

        # Dot-product scores
        scores = item_embs @ user_emb
        scored_items = list(zip(features.candidate_product_ids, scores.tolist()))
        scored_items.sort(key=lambda x: x[1], reverse=True)
        scored_items = scored_items[: features.max_results]

        recs = [
            {"product_id": pid, "score": round(float(s), 6), "rank": rank}
            for rank, (pid, s) in enumerate(scored_items, 1)
        ]

        PERS_PREDICTIONS.labels(surface=surface, model_version=self._version, method="ml").inc()
        return PersonalizationResponse(
            recommendations=recs,
            user_id=features.user_id,
            surface=surface,
            model_version=self._version,
            method="ml",
        )

    def _rule_based_fallback(
        self, features: PersonalizationFeatures, surface: str
    ) -> PersonalizationResponse:
        """Popularity + category affinity heuristic."""
        candidates = features.candidate_product_ids or []
        cat_prefs = features.user_category_preferences or {}

        # Simple scoring: position-based decay + category bonus
        scored: List[tuple] = []
        for idx, pid in enumerate(candidates):
            score = 1.0 / (1.0 + idx * 0.1)  # position decay
            # Boost if product is in a preferred category (simple heuristic)
            for cat, affinity in cat_prefs.items():
                if cat.lower() in pid.lower():
                    score += affinity * 0.5
            scored.append((pid, score))

        # Buy-again surface: prioritise purchase history
        if surface == RecommendationSurface.BUY_AGAIN.value:
            history_set = set(features.user_purchase_history_ids)
            for i, (pid, s) in enumerate(scored):
                if pid in history_set:
                    scored[i] = (pid, s + 5.0)

        scored.sort(key=lambda x: x[1], reverse=True)
        scored = scored[: features.max_results]

        recs = [
            {"product_id": pid, "score": round(float(s), 6), "rank": rank}
            for rank, (pid, s) in enumerate(scored, 1)
        ]

        PERS_PREDICTIONS.labels(
            surface=surface, model_version=self._version, method="rule_based"
        ).inc()
        return PersonalizationResponse(
            recommendations=recs,
            user_id=features.user_id,
            surface=surface,
            model_version=self._version,
            method="rule_based",
        )

    # -- Feature importance ---------------------------------------------------

    def feature_importance(self) -> Dict[str, float]:
        """Return static feature importance weights."""
        return {
            "user_embedding": 0.40,
            "candidate_embedding": 0.40,
            "user_category_preferences": 0.10,
            "user_purchase_history": 0.10,
        }
