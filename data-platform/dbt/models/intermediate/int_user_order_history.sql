-- User-level order aggregations for CLV and segmentation
WITH orders AS (
    SELECT * FROM {{ ref('stg_orders') }}
),
user_metrics AS (
    SELECT
        user_id,
        COUNT(DISTINCT order_id) AS total_orders,
        SUM(total_cents) / 100.0 AS lifetime_revenue_usd,
        AVG(total_cents) / 100.0 AS avg_order_value_usd,
        MIN(placed_at) AS first_order_at,
        MAX(placed_at) AS last_order_at,
        DATE(MIN(placed_at)) AS cohort_date,
        DATE_TRUNC(DATE(MIN(placed_at)), WEEK) AS cohort_week,
        COUNT(DISTINCT order_date) AS distinct_order_days,
        TIMESTAMP_DIFF(MAX(placed_at), MIN(placed_at), DAY) AS customer_lifespan_days,
        COUNTIF(status = 'CANCELLED') AS cancelled_orders,
        COUNTIF(status = 'DELIVERED') AS delivered_orders
    FROM orders
    GROUP BY user_id
)
SELECT
    um.*,
    CASE
        WHEN total_orders >= 10 THEN 'power_user'
        WHEN total_orders >= 5 THEN 'regular'
        WHEN total_orders >= 2 THEN 'repeat'
        ELSE 'one_time'
    END AS user_segment,
    SAFE_DIVIDE(cancelled_orders, total_orders) * 100 AS cancellation_rate_pct
FROM user_metrics um
