# Inventory Service

## Overview

The inventory-service maintains the authoritative stock levels for all products across all warehouses. It handles reservations during checkout, releases on cancellation, manages low-stock alerts, and prevents overselling through pessimistic locking. This is a Tier 1 critical service with extremely high concurrency demands.

**Service Ownership**: Platform Team - Money Path Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8083
**Status**: Tier 1 Critical (inventory authority)
**Database**: PostgreSQL 15+ (stock ledger, reservations)

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month)
- **P99 Latency**:
  - Reserve: < 500ms (POST /inventory/reserve)
  - Release: < 300ms (POST /inventory/release)
  - Check: < 100ms (GET /inventory/{productId})
- **Error Rate**: < 0.05% (lock timeouts, constraint violations)
- **Max Throughput**: 5,000 reservations/minute (extreme concurrency: multiple requests for same product)

## Key Responsibilities

1. **Stock Level Maintenance**: Track available, reserved, and allocated inventory per product per warehouse
2. **Reservation Management**: Reserve stock for pending orders; enforce 5-minute TTL on reservations
3. **Stock Release**: Release reservations on order cancellation or expiry
4. **Oversell Prevention**: Pessimistic locking (SELECT...FOR UPDATE) prevents double-selling
5. **Low Stock Alerts**: Emit events when stock falls below threshold
6. **Reservation Expiry**: Background job cleans up expired reservations (5-min TTL)
7. **Audit Trail**: Track all reserve/release operations for forensics
8. **Stock Adjustments**: Admin-only APIs for inventory corrections (damaged goods, shrinkage, returns)

## Deployment

### GKE Deployment
- **Namespace**: money-path
- **Replicas**: 3 (HA, active-active for load distribution)
- **CPU Request/Limit**: 500m / 1500m
- **Memory Request/Limit**: 512Mi / 1Gi (higher due to connection pool)
- **Startup Probe**: 15s initial delay

### Database
- **Name**: `inventory` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V12)
- **Connection Pool**: HikariCP 60 connections (HIGHEST of all services due to concurrency)
- **Idle Timeout**: 5 minutes
- **Max Lifetime**: 30 minutes

### Network
- **Egress**: To order-service, fulfillment-service, notification-service
- **NetworkPolicy**: Deny-default; allow from checkout-orchestrator

## Architecture

### System Context

```
┌────────────────────────────────────────────────────────┐
│          Inventory Service (Stock Authority)            │
│     (Reservations, Stock Levels, Concurrency Control)   │
└────────┬────────────────────────┬────────────────────┘
         │                        │
    ┌────▼────┐          ┌────────▼────────┐
    │ Upstream │          │   Downstream    │
    │ Clients  │          │   Dependents    │
    └────┬────┘          └────────┬────────┘
         │                        │
   • checkout-             • fulfillment-service
     orchestrator          • notification-service
   • admin-gateway         • search-service (stock availability)
```

### Stock State Transitions

```
AVAILABLE → RESERVED (checkout reserves)
  ├─ [Success] ↓
  │ → RELEASED (checkout cancelled within 5 min)
  │   → back to AVAILABLE
  │
  ├─ [Expiry] ↓
  │ Reservation TTL expires (5 min)
  │   → back to AVAILABLE (via cron job)
  │
  └─ [Order Confirmed] ↓
    → ALLOCATED (fulfillment picks items)
      → back to AVAILABLE (picking failed, items returned)
      → or SOLD (picking completed, stock deducted)
```

## Integrations

### Synchronous Calls (Critical Locks)
| Service | Endpoint | Timeout | Purpose | Lock |
|---------|----------|---------|---------|------|
| order-service | http://order-service:8085/orders/{id}/validate | 3s | Validate order | Row-level SELECT...FOR UPDATE |
| fulfillment-service | http://fulfillment-service:8080/pick/{id} | 5s | Query pick status | No lock (read-only) |

### Asynchronous Events
| Topic | Direction | Events |
|-------|-----------|--------|
| inventory.events | Publish | StockReserved, StockReleased, StockAdjusted, LowStockAlert, ReservationExpired |
| orders.events | Consume | OrderCancelled, OrderConfirmed |
| fulfillment.events | Consume | PickingCompleted (confirms allocation) |

## Data Model

### Core Entities

