# InstaCommerce — Contracts & Event Governance
## Deep Implementation Guide

**Date:** 2026-03-07  
**Audience:** Principal Engineers, Staff Engineers, Platform, SRE  
**Scope:** `contracts/`, all 20 Java + 8 Go event producers/consumers, CI validation, schema governance  
**Depends on:**
- `docs/reviews/contracts-events-review.md` — original findings (July 2025)
- `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-PLATFORM-WISE-2026-03-06.md` §5
- `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md`

---

## Executive verdict

The contract layer is partially designed and unevenly enforced. The `contracts/` module has the right shape: versioned JSON Schemas, gRPC proto definitions, an envelope spec in the README, and a Gradle build that compiles stubs. The outbox-relay-service (Go) produces well-structured envelopes. But the governance layer that connects these artifacts to runtime behaviour is missing. **The documented compatibility policy is real on paper and fictional at runtime.**

Critical findings confirmed by direct code inspection:

| Finding | Evidence | Severity |
|---|---|---|
| `source_service` and `correlation_id` absent from envelope body | `outbox-relay/main.go:buildEventMessage` emits 7 fields; both are absent | 🔴 P0 |
| Envelope field naming split (camelCase vs snake_case) | README says `event_id`; relay emits `eventId` and `id`; EventEnvelope.v1.json requires `id` | 🔴 P0 |
| Dual topic subscriptions as workaround | fraud, wallet, audit subscribe to `order.events` AND `orders.events` both | 🔴 P0 |
| No shared event DTOs consumed from `contracts/` | every consumer defines its own local DTO | 🔴 P0 |
| Three DLQ naming conventions, one service with none | `.DLT`, `.dlq`, `audit.dlq`; wallet has no DLQ | 🔴 P0 |
| No CI breaking-change detection | README claims it; no such job exists in `ci.yml` | 🔴 P0 |
| No consumer idempotency guards | no deduplication on event_id at any consumer | 🟡 P1 |
| No runtime schema validation | JSON Schemas are build-time artifacts only | 🟡 P1 |
| Ghost schemas missing for 4 domains | cart, pricing, wallet, routing-eta publish nothing | 🟡 P1 |
| `additionalProperties: true` on EventEnvelope | silent field drift not detectable | 🟡 P1 |

---

## Part 1 — Contract ownership

### 1.1 Current ownership model

There is no formal ownership layer. The `contracts/` module is a Gradle subproject in `settings.gradle.kts`. It has no `CODEOWNERS` entry, no owning team documented, and no change-class policy. Anyone can commit a schema change that breaks downstream consumers with no required reviewer.

The practical consequence is observed in the data: the `EventEnvelope.v1.json` schema does not match the envelope that `outbox-relay-service` actually builds. That mismatch existed undetected because there is no owner whose job it is to keep them aligned.

### 1.2 Required ownership structure

```
contracts/                         → owned: Platform / Event Governance team
  src/main/proto/                  → sub-owned: service team that owns the gRPC interface
  src/main/resources/schemas/
    orders/                        → sub-owned: order-service team
    payments/                      → sub-owned: payment-service team
    inventory/                     → sub-owned: inventory-service team
    fulfillment/                   → sub-owned: fulfillment-service team
    catalog/                       → sub-owned: catalog-service team
    identity/                      → sub-owned: identity-service team
    fraud/                         → sub-owned: fraud-detection-service team
    rider/                         → sub-owned: rider-fleet-service team
    warehouse/                     → sub-owned: warehouse-service team
    common/                        → owned: Platform (envelope is a platform contract)
```

**Implement via `.github/CODEOWNERS`:**

```
# Envelope and common — Platform owns
/contracts/src/main/resources/schemas/common/   @instacommerce/platform
/contracts/src/main/proto/common/               @instacommerce/platform

# Domain schemas — owning service team must approve
/contracts/src/main/resources/schemas/orders/       @instacommerce/order-team @instacommerce/platform
/contracts/src/main/resources/schemas/payments/     @instacommerce/payment-team @instacommerce/platform
/contracts/src/main/resources/schemas/inventory/    @instacommerce/inventory-team @instacommerce/platform
/contracts/src/main/resources/schemas/fulfillment/  @instacommerce/fulfillment-team @instacommerce/platform
/contracts/src/main/resources/schemas/catalog/      @instacommerce/catalog-team @instacommerce/platform
/contracts/src/main/resources/schemas/identity/     @instacommerce/identity-team @instacommerce/platform
/contracts/src/main/resources/schemas/fraud/        @instacommerce/fraud-team @instacommerce/platform
/contracts/src/main/resources/schemas/rider/        @instacommerce/fleet-team @instacommerce/platform
/contracts/src/main/resources/schemas/warehouse/    @instacommerce/warehouse-team @instacommerce/platform

# Proto definitions
/contracts/src/main/proto/catalog/    @instacommerce/catalog-team @instacommerce/platform
/contracts/src/main/proto/inventory/  @instacommerce/inventory-team @instacommerce/platform
/contracts/src/main/proto/payment/    @instacommerce/payment-team @instacommerce/platform
```

### 1.3 Change classification policy

Not every contract change has the same risk profile. A classification table prevents both under-review (breaking changes merge silently) and over-review (trivial additions blocked for days).

| Change class | Examples | Required approvers | Compatibility window |
|---|---|---|---|
| **C0 — Additive** | Add optional field; add new event type; add enum value | Schema owner (1) | None — backward compatible |
| **C1 — New required field** | Add required field to existing event | Schema owner + Platform + affected consumer teams | 14 days dual publish |
| **C2 — Rename or remove field** | Rename `totalCents` to `totalAmountMinor`; remove `storeId` | Schema owner + Platform + all consumer teams | 90 days — new vN file, both published |
| **C3 — New schema version** | `OrderPlaced.v2.json` (breaking shape) | Schema owner + Platform + CTO approval | 90 days — v1 and v2 live simultaneously |
| **C4 — Topic rename** | `orders.events` → `order-lifecycle.events` | Platform + all producer and consumer teams | 90 days — dual publish to both topics |
| **C5 — Envelope change** | Add/remove/rename top-level envelope field | Platform + all consumer teams | 180 days |

---

## Part 2 — Event envelope standards

### 2.1 The problem: three incompatible envelope definitions

Direct inspection reveals a three-way split between what is documented, what is validated, and what is emitted.

**As documented in `contracts/README.md`:**
```json
{
  "event_id": "...",
  "event_type": "OrderPlaced",
  "aggregate_id": "order-12345",
  "schema_version": "v1",
  "source_service": "order-service",
  "correlation_id": "req-abc-123",
  "timestamp": "2024-01-15T10:30:00Z",
  "payload": { ... }
}
```

**As validated by `contracts/src/main/resources/schemas/common/EventEnvelope.v1.json`:**
```json
required: ["id", "eventType", "aggregateType", "aggregateId", "eventTime", "schemaVersion", "payload"]
```

