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
from apache_beam.transforms.window import FixedWindows
import json
import logging
from datetime import datetime

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
            yield parsed
        except Exception as e:
            logger.warning("Failed to parse rider location event: %s", e)
            yield beam.pvalue.TaggedOutput(
                self.DEAD_LETTER,
                {"raw": value.decode("utf-8", errors="replace"), "error": str(e)},
            )


class RiderUtilizationAggregator(beam.CombineFn):
    """Aggregate rider utilization metrics per zone."""

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
        # A rider active in any accumulator is active overall
        merged["idle_riders"] -= merged["active_riders"]
        return merged

    def extract_output(self, acc):
        avg_speed = (
            acc["total_speed_kmh"] / acc["ping_count"] if acc["ping_count"] > 0 else 0.0
        )
        return {
            "active_rider_count": len(acc["active_riders"]),
            "idle_rider_count": len(acc["idle_riders"]),
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

        # 1-minute rider utilization per zone
        (
            events
            | "Window1Min" >> beam.WindowInto(FixedWindows(60))
            | "KeyByZone" >> beam.Map(lambda e: (e["zone_id"], e))
            | "AggregatePerZone"
            >> beam.CombinePerKey(RiderUtilizationAggregator())
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
