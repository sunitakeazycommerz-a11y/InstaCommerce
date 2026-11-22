"""Customer Lifetime Value (CLV) Predictor.

Estimates the expected revenue from a customer over a configurable time
horizon (default 12 months). Used for acquisition cost budgets, loyalty
tier assignment, and personalised retention campaigns.
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

_DEFAULT_HORIZON_MONTHS: int = 12


class CLVPredictor(BasePredictor):
    """Production customer lifetime value predictor.

    Uses a LightGBM regressor exported to ONNX. Falls back to a simple
    ``avg_order_value × order_frequency × expected_lifespan`` heuristic.
    """

    def __init__(
        self,
        model_name: str = "clv",
        model_dir: str = "/opt/models/clv",
    ) -> None:
        super().__init__(model_name=model_name, model_dir=model_dir)
        self._feature_names: List[str] = []

    def _load_model(self, version: Optional[str]) -> None:
        """Load CLV model from ONNX or native LightGBM."""
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
                raw = json.load(fh)
            self.metadata = ModelMetadata(**raw)
            self._feature_names = self.metadata.features

        self._version = resolved_version

    def predict(self, features: Dict[str, Any]) -> PredictionResult:
        """Predict customer lifetime value."""
        if self.status != ModelStatus.READY or self._model is None:
            return self.rule_based_fallback(features)

        start = time.monotonic()
        try:
            horizon = max(1, self._get_int(features, "horizon_months", _DEFAULT_HORIZON_MONTHS))
            vec = np.array(
                [[self._get_float(features, f, 0.0) for f in self._feature_names]],
                dtype=np.float32,
            )

            if hasattr(self._model, "run"):
                input_name = self._model.get_inputs()[0].name
                res = self._model.run(None, {input_name: vec})
                raw_clv = float(np.asarray(res[0]).reshape(-1)[0])
            else:
                raw_clv = float(np.asarray(self._model.predict(vec)).reshape(-1)[0])

            # Model is trained on a 12-month window; scale to requested horizon.
            clv = max(0.0, raw_clv * (horizon / 12.0))
            tier = self._assign_tier(clv)
            latency_ms = (time.monotonic() - start) * 1_000

            return PredictionResult(
                output={
                    "user_id": features.get("user_id"),
                    "predicted_clv": round(clv, 2),
                    "horizon_months": horizon,
                    "tier": tier,
                    "churn_risk": self._churn_risk(features),
                },
                model_version=self._version,
                model_name=self.model_name,
                latency_ms=round(latency_ms, 2),
            )
        except Exception as exc:
            logger.error(
                "CLV prediction error, falling back",
                extra={"model": self.model_name, "error": str(exc)},
            )
            self.status = ModelStatus.DEGRADED
            return self.rule_based_fallback(features)

    def rule_based_fallback(self, features: Dict[str, Any]) -> PredictionResult:
        """Heuristic CLV: avg_order_value × monthly_frequency × horizon."""
        start = time.monotonic()
        avg_order_value = self._get_float(features, "avg_order_value", 0.0)
        total_orders = self._get_int(features, "total_orders", 0)
        account_age_days = max(self._get_int(features, "account_age_days", 1), 1)
        horizon = max(1, self._get_int(features, "horizon_months", _DEFAULT_HORIZON_MONTHS))
        orders_last_30d = features.get("orders_last_30d")

        if orders_last_30d is not None:
            monthly_freq = float(orders_last_30d)
        else:
            account_age_months = max(account_age_days / 30.0, 1.0)
            monthly_freq = total_orders / account_age_months

        clv = max(0.0, avg_order_value * monthly_freq * horizon)
        tier = self._assign_tier(clv)
        latency_ms = (time.monotonic() - start) * 1_000

        return PredictionResult(
            output={
                "user_id": features.get("user_id"),
                "predicted_clv": round(clv, 2),
                "horizon_months": horizon,
                "tier": tier,
                "churn_risk": self._churn_risk(features),
            },
            model_version="fallback",
            model_name=self.model_name,
            latency_ms=round(latency_ms, 2),
            is_fallback=True,
        )

    @staticmethod
    def _assign_tier(clv: float) -> str:
        """Map predicted CLV to a loyalty tier."""
        if clv >= 50_000:
            return "platinum"
        if clv >= 20_000:
            return "gold"
        if clv >= 5_000:
            return "silver"
        return "bronze"

    @staticmethod
    def _churn_risk(features: Dict[str, Any]) -> str:
        """Simple rule-based churn risk indicator."""
        days_since = BasePredictor._get_int(features, "days_since_last_order", 0)
        if days_since > 60:
            return "high"
        if days_since > 30:
            return "medium"
        return "low"

    def _load_native_lightgbm(self, model_path: Path) -> None:
        lgbm_path = model_path / "model.txt"
        if not lgbm_path.exists():
            raise FileNotFoundError(f"No model artefact found at {model_path}")
        if lgb is None:
            raise ImportError(
                "Neither onnxruntime nor lightgbm is installed. "
                "Install at least one to load the CLV model."
            )
        self._model = lgb.Booster(model_file=str(lgbm_path))
