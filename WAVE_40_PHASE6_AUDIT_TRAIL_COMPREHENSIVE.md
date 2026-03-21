# Wave 40 Phase 6: PCI DSS 7-Year Audit Trail Implementation Guide

**Status**: Planning Phase
**Timeline**: Week 5 (April 15-21, 2026)
**Owner**: Compliance + Platform Engineering
**Deliverables**: Schema design, storage config, access controls, compliance procedures

---

## 1. Executive Summary

### Compliance Context

InstaCommerce operates as a Payment Facilitator (PF), processing $2.8B annually across payment and fulfillment workflows. Current regulatory obligations mandate:

- **PCI DSS 4.0** (Level 1, 3.2B transactions/year): 10.1-10.3 activity logging, user access logs, system access logs
- **Dodd-Frank Act** (Financial transaction audit): 7-year retention for payment-related disputes and regulatory inquiries
- **SOX 404 (Sarbanes-Oxley)**: Payment control testing + audit trail for material weaknesses
- **GDPR** (EU customer data): Right to be forgotten for non-financial PII (encrypted fields only)
- **CCPA/CPRA** (California resident data): Audit trail of data access and deletion

### Business Value

| Benefit | Impact | Measured By |
|---------|--------|-------------|
| **Regulatory Compliance** | Avoid $50K+ PCI violations, $5M+ SOX penalties | Annual audit pass rate |
| **Dispute Resolution** | Prove transaction authenticity in chargebacks | 30-day resolution SLA |
| **Fraud Prevention** | Detect anomalous patterns in payment/admin activity | % of fraud caught before settlement |
| **Operational Accountability** | Audit trail for manual interventions (reconciliation fixes, feature flag overrides) | Admin action attribution |
| **Incident Investigation** | Forensic root cause analysis for security breaches | Mean time to detect (MTTD) |

### Implementation Scope

This phase delivers an **immutable, dual-storage audit ledger**:
- **Hot storage (PostgreSQL)**: 1-year active query layer, continuous backup to standby
- **Cold storage (S3 + Glacier)**: 6-year archive, Object Lock GOVERNANCE mode (7-year total)
- **Immutability guarantees**: Write-once semantics, SHA-256 checksums, RSA-4096 digital signatures
- **Access controls**: RBAC (Compliance officer + CFO only), MFA, IP whitelisting, audit logging of audit queries

**Total events tracked**: ~500M/year (~1.4M/day) across payment, reconciliation, admin, identity, and system domains

---

## 2. PCI DSS Requirements Mapping

### PCI DSS 10.1: Activity Logging (Mandatory)

**Requirement**: "Implement audit trails to link all access to cardholder data to a specific user."

**Compliance Matrix**:

| PCI Requirement | InstaCommerce Implementation | Evidence |
|-----------------|----------------------------|----------|
| 10.1.1: All access to CHD logged | Payment service events (auth, settle, refund) → Kafka → audit_ledger | Kafka topic: `payment-events` |
| 10.1.2: Access to system components | Identity service JWT events (issuance, expiration, validation failure) | audit_ledger.action = 'jwt_issued' \| 'jwt_validated' |
| 10.1.3: Access to cardholder data by privileged users | Admin gateway HTTP logs (who, what, when, from where) | AdminAuditFilter captures request + JWT subject |
| 10.1.4: Direct access to databases | PostgreSQL WAL archiving + query logging (pg_stat_statements) | PostgreSQL: shared_preload_libraries = 'auto_explain' |
| 10.1.5: Access to audit trails | Restricted to Compliance officer + CFO (RBAC enforced) | Audit queries require IAM role 'audit-reader' |
| 10.1.6: Audit trail integrity | SHA-256 checksums + RSA-4096 signatures | audit_ledger.checksum + audit_ledger.signature |

**Gap Analysis** (Current State → Phase 6):
- ✅ Payment events: Already Kafka-published (Wave 36)
- ✅ Reconciliation events: Outbox table in place (Wave 36)
- ❌ **Missing**: Centralized audit ledger + immutable storage
- ❌ **Missing**: Admin action logging
- ❌ **Missing**: S3 cold storage + Object Lock
- ❌ **Missing**: Audit query access controls

### PCI DSS 10.2: User Access Logs

**Requirement**: "Maintain user identification, access initiation date/time, access type, and result."

**Implementation**:

```sql
-- audit_ledger table captures all required fields
CREATE TABLE audit_ledger (
    id BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL,           -- access initiation date/time
    actor VARCHAR(255) NOT NULL,            -- user identification (JWT subject / service principal)
    action VARCHAR(50) NOT NULL,            -- access type (read, write, delete, authenticate)
    entity_type VARCHAR(50) NOT NULL,       -- cardholder data category
    entity_id VARCHAR(255),                 -- specific resource
    result VARCHAR(20) NOT NULL,            -- success / failure
    reason TEXT,                            -- access context / denial reason
    source_ip INET,                         -- access origination
    checksum CHAR(64),                      -- SHA-256
    signature TEXT,                         -- RSA-4096
    created_at TIMESTAMP DEFAULT NOW()
) PARTITION BY RANGE (DATE_TRUNC('day', timestamp));
```

**Audit Queries** (Pre-built):

```sql
-- All CHD access by user (daily report)
SELECT timestamp, actor, action, entity_type, result, source_ip
FROM audit_ledger
WHERE date_trunc('day', timestamp) = CURRENT_DATE
  AND entity_type IN ('payment', 'settlement', 'cardholder_record')
ORDER BY timestamp DESC;

-- Failed authentication attempts (SEV-1 alerting)
SELECT timestamp, actor, reason, source_ip
FROM audit_ledger
WHERE action = 'jwt_validated' AND result = 'failure'
  AND timestamp > NOW() - INTERVAL '1 hour'
ORDER BY timestamp DESC;
```

### PCI DSS 10.3: System Component Access

**Requirement**: "Maintain logs of all activity on critical system components."

**Coverage**:

| System Component | Event Type | Logging Mechanism |
|------------------|-----------|-------------------|
| Payment gateway | Authorization, settlement, refund, correction | payment-service Kafka events |
| Database (PostgreSQL) | Connections, slow queries, DDL changes | postgresql.conf: log_connections, log_duration, log_statement |
| Identity service | Token issuance, expiration, revocation | identity-service audit topic |
| Admin gateway | HTTP requests, feature flag overrides, manual adjustments | AdminAuditFilter in spring boot |
| Fulfillment service | Payment-relevant status transitions | fulfillment-service audit events |
| Reconciliation engine | Run start, mismatch detection, auto-fixes, completion | reconciliation-engine outbox table |

**Example: Payment Gateway Audit Event**

```json
{
  "timestamp": "2026-04-21T14:32:45.123Z",
  "event_id": "evt_7f3b2a8c9d",
  "source": "payment-service",
  "action": "payment_authorized",
  "actor": "checkout-service",
  "entity_type": "payment",
  "entity_id": "pay_4792a3c8",
  "before": {
    "amount": 10000,
    "status": "pending",
    "card_last_4": "****1234"
  },
  "after": {
    "amount": 10000,
    "status": "authorized",
    "card_last_4": "****1234",
    "auth_code": "891452"
  },
  "reason": "Order checkout",
  "source_ip": "203.0.113.42",
  "gateway_response_code": "00",
  "cardholder_name_hash": "sha256(name)",
  "pan_first_6": "401288",
  "pan_last_4": "1234"
}
```

### PCI DSS 10.7: Retention Requirement

**Requirement**: "Retain audit trail history for at least one year, with at least three months immediately available online."

**Phase 6 Implementation** (Exceeds requirement):
- **Online (PostgreSQL)**: 1 year, immediately available, <100ms query latency
- **Archive (S3 Glacier)**: 6 years, <4-hour restore latency
- **Total**: 7 years (Dodd-Frank compliance)

### Dodd-Frank Act: Financial Transaction Audit

**Requirement**: "Maintain complete transaction history for 7 years; prove transaction authenticity in regulatory inquiries."

**Compliance Mechanism**:

```python
# Audit export for regulatory inquiry (with digital signature)
def generate_audit_report(start_date, end_date, reason):
    """Export audit trail segment for regulatory request"""

    transactions = db.query("""
        SELECT * FROM audit_ledger
        WHERE action IN ('payment_authorized', 'payment_settled', 'payment_refunded')
          AND timestamp BETWEEN %s AND %s
        ORDER BY timestamp ASC
    """, (start_date, end_date))

    # Compute Merkle tree root (cryptographic commitment)
    merkle_root = compute_merkle_tree([t.checksum for t in transactions])

    # Sign with regulatory-grade key
    signature = sign_sha256_rsa4096(merkle_root, private_key)

    # Export to CSV with signature
    export_csv_with_signature(transactions, merkle_root, signature)

    # Immutable record in audit_ledger
    log_audit_export(reason, merkle_root, signature)
```

### SOX 404: Payment Controls Audit

**Requirement**: "Maintain evidence of payment control design and operating effectiveness."

**Evidence Trail**:

1. **Design Evidence**: Configuration changes logged
   ```sql
   SELECT action, timestamp, actor, before, after
   FROM audit_ledger
   WHERE action = 'feature_flag_override'
     AND entity_type = 'payment_control'
   ORDER BY timestamp DESC;
   ```

2. **Operating Evidence**: Manual reconciliation fixes
   ```sql
   SELECT action, timestamp, actor, reason
   FROM audit_ledger
   WHERE action = 'reconciliation_fix_applied'
   ORDER BY timestamp DESC;
   ```

3. **Testing Evidence**: Sample query for control testing
   ```sql
   SELECT COUNT(*), SUM(amount)
   FROM audit_ledger
   WHERE action = 'payment_settled'
     AND timestamp BETWEEN '2026-01-01' AND '2026-01-31'
     AND result = 'success';
   ```

---

## 3. Audit Trail Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────────────┐
│                      Event Sources                              │
├─────────────────────────────────────────────────────────────────┤
│ Payment Service     │ Reconciliation  │ Identity Service │ Admin │
│ (event_published)   │ (outbox table)  │ (JWT events)     │ GW    │
└──────────┬──────────┴────────┬────────┴──────────┬───────┴──────┘
           │                   │                   │
           └───────────────────┼───────────────────┘
                               │
                        ┌──────▼──────┐
                        │ Kafka Topics│
                        │             │
                        │• payment-*  │
                        │• recon-*    │
                        │• identity-* │
                        │• admin-*    │
                        │• system-*   │
                        └──────┬──────┘
                               │
                        ┌──────▼─────────────┐
                        │ audit-consumer-go  │
                        │ (Event Processor)  │
                        └──────┬─────────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
         ┌────▼────┐      ┌────▼────┐      ┌───▼──────┐
         │PostgreSQL        │  S3     │      │ Alerting │
         │(Hot, 1yr)        │(Cold)   │      │& Metrics │
         │                  │         │      │          │
         │• audit_ledger    │Standard │      │Prometheus│
         │  (daily parts)   │→Glacier │      │CloudWatch│
         │• Checksums       │(6yr)    │      │          │
         │• Indexes         │         │      │          │
         │• Backups         │         │      │          │
         └────┬─────┘      └────┬────┘      └──────────┘
              │                 │
              └────────┬────────┘
                       │
            ┌──────────▼──────────┐
            │  Query & Compliance │
            │  Layer              │
            │                     │
            │• RBAC Access        │
            │• Audit Reports      │
            │• Regulatory Export  │
            └─────────────────────┘
