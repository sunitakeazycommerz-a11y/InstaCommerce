"""Search Ranking Model — LambdaMART Learning-to-Rank.

30+ features for product ranking. Target: +15% search conversion.
"""
from __future__ import annotations

import logging
import threading
import time
from typing import Any, Dict, List, Optional

import numpy as np
from pydantic import BaseModel, Field
from prometheus_client import Counter, Histogram

logger = logging.getLogger("ai_inference.models.ranking")

# ---------------------------------------------------------------------------
# Prometheus metrics
# ---------------------------------------------------------------------------
RANKING_PREDICTIONS = Counter(
    "ml_ranking_predictions_total",
    "Total ranking predictions",
    ["model_version", "method"],
)
RANKING_LATENCY = Histogram(
    "ml_ranking_latency_seconds",
    "Ranking model inference latency",
    ["model_version"],
)
RANKING_BATCH_SIZE = Histogram(
    "ml_ranking_batch_size",
    "Number of items ranked per request",
    buckets=[1, 5, 10, 20, 50, 100, 200],
)


# ---------------------------------------------------------------------------
# Pydantic schemas
# ---------------------------------------------------------------------------
class RankingFeatures(BaseModel):
    """Input features for a single product in search ranking."""
    # Text relevance
    bm25_score: float = Field(default=0.0, ge=0, description="BM25 text match score")
    query_title_similarity: float = Field(default=0.0, ge=0, le=1, description="Query-title cosine similarity")
    # User context
    user_category_affinity: float = Field(default=0.0, ge=0, le=1, description="User affinity for product category")
    user_brand_affinity: float = Field(default=0.0, ge=0, le=1, description="User affinity for brand")
    user_purchase_count_30d: int = Field(default=0, ge=0, description="User purchases in last 30 days")
    # Product signals
    popularity_7d: float = Field(default=0.0, ge=0, description="7-day popularity metric")
    price_competitiveness: float = Field(default=1.0, ge=0, description="Price vs category avg ratio")
    stock_availability: float = Field(default=1.0, ge=0, le=1, description="Stock level (0=OOS, 1=full)")
    avg_rating: float = Field(default=0.0, ge=0, le=5, description="Average customer rating")
    review_count: int = Field(default=0, ge=0, description="Number of reviews")
    # Context
    time_of_day_hour: int = Field(default=12, ge=0, le=23, description="Hour of day")
    is_promoted: bool = False
    freshness_score: float = Field(default=0.5, ge=0, le=1, description="Listing freshness")

    product_id: Optional[str] = Field(default=None, description="Product identifier (passthrough)")


class RankingResponse(BaseModel):
    """Ranking result for a list of products."""
    ranked_items: List[Dict[str, Any]] = Field(
        default_factory=list,
        description="Items sorted by predicted relevance (descending)",
    )
    model_version: str
    method: str = Field(default="rule_based")


