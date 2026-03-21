# Order Service

## Overview

The order-service is the source of truth for all order data in InstaCommerce. It manages the complete order lifecycle from creation through delivery, maintaining immutable order history, status transitions, and publishing reliable order events for downstream systems. This is a Tier 1 critical service in the money path cluster with extreme availability requirements and strong consistency guarantees.

**Service Ownership**: Platform Team - Money Path Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8085
**Status**: Tier 1 Critical (money path)
**Database**: PostgreSQL 15+ (primary ordering system, immutable audit trail)

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month)
- **P50 Latency**: < 200ms (median read/write)
- **P95 Latency**: < 500ms (read operations)
- **P99 Latency**: < 2s (write operations including outbox flushing)
- **Error Rate**: < 0.1% (invalid input errors excluded)
- **Idempotency**: 100% (no double-billing via idempotency keys)
- **Max Throughput**: 5,000 orders/minute (at 3 replicas, each ~1,700/min capacity)
- **Order Status Accuracy**: 100% (eventual consistency via event sourcing)
- **Event Publishing**: 99.99% (transactional outbox guarantees delivery)

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
- **Liveness Probe**: `/actuator/health/live` (JVM health)
- **Pod Disruption Budget**: minAvailable: 2/3

### Database
- **Name**: `orders` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V11) - auto-applied on startup
- **Connection Pool**: HikariCP 20 connections, 5 min idle timeout
- **Failover**: Read replicas in standby zones (via cloudsql-proxy)
- **Backups**: Daily automated backups, Glacier archival after 30 days

### Network
- **Service Account**: `order-service@project.iam.gserviceaccount.com`
- **Ingress**: Through api-gateway (TLS 1.3 termination)
- **Egress**: To PostgreSQL (cloudsql-proxy), Kafka brokers, identity-service (JWT validation)
- **NetworkPolicy**: Deny-default; allow from checkout-orchestrator, fulfillment-service, admin-gateway

## Architecture

### Order Lifecycle State Machine

```
PENDING (order created, not confirmed)
   ↓
CONFIRMED (payment confirmed)
   ├─ [Payment Failed] → FAILED (payment declined)
   ├─ [Timeout] → EXPIRED (7-day expiry without confirmation)
   ↓
PICKING (fulfillment started picking items)
   ├─ [Out of Stock] → PARTIAL (some items unavailable)
   ├─ [Pick Cancelled] → CANCELLED
   ↓
PICKED (all items picked, packed)
   ↓
IN_TRANSIT (rider assigned, en route)
   ├─ [Delivery Failed] → DELIVERY_FAILED (rider accident, unavailable)
   ├─ [Returned] → RETURNED (customer rejected, return to warehouse)
   ↓
DELIVERED (customer received)
   ↓
COMPLETED ✅
```

### Data Flow (Order Creation)

```
1. checkout-orchestrator → Temporal Activity (calls order-service)
                           ↓
2. order-service → POST /orders (idempotent)
                   ↓
3. Orders DB ← Persist order + Outbox Entry
   (ACID transaction ensures atomicity)
                   ↓
4. CDC Relay ← Detect outbox entry
   (Postgres WAL streaming)
                   ↓
5. Kafka ← PublishOrderCreated → [fulfillment, notification, analytics]
   (at-least-once via CDC)
```

### Order Event Publishing Flow

```
┌─────────────────────────────────────────┐
│ Order Service (CREATE/UPDATE)           │
│ ├─ Update orders table                  │
│ ├─ Insert outbox entry                  │
│ └─ Commit (ACID)                        │
└────────────┬────────────────────────────┘
             │
             ↓ (PostgreSQL WAL)
┌─────────────────────────────────────────┐
│ CDC Relay (Debezium)                    │
│ ├─ Detect outbox insert                 │
│ ├─ Parse Postgres log                   │
│ └─ Emit to Kafka                        │
└────────────┬────────────────────────────┘
             │
             ↓ (Kafka topic: orders.events)
    ┌────────┴────────┬───────────┬───────────┐
    ↓                 ↓           ↓           ↓
  Fulfillment    Notification  Analytics  Reconciliation
  (PickTasks)    (Email/SMS)    (Logging)  (Ledger sync)
```

