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
| **Auth** | JWT RS256 + per-service token isolation |
| **Owner** | Cart & Checkout Team |
| **Status** | Tier 1 (Checkout Critical Path) |

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month)
- **P50 Latency**: < 50ms per operation
- **P95 Latency**: < 150ms per operation
- **P99 Latency**: < 300ms per operation
- **Error Rate**: < 0.05% (strict SLA for critical path)
- **Cart Consistency**: 100% (ACID transactions)
- **Max Throughput**: 50K requests/second per pod (10K RPS add/update operations)
- **Cache Hit Rate**: > 85% (Caffeine local cache)
- **Pricing Sync Latency**: < 200ms p95 (real-time quote generation)

---

## Key Responsibilities

1. **Cart State Management**: Maintain per-user shopping carts with full ACID semantics; support concurrent add/update/remove operations with optimistic locking
2. **Real-Time Availability Sync**: Consume inventory.events to invalidate items when stock becomes unavailable; automatically remove from carts and notify users
3. **Price Snapshot & Quote Generation**: Call pricing-service synchronously to compute current totals; store price snapshots at add-time for historical accuracy
4. **Concurrency Conflict Resolution**: Detect and resolve cart modification conflicts using version columns; retry logic with exponential backoff (100ms→1s)
5. **Event-Driven Persistence**: Publish CartCreated, CartUpdated, CartValidated events via transactional outbox pattern; guarantee at-least-once delivery to Kafka

## Deployment

### GKE Deployment
- **Namespace**: checkout
- **Replicas**: 3 (HA)
- **CPU Request/Limit**: 500m / 1000m
- **Memory Request/Limit**: 512Mi / 1024Mi
- **Startup probe**: 10s delay, 30s timeout (for database migrations)
- **Liveness probe**: `/actuator/health/live` every 10s
- **Readiness probe**: `/actuator/health/ready` (includes DB + Kafka consumer group status)

### Cluster Configuration
- **Ingress**: Behind Istio IngressGateway (VirtualService → cart-service:8088)
- **NetworkPolicy**: Allow from checkout-orchestrator-service + mobile-bff-service
- **Service Account**: cart-service
- **Pod Disruption Budget**: minAvailable: 2 (protect against cluster churn)

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│ Client (Mobile/Web)                                             │
└────────────────────────┬────────────────────────────────────────┘
                         │ JWT Token (cart-service audience)
                         ▼
       ┌─────────────────────────────────────┐
       │ Istio IngressGateway                 │
       │ VirtualService → cart-service:8088   │
       └────────────┬────────────────────────┘
                    │
        ┌───────────▼─────────────┐
        │  Cart-Service (Replicas=3)           │
        │ ┌───────────────────────┐ │
        │ │ Spring Boot 4.0       │ │
        │ │ Port: 8088            │ │
        │ │ JWT AuthFilter        │ │
        │ └────────┬──────────────┘ │
        │          │                │
        │ ┌────────▼──────────────┐ │
        │ │ Caffeine Cache (24h)  │ │
        │ │ cartId → CartDTO      │ │
        │ └────────────────────────┘ │
        │          │                │
        │ ┌────────▼──────────────┐ │
        │ │ CartService (core)    │ │
        │ │ - addItem()           │ │
        │ │ - validateCart()      │ │
        │ │ - updateQuantity()    │ │
        │ └────────┬──────────────┘ │
        └─────────┬──────────────────┘
                  │
    ┌─────────────┼─────────────────────────────────┐
    │             │                                 │
    ▼             ▼                                 ▼
PostgreSQL      pricing-service                 Kafka
carts           POST /pricing/calculate         Produces:
cart_items      (circuit breaker: 30s timeout)  - CartCreated
outbox_events   Fallback: cached quote          - CartUpdated
                                                - CartValidated

    │             │                                 ▲
    │             │ Consume:                        │
    │             │ - Product prices                │ Outbox
    │             │ - Promotions                    │ CDC Relay
    │             │                                 │
    └─────────────┴────────────────────────────────┘

    Kafka Consumers:
    ┌────────────────────────────────────┐
    │ inventory.events topic             │
    │ ProductStockChanged event          │
    │ → Remove item if unavailable       │
    │ → Publish CartUpdated event        │
    └────────────────────────────────────┘

    ┌────────────────────────────────────┐
    │ catalog.events topic               │
    │ ProductDeactivated event           │
    │ → Remove item from carts           │
    └────────────────────────────────────┘
