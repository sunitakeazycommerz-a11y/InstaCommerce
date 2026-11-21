from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json
import time
from typing import TYPE_CHECKING, Any, Dict, List, Optional

from data_platform_jobs.bigquery import get_client, validate_bq_identifier

try:
    from kafka import KafkaConsumer  # type: ignore
except ImportError:  # pragma: no cover - optional dependency
    KafkaConsumer = None

if TYPE_CHECKING:
    from data_platform_jobs.job_registry import JobContext

MAX_BQ_INSERT_BATCH_SIZE = 500


class MalformedJsonError(ValueError):
    """Raised when a Kafka message payload cannot be decoded as a JSON object."""

    def __init__(self, message: str, raw_payload: Optional[str] = None) -> None:
        super().__init__(message)
        self.raw_payload = raw_payload


@dataclass
class KafkaBatchStats:
    valid_count: int = 0
    malformed_json_count: int = 0
    invalid_envelope_count: int = 0
    quarantined_count: int = 0


def _has_kafka_offsets(rows: List[Dict[str, Any]]) -> bool:
    for r in rows:
        topic = r.get("topic")
        partition = r.get("partition")
        offset = r.get("offset")
        if not isinstance(topic, str) or not topic.strip():
            return False
        if isinstance(partition, bool) or not isinstance(partition, int) or partition < 0:
            return False
        if isinstance(offset, bool) or not isinstance(offset, int) or offset < 0:
            return False
    return True


def _insert_rows_in_batches(
    client: Any,
    target_table: str,
    rows: List[Dict[str, Any]],
    *,
    row_ids: Optional[List[str]] = None,
    batch_size: int = MAX_BQ_INSERT_BATCH_SIZE,
    logger: Any = None,
) -> None:
    if batch_size <= 0:
        raise ValueError(f"batch_size must be positive, got {batch_size}")
    if row_ids is not None and len(row_ids) != len(rows):
        raise ValueError("row_ids length must match rows length")
    for start in range(0, len(rows), batch_size):
        end = start + batch_size
        batch_rows = rows[start:end]
        if row_ids is not None:
            errors = client.insert_rows_json(target_table, batch_rows, row_ids=row_ids[start:end])
        else:
            errors = client.insert_rows_json(target_table, batch_rows)
        if errors:
            if logger is not None:
                logger.error("BigQuery insert errors on %s (batch %d:%d): %s", target_table, start, end, errors)
            raise RuntimeError(f"BigQuery insert_rows_json failed: {errors}")


def run_batch_loader(context: JobContext) -> None:
    logger = context.logger
    settings = context.settings
    logger.info("Starting Kafka→BigQuery batch loader run_id=%s", context.run_id)
    logger.info(
        "Bootstrap=%s topics=%s window=%smin max_messages=%s",
        settings.kafka_bootstrap,
        ", ".join(settings.kafka_topics),
        settings.batch_window_minutes,
        settings.kafka_max_messages,
    )
    if context.dry_run:
        logger.info("Dry run enabled; skipping Kafka consumption.")
        return
    if KafkaConsumer is None:
        logger.warning("kafka-python not installed; skipping Kafka batch load.")
        return
    project_id = validate_bq_identifier(settings.project_id, "project_id")
    raw_ds = validate_bq_identifier(settings.bq_raw_dataset, "bq_raw_dataset")
    client = get_client(project_id, logger)
    if client is None:
        logger.warning("BigQuery client unavailable; skipping Kafka batch load.")
        return

    consumer = KafkaConsumer(
        *settings.kafka_topics,
        bootstrap_servers=settings.kafka_bootstrap,
        group_id=settings.kafka_consumer_group,
        auto_offset_reset="earliest",
        enable_auto_commit=False,
        consumer_timeout_ms=1000,
    )
    try:
        quarantine_rows: List[Dict[str, Any]] = []
        stats = KafkaBatchStats()
        rows = _collect_rows(
            consumer,
            settings.batch_window_minutes,
            settings.kafka_max_messages,
            logger=logger,
            quarantine_rows=quarantine_rows,
            stats=stats,
        )
        if not rows and not quarantine_rows:
            logger.info("No Kafka messages collected in batch window.")
            return

        if rows:
            target_table = f"{project_id}.{raw_ds}.kafka_events"
            row_ids = (
                [f"{r['topic']}:{r['partition']}:{r['offset']}" for r in rows]
                if _has_kafka_offsets(rows)
                else None
            )
            _insert_rows_in_batches(
                client,
                target_table,
                rows,
                row_ids=row_ids,
                batch_size=MAX_BQ_INSERT_BATCH_SIZE,
                logger=logger,
            )
            logger.info("Inserted %s Kafka events into %s", len(rows), target_table)

        if quarantine_rows:
            quarantine_table = f"{project_id}.{raw_ds}.kafka_events_quarantine"
            q_row_ids = (
                [f"{r['topic']}:{r['partition']}:{r['offset']}" for r in quarantine_rows]
                if _has_kafka_offsets(quarantine_rows)
                else None
            )
            _insert_rows_in_batches(
                client,
                quarantine_table,
                quarantine_rows,
                row_ids=q_row_ids,
                batch_size=MAX_BQ_INSERT_BATCH_SIZE,
                logger=logger,
            )
            logger.warning(
                "Quarantined %s malformed JSON Kafka messages into %s",
                len(quarantine_rows),
                quarantine_table,
            )

        logger.info(
            "Kafka batch summary: valid=%d malformed_json=%d quarantined=%d invalid_envelope=%d",
            len(rows),
            stats.malformed_json_count,
            len(quarantine_rows),
            stats.invalid_envelope_count,
        )
        consumer.commit()
    finally:
        consumer.close()


