# Checkout Orchestrator - Flowcharts

## Complete Checkout Saga Flow

```mermaid
flowchart TD
    A["📱 POST /checkout\n(JWT + Idempotency-Key)"]
    B["Extract JWT, validate signature"]
    C{{"JWT\nvalid?"}}
    D["Extract Idempotency-Key header"]
    E{{"Key\nprovided?"}}
    F["Generate UUID"]
    G["Use provided key"]
    H["Query checkout_idempotency_keys"]
    I{{"Key exists\n& not expired?"}}
    J["Return cached CheckoutResponse\n(200 OK)"]
    K["Start Temporal workflow\nworkflowId = checkout-{userId}-{key}"]

    A --> B --> C
    C -->|No| Err401["❌ 401 Unauthorized"]
    C -->|Yes| D --> E
    E -->|No| F --> H
    E -->|Yes| G --> H
    H --> I
    I -->|Yes| J
    I -->|No| K

    subgraph Saga["Temporal Saga Execution"]
        S1["① VALIDATING_CART\ncartActivity.validateCart()"]
        S1_Check{{"Cart\nvalid?"}}
        S2["② CALCULATING_PRICES\npricingActivity.calculatePrice()"]
        S3["③ RESERVING_INVENTORY\ninventoryActivity.reserveStock()"]
        S3_Check{{"Stock\nreserved?"}}
        S3_Comp["Register compensation:\nreleaseStock(reservationId)"]
        S4["④ AUTHORIZING_PAYMENT\npaymentActivity.authorizePayment()"]
        S4_Check{{"Payment\nauthorized?"}}
        S5["⑤ CREATING_ORDER\norderActivity.createOrder()"]
        S5_Comp["Register compensation:\ncancelOrder(orderId)"]
        S5b["⑤b REDEEMING_COUPON\n(if applicable)"]
        S6["⑥ CONFIRMING\nconfirmStock + capturePayment"]
        S7["⑦ CLEARING_CART\ncartActivity.clearCart()"]
        S8["✅ COMPLETED\nReturn success response"]
    end

    K --> S1
    S1 --> S1_Check
    S1_Check -->|No| Fail1["FAILED: Cart empty/invalid"]
    S1_Check -->|Yes| S2
    S2 --> S3
    S3 --> S3_Check
    S3_Check -->|No| Fail2["FAILED: Out of stock"]
    S3_Check -->|Yes| S3_Comp --> S4
    S4 --> S4_Check
    S4_Check -->|No| Comp1["COMPENSATING\nsaga.compensate()"]
    S4_Check -->|Yes| S5
    S5 --> S5_Comp --> S5b --> S6 --> S7 --> S8

    Comp1 --> Fail3["FAILED: Payment declined"]

    S8 --> Persist["Persist to checkout_idempotency_keys\n(TTL = 30 min)"]
    Persist --> Success["200 OK\n{orderId, status, totalCents, eta}"]

    style S1 fill:#3b82f6,color:#fff
    style S3 fill:#f59e0b,color:#000
    style S4 fill:#dc2626,color:#fff
    style S5 fill:#22c55e,color:#fff
    style S8 fill:#22c55e,color:#fff
    style Comp1 fill:#dc2626,color:#fff
```

## Payment Compensation Strategy (Three-Tier)

```mermaid
flowchart TD
    EX(["🚨 Exception in saga"])
    CHECK_AUTH{{"paymentAuthorized?"}}
    CHECK_AMT{{"paymentAmountCents\n> 0?"}}
    CHECK_CAPTURED{{"paymentCaptured?"}}
    CHECK_ATTEMPT{{"paymentCapture\nAttempted?"}}

    LOG_SKIP["⚠️ Log warning:\nskip refund (missing amount)"]
    REFUND["💸 refundPayment(\npaymentId, amountCents, refundKey)"]
    TRY_REFUND["Try refundPayment()"]
    VOID["🚫 voidPayment(\npaymentId, voidKey)"]
    VOID2["🚫 voidPayment(\npaymentId, voidKey)"]

    SAGA["saga.compensate()"]
    COMP_ORDER["cancelOrder(orderId)\n(if registered)"]
    COMP_INV["releaseStock(reservationId)\n(if registered)"]
    RETURN(["Return FAILED response"])

    EX --> CHECK_AUTH
    CHECK_AUTH -->|No| SAGA
    CHECK_AUTH -->|Yes| CHECK_AMT
    CHECK_AMT -->|No| LOG_SKIP --> SAGA

    CHECK_AMT -->|Yes| CHECK_CAPTURED
    CHECK_CAPTURED -->|Yes| REFUND --> SAGA

    CHECK_CAPTURED -->|No| CHECK_ATTEMPT
    CHECK_ATTEMPT -->|Yes| TRY_REFUND
    TRY_REFUND -->|success| SAGA
    TRY_REFUND -->|failure| VOID --> SAGA

    CHECK_ATTEMPT -->|No| VOID2 --> SAGA

    SAGA --> COMP_ORDER
    COMP_ORDER --> COMP_INV
    COMP_INV --> RETURN

    style RETURN fill:#dc2626,color:#fff
    style REFUND fill:#f59e0b,color:#000
    style VOID fill:#6366f1,color:#fff
    style VOID2 fill:#6366f1,color:#fff
    style SAGA fill:#4f46e5,color:#fff
```

