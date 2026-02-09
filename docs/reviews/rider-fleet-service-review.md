# Rider Fleet Service — Deep Architecture Review

> **Reviewer:** Senior Fleet Management Architect  
> **Service:** `rider-fleet-service`  
> **Codebase:** Spring Boot 3 / Java 21 / PostgreSQL / Kafka  
> **Scale Target:** 50K+ riders (Q-commerce)  
> **Review Date:** 2025-07-02  
> **Verdict:** ⚠️ **MVP-grade — not production-ready for 50K riders. Critical gaps in assignment algorithm, concurrency safety, real-time location, rider lifecycle, and compliance.**

---

## Table of Contents

1. [Codebase Summary](#1-codebase-summary)
2. [Business Logic Review](#2-business-logic-review)
3. [SLA & Performance Review](#3-sla--performance-review)
4. [Missing Features](#4-missing-features)
5. [Q-Commerce Competitive Gap Analysis](#5-q-commerce-competitive-gap-analysis)
6. [Database Schema Analysis](#6-database-schema-analysis)
7. [Infrastructure & Ops Review](#7-infrastructure--ops-review)
8. [Security Review](#8-security-review)
9. [Testing](#9-testing)
10. [Prioritized Recommendations](#10-prioritized-recommendations)
11. [Appendix — File-by-File Inventory](#11-appendix--file-by-file-inventory)

---

## 1. Codebase Summary

### Tech Stack
| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3 (Web, JPA, Security, Kafka, Cache, Actuator) |
| Database | PostgreSQL (via Flyway migrations) |
| Messaging | Apache Kafka (consumer + producer via outbox) |
| Caching | Caffeine (local, `maximumSize=1000, expireAfterWrite=60s`) |
| Locking | ShedLock (JDBC-based distributed scheduler lock) |
| Auth | JWT (RSA public-key verification, roles-based) |
| Observability | Micrometer + OTLP tracing + Logstash JSON logging |
| Secrets | GCP Secret Manager |
| Container | Multi-stage Docker, JRE 21 Alpine, ZGC, non-root user |

### File Count
- **49 Java source files** (0 test files)
- **7 Flyway migrations** (V1–V7)
- **1 application.yml**, **1 logback-spring.xml**, **1 Dockerfile**, **1 build.gradle.kts**

### Module Structure
```
├── config/          — RiderFleetProperties, SecurityConfig, ShedLockConfig, KafkaErrorConfig
├── consumer/        — FulfillmentEventConsumer, EventEnvelope, FulfillmentEventPayload
├── controller/      — AdminRiderController, RiderController, RiderAssignmentController
├── domain/model/    — Rider, RiderAvailability, RiderEarning, RiderRating, RiderShift, OutboxEvent, enums
├── dto/             — request (3), response (4)
├── exception/       — GlobalExceptionHandler, ApiException hierarchy, TraceIdProvider
├── repository/      — 6 JPA repositories
├── security/        — JwtAuthenticationFilter, DefaultJwtService, JwtKeyLoader, entry points
├── service/         — RiderService, RiderAssignmentService, RiderAvailabilityService, RiderEarningsService, OutboxService
```

---

## 2. Business Logic Review

### 2.1 Rider Onboarding

**Current Implementation:**

```
POST /admin/riders          → createRider()   → Status = INACTIVE
POST /admin/riders/{id}/onboard  → onboardRider() → Status = ACTIVE
POST /admin/riders/{id}/activate → activateRider()→ Status = ACTIVE
```

`RiderService.createRider()` accepts name, phone, email, vehicleType, licenseNumber, storeId. Rider starts as `INACTIVE`. `onboardRider()` and `activateRider()` both do the exact same thing: check `INACTIVE → ACTIVE`.

**Critical Gaps:**

| Feature | Status | Severity |
|---|---|---|
| Document verification (Aadhaar/PAN/DL upload) | ❌ Missing | 🔴 Critical |
| Background check integration | ❌ Missing | 🔴 Critical |
| Training status tracking | ❌ Missing | 🟠 High |
| Vehicle document verification | ❌ Missing | 🔴 Critical |
| License expiry tracking | ❌ Missing | 🔴 Critical |
| Onboarding workflow state machine | ❌ Missing | 🟠 High |
| Profile photo / identity verification | ❌ Missing | 🟠 High |
| Bank account / payout details | ❌ Missing | 🔴 Critical |

**Analysis:**
- `onboardRider()` and `activateRider()` are duplicate logic — both do `INACTIVE → ACTIVE`. There's no intermediate state like `PENDING_VERIFICATION`, `DOCUMENTS_SUBMITTED`, `BACKGROUND_CHECK_PENDING`, `TRAINING_INCOMPLETE`.
- `CreateRiderRequest` has `licenseNumber` as optional (`String` with no `@NotBlank`). For motorcycle/car riders, this must be mandatory.
- No `Rider` entity fields for: `aadhaarVerified`, `backgroundCheckStatus`, `trainingCompleted`, `documentsVerifiedAt`, `bankAccountId`.
- The `RiderStatus` enum (`INACTIVE, ACTIVE, ON_DELIVERY, SUSPENDED, BLOCKED`) lacks onboarding lifecycle states.

**Recommendation:**
```java
// Suggested RiderStatus expansion:
enum RiderStatus {
    REGISTERED,           // Just signed up
    DOCUMENTS_PENDING,    // Awaiting document upload
    DOCUMENTS_SUBMITTED,  // Under review
    BACKGROUND_CHECK,     // BGV in progress
    TRAINING_PENDING,     // Needs to complete training
    READY,                // Fully onboarded, not yet online
    ACTIVE,               // Online, available for orders
    ON_DELIVERY,          // Currently delivering
    ON_BREAK,             // Temporarily unavailable
    SUSPENDED,            // Temporarily banned
    BLOCKED,              // Permanently banned
    CHURNED               // Left the platform
}
```

---

### 2.2 Assignment Algorithm

**Current Implementation (`RiderAssignmentService.assignRider()` + `RiderAvailabilityRepository.findNearestAvailable()`):**

```sql
SELECT ra.* FROM rider_availability ra
JOIN riders r ON r.id = ra.rider_id
WHERE ra.is_available = true
  AND ra.store_id = :storeId
  AND r.status = 'ACTIVE'
  AND ra.current_lat IS NOT NULL
  AND ra.current_lng IS NOT NULL
  AND (6371 * acos(cos(radians(:lat)) * cos(radians(ra.current_lat))
       * cos(radians(ra.current_lng) - radians(:lng))
       + sin(radians(:lat)) * sin(radians(ra.current_lat)))) <= :radiusKm
ORDER BY distance ASC, r.rating_avg DESC
LIMIT 1
FOR UPDATE OF ra SKIP LOCKED
```

**What it does well:**
- ✅ Haversine formula for distance calculation
- ✅ `FOR UPDATE ... SKIP LOCKED` — prevents double-assignment under concurrency
- ✅ Secondary sort by `rating_avg DESC` — higher-rated riders get preference
- ✅ Configurable radius via `rider-fleet.assignment.default-radius-km` (default 5km)
- ✅ Store-scoped — only assigns riders from the same store

**Critical Gaps:**

| Feature | Status | Severity |
|---|---|---|
| Load balancing (fair distribution) | ❌ Missing | 🔴 Critical |
| Multi-order batching | ❌ Missing | 🔴 Critical |
| ETA-based assignment (travel time, not just distance) | ❌ Missing | 🟠 High |
| Rider capacity / current load | ❌ Missing | 🔴 Critical |
| Surge pricing influence on assignment | ❌ Missing | 🟡 Medium |
| Radius escalation (retry with wider radius) | ❌ Missing | 🟠 High |
| Pre-assignment (before pack complete) | ❌ Missing | 🟠 High |
| Assignment timeout + reassignment | ❌ Missing | 🔴 Critical |
| Rider acceptance/rejection flow | ❌ Missing | 🟠 High |
| Weighted scoring (distance + rating + delivery count + idle time) | ❌ Missing | 🟠 High |
| Cross-store assignment fallback | ❌ Missing | 🟡 Medium |

**Performance Concern:**
The Haversine calculation runs in PostgreSQL on every row. At 50K riders with 5K available at any moment, this full-table scan with trigonometric functions will be **slow**. No spatial index (PostGIS `geography` type / GiST index) is used.

**Recommendation:**
```sql
-- Use PostGIS for O(log n) spatial queries instead of O(n) Haversine scan
ALTER TABLE rider_availability ADD COLUMN location geography(Point, 4326);
CREATE INDEX idx_rider_availability_location ON rider_availability USING GIST(location);

-- Query becomes:
SELECT ra.* FROM rider_availability ra
JOIN riders r ON r.id = ra.rider_id
WHERE ra.is_available = true
  AND ST_DWithin(ra.location, ST_MakePoint(:lng, :lat)::geography, :radiusMeters)
ORDER BY ra.location <-> ST_MakePoint(:lng, :lat)::geography
LIMIT 1
FOR UPDATE OF ra SKIP LOCKED;
```

---

### 2.3 Availability Management

**Current Implementation:**

```
POST /riders/{id}/availability?available=true|false  → toggleAvailability()
POST /riders/{id}/location                           → updateLocation(lat, lng)
```

`RiderAvailabilityService.toggleAvailability()` simply sets `is_available` to true/false.  
`updateLocation()` updates `current_lat`/`current_lng` in the `rider_availability` table.

**What exists:**
- ✅ Manual toggle (rider goes online/offline)
- ✅ Location update endpoint
- ✅ Availability per store (`store_id` on `rider_availability`)
- ✅ `last_updated` timestamp on availability record

**Critical Gaps:**

| Feature | Status | Severity |
|---|---|---|
| Shift-based availability enforcement | ❌ Missing | 🟠 High |
| Auto-offline after idle timeout | ❌ Missing | 🔴 Critical |
| Heartbeat / liveness check | ❌ Missing | 🔴 Critical |
| GPS staleness detection | ❌ Missing | 🔴 Critical |
| Geofencing (zone enforcement) | ❌ Missing | 🟠 High |
| Auto-available after delivery complete | ❌ Missing | 🟠 High |
| Maximum online hours enforcement | ❌ Missing | 🟠 High |
| Shift scheduling integration | ❌ Missing | 🟡 Medium |

**Analysis:**
- `RiderShift` entity exists with `shift_start`, `shift_end`, `status` but is **completely unused** — no service references it, no controller exposes it, `RiderShiftRepository` is defined but never injected anywhere.
- No scheduled job to check if riders with stale locations (e.g., no update for 5+ minutes) should be auto-offlined.
- `toggleAvailability()` doesn't check if rider is `ACTIVE` — a `SUSPENDED` rider could toggle available.
- No WebSocket/SSE for real-time location streaming — only REST `POST /location`.
- Location updates go directly to PostgreSQL. At 50K riders updating every 5 seconds = **10K writes/sec** — PostgreSQL will buckle. Need Redis or a time-series DB for hot location data.

**Recommendation:**
- Implement a scheduled job (using ShedLock, which is already configured) to auto-offline stale riders
- Use Redis for location storage with PostgreSQL as cold storage
- Enforce shift boundaries — rider can only go online during scheduled shifts
- Validate rider status before allowing availability toggle

---

### 2.4 Earnings Calculation

**Current Implementation (`RiderEarningsService`):**

```java
void recordEarning(UUID orderId, UUID riderId, long feeCents, long tipCents)
// Creates RiderEarning with deliveryFeeCents + tipCents
// incentiveCents is in the schema but NEVER SET in recordEarning()

EarningsSummaryResponse getEarningsSummary(UUID riderId, Instant from, Instant to)
// Sums: totalDeliveryFee + totalTip + totalIncentive
// Returns: total, deliveryFee, tip, incentive, count, dateRange
```

**What exists:**
- ✅ Per-order earnings tracking (delivery fee + tip)
- ✅ Money stored in cents (avoids floating point)
- ✅ Date-range earnings summary
- ✅ `sumTotalEarnings()` query in repository (though unused in service — uses Java-side sum instead)

**Critical Gaps:**

| Feature | Status | Severity |
|---|---|---|
| Earnings calculation logic (formula) | ❌ Missing — `feeCents` is passed externally, no calculation | 🔴 Critical |
| Distance-based fee calculation | ❌ Missing | 🔴 Critical |
| Surge multiplier | ❌ Missing | 🔴 Critical |
| Incentive recording | ❌ Missing — field exists but never populated | 🟠 High |
| Peak hour bonus | ❌ Missing | 🟠 High |
| Weather bonus (rain/extreme heat) | ❌ Missing | 🟡 Medium |
| Streak rewards | ❌ Missing | 🟡 Medium |
| Minimum guarantee per hour | ❌ Missing | 🟠 High |
| Payout/settlement integration | ❌ Missing | 🔴 Critical |
| Deductions (cash collected, penalties) | ❌ Missing | 🟠 High |
| Daily/weekly earnings dashboard data | Partial (summary exists) | 🟡 Medium |

**Bugs/Issues:**
1. `recordEarning()` never sets `incentiveCents` — always defaults to 0.
2. `getEarningsSummary()` loads ALL `RiderEarning` entities into memory for a date range, then sums in Java. The repository already has `sumTotalEarnings()` which does it in SQL. For a rider with 200 deliveries/day × 365 days = 73K rows loaded into memory for a yearly summary. **Use the SQL aggregate.**
3. No `@Column(unique = true)` on `order_id` in `RiderEarning` — same order can be double-recorded (unlike `RiderRating` which has `unique = true` on `order_id`).
4. `recordEarning()` is called from nowhere in the codebase — no Kafka consumer or controller triggers it.

**Recommendation:**
- Build an earnings calculation engine: `baseFee + (distanceKm × perKmRate) + surgeMutliplier + tip + incentive - deductions`
- Wire `recordEarning()` into the delivery-complete flow via Kafka consumer
- Use the SQL aggregate query instead of loading all rows
- Add unique constraint on `(rider_id, order_id)` in earnings table

---

### 2.5 Rating System

**Current Implementation:**

`RiderRating` entity exists with `rider_id`, `order_id`, `rating` (1-5), `comment`. `RiderRatingRepository` has `findByRiderId()`.

**What exists:**
- ✅ Rating schema with 1-5 constraint (`CHECK (rating >= 1 AND rating <= 5)`)
- ✅ One rating per order (`order_id UNIQUE`)
- ✅ `rating_avg` on `Rider` entity

**What's completely missing:**

| Feature | Status | Severity |
|---|---|---|
| Rating submission API | ❌ No controller/service to submit ratings | 🔴 Critical |
| Average rating recalculation | ❌ Missing — `rating_avg` is set to 5.00 and never updated | 🔴 Critical |
| Rating impact on assignment priority | Partially ✅ (sort by `rating_avg DESC` in query) | 🟡 Medium |
| Low rating threshold actions | ❌ Missing | 🟠 High |
| Auto-suspend below threshold | ❌ Missing | 🟠 High |
| Rating dispute mechanism | ❌ Missing | 🟡 Medium |
| Rider rates customer | ❌ Missing | 🟡 Medium |

**Analysis:**
- The rating system is **schema-only**. No endpoint to submit a rating, no service to calculate `rating_avg`, no automated actions on low ratings.
- `Rider.ratingAvg` is initialized to `5.00` and never updated by any code path.
- The assignment query sorts by `r.rating_avg DESC` as a tiebreaker, so the rating plumbing is ready but non-functional.

---

### 2.6 Pessimistic Locking

**Current Implementation:**

```sql
FOR UPDATE OF ra SKIP LOCKED
```

**Analysis — This is well-implemented:**
- ✅ `FOR UPDATE OF ra SKIP LOCKED` ensures that if two concurrent assignment requests hit the same rider, the second one skips the locked row and picks the next available rider.
- ✅ Runs within `@Transactional` so the lock is held for the duration of the transaction.
- ✅ Both `rider.status` and `availability.isAvailable` are updated atomically.

**Remaining Gaps:**

| Issue | Severity |
|---|---|
| No idempotency key — same order can trigger multiple assignments | 🔴 Critical |
| No check if order already has a rider assigned | 🔴 Critical |
| `Rider.status` update lacks optimistic locking (`@Version`) | 🟠 High |
| No transaction timeout configured | 🟡 Medium |

**Critical Bug:**
If `FulfillmentEventConsumer` receives the same `OrderPacked` event twice (Kafka at-least-once), it will assign **two different riders** to the same order. There is no deduplication on `orderId`.

**Recommendation:**
```java
// Add to RiderAssignmentService.assignRider():
if (earningRepository.existsByOrderId(orderId)) {
    throw new DuplicateAssignmentException(orderId);
}
// Or maintain an assignment table with UNIQUE(order_id)
```

---

### 2.7 Multi-Order Batching

**Status: ❌ Completely Missing**

The current system assigns exactly one rider to one order. `RiderStatus.ON_DELIVERY` means the rider is locked — they cannot receive additional orders.

For Q-commerce with nearby drop-offs, a rider should carry 2-3 orders from the same dark store going in the same direction.

**What's needed:**
```
- Assignment table: rider_assignments (rider_id, order_id, status, sequence, batch_id)
- Rider capacity field: max_concurrent_orders (default 1, up to 3)
- Batch assignment logic: group orders by direction/proximity before assigning
- Status model: ON_DELIVERY with current_load = 1/2/3
```

---

## 3. SLA & Performance Review

### 3.1 Assignment Latency

**Target:** < 30 seconds from pack-complete to rider-assigned

**Current Flow:**
```
Fulfillment Service → Kafka (fulfillment.events) → FulfillmentEventConsumer → assignRider()
```

**Latency Breakdown (estimated):**

| Step | Estimated Latency | Concern |
|---|---|---|
| Kafka produce + consume | 50-200ms | ✅ OK |
| PostgreSQL Haversine query (no spatial index) | 100-500ms at scale | ⚠️ Slow |
| Pessimistic lock acquisition | 0-50ms (low contention), 100-500ms (high contention) | ⚠️ Variable |
| Two `save()` calls (rider + availability) | 10-50ms | ✅ OK |
| Outbox write | 5-20ms | ✅ OK |
| **Total** | **~200ms - 1.5s** (low load) | ✅ Under target |

**At Scale (50K riders, 500 orders/min):**
- Haversine on 5K available rows without spatial index: **500ms-2s per query**
- Under concurrent load, `SKIP LOCKED` causes cascading retries
- Kafka consumer is single-threaded (default `concurrency=1`)
- **Risk: p99 latency > 5s during peak**

**Recommendations:**
1. Add PostGIS spatial index (reduces query to < 10ms)
2. Increase Kafka consumer concurrency (`concurrency=5` on `@KafkaListener`)
3. Pre-compute available riders in Redis sorted set by geohash
4. Add assignment latency metrics (`micrometer.timer`)

---

### 3.2 Location Updates

**Current Implementation:**
```
POST /riders/{id}/location  → RiderAvailabilityService.updateLocation(lat, lng)
```

**Analysis:**

| Metric | Current | Industry Standard |
|---|---|---|
| Protocol | HTTP REST | WebSocket / MQTT |
| Storage | PostgreSQL (direct write) | Redis → batch to PG |
| Frequency | Client-controlled (unknown) | Every 5-10 seconds |
| Battery optimization | ❌ None | Adaptive frequency based on movement |
| Staleness detection | ❌ None | Auto-offline if no update > 2 min |
| Geofencing | ❌ None | Virtual fences around stores/zones |

**Critical Performance Issue:**
At 50K riders × 1 update every 5 seconds = **10,000 writes/second** to PostgreSQL. With Hikari pool of 20 connections, each update taking ~5ms = 200 ops/sec/connection = 4,000 ops/sec max. **PostgreSQL will saturate.**

**Recommendation:**
- Use Redis with geospatial commands (`GEOADD`, `GEORADIUS`) for real-time location
- Batch sync to PostgreSQL every 30 seconds for persistence
- Switch to WebSocket for location streaming (reduces HTTP overhead)
- Implement adaptive location frequency: high frequency during delivery, low when idle

---

### 3.3 Concurrent Assignment Prevention

**Current Status: Partially Implemented**

| Protection | Status |
|---|---|
| Two requests for different orders picking same rider | ✅ `SKIP LOCKED` handles this |
| Same order assigned twice (Kafka retry) | ❌ No idempotency check |
| Rider toggling offline during assignment | ❌ Race condition possible |
| Rider already `ON_DELIVERY` receiving second order | ✅ Query filters `status = 'ACTIVE'` |

**The `SKIP LOCKED` is good** but not sufficient. The missing idempotency on `orderId` is the most critical gap.

---

## 4. Missing Features

### 4.1 Rider Incentive Programs

**Status: ❌ Not Implemented**

The `incentive_cents` column exists in `rider_earnings` but is never populated. No incentive engine exists.

**Required for 50K-rider fleet:**

| Incentive Type | Description | Priority |
|---|---|---|
| Peak hour bonus | ₹20-50 extra per delivery during 12-2pm, 7-10pm | 🔴 Critical |
| Rain/weather bonus | ₹30-60 extra when weather is adverse | 🟠 High |
| Streak rewards | Complete 5 deliveries in a row → ₹100 bonus | 🟠 High |
| First-mile bonus | Extra for long pickup distances | 🟡 Medium |
| Referral bonus | Rider refers new rider → both get ₹500 | 🟡 Medium |
| Login bonus | ₹50 for logging in during low-supply hours | 🟠 High |
| Guaranteed minimum | ₹150/hour minimum during committed shifts | 🔴 Critical |
| Retention bonus | Monthly bonus for riders with > 95% acceptance rate | 🟡 Medium |

**Needed Components:**
- `IncentiveRule` entity (condition, amount, time window)
- `IncentiveEngine` service (evaluates rules per delivery)
- `IncentiveLedger` for tracking earned vs. paid incentives
- Admin API for creating/modifying incentive programs

---

### 4.2 Rider App Features

**Status: ❌ No rider-facing APIs exist beyond basic CRUD**

| Feature | Status | Notes |
|---|---|---|
| Order details (items, customer name, address) | ❌ Missing | No order data in this service |
| Navigation / route guidance | ❌ Missing | No maps integration |
| Customer contact (masked calling) | ❌ Missing | No communication service |
| Proof of delivery (photo) | ❌ Missing | No media upload |
| Delivery OTP verification | ❌ Missing | Critical for Q-commerce |
| Earnings dashboard (today/weekly) | Partial ✅ | Summary endpoint exists |
| Order acceptance/rejection | ❌ Missing | Auto-assigned, no rider consent |
| Delivery status updates (picked up, near store, delivered) | ❌ Missing | No delivery state machine |
| Help/support in-app | ❌ Missing | No support ticket system |
| Offline mode / poor connectivity handling | ❌ Missing | No queue mechanism |

---

### 4.3 Insurance & Compliance

**Status: ❌ Not Implemented**

| Requirement | Status | Regulatory Risk |
|---|---|---|
| Rider accident insurance | ❌ Missing | 🔴 Legal liability |
| Vehicle insurance tracking | ❌ Missing | 🔴 Legal requirement |
| License validity tracking | ❌ Missing — `license_number` stored but no expiry | 🔴 Critical |
| Vehicle registration document | ❌ Missing | 🟠 High |
| RC book expiry tracking | ❌ Missing | 🟠 High |
| Pollution certificate | ❌ Missing | 🟡 Medium |
| Age verification (18+) | ❌ Missing | 🔴 Legal requirement |
| Background verification records | ❌ Missing | 🟠 High |
| GDPR/data privacy compliance | ⚠️ No data deletion API | 🟠 High |

---

### 4.4 Break Management

**Status: ❌ Not Implemented**

| Requirement | Status | Notes |
|---|---|---|
| Mandatory breaks after X hours | ❌ Missing | Labor law requirement |
| Maximum shift duration (10-12 hours) | ❌ Missing | `RiderShift` exists but unused |
| Break request/approval workflow | ❌ Missing | — |
| Auto-offline enforcement after max hours | ❌ Missing | Safety critical |
| Break duration tracking | ❌ Missing | — |
| Meal break scheduling | ❌ Missing | Labor law in some jurisdictions |

**Critical Issue:** `RiderShift` entity and `RiderShiftRepository` exist but are **completely dead code**. No service, no controller, no scheduled job references them. Shift management is scaffolded but not implemented.

---

### 4.5 Rider Communication

**Status: ❌ Not Implemented**

| Feature | Status |
|---|---|
| Broadcast messages to all riders | ❌ Missing |
| Zone-specific announcements | ❌ Missing |
| Training notifications | ❌ Missing |
| Push notifications (FCM) | ❌ Missing |
| Support ticket system | ❌ Missing |
| In-app chat with support | ❌ Missing |
| SMS alerts for assignment | ❌ Missing |
| Rider feedback collection | ❌ Missing |

---

## 5. Q-Commerce Competitive Gap Analysis

### vs. Zepto (10-minute delivery)

| Zepto Feature | Instacommerce Status | Gap |
|---|---|---|
| Rider pre-positioned at dark store | ❌ Store-based but no pre-positioning logic | 🔴 Major |
| Auto-assignment BEFORE pack complete | ❌ Assignment only after `OrderPacked` event | 🔴 Major |
| Real-time rider tracking (customer-facing) | ❌ No customer-facing location API | 🔴 Major |
| Sub-5-min assignment | ⚠️ Depends on available riders | 🟠 |
| Rider pods (dedicated teams per store) | Partial ✅ (`store_id` on rider) | 🟡 Minor |
| Predictive rider demand forecasting | ❌ Missing | 🟠 High |

### vs. Blinkit (10-minute delivery)

| Blinkit Feature | Instacommerce Status | Gap |
|---|---|---|
| Rider pods per dark store | Partial ✅ (store_id) | 🟡 Minor |
| Batch delivery (2-3 orders one trip) | ❌ Missing | 🔴 Major |
| Photo proof of delivery | ❌ Missing | 🔴 Major |
| OTP-based delivery verification | ❌ Missing | 🔴 Major |
| Rider earning predictions ("earn ₹X in next 2 hours") | ❌ Missing | 🟠 |
| Dynamic rider allocation across stores | ❌ Missing | 🟠 High |
| Cash collection tracking | ❌ Missing | 🟠 High |

### vs. DoorDash

| DoorDash Feature | Instacommerce Status | Gap |
|---|---|---|
| Dasher app with earnings prediction | ❌ Missing | 🟠 |
| Flexible scheduling (work when you want) | ❌ Missing — `RiderShift` is dead code | 🟠 |
| Multi-app flexibility handling | ❌ Missing | 🟡 |
| Real-time order offer with ETA/pay | ❌ Missing — auto-assignment, no offer | 🔴 Major |
| Hotspot visualization (busy areas) | ❌ Missing | 🟡 |
| Catering/large order handling | ❌ Missing | 🟡 |
| Top Dasher program (rewards for high performers) | ❌ Missing | 🟡 |

### Key Q-Commerce Differentiators Missing

1. **Pre-assignment:** Zepto assigns riders as soon as an order is placed (before packing). Current system waits for `OrderPacked` — this adds 2-5 minutes to delivery time.
2. **Batch delivery:** Blinkit groups 2-3 nearby deliveries. Current system is strictly 1:1.
3. **Proof of delivery:** Both Zepto and Blinkit require photo/OTP proof. Current system has no delivery confirmation mechanism.
4. **Real-time tracking:** Customer-facing rider location is table-stakes for Q-commerce. Not available.
5. **Demand forecasting:** Zepto pre-positions riders based on predicted demand. No ML/forecasting here.

---

## 6. Database Schema Analysis

### 6.1 Migration Review

| Migration | Table | Analysis |
|---|---|---|
| V1 | `riders` | ✅ Solid. Good indexes on `(status, store_id)` and `phone`. Missing: composite index for assignment query. |
| V2 | `rider_shifts` | ⚠️ Dead — table exists, code doesn't use it. FK to riders ✅. |
| V3 | `rider_availability` | ⚠️ No spatial index. `UNIQUE(rider_id)` is correct (1:1). Index on `(is_available, store_id)` helps filtering. |
| V4 | `rider_earnings` | ⚠️ Missing unique constraint on `order_id` (or `rider_id, order_id`). Index on `(rider_id, earned_at)` is good. |
| V5 | `rider_ratings` | ✅ Good. `UNIQUE(order_id)`, `CHECK(1-5)`, index on `rider_id`. |
| V6 | `outbox_events` | ✅ Good. Partial index `WHERE sent = false` is efficient. |
| V7 | `shedlock` | ✅ Standard ShedLock table. |

### 6.2 Missing Tables

| Table | Purpose |
|---|---|
| `rider_documents` | Store document uploads (DL, RC, insurance, PAN) with expiry dates |
| `rider_assignments` | Assignment history with `order_id UNIQUE`, timing, status |
| `rider_incentive_rules` | Incentive program configuration |
| `rider_incentive_ledger` | Earned incentives tracking |
| `rider_breaks` | Break start/end/type tracking |
| `rider_communications` | Broadcast messages, notifications |
| `rider_zones` | Geographic zone definitions |
| `rider_penalties` | Warning/penalty records |

### 6.3 Schema Issues

1. **`rider_availability.current_lat/current_lng` as `DECIMAL(10,8)` / `DECIMAL(11,8)`:** Correct precision for lat/lng, but without PostGIS, every distance query is a full-table scan with trigonometric functions.

2. **`rider_earnings.order_id` lacks uniqueness:** Same delivery can be double-recorded. The entity has no `@Column(unique = true)` either.

3. **`riders.total_deliveries` denormalized counter:** Never incremented anywhere in the code. Should be updated when a delivery completes, or computed from `rider_earnings COUNT`.

4. **`rider_shifts` is dead code:** Schema allocated but never used.

5. **No `version` column for optimistic locking:** `riders` table should have `version BIGINT NOT NULL DEFAULT 0` for `@Version` support.

---

## 7. Infrastructure & Ops Review

### 7.1 Dockerfile

```dockerfile
FROM gradle:8.7-jdk21 AS build    # ✅ Multi-stage
FROM eclipse-temurin:21-jre-alpine # ✅ Minimal runtime
RUN addgroup -g 1001 app && adduser -u 1001 -G app -D app  # ✅ Non-root
ENV SERVER_PORT=8091
HEALTHCHECK --interval=30s --timeout=5s --retries=3  # ✅ Health check
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseZGC", ...]  # ✅ ZGC for low latency
```

**Assessment:** ✅ Well-configured. ZGC is good for latency-sensitive workloads. Non-root user. Health check on liveness endpoint.

**Minor Issue:** `RUN gradle clean build -x test` — tests are skipped in Docker build. This is fine if CI runs tests separately, but should be documented.

### 7.2 Application Configuration

**Connection Pool:**
```yaml
hikari:
  maximum-pool-size: 20
  minimum-idle: 5
  connection-timeout: 5000
  max-lifetime: 1800000
```
- Pool size 20 is low for 50K riders. At peak, 500+ concurrent requests fighting for 20 connections.
- **Recommendation:** 30-50 pool size, or use connection pooling proxy (PgBouncer).

**Kafka Configuration:**
```yaml
consumer:
  group-id: rider-fleet-service
  auto-offset-reset: earliest
```
- No `max.poll.records`, `session.timeout.ms`, or concurrency configured.
- Default concurrency = 1 consumer thread. For high-throughput order events, need 3-5+.
- `auto-offset-reset: earliest` could replay all historical events on new deployment.

**Cache:**
```yaml
caffeine:
  spec: maximumSize=1000,expireAfterWrite=60s
```
- Local cache only. In a multi-instance deployment, cache inconsistency is guaranteed.
- `maximumSize=1000` is small for 50K riders.
- **No cache is actually used in any `@Cacheable` annotation** — the cache config exists but is dead code.

### 7.3 Observability

| Feature | Status |
|---|---|
| Structured JSON logging (Logstash) | ✅ |
| OTLP tracing (distributed tracing) | ✅ |
| OTLP metrics export | ✅ |
| Prometheus metrics endpoint | ✅ |
| Health probes (liveness + readiness) | ✅ |
| Readiness includes DB check | ✅ |
| Custom business metrics | ❌ Missing |
| Assignment latency histogram | ❌ Missing |
| Rider online/offline counts gauge | ❌ Missing |
| Location update frequency monitor | ❌ Missing |
| Error rate by endpoint | ✅ (via Micrometer defaults) |

**Recommendation:** Add custom metrics:
```java
@Timed(value = "rider.assignment.duration", description = "Time to assign rider")
@Counted(value = "rider.assignment.total", description = "Total assignments")
```

---

## 8. Security Review

### 8.1 Authentication

- ✅ JWT with RSA public key verification
- ✅ Roles-based access control (`ADMIN`, `INTERNAL`)
- ✅ Stateless session (no cookies)
- ✅ Secret Manager for JWT key
- ✅ Structured error responses for auth failures
- ✅ `shouldNotFilter` correctly bypasses actuator endpoints

### 8.2 Authorization

```java
.requestMatchers("/admin/**").hasRole("ADMIN")
.requestMatchers(HttpMethod.POST, "/riders/assign").hasRole("INTERNAL")
.anyRequest().authenticated()
```

**Issues:**
1. `GET /riders/available?storeId=X` — any authenticated user can list available riders and their GPS coordinates. **Privacy/security concern.**
2. `POST /riders/{id}/availability` — any authenticated user can toggle any rider's availability. Should verify rider identity matches JWT subject.
3. `POST /riders/{id}/location` — any authenticated user can update any rider's location. **Critical security issue** — must verify rider owns this profile.
4. No rate limiting on any endpoint.
5. CORS allows all origins (`*`) — should be restricted to known domains.

### 8.3 Data Security

| Concern | Status |
|---|---|
| Phone number stored in plain text | ⚠️ Consider hashing for search, encrypting at rest |
| GPS coordinates in PostgreSQL | ⚠️ Sensitive PII — needs encryption at rest |
| No PII anonymization in logs | ⚠️ `logger.info("Created rider phone={}")` logs phone numbers |
| No data retention policy | ❌ Missing |
| No right-to-erasure (GDPR) | ❌ Missing |

---

## 9. Testing

### Status: ❌ ZERO Tests

**No test files exist.** The `src/test/java` directory is empty.

| Test Type | Status | Risk |
|---|---|---|
| Unit tests | ❌ None | 🔴 Critical |
| Integration tests | ❌ None | 🔴 Critical |
| Repository tests | ❌ None | 🔴 Critical |
| API/controller tests | ❌ None | 🔴 Critical |
| Kafka consumer tests | ❌ None | 🔴 Critical |
| Concurrency / locking tests | ❌ None | 🔴 Critical |

**Note:** Testcontainers for PostgreSQL is included in `build.gradle.kts` but unused.

**Recommendation:** At minimum:
1. `RiderAssignmentServiceTest` — concurrent assignment + idempotency
2. `RiderAvailabilityRepositoryTest` — spatial query correctness
3. `FulfillmentEventConsumerTest` — Kafka message processing
4. `RiderServiceTest` — state machine transitions
5. `SecurityConfigTest` — endpoint authorization

---

## 10. Prioritized Recommendations

### 🔴 P0 — Must Fix Before Production (Blocks launch)

| # | Item | Effort | Impact |
|---|---|---|---|
| 1 | **Add assignment idempotency** — prevent same order assigned to 2 riders | 2 days | Prevents data corruption |
| 2 | **Add PostGIS spatial index** — replace Haversine scan with GiST index | 3 days | 100x faster assignment at scale |
| 3 | **Add unit + integration tests** — at least for assignment, locking, consumer | 5 days | Confidence to deploy |
| 4 | **Implement rider identity verification on endpoints** — rider can only update own location/availability | 1 day | Critical security fix |
| 5 | **Add delivery-complete flow** — rider status back to ACTIVE, earnings recorded | 3 days | Core business flow missing |
| 6 | **Wire `recordEarning()`** — currently dead code, no trigger | 2 days | Riders won't see earnings |
| 7 | **Fix `getEarningsSummary()`** — use SQL aggregate, not in-memory sum | 0.5 days | Memory safety |

### 🟠 P1 — Required for 50K Scale (First 3 months)

| # | Item | Effort | Impact |
|---|---|---|---|
| 8 | **Redis for location storage** — PostgreSQL can't handle 10K writes/sec | 5 days | Infrastructure survival |
| 9 | **Multi-order batching** — assignment table, capacity model, batch logic | 10 days | 30% efficiency gain |
| 10 | **Rider onboarding workflow** — document upload, verification, training | 10 days | Regulatory compliance |
| 11 | **Shift management** — activate dead `RiderShift` code, enforce boundaries | 5 days | Operational control |
| 12 | **Auto-offline stale riders** — scheduled job for GPS timeout | 2 days | Assignment accuracy |
| 13 | **Rating system completion** — submission API, avg recalculation, thresholds | 5 days | Quality control |
| 14 | **Kafka consumer concurrency** — increase to 3-5 threads | 0.5 days | Throughput |
| 15 | **Assignment timeout + reassignment** — if rider doesn't accept in 60s, reassign | 5 days | Delivery SLA |
| 16 | **Delivery proof (OTP/photo)** — table-stakes for Q-commerce | 7 days | Customer trust |

### 🟡 P2 — Competitive Features (3-6 months)

| # | Item | Effort | Impact |
|---|---|---|---|
| 17 | **Incentive engine** — rules, evaluation, ledger | 10 days | Rider retention |
| 18 | **Pre-assignment** — assign before pack complete | 5 days | 2-5 min delivery time reduction |
| 19 | **Customer-facing rider tracking** — WebSocket location streaming | 7 days | Customer experience |
| 20 | **Break management** — labor law compliance | 5 days | Legal compliance |
| 21 | **Rider communication** — push notifications, broadcasts | 7 days | Operational efficiency |
| 22 | **Demand forecasting** — predict rider need per zone/hour | 15 days | Supply optimization |
| 23 | **Rider app features** — navigation, order details, earnings dashboard | 15 days | Rider experience |
| 24 | **Insurance & compliance tracking** — document expiry alerts | 7 days | Legal compliance |

---

## 11. Appendix — File-by-File Inventory

### Domain Models (7 files)
| File | Lines | Purpose | Status |
|---|---|---|---|
| `Rider.java` | 167 | Core rider entity | ⚠️ Missing fields (documents, bank, version) |
| `RiderStatus.java` | 9 | Status enum | ⚠️ Missing onboarding states |
| `VehicleType.java` | 8 | Vehicle enum | ✅ OK for MVP (BICYCLE, MOTORCYCLE, CAR) |
| `RiderShift.java` | 78 | Shift entity | ❌ Dead code — never used |
| `RiderRating.java` | 88 | Rating entity | ⚠️ Schema-only, no write path |
| `RiderEarning.java` | 100 | Earnings entity | ⚠️ `incentiveCents` never populated |
| `RiderAvailability.java` | 107 | Location + availability | ⚠️ Needs PostGIS column |
| `OutboxEvent.java` | 99 | Outbox pattern entity | ✅ Well-structured |

### Repositories (6 files)
| File | Purpose | Status |
|---|---|---|
| `RiderRepository.java` | Rider CRUD + store query | ✅ OK |
| `RiderAvailabilityRepository.java` | Spatial query + availability | ⚠️ Haversine without spatial index |
| `RiderShiftRepository.java` | Shift queries | ❌ Dead code |
| `RiderRatingRepository.java` | Rating queries | ⚠️ Never called |
| `RiderEarningRepository.java` | Earnings queries + aggregate | ⚠️ `sumTotalEarnings()` unused |
| `OutboxEventRepository.java` | Outbox CRUD | ⚠️ No `findBysentFalse()` for polling publisher |

### Services (5 files)
| File | Purpose | Status |
|---|---|---|
| `RiderAssignmentService.java` | Core assignment logic | ⚠️ No idempotency, no batching |
| `RiderService.java` | CRUD + lifecycle | ⚠️ Duplicate `onboard`/`activate` logic |
| `RiderAvailabilityService.java` | Location + toggle | ⚠️ No status validation |
| `RiderEarningsService.java` | Earnings recording + summary | ⚠️ Never triggered, memory-inefficient |
| `OutboxService.java` | Outbox event publishing | ✅ Correct `MANDATORY` propagation |

### Controllers (3 files)
| File | Purpose | Status |
|---|---|---|
| `AdminRiderController.java` | Admin CRUD | ✅ Properly secured with ADMIN role |
| `RiderController.java` | Rider self-service | ⚠️ No identity verification |
| `RiderAssignmentController.java` | Assignment API | ✅ Secured with INTERNAL role |

### Consumer (3 files)
| File | Purpose | Status |
|---|---|---|
| `FulfillmentEventConsumer.java` | Kafka consumer for `OrderPacked` | ⚠️ Swallows exceptions (catch-all with log.error) |
| `EventEnvelope.java` | Kafka message envelope | ✅ OK |
| `FulfillmentEventPayload.java` | Order packed event data | ✅ OK |

### Config (4 files)
| File | Purpose | Status |
|---|---|---|
| `RiderFleetProperties.java` | Custom config properties | ✅ OK |
| `SecurityConfig.java` | Spring Security setup | ⚠️ CORS allows `*` |
| `ShedLockConfig.java` | Distributed scheduler locking | ✅ OK (but no scheduled jobs exist) |
| `KafkaErrorConfig.java` | DLT error handling | ✅ Good — DLT + 3 retries with 1s backoff |

### Security (6 files)
| File | Purpose | Status |
|---|---|---|
| `JwtAuthenticationFilter.java` | JWT extraction + validation | ✅ Well-implemented |
| `JwtService.java` | Interface | ✅ OK |
| `DefaultJwtService.java` | RSA JWT validation | ✅ OK |
| `JwtKeyLoader.java` | PEM key parsing | ✅ Handles PEM + raw Base64 |
| `RestAuthenticationEntryPoint.java` | 401 handler | ✅ Structured JSON response |
| `RestAccessDeniedHandler.java` | 403 handler | ✅ Structured JSON response |

### Exception Handling (5 files)
| File | Purpose | Status |
|---|---|---|
| `GlobalExceptionHandler.java` | Centralized error handling | ✅ Comprehensive |
| `ApiException.java` | Base exception | ✅ OK |
| `RiderNotFoundException.java` | 404 | ✅ OK |
| `NoAvailableRiderException.java` | 503 | ✅ OK |
| `InvalidRiderStateException.java` | 409 | ✅ OK |
| `TraceIdProvider.java` | Trace ID extraction | ✅ Supports B3, W3C, custom headers |

### Migrations (7 files)
All reviewed in Section 6.

---

## Summary Scorecard

| Category | Score | Notes |
|---|---|---|
| **Assignment Algorithm** | 4/10 | Nearest-rider works, but no batching, load balancing, ETA, or pre-assignment |
| **Concurrency Safety** | 6/10 | `SKIP LOCKED` is good, missing idempotency is critical |
| **Rider Onboarding** | 2/10 | Bare CRUD, no verification workflow |
| **Earnings** | 3/10 | Schema exists, calculation logic missing, `recordEarning()` never called |
| **Rating System** | 1/10 | Schema only, no write path, no impact logic |
| **Availability** | 4/10 | Basic toggle + location, no staleness/shift/heartbeat |
| **Performance at Scale** | 3/10 | Haversine scan + PG for locations = bottleneck at 50K |
| **Security** | 6/10 | Good JWT setup, missing identity verification on rider endpoints |
| **Testing** | 0/10 | Zero tests |
| **Observability** | 7/10 | Good baseline (OTLP, Prometheus, JSON logs), missing business metrics |
| **Compliance** | 1/10 | No document tracking, breaks, insurance, data privacy |
| **Q-Commerce Readiness** | 2/10 | Far behind Zepto/Blinkit on core features |
| **Overall** | **3/10** | **MVP skeleton — needs 3-6 months of work for production readiness at 50K scale** |

---

*Review completed. This service has a solid architectural foundation (outbox pattern, JWT security, Kafka integration, proper error handling) but is critically lacking in business logic completeness, performance at scale, testing, and Q-commerce differentiating features.*
