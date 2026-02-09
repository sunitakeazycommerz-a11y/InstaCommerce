# 04 — Order Service & Temporal Checkout Saga (order-service)

> **Bounded Context**: Order Lifecycle, Checkout Orchestration  
> **Runtime**: Java 21 + Spring Boot 4.0.x on GKE  
> **Primary datastore**: Cloud SQL for PostgreSQL 15  
> **Workflow engine**: Temporal (self-hosted or Temporal Cloud)  
> **Event streaming**: Kafka (via Debezium Outbox)  
> **Port (local)**: 8085 | **gRPC port**: 9085

---

## 1. Architecture Overview

```
                     ┌──────────────────┐
                     │  API Gateway      │
                     └────────┬─────────┘
                              │
              ┌───────────────┼────────────────┐
              │               │                │
    ┌─────────▼──────┐  ┌─────▼──────┐  ┌──────▼──────┐
    │ OrderController │  │ BFF /      │  │ Admin       │
    │ (query orders)  │  │ checkout   │  │ cancel API  │
    └─────────┬──────┘  └─────┬──────┘  └──────┬──────┘
              │               │                │
              │        ┌──────▼──────┐         │
              │        │ Temporal    │         │
              │        │ Client      │         │
              │        │ (start wf)  │         │
              │        └──────┬──────┘         │
              │               │                │
    ┌─────────▼───────────────▼────────────────▼───────────┐
    │                   OrderService                        │
    │                                                       │
    │  createOrder()  │  getOrder()  │  cancelOrder()       │
    └─────────┬────────────────────────────────────────────┘
              │
    ┌─────────▼──────────────────────────────────────────┐
    │              PostgreSQL (order_db)                   │
    │  orders │ order_items │ order_status_history         │
    │  outbox_events (Debezium CDC → Kafka)               │
    └─────────┬──────────────────────────────────────────┘
              │
    ┌─────────▼──────────────────────────────────────────┐
    │              Debezium CDC                           │
    │  outbox_events → Kafka topic: orders.events         │
    └────────────────────────────────────────────────────┘

 ──── Temporal Workflow Engine ────

    ┌────────────────────────────────────────────────────┐
    │  CheckoutWorkflow (Saga Orchestrator)               │
    │                                                     │
    │  Step 1: ReserveInventoryActivity                   │
    │        → calls inventory-service gRPC               │
    │        ← reservation_id                             │
    │        ✗ compensation: CancelReservationActivity     │
    │                                                     │
    │  Step 2: AuthorizePaymentActivity                   │
    │        → calls payment-service                      │
    │        ← payment_id                                 │
    │        ✗ compensation: VoidPaymentActivity           │
    │                                                     │
    │  Step 3: CreateOrderActivity                        │
    │        → calls order-service internal               │
    │        ← order_id                                   │
    │        ✗ compensation: CancelOrderActivity           │
    │                                                     │
    │  Step 4: ConfirmReservationActivity                 │
    │        → calls inventory-service confirm             │
    │                                                     │
    │  Step 5: CapturePaymentActivity                     │
    │        → calls payment-service capture               │
    │                                                     │
    │  Step 6: ClearCartActivity                          │
    │        → calls cart-service clear                    │
    │                                                     │
    │  Step 7: EmitOrderConfirmationActivity              │
    │        → writes outbox event                        │
    └────────────────────────────────────────────────────┘
```

---

## 2. Java Package Structure