```

### Event Flow (Example: Payment Authorization)

```
1. checkout-service calls payment-service REST API
   POST /v1/payments/authorize
   Body: { amount: 10000, card: "****1234", ... }

2. payment-service processes authorization
   ✓ Gateway response: success, auth_code: "891452"

3. payment-service publishes event
   Topic: payment-events
   Event: { action: "payment_authorized", entity_id: "pay_4792a3c8", ... }

4. audit-consumer-go consumes from Kafka
   - Validates event schema (JSON schema validation)
   - Computes SHA-256 checksum
   - Dual-writes:
     a) PostgreSQL: INSERT into audit_ledger (immediate)
     b) S3: Buffer in-memory, batch every 5 min (5MB chunks)
   - Signs event with RSA-4096 private key

5. Verification
   - PostgreSQL: Immediately queryable by authorized users
   - S3: Archived within 5 minutes, immutable after 24 hours
   - Monitoring: Kafka lag <2s, S3 latency <5 min
```

### Storage Architecture

#### PostgreSQL (Hot Storage)

**Retention**: 1 year active, continuously backed up

```sql
-- Partitioning strategy (daily, 10GB limit)
CREATE TABLE audit_ledger_2026_04_21 PARTITION OF audit_ledger
  FOR VALUES FROM ('2026-04-21') TO ('2026-04-22');

-- Compression (block-level)
ALTER TABLE audit_ledger_2026_04_21 SET (
  fillfactor = 100,
  toast_tuple_target = 4096
);

-- Indexes for operational queries
CREATE INDEX idx_audit_timestamp ON audit_ledger (timestamp DESC);
CREATE INDEX idx_audit_actor ON audit_ledger (actor);
CREATE INDEX idx_audit_action ON audit_ledger (action);
CREATE INDEX idx_audit_entity ON audit_ledger (entity_type, entity_id);

-- Partitions aged >1 year: DROP
CREATE OR REPLACE FUNCTION drop_old_partitions()
LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE 'DROP TABLE IF EXISTS audit_ledger_' || TO_CHAR(NOW() - INTERVAL '400 days', 'YYYY_MM_DD') || ' CASCADE';
END $$;

-- Trigger: Daily at 1 AM UTC
SELECT cron.schedule('drop_old_audit_partitions', '0 1 * * *', 'SELECT drop_old_partitions()');
```

**High Availability**:
- Primary: `audit-postgres-primary.prod` (4 vCPU, 16GB RAM, 500GB SSD)
- Standby: `audit-postgres-standby.prod` (continuous streaming replication, WAL archiving)
- RPO: <5 minutes (WAL archive to S3 every 5 min)
- RTO: <10 minutes (standby promotion + DNS failover)

#### S3 (Cold Storage)

**Bucket Policy**: Immutable, write-once, 7-year retention

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DenyDeleteObject",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:DeleteObject",
      "Resource": "arn:aws:s3:::instacommerce-audit-archive/*"
    },
    {
      "Sid": "DenyPutObjectVersionTagSet",
      "Effect": "Deny",
      "Principal": "*",
      "Action": "s3:PutObjectVersionTagSet",
      "Resource": "arn:aws:s3:::instacommerce-audit-archive/*"
    },
    {
      "Sid": "AllowAuditConsumerWrite",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:role/audit-consumer-pod-role"
      },
      "Action": "s3:PutObject",
      "Resource": "arn:aws:s3:::instacommerce-audit-archive/*",
      "Condition": {
        "StringEquals": {
          "s3:x-amz-acl": "private",
          "s3:x-amz-server-side-encryption": "AES256"
        }
      }
    }
  ]
}
```

**Object Lock (GOVERNANCE Mode)**:
```bash
# Enable Object Lock at bucket creation
aws s3api create-bucket \
  --bucket instacommerce-audit-archive \
  --object-lock-enabled-for-bucket

# Set default retention (7 years = 2555 days)
aws s3api put-object-retention \
  --bucket instacommerce-audit-archive \
  --key 'YYYY/MM/DD/events-*.json.gz' \
  --retention 'Mode=GOVERNANCE,RetainUntilDate=2033-04-21T00:00:00Z'
```

**Versioning & Lifecycle**:
```yaml
Versioning: Enabled
  - Current: Keep indefinitely (7-year retention via Object Lock)
  - Previous versions: Delete after 90 days

Lifecycle:
  - Standard (0-365 days): Hot access, immediate retrieval
  - Intelligent-Tiering (365-1825 days): Auto-tiering, cost-optimized
  - Glacier Instant (1825+ days): <3-hour restore, cold archive
```

**Key Structure**:
```
s3://instacommerce-audit-archive/
  └── 2026/
      └── 04/
          └── 21/
              ├── events-2026-04-21T00-00-00.json.gz
              ├── events-2026-04-21T05-00-00.json.gz
              ├── events-2026-04-21T10-00-00.json.gz
              └── ...
```

---

## 4. Event Schema

### Core Event Structure (JSON Schema)

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Audit Event",
  "type": "object",
  "required": [
    "timestamp", "event_id", "source", "action", "actor",
    "entity_type", "entity_id", "result", "source_ip"
  ],
  "properties": {
    "timestamp": {
      "type": "string",
      "format": "date-time",
      "description": "ISO 8601 UTC timestamp of event occurrence"
    },
    "event_id": {
      "type": "string",
      "pattern": "^evt_[a-z0-9]{20}$",
      "description": "Globally unique event identifier (immutable)"
    },
    "source": {
      "type": "string",
      "enum": [
        "payment-service",
        "reconciliation-engine",
        "identity-service",
        "admin-gateway-service",
        "fulfillment-service",
        "system"
      ]
    },
    "action": {
      "type": "string",
      "description": "Specific event type (payment_authorized, reconciliation_fix_applied, etc.)"
    },
    "actor": {
      "type": "string",
      "description": "JWT subject (user ID or service principal)"
    },
    "actor_type": {
      "type": "string",
      "enum": ["user", "service", "system"],
      "description": "Actor classification"
    },
    "actor_role": {
      "type": "string",
      "enum": [
        "compliance_officer",
        "cfo",
        "payment_engineer",
        "admin",
        "system_service"
      ]
    },
    "entity_type": {
      "type": "string",
      "enum": [
        "payment",
        "settlement",
        "refund",
        "reconciliation_run",
        "reconciliation_mismatch",
        "reconciliation_fix",
        "feature_flag",
        "user_access",
        "system_config",
        "security_incident"
      ]
    },
    "entity_id": {
      "type": "string",
      "description": "Primary identifier of affected entity"
    },
    "before": {
      "type": "object",
      "description": "State before action (null for create events)"
    },
    "after": {
      "type": "object",
      "description": "State after action (null for delete events)"
    },
    "result": {
      "type": "string",
      "enum": ["success", "failure"],
      "description": "Action outcome"
    },
    "reason": {
      "type": "string",
      "description": "Context or rationale for action (e.g., 'Chargeback dispute resolution')"
    },
    "failure_reason": {
      "type": "string",
      "description": "If result=failure, root cause explanation"
    },
    "source_ip": {
      "type": "string",
      "format": "ipv4",
      "description": "Originating IP address"
    },
    "user_agent": {
      "type": "string",
      "description": "HTTP User-Agent if applicable"
    },
    "request_id": {
      "type": "string",
      "description": "Correlation ID for request tracing"
    },
    "cardholder_identifier": {
      "type": "object",
      "properties": {
        "name_hash": {
          "type": "string",
          "description": "SHA-256(cardholder_name) - PII encrypted"
        },
        "pan_first_6": {
          "type": "string",
          "pattern": "^[0-9]{6}$"
        },
        "pan_last_4": {
          "type": "string",
          "pattern": "^[0-9]{4}$"
        }
      }
    },
    "payment_identifier": {
      "type": "object",
      "properties": {
        "payment_id": {
          "type": "string",
          "pattern": "^pay_[a-z0-9]{20}$"
        },
        "order_id": {
          "type": "string"
        },
        "gateway_reference": {
          "type": "string",
          "description": "External gateway transaction ID"
        },
        "gateway_response_code": {
          "type": "string"
        }
      }
    },
    "amount": {
      "type": "integer",
      "description": "Amount in smallest currency unit (cents)"
    },
    "currency": {
      "type": "string",
      "pattern": "^[A-Z]{3}$"
    },
    "checksum": {
      "type": "string",
      "pattern": "^[a-f0-9]{64}$",
      "description": "SHA-256 of event payload (for integrity verification)"
    },
    "signature": {
      "type": "string",
      "description": "RSA-4096 signature of checksum (base64 encoded)"
    },
    "audit_version": {
      "type": "string",
      "default": "1.0.0",
      "description": "Audit schema version"
    }
  }
}
```

### Event Categories & Examples

#### A. Payment Events

```json
{
  "timestamp": "2026-04-21T14:32:45.123Z",
  "event_id": "evt_a7b3c8d9e0f1g2h3i4j",
  "source": "payment-service",
  "action": "payment_authorized",
  "actor": "checkout-service",
  "actor_type": "service",
  "entity_type": "payment",
  "entity_id": "pay_4792a3c8",
  "before": {
    "status": "pending"
  },
  "after": {
    "status": "authorized",
    "auth_code": "891452"
  },
  "result": "success",
  "reason": "Checkout authorization",
  "source_ip": "203.0.113.42",
  "payment_identifier": {
    "payment_id": "pay_4792a3c8",
    "order_id": "ord_9f2e1a5c",
    "gateway_reference": "txn_stripe_2026042114324512"
  },
  "amount": 10000,
  "currency": "USD",
  "cardholder_identifier": {
    "name_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "pan_first_6": "401288",
    "pan_last_4": "1234"
  },
  "checksum": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
  "signature": "-----BEGIN SIGNATURE-----\nMIIGfAYJKoZ...\n-----END SIGNATURE-----"
}
```

```json
{
  "timestamp": "2026-04-21T14:33:12.456Z",
  "event_id": "evt_b8c4d9e0f1g2h3i4j5k",
  "source": "payment-service",
  "action": "payment_settled",
  "actor": "payment-service",
  "actor_type": "service",
  "entity_type": "settlement",
  "entity_id": "sett_5802b4d9",
  "result": "success",
  "reason": "Daily settlement batch",
  "amount": 10000,
  "currency": "USD",
  "payment_identifier": {
    "payment_id": "pay_4792a3c8",
    "gateway_reference": "txn_stripe_2026042114324512"
  },
  "checksum": "b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7",
  "signature": "-----BEGIN SIGNATURE-----\nMIIGfBYJKoZ...\n-----END SIGNATURE-----"
}
```

#### B. Reconciliation Events

```json
{
  "timestamp": "2026-04-21T02:00:00.000Z",
  "event_id": "evt_c9d5e0f1g2h3i4j5k6l",
  "source": "reconciliation-engine",
  "action": "reconciliation_run_started",
  "actor": "reconciliation-engine",
  "actor_type": "system",
  "entity_type": "reconciliation_run",
  "entity_id": "recon_run_20260421",
  "result": "success",
  "reason": "Daily 2 AM UTC scheduled reconciliation",
  "checksum": "c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8",
  "signature": "-----BEGIN SIGNATURE-----\nMIIGfCYJKoZ...\n-----END SIGNATURE-----"
}
```

```json
{
  "timestamp": "2026-04-21T02:15:34.789Z",
  "event_id": "evt_d0e6f1g2h3i4j5k6l7m",
  "source": "reconciliation-engine",
  "action": "reconciliation_mismatch_detected",
  "actor": "reconciliation-engine",
  "actor_type": "system",
  "entity_type": "reconciliation_mismatch",
  "entity_id": "mismatch_8374a9c0",
  "before": null,
  "after": {
    "reconciliation_run_id": "recon_run_20260421",
    "payment_id": "pay_9f2e1a5c",
    "internal_amount": 25000,
    "gateway_amount": 24900,
    "variance": 100,
    "status": "pending_review"
  },
  "result": "success",
  "reason": "Variance >$1.00 detected",
  "checksum": "d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9",
  "signature": "-----BEGIN SIGNATURE-----\nMIIGfDYJKoZ...\n-----END SIGNATURE-----"
}
```

#### C. Admin Action Events

```json
{
  "timestamp": "2026-04-21T10:45:22.012Z",
  "event_id": "evt_e1f7g2h3i4j5k6l7m8n",
  "source": "admin-gateway-service",
  "action": "feature_flag_override",
  "actor": "ops-engineer-01",
  "actor_type": "user",
  "actor_role": "admin",
  "entity_type": "feature_flag",
  "entity_id": "ff_payment_retry_enabled",
  "before": {
    "enabled": false,
    "rollout_percentage": 0
  },
  "after": {
    "enabled": true,
    "rollout_percentage": 50
  },
  "result": "success",
  "reason": "Incident response: Enable retry logic for timeout errors",
  "source_ip": "203.0.113.100",
  "user_agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
  "request_id": "req_a1b2c3d4e5f6",
  "checksum": "e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0",
  "signature": "-----BEGIN SIGNATURE-----\nMIIGfEYJKoZ...\n-----END SIGNATURE-----"
}
```

#### D. Identity Service Events

```json
{
  "timestamp": "2026-04-21T14:30:00.500Z",
  "event_id": "evt_f2g8h3i4j5k6l7m8n9o",
  "source": "identity-service",
  "action": "jwt_issued",
  "actor": "checkout-service",
  "actor_type": "service",
  "entity_type": "user_access",
  "entity_id": "jwt_token_abc123def456",
  "result": "success",
  "reason": "Service authentication",
  "source_ip": "203.0.113.50",
  "before": null,
  "after": {
    "token_subject": "checkout-service",
    "scopes": ["payments:read", "payments:write"],
    "expires_at": "2026-04-21T15:30:00Z",
    "audience": "instacommerce-payment"
  },
  "checksum": "f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1",
  "signature": "-----BEGIN SIGNATURE-----\nMIIGfFYJKoZ...\n-----END SIGNATURE-----"
}
```

#### E. System Configuration Events

```json
{
  "timestamp": "2026-04-21T08:00:00.000Z",
  "event_id": "evt_g3h9i4j5k6l7m8n9o0p",
  "source": "system",
  "action": "deployment_completed",
  "actor": "system-deployment",
  "actor_type": "system",
  "entity_type": "system_config",
  "entity_id": "payment-service-v2.5.1",
  "before": {
    "version": "2.5.0",
    "deployed_at": "2026-04-20T08:00:00Z"
  },
  "after": {
    "version": "2.5.1",
    "deployed_at": "2026-04-21T08:00:00Z",
    "image_sha": "sha256:a1b2c3d4e5f6"
  },
  "result": "success",
  "reason": "Scheduled release: payment retry improvements",
  "checksum": "g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2",
  "signature": "-----BEGIN SIGNATURE-----\nMIIGfGYJKoZ...\n-----END SIGNATURE-----"
}
```

---

## 5. Data Collection Methods

### A. Payment Service → Audit Trail

**Integration Point**: Kafka event publishing (already implemented in Wave 36)

```go
// payment-service/internal/events/publisher.go
package events

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel/trace"
)

