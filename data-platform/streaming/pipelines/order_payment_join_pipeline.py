# pyright: reportMissingImports=false, reportUnusedExpression=false
"""Order-Payment Streaming Join Pipeline — Apache Beam Kafka → BigQuery

Performs a real-time stateful streaming join between orders.events and payments.events
on order_id with a configurable join skew window (no fixed epoch-boundary misses).
Writes correlated records to BigQuery analytics.realtime_order_payments.
"""
import apache_beam as beam
from apache_beam.coders import PickleCoder
from apache_beam.options.pipeline_options import PipelineOptions, StandardOptions
from apache_beam.io.kafka import ReadFromKafka
from apache_beam.io.gcp.bigquery import WriteToBigQuery
from apache_beam.transforms.timeutil import TimeDomain
from apache_beam.transforms.userstate import ReadModifyWriteStateSpec, TimerSpec, on_timer
from apache_beam.transforms.window import GlobalWindows, TimestampedValue
import json
import logging
from datetime import datetime, timezone

from pipelines.common import (
    first_present as _first_present,
    parse_event_ts as _parse_event_ts,
)

logger = logging.getLogger(__name__)


class OrderPaymentJoinPipelineOptions(PipelineOptions):
    """Pipeline options for the order-payment streaming join pipeline."""

    @classmethod
    def _add_argparse_args(cls, parser):
        parser.add_argument(
            "--kafka_bootstrap",
            default="kafka-bootstrap:9092",
            help="Kafka bootstrap server address",
        )
        parser.add_argument(
            "--orders_topic",
            default="orders.events",
            help="Kafka topic for order events",
        )
        parser.add_argument(
            "--payments_topic",
            default="payments.events",
            help="Kafka topic for payment events",
        )
        parser.add_argument(
            "--output_table",
            default="analytics.realtime_order_payments",
            help="BigQuery destination table for joined order-payment records",
        )
        parser.add_argument(
            "--join_window_seconds",
            type=int,
            default=600,
            help="Allowed join skew window duration in seconds (default 10 min)",
        )


class ParseOrderFn(beam.DoFn):
    """Parse Kafka order message envelope and extract keyed tuple."""

    DEAD_LETTER = "order_dead_letter"

    def process(self, element):
        _, value = element
        try:
            event = json.loads(value.decode("utf-8"))
            payload = event.get("payload") or {}
            order_id = _first_present(
                event, "orderId", "order_id", "aggregateId", "aggregate_id"
            ) or _first_present(payload, "orderId", "order_id")
            if not order_id:
                raise ValueError("Missing orderId in order event")
            event_time_str = _first_present(
                event, "eventTime", "event_time", "timestamp", "occurredAt", "occurred_at", "event_timestamp"
            )
            parsed = {
                "order_id": order_id,
                "user_id": _first_present(payload, "userId", "user_id")
                or _first_present(event, "userId", "user_id"),
                "store_id": _first_present(payload, "storeId", "store_id")
                or _first_present(event, "storeId", "store_id"),
                "zone_id": _first_present(payload, "zoneId", "zone_id", default=""),
                "total_cents": _first_present(
                    payload, "totalCents", "total_amount_cents", "total_cents", default=0
                ),
                "event_type": _first_present(event, "eventType", "event_type"),
                "placed_at": _first_present(payload, "placedAt", "placed_at"),
                "event_time": event_time_str,
            }
            event_ts = _parse_event_ts(event_time_str)
            yield TimestampedValue((order_id, parsed), event_ts)
        except Exception as e:
            logger.warning("Failed to parse order for join: %s", e)
            yield beam.pvalue.TaggedOutput(
                self.DEAD_LETTER,
                {"raw": value.decode("utf-8", errors="replace"), "error": str(e)},
            )