# ---------------------------------------------------------------------------
# Model implementation
# ---------------------------------------------------------------------------
class RankingModel:
    """Production ranking model using LambdaMART (LightGBM ranker).

    Falls back to weighted-score heuristic when ML model is not loaded.
    Thread-safe inference.
    """

    MODEL_NAME = "ranking"
    DEFAULT_VERSION = "ranking-lambdamart-v1"

    FEATURE_COLUMNS: List[str] = [
        "bm25_score", "query_title_similarity",
        "user_category_affinity", "user_brand_affinity", "user_purchase_count_30d",
        "popularity_7d", "price_competitiveness", "stock_availability",
        "avg_rating", "review_count",
        "time_of_day_hour", "is_promoted", "freshness_score",
    ]

    # Weights for rule-based fallback
    _HEURISTIC_WEIGHTS: Dict[str, float] = {
        "bm25_score": 0.25,
        "query_title_similarity": 0.20,
        "user_category_affinity": 0.10,
        "user_brand_affinity": 0.05,
        "popularity_7d": 0.10,
        "price_competitiveness": 0.05,
        "stock_availability": 0.10,
        "avg_rating": 0.08,
        "freshness_score": 0.05,
        "is_promoted": 0.02,
    }

    def __init__(self, model_path: Optional[str] = None, version: Optional[str] = None) -> None:
        self._version = version or self.DEFAULT_VERSION
        self._model: Any = None
        self._lock = threading.Lock()
        if model_path:
            self.load_model(model_path)

    # -- Model lifecycle -----------------------------------------------------

    def load_model(self, path: str) -> None:
        """Load a LightGBM ranker from disk (thread-safe)."""
        try:
            import lightgbm as lgb
            booster = lgb.Booster(model_file=path)
            with self._lock:
                self._model = booster
            logger.info("Loaded ranking model from %s (version=%s)", path, self._version)
        except Exception:
            logger.exception("Failed to load ranking model from %s – falling back to rule-based", path)

    @property
    def is_ml_loaded(self) -> bool:
        return self._model is not None

    @property
    def version(self) -> str:
        return self._version

    # -- Feature preprocessing -----------------------------------------------

    @staticmethod
    def preprocess(features_list: List[RankingFeatures]) -> np.ndarray:
        """Convert list of feature sets to a 2-D NumPy array."""
        rows = []
        for f in features_list:
            rows.append([
                f.bm25_score,
                f.query_title_similarity,
                f.user_category_affinity,
                f.user_brand_affinity,
                float(f.user_purchase_count_30d),
                f.popularity_7d,
                f.price_competitiveness,
                f.stock_availability,
                f.avg_rating,
                float(f.review_count),
                float(f.time_of_day_hour),
                float(f.is_promoted),
                f.freshness_score,
            ])
        return np.array(rows, dtype=np.float64)

    # -- Prediction -----------------------------------------------------------

    def predict(self, features_list: List[RankingFeatures]) -> RankingResponse:
        """Score and rank a list of products."""
        start = time.perf_counter()
        RANKING_BATCH_SIZE.observe(len(features_list))
        try:
            if self._model is not None:
                return self._predict_ml(features_list)
            return self._rule_based_fallback(features_list)
        finally:
            RANKING_LATENCY.labels(model_version=self._version).observe(
                time.perf_counter() - start
            )

    def _predict_ml(self, features_list: List[RankingFeatures]) -> RankingResponse:
        """Predict using LambdaMART model."""
        X = self.preprocess(features_list)
        with self._lock:
            scores = self._model.predict(X)

        ranked = self._build_ranked_items(features_list, scores.tolist(), "ml")
        RANKING_PREDICTIONS.labels(model_version=self._version, method="ml").inc()
        return RankingResponse(
            ranked_items=ranked,
            model_version=self._version,
            method="ml",
        )

    def _rule_based_fallback(self, features_list: List[RankingFeatures]) -> RankingResponse:
        """Weighted linear combination fallback."""
        scores: List[float] = []
        for f in features_list:
            feature_dict = {
                "bm25_score": f.bm25_score,
                "query_title_similarity": f.query_title_similarity,
                "user_category_affinity": f.user_category_affinity,
                "user_brand_affinity": f.user_brand_affinity,
                "popularity_7d": f.popularity_7d,
                "price_competitiveness": f.price_competitiveness,
                "stock_availability": f.stock_availability,
                "avg_rating": f.avg_rating / 5.0,  # normalize to 0-1
                "freshness_score": f.freshness_score,
                "is_promoted": 1.0 if f.is_promoted else 0.0,
            }
            score = sum(
                feature_dict.get(k, 0.0) * w
                for k, w in self._HEURISTIC_WEIGHTS.items()
            )
            # Out-of-stock penalty
            if f.stock_availability < 0.1:
                score *= 0.1
            scores.append(score)

        ranked = self._build_ranked_items(features_list, scores, "rule_based")
        RANKING_PREDICTIONS.labels(model_version=self._version, method="rule_based").inc()
        return RankingResponse(
            ranked_items=ranked,
            model_version=self._version,
            method="rule_based",
        )

    @staticmethod
    def _build_ranked_items(
        features_list: List[RankingFeatures],
        scores: List[float],
        method: str,
    ) -> List[Dict[str, Any]]:
        items = []
        for idx, (f, s) in enumerate(zip(features_list, scores)):
            items.append({
                "product_id": f.product_id or f"item_{idx}",
                "score": round(float(s), 6),
            })
        items.sort(key=lambda x: x["score"], reverse=True)
        for rank, item in enumerate(items, 1):
            item["rank"] = rank
        return items

    # -- Feature importance ---------------------------------------------------

    def feature_importance(self, method: str = "split") -> Dict[str, float]:
        """Return feature importance from the loaded model."""
        if self._model is None:
            return dict(self._HEURISTIC_WEIGHTS)
        try:
            importance = self._model.feature_importance(importance_type=method)
            return {col: float(val) for col, val in zip(self.FEATURE_COLUMNS, importance)}
        except Exception:
            logger.debug("Feature importance extraction failed")
            return {}