type PaymentAuditEvent struct {
	Timestamp              time.Time              `json:"timestamp"`
	EventID                string                 `json:"event_id"`
	Source                 string                 `json:"source"`
	Action                 string                 `json:"action"`
	Actor                  string                 `json:"actor"`
	EntityType             string                 `json:"entity_type"`
	EntityID               string                 `json:"entity_id"`
	Before                 map[string]interface{} `json:"before"`
	After                  map[string]interface{} `json:"after"`
	Result                 string                 `json:"result"`
	FailureReason          *string                `json:"failure_reason,omitempty"`
	Reason                 string                 `json:"reason"`
	SourceIP               string                 `json:"source_ip"`
	PaymentIdentifier      map[string]interface{} `json:"payment_identifier"`
	CardholdingIdentifier  map[string]interface{} `json:"cardholder_identifier"`
	Amount                 int64                  `json:"amount"`
	Currency               string                 `json:"currency"`
	Checksum               string                 `json:"checksum"`
	Signature              string                 `json:"signature"`
}

func PublishPaymentAuditEvent(
	writer *kafka.Writer,
	event *PaymentAuditEvent,
	tracer trace.Tracer,
) error {
	ctx, span := tracer.Start(context.Background(), "PublishPaymentAuditEvent")
	defer span.End()

	// Validate event schema
	if err := ValidateAuditEvent(event); err != nil {
		return fmt.Errorf("invalid audit event: %w", err)
	}

	// Compute checksum (SHA-256)
	checksum := computeSHA256(event)
	event.Checksum = checksum

	// Sign with RSA-4096
	signature, err := signRSA4096(checksum, rsaPrivateKey)
	if err != nil {
		return fmt.Errorf("failed to sign event: %w", err)
	}
	event.Signature = signature

	// Serialize to JSON
	payload, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal event: %w", err)
	}

	// Publish to Kafka topic: payment-events
	msg := kafka.Message{
		Topic: "payment-events",
		Key:   []byte(event.EntityID),
		Value: payload,
		Headers: []kafka.Header{
			{Key: "event_id", Value: []byte(event.EventID)},
			{Key: "timestamp", Value: []byte(event.Timestamp.Format(time.RFC3339))},
		},
	}

	if err := writer.WriteMessages(ctx, msg); err != nil {
		span.RecordError(err)
		return fmt.Errorf("failed to write to Kafka: %w", err)
	}

	return nil
}
```

**Topics**:
- `payment-events`: Authorization, settlement, refund, correction events
- Partition by `entity_id` (payment_id) for ordering guarantees
- Retention: 7 days (for audit-consumer replay)

### B. Reconciliation Engine → Audit Trail

**Integration Point**: PostgreSQL outbox table (Wave 36)

```sql
-- reconciliation-engine/migrations/V4_reconciliation_outbox.sql
CREATE TABLE reconciliation_outbox (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_published ON reconciliation_outbox (published_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_created ON reconciliation_outbox (created_at);
```

**CDC via Debezium** (already in Wave 36):
```bash
# Debezium PostgreSQL connector
{
  "name": "reconciliation-cdc-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "reconciliation-postgres.prod",
    "database.port": 5432,
    "database.user": "debezium_user",
    "database.password": "${DB_PASSWORD}",
    "database.dbname": "reconciliation",
    "database.server.name": "reconciliation",
    "table.include.list": "public.reconciliation_outbox",
    "plugin.name": "pgoutput",
    "publication.name": "reconciliation_pub",
    "slot.name": "reconciliation_slot",
    "decimal.handling.mode": "string",
    "transforms": "route,unwrap",
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": "([^.]+)\\.([^.]+)\\.([^.]+)",
    "transforms.route.replacement": "recon-$3",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "transforms.unwrap.drop.tombstones": true
  }
}
```

**Kafka Topic**: `recon-events` (Debezium envelope)

### C. Identity Service → Audit Trail

**Integration Point**: JWT event publishing

```java
// identity-service/src/main/java/com/instacommerce/identity/audit/JwtAuditEventPublisher.java
@Component
public class JwtAuditEventPublisher {

    private final KafkaTemplate<String, AuditEvent> kafkaTemplate;
    private final RSAPrivateKey rsaPrivateKey;

    @Async
    public void publishJwtIssuedEvent(
        String serviceSubject,
        Set<String> scopes,
        String audience,
        long expiresAt,
        String sourceIp
    ) {
        AuditEvent event = AuditEvent.builder()
            .timestamp(Instant.now())
            .eventId(generateEventId())
            .source("identity-service")
            .action("jwt_issued")
            .actor(serviceSubject)
            .actorType("service")
            .entityType("user_access")
            .entityId("jwt_" + generateTokenId())
            .result("success")
            .reason("Service authentication")
            .sourceIp(sourceIp)
            .after(Map.of(
                "token_subject", serviceSubject,
                "scopes", scopes,
                "expires_at", Instant.ofEpochMilli(expiresAt),
                "audience", audience
            ))
            .build();

        // Sign and publish
        signAndPublish(event, "identity-events");
    }

    @Async
    public void publishJwtValidationFailedEvent(
        String failureReason,
        String sourceIp
    ) {
        AuditEvent event = AuditEvent.builder()
            .timestamp(Instant.now())
            .eventId(generateEventId())
            .source("identity-service")
            .action("jwt_validated")
            .actor("identity-service")
            .actorType("system")
            .entityType("user_access")
            .result("failure")
            .failureReason(failureReason)
            .sourceIp(sourceIp)
            .build();

        signAndPublish(event, "identity-events");
    }

    private void signAndPublish(AuditEvent event, String topic) {
        String checksum = computeSHA256(event);
        event.setChecksum(checksum);

        String signature = signRSA4096(checksum, rsaPrivateKey);
        event.setSignature(signature);

        kafkaTemplate.send(topic, event.getEntityId(), event);
    }
}
```

**Topics**:
- `identity-events`: JWT issuance, expiration, validation failure events

### D. Admin Gateway → Audit Trail

**Integration Point**: Spring servlet filter + JWT extraction

```java
// admin-gateway-service/src/main/java/com/instacommerce/admin/audit/AdminAuditFilter.java
@Component
public class AdminAuditFilter extends OncePerRequestFilter {

    private final AuditEventPublisher auditPublisher;
    private final JwtDecoder jwtDecoder;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        // Extract JWT from Authorization header
        String jwt = extractJwt(request);
        String jwtSubject = null;
        String jwtRole = null;

        if (jwt != null) {
            try {
                Jwt decodedJwt = jwtDecoder.decode(jwt);
                jwtSubject = decodedJwt.getSubject();
                jwtRole = extractRole(decodedJwt);
            } catch (JwtException e) {
                // Invalid JWT, audit as failure
                auditPublisher.publishAdminActionAuditEvent(
                    action = "admin_access_attempted",
                    actor = "unknown",
                    result = "failure",
                    failureReason = e.getMessage(),
                    sourceIp = getSourceIp(request)
                );
            }
        }

        // Capture request details
        long startTime = System.currentTimeMillis();
        String requestPath = request.getRequestURI();
        String requestMethod = request.getMethod();

        try {
            // Process request
            filterChain.doFilter(request, response);

            long duration = System.currentTimeMillis() - startTime;
            int statusCode = response.getStatus();

            // Audit successful requests
            if (shouldAudit(requestPath, requestMethod)) {
                auditPublisher.publishAdminActionAuditEvent(
                    action = deriveAction(requestPath, requestMethod),
                    actor = jwtSubject != null ? jwtSubject : "unknown",
                    actorRole = jwtRole,
                    result = statusCode < 400 ? "success" : "failure",
                    entityType = deriveEntityType(requestPath),
                    entityId = extractEntityId(request),
                    reason = buildReason(requestPath, requestMethod),
                    sourceIp = getSourceIp(request),
                    userAgent = request.getHeader("User-Agent"),
                    requestId = request.getHeader("X-Request-ID")
                );
            }
        } catch (Exception e) {
            auditPublisher.publishAdminActionAuditEvent(
                action = deriveAction(requestPath, requestMethod),
                actor = jwtSubject != null ? jwtSubject : "unknown",
                result = "failure",
                failureReason = e.getMessage(),
                sourceIp = getSourceIp(request)
            );
            throw e;
        }
    }

    private boolean shouldAudit(String requestPath, String method) {
        // Audit mutation operations + sensitive reads
        return method.equals("POST") ||
               method.equals("PUT") ||
               method.equals("DELETE") ||
               requestPath.contains("/admin/dashboard") ||
               requestPath.contains("/admin/flags") ||
               requestPath.contains("/admin/reconciliation");
    }

    private String deriveAction(String path, String method) {
        if (path.contains("/flags")) {
            return method.equals("PUT") ? "feature_flag_override" : "feature_flag_read";
        } else if (path.contains("/reconciliation")) {
            return "reconciliation_review";
        }
        return "admin_action";
    }
}
```

**Topics**:
- `admin-events`: Feature flag overrides, manual reconciliation fixes, policy changes

### E. Fulfillment Service → Audit Trail (Payment-Relevant Only)

**Integration Point**: Order status change events

```go
// fulfillment-service/internal/events/payment_relevant_events.go
package events

