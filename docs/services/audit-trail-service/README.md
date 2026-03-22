# Audit Trail Service

## 1. Overview

The audit-trail-service is the **immutable, compliance-grade event repository** for InstaCommerce. It ingests domain events from 14+ Kafka topics, persists them to PostgreSQL with partition-based retention, and provides admin-protected search & export APIs for regulatory investigations and operational audits. This service is the single source of truth for audit compliance, serving SOC 2, PCI DSS, and GDPR audit requirements while maintaining <10 second consumer lag for real-time event reflection.

**Service Ownership**: Platform Team - Observability & Compliance
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8089 (dev), 8080 (container)
**Status**: Tier 1 Critical (Compliance backbone - regulatory requirement)
**Database**: PostgreSQL 15+ (partitioned, immutable schema)
**Message Queue**: Apache Kafka (14+ event topics)
**Storage Archive**: AWS S3 Glacier (immutable, object-locked)
**Cache**: None (append-only, no caching needed)

### Service Purpose

Audit-trail-service serves as the authoritative audit log for all critical business events in InstaCommerce. Every material action—user creation, order placement, payment processing, fulfillment, refunds, admin actions—is captured with full context (actor, timestamp, IP address, correlation ID, event details). This immutable log is:

- **Regulatory Required**: SOC 2 Type II, PCI DSS 3.2.1, GDPR Article 32
- **Forensic Authority**: Used for incident investigations, chargeback disputes, compliance audits
- **Long-term Archive**: 365-day hot retention in PostgreSQL, then to S3 Glacier (7-year minimum for PCI)
- **Real-time Searchable**: Admin dashboard queries with <500ms latency via full-text search

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **Event Capture**: 100% (no event loss to audit trail)
- **Query Latency P99**: < 500ms for compliance searches (max 1M records)
- **Export Latency P99**: < 10s for CSV export (per 100K records)
- **Consumer Lag**: < 10 seconds (real-time event reflection)
- **Database Uptime**: 99.95% (dedicated PostgreSQL replica)

## Key Responsibilities

1. **Multi-Topic Event Ingestion**: Kafka consumer for 14+ domain event topics (identity, orders, payments, fulfillment, inventory, etc.)
2. **Immutable Append-Only Storage**: PostgreSQL with range partitions by month, no UPDATE/DELETE on base table
3. **Compliance Search**: Full-text search on event metadata, filtering by actor/resource/date ranges
4. **CSV Export**: Streaming export for regulatory audits (SOC 2, PCI DSS, GDPR investigations)
5. **Retention Enforcement**: ShedLock-protected partition detachment & archival to S3 Glacier (365-day retention)
6. **Dead Letter Queue Management**: Unprocessable events routed to `audit.dlq` with error metadata
7. **Distributed Tracing**: OpenTelemetry export per ingestion event
8. **Access Control**: Audit trail access restricted to admin-gateway & compliance tools

## Deployment

### GKE Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: audit-trail-service
  namespace: audit
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels:
      app: audit-trail-service
  template:
    metadata:
      labels:
        app: audit-trail-service
        tier: tier2
    spec:
      serviceAccountName: audit-trail-service
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      containers:
      - name: audit-trail-service
        image: us-central1-docker.pkg.dev/instacommerce/audit/audit-trail-service:v1.5.0
        imagePullPolicy: IfNotPresent
        ports:
        - name: http
          containerPort: 8080
          protocol: TCP
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
          limits:
            cpu: 1000m
            memory: 1024Mi
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 2
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "gcp"
        - name: SERVER_PORT
          value: "8080"
        - name: SPRING_KAFKA_BOOTSTRAP_SERVERS
          value: "kafka-broker-0.kafka:9092,kafka-broker-1.kafka:9092,kafka-broker-2.kafka:9092"
        - name: SPRING_KAFKA_CONSUMER_GROUP_ID
          value: "audit-trail-service"
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            secretKeyRef:
              name: audit-db-credentials
              key: url
        - name: SPRING_DATASOURCE_USERNAME
          valueFrom:
            secretKeyRef:
              name: audit-db-credentials
              key: username
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: audit-db-credentials
              key: password
        - name: AUDIT_PARTITION_RETENTION_DAYS
          value: "365"
        - name: AUDIT_PARTITION_FUTURE_MONTHS
          value: "3"
        - name: AUDIT_DLQ_TOPIC
          value: "audit.dlq"
        - name: AWS_S3_BUCKET
          value: "instacommerce-audit-archive"
        - name: AWS_REGION
          value: "us-central1"
        - name: OTEL_EXPORTER_OTLP_TRACES_ENDPOINT
          value: "http://otel-collector.monitoring:4318/v1/traces"
