# InstaCommerce Event Contracts

Canonical event schemas (JSON Schema) and gRPC service definitions (Protocol Buffers) for cross-service communication in the InstaCommerce platform. All domain events flow through Kafka via the outbox + Debezium CDC pipeline.

---

## Event Envelope

```mermaid
flowchart LR
    subgraph Producer["Producer Service"]
        Outbox["Outbox Table\n(transactional write)"]
    end

    subgraph CDC["Change Data Capture"]
        Debezium["Debezium\nConnector"]
    end

    subgraph Kafka["Kafka"]
        Topic["domain.events\ntopic"]
    end

    subgraph Envelope["Event Envelope"]
        direction TB
        Header["Headers:\n• event_type\n• event_id (UUID)\n• aggregate_id\n• timestamp (ISO 8601)\n• schema_version\n• source_service\n• correlation_id"]
        Payload["Payload:\n(JSON Schema validated)"]
    end

    subgraph Consumers["Consumer Services"]
        C1["Order Service"]
        C2["Payment Service"]
        C3["Analytics Pipeline"]
        C4["Data Platform"]
    end

    Producer --> Outbox --> Debezium --> Topic
    Topic --> Envelope
    Envelope --> Consumers
```

Every event published to Kafka conforms to `EventEnvelope.v1.json`. The canonical Java representation (`EventEnvelope.java`) and outbox relay emit and deserialize both `camelCase` and `snake_case` keys seamlessly via `@JsonAlias`:

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "OrderPlaced",
  "aggregateType": "Order",
  "aggregateId": "order-12345",
  "schemaVersion": "v1",
  "source_service": "order-service",
  "sourceService": "order-service",
  "correlation_id": "req-abc-123",
  "correlationId": "req-abc-123",
  "eventTime": "2026-03-15T10:30:00Z",
  "payload": { ... }
}
```

| Canonical Field | Aliases Accepted | Type | Description |
|---|---|---|---|
| `eventId` / `id` | `event_id` | String / UUID | Globally unique identifier for event deduplication |
| `eventType` | `event_type`, `type` | String | Domain event discriminator (e.g. `OrderPlaced`, `PaymentCaptured`) |
| `aggregateType` | `aggregate_type` | String | Root aggregate entity type (e.g. `Order`, `Payment`, `Cart`) |
| `aggregateId` | `aggregate_id` | String | Business identifier of the aggregate instance |
| `eventTime` | `event_time`, `timestamp`, `event_timestamp` | ISO-8601 UTC Instant | Timestamp when the domain event occurred |
| `schemaVersion` | `schema_version` | String | Contract schema version (default `v1`) |
| `sourceService` | `source_service`, `service` | String | Microservice that produced the event |
| `correlationId` | `correlation_id` | String | Distributed tracing / request correlation identifier |
| `payload` | — | Object / JSON | Domain event payload validated against JSON Schema |

---

## Directory Structure

```
contracts/
├── build.gradle.kts                   # Protobuf plugin + gRPC dependencies
│
└── src/main/
    ├── proto/                         # gRPC service definitions
    │   ├── common/v1/
    │   │   └── money.proto            # Shared Money type
    │   ├── catalog/v1/
    │   │   └── catalog_service.proto  # Catalog gRPC service
    │   ├── cart/v1/
    │   │   └── cart_service.proto     # Cart gRPC service
    │   ├── fulfillment/v1/
    │   │   └── fulfillment_service.proto # Fulfillment gRPC service
    │   ├── inventory/v1/
    │   │   └── inventory_service.proto # Inventory gRPC service
    │   ├── order/v1/
    │   │   └── order_service.proto    # Order gRPC service
    │   ├── payment/v1/
    │   │   └── payment_service.proto  # Payment gRPC service
    │   ├── rider/v1/
    │   │   └── rider_service.proto    # Rider fleet gRPC service
    │   ├── routing/v1/
    │   │   └── routing_service.proto  # Routing & ETA gRPC service
    │   └── wallet/v1/
    │       └── wallet_service.proto   # Wallet & loyalty gRPC service
    │
    └── resources/schemas/             # JSON Schema event definitions
        ├── README.md
        ├── ai-orchestrator/
        ├── cart/
        ├── catalog/
        ├── common/
        ├── customer-support/
        ├── delivery/
        ├── fraud/
        ├── fulfillment/
        ├── identity/
        ├── inventory/
        ├── notification/
        ├── orders/
        ├── payments/
        ├── pricing/
        ├── reconciliation/
        ├── returns/
        ├── rider/
        ├── routing/
        ├── search/
        ├── wallet/
        └── warehouse/
