# Order Service

Manages the order lifecycle from placement through delivery or cancellation. Uses **Temporal** for checkout workflow orchestration (saga pattern with automatic compensation) and **Kafka** for event publishing via the transactional outbox pattern.

## Key Components

| Layer | Component | Responsibility |
|-------|-----------|----------------|
| Controller | `OrderController` | Customer-facing order endpoints (`/orders`) |
| Controller | `CheckoutController` | Initiates Temporal checkout workflow (`/checkout`) |
| Controller | `AdminOrderController` | Admin order management (`/admin/orders`) |
| Service | `OrderService` | Order CRUD, state transitions, outbox publishing |
| Service | `OutboxService` | Transactional outbox writes (runs inside caller's `@Transactional`) |
| Service | `OutboxCleanupJob` | ShedLock-guarded cron that purges sent events older than 30 days |
| Workflow | `CheckoutWorkflow` / `CheckoutWorkflowImpl` | Temporal saga — reserve → pay → create order |
| Activity | `InventoryActivities` | Reserve / confirm / cancel inventory via inventory-service |
| Activity | `PaymentActivities` | Authorize / capture / void payment via payment-service |
| Activity | `OrderActivities` | Create order, cancel order, update status |
| Activity | `CartActivities` | Clear cart after successful checkout |
| Consumer | `IdentityEventConsumer` | Listens to `identity.events` for GDPR user-erasure |
| Domain | `OrderStateMachine` | Enforces valid state transitions |

## Architecture

### 1. Order State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> PLACED: Checkout saga completes
    PENDING --> FAILED: Saga fails
    PLACED --> PACKING: Fulfillment picks
    PACKING --> PACKED: Pick complete
    PACKED --> OUT_FOR_DELIVERY: Rider picked up
    OUT_FOR_DELIVERY --> DELIVERED: Delivery confirmed
    PLACED --> CANCELLED: Cancel requested
    PACKING --> CANCELLED: Cancel during pick
    PACKED --> CANCELLED: Cancel before dispatch
    CANCELLED --> [*]
    DELIVERED --> [*]
    FAILED --> [*]
```

Valid transitions enforced by `OrderStateMachine`:

| From | Allowed To |
|------|-----------|
| `PENDING` | `PLACED`, `FAILED`, `CANCELLED` |
| `PLACED` | `PACKING`, `CANCELLED` |
| `PACKING` | `PACKED`, `CANCELLED` |
| `PACKED` | `OUT_FOR_DELIVERY`, `CANCELLED` |
| `OUT_FOR_DELIVERY` | `DELIVERED` |
| `DELIVERED` | _(terminal)_ |
| `CANCELLED` | _(terminal)_ |
| `FAILED` | _(terminal)_ |

---

### 2. Checkout Saga (Temporal Workflow)

```mermaid
sequenceDiagram
    participant Client
    participant CheckoutController
    participant Temporal
    participant InventoryActivities
    participant PaymentActivities
    participant OrderActivities
    participant CartActivities

    Client->>CheckoutController: POST /checkout
    CheckoutController->>Temporal: Start CheckoutWorkflow<br/>(idempotencyKey as workflowId)

    Note over Temporal: Step 1 — Validate pricing
    Temporal->>Temporal: Compute subtotal, apply discount<br/>Fail if discount > subtotal

    Note over Temporal: Step 2 — Reserve inventory
    Temporal->>InventoryActivities: reserveInventory(idempotencyKey, storeId, items)
    InventoryActivities-->>Temporal: ReserveResult(reservationId)
    Note right of Temporal: Compensation: cancelReservation(reservationId)

    Note over Temporal: Step 3 — Authorize payment
    Temporal->>PaymentActivities: authorizePayment(idempotencyKey, totalCents, currency)
    PaymentActivities-->>Temporal: PaymentResult(paymentId)
    Note right of Temporal: Compensation: voidPayment(paymentId)

    Note over Temporal: Step 4 — Create order (PENDING)
    Temporal->>OrderActivities: createOrder(CreateOrderCommand)
    OrderActivities-->>Temporal: orderId
    Note right of Temporal: Compensation: cancelOrder(orderId)

    Note over Temporal: Step 5 — Confirm reservation
    Temporal->>InventoryActivities: confirmReservation(reservationId)

    Note over Temporal: Step 6 — Capture payment
    Temporal->>PaymentActivities: capturePayment(paymentId)

    Note over Temporal: Step 7 — Clear cart (best-effort)
    Temporal->>CartActivities: clearCart(userId)

    Note over Temporal: Step 8 — Finalize order (PLACED)
    Temporal->>OrderActivities: updateOrderStatus(orderId, PLACED)

    Temporal-->>CheckoutController: CheckoutResult(orderId)
    CheckoutController-->>Client: 200 OK {orderId, workflowId}

    Note over Temporal: On failure at any step
    Temporal->>Temporal: saga.compensate() — runs compensations<br/>in reverse registration order
    Temporal-->>CheckoutController: CheckoutResult(errorMessage)
    CheckoutController-->>Client: 422 Unprocessable Entity
```

**Activity retry configuration:**

| Activity | Timeout | Max Attempts | Backoff | Non-retryable Exceptions |
|----------|---------|-------------|---------|--------------------------|
| `InventoryActivities` | 10 s | 3 | 1 s × 2.0 coeff | `InsufficientStockException` |
| `PaymentActivities` | 30 s | 3 | 2 s | `PaymentDeclinedException` |
| `OrderActivities` | 10 s | 3 | default | — |
| `CartActivities` | 5 s | 2 | default | — |

---

### 3. Service Architecture

```mermaid
flowchart TD
    subgraph Controllers
        OC[OrderController<br/>/orders]
        CC[CheckoutController<br/>/checkout]
        AOC[AdminOrderController<br/>/admin/orders]
    end

    subgraph Security
        JWT[JwtAuthenticationFilter]
        ISAF[InternalServiceAuthFilter]
    end

    subgraph Services
        OS[OrderService]
        OBS[OutboxService]
        ALS[AuditLogService]
        RLS[RateLimitService]
    end

    subgraph Temporal
        CW[CheckoutWorkflow]
        IA[InventoryActivities]
        PA[PaymentActivities]
        OA[OrderActivities]
        CA[CartActivities]
    end

    subgraph Repositories
        OR[OrderRepository]
        OIR[OrderItemRepository]
        OSHR[OrderStatusHistoryRepository]
        OER[OutboxEventRepository]
    end

    subgraph Infrastructure
        PG[(PostgreSQL)]
        KF[/Kafka\]
        TS[Temporal Server]
    end

    subgraph External Services
        INV[inventory-service]
        PAY[payment-service]
        CART[cart-service]
    end

    JWT --> OC & CC & AOC
    ISAF --> OC

    CC -->|rate limit check| RLS
    CC -->|start workflow| TS
    TS --> CW
    CW --> IA -->|HTTP| INV
    CW --> PA -->|HTTP| PAY
    CW --> OA --> OS
    CW --> CA -->|HTTP| CART

    OC --> OS
    AOC --> OS
    OS --> OR & OIR & OSHR
    OS --> OBS --> OER
    OS --> ALS
    OR & OIR & OSHR & OER --> PG
    OER -.->|CDC / poll| KF
```

---

### 4. Event Flow

```mermaid
flowchart LR
    subgraph Order Service
        OS[OrderService]
        OBS[OutboxService]
        OE[(outbox_events)]
        IEC[IdentityEventConsumer]
    end

    subgraph Kafka Topics
        OEV[order.events]
        IEV[identity.events]
        DLT[*.DLT]
    end

    subgraph Downstream
        FS[fulfillment-service]
        NS[notification-service]
        AS[analytics-service]
    end

    OS -->|@Transactional| OBS --> OE
    OE -->|CDC / poll relay| OEV
    OEV --> FS & NS & AS
    IEV --> IEC
    OEV -.->|on failure| DLT
```

**Published events** (via outbox → `order.events`):

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `OrderCreated` | Order row inserted | `orderId`, `userId`, `status` |
| `OrderPlaced` | Status → `PLACED` | `orderId`, `userId`, `storeId`, `paymentId`, `totalCents`, `currency`, `items[]`, `deliveryAddress` |
| `OrderStatusChanged` | Any status transition | `orderId`, `from`, `to` |
| `OrderCancelled` | Status → `CANCELLED` | `orderId`, `userId`, `paymentId`, `totalCents`, `currency`, `reason` |

**Consumed events:**

| Topic | Event | Action |
|-------|-------|--------|
| `identity.events` | `UserErased` | Anonymize user data in orders (GDPR) |

---

### 5. API Reference

#### Customer Endpoints (`/orders`)

| Method | Path | Auth | Description | Response |
|--------|------|------|-------------|----------|
| `GET` | `/orders` | JWT | List current user's orders (paginated) | `Page<OrderSummaryResponse>` |
| `GET` | `/orders/{id}` | JWT | Get order detail (owner or admin) | `OrderResponse` |
| `GET` | `/orders/{id}/status` | JWT | Get order status + timeline | `OrderStatusResponse` |
| `POST` | `/orders/{id}/cancel` | JWT | Cancel order (user, pre-packing only) | `204 No Content` |

#### Checkout (`/checkout`)

| Method | Path | Auth | Description | Response |
|--------|------|------|-------------|----------|
| `POST` | `/checkout` | JWT | Start checkout saga | `200` → `CheckoutResponse` or `422` on failure |

**Request body:**

```json
{
  "userId": "uuid",
  "storeId": "string",
  "items": [
    {
      "productId": "uuid",
      "productName": "string",
      "productSku": "string",
      "quantity": 2,
      "unitPriceCents": 1500,
      "lineTotalCents": 3000
    }
  ],
  "subtotalCents": 3000,
  "discountCents": 0,
  "totalCents": 3000,
  "currency": "INR",
  "couponCode": "SAVE10",
  "idempotencyKey": "unique-key-64-chars-max",
  "deliveryAddress": "123 Main St"
}
```

#### Admin Endpoints (`/admin/orders`) — requires `ROLE_ADMIN`

| Method | Path | Auth | Description | Response |
|--------|------|------|-------------|----------|
| `GET` | `/admin/orders/{id}` | Admin | Get any order | `OrderResponse` |
| `POST` | `/admin/orders/{id}/cancel` | Admin | Cancel any cancellable order | `204 No Content` |
| `POST` | `/admin/orders/{id}/status` | Admin | Advance order status | `204 No Content` |

**Status update request body:**

```json
{
  "status": "PACKING",
  "note": "optional reason"
}
```

#### Error Response Format

All errors return a consistent structure:

```json
{
  "code": "ORDER_NOT_FOUND",
  "message": "Order not found",
  "traceId": "abc123",
  "timestamp": "2025-01-01T00:00:00Z",
  "details": []
}
```

| HTTP Status | Code | Cause |
|-------------|------|-------|
| `400` | `VALIDATION_ERROR` | Invalid request body / parameters |
| `403` | `ACCESS_DENIED` | Missing `ROLE_ADMIN` |
| `404` | `ORDER_NOT_FOUND` | Order does not exist or not owned by user |
| `409` | `DUPLICATE_CHECKOUT` | Idempotency key already used |
| `409` | `INVALID_ORDER_STATE` | Illegal state transition |
| `422` | — | Checkout saga failed (insufficient stock, payment declined, etc.) |
| `429` | `RATE_LIMIT_EXCEEDED` | Checkout rate limit hit (10 req / 60 s per user) |
| `500` | `INTERNAL_ERROR` | Unhandled server error |

---

### 6. Database Schema

```mermaid
erDiagram
    orders ||--o{ order_items : contains
    orders ||--o{ order_status_history : tracks
    orders {
        uuid id PK
        uuid user_id
        boolean user_erased
        varchar store_id
        order_status status
        bigint subtotal_cents
        bigint discount_cents
        bigint total_cents
        varchar currency
        varchar coupon_code
        uuid reservation_id
        uuid payment_id
        varchar idempotency_key UK
        text cancellation_reason
        text delivery_address
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }

    order_items {
        uuid id PK
        uuid order_id FK
        uuid product_id
        varchar product_name
        varchar product_sku
        int quantity
        bigint unit_price_cents
        bigint line_total_cents
        varchar picked_status
    }

    order_status_history {
        bigserial id PK
        uuid order_id FK
        order_status from_status
        order_status to_status
        varchar changed_by
        text note
        timestamptz created_at
    }

    outbox_events {
        uuid id PK
        varchar aggregate_type
        varchar aggregate_id
        varchar event_type
        jsonb payload
        timestamptz created_at
        boolean sent
    }

    audit_log {
        uuid id PK
        uuid user_id
        varchar action
        varchar entity_type
        varchar entity_id
        jsonb details
        varchar ip_address
        text user_agent
        varchar trace_id
        timestamptz created_at
    }
```

**Key indexes:**

| Table | Index | Purpose |
|-------|-------|---------|
| `orders` | `idx_orders_user` | Filter by `user_id` |
| `orders` | `idx_orders_status` | Filter by `status` |
| `orders` | `idx_orders_created` | Sort by `created_at DESC` |
| `orders` | `idx_orders_user_created_at` | Composite for user order listing |
| `orders` | `uq_order_idempotency` | Unique constraint on `idempotency_key` |
| `order_items` | `idx_order_items_order` | Join to parent order |
| `order_status_history` | `idx_osh_order` | History lookup by order |
| `outbox_events` | `idx_outbox_unsent` | Partial index (`WHERE sent = false`) |
| `audit_log` | `idx_audit_user_id` | Lookup by user |
| `audit_log` | `idx_audit_action` | Filter by action type |
| `audit_log` | `idx_audit_created_at` | Time-range queries |

Migrations are managed by **Flyway** (`src/main/resources/db/migration/`).

---

### 7. Error Handling & Compensation Flow

```mermaid
flowchart TD
    Start([POST /checkout]) --> Validate[Validate pricing]
    Validate -->|discount > subtotal| Fail1[Return 422: Discount exceeds subtotal]

    Validate --> Reserve[Reserve Inventory]
    Reserve -->|InsufficientStockException<br/>non-retryable| Comp1[No compensation needed]
    Comp1 --> Fail2[Return 422: Insufficient stock]

    Reserve -->|success| AuthPay[Authorize Payment]
    AuthPay -->|PaymentDeclinedException<br/>non-retryable| Comp2[Cancel reservation]
    Comp2 --> Fail3[Return 422: Payment declined]

    AuthPay -->|success| CreateOrd[Create Order — PENDING]
    CreateOrd -->|failure| Comp3[Void payment → Cancel reservation]
    Comp3 --> Fail4[Return 422]

    CreateOrd -->|success| Confirm[Confirm Reservation]
    Confirm -->|failure| Comp4[Cancel order → Void payment → Cancel reservation]
    Comp4 --> Fail5[Return 422]

    Confirm -->|success| Capture[Capture Payment]
    Capture -->|failure| Comp5[Cancel order → Void payment → Cancel reservation]
    Comp5 --> Fail6[Return 422]

    Capture -->|success| ClearCart[Clear Cart — best-effort]
    ClearCart -->|failure logged, continues| Finalize
    ClearCart -->|success| Finalize[Update Order → PLACED]

    Finalize --> Success([Return 200: orderId + workflowId])
```

**Compensation rules:**

- Compensations run in **reverse registration order** (last registered, first executed).
- Compensations run **sequentially** (`parallelCompensation = false`).
- Cart clearing is best-effort — failure is logged but does not trigger compensation.
- Duplicate checkouts are rejected via Temporal's `WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE` and return `409 Conflict`.

**Retry behavior:**

- Each activity has independent retry configuration with exponential backoff.
- Domain exceptions (`InsufficientStockException`, `PaymentDeclinedException`) are marked **non-retryable** to fail fast.
- The overall workflow has a **5-minute execution timeout**.

## Configuration

| Property | Env Var | Default |
|----------|---------|---------|
| Server port | `SERVER_PORT` | `8085` |
| Database URL | `ORDER_DB_URL` | `jdbc:postgresql://localhost:5432/orders` |
| Inventory service | `INVENTORY_SERVICE_URL` | `http://localhost:8083` |
| Cart service | `CART_SERVICE_URL` | `http://localhost:8084` |
| Payment service | `PAYMENT_SERVICE_URL` | `http://localhost:8086` |
| Temporal address | `TEMPORAL_HOST` | `localhost:7233` |
| Temporal namespace | `TEMPORAL_NAMESPACE` | `instacommerce` |
| Temporal task queue | `TEMPORAL_TASK_QUEUE` | `CHECKOUT_TASK_QUEUE` |
| JWT issuer | `ORDER_JWT_ISSUER` | `instacommerce-identity` |
| Checkout rate limit | — | 10 requests / 60 s per user |

## Tech Stack

- **Java 21**, Spring Boot 3, Spring Data JPA, Spring Security
- **PostgreSQL** with Flyway migrations
- **Temporal** (SDK 1.24.2) for workflow orchestration
- **Kafka** (Spring Kafka) for event publishing & consumption
- **Resilience4j** for rate limiting
- **ShedLock** for distributed cron scheduling
- **Micrometer + OTLP** for metrics and distributed tracing
- **Testcontainers** (PostgreSQL) for integration tests
