# Checkout Orchestrator - End-to-End Flows

## Complete Cart-to-Order-Confirmed Flow (E2E)

```mermaid
graph TB
    Customer["👤 Customer\n(Mobile App)"]
    BFF["mobile-bff-service\n:8097"]
    Identity["identity-service\n:8081\n(JWT validation)"]

    subgraph CheckoutOrchestrator["Checkout Orchestrator Service (:8089)"]
        Controller["CheckoutController"]
        IdempotencyCheck["Idempotency Check\n(PostgreSQL)"]
        WorkflowClient["Temporal WorkflowClient"]
        WorkflowExec["CheckoutWorkflowImpl\n(Saga Coordinator)"]
    end

    subgraph Temporal["Temporal Cluster"]
        TemporalServer["Temporal Server\n:7233"]
        TaskQueue["CHECKOUT_ORCHESTRATOR_TASK_QUEUE"]
    end

    subgraph TransactionalCore["Transactional Core Services"]
        CartService["cart-service :8084\n• validateCart\n• clearCart"]
        PricingService["pricing-service :8087\n• calculatePrice"]
        InventoryService["inventory-service :8083\n• reserveStock\n• confirmStock\n• releaseStock"]
        PaymentService["payment-service :8086\n• authorizePayment\n• capturePayment\n• voidPayment\n• refundPayment"]
        OrderService["order-service :8085\n• createOrder\n• cancelOrder"]
    end

    subgraph ExternalGateway["Payment Gateway"]
        PSP["Stripe / Razorpay\n(Authorization & Capture)"]
    end

    subgraph Outbox["Event Publishing (Outbox → Kafka)"]
        Debezium["Debezium CDC"]
        Kafka["Kafka Topics"]
    end

    subgraph PostCheckout["Post-Checkout Choreography"]
        NotificationService["notification-service\n(Order confirmation)"]
        FulfillmentService["fulfillment-service\n(Pick queue)"]
        RiderFleetService["rider-fleet-service\n(Pre-assignment)"]
        ReconciliationService["reconciliation-service\n(Payment reconcile)"]
    end

    Customer -->|"1. POST /checkout\n(JWT)"| BFF
    BFF -->|"2. Forward + headers"| Controller
    Controller -->|"3. Validate JWT"| Identity
    Identity -->|"4. JWT valid"| Controller

    Controller -->|"5. Check idempotency"| IdempotencyCheck
    Controller -->|"6. Start workflow"| WorkflowClient
    WorkflowClient -->|"7. gRPC"| TemporalServer
    TemporalServer -->|"8. Task"| TaskQueue
    TaskQueue -->|"9. Execute"| WorkflowExec

    WorkflowExec -->|"① validateCart"| CartService
    WorkflowExec -->|"② calculatePrice"| PricingService
    WorkflowExec -->|"③ reserveStock"| InventoryService
    WorkflowExec -->|"④ authorizePayment"| PaymentService
    PaymentService -->|"PSP API"| PSP
    WorkflowExec -->|"⑤ createOrder"| OrderService
    WorkflowExec -->|"⑥ confirmStock"| InventoryService
    WorkflowExec -->|"⑥ capturePayment"| PaymentService
    PaymentService -->|"PSP API"| PSP
    WorkflowExec -->|"⑦ clearCart"| CartService

    OrderService -->|"Outbox"| Debezium
    PaymentService -->|"Outbox"| Debezium
    InventoryService -->|"Outbox"| Debezium
    Debezium -->|"CDC"| Kafka

    Kafka -->|"OrderCreated"| NotificationService
    Kafka -->|"OrderCreated"| FulfillmentService
    Kafka -->|"OrderCreated"| RiderFleetService
    Kafka -->|"PaymentCaptured"| ReconciliationService

    WorkflowExec -->|"10. Response"| Controller
    Controller -->|"11. 200 OK"| BFF
    BFF -->|"12. Order confirmed"| Customer

    style WorkflowExec fill:#4f46e5,color:#fff,stroke:#312e81,stroke-width:2px
    style TemporalServer fill:#6366f1,color:#fff
    style PaymentService fill:#dc2626,color:#fff
    style OrderService fill:#22c55e,color:#fff
```

## Request Flow with Error Handling

