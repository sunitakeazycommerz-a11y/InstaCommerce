"""Unit tests for Data Platform Apache Beam streaming pipelines.

Tests event time watermarking, late event dead-lettering, cart session abandonment,
payment insert failure formatting, inventory discarding accumulation,
rider location spatial pre-aggregation, window timestamps, snake_case envelopes,
and cross-boundary streaming order-payment joins.
"""
from datetime import datetime, timezone
import json
import os
import sys
import time
import types
from typing import Any
from unittest.mock import MagicMock


def _stub_module(name: str, **attrs: Any) -> Any:
    module = types.ModuleType(name)
    for attr_name, attr_value in attrs.items():
        setattr(module, attr_name, attr_value)
    return module


class _MockBeamTimestamp:
    def __init__(self, seconds: float) -> None:
        self._seconds = seconds

    def to_utc_datetime(self) -> datetime:
        return datetime.fromtimestamp(self._seconds, tz=timezone.utc)


class _MockIntervalWindow:
    def __init__(self, start: float, end: float) -> None:
        self.start = _MockBeamTimestamp(start)
        self.end = _MockBeamTimestamp(end)


class _MockDoFn:
    WindowParam = object()
    TimestampParam = object()
    KeyParam = object()

    @staticmethod
    def StateParam(spec: Any) -> Any:
        return spec

    @staticmethod
    def TimerParam(spec: Any) -> Any:
        return spec


class _MockTaggedOutput:
    def __init__(self, tag: str, value: Any) -> None:
        self.tag = tag
        self.value = value


class _MockTimestampedValue:
    def __init__(self, value: Any, timestamp: float) -> None:
        self.value = value
        self.timestamp = timestamp


class _MockAccumulationMode:
    ACCUMULATING = "ACCUMULATING"
    DISCARDING = "DISCARDING"


if "apache_beam" not in sys.modules:
    mock_window = _stub_module(
        "apache_beam.transforms.window",
        TimestampedValue=_MockTimestampedValue,
        Duration=lambda seconds=0: seconds,
        FixedWindows=MagicMock(),
        SlidingWindows=MagicMock(),
        Sessions=MagicMock(),
        GlobalWindows=MagicMock(),
        IntervalWindow=_MockIntervalWindow,
    )
    mock_trigger = _stub_module(
        "apache_beam.transforms.trigger",
        AfterWatermark=MagicMock(),
        AfterProcessingTime=MagicMock(),
        AfterCount=MagicMock(),
        AccumulationMode=_MockAccumulationMode,
    )
    mock_userstate = _stub_module(
        "apache_beam.transforms.userstate",
        ReadModifyWriteStateSpec=lambda name, coder=None: (name, coder),
        TimerSpec=lambda name, time_domain=None: (name, time_domain),
        on_timer=lambda spec: (lambda fn: fn),
    )
    mock_timeutil = _stub_module(
        "apache_beam.transforms.timeutil",
        TimeDomain=types.SimpleNamespace(WATERMARK="WATERMARK", REAL_TIME="REAL_TIME"),
    )
    mock_transforms = _stub_module(
        "apache_beam.transforms",
        window=mock_window,
        trigger=mock_trigger,
        userstate=mock_userstate,
        timeutil=mock_timeutil,
    )
    mock_coders = _stub_module(
        "apache_beam.coders",
        PickleCoder=MagicMock(),
        BooleanCoder=MagicMock(),
        StrUtf8Coder=MagicMock(),
    )
    mock_pipeline_options = _stub_module(
        "apache_beam.options.pipeline_options",
        PipelineOptions=MagicMock(),
        StandardOptions=MagicMock(),
    )
    mock_options = _stub_module("apache_beam.options", pipeline_options=mock_pipeline_options)
    mock_kafka = _stub_module("apache_beam.io.kafka", ReadFromKafka=MagicMock())
    mock_bigquery = _stub_module("apache_beam.io.gcp.bigquery", WriteToBigQuery=MagicMock())
    mock_gcp = _stub_module("apache_beam.io.gcp", bigquery=mock_bigquery)
    mock_io = _stub_module(
        "apache_beam.io",
        kafka=mock_kafka,
        gcp=mock_gcp,
        WriteToText=MagicMock(),
    )
    mock_pvalue = _stub_module("apache_beam.pvalue", TaggedOutput=_MockTaggedOutput)
    mock_beam = _stub_module(
        "apache_beam",
        DoFn=_MockDoFn,
        CombineFn=object,
        pvalue=mock_pvalue,
        transforms=mock_transforms,
        coders=mock_coders,
        options=mock_options,
        io=mock_io,
        Map=MagicMock(),
        Filter=MagicMock(),
        ParDo=MagicMock(),
        CombinePerKey=MagicMock(),
        Values=MagicMock(),
        CoGroupByKey=MagicMock(),
        Flatten=MagicMock(),
        WindowInto=MagicMock(),
        Pipeline=MagicMock(),
    )

    sys.modules["apache_beam"] = mock_beam
    sys.modules["apache_beam.pvalue"] = mock_pvalue
    sys.modules["apache_beam.coders"] = mock_coders
    sys.modules["apache_beam.options"] = mock_options
    sys.modules["apache_beam.options.pipeline_options"] = mock_pipeline_options
    sys.modules["apache_beam.io"] = mock_io
    sys.modules["apache_beam.io.kafka"] = mock_kafka
    sys.modules["apache_beam.io.gcp"] = mock_gcp
    sys.modules["apache_beam.io.gcp.bigquery"] = mock_bigquery
    sys.modules["apache_beam.transforms"] = mock_transforms
    sys.modules["apache_beam.transforms.window"] = mock_window
    sys.modules["apache_beam.transforms.trigger"] = mock_trigger
    sys.modules["apache_beam.transforms.userstate"] = mock_userstate
    sys.modules["apache_beam.transforms.timeutil"] = mock_timeutil

