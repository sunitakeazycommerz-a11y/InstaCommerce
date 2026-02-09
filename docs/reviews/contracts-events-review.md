# Contracts, Events & Cross-Service Communication — Deep Review

> **Scope**: All 18 services in `services/`, `contracts/` module, Kafka topics, REST clients, gRPC protos  
> **Reviewer**: API & Event Architecture Specialist  
> **Date**: 2025-07-02  
> **Verdict**: ⚠️ **NEEDS SIGNIFICANT WORK** — Strong foundation (outbox pattern, proto definitions, shared contract module) but critical gaps in topic naming consistency, missing event publishers, zero OpenAPI docs, no circuit breakers on REST clients, and inconsistent DLT naming.

---

## Executive Summary

| Category | Score | Status |
|----------|-------|--------|
| REST API Consistency | 4/10 | 🔴 No versioning, no Swagger, plural/singular mix |
| Event Schema Registry | 6/10 | 🟡 JSON Schema files exist but no runtime registry |
| Topic Naming Consistency | 3/10 | 🔴 Critical `orders.events` vs `order.events` mismatch |
| Event Completeness | 5/10 | 🟡 Core services publish; cart/pricing/wallet dormant |
| Cross-Service Resilience | 3/10 | 🔴 No circuit breakers, timeouts-only on REST clients |
| Idempotency | 7/10 | 🟢 Good at API level; missing in event consumers |
| DLQ/Error Handling | 4/10 | 🔴 3 different DLT naming conventions, wallet has none |
| gRPC Readiness | 7/10 | 🟢 Proto files defined; no runtime usage yet |
| Contract Module Structure | 8/10 | 🟢 Clean layout, versioned schemas, shared Gradle dep |

---

## Part 1: API Contracts

### 1.1 REST API Consistency — 🔴 CRITICAL

**Finding**: Zero services follow a consistent URL convention. No API versioning exists anywhere.

| Service | Base Path | Style | Versioned |
|---------|-----------|-------|-----------|
| order-service | `/orders` | Singular noun* | ❌ |
| catalog-service | `/products`, `/categories`, `/pricing` | Plural + Singular mix | ❌ |
| cart-service | `/cart` | Singular | ❌ |
| identity-service | `/auth` | Verb-like | ❌ |
| payment-service | `/payments` | Plural | ❌ |
| inventory-service | `/inventory` | Singular | ❌ |
| fulfillment-service | `/fulfillment` | Singular | ❌ |
| pricing-service | `/pricing` | Singular | ❌ |
| search-service | `/search` | Verb-like | ❌ |
| checkout-orchestrator | `/checkout` | Singular | ❌ |
| wallet-loyalty-service | `/wallet`, `/loyalty` | Singular | ❌ |
| routing-eta-service | `/deliveries` | Plural | ❌ |
| warehouse-service | `/stores` | Plural | ❌ |
| rider-fleet-service | `/riders` | Plural | ❌ |
| fraud-detection-service | `/fraud` | Singular | ❌ |
| config-feature-flag-service | `/flags` | Plural | ❌ |
| audit-trail-service | `/audit` | Singular | ❌ |

**Inconsistencies**:
- **Plural vs Singular**: `/payments` (plural) vs `/cart` (singular) vs `/inventory` (singular) — no standard
- **Action-based URLs**: `POST /orders/{id}/cancel`, `POST /payments/{id}/capture` — violates REST; should be state transitions via PATCH
- **No `/api/v1/` prefix**: Breaking changes will require consumer-side coordination with zero runway

**Recommendation**:
```
# Adopt this convention for all services:
/api/v1/{plural-resource}
/api/v1/{plural-resource}/{id}
/api/v1/{plural-resource}/{id}/{sub-resource}

# Examples:
/api/v1/orders
/api/v1/orders/{id}
/api/v1/orders/{id}/status   (PATCH for state change)
/api/v1/payments/{id}
/api/v1/payments/{id}/capture (POST — acceptable for non-CRUD actions)
```

---

### 1.2 Request/Response DTOs — 🟡 MOSTLY CONSISTENT

**Good**: All request DTOs use `@JsonIgnoreProperties(ignoreUnknown = true)` (50+ classes verified). Field naming is consistently `camelCase` across all services.

**Bad**:
- **No shared pagination DTO**: Each service that returns lists builds its own page response
- **No shared `ApiResponse<T>` wrapper**: Responses are raw entities or custom DTOs per service
- **Money representation inconsistency**:
  - Proto `Money` uses `amount_minor` (integer cents/paise) ✅
  - `PaymentCaptured.v1.json` schema uses `"amount": number` (floating point) ❌
  - `PaymentRefunded.v1.json` schema uses `"amountCents": integer` ✅
  - `OrderPlaced.v1.json` schema uses `"unitPrice": number` (floating point) ❌
  
  > 🔴 **BUG**: `amount` as `number` in PaymentCaptured and `unitPrice` as `number` in OrderPlaced will cause floating-point rounding errors for financial calculations. The spec doc says "All amounts in Money proto use `amount_minor` (paise/cents) to avoid float rounding" — but the JSON schemas violate this rule.

