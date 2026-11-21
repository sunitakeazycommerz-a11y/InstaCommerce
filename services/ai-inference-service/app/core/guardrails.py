"""Public facade re-exporting online feature health, cold-start, and SLA guardrails."""

from __future__ import annotations

from app.core.cold_start import (
    COLD_START_FALLBACK_TOTAL,
    COLD_START_PRIORS,
    MODEL_OUTPUT_STD,
    ColdStartPrediction,
    build_cold_start_output,
    compute_confidence_interval,
    is_cold_start_context,
    score_cold_start_fallback,
)
from app.core.config import DEFAULT_SLA_BUDGETS_MS
from app.core.feature_health import (
    DEFAULT_FEATURE_BASELINES,
    FEATURE_DRIFT_ALERTS_TOTAL,
    FEATURE_MISSING_RATIO,
    OOD_FEATURE_TOTAL,
    FeatureBaseline,
    FeatureHealthReport,
    assess_feature_health,
)
from app.core.sla import (
    MODEL_LATENCY_MS,
    SLA_BREACH_TOTAL,
    SLABudgetExceededError,
    _handle_sla_timeout,
    _sla_budget_for_model,
    enforce_async_sla,
    enforce_sync_sla,
    get_sla_executor,
    observe_sla_latency,
    shutdown_sla_executor,
)

__all__ = [
    "COLD_START_FALLBACK_TOTAL",
    "COLD_START_PRIORS",
    "ColdStartPrediction",
    "DEFAULT_FEATURE_BASELINES",
    "DEFAULT_SLA_BUDGETS_MS",
    "FEATURE_DRIFT_ALERTS_TOTAL",
    "FEATURE_MISSING_RATIO",
    "FeatureBaseline",
    "FeatureHealthReport",
    "MODEL_LATENCY_MS",
    "MODEL_OUTPUT_STD",
    "OOD_FEATURE_TOTAL",
    "SLABudgetExceededError",
    "SLA_BREACH_TOTAL",
    "_handle_sla_timeout",
    "_sla_budget_for_model",
    "assess_feature_health",
    "build_cold_start_output",
    "compute_confidence_interval",
    "enforce_async_sla",
    "enforce_sync_sla",
    "get_sla_executor",
    "is_cold_start_context",
    "observe_sla_latency",
    "score_cold_start_fallback",
    "shutdown_sla_executor",
]