```
com.instacommerce.order
├── OrderServiceApplication.java
├── config/
│   ├── SecurityConfig.java
│   ├── TemporalConfig.java          # Temporal WorkflowClient & WorkerFactory beans
│   ├── KafkaConfig.java             # (if direct publish needed, else Debezium handles)
│   └── FeignConfig.java             # Feign clients for inventory, payment, cart
├── controller/
│   ├── OrderController.java         # GET /orders, GET /orders/{id}, GET /orders/{id}/status
│   ├── CheckoutController.java      # POST /checkout (starts Temporal workflow)
│   └── AdminOrderController.java    # POST /admin/orders/{id}/cancel
├── dto/
│   ├── request/
│   │   ├── CheckoutRequest.java     # {userId, storeId, cartSnapshot, couponCode, idempotencyKey}
│   │   └── CancelOrderRequest.java  # {reason}
│   ├── response/
│   │   ├── OrderResponse.java
│   │   ├── OrderSummaryResponse.java
│   │   ├── OrderStatusResponse.java # status + timeline
│   │   ├── CheckoutResponse.java    # {workflowId, orderId (when sync)}
│   │   └── ErrorResponse.java
│   └── mapper/
│       └── OrderMapper.java
├── domain/
│   ├── model/
│   │   ├── Order.java               # Aggregate Root
│   │   ├── OrderItem.java
│   │   ├── OrderStatus.java         # enum
│   │   ├── OrderStatusHistory.java  # status transitions with timestamps
│   │   └── OutboxEvent.java
│   └── statemachine/
│       └── OrderStateMachine.java   # validates transitions
├── service/
│   ├── OrderService.java            # create, get, list, cancel, updateStatus
│   └── OutboxService.java           # write outbox events in same TX
├── repository/
│   ├── OrderRepository.java
│   ├── OrderItemRepository.java
│   ├── OrderStatusHistoryRepository.java
│   └── OutboxEventRepository.java
├── workflow/
│   ├── CheckoutWorkflow.java            # @WorkflowInterface
│   ├── CheckoutWorkflowImpl.java        # @WorkflowMethod implementation
│   ├── activities/
│   │   ├── InventoryActivities.java     # @ActivityInterface
│   │   ├── InventoryActivitiesImpl.java
│   │   ├── PaymentActivities.java
│   │   ├── PaymentActivitiesImpl.java
│   │   ├── OrderActivities.java
│   │   ├── OrderActivitiesImpl.java
│   │   ├── CartActivities.java
│   │   └── CartActivitiesImpl.java
│   └── config/
│       └── WorkerRegistration.java      # registers workers with Temporal
├── client/
│   ├── InventoryClient.java             # Feign/gRPC stub
│   ├── PaymentClient.java
│   └── CartClient.java
├── exception/
│   ├── OrderNotFoundException.java
│   ├── InvalidOrderStateException.java
│   ├── CheckoutFailedException.java
│   └── GlobalExceptionHandler.java
└── infrastructure/
    └── metrics/
        └── OrderMetrics.java
```

---

## 3. Order State Machine

```
                    ┌──────────────┐
                    │   PENDING    │  (workflow started, not yet placed)
                    └──────┬───────┘
                           │ payment authorized + order created
                    ┌──────▼───────┐
            ┌──────>│   PLACED     │<────────────────────────┐
            │       └──────┬───────┘                         │
            │              │ fulfillment picks up             │
            │       ┌──────▼───────┐                         │
            │       │   PACKING    │                         │
            │       └──────┬───────┘                         │
            │              │ all items packed                 │
            │       ┌──────▼───────┐                         │
            │       │   PACKED     │                         │
            │       └──────┬───────┘                         │
            │              │ rider assigned                   │
            │       ┌──────▼───────────────┐                 │
            │       │  OUT_FOR_DELIVERY    │                 │
            │       └──────┬───────────────┘                 │
            │              │ delivered                        │
            │       ┌──────▼───────┐                         │
            │       │  DELIVERED   │ (terminal)              │
            │       └──────────────┘                         │
            │                                                │
            │       ┌──────────────┐                         │
            └───────│  CANCELLED   │ (terminal)              │
                    └──────────────┘                         │
                    Can cancel from: PLACED, PACKING         │
                    Cannot cancel after: PACKED              │
                                                             │
                    ┌──────────────┐                         │
                    │   FAILED     │ (terminal, payment fail)│
                    └──────────────┘                         │
```

