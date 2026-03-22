from __future__ import annotations

from collections import OrderedDict
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from math import exp
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence, Literal
import hashlib
import json
import logging
import os
import random
import time

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field, ValidationError
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

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


@dataclass(frozen=True)
class Settings:
    log_level: str = os.getenv("AI_INFERENCE_LOG_LEVEL", "INFO")
    cache_enabled: bool = parse_bool(os.getenv("AI_INFERENCE_CACHE_ENABLED"), True)
    cache_ttl_seconds: float = float(os.getenv("AI_INFERENCE_CACHE_TTL_SECONDS", "300"))
    cache_max_items: int = int(os.getenv("AI_INFERENCE_CACHE_MAX_ITEMS", "1000"))
    shadow_models: Dict[str, str] = field(default_factory=lambda: parse_json_env("AI_INFERENCE_SHADOW_MODELS", {}))
    shadow_sample_rate: float = float(os.getenv("AI_INFERENCE_SHADOW_SAMPLE_RATE", "1.0"))
    feature_store_backend: str = os.getenv("AI_INFERENCE_FEATURE_STORE_BACKEND", "none")
    redis_url: str = os.getenv("AI_INFERENCE_REDIS_URL", "")
    redis_prefix: str = os.getenv("AI_INFERENCE_REDIS_PREFIX", "features")
    bigquery_project: str = os.getenv("AI_INFERENCE_BIGQUERY_PROJECT", "")
    bigquery_dataset: str = os.getenv("AI_INFERENCE_BIGQUERY_DATASET", "")
    bigquery_table: str = os.getenv("AI_INFERENCE_BIGQUERY_TABLE", "")


settings = Settings()


def configure_logging() -> None:
    level = getattr(logging, settings.log_level.upper(), logging.INFO)
    logging.basicConfig(level=level, format="%(asctime)s %(levelname)s %(name)s %(message)s")


configure_logging()

DEFAULT_WEIGHTS = {
    "eta": {
        "version": "eta-linear-v1",
        "bias": 4.5,
        "weights": {
            "distance_km": 2.3,
            "item_count": 0.6,
            "traffic_factor": 3.1,
        },
    },
    "ranking": {
        "version": "ranking-linear-v1",
        "bias": 0.15,
        "weights": {
            "relevance_score": 0.55,
            "price_score": 0.2,
            "availability_score": 0.35,
            "user_affinity": 0.4,
        },
    },
    "fraud": {
        "version": "fraud-logit-v1",
        "bias": -1.4,
        "weights": {
            "order_amount": 0.012,
            "chargeback_rate": 4.8,
            "device_risk": 2.1,
            "account_age_days": -0.015,
        },
    },
}

WEIGHTS_PATH = Path(
    os.getenv("AI_INFERENCE_WEIGHTS_PATH", str(Path(__file__).resolve().parent / "weights.json"))
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
    def from_sources(cls, defaults: Dict[str, Any], overrides: Optional[Dict[str, Any]] = None) -> "ModelRegistry":
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
            name: {"default_version": entry.default_version, "versions": sorted(entry.versions.keys())}
            for name, entry in self._models.items()
        }


class InferenceCache:
    def __init__(self, max_items: int, ttl_seconds: float) -> None:
        self._max_items = max(0, max_items)
        self._ttl_seconds = max(0.0, ttl_seconds)
        self._store: "OrderedDict[str, tuple[float, Dict[str, Any]]]" = OrderedDict()

    def get(self, key: str) -> Optional[Dict[str, Any]]:
        if self._max_items <= 0:
            return None
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
        self._store[key] = (expires_at, value)
        self._store.move_to_end(key)
        while len(self._store) > self._max_items:
            self._store.popitem(last=False)


class FeatureStoreClient:
    backend = "none"

    def get_features(self, entity_id: str, feature_names: Sequence[str]) -> Dict[str, float]:
        return {}

    def health(self) -> Dict[str, str]:
        return {"backend": self.backend, "status": "disabled"}

    def close(self) -> None:
        return None


class UnavailableFeatureStoreClient(FeatureStoreClient):
    def __init__(self, backend: str, reason: str) -> None:
        self.backend = backend
        self._reason = reason

    def get_features(self, entity_id: str, feature_names: Sequence[str]) -> Dict[str, float]:
        raise RuntimeError(self._reason)

    def health(self) -> Dict[str, str]:
        return {"backend": self.backend, "status": "unavailable", "reason": self._reason}


