# Payment Service — Deep Architecture Review

**Service**: `services/payment-service/`
**Stack**: Java 21, Spring Boot, PostgreSQL, Stripe SDK 24.18, Flyway, ShedLock
**Reviewer Perspective**: Senior Payments Architect (PCI-DSS, 20M+ user Q-Commerce)
**Date**: 2025-07-02

---

## Executive Summary

The payment service implements a **well-structured Authorize → Capture → Void/Refund lifecycle** with proper state machine transitions, an outbox pattern for event publishing, double-entry ledger bookkeeping, and webhook idempotency. However, it is **Stripe-only** with **no support for India-dominant payment methods** (UPI, wallets, net banking, COD), **no PSP failover**, **no partial capture**, **no split payments**, and **no settlement/payout system**. For an Indian Q-commerce platform at 20M+ scale, these are **launch-blocking gaps**.

### Scorecard

| Dimension | Score | Notes |
|---|---|---|
| Payment Lifecycle | ★★★★☆ | Solid state machine, missing partial capture |
| PSP Integration | ★★☆☆☆ | Stripe-only, no Indian payment rails |
| Ledger & Reconciliation | ★★★☆☆ | Double-entry present, no reconciliation job |
| Refund Flow | ★★★★☆ | Partial + multiple refunds, race protection |
| Webhook Processing | ★★★★☆ | Idempotent, signature verified, retry on OL conflict |
| PCI-DSS Compliance | ★★★★★ | No card data touches the service |
| Security | ★★★★☆ | JWT + RBAC + Secret Manager, minor gaps |
| Performance / SLA | ★★★☆☆ | No PSP timeout config, small pool for scale |
| Missing Features | ★★☆☆☆ | No UPI, wallets, COD, multi-PSP, payouts |

**Overall: 68/100 — Solid foundation, not production-ready for Indian Q-commerce.**

---

## 1. Payment Lifecycle

### 1.1 State Machine

The `PaymentStatus` enum defines 9 states:

```
AUTHORIZE_PENDING → AUTHORIZED → CAPTURE_PENDING → CAPTURED → PARTIALLY_REFUNDED → REFUNDED
                  ↘ FAILED      ↘ (revert) AUTHORIZED
                                 → VOID_PENDING → VOIDED
                                   ↘ (revert) AUTHORIZED
```

**What's done well:**
- **Pending states** (`AUTHORIZE_PENDING`, `CAPTURE_PENDING`, `VOID_PENDING`) — The service writes a pending state to the DB *before* calling the PSP, then updates to the final state *after* the PSP responds. This is the correct two-phase pattern that prevents the "PSP charged but DB not updated" scenario.
- **`REQUIRES_NEW` propagation** on every `PaymentTransactionHelper` method — each DB state change is its own transaction, so PSP calls happen outside any open transaction. This avoids long-held DB connections during PSP I/O.
- **Optimistic locking** via `@Version` on the `Payment` entity — prevents concurrent mutations from stomping each other.
- **Idempotency** via `idempotency_key` with a UNIQUE constraint + application-level check before PSP call.

**Issues found:**

#### ❌ BUG: No Partial Capture Support
```java
// PaymentTransactionHelper.java:115
payment.setCapturedCents(payment.getAmountCents()); // Always captures full amount
```
The Stripe gateway *does* pass `amountCents` to the capture call, but the service always sets `capturedCents = amountCents`. In Q-commerce, partial capture is essential when item substitution or out-of-stock reduces order value. The capture endpoint accepts no amount parameter — it's always full capture.

**Fix**: Add an `amountCents` parameter to the capture endpoint. Validate `0 < amountCents <= amountCents`. Set `capturedCents` to the requested amount.

#### ❌ BUG: Refund Allows on REFUNDED Status
```java
// RefundTransactionHelper.java:46-49
if (payment.getStatus() != PaymentStatus.CAPTURED
    && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED
    && payment.getStatus() != PaymentStatus.REFUNDED) {  // ← allows REFUNDED
    throw new PaymentInvalidStateException(...)
}
```
A payment in `REFUNDED` state (fully refunded) can still enter the refund flow. The `available` check (`capturedCents - refundedCents`) would catch it if accurate, but if the webhook handler set `REFUNDED` status via a Stripe event while `refundedCents` hasn't been perfectly synced, a race window exists. Should explicitly reject `REFUNDED` status.

#### ⚠️ RISK: Pending State Orphan Recovery Missing
If the service crashes between writing `AUTHORIZE_PENDING` and receiving the PSP response, the payment is stuck in `AUTHORIZE_PENDING` forever. There is **no scheduled job** to reconcile pending states with the PSP. Need a `StalePendingRecoveryJob` that:
1. Finds payments in `*_PENDING` older than N minutes
2. Queries the PSP for actual status
3. Updates accordingly or marks FAILED

