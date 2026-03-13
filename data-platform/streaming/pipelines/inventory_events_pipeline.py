"""Inventory Events Pipeline — inventory velocity and stockout detection.

Consumes inventory change events from Kafka, computes velocity (units sold per
5-minute window per SKU/store) and detects potential stockouts when on-hand
quantity drops below configurable thresholds.

Metrics produced:
  - Inventory velocity per SKU/store (5-min fixed window)
  - Stockout alerts when quantity falls below reorder threshold
  - Category-level depletion rate
"""
import apache_beam as beam
from apache_beam.options.pipeline_options import PipelineOptions, StandardOptions
from apache_beam.io.kafka import ReadFromKafka
from apache_beam.io.gcp.bigquery import WriteToBigQuery
from apache_beam.transforms.window import FixedWindows, TimestampedValue, Duration
from apache_beam.transforms.trigger import AfterWatermark, AfterProcessingTime, AccumulationMode, AfterCount
import json
import logging
import time
from datetime import datetime
from dateutil.parser import isoparse

logger = logging.getLogger(__name__)

STOCKOUT_THRESHOLD = 5  # Alert when quantity drops below this


class InventoryPipelineOptions(PipelineOptions):
    """Custom pipeline options for the inventory events pipeline."""

    @classmethod
    def _add_argparse_args(cls, parser):
        parser.add_argument(
            "--kafka_bootstrap",
            default="kafka-bootstrap:9092",
            help="Kafka bootstrap server address",
        )
        parser.add_argument(
            "--kafka_topic",
            default="inventory.events",
            help="Kafka topic to consume inventory events from",
        )
        parser.add_argument(
            "--output_table_velocity",
            default="analytics.inventory_velocity",
            help="BigQuery table for inventory velocity metrics",
        )
        parser.add_argument(
            "--output_table_stockout",
            default="analytics.stockout_alerts",
            help="BigQuery table for stockout alerts",
        )
        parser.add_argument(
            "--stockout_threshold",
            type=int,
            default=STOCKOUT_THRESHOLD,
            help="Quantity threshold below which a stockout alert is raised",
        )


class InventoryEventParser(beam.DoFn):
    """Parse inventory event JSON from Kafka."""

    DEAD_LETTER = "dead_letter"

    def process(self, element):
        _, value = element
        try:
            event = json.loads(value.decode("utf-8"))
            payload = event.get("payload", {})
            parsed = {
                "sku_id": payload.get("skuId"),
                "store_id": payload.get("storeId"),
                "event_type": event.get("eventType"),
                "quantity_change": payload.get("quantityChange", 0),
                "quantity_on_hand": payload.get("quantityOnHand"),
                "category": payload.get("category", "UNKNOWN"),
                "event_time": event.get("eventTime"),
            }
            if not parsed["sku_id"] or not parsed["store_id"]:
                raise ValueError("Missing skuId or storeId")
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
            logger.warning("Failed to parse inventory event: %s", e)
            yield beam.pvalue.TaggedOutput(
                self.DEAD_LETTER,
                {"raw": value.decode("utf-8", errors="replace"), "error": str(e)},
            )


class InventoryVelocityAggregator(beam.CombineFn):
    """Aggregate inventory velocity (units consumed) per SKU/store."""

    def create_accumulator(self):
        return {"units_sold": 0, "units_restocked": 0, "event_count": 0}

    def add_input(self, acc, element):
        change = element.get("quantity_change", 0)
        if change < 0:
            acc["units_sold"] += abs(change)
        elif change > 0:
            acc["units_restocked"] += change
        acc["event_count"] += 1
        return acc

    def merge_accumulators(self, accumulators):
        merged = {"units_sold": 0, "units_restocked": 0, "event_count": 0}
        for acc in accumulators:
            merged["units_sold"] += acc["units_sold"]
            merged["units_restocked"] += acc["units_restocked"]
            merged["event_count"] += acc["event_count"]
        return merged

    def extract_output(self, acc):
        return acc


