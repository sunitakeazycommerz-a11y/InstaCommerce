# Transactional Core — Deep Implementation Guide

> **Iteration 3 · Services Tier**
> **Scope:** `checkout-orchestrator-service`, `order-service`, `payment-service`, `payment-webhook-service`, `reconciliation-engine`, `contracts/`
> **Date:** 2026-03-07
> **Classification:** Principal Engineering — Money-Path Safety

---

## Table of Contents

1. [Single Checkout Authority](#1-single-checkout-authority)
2. [Pricing Truth](#2-pricing-truth)
3. [Payment Idempotency](#3-payment-idempotency)
4. [Webhook Closure](#4-webhook-closure)
5. [Reconciliation](#5-reconciliation)
6. [Pending-State Recovery](#6-pending-state-recovery)
7. [Safer Rollout of Money-Path Changes](#7-safer-rollout-of-money-path-changes)
8. [Cross-Cutting Observability](#8-cross-cutting-observability)
9. [Contract Surface](#9-contract-surface)
10. [Migration Sequencing](#10-migration-sequencing)

---

## 1. Single Checkout Authority

### 1.1 Current State: Dual Saga Problem

**Critical finding:** Two independent Temporal checkout workflows exist and serve different callers from different task queues.

| Attribute | `checkout-orchestrator-service` | `order-service` |
|---|---|---|
| Task queue | `CHECKOUT_ORCHESTRATOR_TASK_QUEUE` | `CHECKOUT_TASK_QUEUE` |
| Workflow class | `CheckoutWorkflowImpl` (com.instacommerce.checkout) | `CheckoutWorkflowImpl` (com.instacommerce.order) |
| Workflow ID pattern | `"checkout-" + principal + "-" + idempotencyKey` | `"checkout-" + idempotencyKey` |
| Caller | BFF / mobile API via `/checkout` on port 8089 | order-service internal via `/checkout` on port 8085 |
| Pricing source | Calls `pricing-service` via `PricingActivityImpl` | **Computes inline from `item.unitPriceCents()`** |
| Idempotency | DB-backed `checkout_idempotency_keys` table (30-min TTL) | Temporal `WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE` |
| Payment compensation | `voidPayment` OR `refundPayment` depending on capture state | `voidPayment` only (always registered pre-capture) |
| Pricing lock | Yes — pricing snapshot from pricing-service | **No — trusts client-supplied price** |
| Delivery fee | Yes — included in `PricingResult` | No |
| Coupon applied | Yes — via pricing-service | No |

The two workflows are not redundant backups — they implement materially different business logic, and both are currently reachable. This is an **active correctness defect**: a caller hitting the order-service endpoint bypasses pricing validation and coupon application, and charges the user a client-supplied price.

### 1.2 Authoritative Flow: checkout-orchestrator-service

The orchestrator implements the correct saga:

```
POST /checkout (port 8089, JWT-authenticated)
  │
  ├─ DB idempotency check (checkout_idempotency_keys)
  │
  └─ Temporal: "checkout-{userId}-{idempotencyKey}"
       │
       ├── [1] CartActivity.validateCart(userId)
       │       → validates cart is non-empty, returns items + storeId
       │
       ├── [2] PricingActivity.calculatePrice(userId, items, storeId, coupon, addressId)
       │       → locked price snapshot: subtotal, discount, deliveryFee, total (all in cents)
       │
       ├── [3] InventoryActivity.reserveStock(items)            ←── compensation: releaseStock(reservationId)
       │       → reserves per-item across store SKUs
       │
       ├── [4] PaymentActivity.authorizePayment(totalCents, paymentMethodId, idempotencyKey)
       │       → key = workflowId + "-payment" + "-" + activityId
       │
       ├── [5] OrderActivity.createOrder(OrderCreateRequest)     ←── compensation: cancelOrder(orderId)
       │       → writes order in PENDING status, idempotent by key
       │
       ├── [6] InventoryActivity.confirmStock(reservationId)
       │       PaymentActivity.capturePayment(paymentId, captureKey)
       │
       └── [7] CartActivity.clearCart(userId)   [best-effort, no failure]
```

**Step 5 is the handoff:** the `OrderCreateRequest` carries `reservationId`, `paymentId`, `pricing snapshot`, and `deliveryAddressId`. The order-service receives this through `OrderActivity.createOrder` and writes the order with status `PENDING`.

### 1.3 Required Fix: Remove Checkout from order-service

The order-service `CheckoutController`, `CheckoutWorkflow`, and its five activity implementations must be **deprecated and removed**. The migration path:

**Phase 1 (1 sprint):** Add feature flag `order.checkout.direct-saga.enabled`. Default false in prod, true in dev. Route old callers to the orchestrator's endpoint via API gateway rewrite.

**Phase 2 (1 sprint):** Verify zero traffic on the order-service checkout endpoint via metrics. Delete `CheckoutController`, `CheckoutWorkflowImpl`, `CheckoutWorkflow` interface, all activity implementations in `com.instacommerce.order.workflow`, and the `TemporalConfig`/`TemporalProperties` if no other workflow is registered. Remove `temporal.*` keys from `order/application.yml`.

**Phase 3:** Remove the Temporal task queue `CHECKOUT_TASK_QUEUE` worker registration from order-service's `WorkerRegistration`.

**Rollback:** Re-enable the feature flag. No DB migration is needed.

### 1.4 Alternative Design: Async Checkout

The current orchestrator blocks the HTTP thread for the full Temporal workflow duration (up to 5 minutes execution timeout). Under load (10k+ concurrent checkouts at sale time) this exhausts the Tomcat thread pool.

**Option A — 202 Accepted + polling:**
```
POST /checkout → 202 Accepted { "workflowId": "checkout-user-key", "pollUrl": "/checkout/{workflowId}/status" }
GET  /checkout/{workflowId}/status → { "status": "COMPLETED", "orderId": "..." }
                                      { "status": "AUTHORIZING_PAYMENT" }
                                      { "status": "FAILED", "reason": "..." }
```
The `getStatus()` query method is already implemented on `CheckoutWorkflow`. The controller already exposes `GET /{workflowId}/status`. The only change is making `POST /checkout` return 202 immediately after launching the workflow stub, instead of calling `workflow.checkout(request)` synchronously.

**Option B — WebFlux + Temporal async stub:**
Use `WorkflowClient.execute()` which returns a `CompletableFuture<CheckoutResponse>`. Wrap in `Mono.fromFuture()` and return a server-sent event stream. More complex but enables real-time status push.

**Recommendation:** Option A. It is minimal-invasive, compatible with existing mobile clients via polling, and eliminates thread starvation. The `/checkout/{workflowId}/status` endpoint is already live.

### 1.5 Workflow ID Collision Risk

`checkout-orchestrator-service` workflow ID: `"checkout-" + principal + "-" + idempotencyKey`

If a client retries the same request with the same `Idempotency-Key` header:
- The DB cache returns the cached response (line 60–67 of `CheckoutController.java`) ✅
- If the TTL expired (30 min), a new workflow is launched with the same ID. Since the old workflow may still be running (5-min execution timeout), Temporal will throw `WorkflowExecutionAlreadyStarted`. This is caught by `DuplicateCheckoutException` in order-service but **not handled in checkout-orchestrator-service's controller**. The exception propagates as HTTP 500.

**Fix:** Catch `WorkflowExecutionAlreadyStarted` in `CheckoutController.initiateCheckout()` and return the cached response (or query the live workflow status).

```java
try {
    CheckoutResponse result = workflow.checkout(request);
    // ... persist and return
} catch (WorkflowExecutionAlreadyStarted ex) {
    // Workflow is still running — query its status or return a pending response
    return ResponseEntity.accepted()
        .body(CheckoutResponse.pending(workflowId));
}
```

---

## 2. Pricing Truth

### 2.1 Current State

The `checkout-orchestrator-service` correctly calls `pricing-service` during step 2 of the saga to obtain a locked price snapshot:

```java
PricingRequest pricingRequest = new PricingRequest(
    request.userId(), items, storeId, request.couponCode(), request.deliveryAddressId());
PricingResult pricingResult = pricingActivity.calculatePrice(pricingRequest);
paymentAmountCents = pricingResult.totalCents();
```

The returned `PricingResult` carries `(subtotalCents, discountCents, deliveryFeeCents, totalCents, currency)`.

This snapshot is immediately used for:
- Payment authorization amount (`paymentAmountCents`)
- Order creation (`subtotalCents`, `discountCents`, `deliveryFeeCents`, `totalCents`, `currency`)

**Issue: Pricing is not locked against race conditions.** If pricing-service allows a price to change between the `calculatePrice` call and the time the order is written to the database, the order may record a different total than what was authorized. This window is typically 100–200ms (activity execution), but during flash sales or coupon mass-redemptions, prices can change within that window.

**Issue: No pricing snapshot is stored.** The `Order` entity stores `subtotalCents`, `discountCents`, and `totalCents` but these are denormalized values from the pricing call. There is no `pricing_snapshot_id` or `locked_price_version` to prove what price was agreed to at checkout time. This matters for disputes, audits, and customer support.

### 2.2 Pricing Lock Pattern

**Option A — Quote Token (recommended):**
Pricing-service issues a short-lived `quoteToken` (UUID + signature + expiry, e.g., 5-minute TTL). Checkout-orchestrator passes `quoteToken` in the `OrderCreateRequest`. Order-service validates the token's signature against its own copy of pricing-service's public key before accepting the order creation.

```sql
-- pricing-service: add quote table
CREATE TABLE price_quotes (
    quote_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    store_id        VARCHAR(50) NOT NULL,
    items_hash      VARCHAR(64) NOT NULL,  -- SHA-256 of sorted item IDs + quantities
    total_cents     BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_price_quotes_used ON price_quotes (quote_id) WHERE used_at IS NULL;
```

The `PricingResult` includes `quoteId`. The checkout saga passes it to `OrderCreateRequest`. Order-service rejects order creation if `quoteId` is expired or already used.

**Option B — Idempotent recalculation:**
Order-service receives only the item list + storeId + couponCode and calls pricing-service itself to re-verify the total before writing the order. This doubles the pricing-service load during peak but guarantees freshness at order write time.

**Option C — Version-pinning:**
Pricing-service returns a `priceVersion` (monotonic per-item price change counter). Order-service stores `priceVersion` in the `orders` table and rejects if the item price has changed (requires a price-version lookup at order write time).

**Recommendation:** Option A (quote token). It is the standard pattern used by Stripe, Amazon, and Swiggy. It adds one round-trip to pricing-service during checkout (which already happens) and makes the price agreement immutable and auditable.

### 2.3 Migration Sequencing for Quote Tokens

1. **Migration 1:** Add `quote_id VARCHAR(64)` to `orders` table — nullable. Deploy order-service to accept `quoteId` in `CreateOrderCommand` without validating.
2. **Migration 2:** Deploy pricing-service with `price_quotes` table and quote issuance endpoint. Deploy checkout-orchestrator to pass `quoteId` in `OrderCreateRequest`.
3. **Migration 3:** Deploy order-service to validate `quoteId` (reject if expired). Enable validation behind feature flag.
4. **Migration 4:** Make `quote_id` NOT NULL once all traffic flows through the validated path.

### 2.4 Currency Hardening

`PricingResult.currency()` is a raw `String`. The payment-service's `PaymentTransactionHelper.normalizeCurrency()` normalizes to uppercase but does no ISO 4217 validation. If pricing-service ever returns `"inr"` or `"INR "` (whitespace), the order and payment currency will be inconsistent.

**Fix:** Add a `Currency.getInstance(currency)` check in `PaymentTransactionHelper.normalizeCurrency()` and throw `IllegalArgumentException` for non-ISO codes. Store the normalized value consistently.

---

## 3. Payment Idempotency

### 3.1 Layered Key Architecture

Payment idempotency is implemented across three layers:

```
Layer 1: Checkout-orchestrator workflow
  workflowId = "checkout-{userId}-{idempotencyKey}"
  authKey    = workflowId + "-payment"
  captureKey = workflowId + "-payment-" + paymentId + "-capture"
  voidKey    = workflowId + "-payment-" + paymentId + "-void"
  refundKey  = workflowId + "-payment-" + paymentId + "-refund"

Layer 2: Temporal activity execution
  PaymentActivityImpl.resolveIdempotencyKey(providedKey):
    return providedKey + "-" + activityId   ← activityId is stable across activity retries

Layer 3: Payment-service database
  UNIQUE (idempotency_key) on payments table
  PaymentTransactionHelper.savePendingAuthorization():
    if exists by idempotency_key → return null (idempotent return)
```

**Layer 2 is subtle and important.** Temporal's `activityId` is the identity of a specific scheduled activity attempt within a workflow run. When Temporal retries an activity (e.g., authorize payment fails transiently), it reuses the same `activityId`. This means the compound key `providedKey + "-" + activityId` is stable across retries of the same activity schedule, preventing double-charges on transient PSP failures.

### 3.2 Key Derivation Gap: Cross-Run Replay

If a Temporal workflow is reset (operator intervention via `tctl`), the workflow runs again from history with a new run ID but the same workflow ID. In this case:
- `workflowId` is stable (same)
- `activityId` may differ if the reset point is before the payment activity

The authorization key `workflowId + "-payment" + "-" + activityId` would differ from the original run, causing a second authorization attempt. If the original authorization is still in `AUTHORIZED` state (not voided), the user would have two authorized charges.

**Fix:** Use a sticky suffix that is deterministic across resets. Temporal provides `Workflow.randomUUID()` which is seeded from workflow history and is deterministically replayed. Generate a `paymentNonce` at the start of the workflow:

```java
String paymentNonce = Workflow.randomUUID().toString();
String authKey = workflowId + "-auth-" + paymentNonce;
```

Since `Workflow.randomUUID()` is deterministic within a workflow run's history, replaying from history always produces the same nonce. This is safe regardless of reset point.

### 3.3 Capture Key Construction Race

The current capture key is constructed after the authorization succeeds:

```java
paymentOperationKeyPrefix = paymentIdempotencyKey + "-" + paymentResult.paymentId();
// Later:
paymentActivity.capturePayment(paymentId, paymentOperationKeyPrefix + "-capture");
```

If the workflow crashes after authorization but before the capture key is persisted in Temporal history (i.e., before the next workflow checkpoint), and then the workflow is restarted, `paymentOperationKeyPrefix` is re-derived correctly because `paymentResult.paymentId()` is loaded from Temporal history. This is correct Temporal determinism. ✅

However, the capture key ultimately resolves to:
```
"checkout-{userId}-{idemKey}-payment-{paymentId}-capture-{activityId}"
```
This is **36 + 9 + 36 + 9 + 36 + 9 + 36 = ~131 characters**. The `payments.idempotency_key` column is `VARCHAR(64)`. Truncation is possible.

**Fix:** Apply SHA-256 to keys that exceed 60 characters. Use `DigestUtils.sha256Hex(key).substring(0, 64)`.

```java
private String resolveIdempotencyKey(String providedKey) {
    String activityId = Activity.getExecutionContext().getInfo().getActivityId();
    String raw = (providedKey == null || providedKey.isBlank()) ? activityId : providedKey + "-" + activityId;
    return raw.length() > 60 ? DigestUtils.sha256Hex(raw).substring(0, 64) : raw;
}
```

### 3.4 Double-Charge Scenario Analysis

| Scenario | Current handling | Safe? |
|---|---|---|
| Client retries same idempotency key within 30 min | DB cache hit → cached response returned | ✅ |
| Temporal retries failed authorize activity | Same activityId → same key → DB UNIQUE hit → idempotent return | ✅ |
| PSP timeout on authorize, Temporal retries | Same activityId, same key → payment-service returns existing AUTHORIZED | ✅ |
| Workflow reset before authorize | New activityId → new key → second authorization → **double charge** | ❌ |
| Capture key > 64 chars → VARCHAR truncation | Two different full keys both truncate to same 64-char prefix | ❌ (potential) |
| Crash between capture start and CAPTURE_PENDING write | payment-service revertToAuthorized on failure, workflow retries with same key | ✅ |
| Compensate void fails (PSP timeout), workflow retries | `voidIdempotencyKey` is stable, PSP deduplicates | ✅ (PSP-dependent) |

### 3.5 Payment-Service Internal Idempotency

The `PaymentTransactionHelper.savePendingAuthorization()` pattern is correct:

```
TX1: INSERT payment (AUTHORIZE_PENDING) with idempotency_key
     ↓ unique constraint guards against duplicates
PSP call (outside TX)
TX2: UPDATE payment → AUTHORIZED + pspReference + ledger + outbox
```

If the service crashes between TX1 and TX2, the payment is stuck in `AUTHORIZE_PENDING` forever. See §6 for recovery. ✅

The return-null-on-idempotent-hit pattern in `savePendingAuthorization()` is fragile: callers must null-check the return value, and any future caller that doesn't check will NPE. **Fix:** Return an `IdempotentPaymentResult` that distinguishes `NEW` from `EXISTING`:

```java
record IdempotentPaymentResult(Payment payment, boolean isNew) {}
```

---

## 4. Webhook Closure

### 4.1 Architecture: Two-Layer Deduplication

The webhook pipeline has two separate deduplication mechanisms at different service boundaries:

```
PSP (Stripe / Razorpay / PhonePe)
    │
    ▼
payment-webhook-service (Go, port 8102)
    ├── SignatureVerifier (per-PSP HMAC-SHA256)
    ├── In-memory IdempotencyStore (TTL map, maxSize eviction)
    └── Kafka Producer → topic: "payment-webhook-events"
                               │
                               ▼
               [Kafka Consumer in payment-service — not yet implemented]
                               OR
               Direct PSP webhook forwarding to payment-service:
    │
    ▼
payment-service (Java, port 8086)
    ├── WebhookSignatureVerifier (Stripe signature only)
    ├── WebhookController.handleWebhook() — raw body + Stripe-Signature header
    └── WebhookEventHandler
        ├── DB dedup: processed_webhook_events (PRIMARY KEY event_id)
        └── Optimistic lock retry (3 attempts, 100ms*n backoff)
```

**Architectural confusion:** The Go `payment-webhook-service` publishes to Kafka but there is no Kafka consumer in `payment-service` that reads from the webhook topic. The Java `payment-service` has its own `WebhookController` that accepts direct Stripe webhooks. These are **two parallel webhook paths**:

- Path A: Stripe → payment-webhook-service (Go) → Kafka → *no consumer* (dropped)
- Path B: Stripe → payment-service directly → `WebhookController` → DB dedup → state update

Path A's Kafka events are currently unprocessed. Path B works correctly but is Stripe-only and bypasses the multi-PSP normalization the Go service provides.

### 4.2 Go IdempotencyStore Failure Mode

```go
// handler/dedup.go
type IdempotencyStore struct {
    mu      sync.RWMutex
    seen    map[string]time.Time  // ← in-memory only
    ttl     time.Duration
    maxSize int
    done    chan struct{}
}
```

**Problem:** In-memory deduplication is not durable across restarts and is not shared across Go service instances. If two instances of `payment-webhook-service` run (standard horizontal scaling), both will process the same webhook event and both will publish to Kafka, resulting in duplicate messages on the `payment-webhook-events` topic.

The DB-level dedup in `payment-service` (`processed_webhook_events` PRIMARY KEY) is the correct backstop, but only works if Path B is active (direct webhook). If the intention is to process via Path A (Kafka), the Kafka consumer must apply its own DB dedup.

**Fix (Go service):** Replace `IdempotencyStore` with a Redis-backed implementation:

```go
type RedisIdempotencyStore struct {
    client redis.Client
    ttl    time.Duration
}

func (s *RedisIdempotencyStore) IsDuplicate(eventID string) bool {
    return s.client.Exists(ctx, "webhook:seen:"+eventID).Val() > 0
}

func (s *RedisIdempotencyStore) Mark(eventID string) {
    s.client.SetEX(ctx, "webhook:seen:"+eventID, "1", s.ttl)
}
```

The `go-shared` package already provides Redis connection patterns; use those rather than introducing a new Redis client dependency.

### 4.3 Resolving the Two-Path Problem

**Option A — Consolidate on Path B (direct webhook):**
- Decommission the Go webhook service's Kafka publish.
- Register Stripe/Razorpay/PhonePe webhooks directly to `payment-service`'s `/payments/webhook` endpoint.
- Add Razorpay and PhonePe signature verification to `WebhookSignatureVerifier`.
- Add PSP-specific event parsing to `WebhookEventHandler`.
- Simplest path; no Kafka dependency for webhook processing.

**Option B — Consolidate on Path A (Kafka-mediated):**
- Go service does multi-PSP normalization + signature verification + Kafka publish with Redis dedup.
- Add a Kafka consumer in `payment-service` that reads from `payment-webhook-events` topic.
- Consumer applies DB dedup (`processed_webhook_events`) and calls `WebhookEventHandler.processEvent()`.
- Remove `WebhookController` from `payment-service` (no direct PSP webhook endpoint).
- Better separation: Go service owns PSP protocol translation, Java service owns business state.

**Option C — Keep both paths with event bridging:**
- Go service publishes to Kafka.
- A separate `cdc-consumer-service` bridges the Kafka topic into HTTP calls to `payment-service`.
- More moving parts; not recommended.

**Recommendation:** Option B. The Go service's multi-PSP support (Stripe, Razorpay, PhonePe) and PSP-specific signature verification is valuable and correctly isolates protocol translation. Adding a Kafka consumer to `payment-service` is straightforward and uses the existing outbox/event infrastructure patterns.

### 4.4 Webhook Closure SLA

PSPs require a 2xx response within 30 seconds or they retry. The Go service responds with 202 Accepted immediately after Kafka publish (before Kafka broker acknowledgment in the worst case). This is correct — the PSP does not need to wait for business processing.

Payment-service's `WebhookController` processes synchronously. If `WebhookEventHandler.processEvent()` is slow (optimistic lock retries × 100ms backoff), the response can be delayed. At `MAX_RETRY_ATTEMPTS = 3` with `100ms × attempt` backoff, worst-case inline delay is `100 + 200 + 300 = 600ms` before giving up. This is acceptable for the 30-second PSP SLA.

However, if the webhook is delivered during a DB maintenance window or connection pool exhaustion, the handler throws and the controller returns 500. The PSP will retry; the `processed_webhook_events` dedup ensures idempotent handling on the retry.

**Alerting:** Track `webhook_events_failed_total{psp="*", reason="*"}` in the Go service and `webhook_processing_time_seconds` percentiles in payment-service. Alert on P99 > 5s or error rate > 0.1%.

### 4.5 Webhook State Machine Correctness

`WebhookEventHandler.applyEvent()` handles four Stripe event types:

| Event | Handler | Guard | Ledger Entry |
|---|---|---|---|
| `payment_intent.succeeded` | `handleCaptured` | skips if already CAPTURED | DEBIT authorization_hold → CREDIT merchant_payable |
| `payment_intent.canceled` | `handleVoided` | skips if already VOIDED | DEBIT authorization_hold → CREDIT customer_receivable |
| `payment_intent.payment_failed` | `handleFailed` | skips if already FAILED | none |
| `charge.refunded` | `handleRefunded` | skips if delta ≤ 0 | DEBIT merchant_payable → CREDIT customer_receivable |

**Issue:** Webhook events can arrive out of order. A `charge.refunded` event may arrive before `payment_intent.succeeded` if Stripe batches them. In this case:
- `handleRefunded` executes, sets `PARTIALLY_REFUNDED` or `REFUNDED` on a payment that is still in `AUTHORIZED` state.
- `handleCaptured` then arrives, but the `CAPTURED` guard passes (status ≠ CAPTURED), so it sets status to CAPTURED and records the capture ledger entry. But `capturedCents` was already updated by the refund handler.

**Fix:** Add state guards that reject out-of-order events for semantically invalid transitions:

```java
private void handleRefunded(Payment payment, JsonNode objectNode, String eventId) {
    if (payment.getStatus() == PaymentStatus.AUTHORIZED
        || payment.getStatus() == PaymentStatus.AUTHORIZE_PENDING) {
        log.warn("Refund event arrived before capture for payment {}, queuing for reprocessing", payment.getId());
        // Publish to a dead-letter Kafka topic for manual review
        return;
    }
    // existing logic
}
```

---

## 5. Reconciliation

### 5.1 Current State: File-Based Batch Reconciliation

The `reconciliation-engine` (Go, port 8107) implements scheduled reconciliation comparing two JSON files:

```go
type Config struct {
    PSPExportPath    string  // path to PSP export JSON file
    LedgerPath       string  // path to internal ledger JSON file
    LedgerOutputPath string  // updated ledger written here
    FixStatePath     string  // fix registry (already-applied fixes)
}
```

**Critical gap:** The engine does not read from the live `payment-service` database. It reads from a `LEDGER_PATH` JSON file that must be exported and mounted externally. There is no mechanism to produce this file from the running system. The reconciliation logic is architecturally sound but operationally disconnected from the live data.

### 5.2 Mismatch Detection Logic

```
For each PSP transaction:
  - missing_ledger_entry: no matching ledger entry → auto-fixable (creates entry)
  - amount_mismatch:      amounts differ → auto-fixable (updates entry)
  - currency_mismatch:    currencies differ → manual review

For each ledger entry:
  - missing_psp_export:   no matching PSP record → manual review
```

Auto-fixes are idempotent via a `FixRegistry` (file-backed):
```go
fixID = "type:transactionID"   // e.g., "missing_ledger_entry:psp-1001"
if fixRegistry.Contains(fixID) → skip
else → apply + record + publish Kafka event
```

### 5.3 Target State: Live DB Reconciliation

The reconciliation engine should compare the PSP export against the actual `ledger_entries` table in `payment-service`. Migration path:

**Step 1 — Ledger HTTP export endpoint:**
Add to `payment-service`:
```java
// GET /internal/reconciliation/ledger?since=2026-03-07T00:00:00Z&until=2026-03-07T23:59:59Z
// Returns NDJSON stream of ledger entries
// Secured by INTERNAL_SERVICE_TOKEN header
```

**Step 2 — Reconciliation engine reads ledger via HTTP:**
Replace `LedgerStore` (file-based) with an `HttpLedgerClient` that fetches from the payment-service endpoint. The `PSPSource` remains file-based or is replaced with a PSP report API call.

**Step 3 — Reconciliation engine applies fixes via HTTP:**
Replace `LedgerStore.upsertEntry()` (file write) with `POST /internal/reconciliation/ledger/fix` to payment-service, which applies the fix within a transaction and publishes an outbox event.

**Step 4 — Mismatch event consumers:**
The `reconciliation.events` Kafka topic is published now. Add a consumer in the monitoring/alerting pipeline that:
- Fires PagerDuty alerts on `manual_review` events
- Creates JIRA tickets for `currency_mismatch` events
- Auto-closes tickets on `fixed` events

### 5.4 Alternative: Direct PSP Settlement Report Reconciliation

Rather than comparing against a running ledger, reconcile against daily settlement reports from the PSP:

| Source | Timing | Pros | Cons |
|---|---|---|---|
| Real-time webhook events | Within seconds | Catches issues immediately | Webhooks can be missed or delayed |
| T+1 settlement report (CSV/API) | Next morning | Authoritative PSP truth | 24h lag |
| Intraday reconciliation (6h window) | Every 6 hours | Balance between timeliness and completeness | More complex scheduling |

**Recommendation:** Run three reconciliation tiers:
1. **Real-time:** webhook events update ledger immediately (existing webhook flow)
2. **Intraday:** reconciliation-engine every 30 minutes against live DB
3. **Settlement:** T+1 reconciliation against PSP settlement file with strict amount matching

### 5.5 Currency and Precision

All amounts in the system are in integer cents (`BIGINT`, no floating point). The ledger's `amount_cents` and the PSP export's `amount_cents` are compared by integer equality. This is correct and avoids floating-point precision bugs common in financial systems.

The reconciliation engine's `Mismatch.Type == "amount_mismatch"` fires even for ±1 cent differences, which can arise from PSP rounding in multi-currency scenarios. Add a configurable tolerance band (e.g., 0 for INR, 1 for currencies with sub-unit rounding) controlled by `RECONCILIATION_TOLERANCE_CENTS` env var.

---

## 6. Pending-State Recovery

### 6.1 Pending States Defined

V7 migration adds three pending states to the `payment_status` enum:

```sql
ALTER TYPE payment_status ADD VALUE IF NOT EXISTS 'AUTHORIZE_PENDING' BEFORE 'AUTHORIZED';
ALTER TYPE payment_status ADD VALUE IF NOT EXISTS 'CAPTURE_PENDING' AFTER 'AUTHORIZED';
ALTER TYPE payment_status ADD VALUE IF NOT EXISTS 'VOID_PENDING' AFTER 'CAPTURE_PENDING';
```

These are written atomically to the database *before* the PSP is called, enabling crash recovery. However, **no recovery job exists** to resolve stuck pending states.

### 6.2 Failure Modes

| Scenario | Resulting state | Recovery needed |
|---|---|---|
| Service crash after `AUTHORIZE_PENDING` write, before PSP response | `AUTHORIZE_PENDING` forever | Query PSP: if authorized → `AUTHORIZED`, if failed → `FAILED`, if unknown → retry or void |
| PSP timeout on authorize (no response) | `AUTHORIZE_PENDING` | Query PSP status via payment intent lookup |
| Service crash after `CAPTURE_PENDING` write, before PSP response | `CAPTURE_PENDING` | PSP may have captured; must query PSP |
| PSP timeout on capture, `revertToAuthorized()` called | `AUTHORIZED` (safe) | No recovery needed |
| Service crash after `VOID_PENDING` write, before PSP response | `VOID_PENDING` | PSP may have voided; must query PSP |
| Service crash after PSP authorize, before `completeAuthorization()` TX | `AUTHORIZE_PENDING` | PSP charged but ledger/outbox not written |

The most dangerous scenario is #6: the PSP has a valid authorization hold on the customer's card, but the payment-service has no record of it being `AUTHORIZED`. The Temporal saga's `compensatePayment()` will attempt a void using the `paymentId`, which does exist (it's in `AUTHORIZE_PENDING` status), but `saveVoidPending()` will throw `PaymentInvalidStateException` because status ≠ AUTHORIZED.

### 6.3 StalePendingRecoveryJob: Implementation

```java
@Component
public class StalePendingRecoveryJob {
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(15);
    private static final Logger log = LoggerFactory.getLogger(StalePendingRecoveryJob.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentTransactionHelper txHelper;
    private final AuditLogService auditLogService;

    @Scheduled(fixedDelay = 5 * 60 * 1000)  // every 5 minutes
    @SchedulerLock(name = "stalePendingRecovery", lockAtLeastFor = "PT2M", lockAtMostFor = "PT10M")
    public void recover() {
        Instant staleBefore = Instant.now().minus(STALE_THRESHOLD);

        // Recover stuck AUTHORIZE_PENDING
        paymentRepository
            .findByStatusAndCreatedAtBefore(PaymentStatus.AUTHORIZE_PENDING, staleBefore)
            .forEach(this::recoverAuthorize);

        // Recover stuck CAPTURE_PENDING
        paymentRepository
            .findByStatusAndUpdatedAtBefore(PaymentStatus.CAPTURE_PENDING, staleBefore)
            .forEach(this::recoverCapture);

        // Recover stuck VOID_PENDING
        paymentRepository
            .findByStatusAndUpdatedAtBefore(PaymentStatus.VOID_PENDING, staleBefore)
            .forEach(this::recoverVoid);
    }

    private void recoverAuthorize(Payment payment) {
        try {
            GatewayStatusResult status = paymentGateway.getStatus(payment.getPspReference());
            if (status == null || payment.getPspReference() == null) {
                // PSP never received the request — mark FAILED
                txHelper.markAuthorizationFailed(payment.getId());
                return;
            }
            if (status.isAuthorized()) {
                txHelper.completeAuthorization(payment.getId(), payment.getPspReference());
            } else {
                txHelper.markAuthorizationFailed(payment.getId());
            }
        } catch (Exception ex) {
            log.error("Recovery failed for AUTHORIZE_PENDING payment {}", payment.getId(), ex);
            auditLogService.log(null, "RECOVERY_FAILED", "Payment",
                payment.getId().toString(), Map.of("status", "AUTHORIZE_PENDING", "error", ex.getMessage()));
        }
    }

    // recoverCapture(), recoverVoid() follow same pattern using paymentGateway.getStatus()
}
```

**Required DB indices:**
```sql
-- Add to payment-service migration V9
CREATE INDEX idx_payments_status_created ON payments (status, created_at)
    WHERE status IN ('AUTHORIZE_PENDING', 'CAPTURE_PENDING', 'VOID_PENDING');
```

**PaymentGateway interface extension required:**
```java
public interface PaymentGateway {
    GatewayAuthResult authorize(GatewayAuthRequest request);
    GatewayCaptureResult capture(String pspReference, long amountCents, String idempotencyKey);
    GatewayVoidResult voidAuth(String pspReference, String idempotencyKey);
    GatewayRefundResult refund(String pspReference, long amountCents, String idempotencyKey);
    GatewayStatusResult getStatus(String pspReference);  // ← add this
}
```

### 6.4 Pending-State Recovery in checkout-orchestrator-service

The checkout-orchestrator's `CheckoutIdempotencyKey` table has a 30-minute TTL. There is no mechanism to detect orders where the Temporal workflow is stuck (e.g., Temporal worker crashed mid-saga).

**Add a stale workflow detection job:**

```java
@Scheduled(fixedDelay = 10 * 60 * 1000)  // every 10 minutes
@SchedulerLock(name = "staleWorkflowDetection", lockAtLeastFor = "PT3M", lockAtMostFor = "PT15M")
public void detectStaleWorkflows() {
    Instant staleBefore = Instant.now().minus(Duration.ofMinutes(10));
    // Find all idempotency keys created > 10 minutes ago with no associated successful checkout
    // For each: query Temporal workflow status via workflowClient.newUntypedWorkflowStub(workflowId)
    //            if workflow is RUNNING and workflow.getStatus() is stuck (e.g., "AUTHORIZING_PAYMENT")
    //            → send alert to PagerDuty / ops channel
    //            if workflow is FAILED → purge idempotency key so customer can retry
}
```

### 6.5 Order-Service Pending Orders

The `orders` table has `status = PENDING` as the initial state. An order transitions from `PENDING` to `PLACED` when the checkout saga completes successfully (via `orders.updateOrderStatus(orderId, "PLACED")` in the order-service workflow, or via the order-service `OrderActivities`).

Orders can be stuck in `PENDING` if:
- The saga compensates (order is cancelled correctly) ✅
- The Temporal worker crashes between `createOrder` and `updateOrderStatus` ❌

**Fix:** Add a `StalePendingOrderRecoveryJob` in `order-service`:
```java
// Find PENDING orders older than 30 minutes with a non-null payment_id
// → these were created by a saga that should have completed
// Query Temporal workflow status by reconstructing workflowId
// If workflow COMPLETED → update order to PLACED
// If workflow FAILED → update order to CANCELLED (saga compensation may not have run)
// Alert on any PENDING order older than 1 hour
```

---

## 7. Safer Rollout of Money-Path Changes

### 7.1 Risk Classification

Money-path changes fall into three risk tiers:

| Tier | Examples | Required gates |
|---|---|---|
| 🔴 Critical | Idempotency key construction, PSP call parameters, compensation logic, schema changes to `payments`/`orders` tables | Dark launch + load test + manual audit + staged rollout with 15-min canary hold |
| 🟠 High | Webhook event handlers, ledger entry logic, reconciliation mismatch rules | Feature flag + shadow mode + reconciliation diff test |
| 🟡 Medium | Retry policies, timeout values, logging additions, non-financial status changes | Standard PR + integration test + canary |

### 7.2 Feature Flag Integration

All three Java services already use Spring's property-driven configuration. The `config-feature-flag-service` provides centralized flag management. Wire it into money-path changes:

```java
// PaymentService.java
private boolean isPartialCaptureEnabled() {
    return configClient.isEnabled("payment.partial-capture", false);
}
```

Flags must be:
- **Defaulted to `false`** — new behavior is off until explicitly enabled
- **Scoped by percentage** — 0% → 1% → 10% → 50% → 100% rollout
- **Observable** — every flag evaluation emits a counter metric tagged by flag name + value

### 7.3 Schema Migration Safety

**Non-breaking (safe):**
- Add nullable column to existing table
- Add new index (CONCURRENTLY in PostgreSQL)
- Add new value to existing ENUM (`ADD VALUE IF NOT EXISTS`)
- Add new table

**Breaking (requires multi-step deploy):**
- Rename column → add new + backfill + remove old (three separate deploys)
- Remove column → mark deprecated + dual-write + remove (three separate deploys)
- Change ENUM values → new ENUM type + column migration + drop old (complex)
- Change idempotency key construction → requires dual-key lookup window

**The ENUM addition pattern (already used in V7):**
```sql
ALTER TYPE payment_status ADD VALUE IF NOT EXISTS 'CAPTURE_PENDING' AFTER 'AUTHORIZED';
```
This is safe to deploy before the application code that uses the new value. ✅

### 7.4 Idempotency Key Migration Protocol

If the idempotency key construction algorithm changes (e.g., adding SHA-256 normalization), both old and new key formats must be valid during the transition window:

**Step 1:** Extend `PaymentRepository.findByIdempotencyKey()` to query with both old-format and new-format keys:
```java
Optional<Payment> findByIdempotencyKey(String key);
Optional<Payment> findByIdempotencyKeyOrLegacyIdempotencyKey(String key, String legacyKey);
```

**Step 2:** Add a `legacy_idempotency_key VARCHAR(255)` column to the `payments` table (nullable).

**Step 3:** Deploy new service version that writes new-format key to `idempotency_key` and old-format key to `legacy_idempotency_key`. Lookups check both.

**Step 4:** After all in-flight payments are resolved (one day), remove `legacy_idempotency_key` lookups. Drop the column in a follow-up migration.

### 7.5 Temporal Workflow Versioning

When changing `CheckoutWorkflowImpl`'s execution logic (new steps, changed step order, changed compensation logic), use `Workflow.getVersion()` to maintain backward compatibility with in-flight workflows:

```java
// Scenario: adding fraud check as step 1.5 (between cart validation and pricing)
int version = Workflow.getVersion("fraud-check-v1", Workflow.DEFAULT_VERSION, 1);
if (version >= 1) {
    FraudCheckResult fraudResult = fraudActivity.check(request.userId(), items);
    if (fraudResult.blocked()) {
        return CheckoutResponse.failed("Order blocked by fraud prevention");
    }
}
```

Workflows started before the deployment will have `DEFAULT_VERSION` (-1) and skip the fraud check. Workflows started after will run the new code. This is safe for rolling deploys.

**Versioning discipline:**
- Every behavioral change to `CheckoutWorkflowImpl` must use `Workflow.getVersion()`
- Version names must be descriptive: `"fraud-check-v1"`, `"async-capture-v2"`
- Keep the old code paths until all workflows using those versions have completed (check Temporal UI `maxVersion` vs running workflows)

### 7.6 Canary Deployment for Payment-Service

Because payment-service holds live financial state, canary deployments require additional care:

1. **Shadow mode:** Deploy new version alongside old, route 1% of traffic to new, compare PSP call parameters and idempotency keys — do not actually execute PSP calls in shadow mode.
2. **Canary hold gate:** After initial 1% canary, hold for 15 minutes and verify:
   - Zero increase in `payment_failed_total`
   - Zero `AUTHORIZE_PENDING` / `CAPTURE_PENDING` entries older than 5 minutes
   - Reconciliation engine reports no new mismatches
   - `processed_webhook_events` count progressing normally
3. **Database migration gating:** Flyway migrations run on service startup. If migration fails, service fails to start and the old pods keep traffic. Never deploy a migration that removes or renames columns used by the old code version without a deprecation window.

### 7.7 Rollback Decision Matrix

| Signal | Action | Time budget |
|---|---|---|
| P99 authorize latency > 5s | Rollback immediately | 2 minutes |
| Double-charge detected (PagerDuty) | Stop traffic + rollback + manual review + customer credit | Immediate |
| AUTHORIZE_PENDING count rising | Investigate + rollback if recovery job can't keep up | 5 minutes |
| Reconciliation mismatch rate > 0.01% | Investigation + flag disable; rollback if not resolved | 30 minutes |
| Webhook error rate > 1% | Investigation; rollback if PSP signature verification broken | 10 minutes |
| Idempotency key collision detected | Stop deploy + rollback immediately (potential double charge) | Immediate |

---

## 8. Cross-Cutting Observability

### 8.1 Required Metrics

All services export Prometheus metrics via `/actuator/prometheus` (Java) or `/metrics` (Go). The following money-path metrics must be added or verified:

#### checkout-orchestrator-service
```
checkout_started_total{status="success|failure"}
checkout_step_duration_seconds{step="validate_cart|calculate_price|reserve_inventory|authorize_payment|create_order|confirm|clear_cart"}
checkout_compensation_total{step="inventory|payment|order"}
checkout_idempotency_hit_total{reason="cache_hit|expired_key"}
checkout_workflow_stuck_total{step="*"}          ← from stale workflow detection job
```

#### payment-service
```
payment_authorizations_total{result="success|declined|failed"}
payment_captures_total{result="success|failed"}
payment_voids_total{result="success|failed"}
payment_refunds_total{result="success|failed"}
payment_pending_state_age_seconds{status="AUTHORIZE_PENDING|CAPTURE_PENDING|VOID_PENDING"}  ← gauge, max age
payment_recovery_total{status="*", outcome="resolved|manual"}
webhook_events_processed_total{psp="*", event_type="*"}
webhook_events_failed_total{psp="*", reason="*"}
webhook_duplicate_total{psp="*"}
```

#### reconciliation-engine (already implemented)
```
reconciliation_mismatches_total
reconciliation_fixed_total
reconciliation_manual_review_total
```
Add:
```
reconciliation_run_duration_seconds
reconciliation_ledger_fetch_error_total
reconciliation_psp_fetch_error_total
```

### 8.2 Distributed Trace Requirements

The checkout saga creates a distributed trace that spans 5+ services. For effective debugging:

1. **Temporal workflow ID → trace correlation:** Emit `workflow.id` and `workflow.run_id` as span attributes on every activity's parent span.
2. **Payment idempotency key → trace link:** Store `trace_id` alongside the idempotency key in `checkout_idempotency_keys` table. When a cached response is returned, log the original trace ID.
3. **Cross-service propagation:** Ensure `RestTemplate` beans in `checkout-orchestrator-service` propagate the `traceparent` header. The `InternalServiceAuthInterceptor` should also inject this header.

### 8.3 SLA Dashboards

Create a Grafana dashboard with the following panels:

| Panel | Query | Alert threshold |
|---|---|---|
| Checkout success rate | `rate(checkout_started_total{status="success"}[5m]) / rate(checkout_started_total[5m])` | < 95% |
| P99 checkout duration | `histogram_quantile(0.99, checkout_step_duration_seconds{step="capture"})` | > 5s |
| Pending state health | `payment_pending_state_age_seconds` (max gauge) | > 600s (10 min) |
| Webhook closure rate | `rate(webhook_events_processed_total[5m])` vs PSP delivery rate | gap > 100 |
| Reconciliation mismatch rate | `reconciliation_mismatches_total` change per hour | > 10/hour |
| Double-charge sentinel | Manual review alert from reconciliation | Any alert = P0 |

### 8.4 Audit Trail Requirements

The `AuditLog` tables in both `order-service` and `payment-service` must capture every money-path state change. Verify the following events are logged:

| Event | Service | Required fields |
|---|---|---|
| Order created | order-service | orderId, userId, totalCents, currency, paymentId, reservationId |
| Order placed | order-service | orderId, changedBy, previousStatus |
| Order cancelled | order-service | orderId, reason, cancelledBy, paymentId (for refund routing) |
| Payment authorized | payment-service | paymentId, orderId, amountCents, currency, pspReference, idempotencyKey |
| Payment captured | payment-service | paymentId, capturedCents, pspReference |
| Payment voided | payment-service | paymentId, voidedAt |
| Payment refunded | payment-service | paymentId, refundId, amountCents, reason |
| Webhook received | payment-service | eventId, pspEventType, pspReference, outcome |
| Pending recovery | payment-service | paymentId, recoveredFrom, resolvedTo |
| Reconciliation fix | reconciliation-engine | transactionId, mismatchType, appliedAt |

---

## 9. Contract Surface

### 9.1 Event Schema Gaps

The `contracts/` payment event schemas are minimal:

```json
// PaymentAuthorized.v1.json — current
{ "paymentId": "uuid", "orderId": "uuid", "amountCents": int, "currency": string }
```

Missing fields that downstream consumers (order-service, fulfillment-service, wallet-service, fraud-service) will need:

```json
// PaymentAuthorized.v2.json — proposed (additive only, stays v1 schema version)
{
  "paymentId":      "uuid",
  "orderId":        "uuid",
  "amountCents":    int,
  "currency":       string,
  "pspReference":   string,   // ← needed for refund routing
  "paymentMethod":  string,   // ← "CARD", "UPI", "WALLET" — needed for routing
  "idempotencyKey": string,   // ← audit trail
  "authorizedAt":   datetime  // ← for timeout tracking
}
```

These are additive fields. Backward-compatible under JSON Schema draft-07. No `v2` file needed — amend `v1` schemas with `"required"` check only on existing required fields.

### 9.2 Standard Event Envelope

Per `contracts/README.md`, all events must carry the standard envelope:

```json
{
  "event_id":       "uuid",
  "event_type":     "PaymentAuthorized",
  "aggregate_id":   "payment-uuid",
  "schema_version": "v1",
  "source_service": "payment-service",
  "correlation_id": "checkout-workflow-id",
  "timestamp":      "2026-03-07T00:00:00Z",
  "payload":        { ... }
}
```

**Current gap:** `payment-service`'s `OutboxService.publish()` writes directly to the `payload` JSONB column with no envelope. The `aggregate_type` and `event_type` are present but `event_id`, `schema_version`, `source_service`, `correlation_id`, and `timestamp` are absent from the outbox row. Debezium CDC will publish these rows to Kafka without the standard envelope.

**Fix:** Extend `OutboxService` to write the full envelope:

```java
@Transactional(propagation = Propagation.MANDATORY)
public void publish(String aggregateType, String aggregateId, String eventType,
                    String correlationId, String schemaVersion, Object payload) {
    OutboxEvent event = new OutboxEvent();
    event.setEventId(UUID.randomUUID().toString());
    event.setAggregateType(aggregateType);
    event.setAggregateId(aggregateId);
    event.setEventType(eventType);
    event.setSchemaVersion(schemaVersion);
    event.setSourceService("payment-service");
    event.setCorrelationId(correlationId);
    event.setTimestamp(Instant.now());
    event.setPayload(writePayload(payload));
    outboxEventRepository.save(event);
}
```

The `correlationId` passed from `PaymentTransactionHelper` should be the Temporal workflow ID, which must be threaded through from the `AuthorizeRequest` payload.

### 9.3 OrderCancelled Event — Refund Routing

The `order-service` publishes `OrderCancelled` with:

```json
{ "orderId": "uuid", "userId": "uuid", "paymentId": "uuid", "totalCents": int, "currency": string, "reason": string }
```

This is the trigger for payment-service to initiate a refund. However, there is **no consumer of this event in payment-service**. The comment in `OrderService.cancelOrderByUser()` states:
```java
// Payment refund should be handled by a consumer of the OrderCancelled event
```

**Required:** Add a Kafka consumer in `payment-service` that:
1. Reads `order.OrderCancelled` events
2. Calls `RefundService.refund(paymentId, totalCents, reason)` with an idempotency key derived from `orderId + "-cancellation-refund"`
3. Publishes `PaymentRefunded` event

This completes the cancellation refund loop. Without it, cancelled orders retain their charges until a human manually triggers a refund.

---

## 10. Migration Sequencing

The following table sequences all recommended changes in dependency order:

| Phase | Change | Service | Risk | Prerequisite |
|---|---|---|---|---|
| **P1** | Add VARCHAR(64) cap + SHA-256 normalization to idempotency key resolution | checkout-orchestrator, payment-service | 🟡 | None |
| **P1** | Add recovery index `idx_payments_status_created` | payment-service (migration V9) | 🟢 | None |
| **P1** | Add `StalePendingRecoveryJob` behind feature flag | payment-service | 🟠 | P1 index |
| **P1** | Add `getStatus()` to `PaymentGateway` interface | payment-service | 🟡 | None |
| **P1** | Catch `WorkflowExecutionAlreadyStarted` in checkout-orchestrator controller | checkout-orchestrator | 🟡 | None |
| **P2** | Add Redis-backed `IdempotencyStore` to payment-webhook-service | payment-webhook-service (Go) | 🟡 | Redis available |
| **P2** | Add Razorpay + PhonePe signature verification to payment-service | payment-service | 🟡 | None |
| **P2** | Add Kafka consumer in payment-service for `payment-webhook-events` topic | payment-service | 🟠 | P2 Go dedup |
| **P2** | Add `OrderCancelled` Kafka consumer + auto-refund in payment-service | payment-service | 🟠 | None |
| **P2** | Disable `CheckoutController` + workflow in order-service behind feature flag | order-service | 🟠 | Traffic validated |
| **P3** | Add `price_quotes` table to pricing-service + quote token endpoint | pricing-service | 🟠 | None |
| **P3** | Pass `quoteId` in `OrderCreateRequest` from checkout-orchestrator | checkout-orchestrator | 🟠 | P3 pricing |
| **P3** | Add `quote_id` nullable column to `orders` table | order-service (migration V10) | 🟢 | None |
| **P3** | Enable quote validation in order-service behind feature flag | order-service | 🟠 | P3 pricing + P3 column |
| **P3** | Add standard event envelope to `OutboxService` in both Java services | order-service, payment-service | 🟡 | Downstream consumer compatibility verified |
| **P4** | Delete checkout workflow from order-service entirely | order-service | 🟡 | P2 feature flag + zero traffic verified |
| **P4** | Make `quote_id` NOT NULL in `orders` table | order-service (migration V11) | 🟢 | P3 full rollout + backfill |
| **P4** | Connect reconciliation-engine to live DB via HTTP ledger endpoint | payment-service + reconciliation-engine | 🟡 | P1 complete |

### 10.1 Validation Checklist per Phase

**Before any P1 deploy:**
- [ ] Run `./gradlew :services:payment-service:test` — all tests pass
- [ ] Run `./gradlew :services:checkout-orchestrator-service:test` — all tests pass
- [ ] Verify `AUTHORIZE_PENDING` count in prod is 0 before deploying recovery job
- [ ] Load test: 1k checkout/s for 5 minutes, verify compensation rate < 0.01%

**Before any P2 deploy:**
- [ ] Verify Kafka consumer group `payment-webhook-consumer` has zero lag before enabling
- [ ] Shadow-test webhook path: replay last 1000 Stripe events through new consumer, compare state changes to current DB state
- [ ] Verify Razorpay + PhonePe signature tests pass with production sample payloads

**Before any P3 deploy:**
- [ ] Quote token TTL aligns with checkout step 2→5 maximum duration (currently ≤ 60s total activity time) — set quote TTL to 5 minutes minimum
- [ ] Verify pricing-service can sustain 2× current quote issuance rate (quotes are now consumed once then invalidated)
- [ ] Test coupon exhaustion during concurrent checkouts: two users with the same single-use coupon — only one should succeed

**Before P4 (order-service cleanup):**
- [ ] Zero traffic on `CHECKOUT_TASK_QUEUE` Temporal task queue for 7 consecutive days
- [ ] All in-flight orders in `PENDING` status older than 30 minutes resolved before deploying

---

## Summary: Key Risk Ranking

| # | Risk | Severity | Phase |
|---|---|---|---|
| 1 | Dual checkout saga — order-service computes price from client-supplied values | 🔴 Critical | P2 |
| 2 | No StalePendingRecoveryJob — stuck AUTHORIZE_PENDING = customer charged, no order | 🔴 Critical | P1 |
| 3 | No OrderCancelled consumer in payment-service — cancelled orders are not refunded | 🔴 Critical | P2 |
| 4 | Idempotency key > 64 chars → VARCHAR truncation → possible double-charge | 🔴 Critical | P1 |
| 5 | Go webhook-service in-memory dedup — multi-instance = duplicate Kafka messages | 🟠 High | P2 |
| 6 | Webhook Path A (Kafka) has no consumer — PSP events dropped | 🟠 High | P2 |
| 7 | Reconciliation-engine reads files, not live DB — mismatches not caught in real-time | 🟠 High | P4 |
| 8 | Temporal workflow reset = new activityId = second authorization | 🟠 High | P1 |
| 9 | Out-of-order webhook events (refund before capture) corrupt payment state | 🟠 High | P2 |
| 10 | No quote token — price can change between step 2 and step 5 of saga | 🟡 Medium | P3 |
| 11 | Checkout-orchestrator blocks HTTP thread for full saga duration | 🟡 Medium | P3 |
| 12 | Standard event envelope missing from outbox events | 🟡 Medium | P3 |
| 13 | Stale PENDING orders (no recovery from Temporal crash between create and place) | 🟡 Medium | P1 |