**As emitted by `services/outbox-relay-service/main.go` (`buildEventMessage`):**
```json
{
  "id":            "...",
  "eventId":       "...",
  "aggregateType": "Order",
  "aggregateId":   "order-12345",
  "eventType":     "OrderPlaced",
  "eventTime":     "2024-01-15T10:30:00.000000000Z",
  "schemaVersion": "v1",
  "payload":       { ... }
}
```

**Gaps in emitted envelope:**
- `source_service` — absent from body (present only in Kafka headers as `event_type`, not `source_service`)
- `correlation_id` — absent entirely (not in headers either)
- Both `id` and `eventId` emitted with the same value — redundant, confusing

**Audit consumer workaround** (confirms the real-world impact):
```java
// DomainEventConsumer.java — derives source from topic name because envelope has no source_service
String sourceService = deriveSourceService(topic); // "order.events" → "order-service"
```

This means the canonical field intended for audit, tracing, and GDPR erasure compliance is not in the event body — it is reverse-engineered from the topic name, which itself has a naming inconsistency bug.

### 2.2 Canonical envelope standard

The following is the authoritative canonical envelope definition. All three representations — README, JSON Schema, and relay code — must converge on this.

```json
{
  "eventId":       "550e8400-e29b-41d4-a716-446655440000",
  "eventType":     "OrderPlaced",
  "aggregateType": "Order",
  "aggregateId":   "order-12345",
  "schemaVersion": "v1",
  "sourceService": "order-service",
  "correlationId": "req-abc-123",
  "eventTime":     "2024-01-15T10:30:00.000000000Z",
  "payload":       { ... }
}
```

**Design decisions with rationale:**

| Decision | Rationale |
|---|---|
| camelCase throughout | Matches existing relay output. Changing to snake_case is a C5 envelope change with 180-day window — not worth it now |
| `eventId` only (drop duplicate `id`) | `id` and `eventId` both carry the same UUID. Remove `id` in next envelope version to eliminate consumer confusion |
| `sourceService` in body (not just headers) | Required for audit, GDPR erasure, distributed trace linking. Kafka headers are not accessible to all consumers (e.g., Go sarama consumers that don't read headers) |
| `correlationId` in body | Required for distributed trace continuity across async boundaries. Must survive DLQ replay where headers may be lost |
| `aggregateType` retained | Enables consumers to discriminate without inspecting payload. Audit, stream-processor, and ML consumers all use it |
| `schemaVersion` stays as string `"v1"` | Matches existing schemas. Do not change to integer — existing consumers do string comparison |

### 2.3 Updated `EventEnvelope.v1.json`

The schema must be tightened: remove `additionalProperties: true`, add missing required fields.

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "EventEnvelope",
  "description": "Standard envelope wrapping every domain event published to Kafka",
  "type": "object",
  "required": [
    "eventId",
    "eventType",
    "aggregateType",
    "aggregateId",
    "schemaVersion",
    "sourceService",
    "correlationId",
    "eventTime",
    "payload"
  ],
  "properties": {
    "eventId":       { "type": "string", "format": "uuid" },
    "eventType":     { "type": "string", "minLength": 1 },
    "aggregateType": { "type": "string", "minLength": 1 },
    "aggregateId":   { "type": "string", "minLength": 1 },
    "schemaVersion": { "type": "string", "pattern": "^v[0-9]+$" },
    "sourceService": { "type": "string", "minLength": 1 },
    "correlationId": { "type": "string" },
    "eventTime":     { "type": "string", "format": "date-time" },
    "payload":       { "type": "object" }
  },
  "additionalProperties": false
}
```

> **Migration note:** Changing `additionalProperties` from `true` to `false` is a C1-class schema change. The relay currently merges payload fields into the top-level envelope (see `buildEventMessage` lines 795–800), which means some events have extra top-level fields from the payload. Before tightening `additionalProperties`, audit which consumer-facing fields are currently promoted to top level and either keep them as explicit envelope fields or stop promoting them.

### 2.4 Required outbox table fields

For `sourceService` and `correlationId` to appear in the emitted envelope, they must exist in the outbox table. Current schema (verified in `order-service` `OutboxEvent.java`):

```
outbox_events: id, aggregate_type, aggregate_id, event_type, payload, created_at, sent
```

**Required additions:**
```sql
ALTER TABLE outbox_events
  ADD COLUMN source_service  TEXT NOT NULL DEFAULT 'unknown',
  ADD COLUMN correlation_id  TEXT;
