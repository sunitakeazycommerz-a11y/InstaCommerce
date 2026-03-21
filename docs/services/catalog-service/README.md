# Catalog Service

**Product master data management, category hierarchy, and event publishing — the single source of truth for product catalog with Kafka-driven read-plane sync and comprehensive admin APIs.**

| Property | Value |
|----------|-------|
| **Module** | `:services:catalog-service` |
| **Port** | `8088` |
| **Runtime** | Spring Boot 4.0 · Java 21 |
| **Database** | PostgreSQL 15+ (Flyway-managed) |
| **Messaging** | Kafka producer (`catalog.events` outbox/CDC) |
| **Auth** | JWT RS256 (issuer: `instacommerce-identity`) |
| **Owner** | Catalog & Taxonomy Team |
| **SLO** | P50: 30ms, P99: 100ms (product APIs), 99.95% availability |

---

## Service Role & Ownership

**Owns:**
- `products` table — product master data (name, brand, category, pricing, images, active/inactive status)
- `categories` table — category hierarchy and taxonomy
- `pricing_rules` table — dynamic pricing rules (promotions, tier pricing)
- `product_images` table — product images with CDN URL references
- `audit_log` table — administrative audit trail
- `outbox_events` table — transactional outbox for reliable event publishing (CDC)

**Does NOT own:**
- Stock/inventory counts (→ `inventory-service`, pushes `ProductStockChanged`)
- Pricing calculations (→ `pricing-service`, consumes and calculates)
- Search indexing (→ `search-service`, consumes and builds FTS indexes)
- Fulfillment/delivery info (→ `fulfillment-service`, `warehouse-service`)

**Publishes:**
- `ProductCreated` → `catalog.events`
- `ProductUpdated` → `catalog.events`
- `ProductDelisted` / `ProductDeactivated` → `catalog.events`
- `CategoryCreated` / `CategoryUpdated` → `catalog.events`

---

## High-Level Design

```mermaid
graph TB
    subgraph Edge Layer
        AGW["admin-gateway-service<br/>:8099<br/>(Admin APIs)"]
        BFF["mobile-bff-service<br/>:8097<br/>(Product Browse)"]
    end

    subgraph Master Data
        CS["catalog-service<br/>:8088<br/>(Product SoT)"]
    end

    subgraph Data Layer
        PG[("PostgreSQL<br/>catalog DB<br/>Products, Categories<br/>Pricing Rules")]
        OUTBOX["Outbox Table<br/>(Transactional)"]
    end

    subgraph Downstream Read Planes
        SS["search-service<br/>Denormalized FTS"]
        PS["pricing-service<br/>Pricing Projections"]
        INV["inventory-service<br/>Stock Projections"]
    end

    subgraph Infrastructure
        CDC["CDC / Outbox Relay<br/>(Debezium / Custom)"]
        KF{{"Kafka<br/>catalog.events<br/>inventory.events"}}
        DLT{{"*.DLT"}}
    end

    AGW -->|Admin: POST/PUT /products<br/>POST /categories| CS
    BFF -->|Read: GET /products<br/>GET /categories| CS

    CS --> PG
    CS --> OUTBOX

    OUTBOX -->|CDC Relay| CDC
    CDC -->|Publishes| KF

    KF -->|Subscribe| SS
    KF -->|Subscribe| PS
    KF -->|Subscribe| INV

    KF -.-->|On error| DLT
```

---

## Core APIs

### Product Management

**GET /products/{id}**
```json
Request:
  Path: /products/{id}
  Auth: JWT

Response (200):
{
  "id": "uuid",
  "name": "Fresh Milk 1L",
  "description": "High-quality fresh milk",
  "brand": "Brand A",
  "category": {
    "id": "uuid",
    "name": "Dairy",
    "slug": "dairy"
  },
  "priceCents": 5000,
  "images": [
    {
      "url": "https://cdn.example.com/milk.jpg",
      "alt": "Product image",
      "order": 1
    }
  ],
  "status": "ACTIVE",
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2025-03-21T10:00:00Z"
}
```