class RedisFeatureStoreClient(FeatureStoreClient):
    backend = "redis"

    def __init__(self, url: str, prefix: str) -> None:
        import redis

        self._client = redis.Redis.from_url(url, decode_responses=True)
        self._prefix = prefix.rstrip(":")

    def _key(self, entity_id: str) -> str:
        return f"{self._prefix}:{entity_id}" if self._prefix else entity_id

    def get_features(self, entity_id: str, feature_names: Sequence[str]) -> Dict[str, float]:
        key = self._key(entity_id)
        if feature_names:
            values = self._client.hmget(key, list(feature_names))
            return {
                name: float(value)
                for name, value in zip(feature_names, values)
                if value is not None
            }
        data = self._client.hgetall(key)
        return {name: float(value) for name, value in data.items()}

    def health(self) -> Dict[str, str]:
        try:
            self._client.ping()
            return {"backend": self.backend, "status": "ok"}
        except Exception:
            return {"backend": self.backend, "status": "degraded"}

    def close(self) -> None:
        try:
            self._client.close()
        except Exception:
            pass


class BigQueryFeatureStoreClient(FeatureStoreClient):
    backend = "bigquery"

    def __init__(self, project: str, dataset: str, table: str) -> None:
        from google.cloud import bigquery

        self._bigquery = bigquery
        self._client = bigquery.Client(project=project or None)
        if project:
            self._table = f"{project}.{dataset}.{table}"
        else:
            self._table = f"{dataset}.{table}"

    def get_features(self, entity_id: str, feature_names: Sequence[str]) -> Dict[str, float]:
        if not feature_names:
            return {}
        query = (
            f"SELECT feature_name, feature_value FROM `{self._table}` "
            "WHERE entity_id = @entity_id AND feature_name IN UNNEST(@feature_names)"
        )
        job_config = self._bigquery.QueryJobConfig(
            query_parameters=[
                self._bigquery.ScalarQueryParameter("entity_id", "STRING", entity_id),
                self._bigquery.ArrayQueryParameter("feature_names", "STRING", list(feature_names)),
            ]
        )
        rows = self._client.query(query, job_config=job_config).result()
        result: Dict[str, float] = {}
        for row in rows:
            try:
                result[str(row.feature_name)] = float(row.feature_value)
            except (TypeError, ValueError):
                continue
        return result

    def health(self) -> Dict[str, str]:
        return {"backend": self.backend, "status": "configured"}

    def close(self) -> None:
        try:
            self._client.close()
        except Exception:
            pass


def build_feature_store(config: Settings) -> FeatureStoreClient:
    backend = config.feature_store_backend.lower().strip()
    if backend == "redis":
        if not config.redis_url:
            return UnavailableFeatureStoreClient("redis", "missing_redis_url")
        try:
            return RedisFeatureStoreClient(config.redis_url, config.redis_prefix)
        except ImportError:
            return UnavailableFeatureStoreClient("redis", "redis_dependency_missing")
    if backend == "bigquery":
        if not config.bigquery_dataset or not config.bigquery_table:
            return UnavailableFeatureStoreClient("bigquery", "missing_bigquery_config")
        try:
            return BigQueryFeatureStoreClient(
                config.bigquery_project, config.bigquery_dataset, config.bigquery_table
            )
        except ImportError:
            return UnavailableFeatureStoreClient("bigquery", "bigquery_dependency_missing")
    return FeatureStoreClient()


REQUEST_COUNT = Counter(
    "ai_inference_requests_total",
    "Total inference requests",
    ["endpoint", "model", "version", "status"],
)
REQUEST_LATENCY = Histogram(
    "ai_inference_request_latency_seconds",
    "Inference request latency",
    ["endpoint", "model", "version"],
)
CACHE_EVENTS = Counter(
    "ai_inference_cache_events_total",
    "Inference cache events",
    ["model", "version", "result"],
)
SHADOW_REQUESTS = Counter(
    "ai_inference_shadow_requests_total",
    "Shadow inference requests",
    ["model", "version"],
)
FEATURE_STORE_LATENCY = Histogram(
    "ai_inference_feature_store_latency_seconds",
    "Feature store latency",
    ["backend"],
)
FEATURE_STORE_ERRORS = Counter(
    "ai_inference_feature_store_errors_total",
    "Feature store errors",
    ["backend"],
)


def linear_score(features: Dict[str, float], weights: Dict[str, float], bias: float) -> tuple[float, Dict[str, float]]:
    contributions = {name: float(features.get(name, 0.0)) * weight for name, weight in weights.items()}
    return bias + sum(contributions.values()), contributions


