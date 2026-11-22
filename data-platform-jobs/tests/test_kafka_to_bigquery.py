from __future__ import annotations

import logging
from typing import Any, Dict, List
from unittest.mock import MagicMock, patch

import pytest

from data_platform_jobs.config import JobSettings
from data_platform_jobs.job_registry import build_context
from data_platform_jobs.jobs import kafka_to_bigquery


def _settings(max_messages: int = 2000) -> JobSettings:
    return JobSettings(
        project_id="instacommerce-test",
        bq_raw_dataset="raw",
        bq_staging_dataset="staging",
        bq_marts_dataset="marts",
        bq_features_dataset="features",
        kafka_bootstrap="kafka:9092",
        kafka_topics=("orders.events",),
        kafka_consumer_group="cg-test",
        kafka_max_messages=max_messages,
        batch_window_minutes=1,
        timezone="UTC",
    )


def _msg(offset: int, value: bytes | None, *, topic: str = "orders.events", partition: int = 0) -> MagicMock:
    return MagicMock(
        topic=topic,
        partition=partition,
        offset=offset,
        timestamp=1700000000 + offset,
        key=f"k-{offset}".encode("utf-8"),
        value=value,
    )


def test_kafka_to_bigquery_chunks_insert_rows_into_500_row_batches_with_row_ids() -> None:
    ctx = build_context(settings=_settings(max_messages=1205), dry_run=False)
    mock_consumer = MagicMock()
    mock_client = MagicMock()
    mock_client.insert_rows_json.return_value = []

    messages = [_msg(i, f'{{"order_id": "ord-{i}"}}'.encode("utf-8")) for i in range(1205)]
    mock_consumer.poll.side_effect = [{("orders.events", 0): messages}, {}]

    with (
        patch.object(kafka_to_bigquery, "KafkaConsumer", return_value=mock_consumer),
        patch.object(kafka_to_bigquery, "get_client", return_value=mock_client),
    ):
        kafka_to_bigquery.run_batch_loader(ctx)

    assert mock_client.insert_rows_json.call_count == 3
    batch_sizes = [len(call.args[1]) for call in mock_client.insert_rows_json.call_args_list]
    assert batch_sizes == [500, 500, 205]

    first_row_ids = mock_client.insert_rows_json.call_args_list[0].kwargs["row_ids"]
    second_row_ids = mock_client.insert_rows_json.call_args_list[1].kwargs["row_ids"]
    third_row_ids = mock_client.insert_rows_json.call_args_list[2].kwargs["row_ids"]
    assert first_row_ids[0] == "orders.events:0:0"
    assert first_row_ids[-1] == "orders.events:0:499"
    assert second_row_ids[0] == "orders.events:0:500"
    assert second_row_ids[-1] == "orders.events:0:999"
    assert third_row_ids[0] == "orders.events:0:1000"
    assert third_row_ids[-1] == "orders.events:0:1204"
    mock_consumer.commit.assert_called_once()
    mock_consumer.close.assert_called_once()


