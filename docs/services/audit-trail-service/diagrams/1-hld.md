# Audit Trail Service - High-Level Design

```mermaid
flowchart TD
    subgraph Inbound["Inbound Sources"]
        REST_API["REST API<br/>/audit/events (single & batch)"]
        KAFKA["Kafka (14 domain topics)<br/>identity, catalog, order, payment, etc."]
    end

    subgraph ATS["audit-trail-service :8092"]
        direction TB
        INGEST_CTRL["AuditIngestionController<br/>(REST ingestion)"]
        DOMAIN_CONSUMER["DomainEventConsumer<br/>(Kafka subscriber)"]
        INGEST_SVC["AuditIngestionService<br/>(persistence)"]
        QUERY_SVC["AuditQueryService<br/>(pagination & search)"]
        EXPORT_SVC["AuditExportService<br/>(streaming CSV)"]
        ADMIN_CTRL["AdminAuditController<br/>(search & export)"]
        MAINT_JOB["PartitionMaintenanceJob<br/>(monthly)"]
    end

    subgraph Database["PostgreSQL"]
        AUDIT_TBL["audit_events<br/>(monthly partitions)"]
        ARCHIVE_TBL["audit_events_archive<br/>(retained partitions)"]
        DLQ_TBL["audit.dlq<br/>(failed events)"]
    end

    subgraph Kafka["Kafka Topics"]
        DOMAIN_TOPICS["14 Domain Topics<br/>(identity, catalog, order, ...)"]
        DLQ_TOPIC["audit.dlq"]
    end

    subgraph Observability["Observability"]
        PROM["Prometheus<br/>Metrics"]
        OTEL["OpenTelemetry<br/>Traces"]
    end

    REST_API --> INGEST_CTRL
    KAFKA --> DOMAIN_CONSUMER

    INGEST_CTRL --> INGEST_SVC
    DOMAIN_CONSUMER --> INGEST_SVC
    INGEST_SVC --> AUDIT_TBL
    INGEST_SVC --> DLQ_TOPIC

    ADMIN_CTRL --> QUERY_SVC
    ADMIN_CTRL --> EXPORT_SVC
    QUERY_SVC --> AUDIT_TBL
    EXPORT_SVC --> AUDIT_TBL

    MAINT_JOB --> AUDIT_TBL
    MAINT_JOB --> ARCHIVE_TBL

    INGEST_SVC --> PROM
    QUERY_SVC --> PROM
    EXPORT_SVC --> PROM
    INGEST_SVC --> OTEL
```

## Key Characteristics

- **Immutable Append-Only**: No UPDATE/DELETE on audit rows
- **Monthly Partitions**: Range partitioning by event date
- **14 Domain Topics**: Identity, Catalog, Order, Payment, Inventory, Fulfillment, Rider, Notification, Search, Pricing, Promotion, Support, Returns, Warehouse
- **REST + Kafka Ingestion**: Single, batch, and event stream sources
- **Paginated Search**: JPA Specifications for dynamic querying
- **Streaming CSV Export**: Batched 500-row chunks
- **Compliance**: 365-day retention, partition management
- **Observability**: Prometheus ingestion/query latencies + OTLP traces
