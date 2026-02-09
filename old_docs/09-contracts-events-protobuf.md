# 09 — Contracts, Events & Protobuf Definitions

> **Scope**: Canonical event schemas, gRPC proto files, Kafka topic catalog, versioning strategy  
> **Purpose**: Single source of truth for all inter-service communication contracts  
> **Location**: `contracts/` module — shared as a Maven/Gradle dependency by all services

---

## 1. Module Layout

```
contracts/
├── build.gradle.kts
├── src/main/
│   ├── proto/                          # gRPC / Protobuf definitions
│   │   ├── inventory/v1/
│   │   │   └── inventory_service.proto
│   │   ├── catalog/v1/
│   │   │   └── catalog_service.proto
│   │   ├── payment/v1/
│   │   │   └── payment_service.proto
│   │   └── common/v1/
│   │       └── money.proto
│   └── resources/
│       └── schemas/                    # JSON Schemas for Kafka events
│           ├── orders/
│           │   ├── OrderPlaced.v1.json
│           │   ├── OrderPacked.v1.json
│           │   ├── OrderDispatched.v1.json
│           │   ├── OrderDelivered.v1.json
│           │   ├── OrderCancelled.v1.json
│           │   └── OrderFailed.v1.json
│           ├── payments/
│           │   ├── PaymentAuthorized.v1.json
│           │   ├── PaymentCaptured.v1.json
│           │   ├── PaymentRefunded.v1.json
│           │   └── PaymentFailed.v1.json
│           ├── inventory/
│           │   ├── StockReserved.v1.json
│           │   ├── StockConfirmed.v1.json
│           │   ├── StockReleased.v1.json
│           │   └── LowStockAlert.v1.json
│           ├── fulfillment/
│           │   ├── PickTaskCreated.v1.json
│           │   ├── OrderPacked.v1.json
│           │   ├── RiderAssigned.v1.json
│           │   └── DeliveryCompleted.v1.json
│           └── catalog/
│               ├── ProductCreated.v1.json
│               └── ProductUpdated.v1.json
```

---

## 2. Kafka Topic Catalog

### Naming Convention
```
<domain>.<aggregate>.<event-type>   →   e.g., orders.events
```

| Topic | Producer | Consumers | Key | Partitions | Retention |
|-------|----------|-----------|-----|------------|-----------|
| `orders.events` | order-service (via Debezium) | fulfillment, notification, analytics | orderId | 6 | 7d |
| `payments.events` | payment-service (via Debezium) | order-service, notification | paymentId | 6 | 7d |
| `inventory.events` | inventory-service (via Debezium) | catalog-service (stock display) | productId | 6 | 3d |
| `fulfillment.events` | fulfillment-service (via Debezium) | order-service, notification | orderId | 6 | 3d |
| `catalog.events` | catalog-service (via Debezium) | inventory-service (new products) | productId | 3 | 3d |
| `notifications.dlq` | notification-service | ops/manual | notificationId | 1 | 30d |

### Outbox Table Schema (shared pattern across all services)

```sql
-- Every service that publishes events has this table
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(100)  NOT NULL,   -- e.g., 'Order', 'Payment'
    aggregate_id    VARCHAR(100)  NOT NULL,   -- Business key for Kafka partitioning
    event_type      VARCHAR(100)  NOT NULL,   -- e.g., 'OrderPlaced'
    payload         JSONB         NOT NULL,   -- Full event body
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
```

### Debezium Connector Config (template)

```json
{
  "name": "${SERVICE_NAME}-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "${DB_HOST}",
    "database.port": "5432",
    "database.user": "${DB_USER}",
    "database.password": "${DB_PASSWORD}",
    "database.dbname": "${DB_NAME}",
    "topic.prefix": "${DOMAIN}",
    "table.include.list": "public.outbox_events",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.topic.replacement": "${DOMAIN}.events",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.fields.additional.placement": "aggregate_type:header:aggregateType",
    "transforms.outbox.route.tombstone.on.empty.payload": "true"
  }
}
```

---

## 3. Event Schemas (JSON)

### Common Envelope

Every Kafka event has this envelope structure (set by Debezium outbox transform):

```json
{
  "headers": {
    "id": "uuid",
    "aggregateType": "Order",
    "eventType": "OrderPlaced"
  },
  "key": "order-uuid",
  "payload": { ... }
}
```

