# Fulfillment Service - Entity-Relationship Diagram (ERD)

## Database Schema

```mermaid
erDiagram
    PICK_TASKS ||--o{ PICK_ITEMS : contains
    PICK_TASKS ||--o{ DELIVERIES : has
    PICK_TASKS ||--o{ FULFILLMENT_OUTBOX : publishes

    PICK_TASKS {
        uuid id PK
        uuid order_id UK "unique per order"
        uuid user_id
        boolean user_erased
        string store_id
        uuid payment_id
        uuid picker_id
        string status "PENDING|IN_PROGRESS|COMPLETED|CANCELLED"
        timestamp started_at
        timestamp completed_at
        timestamp created_at
        bigint version "optimistic lock"
    }

    PICK_ITEMS {
        uuid id PK
        uuid pick_task_id FK
        uuid order_item_id
        uuid product_id
        string product_name
        string sku
        int quantity
        int picked_qty
        bigint unit_price_cents
        bigint line_total_cents
        string status "PENDING|PICKED|NOT_AVAILABLE|SUBSTITUTED|MISSING"
        uuid substitute_product_id
        text note
        timestamp created_at
    }

    DELIVERIES {
        uuid id PK
        uuid order_id
        uuid rider_id
        string status "CREATED|ASSIGNED|IN_PROGRESS|IN_TRANSIT|OUT_FOR_DELIVERY|DELIVERED|CANCELLED"
        int eta_minutes
        timestamp created_at
        timestamp updated_at
    }

    FULFILLMENT_OUTBOX {
        uuid id PK
        string aggregate_id "order_id as string"
        string event_type "OrderPacked|RiderAssigned|etc"
        jsonb payload
        timestamp created_at
        timestamp published_at "null until CDC publishes"
    }
```

## Indexes

```sql
-- Performance optimizations
CREATE INDEX idx_pick_tasks_order_id ON pick_tasks(order_id) WHERE status != 'CANCELLED';
CREATE INDEX idx_pick_tasks_store_status ON pick_tasks(store_id, status);
CREATE INDEX idx_pick_tasks_created_at ON pick_tasks(created_at DESC);
CREATE INDEX idx_pick_items_pick_task_id ON pick_items(pick_task_id);
CREATE INDEX idx_pick_items_product_id ON pick_items(product_id);
CREATE INDEX idx_deliveries_rider_id ON deliveries(rider_id);
CREATE INDEX idx_deliveries_order_id ON deliveries(order_id);
CREATE INDEX idx_deliveries_status_created ON deliveries(status, created_at DESC);
CREATE INDEX idx_outbox_published_at ON fulfillment_outbox(published_at NULLS FIRST);
CREATE INDEX idx_outbox_created_at ON fulfillment_outbox(created_at DESC);

-- PostGIS index for geo queries (if used)
CREATE INDEX idx_stores_location_gist ON stores USING GIST(location);
```

## Constraints & Relationships

```mermaid
graph TB
    subgraph "Referential Integrity"
        A["pick_tasks.order_id<br/>(UUID)"]
        B["pick_items.pick_task_id<br/>FK -> pick_tasks.id"]
        C["deliveries.order_id<br/>(UUID ref to orders)"]
    end

    subgraph "Unique Constraints"
        D["pick_tasks(order_id) UNIQUE<br/>One pick task per order"]
        E["pick_items(pick_task_id, product_id)<br/>Implicit uniqueness<br/>in PickService logic"]
    end

    subgraph "Data Integrity"
        F["picked_qty <= quantity<br/>Enforced in app layer"]
        G["started_at >= created_at<br/>Validated in service"]
        H["completed_at >= started_at<br/>Validated in service"]
        I["status transitions<br/>enforced in StateModel"]
    end

    A -->|1:N| B
    D -->|ensures| A
    C -->|references| C
```

## Version Control (Optimistic Locking)

```mermaid
graph TD
    A["PickTask.version = 1"] -->|Thread 1 reads| B["PickTask v1"]
    A -->|Thread 2 reads| C["PickTask v1"]
    B -->|mark item A picked| D["UPDATE pick_items..."]
    C -->|mark item B picked| E["UPDATE pick_items..."]
    D -->|Check version<br/>SELECT version WHERE id=?| F["version = 1 OK"]
    E -->|Check version<br/>SELECT version WHERE id=?| G["version = 1 OK (or 2?)"]
    F -->|UPDATE pick_tasks<br/>SET version = 2<br/>WHERE id=? AND version=1| H["Success<br/>version now = 2"]
    G -->|UPDATE pick_tasks<br/>SET version = 2<br/>WHERE id=? AND version=1| I["Conflict!<br/>0 rows updated"]
    H -->|Thread 1 succeeds| J["Item A marked"]
    I -->|Thread 2 retries<br/>with circuit breaker| K["Exponential backoff"]
    K -->|After backoff| L["Retry markItem()"]
    L -->|New read| M["PickTask v2"]
    M -->|Update| N["version = 3"]
```

## Data Flow Through Tables

```mermaid
flowchart TD
    OrderEvent["[Event] OrderCreated"] -->|consumed| PickSvc["PickService"]
    PickSvc -->|INSERT| PickTasks["pick_tasks<br/>(PENDING)"]
    PickSvc -->|INSERT| PickItems["pick_items<br/>(PENDING)"]

    Picker["Warehouse Picker"] -->|POST markItem| PickSvc
    PickSvc -->|UPDATE| PickItems
    PickSvc -->|UPDATE status<br/>if first item| PickTasks
    PickSvc -->|INSERT| Outbox["fulfillment_outbox"]

    AllPicked{All items<br/>picked?} -->|Yes| PickSvc
    PickSvc -->|UPDATE to COMPLETED| PickTasks
    PickSvc -->|INSERT| Outbox

    Outbox -->|CDC detected| Debezium["Debezium"]
    Debezium -->|publish| Kafka["fulfillment.events"]
    Kafka -->|RiderService<br/>consumes| DeliverySvc["DeliveryService"]
    DeliverySvc -->|INSERT| Deliveries["deliveries<br/>(ASSIGNED)"]
```

## Column Encoding Notes

```markdown
## Enums Stored as VARCHAR

- pick_task_status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
- pick_item_status: 'PENDING' | 'PICKED' | 'NOT_AVAILABLE' | 'SUBSTITUTED' | 'MISSING'
- delivery_status: 'CREATED' | 'ASSIGNED' | 'IN_PROGRESS' | 'IN_TRANSIT' | 'OUT_FOR_DELIVERY' | 'DELIVERED' | 'CANCELLED'

## JSON Storage

- fulfillment_outbox.payload: Full event JSON
  - { "orderId": "...", "userId": "...", "items": [...], "pickupLat": 12.34, ... }

## Currency Fields

- unit_price_cents: bigint representing price in cents (INR * 100)
- line_total_cents: bigint (quantity * unit_price)
```
