# Reconciliation Engine - Sequence Diagram

```mermaid
sequenceDiagram
    participant Scheduler as Scheduler<br/>(2 AM)
    participant ReconciliationEngine as Reconciliation Engine
    participant Lock as PostgreSQL Lock<br/>(ShedLock)
    participant LedgerDB as Payment Ledger<br/>(CDC sourced)
    participant BankAPI as Bank API<br/>(Settlement)
    participant DB as Reconciliation DB
    participant Kafka as Kafka

    Scheduler->>ReconciliationEngine: Trigger reconciliation
    ReconciliationEngine->>Lock: SELECT * FROM shedlock<br/>FOR UPDATE NOWAIT
    Lock-->>ReconciliationEngine: Acquired
    ReconciliationEngine->>LedgerDB: SELECT SUM(amount)<br/>WHERE date = yesterday
    LedgerDB-->>ReconciliationEngine: ledger_total = $1M
    ReconciliationEngine->>BankAPI: GET /settlement?date=yesterday
    BankAPI-->>ReconciliationEngine: bank_total = $999,999.50
    ReconciliationEngine->>ReconciliationEngine: diff = $0.50
    ReconciliationEngine->>DB: INSERT reconciliation_runs
    ReconciliationEngine->>DB: INSERT reconciliation_mismatches
    DB-->>ReconciliationEngine: OK
    ReconciliationEngine->>DB: INSERT reconciliation_fixes<br/>(auto-fix: $0.50 adjustment)
    ReconciliationEngine->>Kafka: Publish ReconciliationCompleted
    Kafka-->>ReconciliationEngine: ACK
    ReconciliationEngine->>Lock: Unlock

    Note over Scheduler,Lock: Total duration: ~2 minutes
```

## Detailed Sequence Execution

### Phase 1: Trigger & Lock Acquisition (0-1 second)

**Step 1: Scheduler Triggers**
- Time: 02:00:00 UTC
- Trigger: Kubernetes CronJob fires
- Message: "Start daily reconciliation for 2026-03-20"

**Step 2: Reconciliation Engine Receives**
- Captures trigger event
- Logs: "Reconciliation job started"
- Prepares parameters (date = yesterday)

**Step 3: Lock Query**
```sql
SELECT * FROM shedlock
WHERE name = 'reconciliation_run_2026-03-20'
FOR UPDATE NOWAIT;
```

**Step 4: Lock Acquired**
- Lock table entry returned
- Locked_by = 'reconciliation-engine-pod-1'
- Lock duration = 4 hours
- Proceed to data fetching

**Duration**: 0-1 second (fast local operation)

### Phase 2: Ledger Data Fetching (1-3 seconds)

**Step 5: Fetch Ledger Sum**

**Query**:
```sql
SELECT
    SUM(amount_cents) as ledger_total,
    COUNT(*) as transaction_count,
    MIN(created_at) as first_transaction,
    MAX(created_at) as last_transaction
FROM payment_ledger
WHERE DATE(created_at) = '2026-03-20'
AND status IN ('COMPLETED', 'SETTLED')
AND merchant_id = 1;
```

**Data Source**: PostgreSQL (CDC-captured payment events)
**Index Used**: (date, status, merchant_id)
**Expected Result**:
```
ledger_total: 100,000,000 cents ($1,000,000.00)
transaction_count: 5,432 transactions
first_transaction: 2026-03-20 00:01:23
last_transaction: 2026-03-20 23:59:45
```

**Latency**: 100-200ms (database round-trip)

**Step 6: Database Returns Result**
- Sends aggregated ledger total
- Confirms transaction count
- Returns timestamp range

**Duration**: 1-3 seconds (with network RTT)

### Phase 3: Bank Statement Fetching (3-7 seconds)

**Step 7: Bank API Call**

**Request**:
```http
GET /settlement/statement?date=2026-03-20 HTTP/1.1
Host: api.bank.com
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
Accept: application/json
```

**Timeout**: 10 seconds (fail-fast)
**Retry**: 3 attempts with exponential backoff
- Attempt 1: Immediate
- Attempt 2: Wait 1 second
- Attempt 3: Wait 2 seconds

**Expected Response**:
```json
{
    "settlement_date": "2026-03-20",
    "total_deposited_cents": 99999950,
    "net_settled_cents": 99999950,
    "transaction_count": 5,432,
    "fees_cents": 0,
    "status": "SETTLED"
}
```

**Step 8: Bank API Returns**
- Status: 200 OK
- Bank total: $999,999.50
- Transaction count matches (5,432)

**Latency**: 3-7 seconds (external API call + network)

### Phase 4: Reconciliation Calculation (7-10 seconds)

**Step 9: Calculate Difference**

**Algorithm**:
```
ledger_total_cents  = 100,000,000 cents
bank_total_cents    =  99,999,950 cents
difference_cents    =         50 cents

reconciled          = (difference_cents == 0) ? TRUE : FALSE
```

**Result**:
```
difference = 50 cents ($0.50)
reconciled = FALSE
needs_action = TRUE
```

**Category Decision**:
- Is difference < 100 cents? YES
- Category = AUTO_FIXABLE
- Reason = "Potential fee rounding error"

**Latency**: 10-50ms (in-process calculation)

### Phase 5: Database Persistence (10-20 seconds)

**Step 10: INSERT Reconciliation Run**
```sql
BEGIN TRANSACTION;
INSERT INTO reconciliation_runs (
    id, reconciliation_date, ledger_total, bank_total,
    difference, status, created_at
) VALUES (
    'run_20260320',
    '2026-03-20',
    100000000,
    99999950,
    50,
    'IN_PROGRESS',
    NOW()
);
```

**Result**: Run record created with status = 'IN_PROGRESS'

