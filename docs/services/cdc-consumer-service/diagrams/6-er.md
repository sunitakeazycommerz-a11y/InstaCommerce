# CDC Consumer Service - Data Model

```mermaid
erDiagram
    KAFKA_MESSAGE ||--o{ DEBEZIUM_ENVELOPE : contains
    KAFKA_MESSAGE ||--o{ MESSAGE_BATCH : grouped_in
    DEBEZIUM_ENVELOPE ||--o{ BQ_ROW : transforms_to
    MESSAGE_BATCH ||--o{ BQ_ROW : accumulates
    BQ_ROW ||--o{ BQ_INSERT_RESPONSE : returns

    KAFKA_MESSAGE {
        string topic
        int partition PK
        int64 offset PK
        bytes key
        bytes value
        int64 timestamp
        int headers_count
    }

    DEBEZIUM_ENVELOPE {
        string op
        json before
        json after
        json source
        int64 ts_ms
        int db_lsn
        string txId
    }

    MESSAGE_BATCH {
        string batch_id PK
        timestamp created_at
        int message_count
        bytes total_size
        timestamp submitted_at
    }

    BQ_ROW {
        string insert_id PK
        string topic FK
        int partition FK
        int64 offset FK
        string operation
        json before_values
        json after_values
        int64 timestamp_ms
        timestamp created_at
    }

    BQ_INSERT_RESPONSE {
        string batch_id FK
        int rows_inserted
        string error_message
        int retry_count
        timestamp response_at
    }

    RETRY_ATTEMPT {
        string batch_id FK
        int attempt_number PK
        int backoff_ms
        timestamp attempted_at
        string error_message
    }

    DLQ_MESSAGE {
        string dlq_id PK
        string original_batch_id FK
        bytes message_value
        string error_reason
        json error_context
        timestamp created_at
        int operator_reviewed
    }

    METRICS_RECORD {
        string batch_id FK
        int latency_ms
        int batch_size
        string result
        timestamp recorded_at
    }

    CONSUMER_LAG {
        string topic PK
        int partition PK
        int64 lag_offset
        timestamp measured_at
    }

    MESSAGE_BATCH ||--o{ RETRY_ATTEMPT : retries
    MESSAGE_BATCH ||--o{ DLQ_MESSAGE : dlq_writes
    MESSAGE_BATCH ||--o{ METRICS_RECORD : emits
    KAFKA_MESSAGE ||--o{ CONSUMER_LAG : tracks
```

## Key Entities

| Entity | Purpose |
|--------|---------|
| **KAFKA_MESSAGE** | Individual Kafka record from CDC topic |
| **DEBEZIUM_ENVELOPE** | Parsed Debezium message (op, before, after) |
| **MESSAGE_BATCH** | Accumulated messages ready for insert |
| **BQ_ROW** | Transformed row for BigQuery insert |
| **BQ_INSERT_RESPONSE** | Result of streaming insert operation |
| **RETRY_ATTEMPT** | Track exponential backoff per batch |
| **DLQ_MESSAGE** | Failed batch routed to dead-letter queue |
| **METRICS_RECORD** | Latency and size metrics per batch |
| **CONSUMER_LAG** | Per-topic/partition lag tracking |
