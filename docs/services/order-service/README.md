# Order Service

## Overview

The order-service is the source of truth for all order data in InstaCommerce. It manages the complete order lifecycle from creation through delivery, maintaining immutable order history, status transitions, and publishing reliable order events for downstream systems. This is a Tier 1 critical service in the money path cluster.

**Service Ownership**: Platform Team - Money Path Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8085
**Status**: Tier 1 Critical (money path)
**Database**: PostgreSQL 15+ (primary ordering system)

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month)
- **P99 Latency**:
  - Reads: < 500ms (GET /orders/{id}, GET /orders)
  - Writes: < 2s (POST /orders, PUT /orders/{id}/cancel)
- **Error Rate**: < 0.1% (invalid input errors excluded)
- **Idempotency**: 100% (no double-billing via idempotency keys)
- **Max Throughput**: 5,000 orders/minute (at 3 replicas, each ~1,700/min capacity)

## Key Responsibilities

1. **Order Persistence**: Accept orders from checkout-orchestrator via Temporal activity; persist atomically to PostgreSQL
2. **Status Lifecycle Management**: Maintain order status transitions (PENDING → CONFIRMED → PICKING → PICKED → IN_TRANSIT → DELIVERED)
3. **Status History Tracking**: Immutable audit log of all transitions with timestamps and audit context
4. **Order Queries**: Expose order details API for customers (filtered by user_id) and admins; support pagination and filtering
5. **Cancellation Support**: Allow customer-initiated cancellation before fulfillment picks items (soft constraint, coordination with fulfillment-service)
6. **Event Publishing**: Transactional outbox pattern ensures orders.events Kafka messages are never lost
7. **Reconciliation Integration**: Expose order data to reconciliation-engine for financial audits
8. **User Isolation**: Enforce user_id from JWT token; prevent cross-user data access

## Deployment

### GKE Deployment
- **Namespace**: money-path
- **Replicas**: 3 (HA, active-active across zones)
- **CPU Request/Limit**: 500m / 1500m
- **Memory Request/Limit**: 512Mi / 1Gi
- **Startup Probe**: 10s initial delay, 3 failure threshold (Flyway migrations)
- **Readiness Probe**: `/actuator/health/ready` (checks DB + Kafka)

### Database
- **Name**: `orders` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V11) - auto-applied on startup
- **Connection Pool**: HikariCP 20 connections, 5 min idle timeout
- **Failover**: Read replicas in standby zones (via cloudsql-proxy)

### Network
- **Service Account**: `order-service@project.iam.gserviceaccount.com`
- **Ingress**: Through api-gateway (TLS 1.3 termination)
- **Egress**: To PostgreSQL (cloudsql-proxy), Kafka brokers, identity-service (JWT validation)
- **NetworkPolicy**: Deny-default; allow from checkout-orchestrator, fulfillment-service, admin-gateway

## Architecture

### System Context

```
┌───────────────────────────────────────────────────────┐
│                   Order Service                        │
│              (Order Lifecycle Authority)               │
└────────┬────────────────────────┬────────────────────┘
         │                        │
    ┌────▼────┐          ┌────────▼────────┐
    │ Upstream │          │   Downstream    │
    └────┬────┘          └────────┬────────┘
         │                        │
   • checkout-             • fulfillment-service
     orchestrator          • notification-service
   • admin-gateway         • reconciliation-engine
                           • search-service
                           • analytics platform
```

### Data Flow (Order Creation)

```
1. checkout-orchestrator → Temporal Activity
                           ↓
2. order-service → POST /orders (idempotent)
                   ↓
3. Orders DB ← Persist + Outbox Entry
                   ↓
4. CDC Relay ← Detect outbox entry
                   ↓
5. Kafka ← PublishOrderCreated → [fulfillment, notification, analytics]
```

### State Transitions