### OrderPlaced.v1

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderPlaced",
  "type": "object",
  "required": ["orderId", "userId", "items", "totalAmount", "currency", "shippingAddress", "placedAt"],
  "properties": {
    "orderId":         { "type": "string", "format": "uuid" },
    "userId":          { "type": "string", "format": "uuid" },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["productId", "productName", "quantity", "unitPrice"],
        "properties": {
          "productId":    { "type": "string", "format": "uuid" },
          "productName":  { "type": "string" },
          "sku":          { "type": "string" },
          "quantity":     { "type": "integer", "minimum": 1 },
          "unitPrice":    { "type": "number", "minimum": 0 }
        }
      }
    },
    "totalAmount":     { "type": "number", "minimum": 0 },
    "currency":        { "type": "string", "enum": ["INR", "USD"] },
    "shippingAddress": {
      "type": "object",
      "required": ["line1", "city", "state", "postalCode"],
      "properties": {
        "line1":      { "type": "string" },
        "line2":      { "type": "string" },
        "city":       { "type": "string" },
        "state":      { "type": "string" },
        "postalCode": { "type": "string" }
      }
    },
    "placedAt":        { "type": "string", "format": "date-time" }
  }
}
```

### OrderDelivered.v1

```json
{
  "title": "OrderDelivered",
  "type": "object",
  "required": ["orderId", "userId", "deliveredAt", "riderId"],
  "properties": {
    "orderId":     { "type": "string", "format": "uuid" },
    "userId":      { "type": "string", "format": "uuid" },
    "riderId":     { "type": "string", "format": "uuid" },
    "deliveredAt": { "type": "string", "format": "date-time" },
    "deliveryDurationMinutes": { "type": "integer" }
  }
}
```

### PaymentCaptured.v1

```json
{
  "title": "PaymentCaptured",
  "type": "object",
  "required": ["paymentId", "orderId", "amount", "currency", "pspReference", "capturedAt"],
  "properties": {
    "paymentId":    { "type": "string", "format": "uuid" },
    "orderId":      { "type": "string", "format": "uuid" },
    "amount":       { "type": "number", "minimum": 0 },
    "currency":     { "type": "string", "enum": ["INR", "USD"] },
    "pspReference": { "type": "string" },
    "capturedAt":   { "type": "string", "format": "date-time" }
  }
}
```

### PaymentRefunded.v1

```json
{
  "title": "PaymentRefunded",
  "type": "object",
  "required": ["refundId", "paymentId", "orderId", "amountCents", "currency"],
  "properties": {
    "refundId":     { "type": "string", "format": "uuid" },
    "paymentId":    { "type": "string", "format": "uuid" },
    "orderId":      { "type": "string", "format": "uuid" },
    "amountCents":  { "type": "integer", "minimum": 0 },
    "currency":     { "type": "string" },
    "reason":       { "type": "string" },
    "refundedAt":   { "type": "string", "format": "date-time" }
  }
}
```

### StockReserved.v1

```json
{
  "title": "StockReserved",
  "type": "object",
  "required": ["reservationId", "orderId", "items", "reservedAt", "expiresAt"],
  "properties": {
    "reservationId": { "type": "string", "format": "uuid" },
    "orderId":       { "type": "string", "format": "uuid" },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "productId": { "type": "string", "format": "uuid" },
          "quantity":  { "type": "integer", "minimum": 1 }
        }
      }
    },
    "reservedAt": { "type": "string", "format": "date-time" },
    "expiresAt":  { "type": "string", "format": "date-time" }
  }
}
```

### LowStockAlert.v1

```json
{
  "title": "LowStockAlert",
  "type": "object",
  "required": ["productId", "warehouseId", "currentQuantity", "threshold", "detectedAt"],
  "properties": {
    "productId":       { "type": "string", "format": "uuid" },
    "warehouseId":     { "type": "string", "format": "uuid" },
    "sku":             { "type": "string" },
    "currentQuantity": { "type": "integer" },
    "threshold":       { "type": "integer" },
    "detectedAt":      { "type": "string", "format": "date-time" }
  }
}
```

---

## 4. gRPC Proto Definitions

### common/v1/money.proto

```protobuf
syntax = "proto3";
package common.v1;

option java_package = "com.instacommerce.contracts.common.v1";
option java_multiple_files = true;

message Money {
  int64  amount_minor = 1;   // Amount in smallest currency unit (paise for INR)
  string currency     = 2;   // ISO 4217 currency code
}
```

### inventory/v1/inventory_service.proto

```protobuf
syntax = "proto3";
package inventory.v1;