def init_telemetry(app_instance: FastAPI) -> Optional[Any]:
    otel_enabled = parse_bool(os.getenv("AI_INFERENCE_OTEL_ENABLED"), True)
    if not otel_enabled:
        return None
    try:
        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
    except ImportError:
        logger.warning("OpenTelemetry instrumentation unavailable")
        return None

    service_name = os.getenv("AI_INFERENCE_OTEL_SERVICE_NAME", "ai-inference-service")
    resource = Resource.create({"service.name": service_name})
    provider = TracerProvider(resource=resource)
    endpoint = os.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
    exporter = OTLPSpanExporter(endpoint=endpoint) if endpoint else OTLPSpanExporter()
    provider.add_span_processor(BatchSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    FastAPIInstrumentor.instrument_app(app_instance)
    return trace.get_tracer("ai-inference-service")


def safe_sigmoid(value: float) -> float:
    if value >= 50:
        return 1.0
    if value <= -50:
        return 0.0
    return 1.0 / (1.0 + exp(-value))


class EtaRequest(BaseModel):
    distance_km: float = Field(..., ge=0, le=200)
    item_count: int = Field(..., ge=0, le=200)
    traffic_factor: float = Field(..., ge=0.5, le=3.0)
    model_version: Optional[str] = None


class EtaResponse(BaseModel):
    eta_minutes: float
    feature_contributions: Dict[str, float]
    bias: float
    model_version: str


class RankingRequest(BaseModel):
    relevance_score: float = Field(..., ge=0, le=1)
    price_score: float = Field(..., ge=0, le=1)
    availability_score: float = Field(..., ge=0, le=1)
    user_affinity: float = Field(..., ge=0, le=1)
    model_version: Optional[str] = None


class RankingResponse(BaseModel):
    ranking_score: float
    raw_score: float
    feature_contributions: Dict[str, float]
    bias: float
    model_version: str


class FraudRequest(BaseModel):
    order_amount: float = Field(..., ge=0, le=10000)
    chargeback_rate: float = Field(..., ge=0, le=1)
    device_risk: float = Field(..., ge=0, le=1)
    account_age_days: int = Field(..., ge=0, le=36500)
    model_version: Optional[str] = None


class FraudResponse(BaseModel):
    fraud_probability: float
    raw_score: float
    feature_contributions: Dict[str, float]
    bias: float
    model_version: str


class BatchInferenceItem(BaseModel):
    model_name: Literal["eta", "ranking", "fraud"]
    payload: Dict[str, Any] = Field(default_factory=dict)
    model_version: Optional[str] = None
    entity_id: Optional[str] = None
    use_feature_store: bool = False


class BatchInferenceRequest(BaseModel):
    items: List[BatchInferenceItem] = Field(..., min_length=1, max_length=100)


class BatchInferenceResult(BaseModel):
    model_name: str
    model_version: Optional[str] = None
    output: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class BatchInferenceResponse(BaseModel):
    results: List[BatchInferenceResult]


def eta_features(request: EtaRequest) -> Dict[str, float]:
    return {
        "distance_km": request.distance_km,
        "item_count": float(request.item_count),
        "traffic_factor": request.traffic_factor,
    }


def ranking_features(request: RankingRequest) -> Dict[str, float]:
    return {
        "relevance_score": request.relevance_score,
        "price_score": request.price_score,
        "availability_score": request.availability_score,
        "user_affinity": request.user_affinity,
    }


def fraud_features(request: FraudRequest) -> Dict[str, float]:
    return {
        "order_amount": request.order_amount,
        "chargeback_rate": request.chargeback_rate,
        "device_risk": request.device_risk,
        "account_age_days": float(request.account_age_days),
    }


def parse_model_request(model_name: str, payload: Mapping[str, Any]) -> BaseModel:
    if model_name == "eta":
        return EtaRequest.model_validate(payload)
    if model_name == "ranking":
        return RankingRequest.model_validate(payload)
    if model_name == "fraud":
        return FraudRequest.model_validate(payload)
    raise ValueError("unknown_model")


def build_features_for_model(model_name: str, request: BaseModel) -> Dict[str, float]:
    if model_name == "eta":
        return eta_features(request)
    if model_name == "ranking":
        return ranking_features(request)
    if model_name == "fraud":
        return fraud_features(request)
    raise ValueError("unknown_model")


def perform_inference(model_name: str, features: Dict[str, float], model_config: ModelConfig) -> Dict[str, Any]:
    raw_score, contributions = linear_score(features, model_config.weights, model_config.bias)
    if model_name == "eta":
        eta_minutes = max(0.0, raw_score)
        return {
            "eta_minutes": eta_minutes,
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
        }
    if model_name == "ranking":
        ranking_score = safe_sigmoid(raw_score)
        return {
            "ranking_score": ranking_score,
            "raw_score": raw_score,
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
        }
    if model_name == "fraud":
        fraud_probability = safe_sigmoid(raw_score)
        return {
            "fraud_probability": fraud_probability,
            "raw_score": raw_score,
            "feature_contributions": contributions,
            "bias": model_config.bias,
            "model_version": model_config.version,
        }
    raise ValueError("unknown_model")


def build_cache_key(model_name: str, model_version: str, features: Dict[str, float]) -> str:
    payload = {"model": model_name, "version": model_version, "features": features}
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def maybe_run_shadow(
    model_name: str, features: Dict[str, float], primary_version: str, registry: ModelRegistry
) -> None:
    shadow_version = settings.shadow_models.get(model_name)
    if not shadow_version or shadow_version == primary_version:
        return
    sample_rate = min(max(settings.shadow_sample_rate, 0.0), 1.0)
    if sample_rate < 1.0 and random.random() > sample_rate:
        return
    try:
        shadow_model = registry.get(model_name, shadow_version)
    except KeyError:
        return
    try:
        perform_inference(model_name, features, shadow_model)
        SHADOW_REQUESTS.labels(model=model_name, version=shadow_model.version).inc()
    except Exception as exc:
        logger.warning("Shadow inference failed for %s: %s", model_name, exc.__class__.__name__)


def execute_inference(
    model_name: str,
    features: Dict[str, float],
    model_config: ModelConfig,
    cache: Optional[InferenceCache],
    registry: ModelRegistry,
) -> tuple[Dict[str, Any], bool]:
    cache_hit = False
    if cache:
        key = build_cache_key(model_name, model_config.version, features)
        cached = cache.get(key)
        if cached is not None:
            cache_hit = True
            CACHE_EVENTS.labels(model=model_name, version=model_config.version, result="hit").inc()
            result = cached
        else:
            CACHE_EVENTS.labels(model=model_name, version=model_config.version, result="miss").inc()
            result = perform_inference(model_name, features, model_config)
            cache.set(key, result)
    else:
        result = perform_inference(model_name, features, model_config)
    maybe_run_shadow(model_name, features, model_config.version, registry)
    return result, cache_hit


def resolve_model_config(registry: ModelRegistry, model_name: str, requested_version: Optional[str]) -> ModelConfig:
    try:
        return registry.get(model_name, requested_version)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="model_version_not_found") from exc


