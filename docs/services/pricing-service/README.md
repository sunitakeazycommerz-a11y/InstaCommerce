# Pricing Service

**Real-time pricing calculations, coupon redemption, promotion rule evaluation, and price quote generation with optimistic locking and Wave 30 money-path safety enhancements.**

| Property | Value |
|----------|-------|
| **Module** | `:services:pricing-service` |
| **Port** | `8087` |
| **Runtime** | Spring Boot 4.0 · Java 21 |
| **Database** | PostgreSQL 15+ (Flyway-managed, @Version locking) |
| **Messaging** | Kafka consumer (catalog.events, promotion.events) |
| **Resilience** | Resilience4j rate limiter, circuit breaker |
| **Auth** | JWT RS256 |
| **Owner** | Pricing & Promotions Team |
| **SLO** | P50: 20ms, P99: 80ms, 99.95% availability |

---

## Service Role & Ownership

**Owns:**
- `coupons` table — coupon master (code, discount, expiry, @Version locking per Wave 30)
- `coupon_redemptions` table — per-order redemption tracking (unique constraint: order_id per coupon)
- `promotions` table — time-based and segment-based promotions
- `pricing_tiers` table — volume/customer-tier based pricing

**Does NOT own:**
- Product master data (→ `catalog-service`, consumed via events)
- Order data (→ `order-service`, provides order context for pricing)
- Effective stock (→ `inventory-service`)

**Consumes:**
- `catalog.events` — ProductUpdated (price changes)
- `promotion.events` — PromotionCreated, PromotionEnded

**Publishes:**
- `pricing.events` — PriceQuoteGenerated, CouponRedeemed (via outbox/CDC)

---

## Core APIs

### Calculate Cart Price

**POST /pricing/calculate**
```
Request Body:
{
  "items": [
    {
      "productId": "uuid",
      "quantity": 2,
      "basePriceCents": 5000
    }
  ],
  "userId": "user-uuid",
  "couponCode": "SAVE10",  // optional
  "storeId": "store-uuid"   // for location-specific pricing
}

Response (200):
{
  "subtotalCents": 10000,
  "discountCents": 1000,
  "totalCents": 9000,
  "breakdown": [
    {
      "productId": "uuid",
      "quantity": 2,
      "basePriceCents": 5000,
      "finalPriceCents": 4500,
      "discountApplied": "PROMOTION_BUNDLE"
    }
  ],
  "quoteId": "quote-uuid",
  "quoteToken": "base64-hmac",
  "expiresAt": "2025-03-21T10:05:00Z"
}

Error: 400 if coupon invalid/expired, 429 if rate limited
```

### Get Product Price

**GET /pricing/products/{id}**
```
Response (200):
{
  "productId": "uuid",
  "basePriceCents": 5000,
  "discountedPriceCents": 4500,
  "promotionCode": "EASTER",
  "validUntil": "2025-04-01T00:00:00Z"
}
```

### Redeem Coupon

**POST /pricing/coupons/redeem** (Wave 30 idempotency)
```
Request Body:
{
  "code": "SAVE10",
  "userId": "user-uuid",
  "orderId": "order-uuid",
  "discountCents": 1000
}

Response (200): Success

Implementation (Wave 30):
  1. Find coupon by code
  2. Check version hasn't been updated (optimistic lock)
  3. INSERT into coupon_redemptions (userId, orderId, couponId, redeemedAt)
     ON CONFLICT (orderId, coupon_id) DO NOTHING
  4. Return success (idempotent: same order_id + coupon = same result)

This ensures:
  - Double-spending is prevented (unique constraint on order_id per coupon)
  - Redemption is idempotent (safe to retry)
  - @Version tracks concurrent updates
```

### Validate Quote

**POST /pricing/quotes/validate**
```
Request Body:
{
  "quoteId": "quote-uuid",
  "quoteToken": "base64-hmac",
  "totalCents": 9000,
  "subtotalCents": 10000,
  "discountCents": 1000
}

Response (200):
{
  "isValid": true,
  "expiresAt": "2025-03-21T10:05:00Z"
}

Validation:
  1. Verify quote hasn't expired
  2. Verify HMAC token (prevent tampering)
  3. Check total matches (no manipulation)
```

---

## Database Schema

### Coupons Table (Wave 30: @Version)

