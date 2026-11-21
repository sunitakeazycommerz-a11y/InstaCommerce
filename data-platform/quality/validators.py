"""Expectation validator dispatch entrypoint and backwards-compatible re-exports."""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Tuple

try:
    from .column_validators import (
        COLUMN_VALIDATORS as _COLUMN_VALIDATORS,
        ColumnValidator as _ColumnValidator,
        TYPE_CHECKERS as _TYPE_CHECKERS,
        validate_column_to_exist as _validate_column_to_exist,
        validate_in_set as _validate_in_set,
        validate_match_regex as _validate_match_regex,
        validate_mean_between as _validate_mean_between,
        validate_not_null as _validate_not_null,
        validate_of_type as _validate_of_type,
        validate_unique as _validate_unique,
        validate_values_between as _validate_values_between,
    )
    from .table_validators import (
        TABLE_VALIDATORS as _TABLE_VALIDATORS,
        TableValidator as _TableValidator,
        parse_finite_float as _parse_finite_float,
        parse_iso_timestamp as _parse_iso_timestamp,
        validate_event_time_gaps as _validate_event_time_gaps,
        validate_pair_gte as _validate_pair_gte,
        validate_row_count as _validate_row_count,
    )
except ImportError:
    from column_validators import (
        COLUMN_VALIDATORS as _COLUMN_VALIDATORS,
        ColumnValidator as _ColumnValidator,
        TYPE_CHECKERS as _TYPE_CHECKERS,
        validate_column_to_exist as _validate_column_to_exist,
        validate_in_set as _validate_in_set,
        validate_match_regex as _validate_match_regex,
        validate_mean_between as _validate_mean_between,
        validate_not_null as _validate_not_null,
        validate_of_type as _validate_of_type,
        validate_unique as _validate_unique,
        validate_values_between as _validate_values_between,
    )
    from table_validators import (
        TABLE_VALIDATORS as _TABLE_VALIDATORS,
        TableValidator as _TableValidator,
        parse_finite_float as _parse_finite_float,
        parse_iso_timestamp as _parse_iso_timestamp,
        validate_event_time_gaps as _validate_event_time_gaps,
        validate_pair_gte as _validate_pair_gte,
        validate_row_count as _validate_row_count,
    )

logger = logging.getLogger(__name__)

__all__ = [
    "_COLUMN_VALIDATORS",
    "_ColumnValidator",
    "_TABLE_VALIDATORS",
    "_TYPE_CHECKERS",
    "_TableValidator",
    "_parse_finite_float",
    "_parse_iso_timestamp",
    "_validate_column_to_exist",
    "_validate_event_time_gaps",
    "_validate_in_set",
    "_validate_match_regex",
    "_validate_mean_between",
    "_validate_not_null",
    "_validate_of_type",
    "_validate_pair_gte",
    "_validate_row_count",
    "_validate_unique",
    "_validate_values_between",
    "validate_expectation",
]


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
