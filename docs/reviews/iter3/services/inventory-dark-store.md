# Inventory & Dark Store — Deep Implementation Guide

```
Cluster ID:          C4
Cluster name:        Inventory & Dark Store
Services:            services/inventory-service, services/warehouse-service
Iter 2 wave(s):      Wave 2
Risk register items: C4 (dark-store loop closure)
Engineering owner:   TBD
SRE owner:           TBD
ADR references:      ADR-TBD
Last updated:        2026-03-07
Status:              DRAFT
```

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Reserve / Confirm / Cancel Semantics — Deep Audit](#2-reserve--confirm--cancel-semantics--deep-audit)
3. [Concurrency Control — Exact Mechanism and Gaps](#3-concurrency-control--exact-mechanism-and-gaps)
4. [Dark-Store Truth — The storeId Gap](#4-dark-store-truth--the-storeid-gap)
5. [Warehouse-to-Inventory Mapping](#5-warehouse-to-inventory-mapping)
6. [Stock Freshness and Drift](#6-stock-freshness-and-drift)
7. [Operational Overrides](#7-operational-overrides)
8. [Rollout of Safer Reservation Behavior](#8-rollout-of-safer-reservation-behavior)
9. [Validation Checklist](#9-validation-checklist)
10. [Rollback Guidance](#10-rollback-guidance)
11. [Observability and Alerting Requirements](#11-observability-and-alerting-requirements)
12. [Prioritized Finding Register](#12-prioritized-finding-register)

---

## 1. Executive Summary

`inventory-service` and `warehouse-service` are the two halves of InstaCommerce's dark-store operational loop. The inventory-service owns per-SKU per-store stock counts and the reserve/confirm/cancel lifecycle that gates checkout. The warehouse-service owns the physical store registry, operating hours, delivery zones, and hourly capacity. Together they should form a closed, authoritative truth about what can be sold, from where, and right now.

**They do not yet form a closed loop.** The key gaps are:

| Gap | Severity | What breaks |
|-----|----------|-------------|
| `storeId` in inventory is a free-form VARCHAR; warehouse-service is never consulted | HIGH | Reservations can be created against non-existent, inactive, or maintenance stores |
| `StockConfirmed` and `StockReleased` event schemas omit `items[]` at the contract level | HIGH | Consumers cannot tell which SKUs were confirmed or released without re-fetching |
| Expiry job processes reservations one-by-one inside a loop; no batch SELECT FOR UPDATE | MEDIUM | Under backlog, expiry job locks one row per DB roundtrip, creating O(n) lock contention |
| No stock reconciliation mechanism | HIGH | Stock drift at 500+ stores is inevitable; no path to correct it programmatically |
| `orderId` is conflated with `idempotencyKey` on the reservation entity | MEDIUM | Downstream event consumers and data-platform models cannot join on a stable order identity |
| TOCTOU window between expiry check and releaseReserved across concurrent paths | MEDIUM | Double-release of `reserved` is possible; DB constraint absorbs it as a raw `DataIntegrityViolationException` |
| Warehouse store `id` is UUID; inventory `store_id` is VARCHAR(50); no referential link | HIGH | These two services have completely separate identifier spaces with no enforcement |
| No per-SKU or per-store low-stock threshold | HIGH | A global threshold of 10 is meaningless across a heterogeneous product and store estate |
| No `OutOfStock` event | HIGH | When available hits zero, no event is published to hide the product in catalog |

The implementation program in this guide closes each of these in a sequenced, rollback-safe way.

---

## 2. Reserve / Confirm / Cancel Semantics — Deep Audit

### 2.1 State Machine (as-implemented)

```
PENDING  ──confirm──▶  CONFIRMED   (on_hand--, reserved-- atomically)
         ──cancel───▶  CANCELLED   (reserved-- only; on_hand unchanged)
         ──expire───▶  EXPIRED     (reserved-- only; via ReservationExpiryJob)
```

Terminal states: `CONFIRMED`, `CANCELLED`, `EXPIRED`. No re-entry is possible.

State guards in `ReservationService`:

| Operation | Guard | Result on violation |
|-----------|-------|---------------------|
| `confirm` | status == PENDING AND now < expiresAt | `ReservationStateException` or `ReservationExpiredException` |
| `cancel` | status != CONFIRMED | For CANCELLED/EXPIRED: idempotent no-op. For CONFIRMED: `ReservationStateException` |
| `expireReservation` | status == PENDING | For any other: silent no-op |

**Assessment — confirm idempotency is partial.** `confirm()` returns early if `status == CONFIRMED` ✅ but does NOT guard against a second confirm call that arrives while the first is mid-transaction (i.e., still PENDING in another thread). The pessimistic lock on `StockItem` rows serializes stock mutations but there is no pessimistic lock on the `Reservation` row during confirm. Two concurrent confirm requests for the same `reservationId` can both read `status == PENDING`, both proceed into `lockStockItem()`, both decrement `on_hand` and `reserved`, and both set `status = CONFIRMED`. The second commit will violate `CHECK (on_hand >= 0)` only if `on_hand` drops to negative, which algebraically it won't (reserved was double-decremented, not on_hand alone)—but `reserved` will go negative, and the DB constraint `CHECK (reserved >= 0)` will catch it as a `DataIntegrityViolationException`. This surfaces as HTTP 500, not a clean 409.

**Fix: Lock the reservation row itself during confirm.**

```java
// In ReservationService.confirm()
Reservation reservation = entityManager
    .createQuery("SELECT r FROM Reservation r WHERE r.id = :id", Reservation.class)
    .setParameter("id", reservationId)
    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
    .setHint("jakarta.persistence.lock.timeout", inventoryProperties.getLockTimeoutMs())
    .getSingleResult();
```

This serializes concurrent confirms so the second one reads `status == CONFIRMED` and exits cleanly.

### 2.2 Reserve — Idempotency

The idempotency key is a client-supplied string checked via `findByIdempotencyKey()`. The unique constraint `uq_reservation_idempotency` on the DB provides the final guard.

**Concern:** The idempotency check is a read-then-write; under a burst of identical requests, two threads can both miss on `findByIdempotencyKey()` and both attempt to insert a `Reservation`. The DB unique constraint on `idempotency_key` will reject the second insert with a `DataIntegrityViolationException`. The `GlobalExceptionHandler` should map this to HTTP 409, but as of the current code it does not explicitly handle `DataIntegrityViolationException`. The result is an HTTP 500 or a raw Spring error body for the second concurrent duplicate request.

**Fix:** Catch `DataIntegrityViolationException` in `GlobalExceptionHandler` (or in `ReservationService.reserve()`) and re-fetch by idempotency key to return the original response:

```java
} catch (DataIntegrityViolationException ex) {
    // Concurrent duplicate; return the winner's result
    return reservationRepository.findByIdempotencyKey(request.idempotencyKey())
        .map(InventoryMapper::toReserveResponse)
        .orElseThrow(() -> new IllegalStateException("Idempotency race could not be resolved"));
}
```

### 2.3 Reserve — Lock Ordering

Lock ordering is implemented correctly:

```java
List<InventoryItemRequest> sortedItems = request.items().stream()
    .sorted(Comparator.comparing(InventoryItemRequest::productId))
    .toList();
```

Items are sorted by `productId` (a UUID, which has a consistent ordering) before any `SELECT FOR UPDATE` is issued. This prevents deadlocks when two concurrent reservations share a subset of the same products but in different order.

**Important invariant to preserve:** Every future code path that touches multiple `StockItem` rows in a single transaction MUST sort by `productId` before locking. This includes any future batch-reserve, transfer, or reconciliation paths.

### 2.4 Confirm — Stock Deduction

```java
// confirm() deduction (correct)
stock.setOnHand(stock.getOnHand() - item.getQuantity());    // physical deduction
stock.setReserved(stock.getReserved() - item.getQuantity()); // reservation release
```

This is algebraically correct: available (`on_hand - reserved`) does not change during confirm—only `on_hand` and `reserved` decrease by the same amount. The DB constraint `reserved <= on_hand` continues to hold.

**One validation gap:** `confirm()` does not re-check `on_hand >= item.getQuantity()` before decrementing. This is safe only if `on_hand` cannot be reduced below `reserved` by a concurrent adjustment. The `adjustStock()` guard `reserved > updatedOnHand` prevents this in InventoryService. However, if this guard is ever bypassed (e.g., by a raw SQL operation or a future migration), the confirm path could drive `on_hand` negative. Adding an explicit guard in confirm adds a second layer of defense:

```java
if (stock.getOnHand() < item.getQuantity()) {
    throw new InsufficientStockException(
        item.getProductId(), stock.getOnHand(), item.getQuantity());
}
```

### 2.5 Cancel / Expire — releaseReserved Race

`releaseReserved()` decrements `stock.reserved` without checking the current value for non-negativity at the application layer:

```java
stock.setReserved(stock.getReserved() - item.getQuantity());
```

The DB constraint `CHECK (reserved >= 0)` protects against actual corruption. However:

1. `cancel()` reads `reservation.status`, sees `PENDING`, proceeds to `releaseReserved()`.
2. `expireReservation()` reads `reservation.status`, sees `PENDING`, proceeds to `releaseReserved()`.
3. Both can be in flight simultaneously if the expiry job fires while a cancel HTTP request is in flight.
4. The first to acquire the `StockItem` pessimistic lock will succeed; the second will also succeed (the `status` check was not under the lock), resulting in double-decrement.
5. The DB constraint catches the second decrement with a raw exception.

**The expiry path does guard on status** (`if (reservation.getStatus() != ReservationStatus.PENDING) return;` in `expireReservation(Reservation)`), but this read of `reservation.status` is not under a lock. There is a TOCTOU window.

**Fix:** Lock the `Reservation` row at the start of `releaseReserved` or at the start of `cancel`/`expireReservation`, then re-read status under the lock before proceeding.

The fix mirrors the confirm fix: acquire a `PESSIMISTIC_WRITE` lock on the reservation entity before evaluating status.

### 2.6 orderId / idempotencyKey Conflation

The `Reservation` entity has no `orderId` field. The service writes:

```java
eventPayload.put("orderId", request.idempotencyKey());
```

This assumes `idempotencyKey == orderId`, which is true only if the caller uses the order ID as the idempotency key (which checkout-orchestrator-service does today, but is an undocumented convention). If the caller ever uses a different idempotency key format (e.g., `order-{orderId}-retry-{N}`), the downstream `StockReserved` event will contain a non-UUID `orderId`, breaking consumers who parse it as a UUID.

**Fix:** Add `order_id UUID` to the `reservations` table and to `ReserveRequest`. Populate it explicitly. Use `idempotencyKey` only for deduplication.

Migration:
```sql
-- V9__add_reservation_order_id.sql
ALTER TABLE reservations ADD COLUMN order_id UUID;
CREATE INDEX idx_reservations_order_id ON reservations (order_id) WHERE order_id IS NOT NULL;
```

### 2.7 Contract Schema Gaps

The canonical JSON schemas in `contracts/src/main/resources/schemas/inventory/` have deficiencies:

**`StockConfirmed.v1.json`** — Missing `items[]` array and `storeId`. Consumers cannot know which SKUs were consumed.
```json
// Current (insufficient)
{ "reservationId": "...", "orderId": "...", "confirmedAt": "..." }
```

**`StockReleased.v1.json`** — Same problem: no `items[]`, no `storeId`, no `reason` field to distinguish CANCELLED from EXPIRED.

**`StockReserved.v1.json`** — Missing `storeId`. Consumers cannot tell which dark store's stock was reserved.

These are additive changes (adding optional fields to existing schemas) and can be made without a version bump. Add to each schema and keep backward compatibility.

Required additions:
```json
// StockReserved.v1.json — add:
"storeId": { "type": "string" },
// items[] already present ✓

// StockConfirmed.v1.json — add:
"storeId": { "type": "string" },
"items": {
  "type": "array",
  "items": { "required": ["productId","quantity"] ... }
}

// StockReleased.v1.json — add:
"storeId": { "type": "string" },
"reason": { "type": "string", "enum": ["CANCELLED","EXPIRED"] },
"items": { "type": "array", ... }
```

After schema updates, rebuild contracts: `./gradlew :contracts:build`.

---

## 3. Concurrency Control — Exact Mechanism and Gaps

### 3.1 How It Works Today

All stock mutations in both `ReservationService` and `InventoryService` use:

```java
entityManager.createQuery("SELECT s FROM StockItem s WHERE s.productId = :pid AND s.storeId = :sid",
        StockItem.class)
    .setParameter("pid", productId)
    .setParameter("sid", storeId)
    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
    .setHint("jakarta.persistence.lock.timeout", inventoryProperties.getLockTimeoutMs())  // default 2000ms
    .getSingleResult();
```

This issues `SELECT ... FOR UPDATE` on the `stock_items` row. PostgreSQL uses row-level locks, so:

- Only one transaction holds the lock on a given `(productId, storeId)` pair at a time.
- Other writers wait (up to `lock-timeout-ms`, default 2000ms) then throw `LockTimeoutException`.
- Readers with `READ_COMMITTED` see the committed value after the lock is released.

The `@Version` field on `StockItem` exists but is **not used for conflict detection**. Because all mutations go through `PESSIMISTIC_WRITE`, the `@Version` increment is a side effect only. Remove `@Version` from `StockItem` or document it explicitly as "version is tracked for audit/CDC but not used for optimistic lock guards."

### 3.2 Lock Timeout Behavior

`INVENTORY_LOCK_TIMEOUT_MS` defaults to 2000ms. Under peak load on a hot SKU (e.g., a viral product across 50 concurrent checkout attempts), threads will queue on the row lock and some will timeout after 2 seconds.

The `LockTimeoutException` is not currently mapped in `GlobalExceptionHandler`. When it fires, the caller gets an unmapped 500. It should be mapped to HTTP 503 (Service Unavailable) with a Retry-After header or HTTP 409 with error code `STOCK_LOCK_TIMEOUT` so clients can retry.

```java
// In GlobalExceptionHandler.java — add:
@ExceptionHandler(jakarta.persistence.LockTimeoutException.class)
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public ErrorResponse handleLockTimeout(jakarta.persistence.LockTimeoutException ex, HttpServletRequest req) {
    return new ErrorResponse("STOCK_LOCK_TIMEOUT",
        "Stock temporarily unavailable, please retry",
        traceIdProvider.getTraceId(), Instant.now(), List.of());
}
```

### 3.3 Sequential vs. Batch Lock Acquisition

The reserve path acquires locks in a for-loop:

```java
for (InventoryItemRequest item : sortedItems) {
    StockItem stock = lockStockItem(item.productId(), request.storeId()); // one SELECT FOR UPDATE per item
    ...
}
```

For a 10-item cart this is 10 sequential roundtrips to PostgreSQL before any stock is modified. At p99 DB latency of 5ms per roundtrip, that's 50ms in lock acquisition alone for a 10-item cart.

**Recommended improvement:** Acquire all locks in one query:

```java
// Batch SELECT FOR UPDATE
List<StockItem> stocks = entityManager.createQuery(
        "SELECT s FROM StockItem s WHERE s.storeId = :sid AND s.productId IN :pids " +
        "ORDER BY s.productId",   // consistent ordering prevents deadlock
        StockItem.class)
    .setParameter("sid", storeId)
    .setParameter("pids", productIds)
    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
    .setHint("jakarta.persistence.lock.timeout", inventoryProperties.getLockTimeoutMs())
    .getResultList();
```

The `ORDER BY s.productId` in the SQL mirrors the in-memory sort, keeping the lock order deterministic. This reduces 10 roundtrips to 1.

### 3.4 @Version Conflict with PESSIMISTIC_WRITE

`StockItem` has both `@Version` (optimistic) and every mutation uses `PESSIMISTIC_WRITE`. This creates a misleading dual-lock appearance. If `@Version` is retained, Hibernate will throw `OptimisticLockException` on stale `version` even when `PESSIMISTIC_WRITE` has already been used—but in practice, since you hold the row lock the version will always be current by the time you commit, making `@Version` a no-op. The annotation is safe but misleading. **Decision required:** either remove `@Version` and rely solely on pessimistic locking (simpler, consistent with current behavior), or switch to optimistic locking with `@Version` and retry loops for lower-contention paths.

**Recommendation:** Remove `@Version` from `StockItem`. Document that the service uses pessimistic locking exclusively for stock mutations to ensure atomic availability check-and-reserve in a single transaction boundary.

---

## 4. Dark-Store Truth — The storeId Gap

### 4.1 The Problem

`StockItem` stores `store_id VARCHAR(50)`:

```sql
-- V1__create_stock_items.sql
store_id VARCHAR(50) NOT NULL,
CONSTRAINT uq_stock_product_store UNIQUE (product_id, store_id),
```

Warehouse-service stores the authoritative store list with `id UUID`. There is **no referential link** between the two services' store identifiers. The inventory-service will accept any arbitrary string as `storeId`—including:

- Typos: `"store-01 "` (trailing space) — creates a second inventory partition
- Inactive stores: `store_id` of a store with `status = INACTIVE` in warehouse-service
- Deleted stores: a store that has been hard-deleted from warehouse-service
- Non-existent stores: a fabricated ID from a malicious or misconfigured caller

This means a reservation can be successfully created against a dark store that is currently in `MAINTENANCE` or `TEMPORARILY_CLOSED` status, and the customer can complete checkout for an order that the store cannot fulfill.

### 4.2 The Coupling Challenge

Direct synchronous calls from inventory-service to warehouse-service on every reservation would introduce tight coupling and a cascading failure risk: if warehouse-service is down, no reservations can be made. This is the wrong tradeoff for a checkout-critical path.

The correct pattern is **eventual consistency with local state**:

1. **inventory-service maintains a local store registry** — a simple `dark_stores` table with `(store_id, status, last_sync_at)`.
2. **warehouse-service publishes `StoreStatusChanged` events** to Kafka via outbox.
3. **inventory-service consumes `StoreStatusChanged`** and updates its local store status.
4. **During reservation**, inventory-service checks local store status before locking stock.

This keeps the reservation path fully local (no synchronous cross-service call) while ensuring store status is eventually accurate.

### 4.3 Implementation Plan

**Step 1: Add local store registry table** (new Flyway migration):

```sql
-- V9__create_dark_stores.sql (or V10 if order_id migration runs first)
CREATE TABLE dark_stores (
    store_id    VARCHAR(50)  PRIMARY KEY,
    status      VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    synced_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seed with all known store IDs from existing stock_items
INSERT INTO dark_stores (store_id, status)
SELECT DISTINCT store_id, 'ACTIVE' FROM stock_items
ON CONFLICT DO NOTHING;
```

**Step 2: Add store status check to `ReservationService.reserve()`**:

```java
// Before locking stock items:
darkStoreRepository.findById(request.storeId())
    .filter(ds -> ds.getStatus().equals("ACTIVE"))
    .orElseThrow(() -> new StoreUnavailableException(request.storeId()));
```

**Step 3: Add Kafka consumer for `StoreStatusChanged`** events:

```java
@KafkaListener(topics = "${kafka.topics.warehouse-events:warehouse.events}")
public void handleStoreStatusChanged(ConsumerRecord<String, String> record) {
    StoreStatusChangedPayload payload = objectMapper.readValue(record.value(), ...);
    darkStoreRepository.findById(payload.getStoreId()).ifPresent(ds -> {
        ds.setStatus(payload.getNewStatus());
        ds.setSyncedAt(Instant.now());
        darkStoreRepository.save(ds);
    });
}
```

**Step 4: Handle rollout carefully** — see [Section 8](#8-rollout-of-safer-reservation-behavior).

### 4.4 Immediate Operational Guard (Pre-Kafka consumer)

Before the Kafka consumer is in place, add a minimal allow-list guard using a config property:

```yaml
# application.yml
inventory:
  store-validation:
    mode: ${INVENTORY_STORE_VALIDATION_MODE:NONE}  # NONE | ALLOWLIST | DARK_STORE_TABLE
    allowed-store-ids: ${INVENTORY_ALLOWED_STORE_IDS:}  # comma-separated
```

In `ALLOWLIST` mode, `ReservationService` checks the configured list. In `DARK_STORE_TABLE` mode, it queries the local table. The default `NONE` preserves existing behavior during rollout.

---

## 5. Warehouse-to-Inventory Mapping

### 5.1 Identifier Spaces

| Service | Store identifier | Type | Authority |
|---------|-----------------|------|-----------|
| warehouse-service | `stores.id` | UUID | Authoritative registry |
| inventory-service | `stock_items.store_id` | VARCHAR(50) | Dependent |
| checkout-orchestrator | `storeId` in ReserveRequest | String | Caller-provided |

The checkout-orchestrator must know the store ID to include in `ReserveRequest`. It derives this from the warehouse-service (via `GET /stores/nearest` or `/stores/by-pincode`), which returns UUID-format store IDs. The orchestrator passes this UUID string to inventory-service. Because UUIDs fit within VARCHAR(50), the current system works by convention—but nothing enforces this.

### 5.2 Zone-to-Store Lookup

The warehouse-service's `ZoneService.mapPincodeToStoreIds()` is the mechanism for resolving a customer's delivery pincode to one or more serving dark stores. The result is a list of store UUIDs. The checkout flow should:

1. Call `GET /stores/by-pincode?pincode={pincode}` → list of store UUIDs
2. For each candidate store, call `GET /stores/{id}/can-accept` → boolean
3. For each candidate store, call `POST /inventory/check` with the storeId → availability
4. Select the best available store (nearest + has stock + has capacity)

**Current gap:** There is no unified "which store serves this cart" API. The checkout-orchestrator must orchestrate across both services. This is fragile and duplicates routing logic at the orchestration level.

**Target state:** A `StoreSelector` capability (could live in checkout-orchestrator-service as a Temporal activity or in warehouse-service as a new endpoint) that takes `{pincode, cartItems[]}` and returns the optimal `storeId`. This encapsulates the zone lookup + capacity check + availability check in one place.

### 5.3 Capacity Integration

`warehouse-service` tracks `store_capacity` (orders/hour). `inventory-service` tracks stock quantities. Neither informs the other. The checkout path currently:

1. Checks inventory via `POST /inventory/check` (stock availability)
2. Does NOT check `GET /stores/{id}/can-accept` (order capacity)

At peak, a dark store can run out of picker capacity before running out of stock. Reserving stock for an order the store cannot physically fulfill is wasteful and creates cancellations. The checkout Temporal workflow should add a warehouse capacity check as a step before or alongside the inventory reserve step.

### 5.4 Operating Hours Integration

`warehouse-service` has `isStoreOpen()` logic with timezone-aware day/time checks. This is not consumed by inventory-service or checkout-orchestrator. A reservation can be created against a closed store. The checkout-orchestrator workflow should add an `isStoreOpen` check before inventory reservation.

**Note on overnight hours bug:** `StoreService.isWithinOperatingHours()` has partial overnight handling (checks previous day's hours), but the main check `!currentTime.isBefore(opensAt) && !currentTime.isAfter(closesAt)` will always return false for a store whose `closes_at < opens_at`. This is a warehouse-service bug that must be fixed alongside the checkout integration.

---

## 6. Stock Freshness and Drift

### 6.1 Stock Accuracy Model

The current stock model is `{on_hand, reserved}` with `available = on_hand - reserved`. `on_hand` is updated only by:

- `adjustStock()` / `adjustStockBatch()` — admin-driven
- `confirm()` — decrements on_hand by confirmed quantity

There is no mechanism to detect or correct stock drift from:

- **Theft / shrinkage:** Products disappear from shelf; on_hand remains high.
- **Damage:** Products become unsellable; on_hand remains high.
- **Receiving discrepancies:** GRN quantity differs from purchase order; on_hand may be over/under.
- **Expiry:** Perishables expire; on_hand remains high until manual write-off.
- **Transfer errors:** Stock moved without system entry.

Over time, `on_hand` will diverge from physical reality. This leads to:

- **Phantom availability:** checkout succeeds; picker cannot find the item; order cancelled at fulfillment.
- **Stockout masking:** physical stock exists but on_hand is zero due to incorrect write-off.

### 6.2 Required: Stock Adjustment Reason Taxonomy

The current `reason` field in `stock_adjustment_log` is a free-text VARCHAR(100). This makes reporting impossible. Define a canonical enum and enforce it:

```sql
-- V10__add_adjustment_reason_enum.sql
CREATE TYPE stock_adjustment_reason AS ENUM (
    'RECEIVING',         -- goods received from supplier/hub
    'CYCLE_COUNT',       -- physical count correction
    'DAMAGE_WRITEOFF',   -- damaged goods removal
    'EXPIRY_WRITEOFF',   -- expired goods removal
    'RETURN_TO_STOCK',   -- customer return re-stocked
    'TRANSFER_IN',       -- inter-store transfer received
    'TRANSFER_OUT',      -- inter-store transfer dispatched
    'ADMIN_CORRECTION'   -- manual override by ops
);

ALTER TABLE stock_adjustment_log
    ALTER COLUMN reason TYPE stock_adjustment_reason USING reason::stock_adjustment_reason;
```

> **Rolling this out without breaking the existing `VARCHAR(100)`:** Deploy the enum migration, then update `StockAdjustRequest.reason` to validate against the enum values at the application layer before the migration runs in production. Use a feature flag to gate the strict validation.

### 6.3 Required: Reconciliation Flow

Add a stock reconciliation endpoint that allows dark store managers to submit physical counts and generate a variance report:

**New endpoint:** `POST /inventory/reconcile` (ADMIN role)

```json
// Request
{
  "storeId": "store-uuid",
  "referenceId": "cycle-count-2026-03-07",
  "counts": [
    { "productId": "uuid-1", "physicalCount": 42 },
    { "productId": "uuid-2", "physicalCount": 8 }
  ]
}

// Response
{
  "referenceId": "cycle-count-2026-03-07",
  "variances": [
    { "productId": "uuid-1", "systemOnHand": 50, "physicalCount": 42, "delta": -8, "reason": "SHRINKAGE" },
    { "productId": "uuid-2", "systemOnHand": 8, "physicalCount": 8, "delta": 0 }
  ],
  "pendingApproval": true
}
```

The reconciliation creates pending variance records that require a second approver to confirm before `adjustStock` mutations are applied. This prevents a single malicious or mistaken operator from silently corrupting stock.

Flyway migration for reconciliation:
```sql
-- V11__create_reconciliation.sql
CREATE TABLE stock_reconciliations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id       VARCHAR(50) NOT NULL,
    reference_id   VARCHAR(255) NOT NULL,
    submitted_by   UUID NOT NULL,
    approved_by    UUID,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at    TIMESTAMPTZ
);

CREATE TABLE stock_reconciliation_items (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_id   UUID NOT NULL REFERENCES stock_reconciliations(id) ON DELETE CASCADE,
    product_id          UUID NOT NULL,
    system_on_hand      INT NOT NULL,
    physical_count      INT NOT NULL,
    delta               INT GENERATED ALWAYS AS (physical_count - system_on_hand) STORED,
    CONSTRAINT chk_physical_count_non_negative CHECK (physical_count >= 0)
);
```

### 6.4 Per-SKU Low-Stock Threshold

The global `INVENTORY_LOW_STOCK_THRESHOLD=10` is a blunt instrument. Add a per-SKU threshold:

```sql
-- V12__add_per_sku_thresholds.sql
ALTER TABLE stock_items
    ADD COLUMN low_stock_threshold INT,
    ADD COLUMN reorder_point INT;

-- NULL means "use global threshold"
```

In `checkLowStock()`:
```java
int threshold = stock.getLowStockThreshold() != null
    ? stock.getLowStockThreshold()
    : inventoryProperties.getLowStockThreshold();
```

### 6.5 Missing: OutOfStock Event

When `available` (= `on_hand - reserved`) reaches zero, no event is published. Add to `checkLowStock()`:

```java
if (available == 0) {
    Map<String, Object> oos = new LinkedHashMap<>();
    oos.put("productId", stock.getProductId().toString());
    oos.put("storeId", storeId);
    oos.put("detectedAt", Instant.now().toString());
    outboxService.publish("StockItem", stock.getProductId().toString(), "OutOfStock", oos);
}
```

Add `OutOfStock.v1.json` to `contracts/src/main/resources/schemas/inventory/`.

The catalog-service and search-service should consume `OutOfStock` events to hide the product from customer-facing APIs in near-real-time. This closes the loop between physical stock depletion and customer-visible availability.

---

## 7. Operational Overrides

### 7.1 Admin Stock Adjustments

Two endpoints exist: `POST /inventory/adjust` (single SKU) and `POST /inventory/adjust-batch` (multi-SKU). Both require `ADMIN` role. The current `actor_id` extraction from JWT works for adjust-batch via `resolveActorId()` but the single-item path also calls it—verify both reach the same code path (they do: both call the private `resolveActorId()` helper).

**Concern: No rate limit on adjust endpoints.** A compromised `ADMIN` JWT could issue thousands of negative deltas, draining stock across stores. Add rate limiting:

```java
// In StockController.adjust() and adjustBatch():
if (!rateLimitService.tryAcquire("admin-adjust-" + extractActorId())) {
    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Adjustment rate limit exceeded");
}
```

**Concern: No approval workflow for large deltas.** A delta of `-10000` on a high-value product should require a second approver. Add a configurable threshold:

```yaml
inventory:
  adjustment:
    large-delta-threshold: ${INVENTORY_LARGE_DELTA_THRESHOLD:500}
    require-approval-above-threshold: ${INVENTORY_REQUIRE_APPROVAL:false}
```

### 7.2 Emergency Stock Override

For scenarios where a dark store is flooding with bad reservations (e.g., a pricing glitch causes mass checkout), add an emergency zero-out capability:

```
POST /admin/inventory/stores/{storeId}/override
{
  "action": "PAUSE_RESERVATIONS",  // | RESUME_RESERVATIONS | DRAIN_PENDING
  "reason": "pricing-incident-2026-03-07",
  "actorId": "uuid"
}
```

`PAUSE_RESERVATIONS`: Sets a store-level flag in `dark_stores.status = 'PAUSED'`. `reserve()` checks this flag and returns HTTP 409 with `STORE_TEMPORARILY_PAUSED`.

`DRAIN_PENDING`: Triggers immediate expiry of all PENDING reservations for this store (calls `expireReservation` for each). Use with caution—this releases all held stock.

### 7.3 Dark Store Status Propagation

When warehouse-service operator changes store status to `MAINTENANCE` or `TEMPORARILY_CLOSED`:
1. `StoreStatusChanged` event is published to `warehouse.events` Kafka topic.
2. inventory-service Kafka consumer updates `dark_stores.status`.
3. New reservation attempts against that store fail with `StoreUnavailableException` (HTTP 409).
4. **Existing PENDING reservations are NOT automatically expired** — this must be a separate operational decision. Add an endpoint or ShedLock job that expires pending reservations for non-ACTIVE stores.

### 7.4 Bulk Receiving Workflow

Dark stores receive deliveries that may update dozens or hundreds of SKUs simultaneously. The current `adjust-batch` endpoint handles the mechanics but requires an `ADMIN` JWT, reason, and reference ID. Define a structured receiving workflow:

1. WMS (or mobile receiving app) creates a receiving note with expected quantities.
2. On confirm, calls `POST /inventory/adjust-batch` with `reason: RECEIVING` and `referenceId: {receiving_note_id}`.
3. The `stock_adjustment_log` entries are linked to the receiving note via `reference_id` for full traceability.
4. A `StockAdjusted` event is published per SKU (current behavior); add an aggregate `ReceivingCompleted` event with the full receiving note summary.

---

## 8. Rollout of Safer Reservation Behavior

This section provides a wave-by-wave rollout plan with validation gates and rollback steps for each change. Changes are ordered from lowest to highest blast radius.

### Wave 0 — Pre-conditions (must be done before anything else)

**Pre-condition checklist:**
- [ ] All tests passing: `./gradlew :services:inventory-service:test :services:warehouse-service:test`
- [ ] Baseline metrics captured: p50/p99 of `/inventory/reserve`, `/inventory/confirm`, `/inventory/cancel`
- [ ] PostgreSQL slow query log enabled for `inventory` and `warehouse` databases
- [ ] ShedLock confirmed working: verify only one instance runs `reservationExpiry` under multi-pod deploy

### Wave 1 — Error handling hardening (zero behavior change, pure defense)

**Changes:**
1. Map `LockTimeoutException` → HTTP 503 in `GlobalExceptionHandler`
2. Map `DataIntegrityViolationException` on idempotency key → idempotent re-fetch in `reserve()`
3. Add explicit guard in `confirm()`: `stock.getOnHand() >= item.getQuantity()`

**Validation:**
- Unit test: verify `LockTimeoutException` returns 503
- Integration test: concurrent identical reserve calls return same response, not 500
- `./gradlew :services:inventory-service:test`

**Rollback:** These are purely additive code changes. Rollback = revert the PR.

### Wave 2 — Lock the Reservation row during confirm and cancel

**Change:** Add `PESSIMISTIC_WRITE` lock on `Reservation` row at start of `confirm()`, `cancel()`, and `expireReservation()`.

**Why this matters:** Closes the TOCTOU window between concurrent confirm/cancel/expire on the same reservation.

**Implementation:**
```java
// In ReservationService — new helper:
private Reservation lockReservation(UUID reservationId) {
    try {
        return entityManager.createQuery(
                "SELECT r FROM Reservation r WHERE r.id = :id", Reservation.class)
            .setParameter("id", reservationId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setHint(LOCK_TIMEOUT_HINT, inventoryProperties.getLockTimeoutMs())
            .getSingleResult();
    } catch (NoResultException e) {
        throw new ReservationNotFoundException(reservationId);
    }
}
```

Replace `reservationRepository.findById(reservationId).orElseThrow(...)` with `lockReservation(reservationId)` in `confirm()`, `cancel()`, and `expireReservation(UUID)`.

**Validation:**
- Load test: 50 concurrent confirm calls for the same reservationId — only one should succeed, others return 409
- Verify `PESSIMISTIC_WRITE` lock on reservation table visible in EXPLAIN ANALYZE output
- Run: `./gradlew :services:inventory-service:test`

**Rollback:** Revert to `findById()`. Low risk: the race is rare in production (requires two simultaneous confirm calls for the same reservation).

**Performance impact:** Each confirm/cancel now acquires two locks (reservation row + stock rows). Deadlock risk is low because lock ordering is: reservation first, then stock rows sorted by productId. This is consistent across all callers.

### Wave 3 — orderId field addition

**Change:** Add `order_id UUID` column to `reservations` table.

**Migration:**
```sql
-- V9__add_reservation_order_id.sql
ALTER TABLE reservations ADD COLUMN order_id UUID;
CREATE INDEX idx_reservations_order_id ON reservations (order_id)
    WHERE order_id IS NOT NULL;
```

**Application change:** Update `ReserveRequest` to accept an explicit `orderId` field (optional, nullable for backward compatibility). If provided, store it on the entity; if absent, derive it from `idempotencyKey` for backward compatibility.

**Contract change:** Update `StockReserved.v1.json` to include `storeId`. This is additive — no version bump needed.

**Validation:**
- Flyway migration runs cleanly on dev DB
- Existing reservations with NULL `order_id` continue to function
- New reservations with explicit `orderId` populate the column
- `./gradlew :contracts:build` to validate schema files

**Rollback:** Column is nullable; removing it requires another migration but no data loss. If rollback needed, add `V9__rollback_order_id.sql` dropping the column and index.

### Wave 4 — Dark-store local registry (store validation)

This is the highest-blast-radius change and must be deployed with a feature flag.

**Step 4a — Deploy migration and Kafka consumer, feature flag OFF:**

```sql
-- V10__create_dark_stores.sql
CREATE TABLE dark_stores (
    store_id   VARCHAR(50) PRIMARY KEY,
    status     VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    synced_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO dark_stores (store_id)
SELECT DISTINCT store_id FROM stock_items
ON CONFLICT DO NOTHING;
```

Deploy Kafka consumer that updates `dark_stores.status` from `StoreStatusChanged` events.

**Step 4b — Validate in dev/staging:** Confirm that status updates from warehouse-service are flowing into inventory-service's `dark_stores` table within the Kafka consumer lag SLA.

**Step 4c — Enable validation in shadow mode (LOG, not BLOCK):**

```yaml
inventory:
  store-validation:
    mode: LOG  # logs mismatches but does not block reservations
```

Monitor logs for reservations against stores not present in `dark_stores` or with non-ACTIVE status. If mismatches are found, investigate and reconcile before moving to BLOCK mode.

**Step 4d — Enable BLOCK mode (with gradual rollout by store ID prefix or traffic percentage via feature flag service):**

```yaml
inventory:
  store-validation:
    mode: DARK_STORE_TABLE  # blocks reservations for inactive/unknown stores
```

**Validation:**
- Simulate `StoreStatusChanged(MAINTENANCE)` event → verify subsequent reserve returns 409
- Simulate `StoreStatusChanged(ACTIVE)` → verify reserve succeeds again
- Verify existing PENDING reservations created before a status change are not automatically cancelled (they are not—only new reserves are blocked)

**Rollback:** Set feature flag back to `NONE` via config map or secret update. No DB change needed. The `dark_stores` table can remain populated; it's only consulted when mode != NONE.

### Wave 5 — Batch lock acquisition

**Change:** Replace the per-item `lockStockItem()` loop with a single batch `SELECT FOR UPDATE`.

This is a performance optimization, not a correctness fix. Deploy after Wave 1-4 have been stable for at least one release cycle.

**Validation:**
- Benchmark before/after: measure p99 of `/inventory/reserve` for 5-item and 10-item carts under 50 concurrent requests
- Verify deadlock rate does not increase (should stay zero; `ORDER BY product_id` maintains ordering)
- Load test: `k6 run --vus 100 --duration 60s reserve-load-test.js`

**Rollback:** Revert to per-item loop. No schema change.

---

## 9. Validation Checklist

Use this checklist before declaring each wave production-ready.

### Functional correctness

- [ ] `reserve()` returns identical response for duplicate idempotency key (idempotency test)
- [ ] `reserve()` returns `INSUFFICIENT_STOCK` when `available < requested`
- [ ] `reserve()` increments `stock_items.reserved` by exactly the requested quantity
- [ ] `confirm()` decrements both `on_hand` and `reserved` by reservation quantities
- [ ] `confirm()` is idempotent: calling twice returns 204 both times with no double-decrement
- [ ] `cancel()` decrements only `reserved`; `on_hand` unchanged
- [ ] `cancel()` on CONFIRMED reservation returns `RESERVATION_NOT_PENDING` (409)
- [ ] `expireReservation()` behaves identically to cancel for stock accounting
- [ ] Concurrent confirm + cancel on same reservation: one succeeds, other gets clean 409
- [ ] Concurrent reserve + expiry on same pending reservation: stock is released exactly once
- [ ] `adjustStock()` with delta causing `on_hand < reserved` is rejected with `INVALID_STOCK_ADJUSTMENT`
- [ ] `adjustStock()` with delta causing `on_hand < 0` is rejected

### Schema constraints (verify via SQL after each test)

```sql
-- These should always hold:
SELECT * FROM stock_items WHERE on_hand < 0;         -- expect 0 rows
SELECT * FROM stock_items WHERE reserved < 0;        -- expect 0 rows
SELECT * FROM stock_items WHERE reserved > on_hand;  -- expect 0 rows

-- Pending reservations that have expired and not been cleaned up:
SELECT COUNT(*) FROM reservations
WHERE status = 'PENDING' AND expires_at < now() - INTERVAL '1 minute';
-- expect small number (expiry job runs every 30s)
```

### Event contract validation

- [ ] `StockReserved` event contains `reservationId`, `orderId`/`idempotencyKey`, `items[]`, `storeId`, `reservedAt`, `expiresAt`
- [ ] `StockConfirmed` event contains `reservationId`, `items[]`, `storeId`, `confirmedAt`
- [ ] `StockReleased` event contains `reservationId`, `items[]`, `storeId`, `releasedAt`, `reason` (CANCELLED or EXPIRED)
- [ ] `LowStockAlert` fires when `available <= threshold` after any stock-reducing operation
- [ ] No duplicate `LowStockAlert` events for the same SKU in the same minute (deduplication to implement)

### Performance gates

- [ ] `/inventory/reserve` p99 < 150ms at 50 concurrent checkouts for 5-item carts
- [ ] `/inventory/check` p99 < 50ms
- [ ] `ReservationExpiryJob` clears 10,000 expired reservations in < 5 minutes
- [ ] HikariCP pool (`maximum-pool-size: 60`) — verify pool exhaustion alert is configured
- [ ] Zero `LockTimeoutException`s under normal load; alert if count > 0

### Operational readiness

- [ ] `ReservationExpiryJob` ShedLock confirmed active on all replicas
- [ ] Dark-store consumer lag alert: alert if `StoreStatusChanged` consumer is > 60s behind
- [ ] Stock adjustment audit log has `actor_id` populated for all ADMIN-role operations
- [ ] `OutboxCleanupJob` confirmed running daily; `outbox_events` table size monitored

---

## 10. Rollback Guidance

### General rollback principle

All changes in this guide use:
1. **Additive migrations** (no column drops, no renames) so that old code can run against new schema.
2. **Feature flags** (`inventory.store-validation.mode`) to enable/disable new behavior without redeploy.
3. **No breaking event contract changes** (additive fields only).

This means rollback is always: redeploy previous container image. Schema changes are backward-compatible and do not need reverting unless the migration itself is problematic.

### Specific rollback scenarios

| Scenario | Rollback action | Risk |
|----------|-----------------|------|
| Wave 2 (reservation row locking) causes deadlocks | Revert to `findById()` | LOW — deadlock pattern (reserve → reservation lock → stock lock) does not match typical access patterns |
| Wave 3 (orderId migration) causes startup failure | Run `V9_rollback__remove_order_id.sql`: `ALTER TABLE reservations DROP COLUMN order_id;` | LOW — nullable column, no data dependency |
| Wave 4 (store validation) blocks legitimate orders | Set `inventory.store-validation.mode=NONE` via ConfigMap hot-reload | LOW — no schema revert needed |
| Wave 5 (batch lock) causes unexpected behavior | Revert batch query to per-item loop | LOW — no schema change |
| Kafka consumer for StoreStatusChanged has bug | Disable consumer via `spring.kafka.listener.auto-startup=false`; resume with fix | MEDIUM — store status in `dark_stores` may be stale; operate in NONE or LOG mode |

### Data repair after a failed rollout

If a wave deploys and causes double-releases or other stock corruption before rollback:

```sql
-- Identify stock_items where reserved > on_hand (constraint violation indicator)
SELECT si.*, r.id as reservation_id, r.status
FROM stock_items si
LEFT JOIN reservations r ON r.store_id = si.store_id AND r.status = 'PENDING'
LEFT JOIN reservation_line_items rli ON rli.reservation_id = r.id
    AND rli.product_id = si.product_id
WHERE si.reserved > si.on_hand;

-- Recompute reserved from active PENDING reservations (reconciliation query):
UPDATE stock_items si
SET reserved = (
    SELECT COALESCE(SUM(rli.quantity), 0)
    FROM reservation_line_items rli
    JOIN reservations r ON r.id = rli.reservation_id
    WHERE r.status = 'PENDING'
      AND r.store_id = si.store_id
      AND rli.product_id = si.product_id
      AND r.expires_at > now()
)
WHERE si.store_id = :target_store_id;
-- Run during off-peak; acquire advisory lock first if possible
```

This recomputes `reserved` from first principles (sum of PENDING reservation quantities) for a specific store. Run with caution; validate output before committing.

---

## 11. Observability and Alerting Requirements

### 11.1 Critical Alerts (page on-call immediately)

| Alert | Condition | Cause |
|-------|-----------|-------|
| `inventory_reserved_gt_on_hand` | Any `stock_items` row where `reserved > on_hand` | DB constraint violation bypassed or concurrency bug |
| `inventory_on_hand_negative` | Any `stock_items` row where `on_hand < 0` | Constraint bypass |
| `inventory_lock_timeout_rate` | `LockTimeoutException` count > 10/min | Lock contention spike; possible deadlock or slow query |
| `reservation_expiry_job_not_running` | No ShedLock heartbeat for > 90s | Expiry job failed; PENDING reservations not being cleaned up |
| `outbox_backlog_high` | `COUNT(*) WHERE sent = false AND created_at < now() - 2m` > 1000 | CDC/outbox relay broken; events not flowing to Kafka |

### 11.2 Warning Alerts (investigate within 30 minutes)

| Alert | Condition | Cause |
|-------|-----------|-------|
| `reserve_p99_high` | p99 > 300ms for `/inventory/reserve` | DB contention, pool exhaustion, or slow lock acquisition |
| `pending_expired_reservations` | > 500 PENDING reservations older than TTL + 5min | Expiry job backlog |
| `dark_store_consumer_lag` | Kafka consumer group lag > 60s | StoreStatusChanged not flowing; store status stale |
| `low_stock_alert_rate_high` | LowStockAlert events > 100/min for same SKU | Threshold too low or no deduplication |

### 11.3 Metrics to Instrument

Add the following Micrometer metrics in `ReservationService` and `InventoryService`:

```java
// Reservation outcomes
registry.counter("inventory.reservation.reserve", "outcome", "success").increment();
registry.counter("inventory.reservation.reserve", "outcome", "insufficient_stock").increment();
registry.counter("inventory.reservation.reserve", "outcome", "idempotent_replay").increment();

// State transitions
registry.counter("inventory.reservation.state_transition",
    "from", "PENDING", "to", "CONFIRMED").increment();

// Lock contention
registry.counter("inventory.stock_lock.timeout", "store_id", storeId).increment();
registry.timer("inventory.stock_lock.wait_time").record(lockAcquisitionDuration);

// Stock health gauges (register as scheduled metrics)
registry.gauge("inventory.stock_items.reserved_gt_on_hand_count", ...,
    () -> stockItemRepository.countViolatingReservedConstraint());
```

### 11.4 Structured Log Fields

Ensure all log events in `ReservationService` include:

```json
{
  "traceId": "...",
  "reservationId": "...",
  "storeId": "...",
  "idempotencyKey": "...",
  "operation": "reserve|confirm|cancel|expire",
  "outcome": "success|insufficient_stock|expired|idempotent",
  "itemCount": 3,
  "durationMs": 47
}
```

---

## 12. Prioritized Finding Register

| ID | Title | Severity | Wave | Affected | Effort |
|----|-------|----------|------|----------|--------|
| C4-F1 | Reservation row not locked during confirm/cancel/expire (TOCTOU race) | HIGH | 2 | inventory-service | 1 day |
| C4-F2 | Concurrent duplicate reserve returns HTTP 500 on idempotency constraint violation | HIGH | 1 | inventory-service | 0.5 day |
| C4-F3 | `storeId` in inventory is unvalidated; reservations against inactive/missing stores succeed | HIGH | 4 | inventory-service, warehouse-service | 3 days |
| C4-F4 | `StockConfirmed` and `StockReleased` schemas omit `items[]` and `storeId` | HIGH | 3 | contracts/, all consumers | 0.5 day |
| C4-F5 | No `OutOfStock` event when available stock reaches zero | HIGH | 3 | inventory-service, contracts/ | 1 day |
| C4-F6 | `orderId` conflated with `idempotencyKey`; no stable order identity on Reservation | MEDIUM | 3 | inventory-service, contracts/ | 1 day |
| C4-F7 | `LockTimeoutException` not mapped; returns HTTP 500 | MEDIUM | 1 | inventory-service | 0.5 day |
| C4-F8 | Stock model lacks `damaged`, `safety_stock` tiers for Q-commerce accuracy | MEDIUM | Post-Wave 5 | inventory-service | 3 days |
| C4-F9 | No stock reconciliation endpoint or cycle-count workflow | MEDIUM | Post-Wave 4 | inventory-service | 3 days |
| C4-F10 | Expiry job processes reservations one-by-one; no batch lock optimization | MEDIUM | 5 | inventory-service | 1 day |
| C4-F11 | Per-item lock acquisition loop; 10 sequential SELECT FOR UPDATE per 10-item cart | MEDIUM | 5 | inventory-service | 1 day |
| C4-F12 | Global low-stock threshold; no per-SKU configuration | MEDIUM | Post-Wave 4 | inventory-service | 1 day |
| C4-F13 | No `OutOfStock` consumption in catalog/search to hide products from browse | MEDIUM | Post-Wave 5 | catalog-service, search-service | 2 days |
| C4-F14 | `@Version` on `StockItem` is misleading alongside `PESSIMISTIC_WRITE` | LOW | 1 | inventory-service | 0.5 day |
| C4-F15 | Warehouse `isStoreOpen()` overnight hours bug | HIGH | 4 (warehouse) | warehouse-service | 0.5 day |
| C4-F16 | Nearest-store query is a full table scan; no bounding-box pre-filter | HIGH | 4 (warehouse) | warehouse-service | 1 day |
| C4-F17 | Capacity increment TOCTOU race (UPSERT not used) | HIGH | 4 (warehouse) | warehouse-service | 0.5 day |
| C4-F18 | Zone `delivery_radius_km` is stored but never used in routing logic | LOW | Post-Wave 5 | warehouse-service | 1 day |
| C4-F19 | No adjustment rate limiting on ADMIN stock adjust endpoints | MEDIUM | 1 | inventory-service | 0.5 day |
| C4-F20 | Checkout does not check store open status or capacity before reserving inventory | HIGH | 4 (orchestrator) | checkout-orchestrator-service | 2 days |

---

*This guide covers `services/inventory-service` and `services/warehouse-service`. For the orchestration layer that calls into this cluster, see `docs/reviews/checkout-orchestrator-review.md` and the C2 cluster guide. For downstream event consumers, see `docs/reviews/contracts-events-review.md` and the C8 cluster guide.*