```
Stock Table (Per Product, Per Warehouse):
├─ id (UUID, PK)
├─ product_id (UUID, FK)
├─ warehouse_id (UUID, FK)
├─ quantity_available (INT, >= 0)
├─ quantity_reserved (INT, >= 0)
├─ quantity_allocated (INT, >= 0)  # Being picked/packed
├─ low_stock_threshold (INT, configurable)
├─ reorder_quantity (INT, for ops)
├─ updated_at (TIMESTAMP)
└─ (Unique constraint: (product_id, warehouse_id))

Reservations Table (Active Holds):
├─ id (UUID, PK)
├─ order_id (UUID, FK → order-service, indexed)
├─ product_id (UUID)
├─ warehouse_id (UUID)
├─ quantity (INT)
├─ reserved_at (TIMESTAMP)
├─ expires_at (TIMESTAMP = reserved_at + 5 minutes, indexed)
├─ released (BOOLEAN, logical delete)
├─ released_at (TIMESTAMP)
├─ release_reason (ENUM: CANCELLED, EXPIRED, ALLOCATED)
└─ (Unique constraint: (order_id, product_id, warehouse_id))

Adjustment Log (Admin Actions):
├─ id (UUID, PK)
├─ product_id (UUID)
├─ warehouse_id (UUID)
├─ adjustment_type (ENUM: DAMAGE, SHRINKAGE, CORRECTION, RETURN)
├─ quantity_delta (INT, positive or negative)
├─ reason (TEXT)
├─ adjusted_by (VARCHAR - admin user)
├─ adjusted_at (TIMESTAMP)
└─ (Audit log for compliance)
```

## API Documentation

### Reserve Stock
**POST /inventory/reserve**
```bash
Authorization: Bearer {JWT_TOKEN}

Request:
{
  "orderId": "order-uuid",
  "items": [
    {
      "productId": "prod-uuid",
      "quantity": 2,
      "warehouseId": "warehouse-uuid"
    }
  ]
}

Response (201 Created):
{
  "reservationId": "reservation-uuid",
  "items": [
    {
      "productId": "prod-uuid",
      "reserved": 2,
      "available": 5,
      "expiresAt": "2025-03-21T10:05:00Z"
    }
  ],
  "ttlMinutes": 5
}

Response (409 Conflict - Out of Stock):
{
  "error": "Insufficient stock",
  "product": "prod-uuid",
  "requested": 2,
  "available": 1
}
```

### Release Reservation
**POST /inventory/release**
```bash
Request:
{
  "reservationId": "reservation-uuid",
  "reason": "ORDER_CANCELLED"
}

Response (200):
{
  "released": true,
  "items": [...],
  "releasedAt": "2025-03-21T10:02:00Z"
}
```

### Check Stock
**GET /inventory/{productId}?warehouseId=warehouse-uuid**
```bash
Response (200):
{
  "productId": "prod-uuid",
  "warehouseId": "warehouse-uuid",
  "available": 5,
  "reserved": 2,
  "allocated": 1,
  "total": 8,
  "lowStockThreshold": 10,
  "isLowStock": false
}
```

### Adjust Stock (Admin Only)
**POST /inventory/{productId}/adjust**
```bash
Authorization: Bearer {ADMIN_JWT}

Request:
{
  "warehouseId": "warehouse-uuid",
  "adjustmentType": "DAMAGE",
  "quantityDelta": -1,
  "reason": "Damaged unit received from vendor"
}

Response (200):
{
  "productId": "prod-uuid",
  "newAvailable": 4,
  "adjustment": {
    "type": "DAMAGE",
    "quantity": -1,
    "adjustedAt": "2025-03-21T10:30:00Z"
  }
}
```

## Error Handling & Resilience

### Concurrency Control Strategy

**Pessimistic Locking (Row-Level)**:
```sql
-- Reserve operation (highest contention)
SELECT * FROM stock WHERE product_id = ? AND warehouse_id = ?
  FOR UPDATE NOWAIT  # Fail immediately if locked
UPDATE stock SET quantity_reserved = quantity_reserved + ?
  WHERE product_id = ? AND warehouse_id = ?
```

**Lock Timeout**: 2 seconds (fail fast on contention)

**Circuit Breaker**: None (DB-level locking is superior)

### Failure Scenarios

**Scenario 1: Lock Timeout (High Contention)**
- Multiple clients reserve same product simultaneously
- First succeeds, others wait up to 2 seconds
- If timeout → Client receives 503
- Recovery: Client retries; exponential backoff

