# Mobile BFF - End-to-End Diagram

```mermaid
graph TB
    User["👤 User<br/>(Mobile App)"]
    CDN["🌍 CDN"]
    ALB["⚖️ ALB"]
    BFF["🔗 Mobile BFF<br/>(Load balanced: 3 pods)"]
    Cache["⚡ Redis"]
    CartSvc["🛒 Cart Service"]
    CatalogSvc["📚 Catalog Service"]
    PricingSvc["💰 Pricing Service"]
    DB1["🗄️ Cart DB"]
    DB2["🗄️ Catalog DB"]

    User -->|1. HTTPS| CDN
    CDN -->|2. Forward| ALB
    ALB -->|3. Route| BFF
    BFF -->|4. Check| Cache
    Cache -->|5. Miss| BFF
    BFF -->|6. Fetch| CartSvc
    BFF -->|7. Fetch| CatalogSvc
    BFF -->|8. Fetch| PricingSvc
    CartSvc -->|Query| DB1
    CatalogSvc -->|Query| DB2
    CartSvc -->|Data| BFF
    CatalogSvc -->|Data| BFF
    PricingSvc -->|Data| BFF
    BFF -->|9. Aggregate| BFF
    BFF -->|10. Cache (5min)| Cache
    BFF -->|11. Response| ALB
    ALB -->|12. HTTPS| User

    style BFF fill:#4A90E2,color:#fff
    style Cache fill:#F5A623,color:#000

    classDef parallelFetch fill:#50E3C2,color:#000
    class 6,7,8 parallelFetch
```

## Complete System Architecture

### 1. User & Client Layer
- **User**: End-user on iOS/Android mobile app
- **Communication**: HTTPS over cellular/WiFi networks
- **Payload Size**: Typical query = 2-5KB

### 2. CDN & Caching Layer
- **CloudFront/CDN**: Caches static assets (HTML, JS, CSS, images)
- **Purpose**: Reduce latency for geo-distributed users
- **Cache Duration**:
  - Static assets: 1 hour
  - API responses: Not cached at CDN (origin must serve)
- **Edge Locations**: 200+ globally for low latency

### 3. Load Balancing Layer
- **AWS ALB**: Application Load Balancer
- **SSL/TLS Termination**: Handles HTTPS encryption/decryption
- **Health Checks**: Pings each BFF pod every 5 seconds
- **Routing Strategy**:
  - Round-robin across 3 BFF pods
  - Sticky sessions (client → same pod, 5 min duration)
- **Rate Limiting**: 100 requests/second per user

### 4. Mobile BFF Layer
- **Deployment**: 3 pods (for high availability)
- **Framework**: Java Spring Boot (or similar)
- **Responsibilities**:
  - JWT authentication
  - Request routing to downstream services
  - Response aggregation
  - Cache management
- **Scaling**: Auto-scales 3-10 pods based on traffic

### 5. Caching Layer
- **Redis Cluster**: 6+ nodes for 99.9% availability
- **Purpose**: Store dashboard responses, session data
- **TTL**: 5 minutes per entry
- **Eviction**: LRU (least recently used) when memory full

### 6. Downstream Services
- **Cart Service**: Manages shopping cart per user
- **Catalog Service**: Product catalog and recommendations
- **Pricing Service**: Dynamic pricing, discounts, personalization
- **Database Layer**: PostgreSQL instances backing each service

## End-to-End Request Flow (11 Steps)

### Step 1-3: Client to ALB
```
User HTTPS Request (GraphQL query)
  ↓
CDN (static assets already cached)
  ↓
ALB (SSL termination, health checks)
```

### Step 4-5: Cache Check
```
BFF receives request on specific pod
  ↓
Query Redis with cache key: {user_id}:{endpoint}
  ↓
Cache Miss (or Hit → skip to step 11)
```

### Step 6-8: Parallel Fetch
```
BFF spawns 3 concurrent requests:
  ├─ Cart Service (gRPC): Get user's cart items
  ├─ Catalog Service (REST): Get recommendations
  └─ Pricing Service (gRPC): Get personalized pricing

All services query their respective databases in parallel
```

### Step 9: Aggregation
```
Wait for all 3 responses (max 2-3 second timeout per service)
  ↓
Merge responses into single payload
  ├─ Cart items + recommendations side-by-side
  ├─ Apply pricing discounts
  └─ Remove duplicates
```

### Step 10: Cache Store
```
Store aggregated response in Redis
  ├─ Cache key: {user_id}:{endpoint}
  ├─ Value: Aggregated JSON
  ├─ TTL: 5 minutes
  └─ Compression: gzip (typical size: 2-5KB after compression)
```

### Step 11-12: Response
```
BFF serializes to JSON and compresses
  ↓
ALB forwards HTTPS response
  ↓
User's mobile app receives dashboard (p99: 150ms)
```

## Infrastructure Components

### Compute
- **Mobile BFF**: 3 pods, 2 CPU, 4GB RAM each
- **Downstream Services**: 2-4 pods each
- **Orchestration**: Kubernetes (EKS)

### Networking
- **Service Mesh**: Istio for traffic management
- **Service Discovery**: Kubernetes DNS (service.default.svc.cluster.local)
- **Network Policies**: Restrict traffic to needed services only
- **Ingress**: ALB Ingress Controller

### Data
- **Caching**: Redis Cluster (6 nodes, 64GB total)
- **Databases**: PostgreSQL (per service, 1 primary + 1 replica)
- **Event Stream**: Kafka for asynchronous events

### Observability
- **Metrics**: Prometheus (scrape BFF pods every 15 seconds)
- **Tracing**: Jaeger (trace individual requests end-to-end)
- **Logging**: CloudWatch (aggregate logs from all pods)
- **Dashboards**: Grafana (latency, error rate, cache hit ratio)

## SLA & Performance

| Metric | Target | Current |
|--------|--------|---------|
| Availability | 99.9% | 99.92% |
| p50 Latency | 80ms | 75ms |
| p99 Latency | 150ms | 145ms |
| Cache Hit Rate | > 60% | 65% |
| Error Rate | < 0.1% | 0.08% |

## Scaling Characteristics

- **Requests per second (single pod)**: 500 req/s
- **BFF cluster capacity** (3 pods): 1,500 req/s
- **Auto-scale trigger**: 70% CPU utilization
- **Max pods**: 10 (hard limit)
- **Scale-up time**: 30-45 seconds
- **Scale-down time**: 5 minutes (cool-down period)

## Resilience Patterns

| Failure Scenario | Behavior | Recovery Time |
|------------------|----------|----------------|
| 1 BFF pod down | Route to remaining 2 pods | 30s (health check) |
| Cart Service down | Return cached cart from previous request | < 1s |
| Redis unavailable | Continue without cache (slower) | Manual |
| ALB AZ down | Route to another AZ | 10s |
| Database connection pool exhausted | Return 503, retry with backoff | 30s |
