# InstaCommerce Production Issue Register
## Iteration 3 — Consolidated Findings

**Purpose:** Consolidated, actionable issue register for InstaCommerce production q-commerce backend  
**Audience:** CTO, Principal Engineers, Engineering Managers, SRE, Platform, Security  
**Source:** Iteration 3 reviews, service-specific deep dives, platform guides, contract audits, and direct code inspection  
**Classification:** Implementation-heavy, easy to consume, production-ready tracking  
**Last updated:** 2026-03-07

---

## How to Use This Register

- **Severity:** P0 (must-fix immediately), P1 (next-wave critical), P2 (important but not blocking)
- **Blast Radius:** User-facing impact scope (all users, all orders, specific flows, operational only)
- **Affected Services:** Specific service modules or platform components
- **Evidence/Source:** File path, line number, or review document reference
- **Failure Mode:** What breaks in production under what conditions
- **Customer/Business Impact:** Revenue, trust, operational cost, or compliance risk
- **Remediation Direction:** Concrete fix approach (not vague recommendations)
- **Owner Class:** Which team owns the fix (Service, Platform, SRE, Security, Data/ML)
- **Wave:** Implementation program wave (0-6 based on dependency graph)
- **Dependency Notes:** Blockers or sequencing constraints

---

## P0 Issues — Must Fix Immediately

### C1-P0-01: Shared Internal Token Grants Overbroad Privilege

| Field | Value |
| ------- | ------- |
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | Full mesh — any compromised pod can impersonate any other service |
| **Affected Services** | All 28 services (20 Java, 8 Go) |
| **Evidence** | `services/*/src/main/resources/application.yml` — `INTERNAL_SERVICE_TOKEN` env var; `InternalServiceAuthFilter.java` grants `ROLE_ADMIN` to every internal caller; `services/go-shared/pkg/auth/internal.go` — single flat token |
| **Failure Mode** | Single token compromise → lateral movement across all services → privilege escalation → admin endpoint access |
| **Customer Impact** | ⚠️ Security breach enables unauthorized data access, order manipulation, payment fraud |
| **Business Impact** | Regulatory (PCI, GDPR), reputational damage, potential financial loss |
| **Remediation Direction** | Replace with per-service JWT or Workload Identity; implement Istio `AuthorizationPolicy` with service-level RBAC; remove `ROLE_ADMIN` grant from internal filter |
| **Owner Class** | Security + Platform |
| **Wave** | Wave 0 (blocking) |
| **Dependencies** | Must complete before any production scale-up |

---

### C1-P0-02: Admin Gateway Has No Application-Layer Auth

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | All admin operations (user management, refunds, order cancellation, inventory overrides) |
| **Affected Services** | `admin-gateway-service` |
| **Evidence** | `services/admin-gateway-service/src/main/java/` — no `SecurityConfig` class, no JWT filter, port 8098 exposed |
| **Failure Mode** | Any mesh-authenticated caller can invoke admin routes; no RBAC enforcement |
| **Customer Impact** | ⚠️ Admin impersonation enables fraudulent refunds, order cancellations, data exfiltration |
| **Business Impact** | Financial loss, compliance violation, operational chaos |
| **Remediation Direction** | Add `SecurityConfig` with JWT validation + dedicated `ROLE_SUPER_ADMIN` claim; network-isolate admin surface via separate Istio VirtualService with stricter auth; implement admin audit logging |
| **Owner Class** | Security + Service Team |
| **Wave** | Wave 0 (blocking) |
| **Dependencies** | Must implement before `admin-gateway-service` receives production traffic |

---

### C1-P0-03: Pod-Local Rate Limiting Bypassed Under Multi-Replica Deployment

| Field | Value |
| ------- | ------- |
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | Identity service — login, registration, password reset endpoints |
| **Affected Services** | `identity-service` |
| **Evidence** | `RateLimitService.java` — Resilience4j + Caffeine in-memory cache per JVM; `application.yml` — `loginLimiter: 5/min`, `registerLimiter: 3/min` |
| **Failure Mode** | Attacker distributes brute-force login across N replicas → effective rate = N × 5/min; bypasses intended 5 req/min per IP |
| **Customer Impact** | 🔒 Account takeover, credential stuffing, registration spam |
| **Business Impact** | Security breach, compliance violation, operational abuse |
| **Remediation Direction** | Replace Caffeine with Redis-backed distributed rate limiter (e.g., Bucket4j + Redis); implement rate limiting at Istio EnvoyFilter layer for defense-in-depth |
| **Owner Class** | Security + Service Team |
| **Wave** | Wave 0 |
| **Dependencies** | Requires Redis deployment or shared Redis cluster |

---