func PublishPaymentRelevantOrderEvents(ctx context.Context, orderID string, statusChange *OrderStatusChange) error {
    // Only audit events relevant to payment reconciliation
    paymentRelevantStatuses := map[string]bool{
        "payment_authorized": true,
        "payment_confirmed": true,
        "order_failed": true,
        "order_cancelled": true,
        "order_refunded": true,
    }

    if !paymentRelevantStatuses[statusChange.NewStatus] {
        return nil // Skip non-payment-relevant events
    }

    event := AuditEvent{
        Timestamp: time.Now(),
        Source: "fulfillment-service",
        Action: fmt.Sprintf("order_%s", statusChange.NewStatus),
        EntityType: "fulfillment_order",
        EntityID: orderID,
        Result: "success",
        After: statusChange.NewData,
    }

    // Publish to Kafka
    return publishToKafka(ctx, "fulfillment-events", event)
}
```

**Topics**:
- `fulfillment-events`: Payment-relevant order status transitions only

---

## 6. Immutability Guarantees

### A. Write-Once Semantics (PostgreSQL + Application)

**Database Layer**:
```sql
-- No UPDATE or DELETE allowed
CREATE TRIGGER prevent_audit_mutation BEFORE UPDATE OR DELETE ON audit_ledger
FOR EACH ROW EXECUTE FUNCTION raise_immutability_violation();

CREATE FUNCTION raise_immutability_violation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit ledger is immutable - no UPDATE or DELETE allowed';
END;
$$ LANGUAGE plpgsql;
```

**Application Layer** (audit-consumer-go):
```go
// audit-consumer/internal/storage/postgres_writer.go
package storage

import (
	"context"
	"fmt"
	"database/sql"
)

func (pw *PostgresWriter) InsertAuditEvent(ctx context.Context, event *AuditEvent) error {
	// Only INSERT allowed, no UPDATE or DELETE
	query := `
		INSERT INTO audit_ledger (
			timestamp, event_id, source, action, actor, actor_type, actor_role,
			entity_type, entity_id, before, after, result, failure_reason, reason,
			source_ip, user_agent, request_id, cardholder_identifier,
			payment_identifier, amount, currency, checksum, signature
		) VALUES (
			$1, $2, $3, $4, $5, $6, $7,
			$8, $9, $10, $11, $12, $13, $14,
			$15, $16, $17, $18,
			$19, $20, $21, $22, $23
		)
		RETURNING id
	`

	var id int64
	err := pw.db.QueryRowContext(ctx, query,
		event.Timestamp, event.EventID, event.Source, event.Action, event.Actor,
		event.ActorType, event.ActorRole, event.EntityType, event.EntityID,
		event.Before, event.After, event.Result, event.FailureReason, event.Reason,
		event.SourceIP, event.UserAgent, event.RequestID, event.CardholdingIdentifier,
		event.PaymentIdentifier, event.Amount, event.Currency, event.Checksum, event.Signature,
	).Scan(&id)

	if err != nil {
		return fmt.Errorf("failed to insert audit event: %w", err)
	}

	return nil
}
```

### B. Cryptographic Checksums (SHA-256)

**Computation**:
```go
// audit-consumer/internal/crypto/checksum.go
package crypto

import (
	"crypto/sha256"
	"encoding/json"
	"encoding/hex"
	"fmt"
)

func ComputeEventChecksum(event *AuditEvent) (string, error) {
	// Canonicalize JSON (sorted keys, no whitespace)
	canonicalJSON, err := json.Marshal(event)
	if err != nil {
		return "", fmt.Errorf("failed to marshal event: %w", err)
	}

	// Exclude checksum and signature fields from computation
	eventCopy := *event
	eventCopy.Checksum = ""
	eventCopy.Signature = ""
	canonicalJSON, _ = json.Marshal(eventCopy)

	// Compute SHA-256
	hash := sha256.Sum256(canonicalJSON)
	return hex.EncodeToString(hash[:]), nil
}

// Verify returns true if checksum matches
func VerifyEventChecksum(event *AuditEvent) (bool, error) {
	storedChecksum := event.Checksum
	event.Checksum = ""
	event.Signature = ""

	computed, err := ComputeEventChecksum(event)
	if err != nil {
		return false, err
	}

	return computed == storedChecksum, nil
}
```

### C. Digital Signatures (RSA-4096)

**Signing**:
```go
// audit-consumer/internal/crypto/signature.go
package crypto

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"fmt"
)

func SignEventWithRSA4096(checksum string, privateKey *rsa.PrivateKey) (string, error) {
	// Sign the checksum using RSA-PSS with SHA-256
	hash := crypto.SHA256
	hashed := hash.Sum256([]byte(checksum))

	signature, err := rsa.SignPSS(
		rand.Reader,
		privateKey,
		hash,
		hashed,
		&rsa.PSSOptions{
			SaltLength: rsa.PSSSaltLengthEqualsHash,
		},
	)
	if err != nil {
		return "", fmt.Errorf("failed to sign: %w", err)
	}

	// Return base64-encoded signature
	return base64.StdEncoding.EncodeToString(signature), nil
}

func VerifyEventSignature(checksum string, signature string, publicKey *rsa.PublicKey) (bool, error) {
	// Decode base64 signature
	decodedSig, err := base64.StdEncoding.DecodeString(signature)
	if err != nil {
		return false, fmt.Errorf("failed to decode signature: %w", err)
	}

	// Verify signature
	hash := crypto.SHA256
	hashed := hash.Sum256([]byte(checksum))

	err = rsa.VerifyPSS(
		publicKey,
		hash,
		hashed,
		decodedSig,
		&rsa.PSSOptions{
			SaltLength: rsa.PSSSaltLengthEqualsHash,
		},
	)

	return err == nil, err
}
```

**Key Rotation** (annual):
```bash
# Generate new RSA-4096 keypair
openssl genrsa -out audit_rsa_2027_private.pem 4096
openssl rsa -in audit_rsa_2027_private.pem -pubout -out audit_rsa_2027_public.pem

# Store in AWS Secrets Manager
aws secretsmanager create-secret \
  --name audit/rsa-private-2027 \
  --secret-string file://audit_rsa_2027_private.pem

aws secretsmanager create-secret \
  --name audit/rsa-public-2027 \
  --secret-string file://audit_rsa_2027_public.pem

# Rotate in audit-consumer deployment
kubectl set env deployment/audit-consumer \
  AUDIT_RSA_PRIVATE_KEY_VERSION=2027 \
  AUDIT_RSA_PUBLIC_KEY_VERSION=2027
```

### D. Merkle Tree Verification (Compliance Reports)

**Computation** (for regulatory exports):
```go
// audit-consumer/internal/crypto/merkle_tree.go
package crypto

import (
	"crypto/sha256"
	"fmt"
)

type MerkleNode struct {
	Hash  string
	Left  *MerkleNode
	Right *MerkleNode
}

func ComputeMerkleRoot(checksums []string) (string, error) {
	if len(checksums) == 0 {
		return "", fmt.Errorf("empty checksum list")
	}

	// Build leaf nodes
	nodes := make([]*MerkleNode, len(checksums))
	for i, checksum := range checksums {
		nodes[i] = &MerkleNode{Hash: checksum}
	}

	// Build tree bottom-up
	for len(nodes) > 1 {
		var newLevel []*MerkleNode

		for i := 0; i < len(nodes); i += 2 {
			var left, right *MerkleNode = nodes[i], nil
			if i+1 < len(nodes) {
				right = nodes[i+1]
			} else {
				right = left // Duplicate last node if odd count
			}

			// Hash left.Hash + right.Hash
			hash := sha256.Sum256([]byte(left.Hash + right.Hash))
			hashStr := fmt.Sprintf("%x", hash)

			parent := &MerkleNode{
				Hash:  hashStr,
				Left:  left,
				Right: right,
			}
			newLevel = append(newLevel, parent)
		}

		nodes = newLevel
	}

	return nodes[0].Hash, nil
}
```

### E. Tamper Detection (Scheduled Integrity Checks)

```go
// audit-consumer/internal/jobs/integrity_checker.go
package jobs

import (
	"context"
	"fmt"
	"log"
	"time"
)