### State Transition Validation
```java
public class OrderStateMachine {
    
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
        OrderStatus.PENDING,            Set.of(OrderStatus.PLACED, OrderStatus.FAILED, OrderStatus.CANCELLED),
        OrderStatus.PLACED,             Set.of(OrderStatus.PACKING, OrderStatus.CANCELLED),
        OrderStatus.PACKING,            Set.of(OrderStatus.PACKED, OrderStatus.CANCELLED),
        OrderStatus.PACKED,             Set.of(OrderStatus.OUT_FOR_DELIVERY),
        OrderStatus.OUT_FOR_DELIVERY,   Set.of(OrderStatus.DELIVERED),
        OrderStatus.DELIVERED,          Set.of(),  // terminal
        OrderStatus.CANCELLED,          Set.of(),  // terminal
        OrderStatus.FAILED,             Set.of()   // terminal
    );
    
    public static void validate(OrderStatus from, OrderStatus to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new InvalidOrderStateException(
                "Cannot transition from " + from + " to " + to);
        }
    }
}
```

---

## 4. Database Schema

### V1__create_orders.sql
```sql
CREATE TYPE order_status AS ENUM (
    'PENDING', 'PLACED', 'PACKING', 'PACKED',
    'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED', 'FAILED'
);

CREATE TABLE orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID            NOT NULL,
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
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version             BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_order_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_orders_user    ON orders (user_id);
CREATE INDEX idx_orders_status  ON orders (status);
CREATE INDEX idx_orders_created ON orders (created_at DESC);
```

### V2__create_order_items.sql
```sql
CREATE TABLE order_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID    NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id      UUID    NOT NULL,
    product_name    VARCHAR(255) NOT NULL,       -- denormalized snapshot
    product_sku     VARCHAR(50)  NOT NULL,
    quantity        INT     NOT NULL,
    unit_price_cents BIGINT NOT NULL,
    line_total_cents BIGINT NOT NULL,
    picked_status   VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, PICKED, MISSING
    CONSTRAINT chk_qty CHECK (quantity > 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
```

### V3__create_order_status_history.sql
```sql
CREATE TABLE order_status_history (
    id          BIGSERIAL   PRIMARY KEY,
    order_id    UUID        NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status order_status,
    to_status   order_status NOT NULL,
    changed_by  VARCHAR(100),   -- 'system', 'user:{id}', 'admin:{id}'
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_osh_order ON order_status_history (order_id);
```

### V4__create_outbox.sql
```sql
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent           BOOLEAN      NOT NULL DEFAULT false
);

CREATE INDEX idx_outbox_unsent ON outbox_events (sent) WHERE sent = false;
```

---

## 5. Temporal Workflow — Complete Implementation

### 5.1 Workflow Interface
```java
@WorkflowInterface
public interface CheckoutWorkflow {
    
    @WorkflowMethod
    CheckoutResult execute(CheckoutRequest request);

    @QueryMethod
    String getStatus();
}
```

### 5.2 Activity Interfaces
```java
@ActivityInterface
public interface InventoryActivities {
    @ActivityMethod
    ReserveResult reserveInventory(String idempotencyKey, String storeId, List<CartItem> items);
    
    @ActivityMethod
    void confirmReservation(String reservationId);
    
    @ActivityMethod
    void cancelReservation(String reservationId);
}

@ActivityInterface
public interface PaymentActivities {
    @ActivityMethod
    PaymentResult authorizePayment(String orderId, long amountCents, String currency, String idempotencyKey);
    
    @ActivityMethod
    void capturePayment(String paymentId);
    
    @ActivityMethod
    void voidPayment(String paymentId);
}

@ActivityInterface
public interface OrderActivities {
    @ActivityMethod
    String createOrder(CreateOrderCommand command);
    
    @ActivityMethod
    void cancelOrder(String orderId, String reason);
    
    @ActivityMethod
    void updateOrderStatus(String orderId, String status);
}

@ActivityInterface
public interface CartActivities {
    @ActivityMethod
    void clearCart(String userId);
}
```

