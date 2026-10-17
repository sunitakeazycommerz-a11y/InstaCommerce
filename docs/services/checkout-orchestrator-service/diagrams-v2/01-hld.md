# Checkout Orchestrator - High-Level Design

## System Context

```mermaid
flowchart TB
    Customer["👤 Customer\n(Mobile / Web)"]
    BFF["mobile-bff-service\n:8097"]
    Identity["identity-service\n:8081\n(JWT RS256)"]
    CO["checkout-orchestrator-service\n:8089\n(This Service)"]
    Temporal["Temporal Server\n:7233"]
    Cart["cart-service\n:8084"]
    Pricing["pricing-service\n:8087"]
    Inventory["inventory-service\n:8083"]
    Payment["payment-service\n:8086"]
    Order["order-service\n:8085"]
    PSP["Payment Gateway\n(Stripe / Razorpay)"]
    Kafka["Kafka\n(orders / payments / inventory topics)"]
    Debezium["Debezium\n(Outbox CDC relay)"]

    Customer -->|"HTTPS"| BFF
    BFF -->|"POST /checkout\n(JWT forwarded)"| CO
    CO -->|"gRPC\nworkflow start/query"| Temporal
    CO -->|"REST\n(X-Internal-Service auth)"| Cart
    CO -->|"REST"| Pricing
    CO -->|"REST"| Inventory
    CO -->|"REST"| Payment
    CO -->|"REST"| Order
    Payment -->|"API"| PSP
    Identity -.->|"JWT public key"| CO

    Order -->|"outbox rows"| Debezium
    Payment -->|"outbox rows"| Debezium
    Inventory -->|"outbox rows"| Debezium
    Debezium -->|"CDC"| Kafka

    style CO fill:#4f46e5,color:#fff,stroke:#312e81
    style Temporal fill:#e0e7ff,color:#000
    style Payment fill:#dc2626,color:#fff
    style Order fill:#059669,color:#fff
```

## Saga Orchestration Architecture

```mermaid
graph TB
    subgraph BFFLayer["Edge Layer"]
        BFF["Mobile BFF Service\n:8097"]
    end

    subgraph CheckoutOrchestrator["Checkout Orchestrator (This Service)"]
        Controller["CheckoutController\nPOST /checkout"]
        IdempotencyCheck["Idempotency Check\n(PostgreSQL)"]
        WorkflowClient["Temporal WorkflowClient"]
        WorkflowExec["CheckoutWorkflowImpl\n(Saga Coordinator)"]
    end

    subgraph TemporalCluster["Temporal Cluster"]
        TemporalServer["Temporal Server\n:7233"]
        TaskQueue["CHECKOUT_ORCHESTRATOR_TASK_QUEUE"]
        WorkflowHistory["Workflow History\n(Event Sourcing)"]
    end

    subgraph Activities["Activity Implementations"]
        CartActivity["CartActivityImpl"]
        PricingActivity["PricingActivityImpl"]
        InventoryActivity["InventoryActivityImpl"]
        PaymentActivity["PaymentActivityImpl"]
        OrderActivity["OrderActivityImpl"]
        CouponActivity["CouponActivityImpl"]
    end

    subgraph DownstreamServices["Domain Services (Transactional Core)"]
        CartSvc["cart-service\n:8084"]
        PricingSvc["pricing-service\n:8087"]
        InventorySvc["inventory-service\n:8083"]
        PaymentSvc["payment-service\n:8086"]
        OrderSvc["order-service\n:8085"]
    end

    BFF -->|"1. POST /checkout"| Controller
    Controller -->|"2. Check duplicate"| IdempotencyCheck
    Controller -->|"3. Start workflow"| WorkflowClient
    WorkflowClient -->|"4. gRPC"| TemporalServer
    TemporalServer -->|"5. Schedule"| TaskQueue
    TaskQueue -->|"6. Execute"| WorkflowExec

    WorkflowExec -->|"Activity calls"| CartActivity
    WorkflowExec --> PricingActivity
    WorkflowExec --> InventoryActivity
    WorkflowExec --> PaymentActivity
    WorkflowExec --> OrderActivity
    WorkflowExec --> CouponActivity

    CartActivity -->|"REST"| CartSvc
    PricingActivity --> PricingSvc
    InventoryActivity --> InventorySvc
    PaymentActivity --> PaymentSvc
    OrderActivity --> OrderSvc

    TemporalServer -->|"Persist"| WorkflowHistory

    style WorkflowExec fill:#4f46e5,color:#fff,stroke:#312e81,stroke-width:2px
    style TemporalServer fill:#6366f1,color:#fff
    style PaymentActivity fill:#dc2626,color:#fff
    style Controller fill:#0ea5e9,color:#fff
```

## Hybrid Orchestration / Choreography Model