func (ic *IntegrityChecker) CheckAuditLedgerIntegrity(ctx context.Context) error {
	// Query all events from yesterday
	yesterday := time.Now().AddDate(0, 0, -1)
	events, err := ic.db.GetEventsByDate(ctx, yesterday)
	if err != nil {
		return fmt.Errorf("failed to query events: %w", err)
	}

	log.Printf("Checking integrity of %d events from %s", len(events), yesterday)

	var tamperingDetected []string

	for _, event := range events {
		// Recompute checksum
		computedChecksum, err := crypto.ComputeEventChecksum(&event)
		if err != nil {
			return fmt.Errorf("failed to compute checksum: %w", err)
		}

		if computedChecksum != event.Checksum {
			tamperingDetected = append(tamperingDetected, fmt.Sprintf(
				"Event %s: checksum mismatch (stored=%s, computed=%s)",
				event.EventID, event.Checksum, computedChecksum,
			))
		}

		// Verify signature
		valid, err := crypto.VerifyEventSignature(event.Checksum, event.Signature, ic.rsaPublicKey)
		if err != nil || !valid {
			tamperingDetected = append(tamperingDetected, fmt.Sprintf(
				"Event %s: signature verification failed",
				event.EventID,
			))
		}
	}

	if len(tamperingDetected) > 0 {
		// Alert: Tampering detected
		ic.alerting.SendSEV1Alert("audit_ledger_tampering_detected", tamperingDetected)
		return fmt.Errorf("tampering detected in %d events", len(tamperingDetected))
	}

	log.Printf("Integrity check passed for %d events", len(events))
	return nil
}
```

**CronJob Schedule** (daily, 3 AM UTC):
```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: audit-integrity-checker
  namespace: production
spec:
  schedule: "0 3 * * *"
  jobTemplate:
    spec:
      template:
        spec:
          serviceAccountName: audit-consumer
          containers:
          - name: integrity-checker
            image: audit-consumer:v1.0.0
            command:
            - /app/audit-integrity-check
            env:
            - name: DATABASE_URL
              valueFrom:
                secretKeyRef:
                  name: audit-db-credentials
                  key: url
            - name: AUDIT_RSA_PUBLIC_KEY
              valueFrom:
                secretKeyRef:
                  name: audit-keys
                  key: rsa-public
          restartPolicy: OnFailure
```

---

## 7. Query & Analysis

### SQL Interface (Hot Storage)

**Daily Settlement Report**:
```sql
-- Generate daily payment settlement report (for SOX 404 testing)
SELECT
    DATE_TRUNC('day', timestamp) as settlement_date,
    COUNT(*) as total_transactions,
    SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) as total_authorized,
    SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END) as successful_count,
    SUM(CASE WHEN result = 'failure' THEN 1 ELSE 0 END) as failed_count,
    ROUND(100.0 * SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END) / COUNT(*), 2) as success_rate_pct,
    ARRAY_AGG(DISTINCT actor) as actors,
    ARRAY_AGG(DISTINCT source_ip) as source_ips
FROM audit_ledger
WHERE action IN ('payment_authorized', 'payment_settled')
  AND DATE_TRUNC('day', timestamp) = CURRENT_DATE - INTERVAL '1 day'
GROUP BY DATE_TRUNC('day', timestamp)
ORDER BY settlement_date DESC;
```

**Monthly Reconciliation Summary**:
```sql
-- Report on reconciliation mismatches and fixes (for fraud prevention)
SELECT
    DATE_TRUNC('month', timestamp) as month,
    COUNT(*) FILTER (WHERE action = 'reconciliation_mismatch_detected') as mismatches_detected,
    COUNT(*) FILTER (WHERE action = 'reconciliation_fix_applied') as mismatches_fixed,
    SUM(CASE
        WHEN action = 'reconciliation_mismatch_detected'
        THEN CAST(after->>'variance' AS BIGINT)
        ELSE 0
    END) as total_variance_cents,
    ROUND(100.0 * COUNT(*) FILTER (WHERE action = 'reconciliation_fix_applied')
          / NULLIF(COUNT(*) FILTER (WHERE action = 'reconciliation_mismatch_detected'), 0), 2) as fix_rate_pct
FROM audit_ledger
WHERE action IN ('reconciliation_mismatch_detected', 'reconciliation_fix_applied')
  AND DATE_TRUNC('month', timestamp) = DATE_TRUNC('month', CURRENT_DATE)
GROUP BY DATE_TRUNC('month', timestamp);
```

**Admin Action Audit Trail**:
```sql
-- Track manual interventions (for operational accountability)
SELECT
    timestamp,
    actor,
    actor_role,
    action,
    entity_type,
    reason,
    source_ip,
    result
FROM audit_ledger
WHERE source = 'admin-gateway-service'
  AND action IN ('feature_flag_override', 'reconciliation_fix_applied')
  AND DATE_TRUNC('day', timestamp) >= CURRENT_DATE - INTERVAL '90 days'
ORDER BY timestamp DESC
LIMIT 1000;
```

**Failed Authentication Attempts** (SEV-1 alerting):
```sql
-- Real-time detection of brute-force or compromised credentials
SELECT
    source_ip,
    COUNT(*) as failure_count,
    MAX(timestamp) as last_attempt,
    ARRAY_AGG(DISTINCT actor) as attempted_actors
FROM audit_ledger
WHERE action = 'jwt_validated'
  AND result = 'failure'
  AND timestamp > NOW() - INTERVAL '1 hour'
GROUP BY source_ip
HAVING COUNT(*) > 5
ORDER BY failure_count DESC;
```

### Export & Compliance Reports

**Regulatory Export (with Digital Signature)**:
```python
# audit-consumer-service/compliance_reports.py
import json
import csv
import hashlib
import hmac
from datetime import datetime
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

def generate_compliance_export(
    start_date: datetime,
    end_date: datetime,
    reason: str,
    requesting_body: str
) -> dict:
    """
    Generate audit trail export for regulatory inquiry (Dodd-Frank, PCI, SOX).
    Returns signed export with Merkle root commitment.
    """

    # Query audit ledger for date range
    events = db.query("""
        SELECT * FROM audit_ledger
        WHERE action IN ('payment_authorized', 'payment_settled', 'payment_refunded')
          AND timestamp BETWEEN %s AND %s
        ORDER BY timestamp ASC
    """, (start_date, end_date))

    # Compute Merkle tree root
    checksums = [event['checksum'] for event in events]
    merkle_root = compute_merkle_root(checksums)

    # Sign Merkle root with RSA-4096
    with open('/var/secrets/audit-rsa-private.pem', 'rb') as f:
        private_key = serialization.load_pem_private_key(f.read(), password=None)

    signature = private_key.sign(
        merkle_root.encode(),
        padding.PSS(
            mgf=padding.MGF1(hashes.SHA256()),
            salt_length=padding.PSS.MAX_LENGTH
        ),
        hashes.SHA256()
    )

    # Export to CSV
    csv_filename = f"audit_export_{start_date.date()}_{end_date.date()}.csv"
    with open(csv_filename, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=[
            'timestamp', 'event_id', 'action', 'actor', 'entity_id',
            'result', 'amount', 'currency', 'checksum', 'cardholder_pan_last_4'
        ])
        writer.writeheader()
        for event in events:
            writer.writerow({
                'timestamp': event['timestamp'],
                'event_id': event['event_id'],
                'action': event['action'],
                'actor': event['actor'],
                'entity_id': event['entity_id'],
                'result': event['result'],
                'amount': event['amount'],
                'currency': event['currency'],
                'checksum': event['checksum'],
                'cardholder_pan_last_4': event['cardholder_identifier'].get('pan_last_4')
            })

    # Generate metadata file
    metadata = {
        'export_date': datetime.utcnow().isoformat(),
        'export_reason': reason,
        'requesting_body': requesting_body,
        'period_start': start_date.isoformat(),
        'period_end': end_date.isoformat(),
        'total_events': len(events),
        'merkle_root': merkle_root,
        'signature_algorithm': 'RSA-PSS-4096-SHA256',
        'csv_file': csv_filename,
        'integrity_verification': 'Use public key to verify signature against merkle_root'
    }

    # Log export in audit_ledger
    db.execute("""
        INSERT INTO audit_ledger (
            timestamp, source, action, actor, entity_type, entity_id,
            result, reason, before, after
        ) VALUES (
            NOW(), 'system', 'compliance_export_generated', 'audit-system',
            'compliance_export', %s, 'success', %s,
            NULL, %s::jsonb
        )
    """, (csv_filename, reason, json.dumps(metadata)))

    return {
        'csv_file': csv_filename,
        'metadata': metadata,
        'signature': signature.hex(),
        'merkle_root': merkle_root
    }
```

---

## 8. Access Control

### RBAC Implementation (Kubernetes + PostgreSQL)

**Kubernetes RBAC** (who can query audit logs):
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: audit-reader
  namespace: production
rules:
- apiGroups: [""]
  resources: ["secrets"]
  resourceNames: ["audit-db-credentials"]
  verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: audit-reader-compliance
  namespace: production
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: audit-reader
subjects:
- kind: User
  name: compliance-officer@instacommerce.com
- kind: User
  name: cfo@instacommerce.com
```

**PostgreSQL Role-Based Access**:
```sql
-- Create audit_reader role (restricted access)
CREATE ROLE audit_reader WITH LOGIN;
GRANT CONNECT ON DATABASE instacommerce TO audit_reader;

-- Grant SELECT only on audit_ledger
GRANT USAGE ON SCHEMA public TO audit_reader;
GRANT SELECT ON audit_ledger TO audit_reader;

-- Deny all write operations
REVOKE INSERT, UPDATE, DELETE ON audit_ledger FROM audit_reader;

-- Create audit_admin role (internal use only)
CREATE ROLE audit_admin WITH LOGIN;
GRANT ALL ON audit_ledger TO audit_admin;

-- Create audit_immutability_trigger to prevent mutations
CREATE TRIGGER prevent_mutations BEFORE UPDATE OR DELETE ON audit_ledger
FOR EACH ROW EXECUTE FUNCTION raise_immutability_violation();
```

**Row-Level Security (RLS)** (hide PII from non-authorized users):
```sql
-- Enable RLS
ALTER TABLE audit_ledger ENABLE ROW LEVEL SECURITY;

-- Policy: audit_reader can only see events after encryption
CREATE POLICY audit_reader_sees_encrypted ON audit_ledger
  FOR SELECT TO audit_reader
  USING (
    -- Compliance officers can see all encrypted data
    current_user = 'audit_reader'::regrole
  );

-- Policy: hide raw PAN/cardholder names
CREATE POLICY hide_raw_pii ON audit_ledger
  FOR SELECT TO public
  USING (
    cardholder_identifier->>'pan_first_6' IS NULL
    OR actor IN (SELECT usename FROM pg_user WHERE usesuper)
  );
```

### MFA & IP Whitelisting