## Failure Point Compensation Matrix

```mermaid
flowchart TB
    subgraph FailurePoints["Failure Points"]
        F1["❌ Cart validation fails"]
        F2["❌ Pricing calculation fails"]
        F3["❌ Inventory reservation fails"]
        F4["❌ Payment authorization declined"]
        F5["❌ Order creation fails"]
        F6["❌ Inventory confirm fails"]
        F7["❌ Payment capture fails"]
    end

    subgraph CompensationActions["Compensation Actions"]
        C_INV["🔄 releaseStock"]
        C_PAY_V["🚫 voidPayment"]
        C_PAY_R["💸 refundPayment"]
        C_ORD["📦 cancelOrder"]
    end

    subgraph NoAction["No Compensation Needed"]
        NA["— (nothing to undo)"]
    end

    F1 --> NA
    F2 --> NA
    F3 --> NA
    F4 --> C_INV
    F5 --> C_INV
    F5 --> C_PAY_V
    F6 --> C_INV
    F6 --> C_PAY_V
    F6 --> C_ORD
    F7 --> C_INV
    F7 --> C_PAY_R
    F7 --> C_ORD

    style F4 fill:#dc2626,color:#fff
    style F5 fill:#dc2626,color:#fff
    style F6 fill:#dc2626,color:#fff
    style F7 fill:#dc2626,color:#fff
    style C_PAY_R fill:#f59e0b,color:#000
```

## Idempotency Check Flow

```mermaid
flowchart TD
    REQ["POST /checkout\nIdempotency-Key header"]
    HEADER{{"Header\npresent?"}}
    GEN["Generate UUID"]
    USE["Use provided key"]
    LOOKUP["DB: checkout_idempotency_keys\nSELECT by idempotency_key"]
    EXISTS{{"Row exists\n& not expired?"}}
    CACHED["✅ Return cached\nCheckoutResponse (200)"]
    WORKFLOW["Start Temporal workflow\nworkflowId = checkout-{userId}-{key}"]
    PERSIST["Persist response\nexpires_at = now + 30 min"]
    RESPOND["200 OK"]

    REQ --> HEADER
    HEADER -->|No| GEN --> LOOKUP
    HEADER -->|Yes| USE --> LOOKUP
    LOOKUP --> EXISTS
    EXISTS -->|Yes| CACHED
    EXISTS -->|No| WORKFLOW
    WORKFLOW --> PERSIST --> RESPOND

    style CACHED fill:#3b82f6,color:#fff
    style RESPOND fill:#22c55e,color:#fff
```

## Activity Retry Flow

```mermaid
flowchart TD
    Schedule["Temporal schedules activity"]
    Execute["Execute activity\n(REST call to downstream)"]
    Result{{"Result?"}}

    Success["✅ Activity succeeded"]
    Retryable["⚠️ Retryable error\n(timeout, 5xx)"]
    NonRetryable["❌ Non-retryable error\n(configured exception)"]

    CheckAttempts{{"Attempts\n< maxAttempts?"}}
    Backoff["Wait: interval × backoff^attempt"]
    Retry["Retry activity"]
    FailActivity["Activity fails"]

    Schedule --> Execute --> Result

    Result -->|Success| Success
    Result -->|Error| CheckException{{"Exception in\nDoNotRetry?"}}

    CheckException -->|Yes| NonRetryable --> FailActivity
    CheckException -->|No| Retryable --> CheckAttempts

    CheckAttempts -->|Yes| Backoff --> Retry --> Execute
    CheckAttempts -->|No| FailActivity

    FailActivity --> TriggerComp["Trigger compensation"]

    style Success fill:#22c55e,color:#fff
    style FailActivity fill:#dc2626,color:#fff
    style Backoff fill:#f59e0b,color:#000
```

## Workflow Status Query Flow