```

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

## Cart Lifecycle States

Carts transition through distinct states to manage cart recovery and analytics:

```
ACTIVE (0-7 days)
  ↓ User inactive 7 days
ABANDONED (7-30 days, retention window for recovery)
  ↓ User active / checkout
CHECKOUT (1-5 min, during order flow)
  ↓ Order confirmed
COMPLETED (1-3 days, post-order retention)
  ↓ Archived
ARCHIVED (deleted from hot storage)
```

**State Transitions:**
- `ACTIVE → CHECKOUT`: Explicit checkout action
- `ACTIVE → ABANDONED`: Scheduled job (idle 7+ days)
- `ABANDONED → ACTIVE`: Re-engagement (user adds item)
- `CHECKOUT → COMPLETED`: Order creation confirmed
- `COMPLETED → ARCHIVED`: 3 days post-order (batch cleanup)

**Retention Policies:**
- ACTIVE carts: 7-day idle timeout (soft delete to ABANDONED)
- ABANDONED carts: 30-day recovery window (re-engagement emails)
- COMPLETED carts: 3-day retention (post-purchase window for reference)

## Cart Batch Optimization

Daily scheduled job (2 AM UTC) performs:

```
1. Remove duplicate items (same product_id within same cart)
   → Consolidate quantities
2. Remove unavailable items (stock = 0)
3. Archive completed carts (> 3 days old)
4. Notify users of abandoned carts (> 7 days, low priority)
5. Compile cart metrics (avg items, avg value, conversion rate)
```

**Performance:** <30s for 1M carts processing

## Session Affinity & Multi-Device Support

**Current State (Wave 40):**
- Session-scoped: Cart persists only in single browser/app session
- Device affinity: User ID + Device ID as composite key

**Future Work (Wave 41+):**
- Cross-device sync: Migrate ACTIVE carts to user-level storage
- Cloud sync: Replicate cart state to CDN for fast retrieval
- Conflict resolution: Last-write-wins if concurrent edits

## Item Quantity Constraints

**Constraints enforced:**
```sql
CHECK(quantity > 0)                     -- No zero/negative quantities
CHECK(quantity <= 999)                  -- Max 999 per item (prevent abuse)
CHECK(item_count <= 50)                 -- Max 50 different items per cart

cart_items.quantity:
  - Min: 1 (DELETE if 0)
  - Max: 999 per product
  - Cart max items: 50 distinct products
```

**Validation Flow:**
1. User requests quantity = 100
2. Check availability from inventory-service (cached, 5s TTL)
3. If available: INSERT / UPDATE cart_items
4. If validation fails: Return 400 BAD_REQUEST with reason

## Price Synchronization with Pricing Service

**Real-Time Quote Generation:**
```
User adds item → CartService.addItem()
                   ↓
            Call pricing-service (circuit breaker: 30s)
            POST /pricing/calculate
                   ↓
            [Pricing-Service Responds]
                   ↓
            Store price snapshot in cart_items.price_cents
            Publish CartUpdated event
            Return to client (< 200ms p95)
```

**Price Staleness Detection:**
- Quote valid for 5 minutes (quoteId + HMAC token)
- Cart validation step verifies prices are current before checkout
- If price changed > 5%: Warn user, refresh quote

**Cache Strategy:**
- Per-product prices: Caffeine cache (TTL: 30s)
- Quote tokens: Redis (TTL: 5min, shared across pods)

## Promotion Conflict Detection

When multiple promotions apply:
```
1. Collect all applicable promotions (catalog search)
2. Sort by discount amount (descending)
3. Apply greedily (largest discount first)
4. Detect conflicts:
   - Bundle promotion conflicts with coupon? → Use highest discount
   - Loyalty discount + coupon? → Additive (STACK_MODE = true)
