"""Expectation validator dispatch entrypoint and public exports."""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Tuple

try:
    from . import column_validators as _col, parsing as _parsing, table_validators as _tbl
except ImportError:
    import column_validators as _col
    import parsing as _parsing
    import table_validators as _tbl

logger = logging.getLogger(__name__)

COLUMN_VALIDATORS = _col.COLUMN_VALIDATORS
ColumnValidator = _col.ColumnValidator
TYPE_CHECKERS = _col.TYPE_CHECKERS
TABLE_VALIDATORS = _tbl.TABLE_VALIDATORS
TableValidator = _tbl.TableValidator
parse_finite_float = _parsing.parse_finite_float
parse_iso_timestamp = _parsing.parse_iso_timestamp

__all__ = [
    "COLUMN_VALIDATORS",
    "ColumnValidator",
    "TABLE_VALIDATORS",
    "TYPE_CHECKERS",
    "TableValidator",
    "parse_finite_float",
    "parse_iso_timestamp",
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

    table_validator = TABLE_VALIDATORS.get(exp_type)
    if table_validator is not None:
        return table_validator(exp_type, kwargs, records, strict_row_count)

    column = kwargs.get("column")
    column_validator = COLUMN_VALIDATORS.get(exp_type)
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
