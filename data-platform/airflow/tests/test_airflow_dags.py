"""Unit tests for Airflow DAGs, Kafka sensors, SQL validation, and Slack alerts."""
import os
import sys
import types
from dataclasses import dataclass
from typing import Any
from unittest.mock import MagicMock

import pytest


@dataclass(frozen=True)
class _MockTopicPartition:
    topic: str
    partition: int


def _stub_module(name: str, **attrs: Any) -> Any:
    module = types.ModuleType(name)
    for attr_name, attr_value in attrs.items():
        setattr(module, attr_name, attr_value)
    return module


class _MockOperator:
    def __init__(
        self,
        task_id: str = "",
        trigger_rule: str = "all_success",
        python_callable: Any = None,
        op_kwargs: dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        self.task_id = task_id
        self.trigger_rule = trigger_rule
        self.python_callable = python_callable
        self.op_kwargs = op_kwargs or {}
        self.kwargs = kwargs
        self.log = MagicMock()
        self.upstream_task_ids: set[str] = set()
        self.downstream_task_ids: set[str] = set()
        if _MockDAG._current is not None and task_id:
            _MockDAG._current.task_dict[task_id] = self

    def _link_downstream(self, other: Any) -> None:
        targets = other if isinstance(other, (list, tuple)) else [other]
        for target in targets:
            if isinstance(target, _MockOperator):
                self.downstream_task_ids.add(target.task_id)
                target.upstream_task_ids.add(self.task_id)

    def __rshift__(self, other: Any) -> Any:
        self._link_downstream(other)
        return other

    def __rrshift__(self, other: Any) -> "_MockOperator":
        sources = other if isinstance(other, (list, tuple)) else [other]
        for source in sources:
            if isinstance(source, _MockOperator):
                source._link_downstream(self)
        return self


class _MockDAG:
    _current: "_MockDAG | None" = None

    def __init__(self, dag_id: str = "", **kwargs: Any) -> None:
        self.dag_id = dag_id
        self.kwargs = kwargs
        self.task_dict: dict[str, _MockOperator] = {}
        self._previous: _MockDAG | None = None

    def __enter__(self) -> "_MockDAG":
        self._previous = _MockDAG._current
        _MockDAG._current = self
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        _MockDAG._current = self._previous


if "airflow" not in sys.modules:
    mock_bash = _stub_module("airflow.operators.bash", BashOperator=_MockOperator)
    mock_python = _stub_module(
        "airflow.operators.python",
        PythonOperator=_MockOperator,
        BranchPythonOperator=_MockOperator,
    )
    mock_operators = _stub_module(
        "airflow.operators",
        bash=mock_bash,
        python=mock_python,
    )
    mock_sensors_base = _stub_module(
        "airflow.sensors.base",
        BaseSensorOperator=_MockOperator,
    )
    mock_external_task = _stub_module(
        "airflow.sensors.external_task",
        ExternalTaskSensor=_MockOperator,
    )
    mock_sensors = _stub_module(
        "airflow.sensors",
        base=mock_sensors_base,
        external_task=mock_external_task,
    )
    mock_hooks_base = _stub_module("airflow.hooks.base", BaseHook=MagicMock())
    mock_hooks = _stub_module("airflow.hooks", base=mock_hooks_base)
    mock_airflow = _stub_module(
        "airflow",
        DAG=_MockDAG,
        operators=mock_operators,
        sensors=mock_sensors,
        hooks=mock_hooks,
    )

    sys.modules["airflow"] = mock_airflow
    sys.modules["airflow.operators"] = mock_operators
    sys.modules["airflow.operators.bash"] = mock_bash
    sys.modules["airflow.operators.python"] = mock_python
    sys.modules["airflow.sensors"] = mock_sensors
    sys.modules["airflow.sensors.base"] = mock_sensors_base
    sys.modules["airflow.sensors.external_task"] = mock_external_task
    sys.modules["airflow.hooks"] = mock_hooks
    sys.modules["airflow.hooks.base"] = mock_hooks_base

airflow_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if airflow_dir not in sys.path:
    sys.path.insert(0, airflow_dir)

from plugins.slack_alerts import build_block_kit_payload  # noqa: E402
from dags import data_quality, dbt_marts, dbt_staging, monitoring_alerts  # noqa: E402
from dags.dbt_marts import KafkaLagSensor  # noqa: E402


def _install_mock_admin_client(monkeypatch: pytest.MonkeyPatch, admin_client: Any) -> None:
    admin_cls = MagicMock(return_value=admin_client)
    mock_admin = _stub_module("confluent_kafka.admin", AdminClient=admin_cls)
    mock_ck = _stub_module("confluent_kafka", admin=mock_admin)
    monkeypatch.setitem(sys.modules, "confluent_kafka", mock_ck)
    monkeypatch.setitem(sys.modules, "confluent_kafka.admin", mock_admin)


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

    def _list_offsets(group_ids: list[str]) -> MagicMock:
        res = MagicMock()
        res.result.return_value = offsets_by_group[group_ids[0]]
        return res

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
    from dags.historical_backfill import _prepare_backfill_params

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
    admin.list_consumer_group_offsets.return_value.result.return_value = {
        tp_sentinel: types.SimpleNamespace(offset=-1001),
    }
    admin.get_watermark_offsets.return_value = (0, 50000)
    _install_mock_admin_client(monkeypatch, admin)

    check_fn = monitoring_alerts.dag.task_dict["check_kafka_lag"].python_callable
    ti = MagicMock()
    check_fn(ti=ti)
    ti.xcom_push.assert_called_once_with(key="kafka_alerts", value=[])
    admin.get_watermark_offsets.assert_not_called()

    admin.list_consumer_group_offsets.return_value.result.return_value = {
        tp_lagging: types.SimpleNamespace(offset=100),
    }
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