```

The outbox-relay `outboxEvent` struct and query must be updated accordingly. The `buildEventMessage` function must include these fields in the envelope.

**Migration path:**
1. Add columns with defaults (non-breaking migration)
2. Update `OutboxEvent.java` model in all Java services
3. Update `outboxEvent` struct in `outbox-relay-service/main.go`
4. Update `buildEventMessage` to include `sourceService` and `correlationId`
5. Update each service's outbox write path to populate from request context

### 2.5 Kafka header vs body strategy

Current state: outbox-relay sets `event_id`, `event_type`, `aggregate_type`, `schema_version` as Kafka headers. These duplicate body fields. Consumers that use the Kafka Java SDK read from the body; consumers using low-level readers may read headers. This split creates an implicit protocol.

**Recommendation:** Keep headers as routing/observability metadata (they don't affect consumer correctness), but treat the **envelope body as the authoritative contract**. All fields needed for correct processing must be in the body. Headers are for infrastructure use only (routing, tracing, log aggregation).

Kafka header convention (non-binding, infrastructure only):

| Header key | Value | Purpose |
|---|---|---|
| `event_id` | UUID string | Deduplication at broker/infra layer |
| `event_type` | String | Topic routing, consumer group filtering |
| `source_service` | String | Log aggregation, alerting |
| `schema_version` | `v1` | Backwards-compat broker-side filtering |
| `correlation_id` | String | Distributed trace header propagation |

---

## Part 3 — Compatibility policy

### 3.1 Current policy vs enforcement gap

The `contracts/README.md` defines a clear backward-compatibility policy:

> "Additive changes stay within the current schema version; breaking changes create a new vN schema file"

The JSON Schema filenames encode version (`OrderPlaced.v1.json`). The proto packages encode version (`inventory.v1`). The 90-day deprecation window is documented.

**What exists in CI to enforce this:** nothing. `./gradlew :contracts:build` compiles protos and validates JSON Schema syntax. It does not:
- compare against the base branch to detect field removals
- fail on required→optional downgrades
- check that a consumer can still deserialize a v1 event if a v1 schema was modified
- run any consumer contract tests

This means the compatibility policy is a documentation statement, not an enforcement mechanism.

### 3.2 Compatibility rules by artifact type

#### JSON Schema event files

| Change | Classification | Allowed in existing vN? |
|---|---|---|
| Add optional field (`"required"` not modified) | Additive (C0) | ✅ Yes |
| Add new event type (new schema file) | Additive (C0) | ✅ Yes — new file |
| Widen enum (add value) | Additive (C0) | ✅ Yes |
| Make optional field required | Breaking (C1) | ❌ New vN file |
| Remove field from `"required"` | Potentially breaking | ⚠️ Evaluate consumers first |
| Rename field | Breaking (C2) | ❌ New vN file |
| Change field type | Breaking (C2) | ❌ New vN file |
| Remove field | Breaking (C2) | ❌ New vN file |
| Change `"format"` constraint | Breaking (C2) | ❌ New vN file |

#### Protobuf definitions

| Change | Backward-compatible? |
|---|---|
| Add new optional field (proto3 — all fields optional) | ✅ Yes |
| Add new RPC method | ✅ Yes |
| Add new message type | ✅ Yes |
| Reserve field number after removal | ✅ Required when removing |
| Change field type | ❌ Never without new package version |
| Remove field without reserving number | ❌ Wire-format corruption risk |
| Rename field (same number) | ✅ Binary safe; breaks JSON transcoding |
| Change field number | ❌ Wire-format breaking |
| Remove RPC method | ❌ Breaking for existing callers |

**Rule for protos:** use `buf breaking` in CI against `main`. `buf` is purpose-built for this and handles field-number tracking, reserved fields, and RPC compatibility across all editions.

### 3.3 Versioning lifecycle for breaking changes

```
Day  0:  Breaking change identified → ADR created
Day  1:  New vN schema file committed (e.g. OrderPlaced.v2.json)
Day  1:  Producer begins dual-publishing to both schemas / topics
Day  1:  Consumer migration ticket created and assigned
Day 30:  Consumer migration check-in (required gate)
Day 60:  Consumer migration must be complete
Day 90:  Producer stops publishing v(N-1)
Day 91:  v(N-1) schema file moved to contracts/deprecated/
Day 181: v(N-1) schema file deleted (after 6 months from producer cutover)
```

**Dual-publish implementation** (Java producer example):
```java
@Transactional
public void placeOrder(Order order) {
    // ... business logic ...
    
    // Publish v1 (during compatibility window)
    outboxService.publish(new OutboxEvent()
        .withAggregateType("Order")
        .withAggregateId(order.getId().toString())
        .withEventType("OrderPlaced")
        .withSchemaVersion("v1")
        .withPayload(toV1Payload(order)));
    
    // Publish v2 concurrently (new consumers migrate to this)
    outboxService.publish(new OutboxEvent()
        .withAggregateType("Order")
        .withAggregateId(order.getId().toString())
        .withEventType("OrderPlaced")
        .withSchemaVersion("v2")
        .withPayload(toV2Payload(order)));
}
```

Consumers opt into v2 by filtering on `schemaVersion == "v2"` and ignoring v1 during migration. After the window closes, v1 publishing stops.

### 3.4 The `schemaVersion` field must be validated at the consumer

Every consumer must validate `schemaVersion` before parsing the payload. Silently processing an unknown version is worse than rejecting it — it leads to corrupted state with no observable signal.

```java
// Recommended Java consumer guard
EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
if (!"v1".equals(envelope.getSchemaVersion())) {
    log.warn("Unexpected schema version: {} for event: {}", 
        envelope.getSchemaVersion(), envelope.getEventType());
    sendToDlq(record, "unsupported_schema_version");
    return;
}
```

---

## Part 4 — CI validation

### 4.1 Current CI state

From `.github/workflows/ci.yml`: the `contracts` module is included in the full-validation matrix (`contracts` listed in the Java build). `./gradlew :contracts:build` runs, which compiles protos and produces gRPC stubs. JSON Schema files are syntactically valid (this is a compile-time check from the Gradle classpath).

**What is missing:**

1. **`buf breaking` for proto backward-compatibility** — no such step exists
2. **JSON Schema breaking-change diff** — no schema diff tool configured
3. **Consumer contract tests** — the contracts README references `OrderEventContractTest` but no such test file exists in any service
4. **Topic naming validation** — no check that consumer `@KafkaListener` topic strings match `contracts/` constants
5. **Envelope field completeness check** — no test verifies that a serialized outbox event satisfies `EventEnvelope.v1.json`

### 4.2 CI jobs to add

All of the following should be gated on the `contracts/` path filter in ci.yml.

#### Job 1: Proto breaking-change check

```yaml
# .github/workflows/ci.yml addition
- name: buf breaking check
  if: github.event_name == 'pull_request'
  run: |
    brew install bufbuild/buf/buf   # or use buf-action
    buf breaking contracts/src/main/proto \
      --against '.git#branch=main,subdir=contracts/src/main/proto'
```

`buf breaking` checks: removed fields, changed field types, changed field numbers, removed RPCs, changed request/response message types. It runs in seconds and has zero false positives on additive changes.

#### Job 2: JSON Schema compatibility diff

There is no single standard tool for JSON Schema backward-compat checking, but a custom script using `ajv` or a Python `jsonschema` diff is practical:

```bash
#!/usr/bin/env python3
# scripts/check-schema-compat.py
# Fails if any field is removed from 'required' array or any field type becomes more restrictive
import json, sys, glob

def check_compat(base_path, head_path):
    with open(base_path) as f: base = json.load(f)
    with open(head_path)  as f: head = json.load(f)
    
    base_required = set(base.get("required", []))
    head_required = set(head.get("required", []))
    removed_required = base_required - head_required
    
    if removed_required:
        print(f"BREAKING: {head_path} removed required fields: {removed_required}")
        sys.exit(1)
    
    base_props = base.get("properties", {})
    head_props = head.get("properties", {})
    for field in base_props:
        if field not in head_props:
            print(f"BREAKING: {head_path} removed field '{field}'")
            sys.exit(1)
        base_type = base_props[field].get("type")
        head_type = head_props[field].get("type")
        if base_type != head_type:
            print(f"BREAKING: {head_path} changed type of '{field}' from {base_type} to {head_type}")
            sys.exit(1)

# Compare against git main
import subprocess
for schema in glob.glob("contracts/src/main/resources/schemas/**/*.json", recursive=True):
    result = subprocess.run(["git", "show", f"origin/main:{schema}"], 
                            capture_output=True, text=True)
    if result.returncode == 0:
        import tempfile, os
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
            f.write(result.stdout)
            tmp = f.name
        check_compat(tmp, schema)
        os.unlink(tmp)
```

```yaml
# ci.yml job
- name: JSON Schema compatibility check
  if: github.event_name == 'pull_request'
  run: python3 scripts/check-schema-compat.py
```

#### Job 3: Contract consumer tests

Each consumer service should have a test that deserializes a canonical sample event against the current schema. These tests compile the `contracts` dependency and run deserialization:

```java
// services/fulfillment-service/src/test/java/.../OrderEventContractTest.java
@Test
void canDeserializeOrderPlacedV1() throws Exception {
    // Load canonical sample from contracts module
    String json = ResourceUtils.loadResource(
        "classpath:/schemas/orders/OrderPlaced.v1.sample.json");
    
    assertDoesNotThrow(() -> {
        EventEnvelope envelope = objectMapper.readValue(json, EventEnvelope.class);
        assertThat(envelope.getEventType()).isEqualTo("OrderPlaced");
        assertThat(envelope.getSchemaVersion()).isEqualTo("v1");
        
        OrderPlacedPayload payload = objectMapper.treeToValue(
            envelope.getPayload(), OrderPlacedPayload.class);
        assertThat(payload.getOrderId()).isNotNull();
        assertThat(payload.getTotalCents()).isPositive();
    });
}
```

Sample JSON files (`OrderPlaced.v1.sample.json`) should live alongside the schemas in `contracts/src/main/resources/schemas/orders/` and represent a complete, valid example event. The contracts build should validate samples against their schemas.

#### Job 4: Topic name constant validation

A simple grep-based CI step that prevents topic string literals in consumer code:

```bash
# scripts/check-topic-literals.sh
# Fail if any @KafkaListener annotation uses a string literal instead of TopicNames constant
VIOLATIONS=$(grep -rn '@KafkaListener.*topics.*"[a-z].*\.events"' services/ \
    --include="*.java" | grep -v "TopicNames\." | grep -v "test")

