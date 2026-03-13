# ADR-004: Event Envelope Standard

## Status

Accepted

## Date

2026-03-13

## Context

Multiple incompatible envelope formats existed across InstaCommerce services. Some
services used snake_case field names, others used camelCase. There was no agreement
on which fields were mandatory versus optional, and different teams placed metadata
in Kafka headers, message bodies, or both.

Wave 24 standardized the use of `source_service` and `correlation_id` in outbox tables
but no formal ADR recorded the canonical envelope shape. As a result, consumers still
had to handle several variations:

1. **Missing fields**: Some producers omitted `schemaVersion` or `sourceService`,
   making it impossible to route messages through schema validation middleware.

2. **Inconsistent casing**: Order-service and payment-service used camelCase while
   fulfillment-service and rider-fleet-service used snake_case, forcing consumers to
   implement dual-deserialization logic.

3. **Header/body split**: Several services duplicated `correlationId` in both Kafka
   headers and the message body, with no guarantee the two values matched.

## Decision

**All events published via the outbox must use the canonical 9-field envelope.**

The canonical envelope fields are:

| Field | Type | Description |
|-------|------|-------------|
| `eventId` | UUID | Unique identifier for this event instance |
| `eventType` | string | Dot-notation event type (e.g., `order.confirmed`) |
| `aggregateId` | string | Identifier of the aggregate root that produced the event |
| `aggregateType` | string | Type of the aggregate root (e.g., `Order`, `Rider`) |
| `schemaVersion` | string | Semantic version of the payload schema (e.g., `1.0.0`) |
| `sourceService` | string | Name of the producing service (e.g., `order-service`) |
| `correlationId` | UUID | End-to-end correlation identifier for distributed tracing |
| `timestamp` | ISO 8601 | Event creation timestamp in UTC (e.g., `2026-03-13T10:15:30Z`) |
| `payload` | object | Domain-specific event data |

Implementation requirements:

1. All envelope field names MUST use camelCase. No snake_case aliases are permitted
   in the message body.

2. Envelope fields MUST be placed in the Kafka message body (value), not in Kafka
   headers. Kafka headers are reserved exclusively for distributed tracing context:
   `trace-id`, `span-id`, and `user-id`.

3. All outbox tables MUST include `source_service` and `correlation_id` columns
   (snake_case per SQL convention) that map to the camelCase envelope fields during
   serialization.

4. Producers MUST populate all 9 fields. Consumers MUST tolerate unknown additional
   fields for forward compatibility but MUST NOT rely on any field outside the
   canonical 9.

## Consequences

### Positive

- Uniform tracing and debugging: every event carries a `correlationId` and
  `sourceService`, enabling end-to-end request tracing without consulting external
  metadata.
- Schema-validatable envelopes: a single JSON Schema can validate the envelope
  structure of any event on the platform, independent of the domain payload.
- Simplified consumer deserialization: one envelope POJO/record works across all
  topics.

### Negative

- Migration required for older consumers that expect snake_case envelope fields.
  These consumers must be updated to deserialize camelCase or use a Jackson
  `PropertyNamingStrategy` adapter during the transition.

### Risks

- Existing services may need to support a dual-format period during migration where
  both camelCase and snake_case envelopes coexist on the same topic. This window
  should be time-boxed and tracked via a migration ticket per service.
- Services that currently place business data in Kafka headers will need refactoring
  to move that data into the `payload` object.
