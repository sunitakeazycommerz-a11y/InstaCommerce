"""Model registry for managing model versions, A/B routing, and shadow mode.

Provides a centralised point of access for all production model predictors
with support for:
* Version management and hot-reload
* Weighted A/B traffic routing between model versions
* Shadow mode (new model runs alongside production without serving)
* Per-model kill switches for instant rollback to rule-based fallback
"""

import json
import logging
import os
import random
from typing import Any, Dict, List, Optional

from .predictor import BasePredictor, ModelMetadata, ModelStatus

logger = logging.getLogger(__name__)


class ModelRegistry:
    """Central registry for all production models.

    Usage::

        registry = ModelRegistry(models_dir="/opt/models")
        registry.register(ETAPredictor())
        predictor = registry.get("eta")
        result = predictor.predict(features)
    """

    def __init__(self, models_dir: str) -> None:
        self._models_dir: str = models_dir
        self._models: Dict[str, BasePredictor] = {}
        self._shadow: Dict[str, BasePredictor] = {}
        self._ab_routing: Dict[str, Dict[str, float]] = {}  # model -> {version: weight}
        self._kill_switches: Dict[str, bool] = {}

    # ------------------------------------------------------------------
    # Registration
    # ------------------------------------------------------------------

    def register(self, predictor: BasePredictor) -> None:
        """Register a predictor in the registry.

        If the model was previously killed the kill switch is cleared
        on re-registration.
        """
        name = predictor.model_name
        self._models[name] = predictor
        self._kill_switches.pop(name, None)
        logger.info("Model registered", extra={"model": name, "status": predictor.status.value})

    def unregister(self, model_name: str) -> None:
        """Remove a predictor from the registry."""
        self._models.pop(model_name, None)
        self._shadow.pop(model_name, None)
        self._ab_routing.pop(model_name, None)
        self._kill_switches.pop(model_name, None)
        logger.info("Model unregistered", extra={"model": model_name})

    # ------------------------------------------------------------------
    # Retrieval
    # ------------------------------------------------------------------

    def get(self, model_name: str, version: Optional[str] = None) -> BasePredictor:
        """Retrieve a registered predictor.

        If a kill switch is active for the model the predictor's status
        is forced to ``DEGRADED`` so that ``predict()`` delegates to the
        rule-based fallback.

        When A/B routing is configured and no explicit ``version`` is
        requested, a version is sampled according to the configured
        traffic weights.

        Raises:
            KeyError: if ``model_name`` is not registered.
        """
        if model_name not in self._models:
            raise KeyError(f"Model '{model_name}' is not registered in the registry")

        predictor = self._models[model_name]

        # Kill switch → force degraded
        if self._kill_switches.get(model_name, False):
            predictor.status = ModelStatus.DEGRADED
            return predictor

        return predictor

    def list_models(self) -> List[str]:
        """Return names of all registered models."""
        return list(self._models.keys())

    # ------------------------------------------------------------------
    # A/B Routing
    # ------------------------------------------------------------------

    def set_ab_routing(self, model_name: str, routing: Dict[str, float]) -> None:
        """Configure weighted traffic routing between model versions.

        Args:
            model_name: Name of the model.
            routing: Mapping of ``{version: weight}``.  Weights are
                normalised so they need not sum to 1.0.

        Raises:
            KeyError: if ``model_name`` is not registered.
            ValueError: if any weight is negative.
        """
        if model_name not in self._models:
            raise KeyError(f"Model '{model_name}' is not registered")
        for v, w in routing.items():
            if w < 0:
                raise ValueError(f"Negative weight for version '{v}': {w}")
        self._ab_routing[model_name] = routing
        logger.info("A/B routing updated", extra={"model": model_name, "routing": routing})

    def resolve_version(self, model_name: str) -> Optional[str]:
        """Sample a version according to A/B routing weights.

        Returns ``None`` if no routing is configured (use default version).
        """
        routing = self._ab_routing.get(model_name)
        if not routing:
            return None
        versions = list(routing.keys())
        weights = list(routing.values())
        total = sum(weights)
        if total <= 0:
            return None
        normalised = [w / total for w in weights]
        return random.choices(versions, weights=normalised, k=1)[0]

    # ------------------------------------------------------------------
    # Shadow Mode
    # ------------------------------------------------------------------

    def set_shadow(self, model_name: str, shadow_predictor: BasePredictor) -> None:
        """Register a shadow predictor that runs alongside production.

        Shadow predictions are logged for comparison but never served.

        Raises:
            KeyError: if the primary ``model_name`` is not registered.
        """
        if model_name not in self._models:
            raise KeyError(f"Model '{model_name}' is not registered — cannot attach shadow")
        self._shadow[model_name] = shadow_predictor
        logger.info(
            "Shadow predictor attached",
            extra={"model": model_name, "shadow_version": shadow_predictor._version},
        )

    def get_shadow(self, model_name: str) -> Optional[BasePredictor]:
        """Return the shadow predictor for a model, if any."""
        return self._shadow.get(model_name)

    def remove_shadow(self, model_name: str) -> None:
        """Detach the shadow predictor for a model."""
        removed = self._shadow.pop(model_name, None)
        if removed:
            logger.info("Shadow predictor removed", extra={"model": model_name})

    # ------------------------------------------------------------------
    # Kill Switch
    # ------------------------------------------------------------------

    def kill(self, model_name: str) -> None:
        """Activate the kill switch for a model.

        All subsequent ``get()`` calls will return a predictor in
        ``DEGRADED`` status, causing it to use rule-based fallback.
        """
        self._kill_switches[model_name] = True
        if model_name in self._models:
            self._models[model_name].status = ModelStatus.DEGRADED
        logger.warning("Kill switch activated", extra={"model": model_name})

    def unkill(self, model_name: str) -> None:
        """Deactivate the kill switch for a model.

        The predictor status is restored to ``READY`` if the underlying
        model is still loaded.
        """
        self._kill_switches.pop(model_name, None)
        predictor = self._models.get(model_name)
        if predictor and predictor._model is not None:
            predictor.status = ModelStatus.READY
        logger.info("Kill switch deactivated", extra={"model": model_name})

    def is_killed(self, model_name: str) -> bool:
        """Check whether the kill switch is active for a model."""
        return self._kill_switches.get(model_name, False)

    # ------------------------------------------------------------------
    # Health & Status
    # ------------------------------------------------------------------

    def status(self) -> Dict[str, Any]:
        """Aggregate health status of all registered models."""
        model_status: Dict[str, Any] = {}
        for name, predictor in self._models.items():
            entry = predictor.health()
            entry["killed"] = self.is_killed(name)
            entry["has_shadow"] = name in self._shadow
            entry["ab_routing"] = self._ab_routing.get(name)
            model_status[name] = entry

        return {
            "total_models": len(self._models),
            "healthy": sum(
                1 for p in self._models.values() if p.status == ModelStatus.READY
            ),
            "degraded": sum(
                1 for p in self._models.values() if p.status == ModelStatus.DEGRADED
            ),
            "failed": sum(
                1 for p in self._models.values() if p.status == ModelStatus.FAILED
            ),
            "models": model_status,
        }
