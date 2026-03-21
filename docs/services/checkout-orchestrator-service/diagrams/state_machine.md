# Checkout Orchestrator Service - Entity Lifecycle & State Machines

## Temporal Workflow Execution Lifecycle

```mermaid
stateDiagram-v2
    [*] --> WorkflowStarted: POST /checkout

    WorkflowStarted --> ExecutingActivities: Workflow execution began<br/>(idempotent start)

    ExecutingActivities --> CartValidation: Activity 1:<br/>validateCart

    CartValidation --> OrderCreation: ✓ cart valid
    CartValidation --> WorkflowFailed: ✗ cart invalid

    OrderCreation --> PaymentAuth: ✓ order created

    PaymentAuth --> InventoryReserve: ✓ payment authorized
    PaymentAuth --> CompensateOrder: ✗ auth declined

    InventoryReserve --> PaymentCapture: ✓ stock reserved
    InventoryReserve --> CompensatePayment: ✗ inventory unavailable

    PaymentCapture --> InventoryConfirm: ✓ payment captured
    PaymentCapture --> CompensateInventory: ✗ capture failed

    InventoryConfirm --> Completed: ✓ stock confirmed

    CompensateOrder --> CompensatePayment: Release payment<br/>if authorized

    CompensatePayment --> CompensateInventory: Release inventory<br/>if reserved

    CompensateInventory --> WorkflowFailed: Cleanup complete

    Completed --> Events: Publish events<br/>to Kafka

    Events --> WorkflowCompleted: WorkflowStatus=SUCCESS<br/>Execution history saved

    WorkflowFailed --> Events2: Publish error events
    Events2 --> WorkflowCompleted: WorkflowStatus=FAILED<br/>Execution history saved

    WorkflowCompleted --> [*]

    note right of WorkflowStarted
        workflowId = checkout-{user}-{key}
        Prevents duplicate executions
    end note

    note right of ExecutingActivities
        Activities execute in temporal workers
        Deterministic execution model
        State persisted after each activity
    end note

    note right of Completed
        All activities successful
        No compensation needed
    end note

    note right of CompensateOrder
        Reverse order creation
        Mark as CANCELLED
    end note

    note right of WorkflowCompleted
        Execution history immutable
        Queryable via Temporal
        Retention: 30 days (configurable)
    end note
```

## Order Entity Lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING: OrderActivity.createOrder<br/>Called from Checkout Workflow

    PENDING --> PLACED: Payment captured<br/>OrderService receives payment confirmation<br/>from Kafka: PaymentCaptured

    PLACED --> PACKING: Fulfillment service<br/>retrieves order

    PACKING --> PACKED: Warehouse picks items<br/>Publishes PickingCompleted event

    PACKED --> OUT_FOR_DELIVERY: Rider assigned<br/>and starts delivery

    OUT_FOR_DELIVERY --> DELIVERED: Delivery completed<br/>GPS confirmation or<br/>Rider app confirmation

    OUT_FOR_DELIVERY --> CANCELLED: Rider unable to deliver<br/>Max retry attempts exceeded

    PENDING --> CANCELLED: Payment declined or<br/>Inventory unavailable<br/>User cancels before payment<br/>Order cancellation timeout

    PENDING --> FAILED: Order creation failed<br/>Database error

    PLACED --> CANCELLED: User initiates cancellation<br/>(pre-fulfillment)

    CANCELLED --> [*]
    DELIVERED --> [*]
    FAILED --> [*]

    note right of PENDING
        order.status = 'PENDING'
        order.payment_id = NULL
        TTL: 15 minutes (auto-cancel)
    end note

    note right of PLACED
        order.status = 'PLACED'
        order.payment_id = {payment_id}
        order.reservation_id = {reservation_id}
        Triggering event: OrderCreated
    end note

    note right of PACKING
        order.status = 'PACKING'
        Warehouse system picks items
        Stock confirmed (no further decrements)
    end note

    note right of DELIVERED
        order.status = 'DELIVERED'
        Final state: no further updates
        Revenue recognized
    end note

    note right of CANCELLED
        order.status = 'CANCELLED'
        order.cancellation_reason = reason
        Inventory released
        Payment refunded if captured
    end note
```

## Payment Entity Lifecycle

```mermaid
stateDiagram-v2
    [*] --> AUTHORIZED: PaymentActivity.authorizePayment<br/>PSP returns auth token

    AUTHORIZED --> CAPTURED: PaymentActivity.capturePayment<br/>Funds deducted from customer account<br/>Receipt issued by PSP

    AUTHORIZED --> VOIDED: Payment voided before capture<br/>Inventory unavailable<br/>Timeout on next activity<br/>Manual intervention

    CAPTURED --> PARTIALLY_REFUNDED: Customer initiates partial refund<br/>Refund amount < captured amount

    CAPTURED --> REFUNDED: Customer initiates full refund<br/>Refund amount = captured amount

    PARTIALLY_REFUNDED --> REFUNDED: Subsequent refund completes

    AUTHORIZED --> FAILED: PSP decline during authorization<br/>Insufficient funds<br/>Card blocked<br/>Authentication failed

    VOIDED --> [*]
    REFUNDED --> [*]
    FAILED --> [*]
    CAPTURED --> [*]: No refund

    note right of AUTHORIZED
        payment.status = 'AUTHORIZED'
        payment.psp_reference = {psp_ref}
        payment.captured_cents = 0
        Funds held by PSP (5-7 days)
    end note

    note right of CAPTURED
        payment.status = 'CAPTURED'
        payment.captured_cents = {amount}
        Funds available to merchant (1-3 days settlement)
        Revenue recognized for accounting
    end note

    note right of VOIDED
        payment.status = 'VOIDED'
        Authorization reversed
        No funds deducted (hold removed)
        Cannot be captured after void
    end note

    note right of PARTIALLY_REFUNDED
        payment.status = 'PARTIALLY_REFUNDED'
        payment.refunded_cents < captured_cents
        Remaining balance can be refunded
        Example: 100 INR captured, 30 INR refunded
    end note

    note right of REFUNDED
        payment.status = 'REFUNDED'
        payment.refunded_cents = captured_cents
        Funds returned to customer (2-5 business days)
        Final immutable state
    end note
