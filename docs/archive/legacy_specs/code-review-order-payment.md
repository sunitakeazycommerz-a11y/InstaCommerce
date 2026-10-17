# Code Review Report: order-service & payment-service

**Reviewer**: Staff Engineer Review  
**Scope**: All Java, SQL, YAML, Gradle files under `services/order-service/` and `services/payment-service/`  
**Reference docs**: `docs/04-order-temporal-saga.md`, `docs/05-payment-refunds.md`, `docs/09-contracts-events-protobuf.md`  
**Date**: 2025-07-17

---

## Executive Summary

Both services demonstrate solid foundational architecture: Temporal saga orchestration, outbox pattern for event publishing, double-entry ledger, and clean PSP abstraction. However, several **CRITICAL** and **HIGH** severity issues exist around transaction safety, race conditions, event contract mismatches, and missing security controls that must be resolved before production readiness at 20M+ user scale.

---

## 1. Transaction Safety

### CRITICAL — PSP Call Inside @Transactional Boundary (Payment Authorize)
**File**: `payment-service/.../service/PaymentService.java:46-96`  
**Issue**: The `authorize()` method is annotated `@Transactional` but makes an external HTTP call to Stripe (`paymentGateway.authorize()`) at line 59 inside the transaction. If the PSP call succeeds but the subsequent DB save fails (e.g., constraint violation, connection loss), the payment is authorized at Stripe but not recorded locally. The transaction rolls back, losing the `psp_reference`. There is no compensation or reconciliation mechanism.  
**Impact**: Money is held on customer's card with no local record. At scale, this creates ghost authorizations and reconciliation nightmares.  
**Recommendation**: Split into two phases: (1) call PSP outside the transaction, (2) persist result inside a transaction. Or use a "pending" record saved before the PSP call.  
**Severity**: **CRITICAL**

### CRITICAL — Same Issue in capture(), voidAuth()
**File**: `payment-service/.../service/PaymentService.java:98-157`  
**Issue**: `capture()` (line 109) and `voidAuth()` (line 144) also make PSP calls inside `@Transactional`. If DB commit fails after a successful PSP capture, the payment is captured at Stripe but locally still shows AUTHORIZED.  
**Severity**: **CRITICAL**

### CRITICAL — Same Issue in RefundService.refund()
**File**: `payment-service/.../service/RefundService.java:48-118`  
**Issue**: PSP refund call at line 72 is inside `@Transactional`. If PSP refund succeeds but the local DB save at line 84-92 fails, money is refunded at Stripe but not recorded locally. The DB constraint `chk_refund_le_captured` may also reject the update, rolling back the whole transaction while Stripe has already processed the refund.  
**Severity**: **CRITICAL**

### HIGH — Refund Race Condition (No SELECT FOR UPDATE)
**File**: `payment-service/.../service/RefundService.java:58-68`  
**Issue**: The payment is loaded with a simple `findById()` (no pessimistic lock). Two concurrent refund requests can both read `refundedCents=0`, both pass the `available` check at line 66, and both proceed to issue PSP refunds, resulting in a double-refund exceeding captured amount. While the DB constraint `chk_refund_le_captured` will catch one of them at commit time, the PSP refund has already been issued.  
**Recommendation**: Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the payment lookup, or use `SELECT ... FOR UPDATE` via a custom query.  
**Severity**: **HIGH**

### HIGH — Checkout Workflow Capture-Before-OrderPlaced Ordering Risk
**File**: `order-service/.../workflow/CheckoutWorkflowImpl.java:96-111`  
**Issue**: The workflow captures payment (line 100) before updating the order status to PLACED (line 111). If the `updateOrderStatus` activity fails after capture, the saga compensates by cancelling the order, but the payment is already captured and there's no reverse-capture compensation registered. The order ends up in CANCELLED state but payment is captured.  
**Recommendation**: Add a refund/void compensation after capturePayment, or reorder so that status update happens before capture (with idempotent retry).  
**Severity**: **HIGH**