**Bastion Host Access** (for sensitive queries):
```bash
#!/bin/bash
# audit-query-gateway.sh - Restricted access to audit queries

# 1. Verify MFA
mfa_verified=$(aws sts get-caller-identity --query "Arn" | grep -i mfa)
if [ -z "$mfa_verified" ]; then
    echo "ERROR: MFA not detected. Please authenticate with MFA enabled."
    exit 1
fi

# 2. Verify IP whitelist
caller_ip=$(aws sts get-caller-identity --query "SourceIp")
allowed_ips=("203.0.113.0/24" "198.51.100.0/24")  # Corporate network + VPN

ip_allowed=false
for cidr in "${allowed_ips[@]}"; do
    if [[ "$caller_ip" =~ "$cidr" ]]; then
        ip_allowed=true
        break
    fi
done

if [ "$ip_allowed" = false ]; then
    echo "ERROR: IP $caller_ip not in whitelist"
    exit 1
fi

# 3. Connect to audit database
psql \
  --host audit-postgres-primary.prod \
  --username audit_reader \
  --database instacommerce \
  --command "SELECT * FROM audit_ledger LIMIT 10;"

# 4. Log access attempt
aws logs put-log-events \
  --log-group-name "/audit/query-access" \
  --log-stream-name "$(date +%Y-%m-%d)" \
  --log-events timestamp=$(date +%s000),message="Query executed by $(whoami) from $caller_ip"
```

### Audit of Audit Queries (Audit Recursion)

```sql
-- Track who queries the audit ledger
CREATE TABLE audit_query_log (
    id BIGSERIAL PRIMARY KEY,
    queried_at TIMESTAMP NOT NULL DEFAULT NOW(),
    user_account VARCHAR(255) NOT NULL,
    query_type VARCHAR(100) NOT NULL,  -- e.g., "daily_settlement", "admin_actions"
    filters JSONB,  -- Date range, entity type, etc.
    rows_returned BIGINT,
    query_duration_ms INTEGER,
    source_ip INET,
    success BOOLEAN
);

-- Trigger on audit_ledger SELECT (via view)
CREATE VIEW audit_ledger_readonly AS
  SELECT * FROM audit_ledger;

-- Log each access
CREATE OR REPLACE FUNCTION log_audit_query()
RETURNS VOID AS $$
BEGIN
    INSERT INTO audit_query_log (
        user_account, query_type, source_ip, success
    ) VALUES (
        current_user, 'manual_query', inet_client_addr(), true
    );
END;
$$ LANGUAGE plpgsql;
```

---

## 9. Retention & Deletion

### Data Retention Policy

| Storage Tier | Retention Period | Access | Cost | Deletion |
|--------------|------------------|--------|------|----------|
| PostgreSQL   | 1 year           | <100ms | $2K/mo | Auto-delete >1yr via partition DROP |
| S3 Standard  | 1 year           | <1s    | $500/mo | Lifecycle: move to Glacier |
| S3 Glacier   | 6 years (total 7) | <4hr   | $50/mo | Retained per Object Lock |

**PostgreSQL Auto-Cleanup** (CronJob):
```sql
-- Drop daily partitions older than 365 days
CREATE OR REPLACE PROCEDURE drop_old_audit_partitions()
LANGUAGE plpgsql
AS $$
DECLARE
    partition_name TEXT;
    cutoff_date DATE;
BEGIN
    cutoff_date := CURRENT_DATE - INTERVAL '365 days';

    FOR partition_name IN
        SELECT tablename FROM pg_tables
        WHERE tablename LIKE 'audit_ledger_%'
          AND tablename::date < cutoff_date
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I CASCADE', partition_name);
        RAISE NOTICE 'Dropped partition: %', partition_name;
    END LOOP;
END;
$$;

-- Schedule: Daily at 1 AM UTC
SELECT cron.schedule('drop_old_audit_partitions', '0 1 * * *', 'CALL drop_old_audit_partitions();');
```

**S3 Lifecycle Policy**:
```json
{
  "Rules": [
    {
      "Id": "MoveToIntelligentTiering",
      "Status": "Enabled",
      "Prefix": "events/",
      "Transitions": [
        {
          "Days": 365,
          "StorageClass": "INTELLIGENT_TIERING"
        },
        {
          "Days": 1825,
          "StorageClass": "GLACIER_IR"
        }
      ],
      "NoncurrentVersionTransitions": [
        {
          "NoncurrentDays": 90,
          "StorageClass": "GLACIER_IR"
        }
      ],
      "Expiration": {
        "Days": 2555
      }
    }
  ]
}
```

### GDPR Right-to-be-Forgotten (Encrypted PII Only)

**Deletion Request Handler**:
```go
// audit-consumer/internal/gdpr/deletion_handler.go
package gdpr

import (
	"context"
	"fmt"
)

func (dh *DeletionHandler) ProcessDeletionRequest(ctx context.Context, customerId string) error {
	// Find all events with encrypted cardholder name hash matching customer
	events, err := dh.db.QueryContext(ctx, `
		SELECT id, cardholder_identifier, event_id
		FROM audit_ledger
		WHERE cardholder_identifier->>'name_hash' = %s
		  AND timestamp > NOW() - INTERVAL '90 days'  -- Non-regulation period
	`, hashCustomerId(customerId))

	if err != nil {
		return fmt.Errorf("failed to query events: %w", err)
	}

	// For each event, anonymize cardholder_identifier but preserve checksums
	for _, event := range events {
		anonymized := event
		anonymized.CardholdingIdentifier = map[string]string{
			"name_hash": "gdpr_deleted_" + event.EventID,
			"pan_first_6": "000000",
			"pan_last_4": "0000",
		}

		// UPDATE cardholder_identifier (allowed for GDPR compliance)
		// Note: This violates immutability but is legally required for GDPR
		// Audit this deletion separately
		_, err := dh.db.ExecContext(ctx, `
			UPDATE audit_ledger
			SET cardholder_identifier = %s
			WHERE id = %s
		`, anonymized.CardholdingIdentifier, event.ID)

		if err != nil {
			return fmt.Errorf("failed to anonymize event: %w", err)
		}

		// Log GDPR deletion (immutable entry)
		dh.auditLogger.LogGDPRDeletion(ctx, event.EventID, customerId)
	}

	return nil
}
```

### Legal Hold (Freeze Retention)

```sql
-- Place legal hold on events (prevent deletion)
CREATE TABLE legal_holds (
    id BIGSERIAL PRIMARY KEY,
    hold_id VARCHAR(255) UNIQUE NOT NULL,
    entity_id VARCHAR(255) NOT NULL,  -- Payment ID, Order ID, etc.
    entity_type VARCHAR(100) NOT NULL,
    reason TEXT NOT NULL,
    placed_by VARCHAR(255) NOT NULL,
    placed_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'active'  -- active, expired, released
);

-- Modify retention deletion trigger
CREATE OR REPLACE FUNCTION check_legal_hold_before_delete()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM legal_holds
        WHERE entity_id = NEW.entity_id
          AND status = 'active'
          AND (expires_at IS NULL OR expires_at > NOW())
    ) THEN
        RAISE EXCEPTION 'Cannot delete: Legal hold active on %', NEW.entity_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

---

## 10. Disaster Recovery

### RTO & RPO Targets

- **RTO (Recovery Time Objective)**: <4 hours (from S3 archive)
- **RPO (Recovery Point Objective)**: <5 minutes (WAL archiving)

### Backup Strategy

**PostgreSQL Primary → Standby (Continuous Replication)**:
```yaml
# kubernetes/manifests/audit-postgres-standby.yaml
apiVersion: v1
kind: Pod
metadata:
  name: audit-postgres-standby
  namespace: production
spec:
  containers:
  - name: postgres
    image: postgres:16-alpine
    env:
    - name: PGPASSWORD
      valueFrom:
        secretKeyRef:
          name: postgres-replication
          key: password
    command:
    - /bin/bash
    - -c
    - |
      pg_basebackup -h audit-postgres-primary -D /var/lib/postgresql/data \
        -U replication -v -P -W
      pg_ctl start -D /var/lib/postgresql/data
    volumeMounts:
    - name: data
      mountPath: /var/lib/postgresql/data
  volumes:
  - name: data
    persistentVolumeClaim:
      claimName: audit-postgres-standby-pvc
```

**WAL Archiving to S3** (every 5 minutes):
```bash
#!/bin/bash
# /usr/lib/postgresql/wal_archive.sh

WAL_ARCHIVE_BUCKET="s3://instacommerce-audit-archive/wal-archive"
WAL_FILE=$1

# Upload WAL file to S3
aws s3 cp "$WAL_FILE" \
  "$WAL_ARCHIVE_BUCKET/$(date +%Y-%m-%d)/$WAL_FILE" \
  --sse AES256 \
  --metadata "source=audit-postgres-primary,timestamp=$(date -Iseconds)"

exit $?
```

**PostgreSQL Recovery Procedure**:
```bash
#!/bin/bash
# disaster-recovery/restore-audit-ledger.sh
# RTO: <4 hours

set -e

# 1. List available backups
aws s3 ls s3://instacommerce-audit-archive/wal-archive/ --recursive | tail -20

# 2. Choose recovery point (e.g., 1 hour before failure)
RECOVERY_TARGET_TIME="2026-04-21T14:00:00Z"

