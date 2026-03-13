"""Cart Events Pipeline — cart abandonment rate (15-min session windows).

Consumes cart lifecycle events from Kafka, groups them into user sessions
using 15-minute session windows, and computes cart abandonment rates.
A cart is considered abandoned if no checkout event follows the last
cart-update within the session window.

Metrics produced:
  - Cart abandonment rate per zone (15-min session window)
  - Average cart value for abandoned vs. converted carts
  - Top abandoned SKUs
"""
import apache_beam as beam
from apache_beam.options.pipeline_options import PipelineOptions, StandardOptions
from apache_beam.io.kafka import ReadFromKafka
from apache_beam.io.gcp.bigquery import WriteToBigQuery
from apache_beam.transforms.window import Sessions, TimestampedValue, Duration
from apache_beam.transforms.trigger import AfterWatermark, AfterProcessingTime, AccumulationMode, AfterCount
import json
import logging
import time
from datetime import datetime
from dateutil.parser import isoparse

logger = logging.getLogger(__name__)

SESSION_GAP_SECONDS = 900  # 15 minutes


class CartPipelineOptions(PipelineOptions):
    """Custom pipeline options for the cart events pipeline."""

    @classmethod
    def _add_argparse_args(cls, parser):
        parser.add_argument(
            "--kafka_bootstrap",
            default="kafka-bootstrap:9092",
            help="Kafka bootstrap server address",
        )
        parser.add_argument(
            "--kafka_topic",
            default="cart.events",
            help="Kafka topic to consume cart events from",
        )
        parser.add_argument(
            "--output_table",
            default="analytics.cart_abandonment",
            help="BigQuery table for cart abandonment metrics",
        )
        parser.add_argument(
            "--session_gap_seconds",
            type=int,
            default=SESSION_GAP_SECONDS,
            help="Session window gap duration in seconds",
        )


class CartEventParser(beam.DoFn):
    """Parse cart event JSON from Kafka."""

    DEAD_LETTER = "dead_letter"

    def process(self, element):
        _, value = element
        try:
            event = json.loads(value.decode("utf-8"))
            payload = event.get("payload", {})
            parsed = {
                "cart_id": payload.get("cartId"),
                "user_id": payload.get("userId"),
                "event_type": event.get("eventType"),
                "zone_id": payload.get("zoneId", ""),
                "cart_value_cents": payload.get("cartValueCents", 0),
                "item_count": payload.get("itemCount", 0),
                "sku_ids": payload.get("skuIds", []),
                "event_time": event.get("eventTime"),
            }
            if not parsed["cart_id"] or not parsed["user_id"]:
                raise ValueError("Missing cartId or userId")
            event_time_str = event.get("eventTime")
            if event_time_str:
                try:
                    event_ts = isoparse(event_time_str).timestamp()
                except (ValueError, TypeError):
                    event_ts = time.time()
            else:
                event_ts = time.time()
            yield TimestampedValue(parsed, event_ts)
        except Exception as e:
            logger.warning("Failed to parse cart event: %s", e)
            yield beam.pvalue.TaggedOutput(
                self.DEAD_LETTER,
                {"raw": value.decode("utf-8", errors="replace"), "error": str(e)},
            )


class CartSessionAggregator(beam.CombineFn):
    """Aggregate cart events within a session to determine abandonment.

    A session is considered converted if it contains a CHECKOUT_COMPLETED event.
    Otherwise, it is abandoned.
    """

    def create_accumulator(self):
        return {
            "event_types": [],
            "max_cart_value_cents": 0,
            "total_items": 0,
            "sku_ids": [],
        }

    def add_input(self, acc, element):
        acc["event_types"].append(element.get("event_type", ""))
        cart_val = element.get("cart_value_cents", 0)
        if cart_val > acc["max_cart_value_cents"]:
            acc["max_cart_value_cents"] = cart_val
        acc["total_items"] = max(acc["total_items"], element.get("item_count", 0))
        acc["sku_ids"].extend(element.get("sku_ids", []))
        return acc

    def merge_accumulators(self, accumulators):
        merged = {
            "event_types": [],
            "max_cart_value_cents": 0,
            "total_items": 0,
            "sku_ids": [],
        }
        for acc in accumulators:
            merged["event_types"].extend(acc["event_types"])
            merged["max_cart_value_cents"] = max(
                merged["max_cart_value_cents"], acc["max_cart_value_cents"]
            )
            merged["total_items"] = max(merged["total_items"], acc["total_items"])
            merged["sku_ids"].extend(acc["sku_ids"])
        return merged

    def extract_output(self, acc):
        converted = "CHECKOUT_COMPLETED" in acc["event_types"]
        return {
            "converted": converted,
            "abandoned": not converted,
            "max_cart_value_cents": acc["max_cart_value_cents"],
            "total_items": acc["total_items"],
            "unique_skus": list(set(acc["sku_ids"])),
            "event_count": len(acc["event_types"]),
        }


