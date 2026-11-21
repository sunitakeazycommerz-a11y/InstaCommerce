"""Demand Forecasting Predictor using Prophet / ONNX.

Produces zone × category level demand forecasts for the next N hours
to drive dynamic pricing, inventory pre-positioning, and rider scheduling.
"""

import json
import logging
import time
from typing import Any, Dict, List, Optional

try:
    from prophet.serialize import model_from_json as _model_from_json  # type: ignore[import-untyped]
    model_from_json: Any = _model_from_json
except ImportError:
    model_from_json: Any = None

from .predictor import BasePredictor, ModelMetadata, ModelStatus, PredictionResult

logger = logging.getLogger(__name__)

# Default forecast horizon
_DEFAULT_HORIZON_HOURS: int = 24


class DemandPredictor(BasePredictor):
    """Production demand-forecasting predictor.

    Primary model is Facebook Prophet serialised to JSON.  Falls back to
    historical weekly averages when the model is unavailable.
    """

    HOURLY_FACTORS: Dict[int, float] = {
        0: 0.3,
        1: 0.3,
        2: 0.3,
        3: 0.3,
        4: 0.3,
        5: 0.3,
        11: 1.6,
        12: 1.6,
        13: 1.6,
        14: 1.6,
        18: 1.8,
        19: 1.8,
        20: 1.8,
        21: 1.8,
    }

    def __init__(
        self,
        model_name: str = "demand",
        model_dir: str = "/opt/models/demand",
    ) -> None:
        super().__init__(model_name=model_name, model_dir=model_dir)
        self._weekly_baselines: Dict[str, Dict[int, float]] = {}

    # ------------------------------------------------------------------
    # Model loading
    # ------------------------------------------------------------------

    def _load_model(self, version: Optional[str]) -> None:
        """Load Prophet model from serialised JSON.

        Expected artefacts:
        * ``model.json`` — Prophet model serialised via ``model_to_json``
        * ``metadata.json`` — :class:`ModelMetadata` fields
        * ``weekly_baselines.json`` — ``{zone_category: {day_of_week: avg_orders}}``
        """
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
                raw = json.load(fh)
            self.metadata = ModelMetadata(**raw)

        baseline_path = model_path / "weekly_baselines.json"
        if baseline_path.exists():
            with open(baseline_path, "r") as fh:
                raw_baselines = json.load(fh)
            # Convert string keys to int (day of week)
            self._weekly_baselines = {
                k: {int(dow): v for dow, v in hourly.items()}
                for k, hourly in raw_baselines.items()
            }

        self._version = resolved_version

    # ------------------------------------------------------------------
    # Prediction
    # ------------------------------------------------------------------

    def predict(self, features: Dict[str, Any]) -> PredictionResult:
        """Forecast demand for a zone × category.

        Required features:
        * ``zone_id`` (str) — delivery zone
        * ``category`` (str) — product category
        * ``horizon_hours`` (int, optional) — forecast horizon, default 24
        * ``day_of_week`` (int) — 0=Monday … 6=Sunday
        * ``hour_of_day`` (int) — 0-23
        """
        if self.status != ModelStatus.READY or self._model is None:
            return self.rule_based_fallback(features)

        start = time.monotonic()
        try:
            raw_horizon = features.get("horizon_hours")
            horizon = max(1, int(raw_horizon if raw_horizon is not None else _DEFAULT_HORIZON_HOURS))
            raw_hour = features.get("hour_of_day")
            hour_of_day = int(raw_hour if raw_hour is not None else 12) % 24
            hour_multiplier = self.HOURLY_FACTORS.get(hour_of_day, 1.0)
            future = self._model.make_future_dataframe(periods=horizon, freq="h")
            forecast = self._model.predict(future)

            # Extract tail forecast rows
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
        """Historical weekly average demand by zone × category.

        Falls back to a global average of 50 orders/hour if no baseline
        data is available for the given zone × category.
        """
        start = time.monotonic()
        zone_id: str = str(features.get("zone_id") or "default")
        category: str = str(features.get("category") or "default")
        raw_dow = features.get("day_of_week")
        day_of_week: int = int(raw_dow if raw_dow is not None else 0)
        raw_hour = features.get("hour_of_day")
        hour_of_day: int = int(raw_hour if raw_hour is not None else 12) % 24
        raw_horizon = features.get("horizon_hours")
        horizon: int = max(0, int(raw_horizon if raw_horizon is not None else _DEFAULT_HORIZON_HOURS))

        key = f"{zone_id}_{category}"
        baseline = self._weekly_baselines.get(key, {})
        base_demand = baseline.get(day_of_week, 50.0)

        # Hour-of-day multiplier (peak lunch/dinner)
        hour_multiplier = self.HOURLY_FACTORS.get(hour_of_day, 1.0)
        predicted_demand = round(base_demand * hour_multiplier, 1)

        hourly_forecast: List[Dict[str, Any]] = []
        for h_offset in range(horizon):
            hour = (hour_of_day + h_offset) % 24
            demand = base_demand * self._hour_multiplier(hour)
            hourly_forecast.append({
                "hour_offset": h_offset,
                "demand": round(demand, 1),
            })

        latency_ms = (time.monotonic() - start) * 1_000
        return PredictionResult(
            output={
                "zone_id": zone_id,
                "category": category,
                "horizon_hours": horizon,
                "predicted_demand": predicted_demand,
                "forecast": hourly_forecast,
                "peak_demand": max(h["demand"] for h in hourly_forecast) if hourly_forecast else 0,
            },
            model_version="fallback",
            model_name=self.model_name,
            latency_ms=round(latency_ms, 2),
            is_fallback=True,
        )

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    @classmethod
    def _hour_multiplier(cls, hour: int) -> float:
        """Peak-hour demand multiplier."""
        return cls.HOURLY_FACTORS.get(hour, 1.0)

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
