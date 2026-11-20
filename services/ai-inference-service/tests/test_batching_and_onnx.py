import asyncio
import time
import numpy as np
import pytest

from app.batching import DynamicBatcher
from app.core.shadow import ShadowRunner
from app.models.onnx_runner import ManagedOnnxSession, check_memory_threshold, get_current_rss_bytes


@pytest.mark.asyncio
async def test_dynamic_batcher_concurrent_requests():
    """Verify dynamic batcher collects concurrent requests and resolves all individual futures (#604)."""
    batcher = DynamicBatcher(max_batch_size=64, max_wait_ms=10.0)
    await batcher.start()

    class DummyConfig:
        version = "ranking-linear-v1"
        bias = 0.15
        weights = {
            "relevance_score": 0.55,
            "price_score": 0.2,
            "availability_score": 0.35,
            "user_affinity": 0.4,
        }

    config = DummyConfig()

    async def single_call(idx: int):
        features = {
            "relevance_score": 0.8 + (idx % 10) * 0.01,
            "price_score": 0.5,
            "availability_score": 1.0,
            "user_affinity": 0.7,
        }
        res = await batcher.submit("ranking", features, config)
        return res

    # Launch 64 concurrent requests
    tasks = [single_call(i) for i in range(64)]
    results = await asyncio.gather(*tasks)

    assert len(results) == 64
    for r in results:
        assert "ranking_score" in r
        assert 0.0 <= r["ranking_score"] <= 1.0
        assert r["model_version"] == "ranking-linear-v1"

    await batcher.stop()


def test_managed_onnx_session_memory_leak_prevention():
    """Verify that running 10,000 continuous inferences does not leak native tensor memory (#612)."""
    session = ManagedOnnxSession()

    # Warm-up run
    for _ in range(100):
        feed = {"input": np.random.randn(1, 16).astype(np.float32)}
        session.run(["output"], feed)

    rss_before = get_current_rss_bytes()

    # Run 10,000 continuous inferences with memory reclamation
    for _ in range(10000):
        feed = {"input": np.random.randn(1, 16).astype(np.float32)}
        session.run(["output"], feed)

    rss_after = get_current_rss_bytes()
    growth_mb = (rss_after - rss_before) / (1024 * 1024)

    # RSS growth should be bounded (< 30MB across 10,000 iterations)
    assert growth_mb < 30.0, f"Memory leak detected: RSS grew by {growth_mb:.2f} MB"


def test_check_memory_threshold_alert():
    """Verify 80% threshold calculation and alert emission (#612)."""
    exceeded_low, rss, limit = check_memory_threshold(0.999)
    exceeded_high, rss, limit = check_memory_threshold(0.0001)
    assert exceeded_high is True


@pytest.mark.asyncio
async def test_dynamic_batcher_stop_cancels_queued_futures():
    """Verify DynamicBatcher.stop() cancels pending submitted requests and clears queue_depth."""
    batcher = DynamicBatcher(max_batch_size=64, max_wait_ms=500.0)

    class DummyConfig:
        version = "v1"
        bias = 0.0
        weights: dict[str, float] = {}

    req1 = asyncio.create_task(batcher.submit("eta", {}, DummyConfig()))
    req2 = asyncio.create_task(batcher.submit("eta", {}, DummyConfig()))
    await asyncio.sleep(0)

    assert batcher.queue_depth == 2

    await batcher.stop()

    assert batcher.queue_depth == 0
    with pytest.raises(asyncio.CancelledError):
        await req1
    with pytest.raises(asyncio.CancelledError):
        await req2


@pytest.mark.asyncio
async def test_shadow_runner_async_create_task_and_metrics():
    """Verify shadow prediction runs via asyncio.create_task without blocking primary and tracks agreement."""
    runner = ShadowRunner(tolerance=0.1)

    async def slow_agreeing_shadow() -> dict[str, float]:
        await asyncio.sleep(0.05)
        return {"ranking_score": 0.82}

    async def diverging_shadow() -> dict[str, float]:
        await asyncio.sleep(0.01)
        return {"ranking_score": 0.10}

    start = time.perf_counter()
    task1 = runner.dispatch("ranking", "v2", slow_agreeing_shadow, {"ranking_score": 0.80})
    elapsed = time.perf_counter() - start
    # Dispatch via create_task must return immediately (< 25ms despite 50ms sleep)
    assert elapsed < 0.025
    assert task1 is not None

    task2 = runner.dispatch("ranking", "v2", diverging_shadow, {"ranking_score": 0.80})
    assert task2 is not None

    await asyncio.gather(task1, task2)
    assert runner.agreement_count == 1
    assert runner.divergence_count == 1
    assert runner.stats["total"] == 2
    assert runner.stats["agreement_rate"] == 0.5


def test_shadow_runner_sync_fallback_and_dropped_async_error_count():
    """Verify sync shadow dispatch offloads to background executor without blocking live path."""
    runner = ShadowRunner(tolerance=0.1)

    def slow_sync_agree() -> dict[str, float]:
        time.sleep(0.05)
        return {"ranking_score": 0.82}

    async def async_dropped() -> dict[str, float]:
        return {"ranking_score": 0.82}

    try:
        start = time.perf_counter()
        assert runner.dispatch("ranking", "v2", slow_sync_agree, {"ranking_score": 0.80}) is None
        assert (time.perf_counter() - start) < 0.025
        runner.wait_pending(timeout=1.0)
        assert runner.agreement_count == 1
        assert runner.error_count == 0

        assert runner.dispatch("ranking", "v2", async_dropped, {"ranking_score": 0.80}) is None
        assert runner.error_count == 1
        assert runner.stats["errors"] == 1
    finally:
        runner.shutdown(wait=True)
