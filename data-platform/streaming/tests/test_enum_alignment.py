from __future__ import annotations

from datetime import datetime, timezone
import json
import sys
from pathlib import Path

_TESTS_DIR = str(Path(__file__).resolve().parent)
if _TESTS_DIR not in sys.path:
    sys.path.insert(0, _TESTS_DIR)

import test_streaming_pipelines as _beam_setup  # type: ignore  # noqa: E402, F401
from pipelines.order_events_pipeline import (  # noqa: E402
    DEAD_LETTER_TAG,
    OrderEventParser,
    OrderVolumeAggregator,
)
from pipelines.payment_events_pipeline import PaymentSuccessRateAggregator  # noqa: E402


def test_payment_success_rate_aggregator_recognizes_captured_and_completed() -> None:
    agg = PaymentSuccessRateAggregator()
    acc = agg.create_accumulator()
    for status in ("CAPTURED", "COMPLETED", "SUCCESS", "FAILED", "VOIDED"):
        acc = agg.add_input(
            acc,
            {
                "payment_id": f"pay-{status}",
                "status": status,
                "amount_cents": 1000,
                "payment_method": "UPI",
                "event_timestamp": "2026-04-01T10:00:00Z",
            },
        )
    out = agg.extract_output(acc)
    assert out["total"] == 5
    assert out["success"] == 3
    assert out["failed"] == 2
    assert out["success_rate"] == 0.6


def test_order_event_parser_accepts_java_outbox_events_and_requires_store_on_placed() -> None:
    assert {"OrderConfirmed", "OrderPacked", "OrderCancelled"}.issubset(
        OrderEventParser.VALID_EVENT_TYPES
    )
    parser = OrderEventParser()

    now_iso = datetime.now(timezone.utc).isoformat()
    cancelled_raw = json.dumps({
        "eventId": "evt-cancel-1",
        "eventType": "OrderCancelled",
        "aggregateId": "ord-100",
        "eventTime": now_iso,
        "payload": {"orderId": "ord-100", "reason": "customer_request"},
    }).encode("utf-8")
    cancelled_out = list(parser.process((b"ord-100", cancelled_raw)))
    assert len(cancelled_out) == 1
    out_val = getattr(cancelled_out[0], "value", cancelled_out[0])
    assert isinstance(out_val, dict)
    assert out_val["store_id"] == "UNKNOWN"
    assert out_val["event_type"] == "OrderCancelled"

    placed_missing_store = json.dumps({
        "eventId": "evt-placed-1",
        "eventType": "ORDER_PLACED",
        "aggregateId": "ord-101",
        "eventTime": now_iso,
        "payload": {"orderId": "ord-101", "totalCents": 2500},
    }).encode("utf-8")
    dlq_out = list(parser.process((b"ord-101", placed_missing_store)))
    assert len(dlq_out) == 1
    assert getattr(dlq_out[0], "tag", None) == DEAD_LETTER_TAG


def test_order_volume_aggregator_counts_placement_events_only() -> None:
    events = [
        {"order_id": f"o-{et}", "event_type": et, "total_cents": 1000}
        for et in ("OrderPlaced", "OrderConfirmed", "OrderCancelled")
    ]
    volume_events = [
        item for item in events if item["event_type"] in OrderEventParser.VOLUME_EVENT_TYPES
    ]
    agg = OrderVolumeAggregator()
    acc = agg.create_accumulator()
    for item in volume_events:
        acc = agg.add_input(acc, item)
    out = agg.extract_output(acc)
    assert out["count"] == 2
    assert out["gmv_cents"] == 2000