5. Log conflict decision in audit trail
6. Return breakdown showing each promotion applied
```

**Example Response:**
```json
{
  "items": [
    {
      "productId": "prod-1",
      "basePrice": 1000,
      "promotions": [
        { "name": "Bundle10%", "discount": 100 },
        { "name": "LoyaltyGold5%", "discount": 45 }
      ],
      "finalPrice": 855
    }
  ]
}
```

## Monitoring & Alerts (20+ Metrics)

### Key Metrics

| Metric | Type | Alert Threshold | Purpose |
|--------|------|-----------------|---------|
| `cart_get_latency_ms` | Histogram (p50, p95, p99) | p99 > 300ms | Cart retrieval performance |
| `cart_add_item_latency_ms` | Histogram | p99 > 200ms | Add item operation speed |
| `cart_validate_latency_ms` | Histogram | p99 > 500ms | Full validation with pricing |
| `cart_remove_item_latency_ms` | Histogram | p99 > 100ms | Remove operation speed |
| `cart_update_quantity_latency_ms` | Histogram | p99 > 150ms | Quantity update speed |
| `cart_active_count` | Gauge | N/A | Currently active carts (memory pressure indicator) |
| `cart_abandoned_count` | Gauge | N/A | Carts > 7 days idle (recovery opportunity) |
| `cart_avg_items` | Gauge | < 2 items = investigate | Average items per cart (product engagement) |
| `cart_avg_value_cents` | Gauge | N/A | Average cart value in cents |
| `pricing_service_call_latency_ms` | Histogram (p99) | > 500ms = degrade | Real-time pricing latency |
| `pricing_service_error_rate` | Gauge | > 5% = circuit open | Pricing service health |
| `inventory_service_call_latency_ms` | Histogram | > 300ms = degrade | Inventory availability check |
| `inventory_service_cache_hit_rate` | Gauge | < 70% = investigate | Cache effectiveness |
| `caffeine_cache_hit_rate` | Gauge | < 85% = investigate | Local cart cache hit rate |
| `caffeine_eviction_rate` | Gauge | N/A | Cache eviction patterns (memory churn) |
| `cart_validation_failures` | Counter (by reason) | > 100/min = investigate | Validation failure tracking |
| `cart_item_unavailable_count` | Counter | > 500/hour = investigate | Product unavailability incidents |
| `outbox_event_publish_latency_ms` | Histogram | p99 > 100ms | Outbox CDC relay latency |
| `db_connection_pool_active` | Gauge | > 27/30 = contention | Database connection pool usage |
| `http_requests_total` | Counter (by endpoint) | N/A | Total request traffic |
| `http_4xx_errors_total` | Counter | N/A | Client errors (validation issues) |
| `http_5xx_errors_total` | Counter | > 0.5% = alert | Server errors |

### Alerting Rules

```yaml
alerts:
  - name: CartServiceP99Latency
    condition: histogram_quantile(0.99, cart_validate_latency_ms) > 500
    duration: 5m
    severity: SEV-2
    action: Check pricing-service health; possible database contention

  - name: PricingServiceDown
    condition: pricing_service_error_rate > 0.05
    duration: 2m
    severity: SEV-1
    action: Page on-call; circuit breaker open; fallback to cached prices

  - name: InventoryServiceDown
    condition: rate(inventory_service_error_count[5m]) > 10
    duration: 2m
    severity: SEV-1
    action: Page on-call; inventory validation broken; checkout at risk

  - name: CacheHitRateLow
    condition: caffeine_cache_hit_rate < 0.7
    duration: 10m
    severity: SEV-3
    action: Investigate cache invalidation; possible memory leak

  - name: CartValidationFailures
    condition: rate(cart_validation_failures[1m]) > 100/60
    duration: 5m
    severity: SEV-2
    action: Check if inventory/pricing service broke; user impact
