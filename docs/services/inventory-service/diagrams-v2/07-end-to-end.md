# Inventory Service - End-to-End Flows

## Complete Checkout Reservation Flow (E2E)

```mermaid
graph TB
    Customer["👤 Customer<br/>(Mobile App)"]
    MobileBFF["📱 Mobile BFF"]
    CartService["🛒 Cart Service"]
    CheckoutOrch["🔄 Checkout Orchestrator<br/>(Temporal Saga)"]

    ALB["⚖️ AWS ALB"]
    InventoryService["📦 Inventory Service"]
    PostgreSQL["🗃️ PostgreSQL"]
    Redis["⚡ Redis"]

    Outbox["📤 Outbox Table"]
    Debezium["🔗 Debezium CDC"]
    Kafka["📬 Kafka"]

    OrderService["📋 Order Service"]
    PaymentService["💳 Payment Service"]

    Customer -->|1. Tap Checkout| MobileBFF
    MobileBFF -->|2. Get cart items| CartService
    CartService -->|3. Cart with items| MobileBFF
    MobileBFF -->|4. Start checkout saga| CheckoutOrch

    CheckoutOrch -->|5. POST /inventory/reserve<br/>{storeId, idempotencyKey, items}| ALB
    ALB -->|6. Forward| InventoryService

    InventoryService -->|7. Rate limit check| Redis
    InventoryService -->|8. Check idempotency key| PostgreSQL
    InventoryService -->|9. Lock stock rows<br/>FOR UPDATE| PostgreSQL
    InventoryService -->|10. Update reserved counts| PostgreSQL
    InventoryService -->|11. Create reservation| PostgreSQL
    InventoryService -->|12. Write to outbox| Outbox

    PostgreSQL -->|13. Transaction COMMIT| InventoryService
    InventoryService -->|14. 200 OK<br/>{reservationId, expiresAt}| CheckoutOrch

    Outbox -->|15. CDC capture| Debezium
    Debezium -->|16. Publish| Kafka

    CheckoutOrch -->|17. Create order<br/>(reservation reference)| OrderService
    CheckoutOrch -->|18. Process payment| PaymentService

    PaymentService -->|19. Payment success| CheckoutOrch

    CheckoutOrch -->|20. POST /inventory/confirm<br/>{reservationId}| InventoryService
    InventoryService -->|21. Decrement on_hand<br/>Release reserved| PostgreSQL
    InventoryService -->|22. 204 No Content| CheckoutOrch

    CheckoutOrch -->|23. Checkout complete| MobileBFF
    MobileBFF -->|24. Order confirmation| Customer

    style Customer fill:#4A90E2,color:#fff
    style CheckoutOrch fill:#7ED321,color:#000
    style InventoryService fill:#4A90E2,color:#fff
    style PostgreSQL fill:#336791,color:#fff
    style Kafka fill:#000000,color:#fff
```

## Replenishment to Checkout Flow (Complete Stock Lifecycle)

```mermaid
graph TB
    subgraph Replenishment["1️⃣ Replenishment Phase"]
        WMS["🏭 Warehouse<br/>Management System"]
        TransferOrder["📝 Transfer Order<br/>Created"]
        WarehouseService["🚚 Warehouse Service"]
        AdjustAPI["POST /inventory/adjust<br/>{delta: +100, reason: REPLENISHMENT}"]
    end

    subgraph Available["2️⃣ Stock Available"]
        StockReady["📦 Stock Ready<br/>on_hand: 100<br/>reserved: 0<br/>available: 100"]
    end

    subgraph Reservation["3️⃣ Checkout Reservation"]
        CheckoutStart["🛒 Customer Checkout"]
        ReserveAPI["POST /inventory/reserve<br/>{items: [{productId, qty: 2}]}"]
        StockReserved["📦 Stock Reserved<br/>on_hand: 100<br/>reserved: 2<br/>available: 98"]
    end

    subgraph Confirmation["4️⃣ Order Confirmation"]
        PaymentSuccess["💳 Payment Success"]
        ConfirmAPI["POST /inventory/confirm<br/>{reservationId}"]
        StockCommitted["📦 Stock Committed<br/>on_hand: 98<br/>reserved: 0<br/>available: 98"]
    end

    subgraph LowStock["5️⃣ Low Stock Trigger"]
        ThresholdCheck["📊 Available <= 10"]
        LowStockAlert["🚨 LowStockAlert Event"]
        ReplenishTrigger["🔄 Trigger Replenishment"]
    end

    WMS -->|Physical goods arrive| TransferOrder
    TransferOrder --> WarehouseService
    WarehouseService --> AdjustAPI
    AdjustAPI -->|Stock increased| StockReady

    StockReady --> CheckoutStart
    CheckoutStart --> ReserveAPI
    ReserveAPI -->|Reserved 2 units| StockReserved

    StockReserved --> PaymentSuccess
    PaymentSuccess --> ConfirmAPI
    ConfirmAPI -->|Committed 2 units| StockCommitted

    StockCommitted --> ThresholdCheck
    ThresholdCheck -->|Threshold breached| LowStockAlert
    LowStockAlert --> ReplenishTrigger
    ReplenishTrigger -->|Cycle repeats| WMS

    style WMS fill:#F5A623,color:#000
    style StockReady fill:#7ED321,color:#000
    style StockReserved fill:#4A90E2,color:#fff
    style StockCommitted fill:#52C41A,color:#fff
    style LowStockAlert fill:#FF6B6B,color:#fff
```