def test_kafka_to_bigquery_quarantines_and_counts_malformed_json_messages(caplog: pytest.LogCaptureFixture) -> None:
    ctx = build_context(settings=_settings(max_messages=10), dry_run=False)
    mock_consumer = MagicMock()
    mock_client = MagicMock()
    mock_client.insert_rows_json.return_value = []

    msgs = [
        _msg(1, b'{"order_id": "ok-1"}'),
        _msg(2, b"{not-valid-json"),
        _msg(3, b"[1, 2, 3]"),
        _msg(4, b"\xff\xfe\xfd"),
        _msg(5, b'{"order_id": "ok-2"}'),
    ]
    mock_consumer.poll.side_effect = [{("orders.events", 0): msgs}, {}]

    with (
        caplog.at_level(logging.INFO, logger="data_platform_jobs"),
        patch.object(kafka_to_bigquery, "KafkaConsumer", return_value=mock_consumer),
        patch.object(kafka_to_bigquery, "get_client", return_value=mock_client),
        patch.object(kafka_to_bigquery.time, "monotonic", side_effect=[0.0, 1.0, 2.0, 100.0]),
    ):
        kafka_to_bigquery.run_batch_loader(ctx)

    assert mock_client.insert_rows_json.call_count == 2

    main_call = mock_client.insert_rows_json.call_args_list[0]
    assert main_call.args[0] == "instacommerce-test.raw.kafka_events"
    assert [r["offset"] for r in main_call.args[1]] == [1, 5]
    assert main_call.kwargs["row_ids"] == ["orders.events:0:1", "orders.events:0:5"]

    quarantine_call = mock_client.insert_rows_json.call_args_list[1]
    assert quarantine_call.args[0] == "instacommerce-test.raw.kafka_events_quarantine"
    q_rows: List[Dict[str, Any]] = quarantine_call.args[1]
    assert len(q_rows) == 3
    assert [r["offset"] for r in q_rows] == [2, 3, 4]
    assert q_rows[0]["raw_value"] == "{not-valid-json"
    assert q_rows[1]["raw_value"] == "[1, 2, 3]"
    assert q_rows[2]["raw_value"] == "fffefd"
    assert quarantine_call.kwargs["row_ids"] == [
        "orders.events:0:2",
        "orders.events:0:3",
        "orders.events:0:4",
    ]
    assert any("malformed_json=3" in rec.message for rec in caplog.records)
    mock_consumer.commit.assert_called_once()


def test_kafka_to_bigquery_all_malformed_batch_quarantines_and_commits() -> None:
    ctx = build_context(settings=_settings(max_messages=2), dry_run=False)
    mock_consumer = MagicMock()
    mock_client = MagicMock()
    mock_client.insert_rows_json.return_value = []

    mock_consumer.poll.side_effect = [
        {("orders.events", 0): [_msg(10, b"{corrupt"), _msg(11, b"123")]},
        {},
    ]

    with (
        patch.object(kafka_to_bigquery, "KafkaConsumer", return_value=mock_consumer),
        patch.object(kafka_to_bigquery, "get_client", return_value=mock_client),
    ):
        kafka_to_bigquery.run_batch_loader(ctx)

    mock_client.insert_rows_json.assert_called_once()
    assert mock_client.insert_rows_json.call_args.args[0] == "instacommerce-test.raw.kafka_events_quarantine"
    assert len(mock_client.insert_rows_json.call_args.args[1]) == 2
    mock_consumer.commit.assert_called_once()


def test_kafka_to_bigquery_raises_if_quarantine_insert_fails_without_commit() -> None:
    ctx = build_context(settings=_settings(max_messages=1), dry_run=False)
    mock_consumer = MagicMock()
    mock_client = MagicMock()
    mock_client.insert_rows_json.return_value = [{"index": 0, "errors": [{"reason": "backendError"}]}]

    mock_consumer.poll.side_effect = [{("orders.events", 0): [_msg(1, b"{bad")]}, {}]

    with (
        patch.object(kafka_to_bigquery, "KafkaConsumer", return_value=mock_consumer),
        patch.object(kafka_to_bigquery, "get_client", return_value=mock_client),
    ):
        with pytest.raises(RuntimeError, match="BigQuery insert_rows_json failed"):
            kafka_to_bigquery.run_batch_loader(ctx)

    mock_consumer.commit.assert_not_called()
    mock_consumer.close.assert_called_once()


def test_kafka_to_bigquery_dry_run_and_missing_dependencies() -> None:
    dry_ctx = build_context(settings=_settings(), dry_run=True)
    with patch.object(kafka_to_bigquery, "KafkaConsumer") as mock_consumer_cls:
        kafka_to_bigquery.run_batch_loader(dry_ctx)
    mock_consumer_cls.assert_not_called()

    live_ctx = build_context(settings=_settings(), dry_run=False)
    with patch.object(kafka_to_bigquery, "KafkaConsumer", None):
        kafka_to_bigquery.run_batch_loader(live_ctx)

    with (
        patch.object(kafka_to_bigquery, "KafkaConsumer", MagicMock()),
        patch.object(kafka_to_bigquery, "get_client", return_value=None),
    ):
        kafka_to_bigquery.run_batch_loader(live_ctx)


