"""Parsing helpers for Redis feature store hashes and JSON entries."""

from __future__ import annotations

import json
import logging
import math
import re
from typing import Any, Dict, Optional, Tuple, cast

logger = logging.getLogger(__name__)

_INT_RE = re.compile(r"[+-]?\d+")
_FLOAT_RE = re.compile(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?")
_STRING_METADATA_FIELDS = frozenset(
    {
        "user_id",
        "segment",
        "updated_at",
        "job_id",
        "clv_segment",
        "preferred_payment_method",
        "preferred_delivery_slot",
    }
)


def decode_redis_text(value: Any) -> Optional[str]:
    if isinstance(value, bytes):
        try:
            return value.decode("utf-8")
        except UnicodeDecodeError:
            return None
    if isinstance(value, str):
        return value
    return None


def parse_hash_value(field: str, raw_value: Any) -> Tuple[bool, Any]:
    if raw_value is None:
        return False, None
    if isinstance(raw_value, bool):
        return True, raw_value
    if isinstance(raw_value, int):
        return True, raw_value
    if isinstance(raw_value, float):
        return (True, raw_value) if math.isfinite(raw_value) else (False, None)

    text = decode_redis_text(raw_value)
    if text is None:
        return False, None

    if field in _STRING_METADATA_FIELDS:
        return True, text

    stripped = text.strip()
    if not stripped:
        return True, text

    if (stripped.startswith("{") and stripped.endswith("}")) or (
        stripped.startswith("[") and stripped.endswith("]")
    ):
        try:
            parsed_json = json.loads(stripped)
            if isinstance(parsed_json, (dict, list)):
                return True, parsed_json
        except json.JSONDecodeError:
            return True, text

    if _INT_RE.fullmatch(stripped):
        return True, int(stripped)

    if _FLOAT_RE.fullmatch(stripped):
        parsed_float = float(stripped)
        if math.isfinite(parsed_float):
            return True, parsed_float

    return True, text


def parse_user_hash(raw_hash: Dict[Any, Any]) -> Dict[str, Any]:
    parsed: Dict[str, Any] = {}
    for raw_key, raw_val in raw_hash.items():
        field = decode_redis_text(raw_key)
        if not field:
            continue
        valid, value = parse_hash_value(field, raw_val)
        if valid:
            parsed[field] = value
    return parsed


def parse_json_dict(raw: Optional[str]) -> Optional[Dict[str, Any]]:
    if not raw:
        return None
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError, ValueError) as exc:
        logger.warning("Failed to decode JSON feature entry: %s", exc)
        return None
    if isinstance(parsed, dict):
        return cast(Dict[str, Any], parsed)
    return None
