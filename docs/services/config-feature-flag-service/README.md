# Config & Feature Flag Service

## Overview

The config-feature-flag-service is the operational authority for feature flags, experiments, and dynamic configuration in InstaCommerce. It provides ultra-fast flag evaluation (<50ms p99) via Caffeine JVM-local caching, with Redis pub/sub cross-pod cache invalidation (Wave 35) ensuring millisecond-level consistency across all replicas. Supports bulk evaluation for mobile clients, A/B experiments with consistent user assignment, and gradual rollouts.

**Service Ownership**: Platform Team - Product Velocity
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8092
**Status**: Tier 2 Critical (enables rapid iteration; impacts all feature launches)
**Database**: PostgreSQL 15+ (flag definitions, experiments, rollouts)
**Cache**: Caffeine (JVM-local, 30s TTL) + Redis pub/sub (Wave 35 invalidation)

## SLOs & Availability

- **Availability SLO**: 99.99% (4 minutes downtime/month - low tolerance; read-only)
- **P50 Latency**: 5ms (Caffeine cache hit)
- **P99 Latency**: 50ms (cache miss → DB)
- **Error Rate**: < 0.05%
- **Cache Hit Ratio**: >= 95% (optimize for cache effectiveness)
- **Cross-Pod Sync**: < 100ms (Redis pub/sub invalidation)
- **Max Throughput**: 100,000 evaluations/minute per pod

## Key Responsibilities

1. **Flag Evaluation**: Lightning-fast flag lookup with Caffeine cache; sub-5ms local cache hits
2. **Bulk Evaluation**: Mobile clients fetch 10-50 flags in single request; optimized response
3. **Cross-Pod Invalidation**: Redis pub/sub ensures all replicas update cache within 100ms of flag change
4. **Experiments**: Consistent user assignment via deterministic hashing (hash(userId + experimentKey) % 100)
5. **Gradual Rollouts**: Percentage-based rollout (e.g., 50% of users see flag)
6. **Circuit Breaker**: Gracefully degrade to stale Caffeine cache if Redis/DB unavailable
7. **Admin APIs**: Create, update, override flags with TTL (Wave 35 compatible)
8. **Audit Logging**: Track all flag changes for compliance

## Deployment

### GKE Deployment
- **Namespace**: default
- **Replicas**: 3 (HA for read-heavy workload)
- **CPU Request/Limit**: 500m / 1000m
- **Memory Request/Limit**: 512Mi / 1Gi
- **Readiness Probe**: `/actuator/health/ready` (checks DB + Redis)

### Database
- **Name**: `featureflag` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V4) auto-applied
- **Connection Pool**: HikariCP 20 connections (read-heavy)
- **Replication**: Async (eventual consistency acceptable for flags)

### Cache Infrastructure
- **Caffeine**: JVM-local, 10,000 entries max, 30s TTL (expireAfterWrite)
- **Redis**: Single-node (or cluster), pub/sub channel: `flag-invalidations`
- **Fallback**: If Redis unavailable, rely on Caffeine 30s TTL

## Architecture

### Wave 35: Redis Pub/Sub Cache Invalidation

```
Before Wave 35 (Problem):
Pod A Updates Flag → Pod A Cache Cleared
Pod B Still Serves Stale Value (30s)
Pod C Still Serves Stale Value (30s)
→ User sees inconsistent flag across requests

After Wave 35 (Solution):
Pod A Updates Flag → DB Write + Redis Publish
All Pods Subscribe to "flag-invalidations" → Immediate Cache Clear
→ All pods synchronized within 100ms
```

### System Architecture Diagram

