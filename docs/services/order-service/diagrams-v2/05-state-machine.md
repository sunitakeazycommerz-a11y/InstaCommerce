# Order Service - State Machine Diagrams

## Order Lifecycle State Machine (Complete)

```mermaid
stateDiagram-v2
    [*] --> PENDING: Order created<br/>via Checkout Orchestrator

    PENDING --> PLACED: Payment confirmed<br/>updateOrderStatus()
    PENDING --> FAILED: Payment failed<br/>or validation error
    PENDING --> CANCELLED: User/admin cancels<br/>before confirmation

    PLACED --> PACKING: Fulfillment started<br/>FulfillmentStarted event
    PLACED --> CANCELLED: User cancels<br/>before packing starts

    PACKING --> PACKED: Items picked & packed<br/>FulfillmentPacked event
    PACKING --> CANCELLED: Admin cancels<br/>(stock issue, fraud)

    PACKED --> OUT_FOR_DELIVERY: Rider assigned<br/>RiderAssigned event
    PACKED --> CANCELLED: Admin cancels<br/>(quality issue)

    OUT_FOR_DELIVERY --> DELIVERED: Delivery confirmed<br/>DeliveryCompleted event

    DELIVERED --> [*]: Terminal state<br/>(order complete)
    CANCELLED --> [*]: Terminal state<br/>(order cancelled)
    FAILED --> [*]: Terminal state<br/>(order failed)

    note right of PENDING
        Initial state
        Waiting for payment
        Can be cancelled
    end note

    note right of PLACED
        Payment successful
        Order confirmed
        Awaiting fulfillment
    end note

    note right of PACKING
        Store is picking items
        Cannot be cancelled by user
    end note

    note right of OUT_FOR_DELIVERY
        Rider en route
        Customer can track
    end note

    note right of DELIVERED
        Order complete
        Refund window starts
    end note

    note right of CANCELLED
        Full/partial refund
        Inventory released
    end note
```

## State Transition Matrix

```mermaid
graph TB
    subgraph legend["Legend"]
        Valid["✅ Valid Transition"]
        Invalid["❌ Invalid"]
    end

    subgraph matrix["Transition Matrix"]
        Matrix["
        FROM → TO         PENDING  PLACED  PACKING  PACKED  OUT_FOR_DELIVERY  DELIVERED  CANCELLED  FAILED
        ────────────────  ───────  ──────  ───────  ──────  ────────────────  ─────────  ─────────  ──────
        PENDING              -       ✅       ❌       ❌           ❌             ❌          ✅        ✅
        PLACED               ❌       -       ✅       ❌           ❌             ❌          ✅        ❌
        PACKING              ❌       ❌       -       ✅           ❌             ❌          ✅        ❌
        PACKED               ❌       ❌       ❌        -           ✅             ❌          ✅        ❌
        OUT_FOR_DELIVERY     ❌       ❌       ❌       ❌            -             ✅          ❌        ❌
        DELIVERED            ❌       ❌       ❌       ❌           ❌              -          ❌        ❌
        CANCELLED            ❌       ❌       ❌       ❌           ❌             ❌           -        ❌
        FAILED               ❌       ❌       ❌       ❌           ❌             ❌          ❌         -
        "]
    end

    style matrix fill:#E8F5E9,color:#000
```

## OrderStateMachine Implementation

```mermaid
graph TD
    A["OrderStateMachine.validate(from, to)"]

    B["TRANSITIONS Map Lookup"]

    subgraph transitions["Allowed Transitions Map"]
        T1["PENDING → {PLACED, FAILED, CANCELLED}"]
        T2["PLACED → {PACKING, CANCELLED}"]
        T3["PACKING → {PACKED, CANCELLED}"]
        T4["PACKED → {OUT_FOR_DELIVERY, CANCELLED}"]
        T5["OUT_FOR_DELIVERY → {DELIVERED}"]
        T6["DELIVERED → {} (terminal)"]
        T7["CANCELLED → {} (terminal)"]
        T8["FAILED → {} (terminal)"]
    end

    C{"to ∈<br/>allowedSet?"}

    D["✅ Transition allowed<br/>(return void)"]
    E["❌ InvalidOrderStateException<br/>Cannot transition from X to Y"]

    A --> B
    B --> T1
    B --> T2
    B --> T3
    B --> T4
    B --> T5
    B --> T6
    B --> T7
    B --> T8

    T1 --> C
    T2 --> C
    T3 --> C
    T4 --> C
    T5 --> C
    T6 --> C
    T7 --> C
    T8 --> C

    C -->|Yes| D
    C -->|No| E

    style D fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style E fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
```

