"""Durable checkpointing for the LangGraph orchestrator.

Provides two implementations of a checkpoint saver:

* ``RedisCheckpointSaver`` – production-grade persistence via Redis.
* ``InMemoryCheckpointSaver`` – zero-dependency fallback for local dev
  and when Redis is unavailable.

Both are thread-safe and serialise ``AgentState`` to JSON.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any, Dict, Optional

from app.graph.state import AgentState

logger = logging.getLogger("ai_orchestrator.checkpoints")


# ---------------------------------------------------------------------------
# Abstract interface
# ---------------------------------------------------------------------------

class CheckpointSaver:
    """Base class for checkpoint persistence."""

    async def save(self, thread_id: str, state: AgentState) -> None:
        raise NotImplementedError

    async def load(self, thread_id: str) -> Optional[AgentState]:
        raise NotImplementedError

    async def delete(self, thread_id: str) -> None:
        raise NotImplementedError


# ---------------------------------------------------------------------------
# In-memory (fallback)
# ---------------------------------------------------------------------------

class InMemoryCheckpointSaver(CheckpointSaver):
    """Thread-safe in-memory checkpoint store.

    Useful for local development and as an automatic fallback when Redis
    is not reachable.
    """

    def __init__(self, max_entries: int = 1_000) -> None:
        self._store: Dict[str, str] = {}
        self._lock = threading.Lock()
        self._max_entries = max_entries

    async def save(self, thread_id: str, state: AgentState) -> None:
        serialised = state.model_dump_json()
        with self._lock:
            if len(self._store) >= self._max_entries and thread_id not in self._store:
                oldest = next(iter(self._store))
                del self._store[oldest]
            self._store[thread_id] = serialised
        logger.debug(
            "checkpoint.save.memory",
            extra={"thread_id": thread_id, "size_bytes": len(serialised)},
        )

    async def load(self, thread_id: str) -> Optional[AgentState]:
        with self._lock:
            raw = self._store.get(thread_id)
        if raw is None:
            return None
        try:
            return AgentState.model_validate_json(raw)
        except Exception:
            logger.exception("checkpoint.load.deserialize_error", extra={"thread_id": thread_id})
            return None

    async def delete(self, thread_id: str) -> None:
        with self._lock:
            self._store.pop(thread_id, None)


# ---------------------------------------------------------------------------
# Redis
# ---------------------------------------------------------------------------

class RedisCheckpointSaver(CheckpointSaver):
    """Redis-backed checkpoint saver with automatic in-memory fallback.

    If the Redis connection fails at any point the saver transparently
    degrades to ``InMemoryCheckpointSaver`` and logs a warning.
    """

    _KEY_PREFIX = "instacommerce:agent:checkpoint:"
    _DEFAULT_TTL_S = 3_600  # 1 hour

    def __init__(
        self,
        redis_url: str = "redis://localhost:6379/0",
        ttl_s: int = _DEFAULT_TTL_S,
    ) -> None:
        self._redis_url = redis_url
        self._ttl_s = ttl_s
        self._redis: Any = None
        self._fallback = InMemoryCheckpointSaver()
        self._use_fallback = False

    async def _get_redis(self) -> Any:
        """Lazy-initialise the Redis client."""
        if self._redis is not None:
            return self._redis
        try:
            from redis.asyncio import Redis  # type: ignore[import-untyped]

            self._redis = Redis.from_url(
                self._redis_url,
                decode_responses=True,
                socket_connect_timeout=2.0,
                socket_timeout=2.0,
            )
            await self._redis.ping()
            logger.info("checkpoint.redis.connected", extra={"url": self._redis_url})
            return self._redis
        except Exception:
            logger.warning(
                "checkpoint.redis.unavailable",
                extra={"url": self._redis_url},
            )
            self._use_fallback = True
            return None

    async def save(self, thread_id: str, state: AgentState) -> None:
        if self._use_fallback:
            await self._fallback.save(thread_id, state)
            return
        try:
            redis = await self._get_redis()
            if redis is None:
                await self._fallback.save(thread_id, state)
                return
            key = f"{self._KEY_PREFIX}{thread_id}"
            await redis.setex(key, self._ttl_s, state.model_dump_json())
            logger.debug("checkpoint.save.redis", extra={"thread_id": thread_id})
        except Exception:
            logger.warning("checkpoint.save.redis_error", extra={"thread_id": thread_id})
            self._use_fallback = True
            await self._fallback.save(thread_id, state)

    async def load(self, thread_id: str) -> Optional[AgentState]:
        if self._use_fallback:
            return await self._fallback.load(thread_id)
        try:
            redis = await self._get_redis()
            if redis is None:
                return await self._fallback.load(thread_id)
            key = f"{self._KEY_PREFIX}{thread_id}"
            raw = await redis.get(key)
            if raw is None:
                return None
            return AgentState.model_validate_json(raw)
        except Exception:
            logger.warning("checkpoint.load.redis_error", extra={"thread_id": thread_id})
            self._use_fallback = True
            return await self._fallback.load(thread_id)

    async def delete(self, thread_id: str) -> None:
        if self._use_fallback:
            await self._fallback.delete(thread_id)
            return
        try:
            redis = await self._get_redis()
            if redis is None:
                await self._fallback.delete(thread_id)
                return
            key = f"{self._KEY_PREFIX}{thread_id}"
            await redis.delete(key)
        except Exception:
            logger.warning("checkpoint.delete.redis_error", extra={"thread_id": thread_id})
            self._use_fallback = True
            await self._fallback.delete(thread_id)

    async def close(self) -> None:
        """Clean up the Redis connection."""
        if self._redis is not None:
            try:
                await self._redis.aclose()
            except Exception:
                pass
            self._redis = None
