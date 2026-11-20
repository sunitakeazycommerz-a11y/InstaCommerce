from __future__ import annotations

from dataclasses import dataclass
import os
from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

from app.graph.state import AgentState, IntentType, ToolResult

_CURRENCY_SYMBOL: str = os.environ.get("PLATFORM_CURRENCY_SYMBOL", "₹") or "₹"


@dataclass(frozen=True)
class SubstitutionCandidate:
    product_id: str
    name: str
    category: str = ""
    brand: str = ""
    price_cents: Optional[int] = None
    rating: float = 0.0
    in_stock: bool = True
    inventory_verified: bool = False
    available_qty: Optional[int] = None
    score: float = 0.0
    reason: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "product_id": self.product_id,
            "name": self.name,
            "category": self.category,
            "brand": self.brand,
            "price_cents": self.price_cents,
            "rating": round(self.rating, 2),
            "in_stock": self.in_stock,
            "inventory_verified": self.inventory_verified,
            "available_qty": self.available_qty,
            "score": round(self.score, 4),
            "reason": self.reason,
        }


def _extract_product_items(data: Any) -> List[Dict[str, Any]]:
    if not isinstance(data, dict):
        return []
    for key in ("results", "products", "content", "items"):
        raw = data.get(key)
        if isinstance(raw, list):
            return [item for item in raw if isinstance(item, dict)]
    if "id" in data or "productId" in data or "product_id" in data:
        return [data]
    return []


def _extract_inventory_map(tool_results: Sequence[ToolResult]) -> Tuple[Dict[str, Tuple[bool, Optional[int]]], Set[str]]:
    availability: Dict[str, Tuple[bool, Optional[int]]] = {}
    checked_ids: Set[str] = set()
    for tr in tool_results:
        if tr.tool_name != "inventory.check" or not tr.success or not isinstance(tr.data, dict):
            continue
        items = tr.data.get("items") or tr.data.get("results") or []
        if not isinstance(items, list):
            continue
        for entry in items:
            if not isinstance(entry, dict):
                continue
            pid = str(entry.get("productId") or entry.get("product_id") or entry.get("sku") or "").strip()
            if not pid:
                continue
            checked_ids.add(pid)
            qty_raw = entry.get("availableQuantity", entry.get("available_quantity", entry.get("quantity")))
            qty = int(qty_raw) if isinstance(qty_raw, (int, float)) else None
            in_stock_raw = entry.get("inStock", entry.get("in_stock", entry.get("available")))
            sufficient_raw = entry.get("sufficient")
            if isinstance(in_stock_raw, bool):
                in_stock = in_stock_raw and (qty is None or qty > 0)
            elif isinstance(sufficient_raw, bool):
                in_stock = sufficient_raw and (qty is None or qty > 0)
            elif qty is not None:
                in_stock = qty > 0
            else:
                in_stock = True
            availability[pid] = (in_stock, qty)
    return availability, checked_ids


def _collect_catalog_candidates(tool_results: Sequence[ToolResult]) -> List[Dict[str, Any]]:
    seen: Set[str] = set()
    candidates: List[Dict[str, Any]] = []
    for tr in tool_results:
        if tr.tool_name not in ("catalog.search", "catalog.list_products", "catalog.get_product"):
            continue
        if not tr.success:
            continue
        for item in _extract_product_items(tr.data):
            pid = str(item.get("id") or item.get("productId") or item.get("product_id") or "").strip()
            name = str(item.get("name") or item.get("title") or "").strip()
            if not pid or not name or pid in seen:
                continue
            seen.add(pid)
            candidates.append(item)
    return candidates


def plan_candidate_inventory_check(
    state: AgentState,
    tool_results: Sequence[ToolResult],
    max_candidates: int = 10,
) -> Optional[Dict[str, Any]]:
    if state.intent != IntentType.SUBSTITUTE:
        return None
    store_id = str(state.context.get("store_id") or state.context.get("storeId") or "").strip()
    if not store_id:
        return None
    _, checked_ids = _extract_inventory_map(tool_results)
    candidates = _collect_catalog_candidates(tool_results)
    missing_items: List[Dict[str, Any]] = []
    for item in candidates:
        pid = str(item.get("id") or item.get("productId") or item.get("product_id") or "").strip()
        if pid and pid not in checked_ids:
            missing_items.append({"productId": pid, "quantity": 1})
        if len(missing_items) >= max_candidates:
            break
    if not missing_items:
        return None
    return {
        "tool": "inventory.check",
        "body": {"storeId": store_id, "items": missing_items},
    }


