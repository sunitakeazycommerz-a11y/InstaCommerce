# Order Service - Database Details

## Overview

Order Service uses PostgreSQL for:
1. Order master data (orders, order_items, order_status_history)
2. Outbox table for CDC event publishing
3. Audit log for GDPR compliance
4. ShedLock for distributed job coordination

**Database Name**: `orders`
**Migrations**: Flyway V1-V11

---

## Configuration

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orders
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  flyway:
    enabled: true
    locations: classpath:db/migration
  jpa:
    hibernate:
      ddl-auto: validate
```

---

## Migration History

| Version | File | Content |
|---------|------|---------|
| V1 | create_orders.sql | Order entity + enums |
| V2 | create_order_items.sql | Line items table |
| V3 | create_order_status_history.sql | Status audit trail |
| V4 | create_outbox.sql | CDC outbox table |
| V5 | create_audit_log.sql | GDPR audit logging |
| V6 | add_orders_user_erased.sql | GDPR erasure flag |
| V7 | add_delivery_address.sql | Delivery address field |
| V8 | create_shedlock.sql | Distributed locking |
| V9 | add_orders_user_created_at_index.sql | Performance index |
| V10 | add_outbox_envelope_fields.sql | EventEnvelope fields |
| V11 | add_orders_quote_id.sql | Pricing quote reference |

---

## Key Tables

### orders
- **Rows**: ~1M (grows ~500K/month at peak)
- **Partitioning**: None (single table, but could partition by created_at)
- **Backup**: WAL archiving + daily snapshots

### order_items
- **Rows**: ~3M (avg 3 items/order)
- **Index**: idx_order_items_order (order_id) for fast item lookup

### outbox_events
- **Rows**: ~1M active (rows marked sent=true can be archived)
- **Index**: idx_outbox_unsent (sent) - Debezium polling target
- **Retention**: 7 days (for replay capability)

### audit_log
- **Rows**: ~1M
- **Purpose**: GDPR audit trail, cannot be modified post-creation
- **Compliance**: Immutable append-only pattern

---

## Query Patterns

```sql
-- Fast user order lookup (0-5ms with index)
SELECT * FROM orders
WHERE user_id = ?
ORDER BY created_at DESC
LIMIT 20;

-- Unfulfilled orders for warehouse (warehouse queries)
SELECT * FROM orders
WHERE status IN ('PLACED', 'PACKING', 'PACKED')
ORDER BY created_at ASC;

-- Unpublished outbox events (Debezium polling)
SELECT * FROM outbox_events
WHERE sent = false
LIMIT 100;

-- Order audit trail
SELECT * FROM audit_log
WHERE order_id = ?
ORDER BY timestamp DESC;

-- CDC capture for consistency check
SELECT COUNT(*) FROM orders WHERE created_at > now() - interval '1 hour';
SELECT COUNT(*) FROM outbox_events WHERE sent = false;
```

---

## Performance Tuning

**Connection Pool**:
- maximum-pool-size: 20 (handles 5K orders/min with 4ms avg query)
- minimum-idle: 5 (warm pool connections)
- connection-timeout: 5000ms

**Index Strategy**:
```sql
CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at DESC);
CREATE INDEX idx_outbox_unsent ON outbox_events(sent) WHERE sent = false;
CREATE INDEX idx_audit_log_order ON audit_log(order_id, timestamp DESC);
```

**Bulk Operations**:
- Batch insert order_items (native query with VALUES (...), (...), ...)
- Bulk update status (ShedLock prevents concurrent updates)

---

## Backup & Disaster Recovery

**RTO**: 30 minutes (spin up DB + restore from snapshot + catchup WAL)
**RPO**: <1 minute (continuous WAL archiving)

**Procedure**:
1. Latest snapshot to new DB instance
2. Replay WAL logs from S3 archive
3. Verify CDC offset in Kafka offset manager
4. Point application to new DB
5. Resume event processing

---

## Compliance & Data Retention

### GDPR Erasure

When user requests erasure:
```sql
UPDATE orders SET user_erased = true WHERE user_id = ?;
UPDATE audit_log SET user_id = NULL WHERE user_id = ?;
-- Audit logs kept for 7 years (immutable, encrypted user_id)
```

### Data Retention

- Orders: 3 years (for tax/refund purposes)
- Audit logs: 7 years (legal requirement)
- Outbox events: 30 days (for replay capability)

---

## Monitoring

**Prometheus Metrics**:
- `db_connection_active` - Active pool connections
- `db_query_duration_ms` - Query latency
- `db_transaction_total` - Transaction count (success/failure)

**Alerts**:
- Alert if query duration > 500ms (indicates slow query)
- Alert if connection pool > 80% utilized
- Alert if WAL lag > 10 minutes
