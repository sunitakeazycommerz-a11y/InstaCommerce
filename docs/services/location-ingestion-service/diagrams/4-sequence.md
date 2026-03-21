# Location Ingestion Service - Ingestion Sequence

```mermaid
sequenceDiagram
    participant App as Rider App
    participant HTTP as HTTP Handler
    participant VAL as LocationValidator
    participant GEO as GeofenceChecker
    participant REDIS as Redis Store
    participant BATCH as LocationBatcher
    participant KAFKA as Kafka Producer
    participant PROM as Prometheus

    App ->> HTTP: POST /ingest/location
    activate HTTP

    HTTP ->> HTTP: Parse JSON
    HTTP ->> VAL: Validate(coordinates)
    activate VAL
    VAL ->> VAL: Check bounds
    VAL -->> HTTP: Valid
    deactivate VAL

    HTTP ->> GEO: CheckGeofence(lat, lng)
    activate GEO
    GEO ->> GEO: Compute H3 cell
    GEO -->> HTTP: Zone info (optional)
    deactivate GEO

    HTTP ->> REDIS: Set(rider:location:{id}, position)
    activate REDIS
    REDIS ->> REDIS: HSET with TTL
    REDIS -->> HTTP: OK
    deactivate REDIS

    HTTP ->> BATCH: Add(location)
    activate BATCH
    BATCH ->> BATCH: Check size/timeout
    alt Batch Not Full
        BATCH -->> HTTP: Buffering
    else Batch Ready
        BATCH ->> KAFKA: WriteMessages(batch)
        activate KAFKA
        KAFKA ->> KAFKA: Send to broker
        KAFKA -->> BATCH: OK
        deactivate KAFKA
        BATCH -->> HTTP: Batch Flushed
    end
    deactivate BATCH

    HTTP ->> PROM: ingest_counter++
    HTTP ->> PROM: latency_histogram.observe(elapsed)
    activate PROM
    PROM -->> HTTP: void
    deactivate PROM

    HTTP -->> App: 200 OK {timestamp, zone}
    deactivate HTTP
```

## Sequence Patterns

- **Synchronous Validation**: Coordinate bounds check before storage
- **Optional Geofencing**: Zone detection does not block response
- **Redis Set**: Latest position overwrite (no transaction needed)
- **Kafka Batching**: Messages accumulated, async flush
- **Fire-and-Forget**: Response sent before Kafka confirmation
- **Metrics Async**: Prometheus emission non-blocking