**POST /products** (Admin)
```json
Request Body:
{
  "name": "Fresh Milk 1L",
  "description": "...",
  "brand": "Brand A",
  "categoryId": "uuid",
  "priceCents": 5000,
  "images": [...]
}

Response (201):
{
  "id": "newly-created-uuid",
  ...
}

Side Effects:
  1. INSERT into products table
  2. INSERT into outbox_events (ProductCreated event)
  3. CDC relay publishes to catalog.events
  4. search-service receives and indexes
  5. pricing-service receives and updates pricing
```

**PUT /products/{id}** (Admin)
```
Updates product and publishes ProductUpdated event
```

**DELETE /products/{id}** (Admin)
```
Soft delete (status = INACTIVE) and publishes ProductDeactivated event
```

**GET /products** (Paginated list)
```
Query:
  category: optional filter
  page: 0-based
  size: 20 (default)

Returns paginated ProductResponse[] with category info
```

### Category Management

**GET /categories** → Hierarchy of categories
**POST /categories** (Admin) → Create new category
**PUT /categories/{id}** (Admin) → Update category

---

## Database Schema

### Products Table

```sql
products:
  id              UUID PRIMARY KEY
  name            VARCHAR(512) NOT NULL
  description     TEXT
  brand           VARCHAR(255)
  category_id     UUID NOT NULL (FK → categories.id)
  price_cents     BIGINT NOT NULL
  status          ENUM('ACTIVE', 'INACTIVE', 'DELISTED')
  store_id        UUID (multi-tenancy)
  created_at      TIMESTAMP NOT NULL DEFAULT now()
  updated_at      TIMESTAMP NOT NULL DEFAULT now()
  version         BIGINT (optimistic locking)

Indexes:
  - category_id
  - status
  - store_id
  - created_at (recent products)
```

### Categories Table

```sql
categories:
  id              UUID PRIMARY KEY
  name            VARCHAR(255) NOT NULL UNIQUE
  slug            VARCHAR(255) NOT NULL UNIQUE
  description     TEXT
  parent_id       UUID (FK → categories.id, NULL = root)
  sort_order      INT
  created_at      TIMESTAMP NOT NULL
  updated_at      TIMESTAMP NOT NULL

Indexes:
  - parent_id (hierarchy traversal)
  - slug (URL-friendly lookups)
```

### Pricing Rules Table

```sql
pricing_rules:
  id              UUID PRIMARY KEY
  product_id      UUID NOT NULL (FK → products.id)
  rule_type       ENUM('TIER', 'BULK', 'SEASONAL', 'PROMOTION')
  min_qty         INT
  max_qty         INT
  discount_pct    DECIMAL(5,2)
  valid_from      TIMESTAMP NOT NULL
  valid_to        TIMESTAMP NOT NULL
  created_at      TIMESTAMP NOT NULL
  updated_at      TIMESTAMP NOT NULL
```

### Outbox Events Table (CDC)

```sql
outbox_events:
  id              UUID PRIMARY KEY
  aggregate_id    UUID NOT NULL (product_id)
  event_type      VARCHAR(100) NOT NULL
  payload         JSONB NOT NULL
  created_at      TIMESTAMP NOT NULL DEFAULT now()
  published_at    TIMESTAMP (NULL until CDC publishes)

Indexes:
  - created_at (CDC relay polls)
  - published_at (track unpublished events)
```

### Audit Log Table

```sql
audit_log:
  id              UUID PRIMARY KEY
  admin_id        UUID NOT NULL
  resource_type   VARCHAR(100)
  resource_id     UUID
  action          ENUM('CREATE', 'UPDATE', 'DELETE')
  before_data     JSONB
  after_data      JSONB
  ip_address      VARCHAR(45)
  timestamp       TIMESTAMP NOT NULL DEFAULT now()
```

---

## Kafka Events Published

### Topic: `catalog.events`

**Partition Key:** `productId` (ensures ordering per product)

#### ProductCreated

