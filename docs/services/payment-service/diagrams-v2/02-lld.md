# Payment Service - Low-Level Design

## Component Architecture

```mermaid
graph TB
    HTTPRequest["🌐 HTTP Request<br/>(POST /payments/authorize)"]

    DispatcherServlet["DispatcherServlet<br/>(Spring MVC)"]
    JwtFilter["🔐 JwtAuthenticationFilter<br/>(JWT RS256 validation)"]
    CorrelationFilter["📋 CorrelationIdFilter<br/>(X-Correlation-Id)"]

    SecurityContext["🛡️ SecurityContext<br/>(set principal + authorities)"]

    PaymentController["💳 PaymentController<br/>(@RestController)"]
    RefundController["↩️ RefundController<br/>(@RestController)"]
    WebhookController["📨 WebhookController<br/>(@RestController)"]

    PaymentService["PaymentService<br/>(Business Logic)"]
    RefundService["RefundService<br/>(Refund Processing)"]
    LedgerService["LedgerService<br/>(Double-entry Ledger)"]
    OutboxService["OutboxService<br/>(Event Publishing)"]
    AuditLogService["AuditLogService<br/>(Audit Trail)"]

    PaymentGateway["PaymentGateway<br/>(Interface)"]
    ResilientDecorator["ResilientPaymentGatewayDecorator<br/>(Retry + Circuit Breaker)"]
    StripeGateway["StripePaymentGateway<br/>(Stripe SDK)"]

    PaymentRepository["PaymentRepository<br/>(JPA)"]
    RefundRepository["RefundRepository<br/>(JPA)"]
    LedgerEntryRepository["LedgerEntryRepository<br/>(JPA)"]
    OutboxEventRepository["OutboxEventRepository<br/>(JPA)"]
    AuditLogRepository["AuditLogRepository<br/>(JPA)"]

    PostgreSQL["🐘 PostgreSQL<br/>(payments, refunds, ledger_entries,<br/>outbox_events, audit_logs)"]

    Redis["⚡ Redis<br/>(Idempotency Cache)"]

    StripeAPI["💰 Stripe API"]

    Response["✅ 200 OK<br/>or ❌ 400/402/409/500"]

    HTTPRequest --> DispatcherServlet
    DispatcherServlet --> CorrelationFilter
    CorrelationFilter --> JwtFilter
    JwtFilter --> SecurityContext

    SecurityContext --> PaymentController
    SecurityContext --> RefundController
    SecurityContext --> WebhookController

    PaymentController --> PaymentService
    RefundController --> RefundService
    WebhookController --> PaymentService

    PaymentService --> LedgerService
    PaymentService --> OutboxService
    PaymentService --> AuditLogService
    RefundService --> LedgerService
    RefundService --> OutboxService

    PaymentService --> PaymentGateway
    RefundService --> PaymentGateway
    PaymentGateway --> ResilientDecorator
    ResilientDecorator --> StripeGateway
    StripeGateway --> StripeAPI

    PaymentService --> PaymentRepository
    RefundService --> RefundRepository
    LedgerService --> LedgerEntryRepository
    OutboxService --> OutboxEventRepository
    AuditLogService --> AuditLogRepository

    PaymentRepository --> PostgreSQL
    RefundRepository --> PostgreSQL
    LedgerEntryRepository --> PostgreSQL
    OutboxEventRepository --> PostgreSQL

    PaymentService --> Redis

    PaymentService --> Response
    RefundService --> Response

    style JwtFilter fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style PaymentService fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style LedgerService fill:#9013FE,color:#fff,stroke:#333,stroke-width:2px
    style StripeGateway fill:#635BFF,color:#fff
    style ResilientDecorator fill:#FF6B6B,color:#fff
```

## PaymentService Implementation

