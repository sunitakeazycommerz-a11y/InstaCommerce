# Stream Processor Service - Event Processing Flowchart

```mermaid
flowchart TD
    A["Fetch Event<br/>from Kafka"] --> B["Extract<br/>Topic & Partition"]
    B --> C["Deserialize<br/>JSON"]
    C --> D{Valid<br/>Event?}
    D -->|No| E["Log Error"]
    E --> F["Skip to Next"]

    D -->|Yes| G["Extract Event Type<br/>(OrderPlaced, PaymentCompleted, etc.)"]
    G --> H["Check Deduplication<br/>topic-partition-offset"]
    H --> I{Already<br/>Processed?}
    I -->|Yes| F
    I -->|No| J["Mark<br/>Processed"]

    J --> K{Event<br/>Type?}
    K -->|OrderPlaced| L["OrderProcessor"]
    K -->|PaymentCompleted| M["PaymentProcessor"]
    K -->|RiderAccepted| N["RiderProcessor"]
    K -->|LocationUpdate| O["RiderProcessor"]
    K -->|InventoryReserved| P["InventoryProcessor"]

    L --> Q["Extract Metrics<br/>(order_count, value)"]
    M --> Q
    N --> Q
    O --> Q
    P --> Q

    Q --> R["Get Timestamp<br/>determine window"]
    R --> S["Add to Aggregator<br/>for multiple windows<br/>30s, 5m, 1h"]
    S --> T["Update Redis<br/>counters/gauges"]
    T --> U["Check SLA<br/>Compliance"]
    U --> V{SLA<br/>Breached?}
    V -->|Yes| W["Record SLA<br/>Violation"]
    V -->|No| X["Continue"]

    W --> Y["Emit Metrics<br/>latency + counters"]
    X --> Y
    Y --> Z["Commit Offset"]
    Z --> A
```

## Flow Details

1. **Event Fetch**: Kafka consumer polling
2. **Deserialization**: JSON parsing and validation
3. **Deduplication**: Check offset to prevent reprocessing
4. **Event Classification**: Route to appropriate processor
5. **Metric Extraction**: Domain-specific aggregation keys
6. **Windowing**: Add to 30s/5m/1h sliding windows
7. **Redis Write**: Update TTL-bounded metric gauges
8. **SLA Evaluation**: Check delivery compliance thresholds
9. **Metrics Emission**: Prometheus counter/gauge updates
10. **Offset Commit**: Mark as processed to Kafka
