# Wave 40 Implementation Plan

**Created**: 2026-03-21
**Status**: Planning Phase
**Overall Complexity**: High (5 concurrent priority tracks)
**Estimated Total Duration**: 220-360 hours
**Recommended Agent Count**: 6-8 concurrent Opus 4.6 agents

---

## Executive Summary

Wave 40 focuses on **platform stabilization and observability** across three parallel workstreams:

1. **Build System Hardening** (P1): Eliminate dependency version fragmentation, standardize tooling
2. **Complete Diagram Coverage** (P2): Fill remaining service documentation gaps from Wave 39
3. **Distributed Tracing** (P3): Add production-grade OpenTelemetry instrumentation
4. **Commit Standards** (P4): Enforce quality gates on repository artifacts
5. **Dependencies & Upgrades** (P5): Resolve security vulnerabilities and technical debt

**Expected Outcomes**:
- Single source of truth for dependency management (gradle/libs.versions.toml)
- 313+ total service diagrams (100% coverage across 28 services)
- Distributed tracing from ingress → payment → order → fulfillment
- Zero-config pre-commit hooks enforced across all developers
- Vulnerability score reduction from current state to <5 open security issues

---

## Priority 1: Build System Hardening (40-60 hours)

**Objective**: Create unified, maintainable dependency management across all 28 services.

**Current State**:
- Shedlock versions: 5.10.0 (6 services), 5.10.2 (8 services), 5.12.0 (4 services)
- gRPC locked at 1.75.0 in root build.gradle.kts (6+ months old)
- lettuce-core hardcoded in config-feature-flag-service (6.3.2.RELEASE)
- No version catalog for transitive dependency control
- Manual dependency tracking across service build files

**Deliverables**:

### Task 1a: Create gradle/libs.versions.toml (8 hours)

**File**: `/gradle/libs.versions.toml`

**Scope**:
- Consolidate all 150+ versioned dependencies into TOML catalog
- Group by category (spring, kafka, grpc, database, testing, etc.)
- Define version overrides for known conflicts (lz4-java, protobuf, etc.)
- Document rationale for each version choice (LTS, security patch, compatibility)

**Example Sections**:
```toml
[versions]
springBoot = "4.0.0"
springCloud = "2024.0.0"
grpc = "1.79.0"  # Upgrade from 1.75.0
shedlock = "5.12.0"  # Standardized to latest
jackson = "2.18.6"
protobuf = "4.32.0"
lettuce = "6.3.2"

[libraries]
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web", version.ref = "springBoot" }
grpc-stub = { module = "io.grpc:grpc-stub", version.ref = "grpc" }
shedlock-spring = { module = "net.javacrumbs.shedlock:shedlock-spring", version.ref = "shedlock" }
```

**Success Criteria**:
- Zero hardcoded versions in service build.gradle.kts files
- All 28 services import from version catalog
- Build succeeds with `gradle :services:payment-service:build`
- Dependency tree shows single version per artifact

**Owner**: 1 Senior Engineer (4-6 hours peer review included)

### Task 1b: Centralize ShedLock Versions (12 hours)

**Scope**:
- Create shedlock-bom entry in libs.versions.toml (v5.12.0 as target)
- Update 16 affected services (payment, order, fulfillment, warehouse, pricing, etc.)
- Verify lock provider compatibility (JDBC template → v5.12.0 API)
- Add integration test for distributed lock (cross-service reservation)

**Affected Services**:
- v5.10.0 → v5.12.0: routing-eta-service, config-feature-flag-service, inventory-service (3 services, 3 hours)
- v5.10.2 → v5.12.0: rider-fleet-service, audit-trail-service, wallet-loyalty-service, pricing-service, fulfillment-service, search-service, cart-service, identity-service, catalog-service, fraud-detection-service (10 services, 6 hours)
- v5.12.0: Already latest (order-service, warehouse-service, checkout-orchestrator-service, 3 services, 0 hours)
- Integration test update (3 hours)

**Migration Strategy**:
1. Update libs.versions.toml with shedlock-bom
2. Replace inline versions with version.ref in build.gradle.kts
3. Run compatibility check: `gradle :services:*/dependencyInsight --dependency shedlock`
4. Integration test: DistributedLockTest verifies concurrent task scheduling across 3+ services

**Success Criteria**:
- All 16 services use v5.12.0
- Gradle dependency report shows single shedlock version
- Integration tests pass (no lock API breakage)
- Code review approval from service owner

**Owner**: 2-3 Engineers (parallel service updates)

### Task 1c: Upgrade gRPC 1.75.0 → 1.79.0 (15 hours)

**Scope**:
- Update gRPC BOM in root build.gradle.kts
- Override constraints in services using gRPC contracts (admin-gateway, cdc-consumer, etc.)
- Run gRPC codegen: `gradle :services:*/generateProto`
- Verify no breaking changes in generated Java/Go stubs
- Add regression test for cross-service gRPC calls

**Change Location**: `/build.gradle.kts` line 33

**Before**:
```kotlin
mavenBom("io.grpc:grpc-bom:1.75.0")
```

**After**:
```kotlin
mavenBom("io.grpc:grpc-bom:1.79.0")
```

**Affected Services** (those with gRPC):
- admin-gateway-service (uses external auth service contract)
- cdc-consumer-service (consumes Debezium event stream)
- dispatch-optimizer-service (distance calculation service)
- routing-eta-service (ETA service)

**Testing Matrix**:
- Unit tests: All gRPC stub generation (no failures expected)
- Integration: Cross-service call from admin-gateway → identity-service JWKS verification
- Load test: 1000 gRPC messages/sec on dispatch-optimizer (performance regression check)

