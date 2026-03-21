# CDC Consumer Service - Message Processing Flowchart

```mermaid
flowchart TD
    A["Fetch Messages<br/>from Kafka"] --> B["Parse Topic<br/>& Partition"]
    B --> C{Valid<br/>Message?}
    C -->|Malformed| D["Log Warning"]
    D --> E["Skip to Next"]

    C -->|Valid| F["Decode Debezium<br/>Envelope"]
    F --> G{Envelope<br/>Valid?}
    G -->|Invalid| H["Log Error"]
    H --> I["Queue for DLQ"]

    G -->|Valid| J["Extract Operation<br/>(CREATE/UPDATE/DELETE)"]
    J --> K["Build BigQuery<br/>Row"]
    K --> L["Add to Batch"]
    L --> M{Batch<br/>Ready?}
    M -->|Not Ready| N["Wait for<br/>timeout or size"]
    N --> M
    M -->|Ready| O["Compute insertID<br/>token-partition-offset"]

    O --> P["Flush Batch<br/>to BigQuery"]
    P --> Q{Insert<br/>Success?}
    Q -->|Success| R["Commit<br/>Kafka Offsets"]
    R --> S["Emit batch_latency<br/>metric"]
    S --> T["Record lag<br/>metric"]
    T --> A

    Q -->|Failure| U["Increment<br/>retries"]
    U --> V{Max<br/>Retries?}
    V -->|No| W["Exponential<br/>Backoff"]
    W --> P
    V -->|Yes| X["Write Batch<br/>to DLQ"]
    X --> I

    I --> Y["Write Message<br/>to cdc.dlq"]
    Y --> Z["Emit dlq_counter"]
    Z --> AA["Commit offset<br/>to DLQ"]
    AA --> A
```

## Flow Details

1. **Message Fetch**: Read from Kafka consumer group
2. **Validation**: Check JSON structure and Debezium envelope format
3. **Envelope Parsing**: Extract op, before, after, source, ts_ms
4. **Row Transformation**: Convert to BigQuery schema
5. **Batching**: Accumulate by size or timeout
6. **Deduplication ID**: insertID = topic-partition-offset for within-window dedup
7. **BigQuery Insert**: Streaming batch insert
8. **Retry Logic**: Exponential backoff up to max retries
9. **DLQ Fallback**: Failed batches written to cdc.dlq
10. **Offset Commit**: Only after successful insert or DLQ write
11. **Metrics**: Record latency, lag, and DLQ counters
