# Mobile Backend-for-Frontend Service

## Overview

The mobile-bff-service is a specialized aggregation layer optimized for mobile client consumption. It combines data from multiple backend services, deduplicates requests, filters unnecessary fields, and optimizes response payloads for bandwidth-constrained mobile networks. This reduces round-trips and payload size for mobile clients.

**Service Ownership**: Platform Team - Mobile Experience
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8081
**Status**: Tier 2 Critical (Mobile client dependency)

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **P99 Latency**: < 800ms aggregation (including downstream calls)
- **Error Rate**: < 0.1%
- **Max Throughput**: 5,000 requests/minute (mobile user sessions)

## Key Responsibilities

1. **Request Aggregation**: Combine results from catalog, cart, order, and payment services into single response
2. **Deduplication**: Cache identical requests within 5-second window to reduce backend load
3. **Field Filtering**: Remove unnecessary fields for mobile clients (e.g., internal-only metadata)
4. **Response Optimization**: Compress JSON, reduce nested depth, inline frequently-used lookups
5. **Fallback Strategy**: Gracefully degrade when backend services unavailable (partial responses)

## Deployment

### GKE Deployment
- **Namespace**: production
- **Replicas**: 3 (HA)
- **CPU Request/Limit**: 400m / 800m
- **Memory Request/Limit**: 512Mi / 1024Mi

### Cluster Configuration
- **Ingress**: External (behind Istio IngressGateway + Cloud Armor WAF)
- **NetworkPolicy**: Allow from istio-ingressgateway; block from untrusted namespaces
- **Service Account**: mobile-bff-service
- **Cache**: Local Caffeine cache + Redis distributed cache for deduplication

## Architecture & Integrations

```
┌────────────────────────────────────────────────────────────────┐
│ Mobile Client (iOS / Android)                                   │
└────────────────────────┬─────────────────────────────────────┘
                         │
              HTTP/1.1 (TLS 1.3, gzip)
                         │
          ┌──────────────▼──────────────────────────────┐
          │ Mobile-BFF-Service                          │
          │ ├─ Request Deduplication (Redis)           │
          │ ├─ Parallel Aggregation                    │
          │ ├─ Field Filtering                         │
          │ └─ Response Compression                    │
          └──────────────┬───────────────┬─────────────┘
                         │               │
        ┌────────────────▼─────┐ ┌───────▼──────────────┐
        │ Backend Services     │ │ Backend Services     │
        ├─ Catalog-Service    │ ├─ Cart-Service       │
        ├─ Order-Service      │ ├─ Payment-Service    │
        └─────────────────────┘ └─────────────────────┘
```

### Request Deduplication Flow

1. **Hash Request**: MD5(method, path, query, userId)
2. **Check Redis Cache**: If hash present + within 5s, return cached response
3. **Parallel Calls**: Issue requests to all backends concurrently (CompletableFuture)
4. **Aggregate Results**: Merge responses, handle partial failures
5. **Cache Result**: Store in Redis with 5-second TTL
6. **Stream Response**: Send compressed JSON to mobile client

## Integrations

### Synchronous Dependencies
| Service | Endpoint | Timeout | Purpose | Fallback |
|---------|----------|---------|---------|----------|
| catalog-service | http://catalog-service:8082/products | 2s | Product details, images, pricing | Return cached catalog version |
| cart-service | http://cart-service:8084/carts/{cartId} | 2s | Cart contents, item counts | Return stale cart from Redis |
| order-service | http://order-service:8087/orders | 2s | Recent orders, status | Return empty list |
| payment-service | http://payment-service:8083/payment-methods | 2s | User payment methods | Return empty list |

### Cache Dependencies
- **Redis**: Deduplication cache (5s window), response cache (1m window)
- **Caffeine L1 Cache**: Hot products, user profiles (in-process, TTL 5 min)

## Endpoints

### Public (Requires JWT Bearer Token)
- `GET /actuator/health` - Liveness probe
- `GET /actuator/metrics` - Prometheus metrics

### Mobile API (Requires JWT)
- `GET /mobile/v1/home` - Home screen aggregation (products + cart + recommendations)
- `GET /mobile/v1/products?q=search` - Product search with aggregated inventory
- `GET /mobile/v1/cart` - User cart with pricing
- `GET /mobile/v1/orders` - Order history with status
- `GET /mobile/v1/checkout/summary` - Final checkout summary (all backends aggregated)

### Example Requests

