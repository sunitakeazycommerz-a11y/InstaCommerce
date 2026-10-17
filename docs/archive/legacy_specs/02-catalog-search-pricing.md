# 02 — Catalog, Search & Pricing Service (catalog-service)

> **Bounded Context**: Product Catalog, Text Search, Pricing & Promotions  
> **Owner team**: Product / Commerce  
> **Runtime**: Java 21 + Spring Boot 4.0.x on GKE  
> **Primary datastore**: Cloud SQL for PostgreSQL 15  
> **Search**: PostgreSQL tsvector (MVP) → OpenSearch (future)  
> **Port (local)**: 8082 | **gRPC port**: 9082

---

## 1. Architecture Overview

```
                        ┌─────────────────┐
                        │  API Gateway     │
                        │  (Istio Ingress) │
                        └────────┬────────┘
                                 │
        ┌────────────────────────┼──────────────────────────┐
        │                        │                          │
  ┌─────▼──────┐          ┌──────▼──────┐           ┌───────▼──────┐
  │  Catalog    │          │  Search     │           │  Pricing     │
  │  Controller │          │  Controller │           │  Controller  │
  │  (REST)     │          │  (REST)     │           │  (gRPC+REST) │
  └─────┬──────┘          └──────┬──────┘           └───────┬──────┘
        │                        │                          │
  ┌─────▼──────┐          ┌──────▼──────┐           ┌───────▼──────┐
  │  Catalog   │          │  Search     │           │  Pricing     │
  │  Service   │          │  Service    │           │  Engine      │
  └─────┬──────┘          └──────┬──────┘           └───────┬──────┘
        │                        │                          │
        │  ┌─────────────────────┘                          │
        │  │                                                │
  ┌─────▼──▼─────────────────────────────────────────────────▼────┐
  │                  PostgreSQL (catalog_db)                       │
  │  products │ categories │ product_images │ pricing_rules       │
  │  coupons  │ search_index (tsvector)    │ outbox_events        │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │  Debezium CDC   │
                    │  (outbox table) │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │  Kafka          │
                    │  catalog.events │
                    └─────────────────┘
```

---

## 2. Java Package Structure

```
com.instacommerce.catalog
├── CatalogServiceApplication.java
├── config/
│   ├── SecurityConfig.java              # JWT validation (verifies tokens from identity-service)
│   ├── KafkaConfig.java                 # Kafka producer for outbox relay (if not using Debezium)
│   ├── CacheConfig.java                 # Caffeine L1 cache for hot products
│   └── GrpcServerConfig.java            # gRPC server for Pricing (internal)
├── controller/
│   ├── ProductController.java           # /products, /products/{id}
│   ├── CategoryController.java          # /categories, /categories/{id}/products
│   ├── SearchController.java            # /search?q=&category=&page=&size=
│   ├── PricingController.java           # /pricing/compute (REST, for external)
│   └── AdminProductController.java      # /admin/products (CRUD, ROLE_ADMIN)
├── grpc/
│   ├── PricingGrpcService.java          # gRPC impl for internal pricing calls
│   └── proto/
│       └── pricing.proto
├── dto/
│   ├── request/
│   │   ├── CreateProductRequest.java
│   │   ├── UpdateProductRequest.java
│   │   ├── PricingComputeRequest.java   # {store_id, items[], coupon_code?}
│   │   └── SearchRequest.java
│   ├── response/
│   │   ├── ProductResponse.java
│   │   ├── CategoryResponse.java
│   │   ├── SearchResultResponse.java
│   │   ├── PricingBreakdownResponse.java
│   │   └── ErrorResponse.java
│   └── mapper/
│       ├── ProductMapper.java           # MapStruct
│       └── CategoryMapper.java
├── domain/
│   ├── model/
│   │   ├── Product.java                 # Aggregate root
│   │   ├── ProductVariant.java
│   │   ├── Category.java                # Self-referencing parent_id for tree
│   │   ├── ProductImage.java
│   │   ├── PricingRule.java             # zone/store price overrides
│   │   ├── Coupon.java
│   │   └── OutboxEvent.java             # Transactional outbox
│   ├── valueobject/
│   │   ├── Money.java                   # amount (long cents) + currency (String ISO)
│   │   ├── Sku.java                     # validated SKU format
│   │   └── Slug.java                    # URL-safe product slug
│   └── service/
│       ├── CatalogDomainService.java    # Business rules (e.g., slug generation)
│       └── PricingStrategy.java         # Interface
├── service/
│   ├── ProductService.java
│   ├── CategoryService.java
│   ├── SearchService.java
│   ├── PricingService.java              # Orchestrates strategy chain
│   ├── CouponService.java
│   └── OutboxService.java              # Writes outbox events in same TX as domain writes
├── pricing/
│   ├── PricingStrategy.java             # Strategy interface
│   ├── BasePriceStrategy.java
│   ├── ZoneOverrideStrategy.java
│   ├── PromotionStrategy.java
│   └── CouponStrategy.java
├── repository/
│   ├── ProductRepository.java
│   ├── CategoryRepository.java
│   ├── PricingRuleRepository.java
│   ├── CouponRepository.java
│   ├── SearchRepository.java            # Custom native queries for tsvector
│   └── OutboxEventRepository.java
├── event/
│   ├── ProductCreatedEvent.java
│   ├── ProductUpdatedEvent.java
│   └── CatalogEventPublisher.java       # Writes to outbox table
├── exception/
│   ├── ProductNotFoundException.java
│   ├── CategoryNotFoundException.java
│   ├── InvalidCouponException.java
│   ├── DuplicateSkuException.java
│   └── GlobalExceptionHandler.java
└── infrastructure/
    ├── search/
    │   └── PostgresFullTextSearch.java   # tsvector queries
    ├── cache/
    │   └── ProductCacheManager.java      # Caffeine with 5 min TTL
    └── metrics/
        └── CatalogMetrics.java
```

