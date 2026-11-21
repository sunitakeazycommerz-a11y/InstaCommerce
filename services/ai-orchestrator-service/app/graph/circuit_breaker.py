"""Circuit breaker and descriptor primitives for orchestrator tools."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from enum import Enum

logger = logging.getLogger("ai_orchestrator.tools")


class CircuitState(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass
class CircuitBreaker:
    """Simple three-state circuit breaker."""

    failure_threshold: int = 3
    reset_timeout_s: float = 30.0

    _state: CircuitState = CircuitState.CLOSED
    _failure_count: int = 0
    _last_failure_time: float = 0.0

    @property
    def state(self) -> CircuitState:
        if self._state == CircuitState.OPEN:
            if (time.monotonic() - self._last_failure_time) >= self.reset_timeout_s:
                self._state = CircuitState.HALF_OPEN
        return self._state

    def record_success(self) -> None:
        self._failure_count = 0
        self._state = CircuitState.CLOSED

    def record_failure(self) -> None:
        self._failure_count += 1
        self._last_failure_time = time.monotonic()
        if self._failure_count >= self.failure_threshold:
            self._state = CircuitState.OPEN
            logger.warning(
                "circuit_breaker.opened",
                extra={"failure_count": self._failure_count},
            )

    def allow_request(self) -> bool:
        current = self.state
        if current == CircuitState.CLOSED:
            return True
        if current == CircuitState.HALF_OPEN:
            return True
        return False


@dataclass
class ToolDescriptor:
    """Metadata and circuit breaker for a single registered tool."""

    name: str
    description: str
    base_url: str
    path: str
    method: str = "GET"
    timeout_s: float = 2.5
    max_retries: int = 2
    is_write: bool = False
    circuit_breaker: CircuitBreaker = field(default_factory=CircuitBreaker)
