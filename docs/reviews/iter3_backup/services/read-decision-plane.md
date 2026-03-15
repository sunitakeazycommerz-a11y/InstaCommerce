# C3 Read & Decision Plane — Deep Implementation Guide

**Cluster:** C3 — Read & Decision Plane  
**Services:** `catalog-service`, `search-service`, `cart-service`, `pricing-service`  
**Author:** Principal Engineering Review — Iteration 3  
**Date:** 2026-03-07  
**Depends on:**
- `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md`
- `docs/reviews/catalog-service-review.md`
- `docs/reviews/search-service-review.md`
- `docs/reviews/cart-service-review.md`
- `docs/reviews/pricing-service-review.md`
- `docs/reviews/contracts-events-review.md`

---

## Executive summary

The C3 cluster is the read-hot surface of InstaCommerce. Every user interaction —  
browsing, searching, building a cart, and seeing a price — flows through it. At q-commerce  
latency targets (p99 < 150 ms browse, < 200 ms search, < 80 ms add-to-cart), correctness  
and performance are not separable concerns. A stale index or a cached price that lags a  
promotion by 60 seconds causes both business harm and user trust erosion.

The code shows a cluster that is **architecturally sound in intent but broken in four critical  
closed loops**:

| Loop | Current state | Severity |
|------|--------------|----------|
| Catalog → Search index pipeline | Outbox publisher is a logging stub; event payload is missing 7 fields search consumers require | **P0** |
| Cart → Pricing contract | `PricingClient` hardcodes wrong base URL; response field `unitPriceCents` vs contract field `priceCents`; no resilience | **P0** |
| Promotion / coupon safety | `validateCoupon` is `readOnly=true` inside `calculateCartPrice`; `redeemCoupon` is never called from pricing path; double-discount is possible | **P0** |
| Caching layer | All four services use JVM-local Caffeine; no Redis; N pods = N independent caches; no event-driven invalidation on pricing or promotions | **P1** |

The sections below describe each gap with line-level evidence, implementation options,  
migration steps, validation gates, and rollback plans.

---

## 1. Catalog truth and outbox pipeline

### 1.1 Current state

`catalog-service` stores the system-of-record for every product. The schema is clean:

```sql
-- V2__create_products.sql
products(id, sku, name, slug, description, category_id, brand,
         base_price_cents, currency, unit, unit_value, weight_grams,
         is_active, ..., search_vector, version)
```

The outbox table exists (`V5__create_outbox_events.sql`). `OutboxService.recordProductEvent()`  
writes rows transactionally. `OutboxPublishJob` polls every second (configurable via  
`catalog.outbox.publish-delay-ms`). So far correct.

**Critical gap — `LoggingOutboxEventPublisher`:**

```java
// LoggingOutboxEventPublisher.java — the ONLY OutboxEventPublisher bean
@Override
public void publish(OutboxEvent event) {
    log.info("Publishing outbox event id={} type={} ...", ...);
    // ↑ nothing else — no KafkaTemplate, no HTTP, no gRPC — just a log line
}
```

The entire Kafka publishing path is a stub. `catalog-service` has **no Kafka dependency**  
in `build.gradle.kts` and no Kafka configuration in `application.yml`. The outbox rows  
are written and marked `sent=true` but never reach Kafka.

**Critical gap — `ProductChangedEvent` payload mismatch:**

```java
// ProductChangedEvent.java — what catalog publishes
public record ProductChangedEvent(
    UUID productId, String sku, String name, String slug,
    UUID categoryId, boolean active
) {}
```

```java
// CatalogEventConsumer.java (search-service) — what search expects
UUID productId = UUID.fromString((String) event.get("productId"));
String name     = (String) event.get("name");
String description = (String) event.get("description");  // ← MISSING
String brand    = (String) event.get("brand");           // ← MISSING
String category = (String) event.get("category");        // ← categoryId exists, not name
long priceCents = ((Number) event.get("priceCents")).longValue(); // ← MISSING
String imageUrl = (String) event.get("imageUrl");        // ← MISSING
boolean inStock = (Boolean) event.get("inStock");        // ← MISSING
```

Seven fields that search-service's consumer expects are absent from the event payload.  
Even if Kafka publishing were wired, every event would produce a `NullPointerException`  
or `ClassCastException` in `CatalogEventConsumer.handleProductUpsert()` and the  
consumer would crash-loop.

**Critical gap — contract schema too thin:**

```json
// contracts/src/main/resources/schemas/catalog/ProductCreated.v1.json
{ "required": ["productId", "name", "sku", "createdAt"] }

// contracts/src/main/resources/schemas/catalog/ProductUpdated.v1.json
{ "required": ["productId", "updatedAt"] }
// ↑ updatedAt only — no changed fields, no price, no stock status
```

The `ProductUpdated` schema carries so little information that downstream consumers  
cannot act on it without a round-trip call back to catalog-service. This creates a  
fan-out HTTP dependency that defeats the purpose of event-driven architecture.

### 1.2 Root cause

