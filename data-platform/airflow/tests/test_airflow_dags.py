"""Unit tests for Airflow DAGs, Kafka sensors, SQL validation, and Slack alerts."""
import sys
import types
from dataclasses import dataclass
from typing import Any, cast
from unittest.mock import MagicMock

import pytest

from dags import data_quality, dbt_marts, dbt_staging, ml_training, monitoring_alerts
from dags.historical_backfill import _prepare_backfill_params
import plugins.kafka_lag_sensor as kafka_lag_sensor_mod
from plugins.kafka_lag_sensor import KafkaLagSensor
from plugins.slack_alerts import build_block_kit_payload


@dataclass(frozen=True)
class _MockTopicPartition:
    topic: str
    partition: int


def _stub_module(name: str, **attrs: Any) -> Any:
    module = types.ModuleType(name)
    for attr_name, attr_value in attrs.items():
        setattr(module, attr_name, attr_value)
    return module


def _install_mock_admin_client(monkeypatch: pytest.MonkeyPatch, admin_client: Any) -> None:
    admin_cls = MagicMock(return_value=admin_client)
    consumer_cls = MagicMock(return_value=admin_client)
    mock_admin = _stub_module("confluent_kafka.admin", AdminClient=admin_cls)
    mock_ck = _stub_module("confluent_kafka", admin=mock_admin, Consumer=consumer_cls)
    monkeypatch.setitem(sys.modules, "confluent_kafka", mock_ck)
    monkeypatch.setitem(sys.modules, "confluent_kafka.admin", mock_admin)
    monkeypatch.setattr(kafka_lag_sensor_mod, "AdminClient", admin_cls)
    monkeypatch.setattr(kafka_lag_sensor_mod, "Consumer", consumer_cls)


def _mock_consumer_admin(
    topic: str,
    group_offsets: dict[str, list[tuple[int, int, int]]],
) -> MagicMock:
    groups = [types.SimpleNamespace(group_id=gid) for gid in group_offsets]
    admin = MagicMock()
    admin.list_consumer_groups.return_value.result.return_value = types.SimpleNamespace(
        valid=groups
    )

    watermark_map: dict[Any, tuple[int, int]] = {}
    offsets_by_group: dict[str, dict[Any, Any]] = {}
    for gid, parts in group_offsets.items():
        grp_map: dict[Any, Any] = {}
        for partition, committed_offset, high_watermark in parts:
            tp = _MockTopicPartition(topic=topic, partition=partition)
            grp_map[tp] = types.SimpleNamespace(offset=committed_offset)
            watermark_map[tp] = (0, high_watermark)
        offsets_by_group[gid] = grp_map

    def _list_offsets(group_ids: list[str]) -> dict[str, MagicMock]:
        fut = MagicMock()
        fut.result.return_value = offsets_by_group[group_ids[0]]
        return {group_ids[0]: fut}

    admin.list_consumer_group_offsets.side_effect = _list_offsets
    admin.get_watermark_offsets.side_effect = lambda tp: watermark_map[tp]
    return admin


def test_slack_alerts_block_kit_payload():
    """Verify modern Block Kit JSON structure with header, fields, and actions (#666)."""
    context = {
        "dag_id": "dbt_marts",
        "task_id": "dbt_run_marts",
        "execution_date": "2026-10-18T02:00:00Z",
        "exception": "Connection reset by peer during BigQuery slot allocation",
    }

    payload = build_block_kit_payload("Critical DAG failure", context, level="critical", channel="#data-alerts")

    assert payload["channel"] == "#data-alerts"
    assert "blocks" in payload
    blocks = payload["blocks"]
    assert len(blocks) >= 4

    header = blocks[0]
    assert header["type"] == "header"
    assert "CRITICAL" in header["text"]["text"]

    section = blocks[1]
    assert section["type"] == "section"
    assert "fields" in section
    fields_text = " ".join(f["text"] for f in section["fields"])
    assert "dbt_marts" in fields_text
    assert "dbt_run_marts" in fields_text

    exc_block = [b for b in blocks if "Exception:" in b.get("text", {}).get("text", "")][0]
    assert "Connection reset by peer" in exc_block["text"]["text"]