### MEDIUM — Outbox `sent` Column Not in Contracts Schema
**File**: `order-service/.../domain/model/OutboxEvent.java:36`, `payment-service/.../domain/model/OutboxEvent.java:36`  
**Issue**: Both OutboxEvent entities have a `sent` boolean field (line 36). The outbox table DDL (V4__create_outbox.sql) includes `sent BOOLEAN NOT NULL DEFAULT false`. However, per `docs/09-contracts-events-protobuf.md` line 76-83, the canonical outbox schema does NOT have a `sent` column — Debezium is supposed to consume via CDC and the row is never marked "sent". This `sent` column suggests a polling-based approach that could conflict with Debezium's CDC if both are active, causing duplicate event publishing.  
**Severity**: **MEDIUM**

### MEDIUM — Missing Optimistic Locking on Payment in Webhook Handler
**File**: `payment-service/.../webhook/WebhookEventHandler.java:44-67`  
**Issue**: The webhook handler loads a payment by PSP reference and updates its status, but doesn't check the `version` field for optimistic concurrency. If a webhook arrives concurrently with a local capture/void call, one could overwrite the other's state without conflict detection. The `@Version` annotation on Payment entity will throw `OptimisticLockException`, but the webhook handler has no retry logic — it will silently fail (caught by the controller returning 200 OK).  
**Severity**: **MEDIUM**

---

## 2. Payment Security

### HIGH — Stripe.apiKey Set as Global Mutable Static
**File**: `payment-service/.../gateway/StripePaymentGateway.java:29,58,74,87`  
**Issue**: `Stripe.apiKey = apiKey` is set on every method call. `Stripe.apiKey` is a **global static field** — this is not thread-safe. In a multi-threaded environment with potential future multi-tenant support, this is dangerous. Modern Stripe SDK recommends using `RequestOptions` for per-request API keys.  
**Recommendation**: Remove all `Stripe.apiKey = apiKey` assignments. Pass API key via `RequestOptions.builder().setApiKey(apiKey)` on each call.  
**Severity**: **HIGH**

### HIGH — Webhook Signature Header Marked `required = false`
**File**: `payment-service/.../controller/WebhookController.java:27-28`  
**Issue**: `@RequestHeader(value = "Stripe-Signature", required = false)` allows requests without a signature header to reach the handler. While `WebhookSignatureVerifier.verify()` returns `false` for null signatures, this still allows unauthenticated requests to hit the JSON parsing and payment lookup code. It should be `required = true` to reject at the framework level.  
**Severity**: **HIGH**

### MEDIUM — Webhook Returns 200 OK Even on Processing Failures
**File**: `payment-service/.../webhook/WebhookEventHandler.java:30-33`  
**Issue**: If JSON parsing fails or pspReference is missing, the handler returns silently (log + return). The controller always returns 200 OK. Stripe will stop retrying. If a legitimate webhook had a transient parse issue, the event is lost forever.  
**Recommendation**: Return 4xx/5xx for recoverable failures so Stripe retries.  
**Severity**: **MEDIUM**

### MEDIUM — No Webhook Idempotency Tracking
**File**: `payment-service/.../webhook/WebhookEventHandler.java`  
**Issue**: There's no deduplication of webhook event IDs. Stripe may deliver the same event multiple times. Without tracking processed event IDs, the same webhook could be processed twice, potentially causing issues (though current handlers are mostly idempotent by status checks).  
**Severity**: **MEDIUM**

### LOW — PCI Compliance — pspReference Exposed in PaymentResponse
**File**: `payment-service/.../dto/response/PaymentResponse.java:8`  
**Issue**: `pspReference` (Stripe PaymentIntent ID like `pi_xxx`) is returned to callers. While this is not PAN data and is acceptable per PCI DSS, exposing PSP internal references unnecessarily increases attack surface. Consider if this needs to be in the response for non-admin callers.  
**Severity**: **LOW**

---

## 3. Data Integrity

