# Checkout Orchestrator Service

## Overview

The checkout-orchestrator-service is the critical orchestration layer for the complete checkout saga in InstaCommerce. It implements a long-running Temporal workflow (durable execution engine) that coordinates atomically across 5+ services (cart validation, inventory reserve, payment authorization, pricing calculation, order creation) while maintaining idempotency and distributed compensation on failure. This Tier 1 critical service blocks revenue; any failure directly impacts conversion rates and customer experience.

**Service Ownership**: Platform Team - Money Path Tier 1 (Critical path)
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8089
**Status**: Tier 1 Critical (blocks revenue; zero-tolerance for silent failures)
**Database**: PostgreSQL 15+ (idempotency ledger, workflow state, audit trail)

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **P50 Latency**: < 500ms per checkout operation (median case)
- **P95 Latency**: < 1.5s per checkout operation
- **P99 Latency**: < 2.5s per checkout operation (async orchestration)
- **Error Rate**: < 0.1% (customer-visible failures; validation errors < 0.5%)
- **Idempotency**: 100% (no double-checkouts via idempotency keys)
- **Workflow Durability**: 100% (crash-recovery via Temporal server)
- **Max Throughput**: 10,000 checkouts/minute (at 3 replicas, ~3,300/min per pod)
- **Temporal Namespace Health**: 99.95% availability (critical for workflow durability)

## Key Responsibilities

1. **Temporal Saga Orchestration**: Multi-step checkout workflow with automatic retry and compensation (rollback)
2. **Idempotency Keys**: Client-provided UUID ensures checkout never duplicates; 30-min TTL in PostgreSQL
3. **Service Coordination**: Sequential calls to cart, inventory, pricing, payment, order services with circuit breakers
4. **Distributed Compensation**: On failure, automatically reverses completed steps (e.g., release inventory holds, void payments)
5. **Workflow Status Queries**: Real-time status via REST API (`GET /checkout/{workflowId}/status`)
6. **Circuit Breaker Resilience**: Resilience4j protection; graceful degradation with fallback error messages
7. **Temporal Server Integration**: Durable execution; survives pod crashes; automatic retry logic built-in
8. **Audit Trail**: Every step recorded for forensics and reconciliation

## Deployment

### GKE Deployment
- **Namespace**: money-path
- **Replicas**: 3 (HA, active-active across zones)
- **CPU Request/Limit**: 750m / 2000m (higher than tier 2; orchestration overhead)
- **Memory Request/Limit**: 768Mi / 1.5Gi
- **Startup Probe**: 15s initial delay, 3 failure threshold (Temporal worker registration)
- **Readiness Probe**: `/actuator/health/ready` (checks DB + Temporal server)
- **Liveness Probe**: `/actuator/health/live` (JVM health)
- **Pod Disruption Budget**: minAvailable: 2/3 (always maintain 2 replicas)

### Database
- **Name**: `checkout` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V5) auto-applied on startup
- **Connection Pool**: HikariCP 30 connections (higher for concurrent workflows)
- **Replication**: Synchronous streaming to standby (RTO <1s)
- **Backups**: Daily, immutable (Glacier archival after 30 days)

### Temporal Configuration
- **Temporal Server**: `temporal-server.temporal:7233` (namespace: instacommerce)
- **Worker Pool**: 100 concurrent workflows per pod (300 total across 3 replicas)
- **Timeout Configuration**:
  - Workflow task: 10s
  - Activity timeout: varies by service (cart=2s, inventory=3s, payment=5s, pricing=2s, order=3s)
  - Retry policy: exponential backoff (1s initial, 60s max)
- **Retention**: 7 days (for reprocessing of failed checkouts)
- **Signal Channel**: Real-time status updates via Temporal signals

## Architecture

### Checkout Saga Step Sequence

```
Step 1: Cart Validation (cart-service)
  └─ Validate items still available
  └─ Check cart not expired (< 30 min)
     Timeout: 2s | Retry: 3x exponential backoff
     Compensation: None (read-only)

  ↓

Step 2: Inventory Reservation (inventory-service)
  └─ Reserve items for 10 minutes
  └─ Pessimistic lock prevents overselling
     Timeout: 3s | Retry: 2x
     Compensation: Release inventory hold

  ↓

Step 3: Payment Authorization (payment-service)
  └─ Authorize funds with payment gateway
  └─ Lock funds (not yet captured)
     Timeout: 5s | Retry: 1x (risky)
     Compensation: Void authorization

  ↓

Step 4: Pricing Calculation (pricing-service)
  └─ Calculate final price with tax, shipping, discount
  └─ Validate against authorized amount
     Timeout: 2s | Retry: 2x
     Compensation: None (read-only)

  ↓

Step 5: Order Creation (order-service)
  └─ Create order record with all items
  └─ Publish OrderCreated event (outbox pattern)
     Timeout: 3s | Retry: 1x
     Compensation: Mark order as CANCELLED

  ✅ CHECKOUT COMPLETE
```

