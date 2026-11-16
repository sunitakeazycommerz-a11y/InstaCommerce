"""Feature Store module for AI inference service."""

from .redis_client import (
    FeatureFetcher,
    RedisFeatureClient,
    RedisFeatureStoreClient,
    create_child_trace_context,
    format_traceparent,
    get_current_trace_context,
    parse_traceparent,
    reset_current_trace_context,
    set_current_trace_context,
)

__all__ = [
    "FeatureFetcher",
    "RedisFeatureClient",
    "RedisFeatureStoreClient",
    "create_child_trace_context",
    "format_traceparent",
    "get_current_trace_context",
    "parse_traceparent",
    "reset_current_trace_context",
    "set_current_trace_context",
]
