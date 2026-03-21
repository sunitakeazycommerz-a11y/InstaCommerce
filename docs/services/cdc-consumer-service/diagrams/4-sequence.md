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

    KR ->> KR: FetchMessage()
    KR ->> BATCH: Add Message

    activate BATCH
    BATCH ->> BATCH: Check Size & Timeout
    alt Batch Not Ready
        BATCH -->> KR: Continue
    else Batch Ready
        BATCH -->> PARSE: Process Batch
    end
    deactivate BATCH

    activate PARSE
    PARSE ->> PARSE: Parse Debezium Envelope
    loop For Each Message
        PARSE ->> PARSE: Extract op, before, after
        PARSE ->> PARSE: Build BQ Row
    end
    PARSE -->> BQ: Messages List
    deactivate PARSE

    activate BQ
    BQ ->> BQ: Generate insertIDs
    BQ ->> BQ: Stream Insert to BigQuery

    alt Insert Success
        BQ ->> KR: Commit Offsets
        activate KR
        KR ->> KR: CommitMessages()
        KR -->> BQ: Offsets Committed
        deactivate KR

        BQ ->> PROM: batch_latency.observe()
        BQ ->> PROM: consumer_lag.set()
        BQ -->> BATCH: Continue Next Batch

    else Insert Failure
        BQ ->> RETRY: Max Retries?
        activate RETRY
        RETRY ->> RETRY: Check Retry Count

        alt Below Max
            RETRY ->> RETRY: Calculate Backoff
            RETRY ->> RETRY: Wait(backoff)
            RETRY -->> BQ: Retry Insert
            deactivate RETRY
            BQ ->> BQ: Stream Insert to BigQuery

        else Max Retries Exceeded
            RETRY -->> DLQ: Failed Batch
            deactivate RETRY

            activate DLQ
            DLQ ->> DLQ: Write to cdc.dlq
            DLQ ->> DLQ: Include Error Metadata
            DLQ ->> PROM: dlq_counter++
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