### Saga State Machine

```
CREATED (client initiated)
  ↓
CART_VALIDATED (items verified in cart)
  ↓
INVENTORY_HELD (items reserved for 10 min)
  ↓
PAYMENT_AUTHORIZED (funds locked)
  ├─ [SUCCESS] ↓
  │ PRICING_CALCULATED (final amount computed)
  │   ↓
  │ ORDER_CREATED (order persisted)
  │   ↓
  │ COMPLETED ✅
  │
  └─ [FAILURE] ↓
    COMPENSATION_IN_PROGRESS
      ├─ Release inventory hold
      ├─ Void payment authorization
      ├─ Clear cart session
      ↓
    FAILED ❌
```

### Operational Workflows (15+ Use Cases)

**Workflow 1: Happy Path Checkout (Success)**
```
1. Client submits checkout with idempotency_key
2. Checkout orchestrator validates idempotency_key not seen before (30-min TTL check)
3. Starts Temporal workflow; waits for all 5 steps to complete
4. Cart validation succeeds; items confirmed available
5. Inventory reservation succeeds; items held for 10 min
6. Payment authorization succeeds; funds locked (not captured yet)
7. Pricing calculation succeeds; final amount matches authorized
8. Order created; OrderCreated event published to outbox (Kafka subscribers: fulfillment, notification, analytics)
9. Return workflow_id and order_id to client (HTTP 200)
10. Client can poll GET /checkout/{workflow_id}/status for real-time updates
11. Inventory hold expires after 10 min; order fulfillment picks items before expiry
12. Payment captured by payment-service (separate capture call)
```

**Workflow 2: Cart Validation Fails (Items Out of Stock)**
```
1. Cart validation step fails: Item XYZ no longer in stock
2. Temporal workflow caught exception; compensation begins
3. No compensation needed (validation is read-only)
4. Workflow transitions to FAILED state
5. Audit log entry: "Cart validation failed: item_xyz out_of_stock"
6. Return HTTP 400 to client with error: "Item XYZ out of stock; please update cart"
7. Client can retry after removing item from cart
8. Idempotency key cleared after 5 minutes (or explicit API call)
```

**Workflow 3: Inventory Reservation Fails (Concurrent Checkouts)**
```
1. Steps 1-2 succeed (cart validated, attempting inventory reservation)
2. Inventory service returns 409: "Insufficient stock due to concurrent checkouts"
3. Temporal retries 2x with exponential backoff (1s, then 2s)
4. All retries fail (competitor checkout grabbed last units)
5. Compensation triggered: No inventory held, so no release needed
6. Workflow marked FAILED
7. Return HTTP 409 to client: "Item temporarily out of stock; retry in 30 seconds"
8. Client can retry immediately (new workflow if idempotency key differs)
```

**Workflow 4: Payment Authorization Fails (Card Declined)**
```
1. Steps 1-3 succeed (cart validated, inventory held, attempting payment auth)
2. Payment service returns: "Card declined - insufficient funds"
3. Temporal retries 1x (payment failures risky; low retry count)
4. Retry also fails
5. Compensation triggered: Release inventory hold from step 2
6. Workflow marked FAILED
7. Audit log: "Payment authorization failed: card_declined | inventory_released"
8. Return HTTP 402 to client: "Payment failed; please check card and retry"
9. Client redirected to payment method update flow
10. Inventory hold released; items available for other customers
```

**Workflow 5: Pricing Calculation Fails (Tax Service Down)**
```
1. Steps 1-4 succeed (cart valid, inventory held, payment authorized, attempting pricing)
2. Pricing service timeout > 2s (tax service down)
3. Temporal retries 2x
4. Service remains down; retries exhausted
5. Compensation triggered: Void payment authorization, release inventory
6. Workflow marked FAILED
7. Audit log: "Pricing calculation timeout; compensation executed"
8. Return HTTP 503 to client: "System temporarily unavailable; please retry"
9. Payment void will complete eventually (asynchronous)
10. Inventory hold will naturally expire after 10 min if not released before
```

