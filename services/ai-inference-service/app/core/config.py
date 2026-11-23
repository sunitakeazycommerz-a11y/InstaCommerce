"""Configuration, model registry, and LRU inference cache for AI Inference Service."""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Mapping, Optional

logger = logging.getLogger("ai_inference")


def parse_bool(value: Optional[str], default: bool) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def parse_json_env(name: str, default: Dict[str, str]) -> Dict[str, str]:
    raw = os.getenv(name)
    if not raw:
        return default
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        logger.warning("Invalid JSON for %s", name)
        return default
    if isinstance(data, dict):
        return {str(key): str(value) for key, value in data.items()}
    return default


DEFAULT_SLA_BUDGETS_MS: Dict[str, float] = {
    "eta": 10.0,
    "fraud": 15.0,
    "ranking": 20.0,
    "personalization": 25.0,
    "clv": 30.0,
    "demand": 50.0,
    "pricing": 20.0,
}


@dataclass(frozen=True)
class Settings:
    log_level: str = os.getenv("AI_INFERENCE_LOG_LEVEL", "INFO")
    cache_enabled: bool = parse_bool(os.getenv("AI_INFERENCE_CACHE_ENABLED"), True)
    cache_ttl_seconds: float = float(os.getenv("AI_INFERENCE_CACHE_TTL_SECONDS", "300"))
    cache_max_items: int = int(os.getenv("AI_INFERENCE_CACHE_MAX_ITEMS", "1000"))
    shadow_models: Dict[str, str] = field(
        default_factory=lambda: parse_json_env("AI_INFERENCE_SHADOW_MODELS", {})
    )
    shadow_sample_rate: float = float(os.getenv("AI_INFERENCE_SHADOW_SAMPLE_RATE", "1.0"))
    feature_store_backend: str = os.getenv("AI_INFERENCE_FEATURE_STORE_BACKEND", "none")
    redis_url: str = os.getenv("AI_INFERENCE_REDIS_URL", "")
    redis_prefix: str = os.getenv("AI_INFERENCE_REDIS_PREFIX", "features")
    bigquery_project: str = os.getenv("AI_INFERENCE_BIGQUERY_PROJECT", "")
    bigquery_dataset: str = os.getenv("AI_INFERENCE_BIGQUERY_DATASET", "")
    bigquery_table: str = os.getenv("AI_INFERENCE_BIGQUERY_TABLE", "")
    ood_z_threshold: float = float(os.getenv("AI_INFERENCE_OOD_Z_THRESHOLD", "4.0"))
    max_missing_feature_ratio: float = float(os.getenv("AI_INFERENCE_MAX_MISSING_RATIO", "0.35"))
    strict_sla_timeout: bool = parse_bool(os.getenv("AI_INFERENCE_STRICT_SLA_TIMEOUT"), False)
    shadow_timeout_seconds: float = float(os.getenv("AI_INFERENCE_SHADOW_TIMEOUT_SECONDS", "0.25"))
    onnx_model_dir: str = os.getenv("AI_INFERENCE_ONNX_MODEL_DIR", "")
    model_artifact_dir: str = os.getenv("AI_INFERENCE_MODEL_ARTIFACT_DIR", "")
    sla_budgets_ms: Dict[str, float] = field(
        default_factory=lambda: {
            model: float(os.getenv(f"AI_INFERENCE_SLA_{model.upper()}_MS", str(default_ms)))
            for model, default_ms in DEFAULT_SLA_BUDGETS_MS.items()
        }
    )


settings = Settings()


def configure_logging() -> None:
    level = getattr(logging, settings.log_level.upper(), logging.INFO)
    logging.basicConfig(level=level, format="%(asctime)s %(levelname)s %(name)s %(message)s")


