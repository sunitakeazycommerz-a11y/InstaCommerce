"""Unit tests for Data Platform Apache Beam streaming pipelines.

Tests event time watermarking, late event dead-lettering, cart session abandonment,
payment insert failure formatting, inventory discarding accumulation,
rider location spatial pre-aggregation, and streaming order-payment joins.
"""
import sys
import types
from unittest.mock import MagicMock
import json
import time
from datetime import datetime, timezone

# Provide mock apache_beam if not installed in current environment
if "apache_beam" not in sys.modules:
    mock_beam = types.ModuleType("apache_beam")
    mock_beam.DoFn = object
    mock_beam.CombineFn = object

    class MockTaggedOutput:
        def __init__(self, tag, value):
            self.tag = tag
            self.value = value

    class MockTimestampedValue:
        def __init__(self, value, timestamp):
            self.value = value
            self.timestamp = timestamp

    class MockPValue:
        TaggedOutput = MockTaggedOutput

    mock_beam.pvalue = MockPValue()
    mock_beam.transforms = types.ModuleType("transforms")
    mock_beam.transforms.window = types.ModuleType("window")
    mock_beam.transforms.window.TimestampedValue = MockTimestampedValue
    mock_beam.transforms.window.Duration = lambda seconds=0: seconds
    mock_beam.transforms.window.FixedWindows = MagicMock()
    mock_beam.transforms.window.SlidingWindows = MagicMock()
    mock_beam.transforms.window.Sessions = MagicMock()
    mock_beam.transforms.trigger = types.ModuleType("trigger")
    mock_beam.transforms.trigger.AfterWatermark = MagicMock()
    mock_beam.transforms.trigger.AfterProcessingTime = MagicMock()
    mock_beam.transforms.trigger.AfterCount = MagicMock()

    class MockAccumulationMode:
        ACCUMULATING = "ACCUMULATING"
        DISCARDING = "DISCARDING"

    mock_beam.transforms.trigger.AccumulationMode = MockAccumulationMode
    mock_beam.options = types.ModuleType("options")
    mock_beam.options.pipeline_options = types.ModuleType("pipeline_options")
    mock_beam.options.pipeline_options.PipelineOptions = MagicMock()
    mock_beam.options.pipeline_options.StandardOptions = MagicMock()
    mock_beam.io = types.ModuleType("io")
    mock_beam.io.kafka = types.ModuleType("kafka")
    mock_beam.io.kafka.ReadFromKafka = MagicMock()
    mock_beam.io.gcp = types.ModuleType("gcp")
    mock_beam.io.gcp.bigquery = types.ModuleType("bigquery")
    mock_beam.io.gcp.bigquery.WriteToBigQuery = MagicMock()
    mock_beam.io.WriteToText = MagicMock()
    mock_beam.Map = MagicMock()
    mock_beam.Filter = MagicMock()
    mock_beam.ParDo = MagicMock()
    mock_beam.CombinePerKey = MagicMock()
    mock_beam.Values = MagicMock()
    mock_beam.CoGroupByKey = MagicMock()
    mock_beam.Pipeline = MagicMock()

    sys.modules["apache_beam"] = mock_beam
    sys.modules["apache_beam.options.pipeline_options"] = mock_beam.options.pipeline_options
    sys.modules["apache_beam.io.kafka"] = mock_beam.io.kafka
    sys.modules["apache_beam.io.gcp.bigquery"] = mock_beam.io.gcp.bigquery
    sys.modules["apache_beam.transforms.window"] = mock_beam.transforms.window
    sys.modules["apache_beam.transforms.trigger"] = mock_beam.transforms.trigger

import os
streaming_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if streaming_dir not in sys.path:
    sys.path.insert(0, streaming_dir)