### C2-P0-04: Dual Checkout Authority — Saga Logic Divergence

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | All orders — every checkout flow |
| **Affected Services** | `checkout-orchestrator-service` (port 8089), `order-service` (port 8085) |
| **Evidence** | `checkout-orchestrator-service/workflow/CheckoutWorkflowImpl.java` — calls `PricingActivity`; `order-service/workflow/CheckoutWorkflowImpl.java` — computes inline from `item.unitPriceCents()` |
| **Failure Mode** | Order-service checkout endpoint bypasses pricing validation, coupon application, delivery fee calculation; charges client-supplied price |
| **Customer Impact** | ⚠️ Price manipulation → revenue loss; incorrect order totals → customer disputes |
| **Business Impact** | Direct revenue leakage, financial reconciliation failures |
| **Remediation Direction** | Deprecate and remove `CheckoutController` + `CheckoutWorkflow` from `order-service`; route all checkout traffic to `checkout-orchestrator-service`; add feature flag `order.checkout.direct-saga.enabled=false` in prod; verify zero traffic via metrics; delete dead code |
| **Owner Class** | Service Team (Order + Checkout) |
| **Wave** | Wave 1 |
| **Dependencies** | Gateway routing must point to orchestrator endpoint only |

---

### C2-P0-05: Payment Idempotency Collision Risk — Workflow ID Reuse

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | Payment authorization — duplicate charges possible |
| **Affected Services** | `checkout-orchestrator-service`, `payment-service` |
| **Evidence** | `CheckoutController.java` line 60–67 — DB cache 30-min TTL; Temporal workflow execution timeout 5 min; `WorkflowExecutionAlreadyStarted` not caught |
| **Failure Mode** | Client retries after TTL expiry → new workflow launched with same ID → collision → HTTP 500; OR payment activity generates non-unique idempotency key → duplicate charge |
| **Customer Impact** | ⚠️ Double payment charges, customer disputes, refund overhead |
| **Business Impact** | Payment processor disputes, chargebacks, customer trust erosion |
| **Remediation Direction** | Catch `WorkflowExecutionAlreadyStarted` exception and return cached response or query workflow status; extend idempotency key to include `activityId` in payment activity: `workflowId + "-payment-" + activityId`; increase DB cache TTL to 60 min |
| **Owner Class** | Service Team (Checkout + Payment) |
| **Wave** | Wave 1 |
| **Dependencies** | Temporal workflow stability monitoring |

---

### C3-P0-06: Catalog → Search Indexing Path Is Non-Functional

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | All search queries — stale or missing products |
| **Affected Services** | `catalog-service`, `search-service` |
| **Evidence** | `catalog-service/publisher/LoggingOutboxEventPublisher.java` — `publish()` method logs only, no Kafka; `build.gradle.kts` — no Kafka dependency; `ProductChangedEvent.java` — missing 7 fields (`basePriceCents`, `unitValue`, `weightGrams`, etc.) |
| **Failure Mode** | Product updates never reach search index; new products invisible; price/availability changes not reflected |
| **Customer Impact** | 🛒 Users see out-of-stock products, incorrect prices, missing new products → failed checkouts → cart abandonment |
| **Business Impact** | Revenue loss (hidden inventory), customer frustration, operational confusion |
| **Remediation Direction** | Replace `LoggingOutboxEventPublisher` with Kafka-backed implementation; add `spring-kafka` dependency; emit full `ProductChangedEvent` payload with all required fields; implement event-driven search reindex via Kafka consumer in `search-service` |
| **Owner Class** | Service Team (Catalog + Search) |
| **Wave** | Wave 2 |
| **Dependencies** | Kafka topic `catalog.events` must exist; consumer group registration |

---

### C3-P0-07: Cart → Pricing API Mismatch and Promotion Bug

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | All cart operations — pricing errors |
| **Affected Services** | `cart-service`, `pricing-service` |
| **Evidence** | `cart-service/client/PricingClient.java` — hardcoded base URL wrong; response field `unitPriceCents` vs contract `priceCents`; `pricing-service/PricingService.java` — `validateCoupon` is `@Transactional(readOnly=true)`; `redeemCoupon()` never called |
| **Failure Mode** | Cart calculates wrong price; coupon validation doesn't prevent double-redemption; promotion misapplication |
| **Customer Impact** | ⚠️ Overcharges or undercharges → customer disputes, refund requests |
| **Business Impact** | Revenue loss (undercharges), customer trust loss (overcharges), financial reconciliation failures |
| **Remediation Direction** | Fix `PricingClient` base URL via config property; align field naming in DTOs; change `validateCoupon` to read-write transaction and mark coupon as used; call `redeemCoupon()` atomically during pricing calculation; add integration test |
| **Owner Class** | Service Team (Cart + Pricing) |
| **Wave** | Wave 2 |
| **Dependencies** | Contract alignment on field naming |

---

### C4-P0-08: Inventory Reservation Concurrency — Double-Confirm Bug

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | Stock reservations — negative reserved counts |
| **Affected Services** | `inventory-service` |
| **Evidence** | `ReservationService.java` — `confirm()` locks `StockItem` but not `Reservation` row; two concurrent confirms both read `status == PENDING` → both decrement reserved → `CHECK (reserved >= 0)` violation → `DataIntegrityViolationException` → HTTP 500 |
| **Failure Mode** | Concurrent confirm requests cause DB constraint violation; surfaces as 500 error instead of clean 409 |
| **Customer Impact** | 🛒 Failed checkouts due to 500 error; operational confusion; stock inconsistency |
| **Business Impact** | Order fulfillment failures, operational overhead, customer churn |
| **Remediation Direction** | Add pessimistic lock on `Reservation` row during confirm: `setLockMode(LockModeType.PESSIMISTIC_WRITE)`; catch `DataIntegrityViolationException` in `GlobalExceptionHandler` and map to HTTP 409; add lock timeout hint |
| **Owner Class** | Service Team (Inventory) |
| **Wave** | Wave 2 |
| **Dependencies** | None |

