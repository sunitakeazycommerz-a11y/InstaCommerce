# Checkout Orchestrator - State Machine Diagrams

## Checkout Workflow State Machine (Complete Lifecycle)

```mermaid
stateDiagram-v2
    [*] --> STARTED: POST /checkout received<br/>Temporal workflow started

    STARTED --> VALIDATING_CART: Begin saga execution<br/>① Cart validation

    VALIDATING_CART --> FAILED: Cart empty or invalid<br/>(no compensation needed)
    VALIDATING_CART --> CALCULATING_PRICES: Cart valid<br/>② Start pricing

    CALCULATING_PRICES --> FAILED: Pricing error<br/>(no compensation needed)
    CALCULATING_PRICES --> RESERVING_INVENTORY: Prices calculated<br/>③ Reserve stock

    RESERVING_INVENTORY --> FAILED: Out of stock<br/>(InsufficientStockException)<br/>(no compensation needed)
    RESERVING_INVENTORY --> AUTHORIZING_PAYMENT: Stock reserved<br/>Register releaseStock compensation<br/>④ Authorize payment

    AUTHORIZING_PAYMENT --> COMPENSATING: Payment declined<br/>(PaymentDeclinedException)
    AUTHORIZING_PAYMENT --> CREATING_ORDER: Payment authorized<br/>⑤ Create order

    CREATING_ORDER --> COMPENSATING: Order creation failed<br/>Exception thrown
    CREATING_ORDER --> REDEEMING_COUPON: Order created<br/>Register cancelOrder compensation<br/>⑤b Redeem coupon (if applicable)

    REDEEMING_COUPON --> COMPENSATING: Coupon redemption failed<br/>(optional step)
    REDEEMING_COUPON --> CONFIRMING: Coupon redeemed (or skipped)<br/>⑥ Confirm stock + Capture payment

    CONFIRMING --> COMPENSATING: Confirm/Capture failed<br/>Exception thrown
    CONFIRMING --> CLEARING_CART: Stock confirmed + Payment captured<br/>⑦ Clear cart (best effort)

    CLEARING_CART --> COMPLETED: Cart cleared (or error swallowed)<br/>Return success response

    COMPENSATING --> FAILED: All compensations executed<br/>(LIFO order)

    COMPLETED --> [*]: CheckoutResponse{COMPLETED}

    FAILED --> [*]: CheckoutResponse{FAILED: reason}

    note right of VALIDATING_CART
        Activity timeout: 10s
        Max attempts: 3
    end note

    note right of AUTHORIZING_PAYMENT
        Activity timeout: 30s/45s
        DoNotRetry: PaymentDeclinedException
    end note

    note right of COMPENSATING
        compensatePayment() runs first
        Then saga.compensate() (LIFO)
    end note
```

## Saga Compensation State Machine

```mermaid
stateDiagram-v2
    [*] --> EXCEPTION_THROWN: Exception during saga execution

    EXCEPTION_THROWN --> CHECK_PAYMENT: Check payment state

    state CHECK_PAYMENT {
        [*] --> PAYMENT_AUTHORIZED: paymentAuthorized?
        PAYMENT_AUTHORIZED --> NO_PAYMENT_COMP: false
        PAYMENT_AUTHORIZED --> CHECK_AMOUNT: true

        CHECK_AMOUNT --> SKIP_REFUND: amountCents null or <= 0
        CHECK_AMOUNT --> CHECK_CAPTURED: amountCents > 0

        CHECK_CAPTURED --> DO_REFUND: paymentCaptured = true
        CHECK_CAPTURED --> CHECK_CAPTURE_ATTEMPTED: paymentCaptured = false

        CHECK_CAPTURE_ATTEMPTED --> TRY_REFUND_THEN_VOID: captureAttempted = true
        CHECK_CAPTURE_ATTEMPTED --> DO_VOID: captureAttempted = false

        DO_REFUND --> PAYMENT_COMP_DONE: refundPayment()
        DO_VOID --> PAYMENT_COMP_DONE: voidPayment()
        TRY_REFUND_THEN_VOID --> REFUND_RESULT: try refundPayment()
        REFUND_RESULT --> PAYMENT_COMP_DONE: success
        REFUND_RESULT --> DO_VOID_FALLBACK: failure
        DO_VOID_FALLBACK --> PAYMENT_COMP_DONE: voidPayment()
        SKIP_REFUND --> PAYMENT_COMP_DONE: log warning
        NO_PAYMENT_COMP --> PAYMENT_COMP_DONE: skip
    }

    PAYMENT_COMP_DONE --> SAGA_COMPENSATE: Run saga.compensate()

    state SAGA_COMPENSATE {
        [*] --> CANCEL_ORDER: if order created
        CANCEL_ORDER --> RELEASE_STOCK: cancelOrder(orderId)
        RELEASE_STOCK --> UNREDEM_COUPON: releaseStock(reservationId)
        UNREDEM_COUPON --> [*]: unredeemCoupon() (if redeemed)
        RELEASE_STOCK --> [*]: (if no coupon)
    }

    SAGA_COMPENSATE --> RETURN_FAILED: All compensations complete

    RETURN_FAILED --> [*]: CheckoutResponse{FAILED}
```

