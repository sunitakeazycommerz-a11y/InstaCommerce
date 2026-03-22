# Inventory Service - Low-Level Design

## Component Architecture

```mermaid
graph TB
    HTTPRequest["🌐 HTTP Request<br/>(POST /inventory/reserve)"]

    DispatcherServlet["DispatcherServlet<br/>(Spring MVC)"]
    JwtFilter["🔐 JwtAuthenticationFilter<br/>(JWT validation)"]
    CorrelationFilter["🔗 CorrelationIdFilter<br/>(Request tracing)"]

    SecurityContext["🛡️ SecurityContext<br/>(principal + authorities)"]

    RateLimiter["⏱️ RateLimitService<br/>(per IP, Caffeine cache)"]

    ReservationController["🎯 ReservationController"]
    StockController["🎯 StockController"]

    ReservationService["📦 ReservationService<br/>(reserve, confirm, cancel)"]
    InventoryService["📦 InventoryService<br/>(check, adjust)"]

    EntityManager["🗃️ EntityManager<br/>(PESSIMISTIC_WRITE locks)"]
    StockItemRepo["StockItemRepository"]
    ReservationRepo["ReservationRepository"]

    OutboxService["📤 OutboxService<br/>(Event publishing)"]
    AuditLogService["📝 AuditLogService"]

    PostgreSQL["🗃️ PostgreSQL"]
    Redis["⚡ Redis<br/>(Rate limiting)"]

    HTTPRequest --> DispatcherServlet
    DispatcherServlet --> CorrelationFilter
    CorrelationFilter --> JwtFilter
    JwtFilter --> SecurityContext

    SecurityContext --> RateLimiter
    RateLimiter --> Redis

    RateLimiter --> ReservationController
    RateLimiter --> StockController

    ReservationController --> ReservationService
    StockController --> InventoryService

    ReservationService --> EntityManager
    InventoryService --> EntityManager

    EntityManager --> StockItemRepo
    EntityManager --> ReservationRepo

    StockItemRepo --> PostgreSQL
    ReservationRepo --> PostgreSQL

    ReservationService --> OutboxService
    InventoryService --> OutboxService
    InventoryService --> AuditLogService

    OutboxService --> PostgreSQL

    style JwtFilter fill:#7ED321,color:#000
    style SecurityContext fill:#4A90E2,color:#fff
    style ReservationService fill:#4A90E2,color:#fff
    style InventoryService fill:#4A90E2,color:#fff
    style EntityManager fill:#F5A623,color:#000
    style OutboxService fill:#9013FE,color:#fff
```

## ReservationService Implementation

```mermaid
graph TD
    A["ReservationService.reserve(request)"]
    B["Check idempotencyKey<br/>findByIdempotencyKey()"]
    C{{"Existing<br/>reservation?"}}
    D["Return existing reservation<br/>(idempotent)"]
    E["Sort items by productId<br/>(prevent deadlocks)"]
    F["For each item:<br/>lockStockItem(productId, storeId)"]
    G["PESSIMISTIC_WRITE lock<br/>SELECT ... FOR UPDATE"]
    H["Calculate available<br/>= on_hand - reserved"]
    I{{"available >=<br/>requested qty?"}}
    J["❌ InsufficientStockException<br/>(productId, available, requested)"]
    K["Increment reserved<br/>stock.reserved += qty"]
    L["Create Reservation entity<br/>status = PENDING"]
    M["Set expiresAt<br/>= now + TTL (default 15min)"]
    N["Create ReservationLineItems"]
    O["Save reservation"]
    P["Publish StockReserved event<br/>to outbox"]
    Q["Check low stock threshold"]
    R{{"available <=<br/>threshold?"}}
    S["Publish LowStockAlert event"]
    T["Return ReserveResponse<br/>(reservationId, expiresAt)"]

    A --> B --> C
    C -->|Yes| D
    C -->|No| E
    E --> F --> G --> H --> I
    I -->|No| J
    I -->|Yes| K
    K --> L --> M --> N --> O --> P --> Q --> R
    R -->|Yes| S --> T
    R -->|No| T

    style A fill:#4A90E2,color:#fff
    style G fill:#F5A623,color:#000
    style J fill:#FF6B6B,color:#fff
    style P fill:#9013FE,color:#fff
    style T fill:#7ED321,color:#000
```

## InventoryService Stock Adjustment

