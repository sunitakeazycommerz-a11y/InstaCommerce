-- Mart: A/B Testing Experiment Conversion & Statistical Significance Tracking
-- Computes exposure counts, conversions, conversion rates, and z-score statistical significance

WITH exposures AS (
    SELECT
        experiment_id,
        experiment_key,
        variant_name,
        user_id,
        MIN(exposed_at) AS first_exposed_at
    FROM {{ source('analytics', 'fact_experiment_exposures') }}
    GROUP BY experiment_id, experiment_key, variant_name, user_id
),

conversions AS (
    SELECT
        e.experiment_id,
        e.experiment_key,
        e.variant_name,
        e.user_id,
        COUNT(DISTINCT o.order_id) AS orders_count,
        SUM(o.total_cents) / 100.0 AS gmv_usd
    FROM exposures e
    JOIN {{ ref('stg_orders') }} o
        ON e.user_id = o.user_id
        AND o.placed_at >= e.first_exposed_at
        AND o.status = 'DELIVERED'
    GROUP BY e.experiment_id, e.experiment_key, e.variant_name, e.user_id
),

aggregated_metrics AS (
    SELECT
        e.experiment_id,
        e.experiment_key,
        e.variant_name,
        COUNT(DISTINCT e.user_id) AS total_users,
        COUNT(DISTINCT c.user_id) AS converted_users,
        COALESCE(SUM(c.orders_count), 0) AS total_orders,
        COALESCE(SUM(c.gmv_usd), 0.0) AS total_gmv_usd,
        SAFE_DIVIDE(COUNT(DISTINCT c.user_id), COUNT(DISTINCT e.user_id)) AS conversion_rate,
        SAFE_DIVIDE(SUM(c.gmv_usd), COUNT(DISTINCT e.user_id)) AS revenue_per_user_usd
    FROM exposures e
    LEFT JOIN conversions c USING (experiment_id, experiment_key, variant_name, user_id)
    GROUP BY e.experiment_id, e.experiment_key, e.variant_name
),

control_benchmark AS (
    SELECT
        experiment_key,
        conversion_rate AS control_conversion_rate,
        total_users AS control_users
    FROM aggregated_metrics
    WHERE variant_name = 'control'
)

SELECT
    m.experiment_id,
    m.experiment_key,
    m.variant_name,
    m.total_users,
    m.converted_users,
    m.total_orders,
    m.total_gmv_usd,
    m.conversion_rate,
    m.revenue_per_user_usd,
    cb.control_conversion_rate,
    m.conversion_rate - cb.control_conversion_rate AS conversion_rate_delta,
    SAFE_DIVIDE(m.conversion_rate - cb.control_conversion_rate, cb.control_conversion_rate) * 100.0 AS lift_pct,
    -- Two-proportion pooled z-score test
    CASE
        WHEN cb.control_conversion_rate IS NOT NULL AND m.variant_name != 'control' AND m.total_users > 30 AND cb.control_users > 30 THEN
            SAFE_DIVIDE(
                m.conversion_rate - cb.control_conversion_rate,
                SQRT(
                    SAFE_DIVIDE(
                        (m.converted_users + (cb.control_conversion_rate * cb.control_users)) *
                        (1 - SAFE_DIVIDE(m.converted_users + (cb.control_conversion_rate * cb.control_users), m.total_users + cb.control_users)),
                        m.total_users
                    ) +
                    SAFE_DIVIDE(
                        (m.converted_users + (cb.control_conversion_rate * cb.control_users)) *
                        (1 - SAFE_DIVIDE(m.converted_users + (cb.control_conversion_rate * cb.control_users), m.total_users + cb.control_users)),
                        cb.control_users
                    )
                )
            )
        ELSE NULL
    END AS z_score,
    CURRENT_TIMESTAMP() AS updated_at
FROM aggregated_metrics m
LEFT JOIN control_benchmark cb USING (experiment_key)