## Failure Handling & Compensation Flow

```mermaid
graph TB
    subgraph HappyPath["✅ Happy Path"]
        HP1["Reserve Stock"]
        HP2["Create Order"]
        HP3["Process Payment"]
        HP4["Confirm Stock"]
        HP5["Complete Checkout"]
    end

    subgraph PaymentFailure["❌ Payment Failure Path"]
        PF1["Reserve Stock ✅"]
        PF2["Create Order ✅"]
        PF3["Process Payment ❌"]
        PF4["Cancel Stock<br/>(Compensation)"]
        PF5["Cancel Order<br/>(Compensation)"]
        PF6["Return Error"]
    end

    subgraph ReserveFailure["❌ Reserve Failure Path"]
        RF1["Reserve Stock ❌<br/>(Insufficient)"]
        RF2["Return 409 Conflict"]
        RF3["Show Out of Stock"]
    end

    subgraph Timeout["⏰ Reservation Timeout"]
        TO1["Reserve Stock ✅"]
        TO2["User abandons cart"]
        TO3["15 min TTL expires"]
        TO4["Expiry Job runs"]
        TO5["Stock Released<br/>(Auto-compensation)"]
    end

    HP1 --> HP2 --> HP3 --> HP4 --> HP5

    PF1 --> PF2 --> PF3
    PF3 -->|Saga compensation| PF4
    PF4 --> PF5 --> PF6

    RF1 --> RF2 --> RF3

    TO1 --> TO2 --> TO3 --> TO4 --> TO5

    style HP5 fill:#7ED321,color:#000
    style PF3 fill:#FF6B6B,color:#fff
    style PF4 fill:#F5A623,color:#000
    style RF1 fill:#FF6B6B,color:#fff
    style TO5 fill:#F5A623,color:#000
```

## Multi-Location Fulfillment Decision

```mermaid
graph TB
    Customer["👤 Customer<br/>Location: Downtown"]

    subgraph LocationSelection["📍 Location Selection"]
        CustomerLocation["Customer GPS"]
        NearestDarkstore["Find nearest darkstore<br/>with available stock"]
    end

    subgraph Darkstores["🏪 Darkstore Inventory"]
        DS1["Darkstore A<br/>Distance: 2km<br/>iPhone: 5 available"]
        DS2["Darkstore B<br/>Distance: 5km<br/>iPhone: 12 available"]
        DS3["Darkstore C<br/>Distance: 8km<br/>iPhone: 0 available"]
    end

    subgraph StockCheck["📊 Availability Check"]
        CheckAPI["POST /inventory/check<br/>{storeId: DS-A, items}"]
        Response["Response:<br/>iPhone: available=5, sufficient=true"]
    end

    subgraph Reserve["🔒 Reserve at Selected Location"]
        ReserveAPI["POST /inventory/reserve<br/>{storeId: DS-A,<br/>items: [{iPhone, qty: 1}]}"]
        Reserved["Reserved at Darkstore A"]
    end

    Customer --> CustomerLocation
    CustomerLocation --> NearestDarkstore

    NearestDarkstore --> DS1
    NearestDarkstore --> DS2
    NearestDarkstore --> DS3

    DS1 -->|Selected: closest with stock| CheckAPI
    CheckAPI --> Response
    Response -->|Stock confirmed| ReserveAPI
    ReserveAPI --> Reserved

    style Customer fill:#4A90E2,color:#fff
    style DS1 fill:#7ED321,color:#000
    style DS2 fill:#F5A623,color:#000
    style DS3 fill:#FF6B6B,color:#fff
    style Reserved fill:#52C41A,color:#fff
```

