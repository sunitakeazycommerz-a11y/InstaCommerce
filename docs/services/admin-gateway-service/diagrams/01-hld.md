# Admin Gateway - High-Level Design

## Architecture Overview

```mermaid
graph TB
    subgraph External["External Systems"]
        KAFKA["Kafka Topics"]
        DB["PostgreSQL"]
    end

    subgraph Service["Admin Gateway (3 Replicas)"]
        API["API Layer<br/>REST/gRPC"]
        CONSUMER["Kafka Consumer<br/>Event Processing"]
        ENGINE["Business Engine<br/>Core Logic"]
        CACHE["Redis Cache<br/>Distributed State"]
        DB_LOCAL["PostgreSQL DB<br/>Persistent Store"]
    end

    subgraph Observability["Observability"]
        METRICS["Prometheus Metrics"]
        TRACES["OpenTelemetry"]
        LOGS["Structured Logs"]
    end

    KAFKA --> CONSUMER
    CONSUMER --> ENGINE
    ENGINE --> CACHE
    CACHE --> DB_LOCAL
    ENGINE --> METRICS
    ENGINE --> TRACES
    ENGINE --> LOGS
```

## System Characteristics

| Feature | Value |
|---|---|
| Deployment | 3 replicas |
| Latency (p99) | <500ms |
| Availability | 99.9% |
| Event Throughput | 10K-50K/sec |

## Data Flow Layers

- **Ingestion**: Kafka consumer groups
- **Processing**: Business logic engine
- **Caching**: Redis distributed cache
- **Persistence**: PostgreSQL with Flyway migrations
- **Observability**: Prometheus + OpenTelemetry + Structured logs
