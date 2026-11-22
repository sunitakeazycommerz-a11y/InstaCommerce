"""Personalization Predictor for user-specific product recommendations.

Combines collaborative filtering signals with real-time browsing context
to produce a per-user relevance score for candidate items.
"""

import json
import logging
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np

try:
    import onnxruntime as _ort
    ort: Any = _ort
except ImportError:
    ort = None

try:
    import lightgbm as _lgb
    lgb: Any = _lgb
except (ImportError, OSError):
    lgb = None

from .predictor import BasePredictor, ModelMetadata, ModelStatus, PredictionResult

logger = logging.getLogger(__name__)

_MAX_RECOMMENDATIONS: int = 50


class PersonalizationPredictor(BasePredictor):
    """Production personalization predictor.

    Uses a LightGBM model combining user embeddings, item features, and
    real-time session context, falling back to popularity ranking.
    """

    def __init__(
        self,
        model_name: str = "personalization",
        model_dir: str = "/opt/models/personalization",
    ) -> None:
        super().__init__(model_name=model_name, model_dir=model_dir)
        self._feature_names: List[str] = []
        self._popular_items: List[str] = []

    def _load_model(self, version: Optional[str]) -> None:
        """Load personalization model from ONNX or native LightGBM."""
        resolved_version = version or self._resolve_latest_version()
        model_path = self.model_dir / resolved_version

        onnx_path = model_path / "model.onnx"
        if onnx_path.exists():
            if ort is not None:
                self._model = ort.InferenceSession(
                    str(onnx_path),
                    providers=["CPUExecutionProvider"],
                )
            else:
                logger.warning("onnxruntime not installed — attempting native LightGBM load")
                self._load_native_lightgbm(model_path)
        else:
            self._load_native_lightgbm(model_path)

        meta_path = model_path / "metadata.json"
        if meta_path.exists():
            with open(meta_path, "r") as fh:
                self.metadata = ModelMetadata(**json.load(fh))
            self._feature_names = self.metadata.features

        popular_path = model_path / "popular_items.json"
        if popular_path.exists():
            with open(popular_path, "r") as fh:
                self._popular_items = json.load(fh)

        self._version = resolved_version

    def predict(self, features: Dict[str, Any]) -> PredictionResult:
        """Score candidate items for a specific user."""
        if self.status != ModelStatus.READY or self._model is None:
            return self.rule_based_fallback(features)

        start = time.monotonic()
        try:
            candidates: List[Dict[str, Any]] = list(features.get("candidates") or [])
            limit: int = max(0, min(self._get_int(features, "limit", 20), _MAX_RECOMMENDATIONS))

            if not candidates:
                return self.rule_based_fallback(features)

            scored = self._score_candidates(candidates, features)
            ranked = sorted(scored, key=lambda x: x["score"], reverse=True)[:limit]
            latency_ms = (time.monotonic() - start) * 1_000

            return PredictionResult(
                output={
                    "user_id": features.get("user_id"),
                    "recommendations": [
                        {"item_id": c["item_id"], "score": round(c["score"], 4), "rank": i + 1}
                        for i, c in enumerate(ranked)
                    ],
                    "total_scored": len(candidates),
                },
                model_version=self._version,
                model_name=self.model_name,
                latency_ms=round(latency_ms, 2),
            )
        except Exception as exc:
            logger.error(
                "Personalization prediction error, falling back",
                extra={"model": self.model_name, "error": str(exc)},
            )
            self.status = ModelStatus.DEGRADED
            return self.rule_based_fallback(features)

    def rule_based_fallback(self, features: Dict[str, Any]) -> PredictionResult:
        """Popularity-based recommendations with category affinity boost."""
        start = time.monotonic()
        candidates: List[Dict[str, Any]] = list(features.get("candidates") or [])
        limit: int = max(0, min(self._get_int(features, "limit", 20), _MAX_RECOMMENDATIONS))
        raw_user_features = features.get("user_features")
        user_features: Dict[str, Any] = raw_user_features if isinstance(raw_user_features, dict) else {}
        preferred_cats: List[str] = list(
            user_features.get("preferred_categories")
            or user_features.get("browse_history_categories")
            or []
        )

        if not candidates:
            popular_source = features.get("store_popular_items") or self._popular_items
            if not popular_source:
                popular_source = [f"sku_popular_{i}" for i in range(1, 21)]
            top_pref_cat = preferred_cats[0] if preferred_cats else "grocery"
            candidates = [
                {
                    "item_id": iid,
                    "order_count_7d": max(1, len(popular_source) - idx),
                    "category": top_pref_cat if idx < 3 and preferred_cats else "grocery",
                    "avg_rating": 4.5,
                }
                for idx, iid in enumerate(popular_source)
            ]

        max_orders = max(
            max((self._get_int(c, "order_count_7d", 0) for c in candidates), default=0),
            1,
        )

        scored: List[Dict[str, Any]] = []
        for c in candidates:
            order_count = self._get_int(c, "order_count_7d", 0)
            avg_rating = self._get_float(c, "avg_rating", 3.0)
            category = str(c.get("category") or "")

            score = (order_count / max_orders) + 0.3 * (1.0 if category in preferred_cats else 0.0) + 0.1 * (avg_rating / 5.0)
            scored.append({"item_id": c.get("item_id") or "unknown", "score": score})

        ranked = sorted(scored, key=lambda x: x["score"], reverse=True)[:limit]
        latency_ms = (time.monotonic() - start) * 1_000

        return PredictionResult(
            output={
                "user_id": features.get("user_id"),
                "recommendations": [
                    {"item_id": c["item_id"], "score": round(c["score"], 4), "rank": i + 1}
                    for i, c in enumerate(ranked)
                ],
                "total_scored": len(candidates),
            },
            model_version="fallback",
            model_name=self.model_name,
            latency_ms=round(latency_ms, 2),
            is_fallback=True,
        )

    def _score_candidates(
        self,
        candidates: List[Dict[str, Any]],
        features: Dict[str, Any],
    ) -> List[Dict[str, Any]]:
        """Run ML model on each candidate."""
        del features
        results: List[Dict[str, Any]] = []
        for c in candidates:
            vec = np.array(
                [[self._get_float(c, f, 0.0) for f in self._feature_names]],
                dtype=np.float32,
            )
            if hasattr(self._model, "run"):
                input_name = self._model.get_inputs()[0].name
                res = self._model.run(None, {input_name: vec})
                score = float(np.asarray(res[0]).reshape(-1)[0])
            else:
                score = float(np.asarray(self._model.predict(vec)).reshape(-1)[0])
            results.append({"item_id": c.get("item_id") or "unknown", "score": score})
        return results

    def _empty_result(self, start: float) -> PredictionResult:
        latency_ms = (time.monotonic() - start) * 1_000
        return PredictionResult(
            output={"user_id": None, "recommendations": [], "total_scored": 0},
            model_version=self._version,
            model_name=self.model_name,
            latency_ms=round(latency_ms, 2),
        )

    def _load_native_lightgbm(self, model_path: Path) -> None:
        lgbm_path = model_path / "model.txt"
        if not lgbm_path.exists():
            raise FileNotFoundError(f"No model artefact found at {model_path}")
        if lgb is None:
            raise ImportError(
                "Neither onnxruntime nor lightgbm is installed. "
                "Install at least one to load the personalization model."
            )
        self._model = lgb.Booster(model_file=str(lgbm_path))
