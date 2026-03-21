# Checkout Orchestrator Service - Database Details

## Overview

Checkout Orchestrator uses PostgreSQL for:
1. **Idempotency cache** - Store checkout responses for duplicate detection
2. **ShedLock table** - Distributed locking for scheduled tasks

---

## Database Configuration

**Database Name**: `checkout`
**Driver**: PostgreSQL JDBC (org.postgresql.Driver)
**Connection URL**: `jdbc:postgresql://localhost:5432/checkout`
**ORM**: Hibernate JPA
**Migrations**: Flyway

**Connection Pool Settings**:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 5000ms
      max-lifetime: 1800000ms (30 min)
```

---

## Schema Details

### checkout_idempotency_keys

**DDL**:
```sql
CREATE TABLE checkout_idempotency_keys (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key  VARCHAR(255)  NOT NULL,
    checkout_response TEXT         NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_idempotency_key ON checkout_idempotency_keys (idempotency_key);
```

**Entity Class**:
```java
@Entity
@Table(name = "checkout_idempotency_keys")
public class CheckoutIdempotencyKey {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String idempotencyKey;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String checkoutResponse;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant expiresAt;
}
```

**Operations**:
- **Insert**: Called after successful workflow completion
- **Read**: Called at request initiation to check for duplicates
- **Delete**: Called when retrieving expired entries OR on explicit duplicate replacement

**Query Performance**:
```sql
-- Fast path (indexed)
SELECT * FROM checkout_idempotency_keys
WHERE idempotency_key = 'uuid-abc-123'
AND expires_at > now();  -- O(1) via unique index + predicate
```

---

### shedlock

**DDL**:
```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

**Purpose**: Prevents concurrent execution of distributed scheduled tasks (ShedLock pattern)

**Typical Locked Jobs**:
- Idempotency key cleanup (TTL expiration)
- Workflow monitoring

**Flyway Versions**:
- V2__create_shedlock.sql

---

## Flyway Migrations

**Migration Location**: `src/main/resources/db/migration`

| Version | File | Description |
|---------|------|-------------|
| 1 | V1__create_checkout_idempotency_keys.sql | Create idempotency table + indexes |
| 2 | V2__create_shedlock.sql | Create distributed lock table |

**Flyway Configuration**:
```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
    validate-on-migrate: true
```

---

## Data Access Patterns

### CheckoutIdempotencyKeyRepository

```java
@Repository
public interface CheckoutIdempotencyKeyRepository extends JpaRepository<CheckoutIdempotencyKey, UUID> {

    Optional<CheckoutIdempotencyKey> findByIdempotencyKey(String idempotencyKey);

    @Query("DELETE FROM CheckoutIdempotencyKey c WHERE c.expiresAt < :now")
    void deleteExpired(Instant now);
}
```

**Usage**:
```java
// Check for duplicate
Optional<CheckoutIdempotencyKey> cached = repo.findByIdempotencyKey(key);
if (cached.isPresent() && cached.get().getExpiresAt().isAfter(Instant.now())) {
    return deserializeResponse(cached.get().getCheckoutResponse());
}

// Cache response
CheckoutIdempotencyKey entity = new CheckoutIdempotencyKey(
    key,
    serializeResponse(result),
    Instant.now().plus(Duration.ofMinutes(30))
);
repo.save(entity);

// Cleanup
repo.deleteExpired(Instant.now());
```

---

## Performance Considerations

| Operation | Index | Complexity | Expected Latency |
|-----------|-------|-----------|------------------|
| Duplicate check | idx_idempotency_key | O(1) | <5ms |
| Insert response | (id) | O(1) | <10ms |
| Delete expired | (expires_at) | O(n) | 50-500ms (batch) |

**Scaling**:
- For 10,000 checkouts/minute at 30-min TTL: ~300,000 active rows
- Expected row count growth: ~300K rows (manageable with single replica)
- Cleanup job should run every 5-10 minutes

---

## Backup & Disaster Recovery

**Backup**: Standard PostgreSQL backup (WAL archiving + snapshots)
**RTO**: <30 minutes (time to spin up new DB + restore)
**RPO**: <1 minute (WAL archiving)

**Note**: Idempotency cache loss is recoverable:
- Clients can retry checkout with same Idempotency-Key
- Temporal workflow will deduplicate (WorkflowExecutionAlreadyStarted)
- Worst case: User is charged twice (compensated via manual refund)
