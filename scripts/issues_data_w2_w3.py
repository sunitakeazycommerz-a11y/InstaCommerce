#!/usr/bin/env python3
"""
Issue definitions for Milestone 19 (Wave 2) and Milestone 20 (Wave 3).
Contains 52 staff-level issue specifications.
"""

WAVE_2_ISSUES = [
    {
        "milestone": 19,
        "title": "drift(contracts): event envelope field naming divergence (eventId vs event_id, camelCase vs snake_case)",
        "labels": ["kind/bug", "priority/p0-critical", "area/data-platform", "java", "go"],
        "body": """### Summary
`EventEnvelope.v1.json` schema mandates `id`, `outbox-relay-service` emits `eventId` and `id` (camelCase), while README and Go consumers expect `event_id` (snake_case). Event consumers crash or fail deserialization.

### Evidence & Code Reference
- `contracts/src/main/resources/schemas/common/EventEnvelope.v1.json`.
- `services/outbox-relay-service/main.go` (`buildEventMessage`).
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C8-P0-11).

### Blast Radius & Business Impact
Silent breakage across all asynchronous Kafka pipelines; unparseable events routed to DLT or dropped.

### Remediation Plan
1. Standardize canonical JSON schema strictly on snake_case: `event_id`, `event_type`, `aggregate_type`, `aggregate_id`.
2. Update `outbox-relay-service` to serialize snake_case fields.
3. Support dual-read compatibility in Java/Go Jackson/json decoders during rolling migration."""
    },
    {
        "milestone": 19,
        "title": "drift(contracts): outbox relay payloads missing correlation_id and source_service headers",
        "labels": ["kind/tech-debt", "priority/p1-high", "area/data-platform", "go"],
        "body": """### Summary
`outbox-relay-service` extracts outbox table records but fails to populate `correlation_id` and `source_service` in the Kafka message envelope and transport headers.

### Evidence & Code Reference
- `services/outbox-relay-service/main.go` lines 110-140.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C8-P1-10).

### Blast Radius & Business Impact
Distributed tracing breaks: inability to correlate background Kafka processing back to originating mobile HTTP request.

### Remediation Plan
1. Extract `correlation_id` from database outbox row metadata.
2. Inject Kafka Record Headers: `X-Correlation-Id`, `X-Source-Service`, `X-B3-TraceId`.
3. Add consumer middleware in `go-shared` and Java contracts decoding these headers into MDC."""
    },
    {
        "milestone": 19,
        "title": "drift(contracts): contracts build not enforced in CI path filters allowing silent breaking changes",
        "labels": ["kind/bug", "priority/p0-critical", "area/data-platform"],
        "body": """### Summary
Changes committed to `contracts/` do not run `./gradlew :contracts:build` or JSON Schema validation in `.github/workflows/ci.yml`.

### Evidence & Code Reference
- `.github/workflows/ci.yml` path triggers omit `contracts/**`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C8-P0-12).

### Blast Radius & Business Impact
Breaking schema changes (field removals, type changes) merge silently to `main`, breaking downstream consumers on deployment.

### Remediation Plan
1. Add `contracts/**` to GitHub Actions CI path filter matrix.
2. Run `./gradlew :contracts:build` and Protobuf linter on every PR touching contracts.
3. Fail CI if generated Java stubs or JSON schemas contain syntax errors."""
    },
    {
        "milestone": 19,
        "title": "feat(contracts): automated JSON Schema breaking change detection and backwards compatibility CI gate",
        "labels": ["kind/feature", "priority/p1-high", "area/data-platform"],
        "body": """### Summary
There is no automated compatibility checker verifying that edits to `contracts/src/main/resources/schemas/**/*.json` adhere to backwards compatibility rules (e.g. no removed required fields).

### Evidence & Code Reference
- `contracts/build.gradle.kts`.

### Blast Radius & Business Impact
Producers emit events that older versions of microservices cannot deserialize during canary deployments.

### Remediation Plan
1. Integrate `json-schema-diff` or Confluent Schema Registry compatibility tool in CI.
2. Enforce FULL compatibility mode: old consumers can read new messages, and new consumers can read old messages.
3. Reject PRs violating backwards compatibility without semantic major version bump."""
    },
    {
        "milestone": 19,
        "title": "fix(catalog): LoggingOutboxEventPublisher logs only and does not publish to catalog.events Kafka topic",
        "labels": ["kind/bug", "priority/p0-critical", "area/data-platform", "java"],
        "body": """### Summary
`catalog-service` uses `LoggingOutboxEventPublisher`, which only writes `logger.info(\"Event published: {}\")` without inserting records into the `outbox_events` table or producing to Kafka.

### Evidence & Code Reference
- `services/catalog-service/src/main/java/com/instacommerce/catalog/publisher/LoggingOutboxEventPublisher.java`.
- `services/catalog-service/build.gradle.kts` lacks Kafka dependencies.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C3-P0-06).

### Blast Radius & Business Impact
New products, category updates, and price changes never propagate to `search-service`. The search index remains permanently stale.

### Remediation Plan
1. Replace `LoggingOutboxEventPublisher` with transactional `PostgresOutboxEventPublisher`.
2. Insert domain events into `outbox_events` table during product create/update transactions.
3. Verify `outbox-relay-service` picks up and publishes events to `catalog.events` topic."""
    },
    {
        "milestone": 19,
        "title": "fix(catalog): ProductChangedEvent missing critical pricing and packaging attributes in contract payload",
        "labels": ["kind/bug", "priority/p0-critical", "area/data-platform", "java"],
        "body": """### Summary
`ProductChangedEvent` payload omits 7 essential fields: `basePriceCents`, `unitValue`, `unitOfMeasure`, `weightGrams`, `isPerishable`, `temperatureZone`, and `vatRateBps`.

### Evidence & Code Reference
- `contracts/src/main/resources/schemas/catalog/ProductUpdated.v1.json`.
- `services/catalog-service/src/main/java/com/instacommerce/catalog/event/ProductChangedEvent.java`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C3-P0-06).

### Blast Radius & Business Impact
Downstream search, pricing, and warehouse services cannot calculate delivery bag volume or display product pricing accurately.

### Remediation Plan
1. Update `ProductUpdated.v1.json` schema to include all 7 missing product attributes.
2. Populate fields from `ProductEntity` when publishing `ProductChangedEvent`.
3. Update `search-service` consumer to index these attributes in Elasticsearch."""
    },
    {
        "milestone": 19,
        "title": "fix(cart): PricingClient hardcoded base URL and field mapping mismatch (unitPriceCents vs priceCents)",
        "labels": ["kind/bug", "priority/p0-critical", "area/edge-api", "java"],
        "body": """### Summary
`PricingClient.java` in `cart-service` uses a hardcoded localhost URL and maps response field `unitPriceCents` while `pricing-service` returns `priceCents`, resulting in null unit prices in cart calculations.

### Evidence & Code Reference
- `services/cart-service/src/main/java/com/instacommerce/cart/client/PricingClient.java`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C3-P0-07).

### Blast Radius & Business Impact
Cart totals compute to zero or fail with `NullPointerException` when querying live pricing.

### Remediation Plan
1. Parameterize `pricing-service.base-url` in `application.yml` with Kubernetes DNS (`http://pricing-service:8089`).
2. Align Jackson `@JsonProperty(\"priceCents\")` mapping in DTO.
3. Add WireMock integration test verifying JSON response parsing."""
    },
    {
        "milestone": 19,
        "title": "fix(pricing): validateCoupon is read-only and never triggers coupon redemption in database",
        "labels": ["kind/bug", "priority/p0-critical", "area/edge-api", "java"],
        "body": """### Summary
`PricingService.validateCoupon()` is annotated with `@Transactional(readOnly = true)`. The method validates the coupon code but never marks usage count, allowing unlimited coupon reuse.

### Evidence & Code Reference
- `services/pricing-service/src/main/java/com/instacommerce/pricing/service/PricingService.java` lines 85-110.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C3-P0-07).

### Blast Radius & Business Impact
Severe revenue leakage: single-use promo codes can be applied infinitely across thousands of customer orders.

### Remediation Plan
1. Split into two methods: `validateCoupon()` (read-only verification) and `redeemCoupon()` (read-write increment).
2. Invoke `redeemCoupon()` atomically upon order placement via Temporal saga.
3. Emit `CouponRedeemed` event to Kafka."""
    },
    {
        "milestone": 19,
        "title": "drift(cache): replace JVM-local Caffeine with distributed Redis cluster across Catalog, Cart, Pricing, Search",
        "labels": ["kind/tech-debt", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
`catalog-service`, `cart-service`, `pricing-service`, and `search-service` all utilize JVM-local Caffeine in-memory caches. With multiple Kubernetes replicas, caches desynchronize, showing different prices to different pods.

### Evidence & Code Reference
- `services/*/src/main/java/com/instacommerce/*/config/CacheConfig.java`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C3-P1-05).

### Blast Radius & Business Impact
Customer sees price jump between refreshing screens or between product page and checkout screen.

### Remediation Plan
1. Configure `RedisCacheManager` backed by shared Redis cluster.
2. Set conservative TTLs (e.g. 5 minutes) with distributed key prefixing (`catalog:product:{id}`).
3. Retain small local L1 cache (Near-Cache) with 10s TTL for hot items."""
    },
    {
        "milestone": 19,
        "title": "feat(cache): event-driven cache invalidation consumer for catalog price and inventory mutations",
        "labels": ["kind/feature", "priority/p1-high", "area/data-platform", "java"],
        "body": """### Summary
When an administrator updates a product price or inventory drops to zero, cached data remains served until TTL expiration, leading to checkout failures on out-of-stock items.

### Evidence & Code Reference
- `services/catalog-service/` and `services/pricing-service/`.

### Blast Radius & Business Impact
Users add out-of-stock items to cart, only to have their order rejected during checkout saga.

### Remediation Plan
1. Add Kafka consumer listening to `catalog.events` and `pricing.events`.
2. Evict affected product and category cache keys in Redis upon receiving `ProductUpdated` or `PriceUpdated` events.
3. Publish invalidation event across Redis Pub/Sub to bust pod-local L1 caches."""
    },
    {
        "milestone": 19,
        "title": "fix(search): Elasticsearch index mapping drift for product attributes and multi-lingual text",
        "labels": ["kind/bug", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
`search-service` index mapping does not define sub-fields for multi-lingual analyzers (e.g. Hindi, regional languages) and uses dynamic field mapping, resulting in unstructured keyword type casting.

### Evidence & Code Reference
- `services/search-service/src/main/resources/elasticsearch/product-mapping.json`.

### Blast Radius & Business Impact
Degraded search discovery: customer queries with colloquial or localized product names return zero results.

### Remediation Plan
1. Author explicit Elasticsearch mapping with edge-ngram tokenizer and multi-field analyzer.
2. Define field mappings for `title.raw`, `title.autocomplete`, and `description.stemmed`.
3. Implement index alias reindexing workflow for zero-downtime mapping updates."""
    },
    {
        "milestone": 19,
        "title": "feat(search): real-time search reindexing consumer for catalog.events and inventory.events",
        "labels": ["kind/feature", "priority/p1-high", "area/data-platform", "java"],
        "body": """### Summary
`search-service` currently relies on periodic batch reindexing, causing a 15-to-30 minute delay before new products or price drops appear in user search queries.

### Evidence & Code Reference
- `services/search-service/src/main/java/com/instacommerce/search/consumer/`.

### Blast Radius & Business Impact
Commercial delay on flash sales and brand launch campaigns; newly listed inventory remains unsearchable.

### Remediation Plan
1. Implement real-time Kafka consumer for `catalog.events` and `inventory.events`.
2. Perform partial document updates (`_update`) in Elasticsearch on inventory count or price changes.
3. Ensure p95 update latency from Kafka event to Elasticsearch searchability is < 500ms."""
    },
    {
        "milestone": 19,
        "title": "fix(cart): cart item quantity updates lack optimistic locking leading to lost updates",
        "labels": ["kind/bug", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
Concurrent `PUT /cart/items` requests from multiple browser tabs or mobile background sync overwrite item quantities without version checking, leading to lost cart items.

### Evidence & Code Reference
- `services/cart-service/src/main/java/com/instacommerce/cart/service/CartService.java`.

### Blast Radius & Business Impact
Frustrating customer experience when updating quantities; unintended checkout basket contents.

### Remediation Plan
1. Add `@Version private Long version;` to `CartEntity`.
2. Return current version in cart response and require `If-Match` ETag header on updates.
3. Handle `OptimisticLockingFailureException` by returning HTTP 419 with refreshed cart state."""
    },
    {
        "milestone": 19,
        "title": "feat(pricing): dynamic surge pricing engine integration with real-time demand signals",
        "labels": ["kind/feature", "priority/p2-medium", "area/ai-ml", "java"],
        "body": """### Summary
`pricing-service` calculates static delivery fees and product prices without adjusting for hyper-local dark store demand spikes or severe weather conditions.

### Evidence & Code Reference
- `services/pricing-service/src/main/java/com/instacommerce/pricing/service/PricingService.java`.

### Blast Radius & Business Impact
Unbalanced delivery demand exceeding rider capacity; lost margin opportunities during peak hours.

### Remediation Plan
1. Consume real-time rider utilization and dark store queue length metrics from Redis.
2. Apply dynamic surge fee multiplier when local capacity exceeds 85%.
3. Display transparent surge notification badge on mobile cart screen."""
    },
    {
        "milestone": 19,
        "title": "fix(cart): abandoned cart expiration cron job missing index on updated_at timestamp",
        "labels": ["kind/bug", "priority/p2-medium", "area/edge-api", "java"],
        "body": """### Summary
`CartExpirationService` executes `SELECT ... WHERE updated_at < ? AND status = 'ACTIVE'` every hour. The `carts` table lacks an index on `(status, updated_at)`, triggering sequential table scans.

### Evidence & Code Reference
- `services/cart-service/src/main/resources/db/migration/V1__init_cart_schema.sql`.

### Blast Radius & Business Impact
PostgreSQL CPU spikes and query lock contention on the primary transactional cart database during hourly sweeps.

### Remediation Plan
1. Add Flyway migration creating index: `CREATE INDEX idx_carts_status_updated ON carts (status, updated_at);`.
2. Process expiration in batches of 500 rows with cursor pagination.
3. Publish `CartExpired` event to Kafka for marketing re-engagement pipelines."""
    },
    {
        "milestone": 19,
        "title": "fix(search): search autocomplete queries execute unindexed wildcard scans on high-traffic terms",
        "labels": ["kind/bug", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
`SearchController.suggest()` generates Elasticsearch `wildcard` queries (`*term*`), which bypass ngram inverted index terms and force full cluster Lucene segment scans.

### Evidence & Code Reference
- `services/search-service/src/main/java/com/instacommerce/search/service/SearchService.java`.

### Blast Radius & Business Impact
Search cluster CPU saturation and p99 latency spikes above 2000ms as users type in the mobile search bar.

### Remediation Plan
1. Replace wildcard queries with `completion_suggester` and `prefix` queries on edge-ngram indexed fields.
2. Cache top 1,000 autocomplete terms in Redis with 1-hour TTL.
3. Enforce min 2-character query length before triggering backend suggest calls."""
    },
    {
        "milestone": 19,
        "title": "feat(catalog): hierarchical category taxonomy tree traversal optimization with materialized path",
        "labels": ["kind/feature", "priority/p2-medium", "area/edge-api", "java"],
        "body": """### Summary
Querying category ancestors and children uses recursive parent_id SQL self-joins, degrading browse performance on deep 4-level taxonomy trees.

### Evidence & Code Reference
- `services/catalog-service/src/main/java/com/instacommerce/catalog/domain/Category.java`.

### Blast Radius & Business Impact
Slow category navigation screens on mobile apps; high database load on category page loads.

### Remediation Plan
1. Add materialized path column: `path VARCHAR(255)` (e.g. `/groceries/dairy/milk/organic`).
2. Index path using PostgreSQL ltree or btree prefix matching.
3. Fetch full sub-tree using single index range scan (`WHERE path LIKE '/groceries/dairy/%'`)."""
    },
    {
        "milestone": 19,
        "title": "fix(pricing): bundle discount calculation order-of-operations bug with percent-off coupons",
        "labels": ["kind/bug", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
When a cart contains both a bundle discount (e.g. Buy 2 Get 10% Off) and a global promo code (e.g. 20% Off), discounts compound in an arbitrary order depending on item array sorting.

### Evidence & Code Reference
- `services/pricing-service/src/main/java/com/instacommerce/pricing/service/DiscountCalculator.java`.

### Blast Radius & Business Impact
Inconsistent discount calculations and unintended excessive margin loss.

### Remediation Plan
1. Establish strict discount precedence order: 1) Item SKU discount, 2) Category bundle, 3) Global coupon code.
2. Apply percent-off coupons only against net subtotal post-bundle deductions.
3. Add property-based testing verifying discount total never exceeds order subtotal."""
    },
    {
        "milestone": 19,
        "title": "feat(cart): multi-device cart synchronization protocol via Redis Pub/Sub invalidation",
        "labels": ["kind/feature", "priority/p2-medium", "area/edge-api", "java"],
        "body": """### Summary
When a customer adds items on desktop web, their mobile app cart screen remains stale until a manual pull-to-refresh is executed.

### Evidence & Code Reference
- `services/cart-service/src/main/java/com/instacommerce/cart/controller/CartController.java`.

### Blast Radius & Business Impact
Friction in omnichannel shopping flows; accidental duplicate ordering across devices.

### Remediation Plan
1. Publish `cart:updated:{userId}` event over Redis Pub/Sub whenever cart state changes.
2. Forward event to active mobile client WebSocket / SSE connections via `mobile-bff-service`.
3. Mobile app automatically updates badge count and item list."""
    },
    {
        "milestone": 19,
        "title": "fix(search): pagination deep-paging OOM risk in Elasticsearch search_after query builder",
        "labels": ["kind/bug", "priority/p2-medium", "area/edge-api", "java"],
        "body": """### Summary
`SearchService` allows arbitrary `from + size` up to 10,000 using standard offset pagination, creating memory pressure on Elasticsearch coordinate nodes.

### Evidence & Code Reference
- `services/search-service/src/main/java/com/instacommerce/search/service/SearchService.java`.

### Blast Radius & Business Impact
Elasticsearch node out-of-memory crashes when scrapers or bots page through deep catalog results.

### Remediation Plan
1. Replace `from + size` with keyset cursor pagination using `search_after`.
2. Hard-limit max page size to 50 items.
3. Reject pagination beyond page 20 for unauthenticated bot user agents."""
    },
    {
        "milestone": 19,
        "title": "feat(catalog): product variant stock aggregation rollup for faceted search filters",
        "labels": ["kind/feature", "priority/p2-medium", "area/edge-api", "java"],
        "body": """### Summary
Faceted search displays filter checkboxes for product attributes (e.g. Size: 500ml, 1L) even when all variants under that attribute are completely out of stock in the user's dark store.

### Evidence & Code Reference
- `services/search-service/src/main/java/com/instacommerce/search/service/FacetedSearchService.java`.

### Blast Radius & Business Impact
Customer clicks filter and sees empty results; high search abandonment rate.

### Remediation Plan
1. Include `available_store_ids` array in Elasticsearch product index mapping.
2. Filter aggregations using `post_filter` scoped to customer's active dark store ID.
3. Grey out or hide facets with zero available items."""
    },
    {
        "milestone": 19,
        "title": "fix(pricing): tax computation precision rounding error causing 1-cent discrepancy with invoice",
        "labels": ["kind/bug", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
`pricing-service` rounds tax per line item using `ROUND_HALF_UP` and then sums them, whereas accounting invoice generator sums taxable amounts and calculates tax on total, causing occasional 1-cent rounding drift.

### Evidence & Code Reference
- `services/pricing-service/src/main/java/com/instacommerce/pricing/service/TaxCalculationService.java`.

### Blast Radius & Business Impact
Invoice tax reconciliation failures with government tax filing systems.

### Remediation Plan
1. Standardize tax calculation algorithm across pricing and billing services to calculate tax on line-item net base using Banker's rounding (`ROUND_HALF_EVEN`).
2. Maintain fractional cent remainder carry-over across line items.
3. Validate total against automated tax compliance unit test suite."""
    },
    {
        "milestone": 19,
        "title": "feat(search): zero-result query fallback to fuzzy spelling correction and vector semantic search",
        "labels": ["kind/feature", "priority/p2-medium", "area/ai-ml", "java"],
        "body": """### Summary
When a customer misspells an item or types a colloquial synonym (e.g. 'coke zero tin'), exact token matching returns zero search results.

### Evidence & Code Reference
- `services/search-service/src/main/java/com/instacommerce/search/service/SearchService.java`.

### Blast Radius & Business Impact
Lost sales conversion on queries with typos; negative customer perception of catalog depth.

### Remediation Plan
1. Configure Elasticsearch fuzzy match (`fuzziness: AUTO`) on zero initial hits.
2. Fall back to calling `ai-inference-service` text embedding endpoint for vector KNN search.
3. Display 'Did you mean: ...' recommendation banner on mobile UI."""
    },
    {
        "milestone": 19,
        "title": "fix(cart): guest cart merger during customer login overwrites existing user saved items",
        "labels": ["kind/bug", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
When an anonymous guest user logs in, `CartService.mergeCart()` replaces the authenticated user's existing saved cart items with the guest cart instead of combining line item quantities.

### Evidence & Code Reference
- `services/cart-service/src/main/java/com/instacommerce/cart/service/CartService.java` merge logic.

### Blast Radius & Business Impact
Customer loses previously accumulated basket items on login, leading to frustration and abandoned orders.

### Remediation Plan
1. Implement additive merge: combine quantities for identical SKU IDs up to max purchase limit (e.g. 10).
2. Append non-duplicate SKUs to the active cart.
3. Invalidate anonymous guest cart token in Redis."""
    },
    {
        "milestone": 19,
        "title": "feat(contracts): Protobuf stub generation and semantic versioning automation in CI",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/data-platform"],
        "body": """### Summary
Protobuf compiler generation requires manual `./gradlew :contracts:build` commands, and Go stubs are committed manually, leading to version drift between Java and Go Protobuf classes.

### Evidence & Code Reference
- `contracts/src/main/proto/`.

### Blast Radius & Business Impact
gRPC method signature mismatch between Java services and Go services.

### Remediation Plan
1. Integrate `buf` CLI tool into CI workflow to validate Protobuf linting and breaking changes.
2. Automate cross-compilation of Go stubs into `services/go-shared/proto/` during build.
3. Tag generated artifacts with semantic version matching git commit."""
    },
    {
        "milestone": 19,
        "title": "fix(pricing): promotion usage limit concurrency bug permitting simultaneous promo code exploitation",
        "labels": ["kind/bug", "priority/p0-critical", "area/edge-api", "java"],
        "body": """### Summary
Promotion `max_redemptions_per_user` check reads count from database without row-level lock. A user scripting 10 parallel checkouts can redeem a single-use coupon 10 times concurrently.

### Evidence & Code Reference
- `services/pricing-service/src/main/java/com/instacommerce/pricing/service/PromotionService.java`.

### Blast Radius & Business Impact
Exploitation of high-value referral discounts (e.g. $20 off first order) by automated discount syndicates.

### Remediation Plan
1. Acquire atomic Redis reservation key: `SET promo:{code}:{userId} 1 NX EX 300`.
2. Commit redemption count in PostgreSQL inside `@Transactional` with `SERIALIZABLE` or `SELECT ... FOR UPDATE`.
3. Add multi-threaded test proving race condition prevention."""
    }
]

WAVE_3_ISSUES = [
    {
        "milestone": 20,
        "title": "fix(inventory): ReservationService confirm() double-confirm race condition violating CHECK(reserved >= 0)",
        "labels": ["kind/bug", "priority/p0-critical", "area/checkout-saga", "java"],
        "body": """### Summary
`ReservationService.confirm()` acquires a lock on `StockItem` but fails to lock the `Reservation` row. Two concurrent confirm calls both read `status == PENDING`, decrement reserved count twice, triggering a database `CHECK (reserved >= 0)` violation and HTTP 500 error.

### Evidence & Code Reference
- `services/inventory-service/src/main/java/com/instacommerce/inventory/service/ReservationService.java` lines 80-115.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C4-P0-08).

### Blast Radius & Business Impact
Failed checkouts due to 500 internal server error instead of clean idempotency or 409 conflict; inventory count corruption.

### Remediation Plan
1. Acquire pessimistic write lock on the `Reservation` entity: `entityManager.find(Reservation.class, id, LockModeType.PESSIMISTIC_WRITE)`.
2. Check `if (reservation.getStatus() != ReservationStatus.PENDING) return;` (idempotent no-op).
3. Map `DataIntegrityViolationException` to HTTP 409 in `GlobalExceptionHandler` with lock timeout hint."""
    },
    {
        "milestone": 20,
        "title": "drift(inventory): inventory store_id VARCHAR(50) vs warehouse id UUID referential integrity gap",
        "labels": ["kind/bug", "priority/p0-critical", "area/infrastructure", "java"],
        "body": """### Summary
`inventory-service` stores `store_id` as arbitrary `VARCHAR(50)`, while `warehouse-service` uses `UUID`. No foreign key or API validation exists, allowing stock reservations against non-existent or decommissioned dark stores.

### Evidence & Code Reference
- `services/inventory-service/src/main/resources/db/migration/V1__init_inventory.sql`.
- `services/warehouse-service/src/main/resources/db/migration/V1__init_warehouse.sql`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C4-P0-09).

