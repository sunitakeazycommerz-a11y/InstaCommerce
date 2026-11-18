"""Data Pipeline Monitoring — runs every 15 minutes.
Checks: Kafka lag, BigQuery freshness, dbt SLA, feature store health.
"""

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator
from google.cloud import bigquery  # type: ignore[import-untyped]
from plugins.kafka_lag_sensor import collect_topic_lags
from plugins.slack_alerts import send_slack_alert
from plugins.sql_validation import validate_bq_identifier

try:
    from airflow.models import DagRun, Variable
except ImportError:
    DagRun = None  # type: ignore[assignment,misc]
    Variable = None  # type: ignore[assignment,misc]

try:
    from google.cloud import aiplatform
except ImportError:
    aiplatform = None  # type: ignore[assignment]


def failure_alert(context):
    try:
        send_slack_alert(context)
    except Exception:
        pass


def _get_kafka_bootstrap_servers() -> str:
    if Variable is not None:
        try:
            val = Variable.get("KAFKA_BOOTSTRAP_SERVERS", default_var=None)
            if not val:
                val = Variable.get("kafka_bootstrap_servers", default_var=None)
            if val:
                return str(val)
        except Exception:
            pass
    return os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka-bootstrap:9092")


default_args = {
    "owner": "data-eng",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["data-alerts@instacommerce.com"],
    "retries": 1,
    "retry_delay": timedelta(minutes=2),
    "execution_timeout": timedelta(minutes=10),
    "on_failure_callback": failure_alert,
}

PROJECT_ID = "instacommerce-prod"

KAFKA_LAG_THRESHOLD = 10000
BQ_FRESHNESS_THRESHOLD_MINUTES = 30
DBT_SLA_THRESHOLD_MINUTES = 120
FEATURE_STORE_STALENESS_MINUTES = 60

CRITICAL_TOPICS = [
    "orders.events",
    "payments.events",
    "inventory.events",
    "identity.events",
]

CRITICAL_TABLES = [
    f"{PROJECT_ID}.staging.stg_orders",
    f"{PROJECT_ID}.staging.stg_payments",
    f"{PROJECT_ID}.staging.stg_inventory_movements",
    f"{PROJECT_ID}.marts.mart_daily_revenue",
]

