-- Staging model for inventory movements from CDC
WITH source AS (
    SELECT * FROM {{ source('cdc', 'inventory_movements') }}
),
renamed AS (
    SELECT
        id AS movement_id,
        product_id,
        store_id,
        movement_type,
        CAST(quantity AS INT64) AS quantity,
        CAST(quantity_before AS INT64) AS quantity_before,
        CAST(quantity_after AS INT64) AS quantity_after,
        reason,
        reference_id,
        TIMESTAMP(created_at) AS created_at,
        _cdc_updated_at
    FROM source
)
SELECT * FROM renamed
