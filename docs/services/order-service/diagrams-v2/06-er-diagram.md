# Order Service - ER Diagram & Data Model

## Core Order Tables

```mermaid
erDiagram
    ORDERS ||--o{ ORDER_ITEMS : "contains"
    ORDERS ||--o{ ORDER_STATUS_HISTORY : "tracks"
    ORDERS ||--o{ OUTBOX_EVENTS : "publishes"
    ORDERS ||--o{ AUDIT_LOG : "audited by"

    ORDERS {
        uuid id PK "Primary key, auto-generated"
        uuid user_id FK "Customer who placed order"
        boolean user_erased "GDPR: user data erased"
        varchar(50) store_id "Fulfillment store"
        order_status status "PENDING, PLACED, PACKING, etc."
        bigint subtotal_cents "Sum of line items"
        bigint discount_cents "Applied discounts"
        bigint total_cents "Final amount charged"
        varchar(3) currency "INR, USD, etc."
        varchar(30) coupon_code "Applied coupon"
        uuid reservation_id "Inventory reservation"
        uuid payment_id "Payment transaction"
        varchar(64) idempotency_key UK "Duplicate prevention"
        text cancellation_reason "Why order was cancelled"
        text delivery_address "Delivery destination"
        uuid quote_id "Pricing quote reference"
        timestamptz created_at "Order creation time"
        timestamptz updated_at "Last modification"
        bigint version "Optimistic locking"
    }

    ORDER_ITEMS {
        uuid id PK "Primary key"
        uuid order_id FK "Parent order"
        uuid product_id "Catalog product"
        varchar(255) product_name "Product display name"
        varchar(50) product_sku "Stock keeping unit"
        int quantity "Quantity ordered"
        bigint unit_price_cents "Price per unit"
        bigint line_total_cents "qty × unit_price"
        varchar(20) picked_status "PENDING, PICKED, UNAVAILABLE"
    }

    ORDER_STATUS_HISTORY {
        bigserial id PK "Auto-increment ID"
        uuid order_id FK "Order reference"
        order_status from_status "Previous status (nullable for initial)"
        order_status to_status "New status"
        varchar(100) changed_by "Actor: user:UUID, admin:UUID, system"
        text note "Reason or context"
        timestamptz created_at "Transition timestamp"
    }

    OUTBOX_EVENTS {
        uuid id PK "Event ID"
        varchar(50) aggregate_type "Order"
        varchar(100) aggregate_id "Order ID"
        varchar(50) event_type "OrderCreated, OrderPlaced, etc."
        text payload "JSON event data"
        varchar(10) schema_version "Event schema version"
        varchar(50) source_service "order-service"
        varchar(100) correlation_id "Trace correlation"
        timestamptz created_at "Event timestamp"
        boolean published "CDC processed flag"
    }

    AUDIT_LOG {
        uuid id PK "Audit entry ID"
        uuid actor_id FK "User who performed action"
        varchar(50) action "ORDER_PLACED, ORDER_CANCELLED"
        varchar(50) resource_type "Order"
        varchar(100) resource_id "Order ID"
        text details "JSON action details"
        varchar(45) ip_address "Client IP"
        timestamptz created_at "Action timestamp"
    }

    SHEDLOCK {
        varchar(64) name PK "Lock name"
        timestamptz lock_until "Lock expiry"
        timestamptz locked_at "Lock acquisition time"
        varchar(255) locked_by "Instance identifier"
    }
```

## Detailed Schema: orders Table

```sql
-- PostgreSQL ENUM type for order status
CREATE TYPE order_status AS ENUM (
    'PENDING',
    'PLACED',
    'PACKING',
    'PACKED',
    'OUT_FOR_DELIVERY',
    'DELIVERED',
    'CANCELLED',
    'FAILED'
);

-- Main orders table
CREATE TABLE orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID            NOT NULL,
    user_erased         BOOLEAN         NOT NULL DEFAULT false,
    store_id            VARCHAR(50)     NOT NULL,
    status              order_status    NOT NULL DEFAULT 'PENDING',
    subtotal_cents      BIGINT          NOT NULL,
    discount_cents      BIGINT          NOT NULL DEFAULT 0,
    total_cents         BIGINT          NOT NULL,
    currency            VARCHAR(3)      NOT NULL DEFAULT 'INR',
    coupon_code         VARCHAR(30),
    reservation_id      UUID,
    payment_id          UUID,
    idempotency_key     VARCHAR(64)     NOT NULL,
    cancellation_reason TEXT,
    delivery_address    TEXT,
    quote_id            UUID,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version             BIGINT          NOT NULL DEFAULT 0,
    
    CONSTRAINT uq_order_idempotency UNIQUE (idempotency_key)
);

-- Performance indexes
CREATE INDEX idx_orders_user        ON orders (user_id);
CREATE INDEX idx_orders_status      ON orders (status);
CREATE INDEX idx_orders_created     ON orders (created_at DESC);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
```

