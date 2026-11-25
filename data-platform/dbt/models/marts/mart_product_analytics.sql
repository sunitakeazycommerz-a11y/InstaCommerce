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
cart_net_intent AS (
    SELECT
        ce.user_id,
        ce.store_id,
        ce.product_id,
        DATE(ce.event_at) AS event_date,
        MAX(ce.event_at) AS last_event_at,
        SUM(
            CASE
                WHEN ce.event_type = 'ADD' THEN ce.quantity
                WHEN ce.event_type = 'REMOVE' THEN -ce.quantity
                ELSE 0
            END
        ) AS net_quantity,
        MAX(ce.unit_price_cents) AS unit_price_cents
    FROM {{ ref('stg_cart_events') }} ce
    GROUP BY ce.user_id, ce.store_id, ce.product_id, DATE(ce.event_at)
    HAVING SUM(
        CASE
            WHEN ce.event_type = 'ADD' THEN ce.quantity
            WHEN ce.event_type = 'REMOVE' THEN -ce.quantity
            ELSE 0
        END
    ) > 0
),
attributed_cart_orders AS (
    SELECT
        cni.product_id,
        cni.store_id,
        o.order_id,
        o.user_id,
        cni.net_quantity,
        cni.unit_price_cents,
        cni.last_event_at,
        ROW_NUMBER() OVER (
            PARTITION BY cni.user_id, cni.store_id, cni.product_id, cni.event_date
            ORDER BY o.placed_at ASC, o.order_id ASC
        ) AS rn
    FROM cart_net_intent cni
    INNER JOIN orders o
        ON cni.user_id = o.user_id
        AND cni.store_id = o.store_id
        AND cni.event_date = o.order_date
        AND o.placed_at >= cni.last_event_at
    WHERE o.status = 'DELIVERED'
),
product_sales AS (
    SELECT
        aco.product_id,
        aco.store_id,
        COUNT(DISTINCT aco.order_id) AS orders_containing_product,
        SUM(aco.net_quantity) AS total_units_sold,
        SUM(aco.net_quantity * aco.unit_price_cents) / 100.0 AS total_sales_usd,
        COUNT(DISTINCT aco.user_id) AS unique_buyers,
        MIN(aco.last_event_at) AS first_sold_at,
        MAX(aco.last_event_at) AS last_sold_at,
        DATE_DIFF(CURRENT_DATE(), DATE(MIN(aco.last_event_at)), DAY) AS days_since_first_sale
    FROM attributed_cart_orders aco
    WHERE aco.rn = 1
    GROUP BY aco.product_id, aco.store_id
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
