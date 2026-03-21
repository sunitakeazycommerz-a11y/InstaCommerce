# Inventory Service - Entity-Relationship Diagram (ERD)

```mermaid
erDiagram
    STOCK ||--o{ RESERVATIONS : has

    STOCK {
        uuid id PK
        uuid product_id
        uuid store_id
        bigint quantity_available
        bigint quantity_reserved
        int low_stock_threshold
        timestamp created_at
        timestamp updated_at
        bigint version "optimistic lock"
    }

    RESERVATIONS {
        uuid id PK
        uuid order_id
        uuid product_id
        uuid store_id
        int quantity
        timestamp reserved_at
        timestamp expires_at "5 min TTL"
        boolean released
        timestamp created_at
    }
```

## Indexes

```sql
CREATE INDEX idx_stock_product_store ON stock(product_id, store_id);
CREATE INDEX idx_stock_created_at ON stock(created_at);
CREATE INDEX idx_reservations_order_id ON reservations(order_id);
CREATE INDEX idx_reservations_expires_at ON reservations(expires_at);
CREATE INDEX idx_reservations_product_store ON reservations(product_id, store_id);
```

## Data Flow

```mermaid
graph TD
    A["Order Placed"] -->|Reserve stock| B["Lock stock row<br/>SELECT...FOR UPDATE"]
    B -->|Decrement available<br/>Increment reserved| C["UPDATE stock table"]
    C -->|INSERT reservation| D["Reservation record<br/>expires_at = now() + 5min"]

    E["Fulfillment picks item"] -->|Release stock| F["Release API call"]
    F -->|Find reservation| G["Query by order_id"]
    G -->|Lock stock row| H["Restore quantities"]
    H -->|DELETE reservation| I["Cleanup"]

    J["Every 30 seconds"] -->|Scheduler runs| K["Query expired<br/>WHERE expires_at < now()"]
    K -->|For each| L["Lock stock row"]
    L -->|Restore qty| M["Increment available<br/>Decrement reserved"]
    M -->|DELETE row| N["Auto-cleanup"]
```

## Constraints & Uniqueness

```markdown
## Unique Constraint
- (product_id, store_id) on STOCK table
- Prevents duplicate stock records for same product/store

## Foreign Key
- RESERVATIONS.product_id -> STOCK.product_id
- RESERVATIONS.store_id -> STOCK.store_id
- Implicit: product + store must exist in STOCK

## Version Column
- Optimistic locking for STOCK table
- Prevents lost updates in concurrent scenarios
- Checked during UPDATE operations

## Reservation Constraint
- released boolean: false until explicitly released or auto-expired
- Helps track which reservations are still active
```

## Quantities Logic

```mermaid
graph TD
    A["Total Stock"] -->|Split into| B["qty_available<br/>(can reserve)"]
    A -->|Split into| C["qty_reserved<br/>(locked)"]
    B -->|Both always sum| D["qty_available +<br/>qty_reserved = total"]

    E["Reserve operation"] -->|qty_available -= qty| F["qty_reserved += qty"]
    G["Release operation"] -->|qty_available += qty| H["qty_reserved -= qty"]
    I["Expire operation"] -->|Expiry detected| J["qty_available += qty"]
    J -->|Restore| K["qty_reserved -= qty"]
```