## User vs Admin Cancellation Rules

```mermaid
stateDiagram-v2
    [*] --> CheckRole: Cancel request received

    CheckRole --> UserCancel: User (customer)
    CheckRole --> AdminCancel: Admin/System

    state UserCancel {
        [*] --> CheckUserState
        CheckUserState --> Allowed: Status ∈ {PENDING, PLACED}
        CheckUserState --> Denied: Status ∈ {PACKING, PACKED,<br/>OUT_FOR_DELIVERY}
        Allowed --> Cancelled: ✅ User can cancel
        Denied --> Rejected: ❌ 409 Conflict<br/>Only before packing
    }

    state AdminCancel {
        [*] --> CheckAdminState
        CheckAdminState --> AdminAllowed: Status ∈ {PENDING, PLACED,<br/>PACKING, PACKED}
        CheckAdminState --> AdminDenied: Status == OUT_FOR_DELIVERY
        AdminAllowed --> Cancelled: ✅ Admin can cancel
        AdminDenied --> AdminRejected: ❌ Cannot cancel<br/>during delivery
    }

    Cancelled --> [*]
    Rejected --> [*]
    AdminRejected --> [*]

    note right of UserCancel
        Users can only cancel
        before store starts
        picking items
    end note

    note right of AdminCancel
        Admins can cancel
        up until rider
        picks up order
    end note
```

## Fulfillment Event Processing State Machine

```mermaid
stateDiagram-v2
    [*] --> EventReceived: Kafka event consumed

    EventReceived --> FetchOrder: Parse orderId,<br/>targetStatus

    FetchOrder --> CheckTerminal: Order loaded

    CheckTerminal --> IgnoreTerminal: Status ∈ {CANCELLED,<br/>FAILED, DELIVERED}
    CheckTerminal --> CheckPending: Status not terminal

    IgnoreTerminal --> [*]: Log: Ignoring event<br/>for terminal state

    CheckPending --> RejectPending: Status == PENDING
    CheckPending --> CheckProgress: Status != PENDING

    RejectPending --> [*]: Throw InvalidOrderStateException<br/>Cannot apply fulfillment<br/>while PENDING

    CheckProgress --> CheckAlreadyPast: Compare lifecycle rank

    CheckAlreadyPast --> IgnoreStale: Already at or past target
    CheckAlreadyPast --> CalculatePath: Can progress

    IgnoreStale --> [*]: Log: Ignoring stale event

    CalculatePath --> ApplyTransitions: Build progression path

    state ApplyTransitions {
        [*] --> NextStatus
        NextStatus --> Validate: Get next status in path
        Validate --> Update: StateMachine.validate() ✓
        Update --> Record: Update order status
        Record --> Publish: Record history
        Publish --> CheckMore: Publish event
        CheckMore --> NextStatus: More statuses
        CheckMore --> Done: Path complete
    }

    ApplyTransitions --> [*]: Order advanced

    note right of CalculatePath
        Example: Target = PACKED
        Current = PLACED
        Path = [PACKING, PACKED]
    end note
```

## Order Status History Recording

