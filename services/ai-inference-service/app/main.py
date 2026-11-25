"""FastAPI entrypoint for the InstaCommerce AI Inference Service."""

from __future__ import annotations

import time
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

from fastapi import FastAPI
from fastapi.responses import Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel

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
    assess_feature_health,
    enforce_async_sla,
    observe_sla_latency,
    shutdown_sla_executor,
)
from app.core.inference import (
    REQUEST_COUNT,
    REQUEST_LATENCY,
    create_model_instances,
    create_onnx_sessions,
    process_batch_item,
    resolve_model_config,
    serve_inference_request,
)
from app.core.shadow import default_shadow_runner
from app.core.telemetry import TraceContextMiddleware, init_telemetry
from app.features.extractors import build_feature_store
from app.features.parity import evaluate_feature_parity
from app.features.redis_client import RedisFeatureStoreClient
from app.features.store import FeatureStoreClient, UnavailableFeatureStoreClient
from app.models import (
    CLVFeatures,
    CLVResponse,
    DemandFeatures,
    DemandResponse,
    PersonalizationFeatures,
    PersonalizationResponse,
    PricingFeatures,
    PricingResponse,
)
from app.schemas import (
    BatchInferenceRequest,
    BatchInferenceResponse,
    DriftCheckRequest,
    DriftCheckResponse,
    EtaRequest,
    EtaResponse,
    FraudRequest,
    FraudResponse,
    RankingRequest,
    RankingResponse,
)

configure_logging()


def _ensure_runtime_state(app_instance: FastAPI) -> None:
    if not hasattr(app_instance.state, "registry"):
        app_instance.state.registry = ModelRegistry.from_sources(DEFAULT_WEIGHTS, load_registry_data())
    if not hasattr(app_instance.state, "cache"):
        app_instance.state.cache = (
            InferenceCache(settings.cache_max_items, settings.cache_ttl_seconds)
            if settings.cache_enabled
            else None
        )
    if not hasattr(app_instance.state, "feature_store"):
        app_instance.state.feature_store = build_feature_store(
            settings, cache=app_instance.state.cache
        )
    if not hasattr(app_instance.state, "feature_fetcher"):
        fs = app_instance.state.feature_store
        app_instance.state.feature_fetcher = (
            fs
            if isinstance(fs, RedisFeatureStoreClient)
            else (
                RedisFeatureStoreClient(redis_url=settings.redis_url, cache=app_instance.state.cache)
                if settings.redis_url
                else None
            )
        )
    if not hasattr(app_instance.state, "models"):
        models = create_model_instances(settings.model_artifact_dir)
        app_instance.state.models = models
        app_instance.state.demand_model = models["demand"]
        app_instance.state.personalization_model = models["personalization"]
        app_instance.state.clv_model = models["clv"]
        app_instance.state.pricing_model = models["pricing"]
        app_instance.state.eta_model = models["eta"]
        app_instance.state.fraud_model = models["fraud"]
        app_instance.state.ranking_model = models["ranking"]
    if not hasattr(app_instance.state, "onnx_sessions"):
        app_instance.state.onnx_sessions = create_onnx_sessions(settings.onnx_model_dir)


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    _ensure_runtime_state(app_instance)
    batcher = DynamicBatcher(max_batch_size=64, max_wait_ms=5.0)
    await batcher.start()
    app_instance.state.batcher = batcher
    try:
        yield
    finally:
        await batcher.stop()
        default_shadow_runner.shutdown(wait=False)
        shutdown_sla_executor(wait=False)
        feature_store = getattr(app_instance.state, "feature_store", None)
        feature_fetcher = getattr(app_instance.state, "feature_fetcher", None)
        if feature_store is not None:
            feature_store.close()
        if feature_fetcher is not None and feature_fetcher is not feature_store:
            feature_fetcher.close()


app = FastAPI(title="AI Inference Service", version="1.1.0", lifespan=lifespan)

app.add_middleware(InternalServiceAuthMiddleware)
app.add_middleware(TraceContextMiddleware)

app.state.tracer = init_telemetry(app)


def _run_unified_endpoint(
    endpoint: str,
    model_name: str,
    request: BaseModel,
    requested_version: Optional[str],
) -> Dict[str, Any]:
    _ensure_runtime_state(app)
    start = time.perf_counter()
    status = "error"
    version_label = requested_version or "unknown"
    try:
        output, version_label = serve_inference_request(
            model_name,
            request,
            requested_version,
            registry=app.state.registry,
            cache=app.state.cache,
            models=app.state.models,
            onnx_sessions=app.state.onnx_sessions,
        )
        status = "success"
        return output
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
    observe_sla_latency(model_name, elapsed_ms, budget_ms)
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


