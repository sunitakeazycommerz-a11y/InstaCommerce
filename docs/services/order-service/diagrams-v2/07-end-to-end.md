# Order Service - End-to-End Flows

## Complete Cart-to-Delivery Flow

```mermaid
graph TB
    Customer["👤 Customer"]
    MobileApp["📱 Mobile App"]
    BFF["📱 Mobile BFF"]
    Cart["🛒 Cart Service"]
    Pricing["💰 Pricing Service"]
    Checkout["🎯 Checkout Orchestrator<br/>(Temporal)"]
    Inventory["📦 Inventory Service"]
    Payment["💳 Payment Service"]
    Order["📋 Order Service"]
    Fulfillment["🏪 Fulfillment Service"]
    Rider["🏍️ Rider Fleet"]
    Notification["📬 Notification Service"]
    Kafka["📬 Kafka"]

    Customer -->|1. Add items| MobileApp
    MobileApp -->|2. Update cart| BFF
    BFF -->|3. Store items| Cart

    Customer -->|4. Checkout| MobileApp
    MobileApp -->|5. Get quote| BFF
    BFF -->|6. Calculate pricing| Pricing
    Pricing -->|7. Quote + token| BFF

    MobileApp -->|8. Confirm order| BFF
    BFF -->|9. Start workflow| Checkout

    Checkout -->|10. Reserve stock| Inventory
    Inventory -->|11. Reservation ID| Checkout

    Checkout -->|12. Process payment| Payment
    Payment -->|13. Payment ID| Checkout

    Checkout -->|14. Create order| Order
    Order -->|15. Order ID| Checkout

    Checkout -->|16. Update → PLACED| Order
    Order -->|17. OrderPlaced| Kafka

    Kafka -->|18. Start fulfillment| Fulfillment
    Fulfillment -->|19. FulfillmentStarted| Kafka
    Kafka -->|20. → PACKING| Order

    Fulfillment -->|21. FulfillmentPacked| Kafka
    Kafka -->|22. → PACKED| Order

    Fulfillment -->|23. Assign rider| Rider
    Rider -->|24. RiderAssigned| Kafka
    Kafka -->|25. → OUT_FOR_DELIVERY| Order

    Rider -->|26. Complete delivery| Rider
    Rider -->|27. DeliveryCompleted| Kafka
    Kafka -->|28. → DELIVERED| Order

    Order -->|29. OrderDelivered| Kafka
    Kafka -->|30. Send confirmation| Notification
    Notification -->|31. Push notification| Customer

    style Order fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style Checkout fill:#9013FE,color:#fff
    style Kafka fill:#50E3C2,color:#000
    style Customer fill:#F5A623,color:#000
```

## Order Lifecycle Timeline

```mermaid
gantt
    title Order Lifecycle Timeline
    dateFormat  HH:mm
    axisFormat  %H:%M

    section Customer
    Browse & Add to Cart       :c1, 10:00, 10m
    Review Cart                :c2, after c1, 2m
    Checkout & Pay             :c3, after c2, 3m
    Track Order                :c4, after c3, 90m

    section Order Service
    Order Created (PENDING)    :o1, 10:12, 1m
    Order Confirmed (PLACED)   :o2, after o1, 1m

    section Fulfillment
    Picking Started (PACKING)  :f1, 10:15, 10m
    Items Packed (PACKED)      :f2, after f1, 5m

    section Delivery
    Rider Assigned             :d1, 10:30, 2m
    Out for Delivery           :d2, after d1, 25m
    Delivered (DELIVERED)      :d3, after d2, 3m
```

## Request Flow with All Services