```mermaid
flowchart TD
    A["GET /checkout/{workflowId}/status"]
    B["Extract workflowId from path"]
    C["Create workflow stub"]
    D["Query workflow: getStatus()"]
    E{{"Workflow\nexists?"}}
    F["Return current status"]
    G["❌ 404 WORKFLOW_NOT_FOUND"]

    H{{"Status value?"}}
    I["STARTED"]
    J["VALIDATING_CART"]
    K["CALCULATING_PRICES"]
    L["RESERVING_INVENTORY"]
    M["AUTHORIZING_PAYMENT"]
    N["CREATING_ORDER"]
    O["REDEEMING_COUPON"]
    P["CONFIRMING"]
    Q["CLEARING_CART"]
    R["COMPLETED"]
    S["COMPENSATING"]
    T["FAILED"]

    A --> B --> C --> D --> E
    E -->|No| G
    E -->|Yes| F --> H

    H --> I
    H --> J
    H --> K
    H --> L
    H --> M
    H --> N
    H --> O
    H --> P
    H --> Q
    H --> R
    H --> S
    H --> T

    style R fill:#22c55e,color:#fff
    style T fill:#dc2626,color:#fff
    style S fill:#f59e0b,color:#000
```

## Concurrent Checkout Prevention

```mermaid
flowchart TD
    Req1["Request 1:\nIdempotency-Key: ik-abc123"]
    Req2["Request 2:\nIdempotency-Key: ik-abc123\n(duplicate)"]

    Controller1["CheckoutController\n(Request 1)"]
    Controller2["CheckoutController\n(Request 2)"]

    DBCheck1["DB: key not found"]
    DBCheck2["DB: key not found\n(race condition)"]

    Start1["workflowClient.start(\ncheckout-user1-ik-abc123)"]
    Start2["workflowClient.start(\ncheckout-user1-ik-abc123)"]

    TemporalAccept["✅ Temporal accepts\nfirst workflow start"]
    TemporalReject["❌ WorkflowExecutionAlreadyStarted"]

    Running["Workflow running"]
    QueryStatus["Query workflow status"]
    Response409["409 CHECKOUT_ALREADY_IN_PROGRESS\n+ workflowId + current status"]

    Req1 --> Controller1 --> DBCheck1 --> Start1 --> TemporalAccept --> Running
    Req2 --> Controller2 --> DBCheck2 --> Start2 --> TemporalReject
    TemporalReject --> QueryStatus --> Response409

    style TemporalAccept fill:#22c55e,color:#fff
    style TemporalReject fill:#f59e0b,color:#000
    style Response409 fill:#6366f1,color:#fff
```

## Idempotency Key Cleanup Flow

```mermaid
flowchart TD
    Scheduler["@Scheduled\n(fixedRate = 3600000)\nHourly trigger"]
    ShedLock["ShedLock acquisition\nlockAtLeastFor=PT5M\nlockAtMostFor=PT30M"]
    Acquired{{"Lock\nacquired?"}}
    Skip["Skip execution\n(another instance holds lock)"]
    Query["SELECT keys WHERE\nexpires_at < now() - 24h"]
    Count{{"Keys found?"}}
    NoOp["No expired keys"]
    Delete["DELETE expired keys\n(batch of 1000)"]
    Log["Log: cleaned N keys"]
    Release["Release ShedLock"]

    Scheduler --> ShedLock --> Acquired
    Acquired -->|No| Skip
    Acquired -->|Yes| Query --> Count
    Count -->|No| NoOp --> Release
    Count -->|Yes| Delete --> Log --> Release

    style Scheduler fill:#6366f1,color:#fff
    style ShedLock fill:#0ea5e9,color:#fff
    style Delete fill:#f59e0b,color:#000
```

## Cart Clear Best-Effort Flow

```mermaid
flowchart TD
    Confirm["Step 6: Confirm + Capture\ncompleted successfully"]
    ClearCart["Step 7: CLEARING_CART\ncartActivity.clearCart(userId)"]
    Result{{"Clear cart\nresult?"}}
    Success["Cart cleared"]
    Failure["Exception thrown"]
    LogWarn["Log.warn:\n'Failed to clear cart, continuing'"]
    Continue["Continue to COMPLETED\n(don't fail checkout)"]
    Complete["✅ COMPLETED\nReturn success response"]

    Confirm --> ClearCart --> Result
    Result -->|Success| Success --> Complete
    Result -->|Exception| Failure --> LogWarn --> Continue --> Complete

    style Complete fill:#22c55e,color:#fff
    style LogWarn fill:#fef3c7,color:#000
    style Continue fill:#dcfce7,color:#000
```
