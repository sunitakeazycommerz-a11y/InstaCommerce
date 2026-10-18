# pyright: reportMissingImports=false, reportUnusedExpression=false
"""Rider Location Pipeline — rider utilization per zone.

Consumes rider GPS pings from Kafka, computes per-zone utilization metrics
(active riders, idle riders, average speed) in 1-minute fixed windows,
and writes results to BigQuery.

Metrics produced:
  - Active / idle rider count per zone (1-min fixed window)
  - Average rider speed per zone
  - Zone coverage heatmap data
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


class RiderPipelineOptions(PipelineOptions):
    """Custom pipeline options for the rider location pipeline."""

    @classmethod
    def _add_argparse_args(cls, parser):
        parser.add_argument(
            "--kafka_bootstrap",
            default="kafka-bootstrap:9092",
            help="Kafka bootstrap server address",
        )
        parser.add_argument(
            "--kafka_topic",
            default="rider.location",
            help="Kafka topic to consume rider location pings from",
        )
        parser.add_argument(
            "--output_table",
            default="analytics.rider_utilization",
            help="BigQuery table for rider utilization metrics",
        )


class RiderLocationParser(beam.DoFn):
    """Parse rider location ping JSON from Kafka."""

    DEAD_LETTER = "dead_letter"

    def process(self, element):
        _, value = element
        try:
            event = json.loads(value.decode("utf-8"))
            payload = event.get("payload", {})
            parsed = {
                "rider_id": payload.get("riderId"),
                "zone_id": payload.get("zoneId"),
                "latitude": payload.get("latitude"),
                "longitude": payload.get("longitude"),
                "speed_kmh": payload.get("speedKmh", 0.0),
                "status": payload.get("status", "UNKNOWN"),
                "heading": payload.get("heading"),
                "event_time": event.get("eventTime"),
            }
            if not parsed["rider_id"] or not parsed["zone_id"]:
                raise ValueError("Missing riderId or zoneId")
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
            logger.warning("Failed to parse rider location event: %s", e)
            yield beam.pvalue.TaggedOutput(
                self.DEAD_LETTER,
                {"raw": value.decode("utf-8", errors="replace"), "error": str(e)},
            )


def compute_spatial_cell(lat, lng):
    """Compute spatial cell index (~100m grid) from lat/lng to prevent high-cardinality OOM."""
    if lat is None or lng is None:
        return "UNKNOWN_CELL"
    return f"{round(lat, 3)}_{round(lng, 3)}"


class SpatialPreAggregateFn(beam.CombineFn):
    """Pre-aggregate GPS pings at 10-second micro-batches by spatial cell and zone.

    Emits compact scalar summary structs instead of accumulating millions of raw pings in memory.
    """

    def create_accumulator(self):
        return {
            "active_riders": set(),
            "idle_riders": set(),
            "total_speed_kmh": 0.0,
            "ping_count": 0,
        }

    def add_input(self, acc, element):
        rider_id = element["rider_id"]
        if element.get("status") in ("ON_DELIVERY", "EN_ROUTE"):
            acc["active_riders"].add(rider_id)
        else:
            acc["idle_riders"].add(rider_id)
        acc["total_speed_kmh"] += element.get("speed_kmh", 0.0)
        acc["ping_count"] += 1
        return acc

    def merge_accumulators(self, accumulators):
        merged = {
            "active_riders": set(),
            "idle_riders": set(),
            "total_speed_kmh": 0.0,
            "ping_count": 0,
        }
        for acc in accumulators:
            merged["active_riders"] |= acc["active_riders"]
            merged["idle_riders"] |= acc["idle_riders"]
            merged["total_speed_kmh"] += acc["total_speed_kmh"]
            merged["ping_count"] += acc["ping_count"]
        merged["idle_riders"] -= merged["active_riders"]
        return merged

    def extract_output(self, acc):
        avg_speed = (
            acc["total_speed_kmh"] / acc["ping_count"] if acc["ping_count"] > 0 else 0.0
        )
        return {
            "active_count": len(acc["active_riders"]),
            "idle_count": len(acc["idle_riders"]),
            "rider_count": len(acc["active_riders"]) + len(acc["idle_riders"]),
            "total_speed_kmh": acc["total_speed_kmh"],
            "avg_speed": round(avg_speed, 2),
            "ping_count": acc["ping_count"],
        }


class ZoneUtilizationAggregator(beam.CombineFn):
    """Aggregate pre-aggregated spatial cell summaries into zone metrics without OOM."""

    def create_accumulator(self):
        return {
            "active_riders": 0,
            "idle_riders": 0,
            "total_speed_kmh": 0.0,
            "ping_count": 0,
        }

    def add_input(self, acc, element):
        acc["active_riders"] += element.get("active_count", 0)
        acc["idle_riders"] += element.get("idle_count", 0)
        acc["total_speed_kmh"] += element.get("total_speed_kmh", 0.0)
        acc["ping_count"] += element.get("ping_count", 0)
        return acc

    def merge_accumulators(self, accumulators):
        merged = {
            "active_riders": 0,
            "idle_riders": 0,
            "total_speed_kmh": 0.0,
            "ping_count": 0,
        }
        for acc in accumulators:
            merged["active_riders"] += acc["active_riders"]
            merged["idle_riders"] += acc["idle_riders"]
            merged["total_speed_kmh"] += acc["total_speed_kmh"]
            merged["ping_count"] += acc["ping_count"]
        return merged

    def extract_output(self, acc):
        avg_speed = (
            acc["total_speed_kmh"] / acc["ping_count"] if acc["ping_count"] > 0 else 0.0
        )
        return {
            "active_rider_count": acc["active_riders"],
            "idle_rider_count": acc["idle_riders"],
            "avg_speed_kmh": round(avg_speed, 2),
            "total_pings": acc["ping_count"],
        }


def format_utilization_row(element):
    """Format aggregated utilization data for BigQuery."""
    zone_id, metrics = element
    return {
        "zone_id": zone_id,
        "active_rider_count": metrics["active_rider_count"],
        "idle_rider_count": metrics["idle_rider_count"],
        "avg_speed_kmh": metrics["avg_speed_kmh"],
        "total_pings": metrics["total_pings"],
        "window_timestamp": datetime.utcnow().isoformat(),
    }


UTILIZATION_TABLE_SCHEMA = {
    "fields": [
        {"name": "zone_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "active_rider_count", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "idle_rider_count", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "avg_speed_kmh", "type": "FLOAT", "mode": "REQUIRED"},
        {"name": "total_pings", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "window_timestamp", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}


def build_pipeline(options):
    """Construct and return the rider location streaming pipeline."""
    pipeline_options = options.view_as(RiderPipelineOptions)

    with beam.Pipeline(options=options) as p:
        parsed = (
            p
            | "ReadKafka"
            >> ReadFromKafka(
                consumer_config={
                    "bootstrap.servers": pipeline_options.kafka_bootstrap,
                    "group.id": "rider-location-pipeline",
                    "auto.offset.reset": "latest",
                },
                topics=[pipeline_options.kafka_topic],
                key_deserializer="org.apache.kafka.common.serialization.StringDeserializer",
                value_deserializer="org.apache.kafka.common.serialization.ByteArrayDeserializer",
            )
            | "ParseLocations"
            >> beam.ParDo(RiderLocationParser()).with_outputs(
                RiderLocationParser.DEAD_LETTER, main="parsed"
            )
        )

        events = parsed.parsed

        # Pre-aggregate GPS pings by spatial cell at 10-second fixed intervals
        pre_aggregated = (
            events
            | "Window10SecMicroBatch" >> beam.WindowInto(
                FixedWindows(10),
                trigger=AfterWatermark(),
                accumulation_mode=AccumulationMode.DISCARDING,
            )
            | "KeyByZoneAndCell"
            >> beam.Map(
                lambda e: (
                    (e["zone_id"], compute_spatial_cell(e.get("latitude"), e.get("longitude"))),
                    e,
                )
            )
            | "PreAggregateByCell" >> beam.CombinePerKey(SpatialPreAggregateFn())
            | "FlattenPreAgg"
            >> beam.Map(
                lambda kv: {
                    "zone_id": kv[0][0],
                    "spatial_cell": kv[0][1],
                    "active_count": kv[1]["active_count"],
                    "idle_count": kv[1]["idle_count"],
                    "total_speed_kmh": kv[1]["total_speed_kmh"],
                    "ping_count": kv[1]["ping_count"],
                }
            )
        )

        # 1-minute rider utilization per zone over pre-aggregated summaries
        (
            pre_aggregated
            | "Window1Min" >> beam.WindowInto(
                FixedWindows(60),
                trigger=AfterWatermark(
                    early=AfterProcessingTime(30),
                    late=AfterCount(1),
                ),
                allowed_lateness=Duration(seconds=300),
                accumulation_mode=AccumulationMode.DISCARDING,
            )
            | "KeyByZone" >> beam.Map(lambda e: (e["zone_id"], e))
            | "AggregatePerZone"
            >> beam.CombinePerKey(ZoneUtilizationAggregator())
            | "FormatUtilization" >> beam.Map(format_utilization_row)
            | "WriteUtilization"
            >> WriteToBigQuery(
                table=pipeline_options.output_table,
                schema=UTILIZATION_TABLE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
            )
        )

        # Dead-letter sink
        (
            parsed[RiderLocationParser.DEAD_LETTER]
            | "FormatDeadLetter" >> beam.Map(json.dumps)
            | "WriteDeadLetter"
            >> beam.io.WriteToText(
                "gs://instacommerce-dataflow/dead-letter/rider-location",
                file_name_suffix=".json",
            )
        )


def run():
    """Entry point for the rider location pipeline."""
    logging.basicConfig(level=logging.INFO)
    options = PipelineOptions()
    options.view_as(StandardOptions).streaming = True
    build_pipeline(options)


if __name__ == "__main__":
    run()
