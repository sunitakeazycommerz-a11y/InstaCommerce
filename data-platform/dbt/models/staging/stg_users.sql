-- Staging model for users from CDC
WITH source AS (
    SELECT * FROM {{ source('cdc', 'users') }}
),
renamed AS (
    SELECT
        id AS user_id,
        phone_number,
        email,
        first_name,
        last_name,
        city,
        zone,
        signup_channel,
        referral_code,
        referred_by_user_id,
        TIMESTAMP(created_at) AS created_at,
        TIMESTAMP(last_active_at) AS last_active_at,
        is_active,
        _cdc_updated_at
    FROM source
)
SELECT * FROM renamed
