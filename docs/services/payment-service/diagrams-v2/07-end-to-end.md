# Payment Service - End-to-End Flows

## Complete Checkout to Refund Flow (E2E)

```mermaid
graph TB
    User["👤 Customer<br/>(Mobile App)"]
    MobileBFF["📱 Mobile BFF"]
    CheckoutOrch["🔄 Checkout Orchestrator<br/>(Temporal Workflow)"]
    CartService["🛒 Cart Service"]
    InventoryService["📦 Inventory Service"]
    PaymentService["💳 Payment Service"]
    StripeAPI["💰 Stripe API"]
    PostgreSQL["🐘 PostgreSQL"]
    Redis["⚡ Redis"]
    Kafka["📬 Kafka<br/>(payment.events)"]
    OrderService["📋 Order Service"]
    FulfillmentService["🚚 Fulfillment Service"]
    NotificationService["🔔 Notification Service"]
    SupportDashboard["👨‍💼 Support Dashboard"]

    subgraph Phase1["Phase 1: Checkout Initiation"]
        User -->|1. Tap 'Place Order'| MobileBFF
        MobileBFF -->|2. Start checkout workflow| CheckoutOrch
        CheckoutOrch -->|3. Get cart details| CartService
        CartService -->|4. Return cart items, total| CheckoutOrch
        CheckoutOrch -->|5. Reserve inventory| InventoryService
        InventoryService -->|6. Inventory reserved| CheckoutOrch
    end

    subgraph Phase2["Phase 2: Payment Authorization"]
        CheckoutOrch -->|7. POST /payments/authorize<br/>{order_id, amount, payment_method}| PaymentService
        PaymentService -->|8. Check idempotency| Redis
        Redis -->|9. Cache miss| PaymentService
        PaymentService -->|10. Create PaymentIntent| StripeAPI
        StripeAPI -->|11. {id: pi_xxx, status: requires_capture}| PaymentService
        PaymentService -->|12. Save payment, ledger entries, outbox event| PostgreSQL
        PaymentService -->|13. {payment_id, status: AUTHORIZED}| CheckoutOrch
    end

    subgraph Phase3["Phase 3: Order Creation"]
        CheckoutOrch -->|14. Create order| OrderService
        OrderService -->|15. {order_id}| CheckoutOrch
    end

    subgraph Phase4["Phase 4: Payment Capture"]
        CheckoutOrch -->|16. POST /payments/{id}/capture| PaymentService
        PaymentService -->|17. Capture PaymentIntent| StripeAPI
        StripeAPI -->|18. {status: succeeded}| PaymentService
        PaymentService -->|19. Update status, ledger, outbox| PostgreSQL
        PaymentService -->|20. {status: CAPTURED}| CheckoutOrch
    end

    subgraph Phase5["Phase 5: Event Propagation"]
        PostgreSQL -->|21. Debezium CDC| Kafka
        Kafka -->|22. PaymentCaptured event| OrderService
        Kafka -->|23. PaymentCaptured event| FulfillmentService
        Kafka -->|24. PaymentCaptured event| NotificationService
        FulfillmentService -->|25. Start fulfillment| FulfillmentService
        NotificationService -->|26. Send confirmation email| User
    end

    subgraph Phase6["Phase 6: Refund (Later)"]
        User -->|27. Request refund via support| SupportDashboard
        SupportDashboard -->|28. POST /refunds<br/>{payment_id, amount, reason}| PaymentService
        PaymentService -->|29. Create Stripe refund| StripeAPI
        StripeAPI -->|30. {id: re_xxx, status: succeeded}| PaymentService
        PaymentService -->|31. Update payment, create ledger entries| PostgreSQL
        PaymentService -->|32. {refund_id, status: COMPLETED}| SupportDashboard
        PostgreSQL -->|33. CDC| Kafka
        Kafka -->|34. PaymentRefunded event| OrderService
        Kafka -->|35. PaymentRefunded event| NotificationService
        NotificationService -->|36. Send refund confirmation| User
    end

    style User fill:#4A90E2,color:#fff
    style PaymentService fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style StripeAPI fill:#635BFF,color:#fff
    style Kafka fill:#231F20,color:#fff
```

## Request Flow with Error Handling