from pipelines.order_events_pipeline import (  # noqa: E402
    OrderEventParser,
    OrderVolumeAggregator,
    SLAComplianceAggregator,
)
from pipelines.cart_events_pipeline import (  # noqa: E402
    CartSessionAggregator,
)
from pipelines.payment_events_pipeline import (  # noqa: E402
    PaymentSuccessRateAggregator,
    FormatBigQueryInsertFailureFn,
)
from pipelines.inventory_events_pipeline import (  # noqa: E402
    InventoryVelocityAggregator,
)
from pipelines.rider_location_pipeline import (  # noqa: E402
    compute_spatial_cell,
    SpatialPreAggregateFn,
    ZoneUtilizationAggregator,
)
from pipelines.order_payment_join_pipeline import (  # noqa: E402
    CorrelateOrderPaymentFn,
)


def test_order_event_parser_event_time_and_on_time():
    """Verify OrderEventParser extracts event timestamp correctly for on-time events (#646)."""
    parser = OrderEventParser()
    now_iso = datetime.now(timezone.utc).isoformat()
    raw_payload = json.dumps({
        "eventType": "ORDER_CREATED",
        "orderId": "ord-1001",
        "eventTime": now_iso,
        "payload": {
            "userId": "usr-1",
            "store_id": "str-1",
            "totalCents": 2500,
            "zoneId": "zone-east",
        },
    }).encode("utf-8")

    results = list(parser.process(("key1", raw_payload)))
    assert len(results) == 1
    item = results[0]
    assert item.value["order_id"] == "ord-1001"
    assert item.value["total_cents"] == 2500
    assert abs(item.timestamp - time.time()) < 5


def test_order_event_parser_late_event_routing_to_dlq():
    """Verify OrderEventParser routes late events (> 1 hour) to dead-letter side output (#647)."""
    parser = OrderEventParser()
    old_time_iso = "2020-01-01T00:00:00Z"
    raw_payload = json.dumps({
        "eventType": "ORDER_PLACED",
        "orderId": "ord-late-999",
        "eventTime": old_time_iso,
        "payload": {
            "userId": "usr-old",
            "store_id": "str-1",
            "totalCents": 1200,
            "zoneId": "zone-west",
        },
    }).encode("utf-8")

    results = list(parser.process(("key_late", raw_payload)))
    assert len(results) == 1
    tagged = results[0]
    assert tagged.tag == OrderEventParser.LATE_EVENTS
    assert tagged.value["order_id"] == "ord-late-999"
    assert tagged.value["lateness_seconds"] > 3600


def test_cart_session_aggregator_conversion_and_abandonment():
    """Verify cart session aggregator correctly marks converted vs abandoned sessions (#650)."""
    agg = CartSessionAggregator()

    # Case 1: Active cart with checkout completed
    acc1 = agg.create_accumulator()
    acc1 = agg.add_input(acc1, {"event_type": "ITEM_ADDED", "cart_value_cents": 1500, "item_count": 2, "sku_ids": ["s1"]})
    acc1 = agg.add_input(acc1, {"event_type": "CHECKOUT_COMPLETED", "cart_value_cents": 1500, "item_count": 2, "sku_ids": ["s1"]})
    out1 = agg.extract_output(acc1)
    assert out1["converted"] is True
    assert out1["abandoned"] is False

    # Case 2: Cart with order placed
    acc2 = agg.create_accumulator()
    acc2 = agg.add_input(acc2, {"event_type": "ITEM_ADDED", "cart_value_cents": 3000, "item_count": 4, "sku_ids": ["s2"]})
    acc2 = agg.add_input(acc2, {"event_type": "ORDER_PLACED", "cart_value_cents": 3000, "item_count": 4, "sku_ids": ["s2"]})
    out2 = agg.extract_output(acc2)
    assert out2["converted"] is True
    assert out2["abandoned"] is False

    # Case 3: Cart abandoned with no completion
    acc3 = agg.create_accumulator()
    acc3 = agg.add_input(acc3, {"event_type": "ITEM_ADDED", "cart_value_cents": 2000, "item_count": 1, "sku_ids": ["s3"]})
    out3 = agg.extract_output(acc3)
    assert out3["converted"] is False
    assert out3["abandoned"] is True


