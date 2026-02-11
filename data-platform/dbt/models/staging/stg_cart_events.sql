-- Staging model for cart events from CDC
WITH source AS (
    SELECT * FROM {{ source('cdc', 'cart_events') }}
),
renamed AS (
    SELECT
        id AS cart_event_id,
        user_id,
        session_id,
        product_id,
        store_id,
        event_type,
        CAST(quantity AS INT64) AS quantity,
        CAST(unit_price_cents AS INT64) AS unit_price_cents,
        platform,
        TIMESTAMP(event_at) AS event_at,
        DATE(event_at) AS event_date,
        _cdc_updated_at
    FROM source
)
SELECT * FROM renamed