```
PENDING (order created, not confirmed)
   ↓
CONFIRMED (payment confirmed)
   ↓
PICKING (fulfillment started picking items)
   ↓
PICKED (all items picked)
   ↓
IN_TRANSIT (rider in delivery)
   ↓
DELIVERED (order received by customer)

CANCELLED (user cancellation before PICKING) ← Can happen from any state pre-picking
```

## Integrations

### Synchronous Calls (with Circuit Breakers)
| Service | Endpoint | Timeout | Purpose | Retry |
|---------|----------|---------|---------|-------|
| checkout-orchestrator | Temporal Activity (gRPC) | 30s | Order creation from checkout saga | None (async via Temporal) |
| fulfillment-service | http://fulfillment-service:8080/fulfillment | 5s | Query fulfillment status for order | 3 retries, exponential backoff |
| inventory-service | http://inventory-service:8083/inventory | 5s | Verify stock during order creation | 2 retries, 500ms backoff |
| payment-service | http://payment-service:8080/payments | 5s | Query payment status during reconciliation | 2 retries |

### Asynchronous Event Channels
| Topic | Direction | Events | Format |
|-------|-----------|--------|--------|
| orders.events | Publish | OrderCreated, OrderStatusChanged, OrderCancelled | Kafka (Debezium envelope via outbox) |
| fulfillment.events | Consume | FulfillmentStarted, ItemPicked, DeliveryAssigned, DeliveryCompleted | Kafka |

### Outbox Pattern (CDC)
- **Table**: `order_service.outbox_events`
- **CDC Tool**: Debezium PostgreSQL connector (listening on WAL)
- **Relay**: outbox-relay-service consumes and publishes to Kafka
- **Guarantee**: Exactly-once semantics via idempotent keys

## Data Model

### Core Entities

```
Orders Table:
├─ id (UUID, PK)
├─ user_id (UUID, FK → identity-service)
├─ checkout_id (VARCHAR, UNIQUE - for idempotency)
├─ status (ENUM: PENDING, CONFIRMED, PICKING, PICKED, IN_TRANSIT, DELIVERED, CANCELLED)
├─ subtotal_cents (INT)
├─ tax_cents (INT)
├─ delivery_fee_cents (INT)
├─ total_cents (INT)
├─ delivery_address_encrypted (TEXT - encrypted PII)
├─ delivery_instructions (TEXT)
├─ created_at (TIMESTAMP, indexed)
├─ updated_at (TIMESTAMP)
└─ version (INT - optimistic lock)

Order Items Table:
├─ id (UUID, PK)
├─ order_id (UUID, FK → Orders)
├─ product_id (UUID)
├─ quantity (INT)
├─ unit_price_cents (INT)
├─ subtotal_cents (INT)
└─ created_at (TIMESTAMP)

Order Status History Table:
├─ id (UUID, PK)
├─ order_id (UUID, FK → Orders)
├─ old_status (ENUM)
├─ new_status (ENUM)
├─ transitioned_by (VARCHAR: "system" or user_id)
├─ reason (TEXT)
├─ created_at (TIMESTAMP)
└─ (Immutable audit log)

Outbox Events Table (CDC):
├─ id (BIGSERIAL)
├─ order_id (UUID, FK)
├─ event_type (VARCHAR: OrderCreated, OrderStatusChanged, OrderCancelled)
├─ event_payload (JSONB)
├─ created_at (TIMESTAMP)
└─ sent (BOOLEAN - polled by CDC relay)
```

### Relationships

```
Users (identity-service)
    ↓ (1:many)
Orders
    ↓ (1:many)
Order Items
    ↓ (0:many) [for each item]
Fulfillment Tasks (fulfillment-service)
```

## API Documentation