**Success Criteria**:
- All gRPC codegen passes without warnings
- No binary-incompatible stubs generated
- Integration tests pass for all 4 affected services
- Performance: dispatch optimizer latency within ±5% of baseline

**Owner**: 1 Senior Engineer + 1 QA Engineer (8 hours engineering, 7 hours testing)

### Task 1d: Remove lettuce-core Hard-Coding (5 hours)

**Scope**:
- Move lettuce-core from config-feature-flag-service build.gradle.kts to libs.versions.toml
- Verify Redis connection pool settings unchanged
- Run flag evaluation integration tests (50+ test cases)
- Update ADR-013 with version management policy

**Current State**: `/services/config-feature-flag-service/build.gradle.kts` line ~42
```kotlin
implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")
```

**Changes**:
1. Add to libs.versions.toml: `lettuce = "6.3.2"`
2. Update build.gradle.kts: `implementation(libs.lettuce.core)`
3. Add optional: `implementation(libs.lettuce.reactive)` if reactive client needed (check code)
4. Verify Spring Data Redis auto-configuration still resolves correctly

**Integration Tests**:
- FlagCacheInvalidatorTest: Verify Redis pub/sub still propagates <100ms
- FlagEvaluationTest: 50 flag scenarios with network latency simulation
- Circuit breaker test: Redis down → Caffeine fallback (unchanged behavior)

**Success Criteria**:
- Zero hardcoded versions in config-feature-flag-service
- All flag cache tests pass
- Build output shows `implementation -> lettuce-core 6.3.2.RELEASE` (from BOM, not service)
- ADR-013 updated with version governance appendix

**Owner**: 1 Engineer (3 hours coding + 2 hours testing/review)

---

## Priority 2: Complete Diagram Coverage (40-60 hours)

**Objective**: Achieve 100% diagram coverage across all 28 services (target: 313+ diagrams).

**Current State** (from Wave 39):
- Tier 1 (8 services): ~200 diagrams ✅
- Tier 2 (8 services): ~80 diagrams ✅
- Tier 3 (4 services): ~26 diagrams ✅
- Tier 4 (6 services): 0 diagrams ❌
- Admin-gateway security suite: 0 specialized diagrams ❌
- Missing: catalog, cart, pricing, notification, feature-flag, wallet services

**Deliverables**:

### Task 2a: Generate 6 Missing Tier 4 Services (30 hours)

**Services**:
1. **Catalog Service** (5 hours)
   - Directory: `/docs/services/catalog-service/diagrams/`
   - HLD: Product catalog model, search indexing, caching strategy (Elasticsearch integration)
   - LLD: CatalogController, ProductService, ProductEntity, SearchIndexManager
   - ER: products, product_categories, product_variants, search_index_versions
   - Flowchart: Product ingestion, search query processing, cache invalidation
   - Sequence: Full-text search, pagination, real-time index updates
   - API: GET /products, GET /products/{id}, POST /products/search, GET /categories
   - Events: ProductCreated, ProductUpdated, ProductDeleted (Kafka)
   - Database: PostgreSQL catalog DB, Elasticsearch cluster
   - Resilience: Circuit breaker to Elasticsearch (fallback to PostgreSQL full-table scan)

2. **Cart Service** (5 hours)
   - Directory: `/docs/services/cart-service/diagrams/`
   - HLD: Shopping cart lifecycle (Add → Update → Checkout → Archive)
   - LLD: CartController, CartService, CartEntity, CartItemEntity, LockManager
   - ER: carts, cart_items, cart_metadata, lock_records
   - Flowchart: Add to cart, validate inventory, merge carts, apply promotions
   - Sequence: Multi-device cart sync, promo code application, checkout handoff
   - API: POST /carts, PUT /carts/{id}/items, GET /carts/{id}
   - Events: CartItemAdded, CartItemRemoved, CheckoutInitiated
   - Database: PostgreSQL cart DB, Redis session cache (2h TTL)
   - Resilience: Optimistic locking on cart updates, inventory service failover

3. **Pricing Service** (5 hours)
   - Directory: `/docs/services/pricing-service/diagrams/`
   - HLD: Dynamic pricing engine, promotion rules, discount strategies
   - LLD: PricingController, PricingService, RuleEngine, PromotionService
   - ER: pricing_rules, promotions, price_history, discount_logs
   - Flowchart: Calculate order price, apply time-based promotions, A/B test pricing
   - Sequence: Get price for product, apply promo code, calculate tax
   - API: POST /pricing/calculate, GET /pricing/rules
   - Events: PricingRuleUpdated, PromotionStarted, PromotionEnded
   - Database: PostgreSQL pricing DB, Redis price cache (5min TTL)
   - Resilience: Fallback to base price if pricing service fails, cache hit ratio >95%

4. **Notification Service** (5 hours)
   - Directory: `/docs/services/notification-service/diagrams/`
   - HLD: Multi-channel notification delivery (Email, SMS, Push)
   - LLD: NotificationController, NotificationService, ChannelStrategy, DeliveryTracker
   - ER: notifications, notification_templates, delivery_logs, user_preferences
   - Flowchart: Template rendering, multi-channel dispatch, retry with exponential backoff
   - Sequence: OrderCreated → notification dispatch → delivery confirmation
   - API: POST /notifications, GET /notifications/{id}/status
   - Events: Consumes OrderCreated, PaymentCompleted, OrderShipped (Kafka)
   - Database: PostgreSQL notifications DB, Kafka DLT for failed deliveries
   - Resilience: Circuit breaker per channel (email, SMS, push), bulkhead for concurrent sends

