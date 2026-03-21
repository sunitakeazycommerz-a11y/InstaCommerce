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

## Pricing Engine Architecture

**Multi-Tier Pricing System:**

```
Request: Calculate price for user + items + context

Layer 1: Whitelisted Users
  ├─ VIP customers: Fixed 10% discount
  ├─ Loyalty PLATINUM: Dynamic 15% + points
  └─ → Return early with white-glove price

Layer 2: Location-Based Pricing
  ├─ Store ID / Region / Country
  ├─ Base price + location markup (e.g., +5% metro premium)
  └─ Cache per location (30s TTL)

Layer 3: Surge Pricing
  ├─ Check demand level (order volume in last 15 min)
  ├─ HIGH demand (> 1000 orders/15min) → +20% markup
  ├─ MEDIUM demand (> 500 orders/15min) → +10% markup
  ├─ LOW demand (< 500 orders/15min) → No markup
  └─ Cooldown: 30 min (prevent rapid flapping)

Layer 4: Promotions
  ├─ Bundle discount (buy 3+ categories → 5% off)
  ├─ Threshold discount (order > $50 → 5% off)
  ├─ Loyalty tier (points multiplier based on tier)
  └─ Apply greedily (largest discount first)

Layer 5: Coupon Redemption
  ├─ Coupon code lookup
  ├─ Validity check (expiry, max uses)
  ├─ Deduplication check (user can't use twice same order)
  └─ Apply fixed or percentage discount

Final: Tax & Fees (not pricing service responsibility)

Return: Price breakdown with all applied rules + confidence score
```

## Surge Pricing Triggers & Cooldown

**Demand Calculation:**

```
every 15 seconds:
  volume_15min = COUNT(orders WHERE created_at > NOW() - 15 minutes)

  IF volume_15min > 1000 AND last_surge_adjustment > 30 min ago:
    surge_level = HIGH
    price_multiplier = 1.20
    trigger_event: SurgePricingActivated
    cooldown_until = NOW() + 30 min

  IF volume_15min > 500:
    surge_level = MEDIUM
    price_multiplier = 1.10
    cooldown_until = NOW() + 15 min
```

**Cooldown Logic (prevents rapid flapping):**

```
Active surge: Until cooldown_until timestamp
After cooldown_until: Surge can trigger again if demand high

Example:
  14:00:00 - Demand spikes → Surge activated (multiplier 1.20)
  14:01:00 - Demand drops, but cooldown_until = 14:30:00
  14:02:00 - Still applying 1.20 multiplier (cooldown in effect)
  14:30:00 - Cooldown expires, check demand again
  14:30:01 - Demand normal, revert to 1.0x multiplier
```

**Alerts:**

```yaml
metrics:
  - pricing_surge_activated_count (counter)
  - pricing_surge_active (gauge, 0/1)
  - pricing_surge_duration_minutes (histogram)
  - pricing_surge_average_multiplier (gauge)
```

## Location-Based Pricing Variations

**Regional Price Rules:**

```
Primary Markets (Metro areas with high delivery cost):
  - New Delhi, Bangalore, Mumbai, Hyderabad
  - Price adjustment: +5% base price

Tier 2 Markets (Mid-sized cities):
  - Pune, Chennai, Jaipur, etc.
  - Price adjustment: +2% base price

Tier 3 Markets (Smaller cities):
  - Rural, remote areas
  - Price adjustment: -2% base price (promotional)

Store-Level Overrides:
  - Specific store can override regional pricing
  - Requires approval from pricing admin
```

**Implementation:**

```java
// Location lookup from order context
Location location = locationService.getByStoreId(storeId);

// Apply location multiplier
regionMultiplier = location.priceMultiplier; // e.g., 1.05
adjustedPrice = basePrice * regionMultiplier;
```

## Real-Time Price Updates & Staleness Detection

**Price Freshness Guarantee:**

```
Quote Generation:
  1. Generate quote ID + token (HMAC signed)
  2. Store quote data in Redis (5-min TTL)
  3. Return quote + HMAC token to client

Checkout Validation:
  1. Client submits quote token
  2. Pricing-service verifies HMAC (no tampering)
  3. Check if quote expired (> 5 min old)
  4. Recalculate current price
  5. IF current price ≠ quoted price:
     → Alert user "Price changed" (show delta)
     → User must re-accept
```