The catalog-service was built with a `SearchProvider` abstraction (comment: "TODO: migrate  
from PostgreSQL tsvector to OpenSearch") and the placeholder `LoggingOutboxEventPublisher`  
was left in while that migration was planned. The migration never happened. The service  
went from prototype to production path with a logging stub as the only Kafka publisher.

### 1.3 Implementation — Option A: Kafka producer in catalog-service (recommended)

This is the lowest-blast-radius fix. It keeps catalog-service as the sole truth publisher.

**Step 1 — Add Kafka dependency:**

```kotlin
// services/catalog-service/build.gradle.kts
implementation("org.springframework.kafka:spring-kafka")
```

**Step 2 — Add Kafka config to application.yml:**

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1

catalog:
  kafka:
    product-events-topic: ${CATALOG_PRODUCT_EVENTS_TOPIC:catalog.events}
  outbox:
    publish-delay-ms: ${CATALOG_OUTBOX_PUBLISH_DELAY_MS:1000}
```

**Step 3 — Replace `LoggingOutboxEventPublisher` with a real publisher:**

```java
// KafkaOutboxEventPublisher.java
@Component
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaOutboxEventPublisher implements OutboxEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public KafkaOutboxEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${catalog.kafka.product-events-topic:catalog.events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(OutboxEvent event) {
        kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    // Re-throw so OutboxPublishJob rolls back the sent=true mark
                    throw new RuntimeException("Kafka send failed for event " + event.getId(), ex);
                }
                log.info("Published event id={} type={} to topic={}",
                    event.getId(), event.getEventType(), topic);
            });
    }
}
```

Keep `LoggingOutboxEventPublisher` as a fallback bean with `@ConditionalOnMissingBean`  
so it activates only when Kafka is absent (e.g., unit tests).

**Step 4 — Expand `ProductChangedEvent` to carry all fields search and pricing need:**

```java
// ProductChangedEvent.java — v2 payload (additive, backward compatible)
public record ProductChangedEvent(
    UUID productId,
    String sku,
    String name,
    String slug,
    UUID categoryId,
    String categoryName,   // resolved from Category entity
    String brand,
    String description,
    long basePriceCents,
    String currency,
    String imageUrl,       // primary image URL, null if none
    boolean active,
    boolean inStock        // derived: active && inventory > 0 (see §4.1)
) {}
```

The `active` field already exists. `inStock` requires coordination with inventory-service  
(see §4). For now, default `inStock = active` — this is better than null which causes  
NPE in search consumer.

**Step 5 — Update `OutboxService.recordProductEvent()` to use the expanded event:**

```java
@Transactional(propagation = Propagation.MANDATORY)
public void recordProductEvent(Product product, String eventType) {
    String imageUrl = product.getImages().stream()
        .filter(ProductImage::isPrimary)
        .findFirst()
        .map(ProductImage::getUrl)
        .orElse(null);
    String categoryName = product.getCategory() != null
        ? product.getCategory().getName() : null;

    ProductChangedEvent event = new ProductChangedEvent(
        product.getId(), product.getSku(), product.getName(), product.getSlug(),
        product.getCategory() != null ? product.getCategory().getId() : null,
        categoryName,
        product.getBrand(), product.getDescription(),
        product.getBasePriceCents(), product.getCurrency(),
        imageUrl, product.isActive(),
        product.isActive()   // inStock = active until inventory feed is wired
    );
    // ... outbox row creation unchanged
}
```

**Step 6 — Update contract schemas (additive, no version bump required):**

```json
// contracts/src/main/resources/schemas/catalog/ProductCreated.v1.json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ProductCreated",
  "type": "object",
  "required": ["productId", "name", "sku", "createdAt"],
  "properties": {
    "productId":      { "type": "string", "format": "uuid" },
    "name":           { "type": "string" },
    "sku":            { "type": "string" },
    "createdAt":      { "type": "string", "format": "date-time" },
    "categoryId":     { "type": "string", "format": "uuid" },
    "categoryName":   { "type": "string" },
    "brand":          { "type": "string" },
    "description":    { "type": "string" },
    "basePriceCents": { "type": "integer", "minimum": 0 },
    "currency":       { "type": "string", "maxLength": 3 },
    "imageUrl":       { "type": ["string", "null"], "format": "uri" },
    "active":         { "type": "boolean" },
    "inStock":        { "type": "boolean" }
  }
}
```

```json
// contracts/src/main/resources/schemas/catalog/ProductUpdated.v1.json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ProductUpdated",
  "type": "object",
  "required": ["productId", "updatedAt"],
  "properties": {
    "productId":      { "type": "string", "format": "uuid" },
    "updatedAt":      { "type": "string", "format": "date-time" },
    "name":           { "type": "string" },
    "categoryId":     { "type": "string", "format": "uuid" },
    "categoryName":   { "type": "string" },
    "brand":          { "type": "string" },
    "description":    { "type": "string" },
    "basePriceCents": { "type": "integer", "minimum": 0 },
    "imageUrl":       { "type": ["string", "null"] },
    "active":         { "type": "boolean" },
    "inStock":        { "type": "boolean" }
  }
}
```

Rebuild: `./gradlew :contracts:build`

### 1.4 Implementation — Option B: Debezium CDC from catalog DB (alternative)

Instead of a Kafka producer in catalog-service, deploy a Debezium connector that tails  
the `outbox_events` table. This is the standard "Debezium Outbox Event Router" pattern  
and is already partially set up in the platform (`docker-compose.yml` includes Debezium  
Kafka Connect).

**Pros:** Eliminates `LoggingOutboxEventPublisher` entirely; catalog-service needs  
zero Kafka client code; guaranteed at-least-once delivery from WAL.

**Cons:** Requires configuring a Debezium connector (JSON config deployment to Connect  
cluster); payload must be in the `payload` column as already structured; adds operational  
dependency on Debezium availability.

**Debezium connector config:**

```json
{
  "name": "catalog-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "${CATALOG_DB_HOST}",
    "database.port": "5432",
    "database.user": "${CATALOG_DB_USER}",
    "database.password": "${CATALOG_DB_PASSWORD}",
    "database.dbname": "catalog",
    "database.server.name": "catalog",
    "table.include.list": "public.outbox_events",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.topic.replacement": "catalog.events",
    "transforms.outbox.table.fields.additional.placement": "aggregate_type:header"
  }
}
```

With Option B, the `OutboxPublishJob` is no longer needed (remove it or disable it via  
feature flag). Debezium handles delivery and the `sent` column can be repurposed as  
a tombstone marker.

**Recommendation:** Use Option A first (faster, self-contained), then migrate to  
Option B once the Debezium connector configuration is verified for other services in  
the CDC pipeline. Do not do both simultaneously on the same outbox table.

### 1.5 Migration steps

| Step | Action | Owner | Validation |
|------|--------|-------|------------|
| 1 | Add Kafka dependency to catalog-service `build.gradle.kts` | Catalog team | `./gradlew :services:catalog-service:build -x test` passes |
| 2 | Add Kafka producer config to `application.yml` | Catalog team | `bootRun` locally, see Kafka producer log on startup |
| 3 | Implement `KafkaOutboxEventPublisher`; keep `LoggingOutboxEventPublisher` as fallback | Catalog team | Unit test: mock KafkaTemplate, verify `.send()` called |
| 4 | Expand `ProductChangedEvent` record with 7 new fields | Catalog team | No existing consumers are broken (additive) |
| 5 | Update `OutboxService.recordProductEvent()` | Catalog team | Integration test: create product → outbox row has full payload |
| 6 | Update contract JSON schemas | Platform/Contracts team | `./gradlew :contracts:build` |
| 7 | Deploy catalog-service to staging | Infra/SRE | Observe `catalog.events` topic in Kafka UI — events appear |
| 8 | Start search-service in staging pointing to same Kafka | Search team | Observe `search_documents` table gets populated |
| 9 | Run full-text search queries in staging → validate results | QA | At least 90% of catalogued products are findable |
| 10 | Deploy to production during low-traffic window | SRE | Monitor `search_documents` row count growing post-deploy |

### 1.6 Rollback

- Step 1–5 are backward compatible (additive fields, same outbox pattern).
- If `KafkaOutboxEventPublisher` fails at runtime, `OutboxPublishJob` catches and logs the error, marks the event unsent, retries next poll cycle. No data loss.
- Roll back catalog-service deployment if Kafka connectivity fails — `LoggingOutboxEventPublisher` becomes the active bean and service continues operating (events are lost for the downtime window but DB is intact).
- Rebuild search index from catalog DB directly using the bulk reindex endpoint (§2.4).

---

## 2. Search index freshness and relevance

### 2.1 Current state

`search-service` maintains its own `search_documents` table (PostgreSQL tsvector). The  
indexing path is:

```
catalog.events → CatalogEventConsumer → SearchIndexService.upsertDocument()
```

Once the Kafka pipeline is fixed (§1), the consumer logic itself has additional issues:

**Issue 1 — Coarse cache invalidation:**

```java
// SearchIndexService.java
@CacheEvict(value = {"searchResults", "autocomplete"}, allEntries = true)
public void upsertDocument(...) { ... }
```

Every product update flushes the **entire** `searchResults` and `autocomplete` caches  
across all 10,000 entries. During a flash sale (where hundreds of products are toggled  
`inStock=false`), this produces a cache stampede: all pods lose their caches  
simultaneously, and Caffeine being JVM-local means N pods each independently  
re-warm from PostgreSQL.

**Issue 2 — `in_stock = TRUE` hard filter without store context:**

```sql
-- SearchDocumentRepository.fullTextSearch()
WHERE sd.search_vector @@ plainto_tsquery('english', :query)
  AND sd.in_stock = TRUE