## Operational Workflows (15+ Use Cases)

**Workflow 1: Happy Path Order Creation**
```
1. Checkout orchestrator sends CreateOrder activity
2. Order service generates order_id (UUID)
3. Inserts order record with status=PENDING
4. Inserts outbox entry (OrderCreated event)
5. Commits transaction (atomically)
6. Returns order_id to checkout orchestrator
7. CDC relay detects outbox entry within 100ms
8. Publishes OrderCreated event to Kafka (fulfillment subscribes)
9. Fulfillment service receives event; creates picking tasks
10. Customer receives order confirmation email (via notification service)
11. Order lifecycle begins
```

**Workflow 2: Order Status Transition (PENDING → CONFIRMED)**
```
1. Payment service captures funds successfully
2. Calls PUT /orders/{orderId}/confirm with payment_confirmation_id
3. Order service validates order still in PENDING state
4. Updates status to CONFIRMED
5. Inserts outbox entry (OrderConfirmed event)
6. Commits transaction
7. Returns 200 OK
8. Outbox relay publishes OrderConfirmed event
9. Downstream services update derived state (search index, analytics)
```

**Workflow 3: Order Cancellation (Pre-Pick)**
```
1. Customer initiates cancellation (before fulfillment picks items)
2. Client calls DELETE /orders/{orderId}/cancel
3. Order service checks current status (must be PENDING or CONFIRMED)
4. If status allows: Update to CANCELLED
5. Insert outbox entry (OrderCancelled event)
6. Commit transaction
7. Outbox relay publishes OrderCancelled event
8. Fulfillment service receives event; cancels picking tasks (if not started)
9. Payment service receives event; issues refund (if payment captured)
10. Notification service sends cancellation confirmation email
11. Order marked as CANCELLED; no further operations allowed
```

**Workflow 4: Order Status Query (Customer)**
```
1. Authenticated user calls GET /orders/{orderId}
2. Order service verifies user_id matches JWT subject (enforced)
3. If mismatch: Return 403 FORBIDDEN
4. If match: Fetch order from DB
5. Return full order details (items, total, status, ETA if in transit)
6. Include status_history array (all transitions with timestamps)
7. Example: status_history = [{status: "PENDING", updated_at: 2025-03-21T10:00:00Z, reason: "created"}, ...]
8. Return 200 with cached ETag header (60s TTL)
9. Client can poll for updates every 30 seconds
```

**Workflow 5: Order Pagination Query (Admin Dashboard)**
```
1. Admin calls GET /orders?page=1&limit=50&status=PENDING&created_from=2025-03-21
2. Order service queries orders table with filters
3. Applied indexes: (created_at DESC, status, user_id)
4. Returns paginated result (50 orders + total count + cursor for next page)
5. Includes aggregated metrics: total_orders=1250, pending_count=320, confirmed_count=930
6. Response includes Link header for pagination (RFC 5988)
7. Admin dashboard renders order list with status indicators
```

**Workflow 6: Order Fulfillment Transition (PENDING → PICKING)**
```
1. Fulfillment service receives OrderCreated event
2. Creates picking tasks for warehouse staff
3. When first item picked: Calls PUT /orders/{orderId}/status with status=PICKING
4. Order service validates transition (PENDING → PICKING allowed)
5. Updates status to PICKING
6. Inserts outbox entry (OrderPickingStarted event)
7. Commit transaction
8. Outbox relay publishes OrderPickingStarted
9. Notification service sends "Your order is being picked" SMS
```