def _excluded_product_ids(state: AgentState, availability: Dict[str, Tuple[bool, Optional[int]]]) -> Set[str]:
    excluded: Set[str] = set()
    raw_oos = state.context.get("out_of_stock_product_ids")
    if isinstance(raw_oos, list):
        for pid in raw_oos:
            if isinstance(pid, str) and pid.strip():
                excluded.add(pid.strip())
    orig_pid = state.context.get("out_of_stock_product_id") or state.context.get("original_product_id")
    if isinstance(orig_pid, str) and orig_pid.strip():
        excluded.add(orig_pid.strip())
    items_ctx = state.context.get("items")
    if isinstance(items_ctx, list):
        for entry in items_ctx:
            if not isinstance(entry, dict):
                continue
            pid = str(entry.get("productId") or entry.get("product_id") or "").strip()
            if not pid:
                continue
            if entry.get("inStock") is False or availability.get(pid, (True, None))[0] is False:
                excluded.add(pid)
    return excluded


def recommend_substitutions(
    state: AgentState,
    tool_results: Sequence[ToolResult],
    limit: int = 3,
) -> List[SubstitutionCandidate]:
    availability, checked_ids = _extract_inventory_map(tool_results)
    excluded = _excluded_product_ids(state, availability)
    raw_candidates = _collect_catalog_candidates(tool_results)
    target_category = str(state.context.get("category") or "").strip().lower()
    target_brand = str(state.context.get("brand") or "").strip().lower()
    target_price_raw = state.context.get("target_price_cents") or state.context.get("price_cents")
    target_price = int(target_price_raw) if isinstance(target_price_raw, (int, float)) and target_price_raw > 0 else None

    ranked: List[SubstitutionCandidate] = []
    for item in raw_candidates:
        pid = str(item.get("id") or item.get("productId") or item.get("product_id") or "").strip()
        if not pid or pid in excluded:
            continue
        name = str(item.get("name") or item.get("title") or "").strip()
        category = str(item.get("category") or "").strip()
        brand = str(item.get("brand") or "").strip()
        price_raw = item.get("priceCents", item.get("price_cents"))
        price_cents = int(price_raw) if isinstance(price_raw, (int, float)) and price_raw >= 0 else None
        rating_raw = item.get("rating", item.get("avg_rating", 0.0))
        rating = float(rating_raw) if isinstance(rating_raw, (int, float)) else 0.0

        verified = pid in checked_ids
        if verified:
            in_stock, avail_qty = availability[pid]
        else:
            raw_flag = item.get("inStock", item.get("in_stock"))
            in_stock = raw_flag is not False
            avail_qty = None

        if not in_stock:
            continue

        score = 0.0
        reasons: List[str] = []
        if verified:
            score += 0.35
            reasons.append("verified in stock")
        else:
            score += 0.15
            reasons.append("catalog match")

        if target_category and category.lower() == target_category:
            score += 0.25
            reasons.append("same category")
        if target_brand and brand.lower() == target_brand:
            score += 0.10
            reasons.append("same brand")
        if target_price and price_cents is not None:
            diff_ratio = abs(price_cents - target_price) / max(target_price, 100)
            score += 0.20 * max(0.0, 1.0 - diff_ratio)
            if diff_ratio <= 0.25:
                reasons.append("similar price")
        if rating > 0:
            score += 0.10 * min(rating, 5.0) / 5.0

        ranked.append(
            SubstitutionCandidate(
                product_id=pid,
                name=name,
                category=category,
                brand=brand,
                price_cents=price_cents,
                rating=rating,
                in_stock=in_stock,
                inventory_verified=verified,
                available_qty=avail_qty,
                score=score,
                reason=", ".join(reasons),
            )
        )

    ranked.sort(
        key=lambda c: (
            c.inventory_verified,
            round(c.score, 6),
            c.rating,
            -(c.price_cents if c.price_cents is not None else 10**9),
            c.name,
        ),
        reverse=True,
    )
    return ranked[:limit]


def format_substitution_summary(candidates: Sequence[SubstitutionCandidate]) -> str:
    if not candidates:
        return (
            "No in-stock substitutes were found right now for the requested item. "
            "Try broadening your search or checking another store."
        )
    formatted: List[str] = []
    for cand in candidates:
        details: List[str] = []
        if cand.price_cents is not None:
            details.append(f"{_CURRENCY_SYMBOL}{cand.price_cents / 100:.2f}")
        details.append("in stock" if cand.inventory_verified else "stock unverified")
        if cand.reason:
            details.append(cand.reason)
        formatted.append(f"{cand.name} ({', '.join(details)})")
    return "Recommended substitutes: " + "; ".join(formatted) + "."