**Workflow 6: Order Creation Fails (Database Constraint Violation)**
```
1. Steps 1-5 succeed, attempting order creation
2. Order service throws: "Duplicate order constraint violation" (duplicate order_id in DB)
3. Temporal retries 1x
4. Retry also fails (order_id already exists; idempotency logic issue)
5. Compensation triggered: Void payment, release inventory, mark order as CANCELLED if created
6. Workflow marked FAILED
7. Audit log: "Order creation failed; compensation executed; escalate to on-call"
8. Alert: SEV-2 incident created (order-service constraint violation)
9. On-call engineer investigates root cause
```

**Workflow 7: Client Cancels Checkout Mid-Flight**
```
1. Checkout initiated; workflow executing step 3 (payment auth in progress)
2. Client calls POST /checkout/{workflow_id}/cancel with timeout 1s
3. Temporal receives cancellation signal
4. Current activity (payment auth) allowed to finish (~500ms more)
5. Workflow does NOT proceed to step 4 (pricing calculation)
6. Compensation triggered immediately: Void payment auth, release inventory
7. Workflow marked CANCELLED
8. Return HTTP 200 to client: "Checkout cancelled; payment voided, inventory released"
9. Audit log: "Checkout cancelled by client; compensation executed"
```

**Workflow 8: Temporal Server Crash During Workflow**
```
1. Workflow executing step 4 (pricing calculation); partial progress saved
2. Temporal server crashes; entire cluster goes down
3. Checkout orchestrator pod detects loss of connection; circuit breaker trips
4. Return HTTP 503 to client: "Checkout service temporarily unavailable"
5. Temporal server recovers after 5 minutes
6. Workflow re-hydrated from event log; resumes from step 4
7. No compensation needed (already completed steps 1-3 are re-verified)
8. Workflow completes; client can poll status
9. Audit log: "Workflow recovered from Temporal server failure"
```

**Workflow 9: Idempotency Key Reuse (Duplicate Checkout)**
```
1. Client submits checkout with idempotency_key = "abc123"
2. Workflow succeeds; order created
3. Client network timeout; didn't receive response
4. Client retries with same idempotency_key = "abc123"
5. Checkout orchestrator detects idempotency key in DB (TTL not expired)
6. Return cached response: Same order_id as first attempt
7. No new workflow spawned; no double-charge
8. Audit log: "Idempotent retry detected for checkout abc123"
```

**Workflow 10-15: Additional Workflows**
- Workflow 10: Surge pricing adjustment during checkout (reprice dynamically)
- Workflow 11: Coupon expiration during checkout (validate TTL)
- Workflow 12: Multi-warehouse inventory split (reserve from multiple zones)
- Workflow 13: International checkout with tariff calculation
- Workflow 14: Gift card partial payment (split payment methods)
- Workflow 15: Express checkout with pre-authorized customer (1-click)

## Performance Optimization Techniques

**1. Temporal Worker Pooling**:
- 100 concurrent workflows per pod (300 total)
- Worker threads sized to handle CPU-bound task coordination
- Activity retries use exponential backoff (1s → 60s max)
- Local activity execution (cart validation) for <1ms latency

**2. Workflow Caching**:
- Cache payment authorization for 5 seconds (duplicate detection)
- Cache inventory reservation status for 2 seconds (avoid re-checks)
- Redis-backed cache with TTL (invalidated on compensation)

**3. Parallel Service Calls** (within Temporal constraints):
- All read-only operations (steps 1, 4) parallelized
- Write operations (steps 2, 3, 5) strictly sequential (transactional safety)
- Circuit breakers prevent cascading failures

**4. Connection Pool Tuning**:
- HikariCP: 30 connections (suitable for 3,300 workflows/min per pod)
- Max lifetime: 30 minutes (connection rotation)
- Idle timeout: 5 minutes (cleanup stale connections)

**5. Database Query Optimization**:
- Idempotency key lookup: Index on (idempotency_key, created_at)
- Audit log: Partition by month for efficient archival

## Monitoring & Alerts (20+ Metrics)

