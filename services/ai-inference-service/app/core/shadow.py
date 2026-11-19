"""Asynchronous shadow inference dispatcher and agreement/divergence tracker."""

from __future__ import annotations

import asyncio
from collections import deque
import inspect
import logging
import time
from typing import Any, Callable, Deque, Dict, List, Optional, Set, Tuple

from prometheus_client import Counter, Histogram

logger = logging.getLogger("ai_inference.shadow")

SHADOW_AGREEMENT_TOTAL = Counter(
    "ai_inference_shadow_agreement_total",
    "Shadow predictions agreeing with primary prediction within tolerance",
    ["model", "version"],
)
SHADOW_DIVERGENCE_TOTAL = Counter(
    "ai_inference_shadow_divergence_total",
    "Shadow predictions diverging from primary prediction beyond tolerance",
    ["model", "version"],
)
SHADOW_SCORE_DELTA = Histogram(
    "ai_inference_shadow_score_delta",
    "Absolute score difference between primary and shadow predictions",
    ["model", "version"],
)
SHADOW_LATENCY = Histogram(
    "ai_inference_shadow_latency_seconds",
    "Shadow prediction execution latency",
    ["model", "version"],
)


def _extract_score(output: Any) -> Optional[float]:
    """Extract primary numerical score from model output dict or scalar."""
    if isinstance(output, (int, float)) and not isinstance(output, bool):
        return float(output)
    if isinstance(output, dict):
        for key in (
            "eta_minutes",
            "ranking_score",
            "fraud_probability",
            "score",
            "prediction",
            "predicted_units",
            "delivery_fee_cents",
            "raw_score",
        ):
            val = output.get(key)
            if isinstance(val, (int, float)) and not isinstance(val, bool):
                return float(val)
    return None


def compare_shadow_output(
    primary_output: Any,
    shadow_output: Any,
    tolerance: float = 0.1,
) -> Tuple[bool, float]:
    """Compare primary and shadow outputs, returning (agreed, abs_delta)."""
    p_score = _extract_score(primary_output)
    s_score = _extract_score(shadow_output)
    if p_score is not None and s_score is not None:
        delta = abs(p_score - s_score)
        return delta <= tolerance, delta
    agreed = primary_output == shadow_output
    return agreed, 0.0 if agreed else 1.0