```

## Security Considerations

### Threat Mitigations

1. **Price Tampering Prevention**: Cart price snapshot validated via HMAC token before checkout
2. **Inventory Race Conditions**: Optimistic locking + inventory-service authoritative source
3. **Quantity Overflow**: Database CHECK constraints + application-level validation
4. **User Isolation**: JWT token extracts userId; users can only access own carts
5. **Distributed Lock**: Prevent concurrent add/remove conflicts (pessimistic lock brief duration)
6. **Outbox Pattern**: At-least-once event delivery prevents lost cart updates

### Known Risks

- **Pricing-Service Down**: Fallback to cached quote (stale prices possible)
- **Concurrent Modifications**: Optimistic locking retries may cause 409 errors under high concurrency
- **Cache Poisoning**: Malicious price in Caffeine cache (mitigated by HMAC validation)
- **Inventory Desync**: Inventory-service decrements stock, cart doesn't see update immediately (eventual consistency)

## Troubleshooting (8+ Scenarios)

### Scenario 1: Cart Operation Timeout (504 errors spike)

**Indicators:**
- `cart_validate_latency_ms` p99 > 1000ms
- Downstream service timeouts (pricing or inventory)

**Root Causes:**
1. Pricing-service latency spike or overload
2. Inventory-service latency spike
3. Database connection pool exhausted
4. Slow network (Istio/firewall)

**Resolution:**
```bash
# Check pricing-service health
curl http://pricing-service:8087/actuator/health/ready

# Check inventory-service health
curl http://inventory-service:8086/actuator/health/ready

# Check database connection pool
curl http://cart-service:8088/actuator/metrics/db_connection_pool_active

# Increase timeouts temporarily
CART_PRICING_TIMEOUT_MS=1000 (from 500)
CART_INVENTORY_TIMEOUT_MS=600 (from 300)

# Scale cart-service replicas if CPU high
kubectl scale deployment cart-service --replicas=5
```

### Scenario 2: Outbox CDC Relay Lag (Events delayed > 30s)

**Indicators:**
- `outbox_event_publish_latency_ms` p99 > 30000ms
- CartUpdated events published late to checkout-orchestrator
- Kafka consumer lag high

**Root Causes:**
1. CDC relay pod crashed or restarted
2. Kafka broker unavailable
3. Database replication lag
4. Outbox table bloated (millions of rows)

**Resolution:**
```bash
# Check CDC relay pod
kubectl get pods -n services | grep outbox-relay

# Check Kafka broker health
kafka-broker-api-versions --bootstrap-server kafka:9092

# Check outbox table size
SELECT pg_size_pretty(pg_total_relation_size('outbox_events'));

# Vacuum if needed
VACUUM ANALYZE outbox_events;

# Trigger manual outbox drain
curl -X POST http://outbox-relay:8096/admin/drain-outbox
```

### Scenario 3: Pricing Service Circuit Breaker Open (Always returning 503)

**Indicators:**
- `pricing_service_error_rate` = 100%
- Circuit breaker state = OPEN
- Requests timing out after 30s wait

**Root Causes:**
1. Pricing-service down or unreachable
2. Network connectivity broken (firewall rule)
3. Circuit breaker threshold misconfigured

**Resolution:**
```bash
# Verify pricing-service is running
kubectl get pods -n services | grep pricing

# Test network connectivity
curl http://pricing-service:8087/actuator/health/live

# Force circuit breaker reset
curl -X POST http://cart-service:8088/admin/circuit-breaker/reset

# Check circuit breaker config
curl http://cart-service:8088/actuator/metrics/resilience4j_circuitbreaker_state
```

### Scenario 4: Caffeine Cache Hit Rate Drops (Cache churn)

**Indicators:**
- `caffeine_cache_hit_rate` drops from 85% → 40%
- `caffeine_eviction_rate` spikes
- Memory usage stable (no leak)

**Root Causes:**
1. Cart IDs changing (userId migration)
2. Cache key format changed
3. Eviction policy too aggressive (maxSize too small)
4. TTL too short (24h to 1h accidentally)

**Resolution:**
```bash
# Check cache config
curl http://cart-service:8088/actuator/metrics/cache_cache_size

# Inspect eviction patterns
curl http://cart-service:8088/actuator/metrics/cache_evictions_weight

# Verify cache TTL
echo $CART_CACHE_TTL_SECONDS (should be 86400 = 24h)

# Temporarily increase cache size
CART_CACHE_MAX_SIZE=10000 (from 5000)

