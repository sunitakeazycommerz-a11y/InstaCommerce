"""Rate limiting for AI agent interactions.

Implements a thread-safe token-bucket algorithm with per-user buckets.
Stale buckets are periodically pruned to bound memory usage.
"""

import logging
import os
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Tuple

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configurable defaults (via environment variables)
# ---------------------------------------------------------------------------
_DEFAULT_RATE_PER_MINUTE: int = int(
    os.environ.get("RATE_LIMIT_PER_MINUTE", "10")
)
_DEFAULT_BURST: int = int(os.environ.get("RATE_LIMIT_BURST", "15"))
_PRUNE_INTERVAL_SECONDS: float = float(
    os.environ.get("RATE_LIMIT_PRUNE_INTERVAL", "300")
)
_BUCKET_STALE_SECONDS: float = float(
    os.environ.get("RATE_LIMIT_STALE_SECONDS", "3600")
)


@dataclass
class _Bucket:
    """Internal token-bucket state for a single user."""

    tokens: float
    max_tokens: float
    refill_rate: float  # tokens per second
    last_refill: float = field(default_factory=time.monotonic)

    def refill(self) -> None:
        """Add tokens accrued since last refill."""
        now = time.monotonic()
        elapsed = now - self.last_refill
        self.tokens = min(self.max_tokens, self.tokens + elapsed * self.refill_rate)
        self.last_refill = now

    def consume(self) -> bool:
        """Try to consume one token. Returns ``True`` on success."""
        self.refill()
        if self.tokens >= 1.0:
            self.tokens -= 1.0
            return True
        return False

    @property
    def retry_after_seconds(self) -> float:
        """Seconds until at least one token becomes available."""
        if self.tokens >= 1.0:
            return 0.0
        deficit = 1.0 - self.tokens
        return deficit / self.refill_rate if self.refill_rate > 0 else 0.0


class TokenBucketRateLimiter:
    """Per-user token bucket rate limiter.

    Default: 10 requests/minute, burst of 15 tokens.

    Usage::

        limiter = TokenBucketRateLimiter()
        allowed, meta = limiter.allow("user-123")
        if not allowed:
            retry_after = meta["retry_after_seconds"]
            # respond with 429

    Parameters
    ----------
    rate_per_minute:
        Sustained token refill rate expressed as requests per minute.
    burst:
        Maximum token bucket size (allows short bursts above sustained rate).
    """

    def __init__(
        self,
        rate_per_minute: int = _DEFAULT_RATE_PER_MINUTE,
        burst: int = _DEFAULT_BURST,
    ) -> None:
        if rate_per_minute <= 0:
            raise ValueError("rate_per_minute must be > 0")
        if burst <= 0:
            raise ValueError("burst must be > 0")

        self._rate_per_second: float = rate_per_minute / 60.0
        self._burst: int = burst
        self._buckets: Dict[str, _Bucket] = {}
        self._lock = threading.Lock()
        self._last_prune: float = time.monotonic()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def allow(self, user_id: str) -> Tuple[bool, Dict[str, Any]]:
        """Check if a request from *user_id* is allowed.

        Returns
        -------
        allowed : bool
            ``True`` when the request is within rate limits.
        metadata : dict
            Contains ``remaining_tokens``, ``retry_after_seconds``, and
            ``limit`` for inclusion in response headers.
        """
        with self._lock:
            self._maybe_prune()
            bucket = self._get_or_create(user_id)
            allowed = bucket.consume()

        meta: Dict[str, Any] = {
            "remaining_tokens": max(0.0, bucket.tokens),
            "retry_after_seconds": 0.0 if allowed else bucket.retry_after_seconds,
            "limit": self._burst,
        }

        if not allowed:
            logger.warning(
                "Rate limit exceeded",
                extra={"user_id": user_id, **meta},
            )

        return allowed, meta

    def reset(self, user_id: str) -> None:
        """Reset the bucket for *user_id* (e.g. after manual override)."""
        with self._lock:
            self._buckets.pop(user_id, None)

    def reset_all(self) -> None:
        """Remove all buckets."""
        with self._lock:
            self._buckets.clear()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _get_or_create(self, user_id: str) -> _Bucket:
        """Return the bucket for *user_id*, creating one if needed.

        Must be called while holding ``self._lock``.
        """
        bucket = self._buckets.get(user_id)
        if bucket is None:
            bucket = _Bucket(
                tokens=float(self._burst),
                max_tokens=float(self._burst),
                refill_rate=self._rate_per_second,
            )
            self._buckets[user_id] = bucket
        return bucket

    def _maybe_prune(self) -> None:
        """Remove stale buckets to bound memory.

        Must be called while holding ``self._lock``.
        """
        now = time.monotonic()
        if now - self._last_prune < _PRUNE_INTERVAL_SECONDS:
            return
        self._last_prune = now
        stale_keys = [
            uid
            for uid, b in self._buckets.items()
            if now - b.last_refill > _BUCKET_STALE_SECONDS
        ]
        for uid in stale_keys:
            del self._buckets[uid]
        if stale_keys:
            logger.debug("Pruned %d stale rate-limit buckets", len(stale_keys))