**Workflow 7: Order Delivery Attempt Failure**
```
1. Rider attempts delivery; no one at address; leaves note
2. Rider calls PUT /orders/{orderId}/delivery-failed with reason="NO_ONE_HOME"
3. Order service validates status (must be IN_TRANSIT)
4. Updates status to DELIVERY_FAILED
5. Inserts outbox entry (OrderDeliveryFailed event)
6. Commit transaction
7. Notification service sends "Delivery failed" notification with retry option
8. Order remains in DELIVERY_FAILED state; manual intervention needed
9. Customer can request retry (rider attempts again next day)
10. Admin can force return-to-warehouse or replace rider
```

**Workflow 8: Order Partial Fulfillment (Item Out of Stock)**
```
1. Fulfillment service starts picking order items
2. Item XYZ out of stock; only Y items available (expected Z)
3. Calls PUT /orders/{orderId}/partial-fulfill with items_available=[...]
4. Order service records which items fulfilled, which items pending
5. Inserts outbox entry (OrderPartiallyFulfilled event)
6. Commit transaction
7. Notification service notifies customer: "Item XYZ out of stock; refund issued"
8. Payment service issues partial refund (amount for missing item)
9. Fulfillment ships available items
10. Order can transition to DELIVERED (for available items) or CANCELLED (for out-of-stock items)
```

**Workflow 9: Order Returned by Customer (Post-Delivery)**
```
1. Customer initiates return within 7-day return window
2. Client calls POST /orders/{orderId}/return with items=[...]
3. Order service validates order in DELIVERED state
4. Updates status to RETURNED
5. Inserts outbox entry (OrderReturned event)
6. Commit transaction
7. Fulfillment service receives OrderReturned event; creates return pickup task
8. Rider picks up returned items from customer address
9. Items returned to warehouse; inventory-service updates stock
10. Payment service issues refund (amount for returned items)
11. Notification service confirms refund initiation
```

**Workflow 10: Order Reconciliation Query**
```
1. Reconciliation engine (nightly batch job) calls GET /orders/reconciliation with date range
2. Order service queries orders created/updated between date range
3. Returns order details with payment_id, fulfillment_status, revenue_recognized
4. Reconciliation engine compares:
   - Orders.revenue_recognized vs Payment.captured_amount (should match)
   - Orders.fulfillment_status vs Fulfillment service state (eventual consistency)
5. Discrepancies flagged for manual investigation
6. Example: Order created but payment never captured (stranded order)
```

**Workflow 11-15: Additional Workflows**
- Workflow 11: Order modification (change delivery address pre-pickup)
- Workflow 12: Order expediting (upgrade to express delivery)
- Workflow 13: Order merging (combine two orders to same rider)
- Workflow 14: Order hold (placed on hold by compliance/fraud check)
- Workflow 15: Order batch import (admin bulk order creation)

## Performance Optimization Techniques

**1. Read Replica Strategy**:
- Write operations → Primary PostgreSQL
- Read-heavy queries (GET /orders, dashboards) → Read replicas
- cloudauth-proxy handles failover automatically

**2. Query Result Caching**:
- Cache individual order details (60s TTL) by order_id
- Cache paginated results (30s TTL) by query parameters
- Cache order count aggregations (5m TTL) for dashboard
- Redis-backed caching layer

**3. Index Optimization**:
- Primary key index on order_id (fast point lookups)
- Composite index (user_id, created_at DESC) for user's recent orders
- Index on (status, created_at) for filtering by status
- Index on payment_id for payment reconciliation queries

**4. Connection Pool Tuning**:
- HikariCP: 20 connections (suitable for 1,700 orders/min per pod)
- Max lifetime: 30 minutes (connection rotation)
- Idle timeout: 5 minutes (cleanup stale connections)

**5. Batch Operations**:
- Bulk order inserts (admin import) use batch INSERTs
- Outbox entries batched with orders (single transaction)
- CDC relay batches outbox events before publishing to Kafka

## Monitoring & Alerts (20+ Metrics)

