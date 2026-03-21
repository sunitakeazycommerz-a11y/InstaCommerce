# CDC Consumer Service - Message Processing Sequence

```mermaid
sequenceDiagram
    participant KR as Kafka Reader
    participant BATCH as Message Batch
    participant PARSE as Debezium Parser
    participant BQ as BigQuery Inserter
    participant RETRY as Retry Manager
    participant DLQ as DLQ Writer
    participant PROM as Prometheus

    KR ->> KR: Fetch message
    KR ->> BATCH: Add Message

    activate BATCH
    BATCH ->> BATCH: Check size and timeout
    alt Batch Not Ready
        BATCH -->> KR: Continue
    else Batch Ready
        BATCH -->> PARSE: Process Batch
    end
    deactivate BATCH

    activate PARSE
    PARSE ->> PARSE: Parse Debezium envelope
    loop For Each Message
        PARSE ->> PARSE: Extract op before after
        PARSE ->> PARSE: Build BQ Row
    end
    PARSE -->> BQ: Messages List
    deactivate PARSE

    activate BQ
    BQ ->> BQ: Generate insert IDs
    BQ ->> BQ: Stream Insert to BigQuery

    alt Insert Success
        BQ ->> KR: Commit Offsets
        activate KR
        KR ->> KR: Commit messages
        KR -->> BQ: Offsets Committed
        deactivate KR

        BQ ->> PROM: Observe batch latency
        BQ ->> PROM: Set consumer lag
        BQ -->> BATCH: Continue Next Batch

    else Insert Failure
        BQ ->> RETRY: Check max retries
        RETRY ->> RETRY: Check Retry Count

        alt Below Max
            RETRY ->> RETRY: Calculate Backoff
            RETRY ->> RETRY: Wait backoff
            RETRY -->> BQ: Retry Insert
            BQ ->> BQ: Stream Insert to BigQuery

        else Max Retries Exceeded
            RETRY -->> DLQ: Failed Batch

            activate DLQ
            DLQ ->> DLQ: Write to cdc dlq
            DLQ ->> DLQ: Include Error Metadata
            DLQ ->> PROM: Increment dlq counter
            DLQ ->> KR: Commit DLQ Offset
            activate KR
            KR -->> DLQ: DLQ Offset Committed
            deactivate KR
            DLQ -->> BATCH: Continue Next Batch
            deactivate DLQ
        end
    end
    deactivate BQ
```

## Sequence Patterns

- **Batch Accumulation**: Messages collected until size or timeout threshold
- **Envelope Parsing**: Debezium structure extraction for all messages
- **Row Transformation**: Convert per-message to BigQuery schema
- **Deduplication**: insertID provides idempotency within ~1 min window
- **Streaming Insert**: BigQuery batch API call
- **Retry Loop**: Exponential backoff on transient failures
- **DLQ Fallback**: Operator-managed replay after max retries
- **Offset Management**: Commit only after BQ or DLQ success
- **Metrics Emission**: Latency, lag, and DLQ counters per batch