```

`in_stock` in `search_documents` is derived from the catalog event's `inStock` field.  
It carries no store or zone context. A product may be in stock at Store A but out of  
stock at Store B. The search result is the same for all users regardless of their  
delivery zone — a product shows as available when the user's nearest dark store has  
zero units.

**Issue 3 — No relevance tuning beyond tsvector weight:**

The trigger sets:
- Weight A: name  
- Weight B: brand, category  
- Weight C: description

`ts_rank()` is used but there is no:
- Boost for availability or recency
- Boost for conversion rate (trending)
- Penalty for low-rated items
- Personalization by user purchase history

`TrendingService.recordQuery()` exists but the trending signal is never fed back  
into search ranking.

**Issue 4 — No DLQ or error handling in `CatalogEventConsumer`:**

```java
@KafkaListener(topics = "catalog.events", groupId = "search-service")
public void handleCatalogEvent(Map<String, Object> event) {
    // ...
    case "ProductCreated", "ProductUpdated" -> handleProductUpsert(event);
    // handleProductUpsert throws on NPE → consumer offset is NOT committed
    // Spring Kafka will retry indefinitely (default ErrorHandler = LoggingErrorHandler)
}
```

A malformed event (or any product with a null `priceCents` field) will block the  
consumer partition permanently. No DLQ is configured.

### 2.2 Store-scoped index design

The correct approach for q-commerce is a store-scoped availability signal layered  
on top of the global text relevance index. Two viable strategies:

**Strategy A — Availability sidecar per store (recommended for scale):**

Add a separate `product_store_availability` table maintained by inventory events:

```sql
-- V5__create_product_store_availability.sql (search-service)
CREATE TABLE product_store_availability (
    product_id UUID      NOT NULL,
    store_id   VARCHAR(50) NOT NULL,
    in_stock   BOOLEAN   NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, store_id)
);
CREATE INDEX idx_psa_store_in_stock ON product_store_availability (store_id, in_stock)
    WHERE in_stock = true;
```

Search query becomes:

```sql
SELECT sd.*, ts_rank(...) AS rank
FROM search_documents sd
JOIN product_store_availability psa
  ON psa.product_id = sd.product_id
 AND psa.store_id = :storeId
 AND psa.in_stock = TRUE
WHERE sd.search_vector @@ plainto_tsquery('english', :query)
  AND (:brand IS NULL OR sd.brand = :brand)
  AND (:category IS NULL OR sd.category = :category)
  AND (:minPrice IS NULL OR sd.price_cents >= :minPrice)
  AND (:maxPrice IS NULL OR sd.price_cents <= :maxPrice)
ORDER BY rank DESC
```

The `storeId` is resolved from the user's delivery address via `warehouse-service` at  
the BFF layer and passed as a query parameter. The BFF already has this context (it  
resolves the serviceability zone for every request).

**Strategy B — `in_stock` array on `search_documents` (simpler, less scalable):**

Add a `available_store_ids TEXT[]` column to `search_documents` and use  
`@> ARRAY[:storeId]::TEXT[]` in the WHERE clause. Simpler but a high-SKU catalog  
with many stores produces wide arrays and expensive index scans.

**Recommendation:** Strategy A for any platform with more than 20 dark stores. Strategy B  
is acceptable during early rollout (< 5 stores) to reduce migration complexity.

### 2.3 Targeted cache invalidation

Replace `allEntries = true` with product-keyed eviction:

```java
// SearchIndexService.java — targeted eviction
@Transactional
@CacheEvict(value = "autocomplete", allEntries = true)  // autocomplete is prefix-keyed, hard to target
public void upsertDocument(UUID productId, ...) {
    // invalidate only the affected product's search cache entries
    // requires moving from Cacheable to manual Cache.evict() for searchResults
    Cache searchCache = cacheManager.getCache("searchResults");
    // searchResults cache key includes query+brand+category+price+page — cannot be
    // targeted per productId without a reverse index; use TTL expiry instead
}
```

The practical recommendation for `searchResults` with compound keys (query + filters + page)  
is **not to use Spring Cache at all** but instead rely on a short TTL write-through Redis  
cache keyed by `storeId:query:brand:category:minPrice:maxPrice:page:size`. This allows:

1. Global invalidation on `catalog.events` (set all keys matching `*:query:*` to expire in 5s  
   rather than immediate eviction — avoids stampede via TTL jitter)
2. Store-scoped invalidation when a store's inventory changes (evict `storeId:*`)

See §5 for the Redis caching strategy.

### 2.4 Bulk reindex endpoint (required for cold start and recovery)

The search-service currently has no way to rebuild the index from scratch. After deploying  
the Kafka fix, a one-time backfill is needed for all existing products.

Add a protected admin endpoint:

```java
// SearchController.java (admin)
@PostMapping("/admin/reindex")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<ReindexResponse> triggerReindex() {
    // Delegates to a ShedLock-protected job that calls catalog-service
    // GET /products (paginated) and upserts each document
    reindexJob.triggerAsync();
    return ResponseEntity.accepted().build();
}
```

The reindex job calls `catalog-service` via an internal HTTP client:

```java
// ReindexJob.java
@Scheduled(cron = "0 0 3 * * SUN")  // weekly full reindex at 3 AM Sunday
@SchedulerLock(name = "search-reindex", lockAtLeastFor = "PT5M", lockAtMostFor = "PT2H")
public void fullReindex() {
    int page = 0;
    Page<ProductSummary> batch;
    do {
        batch = catalogClient.listProducts(page, 500);
        batch.getContent().forEach(p ->
            searchIndexService.upsertDocument(p.productId(), p.name(), p.description(),
                p.brand(), p.categoryName(), p.basePriceCents(), p.primaryImageUrl(), p.active()));
        page++;
    } while (!batch.isLast());
    log.info("Reindex complete: {} products indexed", batch.getTotalElements());
}
```

### 2.5 DLQ configuration for catalog event consumer

```yaml
# search-service application.yml
spring:
  kafka:
    consumer:
      group-id: search-service
      auto-offset-reset: earliest
    listener:
      ack-mode: RECORD
      missing-topics-fatal: false

# Add to KafkaConfig.java:
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
        (record, ex) -> new TopicPartition("catalog.events.DLT", record.partition()));
    FixedBackOff backOff = new FixedBackOff(1000L, 3L);  // 3 retries, 1s apart
    return new DefaultErrorHandler(recoverer, backOff);
}
```

### 2.6 Relevance: incorporating trending signal

`TrendingService.recordQuery()` writes to `trending_queries` but the score is never  
read back into `fullTextSearch`. A simple hybrid scoring approach:

```sql
SELECT sd.*,
    ts_rank(sd.search_vector, plainto_tsquery('english', :query)) * 0.7 +
    COALESCE(tq.normalized_hit_count, 0) * 0.3 AS rank
