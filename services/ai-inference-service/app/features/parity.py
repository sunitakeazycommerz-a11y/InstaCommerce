"""Offline-to-online feature parity checks and trailing metric helpers."""

from __future__ import annotations

import datetime
import logging
import math
from typing import Any, Callable, Dict, List, Optional

logger = logging.getLogger(__name__)


def _coerce_event_date(raw_date: Any) -> Optional[datetime.date]:
    if isinstance(raw_date, str):
        return datetime.date.fromisoformat(raw_date[:10])
    if isinstance(raw_date, datetime.datetime):
        return raw_date.date()
    if isinstance(raw_date, datetime.date):
        return raw_date
    return None


def compute_trailing_conversion_rate(
    orders: List[Dict[str, Any]],
    sessions: List[Dict[str, Any]],
    reference_date: Optional[datetime.date] = None,
) -> float:
    """Compute 30-day conversion rate strictly excluding the current calendar day.

    Window: [reference_date - 30 days, reference_date - 1 day].
    """
    ref = reference_date or datetime.date.today()
    start_date = ref - datetime.timedelta(days=30)
    end_date = ref - datetime.timedelta(days=1)

    valid_orders = sum(
        1
        for o in orders
        if (d := _coerce_event_date(o.get("date"))) is not None and start_date <= d <= end_date
    )
    valid_sessions = sum(
        1
        for s in sessions
        if (d := _coerce_event_date(s.get("date"))) is not None and start_date <= d <= end_date
    )

    if valid_sessions == 0:
        return 0.0
    return float(min(1.0, valid_orders / valid_sessions))


def evaluate_feature_parity(
    get_online_features: Callable[[str], Dict[str, Any]],
    offline_features_map: Dict[str, Dict[str, float]],
    max_drift_pct: float = 1.0,
) -> Dict[str, Any]:
    """Compare offline features against online Redis features and summarize drift."""
    total = 0
    drifted = 0
    deltas: List[float] = []

    for user_id, off_feats in offline_features_map.items():
        on_feats = get_online_features(user_id)
        if on_feats.get("is_fallback"):
            continue

        for feat_name, off_val in off_feats.items():
            if feat_name not in on_feats:
                continue
            total += 1
            try:
                on_val = float(on_feats[feat_name])
            except (TypeError, ValueError):
                drifted += 1
                continue
            if not math.isfinite(on_val):
                drifted += 1
                continue
            delta = abs(off_val - on_val)
            deltas.append(delta)
            if off_val != 0:
                if (delta / abs(off_val)) > 0.01:
                    drifted += 1
            elif delta > 0.005:
                drifted += 1

    drift_rate_pct = (drifted / total * 100.0) if total > 0 else 0.0
    passed = drift_rate_pct < max_drift_pct

    result = {
        "total_evaluated": total,
        "drifted_count": drifted,
        "drift_rate_pct": round(drift_rate_pct, 4),
        "max_allowed_drift_pct": max_drift_pct,
        "passed": passed,
    }
    if not passed:
        logger.warning("Feature parity check FAILED: %s", result)
    else:
        logger.info("Feature parity check PASSED: %s", result)
    return result
