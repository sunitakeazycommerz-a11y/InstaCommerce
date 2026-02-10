from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Tuple


def _split_csv(value: str) -> Tuple[str, ...]:
    items = [item.strip() for item in value.split(",") if item.strip()]
    return tuple(items)


@dataclass(frozen=True)
class JobSettings:
    project_id: str
    bq_raw_dataset: str
    bq_staging_dataset: str
    bq_marts_dataset: str
    bq_features_dataset: str
    kafka_bootstrap: str
    kafka_topics: Tuple[str, ...]
    kafka_consumer_group: str
    kafka_max_messages: int
    batch_window_minutes: int
    timezone: str


def load_settings() -> JobSettings:
    project_id = os.getenv("DATA_PLATFORM_PROJECT_ID", "instacommerce-dev")
    raw_dataset = os.getenv("DATA_PLATFORM_BQ_DATASET_RAW", "raw")
    staging_dataset = os.getenv("DATA_PLATFORM_BQ_DATASET_STAGING", "staging")
    marts_dataset = os.getenv("DATA_PLATFORM_BQ_DATASET_MARTS", "marts")
    features_dataset = os.getenv("DATA_PLATFORM_BQ_DATASET_FEATURES", "features")
    kafka_bootstrap = os.getenv("DATA_PLATFORM_KAFKA_BOOTSTRAP", "localhost:9092")
    kafka_topics = _split_csv(os.getenv("DATA_PLATFORM_KAFKA_TOPICS", "order.events,payment.events"))
    kafka_consumer_group = os.getenv("DATA_PLATFORM_KAFKA_CONSUMER_GROUP", "data-platform-batch-loader")
    kafka_max_messages = int(os.getenv("DATA_PLATFORM_KAFKA_MAX_MESSAGES", "5000"))
    batch_window_minutes = int(os.getenv("DATA_PLATFORM_BATCH_WINDOW_MINUTES", "15"))
    timezone = os.getenv("DATA_PLATFORM_TIMEZONE", "UTC")
    return JobSettings(
        project_id=project_id,
        bq_raw_dataset=raw_dataset,
        bq_staging_dataset=staging_dataset,
        bq_marts_dataset=marts_dataset,
        bq_features_dataset=features_dataset,
        kafka_bootstrap=kafka_bootstrap,
        kafka_topics=kafka_topics,
        kafka_consumer_group=kafka_consumer_group,
        kafka_max_messages=kafka_max_messages,
        batch_window_minutes=batch_window_minutes,
        timezone=timezone,
    )
