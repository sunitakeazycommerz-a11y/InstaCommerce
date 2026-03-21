# Mobile BFF - All 7 Diagrams

## 1. High-Level Design

```mermaid
graph TB
    Mobile["📱 Mobile Client"]
    BFF["🔗 Mobile BFF<br/>(Aggregator)"]
    Cart["🛒 Cart Service"]
    Catalog["📚 Catalog Service"]
    Inventory["📦 Inventory Service"]
    Pricing["💰 Pricing Service"]
    Search["🔍 Search Service"]
    Cache["⚡ Redis Cache<br/>(User sessions, search)"]

    Mobile -->|GraphQL/REST| BFF
    BFF -->|Parallel fetch| Cart
    BFF -->|Parallel fetch| Catalog
    BFF -->|Parallel fetch| Inventory
    BFF -->|Parallel fetch| Pricing
    BFF -->|Parallel fetch| Search
    BFF -->|Session, cache| Cache

    style BFF fill:#4A90E2,color:#fff
    style Cache fill:#F5A623,color:#000
```

## 2. Low-Level Design

```mermaid
graph LR
    Request["HTTP Request"]
    AuthFilter["Auth Filter<br/>(JWT validation)"]
    RequestParser["Parser<br/>(JSON/GraphQL)"]
    DataAggregator["Data Aggregator<br/>(Parallel fetcher)"]
    DataTransformer["Transformer<br/>(API response shaping)"]
    ResponseBuilder["Response Builder<br/>(GraphQL resolver)"]
    Response["HTTP Response"]

    Request --> AuthFilter
    AuthFilter --> RequestParser
    RequestParser --> DataAggregator
    DataAggregator --> DataTransformer
    DataTransformer --> ResponseBuilder
    ResponseBuilder --> Response

    style DataAggregator fill:#7ED321,color:#000
    style ResponseBuilder fill:#7ED321,color:#000
```

## 3. Flowchart - Dashboard Query

```mermaid
flowchart TD
    A["GET /api/mobile/dashboard"]
    B["Authenticate user"]
    C["Check session cache"]
    D{{"Cached?"}}
    E["Fetch user preferences"]
    F["Fetch cart contents"]
    G["Fetch personalized offers"]
    H["Fetch inventory status"]
    I["Aggregate responses"]
    J["Apply transformations"]
    K["Cache for 5 minutes"]
    L["Return 200 OK"]

    A --> B
    B --> C
    C --> D
    D -->|Yes| L
    D -->|No| E
    E --> F
    F --> G
    G --> H
    H --> I
    I --> J
    J --> K
    K --> L

    style A fill:#4A90E2,color:#fff
    style L fill:#7ED321,color:#000
```

## 4. Sequence Diagram

```mermaid
sequenceDiagram
    actor User as Mobile User
    participant App as Mobile App
    participant BFF as Mobile BFF
    participant CartSvc as Cart Service
    participant CatalogSvc as Catalog Service
    participant PricingSvc as Pricing Service
    participant Cache as Redis Cache

    User->>App: Open app
    App->>BFF: GET /dashboard<br/>with JWT
    BFF->>Cache: Check user_dashboard
    Cache-->>BFF: Cache miss
    par Parallel Fetch
        BFF->>CartSvc: GET /carts/user123
        BFF->>CatalogSvc: GET /recommendations
        BFF->>PricingSvc: GET /user123/personalized-pricing
    and Wait
        CartSvc-->>BFF: Cart items
        CatalogSvc-->>BFF: Recommendations
        PricingSvc-->>BFF: Pricing
    end
    BFF->>BFF: Merge responses
    BFF->>Cache: Cache dashboard (5min)
    BFF-->>App: 200 OK {dashboard}
    App-->>User: Display dashboard

    Note over User,Cache: Total: ~150ms (p99)
```

## 5. State Machine

```mermaid
stateDiagram-v2
    [*] --> REQUEST_RECEIVED
    REQUEST_RECEIVED --> AUTHENTICATED
    AUTHENTICATED --> CACHE_CHECKED
    CACHE_CHECKED --> HIT: Found
    CACHE_CHECKED --> MISS: Not found
    HIT --> RESPONSE_SENT
    MISS --> PARALLEL_FETCH
    PARALLEL_FETCH --> AGGREGATING
    AGGREGATING --> TRANSFORMING
    TRANSFORMING --> CACHING
    CACHING --> RESPONSE_SENT
    RESPONSE_SENT --> [*]

    note right of PARALLEL_FETCH
        Fetch from 3-4 services
        concurrently with timeout
    end note

    note right of TRANSFORMING
        Reshape into mobile-friendly
        format (GraphQL response)
    end note
```

## 6. ER Diagram

```mermaid
erDiagram
    USER_SESSION {
        string user_id PK
        json dashboard_cache "Aggregated data"
        json preferences "Mobile settings"
        timestamp expires_at "5min TTL"
    }

    CACHE_STATS {
        string endpoint "e.g., /dashboard"
        integer hits "Cache hits"
        integer misses "Cache misses"
        float hit_ratio "hits/total"
    }

    USER_SESSION ||--o{ CACHE_STATS : tracked_by
```

## 7. End-to-End Diagram

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
