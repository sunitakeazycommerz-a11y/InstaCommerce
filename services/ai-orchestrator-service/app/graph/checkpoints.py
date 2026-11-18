"""Durable checkpointing for the LangGraph orchestrator.

Provides two implementations of a checkpoint saver:

* ``RedisCheckpointSaver`` – production-grade persistence via Redis.
* ``InMemoryCheckpointSaver`` – zero-dependency fallback for local dev
  and when Redis is unavailable.

Both are thread-safe and serialise ``AgentState`` to JSON.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Any, Dict, Optional

from app.graph.state import AgentState

try:
    import redis.asyncio as aioredis  # type: ignore[import-untyped]
except ImportError:
    aioredis = None  # type: ignore[assignment]

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
        retry_cooldown_s: float = 15.0,
        max_cooldown_s: float = 300.0,
    ) -> None:
        self._redis_url = redis_url
        self._ttl_s = ttl_s
        self._retry_cooldown_s = max(0.0, retry_cooldown_s)
        self._max_cooldown_s = max(self._retry_cooldown_s, max_cooldown_s)
        self._redis: Any = None
        self._fallback = InMemoryCheckpointSaver()
        self._use_fallback = False
        self._init_failed = False
        self._consecutive_failures = 0
        self._next_retry_at = 0.0

    def _mark_redis_failure(self) -> None:
        self._redis = None
        self._init_failed = True
        self._use_fallback = True
        backoff = min(
            self._retry_cooldown_s * (2 ** self._consecutive_failures),
            self._max_cooldown_s,
        )
        self._consecutive_failures += 1
        self._next_retry_at = time.monotonic() + backoff

    async def _get_redis(self) -> Any:
        """Lazy-initialise Redis with exponential-backoff cooldown retry on transient failure."""
        if self._redis is not None and not self._init_failed and not self._use_fallback:
            return self._redis

        if self._init_failed or self._use_fallback:
            if time.monotonic() < self._next_retry_at:
                return None

        if aioredis is None:
            self._mark_redis_failure()
            return None

        try:
            client = aioredis.Redis.from_url(
                self._redis_url,
                decode_responses=True,
                socket_connect_timeout=2.0,
                socket_timeout=2.0,
            )
            await client.ping()
            self._redis = client
            self._init_failed = False
            self._use_fallback = False
            self._consecutive_failures = 0
            self._next_retry_at = 0.0
            logger.info("checkpoint.redis.connected", extra={"url": self._redis_url})
            return self._redis
        except Exception:
            logger.warning(
                "checkpoint.redis.unavailable",
                extra={"url": self._redis_url, "failures": self._consecutive_failures + 1},
            )
            self._mark_redis_failure()
            return None

    async def save(self, thread_id: str, state: AgentState) -> None:
        redis = await self._get_redis()
        if redis is None:
            await self._fallback.save(thread_id, state)
            return
        try:
            key = f"{self._KEY_PREFIX}{thread_id}"
            await redis.setex(key, self._ttl_s, state.model_dump_json())
            logger.debug("checkpoint.save.redis", extra={"thread_id": thread_id})
        except Exception:
            logger.warning("checkpoint.save.redis_error", extra={"thread_id": thread_id})
            self._mark_redis_failure()
            await self._fallback.save(thread_id, state)

    async def load(self, thread_id: str) -> Optional[AgentState]:
        redis = await self._get_redis()
        if redis is None:
            return await self._fallback.load(thread_id)
        try:
            key = f"{self._KEY_PREFIX}{thread_id}"
            raw = await redis.get(key)
            if raw is None:
                return await self._fallback.load(thread_id)
            return AgentState.model_validate_json(raw)
        except Exception:
            logger.warning("checkpoint.load.redis_error", extra={"thread_id": thread_id})
            self._mark_redis_failure()
            return await self._fallback.load(thread_id)

    async def delete(self, thread_id: str) -> None:
        redis = await self._get_redis()
        if redis is None:
            await self._fallback.delete(thread_id)
            return
        try:
            key = f"{self._KEY_PREFIX}{thread_id}"
            await redis.delete(key)
        except Exception:
            logger.warning("checkpoint.delete.redis_error", extra={"thread_id": thread_id})
            self._mark_redis_failure()
            await self._fallback.delete(thread_id)

    @property
    def is_fallback_active(self) -> bool:
        """Whether the saver is currently degraded to in-memory fallback."""
        return self._use_fallback

    async def close(self) -> None:
        """Clean up the Redis connection."""
        if self._redis is not None:
            try:
                await self._redis.aclose()
            except Exception:
                logger.warning("checkpoint.redis.close_failed", exc_info=True)
            self._redis = None


async def run_agent_graph(
    graph: Any,
    input_state: Dict[str, Any],
    *,
    checkpointer: Optional[CheckpointSaver] = None,
    config: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Invoke a compiled LangGraph with optional checkpoint load/save."""
    thread_id = (
        ((config or {}).get("configurable") or {}).get("thread_id")
        or input_state.get("session_id")
        or input_state.get("request_id")
    )
    state_to_run = dict(input_state)
    if checkpointer is not None and thread_id:
        try:
            prior = await checkpointer.load(str(thread_id))
            if prior is not None and not state_to_run.get("conversation_history"):
                state_to_run["conversation_history"] = list(prior.conversation_history)
        except Exception:
            logger.warning("graph.checkpoint_load_failed", extra={"thread_id": str(thread_id)})

    if config is not None:
        result = await graph.ainvoke(state_to_run, config=config)
    else:
        result = await graph.ainvoke(state_to_run)

    if checkpointer is not None and thread_id and isinstance(result, dict):
        try:
            await checkpointer.save(str(thread_id), AgentState(**result))
        except Exception:
            logger.warning("graph.checkpoint_save_failed", extra={"thread_id": str(thread_id)})
    return result
