# Order Service - Low-Level Design

## Component Architecture

```mermaid
graph TB
    HTTPRequest["🌐 HTTP Request<br/>(JWT in Authorization header)"]

    DispatcherServlet["DispatcherServlet<br/>(Spring MVC)"]
    JwtFilter["🔐 JwtAuthenticationFilter<br/>(validates JWT)"]
    SecurityContext["🛡️ SecurityContext<br/>(principal + authorities)"]

    RateLimitService["⏱️ RateLimitService<br/>(per-user throttling)"]

    subgraph controllers["Controllers"]
        OrderController["📋 OrderController<br/>(@RestController /orders)"]
        AdminController["🔧 AdminOrderController<br/>(@RestController /admin/orders)"]
        WorkflowController["🎯 WorkflowController<br/>(Temporal activities)"]
    end

    subgraph services["Service Layer"]
        OrderService["📦 OrderService<br/>(Business logic)"]
        OutboxService["📤 OutboxService<br/>(Event publishing)"]
        AuditLogService["📝 AuditLogService<br/>(Audit trail)"]
        UserErasureService["🗑️ UserErasureService<br/>(GDPR compliance)"]
    end

    subgraph domain["Domain Layer"]
        OrderStateMachine["🔄 OrderStateMachine<br/>(State transitions)"]
        OrderMapper["🔀 OrderMapper<br/>(DTO ↔ Entity)"]
    end

    subgraph repositories["Repository Layer"]
        OrderRepository["🗄️ OrderRepository<br/>(JPA)"]
        OrderItemRepository["📦 OrderItemRepository"]
        StatusHistoryRepository["📜 StatusHistoryRepository"]
    end

    subgraph clients["External Clients"]
        PricingQuoteClient["💰 PricingQuoteClient<br/>(Quote validation)"]
    end

    PostgreSQL["🗄️ PostgreSQL"]

    HTTPRequest --> DispatcherServlet
    DispatcherServlet --> JwtFilter
    JwtFilter --> SecurityContext
    SecurityContext --> RateLimitService

    RateLimitService --> OrderController
    RateLimitService --> AdminController
    RateLimitService --> WorkflowController

    OrderController --> OrderService
    AdminController --> OrderService
    WorkflowController --> OrderService

    OrderService --> OrderStateMachine
    OrderService --> OrderMapper
    OrderService --> OutboxService
    OrderService --> AuditLogService
    OrderService --> PricingQuoteClient

    OrderService --> OrderRepository
    OrderService --> OrderItemRepository
    OrderService --> StatusHistoryRepository

    OrderRepository --> PostgreSQL
    OutboxService --> PostgreSQL

    style OrderService fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style OrderStateMachine fill:#9013FE,color:#fff
    style OutboxService fill:#50E3C2,color:#000
```

## OrderService Implementation

```mermaid
graph TD
    A["OrderService<br/>(Spring @Service)"]

    subgraph create["createOrder()"]
        C1["Check idempotency key<br/>(duplicate prevention)"]
        C2["Validate pricing quote<br/>(PricingQuoteClient)"]
        C3["Create Order entity"]
        C4["Create OrderItem entities"]
        C5["Save to PostgreSQL<br/>(transactional)"]
        C6["Record status history<br/>(PENDING)"]
        C7["Publish OrderCreated<br/>(via OutboxService)"]
    end

    subgraph update["updateOrderStatus()"]
        U1["Fetch order by ID"]
        U2["Validate state transition<br/>(OrderStateMachine)"]
        U3["Update status"]
        U4["Record status history"]
        U5["Log audit entry"]
        U6["Publish OrderStatusChanged"]
        U7["If PLACED: publish OrderPlaced"]
    end

    subgraph cancel["cancelOrder()"]
        X1["Fetch order"]
        X2["Validate cancellable state"]
        X3["Set CANCELLED + reason"]
        X4["Record status history"]
        X5["Log audit entry"]
        X6["Publish OrderCancelled<br/>(triggers refund)"]
    end

    A --> C1 --> C2 --> C3 --> C4 --> C5 --> C6 --> C7
    A --> U1 --> U2 --> U3 --> U4 --> U5 --> U6 --> U7
    A --> X1 --> X2 --> X3 --> X4 --> X5 --> X6

    style A fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style C7 fill:#50E3C2,color:#000
    style U6 fill:#50E3C2,color:#000
    style X6 fill:#FF6B6B,color:#fff
```

