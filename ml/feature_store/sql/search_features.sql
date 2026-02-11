-- Search Feature Computation Query
-- Source: analytics.fact_searches, analytics.fact_cart_events
-- Schedule: hourly batch
-- Target: Vertex AI Feature Store — search_features feature group

WITH search_stats AS (
    SELECT
        -- Normalize query: lowercase, trim, collapse whitespace
        LOWER(TRIM(REGEXP_REPLACE(query, r'\s+', ' '))) AS normalized_query,
        FARM_FINGERPRINT(LOWER(TRIM(REGEXP_REPLACE(query, r'\s+', ' ')))) AS query_hash,
        COUNT(*) AS query_popularity_7d,
        AVG(results_count) AS avg_results_count,
        COUNTIF(clicked_position IS NOT NULL) / NULLIF(COUNT(*), 0) AS click_through_rate,
        COUNTIF(purchased) / NULLIF(COUNT(*), 0) AS conversion_rate,
        AVG(CASE WHEN clicked_position IS NOT NULL THEN clicked_position END) AS avg_click_position,
        COUNTIF(results_count = 0) / NULLIF(COUNT(*), 0) AS zero_result_rate,
        COUNTIF(added_to_cart) / NULLIF(COUNT(*), 0) AS add_to_cart_rate,
        AVG(latency_ms) AS avg_latency_ms
    FROM `analytics.fact_searches`
    WHERE search_date >= DATE_SUB(CURRENT_DATE(), INTERVAL 7 DAY)
      AND query IS NOT NULL
      AND TRIM(query) != ''
    GROUP BY normalized_query, query_hash
),

category_intent AS (
    -- Determine primary category intent by finding the most-purchased category for each query
    SELECT
        FARM_FINGERPRINT(LOWER(TRIM(REGEXP_REPLACE(fs.query, r'\s+', ' ')))) AS query_hash,
        ARRAY_AGG(dp.category ORDER BY cnt DESC LIMIT 1)[OFFSET(0)] AS category_intent
    FROM (
        SELECT
            fs.query,
            dp.category,
            COUNT(*) AS cnt
        FROM `analytics.fact_searches` fs
        JOIN `analytics.fact_cart_events` ce
            ON fs.user_id = ce.user_id
            AND fs.search_date = ce.event_date
            AND ce.event_type = 'CHECKOUT'
        JOIN `analytics.dim_products` dp
            ON ce.product_id = dp.product_id
        WHERE fs.search_date >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY)
          AND fs.purchased = TRUE
        GROUP BY fs.query, dp.category
    ) sub
    JOIN `analytics.fact_searches` fs ON sub.query = fs.query
    JOIN `analytics.dim_products` dp ON sub.category = dp.category
    GROUP BY query_hash
),

reformulations AS (
    -- Detect query reformulations within the same session (searches by same user within 2 minutes)
    SELECT
        FARM_FINGERPRINT(LOWER(TRIM(REGEXP_REPLACE(query, r'\s+', ' ')))) AS query_hash,
        COUNTIF(has_followup) / NULLIF(COUNT(*), 0) AS reformulation_rate
    FROM (
        SELECT
            query,
            LEAD(search_date) OVER (PARTITION BY user_id ORDER BY search_date) IS NOT NULL
                AND LEAD(query) OVER (PARTITION BY user_id ORDER BY search_date) != query
                AS has_followup
        FROM `analytics.fact_searches`
        WHERE search_date >= DATE_SUB(CURRENT_DATE(), INTERVAL 7 DAY)
          AND user_id IS NOT NULL
    )
    GROUP BY query_hash
)

SELECT
    ss.query_hash,
    ss.query_popularity_7d,
    ss.avg_results_count,
    ss.click_through_rate,
    ss.conversion_rate,
    ss.avg_click_position,
    ci.category_intent,
    ss.zero_result_rate,
    ss.add_to_cart_rate,
    COALESCE(rf.reformulation_rate, 0.0) AS reformulation_rate,
    ss.avg_latency_ms,
    CURRENT_TIMESTAMP() AS feature_timestamp
FROM search_stats ss
LEFT JOIN category_intent ci USING (query_hash)
LEFT JOIN reformulations rf USING (query_hash)
