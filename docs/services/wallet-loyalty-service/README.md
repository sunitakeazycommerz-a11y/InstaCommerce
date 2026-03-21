# Wallet & Loyalty Service

**User wallet balance management with top-up/debit operations, loyalty points accumulation and redemption, referral tracking, with Wave 30 @Version optimistic locking for money-path safety and idempotent loyalty redemption.**

| Property | Value |
|----------|-------|
| **Module** | `:services:wallet-loyalty-service` |
| **Port** | `8090` |
| **Runtime** | Spring Boot 4.0 · Java 21 |
| **Database** | PostgreSQL 15+ (@Version locking, CHECK constraints) |
| **Messaging** | Kafka consumer (orders.events, payments.events) |
| **Resilience** | Optimistic locking, idempotency keys |
| **Auth** | JWT RS256 + user isolation |
| **Owner** | Payments & Loyalty Team |
| **SLO** | P50: 40ms, P99: 100ms, 99.95% availability, exact consistency |

---

## Service Role & Ownership

**Owns:**
- `wallets` table — user wallet balances with @Version for safe concurrent updates (Wave 30)
- `wallet_transactions` table — debit/credit ledger with idempotency keys
- `loyalty_accounts` table — loyalty points account per user with @Version
- `loyalty_transactions` table — points earned/redeemed with idempotency (Wave 30)
- `referral_codes` table — unique referral code per user
- `shedlock` table — distributed lock for scheduled loyalty jobs

**Does NOT own:**
- Coupon storage (→ `pricing-service`)
- Order data (→ `order-service`)
- Payment receipts (→ `payment-service`)

**Consumes:**
- `orders.events` — OrderCreated (add loyalty points)
- `payments.events` — PaymentCompleted (wallet topup)

**Publishes:**
- `wallet.events` — WalletDebited, WalletCredited, LoyaltyPointsRedeemed (via outbox)

---

## Core APIs

### Get Wallet Balance

**GET /wallet/balance**
```
Auth: JWT (extracts userId)

Response (200):
{
  "userId": "user-uuid",
  "balanceCents": 50000,
  "lastUpdated": "2025-03-21T10:00:00Z"
}
```

### Top Up Wallet

**POST /wallet/topup** (Wave 30: Verifies payment completion)
```
Request Body:
{
  "paymentReference": "payment-uuid",
  "amountCents": 10000
}

Processing:
  1. Verify payment-service confirms completion
  2. Check amount matches payment
  3. INSERT into wallet_transactions (TOPUP, idempotency_key = paymentRef)
  4. UPDATE wallets SET balance = balance + amount (optimistic lock)
     If conflict: retry with exponential backoff
  5. Publish WalletCredited event (outbox)

Response (200):
{
  "transactionId": "transaction-uuid",
  "balanceCents": 60000,
  "timestamp": "2025-03-21T10:00:00Z"
}

Error: 402 if payment incomplete, 409 if balance update conflicts
```

**Idempotency:** If topup called twice with same paymentReference:
- First call: transact, update balance
- Second call: idempotency key prevents duplicate, returns same result

### Debit Wallet (Internal: Checkout Orchestrator)

**POST /wallet/debit** (gRPC internal only)
```
Service-to-service call (no JWT auth, IP whitelisted)

Request:
{
  "userId": "user-uuid",
  "amountCents": 5000,
  "orderId": "order-uuid",
  "idempotencyKey": "order-uuid"  (ensures retry-safety)
}

Processing:
  1. Acquire lock on wallet (SELECT ... FOR UPDATE)
  2. Check balance >= amount (CHECK constraint backup)
  3. INSERT into wallet_transactions (DEBIT)
  4. UPDATE wallets SET balance = balance - amount
     WHERE user_id = ? AND version = ? (optimistic lock with @Version)
  5. If version mismatch: retry (exponential backoff)
  6. Publish WalletDebited event

Response:
{
  "transactionId": "transaction-uuid",
  "balanceCents": 55000
}

Error: 402 PAYMENT_REQUIRED if insufficient funds
```

### Loyalty: Get Points Balance

