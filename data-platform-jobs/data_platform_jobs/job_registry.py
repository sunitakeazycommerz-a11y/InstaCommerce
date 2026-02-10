from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable, Dict, List, Optional
from uuid import uuid4

from data_platform_jobs.config import JobSettings, load_settings
from data_platform_jobs.jobs import etl, features, kafka_to_bigquery


@dataclass(frozen=True)
class JobSchedule:
    cron: str
    timezone: str = "UTC"


@dataclass(frozen=True)
class JobDefinition:
    name: str
    description: str
    schedule: JobSchedule
    handler: Callable[["JobContext"], None]


@dataclass
class JobContext:
    settings: JobSettings
    run_id: str
    started_at: datetime
    logger: logging.Logger
    dry_run: bool = False


JOB_DEFINITIONS: List[JobDefinition] = [
    JobDefinition(
        name="staging_etl",
        description="Refresh BigQuery staging tables from raw datasets.",
        schedule=JobSchedule(cron="0 */2 * * *"),
        handler=etl.run_staging_etl,
    ),
    JobDefinition(
        name="marts_etl",
        description="Refresh BigQuery marts for analytics consumption.",
        schedule=JobSchedule(cron="0 2 * * *"),
        handler=etl.run_marts_etl,
    ),
    JobDefinition(
        name="feature_refresh",
        description="Compute offline ML features in BigQuery.",
        schedule=JobSchedule(cron="0 */4 * * *"),
        handler=features.run_feature_refresh,
    ),
    JobDefinition(
        name="kafka_to_bigquery_batch",
        description="Batch-load Kafka events into BigQuery raw tables.",
        schedule=JobSchedule(cron="*/15 * * * *"),
        handler=kafka_to_bigquery.run_batch_loader,
    ),
    JobDefinition(
        name="data_quality",
        description="Run post-load data quality checks.",
        schedule=JobSchedule(cron="0 */2 * * *"),
        handler=etl.run_data_quality_checks,
    ),
]


def list_jobs() -> List[JobDefinition]:
    return list(JOB_DEFINITIONS)


def get_job(name: str) -> Optional[JobDefinition]:
    for job in JOB_DEFINITIONS:
        if job.name == name:
            return job
    return None


def build_context(settings: Optional[JobSettings] = None, dry_run: bool = False) -> JobContext:
    resolved_settings = settings or load_settings()
    run_id = uuid4().hex
    started_at = datetime.now(timezone.utc)
    logger = logging.getLogger("data_platform_jobs")
    return JobContext(settings=resolved_settings, run_id=run_id, started_at=started_at, logger=logger, dry_run=dry_run)
