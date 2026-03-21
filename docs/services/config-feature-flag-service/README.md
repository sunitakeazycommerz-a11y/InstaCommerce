# Config & Feature Flag Service

**Feature flag evaluation engine with Caffeine-based local caching, Redis pub/sub cross-replica cache invalidation (Wave 35), and bulk evaluation for mobile clients with A/B experiment support.**

| Property | Value |
|----------|-------|
| **Module** | `:services:config-feature-flag-service` |
| **Port** | `8092` |
| **Runtime** | Spring Boot 4.0 · Java 21 |
| **Database** | PostgreSQL 15+ (flags, experiments, rollouts) |
| **Caching** | Caffeine (local) + Redis pub/sub (invalidation, Wave 35) |
| **Messaging** | Kafka consumer (identity.events for user segments) |
| **Auth** | JWT RS256 + service-to-service |
| **Owner** | Product & Engineering Velocity Team |
| **SLO** | P50: 5ms (cached), P99: 50ms, 99.99% availability |

---

## Service Role & Ownership

**Owns:**
- `feature_flags` table — flag definitions (name, enabled, rollout %)
- `experiments` table — A/B experiments (variant A/B, target audience)
- `flag_rollout_rules` table — per-user override rules and gradual rollout
- `experiment_assignments` table — user-to-variant mapping
- Cache: Caffeine (JVM-local) + Redis (cross-pod invalidation, Wave 35)

**Does NOT own:**
- User segments (→ `identity-service`)
- Experiment analytics (→ analytics platform)

**Consumes:**
- `identity.events` — UserCreated (assign to experiment variants)

**Publishes:**
- None (read-only service)

---

## Architecture: Caffeine + Redis Pub/Sub (Wave 35)

### Wave 35 Cache Invalidation Enhancement

**Problem (Pre-Wave 35):**
- Service has 3 replicas running
- Pod A updates flag → cache invalidated on Pod A only
- Pods B and C still serve stale flag value for 30s
- Users experience flag inconsistency across requests

**Solution (Wave 35 - Redis Pub/Sub):**

```mermaid
graph TB
    Admin["Admin Portal<br/>Updates Flag"]
    API["config-service Pod A<br/>Caffeine Cache<br/>Redis Client"]

    Admin -->|PUT /flags/{key}| API
    API -->|1. Update DB| DB[("PostgreSQL")]
    API -->|2. Clear local cache| Cache_A["Caffeine<br/>Pod A"]
    API -->|3. Publish to Redis<br/>Channel: flag-invalidations| Redis{{"Redis<br/>Pub/Sub"}}

    Redis -->|Subscribe| Pod_B["Pod B<br/>Caffeine"]
    Redis -->|Subscribe| Pod_C["Pod C<br/>Caffeine"]

    Pod_B -->|4. Clear cache| Cache_B["Invalidated"]
    Pod_C -->|4. Clear cache| Cache_C["Invalidated"]

    Client1["Client<br/>Next request"] -->|Hits Pod A| API
    Client2["Client<br/>Next request"] -->|Hits Pod B| Pod_B
    Client3["Client<br/>Next request"] -->|Hits Pod C| Pod_C

    API -->|5. Load from DB| DB
    Pod_B -->|5. Load from DB| DB
    Pod_C -->|5. Load from DB| DB

    style API fill:#4CAF50
    style Pod_B fill:#4CAF50
    style Pod_C fill:#4CAF50
```

**Implementation (Wave 35):**

```java
@Component
public class FeatureFlagCacheInvalidationListener {

    private final RedisTemplate<String, String> redisTemplate;
    private final FlagEvaluationService flagService;

    @PostConstruct
    public void subscribe() {
        redisTemplate.getConnectionFactory()
            .getConnection()
            .subscribe(msg -> {
                String invalidationMsg = new String(msg.getMessage());
                // Format: "flag-invalidation:{flagKey}"
                String flagKey = invalidationMsg.split(":")[1];
                // Clear local Caffeine cache
                flagService.invalidateCache(flagKey);
            }, "flag-invalidations".getBytes());
    }

    public void publishInvalidation(String flagKey) {
        // When flag is updated in DB
        redisTemplate.convertAndSend("flag-invalidations",
            "flag-invalidation:" + flagKey);
    }
}

@Service
public class FlagEvaluationService {

    @Cacheable(value = "featureFlags", key = "#flagKey",
               condition = "#flagKey != null")
    public FlagEvaluationResponse evaluate(String flagKey, UUID userId, Map<String, Object> context) {
        // Query DB if not in Caffeine cache
        Flag flag = flagRepository.findByKey(flagKey);

        // Evaluate rule
        boolean enabled = evaluateRule(flag, userId, context);

        return new FlagEvaluationResponse(enabled, flag.getVariant());
    }

    @CacheEvict(value = "featureFlags", key = "#flagKey")
    public void invalidateCache(String flagKey) {
        // Explicit cache invalidation when Redis message received
    }
}
```

