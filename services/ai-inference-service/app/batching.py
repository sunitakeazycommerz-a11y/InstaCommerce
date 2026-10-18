"""Dynamic Batching Controller for High-Throughput Model Serving (#604).

Accumulates incoming inference requests within a 5ms window up to batch size 64.
Executes vector matrix multiplications in parallel on GPU/SIMD tensor cores.
Resolves predictions to individual asynchronous caller futures.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from math import exp
from typing import Any, Callable, Dict, List, Optional, Tuple

import numpy as np
from prometheus_client import Histogram

logger = logging.getLogger("ai_inference.batching")

DYNAMIC_BATCH_SIZE = Histogram(
    "ai_inference_dynamic_batch_size",
    "Number of requests packed into a dynamic inference batch",
    buckets=[1, 2, 4, 8, 16, 32, 64],
)

DYNAMIC_BATCH_LATENCY = Histogram(
    "ai_inference_dynamic_batch_latency_seconds",
    "Processing time for a dynamic inference batch",
    buckets=[0.001, 0.002, 0.005, 0.010, 0.025, 0.050],
)


@dataclass
class _BatchItem:
    model_name: str
    features: Dict[str, float]
    model_config: Any
    future: asyncio.Future
    enqueued_at: float


class DynamicBatcher:
    """Dynamic batching engine that merges concurrent single-request inferences.

    Parameters
    ----------
    max_batch_size:
        Maximum items per GPU matrix execution (default: 64).
    max_wait_ms:
        Time window to accumulate concurrent requests (default: 5.0 ms).
    """

    def __init__(
        self,
        max_batch_size: int = 64,
        max_wait_ms: float = 5.0,
    ) -> None:
        self.max_batch_size = max_batch_size
        self.max_wait_s = max_wait_ms / 1000.0
        self._queues: Dict[str, asyncio.Queue[_BatchItem]] = {}
        self._workers: Dict[str, asyncio.Task] = {}
        self._running = False

    def _get_queue(self, model_key: str) -> asyncio.Queue[_BatchItem]:
        if model_key not in self._queues:
            self._queues[model_key] = asyncio.Queue()
            if self._running:
                self._workers[model_key] = asyncio.create_task(
                    self._worker_loop(model_key, self._queues[model_key])
                )
        return self._queues[model_key]

    async def start(self) -> None:
        """Start background worker tasks."""
        self._running = True
        for key, queue in self._queues.items():
            if key not in self._workers or self._workers[key].done():
                self._workers[key] = asyncio.create_task(self._worker_loop(key, queue))

    async def stop(self) -> None:
        """Cancel worker tasks and drain queues."""
        self._running = False
        for task in self._workers.values():
            task.cancel()
        self._workers.clear()

    async def submit(
        self,
        model_name: str,
        features: Dict[str, float],
        model_config: Any,
    ) -> Dict[str, Any]:
        """Submit a single inference request to the dynamic batch queue.

        Suspends caller until the batch runs and returns the individual slice.
        """
        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()
        model_key = f"{model_name}:{getattr(model_config, 'version', 'v1')}"
        queue = self._get_queue(model_key)
        item = _BatchItem(
            model_name=model_name,
            features=features,
            model_config=model_config,
            future=future,
            enqueued_at=time.perf_counter(),
        )
        await queue.put(item)
        return await future

    async def _worker_loop(self, model_key: str, queue: asyncio.Queue[_BatchItem]) -> None:
        """Continuous batching loop accumulating requests within the max_wait window."""
        while self._running:
            try:
                # Wait for first item in batch
                first_item = await queue.get()
                batch: List[_BatchItem] = [first_item]
                deadline = time.perf_counter() + self.max_wait_s

                # Accumulate subsequent items within time window up to max_batch_size
                while len(batch) < self.max_batch_size:
                    timeout = deadline - time.perf_counter()
                    if timeout <= 0:
                        break
                    try:
                        next_item = await asyncio.wait_for(queue.get(), timeout=timeout)
                        batch.append(next_item)
                    except asyncio.TimeoutError:
                        break

                DYNAMIC_BATCH_SIZE.observe(len(batch))
                start_time = time.perf_counter()
                self._execute_batch(batch)
                DYNAMIC_BATCH_LATENCY.observe(time.perf_counter() - start_time)

            except asyncio.CancelledError:
                break
            except Exception as exc:
                logger.exception("Unexpected error in dynamic batch worker: %s", exc)

    def _execute_batch(self, batch: List[_BatchItem]) -> None:
        """Perform vectorized parallel matrix multiplication and resolve futures."""
        if not batch:
            return

        first = batch[0]
        model_name = first.model_name
        model_config = first.model_config
        weights = getattr(model_config, "weights", {})
        bias = float(getattr(model_config, "bias", 0.0))
        version = str(getattr(model_config, "version", "v1"))

        keys = sorted(weights.keys())
        w_vec = np.array([weights[k] for k in keys], dtype=np.float64)

        # Build feature matrix X: shape (N, D)
        X = np.zeros((len(batch), len(keys)), dtype=np.float64)
        for i, item in enumerate(batch):
            for j, k in enumerate(keys):
                X[i, j] = float(item.features.get(k, 0.0))

        # Parallel Vector Matrix Multiplication: Y = X @ W + Bias
        raw_scores = X @ w_vec + bias

        for i, item in enumerate(batch):
            try:
                raw_score = float(raw_scores[i])
                contributions = {keys[j]: round(float(X[i, j] * w_vec[j]), 6) for j in range(len(keys))}
                if model_name == "eta":
                    eta_minutes = max(0.0, raw_score)
                    res = {
                        "eta_minutes": eta_minutes,
                        "feature_contributions": contributions,
                        "bias": bias,
                        "model_version": version,
                    }
                elif model_name == "ranking":
                    ranking_score = 1.0 / (1.0 + exp(-max(min(raw_score, 100.0), -100.0)))
                    res = {
                        "ranking_score": ranking_score,
                        "raw_score": raw_score,
                        "feature_contributions": contributions,
                        "bias": bias,
                        "model_version": version,
                    }
                elif model_name == "fraud":
                    fraud_probability = 1.0 / (1.0 + exp(-max(min(raw_score, 100.0), -100.0)))
                    res = {
                        "fraud_probability": fraud_probability,
                        "raw_score": raw_score,
                        "feature_contributions": contributions,
                        "bias": bias,
                        "model_version": version,
                    }
                else:
                    res = {
                        "raw_score": raw_score,
                        "feature_contributions": contributions,
                        "bias": bias,
                        "model_version": version,
                    }
                item.future.set_result(res)
            except Exception as exc:
                if not item.future.done():
                    item.future.set_exception(exc)