### Create Order
**POST /orders**
```bash
Authorization: Bearer {JWT_TOKEN}

Request:
{
  "checkoutId": "checkout-uuid",  // Idempotency key
  "items": [
    {
      "productId": "prod-uuid",
      "quantity": 2,
      "unitPriceCents": 1500
    }
  ],
  "subtotalCents": 3000,
  "taxCents": 300,
  "deliveryFeeCents": 200,
  "totalCents": 3500,
  "deliveryAddress": "123 Main St, City",
  "deliveryInstructions": "Leave at door"
}

Response (201 Created):
{
  "id": "order-uuid",
  "userId": "user-uuid",
  "checkoutId": "checkout-uuid",
  "status": "PENDING",
  "items": [...],
  "totalCents": 3500,
  "createdAt": "2025-03-21T10:00:00Z"
}

Response (409 Conflict - Idempotent duplicate):
{
  "error": "Order already exists for checkout",
  "orderId": "order-uuid",
  "checkoutId": "checkout-uuid"
}
```

### Get Order
**GET /orders/{orderId}**
```bash
Authorization: Bearer {JWT_TOKEN}

Response (200):
{
  "id": "order-uuid",
  "userId": "user-uuid",
  "status": "PICKING",
  "items": [...],
  "totalCents": 3500,
  "deliveryAddress": "...",
  "statusHistory": [
    {"status": "PENDING", "transitionedAt": "2025-03-21T10:00:00Z", "reason": "Order created"},
    {"status": "CONFIRMED", "transitionedAt": "2025-03-21T10:01:00Z", "reason": "Payment confirmed"},
    {"status": "PICKING", "transitionedAt": "2025-03-21T10:02:00Z", "reason": "Fulfillment started"}
  ]
}
```

### Cancel Order
**PUT /orders/{orderId}/cancel**
```bash
Authorization: Bearer {JWT_TOKEN}

Request:
{
  "reason": "Changed my mind"  // Optional
}

Response (200):
{
  "id": "order-uuid",
  "status": "CANCELLED",
  "cancelledAt": "2025-03-21T10:15:00Z",
  "refundInitiated": true
}

Response (409):
{
  "error": "Cannot cancel order in PICKED status",
  "currentStatus": "PICKED"
}
```

### List User Orders
**GET /orders?status=DELIVERED&limit=10&offset=0**
```bash
Authorization: Bearer {JWT_TOKEN}

Response (200):
{
  "orders": [...],
  "total": 42,
  "limit": 10,
  "offset": 0,
  "nextOffset": 10
}
```

## Error Handling & Resilience

### Circuit Breaker Strategy
```yaml
resilience4j:
  circuitbreaker:
    instances:
      fulfillmentService:
        registerHealthIndicator: true
        failureRateThreshold: 50%
        slowCallRateThreshold: 50%
        slowCallDurationThreshold: 2s
        waitDurationInOpenState: 60s
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
      inventoryService:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s
  retry:
    instances:
      fulfillmentService:
        maxAttempts: 3
        waitDuration: 500ms
        retryExceptions:
          - java.net.SocketTimeoutException
          - java.net.ConnectException
  timelimiter:
    instances:
      fulfillmentService:
        timeoutDuration: 5s
```

### Failure Scenarios & Recovery

**Scenario 1: Order Creation Fails Mid-Saga**
- Circuit breaker on checkout-orchestrator side prevents orders in inconsistent state
- Checkout can be retried with same idempotency key
- Recovery: Retry checkout; order-service returns same order via idempotency check

**Scenario 2: Outbox CDC Relay Fails**
- Order persisted to DB but event not published to Kafka
- CDC relay recovers and polls `outbox_events` table for unsent=true entries
- Recovery: Auto-retry via relay; eventual consistency (typically <30s delay)

**Scenario 3: Fulfillment Service Unreachable**
- When querying fulfillment status, circuit breaker opens after 50% failure rate
- Fast-fail to prevent cascade; retry with exponential backoff
- Recovery: Monitor fulfillment-service health; circuit breaker auto-resets after 60s

**Scenario 4: Database Connection Pool Exhausted**
- HikariCP queue fills; new requests timeout
- Recovery: Scale replicas (more connection pools) or reduce per-request DB operations