def test_kafka_lag_sensor_within_tolerance(monkeypatch: pytest.MonkeyPatch):
    """Verify KafkaLagSensor returns True when aggregate lag <= max_lag."""
    admin = _mock_consumer_admin("orders.events", {"orders-cg": [(0, 975, 1000)]})
    _install_mock_admin_client(monkeypatch, admin)

    sensor = KafkaLagSensor(
        task_id="test_sensor",
        topic="orders.events",
        max_lag=100,
        bootstrap_servers="kafka-bootstrap:9092",
    )
    assert sensor.poke({}) is True
    assert sensor.topic == "orders.events"
    assert sensor.max_lag == 100


def test_kafka_lag_sensor_exceeds_max_lag(monkeypatch: pytest.MonkeyPatch):
    """Verify KafkaLagSensor returns False when aggregate lag exceeds max_lag."""
    admin = _mock_consumer_admin(
        "orders.events",
        {"orders-cg": [(0, 900, 1000), (1, 850, 1000)]},
    )
    _install_mock_admin_client(monkeypatch, admin)

    sensor = KafkaLagSensor(
        task_id="test_sensor_high_lag",
        topic="orders.events",
        max_lag=100,
        bootstrap_servers="kafka-bootstrap:9092",
    )
    assert sensor.poke({}) is False


def test_kafka_lag_sensor_handles_negative_sentinels_and_admin_errors(monkeypatch: pytest.MonkeyPatch):
    """Verify negative Kafka sentinel offsets are ignored and AdminClient errors return False."""
    admin = _mock_consumer_admin(
        "orders.events",
        {"orders-cg": [(0, -1001, 1000), (1, 960, 1000)]},
    )
    _install_mock_admin_client(monkeypatch, admin)

    sensor = KafkaLagSensor(
        task_id="test_sensor_sentinel",
        topic="orders.events",
        max_lag=100,
        bootstrap_servers="kafka-bootstrap:9092",
    )
    assert sensor.poke({}) is True

    only_sentinel_admin = _mock_consumer_admin("orders.events", {"orders-cg": [(0, -1001, 1000)]})
    _install_mock_admin_client(monkeypatch, only_sentinel_admin)
    assert sensor.poke({}) is False

    failing_admin = MagicMock()
    failing_admin.list_consumer_groups.side_effect = RuntimeError("broker unreachable")
    _install_mock_admin_client(monkeypatch, failing_admin)
    assert sensor.poke({}) is False


def test_historical_backfill_param_extraction():
    """Verify historical backfill param preparation extracts dates and model (#667)."""
    mock_ti = MagicMock()
    mock_dag_run = MagicMock()
    mock_dag_run.conf = {
        "start_date": "2026-08-01",
        "end_date": "2026-08-15",
        "model_name": "mart_daily_revenue",
    }
    context = {"dag_run": mock_dag_run, "ti": mock_ti}

    params = _prepare_backfill_params(**context)
    assert params["start_date"] == "2026-08-01"
    assert params["end_date"] == "2026-08-15"
    assert params["model_name"] == "mart_daily_revenue"
    mock_ti.xcom_push.assert_any_call(key="model_name", value="mart_daily_revenue")


def test_monitoring_alerts_canonical_topics():
    """Verify monitoring_alerts watches canonical TopicNames topics."""
    assert monitoring_alerts.CRITICAL_TOPICS == [
        "orders.events",
        "payments.events",
        "inventory.events",
        "identity.events",
    ]


