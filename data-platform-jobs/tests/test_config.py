from unittest.mock import MagicMock, patch

import pytest

from data_platform_jobs.config import load_settings
from data_platform_jobs.job_registry import build_context
from data_platform_jobs.jobs import features, kafka_to_bigquery


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


