# Inventory Service - ER Diagram & Storage Schema

## Core Domain Tables

```mermaid
erDiagram
    STOCK_ITEMS ||--o{ RESERVATION_LINE_ITEMS : "reserved by"
    STOCK_ITEMS ||--o{ STOCK_ADJUSTMENT_LOGS : "adjusted via"
    RESERVATIONS ||--|{ RESERVATION_LINE_ITEMS : "contains"
    RESERVATIONS ||--o{ OUTBOX_EVENTS : "publishes"
    STOCK_ITEMS ||--o{ OUTBOX_EVENTS : "publishes"

    STOCK_ITEMS {
        uuid id PK "Primary key"
        uuid product_id FK "Product catalog reference"
        uuid store_id FK "Darkstore/warehouse ID"
        int on_hand "Physical quantity in location"
        int reserved "Soft holds (pending checkouts)"
        timestamp updated_at "Last modification time"
        bigint version "Optimistic lock version"
    }

    RESERVATIONS {
        uuid id PK "Primary key"
        string idempotency_key UK "Idempotent request key"
        uuid store_id FK "Location where stock reserved"
        string status "PENDING, CONFIRMED, CANCELLED, EXPIRED"
        timestamp expires_at "TTL expiration (default +15min)"
        timestamp created_at "Reservation created"
        timestamp updated_at "Last status change"
        bigint version "Optimistic lock version"
    }

    RESERVATION_LINE_ITEMS {
        uuid id PK "Primary key"
        uuid reservation_id FK "Parent reservation"
        uuid product_id FK "Product being reserved"
        int quantity "Units reserved"
    }

    STOCK_ADJUSTMENT_LOGS {
        uuid id PK "Primary key"
        uuid product_id FK "Product adjusted"
        uuid store_id FK "Location adjusted"
        int delta "Change amount (+/-)"
        string reason "REPLENISHMENT, SHRINKAGE, CORRECTION, etc."
        string reference_id "External reference (transfer order, etc.)"
        uuid actor_id FK "User who made adjustment"
        timestamp created_at "Adjustment timestamp"
    }

    OUTBOX_EVENTS {
        uuid id PK "Primary key"
        string aggregate_type "Reservation, StockItem"
        string aggregate_id "Entity ID"
        string event_type "StockReserved, StockConfirmed, etc."
        json payload "Event data"
        timestamp created_at "Event timestamp"
    }

    AUDIT_LOGS {
        uuid id PK "Primary key"
        uuid actor_id FK "User performing action"
        string action "STOCK_ADJUSTED, STOCK_ADJUSTED_BATCH"
        string entity_type "StockItem, StockAdjustmentBatch"
        string entity_id "Affected entity ID"
        json details "Action details"
        timestamp created_at "Audit timestamp"
    }

    SHEDLOCK {
        string name PK "Lock name"
        timestamp lock_until "Lock expiration"
        timestamp locked_at "Lock acquisition time"
        string locked_by "Instance identifier"
    }
```

## Indexes & Constraints

```sql
-- stock_items indexes
CREATE UNIQUE INDEX idx_stock_items_product_store 
    ON stock_items(product_id, store_id);
CREATE INDEX idx_stock_items_store 
    ON stock_items(store_id);
CREATE INDEX idx_stock_items_updated 
    ON stock_items(updated_at);

-- reservations indexes
CREATE UNIQUE INDEX idx_reservations_idempotency 
    ON reservations(idempotency_key);
CREATE INDEX idx_reservations_status_expires 
    ON reservations(status, expires_at) 
    WHERE status = 'PENDING';
CREATE INDEX idx_reservations_store 
    ON reservations(store_id);

-- reservation_line_items indexes
CREATE INDEX idx_line_items_reservation 
    ON reservation_line_items(reservation_id);
CREATE INDEX idx_line_items_product 
    ON reservation_line_items(product_id);

-- stock_adjustment_logs indexes
CREATE INDEX idx_adjustment_logs_product_store 
    ON stock_adjustment_logs(product_id, store_id);
CREATE INDEX idx_adjustment_logs_created 
    ON stock_adjustment_logs(created_at);
CREATE INDEX idx_adjustment_logs_actor 
    ON stock_adjustment_logs(actor_id);

-- outbox_events indexes
CREATE INDEX idx_outbox_aggregate 
    ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_created 
    ON outbox_events(created_at);

-- audit_logs indexes
CREATE INDEX idx_audit_entity 
    ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_actor 
    ON audit_logs(actor_id);
CREATE INDEX idx_audit_created 
    ON audit_logs(created_at);
```

## Stock Item State Representation

