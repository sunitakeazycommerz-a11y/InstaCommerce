-- Join orders with delivery data for SLA analysis
WITH orders AS (
    SELECT * FROM {{ ref('stg_orders') }}
),
deliveries AS (
    SELECT * FROM {{ ref('stg_deliveries') }}
)
SELECT
    o.order_id,
    o.user_id,
    o.store_id,
    o.total_cents,
    o.placed_at,
    d.rider_id,
    d.assigned_at,
    d.picked_up_at,
    d.delivered_at,
    d.distance_meters,
    TIMESTAMP_DIFF(d.delivered_at, o.placed_at, MINUTE) AS total_delivery_minutes,
    TIMESTAMP_DIFF(d.delivered_at, o.placed_at, MINUTE) <= 10 AS sla_met,
    o.order_date
FROM orders o
LEFT JOIN deliveries d ON o.order_id = d.order_id
WHERE o.status IN ('DELIVERED', 'OUT_FOR_DELIVERY')
