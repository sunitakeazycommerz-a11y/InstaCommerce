"""Base predictor interface for all models. Supports ONNX Runtime + fallback."""

import logging
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)


class ModelStatus(str, Enum):
    """Lifecycle status of a model predictor."""

    LOADING = "loading"
    READY = "ready"
    DEGRADED = "degraded"  # fallback to rules
    FAILED = "failed"


@dataclass
class PredictionResult:
    """Standardised container for every prediction response."""

    output: Dict[str, Any]
    model_version: str
    model_name: str
    latency_ms: float
    is_fallback: bool = False
    feature_importance: Optional[Dict[str, float]] = None


@dataclass
class ModelMetadata:
    """Immutable metadata loaded alongside the model artifact."""

    name: str
    version: str
    framework: str  # lightgbm, xgboost, onnx, prophet
    created_at: str
    metrics: Dict[str, float] = field(default_factory=dict)
    features: List[str] = field(default_factory=list)


class BasePredictor(ABC):
    """Base class for all model predictors.

    Every concrete predictor **must** implement:
    * ``_load_model``  – framework-specific deserialization
    * ``predict``      – main inference path (ML or fallback)
    * ``rule_based_fallback`` – deterministic fallback when model is unavailable

    The base class manages lifecycle status, timing, and health reporting.
    """

    def __init__(self, model_name: str, model_dir: str) -> None:
        self.model_name: str = model_name
        self.model_dir: Path = Path(model_dir)
        self.status: ModelStatus = ModelStatus.LOADING
        self.metadata: Optional[ModelMetadata] = None
        self._model: Any = None
        self._version: str = "unknown"

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def load(self, version: Optional[str] = None) -> bool:
        """Load model from disk.

        Returns ``True`` if the model loaded successfully.  On failure the
        predictor enters ``DEGRADED`` status and all subsequent ``predict``
        calls should delegate to ``rule_based_fallback``.
        """
        try:
            start = time.monotonic()
            self._load_model(version)
            elapsed_ms = (time.monotonic() - start) * 1_000
            self.status = ModelStatus.READY
            logger.info(
                "Model loaded",
                extra={
                    "model": self.model_name,
                    "version": self._version,
                    "load_time_ms": round(elapsed_ms, 2),
                },
            )
            return True
        except Exception as e:
            logger.error(
                "Model load failed, using fallback",
                extra={"model": self.model_name, "error": str(e)},
            )
            self.status = ModelStatus.DEGRADED
            return False

    def health(self) -> Dict[str, Any]:
        """Return a lightweight health-check payload."""
        return {
            "model": self.model_name,
            "version": self._version,
            "status": self.status.value,
        }

    # ------------------------------------------------------------------
    # Abstract interface
    # ------------------------------------------------------------------

    @abstractmethod
    def _load_model(self, version: Optional[str]) -> None:
        """Implementation-specific model loading."""

    @abstractmethod
    def predict(self, features: Dict[str, Any]) -> PredictionResult:
        """Run inference. Must handle fallback when model not loaded."""

    @abstractmethod
    def rule_based_fallback(self, features: Dict[str, Any]) -> PredictionResult:
        """Deterministic fallback when ML model unavailable."""