```mermaid
graph TB
    Request["HTTP Request<br/>POST /payments/authorize"]

    subgraph HappyPath["Happy Path (<500ms)"]
        H1["✅ JWT validated<br/>(~15ms)"]
        H2["✅ Idempotency check<br/>(~5ms)"]
        H3["✅ Stripe authorize<br/>(~300ms)"]
        H4["✅ DB persist<br/>(~30ms)"]
        H5["✅ Return 200 OK<br/>Total: ~350ms"]
    end

    subgraph ErrorPaths["Error Paths"]
        E1["❌ JWT invalid<br/>- malformed token<br/>- expired<br/>- wrong issuer"]
        E1_Response["Return 401 Unauthorized"]

        E2["❌ Duplicate request<br/>(idempotency hit)"]
        E2_Response["Return 200 OK<br/>(existing payment)"]

        E3["❌ Card declined<br/>(card_declined, insufficient_funds)"]
        E3_Response["Return 402 Payment Required<br/>{decline_code}"]

        E4["❌ Stripe timeout<br/>(>10s)"]
        E4_Fallback["Retry with backoff<br/>Mark FAILED if retries exhausted"]

        E5["❌ Invalid currency"]
        E5_Response["Return 400 Bad Request"]

        E6["❌ Database error"]
        E6_Response["Transaction rollback<br/>Return 500 Error"]
    end

    Request --> H1
    H1 --> Auth_Check{"JWT<br/>valid?"}

    Auth_Check -->|No| E1
    Auth_Check -->|Yes| H2

    H2 --> Idem_Check{"Duplicate?"}
    Idem_Check -->|Yes| E2
    Idem_Check -->|No| Validate{"Currency<br/>valid?"}

    Validate -->|No| E5
    Validate -->|Yes| H3

    H3 --> Stripe_Check{"Stripe<br/>success?"}
    Stripe_Check -->|Timeout| E4
    Stripe_Check -->|Declined| E3
    Stripe_Check -->|Success| H4

    H4 --> DB_Check{"DB<br/>success?"}
    DB_Check -->|No| E6
    DB_Check -->|Yes| H5

    E1 --> E1_Response
    E2 --> E2_Response
    E3 --> E3_Response
    E4 --> E4_Fallback
    E5 --> E5_Response
    E6 --> E6_Response

    style HappyPath fill:#E8F5E9,color:#000,stroke:#333,stroke-width:2px
    style ErrorPaths fill:#FFEBEE,color:#000,stroke:#333,stroke-width:2px
    style H5 fill:#7ED321,color:#000,stroke:#333,stroke-width:3px
    style E1_Response fill:#FF6B6B,color:#fff
    style E2_Response fill:#7ED321,color:#000
    style E3_Response fill:#FF6B6B,color:#fff
```

## Stripe Webhook Integration Flow

```mermaid
graph TB
    Stripe["💰 Stripe<br/>(Webhook Origin)"]
    ALB["⚖️ AWS ALB"]
    PaymentService["💳 Payment Service"]
    WebhookController["WebhookController"]
    SignatureVerifier["WebhookSignatureVerifier"]
    EventProcessor["WebhookEventProcessor"]
    PostgreSQL["🐘 PostgreSQL"]
    Kafka["📬 Kafka"]

    subgraph WebhookFlow["Webhook Processing"]
        Stripe -->|1. POST /webhooks/stripe<br/>Stripe-Signature: t=...,v1=...| ALB
        ALB -->|2. Forward| WebhookController
        WebhookController -->|3. Extract signature, body| SignatureVerifier
        SignatureVerifier -->|4. Verify HMAC-SHA256| SignatureCheck{"Signature<br/>valid?"}
        
        SignatureCheck -->|No| Reject401["❌ Return 401"]
        SignatureCheck -->|Yes| EventProcessor
        
        EventProcessor -->|5. Check deduplication| DedupCheck{"Already<br/>processed?"}
        DedupCheck -->|Yes| Return200Dup["Return 200 OK<br/>(idempotent)"]
        DedupCheck -->|No| RouteEvent["6. Route by event_type"]
    end

    subgraph EventHandlers["Event Handlers"]
        PISucceeded["payment_intent.succeeded<br/>→ Confirm AUTHORIZED"]
        PIFailed["payment_intent.payment_failed<br/>→ Mark FAILED"]
        ChargeCaptured["charge.captured<br/>→ Confirm CAPTURED"]
        ChargeRefunded["charge.refunded<br/>→ Update refund status"]
        ChargeDisputed["charge.dispute.created<br/>→ Mark DISPUTED"]
    end

    subgraph StateUpdate["State Update (Transaction)"]
        UpdatePayment["7. UPDATE payments<br/>SET status = new_status"]
        CreateLedger["8. INSERT ledger_entries<br/>(if state change)"]
        SaveWebhook["9. INSERT processed_webhook_events"]
        CreateOutbox["10. INSERT outbox_events"]
        Commit["11. COMMIT"]
    end

    RouteEvent --> EventHandlers
    EventHandlers --> UpdatePayment
    UpdatePayment --> CreateLedger
    CreateLedger --> SaveWebhook
    SaveWebhook --> CreateOutbox
    CreateOutbox --> Commit
    Commit --> Return200["✅ Return 200 OK"]

    PostgreSQL --> |12. Debezium CDC| Kafka

    style Stripe fill:#635BFF,color:#fff
    style SignatureVerifier fill:#7ED321,color:#000
    style Reject401 fill:#FF6B6B,color:#fff
    style Return200 fill:#7ED321,color:#000
```