```mermaid
graph TB
    subgraph StockItem["stock_items Row Example"]
        Row["id: abc-123<br/>product_id: prod-456<br/>store_id: darkstore-789<br/>on_hand: 100<br/>reserved: 25<br/>version: 5"]
    end

    subgraph Derived["Derived Values"]
        Available["available = on_hand - reserved<br/>= 100 - 25 = 75"]
        CanReserve["can_reserve(qty) =<br/>available >= qty"]
    end

    subgraph Operations["State Transitions"]
        Reserve["RESERVE(qty=10):<br/>reserved += 10<br/>→ reserved = 35"]
        Confirm["CONFIRM(qty=10):<br/>on_hand -= 10<br/>reserved -= 10<br/>→ on_hand = 90, reserved = 25"]
        Cancel["CANCEL(qty=10):<br/>reserved -= 10<br/>→ reserved = 15"]
        Adjust["ADJUST(delta=+50):<br/>on_hand += 50<br/>→ on_hand = 150"]
    end

    Row --> Available
    Available --> CanReserve
    Row --> Reserve
    Row --> Confirm
    Row --> Cancel
    Row --> Adjust

    style Row fill:#336791,color:#fff
    style Available fill:#7ED321,color:#000
```

## Reservation Entity Structure

```mermaid
graph TB
    subgraph Reservation["reservations Row"]
        ResRow["id: res-001<br/>idempotency_key: order-xyz-001<br/>store_id: darkstore-789<br/>status: PENDING<br/>expires_at: 2024-01-15T10:15:00Z<br/>created_at: 2024-01-15T10:00:00Z"]
    end

    subgraph LineItems["reservation_line_items Rows"]
        Item1["id: li-001<br/>reservation_id: res-001<br/>product_id: prod-A<br/>quantity: 2"]
        Item2["id: li-002<br/>reservation_id: res-001<br/>product_id: prod-B<br/>quantity: 1"]
        Item3["id: li-003<br/>reservation_id: res-001<br/>product_id: prod-C<br/>quantity: 3"]
    end

    subgraph StockImpact["Stock Impact"]
        StockA["prod-A: reserved += 2"]
        StockB["prod-B: reserved += 1"]
        StockC["prod-C: reserved += 3"]
    end

    Reservation --> LineItems
    Item1 --> StockA
    Item2 --> StockB
    Item3 --> StockC

    style ResRow fill:#4A90E2,color:#fff
    style Item1 fill:#F5A623,color:#000
    style Item2 fill:#F5A623,color:#000
    style Item3 fill:#F5A623,color:#000
```

## Outbox Event Payload Schemas

```mermaid
graph TB
    subgraph StockReserved["StockReserved Event"]
        SR_Payload["payload: {<br/>  reservationId: uuid,<br/>  orderId: string,<br/>  items: [<br/>    {productId, quantity}<br/>  ],<br/>  reservedAt: timestamp,<br/>  expiresAt: timestamp<br/>}"]
    end

    subgraph StockConfirmed["StockConfirmed Event"]
        SC_Payload["payload: {<br/>  reservationId: uuid,<br/>  orderId: string,<br/>  items: [<br/>    {productId, quantity}<br/>  ],<br/>  confirmedAt: timestamp<br/>}"]
    end

    subgraph StockReleased["StockReleased Event"]
        SRel_Payload["payload: {<br/>  reservationId: uuid,<br/>  orderId: string,<br/>  items: [<br/>    {productId, quantity}<br/>  ],<br/>  releasedAt: timestamp,<br/>  reason: CANCELLED | EXPIRED<br/>}"]
    end

    subgraph StockAdjusted["StockAdjusted Event"]
        SA_Payload["payload: {<br/>  productId: uuid,<br/>  storeId: uuid,<br/>  delta: int,<br/>  reason: string,<br/>  referenceId: string?,<br/>  newOnHand: int,<br/>  adjustedAt: timestamp<br/>}"]
    end

    subgraph LowStockAlert["LowStockAlert Event"]
        LSA_Payload["payload: {<br/>  productId: uuid,<br/>  warehouseId: uuid,<br/>  currentQuantity: int,<br/>  threshold: int,<br/>  detectedAt: timestamp<br/>}"]
    end

    style SR_Payload fill:#4A90E2,color:#fff
    style SC_Payload fill:#7ED321,color:#000
    style SRel_Payload fill:#F5A623,color:#000
    style SA_Payload fill:#9013FE,color:#fff
    style LSA_Payload fill:#FF6B6B,color:#fff
```

## Prometheus Metrics Schema