5. **Feature Flag Service** (5 hours)
   - Directory: `/docs/services/config-feature-flag-service/diagrams/`
   - HLD: Flag evaluation engine, Redis cache, pub/sub invalidation
   - LLD: FlagController, FlagService, FlagCache, CacheInvalidator
   - ER: feature_flags, flag_values, flag_audience_rules, cache_versions
   - Flowchart: Flag evaluation, cache hit/miss, Redis pub/sub propagation
   - Sequence: Admin updates flag → Redis pub/sub → All pods invalidate within 500ms
   - API: GET /flags/{name}, GET /flags/all, POST /flags (admin)
   - Events: FlagUpdated (published internally to Redis channels)
   - Database: PostgreSQL flags DB, Redis pub/sub channels
   - Resilience: Caffeine in-memory cache (fallback), circuit breaker to Redis

6. **Wallet Service** (5 hours)
   - Directory: `/docs/services/wallet-loyalty-service/diagrams/`
   - HLD: Digital wallet, loyalty points, transaction ledger
   - LLD: WalletController, WalletService, LedgerService, PointsCalculator
   - ER: wallets, wallet_transactions, loyalty_points, point_history
   - Flowchart: Add funds, deduct for purchase, earn loyalty points
   - Sequence: User topup wallet, purchase with wallet, loyalty point accrual
   - API: GET /wallets/{userId}, POST /wallets/{userId}/topup, POST /wallets/{userId}/deduct
   - Events: Consumes OrderCreated, PaymentCompleted (to calculate loyalty points)
   - Database: PostgreSQL wallet DB, audit trail table (immutable transaction log)
   - Resilience: Distributed lock for concurrent topup/deduct, transaction journal for replay

**Generation Process** (per service, 5 hours each):
1. Parse service source code (controllers, services, entities, database schema)
2. Generate 6 Mermaid diagrams (HLD, LLD, ER, Flowchart, Sequence, Integration)
3. Create directory `/docs/services/{service}/diagrams/`
4. Write 6 files: `00-all-diagrams.md`, `01-hld.md`, `02-lld.md`, `03-er.md`, `04-flowchart.md`, `05-sequence.md`
5. Generate implementation documentation: `implementation/api.md`, `implementation/events.md`, `implementation/database.md`, `implementation/resilience.md`
6. Peer review diagrams for consistency with existing services

**Success Criteria**:
- All 6 services have 6+ diagrams each (36 diagrams minimum)
- Naming convention matches existing Tier 1-3 services (00-all-diagrams.md entry point)
- All diagrams follow established Mermaid patterns (color scheme, shape conventions)
- Implementation docs complete with API examples, event schemas, database schema
- All diagrams reviewed by service owner + 1 peer

**Owner**: 2 Senior Engineers running in parallel (5 services simultaneously, 10 hours each with review overlap)

### Task 2b: Generate 7-Diagram Admin-Gateway Security Suite (15 hours)

**Objective**: Document admin-gateway authentication, authorization, and security architecture (Wave 34 feature set).

**Directory**: `/docs/services/admin-gateway-service/diagrams/`

**7 Diagrams**:

1. **Authentication Flow Diagram** (2 hours)
   - JWT token acquisition from identity-service
   - JWKS endpoint verification (public key rotation)
   - Token validation pipeline (signature, expiration, audience)
   - Mermaid: Sequence diagram (Admin Client → Admin-Gateway → Identity-Service JWKS endpoint)

2. **Authorization Model Diagram** (2 hours)
   - Audience scoping (aud: instacommerce-admin)
   - Role-based access control (ADMIN, SUPERADMIN, etc.)
   - Per-endpoint permission checks
   - Mermaid: State diagram or class diagram (TokenClaims → Permissions → Endpoints)

3. **Token Lifecycle Diagram** (1.5 hours)
   - Token generation, validation, caching
   - Expiration and refresh token strategy
   - Revocation mechanism (if implemented)
   - Mermaid: Timeline/flowchart (token states)

4. **Istio Integration Diagram** (2 hours)
   - RequestAuthentication policy (JWKS provider config)
   - AuthorizationPolicy rules (allow admin endpoints only for valid tokens)
   - Virtual Service routing
   - Mermaid: Architecture diagram (Envoy proxy, Istio CRDs, Kubernetes API)

5. **Multi-Service Access Pattern** (2 hours)
   - How admin-gateway calls other services (identity-service JWKS, reconciliation-engine, feature-flag-service)
   - Service-to-service token exchange (per-service token scoping from Wave 34B)
   - Circuit breaker and resilience patterns
   - Mermaid: Sequence diagram (Admin-Gateway → Payment/Order/Fulfillment services)

6. **Security Incident Response** (2 hours)
   - Compromised token detection
   - Rate limiting and brute-force protection
   - Audit logging (who accessed what, when)
   - Mermaid: Flowchart (detect incident → revoke token → audit log → alert)

7. **Implementation Details Diagram** (2 hours)
   - Code components: AdminJwtAuthenticationFilter, AdminDashboardController, TokenValidator
   - Filter chain order (authentication → authorization → business logic)
   - Exception handling (InvalidTokenException, ExpiredTokenException, etc.)
   - Mermaid: Class diagram or component diagram

**File Outputs**:
- `/docs/services/admin-gateway-service/diagrams/01-authentication-flow.md`
- `/docs/services/admin-gateway-service/diagrams/02-authorization-model.md`
- `/docs/services/admin-gateway-service/diagrams/03-token-lifecycle.md`
- `/docs/services/admin-gateway-service/diagrams/04-istio-integration.md`
- `/docs/services/admin-gateway-service/diagrams/05-multi-service-access.md`
- `/docs/services/admin-gateway-service/diagrams/06-incident-response.md`
- `/docs/services/admin-gateway-service/diagrams/07-implementation-details.md`
- `/docs/services/admin-gateway-service/diagrams/00-all-diagrams.md` (index)

