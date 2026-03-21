# Inventory Service - Low-Level Design (LLD)

```mermaid
graph TB
    subgraph "HTTP Layer"
        ReserveCtrl["ReservationController<br/>POST /inventory/reserve<br/>POST /inventory/release"]
        StockCtrl["StockController<br/>GET /inventory/{productId}<br/>POST /inventory/{productId}/adjust"]
    end

    subgraph "Service Layer"
        ReserveSvc["ReservationService<br/>- reserve()<br/>- release()<br/>- checkExpiry()"]
        StockSvc["StockService<br/>- checkStock()<br/>- adjustStock()"]
    end

    subgraph "Repository Layer"
        StockRepo["StockRepository<br/>findByProductAndStore<br/>with row lock"]
        ReservRepo["ReservationRepository<br/>findByOrderId"]
    end

    subgraph "Data Layer"
        PostgreSQL["PostgreSQL<br/>- stock table<br/>- reservations table<br/>- outbox table"]
    end

    subgraph "Infrastructure"
        Scheduler["Reservation<br/>Expiry Scheduler<br/>Every 30s"]
    end

    ReserveCtrl --> ReserveSvc
    StockCtrl --> StockSvc
    ReserveSvc --> StockRepo
    ReserveSvc --> ReservRepo
    StockSvc --> StockRepo
    StockRepo -->|SELECT...FOR UPDATE| PostgreSQL
    ReservRepo --> PostgreSQL
    Scheduler -->|DELETE expired| PostgreSQL
```

## Reservation Lifecycle

```sql
create table stock (
    id uuid primary key,
    product_id uuid not null,
    store_id uuid not null,
    quantity_available bigint,
    quantity_reserved bigint,
    low_stock_threshold int,
    created_at timestamp,
    version bigint,
    unique(product_id, store_id)
);

create table reservations (
    id uuid primary key,
    order_id uuid not null,
    product_id uuid not null,
    store_id uuid not null,
    quantity int,
    reserved_at timestamp,
    expires_at timestamp,  -- 5 min TTL
    released boolean default false,
    created_at timestamp
);

create index idx_reservations_order_id on reservations(order_id);
create index idx_reservations_expires_at on reservations(expires_at);
create index idx_stock_product_store on stock(product_id, store_id);
```

## Concurrency Control

```mermaid
graph TD
    A["POST /inventory/reserve"] -->|SELECT...FOR UPDATE| B["Lock stock row"]
    B -->|Check quantity| C{"Enough qty<br/>available?"}
    C -->|No| D["HTTP 409 Out of Stock"]
    C -->|Yes| E["Decrement available"]
    E -->|Increment reserved| F["INSERT into reservations"]
    F -->|Release lock| G["Commit transaction"]
    G -->|HTTP 200| H["ReservationResponse"]

    I["Concurrent request<br/>same product"] -->|SELECT...FOR UPDATE| J["Wait for lock<br/>on stock row"]
    J -->|Lock acquired<br/>after Thread 1| K["Check updated qty"]
    K -->|After T1 reserved| L["Possibly insufficient"]
    L -->|Decision made| M["Reserve or fail"]
```
