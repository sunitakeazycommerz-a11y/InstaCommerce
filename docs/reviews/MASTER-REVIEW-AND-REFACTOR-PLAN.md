# Instacommerce — MASTER Review & Refactoring Plan

**Date:** 2026-02-07
**Version:** 1.0
**Status:** SINGLE SOURCE OF TRUTH — All prior reviews consolidated here
**Scope:** All 18 microservices, infrastructure, CI/CD, contracts, operational readiness
**Scale Target:** 20M+ users, 500K+ daily orders, 10-minute delivery SLA
**Benchmarks:** Zepto, Blinkit, Instacart, DoorDash, Swiggy Instamart

---

> **Supersedes:** `CONSOLIDATED-REVIEW.md`, `MICROSERVICE-REFACTOR-PLAN.md`, all per-service `REVIEW-REPORT.md` files.
> This document is the authoritative reference for making the Instacommerce Q-commerce platform production-ready.

---

## Table of Contents

1. [Platform Maturity Assessment](#section-1-platform-maturity-assessment)
2. [Cross-Cutting Concerns Gap Analysis](#section-2-cross-cutting-concerns-gap-analysis)
3. [Critical Production Blockers](#section-3-critical-production-blockers)
4. [Per-Service Refactoring Roadmap](#section-4-per-service-refactoring-roadmap)
5. [Platform-Level Improvements](#section-5-platform-level-improvements)
6. [Competitive Analysis](#section-6-competitive-analysis)
7. [Production Launch Checklist](#section-7-production-launch-checklist)

---

## Section 1: Platform Maturity Assessment

### Scoring Legend

| Score | Meaning |
|-------|---------|
| 1 | Not started / Placeholder only |
| 2 | Skeleton — basic CRUD, no production hardening |
| 3 | Functional — core logic works, gaps remain |
| 4 | Production-approaching — most concerns addressed |
| 5 | Production-ready — fully hardened, tested, observable |

### Service Structure Inventory

All 18 services share a consistent project structure:

| Artifact | Present In |
|----------|-----------|
| `build.gradle.kts` | 18/18 ✅ |
| `Dockerfile` | 18/18 ✅ |
| `src/main/java` | 18/18 ✅ |
| `application.yml` | 18/18 ✅ |
| `src/test` (with tests) | **0/18** ❌ |
| `src/test` (empty dir) | 2/18 (rider-fleet, warehouse) |

### Maturity Scores

| # | Service | Java Files | Code Completeness | Prod Readiness | Test Coverage | Ops Readiness | Doc Quality | **Avg** |
|---|---------|-----------|-------------------|----------------|---------------|---------------|-------------|---------|
| 1 | identity-service | 48 | 4 | 3 | 1 | 2 | 3 | **2.6** |
| 2 | catalog-service | 59 | 4 | 3 | 1 | 2 | 3 | **2.6** |
| 3 | search-service | 24 | 3 | 2 | 1 | 2 | 2 | **2.0** |
| 4 | pricing-service | 37 | 4 | 3 | 1 | 2 | 2 | **2.4** |
| 5 | cart-service | 31 | 3 | 2 | 1 | 2 | 2 | **2.0** |
| 6 | checkout-orchestrator | 29 | 3 | 2 | 1 | 2 | 2 | **2.0** |
| 7 | order-service | 62 | 4 | 3 | 1 | 2 | 3 | **2.6** |
| 8 | payment-service | 55 | 4 | 2 | 1 | 2 | 3 | **2.4** |
| 9 | inventory-service | 51 | 3 | 2 | 1 | 2 | 3 | **2.2** |
| 10 | fulfillment-service | 60 | 3 | 2 | 1 | 2 | 3 | **2.2** |
| 11 | notification-service | 43 | 3 | 2 | 1 | 1 | 2 | **1.8** |
| 12 | warehouse-service | 34 | 3 | 2 | 1 | 2 | 2 | **2.0** |
| 13 | rider-fleet-service | 40 | 3 | 2 | 1 | 2 | 2 | **2.0** |
| 14 | routing-eta-service | 31 | 3 | 2 | 1 | 2 | 2 | **2.0** |
| 15 | wallet-loyalty-service | 42 | 3 | 2 | 1 | 2 | 2 | **2.0** |
| 16 | audit-trail-service | 20 | 3 | 2 | 1 | 2 | 2 | **2.0** |
| 17 | fraud-detection-service | 36 | 3 | 2 | 1 | 2 | 2 | **2.0** |
| 18 | config-feature-flag | 26 | 3 | 2 | 1 | 2 | 2 | **2.0** |

**Platform Average: 2.2 / 5.0 — Pre-Alpha**

### Key Observations

1. **Zero test coverage across the entire platform.** Not a single service has unit, integration, or end-to-end tests. This is the single biggest gap. No service can be considered production-ready without test coverage.
2. **Original 7 services** (identity, catalog, inventory, order, payment, fulfillment, notification) have the most complete business logic with 43–62 Java files each.
3. **11 new services** (search, pricing, cart, checkout-orchestrator, warehouse, rider-fleet, routing-eta, wallet-loyalty, audit-trail, fraud-detection, config-feature-flag) are fully implemented but have had no code review or hardening pass.
4. **Total codebase: ~651 Java files** across 18 services — substantial platform with real complexity.

---

## Section 2: Cross-Cutting Concerns Gap Analysis

### 2.1 Observability

| Concern | Status | Details |
|---------|--------|---------|
| Structured logging | ✅ Implemented | LogstashEncoder + JSON logs across all services |
| Correlation IDs | ⚠️ Partial | Not enforced in all inter-service calls; no standardized header propagation |
| Metrics (Micrometer) | ❌ Missing | No custom business metrics (auth rates, checkout success rate, payment failures). HPA is CPU-only |
| Distributed tracing | ⚠️ Partial | OpenTelemetry configured in identity-service; not verified across all 18 services |
| Dashboards | ❌ Missing | No Grafana dashboards provisioned; no baseline SLI/SLO tracking |
| Alerting rules | ❌ Missing | No Prometheus alerting rules; no PagerDuty/OpsGenie integration |
| Log aggregation | ⚠️ Partial | Logs shipped to stdout for K8s collection but no centralized search (ELK/Loki) configured |

**Gap Rating: 🔴 CRITICAL — Cannot operate at scale without observability**

### 2.2 Security

| Concern | Status | Details |
|---------|--------|---------|
| JWT Authentication (RS256) | ✅ Implemented | RSA-2048, BCrypt-12, refresh token rotation |
| RBAC (Role-Based Access) | ⚠️ Partial | Missing `@PreAuthorize` on several admin endpoints across fulfillment, order, notification |
| Input validation | ⚠️ Partial | Bean validation present but not comprehensive; no sanitization for XSS |
| CORS configuration | ❌ Missing | Zero CORS config in any service |
| Rate limiting | ⚠️ Broken | `ConcurrentHashMap`-based — unbounded memory growth (OOM DoS vector) |
| API key rotation | ❌ Missing | Stripe API key is static global; no rotation mechanism |
| Service-to-service auth | ❌ Missing | REST clients pass no JWT; relies solely on Istio mTLS (network-level only) |
| Secrets management | ⚠️ Partial | GCP Secrets Manager referenced but not fully wired |
| Webhook verification | ⚠️ Broken | `required=false` on signature header allows unauthenticated webhooks |
| JWT claim hardening | ❌ Missing | No `aud`/`jti` claims — cross-service token replay possible |
| Auth endpoint exposure | 🔴 Broken | `/auth/revoke` unauthenticated (under `/auth/**` permitAll) |
| Notification service auth | 🔴 Missing | Zero Spring Security configuration |
| Pod security context | ❌ Missing | No `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities.drop` |
| Network policies | ❌ Missing | No CNI-level pod isolation |

**Gap Rating: 🔴 CRITICAL — Multiple exploitable vulnerabilities**

### 2.3 Resilience

| Concern | Status | Details |
|---------|--------|---------|
| Circuit breakers | ❌ Missing | No Resilience4j circuit breakers on any REST client |
| Timeouts | 🔴 Zero | All inter-service REST clients use default infinite timeouts |
| Retries | ⚠️ Partial | `Thread.sleep(5min)` retry in notification — blocks thread pool |
| Fallbacks | ❌ Missing | No graceful degradation for any downstream failure |
| Bulkheads | ❌ Missing | No thread pool isolation between consumer groups |
| Istio circuit breaking | ❌ Missing | No DestinationRules with outlier detection |
| Kafka error handling | 🔴 Broken | No `DefaultErrorHandler` — poison pills cause infinite retry loops |
| Graceful shutdown | ✅ Implemented | `server.shutdown=graceful` with 30s timeout across all services |
| Health checks | ⚠️ Broken | References Redis health but Redis is unconfigured |

**Gap Rating: 🔴 CRITICAL — Single slow service cascades into full platform outage**

### 2.4 Data Consistency

| Concern | Status | Details |
|---------|--------|---------|
| Outbox pattern | ⚠️ Partial | Implemented in 5/7 original services; inventory-service has zero event publishing |
| Saga orchestration | ✅ Implemented | Temporal workflows with compensation in checkout-orchestrator |
| Idempotency keys | ⚠️ Partial | Refunds use idempotency keys; inventory uses UNIQUE constraints; not standardized |
| Eventual consistency | ⚠️ Partial | Event-driven design intended but broken due to missing events from inventory |
| Double-entry ledger | ✅ Implemented | Payment service has proper ledger with DB constraints |
| Ledger correctness | 🔴 Broken | Refund ledger debit/credit reversed — will never reconcile |
| Transaction boundaries | 🔴 Broken | PSP calls inside `@Transactional` — ghost payments at scale |
| Race conditions | 🔴 Broken | Refund double-spend — no pessimistic locking |
| Outbox cleanup | ❌ Missing | Outbox tables grow unbounded |
| DB partitioning | ❌ Missing | Orders table not partitioned (will degrade at 100M+ rows) |

**Gap Rating: 🔴 CRITICAL — Financial data integrity at risk**

### 2.5 Configuration

| Concern | Status | Details |
|---------|--------|---------|
| Externalized config | ✅ Implemented | Spring profiles (gcp, dev) with environment variables |
| Secret management | ⚠️ Partial | GCP Secrets Manager referenced; Stripe key hardcoded in some paths |
| Feature flags | ✅ Implemented | Dedicated config-feature-flag-service with bulk evaluation |
| Config hot-reload | ❌ Missing | Requires pod restart for config changes |
| Environment parity | ⚠️ Partial | Dev/staging/prod profiles exist but not fully differentiated |

**Gap Rating: 🟡 MEDIUM — Functional but needs hardening**

### 2.6 Testing

| Concern | Status | Details |
|---------|--------|---------|
| Unit tests | ❌ ZERO | 0/18 services have any unit tests |
| Integration tests | ❌ ZERO | No Testcontainers, no Spring Boot test slices |
| Contract tests | ❌ ZERO | Event schemas exist in `contracts/` but no Pact/Spring Cloud Contract |
| E2E tests | ❌ ZERO | No API test suites, no Postman collections |
| Load/stress tests | ❌ ZERO | No k6/Gatling/Locust scripts |
| Test data management | ❌ ZERO | No factories, fixtures, or seed data |
| CI test gates | ❌ ZERO | No quality gates in CI pipeline |

**Gap Rating: 🔴 CRITICAL — Zero confidence in any code change**

### 2.7 API Consistency

| Concern | Status | Details |
|---------|--------|---------|
| Error response format | ⚠️ Inconsistent | No standardized error envelope across services |
| Pagination | ⚠️ Inconsistent | `AdminListUsers()` loads ALL records — no pagination on some endpoints |
| API versioning | ⚠️ Partial | `/api/v1/` in Istio routes but not enforced in controllers |
| Naming conventions | ✅ Consistent | RESTful naming follows convention |
| OpenAPI/Swagger docs | ❌ Missing | No auto-generated API docs |
| Deprecation strategy | ❌ Missing | No process for API evolution |

**Gap Rating: 🟠 HIGH — API inconsistencies will cause client-side bugs**

---

## Section 3: Critical Production Blockers

### Priority 1: Security Vulnerabilities 🔴

| # | Finding | Service(s) | Severity | Effort |
|---|---------|-----------|----------|--------|
| S1 | `/auth/revoke` endpoint unauthenticated | identity | CRITICAL | S |
| S2 | Notification service has zero Spring Security | notification | CRITICAL | S |
| S3 | Unbounded ConcurrentHashMap rate limiters (OOM DoS) | identity, order, catalog | CRITICAL | M |
| S4 | Admin endpoints missing `@PreAuthorize` | order, fulfillment | HIGH | S |
| S5 | No CORS configuration anywhere | all 18 services | HIGH | M |
| S6 | Webhook signature header `required=false` | payment | HIGH | S |
| S7 | JWT missing `aud`/`jti` claims (token replay) | identity | HIGH | M |
| S8 | No service-to-service auth on REST calls | all calling services | HIGH | L |
| S9 | Stripe.apiKey global static (not thread-safe) | payment | HIGH | S |
| S10 | No pod securityContext in K8s | all 18 services | HIGH | M |
| S11 | No NetworkPolicies | infrastructure | HIGH | M |
| S12 | Deleted user tokens remain valid for 15 min | identity | MEDIUM | M |

### Priority 2: Data Integrity Risks 🔴

| # | Finding | Service(s) | Severity | Effort |
|---|---------|-----------|----------|--------|
| D1 | PSP calls inside `@Transactional` — ghost payments | payment | CRITICAL | M |
| D2 | Refund race condition — double refunds | payment | CRITICAL | S |
| D3 | Refund ledger debit/credit reversed | payment | CRITICAL | S |
| D4 | Inventory service — zero event publishing | inventory | CRITICAL | L |
| D5 | PaymentVoided uses `amount` not `amountCents` | payment | HIGH | S |
| D6 | OrderPlaced event missing `sku` field | order | HIGH | S |
| D7 | 5 events published without schemas | multiple | HIGH | M |
| D8 | Schema drift — PaymentCaptured has 3 versions | payment, contracts | HIGH | M |
| D9 | No refund on post-checkout cancellation | order/payment | HIGH | M |
| D10 | Refund over-calculation ignores discounts | fulfillment | HIGH | M |
| D11 | HTTP calls inside `@Transactional` in PickService | fulfillment | HIGH | M |

### Priority 3: Performance Bottlenecks 🟠

| # | Finding | Service(s) | Severity | Effort |
|---|---------|-----------|----------|--------|
| P1 | Zero timeouts on ALL REST clients | fulfillment, notification | CRITICAL | M |
| P2 | `Thread.sleep(5 min)` blocks notification thread pool | notification | CRITICAL | S |
| P3 | Kafka consumers — no error handler, poison pill death loop | fulfillment, notification | CRITICAL | M |
| P4 | Admin `listUsers()` loads ALL records — instant OOM | identity | HIGH | S |
| P5 | No caching anywhere (no Caffeine/Redis despite config) | catalog, identity | HIGH | M |
| P6 | N+1 queries in PricingService | catalog/pricing | HIGH | M |
| P7 | No HikariCP connection pool tuning | all 18 services | MEDIUM | S |
| P8 | Consumer `concurrency=1` wastes Kafka partitions | all consumers | MEDIUM | S |
| P9 | Template loading uncached | notification | MEDIUM | S |
| P10 | `auto-offset-reset: earliest` replays full history | all consumers | MEDIUM | S |

### Priority 4: Operational Gaps 🟠

| # | Finding | Service(s) | Severity | Effort |
|---|---------|-----------|----------|--------|
| O1 | No rolling update strategy in K8s | infrastructure | CRITICAL | S |
| O2 | No pod anti-affinity (single-node failure = total outage) | infrastructure | CRITICAL | S |
| O3 | No Istio circuit breaking / outlier detection | infrastructure | CRITICAL | M |
| O4 | Reservation expiry job — no distributed lock | inventory | HIGH | S |
| O5 | `@Scheduled` runs on ALL pods (no leader election) | identity, inventory, catalog, payment | HIGH | M |
| O6 | No Terraform state locking | infrastructure | HIGH | S |
| O7 | HPA is CPU-only (no memory/request rate/Kafka lag) | infrastructure | HIGH | M |
| O8 | No Micrometer custom business metrics | all 18 services | HIGH | L |
| O9 | HPA for notification-service missing | infrastructure | MEDIUM | S |
| O10 | Resource quotas/LimitRanges missing | infrastructure | MEDIUM | S |

### Priority 5: Compliance Requirements 🟠

| # | Finding | Service(s) | Severity | Effort |
|---|---------|-----------|----------|--------|
| C1 | Audit log not append-only at DB level | all services + audit-trail | HIGH | M |
| C2 | No PCI-DSS network segmentation for payment | infrastructure | HIGH | L |
| C3 | No GDPR consent management | identity | HIGH | L |
| C4 | Audit log per-service (no tamper evidence) | all services | MEDIUM | M |
| C5 | No data retention policies automated | all services | MEDIUM | M |

### Status Update (Post-Phase 6 Refactor)

**Completed fixes (from plan.md):**
- [x] S1-S3, S5-S11 — auth revoke protection, notification security, Caffeine rate limiting, CORS, webhook verification, JWT claims, service-to-service auth, Stripe thread-safety, securityContext, NetworkPolicies
- [x] D1-D4, D6, D10-D11 — payment TX boundaries, refund lock/ledger, inventory events, OrderPlaced SKU, refund precision, fulfillment HTTP-in-TX
- [x] P1-P5, P7, P9 — REST timeouts, notification retry, Kafka DLQ, admin pagination, caching, HikariCP tuning, template caching
- [x] O1-O5, O7 — rolling updates, anti-affinity, circuit breaking, ShedLock, HPA updates
- [x] C1, C5 — append-only audit logs, retention jobs
- [x] Phase 6 criticals — cart price validation, wallet payment verification + ledger, checkout idempotency/compensation, outbox cleanup, VirtualService port alignment

**Remaining gaps:**
- [ ] S4, S12 — admin `@PreAuthorize` in fulfillment; token revocation latency
- [ ] D5, D7-D9 — PaymentVoided amountCents, missing/duplicate schemas, refund on post-checkout cancel
- [ ] P6, P8, P10 — pricing N+1, consumer concurrency, auto-offset reset defaults
- [ ] O6, O8-O10 — Terraform state locking, business metrics, notification HPA, quotas/limits
- [ ] C2-C4 — PCI segmentation, GDPR consent, tamper-evident centralized audit

---

## Section 4: Per-Service Refactoring Roadmap

### Service 1: identity-service (48 files)

**Current State:** Most mature service. RS256 JWT auth with BCrypt-12, refresh token rotation, audit logging, outbox pattern, rate limiting. Handles auth, user CRUD, notification preferences. Core security infra for the platform.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Fix `/auth/revoke` unauthenticated — move to separate path or add per-path auth | S | P0 |
| 2 | Replace ConcurrentHashMap rate limiter with Caffeine/Redis (OOM fix) | M | P0 |
| 3 | Add `aud`/`jti` JWT claims; implement token blacklist in Redis | M | P0 |
| 4 | Add unit + integration tests (target 80% coverage) | L | P0 |
| 5 | Add Micrometer metrics (auth success/failure rate, token refresh rate) | M | P1 |

**Dependencies:** None — foundational service. All other services depend on identity.

---

### Service 2: catalog-service (59 files)

**Current State:** Feature-rich with products, categories, pricing strategies, coupons, search. Largest service by file count. Still contains pricing and search logic that should be delegated to dedicated services per the refactoring plan.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Delegate pricing logic to pricing-service (remove PricingController, strategies) | L | P1 |
| 2 | Delegate search logic to search-service (remove SearchController) | M | P1 |
| 3 | Add caching layer (Caffeine L1 + Redis L2 for product data) | M | P1 |
| 4 | Replace ConcurrentHashMap rate limiter | M | P0 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** search-service, pricing-service must be validated before delegation.

---

### Service 3: search-service (24 files)

**Current State:** Newly created service with PostgreSQL-backed search. Has Kafka consumer for catalog events, trending queries, caching, and JWT auth. Currently uses PostgreSQL instead of the planned OpenSearch — limits full-text search quality at scale.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Migrate from PostgreSQL to OpenSearch for full-text/autocomplete/faceted search | XL | P1 |
| 2 | Add synonym dictionaries and phonetic analysis for regional languages | L | P2 |
| 3 | Implement autocomplete with prefix suggestions (p99 < 100ms) | M | P1 |
| 4 | Add search analytics (popular queries, zero-result queries, CTR tracking) | M | P2 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** catalog-service (event source), OpenSearch cluster provisioning (infra).

---

### Service 4: pricing-service (37 files)

**Current State:** Fully implemented with coupon management, promotion engine, price rules, outbox pattern. Consumes catalog events via Kafka. Has admin endpoints for promotions and coupons. Solid foundation for dynamic pricing.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Add Redis caching for hot-path price lookups (target p99 < 50ms) | M | P0 |
| 2 | Implement flash sale support with independent scaling | L | P1 |
| 3 | Add price calculation audit trail for compliance | M | P1 |
| 4 | Fix N+1 query patterns in bulk price calculations | M | P1 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** catalog-service (events), Redis cluster (infra).

---

### Service 5: cart-service (31 files)

**Current State:** Implemented with JPA-backed cart store, cleanup jobs, outbox pattern. Uses PostgreSQL instead of the planned Redis-backed ephemeral storage. This limits performance for what should be the highest TPS operation on the platform.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Migrate primary cart state from PostgreSQL to Redis (hash-per-user, TTL 24h) | XL | P1 |
| 2 | Add cart validation endpoint (stock + price check before checkout) | M | P0 |
| 3 | Implement cart abandonment detection and `CartAbandoned` event publishing | M | P2 |
| 4 | Achieve p99 < 20ms latency target via Redis migration | L | P1 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** Redis cluster (infra), inventory-service (validation), pricing-service (validation).

---

### Service 6: checkout-orchestrator-service (29 files)

**Current State:** Implements Temporal-based saga orchestration for multi-step checkout. Has activities for cart, inventory, pricing, payment, and order operations via REST clients. No database — state managed by Temporal. Core coordination point for the platform.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Add timeouts + circuit breakers on all REST activity clients | M | P0 |
| 2 | Implement full compensation (rollback) for each activity on failure | M | P0 |
| 3 | Add checkout metrics (success rate, step durations, failure reasons) | M | P0 |
| 4 | Configure Temporal worker scaling and resource tuning for peak load | L | P1 |
| 5 | Add unit + integration tests (mock Temporal test framework) | L | P0 |

**Dependencies:** All transaction-domain services (cart, inventory, pricing, payment, order).

---

### Service 7: order-service (62 files)

**Current State:** Most complex service by file count. Has order CRUD, state machine, Temporal checkout workflows, admin endpoints, Kafka event publishing, user erasure (GDPR). Still contains checkout logic that should be fully delegated to checkout-orchestrator.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Remove embedded checkout workflow (delegate to checkout-orchestrator) | L | P1 |
| 2 | Add missing state machine transition (PACKED→CANCELLED) | S | P1 |
| 3 | Fix OrderPlaced event — add missing `sku` field | S | P0 |
| 4 | Add orders table partitioning (by month) for query performance at scale | M | P2 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** checkout-orchestrator-service (delegation target).

---

### Service 8: payment-service (55 files)

**Current State:** Has Stripe integration, refund processing, double-entry ledger, webhook handling, outbox pattern. Contains the **most critical bugs in the platform**: ghost payments, double refunds, reversed ledger entries. Requires immediate hardening before any financial transactions.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Extract PSP calls from `@Transactional` (fix ghost payments) | M | P0 |
| 2 | Add pessimistic locking on refund (fix double-spend) | S | P0 |
| 3 | Fix refund ledger debit/credit reversal | S | P0 |
| 4 | Fix webhook signature header `required=false` | S | P0 |
| 5 | Add reconciliation job (Stripe vs. local ledger) | L | P0 |

**Dependencies:** None — critical path, must be fixed immediately.

---

### Service 9: inventory-service (51 files)

**Current State:** Has stock management, reservations with TTL, low-stock alerts, audit logging. Uses ShedLock for distributed scheduling. Critical gap: zero event publishing despite outbox table existing — breaks event-driven architecture for the entire platform.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Implement outbox event publishing (StockReserved, StockConfirmed, StockReleased, LowStockAlert) | L | P0 |
| 2 | Fix reservation expiry job to use ShedLock `@SchedulerLock` | S | P0 |
| 3 | Add distributed lock for `@Scheduled` jobs (all pods running simultaneously) | S | P0 |
| 4 | Add read replicas for stock queries (separate read/write paths) | M | P2 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** Kafka topics provisioning, Debezium CDC setup.

---

### Service 10: fulfillment-service (60 files)

**Current State:** Handles pick task management, delivery coordination, rider assignment, substitutions. Has Kafka consumers for order/identity events. Makes blocking REST calls to order, inventory, and payment services inside `@Transactional`. Needs resilience hardening and domain slimming.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Add timeouts + circuit breakers on all REST clients (currently infinite) | M | P0 |
| 2 | Extract HTTP calls from `@Transactional` in PickService | M | P0 |
| 3 | Add Kafka `DefaultErrorHandler` + DLQ (fix poison pill death loop) | M | P0 |
| 4 | Delegate rider management to rider-fleet-service | L | P1 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** rider-fleet-service (delegation), routing-eta-service (delivery tracking).

---

### Service 11: notification-service (43 files)

**Current State:** Multi-channel notification hub (email via SendGrid, SMS via Twilio). Event-driven via Kafka consumers. Has deduplication, templates, DLQ publishing, user preference lookup. Contains critical bugs: `Thread.sleep(5min)` blocking, zero Spring Security, poison pill loops. **Least mature of original 7 services.**

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Add Spring Security configuration (currently zero auth) | S | P0 |
| 2 | Replace `Thread.sleep(5min)` with Spring Retry `@Retryable` or DB-based retry | S | P0 |
| 3 | Add Kafka `DefaultErrorHandler` + DLQ (fix poison pill loop) | M | P0 |
| 4 | Fix SMS — `RestUserDirectoryClient` returns `phone=null` (SMS always fails) | M | P0 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** identity-service (user lookup), device token management (for PUSH notifications).

---

### Service 12: warehouse-service (34 files)

**Current State:** Store management with zones, operating hours, capacity tracking. Has outbox pattern, caching, ShedLock. Supports nearest-store lookup for zone-based delivery. Complete implementation for dark store operations.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Add geo-spatial indexing for nearest-store lookup (PostGIS or Redis GEOSEARCH) | M | P1 |
| 2 | Implement real-time capacity dashboards | M | P2 |
| 3 | Add store onboarding/offboarding workflow | L | P2 |
| 4 | Add integration with inventory-service for per-store stock views | M | P2 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** inventory-service (stock data), routing-eta-service (delivery zones).

---

### Service 13: rider-fleet-service (40 files)

**Current State:** Complete rider lifecycle management — shifts, earnings, ratings, availability tracking. Consumes fulfillment events via Kafka. Has outbox pattern and ShedLock. Empty test directory exists but no tests written.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Implement optimal rider assignment algorithm (proximity + load balancing) | L | P0 |
| 2 | Add real-time rider location tracking (Redis GEO) | M | P1 |
| 3 | Implement rider surge pricing during peak hours | M | P2 |
| 4 | Add rider compliance checks (documents, background verification) | L | P2 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** routing-eta-service (location/ETA), warehouse-service (store proximity).

---

### Service 14: routing-eta-service (31 files)

**Current State:** Real-time delivery tracking with WebSocket support, ETA calculation using Google Maps integration, location update consumers from Kafka. Supports delivery lifecycle management. Has outbox pattern.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Add Redis caching for ETA calculations (avoid repeated Google Maps API calls) | M | P0 |
| 2 | Implement WebSocket connection management and scaling | M | P1 |
| 3 | Add fallback ETA provider (MapMyIndia) for redundancy | L | P2 |
| 4 | Implement route optimization for multi-drop deliveries | L | P3 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** rider-fleet-service (rider location), Google Maps API quota.

---

### Service 15: wallet-loyalty-service (42 files)

**Current State:** Comprehensive wallet, loyalty points, and referral system. Consumes payment and order events. Has points expiry job, ShedLock, caching. Handles financial transactions (wallet debit/credit) — needs double-entry ledger pattern.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Implement double-entry ledger for wallet transactions (like payment-service) | L | P0 |
| 2 | Add wallet balance reconciliation job | M | P1 |
| 3 | Implement wallet top-up via payment-service integration | M | P1 |
| 4 | Add referral fraud prevention (velocity checks via fraud-detection) | M | P1 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** payment-service (top-up), fraud-detection-service (referral fraud).

---

### Service 16: audit-trail-service (20 files)

**Current State:** Centralized audit log ingestion and querying. Has partition maintenance, batch processing, export capabilities. Consumes all domain events via Kafka. Smallest service — focused on append-only audit log storage.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Enforce append-only at DB level (REVOKE UPDATE/DELETE on audit_events) | S | P0 |
| 2 | Add S3 cold storage archival for compliance retention | L | P1 |
| 3 | Implement tamper-evident hashing (chain hash each entry) | M | P1 |
| 4 | Add compliance export in required formats (PCI-DSS, GDPR) | M | P1 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** S3/GCS bucket provisioning (infra), all services (event sources).

---

### Service 17: fraud-detection-service (36 files)

**Current State:** Rules-based fraud scoring engine with velocity checks, blocklist management. Consumes payment and order events. Has admin interface for rule management. No ML scoring yet — rule-based only.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Add Redis-backed velocity counters (current implementation may be in-memory) | M | P0 |
| 2 | Implement ML model integration for fraud scoring (alongside rules) | XL | P2 |
| 3 | Add device fingerprinting for multi-account fraud detection | L | P2 |
| 4 | Integrate with checkout-orchestrator as sync scoring step | M | P0 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** checkout-orchestrator (integration), Redis cluster (infra), ML platform (future).

---

### Service 18: config-feature-flag-service (26 files)

**Current State:** Feature flag management with bulk evaluation, user/context-based targeting, overrides, audit logging, and caching. Has ShedLock for cache refresh. Foundation for gradual rollouts and A/B testing.

**Top 5 Improvements:**

| # | Improvement | Effort | Priority |
|---|------------|--------|----------|
| 1 | Add Redis hot-path caching (target p99 < 10ms for flag evaluation) | M | P0 |
| 2 | Implement percentage-based rollouts (gradual feature release) | M | P1 |
| 3 | Add client SDK for other services (avoid REST call per flag check) | L | P1 |
| 4 | Implement A/B testing with metrics integration | L | P3 |
| 5 | Add unit + integration tests | L | P0 |

**Dependencies:** Redis cluster (infra), Micrometer metrics (A/B experiment tracking).

---

## Section 5: Platform-Level Improvements

### 5.1 API Gateway & BFF Layer (MISSING)

**Current State:** Istio VirtualService does basic prefix routing. No API gateway with cross-cutting concerns.

**Required:**
- **API Gateway:** Kong, AWS API Gateway, or Envoy-based gateway for:
  - Centralized rate limiting (replace per-service ConcurrentHashMap)
  - Request/response transformation
  - API key management for B2B partners
  - Request logging and analytics
  - Global CORS policy
- **BFF (Backend-For-Frontend):**
  - Mobile BFF: Aggregate product + pricing + inventory for product list (reduces 3 calls to 1)
  - Web BFF: SSR support for SEO on catalog pages
  - Rider App BFF: Aggregate delivery + routing + earnings for rider dashboard

**Estimated Effort:** XL (8-12 weeks)

### 5.2 Data Platform & Analytics (MISSING)

**Current State:** Each service has its own PostgreSQL. No analytics pipeline, no data warehouse, no BI tools.

**Required:**
- **Data Lake:** Kafka → Cloud Storage → BigQuery pipeline
- **Real-time Analytics:** Kafka Streams or Flink for:
  - Live order volume dashboards
  - Real-time inventory levels across stores
  - Delivery SLA tracking (% orders under 10 min)
- **BI Integration:** Metabase/Looker for business teams
- **Data Catalog:** Column-level lineage for GDPR compliance
- **Reporting:** Daily GMV, AOV, delivery times, rider utilization

**Estimated Effort:** XL (12-16 weeks)

### 5.3 ML/AI Integration Points (MISSING)

**Current State:** Zero ML infrastructure. Fraud detection is rule-based only. No personalization, no demand forecasting.

**Required:**
| Use Case | Model Type | Impact |
|----------|-----------|--------|
| Fraud detection | Gradient Boosted Trees / Neural Network | Reduce fraud losses from ~2% to <0.5% of GMV |
| Search ranking | Learning to Rank (LambdaMART) | +15-20% conversion rate |
| Demand forecasting | Time series (Prophet / LSTM) | Reduce stockouts by 30-40% |
| Dynamic pricing | Reinforcement learning | +5-10% margin |
| ETA prediction | Gradient Boosted Regression | Improve ETA accuracy from ±5min to ±2min |
| Personalization | Collaborative filtering | +10-15% AOV |
| Rider assignment | Optimization (OR-tools) | Reduce average delivery time by 2-3 min |

**ML Platform Requirements:** Feature store, model registry, A/B testing framework, model serving (TFServing/Triton)
**Estimated Effort:** XL (16-24 weeks for platform; models are ongoing)

### 5.4 Multi-Region Deployment Strategy

**Current State:** Single GKE region (asia-south1). No read replicas. Single Kafka cluster.

**Target Architecture:**
```
Region 1 (Primary — Mumbai):     Region 2 (DR — Hyderabad):
  GKE Cluster                      GKE Cluster (warm standby)
  Cloud SQL (Primary)               Cloud SQL (Read Replica)
  Kafka Cluster                     Kafka MirrorMaker 2
  Redis Cluster                     Redis Replica
  Temporal (Primary)                Temporal (Standby namespace)
```

**Strategy:**
- **Phase 1:** Add read replicas for Cloud SQL in same region (HA)
- **Phase 2:** Cross-region replication for DR
- **Phase 3:** Active-active multi-region for scale (city-based routing)

**Estimated Effort:** XL (12-16 weeks)

### 5.5 Disaster Recovery Plan

| Scenario | RTO | RPO | Recovery Action |
|----------|-----|-----|----------------|
| Single pod failure | 0 min | 0 | K8s auto-restart (HPA/PDB) |
| Single node failure | 2 min | 0 | Pod anti-affinity + PDB |
| Availability zone failure | 5 min | 0 | Multi-AZ GKE + Cloud SQL HA |
| Region failure | 30 min | 5 min | Failover to DR region |
| Database corruption | 1 hour | 5 min | Point-in-time recovery from backups |
| Kafka cluster failure | 15 min | 1 min | MirrorMaker failover to DR cluster |
| Temporal namespace failure | 10 min | 0 | Namespace migration to standby |
| Full platform failure | 4 hours | 15 min | Rebuild from IaC + restore from backups |

**Required:**
- Automated DB backups every 5 min (currently unverified)
- Kafka MirrorMaker 2 for cross-region topic replication
- Regular DR drills (quarterly)
- Runbooks for each scenario

**Estimated Effort:** L (6-8 weeks)

### 5.6 Load Testing & Capacity Planning

**Current State:** Zero load testing. No baseline performance data. HPA is CPU-only.

**Required:**
- **Load Test Suite:** k6 or Gatling scripts for:
  - Search: 100K QPS target (p99 < 200ms)
  - Cart operations: 500K ops/min target (p99 < 20ms)
  - Checkout: 50K concurrent sagas (p99 < 5s)
  - Payment: 10K TPS (p99 < 2s)
  - WebSocket: 100K concurrent tracking connections
- **Capacity Model:**
  - Per-service: CPU, memory, DB connections at 1x, 5x, 10x, 20x current load
  - Infrastructure: Kafka partitions, Redis memory, DB storage growth
  - Cost projection: Monthly cost at each scale tier
- **Performance Benchmarks:**
  - Establish p50, p95, p99 baselines for each API endpoint
  - Set HPA thresholds based on actual load data (not just CPU)
  - Identify bottleneck services at each scale tier

**Estimated Effort:** L (4-6 weeks)

### 5.7 Cost Optimization Strategy

**Current Resource Allocation (from Helm values):**

| Service | CPU Request | Memory Request | Notes |
|---------|------------|----------------|-------|
| High compute (inventory, order, checkout, rider, routing, fraud) | 500m | 768Mi | Appropriate |
| Medium compute (identity, catalog, payment, pricing, wallet, audit) | 250m | 512Mi | May be over-provisioned |
| Low compute (notification, config-feature-flag) | 200-250m | 384Mi | Appropriate |
| Resource quota total | 24 CPU | 48Gi | Max 48 CPU, 96Gi limits |

**Optimization Opportunities:**
1. **Spot/Preemptible nodes** for non-critical services (search, notification, audit) — 60-80% cost reduction
2. **Right-size databases:** Cart and config-feature-flag don't need full PostgreSQL; use Redis
3. **Autoscaling tuning:** HPA based on actual metrics, not CPU-only
4. **Cold storage tiering:** Move old orders/audit logs to cheaper storage
5. **Reserved instances** for baseline capacity (identity, payment, inventory)
6. **Estimated monthly savings:** 30-40% with proper optimization at scale

**Estimated Effort:** M (2-4 weeks)

---

## Section 6: Competitive Analysis

### Feature Comparison Matrix

| Capability | Zepto | Blinkit | Instacart | DoorDash | **Instacommerce** |
|-----------|-------|---------|-----------|----------|-------------------|
| **Delivery Promise** | 10 min | 10 min | 1-2 hours | 30-60 min | **10 min (target)** |
| **Dark Stores** | 3,000+ | 600+ cities | 0 (retailer stores) | 0 (restaurant/store) | **Architecture ready, 0 live** |
| **Product Catalog** | 10K+ SKUs/store | 8K+ SKUs | 1M+ (marketplace) | 500K+ | **Schema ready, 0 SKUs** |
| **Search** | OpenSearch, ML-ranked | Elasticsearch | ML-ranked, personalized | ML-ranked | **PostgreSQL only ❌** |
| **Dynamic Pricing** | Real-time surge | Surge + flash sales | Zone-based | Real-time | **Rules engine only ❌** |
| **Payment Methods** | UPI, cards, wallets | UPI, cards, Zomato Credit | Cards, Apple Pay | Cards, DashPass | **Stripe only ❌** |
| **Fraud Detection** | ML + rules | ML + rules | ML (Sift Science) | ML ($100M+ saved) | **Rules only ❌** |
| **Rider Fleet** | 50K+ riders | 200K+ riders | Gig shoppers | 6M+ Dashers | **Architecture ready, 0 riders** |
| **Personalization** | ML-driven | ML-driven | Deep personalization | ML-driven | **None ❌** |
| **Loyalty/Wallet** | Zepto Cash | Zomato Credits | Instacart+ ($99/yr) | DashPass ($9.99/mo) | **Architecture ready ✅** |
| **Analytics** | Real-time dashboards | Integrated with Zomato | Retailer analytics | Merchant analytics | **None ❌** |
| **Multi-region** | Pan-India | Pan-India | US, Canada, 7800+ cities | 27 countries | **Single region ❌** |
| **Mobile Apps** | iOS + Android | iOS + Android + web | iOS + Android + web | iOS + Android + web | **API only ❌** |
| **Customer Support** | In-app chat | In-app + Zomato support | In-app + phone | In-app + phone | **None ❌** |
| **Subscription** | Zepto Pass | Zomato Gold | Instacart+ | DashPass | **None ❌** |

### Critical Missing Capabilities (vs. Competitors)

| # | Capability | Competitor Reference | Impact | Effort |
|---|-----------|---------------------|--------|--------|
| 1 | **ML-powered search** | All competitors use ML ranking | 70% of sessions start with search; poor search = lost revenue | XL |
| 2 | **UPI/Indian payment methods** | Zepto/Blinkit process 80%+ via UPI | Cannot operate in India without UPI | L |
| 3 | **Real-time analytics** | All competitors have live dashboards | Cannot make data-driven operational decisions | XL |
| 4 | **Mobile applications** | All competitors are mobile-first | Q-commerce is 95%+ mobile | XL |
| 5 | **Customer support system** | All competitors have in-app support | No way to handle complaints, refund requests | L |
| 6 | **Subscription/membership** | DashPass, Instacart+, Zepto Pass | Drives retention and AOV | M |
| 7 | **Demand forecasting** | Zepto/Blinkit optimize inventory daily | Without forecasting, stockouts are unmanaged | L |
| 8 | **Multi-language support** | Blinkit supports Hindi, regional | India market requires regional language support | M |
| 9 | **Merchant/partner portal** | Instacart provides retailer dashboards | Cannot onboard partners without portal | L |
| 10 | **Referral marketing engine** | All competitors use viral referrals | Service exists but no campaign management | M |

### Competitive Positioning Summary

**Strengths vs. Competitors:**
- Modern architecture (18 microservices, event-driven, Temporal sagas)
- GitOps deployment (ArgoCD, Terraform IaC)
- Istio service mesh with mTLS
- Double-entry payment ledger
- Comprehensive domain decomposition aligned with industry best practices

**Critical Gaps vs. Competitors:**
- Zero operational capability (no stores, riders, SKUs, users)
- No ML/AI across any domain
- No mobile applications
- Single payment provider (Stripe only — no UPI, no wallets)
- No analytics or business intelligence
- No customer support infrastructure

**Assessment:** The platform has strong architectural foundations but is **pre-alpha** — the gap to competitors is **operational maturity** and **ML-driven intelligence**, not architecture.

---

## Section 7: Production Launch Checklist

### Phase 1: Pre-Launch (T-8 to T-2 weeks)

#### Security Audit ✅/❌

- [ ] **S-01:** Fix `/auth/revoke` authentication bypass
- [ ] **S-02:** Add Spring Security to notification-service
- [ ] **S-03:** Replace ConcurrentHashMap rate limiters (identity, order, catalog)
- [ ] **S-04:** Add `@PreAuthorize` to all admin endpoints
- [ ] **S-05:** Configure CORS in all 18 services
- [ ] **S-06:** Fix webhook signature header `required=false`
- [ ] **S-07:** Add `aud`/`jti` JWT claims
- [ ] **S-08:** Implement service-to-service authentication
- [ ] **S-09:** Fix Stripe API key thread-safety
- [ ] **S-10:** Add pod securityContext (runAsNonRoot, readOnlyRootFilesystem)
- [ ] **S-11:** Deploy NetworkPolicies
- [ ] **S-12:** Third-party penetration test
- [ ] **S-13:** Dependency vulnerability scan (OWASP dependency-check)
- [ ] **S-14:** SAST scan (SonarQube/Semgrep)

#### Data Integrity ✅/❌

- [ ] **D-01:** Fix PSP calls inside `@Transactional` (payment-service)
- [ ] **D-02:** Add pessimistic locking on refunds
- [ ] **D-03:** Fix refund ledger debit/credit reversal
- [ ] **D-04:** Implement inventory event publishing
- [ ] **D-05:** Fix PaymentVoided amount field
- [ ] **D-06:** Fix OrderPlaced missing SKU
- [ ] **D-07:** Add schemas for 5 undocumented events
- [ ] **D-08:** Reconcile PaymentCaptured schema versions
- [ ] **D-09:** Add post-checkout cancellation refund flow
- [ ] **D-10:** Fix refund over-calculation (discount-aware)

#### Resilience ✅/❌

- [ ] **R-01:** Add timeouts to ALL REST clients (connect: 5s, read: 10s)
- [ ] **R-02:** Add Resilience4j circuit breakers to all REST clients
- [ ] **R-03:** Fix `Thread.sleep(5min)` in notification retry
- [ ] **R-04:** Add Kafka `DefaultErrorHandler` + DLQ in all consumers
- [ ] **R-05:** Add Istio DestinationRules with outlier detection
- [ ] **R-06:** Extract HTTP calls from `@Transactional` in fulfillment PickService
- [ ] **R-07:** Fix health check Redis references

#### Testing ✅/❌

- [ ] **T-01:** Unit tests for all services (minimum 60% coverage)
- [ ] **T-02:** Integration tests with Testcontainers (DB, Kafka, Redis)
- [ ] **T-03:** Contract tests for all published events
- [ ] **T-04:** API endpoint tests for all controllers
- [ ] **T-05:** Load test: search (100K QPS)
- [ ] **T-06:** Load test: checkout (50K concurrent)
- [ ] **T-07:** Load test: cart (500K ops/min)
- [ ] **T-08:** Chaos engineering: single service failure recovery
- [ ] **T-09:** Chaos engineering: database failover
- [ ] **T-10:** Chaos engineering: Kafka partition leader election

#### Infrastructure ✅/❌

- [ ] **I-01:** Add rolling update strategy (maxUnavailable: 0, maxSurge: 1)
- [ ] **I-02:** Add pod anti-affinity (zone-aware)
- [ ] **I-03:** Configure HPA with custom metrics (not CPU-only)
- [ ] **I-04:** Add HPA for notification-service
- [ ] **I-05:** Fix Terraform state locking
- [ ] **I-06:** Add distributed locking for `@Scheduled` jobs
- [ ] **I-07:** Add resource quotas and LimitRanges
- [ ] **I-08:** Configure DB connection pool tuning (HikariCP)
- [ ] **I-09:** Set up DB read replicas
- [ ] **I-10:** Provision Redis cluster
- [ ] **I-11:** Set up Kafka dead letter topics
- [ ] **I-12:** Configure Kafka consumer concurrency > 1

#### Observability ✅/❌

- [ ] **O-01:** Deploy Grafana with per-service dashboards
- [ ] **O-02:** Add Micrometer business metrics to all services
- [ ] **O-03:** Configure Prometheus alerting rules (SLO-based)
- [ ] **O-04:** Verify distributed tracing end-to-end across all 18 services
- [ ] **O-05:** Set up centralized log aggregation (ELK/Loki)
- [ ] **O-06:** Configure correlation ID propagation across all services
- [ ] **O-07:** Create SLI/SLO dashboards
- [ ] **O-08:** Set up PagerDuty/OpsGenie integration

#### Compliance ✅/❌

- [ ] **C-01:** Enforce append-only audit trail at DB level
- [ ] **C-02:** PCI-DSS network segmentation for payment-service
- [ ] **C-03:** GDPR consent management in identity-service
- [ ] **C-04:** Data retention policies (auto-purge old data)
- [ ] **C-05:** Privacy policy and terms of service
- [ ] **C-06:** Cookie consent mechanism (if web frontend)

---

### Phase 2: Launch Day (T-0)

#### Monitoring ✅/❌

- [ ] **L-01:** All Grafana dashboards verified and visible in war room
- [ ] **L-02:** Alerting rules firing correctly (test with synthetic alerts)
- [ ] **L-03:** Real-time order volume dashboard live
- [ ] **L-04:** Payment success/failure rate dashboard live
- [ ] **L-05:** Delivery SLA tracking dashboard live (% under 10 min)
- [ ] **L-06:** Error rate per service < 0.1% baseline established
- [ ] **L-07:** Kafka consumer lag monitoring active
- [ ] **L-08:** DB connection pool utilization visible

#### On-Call ✅/❌

- [ ] **L-09:** On-call rotation established (primary + secondary)
- [ ] **L-10:** Escalation matrix documented and tested
- [ ] **L-11:** War room Slack channel created
- [ ] **L-12:** On-call engineers have prod access verified
- [ ] **L-13:** PagerDuty/OpsGenie routing rules tested

#### Rollback Plan ✅/❌

- [ ] **L-14:** ArgoCD rollback tested for each service
- [ ] **L-15:** Database migration rollback scripts verified
- [ ] **L-16:** Feature flags configured for kill-switch on new features
- [ ] **L-17:** Traffic shifting capability tested (canary deployment)
- [ ] **L-18:** Rollback decision criteria documented (error rate > X%, latency > Y)
- [ ] **L-19:** Rollback can be executed in < 5 minutes

#### Launch Execution ✅/❌

- [ ] **L-20:** Gradual traffic ramp: 1% → 5% → 25% → 50% → 100%
- [ ] **L-21:** Feature flags controlling new service endpoints
- [ ] **L-22:** Real-time smoke tests running against production
- [ ] **L-23:** Payment reconciliation verified after first 100 orders
- [ ] **L-24:** First delivery completed end-to-end and verified

---

### Phase 3: Post-Launch (T+1 day to T+4 weeks)

#### SLO Tracking ✅/❌

- [ ] **P-01:** All services meeting defined SLOs (weekly review)
- [ ] **P-02:** Error budget tracking established
- [ ] **P-03:** SLO breaches trigger incident review
- [ ] **P-04:** Monthly SLO report to engineering leadership
- [ ] **P-05:** SLO-based alerting tuned (reduce noise)

#### Incident Response ✅/❌

- [ ] **P-06:** Incident classification documented (P0-P4)
- [ ] **P-07:** Incident response playbook tested
- [ ] **P-08:** Post-incident review process established
- [ ] **P-09:** Blameless postmortem template created
- [ ] **P-10:** Incident metrics tracked (MTTD, MTTR, incident count)
- [ ] **P-11:** Recurring incident patterns identified and addressed

#### Capacity Review ✅/❌

- [ ] **P-12:** Week 1: Review actual vs. projected resource usage
- [ ] **P-13:** Week 2: Right-size all service resource requests/limits
- [ ] **P-14:** Week 2: Tune HPA thresholds based on actual traffic
- [ ] **P-15:** Week 3: Database query performance review
- [ ] **P-16:** Week 4: Kafka partition rebalancing review
- [ ] **P-17:** Week 4: Full capacity planning for 3x/5x/10x growth

#### Operational Hygiene ✅/❌

- [ ] **P-18:** Runbooks created for top 10 failure scenarios
- [ ] **P-19:** DR test executed (region failover simulation)
- [ ] **P-20:** Dependency update schedule established
- [ ] **P-21:** Security patch cadence defined (< 48h for critical CVEs)
- [ ] **P-22:** Cost optimization review (spot instances, right-sizing)
- [ ] **P-23:** Technical debt backlog triaged and prioritized

---

## Appendix A: Effort Estimation Guide

| Size | Duration | Team | Description |
|------|----------|------|-------------|
| S | 1-3 days | 1 engineer | Config change, bug fix, minor refactor |
| M | 1-2 weeks | 1-2 engineers | Feature addition, moderate refactor, integration |
| L | 2-4 weeks | 2-3 engineers | Service refactor, new integration, test suite |
| XL | 4-12 weeks | 3-5 engineers | New service/platform, architecture change |

## Appendix B: Priority Definitions

| Priority | Meaning | Timeline |
|----------|---------|----------|
| P0 | Production blocker — must fix before any deployment | Immediate |
| P1 | GA blocker — must fix before general availability | Before launch |
| P2 | Scale blocker — must fix before 10x growth | Within 3 months post-launch |
| P3 | Nice-to-have — improves UX/DX but not blocking | Backlog |

## Appendix C: Finding Cross-Reference

This document consolidates findings from 8 prior reviews:

| Source Document | Findings | Status |
|----------------|----------|--------|
| `services/identity-service/REVIEW-REPORT.md` | 34 findings | Consolidated here |
| `docs/code-review-order-payment.md` | 45 findings | Consolidated here |
| `services/REVIEW_FINDINGS.md` (catalog + inventory) | 38 findings | Consolidated here |
| `services/REVIEW-fulfillment-notification.md` | 41 findings | Consolidated here |
| `docs/INFRASTRUCTURE_AUDIT_REPORT.md` | 65 findings | Consolidated here |
| `docs/contracts-review-findings.md` | 27 findings | Consolidated here |
| `docs/SECURITY-AUDIT-REPORT.md` | 28 findings | Consolidated here |
| `docs/CONSOLIDATED-REVIEW.md` | 200+ findings (master) | Superseded by this document |

**Total unique findings across all reviews:** 200+ (36 critical, 85 high, 95 medium, 52 low)

## Appendix D: Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-02-07 | Chief Architect | Initial creation — consolidated all prior reviews |
| 1.1 | 2026-02-07 | Chief Architect | Status update — Phase 6 fixes completed; remaining gaps summarized |

---

> **This document is the SINGLE SOURCE OF TRUTH** for Instacommerce platform production readiness.
> All engineering decisions, sprint planning, and launch criteria should reference this document.
> Updated as findings are addressed — track progress via the checklists in Section 7.
