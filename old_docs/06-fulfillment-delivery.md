# 06 — Fulfillment & Delivery Service (fulfillment-service)

> **Bounded Context**: Picking, Packing, Substitutions, Rider Dispatch, Delivery  
> **Runtime**: Java 21 + Spring Boot 4.0.x on GKE  
> **Primary datastore**: Cloud SQL for PostgreSQL 15  
> **Port (local)**: 8087 | **gRPC port**: 9087

---

## 1. Architecture Overview

```
  Kafka: orders.events              Kafka: fulfillment.events
         │                                    ▲
         │ OrderPlaced                        │ OrderPacked, Dispatched, Delivered
         ▼                                    │
  ┌──────────────────────────────────────────────────────────┐
  │                  fulfillment-service                      │
  │                                                          │
  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │
  │  │OrderEvent    │  │PickController│  │DeliveryCtrl    │  │
  │  │Consumer      │  │(picker app)  │  │(rider tracking)│  │
  │  └──────┬──────┘  └──────┬───────┘  └───────┬────────┘  │
  │         │                │                   │           │
  │  ┌──────▼────────────────▼───────────────────▼────────┐  │
  │  │             FulfillmentService                      │  │
  │  │  createPickTask │ markItemPicked │ markPacked       │  │
  │  │  assignRider    │ markDelivered  │ handleMissing    │  │
  │  └──────┬──────────────────────────────────────────────┘  │
  │         │                                                │
  │  ┌──────▼──────────────────────────────────────────────┐  │
  │  │  PostgreSQL (fulfillment_db)                         │  │
  │  │  pick_tasks │ pick_items │ deliveries │ riders       │  │
  │  │  outbox_events                                       │  │
  │  └─────────────────────────────────────────────────────┘  │
  └──────────────────────────────────────────────────────────┘
                        │
                        │ Calls to payment-service (partial refund)
                        │ Calls to order-service (status update)
                        ▼
               ┌────────────────┐
               │ payment-service │
               │ order-service   │
               └────────────────┘
```

---

## 2. Package Structure

```
com.instacommerce.fulfillment
├── FulfillmentServiceApplication.java
├── config/
│   ├── SecurityConfig.java
│   ├── KafkaConsumerConfig.java
│   └── FeignConfig.java
├── controller/
│   ├── PickController.java          # Picker app APIs
│   ├── DeliveryController.java      # Delivery/tracking APIs  
│   └── AdminFulfillmentController.java
├── consumer/
│   └── OrderEventConsumer.java      # Listens for OrderPlaced events
├── dto/
│   ├── request/
│   │   ├── MarkItemPickedRequest.java   # {productId, status: PICKED|MISSING}
│   │   ├── MarkPackedRequest.java
│   │   ├── AssignRiderRequest.java
│   │   └── MarkDeliveredRequest.java
│   ├── response/
│   │   ├── PickTaskResponse.java
│   │   ├── PickItemResponse.java
│   │   ├── DeliveryResponse.java
│   │   └── TrackingResponse.java
│   └── mapper/
│       └── FulfillmentMapper.java
├── domain/
│   ├── model/
│   │   ├── PickTask.java              # Aggregate: one per order
│   │   ├── PickItem.java              # Product line within pick task
│   │   ├── PickItemStatus.java        # enum: PENDING, PICKED, MISSING, SUBSTITUTED
│   │   ├── PickTaskStatus.java        # enum: PENDING, IN_PROGRESS, COMPLETED, CANCELLED
│   │   ├── Delivery.java
│   │   ├── DeliveryStatus.java        # enum: ASSIGNED, PICKED_UP, IN_TRANSIT, DELIVERED
│   │   ├── Rider.java
│   │   └── OutboxEvent.java
│   └── valueobject/
│       └── EstimatedTime.java
├── service/
│   ├── PickService.java               # Pick task lifecycle
│   ├── SubstitutionService.java       # Handle missing items
│   ├── DeliveryService.java           # Rider assignment, tracking
│   ├── RiderAssignmentService.java    # Selection algorithm
│   └── OutboxService.java
├── client/
│   ├── PaymentClient.java            # For partial refunds
│   ├── OrderClient.java              # For status updates
│   └── InventoryClient.java          # Release stock for missing items
├── repository/
│   ├── PickTaskRepository.java
│   ├── PickItemRepository.java
│   ├── DeliveryRepository.java
│   ├── RiderRepository.java
│   └── OutboxEventRepository.java
├── exception/
│   └── GlobalExceptionHandler.java
└── infrastructure/
    └── metrics/
        └── FulfillmentMetrics.java
```

---

## 3. Database Schema

### V1__create_pick_tasks.sql
```sql
CREATE TYPE pick_task_status AS ENUM ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED');
CREATE TYPE pick_item_status AS ENUM ('PENDING', 'PICKED', 'MISSING', 'SUBSTITUTED');

CREATE TABLE pick_tasks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID             NOT NULL,
    store_id    VARCHAR(50)      NOT NULL,
    picker_id   UUID,
    status      pick_task_status NOT NULL DEFAULT 'PENDING',
    started_at  TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uq_pick_order UNIQUE (order_id)    -- one pick task per order
);

CREATE TABLE pick_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pick_task_id    UUID             NOT NULL REFERENCES pick_tasks(id) ON DELETE CASCADE,
    product_id      UUID             NOT NULL,
    product_name    VARCHAR(255)     NOT NULL,
    quantity        INT              NOT NULL,
    picked_qty      INT              NOT NULL DEFAULT 0,
    status          pick_item_status NOT NULL DEFAULT 'PENDING',
    substitute_product_id UUID,
    note            TEXT,
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE INDEX idx_pick_tasks_store  ON pick_tasks (store_id, status);
CREATE INDEX idx_pick_items_task   ON pick_items (pick_task_id);
```