```mermaid
graph TB
    subgraph Clients
        Mobile["Mobile App<br/>(iOS/Android)"]
        Backend["Backend Services<br/>(cart, checkout, order)"]
        Admin["Admin Portal"]
    end

    subgraph Services
        FFS["feature-flag-service<br/>(3 replicas)<br/>:8092"]
    end

    subgraph Cache Layer
        Cache["Caffeine<br/>(JVM-local)<br/>30s TTL<br/>10k entries"]
        Redis["Redis Pub/Sub<br/>Channel:<br/>flag-invalidations"]
    end

    subgraph Data Layer
        DB[("PostgreSQL<br/>feature_flags<br/>experiments<br/>rollout_rules")]
    end

    subgraph Invalidation
        InvalidListener["InvalidationListener<br/>(Subscribes to Redis)"]
    end

    Mobile -->|GET /flags/bulk| FFS
    Mobile -->|POST /flags/{key}?context=...| FFS

    Backend -->|GET /flags/{key}| FFS

    Admin -->|PUT /flags/{key}| FFS
    Admin -->|POST /experiments| FFS

    FFS -->|1. Check Caffeine| Cache
    FFS -->|Cache Miss| DB

    FFS -->|2. Update DB| DB
    FFS -->|3. Publish to Redis| Redis

    Redis -->|4. Message| InvalidListener
    InvalidListener -->|5. Clear Cache| Cache

    style FFS fill:#4CAF50
    style Cache fill:#2196F3
    style Redis fill:#FF9800
```

### Data Flow (Flag Evaluation)

```
1. Client: GET /flags/new_checkout_ui?userId=abc
                ↓
2. FFS checks Caffeine cache (key = "featureFlags:new_checkout_ui:abc")
   HIT (< 5ms) → Return { enabled: true, variant: "v2" }
   MISS → Continue

3. Query DB: SELECT * FROM feature_flags WHERE key='new_checkout_ui'
   WHERE user matches rollout rules
   Cache result in Caffeine (30s TTL)
   Return { enabled: true, variant: "v2" }

Data Flow (Admin Update - Wave 35):
1. Admin: PUT /flags/new_checkout_ui { enabled: false }
                ↓
2. FFS updates DB + publishes to Redis:
   PUBLISH flag-invalidations "flag-invalidation:new_checkout_ui"
                ↓
3. ALL pods (A, B, C) receive Redis message via subscription
                ↓
4. Each pod clears Caffeine entry: @CacheEvict
                ↓
5. Next client request loads fresh value from DB
```

## Integrations

### Synchronous Clients
| Client | Endpoint | Timeout | Purpose |
|--------|----------|---------|---------|
| mobile-app | GET /flags/bulk | 2s | Fetch 10-50 flags for app startup |
| checkout-orchestrator | GET /flags/{key} | 1s | Check if new_checkout_ui enabled |
| order-service | GET /flags/fast_checkout | 1s | Enable fast checkout flow |
| admin-gateway-service | PUT /flags/{key}/override | 5s | Temporary flag override with TTL |

### Async Dependencies
- **Redis Pub/Sub**: Subscribe to `flag-invalidations` channel (Wave 35)
- **PostgreSQL**: Read-only with async replication acceptable

### Kafka Integration
- **Consumes**: `identity.events` → UserCreated, UserSegmentChanged (for segment-based rollouts)
- **Does NOT publish**: Read-only service

## Data Model

### Feature Flags Table

```sql
feature_flags:
  id              UUID PRIMARY KEY
  key             VARCHAR(255) NOT NULL UNIQUE
  description     TEXT
  enabled         BOOLEAN NOT NULL DEFAULT FALSE
  rollout_percentage INT DEFAULT 0 (0-100, 0=disabled)
  target_segments VARCHAR[] ARRAY (user segments: ['MOBILE_USERS', 'VIP'])
  exclude_segments VARCHAR[] ARRAY (e.g., ['BETA_TESTERS'])
  created_by      VARCHAR(255) NOT NULL
  created_at      TIMESTAMP NOT NULL DEFAULT now()
  updated_at      TIMESTAMP NOT NULL DEFAULT now()

Indexes:
  - UNIQUE (key) - fast lookup by flag name
  - enabled, rollout_percentage - query active flags
  - created_at - audit trail
```

### Experiments Table