```mermaid
graph TD
    A["InventoryService.adjustStock(request)"]
    B["lockStockItem(productId, storeId)<br/>PESSIMISTIC_WRITE"]
    C["Resolve actorId<br/>from SecurityContext"]
    D["Calculate new on_hand<br/>= current + delta"]
    E{{"newOnHand < 0?"}}
    F["❌ InvalidStockAdjustmentException<br/>Resulting stock cannot be negative"]
    G{{"reserved ><br/>newOnHand?"}}
    H["❌ InvalidStockAdjustmentException<br/>Cannot reduce below reserved"]
    I["Update stock.onHand"]
    J["Create StockAdjustmentLog<br/>(delta, reason, referenceId)"]
    K["Save stock + log"]
    L["Audit log<br/>STOCK_ADJUSTED"]
    M["Publish StockAdjusted event"]
    N{{"delta < 0?"}}
    O["Check low stock threshold"]
    P["Return StockCheckItemResponse"]

    A --> B --> C --> D --> E
    E -->|Yes| F
    E -->|No| G
    G -->|Yes| H
    G -->|No| I
    I --> J --> K --> L --> M --> N
    N -->|Yes| O --> P
    N -->|No| P

    style A fill:#4A90E2,color:#fff
    style B fill:#F5A623,color:#000
    style F fill:#FF6B6B,color:#fff
    style H fill:#FF6B6B,color:#fff
    style M fill:#9013FE,color:#fff
    style P fill:#7ED321,color:#000
```

## Pessimistic Locking Strategy

```mermaid
graph TB
    Request1["Request 1<br/>Reserve Product A"]
    Request2["Request 2<br/>Reserve Product A"]

    subgraph DB["PostgreSQL"]
        Lock["PESSIMISTIC_WRITE<br/>SELECT ... FOR UPDATE<br/>NOWAIT / timeout 5000ms"]
        StockRow["stock_items row<br/>product_id = A, store_id = X"]
    end

    subgraph Ordering["Deadlock Prevention"]
        Sort["Sort items by productId<br/>before locking"]
        Sequential["Lock items in<br/>consistent order"]
    end

    Request1 -->|1. Acquire lock| Lock
    Request2 -->|2. Wait for lock| Lock
    Lock -->|Locked| StockRow
    StockRow -->|3. Modify| Request1
    Request1 -->|4. Commit & release| StockRow
    StockRow -->|5. Lock acquired| Request2

    Sort --> Sequential
    Sequential --> Lock

    LockNote["Lock timeout: 5000ms<br/>Prevents indefinite waits<br/>Throws LockTimeoutException"]
    Lock -.-> LockNote

    style Lock fill:#F5A623,color:#000
    style StockRow fill:#336791,color:#fff
    style Sort fill:#7ED321,color:#000
```

## Batch Adjustment Processing

```mermaid
graph TD
    A["adjustStockBatch(request)"]
    B["Sort items by productId<br/>(consistent lock order)"]
    C["Detect duplicates<br/>in batch"]
    D{{"Duplicates<br/>found?"}}
    E["❌ InvalidStockAdjustmentException<br/>Duplicate productId in batch"]
    F["For each item (sorted):<br/>Lock stock row"]
    G["Validate all items:<br/>- on_hand + delta >= 0<br/>- reserved <= newOnHand"]
    H{{"All<br/>valid?"}}
    I["❌ Roll back entire batch"]
    J["Apply all changes<br/>in single transaction"]
    K["Save all stocks<br/>saveAll(lockedStock.values())"]
    L["Save all adjustment logs<br/>saveAll(logs)"]
    M["Audit log<br/>STOCK_ADJUSTED_BATCH"]
    N["For each item:<br/>Publish StockAdjusted event"]
    O["Check low stock<br/>for decreased items"]
    P["Return StockCheckResponse<br/>(all item results)"]

    A --> B --> C --> D
    D -->|Yes| E
    D -->|No| F
    F --> G --> H
    H -->|No| I
    H -->|Yes| J
    J --> K --> L --> M --> N --> O --> P

    style A fill:#4A90E2,color:#fff
    style B fill:#7ED321,color:#000
    style E fill:#FF6B6B,color:#fff
    style I fill:#FF6B6B,color:#fff
    style J fill:#336791,color:#fff
    style P fill:#7ED321,color:#000
```

## OutboxService Event Publishing

```mermaid
graph TB
    Service["ReservationService<br/>or InventoryService"]
    OutboxService["OutboxService"]
    OutboxTable["outbox_events table"]
    Transaction["Same DB Transaction"]
    Debezium["Debezium CDC"]
    Kafka["Kafka"]

    subgraph EventPayload["Event Payload Structure"]
        EventId["event_id: UUID"]
        EventType["event_type: StockReserved"]
        AggregateType["aggregate_type: Reservation"]
        AggregateId["aggregate_id: reservation_id"]
        Payload["payload: JSON<br/>{items, reservedAt, expiresAt}"]
        CreatedAt["created_at: timestamp"]
    end

    Service -->|1. publish()| OutboxService
    OutboxService -->|2. Create OutboxEvent| OutboxTable
    Service -->|3. Commit| Transaction
    Transaction --> OutboxTable

    OutboxTable -->|4. CDC capture| Debezium
    Debezium -->|5. Transform & publish| Kafka

    OutboxService --> EventPayload

    style OutboxService fill:#9013FE,color:#fff
    style Transaction fill:#336791,color:#fff
    style Kafka fill:#000000,color:#fff
```