streaming_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if streaming_dir not in sys.path:
    sys.path.insert(0, streaming_dir)

from pipelines.order_events_pipeline import (  # noqa: E402
    OrderEventParser,
    OrderVolumeAggregator,
    SLAComplianceAggregator,
    format_sla_row,
    format_volume_row,
)
from pipelines.cart_events_pipeline import (  # noqa: E402
    CartSessionAggregator,
    format_abandonment_row,
)
from pipelines.payment_events_pipeline import (  # noqa: E402
    PaymentSuccessRateAggregator,
    FormatBigQueryInsertFailureFn,
    format_metrics_row,
)
from pipelines.inventory_events_pipeline import (  # noqa: E402
    InventoryVelocityAggregator,
    format_velocity_row,
)
from pipelines.rider_location_pipeline import (  # noqa: E402
    compute_spatial_cell,
    SpatialPreAggregateFn,
    ZoneUtilizationAggregator,
    format_utilization_row,
)
from pipelines.order_payment_join_pipeline import (  # noqa: E402
    CorrelateOrderPaymentFn,
    ParseOrderFn,
    ParsePaymentFn,
    StatefulOrderPaymentJoinFn,
)


class _InMemoryState:
    def __init__(self) -> None:
        self._value: Any = None

    def read(self) -> Any:
        return self._value

    def write(self, value: Any) -> None:
        self._value = value

    def clear(self) -> None:
        self._value = None


class _InMemoryTimer:
    def __init__(self) -> None:
        self.fire_at: float | None = None

    def set(self, timestamp: float) -> None:
        self.fire_at = timestamp

    def clear(self) -> None:
        self.fire_at = None


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
    assert item.value["store_id"] == "str-1"
    assert item.value["total_cents"] == 2500
    assert abs(item.timestamp - time.time()) < 5


def test_order_event_parser_supports_snake_case_envelope_and_zero_total():
    """Verify OrderEventParser supports canonical snake_case EventEnvelope fields."""
    parser = OrderEventParser()
    now_iso = datetime.now(timezone.utc).isoformat()
    raw_payload = json.dumps({
        "event_type": "ORDER_PLACED",
        "aggregate_id": "ord-snake-1",
        "event_time": now_iso,
        "payload": {
            "user_id": "usr-snake",
            "store_id": "str-snake",
            "total_amount_cents": 0,
            "zone_id": "zone-north",
            "placed_at": now_iso,
        },
    }).encode("utf-8")

    results = list(parser.process(("key-snake", raw_payload)))
    assert len(results) == 1
    item = results[0]
    assert item.value["order_id"] == "ord-snake-1"
    assert item.value["event_type"] == "ORDER_PLACED"
    assert item.value["user_id"] == "usr-snake"
    assert item.value["store_id"] == "str-snake"
    assert item.value["total_cents"] == 0
    assert item.value["zone_id"] == "zone-north"
    assert item.value["event_time"] == now_iso


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


