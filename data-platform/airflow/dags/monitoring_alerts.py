"""Data Pipeline Monitoring — runs every 15 minutes.
Checks: Kafka lag, BigQuery freshness, dbt SLA, feature store health.
"""
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator

default_args = {
    "owner": "data-eng",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["data-alerts@instacommerce.com"],
    "retries": 1,
    "retry_delay": timedelta(minutes=2),
    "execution_timeout": timedelta(minutes=10),
    "on_failure_callback": "plugins.slack_alerts.send_slack_alert",
}

PROJECT_ID = "instacommerce-prod"

KAFKA_LAG_THRESHOLD = 10000
BQ_FRESHNESS_THRESHOLD_MINUTES = 30
DBT_SLA_THRESHOLD_MINUTES = 120
FEATURE_STORE_STALENESS_MINUTES = 60

CRITICAL_TOPICS = [
    "orders.cdc",
    "payments.cdc",
    "inventory.updates",
    "user.events",
]

CRITICAL_TABLES = [
    f"{PROJECT_ID}.staging.stg_orders",
    f"{PROJECT_ID}.staging.stg_payments",
    f"{PROJECT_ID}.staging.stg_inventory",
    f"{PROJECT_ID}.marts.fct_orders",
]

with DAG(
    dag_id="monitoring_alerts",
    default_args=default_args,
    description="Monitor data pipeline health every 15 minutes",
    schedule_interval="*/15 * * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["monitoring", "alerts", "data-platform"],
    max_active_runs=1,
) as dag:

    def _check_kafka_lag(**context):
        """Check Kafka consumer group lag for critical topics."""
        from confluent_kafka.admin import AdminClient

        admin = AdminClient({"bootstrap.servers": "kafka.instacommerce.internal:9092"})

        alerts = []
        for topic in CRITICAL_TOPICS:
            try:
                # Fetch consumer group lag via admin API
                consumer_groups = admin.list_consumer_groups().result()
                for group in consumer_groups.valid:
                    offsets = admin.list_consumer_group_offsets(
                        [group.group_id]
                    ).result()
                    for tp, offset_info in offsets.items():
                        if tp.topic == topic:
                            watermarks = admin.get_watermark_offsets(tp)
                            lag = watermarks[1] - offset_info.offset
                            if lag > KAFKA_LAG_THRESHOLD:
                                alerts.append(
                                    f"{topic} (group={group.group_id}): lag={lag:,}"
                                )
            except Exception as e:
                alerts.append(f"{topic}: error checking lag - {str(e)}")

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
        from google.cloud import bigquery

        client = bigquery.Client(project=PROJECT_ID)
        alerts = []

        for table_ref in CRITICAL_TABLES:
            query = f"""
                SELECT TIMESTAMP_DIFF(
                    CURRENT_TIMESTAMP(),
                    MAX(_loaded_at),
                    MINUTE
                ) AS minutes_since_update
                FROM `{table_ref}`
            """
            try:
                results = list(client.query(query).result())
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
        from airflow.models import DagRun

        dbt_dags = ["dbt_staging", "dbt_marts"]
        alerts = []

        for dag_id in dbt_dags:
            latest_run = (
                DagRun.find(dag_id=dag_id, state="success")
            )
            if not latest_run:
                alerts.append(f"{dag_id}: no successful runs found")
                continue

            latest = sorted(latest_run, key=lambda r: r.execution_date)[-1]
            if latest.end_date:
                duration = (latest.end_date - latest.execution_date).total_seconds() / 60
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
        from google.cloud import aiplatform

        aiplatform.init(project=PROJECT_ID, location="us-central1")
        alerts = []

        try:
            featurestores = aiplatform.Featurestore.list()
            for fs in featurestores:
                entity_types = fs.list_entity_types()
                for et in entity_types:
                    # Check last ingestion timestamp
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
        from plugins.slack_alerts import send_slack_alert

        ti = context["ti"]
        all_alerts = []

        for check_key in ["kafka_alerts", "bq_alerts", "dbt_sla_alerts", "feature_store_alerts"]:
            task_map = {
                "kafka_alerts": "check_kafka_lag",
                "bq_alerts": "check_bq_freshness",
                "dbt_sla_alerts": "check_dbt_sla",
                "feature_store_alerts": "check_feature_store_health",
            }
            alerts = ti.xcom_pull(
                task_ids=task_map[check_key], key=check_key
            ) or []
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
