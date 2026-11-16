"""Asynchronous shadow inference dispatcher and agreement/divergence tracker."""

from __future__ import annotations

import asyncio
import inspect
import logging
import time
from typing import Any, Callable, Dict, Optional, Set, Tuple

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

    def __init__(self, tolerance: float = 0.1) -> None:
        self.tolerance = tolerance
        self.agreement_count: int = 0
        self.divergence_count: int = 0
        self.error_count: int = 0
        self._pending_tasks: Set[asyncio.Task[Any]] = set()

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
                shadow_output = await res
            else:
                shadow_output = res
            SHADOW_LATENCY.labels(model=model_name, version=shadow_version).observe(
                time.perf_counter() - start
            )
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
            return {
                "model": model_name,
                "version": shadow_version,
                "agreed": agreed,
                "delta": delta,
                "shadow_output": shadow_output,
            }
        except Exception as exc:
            self.error_count += 1
            logger.warning(
                "Shadow prediction failed for %s (%s): %s",
                model_name,
                shadow_version,
                exc.__class__.__name__,
            )
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
        coro = self._run_shadow(
            model_name, shadow_version, shadow_fn, primary_output, *args, **kwargs
        )
        try:
            task = asyncio.create_task(coro)
            self._pending_tasks.add(task)
            task.add_done_callback(self._pending_tasks.discard)
            return task
        except RuntimeError:
            # No running event loop; run coroutine synchronously in an isolated call
            coro.close()
            start = time.perf_counter()
            try:
                res = shadow_fn(*args, **kwargs)
                if not inspect.isawaitable(res):
                    SHADOW_LATENCY.labels(model=model_name, version=shadow_version).observe(
                        time.perf_counter() - start
                    )
                    agreed, delta = compare_shadow_output(
                        primary_output, res, tolerance=self.tolerance
                    )
                    SHADOW_SCORE_DELTA.labels(model=model_name, version=shadow_version).observe(
                        delta
                    )
                    if agreed:
                        self.agreement_count += 1
                        SHADOW_AGREEMENT_TOTAL.labels(
                            model=model_name, version=shadow_version
                        ).inc()
                    else:
                        self.divergence_count += 1
                        SHADOW_DIVERGENCE_TOTAL.labels(
                            model=model_name, version=shadow_version
                        ).inc()
            except Exception as exc:
                self.error_count += 1
                logger.warning(
                    "Shadow prediction failed for %s (%s): %s",
                    model_name,
                    shadow_version,
                    exc.__class__.__name__,
                )
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
