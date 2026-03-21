# CDC Consumer Service - High-Level Design

```mermaid
flowchart TD
    subgraph Upstream["Upstream"]
        DB["PostgreSQL<br/>Source DBs"]
        DC["Debezium Connect<br/>(CDC Capture)"]
    end

    subgraph Kafka["Kafka Cluster"]
        CDC_TOPICS["CDC Topics<br/>(per-table)"]
        DLQ["cdc.dlq<br/>(Dead Letter)"]
    end

    subgraph CDC_CONSUMER["cdc-consumer-service :8104"]
        direction TB
        HTTP["HTTP Server"]
        CONFIG["Config<br/>(brokers, topics)"]
        READERS["Kafka Readers<br/>(per-topic)"]
        TRANSFORMER["Transform<br/>Debezium Envelope"]
        BATCHER["Batch<br/>Accumulator"]
        BQ_SINK["BigQuery<br/>Sink"]
        RETRY["Retry<br/>with Backoff"]
        DLQ_WRITER["DLQ Writer<br/>(fallback)"]
        METRICS["Metrics<br/>Collector"]
    end

    subgraph Sink["Data Sink"]
        BQ[(BigQuery<br/>cdc_events)]
        DLQ_TOPIC["Kafka DLQ<br/>(fallback)"]
    end

    subgraph Observability["Observability"]
        PROM["Prometheus<br/>:8104/metrics"]
        OTEL["OTLP Collector<br/>(traces)"]
    end

    DB -->|WAL| DC
    DC -->|Debezium envelope| CDC_TOPICS
    CDC_TOPICS --> READERS
    READERS --> TRANSFORMER
    TRANSFORMER --> BATCHER
    BATCHER --> BQ_SINK
    BQ_SINK --> BQ
    BQ_SINK -->|Failed batches| RETRY
    RETRY -->|Max retries exceeded| DLQ_WRITER
    DLQ_WRITER --> DLQ_TOPIC

    HTTP --> CONFIG
    CONFIG --> READERS
    READERS --> METRICS
    BQ_SINK --> METRICS
    DLQ_WRITER --> METRICS
    METRICS --> PROM
    METRICS --> OTEL
```

## Key Characteristics

- **CDC Source**: Debezium-managed Kafka topics with WAL-level capture
- **Stateless Design**: No persistent state; consumer group tracks offsets
- **Envelope Parsing**: Debezium after/before/source/op extraction
- **Batch Streaming**: Accumulate messages, batch insert to BigQuery
- **Idempotency**: insertID deduplication within ~1 minute BQ window
- **Dead-Letter Queue**: Failed batches with provenance for operator replay
- **Resilience**: Exponential backoff with max retries
- **Observability**: Per-topic lag gauges, batch latency histograms