class AbandonmentRateAggregator(beam.CombineFn):
    """Compute abandonment rate across sessions per zone."""

    def create_accumulator(self):
        return {
            "total_sessions": 0,
            "abandoned_sessions": 0,
            "abandoned_value_cents": 0,
            "converted_value_cents": 0,
        }

    def add_input(self, acc, element):
        acc["total_sessions"] += 1
        if element["abandoned"]:
            acc["abandoned_sessions"] += 1
            acc["abandoned_value_cents"] += element["max_cart_value_cents"]
        else:
            acc["converted_value_cents"] += element["max_cart_value_cents"]
        return acc

    def merge_accumulators(self, accumulators):
        merged = {
            "total_sessions": 0,
            "abandoned_sessions": 0,
            "abandoned_value_cents": 0,
            "converted_value_cents": 0,
        }
        for acc in accumulators:
            merged["total_sessions"] += acc["total_sessions"]
            merged["abandoned_sessions"] += acc["abandoned_sessions"]
            merged["abandoned_value_cents"] += acc["abandoned_value_cents"]
            merged["converted_value_cents"] += acc["converted_value_cents"]
        return merged

    def extract_output(self, acc):
        rate = (
            acc["abandoned_sessions"] / acc["total_sessions"]
            if acc["total_sessions"] > 0
            else 0.0
        )
        return {
            "total_sessions": acc["total_sessions"],
            "abandoned_sessions": acc["abandoned_sessions"],
            "abandonment_rate": round(rate, 4),
            "abandoned_value_cents": acc["abandoned_value_cents"],
            "converted_value_cents": acc["converted_value_cents"],
        }


def format_abandonment_row(element):
    """Format abandonment metrics for BigQuery."""
    zone_id, metrics = element
    return {
        "zone_id": zone_id,
        "total_sessions": metrics["total_sessions"],
        "abandoned_sessions": metrics["abandoned_sessions"],
        "abandonment_rate": metrics["abandonment_rate"],
        "abandoned_value_cents": metrics["abandoned_value_cents"],
        "converted_value_cents": metrics["converted_value_cents"],
        "window_timestamp": datetime.utcnow().isoformat(),
    }


ABANDONMENT_TABLE_SCHEMA = {
    "fields": [
        {"name": "zone_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "total_sessions", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "abandoned_sessions", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "abandonment_rate", "type": "FLOAT", "mode": "REQUIRED"},
        {"name": "abandoned_value_cents", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "converted_value_cents", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "window_timestamp", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}


def build_pipeline(options):
    """Construct and return the cart events streaming pipeline."""
    pipeline_options = options.view_as(CartPipelineOptions)

    with beam.Pipeline(options=options) as p:
        parsed = (
            p
            | "ReadKafka"
            >> ReadFromKafka(
                consumer_config={
                    "bootstrap.servers": pipeline_options.kafka_bootstrap,
                    "group.id": "cart-events-pipeline",
                    "auto.offset.reset": "latest",
                },
                topics=[pipeline_options.kafka_topic],
                key_deserializer="org.apache.kafka.common.serialization.StringDeserializer",
                value_deserializer="org.apache.kafka.common.serialization.ByteArrayDeserializer",
            )
            | "ParseEvents"
            >> beam.ParDo(CartEventParser()).with_outputs(
                CartEventParser.DEAD_LETTER, main="parsed"
            )
        )

        events = parsed.parsed

        # 15-minute session window per user → abandonment detection
        sessions = (
            events
            | "KeyByUser" >> beam.Map(lambda e: (e["user_id"], e))
            | "SessionWindow"
            >> beam.WindowInto(
                Sessions(pipeline_options.session_gap_seconds),
                trigger=AfterWatermark(
                    early=AfterProcessingTime(30),
                    late=AfterCount(1),
                ),
                allowed_lateness=Duration(seconds=900),
                accumulation_mode=AccumulationMode.ACCUMULATING,
            )
            | "AggregateSession" >> beam.CombinePerKey(CartSessionAggregator())
            | "ExtractSessionValues" >> beam.Values()
        )

        # Aggregate abandonment rate per zone (re-window into fixed 1-min for output)
        (
            events
            | "KeyByUserForZone" >> beam.Map(lambda e: (e["user_id"], e))
            | "SessionWindowForZone"
            >> beam.WindowInto(
                Sessions(pipeline_options.session_gap_seconds),
                trigger=AfterWatermark(
                    early=AfterProcessingTime(30),
                    late=AfterCount(1),
                ),
                allowed_lateness=Duration(seconds=900),
                accumulation_mode=AccumulationMode.ACCUMULATING,
            )
            | "AggregateSessionForZone"
            >> beam.CombinePerKey(CartSessionAggregator())
            | "AttachZone"
            >> beam.Map(
                lambda kv: (kv[1].get("zone_id", "UNKNOWN"), kv[1])
            )
            | "RewindowFixed" >> beam.WindowInto(
                beam.transforms.window.FixedWindows(60),
                trigger=AfterWatermark(
                    early=AfterProcessingTime(30),
                    late=AfterCount(1),
                ),
                allowed_lateness=Duration(seconds=900),
                accumulation_mode=AccumulationMode.ACCUMULATING,
            )
            | "AbandonmentPerZone"
            >> beam.CombinePerKey(AbandonmentRateAggregator())
            | "FormatAbandonment" >> beam.Map(format_abandonment_row)
            | "WriteAbandonment"
            >> WriteToBigQuery(
                table=pipeline_options.output_table,
                schema=ABANDONMENT_TABLE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
            )
        )

        # Dead-letter sink
        (
            parsed[CartEventParser.DEAD_LETTER]
            | "FormatDeadLetter" >> beam.Map(json.dumps)
            | "WriteDeadLetter"
            >> beam.io.WriteToText(
                "gs://instacommerce-dataflow/dead-letter/cart-events",
                file_name_suffix=".json",
            )
        )


def run():
    """Entry point for the cart events pipeline."""
    logging.basicConfig(level=logging.INFO)
    options = PipelineOptions()
    options.view_as(StandardOptions).streaming = True
    build_pipeline(options)


if __name__ == "__main__":
    run()