---
apiVersion: v1
kind: Service
metadata:
  name: audit-trail-service
  namespace: audit
spec:
  type: ClusterIP
  ports:
  - port: 8089
    targetPort: 8080
    name: http
  selector:
    app: audit-trail-service
```

### Cluster Configuration

- **Namespace**: `audit` (isolated from core services)
- **Replicas**: 3 (HA, rolling updates)
- **NetworkPolicy**: Deny-default; allow from admin-gateway only
- **Service Account**: `audit-trail-service` with S3 write permissions
- **Resource Limits**: 500m CPU / 1000m limit, 512Mi RAM / 1024Mi limit

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  14 Domain Event Topics (Kafka)                      │
│  identity.events, orders.events, payments.events... │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
          ┌────────────────────────────┐
          │  Audit Trail Service (Java)│
          │  Spring Kafka Consumer     │
          │  Thread Pool: 20 threads   │
          └────────────┬───────────────┘
                       │
                       ├─ Valid event
                       │   │
                       │   ▼
                       │ ┌──────────────────────┐
                       │ │ PostgreSQL (audit-db)│
                       │ │ Partitioned table    │
                       │ │ INSERT into partition│
                       │ │ Range by created_at  │
                       │ └──────────────────────┘
                       │
                       ├─ Invalid/unprocessable
                       │   │
                       │   ▼
                       │ ┌──────────────────┐
                       │ │ audit.dlq        │
                       │ │ (DLQ Topic)      │
                       │ └──────────────────┘
                       │
                       └─ Archived (>365 days)
                           │
                           ▼
                        ┌──────────────────────┐
                        │ S3 Glacier (immutable)│
                        │ Batched upload       │
                        └──────────────────────┘

Admin Query Flow:
┌──────────────────┐
│ Admin Dashboard  │  (via admin-gateway-service)
└────────┬─────────┘
         │
         ▼
┌──────────────────────────────┐
│ Audit Trail Search API       │
│ GET /admin/audit/events      │
│ Requires: ADMIN role + JWT   │
└────────┬─────────────────────┘
         │
         ▼
┌──────────────────────────────┐
│ PostgreSQL Query             │
│ Full-text search + filtering │
│ Result: JSON array           │
└──────────────────────────────┘
```

## Data Model

### Audit Events Table (PostgreSQL)

```sql
CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id VARCHAR(64) NOT NULL UNIQUE,           -- Idempotency key
    event_type VARCHAR(100) NOT NULL,               -- OrderPlaced, PaymentCompleted, etc.
    source_service VARCHAR(50) NOT NULL,            -- Service name (order-service, payment-service)
    actor_id UUID,                                  -- User ID who triggered event
    actor_type VARCHAR(20),                         -- USER|SYSTEM|ADMIN|SERVICE
    resource_type VARCHAR(100),                     -- Order|Payment|User|Inventory
    resource_id VARCHAR(255),                       -- Specific resource ID
    action VARCHAR(100) NOT NULL,                   -- CREATE|UPDATE|DELETE|QUERY
    details JSONB,                                  -- Event payload (structured)
    ip_address VARCHAR(45),                         -- Actor IP (v4 or v6)
    user_agent VARCHAR(512),                        -- Browser user agent
    correlation_id VARCHAR(64),                     -- Request trace correlation
    timestamp_ms BIGINT NOT NULL,                   -- Event timestamp (milliseconds)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),  -- Ingestion time (UTC)
    CONSTRAINT valid_actor CHECK (actor_id IS NOT NULL OR actor_type = 'SYSTEM'),
    CONSTRAINT valid_resource CHECK (resource_type IS NOT NULL)
) PARTITION BY RANGE (created_at);

-- Monthly partitions (auto-created by PartitionMaintenanceJob)
CREATE TABLE audit_events_2025_03 PARTITION OF audit_events
    FOR VALUES FROM ('2025-03-01') TO ('2025-04-01');

-- Indices for efficient queries
CREATE INDEX idx_audit_events_actor_date
    ON audit_events (actor_id, created_at DESC);
CREATE INDEX idx_audit_events_resource
    ON audit_events (resource_type, resource_id, created_at DESC);
CREATE INDEX idx_audit_events_event_type
    ON audit_events (event_type, created_at DESC);
CREATE INDEX idx_audit_events_service
    ON audit_events (source_service, created_at DESC);

-- Full-text search index
CREATE INDEX idx_audit_events_fts
    ON audit_events USING GIN (to_tsvector('english', details::text));

-- Partitions are range-partitioned by month (created_at)
-- Retention: 365 days (automatic detach & archive to Glacier)
-- Archive frequency: Daily (2 AM UTC via ShedLock job)
```