**Price Staleness Metrics:**

```
metric: pricing_quote_age_seconds
  - p50: 30s (typical checkout time)
  - p95: 2min (slow users)
  - p99: 4min (very slow)

Alert if: quote age > 5min (SLA breach)
```

## A/B Testing Framework for Price Experiments

**Experiment Workflow:**

```
1. Create Experiment:
   {
     "name": "surge_pricing_threshold_test",
     "control": { "surge_threshold": 1000 },
     "variant_a": { "surge_threshold": 800 },
     "variant_b": { "surge_threshold": 1200 },
     "traffic_split": { "control": 33%, "a": 33%, "b": 34% }
   }

2. Assignment:
   user_hash = HASH(user_id + experiment_name)
   if (user_hash % 100) < 33: → control
   if (user_hash % 100) < 66: → variant_a
   else → variant_b

3. Tracking:
   - Revenue per variant
   - Conversion rate per variant
   - User satisfaction (NPS diff)
   - Order volume impact

4. Analysis (weekly):
   - Variant B: +5% revenue, +2% conversion → WIN
   - Ramp to 100% traffic
   - Retire old variant
```

**Configuration:**

```yaml
experiments:
  - name: surge_pricing_threshold_test
    status: ACTIVE
    startDate: 2025-03-21
    endDate: 2025-04-04
    variants:
      - name: control
        config:
          surge_threshold: 1000
          traffic_pct: 33
      - name: variant_a
        config:
          surge_threshold: 800
          traffic_pct: 33
      - name: variant_b
        config:
          surge_threshold: 1200
          traffic_pct: 34
```

## Monitoring & Alerts (20+ Metrics)

### Key Metrics

| Metric | Type | Alert Threshold | Purpose |
|--------|------|-----------------|---------|
| `pricing_calculate_latency_ms` | Histogram (p99) | > 100ms | Calculation speed |
| `pricing_calculate_success_rate` | Gauge | < 99.9% = alert | Calculation reliability |
| `pricing_coupon_lookup_latency_ms` | Histogram | > 50ms | Coupon lookup speed |
| `pricing_coupon_invalid_rate` | Gauge | N/A | Invalid coupon attempts |
| `pricing_coupon_redemption_rate` | Gauge | < 5% = investigate | Coupon usage % |
| `pricing_coupon_double_spend_attempts` | Counter | > 0 = alert | Fraud prevention |
| `pricing_surge_active_pct` | Gauge | N/A | % of time surge active |
| `pricing_surge_average_multiplier` | Gauge | N/A | Avg surge multiplier |
| `pricing_surge_revenue_impact` | Gauge | N/A | Revenue delta vs baseline |
| `pricing_location_markup_applied_pct` | Gauge | N/A | % requests with location markup |
| `pricing_promotion_applied_pct` | Gauge | N/A | % orders with promotions |
| `pricing_promotion_conflict_count` | Counter | N/A | Promotion conflicts detected |
| `pricing_quote_expired_count` | Counter | > 10/min = investigate | Expired quote attempts |
| `pricing_quote_tampering_attempts` | Counter | > 0 = SEV-1 | HMAC validation failures |
| `pricing_quote_recalculation_delta_cents` | Histogram | p95 > 500 = investigate | Price change at checkout |
| `pricing_discount_applied_avg_cents` | Gauge | N/A | Average discount per order |
| `pricing_catalog_sync_lag_seconds` | Gauge | > 60 = alert | Stale product prices |
| `pricing_promotion_sync_lag_seconds` | Gauge | > 300 = alert | Stale promotions |
| `pricing_ab_test_traffic_distribution` | Gauge (by variant) | Check balance | Traffic split correctness |
| `pricing_ab_test_revenue_by_variant` | Gauge (by variant) | N/A | Revenue per variant |

### Alerting Rules

