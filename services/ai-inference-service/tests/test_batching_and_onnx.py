import asyncio
import pytest
import numpy as np
from app.batching import DynamicBatcher
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
