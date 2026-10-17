# Checkout Orchestrator - Sequence Diagrams

## Complete Checkout Money-Path Sequence (Happy Path)

```mermaid
sequenceDiagram
    autonumber
    participant App as Mobile App
    participant BFF as MobileBFF
    participant CO as CheckoutController<br/>(:8089)
    participant DB as PostgreSQL<br/>(idempotency_keys)
    participant TW as Temporal<br/>(CheckoutWorkflowImpl)
    participant Cart as CartService<br/>(:8084)
    participant Pricing as PricingService<br/>(:8087)
    participant Inv as InventoryService<br/>(:8083)
    participant Pay as PaymentService<br/>(:8086)
    participant PSP as Stripe / Razorpay
    participant Ord as OrderService<br/>(:8085)

    App->>BFF: POST /checkout (JWT, paymentMethodId, deliveryAddressId)
    BFF->>CO: POST /checkout + Idempotency-Key header

    CO->>DB: SELECT FROM checkout_idempotency_keys WHERE key = ?
    alt Key exists and not expired
        DB-->>CO: Cached CheckoutResponse
        CO-->>BFF: 200 OK (cached)
    else Key absent or expired
        CO->>TW: workflowClient.start(workflowId="checkout-{userId}-{key}",<br/>execTimeout=5min, taskQueue=CHECKOUT_ORCHESTRATOR_TASK_QUEUE)

        Note over TW: Saga(parallelCompensation=false,<br/>continueWithError=true)

        TW->>Cart: ① validateCart(userId) [10s, 3 attempts]
        Cart-->>TW: CartValidationResult{valid, items, storeId}

        TW->>Pricing: ② calculatePrice(PricingRequest) [10s, 3 attempts]
        Pricing-->>TW: PricingResult{totalCents, subtotalCents,<br/>discountCents, deliveryFeeCents, currency}

        TW->>Inv: ③ reserveStock(items) [15s, DoNotRetry: InsufficientStockException]
        Inv-->>TW: {reservationId, reserved=true}
        Note over TW: saga.addCompensation(releaseStock, reservationId)

        TW->>Pay: ④ authorizePayment(totalCents, paymentMethodId,<br/>workflowId+":pay:auth") [30s/45s, DoNotRetry: PaymentDeclinedException]
        Pay->>PSP: gateway.authorize(amount, idempotencyKey)
        PSP-->>Pay: authorized
        Pay-->>TW: PaymentAuthResult{paymentId, authorized=true}

        TW->>Ord: ⑤ createOrder(OrderCreateRequest) [15s, 3 attempts]
        Ord-->>TW: {orderId, estimatedDeliveryMinutes}
        Note over TW: saga.addCompensation(cancelOrder, orderId)

        TW->>Inv: ⑥a confirmStock(reservationId)
        TW->>Pay: ⑥b capturePayment(paymentId, captureKey)
        Pay->>PSP: gateway.capture(pspRef, captureKey)
        PSP-->>Pay: captured

        TW->>Cart: ⑦ clearCart(userId) — best effort, failure swallowed

        TW-->>CO: CheckoutResponse{COMPLETED, orderId, totalCents, eta}
        CO->>DB: INSERT checkout_idempotency_keys (key, response, expiresAt=now+30min)
        CO-->>BFF: 200 OK
    end
    BFF-->>App: 200 OK {orderId, totalCents, estimatedDeliveryMinutes}
```

## Payment Authorization Declined - Compensation Sequence

```mermaid
sequenceDiagram
    autonumber
    participant TW as Temporal<br/>(CheckoutWorkflowImpl)
    participant Cart as CartService
    participant Pricing as PricingService
    participant Inv as InventoryService
    participant Pay as PaymentService
    participant PSP as Payment Gateway

    Note over TW: Steps 1-3 succeeded

    TW->>Cart: ① validateCart(userId)
    Cart-->>TW: {valid: true, items, storeId}

    TW->>Pricing: ② calculatePrice(...)
    Pricing-->>TW: {totalCents: 4599}

    TW->>Inv: ③ reserveStock(items)
    Inv-->>TW: {reservationId: "res-123", reserved: true}
    Note over TW: saga.addCompensation(releaseStock, "res-123")

    TW->>Pay: ④ authorizePayment(4599, "pm_xyz", authKey)
    Pay->>PSP: gateway.authorize(4599, authKey)
    PSP-->>Pay: ❌ declined: insufficient_funds

    Pay-->>TW: PaymentAuthResult{authorized: false,<br/>declineReason: "insufficient_funds"}

    Note over TW: currentStatus = "COMPENSATING"

    TW->>TW: No payment to void<br/>(authorization failed)

    TW->>Inv: saga.compensate() → releaseStock("res-123")
    Inv-->>TW: Stock released

    Note over TW: currentStatus = "FAILED"

    TW-->>TW: Return CheckoutResponse{<br/>status: "FAILED: Payment declined: insufficient_funds"}
```

