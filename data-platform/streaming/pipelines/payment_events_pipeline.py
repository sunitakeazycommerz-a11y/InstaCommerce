# pyright: reportMissingImports=false, reportUnusedExpression=false
"""Payment Events Pipeline — payment success rate monitoring.

Consumes payment lifecycle events from Kafka, computes per-minute success/failure
rates and latency percentiles, and writes results to BigQuery for real-time
dashboards and alerting.

Metrics produced:
  - Payment success rate (1-min fixed window, per payment method)
  - Payment latency P50 / P95 / P99 (1-min fixed window)
  - Failed payment counts by error code
"""
import apache_beam as beam
from apache_beam.options.pipeline_options import PipelineOptions, StandardOptions
from apache_beam.io.kafka import ReadFromKafka
from apache_beam.io.gcp.bigquery import WriteToBigQuery
from apache_beam.transforms.window import FixedWindows, TimestampedValue, Duration
from apache_beam.transforms.trigger import AfterWatermark, AfterProcessingTime, AccumulationMode, AfterCount
import json
import logging
import statistics
import time
from datetime import datetime
from dateutil.parser import isoparse

logger = logging.getLogger(__name__)


class PaymentPipelineOptions(PipelineOptions):
    """Custom pipeline options for the payment events pipeline."""

    @classmethod
    def _add_argparse_args(cls, parser):
        parser.add_argument(
            "--kafka_bootstrap",
            default="kafka-bootstrap:9092",
            help="Kafka bootstrap server address",
        )
        parser.add_argument(
            "--kafka_topic",
            default="payments.events",
            help="Kafka topic to consume payment events from",
        )
        parser.add_argument(
            "--output_table",
            default="analytics.payment_metrics",
            help="BigQuery table for payment metrics",
        )
        parser.add_argument(
            "--output_table_insert_failures",
            default="analytics_errors.payment_insert_failures",
            help="BigQuery table for failed payment records insert dead-letter queue",
        )


class PaymentEventParser(beam.DoFn):
    """Parse payment event JSON from Kafka."""

    DEAD_LETTER = "dead_letter"

    def process(self, element):
        _, value = element
        try:
            event = json.loads(value.decode("utf-8"))
            payload = event.get("payload", {})
            parsed = {
                "payment_id": payload.get("paymentId"),
                "order_id": payload.get("orderId"),
                "status": payload.get("status"),
                "method": payload.get("method", "UNKNOWN"),
                "amount_cents": payload.get("amountCents", 0),
                "error_code": payload.get("errorCode"),
                "latency_ms": payload.get("latencyMs"),
                "event_time": event.get("eventTime"),
            }
            if not parsed["payment_id"]:
                raise ValueError("Missing paymentId")
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
            logger.warning("Failed to parse payment event: %s", e)
            yield beam.pvalue.TaggedOutput(
                self.DEAD_LETTER,
                {"raw": value.decode("utf-8", errors="replace"), "error": str(e)},
            )


class PaymentSuccessRateAggregator(beam.CombineFn):
    """Compute payment success rate per payment method within a window."""

    def create_accumulator(self):
        return {"total": 0, "success": 0, "failed": 0, "latencies_ms": []}

    def add_input(self, acc, element):
        acc["total"] += 1
        if element.get("status") == "SUCCESS":
            acc["success"] += 1
        elif element.get("status") in ("FAILED", "DECLINED"):
            acc["failed"] += 1
        if element.get("latency_ms") is not None:
            acc["latencies_ms"].append(element["latency_ms"])
        return acc

    def merge_accumulators(self, accumulators):
        merged = {"total": 0, "success": 0, "failed": 0, "latencies_ms": []}
        for acc in accumulators:
            merged["total"] += acc["total"]
            merged["success"] += acc["success"]
            merged["failed"] += acc["failed"]
            merged["latencies_ms"].extend(acc["latencies_ms"])
        return merged

    def extract_output(self, acc):
        latencies = sorted(acc["latencies_ms"]) if acc["latencies_ms"] else [0]
        success_rate = acc["success"] / acc["total"] if acc["total"] > 0 else 0.0
        return {
            "total": acc["total"],
            "success": acc["success"],
            "failed": acc["failed"],
            "success_rate": round(success_rate, 4),
            "latency_p50_ms": int(statistics.median(latencies)),
            "latency_p95_ms": int(latencies[int(len(latencies) * 0.95)]),
            "latency_p99_ms": int(latencies[int(len(latencies) * 0.99)]),
        }


def format_metrics_row(element):
    """Format aggregated payment metrics for BigQuery."""
    method, metrics = element
    return {
        "payment_method": method,
        "total_payments": metrics["total"],
        "successful_payments": metrics["success"],
        "failed_payments": metrics["failed"],
        "success_rate": metrics["success_rate"],
        "latency_p50_ms": metrics["latency_p50_ms"],
        "latency_p95_ms": metrics["latency_p95_ms"],
        "latency_p99_ms": metrics["latency_p99_ms"],
        "window_timestamp": datetime.utcnow().isoformat(),
    }