## Detailed Schema: order_items Table

```sql
CREATE TABLE order_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id         UUID         NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id       UUID         NOT NULL,
    product_name     VARCHAR(255) NOT NULL,
    product_sku      VARCHAR(50)  NOT NULL,
    quantity         INT          NOT NULL,
    unit_price_cents BIGINT       NOT NULL,
    line_total_cents BIGINT       NOT NULL,
    picked_status    VARCHAR(20)  DEFAULT 'PENDING',
    
    CONSTRAINT chk_qty CHECK (quantity > 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
```

## Detailed Schema: order_status_history Table

```sql
CREATE TABLE order_status_history (
    id          BIGSERIAL    PRIMARY KEY,
    order_id    UUID         NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status order_status,
    to_status   order_status NOT NULL,
    changed_by  VARCHAR(100),
    note        TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_osh_order ON order_status_history (order_id);
```

## Detailed Schema: outbox_events Table

```sql
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload         TEXT         NOT NULL,
    schema_version  VARCHAR(10)  NOT NULL DEFAULT '1',
    source_service  VARCHAR(50)  NOT NULL DEFAULT 'order-service',
    correlation_id  VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published       BOOLEAN      NOT NULL DEFAULT false
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE NOT published;
```

## Detailed Schema: audit_log Table

```sql
CREATE TABLE audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id      UUID,
    action        VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id   VARCHAR(100) NOT NULL,
    details       TEXT,
    ip_address    VARCHAR(45),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_actor ON audit_log (actor_id);
CREATE INDEX idx_audit_resource ON audit_log (resource_type, resource_id);
CREATE INDEX idx_audit_created ON audit_log (created_at DESC);
```

## Entity Relationships

```mermaid
graph TB
    subgraph core["Core Entities"]
        Order["📋 Order<br/>(aggregate root)"]
        OrderItem["📦 OrderItem<br/>(value object)"]
    end

    subgraph tracking["Tracking Entities"]
        StatusHistory["📜 OrderStatusHistory<br/>(immutable log)"]
        AuditLog["📝 AuditLog<br/>(compliance)"]
    end

    subgraph messaging["Messaging Entities"]
        OutboxEvent["📤 OutboxEvent<br/>(transactional outbox)"]
    end

    subgraph infrastructure["Infrastructure"]
        ShedLock["🔒 ShedLock<br/>(distributed locking)"]
    end

    Order -->|"1:N cascaded"| OrderItem
    Order -->|"1:N cascaded"| StatusHistory
    Order -->|"1:N same txn"| OutboxEvent
    Order -->|"1:N reference"| AuditLog

    ShedLock -.->|"OutboxCleanupJob"| OutboxEvent

    style Order fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style OutboxEvent fill:#50E3C2,color:#000
    style StatusHistory fill:#9013FE,color:#fff
```

## Data Flow: Order Creation

```mermaid
graph LR
    subgraph transaction["PostgreSQL Transaction"]
        A["INSERT orders"]
        B["INSERT order_items<br/>(for each item)"]
        C["INSERT order_status_history<br/>(PENDING)"]
        D["INSERT outbox_events<br/>(OrderCreated)"]
    end

    E["COMMIT"]
    F["Debezium CDC"]
    G["Kafka: order.events"]

    A --> B --> C --> D --> E
    E --> F --> G

    style transaction fill:#E8F5E9,color:#000,stroke:#333,stroke-width:2px
    style E fill:#7ED321,color:#000
    style G fill:#50E3C2,color:#000
```

