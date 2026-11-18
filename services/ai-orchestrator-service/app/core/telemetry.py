"""OpenTelemetry initialization for AI Orchestrator Service."""

from __future__ import annotations

import logging
from typing import Any, Optional

from fastapi import FastAPI

from app.config import Settings

try:
    from opentelemetry import trace as otel_trace
except ImportError:
    otel_trace = None  # type: ignore[assignment]

try:
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
    from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor
except ImportError:
    OTLPSpanExporter = None  # type: ignore[assignment,misc]
    FastAPIInstrumentor = None  # type: ignore[assignment,misc]
    HTTPXClientInstrumentor = None  # type: ignore[assignment,misc]
    Resource = None  # type: ignore[assignment,misc]
    TracerProvider = None  # type: ignore[assignment,misc]
    BatchSpanProcessor = None  # type: ignore[assignment,misc]

logger = logging.getLogger("ai_orchestrator.telemetry")


def init_telemetry(app_instance: FastAPI, settings: Settings) -> Optional[Any]:
    if not settings.otel_enabled:
        return None
    if (
        otel_trace is None
        or OTLPSpanExporter is None
        or FastAPIInstrumentor is None
        or HTTPXClientInstrumentor is None
        or Resource is None
        or TracerProvider is None
        or BatchSpanProcessor is None
    ):
        logger.warning("OpenTelemetry instrumentation unavailable")
        return None

    resource = Resource.create({"service.name": settings.otel_service_name or settings.app_name})
    provider = TracerProvider(resource=resource)
    endpoint = str(settings.otel_exporter_otlp_endpoint) if settings.otel_exporter_otlp_endpoint else None
    exporter = OTLPSpanExporter(endpoint=endpoint) if endpoint else OTLPSpanExporter()
    provider.add_span_processor(BatchSpanProcessor(exporter))
    otel_trace.set_tracer_provider(provider)
    FastAPIInstrumentor.instrument_app(app_instance)
    HTTPXClientInstrumentor().instrument()
    return otel_trace.get_tracer(settings.app_name)