```bash
# Get home screen (aggregated)
curl -X GET 'http://mobile-bff-service:8081/mobile/v1/home' \
  -H 'Authorization: Bearer <jwt_token>' \
  -H 'Accept-Encoding: gzip'

# Response (compressed):
# {
#   "featured_products": [{...}],
#   "cart": {"item_count": 3, "total": 45.99},
#   "recent_orders": [{...}],
#   "payment_methods": [{...}]
# }

# Product search (with inventory aggregation)
curl -X GET 'http://mobile-bff-service:8081/mobile/v1/products?q=organic' \
  -H 'Authorization: Bearer <jwt_token>'

# Checkout summary
curl -X GET 'http://mobile-bff-service:8081/mobile/v1/checkout/summary' \
  -H 'Authorization: Bearer <jwt_token>'
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8081
SPRING_REDIS_HOST=redis
SPRING_REDIS_PORT=6379
SPRING_REDIS_PASSWORD=<redis_password>
CATALOG_SERVICE_URL=http://catalog-service:8082
CART_SERVICE_URL=http://cart-service:8084
ORDER_SERVICE_URL=http://order-service:8087
PAYMENT_SERVICE_URL=http://payment-service:8083
DEDUP_CACHE_TTL_SECONDS=5
RESPONSE_CACHE_TTL_MINUTES=1
REQUEST_TIMEOUT_MS=2000
SPRING_PROFILES_ACTIVE=gcp
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
```

### application.yml
```yaml
mobile-bff:
  cache:
    dedup-ttl-seconds: ${DEDUP_CACHE_TTL_SECONDS:5}
    response-ttl-minutes: ${RESPONSE_CACHE_TTL_MINUTES:1}
    caffeine-max-size: 10000
  backends:
    request-timeout-ms: ${REQUEST_TIMEOUT_MS:2000}
    parallel-calls: true
  response:
    compression: gzip
    field-filter-enabled: true
```

## Monitoring & Alerts

### Key Metrics
- `http_server_requests_seconds` (histogram) - BFF request latency
- `http_server_requests_total` (counter) - Requests by status/endpoint
- `mobile_bff_dedup_cache_hits_total` (counter) - Deduplication cache hit rate
- `mobile_bff_backend_errors_total` (counter) - Errors per backend service
- `mobile_bff_aggregation_latency_seconds` (histogram) - Time to aggregate all backends
- `mobile_bff_response_size_bytes` (histogram) - Compressed response size

### Alerting Rules
- `mobile_bff_latency_p99 > 2000ms` - Aggregation too slow for mobile clients
- `mobile_bff_backend_error_rate > 5%` - Multiple backend failures
- `mobile_bff_dedup_cache_hit_rate < 30%` - Ineffective deduplication
- `mobile_bff_redis_connection_failures > 0` - Cache backend down
- `mobile_bff_timeout_errors_total > 100/min` - Backend timeouts during aggregation

### Logging
- **WARN**: Partial response due to backend failure (log which backends failed)
- **INFO**: Cache hits, aggregation time for slow requests (>500ms)
- **ERROR**: Complete aggregation failures, Redis connection issues

## Security Considerations

### Threat Mitigations
1. **Field Filtering**: Remove PII/internal fields before mobile transmission (e.g., internal user IDs)
2. **Response Signing**: Mobile app verifies response signature (prevents MITM modification)
3. **JWT Validation**: All endpoints require valid Bearer token (identity-service JWKS)
4. **Rate Limiting**: Istio VirtualService enforces per-user rate limits (1000 req/min)
5. **WAF Rules**: Cloud Armor blocks SQL injection patterns, bot traffic

### Known Risks
- **Aggregation Logic Bugs**: Incorrect field filtering may leak sensitive data
- **Cache Poisoning**: Compromised Redis could serve stale/malicious aggregations
- **Backend Timeout**: If slow backend doesn't timeout, client hangs (2s timeout enforced)
- **Dedup Window Abuse**: 5s dedup window could cause race conditions if same user fast-clicks

## Troubleshooting

### Slow Aggregation Latency
1. **Check backend health**: Verify catalog/cart/order/payment services are responding
2. **Review backend timeouts**: If any backend slow, entire aggregation delayed (parallel execution)
3. **Check Redis**: `redis-cli PING` verify cache is responsive
4. **Review logs**: Check for partial failures (only some backends responding)

### Deduplication Not Working
1. **Redis connectivity**: Verify `redis-cli -h redis PING` works
2. **Cache TTL**: Confirm requests arrive within 5-second window
3. **Request hashing**: Verify identical requests generate same MD5 hash
4. **Monitor metrics**: Check `mobile_bff_dedup_cache_hits_total` counter

### Stale Data in Responses
1. **Cache TTL too long**: Reduce `RESPONSE_CACHE_TTL_MINUTES` (default 1 min)
2. **Backend not updated**: Verify backend services published new data to Redis
3. **Caffeine L1 stale**: Clear in-process Caffeine cache (restart pod if needed)

## Advanced Aggregation Patterns

### Request Deduplication Algorithm