| Metric | Type | Alert Threshold | Description |
|--------|------|---|---|
| `order_create_latency_ms` | Histogram (p50, p95, p99) | p99 > 2000ms | Order creation latency |
| `order_create_success_rate` | Gauge | < 99.9% for 5m | Order creation success rate |
| `order_status_transition_latency_ms` | Histogram | p99 > 1000ms | Status update latency |
| `outbox_entry_age_seconds` | Gauge | > 60s | CDC relay backlog |
| `order_query_latency_ms` | Histogram | p99 > 500ms | Order read latency |
| `order_cancellation_rate` | Gauge | > 5% | Pre-pickup cancellations |
| `order_partial_fulfillment_rate` | Gauge | > 2% | Partial fulfillments (out-of-stock) |
| `order_delivery_failure_rate` | Gauge | > 1% | Delivery failures |
| `order_idempotency_cache_hits` | Counter | N/A | Duplicate order prevention |
| `database_connection_pool_active` | Gauge | > 19/20 | Connection pool pressure |
| `db_query_time_percentile_99` | Gauge | > 1000ms | Database performance degradation |
| `cdc_relay_lag_seconds` | Gauge | > 5s | CDC event publishing delay |
| `outbox_event_publish_failures` | Counter | > 0/hour | Failed Kafka publishes |
| `order_status_consistency_mismatches` | Counter | > 0/day | Eventual consistency violations |
| `order_list_query_timeout_count` | Counter | > 10/hour | Paginated query timeouts |
| `order_user_isolation_violations` | Counter | > 0/day | Security: cross-user data access attempts |
| `flyway_migration_time_seconds` | Gauge | > 30s | Database migration duration |
| `orders_table_size_mb` | Gauge | > 10000MB | Table bloat (archival needed) |
| `order_update_retry_count` | Counter | > 100/hour | Concurrent update conflicts |
| `payment_id_reconciliation_mismatches` | Counter | > 10/day | Payment linkage issues |

### Alerting Rules

```yaml
alerts:
  - name: OrderCreateLatencySpiking
    condition: histogram_quantile(0.99, order_create_latency_ms) > 2000
    duration: 5m
    severity: SEV-2
    action: "Check database CPU, connection pool, Kafka broker lag"

  - name: OutboxEntryAgeHigh
    condition: outbox_entry_age_seconds > 60
    duration: 5m
    severity: SEV-1
    action: "CDC relay backlog detected; check Debezium connector health"

  - name: OrderCancellationRateSpiking
    condition: order_cancellation_rate > 0.05
    duration: 10m
    severity: SEV-3
    action: "High cancellation rate; investigate conversion issues or checkout problems"

  - name: OrderDeliveryFailureSpike
    condition: rate(order_delivery_failure_count[5m]) > 10
    duration: 5m
    severity: SEV-2
    action: "Delivery failures increasing; check rider availability, routing issues"

  - name: UserIsolationViolation
    condition: order_user_isolation_violations > 0
    duration: 1m
    severity: SEV-1
    action: "SECURITY INCIDENT: Unauthorized cross-user order access attempt"

  - name: DatabaseMigrationFailure
    condition: up{instance="order-service"} == 0 AND started_at < (now - 60s)
    duration: 2m
    severity: SEV-1
    action: "Pod failed to start due to Flyway migration error; check DB schema"
```

## Security Considerations

### Threat Mitigations

1. **User Isolation**: JWT sub claim enforced; users can only see own orders
2. **Idempotency Keys**: Prevent duplicate orders via unique constraint
3. **Status Transition Validation**: Only allow valid state transitions
4. **Immutable Audit Trail**: All status changes logged with timestamps
5. **Encryption at Rest**: PostgreSQL encryption via CloudSQL
6. **Encryption in Transit**: TLS 1.3 for all API calls
7. **Role-Based Access Control**: Admin queries require ADMIN role (JWT)

### Known Risks

