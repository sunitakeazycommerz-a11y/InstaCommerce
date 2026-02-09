# Fulfillment Service — Deep Architecture Review

**Reviewer:** Senior Warehouse Operations Architect  
**Date:** 2025-07-02  
**Scope:** All Java source, SQL migrations, configuration — 76 files across 12 packages  
**Context:** Q-commerce platform targeting 500+ dark stores, 10-minute delivery SLA

---

## Executive Summary

The fulfillment-service provides a functional but **MVP-grade** pick → pack → deliver pipeline. It correctly implements event-driven task creation, optimistic locking, transactional outbox, rider assignment with `FOR UPDATE SKIP LOCKED`, and GDPR erasure. However, it **lacks the warehouse-ops primitives required for Q-commerce at scale**: no batch/wave picking, no bin-level locations, no priority queuing, no temperature-zone awareness, no weight verification, no quality checks, and no real substitution-approval flow. The current architecture will not sustain a 2–3 minute pick-to-pack SLA at 500+ stores without significant additions.

**Overall Verdict:** Solid foundation, not production-ready for Q-commerce scale.

| Category | Rating | Notes |
|---|---|---|
| Pick task creation | ✅ Good | Event-driven, idempotent |
| Pick workflow | ⚠️ Partial | No batch picking, no scan verification |
| Substitution logic | ⚠️ Partial | Auto-refund only, no customer approval |
| Packing | ❌ Missing | No pack verification, weight check, or bag assignment |
| Quality checks | ❌ Missing | No expiry, damage, or freshness validation |
| Delivery handoff | ✅ Good | Event-driven, auto-assignment |
| Partial fulfillment | ⚠️ Partial | Refund per item but no order-level policy |
| Optimistic locking | ✅ Good | @Version on PickTask and Delivery |
| Concurrent picks | ✅ Good | FOR UPDATE SKIP LOCKED on rider; version on tasks |
| SLA optimization | ❌ Missing | No pick-path optimization, no SLA timers |
| GDPR compliance | ✅ Good | User erasure with anonymization |
| Observability | ✅ Good | OTel tracing, structured audit log |

---

## 1. Architecture Overview

### 1.1 Tech Stack
- **Runtime:** Java 21, Spring Boot, JDK 21 with ZGC
- **Database:** PostgreSQL (Flyway-managed, 6 migrations)
- **Messaging:** Apache Kafka (consumer for `orders.events`, `identity.events`)
- **Eventing:** Transactional outbox pattern (CDC-ready via `outbox_events` table)
- **Security:** JWT (RSA public-key verification), role-based (`ADMIN`, `PICKER`, `RIDER`)
- **Observability:** OpenTelemetry (traces + metrics), Prometheus, structured audit log
- **Cloud:** GCP (Secret Manager, Cloud SQL socket factory)

### 1.2 Domain Model

```
OrderPlaced (Kafka) ──► PickTask (1:N) PickItem
                              │
                         markItem() / markPacked()
                              │
                         PickTask.COMPLETED ──► DeliveryService.assignRider()
                              │                       │
                         OrderPacked (outbox)    Delivery ──► Rider
                                                     │
                                              markDelivered()
                                                     │
                                              OrderDelivered (outbox)
```

### 1.3 Database Schema (6 migrations)

| Migration | Purpose |
|---|---|
| V1 | `pick_tasks`, `pick_items` with PG enums, unique constraint on `order_id` |
| V2 | `riders`, `deliveries` with unique constraint on `order_id` |
| V3 | `outbox_events` with partial index on `sent = false` |
| V4 | `audit_log` with indexes on `user_id`, `action`, `created_at` |
| V5 | `user_erased` boolean column on `pick_tasks` (GDPR) |
| V6 | `version` columns on `pick_tasks` and `deliveries` (optimistic locking) |

---

## 2. Business Logic — Detailed Analysis

### 2.1 Pick Task Creation

**File:** `PickService.createPickTask()`, triggered by `OrderEventConsumer`

**Flow:**
1. Kafka consumer receives `OrderPlaced` event from `orders.events` topic
2. Checks idempotency via `findByOrderId()` — returns early if task exists
3. Creates `PickTask` (PENDING) with child `PickItem` records via `CascadeType.ALL`
4. Catches `DataIntegrityViolationException` as secondary idempotency guard (unique constraint on `order_id`)