### HIGH — Double-Entry Ledger Account Names Inconsistent
**File**: `payment-service/.../service/PaymentService.java:80-81, 116-117, 150-151`  
**Issue**: Authorization creates entries for accounts `customer_receivable` ↔ `authorization_hold`. Capture creates `authorization_hold` ↔ `merchant_payable`. Void creates `authorization_hold` ↔ `customer_receivable`. However, the **doc** (`docs/05-payment-refunds.md` lines 274-276, 292-293, 311-312) specifies different account names: just `customer_receivable` DEBIT for auth, `merchant_payable` CREDIT for capture, and `customer_receivable` CREDIT for void — with no `authorization_hold` intermediate account. The actual code uses a proper T-account model (better), but **doc and code are out of sync**, which will confuse auditors and new developers.  
**Severity**: **HIGH** (documentation/implementation drift in financial logic)

### HIGH — Refund Ledger Entries Use Wrong Debit/Credit Direction
**File**: `payment-service/.../service/RefundService.java:93-94`  
**Issue**: The refund creates a double-entry with DEBIT=`refund` and CREDIT=`merchant_payable`. For a refund, the correct accounting is: DEBIT `merchant_payable` (reducing what we owe merchant) and CREDIT `refund` or `customer_receivable` (money returned to customer). The current code debits `refund` and credits `merchant_payable`, which **increases** merchant_payable instead of decreasing it. This means the ledger balance will never reconcile correctly.  
**Severity**: **HIGH**

### MEDIUM — Order State Machine Missing Transitions
**File**: `order-service/.../domain/statemachine/OrderStateMachine.java:13`  
**Issue**: `PACKED → OUT_FOR_DELIVERY` is the only allowed transition from PACKED. However, there's no `PACKED → CANCELLED` transition. In Q-commerce, orders may need cancellation even after packing (e.g., customer cancels before dispatch, quality issue found). The state machine blocks this legitimate business scenario.  
**Severity**: **MEDIUM**

### MEDIUM — No Database Constraint Enforcing State Machine
**File**: `order-service/.../resources/db/migration/V1__create_orders.sql`  
**Issue**: The state machine is only enforced in Java code (`OrderStateMachine.validate()`). A direct DB update (migration, manual fix, another service) could set an invalid state. Consider a DB trigger or at minimum document that the Java layer is the sole guard.  
**Severity**: **MEDIUM**

### LOW — lineTotalCents Not Validated Against quantity × unitPriceCents
**File**: `order-service/.../dto/request/CartItem.java`  
**Issue**: `lineTotalCents` is accepted from the client without validation that `lineTotalCents == quantity × unitPriceCents`. A malicious or buggy client could send inconsistent values. The server should either compute `lineTotalCents` or validate the invariant.  
**Severity**: **LOW**

---

## 4. Scalability

### HIGH — RateLimitService Uses Unbounded ConcurrentHashMap
**File**: `order-service/.../service/RateLimitService.java:15`  
**Issue**: `ConcurrentHashMap<String, RateLimiter> limiters` grows without bound — one entry per `userId`. With 20M+ users, this leaks memory indefinitely. There's no eviction, TTL, or size cap.  
**Recommendation**: Use a bounded cache (Caffeine with expireAfterAccess) or a distributed rate limiter (Redis).  
**Severity**: **HIGH**

### HIGH — No Connection Pool Configuration
**File**: `order-service/.../resources/application.yml`, `payment-service/.../resources/application.yml`  
**Issue**: Neither service configures HikariCP connection pool settings (max pool size, min idle, connection timeout, max lifetime). Spring Boot defaults to `maximumPoolSize=10`, which is far too low for a 20M-user platform. Under load, threads will block waiting for connections.  
**Recommendation**: Configure `spring.datasource.hikari.maximum-pool-size`, `minimum-idle`, `connection-timeout`, `max-lifetime`.  
**Severity**: **HIGH**

### MEDIUM — No Database Partitioning Strategy for Orders
**File**: `order-service/.../resources/db/migration/V1__create_orders.sql`  
**Issue**: The `orders` table will grow unbounded. At 20M+ users with multiple orders each, this table will have hundreds of millions of rows. There's no partitioning (e.g., by `created_at` range) and no archival strategy.  
**Severity**: **MEDIUM**