**Success Criteria**:
- All 7 diagrams render without Mermaid syntax errors
- Covers all admin-gateway security features from Wave 34
- Referenced by ADR-011 (Admin-gateway auth model)
- Cross-referenced in governance docs (OWNERSHIP_MODEL.md, escalation paths)

**Owner**: 1 Security-focused Engineer (15 hours including deep code review)

### Task 2c: Consolidate Naming Convention (5 hours)

**Scope**:
- Audit all 28 services for diagram file naming consistency
- Standardize on pattern: `00-all-diagrams.md` (index), `01-hld.md`, `02-lld.md`, `03-er.md`, `04-flowchart.md`, `05-sequence.md`
- Rename outliers (e.g., payment-service uses `er_diagram.md` instead of `03-er.md`)
- Update service README files to reference new diagram locations
- Create `/docs/DIAGRAM_NAMING_STANDARD.md` for future consistency

**Current Inconsistencies** (sample):
- Payment service: `/diagrams/er_diagram.md` → rename to `/diagrams/03-er.md`
- Outbox relay: `/diagrams/06-er-diagram.md` → rename to `/diagrams/03-er.md`
- Identity cluster: `/diagrams.md` (single file) → split into `/diagrams/00-all-diagrams.md`, etc.

**Naming Standard Document**:
```markdown
# Diagram Naming Convention

All services MUST follow this structure:

/docs/services/{service-name}/diagrams/
├── 00-all-diagrams.md (index with table of contents)
├── 01-hld.md (High-Level Design)
├── 02-lld.md (Low-Level Design)
├── 03-er.md (Entity-Relationship Diagram)
├── 04-flowchart.md (Process Flows)
├── 05-sequence.md (Sequence Diagrams)
└── 06-*.md (optional domain-specific diagrams)

Example: Admin-gateway adds:
├── 06-authentication-flow.md
├── 07-authorization-model.md
├── 08-token-lifecycle.md
... (up to 13 diagrams for security suite)
```

**Success Criteria**:
- All 28 services use numbered naming convention
- No legacy names (er_diagram.md, ER.md, etc.) remain
- Service README files updated with cross-references
- CI/CD linting rule added: `docs/DIAGRAM_NAMING_STANDARD.md` enforced in PRs

**Owner**: 1 Documentation Engineer (5 hours including CI rule addition)

---

## Priority 3: Distributed Tracing (80-120 hours)

**Objective**: Add production-grade OpenTelemetry instrumentation for end-to-end request tracing.

**Current State**:
- OpenTelemetry SDKs present in some services (partial)
- Trace propagation not consistently implemented
- No span context across service boundaries
- 0 instrumentation points in critical money-path

**Deliverables**:

### Task 3a: OpenTelemetry Instrumentation Framework (25 hours)

**Scope**:
- Create shared library: `libs/otel-instrumentation` with reusable components
- Define instrumentation patterns for common scenarios
- Create Spring Boot auto-configuration for zero-config spans
- Document instrumentation best practices

**Deliverables**:

1. **Shared Instrumentation Library** (15 hours)
   - Location: `/services/libs/otel-instrumentation/`
   - Components:
     - TraceInitializer: Initialize global TracerProvider, add SpanExporter (Jaeger/OTLP)
     - HttpClientInstrumentation: Wrap RestTemplate, WebClient for outbound HTTP traces
     - DatabaseInstrumentation: JDBC connection pooling events, query execution spans
     - KafkaInstrumentation: Producer/consumer span creation, message propagation headers
     - GrpcInstrumentation: gRPC client/server interceptors
   - Auto-configuration class: `OtelAutoConfiguration.java`
   - Dependencies: opentelemetry-api, opentelemetry-sdk, opentelemetry-exporter-jaeger

2. **Instrumentation Patterns** (10 hours)
   - Money-path service pattern (payment → order → fulfillment chain)
   - Event-driven service pattern (consumes Kafka events, produces spans)
   - External service calls pattern (circuit breaker + trace context)
   - Database transaction pattern (Flyway migrations, query execution)
   - Error handling pattern (exception to span event conversion)

**File Structure**:
```
/services/libs/otel-instrumentation/
├── src/main/java/com/instacommerce/otel/
│   ├── config/OtelAutoConfiguration.java
│   ├── trace/
│   │   ├── TraceInitializer.java
│   │   ├── TraceIdGenerator.java
│   │   └── ContextPropagator.java
│   ├── instrumentation/
│   │   ├── HttpClientInstrumenter.java
│   │   ├── DatabaseInstrumenter.java
│   │   ├── KafkaInstrumenter.java
│   │   └── GrpcInstrumenter.java
│   └── util/
│       ├── SpanDecorator.java
│       └── ErrorHandler.java
├── src/test/java/...
├── build.gradle.kts
└── README.md
```

**Success Criteria**:
- Library compiles without warnings
- Auto-configuration registered in `spring.factories`
- All 4 instrumenters have >80% unit test coverage
- Documentation includes code examples for each pattern

**Owner**: 1 Senior Backend Engineer (15 hours)

### Task 3b: Span Propagation Across 12 Services (40 hours)

**Money-Path Tracing** (critical transaction flow: Payment → Order → Fulfillment → Delivery):

**Phase 1: Ingress to Payment (8 hours)**

1. **Admin-Gateway Service** (3 hours)
   - Add Tracer injection in AdminDashboardController
   - Create span for each admin request: `span.name = "admin.{endpoint}"`
   - Capture user ID, request path, query parameters as span attributes
   - Add span event on authentication success/failure
   - Integration test: Verify span context in logs