**GET /loyalty/balance**
```
Response (200):
{
  "userId": "user-uuid",
  "pointsBalance": 2500,
  "tierLevel": "GOLD",
  "pointsExpiring": [
    {
      "points": 100,
      "expiresAt": "2025-06-21"
    }
  ]
}
```

### Loyalty: Redeem Points

**POST /loyalty/redeem** (Wave 30: Idempotent)
```
Request Body:
{
  "points": 500,
  "orderId": "order-uuid",
  "idempotencyKey": "order-uuid"
}

Processing (Wave 30):
  1. Find loyalty_account for user (with @Version)
  2. Check points balance >= requested
  3. INSERT into loyalty_transactions
     VALUES (userId, orderId, -500, REDEEMED)
     ON CONFLICT (order_id, user_id) DO NOTHING
     (ensures: same order can't redeem twice)
  4. UPDATE loyalty_accounts
     SET points_balance = points_balance - 500
     WHERE user_id = ? AND version = ?
     (optimistic lock prevents concurrent depletion)
  5. If version conflict: retry with new version
  6. Publish LoyaltyPointsRedeemed event

Response (200):
{
  "pointsRedeemed": 500,
  "discountCents": 5000,
  "newBalanceCents": 2000,
  "timestamp": "2025-03-21T10:00:00Z"
}

Error: 409 if concurrent updates exceed balance (version conflict)
```

---

## Database Schema (Wave 30 Money-Path Safety)

### Wallets Table (@Version Locking)

```sql
wallets:
  id              UUID PRIMARY KEY
  user_id         UUID NOT NULL UNIQUE
  balance_cents   BIGINT NOT NULL CHECK(balance_cents >= 0)
  version         BIGINT NOT NULL DEFAULT 0 (@Version optimistic locking)
  created_at      TIMESTAMP NOT NULL DEFAULT now()
  updated_at      TIMESTAMP NOT NULL DEFAULT now()

Indexes:
  - user_id (per-user lookups)

Constraint:
  - CHECK(balance_cents >= 0) — database-level protection against negative balances
```

### Wallet Transactions (Wave 30: Idempotency)

```sql
wallet_transactions:
  id              UUID PRIMARY KEY
  user_id         UUID NOT NULL
  type            ENUM('TOPUP', 'DEBIT', 'ADJUSTMENT')
  amount_cents    BIGINT NOT NULL
  order_id        UUID
  reference_key   VARCHAR(255) (e.g., paymentId, orderId for idempotency)
  status          ENUM('COMPLETED', 'PENDING', 'FAILED')
  idempotency_key VARCHAR(255) UNIQUE (prevents duplicate processing)
  created_at      TIMESTAMP NOT NULL DEFAULT now()

Indexes:
  - user_id, created_at (transaction history)
  - idempotency_key (dedup on retry)
  - UNIQUE (order_id, type) (per-order limits if needed)
```

### Loyalty Accounts (@Version)

```sql
loyalty_accounts:
  id              UUID PRIMARY KEY
  user_id         UUID NOT NULL UNIQUE
  points_balance  BIGINT NOT NULL CHECK(points_balance >= 0)
  tier_level      ENUM('BRONZE', 'SILVER', 'GOLD', 'PLATINUM') DEFAULT 'BRONZE'
  version         BIGINT NOT NULL DEFAULT 0 (@Version for concurrent safety)
  created_at      TIMESTAMP NOT NULL DEFAULT now()
  updated_at      TIMESTAMP NOT NULL DEFAULT now()

Constraint:
  - CHECK(points_balance >= 0) — cannot have negative points
```

### Loyalty Transactions (Wave 30: Idempotency)

```sql
loyalty_transactions:
  id              UUID PRIMARY KEY
  user_id         UUID NOT NULL
  points_delta    BIGINT NOT NULL (positive or negative)
  type            ENUM('EARNED', 'REDEEMED', 'ADJUSTMENT', 'EXPIRY')
  order_id        UUID (nullable: earned from, or redeemed for)
  reference_txn_id UUID (e.g., wallet_transaction_id that earned points)
  idempotency_key VARCHAR(255) UNIQUE (order_id + user_id → prevents double-redeem)
  created_at      TIMESTAMP NOT NULL DEFAULT now()

Indexes:
  - user_id, created_at (history)
  - UNIQUE (order_id, user_id) (per-order idempotency for redemption)
```