### MEDIUM — Outbox Table No Cleanup/Archival
**File**: `order-service/.../resources/db/migration/V4__create_outbox.sql`, `payment-service/.../resources/db/migration/V4__create_outbox.sql`  
**Issue**: Outbox events accumulate forever. With Debezium CDC, the `sent` column is not set by Debezium — rows remain. Even with polling, there's no TTL or cleanup job (unlike audit_log which has AuditLogCleanupJob in payment-service). This will bloat the table and slow down the partial index `idx_outbox_unsent`.  
**Severity**: **MEDIUM**

### MEDIUM — Audit Log Cleanup Only in Payment Service
**File**: `payment-service/.../service/AuditLogCleanupJob.java` — exists; `order-service/` — no equivalent  
**Issue**: Payment service has `AuditLogCleanupJob` with configurable retention. Order service has no such cleanup, meaning its `audit_log` table grows unbounded.  
**Severity**: **MEDIUM**

### LOW — RestTemplate Used Instead of WebClient
**File**: `order-service/.../client/RestPaymentClient.java`, `RestInventoryClient.java`, `RestCartClient.java`  
**Issue**: All inter-service HTTP clients use blocking `RestTemplate`. For high-throughput async processing within Temporal activities, non-blocking `WebClient` would be more efficient. However, since these run inside Temporal activity threads, blocking is acceptable but limits throughput per worker.  
**Severity**: **LOW**

---

## 5. Event Contract Compliance

### CRITICAL — PaymentCaptured Outbox Payload Uses `amountCents` but Doc Schema Uses `amount`
**File**: `payment-service/.../service/PaymentService.java:119-122` vs `docs/05-payment-refunds.md:296` vs `contracts/.../schemas/payments/PaymentCaptured.v1.json`  
**Issue**: Three sources, three different field names:
- **Actual code** (PaymentService.java:121): publishes `"amountCents"` in the outbox payload
- **Doc** (05-payment-refunds.md line 296): shows `"amount"` in the outbox.publish call
- **JSON Schema** (PaymentCaptured.v1.json): requires `"amountCents"` (integer)
The code matches the JSON schema, but **PaymentVoided** outbox event at line 155 uses `"amount"` instead of `"amountCents"`, which is **inconsistent** within the same service.  
**Severity**: **CRITICAL** (PaymentVoided event has no schema and uses `amount` — consumers expecting `amountCents` will get null)

### HIGH — OrderPlaced Outbox Payload Missing Required Schema Fields
**File**: `order-service/.../service/OrderService.java:181-205` vs `contracts/.../schemas/orders/OrderPlaced.v1.json`  
**Issue**: The JSON schema requires `"totalCents"` (line 6 of schema). The code publishes `"totalCents"` ✓. However, the schema has `"items"` requiring `"sku"` in each item object (line 19), but the code's `buildOrderPlacedPayload()` (line 202) does NOT include `"sku"` (only `productId`, `productName`, `quantity`, `unitPriceCents`, `lineTotalCents`). Schema validation will fail for any consumer validating against the contract.  
**Severity**: **HIGH**

### HIGH — Doc Schema vs Actual JSON Schema Mismatch for PaymentCaptured
**File**: `docs/09-contracts-events-protobuf.md:197-207` vs `contracts/.../schemas/payments/PaymentCaptured.v1.json`  
**Issue**: The doc at line 199 specifies required field `"amount"` (type number). The actual JSON schema file specifies `"amountCents"` (type integer). The doc also requires `"pspReference"` and `"capturedAt"` which are NOT in the actual JSON schema. This means the doc is wrong or the schema is incomplete.  
**Severity**: **HIGH**

### MEDIUM — PaymentAuthorized Outbox Event Not Matching Schema
**File**: `payment-service/.../service/PaymentService.java:83-87` vs `contracts/.../schemas/payments/PaymentAuthorized.v1.json`  
**Issue**: The code publishes `orderId`, `paymentId`, `amountCents`, `currency` — this matches the schema. However, the `orderId` and `paymentId` are serialized as UUID objects (Java `UUID.toString()` via Jackson), while the schema specifies `"format": "uuid"` with `"type": "string"`. Jackson serializes UUID as a string by default, so this is fine. ✓ No issue.

