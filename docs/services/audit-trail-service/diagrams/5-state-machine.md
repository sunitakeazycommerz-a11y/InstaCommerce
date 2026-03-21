# Audit Trail Service - Event State Machine

```mermaid
stateDiagram-v2
    [*] --> Received

    Received --> Parsed: json_parse
    Parsed --> Validated: validate_fields
    Validated --> Built: build_event

    Parsed -->|Parse Error| ParseFail
    ParseFail --> DLQWrite: malformed_json

    Validated -->|Missing Fields| ValidFail
    ValidFail --> DLQWrite

    Built --> Partitioned: determine_partition
    Partitioned --> Persisted: insert_to_db

    Persisted --> Success: insert_ok
    Persisted --> Error: insert_error

    Error --> DLQWrite: db_error

    DLQWrite --> DLQWritten: dlq_written
    DLQWritten --> Metrics: dlq_counter++

    Success --> Metrics: ingest_counter++
    Metrics --> MetricsRec: latency_recorded
    MetricsRec --> Response: 200_or_400

    Response --> [*]

    note right of Parsed
        JSON deserialization
        and structure check
    end note

    note right of Validated
        Required fields:
        event_type, aggregate_id, action
    end note

    note right of Built
        Fluent builder pattern
        for immutability
    end note

    note right of Partitioned
        Select partition based
        on event timestamp
    end note

    note right of Persisted
        INSERT to monthly partition
        append-only, no UPDATE
    end note

    note right of DLQWrite
        Failed events persisted
        to audit.dlq for replay
    end note
```

## State Transitions

- **Received→Parsed**: Event received via REST or Kafka
- **Parsed→Validated**: JSON parsing successful
- **Validated→Built**: Field validation passed
- **Built→Partitioned**: Determine target partition
- **Partitioned→Persisted**: Insert to PostgreSQL
- **Persisted→Success**: Insert succeeded
- **Persisted→Error**: Insert failed
- **Error→DLQWrite**: Route failed event to DLQ
- **Success→Metrics**: Record ingestion metrics
- **DLQWrite→Metrics**: Record DLQ metrics
- **Metrics→Response**: Send HTTP response