**Hash Generation**:
```
MD5(HTTP_METHOD + normalized_path + sorted_query_params + user_id)

Examples:
GET /mobile/v1/products?q=milk&sort=relevance&userId=user-123
→ MD5("GET/mobile/v1/products?q=milk&sort=relevance&userId=user-123") = hash-A

GET /mobile/v1/products?sort=relevance&q=milk&userId=user-123
→ MD5("GET/mobile/v1/products?q=milk&sort=relevance&userId=user-123") = hash-A (same)

GET /mobile/v1/products?q=milk&userId=user-123
→ Different hash (sort param missing)
```

**Cache Window**: 5 seconds (typical mobile refresh rate)
**Hit Rate Target**: 40-60% (depends on user behavior)

### Response Aggregation Strategies

**Strategy 1: Parallel Fan-Out (Default)**
```
Request arrives
  ↓
Spawn 4 concurrent requests:
  - Catalog Service (product details)
  - Pricing Service (calculate totals)
  - Cart Service (user's cart)
  - Order Service (recent orders)

All complete → Merge → Return to client
Timeout per service: 2 seconds
Fallback: Skip service if timeout (return partial response)

Max end-to-end latency: 2 seconds
```

**Strategy 2: Sequential Fallback (For dependent data)**
```
Scenario: Cart depends on product details

1. Fetch products (sequential, no parallelization)
   ↓
2. For each product in cart:
   - Add product details
   - Add current pricing
   ↓
3. Return aggregated response

Latency: P99 < 800ms (products cached locally)
```

**Strategy 3: Prefetch + Lazy Load**
```
High-traffic endpoint (home screen):

Immediate response (< 100ms):
- Featured products (from cache)
- Cart summary (simple count)

Lazy load (background):
- Full cart details (product descriptions)
- Personalized recommendations
- Recent orders with full details

Client triggers lazy load after rendering initial UI
Improves perceived performance (FCP < 100ms)
```

## Performance Optimization Techniques

### Response Compression Strategies

**Compression Levels**:
- Gzip compression: 70-80% size reduction
- Remove unnecessary fields: Additional 20-30% reduction
- Combine strategies: Total 90% size reduction

**Example**:
- Uncompressed response: 250KB
- After Gzip: 50KB (80% reduction)
- After field filtering: 10KB (90% reduction)
- Mobile savings: ~240KB per request (crucial on slow networks)

### Caching Layer Optimization

**L1 Cache (Caffeine, in-process)**:
- Purpose: Hot data (top 100 products)
- Size: 10,000 entries max
- TTL: 5 minutes
- Eviction policy: LRU (least recently used)
- Hit rate target: 40%

**L2 Cache (Redis, shared across pods)**:
- Purpose: Cross-pod dedup + response caching
- Size: 100GB (elastic)
- TTL: 5 seconds (dedup), 1 minute (responses)
- Hit rate target: 50-70%

**L3 Cache (Backend services)**:
- Purpose: Database query caching
- Each service has own caching strategy
- Coordinated invalidation via Kafka events

### Backend Call Optimization

**Call Reduction**:
Before: Mobile client makes 4 calls
- GET /products/{id}
- GET /cart
- GET /pricing
- GET /orders

After (BFF): 1 call to BFF, which makes optimized 4 calls in parallel

**Latency Improvement**:
- Serial calls (4 × 300ms): 1200ms
- Parallel calls (max 300ms): 300ms + aggregation time (50ms) = 350ms
- **Improvement: 3.4x faster**

## Advanced Features & Use Cases

### Feature 1: Mobile App Cold Start Optimization

**Problem**: First app open takes 3-5 seconds (load products, cart, orders)

**Solution**: Prefetch on cold start
```
1. App sends: GET /mobile/v1/home?coldStart=true
2. BFF detects cold start
3. Return pre-aggregated "home" screen data:
   - Featured products (no personalization)
   - Empty cart template
   - Prompt for login
4. Client renders immediately (< 200ms perceived latency)
5. Background: Fetch personalized data after login
```

### Feature 2: Network-Optimized Payloads

**Payload Optimization by Network Type**:

Slow (2G):
```json
{
  "products": [
    {
      "id": "123",
      "name": "Milk",
      "price": 5000
    }
  ]
}
```
Total: 2KB

Fast (4G+):
```json
{
  "products": [
    {
      "id": "123",
      "name": "Milk",
      "description": "Fresh pasteurized milk...",
      "images": ["url1", "url2"],
      "brand": "Amul",
      "category": "Dairy",
      "price": 5000,
      "discounted_price": 4500,
      "reviews_count": 1250,
      "rating": 4.8
    }
  ]
}
```
Total: 50KB

### Feature 3: Progressive Data Loading

**Waterfall Strategy** (Load critical first):
```
Phase 1 (Critical, < 100ms):
- Header + navigation
- Featured products (names + prices)
- Cart count

Phase 2 (High priority, < 500ms):
- Product images
- Full cart contents
- User recommendations

Phase 3 (Nice-to-have, < 2s):
- Full product descriptions
- Customer reviews
- Related products
```

