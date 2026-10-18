# pyright: reportMissingImports=false, reportUnusedExpression=false
"""Order-Payment Streaming Join Pipeline — Apache Beam Kafka → BigQuery

Performs a real-time streaming join between orders.events and payments.events
on order_id using CoGroupByKey with a 10-minute allowed skew window.
Writes correlated records to BigQuery analytics.realtime_order_payments.
"""
import apache_beam as beam
from apache_beam.options.pipeline_options import PipelineOptions, StandardOptions
from apache_beam.io.kafka import ReadFromKafka
from apache_beam.io.gcp.bigquery import WriteToBigQuery
from apache_beam.transforms.window import FixedWindows, TimestampedValue, Duration
from apache_beam.transforms.trigger import AfterWatermark, AccumulationMode
import json
import logging
import time
from datetime import datetime
from dateutil.parser import isoparse

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
            payload = event.get("payload", {})
            order_id = event.get("orderId") or payload.get("orderId") or event.get("aggregateId")
            if not order_id:
                raise ValueError("Missing orderId in order event")
            parsed = {
                "order_id": order_id,
                "user_id": payload.get("userId"),
                "store_id": payload.get("storeId"),
                "zone_id": payload.get("zoneId", ""),
                "total_cents": payload.get("totalCents", 0),
                "event_type": event.get("eventType"),
                "placed_at": payload.get("placedAt"),
                "event_time": event.get("eventTime"),
            }
            event_time_str = event.get("eventTime") or event.get("timestamp") or event.get("occurredAt")
            if event_time_str:
                try:
                    event_ts = isoparse(event_time_str).timestamp()
                except (ValueError, TypeError):
                    event_ts = time.time()
            else:
                event_ts = time.time()
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
            payload = event.get("payload", {})
            order_id = payload.get("orderId") or event.get("orderId")
            payment_id = payload.get("paymentId") or event.get("aggregateId")
            if not order_id:
                raise ValueError("Missing orderId in payment event")
            parsed = {
                "order_id": order_id,
                "payment_id": payment_id,
                "method": payload.get("method", "UNKNOWN"),
                "status": payload.get("status", "UNKNOWN"),
                "amount_cents": payload.get("amountCents", 0),
                "latency_ms": payload.get("latencyMs"),
                "event_type": event.get("eventType"),
                "event_time": event.get("eventTime"),
            }
            event_time_str = event.get("eventTime") or event.get("timestamp") or event.get("occurredAt")
            if event_time_str:
                try:
                    event_ts = isoparse(event_time_str).timestamp()
                except (ValueError, TypeError):
                    event_ts = time.time()
            else:
                event_ts = time.time()
            yield TimestampedValue((order_id, parsed), event_ts)
        except Exception as e:
            logger.warning("Failed to parse payment for join: %s", e)
            yield beam.pvalue.TaggedOutput(
                self.DEAD_LETTER,
                {"raw": value.decode("utf-8", errors="replace"), "error": str(e)},
            )


class CorrelateOrderPaymentFn(beam.DoFn):
    """Correlate order and payment streams from CoGroupByKey on order_id."""

    def process(self, element):
        order_id, grouped = element
        orders = list(grouped.get("orders", []))
        payments = list(grouped.get("payments", []))

        if not orders and not payments:
            return

        order_data = orders[0] if orders else {}
        payment_data = payments[0] if payments else {}

        yield {
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
            "joined_at": datetime.utcnow().isoformat(),
        }


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

        windowed_orders = (
            parsed_orders.orders
            | "WindowOrders"
            >> beam.WindowInto(
                FixedWindows(window_duration),
                trigger=AfterWatermark(),
                allowed_lateness=Duration(seconds=window_duration),
                accumulation_mode=AccumulationMode.DISCARDING,
            )
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

        windowed_payments = (
            parsed_payments.payments
            | "WindowPayments"
            >> beam.WindowInto(
                FixedWindows(window_duration),
                trigger=AfterWatermark(),
                allowed_lateness=Duration(seconds=window_duration),
                accumulation_mode=AccumulationMode.DISCARDING,
            )
        )

        # CoGroupByKey and Join
        (
            {"orders": windowed_orders, "payments": windowed_payments}
            | "CoGroupOrdersPayments" >> beam.CoGroupByKey()
            | "CorrelateStreams" >> beam.ParDo(CorrelateOrderPaymentFn())
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