---

### C4-P0-09: Inventory → Warehouse StoreId Gap — No Referential Integrity

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | Inventory reservations — phantom stores |
| **Affected Services** | `inventory-service`, `warehouse-service` |
| **Evidence** | `inventory-service` — `store_id VARCHAR(50)` free-form; `warehouse-service` — `id UUID`; no foreign key, no validation |
| **Failure Mode** | Reservations created against non-existent, inactive, or maintenance stores; no runtime validation |
| **Customer Impact** | 🚚 Orders placed for stores that can't fulfill → fulfillment failures → customer disappointment |
| **Business Impact** | Operational chaos, failed SLA, customer churn |
| **Remediation Direction** | Add warehouse lookup validation in `ReservationService.reserve()`; implement store existence check via warehouse-service API call; add store status cache with TTL; define canonical store ID format; add migration to align identifier spaces |
| **Owner Class** | Service Team (Inventory + Warehouse) |
| **Wave** | Wave 2 |
| **Dependencies** | Warehouse API must expose store lookup endpoint |

---

### C5-P0-10: Dispatch Owner Absent — No Assignment Logic

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | All deliveries — rider assignment undefined |
| **Affected Services** | `fulfillment-service`, `dispatch-optimizer-service`, `rider-fleet-service` |
| **Evidence** | `fulfillment-service` publishes `OrderPlaced` event; `dispatch-optimizer-service` consumes but doesn't own assignment; `rider-fleet-service` tracks status but doesn't assign |
| **Failure Mode** | No service owns rider-to-order assignment; manual workaround or external tool used; no automated dispatch |
| **Customer Impact** | 🚚 Delivery delays, missed 30-min SLA, customer churn |
| **Business Impact** | SLA breach, operational inefficiency, lost revenue |
| **Remediation Direction** | Designate `dispatch-optimizer-service` as assignment owner; implement assignment API endpoint; emit `RiderAssigned` event; add Temporal workflow for dispatch saga with timeout and reassignment logic; integrate ETA service for routing optimization |
| **Owner Class** | Service Team (Fulfillment + Dispatch) |
| **Wave** | Wave 3 |
| **Dependencies** | ETA service must provide route optimization API |

---

### C8-P0-11: Event Envelope Inconsistency — Field Naming Split

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | All event consumers — parsing failures |
| **Affected Services** | `outbox-relay-service`, `contracts/`, all event consumers |
| **Evidence** | `contracts/src/main/resources/schemas/common/EventEnvelope.v1.json` — requires `id`; `outbox-relay/main.go:buildEventMessage` — emits `eventId` and `id` (camelCase); README claims `event_id` (snake_case) |
| **Failure Mode** | Consumers parse `event_id` but receive `eventId` → field missing → null pointer or validation failure |
| **Customer Impact** | 🚨 Event-driven flows break → async operations fail → data inconsistency |
| **Business Impact** | Operational failures, data quality issues, debugging overhead |
| **Remediation Direction** | Standardize on snake_case (`event_id`, `event_type`, etc.) in envelope spec; update `outbox-relay` to emit snake_case; update JSON Schema to require snake_case; add CI validation job to enforce envelope shape |
| **Owner Class** | Platform + Contracts |
| **Wave** | Wave 1 |
| **Dependencies** | All consumers must handle both naming conventions during migration |

---

### C8-P0-12: Contracts Not CI-Enforced — Ghost Events Exist

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | All event-driven flows — silent breakage |
| **Affected Services** | `contracts/`, all producers/consumers |
| **Evidence** | `.github/workflows/ci.yml` — no `contracts/**` in path filters; no breaking-change detection job; `contracts/README.md` claims CI enforcement but doesn't exist |
| **Failure Mode** | Breaking schema changes merge silently; consumers break at runtime; ghost schemas exist for cart, pricing, wallet, routing-eta |
| **Customer Impact** | 🚨 Production incidents, async operation failures, data loss |
| **Business Impact** | Operational incidents, debugging overhead, trust erosion |
| **Remediation Direction** | Add `contracts/**` to CI path filters; implement schema validation job using JSON Schema CLI; add breaking-change detection with backwards-compatibility check; add `CODEOWNERS` per domain schema; implement C0-C5 change classification policy |
| **Owner Class** | Platform + Contracts |
| **Wave** | Wave 1 |
| **Dependencies** | CI infrastructure for schema validation tooling |

---

