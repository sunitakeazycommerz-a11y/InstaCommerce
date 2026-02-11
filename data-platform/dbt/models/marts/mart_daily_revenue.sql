-- Daily revenue by store, zone, city
SELECT
    order_date,
    store_id,
    COUNT(DISTINCT order_id) AS total_orders,
    COUNT(DISTINCT user_id) AS unique_customers,
    SUM(total_cents) / 100.0 AS net_revenue_usd,
    AVG(total_cents) / 100.0 AS avg_order_value_usd,
    COUNTIF(sla_met) / NULLIF(COUNT(*), 0) * 100 AS sla_10min_pct,
    AVG(total_delivery_minutes) AS avg_delivery_minutes
FROM {{ ref('int_order_deliveries') }}
GROUP BY 1, 2
