# pyright: reportMissingImports=false, reportUnusedExpression=false
"""Order Events Streaming Pipeline — Kafka → BigQuery

Aggregations: orders per minute (per store/zone), GMV, SLA compliance
Window: 1-minute tumbling for counters, 30-minute sliding for SLA
"""
import apache_beam as beam
from apache_beam.options.pipeline_options import PipelineOptions, StandardOptions
from apache_beam.io.kafka import ReadFromKafka
from apache_beam.io.gcp.bigquery import WriteToBigQuery
from apache_beam.transforms.window import FixedWindows, SlidingWindows, TimestampedValue, Duration
from apache_beam.transforms.trigger import AfterWatermark, AfterProcessingTime, AccumulationMode, AfterCount
import json
import logging
import time
from datetime import datetime
from dateutil.parser import isoparse

logger = logging.getLogger(__name__)


class OrderPipelineOptions(PipelineOptions):
    """Custom pipeline options for the order events pipeline."""

    @classmethod
    def _add_argparse_args(cls, parser):
        parser.add_argument(
            "--kafka_bootstrap",
            default="kafka-bootstrap:9092",
            help="Kafka bootstrap server address",
        )
        parser.add_argument(
            "--kafka_topic",
            default="orders.events",
            help="Kafka topic to consume order events from",
        )
        parser.add_argument(
            "--output_table_volume",
            default="analytics.realtime_order_volume",
            help="BigQuery table for order volume metrics",
        )
        parser.add_argument(
            "--output_table_sla",
            default="analytics.sla_compliance",
            help="BigQuery table for SLA compliance metrics",
        )
        parser.add_argument(
            "--output_table_late_events",
            default="analytics_dlq.late_order_events",
            help="BigQuery table for late-arriving order events",
        )


class OrderEventParser(beam.DoFn):
    """Parse order event JSON from Kafka.

    Expects CloudEvents-style envelope with nested payload.
    Dead-letters malformed events and routes late-arriving events beyond watermark.
    """

    DEAD_LETTER = "dead_letter"
    LATE_EVENTS = "late_events"
    ALLOWED_LATENESS_SECONDS = 3600  # 1 hour allowed lateness

    def process(self, element):
        key, value = element
        try:
            event = json.loads(value.decode("utf-8"))
            payload = event.get("payload", {})
            parsed = {
                "event_type": event.get("eventType"),
                "order_id": event.get("orderId") or event.get("aggregateId"),
                "user_id": payload.get("userId"),
                "store_id": payload.get("storeId"),
                "total_cents": payload.get("totalCents", 0),
                "placed_at": payload.get("placedAt"),
                "zone_id": payload.get("zoneId", ""),
                "event_time": event.get("eventTime"),
            }
            if not parsed["order_id"]:
                raise ValueError("Missing order_id in event")
            event_time_str = (
                event.get("eventTime")
                or event.get("timestamp")
                or event.get("occurredAt")
                or event.get("createdAt")
            )
            if event_time_str:
                try:
                    event_ts = isoparse(event_time_str).timestamp()
                except (ValueError, TypeError):
                    event_ts = time.time()
            else:
                event_ts = time.time()

            # Check for late-arriving events beyond allowed lateness window (1 hour)
            current_time = time.time()
            lateness = current_time - event_ts
            if lateness > self.ALLOWED_LATENESS_SECONDS:
                logger.warning(
                    "Late-arriving order event dropped to DLQ: order_id=%s, lateness=%.1fs",
                    parsed["order_id"],
                    lateness,
                )
                yield beam.pvalue.TaggedOutput(
                    self.LATE_EVENTS,
                    {
                        "order_id": parsed["order_id"],
                        "event_type": parsed["event_type"] or "UNKNOWN",
                        "store_id": parsed["store_id"] or "UNKNOWN",
                        "zone_id": parsed["zone_id"] or "UNKNOWN",
                        "total_cents": parsed["total_cents"],
                        "event_time": event_time_str or datetime.utcfromtimestamp(event_ts).isoformat(),
                        "received_at": datetime.utcnow().isoformat(),
                        "lateness_seconds": round(lateness, 2),
                    },
                )
                return

            yield TimestampedValue(parsed, event_ts)
        except Exception as e:
            logger.warning("Failed to parse order event: %s", e)
            yield beam.pvalue.TaggedOutput(
                self.DEAD_LETTER,
                {"raw": value.decode("utf-8", errors="replace"), "error": str(e)},
            )