## Rate Limiting Implementation

```mermaid
graph TD
    Request["HTTP Request"]
    Controller["StockController.check()"]
    RateLimitService["RateLimitService"]
    ExtractIP["Extract client IP<br/>from X-Forwarded-For"]
    CaffeineCache["Caffeine Cache<br/>(In-memory, 1min window)"]
    TryAcquire["tryAcquire(clientIp)"]

    CheckLimit{{"Requests in window<br/>>= limit?"}}
    Allowed["✅ Request allowed<br/>Increment counter"]
    Denied["❌ Rate limit exceeded<br/>429 Too Many Requests"]
    Continue["Continue to<br/>inventoryService"]

    Request --> Controller
    Controller --> RateLimitService
    RateLimitService --> ExtractIP
    ExtractIP --> TryAcquire
    TryAcquire --> CaffeineCache
    CaffeineCache --> CheckLimit
    CheckLimit -->|No| Allowed
    CheckLimit -->|Yes| Denied
    Allowed --> Continue

    ConfigNote["Default: 100 requests/min<br/>Per client IP<br/>Caffeine expireAfterWrite: 1min"]
    CaffeineCache -.-> ConfigNote

    style RateLimitService fill:#F5A623,color:#000
    style Allowed fill:#7ED321,color:#000
    style Denied fill:#FF6B6B,color:#fff
```

## SLO: P99 Latency Target <100ms (Reserve)

```
┌─────────────────────────────────────────────────────────────┐
│  Inventory Reserve Request Timeline (P99 target: <100ms)    │
├─────────────────────────────────────────────────────────────┤
│ Request parsing & validation:          ~2ms                 │
│ JWT Authentication:                    ~5ms                 │
│ Rate limit check (Caffeine):           ~1ms                 │
│ Subtotal (overhead):                   ~8ms                 │
│                                                             │
│ Idempotency key lookup:                ~3ms                 │
│ Sort items by productId:               ~0.1ms               │
│ Lock stock rows (PESSIMISTIC_WRITE):   ~15ms (p99)          │
│   - Per row: ~5ms                                           │
│   - Lock contention adds latency                            │
│                                                             │
│ Availability check (per item):         ~1ms                 │
│ Update reserved counts:                ~2ms                 │
│ Create reservation + line items:       ~3ms                 │
│ Outbox event write:                    ~2ms                 │
│ Low stock check:                       ~1ms                 │
│ Transaction commit:                    ~5ms                 │
│                                                             │
│ Response serialization:                ~2ms                 │
│                                                             │
│ TOTAL P99:                             ~42ms                │
│ BUFFER (100ms target):                 ~58ms                │
│ STATUS:                                ✅ WITHIN SLO        │
└─────────────────────────────────────────────────────────────┘
```

## Error Handling & Resilience

```mermaid
graph TD
    E1["InsufficientStockException"]
    E1_Response["Return 409 Conflict<br/>{productId, available, requested}"]

    E2["ReservationNotFoundException"]
    E2_Response["Return 404 Not Found<br/>{reservationId}"]

    E3["ReservationExpiredException"]
    E3_Response["Return 410 Gone<br/>{reservationId, expiredAt}"]

    E4["ReservationStateException"]
    E4_Response["Return 409 Conflict<br/>{reservationId, currentStatus, action}"]

    E5["ProductNotFoundException"]
    E5_Response["Return 404 Not Found<br/>{productId, storeId}"]

    E6["InvalidStockAdjustmentException"]
    E6_Response["Return 400 Bad Request<br/>{message}"]

    E7["LockTimeoutException"]
    E7_Retry["Retry with backoff<br/>or return 503"]

    E1 --> E1_Response
    E2 --> E2_Response
    E3 --> E3_Response
    E4 --> E4_Response
    E5 --> E5_Response
    E6 --> E6_Response
    E7 --> E7_Retry

    GlobalHandler["GlobalExceptionHandler<br/>@ControllerAdvice"]
    GlobalHandler --> E1
    GlobalHandler --> E2
    GlobalHandler --> E3
    GlobalHandler --> E4
    GlobalHandler --> E5
    GlobalHandler --> E6
    GlobalHandler --> E7

    style E1_Response fill:#F5A623,color:#000
    style E2_Response fill:#FF6B6B,color:#fff
    style E3_Response fill:#FF6B6B,color:#fff
    style E4_Response fill:#F5A623,color:#000
    style E5_Response fill:#FF6B6B,color:#fff
    style E6_Response fill:#FF6B6B,color:#fff
    style E7_Retry fill:#F5A623,color:#000
```