**Flow:**
1. Admin updates flag via API → DB write
2. API publishes `flag-invalidations:{key}` to Redis pub/sub
3. All pods (A, B, C) receive message → clear Caffeine cache for that key
4. Next request to any pod → cache miss → load from DB → cache

**Guarantee:** All replicas synchronized within 100ms (Redis latency + processing)

---

## Core APIs

### Evaluate Flag

**GET /flags/{key}**
```
Query:
  userId: optional UUID
  context: optional JSON map

Example: /flags/new_checkout_ui?userId=abc&context={"platform":"iOS"}

Response (200):
{
  "key": "new_checkout_ui",
  "enabled": true,
  "variant": "v2",
  "reason": "Gradual rollout 50%"
}

Caching:
  - Hit Caffeine cache (< 5ms)
  - If missing: query DB + cache result (30ms)
  - Key: {flagKey}:{userId}:{contextHash}
```

### Bulk Evaluation (Mobile Clients)

**POST /flags/bulk**
```
Request Body:
{
  "keys": ["new_checkout_ui", "loyalty_program", "dark_mode"],
  "userId": "user-uuid",
  "context": {
    "platform": "iOS",
    "osVersion": "17.0"
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
    }
  },
  "cacheKey": "abc123def456"
}

Optimization:
  - Single request (not N requests)
  - All results cached together
  - Device can use cacheKey for delta updates
```

### Admin: Create/Update Flag

**POST /flags** (Admin only)
```
Request Body:
{
  "key": "new_checkout_ui",
  "description": "New checkout experience",
  "enabled": true,
  "rolloutPercentage": 50,  // gradual rollout
  "targetAudience": {
    "segments": ["MOBILE_USERS"],
    "excludeSegments": ["VIP"]
  }
}

Response (201): Created

Side Effects:
  1. INSERT into feature_flags table
  2. Publish to "flag-invalidations" Redis topic (Wave 35)
  3. All pods clear cache for this key
```

**PUT /flags/{key}** (Admin)
```
Updates flag and publishes invalidation
```

### Admin: Create Experiment

**POST /experiments**
```
Request Body:
{
  "key": "checkout_variant_test",
  "flagKey": "new_checkout_ui",
  "variantA": { "name": "current", "enableFlag": false },
  "variantB": { "name": "experimental", "enableFlag": true },
  "targetAudience": ["iOS"],
  "allocation": 50,  // 50/50 split
  "startDate": "2025-04-01",
  "endDate": "2025-05-01"
}

Response (201): Experiment created

Assignment Logic:
  userId → hash(userId + experimentKey) % 100
  IF hash < 50: assign to variant B
  ELSE: assign to variant A
  (Consistent assignment across pod restarts)
```

---

## Database Schema

### Feature Flags Table

```sql
feature_flags:
  id              UUID PRIMARY KEY
  key             VARCHAR(255) NOT NULL UNIQUE
  description     TEXT
  enabled         BOOLEAN NOT NULL DEFAULT FALSE
  rollout_percentage INT DEFAULT 100 (0-100 for gradual rollout)
  target_segments VARCHAR[] (user segments that can access)
  exclude_segments VARCHAR[] (user segments blocked)
  created_by      VARCHAR(255)
  created_at      TIMESTAMP NOT NULL
  updated_at      TIMESTAMP NOT NULL

Indexes:
  - key (fast lookup by flag name)
  - enabled, rollout_percentage (active flags query)
```

### Experiments Table

```sql
experiments:
  id              UUID PRIMARY KEY
  key             VARCHAR(255) NOT NULL UNIQUE
  flag_id         UUID NOT NULL (FK)
  variant_a_name  VARCHAR(100)
  variant_b_name  VARCHAR(100)
  allocation_pct  INT (default 50)
  target_segments VARCHAR[]
  start_date      TIMESTAMP NOT NULL
  end_date        TIMESTAMP NOT NULL
  status          ENUM('PLANNED', 'RUNNING', 'COMPLETED')
  created_at      TIMESTAMP NOT NULL
```

### Experiment Assignments