```
Inventory Service Metrics
├─ http_server_requests_seconds{endpoint, method, status}
│  └ Histogram: Request latency distribution
│     - p50: 20ms, p99: <100ms (reserve)
│
├─ inventory_reservations_total{status, store_id}
│  └ Counter: Total reservations by outcome
│     - status: created, confirmed, cancelled, expired
│
├─ inventory_reservation_duration_seconds{operation}
│  └ Histogram: Operation latency
│     - operation: reserve, confirm, cancel
│
├─ inventory_stock_available{store_id, product_id}
│  └ Gauge: Current available stock
│     - available = on_hand - reserved
│
├─ inventory_stock_reserved{store_id, product_id}
│  └ Gauge: Current reserved stock
│
├─ inventory_low_stock_alerts_total{store_id}
│  └ Counter: Low stock alerts triggered
│
├─ inventory_lock_acquisition_duration_ms
│  └ Histogram: Time to acquire PESSIMISTIC_WRITE lock
│     - p99: <15ms typical
│
├─ inventory_lock_timeouts_total
│  └ Counter: Lock timeout exceptions
│
├─ inventory_rate_limit_rejections_total{endpoint}
│  └ Counter: Rate limit hits
│
├─ inventory_outbox_events_total{event_type}
│  └ Counter: Events published to outbox
│     - event_type: StockReserved, StockConfirmed, etc.
│
├─ inventory_expiry_job_runs_total{status}
│  └ Counter: Expiry job executions
│     - status: success, skipped, error
│
└─ inventory_expired_reservations_total
   └ Counter: Reservations expired by job
```

## Multi-Location Data Model

```mermaid
graph TB
    subgraph Catalog["Product Catalog"]
        Product["Product<br/>id: prod-123<br/>name: iPhone 15<br/>sku: IP15-128-BLK"]
    end

    subgraph Locations["Inventory Locations"]
        DS1["Darkstore A<br/>store_id: ds-001<br/>zone: downtown"]
        DS2["Darkstore B<br/>store_id: ds-002<br/>zone: uptown"]
        WH1["Central Warehouse<br/>store_id: wh-001<br/>type: fulfillment"]
    end

    subgraph StockLevels["Stock by Location"]
        Stock1["stock_items<br/>product_id: prod-123<br/>store_id: ds-001<br/>on_hand: 50<br/>reserved: 10"]
        Stock2["stock_items<br/>product_id: prod-123<br/>store_id: ds-002<br/>on_hand: 30<br/>reserved: 5"]
        Stock3["stock_items<br/>product_id: prod-123<br/>store_id: wh-001<br/>on_hand: 500<br/>reserved: 0"]
    end

    Product --> Stock1
    Product --> Stock2
    Product --> Stock3

    DS1 --> Stock1
    DS2 --> Stock2
    WH1 --> Stock3

    QueryNote["Query: findByStoreIdAndProductIdIn()<br/>Returns stock for specific location"]
    Stock1 -.-> QueryNote

    style Product fill:#4A90E2,color:#fff
    style DS1 fill:#52C41A,color:#fff
    style DS2 fill:#52C41A,color:#fff
    style WH1 fill:#F5A623,color:#000
```

## Data Retention & Cleanup

```mermaid
graph TB
    subgraph Policies["Retention Policies"]
        StockItems["stock_items<br/>Retention: Forever<br/>Current state only"]
        Reservations["reservations<br/>Retention: 90 days<br/>After terminal state"]
        AdjustmentLogs["stock_adjustment_logs<br/>Retention: 365 days<br/>Audit trail"]
        OutboxEvents["outbox_events<br/>Retention: 7 days<br/>After CDC processing"]
        AuditLogs["audit_logs<br/>Retention: 365 days<br/>Compliance"]
    end

    subgraph Jobs["Cleanup Jobs"]
        OutboxCleanup["OutboxCleanupJob<br/>@Scheduled + ShedLock<br/>Deletes events > 7 days"]
        AuditCleanup["AuditLogCleanupJob<br/>@Scheduled + ShedLock<br/>Archives logs > 365 days"]
    end

    OutboxEvents --> OutboxCleanup
    AuditLogs --> AuditCleanup

    style OutboxCleanup fill:#FF6B6B,color:#fff
    style AuditCleanup fill:#FF6B6B,color:#fff
```

## PostgreSQL Configuration

```yaml
# Key PostgreSQL settings for Inventory Service

# Connection pool (HikariCP defaults)
spring.datasource.hikari:
  maximum-pool-size: 20
  minimum-idle: 5
  connection-timeout: 30000
  idle-timeout: 600000

# Lock timeout for PESSIMISTIC_WRITE
inventory.lock-timeout-ms: 5000

# Indexes for query performance
# - (product_id, store_id) for stock lookups
# - (status, expires_at) for expiry job
# - (idempotency_key) for reservation dedup

# Vacuum settings (recommended)
# - autovacuum_vacuum_scale_factor: 0.1
# - autovacuum_analyze_scale_factor: 0.05
```