def fetch_feature_store_features(
    feature_store: FeatureStoreClient, entity_id: str, feature_names: Sequence[str]
) -> Dict[str, float]:
    start = time.perf_counter()
    try:
        features = feature_store.get_features(entity_id, feature_names)
        FEATURE_STORE_LATENCY.labels(backend=feature_store.backend).observe(time.perf_counter() - start)
        return features
    except Exception:
        FEATURE_STORE_ERRORS.labels(backend=feature_store.backend).inc()
        raise


def process_batch_item(
    item: BatchInferenceItem,
    registry: ModelRegistry,
    cache: Optional[InferenceCache],
    feature_store: FeatureStoreClient,
) -> BatchInferenceResult:
    start = time.perf_counter()
    status = "error"
    version_label = item.model_version or "unknown"
    try:
        requested_version = item.model_version
        if not requested_version and isinstance(item.payload, Mapping):
            requested_version = item.payload.get("model_version") or item.payload.get("modelVersion")
        model_config = registry.get(item.model_name, requested_version)
        version_label = model_config.version
        payload = dict(item.payload)
        if item.use_feature_store:
            if not item.entity_id:
                return BatchInferenceResult(
                    model_name=item.model_name,
                    model_version=version_label,
                    error="entity_id_required",
                )
            if feature_store.backend == "none" or isinstance(feature_store, UnavailableFeatureStoreClient):
                return BatchInferenceResult(
                    model_name=item.model_name,
                    model_version=version_label,
                    error="feature_store_unavailable",
                )
            try:
                store_features = fetch_feature_store_features(
                    feature_store, item.entity_id, list(model_config.weights.keys())
                )
            except Exception:
                return BatchInferenceResult(
                    model_name=item.model_name,
                    model_version=version_label,
                    error="feature_store_error",
                )
            payload = {**store_features, **payload}
        try:
            request = parse_model_request(item.model_name, payload)
        except ValidationError:
            return BatchInferenceResult(
                model_name=item.model_name,
                model_version=version_label,
                error="validation_error",
            )
        features = build_features_for_model(item.model_name, request)
        output, _ = execute_inference(item.model_name, features, model_config, cache, registry)
        status = "success"
        return BatchInferenceResult(
            model_name=item.model_name,
            model_version=model_config.version,
            output=output,
        )
    except KeyError:
        return BatchInferenceResult(
            model_name=item.model_name,
            model_version=version_label,
            error="model_version_not_found",
        )
    finally:
        REQUEST_COUNT.labels(
            endpoint="/inference/batch",
            model=item.model_name,
            version=version_label,
            status=status,
        ).inc()
        REQUEST_LATENCY.labels(
            endpoint="/inference/batch",
            model=item.model_name,
            version=version_label,
        ).observe(time.perf_counter() - start)


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    registry = ModelRegistry.from_sources(DEFAULT_WEIGHTS, load_registry_data())
    cache = InferenceCache(settings.cache_max_items, settings.cache_ttl_seconds) if settings.cache_enabled else None
    feature_store = build_feature_store(settings)
    app_instance.state.registry = registry
    app_instance.state.cache = cache
    app_instance.state.feature_store = feature_store
    try:
        yield
    finally:
        feature_store.close()


