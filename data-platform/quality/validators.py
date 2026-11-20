from __future__ import annotations

from datetime import datetime, timezone
import logging
import math
import re
import sys
from typing import Any, Callable, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

_TYPE_CHECKERS: Dict[str, Callable[[Any], bool]] = {
    "str": lambda v: isinstance(v, str),
    "string": lambda v: isinstance(v, str),
    "int": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "int64": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "float": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(float(v)),
    "float64": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(float(v)),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(float(v)),
    "bool": lambda v: isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
}


def _parse_finite_float(value: Any, *, allow_str: bool = True) -> Optional[float]:
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


def _parse_iso_timestamp(raw: Any) -> Optional[datetime]:
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


def _validate_row_count(
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


def _validate_pair_gte(
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
        a_num = _parse_finite_float(val_a)
        b_num = _parse_finite_float(val_b)
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


def _validate_event_time_gaps(
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
        minutes = _parse_finite_float(kwargs["max_gap_minutes"])
        max_gap = minutes * 60.0 if minutes is not None else None
    elif raw_max_gap is not None:
        max_gap = _parse_finite_float(raw_max_gap)
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
        dt = _parse_iso_timestamp(row.get(column))
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


def _validate_column_to_exist(
    exp_type: str,
    column: str,
    _kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    missing_rows = [i for i, r in enumerate(records) if column not in r]
    if not records or missing_rows:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Column '{column}' missing in {len(missing_rows)} record(s)",
        }
    return True, None


def _validate_not_null(
    exp_type: str,
    column: str,
    _kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    null_indices = [i for i, r in enumerate(records) if r.get(column) is None]
    if null_indices:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Found {len(null_indices)} null value(s) in column '{column}'",
            "unexpected_indices": null_indices[:5],
            "unexpected_count": len(null_indices),
        }
    return True, None


def _validate_unique(
    exp_type: str,
    column: str,
    _kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    seen = set()
    duplicates = set()
    for r in records:
        v = r.get(column)
        if v is not None:
            if v in seen:
                duplicates.add(v)
            seen.add(v)
    if duplicates:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Found duplicate values in unique column '{column}': {list(duplicates)[:5]}",
            "unexpected_values": list(duplicates)[:5],
            "duplicate_count": len(duplicates),
        }
    return True, None


def _validate_values_between(
    exp_type: str,
    column: str,
    kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    min_val = kwargs.get("min_value")
    max_val = kwargs.get("max_value")
    out_of_bounds = []
    for i, r in enumerate(records):
        v = r.get(column)
        if v is None:
            continue
        num_v = _parse_finite_float(v)
        if num_v is None:
            out_of_bounds.append((i, v))
            continue
        if min_val is not None and num_v < float(min_val):
            out_of_bounds.append((i, v))
        elif max_val is not None and num_v > float(max_val):
            out_of_bounds.append((i, v))
    if out_of_bounds:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Found {len(out_of_bounds)} value(s) outside [{min_val}, {max_val}] in '{column}'",
            "unexpected_samples": out_of_bounds[:5],
        }
    return True, None


def _validate_in_set(
    exp_type: str,
    column: str,
    kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    value_set = set(kwargs.get("value_set", []))
    invalid_values = [
        v for v in (r.get(column) for r in records) if v is not None and v not in value_set
    ]
    if invalid_values:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Found invalid values not in set for column '{column}': {invalid_values[:5]}",
            "allowed_set": list(value_set),
            "unexpected_values": invalid_values[:5],
        }
    return True, None


def _validate_match_regex(
    exp_type: str,
    column: str,
    kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    regex_pattern = kwargs.get("regex", "")
    compiled = re.compile(regex_pattern)
    mismatches = [
        v for v in (r.get(column) for r in records) if v is not None and not compiled.match(str(v))
    ]
    if mismatches:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Found values in '{column}' not matching regex '{regex_pattern}': {mismatches[:5]}",
            "regex": regex_pattern,
            "unexpected_values": mismatches[:5],
        }
    return True, None


def _validate_of_type(
    exp_type: str,
    column: str,
    kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    expected_type = str(kwargs.get("type_", "")).lower()
    checker = _TYPE_CHECKERS.get(expected_type)
    if checker is None:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Unsupported expected type '{expected_type}'",
        }
    bad = [
        (i, type(v).__name__)
        for i, v in enumerate(r.get(column) for r in records)
        if v is not None and not checker(v)
    ]
    if bad:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Found {len(bad)} value(s) in '{column}' not of type '{expected_type}'",
            "unexpected_samples": bad[:5],
        }
    return True, None


def _validate_mean_between(
    exp_type: str,
    column: str,
    kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    min_val = kwargs.get("min_value")
    max_val = kwargs.get("max_value")
    numeric: List[float] = []
    invalid: List[Tuple[int, Any]] = []
    for i, r in enumerate(records):
        v = r.get(column)
        if v is None:
            continue
        num = _parse_finite_float(v, allow_str=False)
        if num is None:
            invalid.append((i, v))
        else:
            numeric.append(num)
    if invalid:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Found {len(invalid)} non-numeric or non-finite value(s) in '{column}'",
            "unexpected_samples": invalid[:5],
        }
    if not numeric:
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"No numeric values available to compute mean for '{column}'",
        }
    mean_val = sum(numeric) / len(numeric)
    if not math.isfinite(mean_val) or (
        (min_val is not None and mean_val < float(min_val))
        or (max_val is not None and mean_val > float(max_val))
    ):
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Column '{column}' mean {mean_val:.4f} outside [{min_val}, {max_val}]",
            "observed_value": mean_val,
        }
    return True, None