```mermaid
graph TB
    subgraph Orchestrated["ORCHESTRATED (Temporal Saga)"]
        direction LR
        Step1["① Cart\nValidation"]
        Step2["② Pricing\nCalculation"]
        Step3["③ Inventory\nReservation"]
        Step4["④ Payment\nAuthorization"]
        Step5["⑤ Order\nCreation"]
        Step6["⑥ Confirm &\nCapture"]
        Step7["⑦ Clear\nCart"]

        Step1 --> Step2 --> Step3 --> Step4 --> Step5 --> Step6 --> Step7
    end

    SagaComplete["Saga Completes\n(orderId returned)"]

    subgraph Choreographed["CHOREOGRAPHED (Outbox → Kafka)"]
        direction LR
        OrderEvent["OrderCreated\nevent"]
        PaymentEvent["PaymentCaptured\nevent"]
        StockEvent["StockConfirmed\nevent"]

        NotifSvc["notification-service"]
        FulfillSvc["fulfillment-service"]
        RiderSvc["rider-fleet-service"]
        ReconSvc["reconciliation-service"]
        AnalyticsSvc["analytics pipeline"]
    end

    Step7 --> SagaComplete
    SagaComplete --> OrderEvent
    SagaComplete --> PaymentEvent
    SagaComplete --> StockEvent

    OrderEvent --> NotifSvc
    OrderEvent --> FulfillSvc
    OrderEvent --> RiderSvc

    PaymentEvent --> ReconSvc
    PaymentEvent --> NotifSvc

    StockEvent --> AnalyticsSvc

    style Orchestrated fill:#dbeafe,color:#000,stroke:#3b82f6,stroke-width:2px
    style Choreographed fill:#dcfce7,color:#000,stroke:#22c55e,stroke-width:2px
    style SagaComplete fill:#4f46e5,color:#fff
```

## Service Boundary & Ownership

```mermaid
graph TB
    subgraph Owns["✅ Checkout Orchestrator OWNS"]
        SagaSequence["Saga step ordering\n& sequencing"]
        CompensationLogic["Compensation decisions\n(which steps to undo)"]
        IdempotencyCache["Idempotency key cache\n(checkout_idempotency_keys)"]
        WorkflowLifecycle["Temporal workflow lifecycle"]
    end

    subgraph DoesNotOwn["❌ Does NOT Own (Delegates to)"]
        CartData["Cart contents\n→ cart-service"]
        PricingData["Price calculation\n→ pricing-service"]
        InventoryData["Stock counts\n→ inventory-service"]
        PaymentData["Payment ledger\n→ payment-service"]
        OrderData["Order state\n→ order-service"]
        JWTData["JWT keys\n→ identity-service"]
    end

    subgraph PureOrchestrator["Pure Orchestrator Pattern"]
        NoState["No domain state"]
        NoPersist["No order/payment persistence"]
        NoEvents["No event publishing\n(downstream services publish)"]
    end

    Owns --> PureOrchestrator
    DoesNotOwn --> PureOrchestrator

    style Owns fill:#dcfce7,color:#000,stroke:#22c55e,stroke-width:2px
    style DoesNotOwn fill:#fef3c7,color:#000,stroke:#f59e0b,stroke-width:2px
    style PureOrchestrator fill:#e0e7ff,color:#000,stroke:#6366f1,stroke-width:2px
```

## Temporal Integration Architecture

```mermaid
graph TB
    subgraph ClientSide["Controller (Client)"]
        Controller["CheckoutController"]
        WfOptions["WorkflowOptions\n- workflowId: checkout-{userId}-{key}\n- taskQueue: CHECKOUT_ORCHESTRATOR_TASK_QUEUE\n- executionTimeout: 5min"]
        WfClient["WorkflowClient.start()"]
    end

    subgraph TemporalServer["Temporal Server"]
        Frontend["Frontend Service\n(gRPC :7233)"]
        Matching["Matching Service\n(Task routing)"]
        History["History Service\n(Event persistence)"]
        DB["PostgreSQL / Cassandra\n(Workflow state)"]
    end

    subgraph WorkerSide["Worker (This Service)"]
        WorkerFactory["WorkerFactory"]
        Worker["Worker\n(taskQueue: CHECKOUT_ORCHESTRATOR)"]
        WorkflowImpl["CheckoutWorkflowImpl"]
        ActivityImpls["Activity Implementations\n(CartActivityImpl, etc.)"]
    end

    Controller --> WfOptions --> WfClient
    WfClient -->|"gRPC: StartWorkflow"| Frontend
    Frontend --> Matching
    Matching -->|"Task"| Worker

    Worker --> WorkflowImpl
    WorkflowImpl --> ActivityImpls

    ActivityImpls -->|"Activity result"| Worker
    Worker -->|"Complete task"| Frontend
    Frontend --> History
    History --> DB

    style Frontend fill:#6366f1,color:#fff
    style WorkflowImpl fill:#4f46e5,color:#fff
    style Worker fill:#0ea5e9,color:#fff
```

## Critical Path SLOs

```mermaid
graph LR
    subgraph SLOs["Checkout SLOs"]
        Latency["⏱️ P99 Latency\n< 5 seconds"]
        Success["✅ Success Rate\n> 99.5%"]
        Availability["🟢 Availability\n99.9%"]
    end

    subgraph Budgets["Error Budgets"]
        LatencyBudget["Latency violations\n< 0.5% of requests"]
        FailureBudget["Checkout failures\n< 0.5% of attempts"]
        DowntimeBudget["Downtime\n< 8.7 hours/year"]
    end

    subgraph Alerts["Alert Thresholds"]
        SEV1["🔴 SEV-1\np99 > 10s OR\nsuccess < 98%"]
        SEV2["🟠 SEV-2\np99 > 7s OR\nsuccess < 99%"]
        SEV3["🟡 SEV-3\np99 > 5s OR\nsuccess < 99.5%"]
    end

    SLOs --> Budgets --> Alerts

    style Latency fill:#22c55e,color:#fff
    style Success fill:#22c55e,color:#fff
    style SEV1 fill:#dc2626,color:#fff
    style SEV2 fill:#f59e0b,color:#000
```