## Payment State Machine (Within Checkout Context)

```mermaid
stateDiagram-v2
    [*] --> NOT_STARTED: paymentAuthorized=false<br/>paymentCaptured=false

    NOT_STARTED --> AUTHORIZING: authorizePayment() called

    AUTHORIZING --> AUTHORIZED: PaymentAuthResult{authorized=true}<br/>paymentAuthorized=true
    AUTHORIZING --> DECLINED: PaymentAuthResult{authorized=false}<br/>PaymentDeclinedException
    AUTHORIZING --> AUTH_ERROR: Exception thrown

    AUTHORIZED --> CAPTURING: capturePayment() called<br/>paymentCaptureAttempted=true

    CAPTURING --> CAPTURED: Capture successful<br/>paymentCaptured=true
    CAPTURING --> CAPTURE_FAILED: Capture exception

    DECLINED --> COMPENSATION_NOT_NEEDED: No payment to compensate

    AUTH_ERROR --> COMPENSATION_NOT_NEEDED: Authorization never completed

    state COMPENSATION_ZONE {
        CAPTURE_FAILED --> TRY_REFUND: captureAttempted=true
        TRY_REFUND --> REFUNDED: refund successful
        TRY_REFUND --> REFUND_FAILED: refund failed
        REFUND_FAILED --> VOIDED: void successful
        AUTHORIZED --> VOIDED: voidPayment() on<br/>non-capture failure
        CAPTURED --> REFUNDED: refundPayment() on<br/>post-capture failure
    }

    CAPTURED --> [*]: Happy path complete

    VOIDED --> [*]: Payment undone
    REFUNDED --> [*]: Payment undone
    COMPENSATION_NOT_NEEDED --> [*]: Continue saga compensation
```

## Temporal Workflow Execution State Machine

```mermaid
stateDiagram-v2
    [*] --> WORKFLOW_QUEUED: workflowClient.start()

    WORKFLOW_QUEUED --> WORKFLOW_RUNNING: Worker picks up task

    state WORKFLOW_RUNNING {
        [*] --> EXECUTING_ACTIVITY: Schedule activity

        EXECUTING_ACTIVITY --> ACTIVITY_COMPLETED: Activity success
        EXECUTING_ACTIVITY --> ACTIVITY_RETRYING: Retryable error

        ACTIVITY_RETRYING --> EXECUTING_ACTIVITY: Backoff elapsed
        ACTIVITY_RETRYING --> ACTIVITY_FAILED: Max attempts exceeded<br/>or non-retryable

        ACTIVITY_COMPLETED --> NEXT_ACTIVITY: More steps
        ACTIVITY_COMPLETED --> [*]: Saga complete

        ACTIVITY_FAILED --> [*]: Trigger compensation
    }

    WORKFLOW_RUNNING --> WORKFLOW_COMPLETED: Saga returns success
    WORKFLOW_RUNNING --> WORKFLOW_FAILED: Saga returns failure
    WORKFLOW_RUNNING --> WORKFLOW_TIMED_OUT: 5min execution timeout

    WORKFLOW_COMPLETED --> [*]
    WORKFLOW_FAILED --> [*]
    WORKFLOW_TIMED_OUT --> [*]

    note right of ACTIVITY_RETRYING
        Wait: interval × backoff^attempt
        CartActivity: 1s × 2.0
        PaymentActivity: 2s × 2.0
    end note

    note right of WORKFLOW_TIMED_OUT
        Workflow execution timeout: 5min
        Set via WorkflowOptions
    end note
```

## Idempotency Key State Machine

```mermaid
stateDiagram-v2
    [*] --> KEY_RECEIVED: Idempotency-Key header

    KEY_RECEIVED --> KEY_ABSENT: No header provided
    KEY_RECEIVED --> KEY_PROVIDED: Header present

    KEY_ABSENT --> KEY_GENERATED: Generate UUID

    KEY_PROVIDED --> DB_LOOKUP: Query checkout_idempotency_keys
    KEY_GENERATED --> DB_LOOKUP: Query checkout_idempotency_keys

    DB_LOOKUP --> KEY_NOT_FOUND: No matching row
    DB_LOOKUP --> KEY_FOUND: Row exists

    KEY_FOUND --> KEY_EXPIRED: expires_at < now()
    KEY_FOUND --> KEY_VALID: expires_at >= now()

    KEY_VALID --> RETURN_CACHED: Return cached CheckoutResponse<br/>200 OK (fast path)

    KEY_NOT_FOUND --> START_WORKFLOW: Start Temporal workflow
    KEY_EXPIRED --> DELETE_EXPIRED: Delete expired row
    DELETE_EXPIRED --> START_WORKFLOW: Start fresh

    START_WORKFLOW --> WORKFLOW_COMPLETE: Saga completes

    WORKFLOW_COMPLETE --> PERSIST_KEY: INSERT idempotency key<br/>response + expires_at

    PERSIST_KEY --> KEY_STORED: Stored for 30 minutes

    KEY_STORED --> RETURN_CACHED: Subsequent requests<br/>return cached response

    KEY_STORED --> KEY_EXPIRING: TTL counting down

    KEY_EXPIRING --> KEY_EXPIRED_CLEANUP: > 24 hours past expiry

    KEY_EXPIRED_CLEANUP --> KEY_DELETED: IdempotencyKeyCleanupJob<br/>deletes row

    RETURN_CACHED --> [*]
    KEY_DELETED --> [*]

    note right of KEY_STORED
        TTL: 30 minutes
        Cleanup: 24h after expiry
    end note
```