def test_monitoring_alerts_kafka_lag_ignores_negative_sentinel(monkeypatch: pytest.MonkeyPatch):
    """Verify negative Kafka sentinel offsets do not trigger false lag alerts."""
    groups = [types.SimpleNamespace(group_id="cg-1")]
    tp_sentinel = _MockTopicPartition(topic="orders.events", partition=0)
    tp_lagging = _MockTopicPartition(topic="payments.events", partition=0)

    admin = MagicMock()
    admin.list_consumer_groups.return_value.result.return_value = types.SimpleNamespace(valid=groups)
    fut_sentinel = MagicMock()
    fut_sentinel.result.return_value = {
        tp_sentinel: types.SimpleNamespace(offset=-1001),
    }
    admin.list_consumer_group_offsets.return_value = {"cg-1": fut_sentinel}
    admin.get_watermark_offsets.return_value = (0, 50000)
    _install_mock_admin_client(monkeypatch, admin)

    check_fn = monitoring_alerts.dag.task_dict["check_kafka_lag"].python_callable
    ti = MagicMock()
    monkeypatch.setenv("KAFKA_BOOTSTRAP_SERVERS", "kafka-env:9092")
    check_fn(ti=ti)
    ti.xcom_push.assert_called_once_with(key="kafka_alerts", value=[])
    admin.get_watermark_offsets.assert_not_called()
    # O(G) invariant: list_consumer_groups and list_consumer_group_offsets called ONCE across all CRITICAL_TOPICS
    assert admin.list_consumer_groups.call_count == 1
    assert admin.list_consumer_group_offsets.call_count == len(groups)
    assert cast(MagicMock, kafka_lag_sensor_mod.AdminClient).call_args.args[0]["bootstrap.servers"] == "kafka-env:9092"

    fut_lagging = MagicMock()
    fut_lagging.result.return_value = {
        tp_lagging: types.SimpleNamespace(offset=100),
    }
    admin.list_consumer_group_offsets.return_value = {"cg-1": fut_lagging}
    admin.get_watermark_offsets.return_value = (0, 20000)
    with pytest.raises(ValueError, match="payments.events"):
        check_fn(ti=ti)


def test_monitoring_alerts_bq_freshness_rejects_invalid_identifiers(monkeypatch: pytest.MonkeyPatch):
    """Verify _check_bq_freshness validates BigQuery identifiers before SQL interpolation."""
    mock_bq_client = MagicMock()
    monkeypatch.setattr(
        "google.cloud.bigquery.Client",
        MagicMock(return_value=mock_bq_client),
    )
    monkeypatch.setattr(
        monitoring_alerts,
        "CRITICAL_TABLES",
        ["instacommerce-prod.staging.stg_orders`; DROP TABLE orders--"],
    )

    check_fn = monitoring_alerts.dag.task_dict["check_bq_freshness"].python_callable
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        check_fn(ti=MagicMock())
    mock_bq_client.query.assert_not_called()


def test_monitoring_alerts_bq_freshness_parameterizes_table_id(monkeypatch: pytest.MonkeyPatch):
    """Verify _check_bq_freshness uses ScalarQueryParameter for __TABLES__ lookup."""
    mock_bq_client = MagicMock()
    captured_params: list[tuple[str, str, Any]] = []

    def _scalar_param(name: str, param_type: str, value: Any) -> tuple[str, str, Any]:
        item = (name, param_type, value)
        captured_params.append(item)
        return item

    def _job_config(query_parameters: Any = None) -> dict[str, Any]:
        return {"query_parameters": query_parameters}

    mock_bq_client.query.return_value.result.return_value = [
        types.SimpleNamespace(minutes_since_update=5)
    ]
    monkeypatch.setattr("google.cloud.bigquery.Client", MagicMock(return_value=mock_bq_client))
    monkeypatch.setattr("google.cloud.bigquery.ScalarQueryParameter", _scalar_param, raising=False)
    monkeypatch.setattr("google.cloud.bigquery.QueryJobConfig", _job_config, raising=False)
    monkeypatch.setattr(
        monitoring_alerts,
        "CRITICAL_TABLES",
        ["instacommerce-prod.marts.mart_daily_revenue"],
    )

    check_fn = monitoring_alerts.dag.task_dict["check_bq_freshness"].python_callable
    ti = MagicMock()
    check_fn(ti=ti)

    ti.xcom_push.assert_called_once_with(key="bq_alerts", value=[])
    assert captured_params == [("table_id", "STRING", "mart_daily_revenue")]
    called_sql = mock_bq_client.query.call_args.args[0]
    assert "WHERE table_id = @table_id" in called_sql
    assert "'mart_daily_revenue'" not in called_sql


