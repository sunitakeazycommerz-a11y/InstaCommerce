from __future__ import annotations

from typing import TYPE_CHECKING, List

from data_platform_jobs.bigquery import get_client, run_queries

if TYPE_CHECKING:
    from data_platform_jobs.job_registry import JobContext


def run_feature_refresh(context: JobContext) -> None:
    logger = context.logger
    settings = context.settings
    logger.info("Starting feature refresh run_id=%s", context.run_id)
    client = get_client(settings.project_id, logger)
    queries: List[str] = [
        f"""
        CREATE OR REPLACE TABLE `{settings.project_id}.{settings.bq_features_dataset}.user_features` AS
        SELECT
            user_id,
            COUNT(DISTINCT order_id) AS orders_30d,
            AVG(total_cents) / 100.0 AS avg_order_value_30d
        FROM `{settings.project_id}.{settings.bq_staging_dataset}.stg_orders`
        WHERE DATE(placed_at) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
        GROUP BY user_id
        """,
        f"""
        CREATE OR REPLACE TABLE `{settings.project_id}.{settings.bq_features_dataset}.product_features` AS
        SELECT
            product_id,
            COUNT(DISTINCT order_id) AS sales_30d,
            AVG(quantity) AS avg_units_per_order
        FROM `{settings.project_id}.{settings.bq_staging_dataset}.stg_orders`
        WHERE DATE(placed_at) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
        GROUP BY product_id
        """,
    ]
    run_queries(client, queries, logger, context.dry_run)
    logger.info("Completed feature refresh run_id=%s", context.run_id)
