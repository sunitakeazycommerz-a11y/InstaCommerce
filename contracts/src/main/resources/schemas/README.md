# Event JSON Schemas

These JSON schemas define the canonical event payloads produced via the transactional outbox + Debezium CDC pipeline and consumed across the InstaCommerce monorepo.

> **Schema Evolution Rule:** Do not change field types or remove required fields in v1 schemas. Add v2 schema files (`*.v2.json`) with a 90-day deprecation window for breaking changes.

---

## 1. Canonical Transport Envelope

All domain events published to Kafka must be wrapped in the canonical transport envelope:
- **Envelope Schema:** [`common/EventEnvelope.v1.json`](common/EventEnvelope.v1.json)
- **Standard Envelope Fields:**
  - `eventId`: UUID identifying the event instance.
  - `eventType`: Qualified event type string (e.g. `OrderPlaced`, `PaymentAuthorized`).
  - `aggregateId`: Entity identifier (e.g. orderId, paymentId).
  - `schemaVersion`: Integer or string version (e.g. `1` or `v1`).
  - `sourceService`: Name of the originating microservice.
  - `correlationId`: Distributed tracing ID (`X-Correlation-ID`).
  - `timestamp`: UTC ISO-8601 creation timestamp.
  - `payload`: Domain-specific event body conforming to the schemas below.

---

## 2. Canonical Kafka Topics (`TopicNames.java`)

| Constant | Topic Name | Payload Schemas Folder |
|---|---|---|
| `ORDERS_EVENTS` | `orders.events` | `orders/` |
| `PAYMENTS_EVENTS` | `payments.events` | `payments/` |
| `INVENTORY_EVENTS` | `inventory.events` | `inventory/` |
| `FULFILLMENT_EVENTS` | `fulfillment.events` | `fulfillment/` |
| `CATALOG_EVENTS` | `catalog.events` | `catalog/` |
| `CART_EVENTS` | `cart.events` | `cart/` |
| `IDENTITY_EVENTS` | `identity.events` | `identity/` |
| `PRICING_EVENTS` | `pricing.events` | `pricing/` |
| `PROMOTION_EVENTS` | `promotion.events` | `pricing/` |
| `WALLET_EVENTS` | `wallet.events` | `wallet/` |
| `FRAUD_EVENTS` | `fraud.events` | `fraud/` |
| `RIDER_EVENTS` | `rider.events` | `rider/` |
| `DELIVERY_EVENTS` | `delivery.events` | `delivery/` |
| `ROUTING_EVENTS` | `routing.events` | `routing/` |
| `RIDER_GEOFENCE_EVENTS` | `rider.geofence.events` | `routing/` |
| `WAREHOUSE_EVENTS` | `warehouse.events` | `warehouse/` |
| `NOTIFICATION_EVENTS` | `notification.events` | `notification/` |
| `SEARCH_EVENTS` | `search.events` | `search/` |
| `RECONCILIATION_EVENTS` | `reconciliation.events` | `reconciliation/` |
| `AI_ORCHESTRATOR_EVENTS` | `ai-orchestrator.events` | `ai-orchestrator/` |
| `RETURNS_EVENTS` | `returns.events` | `returns/` |
| `CUSTOMER_SUPPORT_EVENTS` | `customer-support.events` | `customer-support/` |
| `PAYMENT_WEBHOOKS` | `payment.webhooks` | Dedicated PSP webhook payloads |
| `OUTBOX_RELAY_DLQ` | `outbox.relay.dlq` | Dead-letter payloads with error reason |

> **Migration Note:** Legacy topic names `order.events` and `payment.events` remain supported as backward-compatible consumer aliases during migration, but all new publishers must emit to `orders.events` and `payments.events`.

---

## 3. Schema Verification & Testing

Validate schema syntax and draft-07 compatibility via Gradle:

```bash
# Compile and test JSON schemas
./gradlew :contracts:test --tests "com.instacommerce.contracts.schemas.*"

# Build all contracts
./gradlew :contracts:build
```
