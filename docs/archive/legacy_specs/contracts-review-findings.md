# Contracts, Events & Protobuf — Exhaustive Review Findings

**Date**: 2025  
**Scope**: `contracts/`, all service outbox publishers, all Kafka consumers  
**Reference Doc**: `docs/09-contracts-events-protobuf.md`

---

## Summary

| Severity | Count |
|----------|-------|
| CRITICAL | 5     |
| HIGH     | 9     |
| MEDIUM   | 8     |
| LOW      | 5     |

---

## 1. Schema Compliance — Outbox Payloads vs JSON Schemas

### CRITICAL-01: `OrderPlaced` payload field naming mismatch — `totalAmount`/`unitPrice` vs `totalCents`/`unitPriceCents`

**Doc schema** (`docs/09-contracts-events-protobuf.md` lines 139–171) defines fields as `totalAmount` (number), `unitPrice` (number), `shippingAddress` (required).  
**Actual JSON schema** (`contracts/.../OrderPlaced.v1.json` lines 5–6) defines `totalCents` (integer), `unitPriceCents` (integer), `lineTotalCents` (integer). No `shippingAddress`.  
**Actual publisher** (`services/order-service/.../OrderService.java` lines 181–205) emits `totalCents`, `subtotalCents`, `discountCents`, `unitPriceCents`, `lineTotalCents`.

**Impact**: The doc is **out of sync** with both the schema file and the publisher. Any consumer built from the doc will deserialize with null/zero values. The actual JSON schema file and the publisher **do match each other** — the doc is stale.

**Files**:
- `docs/09-contracts-events-protobuf.md` lines 134–171 — stale schema
- `contracts/src/main/resources/schemas/orders/OrderPlaced.v1.json` — correct
- `services/order-service/.../OrderService.java` lines 181–205 — correct

---

### CRITICAL-02: `OrderPlaced` publisher emits `storeId`, `paymentId`, `status` — only `storeId`/`paymentId` are optional in schema, `status` is not in schema at all

**Publisher** (`OrderService.java` line 186): `payload.put("status", order.getStatus().name())`  
**Schema** (`OrderPlaced.v1.json`): No `status` property defined.

The `additionalProperties` constraint is not set to `false`, so this won't cause validation failure, but `status` is an **undocumented field** leaking internal state.

**Severity**: HIGH  
**Files**: `OrderService.java:186`, `OrderPlaced.v1.json`

---

### CRITICAL-03: `PaymentCaptured` schema vs publisher — field name `amount` vs `amountCents`, missing `pspReference`

**Schema** (`PaymentCaptured.v1.json`): requires `amountCents` (integer), no `pspReference`.  
**Doc schema** (`09-contracts-events-protobuf.md` lines 197–207): requires `amount` (number) + `pspReference` + `capturedAt`.  
**Publisher** (`PaymentService.java` lines 118–122): emits `amountCents` (integer), no `pspReference`, no `capturedAt`.

| Field | Doc | JSON Schema | Publisher |
|-------|-----|-------------|-----------|
| `amount` | ✅ required | ❌ absent | ❌ absent |
| `amountCents` | ❌ absent | ✅ required | ✅ present |
| `pspReference` | ✅ required | ❌ absent | ❌ absent |
| `capturedAt` | ✅ required | ❌ absent | ❌ absent |

**Impact**: Doc is dangerously stale. Any new consumer team following the doc will expect `amount`, `pspReference`, `capturedAt` — none of which exist.

**Files**:
- `docs/09-contracts-events-protobuf.md` lines 197–207
- `contracts/.../payments/PaymentCaptured.v1.json`
- `services/payment-service/.../PaymentService.java` lines 118–122

---

### CRITICAL-04: `PaymentAuthorized` schema vs publisher — missing `authorizedAt` timestamp

**Schema** (`PaymentAuthorized.v1.json`): requires `paymentId`, `orderId`, `amountCents`, `currency`.  
**Publisher** (`PaymentService.java` lines 83–87): emits exactly those 4 fields.  
**Issue**: No timestamp field (`authorizedAt`). This is the **only payment event** without a timestamp, violating the platform convention that every event has a temporal field. Consumers needing "when was this authorized?" must fall back to Kafka record timestamp.

**Severity**: HIGH  
**Files**: `PaymentAuthorized.v1.json`, `PaymentService.java:83–87`

---

### CRITICAL-05: `PaymentVoided` — event published with NO corresponding JSON schema

**Publisher** (`PaymentService.java` lines 152–155):
```java
outboxService.publish("Payment", saved.getId().toString(), "PaymentVoided",
    Map.of("orderId", saved.getOrderId(), "paymentId", saved.getId(),
           "amount", saved.getAmountCents()));
```