| Metric | Type | Alert Threshold | Description |
|--------|------|---|---|
| `checkout_workflow_duration_ms` | Histogram (p50, p95, p99) | p99 > 2500ms | End-to-end checkout latency |
| `checkout_workflow_success_rate` | Gauge | < 99.9% for 5m | Workflow completion rate |
| `checkout_workflow_compensation_rate` | Counter | > 10/min | Compensation executions |
| `temporal_activity_timeout_rate` | Gauge | > 1% per activity | Activity timeouts by service |
| `checkout_idempotency_cache_hits` | Counter | N/A | Duplicate checkout prevention |
| `payment_authorization_latency_ms` | Histogram | p99 > 5000ms | Critical payment step latency |
| `inventory_reservation_conflicts` | Counter | > 100/min | Concurrent reserve conflicts |
| `circuit_breaker_trip_count` | Counter (by service) | > 5 trips in 5m | Service degradation |
| `temporal_task_queue_lag` | Gauge | > 1000ms | Workflow execution lag |
| `database_connection_pool_active` | Gauge | > 28/30 | Connection pool pressure |
| `idempotency_key_ttl_violations` | Counter | > 0 in 24h | TTL enforcement issues |
| `checkout_cancellation_rate` | Gauge | > 5% | User-initiated cancellations |
| `compensation_success_rate` | Gauge | < 99% | Failed compensation handlers |
| `temporal_namespace_health` | Gauge | 0 = unhealthy | Temporal server connectivity |
| `workflow_event_store_lag` | Gauge | > 5s | Event sourcing backlog |
| `payment_void_latency_ms` | Histogram | p99 > 10000ms | Compensation void speed |
| `inventory_release_failure_rate` | Gauge | > 1% | Inventory compensation failures |
| `order_creation_retry_count` | Counter | > 10/min | Duplicate order retries |
| `checkout_cart_validation_errors` | Counter | > 100/min | Cart-level errors |
| `checkout_pricing_mismatch_alerts` | Counter | > 0/day | Price discrepancy detection |

### Alerting Rules

```yaml
alerts:
  - name: CheckoutWorkflowLatencySpiking
    condition: histogram_quantile(0.99, checkout_workflow_duration_ms) > 2500
    duration: 5m
    severity: SEV-2
    action: "Check downstream services (inventory, payment); review circuit breakers"

  - name: TemporalServerDisconnected
    condition: temporal_namespace_health == 0
    duration: 2m
    severity: SEV-1
    action: "Page on-call; all checkouts blocked without Temporal"

  - name: CompensationFailureSpike
    condition: rate(compensation_failure_count[5m]) > 10
    duration: 5m
    severity: SEV-2
    action: "Payment voids/inventory releases failing; escalate to payments team"

  - name: IdempotencyKeyTTLViolation
    condition: rate(idempotency_key_ttl_violations[1h]) > 0
    duration: 5m
    severity: SEV-3
    action: "Investigate database clock skew or TTL logic bug"

  - name: InventoryReservationConflictSpike
    condition: rate(inventory_reservation_conflicts[5m]) > 100
    duration: 5m
    severity: SEV-3
    action: "High concurrency detected; monitor inventory-service capacity"

  - name: CircuitBreakerTrips
    condition: rate(circuit_breaker_trip_count[5m]) > 5
    duration: 5m
    severity: SEV-2
    action: "Downstream service (payment/inventory) degraded; check health"
```

## Security Considerations

### Threat Mitigations

1. **Idempotency Key Enforcement**: Prevents duplicate checkouts (double-charging) via unique constraint
2. **Payment Authorization Void**: Compensates failed authorizations immediately (risk mitigation)
3. **Inventory Hold Expiry**: Automatic release after 10 min prevents indefinite holds
4. **Temporal Event Sourcing**: All workflow steps recorded; immutable audit trail
5. **Distributed Compensation**: On failure, automated reversals prevent partial state
6. **JWT Audience Scoping**: Only internal checkout-orchestrator can call downstream services
7. **Timeout Enforcement**: Prevents indefinite waiting for stuck services

### Known Risks

- **Temporal Server Compromise**: All workflow state stored in Temporal; tampering impacts checkout atomicity
- **Idempotency Key Bruteforcing**: 30-min TTL; attacker could replay requests within window
- **Payment Authorization Void Delays**: Compensations asynchronous; funds may remain locked for hours
- **Network Partition**: Checkout orchestrator split from Temporal leads to cascading failures
- **Race Condition in Compensation**: Concurrent compensation + user retry could double-void payment

## Troubleshooting (10+ Scenarios)

### Scenario 1: Checkouts Timing Out (P99 > 2500ms)

**Indicators**:
- `checkout_workflow_duration_ms` p99 > 2500ms
- `temporal_task_queue_lag` > 1000ms
- Multiple SEV-2 alerts firing