```mermaid
stateDiagram-v2
    [*] --> StatusChange: Status transition<br/>validated

    StatusChange --> CreateHistory: recordStatusChange()

    CreateHistory --> SetFields: Create OrderStatusHistory entity

    SetFields --> SetOrder: Set order reference
    SetOrder --> SetFrom: Set fromStatus<br/>(previous state)
    SetFrom --> SetTo: Set toStatus<br/>(new state)
    SetTo --> SetChangedBy: Set changedBy<br/>(user:UUID, admin:UUID,<br/>system, fulfillment)
    SetChangedBy --> SetNote: Set note<br/>(reason/context)
    SetNote --> SetTimestamp: Set createdAt<br/>(current timestamp)

    SetTimestamp --> SaveHistory: statusHistoryRepository.save()

    SaveHistory --> [*]: History recorded

    note right of SetChangedBy
        changedBy examples:
        - "user:abc123" (customer)
        - "admin:xyz789" (staff)
        - "system" (automated)
        - "fulfillment" (via event)
        - "workflow" (Temporal)
    end note
```

## Terminal States and Cleanup

```mermaid
stateDiagram-v2
    [*] --> Active: Order in progress

    state Active {
        PENDING --> PLACED
        PLACED --> PACKING
        PACKING --> PACKED
        PACKED --> OUT_FOR_DELIVERY
        OUT_FOR_DELIVERY --> DELIVERED
    }

    Active --> Terminal: Reach terminal state

    state Terminal {
        state DELIVERED {
            [*] --> RefundWindow: Start 7-day window
            RefundWindow --> Finalized: Window closed
        }

        state CANCELLED {
            [*] --> ProcessRefund: Initiate refund
            ProcessRefund --> ReleaseInventory: Release reserved stock
            ReleaseInventory --> NotifyUser: Send cancellation notice
            NotifyUser --> Closed: Cleanup complete
        }

        state FAILED {
            [*] --> LogFailure: Record failure reason
            LogFailure --> AlertOps: Alert if pattern detected
            AlertOps --> Closed: Mark for analysis
        }
    }

    note right of Terminal
        Terminal states:
        - No further transitions
        - Cleanup actions triggered
        - Analytics recorded
    end note
```

## Idempotent State Transitions

```mermaid
stateDiagram-v2
    [*] --> RequestReceived: updateOrderStatus<br/>(orderId, newStatus)

    RequestReceived --> FetchOrder: Load order from DB

    FetchOrder --> CompareStatus: Get currentStatus

    CompareStatus --> AlreadyAtTarget: current == newStatus
    CompareStatus --> NeedTransition: current != newStatus

    AlreadyAtTarget --> [*]: Return early<br/>(no-op, idempotent)

    NeedTransition --> ValidateTransition: OrderStateMachine.validate

    ValidateTransition --> TransitionInvalid: Not allowed
    ValidateTransition --> TransitionValid: Allowed

    TransitionInvalid --> [*]: Throw InvalidOrderStateException

    TransitionValid --> ApplyUpdate: Update status

    ApplyUpdate --> SaveOrder: Save to DB

    SaveOrder --> RecordHistory: Record status change

    RecordHistory --> PublishEvent: Publish to outbox

    PublishEvent --> [*]: Transition complete

    note right of AlreadyAtTarget
        Idempotent behavior:
        If already at target,
        return success without
        making changes
    end note
```

## Lifecycle Rank Comparison

```mermaid
graph TD
    A["lifecycleRank(status)"]

    subgraph ranks["Status Ranks"]
        R1["PENDING: MIN_VALUE<br/>(not in fulfillment lifecycle)"]
        R2["PLACED: 1"]
        R3["PACKING: 2"]
        R4["PACKED: 3"]
        R5["OUT_FOR_DELIVERY: 4"]
        R6["DELIVERED: 5"]
        R7["CANCELLED/FAILED: MIN_VALUE"]
    end

    B["hasReachedLifecycleStep(current, target)"]
    C["lifecycleRank(current) >= lifecycleRank(target)"]
    D{{"Result"}}
    E["true: Skip (already at/past)"]
    F["false: Apply transition"]

    A --> ranks
    ranks --> B
    B --> C --> D
    D -->|rank(current) >= rank(target)| E
    D -->|rank(current) < rank(target)| F

    note1["Used to prevent<br/>stale fulfillment events<br/>from reversing progress"]
    B -.-> note1

    style E fill:#F5A623,color:#000
    style F fill:#7ED321,color:#000
```