```mermaid
graph TD
    A["authorize(request)"]
    B["Check idempotency key<br/>(Redis + DB)"]
    C{"Duplicate<br/>request?"}
    D["Return existing payment"]
    E["Validate currency<br/>(supported currencies)"]
    F["Create Payment entity<br/>status: AUTHORIZE_PENDING"]
    G["Save to PostgreSQL<br/>(transactional)"]
    H["Call gateway.authorize()"]
    I{"Gateway<br/>success?"}
    J["Update status: AUTHORIZED<br/>Set pspReference"]
    K["Update status: FAILED<br/>Set error details"]
    L["Record ledger entries<br/>(DEBIT customer, CREDIT merchant)"]
    M["Create outbox event<br/>(PaymentAuthorized/Failed)"]
    N["Create audit log entry"]
    O["Return PaymentResponse"]

    A --> B
    B --> C
    C -->|Yes| D
    C -->|No| E
    E --> F
    F --> G
    G --> H
    H --> I
    I -->|Yes| J
    I -->|No| K
    J --> L
    K --> L
    L --> M
    M --> N
    N --> O
    D --> O

    style J fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style K fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style L fill:#9013FE,color:#fff
    style M fill:#F5A623,color:#000
```

## ResilientPaymentGatewayDecorator

```mermaid
graph TD
    Request["Gateway Request<br/>(authorize/capture/refund)"]

    Resilience4j["Resilience4j Decorators"]

    CircuitBreaker["🔴 Circuit Breaker<br/>(failureRateThreshold: 50%<br/>waitDurationInOpenState: 60s<br/>slidingWindowSize: 10)"]

    Retry["🔄 Retry<br/>(maxAttempts: 3<br/>waitDuration: 500ms<br/>exponentialBackoff: 2)"]

    RateLimiter["⏱️ Rate Limiter<br/>(limitForPeriod: 100<br/>limitRefreshPeriod: 1s)"]

    Timeout["⏰ Timeout<br/>(timeoutDuration: 10s)"]

    Delegate["StripePaymentGateway<br/>(actual call)"]

    Success["✅ Return result"]
    Fallback["❌ Throw exception<br/>or return cached result"]

    Request --> Resilience4j
    Resilience4j --> CircuitBreaker
    CircuitBreaker --> Retry
    Retry --> RateLimiter
    RateLimiter --> Timeout
    Timeout --> Delegate
    Delegate --> Success

    CircuitBreaker -->|Circuit OPEN| Fallback
    Retry -->|Retries exhausted| Fallback
    Timeout -->|Timeout exceeded| Fallback

    style CircuitBreaker fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style Retry fill:#F5A623,color:#000
    style Delegate fill:#635BFF,color:#fff
```

## LedgerService Double-Entry Bookkeeping

```mermaid
graph TB
    Operation["Payment Operation<br/>(Authorize/Capture/Refund)"]

    subgraph Authorization["Authorization (Hold Funds)"]
        A1["DEBIT: customer_receivables<br/>amount: authorized_cents"]
        A2["CREDIT: merchant_payables<br/>amount: authorized_cents"]
    end

    subgraph Capture["Capture (Settle Funds)"]
        C1["DEBIT: merchant_payables<br/>amount: captured_cents"]
        C2["CREDIT: merchant_revenue<br/>amount: captured_cents"]
    end

    subgraph Void["Void (Release Hold)"]
        V1["CREDIT: customer_receivables<br/>amount: authorized_cents"]
        V2["DEBIT: merchant_payables<br/>amount: authorized_cents"]
    end

    subgraph RefundOp["Refund (Return Funds)"]
        R1["DEBIT: merchant_revenue<br/>amount: refund_cents"]
        R2["CREDIT: customer_receivables<br/>amount: refund_cents"]
    end

    Invariant["📊 Invariant Check:<br/>SUM(DEBIT) = SUM(CREDIT)<br/>for each payment_id"]

    Operation --> Authorization
    Operation --> Capture
    Operation --> Void
    Operation --> RefundOp

    Authorization --> Invariant
    Capture --> Invariant
    Void --> Invariant
    RefundOp --> Invariant

    style A1 fill:#FF6B6B,color:#fff
    style A2 fill:#7ED321,color:#000
    style C1 fill:#FF6B6B,color:#fff
    style C2 fill:#7ED321,color:#000
    style Invariant fill:#9013FE,color:#fff,stroke:#333,stroke-width:2px
```

## WebhookEventProcessor

