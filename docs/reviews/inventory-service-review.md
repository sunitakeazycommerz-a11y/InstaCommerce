# Inventory Service — Deep Architecture Review

**Reviewer:** Senior Supply Chain Architect  
**Date:** 2025-07-02  
**Service:** `services/inventory-service/`  
**Platform context:** Q-commerce, 20M+ users, 500+ dark stores  
**Stack:** Spring Boot 3 / JDK 21 / PostgreSQL / Flyway / ShedLock / Resilience4j / OTEL

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Business Logic Review](#3-business-logic-review)
4. [SLA & Performance Review](#4-sla--performance-review)
5. [Missing Features for Q-Commerce](#5-missing-features-for-q-commerce)
6. [Q-Commerce Competitive Comparison](#6-q-commerce-competitive-comparison)
7. [Security Review](#7-security-review)
8. [Operational Readiness](#8-operational-readiness)
9. [Prioritized Recommendations](#9-prioritized-recommendations)

---

## 1. Executive Summary

The inventory-service implements a **solid Reserve → Confirm → Cancel pattern** with pessimistic locking, idempotency, reservation TTL, outbox-based eventing, and audit logging. For an early-stage Q-commerce platform, the foundations are correct. However, at the stated scale of **20M+ users and 500+ dark stores**, there are **critical gaps** that will cause stock accuracy issues, performance bottlenecks, and competitive disadvantage against Blinkit/Zepto/Instacart.

### Scorecard

| Area | Rating | Notes |
|------|--------|-------|
| Stock Model | ⚠️ Partial | 2-field model (on_hand, reserved) — insufficient for perishables & damage tracking |
| Reservation Flow | ✅ Good | TTL, idempotency, expiry job, pessimistic locking all present |
| Stock Adjustments | ⚠️ Partial | Audit trail exists but lacks `actor_id` population, no batch import |
| Multi-Warehouse | ✅ Present | Per-store stock via `store_id`, but no transfer or pooling |
| Concurrency Safety | ✅ Good | `SELECT FOR UPDATE` with deterministic lock ordering |
| Low Stock Alerts | ⚠️ Basic | Static threshold only, no velocity-based or per-SKU thresholds |
| Expiry Tracking | ❌ Missing | Fatal gap for perishable Q-commerce |
| Return-to-Stock | ❌ Missing | No return processing |
| Performance at Scale | ⚠️ Concern | HikariCP pool=20 insufficient for 500+ stores at peak |
| Observability | ✅ Good | OTEL tracing, structured logging, Prometheus metrics |

**Overall verdict: Production-viable for MVP; requires significant hardening for scale.**

---

## 2. Architecture Overview

### File Inventory (57 files reviewed)

```
src/main/java/com/instacommerce/inventory/
├── config/          5 files  — SecurityConfig, ReservationProperties, InventoryProperties, SchedulerConfig, ShedLockConfig
├── controller/      2 files  — StockController, ReservationController
├── domain/model/    7 files  — StockItem, Reservation, ReservationLineItem, ReservationStatus, StockAdjustmentLog, OutboxEvent, AuditLog
├── dto/
│   ├── mapper/      1 file   — InventoryMapper
│   ├── request/     6 files  — ReserveRequest, ConfirmRequest, CancelRequest, StockCheckRequest, StockAdjustRequest, InventoryItemRequest
│   └── response/    6 files  — ReserveResponse, ReservedItemResponse, StockCheckResponse, StockCheckItemResponse, ErrorResponse, ErrorDetail
├── exception/       8 files  — GlobalExceptionHandler, ApiException + 5 domain exceptions, TraceIdProvider
├── repository/      6 files  — StockItemRepository, ReservationRepository, ReservationLineItemRepository, StockAdjustmentLogRepository, OutboxEventRepository, AuditLogRepository
├── security/        5 files  — JwtAuthenticationFilter, DefaultJwtService, JwtService, JwtKeyLoader, RestAuthenticationEntryPoint, RestAccessDeniedHandler
├── service/         7 files  — ReservationService, InventoryService, ReservationExpiryJob, OutboxService, OutboxCleanupJob, AuditLogService, AuditLogCleanupJob, RateLimitService
└── InventoryServiceApplication.java

src/main/resources/
├── application.yml
├── logback-spring.xml
└── db/migration/    7 SQL files — V1 through V7

Dockerfile, build.gradle.kts
```

### Data Flow

```
[Client] → JWT Auth → Controller → Service → EntityManager (PESSIMISTIC_WRITE)
                                       ├── StockItemRepository (read/write)
                                       ├── ReservationRepository
                                       ├── StockAdjustmentLogRepository (audit)
                                       ├── AuditLogService → AuditLogRepository
                                       └── OutboxService → OutboxEventRepository
                                                              ↓
                                              [CDC/Polling → Message Broker] (external)
```

---

## 3. Business Logic Review

### 3.1 Stock Model — ⚠️ INSUFFICIENT

**Current model (`StockItem`):**
```java
int onHand;     // Physical count in store
int reserved;   // Held by pending reservations
// available = onHand - reserved (computed, not persisted)
```

**Database constraints (V1 migration):**
```sql
CHECK (on_hand >= 0)
CHECK (reserved >= 0)
CHECK (reserved <= on_hand)
```

**Assessment:** The 2-field model is clean and the DB constraints are excellent for preventing negative stock. However, it is **dangerously simplified** for Q-commerce operations at scale.

**Missing stock dimensions:**

| Field | Why It's Needed | Blinkit/Zepto Equivalent |
|-------|----------------|--------------------------|
| `damaged` | Write-off tracking without adjusting on_hand directly | Blinkit separates "damaged" from sellable |
| `in_transit` | Stock arriving from hub to dark store | Zepto tracks "incoming" stock for availability projection |
| `safety_stock` | Minimum buffer before triggering replenishment | Industry standard for demand-driven supply chains |
| `committed` | Confirmed but not yet picked/dispatched | Blinkit's "promised" tier separates this from reserved |
| `expiry_date` | FEFO picking for perishables (90%+ of Q-commerce SKUs) | All major players track lot-level expiry |
| `batch_id` / `lot_number` | Traceability for recalls | Required for FSSAI compliance in India |

**Recommended target model:**
```
on_hand = available + reserved + committed + damaged
available = on_hand - reserved - committed - damaged - safety_stock
```

**Severity: HIGH** — Without `damaged` and `safety_stock`, operators will constantly over-count available stock, leading to failed picks and cancelled orders — the #1 killer of Q-commerce NPS.

---

### 3.2 Reservation Flow — ✅ WELL IMPLEMENTED (minor issues)

**Flow implemented:**
```
Reserve (PENDING) → Confirm (CONFIRMED) → stock decremented
                  → Cancel  (CANCELLED) → reserved released
                  → Expire  (EXPIRED)   → reserved released (automatic)
```

**What works well:**

1. **Idempotency** — `findByIdempotencyKey()` prevents duplicate reservations. Unique constraint on DB (`uq_reservation_idempotency`). ✅
2. **TTL** — Configurable `reservation.ttl-minutes` (default 5 min). `expires_at` stored per reservation. ✅
3. **Automatic expiry** — `ReservationExpiryJob` runs every 30s via `@Scheduled`, protected by ShedLock for multi-instance safety. ✅
4. **Lock ordering** — Items sorted by `productId` before locking to prevent deadlocks. ✅ Excellent.
5. **State machine guards** — Confirm checks PENDING status, checks expiry. Cancel is idempotent for CANCELLED/EXPIRED. ✅
6. **Outbox events** — `StockReserved`, `StockConfirmed`, `StockReleased` events published transactionally via outbox pattern. ✅

**Issues found:**

| # | Issue | Severity | Details |
|---|-------|----------|---------|
| R-1 | **Expiry job has no pagination/limit** | MEDIUM | `findByStatusAndExpiresAtBefore()` loads ALL expired reservations into memory. At scale with 500 stores, if the job falls behind (network partition, DB slowdown), this could OOM the service or cause extremely long-running transactions. **Fix:** Add `LIMIT 100` and batch processing. |
| R-2 | **Confirm does not re-validate stock** | LOW | On confirm, `on_hand` is decremented without checking if it would go negative. The DB constraint `reserved <= on_hand` protects against this, but the error would be an opaque `DataIntegrityViolationException` rather than a meaningful `InsufficientStockException`. In practice, since reserved is decremented simultaneously, this is algebraically safe — but only if no concurrent stock adjustment reduced `on_hand` between reserve and confirm. |
| R-3 | **No `orderId` on Reservation entity** | LOW | The `idempotencyKey` is used as `orderId` in the event payload (`eventPayload.put("orderId", request.idempotencyKey())`), but the reservation entity has no explicit `orderId` field. This couples idempotency to order identity. |
| R-4 | **Reservation TTL may be too short** | INFO | 5 minutes default. For Q-commerce this is reasonable (Blinkit uses ~3-5 min). But during payment gateway slowdowns (India's UPI infra), 5 minutes can cause legitimate orders to expire. Consider making TTL per-payment-method. |
| R-5 | **`releaseReserved()` can set reserved negative** | MEDIUM | If a reservation is expired by the job AND a concurrent cancel arrives, `releaseReserved()` could be called twice. The `cancel()` method checks status first, but there's a TOCTOU gap: two threads could both read PENDING, both proceed. The DB constraint `reserved >= 0` would catch this, but it throws a raw constraint violation. **Mitigation:** The `expireReservation(UUID)` method does check `status != PENDING` and returns early, which helps. But the race window between the check and the lock acquisition in `releaseReserved()` is real. |

---

### 3.3 Stock Adjustment — ⚠️ PARTIAL

**Current implementation (`InventoryService.adjustStock()`):**
- Applies delta to `on_hand` with `PESSIMISTIC_WRITE` lock ✅
- Validates `updatedOnHand >= 0` and `reserved <= updatedOnHand` ✅
- Creates `StockAdjustmentLog` with delta, reason, referenceId ✅
- Writes to `AuditLog` with full details ✅
- Publishes `StockAdjusted` outbox event ✅
- Checks low stock after negative adjustments ✅

**Issues:**

| # | Issue | Severity | Details |
|---|-------|----------|---------|
| A-1 | **`actor_id` never populated** | HIGH | `StockAdjustmentLog.actorId` exists but is never set in `adjustStock()`. The `auditLogService.log(null, ...)` passes `null` for userId. The authenticated user's ID from JWT is available via `SecurityContextHolder` but is not extracted. This means **adjustment audit trail has no actor attribution** — a compliance and fraud risk. |
| A-2 | **No structured adjustment reasons** | MEDIUM | `reason` is a free-text `VARCHAR(100)`. Should be an enum: `RECEIVING`, `DAMAGE_WRITEOFF`, `CYCLE_COUNT`, `RETURN`, `EXPIRY_WRITEOFF`, `TRANSFER_IN`, `TRANSFER_OUT`, `ADMIN_CORRECTION`. Free text makes reporting and analytics unreliable. |
| A-3 | **No batch/bulk adjust endpoint** | HIGH | Only single-item adjustment via `POST /inventory/adjust`. A dark store receiving a delivery of 200 SKUs requires 200 API calls. At 500 stores × daily deliveries = 100K+ individual API calls for receiving alone. **Must add** `POST /inventory/adjust-batch`. |
| A-4 | **`StockAdjustmentLog` has no index** | MEDIUM | V4 migration creates the table with zero indexes. Querying by `product_id`, `store_id`, or `created_at` for reconciliation will be full table scans on what will become a very large table. |
| A-5 | **Adjust endpoint rate-limited only by role** | LOW | Unlike `/check`, the `/adjust` endpoint has no rate limiting — only `@PreAuthorize("hasRole('ADMIN')")`. A compromised admin token could rapidly corrupt stock data. |

---

### 3.4 Multi-Warehouse — ⚠️ BASIC

**What exists:**
- `StockItem` has `store_id` (VARCHAR(50)) ✅
- Unique constraint on `(product_id, store_id)` ✅
- Reservation is scoped to `store_id` ✅
- All queries filter by `store_id` ✅

**What's missing:**

| Feature | Status | Impact |
|---------|--------|--------|
| Cross-store transfer | ❌ Missing | When Store A has excess and Store B has zero, no mechanism to transfer. Blinkit's hub-spoke model relies on inter-store transfers. |
| Stock pooling | ❌ Missing | No ability to present aggregated availability across nearby stores for fallback fulfillment. |
| Store hierarchy | ❌ Missing | No concept of hub → dark store → micro-store. `store_id` is flat. |
| Store metadata | ❌ Missing | No store capacity, operating hours, or geofence. This service should at least validate that `store_id` exists via an external service call. Currently, any string is accepted as `store_id`. |

---

### 3.5 Negative Stock Prevention — ✅ ROBUST

**Defense-in-depth approach:**

1. **Application layer:** `available < item.quantity()` check in `reserve()` (line 72)
2. **Application layer:** `updatedOnHand < 0` check in `adjustStock()` (line 76-77)
3. **Application layer:** `reserved > updatedOnHand` check in `adjustStock()` (line 79-80)
4. **Database layer:** `CHECK (on_hand >= 0)` — hard floor
5. **Database layer:** `CHECK (reserved >= 0)` — hard floor
6. **Database layer:** `CHECK (reserved <= on_hand)` — invariant
7. **Locking:** `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) prevents concurrent modification

**Assessment:** This is **excellent**. The combination of application-level checks with DB constraints as a safety net is exactly right. Even if a race condition bypasses the application check, the DB constraint will reject the transaction.

**One subtle concern:** The `@Version` field on `StockItem` (optimistic locking) is defined but **never actually used** — all mutations go through `PESSIMISTIC_WRITE` via `EntityManager`, bypassing the version check. The `@Version` annotation is harmless (Hibernate will still increment it), but it's misleading. Either remove it or use optimistic locking as the primary strategy with pessimistic as fallback for hot rows.

---

### 3.6 Low Stock Alerts — ⚠️ BASIC

**Current implementation:**
```java
int threshold = inventoryProperties.getLowStockThreshold(); // global, default 10
if (available <= threshold) { publish LowStockAlert event }
```

**Issues:**

| # | Issue | Severity | Details |
|---|-------|----------|---------|
| L-1 | **Global static threshold** | HIGH | A single `low-stock-threshold=10` for all products across all stores. A store selling 500 units/day of milk needs threshold=50, while a store selling 2 units/day of premium coffee needs threshold=3. At 500 stores × thousands of SKUs, a single threshold is meaningless. |
| L-2 | **No per-product or per-store threshold** | HIGH | Need `low_stock_threshold` column on `stock_items` table or a separate threshold config table. |
| L-3 | **No velocity-based dynamic threshold** | MEDIUM | Zepto calculates reorder points based on trailing 7-day demand × lead time. Static thresholds cannot adapt to seasonal or promotional demand changes. |
| L-4 | **Alert deduplication missing** | MEDIUM | Every reservation or adjustment that leaves stock below threshold fires a new `LowStockAlert` event. High-frequency products could generate hundreds of duplicate alerts per day. Need a "last alert sent" timestamp or a "alert_active" flag. |
| L-5 | **No out-of-stock event** | MEDIUM | There's a low stock alert but no explicit `OutOfStock` event when available hits zero. This is critical for hiding products from the customer-facing catalog in real-time. |

---

### 3.7 Stock Reconciliation — ❌ MISSING

**There is no stock reconciliation feature whatsoever.** This means:

- No physical count entry endpoint
- No cycle count workflow
- No variance reporting (system count vs physical count)
- No snapshot of stock at a point in time for auditing
- No reconciliation approval workflow

**Impact:** For a Q-commerce platform with 500+ dark stores, stock drift is inevitable (theft, damage, spoilage, miscounts). Without reconciliation, stock accuracy will degrade to <85% within months. Industry benchmark for Q-commerce is 97%+ stock accuracy. Blinkit runs daily cycle counts with automated variance flagging.

---

## 4. SLA & Performance Review

### 4.1 Reservation Latency — ⚠️ CONCERN

**Critical path:** Customer taps "Buy" → `POST /inventory/reserve` → response → payment initiation

**Current bottleneck analysis:**

```
1. JWT validation           ~1-2ms
2. Idempotency check        ~2-5ms (indexed lookup)
3. Sort items               ~0ms (in-memory)
4. FOR each item:
   a. SELECT FOR UPDATE     ~5-20ms (row lock acquisition, may wait)
   b. Availability check    ~0ms (in-memory)
   c. Update reserved       ~2-5ms
5. Save reservation         ~5-10ms
6. Save line items          ~2-5ms per item
7. Outbox event publish     ~2-5ms
8. Low stock check + event  ~0-5ms
9. Commit transaction       ~5-10ms
```

**Estimated p99 for 5-item cart:** 80-150ms under low contention. Under high contention (same product, many concurrent reserves), p99 could spike to **500ms-2s** due to lock wait times.

**Issues:**

| # | Issue | Severity | Details |
|---|-------|----------|---------|
| P-1 | **No lock wait timeout** | HIGH | `PESSIMISTIC_WRITE` with no `javax.persistence.lock.timeout` hint. Under contention, a thread could wait indefinitely for a row lock. PostgreSQL's `lock_timeout` setting should be set (e.g., 2 seconds) as a safety net. |
| P-2 | **Sequential lock acquisition** | MEDIUM | Items are locked one at a time in a loop. For a 10-item cart, this means 10 sequential roundtrips to the DB. Could batch via `WHERE product_id IN (...) ORDER BY product_id FOR UPDATE`, acquiring all locks in a single query. |
| P-3 | **Individual saves in loop** | MEDIUM | `stockItemRepository.save(stock)` called per item in the reservation loop. Should batch with `saveAll()`. |
| P-4 | **No p99 SLO definition** | INFO | No defined latency targets. Recommend: reserve p99 < 100ms, check p99 < 50ms, adjust p99 < 100ms. |

### 4.2 Concurrent Reservations — ✅ CORRECT, ⚠️ SCALABILITY CONCERN

**Scenario:** Product P with 100 units, 1000 concurrent checkout attempts.

**How it works:**
1. All 1000 threads hit `lockStockItem()` → `SELECT ... FOR UPDATE`
2. PostgreSQL serializes access: only 1 thread holds the row lock at a time
3. Each thread checks availability, increments reserved, commits, releases lock
4. Next thread proceeds with updated `reserved` value
5. After 100 successful reservations, remaining 900 get `InsufficientStockException`

**This is correct.** Pessimistic locking guarantees atomicity.

**Scalability concern:** With 1000 concurrent threads contending for the same row:
- Lock queue forms in PostgreSQL
- Each waiter holds a database connection from HikariCP
- With `maximum-pool-size: 20`, only 20 threads can actively wait
- Remaining 980 threads block waiting for a HikariCP connection
- HikariCP `connection-timeout: 5000` (5s) means mass `SQLTransientConnectionException` after 5s

**At peak load with 500 stores, flash sales, or popular items, this WILL cause cascading failures.**

### 4.3 Database Locking — ✅ CORRECT STRATEGY

```java
// ReservationService.lockStockItem() — line 256-268
entityManager.createQuery(
    "SELECT s FROM StockItem s WHERE s.productId = :pid AND s.storeId = :sid",
    StockItem.class)
    .setParameter("pid", productId)
    .setParameter("sid", storeId)
    .setLockMode(LockModeType.PESSIMISTIC_WRITE)  // → SELECT ... FOR UPDATE
    .getSingleResult();
```

**Assessment:**
- ✅ Row-level locking (not table-level)
- ✅ Lock ordering by `productId` prevents deadlocks
- ✅ Scoped to `(product_id, store_id)` — different stores don't contend
- ✅ `READ_COMMITTED` isolation level is correct for this pattern
- ⚠️ No `SKIP LOCKED` or `NOWAIT` option — see recommendations

### 4.4 Connection Pool — ⚠️ UNDERSIZED

**Current config (application.yml):**
```yaml
hikari:
  maximum-pool-size: 20
  minimum-idle: 5
  connection-timeout: 5000    # 5 seconds
  max-lifetime: 1800000       # 30 minutes
```

**Analysis for 500 stores at peak:**

| Metric | Calculation | Result |
|--------|-------------|--------|
| Peak reservations/sec (estimate) | 500 stores × 10 orders/min / 60 | ~83 reserve/sec |
| Avg reservation hold time | ~50ms per lock per item × 3 items avg | ~150ms |
| Connections needed for reservations alone | 83 × 0.15 | ~13 |
| Plus: stock checks, adjustments, expiry job, outbox writes | +50% overhead | ~20 |
| Plus: burst headroom (3x) | ×3 | ~60 |

**Recommendation:** `maximum-pool-size: 50-80` for production. The current `20` will be exhausted during any traffic spike.

**Additional missing HikariCP settings:**
```yaml
hikari:
  maximum-pool-size: 60
  minimum-idle: 15
  connection-timeout: 3000     # fail fast, don't hold thread for 5s
  max-lifetime: 1800000
  leak-detection-threshold: 30000  # detect connection leaks
  validation-timeout: 3000
```

---

## 5. Missing Features for Q-Commerce

### 5.1 Batch Stock Update — ❌ MISSING (CRITICAL)

**Current state:** Only `POST /inventory/adjust` for single-item adjustment.

**Why critical:** Dark stores receive deliveries multiple times per day. A typical delivery has 100-300 SKUs. Without batch update:
- 300 sequential API calls per delivery × 3 deliveries/day × 500 stores = **450,000 individual API calls/day** just for receiving
- Each call acquires a pessimistic lock independently — massive connection pool pressure
- No transactional atomicity — if call #150 fails, the first 149 are already committed

**Required endpoint:**
```
POST /inventory/adjust-batch
{
  "storeId": "STORE-001",
  "referenceId": "PO-2025-001234",
  "reason": "RECEIVING",
  "items": [
    {"productId": "...", "delta": 50},
    {"productId": "...", "delta": 30},
    ...
  ]
}
```

**Implementation notes:**
- Must acquire all locks in sorted order (existing pattern)
- Single transaction for atomicity
- Single audit log entry with batch reference
- Return partial success details if some items fail

---

### 5.2 Stock Snapshot — ❌ MISSING

**Why needed:**
- End-of-day stock reports for accounting
- Variance analysis between shifts
- Stock value calculation for finance
- Regulatory compliance (GST stock registers in India)

**Approach options:**
1. **CDC-based:** Capture all changes via outbox events → build snapshots in data warehouse
2. **Periodic snapshot table:** Scheduled job takes `INSERT INTO stock_snapshots SELECT * FROM stock_items`
3. **Temporal table:** PostgreSQL temporal extension (bi-temporal audit)

The outbox pattern is already in place, so option 1 is lowest effort.

---

### 5.3 Expiry Tracking — ❌ MISSING (CRITICAL FOR Q-COMMERCE)

**Impact:** 60-80% of Q-commerce SKUs are perishable (dairy, produce, meat, bread). Without expiry tracking:
- No FEFO (First Expired First Out) picking
- Expired products shipped to customers → health risk, legal liability
- No automated expiry write-off → stock inflation
- No near-expiry discounting signals

**Required data model extension:**
```sql
CREATE TABLE stock_lots (
    id          UUID PRIMARY KEY,
    stock_item_id UUID NOT NULL REFERENCES stock_items(id),
    batch_number VARCHAR(50),
    expiry_date DATE NOT NULL,
    quantity    INT NOT NULL CHECK (quantity >= 0),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stock_lots_expiry ON stock_lots (expiry_date);
```

**Reservation changes:** Reserve from the lot with the earliest expiry (FEFO). This fundamentally changes the reservation logic from "decrement from a single row" to "decrement from specific lots."

---

### 5.4 Return to Stock — ❌ MISSING

**Current state:** No return processing. Once a reservation is confirmed, stock is permanently decremented. Customer returns have no path back into inventory.

**Required flow:**
```
Return Request → Damage Assessment → { Good: return to on_hand | Damaged: write-off to damaged }
```

**Required endpoint:**
```
POST /inventory/return
{
  "storeId": "STORE-001",
  "orderId": "ORD-123",
  "items": [
    {"productId": "...", "quantity": 1, "condition": "GOOD"},
    {"productId": "...", "quantity": 1, "condition": "DAMAGED"}
  ]
}
```

---

### 5.5 Demand Forecasting Interface — ❌ MISSING

**Current state:** No inbound interface for demand signals. Low stock alerts are fire-and-forget outbox events with no feedback loop.

**What Zepto does:** Their inventory service exposes:
- Stock velocity API (units sold per hour by SKU by store)
- Replenishment recommendation API (based on lead time + demand forecast)
- Auto-replenishment triggers (when available < forecast demand for next N hours)

**Minimum required:**
1. Expose stock movement history via API for the forecasting service to consume
2. Accept replenishment quantity recommendations and auto-generate purchase orders
3. Dynamic safety stock calculation: `safety_stock = avg_daily_demand × lead_time_days × safety_factor`

---

## 6. Q-Commerce Competitive Comparison

### Feature Matrix

| Feature | InstaCommerce (Current) | Blinkit | Zepto | Instacart |
|---------|------------------------|---------|-------|-----------|
| **Stock tiers** | 2 (on_hand, reserved) | 3 (available, promised, actual) | 3 + incoming | Multi-retailer, per-aisle |
| **Reservation TTL** | ✅ 5 min (configurable) | ~3 min | ~3 min | ~15 min (grocery) |
| **Idempotency** | ✅ Key-based | ✅ | ✅ | ✅ |
| **Pessimistic locking** | ✅ SELECT FOR UPDATE | Redis SETNX | Redis + DB hybrid | Distributed locks |
| **Expiry/FEFO** | ❌ | ✅ Lot-level | ✅ FEFO picking | ✅ Retailer-provided |
| **Batch receiving** | ❌ | ✅ POS scan integration | ✅ Barcode-driven | ✅ Retailer API sync |
| **Cross-store transfer** | ❌ | ✅ Hub-spoke | ✅ Micro-hub | N/A (retailer-owned) |
| **Dynamic thresholds** | ❌ Static global | ✅ Per-SKU | ✅ Time-of-day velocity | ✅ ML-based |
| **Cycle count** | ❌ | ✅ Daily mandatory | ✅ Shift-end count | ✅ Section-based |
| **Stock accuracy score** | ❌ | ✅ Per-store metric | ✅ Real-time dashboard | ✅ Per-retailer SLA |
| **Real-time POS sync** | ❌ | ✅ Bidirectional | ✅ Push-based | ✅ Retailer integration |
| **Substitution candidates** | ❌ | ❌ | ⚠️ Basic | ✅ ML-ranked |
| **Auto-replenishment** | ❌ | ✅ | ✅ | ✅ |
| **Return processing** | ❌ | ✅ | ✅ | ✅ |
| **Outbox/event-driven** | ✅ | ✅ Kafka | ✅ Kafka | ✅ Event bus |

### Key Competitive Gaps

1. **Blinkit differentiator:** Real-time bidirectional POS sync ensures dark store operators see the same stock as the app. InstaCommerce has no POS integration point.
2. **Zepto differentiator:** Time-of-day demand prediction adjusts stock thresholds hourly. Their system knows that milk demand peaks 6-9 AM and auto-raises thresholds before that window.
3. **Instacart differentiator:** Stock accuracy scoring per retailer with SLA enforcement. They compute a "freshness score" for each stock data point based on last sync time.

---

## 7. Security Review

### What's done well:
- ✅ JWT-based authentication with RSA public key verification
- ✅ Role-based access control (`ADMIN` for adjustments)
- ✅ Stateless session management
- ✅ CORS configured (though `*` origin pattern is risky for production)
- ✅ Structured error responses that don't leak stack traces
- ✅ X-Forwarded-For handling for IP resolution
- ✅ Trace ID propagation through error responses

### Security issues:

| # | Issue | Severity | Details |
|---|-------|----------|---------|
| S-1 | **CORS allows all origins** | MEDIUM | `config.setAllowedOriginPatterns(List.of("*"))` with `allowCredentials(true)`. In production, this should be restricted to the specific frontend domain. |
| S-2 | **No rate limiting on reservation endpoints** | MEDIUM | `/inventory/reserve`, `/inventory/confirm`, `/inventory/cancel` have no rate limiting. Only `/inventory/check` is rate-limited. A compromised service token could rapidly exhaust stock. |
| S-3 | **`storeId` not validated against user's permissions** | HIGH | Any authenticated user can reserve stock in any store. There's no check that the user/service has access to the specified `store_id`. In a multi-tenant environment, this is a privilege escalation vector. |
| S-4 | **Adjustment audit doesn't capture actor** | HIGH | See A-1 above. |

---

## 8. Operational Readiness

### ✅ What's production-ready:
1. **Flyway migrations** — Schema versioned, repeatable deployments
2. **Structured logging** — LogstashEncoder for JSON logs, ELK-compatible
3. **OTEL tracing** — Full distributed tracing with configurable sampling
4. **Prometheus metrics** — Actuator + Micrometer + OTLP export
5. **Health checks** — Liveness and readiness probes with DB health
6. **Graceful shutdown** — 30s timeout configured
7. **ShedLock** — Scheduled jobs safe for multi-instance deployment
8. **ZGC** — Low-latency garbage collector, correct choice for p99 targets
9. **Non-root Docker** — Runs as user `app` (UID 1001) ✅
10. **Outbox pattern** — Reliable event publishing without distributed transactions

### ⚠️ Operational concerns:

| # | Issue | Severity | Details |
|---|-------|----------|---------|
| O-1 | **No database connection pool metrics** | MEDIUM | HikariCP exposes metrics via Micrometer automatically, but there's no alerting configured. At `max-pool-size: 20`, pool exhaustion will be silent. |
| O-2 | **Outbox events not consumed** | HIGH | Events are written to `outbox_events` and cleaned up after 30 days, but **there is no outbox poller/CDC consumer in this service**. The `sent` flag is never set to `true` by this service. This implies an external CDC system (Debezium) reads the table — but this should be documented. If no consumer exists, the outbox table will grow unboundedly. |
| O-3 | **Audit log cleanup deletes without limit** | MEDIUM | `AuditLogCleanupJob.purgeExpiredLogs()` calls `deleteByCreatedAtBefore(cutoff)` which Spring Data translates to `DELETE FROM audit_log WHERE created_at < ?`. On a 2-year-old table with millions of rows, this will be a single massive DELETE that locks the table and blocks all audit writes. **Fix:** Batch delete with `LIMIT 10000` in a loop. |
| O-4 | **No index on `stock_adjustment_log`** | MEDIUM | See A-4. As this table grows (potentially millions of rows), any query will full-scan. |
| O-5 | **Reservation expiry job unbounded** | MEDIUM | See R-1. Should process in batches of 100-500. |
| O-6 | **No circuit breaker for DB calls** | LOW | Resilience4j is included as a dependency but only used for rate limiting. No circuit breaker wraps database calls. During DB failover, all threads will block on connection attempts. |

---

## 9. Prioritized Recommendations

### 🔴 P0 — Do Before Next Release (Blocks Scale)

| # | Recommendation | Effort | Impact |
|---|----------------|--------|--------|
| 1 | **Populate `actor_id` in stock adjustments** from JWT subject | 1 day | Compliance, fraud prevention |
| 2 | **Increase HikariCP pool to 50-60** and add `leak-detection-threshold` | 1 hour | Prevents pool exhaustion at scale |
| 3 | **Add `LIMIT` to reservation expiry job** — batch processing | 2 hours | Prevents OOM and long transactions |
| 4 | **Add `lock_timeout`** hint to `PESSIMISTIC_WRITE` queries (2-3 seconds) | 1 hour | Prevents indefinite lock waits |
| 5 | **Add batch stock update endpoint** (`POST /inventory/adjust-batch`) | 3 days | Unblocks receiving operations |
| 6 | **Add indexes to `stock_adjustment_log`** on `(product_id, store_id, created_at)` | 1 hour | Prevents full table scans |
| 7 | **Batch-delete in audit log cleanup** | 2 hours | Prevents table-level locks during cleanup |

### 🟡 P1 — Next Sprint (Competitive Necessity)

| # | Recommendation | Effort | Impact |
|---|----------------|--------|--------|
| 8 | **Per-product/store low stock thresholds** — add `low_stock_threshold` to `stock_items` | 3 days | Meaningful replenishment signals |
| 9 | **Low stock alert deduplication** — don't fire if alert already active | 1 day | Reduces event noise by 90%+ |
| 10 | **Add `OutOfStock` event** when available hits zero | 1 day | Real-time catalog availability |
| 11 | **Structured adjustment reasons** (enum instead of free text) | 2 days | Reliable analytics |
| 12 | **Rate limit reservation endpoints** | 1 day | Security hardening |
| 13 | **Store-level authorization** — validate user can access `store_id` | 2 days | Multi-tenant security |
| 14 | **Restrict CORS origins** for production profile | 1 hour | Security hardening |

### 🟢 P2 — Next Quarter (Q-Commerce Parity)

| # | Recommendation | Effort | Impact |
|---|----------------|--------|--------|
| 15 | **Expiry/lot tracking** (FEFO) — new `stock_lots` table and reservation logic | 2-3 weeks | Perishable safety, regulatory compliance |
| 16 | **Stock reconciliation** — physical count entry, variance reporting | 2 weeks | Stock accuracy maintenance |
| 17 | **Return-to-stock** endpoint with damage assessment | 1 week | Reverse logistics support |
| 18 | **Cross-store transfer** API | 1 week | Hub-spoke fulfillment |
| 19 | **Stock snapshot** — periodic or CDC-based point-in-time snapshots | 1 week | Finance and reporting |
| 20 | **Dynamic safety stock** — velocity-based threshold calculation | 2 weeks | Demand-responsive replenishment |

### 🔵 P3 — Future (Competitive Advantage)

| # | Recommendation | Effort | Impact |
|---|----------------|--------|--------|
| 21 | **Move hot-path locking to Redis** — `SETNX` for reservation, async DB persist | 3-4 weeks | 10x latency reduction on reserve |
| 22 | **Stock accuracy scoring** — per-store, per-SKU accuracy % | 2 weeks | Operational visibility |
| 23 | **Demand forecasting integration** — accept ML signals for auto-replenishment | 3-4 weeks | Predictive supply chain |
| 24 | **Substitution candidate API** — suggest alternatives when OOS | 2 weeks | Reduces cancellations |
| 25 | **POS integration** — real-time bidirectional sync with dark store systems | 4-6 weeks | Blinkit parity |

---

## Appendix A: File-by-File Findings Summary

| File | Status | Key Findings |
|------|--------|-------------|
| `StockItem.java` | ⚠️ | 2-field model insufficient; `@Version` present but unused in practice |
| `Reservation.java` | ✅ | TTL, idempotency, status tracking all correct |
| `ReservationLineItem.java` | ✅ | Clean, no issues |
| `ReservationStatus.java` | ✅ | PENDING→CONFIRMED/CANCELLED/EXPIRED — complete state machine |
| `StockAdjustmentLog.java` | ⚠️ | `actor_id` never populated |
| `OutboxEvent.java` | ✅ | Proper outbox pattern with JSON payload |
| `AuditLog.java` | ✅ | Comprehensive fields including IP, user agent, trace ID |
| `ReservationService.java` | ✅/⚠️ | Core logic sound; expiry unbounded; lock timeout missing |
| `InventoryService.java` | ⚠️ | actor_id missing; no batch adjust; `checkLowStock` duplicated with ReservationService |
| `ReservationExpiryJob.java` | ⚠️ | No LIMIT on expired reservation query |
| `OutboxService.java` | ✅ | `Propagation.MANDATORY` ensures transactional integrity — excellent |
| `OutboxCleanupJob.java` | ⚠️ | No evidence that `sent` flag is ever set to `true` |
| `AuditLogService.java` | ✅ | Proper IP/UA/trace extraction |
| `AuditLogCleanupJob.java` | ⚠️ | Unbounded DELETE — needs batching |
| `RateLimitService.java` | ✅ | Caffeine-backed per-key rate limiter, clean implementation |
| `StockController.java` | ⚠️ | Rate limiting only on `/check`, not on `/adjust` |
| `ReservationController.java` | ⚠️ | No rate limiting on any endpoint |
| `SecurityConfig.java` | ⚠️ | Wildcard CORS origins |
| `SchedulerConfig.java` | ✅ | Simple `@EnableScheduling` |
| `ShedLockConfig.java` | ✅ | `usingDbTime()` — correct for multi-instance |
| `ReservationProperties.java` | ✅ | Configurable TTL and expiry interval |
| `InventoryProperties.java` | ⚠️ | `lowStockThreshold` is global, not per-product |
| `V1__create_stock_items.sql` | ✅ | Excellent CHECK constraints, unique index |
| `V2__create_reservations.sql` | ✅ | Partial indexes on PENDING status — good optimization |
| `V3__create_reservation_line_items.sql` | ✅ | FK with CASCADE delete, positive qty check |
| `V4__create_stock_adjustment_log.sql` | ⚠️ | No indexes at all |
| `V5__create_audit_log.sql` | ✅ | Proper indexes on user_id, action, created_at |
| `V6__create_outbox_events.sql` | ✅ | Partial index on unsent events |
| `V7__create_shedlock.sql` | ✅ | Standard ShedLock schema |
| `application.yml` | ⚠️ | Pool too small; no lock timeout; no leak detection |
| `Dockerfile` | ✅ | Multi-stage, non-root, ZGC, health check |
| `build.gradle.kts` | ✅ | Clean dependencies, Testcontainers included |

---

## Appendix B: Recommended Migration (V8)

```sql
-- V8__add_stock_adjustment_indexes_and_safety_stock.sql

-- Fix: Missing indexes on stock_adjustment_log
CREATE INDEX idx_adj_product_store ON stock_adjustment_log (product_id, store_id);
CREATE INDEX idx_adj_created_at ON stock_adjustment_log (created_at);
CREATE INDEX idx_adj_reference ON stock_adjustment_log (reference_id) WHERE reference_id IS NOT NULL;

-- Feature: Per-product low stock threshold
ALTER TABLE stock_items ADD COLUMN low_stock_threshold INT;
ALTER TABLE stock_items ADD COLUMN safety_stock INT NOT NULL DEFAULT 0;
ALTER TABLE stock_items ADD CONSTRAINT chk_safety_stock_non_negative CHECK (safety_stock >= 0);
```

---

*End of review. Questions and discussion welcome.*
