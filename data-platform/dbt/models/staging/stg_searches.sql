-- Staging model for searches from CDC
WITH source AS (
    SELECT * FROM {{ source('cdc', 'searches') }}
),
renamed AS (
    SELECT
        id AS search_id,
        user_id,
        session_id,
        query AS search_query,
        CAST(results_count AS INT64) AS results_count,
        CAST(clicked_position AS INT64) AS clicked_position,
        clicked_product_id,
        store_id,
        platform,
        TIMESTAMP(searched_at) AS searched_at,
        DATE(searched_at) AS search_date,
        _cdc_updated_at
    FROM source
)
SELECT * FROM renamed