# Restart pod to flush cache
kubectl rollout restart deployment/cart-service
```

### Scenario 5: Inventory Service Unavailable (Item availability check failing)

**Indicators:**
- `inventory_service_error_rate` > 50%
- AddItem returns 503 "Service Unavailable"
- Users cannot add items to cart

**Root Causes:**
1. Inventory-service pod crashed
2. Network policy blocked traffic
3. Inventory database down
4. Kafka consumer lag (stock data out of sync)

**Resolution:**
```bash
# Check inventory-service readiness
kubectl get pods -n services | grep inventory

# Test connectivity
curl http://inventory-service:8086/actuator/health/ready

# Check network policy allows traffic from cart-service
kubectl get networkpolicies -n services | grep inventory

# Bypass inventory check (emergency fallback)
CART_SKIP_INVENTORY_CHECK=true (NOT RECOMMENDED - risk of overselling)

# Check Kafka consumer lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group inventory-consumer --describe
```

### Scenario 6: Concurrent Cart Update Conflict (409 Conflict errors)

**Indicators:**
- HTTP 409 responses spike
- `cart_validation_failures` → reason: "VERSION_MISMATCH"
- Client retrying (exponential backoff)

**Root Causes:**
1. Multiple devices updating same cart simultaneously
2. Retry storm from slow clients
3. Optimistic lock retry limit hit (max 3)

**Resolution:**
```bash
# Identify conflict patterns
SELECT COUNT(*), user_id FROM cart_update_conflicts
WHERE created_at > NOW() - INTERVAL '5 minutes'
GROUP BY user_id
ORDER BY COUNT(*) DESC
LIMIT 10;

# Enable more aggressive retry backoff
CART_RETRY_MAX_ATTEMPTS=5 (from 3)
CART_RETRY_BACKOFF_MULTIPLIER=3.0 (from 2.0)

# For high-concurrency users, use pessimistic lock
-- SELECT ... FOR UPDATE (brief lock during update)
```

### Scenario 7: Abandoned Cart Cleanup Job Fails (Batch processing error)

**Indicators:**
- Scheduled job misses 2 AM UTC run
- `cart_abandoned_count` not incrementing (stuck in ACTIVE state)
- Log: "ExpireCartJob failed"

**Root Causes:**
1. ShedLock distributed lock contention (multiple replicas fighting)
2. Database timeout during batch update
3. Memory OOM in scheduled job (million cart processing)

**Resolution:**
```bash
# Check ShedLock status
SELECT * FROM shedlock WHERE name = 'ExpireCartJob';

# Manually clear stale lock
DELETE FROM shedlock WHERE name = 'ExpireCartJob'
AND lock_at_most_until < NOW();

# Trigger manual cleanup job
curl -X POST http://cart-service:8088/admin/jobs/expire-carts

# Increase job memory and timeout
CART_JOB_MEMORY_MB=1024 (from 512)
CART_JOB_TIMEOUT_SECONDS=300 (from 60)
```

### Scenario 8: Database Connection Pool Exhausted (Requests queuing)

**Indicators:**
- `db_connection_pool_active` = 30/30 (all connections in use)
- `db_connection_pool_pending` > 5 (queued requests)
- Cart operations slow or timeout

**Root Causes:**
1. Pricing-service slow (holding DB connections while waiting)
2. Long-running query (N+1 problem)
3. Connection pool size too small for load
4. Connection leak (not returned to pool)

**Resolution:**
```bash
# Check for slow queries
SELECT query, mean_exec_time, calls FROM pg_stat_statements
WHERE mean_exec_time > 1000
ORDER BY mean_exec_time DESC
LIMIT 10;

# Increase connection pool
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=50 (from 30)

# Reduce timeout on pricing calls (fail fast)
CART_PRICING_TIMEOUT_MS=300 (from 500)