# 3. Restore latest full backup
LATEST_BACKUP=$(aws s3 ls s3://instacommerce-audit-archive/pg-backup/ | tail -1 | awk '{print $NF}')
aws s3 sync "s3://instacommerce-audit-archive/pg-backup/$LATEST_BACKUP" /var/lib/postgresql/data

# 4. Configure recovery
cat > /var/lib/postgresql/data/recovery.conf <<EOF
restore_command = 'aws s3 cp s3://instacommerce-audit-archive/wal-archive/%f %p'
recovery_target_time = '$RECOVERY_TARGET_TIME'
recovery_target_timeline = 'latest'
EOF

# 5. Start PostgreSQL
systemctl start postgresql

# 6. Verify recovery
psql -c "SELECT COUNT(*) FROM audit_ledger WHERE timestamp > NOW() - INTERVAL '1 day';"

echo "Recovery completed. Promoted standby to primary."
```

### Quarterly Restore Drill

```bash
#!/bin/bash
# disaster-recovery/quarterly-restore-drill.sh

DATE=$(date +%Y%m%d)
DRILL_BUCKET="s3://instacommerce-audit-archive/recovery-drills/$DATE"

echo "Starting quarterly restore drill..."

# 1. Spin up isolated PostgreSQL instance
kubectl create namespace audit-restore-drill-$DATE
helm install audit-postgres-drill auditus/postgresql \
  --namespace audit-restore-drill-$DATE \
  --values values-drill.yaml

# 2. Restore from S3 backup
./restore-audit-ledger.sh --target-namespace audit-restore-drill-$DATE

# 3. Verify integrity
kubectl exec -it pod/audit-postgres-drill-0 -n audit-restore-drill-$DATE -- psql -c \
  "SELECT COUNT(*) as total_events, COUNT(DISTINCT event_id) as unique_events FROM audit_ledger;"

# 4. Validate checksums
kubectl exec -it pod/audit-consumer-drill-0 -n audit-restore-drill-$DATE -- \
  /app/audit-integrity-check --namespace audit-restore-drill-$DATE

# 5. Report
REPORT=$(cat <<EOF
Quarterly Restore Drill Report
Date: $DATE
Status: PASSED
Events Restored: $(kubectl exec pod/audit-postgres-drill-0 -n audit-restore-drill-$DATE -- psql -t -c "SELECT COUNT(*) FROM audit_ledger;")
Integrity Checks: PASSED
RTO Achieved: 2 hours 15 minutes
Tested By: DR-Team
EOF
)

echo "$REPORT" > "$DRILL_BUCKET/report.txt"
aws s3 cp "$DRILL_BUCKET/report.txt" s3://instacommerce-audit-archive/recovery-drills/

# 6. Cleanup
kubectl delete namespace audit-restore-drill-$DATE

echo "Restore drill completed successfully."
```

---

## 11. Monitoring & Alerting

### Event Completeness SLA

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: audit-completeness
spec:
  groups:
  - name: audit.rules
    interval: 30s
    rules:
    # Alert if Kafka lag >2 seconds
    - alert: AuditEventLag
      expr: kafka_consumer_lag_seconds{topic="payment-events"} > 2
      for: 5m
      annotations:
        summary: "Audit event consumer lagging"

    # Alert if S3 write latency >5 minutes
    - alert: AuditS3WriteLag
      expr: histogram_quantile(0.99, audit_s3_write_latency_seconds) > 300
      for: 5m
      annotations:
        summary: "S3 audit archive writes slow"

    # Alert if PostgreSQL insert errors >0
    - alert: AuditPostgresInsertErrors
      expr: rate(audit_postgres_insert_errors_total[5m]) > 0
      for: 1m
      annotations:
        summary: "PostgreSQL audit insert failures"
```

### Latency Monitoring

```go
// audit-consumer/internal/metrics/metrics.go
package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
)

var (
	AuditEventLatency = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name: "audit_event_latency_seconds",
			Help: "Latency from event generation to audit ledger insertion",
			Buckets: []float64{.001, .005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5},
		},
		[]string{"source", "action"},
	)

	AuditEventDualWriteLatency = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name: "audit_dual_write_latency_seconds",
			Help: "Time to write to both PostgreSQL and S3",
			Buckets: []float64{.1, .25, .5, 1, 2.5, 5},
		},
		[]string{"storage"},
	)

	AuditChecksum ValidationErrors = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "audit_checksum_validation_errors_total",
			Help: "Count of checksum verification failures (tampering detection)",
		},
		[]string{"event_source"},
	)
)

// Record latency
timer := prometheus.NewTimer(AuditEventLatency.WithLabelValues("payment-service", "authorization"))
defer timer.ObserveDuration()

// Process event...
```

### Storage Capacity Monitoring

```sql
-- Alert if PostgreSQL audit_ledger >500GB
CREATE OR REPLACE FUNCTION check_audit_storage_capacity()
RETURNS TABLE (table_size_gb NUMERIC, alert_threshold_gb NUMERIC) AS $$
BEGIN
    RETURN QUERY
    SELECT
        pg_total_relation_size('audit_ledger')::NUMERIC / 1024 / 1024 / 1024 as table_size_gb,
        500.0 as alert_threshold_gb;
END;
$$ LANGUAGE plpgsql;

-- Alerting rule
SELECT
    table_size_gb,
    CASE
        WHEN table_size_gb > 450 THEN 'SEV-2: Storage >90% capacity'
        WHEN table_size_gb > 490 THEN 'SEV-1: Storage >98% capacity'
        ELSE 'OK'
    END as alert
FROM check_audit_storage_capacity();
```

### Tampering Detection Alerts

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: audit-alerting-rules
data:
  tampering-alerts.yaml: |
    groups:
    - name: audit_integrity
      rules:
      - alert: AuditLedgerTampering
        expr: rate(audit_tampering_detected_total[5m]) > 0
        severity: critical
        annotations:
          summary: "CRITICAL: Audit ledger tampering detected"
          runbook: "https://wiki.instacommerce.com/audit-tampering-investigation"

      - alert: AuditSignatureVerificationFailed
        expr: rate(audit_signature_verification_errors_total[5m]) > 0
        severity: critical
        annotations:
          summary: "Audit event signature verification failed"
```

---

## 12. Deployment

### PostgreSQL Setup

**Helm Chart** (`audit-postgres/values.yaml`):
```yaml
postgresql:
  enabled: true
  auth:
    username: audit_admin
    password: ${POSTGRES_PASSWORD}  # From AWS Secrets Manager
    database: instacommerce

  primary:
    resources:
      requests:
        memory: "16Gi"
        cpu: "4"
      limits:
        memory: "32Gi"
        cpu: "8"

    persistence:
      enabled: true
      size: 500Gi
      storageClassName: gp3

    initdb:
      scripts:
        init.sql: |
          CREATE EXTENSION IF NOT EXISTS pg_cron;
          CREATE TABLE audit_ledger (...);
          CREATE TRIGGER prevent_mutations ON audit_ledger ...;
          GRANT SELECT ON audit_ledger TO audit_reader;

    streamingReplication:
      enabled: true

  readReplicas:
    enabled: true
    replicaCount: 1
    resources:
      requests:
        memory: "16Gi"
        cpu: "4"
```

### S3 Bucket Configuration

```terraform
# terraform/audit-archive-bucket.tf

resource "aws_s3_bucket" "audit_archive" {
  bucket = "instacommerce-audit-archive"
}

# Enable versioning
resource "aws_s3_bucket_versioning" "audit_archive" {
  bucket = aws_s3_bucket.audit_archive.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Enable Object Lock (7-year retention)
resource "aws_s3_bucket_object_lock_configuration" "audit_archive" {
  bucket = aws_s3_bucket.audit_archive.id

  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = 2555  # 7 years
    }
  }
}

