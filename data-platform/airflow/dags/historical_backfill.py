"""Automated historical backfill orchestration DAG (Issue #667).

Accepts parameters via dag_run.conf:
  - start_date: ISO date string (e.g. "2026-09-01")
  - end_date: ISO date string (e.g. "2026-10-01")
  - model_name: Target dbt model (e.g. "mart_order_fulfillment")
Executes incremental date-partitioned backfills and asserts row count parity post-run.
"""
from datetime import date, datetime, timedelta
import json
import logging
import re
import subprocess
from typing import Any, Dict, Tuple

from airflow import DAG
from airflow.operators.python import PythonOperator
from google.cloud import bigquery  # type: ignore[import-untyped]
from plugins.sql_validation import validate_bq_identifier

logger = logging.getLogger(__name__)

default_args = {
    "owner": "data-eng",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["data-alerts@instacommerce.com"],
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
    "execution_timeout": timedelta(hours=2),
}

DBT_PROJECT_DIR = "/opt/airflow/dbt"
DBT_PROFILES_DIR = "/opt/airflow/dbt/profiles"
PROJECT_ID = "instacommerce-prod"

_ISO_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

ALLOWED_BACKFILL_MODELS: frozenset[str] = frozenset({
    "stg_orders",
    "stg_payments",
    "stg_users",
    "stg_products",
    "stg_deliveries",
    "stg_inventory_movements",
    "stg_searches",
    "stg_cart_events",
    "int_order_deliveries",
    "int_product_performance",
    "int_user_order_history",
    "mart_daily_revenue",
    "mart_daily_sales",
    "mart_experiment_conversions",
    "mart_order_fulfillment",
    "mart_product_analytics",
    "mart_rider_performance",
    "mart_search_funnel",
    "mart_sponsored_opportunities",
    "mart_store_performance",
    "mart_user_cohort_retention",
})

_PARITY_TABLE_SPEC: Dict[str, Tuple[str, str, str, str]] = {
    "mart_order_fulfillment": ("staging.stg_orders", "order_date", "marts.mart_order_fulfillment", "order_date"),
    "mart_daily_revenue": ("staging.stg_orders", "order_date", "marts.mart_daily_revenue", "order_date"),
    "mart_daily_sales": ("staging.stg_orders", "order_date", "marts.mart_daily_sales", "sale_date"),
    "mart_store_performance": ("staging.stg_orders", "order_date", "marts.mart_store_performance", "order_date"),
    "mart_rider_performance": ("staging.stg_orders", "order_date", "marts.mart_rider_performance", "order_date"),
    "int_order_deliveries": ("staging.stg_orders", "order_date", "intermediate.int_order_deliveries", "order_date"),
}


def validate_model_name(model_name: Any) -> str:
    if not isinstance(model_name, str) or model_name not in ALLOWED_BACKFILL_MODELS:
        raise ValueError(f"Unsupported or invalid backfill model_name: {model_name!r}")
    return model_name


def validate_iso_date(value: Any, field_name: str) -> date:
    if not isinstance(value, str) or not _ISO_DATE_RE.fullmatch(value):
        raise ValueError(f"Invalid {field_name} (expected YYYY-MM-DD): {value!r}")
    try:
        return datetime.strptime(value, "%Y-%m-%d").date()
    except ValueError as exc:
        raise ValueError(f"Invalid calendar date for {field_name}: {value!r}") from exc


def validate_backfill_conf(conf: Dict[str, Any]) -> Tuple[str, str, str]:
    start_raw = conf.get("start_date", "2026-10-01")
    end_raw = conf.get("end_date", "2026-10-18")
    model_raw = conf.get("model_name", "mart_order_fulfillment")

    start_dt = validate_iso_date(start_raw, "start_date")
    end_dt = validate_iso_date(end_raw, "end_date")
    if start_dt > end_dt:
        raise ValueError(f"start_date ({start_raw}) must be <= end_date ({end_raw})")
    model_name = validate_model_name(model_raw)
    return model_name, start_dt.isoformat(), end_dt.isoformat()


def _prepare_backfill_params(**context):
    """Extract and validate parameters from DAG run configuration."""
    conf = (context.get("dag_run") and context["dag_run"].conf) or {}
    model_name, start_date, end_date = validate_backfill_conf(conf)

    logger.info(
        "Initializing historical backfill: model=%s, range=%s to %s",
        model_name,
        start_date,
        end_date,
    )
    context["ti"].xcom_push(key="start_date", value=start_date)
    context["ti"].xcom_push(key="end_date", value=end_date)
    context["ti"].xcom_push(key="model_name", value=model_name)
    return {"model_name": model_name, "start_date": start_date, "end_date": end_date}