FROM search_documents sd
LEFT JOIN (
    SELECT query, hit_count::float / MAX(hit_count) OVER () AS normalized_hit_count
    FROM trending_queries
    WHERE query ILIKE :query || '%'
    LIMIT 10
) tq ON TRUE
WHERE ...
ORDER BY rank DESC
```

This is a starting point. For production-grade relevance at q-commerce scale:
- Integrate purchase conversion rate per product (from `orders.events`)
- Add freshness decay (newer products score slightly higher)
- Use personalization signals from ML feature store (user's past categories)

---

## 3. Cart → Pricing contract closure

### 3.1 The hardcoded base URL bug

`PricingClient.java` hardcodes the base URL despite `application.yml` declaring  
`pricing-service.base-url`:

```java
// PricingClient.java line 31 — hardcoded
this.restTemplate = builder
    .rootUri("http://pricing-service:8087")   // ← ignores application.yml
    ...
```

```yaml
# cart-service application.yml — declared but ignored
pricing-service:
  base-url: ${PRICING_SERVICE_URL:http://pricing-service:8087}
```

The constructor takes `@Value("${internal.service.name:...}")` and  
`@Value("${internal.service.token:...}")` but has no `@Value` for the base URL.  
The hardcoded value happens to match the default, so this is invisible in vanilla  
Kubernetes but will break in any environment where the pricing service runs on a  
different hostname (e.g., staging, canary, or cross-namespace).

**Fix:**

```java
public PricingClient(RestTemplateBuilder builder,
                     @Value("${pricing-service.base-url:http://pricing-service:8087}") String baseUrl,
                     @Value("${internal.service.name:${spring.application.name}}") String serviceName,
                     @Value("${internal.service.token:dev-internal-token-change-in-prod}") String serviceToken) {
    this.restTemplate = builder
        .rootUri(baseUrl)   // ← use injected value
        ...
}
```

### 3.2 The fail-closed model: correct intent, missing resilience

`CartService.addItem()` calls `pricingClient.getPrice()`. If pricing-service is  
unavailable, it throws `ApiException(SERVICE_UNAVAILABLE)` which rejects the add-to-cart  
request entirely. The comment says "fail-closed" — this is intentional and correct for  
preventing price manipulation.

However, there is no circuit breaker. If pricing-service becomes slow or intermittently  
unavailable, every add-to-cart request piles up waiting for the 3s read timeout.  
Under load, this starves the Hikari connection pool (set to `maximum-pool-size: 30`).

**Add Resilience4j circuit breaker:**

```yaml
# cart-service application.yml
resilience4j:
  circuitbreaker:
    instances:
      pricingClient:
        slidingWindowSize: 20
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 5
        slidingWindowType: COUNT_BASED
  timelimiter:
    instances:
      pricingClient:
        timeoutDuration: 2s
```

```java
// PricingClient.java
@CircuitBreaker(name = "pricingClient", fallbackMethod = "getPriceFallback")
@TimeLimiter(name = "pricingClient")
public PriceResponse getPrice(UUID productId) { ... }

private PriceResponse getPriceFallback(UUID productId, Throwable t) {
    // Fail-closed: re-throw as service unavailable, do not return a default price
    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE",
        "Pricing service temporarily unavailable. Please retry.");
}
```

### 3.3 Price staleness at cart validation time

The cart stores `unit_price_cents` at add-time. The `validateCart()` path checks only  
expiry and emptiness — it never re-fetches prices. At checkout, if a promotion expired  
during the 24h cart TTL, the user sees the promotional price but the order is placed  
at the current (higher) price. This creates a billing discrepancy.

**Add price staleness check to `CartService.validateCart()`:**

```java
public CartResponse validateCart(UUID userId) {
    Cart cart = cartStore.validateCart(userId);  // existing expiry + empty checks

    // Re-price all items against current pricing-service prices
    List<PriceDiscrepancy> discrepancies = new ArrayList<>();
    for (CartItem item : cart.getItems()) {
        PricingClient.PriceResponse current = pricingClient.getPrice(item.getProductId());
        if (current.unitPriceCents() != item.getUnitPriceCents()) {
            discrepancies.add(new PriceDiscrepancy(
                item.getProductId(), item.getUnitPriceCents(), current.unitPriceCents()));
            // Update stored price to current
            item.setUnitPriceCents(current.unitPriceCents());
        }
    }

    if (!discrepancies.isEmpty()) {
        cartStore.saveItems(cart);  // persist re-priced items
        CartResponse response = toResponse(cart);
        // Surface discrepancies to caller so BFF can notify user
        return response.withPriceDiscrepancies(discrepancies);
    }
    return toResponse(cart);
}
```

Add `price_captured_at TIMESTAMPTZ` to `cart_items`:

```sql
-- V5__add_price_captured_at.sql
ALTER TABLE cart_items ADD COLUMN price_captured_at TIMESTAMPTZ NOT NULL DEFAULT now();
```

### 3.4 Contract mismatch: pricing-service GET /products/{id} vs cart PricingClient

`PricingController.getProductPrice()` returns `PricedItem`:

```java
// PricingController.java
@GetMapping("/products/{id}")
public ResponseEntity<PricedItem> getProductPrice(@PathVariable UUID id) {
    long priceCents = pricingService.calculatePrice(id);
    PricedItem item = new PricedItem(id, priceCents, 1, priceCents);
    return ResponseEntity.ok(item);
}
```

`PricedItem` is: `{ productId, unitPriceCents, quantity, lineTotalCents }`

`PricingClient.PriceResponse` is: `{ productId, unitPriceCents, currency }`

The `currency` field is absent from `PricedItem`. The current deserialization works  
because `@JsonIgnoreProperties(ignoreUnknown = true)` is applied implicitly by  
Jackson for records, but the currency is lost. For a multi-currency platform  
(INR default but possible USD/AED for future expansion), this is a latent bug.

**Fix — align the response DTO:**

```java
// PricedItem.java — add currency
public record PricedItem(
    UUID productId,
    long unitPriceCents,
    int quantity,
    long lineTotalCents,
    String currency         // add
) {}
```

Simultaneously, add an endpoint that pricing-service can use going forward:

```
GET /api/v1/prices/{productId}
```

This matches what `PricingClient` expects (`/api/v1/prices/{productId}`) but  
`PricingController` currently exposes `/pricing/products/{id}`. Either path is fine  
but they must be consistent. Preferred: add the `/api/v1/` prefix during the API  
versioning rollout.

### 3.5 Zero-price bug in pricing-service CatalogEventConsumer

```java
// CatalogEventConsumer.java (pricing-service)
if (priceRuleRepository.findActiveRuleByProductId(event.id(), now).isEmpty()) {
    PriceRule rule = new PriceRule();
    rule.setProductId(event.id());
    rule.setBasePriceCents(0);   // ← BUG: zero price for all new products
    rule.setMultiplier(BigDecimal.ONE);
    rule.setActive(true);
    priceRuleRepository.save(rule);
}
```

Every product created via Kafka event gets a `basePriceCents = 0`. The intent was to  
create a placeholder rule for the product to exist in pricing-service's database. But  
any call to `calculatePrice()` for this product returns 0, and it is cached in  
`productPrices` cache for 30 seconds.

**Fix — use the price from the catalog event payload:**

With the expanded `ProductChangedEvent` (§1.3 Step 4), the catalog event now carries  
`basePriceCents`. Use it:

```java
// CatalogEventConsumer.java (pricing-service) — updated
@KafkaListener(topics = "catalog.events", groupId = "pricing-service-catalog")
@Transactional
public void onCatalogEvent(ConsumerRecord<String, String> record) {
    try {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        String eventType = envelope.eventType();
        if (!"ProductCreated".equals(eventType) && !"ProductUpdated".equals(eventType)) {
            return;
        }
        CatalogProductEvent event = objectMapper.treeToValue(envelope.payload(), CatalogProductEvent.class);
        if (event.id() == null) return;

        Instant now = Instant.now();
        long priceCents = event.basePriceCents() > 0 ? event.basePriceCents() : 0;

        if ("ProductCreated".equals(eventType)) {
            if (priceRuleRepository.findActiveRuleByProductId(event.id(), now).isEmpty()) {
                PriceRule rule = new PriceRule();
                rule.setProductId(event.id());
                rule.setBasePriceCents(priceCents);  // ← use actual price
                rule.setEffectiveFrom(now);
                rule.setRuleType("STANDARD");
                rule.setMultiplier(BigDecimal.ONE);
                rule.setActive(true);
                priceRuleRepository.save(rule);
                log.info("Created price rule for product id={} price={}", event.id(), priceCents);
            }
        } else {
            // ProductUpdated — update the base price if changed
            priceRuleRepository.findActiveRuleByProductId(event.id(), now)
                .ifPresent(rule -> {
                    if (priceCents > 0 && rule.getBasePriceCents() != priceCents) {
                        rule.setBasePriceCents(priceCents);
                        priceRuleRepository.save(rule);
                        log.info("Updated price rule for product id={} price={}", event.id(), priceCents);
                    }
                });
        }
    } catch (Exception ex) {
        log.error("Failed to process catalog event at offset={}", record.offset(), ex);
        // Do NOT rethrow — let DLT handle
    }
}
```

Also add a DLT configuration to pricing-service's Kafka consumer (same pattern as §2.5).

---

## 4. Promo and coupon safety

### 4.1 The TOCTOU gap in coupon redemption

The pricing flow for cart checkout is:

```
CartService.validateCart()
  → PricingClient.getPrice() per item   (single-item price, no coupon)

