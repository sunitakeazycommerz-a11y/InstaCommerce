# Fulfillment Service - State Machine Diagram

## PickTask State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: OrderCreated event<br/>PickTask created

    PENDING --> IN_PROGRESS: markItem() called<br/>First item picked by picker
    PENDING --> PENDING: markItem() idempotent<br/>Same item marked again

    IN_PROGRESS --> IN_PROGRESS: markItem()<br/>More items being picked

    IN_PROGRESS --> COMPLETED: All items picked OR<br/>markPacked() explicit
    PENDING --> COMPLETED: markPacked() with<br/>all items ready

    COMPLETED --> [*]: OrderPacked event published<br/>Delivery phase starts

    IN_PROGRESS --> CANCELLED: OrderCancelled event<br/>or manual cancellation
    PENDING --> CANCELLED: Order cancelled<br/>before picking starts
    CANCELLED --> [*]: Inventory released

    note right of PENDING
        Initial state when order arrives.
        No items picked yet.
        Can accept marking of items.
    end note

    note right of IN_PROGRESS
        At least one item has been picked.
        Picker actively working on order.
        Substitutions may be triggered.
    end note

    note right of COMPLETED
        All items picked and packed.
        OrderPacked event published.
        Delivery assignment initiated.
    end note

    note right of CANCELLED
        Order cancelled by customer or system.
        Inventory reservations released.
        Pick task abandoned.
    end note
```

## PickItem State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: Order item added<br/>to pick list

    PENDING --> PICKED: Picker marks item<br/>as successfully picked<br/>qty = ordered qty

    PENDING --> SUBSTITUTED: Item out of stock,<br/>alternative selected

    PENDING --> MISSING: Item cannot be filled<br/>Substitution search fails

    PENDING --> NOT_AVAILABLE: Item temporarily<br/>unavailable (hold)

    PICKED --> [*]: Included in order<br/>Ready for delivery

    SUBSTITUTED --> [*]: Alternative included<br/>in shipment

    MISSING --> [*]: Customer notified<br/>Refund processed

    NOT_AVAILABLE --> PENDING: Item available again<br/>Reset to PENDING

    NOT_AVAILABLE --> MISSING: Timeout exceeded<br/>Mark as missing

    note right of PENDING
        Waiting for picker to process.
        Can be marked as picked, substituted,
        or missing.
    end note

    note right of PICKED
        Successfully picked by warehouse staff.
        Quantity = ordered quantity.
    end note

    note right of SUBSTITUTED
        Alternative product chosen.
        SubstitutionService found replacement.
        May have price adjustment.
    end note

    note right of MISSING
        Cannot be fulfilled.
        No alternatives available.
        Refund triggered.
    end note

    note right of NOT_AVAILABLE
        Temporary hold state.
        Item may become available.
        Or escalate to MISSING after timeout.
    end note
```

## Delivery State Machine

```mermaid
stateDiagram-v2
    [*] --> CREATED: PickTask completed<br/>OrderPacked published

    CREATED --> ASSIGNED: Dispatch matches<br/>order to nearby rider

    ASSIGNED --> IN_PROGRESS: Rider picks up<br/>package from store

    IN_PROGRESS --> IN_TRANSIT: Rider starts<br/>traveling to delivery address

    IN_TRANSIT --> OUT_FOR_DELIVERY: Rider near<br/>destination

    OUT_FOR_DELIVERY --> DELIVERED: Delivery confirmed<br/>by customer/OTP

    OUT_FOR_DELIVERY --> DELIVERY_FAILED: Customer not available<br/>or refused delivery

    DELIVERY_FAILED --> REATTEMPT: Dispatch schedules<br/>next delivery attempt

    REATTEMPT --> IN_PROGRESS: New rider assigned<br/>for reattempt

    IN_PROGRESS --> CANCELLED: Order cancelled<br/>before delivery

    DELIVERED --> [*]: Delivery complete
    CANCELLED --> [*]: Order cancelled

    note right of CREATED
        Initial state when PickTask completes.
        Waiting for rider assignment.
    end note

    note right of ASSIGNED
        Rider selected and assigned.
        Rider notified.
        ETA calculated.
    end note

    note right of IN_PROGRESS
        Rider has picked up package.
        On way to customer location.
    end note

    note right of IN_TRANSIT
        Rider moving towards destination.
        Location updates streamed.
    end note

    note right of OUT_FOR_DELIVERY
        Rider at or near delivery address.
        Sending OTP to customer.
    end note

    note right of DELIVERED
        Package delivered and accepted.
        Order fulfillment complete.
    end note

    note right of DELIVERY_FAILED
        Delivery attempt unsuccessful.
        Reattempt or customer service required.
    end note
```

## Concurrent PickItem Processing

```mermaid
stateDiagram-v2
    state "PickTask IN_PROGRESS" as task_in_progress {
        [*] --> item1["Item 1: PENDING"]
        [*] --> item2["Item 2: PENDING"]
        [*] --> item3["Item 3: PENDING"]

        item1 --> item1_picked["Item 1: PICKED"]
        item2 --> item2_sub["Item 2: SUBSTITUTED"]
        item3 --> item3_missing["Item 3: MISSING"]

        item1_picked --> check1{"All PICKED<br/>or SUBSTITUTED<br/>or MISSING?"}
        item2_sub --> check1
        item3_missing --> check1

        check1 -->|Yes| [*]
        check1 -->|No| waiting["Waiting for<br/>remaining items"]
        waiting --> item1["Item 1 already done"]
    }

    task_in_progress --> task_completed["PickTask: COMPLETED"]
```

## Error State Recovery

```mermaid
stateDiagram-v2
    IN_PROGRESS --> ERROR: Exception thrown<br/>during processing

    ERROR --> RETRY: Can retry?<br/>Transient error?

    RETRY --> PENDING: Reset to PENDING<br/>for manual retry

    RETRY --> IN_PROGRESS: Auto-retry<br/>succeeded

    RETRY --> MANUAL_REVIEW: Manual review<br/>required

    MANUAL_REVIEW --> [*]: Ops team<br/>resolves issue

    note right of ERROR
        Exception in marking item,
        database constraint violation,
        or service dependency failure.
    end note

    note right of RETRY
        Determine if transient.
        Exponential backoff.
        Max 3 attempts.
    end note

    note right of MANUAL_REVIEW
        Escalate to warehouse ops.
        Manual resolution required.
    end note
```