```mermaid
graph TB
    Request["POST /checkout"]

    subgraph HappyPath["Happy Path (<5s p99)"]
        H1["✅ JWT validated"]
        H2["✅ Idempotency check"]
        H3["✅ Workflow started"]
        H4["✅ Cart validated"]
        H5["✅ Prices calculated"]
        H6["✅ Stock reserved"]
        H7["✅ Payment authorized"]
        H8["✅ Order created"]
        H9["✅ Confirmed + Captured"]
        H10["✅ Cart cleared"]
        Success["✅ 200 OK\n{orderId, COMPLETED}"]
    end

    subgraph ErrorPaths["Error Paths"]
        E1["❌ JWT invalid → 401"]
        E2["❌ Cart empty → FAILED"]
        E3["❌ Out of stock → FAILED\n(InsufficientStockException)"]
        E4["❌ Payment declined → FAILED\n+ releaseStock"]
        E5["❌ Order creation failed → FAILED\n+ voidPayment + releaseStock"]
        E6["❌ Capture failed → FAILED\n+ refund/void + cancelOrder + releaseStock"]
        E7["❌ Concurrent request → 409"]
    end

    Request --> JWT{{"JWT\nvalid?"}}
    JWT -->|No| E1
    JWT -->|Yes| H1

    H1 --> Idempotent{{"Cached\nresponse?"}}
    Idempotent -->|Yes| CachedResponse["Return cached (fast path)"]
    Idempotent -->|No| H2

    H2 --> Concurrent{{"Workflow\nalready running?"}}
    Concurrent -->|Yes| E7
    Concurrent -->|No| H3

    H3 --> Cart{{"Cart\nvalid?"}}
    Cart -->|No| E2
    Cart -->|Yes| H4 --> H5

    H5 --> Stock{{"Stock\navailable?"}}
    Stock -->|No| E3
    Stock -->|Yes| H6

    H6 --> Payment{{"Payment\nauthorized?"}}
    Payment -->|No| E4
    Payment -->|Yes| H7

    H7 --> Order{{"Order\ncreated?"}}
    Order -->|No| E5
    Order -->|Yes| H8

    H8 --> Capture{{"Capture\nsucceeded?"}}
    Capture -->|No| E6
    Capture -->|Yes| H9 --> H10 --> Success

    style HappyPath fill:#dcfce7,color:#000,stroke:#22c55e,stroke-width:2px
    style ErrorPaths fill:#fecaca,color:#000,stroke:#dc2626,stroke-width:2px
    style Success fill:#22c55e,color:#fff,stroke-width:3px
```

## Compensation Cascade (Worst-Case Failure)

```mermaid
graph TB
    subgraph NormalExecution["Normal Execution (Steps 1-6)"]
        S1["① Cart validated"]
        S2["② Prices calculated"]
        S3["③ Stock reserved\n(reservationId)"]
        S4["④ Payment authorized\n(paymentId)"]
        S5["⑤ Order created\n(orderId)"]
        S6["⑥ Confirm stock..."]
    end

    Failure["❌ confirmStock() fails"]

    subgraph Compensation["Compensation Cascade (LIFO)"]
        C1["compensatePayment()\n→ voidPayment (auth not captured)"]
        C2["saga.compensate()\n→ cancelOrder(orderId)"]
        C3["saga.compensate()\n→ releaseStock(reservationId)"]
    end

    Result["CheckoutResponse{FAILED}"]

    S1 --> S2 --> S3 --> S4 --> S5 --> S6
    S6 --> Failure

    Failure --> C1
    C1 --> C2
    C2 --> C3
    C3 --> Result

    style Failure fill:#dc2626,color:#fff
    style Compensation fill:#fef3c7,color:#000,stroke:#f59e0b,stroke-width:2px
    style Result fill:#dc2626,color:#fff
```

## Multi-User Concurrent Checkout