**Recommendation**: Create shared DTOs in `contracts/`:
```java
// contracts/src/main/java/.../dto/PageResponse.java
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

// contracts/src/main/java/.../dto/MoneyDto.java
public record MoneyDto(long amountMinor, String currency) {}  // ALWAYS integer cents
```

---

### 1.3 API Versioning Strategy — 🔴 MISSING

**Finding**: Zero services implement API versioning. The spec document (`09-contracts-events-protobuf.md`) defines proto versioning (`inventory.v1`, `payment.v1`) and JSON schema file versioning (`OrderPlaced.v1.json`), but **REST endpoints have no version prefix**.

**Risk**: Any breaking change to a REST endpoint (field rename, removal, type change) requires all consumers to update simultaneously. With 18 services making cross-service REST calls, this creates a cascading deployment dependency.

**Recommendation**: URL-based versioning `/api/v1/` — simplest for debugging and routing. Add to all `@RequestMapping` class-level annotations:
```java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController { ... }
```

---

### 1.4 OpenAPI/Swagger — 🔴 COMPLETELY ABSENT

**Finding**: Zero of 18 services have SpringDoc/Swagger dependencies. No `@Operation`, `@ApiResponse`, `@Schema`, or `@Tag` annotations anywhere. No `springdoc-openapi` in any `build.gradle.kts`.

**Impact**: No auto-generated API documentation. Cross-team integration relies on reading source code or Slack conversations.

**Recommendation**: Add to all services:
```kotlin
// build.gradle.kts
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0")
```
```yaml
# application.yml
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
```

---

### 1.5 gRPC Readiness — 🟢 WELL-PREPARED

**Finding**: The `contracts/` module has well-structured proto files for 3 critical internal services:

| Proto | Service | RPCs Defined | Used in Runtime? |
|-------|---------|-------------|-----------------|
| `inventory/v1/inventory_service.proto` | inventory-service | ReserveStock, ConfirmReservation, CancelReservation, CheckAvailability | ❌ Not yet |
| `payment/v1/payment_service.proto` | payment-service | AuthorizePayment, CapturePayment, VoidAuthorization, RefundPayment | ❌ Not yet |
| `catalog/v1/catalog_service.proto` | catalog-service | GetProductDetails, GetProductsBatch | ❌ Not yet |
| `common/v1/money.proto` | Shared | Money message | ❌ Not yet |

**Good**:
- All protos use `java_multiple_files = true` ✅
- Package versioning follows `package.v1` convention ✅
- `idempotency_key` field on `ReserveStockRequest` and `AuthorizeRequest` ✅
- `Money` message uses `int64 amount_minor` avoiding float issues ✅
- Gradle protobuf plugin configured with grpc-java codegen ✅

**Bad**:
- **No runtime gRPC usage**: All inter-service calls are REST via `RestTemplate`. Zero `@GrpcService` or gRPC client stubs found in any service
- **Missing protos**: fulfillment, routing-eta, rider-fleet, checkout, fraud, wallet — these make cross-service calls but have no proto definitions
- Checkout orchestrator calls 5 services via REST — prime candidate for gRPC migration

**Recommendation**: Migrate the checkout-orchestrator → inventory, payment, and catalog calls from REST to gRPC first (latency-sensitive path). Add protos for `fulfillment/v1/` and `routing/v1/`.

---

### 1.6 Error Response Structure — 🟡 MOSTLY CONSISTENT