**Scenario 2: Reservation Expires (5-minute TTL)**
- Client doesn't confirm order within 5 minutes
- Background job (runs every 30 seconds) identifies expired reservations
- Job releases reservation → Stock available again
- Recovery: Automatic; customer sees item available again

**Scenario 3: Database Connection Pool Exhausted**
- 60 connections all in use due to high concurrency
- New reservations block in queue (up to 30 seconds)
- Recovery: Scale replicas (more connection pools)

**Scenario 4: Stock Goes Negative (Data Corruption)**
- Should never happen due to constraints
- If detected by reconciliation-engine: Alert SEV-2
- Recovery: Manual data correction; investigation

## Configuration

### Environment Variables
```env
SERVER_PORT=8083
SPRING_PROFILES_ACTIVE=gcp

SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/inventory
SPRING_DATASOURCE_HIKARI_POOL_SIZE=60  # HIGHEST of all services
SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES=5
SPRING_DATASOURCE_HIKARI_MAX_LIFETIME_MINUTES=30

JWT_ISSUER=instacommerce-identity
JWT_PUBLIC_KEY=${JWT_PUBLIC_KEY_PEM}

KAFKA_BOOTSTRAP_SERVERS=kafka-broker-1:9092,kafka-broker-2:9092

OTEL_TRACES_SAMPLER=always_on
```

### application.yml
```yaml
inventory:
  lock:
    timeout-ms: 2000  # Fail fast on contention
    nowait: true  # Use NOWAIT instead of waiting
  reservation:
    ttl-minutes: 5
    expiry-check-interval-seconds: 30  # Run cleanup job every 30s
  low-stock:
    alert-enabled: true
    threshold-default: 10

spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 25
          use_sql_comments: true
```

## Monitoring & Observability

### Key Metrics

| Metric | Type | Alert |
|--------|------|-------|
| `inventory_reserve_latency_ms` | Histogram (p50, p95, p99) | p99 > 500ms |
| `inventory_reserve_total` | Counter (by status) | N/A |
| `inventory_lock_timeout_total` | Counter (contention indicator) | > 10/min = investigate |
| `reservation_ttl_expired_total` | Counter | N/A |
| `stock_available_total` | Gauge (per product, per warehouse) | N/A |
| `db_connection_pool_active` | Gauge | > 55/60 = scale alert |
| `db_connection_pool_pending` | Gauge (queue size) | > 5 = contention |
| `low_stock_alert_total` | Counter | N/A |

### Alert Rules
```yaml
alerts:
  - name: InventoryReservationTimeout
    condition: rate(inventory_lock_timeout_total[5m]) > 10/60
    duration: 5m
    severity: SEV-2
    action: Alert ops; consider scaling

  - name: ConnectionPoolSaturation
    condition: db_connection_pool_active / 60 > 0.9
    duration: 2m
    severity: SEV-1
    action: Page on-call; scale service immediately

  - name: ReservationQueueBuildup
    condition: db_connection_pool_pending > 5
    duration: 5m
    severity: SEV-2
    action: Monitor; prepare to scale
```

## Security & Compliance

- **Auth**: JWT RS256
- **Concurrency Guarantees**: Row-level pessimistic locking prevents race conditions
- **Audit Trail**: All reserve/release logged for compliance
- **Encryption**: Sensitive operations encrypted in transit

## Inventory State Transitions & Consistency

### Detailed State Machine

```
AVAILABLE (initial stock)
├─ [Reserve] → RESERVED (checkout flow)
│  └─ [Expiry: 5 min] → AVAILABLE (auto-release via cron)
│  └─ [Release: CANCELLED] → AVAILABLE (manual release)
│  └─ [Confirm Order] → ALLOCATED (allocation starts)
│
├─ [Direct Allocation] → ALLOCATED (skip reserve if stock sufficient)
│  ├─ [Picking Complete] → AVAILABLE (picking successful, items returned)
│  ├─ [Picking Success] → SOLD (deducted from inventory)
│  └─ [Picking Failed] → AVAILABLE (picking incomplete, items returned)
│
└─ [Admin Adjustment] → AVAILABLE ± delta (damage, shrinkage, correction)

Stock Ledger:
total_stock = available + reserved + allocated
```

### Pessimistic vs Optimistic Locking Strategy

