# Checkout Orchestrator Service - Events

## Event Publishing

Checkout Orchestrator does **NOT** publish domain events. It orchestrates the checkout saga via Temporal workflows and delegates event publishing to downstream services.

**Why**: Temporal workflows manage the transactional boundary. Domain events are published by the services that own the aggregates (Order, Payment, Inventory).

---

## Events Consumed

Checkout Orchestrator does **NOT consume** Kafka events. It only orchestrates synchronous HTTP calls.

---

## EventEnvelope Structure (Reference)

While checkout-orchestrator doesn't produce events, other services in the saga do. Events follow the canonical EventEnvelope:

```java
public record EventEnvelope(
    String id,                    // Unique event ID
    String eventType,             // e.g., "OrderPlaced"
    String aggregateType,         // e.g., "Order"
    String aggregateId,           // e.g., orderId
    Instant eventTime,            // When event occurred
    String schemaVersion,         // e.g., "1.0"
    String sourceService,         // e.g., "order-service"
    String correlationId,         // Trace correlation
    JsonNode payload              // Domain event data
) {}
```

---

## Related Events (From Other Services in Saga)

| Service | Topic | Event Type | Triggered By |
|---------|-------|-----------|-------------|
| order-service | orders.events | OrderCreated | Successful checkout |
| order-service | orders.events | OrderFailed | Checkout failure |
| payment-service | payments.events | PaymentAuthorized | Payment step completion |
| inventory-service | inventory.events | ReservationCreated | Inventory reservation |
| inventory-service | inventory.events | ReservationExpired | Timeout after checkout |

---

## Integration Note

Checkout Orchestrator serves as the **synchronous orchestrator** for checkout operations. Asynchronous event processing happens downstream:

```
Checkout Orchestrator (sync saga via Temporal)
        ↓
Order Service creates order (publishes OrderCreated event)
        ↓
Downstream services consume OrderCreated via Kafka
(Fulfillment, Notification, Inventory cleanup, etc.)
```

---

## No DLQ Configuration

Since checkout-orchestrator doesn't consume Kafka, there is no DLQ configuration required at this service level. Dead letter handling occurs at the service level for services that consume events.
