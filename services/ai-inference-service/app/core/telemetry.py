"""W3C traceparent propagation, OpenTelemetry setup, and timed feature-store spans."""

from __future__ import annotations

import contextvars
import logging
import os
import re
import secrets
import time
from contextlib import asynccontextmanager, contextmanager
from typing import Any, AsyncIterator, Callable, Dict, Iterator, Optional

from fastapi import FastAPI, Request
from fastapi.responses import Response
from starlette.middleware.base import BaseHTTPMiddleware

from app.core.config import parse_bool

try:
    from opentelemetry import trace as otel_trace
except ImportError:
    otel_trace = None  # type: ignore[assignment]

try:
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor
except ImportError:
    OTLPSpanExporter = None  # type: ignore[assignment,misc]
    FastAPIInstrumentor = None  # type: ignore[assignment,misc]
    Resource = None  # type: ignore[assignment,misc]
    TracerProvider = None  # type: ignore[assignment,misc]
    BatchSpanProcessor = None  # type: ignore[assignment,misc]

logger = logging.getLogger("ai_inference.telemetry")

_TRACEPARENT_RE = re.compile(
    r"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$"
)
_HEX32_RE = re.compile(r"^[0-9a-f]{32}$")

_CURRENT_TRACE_CONTEXT: contextvars.ContextVar[Optional[Dict[str, str]]] = (
    contextvars.ContextVar("ai_inference_trace_context", default=None)
)


def parse_traceparent(header: Optional[str]) -> Optional[Dict[str, str]]:
    """Parse a W3C traceparent header (`00-<trace_id>-<span_id>-<flags>`)."""
    if not header or not isinstance(header, str):
        return None
    normalized = header.strip().lower()
    match = _TRACEPARENT_RE.fullmatch(normalized)
    if not match:
        return None
    version, trace_id, span_id, flags = match.groups()
    if version != "00" or trace_id == "0" * 32 or span_id == "0" * 16:
        return None
    return {
        "version": version,
        "trace_id": trace_id,
        "parent_span_id": span_id,
        "span_id": span_id,
        "flags": flags,
        "traceparent": normalized,
    }


def format_traceparent(
    trace_id: str, span_id: str, flags: str = "01", version: str = "00"
) -> str:
    """Format a W3C traceparent header string."""
    return f"{version}-{trace_id}-{span_id}-{flags}"


def create_child_trace_context(
    traceparent_header: Optional[str] = None,
    x_trace_id: Optional[str] = None,
) -> Dict[str, str]:
    """Parse incoming traceparent (or generate a new trace) and mint a child span ID."""
    parsed = parse_traceparent(traceparent_header)
    if parsed is not None:
        trace_id = parsed["trace_id"]
        parent_span_id = parsed["parent_span_id"]
        flags = parsed["flags"]
    else:
        candidate = (x_trace_id or "").strip().lower()
        if _HEX32_RE.fullmatch(candidate) and candidate != "0" * 32:
            trace_id = candidate
        else:
            trace_id = secrets.token_hex(16)
        parent_span_id = ""
        flags = "01"

    child_span_id = secrets.token_hex(8)
    while child_span_id == parent_span_id or child_span_id == "0" * 16:
        child_span_id = secrets.token_hex(8)

    traceparent = format_traceparent(trace_id, child_span_id, flags)
    return {
        "version": "00",
        "trace_id": trace_id,
        "parent_span_id": parent_span_id,
        "span_id": child_span_id,
        "flags": flags,
        "traceparent": traceparent,
    }


def get_current_trace_context() -> Optional[Dict[str, str]]:
    """Return active request/span trace context from contextvars."""
    return _CURRENT_TRACE_CONTEXT.get()


def set_current_trace_context(
    ctx: Optional[Dict[str, str]],
) -> contextvars.Token[Optional[Dict[str, str]]]:
    """Bind trace context into contextvars and return reset token."""
    return _CURRENT_TRACE_CONTEXT.set(ctx)


def reset_current_trace_context(
    token: contextvars.Token[Optional[Dict[str, str]]],
) -> None:
    """Restore previous trace context using token."""
    _CURRENT_TRACE_CONTEXT.reset(token)


def _prepare_span_info(
    operation: str,
    entity_id: str,
    traceparent_override: Optional[str],
    on_span_created: Optional[Callable[[Dict[str, Any]], None]],
) -> tuple[Dict[str, str], Dict[str, Any]]:
    active_ctx = get_current_trace_context()
    parent_tp = traceparent_override or (active_ctx.get("traceparent") if active_ctx else None)
    parent_trace_id = active_ctx.get("trace_id") if active_ctx else None
    child_ctx = create_child_trace_context(parent_tp, parent_trace_id)
    headers = {
        "traceparent": child_ctx["traceparent"],
        "X-Trace-Id": child_ctx["trace_id"],
    }
    span_info: Dict[str, Any] = {
        "operation": operation,
        "entity_id": entity_id,
        "trace_id": child_ctx["trace_id"],
        "parent_span_id": child_ctx["parent_span_id"],
        "span_id": child_ctx["span_id"],
        "flags": child_ctx["flags"],
        "traceparent": child_ctx["traceparent"],
        "X-Trace-Id": child_ctx["trace_id"],
        "headers": headers,
    }
    if on_span_created is not None:
        on_span_created(span_info)
    return child_ctx, span_info