### 5.3 Workflow Implementation (Saga with Compensation)
```java
public class CheckoutWorkflowImpl implements CheckoutWorkflow {

    private String currentStatus = "STARTED";

    // Activity stubs with timeouts and retry policies
    private final InventoryActivities inventory = Workflow.newActivityStub(
        InventoryActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setMaximumAttempts(3)
                .setBackoffCoefficient(2.0)
                .setDoNotRetry(InsufficientStockException.class.getName())
                .build())
            .build());

    private final PaymentActivities payment = Workflow.newActivityStub(
        PaymentActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(2))
                .setMaximumAttempts(3)
                .setDoNotRetry(PaymentDeclinedException.class.getName())
                .build())
            .build());

    private final OrderActivities orders = Workflow.newActivityStub(
        OrderActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build());

    private final CartActivities cart = Workflow.newActivityStub(
        CartActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(5))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
            .build());

    @Override
    public CheckoutResult execute(CheckoutRequest request) {
        Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
        
        try {
            // STEP 1: Reserve Inventory
            currentStatus = "RESERVING_INVENTORY";
            ReserveResult reserveResult = inventory.reserveInventory(
                request.getIdempotencyKey(),
                request.getStoreId(),
                request.getItems()
            );
            saga.addCompensation(inventory::cancelReservation, reserveResult.getReservationId());

            // STEP 2: Authorize Payment
            currentStatus = "AUTHORIZING_PAYMENT";
            PaymentResult paymentResult = payment.authorizePayment(
                request.getIdempotencyKey(),  // use same key for correlation
                request.getTotalCents(),
                request.getCurrency(),
                "pay-" + request.getIdempotencyKey()
            );
            saga.addCompensation(payment::voidPayment, paymentResult.getPaymentId());

            // STEP 3: Create Order Record
            currentStatus = "CREATING_ORDER";
            String orderId = orders.createOrder(CreateOrderCommand.builder()
                .userId(request.getUserId())
                .storeId(request.getStoreId())
                .items(request.getItems())
                .subtotalCents(request.getSubtotalCents())
                .discountCents(request.getDiscountCents())
                .totalCents(request.getTotalCents())
                .currency(request.getCurrency())
                .couponCode(request.getCouponCode())
                .reservationId(reserveResult.getReservationId())
                .paymentId(paymentResult.getPaymentId())
                .idempotencyKey(request.getIdempotencyKey())
                .build());
            saga.addCompensation(orders::cancelOrder, orderId, "SAGA_ROLLBACK");

            // STEP 4: Confirm Reservation (stock permanently deducted)
            currentStatus = "CONFIRMING_RESERVATION";
            inventory.confirmReservation(reserveResult.getReservationId());

            // STEP 5: Capture Payment
            currentStatus = "CAPTURING_PAYMENT";
            payment.capturePayment(paymentResult.getPaymentId());

            // STEP 6: Clear Cart (best-effort, don't fail order)
            currentStatus = "CLEARING_CART";
            try {
                cart.clearCart(request.getUserId());
            } catch (Exception e) {
                // Log but don't fail — cart cleanup is non-critical
                Workflow.getLogger(CheckoutWorkflowImpl.class)
                    .warn("Failed to clear cart, continuing", e);
            }

            // STEP 7: Update order status to PLACED
            currentStatus = "FINALIZING";
            orders.updateOrderStatus(orderId, "PLACED");

            currentStatus = "COMPLETED";
            return CheckoutResult.success(orderId);

        } catch (Exception e) {
            currentStatus = "COMPENSATING";
            saga.compensate();
            currentStatus = "FAILED";
            return CheckoutResult.failure(e.getMessage());
        }
    }

    @Override
    public String getStatus() {
        return currentStatus;
    }
}
```

### 5.4 Worker Registration
```java
@Configuration
@RequiredArgsConstructor
public class WorkerRegistration {

    private final WorkflowClient workflowClient;
    private final InventoryActivitiesImpl inventoryActivities;
    private final PaymentActivitiesImpl paymentActivities;
    private final OrderActivitiesImpl orderActivities;
    private final CartActivitiesImpl cartActivities;

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public WorkerFactory workerFactory() {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker("CHECKOUT_TASK_QUEUE");
        
        worker.registerWorkflowImplementationTypes(CheckoutWorkflowImpl.class);
        worker.registerActivitiesImplementations(
            inventoryActivities,
            paymentActivities,
            orderActivities,
            cartActivities
        );
        
        return factory;
    }
}
```

