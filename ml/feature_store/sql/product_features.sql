-- Product Feature Computation Query
-- Source: analytics.fact_orders, analytics.fact_cart_events, analytics.fact_inventory_movements,
--         analytics.fact_searches, analytics.dim_products, analytics.fact_payments
-- Schedule: hourly batch
-- Target: Vertex AI Feature Store — product_features feature group

WITH sales_velocity AS (
    SELECT
        ce.product_id,
        COUNT(DISTINCT ce.event_id) / 7.0 AS sales_velocity_7d
    FROM `analytics.fact_cart_events` ce
    JOIN `analytics.fact_orders` fo ON ce.cart_id = fo.order_id
    WHERE ce.event_type = 'CHECKOUT'
      AND fo.status = 'DELIVERED'
      AND fo.placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 7 DAY)
    GROUP BY ce.product_id
),

conversion AS (
    SELECT
        product_id,
        COUNTIF(purchased) / NULLIF(COUNT(*), 0) AS conversion_rate_30d
    FROM (
        SELECT
            fs.search_id,
            ce.product_id,
            fs.purchased
        FROM `analytics.fact_searches` fs
        JOIN `analytics.fact_cart_events` ce
            ON fs.user_id = ce.user_id
            AND fs.search_date = ce.event_date
        WHERE fs.search_date >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
          AND ce.product_id IS NOT NULL
    )
    GROUP BY product_id
),

product_dim AS (
    SELECT
        product_id,
        avg_rating,
        total_reviews AS review_count,
        unit_price_cents AS price_cents
    FROM `analytics.dim_products`
),

price_comp AS (
    SELECT
        dp.product_id,
        dp.unit_price_cents / NULLIF(cat_avg.avg_price_cents, 0) AS price_competitiveness
    FROM `analytics.dim_products` dp
    JOIN (
        SELECT
            category,
            AVG(unit_price_cents) AS avg_price_cents
        FROM `analytics.dim_products`
        GROUP BY category
    ) cat_avg ON dp.category = cat_avg.category
),

stockout AS (
    SELECT
        product_id,
        COUNTIF(running_stock = 0) / NULLIF(COUNT(DISTINCT movement_date), 0) AS stockout_frequency_30d
    FROM `analytics.fact_inventory_movements`
    WHERE movement_date >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
    GROUP BY product_id
),

returns AS (
    SELECT
        ce.product_id,
        COUNTIF(fp.refund_amount_cents > 0) / NULLIF(COUNT(*), 0) AS return_rate
    FROM `analytics.fact_cart_events` ce
    JOIN `analytics.fact_orders` fo ON ce.cart_id = fo.order_id
    JOIN `analytics.fact_payments` fp ON fo.order_id = fp.order_id
    WHERE fo.placed_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 90 DAY)
      AND ce.event_type = 'CHECKOUT'
    GROUP BY ce.product_id
),

margins AS (
    SELECT
        im.product_id,
        (dp.unit_price_cents - AVG(im.cost_cents)) / NULLIF(dp.unit_price_cents, 0) * 100.0 AS margin_percent
    FROM `analytics.fact_inventory_movements` im
    JOIN `analytics.dim_products` dp ON im.product_id = dp.product_id
    WHERE im.movement_type = 'RECEIVED'
      AND im.movement_date >= DATE_SUB(CURRENT_DATE(), INTERVAL 90 DAY)
    GROUP BY im.product_id, dp.unit_price_cents
),

substitution AS (
    -- Placeholder: assumes analytics.fact_substitutions exists
    -- Tracks when a product is offered and accepted as a substitution
    SELECT
        substitute_product_id AS product_id,
        COUNTIF(accepted) / NULLIF(COUNT(*), 0) AS substitution_acceptance_rate
    FROM `analytics.fact_substitutions`
    WHERE created_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 90 DAY)
    GROUP BY substitute_product_id
),

co_purchase AS (
    SELECT
        a.product_id,
        ARRAY_AGG(b.product_id ORDER BY pair_count DESC LIMIT 5) AS co_purchase_top5
    FROM (
        SELECT
            a.product_id AS product_id,
            b.product_id AS co_product_id,
            COUNT(*) AS pair_count
        FROM `analytics.fact_cart_events` a
        JOIN `analytics.fact_cart_events` b
            ON a.cart_id = b.cart_id
            AND a.product_id < b.product_id
            AND a.event_type = 'CHECKOUT'
            AND b.event_type = 'CHECKOUT'
        WHERE a.event_date >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
        GROUP BY a.product_id, b.product_id
    ) paired
    CROSS JOIN UNNEST([paired.co_product_id]) AS b(product_id)
    GROUP BY a.product_id
)

-- Note: embedding_vector is computed offline by the item2vec training pipeline
-- and ingested separately via ml/train/item2vec/. Not computed in this SQL.

SELECT
    pd.product_id,
    COALESCE(sv.sales_velocity_7d, 0.0) AS sales_velocity_7d,
    COALESCE(cv.conversion_rate_30d, 0.0) AS conversion_rate_30d,
    pd.avg_rating,
    pd.review_count,
    pd.price_cents,
    COALESCE(pc.price_competitiveness, 1.0) AS price_competitiveness,
    COALESCE(so.stockout_frequency_30d, 0.0) AS stockout_frequency_30d,
    COALESCE(rt.return_rate, 0.0) AS return_rate,
    COALESCE(mg.margin_percent, 0.0) AS margin_percent,
    COALESCE(sub.substitution_acceptance_rate, 0.0) AS substitution_acceptance_rate,
    cp.co_purchase_top5,
    CURRENT_TIMESTAMP() AS feature_timestamp
FROM product_dim pd
LEFT JOIN sales_velocity sv USING (product_id)
LEFT JOIN conversion cv USING (product_id)
LEFT JOIN price_comp pc USING (product_id)
LEFT JOIN stockout so USING (product_id)
LEFT JOIN returns rt USING (product_id)
LEFT JOIN margins mg USING (product_id)
LEFT JOIN substitution sub USING (product_id)
LEFT JOIN co_purchase cp USING (product_id)