```sql
experiments:
  id                 UUID PRIMARY KEY
  key                VARCHAR(255) NOT NULL UNIQUE
  flag_id            UUID NOT NULL (FK → feature_flags.id)
  variant_a_name     VARCHAR(100) NOT NULL (e.g., "current")
  variant_b_name     VARCHAR(100) NOT NULL (e.g., "experimental")
  allocation_pct     INT DEFAULT 50 (0-100)
  target_segments    VARCHAR[] ARRAY (devices for this experiment)
  start_date         TIMESTAMP NOT NULL
  end_date           TIMESTAMP NOT NULL
  status             ENUM('PLANNED', 'RUNNING', 'COMPLETED')
  created_at         TIMESTAMP NOT NULL

Indexes:
  - flag_id, status - query active experiments
  - start_date, end_date - timeline queries
```

### Experiment Assignments Table

```sql
experiment_assignments:
  id                 UUID PRIMARY KEY
  experiment_id      UUID NOT NULL (FK → experiments.id)
  user_id            UUID NOT NULL (FK → identity-service users)
  assigned_variant   ENUM('A', 'B')
  assigned_at        TIMESTAMP NOT NULL

Indexes:
  - UNIQUE (experiment_id, user_id) - one assignment per user
  - user_id, assigned_at - user's experiment history
```

### Flag Rollout Rules Table

```sql
flag_rollout_rules:
  id                 UUID PRIMARY KEY
  flag_id            UUID NOT NULL (FK → feature_flags.id)
  rule_type          ENUM('PERCENTAGE', 'SEGMENT', 'USER_OVERRIDE')
  target_users       UUID[] ARRAY (for USER_OVERRIDE)
  percentage         INT (for PERCENTAGE: 0-100)
  enabled            BOOLEAN NOT NULL
  order_index        INT NOT NULL (rule evaluation order)
  created_at         TIMESTAMP NOT NULL

Indexes:
  - flag_id, order_index - evaluation order
```

## API Documentation

### Evaluate Flag (Cached)

**GET /flags/{key}**
```bash
Authorization: Bearer {JWT_TOKEN}

Query Parameters:
  userId: optional UUID (for user-specific rules)
  context: optional JSON (e.g., platform=iOS, osVersion=17.0)

Example: /flags/new_checkout_ui?userId=550e8400&context={"platform":"iOS"}

Response (200):
{
  "key": "new_checkout_ui",
  "enabled": true,
  "variant": "v2",
  "reason": "Gradual rollout 50%"
}

Caching:
  - Cache key: "featureFlags:{key}:{userId}:{contextHash}"
  - Hit rate > 95% expected (JVM-local Caffeine)
  - Miss → DB lookup + cache
  - TTL: 30s (expireAfterWrite)
  - Invalidated via Redis pub/sub (Wave 35)
```

### Bulk Evaluation (Mobile Optimized)

**POST /flags/bulk**
```bash
Authorization: Bearer {JWT_TOKEN}

Request Body:
{
  "keys": ["new_checkout_ui", "loyalty_program", "dark_mode", "loyalty_card"],
  "userId": "550e8400-e29b-41d4",
  "context": {
    "platform": "iOS",
    "osVersion": "17.0",
    "appVersion": "3.2.1"
  }
}

Response (200):
{
  "flags": {
    "new_checkout_ui": {
      "enabled": true,
      "variant": "v2"
    },
    "loyalty_program": {
      "enabled": false
    },
    "dark_mode": {
      "enabled": true
    },
    "loyalty_card": {
      "enabled": true,
      "variant": "variant_b"
    }
  },
  "cacheKey": "cache-key-abc123",  # For delta updates
  "timestamp": "2025-03-21T10:00:00Z"
}

Optimization:
  - Single request (not 4 requests)
  - Batch evaluated against all rules
  - Results cached together
  - Client uses cacheKey for future delta updates
```

### Create Flag (Admin)