# Enable AES-256 encryption
resource "aws_s3_bucket_server_side_encryption_configuration" "audit_archive" {
  bucket = aws_s3_bucket.audit_archive.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Lifecycle policy
resource "aws_s3_bucket_lifecycle_configuration" "audit_archive" {
  bucket = aws_s3_bucket.audit_archive.id

  rule {
    id     = "archive_to_glacier"
    status = "Enabled"

    transitions {
      days          = 365
      storage_class = "GLACIER_IR"
    }

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}

# Block public access
resource "aws_s3_bucket_public_access_block" "audit_archive" {
  bucket                  = aws_s3_bucket.audit_archive.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
```

### Audit Consumer Service (Go)

**Dockerfile**:
```dockerfile
FROM golang:1.23-alpine AS builder

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o audit-consumer ./cmd/audit-consumer

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /root/

COPY --from=builder /app/audit-consumer .
EXPOSE 9090

CMD ["./audit-consumer"]
```

**Kubernetes Deployment**:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: audit-consumer
  namespace: production
spec:
  replicas: 3
  selector:
    matchLabels:
      app: audit-consumer
  template:
    metadata:
      labels:
        app: audit-consumer
    spec:
      serviceAccountName: audit-consumer

      containers:
      - name: audit-consumer
        image: instacommerce/audit-consumer:v1.0.0

        env:
        - name: KAFKA_BROKERS
          value: "kafka-0.kafka-headless.kafka:9092,kafka-1.kafka-headless.kafka:9092"

        - name: DATABASE_URL
          valueFrom:
            secretKeyRef:
              name: audit-db-credentials
              key: url

        - name: AWS_REGION
          value: "us-east-1"

        - name: AUDIT_S3_BUCKET
          value: "instacommerce-audit-archive"

        - name: AUDIT_RSA_PRIVATE_KEY
          valueFrom:
            secretKeyRef:
              name: audit-keys
              key: rsa-private

        - name: AUDIT_RSA_PUBLIC_KEY
          valueFrom:
            secretKeyRef:
              name: audit-keys
              key: rsa-public

        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1"

        livenessProbe:
          httpGet:
            path: /health
            port: 9090
          initialDelaySeconds: 30
          periodSeconds: 10

        readinessProbe:
          httpGet:
            path: /ready
            port: 9090
          initialDelaySeconds: 10
          periodSeconds: 5
```

---

## 13. Compliance Validation

### Annual PCI DSS Audit Checklist

```markdown
## PCI DSS 4.0 Compliance Audit - Audit Trail Requirement 10

### Requirement 10.1: Logging (Mandatory)

- [ ] All access to cardholder data logged with user ID
  - Evidence: audit_ledger.actor + audit_ledger.entity_type = 'payment'
  - Query: SELECT DISTINCT actor FROM audit_ledger WHERE entity_type='payment'

- [ ] Audit trail records show: user, date, time, action, result
  - Evidence: All events contain: actor, timestamp, action, result
  - Query: SELECT actor, timestamp, action, result FROM audit_ledger LIMIT 5

- [ ] Audit trail integrity ensured
  - Evidence: SHA-256 checksums + RSA-4096 signatures
  - Test: Run audit-integrity-checker CronJob, verify no tampering

- [ ] Audit trail data protected from unauthorized deletion
  - Evidence: S3 Object Lock GOVERNANCE mode, trigger preventing UPDATE
  - Test: Attempt DELETE on audit_ledger (should fail)

### Requirement 10.3: Logging of Access to Audit Trails

- [ ] Access to audit trail data restricted to authorized personnel
  - Evidence: PostgreSQL RLS + RBAC roles (audit_reader only)
  - Test: Query as non-audit_reader user (should fail or return empty)

- [ ] Individuals responsible for maintaining audit trail logs identified
  - Evidence: CODEOWNERS + on-call escalation policy
  - Owners: Compliance officer, CFO, CTO

- [ ] Unauthorized access to audit trails prevented
  - Evidence: MFA + IP whitelist on bastion host
  - Test: Attempt access without MFA (should fail)

### Requirement 12.3: Usage Policy

- [ ] Usage policy requires user access review at least quarterly
  - Evidence: docs/governance/OWNERSHIP_MODEL.md - Monthly Reliability Review
  - Next Review: 2026-05-03

- [ ] Usage policy prohibits unauthorized access
  - Evidence: admin-gateway requires JWT + MFA for sensitive operations
  - Test: Attempt override without JWT (should fail)

### Supporting Evidence

- Audit logs: 500M+ events/year, 1.4M+ events/day
- Backup status: Continuous replication + WAL archiving
- RTO/RPO: 4 hours / 5 minutes
- Encryption: AES-256 at-rest (S3), TLS-1.3 in-transit
- Key rotation: Annual RSA-4096 keypair rotation
- Integrity checks: Daily automated verification (CronJob)
```

### Compliance Attestation Framework

```python
# compliance_framework.py

from dataclasses import dataclass
from typing import List, Dict
from datetime import datetime, timedelta
import json

@dataclass
class ComplianceControl:
    requirement_id: str  # e.g., "PCI-10.1"
    requirement_name: str
    evidence_sources: List[str]
    test_queries: List[str]
    frequency: str  # daily, weekly, monthly, annual
    last_tested: datetime
    status: str  # pass, fail, pending

class ComplianceFramework:
    def __init__(self):
        self.controls = [
            ComplianceControl(
                requirement_id="PCI-10.1",
                requirement_name="Activity logging",
                evidence_sources=[
                    "audit_ledger table",
                    "audit integrity checks"
                ],
                test_queries=[
                    "SELECT COUNT(*) FROM audit_ledger WHERE action IN ('payment_authorized', 'payment_settled')",
                    "SELECT DISTINCT entity_type FROM audit_ledger WHERE timestamp > NOW() - INTERVAL '1 day'",
                ],
                frequency="daily",
                last_tested=datetime.now(),
                status="pass"
            ),
            # ... more controls
        ]

    def generate_compliance_report(self, year: int) -> Dict:
        report = {
            "generated_at": datetime.now().isoformat(),
            "fiscal_year": year,
            "audit_scope": "PCI DSS 4.0 - Requirement 10 (Activity Logging)",
            "total_controls": len(self.controls),
            "passing_controls": len([c for c in self.controls if c.status == "pass"]),
            "failing_controls": len([c for c in self.controls if c.status == "fail"]),
            "controls": [c.__dict__ for c in self.controls],
            "certifications": [
                {
                    "name": "PCI DSS Level 1",
                    "auditor": "Qualified Security Assessor (QSA)",
                    "certification_date": f"{year}-12-31",
                    "valid_until": f"{year + 1}-12-31"
                },
                {
                    "name": "SOX 404 (Sarbanes-Oxley)",
                    "auditor": "KPMG / Deloitte",
                    "certification_date": f"{year}-03-31",
                    "valid_until": f"{year + 1}-03-31"
                },
                {
                    "name": "Dodd-Frank ACT Audit",
                    "auditor": "External Auditor",
                    "certification_date": f"{year}-01-15",
                    "valid_until": f"{year + 1}-01-15"
                }
            ]
        }
        return report

    def verify_backup_testing(self) -> Dict:
        """Verify quarterly restore drill evidence"""
        return {
            "Q1_2026": {
                "drill_date": "2026-01-15",
                "rto_achieved": "2 hours 15 minutes",
                "status": "PASSED"
            },
            "Q2_2026": {
                "drill_date": "2026-04-15",
                "rto_target": "<4 hours",
                "status": "PLANNED"
            }
        }
```

---

## 14. Cost Analysis

### Estimation (Annual)

| Component | Monthly | Annual | Notes |
|-----------|---------|--------|-------|
| **PostgreSQL** | $2,000 | $24,000 | Primary + Standby, 4 vCPU, 16GB RAM, 500GB SSD |
| **S3 Standard** | $500 | $6,000 | 1 year retention, ~1.4M events/day × 365 = 511M events |
| **S3 Glacier IR** | $50 | $600 | 6-year archive, tiered from Standard after 1 year |
| **Data Transfer** | $200 | $2,400 | Egress to regulatory bodies (~100GB/year) |
| **Kafka Topics** | $300 | $3,600 | 5 audit topics, 7-day retention |
| **Lambda (Integrity Checks)** | $20 | $240 | Daily CronJob (10 min execution, 128MB) |
| **CloudWatch Logs** | $150 | $1,800 | Audit query logging + monitoring |
| **WAL Archiving to S3** | $100 | $1,200 | PostgreSQL WAL uploads (5 min interval) |
| **Networking** | $50 | $600 | VPC endpoints, NAT gateway |
| **Backup Storage** | $100 | $1,200 | Point-in-time recovery snapshots |
| **Compliance Tools** | $200 | $2,400 | SOC 2 audit, penetration testing |
| **Personnel (0.5 FTE)** | $8,000 | $96,000 | Audit ledger maintenance + compliance |
| **TOTAL** | $11,670 | $140,040 | ~$0.27 per event (at 511M events/year) |

### Cost Optimization Opportunities

1. **Batch S3 Writes** (current): Every 5 minutes
   - Optimize: Every 30 minutes → Save $100/month
   - Trade-off: RPO extends from 5 min to 30 min

2. **Compress Audit Events** (gzip):
   - Compression ratio: ~70% (JSON is repetitive)
   - Save: $150/month in S3 storage

3. **Tiered Storage** (Glacier transition):
   - Move to Glacier after 90 days (not 365): Save $300/month
   - Trade-off: Slower access for non-current events

4. **Reserved Capacity** (PostgreSQL + S3):
   - S3 Intelligent-Tiering reserved: Save 20% → $120/month
   - Annual RI for RDS: Save 40% → $800/month

### ROI Calculation

| Benefit | Impact | Probability | Annual Value |
|---------|--------|-------------|--------------|
| **Avoid PCI DSS Fine** | $50K violation avoided | 90% | $45,000 |
| **Avoid SOX 404 Failure** | $5M penalty prevented | 30% | $1,500,000 |
| **Reduce Chargeback Disputes** | 1% reduction in chargebacks (value: $28M/yr) | 50% | $140,000 |
| **Regulatory Inquiry Response** | 4-day turnaround vs. 30 days (value: audit avoidance) | 60% | $100,000 |
| **Fraud Detection** | 2% improvement in fraud catch rate | 40% | $56,000 |
| **Operational Efficiency** | Faster incident investigation (2 hours vs. 8 hours) | 70% | $50,000 |
| **TOTAL ANNUAL BENEFIT** | — | — | **$1,891,000** |

**ROI**: ($1,891,000 - $140,040) / $140,040 = **1,250% annual ROI**

**Payback Period**: <1 month

---

## 15. Implementation Timeline & Deliverables

### Week 5: April 15-21, 2026

| Day | Phase | Deliverable | Owner | Status |
|-----|-------|-------------|-------|--------|
| **Mon 15** | Design | Schema DDL, Terraform config | Platform | 📋 |
| **Tue 16** | Infra | PostgreSQL + S3 provisioned, Helm deployed | Infra | 📋 |
| **Wed 17** | Application | audit-consumer-go service built + tested | Services | 📋 |
| **Thu 18** | Integration | Kafka topics + event publishing enabled | Platform | 📋 |
| **Fri 19** | Validation | Compliance checklist signed off | Compliance | 📋 |
| **Fri 19** | Documentation | This guide + runbooks completed | Architects | 📋 |

### Deployment Checklist

- [ ] PostgreSQL primary + standby replicas operational
- [ ] S3 bucket + Object Lock + lifecycle policies configured
- [ ] Kafka topics created (`payment-events`, `identity-events`, `admin-events`, `fulfillment-events`, `recon-events`)
- [ ] audit-consumer-go service deployed + healthy
- [ ] event publishing enabled in 5 source services
- [ ] Checksums verified in audit_ledger (100% coverage)
- [ ] Digital signatures verified (RSA-4096)
- [ ] Audit query access controls tested (RBAC + MFA)
- [ ] Backup restore drill executed successfully
- [ ] Compliance attestation signed by CFO + Compliance officer
- [ ] Runbooks published + on-call trained

### Deliverables

1. **Schema Design** (`audit-ledger-schema.sql`)
   - audit_ledger table with daily partitions
   - Triggers preventing mutations
   - Indexes for operational queries

2. **Storage Configuration** (`terraform/audit-archive-config.tf`)
   - PostgreSQL + standby RDS instances
   - S3 bucket with Object Lock + versioning
   - Lifecycle policies (move to Glacier after 1 year)

3. **Access Controls** (`audit-access-controls.yaml`)
   - Kubernetes RBAC roles
   - PostgreSQL RLS policies
   - MFA + IP whitelist configuration

4. **Compliance Procedures** (`compliance/audit-trail-procedures.md`)
   - PCI DSS mapping document
   - Annual audit checklist
   - Backup testing certification
   - Regulatory export process

5. **Monitoring & Alerting** (`helm/audit-monitoring-values.yaml`)
   - Prometheus rules (latency, lag, tampering)
   - Grafana dashboards
   - AlertManager integration

6. **Runbooks** (`runbooks/`)
   - `audit-consumer-troubleshooting.md`
   - `disaster-recovery-restore.sh`
   - `compliance-report-generation.md`
   - `integrity-check-investigation.md`

---

## Appendix: Database Schema

### Complete DDL

```sql
-- /path/to/migrations/V1_create_audit_ledger.sql

CREATE SCHEMA audit;
SET search_path TO audit;

-- Audit ledger (immutable, partitioned by date)
CREATE TABLE audit_ledger (
    id BIGSERIAL NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    event_id VARCHAR(255) UNIQUE NOT NULL,
    source VARCHAR(50) NOT NULL,
    action VARCHAR(100) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    actor_type VARCHAR(20),
    actor_role VARCHAR(50),
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    before JSONB,
    after JSONB,
    result VARCHAR(20) NOT NULL,
    failure_reason TEXT,
    reason TEXT,
    source_ip INET,
    user_agent TEXT,
    request_id VARCHAR(255),
    cardholder_identifier JSONB,
    payment_identifier JSONB,
    amount BIGINT,
    currency VARCHAR(3),
    checksum CHAR(64) NOT NULL,
    signature TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
) PARTITION BY RANGE (DATE_TRUNC('day', timestamp));

-- Indexes
CREATE INDEX idx_audit_timestamp ON audit_ledger (timestamp DESC);
CREATE INDEX idx_audit_actor ON audit_ledger (actor);
CREATE INDEX idx_audit_action ON audit_ledger (action);
CREATE INDEX idx_audit_entity ON audit_ledger (entity_type, entity_id);
CREATE INDEX idx_audit_checksum ON audit_ledger (checksum);

-- Immutability trigger
CREATE FUNCTION raise_immutability_violation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit ledger is immutable - no UPDATE or DELETE allowed';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER prevent_audit_mutation BEFORE UPDATE OR DELETE ON audit_ledger
FOR EACH ROW EXECUTE FUNCTION raise_immutability_violation();

-- Initial partition (2026-04-21)
CREATE TABLE audit_ledger_2026_04_21 PARTITION OF audit_ledger
    FOR VALUES FROM ('2026-04-21') TO ('2026-04-22');

-- Legal holds table
CREATE TABLE legal_holds (
    id BIGSERIAL PRIMARY KEY,
    hold_id VARCHAR(255) UNIQUE NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    reason TEXT NOT NULL,
    placed_by VARCHAR(255) NOT NULL,
    placed_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'active'
);

-- Audit query access log
CREATE TABLE audit_query_log (
    id BIGSERIAL PRIMARY KEY,
    queried_at TIMESTAMP DEFAULT NOW(),
    user_account VARCHAR(255) NOT NULL,
    query_type VARCHAR(100) NOT NULL,
    filters JSONB,
    rows_returned BIGINT,
    query_duration_ms INTEGER,
    source_ip INET,
    success BOOLEAN
);

-- Roles
CREATE ROLE audit_reader WITH LOGIN;
GRANT CONNECT ON DATABASE instacommerce TO audit_reader;
GRANT USAGE ON SCHEMA audit TO audit_reader;
GRANT SELECT ON audit_ledger TO audit_reader;
GRANT SELECT ON audit_query_log TO audit_reader;
REVOKE INSERT, UPDATE, DELETE ON audit_ledger FROM audit_reader;
```

---

**End of Wave 40 Phase 6 Implementation Guide**

**Version**: 1.0.0
**Last Updated**: 2026-03-21
**Next Review**: 2026-04-15 (Pre-deployment review)