```sql
coupons:
  id              UUID PRIMARY KEY
  code            VARCHAR(50) NOT NULL UNIQUE
  discount_type   ENUM('FIXED', 'PERCENTAGE')
  discount_amount BIGINT NOT NULL (cents or basis points)
  max_uses        INT
  current_uses    INT NOT NULL DEFAULT 0
  valid_from      TIMESTAMP NOT NULL
  valid_to        TIMESTAMP NOT NULL
  applicable_products  UUID[] (optional: restrict to specific products)
  version         BIGINT NOT NULL DEFAULT 0 (@Version for optimistic locking)
  created_at      TIMESTAMP NOT NULL
  updated_at      TIMESTAMP NOT NULL

Indexes:
  - code (lookup by code)
  - valid_to (find active coupons)
```

### Coupon Redemptions Table (Wave 30: Money-path safety)

```sql
coupon_redemptions:
  id              UUID PRIMARY KEY
  coupon_id       UUID NOT NULL (FK)
  user_id         UUID NOT NULL
  order_id        UUID NOT NULL
  discount_cents  BIGINT NOT NULL
  redeemed_at     TIMESTAMP NOT NULL DEFAULT now()

Indexes:
  - UNIQUE (order_id, coupon_id) (prevent double-spending per order)
  - user_id, redeemed_at (user redemption history)
```

### Promotions Table

```sql
promotions:
  id              UUID PRIMARY KEY
  name            VARCHAR(255) NOT NULL
  rule_type       ENUM('BUNDLE', 'THRESHOLD', 'SEASONAL', 'LOYALTY')
  discount_pct    DECIMAL(5,2)
  min_cart_value  BIGINT
  applicable_categories VARCHAR[] or JSONB
  valid_from      TIMESTAMP NOT NULL
  valid_to        TIMESTAMP NOT NULL
  status          ENUM('ACTIVE', 'PAUSED', 'EXPIRED')
  created_at      TIMESTAMP NOT NULL
```

---

## Pricing Calculation Flow

```
User adds cart, requests quote:

1. Calculate base prices (product cost * quantity)
2. Apply per-product promotions (bundle, loyalty)
3. Apply coupon if provided (FIXED or PERCENTAGE)
4. Apply cart-level promotions (min spend)
5. Generate quote ID + HMAC token
6. Publish PriceQuoteGenerated event (via outbox)
7. Return to cart-service

Total = Subtotal - Discount

Coupon application (Wave 30):
  - UNIQUE constraint prevents double-use
  - @Version on coupon prevents concurrent modification
  - INSERT ... ON CONFLICT ensures idempotency
```

---

## Resilience Configuration

### Rate Limiting

```yaml
resilience4j:
  ratelimiter:
    instances:
      pricingLimiter:
        limitForPeriod: 5000
        limitRefreshPeriod: 1s
        timeoutDuration: 0s
```

### Circuit Breaker

Calls to `catalog-service` for product data:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      catalogClient:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s
        slowCallRateThreshold: 80%
        slowCallDurationThreshold: 500ms
```

### Timeout

- Database query timeout: 5s
- External service call timeout: 2s

---

## Kafka Events

### Consumed

**catalog.events**: ProductUpdated → Update base prices
**promotion.events**: PromotionCreated → Update promotion rules

### Published

**pricing.events** (via outbox):
```
PriceQuoteGenerated: { quoteId, totalCents, userId, itemCount, expiresAt }
CouponRedeemed: { couponId, userId, orderId, discountCents }
```

---

## Testing

```bash
./gradlew :services:pricing-service:test
./gradlew :services:pricing-service:integrationTest
```

Test scenarios:
- Coupon expiry
- Double-spending prevention (Wave 30)
- Quote tampering detection
- Rate limit enforcement
- Concurrent coupon updates (@Version)

---

## Deployment

```bash
export PRICING_DB_URL=jdbc:postgresql://localhost:5432/pricing
./gradlew :services:pricing-service:bootRun
```

---

## Known Limitations

1. No real-time A/B pricing
2. No inventory-aware dynamic pricing
3. No loyalty multipliers in base calculation
4. No geolocation-based pricing

**Roadmap (Wave 34+):**
- Dynamic pricing based on demand
- Loyalty tier pricing integration
- A/B test price variations
- Regional pricing rules