### Infra-P0-13: Image Registry Mismatch Breaks Deploy Lineage

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | All deployments — wrong images deployed |
| **Affected Services** | CI/CD pipeline, ArgoCD, all services |
| **Evidence** | `.github/workflows/ci.yml` — pushes to `docker.io/instacommerce/*:dev`; `deploy/helm/values-dev.yaml` — references different registry or tag pattern |
| **Failure Mode** | ArgoCD deploys stale or wrong images; git SHA doesn't match deployed image |
| **Customer Impact** | 🚨 Production deploys wrong code → bugs, regressions, outages |
| **Business Impact** | Production incidents, rollback overhead, trust loss |
| **Remediation Direction** | Align CI push target with Helm values; use SHA-based tags instead of `dev` string tag; add post-push verification step; implement deploy artifact verification in ArgoCD hook |
| **Owner Class** | Platform + SRE |
| **Wave** | Wave 0 |
| **Dependencies** | ArgoCD drift detection must be configured |

---

### Testing-P0-14: Effective Absence of Test Coverage Across Fleet

| Field | Value |
|-------|-------|
| **Severity** | 🔴 P0 Critical |
| **Blast Radius** | All services — no regression safety net |
| **Affected Services** | All services |
| **Evidence** | `./gradlew test` — many services have placeholder tests only; representative services lack integration tests; `services/ai-orchestrator-service` ships with no CI build or test |
| **Failure Mode** | Code changes break production behavior silently; no pre-merge regression detection |
| **Customer Impact** | 🚨 Production bugs, service outages, degraded UX |
| **Business Impact** | Incident overhead, customer churn, velocity drag |
| **Remediation Direction** | Mandate minimum test coverage gate (e.g., 60% line coverage) per service; add Testcontainers-based integration tests for transactional services; add CI test job that blocks merge on failure; prioritize money-path and checkout services |
| **Owner Class** | All Service Teams + Platform |
| **Wave** | Wave 1-6 (phased by service criticality) |
| **Dependencies** | CI test infrastructure, coverage reporting tools |

---

## P1 Issues — Next-Wave Critical

### C1-P1-01: JWT Key Rotation Path Not Defined

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | All authenticated users — token invalidation on rotation |
| **Affected Services** | `identity-service` |
| **Evidence** | `JwksController.java` — exposes one key only; no key versioning; `JwtKeyLoader.java` — loads single keypair from Secret Manager |
| **Failure Mode** | Key rotation forces all users to re-authenticate; no graceful overlap period |
| **Customer Impact** | ⚠️ Mass logout on rotation → poor UX, support overhead |
| **Business Impact** | Customer frustration, support load, trust erosion |
| **Remediation Direction** | Implement multi-key JWKS with `kid` versioning; add key rotation schedule (e.g., 90 days); support overlapping keys for grace period (7 days); update `JwtService` to include `kid` in JWT header; add runbook for rotation procedure |
| **Owner Class** | Security + Service Team |
| **Wave** | Wave 4 |
| **Dependencies** | Secret Manager key versioning support |

---

### C1-P1-02: Mobile BFF Has No Security Chain

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | All mobile app traffic |
| **Affected Services** | `mobile-bff-service` |
| **Evidence** | `MobileBffServiceApplication.java` — no `SecurityConfig`, no JWT filter, stub controller only |
| **Failure Mode** | BFF forwards unauthenticated requests to downstream services; bypass of edge auth |
| **Customer Impact** | 🔒 Unauthorized access to user data, potential abuse |
| **Business Impact** | Security risk, compliance violation |
| **Remediation Direction** | Add `SecurityConfig` with JWT validation; implement request authentication filter; add circuit breaker for downstream calls; add observability (metrics, traces) |
| **Owner Class** | Service Team (BFF) + Security |
| **Wave** | Wave 1 |
| **Dependencies** | Identity service JWKS endpoint must be reachable |

---

### C1-P1-03: Istio AuthorizationPolicy Coverage Gap

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | Internal mesh — lateral movement possible |
| **Affected Services** | All services |
| **Evidence** | `deploy/helm/` — only 2 of 28 services have `AuthorizationPolicy` defined; no namespace-wide DENY default |
| **Failure Mode** | Any pod can reach any internal service post-mTLS; no service-level RBAC |
| **Customer Impact** | 🔒 Security risk — compromised pod can abuse internal APIs |
| **Business Impact** | Lateral movement risk, privilege escalation |
| **Remediation Direction** | Define namespace-wide DENY default `AuthorizationPolicy`; add per-service ALLOW policies with service identity principals; implement RBAC matrix for service-to-service communication |
| **Owner Class** | Platform + Security |
| **Wave** | Wave 0 |
| **Dependencies** | Istio workload identity must be configured |

---