## Activity Retry State Machine

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED: Temporal schedules activity

    SCHEDULED --> EXECUTING: Worker picks up activity task

    EXECUTING --> SUCCESS: Activity completes normally
    EXECUTING --> ERROR: Exception thrown

    ERROR --> CHECK_RETRYABLE: Evaluate exception

    CHECK_RETRYABLE --> NON_RETRYABLE: Exception in DoNotRetry list
    CHECK_RETRYABLE --> RETRYABLE: Not in DoNotRetry list

    NON_RETRYABLE --> FAILED: Activity fails immediately

    RETRYABLE --> CHECK_ATTEMPTS: attempts < maxAttempts?

    CHECK_ATTEMPTS --> BACKOFF: Yes, more attempts allowed
    CHECK_ATTEMPTS --> FAILED: No, max attempts reached

    BACKOFF --> WAITING: Wait interval × backoff^attempt

    WAITING --> SCHEDULED: Retry scheduled

    SUCCESS --> [*]
    FAILED --> [*]

    note right of NON_RETRYABLE
        Inventory: InsufficientStockException
        Payment: PaymentDeclinedException
        Coupon: InvalidCouponException,
                CouponNotFoundException
    end note

    note right of BACKOFF
        Cart/Pricing/Order: 1s, 2s, 4s
        Payment: 2s, 4s, 8s
    end note
```

## Checkout Status Query State Machine

```mermaid
stateDiagram-v2
    [*] --> REQUEST_RECEIVED: GET /checkout/{workflowId}/status

    REQUEST_RECEIVED --> CREATE_STUB: newWorkflowStub(workflowId)

    CREATE_STUB --> QUERY_STATUS: Query getStatus()

    QUERY_STATUS --> STATUS_RETURNED: Workflow exists
    QUERY_STATUS --> WORKFLOW_NOT_FOUND: Workflow doesn't exist

    STATUS_RETURNED --> RETURN_STATUS: Return current status

    state RETURN_STATUS {
        STARTED: Workflow just started
        VALIDATING_CART: ① Validating cart
        CALCULATING_PRICES: ② Calculating prices
        RESERVING_INVENTORY: ③ Reserving inventory
        AUTHORIZING_PAYMENT: ④ Authorizing payment
        CREATING_ORDER: ⑤ Creating order
        REDEEMING_COUPON: ⑤b Redeeming coupon
        CONFIRMING: ⑥ Confirming + capturing
        CLEARING_CART: ⑦ Clearing cart
        COMPLETED: ✅ Success
        COMPENSATING: ⚠️ Rolling back
        FAILED: ❌ Failed
    }

    WORKFLOW_NOT_FOUND --> RETURN_404: 404 WORKFLOW_NOT_FOUND

    RETURN_STATUS --> [*]
    RETURN_404 --> [*]
```

## ShedLock Job State Machine

```mermaid
stateDiagram-v2
    [*] --> SCHEDULER_TRIGGER: @Scheduled(fixedRate=3600000)<br/>Hourly trigger

    SCHEDULER_TRIGGER --> ACQUIRE_LOCK: Try ShedLock acquisition

    ACQUIRE_LOCK --> LOCK_ACQUIRED: Lock available
    ACQUIRE_LOCK --> LOCK_HELD: Another instance holds lock

    LOCK_HELD --> SKIP_EXECUTION: Skip this run

    LOCK_ACQUIRED --> QUERY_EXPIRED: SELECT WHERE expires_at < now() - 24h

    QUERY_EXPIRED --> NO_EXPIRED_KEYS: Count = 0
    QUERY_EXPIRED --> EXPIRED_KEYS_FOUND: Count > 0

    NO_EXPIRED_KEYS --> RELEASE_LOCK: No work to do

    EXPIRED_KEYS_FOUND --> DELETE_BATCH: DELETE expired keys<br/>(batch of 1000)

    DELETE_BATCH --> LOG_CLEANUP: Log "Cleaned N keys"

    LOG_CLEANUP --> MORE_KEYS: More to delete?
    MORE_KEYS --> DELETE_BATCH: Yes
    MORE_KEYS --> RELEASE_LOCK: No

    RELEASE_LOCK --> [*]: ShedLock released

    SKIP_EXECUTION --> [*]

    note right of LOCK_ACQUIRED
        lockAtLeastFor: PT5M
        lockAtMostFor: PT30M
    end note
```