**✅ Strengths:**
- Double idempotency protection (query + constraint)
- Event-driven — no polling, instant reaction to order placement
- Transactional — task + items saved atomically

**❌ Issues & Gaps:**
- **No batch picking.** Each order creates exactly one pick task. In Q-commerce at peak load (e.g., 50+ concurrent orders), a single picker walks the same aisle repeatedly instead of batching 5–10 orders for items in the same zone.
- **No order priority.** All tasks are PENDING with no priority field. Express/priority orders sit in the same queue as standard orders.
- **No store capacity validation.** Task is created regardless of whether the store has available pickers or is at capacity.
- **No event deduplication by event ID.** Relies on `orderId` uniqueness, not Kafka event ID. If the same order is re-published with slight modifications, the duplicate is silently swallowed.

**Recommendation:**
```java
// Add to PickTask entity:
@Column(name = "priority")
private int priority; // 0=EXPRESS, 1=STANDARD, 2=SCHEDULED

@Column(name = "wave_id")
private UUID waveId; // For batch/wave picking
```

### 2.2 Pick Workflow

**Files:** `PickService.markItem()`, `PickService.markPacked()`, `PickController`

**Flow:**
1. Picker calls `POST /fulfillment/picklist/{orderId}/items/{productId}` with `MarkItemPickedRequest`
2. Service validates task state (not COMPLETED/CANCELLED)
3. Updates item status (PICKED / MISSING / SUBSTITUTED) with quantity
4. On first item pick, transitions task to IN_PROGRESS, records `startedAt`, sets `pickerId`
5. If item marked MISSING → triggers `SubstitutionService.handleMissingItem()`
6. When all items are non-PENDING → auto-completes task
7. Alternative: `POST /fulfillment/orders/{orderId}/packed` forces completion

**✅ Strengths:**
- Clean state machine: PENDING → IN_PROGRESS → COMPLETED
- Auto-completion when all items resolved
- Picker identity tracked via JWT principal
- Validation: picked quantity cannot exceed ordered quantity

**❌ Issues & Gaps:**

1. **No barcode/SKU scanning.** The API accepts `productId` (UUID) in the URL path — the picker app must know the UUID. In Q-commerce (Blinkit, Zepto), pickers scan a barcode which resolves to the product. There's no scan-to-verify flow.

2. **No item images or location data.** `PickItemResponse` returns `productName` but no image URL, aisle, shelf, or bin location. Pickers in dark stores need `aisle-shelf-bin` coordinates for efficient navigation.

3. **No pick-path optimization.** Items are returned in database insertion order. Q-commerce leaders (Zepto) use AI-optimized pick paths sorted by physical location to minimize walking time.

4. **No concurrent-pick contention prevention.** Two pickers can pick the same order simultaneously. While `@Version` on `PickTask` prevents lost updates, the UX is poor — one picker gets an `OptimisticLockException`. There's no task-locking/assignment mechanism.

5. **No partial quantity handling.** If an order has 5 units of milk and only 3 are available, the picker can set `pickedQty=3` and `status=PICKED`, but there's no automatic refund for the 2 missing units. The MISSING status triggers a full-missing refund for `quantity - pickedQty`, but only when status is set to MISSING, not PICKED with reduced quantity.

6. **`markPacked()` can be called before all items are picked.** It checks `areItemsPicked()` but this only verifies no PENDING items remain — if items are mid-pick (some PENDING), it correctly blocks. However, the check is at the pick_item level, not at a bag/pack level.

**Recommendation:** Add a `PickTask.assignedPickerId` with `SELECT ... FOR UPDATE SKIP LOCKED` when a picker starts working on a task, similar to the rider assignment pattern already in the codebase.

### 2.3 Substitution Service

**File:** `SubstitutionService.handleMissingItem()`

**Flow:**
1. Called when picker marks an item as MISSING
2. Validates `missingQty > 0`, `item.quantity > 0`, and `paymentId` exists
3. Calculates refund: `Math.round((double) lineTotalCents * missingQty / quantity)`
4. Calls `InventoryClient.releaseStock()` to return reserved inventory
5. Calls `PaymentClient.refund()` with idempotency key
6. Publishes `OrderModified` outbox event