### C2-P1-04: Webhook Durability Gaps — Replay Tolerance Bug

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | Payment webhooks — duplicate processing |
| **Affected Services** | `payment-webhook-service` |
| **Evidence** | `payment-webhook-service/handler/stripe.go` — HMAC validation present but replay window 5 minutes; no idempotency table |
| **Failure Mode** | Attacker replays captured webhook within 5-min window → duplicate payment confirmation → double fulfillment |
| **Customer Impact** | ⚠️ Duplicate orders, overcharges, operational chaos |
| **Business Impact** | Financial loss, customer disputes, reconciliation overhead |
| **Remediation Direction** | Add webhook event ID deduplication table; reduce replay tolerance to 30 seconds; persist processed webhook IDs with TTL; add Kafka produce idempotency |
| **Owner Class** | Service Team (Payment Webhook) |
| **Wave** | Wave 1 |
| **Dependencies** | Database or Redis for idempotency tracking |

---

### C3-P1-05: No Distributed Cache — JVM-Local Caffeine Only

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | Catalog, cart, pricing, search — cache inconsistency |
| **Affected Services** | `catalog-service`, `cart-service`, `pricing-service`, `search-service` |
| **Evidence** | All four services use Caffeine in-process cache; N replicas = N independent caches; no event-driven invalidation |
| **Failure Mode** | Price updates don't invalidate cached values across pods; users see stale prices; cache stampede on promotion launch |
| **Customer Impact** | 🛒 Wrong prices shown → cart abandonment, checkout failures, customer disputes |
| **Business Impact** | Revenue loss, customer trust erosion |
| **Remediation Direction** | Introduce Redis cluster for distributed caching; implement event-driven cache invalidation via Kafka (e.g., `PricingChanged` event); add cache versioning with TTL |
| **Owner Class** | Platform + Service Teams |
| **Wave** | Wave 2 |
| **Dependencies** | Redis cluster deployment |

---

### C4-P1-06: No Stock Reconciliation Mechanism

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | Inventory — stock drift inevitable at scale |
| **Affected Services** | `inventory-service` |
| **Evidence** | No reconciliation job or API; stock drift correction is manual |
| **Failure Mode** | Over time, reserved vs. on_hand drifts due to bugs, timeouts, partial failures; no automated correction |
| **Customer Impact** | 🛒 Overselling or underselling; failed checkouts; operational confusion |
| **Business Impact** | Revenue loss, customer churn, operational overhead |
| **Remediation Direction** | Implement daily stock reconciliation job; compare reserved + on_hand vs. external WMS data; emit `StockDriftDetected` event; add operational override API with audit trail |
| **Owner Class** | Service Team (Inventory) |
| **Wave** | Wave 3 |
| **Dependencies** | External WMS integration or warehouse truth source |

---

### C5-P1-07: No ETA Breach Prediction / Continuous Optimization

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | All deliveries — late deliveries not prevented |
| **Affected Services** | `routing-eta-service`, `dispatch-optimizer-service` |
| **Evidence** | ETA calculated at dispatch time only; no real-time ETA updates based on rider location; no breach alerting |
| **Failure Mode** | Rider delays not detected early; no proactive reassignment; customer surprised by late delivery |
| **Customer Impact** | 🚚 Missed 30-min SLA → customer churn, support load |
| **Business Impact** | SLA breach penalties, reputation damage |
| **Remediation Direction** | Implement continuous ETA recalculation based on rider location pings; add breach prediction threshold (e.g., 80% of window elapsed); emit `ETABreachImminent` event; trigger proactive reassignment workflow |
| **Owner Class** | Service Team (ETA + Dispatch) |
| **Wave** | Wave 4 |
| **Dependencies** | Location ingestion stream must be reliable |

---

### C6-P1-08: Loyalty Retries and Concurrency Unsafe

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | Loyalty points — double-credit risk |
| **Affected Services** | `wallet-loyalty-service` |
| **Evidence** | No explicit evidence documented but flagged in iteration 3 review |
| **Failure Mode** | Retry without idempotency → double-credit of loyalty points; concurrent operations → negative balance |
| **Customer Impact** | 🎁 Loyalty abuse, unfair advantage, financial loss |
| **Business Impact** | Revenue loss, program integrity compromised |
| **Remediation Direction** | Add idempotency key to loyalty credit/debit operations; implement optimistic locking on balance updates; add transaction history with audit trail |
| **Owner Class** | Service Team (Wallet/Loyalty) |
| **Wave** | Wave 3 |
| **Dependencies** | None |

---

### C7-P1-09: Feature Flag Emergency-Stop Semantics Too Weak

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | All feature-flagged behavior — cannot quickly disable bad rollout |
| **Affected Services** | `config-feature-flag-service` |
| **Evidence** | No emergency-stop mechanism documented; flag changes require service restart or polling delay |
| **Failure Mode** | Bad feature rollout causes production incident; disabling flag takes minutes; damage continues |
| **Customer Impact** | 🚨 Extended outage or degraded UX during incident |
| **Business Impact** | Incident duration extended, customer impact increased |
| **Remediation Direction** | Implement push-based flag updates (SSE or WebSocket); add emergency-stop API with instant propagation; add kill switch with < 5 sec latency; implement circuit breaker integration |
| **Owner Class** | Service Team (Config/Feature Flag) |
| **Wave** | Wave 2 |
| **Dependencies** | None |

---

