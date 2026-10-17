import os

os.environ.setdefault("AI_INFERENCE_LOG_LEVEL", "WARNING")
os.environ.setdefault("AI_INFERENCE_CACHE_ENABLED", "true")

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.main import app


@pytest_asyncio.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