### 5.5 Starting Workflow from Controller
```java
@RestController
@RequestMapping("/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final WorkflowClient workflowClient;

    @PostMapping
    public ResponseEntity<CheckoutResponse> checkout(
            @RequestBody @Valid CheckoutRequest request,
            @AuthenticationPrincipal UserDetails user) {
        
        WorkflowOptions options = WorkflowOptions.newBuilder()
            .setTaskQueue("CHECKOUT_TASK_QUEUE")
            .setWorkflowId("checkout-" + request.getIdempotencyKey())  // dedup
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setWorkflowExecutionTimeout(Duration.ofMinutes(5))
            .build();

        CheckoutWorkflow workflow = workflowClient.newWorkflowStub(
            CheckoutWorkflow.class, options);

        // Synchronous: wait for result (for MVP)
        CheckoutResult result = workflow.execute(request);

        if (result.isSuccess()) {
            return ResponseEntity.ok(new CheckoutResponse(
                result.getOrderId(), "checkout-" + request.getIdempotencyKey()));
        } else {
            return ResponseEntity.status(422)
                .body(new CheckoutResponse(null, null, result.getErrorMessage()));
        }
    }
}
```

---

## 6. Outbox Event Publishing

```java
@Service
@RequiredArgsConstructor
public class OutboxService {
    
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;
    
    @Transactional(propagation = Propagation.MANDATORY) // must be called within existing TX
    public void publish(String aggregateType, String aggregateId,
                       String eventType, Object payload) {
        OutboxEvent event = OutboxEvent.builder()
            .aggregateType(aggregateType)
            .aggregateId(aggregateId)
            .eventType(eventType)
            .payload(objectMapper.writeValueAsString(payload))
            .build();
        outboxRepo.save(event);
    }
}
```

### Debezium Connector
```json
{
  "name": "order-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "order-db-host",
    "database.dbname": "orders",
    "table.include.list": "public.outbox_events",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.topic.replacement": "orders.events",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.table.field.event.payload": "payload"
  }
}
```

---

## 7. REST API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /checkout | User | Start checkout workflow |
| GET | /orders | User | List my orders (paginated) |
| GET | /orders/{id} | User/Admin | Order details + items |
| GET | /orders/{id}/status | User | Status + timeline |
| POST | /admin/orders/{id}/cancel | Admin | Force cancel order |

### GET /orders/{id} — Response 200
```json
{
  "id": "order-uuid",
  "userId": "user-uuid",
  "storeId": "store-mumbai-01",
  "status": "PLACED",
  "items": [
    {
      "productId": "uuid-1",
      "productName": "Amul Milk 500ml",
      "productSku": "MILK-AMUL-500ML",
      "quantity": 2,
      "unitPriceCents": 3200,
      "lineTotalCents": 6400
    }
  ],
  "subtotalCents": 6400,
  "discountCents": 640,
  "totalCents": 5760,
  "currency": "INR",
  "couponCode": "FIRST10",
  "createdAt": "2026-02-06T10:00:00Z",
  "statusHistory": [
    { "from": null, "to": "PENDING", "at": "2026-02-06T10:00:00Z" },
    { "from": "PENDING", "to": "PLACED", "at": "2026-02-06T10:00:05Z" }
  ]
}
```

---

## 8. GCP & Temporal Deployment

### Cloud SQL
- Instance: `order-db`, db-custom-4-16384 (orders DB needs more resources)
- Enable `logical` replication for Debezium CDC (wal_level=logical)

### Temporal
- Deploy Temporal server on GKE via Helm chart (temporalio/temporal-server-chart)
- Temporal persistence: dedicated Cloud SQL PostgreSQL instance `temporal-db`
- Temporal UI: deployed for dev/staging only (not exposed in prod)
- Task queue: `CHECKOUT_TASK_QUEUE`
- Namespace: `instacommerce`

### application.yml (prod)
```yaml
spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${CLOUD_SQL_INSTANCE}&socketFactory=com.google.cloud.sql.postgres.SocketFactory
    username: ${DB_USER}
    password: ${sm://order-db-password}
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10

temporal:
  service-address: ${TEMPORAL_HOST}:7233
  namespace: instacommerce
  task-queue: CHECKOUT_TASK_QUEUE
```

---

## 9. Observability

| Metric | Type | Labels |
|--------|------|--------|
| `order_checkout_started_total` | Counter | |
| `order_checkout_completed_total` | Counter | status={success,failure} |
| `order_checkout_duration_seconds` | Histogram | |
| `order_status_change_total` | Counter | from, to |
| `order_cancel_total` | Counter | reason |
| `temporal_workflow_completed_total` | Counter | status |
| `temporal_activity_duration_seconds` | Histogram | activity_name |

