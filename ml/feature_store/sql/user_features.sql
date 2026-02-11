-- User Feature Computation Query
-- Source: analytics.fact_orders, analytics.fact_payments, analytics.fact_deliveries, analytics.dim_users
-- Schedule: per_event (triggered on order events), batch refresh every 4 hours
-- Target: Vertex AI Feature Store — user_features feature group

WITH order_stats AS (
    SELECT
        user_id,
        COUNT(*) FILTER (WHERE placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY))
            AS order_count_30d,
        AVG(CASE WHEN placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY) THEN total_cents END)
            AS avg_basket_cents,
        DATE_DIFF(CURRENT_DATE(), MAX(DATE(placed_at)), DAY)
            AS days_since_last_order,
        SUM(total_cents) AS lifetime_gmv_cents,
        COUNTIF(status = 'CANCELLED' AND placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 90 DAY))
            / NULLIF(COUNT(*) FILTER (WHERE placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 90 DAY)), 0)
            AS cancellation_rate_90d
    FROM `analytics.fact_orders`
    GROUP BY user_id
),

order_frequency AS (
    SELECT
        user_id,
        AVG(days_between) AS avg_order_frequency_days
    FROM (
        SELECT
            user_id,
            DATE_DIFF(
                DATE(placed_at),
                LAG(DATE(placed_at)) OVER (PARTITION BY user_id ORDER BY placed_at),
                DAY
            ) AS days_between
        FROM `analytics.fact_orders`
        WHERE placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 90 DAY)
          AND status NOT IN ('CANCELLED', 'FAILED')
    )
    WHERE days_between IS NOT NULL
    GROUP BY user_id
),

preferred_cats AS (
    SELECT
        o.user_id,
        ARRAY_AGG(p.category ORDER BY cnt DESC LIMIT 5) AS preferred_categories
    FROM (
        SELECT
            fo.user_id,
            dp.category,
            COUNT(*) AS cnt
        FROM `analytics.fact_orders` fo
        JOIN `analytics.fact_cart_events` ce
            ON fo.order_id = ce.cart_id AND ce.event_type = 'CHECKOUT'
        JOIN `analytics.dim_products` dp
            ON ce.product_id = dp.product_id
        WHERE fo.placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 90 DAY)
        GROUP BY fo.user_id, dp.category
    ) o
    CROSS JOIN UNNEST([o.category]) AS cat
    GROUP BY o.user_id
),

payment_prefs AS (
    SELECT
        user_id,
        ARRAY_AGG(payment_method ORDER BY method_count DESC LIMIT 1)[OFFSET(0)] AS preferred_payment_method
    FROM (
        SELECT
            user_id,
            method AS payment_method,
            COUNT(*) AS method_count
        FROM `analytics.fact_payments`
        WHERE status = 'CAPTURED'
          AND payment_date >= DATE_SUB(CURRENT_DATE(), INTERVAL 90 DAY)
        GROUP BY user_id, method
    )
    GROUP BY user_id
),

refund_stats AS (
    SELECT
        user_id,
        COUNTIF(refund_amount_cents > 0)
            / NULLIF(COUNT(*), 0) AS refund_rate_90d
    FROM `analytics.fact_payments`
    WHERE payment_date >= DATE_SUB(CURRENT_DATE(), INTERVAL 90 DAY)
    GROUP BY user_id
),

delivery_ratings AS (
    SELECT
        fo.user_id,
        AVG(fd.rider_rating) AS avg_rating_given
    FROM `analytics.fact_orders` fo
    JOIN `analytics.fact_deliveries` fd ON fo.order_id = fd.order_id
    WHERE fd.rider_rating IS NOT NULL
    GROUP BY fo.user_id
),

delivery_slots AS (
    SELECT
        user_id,
        ARRAY_AGG(delivery_slot ORDER BY slot_count DESC LIMIT 1)[OFFSET(0)] AS preferred_delivery_slot
    FROM (
        SELECT
            user_id,
            FORMAT_TIMESTAMP('%H:00', placed_at) AS delivery_slot,
            COUNT(*) AS slot_count
        FROM `analytics.fact_orders`
        WHERE placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 90 DAY)
          AND status = 'DELIVERED'
        GROUP BY user_id, FORMAT_TIMESTAMP('%H:00', placed_at)
    )
    GROUP BY user_id
),

clv AS (
    SELECT
        user_id,
        churn_risk_score,
        clv_segment
    FROM `analytics.dim_users`
),

support AS (
    -- Placeholder: assumes analytics.fact_support_tickets exists
    -- Replace with actual support table reference when available
    SELECT
        user_id,
        COUNT(*) AS support_tickets_30d
    FROM `analytics.fact_support_tickets`
    WHERE created_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY)
    GROUP BY user_id
)

SELECT
    os.user_id,
    os.order_count_30d,
    os.avg_basket_cents,
    pc.preferred_categories,
    os.days_since_last_order,
    os.lifetime_gmv_cents,
    ofreq.avg_order_frequency_days,
    pp.preferred_payment_method,
    clv.churn_risk_score,
    clv.clv_segment,
    ds.preferred_delivery_slot,
    os.cancellation_rate_90d,
    rs.refund_rate_90d,
    dr.avg_rating_given,
    COALESCE(st.support_tickets_30d, 0) AS support_tickets_30d,
    CURRENT_TIMESTAMP() AS feature_timestamp
FROM order_stats os
LEFT JOIN order_frequency ofreq USING (user_id)
LEFT JOIN preferred_cats pc USING (user_id)
LEFT JOIN payment_prefs pp USING (user_id)
LEFT JOIN refund_stats rs USING (user_id)
LEFT JOIN delivery_ratings dr USING (user_id)
LEFT JOIN delivery_slots ds USING (user_id)
LEFT JOIN clv USING (user_id)
LEFT JOIN support st USING (user_id)