---

## 3. Domain Model (Complete DDL)

### V1__create_categories.sql
```sql
CREATE TABLE categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100)  NOT NULL,
    slug        VARCHAR(120)  NOT NULL,
    parent_id   UUID          REFERENCES categories(id) ON DELETE SET NULL,
    sort_order  INT           NOT NULL DEFAULT 0,
    is_active   BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_slug UNIQUE (slug)
);

CREATE INDEX idx_categories_parent ON categories (parent_id);
CREATE INDEX idx_categories_active ON categories (is_active) WHERE is_active = true;
```

### V2__create_products.sql
```sql
CREATE TABLE products (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku             VARCHAR(50)   NOT NULL,
    name            VARCHAR(255)  NOT NULL,
    slug            VARCHAR(280)  NOT NULL,
    description     TEXT,
    category_id     UUID          REFERENCES categories(id),
    brand           VARCHAR(100),
    base_price_cents BIGINT       NOT NULL,           -- price in smallest currency unit
    currency        VARCHAR(3)    NOT NULL DEFAULT 'INR',
    unit            VARCHAR(20)   NOT NULL DEFAULT 'piece',  -- piece, kg, litre
    unit_value      DECIMAL(10,3) NOT NULL DEFAULT 1.0,
    weight_grams    INT,
    is_active       BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    -- Full text search vector (auto-updated via trigger)
    search_vector   TSVECTOR,
    CONSTRAINT uq_product_sku  UNIQUE (sku),
    CONSTRAINT uq_product_slug UNIQUE (slug),
    CONSTRAINT chk_price_positive CHECK (base_price_cents > 0)
);

CREATE INDEX idx_products_category  ON products (category_id);
CREATE INDEX idx_products_active    ON products (is_active) WHERE is_active = true;
CREATE INDEX idx_products_search    ON products USING GIN (search_vector);

-- Trigger to auto-update search_vector
CREATE OR REPLACE FUNCTION products_search_trigger() RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.brand, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_products_search
    BEFORE INSERT OR UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION products_search_trigger();
```

### V3__create_product_images.sql
```sql
CREATE TABLE product_images (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID     NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    url         TEXT     NOT NULL,
    alt_text    VARCHAR(255),
    sort_order  INT      NOT NULL DEFAULT 0,
    is_primary  BOOLEAN  NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_images_product ON product_images (product_id);
```

### V4__create_pricing_rules.sql
```sql
CREATE TABLE pricing_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id      UUID         NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    store_id        VARCHAR(50),        -- NULL = all stores
    zone_id         VARCHAR(50),        -- NULL = all zones
    override_price_cents BIGINT  NOT NULL,
    valid_from      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_to        TIMESTAMPTZ,        -- NULL = no end
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    priority        INT          NOT NULL DEFAULT 0,  -- higher = takes precedence
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_pricing_rules_product ON pricing_rules (product_id, is_active);
CREATE INDEX idx_pricing_rules_store   ON pricing_rules (store_id, product_id) WHERE is_active = true;
```

