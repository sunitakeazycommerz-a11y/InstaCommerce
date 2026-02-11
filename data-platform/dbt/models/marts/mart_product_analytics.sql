-- Product sales velocity, conversion, returns
WITH product_perf AS (
    SELECT * FROM {{ ref('int_product_performance') }}
),
orders AS (
    SELECT * FROM {{ ref('stg_orders') }}
),
inventory AS (
    SELECT * FROM {{ ref('stg_inventory_movements') }}
),
product_sales AS (
    SELECT
        ce.product_id,
        ce.store_id,
        COUNT(DISTINCT o.order_id) AS orders_containing_product,
        SUM(ce.quantity) AS total_units_sold,
        SUM(ce.quantity * ce.unit_price_cents) / 100.0 AS total_sales_usd,
        COUNT(DISTINCT o.user_id) AS unique_buyers,
        MIN(ce.event_at) AS first_sold_at,
        MAX(ce.event_at) AS last_sold_at,
        DATE_DIFF(CURRENT_DATE(), DATE(MIN(ce.event_at)), DAY) AS days_since_first_sale
    FROM {{ ref('stg_cart_events') }} ce
    INNER JOIN orders o ON ce.user_id = o.user_id
        AND ce.store_id = o.store_id
        AND DATE(ce.event_at) = o.order_date
    WHERE ce.event_type = 'ADD'
        AND o.status = 'DELIVERED'
    GROUP BY ce.product_id, ce.store_id
),
product_returns AS (
    SELECT
        product_id,
        store_id,
        SUM(quantity) AS returned_units
    FROM inventory
    WHERE movement_type = 'RETURN'
    GROUP BY product_id, store_id
)
SELECT
    pp.product_id,
    pp.product_name,
    pp.category,
    pp.subcategory,
    pp.brand,
    pp.price_cents / 100.0 AS price_usd,
    COALESCE(pp.cost_cents, 0) / 100.0 AS cost_usd,
    (pp.price_cents - COALESCE(pp.cost_cents, 0)) / 100.0 AS margin_usd,
    pp.store_id,
    COALESCE(ps.total_units_sold, 0) AS total_units_sold,
    COALESCE(ps.total_sales_usd, 0) AS total_sales_usd,
    COALESCE(ps.unique_buyers, 0) AS unique_buyers,
    COALESCE(ps.orders_containing_product, 0) AS orders_containing_product,
    SAFE_DIVIDE(ps.total_units_sold, GREATEST(ps.days_since_first_sale, 1)) AS daily_sales_velocity,
    pp.add_to_cart_count,
    pp.search_click_count,
    SAFE_DIVIDE(ps.orders_containing_product, NULLIF(pp.add_to_cart_count, 0)) * 100 AS cart_to_purchase_pct,
    SAFE_DIVIDE(pp.add_to_cart_count, NULLIF(pp.search_click_count, 0)) * 100 AS click_to_cart_pct,
    COALESCE(pr.returned_units, 0) AS returned_units,
    SAFE_DIVIDE(pr.returned_units, NULLIF(ps.total_units_sold, 0)) * 100 AS return_rate_pct,
    pp.estimated_stock
FROM product_perf pp
LEFT JOIN product_sales ps ON pp.product_id = ps.product_id AND pp.store_id = ps.store_id
LEFT JOIN product_returns pr ON pp.product_id = pr.product_id AND pp.store_id = pr.store_id
