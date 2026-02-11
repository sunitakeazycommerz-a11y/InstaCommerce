-- Staging model for orders from CDC
WITH source AS (
    SELECT * FROM {{ source('cdc', 'orders') }}
),
renamed AS (
    SELECT
        id AS order_id,
        user_id,
        store_id,
        status,
        CAST(total_cents AS INT64) AS total_cents,
        CAST(delivery_fee_cents AS INT64) AS delivery_fee_cents,
        CAST(discount_cents AS INT64) AS discount_cents,
        coupon_code,
        item_count,
        channel,
        platform,
        TIMESTAMP(placed_at) AS placed_at,
        TIMESTAMP(confirmed_at) AS confirmed_at,
        TIMESTAMP(delivered_at) AS delivered_at,
        TIMESTAMP(cancelled_at) AS cancelled_at,
        DATE(placed_at) AS order_date,
        _cdc_updated_at
    FROM source
)
SELECT * FROM renamed