### V5__create_coupons.sql
```sql
CREATE TABLE coupons (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(30)  NOT NULL,
    discount_type   VARCHAR(15)  NOT NULL,   -- PERCENTAGE, FLAT_AMOUNT
    discount_value  BIGINT       NOT NULL,   -- percentage (100 = 1%) or cents
    min_order_cents BIGINT       DEFAULT 0,
    max_discount_cents BIGINT,               -- cap for percentage discounts
    valid_from      TIMESTAMPTZ  NOT NULL,
    valid_to        TIMESTAMPTZ  NOT NULL,
    usage_limit     INT,                     -- NULL = unlimited
    per_user_limit  INT          DEFAULT 1,
    first_order_only BOOLEAN     DEFAULT false,
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_coupon_code UNIQUE (code),
    CONSTRAINT chk_discount_type CHECK (discount_type IN ('PERCENTAGE', 'FLAT_AMOUNT'))
);

CREATE TABLE coupon_usages (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id  UUID         NOT NULL REFERENCES coupons(id),
    user_id    UUID         NOT NULL,
    order_id   UUID,
    used_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_coupon_usage UNIQUE (coupon_id, user_id, order_id)
);
```

### V6__create_outbox.sql
```sql
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50)  NOT NULL,  -- 'Product', 'Coupon'
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,  -- 'ProductCreated', 'ProductUpdated'
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent           BOOLEAN      NOT NULL DEFAULT false
);

-- Debezium reads from here (CDC on INSERT)
CREATE INDEX idx_outbox_unsent ON outbox_events (sent) WHERE sent = false;
```

---

## 4. REST API Contract

### 4.1 Products

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /products/{id} | Public | Get product by ID |
| GET | /products?category={slug}&page=0&size=20 | Public | List products |
| POST | /admin/products | ROLE_ADMIN | Create product |
| PUT | /admin/products/{id} | ROLE_ADMIN | Update product |
| DELETE | /admin/products/{id} | ROLE_ADMIN | Soft-delete product |

#### GET /products/{id} — Response 200
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "sku": "MILK-AMUL-500ML",
  "name": "Amul Toned Milk 500ml",
  "slug": "amul-toned-milk-500ml",
  "description": "Fresh toned milk from Amul",
  "category": { "id": "...", "name": "Dairy", "slug": "dairy" },
  "brand": "Amul",
  "basePriceCents": 3200,
  "currency": "INR",
  "unit": "piece",
  "unitValue": 1.0,
  "weightGrams": 530,
  "images": [
    { "url": "https://cdn.insta.local/milk-amul.webp", "altText": "Amul Milk", "isPrimary": true }
  ],
  "isActive": true,
  "createdAt": "2026-01-15T10:00:00Z"
}
```

#### POST /admin/products — Request
```json
{
  "sku": "MILK-AMUL-500ML",
  "name": "Amul Toned Milk 500ml",
  "description": "Fresh toned milk from Amul",
  "categoryId": "category-uuid",
  "brand": "Amul",
  "basePriceCents": 3200,
  "currency": "INR",
  "unit": "piece",
  "unitValue": 1.0,
  "weightGrams": 530,
  "images": [
    { "url": "https://cdn.insta.local/milk-amul.webp", "altText": "Amul Milk", "isPrimary": true }
  ]
}
```

### 4.2 Categories

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /categories | Public | Get category tree |
| GET | /categories/{id}/products?page=0&size=20 | Public | Products in category |

#### GET /categories — Response 200
```json
[
  {
    "id": "...", "name": "Fruits & Vegetables", "slug": "fruits-vegetables", "parentId": null,
    "children": [
      { "id": "...", "name": "Fresh Fruits", "slug": "fresh-fruits", "parentId": "...", "children": [] }
    ]
  }
]
```

### 4.3 Search

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /search?q={query}&category={slug}&page=0&size=20&sort=relevance | Public | Search products |

#### GET /search?q=milk — Response 200
```json
{
  "results": [ { /* ProductResponse */ } ],
  "totalCount": 42,
  "page": 0,
  "size": 20,
  "query": "milk",
  "took_ms": 23
}
```

### 4.4 Pricing (internal + REST fallback)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /pricing/compute | Internal (service token) | Compute prices for items |

#### POST /pricing/compute — Request
```json
{
  "storeId": "store-mumbai-01",
  "items": [
    { "productId": "uuid-1", "quantity": 2 },
    { "productId": "uuid-2", "quantity": 1 }
  ],
  "couponCode": "FIRST10",
  "userId": "user-uuid"
}
```

#### POST /pricing/compute — Response 200
```json
{
  "items": [
    {
      "productId": "uuid-1",
      "productName": "Amul Milk 500ml",
      "quantity": 2,
      "unitPriceCents": 3200,
      "effectivePriceCents": 2900,
      "lineTotalCents": 5800,
      "appliedRules": ["ZONE_OVERRIDE:store-mumbai-01"]
    }
  ],
  "subtotalCents": 8700,
  "couponDiscount": {
    "code": "FIRST10",
    "discountCents": 870,
    "valid": true
  },
  "totalCents": 7830,
  "currency": "INR"
}
```

---

## 5. Pricing Strategy Chain (LLD)

```
┌─────────────────────────────────────────────────────────────┐
│                   PricingService.compute()                    │
│                                                             │
│  for each item:                                             │
│    1. BasePriceStrategy   → product.base_price_cents        │
│    2. ZoneOverrideStrategy → pricing_rules by store/zone    │
│    3. PromotionStrategy   → active promotions on product    │
│    4. CouponStrategy      → apply coupon if valid           │
│                                                             │
│  Output: PricingBreakdown with line items + totals          │
└─────────────────────────────────────────────────────────────┘
```

```java
// Strategy interface
public interface PricingStrategy {
    /**
     * @param context current context (product, store, user)
     * @param currentPriceCents the running price from previous strategies
     * @return adjusted price and metadata about what was applied
     */
    PricingResult apply(PricingContext context, long currentPriceCents);
    