def validate_kafka_row(row: Dict[str, Any]) -> Dict[str, Any]:
    topic = row.get("topic")
    partition = row.get("partition")
    offset = row.get("offset")
    if not isinstance(topic, str) or not topic.strip():
        raise ValueError("Missing required Kafka topic")
    if isinstance(partition, bool) or not isinstance(partition, int) or partition < 0:
        raise ValueError(f"Invalid Kafka partition: {partition!r}")
    if isinstance(offset, bool) or not isinstance(offset, int) or offset < 0:
        raise ValueError(f"Invalid Kafka offset: {offset!r}")
    return row


def _build_quarantine_row(message: Any, error: str, raw_payload: Optional[str]) -> Dict[str, Any]:
    return {
        "topic": getattr(message, "topic", None),
        "partition": getattr(message, "partition", None),
        "offset": getattr(message, "offset", None),
        "timestamp": getattr(message, "timestamp", None),
        "key": _decode(getattr(message, "key", None)),
        "raw_value": raw_payload,
        "error": error,
        "quarantined_at": datetime.now(timezone.utc).isoformat(),
    }


def _collect_rows(
    consumer: Any,
    window_minutes: int,
    max_messages: int,
    logger: Any = None,
    quarantine_rows: Optional[List[Dict[str, Any]]] = None,
    stats: Optional[KafkaBatchStats] = None,
) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    batch_stats = stats if stats is not None else KafkaBatchStats()
    deadline = time.monotonic() + (window_minutes * 60)

    def _processed_total() -> int:
        return len(rows) + (len(quarantine_rows) if quarantine_rows is not None else 0)

    while time.monotonic() < deadline and _processed_total() < max_messages:
        records = consumer.poll(timeout_ms=1000)
        for _topic_partition, messages in records.items():
            for message in messages:
                try:
                    formatted = _format_message(message)
                    row = validate_kafka_row(formatted)
                except MalformedJsonError as exc:
                    batch_stats.malformed_json_count += 1
                    batch_stats.quarantined_count += 1
                    if quarantine_rows is not None:
                        quarantine_rows.append(
                            _build_quarantine_row(message, str(exc), exc.raw_payload)
                        )
                    if logger is not None:
                        logger.warning(
                            "Quarantining malformed JSON Kafka message (count=%d, topic=%r, partition=%r, offset=%r): %s",
                            batch_stats.malformed_json_count,
                            getattr(message, "topic", None),
                            getattr(message, "partition", None),
                            getattr(message, "offset", None),
                            exc,
                        )
                    if _processed_total() >= max_messages:
                        break
                    continue
                except ValueError as exc:
                    batch_stats.invalid_envelope_count += 1
                    if logger is not None:
                        logger.warning(
                            "Skipping invalid Kafka message (count=%d): %s",
                            batch_stats.invalid_envelope_count,
                            exc,
                        )
                    continue
                rows.append(row)
                batch_stats.valid_count += 1
                if _processed_total() >= max_messages:
                    break
            if _processed_total() >= max_messages:
                break
    return rows


def _format_message(message: Any) -> Dict[str, Any]:
    return {
        "topic": getattr(message, "topic", None),
        "partition": getattr(message, "partition", None),
        "offset": getattr(message, "offset", None),
        "timestamp": getattr(message, "timestamp", None),
        "key": _decode(getattr(message, "key", None)),
        "value": _decode_json(getattr(message, "value", None)),
    }


def _decode(value: Optional[bytes]) -> Optional[str]:
    if value is None:
        return None
    try:
        return value.decode("utf-8")
    except UnicodeDecodeError:
        return value.hex()


def _decode_json(value: Optional[bytes]) -> Optional[Dict[str, Any]]:
    if value is None:
        return None
    try:
        decoded = value.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise MalformedJsonError("Malformed UTF-8 bytes in JSON payload", raw_payload=value.hex()) from exc
    try:
        parsed = json.loads(decoded)
    except json.JSONDecodeError as exc:
        raise MalformedJsonError(f"Malformed JSON payload: {exc.msg}", raw_payload=decoded) from exc
    if not isinstance(parsed, dict):
        raise MalformedJsonError(
            f"Expected JSON object payload, got {type(parsed).__name__}",
            raw_payload=decoded,
        )
    return parsed