**Pessimistic Locking (Reserve Operation - Critical Path)**:
```sql
-- SELECT...FOR UPDATE NOWAIT prevents concurrent reserves on same product
BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;
SELECT * FROM stock
  WHERE product_id = ? AND warehouse_id = ?
  FOR UPDATE NOWAIT;  -- Fail immediately if locked

IF quantity_available >= requested_qty THEN
  UPDATE stock
    SET quantity_reserved = quantity_reserved + requested_qty
    WHERE product_id = ? AND warehouse_id = ?;
  COMMIT;
ELSE
  ROLLBACK; RAISE OUT_OF_STOCK;
END IF;
```

**Lock Timeout**: 2 seconds (fail-fast on contention)
**Justification**: Prevents double-selling; acceptable latency trade-off for correctness

**Optimistic Locking (Admin Adjustments - Low Contention)**:
```sql
-- @Version prevents stale admin updates
UPDATE stock
  SET quantity_available = ?,
      version = version + 1
  WHERE product_id = ? AND warehouse_id = ? AND version = ?;

IF no rows updated THEN
  RETRY (exponential backoff)
END IF;
```

## Stock Rebalancing Between Warehouses

**Manual Rebalancing Endpoint**:
```
POST /inventory/rebalance
{
  "fromWarehouse": "warehouse-us-west-2",
  "toWarehouse": "warehouse-us-east-1",
  "transfers": [
    {"productId": "prod-123", "quantity": 50, "reason": "DEMAND_FORECAST"}
  ]
}
```

**Rebalancing Logic**:
1. Check source warehouse has available stock
2. Lock both warehouses (deadlock-safe ordering: by warehouse_id)
3. Decrement source available; increment destination available
4. Publish `StockTransferInitiated` event
5. Fulfillment picks from destination warehouse
6. Publish `StockTransferCompleted` event

**Demand-Driven Rebalancing** (automatic):
- Daily at 2 AM UTC: Analyze forecast vs current stock
- Identify imbalances: Zone A < 20% capacity, Zone B > 80%
- Auto-trigger rebalancing if cost < shortage risk
- Max transfer: 1000 units/product/day (logistics constraint)

## Replenishment Triggers and Order Batching

**Low Stock Alert Threshold**:
```
SET low_stock_threshold = 20 (configurable per SKU)
IF quantity_available < threshold THEN
  PUBLISH StockLowAlert
```

**Replenishment Order Batching**:
```
Cron Job (runs every 6 hours):
1. Collect all products with stock < reorder_quantity
2. Group by supplier (reduce logistics cost)
3. Batch 1000 units minimum (cost optimization)
4. Create purchase order → supplier API
5. Publish ReplenishmentOrderCreated event
6. Track ETA; forecast arrival in 3-5 days
7. When received: Publish StockReplenished event
```

**Example Batching**:
```
Products needing replenishment:
- Supplier A: prod-123 (50 units), prod-456 (100 units)
- Supplier B: prod-789 (40 units)

Batched PO:
- PO-A: {prod-123: 500, prod-456: 500} (MOQ override)
- PO-B: 500 units prod-789 (MOQ 500)
```

## Dead Letter Queue (DLQ) for Failed Reservations

**Reservation Failure Path**:
```
Reserve Request
  │
  ├─ [Lock Timeout] → DLQ Topic: inventory.reserve.dlq
  ├─ [Out of Stock] → Immediate 409 (not sent to DLQ)
  └─ [DB Error] → DLQ with exception details

DLQ Handler (runs every 5 minutes):
├─ Read 100 messages from DLQ
├─ Retry with exponential backoff (3x max)
├─ If still failing after 3 retries:
│  ├─ Send alert: SEV-2 (reserve operation critical)
│  ├─ Notify order-service: reservation failed
│  └─ Log for manual investigation
└─ Dead-letter to dead-dead-queue after 3 retries
```

**DLQ Metrics**:
- `inventory_dlq_messages_total` - Messages sent to DLQ
- `inventory_dlq_retry_success_rate` - % recovered by retry
- `inventory_dlq_permanent_failure_rate` - % requiring manual intervention

## Inventory Accuracy Reconciliation

