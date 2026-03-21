# Stream Processor Service - Data Model

```mermaid
erDiagram
    DOMAIN_EVENT ||--o{ PROCESSOR_INPUT : routes_to
    PROCESSOR_INPUT ||--o{ DEDUPLICATION_RECORD : checks
    PROCESSOR_INPUT ||--o{ METRIC : extracts

    DOMAIN_EVENT {
        string event_id PK
        string event_type
        string aggregate_id
        int64 timestamp
        json data
        string topic
        int partition
        int64 offset
    }

    PROCESSOR_INPUT {
        string event_id FK
        string processor_type
        json extracted_data
        int64 processing_timestamp
    }

    DEDUPLICATION_RECORD {
        string topic PK
        int partition PK
        int64 offset PK
        timestamp processed_at
    }

    METRIC {
        string metric_name PK
        string metric_type
        float value
        int64 timestamp
        json labels
    }

    WINDOW_ENTRY {
        string window_key PK
        string metric_name
        int64 window_start
        int64 window_end
        float aggregated_value
        int count
    }

    SLA_EVALUATION {
        string zone_id PK
        int64 timestamp PK
        int delivered_count
        int total_orders
        float compliance_percent
        boolean breached
    }

    REDIS_METRIC {
        string redis_key PK
        json metric_value
        timestamp created_at
        timestamp expires_at
    }

    PROMETHEUS_RECORD {
        string metric_name PK
        string label_set
        float value
        timestamp recorded_at
    }

    ORDER_METRIC {
        string order_id PK
        string event_type
        int order_count
        float order_value
        int64 timestamp
    }

    PAYMENT_METRIC {
        string payment_id PK
        string event_type
        int payment_count
        float payment_value
        int64 timestamp
    }

    RIDER_METRIC {
        string rider_id PK
        string event_type
        int rider_count
        float acceptance_rate
        int64 timestamp
    }

    INVENTORY_METRIC {
        string sku_id PK
        string event_type
        int item_count
        float reserve_rate
        int64 timestamp
    }

    METRIC ||--o{ ORDER_METRIC : specializes_to
    METRIC ||--o{ PAYMENT_METRIC : specializes_to
    METRIC ||--o{ RIDER_METRIC : specializes_to
    METRIC ||--o{ INVENTORY_METRIC : specializes_to

    METRIC ||--o{ WINDOW_ENTRY : aggregates_to
    WINDOW_ENTRY ||--o{ SLA_EVALUATION : contributes_to
    METRIC ||--o{ REDIS_METRIC : syncs_to
    METRIC ||--o{ PROMETHEUS_RECORD : emits_to
```

## Key Entities

| Entity | Purpose |
|--------|---------|
| **DOMAIN_EVENT** | Kafka message from producer service |
| **PROCESSOR_INPUT** | Routed to appropriate processor |
| **DEDUPLICATION_RECORD** | Offset tracking for idempotency |
| **METRIC** | Extracted operational metric |
| **WINDOW_ENTRY** | Aggregated metric in time window |
| **SLA_EVALUATION** | Compliance check result per zone |
| **REDIS_METRIC** | TTL-bounded cache entry |
| **PROMETHEUS_RECORD** | Emitted Prometheus metric |
| **ORDER/PAYMENT/RIDER/INVENTORY_METRIC** | Domain-specific metrics |
