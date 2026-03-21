# Audit Trail & CDC Consumer Cluster

## Overview

This cluster comprises two **data plane** services handling observability and analytics:

1. **audit-trail-service**: Centralized, immutable audit log with compliance-grade querying
2. **cdc-consumer-service**: Debezium CDC → BigQuery ingestion for data warehouse

## Cluster Architecture

```
┌───────────────────────────────────────────────────────┐
│           Kafka (14 domain event topics)               │
└───────────────────────────────────────────────────────┘
     │                                          │
     ├──────────────────┬──────────────────────┤
     │                  │                      │
     ▼                  ▼                      ▼
┌─────────────┐  ┌──────────────┐     ┌────────────────┐
│   audit-    │  │     cdc-     │     │  stream-       │
│   trail     │  │   consumer   │     │  processor     │
│ :8089       │  │   :8104      │     │  :8108         │
└─────────────┘  └──────────────┘     └────────────────┘
     │                  │
     ▼                  ▼
┌──────────────┐  ┌──────────────┐
│ PostgreSQL   │  │  BigQuery    │
│(partitioned) │  │(data lake)   │
└──────────────┘  └──────────────┘
```

## Service Responsibilities

### Audit Trail Service (Tier 2)
- **Event Ingestion**: Kafka consumer + REST API for audit events
- **Immutable Storage**: Append-only PostgreSQL with monthly range partitions
- **Compliance Querying**: Full-text search + CSV export for audit investigations
- **Retention Enforcement**: ShedLock-protected partition cleanup
- **Port**: 8089 (dev), 8080 (container)
- **Runtime**: Spring Boot 4, Java 21

### CDC Consumer Service (Tier 2)
- **Change Data Capture**: Debezium logical decoding from PostgreSQL WAL
- **Transformation**: CDC records → analytics schema (JSON fields normalized)
- **BigQuery Ingestion**: Batch insert via Google Cloud client library
- **DLQ Routing**: Failed records → Kafka DLQ topic with error metadata
- **Port**: 8104 (dev), 8080 (container)
- **Runtime**: Go 1.24 (lightweight, fast batch processing)

## Key Technologies

| Component | Version | Purpose |
|-----------|---------|---------|
| Kafka | 2.8+ | Domain events (14 topics), DLQ topics |
| PostgreSQL | 15+ | Audit storage + CDC source |
| BigQuery | — | Data warehouse for analytics |
| Flyway | — | Schema migration (audit partitions) |
| ShedLock | 5.10.2 | Distributed partition maintenance lock |
| Go Client (CDC) | v1.50+ | BigQuery API client |

## Kafka Topics

### Audit Trail Consumer
```
identity.events              → User created, login, erasure
catalog.events              → Product changes
order.events                → Order lifecycle
payment.events              → Payment transactions
inventory.events            → Stock updates
fulfillment.events          → Delivery status
rider.events                → Rider assignments
notification.events         → Notification delivery
[... + 7 more topics]
```

Failed events → `audit.dlq` (configurable via `audit.dlqTopic`)

### CDC Consumer
```
db.public.users             → Debezium CDC for users table
db.public.orders            → Debezium CDC for orders
db.public.payments          → Debezium CDC for payments
[... + all production tables]
```

Failed events → `cdc.dlq` with metadata headers

## Database Schema

### Audit Trail (PostgreSQL partitioned)

```sql
CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(100) NOT NULL,
    source_service VARCHAR(50) NOT NULL,
    actor_id UUID,
    actor_type VARCHAR(20),              -- USER|SYSTEM|ADMIN
    resource_type VARCHAR(100),
    resource_id VARCHAR(255),
    action VARCHAR(100) NOT NULL,
    details JSONB,
    ip_address VARCHAR(45),
    user_agent VARCHAR(512),
    correlation_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (created_at);

-- Monthly partitions created via PartitionMaintenanceJob
CREATE TABLE audit_events_2025_03
    PARTITION OF audit_events
    FOR VALUES FROM ('2025-03-01') TO ('2025-04-01');
```

**Retention**: 365 days (configurable); older partitions detached and archived.

### CDC Consumer (BigQuery schema)

