# Wave 40 Phase 6: Audit Trail (PCI DSS 7-Year Immutable Log) - Week 5

## Executive Summary
**Objective**: 7-year immutable audit log for payment settlement (PCI DSS compliance)
**Timeline**: Week 5 (April 14-18)
**Retention**: 7 years + archival to S3 Glacier
**Compliance**: PCI DSS 4.0 req 10.1 (activity logging), SOX 404

---

## Audit Log Design

### Scope: All Payment Events

```
1. Disbursement transactions (daily)
   - Amount, recipient, auth mechanism
   - Timestamp, actor, approver

2. Reconciliation runs (daily)
   - Mismatch count, auto-fixes applied
   - Manual reviews, final settlement

3. Settlement completions
   - Total confirmed, exceptions, reversals
   - Batch IDs, ledger balances

4. Admin actions
   - Manual reviews, approvals, overrides
   - Policy changes, configuration updates
```

### Immutability Strategy

**Dual-Write Pattern**:

```
┌─────────────────────────────────┐
│  Payment Transaction (Kafka)    │
└────────────┬────────────────────┘
             │
    ┌────────┴────────┐
    │                 │
    ▼                 ▼
PostgreSQL        Kafka Topic
(Hot/Active)      (Append-only)
  ↓                  ↓
7-day TTL       → S3 Glacier
                 (Immutable)
```

### PostgreSQL Schema

```sql
CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY,
  event_type VARCHAR NOT NULL,
    -- 'disbursement', 'reconciliation', 'settlement', 'admin_action'
  entity_id VARCHAR NOT NULL,
  actor_id VARCHAR NOT NULL,
  action VARCHAR NOT NULL,
  old_value JSONB,
  new_value JSONB,
  timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
  signature VARCHAR NOT NULL,  -- HMAC-SHA256 chain
  prev_signature VARCHAR,      -- Hash of previous entry
  created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Immutability: No UPDATE, only INSERT + SELECT
-- Retention: 7 years (policy enforced in app layer)

CREATE INDEX idx_audit_timestamp ON audit_log(timestamp DESC);
CREATE INDEX idx_audit_entity ON audit_log(entity_id, event_type);

-- Archive (after 7 days)
CREATE TABLE audit_log_archive (
  LIKE audit_log INCLUDING ALL
) PARTITION BY RANGE (EXTRACT(YEAR FROM timestamp));
```

### Kafka Append-Only Topic

**Topic**: `audit-trail-immutable`
**Partitions**: 12 (by entity_id hash)
**Retention**: Forever (or 30 days if storage constrained)
**Compression**: Off (audit data must survive hard failures)

**Record Schema**:
```json
{
  "id": "aud-20260321-001002",
  "timestamp": "2026-03-21T14:50:00Z",
  "event_type": "settlement_completed",
  "entity_id": "sett-20260321-003",
  "actor_id": "batch-job",
  "changes": {
    "total_amount": {"old": 0, "new": 1000000},
    "status": {"old": "pending", "new": "completed"}
  },
  "signature": "sha256_hash_of_json",
  "prev_signature": "hash_of_previous_entry"
}
```

### S3 Glacier Archival

**Pipeline** (monthly):

```python
# 1. Export 1 month of audit logs from PostgreSQL
SELECT * FROM audit_log WHERE timestamp >= %s - INTERVAL '1 month'

# 2. Serialize to Avro (columnar, efficient)
avro.write_to_file(logs, 'audit-logs-2026-03.avro')

# 3. Encrypt + upload to S3
s3.upload_large_file(
  'audit-logs-2026-03.avro.encrypted',
  bucket='instacommerce-audit',
  storage_class='GLACIER',  # $4/TB/month (vs $23 hot)
  encryption='aws:kms'
)

# 4. Verify + seal (create hash manifest)
manifest = {
  'file': 'audit-logs-2026-03.avro.encrypted',
  'hash_sha256': 'xyz...',
  'row_count': 2850000,
  'date_range': '2026-03-01 to 2026-03-31',
  'timestamp': now()
}
```

**Retrieval SLA**: 30 days (Glacier standard retrieval time)

---

## Audit Event Types

### 1. Disbursement Events

```sql
INSERT INTO audit_log VALUES (
  event_type='disbursement_created',
  entity_id='disb-202603-001',
  actor_id='reconciliation-engine',
  action='create',
  new_value={
    amount: 100000,
    recipient: 'seller-123',
    auth_method: 'auto_approved',
    reason: 'daily_settlement'
  },
  signature=HMAC_SHA256(...)
);
```

### 2. Reconciliation Events

```sql
INSERT INTO audit_log VALUES (
  event_type='reconciliation_run_completed',
  entity_id='recon-20260321',
  actor_id='batch-reconciliation',
  action='complete',
  new_value={
    mismatches_found: 3,
    auto_fixed: 2,
    manual_review_required: 1,
    total_variance: 500  -- cents
  },
  signature=HMAC_SHA256(...)
);
```

### 3. Admin Override Events

```sql
INSERT INTO audit_log VALUES (
  event_type='admin_override',
  entity_id='disb-202603-001',
  actor_id='alice@instacommerce.com',
  action='approve_after_review',
  old_value={status: 'pending_review'},
  new_value={status: 'approved', approver_notes: 'checked with CFO ok'},
  signature=HMAC_SHA256(...)
);
```

---

## Implementation

### Service: audit-trail-service (Spring Boot)

**Endpoints**:

```java
POST /events
{
  "event_type": "settlement_completed",
  "entity_id": "sett-20260321",
  "actor_id": "batch-job",
  "changes": {...}
}
// Response: 201 Created + event_id

GET /events?entity_id=sett-20260321&from_date=2026-01-01
// Response: All events for entity (immutable)

GET /export?from_date=2026-03-01&to_date=2026-03-31
// Response: S3 URL to Glacier archive (30-day retrieval)
```

---

## Compliance Mapping

| Requirement | Implementation | Status |
|-------------|-----------------|--------|
| **PCI DSS 10.1** | Immutable log in PostgreSQL + S3 | ✅ |
| **7-year retention** | Archival policy + S3 Glacier | ✅ |
| **Tamper detection** | HMAC-SHA256 signature chain | ✅ |
| **Non-repudiation** | Actor + timestamp signed | ✅ |
| **SOX 404** | Internal control documentation | 📋 Manual |

---

## Deployment (Week 5)

| Day | Task |
|-----|------|
| Mon | Create audit-trail-service (Spring Boot stub) |
| Tue | Implement audit_log table + archival job |
| Wed | Integration with reconciliation-engine |
| Thu | Staging validation (7-year sim) |
| Fri | Production deployment + runbooks |

---

## Monitoring

```prometheus
audit_events_total{event_type="settlement"}
audit_archival_latency_days{bucket="glacier"}
audit_log_integrity_checks{status="passed"}
```

**Success**: 100% of payment events logged + archived