### Kafka Topic Schema

```json
{
  "topic": "identity.events|orders.events|payments.events|...",
  "key": "user-123|order-456|payment-789",
  "value": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "eventId": "550e8400-e29b-41d4-a716-446655440000",
    "aggregateType": "User|Order|Payment|...",
    "aggregateId": "user-123",
    "eventType": "UserCreated|OrderPlaced|PaymentCompleted|...",
    "eventTime": "2025-01-15T10:30:00.000Z",
    "schemaVersion": "v1",
    "actor": {
      "id": "admin-1",
      "type": "ADMIN",
      "ipAddress": "192.168.1.1"
    },
    "payload": {
      "userId": "user-123",
      "email": "user@example.com",
      "status": "VERIFIED",
      "customFields": {}
    }
  }
}
```

## API Reference

### Ingestion Endpoints (Internal)

```
POST /audit/events
  Input: Single audit event
  Output: 202 Accepted or 400 Bad Request

POST /audit/events/batch
  Input: Array of up to 1000 events
  Output: 202 Accepted or 400 Bad Request (partial)

GET /actuator/health
  Liveness check (always 200 if service running)

GET /actuator/health/readiness
  Readiness check (200 if DB + Kafka connected)
```

### Admin Query Endpoints (Admin-only)

```
GET /admin/audit/events?actorId=user-123&fromDate=2025-01-01&toDate=2025-01-31
  Query parameters:
    - actorId: Filter by user ID
    - resourceType: Filter by Order|Payment|User|etc.
    - resourceId: Filter by specific resource
    - eventType: Filter by event type
    - sourceService: Filter by service name
    - correlationId: Filter by request trace
    - fromDate: Start date (ISO 8601)
    - toDate: End date (ISO 8601, max 366 days range)
  Response: 200 OK with JSON array (max 10,000 records)
  Requires: JWT token with ADMIN role

GET /admin/audit/export?resourceType=Order&fromDate=2025-01-01&toDate=2025-01-31
  Streaming CSV export (max 100K records per request)
  Response: 200 OK with text/csv stream
  Requires: JWT token with ADMIN role

GET /admin/audit/stats
  Aggregate statistics (event counts by type, source service)
  Response: 200 OK with JSON
  Requires: JWT token with ADMIN role
```

### Example Requests

```bash
# Query all payments by a specific user (compliance investigation)
curl -X GET "http://audit-trail-service:8089/admin/audit/events" \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Accept: application/json" \
  -d '{"actorId":"user-456","resourceType":"Payment","fromDate":"2025-01-01","toDate":"2025-01-31"}' \
  | jq '.events[] | {eventType, resourceId, timestamp_ms, details}'

# Export all order events for 30 days (SOC 2 audit)
curl -X GET "http://audit-trail-service:8089/admin/audit/export" \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Accept: text/csv" \
  -d '{"resourceType":"Order","fromDate":"2025-01-01","toDate":"2025-01-31"}' \
  > order-audit-jan.csv

# Check audit statistics
curl -X GET "http://audit-trail-service:8089/admin/audit/stats" \
  -H "Authorization: Bearer $ADMIN_JWT" | jq '.stats_by_type'
```

## Error Handling

### Event Processing Errors

| Error Type | Root Cause | Recovery |
|-----------|-----------|----------|
| JSON parse error | Malformed Kafka message | Route to `audit.dlq`, log error details |
| Missing required field | Invalid event envelope | Route to `audit.dlq`, alert ops |
| Database constraint violation | Duplicate event ID | Idempotent retry (UPDATE if exists) |
| Partition full | PostgreSQL storage exhausted | Alert ops, archive old partitions |
| Kafka consumer lag spike | Broker slow/unavailable | Auto-reconnect, scale up replicas |