### C8-P1-10: Event Envelope Missing correlation_id and source_service

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | All event-driven flows — observability gap |
| **Affected Services** | `outbox-relay-service`, all event consumers |
| **Evidence** | `outbox-relay/main.go:buildEventMessage` — emits 7 fields; `source_service` and `correlation_id` absent |
| **Failure Mode** | Cannot trace request across services; cannot correlate events to originating request; debugging is manual and slow |
| **Customer Impact** | ⚙️ Indirect — slower incident resolution |
| **Business Impact** | Operational overhead, longer MTTR |
| **Remediation Direction** | Add `source_service` and `correlation_id` to envelope; populate from HTTP headers or workflow context; propagate via Kafka headers; update consumers to extract and propagate |
| **Owner Class** | Platform + Contracts |
| **Wave** | Wave 2 |
| **Dependencies** | HTTP middleware for correlation ID injection |

---

### Data-P1-11: Beam Pipelines Use Processing Time Not Event Time

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | Analytics and ML features — temporal correctness broken |
| **Affected Services** | `data-platform/streaming/pipelines/` |
| **Evidence** | Beam pipeline code uses processing-time windowing instead of event-time; late data dropped |
| **Failure Mode** | Out-of-order events processed incorrectly; analytics metrics skewed; ML features computed on wrong time basis |
| **Customer Impact** | ⚙️ Indirect — wrong ML predictions, incorrect business metrics |
| **Business Impact** | Data quality issues, bad business decisions, ML model degradation |
| **Remediation Direction** | Switch to event-time windowing with watermarks; extract event timestamp from Kafka message or payload; configure allowed lateness (e.g., 1 hour); add late-data side output for monitoring |
| **Owner Class** | Data Engineering |
| **Wave** | Wave 3 |
| **Dependencies** | Event payloads must include reliable timestamp |

---

### ML-P1-12: Production Inference Truth and Shadow Visibility Incomplete

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | ML model deployments — silent degradation risk |
| **Affected Services** | `ml/serving/`, `ai-inference-service` |
| **Evidence** | `ml/serving/shadow_mode.py` — agreement state in-process only; no persistence; no automatic promotion trigger |
| **Failure Mode** | Shadow model agreement rate not tracked durably; cannot detect model degradation; no rollback trigger |
| **Customer Impact** | 🤖 ML predictions degrade silently → worse ETA, fraud detection, search ranking |
| **Business Impact** | Operational inefficiency, customer churn, revenue loss |
| **Remediation Direction** | Persist shadow agreement metrics to BigQuery; implement model drift monitoring with alerts; add automatic rollback on agreement threshold breach; define promotion gate policy |
| **Owner Class** | ML Platform + Data Engineering |
| **Wave** | Wave 4 |
| **Dependencies** | BigQuery sink for metrics, alerting infrastructure |

---

### SRE-P1-13: No Burn-Rate Alerting and Incomplete Java Resilience Posture

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P1 High |
| **Blast Radius** | All services — incident detection delayed |
| **Affected Services** | All services, monitoring stack |
| **Evidence** | `monitoring/prometheus-rules.yaml` — 5 coarse rules only; no burn-rate alerts, no per-service SLO |
| **Failure Mode** | SLA breaches not detected until after error budget exhausted; no early warning; incidents escalate |
| **Customer Impact** | 🚨 Extended outages, degraded UX |
| **Business Impact** | SLA penalties, customer churn, reputation damage |
| **Remediation Direction** | Define SLIs per critical user journey (checkout p99 ≤ 2s, etc.); implement burn-rate alerts (1h, 6h windows); add recording rules for SLI calculations; add Java circuit breaker config (Resilience4j) per downstream call |
| **Owner Class** | SRE + Platform |
| **Wave** | Wave 2 |
| **Dependencies** | Prometheus, alertmanager, runbook per alert |

---

## P2 Issues — Important But Not Immediately Blocking

### C1-P2-01: Redis Uses Plaintext — No TLS

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P2 Medium |
| **Blast Radius** | Cache data — cleartext within VPC |
| **Affected Services** | Redis cluster, all services using Redis |
| **Evidence** | `docker-compose.yml` — Redis port 6379 plaintext; no TLS config in Helm values |
| **Failure Mode** | Cache data (prices, session data, feature flags) transmitted in cleartext within VPC; sniffable by compromised pod |
| **Customer Impact** | 🔒 Low direct impact; security posture weakened |
| **Business Impact** | Compliance risk (PCI for cached payment data) |
| **Remediation Direction** | Enable Redis TLS mode; configure clients with TLS config; rotate to TLS-only endpoint |
| **Owner Class** | Platform + SRE |
| **Wave** | Wave 5 |
| **Dependencies** | Redis cluster reconfiguration |

---

### C5-P2-02: Location Ingestion Service Missing Error Handling

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P2 Medium |
| **Blast Radius** | Rider location updates — data loss on error |
| **Affected Services** | `location-ingestion-service` |
| **Evidence** | No explicit DLQ or retry logic documented |
| **Failure Mode** | Malformed location pings cause consumer crash; no DLQ → data loss; no alerting on parse failures |
| **Customer Impact** | 🚚 Stale rider location → inaccurate ETA → customer confusion |
| **Business Impact** | Operational inefficiency |
| **Remediation Direction** | Add validation layer with schema check; implement DLQ for unparseable messages; add metrics on parse failures; add alerting on DLQ depth |
| **Owner Class** | Service Team (Location Ingestion) |
| **Wave** | Wave 4 |
| **Dependencies** | Kafka DLQ topic creation |

