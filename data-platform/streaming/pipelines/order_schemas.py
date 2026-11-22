"""BigQuery table schemas for the order events streaming pipeline."""

VOLUME_TABLE_SCHEMA = {
    "fields": [
        {"name": "store_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "order_count", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "gmv_cents", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "window_timestamp", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}

SLA_TABLE_SCHEMA = {
    "fields": [
        {"name": "zone_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "total_orders", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "within_sla", "type": "INTEGER", "mode": "REQUIRED"},
        {"name": "compliance_rate", "type": "FLOAT", "mode": "REQUIRED"},
        {"name": "window_timestamp", "type": "TIMESTAMP", "mode": "REQUIRED"},
    ]
}

LATE_EVENT_TABLE_SCHEMA = {
    "fields": [
        {"name": "order_id", "type": "STRING", "mode": "REQUIRED"},
        {"name": "event_type", "type": "STRING", "mode": "REQUIRED"},
        {"name": "store_id", "type": "STRING", "mode": "NULLABLE"},
        {"name": "zone_id", "type": "STRING", "mode": "NULLABLE"},
        {"name": "total_cents", "type": "INTEGER", "mode": "NULLABLE"},
        {"name": "event_time", "type": "STRING", "mode": "NULLABLE"},
        {"name": "received_at", "type": "TIMESTAMP", "mode": "REQUIRED"},
        {"name": "lateness_seconds", "type": "FLOAT", "mode": "REQUIRED"},
    ]
}