### HTTP Response Codes

- `202 Accepted`: Event queued for processing
- `400 Bad Request`: Invalid JSON or missing required fields
- `401 Unauthorized`: Missing/invalid JWT token
- `403 Forbidden`: User lacks ADMIN role
- `500 Internal Server Error`: Database or Kafka failure (retry with exponential backoff)
- `503 Service Unavailable`: Readiness probe failing (no traffic sent)

### DLQ Processing

```bash
# Monitor DLQ topic (events that failed processing)
kubectl exec -n audit deploy/audit-trail-service -- \
  curl -s http://localhost:8080/admin/dlq/stats | jq '.total_dlq_messages'

# Replay specific DLQ messages
curl -X POST "http://audit-trail-service:8089/admin/dlq/replay" \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"offsets":[0,1,2,3]}' | jq '.replayed_count'
```

## Monitoring & Alerts

### Key Metrics (Prometheus)

```yaml
# Ingestion metrics
audit_events_ingested_total{source_service, event_type}
  Counter for events successfully persisted
  Threshold: Should have constant throughput (no sudden drops)

audit_events_dlq_total{reason}
  Counter for events routed to DLQ
  Threshold Alert: > 10/min for more than 2 minutes (SEV-2)

kafka_consumer_lag_seconds{topic, partition}
  Gauge for Kafka consumer lag
  Threshold Alert: > 10 seconds (SEV-2), > 60 seconds (SEV-1)

audit_search_latency_ms{quantile}
  Histogram for admin search query latency
  Threshold Alert: p99 > 1000ms (SEV-3)

audit_export_duration_ms{quantile}
  Histogram for CSV export duration
  Threshold Alert: p99 > 30000ms (SEV-3)

postgres_audit_db_connections_used{}
  Gauge for active PostgreSQL connections
  Threshold Alert: > 40 out of 50 max (SEV-2)

audit_partition_size_bytes{partition}
  Gauge for partition size on disk
  Threshold Alert: > 100GB (SEV-2 - storage issue)
```

### Alert Rules (YAML)

```yaml
groups:
- name: audit-trail-service
  interval: 30s
  rules:
  - alert: AuditEventProcessingFailed
    expr: rate(audit_events_dlq_total[5m]) > 10/60
    for: 2m
    severity: critical
    annotations:
      summary: "Audit trail DLQ spike ({{ $value }}/sec)"
      description: "Events failing to persist to audit DB. Check database connectivity."
      runbook: "/docs/services/audit-trail-service/runbook.md#dlq-processing"

  - alert: AuditConsumerLagHigh
    expr: kafka_consumer_lag_seconds{topic=~"*.events"} > 60
    for: 5m
    severity: warning
    annotations:
      summary: "Audit Kafka consumer lag >1min ({{ $value }}s)"
      description: "Audit trail not catching up with event topics. Scale up replicas."

  - alert: AuditSearchLatencyHigh
    expr: histogram_quantile(0.99, rate(audit_search_latency_ms_bucket[5m])) > 1000
    for: 3m
    severity: warning
    annotations:
      summary: "Audit search p99 latency >1s ({{ $value }}ms)"
      description: "Database query performance degrading. Check PostgreSQL load."

  - alert: AuditPartitionFull
    expr: audit_partition_size_bytes > 100*1024*1024*1024
    severity: critical
    annotations:
      summary: "Audit partition >100GB ({{ $value | humanize }})"
      description: "Archive old records or add storage. Manual intervention needed."
```

### Logging

- **INFO**: Event ingestion (rate: 1/sec at 1K throughput)
- **WARN**: Kafka lag increases, slow query performance, DLQ routing
- **ERROR**: Database connection failures, JSON parse errors, partition maintenance failures
- **DEBUG**: Partition maintenance job details, event payload (if enabled)

## Security Considerations

### Access Control

1. **Kafka Topics**: Service account has consumer permissions only (no produce)
2. **PostgreSQL**: Dedicated audit DB user with INSERT-only to `audit_events` (no UPDATE/DELETE)
3. **S3 Glacier**: Service account has write-only access (no delete of archived records)
4. **Admin APIs**: Require JWT token with `ADMIN` role from identity-service
5. **Audit Logging**: All admin queries logged with actor ID, timestamp, query parameters

