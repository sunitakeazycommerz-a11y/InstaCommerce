"""Table-level expectation validators and shared numeric/timestamp parsers."""

from __future__ import annotations

from datetime import datetime, timezone
import math
import sys
from typing import Any, Callable, Dict, List, Optional, Tuple


def parse_finite_float(value: Any, *, allow_str: bool = True) -> Optional[float]:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        num = float(value)
    elif allow_str and isinstance(value, str):
        try:
            num = float(value)
        except ValueError:
            return None
    else:
        return None
    return num if math.isfinite(num) else None


def parse_iso_timestamp(raw: Any) -> Optional[datetime]:
    if not isinstance(raw, str) or not raw.strip():
        return None
    normalized = raw.strip().replace("Z", "+00:00")
    try:
        dt = datetime.fromisoformat(normalized)
    except ValueError:
        return None
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def validate_row_count(
    exp_type: str,
    kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
    strict_row_count: bool,
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    row_count = len(records)
    min_val = kwargs.get("min_value", 0)
    max_val = kwargs.get("max_value", sys.maxsize)
    if not strict_row_count:
        min_val = 1
    if not (min_val <= row_count <= max_val):
        return False, {
            "expectation_type": exp_type,
            "error": f"Row count {row_count} outside expected range [{min_val}, {max_val}]",
            "row_count": row_count,
            "min_value": min_val,
            "max_value": max_val,
        }
    return True, None


def validate_pair_gte(
    exp_type: str,
    kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
    _strict_row_count: bool,
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    col_a = kwargs.get("column_A")
    col_b = kwargs.get("column_B")
    or_equal = bool(kwargs.get("or_equal", True))
    if not col_a or not col_b:
        return False, {
            "expectation_type": exp_type,
            "error": "Missing required kwargs 'column_A' or 'column_B'",
        }
    violations: List[Tuple[int, Any, Any]] = []
    for idx, row in enumerate(records):
        val_a = row.get(col_a)
        val_b = row.get(col_b)
        if val_a is None or val_b is None:
            continue
        a_num = parse_finite_float(val_a)
        b_num = parse_finite_float(val_b)
        if a_num is None or b_num is None:
            ok = False
        else:
            ok = a_num >= b_num if or_equal else a_num > b_num
        if not ok:
            violations.append((idx, val_a, val_b))
    if violations:
        return False, {
            "expectation_type": exp_type,
            "column_A": col_a,
            "column_B": col_b,
            "error": f"Column pair check failed for {len(violations)} row(s) on {col_a} >= {col_b}",
            "unexpected_samples": violations[:5],
        }
    return True, None


def validate_event_time_gaps(
    exp_type: str,
    kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
    _strict_row_count: bool,
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    column = kwargs.get("column") or kwargs.get("timestamp_column")
    if not column:
        return False, {
            "expectation_type": exp_type,
            "error": "Missing required timestamp 'column' kwarg",
        }
    if not records:
        if kwargs.get("allow_empty", False):
            return True, None
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"No records available to validate event-time gaps on '{column}'",
        }

    raw_max_gap = kwargs.get("max_gap_seconds")
    if raw_max_gap is None and kwargs.get("max_gap_minutes") is not None:
        minutes = parse_finite_float(kwargs["max_gap_minutes"])
        max_gap = minutes * 60.0 if minutes is not None else None
    elif raw_max_gap is not None:
        max_gap = parse_finite_float(raw_max_gap)
    else:
        max_gap = 3600.0
    if max_gap is None or max_gap < 0:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": "Invalid max_gap_seconds / max_gap_minutes threshold",
        }

    parsed: List[datetime] = []
    bad_rows: List[int] = []
    for idx, row in enumerate(records):
        dt = parse_iso_timestamp(row.get(column))
        if dt is None:
            bad_rows.append(idx)
        else:
            parsed.append(dt)
    if bad_rows:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Invalid or missing timestamp values at row indices {bad_rows[:5]}",
        }
    parsed.sort()
    gaps = [
        (parsed[i] - parsed[i - 1]).total_seconds()
        for i in range(1, len(parsed))
        if (parsed[i] - parsed[i - 1]).total_seconds() > max_gap
    ]
    if gaps:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Detected {len(gaps)} event-time gap(s) exceeding {max_gap}s (max={max(gaps):.1f}s)",
            "max_gap_seconds": max(gaps),
        }
    return True, None


TableValidator = Callable[
    [str, Dict[str, Any], List[Dict[str, Any]], bool],
    Tuple[bool, Optional[Dict[str, Any]]],
]

TABLE_VALIDATORS: Dict[str, TableValidator] = {
    "expect_table_row_count_to_be_between": validate_row_count,
    "expect_column_pair_values_a_to_be_greater_than_or_equal_to_b": validate_pair_gte,
    "expect_no_event_time_gaps": validate_event_time_gaps,
}