**✅ The Integer Division Fix Is Correct:**
```java
long refundAmount = Math.round((double) item.getLineTotalCents() * missingQty / item.getQuantity());
```
This correctly handles proportional refunds. For example: `lineTotalCents=1000`, `missingQty=1`, `quantity=3` → `Math.round(333.33)` = 333 cents. Without the `(double)` cast and `Math.round()`, pure integer division would give `333` anyway in this case, but for `lineTotalCents=100, missingQty=1, quantity=3` → `Math.round(33.33)` = 33 vs integer `33`. The fix matters for cases like `lineTotalCents=100, missingQty=2, quantity=3` → `Math.round(66.67)` = **67** cents (correct: customer is owed 2/3 of the total) vs integer `66` (customer shortchanged by 1 cent). **The fix is mathematically correct and favors the customer.**

**❌ Issues & Gaps:**

1. **No customer approval flow.** The substitution is unilateral — picker marks MISSING, system auto-refunds. There's no:
   - Push notification to customer asking "Accept substitute?"
   - Customer approval/rejection flow
   - Time-boxed approval window (e.g., 30 seconds before auto-refund)
   - This is a critical gap vs Instacart (real-time chat) and Blinkit (in-app substitution approval)

2. **No auto-substitution rules engine.** When a product is missing, the system doesn't suggest alternatives. Q-commerce platforms maintain substitution mappings (e.g., Amul Toned Milk 500ml → Mother Dairy Toned Milk 500ml) based on:
   - Same category + similar price
   - Customer history preferences
   - Store inventory availability

3. **SUBSTITUTED status is dead-end.** The `PickItemStatus.SUBSTITUTED` enum exists and the picker can set `substituteProductId`, but:
   - No price adjustment for the substitute (if substitute costs more/less)
   - No inventory reservation for the substitute product
   - No inventory release for the original product when substituted (only released when MISSING)
   - The `substituteProductId` is stored but never used downstream

4. **No refund for partial quantity on PICKED status.** If picker sets `status=PICKED, pickedQty=3` for an item with `quantity=5`, the 2 missing units are not refunded. The refund only triggers on `status=MISSING`.

