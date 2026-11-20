"""Budget enforcement utilities for the LangGraph orchestrator.

Provides strict per-request guardrails:
* **Token budget** – tracks cumulative token usage.
* **Cost calculator** – per-model USD pricing.
* **Latency budget** – elapsed-time ceiling with early termination.
* **Tool-call counter** – hard cap (default 10).

All checks are *fail-closed*: if data is missing the budget is treated
as exhausted.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Dict, Optional

if TYPE_CHECKING:
    from app.config import Settings
    from app.graph.state import AgentState

logger = logging.getLogger("ai_orchestrator.budgets")


# ---------------------------------------------------------------------------
# Per-model token pricing (USD per 1 000 tokens)
# ---------------------------------------------------------------------------

_MODEL_PRICING: Dict[str, Dict[str, float]] = {
    "gpt-4o": {"input": 0.005, "output": 0.015},
    "gpt-4o-mini": {"input": 0.00015, "output": 0.0006},
    "gpt-4-turbo": {"input": 0.01, "output": 0.03},
    "gpt-3.5-turbo": {"input": 0.0005, "output": 0.0015},
}

DEFAULT_PRICING = {"input": 0.01, "output": 0.03}  # conservative fallback


def token_cost(model: str, input_tokens: int, output_tokens: int) -> float:
    """Return estimated cost in USD for a single LLM call.

    Uses known per-model pricing; falls back to a conservative default so
    the budget is never under-counted.
    """
    pricing = _MODEL_PRICING.get(model, DEFAULT_PRICING)
    return (
        (input_tokens / 1_000) * pricing["input"]
        + (output_tokens / 1_000) * pricing["output"]
    )


# ---------------------------------------------------------------------------
# Budget tracker
# ---------------------------------------------------------------------------

@dataclass
class BudgetTracker:
    """Mutable accumulator carried through a single request.

    Attributes:
        max_cost_usd:   Hard ceiling on spend.  Default $0.50.
        max_tokens:     Optional token ceiling (0 = unlimited).
        max_tool_calls: Hard cap on tool invocations.
        max_latency_ms: Wall-clock ceiling.
    """

    max_cost_usd: float = 0.50
    max_tokens: int = 0
    max_tool_calls: int = 10
    max_latency_ms: float = 10_000.0

    # accumulators
    total_cost_usd: float = 0.0
    total_tokens: int = 0
    tool_calls_used: int = 0
    _start_ns: int = field(default_factory=time.monotonic_ns)

    # ----- queries ----------------------------------------------------------

    @property
    def elapsed_ms(self) -> float:
        return (time.monotonic_ns() - self._start_ns) / 1_000_000

    @property
    def cost_remaining(self) -> float:
        return max(0.0, self.max_cost_usd - self.total_cost_usd)

    @property
    def tool_calls_remaining(self) -> int:
        return max(0, self.max_tool_calls - self.tool_calls_used)

    # ----- gates ------------------------------------------------------------

    def is_cost_exceeded(self) -> bool:
        return self.total_cost_usd >= self.max_cost_usd

    def is_latency_exceeded(self) -> bool:
        return self.elapsed_ms >= self.max_latency_ms

    def is_tool_calls_exceeded(self) -> bool:
        return self.tool_calls_used >= self.max_tool_calls

    def is_tokens_exceeded(self) -> bool:
        if self.max_tokens <= 0:
            return False
        return self.total_tokens >= self.max_tokens

    def can_proceed(self) -> bool:
        """Return ``True`` only if **all** budgets have headroom."""
        return not (
            self.is_cost_exceeded()
            or self.is_latency_exceeded()
            or self.is_tool_calls_exceeded()
            or self.is_tokens_exceeded()
        )

    def check_or_raise(self) -> None:
        """Raise ``BudgetExceededError`` if any ceiling is breached."""
        if self.is_cost_exceeded():
            raise BudgetExceededError(
                f"Cost budget exceeded: ${self.total_cost_usd:.4f} >= ${self.max_cost_usd:.2f}"
            )
        if self.is_latency_exceeded():
            raise BudgetExceededError(
                f"Latency budget exceeded: {self.elapsed_ms:.0f}ms >= {self.max_latency_ms:.0f}ms"
            )
        if self.is_tool_calls_exceeded():
            raise BudgetExceededError(
                f"Tool-call budget exceeded: {self.tool_calls_used} >= {self.max_tool_calls}"
            )
        if self.is_tokens_exceeded():
            raise BudgetExceededError(
                f"Token budget exceeded: {self.total_tokens} >= {self.max_tokens}"
            )

    # ----- mutators ---------------------------------------------------------

    def record_llm_call(
        self,
        model: str,
        input_tokens: int,
        output_tokens: int,
    ) -> float:
        """Record an LLM call.  Returns incremental cost in USD."""
        cost = token_cost(model, input_tokens, output_tokens)
        self.total_cost_usd += cost
        self.total_tokens += input_tokens + output_tokens
        logger.debug(
            "budget.llm_call",
            extra={
                "model": model,
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "cost_usd": round(cost, 6),
                "total_cost_usd": round(self.total_cost_usd, 6),
            },
        )
        return cost

    def record_tool_call(self) -> None:
        """Increment tool-call counter."""
        self.tool_calls_used += 1
        logger.debug(
            "budget.tool_call",
            extra={
                "tool_calls_used": self.tool_calls_used,
                "tool_calls_remaining": self.tool_calls_remaining,
            },
        )

    def reset_for_turn(self, *, elapsed_ms: float = 0.0) -> None:
        self._start_ns = time.monotonic_ns() - int(max(0.0, elapsed_ms) * 1_000_000)

    def record_usage_delta(
        self,
        *,
        cost_delta: float = 0.0,
        token_delta: int = 0,
        tool_calls_delta: int = 0,
    ) -> None:
        self.total_cost_usd += max(0.0, cost_delta)
        self.total_tokens += max(0, token_delta)
        self.tool_calls_used += max(0, tool_calls_delta)

    def snapshot(self) -> Dict[str, Any]:
        return {
            "total_cost_usd": self.total_cost_usd,
            "max_cost_usd": self.max_cost_usd,
            "cost_remaining": self.cost_remaining,
            "total_tokens": self.total_tokens,
            "max_tokens": self.max_tokens,
            "tool_calls_used": self.tool_calls_used,
            "tool_calls_remaining": self.tool_calls_remaining,
            "max_tool_calls": self.max_tool_calls,
            "elapsed_ms": self.elapsed_ms,
            "max_latency_ms": self.max_latency_ms,
        }


def budget_tracker_from_state(
    state: "AgentState",
    settings: Optional["Settings"] = None,
) -> BudgetTracker:
    """Construct a synchronized ``BudgetTracker`` from ``AgentState``."""
    elapsed_ms = state.current_elapsed_ms()
    configured_tool_cap = max(
        settings.tool_call_max if settings is not None else 10,
        len(state.tool_results) + max(state.tool_calls_remaining, 0),
    )
    used_tool_calls = max(0, configured_tool_cap - max(state.tool_calls_remaining, 0))
    tracker = BudgetTracker(
        max_cost_usd=state.max_cost_usd,
        max_tokens=state.max_tokens,
        max_tool_calls=configured_tool_cap,
        max_latency_ms=state.max_latency_ms,
        total_cost_usd=state.total_cost_usd,
        total_tokens=state.total_tokens_used,
        tool_calls_used=used_tool_calls,
    )
    tracker.reset_for_turn(elapsed_ms=elapsed_ms)
    return tracker


def apply_budget_to_state(state: "AgentState", tracker: BudgetTracker) -> "AgentState":
    """Sync tracked budget consumption back onto ``AgentState``."""
    snap = tracker.snapshot()
    return state.model_copy(
        update={
            "total_cost_usd": snap["total_cost_usd"],
            "total_tokens_used": snap["total_tokens"],
            "tool_calls_remaining": snap["tool_calls_remaining"],
            "elapsed_ms": max(state.elapsed_ms, snap["elapsed_ms"]),
        }
    )


# ---------------------------------------------------------------------------
# Exception
# ---------------------------------------------------------------------------

class BudgetExceededError(RuntimeError):
    """Raised when any per-request budget ceiling is breached."""
