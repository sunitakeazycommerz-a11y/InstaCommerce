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

_USER_SCOPED_TOOLS = frozenset({"order.get", "cart.get"})


def _normalize_inventory_items(raw_items: Any) -> List[Dict[str, Any]]:
    if not isinstance(raw_items, list):
        return []
    normalized: List[Dict[str, Any]] = []
    for entry in raw_items:
        if not isinstance(entry, dict):
            continue
        product_id = entry.get("productId") or entry.get("product_id")
        if not product_id or not str(product_id).strip():
            continue
        raw_qty = entry.get("quantity", 1)
        qty = int(raw_qty) if isinstance(raw_qty, (int, float)) and not isinstance(raw_qty, bool) else 1
        normalized.append({"productId": str(product_id).strip(), "quantity": max(1, qty)})
    return normalized


def _verify_user_scoped_result(tool_name: str, result: ToolResult, caller_uid: str) -> ToolResult:
    if tool_name not in _USER_SCOPED_TOOLS or not result.success:
        return result
    data = result.data if isinstance(result.data, dict) else {}
    owner_uid = data.get("userId") or data.get("user_id")
    if not owner_uid or str(owner_uid).strip() != caller_uid:
        logger.warning(
            "node.execute_tools.bola_blocked",
            extra={"tool": tool_name, "caller_uid": caller_uid},
        )
        return ToolResult(
            tool_name=tool_name,
            success=False,
            error="authorization_denied: resource ownership mismatch",
            latency_ms=result.latency_ms,
        )
    return result


def plan_tools(state: AgentState) -> List[Dict[str, Any]]:
    """Return an ordered list of tool calls for the detected intent."""
    plan: List[Dict[str, Any]] = []
    caller_uid = (state.user_id or "").strip()

    if state.intent == IntentType.SEARCH:
        plan.append({"tool": "catalog.search", "params": {"query": state.query}})
    elif state.intent == IntentType.SUBSTITUTE:
        plan.append({"tool": "catalog.search", "params": {"query": state.query}})
        store_id = state.context.get("store_id") or state.context.get("storeId")
        items = _normalize_inventory_items(state.context.get("items"))
        if store_id and items:
            plan.append(
                {
                    "tool": "inventory.check",
                    "body": {
                        "storeId": str(store_id),
                        "items": items,
                    },
                }
            )
    elif state.intent == IntentType.ORDER_STATUS:
        order_id = state.context.get("order_id", "")
        if order_id and caller_uid:
            plan.append({"tool": "order.get", "path_params": {"order_id": str(order_id)}})
    elif state.intent == IntentType.RECOMMEND:
        plan.append({"tool": "catalog.list_products", "params": {"sort": "createdAt,desc"}})
    elif state.intent == IntentType.SUPPORT:
        order_id = state.context.get("order_id", "")
        if order_id and caller_uid:
            plan.append({"tool": "order.get", "path_params": {"order_id": str(order_id)}})

    return plan


async def _call_tool_within_deadline(
    registry: ToolRegistry,
    plan: Dict[str, Any],
    deadline_mono: float,
    total_timeout: float,
    *,
    user_id: Optional[str] = None,
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
                user_id=user_id,
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
    """Execute planned tools with ``BudgetTracker`` enforcement and BOLA checks."""
    try:
        tracker = budget_tracker_from_state(state, settings=registry.settings if registry else None)
        tool_plan = plan_tools(state)
        all_planned: List[Dict[str, Any]] = list(tool_plan)

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
                    "planned_tool_calls": all_planned,
                    "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        results: List[ToolResult] = list(state.tool_results)

        if not state.execute_tools:
            logger.info("node.execute_tools.skipped", extra={"request_id": state.request_id})
            return state.model_copy(
                update={
                    "planned_tool_calls": all_planned,
                    "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        if registry is None:
            logger.info("node.execute_tools.no_registry", extra={"request_id": state.request_id})
            return state.model_copy(
                update={
                    "planned_tool_calls": all_planned,
                    "elapsed_ms": max(state.elapsed_ms, tracker.elapsed_ms),
                    "current_node": "execute_tools",
                    "completed_nodes": state.completed_nodes + ["execute_tools"],
                }
            )

        total_timeout = float(registry.settings.tool_total_timeout_seconds)
        deadline_mono = time.monotonic() + max(0.001, total_timeout)
        timeout_errors: List[str] = []
        caller_uid = (state.user_id or "").strip()

        for plan in tool_plan:
            if not tracker.can_proceed():
                break

            tool_name = str(plan["tool"])
            if tool_name in _USER_SCOPED_TOOLS and not caller_uid:
                results.append(
                    ToolResult(
                        tool_name=tool_name,
                        success=False,
                        error="authorization_denied: user_id required for user-scoped tool call",
                    )
                )
                continue

            result = await _call_tool_within_deadline(
                registry,
                plan,
                deadline_mono,
                total_timeout,
                user_id=caller_uid or None,
            )
            if caller_uid:
                result = _verify_user_scoped_result(tool_name, result, caller_uid)
            results.append(result)
            tracker.record_tool_call()
            if result.error and "Aggregate tool timeout exceeded" in result.error:
                timeout_errors.append(result.error)
                break

        if not timeout_errors and tracker.can_proceed():
            follow_up = plan_candidate_inventory_check(state, results)
            if follow_up is not None:
                all_planned.append(follow_up)
                follow_res = await _call_tool_within_deadline(
                    registry,
                    follow_up,
                    deadline_mono,
                    total_timeout,
                    user_id=caller_uid or None,
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
                "planned_tool_calls": all_planned,
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