**Finding**: 17 of 18 services have a `GlobalExceptionHandler` returning the same error shape:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Descriptive error message",
  "traceId": "abc-123-trace-id",
  "timestamp": "2025-01-15T10:30:00Z",
  "details": [
    { "field": "email", "defaultMessage": "must not be blank" }
  ]
}
```

**Good**: Common error codes (`VALIDATION_ERROR`, `ACCESS_DENIED`, `INTERNAL_ERROR`, `RATE_LIMIT_EXCEEDED`) are consistent.

**Bad**:
- **payment-service** lacks `RequestNotPermitted` (rate limiter) handler — will return raw 500 if rate-limited
- **fulfillment-service** uses `logger` instead of `log` (Lombok) — cosmetic but inconsistent
- **checkout-orchestrator** has extra handlers for Temporal-specific exceptions (`WorkflowException`, `WorkflowNotFoundException`, `ResourceAccessException`) — these are appropriate but should be documented
- **notification-service** — exception handler not verified; it lacks a `GlobalExceptionHandler` from the pattern search (may exist under different name)
- The error response is **not formalized as a shared DTO** in `contracts/` — each service redefines the error structure locally

**Recommendation**: Extract `ErrorResponse` and `ErrorDetail` to `contracts/` module as a shared DTO. All services import from there.

---

## Part 2: Event Contracts

### 2.1 Event Schema Registry — 🟡 PARTIAL

**Finding**: The `contracts/` module has 21 JSON Schema files organized by domain:

```
contracts/src/main/resources/schemas/
├── orders/       → 6 schemas (OrderPlaced, OrderPacked, OrderDispatched, OrderDelivered, OrderCancelled, OrderFailed)
├── payments/     → 5 schemas (PaymentAuthorized, PaymentCaptured, PaymentRefunded, PaymentFailed, PaymentVoided)
├── inventory/    → 4 schemas (StockReserved, StockConfirmed, StockReleased, LowStockAlert)
├── fulfillment/  → 4 schemas (PickTaskCreated, OrderPacked, RiderAssigned, DeliveryCompleted)
├── catalog/      → 2 schemas (ProductCreated, ProductUpdated)
```

**Good**:
- JSON Schema Draft-07 with `required` fields defined ✅
- Version in filename (`*.v1.json`) ✅
- Contract test example in spec document ✅

**Bad**:
- **No runtime schema validation**: No Confluent Schema Registry or equivalent. Schemas are build-time artifacts only — consumers can receive events that violate the schema with no runtime enforcement
- **Missing schemas for 7+ event-producing services**:
  - `identity/` — UserRegistered, UserDeleted events (published, no schema)
  - `rider/` — RiderLocationUpdated, RiderStatusChanged events (published, no schema)
  - `warehouse/` — StoreCreated, StoreStatusChanged events (published, no schema)
  - `fraud/` — FraudDetected event (published, no schema)
  - `wallet/` — no events published yet
  - `cart/` — outbox infrastructure exists but dormant
  - `pricing/` — outbox infrastructure exists but dormant
- **No consumer-driven contract tests in CI**: The spec shows an example `OrderEventContractTest` but no actual test files were found in any service's test directory

**Recommendation**: 
1. Add missing JSON schemas for identity, rider, warehouse, fraud domains
2. Implement contract tests that run in CI — each consumer validates its deserialization against the producer's schema
3. Consider adding runtime schema validation via a custom Kafka deserializer that validates against JSON Schema on consumption

---

### 2.2 Topic Naming — 🔴 CRITICAL INCONSISTENCY

**Finding**: Topic naming is **inconsistent between the spec document, producers, and consumers**, causing actual runtime failures or silent message loss.

| Domain | Spec Document Says | Producer Publishes To | Consumer Subscribes To | Match? |
|--------|-------------------|----------------------|----------------------|--------|
| Orders | `orders.events` | order-service → outbox → Debezium routes | fulfillment: `orders.events` ✅, notification: `orders.events` ✅, fraud: `order.events` ❌, wallet: `order.events` ❌, audit: `order.events` ❌ | 🔴 SPLIT |
| Payments | `payments.events` | payment-service → outbox → Debezium routes | notification: `payments.events` ✅, fraud: `payment.events` ❌, wallet: `payment.events` ❌, audit: `payment.events` ❌ | 🔴 SPLIT |
| Inventory | `inventory.events` | inventory-service → outbox → Debezium | audit: `inventory.events` ✅ | ✅ |
| Fulfillment | `fulfillment.events` | fulfillment-service → outbox → Debezium | notification: `fulfillment.events` ✅, rider-fleet: `fulfillment.events` ✅, audit: `fulfillment.events` ✅ | ✅ |
| Catalog | `catalog.events` | catalog-service → outbox → Debezium | search: `catalog.events` ✅, pricing: `catalog.events` ✅, audit (missing) | ✅ |
| Identity | (not in spec) | identity-service → outbox → Debezium | fulfillment: `identity.events` ✅, notification: `identity.events` ✅, order: `identity.events` ✅, audit: `identity.events` ✅ | ✅ |
| Rider | (not in spec) | rider-fleet-service → outbox → Debezium | routing-eta: `rider.events` ✅, audit: `rider.events` ✅ | ✅ |
| Rider Location | (not in spec) | ? | routing-eta: `rider.location.updates` | ❓ Unknown producer |

> 🔴 **BUG — ORDERS TOPIC**: `fraud-detection-service`, `wallet-loyalty-service`, and `audit-trail-service` subscribe to `order.events` (singular), but the spec says `orders.events` (plural) and fulfillment/notification subscribe to `orders.events`. **One group is reading from a non-existent topic or a different topic.**
>
> 🔴 **BUG — PAYMENTS TOPIC**: Same issue. `fraud-detection-service`, `wallet-loyalty-service`, and `audit-trail-service` subscribe to `payment.events` (singular), while `notification-service` subscribes to `payments.events` (plural).

**Root Cause**: The Debezium outbox connector config uses `transforms.outbox.route.topic.replacement = "${DOMAIN}.events"`. If the `DOMAIN` env var is set to `orders` for order-service and `order` for another deployment config, topics diverge.

**Recommendation**:
1. **Immediately audit** the actual Kafka topic names in all environments
2. Standardize on `{domain-plural}.events` — e.g., `orders.events`, `payments.events`
3. Create a `TopicNames.java` constants class in `contracts/`:
```java
public final class TopicNames {
    public static final String ORDERS    = "orders.events";
    public static final String PAYMENTS  = "payments.events";
    public static final String INVENTORY = "inventory.events";
    public static final String FULFILLMENT = "fulfillment.events";
    public static final String CATALOG   = "catalog.events";
    public static final String IDENTITY  = "identity.events";
    public static final String RIDER     = "rider.events";
    private TopicNames() {}
}
```

---

### 2.3 Event Versioning — 🟢 WELL-DESIGNED (on paper)

**Finding**: The spec document defines clear rules:
1. Backward-compatible changes only for existing versions ✅
2. Version in filename (`OrderPlaced.v1.json` → `OrderPlaced.v2.json`) ✅
3. Proto versioning via package path (`inventory.v1`) ✅
4. Consumers MUST use `@JsonIgnoreProperties(ignoreUnknown = true)` ✅ (verified across all consumer DTOs)

**Gap**: No tooling enforces these rules. A developer can change a required field in `OrderPlaced.v1.json` and the build will pass. Need a CI check that compares schema changes against the base branch and blocks breaking changes to v1 schemas.

---

### 2.4 Event Completeness — 🟡 GAPS

**Services that PUBLISH events (outbox + Debezium)**:

| Service | Events Published | Outbox Table | JSON Schema |
|---------|-----------------|-------------|-------------|
| order-service | OrderPlaced, OrderCancelled, OrderStatusChanged | ✅ V3 migration | ✅ 6 schemas |
| payment-service | PaymentAuthorized, PaymentCaptured, PaymentVoided, PaymentRefunded | ✅ V3 migration | ✅ 5 schemas |
| inventory-service | StockReserved, StockReleased, StockAdjusted | ✅ V4 migration | ✅ 4 schemas |
| fulfillment-service | OrderPacked, OrderDelivered, OrderDispatched | ✅ V3 migration | ✅ 4 schemas |
| catalog-service | ProductCreated, ProductUpdated | ✅ V3 migration | ✅ 2 schemas |
| identity-service | UserRegistered(?), UserDeleted | ✅ migration | ❌ No schema |
| rider-fleet-service | RiderStatusChanged(?) | ✅ infrastructure | ❌ No schema |
| warehouse-service | StoreCreated, StoreStatusChanged, StoreDeleted | ✅ migration | ❌ No schema |
| fraud-detection-service | FraudDetected | ✅ infrastructure | ❌ No schema |

**Services with outbox infrastructure but NOT publishing**:

| Service | Has Outbox Table | Publishing? | Should Publish? |
|---------|-----------------|------------|----------------|
| cart-service | ✅ V4 migration | ❌ No calls | 🟡 CartUpdated, CartAbandoned (analytics) |
| pricing-service | ✅ V4 migration | ❌ No calls | 🟡 PriceChanged (catalog/search sync) |
| wallet-loyalty-service | ✅ V5 migration | ❌ No calls | 🟡 WalletCredited, WalletDebited, LoyaltyPointsEarned |
| routing-eta-service | ✅ V3 migration | ❌ No calls | 🟡 ETAUpdated (notification triggers) |

**Services that SHOULD have outbox but DON'T**:

| Service | Missing Events |
|---------|---------------|
| notification-service | NotificationSent, NotificationFailed (for audit/analytics) |
| checkout-orchestrator | CheckoutStarted, CheckoutCompleted, CheckoutFailed (observability) |
| search-service | IndexUpdated (operational metrics) |
| config-feature-flag-service | FlagToggled (audit trail — critical for compliance) |

---

### 2.5 Consumer Alignment — 🔴 SCHEMA MISMATCH RISK

**Finding**: Consumers deserialize events using different strategies with no shared DTO:

| Consumer | Deserialization Method | Shared DTO from contracts/? |
|----------|----------------------|----------------------------|
| fulfillment → orders.events | `OrderEvent` → `OrderPlacedPayload` (local DTOs) | ❌ |
| notification → orders.events | `EventEnvelope` (local DTO) | ❌ |
| fraud → order.events | `EventEnvelope` with `JsonNode` payload | ❌ |
| wallet → order.events | Raw `String` → `JsonNode` manual parse | ❌ |
| audit → 7 topics | Raw `JsonNode` transformation | ❌ |
| search → catalog.events | Local `CatalogProductEvent` DTO | ❌ |
| pricing → catalog.events | Local `EventEnvelope` + `CatalogProductEvent` | ❌ |
| routing-eta → rider.events | Local `RiderEventPayload` DTO | ❌ |
| rider-fleet → fulfillment.events | Local `FulfillmentEventPayload` DTO | ❌ |

> 🔴 **Every consumer defines its own event DTO locally.** The `contracts/` module has JSON Schemas and even a recommended `OrderPlacedEvent` Java DTO in the spec document, but **no consumer imports event DTOs from `contracts/`**. This means:
> - Field name changes in a producer can silently break consumers
> - No compile-time contract enforcement
> - Each consumer may expect different field names for the same event

**Specific field mismatch risks**:
- Fulfillment's `OrderPlacedPayload` has `totalCents` — but `OrderPlaced.v1.json` schema uses `totalAmount` (float)
- Audit uses generic `JsonNode` — cannot validate schema compliance
- Wallet parses raw strings — most fragile, zero type safety

**Recommendation**: Create shared event DTOs in `contracts/src/main/java/`:
```
contracts/src/main/java/com/instacommerce/contracts/events/
├── orders/OrderPlacedEvent.java
├── orders/OrderCancelledEvent.java
├── payments/PaymentCapturedEvent.java
├── inventory/StockReservedEvent.java
├── fulfillment/DeliveryCompletedEvent.java
└── common/EventEnvelope.java
```

---

### 2.6 Dead Letter Topics — 🔴 THREE DIFFERENT CONVENTIONS

| Convention | Services Using It | DLT Topic Example |
|------------|------------------|-------------------|
| Default Spring Kafka (`-dlt`) | fulfillment, search, rider-fleet | `orders.events-dlt` |
| `.dlq` suffix | notification | `orders.events.dlq` |
| `.DLT` suffix (uppercase) | order, pricing, fraud, routing-eta, cart | `orders.events.DLT` |
| **NO DLQ** | **wallet-loyalty** | ❌ Messages lost on error |

**Risks**:
1. **Wallet-loyalty has NO DLQ**: Failed messages from `payment.events` and `order.events` are silently dropped after a `try-catch` logs the error. Wallet credits/debits could be lost.
2. **Three naming conventions** make DLQ monitoring impossible with a single dashboard or alert rule
3. No monitoring/alerting configured for any DLT topic

**Recommendation**:
1. Fix wallet-loyalty-service immediately — add `KafkaErrorConfig` with DLQ
2. Standardize on `.dlt` suffix (lowercase, Spring Kafka default)
3. Add DLT consumer lag monitoring in Grafana for all DLT topics
4. Create a DLT reprocessing utility (admin endpoint to replay DLT messages)

---

## Part 3: Cross-Service Coupling

### 3.1 Sync vs Async Mapping — 🟡 MOSTLY CORRECT

| Interaction | Current Pattern | Correct? | Notes |
|-------------|----------------|----------|-------|
| Checkout → Cart (fetch) | REST (sync) | ✅ | Need data immediately |
| Checkout → Pricing (compute) | REST (sync) | ✅ | Need data immediately |
| Checkout → Inventory (reserve) | REST (sync) | ✅ | Need confirmation |
| Checkout → Payment (authorize) | REST (sync) | ✅ | Need confirmation |
| Checkout → Order (create) | REST (sync) | ✅ | Need orderId back |
| Order → Fulfillment | Kafka (async) | ✅ | Event-driven |
| Order → Notification | Kafka (async) | ✅ | Event-driven |
| Payment → Notification | Kafka (async) | ✅ | Event-driven |
| Fulfillment → Order (status update) | REST (sync) | ⚠️ | **Should be async** — fulfillment updates order status via REST call; if order-service is down, fulfillment blocks |
| Fulfillment → Payment (refund) | REST (sync) | ⚠️ | **Should be async** — refunds can be processed asynchronously |
| Notification → Identity (user lookup) | REST (sync) | ⚠️ | **Should be cached or async** — notification should cache user contact info, not call identity on every event |
| Notification → Order (order lookup) | REST (sync) | ⚠️ | **Should use event data** — the OrderPlaced event already has all needed data |
| Fulfillment → Inventory (confirm) | REST (sync) | 🟡 | Acceptable but gRPC would be better |

**Recommendation**: Convert fulfillment→order status updates and fulfillment→payment refund triggers to Kafka events. Notification should enrich events at publish time (include user email/phone in event payload) to avoid runtime REST calls.

---

### 3.2 Circuit Breakers — 🔴 MISSING ON ALL REST CLIENTS

**Finding**: While Resilience4j is included in 10 service `build.gradle.kts` files, **ALL usage is for rate limiting only** (`@RateLimiter`). **Zero `@CircuitBreaker` annotations or configurations exist anywhere in the codebase.**

| Service | Has Resilience4j | Rate Limiter | Circuit Breaker | Retry |
|---------|-----------------|-------------|----------------|-------|
| checkout-orchestrator | ❌ | ❌ | ❌ | Via Temporal |
| order-service | ✅ | ✅ | ❌ | ❌ |
| fulfillment-service | ❌ | ❌ | ❌ | ❌ |
| notification-service | ❌ | ❌ | ❌ | ✅ (custom retry) |
| catalog-service | ✅ | ✅ | ❌ | ❌ |
| identity-service | ✅ | ✅ | ❌ | ❌ |
| pricing-service | ✅ | ✅ | ❌ | ❌ |

**REST clients with NO circuit breaker**:
- `checkout-orchestrator` → cart, pricing, inventory, payment, order (5 calls, any failure cascades)
- `fulfillment-service` → order, payment, inventory (3 calls)
- `notification-service` → identity (user lookup), order (order lookup)
- `order-service` → payment, inventory, cart (3 calls)

**Note**: The checkout-orchestrator uses Temporal, which provides activity-level retries and timeouts. This partially mitigates the circuit breaker gap for checkout flows specifically. However, `fulfillment-service` and `notification-service` have **zero resilience** on their REST clients.

**Timeouts**: The checkout-orchestrator's `RestClientConfig` configures `connectTimeout` and `readTimeout` via properties ✅. Fulfillment's REST clients also use `RestTemplateBuilder` with timeouts ✅. But without circuit breakers, slow downstream services still consume thread pools.

**Recommendation**:
```yaml
# application.yml for services making REST calls
resilience4j:
  circuitbreaker:
    instances:
      paymentService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
      inventoryService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