if [ -n "$VIOLATIONS" ]; then
    echo "Topic string literals found. Use TopicNames constants instead:"
    echo "$VIOLATIONS"
    exit 1
fi
```

### 4.3 Recommended CI structure for `contracts/`

```yaml
# Excerpt from ci.yml contracts path filter section
contracts:
  path: contracts/**
  jobs:
    - name: compile-and-stubs
      run: ./gradlew :contracts:build
    - name: proto-breaking-check  
      run: buf breaking ...           # NEW
    - name: schema-compat-check
      run: python3 scripts/check-schema-compat.py   # NEW
    - name: consumer-contract-tests
      run: ./gradlew :services:fulfillment-service:test
               :services:notification-service:test
               :services:fraud-detection-service:test
               :services:wallet-loyalty-service:test
               --tests "*ContractTest"              # NEW
```

---

## Part 5 — Schema registry options and adoption

### 5.1 The case for and against a schema registry

**What a schema registry adds:**
- Runtime schema validation (producer and consumer)
- Central schema versioning with compatibility enforcement modes (BACKWARD, FORWARD, FULL)
- Schema evolution history with audit trail
- Language-agnostic: Java, Go, Python consumers use the same schema store
- Integration with Kafka tooling (Schema Registry wire format, Kafka Streams, ksqlDB)

**What a schema registry costs:**
- Additional stateful service to operate and keep highly available
- Wire format change: Schema Registry adds a 5-byte magic prefix to Kafka messages (schema ID), breaking existing consumers that read raw JSON
- Consumer code migration: all services must switch from raw JSON deserialization to registry-aware deserialization
- Operational complexity: registry becomes a hard dependency of every producer and consumer

### 5.2 Options comparison

| Option | Summary | Pros | Cons | Recommendation |
|---|---|---|---|---|
| **A: File-based schemas + CI enforcement (current path)** | Keep JSON Schema files in `contracts/`. Add CI breaking-change checks. Add shared event DTOs. | No new infra. Gradual migration. Works today. | No runtime validation. Schema divergence only caught at deploy time via CI. | **Do now (Wave 1)** |
| **B: Confluent Schema Registry (self-hosted)** | Add Schema Registry container (fits in existing Kafka/Zookeeper stack). Java consumers use `KafkaAvroDeserializer` or JSON Schema serializer. | Industry standard. Runtime enforcement. Compatibility modes. Kafka-native tooling. | Wire format change requires coordinated migration. New service to operate. Avro learning curve. License considerations. | **Plan for Wave 3** |
| **C: Apicurio Registry (Red Hat, Apache-licensed)** | Open-source alternative to Confluent SR. Supports JSON Schema, Avro, Protobuf. REST API for schema management. | Fully open-source. Supports JSON Schema natively (no Avro required). Same wire format option. | Less tooling ecosystem than Confluent. | **Alternative to B if licensing is a concern** |
| **D: Custom validation layer** | Add a Kafka deserializer that validates the JSON envelope body against the schema from `contracts/` classpath resource before delivering to consumer. | No new service. Reuses existing JSON Schema files. | Still file-based — schema updates require redeploy. Does not validate producer output. | **Short-term bridge if B/C timeline is long** |

### 5.3 Recommended adoption path

**Wave 1 (now):**
- Implement Option A: CI enforcement + shared event DTOs + envelope repair
- This is the foundational work that makes Option B possible later

**Wave 3 (after transactional core is hardened):**
- Evaluate Confluent Schema Registry vs Apicurio based on team license stance
- Pilot on one domain (e.g., `orders.events`) with dual-format publishing during migration
- Migrate consumers one at a time using the compatibility window policy (§3.3)

**Wire format migration strategy for Schema Registry adoption:**
1. Enable Schema Registry in `docker-compose.yml` and staging first
2. For 90 days, producers publish both raw JSON (existing) and Schema-Registry-encoded format to separate topic versions: `orders.events` (legacy) and `orders.events.v2` (registry-encoded)
3. Consumers migrate to `orders.events.v2` one by one
4. After all consumers migrated: flip `orders.events` to registry-encoded, retire `orders.events.v2`

### 5.4 Option D: Custom envelope validator (short-term bridge)

For teams that cannot wait for Wave 3 but want runtime protection today:

```java
// contracts/src/main/java/.../kafka/EnvelopeValidatingDeserializer.java
public class EnvelopeValidatingDeserializer implements Deserializer<EventEnvelope> {
    
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory
        .getInstance(SpecVersion.VersionFlag.V7);
    private static final JsonSchema ENVELOPE_SCHEMA = loadEnvelopeSchema();
    
    @Override
    public EventEnvelope deserialize(String topic, byte[] data) {
        JsonNode node = MAPPER.readTree(data);
        Set<ValidationMessage> errors = ENVELOPE_SCHEMA.validate(node);
        if (!errors.isEmpty()) {
            throw new EnvelopeValidationException(topic, errors);
        }
        return MAPPER.treeToValue(node, EventEnvelope.class);
    }
    
    private static JsonSchema loadEnvelopeSchema() {
        InputStream is = EnvelopeValidatingDeserializer.class
            .getResourceAsStream("/schemas/common/EventEnvelope.v1.json");
        return FACTORY.getSchema(is);
    }
}
```

This deserializer can be wired into any Spring Kafka consumer via `spring.kafka.consumer.value-deserializer` and routes malformed events to the DLQ before they reach application code.

---

## Part 6 — Producer-consumer coordination

### 6.1 Current producer inventory

| Producer | Mechanism | Emits to topics | Schema files | `sourceService` in envelope? |
|---|---|---|---|---|
| order-service (Java) | outbox_events → outbox-relay (Go) | `orders.events` | ✅ 6 schemas | ❌ missing |
| payment-service (Java) | outbox_events → outbox-relay (Go) | `payments.events` | ✅ 5 schemas | ❌ missing |
| inventory-service (Java) | outbox_events → outbox-relay (Go) | `inventory.events` | ✅ 4 schemas | ❌ missing |
| fulfillment-service (Java) | outbox_events → outbox-relay (Go) | `fulfillment.events` | ✅ 4 schemas | ❌ missing |
| catalog-service (Java) | outbox_events → outbox-relay (Go) | `catalog.events` | ✅ 2 schemas | ❌ missing |
| identity-service (Java) | outbox_events → outbox-relay (Go) | `identity.events` | ✅ 1 schema (`UserErased`) | ❌ missing |
| rider-fleet-service (Java) | outbox_events → outbox-relay (Go) | `rider.events` | ✅ 5 schemas | ❌ missing |
| warehouse-service (Java) | outbox_events → outbox-relay (Go) | `warehouse.events` | ✅ 3 schemas | ❌ missing |
| fraud-detection-service (Java) | outbox infrastructure | `fraud.events` | ✅ 1 schema | ❌ missing |
| payment-webhook-service (Go) | Direct Kafka produce | `payments.events` | ✅ via payment schemas | ❌ missing |
| cart-service (Java) | outbox infrastructure present | **nothing published** | ❌ | N/A |
| pricing-service (Java) | outbox infrastructure present | **nothing published** | ❌ | N/A |
| wallet-loyalty-service (Java) | outbox infrastructure present | **nothing published** | ❌ | N/A |
| routing-eta-service (Java) | outbox infrastructure present | **nothing published** | ❌ | N/A |

### 6.2 Current consumer map

| Consumer | Topics | Local DTO? | Shared DTO from contracts/? | DLQ |
|---|---|---|---|---|
| fulfillment-service | `orders.events`, `identity.events` | `OrderEvent`, `OrderPlacedPayload`, `EventEnvelope` | ❌ | ❌ none in consumer |
| notification-service | `orders.events`, `payments.events`, `fulfillment.events`, `identity.events` | `EventEnvelope` (local) | ❌ | `.dlq` suffix |
| fraud-detection-service | `order.events`+`orders.events`, `payment.events`+`payments.events` | `JsonNode` | ❌ | `.DLT` suffix |
| wallet-loyalty-service | `order.events`+`orders.events`, `payment.events`+`payments.events` | `JsonNode` (raw string) | ❌ | ❌ **none** |
| audit-trail-service | 16 topics (both `order.events` and `orders.events`) | `JsonNode` | ❌ | `audit.dlq` (custom) |
| search-service | `catalog.events` | local `CatalogProductEvent` | ❌ | `.DLT` suffix |
| pricing-service | `catalog.events` | local `EventEnvelope`+`CatalogProductEvent` | ❌ | `.DLT` suffix |
| routing-eta-service | `rider.events`, `rider.location.updates` | local `RiderEventPayload` | ❌ | `.DLT` suffix |
| rider-fleet-service | `fulfillment.events` | local `FulfillmentEventPayload` | ❌ | `.DLT` suffix |
| stream-processor-service (Go) | `order.events`, `payments.events`, `inventory.events` | Go struct `OrderEvent` | ❌ | Go DLQ topic |
| cdc-consumer-service (Go) | multiple topics | Go struct | ❌ | `KafkaDLQTopic` config |

### 6.3 Shared event DTOs (the key fix)

Every consumer must migrate from local DTO definitions to shared DTOs published in `contracts/`. This is the single highest-leverage change: it turns a runtime correctness problem into a compile-time type-safety problem.

**Proposed `contracts/` structure:**

```
contracts/src/main/java/com/instacommerce/contracts/
├── kafka/
│   ├── EventEnvelope.java               # canonical envelope record
│   └── TopicNames.java                  # all topic name constants
├── events/
│   ├── orders/
│   │   ├── OrderPlacedPayload.java
│   │   ├── OrderCancelledPayload.java
│   │   ├── OrderPackedPayload.java
│   │   ├── OrderDispatchedPayload.java
│   │   ├── OrderDeliveredPayload.java
│   │   └── OrderFailedPayload.java
│   ├── payments/
│   │   ├── PaymentAuthorizedPayload.java
│   │   ├── PaymentCapturedPayload.java
│   │   ├── PaymentFailedPayload.java
│   │   ├── PaymentRefundedPayload.java
│   │   └── PaymentVoidedPayload.java
│   ├── inventory/
│   │   ├── StockReservedPayload.java
│   │   ├── StockConfirmedPayload.java
│   │   ├── StockReleasedPayload.java
│   │   └── LowStockAlertPayload.java
│   ├── fulfillment/
│   │   ├── PickTaskCreatedPayload.java
│   │   ├── OrderPackedPayload.java
│   │   ├── RiderAssignedPayload.java
│   │   └── DeliveryCompletedPayload.java
│   ├── catalog/
│   │   ├── ProductCreatedPayload.java
│   │   └── ProductUpdatedPayload.java
│   └── identity/
│       └── UserErasedPayload.java
└── dto/
    ├── MoneyDto.java                    # amount_minor + currency
    └── PageResponse.java                # shared pagination
```

**`EventEnvelope.java`:**
```java
package com.instacommerce.contracts.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
    String eventId,
    String eventType,
    String aggregateType,
    String aggregateId,
    String schemaVersion,
    String sourceService,
    String correlationId,
    String eventTime,
    JsonNode payload
) {}
```

**`TopicNames.java`:**
```java
package com.instacommerce.contracts.kafka;

public final class TopicNames {
    public static final String ORDERS      = "orders.events";
    public static final String PAYMENTS    = "payments.events";
    public static final String INVENTORY   = "inventory.events";
    public static final String FULFILLMENT = "fulfillment.events";
    public static final String CATALOG     = "catalog.events";
    public static final String IDENTITY    = "identity.events";
    public static final String RIDER       = "rider.events";
    public static final String WAREHOUSE   = "warehouse.events";
    public static final String FRAUD       = "fraud.events";
    public static final String WALLET      = "wallet.events";
    public static final String PRICING     = "pricing.events";
    public static final String CART        = "cart.events";

    // DLQ/DLT convention: canonical topic + ".dlt" (lowercase)
    public static final String ORDERS_DLT   = ORDERS   + ".dlt";
    public static final String PAYMENTS_DLT = PAYMENTS + ".dlt";
    // ... etc

    private TopicNames() {}
}
```

**Migration approach for each consumer:**

1. Add `contracts` as a Gradle dependency: `:contracts` already in the root project
2. Replace local `EventEnvelope` record with `com.instacommerce.contracts.kafka.EventEnvelope`
3. Replace local payload DTOs with `com.instacommerce.contracts.events.*Payload` classes
4. Replace `@KafkaListener(topics = "orders.events")` with `@KafkaListener(topics = TopicNames.ORDERS)`
5. Delete local DTO files

Wallet-loyalty-service additionally requires moving from raw string `consume(String message)` to `consume(ConsumerRecord<String, String> record)` and proper DLQ wiring.

### 6.4 Topic naming fix: singular/plural resolution

The fraud, wallet, and audit services currently subscribe to **both** `order.events` and `orders.events` as a workaround. The audit service subscribes to 16 topics including duplicates:

```java
// DomainEventConsumer.java — current, broken workaround
topics = {"order.events", "orders.events", "payment.events", "payments.events", ...}
```

The outbox-relay correctly maps all `Order` aggregate types to `orders.events`. The singular-form subscriptions (`order.events`) were a hedge against misconfigured outbox records. Once `TopicNames` constants are adopted and the relay mapping is verified correct, the singular-form subscriptions can be removed.

**Cutover procedure:**
1. Verify via `kafka-consumer-groups.sh --describe` that `order.events` topic has zero messages / no active producers
2. Remove singular-form topics from consumer `@KafkaListener` annotations
3. Deploy and monitor DLQ for unexpected increases

---

## Part 7 — Replay handling

### 7.1 Current replay capabilities

There is no replay mechanism of any kind. Dead-letter messages accumulate in DLQ topics and are never processed unless a developer manually re-publishes them. This has real operational consequences:

- A transient network failure causes wallet-loyalty-service to drop a loyalty credit silently (no DLQ at all — messages are lost)
- A schema validation failure during a deployment causes notification events to pile up in `.dlq` with no automated resolution
- There is no audit-trail of what was replayed, when, and by whom

### 7.2 Required replay infrastructure

Replay operates at three levels:

**Level 1 — DLQ consumer with exponential backoff (highest priority)**

Spring Kafka's `DefaultErrorHandler` with `FixedBackOff` already exists in some services. Extend it with exponential backoff and a maximum retry limit:

```java
@Bean
public CommonErrorHandler kafkaErrorHandler(KafkaOperations<String, String> ops) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(ops,
        (record, ex) -> new TopicPartition(record.topic() + ".dlt", record.partition()));
    ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
    backOff.setMaxElapsedTime(60_000L);           // max 60 seconds of retrying
    backOff.setMaxInterval(30_000L);              // cap at 30s between retries
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        JsonParseException.class,                  // don't retry malformed JSON
        EnvelopeValidationException.class          // don't retry invalid envelopes
    );
    return handler;
}
```

Non-retryable exceptions (malformed JSON, unsupported schema version) go immediately to DLQ. Retryable exceptions (transient DB failures, downstream timeouts) get exponential backoff.

**Level 2 — Manual DLQ replay via admin endpoint**

Each service that consumes Kafka events should expose an admin endpoint for DLQ replay:

```java
// POST /admin/dlq/replay?topic=orders.events.dlt&limit=100
@PostMapping("/admin/dlq/replay")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public ReplayResult replayDlq(
    @RequestParam String topic,
    @RequestParam(defaultValue = "100") int limit,
    @RequestParam(defaultValue = "false") boolean dryRun) {
    return dlqReplayService.replay(topic, limit, dryRun);
}
```

The replay service reads from the DLQ topic, re-publishes to the original topic, and records the replay action in the audit trail. The `dry-run` flag previews what would be replayed without publishing.

**Level 3 — Source-of-truth replay from outbox (the strongest guarantee)**

For scenarios where the DLQ itself is corrupt or the event needs to be regenerated from source, the outbox-relay-service can be asked to re-relay specific outbox records:

```sql
-- Mark specific outbox events as un-sent for replay
UPDATE outbox_events
SET sent = false
WHERE aggregate_type = 'Order'
  AND aggregate_id = 'order-12345'
  AND event_type = 'OrderPlaced'
  AND created_at BETWEEN '2024-01-15 10:00:00' AND '2024-01-15 11:00:00';
```

This reuses the exactly-once semantics of the outbox relay without requiring a separate replay system. It also ensures the replayed message has the correct `event_id` from the original outbox record, enabling consumer idempotency deduplication.

**Important:** Outbox table retention must be long enough to support this. Current `OutboxCleanupJob` implementations (verified in rider-fleet-service) delete `sent = true` records. Recommendation: retain `sent = true` records for 7 days minimum before cleanup.

### 7.3 Consumer idempotency for replay safety

Replay is only safe if consumers are idempotent. Currently no consumer has an idempotency guard. Every consumer that performs a state mutation must deduplicate on `eventId`.

**Standard pattern (to be added to all consumers):**

```java
@Component
public class OrderEventConsumer {
    
    private final ProcessedEventRepository processedEvents;
    
    @KafkaListener(topics = TopicNames.ORDERS, groupId = "fulfillment-service")
    @Transactional
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
        
        // Idempotency check — transactional insert to prevent duplicate processing
        if (processedEvents.existsByEventId(envelope.eventId())) {
            log.debug("Skipping duplicate event: {}", envelope.eventId());
            return;
        }
        processedEvents.markProcessed(envelope.eventId(), envelope.eventType());
        
        // ... process event ...
    }
}
```

```sql
-- Migration: add to each Java service that has a Kafka consumer
CREATE TABLE processed_events (
    event_id    UUID        PRIMARY KEY,
    event_type  TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_processed_events_ts ON processed_events (processed_at);
-- Cleanup: delete records older than 7 days via ShedLock job
```

The 7-day retention window must be longer than the outbox replay window and the DLQ backoff window.

### 7.4 Replay and event ordering

Kafka guarantees ordering within a partition, keyed by `aggregateId`. Replay breaks this guarantee: a replayed `OrderPlaced` event may arrive after `OrderDelivered` for the same order.

**Consumer guards required for replay safety:**

```java
// Consumers that depend on event ordering must check state before acting
if ("OrderPlaced".equals(envelope.getEventType())) {
    // Only create pick task if order doesn't already have one
    if (!pickTaskRepository.existsByOrderId(orderId)) {
        pickService.createPickTask(payload);
    }
}
```

This is state-based idempotency: the consumer is safe to re-run because it checks current state before applying the event, not just whether it has seen the event ID before.

---

## Part 8 — Governance model

### 8.1 Event governance lifecycle

```
┌─────────────────────────────────────────────────────────────────┐
│                    EVENT GOVERNANCE LIFECYCLE                    │
└─────────────────────────────────────────────────────────────────┘

AUTHOR                         PLATFORM REVIEW              CONSUMERS

  │                                  │                           │
  │  1. Propose schema change        │                           │
  │  ─ Create ADR                    │                           │
  │  ─ Classify change (C0–C5)       │                           │
  │     ──────────────────────────►  │                           │
  │                                  │  2. Review & classify     │
  │                                  │  ─ Check compatibility    │
  │                                  │  ─ Identify consumers     │
  │                                  │     ──────────────────►   │
  │                                  │                           │  3. Consumer sign-off
  │  4. Commit schema file           │                           │  (required for C1+)
  │  ─ New vN file (if C2+)          │                           │
  │  ─ Update CODEOWNERS             │                           │
  │  ─ CI must pass                  │                           │
  │                                  │  5. Merge + announce      │
  │                                  │  ─ Compatibility window   │
  │                                  │    starts (if C1+)        │
  │                                  │     ──────────────────►   │
  │                                  │                           │  6. Consumer migration
  │                                  │                           │  (within window)
  │                                  │                           │
  │  7. Cutover                      │                           │
  │  ─ Stop publishing vN-1          │                           │
  │  ─ Move to deprecated/           │                           │
  │     ──────────────────────────►  │                           │
  │                                  │  8. Window close          │
  │                                  │  ─ Monitor DLQ spike      │
  │                                  │  ─ Confirm consumer ok    │
```

### 8.2 Schema registry of record (until Confluent/Apicurio is adopted)

Until a runtime schema registry is deployed, `contracts/src/main/resources/schemas/` is the schema registry of record. Its integrity is maintained by:

1. **CODEOWNERS**: changes require owning team + Platform approval
2. **CI**: proto breaking check + JSON Schema compat check on every PR
3. **Immutability rule**: once a vN schema file is merged to `main`, its `required` array and field types are frozen. Only additive changes are allowed. Breaking changes create vN+1.
4. **Deprecation directory**: `contracts/src/main/resources/schemas/deprecated/` holds schemas that have been superseded but are within their compatibility window
5. **Deletion rule**: deprecated schemas are deleted only after 6 months post-cutover AND after confirming zero consumer traffic on the old event type via Kafka topic metrics

### 8.3 Missing schemas to create immediately

The following domains have confirmed event producers but no JSON Schema definitions in `contracts/`. These must be created before any consumer relies on these events for correctness:

| Domain | Events to define | Producer |
|---|---|---|
| `identity/` | `UserRegistered.v1.json` | identity-service |
| `rider/` | `RiderLocationUpdated.v1.json`, `RiderStatusChanged.v1.json` | rider-fleet-service |
| `cart/` | `CartUpdated.v1.json`, `CartAbandoned.v1.json` | cart-service (when publishing starts) |
| `pricing/` | `PriceChanged.v1.json` | pricing-service |
| `wallet/` | `WalletCredited.v1.json`, `WalletDebited.v1.json`, `LoyaltyPointsEarned.v1.json` | wallet-loyalty-service |
| `routing-eta/` | `ETAUpdated.v1.json` | routing-eta-service |
| `config/` | `FeatureFlagToggled.v1.json` | config-feature-flag-service (**compliance-critical**) |
| `checkout/` | `CheckoutStarted.v1.json`, `CheckoutCompleted.v1.json`, `CheckoutFailed.v1.json` | checkout-orchestrator-service |
| `notification/` | `NotificationSent.v1.json`, `NotificationFailed.v1.json` | notification-service |

### 8.4 Money field standard

The existing review (`contracts-events-review.md §1.2`) identified a floating-point money inconsistency. Direct schema inspection confirms:

- `OrderPlaced.v1.json` — `totalCents` as `integer` ✅
- `PaymentAuthorized.v1.json` — `amountCents` as `integer` ✅
- `PaymentCaptured.v1.json` — verify `amount` field (review noted it was `number` / float)
- Proto `Money` — `amount_minor` as `int64` ✅

**Rule:** all money amounts in event schemas **must** use integer minor units (cents/paise) with an accompanying `currency` string. The field name must be `*Cents` (e.g., `totalCents`, `amountCents`, `refundAmountCents`). Fields named `amount` as a floating-point `number` type are a P0 bug.

Add an explicit validation step to the schema compat CI check:
```python
# Fail if any schema uses "type": "number" for a field ending in "amount" or "price"
for field_name, field_def in props.items():
    if any(w in field_name.lower() for w in ["amount", "price", "total", "cost", "fee"]):
        if field_def.get("type") == "number":
            print(f"BREAKING: {schema_path} field '{field_name}' uses float type for money")
            sys.exit(1)
```

---

## Part 9 — Migration options and tradeoffs

### 9.1 Migration option comparison

The following options address the same root problem (unenforced contract governance) with different cost and risk profiles.

#### Option A: Incremental hardening (recommended)

**What:** Fix the envelope, add shared DTOs, add CI checks, standardize DLQ naming. No new infrastructure.

**Steps:**
1. Fix `buildEventMessage` in outbox-relay to include `sourceService` and `correlationId` (requires outbox table migration)
2. Add shared event DTOs to `contracts/`
3. Migrate consumers to shared DTOs one at a time (2-3 services per sprint)
4. Add CI: `buf breaking`, JSON Schema compat check, topic literal check
5. Standardize DLQ naming to `.dlt` across all services
6. Add wallet DLQ (P0 — currently drops messages)

**Timeline:** 6–8 weeks  
**Risk:** Low — each step is independently deployable and reversible  
**Tradeoff:** No runtime schema validation. Malformed events will still reach consumers if CI is bypassed (e.g., hotfix deployments). Mitigation: require CI pass for all schema changes via branch protection.

#### Option B: Schema registry first

**What:** Deploy Confluent Schema Registry (or Apicurio) before fixing anything else. Migrate all producers and consumers to registry-aware serialization.

**Steps:**
1. Add schema registry to `docker-compose.yml` and Helm charts
2. Register all existing schemas in the registry
3. Migrate producers to use `KafkaJsonSchemaSerializer` (or Avro)
4. Migrate consumers to use `KafkaJsonSchemaDeserializer`

**Timeline:** 12–16 weeks (wire format migration is high-coordination)  
**Risk:** High — wire format change is a coordinated cutover for all 30+ producers and consumers  
**Tradeoff:** Provides runtime validation and prevents invalid events at producer time. But the coordination cost is very high and the option A fixes (shared DTOs, DLQ repair, envelope repair) are still needed regardless. Option B without Option A is not sufficient.

**Recommendation:** Option A now, Option B in Wave 3. The Option A fixes must precede Option B because:
- shared DTOs are required for type-safe registry deserialization
- envelope standardization is required before registry registration
- DLQ repair is needed regardless of registry adoption

#### Option C: Contract testing via Pact

**What:** Implement consumer-driven contract testing using the Pact framework.

**What Pact provides:** Consumers write "pacts" (expected interaction descriptions). A pact broker publishes these. Producers verify against pacts before releasing. If a producer change breaks a consumer's expected contract, the producer's CI fails.

**Fit assessment for InstaCommerce:**
- Pact is primarily designed for HTTP/REST contracts, with event (message) contract support added later
- Pact works well when consumers and producers are in separate repos or deployments
- In a monorepo with shared `contracts/` module, consumer-driven contract tests (§4.2 Job 3) provide similar guarantees at lower operational cost
- Pact broker is another stateful service to operate

**Recommendation:** Do not add Pact. The combination of shared event DTOs (compile-time) + consumer contract tests in CI (test-time) + envelope schema validation (runtime Option D) covers the same ground at lower infrastructure cost. Revisit if the codebase splits into multiple repos.

#### Option D: Event sourcing + event store

**What:** Replace outbox+Kafka with an event store (EventStoreDB, Axon Server, or custom PostgreSQL append-only table) as the system of record, projecting to Kafka for consumers.

**This is not recommended** for InstaCommerce at this stage. The current outbox pattern is correct for the problem domain. An event store would solve the replay problem more elegantly but would require rewriting the persistence model of 12+ services. The governance, envelope, and DLQ problems are solvable without this architectural change.

### 9.2 Rollback strategy for each migration step

| Migration step | Rollback strategy |
|---|---|
| Add `source_service`/`correlation_id` to outbox table | Column has `DEFAULT 'unknown'` — all existing code works without changes. Rolling back the relay code leaves columns empty but doesn't break anything. |
| Migrate consumer to shared DTO | Keep old local DTO class for one release. If deserialization fails, revert the DTO import — consumer behaviour is identical. |
| Add CI breaking-change check | If the check has a false positive, add the schema to an allowlist in `check-schema-compat.py`. Does not affect runtime. |
| Standardize DLQ to `.dlt` | Old DLQ topics still exist and have messages. Monitor both `orders.events.DLT` and `orders.events.dlt` during transition. |
| Remove dual topic subscriptions in fraud/wallet | Re-add singular-form topic subscription if monitoring shows missed messages. |
| Deploy schema registry | Wire format change is the hard part. Dual-publish strategy (§5.3) ensures rollback: stop producing to the new topic and consumers fall back to legacy topic. |

---

## Part 10 — Implementation wave plan

### Wave 1 (Weeks 1–4) — Stop the bleeding

**Objective:** Fix the active correctness risks. Nothing in this wave requires coordination beyond the owning service team.

| Task | Owner | Effort | Priority |
|---|---|---|---|
| Fix wallet-loyalty DLQ (zero message loss) | wallet team | 1 day | P0 |
| Add `source_service` + `correlation_id` to all outbox tables (migration) | platform | 2 days | P0 |
| Update `outbox-relay buildEventMessage` to include new fields | platform | 1 day | P0 |
| Fix `EventEnvelope.v1.json` to match actual emitted envelope | platform | 1 day | P0 |
| Add `EventEnvelope.java` + `TopicNames.java` to `contracts/` | platform | 1 day | P0 |
| Migrate fraud + wallet `@KafkaListener` to `TopicNames.ORDERS/PAYMENTS` | fraud team, wallet team | 1 day each | P0 |
| Add `buf breaking` CI job | platform | 0.5 day | P0 |
| Add JSON Schema compat CI check script | platform | 1 day | P0 |

### Wave 2 (Weeks 5–8) — Structural hardening

| Task | Owner | Effort | Priority |
|---|---|---|---|
| Create shared event payload DTOs for all 6 existing domains | platform | 3 days | P1 |
| Migrate fulfillment-service consumer to shared DTOs | fulfillment team | 1 day | P1 |
| Migrate notification-service consumer to shared DTOs | notification team | 1 day | P1 |
| Migrate audit-trail consumer to shared DTOs (remove dual-topic workaround) | audit team | 2 days | P1 |
| Add consumer idempotency (`processed_events` table) to fulfillment, notification, wallet | per team | 1 day each | P1 |
| Standardize all DLQ names to `.dlt` | all consumer teams | 0.5 day each | P1 |
| Add exponential backoff error handler base class to shared library | platform | 1 day | P1 |
| Add missing schemas: `UserRegistered`, `FeatureFlagToggled` (compliance) | identity team, config team | 0.5 day each | P1 |
| Add DLQ replay admin endpoint scaffold | platform | 2 days | P1 |

### Wave 3 (Weeks 9–16) — Registry and replay

| Task | Owner | Effort | Priority |
|---|---|---|---|
| Evaluate and select schema registry (Confluent vs Apicurio) | platform + CTO | 1 week | P2 |
| Deploy schema registry to staging | platform | 3 days | P2 |
| Pilot dual-publish on `orders.events` | order team + platform | 1 week | P2 |
| Add remaining missing schemas (cart, pricing, wallet, routing-eta, checkout) | per team | 0.5 day each | P2 |
| Add consumer contract tests to CI for all 9 consumer services | platform | 1 week | P2 |
| Implement outbox-based replay (mark-as-unsent mechanism + operator tooling) | platform | 1 week | P2 |
| Migrate remaining consumers to shared DTOs (search, pricing, routing-eta, rider-fleet) | per team | 1 day each | P2 |
| Add `CODEOWNERS` file for contracts ownership | platform | 0.5 day | P2 |

---

## Appendix A — Envelope field mapping (current vs target)

| Field | README spec | EventEnvelope.v1.json required? | Relay emits (body) | Relay emits (header) | Target |
|---|---|---|---|---|---|
| `eventId` | `event_id` | `id` (required) | `eventId` + `id` | `event_id` | `eventId` (body) |
| `eventType` | `event_type` | `eventType` (required) | `eventType` | `event_type` | `eventType` (body) |
| `aggregateType` | not in README | `aggregateType` (required) | `aggregateType` | `aggregate_type` | `aggregateType` (body) |
| `aggregateId` | `aggregate_id` | `aggregateId` (required) | `aggregateId` | — | `aggregateId` (body) |
| `schemaVersion` | `schema_version` | `schemaVersion` (required) | `schemaVersion` | `schema_version` | `schemaVersion` (body) |
| `sourceService` | `source_service` | absent | **absent** | **absent** | `sourceService` (body) — **must add** |
| `correlationId` | `correlation_id` | absent | **absent** | **absent** | `correlationId` (body) — **must add** |
| `eventTime` | `timestamp` | `eventTime` (required) | `eventTime` | — | `eventTime` (body) |
| `payload` | `payload` | `payload` (required) | `payload` | — | `payload` (body) |

---

## Appendix B — DLQ topology (current vs target)

| Service | Current DLQ | Target DLQ |
|---|---|---|
| fulfillment-service | none | `fulfillment-orders.events.dlt` |
| notification-service | `orders.events.dlq` | `notification-orders.events.dlt` |
| fraud-detection-service | `order.events.DLT` | `fraud-orders.events.dlt` |
| wallet-loyalty-service | **none — messages dropped** | `wallet-orders.events.dlt` |
| audit-trail-service | `audit.dlq` | `audit.domain.events.dlt` |
| search-service | `catalog.events.DLT` | `search-catalog.events.dlt` |
| pricing-service | `catalog.events.DLT` | `pricing-catalog.events.dlt` |
| routing-eta-service | `rider.events.DLT` | `routing-rider.events.dlt` |
| rider-fleet-service | `fulfillment.events.DLT` | `fleet-fulfillment.events.dlt` |

**Naming convention:** `{consumer-service-prefix}-{source-topic}.dlt`

Including the consumer service prefix in the DLQ name prevents different consumer services from sharing a DLQ, which makes forensics, alerting, and replay ownership unambiguous.

---

## Appendix C — Go consumer envelope alignment

The Go stream-processor and cdc-consumer services define their own event structs:

```go
// stream-processor-service/processor/order_processor.go
type OrderEvent struct {
    EventType   string     `json:"eventType"`
    OrderID     string     `json:"orderId"`
    // ... domain fields at top level (not in "payload" sub-object)
}
```

This assumes the relay's payload-promotion behaviour (lines 795–800 in `buildEventMessage`), where payload fields are hoisted to the top-level envelope object. If that promotion is removed as part of the envelope fix, Go consumers will break.

**Resolution options:**
1. **Keep payload promotion** in the relay, but document it as a supported feature (not a bug). Go consumers continue to read fields from the top level.
2. **Remove payload promotion** and update Go consumers to read from `payload` sub-object. This is cleaner but requires coordinated deployment.

**Recommendation:** Remove payload promotion in a future wave (Wave 3). Until then, document it explicitly in the relay code and in `contracts/README.md`. Consumers that depend on promoted fields should add a comment citing this behaviour so it is visible during migration.

---

*This guide is authoritative for the `contracts/` module. Changes to the canonical envelope, compatibility policy, or CI validation must be reflected here first.*
