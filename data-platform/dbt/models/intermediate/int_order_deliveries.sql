-- Join orders with delivery data for SLA analysis
WITH orders AS (
    SELECT * FROM {{ ref('stg_orders') }}
),
deliveries AS (
    SELECT * FROM {{ ref('stg_deliveries') }}
),
users AS (
    SELECT user_id, zone FROM {{ ref('stg_users') }}
)
SELECT
    o.order_id,
    o.user_id,
    o.store_id,
    u.zone AS zone_id,
    o.status,
    o.total_cents,
    o.placed_at,
    d.rider_id,
    d.assigned_at,
    d.picked_up_at,
    d.delivered_at,
    d.distance_meters,
    TIMESTAMP_DIFF(d.delivered_at, o.placed_at, MINUTE) AS total_delivery_minutes,
    TIMESTAMP_DIFF(d.delivered_at, o.placed_at, MINUTE) <= 10 AS sla_met,
    o.order_date,
    GREATEST(o._cdc_updated_at, COALESCE(d._cdc_updated_at, o._cdc_updated_at)) AS updated_at
FROM orders o
LEFT JOIN deliveries d ON o.order_id = d.order_id
LEFT JOIN users u ON o.user_id = u.user_id
WHERE o.status IN ('DELIVERED', 'OUT_FOR_DELIVERY')
