# Stream Processor Service - Stream Processing State Machine

```mermaid
stateDiagram-v2
    [*] --> Fetched

    Fetched --> Deserialized: parse_json
    Deserialized --> Validated: validate_schema
    Validated --> Classified: classify_event_type
    Deserialized -->|Parse Error| ParseFail
    ParseFail --> Skipped: malformed_json

    Classified --> Deduped: check_offset
    Deduped --> Processed: new_event
    Deduped --> Skipped: already_processed

    Processed --> Extracted: extract_metrics
    Extracted --> Windowed: add_to_windows

    Windowed --> SLACheck: evaluate_compliance
    SLACheck --> SLAPass: compliance_ok
    SLACheck --> SLAFail: compliance_breach

    SLAPass --> RedisWrite: write_metrics
    SLAFail --> RecordBreach: record_sla_breach
    RecordBreach --> RedisWrite

    RedisWrite --> MetricsEmit: emit_counters
    MetricsEmit --> OffsetCommit: record_metrics
    OffsetCommit --> Committed: offsets_committed

    Committed --> [*]

    Skipped --> [*]
    ParseFail --> [*]

    note right of Validated
        JSON schema
        event_type check
    end note

    note right of Classified
        Route to Order/Payment/Rider/
        Inventory processor
    end note

    note right of Deduped
        topic-partition-offset
        tracking
    end note

    note right of Windowed
        Update 30s, 5m, 1h
        sliding windows
    end note

    note right of SLACheck
        30-min window
        delivery_compliance >= threshold
    end note

    note right of RedisWrite
        HSET with TTL
        (5 minutes typical)
    end note
```

## State Transitions

- **Fetched→Deserialized**: Kafka message received
- **Deserialized→Validated**: JSON parsing and schema validation
- **Validated→Classified**: Event type determination
- **Classified→Deduped**: Offset-based deduplication check
- **Deduped→Processed**: New event or skip if duplicate
- **Processed→Extracted**: Domain-specific metric extraction
- **Extracted→Windowed**: Add to sliding windows
- **Windowed→SLACheck**: Evaluate delivery compliance
- **SLACheck→SLAPass/SLAFail**: Compliance result
- **SLAPass→RedisWrite**: Write metrics cache
- **SLAFail→RecordBreach**: Record SLA violation
- **RedisWrite→MetricsEmit**: Prometheus emission
- **MetricsEmit→OffsetCommit**: Record latency metrics
- **OffsetCommit→Committed**: Kafka offset committed