---

## Resilience: Optimistic Locking Strategy (Wave 30)

**Wallet Update Flow:**

```
1. SELECT wallets WHERE user_id = ? FOR UPDATE (pessimistic lock briefly)
2. Check balance sufficient
3. Update with @Version:
   UPDATE wallets
   SET balance_cents = ?,
       version = version + 1
   WHERE user_id = ? AND version = ?

4. If rows updated == 0: version mismatch
   → Retry entire transaction (exponential backoff)
   → Max 3 retries, then fail
```

**Guarantees:**
- **Lost update prevention:** @Version ensures concurrent updates don't overwrite each other
- **Idempotency:** Idempotency keys prevent duplicate transactions on retry
- **Exactness:** No floating-point arithmetic, BIGINT cents only

---

## Expiry Job (ShedLock, Wave 30 Slice Pagination)

```
Scheduled Job: ExpireLoyaltyPointsJob
Frequency: Daily at 2 AM UTC
Lock: ShedLock (single execution across replicas)

Processing (Wave 30 pagination):
  1. Query loyalty_transactions with points expiring today
  2. Batch update with Slice (not Page) to avoid memory issues
     FOR EACH slice of 1000 transactions:
       UPDATE loyalty_accounts SET points_balance = ...
       WHERE user_id IN (...)
       AND version = ? (check version hasn't changed)
  3. Publish LoyaltyPointsExpired events
  4. Log audit trail

Pagination via Slice (Wave 30):
  - Uses OFFSET/LIMIT efficiently
  - No COUNT(*) (slow on large tables)
  - Processes in chunks of 1000
```

---

## Kafka Events

### Consumed

**orders.events**
```
OrderCreated → Award loyalty points
Payload: { orderId, userId, totalCents }
Action: points = totalCents / 100 (1 point per 100 cents)
```

**payments.events**
```
PaymentCompleted → Publish WalletCredited
Payload: { paymentId, amountCents }
```

### Published (Outbox)

**wallet.events**
```
WalletDebited: { walletId, userId, amountCents, orderId }
WalletCredited: { walletId, userId, amountCents, paymentId }
LoyaltyPointsRedeemed: { userId, pointsRedeemed, discountCents, orderId }
LoyaltyPointsExpired: { userId, pointsExpired }
```

---

## Testing

```bash
./gradlew :services:wallet-loyalty-service:test
./gradlew :services:wallet-loyalty-service:integrationTest
```

Test focus (Wave 30):
- Concurrent updates with @Version locking
- Idempotency key deduplication
- Negative balance prevention (CHECK constraint)
- Points expiry with Slice pagination
- Redemption race conditions

---

## Deployment

```bash
export WALLET_DB_URL=jdbc:postgresql://localhost:5432/wallet
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
./gradlew :services:wallet-loyalty-service:bootRun
```

---

## Wallet Ledger Transactions

Transaction types and semantics:

```
Type: TOPUP
  Source: External payment (payment-service calls /wallet/topup)
  Idempotency: paymentId (no duplicate top-ups for same payment)
  Audit: Record user_id, payment_id, amount, timestamp
  State Change: balance += amount

Type: DEBIT
  Source: Internal checkout (checkout-orchestrator service call)
  Idempotency: orderId (no duplicate debits for same order)
  Audit: Record user_id, order_id, amount, timestamp
  State Change: balance -= amount (with CHECK constraint protection)

Type: ADJUSTMENT
  Source: Admin correction (manual reconciliation)
  Audit: Record admin_user_id, reason, amount, timestamp
  Requires: SUPER_ADMIN role
  State Change: balance += amount (or - for reversal)

Type: REFUND
  Source: Order cancellation
  Idempotency: order_id + REFUND type unique
  Audit: Record original_order_id, refund_reason, timestamp
  State Change: balance += refund_amount
```

