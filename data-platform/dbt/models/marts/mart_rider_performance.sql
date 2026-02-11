-- Rider-level performance metrics
WITH deliveries AS (
    SELECT * FROM {{ ref('stg_deliveries') }}
),
orders AS (
    SELECT * FROM {{ ref('stg_orders') }}
),
rider_deliveries AS (
    SELECT
        d.rider_id,
        o.order_date,
        COUNT(DISTINCT d.delivery_id) AS total_deliveries,
        COUNTIF(d.status = 'DELIVERED') AS completed_deliveries,
        COUNTIF(d.status = 'CANCELLED') AS cancelled_deliveries,
        AVG(TIMESTAMP_DIFF(d.delivered_at, o.placed_at, MINUTE)) AS avg_total_delivery_minutes,
        AVG(TIMESTAMP_DIFF(d.picked_up_at, d.assigned_at, MINUTE)) AS avg_pickup_minutes,
        AVG(TIMESTAMP_DIFF(d.delivered_at, d.picked_up_at, MINUTE)) AS avg_dropoff_minutes,
        AVG(d.distance_meters) AS avg_distance_meters,
        SUM(d.distance_meters) AS total_distance_meters,
        COUNTIF(TIMESTAMP_DIFF(d.delivered_at, o.placed_at, MINUTE) <= 10) AS sla_met_count,
        SAFE_DIVIDE(
            COUNTIF(TIMESTAMP_DIFF(d.delivered_at, o.placed_at, MINUTE) <= 10),
            COUNTIF(d.status = 'DELIVERED')
        ) * 100 AS sla_10min_pct,
        MIN(d.assigned_at) AS first_delivery_at,
        MAX(d.delivered_at) AS last_delivery_at
    FROM deliveries d
    INNER JOIN orders o ON d.order_id = o.order_id
    GROUP BY d.rider_id, o.order_date
)
SELECT
    rider_id,
    order_date,
    total_deliveries,
    completed_deliveries,
    cancelled_deliveries,
    SAFE_DIVIDE(cancelled_deliveries, total_deliveries) * 100 AS cancellation_rate_pct,
    avg_total_delivery_minutes,
    avg_pickup_minutes,
    avg_dropoff_minutes,
    avg_distance_meters,
    total_distance_meters / 1000.0 AS total_distance_km,
    sla_met_count,
    sla_10min_pct,
    first_delivery_at,
    last_delivery_at,
    TIMESTAMP_DIFF(last_delivery_at, first_delivery_at, HOUR) AS active_hours
FROM rider_deliveries
