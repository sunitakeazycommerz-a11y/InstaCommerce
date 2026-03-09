# Catalog Service

Product management, categories, full-text search, pricing strategies, and coupons for the InstaCommerce platform. Publishes domain events to Kafka via the transactional outbox pattern.

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 3 |
| Database | PostgreSQL (Flyway migrations) |
| Search | PostgreSQL `tsvector` (pluggable via `SearchProvider`) |
| Caching | Caffeine (products 5 min, categories 10 min, search 30 s) |
| Messaging | Kafka (`catalog.events` topic via outbox) |
| Auth | JWT (RS256, public-key verification) |
| Resilience | Resilience4j rate limiters (per-IP) |
| Observability | OpenTelemetry traces + OTLP metrics, Prometheus endpoint |
| Container | JDK 21 Alpine, ZGC, multi-stage Docker build |

---

## High-Level Design (HLD)

```mermaid
graph LR
    Client([Client / API Gateway])

    subgraph catalog-service
        PC[ProductController]
        CC[CategoryController]
        SC[SearchController]
        PRC[PricingController]
        APC[AdminProductController]

        PS[ProductService]
        CS[CategoryService]
        SS[SearchService]
        PRS[PricingService]
        COS[CouponService]

        SP[[SearchProvider]]
        PSP[PostgresSearchProvider]

        OB[OutboxService]
        AL[AuditLogService]
        RL[RateLimitService]

        strategies[/Pricing Strategies/]
    end

    PG[(PostgreSQL)]
    KC{{Kafka}}
    CACHE[(Caffeine Cache)]

    Client --> PC & CC & SC & PRC & APC

    PC --> PS
    CC --> CS
    SC --> SS
    APC --> PS
    PRC --> PRS

    PS --> OB & AL
    PRS --> COS
    PRS --> strategies
    SS --> SP
    SP -.-> PSP

    PC & SC -.-> RL

    PS & CS & SS --> PG
    PSP --> PG
    OB --> PG
    OB -.->|OutboxPublishJob| KC

    PS & CS & SS -.-> CACHE
```

---

## Pricing Strategy Pattern

Strategies are applied as an ordered chain. Each strategy receives the current price and returns a (possibly modified) price with a rule label.

```mermaid
graph TD
    REQ[PricingComputeRequest] --> SVC[PricingService]
    SVC --> CTX[Build PricingContext<br/>product · storeId · zoneId · rules]
    CTX --> S1["① BasePriceStrategy<br/><i>order=1</i><br/>Sets product.basePriceCents"]
    S1 -->|currentPrice| S2["② ZoneOverrideStrategy<br/><i>order=2</i><br/>Applies store/zone override<br/>from pricing_rules"]
    S2 -->|currentPrice| S3["③ PromotionStrategy<br/><i>order=3</i><br/>Future: time-limited promotions"]
    S3 -->|finalPrice| COUPON[CouponService.validateAndCalculate]
    COUPON --> RESP[PricingBreakdownResponse<br/>items · subtotal · couponDiscount · total]

    style S1 fill:#e8f5e9
    style S2 fill:#fff3e0
    style S3 fill:#e3f2fd
```

---

## Product Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Draft : POST /admin/products
    Draft --> Active : createProduct<br/>(isActive=true by default)
    Active --> Updated : PUT /admin/products/:id
    Updated --> Active : save
    Active --> Deactivated : DELETE /admin/products/:id<br/>(soft delete)
    Deactivated --> Active : PUT /admin/products/:id<br/>isActive=true

    state Active {
        [*] --> Visible
        Visible : Listed in /products<br/>Searchable via /search
    }

    state Deactivated {
        [*] --> Hidden
        Hidden : Excluded from queries<br/>findByIsActiveTrue filters
    }

    note right of Draft
        Outbox event: ProductCreated
        Audit log: PRODUCT_CREATED
    end note
    note right of Updated
        Outbox event: ProductUpdated
        Audit log: PRODUCT_UPDATED
    end note
    note right of Deactivated
        Outbox event: ProductDeactivated
        Audit log: PRODUCT_DEACTIVATED
    end note
```

---

## Search Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant SC as SearchController
    participant RL as RateLimitService
    participant SS as SearchService
    participant SP as SearchProvider
    participant PSP as PostgresSearchProvider
    participant DB as PostgreSQL

    C->>SC: GET /search?q=mango&category=fruits
    SC->>RL: checkSearch(clientIP)
    RL-->>SC: OK (or 429)
    SC->>SS: search(query, category, brand, minPrice, maxPrice, pageable)
    SS->>SS: Check Caffeine cache
    alt Cache hit
        SS-->>SC: cached SearchResultResponse
    else Cache miss
        SS->>SP: search(query, filters, pageable)
        SP->>PSP: delegate
        PSP->>DB: SELECT … WHERE search_vector @@ plainto_tsquery(query)
        DB-->>PSP: Page<Product>
        PSP-->>SS: SearchResultResponse (results, totalCount, tookMs)
        SS-->>SS: Cache result (TTL 30 s)
    end
    SC-->>C: 200 SearchResultResponse

    Note over C,SC: Autocomplete: GET /search/autocomplete?q=man
```