def _run_dbt_backfill(**context):
    """Execute parameterized dbt backfill safely without shell interpolation."""
    ti = context["ti"]
    model_name, start_date, end_date = validate_backfill_conf({
        "model_name": ti.xcom_pull(task_ids="prepare_backfill_params", key="model_name"),
        "start_date": ti.xcom_pull(task_ids="prepare_backfill_params", key="start_date"),
        "end_date": ti.xcom_pull(task_ids="prepare_backfill_params", key="end_date"),
    })
    dbt_vars = json.dumps({
        "is_backfill": True,
        "start_date": start_date,
        "end_date": end_date,
    })
    cmd = [
        "dbt",
        "run",
        "--select",
        f"{model_name}+",
        "--vars",
        dbt_vars,
        "--profiles-dir",
        DBT_PROFILES_DIR,
        "--target",
        "prod",
    ]
    subprocess.run(
        cmd,
        cwd=DBT_PROJECT_DIR,
        shell=False,
        check=True,
        capture_output=True,
        text=True,
    )
    return {"model_name": model_name, "start_date": start_date, "end_date": end_date}


def _validate_backfill_parity(**context):
    """Validate row count parity between source partitions and backfilled mart."""
    ti = context["ti"]
    model_name, start_date, end_date = validate_backfill_conf({
        "model_name": ti.xcom_pull(task_ids="prepare_backfill_params", key="model_name"),
        "start_date": ti.xcom_pull(task_ids="prepare_backfill_params", key="start_date"),
        "end_date": ti.xcom_pull(task_ids="prepare_backfill_params", key="end_date"),
    })
    project_id = validate_bq_identifier(PROJECT_ID, "project")
    src_tbl, src_col, dst_tbl, dst_col = _PARITY_TABLE_SPEC.get(
        model_name,
        ("staging.stg_orders", "order_date", f"marts.{model_name}", "order_date"),
    )
    src_ref = validate_bq_identifier(f"{project_id}.{src_tbl}", "source_table")
    dst_ref = validate_bq_identifier(f"{project_id}.{dst_tbl}", "target_table")
    src_date_col = validate_bq_identifier(src_col, "source_date_column")
    dst_date_col = validate_bq_identifier(dst_col, "target_date_column")

    logger.info(
        "Validating row count parity for %s across %s -> %s",
        model_name,
        start_date,
        end_date,
    )

    client = bigquery.Client(project=project_id)
    job_config = bigquery.QueryJobConfig(
        query_parameters=[
            bigquery.ScalarQueryParameter("start_date", "DATE", start_date),
            bigquery.ScalarQueryParameter("end_date", "DATE", end_date),
        ]
    )
    query = f"""
        SELECT
            (SELECT COUNT(*) FROM `{src_ref}` WHERE {src_date_col} BETWEEN @start_date AND @end_date) AS source_count,
            (SELECT COUNT(*) FROM `{dst_ref}` WHERE {dst_date_col} BETWEEN @start_date AND @end_date) AS mart_count
    """
    rows = list(client.query(query, job_config=job_config).result())
    if not rows:
        raise ValueError("Parity query returned no rows")

    row = rows[0]
    source_count = int(row.source_count)
    mart_count = int(row.mart_count)
    if source_count == 0 and mart_count == 0:
        parity_ratio = 1.0
    elif source_count <= 0:
        parity_ratio = 0.0
    else:
        parity_ratio = mart_count / float(source_count)

    if parity_ratio < 0.999:
        raise ValueError(
            f"Backfill parity validation failed: source vs mart ratio {parity_ratio:.4f} < 0.999 "
            f"(source={source_count}, mart={mart_count})"
        )

    logger.info("Row count parity confirmed (ratio=%.4f)", parity_ratio)
    return {
        "parity_confirmed": True,
        "model": model_name,
        "parity_ratio": parity_ratio,
        "source_count": source_count,
        "mart_count": mart_count,
    }


with DAG(
    dag_id="historical_backfill",
    default_args=default_args,
    description="Automated date-partitioned dbt backfill orchestration DAG",
    schedule=None,
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["dbt", "backfill", "data-platform"],
    max_active_runs=1,
) as dag:
    prepare_params = PythonOperator(
        task_id="prepare_backfill_params",
        python_callable=_prepare_backfill_params,
        provide_context=True,
    )

    run_backfill = PythonOperator(
        task_id="run_dbt_backfill",
        python_callable=_run_dbt_backfill,
        provide_context=True,
        execution_timeout=timedelta(hours=2),
    )

    validate_parity = PythonOperator(
        task_id="validate_backfill_parity",
        python_callable=_validate_backfill_parity,
        provide_context=True,
    )

    prepare_params >> run_backfill >> validate_parity
