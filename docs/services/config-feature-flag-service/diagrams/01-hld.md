# Config & Feature Flag Service - High-Level Design (HLD)

## System Architecture Overview

The Config & Feature Flag Service is a distributed caching system with Wave 35 Redis pub/sub invalidation for fast cross-pod consistency. The architecture separates concerns into three layers:

### Layer 1: Client Interface
- **End-Users & Mobile Clients**: Query flag evaluation via HTTP REST
- **Admin Portal**: Update flags and manage experiments
- **Internal Services**: Consume flags via SDK or HTTP

### Layer 2: Feature Flag Engine (3 Replicas)
- **Local Cache**: Caffeine cache (10k max entries, 30s TTL)
- **Evaluation Engine**: Rule-based flag evaluation with context awareness
- **Experiment Assignment**: Hash-based consistent assignment (userId + experimentKey)
- **Redis Pub/Sub Client**: Subscribe to invalidation messages

### Layer 3: Data & Persistence
- **PostgreSQL**: Flag definitions, experiments, rollout rules, assignments
- **Redis**: Pub/sub channel for cache invalidation messages

---

## System Diagram (Wave 35 Architecture)

```mermaid
graph TB
    subgraph "External Systems"
        AdminUI["🔐 Admin Portal"]
        MobileApp["📱 Mobile Clients"]
        InternalSvc["🔗 Internal Services"]
    end

    subgraph "API Gateway / Load Balancer"
        LB["⚖️ Load Balancer<br/>Round-robin"]
    end

    subgraph "config-feature-flag-service Cluster (3 Replicas)"
        Pod1["Pod 1<br/>Port 8092<br/>Spring Boot 4.0"]
        Pod2["Pod 2<br/>Port 8092<br/>Spring Boot 4.0"]
        Pod3["Pod 3<br/>Port 8092<br/>Spring Boot 4.0"]

        subgraph "Cache Layer (All Pods)"
            Cache1["Caffeine<br/>10k entries<br/>30s TTL"]
            Cache2["Caffeine<br/>10k entries<br/>30s TTL"]
            Cache3["Caffeine<br/>10k entries<br/>30s TTL"]
        end

        subgraph "Evaluation Engine (All Pods)"
            Engine1["FlagEvaluationService<br/>Rule Evaluation"]
            Engine2["FlagEvaluationService<br/>Rule Evaluation"]
            Engine3["FlagEvaluationService<br/>Rule Evaluation"]
        end

        subgraph "Redis Pub/Sub Subscribers (All Pods)"
            Sub1["FlagCacheInvalidator<br/>Subscribes to<br/>flag-invalidations"]
            Sub2["FlagCacheInvalidator<br/>Subscribes to<br/>flag-invalidations"]
            Sub3["FlagCacheInvalidator<br/>Subscribes to<br/>flag-invalidations"]
        end
    end

    subgraph "Persistence Layer"
        PostgreSQL["🗄️ PostgreSQL 15+<br/>feature_flags<br/>experiments<br/>experiment_assignments<br/>flag_rollout_rules"]
        Redis["🔴 Redis<br/>Pub/Sub Channel<br/>flag-invalidations"]
    end

    subgraph "Monitoring & Observability"
        Prometheus["📊 Prometheus<br/>Metrics"]
        Logs["📝 Logs<br/>OpenTelemetry"]
    end

    AdminUI -->|1. PUT /flags/{key}| LB
    MobileApp -->|2. GET /flags/bulk| LB
    InternalSvc -->|3. GET /flags/{key}| LB

    LB -->|Route| Pod1
    LB -->|Route| Pod2
    LB -->|Route| Pod3

    Pod1 --> Cache1
    Pod2 --> Cache2
    Pod3 --> Cache3

    Cache1 --> Engine1
    Cache2 --> Engine2
    Cache3 --> Engine3

    Engine1 --> PostgreSQL
    Engine2 --> PostgreSQL
    Engine3 --> PostgreSQL

    Pod1 --> Sub1
    Pod2 --> Sub2
    Pod3 --> Sub3

    Sub1 -->|4. Subscribe| Redis
    Sub2 -->|4. Subscribe| Redis
    Sub3 -->|4. Subscribe| Redis

    Engine1 -->|5. Publish invalidation| Redis
    Engine2 -->|5. Publish invalidation| Redis
    Engine3 -->|5. Publish invalidation| Redis

    Pod1 --> Prometheus
    Pod2 --> Prometheus
    Pod3 --> Prometheus

    Pod1 --> Logs
    Pod2 --> Logs
    Pod3 --> Logs

    style Pod1 fill:#4CAF50,color:#fff
    style Pod2 fill:#4CAF50,color:#fff
    style Pod3 fill:#4CAF50,color:#fff
    style PostgreSQL fill:#336699,color:#fff
    style Redis fill:#E74C3C,color:#fff
    style Prometheus fill:#FF9800,color:#fff
    style LB fill:#9C27B0,color:#fff
```

---

## Wave 35 Enhancement: Redis Pub/Sub Cache Invalidation

### Problem (Pre-Wave 35)
- Service has 3 replicas with Caffeine local cache
- Flag updated on Pod A → cache cleared only on Pod A
- Pods B & C serve stale value for 30s → user inconsistency

