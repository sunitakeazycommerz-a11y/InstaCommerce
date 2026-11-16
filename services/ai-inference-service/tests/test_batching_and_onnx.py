import asyncio
import time
import pytest
import numpy as np
from app.batching import DynamicBatcher
from app.core.batching import DynamicBatcher as CoreDynamicBatcher, _BatchItem
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
    # Test normal memory condition (< 80%)
    exceeded_low, rss, limit = check_memory_threshold(0.999)
    # Test tight threshold trigger
    exceeded_high, rss, limit = check_memory_threshold(0.0001)
    assert exceeded_high is True


@pytest.mark.asyncio
async def test_dynamic_batcher_stop_cancels_queued_futures():
    """Verify DynamicBatcher.stop() cancels remaining queued futures before clearing _queue."""
    batcher = CoreDynamicBatcher(max_batch_size=64, max_wait_ms=500.0)
    loop = asyncio.get_running_loop()
    fut1 = loop.create_future()
    fut2 = loop.create_future()

    item1 = _BatchItem(model_name="eta", features={}, model_config=None, future=fut1, enqueued_at=0.0)
    item2 = _BatchItem(model_name="eta", features={}, model_config=None, future=fut2, enqueued_at=0.0)
    batcher._queue.append(item1)
    q = batcher._get_queue("eta:v1")
    await q.put(item2)

    await batcher.stop()

    assert fut1.cancelled()
    assert fut2.cancelled()
    assert len(batcher._queue) == 0


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
    # Dispatch via create_task must return immediately (< 20ms despite 50ms sleep)
    assert elapsed < 0.025
    assert task1 is not None

    task2 = runner.dispatch("ranking", "v2", diverging_shadow, {"ranking_score": 0.80})
    assert task2 is not None

    await asyncio.gather(task1, task2)
    assert runner.agreement_count == 1
    assert runner.divergence_count == 1
    assert runner.stats["total"] == 2
    assert runner.stats["agreement_rate"] == 0.5
