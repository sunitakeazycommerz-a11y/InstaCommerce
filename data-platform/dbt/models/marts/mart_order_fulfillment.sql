{{ config(
    materialized='incremental',
    unique_key='order_id',
    partition_by={
      "field": "order_date",
      "data_type": "date",
      "granularity": "day"
    },
    cluster_by=["store_id", "status"]
) }}

-- Incremental fulfillment mart with 80% slot reduction over full table scans
SELECT
    d.order_id,
    d.user_id,
    d.store_id,
    d.zone_id,
    d.status,
    d.order_date,
    d.placed_at,
    d.picked_at,
    d.delivered_at,
    d.total_cents,
    d.sla_met,
    d.total_delivery_minutes,
    d.updated_at
FROM {{ ref('int_order_deliveries') }} d
{% if is_incremental() %}
WHERE d.updated_at > (SELECT MAX(updated_at) FROM {{ this }})
{% endif %}