# Restart pod with new pool size
kubectl rollout restart deployment/cart-service
```

## Production Runbook Patterns

### Runbook 1: Emergency Cart Validation Skip (Degraded Mode)

**Scenario:** Pricing-service down, users cannot add items

**SLA:** < 5 min to mitigate

1. **Alert Received:** PricingServiceDown alert triggers
2. **Immediate Actions:**
   - Check pricing-service: `kubectl get pods -n services | grep pricing`
   - If DOWN: Decision: Allow cart operations with cached prices (risky) or block?
3. **Mitigation (Option A - Allow with Warning):**
   - Set feature flag: `CART_PRICING_VALIDATION_REQUIRED = false`
   - Users can add items, but prices may be stale (notify user)
   - Monitor fraud (over-selling cheap items)
4. **Mitigation (Option B - Block):**
   - Return 503 "Checkout temporarily unavailable"
   - Display message: "We're having technical difficulties"
5. **Recovery:**
   - Pricing-service restored
   - Re-enable validation
   - Audit affected carts for pricing discrepancies

### Runbook 2: Inventory Desync Recovery

**Scenario:** Inventory-service shows stock ≠ actual stock (user can't add available item)

1. **Diagnosis:**
   - Compare cart-service cache vs inventory-service source
   - Check Kafka consumer lag
2. **Quick Fix:**
   - Clear inventory cache: `curl -X POST http://inventory-service:8086/admin/cache/clear`
3. **Recovery:**
   - Restart CDC consumer if lag > 5 min
   - Audit transaction history for correctness

## Related Documentation

- **ADR-007**: Cart State Machine & Lifecycle (design rationale)
- **ADR-008**: Price Quote Generation & Validation (pricing model)
- **Runbook**: cart-service/runbook.md (on-call procedures)
- **Integration Tests**: Wave 37 coverage (74+ tests)

## Integrations

### Synchronous Calls

| Service | Endpoint | Timeout | Purpose | Critical |
|---------|----------|---------|---------|----------|
| pricing-service | POST /pricing/calculate | 500ms | Real-time quote | Yes |
| inventory-service | GET /inventory/{productId}/stock | 300ms | Availability check | Yes |

### Event Consumers

| Topic | Event | Action |
|-------|-------|--------|
| inventory.events | ProductStockChanged | Remove item if out of stock |
| catalog.events | ProductDeactivated | Remove item from cart |

## Configuration

### Environment Variables

```env
SERVER_PORT=8088
SPRING_PROFILES_ACTIVE=gcp

SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/carts
SPRING_DATASOURCE_HIKARI_POOL_SIZE=30
SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES=5

CART_CACHE_TTL_SECONDS=86400
CART_CACHE_MAX_SIZE=5000
CART_PRICING_TIMEOUT_MS=500
CART_INVENTORY_TIMEOUT_MS=300
CART_RETRY_MAX_ATTEMPTS=3
CART_RETRY_BACKOFF_MULTIPLIER=2.0

SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
CART_KAFKA_CONSUMER_GROUP=cart-service-v1
CART_KAFKA_AUTO_OFFSET_RESET=latest

OTEL_TRACES_SAMPLER=always_on
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
```

### application.yml

```yaml
cart:
  cache:
    ttl-seconds: ${CART_CACHE_TTL_SECONDS:86400}
    max-size: ${CART_CACHE_MAX_SIZE:5000}
  pricing:
    timeout-ms: ${CART_PRICING_TIMEOUT_MS:500}
    circuit-breaker-threshold: 50%
  inventory:
    timeout-ms: ${CART_INVENTORY_TIMEOUT_MS:300}
  retry:
    max-attempts: ${CART_RETRY_MAX_ATTEMPTS:3}
    backoff-multiplier: ${CART_RETRY_BACKOFF_MULTIPLIER:2.0}
  kafka:
    consumer-group: ${CART_KAFKA_CONSUMER_GROUP:cart-service-v1}

spring:
  datasource:
    hikari:
      pool-size: 30
      idle-timeout: 5m
      max-lifetime: 30m
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

## Advanced Cart Operations

### Cart Merging (Multi-Device Support Future)

**When merging carts**:
```
Scenario: User adds items on mobile, then opens website

Phase 1: Fetch carts
  - Mobile cart: [Milk (qty=2), Bread (qty=1)]
  - Web cart: [Milk (qty=1), Eggs (qty=1)]

Phase 2: Merge algorithm
  IF item in both carts:
    qty = MAX(qty_mobile, qty_web)  -- take larger quantity
  ELSE:
    qty = qty_mobile + qty_web  -- combine quantities

Phase 3: Result
  Merged: [Milk (qty=2), Bread (qty=1), Eggs (qty=1)]