```mermaid
graph TB
    Request["📱 Place Order Request"]

    subgraph happy["Happy Path (~15 min total)"]
        H1["✅ Auth validated<br/>(~10ms)"]
        H2["✅ Inventory reserved<br/>(~100ms)"]
        H3["✅ Payment processed<br/>(~2s)"]
        H4["✅ Order created<br/>(~150ms)"]
        H5["✅ Order placed<br/>(~50ms)"]
        H6["✅ Fulfillment started<br/>(~1min)"]
        H7["✅ Items packed<br/>(~10min)"]
        H8["✅ Rider assigned<br/>(~2min)"]
        H9["✅ Delivered<br/>(~20min)"]
    end

    subgraph errors["Error Paths"]
        E1["❌ Auth failed<br/>→ 401 Unauthorized"]
        E2["❌ Stock unavailable<br/>→ Order failed, notify user"]
        E3["❌ Payment declined<br/>→ Release reservation, notify"]
        E4["❌ Fulfillment issue<br/>→ Admin cancellation, refund"]
        E5["❌ Delivery failed<br/>→ Reschedule or refund"]
    end

    Request --> H1
    H1 --> H2 --> H3 --> H4 --> H5
    H5 --> H6 --> H7 --> H8 --> H9

    H1 -.->|Fail| E1
    H2 -.->|Fail| E2
    H3 -.->|Fail| E3
    H6 -.->|Fail| E4
    H8 -.->|Fail| E5

    style happy fill:#E8F5E9,color:#000,stroke:#333,stroke-width:2px
    style errors fill:#FFEBEE,color:#000,stroke:#333,stroke-width:2px
    style H9 fill:#7ED321,color:#000,stroke:#333,stroke-width:3px
```

## Event Flow: Order → Fulfillment → Delivery

```mermaid
sequenceDiagram
    participant Order as Order Service
    participant Kafka as Kafka
    participant Fulfillment as Fulfillment Service
    participant Store as Dark Store
    participant Rider as Rider Fleet
    participant Customer as Customer App

    Note over Order: Order status: PLACED

    Order->>Kafka: 1. OrderPlaced<br/>{orderId, items, storeId, address}

    Kafka->>Fulfillment: 2. Consume OrderPlaced
    Fulfillment->>Fulfillment: 3. Create fulfillment task
    Fulfillment->>Store: 4. Assign to store

    Store->>Fulfillment: 5. Start picking
    Fulfillment->>Kafka: 6. FulfillmentStarted

    Kafka->>Order: 7. Update → PACKING
    Order->>Kafka: 8. OrderStatusChanged

    Kafka->>Customer: 9. Push: "Order being prepared"

    Store->>Fulfillment: 10. Picking complete
    Fulfillment->>Kafka: 11. FulfillmentPacked

    Kafka->>Order: 12. Update → PACKED
    Order->>Kafka: 13. OrderStatusChanged

    Fulfillment->>Rider: 14. Request rider
    Rider->>Rider: 15. Find nearest available
    Rider->>Kafka: 16. RiderAssigned

    Kafka->>Order: 17. Update → OUT_FOR_DELIVERY
    Order->>Kafka: 18. OrderStatusChanged

    Kafka->>Customer: 19. Push: "Rider on the way"

    Rider->>Customer: 20. Live tracking
    Rider->>Rider: 21. Deliver order
    Rider->>Kafka: 22. DeliveryCompleted

    Kafka->>Order: 23. Update → DELIVERED
    Order->>Kafka: 24. OrderStatusChanged

    Kafka->>Customer: 25. Push: "Order delivered"
```

## Cancellation & Refund Flow

