# CDC Consumer Service - Message State Machine

```mermaid
stateDiagram-v2
    [*] --> Fetched

    Fetched --> Buffered: add_to_batch
    Buffered --> WaitForBatch: batch_not_ready
    WaitForBatch --> Buffered: timeout_or_size
    WaitForBatch --> Parsed: timeout_triggered

    Buffered --> Parsed: batch_ready

    Parsed --> DebeziumParsed: parse_envelope
    DebeziumParsed --> RowTransformed: extract_op_before_after
    RowTransformed --> Batched: create_bq_row

    Batched --> Inserting: all_rows_ready
    Inserting --> BQSuccess: insert_ok

    Inserting --> BQFail: insert_error
    BQFail --> RetryCheck: check_retry_count
    RetryCheck --> Backoff: retry_count_ok
    Backoff --> Inserting: retry_after_backoff

    RetryCheck --> DLQWait: max_retries_exceeded
    DLQWait --> DLQWriting: queue_for_dlq

    BQSuccess --> OffsetCommit: success
    DLQWriting --> OffsetCommit: dlq_written

    OffsetCommit --> Committed: offsets_committed
    Committed --> MetricsRecord: batch_complete
    MetricsRecord --> [*]

    note right of Buffered
        Accumulate messages
        by size or timeout
    end note

    note right of DebeziumParsed
        Extract Debezium
        op, before, after, source
    end note

    note right of Inserting
        BigQuery streaming
        insert with insertID
    end note

    note right of Backoff
        Exponential backoff:
        2^n * base (capped at max)
    end note

    note right of DLQWriting
        Failed batch written
        to cdc.dlq for replay
    end note

    note right of OffsetCommit
        Commit only after BQ or DLQ success
        ensures at-least-once delivery
    end note
```

## State Transitions

- **Fetched→Buffered**: Message added to accumulator
- **Buffered→Parsed**: Batch size or timeout reached
- **Parsed→DebeziumParsed**: JSON envelope extracted
- **DebeziumParsed→RowTransformed**: Operation and values extracted
- **RowTransformed→Batched**: BigQuery row created for all messages
- **Batched→Inserting**: Batch ready for BigQuery insert
- **Inserting→BQSuccess**: Insert succeeds (idempotent within window)
- **Inserting→BQFail→RetryCheck**: Transient failure, check retry count
- **RetryCheck→Backoff**: Below max retries, exponential backoff
- **RetryCheck→DLQWait**: Max retries exceeded, queue for DLQ
- **BQSuccess/DLQWriting→OffsetCommit**: Commit Kafka offsets
- **OffsetCommit→MetricsRecord**: Record latency and lag