def test_format_rows_use_event_time_window_end_timestamp():
    """Verify all windowed format helpers use window.end.to_utc_datetime().isoformat()."""
    end_dt = datetime(2026, 1, 15, 10, 5, 0, tzinfo=timezone.utc)
    window = _MockIntervalWindow(start=end_dt.timestamp() - 60, end=end_dt.timestamp())
    expected_ts = end_dt.isoformat()

    assert format_volume_row(("store-1", {"count": 4, "gmv_cents": 12000}), window=window)["window_timestamp"] == expected_ts
    assert format_sla_row(("zone-1", {"total": 10, "within_sla": 9, "compliance_rate": 0.9}), window=window)["window_timestamp"] == expected_ts
    assert format_abandonment_row(
        (
            "zone-1",
            {
                "total_sessions": 5,
                "abandoned_sessions": 1,
                "abandonment_rate": 0.2,
                "abandoned_value_cents": 500,
                "converted_value_cents": 4000,
            },
        ),
        window=window,
    )["window_timestamp"] == expected_ts
    assert format_velocity_row(
        (("sku-1", "store-1"), {"units_sold": 3, "units_restocked": 0, "event_count": 2}),
        window=window,
    )["window_timestamp"] == expected_ts
    assert format_utilization_row(
        (
            "zone-1",
            {
                "active_rider_count": 3,
                "idle_rider_count": 1,
                "avg_speed_kmh": 22.5,
                "total_pings": 30,
            },
        ),
        window=window,
    )["window_timestamp"] == expected_ts
    assert format_metrics_row(
        (
            "UPI",
            {
                "total": 10,
                "success": 9,
                "failed": 1,
                "success_rate": 0.9,
                "latency_p50_ms": 100,
                "latency_p95_ms": 200,
                "latency_p99_ms": 300,
            },
        ),
        window=window,
    )["window_timestamp"] == expected_ts


def test_cart_session_aggregator_conversion_and_abandonment():
    """Verify cart session aggregator correctly marks converted vs abandoned sessions (#650)."""
    agg = CartSessionAggregator()

    acc1 = agg.create_accumulator()
    acc1 = agg.add_input(acc1, {"event_type": "ITEM_ADDED", "cart_value_cents": 1500, "item_count": 2, "sku_ids": ["s1"]})
    acc1 = agg.add_input(acc1, {"event_type": "CHECKOUT_COMPLETED", "cart_value_cents": 1500, "item_count": 2, "sku_ids": ["s1"]})
    out1 = agg.extract_output(acc1)
    assert out1["converted"] is True
    assert out1["abandoned"] is False

    acc2 = agg.create_accumulator()
    acc2 = agg.add_input(acc2, {"event_type": "ITEM_ADDED", "cart_value_cents": 3000, "item_count": 4, "sku_ids": ["s2"]})
    acc2 = agg.add_input(acc2, {"event_type": "ORDER_PLACED", "cart_value_cents": 3000, "item_count": 4, "sku_ids": ["s2"]})
    out2 = agg.extract_output(acc2)
    assert out2["converted"] is True
    assert out2["abandoned"] is False

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

    acc = agg.add_input(acc, {"quantity_change": -3})
    acc = agg.add_input(acc, {"quantity_change": -2})
    acc = agg.add_input(acc, {"quantity_change": 10})

    out = agg.extract_output(acc)
    assert out["units_sold"] == 5
    assert out["units_restocked"] == 10
    assert out["event_count"] == 3


def test_rider_location_spatial_preaggregation_and_summary():
    """Verify spatial cell indexing and micro-batch pre-aggregation to prevent OOM (#656)."""
    cell = compute_spatial_cell(12.9716, 77.5946)
    assert cell == "12.972_77.595"
    assert compute_spatial_cell(None, None) == "UNKNOWN_CELL"

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