```

---

### 3.3 Idempotency — 🟢 API Level / 🔴 Event Consumer Level

**API-Level Idempotency** (Good):

| Service | Mechanism | Storage |
|---------|-----------|---------|
| payment-service | `idempotency_key` on payments + refunds | DB unique constraint |
| inventory-service | `idempotency_key` on reservations | DB unique constraint |
| order-service | `idempotency_key` on orders | DB unique constraint |
| checkout-orchestrator | `Idempotency-Key` header → Caffeine cache | In-memory (⚠️ lost on restart) |
| wallet-loyalty-service | `reference_type + reference_id` unique index | DB unique index |
| notification-service | `event_id + channel` unique constraint | DB unique constraint |

> ⚠️ **Checkout idempotency uses in-memory Caffeine cache** — on pod restart or scale-out, duplicate checkouts can occur. Should use Redis or DB-backed idempotency.

**Event Consumer Idempotency** (Bad):

| Consumer | Has Dedup? | Risk |
|----------|-----------|------|
| fulfillment → orders.events | ❌ | Duplicate pick tasks created |
| notification → orders.events | ✅ (via DeduplicationService) | Low risk |
| fraud → order.events | ❌ | Duplicate fraud scores (low impact) |
| wallet → order.events | ❌ | 🔴 **Duplicate loyalty points** — financial impact |
| wallet → payment.events | ❌ | 🔴 **Duplicate wallet credits** — financial impact |
| search → catalog.events | ❌ | Duplicate index updates (idempotent operation, low risk) |
| pricing → catalog.events | ❌ | Duplicate price syncs (low risk) |
| rider-fleet → fulfillment.events | ❌ | Duplicate rider notifications |
| routing-eta → rider.events | ❌ | Duplicate ETA calculations (low risk) |
| audit → all topics | ❌ | Duplicate audit entries |

> 🔴 **Critical**: wallet-loyalty-service consumers have NO deduplication and NO DLQ. A Kafka rebalance or consumer restart will reprocess messages, potentially crediting wallets or awarding points multiple times.

**Recommendation**: All consumers with side effects (wallet, fulfillment, notification) must implement idempotency:
```java
// In contracts/ module
public interface IdempotentEventProcessor {
    boolean isProcessed(String eventId);
    void markProcessed(String eventId);
}
```

---

### 3.4 Ordering Guarantees — 🟢 MOSTLY CORRECT

**Finding**: The outbox pattern with Debezium uses `aggregate_id` as the Kafka message key (configured in Debezium connector: `transforms.outbox.table.field.event.key = aggregate_id`). This ensures:
- All events for the same order go to the same partition → ordered ✅
- All events for the same payment go to the same partition → ordered ✅

**Potential Issue**: `rider.location.updates` topic consumed by routing-eta-service — the producer and partition key strategy for this topic is unclear. If rider location updates are not keyed by `riderId`, events for the same rider could be processed out of order.

**Recommendation**: Verify partition key for `rider.location.updates` is `riderId`.

---

## Part 4: Missing Contracts

### 4.1 Checkout → All Services — 🟡 IMPLICIT CONTRACTS

The checkout-orchestrator makes REST calls to 5 services via Temporal activities:

| Activity | Target Service | Contract Defined? | Notes |
|----------|---------------|------------------|-------|
| `CartActivityImpl` | cart-service | ❌ No formal contract | Calls GET to fetch cart |
| `PricingActivityImpl` | pricing-service | ❌ No formal contract | Calls POST to compute price |
| `InventoryActivityImpl` | inventory-service | ✅ Proto exists (not used) | Calls POST /inventory/reserve |
| `PaymentActivityImpl` | payment-service | ✅ Proto exists (not used) | Calls POST /payments/authorize |
| `OrderActivityImpl` | order-service | ❌ No formal contract | Calls POST to create order |

**Recommendation**: Define OpenAPI specs or protos for cart and pricing service endpoints used by checkout. Migrate inventory and payment calls to use the existing gRPC proto stubs.

---

### 4.2 Fraud → Checkout — 🔴 NO CONTRACT

**Finding**: `fraud-detection-service` has a `FraudCheckRequest` DTO and REST API at `POST /fraud/check`, but:
- Checkout-orchestrator does NOT call fraud-detection — no fraud activity in the checkout workflow
- No fraud check in the order placement flow
- Fraud service consumes order/payment events reactively but doesn't block checkout

**Risk**: Fraudulent orders are detected AFTER placement, not prevented.

**Recommendation**: Add `FraudCheckActivity` to checkout workflow:
```protobuf
// contracts/src/main/proto/fraud/v1/fraud_service.proto
service FraudService {
  rpc CheckOrder(FraudCheckRequest) returns (FraudCheckResponse);
}
message FraudCheckRequest {
  string user_id = 1;
  common.v1.Money amount = 2;
  string payment_method_id = 3;
  string shipping_address_hash = 4;
}
message FraudCheckResponse {
  FraudDecision decision = 1;  // APPROVE, REVIEW, REJECT
  double risk_score = 2;
  string reason = 3;
}
```

---

### 4.3 Feature Flag → All Services — 🟡 PARTIALLY DEFINED

**Finding**: `config-feature-flag-service` has a REST API at `/flags` with endpoints to evaluate flags. However:
- No client SDK or shared client library for other services to call it
- No caching strategy documented — every flag check is a REST call
- No contract for the evaluation request/response
- No `FlagToggled` event for audit trail

**Recommendation**: Create a shared feature-flag client in `contracts/`:
```java
// contracts/src/main/java/.../featureflag/FeatureFlagClient.java
public interface FeatureFlagClient {
    boolean isEnabled(String flagKey, String userId);
    <T> T evaluate(String flagKey, String userId, Class<T> valueType);
}
```

---

### 4.4 Wallet → Payment (Split Payment) — 🔴 NO CONTRACT

**Finding**: `wallet-loyalty-service` has wallet balance/debit/credit APIs, but there is **no integration with payment-service for split payments** (part wallet + part card). The checkout orchestrator does not call wallet-loyalty-service.

**Missing**:
- No `WalletDebitActivity` in checkout workflow
- No wallet balance check before payment authorization
- No contract for wallet debit/credit from checkout flow
- No rollback (saga compensation) for wallet debit if payment fails

**Recommendation**: Add wallet as an optional payment method in checkout:
```protobuf
// contracts/src/main/proto/wallet/v1/wallet_service.proto
service WalletService {
  rpc DebitWallet(DebitRequest) returns (DebitResponse);
  rpc CreditWallet(CreditRequest) returns (CreditResponse);  // compensation
  rpc GetBalance(BalanceRequest) returns (BalanceResponse);
}
```

---

### 4.5 Audit → All Services — 🟢 MOSTLY WORKING

**Finding**: `audit-trail-service` subscribes to 7 event topics and stores all events as audit entries. This is the most comprehensive consumer.

**Good**:
- Subscribes to: `identity.events`, `catalog.events`, `order.events`, `payment.events`, `inventory.events`, `fulfillment.events`, `rider.events` ✅
- Has DLQ (`audit.dlq`) for failed processing ✅
- Generic `JsonNode` deserialization handles any event shape ✅

**Gaps**:
- **Missing topics**: Not subscribed to `catalog.events` in the code (contradicting the topic list — need to verify)
- **Not subscribed to**: `rider.location.updates`, notification events, checkout events, warehouse events, pricing events, search events, fraud events, wallet events, feature-flag events
- **No REST API contract** for services to push audit events directly (only Kafka ingestion)
- Uses `order.events` (singular) — may not match actual topic name (see §2.2)

**Recommendation**: Add subscriptions for `warehouse.events`, `fraud.events`. Add a REST endpoint for services that don't publish Kafka events to push audit entries.

---

## Part 5: Priority Action Items

### 🔴 P0 — Fix Immediately (Production Risk)

| # | Issue | Impact | Services Affected |
|---|-------|--------|-------------------|
| 1 | **Topic naming mismatch**: `orders.events` vs `order.events`, `payments.events` vs `payment.events` | Consumers reading from wrong/non-existent topics. Events silently dropped. | fraud, wallet, audit vs fulfillment, notification |
| 2 | **Wallet-loyalty has NO DLQ** | Failed event processing silently drops messages. Wallet credits lost. | wallet-loyalty-service |
| 3 | **Wallet-loyalty consumers have NO idempotency** | Kafka rebalance causes duplicate wallet credits/loyalty points. Financial loss. | wallet-loyalty-service |
| 4 | **Money type inconsistency**: `amount` (float) vs `amountCents` (integer) in JSON schemas | Floating-point rounding errors in financial calculations | OrderPlaced, PaymentCaptured schemas |

### 🟡 P1 — Fix This Sprint

| # | Issue | Impact | Effort |
|---|-------|--------|--------|
| 5 | Add API versioning (`/api/v1/`) to all services | Prevents breaking changes from cascading | Medium |
| 6 | Create shared topic name constants in `contracts/` | Prevents topic name typos | Small |
| 7 | Standardize DLT naming convention (`.dlt` lowercase) | Enables unified DLQ monitoring | Small |
| 8 | Add circuit breakers to fulfillment + notification REST clients | Prevents cascade failures | Medium |
| 9 | Add shared event DTOs to `contracts/` module | Compile-time contract enforcement | Medium |
| 10 | Add missing JSON schemas (identity, rider, warehouse, fraud) | Complete schema registry | Medium |

### 🟢 P2 — Plan for Next Quarter

| # | Issue | Impact | Effort |
|---|-------|--------|--------|
| 11 | Migrate checkout→inventory/payment from REST to gRPC | Latency reduction on critical path | Large |
| 12 | Add SpringDoc/OpenAPI to all services | Developer productivity, API discoverability | Medium |
| 13 | Add fraud check to checkout workflow | Fraud prevention | Large |
| 14 | Add wallet split-payment to checkout workflow | New payment method | Large |
| 15 | Activate dormant outbox publishers (cart, pricing, wallet) | Analytics, cross-service sync | Medium |
| 16 | Move checkout idempotency from Caffeine to Redis | Survives pod restarts | Small |
| 17 | Add contract tests to CI pipeline | Prevents schema drift | Medium |
| 18 | Add feature-flag client SDK to contracts/ | Standardized flag evaluation | Medium |
| 19 | Convert fulfillment→order status updates to async events | Decouples services | Medium |
| 20 | Add runtime schema validation (custom Kafka deserializer) | Catches schema violations at consumption | Large |

---

## Appendix A: Full Topic × Consumer Matrix

```
                        ┌──────────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┐
                        │ fulfill- │ notifi-  │  fraud-  │ wallet-  │  audit-  │ search-  │ pricing- │ rider-   │
                        │   ment   │  cation  │detection │ loyalty  │  trail   │ service  │ service  │  fleet   │
