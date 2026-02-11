"""Data Quality Checks — runs every 2 hours after staging.
Great Expectations validation suites per domain.
"""
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.sensors.external_task import ExternalTaskSensor

default_args = {
    "owner": "data-eng",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["data-alerts@instacommerce.com"],
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
    "execution_timeout": timedelta(minutes=30),
    "on_failure_callback": "plugins.slack_alerts.send_slack_alert",
}

PROJECT_ID = "instacommerce-prod"
GE_ROOT_DIR = "/opt/airflow/great_expectations"
QUALITY_THRESHOLD = 0.95  # Block downstream if pass rate below 95%

DOMAIN_SUITES = {
    "orders": {
        "suite_name": "orders_suite",
        "datasource": f"{PROJECT_ID}.staging.stg_orders",
        "critical": True,
    },
    "payments": {
        "suite_name": "payments_suite",
        "datasource": f"{PROJECT_ID}.staging.stg_payments",
        "critical": True,
    },
    "inventory": {
        "suite_name": "inventory_suite",
        "datasource": f"{PROJECT_ID}.staging.stg_inventory",
        "critical": True,
    },
    "users": {
        "suite_name": "users_suite",
        "datasource": f"{PROJECT_ID}.staging.stg_users",
        "critical": False,
    },
}

with DAG(
    dag_id="data_quality",
    default_args=default_args,
    description="Great Expectations validation suites every 2 hours after staging",
    schedule_interval="0 */2 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["data-quality", "great-expectations", "data-platform"],
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

    def _validate_domain(domain: str, suite_config: dict, **context):
        """Run Great Expectations validation suite for a domain."""
        import great_expectations as gx

        ge_context = gx.get_context(context_root_dir=GE_ROOT_DIR)

        batch_request = {
            "datasource_name": "bigquery",
            "data_connector_name": "default",
            "data_asset_name": suite_config["datasource"],
            "batch_spec_passthrough": {
                "query": f"SELECT * FROM `{suite_config['datasource']}` "
                         f"WHERE DATE(_loaded_at) = '{context['ds']}'"
            },
        }

        checkpoint_config = {
            "name": f"{domain}_checkpoint_{context['ds_nodash']}",
            "validations": [
                {
                    "batch_request": batch_request,
                    "expectation_suite_name": suite_config["suite_name"],
                }
            ],
        }

        checkpoint = ge_context.add_or_update_checkpoint(**checkpoint_config)
        result = checkpoint.run()

        success_percent = result.statistics["success_percent"]
        passed = result.success

        context["ti"].xcom_push(
            key=f"{domain}_success_percent", value=success_percent
        )
        context["ti"].xcom_push(key=f"{domain}_passed", value=passed)

        if suite_config["critical"] and success_percent < QUALITY_THRESHOLD * 100:
            raise ValueError(
                f"Critical quality check failed for {domain}: "
                f"{success_percent}% pass rate (threshold: {QUALITY_THRESHOLD * 100}%)"
            )

        return {
            "domain": domain,
            "passed": passed,
            "success_percent": success_percent,
        }

    validation_tasks = []
    for domain, suite_config in DOMAIN_SUITES.items():
        task = PythonOperator(
            task_id=f"validate_{domain}",
            python_callable=_validate_domain,
            op_kwargs={"domain": domain, "suite_config": suite_config},
            provide_context=True,
        )
        validation_tasks.append(task)

    def _alert_on_critical_failures(**context):
        """Check all validation results and alert on critical failures."""
        from plugins.slack_alerts import send_slack_alert

        failures = []
        for domain, suite_config in DOMAIN_SUITES.items():
            passed = context["ti"].xcom_pull(
                task_ids=f"validate_{domain}", key=f"{domain}_passed"
            )
            pct = context["ti"].xcom_pull(
                task_ids=f"validate_{domain}", key=f"{domain}_success_percent"
            )

            if not passed:
                severity = "CRITICAL" if suite_config["critical"] else "WARNING"
                failures.append(f"[{severity}] {domain}: {pct}% passed")

        if failures:
            msg = (
                f":rotating_light: *Data Quality Failures*\n"
                f"• Execution: `{context['ds']}`\n"
                + "\n".join(f"• {f}" for f in failures)
                + f"\n• Quality threshold: {QUALITY_THRESHOLD * 100}%"
            )
            send_slack_alert(context, message=msg, level="critical")

    alert_critical = PythonOperator(
        task_id="alert_on_critical_failures",
        python_callable=_alert_on_critical_failures,
        trigger_rule="all_done",
        provide_context=True,
    )

    def _block_downstream_if_critical(**context):
        """Fail the task if any critical domain is below quality threshold."""
        for domain, suite_config in DOMAIN_SUITES.items():
            if not suite_config["critical"]:
                continue

            pct = context["ti"].xcom_pull(
                task_ids=f"validate_{domain}", key=f"{domain}_success_percent"
            )
            if pct is not None and pct < QUALITY_THRESHOLD * 100:
                raise ValueError(
                    f"Blocking downstream: {domain} quality at {pct}% "
                    f"(minimum {QUALITY_THRESHOLD * 100}%)"
                )

    block_downstream = PythonOperator(
        task_id="block_downstream_if_critical",
        python_callable=_block_downstream_if_critical,
        trigger_rule="all_done",
        provide_context=True,
    )

    wait_for_staging >> validation_tasks >> [alert_critical, block_downstream]
