# Warehouse Service — Deep Architecture Review

**Service:** `services/warehouse-service`
**Platform:** Instacommerce Q-Commerce (500+ dark stores)
**Stack:** Spring Boot 3 / Java 21 / PostgreSQL / Flyway / Caffeine cache
**Reviewed Files:** 41 Java files, 6 SQL migrations, application.yml, Dockerfile, build.gradle.kts, logback-spring.xml

---

## Executive Summary

The warehouse-service provides store CRUD, GPS-based nearest-store lookup (Haversine), pincode-to-store zone mapping, hourly capacity tracking, and operating-hours checks. It follows a solid outbox-pattern for event publishing and has proper JWT security.

However, it has **critical gaps** that will become blockers at 500+ dark-store scale: no spatial index (every nearest-store query is a full table scan), a schema/entity version-column mismatch that will crash on startup, no timezone handling for operating hours, no multi-store order routing, and zero test coverage.

**Overall Readiness: 🟡 Not production-ready for 500+ stores without the fixes below.**

---

## Table of Contents

1. [CRITICAL — Must Fix Before Production](#1-critical--must-fix-before-production)
2. [Store Model](#2-store-model)
3. [Zone Mapping](#3-zone-mapping)
4. [Operating Hours](#4-operating-hours)
5. [Capacity Management](#5-capacity-management)
6. [Store Onboarding](#6-store-onboarding)
7. [Store Health Monitoring](#7-store-health-monitoring)
8. [Geo Queries](#8-geo-queries)
9. [SLA & Performance](#9-sla--performance)
10. [Caching](#10-caching)
11. [Missing Features](#11-missing-features)
12. [Security Review](#12-security-review)
13. [Data Integrity & Schema](#13-data-integrity--schema)
14. [Observability](#14-observability)
15. [Testing](#15-testing)
16. [Recommendations Summary](#16-recommendations-summary)

---

## 1. CRITICAL — Must Fix Before Production

### 1.1 🔴 `@Version` Column Missing from Migration — Application Will Not Start

**Files:** `Store.java:64`, `V1__create_stores.sql`

The JPA entity declares `@Version private long version;` for optimistic locking, but the `V1__create_stores.sql` migration **does not create a `version` column**. With `ddl-auto: validate`, Hibernate will throw `SchemaManagementException` on startup:

```
Schema-validation: missing column [version] in table [stores]
```

**Fix:** Add a new Flyway migration:
```sql
-- V7__add_stores_version_column.sql
ALTER TABLE stores ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

### 1.2 🔴 Nearest-Store Query Does Full Table Scan (Haversine Without Spatial Index)

**File:** `StoreRepository.java:17-32`

The `findNearestStores` query computes Haversine distance for **every active store** before filtering. At 500+ stores this means:
- 500+ trigonometric calculations per request
- No index can accelerate `acos(cos(...))` expressions
- The composite B-tree index `idx_stores_lat_lng` on `(latitude, longitude)` is **useless** for this query pattern

At current scale (500 stores) latency will be ~5-20ms. At 5,000+ stores it becomes a real problem.

**Fix (Immediate):** Add a bounding-box pre-filter to the query:
```sql
WHERE s.status = 'ACTIVE'
  AND s.latitude BETWEEN :lat - (:radiusKm / 111.0) AND :lat + (:radiusKm / 111.0)
  AND s.longitude BETWEEN :lng - (:radiusKm / (111.0 * cos(radians(:lat))))
                       AND :lng + (:radiusKm / (111.0 * cos(radians(:lat))))
```
This leverages the existing `idx_stores_lat_lng` B-tree index to narrow candidates before Haversine.

**Fix (Long-term):** Enable PostGIS and use `ST_DWithin` with a `GIST` spatial index for O(log n) lookups.

### 1.3 🔴 Capacity Increment Has Race Condition

**File:** `CapacityService.java:63-84`

The `incrementOrderCount` method has a TOCTOU (time-of-check/time-of-use) race:
1. Thread A calls `findByStoreIdAndDateAndHour` → empty
2. Thread B calls `findByStoreIdAndDateAndHour` → empty
3. Both threads create a new `StoreCapacity` row with `currentOrders=1`
4. The unique constraint `uq_store_date_hour` causes one to fail with `DataIntegrityViolationException`

**Fix:** Use `INSERT ... ON CONFLICT` (upsert) via a native query:
```sql
INSERT INTO store_capacity (id, store_id, date, hour, current_orders, max_orders, created_at, updated_at)
VALUES (gen_random_uuid(), :storeId, :date, :hour, 1, :maxOrders, now(), now())
ON CONFLICT (store_id, date, hour)
DO UPDATE SET current_orders = store_capacity.current_orders + 1, updated_at = now()
WHERE store_capacity.current_orders < store_capacity.max_orders
```

---

## 2. Store Model

**Files:** `Store.java`, `V1__create_stores.sql`, `CreateStoreRequest.java`, `StoreResponse.java`

### What's Present
| Attribute | Present | Notes |
|---|---|---|
| id (UUID) | ✅ | Auto-generated |
| name | ✅ | VARCHAR(255), validated `@NotBlank` |
| address | ✅ | TEXT, validated `@NotBlank` |
| city | ✅ | VARCHAR(100) |
| state | ✅ | VARCHAR(100) |
| pincode | ✅ | VARCHAR(10), validated min=5 max=10 |
| latitude/longitude | ✅ | DECIMAL(10,8) / DECIMAL(11,8) — correct precision |
| status | ✅ | Enum: ACTIVE, INACTIVE, MAINTENANCE, TEMPORARILY_CLOSED |
| capacity_orders_per_hour | ✅ | Default 100 |
| created_at / updated_at | ✅ | TIMESTAMPTZ, auto-managed |
| version (optimistic lock) | ⚠️ | In entity but **missing from migration** (see §1.1) |

### What's Missing

| Attribute | Severity | Rationale |
|---|---|---|
| `store_type` | 🔴 High | No way to distinguish dark_store vs hub vs micro_warehouse. Critical for hub-and-spoke routing, inventory transfer rules, and capacity planning. Different store types have different SLAs. |
| `phone` / `email` | 🟡 Medium | No contact info for store operations. Required for escalation during order failures. |
| `manager_name` / `manager_id` | 🟡 Medium | No store manager assignment. Needed for operational accountability and push notifications. |
| `country` | 🟡 Medium | Assumes India-only. Blocks multi-country expansion. |
| `timezone` | 🔴 High | Operating hours are stored as `LocalTime` but no timezone on the store. A store in Kolkata (IST) and one in London (GMT) would be evaluated identically. See §4. |
| `operational_since` | 🟢 Low | When the store went live — useful for analytics. |
| `square_footage` / `storage_capacity` | 🟢 Low | Physical capacity limits for inventory planning. |
| `is_active` (soft delete) | 🟡 Medium | `deleteStore()` does a hard `DELETE`. At 500+ stores, historical data and audit trail is lost. |

### Recommendation
Add `store_type`, `timezone`, and `phone` columns in the next migration. Switch to soft-delete by using `status = 'DECOMMISSIONED'` instead of hard `DELETE`.

---

## 3. Zone Mapping

**Files:** `StoreZone.java`, `V2__create_store_zones.sql`, `ZoneService.java`, `StoreZoneRepository.java`

### Current Design
- **Pincode-based mapping:** Each zone links a store to a pincode with a `delivery_radius_km`.
- **Unique constraint:** `(store_id, pincode)` — one store can serve one pincode only once.
- **Lookup:** `findActiveZonesByPincode` fetches all active stores for a given pincode.
- **Caching:** Pincode → store IDs cached in Caffeine (5 min TTL).

### Gaps

| Issue | Severity | Detail |
|---|---|---|
| **No GPS-based zone assignment** | 🔴 High | Customer sends lat/lng, but zone lookup is pincode-only. The `findNearestStores` endpoint uses GPS, but `mapPincodeToStoreIds` does not. These two strategies can return conflicting results — a customer at a pincode boundary may get different stores depending on which endpoint the order-service calls. |
| **No overlapping zone resolution** | 🔴 High | Multiple stores can serve the same pincode (no priority/weight). When two dark stores share pincode 560001, there's no strategy to pick the better one (nearest, least loaded, highest performance score). |
| **`delivery_radius_km` is unused** | 🟡 Medium | The field exists on `StoreZone` but is **never used in any query or logic**. The Haversine query in `StoreRepository` uses its own `radiusKm` parameter independently. This is dead data. |
| **No polygon/geofence support** | 🟡 Medium | Pincode boundaries are irregular. A store at the edge of a pincode may be far from customers in that pincode. Polygon-based zones (GeoJSON) would be more accurate. |
| **No zone versioning** | 🟢 Low | Zone changes take effect immediately with no history. For auditing zone coverage changes over time. |

### Recommendation
Unify the two lookup strategies (pincode and GPS) into a single flow: use GPS-based nearest store as the primary strategy, with pincode as a fallback. When multiple stores match, apply a scoring function (distance × capacity × performance).

---

## 4. Operating Hours

**Files:** `StoreHours.java`, `V3__create_operating_hours.sql`, `StoreService.java:77-94`

### Current Design
- Weekly schedule: `day_of_week` (0-6) with `opens_at` / `closes_at` as `LocalTime`.
- `is_holiday` boolean flag per day.
- `isStoreOpen()` checks status, finds hours for current day, checks holiday flag, compares current time.

### Gaps

| Issue | Severity | Detail |
|---|---|---|
| **No timezone handling** | 🔴 Critical | `isStoreOpen(UUID, LocalDateTime)` uses `LocalDateTime.now()` (server timezone) in the controller. If the server is in UTC and the store is in IST (UTC+5:30), a store that should be open at 9 AM IST would be checked at 9 AM UTC = 2:30 PM IST. **Every time comparison is wrong for any store not in the server's timezone.** |
| **No holiday calendar** | 🔴 High | `is_holiday` is per day-of-week, not per date. Cannot model "Dec 25 is a holiday" or "Diwali week." A Monday can't be a holiday on one specific date but open on other Mondays. |
| **No emergency closure** | 🟡 Medium | The `TEMPORARILY_CLOSED` status exists but there's no scheduled auto-reopen. An emergency closure requires manual status change back to ACTIVE, which is error-prone at 500+ stores. |
| **No break hours** | 🟡 Medium | One `opens_at/closes_at` pair per day. Cannot model lunch breaks or split shifts (e.g., 9AM-1PM, 3PM-10PM). |
| **Overnight hours broken** | 🔴 High | If a store operates 10PM to 6AM (overnight), `closes_at < opens_at`. The current check `!currentTime.isBefore(opensAt) && !currentTime.isAfter(closesAt)` will **always return false** for overnight windows. |
| **Day-of-week mapping inconsistency** | 🟡 Medium | `StoreService` uses `now.getDayOfWeek().getValue() % 7` which maps Monday=1→1, Sunday=7→0. But the migration says `CHECK (day_of_week BETWEEN 0 AND 6)` with no documentation on which day is 0. If seed data uses Sunday=0, it matches. If it uses Monday=0, it doesn't. This is a silent data bug. |

### Recommendation
1. Add `timezone VARCHAR(50)` to `stores` table (e.g., `Asia/Kolkata`).
2. Convert `isStoreOpen()` to use `ZonedDateTime`:
   ```java
   ZonedDateTime storeNow = ZonedDateTime.now(ZoneId.of(store.getTimezone()));
   ```
3. Create a `store_holidays` table: `(store_id, date, reason)` for date-specific closures.
4. Handle overnight hours: if `closesAt < opensAt`, treat as crossing midnight.

---

## 5. Capacity Management

**Files:** `StoreCapacity.java`, `V4__create_store_capacity.sql`, `CapacityService.java`, `StoreCapacityRepository.java`

### Current Design
- Hourly capacity tracking: `(store_id, date, hour)` → `current_orders / max_orders`.
- `canAcceptOrder()` checks if `currentOrders < maxOrders`.
- `incrementOrderCount()` atomically increments via `UPDATE ... WHERE current_orders < max_orders`.
- Nightly cleanup deletes records older than 7 days.
- `max_orders` is copied from `store.capacityOrdersPerHour` at first order of each hour.

### Gaps

| Issue | Severity | Detail |
|---|---|---|
| **Race condition on first order** | 🔴 Critical | See §1.3. Two concurrent first-orders-of-the-hour will both try to INSERT, one fails with unique constraint violation. |
| **No capacity reservation** | 🟡 Medium | `canAcceptOrder` and `incrementOrderCount` are separate calls. Between check and increment, capacity could fill up. Should be a single atomic operation returning success/failure. |
| **Static capacity** | 🟡 Medium | `max_orders` is fixed per store. No mechanism to adjust based on: current staffing levels, picker availability, real-time order complexity, or time-of-day demand patterns. |
| **No capacity forecasting** | 🟢 Low | No historical analysis to pre-warm capacity or predict peak hours. |
| **Timezone issue** | 🔴 High | `CapacityService.getStoreCapacity(id, LocalDateTime.now())` — same timezone problem as operating hours. The "hour" bucket is server-local, not store-local. |
| **No overflow handling** | 🟡 Medium | When a store hits capacity, the service returns `false` but doesn't redirect to the next-nearest store. The calling service must implement fallback logic with no guidance from warehouse-service. |
| **`CapacityExceededException` is never thrown** | 🟢 Low | The exception class exists but is never used in any code path. `incrementOrderCount` returns `false` instead of throwing. Inconsistent error strategy. |

### Recommendation
Replace the check-then-insert pattern with a single upsert query. Expose a `reserveCapacity(storeId)` endpoint that atomically checks + increments and returns the updated capacity state, or throws `CapacityExceededException`.

---

## 6. Store Onboarding

**Files:** `AdminStoreController.java`, `StoreService.java:96-117`, `CreateStoreRequest.java`

### Current Workflow
1. Admin sends `POST /admin/stores` with name, address, city, state, pincode, lat/lng, capacity.
2. Store is created with `ACTIVE` status immediately.
3. Outbox event `StoreCreated` is published.

### Gaps

| Issue | Severity | Detail |
|---|---|---|
| **No onboarding workflow** | 🔴 High | Store goes directly to `ACTIVE`. No `PENDING_SETUP` → `READY_FOR_REVIEW` → `ACTIVE` lifecycle. A store created without zones or operating hours is immediately "active" and could receive orders it can't fulfill. |
| **No mandatory data validation** | 🔴 High | A store can be created without any `StoreZone` or `StoreHours`. It will never be found via pincode lookup and will always return "closed" from `isStoreOpen()`. Silent failure. |
| **No zone creation in store setup** | 🟡 Medium | No API endpoint exists to create/update zones. The `store_zones` table can only be populated via direct database insertion. |
| **No hours creation in store setup** | 🟡 Medium | Same issue — no API to create/update operating hours. |
| **No bulk onboarding** | 🟢 Low | At 500+ stores, one-at-a-time creation via API is impractical. Need CSV/batch import. |
| **No coordinate validation** | 🟡 Medium | Lat/lng are validated as ranges but not checked against the provided city/state. A typo could place a Mumbai store in the ocean. |
| **Hard delete with no soft-delete** | 🟡 Medium | `DELETE /admin/stores/{id}` performs `storeRepository.delete(store)` — cascading delete removes all zones, hours, and capacity data permanently. No audit trail. |

### Recommendation
1. Add `PENDING_SETUP` to `StoreStatus` enum.
2. Create stores in `PENDING_SETUP` status.
3. Add endpoints for zone and hours management: `POST /admin/stores/{id}/zones`, `PUT /admin/stores/{id}/hours`.
4. Add an "activate store" endpoint that validates zones and hours exist before transitioning to `ACTIVE`.

---

## 7. Store Health Monitoring

### Current State: **Nothing implemented.**

There are no store-level metrics, health scores, or performance tracking anywhere in the codebase.

### What's Needed

| Metric | Priority | Purpose |
|---|---|---|
| **Pick rate** (items/hour) | 🔴 High | Determines if a store can handle complex orders |
| **Fulfillment rate** (%) | 🔴 High | Orders successfully completed vs accepted |
| **Average preparation time** | 🔴 High | Affects ETA shown to customer |
| **Cancellation rate** | 🟡 Medium | Stores with high cancellation should get fewer orders |
| **Item availability score** | 🔴 High | If a store frequently has out-of-stock items, route elsewhere |
| **Rider wait time** | 🟡 Medium | How long riders wait for orders at this store |

### Recommendation
Create a `store_metrics` table or connect to an analytics pipeline. Expose `GET /stores/{id}/health` endpoint. Use these metrics for store scoring in routing decisions (see §11.5).

---

## 8. Geo Queries

**File:** `StoreRepository.java:17-32`

### Haversine Implementation Analysis

```sql
6371 * acos(
    LEAST(1.0, cos(radians(:lat)) * cos(radians(s.latitude))
    * cos(radians(s.longitude) - radians(:lng))
    + sin(radians(:lat)) * sin(radians(s.latitude)))
)
```

| Aspect | Assessment |
|---|---|
| **Formula correctness** | ✅ Correct Haversine with `LEAST(1.0, ...)` to prevent `acos` domain errors from floating-point rounding |
| **Earth radius** | ✅ 6371 km (mean radius) — acceptable |
| **Accuracy** | ⚠️ Haversine assumes spherical Earth. Error up to 0.3% vs Vincenty/WGS-84. For Q-commerce (<20km), this is acceptable. |
| **Performance** | 🔴 Full table scan. Every active store row computes 6 trigonometric functions. No index can help. |
| **Result limiting** | ✅ `LIMIT :maxResults` (default 5) prevents excessive result sets |
| **Status filter** | ✅ Only `ACTIVE` stores |

### Performance Projection

| Store Count | Estimated Query Time | Acceptable? |
|---|---|---|
| 100 | ~2ms | ✅ |
| 500 | ~5-10ms | ⚠️ Borderline |
| 5,000 | ~50-100ms | 🔴 No |
| 50,000 | ~500ms+ | 🔴 No |

### Recommendation
1. **Immediate:** Add bounding-box pre-filter (see §1.2) to reduce candidate rows by ~95%.
2. **Medium-term:** Install PostGIS, add `geography` column, create GIST index. Query becomes:
   ```sql
   SELECT * FROM stores
   WHERE ST_DWithin(geog, ST_MakePoint(:lng, :lat)::geography, :radiusMeters)
   ORDER BY ST_Distance(geog, ST_MakePoint(:lng, :lat)::geography)
   LIMIT :maxResults
   ```
   This is O(log n) with the spatial index.

---

## 9. SLA & Performance

### Store Lookup Latency

Every customer order triggers a nearest-store lookup. This is on the critical path.

| Metric | Current | Target |
|---|---|---|
| Nearest-store lookup (500 stores) | ~5-10ms (DB) + cache miss | < 5ms p99 |
| Store-by-ID | ~1ms (cached) | ✅ |
| Pincode-to-store | ~2ms (cached) | ✅ |
| Capacity check | ~2ms (DB, not cached) | Should cache |

**Concerns:**
- The nearest-store query is **never cached**. Every call hits the DB because lat/lng coordinates vary per customer. This is correct — you can't cache arbitrary GPS queries easily. But it means the query itself must be fast.
- Capacity check is not cached and is called per-order. At peak (e.g., 10K orders/minute across 500 stores), this is 10K queries/minute hitting `store_capacity`.
- No connection pooling configuration for peak — HikariCP max pool is 20 connections, which may be insufficient.

### Recommendation
- Add bounding-box pre-filter for nearest-store (§1.2).
- Consider caching capacity per store for 10-30 seconds (acceptable staleness for capacity checks).
- Load-test HikariCP at 20 connections with expected peak queries. Consider increasing to 30-40 for production.

---

## 10. Caching

**File:** `CacheConfig.java`, `StoreService.java`, `ZoneService.java`

### Current Configuration

| Cache | Key | TTL | Max Size | Used In |
|---|---|---|---|---|
| `stores` | store UUID | 5 min | 2,000 | `StoreService.getStore()` |
| `store-zones` | pincode | 5 min | 2,000 | `ZoneService.mapPincodeToStoreIds()` |
| `store-hours` | — | 5 min | 2,000 | **Declared but never used** |

### Issues

| Issue | Severity | Detail |
|---|---|---|
| **`store-hours` cache declared but unused** | 🟡 Medium | `CacheConfig` creates it, `application.yml` lists it, but no `@Cacheable` annotation references it. `isStoreOpen()` hits the DB every time. At peak, this is called for every order. |
| **Nearest-store results not cached** | ⚠️ Expected | GPS queries can't be key-cached trivially. But could cache active store list and compute in-memory. |
| **No cache eviction on zone changes** | 🟡 Medium | There's no zone management API, but if zones are changed via DB, the `store-zones` cache will serve stale data for up to 5 minutes. |
| **`StoreService.findByCity()` not cached** | 🟢 Low | City-based lookups hit DB every time. Low priority since it's less frequent. |
| **Cache stats not exposed** | 🟡 Medium | `recordStats()` is called on Caffeine but no actuator endpoint exposes cache hit/miss rates. |
| **Duplicate cache config** | 🟢 Low | Cache is configured in both `CacheConfig.java` (Caffeine builder) and `application.yml` (`spring.cache.caffeine.spec`). The Java config takes precedence, making the YAML config dead. |

### Recommendation
1. Add `@Cacheable("store-hours")` to the `isStoreOpen` path or the `StoreHoursRepository.findByStoreIdAndDayOfWeek` call.
2. For nearest-store at scale: cache all active stores in-memory and compute Haversine in Java. With 500 stores × ~200 bytes = ~100KB, this trivially fits in memory. Refresh every 60 seconds.
3. Expose Caffeine metrics via Micrometer (already using `recordStats()`; just need to wire to actuator).

---

## 11. Missing Features

### 11.1 Multi-Store Routing

**Status: Not implemented.**

When the nearest store lacks items or is at capacity, there's no fallback logic. The API returns a sorted list of nearest stores, but:
- No inventory awareness (warehouse-service doesn't know what items a store has)
- No automatic fallback — the calling service must iterate and check each store
- No split-order support (partial fulfillment from multiple stores)

**Recommendation:** Expose a `POST /stores/route` endpoint:
```json
{
  "customerLat": 12.97,
  "customerLng": 77.59,
  "items": ["sku-001", "sku-002"],
  "radiusKm": 10
}
```
Returns ranked stores with availability + capacity status. Requires integration with inventory-service.

### 11.2 Store Clusters (Hub-and-Spoke)

**Status: Not implemented. No `store_type` field exists.**

For metro areas with 50+ dark stores, a hub-and-spoke model is essential:
- **Hub:** Large warehouse for long-tail SKUs, inventory replenishment
- **Spoke (Dark Store):** Customer-facing, limited SKU set, fast picking
- **Micro-Warehouse:** Smallest footprint, top 200 SKUs

Without `store_type` and `parent_store_id` (for hub → spoke relationships), this model cannot be implemented.

### 11.3 Delivery Zone Visualization

**Status: Not implemented.**

No admin UI/API to:
- View zones on a map
- Draw/edit polygon-based zones
- See zone overlaps
- View coverage gaps

This is critical for operations teams managing 500+ stores.

### 11.4 Dynamic Capacity

**Status: Not implemented.**

Current capacity is static (`capacity_orders_per_hour` on the store). No mechanism for:
- Adjusting based on current staff count
- Peak hour multipliers
- Weather-based adjustments (rain = more orders, fewer riders)
- Gradual ramp-up for new stores

### 11.5 Store Scoring

**Status: Not implemented.**

No composite score to rank stores for routing. A scoring model should consider:
```
score = w1 * (1/distance) + w2 * fulfillment_rate + w3 * (1 - capacity_utilization) + w4 * item_availability
```

---

## 12. Security Review

**Files:** `SecurityConfig.java`, `JwtAuthenticationFilter.java`, `DefaultJwtService.java`, `JwtKeyLoader.java`

### Positive Findings
- ✅ Stateless JWT authentication (no sessions)
- ✅ RSA public-key verification (asymmetric — service cannot forge tokens)
- ✅ CSRF disabled (correct for stateless API)
- ✅ Role-based access: `/admin/**` requires `ROLE_ADMIN`
- ✅ Actuator and health endpoints are public (needed for k8s probes)
- ✅ Structured error responses on auth failure (no stack traces leaked)
- ✅ Non-root user in Dockerfile (`app:1001`)

### Concerns

| Issue | Severity | Detail |
|---|---|---|
| **CORS allows all origins** | 🟡 Medium | `setAllowedOriginPatterns(List.of("*"))` with `setAllowCredentials(true)` is overly permissive. In production, restrict to known frontend domains. |
| **Public endpoints may be too broad** | 🟡 Medium | `GET /stores/nearest` and `GET /stores/{id}` are unauthenticated. This allows anyone to enumerate all stores and their locations. Consider requiring at least an API key for external clients. |
| **Several GET endpoints require auth but aren't admin** | 🟢 Low | `/stores/{id}/capacity`, `/stores/by-pincode`, `/stores/by-city`, `/stores/{id}/zones`, `/stores/{id}/open` all require authentication but not ADMIN role. This seems intentional (internal service-to-service calls). |
| **No rate limiting** | 🟡 Medium | No request rate limiting. The nearest-store endpoint is computationally expensive and could be abused. |
| **Secret Manager key names are hardcoded** | 🟢 Low | `sm://db-password-warehouse` and `sm://jwt-rsa-public-key` — acceptable for GCP Secret Manager but should be documented. |

---

## 13. Data Integrity & Schema

### Migration Review

| Migration | Assessment |
|---|---|
| V1 — stores | ✅ Good indexes. 🔴 Missing `version` column. Missing `store_type`, `timezone`. |
| V2 — store_zones | ✅ Good unique constraint `(store_id, pincode)`. ✅ Index on pincode. |
| V3 — store_hours | ✅ Good unique constraint `(store_id, day_of_week)`. ✅ CHECK constraint on day_of_week. |
| V4 — store_capacity | ✅ Good unique constraint `(store_id, date, hour)`. ✅ CHECK on hour range. |
| V5 — outbox_events | ✅ Partial index on unsent events. |
| V6 — shedlock | ✅ Standard shedlock schema. |

### Entity/Schema Mismatches

| Entity Field | DB Column | Issue |
|---|---|---|
| `Store.version` (long) | **Missing** | 🔴 Will crash. Needs V7 migration. |
| `StoreZone.deliveryRadiusKm` | `delivery_radius_km` | ⚠️ Column exists but value is never read by any query. Dead data. |

### Data Model Concerns

1. **No audit columns:** No `created_by`, `updated_by`. At 500+ stores with multiple admins, cannot trace who made changes.
2. **No soft delete:** Hard `DELETE` on stores cascades to zones, hours, and capacity. Data is lost forever.
3. **Outbox event consumer missing:** Events are written to `outbox_events` but there's no CDC connector or poller to actually send them. The `sent` flag is never set to `true` except presumably by an external process. This should be documented.

---

## 14. Observability

### What's Present
- ✅ Structured JSON logging (Logstash encoder)
- ✅ OpenTelemetry tracing (OTLP exporter)
- ✅ Prometheus metrics endpoint
- ✅ Spring Boot Actuator (health, info, metrics, prometheus)
- ✅ Liveness/readiness probes configured
- ✅ Trace ID propagation in error responses (X-B3-TraceId, traceparent, X-Request-Id)
- ✅ Graceful shutdown (30s timeout)

### What's Missing
- ❌ **No custom business metrics.** No counters for: stores created, orders routed, capacity breaches, zone lookups, cache hit/miss.
- ❌ **No latency histograms** for the nearest-store query (the most critical operation).
- ❌ **No alerting annotations** or SLO definitions.
- ❌ **Caffeine cache stats** are recorded (`recordStats()`) but not wired to Micrometer — metrics won't appear in Prometheus.

### Recommendation
Add Micrometer `@Timed` annotations to key service methods. Wire Caffeine stats to actuator via `CaffeineCacheMetrics`.

---

## 15. Testing

### Current State: **Zero test coverage.**

The `src/test/java/com/` directory tree exists but contains **no test files**. The `build.gradle.kts` includes test dependencies (JUnit Jupiter, Testcontainers PostgreSQL, Spring Security Test) but no tests are written.

### Impact at 500+ Stores
- No regression safety net for Haversine query changes
- No validation that capacity race conditions are handled
- No integration tests for the JWT security filter chain
- No contract tests for the API responses consumed by order-service

### Minimum Required Test Coverage

| Test | Priority | Type |
|---|---|---|
| Nearest-store Haversine accuracy | 🔴 P0 | Integration (Testcontainers) |
| Capacity increment race condition | 🔴 P0 | Concurrent integration test |
| Store CRUD + outbox event | 🔴 P0 | Integration |
| isStoreOpen with edge cases | 🟡 P1 | Unit test |
| JWT auth filter chain | 🟡 P1 | Integration |
| Zone pincode lookup | 🟡 P1 | Integration |
| Overnight operating hours | 🟡 P1 | Unit test |
| CreateStoreRequest validation | 🟢 P2 | Unit test |

---

## 16. Recommendations Summary

### 🔴 P0 — Must Fix (Blocks Production)

| # | Issue | Effort | Files |
|---|---|---|---|
| 1 | Add `version` column migration | 30 min | New V7 migration |
| 2 | Add bounding-box pre-filter to nearest-store query | 2 hrs | `StoreRepository.java` |
| 3 | Fix capacity increment race condition (upsert) | 2 hrs | `CapacityService.java`, `StoreCapacityRepository.java` |
| 4 | Add `timezone` to stores + fix `isStoreOpen()` | 4 hrs | Migration, `Store.java`, `StoreService.java` |
| 5 | Fix overnight operating hours logic | 1 hr | `StoreService.java` |
| 6 | Write core integration tests | 2 days | New test files |

### 🟡 P1 — Should Fix (Next Sprint)

| # | Issue | Effort |
|---|---|---|
| 7 | Add `store_type` column (dark_store/hub/micro_warehouse) | 2 hrs |
| 8 | Add zone and hours management APIs | 1 day |
| 9 | Store onboarding workflow (PENDING_SETUP → ACTIVE) | 4 hrs |
| 10 | Add holiday calendar table | 4 hrs |
| 11 | Wire Caffeine cache stats to Micrometer | 1 hr |
| 12 | Add `@Cacheable` for store hours | 30 min |
| 13 | Restrict CORS origins for production | 30 min |
| 14 | Soft delete instead of hard delete | 2 hrs |

### 🟢 P2 — Nice to Have (Backlog)

| # | Issue | Effort |
|---|---|---|
| 15 | PostGIS spatial index migration | 1 day |
| 16 | Multi-store routing with inventory awareness | 3 days |
| 17 | Store health metrics + scoring | 1 week |
| 18 | Hub-and-spoke cluster model | 1 week |
| 19 | Dynamic capacity based on staffing | 3 days |
| 20 | Delivery zone polygon support (GeoJSON) | 1 week |
| 21 | Store onboarding bulk import | 2 days |

---

*Review generated from full codebase analysis of 41 source files, 6 migrations, and all configuration.*