#### ⚠️ RISK: Void Only Allowed From AUTHORIZED
```java
// PaymentTransactionHelper.java:151
if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
    throw new PaymentInvalidStateException(...)
}
```
Cannot void a payment that is in `AUTHORIZE_PENDING` (PSP may have authorized it). Also cannot void from `CAPTURE_PENDING` if the capture hasn't actually gone through yet. This is correct for Stripe (you can't cancel a PI that's being captured), but the service should handle edge cases with a reconciliation check.

---

## 2. PSP Integration

### 2.1 Architecture

```
PaymentGateway (interface)
  ├── StripePaymentGateway (@Profile("!test"))
  └── MockPaymentGateway  (@Profile("test"))
```

**What's done well:**
- **Clean abstraction** via `PaymentGateway` interface with `authorize`, `capture`, `voidAuth`, `refund`.
- **Idempotency keys** passed to Stripe on both authorize and refund calls.
- **`@Profile` switching** for test vs production.
- **Stripe SDK idempotency** via `RequestOptions.setIdempotencyKey()`.

**Issues found:**

#### 🚨 CRITICAL: Stripe-Only — No Indian Payment Methods
For an Indian Q-commerce platform with 20M+ users, **UPI accounts for 60-70% of digital transactions**. The service has:
- ❌ **No UPI** (UPI Collect, UPI Intent, UPI QR, UPI Autopay/Mandate)
- ❌ **No wallets** (Paytm, PhonePe, Amazon Pay)
- ❌ **No net banking**
- ❌ **No COD (Cash on Delivery)** — still 20-30% of e-commerce orders
- ❌ **No EMI/BNPL** (Simpl, LazyPay)

**Comparison with competitors:**
| Feature | Blinkit | Zepto | Instacart | **InstaCommerce** |
|---|---|---|---|---|
| UPI | ✅ Razorpay | ✅ Pre-authorized | N/A (US) | ❌ |
| Cards | ✅ | ✅ | ✅ Stripe | ✅ Stripe only |
| Wallets | ✅ | ✅ Auto-debit | N/A | ❌ |
| COD | ✅ | ✅ | N/A | ❌ |
| Net Banking | ✅ | ✅ | N/A | ❌ |
| PSP Failover | ✅ Multi-PSP | ✅ | ✅ Stripe+Braintree | ❌ Single PSP |
| Saved Methods | ✅ | ✅ | ✅ | ❌ |

#### ❌ No PSP Failover / Multi-PSP Routing
Single-PSP architecture means a Stripe outage = **100% payment downtime**. At 20M+ users with likely 200K+ daily orders, even 5 minutes of Stripe downtime = significant revenue loss.

**Required**: Strategy pattern with PSP routing:
```java
public interface PaymentGatewayRouter {
    PaymentGateway route(PaymentMethod method, String currency, long amountCents);
}
```
- Primary: Razorpay (for UPI, cards, net banking, wallets — Indian rails)
- Failover: Stripe (international cards)
- Future: Juspay (aggregator of aggregators)

#### ⚠️ No Stripe Timeout Configuration
The `StripePaymentGateway` uses Stripe SDK defaults. Stripe's default timeout is **80 seconds**. For a Q-commerce checkout where a user expects <3s response, this is unacceptable.

```java
// Missing: Stripe.setConnectTimeout(5000);
// Missing: Stripe.setReadTimeout(10000);
```

Should configure via `RequestOptions`:
```java
RequestOptions.builder()
    .setApiKey(apiKey)
    .setConnectTimeout(5000)
    .setReadTimeout(15000)
    .build();
```

#### ⚠️ Generic Error Messages
```java
// StripePaymentGateway.java:51
throw new PaymentGatewayException("Payment processing failed"); // Same message everywhere
```
All StripeException catches return the same generic message. Should at least include the Stripe error code (`ex.getCode()`) for debugging (without exposing raw Stripe messages to the client).

---

## 3. Double-Entry Ledger

### 3.1 Ledger Design

**Account structure** (discovered from code):

| Event | Debit Account | Credit Account |
|---|---|---|
| Authorization | `customer_receivable` | `authorization_hold` |
| Capture | `authorization_hold` | `merchant_payable` |
| Void | `authorization_hold` | `customer_receivable` |
| Refund | `merchant_payable` | `customer_receivable` |

**What's done well:**
- **Proper double-entry**: Every financial event creates paired DEBIT + CREDIT entries via `recordDoubleEntry()`.
- **`Propagation.MANDATORY`**: Ledger entries can only be created within an existing transaction — prevents orphaned entries.
- **Correct flow direction**:
  - Auth: Money moves from customer → hold (reserve)
  - Capture: Hold → merchant payable (money earned)
  - Void: Hold → back to customer (release)
  - Refund: Merchant payable → back to customer (return)