**Issues**:
1. No `PaymentVoided.v1.json` exists in `contracts/src/main/resources/schemas/payments/`.
2. Uses field name `amount` (not `amountCents`) — inconsistent with all other payment events.
3. Missing `currency` field — all other payment events include it.
4. No consumer exists for this event type.

**Files**: `PaymentService.java:152–155`, `contracts/.../payments/` (missing file)

---

### HIGH-01: `OrderDelivered` publisher payload diverges from schema

**Schema** (`OrderDelivered.v1.json`): requires `orderId`, `userId`, `deliveredAt`. Optional: `riderId`, `deliveryDurationMinutes`.  
**Publisher** (`DeliveryService.java` lines 119–124): emits `orderId`, `userId`, `deliveredAt`. Does NOT emit `riderId` or `deliveryDurationMinutes`.  
**Doc schema** (`09-contracts-events-protobuf.md` lines 177–189): requires `riderId`.

The publisher **omits `riderId`** which the doc schema says is required. The actual JSON schema file made `riderId` optional (correct relaxation), but the doc is stale.

**Files**: `DeliveryService.java:119–124`, `OrderDelivered.v1.json`, doc lines 177–189

---

### HIGH-02: `OrderPacked` — fulfillment publisher emits `note` field not in schema

**Publisher** (`PickService.java` lines 205–212): emits `orderId`, `userId`, `storeId`, `packedAt`, `note`.  
**Schema** (`fulfillment/OrderPacked.v1.json`): No `note` property. Also present in `orders/OrderPacked.v1.json` without `note`.

**Duplicate schema**: `OrderPacked.v1.json` exists in BOTH `schemas/orders/` and `schemas/fulfillment/` with different `required` fields:
- `orders/OrderPacked.v1.json` requires `storeId`
- `fulfillment/OrderPacked.v1.json` does NOT require `storeId`

**Severity**: HIGH  
**Files**: `PickService.java:205–212`, `orders/OrderPacked.v1.json`, `fulfillment/OrderPacked.v1.json`

---

### HIGH-03: `PaymentRefunded` — doc schema `amountCents` type inconsistency

**Doc** (`09-contracts-events-protobuf.md` lines 209–226): defines `amountCents` as `integer`.  
**JSON schema file** (`PaymentRefunded.v1.json`): defines `amountCents` as `integer`.  
**Publisher** (`RefundService.java` lines 95–105): emits `amountCents` as `long` (Java `long`) — serialized as JSON integer. ✅ matches.  
**However**, the `currency` field in the schema has NO enum constraint (unlike the doc which uses `"enum": ["INR", "USD"]`). This allows arbitrary currency strings.

The doc also shows `refundedAt` is **not required** but the publisher **does emit it** (line 101). This is backward-compatible but the schema should document it.

**Files**: `RefundService.java:95–105`, `PaymentRefunded.v1.json`, doc lines 209–226

---

## 2. Protobuf Definitions

### HIGH-04: `inventory_service.proto` imports `money.proto` but never uses it

**File**: `contracts/src/main/proto/inventory/v1/inventory_service.proto` line 7:
```protobuf
import "common/v1/money.proto";
```
No message in this file references `common.v1.Money`. This is a dead import.

**Severity**: LOW

---

### MEDIUM-01: No `fulfillment_service.proto` exists

The fulfillment service makes synchronous HTTP calls to order-service (`OrderClient`) and payment-service (`PaymentClient`) instead of using gRPC. Given that inventory and payment have gRPC definitions, fulfillment is an outlier creating an inconsistent communication pattern.

**Files**: `contracts/src/main/proto/` — no `fulfillment/` directory with service proto

---

### MEDIUM-02: Proto versioning — no v2 packages exist yet, but no deprecation annotations

All protos are `v1`. The versioning strategy (doc section 6) says to create `v2` for breaking changes. However:
- No `option deprecated = true` on any fields
- No `reserved` field numbers declared for future evolution
- No `google.protobuf.FieldOptions` extensions for deprecation

**Recommendation**: Add `reserved` ranges and document field deprecation policy in proto comments.

---

### MEDIUM-03: `payment_service.proto` `AuthorizeRequest` uses `payment_method_id` (string) — not a typed enum

The field `payment_method_id = 5` accepts any string. There's no validation or enum constraining allowed payment method types. For a 20M+ user platform, this should be a well-known enum or at least documented patterns.

---

## 3. Event Envelope

### HIGH-05: Inconsistent envelope structures across consumers

Three different envelope record types exist:

| Service | Class | Fields |
|---------|-------|--------|
| fulfillment-service | `OrderEvent` | `eventType`, `payload` |
| fulfillment-service | `EventEnvelope` | `id`, `aggregateId`, `eventType`, `payload` |
| notification-service | `EventEnvelope` | `id`, `aggregateId`, `eventType`, `payload` |
| order-service | `EventEnvelope` | `id`, `aggregateId`, `eventType`, `payload` |

**Issues**:
1. `OrderEvent` (used by fulfillment's `OrderEventConsumer`) lacks `id` and `aggregateId`, making it impossible to correlate/deduplicate events.
2. The doc says envelope has `headers.id`, `headers.aggregateType`, `headers.eventType` — but Debezium outbox transform puts these in Kafka headers, NOT in the JSON body. The consumer code reads `eventType` from the JSON body, which means it's reading the outbox table's raw row, not the transformed envelope.
3. **No `version` field** in any envelope — doc section 6 mentions schema versioning but the envelope carries no version indicator.
4. **No `timestamp` field** in the envelope — consumers rely on individual event payloads for timestamps, which are inconsistently present.

**Files**:
- `fulfillment/.../OrderEvent.java`
- `fulfillment/.../EventEnvelope.java`
- `notification/.../EventEnvelope.java`
- `order/.../EventEnvelope.java`

---

### HIGH-06: `EventEnvelope` is duplicated across 3 services — should be in contracts module

The `EventEnvelope` record is copy-pasted identically in order-service, fulfillment-service, and notification-service. It should be a shared DTO in the `contracts` module to ensure consistency.

Similarly, `UserErasedEvent` is duplicated across 3 services.

**Files**: All `EventEnvelope.java` and `UserErasedEvent.java` files listed above.

---

## 4. Missing Events

### CRITICAL: Events published without schemas

| Event Type | Publisher | Schema Exists? |
|-----------|-----------|----------------|
| `OrderCreated` | `order-service/OrderService.java:81` | ❌ NO |
| `OrderStatusChanged` | `order-service/OrderService.java:163` | ❌ NO |
| `PaymentVoided` | `payment-service/PaymentService.java:152` | ❌ NO |
| `UserErased` | `identity-service/UserDeletionService.java:46` | ❌ NO |
| `OrderModified` | `fulfillment-service/SubstitutionService.java:38` | ❌ NO |
| `OrderDispatched` | `fulfillment-service/DeliveryService.java:201` | Published as `OrderDispatched` from Fulfillment aggregate, but schema is in `orders/` not `fulfillment/` |

**5 events published with zero schema definition**. This is a contract governance failure.

### Events in schemas but never published

| Schema | Publisher Found? |
|--------|-----------------|
| `orders/OrderFailed.v1.json` | ❌ No outbox publish found |
| `payments/PaymentFailed.v1.json` | ❌ No outbox publish found |
| `inventory/StockReserved.v1.json` | ❌ No outbox publish in inventory-service |
| `inventory/StockConfirmed.v1.json` | ❌ No outbox publish in inventory-service |
| `inventory/StockReleased.v1.json` | ❌ No outbox publish in inventory-service |
| `inventory/LowStockAlert.v1.json` | ❌ No outbox publish in inventory-service |
| `fulfillment/PickTaskCreated.v1.json` | ❌ No outbox publish found |
| `fulfillment/RiderAssigned.v1.json` | ❌ No outbox publish found |
| `fulfillment/DeliveryCompleted.v1.json` | ❌ No outbox publish found (uses `OrderDelivered` instead) |
| `catalog/ProductCreated.v1.json` | ⚠️ Published but payload mismatches (see below) |
| `catalog/ProductUpdated.v1.json` | ⚠️ Published but payload mismatches (see below) |

**The entire inventory-service has no outbox publishing at all** — no `OutboxService` class, no outbox event table usage found. All 4 inventory event schemas are orphaned.

---

## 5. Consumer Alignment

### HIGH-07: Fulfillment `OrderEventConsumer` uses `OrderEvent` instead of `EventEnvelope`

**File**: `fulfillment/.../OrderEventConsumer.java` line 26:
```java
OrderEvent event = objectMapper.readValue(record.value(), OrderEvent.class);
```

`OrderEvent` has only `eventType` + `payload` (no `id`, no `aggregateId`). But every other consumer uses `EventEnvelope` which has `id` + `aggregateId`. This means:
- No deduplication possible on order events in fulfillment
- Different deserialization contract from other services

**Severity**: HIGH

---

### HIGH-08: `OrderPlaced` consumer DTO mismatches with schema for `totalAmount` → `totalCents`

**Schema** (`OrderPlaced.v1.json`): uses `totalCents`, `unitPriceCents`, `lineTotalCents`  
**Consumer DTO** (`fulfillment/.../OrderPlacedPayload.java`): Has NO `totalCents` field — only `orderId`, `userId`, `storeId`, `paymentId`, `items`.  
**Consumer DTO** (`fulfillment/.../OrderPlacedItem.java`): `unitPriceCents` (long), `lineTotalCents` (long) — matches schema.

The `OrderPlacedPayload` is **missing `totalCents`, `currency`, `placedAt`** which are all required in the schema. This is fine because `@JsonIgnoreProperties(ignoreUnknown = true)` will skip them, but it means the fulfillment service **cannot access** these fields.

**Severity**: MEDIUM

---

### MEDIUM-04: `catalog.events` topic has NO consumers

The doc says `catalog.events` is consumed by `inventory-service (new products)`. But:
- No `@KafkaListener` for `catalog.events` exists in any service
- No consumer class in inventory-service processes catalog events

**Files**: `docs/09-contracts-events-protobuf.md` line 68, all service consumer packages

---

### MEDIUM-05: `payments.events` — order-service is listed as consumer but has no listener

The doc says `payments.events` is consumed by `order-service, notification`. But:
- `order-service` only has a `@KafkaListener` for `identity.events`
- No `payments.events` consumer exists in order-service

The Temporal workflow may handle payment responses via gRPC instead, but this contradicts the documented architecture.

**Files**: `docs/09-contracts-events-protobuf.md` line 65, `order-service/consumer/`

---

## 6. Versioning Strategy

### MEDIUM-06: No runtime schema validation exists

The doc (section 6) describes consumer-driven contract tests, but:
- No JSON schema validation library is in any service's `build.gradle.kts` (e.g., `networknt/json-schema-validator`)
- The contract test shown in the doc is a **sample** — no actual test classes implement it
- No CI/CD schema validation gate

**Severity**: MEDIUM

---

### MEDIUM-07: `ProductChangedEvent` payload does not match either `ProductCreated.v1.json` or `ProductUpdated.v1.json`

**Publisher DTO** (`catalog/.../ProductChangedEvent.java`):
```java
record ProductChangedEvent(UUID id, String sku, String name, String slug, UUID categoryId, boolean active)
```

**Schema** (`ProductCreated.v1.json`): requires `productId`, `name`, `sku`, `createdAt`.  
**Schema** (`ProductUpdated.v1.json`): requires `productId`, `updatedAt`.

| Field | Publisher | ProductCreated Schema | ProductUpdated Schema |
|-------|-----------|----------------------|----------------------|
| `id` | ✅ | ❌ expects `productId` | ❌ expects `productId` |
| `sku` | ✅ | ✅ | ❌ |
| `name` | ✅ | ✅ | ❌ |
| `slug` | ✅ | ❌ | ❌ |
| `categoryId` | ✅ | ❌ | ❌ |
| `active` | ✅ | ❌ | ❌ |
| `productId` | ❌ | ✅ required | ✅ required |
| `createdAt` | ❌ | ✅ required | ❌ |
| `updatedAt` | ❌ | ❌ | ✅ required |

**Impact**: CRITICAL — the publisher emits `id` but the schema requires `productId`. Any consumer validating against the schema will reject the event. Additionally, required timestamp fields (`createdAt`/`updatedAt`) are never emitted.

**Files**: `catalog/.../ProductChangedEvent.java`, `ProductCreated.v1.json`, `ProductUpdated.v1.json`

---

## 7. Cross-Service Coupling

### HIGH-09: Fulfillment service makes synchronous HTTP calls to order-service and payment-service

**Files**:
- `fulfillment/.../OrderClient` — called from `PickService.java:115` (`updateStatus`), `DeliveryService.java:115,200`
- `fulfillment/.../PaymentClient` — called from `SubstitutionService.java:37` (`refund`)
- `fulfillment/.../InventoryClient` — called from `SubstitutionService.java:35` (`releaseStock`)

**Issues**:
1. `OrderClient.updateStatus()` is called synchronously during pick/pack/delivery flow. If order-service is down, the entire fulfillment pipeline stalls.
2. `PaymentClient.refund()` is called synchronously during substitution. Payment gateway timeouts will block the fulfillment transaction.
3. These should be **async commands via Kafka** or at minimum use **circuit breakers** with fallback.

**Severity**: HIGH

---

### MEDIUM-08: Notification service makes synchronous REST calls to order-service and identity-service

**File**: `notification/.../NotificationService.java`:
- `orderLookupClient.findOrder()` (line 202) — sync HTTP to order-service
- `userDirectoryClient.findUser()` (lines 170, 264) — sync HTTP to identity-service

These are read-only lookups but create runtime coupling. If order-service is slow, notification delivery is delayed.

---

## 8. Data Contracts — Proto vs JSON Discrepancies

### LOW-01: Money representation inconsistency — proto uses `amount_minor` (int64), JSON uses `amountCents` (int)

**Proto** (`money.proto`): `int64 amount_minor` — name says "minor units"  
**JSON events**: field named `amountCents` (integer)  
**Doc**: says to use `amount_minor` for amounts to avoid float rounding

The naming is inconsistent: `amount_minor` vs `amountCents`. The semantics are the same but a developer must know both conventions.

---

### LOW-02: `OrderCancelled` schema is minimal — missing `userId`, `cancelledAt`

**Schema** (`OrderCancelled.v1.json`): only `orderId` + `reason`.  
**Publisher** (`OrderService.java:129–130`): emits `orderId` + `reason`.

While consistent, the event lacks `userId` and a timestamp, making it less useful for consumers (notification-service needs `userId` to send cancellation notifications — currently has to do an order lookup).

---

### LOW-03: `LowStockAlert` schema discrepancy — doc says `warehouseId`, actual schema says `storeId`

**Doc** (`09-contracts-events-protobuf.md` line 261): `"warehouseId": { "type": "string", "format": "uuid" }`  
**Actual schema** (`LowStockAlert.v1.json` line 8): `"storeId": { "type": "string" }`

Also, the doc has `format: uuid` on it but the actual schema has just `type: string` with no format constraint.

---

### LOW-04: `catalog-service` OutboxService missing `@Transactional(propagation = MANDATORY)`

All other OutboxService implementations (order, payment, fulfillment, identity) use `@Transactional(propagation = Propagation.MANDATORY)` to ensure outbox writes participate in the caller's transaction.

The catalog OutboxService (`catalog/.../OutboxService.java`) has **no `@Transactional` annotation at all**. This means:
- If the caller's transaction rolls back, the outbox event may still be persisted
- This breaks the transactional outbox guarantee

**Severity**: HIGH  
**File**: `catalog/.../OutboxService.java:21`

---

### LOW-05: `OrderDispatched` published by fulfillment but schema lives under `orders/`

The event `OrderDispatched` is published with `aggregateType = "Fulfillment"` (from `DeliveryService.java:201`) but its schema is at `contracts/.../schemas/orders/OrderDispatched.v1.json`. The Debezium outbox transform routes based on aggregate type, so this event goes to `fulfillment.events` topic — but the schema is cataloged under orders. This creates confusion about which topic carries this event.

---

## Consolidated Action Items (Priority Order)

| # | Severity | Action |
|---|----------|--------|
| 1 | CRITICAL | Add missing schemas: `OrderCreated.v1.json`, `OrderStatusChanged.v1.json`, `PaymentVoided.v1.json`, `UserErased.v1.json`, `OrderModified.v1.json` |
| 2 | CRITICAL | Fix `ProductChangedEvent` to emit `productId` instead of `id`, and add `createdAt`/`updatedAt` timestamps |
| 3 | CRITICAL | Update `docs/09-contracts-events-protobuf.md` to match actual schema files (totalCents not totalAmount, amountCents not amount, etc.) |
| 4 | HIGH | Add `@Transactional(propagation = MANDATORY)` to catalog-service `OutboxService` |
| 5 | HIGH | Standardize on `EventEnvelope` in fulfillment `OrderEventConsumer` (replace `OrderEvent`) |
| 6 | HIGH | Extract `EventEnvelope`, `UserErasedEvent` into contracts module as shared DTOs |
| 7 | HIGH | Add circuit breakers to fulfillment→order, fulfillment→payment sync calls; plan migration to async commands |
| 8 | HIGH | Add `currency` to `PaymentVoided` event; rename `amount` → `amountCents` for consistency |
| 9 | MEDIUM | Implement inventory-service outbox publishing for `StockReserved`, `StockConfirmed`, `StockReleased`, `LowStockAlert` |
| 10 | MEDIUM | Add `catalog.events` consumer in inventory-service per documented architecture |
| 11 | MEDIUM | Add `payments.events` consumer in order-service or remove from doc if handled via gRPC |
| 12 | MEDIUM | Add `version` and `timestamp` fields to `EventEnvelope` |
| 13 | MEDIUM | Implement JSON schema validation in CI pipeline |
| 14 | LOW | Remove unused `money.proto` import from `inventory_service.proto` |
| 15 | LOW | Add `userId` + `cancelledAt` to `OrderCancelled` schema |