```sql
CREATE TABLE project.dataset.cdc_events (
    topic STRING NOT NULL,              -- Source Kafka topic
    partition INT64 NOT NULL,           -- Partition number
    offset INT64 NOT NULL,              -- Message offset
    key STRING,                         -- Message key
    op STRING,                          -- CDC operation: c|u|d|r (create|update|delete|read)
    ts_ms INT64,                        -- CDC timestamp (milliseconds)
    source JSON,                        -- CDC source metadata
    before JSON,                        -- Pre-image (for updates/deletes)
    after JSON,                         -- Post-image (for creates/updates)
    payload JSON,                       -- Full Debezium payload
    headers JSON,                       -- Kafka headers
    raw STRING,                         -- Raw message value
    kafka_timestamp TIMESTAMP,
    ingested_at TIMESTAMP NOT NULL
) PARTITION BY DATE(ingested_at)
CLUSTER BY topic, partition;
```

## API Reference

### Audit Trail Service

```
POST   /audit/events                    → Ingest single event
POST   /audit/events/batch              → Ingest up to 1000 events
GET    /admin/audit/events              → Search with filters (ADMIN)
GET    /admin/audit/export              → Streaming CSV export (ADMIN)
GET    /actuator/health                 → Liveness
GET    /actuator/health/readiness       → Readiness (DB + Kafka)
```

**Search filters**: `actorId`, `resourceType`, `resourceId`, `sourceService`, `eventType`, `correlationId`, `fromDate`, `toDate`

**Default**: Last 30 days; Max query range: 366 days

### CDC Consumer Service

```
GET    /health                          → Liveness
GET    /ready                           → Readiness (Kafka + BigQuery)
GET    /metrics                         → Prometheus metrics
```

## Configuration

### Audit Trail Environment

```bash
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
SPRING_KAFKA_CONSUMER_GROUP_ID=audit-trail-service
AUDIT_PARTITION_RETENTIONDAYS=365      # Days before detachment
AUDIT_PARTITION_FUTUREMONTHS=3         # Future partitions to create
AUDIT_DLQTOPIC=audit.dlq
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces
```

### CDC Consumer Environment

```bash
PORT=8104
KAFKA_BROKERS=kafka:9092
KAFKA_TOPICS=db.public.users,db.public.orders,...
KAFKA_GROUP_ID=cdc-consumer-service
KAFKA_DLQ_TOPIC=cdc.dlq
BQ_PROJECT=my-gcp-project
BQ_DATASET=analytics
BQ_TABLE=cdc_events
BQ_BATCH_SIZE=500
BQ_BATCH_TIMEOUT=5s
BQ_INSERT_TIMEOUT=30s
BQ_MAX_RETRIES=5
LOG_LEVEL=info
```

## Observability

### Audit Trail Metrics

```
audit_events_ingested_total{source}     # Ingestion counter
audit_search_latency_ms{}               # Query latency
audit_export_rows_total{}               # CSV export row count
kafka_consumer_lag{topic}               # Consumer group lag
```

### CDC Consumer Metrics

```
cdc_consumer_lag{topic, partition}      # Kafka consumer lag
cdc_batch_latency_seconds{}             # BigQuery insert latency
cdc_dlq_total{}                         # DLQ message count
bigquery_insert_errors_total{reason}    # Insert failure reason
```

## Failure Modes

| Scenario | Detection | Recovery |
|----------|-----------|----------|
| PostgreSQL partition full | INSERT fails, metrics spike | Manual partition cleanup; alert ops |
| Kafka broker down | Consumer lag grows indefinitely | Auto-reconnect on broker recover |
| BigQuery quota exceeded | 403 error in batch insert | Exponential backoff; retry with reduced batch |
| Stale CDC offset | Restart skips events | Manual offset reset (risky; data gaps) |
| DLQ topic missing | Write fails | Create topic; replay DLQ batch to retry |

## Deployment Checklist

- [ ] Partition maintenance job tested (creates future partitions)
- [ ] Retention cleanup validated (doesn't delete too much)
- [ ] BigQuery dataset & table exist with correct schema
- [ ] CDC source (PostgreSQL logical replication) enabled
- [ ] Kafka topics exist for all domain events + DLQ topics
- [ ] Readiness probes include Kafka + DB connectivity

## Known Limitations

1. **Audit coverage gap**: Only 14 topics captured; missing customer-support, returns
2. **CDC lag**: Logical decoding can lag behind WAL under high write load
3. **No dedup on CDC**: Duplicate inserts if BigQuery connector restarts
4. **Manual partition cleanup**: Detached partitions require ops intervention
5. **BigQuery schema drift**: New fields in CDC source not auto-migrated to BQ

## References

- [Audit Trail Service README](/services/audit-trail-service/README.md) — Full specification
- [CDC Consumer Service README](/services/cdc-consumer-service/README.md) — Implementation details
- [Data Flow Architecture](/docs/architecture/DATA-FLOW.md) — Event pipeline design
- [Debezium Documentation](https://debezium.io/) — CDC platform guide
