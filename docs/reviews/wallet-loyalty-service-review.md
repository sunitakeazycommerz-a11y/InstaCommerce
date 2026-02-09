# Wallet-Loyalty-Service — Deep Architecture Review

**Reviewer:** Senior Fintech Architect  
**Service:** `wallet-loyalty-service`  
**Stack:** Java 21 · Spring Boot · PostgreSQL · Kafka · Flyway · ShedLock · Caffeine  
**Scale Context:** Q-Commerce platform, 20M+ users  
**Date:** 2025-07-02

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Codebase Inventory](#2-codebase-inventory)
3. [Business Logic Review](#3-business-logic-review)
   - [3.1 Wallet Operations](#31-wallet-operations)
   - [3.2 Pessimistic Locking](#32-pessimistic-locking)
   - [3.3 Insufficient Balance Handling](#33-insufficient-balance-handling)
   - [3.4 Loyalty Points Engine](#34-loyalty-points-engine)
   - [3.5 Referral System](#35-referral-system)
   - [3.6 Points Expiry Job](#36-points-expiry-job)
   - [3.7 Transaction History](#37-transaction-history)
4. [SLA & Financial Integrity](#4-sla--financial-integrity)
   - [4.1 Atomicity](#41-atomicity)
   - [4.2 Idempotency](#42-idempotency)
   - [4.3 Reconciliation](#43-reconciliation)
   - [4.4 Audit Trail](#44-audit-trail)
5. [Missing Features & Gap Analysis](#5-missing-features--gap-analysis)
6. [Q-Commerce Competitor Comparison](#6-q-commerce-competitor-comparison)
7. [Security Review](#7-security-review)
8. [Infrastructure & Observability](#8-infrastructure--observability)
9. [Critical Bugs & Risks](#9-critical-bugs--risks)
10. [Prioritized Recommendations](#10-prioritized-recommendations)

---

## 1. Executive Summary

The wallet-loyalty-service provides a **functional but incomplete** foundation for wallet, loyalty, and referral capabilities. The core wallet debit/credit flow is sound — pessimistic locking, idempotency via unique index, transactional outbox pattern, and DB-level `CHECK` constraints are all present. However, **several critical gaps exist** that would block production-readiness at 20M+ user scale:

| Area | Status | Risk Level |
|------|--------|------------|
| Wallet credit/debit | ✅ Implemented | 🟢 Low |
| Pessimistic locking | ✅ Implemented | 🟡 Medium (race on `checkIdempotency`) |
| Idempotency | ⚠️ Partial (TOCTOU race) | 🔴 High |
| Double-entry bookkeeping | ❌ Not implemented | 🔴 High |
| Points expiry job | ⚠️ Scalability issue | 🔴 High |
| Referral fraud prevention | ⚠️ Basic only | 🟡 Medium |
| Wallet limits / KYC | ❌ Not implemented | 🔴 Critical (regulatory) |
| Cashback vesting | ❌ Not implemented | 🟡 Medium |
| Auto-debit (wallet+card split) | ❌ Not implemented | 🟡 Medium |
| Gift cards | ❌ Not implemented | 🟢 Low (P2) |

**Verdict:** The service needs **6-8 weeks of hardening** before handling real money at scale. The idempotency TOCTOU race, lack of double-entry, and missing KYC limits are the top 3 blockers.

---

## 2. Codebase Inventory

| Layer | Files | Notes |
|-------|-------|-------|
| **Domain Models** | `Wallet`, `WalletTransaction`, `LoyaltyAccount`, `LoyaltyTransaction`, `LoyaltyTier`, `ReferralCode`, `ReferralRedemption`, `OutboxEvent` | 8 entities |
| **Services** | `WalletService`, `LoyaltyService`, `ReferralService`, `PointsExpiryJob`, `OutboxService` | 5 services |
| **Controllers** | `WalletController`, `LoyaltyController`, `ReferralController` | 3 REST controllers |
| **Repositories** | 7 JPA repositories | Standard Spring Data JPA |
| **Kafka Consumers** | `OrderEventConsumer`, `PaymentEventConsumer` | 2 event consumers |
| **Config** | `SecurityConfig`, `ShedLockConfig`, `SchedulerConfig`, `CacheConfig`, `WalletProperties` | 5 config classes |
| **Security** | `JwtAuthenticationFilter`, `DefaultJwtService`, `JwtKeyLoader`, `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler` | 5 security classes |
| **Exceptions** | `GlobalExceptionHandler`, `ApiException`, `DuplicateTransactionException`, `InsufficientBalanceException`, `WalletNotFoundException`, `TraceIdProvider` | 6 exception classes |
| **DTOs** | 4 request records, 5 response records | Clean Java records |
| **Migrations** | V1–V6 (wallets, transactions, loyalty, referrals, outbox, shedlock) | 6 Flyway scripts |

**Total: 53 source files reviewed.**

---

## 3. Business Logic Review

### 3.1 Wallet Operations

**File:** `WalletService.java`

#### Credit Flow
```
1. checkIdempotency(refType, refId)         — read-check for duplicates
2. findByUserIdForUpdate(userId)             — SELECT ... FOR UPDATE
3. wallet.balanceCents += amountCents         — in-memory add
4. walletRepository.save(wallet)             — persist new balance
5. recordTransaction(...)                    — insert wallet_transaction row
6. outboxService.publish(...)                — transactional outbox event
```

#### Debit Flow
```
1. checkIdempotency(refType, refId)         — read-check for duplicates
2. findByUserIdForUpdate(userId)             — SELECT ... FOR UPDATE
3. if (balance < amount) throw               — insufficient balance check
4. wallet.balanceCents -= amountCents         — in-memory subtract
5. walletRepository.save(wallet)             — persist new balance
6. recordTransaction(...)                    — insert wallet_transaction row
7. outboxService.publish(...)                — transactional outbox event
```

**✅ What's Good:**
- Uses `long` (cents) instead of `BigDecimal` — avoids floating-point rounding errors
- DB-level `CHECK (balance_cents >= 0)` constraint prevents negative balances even if app logic fails
- DB-level `CHECK (amount_cents > 0)` prevents zero/negative transaction amounts
- `@Version` field on `Wallet` entity provides optimistic locking as a safety net
- `balance_after_cents` column on each transaction provides a running snapshot
- All operations wrapped in `@Transactional`

**❌ What's Missing:**
- **No double-entry bookkeeping.** There is a single `wallets` table with a mutable `balance_cents` field. In proper financial systems, every credit should have a corresponding debit entry from a source ledger (e.g., "Platform Liability" account). Without double-entry, reconciliation becomes manual and audit-proof financial reporting is impossible.
- **No maximum credit/debit amount validation.** A single API call could credit ₹100 crore. Need per-transaction and daily limits.
- **No currency validation beyond default 'INR'.** The `currency` column exists but is never validated or used in multi-currency logic.

**Recommendation:** Introduce a `ledger_entries` table with `debit_account`, `credit_account`, `amount_cents` columns. Each wallet operation should create two ledger entries (double-entry). The wallet `balance_cents` becomes a derived/cached value verifiable against the ledger sum.

---

### 3.2 Pessimistic Locking

**File:** `WalletRepository.java`

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
Optional<Wallet> findByUserIdForUpdate(@Param("userId") UUID userId);
```

**✅ What's Good:**
- `PESSIMISTIC_WRITE` translates to `SELECT ... FOR UPDATE` in PostgreSQL — correct for preventing concurrent debits on the same wallet
- Both `credit()` and `debit()` use this lock — prevents lost updates
- DB `CHECK (balance_cents >= 0)` is a last-resort safety net

**⚠️ Issues:**

1. **Lock ordering not enforced.** If a referral redemption credits two wallets (referrer + referee) in the same transaction, and another concurrent transaction credits them in reverse order → **deadlock risk.** The `redeemReferral()` method calls `walletService.credit()` twice sequentially. If two referral redemptions involve overlapping users, PostgreSQL will detect the deadlock and abort one, but the error is not gracefully handled.

2. **No lock timeout configured.** The `@Lock` annotation doesn't set a timeout. Under heavy load (flash sales), a long queue of `SELECT ... FOR UPDATE` requests on a hot wallet (e.g., a popular referrer) can cause cascading timeouts. Recommended: set `jakarta.persistence.lock.timeout` to 3-5 seconds via `@QueryHints`.

3. **Missing index consideration.** `findByUserIdForUpdate` queries by `user_id`. The index `idx_wallets_user_id` exists, so the lock is row-level, not table-level. ✅ Good.

**Recommendation:**
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
@Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
Optional<Wallet> findByUserIdForUpdate(@Param("userId") UUID userId);
```

---

### 3.3 Insufficient Balance Handling

**File:** `WalletService.java:74-77`

```java
if (wallet.getBalanceCents() < amountCents) {
    throw new InsufficientBalanceException(
        String.format("Insufficient balance: available=%d, requested=%d",
            wallet.getBalanceCents(), amountCents));
}
```

**✅ What's Good:**
- Throws a domain-specific exception with HTTP 422 status
- Error response includes available vs requested amounts (useful for client-side wallet+card split UI)
- DB `CHECK (balance_cents >= 0)` is a backstop

**❌ What's Missing:**
- **No partial debit / wallet+card split.** The API is all-or-nothing. For Q-commerce, the checkout flow should support: "Debit ₹200 from wallet, charge remaining ₹300 on card." This requires a `partialDebitAllowed` flag or a separate endpoint that returns `{walletDebited: 200, remainingAmount: 300}`.
- **No response body with available balance on failure.** The exception message has it, but a structured response field like `availableBalanceCents` would help the frontend auto-fill the wallet amount.

**Recommendation:** Add a `tryDebit` method:
```java
public DebitResult tryDebit(UUID userId, long requestedCents) {
    Wallet w = findForUpdate(userId);
    long actualDebit = Math.min(w.getBalanceCents(), requestedCents);
    // debit actualDebit, return {debited, remaining}
}
```

---

### 3.4 Loyalty Points Engine

**Files:** `LoyaltyService.java`, `LoyaltyTier.java`, `LoyaltyAccount.java`

#### Points Earning
```java
int pointsEarned = (int) (orderTotalCents / 100) * walletProperties.getLoyalty().getPointsPerRupee();
```

**⚠️ Integer Truncation Bug (Line 49):**
```java
(int) (orderTotalCents / 100) * pointsPerRupee
```
The cast to `int` is applied to `(orderTotalCents / 100)` first due to parenthesization. For orders < ₹1 (< 100 cents), this evaluates to `0 * pointsPerRupee = 0`. This is probably intentional (no points for sub-rupee orders), but the implicit truncation is fragile. If `pointsPerRupee` ever becomes a multiplier > 1 (e.g., 2x during promotions), the truncation happens before multiplication, losing precision.

**Better:**
```java
int pointsEarned = (int) ((orderTotalCents * pointsPerRupee) / 100);
```

#### Tier Progression

| Tier | Threshold (lifetime points) |
|------|-----------------------------|
| BRONZE | 0 |
| SILVER | 5,000 |
| GOLD | 25,000 |
| PLATINUM | 100,000 |

```java
public static LoyaltyTier fromLifetimePoints(int lifetimePoints) {
    LoyaltyTier result = BRONZE;
    for (LoyaltyTier tier : values()) {
        if (lifetimePoints >= tier.threshold) result = tier;
    }
    return result;
}
```

**✅ What's Good:**
- Tier upgrade is checked after every `earnPoints()` call
- Upgrade triggers an outbox event (`TierUpgraded`) for downstream services (notifications, UI badge update)
- `lifetimePoints` is separate from `pointsBalance` — points redemption doesn't affect tier

**❌ What's Missing:**
- **No tier downgrade logic.** Once PLATINUM, always PLATINUM — even if a user is dormant for years. Competitors (Blinkit, Zepto) reset tiers annually.
- **No tier-based earn rate multiplier.** PLATINUM users should earn 2x-3x points. Current `pointsPerRupee` is a global constant.
- **No tier-based benefits mapping.** The tier enum has no associated perks (free delivery, priority support, etc.). This should be a separate `tier_benefits` configuration.
- **Loyalty earn lacks idempotency.** `earnPoints()` does not check for duplicate `orderId`. If the `OrderDelivered` Kafka event is replayed, the user gets double points. The `loyalty_transactions` table has no unique constraint on `(reference_type, reference_id)`.

**Recommendation:** Add a unique index on `loyalty_transactions(reference_type, reference_id)` similar to wallet transactions.

#### Points Redemption
- Redeems points but **does not convert them to wallet credit.** The `redeemPoints()` method decrements `pointsBalance` but doesn't call `walletService.credit()`. The points-to-currency conversion is missing entirely.
- **No minimum redemption threshold.** Users can redeem 1 point. Should require a minimum (e.g., 100 points = ₹1).
- **Redemption `referenceId` is `UUID.randomUUID()`** — not idempotent. If a client retries, a second deduction happens.

---

### 3.5 Referral System

**File:** `ReferralService.java`

#### Code Generation
```java
private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 31 chars
private static final int CODE_LENGTH = 8;
```

- **Alphabet:** 31 characters (excluded `0`, `1`, `I`, `O` to avoid visual ambiguity). ✅ Good UX decision.
- **Keyspace:** 31^8 ≈ 852 billion combinations. Collision-free for 20M users. ✅ Sufficient.
- **Collision handling:** Retry loop up to 10 attempts with DB lookup. ✅ Correct.
- **Uniqueness enforced at DB level:** `UNIQUE` constraint on `referral_codes.code`. ✅ Good.
- **Uses `SecureRandom`** — not predictable. ✅ Good.

#### Fraud Prevention

| Check | Implemented? | Notes |
|-------|-------------|-------|
| Self-referral (own code) | ✅ Yes | `referralCode.getUserId().equals(newUserId)` |
| One-time use per user | ✅ Yes | `referred_user_id UNIQUE` in `referral_redemptions` |
| Max uses per code | ✅ Yes | `uses >= maxUses` check |
| Code deactivation | ✅ Yes | `active` flag checked |
| Device fingerprint check | ❌ No | Same person, multiple accounts |
| Phone/email verification | ❌ No | Fake signups for referral farming |
| IP/location velocity check | ❌ No | Referral rings |
| Cooldown between redemptions | ❌ No | Burst redemption from bots |
| Referrer account age check | ❌ No | Freshly created accounts farming |

**⚠️ Critical Issue — No Referral Code Pessimistic Lock:**
```java
ReferralCode referralCode = referralCodeRepository.findByCode(code.toUpperCase())
    .orElseThrow(...);
```
This is a plain `SELECT` without `FOR UPDATE`. Two concurrent redemptions of the same code can both pass the `uses < maxUses` check and both increment `uses`, resulting in `uses > maxUses`. The `referral_redemptions.referred_user_id UNIQUE` constraint prevents the same user from redeeming twice, but does NOT prevent two different new users from exceeding `maxUses` in a race condition.

**Fix:** Add `findByCodeForUpdate()` with `@Lock(LockModeType.PESSIMISTIC_WRITE)`.

**⚠️ Reward Crediting is Not Idempotent:**
The `refId` for referral wallet credits is:
```java
String refId = "referral-" + redemption.getId().toString();
// "referral-{uuid}-referrer" and "referral-{uuid}-referee"
```
The `redemption.getId()` is a new UUID generated at save time. If the transaction partially fails after saving the redemption but before crediting wallets, a retry would generate a new `redemption.getId()` — so idempotency is not guaranteed. However, the `referred_user_id UNIQUE` constraint would prevent the redemption row from being re-inserted, so a retry would throw `REFERRAL_ALREADY_USED`. This means **partial failure leaves the redemption recorded but wallets uncredited** — a stuck state.

**Recommendation:** Use a saga pattern or ensure the entire referral flow (redemption + 2 wallet credits) is in a single DB transaction (which it currently is via `@Transactional`). The real risk is if one of the wallet credits fails (e.g., `DuplicateTransactionException` on retry) — the second credit won't happen and the transaction rolls back, but the calling code catches `DuplicateTransactionException` from `checkIdempotency`. Since both credits use unique `refId` suffixes (`-referrer`, `-referee`), this should be safe on first attempt. **Risk is LOW but should be tested.**

---

### 3.6 Points Expiry Job

**File:** `PointsExpiryJob.java`

```java
@Scheduled(cron = "0 0 2 * * *")                                           // 2 AM daily
@SchedulerLock(name = "points-expiry", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
@Transactional
public void expireOldPoints() {
    List<LoyaltyAccount> accounts = accountRepository.findAll();  // ← LOADS ALL ACCOUNTS
    for (LoyaltyAccount account : accounts) {
        int expirablePoints = transactionRepository.sumExpirablePoints(account.getId(), cutoff);
        // ...
    }
}
```

**🔴 Critical Scalability Issue:**
- `accountRepository.findAll()` loads **ALL 20M+ loyalty accounts into memory** in a single query.
- For each account, it executes a `sumExpirablePoints` query — that's **20M+ additional queries** (N+1 problem).
- The entire operation runs in a **single `@Transactional` block** — one massive DB transaction holding locks for potentially hours.
- JPA first-level cache will hold all 20M entities in memory → **OutOfMemoryError**.

**Impact at scale:** This job will either OOM-kill the pod or run for hours, holding a single database transaction open, blocking other writes.

**✅ What's Good:**
- ShedLock prevents multiple instances from running concurrently. ✅
- `lockAtLeastFor = "PT5M"` prevents rapid re-execution. ✅
- `lockAtMostFor = "PT30M"` prevents infinite lock hold. ✅ (But 30 min may not be enough for 20M accounts.)

**⚠️ Idempotency Issue:**
```java
txn.setReferenceId("expiry-" + UUID.randomUUID());
```
Each run generates a new `referenceId` with a random UUID. If the job crashes mid-way and restarts, it will re-process accounts that were already processed in the same run (since expiry transactions were committed before the crash — wait, no, the entire method is `@Transactional`, so if it crashes, everything rolls back). But at 20M accounts, the transaction will timeout or OOM before completing.

**Recommendation — Batch Processing:**
```java
@Scheduled(cron = "0 0 2 * * *")
@SchedulerLock(name = "points-expiry", lockAtLeastFor = "PT5M", lockAtMostFor = "PT2H")
public void expireOldPoints() {
    Instant cutoff = ...;
    int page = 0;
    int batchSize = 1000;
    while (true) {
        List<LoyaltyAccount> batch = accountRepository
            .findAccountsWithExpirablePoints(cutoff, PageRequest.of(page, batchSize));
        if (batch.isEmpty()) break;
        for (LoyaltyAccount account : batch) {
            expireSingleAccount(account, cutoff);  // each in its own @Transactional
        }
        page++;
    }
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void expireSingleAccount(LoyaltyAccount account, Instant cutoff) { ... }
```

**Additional issue with the expiry query:**
```sql
AND NOT EXISTS (SELECT 1 FROM LoyaltyTransaction e WHERE e.account.id = :accountId
    AND e.type = 'EXPIRE' AND e.referenceId = CAST(t.id AS string))
```
This tries to avoid double-expiring by checking if an EXPIRE transaction already exists with the `referenceId` matching the original EARN transaction's `id`. But the current code uses `"expiry-" + UUID.randomUUID()` as the referenceId — it does NOT reference the original EARN transaction ID. **The anti-duplication logic in the query will never match.** This means re-running the job (after a full rollback) could double-expire points.

---

### 3.7 Transaction History

**File:** `WalletTransactionRepository.java`, `WalletController.java`

```java
Page<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);
```

**✅ What's Good:**
- Paginated with Spring Data `Pageable` — default 20 per page
- Index `idx_wallet_txn_wallet_created ON wallet_transactions (wallet_id, created_at DESC)` — composite index perfectly matches the query pattern ✅
- `created_at DESC` ordering in index matches the query — avoids sort operations ✅

**⚠️ Issues:**
- **No cursor-based pagination.** For high-volume users (delivery agents, power users with 10K+ transactions), OFFSET-based pagination degrades. Page 500 requires scanning 10K rows. Use keyset pagination (`WHERE created_at < :lastSeen ORDER BY created_at DESC LIMIT 20`).
- **No date range filter.** Users should be able to filter by date range to reduce result sets.
- **No transaction type filter.** Can't filter by CREDIT-only or DEBIT-only.
- **`Page<>` return type includes `totalElements` count query** — expensive for large datasets. Switch to `Slice<>` if total count isn't needed.

---

## 4. SLA & Financial Integrity

### 4.1 Atomicity

**Verdict: ✅ GOOD — with caveats.**

The `credit()` and `debit()` methods are annotated with `@Transactional`. Within a single transaction:
1. Wallet balance is updated
2. Transaction record is inserted
3. Outbox event is inserted (`Propagation.MANDATORY` ensures it participates in the caller's TX)

If any step fails, the entire transaction rolls back. The outbox pattern ensures external event publishing is decoupled from the DB transaction — a CDC/poller reads the outbox table and publishes to Kafka separately.

**⚠️ Issue:** The `OutboxService.publish()` uses `Propagation.MANDATORY` — correct, it fails if there's no existing transaction. But there's **no outbox poller/CDC connector in this codebase.** The outbox events are written but never sent. Presumably Debezium or a custom poller exists elsewhere, but this is a gap in this service's boundary.

### 4.2 Idempotency

**Verdict: ⚠️ TOCTOU Race Condition — Must Fix.**

```java
private void checkIdempotency(ReferenceType refType, String refId) {
    transactionRepository.findByReferenceTypeAndReferenceId(refType, refId)
        .ifPresent(existing -> {
            throw new DuplicateTransactionException(...);
        });
}
```

This is a **read-then-write** check. The sequence is:
1. `checkIdempotency` → `SELECT` (no lock)
2. `findByUserIdForUpdate` → `SELECT ... FOR UPDATE`
3. `INSERT wallet_transaction`

**Race condition:** Two concurrent requests with the same `(refType, refId)`:
- Thread A: `checkIdempotency` → no existing row → proceeds
- Thread B: `checkIdempotency` → no existing row → proceeds
- Thread A: inserts transaction → success
- Thread B: inserts transaction → **`idx_wallet_txn_idempotent` unique index violation → `DataIntegrityViolationException` (unhandled, becomes 500)**

**The unique index `idx_wallet_txn_idempotent ON wallet_transactions (reference_type, reference_id)` IS the real idempotency guard**, and it works correctly. But the exception handling is wrong — a `DataIntegrityViolationException` from the unique index should be caught and translated to `DuplicateTransactionException` (409 Conflict) instead of bubbling up as a 500 Internal Server Error.

**Fix:**
```java
// In WalletService, wrap the save in a try-catch:
try {
    WalletTransaction txn = transactionRepository.save(newTxn);
} catch (DataIntegrityViolationException ex) {
    throw new DuplicateTransactionException("Duplicate: " + refType + "/" + refId);
}
```

**Additionally:** The `checkIdempotency` step is unnecessary overhead (an extra SELECT per request). The unique index alone is sufficient. Remove `checkIdempotency` and rely on the DB constraint with proper exception translation.

**⚠️ Top-Up Endpoint Idempotency Issue:**

```java
// WalletController.java:45
"topup-" + UUID.randomUUID()  // generates a NEW reference ID every time
```

Every top-up request generates a unique `referenceId`. This means **retrying a failed top-up creates a duplicate credit.** The client should provide an idempotency key (e.g., `X-Idempotency-Key` header — already allowed in CORS config but never used). The `TopUpRequest` DTO should include an optional `idempotencyKey` field.

### 4.3 Reconciliation

**Verdict: ❌ Not Implemented.**

There is no reconciliation mechanism:
- No scheduled job to verify `SUM(CASE WHEN type='CREDIT' THEN amount_cents ELSE -amount_cents END) = wallet.balance_cents`
- No API endpoint for ops/finance teams to trigger reconciliation
- No alerting on balance drift
- The `balance_after_cents` column on each transaction enables point-in-time balance verification, but no code uses it

**Recommendation:** Add a daily reconciliation job:
```sql
SELECT w.id, w.user_id, w.balance_cents,
    COALESCE(SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount_cents
                      WHEN t.type = 'DEBIT' THEN -t.amount_cents END), 0) AS computed_balance
FROM wallets w
LEFT JOIN wallet_transactions t ON t.wallet_id = w.id
GROUP BY w.id
HAVING w.balance_cents != computed_balance;
```

### 4.4 Audit Trail

**Verdict: ⚠️ Partial.**

| Requirement | Status |
|------------|--------|
| Every wallet mutation recorded | ✅ `wallet_transactions` table |
| Transaction immutability | ⚠️ No `UPDATE`/`DELETE` restrictions on `wallet_transactions` |
| Wallet balance change history | ✅ `balance_after_cents` column |
| Who performed the action | ❌ No `performed_by` or `actor_id` column |
| IP address / user agent | ❌ Not captured |
| Outbox events for downstream audit | ✅ Yes |
| Soft-delete vs hard-delete | ❌ No `deleted_at` column on any entity |

**Issues:**
- The `wallet_transactions` table has no database-level immutability. An admin with DB access can `UPDATE` or `DELETE` rows. Consider a PostgreSQL trigger or using an append-only audit table.
- No `actor_id` field distinguishes between user-initiated vs system-initiated (Kafka consumer) mutations. Critical for regulatory audits.
- The `description` field is free-text and not standardized. Should use structured metadata (JSON column).

---

## 5. Missing Features & Gap Analysis

### 5.1 Wallet Top-Up via Payment ❌

The `POST /wallet/topup` endpoint directly credits the wallet **without payment processing.** There is no:
- Integration with `payment-service` to collect funds
- Payment status verification (what if UPI payment fails after wallet credit?)
- PG callback handling
- Pending/confirming states for the wallet credit

**Current flow (BROKEN):**
```
Client → POST /wallet/topup {amountCents: 5000}
         → WalletService.credit() → Balance += 5000 (FREE MONEY!)
```

**Required flow:**
```
Client → POST /wallet/topup {amountCents: 5000}
         → Create pending transaction
         → Redirect to payment-service
         → payment-service callback → Confirm credit
```

### 5.2 Auto-Debit (Wallet + Card Split) ❌

No support for splitting a payment across wallet and payment method. The checkout service would need an API like:
```
POST /wallet/debit-or-split
{
    orderId: "...",
    totalCents: 50000,
    maxWalletDebitCents: 50000  // debit up to full amount from wallet
}
→ Response: { walletDebited: 20000, remainingCents: 30000 }
```

### 5.3 Cashback with Vesting Period ❌

The current implementation in `OrderEventConsumer` credits 2% cashback **instantly** on order delivery:
```java
long cashbackCents = orderTotalCents * 2 / 100;
walletService.credit(..., ReferenceType.CASHBACK, ...);
```

**Issues:**
- No vesting/lock-up period. Cashback should be held for 24-72 hours and only released if the order isn't returned.
- No tier-based cashback rates. PLATINUM users should get higher cashback.
- No cashback cap per order or per day.
- Cashback is awarded even if the order was paid fully via wallet (double-dipping).

### 5.4 Referral Fraud Prevention ❌

See Section 3.5. Missing: device fingerprinting, phone verification, IP velocity checks, account age requirements.

### 5.5 Wallet Limits (KYC/RBI Compliance) 🔴 CRITICAL

For **India (INR)**, RBI's Prepaid Payment Instrument (PPI) guidelines require:
- **Minimum KYC wallet:** Max balance ₹10,000, max monthly load ₹10,000
- **Full KYC wallet:** Max balance ₹2,00,000
- No current balance limit enforcement in the codebase
- No `kyc_status` field on the wallet or user
- The DB `CHECK (balance_cents >= 0)` only prevents negative balances — no upper bound

**This is a regulatory blocker for production launch in India.**

### 5.6 Gift Cards ❌

No gift card or prepaid card support. Would require:
- `gift_cards` table (code, balance, expiry, sender, recipient)
- Activation/redemption flow
- Balance transfer to wallet

### 5.7 Points-to-Currency Conversion ❌

`LoyaltyService.redeemPoints()` deducts points but **never credits the wallet.** There's no conversion rate or wallet credit step. Points redemption currently has no monetary value.

---

## 6. Q-Commerce Competitor Comparison

| Feature | Instacommerce (Current) | Blinkit | Zepto | Instacart |
|---------|------------------------|---------|-------|-----------|
| **Digital Wallet** | ✅ Basic credit/debit | ✅ Full wallet with top-up via UPI/cards | ✅ Zepto Cash with instant loads | ✅ Instacart Cash |
| **Cashback Engine** | ⚠️ Instant 2% (no vesting) | ✅ Tiered cashback with 48h vesting | ✅ Instant cashback with caps | ✅ Retailer-funded offers |
| **Loyalty Tiers** | ✅ Bronze → Platinum | ✅ Tiers with free delivery perks | ⚠️ Points but no tiers | ✅ Instacart+ membership |
| **Free Delivery (tier perk)** | ❌ Not implemented | ✅ Gold tier gets free delivery | ✅ Zepto Pass | ✅ Instacart+ |
| **Referral Credits** | ✅ ₹50 both parties | ✅ ₹100 both parties | ✅ ₹75 + leaderboard | ✅ $10 credits |
| **Referral Fraud Prevention** | ⚠️ Self-referral only | ✅ Phone OTP + device ID | ✅ Device fingerprint | ✅ Full verification |
| **Wallet Limits (KYC)** | ❌ None | ✅ RBI-compliant | ✅ RBI-compliant | N/A (US) |
| **Gift Cards** | ❌ None | ✅ Blinkit Gift Cards | ❌ None | ✅ eGift Cards |
| **Auto-Debit (wallet first)** | ❌ None | ✅ Default wallet first | ✅ Auto-apply wallet | ✅ Credits auto-applied |
| **Points Expiry** | ✅ 12-month (buggy job) | ✅ 6-month with notification | ✅ 3-month | ✅ Per-promotion expiry |
| **Subscription/Membership** | ❌ None | ✅ Blinkit Pass | ✅ Zepto Pass | ✅ Instacart+ |
| **Cashback Vesting** | ❌ None | ✅ 48h lock-in | ✅ Instant (< ₹50) | ✅ Applied next order |

**Key Gaps vs Competition:**
1. **Wallet limits / KYC** — regulatory blocker
2. **Cashback vesting** — financial risk (instant cashback can be gamed with returns)
3. **Subscription/membership model** — major revenue driver for competitors
4. **Auto-debit on checkout** — critical for wallet adoption
5. **Points → wallet credit conversion** — points have no monetary value currently

---

## 7. Security Review

| Area | Status | Notes |
|------|--------|-------|
| JWT RSA verification | ✅ | Asymmetric keys, issuer validation, expiry check |
| Stateless auth | ✅ | No sessions, `SessionCreationPolicy.STATELESS` |
| CSRF disabled | ✅ | Correct for stateless APIs |
| CORS configured | ✅ | Configurable origins, credentials allowed |
| Actuator endpoints | ✅ | Only health/info/prometheus/metrics exposed |
| Secret management | ✅ | GCP Secret Manager for DB passwords and JWT keys |
| User isolation | ⚠️ | `auth.getName()` extracts user ID from JWT — no admin override for other users |
| Rate limiting | ❌ | No rate limiting on credit/debit/referral endpoints |
| Input validation | ✅ | `@Valid` on all request DTOs, `@Min` constraints |
| SQL injection | ✅ | All queries via JPA/Spring Data — parameterized |
| Admin endpoints | ❌ | No admin APIs for manual credit/debit/reconciliation |

**⚠️ Missing Rate Limiting:** Without rate limits, an authenticated user can send thousands of debit/credit requests per second. The pessimistic lock serializes them, but each holds a DB connection from the pool (max 20). A single malicious user can exhaust the connection pool.

---

## 8. Infrastructure & Observability

| Area | Status | Notes |
|------|--------|-------|
| Distributed tracing | ✅ | OpenTelemetry via Micrometer bridge |
| Metrics | ✅ | OTLP + Prometheus endpoint |
| Structured logging | ✅ | Logstash encoder for JSON logs |
| Trace ID in errors | ✅ | `TraceIdProvider` extracts from B3/W3C headers |
| Health checks | ✅ | Liveness + readiness with DB health |
| Graceful shutdown | ✅ | 30s timeout |
| Connection pool | ✅ | HikariCP, max 20 connections |
| JVM tuning | ✅ | ZGC, 75% RAM, `urandom` for crypto |
| Flyway migrations | ✅ | `ddl-auto: validate` — schema changes only via migration |
| Cache | ✅ | Caffeine with 60s TTL, 10K max entries |
| Open-in-view | ✅ | Disabled (`open-in-view: false`) — no lazy loading leaks |
| Kafka consumer | ⚠️ | No DLQ, no retry topic. Failed messages are logged and swallowed |
| Testcontainers | ✅ | PostgreSQL testcontainer in test deps |

**⚠️ Kafka Consumer Error Handling:**
```java
catch (Exception ex) {
    log.error("Failed to process payment event: {}", message, ex);
}
```
Failed Kafka messages are silently dropped. A `PaymentRefunded` event that fails processing means the customer never gets their refund. This needs:
- Dead Letter Topic (DLT) for failed messages
- Retry with exponential backoff (Spring Kafka `@RetryableTopic`)
- Alerting on DLT messages

**⚠️ Cache Staleness:**
`walletBalance` cache has 60s TTL. After a debit, the cache is evicted via `@CacheEvict`. But if another service (Kafka consumer) credits the wallet, the cache for that user's balance is stale for up to 60 seconds. The `@CacheEvict` is only on `WalletService.credit()` and `.debit()`, which use the `userId` key — so Kafka-triggered credits DO evict the cache. ✅ This is correct.

However, if there are multiple service instances, Caffeine is a local cache — each instance has its own cache. After a credit on instance A, instance B still serves stale balance for 60 seconds. Consider Redis for distributed caching at this scale.

---

## 9. Critical Bugs & Risks

### P0 — Must Fix Before Production

| # | Bug | File | Impact | Fix |
|---|-----|------|--------|-----|
| 1 | **Idempotency TOCTOU race** → unique index violation returns 500 instead of 409 | `WalletService.java:103-109` | Duplicate credits on retry look like errors | Catch `DataIntegrityViolationException`, map to `DuplicateTransactionException` |
| 2 | **Points expiry job loads ALL accounts** into memory | `PointsExpiryJob.java:43` | OOM at 20M accounts, multi-hour transaction | Batch with pagination, per-account transactions |
| 3 | **Top-up has no payment integration** — free money | `WalletController.java:37-48` | Financial loss | Integrate with payment-service |
| 4 | **No wallet balance limits** — RBI non-compliance | Missing entirely | Regulatory risk | Add KYC tiers with balance/load limits |
| 5 | **Expiry job referenceId doesn't match the anti-dup query** | `PointsExpiryJob.java:59` vs `LoyaltyTransactionRepository.java:16` | Points can be double-expired | Use EARN transaction ID as expiry referenceId |

### P1 — Should Fix Before Scale

| # | Bug | File | Impact | Fix |
|---|-----|------|--------|-----|
| 6 | **Loyalty earn not idempotent** — Kafka replays double-award | `LoyaltyService.java:45-70` | Financial loss | Add unique constraint on `(reference_type, reference_id)` |
| 7 | **Referral code has no pessimistic lock** — can exceed maxUses | `ReferralService.java:46` | Minor financial loss | Add `findByCodeForUpdate()` |
| 8 | **Kafka consumers swallow errors** — refunds/cashback silently lost | `PaymentEventConsumer.java:33`, `OrderEventConsumer.java:38` | Customers missing refunds | Add DLT + retry |
| 9 | **Points redemption has no wallet credit** — points have no monetary value | `LoyaltyService.java:73-97` | Feature gap | Add conversion rate + wallet credit |
| 10 | **Top-up referenceId is random UUID** — not client-idempotent | `WalletController.java:45` | Double charges on retry | Accept idempotency key from client |
| 11 | **No double-entry bookkeeping** | Missing entirely | Can't produce financial statements | Add ledger_entries table |

---

## 10. Prioritized Recommendations

### Phase 1: Financial Safety (Weeks 1-2)
1. **Fix idempotency TOCTOU** — catch `DataIntegrityViolationException` and map to 409
2. **Add idempotency key to top-up** — accept from client, use as `referenceId`
3. **Fix points expiry job** — batch processing with per-account transactions
4. **Fix expiry referenceId** to reference original EARN transaction ID
5. **Add loyalty earn idempotency** — unique index on `(reference_type, reference_id)`
6. **Add Kafka DLT** — `@RetryableTopic` with 3 retries + dead letter

### Phase 2: Regulatory Compliance (Weeks 3-4)
7. **KYC-based wallet limits** — `kyc_status` enum, balance/load caps per tier
8. **Payment integration for top-up** — redirect to payment-service, confirm on callback
9. **Transaction audit enrichment** — add `actor_id`, `ip_address`, `user_agent` columns
10. **Rate limiting** — per-user rate limits on financial endpoints (Resilience4j or Bucket4j)

### Phase 3: Feature Parity (Weeks 5-6)
11. **Points-to-wallet conversion** — define conversion rate, credit wallet on redemption
12. **Cashback vesting** — `cashback_pending` table with 48h release schedule
13. **Wallet+card split (auto-debit)** — `tryDebit` endpoint for checkout
14. **Tier-based benefits** — earn rate multiplier, free delivery, priority support
15. **Referral lock** — `findByCodeForUpdate()` + velocity checks

### Phase 4: Competitive Features (Weeks 7-8)
16. **Double-entry ledger** — proper bookkeeping for financial reporting
17. **Daily reconciliation job** — verify balance = sum(transactions)
18. **Subscription/membership model** — Instacommerce+ pass
19. **Gift cards** — prepaid cards with activation and wallet transfer
20. **Distributed caching** — migrate from Caffeine to Redis for multi-instance consistency
21. **Cursor-based pagination** — for high-volume transaction history

---

*End of review. Total files analyzed: 53 (all Java sources, SQL migrations, YAML config, Dockerfile, build.gradle.kts).*