**POST /flags**
```bash
Authorization: Bearer {JWT_TOKEN}
Content-Type: application/json

Request Body:
{
  "key": "new_checkout_ui",
  "description": "New checkout experience with Wave 35 optimization",
  "enabled": true,
  "rolloutPercentage": 50,
  "targetAudience": {
    "segments": ["MOBILE_USERS", "VIP"],
    "excludeSegments": ["BETA_TESTERS"]
  }
}

Response (201 Created):
{
  "id": "flag-uuid",
  "key": "new_checkout_ui",
  ...
}

Side Effects:
  1. INSERT into feature_flags table
  2. PUBLISH to Redis "flag-invalidations:new_checkout_ui"
  3. All pods clear Caffeine cache (< 100ms)
```

### Curl Examples

```bash
# 1. Evaluate single flag (cache hit < 5ms)
curl -s http://feature-flag-service:8092/flags/new_checkout_ui?userId=abc \
  -H "Authorization: Bearer $JWT_TOKEN" | jq '.enabled'

# 2. Bulk evaluation for mobile (optimized)
curl -s -X POST http://feature-flag-service:8092/flags/bulk \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "keys": ["new_checkout_ui", "dark_mode", "loyalty_program"],
    "userId": "550e8400",
    "context": {"platform": "iOS"}
  }' | jq '.flags | keys'

# 3. Create flag (admin)
curl -s -X POST http://feature-flag-service:8092/flags \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "key": "new_feature",
    "enabled": true,
    "rolloutPercentage": 50
  }' | jq '.id'

# 4. Create experiment (admin)
curl -s -X POST http://feature-flag-service:8092/experiments \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "key": "checkout_variant_test",
    "flagKey": "new_checkout_ui",
    "variantA": {"name": "current"},
    "variantB": {"name": "experimental"},
    "allocation": 50,
    "startDate": "2025-04-01",
    "endDate": "2025-05-01"
  }' | jq '.id'
```

## Error Handling & Resilience

### Circuit Breaker Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      databaseBreaker:
        registerHealthIndicator: true
        failureRateThreshold: 50%
        slowCallRateThreshold: 80%
        slowCallDurationThreshold: 1000ms
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3

  timelimiter:
    instances:
      databaseQuery:
        timeoutDuration: 2s
        cancelRunningFuture: true