DEFAULT_WEIGHTS: Dict[str, Any] = {
    "eta": {
        "default_version": "eta-linear-v1",
        "versions": {
            "eta-linear-v1": {
                "bias": 4.5,
                "weights": {
                    "distance_km": 2.3,
                    "item_count": 0.6,
                    "traffic_factor": 3.1,
                },
            },
            "eta-lgbm-v1": {
                "bias": 4.5,
                "weights": {
                    "distance_km": 2.3,
                    "item_count": 0.6,
                    "traffic_factor": 3.1,
                },
            },
            "eta-onnx-v1": {
                "bias": 4.5,
                "weights": {
                    "distance_km": 2.3,
                    "item_count": 0.6,
                    "traffic_factor": 3.1,
                },
            },
        },
    },
    "ranking": {
        "default_version": "ranking-linear-v1",
        "versions": {
            "ranking-linear-v1": {
                "bias": 0.15,
                "weights": {
                    "relevance_score": 0.55,
                    "price_score": 0.2,
                    "availability_score": 0.35,
                    "user_affinity": 0.4,
                },
            },
            "ranking-lambdamart-v1": {
                "bias": 0.15,
                "weights": {
                    "relevance_score": 0.55,
                    "price_score": 0.2,
                    "availability_score": 0.35,
                    "user_affinity": 0.4,
                },
            },
            "ranking-onnx-v1": {
                "bias": 0.15,
                "weights": {
                    "relevance_score": 0.55,
                    "price_score": 0.2,
                    "availability_score": 0.35,
                    "user_affinity": 0.4,
                },
            },
        },
    },
    "fraud": {
        "default_version": "fraud-logit-v1",
        "versions": {
            "fraud-logit-v1": {
                "bias": -1.4,
                "weights": {
                    "order_amount": 0.012,
                    "chargeback_rate": 4.8,
                    "device_risk": 2.1,
                    "account_age_days": -0.015,
                },
            },
            "fraud-xgb-v1": {
                "bias": -1.4,
                "weights": {
                    "order_amount": 0.012,
                    "chargeback_rate": 4.8,
                    "device_risk": 2.1,
                    "account_age_days": -0.015,
                },
            },
            "fraud-onnx-v1": {
                "bias": -1.4,
                "weights": {
                    "order_amount": 0.012,
                    "chargeback_rate": 4.8,
                    "device_risk": 2.1,
                    "account_age_days": -0.015,
                },
            },
        },
    },
    "demand": {
        "default_version": "demand-tft-v1",
        "versions": {
            "demand-tft-v1": {
                "bias": 1.5,
                "weights": {
                    "sales_same_hour_last_week": 0.45,
                    "sales_avg_7d": 0.35,
                    "sales_avg_30d": 0.20,
                    "temperature_celsius": 0.05,
                    "discount_percent": 0.12,
                },
            },
            "demand-onnx-v1": {
                "bias": 1.5,
                "weights": {
                    "sales_same_hour_last_week": 0.45,
                    "sales_avg_7d": 0.35,
                    "sales_avg_30d": 0.20,
                    "temperature_celsius": 0.05,
                    "discount_percent": 0.12,
                },
            },
        },
    },
    "personalization": {
        "default_version": "pers-ncf-v1",
        "versions": {
            "pers-ncf-v1": {
                "bias": 0.10,
                "weights": {
                    "user_avg_order_value": 0.001,
                    "user_order_frequency_7d": 0.15,
                    "time_of_day_hour": 0.02,
                },
            },
            "pers-onnx-v1": {
                "bias": 0.10,
                "weights": {
                    "user_avg_order_value": 0.001,
                    "user_order_frequency_7d": 0.15,
                    "time_of_day_hour": 0.02,
                },
            },
        },
    },
    "clv": {
        "default_version": "clv-bg-nbd-v1",
        "versions": {
            "clv-bg-nbd-v1": {
                "bias": 2000.0,
                "weights": {
                    "frequency_total": 1200.0,
                    "frequency_30d": 4500.0,
                    "monetary_avg_cents": 6.0,
                    "tenure_days": 15.0,
                    "recency_days": -40.0,
                },
            },
            "clv-onnx-v1": {
                "bias": 2000.0,
                "weights": {
                    "frequency_total": 1200.0,
                    "frequency_30d": 4500.0,
                    "monetary_avg_cents": 6.0,
                    "tenure_days": 15.0,
                    "recency_days": -40.0,
                },
            },
        },
    },
    "pricing": {
        "default_version": "pricing-bandit-v1",
        "versions": {
            "pricing-bandit-v1": {
                "bias": 250.0,
                "weights": {
                    "distance_km": 35.0,
                    "item_count": 10.0,
                    "surge_multiplier": 180.0,
                    "pending_orders_in_zone": 8.0,
                    "active_riders_in_zone": -12.0,
                },
            },
            "pricing-onnx-v1": {
                "bias": 250.0,
                "weights": {
                    "distance_km": 35.0,
                    "item_count": 10.0,
                    "surge_multiplier": 180.0,
                    "pending_orders_in_zone": 8.0,
                    "active_riders_in_zone": -12.0,
                },
            },
        },
    },
}

