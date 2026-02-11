-- Product-level metrics for search ranking and inventory
WITH products AS (
    SELECT * FROM {{ ref('stg_products') }}
),
cart_events AS (
    SELECT * FROM {{ ref('stg_cart_events') }}
),
searches AS (
    SELECT * FROM {{ ref('stg_searches') }}
),
inventory AS (
    SELECT * FROM {{ ref('stg_inventory_movements') }}
),
product_cart_metrics AS (
    SELECT
        product_id,
        COUNTIF(event_type = 'ADD') AS add_to_cart_count,
        COUNTIF(event_type = 'REMOVE') AS remove_from_cart_count,
        COUNT(DISTINCT user_id) AS unique_cart_users
    FROM cart_events
    GROUP BY product_id
),
product_search_clicks AS (
    SELECT
        clicked_product_id AS product_id,
        COUNT(*) AS search_click_count,
        AVG(clicked_position) AS avg_click_position
    FROM searches
    WHERE clicked_product_id IS NOT NULL
    GROUP BY clicked_product_id
),
product_inventory AS (
    SELECT
        product_id,
        store_id,
        SUM(CASE WHEN movement_type = 'INBOUND' THEN quantity ELSE 0 END) AS total_inbound,
        SUM(CASE WHEN movement_type = 'OUTBOUND' THEN quantity ELSE 0 END) AS total_outbound,
        SUM(CASE WHEN movement_type = 'WRITE_OFF' THEN quantity ELSE 0 END) AS total_write_off
    FROM inventory
    GROUP BY product_id, store_id
)
SELECT
    p.product_id,
    p.product_name,
    p.category,
    p.subcategory,
    p.brand,
    p.price_cents,
    p.cost_cents,
    p.store_id,
    COALESCE(pcm.add_to_cart_count, 0) AS add_to_cart_count,
    COALESCE(pcm.remove_from_cart_count, 0) AS remove_from_cart_count,
    COALESCE(pcm.unique_cart_users, 0) AS unique_cart_users,
    COALESCE(psc.search_click_count, 0) AS search_click_count,
    psc.avg_click_position,
    COALESCE(pi.total_inbound, 0) AS total_inbound,
    COALESCE(pi.total_outbound, 0) AS total_outbound,
    COALESCE(pi.total_write_off, 0) AS total_write_off,
    COALESCE(pi.total_inbound, 0) - COALESCE(pi.total_outbound, 0) - COALESCE(pi.total_write_off, 0) AS estimated_stock
FROM products p
LEFT JOIN product_cart_metrics pcm ON p.product_id = pcm.product_id
LEFT JOIN product_search_clicks psc ON p.product_id = psc.product_id
LEFT JOIN product_inventory pi ON p.product_id = pi.product_id AND p.store_id = pi.store_id
WHERE p.is_active = TRUE