def _enter_otel_span(
    operation: str,
    entity_id: str,
    child_ctx: Dict[str, str],
    db_system: str,
) -> Any:
    if otel_trace is None:
        return None
    try:
        tracer = otel_trace.get_tracer("ai-inference-service.feature-store")
        cm = tracer.start_as_current_span(f"{db_system}.{operation}")
        otel_span = cm.__enter__()
        otel_span.set_attribute("db.system", db_system)
        otel_span.set_attribute("feature_store.entity_id", entity_id)
        otel_span.set_attribute("traceparent", child_ctx["traceparent"])
        otel_span.set_attribute("http.request.header.traceparent", child_ctx["traceparent"])
        otel_span.set_attribute("X-Trace-Id", child_ctx["trace_id"])
        return cm
    except Exception as exc:
        logger.warning("Failed to start OTel span for %s: %s", operation, exc)
        return None


def _exit_otel_span(
    otel_cm: Any,
    operation: str,
    exc_info: tuple[Any, Any, Any],
) -> None:
    if otel_cm is None:
        return
    try:
        otel_cm.__exit__(*exc_info)
    except Exception as exc:
        logger.warning("Failed to close OTel span for %s: %s", operation, exc)


@contextmanager
def feature_span(
    operation: str,
    entity_id: str,
    traceparent_override: Optional[str] = None,
    db_system: str = "redis",
    on_span_created: Optional[Callable[[Dict[str, Any]], None]] = None,
) -> Iterator[Dict[str, Any]]:
    """Wrap synchronous feature-store I/O inside a timed child OTel span."""
    child_ctx, span_info = _prepare_span_info(
        operation, entity_id, traceparent_override, on_span_created
    )
    start = time.perf_counter()
    token = set_current_trace_context(child_ctx)
    otel_cm = _enter_otel_span(operation, entity_id, child_ctx, db_system)
    exc_info: tuple[Any, Any, Any] = (None, None, None)
    try:
        yield span_info
    except BaseException as exc:
        exc_info = (type(exc), exc, exc.__traceback__)
        raise
    finally:
        span_info["duration_ms"] = round((time.perf_counter() - start) * 1000.0, 4)
        _exit_otel_span(otel_cm, operation, exc_info)
        reset_current_trace_context(token)


@asynccontextmanager
async def async_feature_span(
    operation: str,
    entity_id: str,
    traceparent_override: Optional[str] = None,
    db_system: str = "redis",
    on_span_created: Optional[Callable[[Dict[str, Any]], None]] = None,
) -> AsyncIterator[Dict[str, Any]]:
    """Wrap asynchronous feature-store I/O inside a timed child OTel span."""
    child_ctx, span_info = _prepare_span_info(
        operation, entity_id, traceparent_override, on_span_created
    )
    start = time.perf_counter()
    token = set_current_trace_context(child_ctx)
    otel_cm = _enter_otel_span(operation, entity_id, child_ctx, db_system)
    exc_info: tuple[Any, Any, Any] = (None, None, None)
    try:
        yield span_info
    except BaseException as exc:
        exc_info = (type(exc), exc, exc.__traceback__)
        raise
    finally:
        span_info["duration_ms"] = round((time.perf_counter() - start) * 1000.0, 4)
        _exit_otel_span(otel_cm, operation, exc_info)
        reset_current_trace_context(token)


class TraceContextMiddleware(BaseHTTPMiddleware):
    """W3C traceparent context propagation middleware."""

    async def dispatch(self, request: Request, call_next: Any) -> Response:
        incoming_tp = request.headers.get("traceparent")
        incoming_trace_id = request.headers.get("x-trace-id")
        ctx = create_child_trace_context(incoming_tp, incoming_trace_id)
        request.state.trace_context = ctx
        request.state.trace_id = ctx["trace_id"]
        request.state.span_id = ctx["span_id"]
        request.state.traceparent = ctx["traceparent"]
        token = set_current_trace_context(ctx)
        try:
            response: Response = await call_next(request)
            response.headers["traceparent"] = ctx["traceparent"]
            response.headers["X-Trace-Id"] = ctx["trace_id"]
            return response
        finally:
            reset_current_trace_context(token)


def init_telemetry(app_instance: FastAPI) -> Optional[Any]:
    otel_enabled = parse_bool(os.getenv("AI_INFERENCE_OTEL_ENABLED"), True)
    if not otel_enabled:
        return None
    if (
        otel_trace is None
        or OTLPSpanExporter is None
        or FastAPIInstrumentor is None
        or Resource is None
        or TracerProvider is None
        or BatchSpanProcessor is None
    ):
        logger.warning("OpenTelemetry instrumentation unavailable")
        return None

    service_name = os.getenv("AI_INFERENCE_OTEL_SERVICE_NAME", "ai-inference-service")
    resource = Resource.create({"service.name": service_name})
    provider = TracerProvider(resource=resource)
    endpoint = os.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
    exporter = OTLPSpanExporter(endpoint=endpoint) if endpoint else OTLPSpanExporter()
    provider.add_span_processor(BatchSpanProcessor(exporter))
    otel_trace.set_tracer_provider(provider)
    FastAPIInstrumentor.instrument_app(app_instance)
    return otel_trace.get_tracer("ai-inference-service")
