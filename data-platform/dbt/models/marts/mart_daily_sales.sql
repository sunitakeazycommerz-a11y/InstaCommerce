{{ config(
    materialized='table',
    partition_by={
      "field": "sale_date",
      "data_type": "date",
      "granularity": "day"
    },
    cluster_by=["store_id"]
) }}

-- Daily sales mart aggregating store revenues and SLA on-time metrics
SELECT
    d.order_date AS sale_date,
    d.store_id,
    COUNT(DISTINCT d.order_id) AS order_count,
    COUNT(DISTINCT d.user_id) AS customer_count,
    ROUND(SUM(d.total_cents) / 100.0, 2) AS total_revenue_usd,
    ROUND(AVG(d.total_cents) / 100.0, 2) AS avg_order_value_usd,
    COUNTIF(d.sla_met) AS on_time_orders,
    ROUND(COUNTIF(d.sla_met) * 100.0 / NULLIF(COUNT(*), 0), 2) AS on_time_pct,
    CURRENT_TIMESTAMP() AS calculated_at
FROM {{ ref('int_order_deliveries') }} d
GROUP BY 1, 2