```mermaid
graph TB
    User1["👤 User 1"]
    User2["👤 User 2"]
    User3["👤 User 3"]

    Gateway["Load Balancer"]

    subgraph Replicas["Checkout Orchestrator (3 replicas)"]
        CO1["checkout-orchestrator-1"]
        CO2["checkout-orchestrator-2"]
        CO3["checkout-orchestrator-3"]
    end

    subgraph SharedInfra["Shared Infrastructure"]
        IdempotencyDB["PostgreSQL\n(idempotency keys)"]
        TemporalCluster["Temporal Cluster\n(workflow coordination)"]
    end

    subgraph Downstream["Domain Services"]
        CartSvc["cart-service"]
        InvSvc["inventory-service"]
        PaySvc["payment-service"]
        OrdSvc["order-service"]
    end

    User1 -->|"Request 1"| Gateway
    User2 -->|"Request 2"| Gateway
    User3 -->|"Request 3"| Gateway

    Gateway --> CO1
    Gateway --> CO2
    Gateway --> CO3

    CO1 --> IdempotencyDB
    CO2 --> IdempotencyDB
    CO3 --> IdempotencyDB

    CO1 --> TemporalCluster
    CO2 --> TemporalCluster
    CO3 --> TemporalCluster

    TemporalCluster --> CartSvc
    TemporalCluster --> InvSvc
    TemporalCluster --> PaySvc
    TemporalCluster --> OrdSvc

    Note1["Stateless replicas\nTemporal coordinates workflows\nPostgreSQL prevents duplicates"]

    style TemporalCluster fill:#6366f1,color:#fff
    style IdempotencyDB fill:#4f46e5,color:#fff
```

## SLO Monitoring Dashboard

```mermaid
graph LR
    CO["Checkout Orchestrator"]

    subgraph SLOs["Service Level Objectives"]
        Latency["⏱️ P99 Latency\n< 5 seconds"]
        Success["✅ Success Rate\n> 99.5%"]
        Availability["🟢 Availability\n99.9%"]
    end

    subgraph Metrics["Key Metrics"]
        M1["checkout_workflow_duration_ms\n(Histogram)"]
        M2["checkout_workflow_completed_total{status}\n(Counter)"]
        M3["checkout_activity_duration_ms{activity}\n(Histogram per step)"]
        M4["checkout_compensation_triggered_total{step}\n(Counter)"]
    end

    subgraph Alerts["Alert Rules"]
        SEV1["🔴 SEV-1\np99 > 10s OR success < 98%\n→ Page SRE"]
        SEV2["🟠 SEV-2\np99 > 7s OR success < 99%\n→ Create incident"]
        SEV3["🟡 SEV-3\np99 > 5s OR success < 99.5%\n→ Review in standup"]
    end

    CO --> SLOs
    SLOs --> Metrics
    Metrics --> Alerts

    style Latency fill:#22c55e,color:#fff
    style Success fill:#22c55e,color:#fff
    style SEV1 fill:#dc2626,color:#fff
    style SEV2 fill:#f59e0b,color:#000
```

## Resilience & Degradation Patterns

```mermaid
graph TB
    Request["Checkout Request"]

    subgraph CircuitBreakers["Circuit Breakers (per downstream)"]
        CartCB["cart-service CB"]
        PricingCB["pricing-service CB"]
        InventoryCB["inventory-service CB"]
        PaymentCB["payment-service CB"]
        OrderCB["order-service CB"]
    end

    subgraph TemporalResilience["Temporal Resilience"]
        ActivityRetries["Activity retries\n(3 attempts, exponential backoff)"]
        WorkflowTimeout["Workflow timeout\n(5 minutes)"]
        CompensationGuarantee["Compensation guarantee\n(saga pattern)"]
    end

    subgraph Fallbacks["Failure Handling"]
        NonRetryable["Non-retryable exceptions\n→ Immediate compensation"]
        Timeout["Timeout exceeded\n→ Workflow fails"]
        PSPUnavailable["PSP unavailable\n→ Retry with backoff"]
    end

    Request --> CircuitBreakers
    CircuitBreakers --> TemporalResilience
    TemporalResilience --> Fallbacks

    style TemporalResilience fill:#6366f1,color:#fff
    style CompensationGuarantee fill:#22c55e,color:#fff
```

## Post-Checkout Event Flow