## Order Creation Failure - Full Compensation Sequence

```mermaid
sequenceDiagram
    autonumber
    participant TW as Temporal<br/>(CheckoutWorkflowImpl)
    participant Inv as InventoryService
    participant Pay as PaymentService
    participant PSP as Payment Gateway
    participant Ord as OrderService

    Note over TW: Steps 1-4 succeeded, payment authorized

    TW->>Ord: ⑤ createOrder(OrderCreateRequest)
    Ord-->>TW: ❌ 500 Internal Server Error

    Note over TW: currentStatus = "COMPENSATING"<br/>compensatePayment() runs first

    rect rgb(254, 226, 226)
        Note over TW,PSP: Payment Compensation
        TW->>TW: paymentAuthorized=true, paymentCaptured=false,<br/>paymentCaptureAttempted=false
        TW->>Pay: voidPayment(paymentId, voidKey)
        Pay->>PSP: gateway.void(pspRef, voidKey)
        PSP-->>Pay: voided
        Pay-->>TW: void successful
    end

    rect rgb(254, 243, 199)
        Note over TW,Inv: Saga Compensation (LIFO order)
        TW->>Inv: saga.compensate() → releaseStock(reservationId)
        Inv-->>TW: Stock released
    end

    Note over TW: currentStatus = "FAILED"

    TW-->>TW: Return CheckoutResponse{<br/>status: "FAILED: Order creation error"}
```

## Payment Capture Failure - Refund Fallback Sequence

```mermaid
sequenceDiagram
    autonumber
    participant TW as Temporal<br/>(CheckoutWorkflowImpl)
    participant Inv as InventoryService
    participant Pay as PaymentService
    participant PSP as Payment Gateway
    participant Ord as OrderService

    Note over TW: Steps 1-5 succeeded, order created

    TW->>Inv: ⑥a confirmStock(reservationId)
    Inv-->>TW: Stock confirmed

    TW->>Pay: ⑥b capturePayment(paymentId, captureKey)
    Pay->>PSP: gateway.capture(pspRef, captureKey)
    PSP-->>Pay: ❌ capture_failed (PSP error)
    Pay-->>TW: Exception thrown

    Note over TW: currentStatus = "COMPENSATING"<br/>paymentCaptureAttempted = true

    rect rgb(254, 226, 226)
        Note over TW,PSP: Payment Compensation (try refund first)
        TW->>Pay: refundPayment(paymentId, amountCents, refundKey)
        Pay->>PSP: gateway.refund(pspRef, amount, refundKey)
        PSP-->>Pay: ❌ refund_failed (capture didn't complete)
        TW->>TW: Refund failed, trying void
        TW->>Pay: voidPayment(paymentId, voidKey)
        Pay->>PSP: gateway.void(pspRef, voidKey)
        PSP-->>Pay: voided
        Pay-->>TW: void successful
    end

    rect rgb(254, 243, 199)
        Note over TW,Ord: Saga Compensation (LIFO order)
        TW->>Ord: cancelOrder(orderId)
        Ord-->>TW: Order cancelled
        TW->>Inv: releaseStock(reservationId)
        Inv-->>TW: Stock released
    end

    Note over TW: currentStatus = "FAILED"
```

## Concurrent Checkout Detection Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Client1 as Client 1
    participant Client2 as Client 2
    participant CO as CheckoutController
    participant DB as PostgreSQL
    participant Temporal as Temporal Server

    Client1->>CO: POST /checkout<br/>Idempotency-Key: ik-abc123
    Client2->>CO: POST /checkout<br/>Idempotency-Key: ik-abc123

    par Concurrent requests
        CO->>DB: SELECT idempotency_key
        Note over DB: Key not found
    and
        CO->>DB: SELECT idempotency_key
        Note over DB: Key not found
    end

    CO->>Temporal: workflowClient.start(<br/>workflowId="checkout-user1-ik-abc123")
    Temporal-->>CO: ✅ Workflow started

    CO->>Temporal: workflowClient.start(<br/>workflowId="checkout-user1-ik-abc123")
    Temporal-->>CO: ❌ WorkflowExecutionAlreadyStarted

    CO->>Temporal: Query workflow status
    Temporal-->>CO: status: "AUTHORIZING_PAYMENT"

    CO-->>Client1: 200 OK (workflow in progress)
    CO-->>Client2: 409 CHECKOUT_ALREADY_IN_PROGRESS<br/>{workflowId, status, statusPath}