### MEDIUM — OrderCancelled Outbox Payload Missing Fields
**File**: `order-service/.../service/OrderService.java:129-130` vs `contracts/.../schemas/orders/OrderCancelled.v1.json`  
**Issue**: The schema requires `orderId` and `reason`. The code publishes both ✓. However, the `orderId` is published as a UUID object. Jackson will serialize it as a string, matching the schema. ✓ Acceptable.

### MEDIUM — No PaymentVoided or PaymentFailed Schema in Contracts
**File**: `contracts/src/main/resources/schemas/payments/` — missing `PaymentVoided.v1.json`  
**Issue**: The payment service publishes `PaymentVoided` events (PaymentService.java:152), but there's no corresponding JSON schema in the contracts module. Consumers have no contract to validate against. `PaymentFailed.v1.json` exists but is never published by the code.  
**Severity**: **MEDIUM**

---

## 6. Error Handling

### HIGH — PaymentGatewayException Leaks Stripe Internal Details
**File**: `payment-service/.../gateway/StripePaymentGateway.java:51,67,79,99`  
**Issue**: `PaymentGatewayException` wraps the original `StripeException` with its message exposed. The `GlobalExceptionHandler` returns this in the HTTP response body. Stripe error messages may contain internal details (merchant ID, charge IDs). At minimum, sanitize PSP error messages before returning to callers.  
**Severity**: **HIGH**

### MEDIUM — Checkout Workflow Catches All Exceptions for Compensation
**File**: `order-service/.../workflow/CheckoutWorkflowImpl.java:115-119`  
**Issue**: The catch block catches `Exception`, which is overly broad. This means any exception (including NullPointerException from a bug) triggers saga compensation. While this is safe from a consistency perspective, it makes debugging harder because the original exception type is lost — only `e.getMessage()` is preserved in the `CheckoutResult`. Consider logging the exception class name.  
**Severity**: **MEDIUM**

### MEDIUM — IdentityEventConsumer Re-throws as IllegalStateException
**File**: `order-service/.../consumer/IdentityEventConsumer.java:40-41`  
**Issue**: On any processing failure, the consumer throws `IllegalStateException`, which will cause the Kafka consumer to fail and potentially enter a retry loop. For poison messages (malformed JSON), this creates an infinite retry loop. A DLQ strategy or skip-after-N-retries is needed.  
**Severity**: **MEDIUM**

### LOW — No Exception Handler for PaymentGatewayException in Order Service
**File**: `order-service/.../exception/GlobalExceptionHandler.java`  
**Issue**: If an inter-service call to payment-service returns a 500 error, the `RestTemplate` throws `HttpServerErrorException`, which falls through to the generic `Exception` handler returning a generic "INTERNAL_ERROR". Consider mapping upstream 500s to a more descriptive error.  
**Severity**: **LOW**

---

## 7. API Design

### HIGH — Admin Cancel Endpoint Missing @PreAuthorize
**File**: `order-service/.../controller/AdminOrderController.java:30-36`  
**Issue**: The `cancelOrder()` method at line 30 does NOT have `@PreAuthorize("hasRole('ADMIN')")`. While the `SecurityConfig` at line 29 protects `POST /admin/**` with `hasRole("ADMIN")`, the `getOrder()` at line 39 has `@PreAuthorize("hasRole('ADMIN')")` redundantly. The `updateStatus()` at line 45 also lacks `@PreAuthorize`. The `SecurityConfig` pattern `POST /admin/**` only protects POST — GET `/admin/orders/{id}` is only protected by `@PreAuthorize` on the method. This is inconsistent and fragile; rely on either method-level or URL-level security, not a mix.  
**Severity**: **HIGH**

### HIGH — Admin GET on AdminOrderController Not Protected by URL Pattern
**File**: `order-service/.../config/SecurityConfig.java:29`  
**Issue**: `requestMatchers(HttpMethod.POST, "/admin/**").hasRole("ADMIN")` only protects POST requests to `/admin/**`. The GET endpoint `AdminOrderController.getOrder()` (GET `/admin/orders/{id}`) falls through to `.anyRequest().authenticated()`, meaning any authenticated user can access admin order details. The `@PreAuthorize("hasRole('ADMIN')")` on the method saves it, but only because `@EnableMethodSecurity` is active.  
**Recommendation**: Change to `.requestMatchers("/admin/**").hasRole("ADMIN")` (without HTTP method restriction).  
**Severity**: **HIGH**