app = FastAPI(title="AI Inference Service", version="1.1.0", lifespan=lifespan)

from app.auth import InternalServiceAuthMiddleware
app.add_middleware(InternalServiceAuthMiddleware)

app.state.tracer = init_telemetry(app)


@app.get("/metrics")
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/models")
def list_models() -> Dict[str, Any]:
    return {"models": app.state.registry.summary()}


@app.get("/health")
def health() -> Dict[str, Any]:
    return {
        "status": "ok",
        "models": app.state.registry.summary(),
        "feature_store": app.state.feature_store.health(),
    }


@app.get("/health/ready")
def readiness() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/health/live")
def liveness() -> Dict[str, str]:
    return {"status": "ok"}


@app.post("/inference/eta", response_model=EtaResponse)
def eta_inference(request: EtaRequest, model_version: Optional[str] = None) -> EtaResponse:
    start = time.perf_counter()
    status = "error"
    version_label = "unknown"
    try:
        registry = app.state.registry
        cache = app.state.cache
        requested_version = model_version or request.model_version
        model_config = resolve_model_config(registry, "eta", requested_version)
        version_label = model_config.version
        features = eta_features(request)
        output, _ = execute_inference("eta", features, model_config, cache, registry)
        status = "success"
        return EtaResponse(**output)
    finally:
        REQUEST_COUNT.labels(
            endpoint="/inference/eta", model="eta", version=version_label, status=status
        ).inc()
        REQUEST_LATENCY.labels(endpoint="/inference/eta", model="eta", version=version_label).observe(
            time.perf_counter() - start
        )


@app.post("/inference/ranking", response_model=RankingResponse)
def ranking_inference(request: RankingRequest, model_version: Optional[str] = None) -> RankingResponse:
    start = time.perf_counter()
    status = "error"
    version_label = "unknown"
    try:
        registry = app.state.registry
        cache = app.state.cache
        requested_version = model_version or request.model_version
        model_config = resolve_model_config(registry, "ranking", requested_version)
        version_label = model_config.version
        features = ranking_features(request)
        output, _ = execute_inference("ranking", features, model_config, cache, registry)
        status = "success"
        return RankingResponse(**output)
    finally:
        REQUEST_COUNT.labels(
            endpoint="/inference/ranking", model="ranking", version=version_label, status=status
        ).inc()
        REQUEST_LATENCY.labels(
            endpoint="/inference/ranking", model="ranking", version=version_label
        ).observe(time.perf_counter() - start)


@app.post("/inference/fraud", response_model=FraudResponse)
def fraud_inference(request: FraudRequest, model_version: Optional[str] = None) -> FraudResponse:
    start = time.perf_counter()
    status = "error"
    version_label = "unknown"
    try:
        registry = app.state.registry
        cache = app.state.cache
        requested_version = model_version or request.model_version
        model_config = resolve_model_config(registry, "fraud", requested_version)
        version_label = model_config.version
        features = fraud_features(request)
        output, _ = execute_inference("fraud", features, model_config, cache, registry)
        status = "success"
        return FraudResponse(**output)
    finally:
        REQUEST_COUNT.labels(
            endpoint="/inference/fraud", model="fraud", version=version_label, status=status
        ).inc()
        REQUEST_LATENCY.labels(endpoint="/inference/fraud", model="fraud", version=version_label).observe(
            time.perf_counter() - start
        )


@app.post("/inference/batch", response_model=BatchInferenceResponse)
def batch_inference(request: BatchInferenceRequest) -> BatchInferenceResponse:
    registry = app.state.registry
    cache = app.state.cache
    feature_store = app.state.feature_store
    results = [process_batch_item(item, registry, cache, feature_store) for item in request.items]
    return BatchInferenceResponse(results=results)
