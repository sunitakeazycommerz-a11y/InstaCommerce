"""Per-model SLA latency budgets and timeout fallback enforcement."""

from __future__ import annotations

import asyncio
import atexit
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeoutError
import logging
import os
import threading
import time
from typing import Any, Awaitable, Callable, Dict, Optional, Tuple

from prometheus_client import Counter, Histogram

from app.core.cold_start import build_cold_start_output
from app.core.config import DEFAULT_SLA_BUDGETS_MS

logger = logging.getLogger("ai_inference.guardrails")

SLA_BREACH_TOTAL = Counter("ai_inference_sla_breaches_total", "Per-model SLA timeout breaches", ["model", "action"])
MODEL_LATENCY_MS = Histogram("ai_inference_model_duration_ms", "Per-model inference latency in ms", ["model"])

_SLA_EXECUTOR_LOCK = threading.Lock()
_SLA_EXECUTOR: Optional[ThreadPoolExecutor] = None
_SLA_MAX_WORKERS = max(4, min(32, (os.cpu_count() or 2) * 2))


def get_sla_executor() -> ThreadPoolExecutor:
    global _SLA_EXECUTOR
    with _SLA_EXECUTOR_LOCK:
        if _SLA_EXECUTOR is None:
            _SLA_EXECUTOR = ThreadPoolExecutor(
                max_workers=_SLA_MAX_WORKERS,
                thread_name_prefix="ai-inference-sla",
            )
        return _SLA_EXECUTOR


def shutdown_sla_executor(wait: bool = False) -> None:
    global _SLA_EXECUTOR
    with _SLA_EXECUTOR_LOCK:
        executor = _SLA_EXECUTOR
        _SLA_EXECUTOR = None
    if executor is not None:
        executor.shutdown(wait=wait, cancel_futures=True)


atexit.register(shutdown_sla_executor)


class SLABudgetExceededError(TimeoutError):
    """Raised when strict SLA enforcement aborts an over-budget inference call."""


def _sla_budget_for_model(model_name: str, override_ms: Optional[float] = None) -> float:
    return (
        float(override_ms)
        if override_ms is not None and override_ms > 0
        else DEFAULT_SLA_BUDGETS_MS.get(model_name, 25.0)
    )


def _handle_sla_timeout(
    model_name: str,
    model_version: str,
    budget: float,
    elapsed_ms: float,
    fallback_on_timeout: bool,
    exc: BaseException,
) -> Tuple[Dict[str, Any], bool, float]:
    MODEL_LATENCY_MS.labels(model=model_name).observe(elapsed_ms)
    if not fallback_on_timeout:
        SLA_BREACH_TOTAL.labels(model=model_name, action="rejected").inc()
        raise SLABudgetExceededError(f"Model {model_name} exceeded SLA budget ({budget:.1f}ms)") from exc
    SLA_BREACH_TOTAL.labels(model=model_name, action="fallback").inc()
    logger.warning(
        "sla.timeout_fallback",
        extra={"model": model_name, "budget_ms": budget, "elapsed_ms": round(elapsed_ms, 2)},
    )
    fallback = build_cold_start_output(
        model_name, model_version, reason="sla_timeout", sla_breached=True
    )
    return fallback, True, elapsed_ms


def observe_sla_latency(model_name: str, elapsed_ms: float, budget_ms: float) -> bool:
    MODEL_LATENCY_MS.labels(model=model_name).observe(elapsed_ms)
    if elapsed_ms > budget_ms:
        SLA_BREACH_TOTAL.labels(model=model_name, action="observed_breach").inc()
        logger.warning(
            "sla.observed_breach",
            extra={"model": model_name, "budget_ms": budget_ms, "elapsed_ms": round(elapsed_ms, 2)},
        )
        return True
    return False


def enforce_sync_sla(
    model_name: str,
    fn: Callable[[], Dict[str, Any]],
    *,
    budget_ms: Optional[float] = None,
    strict: bool = False,
    model_version: str = "fallback",
    fallback_on_timeout: bool = True,
) -> Tuple[Dict[str, Any], bool, float]:
    budget = _sla_budget_for_model(model_name, budget_ms)
    start = time.perf_counter()

    if strict:
        future = get_sla_executor().submit(fn)
        try:
            result = future.result(timeout=budget / 1000.0)
        except FuturesTimeoutError as exc:
            future.cancel()
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            return _handle_sla_timeout(
                model_name, model_version, budget, elapsed_ms, fallback_on_timeout, exc
            )
    else:
        result = fn()

    elapsed_ms = (time.perf_counter() - start) * 1000.0
    breached = observe_sla_latency(model_name, elapsed_ms, budget)
    return result, breached, elapsed_ms


async def enforce_async_sla(
    model_name: str,
    awaitable: Awaitable[Dict[str, Any]],
    *,
    budget_ms: Optional[float] = None,
    model_version: str = "fallback",
    fallback_on_timeout: bool = True,
) -> Tuple[Dict[str, Any], bool, float]:
    budget = _sla_budget_for_model(model_name, budget_ms)
    start = time.perf_counter()
    try:
        result = await asyncio.wait_for(awaitable, timeout=budget / 1000.0)
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        MODEL_LATENCY_MS.labels(model=model_name).observe(elapsed_ms)
        return result, False, elapsed_ms
    except asyncio.TimeoutError as exc:
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return _handle_sla_timeout(
            model_name, model_version, budget, elapsed_ms, fallback_on_timeout, exc
        )
