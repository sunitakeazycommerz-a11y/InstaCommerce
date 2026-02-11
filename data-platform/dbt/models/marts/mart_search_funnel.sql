-- Search → click → add-to-cart → purchase funnel
WITH searches AS (
    SELECT * FROM {{ ref('stg_searches') }}
),
cart_events AS (
    SELECT * FROM {{ ref('stg_cart_events') }}
),
orders AS (
    SELECT * FROM {{ ref('stg_orders') }}
),
daily_searches AS (
    SELECT
        search_date,
        store_id,
        platform,
        COUNT(DISTINCT search_id) AS total_searches,
        COUNT(DISTINCT user_id) AS unique_searchers,
        COUNTIF(clicked_product_id IS NOT NULL) AS searches_with_click,
        COUNT(DISTINCT CASE WHEN clicked_product_id IS NOT NULL THEN search_id END) AS clicked_searches,
        AVG(results_count) AS avg_results_count,
        COUNTIF(results_count = 0) AS zero_result_searches
    FROM searches
    GROUP BY search_date, store_id, platform
),
daily_cart_adds AS (
    SELECT
        event_date,
        store_id,
        platform,
        COUNT(DISTINCT CASE WHEN event_type = 'ADD' THEN cart_event_id END) AS add_to_cart_events,
        COUNT(DISTINCT CASE WHEN event_type = 'ADD' THEN user_id END) AS users_adding_to_cart
    FROM cart_events
    GROUP BY event_date, store_id, platform
),
daily_orders AS (
    SELECT
        order_date,
        store_id,
        platform,
        COUNT(DISTINCT order_id) AS completed_orders,
        COUNT(DISTINCT user_id) AS ordering_users
    FROM orders
    WHERE status = 'DELIVERED'
    GROUP BY order_date, store_id, platform
)
SELECT
    ds.search_date AS funnel_date,
    ds.store_id,
    ds.platform,
    ds.total_searches,
    ds.unique_searchers,
    ds.clicked_searches,
    ds.zero_result_searches,
    SAFE_DIVIDE(ds.clicked_searches, ds.total_searches) * 100 AS search_to_click_pct,
    COALESCE(dca.add_to_cart_events, 0) AS add_to_cart_events,
    COALESCE(dca.users_adding_to_cart, 0) AS users_adding_to_cart,
    SAFE_DIVIDE(dca.users_adding_to_cart, ds.unique_searchers) * 100 AS search_to_cart_pct,
    COALESCE(do2.completed_orders, 0) AS completed_orders,
    COALESCE(do2.ordering_users, 0) AS ordering_users,
    SAFE_DIVIDE(do2.ordering_users, ds.unique_searchers) * 100 AS search_to_purchase_pct,
    SAFE_DIVIDE(ds.zero_result_searches, ds.total_searches) * 100 AS zero_result_rate_pct
FROM daily_searches ds
LEFT JOIN daily_cart_adds dca
    ON ds.search_date = dca.event_date
    AND ds.store_id = dca.store_id
    AND ds.platform = dca.platform
LEFT JOIN daily_orders do2
    ON ds.search_date = do2.order_date
    AND ds.store_id = do2.store_id
    AND ds.platform = do2.platform
