# Audit Trail Service - Low-Level Design

```mermaid
classDiagram
    class AuditIngestionController {
        -service: AuditIngestionService
        +postEvent(request) ResponseEntity
        +postBatch(requests) ResponseEntity
    }

    class AdminAuditController {
        -queryService: AuditQueryService
        -exportService: AuditExportService
        +search(criteria) Page[AuditEvent]
        +exportCSV(criteria) ResponseEntity
    }

    class DomainEventConsumer {
        -service: AuditIngestionService
        -topics: []string
        +consume(event)
    }

    class AuditIngestionService {
        -repository: AuditEventRepository
        -builder: AuditEventBuilder
        -dlqWriter: KafkaTemplate
        +ingest(request) AuditEvent
        +ingestBatch(requests) List[AuditEvent]
        -handleError(event, error)
    }

    class AuditEvent {
        -event_id: UUID
        -event_type: string
        -aggregate_id: UUID
        -actor_id: UUID
        -action: string
        -resource_type: string
        -timestamp: LocalDateTime
        -details: JSON
        -created_at: LocalDateTime
    }

    class AuditEventRequest {
        -event_type: string
        -aggregate_id: UUID
        -actor_id: UUID
        -action: string
        -resource_type: string
        -details: JSON
    }

    class AuditEventBuilder {
        -event: AuditEvent
        +withEventType(type) AuditEventBuilder
        +withAggregateId(id) AuditEventBuilder
        +withActor(id) AuditEventBuilder
        +build() AuditEvent
    }

    class AuditQueryService {
        -repository: AuditEventRepository
        +search(criteria) Page[AuditEvent]
        -buildSpecification(criteria) Specification
    }

    class AuditSearchCriteria {
        -event_type: String
        -actor_id: UUID
        -resource_type: String
        -start_date: LocalDateTime
        -end_date: LocalDateTime
        -page: int
        -size: int
    }

    class AuditExportService {
        -repository: AuditEventRepository
        +exportCSV(criteria) StreamingResponseBody
        -writeBatch(writer, rows)
    }

    class PartitionMaintenanceJob {
        -repository: AuditEventRepository
        -retention_days: int
        -future_months: int
        +execute()
        -createFuturePartitions()
        -detachOldPartitions()
    }

    class AuditEventRepository {
        +findAll(specification, pageable) Page[AuditEvent]
        +saveAll(events) List[AuditEvent]
    }

    class MetricsCollector {
        -ingest_counter: Counter
        -query_latency: Histogram
        -export_counter: Counter
        -dlq_counter: Counter
        +recordIngest(count, latency)
        +recordQuery(latency)
        +recordExport(rows, latency)
    }

    AuditIngestionController --> AuditIngestionService
    AdminAuditController --> AuditQueryService
    AdminAuditController --> AuditExportService
    DomainEventConsumer --> AuditIngestionService
    AuditIngestionService --> AuditEvent
    AuditIngestionService --> AuditEventBuilder
    AuditQueryService --> AuditSearchCriteria
    AuditQueryService --> AuditEventRepository
    AuditExportService --> AuditEventRepository
    PartitionMaintenanceJob --> AuditEventRepository
    AuditIngestionService --> MetricsCollector
```

## Component Responsibilities

| Component | Responsibility |
|-----------|-----------------|
| **AuditIngestionController** | REST POST endpoints for events |
| **AdminAuditController** | Search and export endpoints (admin-only) |
| **DomainEventConsumer** | Kafka listener for 14 domain topics |
| **AuditIngestionService** | Persistence and DLQ routing |
| **AuditEventBuilder** | Fluent builder pattern for audit events |
| **AuditQueryService** | Dynamic JPA Specification queries |
| **AuditExportService** | Streaming CSV export in batches |
| **PartitionMaintenanceJob** | Monthly partition lifecycle |
| **AuditEventRepository** | JPA data access layer |
| **MetricsCollector** | Prometheus metrics emission |
