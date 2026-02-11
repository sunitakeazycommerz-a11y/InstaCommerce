"""Personalization Predictor for user-specific product recommendations.

Combines collaborative filtering signals with real-time browsing context
to produce a per-user relevance score for candidate items.
"""

import json
import logging
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from .predictor import BasePredictor, ModelMetadata, ModelStatus, PredictionResult

logger = logging.getLogger(__name__)

# Maximum number of recommendations to return
_MAX_RECOMMENDATIONS: int = 50


class PersonalizationPredictor(BasePredictor):
    """Production personalization predictor.

    Uses a LightGBM model that combines user embeddings (from collaborative
    filtering), item features, and real-time session context.  Falls back
    to popularity-based recommendations when the model is unavailable.
    """

    def __init__(
        self,
        model_name: str = "personalization",
        model_dir: str = "/opt/models/personalization",
    ) -> None:
        super().__init__(model_name=model_name, model_dir=model_dir)
        self._feature_names: List[str] = []
        self._popular_items: List[str] = []

    # ------------------------------------------------------------------
    # Model loading
    # ------------------------------------------------------------------

    def _load_model(self, version: Optional[str]) -> None:
        """Load personalization model from ONNX or native LightGBM.

        Expected artefacts:
        * ``model.onnx`` — ONNX-exported LightGBM model
        * ``metadata.json`` — :class:`ModelMetadata` fields
        * ``popular_items.json`` — ordered list of globally popular item IDs
        """
        resolved_version = version or self._resolve_latest_version()
        model_path = self.model_dir / resolved_version

        onnx_path = model_path / "model.onnx"
        if onnx_path.exists():
            try:
                import onnxruntime as ort  # type: ignore[import-untyped]

                self._model = ort.InferenceSession(
                    str(onnx_path),
                    providers=["CPUExecutionProvider"],
                )
            except ImportError:
                logger.warning("onnxruntime not installed — attempting native LightGBM load")
                self._load_native_lightgbm(model_path)
        else:
            self._load_native_lightgbm(model_path)

        meta_path = model_path / "metadata.json"
        if meta_path.exists():
            with open(meta_path, "r") as fh:
                raw = json.load(fh)
            self.metadata = ModelMetadata(**raw)
            self._feature_names = self.metadata.features

        popular_path = model_path / "popular_items.json"
        if popular_path.exists():
            with open(popular_path, "r") as fh:
                self._popular_items = json.load(fh)

        self._version = resolved_version

    # ------------------------------------------------------------------
    # Prediction
    # ------------------------------------------------------------------

    def predict(self, features: Dict[str, Any]) -> PredictionResult:
        """Score candidate items for a specific user.

        Required features:
        * ``user_id`` (str) — customer identifier
        * ``candidates`` — list of dicts with item-level features:
            - ``item_id`` (str)
            - ``category`` (str)
            - ``price`` (float)
            - ``avg_rating`` (float)
            - ``order_count_7d`` (int)
        * ``user_features`` (dict, optional):
            - ``preferred_categories`` (list[str])
            - ``avg_basket_size`` (float)
            - ``days_since_last_order`` (int)
            - ``browse_history_categories`` (list[str])
        * ``limit`` (int, optional) — max items to return, default 20
        """
        if self.status != ModelStatus.READY or self._model is None:
            return self.rule_based_fallback(features)

        start = time.monotonic()
        try:
            candidates: List[Dict[str, Any]] = features.get("candidates", [])
            limit: int = min(int(features.get("limit", 20)), _MAX_RECOMMENDATIONS)

            if not candidates:
                return self._empty_result(start)

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
        """Popularity-based recommendations with category affinity boost.

        Score = popularity_norm + 0.3 × category_match + 0.1 × rating_norm
        """
        start = time.monotonic()
        candidates: List[Dict[str, Any]] = features.get("candidates", [])
        limit: int = min(int(features.get("limit", 20)), _MAX_RECOMMENDATIONS)

        if not candidates:
            return self._empty_result(start)

        user_features = features.get("user_features", {})
        preferred_cats: List[str] = user_features.get("preferred_categories", [])
        max_orders = max(int(c.get("order_count_7d", 0)) for c in candidates) or 1

        scored: List[Dict[str, Any]] = []
        for c in candidates:
            order_count = int(c.get("order_count_7d", 0))
            avg_rating = float(c.get("avg_rating", 3.0))
            category = str(c.get("category", ""))

            pop_score = order_count / max_orders
            cat_match = 1.0 if category in preferred_cats else 0.0
            rating_score = avg_rating / 5.0

            score = pop_score + 0.3 * cat_match + 0.1 * rating_score
            scored.append({"item_id": c.get("item_id", "unknown"), "score": score})

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

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _score_candidates(
        self,
        candidates: List[Dict[str, Any]],
        features: Dict[str, Any],
    ) -> List[Dict[str, Any]]:
        """Run ML model on each candidate."""
        import numpy as np  # type: ignore[import-untyped]

        results: List[Dict[str, Any]] = []
        for c in candidates:
            vec = np.array(
                [[float(c.get(f, 0)) for f in self._feature_names]],
                dtype=np.float32,
            )
            if hasattr(self._model, "run"):
                input_name = self._model.get_inputs()[0].name
                score = float(self._model.run(None, {input_name: vec})[0][0])
            else:
                score = float(self._model.predict(vec)[0])
            results.append({"item_id": c.get("item_id", "unknown"), "score": score})
        return results

    def _empty_result(self, start: float) -> PredictionResult:
        latency_ms = (time.monotonic() - start) * 1_000
        return PredictionResult(
            output={"user_id": None, "recommendations": [], "total_scored": 0},
            model_version=self._version,
            model_name=self.model_name,
            latency_ms=round(latency_ms, 2),
        )

    def _resolve_latest_version(self) -> str:
        if not self.model_dir.exists():
            raise FileNotFoundError(f"Model directory not found: {self.model_dir}")
        versions = sorted(
            [d.name for d in self.model_dir.iterdir() if d.is_dir()],
            reverse=True,
        )
        if not versions:
            raise FileNotFoundError(f"No model versions found in {self.model_dir}")
        return versions[0]

    def _load_native_lightgbm(self, model_path: Path) -> None:
        lgbm_path = model_path / "model.txt"
        if not lgbm_path.exists():
            raise FileNotFoundError(f"No model artefact found at {model_path}")
        try:
            import lightgbm as lgb  # type: ignore[import-untyped]

            self._model = lgb.Booster(model_file=str(lgbm_path))
        except ImportError as exc:
            raise ImportError(
                "Neither onnxruntime nor lightgbm is installed. "
                "Install at least one to load the personalization model."
            ) from exc