**Root Causes**:
1. Temporal server overloaded or unhealthy
2. Downstream service (payment-service) responding slowly (> 5s)
3. Database query slow (idempotency key lookup)

**Resolution**:
```bash
# Check Temporal server health
kubectl get pods -n temporal temporal-server-0
kubectl logs -n temporal temporal-server-0 | grep ERROR

# Check downstream service latency
curl -w "@curl-format.txt" http://payment-service:8084/health

# Monitor workflow task queue lag
curl http://checkout-orchestrator-service:8089/actuator/metrics/temporal_task_queue_lag

# Increase Temporal worker pool if needed
TEMPORAL_WORKER_POOL_SIZE=150 (restart pod)

# Identify slow upstream service
curl http://checkout-orchestrator-service:8089/actuator/metrics/temporal_activity_latency_ms?tag=activity:PaymentAuthorization
```

### Scenario 2: Checkouts Failing with 409 Conflict (Inventory Exhausted)

**Indicators**:
- HTTP 409 responses increasing
- `inventory_reservation_conflicts` counter > 100/min
- Low conversion rate during peak traffic

**Root Causes**:
1. Multiple concurrent checkouts competing for same inventory
2. Inventory reservation timeout (3s insufficient for high load)
3. Overbooking (inventory levels miscalculated)

**Resolution**:
```bash
# Check current inventory for high-velocity product
SELECT product_id, available, reserved, allocated
FROM inventory
WHERE product_id IN (SELECT product_id FROM checkout_failures LIMIT 10)
ORDER BY available ASC;

# Increase inventory if overbooking confirmed
UPDATE inventory SET available = available + 100 WHERE product_id = 'xyz123';

# Increase inventory reservation timeout
TEMPORAL_ACTIVITY_TIMEOUT_INVENTORY=5000 (instead of 3000ms)

# Enable inventory redistribution (if multi-warehouse)
POST /admin/inventory/rebalance?from_warehouse=sf&to_warehouse=ny&quantity=500
```

### Scenario 3: Compensation Failures (Payment Voids Stuck)

**Indicators**:
- `compensation_failure_rate` > 1%
- `payment_void_latency_ms` p99 > 10000ms
- Customers report duplicate charges

**Root Causes**:
1. Payment service rejecting void due to invalid authorization_id
2. Network timeout during void compensation
3. Payment gateway circuit breaker open

**Resolution**:
```bash
# Check pending compensation workflows
curl http://checkout-orchestrator-service:8089/admin/workflows?state=COMPENSATION_IN_PROGRESS

# Retry failed compensations manually
curl -X POST http://checkout-orchestrator-service:8089/admin/compensation/retry \
  -d '{"workflow_ids": ["wf-123", "wf-456"]}'

# Check payment service circuit breaker status
curl http://payment-service:8084/actuator/health/circuitBreakers

# Verify authorization_id exists in payment-service
SELECT * FROM payment_authorizations WHERE auth_id = 'auth-xyz' LIMIT 1;

# If missing, mark workflow as manually compensated
UPDATE checkout_workflows SET compensation_status = 'MANUAL_VOID'
WHERE id = 'wf-123' AND compensation_status = 'PENDING';
```

### Scenario 4: Idempotency Key Not Preventing Duplicates

**Indicators**:
- Same order_id created twice with different checkout workflow IDs
- `checkout_idempotency_cache_hits` not incrementing
- Duplicate charges on customer statement

**Root Causes**:
1. Idempotency key TTL expired; second request treated as new
2. Database constraint not enforced (migration issue)
3. Cache miss due to Redis failure

**Resolution**:
```bash
# Verify idempotency key constraint in DB
SELECT constraint_name FROM information_schema.table_constraints
WHERE table_name = 'checkout_workflows' AND constraint_type = 'UNIQUE';

# Check for duplicate idempotency keys
SELECT idempotency_key, COUNT(*) as count
FROM checkout_workflows
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY idempotency_key
HAVING COUNT(*) > 1;

# Check idempotency key TTL
SELECT * FROM checkout_workflows WHERE idempotency_key = 'abc123'
AND created_at > NOW() - INTERVAL '30 minutes';

# Extend TTL if needed
UPDATE checkout_workflows SET idempotency_key_expires_at = NOW() + INTERVAL '1 hour'
WHERE idempotency_key = 'abc123';

# Check Redis cache
redis-cli GET "idempotency:abc123"
```

### Scenario 5: Temporal Event Store Corruption (Workflow Recovery Fails)