```mermaid
graph TB
    subgraph trigger["Cancellation Triggers"]
        UserCancel["👤 User cancels<br/>(before packing)"]
        AdminCancel["👨‍💼 Admin cancels<br/>(fraud, stock issue)"]
        SystemCancel["⚙️ System cancels<br/>(timeout, failure)"]
    end

    Order["📋 Order Service"]

    subgraph processing["Cancellation Processing"]
        Validate["Validate state<br/>(can cancel?)"]
        SetCancelled["Set CANCELLED<br/>+ reason"]
        RecordHistory["Record status history"]
        PublishEvent["Publish OrderCancelled"]
    end

    Kafka["📬 Kafka"]

    subgraph consumers["Event Consumers"]
        Payment["💳 Payment Service<br/>→ Initiate refund"]
        Inventory["📦 Inventory Service<br/>→ Release reservation"]
        Fulfillment["🏪 Fulfillment Service<br/>→ Cancel task"]
        Notification["📬 Notification Service<br/>→ Send cancellation notice"]
    end

    Customer["👤 Customer"]

    UserCancel --> Order
    AdminCancel --> Order
    SystemCancel --> Order

    Order --> Validate --> SetCancelled --> RecordHistory --> PublishEvent

    PublishEvent --> Kafka

    Kafka --> Payment
    Kafka --> Inventory
    Kafka --> Fulfillment
    Kafka --> Notification

    Payment -->|Refund processed| Customer
    Notification -->|SMS/Push| Customer

    style Order fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style Kafka fill:#50E3C2,color:#000
    style Payment fill:#FF6B6B,color:#fff
```

## Multi-Service Saga (Checkout)

```mermaid
graph TB
    subgraph saga["Checkout Saga (Temporal Workflow)"]
        Start["Start Checkout"]
        ReserveStock["📦 Reserve Stock"]
        ProcessPayment["💳 Process Payment"]
        CreateOrder["📋 Create Order"]
        ConfirmOrder["✅ Confirm Order"]
        CompleteSaga["Complete Saga"]
    end

    subgraph compensate["Compensation (on failure)"]
        CancelPayment["💳 Void Payment"]
        ReleaseStock["📦 Release Stock"]
        CancelOrder["📋 Cancel Order"]
    end

    Start --> ReserveStock
    ReserveStock -->|Success| ProcessPayment
    ReserveStock -->|Fail| ReleaseStock

    ProcessPayment -->|Success| CreateOrder
    ProcessPayment -->|Fail| CancelPayment
    CancelPayment --> ReleaseStock

    CreateOrder -->|Success| ConfirmOrder
    CreateOrder -->|Fail| CancelPayment

    ConfirmOrder -->|Success| CompleteSaga
    ConfirmOrder -->|Fail| CancelOrder
    CancelOrder --> CancelPayment

    style saga fill:#E8F5E9,color:#000,stroke:#333,stroke-width:2px
    style compensate fill:#FFEBEE,color:#000,stroke:#333,stroke-width:2px
    style CompleteSaga fill:#7ED321,color:#000,stroke:#333,stroke-width:3px
```

## Observability: Order Metrics Dashboard

```mermaid
graph LR
    Order["📋 Order Service"]

    subgraph metrics["Key Metrics"]
        M1["orders_created_total<br/>(Counter)"]
        M2["orders_placed_total<br/>(Counter)"]
        M3["orders_cancelled_total<br/>(Counter by reason)"]
        M4["orders_delivered_total<br/>(Counter)"]
        M5["order_processing_duration_ms<br/>(Histogram)"]
        M6["order_lifecycle_duration_ms<br/>(Histogram by stage)"]
    end

    subgraph slos["Service Level Objectives"]
        SLO1["📊 Order creation: P99 < 300ms"]
        SLO2["📊 Status update: P99 < 100ms"]
        SLO3["📊 Availability: 99.9%"]
        SLO4["📊 Error rate: < 0.1%"]
    end

    subgraph alerts["Alert Rules"]
        A1["🔴 SEV-1: Order creation > 1s P99"]
        A2["🟠 SEV-2: Order creation > 500ms P99"]
        A3["🔴 SEV-1: Cancellation rate > 5%"]
        A4["🟠 SEV-2: Delivery success < 98%"]
    end

    Order --> M1
    Order --> M2
    Order --> M3
    Order --> M4
    Order --> M5
    Order --> M6

    M5 --> SLO1
    M5 --> SLO2
    M1 --> SLO3
    M3 --> SLO4

    SLO1 --> A1
    SLO1 --> A2
    SLO4 --> A3
    M4 --> A4

    style Order fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style A1 fill:#FF6B6B,color:#fff
    style A2 fill:#F5A623,color:#000
    style A3 fill:#FF6B6B,color:#fff
    style A4 fill:#F5A623,color:#000
```

