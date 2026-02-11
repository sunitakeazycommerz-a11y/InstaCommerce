-- Store Feature Computation Query
-- Source: analytics.fact_orders, analytics.fact_deliveries, analytics.fact_inventory_movements, analytics.dim_stores
-- Schedule: every 5 minutes (near real-time)
-- Target: Vertex AI Feature Store — store_features feature group

WITH recent_orders AS (
    SELECT
        store_id,
        AVG(
            TIMESTAMP_DIFF(picked_at, confirmed_at, MINUTE)
        ) AS avg_prep_time_1h,
        COUNT(*) AS current_hour_orders
    FROM `analytics.fact_orders`
    WHERE placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 1 HOUR)
      AND status NOT IN ('CANCELLED', 'FAILED')
    GROUP BY store_id
),

store_capacity AS (
    SELECT
        ds.store_id,
        -- Utilization = current hour orders / estimated hourly capacity
        -- Capacity estimated as: picker_count * avg_picks_per_hour (configurable, default 8)
        SAFE_DIVIDE(ro.current_hour_orders, ds.total_skus * 0.01) * 100.0 AS utilization_pct
    FROM `analytics.dim_stores` ds
    LEFT JOIN recent_orders ro USING (store_id)
),

stockouts AS (
    SELECT
        store_id,
        COUNTIF(running_stock = 0) AS stockout_count_today
    FROM `analytics.fact_inventory_movements`
    WHERE movement_date = CURRENT_DATE()
    GROUP BY store_id
),

active_pickers AS (
    -- Placeholder: assumes analytics.dim_store_staff or a real-time staffing table
    -- In production, this comes from the warehouse-service real-time state
    SELECT
        store_id,
        COUNT(*) AS picker_count_active
    FROM `analytics.dim_store_staff`
    WHERE shift_date = CURRENT_DATE()
      AND status = 'ACTIVE'
      AND CURRENT_TIMESTAMP() BETWEEN shift_start AND shift_end
    GROUP BY store_id
),

queue AS (
    SELECT
        store_id,
        COUNT(*) AS queue_depth
    FROM `analytics.fact_orders`
    WHERE status IN ('PLACED', 'PACKING')
      AND placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 2 HOUR)
    GROUP BY store_id
),

delivery_perf AS (
    SELECT
        store_id,
        AVG(delivery_time_minutes) AS avg_delivery_time_1h,
        COUNTIF(sla_met) / NULLIF(COUNT(*), 0) AS sla_compliance_1h
    FROM `analytics.fact_deliveries`
    WHERE delivered_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 1 HOUR)
    GROUP BY store_id
)

SELECT
    ds.store_id,
    COALESCE(ro.avg_prep_time_1h, ds.avg_prep_time_minutes) AS avg_prep_time_1h,
    COALESCE(ro.current_hour_orders, 0) AS current_hour_orders,
    COALESCE(sc.utilization_pct, 0.0) AS utilization_pct,
    COALESCE(so.stockout_count_today, 0) AS stockout_count_today,
    COALESCE(ap.picker_count_active, 0) AS picker_count_active,
    COALESCE(q.queue_depth, 0) AS queue_depth,
    COALESCE(dp.avg_delivery_time_1h, ds.avg_prep_time_minutes + 5.0) AS avg_delivery_time_1h,
    COALESCE(dp.sla_compliance_1h, 1.0) AS sla_compliance_1h,
    CURRENT_TIMESTAMP() AS feature_timestamp
FROM `analytics.dim_stores` ds
LEFT JOIN recent_orders ro USING (store_id)
LEFT JOIN store_capacity sc USING (store_id)
LEFT JOIN stockouts so USING (store_id)
LEFT JOIN active_pickers ap USING (store_id)
LEFT JOIN queue q USING (store_id)
LEFT JOIN delivery_perf dp USING (store_id)