**Issues found:**

#### ❌ No Balance Consistency Check
There is **no query or scheduled job** to verify that `SUM(DEBIT) = SUM(CREDIT)` per payment or globally. A ledger without balance verification is a ledger you can't trust.

**Required**:
```sql
-- Per-payment balance check
SELECT payment_id,
       SUM(CASE WHEN entry_type = 'DEBIT' THEN amount_cents ELSE 0 END) as total_debit,
       SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_cents ELSE 0 END) as total_credit
FROM ledger_entries
GROUP BY payment_id
HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount_cents ELSE 0 END) <>
       SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_cents ELSE 0 END);
```

#### ❌ No Reconciliation with PSP
No daily/hourly job to compare internal ledger totals with Stripe's settlement reports. At scale, drift between internal records and PSP records is inevitable (webhook failures, partial processing, manual adjustments on Stripe dashboard).

#### ⚠️ Missing Account Table
Accounts (`customer_receivable`, `authorization_hold`, `merchant_payable`) are hardcoded strings, not backed by an `accounts` table with types (ASSET, LIABILITY, REVENUE, EXPENSE) and running balances. This limits:
- Real-time balance queries
- Chart of accounts management
- Financial reporting

#### ⚠️ No Ledger Entries for Webhook-Driven State Changes
The `WebhookEventHandler` updates payment status (`handleCaptured`, `handleVoided`, `handleRefunded`) but **does not create ledger entries**. This means:
- If capture happens asynchronously via webhook (not API), no ledger record
- Ledger and payment status can drift

---

## 4. Refund Flow

### 4.1 Current Implementation

**What's done well:**
- **Pessimistic locking**: `findByIdForUpdate()` with `PESSIMISTIC_WRITE` on the payment row during refund — prevents concurrent refunds from exceeding captured amount.
- **Partial refund**: Correctly supports partial refunds and tracks `refundedCents` on the payment.
- **Multiple partial refunds**: Allowed — each refund is a separate row in `refunds` table.
- **Full vs partial detection**: `refundedCents >= capturedCents` → REFUNDED, else PARTIALLY_REFUNDED.
- **Idempotency**: Refund idempotency key checked before processing.
- **DB constraint**: `CHECK (refunded_cents <= captured_cents)` in migration as safety net.
- **Two-phase pattern**: PENDING refund saved → PSP called → COMPLETED/FAILED updated.

**Issues found:**

