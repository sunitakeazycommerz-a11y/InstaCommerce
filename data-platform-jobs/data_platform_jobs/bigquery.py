from __future__ import annotations

import logging
from typing import Iterable, Optional

try:
    from google.cloud import bigquery  # type: ignore
except ImportError:  # pragma: no cover - optional dependency
    bigquery = None


def get_client(project_id: str, logger: logging.Logger):
    if not project_id:
        logger.warning("BigQuery project_id not configured; skipping client creation.")
        return None
    if bigquery is None:
        logger.warning("google-cloud-bigquery not installed; skipping query execution.")
        return None
    return bigquery.Client(project=project_id)


def run_queries(
    client,
    queries: Iterable[str],
    logger: logging.Logger,
    dry_run: bool,
) -> None:
    for query in queries:
        _run_query(client, query, logger, dry_run)


def _run_query(client, query: str, logger: logging.Logger, dry_run: bool) -> None:
    if dry_run:
        logger.info("Dry run query: %s", query)
        return
    if client is None:
        logger.warning("BigQuery client unavailable; skipping query.")
        return
    job = client.query(query)
    job.result()
    logger.info("BigQuery job completed: %s", job.job_id)