def test_payment_insert_failure_formatting():
    """Verify FormatBigQueryInsertFailureFn formats failed BigQuery inserts for dead-lettering (#652)."""
    fn = FormatBigQueryInsertFailureFn()
    failed_row = ("analytics.payment_metrics", {"payment_id": "pay-99", "amount": "invalid_type"})
    results = list(fn.process(failed_row))

    assert len(results) == 1
    dlq_record = results[0]
    assert dlq_record["destination"] == "analytics.payment_metrics"
    assert "pay-99" in dlq_record["payload"]
    assert "Schema mismatch" in dlq_record["error_message"]


def test_inventory_velocity_aggregator():
    """Verify InventoryVelocityAggregator calculates incremental units sold (#654)."""
    agg = InventoryVelocityAggregator()
    acc = agg.create_accumulator()

    # Sales are negative changes (-3, -2)
    acc = agg.add_input(acc, {"quantity_change": -3})
    acc = agg.add_input(acc, {"quantity_change": -2})
    # Restock is positive (+10)
    acc = agg.add_input(acc, {"quantity_change": 10})

    out = agg.extract_output(acc)
    assert out["units_sold"] == 5
    assert out["units_restocked"] == 10
    assert out["event_count"] == 3


def test_rider_location_spatial_preaggregation_and_summary():
    """Verify spatial cell indexing and micro-batch pre-aggregation to prevent OOM (#656)."""
    # 1. Spatial cell index
    cell = compute_spatial_cell(12.9716, 77.5946)
    assert cell == "12.972_77.595"
    assert compute_spatial_cell(None, None) == "UNKNOWN_CELL"

    # 2. Spatial micro-batch aggregation
    pre_agg = SpatialPreAggregateFn()
    acc = pre_agg.create_accumulator()
    acc = pre_agg.add_input(acc, {"rider_id": "r1", "status": "ON_DELIVERY", "speed_kmh": 25.0})
    acc = pre_agg.add_input(acc, {"rider_id": "r2", "status": "IDLE", "speed_kmh": 0.0})
    acc = pre_agg.add_input(acc, {"rider_id": "r1", "status": "ON_DELIVERY", "speed_kmh": 27.0})

    out = pre_agg.extract_output(acc)
    assert out["active_count"] == 1
    assert out["idle_count"] == 1
    assert out["rider_count"] == 2
    assert out["ping_count"] == 3

    # 3. Zone utilization summary
    zone_agg = ZoneUtilizationAggregator()
    z_acc = zone_agg.create_accumulator()
    z_acc = zone_agg.add_input(z_acc, out)
    z_out = zone_agg.extract_output(z_acc)
    assert z_out["active_rider_count"] == 1
    assert z_out["idle_rider_count"] == 1
    assert z_out["total_pings"] == 3


def test_order_payment_join_correlation():
    """Verify CorrelateOrderPaymentFn joins orders and payments on order_id (#663)."""
    join_fn = CorrelateOrderPaymentFn()
    grouped_element = (
        "ord-555",
        {
            "orders": [
                {
                    "user_id": "usr-42",
                    "store_id": "str-9",
                    "zone_id": "zone-south",
                    "total_cents": 4500,
                    "event_type": "ORDER_PLACED",
                    "placed_at": "2026-10-18T10:00:00Z",
                }
            ],
            "payments": [
                {
                    "payment_id": "pay-777",
                    "method": "UPI",
                    "status": "SUCCESS",
                    "amount_cents": 4500,
                    "latency_ms": 120,
                }
            ],
        },
    )

    results = list(join_fn.process(grouped_element))
    assert len(results) == 1
    unified = results[0]
    assert unified["order_id"] == "ord-555"
    assert unified["user_id"] == "usr-42"
    assert unified["payment_method"] == "UPI"
    assert unified["payment_status"] == "SUCCESS"
    assert unified["order_total_cents"] == 4500
    assert unified["payment_latency_ms"] == 120