Phase 4: Validation
  - Check inventory still available
  - Recalculate pricing with combined totals
  - Notify user of changes
```

### Cart Recovery & Abandonment

**Abandoned Cart Detection**:
```
Definition: ACTIVE cart with no modifications for 7+ days

Detection job (nightly):
  SELECT * FROM carts
  WHERE status='ACTIVE' AND updated_at < NOW() - INTERVAL '7 days'

Action:
  1. Mark as ABANDONED
  2. Trigger recovery email (next morning, 9 AM user timezone)
  3. Email includes: Products in cart, total value, 24h discount code
  4. Re-engagement target: Recover 15-20% of abandoned carts

Metrics:
  - Abandonment rate: 30-40% (industry standard 25%)
  - Recovery rate: 10-15% of abandoned (email effectiveness)
  - Revenue recovered: $X per day
```

**Lifetime of Abandoned Cart**:
```
Day 0: Cart becomes abandoned (inactive 7+ days)
Day 1: Recovery email sent (p0.5 open rate)
Day 2-7: Retargeting ads shown
Day 8: Mark as EXPIRED, flag for archival
Day 30: Archive to cold storage (keep for 1 year)

If user re-engages (adds item):
  → Cart status back to ACTIVE
  → Cancels recovery email schedule
```

### High-Concurrency Cart Updates

**Concurrency Control Strategy**:
```
Problem: Mobile users fast-clicking "Add to Cart"
  Request 1: Add Milk, update quantity
  Request 2: Add Bread (arrive before Request 1 completes)
  Race condition: Request 2 reads stale cart version

Solution: Optimistic Locking

Request 1:
  SELECT cart WHERE id='cart-uuid' (version=5, items=3)
  INSERT item (version still 5)
  UPDATE carts SET version=6 (if version=5) -- atomic
  Success: version now 6

Request 2:
  SELECT cart WHERE id='cart-uuid' (version=5, items=3)
  INSERT item (version still 5)
  UPDATE carts SET version=6 (if version=5) -- fails! (version already 6)
  Failure: 409 Conflict

Client retry logic:
  1. Get latest cart (version=6)
  2. Reapply change
  3. Re-submit with version=6
  → Success on retry
```

**Performance under load**:
```
Low contention (< 10% retries):
  - Average latency: 150ms
  - p99 latency: 300ms
  - No user impact

High contention (> 30% retries):
  - Average latency: 250ms (longer retry cycles)
  - p99 latency: 800ms (multiple retries)
  - User impact: Slow "Add to Cart" response

Mitigation:
  - Increase retry backoff multiplier
  - Use pessimistic lock for ultra-high-contention users
  - Add queue for rapid-fire requests
```

## Production Deployment Patterns

### Blue-Green Deployment

**Deployment process**:
```
1. Deploy cart-service-v2 (new version)
2. Route 10% traffic to v2, monitor:
   - p99 latency
   - Error rate
   - Cache hit rate
   - Payment success rate
3. After 15 min healthy: Route 50% → 50%
4. After 30 min healthy: Route 100% to v2
5. Keep v1 running for 24h (rollback window)
6. Metrics to watch during cutover:
   - Checkout conversion rate (should not drop)
   - Cart validation success rate (should stay > 99%)
   - Price staleness (should not increase)
```

**Rollback triggers**:
```
Automatic rollback if:
  - p99 latency > 500ms
  - Error rate > 1%
  - Payment service errors > 5/min
  - Pricing sync failures > 10/min

Manual rollback if:
  - Customer complaints about pricing
  - Fraud score anomalies
  - Data inconsistency alerts
```

## Known Limitations

1. No persistent cart sync across devices (session-only)
2. No cart sharing/gift carts
3. No cart recommendations
4. No cart expiry notifications
5. No location-aware pricing (all users see same price)
6. No bulk cart import/export
7. No cart analytics (popular products, avg cart value per segment)
8. No inventory reservation (soft hold only)

**Roadmap (Wave 41+):**
- Cross-device cart sync
- Cart recommendations based on browsing history
- Abandoned cart recovery emails
- Gift cart functionality
- Wishlist/save for later feature
- Advanced cart analytics & insights
- Hard inventory reservation (30-min hold)
- Cart sharing (share link with others)