## Concurrent Request Handling

```mermaid
graph TB
    subgraph Requests["⚡ Concurrent Checkouts"]
        Req1["Customer A<br/>Reserve iPhone x1"]
        Req2["Customer B<br/>Reserve iPhone x1"]
        Req3["Customer C<br/>Reserve iPhone x1"]
    end

    subgraph Stock["📦 Stock State"]
        Initial["Initial:<br/>on_hand: 2<br/>reserved: 0<br/>available: 2"]
    end

    subgraph Processing["🔄 Sequential Lock Processing"]
        Lock1["Req1 acquires lock<br/>Checks available: 2 ✅<br/>Reserves 1"]
        Lock2["Req2 acquires lock<br/>Checks available: 1 ✅<br/>Reserves 1"]
        Lock3["Req3 acquires lock<br/>Checks available: 0 ❌<br/>InsufficientStock"]
    end

    subgraph Results["📋 Results"]
        Res1["Customer A: 200 OK<br/>Reservation created"]
        Res2["Customer B: 200 OK<br/>Reservation created"]
        Res3["Customer C: 409 Conflict<br/>Out of stock"]
    end

    subgraph FinalState["📦 Final Stock State"]
        Final["Final:<br/>on_hand: 2<br/>reserved: 2<br/>available: 0"]
    end

    Req1 --> Lock1
    Req2 --> Lock2
    Req3 --> Lock3

    Lock1 -->|COMMIT| Lock2
    Lock2 -->|COMMIT| Lock3

    Lock1 --> Res1
    Lock2 --> Res2
    Lock3 --> Res3

    Initial --> Processing
    Processing --> FinalState

    LockNote["PESSIMISTIC_WRITE ensures<br/>serialized access to stock row<br/>No overselling possible"]
    Lock1 -.-> LockNote

    style Res1 fill:#7ED321,color:#000
    style Res2 fill:#7ED321,color:#000
    style Res3 fill:#FF6B6B,color:#fff
    style Final fill:#F5A623,color:#000
```

## Event-Driven Cross-Service Communication

```mermaid
graph LR
    InventoryService["📦 Inventory Service"]

    subgraph Events["📬 Kafka Events"]
        StockReserved["inventory.stock.reserved"]
        StockConfirmed["inventory.stock.confirmed"]
        StockReleased["inventory.stock.released"]
        LowStockAlert["inventory.stock.low-alert"]
    end

    subgraph Consumers["🔔 Event Consumers"]
        OrderService["📋 Order Service<br/>- Update order status<br/>- Track reservation"]
        WarehouseService["🏭 Warehouse Service<br/>- Trigger replenishment<br/>- Update transfer orders"]
        AnalyticsPlatform["📊 Analytics Platform<br/>- Demand forecasting<br/>- Stock velocity metrics"]
        NotificationService["📧 Notification Service<br/>- Low stock alerts<br/>- Ops team notifications"]
        FulfillmentService["📦 Fulfillment Service<br/>- Prepare picking lists<br/>- Dispatch orders"]
    end

    InventoryService --> StockReserved
    InventoryService --> StockConfirmed
    InventoryService --> StockReleased
    InventoryService --> LowStockAlert

    StockReserved --> OrderService
    StockConfirmed --> OrderService
    StockConfirmed --> FulfillmentService
    StockReleased --> OrderService

    LowStockAlert --> WarehouseService
    LowStockAlert --> NotificationService

    StockReserved --> AnalyticsPlatform
    StockConfirmed --> AnalyticsPlatform
    StockReleased --> AnalyticsPlatform

    style InventoryService fill:#4A90E2,color:#fff
    style StockReserved fill:#000000,color:#fff
    style StockConfirmed fill:#000000,color:#fff
    style StockReleased fill:#000000,color:#fff
    style LowStockAlert fill:#FF6B6B,color:#fff
```

## SLO & Monitoring Dashboard