Checkout orchestrator calls:
  → POST /pricing/calculate  { items, userId, couponCode }
    → PricingService.calculateCartPrice()
      → CouponService.validateCoupon()   (@Transactional(readOnly=true))
        → returns discountCents, does NOT decrement counter
      → returns PriceCalculationResponse with couponDiscount
  → checkout-orchestrator uses the discount in the order total
  → ???  WHO CALLS redeemCoupon() ???
```

`CouponService.redeemCoupon()` (which decrements `total_redeemed` and inserts a  
`coupon_redemptions` row under `SELECT FOR UPDATE`) is **never called** in the  
pricing path. It is a public method with no callers other than tests.

The result: a user can apply the same coupon to 10 carts simultaneously. Each  
`calculateCartPrice()` call sees `total_redeemed < total_limit` (readOnly, no lock)  
and grants the discount. The order goes through 10 times and the coupon is over-redeemed.

**Fix — two-phase redemption protocol:**

Phase 1 (at checkout initiation — `POST /pricing/calculate`):
- `validateCoupon()` is called as now (readOnly), returns validation result
- Additionally write a `coupon_reservation` row:
  ```sql
  -- V6__create_coupon_reservations.sql
  CREATE TABLE coupon_reservations (
      id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      coupon_id       UUID         NOT NULL REFERENCES coupons(id),
      user_id         UUID         NOT NULL,
      checkout_id     UUID         NOT NULL UNIQUE,
      discount_cents  BIGINT       NOT NULL,
      reserved_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
      expires_at      TIMESTAMPTZ  NOT NULL DEFAULT now() + INTERVAL '15 minutes',
      CONSTRAINT uq_coupon_user_checkout UNIQUE (coupon_id, user_id, checkout_id)
  );
  CREATE INDEX idx_coupon_res_expires ON coupon_reservations (expires_at)
      WHERE expires_at > now();
  ```
- This is a soft reservation, not a final redemption.

Phase 2 (at order confirmation — called by checkout-orchestrator):
- `CouponService.redeemCoupon(code, userId, orderId, discountCents)` is called
- The `SELECT FOR UPDATE` lock ensures atomicity
- The reservation row is deleted; the `coupon_redemptions` row is inserted

Phase rollback (if checkout is abandoned or payment fails):
- The reservation expires automatically after 15 minutes
- A `CouponReservationCleanupJob` runs every 5 minutes to evict expired reservations:
  ```java
  @Scheduled(cron = "0 */5 * * * *")
  @SchedulerLock(name = "coupon-reservation-cleanup", ...)
  @Transactional
  public void expireReservations() {
      couponReservationRepository.deleteExpired(Instant.now());
  }
  ```

### 4.2 Promotion best-of selection is correct but not collision-safe

`PricingService.calculateCartPrice()` iterates all active promotions and picks the  
highest discount. This is the correct "best offer" UX. However:

```java
for (Promotion promotion : promotionService.findActivePromotions()) {
    if (now.isBefore(promotion.getStartAt()) || !now.isBefore(promotion.getEndAt())) {
        continue;  // ← double-checks validity even though findActivePromotions filters active=true
    }
    // ...
}
```

`findActivePromotions()` is cached with a 60-second TTL. A promotion that expired  
1 second ago may still be in the cache and pass the `promotion.isActive()` filter.  
The `now.isBefore(promotion.getEndAt())` guard inside the loop catches this correctly —  
the double check is actually a safety net for cache lag. The code is correct here.

**However**, `max_uses` is not checked in `findActivePromotions()` or in the loop:

```java
// PricingService.java — no max_uses check!
if (subtotalCents < promotion.getMinOrderCents()) { continue; }
// ↑ no check for promotion.getMaxUses() vs promotion.getCurrentUses()
```

A promotion with `max_uses = 1000` and `current_uses = 1000` will still be applied  
because `current_uses` is not validated before the discount is applied. `redeemCoupon()`  
increments `total_redeemed` on coupons but there is no equivalent for promotions.

**Fix — add max_uses guard:**

```java
// PricingService.calculateCartPrice() — in the promotion loop
if (promotion.getMaxUses() != null
        && promotion.getCurrentUses() >= promotion.getMaxUses()) {
    continue;  // promotion exhausted
}
```

And increment `current_uses` at order confirmation (checkout-orchestrator calls a  
new endpoint `POST /pricing/promotions/{id}/use`). Use optimistic locking or a  
DB-level `UPDATE ... WHERE current_uses < max_uses RETURNING id` pattern.

### 4.3 Coupon stacking vulnerability

`calculateCartPrice()` applies one coupon **after** the promotion discount:

```java
long couponDiscount = couponService.validateCoupon(
    couponCode, userId, subtotalCents - promotionDiscount);   // ← coupon on discounted total