class OrderVolumeAggregator(beam.CombineFn):
    """Aggregate order counts and GMV per store/zone within a window."""

    def create_accumulator(self):
        return {"count": 0, "gmv_cents": 0}

    def add_input(self, acc, element):
        acc["count"] += 1
        acc["gmv_cents"] += element.get("total_cents", 0)
        return acc

    def merge_accumulators(self, accumulators):
        merged = {"count": 0, "gmv_cents": 0}
        for acc in accumulators:
            merged["count"] += acc["count"]
            merged["gmv_cents"] += acc["gmv_cents"]
        return merged

    def extract_output(self, acc):
        return acc


class SLAComplianceAggregator(beam.CombineFn):
    """Compute SLA compliance rate over a sliding window.

    SLA target: order delivered within 10 minutes of placement (InstaCommerce SLA promise).
    """

    SLA_TARGET_SECONDS = 600  # 10 minutes (600 seconds)

    def create_accumulator(self):
        return {"total": 0, "within_sla": 0}

    def add_input(self, acc, element):
        acc["total"] += 1
        delivery_secs = element.get("delivery_seconds")
        if delivery_secs is not None and delivery_secs <= self.SLA_TARGET_SECONDS:
            acc["within_sla"] += 1
        return acc

    def merge_accumulators(self, accumulators):
        merged = {"total": 0, "within_sla": 0}
        for acc in accumulators:
            merged["total"] += acc["total"]
            merged["within_sla"] += acc["within_sla"]
        return merged

    def extract_output(self, acc):
        rate = acc["within_sla"] / acc["total"] if acc["total"] > 0 else 0.0
        return {
            "total": acc["total"],
            "within_sla": acc["within_sla"],
            "compliance_rate": round(rate, 4),
        }


def format_volume_row(element):
    """Format aggregated volume data for BigQuery insertion."""
    key, value = element
    return {
        "store_id": key,
        "order_count": value["count"],
        "gmv_cents": value["gmv_cents"],
        "window_timestamp": datetime.utcnow().isoformat(),
    }


def format_sla_row(element):
    """Format SLA compliance data for BigQuery insertion."""
    key, value = element
    return {
        "zone_id": key,
        "total_orders": value["total"],
        "within_sla": value["within_sla"],
        "compliance_rate": value["compliance_rate"],
        "window_timestamp": datetime.utcnow().isoformat(),
    }