```

## Workflow Query Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Client as Client
    participant CO as CheckoutController
    participant WfClient as WorkflowClient
    participant Temporal as Temporal Server
    participant WfImpl as CheckoutWorkflowImpl

    Client->>CO: GET /checkout/{workflowId}/status

    CO->>WfClient: newWorkflowStub(CheckoutWorkflow.class, workflowId)
    WfClient-->>CO: Workflow stub created

    CO->>Temporal: Query: getStatus()
    Temporal->>WfImpl: Execute query method
    WfImpl-->>Temporal: currentStatus = "CREATING_ORDER"
    Temporal-->>CO: "CREATING_ORDER"

    CO-->>Client: 200 OK<br/>{workflowId, status: "CREATING_ORDER"}
```

## Idempotency Key Lineage Through Money-Path

```mermaid
sequenceDiagram
    participant Client
    participant CO as CheckoutController
    participant WfImpl as CheckoutWorkflowImpl
    participant PayKeys as PaymentOperationKeys
    participant Pay as PaymentService

    Client->>CO: Idempotency-Key: "ik-abc123"

    CO->>WfImpl: Start workflow<br/>workflowId = "checkout-user1-ik-abc123"

    WfImpl->>PayKeys: new PaymentOperationKeys(workflowId)

    Note over PayKeys: Key derivation (stable across retries)

    WfImpl->>PayKeys: authorize()
    PayKeys-->>WfImpl: "checkout-user1-ik-abc123:pay:auth"
    WfImpl->>Pay: authorizePayment(..., authKey)
    Pay-->>WfImpl: paymentId = "pay_xyz789"

    WfImpl->>PayKeys: capture("pay_xyz789")
    PayKeys-->>WfImpl: "checkout-user1-ik-abc123:pay:pay_xyz789:capture"
    WfImpl->>Pay: capturePayment(paymentId, captureKey)

    Note over PayKeys: On compensation:
    WfImpl->>PayKeys: voidOp("pay_xyz789")
    PayKeys-->>WfImpl: "checkout-user1-ik-abc123:pay:pay_xyz789:void"

    WfImpl->>PayKeys: refund("pay_xyz789")
    PayKeys-->>WfImpl: "checkout-user1-ik-abc123:pay:pay_xyz789:refund"
```

## Temporal Activity Retry Sequence

```mermaid
sequenceDiagram
    autonumber
    participant WfImpl as CheckoutWorkflowImpl
    participant Temporal as Temporal Server
    participant ActImpl as InventoryActivityImpl
    participant InvSvc as InventoryService

    WfImpl->>Temporal: Schedule reserveStock activity

    Temporal->>ActImpl: Execute reserveStock(items)
    ActImpl->>InvSvc: POST /inventory/reserve
    InvSvc-->>ActImpl: ❌ 503 Service Unavailable

    Note over Temporal: Attempt 1 failed (retryable)

    Temporal->>Temporal: Wait 1s (initial interval)

    Temporal->>ActImpl: Execute reserveStock(items)
    ActImpl->>InvSvc: POST /inventory/reserve
    InvSvc-->>ActImpl: ❌ 503 Service Unavailable

    Note over Temporal: Attempt 2 failed (retryable)

    Temporal->>Temporal: Wait 2s (1s × 2.0 backoff)

    Temporal->>ActImpl: Execute reserveStock(items)
    ActImpl->>InvSvc: POST /inventory/reserve
    InvSvc-->>ActImpl: ✅ 200 OK {reservationId, reserved: true}

    ActImpl-->>Temporal: Activity completed
    Temporal-->>WfImpl: InventoryReservationResult
```

## Cart Clear Best-Effort Sequence

```mermaid
sequenceDiagram
    autonumber
    participant WfImpl as CheckoutWorkflowImpl
    participant CartAct as CartActivityImpl
    participant CartSvc as CartService

    Note over WfImpl: Step 6 (confirm + capture) completed

    WfImpl->>WfImpl: currentStatus = "CLEARING_CART"

    WfImpl->>CartAct: clearCart(userId)
    CartAct->>CartSvc: DELETE /cart/{userId}
    CartSvc-->>CartAct: ❌ 500 Internal Server Error

    CartAct-->>WfImpl: Exception thrown

    WfImpl->>WfImpl: catch(Exception e)
    WfImpl->>WfImpl: log.warn("Failed to clear cart for user {}, continuing", userId, e)

    Note over WfImpl: Continue execution<br/>(don't fail checkout)

    WfImpl->>WfImpl: currentStatus = "COMPLETED"
    WfImpl-->>WfImpl: Return CheckoutResponse{COMPLETED, orderId, ...}
```