**Scheduled Reconciliation** (daily at 2 AM UTC):
```
1. Snapshot database stock levels
2. Query fulfillment-service: allocated items in transit
3. Calculate expected: available + reserved + allocated
4. Query payment-service: refunded orders (should increment stock)
5. Calculate variance per product per warehouse
6. If variance > 5 units: Alert ops (investigate discrepancy)
7. If variance > 100 units: SEV-2 (potential data loss)
8. Publish ReconciliationCompleted event
```

**Root Causes of Variance**:
- Shrinkage (theft, damage): 0.5-2% of inventory
- Double-counting: Allocated not properly released
- Refund processing lag: Refunded items not yet credited
- Data corruption: Rare; escalate to SRE team

**Recovery Process**:
1. Isolate affected SKU
2. Physical count at warehouse
3. Adjust inventory via admin API
4. Update adjustment_log with reason + approver
5. Notify fulfillment team of reconciliation

## Metrics for Inventory Health (25+ Metrics)

### Stock Metrics
| Metric | Type | Target |
|--------|------|--------|
| `inventory_stock_turnover_ratio` | Gauge (per SKU) | 4-12x/year |
| `inventory_stock_velocity_units_per_day` | Gauge | > 0 (fast movers) |
| `inventory_obsolescence_ratio` | Gauge | < 2% |
| `inventory_shrinkage_rate` | Gauge | < 1% |
| `inventory_carrying_cost_usd` | Gauge | Minimize trend |
| `inventory_aging_days` | Histogram (p50, p95, p99) | P99 < 180 days |

### Reserve Metrics
| Metric | Type | Alert |
|--------|------|-------|
| `inventory_reserve_latency_ms` | Histogram (p99) | > 500ms |
| `inventory_reserve_success_rate` | Gauge | < 95% = SEV-2 |
| `inventory_lock_timeout_total` | Counter | > 10/min |
| `inventory_reserve_dlq_messages` | Counter | > 0 = alert |
| `inventory_reservation_expiry_total` | Counter | N/A |

### Multi-Warehouse Metrics
| Metric | Type | Purpose |
|--------|------|---------|
| `inventory_by_warehouse` | Gauge (by warehouse_id) | Capacity planning |
| `inventory_rebalance_events_total` | Counter | Tracking rebalances |
| `inventory_transfer_latency_days` | Gauge | Supply chain efficiency |
| `inventory_warehouse_utilization_pct` | Gauge | Forecasting |

## Multi-Warehouse Consistency Patterns

**Distributed Consistency Challenge**:
```
Problem: Checkout in Zone A reserves stock from Warehouse B
- Stock decrements in Warehouse B
- Fulfillment picks from Warehouse A (closer)
- Cross-warehouse transfer needed

Solution: Two-phase commit with timeouts
```

**Consistency Protocol**:
```
Phase 1: Lock (2 seconds max)
├─ Reserve from source warehouse
├─ Lock destination warehouse
└─ Verify sufficient stock

Phase 2: Commit (after fulfillment picks)
├─ Decrement source
├─ Increment destination (via transfer)
├─ Publish events to Kafka
└─ Release locks
```

**Fallback**: If phase 2 fails, async reconciliation corrects within 1 hour

## Performance Optimization Techniques

**1. Index Strategy**:
```sql
CREATE INDEX idx_stock_product_warehouse ON stock(product_id, warehouse_id);
CREATE INDEX idx_reservations_expires ON reservations(expires_at)
  WHERE released = FALSE;
CREATE INDEX idx_stock_by_warehouse ON stock(warehouse_id, quantity_available);
```

**2. Connection Pool Tuning**:
- HikariCP: 60 connections (highest of all services)
- Max lifetime: 30 minutes (connection rotation)
- Idle timeout: 5 minutes
- Queue timeout: 30 seconds (fail-fast on saturation)

**3. Batch Operations**:
- Reserve up to 100 items per request (reduce lock contention)
- Release expired reservations in 1000-item batches
- Adjustment log: Insert 100 at a time (bulk insert)

**4. Caching**:
- Local Caffeine cache: Top 100 products by volume (1-minute TTL)
- Redis cache: Warehouse capacities (1-hour TTL)
- Query result cache: Stock by warehouse (30-second TTL)

**5. Query Optimization**:
- Use EXPLAIN ANALYZE to identify slow queries
- Avoid SELECT * (fetch only needed columns)
- Use connection pooling; avoid creating new connections

## Advanced Troubleshooting (10+ Scenarios)

### Scenario 1: Cascading Lock Timeouts