---

## Coupon Validation Flow

```mermaid
flowchart TD
    START([couponCode received]) --> NULL{Code null<br/>or blank?}
    NULL -->|Yes| SKIP[Return null — no discount]
    NULL -->|No| FIND[Find coupon by code<br/>case-insensitive]
    FIND --> NF{Not found?}
    NF -->|Yes| ERR1[InvalidCouponException<br/>Coupon not found]
    NF -->|No| ACTIVE{isActive?}
    ACTIVE -->|No| ERR2[Coupon is inactive]
    ACTIVE -->|Yes| DATE{Within<br/>validFrom ↔ validTo?}
    DATE -->|No| ERR3[Coupon has expired]
    DATE -->|Yes| MIN{subtotal ≥<br/>minOrderCents?}
    MIN -->|No| ERR4[Minimum order<br/>value not met]
    MIN -->|Yes| ULIMIT{usageLimit<br/>exceeded?}
    ULIMIT -->|Yes| ERR5[Usage limit reached]
    ULIMIT -->|No| PLIMIT{perUserLimit<br/>exceeded?}
    PLIMIT -->|Yes| ERR6[User limit reached]
    PLIMIT -->|No| FIRST{firstOrderOnly<br/>& user has orders?}
    FIRST -->|Yes| ERR7[First order only]
    FIRST -->|No| CALC[Compute discount<br/>PERCENTAGE or FLAT_AMOUNT]
    CALC --> CAP[Cap at maxDiscountCents<br/>and subtotal]
    CAP --> OK([CouponDiscountResponse])

    style ERR1 fill:#ffcdd2
    style ERR2 fill:#ffcdd2
    style ERR3 fill:#ffcdd2
    style ERR4 fill:#ffcdd2
    style ERR5 fill:#ffcdd2
    style ERR6 fill:#ffcdd2
    style ERR7 fill:#ffcdd2
    style OK fill:#c8e6c9
```

---

## API Reference

### Public Endpoints (no auth required)

| Method | Path | Description | Query Params |
|---|---|---|---|
| `GET` | `/products` | List active products (paginated) | `category`, `page`, `size`, `sort` |
| `GET` | `/products/{id}` | Get product by ID | — |
| `GET` | `/categories` | Category tree (hierarchical) | — |
| `GET` | `/categories/{id}/products` | Products in a category (paginated) | `page`, `size`, `sort` |
| `GET` | `/search?q=` | Full-text search with filters | `q`, `category`, `brand`, `minPrice`, `maxPrice`, `page`, `size` |
| `GET` | `/search/autocomplete?q=` | Autocomplete suggestions | `q` |

### Authenticated Endpoints (JWT, `ROLE_ADMIN`)

| Method | Path | Description | Body |
|---|---|---|---|
| `POST` | `/admin/products` | Create product | `CreateProductRequest` |
| `PUT` | `/admin/products/{id}` | Update product | `UpdateProductRequest` |
| `DELETE` | `/admin/products/{id}` | Soft-delete (deactivate) product | — |

### Internal Endpoint (authenticated)

| Method | Path | Description | Body |
|---|---|---|---|
| `POST` | `/pricing/compute` | Compute pricing breakdown with strategy chain + coupon | `PricingComputeRequest` |

### Rate Limiting

Product and search endpoints are rate-limited at **100 requests / 60 s** per client IP (configurable via `resilience4j.ratelimiter`).

---

## Database Schema

```mermaid
erDiagram
    categories {
        uuid id PK
        varchar name
        varchar slug UK
        uuid parent_id FK
        int sort_order
        boolean is_active
        timestamptz created_at
        timestamptz updated_at
    }

    products {
        uuid id PK
        varchar sku UK
        varchar name
        varchar slug UK
        text description
        uuid category_id FK
        varchar brand
        bigint base_price_cents
        varchar currency
        varchar unit
        decimal unit_value
        int weight_grams
        boolean is_active
        timestamptz created_at
        timestamptz updated_at
        bigint version
        tsvector search_vector
    }

    product_images {
        uuid id PK
        uuid product_id FK
        text url
        varchar alt_text
        int sort_order
        boolean is_primary
        timestamptz created_at
    }

    pricing_rules {
        uuid id PK
        uuid product_id FK
        varchar store_id
        varchar zone_id
        bigint override_price_cents
        timestamptz valid_from
        timestamptz valid_to
        boolean is_active
        int priority
        timestamptz created_at
    }

    coupons {
        uuid id PK
        varchar code UK
        varchar discount_type
        bigint discount_value
        bigint min_order_cents
        bigint max_discount_cents
        timestamptz valid_from
        timestamptz valid_to
        int usage_limit
        int per_user_limit
        boolean first_order_only
        boolean is_active
        timestamptz created_at
    }

    coupon_usages {
        uuid id PK
        uuid coupon_id FK
        uuid user_id
        uuid order_id
        timestamptz used_at
    }

    outbox_events {
        uuid id PK
        varchar aggregate_type
        varchar aggregate_id
        varchar event_type
        jsonb payload
        timestamptz created_at
        boolean sent
    }

    audit_log {
        uuid id PK
        uuid user_id
        varchar action
        varchar entity_type
        varchar entity_id
        jsonb details
        varchar ip_address
        text user_agent
        varchar trace_id
        timestamptz created_at
    }

    shedlock {
        varchar name PK
        timestamp lock_until
        timestamp locked_at
        varchar locked_by
    }

    categories ||--o{ categories : "parent_id"
    categories ||--o{ products : "category_id"
    products ||--o{ product_images : "product_id"
    products ||--o{ pricing_rules : "product_id"
    coupons ||--o{ coupon_usages : "coupon_id"
```