## Resilience & Degradation Patterns

```mermaid
graph TB
    Request["Payment Request"]

    subgraph Resilience["Resilience Patterns"]
        CB["🔴 Circuit Breaker<br/>(Stripe Gateway)"]
        Retry["🔄 Retry with Backoff<br/>(max 3 attempts)"]
        Timeout["⏰ Timeout<br/>(10s per call)"]
        Idempotency["🔑 Idempotency<br/>(Redis + DB)"]
    end

    subgraph ServiceStatus["Service Status Check"]
        StripeHealth{"Stripe<br/>healthy?"}
        CBState{"Circuit<br/>Breaker<br/>state?"}
    end

    subgraph Outcomes["Outcomes"]
        DirectCall["Direct call to Stripe<br/>(normal operation)"]
        FailFast["Fast fail<br/>(circuit OPEN)"]
        RetryCall["Retry with backoff<br/>(transient failure)"]
        DegradeResponse["Degraded response<br/>(return queued status)"]
    end

    Request --> Idempotency
    Idempotency --> CB
    CB --> CBState

    CBState -->|CLOSED| StripeHealth
    CBState -->|OPEN| FailFast
    CBState -->|HALF_OPEN| TestCall["Test probe"]

    StripeHealth -->|Yes| DirectCall
    StripeHealth -->|Transient error| Retry
    StripeHealth -->|Timeout| Timeout

    Retry --> RetryCall
    Timeout --> RetryCall
    RetryCall -->|Success| DirectCall
    RetryCall -->|Exhausted| DegradeResponse

    TestCall -->|Success| DirectCall
    TestCall -->|Fail| FailFast

    FailFast --> DegradeResponse

    style CB fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style DirectCall fill:#7ED321,color:#000
    style FailFast fill:#F5A623,color:#000
    style DegradeResponse fill:#F5A623,color:#000
```

## Multi-Service Orchestration (Saga Pattern)

```mermaid
graph TB
    subgraph Workflow["Checkout Workflow (Temporal)"]
        Start["Start Workflow"]
        ReserveInventory["Activity: Reserve Inventory"]
        AuthorizePayment["Activity: Authorize Payment"]
        CreateOrder["Activity: Create Order"]
        CapturePayment["Activity: Capture Payment"]
        Complete["Complete Workflow"]
    end

    subgraph Compensations["Compensation Actions"]
        ReleaseInventory["Compensation: Release Inventory"]
        VoidPayment["Compensation: Void Payment"]
        CancelOrder["Compensation: Cancel Order"]
    end

    subgraph Services["Services"]
        Inventory["Inventory Service"]
        Payment["Payment Service"]
        Order["Order Service"]
    end

    Start --> ReserveInventory
    ReserveInventory -->|Success| AuthorizePayment
    ReserveInventory -->|Failure| WorkflowFailed["Workflow Failed"]

    AuthorizePayment -->|Success| CreateOrder
    AuthorizePayment -->|Failure| ReleaseInventory
    ReleaseInventory --> WorkflowFailed

    CreateOrder -->|Success| CapturePayment
    CreateOrder -->|Failure| VoidPayment
    VoidPayment --> ReleaseInventory

    CapturePayment -->|Success| Complete
    CapturePayment -->|Failure| CancelOrder
    CancelOrder --> VoidPayment

    ReserveInventory --> Inventory
    AuthorizePayment --> Payment
    CreateOrder --> Order
    CapturePayment --> Payment
    ReleaseInventory --> Inventory
    VoidPayment --> Payment
    CancelOrder --> Order

    style AuthorizePayment fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style CapturePayment fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style VoidPayment fill:#FF6B6B,color:#fff
    style WorkflowFailed fill:#FF6B6B,color:#fff
```

## Reconciliation & Monitoring Dashboard

