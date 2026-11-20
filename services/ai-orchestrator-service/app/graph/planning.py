from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Dict, List, Optional

from app.graph.budgets import BudgetExceededError, budget_tracker_from_state
from app.graph.state import AgentState, IntentType, ToolResult
from app.graph.substitution import plan_candidate_inventory_check
from app.graph.tools import ToolRegistry

logger = logging.getLogger("ai_orchestrator.planning")


def plan_tools(state: AgentState) -> List[Dict[str, Any]]:
    """Return an ordered list of tool calls for the detected intent."""
    plan: List[Dict[str, Any]] = []

    if state.intent == IntentType.SEARCH:
        plan.append({"tool": "catalog.search", "params": {"q": state.query}})
    elif state.intent == IntentType.SUBSTITUTE:
        plan.append({"tool": "catalog.search", "params": {"q": state.query}})
        store_id = state.context.get("store_id")
        items = state.context.get("items")
        if store_id and items:
            plan.append(
                {
                    "tool": "inventory.check",
                    "body": {
                        "storeId": store_id,
                        "items": items,
                    },
                }
            )
    elif state.intent == IntentType.ORDER_STATUS:
        order_id = state.context.get("order_id", "")
        if order_id:
            plan.append({"tool": "order.get", "path_params": {"order_id": order_id}})
    elif state.intent == IntentType.RECOMMEND:
        plan.append({"tool": "catalog.list_products", "params": {"sort": "popularity"}})
    elif state.intent == IntentType.SUPPORT:
        order_id = state.context.get("order_id", "")
        if order_id:
            plan.append({"tool": "order.get", "path_params": {"order_id": order_id}})

    return plan


async def _call_tool_within_deadline(
    registry: ToolRegistry,
    plan: Dict[str, Any],
    deadline_mono: float,
    total_timeout: float,
) -> ToolResult:
    remaining = deadline_mono - time.monotonic()
    if remaining <= 0:
        return ToolResult(
            tool_name=str(plan["tool"]),
            success=False,
            error=f"Aggregate tool timeout exceeded ({total_timeout:.2f}s)",
        )
    try:
        return await asyncio.wait_for(
            registry.call(
                plan["tool"],
                params=plan.get("params"),
                body=plan.get("body"),
                path_params=plan.get("path_params"),
            ),
            timeout=remaining,
        )
    except asyncio.TimeoutError:
        return ToolResult(
            tool_name=str(plan["tool"]),
            success=False,
            error=f"Aggregate tool timeout exceeded ({total_timeout:.2f}s)",
            latency_ms=remaining * 1000.0,
        )


async def execute_tools(
    state: AgentState,
    registry: Optional[ToolRegistry] = None,
) -> AgentState:
    """Execute planned tools with ``BudgetTracker`` enforcement."""
    try:
        tracker = budget_tracker_from_state(state, settings=registry.settings if registry else None)

        if not tracker.can_proceed():
            try:
                tracker.check_or_raise()
            except BudgetExceededError as exc:
                logger.warning(
                    "node.execute_tools.budget_exceeded",
                    extra={"request_id": state.request_id, "reason": str(exc)},
                )
            return state.model_copy(
                update={
                    "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        tool_plan = plan_tools(state)
        results: List[ToolResult] = list(state.tool_results)

        if not state.execute_tools:
            logger.info("node.execute_tools.skipped", extra={"request_id": state.request_id})
            return state.model_copy(
                update={
                    "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        if registry is None:
            logger.info("node.execute_tools.no_registry", extra={"request_id": state.request_id})
            return state.model_copy(
                update={
                    "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        total_timeout = float(registry.settings.tool_total_timeout_seconds)
        deadline_mono = time.monotonic() + max(0.001, total_timeout)
        timeout_errors: List[str] = []

        for plan in tool_plan:
            if not tracker.can_proceed():
                break

            result = await _call_tool_within_deadline(registry, plan, deadline_mono, total_timeout)
            results.append(result)
            tracker.record_tool_call()
            if result.error and "Aggregate tool timeout exceeded" in result.error:
                timeout_errors.append(result.error)
                break

        if not timeout_errors and tracker.can_proceed():
            follow_up = plan_candidate_inventory_check(state, results)
            if follow_up is not None:
                follow_res = await _call_tool_within_deadline(
                    registry, follow_up, deadline_mono, total_timeout
                )
                results.append(follow_res)
                tracker.record_tool_call()
                if follow_res.error and "Aggregate tool timeout exceeded" in follow_res.error:
                    timeout_errors.append(follow_res.error)

        logger.info(
            "node.execute_tools",
            extra={
                "request_id": state.request_id,
                "tools_called": len(results) - len(state.tool_results),
                "remaining": tracker.tool_calls_remaining,
            },
        )

        return state.model_copy(
            update={
                "tool_results": results,
                "tool_calls_remaining": tracker.tool_calls_remaining,
                "total_cost_usd": tracker.total_cost_usd,
                "total_tokens_used": tracker.total_tokens,
                "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                "errors": state.errors + timeout_errors if timeout_errors else state.errors,
                "current_node": "execute_tools",
                "completed_nodes": state.completed_nodes + ["execute_tools"],
            }
        )
    except Exception as exc:
        logger.exception("node.execute_tools.error", extra={"request_id": state.request_id})
        return state.model_copy(
            update={
                "errors": state.errors + [f"execute_tools: {exc}"],
                "current_node": "execute_tools",
                "completed_nodes": state.completed_nodes + ["execute_tools"],
            }
        )
