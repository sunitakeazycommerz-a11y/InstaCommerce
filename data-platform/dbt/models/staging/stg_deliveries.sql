-- Staging model for deliveries from CDC
WITH source AS (
    SELECT * FROM {{ source('cdc', 'deliveries') }}
),
renamed AS (
    SELECT
        id AS delivery_id,
        order_id,
        rider_id,
        status,
        CAST(distance_meters AS FLOAT64) AS distance_meters,
        CAST(estimated_minutes AS INT64) AS estimated_minutes,
        TIMESTAMP(assigned_at) AS assigned_at,
        TIMESTAMP(picked_up_at) AS picked_up_at,
        TIMESTAMP(delivered_at) AS delivered_at,
        TIMESTAMP(cancelled_at) AS cancelled_at,
        cancellation_reason,
        _cdc_updated_at
    FROM source
)
SELECT * FROM renamed