**Step 11: INSERT Mismatch Record**
```sql
INSERT INTO reconciliation_mismatches (
    id, run_id, amount_diff, category, reason, created_at
) VALUES (
    'mism_001',
    'run_20260320',
    50,
    'AUTO_FIXABLE',
    'Small rounding discrepancy',
    NOW()
);
```

**Result**: Mismatch record created (pending fix)

**Step 12: Database Acknowledges**
- Both INSERTs succeed
- Returns 2 rows affected
- Transaction committed

**Latency**: 10-20 seconds (including transaction overhead)

### Phase 6: Auto-Fix Application (20-25 seconds)

**Step 13: INSERT Fix Record**

**Auto-Fix Decision**:
```
amount_diff = 50 cents
threshold = 100 cents
auto_fixable = (amount_diff < threshold) ? TRUE : FALSE

fix_type = "AUTO_FIX"
status = "APPLIED"
approver = "system"
```

**Fix Record**:
```sql
INSERT INTO reconciliation_fixes (
    id, mismatch_id, fix_type, status,
    approver, approved_at, created_at
) VALUES (
    'fix_001',
    'mism_001',
    'AUTO_FIX',
    'APPLIED',
    'system',
    NOW(),
    NOW()
);

-- Also adjust ledger total (audit trail):
INSERT INTO reconciliation_adjustments (
    id, run_id, adjustment_amount, reason
) VALUES (
    'adj_001',
    'run_20260320',
    50,
    'Fee rounding adjustment'
);
```

**Result**: Fix applied and recorded (immutable audit trail)

**Latency**: 5-10 seconds (database writes)

### Phase 7: Event Publishing (25-30 seconds)

**Step 14: Publish Reconciliation Event**

**Event Type**: ReconciliationCompleted

**Event Payload**:
```json
{
    "event_id": "evt_complete_20260320",
    "event_type": "ReconciliationCompleted",
    "run_id": "run_20260320",
    "reconciliation_date": "2026-03-20",
    "ledger_total_cents": 100000000,
    "bank_total_cents": 99999950,
    "difference_cents": 50,
    "status": "COMPLETED_WITH_AUTO_FIX",
    "total_mismatches": 1,
    "auto_fixed": 1,
    "manual_review_pending": 0,
    "duration_ms": 25000,
    "timestamp": "2026-03-21T02:00:25Z",
    "published_by": "reconciliation-engine"
}
```

**Topic**: reconciliation.events
**Partition**: 0 (single partition for ordering)
**Replication Factor**: 3 (high durability)

**Kafka ACK**: Waits for acks="all" (leader + 1 replica confirmed)

**Step 15: Kafka Acknowledges**
- Message received by broker 1 (leader)
- Replicated to broker 2, broker 3
- ACK sent when min in-sync replicas confirmed (default: 1)

**Latency**: 5-10 seconds (Kafka round-trip)

### Phase 8: Cleanup (30+ seconds)

**Step 16: Release Lock**
```sql
UPDATE shedlock
SET lock_at = NULL,
    locked_at = NULL,
    locked_by = NULL
WHERE name = 'reconciliation_run_2026-03-20';
```

**Result**: Lock released
**Next Run**: Can now proceed at 2 AM UTC tomorrow

**Step 17: Emit Metrics**
- `reconciliation_duration_ms`: 25000
- `reconciliation_mismatches_total`: 1
- `reconciliation_auto_fixed_total`: 1
- `reconciliation_status`: "success"

**Step 18: Log Completion**
- "Reconciliation completed in 25 seconds"
- "1 mismatch detected, 1 auto-fixed"
- "No manual review needed"

## End-to-End Timeline

| Phase | Step | Duration | Total Time |
|-------|------|----------|-----------|
| Trigger & Lock | 1-4 | 1s | 1s |
| Ledger Fetch | 5-6 | 2s | 3s |
| Bank API | 7-8 | 4s | 7s |
| Calculation | 9 | 0.05s | 7s |
| DB Persist | 10-12 | 10s | 17s |
| Auto-Fix | 13 | 5s | 22s |
| Publish Event | 14-15 | 5s | 27s |
| Cleanup | 16-18 | 1s | 28s |
| **Total** | | | **28 seconds** |

**Actual P99**: 60 seconds (with retries/backoff)
**SLA Target**: 4 hours (well met)

## Failure Recovery Sequences

### Scenario 1: Bank API Timeout
```
BankAPI call → Timeout (10s)
  ↓ Retry 1: Wait 1s → Timeout
  ↓ Retry 2: Wait 2s → Timeout
  ↓ Retry 3: Wait 4s → Timeout
  ↓ Use cached statement from previous day
  ↓ Continue reconciliation with stale data (flagged)
  ↓ Alert: "Using stale bank statement"
```

### Scenario 2: Lock Acquisition Fails
```
Lock query → Another run already active
  ↓ Release control
  ↓ Sleep 5 minutes
  ↓ Retry lock acquisition
  ↓ If still locked after 15 min total: Alert operations
```

### Scenario 3: Kafka Publish Fails
```
Publish event → Kafka broker unreachable
  ↓ Retry 3x with backoff
  ↓ If all retries fail: Queue in local cache
  ↓ Retry on next poll (background task)
  ↓ No data loss (DB records persisted)
```

## Metrics & Monitoring

| Metric | Value | Alert Threshold |
|--------|-------|-----------------|
| Total duration | 28s | > 4 hours (SLA) |
| Lock wait | 1s | > 30 min (stuck) |
| Ledger query | 2s | > 10s (warning) |
| Bank API | 4s | > 30s (error) |
| DB writes | 10s | > 60s (error) |
| Event publish | 5s | > 30s (warning) |
| Mismatches found | 1 | > 100 (investigation) |
| Auto-fix success | 1 | < 50% (warning) |