### Blast Radius & Business Impact
Phantom inventory: orders accepted and charged for dark stores that are permanently closed or undergoing renovation.

### Remediation Plan
1. Validate store existence and active operational status via `warehouse-service` client before creating reservations.
2. Cache valid dark store UUIDs in Redis with 10-minute TTL.
3. Migrate `inventory-service` `store_id` column to standard `UUID` type in Flyway migration."""
    },
    {
        "milestone": 20,
        "title": "fix(dispatch): designate dispatch-optimizer-service as formal assignment owner emitting RiderAssigned",
        "labels": ["kind/bug", "priority/p0-critical", "area/checkout-saga", "go"],
        "body": """### Summary
`fulfillment-service` emits `OrderPlaced`, `rider-fleet-service` monitors GPS pings, but neither service officially owns rider assignment. `dispatch-optimizer-service` consumes events but lacks authority to persist and emit canonical `RiderAssigned.v1.json`.

### Evidence & Code Reference
- `services/dispatch-optimizer-service/`
- ADR-002 (Dispatch Authority) & Issue Register C5-P0-10.

### Blast Radius & Business Impact
Orders sit packed in dark stores indefinitely without rider dispatch; delivery SLA breaches (exceeding 15-minute promise).

### Remediation Plan
1. Formally designate `dispatch-optimizer-service` as sole authority for order-to-rider matching.
2. Expose `/dispatch/assign` gRPC endpoint and consume packed order events.
3. Publish `RiderAssigned.v1.json` to `rider.events` topic upon successful bipartite match."""
    },
    {
        "milestone": 20,
        "title": "feat(inventory): daily automated stock reconciliation engine comparing reserved+on_hand vs WMS truth",
        "labels": ["kind/feature", "priority/p1-high", "area/data-platform", "java"],
        "body": """### Summary