class ParsePaymentFn(beam.DoFn):
    """Parse Kafka payment message envelope and extract keyed tuple."""

    DEAD_LETTER = "payment_dead_letter"

    def process(self, element):
        _, value = element
        try:
            event = json.loads(value.decode("utf-8"))
            payload = event.get("payload") or {}
            order_id = _first_present(payload, "orderId", "order_id") or _first_present(
                event, "orderId", "order_id"
            )
            payment_id = _first_present(payload, "paymentId", "payment_id") or _first_present(
                event, "paymentId", "payment_id", "aggregateId", "aggregate_id"
            )
            if not order_id:
                raise ValueError("Missing orderId in payment event")
            event_time_str = _first_present(
                event, "eventTime", "event_time", "timestamp", "occurredAt", "occurred_at", "event_timestamp"
            )
            parsed = {
                "order_id": order_id,
                "payment_id": payment_id,
                "method": _first_present(payload, "method", "paymentMethod", "payment_method", default="UNKNOWN"),
                "status": _first_present(payload, "status", default="UNKNOWN"),
                "amount_cents": _first_present(payload, "amountCents", "amount_cents", default=0),
                "latency_ms": _first_present(payload, "latencyMs", "latency_ms"),
                "event_type": _first_present(event, "eventType", "event_type"),
                "event_time": event_time_str,
            }
            event_ts = _parse_event_ts(event_time_str)
            yield TimestampedValue((order_id, parsed), event_ts)
        except Exception as e:
            logger.warning("Failed to parse payment for join: %s", e)
            yield beam.pvalue.TaggedOutput(
                self.DEAD_LETTER,
                {"raw": value.decode("utf-8", errors="replace"), "error": str(e)},
            )


def _build_joined_row(order_id: str, order_data: dict, payment_data: dict) -> dict:
    return {
        "order_id": order_id,
        "user_id": order_data.get("user_id"),
        "store_id": order_data.get("store_id"),
        "zone_id": order_data.get("zone_id"),
        "order_total_cents": order_data.get("total_cents", 0),
        "order_status": order_data.get("event_type", "UNKNOWN"),
        "placed_at": order_data.get("placed_at"),
        "payment_id": payment_data.get("payment_id"),
        "payment_method": payment_data.get("method", "UNKNOWN"),
        "payment_status": payment_data.get("status", "UNKNOWN"),
        "payment_amount_cents": payment_data.get("amount_cents", 0),
        "payment_latency_ms": payment_data.get("latency_ms"),
        "joined_at": datetime.now(timezone.utc).isoformat(),
    }


class StatefulOrderPaymentJoinFn(beam.DoFn):
    """Stateful streaming join on order_id using event-time watermark expiry."""

    ORDER_STATE = ReadModifyWriteStateSpec("order", PickleCoder())
    PAYMENT_STATE = ReadModifyWriteStateSpec("payment", PickleCoder())
    EMITTED_STATE = ReadModifyWriteStateSpec("emitted", PickleCoder())
    EXPIRY_TIMER = TimerSpec("expiry", TimeDomain.WATERMARK)

    def __init__(self, join_window_seconds: int = 600):
        self.join_window_seconds = join_window_seconds

    def process(
        self,
        element,
        timestamp=beam.DoFn.TimestampParam,
        order_state=beam.DoFn.StateParam(ORDER_STATE),
        payment_state=beam.DoFn.StateParam(PAYMENT_STATE),
        emitted_state=beam.DoFn.StateParam(EMITTED_STATE),
        expiry_timer=beam.DoFn.TimerParam(EXPIRY_TIMER),
    ):
        order_id, tagged = element
        side, payload = tagged

        if side == "order":
            order_state.write(payload)
        elif side == "payment":
            payment_state.write(payload)

        if emitted_state.read():
            return

        order_data = order_state.read()
        payment_data = payment_state.read()
        expiry_timer.set(float(timestamp) + self.join_window_seconds)

        if order_data is not None and payment_data is not None:
            emitted_state.write(True)
            yield _build_joined_row(order_id, order_data, payment_data)

    @on_timer(EXPIRY_TIMER)
    def on_expiry(
        self,
        key=beam.DoFn.KeyParam,
        order_state=beam.DoFn.StateParam(ORDER_STATE),
        payment_state=beam.DoFn.StateParam(PAYMENT_STATE),
        emitted_state=beam.DoFn.StateParam(EMITTED_STATE),
    ):
        try:
            if not emitted_state.read():
                order_data = order_state.read()
                payment_data = payment_state.read()
                if order_data is not None or payment_data is not None:
                    yield _build_joined_row(key, order_data or {}, payment_data or {})
        finally:
            order_state.clear()
            payment_state.clear()
            emitted_state.clear()


