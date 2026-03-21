# Order Service - Kafka Events

## Event Publishing (Outbox + CDC)

Order Service publishes events via the Outbox pattern + Debezium CDC connector. Events are guaranteed to be published exactly-once.

### Produced Topics

**Topic**: `orders.events` (from TopicNames.ORDERS_EVENTS)

---

## Event Types

### OrderCreated

**Event Type**: `OrderCreated`
**Aggregate Type**: `Order`
**Trigger**: When order is first created (status=PENDING)

**Payload**:
```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "550e8400-e29b-41d4-a716-446655440001",
  "storeId": "STORE-NYC-001",
  "totalCents": 99500,
  "currency": "INR",
  "items": [
    {
      "productId": "PROD-001",
      "quantity": 2,
      "unitPriceCents": 40000,
      "productName": "Fresh Bread"
    }
  ],
  "couponCode": "SAVE10",
  "createdAt": "2026-03-21T08:00:00Z"
}
```

**Consumers**:
- fulfillment-service - Starts picking process
- notification-service - Sends order confirmation email
- reconciliation-engine - Verifies payment settlement

---

### OrderStatusChanged

**Event Type**: `OrderStatusChanged`
**Trigger**: When order status transitions

**Payload**:
```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "550e8400-e29b-41d4-a716-446655440001",
  "oldStatus": "PENDING",
  "newStatus": "PLACED",
  "changedAt": "2026-03-21T08:05:00Z",
  "reason": "payment_captured"
}
```

**Consumers**:
- notification-service - Sends status update SMS
- reconciliation-engine - Audits status timeline

---

### OrderCancelled

**Event Type**: `OrderCancelled`
**Trigger**: When customer cancels order (before packing)

**Payload**:
```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "550e8400-e29b-41d4-a716-446655440001",
  "reason": "Changed my mind",
  "cancelledAt": "2026-03-21T08:10:00Z"
}
```

**Consumers**:
- fulfillment-service - Stop picking, release inventory
- inventory-service - Release reservation
- notification-service - Send cancellation confirmation

---

## EventEnvelope Structure

All events are wrapped in the canonical EventEnvelope:

```java
public record EventEnvelope(
    String id,                    // Unique event UUID
    String eventType,             // "OrderCreated"
    String aggregateType,         // "Order"
    String aggregateId,           // orderId
    Instant eventTime,            // Event creation time
    String schemaVersion,         // "1.0"
    String sourceService,         // "order-service"
    String correlationId,         // Trace correlation ID
    JsonNode payload              // Above payload
) {}
```

**Example Full Envelope** (as published to Kafka):
```json
{
  "id": "evt-550e8400-e29b-41d4-a716-446655440100",
  "eventType": "OrderCreated",
  "aggregateType": "Order",
  "aggregateId": "550e8400-e29b-41d4-a716-446655440000",
  "eventTime": "2026-03-21T08:00:00.123Z",
  "schemaVersion": "1.0",
  "sourceService": "order-service",
  "correlationId": "trace-550e8400-e29b-41d4-a716-446655440002",
  "payload": {
    "orderId": "550e8400-e29b-41d4-a716-446655440000",
    ... rest of payload
  }
}
```

---

## Event Publishing Pipeline

```
1. OrderService.createOrder()
   ↓
2. Persist Order + OutboxEvent in same transaction
   ↓
3. Flyway trigger on outbox_events INSERT
   ↓
4. Debezium connector detects CDC change
   ↓
5. Publish to Kafka orders.events topic
   ↓
6. Update outbox_events.sent = true
   ↓
7. Consumers receive EventEnvelope
```

---

## Dead-Letter Topic

**Topic**: `orders.events.DLT` (from TopicNames.ORDERS_DLT)

**When Used**:
- Consumer fails to deserialize event (schema mismatch)
- Consumer processes event but throws unhandled exception (after retries)
- Poison pill messages

**DLT Handling**:
- Manually reviewed by ops team
- May re-publish to main topic after fix
- Logged to audit trail for compliance

---

## No Events Consumed

Order Service does NOT consume Kafka events. It only produces.

---

## Schema References

- **Schema File**: `contracts/src/main/resources/schemas/order/OrderCreated.v1.json`
- **Schema Registry**: Confluent Schema Registry (if enabled)
- **Compatibility**: Backward compatible (new fields optional)