### V2__create_deliveries.sql
```sql
CREATE TYPE delivery_status AS ENUM ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'FAILED');

CREATE TABLE riders (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    phone       VARCHAR(20),
    store_id    VARCHAR(50)  NOT NULL,
    is_available BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE deliveries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID            NOT NULL,
    rider_id        UUID            REFERENCES riders(id),
    status          delivery_status NOT NULL DEFAULT 'ASSIGNED',
    estimated_minutes INT,
    dispatched_at   TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_delivery_order UNIQUE (order_id)
);

CREATE INDEX idx_deliveries_rider ON deliveries (rider_id, status);
CREATE INDEX idx_riders_store     ON riders (store_id, is_available);
```

---

## 4. Event-Driven Flow

### Consuming OrderPlaced Event
```java
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {
    
    private final PickService pickService;

    @KafkaListener(topics = "orders.events", groupId = "fulfillment-service")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        OrderEvent event = objectMapper.readValue(record.value(), OrderEvent.class);
        
        if ("OrderPlaced".equals(event.getEventType())) {
            pickService.createPickTask(
                event.getPayload().getOrderId(),
                event.getPayload().getStoreId(),
                event.getPayload().getItems()
            );
        }
    }
}
```

### Publishing Fulfillment Events (via outbox)
```java
// On order packed
outboxService.publish("Fulfillment", orderId.toString(), "OrderPacked",
    Map.of("orderId", orderId, "storeId", storeId, "packedAt", Instant.now()));

// On dispatched
outboxService.publish("Fulfillment", orderId.toString(), "OrderDispatched",
    Map.of("orderId", orderId, "riderId", riderId, "estimatedMinutes", 15));

// On delivered
outboxService.publish("Fulfillment", orderId.toString(), "OrderDelivered",
    Map.of("orderId", orderId, "deliveredAt", Instant.now()));
```

---

## 5. REST API (Picker + Delivery)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /fulfillment/picklist/{storeId} | ROLE_PICKER | Pending pick tasks |
| GET | /fulfillment/picklist/{orderId}/items | ROLE_PICKER | Items to pick |
| POST | /fulfillment/picklist/{orderId}/items/{productId} | ROLE_PICKER | Mark item status |
| POST | /fulfillment/orders/{orderId}/packed | ROLE_PICKER | Mark order packed |
| GET | /orders/{orderId}/tracking | User | Delivery tracking |
| POST | /fulfillment/orders/{orderId}/delivered | ROLE_RIDER | Mark delivered |

### POST /fulfillment/picklist/{orderId}/items/{productId} — Request
```json
{ "status": "PICKED", "pickedQty": 2 }
```
or
```json
{ "status": "MISSING", "note": "Out of stock on shelf" }
```

### GET /orders/{orderId}/tracking — Response 200
```json
{
  "orderId": "uuid",
  "status": "OUT_FOR_DELIVERY",
  "riderName": "Raj",
  "estimatedMinutes": 12,
  "dispatchedAt": "2026-02-06T10:15:00Z",
  "timeline": [
    { "status": "PLACED", "at": "2026-02-06T10:00:00Z" },
    { "status": "PACKING", "at": "2026-02-06T10:05:00Z" },
    { "status": "PACKED", "at": "2026-02-06T10:12:00Z" },
    { "status": "OUT_FOR_DELIVERY", "at": "2026-02-06T10:15:00Z" }
  ]
}
```

---

## 6. Substitution & Missing Item Flow

```
Item marked MISSING by picker
        │
        ▼
  ┌───────────────────────────────┐
  │  SubstitutionService          │
  │                               │
  │  1. Log missing item          │
  │  2. Call inventory-service:   │
  │     release reservation qty   │
  │     for this item             │
  │  3. Call payment-service:     │
  │     partial refund for item   │
  │     amount                    │
  │  4. Update order total        │
  │     (call order-service)      │
  │  5. Emit OrderModified event  │
  └───────────────────────────────┘
```

---

## 7. Rider Assignment Algorithm (v1 — simple)

```java
@Service
@RequiredArgsConstructor
public class RiderAssignmentService {
    
    private final RiderRepository riderRepo;
    
    public Rider assignRider(String storeId) {
        // v1: Pick first available rider at this store (round-robin by last assignment)
        return riderRepo.findFirstAvailableByStoreId(storeId)
            .orElseThrow(() -> new NoAvailableRiderException(storeId));
    }
}
```

```sql
-- Custom query
SELECT r.* FROM riders r
WHERE r.store_id = :storeId AND r.is_available = true
ORDER BY (
    SELECT MAX(d.dispatched_at) FROM deliveries d WHERE d.rider_id = r.id
) ASC NULLS FIRST
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

---

## 8. Agent Instructions (CRITICAL)

### MUST DO
1. Create pick task from `OrderPlaced` Kafka event — idempotent by order_id (UNIQUE constraint).
2. When all items are PICKED or MISSING, auto-transition task to COMPLETED.
3. For MISSING items: call inventory cancel for that item's qty, call payment partial refund.
4. Publish events via outbox table (never direct Kafka).
5. Rider assignment uses `FOR UPDATE SKIP LOCKED` to prevent assigning same rider concurrently.
6. Track full timeline for customer-facing tracking API.

### MUST NOT DO
1. Do NOT block order on delivery partner API — rider assignment is best-effort.
2. Do NOT invent real-time GPS tracking — use static ETA for MVP.
3. Do NOT expose picker/rider endpoints to customers.
4. Do NOT skip missing item refund handling.

### DEFAULTS
- Default ETA: 15 minutes from dispatch
- Local DB: `jdbc:postgresql://localhost:5432/fulfillment`
- Kafka group: `fulfillment-service`