**Indicators**:
- `inventory_lock_timeout_total` > 50/min
- Reserve endpoint P99 > 1000ms
- Order conversion rate dropping

**Root Causes**:
1. Popular product (e.g., sale item) with high reserve concurrency
2. Connection pool exhausted (60 all in use)
3. Database slow (query optimization regression)
4. Deadlock detection causing transaction rollback

**Resolution**:
```bash
# Identify hot product
SELECT product_id, COUNT(*) as reserve_attempts
FROM activity_log
WHERE operation = 'RESERVE'
AND created_at > NOW() - INTERVAL '1 hour'
GROUP BY product_id
ORDER BY reserve_attempts DESC
LIMIT 10;

# Check connection pool status
SELECT COUNT(*) as active_connections
FROM pg_stat_activity WHERE datname = 'inventory';

# Scale inventory service replicas
kubectl scale deployment inventory-service --replicas=5

# Reduce lock timeout temporarily
INVENTORY_LOCK_TIMEOUT_MS=1000 (instead of 2000)

# Partition hot product across multiple warehouses
UPDATE stock SET warehouse_id = 'warehouse-dedicated-hot'
WHERE product_id = 'prod-hot-item';
```

### Scenario 2: Reservation Expiry Not Cleaning Up

**Indicators**:
- `reservation_ttl_expired_total` not incrementing
- Stale reservations accumulating in database
- `quantity_available` lower than expected

**Root Causes**:
1. Cleanup job disabled
2. Cleanup job stuck (previous job still running)
3. Database deadlock preventing UPDATE
4. Cron expression misconfigured

**Resolution**:
```bash
# Check cleanup job status
SELECT * FROM scheduled_job_log
WHERE job_name = 'CleanupExpiredReservations'
ORDER BY executed_at DESC LIMIT 5;

# Manual cleanup if needed
UPDATE reservations
SET released = TRUE, released_at = NOW(), release_reason = 'EXPIRED'
WHERE expires_at < NOW() AND released = FALSE;

# Verify cleanup job config
INVENTORY_RESERVATION_EXPIRY_INTERVAL_SECONDS=30

# Monitor cleanup performance
SELECT COUNT(*) as expired_count
FROM reservations
WHERE expires_at < NOW() AND released = FALSE;
```

### Scenario 3: Stock Goes Negative (Data Corruption)

**Indicators**:
- Dashboard shows negative stock for product
- Reconciliation engine alerts: "Stock < 0"
- Customer complaint: Can't order available item

**Root Causes**:
1. Race condition in reserve/release logic (should be impossible)
2. Malicious admin bypass via direct SQL (security investigation)
3. Data corruption from failed transaction
4. Double-counting bug in fulfillment flow

**Resolution**:
```bash
# Identify affected products
SELECT product_id, warehouse_id, quantity_available
FROM stock
WHERE quantity_available < 0
ORDER BY quantity_available;

# Immediate containment: Block reservations for affected products
UPDATE stock SET quantity_available = 0
WHERE quantity_available < 0;

# Investigate data integrity
SELECT * FROM adjustment_log
WHERE product_id = 'affected-product'
ORDER BY adjusted_at DESC LIMIT 20;

# Verify no double-counting
SELECT
  SUM(quantity_available) as available,
  SUM(quantity_reserved) as reserved,
  SUM(quantity_allocated) as allocated
FROM stock
WHERE product_id = 'affected-product';

# Audit reserve/release events
SELECT COUNT(*) as total_events, status, error
FROM kafka_events
WHERE product_id = 'affected-product'
AND event_type IN ('StockReserved', 'StockReleased')
GROUP BY status, error;
```

### Scenario 4: Database Connection Pool Exhausted

**Indicators**:
- `db_connection_pool_active` = 60/60
- `db_connection_pool_pending` growing
- New reserve requests timeout (503)

**Root Causes**:
1. Leaked connection (not returned to pool)
2. Long-running query holding connection
3. Peak traffic spike (100x normal volume)
4. Downstream service slow (payment-service timeout causing hold)

