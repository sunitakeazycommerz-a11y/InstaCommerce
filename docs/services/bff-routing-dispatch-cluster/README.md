# BFF, Routing ETA & Dispatch Optimizer Cluster

## Overview

This cluster comprises three services forming the **customer-facing API layer** and **real-time logistics optimization**:

1. **mobile-bff-service**: Backend for Frontend (BFF) — reactive API adapter for mobile/web clients
2. **routing-eta-service**: Real-time delivery ETA estimation using location + traffic data
3. **dispatch-optimizer-service**: ML-backed rider assignment optimization

## Cluster Architecture

```
┌──────────────┐    ┌──────────────┐
│  Mobile App  │    │  Web Browser │
└──────────────┘    └──────────────┘
       │                    │
       └────────┬───────────┘
                │
         ┌──────▼──────┐
         │ Istio Ingress│
         └──────┬──────┘
                │
     ┌──────────┼──────────┐
     ▼          ▼          ▼
┌─────────┐ ┌──────────┐ ┌──────────────┐
│ mobile- │ │ routing- │ │  dispatch-   │
│   bff   │ │   eta    │ │  optimizer   │
│ :8085   │ │ :8086    │ │    :8087     │
└─────────┘ └──────────┘ └──────────────┘
   │           │              │
   │           │              │
   ├─────┬─────┤              │
   │     │     │              │
   ▼     ▼     ▼              ▼
 Cache Orders Location    Location Events
 (Redis) Data (Kafka)     (Kafka)
```

## Service Responsibilities

### Mobile BFF Service (Tier 1)
- **API Gateway Pattern**: Aggregates domain service calls into optimized mobile API
- **GraphQL/REST**: Flexible data fetching for heterogeneous clients
- **Response Caching**: Caffeine cache for trending products, store listings
- **Request Batching**: Debounces rapid client requests
- **Circuit Breaker**: Resilience4j + fallback responses
- **Port**: 8085 (dev), 8080 (container)
- **Runtime**: Spring Boot WebFlux (non-blocking/reactive)

### Routing ETA Service (Tier 2)
- **ETA Calculation**: Predicts delivery time using distance + historical traffic
- **Real-time Updates**: Listens to rider location Kafka stream
- **Location Indexing**: Redis geo-spatial indexing for nearest-rider queries
- **Port**: 8086 (dev), 8080 (container)
- **Runtime**: Java 21 / Spring Boot 4

### Dispatch Optimizer Service (Tier 2)
- **Rider Assignment**: ML model selects optimal rider for order using location + availability
- **Load Balancing**: Distributes orders fairly across zone riders
- **Rebalancing**: Periodic assignment optimization during delivery
- **Port**: 8087 (dev), 8080 (container)
- **Runtime**: Go 1.24 (fast math-heavy optimization)

## Key Technologies

| Component | Version | Purpose |
|-----------|---------|---------|
| Spring Boot WebFlux | 4.0 | Reactive HTTP server |
| Redis | 7.0+ | Caching + geo-spatial indexing |
| Resilience4j | 2.2.0 | Circuit breaker, bulkhead, retry |
| Kafka | 2.8+ | Location stream, rider events |
| Go | 1.24 | High-performance optimization solver |
| PostgreSQL | 15+ | Order/rider state (routing-eta) |

## Deployment Model

- **Infrastructure**: GKE asia-south1, Istio mesh
- **Replicas**: mobile-bff (5-20 HPA), routing-eta (3-10), dispatch-optimizer (2-5)
- **Data stores**: Redis cluster (3-node), PostgreSQL primary + replicas
- **Caching**: Caffeine (local) + Redis (distributed) dual-layer

## API Contracts

### Mobile BFF Service
```
GET    /api/v2/user/profile              → User profile (cached)
GET    /api/v2/orders                    → Order history
POST   /api/v2/orders                    → Place order
GET    /api/v2/orders/{id}               → Order details + ETA
POST   /api/v2/orders/{id}/cancel        → Cancel order
GET    /api/v2/catalog/trending          → Top products (cached)
GET    /api/v2/stores/nearby             → Stores by location
GET    /api/v2/notifications             → User notifications
```

### Routing ETA Service
```
GET    /api/v1/eta                       → Calculate ETA
GET    /api/v1/eta/{orderId}             → Get cached ETA
POST   /api/v1/eta/batch                 → Batch ETA lookup
```

### Dispatch Optimizer Service
```
POST   /api/v1/assign                    → Assign rider to order
GET    /api/v1/assignment/{orderId}      → Get current assignment
POST   /api/v1/rebalance                 → Rebalance zone assignments
```

## Observability

### Metrics

```
mobile_bff_requests_total{endpoint, status}       # BFF request counter
mobile_bff_response_time_seconds{}                # Response latency histogram
cache_hit_ratio{cache_name}                       # Caffeine cache effectiveness
circuit_breaker_calls_total{state}                # Resilience4j circuit state
routing_eta_calculation_ms{}                      # ETA computation latency
dispatch_optimization_ms{}                        # Assignment solver latency
```

### Logging

JSON structured via `log/slog` (Go services):
```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "service": "routing-eta-service",
  "level": "INFO",
  "message": "ETA calculated",
  "order_id": "ord-123",
  "eta_minutes": 18,
  "distance_km": 2.5,
  "trace_id": "abc123"
}
```

## Failure Modes & Recovery

| Scenario | Detection | Mitigation |
|----------|-----------|-----------|
| Redis cache down | Connection timeout | Fallback to source DB; cache disabled |
| Order service unavailable | Circuit breaker open | Return cached response if available |
| Kafka location stream stalled | Consumer lag > 10,000 | Alert ops; rider assignments stale |
| Dispatch solver timeout | Solver runs > 5s | Return greedy assignment, escalate |
| Mobile client throttled | 429 responses | Client-side exponential backoff |

## Deployment Checklist

- [ ] BFF cache TTLs validated for expected traffic patterns
- [ ] Circuit breaker thresholds tuned to domain latencies
- [ ] Routing ETA model tested on current traffic snapshot
- [ ] Dispatch solver performance regression tested (< 100ms)
- [ ] Kafka topic subscriptions verified (location.updates exists)
- [ ] Redis cluster reachability confirmed
- [ ] Readiness probes include cache + Kafka health

## Known Limitations

1. **Mobile BFF caching**: No cache invalidation on upstream changes → stale data for 5-10 min
2. **Routing ETA**: Uses static historical traffic data, not real-time → inaccurate during anomalies
3. **Dispatch Optimizer**: ML model frozen at deploy time → no online learning
4. **Cache stampede**: Multiple clients hitting cold cache simultaneously → thundering herd
5. **Geo-indexing stale**: Redis geo data only updated via Kafka stream, laggy on rapid moves

## References

- [Mobile BFF Design Review](/docs/reviews/api-gateway-bff-design.md) — Detailed BFF rationale
- [Routing ETA Architecture](/docs/architecture/INFRASTRUCTURE.md) — GCP Maps integration
- [Dispatch Optimizer ML Pipeline](/docs/reviews/FLEET-ARCHITECTURE-REVIEW-2026-02-13.md) — Model training
- [Resilience4j Guide](https://resilience4j.readme.io/) — Framework documentation
