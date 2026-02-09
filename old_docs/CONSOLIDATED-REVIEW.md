# Instacommerce Platform — Consolidated Code Review

**Date:** 2026-02-07  
**Scope:** Full codebase — 7 microservices, infrastructure, CI/CD, contracts  
**Scale Target:** 20M+ users, Q-commerce (10-min delivery), PCI-DSS/GDPR compliant  
**Benchmarks:** Zepto, Blinkit, Instacart, DoorDash, Swiggy Instamart

---

## Executive Summary

The Instacommerce platform has a solid foundation: well-structured microservices with Temporal saga orchestration, outbox pattern, double-entry ledger, RS256 JWT auth, Istio mesh, ArgoCD GitOps, and Terraform IaC. However, **8 parallel deep-dive reviews** uncovered **200+ findings** across security, scalability, resilience, data integrity, and operational readiness that must be addressed before production at 20M+ scale.

### Finding Distribution

| Severity | Identity | Order/Payment | Catalog/Inventory | Fulfillment/Notification | Infrastructure | Contracts | Security | **Total** |
|----------|----------|---------------|-------------------|--------------------------|----------------|-----------|----------|-----------|
| 🔴 CRITICAL | 5 | 4 | 2 | 7 | 9 | 5 | 4 | **36** |
| 🟠 HIGH | 9 | 15 | 5 | 11 | 27 | 9 | 9 | **85** |
| 🟡 MEDIUM | 12 | 18 | 12 | 14 | 21 | 8 | 10 | **95** |
| 🔵 LOW | 8 | 8 | 9 | 9 | 8 | 5 | 5 | **52** |

---

## 🔴 P0 — CRITICAL: Block Production Deployment

These findings will cause data loss, financial errors, cascading outages, or security breaches at scale.

### 1. PSP Calls Inside @Transactional — Ghost Payments (Payment Service)
**Files:** `PaymentService.java:46-96,98-157`, `RefundService.java:48-118`  
External Stripe API calls (authorize, capture, void, refund) are inside `@Transactional`. If PSP succeeds but DB commit fails, money moves at Stripe with no local record. At 20M users with 100K+ daily transactions, this creates irreconcilable ghost payments.  
**Fix:** Extract PSP calls outside the transaction boundary. Save a PENDING record first, call PSP, then update within a new transaction.