### MEDIUM — Checkout Endpoint Runs Workflow Synchronously
**File**: `order-service/.../controller/CheckoutController.java:66`  
**Issue**: `workflow.execute(resolved)` is a **synchronous** blocking call — the HTTP request blocks until the entire Temporal workflow completes (inventory reserve + payment authorize + order create + confirm + capture + cart clear + status update). This can take 10-30+ seconds. At 20M users with burst checkout traffic, this ties up Tomcat threads.  
**Recommendation**: Use `WorkflowClient.start()` for async execution and return the workflow ID immediately. Let clients poll for status via the `getStatus()` query method.  
**Severity**: **MEDIUM**

### MEDIUM — Payment Service Internal Endpoints Not Separated from Admin
**File**: `payment-service/.../config/SecurityConfig.java:29`  
**Issue**: Refund endpoint requires `ROLE_ADMIN` via `.requestMatchers(HttpMethod.POST, "/payments/*/refund").hasRole("ADMIN")`. But authorize/capture/void are accessible by any authenticated user. Per the docs, these should be internal-only (service-to-service). There's no service-account-specific role check. Any user with a valid JWT can call `/payments/authorize`.  
**Severity**: **MEDIUM**

### LOW — OrderStatusUpdateRequest Accepts Arbitrary Status Strings
**File**: `order-service/.../dto/request/OrderStatusUpdateRequest.java`  
**Issue**: The `status` field is a free-form `String` with only `@NotBlank`. An invalid status like `"BANANA"` will cause `IllegalArgumentException` at `OrderStatus.valueOf()` (AdminOrderController line 51), which is caught by `GlobalExceptionHandler` but returns a generic "Invalid input" error. Consider using an enum in the DTO or adding a `@Pattern` constraint.  
**Severity**: **LOW**

---

## 8. Missing Features

### HIGH — No User-Initiated Order Cancellation
**File**: `order-service/.../controller/OrderController.java`  
**Issue**: The `OrderController` only has GET endpoints. There's no `POST /orders/{id}/cancel` for users to cancel their own orders. Only admin can cancel via `AdminOrderController`. For a Q-commerce platform, users must be able to cancel orders before packing starts.  
**Severity**: **HIGH**

### HIGH — No Refund Triggered on Order Cancellation
**File**: `order-service/.../service/OrderService.java:107-131`  
**Issue**: `cancelOrder()` updates order status and publishes an event but does NOT trigger a payment refund or void. If an order is cancelled after payment capture, the customer's money is not returned. The saga compensates with `voidPayment` only during checkout — post-checkout cancellation has no refund logic.  
**Severity**: **HIGH**

### MEDIUM — No Payment Method Management
**Issue**: There's no endpoint or model for storing/listing/deleting saved payment methods. For a 20M-user platform, saved cards/UPI/wallets are essential for checkout conversion.  
**Severity**: **MEDIUM**

### MEDIUM — No Cart Abandonment Detection
**Issue**: The order-service has no scheduled job or mechanism to detect and handle abandoned checkouts (e.g., inventory reserved but checkout never completed). While Temporal workflow timeouts (5 min per CheckoutController.java:63) will eventually timeout and compensate, there's no proactive notification or analytics event for cart abandonment.  
**Severity**: **MEDIUM**

### MEDIUM — No Subscription/Recurring Payment Support
**Issue**: No infrastructure for recurring payments, subscription orders, or scheduled deliveries — table stakes for Q-commerce platforms.  
**Severity**: **MEDIUM**

### LOW — No Payment History/List Endpoint
**File**: `payment-service/.../controller/PaymentController.java`  
**Issue**: Only GET by ID exists. There's no `GET /payments?orderId=X` or list endpoint for admin dashboard needs.  
**Severity**: **LOW**