## Balance Reconciliation Procedures

**Daily Reconciliation Job (3 AM UTC):**
```
1. Load all wallets with updated_at < 24h ago
2. FOR EACH wallet:
   a. Query wallet_transactions ledger
   b. Calculate expected balance = SUM(transaction amounts)
   c. Compare to wallet.balance_cents
   d. IF mismatch > 100 cents: Flag for manual review
3. Publish WalletReconciliationCompleted event
4. Alert ops if > 1% wallets need review
```

**Manual Correction Workflow:**
```
1. Ops identifies mismatch (e.g., balance = 1000, expected = 1050)
2. POST /wallet/reconcile
   {
     "walletId": "wallet-uuid",
     "discrepancyCents": 50,
     "reason": "CDC relay lag - order payment not processed",
     "adminUserId": "admin-1"
   }
3. System:
   a. INSERT adjustment transaction (type: ADJUSTMENT)
   b. UPDATE wallets SET balance = balance + 50
   c. Create audit log (immutable)
4. Send notification to user (transparent)
```

## Anti-Fraud Monitoring

**Velocity Checks (prevents stolen credentials):**
```
Rule: Top-up spike
  IF topup > 5x average in 1 hour: Flag for review
  IF topup > 100K in 1 hour: Block + notify user

Rule: Redemption spike
  IF points redeemed > 10x average in 1 hour: Flag
  IF points redeemed > 90% balance: Require email confirmation

Rule: Device anomaly
  IF topup from new device + high amount: Flag
  IF redemption from different country: Email verification
```

**Monitoring:**
```yaml
metrics:
  - wallet_topup_spike_detection (anomaly score)
  - wallet_redemption_spike_detection (anomaly score)
  - fraud_flagged_wallets (counter)
  - anti_fraud_alerts_triggered (counter)
```

## Monitoring & Alerts (20+ Metrics)

### Key Metrics

| Metric | Type | Alert Threshold | Purpose |
|--------|------|-----------------|---------|
| `wallet_balance_query_latency_ms` | Histogram (p99) | > 100ms | Balance lookup speed |
| `wallet_topup_latency_ms` | Histogram (p99) | > 300ms | Top-up operation latency |
| `wallet_debit_latency_ms` | Histogram (p99) | > 200ms | Debit operation latency |
| `wallet_topup_success_rate` | Gauge | < 95% = alert | Top-up success |
| `wallet_debit_success_rate` | Gauge | < 99% = alert | Debit success (critical path) |
| `wallet_reconciliation_mismatches` | Counter | > 1% = alert | Ledger inconsistencies |
| `wallet_negative_balance_violations` | Counter | > 0 = SEV-1 | CHECK constraint violations |
| `wallet_transaction_idempotency_hits` | Counter | N/A | Retry deduplication |
| `loyalty_points_redeemed_total` | Counter (by tier) | N/A | Redemption volume |
| `loyalty_points_earned_total` | Counter | N/A | Point accumulation |
| `loyalty_points_expiry_total` | Counter | > 50K/day = investigate | Expired points (revenue impact) |
| `loyalty_tier_progression_count` | Counter (by tier) | N/A | Users advancing tiers |
| `loyalty_tier_demotion_count` | Counter | > 100/month = investigate | Users losing tier status |
| `loyalty_redemption_conversion_rate` | Gauge | < 5% = investigate | % of points redeemed |
| `loyalty_account_creation_latency_ms` | Histogram | > 100ms | Account setup speed |
| `payment_service_call_latency_ms` | Histogram (p99) | > 500ms | Payment verification |
| `payment_service_error_rate` | Gauge | > 5% = circuit open | Payment service health |
| `optimistic_lock_conflict_rate` | Gauge | > 1% = contention | Version update conflicts |
| `db_connection_pool_active` | Gauge | > 27/30 = contention | Connection pool usage |
| `outbox_event_publish_latency_ms` | Histogram (p99) | > 100ms | Event delivery latency |

### Alerting Rules

