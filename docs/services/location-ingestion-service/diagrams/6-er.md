# Location Ingestion Service - Data Model

```mermaid
erDiagram
    RIDER_APP ||--o{ LOCATION_REQUEST : sends
    LOCATION_REQUEST ||--o{ VALIDATED_LOCATION : transforms
    VALIDATED_LOCATION ||--o{ REDIS_ENTRY : stores
    VALIDATED_LOCATION ||--o{ KAFKA_MESSAGE : publishes
    KAFKA_MESSAGE ||--o{ LOCATION_UPDATE_EVENT : represents

    RIDER_APP {
        string rider_id PK
        string device_id
        string app_version
    }

    LOCATION_REQUEST {
        string request_id PK
        string rider_id FK
        float latitude
        float longitude
        float accuracy_m
        int64 timestamp_ms
        string device_id
    }

    VALIDATED_LOCATION {
        string rider_id PK
        float latitude_normalized
        float longitude_normalized
        float accuracy_m
        int64 timestamp_ms
        string h3_cell
        string zone_name
        timestamp ingestion_time
    }

    REDIS_ENTRY {
        string key PK
        json location_value
        timestamp created_at
        timestamp expires_at
    }

    KAFKA_MESSAGE {
        string topic
        string key
        int partition
        int64 offset
        json value
        timestamp created_at
    }

    LOCATION_UPDATE_EVENT {
        string rider_id PK
        float latitude
        float longitude
        float accuracy_m
        int64 timestamp_ms
        string h3_cell
        timestamp event_timestamp
    }

    GEOFENCE_ZONE {
        string zone_id PK
        string zone_name
        json h3_cells
        json vertices
        timestamp created_at
    }

    H3_CELL {
        string h3_token PK
        int resolution
        json geometry
    }

    BATCH_ACCUMULATOR {
        string batch_id PK
        int message_count
        int total_bytes
        timestamp created_at
        timestamp flush_time
    }

    INGESTION_METRICS {
        int64 timestamp_ms PK
        int ingest_count
        int valid_count
        int invalid_count
        float avg_latency_ms
        float redis_latency_p99_ms
    }

    LOCATION_REQUEST ||--o{ GEOFENCE_ZONE : may_belong_to
    VALIDATED_LOCATION ||--o{ H3_CELL : contains
    KAFKA_MESSAGE ||--o{ BATCH_ACCUMULATOR : grouped_in
    LOCATION_REQUEST ||--o{ INGESTION_METRICS : contributes_to
```

## Key Entities

| Entity | Purpose |
|--------|---------|
| **RIDER_APP** | Mobile app instance with device ID |
| **LOCATION_REQUEST** | Incoming GPS update |
| **VALIDATED_LOCATION** | Sanitized and normalized location |
| **REDIS_ENTRY** | Latest position cache entry |
| **KAFKA_MESSAGE** | Persisted event in topic |
| **LOCATION_UPDATE_EVENT** | Domain event for subscribers |
| **GEOFENCE_ZONE** | Pre-defined geographic zone |
| **H3_CELL** | Hierarchical spatial index |
| **BATCH_ACCUMULATOR** | Kafka batching metadata |
| **INGESTION_METRICS** | Latency and throughput metrics |