## Monitoring & Observability

### Key Metrics Deep Dive

**Deduplication Effectiveness**:
```
metric: mobile_bff_dedup_cache_hits_total
Usage:
- If hit_rate > 60%: Dedup working well
- If hit_rate 30-60%: Normal (diverse user behavior)
- If hit_rate < 30%: Investigate (cache TTL too short? Data changing rapidly?)

Trend analysis:
- Morning (6-9 AM): Hit rate 20% (diverse searches)
- Midday (12-1 PM): Hit rate 50% (lunch orders, repeated)
- Peak hours (7-10 PM): Hit rate 40% (mixed)
```

**Aggregation Latency by Endpoint**:
```
GET /mobile/v1/home: p99 = 600ms (4 parallel calls)
GET /mobile/v1/checkout/summary: p99 = 800ms (5 calls + pricing)
GET /mobile/v1/products?q=search: p99 = 300ms (1-2 calls)

Alerts:
- If any endpoint p99 > SLA: Check downstream services
- If specific endpoint slow: Profile to find bottleneck
```

### Error Rate Tracking

**Backend Service Errors**:
```
metric: mobile_bff_backend_errors_total (by service)

Scenarios:
1. Cart-service down: mobile_bff_backend_errors_total{service="cart"} = X/min
   → Return partial response (cart skipped)
   → Alert: SEV-2

2. Pricing-service down: pricing calculation skipped
   → Return products without pricing
   → Alert: SEV-2 (money path affected)

3. Order-service down: Recent orders omitted
   → Return products + cart only
   → Alert: SEV-3 (non-critical data)
```

## Deployment & Scaling

### Horizontal Pod Autoscaler Configuration
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: mobile-bff-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: mobile-bff-service
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 75
```

### Blue-Green Deployment

**Rollout Process**:
```
1. Deploy v2 canary (10% traffic)
   - Monitor: latency, error rate, cache hit ratio
2. If healthy at 10% for 10 min: Route 50% traffic
3. If healthy at 50% for 10 min: Route 100% traffic
4. Keep v1 for 24 hours (rollback window)

Rollback triggers:
- p99 latency > 1000ms
- Error rate > 1%
- Cache hit rate drops > 30%
```

## Production Runbook Patterns

### Runbook 1: Redis Cache Failure (Dedup Cache Down)

**Symptom**: Dedup cache hit rate drops to 0%

**Impact**:
- No dedup → 4x backend load increase
- Latency may spike due to downstream overload

**Recovery** (< 5 min):
```bash
# Step 1: Verify Redis is down
redis-cli ping  # Returns PONG if up

# Step 2: Graceful degradation (already active)
# BFF automatically falls back to no-dedup mode

# Step 3: Restart Redis
kubectl rollout restart statefulset/redis

# Step 4: Verify recovery
redis-cli SET test "value"
redis-cli GET test  # Returns "value"

# Step 5: Monitor latency recovery
# Dedup should kick back in within 1 minute
```

### Runbook 2: Slow Aggregation (Backend Service Timeout)

**Symptom**: GET /mobile/v1/home taking > 1000ms p99

**Diagnosis**:
```bash
# Check which backend is slow
curl -X GET http://mobile-bff-service:8081/actuator/metrics/mobile_bff_backend_latency_ms \
  | jq '.[] | select(.mean > 500)'

# Example output:
# {service: "catalog-service", mean: 600ms, p99: 900ms}
# {service: "cart-service", mean: 50ms, p99: 100ms}
```

**Resolution**:
- If catalog slow: Check catalog-service pod health
- If cart slow: Rare (in-memory operations)
- **Action**: Page on-call for that service team

### Runbook 3: Field Filtering Bug (Sensitive Data Leaked)

**Symptom**: Mobile app showing internal_user_id field to customers

**Impact**: Security issue (PII exposure)

**Immediate** (< 1 min):
```bash
# Emergency hotfix: Disable field filtering temporarily
MOBILE_BFF_FIELD_FILTER_ENABLED=false

# This returns all fields (safe, no internal data)
# Will show more data than needed, but no security breach
```

**Proper Fix** (< 30 min):
```bash
# 1. Identify which service leaked data (e.g., user-service)
# 2. Update field filter rule:
#    OLD: whitelist=[id, name, email, phone]
#    NEW: blacklist=[internal_user_id, system_flags, ...]
# 3. Redeploy BFF with updated filter
# 4. Audit response to confirm fix
```

## Related Documentation

- **ADR-003**: BFF Aggregation Pattern
- **ADR-004**: Request Deduplication Strategy
- **Runbook**: mobile-bff-service/runbook.md
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Integration Tests](../../test/mobile-bff-integration.md)
- [Performance Tuning Guide](performance-tuning.md)
