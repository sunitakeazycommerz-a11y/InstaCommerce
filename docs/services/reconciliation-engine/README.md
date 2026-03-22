# Reconciliation Engine

## Overview

The reconciliation-engine is the authoritative source for financial data integrity in InstaCommerce. It performs daily automated reconciliation between the payment ledger and order ledger, detects discrepancies, applies auto-fix rules, and maintains an immutable audit trail for PCI DSS compliance.

**Service Ownership**: Finance Platform Team - Core Services
**Language**: Go 1.22 + PostgreSQL
**Default Port**: 8098
**Status**: Tier 1 Critical (Financial reconciliation)

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **P99 Latency**: < 500ms for reconciliation queries
- **P99 Reconciliation Duration**: < 15 minutes (daily job)
- **Error Rate**: < 0.1% (all errors logged to audit trail)
- **Data Consistency**: 100% (ACID compliance via PostgreSQL transactions)

## Key Responsibilities

1. **Daily Reconciliation Job**: Atomic comparison of payment ledger vs order ledger, scheduled at 2 AM UTC
2. **Mismatch Detection**: Identify gaps, overages, missing transactions with auto-classification rules
3. **Auto-Fix Engine**: Apply predefined correction rules (e.g., duplicate refunds, timing mismatches, decimal rounding)
4. **Audit Trail**: Immutable event stream to PostgreSQL + S3 Glacier for 7-year regulatory retention

## Deployment

### GKE Deployment
- **Namespace**: finance
- **Replicas**: 3 (HA with singleton reconciliation job)
- **CPU Request/Limit**: 500m / 1000m
- **Memory Request/Limit**: 512Mi / 1Gi
- **StatefulSet**: Ensures single pod holds reconciliation lease

### Cluster Configuration
- **Ingress**: Internal-only (behind Istio IngressGateway, finance team only)
- **NetworkPolicy**: Deny-default; only allow from mobile-bff, checkout-orchestrator, admin-gateway
- **Service Account**: reconciliation-engine
- **Storage**: PersistentVolume for PostgreSQL connection pooling state

## Architecture

### Reconciliation Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│ Daily Scheduler (2 AM UTC)                                          │
└────────────────────────────┬────────────────────────────────────────┘
                             │
              1. Acquire exclusive lease (PostgreSQL advisory lock)
                             │
        ┌────────────────────▼────────────────────┐
        │ Reconciliation Engine                   │
        │  - Load payment ledger (last 24h)      │
        │  - Load order ledger (last 24h)        │
        │  - Detect mismatches                   │
        │  - Apply auto-fix rules                │
        │  - Persist to reconciliation schema    │
        └────────────────────┬────────────────────┘
                             │
        2. Publish Kafka events (Debezium CDC)
                             │
        ┌────────────────────▼────────────────────┐
        │ PostgreSQL Outbox                       │
        │  - ReconciliationStarted                │
        │  - MismatchFound (batch)                │
        │  - ReconciliationCompleted              │
        │  - FixApplied                           │
        └────────────────────┬────────────────────┘
                             │
        3. Immutable audit trail (S3 Glacier)
```

### Integrations

| Service | Endpoint | Timeout | Purpose |
|---------|----------|---------|---------|
| payment-service | PostgreSQL ledger | N/A | Read payment transactions (direct DB) |
| order-service | PostgreSQL ledger | N/A | Read order transactions (direct DB) |
| Kafka (reconciliation.cdc) | - | - | Publish reconciliation events |
| admin-gateway-service | http://reconciliation-engine:8098/pending | 5s | Query pending mismatches |

## Endpoints

### Public (Unauthenticated)
- `GET /health` - Liveness probe (reconciliation pod status)
- `GET /metrics` - Prometheus metrics (Go runtime, reconciliation counters)

### Internal API (Requires X-Internal-Token)
- `GET /api/v1/runs` - List reconciliation runs (last 30 days)
- `GET /api/v1/runs/{runId}/mismatches` - Get mismatches for a specific run
- `GET /api/v1/pending` - Current pending items (from admin-gateway)
- `POST /api/v1/mismatches/{mismatchId}/review` - Mark mismatch as reviewed (admin action)
- `POST /api/v1/mismatches/{mismatchId}/fix` - Manually apply fix (admin override)

### Example Requests

```bash
# Get authorization token
TOKEN=$(curl -X POST http://identity-service:8080/token/internal \
  -H "Content-Type: application/json" \
  -d '{"service":"admin-gateway-service"}' | jq -r '.token')

# List recent reconciliation runs
curl -X GET http://reconciliation-engine:8098/api/v1/runs?limit=10 \
  -H "X-Internal-Token: $TOKEN"

# Get mismatches from latest run
LATEST_RUN=$(curl -X GET http://reconciliation-engine:8098/api/v1/runs?limit=1 \
  -H "X-Internal-Token: $TOKEN" | jq -r '.[0].id')

curl -X GET http://reconciliation-engine:8098/api/v1/runs/$LATEST_RUN/mismatches \
  -H "X-Internal-Token: $TOKEN"

# Manually approve and fix a mismatch
curl -X POST http://reconciliation-engine:8098/api/v1/mismatches/{mismatchId}/fix \
  -H "X-Internal-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fixType":"manual_override","reason":"timing_discrepancy","approvedBy":"admin-team"}'
```

## Configuration

### Environment Variables
```env
PORT=8098
DATABASE_URL=postgresql://recon:password@postgres.database:5432/reconciliation?sslmode=require
DATABASE_MAX_CONNECTIONS=20
DATABASE_CONN_LIFETIME_SECONDS=1800