#### ⚠️ Refund Race Window Between Phases
Between `savePendingRefund()` (which locks payment, checks available, saves PENDING refund) and `completeRefund()` (which updates `refundedCents`), the pessimistic lock is released (different transactions with `REQUIRES_NEW`). If two refund requests arrive near-simultaneously:
1. Request A: locks payment, checks available=1000, creates PENDING refund for 600, releases lock
2. Request B: locks payment, checks available=1000 (A's refundedCents not yet updated!), creates PENDING refund for 600, releases lock
3. Request A: calls PSP, succeeds, updates refundedCents to 600
4. Request B: calls PSP, succeeds, updates refundedCents to 1200 → **exceeds captured!**

The DB constraint `chk_refund_le_captured` would catch this at the DB level, but the PSP has already processed both refunds. This is a **money leak**.

**Fix**: Either:
- Include PENDING refund amounts in the availability check: `available = capturedCents - refundedCents - SUM(pending refunds)`
- Or use a single serialized refund queue per payment

#### ⚠️ No Refund Expiry for PENDING State
If `markRefundFailed()` is never called (service crash between PSP call and status update), the refund stays PENDING forever. The PSP may have actually processed it. Need a recovery job similar to pending payment recovery.

---

## 5. Webhook Processing

### 5.1 Current Implementation

**What's done well:**
- **Signature verification**: Custom `WebhookSignatureVerifier` with HMAC-SHA256, timestamp tolerance (300s default), constant-time comparison (`secureEquals`).
- **Idempotency**: `processed_webhook_events` table with PK constraint. Double-checked — first via `existsById()`, then via `DataIntegrityViolationException` catch for concurrent duplicates.
- **Optimistic lock retry**: Up to 3 retries with linear backoff (100ms, 200ms, 300ms) for `ObjectOptimisticLockingFailureException`.
- **Out-of-order handling**: Status checks (e.g., `if payment.getStatus() == CAPTURED return`) prevent regressive state changes.
- **Webhook endpoint excluded from JWT auth** in `SecurityConfig` and `JwtAuthenticationFilter.shouldNotFilter()`.

**Issues found:**

#### ⚠️ RISK: Webhook Returns 200 on Processing Failure
```java
// WebhookController.java:36-38
} catch (Exception ex) {
    log.error("Webhook processing failed", ex);
    return ResponseEntity.internalServerError().body("Processing failed");
}
```
Returns 500, which is correct for Stripe (Stripe will retry). But if the webhook handler throws after the dedup record is saved (inside `processEvent`'s transaction), the dedup record is committed, and when Stripe retries, the event is skipped. This is a **permanent data loss** scenario.

**Fix**: The `processEvent` method is `@Transactional` — if it throws, the transaction rolls back including the dedup record. But the retry logic in `handle()` catches `ObjectOptimisticLockingFailureException` and re-throws after max retries, which correctly triggers Stripe retry. The risk is in other exception types that might be thrown after partial commit.

#### ⚠️ No Webhook Event Queuing
Webhooks are processed synchronously in the HTTP request thread. Under load (e.g., a batch refund touching 10K payments), webhook processing could overwhelm the service. Should queue webhook events and process asynchronously.

#### ⚠️ `charge.refunded` Handling Uses Max, Not Sum
```java
// WebhookEventHandler.java:145
payment.setRefundedCents(Math.max(payment.getRefundedCents(), refunded));
```
Uses `Math.max` comparing the current DB value with the Stripe `amount_refunded` field. This is actually correct for Stripe since `amount_refunded` is cumulative, but it can conflict with the API-driven refund flow which uses summation. If a refund is processed via API and a webhook arrives, the values could disagree.

---

## 6. Payment Retry & Timeout

#### 🚨 CRITICAL: No PSP Timeout Configuration
No connect/read timeout set on Stripe SDK calls. Default Stripe timeout is **80 seconds**. During a Stripe outage, every payment request will hold a thread and a DB connection for 80 seconds.

With HikariCP `maximum-pool-size: 20` and `connection-timeout: 5000`, after just **20 concurrent payment requests** during a Stripe slowdown, the connection pool is exhausted, and the entire service becomes unresponsive — including health checks, webhooks, and GET endpoints.

**Impact at scale**: With 200K+ daily orders (20M users), peak load could be 50-100 concurrent payment requests. A 30-second Stripe delay would cascade into full service unavailability.

#### ❌ No Auto-Retry on PSP Timeout
If the PSP call times out, the payment is left in `*_PENDING` state and the exception bubbles up as `PaymentGatewayException`. The caller gets a 500 error. There is:
- No automatic retry with exponential backoff
- No circuit breaker (e.g., Resilience4j)
- No reconciliation job to resolve `*_PENDING` states

**This is the correct behavior for timeouts** — auto-retry on payment authorize/capture could double-charge. But there must be a manual or scheduled reconciliation path to resolve stuck payments.

---

## 7. PCI-DSS Compliance

### 7.1 Card Data Handling

**Rating: ★★★★★ — Excellent**

The service **never touches raw card data**. Analysis of all code:

| Check | Status | Evidence |
|---|---|---|
| No PAN storage | ✅ | No card number field in any entity, DTO, or migration |
| No CVV handling | ✅ | Not present anywhere in codebase |
| No card expiry storage | ✅ | Not present |
| Token-based | ✅ | `paymentMethod` field contains Stripe `pm_*` tokens |
| Stripe tokenization | ✅ | Client sends Stripe token, server only sees `pm_*` IDs |

The `AuthorizeRequest.paymentMethod` field accepts a Stripe payment method ID (e.g., `pm_1234`), which is a token — not a card number. The service is **SAQ-A eligible** (PCI scope minimized to Stripe's infrastructure).

### 7.2 Log Safety

**Checked for card data in logs:**
- `logback` configured with `logstash-logback-encoder` (structured JSON logging)
- No `log.info/debug/error` call logs the `paymentMethod` field
- `PaymentResponse` has `@JsonIgnore` on `pspReference` — not exposed to API consumers
- Generic error messages ("Payment processing failed") prevent Stripe error details from leaking

**One concern**: The `metadata` JSONB field on Payment entity could potentially contain sensitive data if the caller includes it. No sanitization or validation of metadata content.

### 7.3 Encryption at Rest

- **DB password**: Loaded from GCP Secret Manager (`sm://db-password-payment`)
- **Stripe API key**: Loaded from GCP Secret Manager (`sm://stripe-api-key`)
- **Webhook secret**: Loaded from GCP Secret Manager (`sm://stripe-webhook-secret`)
- **JWT public key**: Loaded from GCP Secret Manager (`sm://jwt-rsa-public-key`)

**Gap**: No database-level encryption-at-rest configuration visible (depends on GCP CloudSQL settings, not application-level). No column-level encryption for the `psp_reference` field, which is a Stripe PaymentIntent ID — low sensitivity but could aid in reconnaissance.

---

## 8. Tokenization

### 8.1 Current State

- ✅ Stripe payment method tokens (`pm_*`) used for authorization
- ✅ No raw card data in the codebase
- ❌ **No saved payment methods** (card vault)
- ❌ **No Stripe Customer objects** created or linked to users
- ❌ **No recurring payment support**

For Q-commerce repeat purchases (users order 10-20x/month), **one-tap payment with saved methods is critical** for conversion. Blinkit and Zepto both offer saved UPI and card auto-debit.

---

## 9. Access Control & Security

### 9.1 Authentication

- ✅ **JWT-based** with RSA public key verification
- ✅ **Stateless sessions** (no session cookies)
- ✅ **Issuer validation** against configured issuer
- ✅ **Role extraction** from JWT claims (`roles` field)

### 9.2 Authorization

```java
// SecurityConfig.java:53-55
.requestMatchers("/actuator/**", "/error", "/payments/webhook").permitAll()
.requestMatchers(HttpMethod.POST, "/payments/*/refund").hasRole("ADMIN")
.anyRequest().authenticated()
```

| Endpoint | Access | Assessment |
|---|---|---|
| `POST /payments/authorize` | Authenticated | ✅ Correct |
| `POST /payments/{id}/capture` | Authenticated | ⚠️ Should be ADMIN or SERVICE role |
| `POST /payments/{id}/void` | Authenticated | ⚠️ Should be ADMIN or SERVICE role |
| `POST /payments/{id}/refund` | ADMIN only | ✅ Correct |
| `GET /payments/{id}` | Authenticated | ⚠️ No ownership check |
| `POST /payments/webhook` | Public | ✅ Correct (signature-verified) |
| `/actuator/**` | Public | ⚠️ Exposes health, metrics, Prometheus |

**Issues found:**

#### ⚠️ No Ownership Validation
Any authenticated user can call `GET /payments/{id}` or `POST /payments/{id}/capture` with any payment ID. There is no check that `payment.orderId` belongs to the requesting user. At 20M users, this is an **IDOR vulnerability** — a user could enumerate payment IDs and view other users' payment details.

#### ⚠️ Actuator Endpoints Publicly Accessible
`/actuator/prometheus` and `/actuator/metrics` expose internal performance metrics. Should be restricted to internal network or require authentication.

#### ⚠️ CORS Allows Credentials
```java
configuration.setAllowCredentials(true);
```
Combined with a configurable `allowedOrigins`, CORS misconfiguration could allow cross-origin credential theft. In production, origins must be strictly locked down.

---

## 10. SLA & Performance

### 10.1 Connection Pooling

```yaml
hikari:
  maximum-pool-size: 20
  minimum-idle: 5
  connection-timeout: 5000
  max-lifetime: 1800000
```

**Assessment for 20M+ users:**

- **Pool size 20 is too small**. With the two-phase pattern (each payment operation = 2-3 separate transactions via `REQUIRES_NEW`), a single payment authorize consumes 2 connections sequentially. Under 50 concurrent payments, the pool would saturate.
- **Recommended**: `maximum-pool-size: 50-80` for a payment service at this scale, with monitoring.
- `connection-timeout: 5000ms` (5s) is reasonable.
- `max-lifetime: 1800000ms` (30min) is correct for CloudSQL.

### 10.2 Database Indexes

```sql
-- V1: payments
CREATE INDEX idx_payments_order ON payments (order_id);
CREATE INDEX idx_payments_psp ON payments (psp_reference);
-- UNIQUE on idempotency_key (implicit index)

-- V2: refunds
CREATE INDEX idx_refunds_payment ON refunds (payment_id);
-- UNIQUE on idempotency_key (implicit index)

-- V3: ledger
CREATE INDEX idx_ledger_payment ON ledger_entries (payment_id);

-- V4: outbox
CREATE INDEX idx_outbox_unsent ON outbox_events (sent) WHERE sent = false;
```

**Missing indexes:**
- `ledger_entries(account, entry_type)` — needed for account balance queries
- `ledger_entries(created_at)` — needed for time-range reconciliation
- `refunds(status)` — needed for pending refund recovery
- `payments(status, updated_at)` — needed for stale pending recovery

### 10.3 Graceful Shutdown

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

✅ Correct. In-flight payment requests get 30s to complete before forced termination.

---

## 11. Outbox Pattern

### 11.1 Implementation

```java
@Transactional(propagation = Propagation.MANDATORY)
public void publish(String aggregateType, String aggregateId, String eventType, Object payload) {
    OutboxEvent outboxEvent = new OutboxEvent();
    // ... save to DB
}
```

**What's done well:**
- `Propagation.MANDATORY` ensures events are only written within the same transaction as the domain change — guarantees atomicity.
- Events published for: `PaymentAuthorized`, `PaymentCaptured`, `PaymentVoided`, `PaymentRefunded`.
- Cleanup job runs daily at 3:30 AM with 30-day retention.
- ShedLock prevents duplicate cleanup across instances.

**Issues found:**

#### 🚨 CRITICAL: No Outbox Relay/Poller
The outbox events are written to the database but there is **no component to read them and publish to a message broker** (Kafka, RabbitMQ, GCP Pub/Sub). The `sent` column exists but is never updated to `true`. The events are written and then deleted after 30 days.

**Impact**: Downstream services (order-service, notification-service) never receive payment events. This means no order status updates on payment capture, no refund notifications, no analytics events.

**Fix**: Implement a Debezium CDC connector or a polling relay job:
```java
@Scheduled(fixedDelay = 5000)
public void relay() {
    List<OutboxEvent> events = outboxEventRepository.findBySentFalse();
    for (OutboxEvent event : events) {
        kafkaTemplate.send(event.getEventType(), event.getPayload());
        event.setSent(true);
        outboxEventRepository.save(event);
    }
}
```

#### ⚠️ Outbox Cleanup Deletes Unsent Events
```java
@Query("DELETE FROM OutboxEvent e WHERE e.createdAt < :cutoff")
int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
```
This deletes **all** events older than 30 days, regardless of `sent` status. If the relay is not running, events are silently lost.

---

## 12. Audit Logging

### 12.1 Implementation

- **Audit events logged**: `PAYMENT_AUTHORIZED`, `PAYMENT_CAPTURED`, `REFUND_ISSUED`
- **Context captured**: userId, IP address (X-Forwarded-For aware), User-Agent, trace ID
- **Retention**: 730 days (2 years) — PCI-DSS requires 1 year retention, so this exceeds requirements ✅
- **Cleanup**: ShedLock-protected daily job

**Issues found:**

#### ⚠️ Missing Audit Events
Not logged:
- `PAYMENT_VOIDED` — the `completeVoided()` method does not call `auditLogService.log()`
- `PAYMENT_FAILED` — authorization/capture failures
- `REFUND_FAILED` — failed refund attempts
- Webhook events processed
- All `GET /payments/{id}` reads (PCI-DSS may require access logging)

#### ⚠️ Audit Log userId is Always Null
```java
auditLogService.log(null, "PAYMENT_AUTHORIZED", ...);  // null userId everywhere
```
The `resolveUserId(null)` fallback tries to extract from `SecurityContext`, but since `PaymentTransactionHelper` runs in `REQUIRES_NEW` transactions, the `SecurityContext` may or may not be propagated depending on thread context. Should explicitly pass the userId from the controller layer.

---

## 13. Missing Features for Q-Commerce

### 13.1 Priority P0 (Launch Blocking)

| Feature | Why Critical | Effort |
|---|---|---|
| **UPI Support** | 60-70% of Indian digital payments | High — requires Razorpay/Juspay integration |
| **COD** | 20-30% of orders, especially Tier 2/3 cities | Medium — no PSP needed, internal state machine |
| **Multi-PSP Router** | Single-PSP = single point of failure | High — strategy pattern + health monitoring |
| **Outbox Relay** | No downstream event delivery currently | Medium — CDC or poller implementation |
| **PSP Timeout Config** | Service unavailable during PSP slowdown | Low — Stripe SDK configuration |

### 13.2 Priority P1 (Growth Blocking)

| Feature | Why Critical | Effort |
|---|---|---|
| **Saved Payment Methods** | Repeat purchase conversion (10-20 orders/month/user) | Medium — Stripe Customer + SetupIntent |
| **Wallet Support** | Paytm/PhonePe/Amazon Pay | Medium — via Razorpay |
| **Partial Capture** | Item substitution, out-of-stock handling | Low — extend capture endpoint |
| **Stale Pending Recovery** | Orphaned pending payments | Medium — scheduled reconciliation job |
| **PSP Reconciliation** | Financial accuracy | High — daily settlement file comparison |

### 13.3 Priority P2 (Scale Features)

| Feature | Why Critical | Effort |
|---|---|---|
| **Split Payments** | Wallet + card, multiple methods per order | High — complex orchestration |
| **Pre-authorized UPI** | Zepto-style instant debit | High — UPI mandate integration |
| **Tip Handling** | Delayed capture with tip amount (rider tips) | Medium — post-capture adjustment |
| **Payout to Merchants/Riders** | Settlement system | Very High — separate service |
| **Payment Links** | COD-to-online conversion | Medium |
| **Subscription/Auto-debit** | Pass subscriptions, loyalty programs | High |
| **Circuit Breaker** | Resilience4j for PSP calls | Low |

---

## 14. Q-Commerce Competitor Comparison

### 14.1 Blinkit (Zomato)

- **PSP**: Razorpay (primary), backup PSPs for failover
- **Methods**: UPI, Cards, Wallets (Paytm, PhonePe), Net Banking, COD
- **Refunds**: Instant refund to source for prepaid; Zomato credits for fast resolution
- **Saved Methods**: Yes — saved UPI VPA, saved cards via Razorpay vault
- **Special**: Zomato Money (wallet) pre-loaded for faster checkout

### 14.2 Zepto

- **PSP**: Multiple (Razorpay + Juspay)
- **Methods**: Pre-authorized UPI (UPI Autopay mandate), Cards, Wallets
- **Refunds**: Instant to Zepto wallet, source refund within 5-7 days
- **Special**: UPI mandate for 1-tap ordering, auto-debit for subscriptions
- **Split**: Zepto Cash + Card/UPI split payment

### 14.3 Instacart (US reference)

- **PSP**: Stripe (primary) + Braintree (failover)
- **Methods**: Cards, Apple Pay, Google Pay, Instacart Credits
- **Capture**: Delayed capture (auth at checkout, capture at delivery with substitutions)
- **Special**: Tip handling (post-delivery tip adjustment), EBT/SNAP payments
- **Reconciliation**: Daily automated reconciliation with both PSPs

### 14.4 InstaCommerce (Current State)

- **PSP**: Stripe only, no failover
- **Methods**: Card only (via Stripe token)
- **Refunds**: Partial + full via API, no instant refund
- **Saved Methods**: None
- **Special**: None
- **Reconciliation**: None

---

## 15. Specific Code-Level Findings

### 15.1 Bugs

| # | Severity | Location | Description |
|---|---|---|---|
| B1 | High | `PaymentTransactionHelper:115` | Capture always sets `capturedCents = amountCents` — no partial capture |
| B2 | Medium | `RefundTransactionHelper:46-49` | Allows refund on already-REFUNDED payment (should reject) |
| B3 | Medium | `RefundTransactionHelper:42-66` → `69-114` | Race window between pending refund creation and refund completion allows double-refund at PSP |
| B4 | Medium | `WebhookEventHandler:116-126` | Webhook capture handler doesn't create ledger entries |
| B5 | Low | `PaymentTransactionHelper:160-175` | `completeVoided()` doesn't log audit event |

### 15.2 Security

| # | Severity | Location | Description |
|---|---|---|---|
| S1 | High | `SecurityConfig:53` | No ownership validation on payment GET/capture/void — IDOR |
| S2 | Medium | `SecurityConfig:53` | Capture/void endpoints only require authentication, not ADMIN |
| S3 | Medium | `SecurityConfig:53` | Actuator endpoints publicly accessible (metrics, prometheus) |
| S4 | Low | `StripePaymentGateway:51` | Generic error messages lose PSP error context for debugging |

### 15.3 Reliability

| # | Severity | Location | Description |
|---|---|---|---|
| R1 | Critical | Entire PSP layer | No PSP call timeout configuration |
| R2 | Critical | Missing | No stale `*_PENDING` state recovery job |
| R3 | Critical | `OutboxService` | Outbox events written but never relayed to message broker |
| R4 | High | `OutboxCleanupJob` | Cleanup deletes unsent events |
| R5 | Medium | `application.yml:17` | HikariCP pool size 20 too small for scale |

### 15.4 Data Integrity

| # | Severity | Location | Description |
|---|---|---|---|
| D1 | High | `LedgerService` | No balance verification (SUM debit = SUM credit) |
| D2 | High | Missing | No daily PSP reconciliation |
| D3 | Medium | `WebhookEventHandler` | Webhook state changes don't create ledger entries |
| D4 | Low | Missing | No ledger account table (accounts are magic strings) |

---

## 16. Recommended Action Plan

### Sprint 1 (Week 1-2): Critical Fixes
1. **Configure Stripe timeouts** (connect: 5s, read: 15s)
2. **Implement stale pending recovery job** (ShedLock, runs every 5 min)
3. **Implement outbox relay** (polling or CDC to message broker)
4. **Fix outbox cleanup** to skip unsent events
5. **Add ownership validation** on payment endpoints
6. **Increase HikariCP pool size** to 50

### Sprint 2 (Week 3-4): Indian Payment Rails
7. **Integrate Razorpay** as primary PSP for Indian methods
8. **Add UPI support** (Collect + Intent flows)
9. **Add COD support** (internal state machine, no PSP)
10. **Implement PSP router** with health-check-based failover

### Sprint 3 (Week 5-6): Financial Integrity
11. **Add partial capture** support
12. **Fix refund race condition** (include PENDING refunds in availability check)
13. **Add ledger balance verification job**
14. **Add webhook-driven ledger entries**
15. **Implement PSP reconciliation job** (daily Stripe settlement file comparison)

### Sprint 4 (Week 7-8): Growth Features
16. **Saved payment methods** (Stripe Customer + Razorpay tokens)
17. **Wallet support** via Razorpay
18. **Circuit breaker** (Resilience4j) on PSP calls
19. **Complete audit logging** (void events, failed events, access logging)

---

## Appendix A: File Inventory

| File | Lines | Purpose |
|---|---|---|
| `domain/model/Payment.java` | 195 | Core payment entity with optimistic locking |
| `domain/model/PaymentStatus.java` | 13 | 9-state enum including pending states |
| `domain/model/Refund.java` | 114 | Refund entity linked to payment |
| `domain/model/RefundStatus.java` | 7 | PENDING/COMPLETED/FAILED |
| `domain/model/LedgerEntry.java` | 124 | Double-entry ledger row |
| `domain/model/LedgerEntryType.java` | 6 | DEBIT/CREDIT |
| `domain/model/OutboxEvent.java` | 99 | Transactional outbox event |
| `domain/model/ProcessedWebhookEvent.java` | 42 | Webhook dedup record |
| `domain/model/AuditLog.java` | 127 | PCI audit trail |
| `service/PaymentService.java` | 132 | Payment orchestrator (authorize/capture/void) |
| `service/RefundService.java` | 73 | Refund orchestrator |
| `service/PaymentTransactionHelper.java` | 199 | Transactional boundaries for payments |
| `service/RefundTransactionHelper.java` | 131 | Transactional boundaries for refunds |
| `service/LedgerService.java` | 50 | Double-entry ledger recording |
| `service/OutboxService.java` | 38 | Outbox event publishing |
| `service/AuditLogService.java` | 81 | Audit trail with request context |
| `service/OutboxCleanupJob.java` | 34 | Scheduled outbox purge |
| `service/AuditLogCleanupJob.java` | 34 | Scheduled audit log purge |
| `gateway/PaymentGateway.java` | 11 | PSP abstraction interface |
| `gateway/StripePaymentGateway.java` | 121 | Stripe SDK integration |
| `gateway/MockPaymentGateway.java` | 29 | Test double |
| `gateway/Gateway*Result.java` | ~15 each | PSP response records |
| `webhook/WebhookSignatureVerifier.java` | 106 | HMAC-SHA256 signature verification |
| `webhook/WebhookEventHandler.java` | 182 | Idempotent webhook processor |
| `controller/PaymentController.java` | 43 | REST endpoints for payment lifecycle |
| `controller/RefundController.java` | 27 | REST endpoint for refunds |
| `controller/WebhookController.java` | 42 | Stripe webhook receiver |
| `config/SecurityConfig.java` | 62 | JWT + CORS + RBAC configuration |
| `config/PaymentProperties.java` | 33 | Typed configuration properties |
| `security/JwtAuthenticationFilter.java` | 79 | JWT token extraction and validation |
| `security/DefaultJwtService.java` | 54 | RSA JWT verification with role extraction |
| `security/JwtKeyLoader.java` | 52 | PEM/DER RSA public key loader |
| `exception/GlobalExceptionHandler.java` | 84 | Centralized error handling |
| `db/migration/V1-V8` | 8 files | Flyway migrations (payments, refunds, ledger, outbox, audit, webhooks, shedlock) |
| `application.yml` | 73 | Full configuration with Secret Manager |
| `Dockerfile` | 23 | Multi-stage build, non-root, ZGC, health check |
| `build.gradle.kts` | 36 | Dependencies |

## Appendix B: Dependency Analysis

| Dependency | Version | Purpose | Risk |
|---|---|---|---|
| `stripe-java` | 24.18.0 | PSP SDK | ⚠️ Check for updates, v26+ available |
| `spring-boot` | (managed) | Framework | Low |
| `flyway-core` | (managed) | DB migrations | Low |
| `shedlock` | 5.10.2 | Distributed job locking | Low |
| `jjwt` | 0.12.5 | JWT validation | Low |
| `spring-cloud-gcp-starter-secretmanager` | (managed) | Secret management | Low |
| `micrometer-tracing-bridge-otel` | (managed) | Distributed tracing | Low |
| `logstash-logback-encoder` | 7.4 | Structured logging | Low |
| `testcontainers` | 1.19.3 | Integration testing | Low (test only) |

---

*End of review. Total files analyzed: 63 (Java source, SQL migrations, YAML config, Dockerfile, build script).*
