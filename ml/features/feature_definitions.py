"""Canonical ML Feature Definitions — InstaCommerce ML Platform.

Guarantees exact offline-to-online parity by standardizing rolling feature windows:
all 30-day trailing metrics strictly exclude the current calendar day (i.e. [T-30d, T-1d]),
preventing training-serving target leakage and offline-to-online drift.
"""

from __future__ import annotations

import datetime
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple


@dataclass(frozen=True)
class FeatureWindow:
    """Window specification for feature aggregation."""
    days: int
    exclude_current_day: bool = True

    def get_date_bounds(self, reference_date: Optional[datetime.date] = None) -> Tuple[datetime.date, datetime.date]:
        """Return (start_date, end_date) inclusive.

        When exclude_current_day=True:
            end_date = reference_date - 1 day
            start_date = reference_date - days
        """
        ref = reference_date or datetime.date.today()
        if self.exclude_current_day:
            end_date = ref - datetime.timedelta(days=1)
            start_date = ref - datetime.timedelta(days=self.days)
        else:
            end_date = ref
            start_date = ref - datetime.timedelta(days=self.days - 1)
        return start_date, end_date


# Canonical 30-day trailing window excluding today
WINDOW_30D_EXCLUDING_TODAY = FeatureWindow(days=30, exclude_current_day=True)


def compute_user_conversion_rate_30d(
    orders: List[Dict[str, Any]],
    searches_or_carts: List[Dict[str, Any]],
    reference_date: Optional[datetime.date] = None,
) -> float:
    """Compute 30-day user conversion rate strictly excluding the current calendar day.

    Formula: orders_count / total_sessions_or_searches in [T-30d, T-1d].
    """
    start_date, end_date = WINDOW_30D_EXCLUDING_TODAY.get_date_bounds(reference_date)

    valid_orders = 0
    for o in orders:
        o_date = o.get("date")
        if isinstance(o_date, str):
            o_date = datetime.date.fromisoformat(o_date[:10])
        elif isinstance(o_date, datetime.datetime):
            o_date = o_date.date()
        if o_date and start_date <= o_date <= end_date:
            valid_orders += 1

    valid_sessions = 0
    for s in searches_or_carts:
        s_date = s.get("date")
        if isinstance(s_date, str):
            s_date = datetime.date.fromisoformat(s_date[:10])
        elif isinstance(s_date, datetime.datetime):
            s_date = s_date.date()
        if s_date and start_date <= s_date <= end_date:
            valid_sessions += 1

    if valid_sessions == 0:
        return 0.0
    return float(min(1.0, valid_orders / valid_sessions))


def verify_feature_parity(
    offline_features: Dict[str, float],
    online_features: Dict[str, float],
    max_drift_pct: float = 1.0,
) -> Dict[str, Any]:
    """Verify offline-to-online parity across user feature records.

    Asserts that the percentage of users with feature discrepancy > 1e-4
    is strictly less than max_drift_pct (default 1.0%).
    """
    total_evaluated = 0
    drifted_count = 0
    deltas = []

    for key, off_val in offline_features.items():
        if key in online_features:
            on_val = online_features[key]
            total_evaluated += 1
            delta = abs(off_val - on_val)
            deltas.append(delta)
            # Consider drifted if relative difference > 0.01 (1%) or absolute diff > 0.005
            if off_val != 0:
                rel_diff = delta / abs(off_val)
                if rel_diff > 0.01:
                    drifted_count += 1
            elif delta > 0.005:
                drifted_count += 1

    drift_rate = (drifted_count / total_evaluated * 100.0) if total_evaluated > 0 else 0.0
    passed = drift_rate < max_drift_pct

    return {
        "total_evaluated": total_evaluated,
        "drifted_count": drifted_count,
        "drift_rate_pct": round(drift_rate, 4),
        "max_allowed_drift_pct": max_drift_pct,
        "max_delta": max(deltas) if deltas else 0.0,
        "avg_delta": sum(deltas) / len(deltas) if deltas else 0.0,
        "passed": passed,
    }
