"""Core utilities for AI Inference Service (batching and shadow mode)."""

from app.core.batching import DynamicBatcher, _BatchItem
from app.core.shadow import (
    SHADOW_AGREEMENT_TOTAL,
    SHADOW_DIVERGENCE_TOTAL,
    SHADOW_LATENCY,
    SHADOW_SCORE_DELTA,
    ShadowRunner,
    compare_shadow_output,
    dispatch_shadow_prediction,
)

__all__ = [
    "DynamicBatcher",
    "_BatchItem",
    "SHADOW_AGREEMENT_TOTAL",
    "SHADOW_DIVERGENCE_TOTAL",
    "SHADOW_LATENCY",
    "SHADOW_SCORE_DELTA",
    "ShadowRunner",
    "compare_shadow_output",
    "dispatch_shadow_prediction",
]
