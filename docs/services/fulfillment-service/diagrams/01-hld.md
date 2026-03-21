# Fulfillment Service - High-Level Design (HLD)

## Architecture Overview

```mermaid
graph TB
    subgraph "Client Layer"
        WMS["Warehouse Management System"]
        Mobile["Mobile App"]
    end

    subgraph "Fulfillment Service"
        API["REST API<br/>:8080"]
        PickOps["Pick Operations"]
        DeliveryOps["Delivery Operations"]
        SubstService["Substitution Service"]
        Outbox["Outbox Service<br/>CDC Events"]
    end

    subgraph "External Services"
        OrderSvc["Order Service"]
        WarehouseSvc["Warehouse Service"]
        InventorySvc["Inventory Service"]
        RiderSvc["Rider Fleet Service"]
    end

    subgraph "Data & Events"
        PostgreSQL["PostgreSQL<br/>fulfillment DB"]
        Kafka["Kafka<br/>fulfillment.events"]
    end

    subgraph "Observability"
        Metrics["Prometheus Metrics"]
        Logs["Structured Logs"]
    end

    WMS -->|Pick Tasks| API
    Mobile -->|Delivery Status| API
    API --> PickOps
    API --> DeliveryOps
    PickOps --> SubstService
    PickOps --> WarehouseSvc
    PickOps --> InventorySvc
    DeliveryOps --> RiderSvc
    PickOps --> Outbox
    DeliveryOps --> Outbox
    Outbox --> PostgreSQL
    Outbox --> Kafka
    OrderSvc -->|OrderCreated| API
    OrderSvc -->|OrderCancelled| API
    PostgreSQL --> Metrics
    PostgreSQL --> Logs
```

## Data Flow

```mermaid
graph LR
    OrderPlaced["Order Placed Event"] -->|consumed| FS["Fulfillment Service"]
    FS -->|1. Create Pick Task| PKT["Pick Tasks Table"]
    FS -->|2. Link Items| PI["Pick Items Table"]
    FS -->|3. Warehouse Query| WH["Warehouse Service<br/>Store Zones"]
    FS -->|4. Assign Rider| RF["Rider Fleet Service"]
    FS -->|5. Publish Event| EV["OrderPacked Event<br/>Kafka"]
```

## Key Components

| Component | Responsibility | Technology |
|-----------|-----------------|------------|
| Pick Operations | Create tasks, track item picking | Java Spring, PostgreSQL |
| Delivery Operations | Assign riders, track delivery status | Java Spring, Kafka |
| Substitution Service | Handle out-of-stock items | Business Logic |
| Outbox Service | Event publishing (CDC pattern) | PostgreSQL Outbox |
| Cache Layer | Store zones, availability (optional) | Redis/Caffeine |

## Critical Paths

1. **Pick Path**: OrderCreated → Create PickTask → Mark Items → Publish OrderPacked
2. **Delivery Path**: OrderPacked → Assign Rider → Track Delivery → DeliveryCompleted
3. **Substitution Path**: ItemNotFound → Substitution Logic → Alternative Product

## SLO Targets

- **Availability**: 99.9%
- **P99 Latency**: < 1.2 seconds
- **Pick Task Creation**: < 500ms
- **Rider Assignment**: < 1000ms
