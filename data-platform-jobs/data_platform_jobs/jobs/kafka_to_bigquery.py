from __future__ import annotations

import json
import time
from typing import TYPE_CHECKING, Any, Dict, List, Optional

from data_platform_jobs.bigquery import get_client

try:
    from kafka import KafkaConsumer  # type: ignore
except ImportError:  # pragma: no cover - optional dependency
    KafkaConsumer = None

if TYPE_CHECKING:
    from data_platform_jobs.job_registry import JobContext


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
    client = get_client(settings.project_id, logger)
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
        rows = _collect_rows(consumer, settings.batch_window_minutes, settings.kafka_max_messages)
        if not rows:
            logger.info("No Kafka messages collected in batch window.")
            return
        target_table = f"{settings.project_id}.{settings.bq_raw_dataset}.kafka_events"
        errors = client.insert_rows_json(target_table, rows)
        if errors:
            logger.warning("BigQuery insert errors: %s", errors)
        else:
            logger.info("Inserted %s Kafka events into %s", len(rows), target_table)
        consumer.commit()
    finally:
        consumer.close()


def _collect_rows(consumer, window_minutes: int, max_messages: int) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    deadline = time.monotonic() + (window_minutes * 60)
    while time.monotonic() < deadline and len(rows) < max_messages:
        records = consumer.poll(timeout_ms=1000)
        for topic_partition, messages in records.items():
            for message in messages:
                rows.append(_format_message(message))
                if len(rows) >= max_messages:
                    break
            if len(rows) >= max_messages:
                break
    return rows


def _format_message(message: Any) -> Dict[str, Any]:
    return {
        "topic": getattr(message, "topic", None),
        "partition": getattr(message, "partition", None),
        "offset": getattr(message, "offset", None),
        "timestamp": getattr(message, "timestamp", None),
        "key": _decode(message.key),
        "value": _decode_json(message.value),
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
    decoded = _decode(value)
    if decoded is None:
        return None
    try:
        return json.loads(decoded)
    except json.JSONDecodeError:
        return {"raw": decoded}
