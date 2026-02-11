"""Shadow mode: run new model alongside production, compare results without serving.

Shadow mode allows safe validation of new model versions in production traffic.
The shadow predictor runs on the same input as the production model, but its
results are **never** returned to the caller.  Instead, comparison metrics are
logged for offline analysis.
"""

import logging
import time
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout
from typing import Any, Dict, Optional

from .predictor import BasePredictor, PredictionResult

logger = logging.getLogger(__name__)

# Maximum time (seconds) to wait for shadow prediction before abandoning
_SHADOW_TIMEOUT_S: float = 1.0

# Thread pool shared across shadow runners
_EXECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="shadow")


class ShadowRunner:
    """Executes shadow predictions alongside production and logs comparison metrics.

    Usage::

        runner = ShadowRunner()
        prod_result = production_predictor.predict(features)
        runner.run_shadow(prod_result, shadow_predictor, features)
        # prod_result is returned to the caller — shadow result is never served.
    """

    def __init__(self, timeout_s: float = _SHADOW_TIMEOUT_S) -> None:
        self._timeout_s: float = timeout_s
        self._comparison_count: int = 0
        self._agreement_count: int = 0

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def run_shadow(
        self,
        production_result: PredictionResult,
        shadow_predictor: BasePredictor,
        features: Dict[str, Any],
    ) -> Optional[Dict[str, Any]]:
        """Run the shadow predictor asynchronously and log comparison.

        Args:
            production_result: The result already served from the production model.
            shadow_predictor: The candidate model to evaluate.
            features: The same feature dict sent to production.

        Returns:
            A comparison metrics dict (for testing / debugging), or ``None``
            if the shadow prediction failed or timed out.
        """
        try:
            future = _EXECUTOR.submit(shadow_predictor.predict, features)
            shadow_result = future.result(timeout=self._timeout_s)
            comparison = self._compare(production_result, shadow_result)
            self._log_comparison(production_result, shadow_result, comparison)
            return comparison
        except FuturesTimeout:
            logger.warning(
                "Shadow prediction timed out",
                extra={
                    "model": shadow_predictor.model_name,
                    "timeout_s": self._timeout_s,
                },
            )
            return None
        except Exception as exc:
            logger.error(
                "Shadow prediction failed",
                extra={
                    "model": shadow_predictor.model_name,
                    "error": str(exc),
                },
            )
            return None

    @property
    def agreement_rate(self) -> float:
        """Fraction of shadow predictions that agreed with production."""
        if self._comparison_count == 0:
            return 0.0
        return self._agreement_count / self._comparison_count

    @property
    def total_comparisons(self) -> int:
        return self._comparison_count

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _compare(
        self,
        production: PredictionResult,
        shadow: PredictionResult,
    ) -> Dict[str, Any]:
        """Compute comparison metrics between production and shadow results."""
        self._comparison_count += 1

        prod_output = production.output
        shadow_output = shadow.output

        # Generic numeric delta for the first shared numeric key
        score_delta: Optional[float] = None
        agrees = True
        for key in prod_output:
            if key in shadow_output:
                pv = prod_output[key]
                sv = shadow_output[key]
                if isinstance(pv, (int, float)) and isinstance(sv, (int, float)):
                    score_delta = abs(float(pv) - float(sv))
                    # Agreement if within 10 % relative tolerance
                    if pv != 0 and score_delta / abs(float(pv)) > 0.10:
                        agrees = False
                elif pv != sv:
                    agrees = False

        if agrees:
            self._agreement_count += 1

        return {
            "agrees": agrees,
            "score_delta": score_delta,
            "production_version": production.model_version,
            "shadow_version": shadow.model_version,
            "production_latency_ms": production.latency_ms,
            "shadow_latency_ms": shadow.latency_ms,
            "latency_overhead_ms": shadow.latency_ms - production.latency_ms,
        }

    @staticmethod
    def _log_comparison(
        production: PredictionResult,
        shadow: PredictionResult,
        comparison: Dict[str, Any],
    ) -> None:
        """Emit structured log for offline analysis."""
        logger.info(
            "Shadow comparison",
            extra={
                "model": production.model_name,
                "production_version": production.model_version,
                "shadow_version": shadow.model_version,
                "agrees": comparison["agrees"],
                "score_delta": comparison.get("score_delta"),
                "production_latency_ms": production.latency_ms,
                "shadow_latency_ms": shadow.latency_ms,
            },
        )