VOLUME_TABLE_SCHEMA = {
    "fields": [
        {"name": "store_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "order_count", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "gmv_cents", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "window_timestamp", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}

SLA_TABLE_SCHEMA = {
    "fields": [
        {"name": "zone_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "total_orders", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "within_sla", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "compliance_rate", "type": "FLOAT", "mode": "REQUIRED"},
        {"name": "window_timestamp", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}

LATE_EVENT_TABLE_SCHEMA = {
    "fields": [
        {"name": "order_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "event_type", "type": "STRING", "mode": "REQUIRED"},
        {"name": "store_id", "type": "STRING", "mode": "NULLABLE"},
        {"name": "zone_id", "type": "STRING", "mode": "NULLABLE"},
        {"name": "total_cents", "type": "INTEGER", "mode": "NULLABLE"},
        {"name": "event_time", "type": "STRING", "mode": "NULLABLE"},
        {"name": "received_at", "type": "TIMESTAMP", "mode": "REQUIRED"},
        {"name": "lateness_seconds", "type": "FLOAT", "mode": "REQUIRED"},
    ]
}


def build_pipeline(options):
    """Construct and return the order events streaming pipeline."""
    pipeline_options = options.view_as(OrderPipelineOptions)

    with beam.Pipeline(options=options) as p:
        # Read from Kafka
        parsed = (
            p
            | "ReadKafka"
            >> ReadFromKafka(
                consumer_config={
                    "bootstrap.servers": pipeline_options.kafka_bootstrap,
                    "group.id": "order-events-pipeline",
                    "auto.offset.reset": "latest",
                },
                topics=[pipeline_options.kafka_topic],
                key_deserializer="org.apache.kafka.common.serialization.StringDeserializer",
                value_deserializer="org.apache.kafka.common.serialization.ByteArrayDeserializer",
            )
            | "ParseEvents"
            >> beam.ParDo(OrderEventParser()).with_outputs(
                OrderEventParser.DEAD_LETTER,
                OrderEventParser.LATE_EVENTS,
                main="parsed",
            )
        )

        events = parsed.parsed

        # --- 1-minute order volume (fixed window with 1-hour allowed lateness) ---
        (
            events
            | "Window1Min" >> beam.WindowInto(
                FixedWindows(60),
                trigger=AfterWatermark(
                    early=AfterProcessingTime(30),
                    late=AfterCount(1),
                ),
                allowed_lateness=Duration(seconds=3600),
                accumulation_mode=AccumulationMode.ACCUMULATING,
            )
            | "KeyByStore" >> beam.Map(lambda e: (e["store_id"], e))
            | "CountPerStore" >> beam.CombinePerKey(OrderVolumeAggregator())
            | "FormatVolume" >> beam.Map(format_volume_row)
            | "WriteVolume"
            >> WriteToBigQuery(
                table=pipeline_options.output_table_volume,
                schema=VOLUME_TABLE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
                method=WriteToBigQuery.Method.STORAGE_WRITE_API,
                triggering_frequency=2,
            )
        )

        # --- 30-minute SLA compliance (sliding window, 1-min slide with 1-hour allowed lateness) ---
        (
            events
            | "FilterDelivered"
            >> beam.Filter(lambda e: e["event_type"] == "ORDER_DELIVERED")
            | "Window30MinSliding" >> beam.WindowInto(
                SlidingWindows(1800, 60),
                trigger=AfterWatermark(
                    early=AfterProcessingTime(30),
                    late=AfterCount(1),
                ),
                allowed_lateness=Duration(seconds=3600),
                accumulation_mode=AccumulationMode.ACCUMULATING,
            )
            | "KeyByZone" >> beam.Map(lambda e: (e["zone_id"], e))
            | "SLAPerZone" >> beam.CombinePerKey(SLAComplianceAggregator())
            | "FormatSLA" >> beam.Map(format_sla_row)
            | "WriteSLA"
            >> WriteToBigQuery(
                table=pipeline_options.output_table_sla,
                schema=SLA_TABLE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
                method=WriteToBigQuery.Method.STORAGE_WRITE_API,
                triggering_frequency=2,
            )
        )

        # --- Late events dead-letter sink (BigQuery) ---
        (
            parsed[OrderEventParser.LATE_EVENTS]
            | "WriteLateEvents"
            >> WriteToBigQuery(
                table=pipeline_options.output_table_late_events,
                schema=LATE_EVENT_TABLE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
            )
        )

        # --- Dead-letter sink ---
        (
            parsed[OrderEventParser.DEAD_LETTER]
            | "FormatDeadLetter" >> beam.Map(json.dumps)
            | "WriteDeadLetter"
            >> beam.io.WriteToText(
                "gs://instacommerce-dataflow/dead-letter/order-events",
                file_name_suffix=".json",
            )
        )


def run():
    """Entry point for the order events pipeline."""
    logging.basicConfig(level=logging.INFO)
    options = PipelineOptions()
    options.view_as(StandardOptions).streaming = True
    build_pipeline(options)


if __name__ == "__main__":
    run()