```

## Inventory Reservation Lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING: InventoryActivity.reserveStock<br/>Called from Checkout Workflow<br/>Pessimistic lock acquired

    PENDING --> CONFIRMED: InventoryActivity.confirmStock<br/>Called after payment capture<br/>Stock moved to fulfillment

    PENDING --> CANCELLED: Inventory unavailable<br/>insufficient qty<br/>Timeout on activity<br/>Explicit cancel activity<br/>TTL expired (15 min)

    CONFIRMED --> [*]: Normal end<br/>Order fulfillment proceeds

    CANCELLED --> [*]: Rollback<br/>Stock released to available pool

    note right of PENDING
        reservation.status = 'PENDING'
        reservation.expires_at = now + 15min
        stock_items.reserved += qty (locked)
        stock_items.on_hand -= qty (reserved)
        Prevents overbooking
    end note

    note right of CONFIRMED
        reservation.status = 'CONFIRMED'
        expires_at no longer checked
        Stock locked until fulfillment
        Cannot be cancelled
    end note

    note right of CANCELLED
        reservation.status = 'CANCELLED'
        stock_items.reserved -= qty (unlocked)
        stock_items.on_hand += qty (restored)
        Available for other customers
    end note
```

## Idempotency Key Lifecycle (Database)

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: After successful checkout<br/>INSERT checkout_idempotency_keys<br/>(key, response, expires_at)

    ACTIVE --> ACTIVE: Duplicate request arrives<br/>SELECT returns cached response<br/>TTL checked

    ACTIVE --> EXPIRED: TTL reached<br/>current_timestamp > expires_at<br/>(Default: 30 minutes)

    EXPIRED --> PURGED: Cleanup job<br/>DELETE where expires_at < now<br/>Runs daily

    PURGED --> [*]
    EXPIRED --> [*]: After 30 min

    note right of ACTIVE
        Response cached in PostgreSQL
        checkout_idempotency_keys table
        Prevents duplicate workflows
        Multiple queries within TTL return same result
    end note

    note right of EXPIRED
        TTL: 30 minutes (configurable)
        After expiration, treat as new checkout
        Allows retry after 30 min if needed
    end note

    note right of PURGED
        Reclaim database space
        Daily scheduled cleanup
        Frees up rows with old keys
    end note
```

## Workflow State Persistence in Temporal

```mermaid
graph TB
    subgraph Request["HTTP Request Timeline"]
        T0["T0: Checkout request<br/>idempotency_key=X"]
        T1["T1: Start workflow"]
        T2["T2: Return async response"]
    end

    subgraph Workflow["Workflow Execution (Temporal History)"]
        W0["WorkflowStarted<br/>version=1"]
        W1["Activity1Scheduled<br/>validateCart"]
        W2["Activity1Completed<br/>cart valid"]
        W3["Activity2Scheduled<br/>createOrder"]
        W4["Activity2Completed<br/>order_id=uuid"]
        W5["..."]
        W6["WorkflowCompleted<br/>result=CheckoutResponse"]
    end

    subgraph Recovery["Failure Recovery"]
        F0["Worker crash/restart"]
        F1["Replay workflow history<br/>from immutable log"]
        F2["Resume from last state<br/>(no duplicate side effects)"]
        F3["Complete remaining activities"]
    end

    T0 --> T1
    T1 --> W0
    W0 --> W1
    W1 --> W2
    W2 --> W3
    W3 --> W4
    W4 --> W5
    W5 --> W6
    T1 --> T2

    W6 --> Recovery
    F0 --> F1
    F1 --> F2
    F2 --> F3
    F3 --> W6

    style W0 fill:#ffffcc
    style W2 fill:#90EE90
    style W4 fill:#90EE90
    style W6 fill:#90EE90
    style F1 fill:#ffcccc
    style F2 fill:#ffcccc
```

## Key Lifecycle Rules

| Entity | Initial State | Final States | TTL/Timeout | Notes |
|--------|---|---|---|---|
| **Workflow** | Started | Completed, Failed | 5 min | Replayed on worker recovery |
| **Order** | PENDING | DELIVERED, CANCELLED, FAILED | 15 min (auto-cancel) | Status immutable after PLACED |
| **Payment** | AUTHORIZED | CAPTURED, VOIDED, REFUNDED, FAILED | N/A | 180-day audit trail required |
| **Reservation** | PENDING | CONFIRMED, CANCELLED | 15 min (auto-release) | Prevents stock overbooking |
| **Idempotency Key** | ACTIVE | EXPIRED | 30 min | After expiration, new checkout allowed |