### Solution (Wave 35)
- **Publisher**: When flag updated in DB, publish message to Redis channel
- **Subscribers**: All 3 pods subscribe to `flag-invalidations` channel
- **Propagation**: <100ms (Redis latency + Spring message handler)
- **Fallback**: TTL expiration (30s) if Redis pub/sub unavailable

### Guarantee
✅ All replicas synchronized within **<500ms SLO**

---

## Data Flow Layers

### 1. Read Path (Cache-First)
```
Client Request → Load Balancer → Pod (1 of 3)
    ↓
Check Caffeine Cache
    ├─ HIT (< 5ms) → Return flag
    └─ MISS (Cache evicted or new flag)
        ↓
    Query PostgreSQL (30ms)
    ↓
    Update Caffeine Cache
    ↓
    Return flag to client
```

### 2. Write Path (Flag Update)
```
Admin Portal → PUT /flags/{key} → Load Balancer → Pod
    ↓
1. Update PostgreSQL
    ↓
2. Clear local Caffeine cache
    ↓
3. Publish to Redis channel: "flag-invalidations:{flagKey}"
    ↓
4. All 3 pods receive message
    ↓
5. All pods clear Caffeine cache for that key
    ↓
6. Return 200 OK to admin
```

### 3. Invalidation Path (Wave 35)
```
Redis Pub/Sub Message → All Subscribers
    ↓
FlagCacheInvalidator receives message (5ms)
    ↓
Spring @CacheEvict annotation
    ↓
Caffeine removes entry
    ↓
Next client request → Cache MISS → Load from DB
```

---

## Component Responsibilities

| Component | Responsibility |
|-----------|-----------------|
| **FlagEvaluationService** | Evaluate flag rules, manage Caffeine cache, return enabled status |
| **FlagCacheEventListener** | Publish invalidation messages to Redis when flag updated |
| **FlagCacheInvalidator** | Subscribe to Redis, trigger `@CacheEvict` on all pods |
| **ExperimentService** | Consistent user-to-variant assignment (MD5 hash based) |
| **RolloutRuleEngine** | Evaluate percentage-based, segment-based, user-override rules |
| **HealthCheckService** | Monitor cache hit rate, Redis connectivity, DB health |

---

## Consistency & Resilience Model

### Strong Consistency (Wave 35)
- ✅ Flag update → immediately visible to all clients
- ✅ No stale reads > 100ms (Redis pub/sub latency)
- ✅ Graceful fallback to 30s TTL if Redis unavailable

### Availability
- ✅ 99.99% SLO (3 replicas, auto-failover)
- ✅ Pod failure → traffic shifts to remaining 2 pods
- ✅ Partial outage: serve from Caffeine (stale but available)

### Experiment Consistency
- ✅ Hash-based assignment (userId + experimentKey) → deterministic
- ✅ Same user always gets same variant across pod restarts
- ✅ No double-counting in experiment analytics

---

## Deployment Topology (Kubernetes)

```
Namespace: feature-flags

Deployment: config-feature-flag-service
├─ Replicas: 3
├─ Image: config-feature-flag-service:latest
├─ Ports: 8092 (service), 9090 (metrics)
├─ Resources:
│  ├─ Requests: 250m CPU, 512Mi Memory
│  ├─ Limits: 500m CPU, 1Gi Memory
├─ Readiness Probe: /actuator/health (PostgreSQL + Redis)
├─ Environment:
│  ├─ SPRING_DATASOURCE_URL: jdbc:postgresql://postgres.database.svc.cluster.local:5432/featureflag
│  ├─ REDIS_HOST: redis.cache.svc.cluster.local
│  ├─ REDIS_PORT: 6379

Service: config-feature-flag-service
├─ Type: ClusterIP
├─ Port: 8092
└─ Selector: app=config-feature-flag-service

PVC: feature-flag-db
└─ Storage: 20Gi (PostgreSQL persistent volume)
```

---

## Key Metrics & SLO

| Metric | P50 | P99 | SLO |
|--------|-----|-----|-----|
| **Flag Evaluation** | 5ms | 50ms | 99.99% |
| **Redis Pub/Sub Propagation** | 20ms | 100ms | <500ms |
| **Experiment Assignment** | 2ms | 20ms | 99.9% |
| **Cache Hit Rate** | N/A | N/A | >95% |

---

## External Dependencies

| Dependency | Purpose | Failure Impact |
|-----------|---------|-----------------|
| **PostgreSQL** | Flag definitions storage | Circuit breaker: serve stale cache |
| **Redis** | Pub/sub invalidation | Graceful: fallback to 30s TTL |
| **Identity Service** | User segments for targeting | Shared segments cache |
| **Kafka** | Consume user created events | Queue backlog, catch-up on restart |

---

## Security

- ✅ JWT RS256 authentication (from identity-service JWKS)
- ✅ RBAC: Admin endpoints require `ADMIN` role
- ✅ Service-to-service: Per-service token validation
- ✅ Postgres: SSL connection, encrypted password
- ✅ Redis: Password-protected (if external), VPC-isolated