```mermaid
graph TB
    subgraph CheckoutComplete["Checkout Completes"]
        SagaSuccess["Saga returns COMPLETED"]
        OrderCreated["OrderCreated event\n(order-service outbox)"]
        PaymentCaptured["PaymentCaptured event\n(payment-service outbox)"]
        StockConfirmed["StockConfirmed event\n(inventory-service outbox)"]
    end

    subgraph CDCPipeline["CDC Pipeline"]
        Debezium["Debezium\n(PostgreSQL → Kafka)"]
    end

    subgraph KafkaTopics["Kafka Topics"]
        OrdersTopic["orders.events"]
        PaymentsTopic["payments.events"]
        InventoryTopic["inventory.events"]
    end

    subgraph Consumers["Event Consumers"]
        NotificationSvc["notification-service\n→ Send confirmation SMS/email"]
        FulfillmentSvc["fulfillment-service\n→ Create pick task"]
        RiderSvc["rider-fleet-service\n→ Pre-assign rider"]
        ReconcileSvc["reconciliation-service\n→ Log payment"]
        AnalyticsPipeline["analytics pipeline\n→ Update metrics"]
        FraudSvc["fraud-service\n→ Signal patterns"]
    end

    SagaSuccess --> OrderCreated
    SagaSuccess --> PaymentCaptured
    SagaSuccess --> StockConfirmed

    OrderCreated --> Debezium
    PaymentCaptured --> Debezium
    StockConfirmed --> Debezium

    Debezium --> OrdersTopic
    Debezium --> PaymentsTopic
    Debezium --> InventoryTopic

    OrdersTopic --> NotificationSvc
    OrdersTopic --> FulfillmentSvc
    OrdersTopic --> RiderSvc

    PaymentsTopic --> ReconcileSvc
    PaymentsTopic --> NotificationSvc
    PaymentsTopic --> FraudSvc

    InventoryTopic --> AnalyticsPipeline

    style SagaSuccess fill:#22c55e,color:#fff
    style Debezium fill:#f59e0b,color:#000
    style OrdersTopic fill:#3b82f6,color:#fff
```

## Checkout Timeline (Latency Breakdown)

```
┌─────────────────────────────────────────────────────────────────┐
│  Checkout E2E Timeline (P99 target: <5s)                        │
├─────────────────────────────────────────────────────────────────┤
│ Controller overhead:                                            │
│   JWT validation:                         ~30ms                 │
│   Idempotency DB lookup:                  ~10ms                 │
│   Temporal workflow start:                ~100ms                │
│                                                                 │
│ Saga execution:                                                 │
│   ① Cart validation:                      ~200ms p99            │
│   ② Pricing calculation:                  ~300ms p99            │
│   ③ Inventory reservation:                ~200ms p99            │
│   ④ Payment authorization (PSP):          ~1500ms p99           │
│   ⑤ Order creation:                       ~300ms p99            │
│   ⑥ Stock confirm + Payment capture:      ~800ms p99            │
│   ⑦ Cart clear (best effort):             ~100ms                │
│                                                                 │
│ Response handling:                                              │
│   Temporal result propagation:            ~50ms                 │
│   Idempotency key persistence:            ~20ms                 │
│   HTTP response serialization:            ~10ms                 │
│                                                                 │
│ TOTAL P99:                                ~3620ms               │
│ BUDGET REMAINING:                         ~1380ms               │
│ STATUS:                                   ✅ WITHIN 5s SLO      │
│                                                                 │
│ CRITICAL PATH: Payment authorization (44% of total)             │
│ OPTIMIZATION: PSP selection, caching, regional routing          │
└─────────────────────────────────────────────────────────────────┘
```

## Disaster Recovery & Rollback

```mermaid
graph TB
    subgraph NormalOperation["Normal Operation"]
        Primary["Primary Region\nCheckout Orchestrator"]
        PrimaryTemporal["Primary Temporal"]
        PrimaryDB["Primary PostgreSQL"]
    end

    subgraph FailoverScenarios["Failover Scenarios"]
        TemporalDown["Temporal Down\n→ New checkouts fail\n→ Existing workflows resume\nwhen recovered"]

        DBDown["PostgreSQL Down\n→ Idempotency fails open\n→ Potential duplicates\n(downstream idempotent)"]

        ServiceDown["Downstream Service Down\n→ Activity retries\n→ Compensation on timeout"]
    end

    subgraph Rollback["Rollback Strategy"]
        RollbackCode["Code rollback\n→ ArgoCD revert"]
        RollbackConfig["Config rollback\n→ application.yml revert"]
        TemporalReset["Temporal workflow reset\n→ Replay from event"]
    end

    NormalOperation --> FailoverScenarios
    FailoverScenarios --> Rollback

    style TemporalDown fill:#f59e0b,color:#000
    style DBDown fill:#dc2626,color:#fff
    style ServiceDown fill:#f59e0b,color:#000
```