2. **Checkout-Orchestrator Service** (5 hours)
   - Wrap checkout orchestration in Temporal activity span
   - Create child spans for each orchestration step (payment attempt, inventory check, fulfillment trigger)
   - Link spans to Temporal workflow ID (for post-mortem tracing)
   - Test: Mock Temporal, verify span hierarchy

**Phase 2: Payment Service (12 hours)**

3. **Payment Service** (8 hours)
   - Create top-level span: `span.name = "payment.process"` with attributes (amount, currency, merchant)
   - Child span for Stripe API call: `span.name = "stripe.authorize"` with circuit breaker events
   - Child span for database write: `span.name = "payment_ledger.insert"`
   - Child span for Kafka publish: `span.name = "payment.created"` with message ID
   - Async recovery job span: `span.name = "payment.recover_stale"` with retry count
   - Integration test: Generate 100 payments, verify span tree completeness

4. **Payment-Webhook Service** (4 hours)
   - Create span for webhook receipt: `span.name = "webhook.received"` with Stripe event type
   - Child span for signature verification: `span.name = "webhook.verify_signature"`
   - Child span for Kafka publish: `span.name = "webhook.published"`
   - Deduplication span: `span.name = "webhook.dedup_check"` with cache hit/miss
   - Test: Send 50 duplicate webhooks, verify single publish

**Phase 3: Order Service (10 hours)**

5. **Order Service** (10 hours)
   - Create span for order creation: `span.name = "order.create"` with order ID, item count
   - Child span for event retrieval: `span.name = "payment_ledger.fetch"` (payment status validation)
   - Child span for Temporal workflow start: `span.name = "order.workflow_start"` with workflow ID
   - Child span for Outbox event creation: `span.name = "outbox_event.create"`
   - CDC span: `span.name = "order.cdc_publish"` (event publication)
   - Test: Create 50 orders, verify end-to-end span chain from checkout → order

**Phase 4: Fulfillment & Logistics (12 hours)**

6. **Fulfillment Service** (5 hours)
   - Consume OrderCreated event with trace context extraction
   - Create span: `span.name = "fulfillment.start"` with order ID
   - Child span for pick job creation: `span.name = "pick_job.create"` with warehouse ID
   - Child span for warehouse call: `span.name = "warehouse.get_location"` with geolocation data
   - Test: End-to-end trace from payment → fulfillment

7. **Warehouse Service** (3 hours)
   - Create span for location query: `span.name = "warehouse.location_query"` with lat/long
   - Child span for cache lookup: `span.name = "warehouse.cache_lookup"` with hit/miss
   - Child span for database query: `span.name = "warehouse.db_query"` with result count

8. **Dispatch-Optimizer Service** (3 hours)
   - Create span for routing calculation: `span.name = "dispatch.calculate_route"` with waypoint count
   - Child span for Haversine distance: `span.name = "dispatch.haversine"` with pair count
   - Tracing algorithm execution time

9. **Routing-ETA Service** (1 hour)
   - Create span for ETA calculation: `span.name = "routing.calculate_eta"` with distance
   - Attributes: duration, traffic conditions, confidence level

**Phase 5: Cross-Service Integration (8 hours)**

10. **Config-Feature-Flag Service** (2 hours)
    - Wrap flag evaluation in span: `span.name = "flag.evaluate"` with flag name, result
    - Span event: Redis cache hit/miss (for performance analysis)

11. **Notification Service** (3 hours)
    - Consume events (OrderCreated, PaymentCompleted, OrderShipped) with trace context
    - Create span per notification: `span.name = "notification.send"` with channel (email/SMS/push)
    - Child span per channel: `span.name = "notification.email"`, `notification.sms`, etc.

12. **Audit Service** (3 hours)
    - Intercept trace context from request headers
    - Create span: `span.name = "audit.log"` with actor, action, resource
    - Link to parent trace for correlation

**Test Strategy**:
- Unit tests: Mock Tracer, verify span creation (15 hours)
- Integration tests: Docker Compose with Jaeger backend, trace complete workflows (15 hours)
- End-to-end scenario: Payment → Order → Fulfillment → Delivery with span verification (10 hours)

**Success Criteria**:
- 340+ instrumentation points added across 12 services (28+ per service)
- Span propagation verified across service boundaries (W3C Trace Context standard)
- <100ms overhead per service call (measure baseline + tracing)
- All critical business flows generate 100+ spans each
- Jaeger UI shows complete trace visualization for money-path workflows

**Owner**: 3 Engineers in parallel (Phase 1-2: 1 engineer, Phase 3-4: 1 engineer, Phase 5: 1 engineer)

### Task 3c: Span Propagation Across 12 Services (see Task 3b above)

### Task 3d: Integration Tests for Trace Propagation (15 hours)

**Scope**:
- Create test framework: TraceAssertions for verifying span hierarchy
- Write test suite per service (25 tests minimum per critical service)
- Set up Testcontainers with Jaeger backend
- Verify W3C Trace Context propagation headers

**Test Cases**:

1. **Happy Path Trace** (5 hours)
   - Test class: `PaymentToOrderToFulfillmentTraceTest`
   - Scenario: Customer completes payment → order created → fulfillment started
   - Assertions:
     - Root span has 3+ child spans (payment, order, fulfillment)
     - All spans linked by trace ID
     - Span timing: payment <1s, order <2s, fulfillment <3s
     - All attributes populated (user ID, order ID, amount, etc.)
   - Test location: `/services/payment-service/src/test/java/com/instacommerce/payment/trace/`

2. **Error Path Trace** (5 hours)
   - Test class: `PaymentFailureTraceTest`
   - Scenario: Payment declined → error span with exception details
   - Assertions:
     - Error span has `error=true` attribute
     - Exception type and message captured in span event
     - Retry attempt creates new child span
     - Final error propagates to parent service
   - Test location: `/services/payment-service/src/test/java/.../trace/`