- **Direct Database Access**: If PostgreSQL credentials compromised, all orders exposed
- **Idempotency Key Guessing**: UUIDs are cryptographically random; bruteforce infeasible
- **CDC Event Tampering**: If Debezium connector compromised, events could be modified
- **Time-of-Check Time-of-Use (TOCTOU)**: Status transitions can race if not serialized
- **Audit Trail Deletion**: Direct DB deletion could remove order history (mitigated by immutable table design)

## Troubleshooting (10+ Scenarios)

### Scenario 1: Order Creation Latency High (P99 > 2000ms)

**Indicators**:
- `order_create_latency_ms` p99 > 2000ms
- Customers report slow checkout
- Checkout orchestrator timeouts on order creation step

**Root Causes**:
1. PostgreSQL write latency high (overloaded)
2. Connection pool exhausted
3. Outbox insert causing contention

**Resolution**:
```bash
# Check database write latency
curl http://order-service:8085/actuator/metrics/db_write_latency_ms | jq '.measurements'

# Check connection pool utilization
curl http://order-service:8085/actuator/metrics/db_connection_pool_active

# Identify slow queries
SELECT query, calls, mean_time FROM pg_stat_statements
WHERE query LIKE '%orders%' ORDER BY mean_time DESC LIMIT 5;

# Check for table locks
SELECT * FROM pg_locks WHERE relation = (SELECT oid FROM pg_class WHERE relname = 'orders');

# Scale order-service replicas
kubectl scale deployment order-service --replicas=5 -n money-path

# Increase connection pool if safe
SPRING_DATASOURCE_HIKARI_POOL_SIZE=30 (restart pods)
```

### Scenario 2: Outbox Entry Age High (CDC Backlog)

**Indicators**:
- `outbox_entry_age_seconds` > 60
- Event publishing delayed
- Fulfillment service not receiving events

**Root Causes**:
1. Debezium CDC connector stuck or behind
2. Kafka broker down or slow
3. PostgreSQL WAL segment not being read

**Resolution**:
```bash
# Check CDC connector status
curl http://debezium-connect:8080/connectors/postgres-cdc/status | jq '.connector.state'

# Check Kafka consumer lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group debezium-postgres --describe

# Restart CDC connector if stuck
curl -X POST http://debezium-connect:8080/connectors/postgres-cdc/restart

# Check PostgreSQL WAL replication slots
SELECT slot_name, active, restart_lsn FROM pg_replication_slots;

# If slot stuck: Advance slot manually (WARNING: may skip events)
SELECT pg_replication_slot_advance('debezium', pg_current_wal_lsn());
```

### Scenario 3: Order User Isolation Violation (Security Incident)

**Indicators**:
- `order_user_isolation_violations` counter > 0
- Logs: "User xyz accessed order belonging to user abc"
- SEV-1 alert firing

**Root Causes**:
1. JWT validation bypass (bug in code)
2. Missing user_id check in query
3. Direct database access without JWT enforcement

**Resolution**:
```bash
# IMMEDIATE: Block affected users, audit access logs
SELECT * FROM order_access_audit_log
WHERE accessed_at > NOW() - INTERVAL '1 hour'
AND user_requesting != user_owning_order
ORDER BY accessed_at DESC;

# Identify root cause
grep -r "user_id" src/controller/OrderController.java | grep -v "JWT\|validate"

# If JWT bypass: Deploy hotfix immediately
# If missing check: Add @PreAuthorize("hasRole('USER')")

# Scope of breach
SELECT COUNT(DISTINCT user_id) FROM orders
WHERE owner_user_id != ${attacking_user_id}
AND updated_at > ${breach_start_time};

# Create incident ticket for compliance team
```

### Scenario 4: Order Status Inconsistency (Race Condition)

**Indicators**:
- `order_status_consistency_mismatches` counter increasing
- Reconciliation engine reporting status mismatches
- Example: Order says DELIVERED in order-service but PENDING in fulfillment-service

**Root Causes**:
1. Concurrent status update attempts (racing transactions)
2. Outbox event not published (CDC lag > 5min)
3. Downstream service crashed before consuming event