### 2. Refund Race Condition — Double Refunds (Payment Service)
**File:** `RefundService.java:58-68`  
No `SELECT FOR UPDATE` on payment during refund. Two concurrent refunds can both pass the `available` check and both issue PSP refunds, over-refunding. The DB constraint catches one at commit, but the PSP refund is already issued.  
**Fix:** Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` on payment lookup.

### 3. Refund Ledger Debit/Credit Reversed (Payment Service)
**File:** `RefundService.java:93-94`  
Refund creates DEBIT=`refund`, CREDIT=`merchant_payable`. This increases merchant_payable instead of decreasing it. Ledger will never reconcile.  
**Fix:** Reverse to DEBIT=`merchant_payable`, CREDIT=`customer_receivable`.

### 4. Zero Timeouts on ALL REST Clients (Fulfillment + Notification)
**Files:** `RestPaymentClient.java`, `RestOrderClient.java`, `RestInventoryClient.java`, `RestUserDirectoryClient.java`, `RestOrderLookupClient.java`  
All inter-service REST clients use bare `RestTemplate` with infinite timeouts. A single slow downstream cascades into full system failure — all threads block, Kafka consumers stop, notifications halt.  
**Fix:** Configure `setConnectTimeout(5s).setReadTimeout(10s)` + Resilience4j circuit breakers.

### 5. Thread.sleep() Blocks Notification Thread Pool
**File:** `RetryableNotificationSender.java:90-95`  
`Thread.sleep(5 minutes)` inside `@Async` method. With `maxPoolSize=16`, just 16 concurrent temp failures stall ALL notifications for 5+ minutes.  
**Fix:** Use scheduled retry (Spring Retry `@Retryable`, or poll-based retry from DB).

### 6. Kafka Consumers — No Error Handler, Poison Pill Death Loop
**Files:** All `*EventConsumer.java` in fulfillment + notification  
`throw new IllegalStateException()` on any error + no `DefaultErrorHandler` configured = infinite retry on malformed messages, blocking partitions forever.  
**Fix:** Configure `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` and `FixedBackOff(maxAttempts=3)`.

### 7. Inventory Service — Zero Event Publishing
**Files:** `inventory-service/` (entire codebase)  
No outbox table, no outbox event publishing, no Debezium config. StockReserved, StockConfirmed, StockReleased, LowStockAlert events are specified in contracts but never emitted. Event-driven architecture is broken for this service.  
**Fix:** Add outbox table migration, OutboxService, and publish events in stock mutation transactions.

### 8. Unbounded ConcurrentHashMap Rate Limiters — OOM DoS
**Files:** `RateLimitService.java` in identity, order, catalog  
One `RateLimiter` per IP/userId, never evicted. Attackers can rotate IPs to cause unbounded memory growth → OOM.  
**Fix:** Replace with Caffeine cache (maxSize + TTL) or Redis-based distributed rate limiter.

### 9. /auth/revoke Unauthenticated (Identity Service)
**File:** `SecurityConfig.java:24`  
`/auth/**` permits all. This includes `/auth/revoke` — anyone who intercepts a refresh token can revoke it without proving identity.  
**Fix:** Move revoke endpoint or add per-path auth rules.

### 10. No Rolling Update Strategy + No Pod Anti-Affinity (Kubernetes)
**File:** `deploy/helm/templates/deployment.yaml`  
No `strategy` block (defaults to 25% maxUnavailable). No `affinity` rules — all replicas can land on one node. Single node failure = total service outage.  
**Fix:** Add `maxUnavailable: 0, maxSurge: 1` strategy + zone-aware pod anti-affinity.

### 11. No Istio Circuit Breaking / Outlier Detection
**File:** `deploy/helm/templates/istio/` (no DestinationRule)  
No circuit breakers at mesh level. A slow downstream cascades everywhere.  
**Fix:** Add DestinationRules with outlierDetection and connectionPool limits.

### 12. Reservation Expiry Job — No Distributed Lock (Inventory)
**File:** `ReservationExpiryJob.java:22-29`  
`@Scheduled` runs on ALL pods. Multiple pods fight over the same expired reservations causing contention and wasted resources.  
**Fix:** Use ShedLock `@SchedulerLock` or advisory locks.

---

## 🟠 P1 — HIGH: Fix Before GA Launch

### Security
- **Admin endpoints missing @PreAuthorize** — `AdminOrderController.cancelOrder()`, `updateStatus()`, `AdminFulfillmentController` (3 endpoints)
- **Notification service has zero Spring Security** — no auth at all
- **Stripe.apiKey global static** — not thread-safe, use `RequestOptions`
- **Webhook signature header `required=false`** — allows unauthenticated webhook requests
- **No CORS configuration** — in any service
- **JWT missing `aud`/`jti` claims** — cross-service token replay possible
- **Deleted user access tokens still valid** — no blacklist, 15-min window
- **No Kubernetes pod securityContext** — `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities.drop`
- **No NetworkPolicies** — defense-in-depth at CNI layer missing
- **HTTP calls inside @Transactional** — fulfillment PickService makes HTTP to order/payment/inventory inside DB transactions; if TX rolls back after HTTP succeeds, stale state
- **No service-to-service auth on REST calls** — /admin/* endpoints require auth but REST clients pass no JWT

### Data Integrity
- **PaymentVoided uses `amount` not `amountCents`** — inconsistent with all other events
- **OrderPlaced missing `sku` field** — schema requires it, publisher omits
- **5 events published without schemas** — OrderCreated, OrderStatusChanged, PaymentVoided, UserErased, OrderModified
- **Doc schemas ≠ actual schemas ≠ publishers** — PaymentCaptured has 3 different versions across doc/schema/code
- **No refund triggered on post-checkout cancellation** — captured payment not returned
- **Refund over-calculation** — SubstitutionService uses `unitPrice × qty` ignoring discounts

### Scalability
- **No HikariCP tuning** — any service, defaults to pool size 10
- **Admin listUsers() loads ALL 20M users** — no pagination, instant OOM
- **No caching anywhere** — catalog, identity, no Caffeine/Redis despite docs specifying it
- **N+1 queries in PricingService** — 2 queries per cart item
- **PUSH notifications always SKIPPED** — no device token lookup
- **SMS always fails** — RestUserDirectoryClient returns `phone=null`
- **Duplicate identity HTTP calls per notification** — `fallbackName()` + `resolveRecipient()` both call findUser()

### Operations
- **No Micrometer auth metrics** — dashboards have no data
- **Scheduling runs on all pods** — no leader election (identity, inventory, catalog, payment)
- **No Terraform state locking** — concurrent applies can corrupt state
- **HPA CPU-only** — no memory, request rate, or Kafka lag metrics

---

## 🟡 P2 — MEDIUM: Fix Before Scale-Up

- Order state machine missing PACKED→CANCELLED transition
- No DB-level state machine enforcement
- Webhook returns 200 OK on processing failures (lost events)
- No webhook event ID deduplication
- Synchronous checkout blocks HTTP thread for entire saga
- No outbox cleanup job (grows unbounded)
- No orders table partitioning strategy
- No API path versioning (`/api/v1`)
- Rate limit values mismatch docs
- Missing Retry-After header on 429
- Password policy no special char requirement
- X-Forwarded-For trusted blindly
- No account lockout after failed logins
- No refresh token family detection
- Audit log not append-only at DB level
- INET column type mismatch risk
- Duplicate indexes after migration V5
- Template loading uncached
- EventId fallback breaks deduplication
- Consumer concurrency=1 (wasting Kafka partitions)
- HPA notification-service missing
- Resource quotas/LimitRanges missing
- `auto-offset-reset: earliest` replays all history
- Health check references Redis but not configured

---

## ✅ What's Done Well

| Area | Implementation |
|------|---------------|
| JWT Auth | RS256 with RSA-2048, BCrypt-12, refresh token rotation |
| Saga Orchestration | Temporal workflows with compensation logic |
| Event Publishing | Outbox pattern in 5/7 services |
| Double-Entry Ledger | Payment accounting with constraints |
| GDPR Erasure | Anonymization across order/fulfillment/notification |
| Structured Logging | LogstashEncoder, JSON logs |
| Graceful Shutdown | `server.shutdown=graceful` with 30s timeout |
| Docker Best Practices | Non-root, JVM tuning, ZGC, multi-stage builds |
| Istio mTLS | STRICT PeerAuthentication enabled |
| GitOps | ArgoCD with automated sync |
| IaC | Terraform for GKE, Cloud SQL, Memorystore |
| Deduplication | Notification dedup via UNIQUE constraint |
| PII Masking | MaskingUtil for notification recipients |
| Idempotency | Refund via idempotency keys, inventory via UNIQUE order_id |

---

## Detailed Reports (by domain)

Individual deep-dive reports are available at:
1. `services/identity-service/REVIEW-REPORT.md` — 34 findings
2. `docs/code-review-order-payment.md` — 45 findings
3. `services/REVIEW_FINDINGS.md` — 38 findings (catalog + inventory)
4. `services/REVIEW-fulfillment-notification.md` — 41 findings
5. `docs/INFRASTRUCTURE_AUDIT_REPORT.md` — 65 findings
6. `docs/contracts-review-findings.md` — 27 findings
7. `docs/SECURITY-AUDIT-REPORT.md` — 28 findings