```yaml
alerts:
  - name: WalletDebitFailure
    condition: rate(wallet_debit_success_rate[5m]) < 0.99
    duration: 5m
    severity: SEV-1
    action: Page on-call; checkout at risk; verify payment-service healthy

  - name: WalletReconciliationMismatch
    condition: wallet_reconciliation_mismatches > COUNT(*) * 0.01
    duration: 10m
    severity: SEV-2
    action: Check CDC relay; verify ledger consistency; manual audit

  - name: LoyaltyRedemptionBlocked
    condition: rate(loyalty_redemption_errors[5m]) > 0
    duration: 5m
    severity: SEV-2
    action: Check loyalty-account update failures; user impact

  - name: OptimisticLockContention
    condition: optimistic_lock_conflict_rate > 0.01
    duration: 10m
    severity: SEV-3
    action: Monitor concurrent wallet updates; possible high-frequency trading
```

## Security Considerations

### Threat Mitigations

1. **Negative Balance Prevention**: CHECK constraint + optimistic locking prevents overdraft
2. **Idempotency Protection**: Transaction idempotency keys prevent double-charging
3. **User Isolation**: JWT token extracts userId; users only access own wallet
4. **Concurrent Update Safety**: @Version optimistic locking prevents lost updates
5. **Fraud Detection**: Velocity checks flag suspicious activity
6. **Immutable Audit Trail**: All transactions logged permanently (7-year retention)
7. **Payment Verification**: Topup calls payment-service to verify completion

### Known Risks

- **Payment Service Down**: Topup fails if payment-service unreachable
- **CDC Relay Lag**: Wallet state may be stale (eventual consistency)
- **Concurrent Redemption**: Multiple redemptions may exceed available points (retry required)
- **Fraud False Positives**: Legitimate high-value transactions flagged (user contacts support)

## Troubleshooting (8+ Scenarios)

### Scenario 1: Wallet Debit Failures (Checkout Broken)

**Indicators:**
- HTTP 402 PAYMENT_REQUIRED spike
- `wallet_debit_success_rate` < 99%
- Users cannot checkout

**Root Causes:**
1. Wallet service down or slow
2. Database connection pool exhausted
3. Optimistic lock conflict (high concurrency)
4. User balance insufficient

**Resolution:**
```bash
# Check wallet-service readiness
kubectl get pods -n services | grep wallet

# Check if balance issue
curl http://wallet-loyalty-service:8090/wallet/balance \
  -H "Authorization: Bearer $JWT_TOKEN"

# Check database health
curl http://wallet-loyalty-service:8090/actuator/health/ready

# Increase connection pool if contention
SPRING_DATASOURCE_HIKARI_POOL_SIZE=50 (from 30)

# Force retry on 409 Conflict
Retry-After: 1s (exponential backoff)
```

### Scenario 2: Optimistic Lock Conflict Spike (409 Conflict errors)

**Indicators:**
- `optimistic_lock_conflict_rate` > 1%
- HTTP 409 Conflict responses increase
- Users experience "Please try again" errors

**Root Causes:**
1. Multiple concurrent debit attempts for same order (unlikely)
2. Concurrent topup + debit (payment + checkout happening simultaneously)
3. Manual adjustment conflicts with automatic flow

**Resolution:**
```bash
# Monitor conflict pattern
SELECT user_id, COUNT(*) as conflict_count
FROM wallet_conflict_log
WHERE created_at > NOW() - INTERVAL '5 minutes'
GROUP BY user_id
ORDER BY conflict_count DESC;

# Increase retry attempts
WALLET_RETRY_MAX_ATTEMPTS=5 (from 3)

# Use pessimistic lock for critical operations
-- SELECT ... FOR UPDATE (strong consistency)
```

### Scenario 3: Wallet Reconciliation Fails (Ledger desync)

**Indicators:**
- `wallet_reconciliation_mismatches` > 1% of wallets
- Manual audit shows balance ≠ ledger sum
- Users report incorrect balance

**Root Causes:**
1. CDC relay lag (events not yet applied)
2. Dropped transaction (network issue)
3. Database corruption (rare)
4. Billing system sent event twice (Kafka duplicate)