```

### Build

```bash
# Generate Protobuf + gRPC stubs
./gradlew :contracts:build
```

| Library | Version |
|---------|---------|
| grpc-protobuf | 1.62.2 |
| grpc-stub | 1.62.2 |
| grpc-netty-shaded | 1.62.2 |
| protoc | 3.25.3 |
| protoc-gen-grpc-java | 1.62.2 |

---

## Event Types by Domain

### Orders (`orders.events`)

| Event | Schema | Version | Description |
|-------|--------|---------|-------------|
| `OrderPlaced` | `orders/OrderPlaced.v1.json` | v1 | Order created with items, totals, payment reference |
| `OrderCreated` | `orders/OrderCreated.v1.json` | v1 | Initial order creation record |
| `OrderStatusChanged` | `orders/OrderStatusChanged.v1.json` | v1 | Generic status transition notification |
| `OrderCancelled` | `orders/OrderCancelled.v1.json` | v1 | Order cancelled (by customer or system) |
| `OrderFailed` | `orders/OrderFailed.v1.json` | v1 | Order processing failed |

### Payments (`payments.events`)

| Event | Schema | Version | Description |
|-------|--------|---------|-------------|
| `PaymentAuthorized` | `payments/PaymentAuthorized.v1.json` | v1 | Payment authorization succeeded |
| `PaymentCaptured` | `payments/PaymentCaptured.v1.json` | v1 | Payment captured (funds collected) |
| `PaymentFailed` | `payments/PaymentFailed.v1.json` | v1 | Payment processing failed |
| `PaymentRefunded` | `payments/PaymentRefunded.v1.json` | v1 | Payment refunded to customer |
| `PaymentVoided` | `payments/PaymentVoided.v1.json` | v1 | Payment authorization voided |
| `PaymentDisputed` | `payments/PaymentDisputed.v1.json` | v1 | Payment disputed by cardholder |
| `PaymentDisputeUpdated` | `payments/PaymentDisputeUpdated.v1.json` | v1 | Dispute status updated by PSP |
| `PaymentDisputeWon` | `payments/PaymentDisputeWon.v1.json` | v1 | Dispute resolved in merchant's favor |
| `PaymentDisputeLost` | `payments/PaymentDisputeLost.v1.json` | v1 | Dispute resolved in cardholder's favor |
| `PaymentCaptureReverted` | `payments/PaymentCaptureReverted.v1.json` | v1 | Capture attempt reverted to authorized |
| `PaymentVoidReverted` | `payments/PaymentVoidReverted.v1.json` | v1 | Void attempt reverted to authorized |
| `PaymentRefundFailed` | `payments/PaymentRefundFailed.v1.json` | v1 | Refund processing failed |

### Inventory (`inventory.events`)

| Event | Schema | Version | Description |
|-------|--------|---------|-------------|
| `StockReserved` | `inventory/StockReserved.v1.json` | v1 | Stock reserved for an order |
| `StockConfirmed` | `inventory/StockConfirmed.v1.json` | v1 | Stock reservation confirmed |
| `StockReleased` | `inventory/StockReleased.v1.json` | v1 | Reserved stock released back |
| `LowStockAlert` | `inventory/LowStockAlert.v1.json` | v1 | Stock below reorder threshold |

### Fulfillment (`fulfillment.events`)

| Event | Schema | Version | Description |
|-------|--------|---------|-------------|
| `PickTaskCreated` | `fulfillment/PickTaskCreated.v1.json` | v1 | Pick task assigned to store staff |
| `OrderPacked` | `fulfillment/OrderPacked.v1.json` | v1 | Order picked and packed at store |
| `OrderDispatched` | `fulfillment/OrderDispatched.v1.json` | v1 | Order handed to rider for delivery |
| `OrderDelivered` | `fulfillment/OrderDelivered.v1.json` | v1 | Order delivered to customer |
| `OrderModified` | `fulfillment/OrderModified.v1.json` | v1 | Order items modified or substituted |
| `DeliveryCompleted` | `fulfillment/DeliveryCompleted.v1.json` | v1 | Delivery confirmed complete |
| `DeliveryStatus` | `fulfillment/DeliveryStatus.v1.json` | v1 | Canonical delivery lifecycle states enum |
| `DeliveryFailed` | `fulfillment/DeliveryFailed.v1.json` | v1 | Delivery failed notification with root cause |

### Catalog (`catalog.events`)

| Event | Schema | Version | Description |
|-------|--------|---------|-------------|
| `ProductCreated` | `catalog/ProductCreated.v1.json` | v1 | New product added to catalog |
| `ProductUpdated` | `catalog/ProductUpdated.v1.json` | v1 | Product details updated |
| `ProductDeactivated` | `catalog/ProductDeactivated.v1.json` | v1 | Product deactivated from catalog |

### Identity (`identity.events`)

| Event | Schema | Version | Description |
|-------|--------|---------|-------------|
| `UserRegistered` | `identity/UserRegistered.v1.json` | v1 | New user registered |
| `AccountLocked` | `identity/AccountLocked.v1.json` | v1 | Security lockdown triggered on user account |
| `AccountUnlocked` | `identity/AccountUnlocked.v1.json` | v1 | User account unlocked by customer support or admin |
| `UserErased` | `identity/UserErased.v1.json` | v1 | User data erased (GDPR right-to-be-forgotten) |

### Fraud (`fraud.events`)

| Event | Schema | Version | Description |
|-------|--------|---------|-------------|
| `FraudDetected` | `fraud/FraudDetected.v1.json` | v1 | Fraud detected on transaction |

### Rider (`rider.events`)

| Event | Schema | Version | Description |
|-------|--------|---------|-------------|
| `RiderCreated` | `rider/RiderCreated.v1.json` | v1 | New rider registered |
| `RiderOnboarded` | `rider/RiderOnboarded.v1.json` | v1 | Rider onboarding completed |
| `RiderActivated` | `rider/RiderActivated.v1.json` | v1 | Rider activated for deliveries |
| `RiderAssigned` | `rider/RiderAssigned.v1.json` | v1 | Rider assigned to order |
| `RiderReleased` | `rider/RiderReleased.v1.json` | v1 | Rider assignment released |
| `RiderSuspended` | `rider/RiderSuspended.v1.json` | v1 | Rider account suspended |

### Warehouse (`warehouse.events`)

| Event | Schema | Version | Description |
|-------|--------|---------|-------------|
| `StoreCreated` | `warehouse/StoreCreated.v1.json` | v1 | New store/dark store created |
| `StoreStatusChanged` | `warehouse/StoreStatusChanged.v1.json` | v1 | Store status changed (open/closed/maintenance) |
| `StoreDeleted` | `warehouse/StoreDeleted.v1.json` | v1 | Store decommissioned |

### Cart (`cart.events`)

| Event | Schema | Version | Description |
|---|---|---|---|
| `CartItemAdded` | `cart/CartItemAdded.v1.json` | v1 | Item added to active cart |
| `CartItemRemoved` | `cart/CartItemRemoved.v1.json` | v1 | Item removed from cart |
| `CartCleared` | `cart/CartCleared.v1.json` | v1 | Cart emptied |

### Pricing & Promotions (`pricing.events`, `promotion.events`)

| Event | Schema | Version | Description |
|---|---|---|---|
| `PromotionCreated` | `pricing/PromotionCreated.v1.json` | v1 | New promotional campaign created |
| `PromotionUpdated` | `pricing/PromotionUpdated.v1.json` | v1 | Promotion discount rules or limits updated |
| `PromotionDeactivated` | `pricing/PromotionDeactivated.v1.json` | v1 | Promotion campaign deactivated |
| `CouponRedeemed` | `pricing/CouponRedeemed.v1.json` | v1 | Coupon applied and redeemed on checkout |

### Wallet & Loyalty (`wallet.events`)

| Event | Schema | Version | Description |
|---|---|---|---|
| `PointsEarned` | `wallet/PointsEarned.v1.json` | v1 | Loyalty points earned on delivered order |
| `PointsRedeemed` | `wallet/PointsRedeemed.v1.json` | v1 | Loyalty points converted to discount |
| `WalletCredited` | `wallet/WalletCredited.v1.json` | v1 | Cashback, top-up, or refund credit |
| `WalletDebited` | `wallet/WalletDebited.v1.json` | v1 | Balance debited from wallet |

### Routing & Logistics (`delivery.events`, `routing.events`, `rider.geofence.events`)

| Event | Schema / Payload | Version | Description |
|---|---|---|---|
| `DeliveryStatusUpdated` | `delivery/DeliveryStatusUpdated.v1.json` | v1 | Canonical transition between dispatched, in-transit, arrived |
| `ETABreachRisk` | `routing/ETABreachRisk.v1.json` | v1 | Alert emitted when delivery is projected to breach SLA |
| `DELIVERY_CREATED` | Delivery tracking payload | v1 | Delivery leg instantiated for dispatch |
| `DELIVERY_COMPLETED` | Proof of delivery | v1 | Order delivered and verified |
| `RiderGeofenceEntered` | Store ID, rider ID, radius | v1 | Rider entered dark store pickup geofence |
| `RiderGeofenceExited` | Store ID, rider ID | v1 | Rider departed dark store with package |

### AI Orchestrator (`ai-orchestrator.events`)

| Event | Schema | Version | Description |
|---|---|---|---|
| `IntentAnalyzed` | `ai-orchestrator/IntentAnalyzed.v1.json` | v1 | User shopping intent, entities, and copilot plan |

### Customer Support (`customer-support.events`)

| Event | Schema | Version | Description |
|---|---|---|---|
| `TicketCreated` | `customer-support/TicketCreated.v1.json` | v1 | Escalated customer support ticket and context |

### Notification (`notification.events`)

| Event | Schema | Version | Description |
|---|---|---|---|
| `NotificationDispatched` | `notification/NotificationDispatched.v1.json` | v1 | Multi-channel dispatch (SMS, email, push) notification |

### Reconciliation (`reconciliation.events`)

| Event | Schema | Version | Description |
|---|---|---|---|
| `ReconciliationCompleted` | `reconciliation/ReconciliationCompleted.v1.json` | v1 | Financial or inventory reconciliation run summary |

### Returns & Exchanges (`returns.events`)

| Event | Schema | Version | Description |
|---|---|---|---|
| `ReturnInitiated` | `returns/ReturnInitiated.v1.json` | v1 | Customer initiated product return or exchange |

### Search & Discovery (`search.events`)

| Event | Schema | Version | Description |
|---|---|---|---|
| `SearchIndexed` | `search/SearchIndexed.v1.json` | v1 | Document indexation or search telemetry signal |

---

## Communication Protocol Boundaries

InstaCommerce enforces clear polyglot interface boundaries:
- **Java Microservices (20 Services):** Communicate synchronously over **REST / HTTP** (JSON payload over HTTP/1.1 or HTTP/2) and asynchronously via Kafka domain events conforming to `EventEnvelope.v1.json`. Java services do **not** run gRPC server stubs.
- **Go Microservices & Libraries (`go-shared`, `dispatch-optimizer`, `cdc-consumer`):** Implement and consume the generated Protocol Buffer stubs (`src/main/proto/`) for high-throughput, low-latency inter-service RPCs.
- **Python AI Services (`ai-orchestrator-service`, `ai-inference-service`):** Expose FastAPI HTTP endpoints and consume/publish Kafka events where applicable.

---

## Canonical Kafka Topic Registry (`TopicNames.java`)

All Java services, Go relay, and consumers reference this single source of truth:

| Constant | Kafka Topic | Primary Publisher | Primary Consumers |
|---|---|---|---|
| `IDENTITY_EVENTS` | `identity.events` | `identity-service` | Audit, notification, stream-processor |
| `CATALOG_EVENTS` | `catalog.events` | `catalog-service` | Search, pricing, data-platform |
| `ORDERS_EVENTS` | `orders.events` | `order-service` | Fulfillment, wallet, inventory, data-platform |
| `PAYMENTS_EVENTS` | `payments.events` | `payment-service` | Order, wallet, reconciliation, audit |
| `INVENTORY_EVENTS` | `inventory.events` | `inventory-service` | Warehouse, search, catalog, mobile-bff |
| `FULFILLMENT_EVENTS` | `fulfillment.events` | `fulfillment-service` | Rider fleet, order, routing, notifications |
| `RIDER_EVENTS` | `rider.events` | `rider-fleet-service` | Routing ETA, dispatch optimizer |
| `DELIVERY_EVENTS` | `delivery.events` | `routing-eta-service` | Audit trail, notification, rider fleet |
| `ROUTING_EVENTS` | `routing.events` | `routing-eta-service` | Dispatch optimizer, mobile-bff |
| `RIDER_GEOFENCE_EVENTS` | `rider.geofence.events` | `location-ingestion-service` | Routing ETA, fulfillment |
| `CART_EVENTS` | `cart.events` | `cart-service` | Audit trail, AI orchestrator, analytics |
| `PRICING_EVENTS` | `pricing.events` | `pricing-service` | Search, catalog, cart |
| `PROMOTION_EVENTS` | `promotion.events` | `pricing-service` | Cart, checkout orchestrator |
| `WALLET_EVENTS` | `wallet.events` | `wallet-loyalty-service` | Checkout orchestrator, audit trail |
| `FRAUD_EVENTS` | `fraud.events` | `fraud-detection-service` | Checkout, payment, audit trail |
| `NOTIFICATION_EVENTS` | `notification.events` | `notification-service` | Audit trail, analytics |
| `SEARCH_EVENTS` | `search.events` | `search-service` | AI inference, analytics |
| `WAREHOUSE_EVENTS` | `warehouse.events` | `warehouse-service` | Inventory, routing, dispatch |
| `RECONCILIATION_EVENTS` | `reconciliation.events` | `reconciliation-engine` | Ledger, payment, finance platform |
| `AI_ORCHESTRATOR_EVENTS` | `ai-orchestrator.events` | `ai-orchestrator-service` | Audit trail, stream processor |
| `RIDER_LOCATION_UPDATES` | `rider.location.updates` | `location-ingestion-service` | Routing ETA, dispatch optimizer |
| `PAYMENT_WEBHOOKS` | `payment.webhooks` | `payment-webhook-service` | Payment service |
| `OUTBOX_RELAY_DLQ` | `outbox.relay.dlq` | `outbox-relay-service` | Dead-letter monitor, DLQ retry tooling |
| `RETURNS_EVENTS` | `returns.events` | `fulfillment-service` | Order, inventory, wallet |
| `CUSTOMER_SUPPORT_EVENTS` | `customer-support.events` | `admin-gateway-service` | Notification, audit trail |
| `*_DLT` | `{topic}.DLT` | Any DeadLetterPublishingRecoverer | Ops alerting, replay tooling |


---

## gRPC Proto Definitions

| Service | Proto File | Description |
|---------|-----------|-------------|
| `common.v1.Money` | `common/v1/money.proto` | Shared monetary value type (amount + currency) |
| `CatalogService` | `catalog/v1/catalog_service.proto` | Product CRUD, search, category management |
| `CartService` | `cart/v1/cart_service.proto` | Cart management, item updates, cart checkout validation |
| `FulfillmentService` | `fulfillment/v1/fulfillment_service.proto` | Order fulfillment tasks, pick waves, packing verification |
| `InventoryService` | `inventory/v1/inventory_service.proto` | Stock queries, reservations, adjustments |
| `OrderService` | `order/v1/order_service.proto` | Order lifecycle, status queries, cancellations |
| `PaymentService` | `payment/v1/payment_service.proto` | Payment authorization, capture, refund |
| `RiderService` | `rider/v1/rider_service.proto` | Rider shifts, geofence status, automated dispatch assignments |
| `RoutingService` | `routing/v1/routing_service.proto` | Distance matrix, dynamic route optimization, delivery ETA calculation |
| `WalletService` | `wallet/v1/wallet_service.proto` | Wallet balance, credit/debit transactions, customer loyalty points |

---

## Schema Evolution Strategy

```mermaid
flowchart LR
    subgraph Rules["Schema Evolution Rules"]
        Additive["✅ Additive Changes\n• Add optional fields\n• Add new event types\n• Add enum values"]
        Breaking["❌ Breaking Changes\n• Remove required fields\n• Change field types\n• Rename fields"]
        Versioned["🔄 New Version\n• Create v2 schema file\n• Maintain v1 support\n• Deprecation window: 90 days"]
    end

    Additive --> SameVersion["Same version\n(backward compatible)"]
    Breaking --> Versioned
```

### Rules

1. **Backward compatible** (additive) changes are applied to the existing `v1` schema:
   - Add new optional fields
   - Add new event types
   - Widen enum values

2. **Breaking changes** require a new version file (e.g., `OrderPlaced.v2.json`):
   - Removing or renaming required fields
   - Changing field types
   - Both v1 and v2 are supported during a **90-day deprecation window**

3. **Naming conventions**:
   - Events: `{AggregateAction}.v{N}.json` (e.g., `OrderPlaced.v1.json`)
   - Kafka topics: `{domain}.events` (e.g., `orders.events`)

---

## CI Validation

Schema validation runs automatically in CI:

```bash
# Build contracts (compiles protos + validates JSON schemas)
./gradlew :contracts:build
```

| Check | Trigger | Description |
|-------|---------|-------------|
| Proto compilation | Every PR | Compile all `.proto` files, generate gRPC stubs |
| JSON Schema validation | Every PR | Validate all schemas are valid draft-07 |
| Breaking change detection | Every PR | Diff schemas against `main` branch for field removals |
| Compatibility test | Every PR | Verify consumers can deserialize with updated schemas |
