# Location Ingestion Service - Location State Machine

```mermaid
stateDiagram-v2
    [*] --> Received

    Received --> Parsed: json_parse
    Parsed --> Validated: validate_coordinates
    Validated --> Geofenced: check_geofence

    Parsed -->|Parse Error| ParseFail
    ParseFail --> Error400: malformed_json

    Validated -->|Invalid Bounds| BoundsFail
    BoundsFail --> Error400

    Validated -->|Invalid Precision| PrecisionFail
    PrecisionFail --> Normalized: normalize

    Geofenced --> Normalized: zone_checked

    Normalized --> RedisStore: position_normalized
    RedisStore --> RedisStored: redis_set_ok

    RedisStore -->|Redis Error| RedisError
    RedisError --> RedisRetry: async_retry

    RedisStored --> KafkaBatch: queued
    KafkaBatch --> BatchAccum: buffering

    BatchAccum --> BatchFull{size_or_timeout?}
    BatchFull -->|Not Ready| BatchAccum
    BatchFull -->|Ready| KafkaFlush: flush_batch

    KafkaFlush --> KafkaSent: sent_ok
    KafkaFlush -->|Kafka Error| KafkaRetry: backoff_retry

    KafkaRetry --> KafkaFlush

    RedisStored --> MetricsRec: counted
    KafkaSent --> MetricsRec
    MetricsRec --> Responded: response_200

    Responded --> [*]

    Error400 --> Response400
    Response400 --> [*]

    RedisRetry --> [*]

    note right of Validated
        Latitude: [-90, 90]
        Longitude: [-180, 180]
        Precision: configurable
    end note

    note right of Geofenced
        H3 cell computation
        Zone lookup (optional)
    end note

    note right of RedisStored
        Latest position overwrite
        With TTL (default 1 hour)
    end note

    note right of KafkaBatch
        Accumulate messages
        until size or timeout
    end note
```

## State Transitions

- **Received→Parsed**: JSON parsing initiated
- **Parsed→Validated**: Coordinate bounds checking
- **Validated→Geofenced**: H3 cell computation (optional)
- **Geofenced→Normalized**: Precision normalization
- **Normalized→RedisStore**: Redis HSET operation
- **RedisStore→RedisStored**: Successful storage
- **RedisStored→KafkaBatch**: Message queued for batching
- **KafkaBatch→BatchAccum**: Accumulated in memory
- **BatchAccum→KafkaFlush**: Batch size or timeout reached
- **KafkaFlush→KafkaSent**: Kafka broker acknowledged
- **KafkaSent→MetricsRec**: Metrics recorded
- **MetricsRec→Responded**: 200 OK response sent
