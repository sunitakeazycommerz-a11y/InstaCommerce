"""FastAPI entrypoint for the InstaCommerce AI Orchestrator Service."""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from typing import Awaitable, Callable, Dict

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response

from app.api.handlers import (
    get_registry,
    router as v2_router,
    set_audit_publisher,
    set_checkpoint_saver,
    set_compiled_graph,
    set_llm_client,
    set_retriever,
    set_semantic_cache,
    shutdown_handlers,
)
from app.api.v1 import router as v1_router
from app.audit import AuditEventPublisher, NoOpAuditPublisher
from app.auth import InternalServiceAuthMiddleware
from app.cache.semantic_cache import SemanticCache
from app.config import Settings
from app.core.llm import CachedRetrievalProvider, LlmClient
from app.core.logging import configure_logging
from app.core.telemetry import init_telemetry
from app.graph.checkpoints import RedisCheckpointSaver
from app.graph.graph import build_agent_graph
from app.guardrails.rate_limiter import TokenBucketRateLimiter
from app.middleware import enforce_agent_guardrails

settings = Settings()
logger = logging.getLogger("ai_orchestrator")
configure_logging(settings)


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    llm_client = LlmClient(settings)
    retriever = CachedRetrievalProvider(
        ttl_seconds=settings.rag_cache_ttl_seconds,
        max_entries=settings.rag_cache_max_entries,
        max_results=settings.rag_max_results,
    )
    audit_publisher = (
        AuditEventPublisher(
            bootstrap_servers=settings.kafka_bootstrap_servers,
            topic=settings.audit_topic,
        )
        if settings.audit_enabled
        else NoOpAuditPublisher()
    )
    await audit_publisher.start()
    set_audit_publisher(audit_publisher)

    checkpoint_saver = RedisCheckpointSaver(redis_url=settings.redis_url)
    set_checkpoint_saver(checkpoint_saver)

    registry = await get_registry()
    agent_graph = build_agent_graph(
        settings=settings,
        tool_registry=registry,
        checkpointer=checkpoint_saver,
    )
    set_compiled_graph(agent_graph)
    set_llm_client(llm_client)
    set_retriever(retriever)
    semantic_cache = SemanticCache()
    set_semantic_cache(semantic_cache)

    app_instance.state.audit_publisher = audit_publisher
    app_instance.state.checkpoint_saver = checkpoint_saver
    app_instance.state.agent_graph = agent_graph
    app_instance.state.tool_registry = registry
    app_instance.state.llm = llm_client
    app_instance.state.retriever = retriever
    app_instance.state.semantic_cache = semantic_cache
    app_instance.state.agent_ip_rate_limiter = TokenBucketRateLimiter(
        rate_per_minute=settings.agent_ip_rate_limit_per_minute,
        burst=settings.agent_ip_rate_limit_burst,
    )
    app_instance.state.agent_user_rate_limiter = TokenBucketRateLimiter(
        rate_per_minute=settings.agent_user_rate_limit_per_minute,
        burst=settings.agent_user_rate_limit_burst,
    )
    app_instance.state.agent_request_semaphore = asyncio.Semaphore(
        settings.agent_max_inflight_requests
    )
    try:
        yield
    finally:
        try:
            await audit_publisher.stop()
        except Exception:
            logger.exception("lifespan.audit_publisher_stop_failed")
        await shutdown_handlers()


app = FastAPI(title=settings.app_name, lifespan=lifespan)
app.state.tracer = init_telemetry(app, settings)


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Catch-all exception handler that logs details server-side without leaking exc."""
    logger.exception("unhandled.exception", extra={"path": str(request.url.path)})
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal server error", "error": "INTERNAL_ERROR"},
    )


app.include_router(v1_router)
app.include_router(v2_router)


@app.middleware("http")
async def agent_guardrails_middleware(
    request: Request,
    call_next: Callable[[Request], Awaitable[Response]],
) -> Response:
    return await enforce_agent_guardrails(request, call_next, settings)


# Register InternalServiceAuthMiddleware outermost so unauthenticated requests
# are rejected before consuming rate-limiter tokens or semaphore concurrency slots.
app.add_middleware(InternalServiceAuthMiddleware)


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/health/ready")
async def readiness() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/health/live")
async def liveness() -> Dict[str, str]:
    return {"status": "ok"}
