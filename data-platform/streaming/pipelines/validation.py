# pyright: reportMissingImports=false
"""Shared schema validation and null-safety coercion for Beam streaming pipelines."""
from __future__ import annotations

import math
from typing import Any, List, Optional


def require_non_empty_str(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"Missing or invalid {field_name}")
    return value.strip()


def coerce_int(
    value: Any,
    field_name: str,
    *,
    default: Optional[int] = None,
    min_value: Optional[int] = None,
    max_value: Optional[int] = None,
) -> int:
    if value is None:
        if default is None:
            raise ValueError(f"Missing required integer {field_name}")
        result = default
    elif isinstance(value, bool):
        raise ValueError(f"Invalid integer {field_name}: boolean not allowed")
    elif isinstance(value, int):
        result = value
    elif isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError(f"Non-finite value for {field_name}")
        result = int(value)
    elif isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            if default is None:
                raise ValueError(f"Empty integer {field_name}")
            result = default
        else:
            try:
                result = int(float(stripped)) if "." in stripped else int(stripped)
            except ValueError as exc:
                raise ValueError(f"Invalid integer {field_name}: {value!r}") from exc
    else:
        raise ValueError(f"Invalid integer {field_name}: {type(value).__name__}")

    if min_value is not None and result < min_value:
        raise ValueError(f"{field_name} {result} below minimum {min_value}")
    if max_value is not None and result > max_value:
        raise ValueError(f"{field_name} {result} above maximum {max_value}")
    return result


def coerce_optional_int(
    value: Any,
    field_name: str,
    *,
    min_value: Optional[int] = None,
    max_value: Optional[int] = None,
) -> Optional[int]:
    if value is None:
        return None
    return coerce_int(value, field_name, min_value=min_value, max_value=max_value)


def coerce_float(
    value: Any,
    field_name: str,
    *,
    default: Optional[float] = None,
    min_value: Optional[float] = None,
    max_value: Optional[float] = None,
) -> float:
    if value is None:
        if default is None:
            raise ValueError(f"Missing required float {field_name}")
        result = default
    elif isinstance(value, bool):
        raise ValueError(f"Invalid float {field_name}: boolean not allowed")
    elif isinstance(value, (int, float)):
        result = float(value)
    elif isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            if default is None:
                raise ValueError(f"Empty float {field_name}")
            result = default
        else:
            try:
                result = float(stripped)
            except ValueError as exc:
                raise ValueError(f"Invalid float {field_name}: {value!r}") from exc
    else:
        raise ValueError(f"Invalid float {field_name}: {type(value).__name__}")

    if not math.isfinite(result):
        raise ValueError(f"Non-finite value for {field_name}")
    if min_value is not None and result < min_value:
        raise ValueError(f"{field_name} {result} below minimum {min_value}")
    if max_value is not None and result > max_value:
        raise ValueError(f"{field_name} {result} above maximum {max_value}")
    return result


def coerce_optional_float(
    value: Any,
    field_name: str,
    *,
    min_value: Optional[float] = None,
    max_value: Optional[float] = None,
) -> Optional[float]:
    if value is None:
        return None
    return coerce_float(value, field_name, min_value=min_value, max_value=max_value)


def coerce_str_list(value: Any, field_name: str) -> List[str]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise ValueError(f"Invalid {field_name}: expected list, got {type(value).__name__}")
    items: List[str] = []
    for entry in value:
        if not isinstance(entry, str) or not entry.strip():
            raise ValueError(f"Invalid entry in {field_name}: expected non-empty string")
        items.append(entry.strip())
    return items