```

This is correct — coupons are applied on the post-promotion price, not the pre-promotion  
subtotal. This prevents double-counting on the full subtotal. However, a user can  
apply both a promotion AND a coupon. Depending on business policy, this may or may not  
be intended. Add a `stackable` flag to `Promotion`:

```sql
ALTER TABLE promotions ADD COLUMN stackable_with_coupon BOOLEAN NOT NULL DEFAULT true;
```

And check it in `calculateCartPrice()`:

```java
if (bestPromo != null && !bestPromo.isStackableWithCoupon() && request.couponCode() != null) {
    // Business decision: either reject coupon or skip promotion
    // Recommended: apply whichever gives greater discount, not both
    couponDiscount = 0;
}
```

---

## 5. Caching architecture

### 5.1 Current state — all four services use JVM-local Caffeine only

| Service | Cache names | Size | TTL | Redis? |
|---------|------------|------|-----|--------|
| catalog-service | `products`, `search`, `categories` | N/A | N/A | No — no cache config in yml, build artifacts show CacheConfig class |
| search-service | `searchResults`, `autocomplete`, `trending` | 10k / 5k / 100 | 5m / 2m / 1m | No |
| cart-service | `carts` | 50k | 60m | No |
| pricing-service | `productPrices`, `activePromotions`, `priceRules` | 10k / 500 / 10k | 30s / 60s / 30s | No |

With N replicas per service, there are N independent caches. An update to a product  
invalidates the entry on **one pod only** — the pod that handled the write request.  
The other N-1 pods serve stale data until TTL expiry.

For pricing-service with a 30-second TTL on `productPrices`, this means any pod  
that hasn't seen the price update will serve the old price for up to 30 seconds.  
During a flash sale start (price drops from ₹499 to ₹99), some users see the  
promotional price and others see the original price depending on which pod they hit.

For cart-service with a 60-minute TTL, a cart update on one pod is invisible to other  
pods for up to an hour — meaning `validateBusinessRules()` sees a stale cart and  
allows over-limit adds.

### 5.2 Target caching architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Redis Cluster                            │
│  Key pattern             TTL       Eviction trigger          │
│  ─────────────────────── ───────── ──────────────────────── │
│  cart:{userId}           5m        CartService write         │
│  price:{productId}       30s       catalog.events consumer   │
│  promo:active            60s       AdminPromotionController  │
│  search:{store}:{hash}   120s      catalog.events consumer   │
│  autocomplete:{prefix}   120s      catalog.events consumer   │
└──────────────────────────────────────────────────────────────┘
         ↑ write-through      ↑ event-driven invalidation
```

**Layer 1 — Redis as shared cache (replace Caffeine for shared state):**

```yaml
# cart-service application.yml
spring:
  cache:
    type: redis
  data:
    redis:
      host: ${REDIS_HOST:redis}
      port: ${REDIS_PORT:6379}
      timeout: 1000ms
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5
```

```java
// cart-service CacheConfig.java
@Bean
public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
    RedisCacheConfiguration cartConfig = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(5))
        .serializeValuesWith(RedisSerializationContext.SerializationPair
            .fromSerializer(new GenericJackson2JsonRedisSerializer()));
    return RedisCacheManager.builder(factory)
        .withCacheConfiguration("carts", cartConfig)
        .build();
}
```

**Layer 2 — Caffeine as L1 (read-aside) for ultra-hot data:**

For pricing (30s TTL, extremely hot), keep Caffeine as L1 in front of Redis L2:

```java
// pricing-service: L1 (Caffeine) → L2 (Redis) read-aside
@Cacheable(value = "productPrices", key = "#productId",
           cacheManager = "caffeineCacheManager")  // L1 only
public long calculatePrice(UUID productId) { ... }

// On cache miss, Redis is checked before DB via a separate CacheResolver
```

This two-tier approach keeps per-pod memory usage bounded while ensuring cross-pod  
consistency on Redis.

**Layer 3 — Event-driven Redis invalidation:**

Add a Kafka consumer in pricing-service and search-service that listens to  
`catalog.events` and invalidates Redis keys:

```java
// pricing-service: CatalogEventConsumer addition
@KafkaListener(topics = "catalog.events", groupId = "pricing-cache-invalidation")
public void onCatalogEvent(String payload) {
    // Parse productId from payload
    // cacheManager.getCache("productPrices").evict(productId);
    // cacheManager.getCache("priceRules").evict(productId);
}
```

This ensures price cache is invalidated within Kafka consumer lag time (typically  
< 100ms) rather than waiting for TTL expiry.

### 5.3 Redis connectivity failure mode

All services must degrade gracefully if Redis is unavailable:

```java
// CacheConfig.java — resilient Redis configuration
@Bean
public CacheErrorHandler cacheErrorHandler() {
    return new SimpleCacheErrorHandler() {
        @Override
        public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
            log.warn("Redis cache GET failed for cache={} key={}: {}", cache.getName(), key, ex.getMessage());
            // Allow method to execute normally (cache miss fallback)
        }
        @Override
        public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
            log.warn("Redis cache PUT failed for cache={} key={}: {}", cache.getName(), key, ex.getMessage());
            // Continue without caching the result
        }
    };
}
```

Redis failure must not fail user requests. All caches are optional acceleration layers,  
not correctness dependencies.

---

## 6. Low-latency read decision budget

### 6.1 q-commerce latency targets

| Operation | p50 target | p99 target | Current bottleneck |
|-----------|-----------|-----------|-------------------|
| Product detail (catalog) | 20ms | 60ms | DB query + no cache in catalog-service |
| Search | 50ms | 150ms | PostgreSQL tsvector + no store scoping |
| Autocomplete | 20ms | 60ms | ILIKE pattern scan (partial) |
| Cart fetch | 10ms | 40ms | Caffeine hit; Redis will be faster at scale |
| Price fetch (single) | 15ms | 50ms | Caffeine L1, 30s TTL |
| Add to cart | 100ms | 300ms | pricing-service HTTP roundtrip (3s timeout!) |
| Cart validate | 200ms | 600ms | N × pricing HTTP calls (one per item) |

The `cart validate` path is `O(N)` in pricing calls. For a cart with 10 items, that  
is 10 sequential HTTP calls to pricing-service. At 3s timeout each, worst case is  
30 seconds of latency.

### 6.2 Batch pricing endpoint (critical path fix)

The current `GET /pricing/products/{id}` endpoint is called per-item. Add a batch endpoint:

```java
// PricingController.java
@PostMapping("/products/batch-price")
@RateLimiter(name = "pricingLimiter")
public ResponseEntity<Map<UUID, PricedItem>> batchPrice(
        @RequestBody @Valid BatchPriceRequest request) {
    Map<UUID, Long> prices = pricingService.calculatePrices(request.productIds());
    Map<UUID, PricedItem> response = prices.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> new PricedItem(e.getKey(), e.getValue(), 1, e.getValue(), "INR")));
    return ResponseEntity.ok(response);
}
```

And in `PricingService`:

```java
// batch fetch with single DB query
@Cacheable(value = "productPrices", key = "#productIds.toString()")
@Transactional(readOnly = true)
public Map<UUID, Long> calculatePrices(List<UUID> productIds) {
    Instant now = Instant.now();
    return priceRuleRepository.findActiveRulesForProducts(productIds, now)
        .stream()
        .collect(Collectors.toMap(
            PriceRule::getProductId,
            rule -> applyMultiplier(rule.getBasePriceCents(), rule.getMultiplier())));
}
```

`CartService.validateCart()` then uses a single HTTP call:

```java
// Single batch call instead of N individual calls
Map<UUID, PricingClient.PriceResponse> prices = pricingClient.batchGetPrices(
    cart.getItems().stream().map(CartItem::getProductId).toList());
```

This reduces validate latency from `O(N × 50ms)` to `O(50ms)` regardless of cart size.

### 6.3 Connection pool sizing

Current Hikari settings vs q-commerce read load:

| Service | max-pool-size | min-idle | Notes |
|---------|--------------|----------|-------|
| catalog-service | 20 | 5 | Search queries can be slow; increase to 30 |
| search-service | 50 | 20 | Correctly sized for high search volume |
| cart-service | 30 | 10 | Adequate for cart ops; Redis reduces DB hits |
| pricing-service | 20 | 5 | Increase to 30 for batch pricing under load |

search-service also sets `statement_timeout=5000` (5s) — this is too long for a  
read-heavy service. Reduce to `statement_timeout=2000` for search queries; use a  
separate connection pool property for indexing writes if needed.

### 6.4 PostgreSQL index coverage for search

The existing tsvector GIN index covers full-text search well. Additional partial indexes  
to add for q-commerce browse patterns:

```sql
-- V5__add_search_composite_indexes.sql (search-service)
-- Used by category browse + in-stock filter (common q-commerce pattern)
CREATE INDEX idx_sd_category_stock ON search_documents (category, in_stock)
    WHERE in_stock = true;

-- Used by brand filter
CREATE INDEX idx_sd_brand_stock ON search_documents (brand, in_stock)
    WHERE in_stock = true;

-- Used by price range filter with in_stock
CREATE INDEX idx_sd_price_stock ON search_documents (price_cents, in_stock)
    WHERE in_stock = true;

-- Covering index for autocomplete (name prefix search + name output)
-- V4__add_autocomplete_index.sql already adds idx_search_documents_name_pattern
-- Add covering index to avoid heap fetch:
CREATE INDEX idx_sd_autocomplete_covering
    ON search_documents (name text_pattern_ops, category, product_id)
    WHERE in_stock = true;
```

---

## 7. Observability gaps and SLO instrumentation

### 7.1 Missing metrics

| Metric | Service | Why needed |
|--------|---------|------------|
| `search.cache.hit_ratio` | search-service | Cache effectiveness; should be > 80% |
| `pricing.cache.hit_ratio` | pricing-service | Detect cache thrashing during promos |
| `cart.pricing_call.latency` | cart-service | Detect pricing-service degradation |
| `search.index.lag_seconds` | search-service | Time between catalog event and index update |
| `search.query.no_results_rate` | search-service | Relevance quality signal |
| `coupon.redemption.rejected_duplicate` | pricing-service | Detect coupon abuse |
| `pricing.price_mismatch.count` | cart-service | Fraud detection signal |

Add to each service's Micrometer instrumentation:

```java
// search-service: SearchService.java
private final MeterRegistry meterRegistry;

public SearchResponse search(...) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        SearchResponse response = ...;
        if (response.totalElements() == 0) {
            meterRegistry.counter("search.query.no_results", "query", sanitize(query)).increment();
        }
        return response;
    } finally {
        sample.stop(meterRegistry.timer("search.query.latency"));
    }
}
```

### 7.2 Index freshness SLO

Define a lag SLO: search index should reflect catalog changes within 30 seconds  
under normal conditions (p95).

Track with:

```java
// SearchIndexService.upsertDocument() — record lag
public void upsertDocument(UUID productId, ..., Instant eventTime) {
    long lagMs = Instant.now().toEpochMilli() - eventTime.toEpochMilli();
    meterRegistry.timer("search.index.lag").record(lagMs, TimeUnit.MILLISECONDS);
    if (lagMs > 30_000) {
        log.warn("Search index lag exceeded 30s: lagMs={} productId={}", lagMs, productId);
    }
    ...
}
```

Alert: fire page if p95 lag > 60s for 5 consecutive minutes.

---

## 8. Migration sequence and wave assignment

This cluster is assigned to **Wave 3** in the iteration 3 program. Prerequisites:

- Wave 0/1 (Edge & Identity, Money Path) must be complete before Wave 3 deploys  
  changes that affect checkout-orchestrator → pricing-service coupon redemption path.

### 8.1 Recommended sequencing within Wave 3

| Phase | Deliverable | Risk | Rollback |
|-------|------------|------|----------|
| W3-C3-1 | Fix `LoggingOutboxEventPublisher` → `KafkaOutboxEventPublisher` | Medium (new Kafka dependency) | Remove Kafka dep, redeploy catalog-service |
| W3-C3-2 | Expand `ProductChangedEvent` payload + update contract schemas | Low (additive) | N/A — old consumers ignore new fields |
| W3-C3-3 | Fix zero-price bug in pricing-service `CatalogEventConsumer` | Medium (pricing data change) | Feature flag; default to no price update from events |
| W3-C3-4 | Fix `PricingClient` base URL injection | Low | Hardcoded default was correct value anyway |
| W3-C3-5 | Add circuit breaker to `PricingClient` | Low | Remove Resilience4j annotation if circuit opens unexpectedly |
| W3-C3-6 | Implement batch pricing endpoint | Medium (new endpoint) | Clients fall back to single-item GET |
| W3-C3-7 | Add price staleness check to `validateCart()` | Medium (changes checkout UX) | Feature flag `cart.reprice-on-validate=false` |
| W3-C3-8 | Add `coupon_reservations` table + two-phase redemption | High (schema + flow change) | Feature flag; fall back to readOnly validate only |
| W3-C3-9 | Migrate cart cache to Redis | High (infra dependency) | Caffeine fallback via `@Primary` bean swap |
| W3-C3-10 | Migrate pricing cache to Redis L2 + Caffeine L1 | High (infra dependency) | Same as W3-C3-9 |
| W3-C3-11 | Add store-scoped search index (availability sidecar) | High (schema + query change) | Feature flag `search.store-scoped=false` → fall back to global in_stock |
| W3-C3-12 | Bulk reindex endpoint + weekly scheduled reindex | Low | No state impact; worst case re-runs |

### 8.2 Feature flags for each risky phase

Use `config-feature-flag-service` to gate the high-risk phases:

