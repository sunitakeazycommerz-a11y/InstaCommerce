from __future__ import annotations

import json
import logging
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from data_platform_jobs import bigquery as bq_mod
from data_platform_jobs.config import load_settings
from data_platform_jobs.hooks.scheduler import export_schedules, list_schedules
from data_platform_jobs.job_registry import JOB_DEFINITIONS, build_context, get_job, list_jobs


@pytest.mark.parametrize(
    "valid_id",
    ["instacommerce-dev", "analytics", "instacommerce_prod.marts.mart_daily_revenue"],
)
def test_validate_bq_identifier_accepts_valid_identifiers(valid_id: str) -> None:
    assert bq_mod.validate_bq_identifier(valid_id) == valid_id


@pytest.mark.parametrize(
    "bad_id",
    ["", "proj..dataset", "proj`; DROP TABLE x--", "bad space", "123/456"],
)
def test_validate_bq_identifier_rejects_invalid_identifiers(bad_id: str) -> None:
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        bq_mod.validate_bq_identifier(bad_id, "project_id")


def test_get_client_and_run_queries() -> None:
    logger = logging.getLogger("test_bq")
    assert bq_mod.get_client("", logger) is None

    with patch.object(bq_mod, "bigquery", None):
        assert bq_mod.get_client("instacommerce-dev", logger) is None

    fake_bq = SimpleNamespace(Client=MagicMock(return_value="bq-client"))
    with patch.object(bq_mod, "bigquery", fake_bq):
        assert bq_mod.get_client("instacommerce-dev", logger) == "bq-client"
        fake_bq.Client.assert_called_once_with(project="instacommerce-dev")

    mock_client = MagicMock()
    mock_job = MagicMock(job_id="job-1")
    mock_client.query.return_value = mock_job

    bq_mod.run_queries(mock_client, ["SELECT 1"], logger, dry_run=True)
    mock_client.query.assert_not_called()

    bq_mod.run_queries(None, ["SELECT 1"], logger, dry_run=False)

    bq_mod.run_queries(mock_client, ["SELECT 1", "SELECT 2"], logger, dry_run=False)
    assert mock_client.query.call_count == 2
    assert mock_job.result.call_count == 2


def test_job_registry_and_scheduler_export() -> None:
    jobs = list_jobs()
    assert len(jobs) == len(JOB_DEFINITIONS)
    names = {j.name for j in jobs}
    assert {"staging_etl", "marts_etl", "feature_refresh", "kafka_to_bigquery_batch", "data_quality"} <= names

    assert get_job("feature_refresh") is not None
    assert get_job("nonexistent_job") is None

    settings = load_settings()
    ctx = build_context(settings=settings, dry_run=True)
    assert ctx.settings == settings
    assert ctx.dry_run is True
    assert len(ctx.run_id) == 32

    schedules = list_schedules()
    assert len(schedules) == len(jobs)
    parsed = json.loads(export_schedules("json"))
    assert len(parsed) == len(jobs)
    assert "staging_etl" in export_schedules("text")

    with pytest.raises(ValueError, match="Unsupported format"):
        export_schedules("yaml")