## Order Status Tracking (Customer View)

```mermaid
graph TB
    Customer["👤 Customer opens app"]

    subgraph tracking["Order Tracking View"]
        Header["Order #12345<br/>Placed at 10:15 AM"]

        subgraph timeline["Status Timeline"]
            S1["✅ Order Placed<br/>10:15 AM"]
            S2["✅ Preparing<br/>10:17 AM"]
            S3["✅ Packed<br/>10:28 AM"]
            S4["🔵 Out for Delivery<br/>10:32 AM"]
            S5["⏳ Delivered<br/>Expected: 10:55 AM"]
        end

        subgraph liveTracking["Live Tracking"]
            Map["🗺️ Map View<br/>Rider location"]
            ETA["⏱️ ETA: 8 min"]
            RiderInfo["🏍️ Rider: Amit<br/>📞 Call Rider"]
        end

        subgraph items["Order Items"]
            Item1["🥛 Milk 1L × 2"]
            Item2["🍞 Bread × 1"]
            Item3["🥚 Eggs 12-pack × 1"]
        end

        Total["💰 Total: ₹285"]
    end

    Customer --> Header
    Header --> S1 --> S2 --> S3 --> S4 --> S5
    S4 --> Map
    Map --> ETA
    ETA --> RiderInfo
    Header --> Item1
    Item1 --> Item2
    Item2 --> Item3
    Item3 --> Total

    style S4 fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style Map fill:#50E3C2,color:#000
```

## Error Recovery Patterns

```mermaid
graph TB
    subgraph errors["Common Failure Scenarios"]
        E1["Payment timeout"]
        E2["Inventory sync lag"]
        E3["Order service down"]
        E4["Kafka unavailable"]
        E5["Database failure"]
    end

    subgraph recovery["Recovery Mechanisms"]
        R1["Temporal workflow retry<br/>(exponential backoff)"]
        R2["Reservation TTL<br/>(auto-release)"]
        R3["Idempotency key<br/>(safe retry)"]
        R4["Outbox pattern<br/>(guaranteed delivery)"]
        R5["Read replica failover<br/>(HA Postgres)"]
    end

    subgraph outcome["Outcome"]
        O1["✅ Order eventually created"]
        O2["✅ Stock consistency"]
        O3["✅ No duplicate orders"]
        O4["✅ Events delivered"]
        O5["✅ Service continues"]
    end

    E1 --> R1 --> O1
    E2 --> R2 --> O2
    E3 --> R3 --> O3
    E4 --> R4 --> O4
    E5 --> R5 --> O5

    style recovery fill:#E8F5E9,color:#000,stroke:#333,stroke-width:2px
    style outcome fill:#7ED321,color:#000
```

## Data Consistency Model

```mermaid
graph TB
    subgraph sync["Synchronous (Strong Consistency)"]
        A1["Order creation<br/>(single transaction)"]
        A2["Status update<br/>(optimistic locking)"]
        A3["Cancellation<br/>(same DB transaction)"]
    end

    subgraph async["Asynchronous (Eventual Consistency)"]
        B1["Inventory sync<br/>(via events)"]
        B2["Payment status<br/>(webhook/event)"]
        B3["Fulfillment state<br/>(event-driven)"]
        B4["Analytics<br/>(stream processing)"]
    end

    subgraph guarantees["Guarantees"]
        G1["Order + Items + History<br/>= Atomic"]
        G2["Outbox + Order<br/>= Same transaction"]
        G3["Events delivered<br/>= At-least-once"]
        G4["Consumers<br/>= Idempotent"]
    end

    A1 --> G1
    A2 --> G2
    B1 --> G3
    B2 --> G4

    style sync fill:#4A90E2,color:#fff
    style async fill:#50E3C2,color:#000
    style guarantees fill:#9013FE,color:#fff
```