### Idempotency Implementation
- **Key**: `checkout_id` from checkout-orchestrator (UNIQUE constraint)
- **Client Responsibility**: Must retry with same checkout_id on transient failures
- **Server Guarantee**: Second request with same checkout_id returns cached response (409 Conflict)
- **Deduplication**: Database constraint prevents duplicate orders at DB level

## Configuration

### Environment Variables
```env
# Server
SERVER_PORT=8085
SPRING_PROFILES_ACTIVE=gcp

# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/orders
SPRING_DATASOURCE_USERNAME=${DB_USER}
SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
SPRING_DATASOURCE_HIKARI_POOL_SIZE=20
SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES=5

# JWT
JWT_ISSUER=instacommerce-identity
JWT_PUBLIC_KEY=${JWT_PUBLIC_KEY_PEM}

# Kafka
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka-broker-1:9092,kafka-broker-2:9092
SPRING_KAFKA_PRODUCER_COMPRESSION_TYPE=snappy
SPRING_KAFKA_PRODUCER_BATCH_SIZE=16384

# Tracing
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
OTEL_SDK_DISABLED_BY_DEFAULT=false
OTEL_TRACES_SAMPLER=always_on

# Feature Flags
CONFIG_SERVICE_URL=http://config-feature-flag-service:8095
```

### application.yml
```yaml
server:
  port: 8085
  compression:
    enabled: true
    min-response-size: 1024

spring:
  jpa:
    hibernate:
      ddl-auto: none  # Use Flyway for migrations
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc:
          batch_size: 20
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=5000,expireAfterWrite=5m

order-service:
  idempotency:
    ttl-minutes: 30
    cleanup-batch-size: 1000
  cancellation:
    allowed-before-status: PICKING
    compensation-timeout-seconds: 120
```

## Monitoring & Observability

### Key Metrics

| Metric | Type | Labels | Alert Threshold |
|--------|------|--------|-----------------|
| `order_creation_latency_ms` | Histogram | p50, p95, p99 | p99 > 2000ms |
| `order_creation_total` | Counter | status (success/failure) | N/A |
| `order_cancellation_total` | Counter | reason | N/A |
| `orders_status_transitions_total` | Counter | from_status, to_status | N/A |
| `outbox_events_pending_total` | Gauge | (should be ~0) | > 100 for >5min |
| `order_query_latency_ms` | Histogram | endpoint | p99 > 500ms |
| `db_connection_pool_active` | Gauge | (monitor for saturation) | > 18/20 |
| `jvm_memory_usage_bytes` | Gauge | (heap usage) | > 80% |

### Alert Rules

```yaml
alerts:
  - name: OrderServiceHighLatency
    condition: order_creation_latency_ms{quantile="0.99"} > 2000
    duration: 5m
    severity: SEV-1
    action: Page on-call engineer

  - name: OrderCreationErrorRate
    condition: rate(order_creation_total{status="failure"}[5m]) > 0.001
    duration: 5m
    severity: SEV-2
    action: Alert to Slack channel

  - name: OutboxEventBacklog
    condition: outbox_events_pending_total > 100
    duration: 10m
    severity: SEV-2
    action: Check CDC relay health

  - name: DatabaseConnectionPoolExhaustion
    condition: db_connection_pool_active / 20 > 0.9
    duration: 2m
    severity: SEV-1
    action: Page on-call, prepare to scale
```

### Logging

- **INFO**: Order creation/updates, status transitions (audit trail)
- **WARN**: Idempotency conflicts, non-critical failures, fallback activations
- **ERROR**: Database errors, circuit breaker open events, unhandled exceptions
- **DEBUG**: Outbox polling, CDC events (high volume - disabled in production)

### Tracing

- **Sampler**: Always-on (1.0) for all order operations
- **Spans**: Order creation, DB transactions, Kafka publishes, circuit breaker calls
- **Correlation ID**: Propagated from checkout-orchestrator through all services

