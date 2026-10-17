import os

os.environ.setdefault("AI_ORCHESTRATOR_LOG_LEVEL", "WARNING")
os.environ.setdefault("AI_ORCHESTRATOR_OTEL_ENABLED", "false")
os.environ.setdefault("AI_ORCHESTRATOR_AUDIT_ENABLED", "false")

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.main import app


@pytest_asyncio.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
