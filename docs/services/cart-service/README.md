# Cart Service

**Shopping cart management with real-time product availability checks, pricing integration, and outbox-based event publishing for reliable cart state synchronization.**

| Property | Value |
|----------|-------|
| **Module** | `:services:cart-service` |
| **Port** | `8088` |
| **Runtime** | Spring Boot 4.0 · Java 21 |
| **Database** | PostgreSQL 15+ (Flyway-managed) |
| **Messaging** | Kafka consumer (inventory.events, catalog.events) |
| **Cache** | Caffeine (cart TTL: 24h) |
| **Auth** | JWT RS256 + user isolation |
| **Owner** | Cart & Checkout Team |
| **SLO** | P50: 50ms, P99: 150ms, 99.9% availability |

---

## Service Role & Ownership

**Owns:**
- `carts` table — user shopping carts (session-scoped, persisted)
- `cart_items` table — items in cart with product ID, quantity, price snapshot
- `outbox_events` table — transactional outbox for CartUpdated, CartValidated events

**Does NOT own:**
- Product data (→ `catalog-service`)
- Stock availability (→ `inventory-service`)
- Pricing calculations (→ `pricing-service`, called synchronously for real-time quotes)
- Checkout/order creation (→ `checkout-orchestrator-service`)

**Consumes:**
- `inventory.events` — ProductStockChanged (invalidate affected carts)
- `catalog.events` — ProductDeactivated (remove from carts)

**Publishes:**
- `CartCreated`, `CartUpdated`, `CartValidated` → `cart.events` (via outbox/CDC)

---

## Core APIs

### Get Cart

**GET /cart**
```
Auth: JWT (extracts userId from token)

Response (200):
{
  "id": "cart-uuid",
  "userId": "user-uuid",
  "items": [
    {
      "productId": "product-uuid",
      "name": "Fresh Milk 1L",
      "quantity": 2,
      "priceCents": 5000,
      "totalCents": 10000,
      "available": true,
      "imageUrl": "https://..."
    }
  ],
  "subtotalCents": 10000,
  "itemCount": 2,
  "lastModified": "2025-03-21T10:00:00Z"
}
```

### Add Item

**POST /cart/items**
```
Request Body:
{
  "productId": "product-uuid",
  "quantity": 2
}

Response (201):
{
  "items": [...],
  "subtotalCents": 10000
}

Error: 400 if product out of stock, 404 if product not found
```

### Update Quantity

**PATCH /cart/items/{productId}**
```
Request Body:
{
  "quantity": 5
}

Response (200): Updated CartResponse

Error: 400 if quantity exceeds stock
```

### Remove Item

**DELETE /cart/items/{productId}**
```
Response (200): Updated CartResponse
```

### Clear Cart

**DELETE /cart**
```
Response (204): No content
```

### Validate Cart

**POST /cart/validate**
```
Checks:
  1. All items still in stock
  2. All prices are current
  3. Pricing quote is valid

Response (200):
{
  "isValid": true,
  "errors": [],
  "totalCents": 10000,
  "quoteId": "quote-uuid",
  "quoteToken": "base64-token"
}

Response (400):
{
  "isValid": false,
  "errors": [
    "ProductId X is out of stock",
    "Price changed for ProductId Y"
  ]
}
```

---

## Database Schema

### Carts Table

```sql
carts:
  id              UUID PRIMARY KEY
  user_id         UUID NOT NULL (FK → identity-service)
  store_id        UUID (multi-tenancy)
  total_cents     BIGINT NOT NULL DEFAULT 0
  item_count      INT NOT NULL DEFAULT 0
  status          ENUM('ACTIVE', 'ABANDONED', 'CONVERTED')
  created_at      TIMESTAMP NOT NULL DEFAULT now()
  updated_at      TIMESTAMP NOT NULL DEFAULT now()

Indexes:
  - user_id (per-user carts)
  - status (abandoned cart cleanup)
  - updated_at (LRU expiry)
```

### Cart Items Table

```sql
cart_items:
  id              UUID PRIMARY KEY
  cart_id         UUID NOT NULL (FK → carts.id)
  product_id      UUID NOT NULL
  quantity        INT NOT NULL CHECK(quantity > 0)
  price_cents     BIGINT NOT NULL (snapshot at add time)
  total_cents     BIGINT NOT NULL (quantity * price)
  added_at        TIMESTAMP NOT NULL DEFAULT now()

Indexes:
  - cart_id
  - product_id (quickly find items by product)
```

### Outbox Events Table

```sql
outbox_events:
  id              UUID PRIMARY KEY
  cart_id         UUID NOT NULL
  event_type      VARCHAR(100)
  payload         JSONB NOT NULL
  created_at      TIMESTAMP NOT NULL DEFAULT now()
  published_at    TIMESTAMP (NULL until CDC)
```

---

## Resilience Configuration

### Outbox Pattern

Cart updates are written to `outbox_events` within the same database transaction:

```
User adds item
→ INSERT into cart_items
→ UPDATE carts (total, item_count)
→ INSERT into outbox_events
→ COMMIT (all or nothing)
→ CDC relay polls and publishes to Kafka
```

**Guarantee:** At-least-once delivery (CDC retries until offset commits)

### Real-Time Integration with Pricing Service

Cart validation calls `pricing-service` synchronously:

```java
// In CartService.validateCart()
PriceCalculationResponse pricing = pricingClient.calculateCartPrice(request);
// If pricing service is down: Circuit breaker half-open, return cached quote or 503
```

**Circuit Breaker Config:**
- Failure threshold: 50%
- Wait duration: 30s
- Slow call threshold: 1000ms

### Inventory Synchronization

Kafka consumer listens to `inventory.events`:

```
ProductStockChanged (inStock = false)
→ Remove from active carts with CartUpdated event
→ Notify user (push notification via notification-service)
```

---

## Kafka Events

### Consumed Topics

**inventory.events**
```
Event: ProductStockChanged
Payload: { productId, inStock, quantity }
Action: If inStock = false, remove product from carts
```

**catalog.events**
```
Event: ProductDeactivated
Payload: { productId }
Action: Remove product from carts
```

### Published Topics

**cart.events** (Partition Key: userId)
```
CartCreated, CartUpdated, CartValidated events
Published via outbox/CDC relay
```

---

## Testing & Validation

### Test Coverage

```bash
./gradlew :services:cart-service:test
./gradlew :services:cart-service:integrationTest
```

### Load Testing

Typical cart sizes: 3-10 items
Peak add/update rate: 10K RPS per pod

---

## Deployment

### Local

```bash
export CART_DB_URL=jdbc:postgresql://localhost:5432/carts
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
./gradlew :services:cart-service:bootRun
```

### Kubernetes

```bash
kubectl apply -f k8s/cart-service/deployment.yaml
kubectl set image deployment/cart-service cart-service=cart-service:v1.2.3
```

---

## Known Limitations

1. No persistent cart sync across devices (session-only)
2. No cart sharing/gift carts
3. No cart recommendations
4. No cart expiry notifications

**Roadmap (Wave 34+):**
- Cross-device cart sync
- Cart recommendations based on browsing history
- Abandoned cart recovery emails
- Gift cart functionality

