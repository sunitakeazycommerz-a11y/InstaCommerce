"""Model registry for managing model versions, A/B routing, and shadow mode.

Provides a centralised point of access for all production model predictors
with support for:
* Version management and hot-reload
* Weighted A/B traffic routing between model versions
* Shadow mode (new model runs alongside production without serving)
* Per-model kill switches for instant rollback to rule-based fallback
"""

import copy
import hashlib
import logging
import random
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

from .integrity import CHECKSUM_FILENAME
from .predictor import BasePredictor, ModelStatus

logger = logging.getLogger(__name__)


class ABTestRouter:
    """Deterministic A/B traffic router using (user_id, model_name) hashing."""

    def __init__(self, routing: Optional[Dict[str, Dict[str, float]]] = None) -> None:
        self._routing: Dict[str, Dict[str, float]] = routing or {}

    def set_variants(self, model_name: str, variants: Dict[str, float]) -> None:
        for v, w in variants.items():
            if w < 0:
                raise ValueError(f"Negative weight for variant '{v}': {w}")
        self._routing[model_name] = dict(variants)

    def route(
        self,
        user_id: str,
        model_name: str,
        variants: Optional[Dict[str, float]] = None,
    ) -> Optional[str]:
        weights_map = variants if variants is not None else self._routing.get(model_name)
        if not weights_map:
            return None
        total = sum(weights_map.values())
        if total <= 0:
            return None
        digest = hashlib.sha256(f"{user_id}:{model_name}".encode("utf-8")).digest()
        bucket = int.from_bytes(digest[:8], "big") / float(1 << 64)
        cumulative = 0.0
        chosen: Optional[str] = None
        for variant, weight in weights_map.items():
            chosen = variant
            cumulative += weight / total
            if bucket < cumulative:
                return variant
        return chosen


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
        self._versioned_predictors: Dict[str, Dict[str, BasePredictor]] = {}
        self._shadow: Dict[str, BasePredictor] = {}
        self._ab_routing: Dict[str, Dict[str, float]] = {}  # model -> {version: weight}
        self._kill_switches: Dict[str, bool] = {}
        self._previous_versions: Dict[str, str] = {}
        self._version_history: Dict[str, List[str]] = {}
        self._lock = threading.RLock()

    # ------------------------------------------------------------------
    # Registration
    # ------------------------------------------------------------------

    def register(self, predictor: BasePredictor) -> None:
        """Register a predictor in the registry.

        If the model was previously killed the kill switch is cleared
        on re-registration.
        """
        with self._lock:
            name = predictor.model_name
            existing = self._models.get(name)
            if existing is not None and existing._version and existing._version != predictor._version:
                self._previous_versions[name] = existing._version
            self._models[name] = predictor
            version_cache: Dict[str, BasePredictor] = {}
            if predictor._version and predictor._version != "unknown":
                version_cache[predictor._version] = predictor
            self._versioned_predictors[name] = version_cache
            self._kill_switches.pop(name, None)
            if predictor._version:
                history = self._version_history.setdefault(name, [])
                if not history or history[-1] != predictor._version:
                    history.append(predictor._version)
        logger.info("Model registered", extra={"model": name, "status": predictor.status.value})

    def unregister(self, model_name: str) -> None:
        """Remove a predictor from the registry."""
        with self._lock:
            self._models.pop(model_name, None)
            self._versioned_predictors.pop(model_name, None)
            self._shadow.pop(model_name, None)
            self._ab_routing.pop(model_name, None)
            self._kill_switches.pop(model_name, None)
            self._previous_versions.pop(model_name, None)
            self._version_history.pop(model_name, None)
        logger.info("Model unregistered", extra={"model": model_name})

    # ------------------------------------------------------------------
    # Retrieval
    # ------------------------------------------------------------------

    def _build_version_predictor(
        self,
        base_predictor: BasePredictor,
        target_version: str,
    ) -> BasePredictor:
        version_dir = Path(base_predictor.model_dir) / target_version
        if not version_dir.exists():
            raise FileNotFoundError(f"Model version directory not found: {version_dir}")
        candidate = copy.copy(base_predictor)
        if not candidate._verify_integrity(version_dir):
            raise RuntimeError(f"Model integrity check failed for version '{target_version}'")
        candidate._model = None
        candidate._load_model(target_version)
        candidate._version = target_version
        if candidate._model is not None:
            candidate.status = ModelStatus.READY
        return candidate

    def get(
        self,
        model_name: str,
        version: Optional[str] = None,
        user_id: Optional[str] = None,
    ) -> BasePredictor:
        """Retrieve a registered predictor with kill-switch and A/B routing support."""
        with self._lock:
            if model_name not in self._models:
                raise KeyError(f"Model '{model_name}' is not registered in the registry")

            predictor = self._models[model_name]

            if self._kill_switches.get(model_name, False):
                predictor.status = ModelStatus.DEGRADED
                return predictor

            is_ab_routed = False
            target_version = version
            if target_version is None and model_name in self._ab_routing:
                target_version = self.resolve_version(model_name, user_id=user_id)
                is_ab_routed = target_version is not None

            if target_version is None or target_version == "latest":
                return predictor

            if is_ab_routed:
                version_cache = self._versioned_predictors.setdefault(model_name, {})
                cached = version_cache.get(target_version)
                if cached is not None:
                    return cached
                if predictor._version == target_version:
                    version_cache[target_version] = predictor
                    return predictor
                candidate = self._build_version_predictor(predictor, target_version)
                version_cache[target_version] = candidate
                return candidate

            if predictor._version == target_version:
                return predictor

            candidate = self._build_version_predictor(predictor, target_version)
            previous_version = predictor._version
            self._models[model_name] = candidate
            self._versioned_predictors.setdefault(model_name, {})[target_version] = candidate
            if previous_version and previous_version != target_version:
                self._previous_versions[model_name] = previous_version
                history = self._version_history.setdefault(model_name, [])
                if not history or history[-1] != target_version:
                    history.append(target_version)

            return candidate

    def rollback(
        self,
        model_name: str,
        target_version: Optional[str] = None,
    ) -> BasePredictor:
        """Roll back an active model predictor to a prior known-good version."""
        with self._lock:
            if model_name not in self._models:
                raise KeyError(f"Model '{model_name}' is not registered in the registry")
            resolved = target_version or self._previous_versions.get(model_name)
            if not resolved:
                raise ValueError(f"No previous version recorded for '{model_name}'")
            prev_ab = self._ab_routing.pop(model_name, None)
            prev_kill = self._kill_switches.pop(model_name, None)
            try:
                predictor = self.get(model_name, version=resolved)
            except Exception:
                if prev_ab is not None:
                    self._ab_routing[model_name] = prev_ab
                if prev_kill is not None:
                    self._kill_switches[model_name] = prev_kill
                raise
        logger.warning(
            "Model rolled back",
            extra={"model": model_name, "version": resolved},
        )
        return predictor

    def list_models(self) -> List[str]:
        """Return names of all registered models."""
        with self._lock:
            return list(self._models.keys())

    # ------------------------------------------------------------------
    # A/B Routing
    # ------------------------------------------------------------------

    def set_ab_routing(self, model_name: str, routing: Dict[str, float]) -> None:
        """Configure weighted traffic routing between model versions."""
        with self._lock:
            if model_name not in self._models:
                raise KeyError(f"Model '{model_name}' is not registered")
            for v, w in routing.items():
                if w < 0:
                    raise ValueError(f"Negative weight for version '{v}': {w}")
            self._ab_routing[model_name] = dict(routing)
        logger.info("A/B routing updated", extra={"model": model_name, "routing": routing})

    def resolve_version(self, model_name: str, user_id: Optional[str] = None) -> Optional[str]:
        """Sample a version according to A/B routing weights.

        Returns ``None`` if no routing is configured (use default version).
        """
        with self._lock:
            routing = self._ab_routing.get(model_name)
            if not routing:
                return None
            routing_copy = dict(routing)
        if user_id is not None:
            return ABTestRouter({model_name: routing_copy}).route(user_id=user_id, model_name=model_name)
        versions = list(routing_copy.keys())
        weights = list(routing_copy.values())
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
        with self._lock:
            if model_name not in self._models:
                raise KeyError(f"Model '{model_name}' is not registered — cannot attach shadow")
            self._shadow[model_name] = shadow_predictor
        logger.info(
            "Shadow predictor attached",
            extra={"model": model_name, "shadow_version": shadow_predictor._version},
        )

    def get_shadow(self, model_name: str) -> Optional[BasePredictor]:
        """Return the shadow predictor for a model, if any."""
        with self._lock:
            return self._shadow.get(model_name)

    def remove_shadow(self, model_name: str) -> None:
        """Detach the shadow predictor for a model."""
        with self._lock:
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
        with self._lock:
            self._kill_switches[model_name] = True
            if model_name in self._models:
                self._models[model_name].status = ModelStatus.DEGRADED
            for cached in self._versioned_predictors.get(model_name, {}).values():
                cached.status = ModelStatus.DEGRADED
        logger.warning("Kill switch activated", extra={"model": model_name})

    def unkill(self, model_name: str) -> None:
        """Deactivate the kill switch for a model.

        The predictor status is restored to ``READY`` if the underlying
        model is still loaded.
        """
        with self._lock:
            self._kill_switches.pop(model_name, None)
            predictor = self._models.get(model_name)
            if predictor and predictor._model is not None:
                predictor.status = ModelStatus.READY
            for cached in self._versioned_predictors.get(model_name, {}).values():
                if cached._model is not None:
                    cached.status = ModelStatus.READY
        logger.info("Kill switch deactivated", extra={"model": model_name})

    def is_killed(self, model_name: str) -> bool:
        """Check whether the kill switch is active for a model."""
        with self._lock:
            return self._kill_switches.get(model_name, False)

    # ------------------------------------------------------------------
    # Health & Status
    # ------------------------------------------------------------------

    def status(self) -> Dict[str, Any]:
        """Aggregate health status of all registered models.

        Each model entry now includes an ``integrity_verified`` flag
        that indicates whether the model's artifacts passed SHA-256
        checksum verification at load time.
        """
        with self._lock:
            models_snapshot = dict(self._models)
            shadow_keys = set(self._shadow.keys())
            ab_routing_snapshot = {k: dict(v) for k, v in self._ab_routing.items()}
            previous_snapshot = dict(self._previous_versions)
            history_snapshot = {k: list(v) for k, v in self._version_history.items()}
            kill_snapshot = dict(self._kill_switches)

        model_status: Dict[str, Any] = {}
        for name, predictor in models_snapshot.items():
            entry = predictor.health()
            entry["killed"] = kill_snapshot.get(name, False)
            entry["has_shadow"] = name in shadow_keys
            entry["ab_routing"] = ab_routing_snapshot.get(name)
            entry["previous_version"] = previous_snapshot.get(name)
            entry["version_history"] = history_snapshot.get(name, [])
            checksums_present = (predictor.model_dir / CHECKSUM_FILENAME).exists()
            entry["integrity_checksums_present"] = checksums_present
            entry["integrity_verified"] = (
                checksums_present and predictor.status != ModelStatus.FAILED
            )
            model_status[name] = entry

        return {
            "total_models": len(models_snapshot),
            "healthy": sum(
                1 for p in models_snapshot.values() if p.status == ModelStatus.READY
            ),
            "degraded": sum(
                1 for p in models_snapshot.values() if p.status == ModelStatus.DEGRADED
            ),
            "failed": sum(
                1 for p in models_snapshot.values() if p.status == ModelStatus.FAILED
            ),
            "models": model_status,
        }
