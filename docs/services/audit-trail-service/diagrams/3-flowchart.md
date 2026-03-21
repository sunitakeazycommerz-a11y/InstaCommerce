# Audit Trail Service - Event Ingestion Flowchart

```mermaid
flowchart TD
    A["Incoming Event<br/>REST POST or Kafka"] --> B["Parse Event"]
    B --> C{Source<br/>Type?}
    C -->|REST| D["Single or Batch?"]
    C -->|Kafka| E["Transform to<br/>AuditEvent"]
    D -->|Single| F["Extract from Request"]
    D -->|Batch| G["Loop Through Array"]
    G --> F
    F --> E
    E --> H["Validate Fields<br/>required: event_type, aggregate_id, action"]
    H --> I{Valid?}
    I -->|No| J["Log Error"]
    J --> K["Route to DLQ<br/>audit.dlq"]
    I -->|Yes| L["Build AuditEvent<br/>via builder pattern"]
    L --> M["Persist to PostgreSQL<br/>INSERT into audit_events"]
    M --> N{Insert<br/>Success?}
    N -->|Error| K
    N -->|Success| O["Emit Metrics<br/>ingest_counter++"]
    O --> P["Record Latency<br/>histogram.observe"]
    P --> Q["Return 200<br/>or batch response"]
    K --> R["Return 400<br/>or partial error"]
    Q --> S["HTTP Response"]
    R --> S
```

## Flow Details

1. **Event Source**: REST API (single/batch) or Kafka subscription
2. **Parsing**: JSON deserialization and validation
3. **Field Validation**: Required fields check (event_type, aggregate_id, action)
4. **Transformation**: Map to AuditEvent entity
5. **Builder Pattern**: Fluent construction for immutability
6. **Persistence**: INSERT to monthly-partitioned audit_events table
7. **DLQ Fallback**: Failed events routed to audit.dlq for replay
8. **Metrics**: Record ingestion counter and latency
9. **Response**: 200 OK or partial error for batch