with DAG(
    dag_id="monitoring_alerts",
    default_args=default_args,
    description="Monitor data pipeline health every 15 minutes",
    schedule="*/15 * * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["monitoring", "alerts", "data-platform"],
    max_active_runs=1,
) as dag:

    def _check_kafka_lag(**context):
        """Check Kafka consumer group lag for critical topics in O(G) calls."""
        bootstrap_servers = _get_kafka_bootstrap_servers()
        alerts = []
        try:
            lags_by_topic = collect_topic_lags(
                bootstrap_servers,
                CRITICAL_TOPICS,
                consumer_group_id="airflow-kafka-lag-monitor",
            )
            for topic in CRITICAL_TOPICS:
                for group_id, lag in lags_by_topic.get(topic, {}).items():
                    if lag > KAFKA_LAG_THRESHOLD:
                        alerts.append(f"{topic} (group={group_id}): lag={lag:,}")
        except Exception as e:
            alerts.append(f"error checking Kafka lag - {str(e)}")

        context["ti"].xcom_push(key="kafka_alerts", value=alerts)
        if alerts:
            raise ValueError(f"Kafka lag alerts: {'; '.join(alerts)}")

    check_kafka = PythonOperator(
        task_id="check_kafka_lag",
        python_callable=_check_kafka_lag,
        provide_context=True,
    )

    def _check_bq_freshness(**context):
        """Check BigQuery table freshness for critical tables."""
        project_id = validate_bq_identifier(PROJECT_ID, "project")
        client = bigquery.Client(project=project_id)
        alerts = []

        for table_ref in CRITICAL_TABLES:
            table_ref = validate_bq_identifier(table_ref, "table_ref")
            job_config = None
            if "staging" in table_ref:
                query = f"""
                    SELECT TIMESTAMP_DIFF(
                        CURRENT_TIMESTAMP(),
                        TIMESTAMP(MAX(_cdc_updated_at)),
                        MINUTE
                    ) AS minutes_since_update
                    FROM `{table_ref}`
                """
            else:
                parts = table_ref.split(".")
                dataset_name = validate_bq_identifier(
                    parts[-2] if len(parts) >= 2 else "marts", "dataset"
                )
                table_name = validate_bq_identifier(parts[-1], "table")
                query = f"""
                    SELECT TIMESTAMP_DIFF(
                        CURRENT_TIMESTAMP(),
                        TIMESTAMP_MILLIS(last_modified_time),
                        MINUTE
                    ) AS minutes_since_update
                    FROM `{project_id}.{dataset_name}.__TABLES__`
                    WHERE table_id = @table_id
                """
                job_config = bigquery.QueryJobConfig(
                    query_parameters=[
                        bigquery.ScalarQueryParameter("table_id", "STRING", table_name)
                    ]
                )
            try:
                query_job = (
                    client.query(query, job_config=job_config)
                    if job_config is not None
                    else client.query(query)
                )
                results = list(query_job.result())
                if results:
                    minutes_old = results[0].minutes_since_update
                    if minutes_old and minutes_old > BQ_FRESHNESS_THRESHOLD_MINUTES:
                        alerts.append(f"{table_ref}: {minutes_old}min stale")
            except Exception as e:
                alerts.append(f"{table_ref}: error - {str(e)}")

        context["ti"].xcom_push(key="bq_alerts", value=alerts)
        if alerts:
            raise ValueError(f"BQ freshness alerts: {'; '.join(alerts)}")

    check_bq = PythonOperator(
        task_id="check_bq_freshness",
        python_callable=_check_bq_freshness,
        provide_context=True,
    )

    def _check_dbt_sla(**context):
        """Check that dbt DAGs completed within SLA."""
        if DagRun is None:
            raise ImportError("airflow.models.DagRun unavailable")

        dbt_dags = ["dbt_staging", "dbt_marts"]
        alerts = []

        for dag_id in dbt_dags:
            latest_run = DagRun.find(dag_id=dag_id, state="success")
            if not latest_run:
                alerts.append(f"{dag_id}: no successful runs found")
                continue

            latest = sorted(latest_run, key=lambda r: r.execution_date)[-1]
            if latest.end_date and latest.start_date:
                duration = (latest.end_date - latest.start_date).total_seconds() / 60
                if duration > DBT_SLA_THRESHOLD_MINUTES:
                    alerts.append(
                        f"{dag_id}: took {duration:.0f}min (SLA: {DBT_SLA_THRESHOLD_MINUTES}min)"
                    )

        context["ti"].xcom_push(key="dbt_sla_alerts", value=alerts)
        if alerts:
            raise ValueError(f"dbt SLA alerts: {'; '.join(alerts)}")

    check_dbt_sla = PythonOperator(
        task_id="check_dbt_sla",
        python_callable=_check_dbt_sla,
        provide_context=True,
    )

    def _check_feature_store_health(**context):
        """Check Vertex AI Feature Store online serving health."""
        if aiplatform is None:
            raise ImportError("google.cloud.aiplatform unavailable")

        aiplatform.init(project=PROJECT_ID, location="us-central1")
        alerts = []

        try:
            featurestores = aiplatform.Featurestore.list()
            for fs in featurestores:
                entity_types = fs.list_entity_types()
                for et in entity_types:
                    metadata = et.gca_resource
                    if hasattr(metadata, "monitoring_stats_anomalies"):
                        for anomaly in metadata.monitoring_stats_anomalies:
                            alerts.append(
                                f"{fs.display_name}/{et.display_name}: "
                                f"anomaly detected - {anomaly.objective}"
                            )
        except Exception as e:
            alerts.append(f"Feature store health check error: {str(e)}")

        context["ti"].xcom_push(key="feature_store_alerts", value=alerts)
        if alerts:
            raise ValueError(f"Feature store alerts: {'; '.join(alerts)}")

    check_feature_store = PythonOperator(
        task_id="check_feature_store_health",
        python_callable=_check_feature_store_health,
        provide_context=True,
    )

    def _aggregate_and_alert(**context):
        """Aggregate all monitoring results and send unified alert."""
        ti = context["ti"]
        all_alerts = []

        for check_key in ["kafka_alerts", "bq_alerts", "dbt_sla_alerts", "feature_store_alerts"]:
            task_map = {
                "kafka_alerts": "check_kafka_lag",
                "bq_alerts": "check_bq_freshness",
                "dbt_sla_alerts": "check_dbt_sla",
                "feature_store_alerts": "check_feature_store_health",
            }
            alerts = ti.xcom_pull(task_ids=task_map[check_key], key=check_key) or []
            all_alerts.extend(alerts)

        if all_alerts:
            msg = (
                f":rotating_light: *Data Platform Monitoring Alert*\n"
                f"• Time: `{context['ts']}`\n"
                f"• Issues found: {len(all_alerts)}\n"
                + "\n".join(f"  - {a}" for a in all_alerts)
            )
            send_slack_alert(context, message=msg, level="critical")

    aggregate_alert = PythonOperator(
        task_id="aggregate_and_alert",
        python_callable=_aggregate_and_alert,
        trigger_rule="all_done",
        provide_context=True,
    )

    [check_kafka, check_bq, check_dbt_sla, check_feature_store] >> aggregate_alert