METRICS_TABLE_SCHEMA = {
    "fields": [
        {"name": "payment_method", "type": "STRING", "mode": "REQUIRED"},
        {"name": "total_payments", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "successful_payments", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "failed_payments", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "success_rate", "type": "FLOAT", "mode": "REQUIRED"},
        {"name": "latency_p50_ms", "type": "INTEGER", "mode": "NULLABLE"},
        {"name": "latency_p95_ms", "type": "INTEGER", "mode": "NULLABLE"},
        {"name": "latency_p99_ms", "type": "INTEGER", "mode": "NULLABLE"},
        {"name": "window_timestamp", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}

INSERT_FAILURE_SCHEMA = {
    "fields": [
        {"name": "destination", "type": "STRING", "mode": "REQUIRED"},
        {"name": "payload", "type": "STRING", "mode": "REQUIRED"},
        {"name": "error_message", "type": "STRING", "mode": "REQUIRED"},
        {"name": "failed_at", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}


class FormatBigQueryInsertFailureFn(beam.DoFn):
    """Format failed BigQuery streaming inserts for dead-letter routing."""

    def process(self, element):
        destination = "analytics.payment_metrics"
        row_content = element
        if isinstance(element, tuple) and len(element) >= 2:
            destination = str(element[0])
            row_content = element[1]

        logger.error(
            "CRITICAL: BigQuery insert failure detected for destination %s: %s",
            destination,
            row_content,
        )
        yield {
            "destination": destination,
            "payload": json.dumps(row_content) if not isinstance(row_content, str) else row_content,
            "error_message": "Schema mismatch or streaming buffer error during insertion",
            "failed_at": datetime.utcnow().isoformat(),
        }


def build_pipeline(options):
    """Construct and return the payment events streaming pipeline."""
    pipeline_options = options.view_as(PaymentPipelineOptions)

    with beam.Pipeline(options=options) as p:
        parsed = (
            p
            | "ReadKafka"
            >> ReadFromKafka(
                consumer_config={
                    "bootstrap.servers": pipeline_options.kafka_bootstrap,
                    "group.id": "payment-events-pipeline",
                    "auto.offset.reset": "latest",
                },
                topics=[pipeline_options.kafka_topic],
                key_deserializer="org.apache.kafka.common.serialization.StringDeserializer",
                value_deserializer="org.apache.kafka.common.serialization.ByteArrayDeserializer",
            )
            | "ParseEvents"
            >> beam.ParDo(PaymentEventParser()).with_outputs(
                PaymentEventParser.DEAD_LETTER, main="parsed"
            )
        )

        events = parsed.parsed

        # 1-minute payment success rate per method
        write_result = (
            events
            | "Window1Min" >> beam.WindowInto(
                FixedWindows(60),
                trigger=AfterWatermark(
                    early=AfterProcessingTime(30),
                    late=AfterCount(1),
                ),
                allowed_lateness=Duration(seconds=300),
                accumulation_mode=AccumulationMode.ACCUMULATING,
            )
            | "KeyByMethod" >> beam.Map(lambda e: (e["method"], e))
            | "AggregatePerMethod"
            >> beam.CombinePerKey(PaymentSuccessRateAggregator())
            | "FormatMetrics" >> beam.Map(format_metrics_row)
            | "WriteMetrics"
            >> WriteToBigQuery(
                table=pipeline_options.output_table,
                schema=METRICS_TABLE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
                insert_retry_policy="NEVER",
            )
        )

        # Route failed streaming inserts to dead-letter table
        (
            write_result.failed_rows
            | "FormatInsertFailures" >> beam.ParDo(FormatBigQueryInsertFailureFn())
            | "WriteInsertFailures"
            >> WriteToBigQuery(
                table=pipeline_options.output_table_insert_failures,
                schema=INSERT_FAILURE_SCHEMA,
                write_disposition="WRITE_APPEND",
                create_disposition="CREATE_IF_NEEDED",
            )
        )

        # Dead-letter sink
        (
            parsed[PaymentEventParser.DEAD_LETTER]
            | "FormatDeadLetter" >> beam.Map(json.dumps)
            | "WriteDeadLetter"
            >> beam.io.WriteToText(
                "gs://instacommerce-dataflow/dead-letter/payment-events",
                file_name_suffix=".json",
            )
        )


def run():
    """Entry point for the payment events pipeline."""
    logging.basicConfig(level=logging.INFO)
    options = PipelineOptions()
    options.view_as(StandardOptions).streaming = True
    build_pipeline(options)


if __name__ == "__main__":
    run()