3. **Async Flow Trace** (3 hours)
   - Test class: `KafkaConsumerTraceTest`
   - Scenario: Event published to Kafka → consumer reads with trace context
   - Assertions:
     - Consumer span links to producer span (via trace ID in message)
     - Message processing creates child spans
     - Trace context extractable from Kafka headers (W3C standard)

4. **Cross-Service Call Trace** (2 hours)
   - Test class: `RestClientTraceTest`
   - Scenario: Service A calls Service B (via RestTemplate)
   - Assertions:
     - Request includes `traceparent` header (W3C format)
     - Service B receives header and links to parent trace
     - HTTP client span includes status code attribute

**Test Infrastructure** (`/services/libs/otel-test/`):
```java
public class TraceAssertions {
  public static void assertSpanHierarchy(List<SpanData> spans, String rootSpanName, int expectedChildren) { }
  public static void assertSpanTiming(SpanData span, Duration maxDuration) { }
  public static void assertSpanAttributes(SpanData span, Map<String, Object> expectedAttrs) { }
  public static void assertTraceContext(HttpRequest request, String expectedTraceId) { }
}
```

**Success Criteria**:
- 100+ integration tests across 12 services
- All tests pass with Testcontainers Jaeger backend
- >90% span coverage verification (every span type verified)
- CI/CD includes trace tests (runs on every commit)
- Test execution time <5 minutes (parallel runs)

**Owner**: 1 QA + 1 Backend Engineer (15 hours split)

---

## Priority 4: Commit Standards (4-8 hours)

**Objective**: Enforce quality gates on commit messages and code artifacts.

**Current State**:
- No pre-commit hooks
- Inconsistent commit message formats (72-char titles not enforced)
- No linting of new code before push

**Deliverables**:

### Task 4a: Pre-Commit Hook for 72-Char Title Validation (3 hours)

**File**: `/.git/hooks/commit-msg`

**Implementation**:
```bash
#!/bin/bash
# Validate commit message title (first line) is <= 72 characters

COMMIT_MSG=$(cat "$1")
FIRST_LINE=$(echo "$COMMIT_MSG" | head -n 1)
CHAR_COUNT=${#FIRST_LINE}

if [ $CHAR_COUNT -gt 72 ]; then
  echo "Error: Commit title exceeds 72 characters ($CHAR_COUNT chars)"
  echo "Title: $FIRST_LINE"
  exit 1
fi

# Validate conventional commit format (optional, can be strict or permissive)
# Pattern: type(scope): description
if ! echo "$FIRST_LINE" | grep -E "^(feat|fix|docs|style|refactor|test|chore|ci)" > /dev/null; then
  echo "Warning: Consider using conventional commits (feat|fix|docs|...)"
fi

exit 0
```

**Installation**:
- Create `/scripts/install-hooks.sh` to copy pre-commit hook
- Run in CI/CD post-checkout: `bash scripts/install-hooks.sh`
- Document in CONTRIBUTING.md

**Testing**:
- Test: Commit with 71-char title → success
- Test: Commit with 73-char title → rejected
- Test: Commit with invalid conventional format → warning (not failure)

**Success Criteria**:
- Hook executable and installed on all developer machines
- Prevents 100% of oversized commit titles
- Backward compatible with existing commits (no rewrite needed)

**Owner**: 1 DevOps Engineer (3 hours including CI integration)

### Task 4b: Developer Guide for Commit Messages (2-5 hours)

**File**: `/CONTRIBUTING.md` (create or update existing section)

**Contents** (2000-3000 words):

1. **Commit Message Format** (300 words)
   - Structure: `{type}({scope}): {title}` (≤72 chars)
   - Body: Detailed explanation (wrap at 80 chars)
   - Footer: `Closes #123`, `Co-Authored-By: ...`
   - Examples:
     ```
     feat(payment-service): Add Stripe recurring payment support

     Implements RecurringChargeService to handle monthly subscriptions.
     Adds two new API endpoints: POST /recurring-charges and
     DELETE /recurring-charges/{id}.

     Closes #456
     ```

2. **Commit Types** (400 words)
   - `feat`: New feature
   - `fix`: Bug fix
   - `docs`: Documentation changes
   - `style`: Code formatting (no logic change)
   - `refactor`: Code restructuring
   - `test`: Test additions/modifications
   - `chore`: Build, CI, dependency updates
   - `ci`: CI/CD pipeline changes
   - Examples for each type

3. **Common Scopes** (300 words)
   - Service name: `payment-service`, `order-service`, etc.
   - Shared library: `otel-instrumentation`, `testing-commons`, etc.
   - Infrastructure: `k8s`, `terraform`, `docker`, etc.
   - Each scope with examples

4. **Best Practices** (400 words)
   - Atomic commits (single logical change)
   - Imperative mood in title ("Add feature" not "Added feature")
   - No period at end of title
   - Reference issues and PRs
   - Examples of good vs. bad commits

5. **Pre-Commit Validation** (300 words)
   - Hook installation instructions
   - Bypassing hooks (discouraged): `git commit --no-verify`
   - Checking hook status: `ls -la .git/hooks/commit-msg`
   - Troubleshooting

6. **Rebasing & Amending** (200 words)
   - When to use `--amend` vs. new commit
   - Interactive rebase guidelines
   - Golden rule: Never rewrite public history

**Success Criteria**:
- Document merged into master
- Linked from project README
- >90% of new commits follow format within 30 days
- CI/CD enforces format (automated checks)

**Owner**: 1 Tech Lead (4-5 hours including examples, review, and CI integration)