    int order();  // execution order (lower = first)
}

// Spring wiring: inject List<PricingStrategy> ordered by order()
@Service
public class PricingService {
    private final List<PricingStrategy> strategies; // auto-sorted by @Order
    
    public PricingBreakdown compute(PricingComputeRequest request) {
        // for each item, run strategy chain
        // accumulate results
        // apply coupon at basket level
    }
}
```

---

## 6. Outbox Pattern (Debezium Integration)

Writing a product and its event in one transaction:

```java
@Transactional
public ProductResponse createProduct(CreateProductRequest request) {
    Product product = productMapper.toEntity(request);
    product = productRepository.save(product);
    
    OutboxEvent event = OutboxEvent.builder()
        .aggregateType("Product")
        .aggregateId(product.getId().toString())
        .eventType("ProductCreated")
        .payload(objectMapper.writeValueAsString(
            new ProductCreatedEvent(product.getId(), product.getSku(), product.getName())))
        .build();
    outboxEventRepository.save(event);
    
    return productMapper.toResponse(product);
}
```

### Debezium Connector Config (Kafka Connect)
```json
{
  "name": "catalog-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "catalog-db-host",
    "database.port": "5432",
    "database.user": "debezium",
    "database.password": "${secret:catalog-debezium-password}",
    "database.dbname": "catalog",
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

---

## 7. Search Implementation (PostgreSQL FTS)

```java
@Repository
public class SearchRepositoryImpl implements SearchRepository {
    
    @PersistenceContext
    private EntityManager em;
    
    public Page<Product> search(String query, String categorySlug, Pageable pageable) {
        String sql = """
            SELECT p.* FROM products p
            LEFT JOIN categories c ON p.category_id = c.id
            WHERE p.is_active = true
              AND p.search_vector @@ plainto_tsquery('english', :query)
              AND (:categorySlug IS NULL OR c.slug = :categorySlug)
            ORDER BY ts_rank(p.search_vector, plainto_tsquery('english', :query)) DESC
            LIMIT :limit OFFSET :offset
            """;
        // execute native query, map to Product entities
    }
}
```

---

## 8. GCP Cloud Guidelines

### Cloud SQL
- Instance: `catalog-db`, same region, db-custom-2-8192
- Connection via Cloud SQL Auth Proxy sidecar
- Read replicas for search queries if load is high (documented future scaling path)

### Caching (Caffeine — in-process)
```yaml
spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=10000,expireAfterWrite=300s
```
- Cache keys: `product:{id}`, `category:tree`
- Cache eviction: on product update, evict `product:{id}` and `category:tree`
- Future: Add GCP Memorystore (Redis) as L2 cache if needed

### application.yml (prod)
```yaml
spring:
  application:
    name: catalog-service
  datasource:
    url: jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${CLOUD_SQL_INSTANCE}&socketFactory=com.google.cloud.sql.postgres.SocketFactory
    username: ${DB_USER}
    password: ${sm://catalog-db-password}
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=10000,expireAfterWrite=300s

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

---

## 9. Observability

### Metrics
| Metric | Type | Labels |
|--------|------|--------|
| `catalog_product_created_total` | Counter | |
| `catalog_search_requests_total` | Counter | status |
| `catalog_search_duration_seconds` | Histogram | |
| `catalog_pricing_compute_duration_seconds` | Histogram | |
| `catalog_cache_hit_total` | Counter | cache_name |
| `catalog_cache_miss_total` | Counter | cache_name |

### SLOs
| SLO | Target | Window |
|-----|--------|--------|
| Search p95 latency | < 150ms | 30 days |
| Product GET p95 latency | < 100ms | 30 days |
| Pricing compute p95 | < 200ms | 30 days |

---

## 10. Error Codes

| HTTP | Code | When |
|------|------|------|
| 400 | VALIDATION_ERROR | Invalid input |
| 404 | PRODUCT_NOT_FOUND | Product ID miss |
| 404 | CATEGORY_NOT_FOUND | Category ID miss |
| 409 | DUPLICATE_SKU | SKU already exists |
| 422 | INVALID_COUPON | Coupon expired/invalid/used |
| 500 | INTERNAL_ERROR | Unexpected |

---

## 11. Testing Strategy

### Unit Tests
- `PricingServiceTest`: test each strategy independently and the full chain (base → zone → promo → coupon)
- `CouponServiceTest`: valid coupon, expired, already used, min order not met
- `SearchRepositoryTest`: tsvector ranking correctness

### Integration Tests (Testcontainers)
- Create product → verify in DB and outbox event written
- Search flow: insert 100 products → search → verify relevance ranking
- Pricing: product with zone override → compute returns override price

### Contract Tests
- ProductResponse JSON shape assertion
- PricingBreakdownResponse exact fields
- Outbox event payload JSON Schema validation

---

## 12. Helm Values

```yaml
replicaCount: 2
image:
  repository: gcr.io/<project>/catalog-service
resources:
  requests: { cpu: 500m, memory: 768Mi }
  limits:   { cpu: 2000m, memory: 1536Mi }
hpa:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilization: 65
```

---

## 13. Agent Instructions (CRITICAL)

### MUST DO
1. Use the exact package structure in section 2.
2. All prices are stored in **cents** (long). No floating-point arithmetic for money. Use the `Money` value object.
3. Pricing strategies must implement the `PricingStrategy` interface and be auto-discovered via Spring's `@Order`.
4. Product creation MUST write to `outbox_events` in the same `@Transactional`. Never publish to Kafka directly.
5. Search uses PostgreSQL `tsvector` with weighted ranking (name=A, brand=B, description=C).
6. Implement `slug` generation from product name (lowercase, hyphens, alphanumeric only). Ensure uniqueness.
7. Use Caffeine in-process cache for product reads; evict on writes.
8. Admin endpoints require `ROLE_ADMIN` enforced via `@PreAuthorize`.
9. All GET endpoints must support pagination (page, size) with Spring Pageable.
10. Category tree: use recursive CTE or adjacency list with parent_id. Return nested JSON.

### MUST NOT DO
1. Do NOT use `float` or `double` for prices. Ever.
2. Do NOT call Kafka producer directly from product create/update — use outbox only.
3. Do NOT expose admin endpoints without role check.
4. Do NOT skip the search trigger; every product must have a search_vector.
5. Do NOT hardcode store IDs or zone IDs in pricing logic.

### DEFAULTS
- Page size default: 20, max: 100
- Cache TTL: 300 seconds, max entries: 10,000
- Currency: INR
- Local dev DB: `jdbc:postgresql://localhost:5432/catalog` user=postgres pass=postgres

### ESCALATION
Add `// TODO(AGENT):` for:
- Whether to support product variants (size/color) — currently schema has placeholder
- Whether to integrate with an external CDN for image URLs
- Whether to use OpenSearch instead of Postgres FTS