**Indicators**:
- Temporal unable to re-hydrate workflows after server restart
- Workflows stuck in PENDING state indefinitely
- Logs: "Event store sequence number mismatch"

**Root Causes**:
1. Temporal event store database corrupted
2. Concurrent write to same workflow_id (rare)
3. Temporal version mismatch (incompatible schema)

**Resolution**:
```bash
# Check Temporal event store health
kubectl exec -it temporal-server-0 -- \
  psql -U temporal -d temporal -c "SELECT COUNT(*) FROM events WHERE workflow_id LIKE 'checkout-%';"

# Identify corrupted workflows
curl http://checkout-orchestrator-service:8089/admin/workflows/status \
  | jq '.[] | select(.state == "PENDING" and .created_at < (now - 3600))'

# Manually mark stuck workflows as FAILED and compensate
curl -X POST http://checkout-orchestrator-service:8089/admin/workflows/mark-failed \
  -d '{"workflow_ids": ["wf-123"], "reason": "temporal_recovery_failed"}'

# Recover manually (contact Temporal support for event store repair)
```

### Scenario 6: Payment Authorization Void Rate High (> 5%)

**Indicators**:
- `compensation_void_count` / `checkout_workflow_count` > 0.05
- Revenue impact (customers see void notifications)
- `payment_void_latency_ms` increasing

**Root Causes**:
1. High failure rate in subsequent steps (inventory, pricing)
2. Temporal timeouts causing unnecessary voids
3. Downstream service circuit breaker open

**Resolution**:
```bash
# Identify which step is failing most
SELECT step, COUNT(*) as failure_count
FROM checkout_workflow_failures
WHERE created_at > NOW() - INTERVAL '1 hour'
GROUP BY step
ORDER BY failure_count DESC;

# Check circuit breaker trip rate by service
curl http://checkout-orchestrator-service:8089/actuator/metrics/resilience4j_circuitbreaker_calls_total

# If inventory service failing: Check stock levels, scaling, DB performance
# If pricing service failing: Check tax service, network connectivity
# If order service failing: Check database locks, replication lag

# Increase timeout for stuck service
TEMPORAL_ACTIVITY_TIMEOUT_INVENTORY=5000 (increase from 3000)
```

### Scenario 7: Workflow Event Store Backlog (Lag > 5 seconds)

**Indicators**:
- `workflow_event_store_lag` > 5000ms
- Workflow completion time increasing
- Temporal database CPU utilization high

**Root Causes**:
1. Temporal event store write-heavy (all workflows replaying history)
2. Database index fragmentation
3. Replication lag to standby

**Resolution**:
```bash
# Check Temporal event store table size
SELECT pg_size_pretty(pg_total_relation_size('events')) FROM pg_tables;

# Analyze event store query performance
EXPLAIN ANALYZE SELECT * FROM events WHERE workflow_id = 'checkout-xyz' ORDER BY sequence DESC;

# Rebuild fragmented indexes
REINDEX INDEX idx_events_workflow_id;
VACUUM ANALYZE events;

# Check replication lag
SELECT NOW() - pg_last_xact_replay_timestamp() as replication_lag;

# If lag high, promote standby and add new replica
```

### Scenario 8: Circuit Breaker Trips (Service Degradation)

**Indicators**:
- `circuit_breaker_trip_count` > 5 in 5 minutes
- Specific service (e.g., payment-service) failing > 50% of requests
- Checkouts fail with HTTP 503

**Root Causes**:
1. Downstream service (payment-service, inventory-service) degraded or down
2. Network connectivity issue (firewall rule, DNS)
3. Cascading failures from dependency

**Resolution**:
```bash
# Check service health
for svc in cart-service inventory-service payment-service pricing-service order-service; do
  echo "=== $svc ==="
  curl http://$svc:${PORT}/actuator/health/ready
done

# Check circuit breaker state
curl http://checkout-orchestrator-service:8089/actuator/health/circuitBreakers | jq '..'

# Manually reset circuit breaker if healthy
curl -X POST http://checkout-orchestrator-service:8089/admin/circuit-breaker/reset \
  -d '{"service": "payment-service"}'

# Check network connectivity
kubectl exec -it deployment/checkout-orchestrator-service -- \
  curl -v http://payment-service:8084/health

# Scale up failing service
kubectl scale deployment payment-service --replicas=5 -n money-path
```

### Scenario 9: Temporal Task Queue Backlog (Worker Starvation)

**Indicators**:
- `temporal_task_queue_lag` > 1000ms
- Workflow execution latency increasing
- Temporal worker CPU utilization at 100%

