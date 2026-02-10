from __future__ import annotations

from typing import TYPE_CHECKING, List

from data_platform_jobs.bigquery import get_client, run_queries

if TYPE_CHECKING:
    from data_platform_jobs.job_registry import JobContext


def run_staging_etl(context: JobContext) -> None:
    logger = context.logger
    settings = context.settings
    logger.info("Starting staging ETL run_id=%s", context.run_id)
    client = get_client(settings.project_id, logger)
    queries: List[str] = [
        f"""
        CREATE OR REPLACE TABLE `{settings.project_id}.{settings.bq_staging_dataset}.stg_orders` AS
        SELECT *
        FROM `{settings.project_id}.{settings.bq_raw_dataset}.orders`
        WHERE DATE(placed_at) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
        """,
        f"""
        CREATE OR REPLACE TABLE `{settings.project_id}.{settings.bq_staging_dataset}.stg_payments` AS
        SELECT *
        FROM `{settings.project_id}.{settings.bq_raw_dataset}.payments`
        WHERE DATE(created_at) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
        """,
    ]
    run_queries(client, queries, logger, context.dry_run)
    logger.info("Completed staging ETL run_id=%s", context.run_id)


def run_marts_etl(context: JobContext) -> None:
    logger = context.logger
    settings = context.settings
    logger.info("Starting marts ETL run_id=%s", context.run_id)
    client = get_client(settings.project_id, logger)
    query = f"""
    CREATE OR REPLACE TABLE `{settings.project_id}.{settings.bq_marts_dataset}.mart_daily_revenue` AS
    SELECT
        DATE(placed_at) AS order_date,
        store_id,
        COUNT(DISTINCT order_id) AS total_orders,
        SUM(total_cents) / 100.0 AS net_revenue
    FROM `{settings.project_id}.{settings.bq_staging_dataset}.stg_orders`
    GROUP BY 1, 2
    """
    run_queries(client, [query], logger, context.dry_run)
    logger.info("Completed marts ETL run_id=%s", context.run_id)


def run_data_quality_checks(context: JobContext) -> None:
    logger = context.logger
    settings = context.settings
    logger.info("Starting data quality checks run_id=%s", context.run_id)
    client = get_client(settings.project_id, logger)
    queries = [
        f"""
        SELECT COUNT(*) AS row_count
        FROM `{settings.project_id}.{settings.bq_staging_dataset}.stg_orders`
        """,
        f"""
        SELECT COUNT(*) AS row_count
        FROM `{settings.project_id}.{settings.bq_marts_dataset}.mart_daily_revenue`
        """,
    ]
    run_queries(client, queries, logger, context.dry_run)
    logger.info("Completed data quality checks run_id=%s", context.run_id)
