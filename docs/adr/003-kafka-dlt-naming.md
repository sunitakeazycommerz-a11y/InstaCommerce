# ADR-003: Kafka DLT Naming Convention

## Status

Accepted

## Date

2026-03-13

## Context

The InstaCommerce platform has three incompatible dead-letter topic (DLT) naming
conventions across its microservices:

1. **`{topic}.DLT`** (majority): payment-service, order-service, search-service,
   wallet-loyalty-service, fraud-service, routing-eta-service, cart-service, and
   pricing-service all follow this uppercase-suffix convention.

2. **`{topic}.dlq`** (notification-service): Uses a lowercase `.dlq` suffix, inherited
   from an earlier naming standard.

3. **`{topic}-dlt`** (fulfillment-service, rider-fleet-service): Uses the Spring Boot
   default hyphenated suffix, which was never overridden when these services were
   scaffolded.

This inconsistency causes several operational problems:

- **Monitoring gaps**: No single glob pattern can match all DLT topics for alerting.
- **ACL fragmentation**: Kafka ACLs must enumerate three different patterns instead of one.
- **Replay tooling**: The ops replay script must maintain a per-service lookup table to
  find the correct DLT topic name.
- **Onboarding confusion**: New engineers cannot predict which naming convention a service
  uses without inspecting its `KafkaErrorConfig`.

## Decision

**All services MUST use the `{source-topic}.DLT` naming convention for dead-letter topics.**

Implementation requirements:

1. Each service's `KafkaErrorConfig` (or equivalent error-handling configuration) must
   declare a custom `DeadLetterPublishingRecoverer` with an explicit topic resolver that
   produces `{source-topic}.DLT`.

2. All `KafkaErrorConfig` classes must explicitly declare not-retryable exception types.
   At minimum, `JsonProcessingException` and `IllegalArgumentException` must be classified
   as not-retryable so they are routed to the DLT immediately without exhausting retries.

3. DLT partition assignment must match the source partition to preserve ordering
   guarantees. The `DeadLetterPublishingRecoverer` must use
   `(r, e) -> new TopicPartition(r.topic() + ".DLT", r.partition())` or equivalent.

4. All DLT-publish events must be logged at `WARN` level with the original topic, partition,
   offset, and exception class name to enable correlation with upstream failures.

## Consequences

### Positive

- Single glob pattern `*.DLT` is sufficient for monitoring and alerting across all services.
- Unified Kafka ACL prefix rule (`*->.DLT`) simplifies cluster security configuration.
- A single replay convention works across the entire platform without per-service lookups.
- New services get a clear, unambiguous standard to follow.

### Negative

- notification-service requires a future migration from its existing `.dlq` topics to the
  `.DLT` convention. This must be coordinated with a consumer group reset.

### Risks

- Existing `.dlq` topics (notification-service) and `-dlt` topics (fulfillment-service,
  rider-fleet-service) contain historical poison-pill records. These legacy topics must
  **not** be deleted until they are confirmed empty and all records have been either
  replayed or explicitly discarded.
- During the migration window, services will produce to the new `.DLT` topics while legacy
  topics still exist, requiring temporary dual monitoring.
