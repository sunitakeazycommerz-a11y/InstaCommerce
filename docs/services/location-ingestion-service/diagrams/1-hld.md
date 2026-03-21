# Location Ingestion Service - High-Level Design

```mermaid
flowchart LR
    subgraph Riders["Rider Layer"]
        RA["Rider Mobile App<br/>(GPS every ~5s)"]
    end

    subgraph LIS["location-ingestion-service :8105"]
        direction TB
        HTTP["POST /ingest/location<br/>Single ingestion"]
        WS["WS /ingest/ws<br/>WebSocket stream"]
        VAL["Validate +<br/>Normalize"]
        H3GEO["H3 Geofence<br/>Enrichment<br/>(optional)"]
        REDIS_W["Redis HSET<br/>rider:location:{id}"]
        KAFKA["Kafka Batcher<br/>rider.location.updates"]
    end

    subgraph Caches["Caches"]
        REDIS[("Redis<br/>Latest Positions")]
    end

    subgraph Events["Event Log"]
        KAFKA_TOPIC["Kafka Topic<br/>rider.location.updates"]
    end

    subgraph Downstream["Downstream Consumers"]
        SP["stream-processor-service<br/>(analytics)"]
        DO["dispatch-optimizer-service<br/>(routing)"]
        RE["routing-eta-service<br/>(ETA calc)"]
    end

    subgraph Observability["Observability"]
        PROM["Prometheus<br/>:8105/metrics"]
    end

    RA -->|"HTTP POST"| HTTP
    RA -->|"WebSocket"| WS

    HTTP --> VAL
    WS --> VAL
    VAL --> H3GEO
    H3GEO --> REDIS_W
    REDIS_W --> REDIS
    REDIS_W --> KAFKA
    KAFKA --> KAFKA_TOPIC

    KAFKA_TOPIC --> SP
    REDIS --> DO
    REDIS --> RE
    KAFKA --> PROM
    VAL --> PROM
```

## Key Characteristics

- **High-Throughput Ingestion**: HTTP POST and WebSocket endpoints
- **Ephemeral State**: Redis latest-position cache (no persistent DB)
- **Validation & Normalization**: Coordinate bounds, precision checks
- **Optional H3 Geofencing**: In-process zone detection (log-only)
- **Dual Output**: Redis for query-time access, Kafka for durable event log
- **Stateless Design**: No message ordering guarantees, but fire-and-forget
- **Configurable TTL**: Redis keys with automatic expiration
- **Observability**: Kafka batching metrics, validation counters