**Root Causes**:
1. Insufficient worker threads (100 per pod insufficient)
2. Activities taking too long (> 2s)
3. Temporal server overloaded

**Resolution**:
```bash
# Check Temporal worker utilization
curl http://checkout-orchestrator-service:8089/actuator/metrics/temporal_worker_utilization

# Increase worker pool size
TEMPORAL_WORKER_POOL_SIZE=200 (restart pods)

# Identify slow activities
curl http://checkout-orchestrator-service:8089/actuator/metrics/temporal_activity_latency_ms \
  | grep -E "PaymentAuthorization|InventoryReservation" | sort -k2 -rn

# Optimize slow activity (if inventory taking too long)
# Check inventory-service DB performance, connection pool, indexes

# Scale checkout-orchestrator replicas
kubectl scale deployment checkout-orchestrator-service --replicas=5 -n money-path
```

## Production Runbook Patterns

### Runbook 1: Emergency Checkout Rollback (Temporal Server Down)

**SLA**: < 5 min detection to mitigation

1. **Alert Received**: Temporal namespace unhealthy (health check fails)
2. **Immediate Actions**:
   - Verify Temporal server pod status: `kubectl get pods -n temporal`
   - Check Temporal server logs for errors
   - Verify database connectivity to Temporal event store
3. **Mitigation**:
   - If recoverable: Restart Temporal server pod
   - If data corruption: Failover to standby Temporal cluster (if available)
   - If irrecoverable: Switch checkouts to "deferred mode" (queue to async processor)
4. **Communication**:
   - Notify #incidents: "Checkout temporarily queued; orders will process in 5-10 minutes"
5. **Recovery**:
   - Once Temporal healthy: Replay queued checkouts from queue
   - Monitor workflow recovery lag
   - Create post-mortem ticket

### Runbook 2: Payment Authorization Failures (Stripe API Down)

**Scenario**: Payment service circuit breaker open; all authorizations failing

1. **Verification**: Check Stripe API status (stripe.com/status)
2. **Fallback Strategy**:
   - If temporary (< 2 min): Continue retrying with longer timeout
   - If extended (> 10 min): Activate "payment deferral mode" (authorize later via background job)
3. **Customer Communication**:
   - Email: "Checkout experiencing temporary delays; orders will complete within 1 hour"
4. **Mitigations**:
   - Increase payment authorization timeout from 5s to 10s
   - Enable exponential backoff retry (1s, 2s, 4s, 8s)
   - Reduce batch authorization rate to prevent overwhelming Stripe
5. **Recovery**:
   - Monitor Stripe API recovery
   - Replay deferred authorizations once recovered
   - Track revenue impact (deferred orders may expire if not captured within 24h)

### Runbook 3: Inventory Exhaustion (Stock Out)

1. **Detection**: Inventory reservation failing 409 > 100/min
2. **Actions**:
   - Query inventory levels: `SELECT * FROM inventory WHERE available <= 0 ORDER BY demand DESC`
   - Check if overbooking or legitimate exhaustion
   - If error: Correct inventory levels with admin API
   - If legitimate: Notify merchandising team
3. **Customer Experience**:
   - Disable checkout for out-of-stock items (client-side filtering)
   - Show "out of stock" message (not generic error)
4. **Recovery**:
   - Once inventory restocked: Re-enable checkout
   - Notify customers (email with restock notification)

## Configuration

### Environment Variables
```env
SERVER_PORT=8089
SPRING_PROFILES_ACTIVE=gcp

# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/checkout
SPRING_DATASOURCE_HIKARI_POOL_SIZE=30
SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES=5

# Temporal Configuration
TEMPORAL_HOST=temporal-server.temporal
TEMPORAL_PORT=7233
TEMPORAL_NAMESPACE=instacommerce
TEMPORAL_WORKER_POOL_SIZE=100
TEMPORAL_WORKER_THREADS=50

# Timeouts (milliseconds)
CHECKOUT_WORKFLOW_TIMEOUT_MS=30000
TEMPORAL_ACTIVITY_TIMEOUT_CART_MS=2000
TEMPORAL_ACTIVITY_TIMEOUT_INVENTORY_MS=3000
TEMPORAL_ACTIVITY_TIMEOUT_PAYMENT_MS=5000
TEMPORAL_ACTIVITY_TIMEOUT_PRICING_MS=2000
TEMPORAL_ACTIVITY_TIMEOUT_ORDER_MS=3000

# Retry Configuration
TEMPORAL_INITIAL_RETRY_INTERVAL_MS=1000
TEMPORAL_MAX_RETRY_INTERVAL_MS=60000
TEMPORAL_BACKOFF_COEFFICIENT=2.0

# Idempotency Configuration
IDEMPOTENCY_KEY_TTL_MINUTES=30
IDEMPOTENCY_CACHE_SIZE=10000

# Circuit Breaker
RESILIENCE4J_CIRCUITBREAKER_INSTANCES_PAYMENT_FAILURE_THRESHOLD=50
RESILIENCE4J_CIRCUITBREAKER_INSTANCES_PAYMENT_WAIT_DURATION_OPEN_MS=60000

# Observability
OTEL_TRACES_SAMPLER=always_on
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
```