### SLOs
| SLO | Target |
|-----|--------|
| Checkout e2e p95 | < 3s |
| Order availability | 99.9% |
| Order query p95 | < 200ms |

---

## 10. Error Codes
| HTTP | Code | When |
|------|------|------|
| 400 | VALIDATION_ERROR | Bad input |
| 404 | ORDER_NOT_FOUND | Order ID miss |
| 409 | DUPLICATE_CHECKOUT | Same idempotency key |
| 409 | INSUFFICIENT_STOCK | Inventory reserve failed |
| 422 | PAYMENT_DECLINED | Payment auth failed |
| 422 | CHECKOUT_FAILED | Saga failed |
| 422 | INVALID_STATE_TRANSITION | Cannot cancel delivered order |
| 500 | INTERNAL_ERROR | Unexpected |

---

## 11. Testing Strategy

### Unit Tests
- `OrderStateMachine`: every valid transition succeeds, every invalid throws
- `CheckoutWorkflowImpl`: Temporal test framework simulated, test success + failure + compensation

### Integration Tests (Testcontainers + Temporal Test Server)
```java
@Test
void checkoutSuccessPath_createsOrder() {
    // Use Temporal TestWorkflowEnvironment
    // Mock activities to return success
    // Assert: order created, reservation confirmed, payment captured, cart cleared
}

@Test
void checkoutPaymentDecline_compensatesFullly() {
    // Mock PaymentActivities to throw PaymentDeclinedException
    // Assert: reservation cancelled, no order in DB, workflow status FAILED
}

@Test
void checkoutWorkerCrash_resumesOnNewWorker() {
    // Start workflow, kill worker mid-execution
    // Restart worker, verify workflow completes
}
```

### Outbox Test
- Create order → assert outbox_events has row → simulate Debezium read → assert Kafka message

---

## 12. Agent Instructions (CRITICAL)

### MUST DO
1. Use the **exact package structure** from section 2.
2. Order state transitions MUST go through `OrderStateMachine.validate()` — no direct status writes.
3. Every state change MUST write to `order_status_history` table in the same transaction.
4. Every state change MUST write to `outbox_events` for Debezium CDC. Never call Kafka producer directly.
5. Checkout workflow uses **Temporal Saga** built-in compensation (`Saga` class). Compensations run in **reverse order**.
6. All activities MUST be **idempotent**: calling twice with same input produces same result.
7. Workflow ID = `"checkout-" + idempotencyKey` with `REJECT_DUPLICATE` policy to prevent duplicate checkouts.
8. Activity timeouts: inventory 10s, payment 30s, order 10s, cart 5s.
9. `DoNotRetry` list: `InsufficientStockException`, `PaymentDeclinedException` — these are business failures, not transient.
10. Cart clearing is **best-effort**: catch exceptions, log, don't fail the order.
11. Order items store **denormalized product data** (name, SKU, price at time of order). Never join back to catalog for display.
12. All money in **cents (long)**.

### MUST NOT DO
1. Do NOT implement distributed transactions (2PC) — use saga compensation only.
2. Do NOT store the full cart in order — only the items with snapshot prices.
3. Do NOT expose checkout or order creation directly without Temporal.
4. Do NOT let a failed workflow leave orphaned reservations or charges. Saga compensations handle this.
5. Do NOT skip workflow ID deduplication — this prevents double-ordering on retries.

### DEFAULTS
- Workflow execution timeout: 5 minutes
- Activity retry: 3 attempts with 2x backoff
- Temporal namespace: `instacommerce`
- Task queue: `CHECKOUT_TASK_QUEUE`
- Local Temporal: `localhost:7233`
- Local DB: `jdbc:postgresql://localhost:5432/orders`

### ESCALATION
Add `// TODO(AGENT):` for:
- Whether checkout should be synchronous (wait) or async (return workflow ID for polling)
- Whether to add a payment holding step (auth now, capture at delivery)
- Whether to add order modification (add/remove items after placement)
