# Location Ingestion Service - Low-Level Design

```mermaid
classDiagram
    class HTTPServer {
        -listener: net.Listener
        -mux: http.ServeMux
        +HandleIngest(w, r)
        +HandleHealth(w, r)
        +HandleMetrics(w, r)
    }

    class WebSocketHandler {
        -upgrader: websocket.Upgrader
        -service: IngestService
        +Handle(w, r)
        -handleConnection(conn)
    }

    class LocationRequest {
        -rider_id: string
        -latitude: float64
        -longitude: float64
        -accuracy_m: float64
        -timestamp_ms: int64
        -device_id: string
    }

    class LocationValidator {
        -max_latitude: float64
        -min_latitude: float64
        -max_longitude: float64
        -min_longitude: float64
        +Validate(req) error
        +NormalizeCoordinates(lat, lng)
    }

    class GeofenceChecker {
        -h3_resolution: int
        -zones: map[string]Zone
        +CheckGeofence(lat, lng) Zone
        +ComputeH3Cell(lat, lng) string
    }

    class IngestService {
        -redis: RedisClient
        -batcher: LocationBatcher
        -validator: LocationValidator
        -geofence: GeofenceChecker
        +Ingest(req) error
        -storePosition(req)
        -publishEvent(req)
    }

    class LatestPositionStore {
        -redis_client: RedisClient
        -prefix: string
        -ttl: Duration
        +Set(rider_id, location) error
        +Get(rider_id) Location
        +GetNearby(center, radius_m) []Location
    }

    class LocationBatcher {
        -topic: string
        -writer: KafkaProducer
        -batch_size: int
        -batch_timeout: Duration
        -accumulator: []LocationUpdate
        -mutex: sync.Mutex
        +Add(location)
        -flush()
    }

    class KafkaProducer {
        -writer: kafka.Writer
        -topic: string
        +WriteMessages(ctx, msgs) error
    }

    class MetricsCollector {
        -ingest_counter: Counter
        -valid_counter: Counter
        -invalid_counter: Counter
        -batch_size_histogram: Histogram
        -redis_latency_histogram: Histogram
        +RecordIngest(outcome)
        +RecordBatchSize(size)
        +RecordLatency(operation, latency)
    }

    class HTTPServer --> WebSocketHandler
    class HTTPServer --> IngestService
    class IngestService --> LocationValidator
    class IngestService --> GeofenceChecker
    class IngestService --> LatestPositionStore
    class IngestService --> LocationBatcher
    class LatestPositionStore --> KafkaProducer
    class LocationBatcher --> KafkaProducer
    class IngestService --> MetricsCollector
```

## Component Responsibilities

| Component | Responsibility |
|-----------|-----------------|
| **HTTPServer** | HTTP POST endpoint and health checks |
| **WebSocketHandler** | WebSocket connection upgrade and streaming |
| **LocationRequest** | Validated request model |
| **LocationValidator** | Coordinate bounds and precision checks |
| **GeofenceChecker** | H3-based zone detection (optional) |
| **IngestService** | Orchestrates validation, storage, and publishing |
| **LatestPositionStore** | Redis HSET operations with TTL |
| **LocationBatcher** | Kafka message batching by size/timeout |
| **KafkaProducer** | Kafka writer with retry logic |
| **MetricsCollector** | Prometheus metrics emission |
