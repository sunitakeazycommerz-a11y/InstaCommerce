-- Per-store P&L and operational metrics
WITH order_deliveries AS (
    SELECT * FROM {{ ref('int_order_deliveries') }}
),
payments AS (
    SELECT * FROM {{ ref('stg_payments') }}
),
store_orders AS (
    SELECT
        od.store_id,
        od.order_date,
        COUNT(DISTINCT od.order_id) AS total_orders,
        COUNT(DISTINCT od.user_id) AS unique_customers,
        SUM(od.total_cents) / 100.0 AS gross_revenue_usd,
        AVG(od.total_cents) / 100.0 AS avg_order_value_usd,
        AVG(od.total_delivery_minutes) AS avg_delivery_minutes,
        COUNTIF(od.sla_met) AS sla_met_orders,
        COUNT(*) AS total_delivery_orders,
        SAFE_DIVIDE(COUNTIF(od.sla_met), COUNT(*)) * 100 AS sla_10min_pct,
        AVG(od.distance_meters) AS avg_distance_meters
    FROM order_deliveries od
    GROUP BY od.store_id, od.order_date
),
store_payments AS (
    SELECT
        o.store_id,
        DATE(p.completed_at) AS payment_date,
        SUM(p.amount_cents) / 100.0 AS collected_revenue_usd,
        COUNTIF(p.status = 'FAILED') AS failed_payments,
        COUNTIF(p.status = 'REFUNDED') AS refunded_payments
    FROM payments p
    INNER JOIN {{ ref('stg_orders') }} o ON p.order_id = o.order_id
    GROUP BY o.store_id, DATE(p.completed_at)
)
SELECT
    so.store_id,
    so.order_date,
    so.total_orders,
    so.unique_customers,
    so.gross_revenue_usd,
    so.avg_order_value_usd,
    so.avg_delivery_minutes,
    so.sla_10min_pct,
    so.avg_distance_meters,
    COALESCE(sp.collected_revenue_usd, 0) AS collected_revenue_usd,
    COALESCE(sp.failed_payments, 0) AS failed_payments,
    COALESCE(sp.refunded_payments, 0) AS refunded_payments
FROM store_orders so
LEFT JOIN store_payments sp
    ON so.store_id = sp.store_id AND so.order_date = sp.payment_date
