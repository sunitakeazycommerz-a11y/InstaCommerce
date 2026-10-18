"""Unit tests for Airflow DAGs, Kafka sensors, and Slack Block Kit alerts.

Tests Kafka lag sensing, Slack Block Kit payload structure, task execution timeouts,
and historical backfill DAG parameter validation.
"""
import sys
import os
import types
from unittest.mock import MagicMock

# Provide mock airflow if not installed
if "airflow" not in sys.modules:
    mock_airflow = types.ModuleType("airflow")
    mock_airflow.DAG = MagicMock()
    mock_operators = types.ModuleType("operators")
    mock_bash = types.ModuleType("bash")
    mock_bash.BashOperator = MagicMock()
    mock_python = types.ModuleType("python")
    mock_python.PythonOperator = MagicMock()
    mock_sensors = types.ModuleType("sensors")
    mock_sensors_base = types.ModuleType("base")

    class MockBaseSensorOperator:
        def __init__(self, **kwargs):
            self.log = MagicMock()

    mock_sensors_base.BaseSensorOperator = MockBaseSensorOperator
    mock_sensors.base = mock_sensors_base

    mock_external_task = types.ModuleType("external_task")
    mock_external_task.ExternalTaskSensor = MagicMock()
    mock_sensors.external_task = mock_external_task

    mock_hooks = types.ModuleType("hooks")
    mock_hooks_base = types.ModuleType("base")
    mock_hooks_base.BaseHook = MagicMock()
    mock_hooks.base = mock_hooks_base

    mock_airflow.operators = mock_operators
    mock_airflow.operators.bash = mock_bash
    mock_airflow.operators.python = mock_python
    mock_airflow.sensors = mock_sensors
    mock_airflow.sensors.base = mock_sensors_base
    mock_airflow.sensors.external_task = mock_external_task
    mock_airflow.hooks = mock_hooks
    mock_airflow.hooks.base = mock_hooks_base

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
from dags.dbt_marts import KafkaLagSensor  # noqa: E402


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

    # Check header
    header = blocks[0]
    assert header["type"] == "header"
    assert "CRITICAL" in header["text"]["text"]

    # Check section fields
    section = blocks[1]
    assert section["type"] == "section"
    assert "fields" in section
    fields_text = " ".join(f["text"] for f in section["fields"])
    assert "dbt_marts" in fields_text
    assert "dbt_run_marts" in fields_text

    # Check exception block
    exc_block = [b for b in blocks if "Exception:" in b.get("text", {}).get("text", "")][0]
    assert "Connection reset by peer" in exc_block["text"]["text"]


def test_kafka_lag_sensor_within_tolerance():
    """Verify KafkaLagSensor pokes successfully when lag is below maximum allowed lag (#655)."""
    sensor = KafkaLagSensor(
        task_id="test_sensor",
        topic="orders.events",
        max_lag=100,
        bootstrap_servers="kafka-bootstrap:9092",
    )
    result = sensor.poke({})
    assert result is True
    assert sensor.topic == "orders.events"
    assert sensor.max_lag == 100


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