### Key Indexes

| Table | Index | Purpose |
|---|---|---|
| `products` | `GIN (search_vector)` | Full-text search |
| `products` | `(category_id, is_active)` | Category listing |
| `products` | `(brand)` | Brand filter |
| `products` | `(base_price_cents)` | Price range filter |
| `pricing_rules` | `(store_id, product_id)` | Store-specific pricing lookups |
| `coupon_usages` | `(coupon_id, user_id)` | Per-user limit checks |
| `outbox_events` | `(sent) WHERE sent = false` | Pending event polling |
| `audit_log` | `(created_at)` | Time-range queries and cleanup |

---

## Project Structure

```
src/main/java/com/instacommerce/catalog/
├── config/            SecurityConfig, CacheConfig, SchedulerConfig, CatalogProperties
├── controller/        ProductController, CategoryController, SearchController,
│                      PricingController, AdminProductController
├── domain/model/      Product, Category, PricingRule, Coupon, CouponUsage,
│                      ProductImage, OutboxEvent, AuditLog
├── domain/valueobject/ Money
├── dto/request/       CreateProductRequest, UpdateProductRequest,
│                      PricingComputeRequest, PricingItemRequest, ProductImageRequest
├── dto/response/      ProductResponse, CategoryResponse, SearchResultResponse,
│                      PricingBreakdownResponse, PricingItemResponse,
│                      CouponDiscountResponse, AutocompleteResult, ErrorResponse
├── dto/mapper/        ProductMapper
├── event/             ProductChangedEvent
├── exception/         GlobalExceptionHandler, ProductNotFoundException,
│                      CategoryNotFoundException, DuplicateSkuException,
│                      InvalidCouponException, ApiException
├── pricing/           PricingStrategy, BasePriceStrategy, ZoneOverrideStrategy,
│                      PromotionStrategy, PricingContext, PricingResult
├── repository/        ProductRepository, CategoryRepository, SearchRepository,
│                      PricingRuleRepository, CouponRepository,
│                      CouponUsageRepository, OutboxEventRepository, AuditLogRepository
├── security/          JwtAuthenticationFilter, JwtService, DefaultJwtService,
│                      JwtKeyLoader, RestAuthenticationEntryPoint,
│                      RestAccessDeniedHandler
└── service/           ProductService, CategoryService, SearchService, PricingService,
                       CouponService, OutboxService, OutboxPublishJob, OutboxCleanupJob,
                       OutboxEventPublisher, LoggingOutboxEventPublisher,
                       AuditLogService, AuditLogCleanupJob,
                       SearchProvider, PostgresSearchProvider, RateLimitService
```

---

## Running Locally

```bash
# Start dependencies
docker compose up -d postgres

# Run the service (port 8082)
./gradlew bootRun

# Health check
curl http://localhost:8082/actuator/health/liveness
```

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8082` | HTTP listen port |
| `CATALOG_DB_URL` | `jdbc:postgresql://localhost:5432/catalog` | PostgreSQL JDBC URL |
| `CATALOG_DB_USER` | `postgres` | Database user |
| `CATALOG_DB_PASSWORD` | — | Database password |
| `CATALOG_JWT_ISSUER` | `instacommerce-identity` | Expected JWT issuer |
| `CATALOG_JWT_PUBLIC_KEY` | — | RSA public key for JWT verification |
| `TRACING_PROBABILITY` | `1.0` | OpenTelemetry trace sampling rate |

## Low-Level Design (LLD)

The low-level design centers on `ProductService`, `SearchService`, `PricingService`, `CouponService`, PostgreSQL-backed repositories, and the outbox/audit jobs listed below. The following strategy, lifecycle, and flow diagrams document how those components collaborate inside the service boundary.

---

## Testing

```bash
./gradlew :services:catalog-service:test
```

## Rollout and Rollback

- keep search/indexing and catalog write-path changes additive so downstream search consumers can tolerate overlap windows
- monitor outbox lag, search freshness, and admin write failures during rollout
- roll back application behavior first; reserve schema rollbacks for cases where additive migrations cannot preserve compatibility

## Known Limitations

- search freshness and typo-tolerance remain behind the benchmark set by leading q-commerce operators and are still called out in the iter3 read/decision review
- catalog event semantics must remain aligned with `contracts/` and downstream search/index consumers as the browse plane evolves