```mermaid
graph TB
    InventoryService["📦 Inventory Service"]

    subgraph SLOs["📊 Service Level Objectives"]
        LatencySLO["⏱️ Latency SLO<br/>Reserve P99 < 100ms<br/>Confirm P99 < 50ms<br/>Check P99 < 30ms"]
        AvailabilitySLO["✅ Availability SLO<br/>99.95% uptime<br/>= 22min downtime/month"]
        ErrorSLO["🚨 Error Budget<br/>0.05% error rate"]
    end

    subgraph Metrics["📈 Key Metrics"]
        M1["inventory_reservation_duration_ms"]
        M2["inventory_reservations_total{status}"]
        M3["inventory_lock_acquisition_duration_ms"]
        M4["inventory_low_stock_alerts_total"]
        M5["inventory_expired_reservations_total"]
    end

    subgraph Alerts["🚨 Alert Rules"]
        A1["🔴 SEV-1: Reserve P99 > 200ms<br/>(5min window)"]
        A2["🟠 SEV-2: Lock timeout rate > 1%<br/>(15min window)"]
        A3["🟡 SEV-3: Expiry job failures<br/>(1hr window)"]
    end

    subgraph Dashboards["📊 Grafana Dashboards"]
        D1["Real-time Inventory Health<br/>- Stock levels by location<br/>- Reservation throughput<br/>- Error rates"]
        D2["Stock Movement Analysis<br/>- Reserve/Confirm ratios<br/>- Cancellation rates<br/>- Expiry patterns"]
        D3["SLO Burn Rate<br/>- Multi-window analysis<br/>- Error budget consumption"]
    end

    InventoryService --> LatencySLO
    InventoryService --> AvailabilitySLO
    InventoryService --> ErrorSLO

    LatencySLO --> M1
    AvailabilitySLO --> M2
    ErrorSLO --> M3

    M1 --> A1
    M3 --> A2
    M5 --> A3

    M1 --> D1
    M2 --> D2
    M4 --> D3

    A1 --> PagerDuty["📱 PagerDuty"]
    A2 --> Slack["💬 Slack #inventory-alerts"]

    style LatencySLO fill:#7ED321,color:#000
    style AvailabilitySLO fill:#7ED321,color:#000
    style A1 fill:#FF6B6B,color:#fff
    style A2 fill:#F5A623,color:#000
```

## High-Availability Architecture

```mermaid
graph TB
    subgraph Traffic["🌐 Traffic Layer"]
        ALB["AWS ALB<br/>(Health checks)"]
        Istio["Istio Ingress<br/>(mTLS, routing)"]
    end

    subgraph K8s["☸️ Kubernetes Cluster"]
        subgraph Pods["Inventory Service Pods"]
            Pod1["Pod 1<br/>Ready ✅"]
            Pod2["Pod 2<br/>Ready ✅"]
            Pod3["Pod 3<br/>Ready ✅"]
        end

        HPA["HPA<br/>Min: 3, Max: 10<br/>CPU: 70%"]
        PDB["PDB<br/>minAvailable: 2"]
    end

    subgraph Database["🗃️ PostgreSQL HA"]
        Primary["Primary<br/>(Read/Write)"]
        Replica1["Replica 1<br/>(Read)"]
        Replica2["Replica 2<br/>(Standby)"]
    end

    subgraph Observability["📊 Observability"]
        Prometheus["Prometheus"]
        Grafana["Grafana"]
        Jaeger["Jaeger"]
    end

    ALB --> Istio
    Istio --> Pod1
    Istio --> Pod2
    Istio --> Pod3

    Pod1 --> Primary
    Pod2 --> Primary
    Pod3 --> Primary

    Primary --> Replica1
    Primary --> Replica2

    Pod1 --> Prometheus
    Pod2 --> Prometheus
    Pod3 --> Prometheus

    Prometheus --> Grafana

    HPA --> Pods
    PDB --> Pods

    FailoverNote["Automatic failover:<br/>- K8s restarts unhealthy pods<br/>- PDB ensures min 2 available<br/>- PostgreSQL auto-promotes replica"]
    Replica2 -.-> FailoverNote

    style Primary fill:#336791,color:#fff
    style Pod1 fill:#326CE5,color:#fff
    style Pod2 fill:#326CE5,color:#fff
    style Pod3 fill:#326CE5,color:#fff
    style HPA fill:#F5A623,color:#000
```
