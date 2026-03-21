# Fulfillment Service - Low-Level Design (LLD)

## Component Architecture

```mermaid
graph TB
    subgraph "HTTP Layer"
        PickCtrl["PickController<br/>GET /picklist/{storeId}<br/>POST /picklist/{orderId}/items/{productId}"]
        DelivCtrl["DeliveryController<br/>POST /delivery/{orderId}/assign<br/>POST /delivery/{orderId}/update-status"]
    end

    subgraph "Service Layer"
        PickSvc["PickService<br/>- createPickTask()<br/>- listPendingTasks()<br/>- markItem()<br/>- markPacked()"]
        DeliverySvc["DeliveryService<br/>- assignRider()<br/>- updateStatus()<br/>- trackDelivery()"]
        SubstSvc["SubstitutionService<br/>- handleMissingItem()<br/>- findAlternatives()"]
    end

    subgraph "Repository Layer"
        PickTaskRepo["PickTaskRepository<br/>findByOrderId()<br/>findByStoreId()"]
        PickItemRepo["PickItemRepository<br/>findByPickTask_Id()"]
        DeliveryRepo["DeliveryRepository"]
    end

    subgraph "Event & Integration"
        OutboxSvc["OutboxService<br/>publish() - Outbox table"]
        EventPub["ApplicationEventPublisher<br/>OrderStatusUpdateEvent"]
        WarehouseClient["WarehouseClient<br/>getStoreCoordinates()"]
        RiderClient["RiderClient<br/>assignRider()"]
    end

    subgraph "Data Layer"
        PostgreSQL["PostgreSQL<br/>- pick_tasks<br/>- pick_items<br/>- deliveries<br/>- outbox"]
    end

    PickCtrl --> PickSvc
    DelivCtrl --> DeliverySvc
    PickSvc --> SubstSvc
    PickSvc --> PickTaskRepo
    PickSvc --> PickItemRepo
    DeliverySvc --> DeliveryRepo
    PickSvc --> OutboxSvc
    PickSvc --> EventPub
    PickSvc --> WarehouseClient
    DeliverySvc --> RiderClient
    PickTaskRepo --> PostgreSQL
    PickItemRepo --> PostgreSQL
    DeliveryRepo --> PostgreSQL
    OutboxSvc --> PostgreSQL
```

## Database Schema Details

```sql
-- Pick Tasks (Core entity)
create table pick_tasks (
    id uuid primary key,
    order_id uuid not null unique,
    user_id uuid not null,
    user_erased boolean default false,
    store_id varchar not null,
    payment_id uuid,
    picker_id uuid,
    status varchar(20),  -- PENDING, IN_PROGRESS, COMPLETED, CANCELLED
    started_at timestamp,
    completed_at timestamp,
    created_at timestamp,
    version bigint  -- Optimistic locking
);

-- Pick Items (Detail)
create table pick_items (
    id uuid primary key,
    pick_task_id uuid references pick_tasks(id),
    order_item_id uuid,
    product_id uuid not null,
    product_name varchar,
    sku varchar,
    quantity integer,
    picked_qty integer,
    unit_price_cents bigint,
    line_total_cents bigint,
    status varchar(20),  -- PENDING, PICKED, NOT_AVAILABLE, SUBSTITUTED
    substitute_product_id uuid,
    note text,
    created_at timestamp
);

-- Deliveries
create table deliveries (
    id uuid primary key,
    order_id uuid not null,
    rider_id uuid,
    status varchar(20),  -- ASSIGNED, IN_PROGRESS, DELIVERED, FAILED
    eta_minutes integer,
    created_at timestamp,
    updated_at timestamp
);

-- Outbox (CDC Events)
create table fulfillment_outbox (
    id uuid primary key,
    aggregate_id varchar not null,
    event_type varchar not null,
    payload jsonb,
    created_at timestamp,
    published_at timestamp
);
```

## Transactional Boundaries

```mermaid
graph LR
    T1["Txn: createPickTask()"]
    T2["Txn: markItem()"]
    T3["Txn: markPacked()"]
    T4["Txn: assignRider()"]

    T1 -->|Pessimistic Lock on order_id| T2
    T2 -->|Optimistic Version| T1
    T3 -->|All items picked?| T2
    T4 -->|After PickTask COMPLETED| T3
```

## Call Flow (Sequence Within Service)

```mermaid
sequenceDiagram
    participant Picker as Warehouse Picker
    participant PickCtrl as PickController
    participant PickSvc as PickService
    participant PickRepo as PickTaskRepository
    participant InvClient as InventoryService
    participant OutboxSvc as OutboxService

    Picker->>PickCtrl: POST /picklist/{orderId}/items/{productId}
    PickCtrl->>PickSvc: markItem(orderId, productId, request)
    PickSvc->>PickRepo: findByOrderId(orderId)
    PickRepo-->>PickSvc: PickTask (PENDING)
    PickSvc->>PickRepo: findByPickTask_OrderIdAndProductId()
    PickRepo-->>PickSvc: PickItem
    PickSvc->>PickSvc: applyItemUpdate() - set status, qty
    PickSvc->>PickRepo: save(item)
    alt Item Missing
        PickSvc->>InvClient: checkAvailability()
        PickSvc->>PickSvc: handleMissingItem()
    end
    alt All Items Picked
        PickSvc->>PickRepo: save(task) - COMPLETED
        PickSvc->>OutboxSvc: publish(OrderPacked event)
    end
    PickSvc-->>PickCtrl: PickItemResponse
    PickCtrl-->>Picker: HTTP 200 OK
```

## Error Handling

- **Optimistic Lock Conflict**: Retry on version mismatch (Circuit breaker after 3 attempts)
- **Invalid State Transition**: Throw InvalidPickTaskStateException
- **Missing Item**: Trigger substitution service or hold for manual review
- **Warehouse Service Down**: Log warning, use cached coordinates if available