---

## Priority 5: Dependencies & Upgrades (20-40 hours, Backlog)

**Objective**: Resolve 30 Dependabot vulnerabilities and upgrade high-risk libraries.

**Current State**:
- ~30 open Dependabot alerts (varies by day)
- Jackson: 2.18.6 (latest, secure)
- Spring Boot: 4.0.0 (current, but check Spring Security updates)
- gRPC: 1.75.0 → 1.79.0 (in Priority 1b)

**Deliverables**:

### Task 5a: Dependabot Alert Triage (12 hours)

**Scope**:
- Review all 30 open alerts
- Categorize by severity: CRITICAL, HIGH, MEDIUM, LOW
- Determine upgrade path per library
- Create migration plan for breaking changes

**Categories**:

**CRITICAL** (upgrade immediately):
- Any vulnerability in Spring Security, authentication libraries
- Database driver vulnerabilities
- Cryptography library flaws
- Action: Create individual PRs, merge within 24h

**HIGH** (upgrade in bulk, within 1 week):
- Common utilities (commons-lang, guava, etc.)
- Logging libraries
- JSON processing
- Action: Group by service, test in integration environment

**MEDIUM** (upgrade in Priority 5b):
- Development-only dependencies (testing frameworks, plugins)
- Non-critical utilities
- Action: Batch upgrades, coordinate with team

**LOW** (defer to next quarter):
- Minor version bumps (no security fixes)
- Library ecosystem shifts

**Output**: `/docs/wave-40/DEPENDABOT_TRIAGE.md`

**Success Criteria**:
- All 30 alerts categorized
- Upgrade path defined for each
- Breaking changes documented
- Risk assessment per library

**Owner**: 1 Senior Engineer + DevOps pair (8 hours analysis + 4 hours documentation)

### Task 5b: Stage Release Upgrades (15 hours)

**Scope**:
- Create feature branch: `feat/wave-40-dependency-upgrades`
- Group HIGH-priority upgrades by service
- Run full test suite per upgrade
- Create separate commit per major library upgrade (enables easy revert)

**High-Priority Libraries** (example):
- Spring Security 6.2.x → 6.3.x (if available, for auth fixes)
- Kafka client 3.8.x → 3.9.x (performance, stability)
- PostgreSQL driver 42.7.x → 42.8.x (if available)
- Testcontainers 1.20.x → 1.21.x (latest)

**Testing Matrix** (per library upgrade):
- Unit tests: `gradle :services:*/test`
- Integration tests: `docker-compose -f docker-compose.test.yml up`
- Compatibility test: Cross-service calls (payment → order → fulfillment)
- Performance baseline: Compare latency before/after

**Success Criteria**:
- All HIGH-priority upgrades completed
- Full test suite passes
- No performance regression (>5% allowed variance)
- All changes documented in commit messages

**Owner**: 2 Engineers in parallel (8 hours each, with 1-hour daily sync)

---

## Resource Allocation

### Recommended Team Structure

```
Wave 40 Leadership (20% of time):
├── Tech Lead (1): Prioritization, cross-priority coordination, approvals
└── DevOps Lead (1): Build system, CI/CD integration, infrastructure decisions

Priority 1: Build System Hardening (1.5 FTE)
├── Senior Backend Engineer (1 FTE): Tasks 1a, 1b, 1c
├── QA Engineer (0.5 FTE): Integration testing, gRPC compatibility

Priority 2: Diagram Coverage (1.5 FTE)
├── Senior Backend Engineer (0.5 FTE): Task 2a (catalog, pricing)
├── Backend Engineer (0.5 FTE): Task 2a (cart, notification)
├── Infra/Security Engineer (0.5 FTE): Task 2b (admin-gateway security)
└── Documentation Engineer (0.25 FTE): Task 2c (naming standards)

Priority 3: Distributed Tracing (2.5 FTE)
├── Senior Backend Engineer (0.5 FTE): Task 3a (framework)
├── Backend Engineer 1 (0.5 FTE): Task 3b Phase 1-2 (payment flow)
├── Backend Engineer 2 (0.5 FTE): Task 3b Phase 3-4 (order/fulfillment)
├── Backend Engineer 3 (0.5 FTE): Task 3b Phase 5 (cross-service)
└── QA Engineer (0.5 FTE): Task 3d (integration tests)

Priority 4: Commit Standards (0.25 FTE)
├── DevOps Engineer (0.15 FTE): Task 4a (pre-commit hook)
└── Tech Lead (0.1 FTE): Task 4b (developer guide)

Priority 5: Dependencies (0.75 FTE) — Backlog
├── Senior Engineer (0.5 FTE): Task 5a (triage)
└── 2 Engineers (0.25 FTE each): Task 5b (staged releases)

Total: 6.5 FTE for 220-360 hours
```

### Parallel Execution Timeline

```
Week 1-2 (Setup & Foundational):
├── P1a: Create libs.versions.toml (8 hours)
├── P3a: OpenTelemetry framework (25 hours)
├── P4a: Pre-commit hook (3 hours)
└── P2c: Naming standard doc (2 hours)

Week 2-3 (Core Implementation):
├── P1b: Shedlock version consolidation (12 hours)
├── P1c: gRPC upgrade (15 hours)
├── P1d: lettuce-core removal (5 hours)
├── P2a: 6 missing Tier 4 services (30 hours)
├── P3b Phase 1-2: Payment flow instrumentation (20 hours)
├── P4b: Developer guide (4 hours)

Week 4-5 (Scaling & Integration):
├── P2b: Admin-gateway security suite (15 hours)
├── P3b Phase 3-4: Order/fulfillment instrumentation (22 hours)
├── P3b Phase 5: Cross-service instrumentation (11 hours)
├── P3d: Integration tests (15 hours)

Week 6 (Polish & Backlog):
├── P5a: Dependabot triage (12 hours)
├── P5b: Staged releases (15 hours, if time allows)
└── Final reviews and documentation

Estimated Duration: 6 weeks (4 weeks critical path, 2 weeks buffer)
```