class StockoutDetector(beam.DoFn):
    """Emit stockout alerts when on-hand quantity falls below threshold."""

    def __init__(self, threshold=STOCKOUT_THRESHOLD):
        self._threshold = threshold

    def process(self, element):
        qty = element.get("quantity_on_hand")
        if qty is not None and qty <= self._threshold:
            yield {
                "sku_id": element["sku_id"],
                "store_id": element["store_id"],
                "quantity_on_hand": qty,
                "category": element["category"],
                "alert_time": datetime.utcnow().isoformat(),
                "severity": "CRITICAL" if qty == 0 else "WARNING",
            }


def format_velocity_row(element):
    """Format velocity data for BigQuery."""
    key, metrics = element
    sku_id, store_id = key
    return {
        "sku_id": sku_id,
        "store_id": store_id,
        "units_sold": metrics["units_sold"],
        "units_restocked": metrics["units_restocked"],
        "event_count": metrics["event_count"],
        "window_timestamp": datetime.utcnow().isoformat(),
    }


VELOCITY_TABLE_SCHEMA = {
    "fields": [
        {"name": "sku_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "store_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "units_sold", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "units_restocked", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "event_count", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "window_timestamp", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}

STOCKOUT_TABLE_SCHEMA = {
    "fields": [
        {"name": "sku_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "store_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "quantity_on_hand", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "category", "type": "STRING", "mode": "NULLABLE"},
        {"name": "alert_time", "type": "TIMESTAMP", "mode": "REQUIRED"},
        {"name": "severity", "type": "STRING", "mode": "REQUIRED"},
    ]
}


def build_pipeline(options):
    """Construct and return the inventory events streaming pipeline."""
    pipeline_options = options.view_as(InventoryPipelineOptions)

    with beam.Pipeline(options=options) as p:
        parsed = (
            p
            | "ReadKafka"
            >> ReadFromKafka(
                consumer_config={
                    "bootstrap.servers": pipeline_options.kafka_bootstrap,
                    "group.id": "inventory-events-pipeline",
                    "auto.offset.reset": "latest",
                },
                topics=[pipeline_options.kafka_topic],
                key_deserializer="org.apache.kafka.common.serialization.StringDeserializer",
                value_deserializer="org.apache.kafka.common.serialization.ByteArrayDeserializer",
            )
            | "ParseEvents"
            >> beam.ParDo(InventoryEventParser()).with_outputs(
                InventoryEventParser.DEAD_LETTER, main="parsed"
            )
        )

        events = parsed.parsed

        # 5-minute inventory velocity per SKU/store
        (
            events
            | "Window5Min" >> beam.WindowInto(
                FixedWindows(300),
                trigger=AfterWatermark(
                    early=AfterProcessingTime(30),
                    late=AfterCount(1),
                ),
                allowed_lateness=Duration(seconds=600),
                accumulation_mode=AccumulationMode.ACCUMULATING,
            )
            | "KeyBySkuStore"
            >> beam.Map(lambda e: ((e["sku_id"], e["store_id"]), e))
            | "AggregateVelocity"
            >> beam.CombinePerKey(InventoryVelocityAggregator())
            | "FormatVelocity" >> beam.Map(format_velocity_row)
            | "WriteVelocity"
            >> WriteToBigQuery(
                table=pipeline_options.output_table_velocity,
                schema=VELOCITY_TABLE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
            )
        )

        # Stockout detection (per-event, no windowing needed)
        (
            events
            | "DetectStockout"
            >> beam.ParDo(
                StockoutDetector(
                    threshold=pipeline_options.stockout_threshold
                )
            )
            | "WriteStockoutAlerts"
            >> WriteToBigQuery(
                table=pipeline_options.output_table_stockout,
                schema=STOCKOUT_TABLE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
            )
        )

        # Dead-letter sink
        (
            parsed[InventoryEventParser.DEAD_LETTER]
            | "FormatDeadLetter" >> beam.Map(json.dumps)
            | "WriteDeadLetter"
            >> beam.io.WriteToText(
                "gs://instacommerce-dataflow/dead-letter/inventory-events",
                file_name_suffix=".json",
            )
        )


def run():
    """Entry point for the inventory events pipeline."""
    logging.basicConfig(level=logging.INFO)
    options = PipelineOptions()
    options.view_as(StandardOptions).streaming = True
    build_pipeline(options)


if __name__ == "__main__":
    run()
