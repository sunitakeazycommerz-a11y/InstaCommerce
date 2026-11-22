"""Shared numeric and ISO-8601 timestamp parsing utilities for quality validators."""

from __future__ import annotations

from datetime import datetime, timezone
import math
from typing import Any, Optional


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