---

## Risk Assessment

### Risk 1: Gradle Version Catalog Adoption (P1a)

**Risk Level**: MEDIUM

**Description**: Moving 150+ versions to TOML may break some Gradle plugins or versions.

**Mitigation**:
- Test with smallest service first (routing-eta-service)
- Keep old build.gradle.kts comments for 1 iteration (easy rollback)
- Pin Gradle wrapper version (currently 8.6, document in README)

**Impact if not mitigated**: 2-3 day rollback + retry

### Risk 2: gRPC API Compatibility (P1c)

**Risk Level**: MEDIUM-HIGH

**Description**: 1.75.0 → 1.79.0 may break generated stubs for services with complex protobuf files.

**Mitigation**:
- Run codegen locally first, inspect diff before committing
- Test dispatch-optimizer (most complex gRPC consumer) thoroughly
- Have rollback commit ready (revert gRPC BOM)

**Impact if not mitigated**: 1-2 day investigation + revert, then staged upgrade approach

### Risk 3: Diagram Generation Quality (P2a)

**Risk Level**: MEDIUM

**Description**: Auto-generating 36 diagrams may produce inconsistent or incomplete docs.

**Mitigation**:
- Use experienced engineers (Wave 39 veterans)
- Peer review all diagrams before merge (2 reviewers per service)
- Create diagram generation template/checklist

**Impact if not mitigated**: Rework 20-30% of diagrams, ~10 hours re-work

### Risk 4: Trace Context Propagation Complexity (P3b)

**Risk Level**: HIGH

**Description**: W3C Trace Context propagation across 12 services is complex; missed propagation breaks visibility.

**Mitigation**:
- Start with synchronous calls (payment → order)
- Add async/event-driven traces only after sync verified
- Test with Jaeger UI visualization (manual verification)
- Have fallback: disable tracing if performance impact >10%

**Impact if not mitigated**: 1 week debugging + potential performance regression

### Risk 5: Dependabot Upgrade Cascades (P5)

**Risk Level**: MEDIUM

**Description**: Upgrading one library may force cascading upgrades (e.g., Spring Security → Spring Framework → all services).

**Mitigation**:
- Test upgrades in isolated branch first
- Use dependency management platform (Spring Cloud BOM) to pin transitive deps
- Coordinate with architecture team before committing multiple upgrades

**Impact if not mitigated**: Build failures across 10+ services, 2-3 day recovery

---

## Success Criteria

### Build System Hardening (P1)

- [ ] `gradle/libs.versions.toml` created with 150+ dependencies
- [ ] All 28 services build successfully with version catalog
- [ ] ShedLock: 16 services upgraded to v5.12.0
- [ ] gRPC: Upgraded to 1.79.0 with no breaking changes
- [ ] lettuce-core: Removed from config-feature-flag-service hardcoding
- [ ] Gradle dependency report shows single version per artifact (no conflicts)
- [ ] CI/CD: All 28 services pass integration tests post-upgrade

### Diagram Coverage (P2)

- [ ] 6 missing Tier 4 services documented (36 diagrams)
- [ ] Admin-gateway security suite: 7+ specialized diagrams
- [ ] All 28 services use standardized naming convention
- [ ] 313+ total diagrams across platform (306 existing + 48 new minimum)
- [ ] All diagrams render without Mermaid errors
- [ ] Service README files cross-reference diagram locations

### Distributed Tracing (P3)

- [ ] OpenTelemetry instrumentation library created and published
- [ ] 340+ instrumentation points added across 12 services
- [ ] Money-path trace (payment → order → fulfillment → delivery) verified end-to-end
- [ ] W3C Trace Context propagation headers verified across services
- [ ] Jaeger UI displays complete trace visualization
- [ ] Performance overhead <100ms per service call (verified with baseline comparison)
- [ ] 100+ integration tests for trace propagation

### Commit Standards (P4)

- [ ] Pre-commit hook installed on 100% of developer machines
- [ ] 72-char title validation enforced (0 commits >72 chars merged)
- [ ] Developer guide published and linked from README
- [ ] >90% of new commits follow format within 30 days

### Dependencies & Upgrades (P5)

- [ ] All 30 Dependabot alerts triaged and categorized
- [ ] CRITICAL vulnerabilities resolved
- [ ] HIGH-priority upgrades staged and tested
- [ ] Dependabot score improved (target: <5 open issues)

---

## Appendix: Related Documentation

- **ADR-011**: Admin-gateway authentication model
- **ADR-012**: Per-service token scoping
- **ADR-013**: Feature-flag cache invalidation
- **ADR-014**: Reconciliation authority model
- **ADR-015**: SLO and error-budget policy
- **OWNERSHIP_MODEL.md**: Service ownership, standing forums
- **service-slos.md**: SLO targets for all 28 services

---

## Next Steps

1. **Week 1**: Secure executive approval for 6-week timeline
2. **Week 1**: Assign engineers to priority tracks (kick-off meetings)
3. **Week 1**: Create tracking issues in GitHub (1 per task)
4. **Week 1**: Schedule standing sync meetings (daily P1/P2, 3x weekly P3, weekly P4-P5)
5. **Week 2**: Begin implementation (P1a, P3a, P4a in parallel)
6. **Week 6**: Final merge and deployment

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-03-21 | Wave 40 Planning Agent | Initial comprehensive roadmap |
