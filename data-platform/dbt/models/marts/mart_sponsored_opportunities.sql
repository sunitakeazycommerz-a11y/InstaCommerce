-- Sponsored placement opportunity scoring by store, category, and day
WITH searches AS (
    SELECT * FROM {{ ref('stg_searches') }}
),
products AS (
    SELECT * FROM {{ ref('stg_products') }}
),
cart_events AS (
    SELECT * FROM {{ ref('stg_cart_events') }}
),
orders AS (
    SELECT * FROM {{ ref('stg_orders') }}
),
daily_category_search_demand AS (
    SELECT
        s.search_date AS opportunity_date,
        s.store_id,
        COALESCE(p.category, 'UNCATEGORIZED') AS category,
        COUNT(DISTINCT s.search_id) AS total_searches,
        COUNT(DISTINCT CASE WHEN s.clicked_product_id IS NOT NULL THEN s.search_id END) AS clicked_searches,
        COUNTIF(s.results_count = 0) AS zero_result_searches
    FROM searches s
    LEFT JOIN products p
        ON s.clicked_product_id = p.product_id
        AND s.store_id = p.store_id
    GROUP BY s.search_date, s.store_id, COALESCE(p.category, 'UNCATEGORIZED')
),
daily_category_cart_intent AS (
    SELECT
        ce.event_date AS opportunity_date,
        ce.store_id,
        p.category,
        COUNT(DISTINCT ce.cart_event_id) AS add_to_cart_events,
        COUNT(DISTINCT ce.user_id) AS users_adding_to_cart
    FROM cart_events ce
    INNER JOIN products p
        ON ce.product_id = p.product_id
        AND ce.store_id = p.store_id
    WHERE ce.event_type = 'ADD'
    GROUP BY ce.event_date, ce.store_id, p.category
),
daily_category_orders AS (
    SELECT
        ce.event_date AS opportunity_date,
        ce.store_id,
        p.category,
        COUNT(DISTINCT o.order_id) AS delivered_orders,
        COUNT(DISTINCT o.user_id) AS ordering_users,
        -- Attributable GMV from add-to-cart events aligned to delivered orders on same day/user/store.
        SUM(ce.quantity * ce.unit_price_cents) / 100.0 AS attributable_gmv_usd
    FROM cart_events ce
    INNER JOIN products p
        ON ce.product_id = p.product_id
        AND ce.store_id = p.store_id
    INNER JOIN orders o
        ON ce.user_id = o.user_id
        AND ce.store_id = o.store_id
        AND ce.event_date = o.order_date
    WHERE ce.event_type = 'ADD'
        AND o.status = 'DELIVERED'
    GROUP BY ce.event_date, ce.store_id, p.category
),
category_product_health AS (
    SELECT
        pa.store_id,
        pa.category,
        AVG(pa.margin_usd) AS avg_margin_usd,
        AVG(pa.cart_to_purchase_pct) AS avg_cart_to_purchase_pct,
        AVG(pa.click_to_cart_pct) AS avg_click_to_cart_pct
    FROM {{ ref('mart_product_analytics') }} pa
    GROUP BY pa.store_id, pa.category
),
store_revenue AS (
    SELECT
        order_date AS opportunity_date,
        store_id,
        net_revenue_usd
    FROM {{ ref('mart_daily_revenue') }}
)
SELECT
    d.opportunity_date,
    d.store_id,
    d.category,
    d.total_searches,
    d.clicked_searches,
    d.zero_result_searches,
    COALESCE(SAFE_DIVIDE(d.clicked_searches, NULLIF(d.total_searches, 0)) * 100, 0) AS search_to_click_pct,
    COALESCE(ci.add_to_cart_events, 0) AS add_to_cart_events,
    COALESCE(ci.users_adding_to_cart, 0) AS users_adding_to_cart,
    COALESCE(co.delivered_orders, 0) AS delivered_orders,
    COALESCE(co.ordering_users, 0) AS ordering_users,
    COALESCE(SAFE_DIVIDE(COALESCE(ci.add_to_cart_events, 0), NULLIF(d.clicked_searches, 0)) * 100, 0) AS click_to_cart_pct,
    COALESCE(SAFE_DIVIDE(COALESCE(co.delivered_orders, 0), NULLIF(ci.add_to_cart_events, 0)) * 100, 0) AS cart_to_purchase_pct,
    COALESCE(co.attributable_gmv_usd, 0) AS attributable_gmv_usd,
    COALESCE(ph.avg_margin_usd, 0) AS avg_margin_usd,
    COALESCE(ph.avg_click_to_cart_pct, 0) AS product_click_to_cart_pct,
    COALESCE(ph.avg_cart_to_purchase_pct, 0) AS product_cart_to_purchase_pct,
    COALESCE(sr.net_revenue_usd, 0) AS store_revenue_usd,
    (
        SAFE_DIVIDE(d.total_searches, 1000.0) * 35
        + SAFE_DIVIDE(COALESCE(ci.add_to_cart_events, 0), 500.0) * 20
        + SAFE_DIVIDE(COALESCE(co.delivered_orders, 0), 300.0) * 20
        + SAFE_DIVIDE(COALESCE(ph.avg_margin_usd, 0), 10.0) * 15
        + (100 - COALESCE(SAFE_DIVIDE(d.zero_result_searches, NULLIF(d.total_searches, 0)) * 100, 0)) * 0.10
    ) AS monetization_readiness_score,
    CASE
        WHEN (
            SAFE_DIVIDE(d.total_searches, 1000.0) * 35
            + SAFE_DIVIDE(COALESCE(ci.add_to_cart_events, 0), 500.0) * 20
            + SAFE_DIVIDE(COALESCE(co.delivered_orders, 0), 300.0) * 20
            + SAFE_DIVIDE(COALESCE(ph.avg_margin_usd, 0), 10.0) * 15
            + (100 - COALESCE(SAFE_DIVIDE(d.zero_result_searches, NULLIF(d.total_searches, 0)) * 100, 0)) * 0.10
        ) >= 25 THEN 'HIGH'
        WHEN (
            SAFE_DIVIDE(d.total_searches, 1000.0) * 35
            + SAFE_DIVIDE(COALESCE(ci.add_to_cart_events, 0), 500.0) * 20
            + SAFE_DIVIDE(COALESCE(co.delivered_orders, 0), 300.0) * 20
            + SAFE_DIVIDE(COALESCE(ph.avg_margin_usd, 0), 10.0) * 15
            + (100 - COALESCE(SAFE_DIVIDE(d.zero_result_searches, NULLIF(d.total_searches, 0)) * 100, 0)) * 0.10
        ) >= 12 THEN 'MEDIUM'
        ELSE 'LOW'
    END AS opportunity_tier
FROM daily_category_search_demand d
LEFT JOIN daily_category_cart_intent ci
    ON d.opportunity_date = ci.opportunity_date
    AND d.store_id = ci.store_id
    AND d.category = ci.category
LEFT JOIN daily_category_orders co
    ON d.opportunity_date = co.opportunity_date
    AND d.store_id = co.store_id
    AND d.category = co.category
LEFT JOIN category_product_health ph
    ON d.store_id = ph.store_id
    AND d.category = ph.category
LEFT JOIN store_revenue sr
    ON d.opportunity_date = sr.opportunity_date
    AND d.store_id = sr.store_id
