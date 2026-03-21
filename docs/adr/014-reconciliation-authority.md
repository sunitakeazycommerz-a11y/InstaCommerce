# ADR-014: Reconciliation Authority Model

## Status
Decided

## Date
2026-03-21

## Context

**Problem**: Reconciliation was file-based (local disk), creating several issues:
1. **No audit trail**: Reconciliation runs not queryable; hard to debug issues
2. **Pod crashes lose state**: If reconciliation pod crashes mid-run, state is lost (no resume capability)
3. **No mismatch tracking**: Discrepancies noted in logs but not stored for auditing
4. **Manual fix workflow**: Fixes applied externally (e.g., adjusting payment records) with no formal registry
5. **Compliance risk**: Regulators require immutable reconciliation ledger for financial audits
6. **Reliability**: Single point of failure (one pod running reconciliation; no failover)

**Current architecture (Wave 35)**:
- Reconciliation engine runs daily as Kubernetes CronJob
- Generates daily ledger file (JSON) on local disk
- Stores mismatches in memory or temporary CSV files
- Manual fixes via SQL patches or payment-service API calls (logged in tickets, not DB)

**Requirements**:
- PCI DSS audit trail: All reconciliation runs queryable by date + operator + status
- Financial SLO: Reconciliation completes within 4 hours of daily trigger (2 AM UTC)
- Compliance: Immutable mismatch registry (can't modify historical mismatches)
- Operational: Resume capability if pod crashes mid-reconciliation

## Decision

Implement PostgreSQL-backed reconciliation ledger as source-of-truth (instead of file-based):

### Architecture

**1. Reconciliation Schema (PostgreSQL)**
```sql
-- Core reconciliation run tracking
CREATE TABLE reconciliation_runs (
  id UUID PRIMARY KEY,
  run_date DATE NOT NULL UNIQUE,
  started_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP,
  status ENUM ('IN_PROGRESS', 'COMPLETED', 'FAILED', 'PAUSED') NOT NULL,
  operator_id VARCHAR(255),  -- Who triggered the run (identity-service token)
  expected_count INT,        -- Expected # of transactions
  processed_count INT,       -- Actual # processed
  mismatch_count INT,        -- Mismatches found
  notes TEXT
);

-- Immutable mismatch registry
CREATE TABLE reconciliation_mismatches (
  id UUID PRIMARY KEY,
  reconciliation_run_id UUID NOT NULL REFERENCES reconciliation_runs(id),
  transaction_id VARCHAR(255) NOT NULL,
  payment_ledger_amount DECIMAL(12,2),
  bank_statement_amount DECIMAL(12,2),
  difference DECIMAL(12,2),
  mismatch_type ENUM ('AMOUNT_MISMATCH', 'MISSING_IN_LEDGER', 'MISSING_IN_BANK', 'DUPLICATE') NOT NULL,
  detected_at TIMESTAMP NOT NULL,
  UNIQUE(reconciliation_run_id, transaction_id)
);

-- Operator-approved fixes with full audit trail
CREATE TABLE reconciliation_fixes (
  id UUID PRIMARY KEY,
  mismatch_id UUID NOT NULL REFERENCES reconciliation_mismatches(id),
  fix_type ENUM ('LEDGER_ADJUSTMENT', 'BANK_ADJUSTMENT', 'MANUAL_REVIEW', 'REVERSED') NOT NULL,
  fixed_amount DECIMAL(12,2),
  operator_id VARCHAR(255) NOT NULL,
  approved_at TIMESTAMP NOT NULL,
  rationale TEXT NOT NULL,
  status ENUM ('APPROVED', 'APPLIED', 'REVERTED') NOT NULL DEFAULT 'APPROVED',
  applied_at TIMESTAMP
);
```

**2. Event Emission (Debezium CDC)**
- Debezium captures changes to `reconciliation_*` tables
- Publishes to Kafka topic: `reconciliation.cdc` (for downstream auditing)
- Reconciliation engine also publishes events to `reconciliation.events`:
  - `reconciliation.run.started`
  - `reconciliation.run.completed`
  - `reconciliation.mismatch.detected`
  - `reconciliation.fix.applied`

**3. Reconciliation Process**
```
1. Daily scheduler (2 AM UTC) triggers reconciliation CronJob
2. Pod acquires distributed lock (ShedLock on PostgreSQL)
3. Fetches payment ledger from CDC consumer (recent transactions)
4. Fetches bank statement dump from external system
5. Compares transaction-by-transaction:
   - Inserts matches into reconciliation_runs.processed_count
   - Inserts mismatches into reconciliation_mismatches (immutable)
6. On completion: updates reconciliation_runs.status = 'COMPLETED'
7. Emits reconciliation.events for downstream consumers (audit-trail-service, etc.)
8. Releases distributed lock

On failure:
- Sets status = 'FAILED' + error message
- Manual intervention: operator reviews logs, runs "resume" command
- Resume command picks up from last checkpoint (in reconciliation_runs.processed_count)
```

**4. Fix Workflow**
```
1. Operator reviews mismatches via admin-dashboard (queries reconciliation_mismatches)
2. Submits fix: { mismatch_id, fix_type, fixed_amount, rationale }
3. Second operator approves (separation of duties)
4. Once approved: reconciliation-engine applies fix
   - Updates payment-service ledger (if LEDGER_ADJUSTMENT)
   - Creates credit/debit transaction
   - Emits reconciliation.fix.applied event
   - Updates reconciliation_fixes.status = 'APPLIED'
5. Full audit trail preserved: who fixed it, when, why
```

## Consequences

### Positive
- ✅ **Full audit trail**: All runs queryable by date, status, operator; complies with PCI DSS
- ✅ **Immutable mismatch registry**: Can't modify historical mismatches (compliance requirement)
- ✅ **Resilience**: Pod crash doesn't lose state; can resume from checkpoint
- ✅ **Scalability**: Multiple reconciliation pods supported (distributed lock ensures single active run)
- ✅ **Compliance**: Operator approval workflow for fixes (SoD = separation of duties)
- ✅ **Observability**: Metrics & events enable monitoring, alerting, post-mortems
- ✅ **No manual SQL patches**: All changes go through formal reconciliation_fixes table (auditable)

### Negative
- ⚠️ **PostgreSQL dependency**: Adds operational complexity (mitigated by existing usage; already mission-critical)
- ⚠️ **Debezium dependency**: Requires CDC sink (mitigated by Wave 31 experience; already operational)
- ⚠️ **Increased storage**: Reconciliation tables grow over time (mitigated by archiving strategy: see implementation)
- ⚠️ **Operational overhead**: Requires manual fix approval process (intentional for compliance)

## Compliance

| Requirement | Met | Evidence |
|-------------|-----|----------|
| **PCI DSS 10.1** (audit trail) | ✅ | reconciliation_runs + reconciliation_fixes tables (immutable) |
| **PCI DSS 10.2** (operator accountability) | ✅ | operator_id tracked in both tables |
| **Financial audit** (replayability) | ✅ | Can re-run any reconciliation by date (CDC ledger available) |
| **SOX compliance** (SoD) | ✅ | Fix approval workflow (2 operators required) |
| **Regulatory reporting** (SLO <4h daily) | ✅ | ShedLock ensures single run; metrics track completion time |

## SLO

- **Reconciliation completion**: <4 hours (99.5% uptime SLO on reconciliation-engine)
- **Fix application**: <1 hour (operator approval time)
- **Auditability**: 100% (all operations logged in DB)
- **Data loss**: 0% (PostgreSQL durability; WAL archiving)

## Monitoring

Metrics to emit:
```
reconciliation_run_duration_seconds (histogram)
reconciliation_mismatches_count (gauge, per run_date)
reconciliation_fixes_applied_total (counter)
reconciliation_run_failures_total (counter)
reconciliation_operator_approval_lag_seconds (histogram)
```

Alerts:
- `reconciliation_run_failure`: If reconciliation status = 'FAILED'
- `reconciliation_mismatches_high`: If mismatches > 10% of transactions
- `reconciliation_fix_pending`: If approval pending >30 minutes

## Deployment

### Flyway Migrations
- `V001__create_reconciliation_schema.sql`: Create tables above
- `V002__add_reconciliation_indexes.sql`: Performance tuning (reconciliation_run_id, run_date, status)

### Kubernetes ConfigMap
- `RECONCILIATION_SCHEDULE`: "0 2 * * *" (2 AM UTC daily)
- `RECONCILIATION_SLA_MINUTES`: 240 (4-hour SLA)

### Helm Values
- `reconciliation.postgres.host`, `.user`, `.password`, `.database`
- `reconciliation.debezium.enabled`: true
- `reconciliation.kafka.topic`: "reconciliation.cdc"

## Alternatives Considered

**Option A**: Keep file-based + S3 backup
- ❌ No audit trail; hard to query mismatches
- ❌ Data loss risk (S3 async, pod crash during upload)
- ❌ Doesn't scale to multiple pods

**Option B**: Use payment-service's ledger directly
- ❌ Violates SoC (separation of concerns); payment-service owns payments, not reconciliation
- ❌ Couples reconciliation to payment-service schema changes
- ❌ No mismatch tracking (payment-service assumes ledger is correct)

**Option C**: Event-sourcing all transactions (like Wallet-Loyalty)
- ❌ Too complex for Wave 36 scope; requires redesign of CDC consumer, payment-service
- ❌ Doesn't solve immediate compliance requirement
- ⏳ Defer to Wave 39 if needed

**Selected**: Option D (this ADR) balances operational simplicity, compliance, and auditability.

## Migration Path

1. **Wave 36**: Deploy PostgreSQL schema + reconciliation-engine changes
2. **Staging**: Run 7 days of reconciliation in staging; verify schema + queries work
3. **Prod rollout** (Week 1):
   - Deploy reconciliation-engine with new DB schema
   - File-based reconciliation runs continue in parallel (read-only, for safety)
   - Operators manually verify DB matches file outputs
4. **Prod rollout** (Week 2):
   - Cut over: disable file-based, enable DB-only reconciliation
   - Archive old files to S3 (retention: 7 years per PCI DSS)

## References

- Wave 31: Outbox relay + Debezium CDC (related: reliable event publishing pattern)
- ADR-004: Event envelope (related: event schema)
- Wave 36 Track B: CDC Wiring (related: payment-ledger consumer)
- Helm: `deploy/helm/reconciliation-engine/`
- Docs: `docs/services/reconciliation-engine/` (runbooks, API docs)