WEIGHTS_PATH = Path(
    os.getenv(
        "AI_INFERENCE_WEIGHTS_PATH",
        str(Path(__file__).resolve().parent.parent / "weights.json"),
    )
)


def load_registry_data() -> Dict[str, Any]:
    if not WEIGHTS_PATH.exists():
        return {}
    try:
        with WEIGHTS_PATH.open("r", encoding="utf-8") as file:
            data = json.load(file)
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("Failed to load weights registry: %s", exc.__class__.__name__)
        return {}
    if isinstance(data, dict):
        if isinstance(data.get("models"), dict):
            return data["models"]
        return data
    return {}


@dataclass(frozen=True)
class ModelConfig:
    version: str
    bias: float
    weights: Dict[str, float]


@dataclass(frozen=True)
class ModelEntry:
    default_version: str
    versions: Dict[str, ModelConfig]


def parse_model_config(version: str, raw: Mapping[str, Any]) -> ModelConfig:
    weights_raw = raw.get("weights") if isinstance(raw, Mapping) else {}
    if not isinstance(weights_raw, Mapping):
        weights_raw = {}
    weights = {str(key): float(value) for key, value in weights_raw.items()}
    bias = float(raw.get("bias", 0.0)) if isinstance(raw, Mapping) else 0.0
    return ModelConfig(version=version, bias=bias, weights=weights)


def normalize_model_entry(name: str, raw: Mapping[str, Any]) -> ModelEntry:
    versions: Dict[str, ModelConfig] = {}
    default_version: Optional[str] = None
    if isinstance(raw, Mapping) and isinstance(raw.get("versions"), Mapping):
        versions_raw = raw.get("versions") or {}
        for version_name, config in versions_raw.items():
            if isinstance(config, Mapping):
                version_key = str(version_name)
                versions[version_key] = parse_model_config(version_key, config)
        default_version = raw.get("default_version") or raw.get("defaultVersion")
    if not versions:
        version = str(raw.get("version") or f"{name}-v1") if isinstance(raw, Mapping) else f"{name}-v1"
        versions[version] = parse_model_config(version, raw if isinstance(raw, Mapping) else {})
        default_version = version
    if not default_version or default_version not in versions:
        default_version = next(iter(versions))
    return ModelEntry(default_version=default_version, versions=versions)


class ModelRegistry:
    def __init__(self, models: Dict[str, ModelEntry]) -> None:
        self._models = models

    @classmethod
    def from_sources(
        cls, defaults: Dict[str, Any], overrides: Optional[Dict[str, Any]] = None
    ) -> "ModelRegistry":
        merged = {**defaults, **(overrides or {})}
        models = {name: normalize_model_entry(name, data) for name, data in merged.items()}
        return cls(models)

    def get(self, name: str, version: Optional[str] = None) -> ModelConfig:
        entry = self._models.get(name)
        if not entry:
            raise KeyError(f"model_not_found:{name}")
        resolved = version or entry.default_version
        if resolved in {"default", "latest"}:
            resolved = entry.default_version
        model = entry.versions.get(resolved)
        if not model:
            raise KeyError(f"model_version_not_found:{name}:{resolved}")
        return model

    def summary(self) -> Dict[str, Any]:
        return {
            name: {
                "default_version": entry.default_version,
                "versions": sorted(entry.versions.keys()),
            }
            for name, entry in self._models.items()
        }


class InferenceCache:
    def __init__(self, max_items: int, ttl_seconds: float) -> None:
        self._max_items = max(0, max_items)
        self._ttl_seconds = max(0.0, ttl_seconds)
        self._store: "OrderedDict[str, tuple[float, Dict[str, Any]]]" = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key: str) -> Optional[Dict[str, Any]]:
        if self._max_items <= 0:
            return None
        with self._lock:
            entry = self._store.get(key)
            if not entry:
                return None
            expires_at, value = entry
            if expires_at and expires_at < time.monotonic():
                self._store.pop(key, None)
                return None
            self._store.move_to_end(key)
            return value

    def set(self, key: str, value: Dict[str, Any]) -> None:
        if self._max_items <= 0:
            return
        expires_at = time.monotonic() + self._ttl_seconds if self._ttl_seconds > 0 else 0.0
        with self._lock:
            self._store[key] = (expires_at, value)
            self._store.move_to_end(key)
            while len(self._store) > self._max_items:
                self._store.popitem(last=False)