option java_package = "com.instacommerce.contracts.inventory.v1";
option java_multiple_files = true;

import "common/v1/money.proto";

service InventoryService {
  // Reserve stock for an order — all-or-nothing
  rpc ReserveStock(ReserveStockRequest) returns (ReserveStockResponse);
  
  // Confirm a previous reservation (after payment capture)
  rpc ConfirmReservation(ConfirmReservationRequest) returns (ConfirmReservationResponse);
  
  // Cancel a reservation (saga compensation)
  rpc CancelReservation(CancelReservationRequest) returns (CancelReservationResponse);
  
  // Check stock availability (non-blocking read)
  rpc CheckAvailability(CheckAvailabilityRequest) returns (CheckAvailabilityResponse);
}

message ReserveStockRequest {
  string idempotency_key = 1;          // Unique per checkout attempt
  string order_id        = 2;
  repeated StockLineItem items = 3;
}

message StockLineItem {
  string product_id = 1;
  int32  quantity   = 2;
}

message ReserveStockResponse {
  string reservation_id = 1;
  bool   success        = 2;
  string failure_reason = 3;           // Set if success=false (e.g., "INSUFFICIENT_STOCK:product-uuid")
  repeated StockLineResult line_results = 4;
}

message StockLineResult {
  string product_id      = 1;
  int32  requested       = 2;
  int32  available       = 3;
  bool   reserved        = 4;
}

message ConfirmReservationRequest {
  string reservation_id = 1;
}

message ConfirmReservationResponse {
  bool confirmed = 1;
}

message CancelReservationRequest {
  string reservation_id = 1;
}

message CancelReservationResponse {
  bool cancelled = 1;
}

message CheckAvailabilityRequest {
  repeated string product_ids = 1;
}

message CheckAvailabilityResponse {
  repeated ProductAvailability items = 1;
}

message ProductAvailability {
  string product_id       = 1;
  int32  available_qty    = 2;
  int32  reserved_qty     = 3;
}
```

### payment/v1/payment_service.proto

```protobuf
syntax = "proto3";
package payment.v1;

option java_package = "com.instacommerce.contracts.payment.v1";
option java_multiple_files = true;

import "common/v1/money.proto";

service PaymentService {
  rpc AuthorizePayment(AuthorizeRequest) returns (AuthorizeResponse);
  rpc CapturePayment(CaptureRequest) returns (CaptureResponse);
  rpc VoidAuthorization(VoidRequest) returns (VoidResponse);
  rpc RefundPayment(RefundRequest) returns (RefundResponse);
}

message AuthorizeRequest {
  string idempotency_key   = 1;
  string order_id          = 2;
  string user_id           = 3;
  common.v1.Money amount   = 4;
  string payment_method_id = 5;    // Stripe PaymentMethod ID (pm_xxx)
}

message AuthorizeResponse {
  string payment_id   = 1;
  bool   authorized   = 2;
  string decline_code = 3;         // Set if authorized=false
}

message CaptureRequest {
  string payment_id = 1;
}

message CaptureResponse {
  bool   captured      = 1;
  string psp_reference = 2;
}

message VoidRequest {
  string payment_id = 1;
}

message VoidResponse {
  bool voided = 1;
}

message RefundRequest {
  string payment_id  = 1;
  common.v1.Money amount = 2;      // Partial or full
  string reason      = 3;
}

message RefundResponse {
  string refund_id = 1;
  bool   refunded  = 2;
}
```

### catalog/v1/catalog_service.proto

```protobuf
syntax = "proto3";
package catalog.v1;

option java_package = "com.instacommerce.contracts.catalog.v1";
option java_multiple_files = true;

import "common/v1/money.proto";

service CatalogService {
  // Used by order-service to snapshot product details at time of order
  rpc GetProductDetails(GetProductDetailsRequest) returns (GetProductDetailsResponse);
  
  // Used by fulfillment to get pick list details
  rpc GetProductsBatch(GetProductsBatchRequest) returns (GetProductsBatchResponse);
}

message GetProductDetailsRequest {
  string product_id = 1;
}

message GetProductDetailsResponse {
  string product_id   = 1;
  string name         = 2;
  string sku          = 3;
  common.v1.Money price = 4;
  string image_url    = 5;
  string category     = 6;
}

message GetProductsBatchRequest {
  repeated string product_ids = 1;
}