## OrderStateMachine Transitions

```mermaid
graph TD
    A["OrderStateMachine.validate(from, to)"]

    B["Get allowed transitions<br/>from TRANSITIONS map"]

    C{"to ∈<br/>allowed?"}

    D["✅ Transition allowed<br/>(return void)"]
    E["❌ InvalidOrderStateException<br/>Cannot transition from X to Y"]

    A --> B --> C
    C -->|Yes| D
    C -->|No| E

    style D fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style E fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
```

## Domain Model

```mermaid
classDiagram
    class Order {
        +UUID id
        +UUID userId
        +String storeId
        +OrderStatus status
        +long subtotalCents
        +long discountCents
        +long totalCents
        +String currency
        +String couponCode
        +UUID reservationId
        +UUID paymentId
        +String idempotencyKey
        +String cancellationReason
        +String deliveryAddress
        +UUID quoteId
        +Instant createdAt
        +Instant updatedAt
        +long version
        +List~OrderItem~ items
    }

    class OrderItem {
        +UUID id
        +Order order
        +UUID productId
        +String productName
        +String productSku
        +int quantity
        +long unitPriceCents
        +long lineTotalCents
        +String pickedStatus
    }

    class OrderStatusHistory {
        +Long id
        +Order order
        +OrderStatus fromStatus
        +OrderStatus toStatus
        +String changedBy
        +String note
        +Instant createdAt
    }

    class OrderStatus {
        <<enumeration>>
        PENDING
        PLACED
        PACKING
        PACKED
        OUT_FOR_DELIVERY
        DELIVERED
        CANCELLED
        FAILED
    }

    class OutboxEvent {
        +UUID id
        +String aggregateType
        +String aggregateId
        +String eventType
        +String payload
        +Instant createdAt
        +boolean published
    }

    class AuditLog {
        +UUID id
        +UUID actorId
        +String action
        +String resourceType
        +String resourceId
        +String details
        +String ipAddress
        +Instant createdAt
    }

    Order "1" --> "*" OrderItem : contains
    Order "1" --> "*" OrderStatusHistory : tracks
    Order --> OrderStatus : has
    OrderStatusHistory --> OrderStatus : from/to
```

## OutboxService Implementation

```mermaid
graph TD
    A["OutboxService.publish<br/>(aggregateType, aggregateId,<br/>eventType, payload)"]

    B["Create OutboxEvent entity"]
    C["Serialize payload to JSON"]
    D["Set envelope fields:<br/>- event_id (UUID)<br/>- event_type<br/>- aggregate_id<br/>- schema_version: 1<br/>- source_service: order-service<br/>- correlation_id<br/>- timestamp"]
    E["Save to outbox table<br/>(same transaction as order)"]
    F["Debezium CDC picks up<br/>new outbox row"]
    G["Publish to Kafka topic<br/>(order.events)"]
    H["Outbox row marked published"]

    A --> B --> C --> D --> E
    E --> F --> G --> H

    note1["Transactional Outbox Pattern<br/>Ensures exactly-once delivery"]
    E -.-> note1

    style A fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style E fill:#336791,color:#fff
    style G fill:#50E3C2,color:#000
```

## Request Processing Pipeline

