"""Tests for internal service authentication middleware."""

import pytest
from httpx import ASGITransport, AsyncClient
from unittest.mock import patch


@pytest.fixture
def auth_app():
    with patch.dict("os.environ", {"INTERNAL_SERVICE_TOKEN": "test-secret-token"}):
        import importlib
        import app.main as main_module
        importlib.reload(main_module)
        yield main_module.app


@pytest.mark.asyncio
async def test_health_bypasses_auth(auth_app):
    transport = ASGITransport(app=auth_app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/health")
        assert resp.status_code == 200


@pytest.mark.asyncio
async def test_inference_rejects_without_headers(auth_app):
    transport = ASGITransport(app=auth_app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post("/inference/eta", json={
            "order_id": "test", "store_lat": 0, "store_lon": 0,
            "customer_lat": 0, "customer_lon": 0,
        })
        assert resp.status_code == 401
        assert resp.json()["error"] == "UNAUTHORIZED"


@pytest.mark.asyncio
async def test_inference_rejects_bad_token(auth_app):
    transport = ASGITransport(app=auth_app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post("/inference/eta", json={
            "order_id": "test", "store_lat": 0, "store_lon": 0,
            "customer_lat": 0, "customer_lon": 0,
        }, headers={
            "X-Internal-Service": "test-svc",
            "X-Internal-Token": "wrong-token",
        })
        assert resp.status_code == 403
        assert resp.json()["error"] == "FORBIDDEN"


@pytest.mark.asyncio
async def test_inference_accepts_valid_token(auth_app):
    transport = ASGITransport(app=auth_app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post("/inference/eta", json={
            "order_id": "test", "store_lat": 0, "store_lon": 0,
            "customer_lat": 0, "customer_lon": 0,
        }, headers={
            "X-Internal-Service": "test-svc",
            "X-Internal-Token": "test-secret-token",
        })
        assert resp.status_code not in (401, 403)