┌───────────────────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┤
│ orders.events         │    ✅    │    ✅    │  ❌(1)   │  ❌(1)   │  ❌(1)   │          │          │          │
│ order.events          │          │          │    ✅    │    ✅    │    ✅    │          │          │          │
│ payments.events       │          │    ✅    │  ❌(2)   │  ❌(2)   │  ❌(2)   │          │          │          │
│ payment.events        │          │          │    ✅    │    ✅    │    ✅    │          │          │          │
│ inventory.events      │          │          │          │          │    ✅    │          │          │          │
│ fulfillment.events    │          │    ✅    │          │          │    ✅    │          │          │    ✅    │
│ catalog.events        │          │          │          │          │  ❌(3)   │    ✅    │    ✅    │          │
│ identity.events       │    ✅    │    ✅    │          │          │    ✅    │          │          │          │
│ rider.events          │          │          │          │          │    ✅    │          │          │          │
│ rider.location.updates│          │          │          │          │          │          │          │          │
│ routing-eta listens → │          │          │          │          │          │          │          │          │
└───────────────────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘

(1) These services subscribe to "order.events" (singular) — MAY be a different topic
(2) These services subscribe to "payment.events" (singular) — MAY be a different topic
(3) Audit subscribes to "catalog.events" in the code but it's listed in the topic array — verify
```

---

## Appendix B: REST Client Dependency Map

```
checkout-orchestrator ──REST──→ cart-service
                      ──REST──→ pricing-service
                      ──REST──→ inventory-service
                      ──REST──→ payment-service
                      ──REST──→ order-service

