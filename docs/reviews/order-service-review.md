# Order Service — Deep Architecture Review

**Reviewer:** Senior Order Management Architect  
**Date:** 2025-07-02  
**Scope:** Every file in `services/order-service/` (84 Java files, 7 Flyway migrations, application.yml, logback-spring.xml, Dockerfile, build.gradle.kts)  
**Platform Context:** Q-commerce, 20M+ users, Blinkit / Zepto / Instacart competitive set  
**Verdict:** Solid foundation with a well-designed Temporal saga checkout. **Not production-ready for Q-commerce scale** — critical gaps in state machine, order lifecycle, performance at scale, and real-time customer experience features.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Business Logic Review](#3-business-logic-review)
   - [3.1 State Machine](#31-state-machine)
   - [3.2 Order Creation & Atomicity](#32-order-creation--atomicity)
   - [3.3 Cancel Flow](#33-cancel-flow)
   - [3.4 Order Modification](#34-order-modification)
   - [3.5 Order History & Querying](#35-order-history--querying)
   - [3.6 Status Updates & Fulfillment Integration](#36-status-updates--fulfillment-integration)
4. [SLA & Performance Review](#4-sla--performance-review)
   - [4.1 Query Patterns & Indexing](#41-query-patterns--indexing)
   - [4.2 Event Publishing & Outbox Pattern](#42-event-publishing--outbox-pattern)
   - [4.3 Database Partitioning Strategy](#43-database-partitioning-strategy)
5. [Missing Features Analysis](#5-missing-features-analysis)
6. [Q-Commerce Competitive Gap Analysis](#6-q-commerce-competitive-gap-analysis)
7. [Security Review](#7-security-review)
8. [Infrastructure & Operational Review](#8-infrastructure--operational-review)
9. [Code Quality Observations](#9-code-quality-observations)
10. [Priority-Ranked Recommendations](#10-priority-ranked-recommendations)

---

## 1. Executive Summary

### What's Done Well
| Area | Detail |
|------|--------|
| **Temporal Saga** | Proper compensating transactions (inventory release → payment void → order cancel) with correct ordering (`setParallelCompensation(false)`) |
| **Idempotency** | DB-level unique constraint on `idempotency_key` + Temporal workflow-ID dedup (`REJECT_DUPLICATE`) |
| **Outbox Pattern** | Events written in same DB transaction as state change (`Propagation.MANDATORY`), cleanup job at 3 AM |
| **State Machine** | Explicit transition map, immutable class, throws `InvalidOrderStateException` for illegal moves |
| **Audit Trail** | Comprehensive `audit_log` with IP, user-agent, trace-id, JSONB details |
| **GDPR Erasure** | `UserErasureService` anonymizes orders on `UserErased` Kafka event |
| **Security** | JWT RSA verification, `ROLE_ADMIN` method-level security, stateless sessions, structured error responses |
| **Observability** | OTLP tracing + metrics, structured JSON logging (Logstash encoder), trace-ID propagation |

### Critical Issues (Blockers)
| # | Issue | Severity | Impact |
|---|-------|----------|--------|
| 1 | Missing states: `READY_FOR_PICKUP`, `PARTIALLY_FULFILLED`, `REFUND_PENDING`, `RETURNED` | 🔴 Critical | Cannot model real-world Q-commerce fulfillment |
| 2 | No outbox relay/poller — events are written but never sent to Kafka | 🔴 Critical | Downstream services never receive events |
| 3 | Checkout blocks HTTP thread for entire Temporal workflow (~seconds) | 🔴 Critical | Thread starvation under load |
| 4 | No database partitioning — `orders` table will hit billions of rows | 🔴 Critical | Query degradation at scale |
| 5 | No order modification capability at all | 🟠 High | Users cannot change orders after placing |
| 6 | Cancellation has no time-window enforcement | 🟠 High | Users can cancel even during delivery |
| 7 | No event consumer for fulfillment/delivery status updates | 🟠 High | Order status never progresses beyond PLACED |
| 8 | Missing composite index `(user_id, created_at DESC)` | 🟠 High | Order history pagination scans full user rows |

---

## 2. Architecture Overview

```
┌────────────────────────────────────────────────────────────────────┐
│                        order-service                               │
│                                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐ │
│  │ Checkout      │  │ Order        │  │ Admin Order              │ │
│  │ Controller    │  │ Controller   │  │ Controller               │ │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────────┘ │
│         │                 │                      │                 │
│         ▼                 ▼                      ▼                 │
│  ┌──────────────┐  ┌──────────────┐                               │
│  │ Temporal      │  │ OrderService │◄──── State Machine            │
│  │ Workflow      │  │              │                               │
│  │ Client        │  │  + Outbox    │                               │
│  └──────┬───────┘  │  + Audit     │                               │
│         │          └──────┬───────┘                                │
│         ▼                 │                                        │
│  ┌──────────────┐         ▼                                        │
│  │ Checkout     │  ┌──────────────┐                                │
│  │ Workflow     │  │ PostgreSQL   │                                │
│  │ (Temporal)   │  │ orders       │                                │
│  │              │  │ order_items  │                                │
│  │ Activities:  │  │ outbox_events│                                │
│  │ - Inventory  │  │ audit_log    │                                │
│  │ - Payment    │  │ status_hist  │                                │
│  │ - Order      │  └──────────────┘                                │
│  │ - Cart       │                                                  │
│  └──────────────┘                                                  │
│                                                                    │
│  Kafka Consumer: identity.events → UserErasureService              │
│  Kafka Producer: (outbox_events — ⚠️ NO RELAY IMPLEMENTED)        │
└────────────────────────────────────────────────────────────────────┘
```

**Tech Stack:** Spring Boot 3 + JDK 21, PostgreSQL, Temporal, Kafka, Resilience4j, Caffeine, Flyway, OTEL, GCP Secret Manager

---

## 3. Business Logic Review

### 3.1 State Machine

**File:** `OrderStateMachine.java`, `OrderStatus.java`

**Current States & Transitions:**

```
PENDING ──→ PLACED ──→ PACKING ──→ PACKED ──→ OUT_FOR_DELIVERY ──→ DELIVERED
  │            │          │           │
  ├──→ FAILED  ├──→ CANCELLED       ├──→ CANCELLED
  └──→ CANCELLED         └──→ CANCELLED

DELIVERED ──→ (terminal)
CANCELLED ──→ (terminal)
FAILED    ──→ (terminal)
```

#### ✅ What's Good
- Immutable `Map.of()` — thread-safe, no accidental mutation
- `validate()` throws `InvalidOrderStateException` (422) on illegal transitions
- Cancellation allowed from `PENDING`, `PLACED`, `PACKING`, `PACKED` — reasonable window

#### 🔴 Missing States (Critical for Q-Commerce)

| Missing State | Why Needed | Competitor Reference |
|---------------|------------|---------------------|
| `READY_FOR_PICKUP` | Dark-store model: order packed and waiting for rider | Blinkit shows "Your order is ready!" |
| `PARTIALLY_FULFILLED` | Some items out of stock during picking | Instacart shows "3 of 5 items found" |
| `REFUND_PENDING` | After cancellation, refund is async (2-7 days) | Zepto shows "Refund initiated" status |
| `REFUND_COMPLETED` | Refund confirmed by PSP | All Q-commerce apps show this |
| `RETURNED` | Customer returns order (RTO or customer-initiated) | Required for reverse logistics |
| `PAYMENT_PENDING` | COD orders or UPI timeout scenarios | Critical for Indian market |
| `ASSIGNED` | Delivery partner assigned but not yet picked up | Blinkit "Rider on the way to store" |

#### 🟠 Transition Gaps

1. **`OUT_FOR_DELIVERY` → `CANCELLED` is blocked** — What about failed delivery attempts? RTO? Need `OUT_FOR_DELIVERY → RETURNED`.
2. **No `DELIVERED` → `RETURNED`** — No return/RTO flow at all.
3. **No `CANCELLED` → `REFUND_PENDING`** — Cancellation doesn't trigger refund state tracking.
4. **`PACKED` → `CANCELLED` is allowed** — This is debatable. Once packed, cancellation should require admin approval or have a very short window since rider may already be assigned.

#### Recommendation
```java
public enum OrderStatus {
    PENDING,
    PAYMENT_PENDING,    // NEW: COD / UPI timeout
    PLACED,
    PACKING,
    PARTIALLY_FULFILLED, // NEW: some items unavailable
    PACKED,
    READY_FOR_PICKUP,   // NEW: awaiting rider
    ASSIGNED,           // NEW: rider assigned
    OUT_FOR_DELIVERY,
    DELIVERED,
    RETURN_REQUESTED,   // NEW
    RETURNED,           // NEW
    CANCELLED,
    REFUND_PENDING,     // NEW
    REFUND_COMPLETED,   // NEW
    FAILED
}
```

---

### 3.2 Order Creation & Atomicity

**Files:** `CheckoutWorkflowImpl.java`, `OrderService.createOrder()`, `OutboxService.publish()`

**Checkout Flow (Temporal Saga):**
```
1. RESERVING_INVENTORY  → inventory.reserveInventory()
2. AUTHORIZING_PAYMENT  → payment.authorizePayment()
3. CREATING_ORDER       → orders.createOrder() [DB write + outbox event in same TX]
4. CONFIRMING_RESERVATION → inventory.confirmReservation()
5. CAPTURING_PAYMENT    → payment.capturePayment()
6. CLEARING_CART        → cart.clearCart() [failure swallowed]
7. FINALIZING           → orders.updateOrderStatus("PLACED")
```

**Compensations (on failure):**
```
cancelReservation() → voidPayment() → cancelOrder()
```

#### ✅ What's Good
- **Temporal guarantees**: Saga compensations run in reverse order even if process crashes
- **Idempotency is double-layered**: DB `UNIQUE(idempotency_key)` + Temporal `REJECT_DUPLICATE` workflow ID policy
- **Atomicity of DB write + event publish**: `OutboxService.publish()` uses `Propagation.MANDATORY` — runs in the caller's transaction. So `createOrder()` DB write and outbox event insert are atomic. ✅
- **Cart clear failure is non-fatal**: Correctly swallowed — cart can be cleared later

#### 🔴 Critical Issues

**Issue 1: Checkout blocks HTTP thread**

```java
// CheckoutController.java — line 70
CheckoutResult result = workflow.execute(resolved);  // BLOCKING CALL
```

This is a **synchronous** call to Temporal that blocks the Servlet thread for the entire workflow duration (inventory check + payment auth + DB write + confirm + capture = 5-30 seconds). At 20M users, this will exhaust the Tomcat thread pool.

The code even has a TODO acknowledging this:
```java
// TODO: Convert to async (non-blocking) checkout. Currently blocks the HTTP thread
```

**Fix:** Return `202 Accepted` with a `workflowId`, provide `GET /checkout/{workflowId}/status` polling endpoint, or use SSE/WebSocket for real-time result streaming.

**Issue 2: Race condition between steps 3 and 7**

Order is created in `PENDING` status (step 3), then later updated to `PLACED` (step 7). Between these steps, the order exists in `PENDING` state in the DB. If the user queries their orders during this window, they see a `PENDING` order. If step 5 (capturePayment) fails, the saga compensates and the order goes to `CANCELLED`. But during the window, the user already saw a "pending" order.

This is acceptable but should be documented. Consider adding an `ORDER_PROCESSING` status to indicate "checkout in progress, not yet confirmed."

**Issue 3: Step 4 and 5 failure handling**

If `confirmReservation()` (step 4) succeeds but `capturePayment()` (step 5) fails, the saga compensates by voiding the payment (already failed, so likely no-op) and cancelling the reservation. But the reservation was already confirmed — `cancelReservation()` is the compensation registered for the original `reserveInventory()`, not for `confirmReservation()`. After confirmation, the inventory may have been committed. Need a `reverseConfirmation()` compensation.

**Issue 4: No total verification**

The `totalCents` comes from the client request and is stored directly. There's no server-side recalculation of `sum(quantity * unitPriceCents) - discountCents`. A malicious client could send `totalCents: 1` with items worth ₹5000.

```java
// OrderService.createOrder() — trusts client-supplied totals
order.setSubtotalCents(command.getSubtotalCents());
order.setTotalCents(command.getTotalCents());
```

**Fix:** Recalculate totals server-side or validate against catalog prices during checkout.

---

### 3.3 Cancel Flow

**Files:** `OrderController.cancelOrder()`, `AdminOrderController.cancelOrder()`, `OrderService.cancelOrderByUser()`, `OrderService.cancelOrder()`

#### Flow Analysis

| Actor | Endpoint | Method | Authorization |
|-------|----------|--------|--------------|
| User | `POST /orders/{id}/cancel` | `cancelOrderByUser()` | JWT (owns order) |
| Admin | `POST /admin/orders/{id}/cancel` | `cancelOrder()` | `ROLE_ADMIN` |
| System (Saga) | Temporal activity | `cancelOrder()` | Internal |

#### ✅ What's Good
- **User can only cancel their own orders**: `findByIdAndUserId()` enforces ownership
- **Idempotent**: If already `CANCELLED`, returns silently
- **Audit trail**: Both user and admin cancels are audit-logged with actor identity
- **Event published**: `OrderCancelled` event → outbox (for downstream refund trigger)
- **Reason captured**: `cancellationReason` stored on order entity

#### 🔴 Issues

**Issue 1: No cancellation window enforcement**

```java
// OrderStateMachine allows cancel from PACKED
OrderStatus.PACKED, Set.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CANCELLED)
```

User can cancel **after the order is packed**. In Q-commerce, once packed, the store has committed labor and materials. Most platforms (Blinkit, Zepto) restrict user cancellation to **before packing starts** (i.e., `PLACED` status only). Admin should be able to cancel from any non-terminal state.

**Recommendation:**
```java
// User cancel: only PENDING, PLACED
// Admin cancel: PENDING, PLACED, PACKING, PACKED, OUT_FOR_DELIVERY
// System cancel: any non-terminal
```

**Issue 2: No refund trigger**

The code has comments:
```java
// Payment refund should be handled by a consumer of the OrderCancelled event
```

But as noted in [Section 4.2](#42-event-publishing--outbox-pattern), the outbox relay is not implemented. Even if it were, there's no `REFUND_PENDING` state to track refund progress. The user sees `CANCELLED` but has no visibility into whether their money is being returned.

**Issue 3: No cancellation fee logic**

Q-commerce platforms typically charge a cancellation fee if order is cancelled after packing. No such logic exists.

**Issue 4: Duplicate code**

`cancelOrderByUser()` and `cancelOrder(UUID, String, String, UUID)` have nearly identical logic (80% overlap). Should be refactored to a single private method with an `ActorType` parameter.

---

### 3.4 Order Modification

**Verdict: Completely absent.** ❌

There is **zero** order modification capability in the codebase:
- No endpoint to add/remove items
- No endpoint to change quantities
- No substitution approval workflow
- No modification time-window concept

#### What's Needed for Q-Commerce

| Feature | Blinkit | Zepto | Instacart | Current |
|---------|---------|-------|-----------|---------|
| Add/remove items before packing | ✅ | ✅ | ✅ | ❌ |
| Modify quantity | ✅ | ✅ | ✅ | ❌ |
| Substitution approval | ❌ | ❌ | ✅ (real-time) | ❌ |
| Modification window (before packing) | ✅ | ✅ | ✅ | ❌ |
| Price recalculation on modification | ✅ | ✅ | ✅ | ❌ |

#### Recommended Design
```
POST /orders/{id}/modify
{
  "items": [
    { "productId": "...", "quantity": 3, "action": "UPDATE" },
    { "productId": "...", "action": "REMOVE" },
    { "productId": "...", "quantity": 1, "action": "ADD", ... }
  ]
}

Rules:
- Only allowed when status ∈ {PLACED, PACKING} (configurable window)
- Requires inventory re-reservation for new/increased items
- Triggers payment adjustment (additional auth or partial void)
- Emits OrderModified event
- Records modification in order_status_history with details
```

---

### 3.5 Order History & Querying

**Files:** `OrderController.listOrders()`, `OrderRepository.findByUserId()`

#### Current Capabilities
```
GET /orders                    → Page<OrderSummaryResponse> (user's orders, paginated)
GET /orders/{id}               → OrderResponse (full order + status timeline)
GET /orders/{id}/status        → OrderStatusResponse (status + timeline)
GET /admin/orders/{id}         → OrderResponse (admin, any order)
```

#### ✅ What's Good
- **Pagination**: Spring Data `Pageable` with `MAX_PAGE_SIZE = 100` cap
- **Ownership enforcement**: Non-admin users can only see their own orders
- **Status timeline**: Full history from `order_status_history` table

#### 🟠 Issues

**Issue 1: No filtering or search**

Users cannot filter by:
- Status (show only active orders)
- Date range (last 30 days)
- Store
- Text search (product name)

For a power user with 1000+ orders, scrolling through paginated results without filters is unusable.

**Issue 2: Missing composite index**

```sql
-- V1: Only single-column indexes exist
CREATE INDEX idx_orders_user    ON orders (user_id);
CREATE INDEX idx_orders_created ON orders (created_at DESC);
```

The query `findByUserId(userId, pageable)` likely sorts by `created_at DESC`. PostgreSQL cannot efficiently use two separate indexes for `WHERE user_id = ? ORDER BY created_at DESC`. Needs:

```sql
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
```

**Issue 3: N+1 query risk**

`OrderMapper.toResponse()` accesses `order.getItems()` which triggers lazy loading. For the list endpoint, `OrderMapper.toSummary()` doesn't access items (good), but `getOrder()` does. Should use `@EntityGraph` or `JOIN FETCH` for the single-order query.

**Issue 4: No admin list/search endpoint**

`AdminOrderController` only has `GET /admin/orders/{id}` (single order). Admin cannot:
- List all orders
- Search by user, status, date range, store
- Export orders

This is essential for ops/support teams.

**Issue 5: No "reorder" support**

Blinkit and Zepto have a "Reorder" button. This requires the API to return enough item detail (productId, SKU, quantity) to reconstruct a cart. The `OrderSummaryResponse` strips items — the full `OrderResponse` is needed but it's expensive to fetch for a list view.

**Recommendation:** Add a lightweight `GET /orders/{id}/reorder` endpoint that returns a `CheckoutRequest`-compatible payload.

---

### 3.6 Status Updates & Fulfillment Integration

**Current mechanism:** Admin REST API only.

```java
// AdminOrderController.updateStatus()
POST /admin/orders/{id}/status  { "status": "PACKING", "note": "..." }
```

#### 🔴 Critical Gap: No Event Consumer for Fulfillment/Delivery

The order service has **no Kafka consumer** for fulfillment or delivery events. The only consumer is `IdentityEventConsumer` for GDPR erasure. This means:

1. When the warehouse starts packing → no automatic status update
2. When rider picks up → no automatic status update
3. When rider delivers → no automatic status update

**In production, either:**
- Fulfillment-service and delivery-service must call the admin REST API (tight coupling, fragile)
- Or (better) order-service should consume domain events:

```
Topics to consume:
- fulfillment.events → PackingStarted, PackingCompleted, ItemSubstituted, ItemOutOfStock
- delivery.events → RiderAssigned, PickedUp, InTransit, Delivered, DeliveryFailed
```

This is the most critical missing integration for a Q-commerce platform. Without it, the order status is essentially manual.

---

## 4. SLA & Performance Review

### 4.1 Query Patterns & Indexing

**Current Indexes (from migrations):**

| Table | Index | Columns | Type |
|-------|-------|---------|------|
| orders | `idx_orders_user` | `user_id` | B-tree |
| orders | `idx_orders_status` | `status` | B-tree |
| orders | `idx_orders_created` | `created_at DESC` | B-tree |
| orders | `uq_order_idempotency` | `idempotency_key` | Unique |
| order_items | `idx_order_items_order` | `order_id` | B-tree |
| order_status_history | `idx_osh_order` | `order_id` | B-tree |
| outbox_events | `idx_outbox_unsent` | `sent` (partial WHERE false) | B-tree |
| audit_log | `idx_audit_user_id` | `user_id` | B-tree |
| audit_log | `idx_audit_action` | `action` | B-tree |
| audit_log | `idx_audit_created_at` | `created_at` | B-tree |

#### Missing Indexes

```sql
-- 1. CRITICAL: Order history pagination (user_id + sort by created_at)
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);

-- 2. IMPORTANT: Admin search by store
CREATE INDEX idx_orders_store ON orders (store_id);

-- 3. IMPORTANT: Admin search by status + date
CREATE INDEX idx_orders_status_created ON orders (status, created_at DESC);

-- 4. USEFUL: Composite for user active orders
CREATE INDEX idx_orders_user_status ON orders (user_id, status);

-- 5. USEFUL: Payment ID lookup (for payment webhooks)
CREATE INDEX idx_orders_payment ON orders (payment_id) WHERE payment_id IS NOT NULL;

-- 6. USEFUL: Reservation ID lookup (for inventory callbacks)
CREATE INDEX idx_orders_reservation ON orders (reservation_id) WHERE reservation_id IS NOT NULL;
```

#### Connection Pool Analysis

```yaml
hikari:
  maximum-pool-size: 20
  minimum-idle: 5
  connection-timeout: 5000
```

20 connections is low for a service handling 20M users. With the blocking checkout (each checkout holds a connection for ~1-5s across multiple activity calls), this will exhaust quickly. **After fixing checkout to be async, 20 is reasonable for read-heavy order queries.**

---

### 4.2 Event Publishing & Outbox Pattern

**Files:** `OutboxService.java`, `OutboxCleanupJob.java`, `OutboxEventRepository.java`

#### Current Implementation
```
Order state change → (same TX) → INSERT into outbox_events
                                   ↓
                          (NOTHING reads and sends to Kafka)
                                   ↓
                          OutboxCleanupJob deletes sent=true events after 30 days
```

#### 🔴 Critical: No Outbox Relay

The outbox table is written to, but **nothing polls it and publishes to Kafka**. The `sent` column is never set to `true` by application code. The `OutboxCleanupJob` deletes `WHERE sent = true`, which means it deletes nothing.

**Required:** An outbox relay component:

**Option A: Polling Publisher (simpler)**
```java
@Scheduled(fixedDelay = 1000)
@Transactional
public void relay() {
    List<OutboxEvent> unsent = outboxRepo.findTop100BySentFalseOrderByCreatedAtAsc();
    for (OutboxEvent event : unsent) {
        kafkaTemplate.send(event.getEventType(), event.getAggregateId(), event.getPayload());
        event.setSent(true);
    }
}
```

**Option B: Debezium CDC (production-grade)**
- Debezium reads PostgreSQL WAL, captures INSERT on `outbox_events`, and forwards to Kafka
- No polling overhead, exactly-once semantics with log-based CDC
- Recommended for Q-commerce scale

#### Events Currently Published to Outbox

| Event | Trigger | Payload |
|-------|---------|---------|
| `OrderCreated` | `createOrder()` | orderId, userId, status |
| `OrderCancelled` | `cancelOrder()` / `cancelOrderByUser()` | orderId, userId, paymentId, totalCents, currency, reason |
| `OrderStatusChanged` | `updateOrderStatus()` | orderId, from, to |
| `OrderPlaced` | `updateOrderStatus()` when `to == PLACED` | Full order + items payload |

#### Missing Events

| Event | When | Why Needed |
|-------|------|-----------|
| `OrderDelivered` | Status → DELIVERED | Trigger loyalty points, review prompt |
| `OrderModified` | Items changed | Inventory adjustment, payment adjustment |
| `OrderRefundInitiated` | Refund processing starts | Payment service needs this |
| `OrderItemSubstituted` | Picker substitutes item | Customer notification |

---

### 4.3 Database Partitioning Strategy

**Current:** No partitioning. Single `orders` table.

#### 🔴 Problem at Scale

| Metric | Projection |
|--------|-----------|
| Users | 20M |
| Avg orders/user/month | 4 |
| Monthly orders | 80M |
| Annual orders | ~1B |
| Rows after 3 years | ~3B |

At 3B rows, even with indexes, queries will degrade. PostgreSQL handles this but needs partitioning.

#### Recommended Strategy

**Range partition by `created_at` (monthly):**
```sql
CREATE TABLE orders (
    ...
) PARTITION BY RANGE (created_at);

CREATE TABLE orders_2025_01 PARTITION OF orders
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');
-- auto-create future partitions via pg_partman
```

**Why `created_at` and not `user_id`?**
- Most queries are "recent orders" — partition pruning by date is most effective
- User-based hash partitioning spreads data evenly but doesn't help with "my recent orders" query
- Archival is natural: detach old partitions → move to cold storage

**Additional recommendations:**
- Use `pg_partman` for automated partition management
- Archive orders older than 12 months to a read-replica or data warehouse
- Consider BRIN index on `created_at` for range scans across partitions

---

## 5. Missing Features Analysis

### 5.1 Order Splitting (Multi-Store)

**Status:** ❌ Not implemented

Currently, each order has a single `store_id`. If a user's cart contains items from multiple stores (marketplace model), the platform must split into sub-orders.

**Required design:**
```
parent_order (user-facing)
  ├── sub_order_1 (store_id = "store-A", items [1,2,3])
  ├── sub_order_2 (store_id = "store-B", items [4,5])
  └── payment spans parent order
```

**Schema change needed:**
```sql
ALTER TABLE orders ADD COLUMN parent_order_id UUID REFERENCES orders(id);
ALTER TABLE orders ADD COLUMN is_sub_order BOOLEAN DEFAULT false;
```

### 5.2 Scheduled Orders

**Status:** ❌ Not implemented

"Place order now, deliver at 6 PM" — common in grocery Q-commerce.

**Required:**
- `scheduled_delivery_at TIMESTAMPTZ` column on `orders`
- `SCHEDULED` status in state machine
- Scheduler that transitions `SCHEDULED → PLACED` at appropriate time (accounting for prep time)
- Temporal already available — scheduled workflow execution is native

### 5.3 Recurring Orders

**Status:** ❌ Not implemented

"Auto-reorder milk every Sunday" — Instacart's Subscribe & Save equivalent.

**Required:**
- Separate `order_subscriptions` table (user, items, frequency, next_run, active)
- Temporal cron workflow or dedicated scheduler
- Inventory check + price update at execution time (prices change)
- User notification before auto-order with opt-out window

### 5.4 Order Tracking Link

**Status:** ❌ Not implemented

Shareable URL like `https://track.instacommerce.in/t/abc123` that works without login.

**Required:**
- `tracking_token VARCHAR(32)` on `orders` table (random, URL-safe)
- `GET /track/{token}` public endpoint (no auth) returning sanitized order status
- Short link generation service
- Rate limiting on public endpoint to prevent enumeration

### 5.5 Order Notes / Special Instructions

**Status:** Partially implemented via `delivery_address TEXT`

The `delivery_address` field exists but there's no separate field for:
- Special instructions ("Leave at door", "Ring bell twice")
- Gate code / building access code
- Landmark ("Next to red gate")
- Preferred contact method

**Required:**
```sql
ALTER TABLE orders ADD COLUMN delivery_notes TEXT;
ALTER TABLE orders ADD COLUMN delivery_contact_phone VARCHAR(15);
ALTER TABLE orders ADD COLUMN delivery_landmark VARCHAR(200);
```

---

## 6. Q-Commerce Competitive Gap Analysis

### vs. Blinkit

| Feature | Blinkit | Instacommerce | Gap |
|---------|---------|---------------|-----|
| Live order tracking (map) | ✅ | ❌ | Need location streaming via WebSocket/SSE |
| Push notification per status | ✅ | ❌ | Need notification events from status changes |
| Reorder button | ✅ | ❌ | Need reorder endpoint |
| ETA display | ✅ | ❌ | No ETA field on order |
| Rider tip | ✅ | ❌ | No tip model |
| Order rating/review | ✅ | ❌ | No post-delivery feedback |

### vs. Zepto

| Feature | Zepto | Instacommerce | Gap |
|---------|-------|---------------|-----|
| 10-min ETA countdown | ✅ | ❌ | No `estimated_delivery_at` field |
| Order modification during packing | ✅ | ❌ | No modification API |
| Split payment display | ✅ | ❌ | Single paymentId, no split payment model |
| Real-time packing progress | ✅ | ❌ | `pickedStatus` on items exists but no consumer updates it |
| Surge pricing indicator | ✅ | ❌ | No delivery fee / surge model |

### vs. Instacart

| Feature | Instacart | Instacommerce | Gap |
|---------|-----------|---------------|-----|
| In-app chat with shopper | ✅ | ❌ | No chat/messaging integration |
| Real-time substitution approval | ✅ | ❌ | `pickedStatus` field exists but no substitution workflow |
| Tip adjustment post-delivery | ✅ | ❌ | No tip model |
| Multi-store batch ordering | ✅ | ❌ | No order splitting |
| Item-level refund | ✅ | ❌ | No partial refund model |
| Delivery instructions with photo | ✅ | ❌ | Text-only address |

### Priority Features for Competitive Parity

1. **ETA tracking** — Add `estimated_delivery_at` field, update via delivery events
2. **Notification triggers** — Emit events that notification-service consumes per status change
3. **Substitution workflow** — Leverage existing `pickedStatus` on `OrderItem`, add approval flow
4. **Reorder API** — Low-effort, high-value
5. **Item-level refund** — Add `refund_cents` and `refund_status` to `order_items`

---

## 7. Security Review

### ✅ Strengths
- **RSA JWT verification** with configurable public key via GCP Secret Manager
- **Stateless sessions** (no server-side session storage)
- **Method-level security**: `@PreAuthorize("hasRole('ADMIN')")` on admin endpoints
- **Ownership enforcement**: `findByIdAndUserId()` prevents cross-user access
- **CSRF disabled** (correct for stateless JWT API)
- **Structured error responses** — no stack traces leaked to client
- **IP + User-Agent logging** in audit trail

### 🟠 Concerns

1. **CORS wildcard in production**
   ```java
   configuration.setAllowedOriginPatterns(List.of("*"));
   ```
   This allows any origin. Should be restricted to known frontend domains in production.

2. **No rate limiting on order queries**
   Only `checkoutLimiter` exists (10 req/60s). No rate limiting on:
   - `GET /orders` (could be abused for data scraping)
   - `GET /orders/{id}/status` (polling without WebSocket)
   - `POST /admin/orders/{id}/status` (no admin rate limit)

3. **Order ID enumeration**
   UUIDs (v4) are used — good, not sequential. But the error response `"Order not found: {uuid}"` confirms whether a UUID is valid format vs not found, which is minor info leak.

4. **No request body size limit configured**
   A malicious checkout request with 10,000 items would be processed. Need `@Size(max = 100)` on items list.

   **Current:**
   ```java
   @Valid @NotEmpty List<CartItem> items,  // No max size
   ```

5. **`deliveryAddress` is raw text (500 chars)**
   No XSS sanitization. If this is rendered in any web UI, it's a stored XSS vector. Should sanitize or validate format.

---

## 8. Infrastructure & Operational Review

### Dockerfile ✅ Well-Configured
- Multi-stage build (build → runtime)
- Non-root user (`app:1001`)
- ZGC garbage collector (low-latency, correct for Q-commerce)
- 75% RAM cap
- Liveness healthcheck via actuator

### Application Configuration

| Setting | Value | Assessment |
|---------|-------|-----------|
| HikariCP pool | 20 max, 5 idle | ⚠️ Low for production. After async checkout fix, adequate for reads |
| Connection timeout | 5000ms | ✅ Reasonable |
| Max lifetime | 1800000ms (30m) | ✅ Standard |
| Shutdown timeout | 30s | ✅ Graceful |
| JPA open-in-view | false | ✅ Correct — prevents lazy-loading outside transactions |
| Flyway | enabled | ✅ |
| Tracing probability | 1.0 (configurable) | ⚠️ 100% in dev, should be 0.01-0.1 in prod |

### Kafka Configuration
- Dead letter topic: `{topic}.DLT` — ✅
- Fixed backoff: 1s, 3 retries — ✅
- **Missing:** No consumer group config for fulfillment/delivery events (because no consumer exists)

### Temporal Configuration
- Workflow execution timeout: 5 minutes — ✅ (generous for checkout)
- Activity timeouts: 5-30s — ✅ Appropriate
- Retry: 2-3 attempts with backoff — ✅
- `DoNotRetry` for business exceptions (insufficient stock, payment declined) — ✅ Excellent

### Missing Operational Features
- **No circuit breaker** on REST clients (inventory, payment, cart). `resilience4j` is in the build but only rate limiter is configured. Need `@CircuitBreaker` on `RestInventoryClient`, `RestPaymentClient`.
- **No request/response logging** for external service calls (debugging production issues)
- **No health indicator** for Temporal connectivity
- **No custom metrics** (order count by status, checkout latency histogram, cancellation rate)

---

## 9. Code Quality Observations

### Positive Patterns
- **Records for DTOs** — immutable, concise
- **Builder pattern** for `CreateOrderCommand` — complex construction
- **Clean separation**: controller → service → repository
- **No circular dependencies**
- **`@Version` for optimistic locking** on Order entity

### Issues

1. **`Order` entity is an anemic domain model**
   All logic lives in `OrderService`. The `Order` entity is pure data with getters/setters. Consider moving state validation into the entity:
   ```java
   public void transitionTo(OrderStatus newStatus) {
       OrderStateMachine.validate(this.status, newStatus);
       this.status = newStatus;
   }
   ```

2. **Duplicate cancel logic**
   `cancelOrderByUser()` and `cancelOrder()` are 90% identical. Extract common method.

3. **`pickedStatus` is a `String`, not an enum**
   ```java
   private String pickedStatus = "PENDING";
   ```
   Should be an enum with values like `PENDING`, `PICKED`, `SUBSTITUTED`, `OUT_OF_STOCK`, `SKIPPED`.

4. **Missing `@Transactional(readOnly = true)` on some reads**
   `listOrders()` has it, but the approach is inconsistent.

5. **No DTO validation in `AdminOrderController`**
   `OrderStatusUpdateRequest` accepts any `OrderStatus`. Admin could try to set status to `PENDING` (which is only valid as initial state). The state machine catches this, but validation should happen at the API boundary.

6. **RestTemplate in Temporal activities**
   Activities run in Temporal worker threads. `RestTemplate` is blocking. Each blocked activity holds a worker thread. The code has TODOs to migrate to WebClient. **This should be prioritized** for throughput under load.

7. **`Order.setItems()` has O(n) clear-and-rebuild**
   ```java
   public void setItems(List<OrderItem> items) {
       this.items.clear();  // triggers DELETE ALL then INSERT ALL
   ```
   For initial creation this is fine, but if order modification is added, this pattern causes full replacement of all items on every update.

---

## 10. Priority-Ranked Recommendations

### 🔴 P0 — Must Fix Before Production

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 1 | **Implement outbox relay** (polling or Debezium CDC) | M | Events never reach consumers without this |
| 2 | **Make checkout async** (return 202 + polling) | M | Thread starvation under load |
| 3 | **Add missing order states** (READY_FOR_PICKUP, REFUND_PENDING, RETURNED at minimum) | S | Cannot model real fulfillment |
| 4 | **Add fulfillment/delivery event consumers** | L | Orders stuck in PLACED forever without manual admin intervention |
| 5 | **Add `(user_id, created_at DESC)` composite index** | S | Order history pagination performance |
| 6 | **Server-side total recalculation** in checkout | S | Price manipulation vulnerability |
| 7 | **Add `@Size(max = 50)` on checkout items list** | S | Denial-of-service via oversized orders |

### 🟠 P1 — Before Scale (>1M orders/month)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 8 | **Table partitioning** (by `created_at`, monthly) | L | Prevents query degradation at scale |
| 9 | **Cancellation window enforcement** (user cancel only before PACKING) | S | Prevent loss from late cancellations |
| 10 | **Order modification API** | L | Competitive table-stakes |
| 11 | **Circuit breakers** on REST clients | S | Cascade failure prevention |
| 12 | **ETA field + display** | S | Customer experience |
| 13 | **Admin order list/search** endpoint | M | Ops team needs this day-1 |
| 14 | **Restrict CORS origins** for production | S | Security |

### 🟡 P2 — Competitive Features

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 15 | Order splitting (multi-store) | L | Marketplace model |
| 16 | Substitution approval workflow | L | Instacart parity |
| 17 | Scheduled orders | M | Grocery use case |
| 18 | Reorder API | S | High-value, low-effort |
| 19 | Shareable tracking link | S | Customer sharing |
| 20 | Delivery notes/landmark fields | S | Delivery success rate |
| 21 | Post-delivery rating/review | M | Quality feedback loop |
| 22 | Push notification integration | M | Engagement |
| 23 | Recurring orders (subscribe & save) | L | Retention |
| 24 | Item-level partial refund | M | Customer satisfaction |

### Effort Key: S = <2 days, M = 3-5 days, L = 1-3 weeks

---

## Appendix A: File Inventory

| Path | Lines | Purpose |
|------|-------|---------|
| `domain/model/Order.java` | 255 | Core entity with 15 fields, `@Version` optimistic lock |
| `domain/model/OrderItem.java` | 116 | Line items with `pickedStatus` |
| `domain/model/OrderStatus.java` | 12 | 8-value enum |
| `domain/model/OrderStatusHistory.java` | 106 | Audit trail per transition |
| `domain/model/OutboxEvent.java` | 99 | Transactional outbox event |
| `domain/model/AuditLog.java` | 127 | Security/compliance audit |
| `domain/statemachine/OrderStateMachine.java` | 28 | Transition validation map |
| `service/OrderService.java` | 257 | Core business logic |
| `service/OutboxService.java` | 38 | Event publishing (same TX) |
| `service/OutboxCleanupJob.java` | 29 | Daily cleanup of sent events |
| `service/RateLimitService.java` | 43 | Per-user rate limiting |
| `service/UserErasureService.java` | 31 | GDPR anonymization |
| `service/AuditLogService.java` | 81 | Audit trail writer |
| `controller/CheckoutController.java` | 80 | Temporal workflow trigger |
| `controller/OrderController.java` | 71 | User-facing CRUD |
| `controller/AdminOrderController.java` | 61 | Admin operations |
| `workflow/CheckoutWorkflow.java` | 16 | Temporal interface |
| `workflow/CheckoutWorkflowImpl.java` | 132 | Saga with 4 activities |
| `workflow/activities/*` | ~120 | Activity interfaces + impls |
| `workflow/config/WorkerRegistration.java` | 51 | Temporal worker setup |
| `client/*` | ~200 | REST clients for inventory, payment, cart |
| `repository/*` | ~60 | JPA repositories |
| `security/*` | ~200 | JWT auth, filters, handlers |
| `config/*` | ~150 | Security, Kafka, Temporal, properties |
| `exception/*` | ~150 | Global handler + domain exceptions |
| `dto/**` | ~120 | Request/response records |
| `consumer/*` | ~60 | Kafka identity event consumer |
| `db/migration/V1-V7` | 7 files | Schema + indexes |

## Appendix B: Database Schema ERD

```
┌─────────────────────┐       ┌──────────────────────┐
│       orders         │       │    order_items        │
├─────────────────────┤       ├──────────────────────┤
│ id (PK, UUID)        │──┐   │ id (PK, UUID)         │
│ user_id (UUID)       │  │   │ order_id (FK) ────────┤
│ user_erased (bool)   │  │   │ product_id (UUID)     │
│ store_id (VARCHAR)   │  │   │ product_name          │
│ status (ENUM)        │  │   │ product_sku           │
│ subtotal_cents       │  │   │ quantity              │
│ discount_cents       │  │   │ unit_price_cents      │
│ total_cents          │  │   │ line_total_cents      │
│ currency             │  │   │ picked_status         │
│ coupon_code          │  │   └──────────────────────┘
│ reservation_id       │  │
│ payment_id           │  │   ┌──────────────────────┐
│ idempotency_key (UQ) │  │   │ order_status_history  │
│ cancellation_reason  │  │   ├──────────────────────┤
│ delivery_address     │  └──▶│ order_id (FK)         │
│ created_at           │      │ from_status (ENUM)    │
│ updated_at           │      │ to_status (ENUM)      │
│ version              │      │ changed_by            │
└─────────────────────┘      │ note                  │
                              │ created_at            │
┌─────────────────────┐      └──────────────────────┘
│   outbox_events      │
├─────────────────────┤      ┌──────────────────────┐
│ id (PK, UUID)        │      │    audit_log          │
│ aggregate_type       │      ├──────────────────────┤
│ aggregate_id         │      │ id (PK, UUID)         │
│ event_type           │      │ user_id               │
│ payload (JSONB)      │      │ action                │
│ created_at           │      │ entity_type           │
│ sent (bool)          │      │ entity_id             │
└─────────────────────┘      │ details (JSONB)       │
                              │ ip_address            │
                              │ user_agent            │
                              │ trace_id              │
                              │ created_at            │
                              └──────────────────────┘
```

---

*End of Review*