---

### C8-P2-03: No Runtime Schema Validation at Consumers

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P2 Medium |
| **Blast Radius** | All event consumers — schema drift undetected |
| **Affected Services** | All event consumers |
| **Evidence** | JSON Schemas are build-time artifacts only; no runtime validation |
| **Failure Mode** | Producer emits invalid payload → consumer crashes or processes corrupt data; no early detection |
| **Customer Impact** | 🚨 Async operation failures, data corruption |
| **Business Impact** | Operational incidents, data quality issues |
| **Remediation Direction** | Add runtime JSON Schema validation in consumers; reject invalid messages to DLQ; emit validation failure metrics; add alerting on validation failure rate |
| **Owner Class** | Platform + Service Teams |
| **Wave** | Wave 5 |
| **Dependencies** | JSON Schema validation library integration |

---

### Infra-P2-04: Kafka ADVERTISED_LISTENERS Misconfigured for Inter-Container Routing

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P2 Medium |
| **Blast Radius** | Local development — Kafka unreachable |
| **Affected Services** | `docker-compose.yml` Kafka |
| **Evidence** | `docker-compose.yml` — `KAFKA_ADVERTISED_LISTENERS` may not route correctly for inter-container communication |
| **Failure Mode** | Local services cannot connect to Kafka; development blocked |
| **Customer Impact** | ⚙️ None (dev only) |
| **Business Impact** | Developer productivity loss |
| **Remediation Direction** | Fix `ADVERTISED_LISTENERS` to include `PLAINTEXT://kafka:9092` for inter-container and `PLAINTEXT://localhost:9093` for host; test with `docker-compose exec` |
| **Owner Class** | Platform + SRE |
| **Wave** | Wave 0 |
| **Dependencies** | None |

---

### Infra-P2-05: No Staging Environment

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P2 Medium |
| **Blast Radius** | All deployments — no pre-prod validation |
| **Affected Services** | All services |
| **Evidence** | `argocd/` — only dev and prod applications; no staging |
| **Failure Mode** | Changes deployed to prod without realistic pre-prod testing; higher incident risk |
| **Customer Impact** | 🚨 Production incidents that could have been caught in staging |
| **Business Impact** | Incident risk, rollback overhead |
| **Remediation Direction** | Add `argocd/apps/instacommerce-staging.yaml`; add `deploy/helm/values-staging.yaml`; configure staging GKE cluster; add CI promotion pipeline dev → staging → prod |
| **Owner Class** | Platform + SRE |
| **Wave** | Wave 2 |
| **Dependencies** | Staging GKE cluster provisioning |

---

### AI-P2-06: AI Agent Governance — Path Parameter Sanitization Missing

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P2 Medium |
| **Blast Radius** | AI agent tool calls — path traversal risk |
| **Affected Services** | `ai-orchestrator-service` |
| **Evidence** | `ai-orchestrator-service` LangGraph tools — no explicit path sanitization documented |
| **Failure Mode** | Malicious prompt injects path traversal (e.g., `../../../etc/passwd`) → tool reads unintended files |
| **Customer Impact** | 🔒 Security risk — data exfiltration |
| **Business Impact** | Security breach, compliance violation |
| **Remediation Direction** | Implement allowlist-based path validation; reject `..` in paths; log and alert on injection attempts; implement per-tier tool enforcement (read-only vs. read-write) |
| **Owner Class** | AI/ML Team + Security |
| **Wave** | Wave 4 |
| **Dependencies** | AI agent audit logging |

---

### AI-P2-07: PII Vault Secret Management

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P2 Medium |
| **Blast Radius** | AI services — PII exposure risk |
| **Affected Services** | `ai-orchestrator-service`, `ai-inference-service` |
| **Evidence** | AI agent governance review flags PII vault secret handling |
| **Failure Mode** | PII vault decryption key stored insecurely; leaked in logs or metrics |
| **Customer Impact** | 🔒 PII breach → GDPR violation |
| **Business Impact** | Regulatory penalties, reputational damage |
| **Remediation Direction** | Store PII vault decryption key in GCP Secret Manager; rotate regularly (90 days); implement access audit logging; encrypt PII at rest in Redis checkpoints |
| **Owner Class** | Security + AI/ML Team |
| **Wave** | Wave 4 |
| **Dependencies** | Secret Manager key rotation automation |

---

### Data-P2-08: No Outbox Table Cleanup in 11 of 13 Services

