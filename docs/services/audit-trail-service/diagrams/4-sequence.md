# Audit Trail Service - Event Ingestion Sequence

```mermaid
sequenceDiagram
    participant Client as REST Client or Kafka
    participant INGEST_CTRL as AuditIngestionController
    participant INGEST_SVC as AuditIngestionService
    participant BUILDER as AuditEventBuilder
    participant DB as PostgreSQL
    participant DLQ as Kafka DLQ
    participant PROM as Prometheus

    Client ->> INGEST_CTRL: POST /audit/events
    activate INGEST_CTRL

    INGEST_CTRL ->> INGEST_SVC: ingest(request)
    activate INGEST_SVC

    INGEST_SVC ->> INGEST_SVC: Validate fields

    alt Validation Fails
        INGEST_SVC ->> DLQ: Write to audit.dlq
        activate DLQ
        DLQ -->> INGEST_SVC: OK
        deactivate DLQ
        INGEST_SVC -->> INGEST_CTRL: IngestionResult{error}
    else Validation Passes
        INGEST_SVC ->> BUILDER: new AuditEventBuilder()
        activate BUILDER
        BUILDER ->> BUILDER: withEventType(type)
        BUILDER ->> BUILDER: withAggregateId(id)
        BUILDER ->> BUILDER: withActor(id)
        BUILDER ->> BUILDER: withAction(action)
        BUILDER ->> BUILDER: withResourceType(type)
        BUILDER -->> INGEST_SVC: AuditEvent
        deactivate BUILDER

        INGEST_SVC ->> DB: INSERT into audit_events
        activate DB
        DB ->> DB: Determine partition<br/>based on timestamp
        DB -->> INGEST_SVC: OK
        deactivate DB

        INGEST_SVC ->> PROM: ingest_counter++
        activate PROM
        PROM -->> INGEST_SVC: void
        deactivate PROM

        INGEST_SVC ->> PROM: ingest_latency_ms.observe(elapsed)
        activate PROM
        PROM -->> INGEST_SVC: void
        deactivate PROM

        INGEST_SVC -->> INGEST_CTRL: IngestionResult{success}
    end

    INGEST_CTRL -->> Client: 200 or 400
    deactivate INGEST_SVC
    deactivate INGEST_CTRL
```

## Sequence Patterns

- **Validation First**: Early error detection
- **Builder Pattern**: Fluent immutable event construction
- **Partition Routing**: Automatic based on timestamp
- **Transactional Insert**: Single DB call
- **DLQ Fallback**: Failed events persisted for replay
- **Metrics Async**: Non-blocking Prometheus emission