### LOW — No Delivery Address in Order
**File**: `order-service/.../domain/model/Order.java`  
**Issue**: The Order entity has no delivery/shipping address fields. The `OrderPlaced.v1.json` schema in docs mentions `shippingAddress` as required, but the actual `contracts/src/main/resources/schemas/orders/OrderPlaced.v1.json` does not require it, and the Order entity doesn't store it. For Q-commerce delivery, this is essential.  
**Severity**: **LOW** (may be handled by fulfillment service, but should be snapshotted at order time)

---

## 9. Additional Findings

### MEDIUM — Outbox `sent` Column Unused with Debezium
**File**: Both services' `OutboxEvent.java` and `V4__create_outbox.sql`  
**Issue**: The `sent` column is defined but never set to `true` anywhere in the codebase. If using Debezium CDC (as per docs), this column is unnecessary. If using polling-based publishing, there's no poller implementation. This is dead code that causes confusion.  
**Severity**: **MEDIUM**

### MEDIUM — Temporal WorkerFactory Lifecycle
**File**: `order-service/.../workflow/config/WorkerRegistration.java:38`  
**Issue**: `@Bean(initMethod = "start", destroyMethod = "shutdown")` — the WorkerFactory is started as a bean. This is fine, but there's no graceful drain of in-progress workflows during shutdown. The `server.shutdown=graceful` with `timeout-per-shutdown-phase=30s` may not be sufficient for long-running Temporal activities. Consider Temporal's `WorkerFactory.shutdownNow()` vs `shutdown()` semantics.  
**Severity**: **MEDIUM**

### LOW — Dockerfile COPY . . in Build Stage Copies Everything
**File**: Both services' `Dockerfile:3`  
**Issue**: `COPY . .` copies the entire project into the build stage, including `.git`, `node_modules`, etc. Use `.dockerignore` or multi-stage selective COPY for faster builds.  
**Severity**: **LOW**

### LOW — Health Check References Redis But No Redis Dependency
**File**: `order-service/.../resources/application.yml:77`, `payment-service/.../resources/application.yml:60`  
**Issue**: Health readiness group includes `redis` (`include: readinessState,db,redis`) but neither service has a Redis dependency in `build.gradle.kts`. This will either be ignored or cause a health check misconfiguration.  
**Severity**: **LOW**

---

## Summary Table

