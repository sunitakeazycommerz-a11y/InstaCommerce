from __future__ import annotations

import time
from typing import Any, Dict

import numpy as np
import pytest

from app.core.config import Settings
from app.core.inference import CACHE_EVENTS
from app.core.shadow import default_shadow_runner
from app.main import _ensure_runtime_state, app
from app.models import ManagedOnnxSession


def _auth_headers() -> Dict[str, str]:
    return {
        "X-Internal-Service": "test-svc",
        "X-Internal-Token": "test-secret-token",
    }


@pytest.mark.asyncio
async def test_rich_models_route_through_registry_cache_health_shadow_and_sla(
    client, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("INTERNAL_SERVICE_TOKEN", "test-secret-token")
    _ensure_runtime_state(app)

    bad_ver = await client.post(
        "/inference/demand?model_version=does-not-exist",
        json={"store_id": "s-1", "product_id": "p-1", "hour": 10, "day_of_week": 2},
        headers=_auth_headers(),
    )
    assert bad_ver.status_code == 404
    assert bad_ver.json()["detail"] == "model_version_not_found"

    demand_payload = {
        "store_id": "s-cache",
        "product_id": "p-cache-1",
        "hour": 14,
        "day_of_week": 3,
        "sales_same_hour_last_week": 12.0,
        "sales_avg_7d": 10.0,
        "sales_avg_30d": 9.0,
    }
    before_hits = CACHE_EVENTS.labels(
        model="demand", version="demand-tft-v1", result="hit"
    )._value.get()
    resp1 = await client.post("/inference/demand", json=demand_payload, headers=_auth_headers())
    resp2 = await client.post("/inference/demand", json=demand_payload, headers=_auth_headers())
    after_hits = CACHE_EVENTS.labels(
        model="demand", version="demand-tft-v1", result="hit"
    )._value.get()
    assert resp1.status_code == 200
    assert resp2.status_code == 200
    assert resp1.json()["model_version"] == "demand-tft-v1"
    assert resp1.json()["predicted_units"] == resp2.json()["predicted_units"]
    assert after_hits == before_hits + 1.0

    ood_resp = await client.post(
        "/inference/demand",
        json={
            "store_id": "s-ood",
            "product_id": "p-ood",
            "hour": 14,
            "day_of_week": 3,
            "sales_same_hour_last_week": 99999.0,
            "sales_avg_7d": 50000.0,
        },
        headers=_auth_headers(),
    )
    assert ood_resp.status_code == 200
    assert ood_resp.json()["drift_detected"] is True

    for endpoint, payload, expected_version in [
        (
            "/inference/personalization",
            {
                "user_id": "u-1",
                "candidate_product_ids": ["sku-1", "sku-2"],
                "user_avg_order_value": 450.0,
            },
            "pers-ncf-v1",
        ),
        (
            "/inference/clv",
            {
                "user_id": "u-1",
                "recency_days": 4,
                "frequency_total": 10,
                "frequency_30d": 3,
                "monetary_avg_cents": 4000,
                "tenure_days": 200,
            },
            "clv-bg-nbd-v1",
        ),
        (
            "/inference/pricing",
            {
                "order_amount_cents": 2500,
                "distance_km": 3.2,
                "item_count": 4,
            },
            "pricing-bandit-v1",
        ),
    ]:
        r = await client.post(endpoint, json=payload, headers=_auth_headers())
        assert r.status_code == 200
        assert r.json()["model_version"] == expected_version

    monkeypatch.setattr(
        "app.core.inference.settings",
        Settings(shadow_models={"demand": "demand-onnx-v1"}, shadow_sample_rate=1.0),
    )
    before_total = default_shadow_runner.stats["total"]
    shadow_resp = await client.post(
        "/inference/demand",
        json={
            "store_id": "s-shadow",
            "product_id": "p-shadow",
            "hour": 11,
            "day_of_week": 1,
            "sales_same_hour_last_week": 11.0,
            "sales_avg_7d": 10.0,
        },
        headers=_auth_headers(),
    )
    assert shadow_resp.status_code == 200
    default_shadow_runner.wait_pending(timeout=1.0)
    assert default_shadow_runner.stats["total"] >= before_total

    orig_predict = app.state.models["demand"].predict
    monkeypatch.setattr(
        "app.core.inference.settings",
        Settings(sla_budgets_ms={"demand": 5.0}, strict_sla_timeout=True),
    )

    def _slow_predict(feats: Any) -> Any:
        time.sleep(0.05)
        return orig_predict(feats)

    monkeypatch.setattr(app.state.models["demand"], "predict", _slow_predict)
    sla_resp = await client.post(
        "/inference/demand",
        json={"store_id": "s-sla", "product_id": "p-sla-timeout", "hour": 9, "day_of_week": 0},
        headers=_auth_headers(),
    )
    assert sla_resp.status_code == 200
    sla_body = sla_resp.json()
    assert sla_body["sla_breached"] is True
    assert sla_body["is_cold_start"] is True


@pytest.mark.asyncio
async def test_eta_fraud_ranking_wire_domain_models_and_onnx_sessions(
    client, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("INTERNAL_SERVICE_TOKEN", "test-secret-token")
    _ensure_runtime_state(app)

    eta_lgbm = await client.post(
        "/inference/eta?model_version=eta-lgbm-v1",
        json={"distance_km": 4.0, "item_count": 6, "traffic_factor": 1.3},
        headers=_auth_headers(),
    )
    assert eta_lgbm.status_code == 200
    assert eta_lgbm.json()["model_version"] == "eta-lgbm-v1"
    assert eta_lgbm.json()["method"] == "rule_based"
    assert eta_lgbm.json()["eta_minutes"] > 0

    eta_onnx_fallback = await client.post(
        "/inference/eta?model_version=eta-onnx-v1",
        json={"distance_km": 4.2, "item_count": 6, "traffic_factor": 1.3},
        headers=_auth_headers(),
    )
    assert eta_onnx_fallback.status_code == 200
    assert eta_onnx_fallback.json()["method"] == "onnx_fallback"

    ranking_resp = await client.post(
        "/inference/ranking?model_version=ranking-lambdamart-v1",
        json={"relevance_score": 0.9, "price_score": 0.6, "availability_score": 1.0, "user_affinity": 0.7},
        headers=_auth_headers(),
    )
    assert ranking_resp.status_code == 200
    assert ranking_resp.json()["model_version"] == "ranking-lambdamart-v1"
    assert 0.0 <= ranking_resp.json()["ranking_score"] <= 1.0

    fraud_resp = await client.post(
        "/inference/fraud?model_version=fraud-xgb-v1",
        json={"order_amount": 120.0, "chargeback_rate": 0.1, "device_risk": 0.2, "account_age_days": 90},
        headers=_auth_headers(),
    )
    assert fraud_resp.status_code == 200
    assert fraud_resp.json()["model_version"] == "fraud-xgb-v1"
    assert 0.0 <= fraud_resp.json()["fraud_probability"] <= 1.0

    class FakeOrtSession:
        def run(self, _output_names: Any, input_feed: Dict[str, np.ndarray]) -> list[np.ndarray]:
            assert "input" in input_feed
            return [np.array([[18.25]], dtype=np.float32)]

    orig_session = app.state.onnx_sessions["eta"]
    app.state.onnx_sessions["eta"] = ManagedOnnxSession(FakeOrtSession())
    try:
        eta_onnx = await client.post(
            "/inference/eta?model_version=eta-onnx-v1",
            json={"distance_km": 5.5, "item_count": 3, "traffic_factor": 1.1},
            headers=_auth_headers(),
        )
        assert eta_onnx.status_code == 200
        assert eta_onnx.json()["method"] == "onnx"
        assert eta_onnx.json()["eta_minutes"] == pytest.approx(18.25)
    finally:
        app.state.onnx_sessions["eta"] = orig_session


@pytest.mark.asyncio
async def test_shadow_stats_and_drift_check_endpoints(
    client, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("INTERNAL_SERVICE_TOKEN", "test-secret-token")
    _ensure_runtime_state(app)

    unauth = await client.get("/shadow/stats")
    assert unauth.status_code == 401

    shadow_resp = await client.get("/shadow/stats", headers=_auth_headers())
    assert shadow_resp.status_code == 200
    shadow_body = shadow_resp.json()
    assert "stats" in shadow_body
    assert "recent_comparisons" in shadow_body
    assert "configured_shadow_models" in shadow_body

    healthy_drift = await client.post(
        "/drift/check",
        json={
            "model_name": "eta",
            "features": {"distance_km": 3.5, "item_count": 5.0, "traffic_factor": 1.2},
        },
        headers=_auth_headers(),
    )
    assert healthy_drift.status_code == 200
    assert healthy_drift.json()["drift_detected"] is False

    bad_drift = await client.post(
        "/drift/check",
        json={
            "model_name": "pricing",
            "entity_id": "user-1",
            "features": {
                "order_amount_cents": 2000.0,
                "distance_km": 190.0,
                "active_riders_in_zone": 10.0,
                "pending_orders_in_zone": 6.0,
                "surge_multiplier": 1.1,
            },
            "offline_features": {"user-1": {"order_amount_cents": 1000.0, "distance_km": 3.0}},
            "max_drift_pct": 1.0,
        },
        headers=_auth_headers(),
    )
    assert bad_drift.status_code == 200
    bad_body = bad_drift.json()
    assert bad_body["drift_detected"] is True
    assert "distance_km" in bad_body["ood_features"]
    assert len(bad_body["parity_reports"]) == 1
    assert bad_body["parity_reports"][0]["passed"] is False