There is no automated reconciliation mechanism between `inventory-service` PostgreSQL counters and physical dark store Warehouse Management System (WMS) barcode inventory audits.

### Evidence & Code Reference
- `services/inventory-service/`
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C4-P1-06).

### Blast Radius & Business Impact
Unchecked stock drift over weeks: inventory shows available items that are damaged or stolen, resulting in checkout cancellations.

### Remediation Plan
1. Create scheduled Spring Batch job running daily at 03:00 IST comparing system stock with physical WMS scan file.
2. Flag items with discrepancy (`abs(system_stock - physical_stock) > 0`).
3. Emit `StockDriftDetected` event and generate manual count audit tasks for dark store managers."""
    },
    {
        "milestone": 20,
        "title": "fix(fulfillment): pick task creation missing dark store zone aisle routing optimization",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
`PickTaskService` generates warehouse picking lists in random SKU order instead of sorting items by physical aisle sequence (S-shape pick path), increasing dark store picker walking distance by 300%.

### Evidence & Code Reference
- `services/fulfillment-service/src/main/java/com/instacommerce/fulfillment/service/PickTaskService.java`.

### Blast Radius & Business Impact
Packing time exceeds 5-minute target, breaching the overall 10-15 minute delivery SLA.

### Remediation Plan
1. Ingest dark store bin location coordinates (`aisle`, `shelf`, `bin`) into `fulfillment-service`.
2. Implement Traveling Salesperson (TSP) heuristic sorting pick tasks along standard S-curve pick path.
3. Measure pick duration per order in Prometheus timer."""
    },
    {
        "milestone": 20,
        "title": "fix(warehouse): dark store status transition to MAINTENANCE does not reject pending reservations",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
When an administrator changes a store status to `MAINTENANCE` or `INACTIVE` in `warehouse-service`, active checkout workflows in `checkout-orchestrator-service` continue creating reservations for the next 15 minutes.

### Evidence & Code Reference
- `services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/WarehouseService.java`.

### Blast Radius & Business Impact
Orders accepted for stores undergoing electrical maintenance or inventory stocktaking, resulting in mass customer cancellations.

### Remediation Plan
1. Publish `StoreStatusChanged.v1.json` event immediately to `warehouse.events` upon status change.
2. Invalidate store cache across `inventory-service`, `catalog-service`, and `checkout-orchestrator-service`.
3. Reject incoming checkout attempts for inactive stores with HTTP 422 `STORE_TEMPORARILY_CLOSED`."""
    },
    {
        "milestone": 20,
        "title": "feat(inventory): stock drift alert webhook triggering physical dark store cycle count audits",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform", "java"],
        "body": """### Summary
When an item is marked missing by a picker during packing, system inventory decrements, but no operational cycle count audit is scheduled to verify the remaining physical shelf stock.

### Evidence & Code Reference
- `services/fulfillment-service/src/main/java/com/instacommerce/fulfillment/service/FulfillmentService.java`.

### Blast Radius & Business Impact
Other customers order the remaining phantom units of the same broken or stolen SKU within minutes.

### Remediation Plan
1. Emit `PickItemMissing` event when picker reports missing unit.
2. `inventory-service` freezes available stock for that SKU in that dark store (`is_frozen = true`).
3. Generate urgent physical verification task on dark store mobile handheld device."""
    },
    {
        "milestone": 20,
        "title": "fix(dispatch): bipartite matching algorithm crashes when candidate rider pool is empty",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "go"],
        "body": """### Summary
`dispatch-optimizer-service` Kuhn-Munkres matching routine panics on nil matrix pointer when no active riders are checked in within a 5km radius of the dark store.

### Evidence & Code Reference
- `services/dispatch-optimizer-service/optimizer/matcher.go` line 65.

### Blast Radius & Business Impact
Service pod crash and failure to process subsequent dispatch assignments across all other dark stores in the city.

### Remediation Plan
1. Add guard clause checking `if len(availableRiders) == 0`: return `ErrNoRidersAvailable`.
2. Trigger re-evaluation timer (retry in 30 seconds).
3. If no rider found after 3 minutes, escalate to dispatch operations dashboard."""
    },
    {
        "milestone": 20,
        "title": "feat(fulfillment): multi-picker order batching protocol for high-velocity dark store aisles",
        "labels": ["kind/feature", "priority/p2-medium", "area/checkout-saga", "java"],
        "body": """### Summary
Pickers currently pick one order at a time (single-order picking). During rush hours, pickers cross paths constantly in the produce and beverage aisles.

### Evidence & Code Reference
- `services/fulfillment-service/src/main/java/com/instacommerce/fulfillment/service/PickTaskService.java`.

### Blast Radius & Business Impact
Sub-optimal picker throughput limiting dark store peak processing capacity to 300 orders/hour.

### Remediation Plan
1. Implement batch picking algorithm combining up to 4 small orders into a single multi-compartment cart run.
2. Guide picker to sort items into color-coded customer totes at the pick face.
3. Validate compartment barcode before proceeding to next aisle."""
    },
    {
        "milestone": 20,
        "title": "fix(rider): rider check-in status stale in Redis due to missing TTL heartbeat refresh",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "go"],
        "body": """### Summary
When a rider's phone battery dies or loses cell coverage, their status in `rider-fleet-service` remains `AVAILABLE` in Redis because heartbeat records lack an automated TTL expiration.

### Evidence & Code Reference
- `services/location-ingestion-service/store/redis.go`.

### Blast Radius & Business Impact
Dispatch optimizer repeatedly assigns orders to phantom offline riders, delaying customer delivery by 10+ minutes until assignment timeout.

### Remediation Plan
1. Set 60-second TTL on rider active status keys in Redis.
2. Require rider mobile app to send keepalive ping every 10 seconds.
3. Mark rider `OFFLINE` automatically when key expires."""
    },
    {
        "milestone": 20,
        "title": "feat(inventory): safety stock dynamic buffer based on historical SKU replenishment lead time",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform", "java"],
        "body": """### Summary
Safety stock threshold is hardcoded to 2 units for all items. Fast-moving SKUs (milk, bread) sell out before replenishment arrives, while slow-moving items hold unnecessary buffer.

### Evidence & Code Reference
- `services/inventory-service/src/main/java/com/instacommerce/inventory/domain/StockItem.java`.

### Blast Radius & Business Impact
Frequent stockouts on hero grocery categories, causing high cart abandonment.

### Remediation Plan
1. Calculate dynamic safety buffer per SKU: `safety_stock = z_score * std_dev_demand * sqrt(lead_time_days)`.
2. Ingest calculated thresholds daily from BigQuery analytics mart.
3. Trigger automated vendor purchase order when available stock hits dynamic threshold."""
    },
    {
        "milestone": 20,
        "title": "fix(warehouse): bin location duplicate assignment bug during concurrent inventory replenishment",
        "labels": ["kind/bug", "priority/p2-medium", "area/infrastructure", "java"],
        "body": """### Summary
When two warehouse associates scan inbound shipments simultaneously, both items can be assigned to the same physical bin location without a unique constraint check.

### Evidence & Code Reference
- `services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/BinService.java`.

### Blast Radius & Business Impact
Bin overcrowding and picker confusion when multiple distinct SKUs occupy the same physical bin slot.

### Remediation Plan
1. Add composite database constraint: `UNIQUE (store_id, aisle, rack, shelf, bin_number)`.
2. Check bin volume capacity before assigning secondary item.
3. Throw HTTP 409 `BIN_ALREADY_OCCUPIED` on duplicate assignment."""
    },
    {
        "milestone": 20,
        "title": "feat(dispatch): automated re-dispatch workflow on rider assignment rejection or timeout",
        "labels": ["kind/feature", "priority/p1-high", "area/checkout-saga", "go"],
        "body": """### Summary
If an assigned rider rejects an order offer or does not accept within 45 seconds, the order remains in an unassigned limbo state requiring manual dispatcher intervention.

### Evidence & Code Reference
- `services/dispatch-optimizer-service/`.

### Blast Radius & Business Impact
Unattended packed orders sitting at dark store staging shelves; severe delivery delays.

### Remediation Plan
1. Implement a 45-second timer on rider acceptance.
2. If timeout expires or rider rejects, automatically exclude that rider and trigger immediate second-best match.
3. Increment order dispatch attempt counter and alert lead dispatcher after 3 failed attempts."""
    },
    {
        "milestone": 20,
        "title": "fix(fulfillment): tote barcode scanning validation missing duplicate scan prevention",
        "labels": ["kind/bug", "priority/p2-medium", "area/checkout-saga", "java"],
        "body": """### Summary
When packing multi-unit items, accidental double-scanning of a single physical unit satisfies pick list requirements without verifying serial numbers or packaging weights.

### Evidence & Code Reference
- `services/fulfillment-service/src/main/java/com/instacommerce/fulfillment/service/PackingService.java`.

### Blast Radius & Business Impact
Customer receives fewer items than billed (e.g. billed for 2 apples, received 1), causing refund claims.

### Remediation Plan
1. Require 1000ms debounce on barcode scanner input events in mobile UI.
2. Integrate check-weigher scale verification at packing stations matching expected item tare weight (+/- 5%).
3. Log scanner hardware ID in pick audit logs."""
    },
    {
        "milestone": 20,
        "title": "feat(inventory): expiring perishable inventory automated markdown and liquidation trigger",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform", "java"],
        "body": """### Summary
Perishable items (fresh milk, berries, baked goods) approaching expiration dates are not automatically discounted, leading to high store waste and shrinkage costs.

### Evidence & Code Reference
- `services/inventory-service/` and `services/pricing-service/`.

### Blast Radius & Business Impact
Dark store food waste write-offs; financial inventory shrinkage.

### Remediation Plan
1. Track batch expiry dates (`expiry_date`) in `stock_batches` table.
2. When expiry is within 24 hours, emit `PerishableExpiringSoon` event to `pricing-service`.
3. Apply 50% discount and feature item in 'Save Food / Quick Sale' section on customer app."""
    },
    {
        "milestone": 20,
        "title": "fix(rider): rider fleet service database lock contention during concurrent location ping ingestion",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
`rider-fleet-service` attempts to update rider last-seen coordinates directly in PostgreSQL on every GPS ping, creating extreme database lock contention under 5,000 active riders.

### Evidence & Code Reference
- `services/rider-fleet-service/src/main/java/com/instacommerce/rider/service/RiderLocationService.java`.
- Glossary Section 1: 'Rider Ping - high-frequency GPS coordinate submitted to location-ingestion-service'.

### Blast Radius & Business Impact
PostgreSQL connection pool exhaustion; database crashes impacting all rider management operations.

### Remediation Plan
1. Ingest high-frequency rider pings strictly into Redis geospatial index (`GEOADD riders:active`).
2. Buffer location history in Kafka (`rider.location.updates`) for async batch storage in BigQuery.
3. Remove direct synchronous PostgreSQL updates from rider ping hot path."""
    },
    {
        "milestone": 20,
        "title": "feat(warehouse): cold-chain perishable staging temperature sensor telemetry threshold alerts",
        "labels": ["kind/feature", "priority/p2-medium", "area/infrastructure", "java"],
        "body": """### Summary
Refrigerated and frozen goods staging bags sit in ambient dark store dispatch areas without IoT temperature monitoring before rider pickup.

### Evidence & Code Reference
- `services/warehouse-service/`.

### Blast Radius & Business Impact
Melted ice cream and spoiled dairy delivered to customers during summer peak temperatures.

### Remediation Plan
1. Ingest Bluetooth/Zigbee temperature sensor telemetry into `warehouse-service`.
2. Alert warehouse manager if chilled staging bay temperature exceeds 4°C for > 5 minutes.
3. Require insulated thermal bags with cold-packs for all cold-chain order dispatches."""
    },
    {
        "milestone": 20,
        "title": "fix(fulfillment): dispatch handoff barcode verification missing cryptographic rider identity check",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
Dark store handoff to riders checks only the plain order number barcode. Any individual posing as a rider can scan an order bag and walk away with merchandise.

### Evidence & Code Reference
- `services/fulfillment-service/src/main/java/com/instacommerce/fulfillment/service/DispatchService.java`.

### Blast Radius & Business Impact
Order theft and shrinkage at busy dark store dispatch doors during evening rushes.

### Remediation Plan
1. Generate dynamic, time-based OTP or signed QR code in rider app.
2. Warehouse dispatch associate scans rider QR code before releasing order package.
3. Transition order to `OUT_FOR_DELIVERY` only after cryptographic rider match verification."""
    },
    {
        "milestone": 20,
        "title": "feat(dispatch): rider battery state of charge (SoC) integration in multi-delivery route selection",
        "labels": ["kind/feature", "priority/p2-medium", "area/checkout-saga", "go"],
        "body": """### Summary
Dispatch optimizer assigns long-distance (8km+) deliveries to electric 2-wheeler riders whose battery state of charge is below 15%, causing mid-trip vehicle breakdown.

### Evidence & Code Reference
- `services/dispatch-optimizer-service/optimizer/matcher.go`.

### Blast Radius & Business Impact
Rider strandings, delayed deliveries, and cold food spoilage.

### Remediation Plan
1. Ingest battery telemetry (`battery_percentage`, `vehicle_type`) from rider mobile app.
2. Filter out riders with < 20% battery from delivery assignments exceeding 3km.
3. Direct low-battery riders to nearby battery swapping stations."""
    },
    {
        "milestone": 20,
        "title": "fix(inventory): negative stock allocation bug on simultaneous split reservation and backorder cancel",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
When an order item is cancelled while an inventory split reservation is in progress, the quantity calculation subtracts without bounding to zero, causing negative on-hand counts.

### Evidence & Code Reference
- `services/inventory-service/src/main/java/com/instacommerce/inventory/domain/StockItem.java` `decrement()` method.

### Blast Radius & Business Impact
Inventory database corrupted; negative inventory counts block future stock replenishment receipts.

### Remediation Plan
1. Add domain model invariant: `if (quantity > this.reserved) throw new InsufficientStockException()`.
2. Add database check constraint: `ALTER TABLE stock_items ADD CONSTRAINT chk_on_hand_positive CHECK (on_hand >= 0);`.
3. Add unit test asserting negative deduction rejection."""
    },
    {
        "milestone": 20,
        "title": "feat(warehouse): automated pick path optimization using dark store physical layout graph",
        "labels": ["kind/feature", "priority/p2-medium", "area/checkout-saga", "java"],
        "body": """### Summary
Dark store layout aisles are treated as a flat list without modeling one-way aisles, dead ends, or heavy beverage pallet placement.

### Evidence & Code Reference
- `services/warehouse-service/src/main/java/com/instacommerce/warehouse/domain/StoreLayout.java`.

### Blast Radius & Business Impact
Sub-optimal picker routing and aisle congestion during peak order volume.

### Remediation Plan
1. Model dark store floorplan as a directed graph of aisle nodes and transit edges.
2. Implement Dijkstra shortest pick-path algorithm routing pickers logically from heavy goods to fragile bakery items.
3. Visualize path on picker handheld tablet."""
    },
    {
        "milestone": 20,
        "title": "fix(rider): geofence entry event false-positive deduplication around dark store dispatch bays",
        "labels": ["kind/bug", "priority/p2-medium", "area/checkout-saga", "go"],
        "body": """### Summary
GPS jitter causes riders waiting near dark store entrances to trigger dozens of `RiderArrivedAtStore` geofence events within seconds.

### Evidence & Code Reference
- `services/location-ingestion-service/handler/geofence.go`.

### Blast Radius & Business Impact
Kafka event flood on `rider.geofence.events` topic and redundant push notifications sent to dark store packing leads.

### Remediation Plan
1. Apply 3-minute hysteresis window on geofence entry/exit events per rider.
2. Require 3 consecutive GPS pings inside geofence boundary before confirming arrival.
3. Deduplicate events in Redis using key `geofence:{riderId}:{storeId}`."""
    },
    {
        "milestone": 20,
        "title": "feat(fulfillment): proof of delivery photo signature upload and storage retention policy",
        "labels": ["kind/feature", "priority/p2-medium", "area/checkout-saga", "java"],
        "body": """### Summary
When orders are delivered to customer doorsteps ('no-contact delivery'), riders take photos that are uploaded without compression, storage lifecycle, or PII masking.

### Evidence & Code Reference
- `services/fulfillment-service/src/main/java/com/instacommerce/fulfillment/service/DeliveryService.java`.

### Blast Radius & Business Impact
Uncontrolled Google Cloud Storage bucket size growth and customer privacy compliance concerns.

### Remediation Plan
1. Compress delivery photos on mobile device to WebP format (< 200KB).
2. Upload to private GCS bucket with signed URLs and 30-day lifecycle expiration rule.
3. Mask any detected human faces or license plates using automated image redaction."""
    },
    {
        "milestone": 20,
        "title": "fix(warehouse): dark store capacity overload alert trigger when pending orders exceed packing bays",
        "labels": ["kind/bug", "priority/p1-high", "area/infrastructure", "java"],
        "body": """### Summary
`warehouse-service` does not throttle incoming order reservations when the number of un-picked orders exceeds physical dark store staging shelf capacity.

### Evidence & Code Reference
- `services/warehouse-service/src/main/java/com/instacommerce/warehouse/service/WarehouseService.java`.

### Blast Radius & Business Impact
Dark store physical floor gridlock: orders pile up on floors, items get crushed, and delivery ETAs collapse.

### Remediation Plan
1. Define `max_concurrent_orders` limit per dark store in `warehouse-service`.
2. When pending orders hit 90% capacity, signal `checkout-orchestrator-service` to extend promised delivery ETA window by 15 minutes.
3. If 100% capacity is reached, temporarily pause new order acceptance for that micro-zone."""
    },
    {
        "milestone": 20,
        "title": "feat(inventory): supplier purchase order ASN (Advanced Shipping Notice) automated receiving ingest",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform", "java"],
        "body": """### Summary
Inbound supplier inventory deliveries are entered manually SKU by SKU by dark store inventory staff on receipt, taking 45 minutes per truck delivery.

### Evidence & Code Reference
- `services/inventory-service/src/main/java/com/instacommerce/inventory/controller/InboundReceivingController.java`.

### Blast Radius & Business Impact
Labor inefficiency, receiving bottlenecks, and delayed inventory availability on the customer app.

### Remediation Plan
1. Ingest EDI 856 / JSON Advanced Shipping Notices (ASN) from suppliers directly into `inventory-service`.
2. Allow warehouse staff to scan pallet master barcodes for one-click shipment receipt.
3. Automatically update available inventory and reconcile against purchase order."""
    },
    {
        "milestone": 20,
        "title": "fix(dispatch): dispatch assignment locks rider record indefinitely on unhandled panic",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "go"],
        "body": """### Summary
If a panic occurs inside `dispatch-optimizer-service` while holding a Redis mutex on a rider, the mutex lock remains active until expiration, rendering the rider unassignable.

### Evidence & Code Reference
- `services/dispatch-optimizer-service/handler/dispatch.go`.

### Blast Radius & Business Impact
Riders stranded in 'assignment in progress' state unable to receive delivery orders for up to 30 minutes.

### Remediation Plan
1. Wrap dispatch handler in Go `defer func() { if r := recover(); r != nil { mutex.Unlock() } }()` recovery pattern.
2. Shorten mutex lock TTL from 10 minutes to 30 seconds.
3. Emit Prometheus metric `dispatch.handler.panics` with immediate alert trigger."""
    }
]

print(f"Loaded {len(WAVE_2_ISSUES)} Wave 2 issues and {len(WAVE_3_ISSUES)} Wave 3 issues.")