```mermaid
graph LR
    PaymentService["Payment Service"]

    subgraph SLOs["Service Level Objectives"]
        AuthSLO["📊 Authorization SLO<br/>P99 < 500ms<br/>Success rate > 99%"]
        CaptureSLO["📊 Capture SLO<br/>P99 < 300ms<br/>Success rate > 99.9%"]
        RefundSLO["📊 Refund SLO<br/>P99 < 400ms<br/>Success rate > 99%"]
    end

    subgraph Metrics["Key Metrics"]
        M1["payment_authorizations_total<br/>{status, currency}"]
        M2["payment_operation_duration_ms<br/>{operation}"]
        M3["stripe_api_latency_ms<br/>{endpoint}"]
        M4["ledger_balance_imbalance_total"]
        M5["circuit_breaker_state<br/>{gateway}"]
    end

    subgraph Alerts["Alert Rules (Multi-window)"]
        A1["🔴 SEV-1 (5min)<br/>Authorization failure > 5%"]
        A2["🔴 SEV-1 (5min)<br/>Stripe API p99 > 2000ms"]
        A3["🟠 SEV-2 (15min)<br/>Capture failure > 1%"]
        A4["🟡 SEV-3 (1hr)<br/>Ledger imbalance detected"]
        A5["🟡 SEV-3 (6hr)<br/>Refund processing > 24hr"]
    end

    subgraph Dashboards["Grafana Dashboards"]
        D1["Payment Overview<br/>- TPV (Total Payment Volume)<br/>- Success rates<br/>- Latency percentiles"]
        D2["Stripe Health<br/>- API latency<br/>- Error rates<br/>- Circuit breaker state"]
        D3["Reconciliation<br/>- Ledger balance<br/>- Stale payments<br/>- Webhook lag"]
    end

    PaymentService --> AuthSLO
    PaymentService --> CaptureSLO
    PaymentService --> RefundSLO

    AuthSLO --> M1
    CaptureSLO --> M2
    RefundSLO --> M3

    M1 --> A1
    M2 --> A2
    M3 --> A3
    M4 --> A4

    M1 --> D1
    M3 --> D2
    M4 --> D3

    A1 --> Page["📱 Page SRE"]
    A2 --> Page
    A3 --> Incident["📋 Create Incident"]
    A4 --> Review["📊 Weekly Review"]

    style AuthSLO fill:#7ED321,color:#000
    style CaptureSLO fill:#7ED321,color:#000
    style RefundSLO fill:#7ED321,color:#000
    style A1 fill:#FF6B6B,color:#fff
    style A2 fill:#FF6B6B,color:#fff
    style A3 fill:#F5A623,color:#000
    style A4 fill:#FFC107,color:#000
```

## Data Flow: Payment to Analytics

```mermaid
graph TB
    subgraph OperationalDB["Operational Layer"]
        PaymentService["Payment Service"]
        PostgreSQL["PostgreSQL<br/>(payments, ledger)"]
    end

    subgraph CDC["Change Data Capture"]
        Debezium["Debezium Connector<br/>(outbox.EventRouter)"]
        Kafka["Kafka<br/>(payment.events)"]
    end

    subgraph DataPlatform["Data Platform"]
        KafkaConnect["Kafka Connect<br/>(BigQuery Sink)"]
        BigQuery["BigQuery<br/>(Raw Events)"]
        DBT["dbt Models<br/>(stg_payments, int_payments, mart_payments)"]
        Marts["Analytics Marts<br/>(payment_metrics, revenue_daily)"]
    end

    subgraph Analytics["Analytics & ML"]
        Looker["Looker<br/>(Business Intelligence)"]
        MLFeatures["ML Feature Store<br/>(fraud signals)"]
        FraudModel["Fraud Detection Model"]
    end

    PaymentService -->|1. Persist| PostgreSQL
    PostgreSQL -->|2. CDC| Debezium
    Debezium -->|3. Publish| Kafka
    Kafka -->|4. Consume| KafkaConnect
    KafkaConnect -->|5. Load| BigQuery
    BigQuery -->|6. Transform| DBT
    DBT -->|7. Build| Marts
    Marts -->|8. Query| Looker
    Marts -->|9. Extract| MLFeatures
    MLFeatures -->|10. Train| FraudModel
    FraudModel -->|11. Score| PaymentService

    style PaymentService fill:#4A90E2,color:#fff
    style Kafka fill:#231F20,color:#fff
    style BigQuery fill:#4285F4,color:#fff
    style Looker fill:#9013FE,color:#fff
```
