"""Demand Forecasting Predictor using Prophet / ONNX.

Produces zone × category level demand forecasts for the next N hours
to drive dynamic pricing, inventory pre-positioning, and rider scheduling.
"""

import json
import logging
import time
from typing import Any, Dict, List, Optional

try:
    from prophet.serialize import model_from_json as _model_from_json
    model_from_json: Any = _model_from_json
except ImportError:
    model_from_json = None

from .predictor import BasePredictor, ModelMetadata, ModelStatus, PredictionResult

logger = logging.getLogger(__name__)

_DEFAULT_HORIZON_HOURS: int = 24


class DemandPredictor(BasePredictor):
    """Production demand-forecasting predictor.

    Primary model is Facebook Prophet serialised to JSON. Falls back to
    historical weekly averages when the model is unavailable.
    """

    HOURLY_FACTORS: Dict[int, float] = {
        0: 0.3, 1: 0.3, 2: 0.3, 3: 0.3, 4: 0.3, 5: 0.3,
        11: 1.6, 12: 1.6, 13: 1.6, 14: 1.6,
        18: 1.8, 19: 1.8, 20: 1.8, 21: 1.8,
    }

    def __init__(
        self,
        model_name: str = "demand",
        model_dir: str = "/opt/models/demand",
    ) -> None:
        super().__init__(model_name=model_name, model_dir=model_dir)
        self._weekly_baselines: Dict[str, Dict[int, float]] = {}

    def _load_model(self, version: Optional[str]) -> None:
        """Load Prophet model from serialised JSON."""
        resolved_version = version or self._resolve_latest_version()
        model_path = self.model_dir / resolved_version

        prophet_path = model_path / "model.json"
        if prophet_path.exists():
            if model_from_json is not None:
                with open(prophet_path, "r") as fh:
                    self._model = model_from_json(fh.read())
            else:
                logger.warning("prophet not installed — demand model will use fallback only")
                self._model = None
        else:
            logger.warning("Prophet model file not found at %s", prophet_path)

        meta_path = model_path / "metadata.json"
        if meta_path.exists():
            with open(meta_path, "r") as fh:
                self.metadata = ModelMetadata(**json.load(fh))

        baseline_path = model_path / "weekly_baselines.json"
        if baseline_path.exists():
            with open(baseline_path, "r") as fh:
                raw_baselines = json.load(fh)
            self._weekly_baselines = {
                k: {int(dow): v for dow, v in hourly.items()}
                for k, hourly in raw_baselines.items()
            }

        self._version = resolved_version

    def predict(self, features: Dict[str, Any]) -> PredictionResult:
        """Forecast demand for a zone × category."""
        if self.status != ModelStatus.READY or self._model is None:
            return self.rule_based_fallback(features)

        start = time.monotonic()
        try:
            horizon = max(1, self._get_int(features, "horizon_hours", _DEFAULT_HORIZON_HOURS))
            hour_of_day = self._get_int(features, "hour_of_day", 12) % 24
            hour_multiplier = self.HOURLY_FACTORS.get(hour_of_day, 1.0)
            future = self._model.make_future_dataframe(periods=horizon, freq="h")
            forecast = self._model.predict(future)

            tail = forecast.tail(horizon)
            hourly_forecast: List[Dict[str, Any]] = [
                {
                    "ds": str(row["ds"]),
                    "yhat": round(float(row["yhat"]) * self.HOURLY_FACTORS.get((hour_of_day + idx) % 24, 1.0), 1),
                    "yhat_lower": round(float(row["yhat_lower"]) * self.HOURLY_FACTORS.get((hour_of_day + idx) % 24, 1.0), 1),
                    "yhat_upper": round(float(row["yhat_upper"]) * self.HOURLY_FACTORS.get((hour_of_day + idx) % 24, 1.0), 1),
                }
                for idx, (_, row) in enumerate(tail.iterrows())
            ]
            predicted_demand = round(
                (hourly_forecast[0]["yhat"] if hourly_forecast else 50.0 * hour_multiplier),
                1,
            )

            latency_ms = (time.monotonic() - start) * 1_000
            return PredictionResult(
                output={
                    "zone_id": features.get("zone_id"),
                    "category": features.get("category"),
                    "horizon_hours": horizon,
                    "predicted_demand": predicted_demand,
                    "forecast": hourly_forecast,
                    "peak_demand": max((h["yhat"] for h in hourly_forecast), default=0.0),
                },
                model_version=self._version,
                model_name=self.model_name,
                latency_ms=round(latency_ms, 2),
            )
        except Exception as exc:
            logger.error(
                "Demand prediction error, falling back",
                extra={"model": self.model_name, "error": str(exc)},
            )
            self.status = ModelStatus.DEGRADED
            return self.rule_based_fallback(features)

    def rule_based_fallback(self, features: Dict[str, Any]) -> PredictionResult:
        """Historical weekly average demand by zone × category."""
        start = time.monotonic()
        zone_id: str = str(features.get("zone_id") or "default")
        category: str = str(features.get("category") or "default")
        day_of_week: int = self._get_int(features, "day_of_week", 0)
        hour_of_day: int = self._get_int(features, "hour_of_day", 12) % 24
        horizon: int = max(0, self._get_int(features, "horizon_hours", _DEFAULT_HORIZON_HOURS))

        baseline = self._weekly_baselines.get(f"{zone_id}_{category}", {})
        base_demand = baseline.get(day_of_week, 50.0)
        predicted_demand = round(base_demand * self.HOURLY_FACTORS.get(hour_of_day, 1.0), 1)

        hourly_forecast: List[Dict[str, Any]] = [
            {
                "hour_offset": h_offset,
                "demand": round(base_demand * self._hour_multiplier((hour_of_day + h_offset) % 24), 1),
            }
            for h_offset in range(horizon)
        ]

        latency_ms = (time.monotonic() - start) * 1_000
        return PredictionResult(
            output={
                "zone_id": zone_id,
                "category": category,
                "horizon_hours": horizon,
                "predicted_demand": predicted_demand,
                "forecast": hourly_forecast,
                "peak_demand": max((h["demand"] for h in hourly_forecast), default=0),
            },
            model_version="fallback",
            model_name=self.model_name,
            latency_ms=round(latency_ms, 2),
            is_fallback=True,
        )

    @classmethod
    def _hour_multiplier(cls, hour: int) -> float:
        """Peak-hour demand multiplier."""
        return cls.HOURLY_FACTORS.get(hour, 1.0)