**Resolution**:
```bash
# Identify affected orders
SELECT o.order_id, o.status as order_status, f.status as fulfillment_status
FROM orders o
JOIN fulfillment_status f ON o.order_id = f.order_id
WHERE o.status != f.status
LIMIT 20;

# Check if CDC backlog (outbox not flushed)
SELECT COUNT(*) FROM outbox WHERE published_at IS NULL;

# Force CDC relay to process backlog
curl -X POST http://cdc-relay:8091/admin/flush

# Manual status correction (if safe)
UPDATE orders SET status = 'DELIVERED' WHERE order_id = 'order-123'
AND status = 'PENDING' AND existed_at < (NOW() - INTERVAL '1 hour');

# Check CDC connector lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group order-cdc-consumer --describe
```

### Scenario 5: Idempotency Key Not Preventing Duplicates

**Indicators**:
- Two orders created with same idempotency key
- `order_idempotency_cache_hits` = 0
- Duplicate charges on customer statement

**Root Causes**:
1. Unique constraint not enforced in DB
2. Idempotency key TTL expired
3. Cache miss (Redis down)

**Resolution**:
```bash
# Verify unique constraint exists
SELECT constraint_name FROM information_schema.table_constraints
WHERE table_name = 'orders' AND constraint_type = 'UNIQUE'
AND constraint_name LIKE '%idempotency%';

# If missing: Add constraint
ALTER TABLE orders ADD CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key, user_id);

# Check for duplicate idempotency keys
SELECT idempotency_key, COUNT(*) as count
FROM orders
WHERE idempotency_key IS NOT NULL
GROUP BY idempotency_key
HAVING COUNT(*) > 1;

# Manual deduplication (if duplicates found)
DELETE FROM orders
WHERE order_id IN (
  SELECT order_id FROM orders
  WHERE (idempotency_key, created_at) IN (
    SELECT idempotency_key, MAX(created_at)
    FROM orders
    GROUP BY idempotency_key
    HAVING COUNT(*) > 1
  )
)
AND status = 'PENDING'; # Only delete if still pending
```

### Scenario 6: Outbox Event Publish Failures

**Indicators**:
- `outbox_event_publish_failures` counter > 0
- Events stuck in outbox table (published_at IS NULL)
- Downstream services not receiving events

**Root Causes**:
1. Kafka broker down or unreachable
2. Topic does not exist (orders.events)
3. Debezium connector dead

**Resolution**:
```bash
# Check Kafka broker health
kafka-broker-api-versions.sh --bootstrap-server kafka:9092

# Verify topic exists
kafka-topics.sh --bootstrap-server kafka:9092 --list | grep orders

# If missing: Create topic
kafka-topics.sh --bootstrap-server kafka:9092 \
  --create --topic orders.events \
  --partitions 3 --replication-factor 2

# Check Debezium connector logs
kubectl logs -f deployment/debezium-connect -n data-pipeline | grep ERROR

# Restart Debezium
kubectl rollout restart deployment/debezium-connect -n data-pipeline

# Manually publish stuck events (if safe)
SELECT * FROM outbox WHERE published_at IS NULL AND created_at < NOW() - INTERVAL '5 minutes';
# Manually call POST /admin/outbox/publish/{outbox_id}
```

### Scenario 7: High Order Cancellation Rate (> 5%)

**Indicators**:
- `order_cancellation_rate` > 0.05 (5%)
- Revenue impact (cancelled orders = lost revenue)
- High cancellation + high return rate

**Root Causes**:
1. Checkout issues (payment failing, long latency)
2. Product description misleading
3. Delivery expectations not met (expensive shipping)
4. Better deal found elsewhere