```mermaid
graph TD
    Webhook["Stripe Webhook<br/>POST /webhooks/stripe"]

    VerifySignature["WebhookSignatureVerifier<br/>Verify Stripe-Signature header"]

    SigValid{"Signature<br/>valid?"}

    Reject401["Return 401 Unauthorized"]

    ParseEvent["Parse event JSON<br/>Extract event_type"]

    CheckIdempotency["Check ProcessedWebhookEvent<br/>(deduplication)"]

    AlreadyProcessed{"Already<br/>processed?"}

    Return200["Return 200 OK<br/>(idempotent)"]

    RouteHandler["WebhookEventHandler<br/>Route by event_type"]

    subgraph Handlers["Event Handlers"]
        PaymentIntentSucceeded["payment_intent.succeeded<br/>→ Confirm authorization"]
        PaymentIntentFailed["payment_intent.payment_failed<br/>→ Mark as FAILED"]
        ChargeRefunded["charge.refunded<br/>→ Update refund status"]
        ChargeDisputed["charge.dispute.created<br/>→ Mark as DISPUTED"]
        RefundUpdated["refund.updated<br/>→ Update refund status"]
    end

    PersistIdempotency["Save ProcessedWebhookEvent<br/>(event_id, processed_at)"]

    TransitionState["Transition Payment/Refund state<br/>Update ledger entries"]

    PublishEvent["Publish outbox event<br/>(Kafka)"]

    Success["Return 200 OK"]

    Webhook --> VerifySignature
    VerifySignature --> SigValid
    SigValid -->|No| Reject401
    SigValid -->|Yes| ParseEvent
    ParseEvent --> CheckIdempotency
    CheckIdempotency --> AlreadyProcessed
    AlreadyProcessed -->|Yes| Return200
    AlreadyProcessed -->|No| RouteHandler
    RouteHandler --> Handlers
    Handlers --> PersistIdempotency
    PersistIdempotency --> TransitionState
    TransitionState --> PublishEvent
    PublishEvent --> Success

    style VerifySignature fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style RouteHandler fill:#4A90E2,color:#fff
    style TransitionState fill:#F5A623,color:#000
```

## Scheduled Jobs

```mermaid
graph TB
    subgraph Jobs["Scheduled Jobs (ShedLock)"]
        StalePending["StalePendingPaymentRecoveryJob<br/>@Scheduled(fixedDelay=5min)<br/>Find PENDING > 30min, sync with PSP"]
        StaleRefund["StalePendingRefundRecoveryJob<br/>@Scheduled(fixedDelay=5min)<br/>Find PENDING refunds, sync status"]
        LedgerVerify["LedgerIntegrityVerificationJob<br/>@Scheduled(cron='0 0 * * * *')<br/>Verify DEBIT=CREDIT invariant"]
        OutboxCleanup["OutboxCleanupJob<br/>@Scheduled(fixedDelay=1hr)<br/>Delete sent events > 7 days"]
        AuditCleanup["AuditLogCleanupJob<br/>@Scheduled(cron='0 0 3 * * *')<br/>Archive logs > 90 days"]
    end

    ShedLock["🔒 ShedLock<br/>(PostgreSQL lock table)"]

    subgraph Actions["Recovery Actions"]
        QueryPSP["Query Stripe API<br/>for actual status"]
        UpdateLocal["Update local state<br/>to match PSP"]
        AlertOnMismatch["Alert if ledger<br/>imbalance detected"]
    end

    StalePending --> ShedLock
    StaleRefund --> ShedLock
    LedgerVerify --> ShedLock
    OutboxCleanup --> ShedLock
    AuditCleanup --> ShedLock

    StalePending --> QueryPSP
    StaleRefund --> QueryPSP
    QueryPSP --> UpdateLocal
    LedgerVerify --> AlertOnMismatch

    style ShedLock fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style LedgerVerify fill:#9013FE,color:#fff
    style AlertOnMismatch fill:#F5A623,color:#000
```

## Repository Layer