```

### Failure Scenarios

**Scenario 1: Redis Unavailable (Wave 35)**
- Redis pub/sub subscription fails on startup
- Service continues; Caffeine acts as sole cache
- Flags expire after 30s TTL
- Admin flag updates only visible after 30s (not immediate)
- Recovery: Redis reconnected automatically; pub/sub resumes

**Scenario 2: Database Unavailable**
- Circuit breaker opens after 50% failures
- Service falls back to Caffeine (stale data, up to 30s old)
- Flag evaluation still works but may return stale values
- Alert: SEV-2 (cache-backed degradation acceptable for feature flags)
- Recovery: DB reconnected → CB half-open → single request tests → CB closes

**Scenario 3: High Cache Miss Rate**
- If hit rate < 80%, investigate:
  - Too many unique flag combinations
  - Frequent cache evictions
  - Flag updates not propagating via Redis
- Resolution: Increase Caffeine size or check Redis connectivity

**Scenario 4: Experiment Assignment Inconsistency**
- User A assigned to variant B, then refreshes and gets variant A
- Root cause: Hash-based assignment using different seed
- Prevention: Use consistent hash(userId + experimentKey) % allocation
- Recovery: Clear experiment assignments; users reassigned on next request

## Monitoring & Observability

### Key Metrics

| Metric | Type | Alert Threshold | Purpose |
|--------|------|-----------------|---------|
| `cache.caffeine.hits` | Counter | N/A | Cache hits |
| `cache.caffeine.misses` | Counter | N/A | Cache misses |
| `cache.caffeine.hitRate` | Gauge | < 80% = investigate | Cache effectiveness |
| `featureflag.evaluation.latency_ms` | Histogram | p99 > 50ms = alert | Evaluation performance |
| `featureflag.redis.pub.count` | Counter | N/A | Invalidation messages sent |
| `featureflag.redis.sub.count` | Counter | N/A | Invalidation messages received |
| `featureflag.redis.lag_ms` | Gauge | > 500ms = investigate | Redis pub/sub lag |
| `db.hikari.connections.active` | Gauge | > 18 (of 20) = alert | Connection pool pressure |

### Alert Rules (YAML)

```yaml
alerts:
  - name: FeatureFlagCacheHitRateLow
    condition: cache_caffeine_hitRate < 0.80
    duration: 10m
    severity: SEV-2
    action: Alert; investigate cache churn or increased requests

  - name: FeatureFlagEvaluationLatency
    condition: histogram_quantile(0.99, featureflag_evaluation_latency_ms) > 50
    duration: 5m
    severity: SEV-2
    action: Alert; check DB latency or cache misses

  - name: RedisInvalidationLag
    condition: featureflag_redis_lag_ms > 500
    duration: 5m
    severity: SEV-2
    action: Investigate Redis connectivity or pub/sub channel congestion

  - name: FeatureFlagPubSubGap
    condition: (featureflag_redis_pub_count - featureflag_redis_sub_count) > 10
    duration: 5m
    severity: SEV-1
    action: Page on-call; Redis pub/sub messages not being received by all pods

  - name: FeatureFlagDBConnectionPoolExhausted
    condition: db_hikari_connections_active > 18
    duration: 5m
    severity: SEV-2
    action: Scale up replicas or check for slow queries

  - name: FeatureFlagHighErrorRate
    condition: rate(http_requests_total{service="feature-flag", status=~"5.."}[1m]) > 0.001
    duration: 5m
    severity: SEV-2
    action: Investigate; likely DB or cache issue
```

### Logging
- **INFO**: Flag created, experiment started, experiment ended
- **WARN**: Redis pub/sub disconnected, cache hit rate low, slow query
- **ERROR**: Database unavailable, circuit breaker open, unhandled exception
- **DEBUG**: Cache key details, evaluation rules, assignment logic (disabled in prod)

### Tracing
- **Spans**: Flag lookup, DB query, experiment assignment
- **Sampling**: 10% (high volume; non-critical)
- **Retention**: 1 day

## Security Considerations

### Authentication & Authorization
- **JWT Validation**: RS256 via identity-service JWKS
- **Admin APIs**: Requires `ADMIN` role
- **Read APIs**: Public (no auth required for GET /flags)

### Data Protection
- **Sensitive Data**: None (flags are operational configs)
- **Audit Trail**: All flag changes logged with admin user ID
- **PII**: No user data stored; only IDs for assignment

### Known Risks
1. **Flag Stampede**: Sudden cache expiration with high traffic → Mitigated by Redis invalidation (wave 35)
2. **Experiment Collision**: Multiple experiments on same flag → Mitigated by flag-to-experiment relationship
3. **Flag Poisoning**: Malformed flag value → Mitigated by schema validation

## Troubleshooting

### Issue: Cache Hit Rate < 80%
**Possible Causes:**
1. Flag updates too frequent (invalidation > cache TTL)
2. Too many unique user/context combinations
3. Redis pub/sub not connected (Wave 35)

**Diagnosis:**
```bash
# Check cache metrics
curl http://localhost:8092/actuator/metrics/cache.caffeine.hitRate

# Check Redis connection
kubectl logs -n default deployment/feature-flag-service | grep -i "redis\|connection"

# Check flag update frequency
psql -d featureflag -c "SELECT COUNT(*) FROM feature_flags WHERE updated_at > now() - interval '1 minute';"
```

**Resolution:**
- Reduce flag update frequency if testing
- Increase Caffeine size (maximumSize) if many unique combinations
- Verify Redis pub/sub is connected

### Issue: Stale Flag Value Served (Wave 35 Not Working)
**Possible Causes:**
1. Redis pub/sub subscription failed on startup
2. Pod didn't receive invalidation message
3. Caffeine size too small; entry evicted

**Diagnosis:**
```bash
# Check Redis subscription status
kubectl exec -n default pod/feature-flag-service-0 -- \
  redis-cli PUBSUB CHANNELS

