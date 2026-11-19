"""FastAPI entrypoint for the InstaCommerce AI Inference Service."""

from __future__ import annotations

import time
from contextlib import asynccontextmanager
from typing import Any, Callable, Dict, Optional

from fastapi import FastAPI
from fastapi.responses import Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app.auth import InternalServiceAuthMiddleware
from app.batching import DynamicBatcher
from app.core.config import (
    DEFAULT_WEIGHTS,
    InferenceCache,
    ModelRegistry,
    configure_logging,
    load_registry_data,
    settings,
)
from app.core.guardrails import (
    MODEL_LATENCY_MS,
    SLA_BREACH_TOTAL,
    enforce_async_sla,
)
from app.core.inference import (
    REQUEST_COUNT,
    REQUEST_LATENCY,
    execute_inference,
    process_batch_item,
    resolve_model_config,
)
from app.core.telemetry import TraceContextMiddleware, init_telemetry
from app.features.extractors import (
    build_feature_store,
    eta_features,
    fraud_features,
    ranking_features,
)
from app.features.redis_client import RedisFeatureStoreClient
from app.models import (
    CLVFeatures,
    CLVModel,
    CLVResponse,
    DemandFeatures,
    DemandModel,
    DemandResponse,
    DynamicPricingModel,
    PersonalizationFeatures,
    PersonalizationModel,
    PersonalizationResponse,
    PricingFeatures,
    PricingResponse,
)
from app.schemas import (
    BatchInferenceRequest,
    BatchInferenceResponse,
    EtaRequest,
    EtaResponse,
    FraudRequest,
    FraudResponse,
    RankingRequest,
    RankingResponse,
)

configure_logging()


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    registry = ModelRegistry.from_sources(DEFAULT_WEIGHTS, load_registry_data())
    cache = (
        InferenceCache(settings.cache_max_items, settings.cache_ttl_seconds)
        if settings.cache_enabled
        else None
    )
    feature_store = build_feature_store(settings, cache=cache)
    feature_fetcher = (
        feature_store
        if isinstance(feature_store, RedisFeatureStoreClient)
        else (
            RedisFeatureStoreClient(redis_url=settings.redis_url, cache=cache)
            if settings.redis_url
            else None
        )
    )
    batcher = DynamicBatcher(max_batch_size=64, max_wait_ms=5.0)
    await batcher.start()
    app_instance.state.registry = registry
    app_instance.state.cache = cache
    app_instance.state.feature_store = feature_store
    app_instance.state.feature_fetcher = feature_fetcher
    app_instance.state.batcher = batcher
    app_instance.state.demand_model = DemandModel()
    app_instance.state.personalization_model = PersonalizationModel()
    app_instance.state.clv_model = CLVModel()
    app_instance.state.pricing_model = DynamicPricingModel()
    try:
        yield
    finally:
        await batcher.stop()
        feature_store.close()
        if feature_fetcher is not None and feature_fetcher is not feature_store:
            feature_fetcher.close()


app = FastAPI(title="AI Inference Service", version="1.1.0", lifespan=lifespan)

app.add_middleware(InternalServiceAuthMiddleware)
app.add_middleware(TraceContextMiddleware)

app.state.tracer = init_telemetry(app)


def _run_linear_endpoint(
    endpoint: str,
    model_name: str,
    requested_version: Optional[str],
    features: Dict[str, float],
) -> Dict[str, Any]:
    start = time.perf_counter()
    status = "error"
    version_label = "unknown"
    try:
        registry: ModelRegistry = app.state.registry
        cache: Optional[InferenceCache] = app.state.cache
        model_config = resolve_model_config(registry, model_name, requested_version)
        version_label = model_config.version
        output, _ = execute_inference(model_name, features, model_config, cache, registry)
        status = "success"
        return output
    finally:
        REQUEST_COUNT.labels(
            endpoint=endpoint, model=model_name, version=version_label, status=status
        ).inc()
        REQUEST_LATENCY.labels(endpoint=endpoint, model=model_name, version=version_label).observe(
            time.perf_counter() - start
        )


def _run_model_endpoint(
    endpoint: str,
    model_name: str,
    default_version: str,
    get_model: Callable[[], Any],
    features: Any,
) -> Any:
    start = time.perf_counter()
    status = "error"
    version_label = default_version
    try:
        model = get_model()
        version_label = model.version
        response = model.predict(features)
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        MODEL_LATENCY_MS.labels(model=model_name).observe(elapsed_ms)
        budget_ms = settings.sla_budgets_ms.get(model_name, 25.0)
        if elapsed_ms > budget_ms:
            SLA_BREACH_TOTAL.labels(model=model_name, action="observed_breach").inc()
        status = "success"
        return response
    finally:
        REQUEST_COUNT.labels(
            endpoint=endpoint, model=model_name, version=version_label, status=status
        ).inc()
        REQUEST_LATENCY.labels(endpoint=endpoint, model=model_name, version=version_label).observe(
            time.perf_counter() - start
        )


