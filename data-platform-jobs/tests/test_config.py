from unittest.mock import MagicMock, patch

import pytest

from data_platform_jobs.config import load_settings
from data_platform_jobs.job_registry import build_context
from data_platform_jobs.jobs import etl, features, kafka_to_bigquery


def test_load_settings_default_topics():
    settings = load_settings()
    assert "orders.events" in settings.kafka_topics
    assert "payments.events" in settings.kafka_topics
    assert settings.project_id == "instacommerce-dev"


def test_features_bounded_30_day_window_excludes_current_day():
    ctx = build_context(dry_run=True)
    captured_queries: list[str] = []

    def _capture_queries(client, queries, logger, dry_run):
        captured_queries.extend(queries)

    with (
        patch.object(features, "get_client", return_value=MagicMock()),
        patch.object(features, "run_queries", side_effect=_capture_queries),
    ):
        features.run_feature_refresh(ctx)

    assert len(captured_queries) == 2
    for sql in captured_queries:
        assert "DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)" in sql
        assert "DATE_SUB(CURRENT_DATE(), INTERVAL 1 DAY)" in sql


def test_kafka_to_bigquery_raises_on_insert_errors_without_commit():
    ctx = build_context(dry_run=False)
    mock_consumer = MagicMock()
    mock_client = MagicMock()
    mock_client.insert_rows_json.return_value = [{"index": 0, "errors": [{"reason": "invalid"}]}]

    with (
        patch.object(kafka_to_bigquery, "KafkaConsumer", return_value=mock_consumer),
        patch.object(kafka_to_bigquery, "get_client", return_value=mock_client),
        patch.object(kafka_to_bigquery, "_collect_rows", return_value=[{"topic": "orders.events"}]),
    ):
        with pytest.raises(RuntimeError, match="BigQuery insert_rows_json failed"):
            kafka_to_bigquery.run_batch_loader(ctx)

    mock_consumer.commit.assert_not_called()
    mock_consumer.close.assert_called_once()


def test_etl_and_features_reject_invalid_bq_identifiers():
    bad_ctx = build_context(dry_run=True)
    bad_ctx.settings = load_settings().__class__(
        **{**load_settings().__dict__, "project_id": "bad`; DROP TABLE x--"}
    )
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        etl.run_staging_etl(bad_ctx)
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        features.run_feature_refresh(bad_ctx)


def test_kafka_to_bigquery_validates_rows_and_uses_deterministic_row_ids():
    ctx = build_context(dry_run=False)
    mock_consumer = MagicMock()
    mock_client = MagicMock()
    mock_client.insert_rows_json.return_value = []

    valid_msg = MagicMock(
        topic="orders.events",
        partition=2,
        offset=42,
        timestamp=1700000000,
        key=b"ord-1",
        value=b'{"order_id": "ord-1"}',
    )
    corrupt_msg = MagicMock(
        topic="",
        partition=-1,
        offset="bad",
        timestamp=None,
        key=None,
        value=None,
    )
    mock_consumer.poll.side_effect = [
        {("orders.events", 2): [corrupt_msg, valid_msg]},
        {},
    ]
    orig_max = ctx.settings.kafka_max_messages
    ctx.settings = ctx.settings.__class__(**{**ctx.settings.__dict__, "kafka_max_messages": 1})

    with (
        patch.object(kafka_to_bigquery, "KafkaConsumer", return_value=mock_consumer),
        patch.object(kafka_to_bigquery, "get_client", return_value=mock_client),
    ):
        kafka_to_bigquery.run_batch_loader(ctx)

    mock_client.insert_rows_json.assert_called_once()
    call_kwargs = mock_client.insert_rows_json.call_args.kwargs
    assert call_kwargs["row_ids"] == ["orders.events:2:42"]
    mock_consumer.commit.assert_called_once()
    assert orig_max > 0