ORDER_PAYMENTS_TABLE_SCHEMA = {
    "fields": [
        {"name": "order_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "user_id", "type": "STRING", "mode": "NULLABLE"},
        {"name": "store_id", "type": "STRING", "mode": "NULLABLE"},
        {"name": "zone_id", "type": "STRING", "mode": "NULLABLE"},
        {"name": "order_total_cents", "type": "INTEGER", "mode": "NULLABLE"},
        {"name": "order_status", "type": "STRING", "mode": "NULLABLE"},
        {"name": "placed_at", "type": "TIMESTAMP", "mode": "NULLABLE"},
        {"name": "payment_id", "type": "STRING", "mode": "NULLABLE"},
        {"name": "payment_method", "type": "STRING", "mode": "NULLABLE"},
        {"name": "payment_status", "type": "STRING", "mode": "NULLABLE"},
        {"name": "payment_amount_cents", "type": "INTEGER", "mode": "NULLABLE"},
        {"name": "payment_latency_ms", "type": "INTEGER", "mode": "NULLABLE"},
        {"name": "joined_at", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}


def build_pipeline(options):
    """Construct and return the order-payment streaming join pipeline."""
    pipeline_options = options.view_as(OrderPaymentJoinPipelineOptions)
    window_duration = pipeline_options.join_window_seconds

    with beam.Pipeline(options=options) as p:
        # Read and parse orders
        parsed_orders = (
            p
            | "ReadOrdersKafka"
            >> ReadFromKafka(
                consumer_config={
                    "bootstrap.servers": pipeline_options.kafka_bootstrap,
                    "group.id": "order-payment-join-orders",
                    "auto.offset.reset": "latest",
                },
                topics=[pipeline_options.orders_topic],
                key_deserializer="org.apache.kafka.common.serialization.StringDeserializer",
                value_deserializer="org.apache.kafka.common.serialization.ByteArrayDeserializer",
            )
            | "ParseOrders"
            >> beam.ParDo(ParseOrderFn()).with_outputs(
                ParseOrderFn.DEAD_LETTER, main="orders"
            )
        )

        tagged_orders = parsed_orders.orders | "TagOrders" >> beam.Map(
            lambda kv: (kv[0], ("order", kv[1]))
        )

        # Read and parse payments
        parsed_payments = (
            p
            | "ReadPaymentsKafka"
            >> ReadFromKafka(
                consumer_config={
                    "bootstrap.servers": pipeline_options.kafka_bootstrap,
                    "group.id": "order-payment-join-payments",
                    "auto.offset.reset": "latest",
                },
                topics=[pipeline_options.payments_topic],
                key_deserializer="org.apache.kafka.common.serialization.StringDeserializer",
                value_deserializer="org.apache.kafka.common.serialization.ByteArrayDeserializer",
            )
            | "ParsePayments"
            >> beam.ParDo(ParsePaymentFn()).with_outputs(
                ParsePaymentFn.DEAD_LETTER, main="payments"
            )
        )

        tagged_payments = parsed_payments.payments | "TagPayments" >> beam.Map(
            lambda kv: (kv[0], ("payment", kv[1]))
        )

        (
            (tagged_orders, tagged_payments)
            | "MergeOrderPaymentStreams" >> beam.Flatten()
            | "WindowGlobal" >> beam.WindowInto(GlobalWindows())
            | "CorrelateStreams"
            >> beam.ParDo(
                StatefulOrderPaymentJoinFn(join_window_seconds=window_duration)
            )
            | "WriteRealtimeOrderPayments"
            >> WriteToBigQuery(
                table=pipeline_options.output_table,
                schema=ORDER_PAYMENTS_TABLE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
            )
        )


def run():
    """Entry point for the order-payment join pipeline."""
    logging.basicConfig(level=logging.INFO)
    options = PipelineOptions()
    options.view_as(StandardOptions).streaming = True
    build_pipeline(options)


if __name__ == "__main__":
    run()
