# Inventory Service

Stock management, reservation (for checkout), stock adjustments, and low-stock alerts for the InstaCommerce platform.

Built with Spring Boot 3 on Java 21. Uses PostgreSQL for persistence, the transactional outbox pattern for reliable event publishing to Kafka (`inventory.events`), and pessimistic locking for safe concurrent stock mutations.

---

## Table of Contents

- [Service Architecture](#service-architecture)
- [Stock Reservation Flow](#stock-reservation-flow)
- [Inventory Adjustment Flow](#inventory-adjustment-flow)
- [Low-Stock Alert Pipeline](#low-stock-alert-pipeline)
- [Database Schema](#database-schema)
- [API Reference](#api-reference)
- [Key Components](#key-components)
- [Configuration](#configuration)
- [Running Locally](#running-locally)

---

## Service Architecture

```mermaid
graph TB
    Client([API Clients])

    subgraph inventory-service
        RC[ReservationController]
        SC[StockController]
        RS[ReservationService]
        IS[InventoryService]
        RLS[RateLimitService]
        OBS[OutboxService]
        ALS[AuditLogService]
        REJ[ReservationExpiryJob]
        OCJ[OutboxCleanupJob]
        ACJ[AuditLogCleanupJob]
    end

    subgraph Data Stores
        PG[(PostgreSQL)]
        KF[[Kafka<br/>inventory.events]]
    end

    Client -->|REST| RC
    Client -->|REST| SC
    SC --> RLS
    SC --> IS
    RC --> RS
    IS --> OBS
    IS --> ALS
    RS --> OBS
    RS --> ALS
    REJ -->|scheduled| RS
    OBS --> PG
    ALS --> PG
    IS --> PG
    RS --> PG
    OCJ -->|purge sent events| PG
    ACJ -->|purge old logs| PG
    PG -.->|CDC / poll| KF
```

---

## Stock Reservation Flow

The reservation lifecycle follows a **reserve → confirm / cancel** pattern with automatic expiry for abandoned reservations.

```mermaid
stateDiagram-v2
    [*] --> PENDING : POST /inventory/reserve
    PENDING --> CONFIRMED : POST /inventory/confirm
    PENDING --> CANCELLED : POST /inventory/cancel
    PENDING --> EXPIRED : ReservationExpiryJob

    CONFIRMED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]

    note right of PENDING
        Reserved qty added to stock_items.reserved.
        TTL = reservation.ttl-minutes (default 5 min).
    end note

    note right of CONFIRMED
        on_hand and reserved both decremented
        by reserved quantity (stock consumed).
        Publishes StockConfirmed event.
    end note

    note right of CANCELLED
        reserved decremented, on_hand unchanged
        (stock returned to available pool).
        Publishes StockReleased event.
    end note

    note right of EXPIRED
        Same release as CANCELLED.
        Publishes StockReleased event
        with reason=EXPIRED.
    end note
```

**Detailed reserve → confirm flow:**

```mermaid
sequenceDiagram
    participant Client
    participant RC as ReservationController
    participant RS as ReservationService
    participant DB as PostgreSQL
    participant OB as OutboxService

    Client->>RC: POST /inventory/reserve
    RC->>RS: reserve(request)
    RS->>DB: Check idempotency_key (return early if exists)
    RS->>DB: SELECT ... FOR UPDATE (lock stock rows, sorted by productId)
    RS->>RS: Validate available >= requested
    RS->>DB: Increment stock_items.reserved
    RS->>DB: INSERT reservation + line items
    RS->>OB: publish("StockReserved")
    OB->>DB: INSERT outbox_events
    RS-->>Client: ReserveResponse (reservationId, expiresAt, items)

    Note over Client,DB: Later — checkout completes

    Client->>RC: POST /inventory/confirm
    RC->>RS: confirm(reservationId)
    RS->>DB: Load reservation, verify PENDING & not expired
    RS->>DB: SELECT ... FOR UPDATE (lock stock rows)
    RS->>DB: Decrement on_hand and reserved
    RS->>DB: Set status = CONFIRMED
    RS->>OB: publish("StockConfirmed")
    RS-->>Client: 204 No Content
```

---

## Inventory Adjustment Flow

Stock adjustments (receiving, shrinkage, corrections) are performed by admins and require the `ADMIN` role.

```mermaid
sequenceDiagram
    participant Admin
    participant SC as StockController
    participant IS as InventoryService
    participant DB as PostgreSQL
    participant AL as AuditLogService
    participant OB as OutboxService

    Admin->>SC: POST /inventory/adjust (or /adjust-batch)
    SC->>IS: adjustStock(request)
    IS->>DB: SELECT ... FOR UPDATE (pessimistic lock, sorted by productId)
    IS->>IS: Validate: resulting on_hand >= 0, on_hand >= reserved
    IS->>DB: UPDATE stock_items.on_hand
    IS->>DB: INSERT stock_adjustment_log
    IS->>AL: log("STOCK_ADJUSTED")
    AL->>DB: INSERT audit_log
    IS->>OB: publish("StockAdjusted")
    OB->>DB: INSERT outbox_events
    IS->>IS: Check low-stock threshold
    alt available <= threshold
        IS->>OB: publish("LowStockAlert")
    end
    IS-->>Admin: StockCheckItemResponse
```

---

## Low-Stock Alert Pipeline

Alerts are evaluated whenever available stock (`on_hand − reserved`) drops to or below the configurable threshold.

```mermaid
flowchart LR
    A[Stock Mutation] -->|adjust / reserve / confirm| B{available <= threshold?}
    B -->|Yes| C[OutboxService.publish<br/>LowStockAlert]
    C --> D[(outbox_events table)]
    D -->|CDC / poller| E[[Kafka<br/>inventory.events]]
    E --> F[Notification Service /<br/>Alerting Pipeline]
    B -->|No| G[No action]
```

**LowStockAlert event payload:**

```json
{
  "productId": "uuid",
  "warehouseId": "store-01",
  "currentQuantity": 8,
  "threshold": 10,
  "detectedAt": "2025-01-15T10:30:00Z"
}
```

**Trigger points:**

| Operation | Condition |
|---|---|
| `adjustStock` / `adjustStockBatch` | When `delta < 0` and resulting available ≤ threshold |
| `reserve` | After reserving stock, if available ≤ threshold |
| `confirm` | After decrementing `on_hand`, if available ≤ threshold |

---

## Database Schema

```mermaid
erDiagram
    stock_items {
        UUID id PK
        UUID product_id UK
        VARCHAR store_id UK
        INT on_hand
        INT reserved
        TIMESTAMPTZ updated_at
        BIGINT version
    }

    reservations {
        UUID id PK
        VARCHAR idempotency_key UK
        VARCHAR store_id
        reservation_status status
        TIMESTAMPTZ expires_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }

    reservation_line_items {
        UUID id PK
        UUID reservation_id FK
        UUID product_id
        INT quantity
    }

    stock_adjustment_log {
        BIGSERIAL id PK
        UUID product_id
        VARCHAR store_id
        INT delta
        VARCHAR reason
        VARCHAR reference_id
        UUID actor_id
        TIMESTAMPTZ created_at
    }

    outbox_events {
        UUID id PK
        VARCHAR aggregate_type
        VARCHAR aggregate_id
        VARCHAR event_type
        JSONB payload
        TIMESTAMPTZ created_at
        BOOLEAN sent
    }

    audit_log {
        UUID id PK
        UUID user_id
        VARCHAR action
        VARCHAR entity_type
        VARCHAR entity_id
        JSONB details
        VARCHAR ip_address
        TEXT user_agent
        VARCHAR trace_id
        TIMESTAMPTZ created_at
    }

    reservations ||--o{ reservation_line_items : "has"
```

**Key constraints:**

- `stock_items`: unique on `(product_id, store_id)`, check `on_hand >= 0`, check `reserved >= 0`, check `reserved <= on_hand`
- `reservations`: unique on `idempotency_key`; partial indexes on `status = 'PENDING'` for expiry queries
- `reservation_line_items`: FK to `reservations` with `ON DELETE CASCADE`, check `quantity > 0`

---

## API Reference

All endpoints are served under the `/inventory` base path.

### Reservation Endpoints

| Method | Path | Auth | Description | Request Body | Success Response |
|---|---|---|---|---|---|
| `POST` | `/inventory/reserve` | JWT | Reserve stock for checkout | `ReserveRequest` | `200` — `ReserveResponse` |
| `POST` | `/inventory/confirm` | JWT | Confirm reservation (deduct stock) | `ConfirmRequest` | `204` No Content |
| `POST` | `/inventory/cancel` | JWT | Cancel reservation (release stock) | `CancelRequest` | `204` No Content |

### Stock Endpoints

| Method | Path | Auth | Description | Request Body | Success Response |
|---|---|---|---|---|---|
| `POST` | `/inventory/check` | JWT | Check stock availability | `StockCheckRequest` | `200` — `StockCheckResponse` |
| `POST` | `/inventory/adjust` | `ADMIN` | Adjust stock for a single product | `StockAdjustRequest` | `200` — `StockCheckItemResponse` |
| `POST` | `/inventory/adjust-batch` | `ADMIN` | Adjust stock for multiple products | `StockAdjustBatchRequest` | `200` — `StockCheckResponse` |

### Request / Response Schemas

<details>
<summary><strong>ReserveRequest</strong></summary>

```json
{
  "idempotencyKey": "order-abc-123",
  "storeId": "store-01",
  "items": [
    { "productId": "uuid", "quantity": 2 }
  ]
}
```
</details>

<details>
<summary><strong>ReserveResponse</strong></summary>

```json
{
  "reservationId": "uuid",
  "expiresAt": "2025-01-15T10:35:00Z",
  "items": [
    { "productId": "uuid", "quantity": 2 }
  ]
}
```
</details>

<details>
<summary><strong>ConfirmRequest / CancelRequest</strong></summary>

```json
{ "reservationId": "uuid" }
```
</details>

<details>
<summary><strong>StockCheckRequest</strong></summary>

```json
{
  "storeId": "store-01",
  "items": [
    { "productId": "uuid", "quantity": 5 }
  ]
}
```
</details>

<details>
<summary><strong>StockCheckResponse</strong></summary>

```json
{
  "items": [
    { "productId": "uuid", "available": 42, "onHand": 50, "sufficient": true }
  ]
}
```
</details>

<details>
<summary><strong>StockAdjustRequest</strong></summary>

```json
{
  "productId": "uuid",
  "storeId": "store-01",
  "delta": -5,
  "reason": "SHRINKAGE",
  "referenceId": "optional-ref"
}
```
</details>

<details>
<summary><strong>StockAdjustBatchRequest</strong></summary>

```json
{
  "storeId": "store-01",
  "reason": "RECEIVING",
  "referenceId": "PO-2025-001",
  "items": [
    { "productId": "uuid-1", "delta": 100 },
    { "productId": "uuid-2", "delta": 50 }
  ]
}
```
</details>

### Error Response

All errors follow a standard envelope:

```json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "Insufficient stock for product ...",
  "traceId": "abc123",
  "timestamp": "2025-01-15T10:30:00Z",
  "details": []
}
```

| HTTP Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Invalid request body / constraint violation |
| `403` | `ACCESS_DENIED` | Missing `ADMIN` role for adjust endpoints |
| `404` | `PRODUCT_NOT_FOUND` | Product/store combination does not exist |
| `404` | `RESERVATION_NOT_FOUND` | Reservation ID not found |
| `409` | `INSUFFICIENT_STOCK` | Not enough available stock to reserve |
| `409` | `RESERVATION_EXPIRED` | Reservation TTL exceeded |
| `409` | `INVALID_RESERVATION_STATE` | Invalid state transition (e.g., cancel a confirmed reservation) |
| `409` | `INVALID_STOCK_ADJUSTMENT` | Adjustment would result in negative stock |
| `429` | — | Rate limit exceeded (`/inventory/check`) |

---

## Key Components

| Component | Role |
|---|---|
| `ReservationController` | REST endpoints for reserve / confirm / cancel |
| `StockController` | REST endpoints for stock check and adjustments |
| `ReservationService` | Reservation lifecycle — reserve, confirm, cancel, expire. Pessimistic locking with sorted product IDs to prevent deadlocks |
| `InventoryService` | Stock availability checks and adjustments. Pessimistic locking, audit logging, outbox event publishing |
| `OutboxService` | Writes domain events to `outbox_events` within the same transaction (`Propagation.MANDATORY`). A CDC connector or poller publishes these to Kafka |
| `RateLimitService` | Per-IP rate limiting using Resilience4j + Caffeine cache (default 50 req / 60 s) |
| `AuditLogService` | Records admin actions with user, IP, User-Agent, and trace ID |
| `ReservationExpiryJob` | Scheduled job (ShedLock) that expires stale `PENDING` reservations in batches of 100 |
| `OutboxCleanupJob` | Purges sent outbox events older than 30 days (daily at 04:00) |
| `AuditLogCleanupJob` | Purges audit logs older than the retention period (default 730 days, daily at 03:00) |

---

## Configuration

Key properties in `application.yml` (override via environment variables):

| Property | Env Var | Default | Description |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8083` | HTTP listen port |
| `spring.datasource.url` | `INVENTORY_DB_URL` | `jdbc:postgresql://localhost:5432/inventory` | PostgreSQL connection |
| `inventory.low-stock-threshold` | `INVENTORY_LOW_STOCK_THRESHOLD` | `10` | Available qty at or below which `LowStockAlert` fires |
| `inventory.lock-timeout-ms` | `INVENTORY_LOCK_TIMEOUT_MS` | `2000` | Pessimistic lock wait timeout (ms) |
| `reservation.ttl-minutes` | `INVENTORY_RESERVATION_TTL_MINUTES` | `5` | Reservation time-to-live |
| `reservation.expiry-check-interval-ms` | `INVENTORY_RESERVATION_EXPIRY_INTERVAL_MS` | `30000` | Expiry job polling interval |
| `rate-limit.requests-per-period` | — | `50` | Max requests per period per IP |
| `rate-limit.period-seconds` | — | `60` | Rate limit window (seconds) |

---

## Running Locally

```bash
# Start PostgreSQL
docker run -d --name inventory-pg \
  -e POSTGRES_DB=inventory \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 postgres:16

# Build & run
./gradlew :services:inventory-service:bootRun

# Or via Docker
docker build -t inventory-service services/inventory-service
docker run -p 8083:8080 \
  -e INVENTORY_DB_URL=jdbc:postgresql://host.docker.internal:5432/inventory \
  -e INVENTORY_DB_PASSWORD=postgres \
  -e INVENTORY_JWT_PUBLIC_KEY="<pem>" \
  inventory-service
```

### Health Check

```
GET /actuator/health/liveness   → liveness probe
GET /actuator/health/readiness  → readiness probe (includes DB)
```
