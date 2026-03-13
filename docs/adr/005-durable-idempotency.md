# ADR-005: Durable Idempotency Standard

## Status

Accepted

## Date

2026-03-13

## Context

Each InstaCommerce service implements idempotency differently, leading to inconsistent
guarantees and duplicated engineering effort:

1. **payment-service** (gold standard): Uses CAS (compare-and-swap) with an
   `idempotency_key` column protected by a database unique constraint. The application
   checks for an existing key before writing, and the unique constraint acts as a safety
   net for race conditions.

2. **checkout-orchestrator-service**: Maintains a dedicated `checkout_idempotency_keys`
   table with ShedLock-based periodic cleanup of expired entries.

3. **order-service**: Adds an `idempotency_key` column to the orders table with a
   `findByIdempotencyKey()` existence check before insert, but relies solely on the
   application-level check without a unique constraint fallback.

4. **fulfillment-service**: Uses `findByOrderId()` as an existence check and catches
   `DataIntegrityViolationException` as a fallback, but has no explicit idempotency key
   concept.

5. **rider-fleet-service**: Uses `existsByOrderId()` with a `DuplicateAssignmentException`
   thrown on conflict, but has no structured idempotency key format.

This inconsistency creates several problems:

- **Silent duplicates**: Services without unique constraints can create duplicate records
  under concurrent delivery (e.g., Kafka rebalance replays).
- **Inconsistent error handling**: Some services throw exceptions on duplicates (breaking
  Kafka consumers), while others silently succeed.
- **No standard key format**: Each service invents its own key derivation, making
  cross-service tracing difficult.
- **No TTL policy**: Some services accumulate idempotency records indefinitely.

## Decision

**All retry-target write operations must use the "belt and suspenders" idempotency
pattern: a database unique constraint combined with an application-level existence check.**

Implementation requirements:

1. **Dual protection**: Every write operation that may be retried (Kafka consumer handlers,
   Temporal activity methods, REST endpoints with `Idempotency-Key` headers) must:
   - Perform an application-level existence check (`findByIdempotencyKey()`) before
     attempting the write.
   - Have a database unique constraint on the idempotency key column as a race-condition
     safety net.

2. **Key source**: The idempotency key must be derived from an immutable external
   identifier. Acceptable sources are:
   - Kafka: topic + partition + offset (e.g., `orders.packed:12:48832`).
   - Temporal: workflow ID + activity name.
   - REST: client-supplied `Idempotency-Key` header value.

3. **Key format**: All idempotency keys follow the format
   `{service}:{operation}:{external-id}`. Examples:
   - `payment:capture:checkout-wf-abc123`
   - `fulfillment:create-delivery:orders.packed:12:48832`
   - `rider-fleet:assign:orders.packed:12:48832`

4. **Duplicate response behavior**: When a duplicate is detected, the service must return
   the same response as the original operation. Kafka consumers must **not** throw
   exceptions on duplicates -- they must log at `INFO` level and return normally so the
   offset is committed.

5. **TTL policy**: Idempotency records must have a defined time-to-live:
   - Order-related operations: 7 days.
   - Payment-related operations: 30 days.
   - Cleanup is performed by a ShedLock-guarded scheduled task that deletes expired
     records in batches.

## Consequences

### Positive

- Consistent duplicate protection across all services with both application and database
  safeguards.
- Kafka consumers never break on redelivery -- duplicates are handled gracefully.
- Structured key format enables cross-service correlation and tracing in log aggregation.
- Defined TTL prevents unbounded growth of idempotency records.
- New services have a clear, copy-paste-ready pattern to follow.

### Negative

- Services that currently lack a unique constraint (order-service, fulfillment-service,
  rider-fleet-service) require schema migrations to add the constraint.
- The ShedLock cleanup dependency adds a runtime requirement for services that do not
  already use ShedLock.

### Risks

- Adding a unique constraint to an existing table with duplicate data will fail. Services
  must deduplicate existing records before applying the migration.
- The 7-day TTL for order-related keys assumes Kafka consumer lag never exceeds 7 days.
  If a consumer group is paused for longer, replayed messages may not be deduplicated.
  Monitoring consumer lag with alerts at the 5-day mark mitigates this risk.