@app.get("/shadow/stats")
def shadow_stats() -> Dict[str, Any]:
    return {
        "stats": default_shadow_runner.stats,
        "recent_comparisons": default_shadow_runner.recent_comparisons,
        "configured_shadow_models": dict(settings.shadow_models),
        "sample_rate": settings.shadow_sample_rate,
    }


@app.post("/drift/check", response_model=DriftCheckResponse)
def drift_check(request: DriftCheckRequest) -> DriftCheckResponse:
    _ensure_runtime_state(app)
    report = assess_feature_health(
        request.model_name,
        request.features,
        expected_features=request.expected_features,
        z_threshold=settings.ood_z_threshold,
        max_missing_ratio=settings.max_missing_feature_ratio,
    )
    parity_reports: List[Dict[str, Any]] = []
    parity_failed = False
    if request.offline_features:
        offline_map = request.offline_features
        fetcher: Optional[RedisFeatureStoreClient] = getattr(app.state, "feature_fetcher", None)
        feature_store: Optional[FeatureStoreClient] = getattr(app.state, "feature_store", None)

        def _get_online(eid: str) -> Dict[str, Any]:
            if fetcher is not None:
                return fetcher.get_user_features(eid)
            if (
                feature_store is not None
                and feature_store.backend != "none"
                and not isinstance(feature_store, UnavailableFeatureStoreClient)
            ):
                return feature_store.get_features(eid, list(offline_map.get(eid, {}).keys()))
            if request.entity_id == eid or len(offline_map) == 1:
                return dict(request.features)
            return {}

        parity_report = evaluate_feature_parity(
            _get_online,
            offline_map,
            max_drift_pct=request.max_drift_pct,
        )
        parity_failed = not bool(parity_report.get("passed", True))
        parity_reports = [parity_report]

    return DriftCheckResponse(
        model_name=report.model_name,
        drift_detected=report.drift_detected or parity_failed,
        missing_count=report.missing_count,
        expected_count=report.expected_count,
        missing_ratio=report.missing_ratio,
        missing_features=report.missing_features,
        ood_features=report.ood_features,
        max_z_score=report.max_z_score,
        missing_threshold_exceeded=report.missing_threshold_exceeded,
        parity_reports=parity_reports,
    )


@app.post("/inference/eta", response_model=EtaResponse)
async def eta_inference(request: EtaRequest, model_version: Optional[str] = None) -> EtaResponse:
    output = _run_unified_endpoint(
        "/inference/eta",
        "eta",
        request,
        model_version or request.model_version,
    )
    return EtaResponse(**output)


@app.post("/inference/ranking", response_model=RankingResponse)
async def ranking_inference(
    request: RankingRequest, model_version: Optional[str] = None
) -> RankingResponse:
    output = _run_unified_endpoint(
        "/inference/ranking",
        "ranking",
        request,
        model_version or request.model_version,
    )
    return RankingResponse(**output)


@app.post("/inference/fraud", response_model=FraudResponse)
async def fraud_inference(request: FraudRequest, model_version: Optional[str] = None) -> FraudResponse:
    output = _run_unified_endpoint(
        "/inference/fraud",
        "fraud",
        request,
        model_version or request.model_version,
    )
    return FraudResponse(**output)


@app.post("/inference/batch", response_model=BatchInferenceResponse)
async def batch_inference(request: BatchInferenceRequest) -> BatchInferenceResponse:
    _ensure_runtime_state(app)
    registry = app.state.registry
    cache = app.state.cache
    feature_store = app.state.feature_store
    results = [process_batch_item(item, registry, cache, feature_store) for item in request.items]
    return BatchInferenceResponse(results=results)


@app.post("/inference/demand", response_model=DemandResponse)
def demand_inference(
    features: DemandFeatures, model_version: Optional[str] = None
) -> DemandResponse:
    output = _run_unified_endpoint(
        "/inference/demand",
        "demand",
        features,
        model_version or features.model_version,
    )
    return DemandResponse(**output)


@app.post("/inference/personalization", response_model=PersonalizationResponse)
def personalization_inference(
    features: PersonalizationFeatures, model_version: Optional[str] = None
) -> PersonalizationResponse:
    output = _run_unified_endpoint(
        "/inference/personalization",
        "personalization",
        features,
        model_version or features.model_version,
    )
    return PersonalizationResponse(**output)


@app.post("/inference/clv", response_model=CLVResponse)
def clv_inference(
    features: CLVFeatures, model_version: Optional[str] = None
) -> CLVResponse:
    output = _run_unified_endpoint(
        "/inference/clv",
        "clv",
        features,
        model_version or features.model_version,
    )
    return CLVResponse(**output)


@app.post("/inference/pricing", response_model=PricingResponse)
def pricing_inference(
    features: PricingFeatures, model_version: Optional[str] = None
) -> PricingResponse:
    output = _run_unified_endpoint(
        "/inference/pricing",
        "pricing",
        features,
        model_version or features.model_version,
    )
    return PricingResponse(**output)
