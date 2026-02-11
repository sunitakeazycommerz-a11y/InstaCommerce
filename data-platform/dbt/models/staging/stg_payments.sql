-- Staging model for payments from CDC
WITH source AS (
    SELECT * FROM {{ source('cdc', 'payments') }}
),
renamed AS (
    SELECT
        id AS payment_id,
        order_id,
        user_id,
        payment_method,
        status,
        CAST(amount_cents AS INT64) AS amount_cents,
        currency,
        gateway,
        gateway_reference,
        TIMESTAMP(initiated_at) AS initiated_at,
        TIMESTAMP(completed_at) AS completed_at,
        TIMESTAMP(failed_at) AS failed_at,
        failure_reason,
        _cdc_updated_at
    FROM source
)
SELECT * FROM renamed