_TableValidator = Callable[
    [str, Dict[str, Any], List[Dict[str, Any]], bool],
    Tuple[bool, Optional[Dict[str, Any]]],
]
_ColumnValidator = Callable[
    [str, str, Dict[str, Any], List[Dict[str, Any]]],
    Tuple[bool, Optional[Dict[str, Any]]],
]

_TABLE_VALIDATORS: Dict[str, _TableValidator] = {
    "expect_table_row_count_to_be_between": _validate_row_count,
    "expect_column_pair_values_a_to_be_greater_than_or_equal_to_b": _validate_pair_gte,
    "expect_no_event_time_gaps": _validate_event_time_gaps,
}

_COLUMN_VALIDATORS: Dict[str, _ColumnValidator] = {
    "expect_column_to_exist": _validate_column_to_exist,
    "expect_column_values_to_not_be_null": _validate_not_null,
    "expect_column_values_to_be_unique": _validate_unique,
    "expect_column_values_to_be_between": _validate_values_between,
    "expect_column_values_to_be_in_set": _validate_in_set,
    "expect_column_values_to_match_regex": _validate_match_regex,
    "expect_column_values_to_be_of_type": _validate_of_type,
    "expect_column_mean_to_be_between": _validate_mean_between,
}


def validate_expectation(
    exp: Dict[str, Any],
    records: List[Dict[str, Any]],
    strict_row_count: bool = False,
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    """Validate a single expectation against a list of record dicts."""
    exp_type = str(exp.get("expectation_type") or "")
    kwargs = exp.get("kwargs", {})

    table_validator = _TABLE_VALIDATORS.get(exp_type)
    if table_validator is not None:
        return table_validator(exp_type, kwargs, records, strict_row_count)

    column = kwargs.get("column")
    column_validator = _COLUMN_VALIDATORS.get(exp_type)
    if column_validator is None:
        logger.error("Unsupported expectation type: %s", exp_type)
        return False, {
            "expectation_type": exp_type,
            "column": column,
            "error": f"Unsupported expectation type '{exp_type}'",
        }
    if column is None:
        return False, {
            "expectation_type": exp_type,
            "error": f"Missing required 'column' kwarg for expectation '{exp_type}'",
        }
    return column_validator(exp_type, column, kwargs, records)