### Data Protection

1. **Encryption in Transit**: TLS 1.2+ for Kafka, PostgreSQL, S3 connections
2. **Encryption at Rest**: PostgreSQL encryption, S3 SSE-S3 for Glacier archives
3. **Data Retention**: 365 days in PostgreSQL, then archived to S3 Glacier (immutable)
4. **No PII Deletion**: Audit trail is append-only; GDPR erasures create DELETE_ACCOUNT events (not removals)

### Threat Mitigations

1. **Duplicate Detection**: `event_id` uniqueness constraint prevents replay attacks
2. **Immutable Archive**: S3 Glacier object lock prevents tampering with audit logs
3. **Rate Limiting**: Kafka consumer thread pool limits (20 threads) prevent DoS
4. **Partition Isolation**: Monthly partitions can be independently archived or reviewed

## Troubleshooting

### High Kafka Consumer Lag

**Symptom**: `kafka_consumer_lag_seconds` > 60 for extended time

**Diagnosis**:
```bash
# Check pod logs for errors
kubectl logs -n audit deploy/audit-trail-service --tail=100 | grep -i "error\|exception"

# Check consumer thread pool utilization
kubectl exec -n audit deploy/audit-trail-service -- \
  curl -s http://localhost:8080/metrics | grep "jvm_threads_live"

# Check Kafka broker connectivity
kubectl exec -n audit deploy/audit-trail-service -- \
  curl -s http://localhost:8080/actuator/health | jq '.components.kafka'
```

**Resolution**:
1. Scale up replicas: `kubectl scale deploy audit-trail-service -n audit --replicas=5`
2. Increase consumer thread pool: Set `KAFKA_CONSUMER_THREADS=40` (default 20)
3. Check Kafka broker health: Ensure all brokers are running and healthy

### Database Connection Pool Exhausted

**Symptom**: `INSERT` operations slow/failing, `postgres_audit_db_connections_used > 40`

**Diagnosis**:
```bash
# Check active connections in PostgreSQL
kubectl exec -n audit postgres-0 -- \
  psql -U postgres -c "SELECT count(*) FROM pg_stat_activity WHERE datname='audit';"

# Check connection pool settings
kubectl exec -n audit deploy/audit-trail-service -- \
  curl -s http://localhost:8080/actuator/dbhealth | jq '.active_connections'
```

**Resolution**:
1. Increase connection pool: `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=30` (default 10)
2. Reduce connection lifetime: `SPRING_DATASOURCE_HIKARI_MAX_LIFETIME=600000` (10 min)
3. Monitor for query slowness: Long-running queries should be identified and optimized

### Partition Maintenance Failures

**Symptom**: New partitions not created, old partitions not archived

**Diagnosis**:
```bash
# Check maintenance job logs
kubectl logs -n audit deploy/audit-trail-service | grep "PartitionMaintenanceJob\|ShedLock"

# List existing partitions
kubectl exec -n audit postgres-0 -- \
  psql -U postgres audit_db -c "SELECT schemaname, tablename FROM pg_tables WHERE tablename LIKE 'audit_events_%';"
```

**Resolution**:
1. Check ShedLock table for stuck locks: `DELETE FROM shedlock WHERE name='partition-maintenance' AND locked_at < now() - interval '1 hour'`
2. Manually create future partitions: SQL script in `/deploy/scripts/create-audit-partitions.sql`
3. Archive old records: `kubectl exec deploy/audit-trail-service -- curl -X POST http://localhost:8080/admin/partition/archive`

## Related Documentation

- **ADR-014**: [Reconciliation Authority Model](/docs/adr/014-reconciliation-authority.md) - Audit trail as source of truth
- **ADR-015**: [SLO & Error Budget Policy](/docs/adr/015-slo-error-budget.md) - 99.9% availability targets
- **Runbook**: [audit-trail-runbook.md](/docs/services/audit-cdc-cluster/audit-trail-runbook.md)
- **Cluster Overview**: [audit-cdc-cluster README](/docs/services/audit-cdc-cluster/README.md)
- **Diagrams**: [Audit Trail Architecture](/docs/services/audit-trail-service/diagrams/)
