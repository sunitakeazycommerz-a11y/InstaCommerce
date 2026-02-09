# Catalog-Service & Inventory-Service — Production Review Findings

**Reviewer**: Staff Engineer Review  
**Scope**: All files under `services/catalog-service/` and `services/inventory-service/`  
**Reference docs**: `docs/02-catalog-search-pricing.md`, `docs/03-inventory-cart.md`, `docs/09-contracts-events-protobuf.md`  
**Platform context**: 20M+ users, Q-commerce (Zepto/Blinkit scale)

---

## Table of Contents

1. [Search & Discovery](#1-search--discovery)
2. [Inventory Management](#2-inventory-management)
3. [Scalability](#3-scalability)
4. [Data Model](#4-data-model)
5. [Concurrency](#5-concurrency)
6. [Event Contracts](#6-event-contracts)
7. [Performance](#7-performance)
8. [Missing Features](#8-missing-features)
9. [Additional Findings](#9-additional-findings)
10. [Summary Matrix](#10-summary-matrix)

---

## 1. Search & Discovery

### FINDING-S1: No Autocomplete / Prefix Search — Severity: HIGH

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/repository/SearchRepository.java` (L19-27)

`plainto_tsquery('english', :query)` only supports full-word matching. It will not match partial input like "mil" when the user is typing "milk". For a Q-commerce app where 70%+ of traffic starts with search, this is a critical UX gap.

**Impact**: Users must type full words to get results; no typeahead suggestions.  
**Recommendation**: Add a prefix-search endpoint using `to_tsquery` with `:*` suffix for the last term, or add a `pg_trgm`-based trigram index for fuzzy/prefix matching. Plan migration to OpenSearch for production-grade autocomplete.

### FINDING-S2: No Faceted Search — Severity: MEDIUM

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/controller/SearchController.java` (L25-31)

The search API only filters by `category` slug. There are no facets for brand, price range, unit, or weight. At scale, customers expect to narrow results by multiple dimensions.

**Impact**: Reduced discoverability on large catalogs (10K+ SKUs).  
**Recommendation**: Add brand filter, price-range filter, and return facet counts in the response. These can be implemented as SQL `GROUP BY` aggregations in the near term, or as OpenSearch aggregations in the future.

### FINDING-S3: Search SQL Injection Risk via Native Query — Severity: LOW

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/repository/SearchRepository.java` (L19-34)

The query uses named parameters (`:query`, `:categorySlug`) via JPA `Query.setParameter()`, which provides proper parameterization. This is correctly implemented and safe from SQL injection. However, the native query string is not sanitized for tsvector special characters (`&`, `|`, `!`, `:`). If a user sends `milk | poison`, `plainto_tsquery` handles this safely (it strips operators), so this is LOW risk.

### FINDING-S4: No Search Results Caching — Severity: MEDIUM

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/service/SearchService.java` (L24-38)

Every search query hits PostgreSQL directly. For identical popular queries ("milk", "bread"), this causes unnecessary database load. The doc mentions Caffeine cache for products but search results have no caching layer.

**Impact**: Under 20M users, popular search terms will hammer the DB.  
**Recommendation**: Add a short-TTL (30-60s) Caffeine cache keyed by `(query, categorySlug, page, size)` for search results.

### FINDING-S5: No OpenSearch/Elasticsearch Integration — Severity: MEDIUM

**Files**: `catalog-service/build.gradle.kts`, entire codebase

The docs note "PostgreSQL tsvector (MVP) → OpenSearch (future)" but there is zero groundwork for this migration. No search abstraction layer, no interface separating the search implementation from the service layer. `SearchService` directly calls `SearchRepository` which is tightly coupled to PostgreSQL native queries.

**Recommendation**: Extract a `SearchProvider` interface with `PostgresSearchProvider` as the current implementation to facilitate future OpenSearch migration.

---

## 2. Inventory Management

### FINDING-I1: Reservation Expiry Job Has No Distributed Lock — Severity: CRITICAL

**File**: `inventory-service/src/main/java/com/instacommerce/inventory/service/ReservationExpiryJob.java` (L22-29)

The `@Scheduled(fixedRateString=...)` expiry job runs on every instance of the service. With `replicaCount: 2` (and HPA up to 8), multiple pods will concurrently attempt to expire the same reservations. While each individual `expireReservation()` call is transactional with pessimistic locking, the `findByStatusAndExpiresAtBefore` query returns the same set of reservations to all pods, causing:
- Unnecessary contention and lock waits on `stock_items` rows
- Wasted database connections and CPU
- Potential `ReservationNotFoundException` if one pod expires before another pod's transaction starts

**Impact**: Under load, multiple pods will fight over the same expired reservations, degrading database performance.  
**Recommendation**: Use ShedLock (`@SchedulerLock`) or a database advisory lock to ensure only one pod runs the expiry job at a time. Alternatively, use `SELECT ... FOR UPDATE SKIP LOCKED` on the reservations query.

### FINDING-I2: No Outbox Pattern for Inventory Events — Severity: HIGH

**Files**: `inventory-service/` (entire codebase), `docs/09-contracts-events-protobuf.md` (L66)

The contracts doc (section 2) specifies `inventory.events` Kafka topic with inventory-service as producer via Debezium. The event schemas define `StockReserved.v1`, `StockConfirmed.v1`, `StockReleased.v1`, and `LowStockAlert.v1`. However, **the inventory-service has no outbox table, no outbox event publishing, and no Debezium connector config**. There is no `outbox_events` table in any migration file.

**Impact**: Downstream consumers (catalog-service for stock display, notification-service for low-stock alerts) will never receive inventory events. The event-driven architecture is broken for this service.  
**Recommendation**: Add `V6__create_outbox_events.sql` migration, an `OutboxEvent` entity, and publish events in the same transaction as stock mutations (reserve/confirm/cancel/adjust).

### FINDING-I3: No LowStockAlert Detection — Severity: HIGH

**Files**: `inventory-service/src/main/java/com/instacommerce/inventory/service/InventoryService.java`, `docs/09-contracts-events-protobuf.md` (L256-269)

The `LowStockAlert.v1` event schema requires `productId`, `warehouseId`, `currentQuantity`, `threshold`, `detectedAt`. But the inventory service has:
- No configurable low-stock threshold
- No detection logic after stock adjustments or reservation confirmations
- No event publishing mechanism (see FINDING-I2)

**Impact**: Warehouse teams and procurement get no automated alerts when stock runs low — a critical gap for a 10-minute delivery platform.  
**Recommendation**: Add a `low_stock_threshold` column to `stock_items` (or a separate config table), check after every `adjustStock()` and `confirm()`, and publish `LowStockAlert` via outbox.

### FINDING-I4: Expiry Job Loads All Expired Reservations Unbounded — Severity: MEDIUM

**File**: `inventory-service/src/main/java/com/instacommerce/inventory/service/ReservationExpiryJob.java` (L24-25)

`findByStatusAndExpiresAtBefore(PENDING, now)` returns ALL expired reservations with no `LIMIT`. During a traffic spike or if the job was temporarily down, thousands of expired reservations could be loaded into memory and processed in a single scheduled invocation, causing OOM or long transaction hold times.

**Impact**: Memory exhaustion and long-running transactions during backlog processing.  
**Recommendation**: Use `Pageable` with a limit (e.g., 100 per batch) and loop until no more results. Add a `@Transactional` per-item instead of per-batch.

### FINDING-I5: StockItem `@Version` Field Conflicts with Pessimistic Locking — Severity: MEDIUM

**File**: `inventory-service/src/main/java/com/instacommerce/inventory/domain/model/StockItem.java` (L37-38)

The `StockItem` entity has `@Version private long version;` (optimistic locking) while the service explicitly uses `LockModeType.PESSIMISTIC_WRITE` (pessimistic locking). The doc (section 10) explicitly says: "never optimistic locking for reservation." Having `@Version` means JPA will throw `OptimisticLockException` on concurrent updates even when the row is already pessimistically locked, because the version check happens at flush time.

**Impact**: Under high concurrency, legitimate pessimistically-locked updates could fail with `OptimisticLockException` if two transactions read the same version before one commits.  
**Recommendation**: Remove `@Version` from `StockItem` entirely. The pessimistic lock provides the concurrency control. The `version` column in the DDL can remain for audit purposes but should not be mapped with `@Version`.

---

## 3. Scalability

### FINDING-SC1: No Distributed Cache (Redis) for Catalog Reads — Severity: HIGH

**Files**: `catalog-service/build.gradle.kts`, `catalog-service/src/main/resources/application.yml`

The doc specifies "Caffeine L1 cache for hot products" with a future path to "GCP Memorystore (Redis) as L2 cache." However, the current implementation has **no caching at all** — neither Caffeine nor Redis. There is no `spring-boot-starter-cache` dependency, no `@Cacheable` annotations on any service method, and no `CacheConfig.java` file (mentioned in the doc but absent from the codebase).

**Impact**: Every `GET /products/{id}` hits PostgreSQL directly. At 20M users with read-heavy catalog traffic, this will overwhelm the database. Catalog data is highly cacheable (changes infrequently).  
**Recommendation**: Add `spring-boot-starter-cache` + `caffeine` dependencies, create `CacheConfig.java`, and add `@Cacheable("products")` to `ProductService.getProduct()` and `CategoryService.getCategoryTree()` with eviction on writes.

### FINDING-SC2: No HikariCP Tuning in Catalog Service — Severity: MEDIUM

**File**: `catalog-service/src/main/resources/application.yml`

The catalog service `application.yml` has no HikariCP pool configuration. Spring Boot defaults to `maximumPoolSize=10`, which is insufficient for a service handling search, product reads, pricing computation, and admin writes concurrently. The doc specifies `maximum-pool-size: 30, minimum-idle: 10`.

The inventory service similarly lacks explicit pool config, though its doc says `maximum-pool-size: 20`.

**Impact**: Connection pool exhaustion under moderate load.  
**Recommendation**: Add explicit HikariCP configuration matching the documented values.

### FINDING-SC3: No Database Sharding Readiness — Severity: LOW

**Files**: All DDL migrations

All tables use `UUID` primary keys, which is good for future sharding. However, there is no shard key consideration in the schema. For inventory, the natural shard key is `store_id` (partition by store/warehouse). The composite unique constraint `(product_id, store_id)` on `stock_items` aligns well with this.

**Impact**: Not an immediate issue but will require schema changes for horizontal scaling.  
**Recommendation**: Document the sharding strategy. For inventory, shard by `store_id`. For catalog, read replicas + caching are likely sufficient before sharding is needed.

### FINDING-SC4: Rate Limiter Memory Leak — Severity: HIGH

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/service/RateLimitService.java` (L16, L29)

The `ConcurrentHashMap<String, RateLimiter> limiters` creates a new `RateLimiter` per unique IP address and **never evicts** them. With 20M users, this will grow unboundedly, consuming heap memory. The key format `productLimiter:192.168.1.1` means one entry per (limiter-type × distinct-IP).

**Impact**: Memory leak proportional to unique client IPs. Will cause OOM in production.  
**Recommendation**: Use a bounded cache (Caffeine with `maximumSize` + `expireAfterAccess`) instead of `ConcurrentHashMap`. Alternatively, move rate limiting to the API gateway (Istio) where it belongs for a microservices architecture.

---

## 4. Data Model

### FINDING-D1: No ProductVariant Entity — Severity: MEDIUM

**Files**: `catalog-service/src/main/java/com/instacommerce/catalog/domain/model/Product.java`, DDL migrations

The doc mentions `ProductVariant.java` in the package structure (doc section 2, line 91) but the implementation has no `ProductVariant` entity, no `product_variants` table, and no variant-related logic. For a grocery Q-commerce app, variants are essential (e.g., milk in 500ml/1L/2L, different pack sizes).

**Impact**: Each size/variant must be a separate product with a separate SKU, leading to catalog bloat and poor UX (no "select size" dropdown).  
**Recommendation**: Add a `product_variants` table with `product_id FK`, `variant_name`, `variant_value`, `sku`, `price_cents`, `weight_grams`. Update `Product` to have a `OneToMany` relationship.

### FINDING-D2: Category Hierarchy Limited to Adjacency List — Severity: LOW

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/service/CategoryService.java` (L34-55)

The category tree is built by loading ALL active categories into memory and assembling the tree in Java. This works for small catalogs but:
- Loads the entire category table on every request (no caching)
- No recursive CTE for "find all products under a parent category and its descendants"
- `getProductsByCategory()` only returns products in the exact category, not its children

**Impact**: Browsing "Fruits & Vegetables" won't show products in "Fresh Fruits" sub-category.  
**Recommendation**: Add a recursive CTE query for descendant category IDs, or use a materialized path / closure table for efficient subtree queries. Cache the category tree (it rarely changes).

### FINDING-D3: Reservation Missing `order_id` Field — Severity: MEDIUM

**Files**: `inventory-service/src/main/java/com/instacommerce/inventory/domain/model/Reservation.java`, `docs/09-contracts-events-protobuf.md` (L317)

The gRPC contract `ReserveStockRequest` includes `order_id` field, and the `StockReserved.v1` event schema requires `orderId`. But the `Reservation` entity and `reservations` DDL table have no `order_id` column. The `ReserveRequest` DTO also lacks `orderId`.

**Impact**: Cannot correlate reservations with orders for debugging, auditing, or event publishing compliance.  
**Recommendation**: Add `order_id UUID` column to `reservations` table and propagate through the API.

### FINDING-D4: `reservation_status` Mapped as String Despite DB Enum — Severity: LOW

**File**: `inventory-service/src/main/java/com/instacommerce/inventory/domain/model/Reservation.java` (L34-36)

The DDL creates `reservation_status` as a PostgreSQL `ENUM` type, but the JPA entity uses `@Enumerated(EnumType.STRING)`. This works because PostgreSQL will cast the string to the enum, but it's fragile — adding a new status in Java without a migration to alter the enum type will cause runtime errors.

**Recommendation**: Either use `VARCHAR` in DDL (more flexible) or ensure a migration accompanies any Java enum change.

---

## 5. Concurrency

### FINDING-C1: Slug Uniqueness Check Has TOCTOU Race Condition — Severity: MEDIUM

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/service/ProductService.java` (L163-177)

`resolveUniqueSlug()` checks `existsBySlug(candidate)` then saves. Two concurrent product creations with the same name will both pass the check, and one will fail with a unique constraint violation (`uq_product_slug`). While the DB constraint prevents data corruption, the user gets an opaque 500 error instead of a meaningful retry.

**Impact**: Rare but possible race condition on concurrent product creation with identical names.  
**Recommendation**: Catch `DataIntegrityViolationException` on save and retry with an incremented suffix, or use `INSERT ... ON CONFLICT` logic.

### FINDING-C2: SKU Uniqueness Check Same TOCTOU Pattern — Severity: LOW

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/service/ProductService.java` (L60-61)

Same pattern as C1: `existsBySku()` check then save. DB constraint `uq_product_sku` prevents corruption but error handling is not graceful.

### FINDING-C3: Reservation Concurrency Correctly Implemented — Severity: ✅ OK

**File**: `inventory-service/src/main/java/com/instacommerce/inventory/service/ReservationService.java` (L52-91)

The reservation flow correctly:
- Sorts items by `productId` to prevent deadlocks (L58-60)
- Uses `LockModeType.PESSIMISTIC_WRITE` (SELECT FOR UPDATE) on each stock item (L167-176)
- Validates availability under lock before mutating (L64-67)
- All-or-nothing: throws before any mutation if any item is insufficient (L66)
- Idempotency via `idempotencyKey` check (L54-57)
- Uses `READ_COMMITTED` isolation level (L52)

This is production-grade implementation for overselling prevention. **Well done.**

### FINDING-C4: Confirm/Cancel Don't Use Pessimistic Lock on Reservation Row — Severity: MEDIUM

**File**: `inventory-service/src/main/java/com/instacommerce/inventory/service/ReservationService.java` (L94-96, L121-123)

`confirm()` and `cancel()` use `reservationRepository.findById()` (no lock) to read the reservation, then check status, then lock stock items. Two concurrent `confirm()` calls for the same reservation could both pass the status check, both lock stock items, and both decrement `on_hand` — double-deducting inventory.

**Impact**: Double-confirmation of a reservation deducts stock twice. Extremely rare but catastrophic.  
**Recommendation**: Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the `findById` query for confirm/cancel, or add a custom query with pessimistic lock: `SELECT r FROM Reservation r WHERE r.id = :id` with `PESSIMISTIC_WRITE`.

---

## 6. Event Contracts

### FINDING-E1: Inventory Service Publishes No Events — Severity: CRITICAL

**Files**: Entire `inventory-service/` codebase, `docs/09-contracts-events-protobuf.md` (L39-42, L229-269)

See FINDING-I2. The contracts module defines 4 inventory events (`StockReserved`, `StockConfirmed`, `StockReleased`, `LowStockAlert`) and the `inventory.events` Kafka topic, but the inventory-service has zero event publishing infrastructure. No outbox table, no outbox entity, no event DTOs.

### FINDING-E2: ProductChangedEvent Missing Fields vs Schema — Severity: MEDIUM

**Files**: `catalog-service/src/main/java/com/instacommerce/catalog/event/ProductChangedEvent.java` (L6-12), `docs/09-contracts-events-protobuf.md` (L48-50)

The `ProductCreated.v1` and `ProductUpdated.v1` schemas are referenced but their full JSON schemas are not defined in the contracts doc. The `ProductChangedEvent` record has `{id, sku, name, slug, categoryId, active}` — a minimal payload. Missing fields that downstream consumers likely need:
- `basePriceCents` / `currency` (pricing display)
- `brand` (search index sync)
- `imageUrl` (for notification thumbnails)

**Impact**: Downstream consumers must call back to catalog-service API to get full product details on every event.  
**Recommendation**: Include key product fields in the event payload, or explicitly document that consumers should enrich via API call.

### FINDING-E3: gRPC Proto Not Implemented — Severity: MEDIUM

**Files**: Both services' `build.gradle.kts`, doc sections on gRPC

The docs specify gRPC endpoints for both services (`PricingGrpcService.java`, `InventoryGrpcService.java`) with proto files. However:
- No gRPC dependencies in either `build.gradle.kts` (no `grpc-netty-shaded`, `grpc-stub`, `grpc-protobuf`)
- No `proto/` directories in either service
- No gRPC server configuration
- No dependency on the `contracts` module

Both services are REST-only. Cart-service and order-service will need to make HTTP calls instead of efficient gRPC calls for pricing and inventory operations.

**Impact**: Higher latency for internal service-to-service calls (HTTP vs gRPC binary protocol).  
**Recommendation**: Add gRPC dependencies, import the `contracts` module, and implement gRPC service stubs alongside REST controllers.

---

## 7. Performance

### FINDING-P1: N+1 Query in PricingService — Severity: HIGH

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/service/PricingService.java` (L41-54)

The `compute()` method iterates over each `PricingItemRequest` and:
1. Calls `productRepository.findByIdAndIsActiveTrue(itemRequest.productId())` — 1 query per item
2. Inside `ZoneOverrideStrategy.apply()`, calls `pricingRuleRepository.findApplicable(...)` — 1 query per item

For a cart with 10 items, this executes **20+ queries** (10 product lookups + 10 pricing rule lookups).

**Impact**: Pricing computation latency scales linearly with cart size. With 20 items, this could exceed the 200ms SLO.  
**Recommendation**: Batch-load all products by IDs in a single query (`findAllById`), batch-load all pricing rules by product IDs, then iterate in memory.

### FINDING-P2: Dual Count + Data Query for Search — Severity: MEDIUM

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/repository/SearchRepository.java` (L18-48)

Every search executes two native queries: the data query (L28-34) and the count query (L36-46). The count query re-evaluates the tsvector matching, which is expensive. For large product tables, this doubles search latency.

**Recommendation**: Use `window function` approach (`COUNT(*) OVER()`) in the data query to get total count in a single query, or cache the count for repeated queries.

### FINDING-P3: Category Tree Loaded from DB on Every Request — Severity: MEDIUM

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/service/CategoryService.java` (L34-55)

`getCategoryTree()` loads ALL active categories from the database on every call. Category data changes extremely rarely (admin operation). This should be cached.

**Impact**: Unnecessary database queries for static data.  
**Recommendation**: Cache with `@Cacheable("categoryTree")` and evict on category admin operations.

### FINDING-P4: `deleteByCreatedAtBefore` Without Batch Limiting — Severity: LOW

**Files**: `catalog-service/...AuditLogCleanupJob.java` (L24-27), `inventory-service/...AuditLogCleanupJob.java` (L24-27)

`auditLogRepository.deleteByCreatedAtBefore(cutoff)` generates a single `DELETE FROM audit_log WHERE created_at < ?`. After 2 years of operation (730d retention), this could delete millions of rows in a single statement, causing long lock holds and WAL bloat.

**Recommendation**: Delete in batches (e.g., 10K rows per iteration) using a loop with `LIMIT`.

### FINDING-P5: Product Mapper Triggers Lazy Loading of Category — Severity: MEDIUM

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/dto/mapper/ProductMapper.java` (L20)

`toCategorySummary(product.getCategory())` accesses the `@ManyToOne(fetch = FetchType.LAZY)` category association. When mapping a `Page<Product>` (list endpoint), this triggers a separate SELECT for each product's category — a classic N+1 problem.

**Impact**: Listing 20 products generates 1 (products) + 20 (categories) queries = 21 queries.  
**Recommendation**: Use `@EntityGraph` or `JOIN FETCH` in the product repository queries to eagerly load categories when listing products.

---

## 8. Missing Features

### FINDING-M1: No Dynamic Pricing / Surge Pricing — Severity: HIGH

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/pricing/`

The pricing strategy chain supports base price + zone overrides + promotions + coupons. But there is no demand-based dynamic pricing (common in Q-commerce during peak hours). The `PromotionStrategy` is a no-op stub (returns unchanged price).

**Recommendation**: Implement `DemandPricingStrategy` that adjusts prices based on time-of-day, demand signals, or store-specific rules. The strategy pattern makes this straightforward to add.

### FINDING-M2: No Flash Sale / Time-Limited Offer Support — Severity: MEDIUM

**Files**: All pricing files

`PricingRule` has `valid_from`/`valid_to` which provides basic time-windowing, but there's no:
- Inventory cap per promotion ("first 100 units at 50% off")
- Real-time countdown / availability tracking
- Priority handling between flash sales and regular promotions

**Recommendation**: Add `quantity_limit` and `quantity_used` to `pricing_rules` for capped promotions.

### FINDING-M3: No Product Recommendations — Severity: LOW

No collaborative filtering, "frequently bought together", or "customers also viewed" features exist. This is expected for MVP but is a significant revenue driver for Q-commerce.

### FINDING-M4: No Bundle Pricing — Severity: LOW

No mechanism to define product bundles (e.g., "breakfast combo: bread + milk + eggs at ₹X"). Would require a new entity and pricing strategy.

### FINDING-M5: No Inventory Forecasting — Severity: LOW

The `stock_adjustment_log` provides historical data for forecasting but no predictive logic exists. This is expected for MVP.

### FINDING-M6: No Bulk Product Import/Export — Severity: MEDIUM

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/controller/AdminProductController.java`

Only single-product CRUD endpoints exist. For onboarding a new dark store with 5000+ SKUs, admins must create products one at a time. No CSV/bulk import endpoint.

**Recommendation**: Add `POST /admin/products/bulk` accepting a list of products, with batch insert and partial failure reporting.

---

## 9. Additional Findings

### FINDING-A1: Duplicate Code Across Services — Severity: LOW

The following classes are **identical or near-identical** between catalog-service and inventory-service:
- `JwtAuthenticationFilter.java`
- `DefaultJwtService.java`
- `JwtKeyLoader.java`
- `JwtService.java` (interface)
- `RestAuthenticationEntryPoint.java`
- `RestAccessDeniedHandler.java`
- `TraceIdProvider.java`
- `AuditLog.java` (entity)
- `AuditLogService.java`
- `AuditLogCleanupJob.java`
- `AuditLogRepository.java`
- `ApiException.java`
- `ErrorResponse.java` / `ErrorDetail.java`

**Recommendation**: Extract a shared `instacommerce-common` library module containing security, audit, error handling, and tracing infrastructure.

### FINDING-A2: Application.yml References Redis in Health Checks but No Redis Dependency — Severity: LOW

**Files**: `catalog-service/src/main/resources/application.yml` (L68), `inventory-service/src/main/resources/application.yml` (L60)

Both services include `redis` in the readiness health check group (`readinessState,db,redis`) but neither service has Redis configured or as a dependency. This will cause the readiness probe to report unhealthy.

**Impact**: Kubernetes readiness probes will fail, preventing the pods from receiving traffic.  
**Recommendation**: Remove `redis` from the readiness health group until Redis is actually integrated.

### FINDING-A3: Outbox Table Has `sent` Column Not Used by Debezium — Severity: LOW

**File**: `catalog-service/src/main/resources/db/migration/V5__create_outbox_events.sql` (L8)

The outbox table has a `sent BOOLEAN DEFAULT false` column. With Debezium CDC (which reads the WAL, not polls), this column is unused. The `OutboxEvent` entity also maps it but never sets it to `true`. Over time, this table will grow unboundedly.

**Recommendation**: Add a cleanup job to delete outbox events older than the Debezium connector's confirmed offset, or use Debezium's `outbox.event.router` which can auto-delete after processing. Alternatively, simply truncate outbox events on a schedule since Debezium reads from the WAL.

### FINDING-A4: No `@Transactional` on `CouponService.validateAndCalculate` — Severity: LOW

**File**: `catalog-service/src/main/java/com/instacommerce/catalog/service/CouponService.java` (L22)

The method makes multiple repository calls (`findByCodeIgnoreCase`, `countByCoupon_Id`, `countByCoupon_IdAndUserId`, `existsByUserId`) but has no `@Transactional` annotation. Each call executes in its own transaction/session, which could lead to inconsistent reads under concurrent coupon usage.

**Recommendation**: Add `@Transactional(readOnly = true)`.

### FINDING-A5: Missing `Dockerfile` Review (Out of Scope but Noted)

Both services have `Dockerfile` files at their root. These were not examined in detail but should follow multi-stage build patterns with non-root user for production.

### FINDING-A6: ReservationExpiryJob Calls `cancel()` Logic Without Audit Logging — Severity: LOW

**File**: `inventory-service/src/main/java/com/instacommerce/inventory/service/ReservationService.java` (L150-154)

Stock adjustments from manual admin operations are audit-logged via `AuditLogService`, but reservation-related stock changes (reserve, confirm, cancel, expire) produce no `StockAdjustmentLog` entries. Only the `adjustStock()` endpoint logs to `stock_adjustment_log`.

**Impact**: No audit trail for stock movements due to reservations.  
**Recommendation**: Log `StockAdjustmentLog` entries for reserve (reason=RESERVATION), confirm (reason=CONFIRM), cancel (reason=CANCEL), and expire (reason=EXPIRY) operations.

---

## 10. Summary Matrix

| ID | Finding | Severity | Service | Category |
|----|---------|----------|---------|----------|
| I1 | Expiry job has no distributed lock | **CRITICAL** | inventory | Inventory Mgmt |
| E1 | Inventory publishes no events (outbox missing) | **CRITICAL** | inventory | Event Contracts |
| I2 | No outbox pattern for inventory events | **HIGH** | inventory | Inventory Mgmt |
| I3 | No LowStockAlert detection | **HIGH** | inventory | Inventory Mgmt |
| SC1 | No caching layer (Caffeine/Redis) for catalog | **HIGH** | catalog | Scalability |
| SC4 | Rate limiter ConcurrentHashMap memory leak | **HIGH** | catalog | Scalability |
| P1 | N+1 queries in PricingService (per cart item) | **HIGH** | catalog | Performance |
| S1 | No autocomplete/prefix search | **HIGH** | catalog | Search |
| M1 | No dynamic pricing / surge pricing | **HIGH** | catalog | Missing Features |
| C4 | Confirm/cancel don't lock reservation row | **MEDIUM** | inventory | Concurrency |
| I4 | Expiry job loads unbounded result set | **MEDIUM** | inventory | Inventory Mgmt |
| I5 | StockItem @Version conflicts with pessimistic lock | **MEDIUM** | inventory | Inventory Mgmt |
| D1 | No ProductVariant entity | **MEDIUM** | catalog | Data Model |
| D3 | Reservation missing order_id field | **MEDIUM** | inventory | Data Model |
| E2 | ProductChangedEvent missing fields | **MEDIUM** | catalog | Event Contracts |
| E3 | gRPC proto not implemented | **MEDIUM** | both | Event Contracts |
| SC2 | No HikariCP tuning | **MEDIUM** | both | Scalability |
| S2 | No faceted search | **MEDIUM** | catalog | Search |
| S4 | No search results caching | **MEDIUM** | catalog | Search |
| S5 | No search abstraction for OpenSearch migration | **MEDIUM** | catalog | Search |
| P2 | Dual count + data query for search | **MEDIUM** | catalog | Performance |
| P3 | Category tree loaded from DB every request | **MEDIUM** | catalog | Performance |
| P5 | N+1 on category lazy load in product listing | **MEDIUM** | catalog | Performance |
| M2 | No flash sale / capped promotions | **MEDIUM** | catalog | Missing Features |
| M6 | No bulk product import | **MEDIUM** | catalog | Missing Features |
| C1 | Slug uniqueness TOCTOU race | **MEDIUM** | catalog | Concurrency |
| A2 | Redis in health check but not configured | **LOW** | both | Config |
| D2 | Category tree limited to adjacency list | **LOW** | catalog | Data Model |
| D4 | Enum mismatch between JPA and PostgreSQL | **LOW** | inventory | Data Model |
| SC3 | No sharding readiness documentation | **LOW** | both | Scalability |
| C2 | SKU uniqueness TOCTOU race | **LOW** | catalog | Concurrency |
| S3 | tsvector special chars (mitigated by plainto_tsquery) | **LOW** | catalog | Search |
| P4 | Audit log cleanup unbounded delete | **LOW** | both | Performance |
| A1 | Duplicate code across services | **LOW** | both | Code Quality |
| A3 | Outbox `sent` column unused by Debezium | **LOW** | catalog | Event Contracts |
| A4 | CouponService missing @Transactional | **LOW** | catalog | Concurrency |
| A6 | No StockAdjustmentLog for reservations | **LOW** | inventory | Inventory Mgmt |
| M3 | No product recommendations | **LOW** | catalog | Missing Features |
| M4 | No bundle pricing | **LOW** | catalog | Missing Features |
| M5 | No inventory forecasting | **LOW** | inventory | Missing Features |

---

### Priority Action Items (Recommended Order)

1. **CRITICAL**: Add distributed lock to ReservationExpiryJob (I1)
2. **CRITICAL**: Add outbox table + event publishing to inventory-service (E1/I2)
3. **HIGH**: Add Caffeine caching to catalog-service product/category reads (SC1)
4. **HIGH**: Fix rate limiter memory leak — use bounded cache (SC4)
5. **HIGH**: Batch-load products/pricing rules in PricingService (P1)
6. **HIGH**: Add LowStockAlert threshold + detection (I3)
7. **HIGH**: Add autocomplete/prefix search endpoint (S1)
8. **MEDIUM**: Add pessimistic lock on reservation row for confirm/cancel (C4)
9. **MEDIUM**: Remove `@Version` from StockItem or document why it coexists with pessimistic locking (I5)
10. **MEDIUM**: Add `order_id` to reservations schema (D3)
