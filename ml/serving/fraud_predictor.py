"""Fraud Model Predictor with threshold-based decisions.

Produces a risk score in ``[0, 100]`` and maps it to one of three actions:
* **auto_approve** — score < 30
* **soft_review** — 30 ≤ score < 70 (queue for manual review)
* **block** — score ≥ 70
"""

import json
import logging
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from .predictor import BasePredictor, ModelMetadata, ModelStatus, PredictionResult

logger = logging.getLogger(__name__)

# Decision thresholds (score 0-100)
_THRESHOLD_AUTO_APPROVE: int = 30
_THRESHOLD_BLOCK: int = 70


class FraudPredictor(BasePredictor):
    """Production fraud-detection predictor.

    Uses an XGBoost classifier exported to ONNX.  When the model is
    unavailable the predictor falls back to rule-based velocity checks
    (order frequency, device fingerprint re-use, high-value first orders).
    """

    THRESHOLD_AUTO_APPROVE: int = _THRESHOLD_AUTO_APPROVE
    THRESHOLD_BLOCK: int = _THRESHOLD_BLOCK

    def __init__(
        self,
        model_name: str = "fraud",
        model_dir: str = "/opt/models/fraud",
    ) -> None:
        super().__init__(model_name=model_name, model_dir=model_dir)
        self._feature_names: List[str] = []

    # ------------------------------------------------------------------
    # Model loading
    # ------------------------------------------------------------------

    def _load_model(self, version: Optional[str]) -> None:
        """Load XGBoost model from ONNX or native format.

        Expected artefacts:
        * ``model.onnx`` — ONNX-exported XGBoost classifier
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
                logger.warning("onnxruntime not installed — attempting native XGBoost load")
                self._load_native_xgboost(model_path)
        else:
            self._load_native_xgboost(model_path)

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
        """Score a transaction and return a decision.

        Required features:
        * ``order_total`` — order value in local currency
        * ``user_order_count`` — lifetime order count for this user
        * ``orders_last_hour`` — number of orders placed in the last 60 min
        * ``unique_devices_30d`` — distinct device fingerprints in 30 days
        * ``payment_method`` — ``card``, ``wallet``, ``cod``
        * ``distance_to_avg_location_km`` — deviation from typical delivery address
        * ``account_age_days`` — age of the customer account
        """
        if self.status != ModelStatus.READY or self._model is None:
            return self.rule_based_fallback(features)

        start = time.monotonic()
        try:
            score = self._run_model(features)
            score = max(0.0, min(100.0, score))
            decision = self._score_to_decision(score)
            latency_ms = (time.monotonic() - start) * 1_000

            return PredictionResult(
                output={
                    "fraud_score": round(score, 1),
                    "decision": decision,
                    "threshold_auto_approve": self.THRESHOLD_AUTO_APPROVE,
                    "threshold_block": self.THRESHOLD_BLOCK,
                },
                model_version=self._version,
                model_name=self.model_name,
                latency_ms=round(latency_ms, 2),
            )
        except Exception as exc:
            logger.error(
                "Fraud prediction error, falling back",
                extra={"model": self.model_name, "error": str(exc)},
            )
            self.status = ModelStatus.DEGRADED
            return self.rule_based_fallback(features)

    def rule_based_fallback(self, features: Dict[str, Any]) -> PredictionResult:
        """Deterministic velocity-based fraud scoring.

        Rules:
        * +25 if > 3 orders in last hour
        * +20 if > 2 unique devices in 30 days
        * +15 if order total > 5000 and account age < 7 days
        * +15 if delivery address > 10 km from average location
        * +10 if first order and payment method is card
        """
        start = time.monotonic()
        score: float = 0.0

        orders_last_hour = int(features.get("orders_last_hour", 0))
        if orders_last_hour > 3:
            score += 25.0

        unique_devices = int(features.get("unique_devices_30d", 1))
        if unique_devices > 2:
            score += 20.0

        order_total = float(features.get("order_total", 0))
        account_age = int(features.get("account_age_days", 365))
        if order_total > 5000 and account_age < 7:
            score += 15.0

        distance_deviation = float(features.get("distance_to_avg_location_km", 0))
        if distance_deviation > 10.0:
            score += 15.0

        user_order_count = int(features.get("user_order_count", 1))
        payment_method = str(features.get("payment_method", "cod"))
        if user_order_count == 0 and payment_method == "card":
            score += 10.0

        score = max(0.0, min(100.0, score))
        decision = self._score_to_decision(score)
        latency_ms = (time.monotonic() - start) * 1_000

        return PredictionResult(
            output={
                "fraud_score": round(score, 1),
                "decision": decision,
                "threshold_auto_approve": self.THRESHOLD_AUTO_APPROVE,
                "threshold_block": self.THRESHOLD_BLOCK,
            },
            model_version="fallback",
            model_name=self.model_name,
            latency_ms=round(latency_ms, 2),
            is_fallback=True,
        )

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _score_to_decision(score: float) -> str:
        if score < _THRESHOLD_AUTO_APPROVE:
            return "auto_approve"
        if score >= _THRESHOLD_BLOCK:
            return "block"
        return "soft_review"

    def _run_model(self, features: Dict[str, Any]) -> float:
        """Execute ONNX or native model and return raw probability × 100."""
        import numpy as np  # type: ignore[import-untyped]

        input_array = np.array(
            [[float(features.get(f, 0)) for f in self._feature_names]],
            dtype=np.float32,
        )
        if hasattr(self._model, "run"):
            # ONNX runtime
            input_name = self._model.get_inputs()[0].name
            results = self._model.run(None, {input_name: input_array})
            proba = float(results[1][0][1])  # probability of fraud class
        else:
            # Native XGBoost Booster
            import xgboost as xgb  # type: ignore[import-untyped]

            dmat = xgb.DMatrix(input_array, feature_names=self._feature_names)
            proba = float(self._model.predict(dmat)[0])

        return proba * 100.0

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

    def _load_native_xgboost(self, model_path: Path) -> None:
        xgb_path = model_path / "model.json"
        if not xgb_path.exists():
            raise FileNotFoundError(f"No model artefact found at {model_path}")
        try:
            import xgboost as xgb  # type: ignore[import-untyped]

            self._model = xgb.Booster()
            self._model.load_model(str(xgb_path))
        except ImportError as exc:
            raise ImportError(
                "Neither onnxruntime nor xgboost is installed. "
                "Install at least one to load the fraud model."
            ) from exc
