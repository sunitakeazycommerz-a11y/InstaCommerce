# Audit Trail Service — Deep Compliance & Architecture Review

**Service:** `services/audit-trail-service`
**Reviewer:** Senior Compliance & Audit Architect
**Date:** 2025-07-02
**Stack:** Spring Boot 3 / Java 21 / PostgreSQL (partitioned) / Kafka / Flyway / ShedLock
**Risk Rating:** 🟡 MEDIUM — solid foundation with several critical gaps before production compliance certification

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Files Reviewed](#2-files-reviewed)
3. [Business Logic Review](#3-business-logic-review)
   - 3.1 [Append-Only Semantics](#31-append-only-semantics)
   - 3.2 [Event Ingestion](#32-event-ingestion)
   - 3.3 [Multi-Topic Consumer](#33-multi-topic-consumer)
   - 3.4 [Search / Query](#34-search--query)
   - 3.5 [Export](#35-export)
   - 3.6 [Partition Strategy](#36-partition-strategy)
4. [SLA & Compliance Review](#4-sla--compliance-review)
   - 4.1 [Data Integrity](#41-data-integrity)
   - 4.2 [Retention](#42-retention)
   - 4.3 [PCI-DSS Req 10](#43-pci-dss-req-10)
   - 4.4 [GDPR Art 30](#44-gdpr-art-30)
   - 4.5 [SOC 2](#45-soc-2)
5. [Missing Features & Recommendations](#5-missing-features--recommendations)
   - 5.1 [Cryptographic Chaining](#51-cryptographic-chaining)
   - 5.2 [S3/GCS Archival](#52-s3gcs-archival)
   - 5.3 [Alert Triggers](#53-alert-triggers)
   - 5.4 [Data Subject Access (GDPR DSAR)](#54-data-subject-access-gdpr-dsar)
   - 5.5 [Correlation ID Tracking](#55-correlation-id-tracking)
6. [Consolidated Finding Matrix](#6-consolidated-finding-matrix)
7. [Remediation Roadmap](#7-remediation-roadmap)

---

## 1. Executive Summary

The audit-trail-service provides a functional foundation for audit logging in the Instacommerce Q-commerce platform. It ingests events from 7 Kafka domain topics and an HTTP API, persists them in a partitioned PostgreSQL table with append-only DDL constraints, supports paginated search via JPA Specifications, and offers synchronous CSV export.

**Strengths:**
- Append-only enforced at both DDL (`REVOKE UPDATE, DELETE`) and JPA (`updatable = false`) layers
- Monthly range partitioning with automated future partition creation
- Multi-topic Kafka consumer with DLQ for poison messages
- JWT-based RBAC with role separation (SYSTEM/SERVICE for ingestion, ADMIN for query/export)
- ShedLock-based distributed locking for partition maintenance
- Bean validation on all ingestion DTOs
- OpenTelemetry tracing and Prometheus metrics integrated

**Critical Gaps (must fix before compliance certification):**
- ❌ **No cryptographic chaining / tamper evidence** — audit records can be silently altered by a DBA with superuser access
- ❌ **No async export** — synchronous CSV streaming will OOM or timeout on large date ranges (billions of rows)
- ❌ **No GDPR DSAR endpoint** — no way to export all records for a specific data subject
- ❌ **No archival to cold storage** — detached partitions are abandoned, not moved to S3/GCS
- ❌ **No anomaly alerting** — no detection of suspicious audit patterns
- ⚠️ **Missing Kafka topics** — `notification.events`, `search.events`, `pricing.events`, `promotion.events` not consumed
- ⚠️ **Retention is detach-only** — old partitions are detached but never dropped or archived, leading to storage leak
- ⚠️ **Export has no size guard** — `Integer.MAX_VALUE` passed as page size for export criteria

---

## 2. Files Reviewed

| # | File | Purpose |
|---|------|---------|
| 1 | `build.gradle.kts` | Dependencies: Spring Boot 3, JPA, Kafka, Security, Flyway, ShedLock, JJWT, Resilience4j, OTel |
| 2 | `Dockerfile` | Multi-stage build, JDK 21, ZGC, non-root user, health check |
| 3 | `V1__create_audit_events.sql` | Core table DDL: partitioned by `created_at`, indexes, `REVOKE UPDATE/DELETE` |
| 4 | `V2__create_audit_retention_policy.sql` | Partition creation functions: `create_audit_partition()`, `ensure_future_audit_partitions()` |
| 5 | `V3__create_shedlock.sql` | ShedLock table for distributed job coordination |
| 6 | `application.yml` | Full configuration: datasource, Kafka, JWT, partition policy, OTel, actuator |
| 7 | `AuditTrailServiceApplication.java` | Boot entry point with `@EnableConfigurationProperties` |
| 8 | `AuditEvent.java` | JPA entity — all fields `updatable = false`, builder pattern |
| 9 | `AuditEventBuilder.java` | Builder with required-field validation (`eventType`, `sourceService`, `action`) |
| 10 | `AuditEventRepository.java` | Spring Data JPA + `JpaSpecificationExecutor` for dynamic queries |
| 11 | `AuditIngestionService.java` | Single + batch ingestion with Hibernate batch inserts |
| 12 | `AuditQueryService.java` | Dynamic specification-based search with pagination |
| 13 | `AuditExportService.java` | Synchronous streaming CSV export with 500-row batches |
| 14 | `PartitionMaintenanceJob.java` | Scheduled monthly job: create future partitions, detach old ones |
| 15 | `DomainEventConsumer.java` | Multi-topic Kafka consumer with DLQ routing |
| 16 | `KafkaConfig.java` | Producer factory for DLQ forwarding |
| 17 | `AuditProperties.java` | Type-safe config: JWT, partition retention, DLQ topic |
| 18 | `ShedLockConfig.java` | Distributed lock provider for scheduled jobs |
| 19 | `SecurityConfig.java` | Filter chain: stateless JWT, role-based path authorization |
| 20 | `JwtAuthenticationFilter.java` | RSA JWT validation with public key from Secret Manager |
| 21 | `RestAuthenticationEntryPoint.java` | 401 JSON response handler |
| 22 | `RestAccessDeniedHandler.java` | 403 JSON response handler |
| 23 | `AuditIngestionController.java` | POST `/audit/events` and `/audit/events/batch` |
| 24 | `AdminAuditController.java` | GET `/admin/audit/events` (search) and `/admin/audit/export` (CSV) |
| 25 | `AuditEventRequest.java` | Ingestion DTO with Bean Validation constraints |
| 26 | `AuditEventResponse.java` | Response DTO record |
| 27 | `AuditSearchCriteria.java` | Search parameters with pagination bounds (`size` capped at 100) |
| 28 | `ErrorResponse.java` | Standardized error envelope |
| 29 | `ErrorDetail.java` | Field-level error detail |
| 30 | `GlobalExceptionHandler.java` | Central exception handling: validation, access denied, fallback |
| 31 | `ApiException.java` | Custom application exception with HTTP status |

---

## 3. Business Logic Review

### 3.1 Append-Only Semantics

**Rating: 🟢 GOOD (with caveats)**

#### What's Implemented

**DDL Layer (`V1__create_audit_events.sql`, line 30):**
```sql
REVOKE UPDATE, DELETE ON audit_events FROM audit_app;
```
This revokes mutation privileges from the `audit_app` database role. Any `UPDATE` or `DELETE` issued by the application connection will fail with a permission error.

**JPA Layer (`AuditEvent.java`, lines 21–58):**
Every column annotation includes `updatable = false`:
```java
@Column(name = "event_type", nullable = false, length = 100, updatable = false)
private String eventType;
```
Hibernate will refuse to generate `UPDATE` statements for these fields. Combined with no setter methods exposed, the entity is effectively immutable at the ORM level.

**Repository Layer (`AuditEventRepository.java`):**
No custom `@Modifying` queries exist. Only `findBy*` and specification-based reads. The `save()` method from `JpaRepository` is only used for `INSERT` (new entities with generated UUIDs).

#### Gaps & Risks

| # | Issue | Severity | Detail |
|---|-------|----------|--------|
| 1 | **Superuser bypass** | 🔴 CRITICAL | `REVOKE` only applies to the `audit_app` role. A PostgreSQL superuser or the `postgres` role can still `UPDATE`/`DELETE`. This is unavoidable at the DB level but must be mitigated with cryptographic chaining (see §5.1). |
| 2 | **No REVOKE on partition children** | 🟡 MEDIUM | `REVOKE` is issued on the parent `audit_events` table. PostgreSQL propagates privileges to partitions created *after* the `REVOKE`, but manually-created partitions or restored partitions may not inherit this. The `create_audit_partition()` function does not explicitly `REVOKE` on new partitions. |
| 3 | **Flyway migration role** | 🟡 MEDIUM | `REVOKE` targets `audit_app`, but the Flyway migration itself runs as the datasource user configured in `application.yml` (`AUDIT_DB_USER`, defaulting to `postgres`). If the app connects as `postgres` (not `audit_app`), the `REVOKE` has **zero effect**. The deployment must ensure the app runtime uses the `audit_app` role. |
| 4 | **No row-level trigger guard** | 🟢 LOW | A defense-in-depth `BEFORE UPDATE OR DELETE` trigger that raises an exception would protect against accidental grants. Not present. |

#### Recommendation
```sql
-- Add to V1 or new migration: defense-in-depth trigger
CREATE OR REPLACE FUNCTION prevent_audit_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % operations are prohibited', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();
```

---

### 3.2 Event Ingestion

**Rating: 🟡 ADEQUATE**

#### Dual Ingestion Paths

1. **HTTP API** (`AuditIngestionController.java`):
   - `POST /audit/events` — single event, returns `201 CREATED` with response body
   - `POST /audit/events/batch` — list of events, returns `201` with no body
   - Protected by `hasAnyRole("SYSTEM", "SERVICE")` — only internal services can push

2. **Kafka Consumer** (`DomainEventConsumer.java`):
   - Listens on 7 topics: `identity.events`, `catalog.events`, `order.events`, `payment.events`, `inventory.events`, `fulfillment.events`, `rider.events`
   - Transforms generic JSON envelope into `AuditEventRequest`
   - Failures routed to DLQ (`audit.dlq`)

#### Gaps

| # | Issue | Severity | Detail |
|---|-------|----------|--------|
| 1 | **Missing domain topics** | 🟡 MEDIUM | A Q-commerce platform typically has: `notification.events`, `search.events`, `pricing.events`, `promotion.events`, `customer-support.events`, `returns.events`, `warehouse.events`. None of these are consumed. Events from these domains will NOT appear in the audit trail. |
| 2 | **No idempotency** | 🟡 MEDIUM | Neither the HTTP API nor the Kafka consumer implements idempotent ingestion. If Kafka redelivers a message (consumer crash before commit), the same event will be stored twice. The `id` is auto-generated (`gen_random_uuid()`), so there's no natural dedup key. Should use event's own ID from the envelope as the `id` if present. |
| 3 | **Batch ingestion lacks size limit** | 🟡 MEDIUM | `POST /audit/events/batch` accepts a `List<AuditEventRequest>` with no `@Size(max=...)` constraint. A caller could send 100K events in one request, causing OOM or long transaction holds. |
| 4 | **No retry on Kafka consumer** | 🟢 LOW | Failed messages go directly to DLQ with no retry. Spring Kafka's `DefaultErrorHandler` with backoff is not configured. Transient DB failures will permanently lose events to DLQ. |
| 5 | **`action` field duplicated** | 🟢 LOW | In `DomainEventConsumer.transformToAuditEvent()` line 89, `eventType` is passed as both the `eventType` and `action` parameter: `eventType, sourceService, actorId, actorType, aggregateType, aggregateId, eventType, ...`. The `action` field should likely be derived from a separate field in the envelope (e.g., `envelope.get("action")`). |
| 6 | **No schema validation on Kafka payloads** | 🟢 LOW | The consumer accepts raw JSON and does best-effort field extraction. Malformed events with missing `eventType` will be stored as `UNKNOWN`. No schema registry integration. |

---

### 3.3 Multi-Topic Consumer

**Rating: 🟡 ADEQUATE**

#### Configuration Analysis

**`DomainEventConsumer.java` (lines 36–48):**
```java
@KafkaListener(
    topics = { "identity.events", "catalog.events", "order.events",
               "payment.events", "inventory.events", "fulfillment.events",
               "rider.events" },
    groupId = "${spring.kafka.consumer.group-id}",
    concurrency = "3"
)
```

**`application.yml` (lines 36–43):**
```yaml
spring.kafka.consumer:
  group-id: ${AUDIT_KAFKA_GROUP:audit-trail-service}
  auto-offset-reset: earliest
  max.poll.interval.ms: 600000   # 10 minutes
  max.poll.records: 100
```

#### Assessment

| Aspect | Status | Detail |
|--------|--------|--------|
| **Consumer group** | 🟢 OK | Dedicated group `audit-trail-service`. Won't compete with other consumers. |
| **Auto-offset-reset** | 🟢 OK | `earliest` ensures no events are missed on first deployment or group reset. |
| **Concurrency** | 🟡 CONCERN | `concurrency = "3"` creates 3 consumer threads, but with 7 topics, partition assignment depends on topic partition counts. If each topic has 3+ partitions, some threads will handle many partitions. Consider increasing concurrency or using separate `@KafkaListener` groups for high-volume topics (`order.events`, `payment.events`). |
| **Poll interval** | 🟢 OK | 600s (10 min) is generous for batch processing of 100 records. |
| **Commit strategy** | 🟡 CONCERN | Default `enable.auto.commit = true` (not explicitly overridden). Auto-commit can lose events if the consumer crashes after commit but before DB persist. Should use manual commit (`AckMode.MANUAL_IMMEDIATE` or `RECORD`). |
| **Topic hardcoding** | 🟡 CONCERN | Topics are hardcoded in the annotation. Adding a new domain service requires a code change and redeployment. Should be externalized to `application.yml` with `${audit.kafka.topics}`. |
| **Error handling** | 🟢 OK | DLQ routing via `sendToDlq()` prevents consumer blocking. But no retry before DLQ (see §3.2). |
| **Deserialization** | 🟢 OK | Uses `StringDeserializer` + manual `ObjectMapper` parsing. Resilient to schema changes — won't crash on unknown fields. |

---

### 3.4 Search / Query

**Rating: 🟡 ADEQUATE — Performance concerns at scale**

#### Supported Query Dimensions

From `AuditQueryService.buildSpecification()` and `AdminAuditController.search()`:

| Dimension | Supported | Index Backing |
|-----------|-----------|---------------|
| `actorId` | ✅ | `idx_audit_actor_created` (composite with `created_at`) |
| `resourceType` + `resourceId` | ✅ | `idx_audit_resource` |
| `sourceService` | ✅ | `idx_audit_source_created` (composite with `created_at`) |
| `eventType` | ✅ | `idx_audit_event_type` |
| `fromDate` / `toDate` (time range) | ✅ | `idx_audit_created` + partition pruning |
| `correlationId` | ❌ (not in search criteria) | `idx_audit_correlation` (partial, exists but unused in queries) |
| `action` | ❌ | No index |
| `ipAddress` | ❌ | No index |
| Full-text search on `details` JSONB | ❌ | No GIN index |

#### Performance at Billions of Rows

| Concern | Severity | Detail |
|---------|----------|--------|
| **No mandatory time-range filter** | 🔴 HIGH | Queries without `fromDate`/`toDate` will scan ALL partitions. At billions of rows, this will cause full-table scans across all partitions. The API should **require** at least one time bound, or default to last 30 days. |
| **No cursor-based pagination** | 🟡 MEDIUM | Uses offset-based pagination (`PageRequest.of(page, size)`). At high offsets (page 10000+), PostgreSQL must scan and discard all preceding rows. Should use keyset/cursor pagination (`WHERE created_at < :lastSeen ORDER BY created_at DESC LIMIT :size`). |
| **`size` capped at 100** | 🟢 OK | `AuditSearchCriteria` compact constructor caps `size` to 100 max. Good for query protection. |
| **No JSONB search** | 🟡 MEDIUM | The `details` JSONB column holds the event payload but is not searchable. For compliance investigations ("find all events where payment amount > 10000"), a GIN index on `details` with `jsonb_path_ops` would be needed. |
| **Missing `correlationId` in search** | 🟡 MEDIUM | `AuditSearchCriteria` does not include `correlationId` despite the index existing. Cross-service transaction tracing is impossible through the API. |

#### Recommendation
```java
// Enforce mandatory time range for large-table safety
public AuditSearchCriteria {
    if (page < 0) page = 0;
    if (size <= 0 || size > 100) size = 20;
    if (fromDate == null) fromDate = Instant.now().minus(Duration.ofDays(30));
    if (toDate == null) toDate = Instant.now();
    if (Duration.between(fromDate, toDate).toDays() > 366) {
        throw new IllegalArgumentException("Query range cannot exceed 366 days");
    }
}
```

---

### 3.5 Export

**Rating: 🔴 CRITICAL GAPS**

#### What's Implemented (`AuditExportService.java`)

- Synchronous streaming CSV via `HttpServletResponse.getWriter()`
- Batched reads of 500 rows at a time using paginated `findAll(spec, pageRequest)`
- Proper CSV escaping (quotes, commas, newlines)
- ISO-8601 timestamp formatting
- Header row with all columns (excluding `details` JSONB)

#### Critical Issues

| # | Issue | Severity | Detail |
|---|-------|----------|--------|
| 1 | **Synchronous export — no async/background job** | 🔴 CRITICAL | For compliance audits covering months of data (potentially millions of rows), the HTTP request will timeout. There is no async job mechanism. The export should use a background job that writes to S3/GCS and returns a download URL. |
| 2 | **`Integer.MAX_VALUE` as size** | 🔴 CRITICAL | `AdminAuditController.exportCsv()` line 61 passes `Integer.MAX_VALUE` as the `size` parameter to `AuditSearchCriteria`. However, the compact constructor in `AuditSearchCriteria` caps it to 20 (`if (size <= 0 \|\| size > 100) size = 20`). **This means export is silently capped at 20 rows per page, not 500.** The `EXPORT_BATCH_SIZE` of 500 is used in `PageRequest.of(page, EXPORT_BATCH_SIZE)`, overriding the criteria size — so the actual behavior works, but the criteria size is misleading and unused. |
| 3 | **No export size limit** | 🟡 MEDIUM | No guard against exporting the entire audit history. A careless admin request without filters could attempt to stream billions of rows, exhausting DB connections and memory. |
| 4 | **`details` column excluded from CSV** | 🟡 MEDIUM | The JSONB `details` field (which holds the actual event payload) is not included in the CSV export. For compliance, the payload is often the most important part. |
| 5 | **No export audit trail** | 🟡 MEDIUM | The export action itself is not logged as an audit event. Who exported what data and when? This is a SOC 2 requirement. |
| 6 | **Single transaction for entire export** | 🟡 MEDIUM | `@Transactional(readOnly = true)` holds a single transaction for the entire export duration. For large exports, this means a long-lived read-only transaction, which can cause PostgreSQL MVCC issues (preventing autovacuum on the table). |
| 7 | **No CSV format validation** | 🟢 LOW | User-agent strings could contain injection payloads. While CSV injection is low-risk for server-generated files, formulas starting with `=`, `+`, `-`, `@` should be escaped for Excel users. |

---

### 3.6 Partition Strategy

**Rating: 🟢 GOOD — with archival gap**

#### What's Implemented

**Migration V2 — Partition Creation:**
- `create_audit_partition(partition_date DATE)` — creates `audit_events_YYYY_MM` child partitions
- `ensure_future_audit_partitions(months_ahead INT DEFAULT 3)` — creates current + next N months
- Initial migration creates partitions for current month + 3 months ahead

**PartitionMaintenanceJob.java:**
- Runs monthly at 2:00 AM on the 1st: `@Scheduled(cron = "0 0 2 1 * *")`
- ShedLock ensures single-instance execution across replicas: `lockAtMostFor = "PT30M"`
- **Creates** future partitions by calling `ensure_future_audit_partitions()`
- **Detaches** old partitions beyond `retention-days` (default: 90 days)

#### Assessment

| Aspect | Status | Detail |
|--------|--------|--------|
| **Auto-creation** | 🟢 OK | Future partitions auto-created 3 months ahead. If the job fails, there's a 3-month buffer before inserts fail. |
| **Partition naming** | 🟢 OK | Consistent `audit_events_YYYY_MM` naming convention. |
| **Detach logic** | 🟢 OK | Uses `pg_inherits` to discover partitions, compares partition date to cutoff, detaches cleanly. |
| **ShedLock** | 🟢 OK | Prevents concurrent execution in multi-replica deployments. |
| **Archival after detach** | 🔴 CRITICAL | Detached partitions become orphaned standalone tables. They are **not** exported to S3/GCS, not dropped, and will accumulate as dead tables indefinitely. This is a storage and operational concern. |
| **No monitoring** | 🟡 MEDIUM | No metrics/alerts for partition creation failure. If the job fails silently for 3 months, inserts will start failing with "no partition for value". |
| **SQL injection risk** | 🟡 MEDIUM | `PartitionMaintenanceJob.java` line 39: `jdbcTemplate.execute("SELECT ensure_future_audit_partitions(" + futureMonths + ")")` concatenates an integer directly. While `futureMonths` comes from config (not user input) and is an `int` (so injection is impossible), this should use parameterized queries as a best practice. |
| **Detach without CONCURRENTLY** | 🟢 LOW | `ALTER TABLE audit_events DETACH PARTITION` takes an `ACCESS EXCLUSIVE` lock. For a running system, `DETACH PARTITION ... CONCURRENTLY` (PostgreSQL 14+) would avoid blocking concurrent inserts. |

---

## 4. SLA & Compliance Review

### 4.1 Data Integrity

**Rating: 🔴 CRITICAL GAP — No Tamper Evidence**

#### Current State

- **Append-only DDL:** `REVOKE UPDATE, DELETE` on `audit_app` role ✅
- **Immutable JPA entity:** All fields `updatable = false` ✅
- **No setters:** Entity has only getters, constructed via builder ✅

#### What's Missing

| Requirement | Status | Detail |
|-------------|--------|--------|
| **Cryptographic hash per record** | ❌ MISSING | No `sha256_hash` column. A DBA could silently alter any record without detection. |
| **Hash chaining** | ❌ MISSING | No `previous_hash` column linking each record to its predecessor. Deletion of a record would break no chain. |
| **Digital signature** | ❌ MISSING | No HMAC or asymmetric signature proving the service (not a DBA) created the record. |
| **Integrity verification endpoint** | ❌ MISSING | No API to verify the hash chain is intact. |
| **External hash anchoring** | ❌ MISSING | No periodic hash anchor published to an external immutable store (e.g., blockchain, RFC 3161 timestamp authority). |

**Impact:** Without tamper evidence, the audit trail cannot prove its own integrity during a forensic investigation. An insider with DB access could alter records undetected. This is a **PCI-DSS Req 10.5** failure and a **SOC 2 CC7.2** gap.

---

### 4.2 Retention

**Rating: 🟡 PARTIAL**

#### Current Configuration

```yaml
audit:
  partition:
    retention-days: ${AUDIT_PARTITION_RETENTION_DAYS:90}
```

| Aspect | Status | Detail |
|--------|--------|--------|
| **Configurable retention** | 🟢 OK | Externalized via environment variable, default 90 days. |
| **Per-regulation retention** | ❌ MISSING | Single global retention period. PCI-DSS requires 1 year online + 1 year archived (Req 10.7). SOX requires 7 years. GDPR varies by purpose. Different event types may need different retention. |
| **Retention enforcement** | 🟡 PARTIAL | Partitions are **detached** after 90 days but **not dropped or archived**. Data is still on disk. |
| **Retention audit trail** | ❌ MISSING | No log of which partitions were detached and when. |

#### Compliance Requirements

| Regulation | Required Retention | Current Status |
|------------|-------------------|----------------|
| **PCI-DSS 10.7** | 12 months online, 12 months archived | ❌ 90 days default, no archival |
| **SOX** | 7 years | ❌ Not supported |
| **GDPR** | "No longer than necessary" (purpose-specific) | ❌ No per-purpose retention |
| **SOC 2** | As defined by organization policy | 🟡 Configurable but single policy |

#### Recommendation

Add a `retention_policy` table per event type:
```sql
CREATE TABLE audit_retention_policies (
    event_type_pattern VARCHAR(100) PRIMARY KEY,
    online_retention_days INT NOT NULL DEFAULT 365,
    archive_retention_days INT NOT NULL DEFAULT 2555,  -- 7 years
    regulation VARCHAR(50)
);
```

---

### 4.3 PCI-DSS Req 10

**Rating: 🟡 PARTIAL COMPLIANCE**

PCI-DSS Requirement 10 mandates logging and monitoring of all access to cardholder data and network resources.

| Sub-Requirement | Required | Implemented | Gap |
|----------------|----------|-------------|-----|
| **10.1** — Audit trails linking access to individual users | Actor ID + actor type logged | ✅ | — |
| **10.2.1** — All individual user accesses to cardholder data | Events captured via Kafka | 🟡 | Depends on source services emitting correct events. No verification that `payment.events` includes all CHD access. |
| **10.2.2** — Actions by any individual with root/admin privileges | `actorType = 'ADMIN'` supported | 🟡 | No special handling or alerting for admin actions. |
| **10.2.3** — Access to all audit trails | No audit-of-audit-trail access logging | ❌ | Searches and exports of the audit trail itself are not logged. |
| **10.2.4** — Invalid logical access attempts | Security exceptions logged to application logs | 🟡 | 401/403 responses are returned but not stored as audit events. |
| **10.2.5** — Use of identification and authentication mechanisms | `identity.events` topic consumed | ✅ | — |
| **10.2.6** — Initialization, stopping, or pausing of audit logs | No lifecycle events logged | ❌ | Service start/stop, partition operations not audited. |
| **10.2.7** — Creation and deletion of system-level objects | Not captured | ❌ | Schema changes, user creation not in scope. |
| **10.3.1** — User identification | `actor_id` (UUID) | ✅ | — |
| **10.3.2** — Type of event | `event_type` | ✅ | — |
| **10.3.3** — Date and time | `created_at` (TIMESTAMPTZ) | ✅ | — |
| **10.3.4** — Success or failure indication | Not captured | ❌ | No `outcome` / `status` field (SUCCESS/FAILURE/DENIED). |
| **10.3.5** — Origination of event | `source_service` + `ip_address` | ✅ | — |
| **10.3.6** — Identity/name of affected data/resource | `resource_type` + `resource_id` | ✅ | — |
| **10.5** — Secure audit trails against alteration | `REVOKE UPDATE/DELETE` only | ❌ | No cryptographic integrity. Superuser can alter. See §4.1. |
| **10.7** — Retain audit trail history for at least 1 year | 90 days default | ❌ | Must be at least 365 days for online, with archival. |

---

### 4.4 GDPR Art 30

**Rating: 🔴 NOT COMPLIANT**

GDPR Article 30 requires records of processing activities. Article 15 grants data subjects the right to access their data.

| Requirement | Status | Detail |
|-------------|--------|--------|
| **Processing activity records** | ❌ | The audit trail records events but does not classify them as "processing activities" with purpose, legal basis, data categories, or recipients. |
| **Data subject access request (DSAR)** | ❌ | No dedicated endpoint to export all audit records for a specific `actorId` (data subject). The existing `actorId` search is admin-only and does not format for DSAR response. |
| **Right to erasure** | 🟡 N/A | Audit logs are typically exempt from erasure under Art 17(3)(e) (legal claims) and Art 17(3)(b) (legal obligation). However, this exemption should be documented. |
| **Data minimization** | 🟡 | `details` JSONB may contain excessive PII. No redaction/masking policy. |
| **Purpose limitation** | ❌ | No `processing_purpose` field to distinguish security audit vs. operational monitoring vs. compliance. |

---

### 4.5 SOC 2

**Rating: 🟡 PARTIAL**

SOC 2 Trust Service Criteria relevant to audit logging:

| Criteria | Requirement | Status | Gap |
|----------|-------------|--------|-----|
| **CC6.1** — Logical access security | Access controls on audit endpoints | ✅ | JWT + RBAC implemented |
| **CC6.2** — Prior to issuing credentials | Not in scope for this service | N/A | — |
| **CC7.1** — Detection of unauthorized changes | Tamper evidence | ❌ | No hash chaining |
| **CC7.2** — Monitoring for anomalies | Alerting on suspicious patterns | ❌ | No anomaly detection |
| **CC7.3** — Evaluation of identified events | Investigation queries | 🟡 | Search works but missing correlation ID search |
| **CC8.1** — Change management | Audit of configuration changes | ❌ | Service config changes not logged |
| **CC9.1** — Risk mitigation | Data integrity protection | ❌ | See §4.1 |

---

## 5. Missing Features & Recommendations

### 5.1 Cryptographic Chaining

**Priority: 🔴 P0 — Required for PCI-DSS 10.5 and SOC 2 CC7.1**

#### Problem
A DBA or compromised superuser account can silently `UPDATE` or `DELETE` audit records without any evidence of tampering. The `REVOKE` statement is a speed bump, not a security control.

#### Recommended Implementation

**1. Add columns to `audit_events`:**
```sql
ALTER TABLE audit_events ADD COLUMN record_hash VARCHAR(64);
ALTER TABLE audit_events ADD COLUMN previous_hash VARCHAR(64);
ALTER TABLE audit_events ADD COLUMN sequence_number BIGSERIAL;
```

**2. Compute hash before insertion in `AuditIngestionService`:**
```java
String hashInput = event.getEventType() + "|" + event.getSourceService() + "|" 
    + event.getActorId() + "|" + event.getAction() + "|" 
    + event.getResourceType() + "|" + event.getResourceId() + "|" 
    + event.getCreatedAt() + "|" + previousHash;
String recordHash = DigestUtils.sha256Hex(hashInput);
```

**3. Verification endpoint:**
```
GET /admin/audit/integrity?fromDate=...&toDate=...
→ { "verified": true, "recordsChecked": 150000, "chainValid": true }
```

**4. Periodic external anchoring:**
Publish the latest hash to an immutable external store (e.g., AWS QLDB, a public blockchain timestamp, or RFC 3161 TSA) every hour.

---

### 5.2 S3/GCS Archival

**Priority: 🔴 P0 — Required for PCI-DSS 10.7 retention**

#### Problem
`PartitionMaintenanceJob.detachOldPartitions()` detaches partitions but leaves them as orphaned tables. They are never:
- Exported to cold storage (S3/GCS)
- Compressed
- Dropped after successful archival

#### Recommended Implementation

```java
private void archiveAndDropPartition(String partitionName) {
    // 1. Export to Parquet/CSV in GCS
    String gcsPath = String.format("gs://audit-archive/%s/%s.parquet", 
        LocalDate.now().getYear(), partitionName);
    jdbcTemplate.execute(String.format(
        "COPY %s TO PROGRAM 'gsutil cp - %s' WITH (FORMAT csv, HEADER)",
        partitionName, gcsPath));
    
    // 2. Verify row count matches
    long sourceCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM " + partitionName, Long.class);
    // Verify against GCS metadata...
    
    // 3. Drop the detached partition
    jdbcTemplate.execute("DROP TABLE " + partitionName);
    
    // 4. Log archival event
    ingestionService.ingest(new AuditEventRequest(
        "PARTITION_ARCHIVED", "audit-trail-service", null, "SYSTEM",
        "PARTITION", partitionName, "ARCHIVE", 
        Map.of("gcsPath", gcsPath, "rowCount", sourceCount),
        null, null, null));
}
```

---

### 5.3 Alert Triggers

**Priority: 🟡 P1 — Required for SOC 2 CC7.2**

#### Problem
No mechanism to detect suspicious audit patterns:
- Admin user accessing >100 records in 1 minute
- Bulk data access patterns (data exfiltration signal)
- Failed authentication spikes
- First-time admin accessing payment data
- Audit export by non-compliance roles

#### Recommended Implementation

**Option A: In-service alerting (simple)**
```java
@Component
public class AuditAnomalyDetector {
    
    @Scheduled(fixedRate = 60_000)
    public void detectAnomalies() {
        // Query recent events for suspicious patterns
        long adminAccessCount = repository.countByActorTypeAndCreatedAtAfter(
            "ADMIN", Instant.now().minus(Duration.ofMinutes(5)));
        if (adminAccessCount > THRESHOLD) {
            alertService.fire("HIGH_ADMIN_ACTIVITY", adminAccessCount);
        }
    }
}
```

**Option B: External SIEM integration (enterprise)**
- Stream audit events to Elasticsearch/Splunk/Datadog via Kafka Connect
- Define alert rules in the SIEM platform
- This is more scalable and decouples detection from the audit service

---

### 5.4 Data Subject Access (GDPR DSAR)

**Priority: 🟡 P1 — Required for GDPR Art 15 compliance**

#### Problem
There is no endpoint to export all audit records associated with a specific data subject (user). The existing admin search endpoint can filter by `actorId`, but:
- It requires ADMIN role (the data subject can't access it)
- It returns paginated JSON (not a portable DSAR format)
- It doesn't search the `details` JSONB for the user's ID appearing as a target (e.g., an admin accessing *their* records)

#### Recommended Endpoint

```
GET /dsar/export?subjectId={userId}&format=json
Authorization: Bearer {service-token-with-DSAR-role}

→ Returns all audit events where:
  - actorId = subjectId  (actions BY the user)
  - resource_id = subjectId  (actions ON the user's data)
  - details @> '{"userId": "subjectId"}'  (mentions in payload)
```

This should be an async job returning a download link, since DSAR exports may cover years of data.

---

### 5.5 Correlation ID Tracking

**Priority: 🟡 P1 — Required for effective incident investigation**

#### Current State
- `correlation_id` column exists in the schema ✅
- Partial index `idx_audit_correlation` exists ✅
- Kafka consumer extracts `correlationId` from the envelope ✅
- **BUT:** `AuditSearchCriteria` does NOT include `correlationId` ❌
- **AND:** No API to query by `correlationId` ❌

#### Impact
When investigating a user complaint ("my order was charged twice"), the support team needs to trace the full transaction flow across identity → order → payment → fulfillment. The correlation ID links these events, but there's no way to query by it through the API.

#### Fix Required (Minimal)

**1. Add to `AuditSearchCriteria.java`:**
```java
public record AuditSearchCriteria(
    UUID actorId,
    String resourceType,
    String resourceId,
    String sourceService,
    String eventType,
    String correlationId,    // ADD THIS
    Instant fromDate,
    Instant toDate,
    int page,
    int size
) { ... }
```

**2. Add to `AuditQueryService.buildSpecification()`:**
```java
if (criteria.correlationId() != null && !criteria.correlationId().isBlank()) {
    predicates.add(cb.equal(root.get("correlationId"), criteria.correlationId()));
}
```

**3. Add to `AdminAuditController.search()`:**
```java
@RequestParam(required = false) String correlationId,
```

---

## 6. Consolidated Finding Matrix

| # | Finding | Severity | Category | Regulation Impact | Effort |
|---|---------|----------|----------|-------------------|--------|
| F1 | No cryptographic chaining/tamper evidence | 🔴 CRITICAL | Data Integrity | PCI 10.5, SOC 2 CC7.1 | L (2-3 sprints) |
| F2 | No cold storage archival after partition detach | 🔴 CRITICAL | Retention | PCI 10.7 | M (1-2 sprints) |
| F3 | Synchronous CSV export (no async) | 🔴 CRITICAL | Export | Operational | M (1-2 sprints) |
| F4 | Default retention 90 days (PCI requires 365+) | 🔴 CRITICAL | Retention | PCI 10.7 | S (config change) |
| F5 | No GDPR DSAR endpoint | 🟡 HIGH | Compliance | GDPR Art 15 | M (1-2 sprints) |
| F6 | No anomaly alerting | 🟡 HIGH | Monitoring | SOC 2 CC7.2 | M (1-2 sprints) |
| F7 | Missing Kafka topics (notification, pricing, etc.) | 🟡 HIGH | Completeness | All | S (config change) |
| F8 | No `correlationId` in search API | 🟡 HIGH | Search | Operational | XS (hours) |
| F9 | No idempotent ingestion (dedup) | 🟡 MEDIUM | Ingestion | Data Quality | S (1 sprint) |
| F10 | No `outcome` (success/failure) field | 🟡 MEDIUM | Schema | PCI 10.3.4 | S (1 sprint) |
| F11 | Audit trail access not self-audited | 🟡 MEDIUM | Compliance | PCI 10.2.3 | S (1 sprint) |
| F12 | `REVOKE` may not apply if app runs as `postgres` | 🟡 MEDIUM | Security | PCI 10.5 | XS (infra config) |
| F13 | No per-regulation retention policies | 🟡 MEDIUM | Retention | Multi-reg | M (1-2 sprints) |
| F14 | Kafka auto-commit risks event loss | 🟡 MEDIUM | Reliability | Operational | S (config) |
| F15 | No mandatory time-range on queries | 🟡 MEDIUM | Performance | Operational | XS (hours) |
| F16 | Batch ingestion no size limit | 🟢 LOW | API Safety | Operational | XS (hours) |
| F17 | Duplicated specification logic (query vs export) | 🟢 LOW | Code Quality | — | XS (hours) |
| F18 | `details` JSONB excluded from CSV export | 🟢 LOW | Export | Compliance | XS (hours) |
| F19 | No defense-in-depth trigger on audit_events | 🟢 LOW | Security | PCI 10.5 | XS (hours) |
| F20 | PublicKey parsed on every request (no caching) | 🟢 LOW | Performance | Operational | XS (hours) |

---

## 7. Remediation Roadmap

### Phase 1: Critical Compliance Fixes (Weeks 1–4)

| Item | Finding | Action |
|------|---------|--------|
| 1.1 | F4 | Change `AUDIT_PARTITION_RETENTION_DAYS` default to `365` in `application.yml` and deployment config |
| 1.2 | F8 | Add `correlationId` to `AuditSearchCriteria`, `AuditQueryService`, and `AdminAuditController` |
| 1.3 | F12 | Verify all deployments use `audit_app` role (not `postgres`). Add `BEFORE UPDATE/DELETE` trigger as safety net |
| 1.4 | F15 | Add mandatory time-range validation in `AuditSearchCriteria` (max 366 days, default last 30 days) |
| 1.5 | F16 | Add `@Size(max = 1000)` on batch ingestion list parameter |
| 1.6 | F14 | Configure `spring.kafka.consumer.enable-auto-commit=false` and use `AckMode.RECORD` |

### Phase 2: Cryptographic Integrity (Weeks 5–8)

| Item | Finding | Action |
|------|---------|--------|
| 2.1 | F1 | Add `record_hash`, `previous_hash`, `sequence_number` columns via Flyway V4 migration |
| 2.2 | F1 | Implement SHA-256 hash computation in `AuditIngestionService` before `repository.save()` |
| 2.3 | F1 | Add `GET /admin/audit/integrity` verification endpoint |
| 2.4 | F19 | Add `BEFORE UPDATE OR DELETE` trigger to `audit_events` |
| 2.5 | F10 | Add `outcome` column (`SUCCESS`/`FAILURE`/`DENIED`) via Flyway V5 migration |

### Phase 3: Archival & Export (Weeks 9–12)

| Item | Finding | Action |
|------|---------|--------|
| 3.1 | F2 | Implement GCS archival in `PartitionMaintenanceJob` (export → verify → drop) |
| 3.2 | F3 | Implement async export: background job → write to GCS → return presigned download URL |
| 3.3 | F11 | Emit self-audit events for all admin search and export operations |
| 3.4 | F18 | Include `details` JSONB as a JSON string column in CSV export |

### Phase 4: GDPR & Monitoring (Weeks 13–16)

| Item | Finding | Action |
|------|---------|--------|
| 4.1 | F5 | Implement DSAR export endpoint (`GET /dsar/export?subjectId=...`) |
| 4.2 | F6 | Implement anomaly detection (in-service or SIEM integration) |
| 4.3 | F7 | Add missing Kafka topics to consumer configuration |
| 4.4 | F9 | Implement idempotent ingestion using event envelope ID as dedup key |
| 4.5 | F13 | Implement per-event-type retention policy table |

---

*End of Review*