**Resolution**:
```bash
# Check for idle connections
SELECT datname, usename, application_name, query, query_start
FROM pg_stat_activity
WHERE query NOT LIKE '%pg_stat_activity%'
ORDER BY query_start DESC;

# Kill idle connections (use cautiously)
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'inventory'
AND query_start < NOW() - INTERVAL '30 minutes'
AND state = 'idle';

# Increase pool size temporarily
kubectl set env deployment inventory-service \
  SPRING_DATASOURCE_HIKARI_POOL_SIZE=80

# Scale replicas (more connection pools)
kubectl scale deployment inventory-service --replicas=5

# Analyze connection usage pattern
SELECT
  hour,
  active_connections,
  pending_connections
FROM metrics
WHERE service = 'inventory-service'
AND timestamp > NOW() - INTERVAL '6 hours'
ORDER BY timestamp DESC;
```

### Scenario 5: Reservation Lock Conflicts (Deadlock)

**Indicators**:
- PostgreSQL logs: "DEADLOCK detected"
- Reserve endpoint returns 500 intermittently
- `inventory_dlq_messages` spike

**Root Causes**:
1. Two concurrent reserves in different order (classic deadlock)
2. Reserve → Allocation → Release cycle in different transaction order
3. Admin adjustment during heavy reserve traffic

**Resolution**:
```bash
# Check PostgreSQL deadlock logs
SELECT database, tid, lines
FROM pg_log_statements
WHERE message LIKE '%DEADLOCK%'
ORDER BY timestamp DESC LIMIT 10;

# Implement ordered locking (prevent circular waits)
-- Lock warehouses in consistent order: ORDER BY warehouse_id
SELECT * FROM stock
WHERE product_id IN (?, ?, ?)
ORDER BY warehouse_id, product_id
FOR UPDATE;

# Reduce transaction scope (fewer operations in lock)
-- Don't hold lock during downstream API calls

# Increase isolation level monitoring
ISOLATION_LEVEL=SERIALIZABLE (for critical reserves)

# Retry with exponential backoff on deadlock
catch DeadlockException: sleep(random(100, 500ms)) then retry
```

### Scenario 6: Slow Expiry Cleanup (>5 minutes)

**Indicators**:
- Cleanup job duration > 5 minutes (vs normal 30 seconds)
- `reservation_ttl_expired_total` growing slower than expected
- Stale reservations accumulating

**Root Causes**:
1. Too many expired reservations (>100k)
2. Index fragmentation on expires_at
3. Concurrent transactions blocking cleanup
4. Slow I/O (storage degradation)

**Resolution**:
```bash
# Count expired reservations
SELECT COUNT(*) as expired_count
FROM reservations
WHERE expires_at < NOW() AND released = FALSE;

# Check index fragmentation
SELECT schemaname, tablename, indexname,
       idx_blks_read, idx_blks_hit, idx_blks_hit::float/(idx_blks_hit + idx_blks_read) as hit_ratio
FROM pg_statio_user_indexes
WHERE tablename = 'reservations'
ORDER BY hit_ratio ASC;

# Rebuild index
REINDEX INDEX idx_reservations_expires;

# Vacuum and analyze
VACUUM ANALYZE reservations;

# Optimize cleanup job
-- Batch: DELETE 1000 at a time instead of all at once
-- Log progress: Every 10k deleted items

# Monitor I/O performance
SELECT
  schemaname, tablename,
  seq_scan, seq_tup_read,
  idx_scan, idx_tup_fetch
FROM pg_stat_user_tables
WHERE tablename = 'reservations'
ORDER BY seq_scan DESC;
```

### Scenario 7: Cross-Warehouse Transfer Stuck

**Indicators**:
- Transfer initiated but never completed
- Source warehouse shows reserved (transfer lock)
- Destination warehouse unchanged
- Fulfillment can't pick from destination

**Root Causes**:
1. Async event not published (Kafka producer error)
2. Destination warehouse lock timed out
3. Network partition during transfer
4. Database transaction not committed

**Resolution**:
```bash
# Check transfer status
SELECT * FROM stock_transfers
WHERE status = 'IN_PROGRESS'
ORDER BY initiated_at DESC;

# Verify Kafka events published
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic inventory.events --from-beginning \
  --max-messages 100 | grep -i TRANSFER

# Manual recovery: Complete stuck transfer
UPDATE stock
SET quantity_available = quantity_available - 50
WHERE product_id = 'prod-123' AND warehouse_id = 'src';

UPDATE stock
SET quantity_available = quantity_available + 50
WHERE product_id = 'prod-123' AND warehouse_id = 'dst';

# Publish recovery event
POST /admin/inventory/transfer-complete
{
  "productId": "prod-123",
  "fromWarehouse": "src",
  "toWarehouse": "dst",
  "quantity": 50,
  "recoveryMode": true
}
```

