"""Ranking Model Predictor for search results and category feeds.

Produces a relevance score for each candidate item and returns them
sorted in descending order.  Incorporates personalisation signals,
inventory freshness, and margin-aware boosting.
"""

import json
import logging
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from .predictor import BasePredictor, ModelMetadata, ModelStatus, PredictionResult

logger = logging.getLogger(__name__)


class RankingPredictor(BasePredictor):
    """Production search & feed ranking predictor.

    Uses a LightGBM LambdaRank model exported to ONNX.  When the model
    is unavailable the predictor falls back to a weighted combination of
    popularity (order count) and recency (hours since last sold).
    """

    def __init__(
        self,
        model_name: str = "ranking",
        model_dir: str = "/opt/models/ranking",
    ) -> None:
        super().__init__(model_name=model_name, model_dir=model_dir)
        self._feature_names: List[str] = []

    # ------------------------------------------------------------------
    # Model loading
    # ------------------------------------------------------------------

    def _load_model(self, version: Optional[str]) -> None:
        """Load ranking model from ONNX or native LightGBM.

        Expected artefacts:
        * ``model.onnx`` — ONNX-exported LambdaRank model
        * ``metadata.json`` — :class:`ModelMetadata` fields
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

        self._version = resolved_version

    # ------------------------------------------------------------------
    # Prediction
    # ------------------------------------------------------------------

    def predict(self, features: Dict[str, Any]) -> PredictionResult:
        """Score and rank candidate items.

        Required features:
        * ``candidates`` — list of dicts, each containing item-level features:
            - ``item_id`` (str)
            - ``category`` (str)
            - ``order_count_7d`` (int) — orders in last 7 days
            - ``hours_since_last_sold`` (float)
            - ``avg_rating`` (float, 1-5)
            - ``margin_pct`` (float, 0-100)
            - ``in_stock`` (bool)
        * ``query`` (str, optional) — search query for text relevance
        * ``user_id`` (str, optional) — for personalisation features
        """
        if self.status != ModelStatus.READY or self._model is None:
            return self.rule_based_fallback(features)

        start = time.monotonic()
        try:
            candidates: List[Dict[str, Any]] = features.get("candidates", [])
            if not candidates:
                return self._empty_result(start)

            scored = self._score_candidates(candidates)
            ranked = sorted(scored, key=lambda x: x["score"], reverse=True)
            latency_ms = (time.monotonic() - start) * 1_000

            return PredictionResult(
                output={
                    "ranked_items": [
                        {"item_id": c["item_id"], "score": round(c["score"], 4), "rank": i + 1}
                        for i, c in enumerate(ranked)
                    ],
                    "total_candidates": len(candidates),
                },
                model_version=self._version,
                model_name=self.model_name,
                latency_ms=round(latency_ms, 2),
            )
        except Exception as exc:
            logger.error(
                "Ranking prediction error, falling back",
                extra={"model": self.model_name, "error": str(exc)},
            )
            self.status = ModelStatus.DEGRADED
            return self.rule_based_fallback(features)

    def rule_based_fallback(self, features: Dict[str, Any]) -> PredictionResult:
        """Popularity + recency heuristic ranking.

        ``score = 0.6 * norm(order_count_7d) + 0.3 * recency_score + 0.1 * avg_rating / 5``
        Items out of stock are pushed to the bottom.
        """
        start = time.monotonic()
        candidates: List[Dict[str, Any]] = features.get("candidates", [])
        if not candidates:
            return self._empty_result(start)

        max_orders = max(int(c.get("order_count_7d", 0)) for c in candidates) or 1

        scored: List[Dict[str, Any]] = []
        for c in candidates:
            order_count = int(c.get("order_count_7d", 0))
            hours_since = float(c.get("hours_since_last_sold", 168))
            avg_rating = float(c.get("avg_rating", 3.0))
            in_stock = bool(c.get("in_stock", True))

            pop_score = order_count / max_orders
            recency_score = max(0.0, 1.0 - hours_since / 168.0)
            rating_score = avg_rating / 5.0

            score = 0.6 * pop_score + 0.3 * recency_score + 0.1 * rating_score
            if not in_stock:
                score *= 0.01  # penalise out-of-stock heavily

            scored.append({"item_id": c.get("item_id", "unknown"), "score": score})

        ranked = sorted(scored, key=lambda x: x["score"], reverse=True)
        latency_ms = (time.monotonic() - start) * 1_000

        return PredictionResult(
            output={
                "ranked_items": [
                    {"item_id": c["item_id"], "score": round(c["score"], 4), "rank": i + 1}
                    for i, c in enumerate(ranked)
                ],
                "total_candidates": len(candidates),
            },
            model_version="fallback",
            model_name=self.model_name,
            latency_ms=round(latency_ms, 2),
            is_fallback=True,
        )

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _score_candidates(self, candidates: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Run ML model on each candidate and attach score."""
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
            output={"ranked_items": [], "total_candidates": 0},
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
                "Install at least one to load the ranking model."
            ) from exc