```yaml
alerts:
  - name: PricingLatencySlow
    condition: histogram_quantile(0.99, pricing_calculate_latency_ms) > 150
    duration: 5m
    severity: SEV-2
    action: Check database load; possible n+1 query

  - name: CouponDoubleSpendAttempt
    condition: rate(pricing_coupon_double_spend_attempts[1m]) > 0
    duration: 1m
    severity: SEV-1
    action: Page on-call; fraud attempt; check UNIQUE constraint

  - name: QuoteTamperingDetected
    condition: rate(pricing_quote_tampering_attempts[5m]) > 0
    duration: 5m
    severity: SEV-1
    action: Security incident; HMAC validation failed; possible attack

  - name: PriceRecalculationDelta
    condition: histogram_quantile(0.95, pricing_quote_recalculation_delta_cents) > 500
    duration: 10m
    severity: SEV-2
    action: Check if surge pricing changing during checkout; user impact

  - name: SurgeRevenueDelta
    condition: pricing_surge_revenue_impact > 0.15
    duration: 1h
    severity: SEV-3
    action: Monitor if surge pricing too aggressive; review pricing strategy

  - name: CatalogSyncLag
    condition: pricing_catalog_sync_lag_seconds > 60
    duration: 5m
    severity: SEV-2
    action: Check CDC relay; product prices stale; possible arbitrage
```

## Security Considerations

### Threat Mitigations