### Scenario 8: Rebalancing Creating Inefficiencies

**Indicators**:
- Frequent transfers between same warehouses
- Transfer cost > benefit (customer spend moving items around)
- Fulfillment latency increasing (items further from customers)

**Root Causes**:
1. Rebalancing algorithm too aggressive
2. Demand forecast inaccurate
3. Warehouse locations suboptimal
4. Logistics cost not factored in rebalancing

**Resolution**:
```bash
# Analyze rebalancing efficiency
SELECT
  from_warehouse, to_warehouse,
  COUNT(*) as transfer_count,
  SUM(quantity) as total_units,
  AVG(cost_usd) as avg_cost
FROM stock_transfers
WHERE created_at > NOW() - INTERVAL '30 days'
GROUP BY from_warehouse, to_warehouse
ORDER BY transfer_count DESC;

# Disable aggressive rebalancing
INVENTORY_REBALANCE_AUTO_ENABLED=false

# Adjust rebalancing cost threshold
INVENTORY_REBALANCE_COST_THRESHOLD_USD=500 (only if beneficial)

# Manual rebalancing with approval
POST /admin/inventory/rebalance-plan
{
  "review": true,
  "approver_id": "inventory-lead"
}
```

### Scenario 9: Admin Adjustment Rollback Needed

**Indicators**:
- Wrong adjustment applied to inventory
- Physical count doesn't match DB
- Need to reverse a previous adjustment

**Root Causes**:
1. Data entry error (wrong quantity)
2. Wrong warehouse selected
3. Duplicate adjustment processing

**Resolution**:
```bash
# Identify adjustment
SELECT * FROM adjustment_log
WHERE product_id = 'prod-123'
ORDER BY adjusted_at DESC
LIMIT 5;

# Create rollback adjustment (reverse delta)
POST /inventory/adjust
{
  "productId": "prod-123",
  "warehouseId": "warehouse-uuid",
  "adjustmentType": "CORRECTION",
  "quantityDelta": -5,  # Reverse the +5 from earlier
  "reason": "Rollback of erroneous adjustment from 2025-03-20 14:30:00"
}

# Verify stock correct
SELECT quantity_available
FROM stock
WHERE product_id = 'prod-123' AND warehouse_id = 'warehouse-uuid';

# Update adjustment log comment
UPDATE adjustment_log
SET notes = 'Rolled back due to data entry error'
WHERE id = 'adjustment-uuid';
```

### Scenario 10: Cost Optimization (Reduce Carrying Costs)

**Indicators**:
- Inventory carrying cost > target (% of revenue)
- Obsolete stock accumulating (aging > 180 days)
- Turnover ratio declining (inventory not moving)

**Resolution**:
```bash
# Identify slow-moving SKUs
SELECT product_id, quantity_available, days_in_stock,
       quantity_available * unit_cost as carrying_cost_usd
FROM stock
WHERE days_in_stock > 180
ORDER BY carrying_cost_usd DESC
LIMIT 50;

# Implement clearance pricing
-- Marketing team discounts old SKUs
-- Inventory team tracks moving in real-time

# Adjust reorder quantities
UPDATE stock
SET reorder_quantity = reorder_quantity * 0.75  # Reduce by 25%
WHERE days_in_stock > 90
AND turnover_ratio < 2;

# Forecast-driven replenishment
-- Use demand forecast to reduce buffer stock
-- Balance service level (99%) vs inventory carrying cost

# Monitor carrying cost metric
SELECT
  SUM(quantity_available * unit_cost) as total_carrying_cost,
  total_carrying_cost / (SELECT SUM(revenue) FROM orders WHERE created_at > NOW() - INTERVAL '1 month') * 100 as pct_of_revenue
FROM stock;
```

## Related Documentation

- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Database Schema (ERD)](diagrams/erd.md)
- [REST API Contract](implementation/api.md)
- [Resilience & Retry Logic](implementation/resilience.md)
- [Request/Response Flows](diagrams/flowchart.md)
- [Sequence Diagrams](diagrams/sequence.md)
- [Kafka Events](implementation/events.md)
- [Database Details](implementation/database.md)
- [Deployment Runbook](runbook.md)
- **ADR-014**: Reconciliation Authority Model (consistency guarantees)