def test_data_quality_dag_rejects_invalid_sql_identifiers(monkeypatch: pytest.MonkeyPatch):
    """Verify data_quality DAG validates BigQuery identifiers before SQL interpolation."""
    validate_fn = data_quality.dag.task_dict["validate_orders"].python_callable
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        validate_fn(
            domain="orders",
            suite_config={"suite_name": "orders_suite", "datasource": "proj.staging.orders`; DROP TABLE x--", "critical": True},
            ds="2026-10-18",
            ds_nodash="20261018",
            ti=MagicMock(),
        )

    reconcile_fn = data_quality.dag.task_dict["reconcile_daily_revenue"].python_callable
    monkeypatch.setattr(data_quality, "PROJECT_ID", "proj`; DROP TABLE orders--")
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        reconcile_fn(ds="2026-10-18", ti=MagicMock())

    monkeypatch.setattr(data_quality, "PROJECT_ID", "proj..bad")
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        reconcile_fn(ds="2026-10-18", ti=MagicMock())


def test_dbt_staging_alert_on_failure_wired_to_all_upstreams():
    """Verify dbt_staging alert_on_failure triggers if either run or test fails."""
    alert_task = dbt_staging.dag.task_dict["alert_on_failure"]
    assert alert_task.trigger_rule == "one_failed"
    assert alert_task.upstream_task_ids == {"dbt_run_staging", "dbt_test_staging"}


def test_dbt_marts_alert_on_failure_wired_to_all_upstreams():
    """Verify dbt_marts alert_on_failure triggers if any upstream task fails."""
    alert_task = dbt_marts.dag.task_dict["alert_on_failure"]
    assert alert_task.trigger_rule == "one_failed"
    assert alert_task.upstream_task_ids == {
        "wait_for_staging",
        "check_kafka_lag",
        "dbt_run_intermediate",
        "dbt_run_marts",
        "dbt_test_marts",
    }
    assert dbt_marts.dag.task_dict["notify_completion"].upstream_task_ids == {"dbt_test_marts"}


def test_ml_training_promotion_gate_evaluates_full_gate_suite():
    gate_fn = ml_training.dag.task_dict["gate_fraud_detection"].python_callable
    config = ml_training.MODEL_CONFIGS["fraud_detection"]

    ti_pass = MagicMock()
    ti_pass.xcom_pull.return_value = {"f1_score": 0.94, "false_positive_rate": 0.02}
    assert gate_fn(model_name="fraud_detection", config=config, ti=ti_pass) == "fraud_detection_group.promote_fraud_detection"

    ti_fail_fpr = MagicMock()
    ti_fail_fpr.xcom_pull.return_value = {"f1_score": 0.94, "false_positive_rate": 0.12}
    assert gate_fn(model_name="fraud_detection", config=config, ti=ti_fail_fpr) == "fraud_detection_group.skip_promote_fraud_detection"

    ti_missing_fpr = MagicMock()
    ti_missing_fpr.xcom_pull.return_value = {"f1_score": 0.94}
    assert gate_fn(model_name="fraud_detection", config=config, ti=ti_missing_fpr) == "fraud_detection_group.skip_promote_fraud_detection"


def test_ml_training_fallback_gates_reject_bool_nan_and_resolve_aliases(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(ml_training, "_shared_evaluate_gates", None)

    assert ml_training._evaluate_model_gates(
        {"ndcg@10": float("nan")},
        {"min_ndcg@10": 0.75},
    ) is False
    assert ml_training._evaluate_model_gates(
        {"rmse": float("nan"), "mape": 0.02, "accuracy_pct": 95.0},
        {"max_rmse": 0.15, "max_mape": 0.08, "min_accuracy_pct": 92.0},
    ) is False
    assert ml_training._evaluate_model_gates(
        {"ndcg@10": True},
        {"min_ndcg@10": 0.75},
    ) is False
    assert ml_training._evaluate_model_gates(
        {"mae_min": 2.1, "within_2min_pct": 0.9},
        {"max_mae_minutes": 3.0, "min_within_2min_pct": 0.85},
    ) is True
    assert ml_training._evaluate_model_gates(
        {"ndcg_at_10": 0.82},
        {"min_ndcg@10": 0.75},
    ) is True
