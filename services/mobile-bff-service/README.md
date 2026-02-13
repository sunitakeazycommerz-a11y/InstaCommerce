# Mobile BFF Service

Backend-For-Frontend (BFF) service providing mobile-optimized API aggregation for the InstaCommerce mobile app. Aggregates data from multiple backend microservices into single, efficient responses tailored for mobile consumption using reactive (WebFlux) non-blocking I/O.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Key Components](#key-components)
- [Aggregation Pattern](#aggregation-pattern)
- [Supported Mobile Endpoints](#supported-mobile-endpoints)
- [Caching Strategy](#caching-strategy)
- [API Reference](#api-reference)
- [Configuration](#configuration)

---

## Architecture Overview

```mermaid
graph TB
    subgraph Mobile Clients
        IOS[iOS App]
        ANDROID[Android App]
    end

    subgraph Mobile BFF Service :8097
        MBC[MobileBffController<br>/bff/mobile/v1 • /m/v1]
        CACHE[(Caffeine Cache)]
        CB[Resilience4j<br>Circuit Breaker]
    end

    subgraph Backend Services
        PRODUCT[Product Service]
        CATALOG[Catalog Service]
        ORDER[Order Service]
        USER[Identity Service]
        CART[Cart Service]
        FF[Feature Flag Service]
        SEARCH[Search Service]
    end

    subgraph Observability
        OTEL[OpenTelemetry Collector]
        PROM[Prometheus]
    end

    IOS --> MBC
    ANDROID --> MBC

    MBC --> CACHE
    MBC --> CB
    CB --> PRODUCT & CATALOG & ORDER & USER & CART & FF & SEARCH

    MBC --> OTEL
    OTEL --> PROM
```

---

## Key Components

| Component | Responsibility |
|---|---|
| **MobileBffController** | Mobile-facing API layer — exposes aggregated endpoints at `/bff/mobile/v1` and `/m/v1` (short alias) |
| **Caffeine Cache** | In-memory caching for frequently accessed, slow-changing data (categories, promotions) |
| **Resilience4j Circuit Breaker** | Protects against backend service failures with fallback responses |
| **WebFlux Reactive Stack** | Non-blocking I/O for parallel service calls and efficient mobile response times |

---

## Aggregation Pattern

```mermaid
sequenceDiagram
    participant App as Mobile App
    participant BFF as Mobile BFF
    participant Product as Product Svc
    participant Catalog as Catalog Svc
    participant Cart as Cart Svc
    participant Flags as Feature Flag Svc
    participant User as Identity Svc

    App->>BFF: GET /bff/mobile/v1/home

    par Parallel Service Calls
        BFF->>Product: GET /products/featured
        BFF->>Catalog: GET /categories/top
        BFF->>Cart: GET /cart/{userId}
        BFF->>Flags: POST /flags/bulk
        BFF->>User: GET /users/{userId}/profile
    end

    Product-->>BFF: Featured products
    Catalog-->>BFF: Top categories
    Cart-->>BFF: Cart summary
    Flags-->>BFF: Feature flags
    User-->>BFF: User profile

    BFF->>BFF: Aggregate & transform<br>for mobile payload

    BFF-->>App: Single optimized response
```

### Why BFF?

```mermaid
flowchart LR
    subgraph Without BFF
        M1[Mobile App] -->|Request 1| S1[Service A]
        M1 -->|Request 2| S2[Service B]
        M1 -->|Request 3| S3[Service C]
        M1 -->|Request 4| S4[Service D]
        M1 -->|Request 5| S5[Service E]
    end

    subgraph With BFF
        M2[Mobile App] -->|1 Request| BFF[Mobile BFF]
        BFF -->|Parallel| S6[Service A]
        BFF -->|Parallel| S7[Service B]
        BFF -->|Parallel| S8[Service C]
        BFF -->|Parallel| S9[Service D]
        BFF -->|Parallel| S10[Service E]
    end
```

**Benefits:**
- **Fewer round trips** — One mobile request instead of 5+ separate API calls
- **Lower latency** — Parallel server-side calls over fast internal network
- **Battery & data savings** — Reduced connection overhead on mobile
- **Mobile-shaped payloads** — Only fields the mobile app needs, no over-fetching
- **Backend isolation** — Mobile app decoupled from service topology changes

---

## Supported Mobile Endpoints

```mermaid
mindmap
  root((Mobile BFF))
    Home Screen
      Featured Products
      Top Categories
      Promotions
      Cart Badge Count
      Feature Flags
    Product
      Product Detail
      Related Products
      Reviews Summary
    Cart
      Cart Contents
      Price Summary
      Shipping Options
    Search
      Product Search
      Search Suggestions
      Filters
    Profile
      User Info
      Order History
      Addresses
```

---

## Caching Strategy

```mermaid
flowchart TD
    subgraph Mobile BFF Cache
        CC[Caffeine Cache<br>In-Memory]
    end

    subgraph Cache Tiers
        HOT[Hot Data — TTL: short<br>Cart, Prices]
        WARM[Warm Data — TTL: medium<br>Product Details, Search]
        COLD[Cold Data — TTL: long<br>Categories, Promotions, Config]
    end

    REQ[Mobile Request] --> CC
    CC -->|Cache Hit| RES[Fast Response]
    CC -->|Cache Miss| SVC[Backend Services]
    SVC --> CC
    CC --> RES

    CC --> HOT & WARM & COLD

    subgraph Invalidation
        EVT[Service Events] -->|Evict| CC
        TTL_EXP[TTL Expiry] -->|Auto-evict| CC
    end

    style CC fill:#ffd700,stroke:#333
```

| Data Category | Cache Strategy | Rationale |
|---|---|---|
| Categories & Navigation | Long TTL | Changes infrequently, used on every screen |
| Product Listings | Medium TTL | Moderate change rate, high read frequency |
| Cart & Prices | Short TTL / No cache | Must be fresh for checkout accuracy |
| Feature Flags | Short TTL | Flags cached upstream in Feature Flag Service |
| User Profile | Per-session | User-specific, cached for session duration |

**Implementation:**
- Cache type: **Caffeine** (configured via Spring Cache abstraction)
- Dependency: `com.github.ben-manes.caffeine:caffeine:3.1.8`
- Circuit breaker: **Resilience4j** — returns cached/fallback data when backend services are degraded

---

## API Reference

### Mobile Endpoints

Both path prefixes are equivalent — `/m/v1` is a short alias for `/bff/mobile/v1`.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/bff/mobile/v1/home` | Home screen aggregated data |
| `GET` | `/m/v1/home` | Home screen (short alias) |

### Home Response

**GET /bff/mobile/v1/home**

```json
{
  "status": "ok"
}
```

### Actuator Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/actuator/health/liveness` | Kubernetes liveness probe |
| `GET` | `/actuator/health/readiness` | Kubernetes readiness probe |
| `GET` | `/actuator/prometheus` | Prometheus metrics scrape endpoint |
| `GET` | `/actuator/info` | Application info |

### Error Responses

| Status | Description |
|---|---|
| `401` | Missing or invalid authentication |
| `502` | One or more backend services unavailable |
| `504` | Backend service timeout |

---

## Configuration

### application.yml

```yaml
server:
  port: ${SERVER_PORT:8097}
  shutdown: graceful

spring:
  application:
    name: mobile-bff-service
  cache:
    type: caffeine
  lifecycle:
    timeout-per-shutdown-phase: 30s

internal:
  service:
    token: ${INTERNAL_SERVICE_TOKEN:dev-internal-token-change-in-prod}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
  tracing:
    sampling:
      probability: ${TRACING_PROBABILITY:1.0}
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://otel-collector.monitoring:4318/v1/traces}
    metrics:
      export:
        endpoint: ${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:http://otel-collector.monitoring:4318/v1/metrics}
```

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8097` | HTTP server port |
| `INTERNAL_SERVICE_TOKEN` | `dev-internal-token-change-in-prod` | Token for inter-service authentication |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://otel-collector.monitoring:4318/v1/traces` | OpenTelemetry traces endpoint |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `http://otel-collector.monitoring:4318/v1/metrics` | OpenTelemetry metrics endpoint |
| `TRACING_PROBABILITY` | `1.0` | Trace sampling probability (0.0–1.0) |
| `ENVIRONMENT` | `dev` | Deployment environment tag |

### Tech Stack

- Java 21, Spring Boot 3.x (WebFlux)
- Reactive stack (Project Reactor / `Mono<T>`)
- Caffeine Cache
- Resilience4j (Circuit Breaker)
- Micrometer + OpenTelemetry + Prometheus
- ZGC garbage collector
- Docker (Alpine, non-root user)
- Kubernetes health probes (liveness/readiness)