5. **Fire-and-forget external calls.** `RestPaymentClient.refund()` and `RestInventoryClient.releaseStock()` catch and log HTTP errors but don't retry or compensate. If the refund HTTP call fails:
   - No retry mechanism
   - No compensation/saga
   - The outbox event is still published (it's written in the same transaction as the DB changes)
   - The customer doesn't get their refund

**Critical Bug — RestInventoryClient has no error handling:**
```java
// RestInventoryClient.releaseStock() — NO try/catch
restTemplate.postForObject(baseUrl + "/inventory/adjust", request, Object.class);
```
Unlike `RestPaymentClient` and `RestOrderClient` which catch `HttpClientErrorException`, `RestInventoryClient` lets exceptions propagate. If the inventory service is down, the **entire `markItem()` transaction rolls back**, meaning the picker's work is lost. This is inconsistent with the other clients' fire-and-forget approach.

### 2.4 Packing

**Current State:** The `markPacked()` endpoint (`POST /fulfillment/orders/{orderId}/packed`) simply transitions the pick task to COMPLETED. There is **no packing domain model**.

**❌ Completely Missing:**

| Feature | Status | Q-Commerce Standard |
|---|---|---|
| Pack verification (scan each item into bag) | ❌ Missing | Blinkit: mandatory scan |
| Weight check (expected vs actual) | ❌ Missing | Blinkit: weight tolerance ±5% |
| Bag assignment (bag ID tracking) | ❌ Missing | All platforms: bag barcode |
| Temperature zones (frozen/chilled/ambient) | ❌ Missing | Zepto: separate cold-chain bags |
| Bag count per order | ❌ Missing | Standard for rider handoff |
| Pack photo/proof | ❌ Missing | Instacart: pack photo |

**Recommendation:** Introduce a `PackTask` entity:
```sql
CREATE TABLE pack_tasks (
    id UUID PRIMARY KEY,
    pick_task_id UUID REFERENCES pick_tasks(id),
    bag_count INT,
    total_weight_grams INT,
    expected_weight_grams INT,
    weight_verified BOOLEAN DEFAULT false,
    temperature_zones TEXT[], -- ['FROZEN', 'CHILLED', 'AMBIENT']
    packed_at TIMESTAMPTZ,
    packed_by UUID
);
```

### 2.5 Quality Checks

**Current State:** None.

**❌ Completely Missing:**

| Check | Status | Impact |
|---|---|---|
| Expiry date validation | ❌ | Picker could pack expired products |
| Best-before threshold (e.g., >3 days remaining) | ❌ | Customer dissatisfaction |
| Damage check (visual inspection flag) | ❌ | No accountability |
| Freshness scoring (produce grade A/B/C) | ❌ | No quality differentiation |
| FSSAI compliance (India food safety) | ❌ | Regulatory risk |

**Recommendation:** Add quality fields to `PickItem`:
```java
@Column(name = "expiry_date")
private LocalDate expiryDate;

@Column(name = "freshness_score")
private Integer freshnessScore; // 1-5

@Column(name = "damage_flag")
private boolean damageFlag;
```

### 2.6 Delivery Handoff

**Files:** `DeliveryService.assignRider()`, `RiderAssignmentService`, `RiderRepository`

**Flow:**
1. When pick task completes → `publishPacked()` calls `deliveryService.assignRider(task)`
2. `RiderAssignmentService` finds next available rider:
   ```sql
   SELECT * FROM riders
   WHERE store_id = :storeId AND is_available = true
   ORDER BY (SELECT MAX(d.dispatched_at) FROM deliveries d WHERE d.rider_id = r.id)
   ASC NULLS FIRST
   LIMIT 1
   FOR UPDATE SKIP LOCKED
   ```
3. Rider marked unavailable, Delivery created with status ASSIGNED
4. `OrderDispatched` outbox event published
5. Order status updated to `OUT_FOR_DELIVERY` via Spring event → HTTP call to order-service

**✅ Strengths:**
- **Excellent rider query.** `FOR UPDATE SKIP LOCKED` prevents race conditions when multiple orders complete simultaneously. Orders skip locked riders and pick the next available one.
- **Least-recently-used assignment.** Riders are ordered by their most recent dispatch time, ensuring fair distribution.
- **Event-driven handoff.** Rider assignment is triggered automatically on pack completion — no polling.
- **Idempotent delivery creation.** Checks for existing delivery before creating new one.
- **Graceful degradation.** If no rider available, logs warning and returns `Optional.empty()` — order remains packed but unassigned.

**❌ Issues & Gaps:**

1. **No rider re-assignment mechanism.** If a rider is assigned but never picks up (goes offline), there's no timeout or reassignment flow. The delivery stays in ASSIGNED status indefinitely.

2. **No proximity/zone-based assignment.** Riders are assigned by store only, not by proximity to the delivery address. For dark store models this is acceptable (rider is at the store), but there's no validation that the rider is physically at the store.

3. **No rider capacity tracking.** A rider can only handle one order at a time (`is_available` boolean). Multi-order batching for nearby deliveries is not supported.

4. **Missing `PICKED_UP` and `IN_TRANSIT` transitions.** The `DeliveryStatus` enum has these values, but there are no API endpoints or service methods to transition through them. The flow jumps from `ASSIGNED` directly to `DELIVERED`.

5. **`TransactionalEventListener(AFTER_COMMIT)` for order status update is fire-and-forget.** `OrderStatusEventListener` calls `orderClient.updateStatus()` after commit. If the HTTP call fails, the order service never learns the status changed. This should use the outbox pattern (which is already in place for other events) instead of a synchronous HTTP call.

### 2.7 Partial Fulfillment

**Current Handling:**
- Individual items can be marked MISSING or SUBSTITUTED
- MISSING items trigger `SubstitutionService.handleMissingItem()` → inventory release + refund
- Order still completes even if all items are missing

**❌ Issues:**

1. **No minimum-fulfillment policy.** If an order has 10 items and 9 are missing, the order still completes with 1 item and a delivery is dispatched. There should be a configurable threshold (e.g., "cancel order if <30% items fulfilled").

2. **No order-level refund summary.** Each missing item triggers an independent refund. There's no aggregated view of "total refunded" for the order. The customer gets multiple small refund notifications.

3. **No delivery fee adjustment.** If most items are missing, the customer still pays full delivery fee. Some platforms waive delivery fees for heavily short-fulfilled orders.

---

## 3. SLA & Performance Analysis

### 3.1 Pick-to-Pack Time

**Target:** < 3 minutes (for 10-minute delivery: ~2 min pick + ~1 min pack + ~7 min ride)

**Current System Performance Characteristics:**

| Factor | Assessment | Impact |
|---|---|---|
| Pick path optimization | ❌ None | Picker walks randomly |
| Item location data | ❌ None | Picker searches by product name |
| Batch picking | ❌ None | One trip per order |
| Scan verification | ❌ None | No scan overhead (but no accuracy) |
| API latency per item | ⚠️ HTTP POST per item | ~50ms × N items network overhead |
| Database queries | ⚠️ N+1 risk | `findByPickTask_OrderIdAndProductId` per item |

**Critical SLA Gap:** Without bin/shelf locations, an average 15-item order in a dark store (1000+ SKUs) requires the picker to visually search for each item. Industry data from dark store operations:
- **Without location data:** 15–25 seconds per item → 3.75–6.25 minutes for 15 items
- **With aisle-shelf-bin:** 5–8 seconds per item → 1.25–2 minutes for 15 items
- **With optimized pick path:** 3–5 seconds per item → 0.75–1.25 minutes for 15 items

**The system cannot meet a 3-minute SLA without item location data.**

### 3.2 Concurrent Picks

**Assessment: ✅ Generally Well-Handled**

- `@Version` on `PickTask` and `Delivery` prevents lost updates (optimistic locking)
- `FOR UPDATE SKIP LOCKED` on rider assignment prevents contention
- Unique constraints on `order_id` in both `pick_tasks` and `deliveries` prevent duplicates

**Remaining Risks:**

1. **PickItem has no `@Version`.** Two pickers could simultaneously mark different items on the same task. While each item update is independent, the task-level status transition (PENDING → IN_PROGRESS) could conflict if two pickers start the same task simultaneously. The `@Version` on `PickTask` would catch this, but the second picker gets an error rather than graceful handling.

2. **No picker-task locking.** There's no mechanism to assign/lock a task to a specific picker. The `pickerId` is set on first item pick, but another picker could also start picking items from the same task.

### 3.3 Optimistic Locking

**Assessment: ✅ Correctly Implemented**

```java
// PickTask.java
@Version
private Long version;

// Delivery.java
@Version
private Long version;
```

- V6 migration adds `version BIGINT NOT NULL DEFAULT 0` to both tables
- JPA/Hibernate will automatically increment version on each update
- `OptimisticLockException` thrown on concurrent modification

**Gap:** No explicit retry logic. When `OptimisticLockException` occurs, the request fails with 500. Should add `@Retryable` or manual retry for critical operations.

---

## 4. Missing Features — Detailed Gap Analysis

### 4.1 Wave/Batch Picking

**Status: ❌ Not Implemented**

**What's needed:** Group 3–5 orders with items in the same aisle zone. Picker makes one trip, picks items for all orders, then sorts at the packing station.

**Impact:** At peak load (30+ concurrent orders per store), individual picking means 30 separate trips through the same aisles. Wave picking reduces trips by 60–70%.

**Implementation sketch:**
```sql
CREATE TABLE pick_waves (
    id UUID PRIMARY KEY,
    store_id VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ
);

ALTER TABLE pick_tasks ADD COLUMN wave_id UUID REFERENCES pick_waves(id);
```

### 4.2 Priority Queuing

**Status: ❌ Not Implemented**

**Current:** `listPendingTasks()` returns tasks ordered by default (insertion order). No priority differentiation.

**What's needed:**
- Priority levels: EXPRESS (0), STANDARD (1), SCHEDULED (2)
- Express orders jump to front of pick queue
- SLA countdown timer per order (e.g., "must be picked within 90 seconds")
- Priority inherited from order type

**Impact:** All customers get the same pick speed. Express/premium customers see no benefit.

### 4.3 Picker Performance Metrics

**Status: ❌ Not Implemented**

**What's needed:**
- Items picked per hour (IPH)
- Pick accuracy rate (correct items / total items)
- Average pick time per order
- Idle time between tasks
- Dashboard for store managers

**Available data points (already in the model):**
- `PickTask.startedAt` and `PickTask.completedAt` — can derive pick duration
- `PickTask.pickerId` — can attribute performance
- `PickItem.status` — can derive accuracy (MISSING/SUBSTITUTED = errors)

**The data exists; only the analytics layer is missing.**

### 4.4 Bin/Shelf Location

**Status: ❌ Not Implemented**

**What's needed:**
```
Aisle A → Shelf 3 → Bin 7 → "Amul Toned Milk 500ml"
```

This is the **#1 feature needed** for Q-commerce pick performance. Without it, pick times are 3–5x slower.

**Implementation:** This likely belongs in the inventory/catalog service, but the fulfillment service needs to include location data in `PickItemResponse`:
```java
public record PickItemResponse(
    // ...existing fields...
    String aisle,
    String shelf,
    String bin,
    int sortOrder  // optimized pick sequence
) {}
```

### 4.5 Return Handling

**Status: ❌ Not Implemented**

No return flow exists in the fulfillment service. For Q-commerce, returns are typically processed at the dark store:
- Customer returns item to rider
- Rider returns to store
- Store restocks or discards
- Refund triggered

---

## 5. Q-Commerce Competitor Comparison

### Feature Matrix

| Feature | Instacommerce (Current) | Blinkit | Zepto | Instacart |
|---|---|---|---|---|
| **Pick Task Creation** | ✅ Event-driven | ✅ Event-driven | ✅ Event-driven | ✅ Event-driven |
| **Digital Pick List** | ⚠️ Text only | ✅ With item images | ✅ With images + location | ✅ With images |
| **Barcode Scanning** | ❌ | ✅ Mandatory scan | ✅ Mandatory scan | ✅ Mandatory scan |
| **Item Location (Aisle-Shelf-Bin)** | ❌ | ✅ Shelf-level | ✅ Bin-level | N/A (retail store) |
| **Pick Path Optimization** | ❌ | ⚠️ Zone-based | ✅ AI-optimized | ⚠️ Aisle-based |
| **Batch/Wave Picking** | ❌ | ✅ 3-5 order batches | ✅ Dynamic waves | N/A (single shopper) |
| **Pick Time Target** | ❌ No SLA | 2 min | 90 sec | 15-30 min |
| **Weight Verification** | ❌ | ✅ ±5% tolerance | ✅ Automated scale | ✅ Manual |
| **Temperature Zones** | ❌ | ✅ 3 zones | ✅ 3 zones | ⚠️ Insulated bags |
| **Substitution Approval** | ❌ Auto-refund | ✅ In-app approval | ✅ In-app + chat | ✅ Real-time chat |
| **Auto-Substitution Rules** | ❌ | ✅ Category-based | ✅ ML-based | ✅ Customer preference |
| **Expiry Date Check** | ❌ | ✅ Scan-validated | ✅ Automated | ⚠️ Shopper judgment |
| **Freshness Scoring** | ❌ | ⚠️ Basic | ✅ AI-graded | ⚠️ Shopper judgment |
| **Pack Verification** | ❌ | ✅ Scan-out | ✅ Scan + weight | ✅ Photo proof |
| **Priority Queuing** | ❌ | ✅ Express priority | ✅ Dynamic priority | ⚠️ Batch-based |
| **Picker Performance** | ❌ | ✅ IPH dashboard | ✅ Real-time scoring | ✅ Shopper ratings |
| **Rider Assignment** | ✅ LRU-based | ✅ Proximity-based | ✅ AI-optimized | N/A (shopper delivers) |
| **Optimistic Locking** | ✅ | ✅ | ✅ | ✅ |
| **Return Handling** | ❌ | ✅ In-store | ✅ In-store | ✅ In-store + doorstep |

### Key Competitive Gaps (Priority Order)

1. **Bin-level item location** — 3-5x pick speed improvement
2. **Barcode scan verification** — Eliminates wrong-item errors (~2-5% without scanning)
3. **Customer substitution approval** — Major customer satisfaction driver
4. **Wave/batch picking** — 60-70% reduction in picker walking at peak load
5. **Weight verification** — Prevents under-filling and fraud
6. **Temperature zone separation** — Food safety compliance requirement

---

## 6. Code Quality & Bugs

### 6.1 Critical Bug: RestInventoryClient Missing Error Handling

**File:** `RestInventoryClient.java`

```java
@Override
public void releaseStock(UUID productId, String storeId, int quantity, String reason, String referenceId) {
    InventoryAdjustRequest request = new InventoryAdjustRequest(productId, storeId, quantity, reason, referenceId);
    restTemplate.postForObject(baseUrl + "/inventory/adjust", request, Object.class);
    // ⚠️ NO try/catch — exception propagates and rolls back the entire markItem() transaction
}
```

**Impact:** If the inventory service is down when a picker marks an item as MISSING, the entire transaction rolls back. The picker's work is lost, and they have to re-do the operation. Both `RestPaymentClient` and `RestOrderClient` have try/catch blocks for this exact scenario — `RestInventoryClient` is the inconsistent outlier.

**Fix:** Add the same error handling pattern:
```java
try {
    restTemplate.postForObject(baseUrl + "/inventory/adjust", request, Object.class);
} catch (HttpClientErrorException | HttpServerErrorException ex) {
    logger.error("Inventory adjust HTTP error for product {} store {}: {} {}",
        productId, storeId, ex.getStatusCode(), ex.getMessage());
} catch (Exception ex) {
    logger.error("Inventory adjust failed for product {} store {}: {}",
        productId, storeId, ex.getMessage());
}
```

### 6.2 Medium Issue: OrderStatusEventListener Uses HTTP Instead of Outbox

**File:** `OrderStatusEventListener.java`

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleOrderStatusUpdate(OrderStatusUpdateEvent event) {
    orderClient.updateStatus(event.orderId(), event.status(), event.note());
}
```

This fires HTTP calls to the order service after commit. If the HTTP call fails, the status update is lost forever. Meanwhile, the service already uses the outbox pattern for `OrderPacked`, `OrderDispatched`, and `OrderDelivered` events. The order status update should be routed through the outbox too.

### 6.3 Medium Issue: Missing `PICKED_UP` / `IN_TRANSIT` Delivery Transitions

`DeliveryStatus` enum defines: `ASSIGNED → PICKED_UP → IN_TRANSIT → DELIVERED → FAILED`

But the codebase only transitions: `ASSIGNED → DELIVERED`. There's no API endpoint or service method for the intermediate states. The `trackingTimeline` only shows `OUT_FOR_DELIVERY` (dispatch) and `DELIVERED` — no real-time rider tracking.

### 6.4 Low Issue: PickItem Missing @Version

`PickTask` and `Delivery` have `@Version` for optimistic locking. `PickItem` does not. While concurrent updates to the same pick item are unlikely in the current flow (items are addressed by `orderId + productId`), adding `@Version` would be a defensive measure.

### 6.5 Low Issue: Rider Entity Missing @Version

The `Rider` entity lacks `@Version`. While `FOR UPDATE SKIP LOCKED` handles contention at the query level, direct updates to rider availability (`rider.setAvailable(false)`) could theoretically conflict if two transactions modify the same rider outside the locked query path (e.g., `markDelivered` releasing a rider + admin simultaneously editing the rider).

### 6.6 Informational: Default ETA is 15 Minutes

```yaml
fulfillment:
  delivery:
    default-eta-minutes: 15
```

For a 10-minute delivery platform, the default ETA of 15 minutes is misaligned. This should be configurable per store and dynamically calculated based on distance, traffic, and historical data.

---

## 7. Security Assessment

**Overall: ✅ Good**

| Aspect | Status | Notes |
|---|---|---|
| Authentication | ✅ | JWT with RSA public key verification |
| Authorization | ✅ | Role-based: ADMIN, PICKER, RIDER |
| Endpoint protection | ✅ | `/admin/**` → ADMIN, `/fulfillment/picklist/**` → PICKER, `delivered` → RIDER |
| CSRF | ✅ | Disabled (stateless API, correct for JWT) |
| CORS | ⚠️ | `AllowedOriginPatterns: *` — too permissive for production |
| Audit logging | ✅ | IP, User-Agent, trace ID, action details |
| GDPR erasure | ✅ | User anonymization via Kafka event |
| Error responses | ✅ | Consistent `ErrorResponse` format, no stack trace leakage |

**Recommendation:** Restrict CORS origins to known frontend domains in production.

---

## 8. Observability & Operations

**Overall: ✅ Good Foundation**

| Feature | Status |
|---|---|
| Distributed tracing (OTel) | ✅ |
| Metrics (Prometheus/OTLP) | ✅ |
| Health checks (liveness + readiness) | ✅ |
| Audit log | ✅ |
| Structured logging | ✅ (Logstash encoder) |
| Graceful shutdown | ✅ (30s timeout) |
| Dead letter queue | ✅ (Kafka DLT with 3 retries) |
| Outbox pattern | ✅ |

**Gaps:**
- No custom business metrics (pick time histogram, items per order, substitution rate)
- No SLA breach alerting
- No real-time dashboard data endpoints for store managers

---

## 9. Prioritized Recommendations

### P0 — Fix Before Production

| # | Item | Effort | Impact |
|---|---|---|---|
| 1 | Add error handling to `RestInventoryClient` | 30 min | Prevents picker transaction rollbacks |
| 2 | Move order status updates to outbox pattern | 2 hours | Prevents lost status updates |
| 3 | Add `@Version` to `PickItem` and `Rider` entities | 30 min | Defensive concurrency safety |
| 4 | Restrict CORS origins | 15 min | Security hardening |

### P1 — Required for Q-Commerce SLA

| # | Item | Effort | Impact |
|---|---|---|---|
| 5 | Item location data (aisle-shelf-bin) in pick list | 1 week | 3-5x pick speed improvement |
| 6 | Barcode scan verification flow | 1 week | Eliminates ~3% wrong-item rate |
| 7 | Customer substitution approval flow | 2 weeks | Major CX improvement |
| 8 | Priority queuing (EXPRESS/STANDARD) | 3 days | Premium customer experience |
| 9 | Pick task assignment/locking (claim task) | 3 days | Prevents wasted picker effort |
| 10 | `PICKED_UP` / `IN_TRANSIT` delivery transitions | 2 days | Real-time delivery tracking |

### P2 — Required for Scale (500+ stores)

| # | Item | Effort | Impact |
|---|---|---|---|
| 11 | Wave/batch picking | 2 weeks | 60-70% less walking at peak |
| 12 | Pack verification (scan-out + weight check) | 2 weeks | Quality assurance |
| 13 | Temperature zone handling | 1 week | Food safety compliance |
| 14 | Minimum fulfillment threshold | 2 days | Avoid uneconomical deliveries |
| 15 | Picker performance metrics/dashboard | 1 week | Operational visibility |
| 16 | Dynamic ETA calculation | 1 week | Accurate delivery promises |

### P3 — Competitive Parity

| # | Item | Effort | Impact |
|---|---|---|---|
| 17 | Pick path optimization (sorted by location) | 1 week | Further pick time reduction |
| 18 | Auto-substitution rules engine | 3 weeks | Reduces missing-item refunds |
| 19 | Expiry date / freshness checks | 1 week | Quality differentiation |
| 20 | Return handling flow | 2 weeks | Complete order lifecycle |
| 21 | Multi-order rider batching | 2 weeks | Delivery cost optimization |
| 22 | Rider proximity-based assignment | 1 week | Faster pickup |

---

## 10. Summary Verdict

The fulfillment-service is a **well-engineered MVP** with clean architecture, proper event-driven patterns, and solid infrastructure choices (outbox, optimistic locking, audit logging, GDPR compliance). The codebase is well-organized and production-quality from a software engineering perspective.

However, as a **warehouse operations system for Q-commerce**, it implements only the minimum viable path: create task → pick items → mark packed → assign rider → mark delivered. It lacks the operational primitives that differentiate a 10-minute delivery platform from a standard e-commerce fulfillment system.

**The single highest-impact investment is bin-level item locations + pick path optimization.** This alone can bring pick times from 5+ minutes to under 2 minutes. Combined with barcode scanning and wave picking, the system can achieve the sub-3-minute pick-to-pack SLA required for 10-minute delivery.

The four P0 items (inventory client error handling, outbox for status updates, version annotations, CORS restriction) should be addressed before any production deployment.