### application.yml
```yaml
checkout:
  orchestrator:
    workflow-timeout-seconds: 30
    idempotency:
      ttl-minutes: 30
      cache-size: 10000
    temporal:
      namespace: instacommerce
      worker-pool-size: 100
      retry-policy:
        initial-interval: 1s
        max-interval: 60s
        backoff-coefficient: 2.0

spring:
  datasource:
    hikari:
      pool-size: 30
      idle-timeout: 5m
  jpa:
    hibernate:
      ddl-auto: validate
```

## Integrations

### Synchronous Calls
| Service | Endpoint | Timeout | Purpose | Critical |
|---------|----------|---------|---------|----------|
| cart-service | http://cart-service:8086/carts/{id}/validate | 2s | Cart validation | Yes |
| inventory-service | http://inventory-service:8083/inventory/reserve | 3s | Inventory hold | Yes |
| payment-service | http://payment-service:8084/payments/authorize | 5s | Payment authorization | Yes |
| pricing-service | http://pricing-service:8096/pricing/calculate | 2s | Final price calculation | Yes |
| order-service | http://order-service:8085/orders | 3s | Order creation | Yes |

### Event Publishers
| Topic | Event | Pattern | Reliability |
|-------|-------|---------|------------|
| checkout.events | CheckoutStarted | Async (Temporal signals) | At-least-once |
| checkout.events | CheckoutCompleted | Async (order outbox) | Exactly-once |
| checkout.events | CheckoutFailed | Async (dead-letter queue) | At-least-once |

## Endpoints

### Public (Health Check)
- `GET /actuator/health` - Liveness probe
- `GET /actuator/health/ready` - Readiness probe (checks DB + Temporal)
- `GET /actuator/metrics` - Prometheus metrics

### Checkout API
- `POST /checkout` - Initiate checkout workflow
- `GET /checkout/{workflowId}/status` - Query workflow status (polling)
- `POST /checkout/{workflowId}/cancel` - Cancel checkout mid-flight
- `GET /checkout/idempotency/{key}` - Lookup previous checkout result by idempotency key

### Admin API (Internal)
- `GET /admin/workflows` - List active workflows (with filters)
- `POST /admin/compensation/retry` - Manually retry failed compensations
- `POST /admin/circuit-breaker/reset` - Reset circuit breaker

### Example Requests

```bash
# Initiate checkout
curl -X POST http://checkout-orchestrator-service:8089/checkout \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{
    "cart_id": "cart-123",
    "user_id": "user-456",
    "payment_method_id": "pm-789",
    "idempotency_key": "uuid-abc123",
    "requested_delivery_date": "2025-03-24"
  }' | jq '.workflow_id'

# Poll checkout status
curl -X GET http://checkout-orchestrator-service:8089/checkout/wf-123abc/status \
  -H "Authorization: Bearer $JWT_TOKEN" | jq '.state, .order_id'

# Cancel in-flight checkout
curl -X POST http://checkout-orchestrator-service:8089/checkout/wf-123abc/cancel \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{"reason": "customer_cancelled"}' | jq '.state'

# Lookup by idempotency key
curl -X GET http://checkout-orchestrator-service:8089/checkout/idempotency/uuid-abc123 \
  -H "Authorization: Bearer $JWT_TOKEN" | jq '.workflow_id, .order_id, .state'
```

## Related Documentation

- **ADR-009**: Temporal Saga Pattern for Checkout Orchestration
- **Wave 36**: Reconciliation Engine Integration (cross-service atomicity)
- **Wave 34**: Per-Service Token Scoping (internal service auth)
- **Runbook**: checkout-orchestrator-service/runbook.md
- **High-Level Design**: diagrams/hld.md
- **Sequence Diagrams**: diagrams/sequence.md (checkout flow)