1. **Price Tampering Prevention**: Quote tokens signed with HMAC-SHA256 (client can't modify)
2. **Double-Spending Prevention**: UNIQUE constraint on (order_id, coupon_id) prevents double-redemption
3. **Discount Stack Overflow**: MAX discount capped at 50% (prevent -ve prices)
4. **Concurrency Safety**: @Version on coupon table prevents concurrent updates
5. **Audit Trail**: All coupon redemptions logged immutably (7-year retention)

### Known Risks

- **Pricing Bug**: Calculation error could undercharge or overcharge (mitigated by unit tests + manual review)
- **Promotion Explosion**: Multiple overlapping promos could compound unexpectedly (mitigated by greedy selection)
- **Surge Pricing Abuse**: Could spike prices unfairly (mitigated by cooldown + alerts)
- **Coupon Code Leak**: Private coupon exposed publicly (mitigated by revocation + audit)

## Troubleshooting (8+ Scenarios)

### Scenario 1: Pricing Calculation Timeout (100ms SLA breached)

**Indicators:**
- `pricing_calculate_latency_ms` p99 > 200ms
- Checkout taking longer than expected
- Database queries slow

**Root Causes:**
1. Product lookup N+1 query
2. Promotion rule evaluation too complex
3. Database connection pool exhausted
4. Network latency

**Resolution:**
```bash
# Check slow queries
SELECT query, mean_exec_time FROM pg_stat_statements
WHERE mean_exec_time > 50
ORDER BY mean_exec_time DESC;

# Profile pricing service
curl http://pricing-service:8087/admin/profiling/start

# Run test batch
curl -X POST http://pricing-service:8087/admin/profiling/run-test \
  -d '{"iterations": 1000}'

# Analyze results
curl http://pricing-service:8087/admin/profiling/results

# Optimize if needed (add index, cache, etc.)
```

### Scenario 2: Coupon Double-Spend Detected (User charged twice)

**Indicators:**
- `pricing_coupon_double_spend_attempts` counter > 0
- Audit shows same coupon redeemed twice for same order
- User complaint: "Charged twice"

**Root Causes:**
1. UNIQUE constraint missing on (order_id, coupon_id)
2. Idempotency key not working
3. Database replication lag (duplicate inserted on replica)

**Resolution:**
```bash
# Verify UNIQUE constraint exists
SELECT constraint_name FROM information_schema.table_constraints
WHERE table_name = 'coupon_redemptions'
AND constraint_type = 'UNIQUE';

# If missing, add constraint
ALTER TABLE coupon_redemptions
ADD CONSTRAINT unique_order_coupon UNIQUE (order_id, coupon_id);

# Find duplicate redemptions
SELECT order_id, coupon_id, COUNT(*)
FROM coupon_redemptions
GROUP BY order_id, coupon_id
HAVING COUNT(*) > 1;

# Investigate and potentially refund duplicate charge
curl -X POST http://pricing-service:8087/admin/coupon-redemption/{redemptionId}/reverse
```

### Scenario 3: Surge Pricing Not Triggering (Despite high demand)

**Indicators:**
- Order volume > 1000/15min
- `pricing_surge_active_pct` = 0% (should be active)
- Revenue lower than expected

**Root Causes:**
1. Demand calculation bug (not counting correctly)
2. Cooldown still active from previous surge
3. Configuration disabled surge pricing

**Resolution:**
```bash
# Check surge config
curl http://pricing-service:8087/admin/config/surge-pricing

# Manual demand count
SELECT COUNT(*) FROM orders
WHERE created_at > NOW() - INTERVAL '15 minutes';

# Check surge state
curl http://pricing-service:8087/admin/surge/status

# Force surge activation for testing
curl -X POST http://pricing-service:8087/admin/surge/activate \
  -d '{"multiplier": 1.20, "durationMinutes": 10}'

# Check if configuration correct
PRICING_SURGE_THRESHOLD=1000 (threshold for HIGH demand)
```

### Scenario 4: Location-Based Pricing Not Applied (Regional discount missing)

**Indicators:**
- Same product, different prices expected but prices match
- `pricing_location_markup_applied_pct` = 0%
- Regional pricing revenue impact = 0

**Root Causes:**
1. Location lookup failing (returning null)
2. Price multiplier not being applied
3. Cache returning stale data

**Resolution:**
```bash
# Test location lookup
curl http://pricing-service:8087/admin/location/test \
  -d '{"storeId":"store-uuid"}'

# Check location cache
redis-cli GET pricing:location:store-uuid

# Verify location configuration
curl http://pricing-service:8087/admin/config/location-pricing

# Manually clear location cache
redis-cli DEL pricing:location:*

# Force cache refresh
curl -X POST http://pricing-service:8087/admin/cache/refresh-locations

# Check if location data in database
SELECT store_id, region, price_multiplier FROM location_pricing;
```

### Scenario 5: Quote Token Validation Failing (HMAC mismatch)

**Indicators:**
- `pricing_quote_tampering_attempts` counter increasing
- Users see "Invalid quote" errors at checkout
- HTTP 400 response: "INVALID_QUOTE_TOKEN"

**Root Causes:**
1. Quote token corrupted in transit
2. HMAC key rotated (old tokens invalid)
3. Client-side token manipulation attempt

**Resolution:**
```bash
# Check HMAC key status
curl http://pricing-service:8087/admin/hmac-key/status

# Verify key hasn't been rotated recently
SELECT created_at, rotated_at FROM hmac_keys
WHERE status = 'ACTIVE';

# If key rotated, tokens need regeneration
curl -X POST http://pricing-service:8087/admin/quotes/regenerate-all

# Check for active tampering attempts
SELECT COUNT(*) as attempt_count
FROM quote_validation_failures
WHERE created_at > NOW() - INTERVAL '1 hour'
AND failure_reason = 'HMAC_MISMATCH';

# If attack suspected, alert security team
```

### Scenario 6: Promotion Rule Conflict (Unclear which discount applied)

**Indicators:**
- `pricing_promotion_conflict_count` counter increasing
- Users see unexpected discounts
- Pricing logic unclear (which rule applied?)

**Root Causes:**
1. Multiple overlapping promotions (bundle + threshold)
2. Promotion conflict resolution unclear
3. New promotion added without testing

**Resolution:**
```bash
# Check active promotions
curl http://pricing-service:8087/admin/promotions/active

# Test pricing with conflict scenario
curl -X POST http://pricing-service:8087/admin/test-pricing \
  -d '{
    "items": [...],
    "promoCode": "BUNDLE10",
    "showConflicts": true
  }'

# Review promotion priority
curl http://pricing-service:8087/admin/config/promotion-priority

# Adjust if needed
PROMOTION_PRIORITY=["BUNDLE", "THRESHOLD", "LOYALTY", "COUPON"]

# Restart service
kubectl rollout restart deployment/pricing-service
```

### Scenario 7: A/B Test Traffic Distribution Imbalanced (Wrong split)

**Indicators:**
- Experiment traffic split: expected 33/33/34, actual 50/30/20
- Variant revenue comparison unreliable
- User complaints: "I'm always seeing variant A"

**Root Causes:**
1. Hash function biased
2. User ID distribution skewed
3. Assignment logic bug

**Resolution:**
```bash
# Check actual traffic distribution
SELECT variant, COUNT(*) as count,
       100.0 * COUNT(*) / SUM(COUNT(*)) OVER () as pct
FROM pricing_ab_test_assignments
WHERE experiment_name = 'surge_pricing_threshold_test'
GROUP BY variant;

# Verify hash distribution
curl http://pricing-service:8087/admin/experiments/test-hash-distribution \
  -d '{"experimentName":"surge_pricing_threshold_test","samples":10000}'

# If hash function OK, check assignment logic
-- Verify modulo operation correct: hash % 100 < target_pct

# If assignment logic OK, check data quality
-- Verify user_id values uniformly distributed

# If all checks pass, traffic might legitimately skew during ramp-up
-- Continue monitoring
```

### Scenario 8: Catalog Product Price Out of Sync

**Indicators:**
- `pricing_catalog_sync_lag_seconds` > 60
- Product price in pricing-service ≠ catalog-service
- Customers see old price

**Root Causes:**
1. CDC relay lagging (Kafka message delayed)
2. Product update event dropped
3. Pricing-service cache stale

**Resolution:**
```bash
# Check CDC relay lag
SELECT MAX(LAG_SECONDS) FROM cdc_lag_metrics
WHERE topic = 'catalog.events';

# Check Kafka consumer lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group pricing-catalog-consumer --describe

# Manually sync product
curl -X POST http://pricing-service:8087/admin/catalog/sync \
  -d '{"productId":"product-uuid"}'

# Check if event was published
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic catalog.events \
  --from-beginning \
  --max-messages 20 | grep product-uuid

# If event missing, notify catalog-service team
```

## Production Runbook Patterns

### Runbook 1: Surge Pricing Emergency Disable

**Scenario:** Surge multiplier too aggressive, revenue dropped

**SLA:** < 5 min to disable

1. **Alert Received:** SurgeRevenueDelta alert (surge impact > 15%)
2. **Verify:** Check if surge actually active + check revenue
3. **Disable:** Feature flag `PRICING_SURGE_ENABLED=false`
4. **Restart:** `kubectl rollout restart deployment/pricing-service`
5. **Monitor:** Revenue should recover
6. **Investigate:** Post-incident review why surge was too aggressive

### Runbook 2: Coupon Code Revocation (Leaked code fix)

**Scenario:** Private coupon code leaked publicly, too many users using

1. **Immediate:** Disable coupon code
   ```bash
   curl -X POST http://pricing-service:8087/admin/coupons/{couponId}/disable
   ```
2. **Communication:** Notify users "Coupon expired"
3. **Audit:** Count affected orders, cost impact
4. **Refund:** Manual refund process if needed
5. **Prevention:** Code review before next coupon release

## Related Documentation

- **ADR-013**: Price Quote Validation Strategy
- **ADR-014**: A/B Testing Framework for Experiments
- **Runbook**: pricing-service/runbook.md

## Configuration

```env
SERVER_PORT=8087

PRICING_SURGE_ENABLED=true
PRICING_SURGE_THRESHOLD=1000
PRICING_SURGE_MULTIPLIER_HIGH=1.20
PRICING_SURGE_MULTIPLIER_MEDIUM=1.10
PRICING_SURGE_COOLDOWN_MINUTES=30

PRICING_LOCATION_PRICING_ENABLED=true
PRICING_LOCATION_CACHE_TTL=30

PRICING_AB_TEST_ENABLED=true
PRICING_AB_TEST_HASH_FUNCTION=murmur3

SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/pricing
SPRING_DATASOURCE_HIKARI_POOL_SIZE=30

OTEL_TRACES_SAMPLER=always_on
```

## Known Limitations

1. No real-time A/B pricing (A/B test weekly, not hourly)
2. No inventory-aware dynamic pricing (stock level independent)
3. No loyalty multipliers in base calculation (separate call)
4. No geolocation-based pricing granularity (city-level only, not neighborhood)
5. No subscription/recurring billing (one-time payments only)
6. No price forecast/prediction (no ML models)

**Roadmap (Wave 41+):**
- Real-time A/B pricing (hourly updates)
- Inventory-aware dynamic pricing (low stock = higher price)
- Loyalty tier pricing integration (direct, not separate)
- Granular geolocation pricing (neighborhood-level)
- Subscription support
- ML-based demand forecasting
- Competitor price tracking

