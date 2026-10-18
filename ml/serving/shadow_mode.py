"""Shadow mode: run new model alongside production, compare results without serving.

Shadow mode allows safe validation of new model versions in production traffic.
The shadow predictor runs on the same input as the production model, but its
results are **never** returned to the caller.  Instead, comparison metrics and
detailed feature logs are streamed to Kafka/BigQuery for offline audit,
agreement monitoring, and CI promotion gate verification.
"""

import datetime
import json
import logging
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeout
from dataclasses import asdict, dataclass
from typing import Any, Callable, Dict, List, Optional

from .predictor import BasePredictor, PredictionResult

logger = logging.getLogger(__name__)

# Maximum time (seconds) to wait for shadow prediction before abandoning
_SHADOW_TIMEOUT_S: float = 1.0

# Thread pool shared across shadow runners
_EXECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="shadow")

# Default Kafka topic for shadow prediction events
DEFAULT_SHADOW_TOPIC = "ml.shadow.events"

# Minimum agreement rate required for model promotion (92%)
DEFAULT_PROMOTION_AGREEMENT_THRESHOLD: float = 0.92


class ShadowAgreementGateFailedError(Exception):
    """Raised when shadow agreement rate fails the promotion threshold."""
    pass


@dataclass
class ShadowPredictionLog:
    """Structured record for shadow vs champion prediction comparison."""
    event_id: str
    timestamp: str
    model_name: str
    champion_version: str
    shadow_version: str
    features: Dict[str, Any]
    prediction_champion: Dict[str, Any]
    prediction_shadow: Dict[str, Any]
    agrees: bool
    score_delta: Optional[float]
    champion_latency_ms: float
    shadow_latency_ms: float
    latency_overhead_ms: float

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    def to_bigquery_row(self) -> Dict[str, Any]:
        """Convert log record to BigQuery row format with JSON serialized fields."""
        return {
            "event_id": self.event_id,
            "timestamp": self.timestamp,
            "model_name": self.model_name,
            "champion_version": self.champion_version,
            "shadow_version": self.shadow_version,
            "features_json": json.dumps(self.features),
            "prediction_champion_json": json.dumps(self.prediction_champion),
            "prediction_shadow_json": json.dumps(self.prediction_shadow),
            "agrees": self.agrees,
            "score_delta": self.score_delta,
            "champion_latency_ms": self.champion_latency_ms,
            "shadow_latency_ms": self.shadow_latency_ms,
            "latency_overhead_ms": self.latency_overhead_ms,
        }


class ShadowRunner:
    """Executes shadow predictions alongside production, logs comparison metrics,
    and streams audit logs to Kafka and BigQuery.

    Usage::

        runner = ShadowRunner()
        prod_result = production_predictor.predict(features)
        runner.run_shadow(prod_result, shadow_predictor, features)
        # Verify CI gate before promotion:
        runner.verify_promotion_gate(threshold=0.92)
    """

    def __init__(
        self,
        timeout_s: float = _SHADOW_TIMEOUT_S,
        kafka_producer: Optional[Callable[[str, Dict[str, Any]], None]] = None,
        kafka_topic: str = DEFAULT_SHADOW_TOPIC,
    ) -> None:
        self._timeout_s: float = timeout_s
        self._comparison_count: int = 0
        self._agreement_count: int = 0
        self._kafka_producer = kafka_producer
        self._kafka_topic = kafka_topic
        self._log_buffer: List[ShadowPredictionLog] = []

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def run_shadow(
        self,
        production_result: PredictionResult,
        shadow_predictor: BasePredictor,
        features: Dict[str, Any],
    ) -> Optional[Dict[str, Any]]:
        """Run the shadow predictor asynchronously, compare, and stream log.

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
            log_record = self._build_log_record(production_result, shadow_result, comparison, features)
            self._log_buffer.append(log_record)
            self._stream_log(log_record)
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

    @property
    def total_agreements(self) -> int:
        return self._agreement_count

    def get_buffered_logs(self) -> List[ShadowPredictionLog]:
        """Return buffered shadow prediction logs."""
        return list(self._log_buffer)

    def clear_buffer(self) -> None:
        """Clear buffered shadow prediction logs."""
        self._log_buffer.clear()

    def export_to_bigquery_rows(self) -> List[Dict[str, Any]]:
        """Export all buffered logs in BigQuery-compatible schema."""
        return [log.to_bigquery_row() for log in self._log_buffer]

    def verify_promotion_gate(
        self,
        threshold: float = DEFAULT_PROMOTION_AGREEMENT_THRESHOLD,
        min_samples: int = 1,
        raise_on_failure: bool = True,
    ) -> bool:
        """Evaluate shadow agreement promotion gate.

        Args:
            threshold: Minimum agreement fraction required (default 0.92 / 92%).
            min_samples: Minimum comparisons required before gate can pass.
            raise_on_failure: If True, raises ShadowAgreementGateFailedError on failure.

        Returns:
            True if gate passed, False otherwise.
        """
        if self._comparison_count < min_samples:
            msg = f"Insufficient shadow samples ({self._comparison_count} < {min_samples})"
            if raise_on_failure:
                raise ShadowAgreementGateFailedError(msg)
            logger.warning(msg)
            return False

        rate = self.agreement_rate
        passed = rate >= threshold
        if not passed:
            msg = (
                f"Shadow agreement gate failed: current agreement {rate:.4f} "
                f"is below required threshold {threshold:.4f} "
                f"({self._agreement_count}/{self._comparison_count} agreed)"
            )
            logger.warning(msg)
            if raise_on_failure:
                raise ShadowAgreementGateFailedError(msg)
            return False

        logger.info(
            "Shadow agreement gate PASSED: %.4f >= %.4f (%d/%d agreed)",
            rate,
            threshold,
            self._agreement_count,
            self._comparison_count,
        )
        return True

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

    def _build_log_record(
        self,
        production: PredictionResult,
        shadow: PredictionResult,
        comparison: Dict[str, Any],
        features: Dict[str, Any],
    ) -> ShadowPredictionLog:
        return ShadowPredictionLog(
            event_id=str(uuid.uuid4()),
            timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
            model_name=production.model_name,
            champion_version=production.model_version,
            shadow_version=shadow.model_version,
            features=features,
            prediction_champion=production.output,
            prediction_shadow=shadow.output,
            agrees=comparison["agrees"],
            score_delta=comparison.get("score_delta"),
            champion_latency_ms=production.latency_ms,
            shadow_latency_ms=shadow.latency_ms,
            latency_overhead_ms=comparison["latency_overhead_ms"],
        )

    def _stream_log(self, log_record: ShadowPredictionLog) -> None:
        """Stream shadow prediction record to Kafka or BigQuery pipeline."""
        if self._kafka_producer is not None:
            try:
                self._kafka_producer(self._kafka_topic, log_record.to_dict())
            except Exception as e:
                logger.warning("Failed to stream shadow prediction to Kafka: %s", e)

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
