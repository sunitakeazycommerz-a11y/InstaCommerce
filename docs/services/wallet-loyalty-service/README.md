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

## Known Limitations

1. No cross-currency support (INR only)
2. No transaction analytics dashboard
3. No referral reward automation (manual payout)
4. No points marketplace (redemption limited to top-up only)

**Roadmap (Wave 34+):**
- Referral reward automation
- Points marketplace (partner rewards)
- Gamification (achievements, badges)
- Real-time transaction dashboard