def test_kafka_to_bigquery_mid_poll_multi_partition_commits_only_processed_offsets() -> None:
    ctx = build_context(settings=_settings(max_messages=4), dry_run=False)
    mock_consumer = MagicMock()
    mock_client = MagicMock()
    mock_client.insert_rows_json.return_value = []

    p0_msgs = [_msg(offset, f'{{"order_id": "p0-{offset}"}}'.encode("utf-8"), partition=0) for offset in (10, 11)]
    p1_msgs = [
        _msg(20, b'{"order_id": "p1-20"}', partition=1),
        _msg(21, b"{bad-json", partition=1),
        _msg(22, b'{"order_id": "p1-22"}', partition=1),
        _msg(23, b'{"order_id": "p1-23"}', partition=1),
    ]
    p2_msgs = [_msg(30, b'{"order_id": "p2-30"}', partition=2)]

    mock_consumer.poll.side_effect = [
        {
            ("orders.events", 0): p0_msgs,
            ("orders.events", 1): p1_msgs,
            ("orders.events", 2): p2_msgs,
        }
    ]

    with (
        patch.object(kafka_to_bigquery, "KafkaConsumer", return_value=mock_consumer),
        patch.object(kafka_to_bigquery, "get_client", return_value=mock_client),
    ):
        kafka_to_bigquery.run_batch_loader(ctx)

    assert mock_client.insert_rows_json.call_count == 2
    valid_call = mock_client.insert_rows_json.call_args_list[0]
    quarantine_call = mock_client.insert_rows_json.call_args_list[1]
    assert valid_call.kwargs["row_ids"] == [
        "orders.events:0:10",
        "orders.events:0:11",
        "orders.events:1:20",
    ]
    assert quarantine_call.kwargs["row_ids"] == ["orders.events:1:21"]

    mock_consumer.commit.assert_called_once()
    committed_offsets = mock_consumer.commit.call_args.kwargs["offsets"]
    assert committed_offsets == {
        kafka_to_bigquery.TopicPartition("orders.events", 0): kafka_to_bigquery.OffsetAndMetadata(12, ""),
        kafka_to_bigquery.TopicPartition("orders.events", 1): kafka_to_bigquery.OffsetAndMetadata(22, ""),
    }
    assert kafka_to_bigquery.TopicPartition("orders.events", 2) not in committed_offsets
    mock_consumer.close.assert_called_once()


def test_kafka_to_bigquery_mid_poll_single_partition_cutoff_leaves_trailing_uncommitted() -> None:
    ctx = build_context(settings=_settings(max_messages=2), dry_run=False)
    mock_consumer = MagicMock()
    mock_client = MagicMock()
    mock_client.insert_rows_json.return_value = []

    p0_msgs = [_msg(offset, f'{{"order_id": "p0-{offset}"}}'.encode("utf-8"), partition=0) for offset in range(5)]
    p1_msgs = [_msg(offset, f'{{"order_id": "p1-{offset}"}}'.encode("utf-8"), partition=1) for offset in range(3)]
    mock_consumer.poll.side_effect = [{("orders.events", 0): p0_msgs, ("orders.events", 1): p1_msgs}]

    with (
        patch.object(kafka_to_bigquery, "KafkaConsumer", return_value=mock_consumer),
        patch.object(kafka_to_bigquery, "get_client", return_value=mock_client),
    ):
        kafka_to_bigquery.run_batch_loader(ctx)

    mock_client.insert_rows_json.assert_called_once()
    assert mock_client.insert_rows_json.call_args.kwargs["row_ids"] == [
        "orders.events:0:0",
        "orders.events:0:1",
    ]
    committed_offsets = mock_consumer.commit.call_args.kwargs["offsets"]
    assert committed_offsets == {
        kafka_to_bigquery.TopicPartition("orders.events", 0): kafka_to_bigquery.OffsetAndMetadata(2, ""),
    }
    assert kafka_to_bigquery.TopicPartition("orders.events", 1) not in committed_offsets