**Resolution**:
```bash
# Analyze cancellation reasons
SELECT cancel_reason, COUNT(*) as count
FROM order_cancellations
WHERE created_at > NOW() - INTERVAL '7 days'
GROUP BY cancel_reason
ORDER BY count DESC;

# Segment by user, product, region
SELECT product_id, COUNT(*) as cancellation_count
FROM orders
WHERE status = 'CANCELLED'
AND created_at > NOW() - INTERVAL '7 days'
GROUP BY product_id
ORDER BY cancellation_count DESC LIMIT 10;

# Check if checkout issues (payment failures)
SELECT COUNT(*) as failed_checkouts
FROM order_creation_failures
WHERE created_at > NOW() - INTERVAL '7 days'
AND reason LIKE '%payment%';

# If checkout issues: Escalate to payment-service team
# If product issues: Alert merchandising team
```

### Scenario 8: Order Fulfillment Transition Failures

**Indicators**:
- Orders stuck in PENDING state > 1 hour
- Fulfillment service not receiving OrderCreated events
- `outbox_entry_age_seconds` > 300s

**Root Causes**:
1. CDC event not published
2. Fulfillment service not subscribing to orders.events
3. Fulfillment service crashed

**Resolution**:
```bash
# Check order age (stuck PENDING)
SELECT order_id, created_at, NOW() - created_at as age
FROM orders
WHERE status = 'PENDING'
AND created_at < NOW() - INTERVAL '1 hour'
LIMIT 20;

# Check if outbox entries exist
SELECT COUNT(*) FROM outbox
WHERE event_type = 'OrderCreated'
AND created_at > NOW() - INTERVAL '1 hour'
AND published_at IS NULL;

# Check Fulfillment consumer lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group fulfillment-orders-consumer --describe

# Check Fulfillment service health
kubectl get pods -n fulfillment | grep fulfillment-service
curl http://fulfillment-service:8080/actuator/health

# If Fulfillment crashed: Restart
kubectl rollout restart deployment/fulfillment-service -n fulfillment

# Manually trigger picking task creation (for stuck orders)
curl -X POST http://order-service:8085/admin/orders/{order_id}/repulish-event
```

### Scenario 9: Database Table Bloat (Large orders Table)

**Indicators**:
- `orders_table_size_mb` > 10000MB
- Query performance degradation
- PostgreSQL storage costs increasing

**Root Causes**:
1. No archival of old orders (> 1 year)
2. Frequent UPDATEs causing dead rows (VACUUM not keeping up)
3. Orders never deleted (immutable policy)

**Resolution**:
```bash
# Check table size
SELECT pg_size_pretty(pg_total_relation_size('orders')) as total_size;

# Archive old orders (pre-2024)
BEGIN;
CREATE TABLE orders_2023_archive AS
SELECT * FROM orders WHERE created_at < '2024-01-01';

CREATE INDEX ON orders_2023_archive(order_id);
CREATE INDEX ON orders_2023_archive(user_id);

DELETE FROM orders WHERE created_at < '2024-01-01';
COMMIT;

# Run VACUUM to reclaim space
VACUUM ANALYZE orders;

# Backup archived table
pg_dump -t orders_2023_archive > orders_2023.sql
```

### Scenario 10: Payment Reconciliation Mismatches

**Indicators**:
- `payment_id_reconciliation_mismatches` counter > 10/day
- Reconciliation engine reports orders with no corresponding payment
- Revenue recognition issues

**Root Causes**:
1. Payment created but order not created (order-service down during payment)
2. Payment linked to wrong order_id (data corruption)
3. Payment captured but not visible to order-service

**Resolution**:
```bash
# Identify mismatches
SELECT o.order_id, p.payment_id, o.payment_id as linked_payment_id
FROM orders o
FULL OUTER JOIN payments p ON o.payment_id = p.payment_id
WHERE (o.payment_id IS NULL OR p.payment_id IS NULL)
AND o.created_at > NOW() - INTERVAL '7 days'
LIMIT 50;

# Link orphaned payments to orders
UPDATE orders SET payment_id = 'pay-xyz' WHERE order_id = 'order-abc'
AND payment_id IS NULL;

# If payment truly unlinked: Investigate payment-service
SELECT * FROM payments WHERE order_id IS NULL AND created_at > NOW() - INTERVAL '24 hours';

# Revenue recognition: Manual adjustment if needed
```

