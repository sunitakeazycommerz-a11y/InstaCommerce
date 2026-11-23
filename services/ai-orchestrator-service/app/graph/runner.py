from __future__ import annotations

from collections import OrderedDict
import logging
from typing import Any, Dict, Optional

from app.config import Settings
from app.graph.budgets import (
    BudgetExceededError,
    BudgetTracker,
    RedisSessionBudgetStore,
    budget_tracker_from_state,
)
from app.graph.checkpoints import CheckpointSaver, run_agent_graph
from app.graph.state import AgentState

logger = logging.getLogger("ai_orchestrator.runner")

_DEFAULT_SETTINGS = Settings()


class AgentExecutionRunner:
    """Runs graph executions while enforcing per-request and per-session ``BudgetTracker`` limits."""

    def __init__(
        self,
        max_session_cost_usd: float = 0.50,
        max_session_tokens: int = 0,
        max_session_tool_calls: int = 25,
        max_sessions: int = 10_000,
        settings: Optional[Settings] = None,
        budget_store: Optional[RedisSessionBudgetStore] = None,
    ) -> None:
        self.max_session_cost_usd = max_session_cost_usd
        self.max_session_tokens = max_session_tokens
        self.max_session_tool_calls = max_session_tool_calls
        self.max_sessions = max(1, max_sessions)
        self._settings = settings or _DEFAULT_SETTINGS
        self._session_trackers: OrderedDict[str, BudgetTracker] = OrderedDict()
        if budget_store is not None:
            self._budget_store: Optional[RedisSessionBudgetStore] = budget_store
        elif self._settings.redis_session_budgets_enabled and self._settings.redis_url:
            self._budget_store = RedisSessionBudgetStore(
                redis_url=self._settings.redis_url,
                ttl_s=self._settings.session_budget_ttl_seconds,
            )
        else:
            self._budget_store = None

    def get_session_tracker(self, session_id: Optional[str]) -> Optional[BudgetTracker]:
        if not session_id:
            return None
        tracker = self._session_trackers.get(session_id)
        if tracker is not None:
            self._session_trackers.move_to_end(session_id)
            return tracker
        tracker = BudgetTracker(
            max_cost_usd=self.max_session_cost_usd,
            max_tokens=self.max_session_tokens,
            max_tool_calls=self.max_session_tool_calls,
            max_latency_ms=60_000.0,
        )
        self._session_trackers[session_id] = tracker
        while len(self._session_trackers) > self.max_sessions:
            self._session_trackers.popitem(last=False)
        return tracker

    def build_request_tracker(self, state: AgentState) -> BudgetTracker:
        return budget_tracker_from_state(state, settings=self._settings)

    async def close(self) -> None:
        if self._budget_store is not None:
            await self._budget_store.close()

    async def run(
        self,
        graph: Any,
        state: AgentState,
        config: Optional[Dict[str, Any]] = None,
        checkpointer: Optional[CheckpointSaver] = None,
    ) -> AgentState:
        session_id = state.session_id or state.user_id
        session_tracker = self.get_session_tracker(session_id)
        request_tracker = self.build_request_tracker(state)

        if session_tracker is not None and session_id and self._budget_store is not None:
            remote = await self._budget_store.load_usage(session_id)
            if remote is not None:
                session_tracker.total_cost_usd = max(
                    session_tracker.total_cost_usd,
                    float(remote["total_cost_usd"]),
                )
                session_tracker.total_tokens = max(
                    session_tracker.total_tokens,
                    int(remote["total_tokens"]),
                )
                session_tracker.tool_calls_used = max(
                    session_tracker.tool_calls_used,
                    int(remote["tool_calls_used"]),
                )

        try:
            if session_tracker is not None:
                session_tracker.reset_for_turn()
                session_tracker.check_or_raise()
            request_tracker.check_or_raise()
        except BudgetExceededError as exc:
            logger.warning(
                "runner.budget_exceeded",
                extra={"request_id": state.request_id, "reason": str(exc)},
            )
            return state.model_copy(
                update={
                    "needs_escalation": True,
                    "escalation_reason": str(exc),
                    "escalation_category": "budget_exceeded",
                    "response": (
                        "This request exceeds the allowed processing budget. "
                        f"Your reference number is {state.request_id}."
                    ),
                }
            )

        effective_state = state
        if session_tracker is not None:
            snap = session_tracker.snapshot()
            updates: Dict[str, Any] = {
                "max_cost_usd": min(state.max_cost_usd, float(snap["cost_remaining"])),
                "tool_calls_remaining": min(
                    state.tool_calls_remaining, int(snap["tool_calls_remaining"])
                ),
            }
            if session_tracker.max_tokens > 0:
                remaining_session_tokens = max(0, session_tracker.max_tokens - int(snap["total_tokens"]))
                updates["max_tokens"] = (
                    min(state.max_tokens, remaining_session_tokens)
                    if state.max_tokens > 0
                    else remaining_session_tokens
                )
            effective_state = effective_state.model_copy(update=updates)

        result_dict = await run_agent_graph(
            graph,
            effective_state.model_dump(),
            checkpointer=checkpointer,
            config=config,
        )

        result = AgentState(**result_dict) if isinstance(result_dict, dict) else result_dict

        if session_tracker is not None:
            cost_delta = max(0.0, result.total_cost_usd - state.total_cost_usd)
            token_delta = max(0, result.total_tokens_used - state.total_tokens_used)
            tool_calls_delta = max(0, len(result.tool_results) - len(state.tool_results))
            session_tracker.record_usage_delta(
                cost_delta=cost_delta,
                token_delta=token_delta,
                tool_calls_delta=tool_calls_delta,
            )
            if session_id and self._budget_store is not None:
                remote_after = await self._budget_store.record_usage_delta(
                    session_id,
                    cost_delta=cost_delta,
                    token_delta=token_delta,
                    tool_calls_delta=tool_calls_delta,
                )
                if remote_after is not None:
                    session_tracker.total_cost_usd = max(
                        session_tracker.total_cost_usd,
                        float(remote_after["total_cost_usd"]),
                    )
                    session_tracker.total_tokens = max(
                        session_tracker.total_tokens,
                        int(remote_after["total_tokens"]),
                    )
                    session_tracker.tool_calls_used = max(
                        session_tracker.tool_calls_used,
                        int(remote_after["tool_calls_used"]),
                    )

        return result
