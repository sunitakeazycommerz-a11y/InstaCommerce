from __future__ import annotations

from typing import List
from unittest.mock import MagicMock, patch

import pytest

from data_platform_jobs.config import JobSettings
from data_platform_jobs.job_registry import build_context
from data_platform_jobs.jobs import etl, features


def _custom_settings(
    *,
    project_id: str = "instacommerce-test",
    bq_marts_dataset: str = "marts",
) -> JobSettings:
    return JobSettings(
        project_id=project_id,
        bq_raw_dataset="raw",
        bq_staging_dataset="staging",
        bq_marts_dataset=bq_marts_dataset,
        bq_features_dataset="features",
        kafka_bootstrap="localhost:9092",
        kafka_topics=("orders.events",),
        kafka_consumer_group="test-group",
        kafka_max_messages=100,
        batch_window_minutes=1,
        timezone="UTC",
    )


def test_run_staging_etl_builds_orders_order_items_and_payments() -> None:
    ctx = build_context(settings=_custom_settings(), dry_run=False)
    captured_queries: List[str] = []

    def _capture(client: object, queries: List[str], logger: object, dry_run: bool) -> None:
        assert dry_run is False
        captured_queries.extend(queries)

    with (
        patch.object(etl, "get_client", return_value=MagicMock()),
        patch.object(etl, "run_queries", side_effect=_capture),
    ):
        etl.run_staging_etl(ctx)

    assert len(captured_queries) == 3
    assert "`instacommerce-test.staging.stg_orders`" in captured_queries[0]
    assert "`instacommerce-test.raw.orders`" in captured_queries[0]
    assert "`instacommerce-test.staging.stg_order_items`" in captured_queries[1]
    assert "`instacommerce-test.raw.order_items`" in captured_queries[1]
    assert "INNER JOIN `instacommerce-test.staging.stg_orders`" in captured_queries[1]
    assert "`instacommerce-test.staging.stg_payments`" in captured_queries[2]


def test_run_marts_etl_and_data_quality_checks() -> None:
    ctx = build_context(settings=_custom_settings(), dry_run=False)
    mock_client = MagicMock()
    mock_job = MagicMock()
    mock_job.job_id = "job-123"
    mock_client.query.return_value = mock_job

    with patch.object(etl, "get_client", return_value=mock_client):
        etl.run_marts_etl(ctx)
        etl.run_data_quality_checks(ctx)

    assert mock_client.query.call_count == 3
    marts_sql = mock_client.query.call_args_list[0].args[0]
    assert "`instacommerce-test.marts.mart_daily_revenue`" in marts_sql
    assert "`instacommerce-test.staging.stg_orders`" in marts_sql

    dq_sql_1 = mock_client.query.call_args_list[1].args[0]
    dq_sql_2 = mock_client.query.call_args_list[2].args[0]
    assert "`instacommerce-test.staging.stg_orders`" in dq_sql_1
    assert "`instacommerce-test.marts.mart_daily_revenue`" in dq_sql_2


def test_product_features_queries_stg_order_items_joined_with_stg_orders() -> None:
    ctx = build_context(settings=_custom_settings(), dry_run=True)
    captured_queries: List[str] = []

    def _capture(client: object, queries: List[str], logger: object, dry_run: bool) -> None:
        captured_queries.extend(queries)

    with (
        patch.object(features, "get_client", return_value=MagicMock()),
        patch.object(features, "run_queries", side_effect=_capture),
    ):
        features.run_feature_refresh(ctx)

    assert len(captured_queries) == 2
    user_sql, product_sql = captured_queries
    assert "`instacommerce-test.features.user_features`" in user_sql
    assert "`instacommerce-test.staging.stg_orders`" in user_sql

    assert "`instacommerce-test.features.product_features`" in product_sql
    assert "`instacommerce-test.staging.stg_order_items` oi" in product_sql
    assert "INNER JOIN `instacommerce-test.staging.stg_orders` o" in product_sql
    assert "oi.product_id" in product_sql
    assert "oi.quantity" in product_sql
    assert "o.placed_at" in product_sql
    assert "FROM `instacommerce-test.staging.stg_orders`\n" not in product_sql


def test_etl_rejects_invalid_dataset_identifiers() -> None:
    bad_ctx = build_context(settings=_custom_settings(bq_marts_dataset="bad..marts"), dry_run=True)
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        etl.run_marts_etl(bad_ctx)
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        etl.run_data_quality_checks(bad_ctx)
