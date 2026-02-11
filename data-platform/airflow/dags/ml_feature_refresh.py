"""ML Feature Refresh — runs every 4 hours.
Recomputes feature store features from BigQuery and pushes to online store.
"""
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.providers.google.cloud.operators.bigquery import (
    BigQueryInsertJobOperator,
)

default_args = {
    "owner": "ml-eng",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["ml-alerts@instacommerce.com", "data-alerts@instacommerce.com"],
    "retries": 2,
    "retry_delay": timedelta(minutes=10),
    "execution_timeout": timedelta(minutes=45),
    "on_failure_callback": "plugins.slack_alerts.send_slack_alert",
}

PROJECT_ID = "instacommerce-prod"
FEATURE_STORE_ID = "instacommerce_features"
FEATURE_STORE_REGION = "us-central1"

FEATURE_QUERIES = {
    "user_features": "sql/features/user_features.sql",
    "product_features": "sql/features/product_features.sql",
    "order_features": "sql/features/order_features.sql",
    "delivery_features": "sql/features/delivery_features.sql",
}

FRESHNESS_THRESHOLD_MINUTES = 60

with DAG(
    dag_id="ml_feature_refresh",
    default_args=default_args,
    description="Recompute and push ML features every 4 hours",
    schedule_interval="0 */4 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["ml", "feature-store", "data-platform"],
    max_active_runs=1,
) as dag:

    def _run_feature_sql(feature_name: str, sql_path: str, **context):
        """Execute feature computation SQL in BigQuery."""
        from google.cloud import bigquery

        client = bigquery.Client(project=PROJECT_ID)

        with open(f"/opt/airflow/dbt/{sql_path}") as f:
            query = f.read()

        query = query.replace("{{ ds }}", context["ds"])

        job_config = bigquery.QueryJobConfig(
            destination=f"{PROJECT_ID}.features.{feature_name}",
            write_disposition="WRITE_TRUNCATE",
            labels={"pipeline": "feature-refresh", "feature": feature_name},
        )

        job = client.query(query, job_config=job_config)
        job.result()

        context["ti"].xcom_push(
            key=f"{feature_name}_row_count", value=job.num_dml_affected_rows
        )

    feature_tasks = []
    for feature_name, sql_path in FEATURE_QUERIES.items():
        task = PythonOperator(
            task_id=f"compute_{feature_name}",
            python_callable=_run_feature_sql,
            op_kwargs={"feature_name": feature_name, "sql_path": sql_path},
            provide_context=True,
        )
        feature_tasks.append(task)

    def _push_to_online_store(**context):
        """Push computed features to Vertex AI Feature Store online serving."""
        from google.cloud import aiplatform

        aiplatform.init(project=PROJECT_ID, location=FEATURE_STORE_REGION)

        for feature_name in FEATURE_QUERIES:
            entity_type = aiplatform.featurestore.EntityType(
                entity_type_name=feature_name,
                featurestore_id=FEATURE_STORE_ID,
            )
            entity_type.ingest_from_bq(
                bq_source_uri=f"bq://{PROJECT_ID}.features.{feature_name}",
                feature_ids=["*"],
                entity_id_field="entity_id",
            )

    push_to_online_store = PythonOperator(
        task_id="push_to_online_store",
        python_callable=_push_to_online_store,
        provide_context=True,
        execution_timeout=timedelta(minutes=20),
    )

    def _validate_feature_freshness(**context):
        """Validate that all features were updated within the threshold."""
        from datetime import timezone

        from google.cloud import bigquery

        client = bigquery.Client(project=PROJECT_ID)
        stale_features = []

        for feature_name in FEATURE_QUERIES:
            query = f"""
                SELECT TIMESTAMP_DIFF(
                    CURRENT_TIMESTAMP(),
                    MAX(_updated_at),
                    MINUTE
                ) AS minutes_since_update
                FROM `{PROJECT_ID}.features.{feature_name}`
            """
            result = list(client.query(query).result())
            if result and result[0].minutes_since_update > FRESHNESS_THRESHOLD_MINUTES:
                stale_features.append(
                    f"{feature_name}: {result[0].minutes_since_update}min old"
                )

        if stale_features:
            context["ti"].xcom_push(key="stale_features", value=stale_features)
            raise ValueError(
                f"Stale features detected: {', '.join(stale_features)}"
            )

    validate_freshness = PythonOperator(
        task_id="validate_feature_freshness",
        python_callable=_validate_feature_freshness,
        provide_context=True,
    )

    def _alert_if_stale(**context):
        """Send alert if any features are stale after refresh."""
        from plugins.slack_alerts import send_slack_alert

        ti = context["ti"]
        stale = ti.xcom_pull(
            task_ids="validate_feature_freshness", key="stale_features"
        )

        if stale:
            msg = (
                f":warning: *Stale ML features detected*\n"
                f"• Features: {', '.join(stale)}\n"
                f"• Threshold: {FRESHNESS_THRESHOLD_MINUTES} minutes\n"
                f"• Action: Check BigQuery ingestion pipeline."
            )
            send_slack_alert(context, message=msg, level="warning")

    alert_if_stale = PythonOperator(
        task_id="alert_if_stale",
        python_callable=_alert_if_stale,
        trigger_rule="all_done",
        provide_context=True,
    )

    feature_tasks >> push_to_online_store >> validate_freshness >> alert_if_stale
