# pyright: reportMissingImports=false
"""Shared event-parsing and window helpers for Dataflow streaming pipelines."""
from datetime import datetime, timezone
import time

import apache_beam as beam
from dateutil.parser import isoparse


def first_present(mapping, *keys, default=None):
    """Return the first non-None value matching any of the given keys."""
    for key in keys:
        if key in mapping and mapping[key] is not None:
            return mapping[key]
    return default


def parse_event_ts(event_time_str):
    """Parse an ISO-8601 event timestamp, falling back to current epoch seconds."""
    if event_time_str:
        try:
            return isoparse(event_time_str).timestamp()
        except (ValueError, TypeError):
            return time.time()
    return time.time()


def window_end_iso(window) -> str:
    """Format an Apache Beam window's UTC end timestamp in ISO-8601."""
    if window is None or window is beam.DoFn.WindowParam:
        return datetime.now(timezone.utc).isoformat()
    return window.end.to_utc_datetime().isoformat()
