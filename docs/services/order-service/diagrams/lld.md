# Order Service - Low-Level Design

## Components

### 1. OrderController
**File**: src/main/java/com/instacommerce/order/controller/OrderController.java

**Endpoints**:
- `GET /orders` - List user's orders (paginated)
- `GET /orders/{id}` - Get order details
- `GET /orders/{id}/status` - Get order status only
- `POST /orders/{id}/cancel` - Cancel order

**Key Logic**:
- JWT principal validation ensures user isolation
- Admin flag allows admins to query any order
- Pagination via Spring Data Pageable

### 2. AdminOrderController
Similar to OrderController but with additional admin operations.

### 3. Order Entity
```java
@Entity
@Table(name = "orders")
@Version
public class Order {
    @Id
    private UUID id;
    private UUID userId;
    private String storeId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;  // PENDING, PLACED, PACKING, PACKED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, FAILED

    private Long subtotalCents;
    private Long discountCents;
    private Long totalCents;
    private String currency;
    private String couponCode;
    private UUID reservationId;
    private UUID paymentId;

    @Column(unique = true)
    private String idempotencyKey;  // Link to checkout idempotency

    private String cancellationReason;

    @OneToMany(cascade = CascadeType.ALL)
    private List<OrderItem> items;

    @Version
    private Long version;  // Optimistic locking
}
```

### 4. OrderItem Entity
```java
@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    private UUID id;
    private UUID orderId;
    private UUID productId;
    private String productName;
    private String productSku;
    private Integer quantity;
    private Long unitPriceCents;
    private Long lineTotalCents;
    private String pickedStatus;  // PENDING, PICKED, NOT_AVAILABLE
}
```

### 5. OutboxEvent Entity (Outbox Pattern)
```java
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    private UUID id;
    private String aggregateType;  // "Order"
    private String aggregateId;    // orderId
    private String eventType;      // "OrderCreated", "OrderStatusChanged"
    @Convert(attributeConverter = JsonBinaryType.class)
    private JsonNode payload;
    private Instant createdAt;
    private Boolean sent;  // CDC marks as true after publish
}
```

### 6. AuditLog Entity
```java
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id
    private UUID id;
    private UUID orderId;
    private String action;  // "CREATED", "STATUS_CHANGED", "CANCELLED"
    private String oldValue;
    private String newValue;
    private Instant timestamp;
    private String userId;
}
```

## State Machine

```
PENDING ─→ PLACED ─→ PACKING ─→ PACKED ─→ OUT_FOR_DELIVERY ─→ DELIVERED
  ↓ (cancel)  ↓ (cancel)  ↓ (cancel soft)
CANCELLED    CANCELLED    CANCELLED (soft constraint)

FAILED (payment declined, no stock)
```

## Database Schema (Minimal)

```sql
CREATE TYPE order_status AS ENUM (
    'PENDING', 'PLACED', 'PACKING', 'PACKED',
    'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED', 'FAILED'
);

-- Main orders table
CREATE TABLE orders (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL,
    store_id            VARCHAR(50) NOT NULL,
    status              order_status NOT NULL DEFAULT 'PENDING',
    subtotal_cents      BIGINT NOT NULL,
    discount_cents      BIGINT NOT NULL DEFAULT 0,
    total_cents         BIGINT NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'INR',
    coupon_code         VARCHAR(30),
    reservation_id      UUID,
    payment_id          UUID,
    idempotency_key     VARCHAR(64) UNIQUE NOT NULL,
    cancellation_reason TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0
);

-- Outbox for event publishing
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent           BOOLEAN NOT NULL DEFAULT false
);

-- Audit log
CREATE TABLE audit_log (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL REFERENCES orders(id),
    action          VARCHAR(50) NOT NULL,
    old_value       TEXT,
    new_value       TEXT,
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_id         VARCHAR(255)
);
```

## Temporal Workflow Integration

order-service is called as a Temporal Activity by checkout-orchestrator:

```java
// In CheckoutWorkflow (Temporal)
public CheckoutResponse checkout(CheckoutRequest request) {
    // ... previous activities ...

    // Call CreateOrderActivity
    CreateOrderResult result = Workflow.executeActivity(
        CreateOrderActivity.class,
        request.orderId(),
        request.userId(),
        request.items()
    );

    return new CheckoutResponse(result.orderId(), "SUCCESS", result.total());
}
```

**Activity Implementation**:
```java
@Activity
public CreateOrderResult createOrder(UUID orderId, UUID userId, List<OrderItem> items) {
    // Called via HTTP: POST /orders (internally via REST client)
    // order-service persists to DB, publishes outbox event
    // Returns orderId to workflow
}
```