| # | Category | Finding | Severity | File |
|---|----------|---------|----------|------|
| 1 | Transaction Safety | PSP call inside @Transactional (authorize) | CRITICAL | PaymentService.java:46-96 |
| 2 | Transaction Safety | PSP call inside @Transactional (capture/void) | CRITICAL | PaymentService.java:98-157 |
| 3 | Transaction Safety | PSP call inside @Transactional (refund) | CRITICAL | RefundService.java:48-118 |
| 4 | Event Contract | PaymentVoided uses `amount` vs `amountCents` | CRITICAL | PaymentService.java:155 |
| 5 | Transaction Safety | Refund race condition (no SELECT FOR UPDATE) | HIGH | RefundService.java:58-68 |
| 6 | Transaction Safety | Capture before OrderPlaced, no compensation | HIGH | CheckoutWorkflowImpl.java:96-111 |
| 7 | Payment Security | Stripe.apiKey global static not thread-safe | HIGH | StripePaymentGateway.java:29 |
| 8 | Payment Security | Webhook signature header not required | HIGH | WebhookController.java:27-28 |
| 9 | Data Integrity | Refund ledger debit/credit direction wrong | HIGH | RefundService.java:93-94 |
| 10 | Data Integrity | Ledger account names differ from docs | HIGH | PaymentService.java:80-81 |
| 11 | Event Contract | OrderPlaced payload missing `sku` field | HIGH | OrderService.java:197-204 |
| 12 | Event Contract | Doc vs actual PaymentCaptured schema mismatch | HIGH | docs/09 vs PaymentCaptured.v1.json |
| 13 | Error Handling | PaymentGatewayException leaks PSP internals | HIGH | StripePaymentGateway.java |
| 14 | API Design | Admin cancel missing @PreAuthorize | HIGH | AdminOrderController.java:30 |
| 15 | API Design | Admin GET not URL-protected | HIGH | SecurityConfig.java:29 |
| 16 | Scalability | RateLimitService unbounded map | HIGH | RateLimitService.java:15 |
| 17 | Scalability | No HikariCP pool configuration | HIGH | application.yml (both) |
| 18 | Missing Feature | No user-initiated order cancellation | HIGH | OrderController.java |
| 19 | Missing Feature | No refund on post-checkout cancellation | HIGH | OrderService.java:107-131 |
| 20 | Transaction Safety | Outbox `sent` column conflicts with Debezium | MEDIUM | OutboxEvent.java, V4 SQL |
| 21 | Transaction Safety | No optimistic lock retry in webhook handler | MEDIUM | WebhookEventHandler.java |
| 22 | Payment Security | Webhook returns 200 on processing failures | MEDIUM | WebhookEventHandler.java:30-33 |
| 23 | Payment Security | No webhook event ID deduplication | MEDIUM | WebhookEventHandler.java |
| 24 | Data Integrity | State machine missing PACKED→CANCELLED | MEDIUM | OrderStateMachine.java:13 |
| 25 | Data Integrity | No DB-level state machine enforcement | MEDIUM | V1__create_orders.sql |
| 26 | Event Contract | No PaymentVoided schema in contracts | MEDIUM | contracts/schemas/payments/ |
| 27 | Event Contract | PaymentAuthorized outbox payload OK | — | ✓ |
| 28 | Error Handling | Broad catch in checkout workflow | MEDIUM | CheckoutWorkflowImpl.java:115 |
| 29 | Error Handling | Kafka consumer infinite retry on poison msg | MEDIUM | IdentityEventConsumer.java:40 |
| 30 | API Design | Checkout blocks HTTP thread synchronously | MEDIUM | CheckoutController.java:66 |
| 31 | API Design | Payment internal endpoints not role-gated | MEDIUM | SecurityConfig.java:29 (payment) |
| 32 | Scalability | No orders table partitioning | MEDIUM | V1__create_orders.sql |
| 33 | Scalability | No outbox cleanup job | MEDIUM | V4__create_outbox.sql |
| 34 | Scalability | Audit log cleanup only in payment svc | MEDIUM | order-service (missing) |
| 35 | Missing Feature | No payment method management | MEDIUM | (missing) |
| 36 | Missing Feature | No cart abandonment detection | MEDIUM | (missing) |
| 37 | Missing Feature | No subscription/recurring payments | MEDIUM | (missing) |
| 38 | Data Integrity | lineTotalCents not validated | LOW | CartItem.java |
| 39 | Payment Security | pspReference in response | LOW | PaymentResponse.java |
| 40 | Scalability | RestTemplate vs WebClient | LOW | RestPaymentClient.java |
| 41 | API Design | Status update accepts arbitrary strings | LOW | OrderStatusUpdateRequest.java |
| 42 | Missing Feature | No payment list/history endpoint | LOW | PaymentController.java |
| 43 | Missing Feature | No delivery address in Order entity | LOW | Order.java |
| 44 | Misc | Dockerfile COPY . . inefficient | LOW | Dockerfile (both) |
| 45 | Misc | Health check references Redis w/o dep | LOW | application.yml (both) |

---

## Priority Recommendations

### Immediate (Block Production Deployment)
1. **Extract PSP calls out of @Transactional boundaries** — findings #1-3
2. **Fix refund ledger debit/credit direction** — finding #9
3. **Standardize `amountCents` field name in PaymentVoided outbox event** — finding #4
4. **Add SELECT FOR UPDATE on payment during refund** — finding #5

### Next Sprint
5. Fix admin endpoint security (URL vs method-level) — findings #14-15
6. Add compensation for payment capture in saga — finding #6
7. Fix Stripe.apiKey thread-safety — finding #7
8. Configure HikariCP connection pools — finding #17
9. Add user-initiated cancel + refund-on-cancel — findings #18-19
10. Add OrderPlaced `sku` field to outbox payload — finding #11

### Backlog
11. Implement outbox cleanup job
12. Add rate limiter cache eviction
13. Move checkout to async workflow execution
14. Add webhook idempotency tracking
15. Add PaymentVoided schema to contracts
