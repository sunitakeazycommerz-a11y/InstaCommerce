-- Rider Feature Computation Query
-- Source: analytics.fact_deliveries, analytics.dim_riders, analytics.fact_orders
-- Schedule: per_location_ping (streaming) with batch fallback every 5 minutes
-- Target: Vertex AI Feature Store — rider_features feature group

WITH delivery_perf AS (
    SELECT
        rider_id,
        AVG(delivery_time_minutes) AS avg_delivery_time_7d
    FROM `analytics.fact_deliveries`
    WHERE delivered_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 7 DAY)
    GROUP BY rider_id
),

acceptance AS (
    SELECT
        rider_id,
        acceptance_rate
    FROM `analytics.dim_riders`
),

current_active AS (
    -- Orders assigned to rider that are not yet delivered
    SELECT
        fd.rider_id,
        COUNT(*) AS current_deliveries
    FROM `analytics.fact_deliveries` fd
    JOIN `analytics.fact_orders` fo ON fd.order_id = fo.order_id
    WHERE fo.status IN ('PACKING', 'PACKED', 'OUT_FOR_DELIVERY')
      AND fd.delivered_at IS NULL
    GROUP BY fd.rider_id
),

ratings AS (
    SELECT
        rider_id,
        AVG(rider_rating) AS avg_rating
    FROM `analytics.fact_deliveries`
    WHERE delivered_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY)
      AND rider_rating IS NOT NULL
    GROUP BY rider_id
),

zone_familiarity AS (
    -- Score based on number of deliveries in the rider's current zone vs total deliveries
    SELECT
        fd.rider_id,
        SAFE_DIVIDE(
            COUNTIF(ds.zone = dr.current_zone),
            COUNT(*)
        ) AS zone_familiarity_score
    FROM `analytics.fact_deliveries` fd
    JOIN `analytics.dim_stores` ds USING (store_id)
    JOIN (
        -- Rider's most recent zone from dim_riders or location updates
        SELECT rider_id, city AS current_zone FROM `analytics.dim_riders`
    ) dr USING (rider_id)
    WHERE fd.delivered_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY)
    GROUP BY fd.rider_id
),

consecutive AS (
    -- Count consecutive deliveries since last break
    -- A break is defined as a gap > 30 minutes between deliveries
    SELECT
        rider_id,
        COUNT(*) AS consecutive_deliveries_count,
        TIMESTAMP_DIFF(
            CURRENT_TIMESTAMP(),
            MAX(CASE WHEN gap_minutes > 30 THEN delivered_at END),
            MINUTE
        ) AS last_break_minutes_ago
    FROM (
        SELECT
            rider_id,
            delivered_at,
            TIMESTAMP_DIFF(
                delivered_at,
                LAG(delivered_at) OVER (PARTITION BY rider_id ORDER BY delivered_at),
                MINUTE
            ) AS gap_minutes
        FROM `analytics.fact_deliveries`
        WHERE delivery_date = CURRENT_DATE()
    )
    GROUP BY rider_id
),

earnings AS (
    SELECT
        rider_id,
        SUM(delivery_fee_cents + COALESCE(tip_cents, 0)) AS earnings_today_cents
    FROM `analytics.fact_deliveries`
    WHERE delivery_date = CURRENT_DATE()
    GROUP BY rider_id
),

vehicle AS (
    SELECT
        rider_id,
        vehicle_type
    FROM `analytics.dim_riders`
)

SELECT
    dr.rider_id,
    COALESCE(dp.avg_delivery_time_7d, dr.avg_delivery_minutes) AS avg_delivery_time_7d,
    COALESCE(acc.acceptance_rate, dr.acceptance_rate) AS acceptance_rate,
    COALESCE(ca.current_deliveries, 0) AS current_deliveries,
    COALESCE(rt.avg_rating, dr.avg_rating) AS avg_rating,
    COALESCE(zf.zone_familiarity_score, 0.0) AS zone_familiarity_score,
    COALESCE(con.consecutive_deliveries_count, 0) AS consecutive_deliveries_count,
    COALESCE(earn.earnings_today_cents, 0) AS earnings_today_cents,
    COALESCE(veh.vehicle_type, 'scooter') AS vehicle_type,
    COALESCE(con.last_break_minutes_ago, 0) AS last_break_minutes_ago,
    CURRENT_TIMESTAMP() AS feature_timestamp
FROM `analytics.dim_riders` dr
LEFT JOIN delivery_perf dp USING (rider_id)
LEFT JOIN acceptance acc USING (rider_id)
LEFT JOIN current_active ca USING (rider_id)
LEFT JOIN ratings rt USING (rider_id)
LEFT JOIN zone_familiarity zf USING (rider_id)
LEFT JOIN consecutive con USING (rider_id)
LEFT JOIN earnings earn USING (rider_id)
LEFT JOIN vehicle veh USING (rider_id)