**Resolution:**
```bash
# Check CDC relay lag
SELECT AVG(lag_ms), MAX(lag_ms) FROM cdc_lag_metrics
WHERE topic = 'wallet.events';

# Verify ledger consistency
SELECT user_id, balance_cents,
       SUM(amount) as ledger_sum
FROM wallet_transactions
WHERE user_id = 'user-uuid'
GROUP BY user_id
HAVING balance_cents != SUM(amount);

# Manual correction if needed
curl -X POST http://wallet-loyalty-service:8090/admin/reconcile \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "walletId": "wallet-uuid",
    "discrepancyCents": 500,
    "reason": "CDC relay lag - manual correction"
  }'
```

### Scenario 4: Loyalty Points Expiry Failure (Scheduled job fails)

**Indicators:**
- ExpireLoyaltyPointsJob didn't run at 2 AM
- `loyalty_points_expiry_total` hasn't incremented
- Users complain points expired but still in balance

**Root Causes:**
1. ShedLock distributed lock stuck (previous run didn't release)
2. Database timeout during batch update (millions of points)
3. Memory OOM during Slice pagination

**Resolution:**
```bash
# Check ShedLock status
SELECT * FROM shedlock
WHERE name = 'ExpireLoyaltyPointsJob';

# Clear stuck lock if needed
DELETE FROM shedlock
WHERE name = 'ExpireLoyaltyPointsJob'
AND lock_at_most_until < NOW();

# Trigger manual expiry
curl -X POST http://wallet-loyalty-service:8090/admin/jobs/expire-points

# Increase job memory
LOYALTY_JOB_MEMORY_MB=1024 (from 512)
```

### Scenario 5: Payment Service Unreachable (Topup fails)

**Indicators:**
- HTTP 503 errors on POST /wallet/topup
- `payment_service_error_rate` = 100%
- Circuit breaker OPEN

**Root Causes:**
1. Payment-service down
2. Network firewall rule blocking traffic
3. Load balancer issue

**Resolution:**
```bash
# Check payment-service
kubectl get pods -n services | grep payment

# Test connectivity
curl http://payment-service:8084/actuator/health/live

# Force circuit breaker reset
curl -X POST http://wallet-loyalty-service:8090/admin/circuit-breaker/reset

# Allow topup without verification (not recommended)
WALLET_SKIP_PAYMENT_VERIFICATION=true
```

### Scenario 6: Loyalty Tier Progression Stuck (Not advancing users)

**Indicators:**
- Tier progression count = 0 for extended period
- Users have high point balance but still BRONZE tier
- Expected GOLD users stuck at SILVER

**Root Causes:**
1. Tier progression job not running (ShedLock stuck)
2. Tier threshold config misconfigured
3. Loyalty account @Version conflict

**Resolution:**
```bash
# Check tier progression config
curl http://wallet-loyalty-service:8090/admin/config/tier-thresholds

# Manually trigger tier check
curl -X POST http://wallet-loyalty-service:8090/admin/jobs/update-tiers

# Verify tier thresholds
curl -X GET http://wallet-loyalty-service:8090/admin/loyalty/user/{userId}/tier-status
```

### Scenario 7: Idempotency Key Collision (Duplicate transactions)

**Indicators:**
- Same orderId appears twice in wallet_transactions
- `wallet_transaction_idempotency_hits` = 0 (dedup not working)
- User charged twice for single purchase

**Root Causes:**
1. Idempotency key not set in checkout service
2. Key collision (different orders same hash)
3. Transaction cleanup bug (deleted and re-inserted)

**Resolution:**
```bash
# Find duplicate transactions
SELECT order_id, COUNT(*) as dup_count
FROM wallet_transactions
WHERE type = 'DEBIT'
GROUP BY order_id
HAVING COUNT(*) > 1;

# Manually reverse duplicate charge
curl -X POST http://wallet-loyalty-service:8090/admin/transactions/{txnId}/reverse \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Ensure idempotency key always set
WALLET_ENFORCE_IDEMPOTENCY=true
```

### Scenario 8: Anti-Fraud Flag False Positive (Legitimate user blocked)

**Indicators:**
- User cannot topup (anti-fraud flag triggered)
- `fraud_flagged_wallets` count high
- Support tickets: "My wallet is locked"

**Root Causes:**
1. Velocity threshold too aggressive (5x average)
2. User suddenly increased spending (promotion)
3. Device change triggers flag (travel, new phone)

**Resolution:**
```bash
# Check anti-fraud flag
curl http://wallet-loyalty-service:8090/wallet/{walletId}/fraud-status

# Whitelist user temporarily
curl -X POST http://wallet-loyalty-service:8090/admin/fraud/whitelist \
  -d '{"walletId": "wallet-uuid", "ttlHours": 24}'

# Adjust velocity thresholds
FRAUD_TOPUP_SPIKE_THRESHOLD=10 (from 5)
FRAUD_TOPUP_DAILY_MAX=500000 (from 100000 cents)

# Review and clear flag
curl -X POST http://wallet-loyalty-service:8090/admin/fraud/clear \
  -d '{"walletId": "wallet-uuid", "reason": "Manual review - legitimate user"}'
```

## Production Runbook Patterns

### Runbook 1: Emergency Wallet Topup Restoration

**Scenario:** Payment-service down, users can't add wallet balance

**SLA:** < 10 min to mitigation

1. **Alert Received:** PaymentServiceDown alert
2. **Diagnosis:**
   - Check payment-service pods: `kubectl get pods -n services | grep payment`
   - If DOWN: Decision on recovery
3. **Mitigation:**
   - Option A (Safe): Block topup, display "Service temporarily unavailable"
   - Option B (Risky): Allow topup without verification (manual settlement later)
4. **Recovery:**
   - Restore payment-service
   - Audit any topups made without verification
   - Reconcile wallet balances
5. **Post-Incident:**
   - Add payment-service retry timeout
   - Consider fallback payment processor

### Runbook 2: Loyalty Points Mass Expiry

**Scenario:** > 1M points expiring, affecting user engagement

1. **Prevention:** Pre-announce expiry (2 weeks notice in app)
2. **Execution:**
   - Run expiry job at 2 AM UTC (low-traffic window)
   - Monitor `loyalty_points_expiry_total` metric
3. **Communication:**
   - Send in-app notification: "Your points expire in 7 days"
   - Email top users (GOLD tier, > 1000 points)
4. **Retention:**
   - Offer 1.5x point multiplier for spending before expiry
   - Auto-extend expiry 30 days for power users

## Related Documentation

- **ADR-009**: Wallet Money-Path Safety (optimistic locking model)
- **ADR-010**: Idempotency Keys & Deduplication (payment guarantees)
- **Runbook**: wallet-loyalty-service/runbook.md (on-call procedures)

## Configuration

### Environment Variables

```env
SERVER_PORT=8090
SPRING_PROFILES_ACTIVE=gcp

SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/wallet
SPRING_DATASOURCE_HIKARI_POOL_SIZE=30

WALLET_RETRY_MAX_ATTEMPTS=3
WALLET_RETRY_BACKOFF_MULTIPLIER=2.0
WALLET_SKIP_PAYMENT_VERIFICATION=false

LOYALTY_TIER_BRONZE_THRESHOLD=0
LOYALTY_TIER_SILVER_THRESHOLD=1000
LOYALTY_TIER_GOLD_THRESHOLD=5000
LOYALTY_TIER_PLATINUM_THRESHOLD=20000

FRAUD_TOPUP_SPIKE_THRESHOLD=5
FRAUD_TOPUP_DAILY_MAX=100000

OTEL_TRACES_SAMPLER=always_on
```

## Known Limitations

1. No cross-currency support (INR only)
2. No transaction analytics dashboard
3. No referral reward automation (manual payout)
4. No points marketplace (redemption limited to top-up only)
5. No subscription billing (one-time payments only)
6. No escrow/hold functionality (for pending orders)

**Roadmap (Wave 41+):**
- Multi-currency support (USD, EUR)
- Referral reward automation
- Points marketplace (partner rewards)
- Gamification (achievements, badges)
- Real-time transaction dashboard
- Subscription support