message GetProductsBatchResponse {
  repeated GetProductDetailsResponse products = 1;
}
```

---

## 5. Gradle Build for Contracts Module

```kotlin
// contracts/build.gradle.kts
plugins {
    java
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    implementation("io.grpc:grpc-protobuf:1.62.2")
    implementation("io.grpc:grpc-stub:1.62.2")
    implementation("io.grpc:grpc-netty-shaded:1.62.2")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}
```

Each service includes this as a dependency:

```kotlin
// services/order-service/build.gradle.kts
dependencies {
    implementation(project(":contracts"))
}
```

---

## 6. Schema Versioning Strategy

### Rules
1. **Backward-compatible changes only** for existing versions:
   - Adding optional fields ✅
   - Removing required fields ❌ (create v2)
   - Changing field types ❌ (create v2)

2. **Version in file name**: `OrderPlaced.v1.json` → `OrderPlaced.v2.json`

3. **Proto versioning**: Package path includes version (`inventory.v1`, `inventory.v2`)

4. **Consumers MUST ignore unknown fields** — use `@JsonIgnoreProperties(ignoreUnknown = true)`

5. **Producer MUST populate ALL required fields** — contract tests verify this

### Consumer-Driven Contract Tests

```java
@SpringBootTest
class OrderEventContractTest {
    
    @Test
    void orderPlaced_event_matches_schema() throws Exception {
        String schemaPath = "schemas/orders/OrderPlaced.v1.json";
        String sampleEvent = """
            {
              "orderId": "123e4567-e89b-12d3-a456-426614174000",
              "userId": "123e4567-e89b-12d3-a456-426614174001",
              "items": [{
                "productId": "123e4567-e89b-12d3-a456-426614174002",
                "productName": "Fresh Milk 1L",
                "sku": "MILK-001",
                "quantity": 2,
                "unitPrice": 55.00
              }],
              "totalAmount": 110.00,
              "currency": "INR",
              "shippingAddress": {
                "line1": "123 Main St",
                "city": "Mumbai",
                "state": "MH",
                "postalCode": "400001"
              },
              "placedAt": "2025-01-15T10:30:00Z"
            }
            """;
        
        JsonSchema schema = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V7)
            .getSchema(getClass().getClassLoader().getResourceAsStream(schemaPath));
        
        Set<ValidationMessage> errors = schema.validate(
            new ObjectMapper().readTree(sampleEvent)
        );
        
        assertThat(errors).isEmpty();
    }
}
```

---

## 7. Event Java DTOs (Generated or Hand-Written)

```java
// Recommended: hand-write event DTOs in contracts module for clarity

@Data @Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderPlacedEvent {
    private UUID orderId;
    private UUID userId;
    private List<OrderItemSnapshot> items;
    private BigDecimal totalAmount;
    private String currency;
    private ShippingAddress shippingAddress;
    private Instant placedAt;
    
    @Data @Builder
    public static class OrderItemSnapshot {
        private UUID productId;
        private String productName;
        private String sku;
        private int quantity;
        private BigDecimal unitPrice;
    }
    
    @Data @Builder
    public static class ShippingAddress {
        private String line1;
        private String line2;
        private String city;
        private String state;
        private String postalCode;
    }
}
```

---

## 8. Agent Instructions (CRITICAL)

### MUST DO
1. ALL inter-service events go through the outbox table + Debezium — NEVER publish directly to Kafka from application code.
2. ALL gRPC calls use the generated stubs from the `contracts` module — NEVER hand-write stubs.
3. Consumer DTOs MUST have `@JsonIgnoreProperties(ignoreUnknown = true)`.
4. Proto files use `java_multiple_files = true` so each message gets its own class.
5. Kafka message key = aggregate ID (ensures ordering per entity).
6. JSON Schema files MUST list ALL required fields — consumers validate against these.

### MUST NOT DO
1. Do NOT put business logic in the contracts module — it is DTOs, protos, and schemas only.
2. Do NOT use Avro — we use JSON + JSON Schema for simplicity in MVP.
3. Do NOT change field names or types in existing v1 schemas — create v2 instead.
4. Do NOT use Kafka headers for business data — only for metadata (eventType, aggregateType).

### DEFAULTS
- Kafka serialization: JSON (Jackson)
- Proto compiler: protoc 3.25.x with grpc-java plugin
- All amounts in Money proto use `amount_minor` (paise/cents) to avoid float rounding
- Event timestamps: ISO-8601 UTC (`Instant` in Java)
