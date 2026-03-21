# CDC Consumer Service - Low-Level Design

```mermaid
classDiagram
    class Config {
        -port: string
        -log_level: LogLevel
        -kafka_brokers: []string
        -kafka_group_id: string
        -kafka_topics: []string
        -kafka_dlq_topic: string
        -batch_size: int
        -batch_timeout: Duration
        -bq_project: string
        -bq_dataset: string
        -bq_table: string
        -bq_insert_timeout: Duration
        -bq_max_retries: int
        -bq_base_backoff: Duration
        -bq_max_backoff: Duration
    }

    class KafkaReader {
        -reader: kafka.Reader
        -topic: string
        -partition: int
        +ReadMessage(ctx) kafka.Message
        +CommitMessages(ctx, messages)
    }

    class MessageBatch {
        -messages: []kafka.Message
        -size: int
        -created_at: timestamp
        -timeout: Duration
        +IsFull() bool
        +IsStale() bool
    }

    class DebeziumEnvelope {
        -op: string
        -before: JSON
        -after: JSON
        -source: JSON
        -ts_ms: int64
    }

    class BQTransformer {
        -envelope: DebeziumEnvelope
        +ToInsertID(topic, partition, offset) string
        +ToBQRow() map[string]bigquery.Value
        +ExtractTimestamp() int64
    }

    class BigQueryInserter {
        -project: string
        -dataset: string
        -table: string
        -timeout: Duration
        +StreamInsert(ctx, rows) error
        +ExecuteWithRetry(ctx, backoff) error
    }

    class DLQWriter {
        -writer: kafka.Writer
        -topic: string
        +WriteFailedBatch(ctx, messages, error) error
    }

    class MetricsCollector {
        -consumer_lag: GaugeVec
        -batch_latency: Histogram
        -batch_size: Histogram
        -dlq_count: Counter
        +RecordConsumerLag(topic, partition, lag)
        +RecordBatchLatency(latency)
        +RecordDLQWrite()
    }

    class HTTPServer {
        -port: string
        +Health() string
        +Ready() string
        +Metrics() string
    }

    class ReadinessProbe {
        -ready: atomic.Bool
        +Ready() bool
        +SetReady(bool)
    }

    Config --> KafkaReader
    KafkaReader --> MessageBatch
    MessageBatch --> DebeziumEnvelope
    DebeziumEnvelope --> BQTransformer
    BQTransformer --> BigQueryInserter
    BigQueryInserter --> MetricsCollector
    MessageBatch --> DLQWriter
    HTTPServer --> MetricsCollector
    ReadinessProbe --> HTTPServer
```

## Component Responsibilities

| Component | Responsibility |
|-----------|-----------------|
| **Config** | Load and validate environment configuration |
| **KafkaReader** | Consume messages per topic with group management |
| **MessageBatch** | Accumulate messages by size or timeout |
| **DebeziumEnvelope** | Parse Debezium message structure |
| **BQTransformer** | Convert envelope to BigQuery-compatible row |
| **BigQueryInserter** | Batch streaming insert with retries |
| **DLQWriter** | Route failed batches to Kafka DLQ |
| **MetricsCollector** | Prometheus metrics emission |
| **HTTPServer** | Health, readiness, and metrics endpoints |
| **ReadinessProbe** | Track service readiness state |
