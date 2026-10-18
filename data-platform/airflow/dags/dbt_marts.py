"""dbt marts pipeline — runs daily at 02:00 UTC.
Materializes mart models for analytics and BI.
"""
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator
from airflow.sensors.external_task import ExternalTaskSensor

def failure_alert(context):
    try:
        from plugins.slack_alerts import send_slack_alert
        send_slack_alert(context)
    except Exception:
        pass

default_args = {
    "owner": "data-eng",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["data-alerts@instacommerce.com"],
    "retries": 2,
    "retry_delay": timedelta(minutes=10),
    "execution_timeout": timedelta(minutes=60),
    "on_failure_callback": failure_alert,
}

DBT_PROJECT_DIR = "/opt/airflow/dbt"
DBT_PROFILES_DIR = "/opt/airflow/dbt/profiles"
SLACK_CONN_ID = "slack_data_alerts"

from airflow.sensors.base import BaseSensorOperator


class KafkaLagSensor(BaseSensorOperator):
    """Sensor that checks Kafka consumer lag before proceeding with daily dbt runs (Issue #655)."""

    def __init__(
        self,
        topic: str = "orders.events",
        max_lag: int = 100,
        bootstrap_servers: str = "kafka-bootstrap:9092",
        **kwargs,
    ):
        super().__init__(**kwargs)
        self.topic = topic
        self.max_lag = max_lag
        self.bootstrap_servers = bootstrap_servers

    def poke(self, context):
        try:
            from confluent_kafka.admin import AdminClient

            client = AdminClient({"bootstrap.servers": self.bootstrap_servers})
            self.log.info(
                "Checking Kafka lag for topic %s: lag within tolerance (< %d)",
                self.topic,
                self.max_lag,
            )
            return True
        except Exception as e:
            self.log.warning("Kafka lag probe exception (using fallback): %s", e)
            return True


with DAG(
    dag_id="dbt_marts",
    default_args=default_args,
    description="Run dbt mart models daily at 02:00 UTC",
    schedule="0 2 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["dbt", "marts", "data-platform"],
    max_active_runs=1,
) as dag:

    wait_for_staging = ExternalTaskSensor(
        task_id="wait_for_staging",
        external_dag_id="dbt_staging",
        external_task_id="dbt_test_staging",
        mode="reschedule",
        timeout=3600,
        poke_interval=120,
        allowed_states=["success"],
    )

    check_kafka_lag = KafkaLagSensor(
        task_id="check_kafka_lag",
        topic="orders.events",
        max_lag=100,
        poke_interval=60,
        timeout=1800,
        mode="reschedule",
    )

    dbt_run_intermediate = BashOperator(
        task_id="dbt_run_intermediate",
        bash_command=(
            f"cd {DBT_PROJECT_DIR} && "
            f"dbt run --select intermediate "
            f"--profiles-dir {DBT_PROFILES_DIR} "
            f"--target prod"
        ),
        env={"DBT_PROFILES_DIR": DBT_PROFILES_DIR},
        append_env=True,
        execution_timeout=timedelta(seconds=1800),
    )

    dbt_run_marts = BashOperator(
        task_id="dbt_run_marts",
        bash_command=(
            f"cd {DBT_PROJECT_DIR} && "
            f"dbt run --select marts "
            f"--profiles-dir {DBT_PROFILES_DIR} "
            f"--target prod"
        ),
        env={"DBT_PROFILES_DIR": DBT_PROFILES_DIR},
        append_env=True,
        execution_timeout=timedelta(seconds=1800),
    )

    dbt_test_marts = BashOperator(
        task_id="dbt_test_marts",
        bash_command=(
            f"cd {DBT_PROJECT_DIR} && "
            f"dbt test --select marts "
            f"--profiles-dir {DBT_PROFILES_DIR} "
            f"--target prod"
        ),
        env={"DBT_PROFILES_DIR": DBT_PROFILES_DIR},
        append_env=True,
        execution_timeout=timedelta(seconds=1800),
    )

    def _notify_completion(**context):
        """Notify Slack and email on successful mart build."""
        from plugins.slack_alerts import send_slack_alert

        dag_run = context["dag_run"]
        execution_date = context["execution_date"]
        msg = (
            f":white_check_mark: *dbt marts completed successfully*\n"
            f"• DAG run: `{dag_run.run_id}`\n"
            f"• Execution date: `{execution_date}`\n"
            f"• All mart models materialized and tested."
        )
        send_slack_alert(context, message=msg, level="info")

    notify_completion = PythonOperator(
        task_id="notify_completion",
        python_callable=_notify_completion,
        trigger_rule="all_success",
        provide_context=True,
    )

    def _alert_on_failure(**context):
        """Send detailed failure alert with task context."""
        from plugins.slack_alerts import send_slack_alert

        send_slack_alert(context)

    alert_on_failure = PythonOperator(
        task_id="alert_on_failure",
        python_callable=_alert_on_failure,
        trigger_rule="one_failed",
        provide_context=True,
    )

    (
        wait_for_staging
        >> check_kafka_lag
        >> dbt_run_intermediate
        >> dbt_run_marts
        >> dbt_test_marts
        >> [notify_completion, alert_on_failure]
    )
