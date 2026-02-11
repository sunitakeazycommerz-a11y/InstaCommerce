"""dbt staging pipeline — runs every 2 hours.
Materializes staging models from CDC sources.
"""
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator

default_args = {
    "owner": "data-eng",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["data-alerts@instacommerce.com"],
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
    "execution_timeout": timedelta(minutes=30),
    "on_failure_callback": "plugins.slack_alerts.send_slack_alert",
}

DBT_PROJECT_DIR = "/opt/airflow/dbt"
DBT_PROFILES_DIR = "/opt/airflow/dbt/profiles"

with DAG(
    dag_id="dbt_staging",
    default_args=default_args,
    description="Run dbt staging models every 2 hours",
    schedule_interval="0 */2 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["dbt", "staging", "data-platform"],
    max_active_runs=1,
    sla_miss_callback=None,
) as dag:

    dbt_run_staging = BashOperator(
        task_id="dbt_run_staging",
        bash_command=(
            f"cd {DBT_PROJECT_DIR} && "
            f"dbt run --select staging "
            f"--profiles-dir {DBT_PROFILES_DIR} "
            f"--target prod "
            f"--full-refresh={{{{ var.value.get('dbt_full_refresh', 'false') }}}}"
        ),
        env={
            "DBT_PROFILES_DIR": DBT_PROFILES_DIR,
        },
        append_env=True,
    )

    dbt_test_staging = BashOperator(
        task_id="dbt_test_staging",
        bash_command=(
            f"cd {DBT_PROJECT_DIR} && "
            f"dbt test --select staging "
            f"--profiles-dir {DBT_PROFILES_DIR} "
            f"--target prod"
        ),
        env={
            "DBT_PROFILES_DIR": DBT_PROFILES_DIR,
        },
        append_env=True,
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

    dbt_run_staging >> dbt_test_staging >> alert_on_failure