# Check pod logs for invalidation messages
kubectl logs -n default deployment/feature-flag-service | grep "flag-invalidation"

# Check if entry was evicted
curl http://localhost:8092/actuator/metrics/cache.caffeine.evictions
```

**Resolution:**
- Restart pod if Redis subscription failed
- Increase Caffeine maximumSize if evictions high
- Verify Redis server is running: `redis-cli PING`

### Issue: Experiment Assignment Inconsistency
**Possible Causes:**
1. Different pods using different hash seeds
2. Experiment variant allocation changed mid-experiment
3. User reassigned manually in DB

**Diagnosis:**
```bash
# Check experiment allocation
psql -d featureflag -c "SELECT * FROM experiments WHERE key='test_exp';"

# Check user assignments
psql -d featureflag -c "SELECT assigned_variant FROM experiment_assignments WHERE experiment_id=? AND user_id=?;"

# Test hash consistency
curl -s http://localhost:8092/flags/bulk \
  -H "Authorization: Bearer $JWT" \
  -d '{"keys":["test_flag"], "userId":"same-uuid"}' \
  | jq '.flags[].variant'  # Run multiple times; should be same
```

**Resolution:**
- Use deterministic hash(userId + experimentKey) % allocation
- Don't modify allocation mid-experiment
- If manual correction needed, clear assignments; resync

## Configuration

### Environment Variables
```env
# Server
SERVER_PORT=8092
SPRING_PROFILES_ACTIVE=gcp

# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/featureflag
SPRING_DATASOURCE_USERNAME=${DB_USER}
SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
SPRING_DATASOURCE_HIKARI_POOL_SIZE=20

# Redis (Wave 35)
SPRING_REDIS_HOST=redis.default.svc.cluster.local
SPRING_REDIS_PORT=6379
REDIS_TIMEOUT_MS=1000

# JWT
JWT_ISSUER=instacommerce-identity
JWT_PUBLIC_KEY=${JWT_PUBLIC_KEY_PEM}

# Tracing
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
OTEL_TRACES_SAMPLER=always_on
```

### application.yml
```yaml
server:
  port: 8092
spring:
  jpa:
    hibernate:
      ddl-auto: none
  cache:
    caffeine:
      spec: maximumSize=10000,expireAfterWrite=30s
    cache-names:
      - featureFlags
      - experiments

config-feature-flag:
  cache:
    ttl-seconds: 30
    max-size: 10000
    record-stats: true

  redis:
    enabled: true
    pub-sub-channel: flag-invalidations
    reconnect-attempts: 5
    reconnect-delay-ms: 1000
```

## Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: feature-flag-service
  namespace: default
spec:
  replicas: 3
  selector:
    matchLabels:
      app: feature-flag-service
  template:
    metadata:
      labels:
        app: feature-flag-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8092"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      containers:
      - name: feature-flag-service
        image: feature-flag-service:v1.2.3
        ports:
        - containerPort: 8092
          name: http
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: gcp
        - name: SPRING_REDIS_HOST
          value: redis.default.svc.cluster.local
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
          limits:
            cpu: 1000m
            memory: 1Gi
        livenessProbe:
          httpGet:
            path: /actuator/health/live
            port: 8092
          initialDelaySeconds: 10
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/ready
            port: 8092
          initialDelaySeconds: 15
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: config-feature-flag-service
  namespace: default
spec:
  selector:
    app: feature-flag-service
  ports:
  - port: 8092
    targetPort: 8092
    name: http
  type: ClusterIP
```

## Related Documentation

- **Wave 35**: Redis pub/sub cache invalidation feature
- **Wave 38**: SLOs and observability
- **ADR-013**: Feature-flag cache invalidation strategy
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Runbook](runbook.md)