KAFKA_BROKERS=kafka-broker-0.kafka:9092,kafka-broker-1.kafka:9092,kafka-broker-2.kafka:9092
KAFKA_TOPIC_RECONCILIATION_CDC=reconciliation.cdc

RECONCILIATION_SCHEDULE_UTC=0 2 * * * (daily at 2 AM UTC)
RECONCILIATION_LEASE_TIMEOUT_SECONDS=3600
RECONCILIATION_AUTO_FIX_RULES=timing_threshold:300,decimal_rounding:0.01

AUDIT_S3_BUCKET=instacommerce-audit-trail
AUDIT_S3_RETENTION_YEARS=7
AUDIT_S3_ENCRYPTION=AES256

INTERNAL_TOKEN_SECRET=<shared secret from kubernetes secret>
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
OTEL_SERVICE_NAME=reconciliation-engine
```

### application.env (Go Configuration)
```env
# Reconciliation Engine Config
RECON_PAYMENT_LEDGER_QUERY_TIMEOUT_SECONDS=60
RECON_ORDER_LEDGER_QUERY_TIMEOUT_SECONDS=60
RECON_AUTO_FIX_ENABLED=true
RECON_DRY_RUN_ENABLED=false (set true for testing)

# Mismatch Classification Rules
RECON_MISMATCH_THRESHOLD_AMOUNT=0.01 (cents)
RECON_MISMATCH_THRESHOLD_TIME=300 (seconds, for timing mismatches)
RECON_DUPLICATE_DETECTION_WINDOW=60 (seconds)

# Logging
LOG_LEVEL=INFO
LOG_FORMAT=json (for structured logging)
```

## Monitoring & Alerts

### Key Metrics
- `reconciliation_run_duration_seconds` (histogram) - Daily job duration
- `reconciliation_mismatches_detected_total` (counter) - Mismatches found per run
- `reconciliation_auto_fixes_applied_total` (counter) - Auto-fixes applied
- `reconciliation_database_query_seconds` (histogram) - Ledger query latency
- `reconciliation_outbox_events_published_total` (counter) - Kafka events sent
- `go_goroutines` - Active goroutines (reconciliation work)
- `process_resident_memory_bytes` - Memory usage

### Alerting Rules
- `reconciliation_run_failed` - Job failed to complete (page immediately)
- `reconciliation_run_duration_seconds > 900` - Job exceeding 15-minute SLO (SEV-2)
- `reconciliation_mismatches_detected_total > 100` - Unusual spike in mismatches (review required)
- `reconciliation_auto_fix_failed_rate > 1%` - Fix engine errors (investigate rule quality)
- `reconciliation_database_connection_failures > 0` - Database connectivity issue (page)

### Logging
- **ERROR**: Job failures, database errors, Kafka publish failures
- **WARN**: Auto-fix failures, unusual mismatch patterns (threshold > 50 per run)
- **INFO**: Job started, mismatches detected, fixes applied (audit trail)
- **DEBUG**: Individual transaction matching logic (only in testing environments)

## Security Considerations

### Threat Mitigations
1. **Immutable Audit Trail**: All reconciliation operations logged to PostgreSQL + S3 Glacier (no deletion allowed)
2. **Token Scoping**: X-Internal-Token scoped to reconciliation-engine service (not shareable across services)
3. **Database Access Control**: Direct PostgreSQL connections use service account (least privilege)
4. **Admin Override Tracking**: All manual fixes require authentication + approval reason (compliance)
5. **Encryption in Transit**: TLS 1.3 for Kafka and PostgreSQL connections

### Known Risks
- **Ledger tampering**: Reconciliation relies on payment/order ledger integrity (mitigated by separate CDC)
- **Lease timeout race**: Two reconciliation jobs could run if lease holder crashes (mitigated by 1-hour timeout)
- **Kafka event loss**: If CDC consumer crashes, events buffered in Kafka topic (mitigated by Kafka replication)

## Troubleshooting

### Reconciliation Job Not Running
1. **Check lease**: `SELECT * FROM pg_locks WHERE pid = current_setting('backend_pid')`
2. **Verify schedule**: Check `RECONCILIATION_SCHEDULE_UTC` env var (should be `0 2 * * *`)
3. **Check logs**: `kubectl logs -n finance -l app=reconciliation-engine | grep -i scheduler`
4. **Manual trigger**: `curl -X POST http://reconciliation-engine:8098/api/v1/admin/trigger-reconciliation`

### High Mismatch Volumes
1. **Check for data quality issues**: Review order-service and payment-service logs for injection errors
2. **Adjust auto-fix rules**: Review `RECON_AUTO_FIX_RULES` - may be too strict
3. **Investigate timing**: Large timing mismatches suggest clock skew between services

### Database Connection Failures
1. **Verify credentials**: Check `DATABASE_URL` matches postgres service account
2. **Check pool exhaustion**: Monitor `reconciliation_database_connections_active` metric
3. **Verify network policy**: Ensure NetworkPolicy allows finance namespace to postgres

## Related Documentation

- **ADR-014**: Reconciliation Authority Model (design rationale, PCI DSS compliance)
- **Wave-36 Track C**: Reconciliation Logic and Scheduler (implementation guide)
- **Wave-37**: Integration tests and mismatch detection rules
- **Runbook**: reconciliation-engine/runbook.md
- **Schema**: reconciliation-engine/schema/reconciliation.sql (Flyway migrations)