class ShadowRunner:
    """Dispatches shadow predictions via asyncio.create_task and tracks agreement."""

    def __init__(
        self,
        tolerance: float = 0.1,
        timeout_seconds: float = 0.25,
        max_history: int = 200,
    ) -> None:
        self.tolerance = tolerance
        self.timeout_seconds = max(0.001, timeout_seconds)
        self.agreement_count: int = 0
        self.divergence_count: int = 0
        self.error_count: int = 0
        self._pending_tasks: Set[asyncio.Task[Any]] = set()
        self._comparisons: Deque[Dict[str, Any]] = deque(maxlen=max(1, max_history))

    @property
    def stats(self) -> Dict[str, Any]:
        total = self.agreement_count + self.divergence_count
        return {
            "total": total,
            "agreed": self.agreement_count,
            "diverged": self.divergence_count,
            "errors": self.error_count,
            "agreement_rate": (self.agreement_count / total) if total > 0 else 1.0,
        }

    @property
    def recent_comparisons(self) -> List[Dict[str, Any]]:
        return list(self._comparisons)

    def _record_comparison(
        self,
        model_name: str,
        shadow_version: str,
        primary_output: Any,
        shadow_output: Any,
        elapsed_s: float,
    ) -> Tuple[bool, float]:
        SHADOW_LATENCY.labels(model=model_name, version=shadow_version).observe(elapsed_s)
        agreed, delta = compare_shadow_output(
            primary_output, shadow_output, tolerance=self.tolerance
        )
        SHADOW_SCORE_DELTA.labels(model=model_name, version=shadow_version).observe(delta)
        if agreed:
            self.agreement_count += 1
            SHADOW_AGREEMENT_TOTAL.labels(model=model_name, version=shadow_version).inc()
        else:
            self.divergence_count += 1
            SHADOW_DIVERGENCE_TOTAL.labels(model=model_name, version=shadow_version).inc()
        entry = {
            "model": model_name,
            "shadow_version": shadow_version,
            "primary_score": _extract_score(primary_output),
            "shadow_score": _extract_score(shadow_output),
            "delta": round(delta, 6),
            "tolerance": self.tolerance,
            "agreed": agreed,
            "elapsed_ms": round(elapsed_s * 1000.0, 3),
        }
        self._comparisons.append(entry)
        logger.info("shadow.comparison", extra=entry)
        return agreed, delta

    def _record_error(self, model_name: str, shadow_version: str, reason: str) -> None:
        self.error_count += 1
        logger.warning(
            "Shadow prediction failed for %s (%s): %s",
            model_name,
            shadow_version,
            reason,
        )

    async def _run_shadow(
        self,
        model_name: str,
        shadow_version: str,
        shadow_fn: Callable[..., Any],
        primary_output: Any,
        *args: Any,
        **kwargs: Any,
    ) -> Optional[Dict[str, Any]]:
        start = time.perf_counter()
        try:
            res = shadow_fn(*args, **kwargs)
            if inspect.isawaitable(res):
                shadow_output = await asyncio.wait_for(res, timeout=self.timeout_seconds)
            else:
                shadow_output = res
            agreed, delta = self._record_comparison(
                model_name,
                shadow_version,
                primary_output,
                shadow_output,
                time.perf_counter() - start,
            )
            return {
                "model": model_name,
                "version": shadow_version,
                "agreed": agreed,
                "delta": delta,
                "shadow_output": shadow_output,
            }
        except asyncio.TimeoutError:
            self._record_error(model_name, shadow_version, "TimeoutError")
            return None
        except Exception as exc:
            self._record_error(model_name, shadow_version, exc.__class__.__name__)
            return None

    def dispatch(
        self,
        model_name: str,
        shadow_version: str,
        shadow_fn: Callable[..., Any],
        primary_output: Any,
        *args: Any,
        **kwargs: Any,
    ) -> Optional[asyncio.Task[Any]]:
        """Schedule shadow prediction via asyncio.create_task without blocking caller."""
        try:
            asyncio.get_running_loop()
            coro = self._run_shadow(
                model_name, shadow_version, shadow_fn, primary_output, *args, **kwargs
            )
            task = asyncio.create_task(coro)
            self._pending_tasks.add(task)
            task.add_done_callback(self._pending_tasks.discard)
            return task
        except RuntimeError:
            # No running event loop; execute synchronous shadow_fn directly.
            start = time.perf_counter()
            try:
                res = shadow_fn(*args, **kwargs)
                if inspect.isawaitable(res):
                    if inspect.iscoroutine(res):
                        res.close()
                    self._record_error(
                        model_name,
                        shadow_version,
                        "async shadow_fn dropped without running event loop",
                    )
                    return None
                self._record_comparison(
                    model_name,
                    shadow_version,
                    primary_output,
                    res,
                    time.perf_counter() - start,
                )
            except Exception as exc:
                self._record_error(model_name, shadow_version, exc.__class__.__name__)
            return None


default_shadow_runner = ShadowRunner()


def dispatch_shadow_prediction(
    model_name: str,
    shadow_version: str,
    shadow_fn: Callable[..., Any],
    primary_output: Any,
    *args: Any,
    runner: Optional[ShadowRunner] = None,
    **kwargs: Any,
) -> Optional[asyncio.Task[Any]]:
    """Dispatch shadow prediction via asyncio.create_task on the active ShadowRunner."""
    active_runner = runner or default_shadow_runner
    return active_runner.dispatch(
        model_name, shadow_version, shadow_fn, primary_output, *args, **kwargs
    )
