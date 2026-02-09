# Checkout Orchestrator Service — Architecture Review

> **Reviewer:** Senior Distributed Systems Architect  
> **Service:** `checkout-orchestrator-service`  
> **Platform:** Instacommerce (Q-Commerce, 20M+ users)  
> **Date:** 2025-07-02  
> **Verdict:** ⚠️ **Conditionally Production-Ready — 11 Critical/High issues must be resolved**

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Business Logic Review](#3-business-logic-review)
4. [SLA & Performance Review](#4-sla--performance-review)
5. [Missing Features for Q-Commerce](#5-missing-features-for-q-commerce)
6. [Security Review](#6-security-review)
7. [Infrastructure & Operability](#7-infrastructure--operability)
8. [Q-Commerce Competitor Comparison](#8-q-commerce-competitor-comparison)
9. [Issue Tracker](#9-issue-tracker)
10. [Recommended Action Plan](#10-recommended-action-plan)

---

## 1. Executive Summary

The checkout orchestrator implements a **Temporal-based saga pattern** with 7 sequential steps. The core flow is structurally sound — it covers cart validation, pricing, inventory reservation, payment authorization, order creation, confirmation, and cart clearing. Temporal provides durable execution guarantees that most hand-rolled saga implementations lack.

However, this review surfaces **11 critical/high-severity issues** that present real risk at 20M+ user scale:

| Severity | Count | Examples |
|----------|-------|---------|
| 🔴 Critical | 4 | Payment double-charge on retry, confirm-step not compensated, idempotency not durable, missing `InventoryReservationResult.reserved` check |
| 🟠 High | 7 | No fraud check, no delivery slot, no address validation, synchronous controller blocking, payment timeout too aggressive, no worker tuning, CORS wildcard |
| 🟡 Medium | 6 | No circuit breaker, no rate limiting, cart clear failure orphans cart, missing heartbeat on long activities, no dead-letter handling, no split payments |
| 🟢 Low | 3 | Structured logging gaps, missing Temporal metrics, no workflow versioning |

---

## 2. Architecture Overview

### Saga Flow (as implemented)

```
┌─────────────┐     ┌──────────────┐     ┌────────────────┐     ┌─────────────────┐
│  1. Validate │────▶│ 2. Calculate │────▶│ 3. Reserve     │────▶│ 4. Authorize    │
│     Cart     │     │    Prices    │     │   Inventory    │     │   Payment       │
└─────────────┘     └──────────────┘     └────────────────┘     └─────────────────┘
                                           compensation:          compensation:
                                           releaseStock()         voidPayment()
                                                                        │
       ┌────────────┐     ┌──────────────┐     ┌──────────────┐         │
       │ 7. Clear   │◀────│ 6. Confirm   │◀────│ 5. Create    │◀────────┘
       │   Cart     │     │  Inv+Payment │     │   Order      │
       └────────────┘     └──────────────┘     └──────────────┘
       (best-effort)      (NO compensation!)    compensation:
                                                cancelOrder()
```

### Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Runtime | Java 21 (Temurin), ZGC | — |
| Framework | Spring Boot (Web, Security, Actuator, Validation) | — |
| Orchestration | Temporal SDK | 1.22.3 |
| Auth | JJWT (RSA public key verification) | 0.12.5 |
| HTTP Clients | Spring `RestTemplate` (per-service, qualified beans) | — |
| Caching | Caffeine (idempotency keys) | 3.1.8 |
| Observability | Micrometer → OpenTelemetry (traces + metrics), Logback | — |
| Container | Multi-stage Docker, Alpine-based JRE, non-root | — |

### Files Reviewed (40 files)

| Category | Files |
|----------|-------|
| Workflow | `CheckoutWorkflow.java`, `CheckoutWorkflowImpl.java` |
| Activities (interfaces) | `CartActivity`, `PricingActivity`, `InventoryActivity`, `PaymentActivity`, `OrderActivity` |
| Activities (impl) | `CartActivityImpl`, `PricingActivityImpl`, `InventoryActivityImpl`, `PaymentActivityImpl`, `OrderActivityImpl` |
| DTOs (10) | `CheckoutRequest`, `CheckoutResponse`, `CartItem`, `CartValidationResult`, `PricingRequest`, `PricingResult`, `InventoryReservationResult`, `PaymentAuthResult`, `OrderCreateRequest`, `OrderCreationResult`, `ErrorResponse`, `ErrorDetail` |
| Config | `TemporalConfig`, `TemporalProperties`, `RestClientConfig`, `CheckoutProperties`, `application.yml` |
| Security | `SecurityConfig`, `JwtAuthenticationFilter`, `JwtService`, `DefaultJwtService`, `JwtKeyLoader`, `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler` |
| Exception | `GlobalExceptionHandler`, `CheckoutException`, `TraceIdProvider` |
| Infra | `Dockerfile`, `build.gradle.kts`, `CheckoutOrchestratorApplication` |

---

## 3. Business Logic Review

### 3.1 Saga Flow Completeness

**Steps implemented:**

| Step | Activity | Status |
|------|----------|--------|
| 1. Validate cart | `cartActivity.validateCart(userId)` | ✅ Present |
| 2. Calculate prices | `pricingActivity.calculatePrice(request)` | ✅ Present |
| 3. Reserve inventory | `inventoryActivity.reserveStock(items)` | ⚠️ **Missing `reserved` check** |
| 4. Authorize payment | `paymentActivity.authorizePayment(amount, method)` | ✅ Present |
| 5. Create order | `orderActivity.createOrder(request)` | ✅ Present |
| 6. Confirm (inventory + payment capture) | `inventoryActivity.confirmStock()` + `paymentActivity.capturePayment()` | ⚠️ See §3.2 |
| 7. Clear cart | `cartActivity.clearCart(userId)` | ✅ Best-effort (acceptable) |

#### 🔴 CRITICAL: `InventoryReservationResult.reserved` is never checked

```java
// CheckoutWorkflowImpl.java:110
InventoryReservationResult reservationResult = inventoryActivity.reserveStock(items);
saga.addCompensation(inventoryActivity::releaseStock, reservationResult.reservationId());
```

The DTO has a `boolean reserved` field but the workflow **never checks** `reservationResult.reserved()`. If the inventory service returns `reserved=false` with a partial reservation ID (e.g., for audit), the saga proceeds to payment for items that aren't actually reserved.

**Fix:** Add validation immediately after `reserveStock()`:
```java
if (!reservationResult.reserved()) {
    return CheckoutResponse.failed("Some items are out of stock");
}
```

### 3.2 Compensation Logic

**Compensation registration order:**

| Step | Compensation Registered | Triggered On Failure Of |
|------|------------------------|------------------------|
| 3 | `inventoryActivity.releaseStock(reservationId)` | Steps 4, 5, 6, or any exception |
| 4 | `paymentActivity.voidPayment(paymentId)` | Steps 5, 6, or any exception |
| 5 | `orderActivity.cancelOrder(orderId)` | Step 6 or any exception |

**What's right:**
- `Saga.Options.Builder().setParallelCompensation(false)` — compensations run sequentially (correct for financial operations)
- Payment decline (non-exception) is handled explicitly with early `saga.compensate()` + return

#### 🔴 CRITICAL: Step 6 (Confirm) has NO compensation

```java
// CheckoutWorkflowImpl.java:148-149
inventoryActivity.confirmStock(reservationResult.reservationId());
paymentActivity.capturePayment(paymentResult.paymentId());
```

**Scenario:** `confirmStock()` succeeds, then `capturePayment()` throws. The outer `catch` calls `saga.compensate()`, which:
1. Cancels the order ✅
2. Voids the payment ✅ (but payment was never captured, so this is a void on an auth — acceptable)
3. Releases inventory ❌ **WRONG** — inventory was already confirmed, `releaseStock()` on a confirmed reservation may fail or be a no-op

**The real danger is the reverse:** `capturePayment()` succeeds, then subsequent code fails. Payment is captured but if the catch block fires, `voidPayment()` is called on an already-captured payment — **you cannot void a captured payment, you must refund it**. The payment service must handle this distinction, or the orchestrator needs `refundPayment()`.

**Fix:** Confirm/capture should be a two-phase approach:
1. Add compensation for `confirmStock` that calls a `revertConfirmation` or `releaseStock` should handle already-confirmed reservations
2. After `capturePayment`, remove the `voidPayment` compensation and add a `refundPayment` compensation instead (or make `voidPayment` in the payment service smart enough to refund if already captured)
3. Consider making confirm+capture atomic or use a sub-saga

### 3.3 Idempotency

#### 🔴 CRITICAL: Idempotency cache is in-memory (Caffeine), not durable

```java
// CheckoutController.java:38-41
private final Cache<String, String> idempotencyCache = Caffeine.newBuilder()
    .maximumSize(100_000)
    .expireAfterWrite(Duration.ofMinutes(30))
    .build();
```

**Problems at 20M+ user scale:**

1. **Pod restart loses all keys** — if the service restarts during peak (deploy, OOM, node drain), every in-flight idempotency key is lost. Users who retry get a second checkout workflow.
2. **Multi-instance deployments** — with N replicas behind a load balancer, a retry request may hit a different pod that doesn't have the key. This defeats idempotency entirely.
3. **30-minute TTL** — a user who starts checkout, gets distracted, and retaps "Place Order" 31 minutes later gets a new workflow.
4. **100K size cap** — at 20M users with even 1% checkout rate during peak, 200K concurrent checkouts overflow the cache, silently evicting valid keys.

**Temporal already solves this!** Temporal enforces workflow ID uniqueness. If you use a deterministic workflow ID (which is already done: `checkout-{userId}-{idempotencyKey}`), starting a duplicate workflow will throw `WorkflowExecutionAlreadyStarted`. The Caffeine cache is redundant and creates a false sense of idempotency.

**Fix:**
```java
// Remove Caffeine cache entirely. Use Temporal's built-in idempotency:
WorkflowOptions options = WorkflowOptions.newBuilder()
    .setWorkflowId(workflowId)
    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
    .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
    .build();
```

Then catch `WorkflowExecutionAlreadyStarted` and return the existing workflow's result.

### 3.4 Workflow ID Generation

```java
String workflowId = "checkout-" + principal + "-" + idempotencyKey;
```

**Issues:**
- If the client doesn't send `Idempotency-Key`, a random UUID is generated server-side — this means every request creates a new workflow, **completely defeating idempotency** for clients that don't know to send the header.
- **Recommendation:** The idempotency key should be mandatory for checkout (not optional). Return 400 if missing. Or derive it deterministically from `userId + cartVersion/hash` so the same cart state always produces the same key.

### 3.5 Timeout Configuration

| Activity | `startToCloseTimeout` | Realistic? |
|----------|----------------------|------------|
| Cart validation | 10s | ✅ Adequate for a Redis/DB-backed cart |
| Price calculation | 10s | ✅ Should be fast (in-memory rules + coupon lookup) |
| Inventory reservation | 15s | ✅ Involves DB write + possible distributed lock |
| Payment authorization | 30s | ⚠️ See below |
| Order creation | 15s | ✅ DB insert |

#### 🟠 HIGH: Payment timeout analysis

30s `startToCloseTimeout` for payment authorization is the right ballpark for Stripe/Razorpay. However:

- **Stripe p99 latency** can spike to 15-20s during incidents. With 3 retry attempts × 30s timeout = **90s worst case for payment alone**
- The **workflow execution timeout is 5 minutes** (`WorkflowExecutionTimeout(Duration.ofMinutes(5))`), so worst-case sum of all activities: 10 + 10 + 15 + 90 + 15 = **140s** (within 5min budget)
- **Missing: `scheduleToCloseTimeout`** — there's no overall timeout for the activity including queue time. If the task queue is backed up, an activity could sit queued for minutes before even starting, and the 30s clock only starts after a worker picks it up.

**Fix:** Add `setScheduleToCloseTimeout(Duration.ofSeconds(45))` to payment activity to cap total time including queue wait.

### 3.6 Retry Policy

| Activity | Max Attempts | Initial Interval | Backoff | `doNotRetry` |
|----------|-------------|-------------------|---------|-------------|
| Cart | 3 | 1s | 2.0x | — |
| Pricing | 3 | 1s | 2.0x | — |
| Inventory | 3 | 1s | 2.0x | `InsufficientStockException` |
| Payment | 3 | 2s | 2.0x | `PaymentDeclinedException` |
| Order | 3 | 1s | 2.0x | — |

#### 🔴 CRITICAL: Payment retry can cause double-charge

The payment activity has `maxAttempts=3`. If `authorizePayment()` succeeds at the payment gateway (Stripe creates a PaymentIntent), but the **response fails to reach the orchestrator** (network timeout, pod killed), Temporal retries the activity. The payment service receives a second `authorizePayment()` call and creates a **second authorization hold** on the customer's card.

**Why `PaymentDeclinedException` in `doNotRetry` doesn't help:** The issue isn't declined payments — it's successful payments where the response was lost.

**Fix:** The `PaymentActivityImpl.authorizePayment()` must use an **idempotency key** when calling the payment gateway:
```java
// PaymentActivityImpl.java — pass Temporal activity ID as idempotency key
String idempotencyKey = Activity.getExecutionContext().getInfo().getActivityId();
restTemplate.postForObject("/api/payments/authorize",
    Map.of("amountCents", amountCents, "paymentMethodId", paymentMethodId,
           "idempotencyKey", idempotencyKey),
    PaymentAuthResult.class);
```

This ensures the payment service (and downstream Stripe/Razorpay) deduplicates the request.

**Additionally:** `capturePayment()` and `voidPayment()` are also retryable (3 attempts) but have **no `doNotRetry` exceptions and no idempotency keys**. A retried capture could capture twice (double-debit). These must also be idempotent at the payment service layer.

### 3.7 Partial Failure: cart.clear() after order creation

```java
// CheckoutWorkflowImpl.java:152-158
try {
    cartActivity.clearCart(request.userId());
} catch (Exception e) {
    Workflow.getLogger(CheckoutWorkflowImpl.class)
        .warn("Failed to clear cart for user {}, continuing", request.userId(), e);
}
```

**Verdict: Acceptable.** This is the correct pattern — cart clearing is a side effect, not a business invariant. If it fails:
- The order is already created and payment captured
- The user sees their order confirmation
- The stale cart can be cleared on next cart access or via a background reconciliation job

**Minor improvement:** Emit a metric/event for cart-clear failures so ops can monitor the rate:
```java
Workflow.getMetricsScope().counter("checkout_cart_clear_failure").inc(1);
```

### 3.8 Controller is Synchronous (Blocking)

```java
// CheckoutController.java:87
CheckoutResponse result = workflow.checkout(request);
```

This is a **synchronous workflow invocation** — the HTTP thread blocks until the entire saga completes (potentially 30-140 seconds). At 20M users with a Tomcat thread pool of ~200, **only ~200 concurrent checkouts can be in flight**. This is a scalability ceiling.

#### 🟠 HIGH: Switch to async workflow start + polling

**Recommended pattern:**
```java
@PostMapping
public ResponseEntity<Map<String, String>> initiateCheckout(...) {
    WorkflowClient.start(workflow::checkout, request);  // non-blocking
    return ResponseEntity.accepted()
        .body(Map.of("workflowId", workflowId, "statusUrl", "/checkout/" + workflowId + "/status"));
}
```

The client then polls `GET /checkout/{workflowId}/status` or subscribes via WebSocket/SSE. This is how DoorDash, Uber, and other high-scale systems handle long-running orchestrations.

---

## 4. SLA & Performance Review

### 4.1 End-to-End Latency Budget

| Step | Timeout | Expected p50 | Expected p99 |
|------|---------|-------------|-------------|
| Cart validation | 10s | ~50ms | ~200ms |
| Price calculation | 10s | ~30ms | ~150ms |
| Inventory reservation | 15s | ~100ms | ~500ms |
| Payment authorization | 30s | ~1s | ~5s |
| Order creation | 15s | ~100ms | ~300ms |
| Inventory confirm | 15s | ~50ms | ~200ms |
| Payment capture | 30s | ~500ms | ~3s |
| Cart clear | 10s | ~30ms | ~100ms |
| **Temporal overhead** | — | ~50ms | ~200ms |
| **Total** | — | **~1.9s** | **~9.6s** |

#### 🟠 HIGH: p99 exceeds 5s target

The p99 estimate of **~9.6s** exceeds the 5s SLA. Payment authorization (p99 ~5s) and payment capture (p99 ~3s) are the dominant contributors.

**Mitigations:**
1. **Pre-authorize payment** — authorize when the user adds a payment method, not during checkout. Checkout only captures. Eliminates the ~5s p99 auth step.
2. **Parallelize confirm+capture** — `confirmStock()` and `capturePayment()` are independent and can run in parallel:
   ```java
   Promise<Void> confirmPromise = Async.procedure(inventoryActivity::confirmStock, reservationId);
   Promise<Void> capturePromise = Async.procedure(paymentActivity::capturePayment, paymentId);
   confirmPromise.get();
   capturePromise.get();
   ```
3. **Async checkout** (see §3.8) — decouple user-facing latency from saga duration.

### 4.2 Temporal Configuration

```yaml
temporal:
  service-address: "${TEMPORAL_HOST:localhost}:7233"
  namespace: instacommerce
  task-queue: CHECKOUT_ORCHESTRATOR_TASK_QUEUE
```

#### 🟠 HIGH: No worker tuning

`TemporalConfig.java` creates a single worker with **all default settings**:

```java
Worker worker = factory.newWorker(temporalProperties.getTaskQueue());
// No WorkerOptions configured!
```

**Temporal defaults:**
- `maxConcurrentActivityExecutionSize` = 200
- `maxConcurrentWorkflowTaskExecutionSize` = 200
- `maxConcurrentLocalActivityExecutionSize` = 200

At 20M users, these defaults may be fine for a single pod, but there's **no explicit configuration**, meaning:
1. No control over thread pool sizing relative to downstream capacity
2. No rate limiting to protect payment/inventory services from thundering herd
3. No sticky workflow configuration for cache efficiency

**Fix:** Add explicit `WorkerOptions`:
```java
WorkerOptions workerOptions = WorkerOptions.newBuilder()
    .setMaxConcurrentActivityExecutionSize(100)     // tune per pod resources
    .setMaxConcurrentWorkflowTaskExecutionSize(200)
    .setMaxTaskQueueActivitiesPerSecond(500)         // rate limit to protect downstreams
    .build();
Worker worker = factory.newWorker(taskQueue, workerOptions);
```

### 4.3 REST Client Timeouts

All 5 downstream services have **identical** timeout configuration:

```yaml
connect-timeout: 5000   # 5 seconds
read-timeout: 10000     # 10 seconds
```

**Issues:**

1. **5s connect timeout is too high** — if a service is down, each connection attempt burns 5s. With 3 Temporal retries, that's 15s of connect timeouts alone. Set to 1-2s.
2. **10s read timeout for all services is one-size-fits-all** — payment needs 15-20s for gateway calls, while cart validation should complete in <1s. Per-service tuning is needed:

| Service | Recommended Connect | Recommended Read |
|---------|-------------------|-----------------|
| Cart | 1000ms | 3000ms |
| Pricing | 1000ms | 3000ms |
| Inventory | 1000ms | 5000ms |
| Payment | 2000ms | 20000ms |
| Order | 1000ms | 5000ms |

3. **No connection pooling** — `RestTemplate` creates a new connection per request. At scale, use `HttpComponentsClientHttpRequestFactory` with Apache HttpClient and connection pool:
   ```java
   PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
   cm.setMaxTotal(200);
   cm.setDefaultMaxPerRoute(50);
   ```

4. **No retry at HTTP level** — relies entirely on Temporal retries. This is fine architecturally (Temporal is the retry layer), but means every transient 503 burns a full Temporal retry attempt.

---

## 5. Missing Features for Q-Commerce

### 5.1 🟠 Delivery Slot Selection — MISSING

**Current state:** `CheckoutRequest` accepts `deliveryAddressId` but there is **no delivery slot/time window selection** anywhere in the flow. The `OrderCreationResult` returns `estimatedDeliveryMinutes` (presumably calculated by the order service), but there's no:
- Slot availability check
- Slot reservation during checkout
- Slot confirmation after order creation

**For Q-commerce (10-minute delivery promise), this is critical.** If the slot isn't reserved during checkout, two users could both check out for the same 10-minute window, and one order will be delayed.

**Recommended placement in saga:**
```
Step 2.5: Reserve delivery slot (after pricing, before inventory)
  - Input: storeId, deliveryAddressId, estimatedItems
  - Output: slotId, estimatedDeliveryTime
  - Compensation: releaseSlot(slotId)
```

### 5.2 🟠 Fraud Check — MISSING

**Current state:** No fraud scoring, risk assessment, or velocity checks anywhere in the checkout flow.

**Risk at 20M users:** Without fraud checks:
- Stolen credit cards can complete checkout instantly
- Bot-driven order flooding (competitive sabotage)
- Promo/coupon abuse at scale
- Account takeover → unauthorized orders

**Recommended placement:** Between pricing and payment (Step 3.5):
```
Step 3.5: Fraud scoring
  - Input: userId, items, totalCents, paymentMethodId, deliveryAddressId, deviceFingerprint, IP
  - Output: riskScore, decision (ALLOW / REVIEW / BLOCK)
  - If BLOCK: fail checkout immediately (no compensation needed if before inventory)
  - If REVIEW: flag order for manual review post-creation
```

**Competitor reference:** DoorDash runs fraud inline with <100ms p99 using a pre-computed risk score that's ready before checkout even starts.

### 5.3 🟠 Address Validation / Serviceability — MISSING

**Current state:** `deliveryAddressId` is passed through to pricing and order creation, but there's **no explicit serviceability check** — is this address within the dark store's delivery radius? Is the pincode serviceable?

**Risk:** An order gets created and payment captured for an address that can't be delivered to. This requires a refund + cancellation — bad UX and operational waste.

**Recommended placement:** Step 1.5 (after cart validation, before pricing):
```
Step 1.5: Validate delivery address
  - Input: deliveryAddressId, storeId
  - Output: serviceable (boolean), estimatedDistance, deliveryZone
  - If not serviceable: fail fast with "Address not in delivery range"
```

### 5.4 🟡 Payment Method Selection — LIMITED

**Current state:** `CheckoutRequest` accepts a single `paymentMethodId` string.

**Missing for Q-commerce:**
- **Split payments** (wallet balance + card for remainder) — Zepto, Blinkit, Swiggy all support this
- **Cash on Delivery (COD)** — no COD flow, which skips payment authorization entirely
- **UPI intent/collect** — dominant in India, requires async payment confirmation
- **Pay Later / BNPL** — growing in Q-commerce

**Recommended DTO change:**
```java
public record CheckoutRequest(
    String userId,
    List<PaymentSplit> payments,  // [{method: "WALLET", amount: 5000}, {method: "pm_xxx", amount: 3500}]
    String couponCode,
    String deliveryAddressId,
    String deliverySlotId         // new
) {}
```

### 5.5 🟡 Order Confirmation Response — ADEQUATE but minimal

**Current response:**
```json
{
  "orderId": "ord_abc123",
  "status": "COMPLETED",
  "totalCents": 85000,
  "estimatedDeliveryMinutes": 10
}
```

**Missing fields that Q-commerce apps typically return:**
- `estimatedDeliveryTime` (ISO 8601 timestamp, not just minutes)
- `orderTrackingUrl`
- `storeDetails` (name, address for "Your order from...")
- `itemsSummary` (count, item names for confirmation screen)
- `paymentSummary` (method used, last 4 digits)
- `deliveryAddress` (for "Delivering to...")

---

## 6. Security Review

### 6.1 Authentication — Good

- RSA public key JWT verification ✅
- Stateless session management ✅
- Actuator endpoints excluded from auth ✅
- Proper 401/403 error responses with trace IDs ✅

### 6.2 🟠 CORS Configuration — Too Permissive

```java
configuration.setAllowedOriginPatterns(List.of("*"));
configuration.setAllowCredentials(true);
```

**`allowedOriginPatterns("*")` with `allowCredentials(true)` is dangerous.** Any origin can make credentialed requests to the checkout API. In production, restrict to your known frontend domains.

### 6.3 Authorization — Insufficient

The `@AuthenticationPrincipal String principal` is extracted but **never validated against `request.userId()`**. A user with a valid JWT for user A can submit a checkout for user B:

```java
// CheckoutController.java:50-53 — principal is used for workflow ID but not validated
public ResponseEntity<CheckoutResponse> initiateCheckout(
    @Valid @RequestBody CheckoutRequest request,
    @AuthenticationPrincipal String principal, ...) {
    // request.userId() could be ANY user ID — not checked against principal!
```

**Fix:**
```java
if (!principal.equals(request.userId())) {
    throw new CheckoutException("FORBIDDEN", "Cannot checkout for another user", HttpStatus.FORBIDDEN);
}
```

Or better: **remove `userId` from the request body entirely** and always use `principal`.

### 6.4 Rate Limiting — MISSING

No rate limiting on the checkout endpoint. A single user or bot can flood the system with checkout requests. At minimum, add per-user rate limiting (e.g., 5 checkouts per minute per user).

---

## 7. Infrastructure & Operability

### 7.1 Dockerfile — Good

- Multi-stage build ✅
- Non-root user ✅
- ZGC garbage collector (low-latency, ideal for checkout) ✅
- Health check configured ✅
- `MaxRAMPercentage=75%` ✅

### 7.2 Observability

**What's configured:**
- OpenTelemetry traces + metrics via Micrometer ✅
- Prometheus metrics endpoint ✅
- Liveness + readiness probes ✅
- Logback structured logging (logstash encoder in deps) ✅

**What's missing:**
- **No Temporal-specific metrics** — workflow duration, activity latency, retry count, failure rate
- **No custom checkout metrics** — checkout success rate, step-by-step latency, compensation frequency
- **Activity heartbeating** — long-running activities (payment: 30s timeout) should heartbeat to detect zombie activities

### 7.3 Graceful Shutdown

```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

The `WorkerFactory` is registered with `destroyMethod = "shutdown"` ✅. This ensures in-flight activities complete before the pod is terminated. 30s shutdown grace period should be sufficient given activity timeouts.

### 7.4 No Workflow Versioning

The `CheckoutWorkflowImpl` has no `Workflow.getVersion()` calls. When the saga logic is updated (e.g., adding fraud check step), existing in-flight workflows will break because their history doesn't match the new code.

**Fix:** Before any change to the workflow, add versioning:
```java
int version = Workflow.getVersion("AddFraudCheck", Workflow.DEFAULT_VERSION, 1);
if (version >= 1) {
    // new flow with fraud check
}
```

---

## 8. Q-Commerce Competitor Comparison

| Capability | Instacommerce (Current) | Zepto | Blinkit | DoorDash |
|-----------|------------------------|-------|---------|----------|
| **Checkout latency target** | ~2s p50 / ~10s p99 | <3s end-to-end | <3s end-to-end | <5s end-to-end |
| **Orchestration** | Temporal (saga) | Custom state machine | Custom saga | Cadence (Temporal predecessor) |
| **Payment model** | Auth → Capture (sync) | Pre-authorized, capture at dispatch | Auth at checkout, capture at dispatch | Pre-authorized |
| **Inventory model** | Reserve → Confirm | Optimistic (reserve at cart) | Reserve at checkout | Optimistic with fallback |
| **Delivery slots** | ❌ Not implemented | Implicit (10-min promise) | Slot-based selection | Time window selection |
| **Fraud check** | ❌ Not implemented | Inline (<100ms) | Pre-computed risk score | Inline (Cadence activity) |
| **Payment methods** | Single card/method | Card + UPI + Wallet | COD + UPI + Wallet + Card | Card + Gift card + DashPass credits |
| **Split payments** | ❌ Not supported | Wallet + Card | Wallet + Card + COD | Credits + Card |
| **Address validation** | ❌ Not implemented | At cart/address entry | At address entry | At address entry |
| **Idempotency** | In-memory (broken at scale) | Request-level (Redis) | Request-level (DB) | Cadence workflow ID |
| **Async checkout** | ❌ Synchronous HTTP | Async + push notification | Async + polling | Async + push |
| **Checkout response** | Minimal (4 fields) | Rich (tracking, ETA, store) | Rich (tracking, ETA, rider) | Rich (tracking, ETA, dasher) |

### Key Gaps vs. Competitors

1. **Pre-authorization** — Zepto and DoorDash authorize payment when the user adds a card, not during checkout. This removes 3-5s from checkout latency.
2. **Optimistic inventory** — Zepto reserves inventory when items are added to cart, making checkout instant. Current implementation reserves at checkout time.
3. **Async checkout** — All competitors use async checkout with push-based confirmation. Instacommerce blocks the HTTP thread.
4. **Rich confirmation** — Competitors return tracking URLs, rider assignment ETA, and store details. Current implementation returns only 4 fields.

---

## 9. Issue Tracker

| # | Severity | Category | Issue | File | Line(s) |
|---|----------|----------|-------|------|---------|
| 1 | 🔴 Critical | Business Logic | `InventoryReservationResult.reserved` field never checked — proceeds with unreserved inventory | `CheckoutWorkflowImpl.java` | 110 |
| 2 | 🔴 Critical | Compensation | Step 6 (confirmStock + capturePayment) has no compensation — confirmed inventory can't be released, captured payment can't be voided (must be refunded) | `CheckoutWorkflowImpl.java` | 148-149 |
| 3 | 🔴 Critical | Idempotency | In-memory Caffeine cache for idempotency keys lost on restart, not shared across pods — use Temporal workflow ID uniqueness instead | `CheckoutController.java` | 38-41 |
| 4 | 🔴 Critical | Payment Safety | Payment retry (3 attempts) without idempotency key → double authorization hold on customer's card | `CheckoutWorkflowImpl.java` | 64-75, `PaymentActivityImpl.java` |
| 5 | 🟠 High | Missing Feature | No delivery slot reservation in checkout flow | Entire saga | — |
| 6 | 🟠 High | Missing Feature | No fraud check / risk scoring before payment | Entire saga | — |
| 7 | 🟠 High | Missing Feature | No address serviceability validation | Entire saga | — |
| 8 | 🟠 High | Performance | Synchronous workflow invocation blocks HTTP thread — max ~200 concurrent checkouts | `CheckoutController.java` | 87 |
| 9 | 🟠 High | Performance | p99 latency ~9.6s exceeds 5s target — parallelize confirm/capture, consider pre-auth | `CheckoutWorkflowImpl.java` | 148-149 |
| 10 | 🟠 High | Temporal | No worker tuning (concurrency, rate limiting) — defaults may overwhelm downstream services at scale | `TemporalConfig.java` | 46 |
| 11 | 🟠 High | Security | CORS allows all origins with credentials — must restrict to known frontend domains | `SecurityConfig.java` | 41-44 |
| 12 | 🟡 Medium | Security | `request.userId()` not validated against JWT principal — user A can checkout for user B | `CheckoutController.java` | 50-53 |
| 13 | 🟡 Medium | Resilience | No circuit breaker on downstream REST calls — cascading failures possible | `RestClientConfig.java` | — |
| 14 | 🟡 Medium | Security | No rate limiting on checkout endpoint | `CheckoutController.java` | — |
| 15 | 🟡 Medium | Observability | No heartbeating on payment activity (30s timeout) — zombie activities undetected | `PaymentActivityImpl.java` | — |
| 16 | 🟡 Medium | Payment | No split payment support (wallet + card), no COD, no UPI async flow | `CheckoutRequest.java` | — |
| 17 | 🟡 Medium | Resilience | REST client connect timeout 5s too high — set to 1-2s to fail fast on dead services | `application.yml` | 18-35 |
| 18 | 🟢 Low | Operability | No workflow versioning — in-flight workflows will break on saga logic changes | `CheckoutWorkflowImpl.java` | — |
| 19 | 🟢 Low | Observability | No custom checkout metrics (success rate, step latency, compensation count) | — | — |
| 20 | 🟢 Low | Performance | No HTTP connection pooling — new TCP connection per downstream request | `RestClientConfig.java` | — |

---

## 10. Recommended Action Plan

### Phase 1: Pre-Production Must-Fix (Week 1-2)

| Priority | Issue # | Action |
|----------|---------|--------|
| P0 | #1 | Add `reserved` check after `reserveStock()` |
| P0 | #4 | Pass Temporal activity ID as idempotency key to payment service |
| P0 | #3 | Remove Caffeine cache, use Temporal `WorkflowIdConflictPolicy.USE_EXISTING` |
| P0 | #2 | Handle confirm/capture compensation correctly (refund vs void distinction) |
| P0 | #12 | Validate `request.userId() == principal` or remove userId from request body |
| P1 | #11 | Restrict CORS to production frontend domains |
| P1 | #8 | Switch to async workflow start + polling/SSE for checkout response |
| P1 | #10 | Add explicit `WorkerOptions` with concurrency and rate limits |

### Phase 2: Q-Commerce Parity (Week 3-4)

| Priority | Issue # | Action |
|----------|---------|--------|
| P1 | #6 | Add fraud scoring activity (async, pre-computed risk score) |
| P1 | #7 | Add address serviceability check as first saga step |
| P1 | #5 | Add delivery slot reservation activity |
| P1 | #9 | Parallelize confirmStock + capturePayment; evaluate pre-authorization model |
| P2 | #14 | Add per-user rate limiting (Redis-based token bucket) |
| P2 | #17 | Tune REST client timeouts per service |

### Phase 3: Scale Hardening (Week 5-6)

| Priority | Issue # | Action |
|----------|---------|--------|
| P2 | #13 | Add Resilience4j circuit breakers per downstream service |
| P2 | #15 | Add activity heartbeating for payment (30s) |
| P2 | #16 | Support split payments, COD, UPI async flows |
| P2 | #20 | Replace `RestTemplate` with `WebClient` or Apache HttpClient with connection pool |
| P3 | #18 | Add `Workflow.getVersion()` before any saga logic change |
| P3 | #19 | Add Micrometer counters/timers for each saga step |

### Suggested Saga Flow (Post-Fixes)

```
 1. Validate cart
 2. Validate delivery address (serviceability) ← NEW
 3. Reserve delivery slot ← NEW
 4. Calculate prices
 5. Reserve inventory
 6. Fraud scoring ← NEW
 7. Authorize payment (with idempotency key)
 8. Create order
 9. Confirm inventory ┐
10. Capture payment   ┘ (parallel, with proper compensation)
11. Clear cart (best-effort)
```

---

*End of review. For questions or clarifications, reach out to the architecture team.*