@app.post("/inference/dynamic-batch")
async def dynamic_batch_endpoint(
    model_name: str,
    features: Dict[str, float],
    model_version: Optional[str] = None,
) -> Dict[str, Any]:
    registry = app.state.registry
    batcher: DynamicBatcher = app.state.batcher
    model_config = resolve_model_config(registry, model_name, model_version)
    budget_ms = settings.sla_budgets_ms.get(model_name, 25.0)
    if settings.strict_sla_timeout:
        output, sla_breached, _ = await enforce_async_sla(
            model_name,
            batcher.submit(model_name, features, model_config),
            budget_ms=budget_ms,
            model_version=model_config.version,
            fallback_on_timeout=True,
        )
        return {**output, "sla_breached": sla_breached}
    start = time.perf_counter()
    output = await batcher.submit(model_name, features, model_config)
    elapsed_ms = (time.perf_counter() - start) * 1000.0
    MODEL_LATENCY_MS.labels(model=model_name).observe(elapsed_ms)
    if elapsed_ms > budget_ms:
        SLA_BREACH_TOTAL.labels(model=model_name, action="observed_breach").inc()
    return output


@app.get("/metrics")
def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/models")
def list_models() -> Dict[str, Any]:
    registry = getattr(app.state, "registry", None)
    return {"models": registry.summary() if registry else {}}


@app.get("/health")
def health() -> Dict[str, Any]:
    registry = getattr(app.state, "registry", None)
    feature_store = getattr(app.state, "feature_store", None)
    return {
        "status": "ok",
        "models": registry.summary() if registry else {},
        "feature_store": feature_store.health() if feature_store else {"status": "ok"},
    }


@app.get("/health/ready")
def readiness() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/health/live")
def liveness() -> Dict[str, str]:
    return {"status": "ok"}


@app.post("/inference/eta", response_model=EtaResponse)
def eta_inference(request: EtaRequest, model_version: Optional[str] = None) -> EtaResponse:
    output = _run_linear_endpoint(
        "/inference/eta",
        "eta",
        model_version or request.model_version,
        eta_features(request),
    )
    return EtaResponse(**output)


@app.post("/inference/ranking", response_model=RankingResponse)
def ranking_inference(
    request: RankingRequest, model_version: Optional[str] = None
) -> RankingResponse:
    output = _run_linear_endpoint(
        "/inference/ranking",
        "ranking",
        model_version or request.model_version,
        ranking_features(request),
    )
    return RankingResponse(**output)


@app.post("/inference/fraud", response_model=FraudResponse)
def fraud_inference(request: FraudRequest, model_version: Optional[str] = None) -> FraudResponse:
    output = _run_linear_endpoint(
        "/inference/fraud",
        "fraud",
        model_version or request.model_version,
        fraud_features(request),
    )
    return FraudResponse(**output)


@app.post("/inference/batch", response_model=BatchInferenceResponse)
def batch_inference(request: BatchInferenceRequest) -> BatchInferenceResponse:
    registry = app.state.registry
    cache = app.state.cache
    feature_store = app.state.feature_store
    results = [process_batch_item(item, registry, cache, feature_store) for item in request.items]
    return BatchInferenceResponse(results=results)


@app.post("/inference/demand", response_model=DemandResponse)
def demand_inference(features: DemandFeatures) -> DemandResponse:
    return _run_model_endpoint(
        "/inference/demand",
        "demand",
        "demand-tft-v1",
        lambda: app.state.demand_model,
        features,
    )


@app.post("/inference/personalization", response_model=PersonalizationResponse)
def personalization_inference(features: PersonalizationFeatures) -> PersonalizationResponse:
    return _run_model_endpoint(
        "/inference/personalization",
        "personalization",
        "pers-ncf-v1",
        lambda: app.state.personalization_model,
        features,
    )


@app.post("/inference/clv", response_model=CLVResponse)
def clv_inference(features: CLVFeatures) -> CLVResponse:
    return _run_model_endpoint(
        "/inference/clv",
        "clv",
        "clv-bg-nbd-v1",
        lambda: app.state.clv_model,
        features,
    )


@app.post("/inference/pricing", response_model=PricingResponse)
def pricing_inference(features: PricingFeatures) -> PricingResponse:
    return _run_model_endpoint(
        "/inference/pricing",
        "pricing",
        "pricing-bandit-v1",
        lambda: app.state.pricing_model,
        features,
    )