```mermaid
graph LR
    Request["🌐 HTTP Request"]
    Timer["⏱️ Timer start"]

    Auth["🔐 JWT Auth<br/>~10ms"]
    AuthMetric["jwt_auth_duration_ms"]

    RateLimit["🚦 Rate Limit<br/>~2ms"]
    RateLimitMetric["rate_limit_check_duration_ms"]

    Controller["🎯 Controller<br/>~5ms"]

    Service["📦 Service Logic<br/>~50-100ms"]
    ServiceMetric["order_service_duration_ms"]

    DB["🗄️ PostgreSQL<br/>~20-50ms"]
    DBMetric["db_query_duration_ms"]

    Outbox["📤 Outbox Write<br/>~10ms"]

    Response["📤 Response<br/>~5ms"]
    ResponseMetric["http_response_time_ms"]

    Request --> Timer --> Auth
    Auth --> AuthMetric --> RateLimit
    RateLimit --> RateLimitMetric --> Controller
    Controller --> Service
    Service --> ServiceMetric --> DB
    DB --> DBMetric --> Outbox
    Outbox --> Response
    Response --> ResponseMetric

    style Timer fill:#9013FE,color:#fff
    style AuthMetric fill:#9013FE,color:#fff
    style ServiceMetric fill:#9013FE,color:#fff
    style DBMetric fill:#9013FE,color:#fff
    style ResponseMetric fill:#9013FE,color:#fff
```

## SLO: P99 Latency Target <300ms

```
┌─────────────────────────────────────────────────────────────┐
│  Order Service Request Timeline (P99 target: <300ms)        │
├─────────────────────────────────────────────────────────────┤
│ JWT Extraction & Validation:           ~10ms                │
│ Rate Limit Check:                      ~2ms                 │
│ Controller + DTO Validation:           ~5ms                 │
│ Subtotal (Gateway overhead):           ~17ms                │
│                                                             │
│ OrderService.createOrder():                                 │
│   - Idempotency check:                 ~10ms                │
│   - Quote validation (REST call):      ~50ms p99            │
│   - Entity creation + mapping:         ~5ms                 │
│   - PostgreSQL transaction:            ~40ms p99            │
│   - Outbox event write:                ~10ms                │
│   - Subtotal:                          ~115ms               │
│                                                             │
│ Response Serialization:                ~10ms                │
│ Network I/O:                           ~20ms                │
│                                                             │
│ TOTAL P99:                             ~162ms               │
│ BUFFER (300ms target):                 ~138ms               │
│ STATUS:                                ✅ WITHIN SLO        │
└─────────────────────────────────────────────────────────────┘
```

## Error Handling & Resilience

```mermaid
graph TD
    A["Order Creation Failure"]
    B["Pricing Quote Timeout"]
    C["PostgreSQL Failure"]
    D["Outbox Write Failure"]
    E["Invalid State Transition"]

    A --> A1["Log: ORDER_CREATION_FAILED"]
    A1 --> A2["Return 500 with error details"]
    A2 --> A3["Client can retry<br/>(idempotency key)"]

    B --> B1["Circuit breaker OPEN<br/>for pricing-service"]
    B1 --> B2["Retry with backoff<br/>(max 3 attempts)"]
    B2 --> B3["If exhausted: reject order<br/>(quote required)"]

    C --> C1["Transaction rollback"]
    C1 --> C2["No partial state"]
    C2 --> C3["Return 503<br/>Service Unavailable"]

    D --> D1["Included in same<br/>transaction as order"]
    D1 --> D2["If outbox fails,<br/>order fails too"]
    D2 --> D3["Atomic guarantee"]

    E --> E1["Log: INVALID_STATE_TRANSITION"]
    E1 --> E2["Return 409 Conflict"]
    E2 --> E3["Include current state<br/>in response"]

    style A3 fill:#7ED321,color:#000
    style B3 fill:#FF6B6B,color:#fff
    style C3 fill:#FF6B6B,color:#fff
    style D3 fill:#7ED321,color:#000
    style E3 fill:#F5A623,color:#000
```