```json
{
  "id": "event-uuid",
  "eventType": "ProductCreated",
  "aggregateType": "Product",
  "aggregateId": "product-uuid",
  "eventTime": "2025-03-21T10:00:00Z",
  "schemaVersion": "1.0",
  "sourceService": "catalog-service",
  "correlationId": "request-uuid",
  "payload": {
    "productId": "product-uuid",
    "name": "Fresh Milk 1L",
    "description": "...",
    "brand": "Brand A",
    "category": "Dairy",
    "categoryId": "category-uuid",
    "priceCents": 5000,
    "images": ["https://..."],
    "storeId": "store-uuid"
  }
}
```

#### ProductUpdated
Same as ProductCreated (used for idempotent updates)

#### ProductDeactivated / ProductDelisted

```json
{
  "eventType": "ProductDeactivated",
  "payload": {
    "productId": "product-uuid",
    "deactivatedAt": "2025-03-21T10:00:00Z"
  }
}
```

**Subscribers:**
- `search-service` — removes from search index
- `pricing-service` — invalidates pricing rules
- `inventory-service` — soft-delete from stock
- `cart-service` — removes from active carts (future)

---

## Resilience Configuration

### Circuit Breaker (Resilience4j)

```yaml
resilience4j:
  ratelimiter:
    instances:
      productLimiter:
        limitForPeriod: 100
        limitRefreshPeriod: 60s

  circuitbreaker:
    instances:
      outboxRelay:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s
        slowCallRateThreshold: 80%
        slowCallDurationThreshold: 1000ms
```

### Timeouts

- Database statement timeout: 5s
- HikariCP connection timeout: 5s (more conservative than search-service)
- KafkaTemplate send timeout: 10s

### Retries

- Kafka producer: 3 retries (idempotent)
- Outbox relay: exponential backoff (handled by CDC connector)

---

## Observability

**Health Checks:**
- Liveness: `/actuator/health/live` (JVM state)
- Readiness: `/actuator/health/ready` (DB connection, Kafka producer ready)

**Metrics:**
- `catalog.product.create.count` — Total products created
- `catalog.product.update.count` — Total updates
- `catalog.outbox.unpublished` — Unpublished events in outbox
- `kafka.producer.record-send-total` — Kafka publish count
- `db.hikari.connections.active` — Active DB connections

**Tracing:**
- OpenTelemetry OTLP tracing (100% sampling)

---

## Deployment & Runbook

### Build

```bash
./gradlew :services:catalog-service:build
./gradlew :services:catalog-service:test
```

### Local Development

```bash
export CATALOG_DB_URL=jdbc:postgresql://localhost:5432/catalog
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
./gradlew :services:catalog-service:bootRun
```

### Docker

```bash
docker build -t catalog-service:latest services/catalog-service/
docker run -p 8088:8080 \
  -e CATALOG_DB_URL=jdbc:postgresql://postgres:5432/catalog \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  catalog-service:latest
```

### Kubernetes

```bash
kubectl apply -f k8s/catalog-service/deployment.yaml
kubectl rollout status deployment/catalog-service
```

### Debugging

**Check outbox events (unpublished):**
```sql
SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL;
```

**Replay a failed event:**
```sql
UPDATE outbox_events SET published_at = NULL WHERE id = ?;
-- CDC relay will republish
```

---

## Known Limitations

1. No image CDN optimization (direct URLs only, no thumbnail generation)
2. No bulk product import API (manual POST per product)
3. No A/B testing / variant management
4. No attribute system beyond name/brand/category

**Roadmap (Wave 34+):**
- Bulk import API with CSV/Excel support
- Product variants and SKUs
- Dynamic attribute system
- Image optimization and CDN thumbnails

---

## Dependencies

| Component | Version | Purpose |
|-----------|---------|---------|
| Spring Boot | 4.0 | Web framework |
| Spring Data JPA | 4.0 | Database ORM |
| PostgreSQL | 15+ | Product master data store |
| Kafka | 3.x | Event publishing |
| Resilience4j | Latest | Rate limiting, circuit breakers |
| Flyway | Latest | Schema migrations |
| JWT (JJWT) | 0.12.5 | Authentication |
| Testcontainers | 1.19.3 | Integration testing |

---

## Contact

- **On-call:** catalog-team-oncall@instacommerce.com
- **Slack:** #catalog-service
- **Docs:** /docs/services/catalog-service

