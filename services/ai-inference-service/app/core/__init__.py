"""Core utilities for AI Inference Service (shadow mode and telemetry)."""

from app.core.shadow import (
    SHADOW_AGREEMENT_TOTAL,
    SHADOW_DIVERGENCE_TOTAL,
    SHADOW_LATENCY,
    SHADOW_SCORE_DELTA,
    ShadowRunner,
    compare_shadow_output,
    dispatch_shadow_prediction,
)
from app.core.telemetry import (
    TraceContextMiddleware,
    async_feature_span,
    create_child_trace_context,
    feature_span,
    format_traceparent,
    get_current_trace_context,
    init_telemetry,
    parse_traceparent,
    reset_current_trace_context,
    set_current_trace_context,
)

__all__ = [
    "SHADOW_AGREEMENT_TOTAL",
    "SHADOW_DIVERGENCE_TOTAL",
    "SHADOW_LATENCY",
    "SHADOW_SCORE_DELTA",
    "ShadowRunner",
    "TraceContextMiddleware",
    "async_feature_span",
    "compare_shadow_output",
    "create_child_trace_context",
    "dispatch_shadow_prediction",
    "feature_span",
    "format_traceparent",
    "get_current_trace_context",
    "init_telemetry",
    "parse_traceparent",
    "reset_current_trace_context",
    "set_current_trace_context",
]