def test_stateful_order_payment_join_across_epoch_boundary():
    """Verify stateful join matches orders at 09:58 and payments at 10:02 across 10:00 boundary."""
    join_fn = StatefulOrderPaymentJoinFn(join_window_seconds=600)

    order_raw = json.dumps({
        "event_type": "ORDER_PLACED",
        "aggregate_id": "ord-boundary-1",
        "event_time": "2026-10-18T09:58:00Z",
        "payload": {
            "user_id": "usr-1",
            "store_id": "str-2",
            "zone_id": "zone-a",
            "total_amount_cents": 4500,
            "placed_at": "2026-10-18T09:58:00Z",
        },
    }).encode("utf-8")
    payment_raw = json.dumps({
        "event_type": "PAYMENT_CAPTURED",
        "aggregate_id": "pay-boundary-1",
        "event_time": "2026-10-18T10:02:00Z",
        "payload": {
            "order_id": "ord-boundary-1",
            "payment_id": "pay-boundary-1",
            "method": "UPI",
            "status": "SUCCESS",
            "amount_cents": 4500,
            "latency_ms": 95,
        },
    }).encode("utf-8")

    parsed_order = list(ParseOrderFn().process(("k1", order_raw)))[0]
    parsed_payment = list(ParsePaymentFn().process(("k2", payment_raw)))[0]

    order_state = _InMemoryState()
    payment_state = _InMemoryState()
    emitted_state = _InMemoryState()
    expiry_timer = _InMemoryTimer()

    emitted: list[dict[str, Any]] = []
    emitted.extend(
        join_fn.process(
            (parsed_order.value[0], ("order", parsed_order.value[1])),
            timestamp=parsed_order.timestamp,
            order_state=order_state,
            payment_state=payment_state,
            emitted_state=emitted_state,
            expiry_timer=expiry_timer,
        )
    )
    assert emitted == []
    assert expiry_timer.fire_at is not None

    emitted.extend(
        join_fn.process(
            (parsed_payment.value[0], ("payment", parsed_payment.value[1])),
            timestamp=parsed_payment.timestamp,
            order_state=order_state,
            payment_state=payment_state,
            emitted_state=emitted_state,
            expiry_timer=expiry_timer,
        )
    )
    emitted.extend(
        join_fn.process(
            (parsed_payment.value[0], ("payment", parsed_payment.value[1])),
            timestamp=parsed_payment.timestamp + 1,
            order_state=order_state,
            payment_state=payment_state,
            emitted_state=emitted_state,
            expiry_timer=expiry_timer,
        )
    )
    emitted.extend(
        join_fn.on_expiry(
            key="ord-boundary-1",
            order_state=order_state,
            payment_state=payment_state,
            emitted_state=emitted_state,
        )
    )

    assert len(emitted) == 1, "duplicate arrival or watermark expiry must not emit twice"
    row = emitted[0]
    assert row["order_id"] == "ord-boundary-1"
    assert row["order_total_cents"] == 4500
    assert row["payment_id"] == "pay-boundary-1"
    assert row["payment_status"] == "SUCCESS"
    assert order_state.read() is None
    assert payment_state.read() is None
    assert emitted_state.read() is None


def test_stateful_order_payment_join_emits_unmatched_on_expiry():
    """Verify unmatched order emits when watermark expires and cleans up state."""
    join_fn = StatefulOrderPaymentJoinFn(join_window_seconds=600)

    order_state = _InMemoryState()
    payment_state = _InMemoryState()
    emitted_state = _InMemoryState()
    expiry_timer = _InMemoryTimer()

    ts = datetime(2026, 10, 18, 9, 58, 0, tzinfo=timezone.utc).timestamp()
    out1 = list(
        join_fn.process(
            (
                "ord-unmatched",
                ("order", {"user_id": "u1", "store_id": "s1", "total_cents": 1999, "event_type": "ORDER_PLACED"}),
            ),
            timestamp=ts,
            order_state=order_state,
            payment_state=payment_state,
            emitted_state=emitted_state,
            expiry_timer=expiry_timer,
        )
    )
    assert out1 == []
    assert expiry_timer.fire_at == ts + 600

    out2 = list(
        join_fn.on_expiry(
            key="ord-unmatched",
            order_state=order_state,
            payment_state=payment_state,
            emitted_state=emitted_state,
        )
    )
    assert len(out2) == 1
    assert out2[0]["order_id"] == "ord-unmatched"
    assert out2[0]["order_total_cents"] == 1999
    assert out2[0]["payment_status"] == "UNKNOWN"
    assert order_state.read() is None
