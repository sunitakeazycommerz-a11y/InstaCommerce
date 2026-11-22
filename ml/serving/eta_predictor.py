"""ETA Model Predictor with three-stage prediction.

Stage 1 (pre-order):  zone-level average with time-of-day adjustment.
Stage 2 (post-assign): rider-specific model with live traffic data.
Stage 3 (in-transit):  remaining distance / speed model.
"""

import json
import logging
import math
import time
from pathlib import Path
from typing import Any, Dict, Optional

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

_ETA_MIN: float = 1.0
_ETA_MAX: float = 120.0
_DEFAULT_ZONE_ETA: float = 25.0
_TRAFFIC_MULTIPLIERS: Dict[int, float] = {0: 1.0, 1: 1.2, 2: 1.5, 3: 2.0}


class ETAPredictor(BasePredictor):
    """Production three-stage ETA predictor."""

    def __init__(self, model_name: str = "eta", model_dir: str = "/opt/models/eta") -> None:
        super().__init__(model_name=model_name, model_dir=model_dir)
        self._zone_averages: Dict[str, float] = {}

    def _load_model(self, version: Optional[str]) -> None:
        """Load LightGBM model from ONNX or native format."""
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

        zone_path = model_path / "zone_averages.json"
        if zone_path.exists():
            with open(zone_path, "r") as fh:
                self._zone_averages = json.load(fh)

        self._version = resolved_version

    def predict(self, features: Dict[str, Any]) -> PredictionResult:
        """Run ETA inference."""
        if self.status != ModelStatus.READY or self._model is None:
            return self.rule_based_fallback(features)

        start = time.monotonic()
        try:
            stage = features.get("stage", "pre_order")
            eta = self._clamp(self._predict_by_stage(stage, features))
            latency_ms = (time.monotonic() - start) * 1_000
            confidence = self._compute_confidence(stage, features)

            return PredictionResult(
                output={
                    "eta_minutes": round(eta, 1),
                    "stage": stage,
                    "confidence": round(confidence, 3),
                    "lower_bound_minutes": round(max(_ETA_MIN, eta * 0.8), 1),
                    "upper_bound_minutes": round(min(_ETA_MAX, eta * 1.2), 1),
                },
                model_version=self._version,
                model_name=self.model_name,
                latency_ms=round(latency_ms, 2),
            )
        except Exception as exc:
            logger.error(
                "ETA prediction error, falling back",
                extra={"model": self.model_name, "error": str(exc)},
            )
            self.status = ModelStatus.DEGRADED
            return self.rule_based_fallback(features)

    def rule_based_fallback(self, features: Dict[str, Any]) -> PredictionResult:
        """Deterministic ETA: distance_km * 3 + item_count * 0.5 + traffic * 2."""
        start = time.monotonic()
        distance_km = self._get_float(features, "distance_km", 3.0)
        item_count = self._get_int(features, "item_count", 1)
        traffic_level = self._get_int(features, "traffic_level", 0)

        eta = self._clamp(distance_km * 3.0 + item_count * 0.5 + traffic_level * 2.0)
        latency_ms = (time.monotonic() - start) * 1_000

        return PredictionResult(
            output={
                "eta_minutes": round(eta, 1),
                "stage": features.get("stage") or "pre_order",
                "confidence": 0.5,
                "lower_bound_minutes": round(max(_ETA_MIN, eta * 0.7), 1),
                "upper_bound_minutes": round(min(_ETA_MAX, eta * 1.4), 1),
            },
            model_version="fallback",
            model_name=self.model_name,
            latency_ms=round(latency_ms, 2),
            is_fallback=True,
        )

    def _predict_by_stage(self, stage: str, features: Dict[str, Any]) -> float:
        """Dispatch prediction to the appropriate stage handler."""
        if stage == "in_transit":
            return self._stage3_in_transit(features)
        if stage == "post_assign":
            return self._stage2_post_assign(features)
        return self._stage1_pre_order(features)

    def _stage1_pre_order(self, features: Dict[str, Any]) -> float:
        """Zone-level average with peak-hour adjustment."""
        zone_id = str(features.get("zone_id") or "default")
        base_eta = self._zone_averages.get(zone_id, _DEFAULT_ZONE_ETA)
        hour = self._get_int(features, "hour_of_day", 12) % 24
        if hour in range(12, 15) or hour in range(19, 22):
            base_eta *= 1.15
        return base_eta

    def _stage2_post_assign(self, features: Dict[str, Any]) -> float:
        """Rider-specific with live traffic."""
        distance_km = self._get_float(features, "distance_km", 3.0)
        rider_speed = self._get_float(features, "rider_avg_speed_kmh", 15.0)
        traffic = self._get_int(features, "traffic_level", 0)
        multiplier = _TRAFFIC_MULTIPLIERS.get(traffic, 1.0)
        return (distance_km / max(rider_speed, 1.0)) * 60.0 * multiplier

    def _stage3_in_transit(self, features: Dict[str, Any]) -> float:
        """Remaining distance / current speed."""
        remaining_km = self._get_float(features, "remaining_distance_km", 1.0)
        current_speed = self._get_float(features, "current_speed_kmh", 12.0)
        return (remaining_km / max(current_speed, 1.0)) * 60.0

    @staticmethod
    def _compute_confidence(stage: str, features: Dict[str, Any]) -> float:
        """Heuristic confidence score that increases as more data is available."""
        base = {"pre_order": 0.6, "post_assign": 0.75, "in_transit": 0.9}.get(stage, 0.5)
        if features.get("traffic_level") is not None:
            base += 0.05
        return min(base, 1.0)

    @staticmethod
    def _clamp(eta: float) -> float:
        if not math.isfinite(eta):
            return _DEFAULT_ZONE_ETA
        return max(_ETA_MIN, min(_ETA_MAX, eta))

    def _load_native_lightgbm(self, model_path: Path) -> None:
        """Fallback loader using native LightGBM Booster."""
        lgbm_path = model_path / "model.txt"
        if not lgbm_path.exists():
            raise FileNotFoundError(f"No model artefact found at {model_path}")
        if lgb is None:
            raise ImportError(
                "Neither onnxruntime nor lightgbm is installed. "
                "Install at least one to load the ETA model."
            )
        self._model = lgb.Booster(model_file=str(lgbm_path))