```sql
experiment_assignments:
  id              UUID PRIMARY KEY
  experiment_id   UUID NOT NULL
  user_id         UUID NOT NULL
  assigned_variant ENUM('A', 'B')
  assigned_at     TIMESTAMP NOT NULL

Indexes:
  - UNIQUE (experiment_id, user_id) (one assignment per user)
  - user_id, assigned_at (user's experiment history)
```

### Flag Rollout Rules

```sql
flag_rollout_rules:
  id              UUID PRIMARY KEY
  flag_id         UUID NOT NULL
  rule_type       ENUM('PERCENTAGE', 'SEGMENT', 'USER_OVERRIDE')
  target_users    UUID[] (for USER_OVERRIDE)
  percentage      INT (for PERCENTAGE rollout)
  enabled         BOOLEAN
  order_index     INT (rule evaluation order)
  created_at      TIMESTAMP NOT NULL

Indexes:
  - flag_id, order_index (evaluation order)
```

---

## Caching Strategy

### Caffeine Cache Configuration

```yaml
spring:
  cache:
    caffeine:
      spec: maximumSize=10000,expireAfterWrite=30s

# Or per-cache:
cache:
  featureFlags:
    maxSize: 10000
    ttlSeconds: 30
    recordStats: true  # Hits/misses metrics
```

### Cache Key Design

```
Simple flag: "featureFlags:{flagKey}"
  Example: "featureFlags:new_checkout_ui"

User-specific: "featureFlags:{flagKey}:{userId}"
  Example: "featureFlags:new_checkout_ui:550e8400-e29b-41d4"

Context-aware: "featureFlags:{flagKey}:{userId}:{contextHash}"
  contextHash = MD5(JSON.stringify(context))
```

### Cache Invalidation (Wave 35)

1. **Explicit invalidation** (when flag updated)
   - Admin updates flag in DB
   - API publishes to Redis pub/sub `"flag-invalidations:{key}"`
   - All pods receive message → `@CacheEvict` → clear Caffeine

2. **TTL expiration** (fallback)
   - Caffeine default TTL: 30s
   - If Redis pub/sub fails, cache expires automatically after 30s

3. **Manual invalidation** (admin force-refresh)
   - Admin can trigger immediate cache clear via API
   - Publishes emergency invalidation message to Redis

---

## Resilience

### Circuit Breaker (Database)

If flag database unavailable:
- Serve from Caffeine cache (stale but available)
- Circuit breaker half-open: retry after 30s
- If DB recovers: auto-close circuit breaker

### Cache Metrics

```
cache.caffeine.hits
cache.caffeine.misses
cache.caffeine.hitRate
cache.caffeine.evictions
```

Monitor hit rate: Target > 95% (low cache miss rate = good)

---

## Testing

```bash
./gradlew :services:config-feature-flag-service:test
./gradlew :services:config-feature-flag-service:integrationTest
```

Test focus (Wave 35):
- Redis pub/sub message receipt → cache invalidation
- Cross-pod cache consistency after invalidation
- Experiment assignment consistency (hash-based)
- TTL expiration as fallback
- Graceful degradation if Redis unavailable

---

## Deployment

```bash
export FEATURE_FLAG_DB_URL=jdbc:postgresql://localhost:5432/featureflag
export REDIS_HOST=localhost
export REDIS_PORT=6379
./gradlew :services:config-feature-flag-service:bootRun
```

### Kubernetes (Wave 35)

```yaml
# ConfigMap with Redis connection
configMapKeyRef:
  name: feature-flag-config
  key: redis-host  # localhost or redis.default.svc.cluster.local

# Deployment with 3 replicas
replicas: 3
resources:
  requests:
    memory: 512Mi
    cpu: 250m
```

---

## Observability (Wave 35)

**Metrics:**
- `cache.caffeine.hitRate` — % of requests served from cache
- `featureflag.redis.pub.count` — invalidation messages published
- `featureflag.redis.sub.count` — invalidation messages received
- `featureflag.evaluation.latency_ms` — flag evaluation time

**Alerting:**
- If hitRate < 80%: investigate cache churn
- If pub/sub lag > 1s: investigate Redis connectivity
- If stale flag served > 5 times: alert on-call

---

## Known Limitations

1. No multi-region cache sync (single Redis instance)
2. No flag rollback history
3. No experiment analytics built-in (external platform)
4. No flag dependencies (flag A requires flag B)

**Roadmap (Wave 36+):**
- Multi-region Redis cluster (geo-redundancy)
- Experiment analytics integration
- Flag dependency engine
- Safe deletion workflow (flag deprecation)