```mermaid
graph TB
    subgraph Repositories["JPA Repositories"]
        PaymentRepo["PaymentRepository<br/>extends JpaRepository"]
        RefundRepo["RefundRepository<br/>extends JpaRepository"]
        LedgerRepo["LedgerEntryRepository<br/>extends JpaRepository"]
        OutboxRepo["OutboxEventRepository<br/>extends JpaRepository"]
        AuditRepo["AuditLogRepository<br/>extends JpaRepository"]
        WebhookRepo["ProcessedWebhookEventRepository<br/>extends JpaRepository"]
    end

    subgraph CustomQueries["Custom Query Methods"]
        FindByOrderId["findByOrderId(UUID orderId)"]
        FindByIdempotencyKey["findByIdempotencyKey(String key)"]
        FindByPaymentId["findByPaymentId(UUID paymentId)"]
        FindUnsent["findBySentFalseOrderByCreatedAtAsc()"]
        FindStale["findByStatusAndCreatedAtBefore(status, threshold)"]
        SumByPaymentId["sumAmountCentsByPaymentIdAndEntryType()"]
    end

    PaymentRepo --> FindByOrderId
    PaymentRepo --> FindByIdempotencyKey
    RefundRepo --> FindByPaymentId
    LedgerRepo --> SumByPaymentId
    OutboxRepo --> FindUnsent
    PaymentRepo --> FindStale

    style PaymentRepo fill:#4A90E2,color:#fff
    style LedgerRepo fill:#9013FE,color:#fff
```

## SLO: P99 Latency Target <500ms

```
┌─────────────────────────────────────────────────────────────┐
│  Payment Authorization Timeline (P99 target: <500ms)        │
├─────────────────────────────────────────────────────────────┤
│ JWT Validation:                        ~15ms                │
│ Idempotency Check (Redis):             ~5ms                 │
│ Payment Entity Creation:               ~2ms                 │
│ Subtotal (Pre-PSP):                    ~22ms                │
│                                                             │
│ Stripe API Call (authorize):           ~300-400ms (p99)     │
│   - Network latency to Stripe:         ~50ms                │
│   - Stripe processing:                 ~250-350ms           │
│                                                             │
│ Database Write (payment + ledger):     ~30ms                │
│ Outbox Event Write:                    ~10ms                │
│ Response Serialization:                ~5ms                 │
│                                                             │
│ TOTAL P99:                             ~467ms               │
│ BUFFER (500ms target):                 ~33ms                │
│ STATUS:                                ✅ WITHIN SLO        │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Capture/Void Timeline (P99 target: <300ms)                 │
├─────────────────────────────────────────────────────────────┤
│ JWT Validation:                        ~15ms                │
│ Payment Lookup:                        ~10ms                │
│ Stripe API Call:                       ~200-250ms (p99)     │
│ Database Update + Ledger:              ~30ms                │
│ TOTAL P99:                             ~305ms               │
│ STATUS:                                ⚠️ NEAR SLO          │
└─────────────────────────────────────────────────────────────┘
```

## Error Handling & Resilience

```mermaid
graph TD
    A["Stripe API Error"]
    B["Network Timeout"]
    C["Database Error"]
    D["Idempotency Conflict"]
    E["Invalid State Transition"]

    A --> A1["PaymentGatewayException<br/>(card_declined, insufficient_funds)"]
    A1 --> A2["Map to PaymentDeclinedException"]
    A2 --> A3["Return 402 Payment Required"]
    A3 --> A4["Include decline_code in response"]

    B --> B1["PaymentGatewayTransientException"]
    B1 --> B2["Retry with exponential backoff<br/>(max 3 attempts)"]
    B2 --> B3{"Retry<br/>success?"}
    B3 -->|Yes| B4["Continue normal flow"]
    B3 -->|No| B5["Set status: FAILED<br/>Return 503 Service Unavailable"]

    C --> C1["DataAccessException"]
    C1 --> C2["Transaction rollback"]
    C2 --> C3["Log error with correlation_id"]
    C3 --> C4["Return 500 Internal Server Error"]

    D --> D1["DuplicatePaymentException"]
    D1 --> D2["Return existing payment<br/>(idempotent)"]
    D2 --> D3["Return 200 OK"]

    E --> E1["PaymentInvalidStateException"]
    E1 --> E2["Log state violation"]
    E2 --> E3["Return 409 Conflict<br/>Include current_state, attempted_action"]

    style A3 fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style B5 fill:#FF6B6B,color:#fff
    style C4 fill:#FF6B6B,color:#fff
    style D3 fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style E3 fill:#F5A623,color:#000
```