fulfillment-service   ──REST──→ order-service      (status updates)
                      ──REST──→ payment-service     (refunds)
                      ──REST──→ inventory-service   (confirm/cancel)

notification-service  ──REST──→ identity-service    (user lookup)
                      ──REST──→ order-service       (order lookup)

order-service         ──REST──→ payment-service     (via Temporal activities)
                      ──REST──→ inventory-service   (via Temporal activities)
                      ──REST──→ cart-service        (fetch cart)

Total sync REST dependencies: 14 cross-service calls
Circuit breakers configured:  0
```

---

## Appendix C: Event Schema Field Comparison (Money Fields)

| Schema | Field | Type | Consistent with Proto? |
|--------|-------|------|----------------------|
| `OrderPlaced.v1.json` | `totalAmount` | `number` | ❌ Should be integer (cents) |
| `OrderPlaced.v1.json` | `unitPrice` | `number` | ❌ Should be integer (cents) |
| `PaymentCaptured.v1.json` | `amount` | `number` | ❌ Should be integer (cents) |
| `PaymentRefunded.v1.json` | `amountCents` | `integer` | ✅ |
| `StockReserved.v1.json` | (no money field) | — | N/A |
| `LowStockAlert.v1.json` | (no money field) | — | N/A |
| Proto `Money` | `amount_minor` | `int64` | ✅ Reference |

> **Action**: Migrate all JSON Schema money fields to `integer` type representing minor units (paise/cents). Create v2 schemas if needed.
