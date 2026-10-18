import os

os.environ.setdefault("AI_ORCHESTRATOR_LOG_LEVEL", "WARNING")
os.environ.setdefault("AI_ORCHESTRATOR_OTEL_ENABLED", "false")
os.environ.setdefault("AI_ORCHESTRATOR_AUDIT_ENABLED", "false")

from typing import Any, cast
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.main import app


@pytest_asyncio.fixture
async def client():
    transport = ASGITransport(app=cast(Any, app))
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