| Field | Value |
|-------|-------|
| **Severity** | 🟡 P2 Medium |
| **Blast Radius** | All outbox producers — unbounded DB growth |
| **Affected Services** | 11 Java services with outbox tables |
| **Evidence** | No TTL or cleanup job in most services; outbox table grows unbounded |
| **Failure Mode** | Outbox table size grows to multi-GB; query performance degrades; storage costs increase |
| **Customer Impact** | ⚙️ Indirect — service slowdown over time |
| **Business Impact** | Operational overhead, infrastructure cost |
| **Remediation Direction** | Add scheduled cleanup job (e.g., delete rows older than 7 days where `sent=true`); add index on `created_at`; configure via `outbox.cleanup.retention-days` property |
| **Owner Class** | Service Teams |
| **Wave** | Wave 3 |
| **Dependencies** | None |

---

## Summary Statistics

| Severity | Count | Percentage |
|----------|-------|------------|
| P0 Critical | 14 | 42% |
| P1 High | 13 | 39% |
| P2 Medium | 8 | 24% |
| **Total** | **35** | **100%** |

### By Owner Class

| Owner | P0 | P1 | P2 | Total |
|-------|----|----|-----|-------|
| Service Teams | 6 | 6 | 2 | 14 |
| Platform | 3 | 3 | 4 | 10 |
| Security | 3 | 2 | 2 | 7 |
| SRE | 1 | 1 | 1 | 3 |
| Data/ML | 0 | 2 | 1 | 3 |
| Contracts | 1 | 0 | 0 | 1 |

### By Wave (Implementation Sequencing)

| Wave | Focus | Issue Count |
|------|-------|-------------|
| Wave 0 | Truth restoration, security, infra baseline | 6 |
| Wave 1 | Money path, contracts, edge hardening | 8 |
| Wave 2 | Read plane, observability, caching | 7 |
| Wave 3 | Dark store loop, loyalty, inventory | 4 |
| Wave 4 | ETA, ML governance, AI safety | 5 |
| Wave 5+ | Technical debt, compliance hardening | 5 |

---

## Critical Dependencies (Cross-Issue)

1. **Wave 0 must complete first:**
   - Shared token replacement (C1-P0-01) blocks all service-level security work
   - Registry mismatch fix (Infra-P0-13) blocks all deployments
   - Auth hardening (C1-P0-02, C1-P0-03) blocks production traffic scale-up

2. **Contract governance enables async safety:**
   - Event envelope standardization (C8-P0-11) required before any new event consumers
   - CI enforcement (C8-P0-12) required before schema changes at scale

3. **Money path correctness is a dependency chain:**
   - Dual checkout removal (C2-P0-04) → Payment idempotency (C2-P0-05) → Webhook durability (C2-P1-04)

4. **Dark store loop requires coordination:**
   - Inventory-warehouse integration (C4-P0-09) → Stock reconciliation (C4-P1-06) → Dispatch owner (C5-P0-10)

5. **Observability is a platform dependency:**
   - Burn-rate alerts (SRE-P1-13) required before any aggressive rollout
   - Correlation ID propagation (C8-P1-10) required for incident debugging at scale

---

## Appendix: Service Criticality Matrix

| Service | Money Path | Customer Facing | SLA Sensitivity | Incident Blast Radius | Priority Tier |
|---------|------------|-----------------|-----------------|----------------------|---------------|
| `checkout-orchestrator-service` | ✅ | ✅ | ≤ 2s p99 | All orders | Tier 0 (highest) |
| `payment-service` | ✅ | ✅ | ≤ 3s p99 | All payments | Tier 0 |
| `order-service` | ✅ | ✅ | ≤ 2s p99 | All orders | Tier 0 |
| `identity-service` | 🔒 | ✅ | ≤ 300ms p99 | All users | Tier 0 |
| `inventory-service` | ⚙️ | ✅ | ≤ 150ms p99 | All checkouts | Tier 1 |
| `cart-service` | ⚙️ | ✅ | ≤ 80ms p99 | All sessions | Tier 1 |
| `pricing-service` | ⚙️ | ✅ | ≤ 100ms p99 | All carts | Tier 1 |
| `catalog-service` | 📦 | ✅ | ≤ 200ms p99 | Browse UX | Tier 1 |
| `search-service` | 📦 | ✅ | ≤ 200ms p99 | Browse UX | Tier 1 |
| `fulfillment-service` | 🚚 | ✅ | ≤ 10s p99 | All deliveries | Tier 1 |
| `dispatch-optimizer-service` | 🚚 | ⚙️ | ≤ 10s p99 | All deliveries | Tier 2 |
| `rider-fleet-service` | 🚚 | ⚙️ | ≤ 5s p99 | Rider ops | Tier 2 |
| `outbox-relay-service` | ⚙️ | ❌ | Best effort | Async reliability | Tier 2 |
| `admin-gateway-service` | 🔒 | ❌ | Manual ops | Admin ops | Tier 3 (when hardened) |

---

## Next Steps

1. **Triage session:** Review P0 issues with CTO, Principal Engineers, and affected service owners
2. **Wave 0 kickoff:** Assign owners for blocking issues (shared token, registry mismatch, admin auth)
3. **CI governance:** Implement contract validation and test coverage gates immediately
4. **Quarterly review cadence:** Re-audit issue register every quarter as implementation progresses

---

**End of Issue Register**
