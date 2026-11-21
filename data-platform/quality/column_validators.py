"""Column-level expectation validators."""

from __future__ import annotations

import math
import re
from typing import Any, Callable, Dict, List, Optional, Tuple

try:
    from .table_validators import parse_finite_float
except ImportError:
    from table_validators import parse_finite_float

TYPE_CHECKERS: Dict[str, Callable[[Any], bool]] = {
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


def validate_column_to_exist(
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


def validate_not_null(
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


def validate_unique(
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


def validate_values_between(
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
        num_v = parse_finite_float(v)
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


def validate_in_set(
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


def validate_match_regex(
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


def validate_of_type(
    exp_type: str,
    column: str,
    kwargs: Dict[str, Any],
    records: List[Dict[str, Any]],
) -> Tuple[bool, Optional[Dict[str, Any]]]:
    expected_type = str(kwargs.get("type_", "")).lower()
    checker = TYPE_CHECKERS.get(expected_type)
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


def validate_mean_between(
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
        num = parse_finite_float(v, allow_str=False)
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


ColumnValidator = Callable[
    [str, str, Dict[str, Any], List[Dict[str, Any]]],
    Tuple[bool, Optional[Dict[str, Any]]],
]

COLUMN_VALIDATORS: Dict[str, ColumnValidator] = {
    "expect_column_to_exist": validate_column_to_exist,
    "expect_column_values_to_not_be_null": validate_not_null,
    "expect_column_values_to_be_unique": validate_unique,
    "expect_column_values_to_be_between": validate_values_between,
    "expect_column_values_to_be_in_set": validate_in_set,
    "expect_column_values_to_match_regex": validate_match_regex,
    "expect_column_values_to_be_of_type": validate_of_type,
    "expect_column_mean_to_be_between": validate_mean_between,
}