## Security Considerations

### Authentication & Authorization
- **Auth**: JWT RS256 validation via identity-service JWKS
- **User Isolation**: `user_id` claim from JWT must match order.user_id for read/write operations
- **Admin Access**: Users with `admin` role can view any order (via X-Admin-Access header)
- **Token Caching**: JWKS cached with 5-minute TTL

### Data Protection
- **Encryption at Rest**: Delivery address encrypted with KMS-managed key (GCP Cloud KMS)
- **Encryption in Transit**: TLS 1.3 for all network communications
- **Audit Logging**: Order modifications logged to audit-trail-service for compliance

### Known Security Risks
1. **Token Compromise**: If admin JWT stolen, attacker can view all orders → Mitigated by short expiry (1 hour)
2. **SQL Injection**: Parameterized queries used; risk minimal → Mitigated by ORM (JPA)
3. **Double-Billing**: Idempotency key prevents duplicates → Guaranteed by DB constraint

### Compliance
- **PCI DSS**: Sensitive order data encrypted; audit trails maintained
- **GDPR**: User erasure coordinated with identity-service; order data retained for compliance periods

## Troubleshooting

### Issue: 401 Unauthorized on order creation
**Possible Causes**:
1. JWT token expired
2. JWT issuer mismatch
3. User mismatch (JWT user_id ≠ order.user_id in request)

**Diagnosis**:
```bash
# Check logs for JWT validation errors
kubectl logs -n money-path deploy/order-service | grep "JWT\|unauthorized"

# Verify token claims
JWT_TOKEN=$(curl -X POST http://identity-service:8080/jwt/issue -d '...')
echo $JWT_TOKEN | jq -R 'split(".")[1] | @base64d | fromjson'
```

**Resolution**: Issue new token from identity-service

### Issue: 409 Conflict on order creation (idempotency)
**Diagnosis**:
```bash
# Check if order already exists
curl -H "Authorization: Bearer $JWT" \
  http://localhost:8085/orders \
  | jq '.[] | select(.checkoutId == "checkout-abc")'
```

**Resolution**: Idempotency working correctly; client should not retry with new checkout_id

### Issue: Order stuck in PENDING status
**Possible Causes**:
1. Payment confirmation event not received
2. Fulfillment service unreachable
3. Kafka consumer lag

**Diagnosis**:
```bash
# Check Kafka consumer lag
kafka-consumer-groups --describe --group order-service

# Check payment confirmation events
kafka-console-consumer --topic payments.events \
  --from-beginning | grep "PaymentConfirmed"
```

**Resolution**: Manually trigger status update; check downstream service health

### Issue: High database query latency
**Possible Causes**:
1. Connection pool exhausted
2. Missing indexes
3. Full table scans

**Diagnosis**:
```bash
# Check connection pool usage
curl http://localhost:8085/actuator/metrics/hikaricp.connections.active

# Check database locks
SELECT * FROM pg_locks WHERE database = (SELECT oid FROM pg_database WHERE datname = 'orders');
```

**Resolution**: Scale replicas (more pools) or add indexes on frequently-used columns

## Operational Runbooks

See [runbook.md](runbook.md) for:
- Pre-deployment checklist
- Deployment procedures (rolling, blue-green, rollback)
- Scaling operations
- Database maintenance
- Incident response playbooks

## Related Documentation

- **ADR-006**: Internal Service Authentication
- **ADR-014**: Reconciliation Authority Model (for audit trail)
- **Wave 34**: Admin-Gateway authentication
- **Wave 37**: Integration tests (74+ test scenarios)
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Database Schema (ERD)](diagrams/erd.md)
- [Request/Response Flows](diagrams/flowchart.md)
- [Sequence Diagrams](diagrams/sequence.md)
- [REST API Contract](implementation/api.md)
- [Kafka Events](implementation/events.md)
- [Database Details](implementation/database.md)
- [Resilience & Retry Logic](implementation/resilience.md)
