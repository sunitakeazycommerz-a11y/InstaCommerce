# Order Service - Resilience & Retry Logic

## No Circuit Breakers

Order Service does NOT use Resilience4j circuit breakers for downstream calls (it queries its own database + publishes outbox events, which are local operations).

---

## Timeout Configuration

```yaml
# HTTP request timeout (if calling other services)
spring:
  mvc:
    async:
      request-timeout: 30000  # 30 seconds

# Database connection timeout
  datasource:
    hikari:
      connection-timeout: 5000ms
```

---

## Transaction Handling

Order creation uses a **single database transaction** to ensure atomicity:

```java
@Transactional
public Order createOrder(CreateOrderRequest request) {
    // 1. Validate data
    // 2. Persist Order entity
    // 3. Persist OrderItems (cascade)
    // 4. Persist OutboxEvent in same transaction
    // 5. Commit all-or-nothing
}
```

**Isolation Level**: READ_COMMITTED (default PostgreSQL)

**Retry Strategy**:
- **Deadlock**: Spring auto-retries 3x (configurable)
- **Unique constraint violation**: No retry (fail fast)
- **Connection pool exhaustion**: No retry (fail fast, alert)

---

## Outbox Event Publishing Guarantees

**Exactly-Once Semantics**:
1. Order persisted in same transaction as OutboxEvent
2. Debezium CDC picks up outbox_events
3. CDC publishes to Kafka (idempotent, keyed by aggregateId)
4. CDC updates sent=true in same transaction as Kafka publish
5. Failure in step 4 → retry on next CDC poll

**Failure Scenarios**:
- **DB commit fails**: OutboxEvent not persisted, no event published, client gets error
- **Kafka publish fails**: CDC retries, exponential backoff
- **Sent=true update fails**: Manual intervention required, but minimal data loss

---

## Concurrency Control

### Optimistic Locking

Orders use version field for optimistic locking:

```java
@Entity
public class Order {
    @Version
    private Long version;  // Auto-incremented on update
}
```

**Behavior**:
- When updating order, if version mismatch → OptimisticLockingFailureException
- Spring retries the transaction (limited retries)
- Client gets 409 Conflict if retry exhausted

**Example - Status Update**:
```java
order.setStatus(OrderStatus.PLACED);
orderRepository.save(order);  // Version incremented 0 → 1

// If another transaction updates order simultaneously:
// org.springframework.orm.jpa.JpaOptimisticLockingFailureException
```

---

## Idempotency Key Constraint

**Unique Constraint**: `UNIQUE (idempotency_key)`

**Effect**:
- Duplicate checkout → duplicate order creation attempt
- PostgreSQL rejects with unique constraint violation
- Spring catches exception, either:
  - Returns 409 Conflict (idempotency key already exists)
  - Or returns cached response if in cache

---

## Health Checks

### Readiness Probe

Depends on database connectivity:

```yaml
management:
  health:
    db:
      enabled: true
```

**Readiness Endpoint**: `/actuator/health/ready`

```bash
curl http://localhost:8085/actuator/health/ready
# {
#   "status": "UP",
#   "components": {
#     "db": {
#       "status": "UP"
#     }
#   }
# }
```

---

## Failure Recovery

### Database Connection Pool Exhaustion

**Symptoms**: All requests timeout waiting for connection

**Recovery**:
```bash
# Kill long-running queries
psql -d orders -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE duration > '60s';"

# Restart service
kubectl rollout restart deployment/order-service -n money-path

# Verify health
curl http://localhost:8085/actuator/health
```

### Deadlock in Order Creation

**Symptoms**: Random 409 errors during high load

**Recovery**:
- Built-in Spring retry mechanism handles most cases
- If persistent, increase transaction isolation level:
```yaml
spring:
  jpa:
    properties:
      hibernate.connection.isolation: 2  # READ_COMMITTED
```

### CDC Lag (Outbox Events Stuck)

**Symptoms**: Fulfillment not starting, no OrderCreated events

**Monitoring**:
```bash
# Check Debezium connector status
curl http://debezium-connect:8083/connectors/order-cdc/status

# Check Kafka offset lag
kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --group order-cdc-group \
  --describe
```

**Recovery**:
1. Stop Debezium connector
2. Manually publish unpublished outbox events (query sent=false rows)
3. Restart connector
4. Verify catch-up

---

## Monitoring & Alerts

### Key Metrics

- `jdbc_connections_active` - Active database connections
- `db_transaction_duration_seconds` - Transaction latency
- `jpa_query_time` - Query execution time
- `order_created_total` - Cumulative orders created
- `order_outbox_lag` - Unpublished outbox events count

### Recommended Alerts

```promql
# Alert if outbox lag > 100 events (CDC behind)
order_outbox_lag > 100 for 5m

# Alert if order creation latency > 2s
db_transaction_duration_seconds{method="createOrder"} > 2 for 5m

# Alert if unique constraint errors > 10/min (duplicate checkouts)
rate(db_constraint_violation_total[1m]) > 0.1
```