```yaml
# Feature flags for C3 cluster
flags:
  c3.kafka-outbox-enabled: true           # W3-C3-1
  c3.cart-reprice-on-validate: false      # W3-C3-7 (off initially)
  c3.coupon-two-phase: false              # W3-C3-8 (off initially)
  c3.cart-redis-cache: false              # W3-C3-9 (off initially)
  c3.search-store-scoped: false           # W3-C3-11 (off initially)
```

Each flag defaults `false` (safe state = old behavior). Enable in staging first, run  
validation (§8.3), then enable in production.

### 8.3 Validation gates per phase

**W3-C3-1 (Kafka publisher):**
- Assert `catalog.events` topic shows new messages within 5s of product create/update in staging
- Assert search-service `search_documents` row count grows after catalog inserts
- Assert no `NullPointerException` in search-service consumer logs

**W3-C3-3 (Zero-price fix):**
- Create test product via catalog-service admin API
- Wait 5s for event propagation
- Call `GET /pricing/products/{id}` — assert `unitPriceCents` matches product's `basePriceCents`
- Assert `productPrices` cache is populated with correct value

**W3-C3-7 (Price staleness check):**
- Add item at price X, manually update price to Y in pricing-service DB
- Evict price cache
- Call `POST /cart/validate` — assert response includes price discrepancy alert
- Assert `unit_price_cents` in `cart_items` is updated to Y

**W3-C3-8 (Coupon two-phase):**
- Concurrently submit 5 checkout requests with same coupon code, same user, `total_limit=1`
- Assert exactly 1 succeeds; 4 return 409 or equivalent
- Assert `coupon_reservations` table has 1 row; `coupon_redemptions` has 1 row after order confirm

**W3-C3-11 (Store-scoped search):**
- Create product with `in_stock=true` in `product_store_availability` for Store A only
- Search from Store A zone → product appears in results
- Search from Store B zone → product absent from results
- Assert `search.index.lag` metric p95 < 30s

### 8.4 Rollback decision matrix

| Phase | Rollback trigger | Rollback action |
|-------|-----------------|-----------------|
| W3-C3-1 | > 1% error rate on catalog mutations | Redeploy previous catalog-service; outbox rows accumulate and will be re-processed |
| W3-C3-3 | Price rules with `base_price_cents = 0` appearing in production | Run: `UPDATE price_rules SET active=false WHERE base_price_cents = 0 AND created_at > '<deploy_time>'` |
| W3-C3-7 | Checkout abandonment rate increases > 5% | Flip `c3.cart-reprice-on-validate=false` flag |
| W3-C3-8 | Coupon validation error rate > 2% | Flip `c3.coupon-two-phase=false` flag |
| W3-C3-9 | Redis connectivity > 0.1% error rate | Flip `c3.cart-redis-cache=false`; Caffeine reactivates |
| W3-C3-11 | Search result count drops > 20% | Flip `c3.search-store-scoped=false`; global in_stock filter reactivates |

---

## 9. Summary of critical fixes and priorities

| Priority | Issue | Service | Fix reference |
|----------|-------|---------|---------------|
| **P0** | `LoggingOutboxEventPublisher` is a stub — no events reach Kafka | catalog-service | §1.3 |
| **P0** | `ProductChangedEvent` missing 7 fields required by search consumer | catalog-service | §1.3 Step 4 |
| **P0** | `ProductCreated.v1.json` / `ProductUpdated.v1.json` schemas too thin to be useful | contracts | §1.3 Step 6 |
| **P0** | Zero-price bug: all new products created via event get `basePriceCents=0` | pricing-service | §3.5 |
| **P0** | `redeemCoupon()` is never called; coupon limits are not enforced | pricing-service | §4.1 |
| **P0** | `PricingClient` base URL hardcoded, ignores application.yml | cart-service | §3.1 |
| **P1** | No circuit breaker on `PricingClient` — pricing failure cascades to cart failure | cart-service | §3.2 |
| **P1** | Cart validate re-prices with N individual HTTP calls — `O(N)` latency | cart-service | §6.2 |
| **P1** | Caffeine L1 only — N pods = N stale caches; no shared Redis | all | §5 |
| **P1** | Search has no store-scoped availability — global `in_stock` is not q-commerce correct | search-service | §2.2 |
| **P1** | No DLQ on any Kafka consumer in C3 cluster — malformed event blocks partition | search/pricing | §2.5 |
| **P2** | `@CacheEvict(allEntries=true)` causes cache stampede on product updates | search-service | §2.3 |
| **P2** | `max_uses` not checked for promotions (only for coupons) | pricing-service | §4.2 |
| **P2** | Coupon stacking policy (promotion + coupon) not configurable | pricing-service | §4.3 |
| **P2** | No bulk reindex endpoint; cold-start requires manual intervention | search-service | §2.4 |
| **P2** | `statement_timeout=5000ms` too high for search-service read queries | search-service | §6.3 |
| **P3** | Search ranking does not incorporate trending or conversion signals | search-service | §2.6 |
| **P3** | No cache metrics (hit ratio, eviction rate) on any service | all | §7.1 |
| **P3** | Price change notification missing from cart validate response | cart-service | §3.3 |

---

## Appendix A — Contract envelope fields alignment

`EventEnvelope.v1.json` declares both `id` and `eventId` as optional fields. The canonical  
field from `contracts/README.md` is `event_id` (snake_case). The Java record in  
pricing-service uses `EventEnvelope.eventType()` which maps to `eventType` (camelCase).  
The schema field is `eventType` — this is consistent.

However, `eventTime` in the schema vs `timestamp` in the contracts/README.md example are  
inconsistent:

```json
// EventEnvelope.v1.json — uses "eventTime"
"eventTime": { "type": "string", "format": "date-time" }

// contracts/README.md example — uses "timestamp"
"timestamp": "2024-01-15T10:30:00Z"
```

Standardize on `eventTime` (aligns with CloudEvents spec). Update the README example.

## Appendix B — Catalog-service has its own local PricingService (debt)

`catalog-service` contains its own `PricingService` with a chain-of-responsibility  
pattern (`BasePriceStrategy`, `ZoneOverrideStrategy`, `PromotionStrategy`). This is  
separate from and independent of `pricing-service`. `PromotionStrategy.apply()` is  
a stub that returns `PricingResult.unchanged()`.

This creates two competing sources of pricing truth:
- `catalog-service.PricingService` — used for `GET /pricing/compute` (returns per-item breakdown with store/zone override from `pricing_rules` table in catalog DB)
- `pricing-service.PricingService` — used for `POST /pricing/calculate` (applies promotions and coupons from pricing DB)

Cart-service calls only `pricing-service`. The BFF (`mobile-bff-service`) may call  
either depending on the use case. This needs consolidation: pricing-service should  
be the single truth, and catalog-service's `PricingController` and `PricingService`  
should be deprecated. The `pricing_rules` table in catalog DB is a zone-override mechanism  
that does not exist in pricing-service — it needs to be migrated there.

Migration plan for this consolidation is out of scope for Wave 3 (too high blast  
radius) and should be tracked as a Wave 4 item.