## Production Runbook Patterns

### Runbook 1: Order Creation Service Degradation

**SLA**: < 5 min detection to mitigation

1. **Alert**: Order creation success rate < 99.9%
2. **Diagnosis**:
   - Check database CPU/memory (kubectl top)
   - Check connection pool utilization
   - Check Kafka broker health
3. **Mitigation**:
   - Scale order-service replicas (3 → 5)
   - Increase DB connection pool temporarily
   - Drain slow orders to queue (return 202 Accepted)
4. **Communication**: "Checkout experiencing temporary slowness"
5. **Recovery**: Monitor recovery; scale down once stable

### Runbook 2: Order Status Inconsistency (Eventual Consistency Breach)

**Scenario**: CDC relay backlog; orders not transitioning to fulfillment

1. **Detection**: Outbox entry age > 60s
2. **Actions**:
   - Restart Debezium connector
   - Check Kafka broker disk space
   - Flush stuck outbox entries
3. **Escalation**: If not resolved in 10 min, page fulfillment-service owner
4. **Recovery**: Manually trigger event re-publishing

## Configuration

### Environment Variables
```env
SERVER_PORT=8085
SPRING_PROFILES_ACTIVE=gcp

SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/orders
SPRING_DATASOURCE_HIKARI_POOL_SIZE=20
SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES=5

ORDER_IDEMPOTENCY_KEY_TTL_MINUTES=30
ORDER_CACHE_TTL_SECONDS=60

OTEL_TRACES_SAMPLER=always_on
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
```

## Integrations

### Synchronous Calls
| Service | Endpoint | Timeout | Purpose | Critical |
|---------|----------|---------|---------|----------|
| identity-service | http://identity-service:8080/jwt/validate | 1s | JWT validation | Yes |
| payment-service | http://payment-service:8084/payments/{id} | 2s | Payment lookup | Yes |

### Event Publishing
| Topic | Event | Pattern | Reliability |
|-------|-------|---------|------------|
| orders.events | OrderCreated | Outbox pattern | Exactly-once |
| orders.events | OrderConfirmed | Outbox pattern | Exactly-once |
| orders.events | OrderCancelled | Outbox pattern | Exactly-once |
| orders.events | OrderDelivered | Outbox pattern | Exactly-once |

## Endpoints

### Public (Health Check)
- `GET /actuator/health` - Liveness probe
- `GET /actuator/health/ready` - Readiness (checks DB + Kafka)
- `GET /actuator/metrics` - Prometheus metrics

### Order API
- `POST /orders` - Create order (idempotent)
- `GET /orders/{id}` - Get order details
- `GET /orders` - List user's orders (paginated)
- `PUT /orders/{id}/cancel` - Cancel order
- `PUT /orders/{id}/status` - Transition order status

### Admin API
- `GET /admin/orders` - Admin order list (with filters)
- `POST /admin/orders/batch` - Bulk import orders

### Example Requests

```bash
# Create order
curl -X POST http://order-service:8085/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{
    "cart_id": "cart-123",
    "user_id": "user-456",
    "payment_id": "pay-789",
    "delivery_address": "123 Main St",
    "idempotency_key": "uuid-abc123"
  }' | jq '.order_id'

# Get order
curl -X GET http://order-service:8085/orders/order-123 \
  -H "Authorization: Bearer $JWT_TOKEN" | jq '.status'

# Cancel order
curl -X PUT http://order-service:8085/orders/order-123/cancel \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{"reason": "customer_cancelled"}' | jq '.status'
```

## Related Documentation

- **ADR-004**: Order Service Schema and Lifecycle
- **Wave 36**: Reconciliation Engine (financial audit)
- **Wave 33**: Transactional Outbox Pattern (CDC event publishing)
- **Runbook**: order-service/runbook.md
- **Sequence Diagrams**: diagrams/order-lifecycle-sequence.md
