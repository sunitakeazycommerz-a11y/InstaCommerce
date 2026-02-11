-- Staging model for products from CDC
WITH source AS (
    SELECT * FROM {{ source('cdc', 'products') }}
),
renamed AS (
    SELECT
        id AS product_id,
        name AS product_name,
        category,
        subcategory,
        brand,
        sku,
        CAST(price_cents AS INT64) AS price_cents,
        CAST(cost_cents AS INT64) AS cost_cents,
        unit,
        weight_grams,
        is_active,
        store_id,
        TIMESTAMP(created_at) AS created_at,
        TIMESTAMP(updated_at) AS updated_at,
        _cdc_updated_at
    FROM source
)
SELECT * FROM renamed
