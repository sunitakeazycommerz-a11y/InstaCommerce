from __future__ import annotations

from typing import TYPE_CHECKING, List

from data_platform_jobs.bigquery import get_client, run_queries, validate_bq_identifier

if TYPE_CHECKING:
    from data_platform_jobs.job_registry import JobContext


def run_staging_etl(context: JobContext) -> None:
    logger = context.logger
    settings = context.settings
    project_id = validate_bq_identifier(settings.project_id, "project_id")
    staging_ds = validate_bq_identifier(settings.bq_staging_dataset, "bq_staging_dataset")
    raw_ds = validate_bq_identifier(settings.bq_raw_dataset, "bq_raw_dataset")
    logger.info("Starting staging ETL run_id=%s", context.run_id)
    client = get_client(project_id, logger)
    queries: List[str] = [
        f"""
        CREATE OR REPLACE TABLE `{project_id}.{staging_ds}.stg_orders` AS
        SELECT *
        FROM `{project_id}.{raw_ds}.orders`
        WHERE DATE(placed_at) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
        """,
        f"""
        CREATE OR REPLACE TABLE `{project_id}.{staging_ds}.stg_order_items` AS
        SELECT oi.*
        FROM `{project_id}.{raw_ds}.order_items` oi
        INNER JOIN `{project_id}.{staging_ds}.stg_orders` o
            ON oi.order_id = o.order_id
        """,
        f"""
        CREATE OR REPLACE TABLE `{project_id}.{staging_ds}.stg_payments` AS
        SELECT *
        FROM `{project_id}.{raw_ds}.payments`
        WHERE DATE(created_at) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
        """,
    ]
    run_queries(client, queries, logger, context.dry_run)
    logger.info("Completed staging ETL run_id=%s", context.run_id)


def run_marts_etl(context: JobContext) -> None:
    logger = context.logger
    settings = context.settings
    project_id = validate_bq_identifier(settings.project_id, "project_id")
    marts_ds = validate_bq_identifier(settings.bq_marts_dataset, "bq_marts_dataset")
    staging_ds = validate_bq_identifier(settings.bq_staging_dataset, "bq_staging_dataset")
    logger.info("Starting marts ETL run_id=%s", context.run_id)
    client = get_client(project_id, logger)
    query = f"""
    CREATE OR REPLACE TABLE `{project_id}.{marts_ds}.mart_daily_revenue` AS
    SELECT
        DATE(placed_at) AS order_date,
        store_id,
        COUNT(DISTINCT order_id) AS total_orders,
        SUM(total_cents) / 100.0 AS net_revenue
    FROM `{project_id}.{staging_ds}.stg_orders`
    GROUP BY 1, 2
    """
    run_queries(client, [query], logger, context.dry_run)
    logger.info("Completed marts ETL run_id=%s", context.run_id)


def run_data_quality_checks(context: JobContext) -> None:
    logger = context.logger
    settings = context.settings
    project_id = validate_bq_identifier(settings.project_id, "project_id")
    staging_ds = validate_bq_identifier(settings.bq_staging_dataset, "bq_staging_dataset")
    marts_ds = validate_bq_identifier(settings.bq_marts_dataset, "bq_marts_dataset")
    logger.info("Starting data quality checks run_id=%s", context.run_id)
    client = get_client(project_id, logger)
    queries = [
        f"""
        SELECT COUNT(*) AS row_count
        FROM `{project_id}.{staging_ds}.stg_orders`
        """,
        f"""
        SELECT COUNT(*) AS row_count
        FROM `{project_id}.{marts_ds}.mart_daily_revenue`
        """,
    ]
    run_queries(client, queries, logger, context.dry_run)
    logger.info("Completed data quality checks run_id=%s", context.run_id)