## Index Strategy

```mermaid
graph TB
    subgraph orders_idx["orders Indexes"]
        I1["idx_orders_user<br/>(user_id)<br/>→ User's order history"]
        I2["idx_orders_status<br/>(status)<br/>→ Orders by status"]
        I3["idx_orders_created<br/>(created_at DESC)<br/>→ Recent orders"]
        I4["idx_orders_user_created<br/>(user_id, created_at DESC)<br/>→ User's recent orders"]
        I5["uq_order_idempotency<br/>(idempotency_key) UNIQUE<br/>→ Duplicate prevention"]
    end

    subgraph items_idx["order_items Indexes"]
        I6["idx_order_items_order<br/>(order_id)<br/>→ Items for order"]
    end

    subgraph history_idx["order_status_history Indexes"]
        I7["idx_osh_order<br/>(order_id)<br/>→ History for order"]
    end

    subgraph outbox_idx["outbox_events Indexes"]
        I8["idx_outbox_unpublished<br/>(created_at) WHERE NOT published<br/>→ Pending events"]
    end

    subgraph audit_idx["audit_log Indexes"]
        I9["idx_audit_actor<br/>(actor_id)<br/>→ Actions by user"]
        I10["idx_audit_resource<br/>(resource_type, resource_id)<br/>→ Actions on resource"]
        I11["idx_audit_created<br/>(created_at DESC)<br/>→ Recent actions"]
    end
```

## Query Patterns

```sql
-- List user's orders (paginated, recent first)
SELECT * FROM orders
WHERE user_id = ?
ORDER BY created_at DESC
LIMIT 20 OFFSET 0;
-- Uses: idx_orders_user_created

-- Get order with items
SELECT o.*, oi.*
FROM orders o
LEFT JOIN order_items oi ON oi.order_id = o.id
WHERE o.id = ?;
-- Uses: PK, idx_order_items_order

-- Get order status history (timeline)
SELECT * FROM order_status_history
WHERE order_id = ?
ORDER BY created_at ASC;
-- Uses: idx_osh_order

-- Check idempotency (before creating)
SELECT id FROM orders
WHERE idempotency_key = ?;
-- Uses: uq_order_idempotency

-- Get pending outbox events (CDC backup)
SELECT * FROM outbox_events
WHERE NOT published
ORDER BY created_at ASC
LIMIT 100;
-- Uses: idx_outbox_unpublished

-- Audit trail for order
SELECT * FROM audit_log
WHERE resource_type = 'Order'
  AND resource_id = ?
ORDER BY created_at DESC;
-- Uses: idx_audit_resource
```

## Data Retention & Cleanup

```mermaid
graph TB
    subgraph retention["Data Retention Policy"]
        Orders["📋 Orders<br/>Retained: 7 years<br/>(regulatory)"]
        StatusHistory["📜 Status History<br/>Retained: 7 years<br/>(audit trail)"]
        AuditLog["📝 Audit Log<br/>Retained: 7 years<br/>(compliance)"]
        OutboxEvents["📤 Outbox Events<br/>Cleaned: 7 days<br/>(after published)"]
    end

    subgraph cleanup["Cleanup Jobs"]
        OutboxCleanup["OutboxCleanupJob<br/>(ShedLock protected)<br/>Runs: Every 1 hour<br/>Deletes published events > 7 days"]
    end

    OutboxCleanup -->|"DELETE FROM outbox_events<br/>WHERE published = true<br/>AND created_at < now() - 7 days"| OutboxEvents

    style OutboxCleanup fill:#F5A623,color:#000
```

## GDPR: User Erasure

```mermaid
graph TD
    A["User Erasure Request<br/>(GDPR Right to Erasure)"]
    B["UserErasureService.eraseUser(userId)"]
    C["Find all orders for user"]
    D["For each order:<br/>Anonymize PII"]
    E["Set user_erased = true"]
    F["Clear delivery_address"]
    G["Retain order for<br/>financial/regulatory purposes"]

    A --> B --> C --> D --> E --> F --> G

    note1["Orders are anonymized,<br/>not deleted, to preserve<br/>financial records"]
    G -.-> note1

    style A fill:#FF6B6B,color:#fff
    style G fill:#7ED321,color:#000
```
