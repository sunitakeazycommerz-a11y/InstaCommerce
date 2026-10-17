# Wave 40 Task Tracking Template

**Updated Weekly** | Week 1 of 6 | 2026-03-21

---

## Executive Status Dashboard

| Priority | % Complete | Hours Used | Hours Remaining | Status | Blocker? |
|----------|------------|-----------|-----------------|--------|----------|
| P1: Build System | 0% | 0/40-60 | 60 | 🔴 Not Started | YES (blocks P1b,1c,1d) |
| P2: Diagrams | 0% | 0/40-60 | 60 | 🔴 Not Started | No |
| P3: Tracing | 0% | 0/80-120 | 120 | 🔴 Not Started | YES (blocks P3b) |
| P4: Standards | 0% | 0/4-8 | 8 | 🔴 Not Started | No |
| P5: Dependencies | 0% | 0/20-40 | 40 | ⏳ Planned | No (backlog) |
| **TOTAL** | **0%** | **0/220-360** | **360** | 🔴 | — |

**On Track?**: No (Week 1 just starting)
**Critical Path**: P1a, P3a must complete by Day 5
**Next Review**: 2026-03-28 (end of Week 1)

---

## Priority 1: Build System Hardening

**Target**: 40-60 hours | **Owner**: [Senior Backend Engineer] | **Status**: 🔴 Not Started

### Task 1a: Create gradle/libs.versions.toml (8 hours)

- [ ] **Day 1**: Parse current build files, list all 150+ dependencies
- [ ] **Day 1-2**: Create libs.versions.toml structure (versioning, BOM imports)
- [ ] **Day 2**: Resolve dependency conflicts (lz4-java, protobuf, etc.)
- [ ] **Day 3**: Test with smallest service (routing-eta-service)
- [ ] **Day 3**: Expand to all 28 services
- [ ] **Day 4**: Code review + peer approval
- [ ] **Day 5**: Merge to master

**Subtasks**:
- [ ] Research version conflicts: `gradle dependencyInsight --dependency {artifact}`
- [ ] Document version rationale (LTS, security, compatibility)
- [ ] Add libs.versions.toml to .gitignore (if not already)
- [ ] Update build.gradle.kts to import version catalog

**Status**: ⏳ Planning | **Hours Used**: 0/8 | **Assigned To**: [NAME]

**Blockers**: None
**Risk**: Gradle plugin compatibility (mitigated: rollback commit ready)

---

### Task 1b: Consolidate ShedLock Versions (12 hours)

- [ ] **Day 6**: Update libs.versions.toml with shedlock-bom (v5.12.0)
- [ ] **Day 6-7**: Update 6 services (v5.10.0 → v5.12.0): routing-eta, config-feature-flag, inventory
- [ ] **Day 7-8**: Update 10 services (v5.10.2 → v5.12.0)
- [ ] **Day 8**: Create integration test (cross-service lock validation)
- [ ] **Day 8-9**: Code review across all 16 services
- [ ] **Day 10**: Merge all PRs

**Affected Services** (16 total):
- [ ] routing-eta-service (v5.10.0)
- [ ] config-feature-flag-service (v5.10.0)
- [ ] inventory-service (v5.10.0)
- [ ] cart-service (v5.10.2)
- [ ] catalog-service (v5.10.2)
- [ ] fraud-detection-service (v5.10.2)
- [ ] identity-service (v5.10.2)
- [ ] audit-trail-service (v5.10.2)
- [ ] rider-fleet-service (v5.10.2)
- [ ] search-service (v5.10.2)
- [ ] wallet-loyalty-service (v5.10.2)
- [ ] pricing-service (v5.10.2)
- [ ] fulfillment-service (v5.10.2)
- [ ] order-service (v5.12.0)
- [ ] warehouse-service (v5.12.0)
- [ ] checkout-orchestrator-service (v5.12.0)

**Status**: ⏳ Blocked (waiting for 1a) | **Hours Used**: 0/12 | **Assigned To**: [NAME]

**Blockers**: Task 1a must complete first

---

### Task 1c: Upgrade gRPC 1.75.0 → 1.79.0 (15 hours)

- [ ] **Day 11**: Update gRPC BOM in root build.gradle.kts (line 33)
- [ ] **Day 11**: Run `gradle :services:*/generateProto` (codegen test)
- [ ] **Day 12**: Inspect generated stubs (diff before/after)
- [ ] **Day 12-13**: Test integration: admin-gateway → identity-service JWKS
- [ ] **Day 13**: Load test: dispatch-optimizer (1000 gRPC msg/sec)
- [ ] **Day 14**: Code review + approval
- [ ] **Day 15**: Merge

**Affected Services** (4 gRPC consumers):
- [ ] admin-gateway-service
- [ ] cdc-consumer-service
- [ ] dispatch-optimizer-service
- [ ] routing-eta-service

**Status**: ⏳ Blocked (waiting for 1a) | **Hours Used**: 0/15 | **Assigned To**: [NAME] + [QA]

**Blockers**: Task 1a must complete first
**Risk**: gRPC API breaking change (mitigated: test codegen first)

---

### Task 1d: Remove lettuce-core Hard-Coding (5 hours)

- [ ] **Day 16**: Add lettuce (6.3.2) to libs.versions.toml
- [ ] **Day 16**: Update config-feature-flag-service build.gradle.kts
- [ ] **Day 16**: Run FlagCacheInvalidatorTest (50+ test cases)
- [ ] **Day 17**: Code review
- [ ] **Day 17**: Merge

**Status**: ⏳ Blocked (waiting for 1a) | **Hours Used**: 0/5 | **Assigned To**: [NAME]

**Blockers**: Task 1a must complete first

---

## Priority 2: Diagram Coverage

**Target**: 40-60 hours | **Owner**: [Backend Engineer] | **Status**: 🔴 Not Started

### Task 2a: Generate 6 Tier 4 Services (30 hours)

**Services** (5 hours each):

- [ ] **Catalog Service** (5h)
  - [ ] Parse source (ProductService, ProductEntity, search indexing)
  - [ ] Generate 6 diagrams (HLD, LLD, ER, flowchart, sequence, integration)
  - [ ] Write implementation docs (API, events, database, resilience)
  - [ ] Peer review
  - **Owner**: [NAME] | **Status**: ⏳ | **Hours Used**: 0/5

- [ ] **Cart Service** (5h)
  - [ ] Parse source
  - [ ] Generate 6 diagrams
  - [ ] Write implementation docs
  - [ ] Peer review
  - **Owner**: [NAME] | **Status**: ⏳ | **Hours Used**: 0/5

- [ ] **Pricing Service** (5h)
  - [ ] Parse source (PricingService, RuleEngine, PromotionService)
  - [ ] Generate 6 diagrams
  - [ ] Write implementation docs
  - [ ] Peer review
  - **Owner**: [NAME] | **Status**: ⏳ | **Hours Used**: 0/5

- [ ] **Notification Service** (5h)
  - [ ] Parse source (multi-channel delivery, templates, tracking)
  - [ ] Generate 6 diagrams
  - [ ] Write implementation docs
  - [ ] Peer review
  - **Owner**: [NAME] | **Status**: ⏳ | **Hours Used**: 0/5

- [ ] **Config-Feature-Flag Service** (5h)
  - [ ] Parse source (FlagService, FlagCache, Redis pub/sub)
  - [ ] Generate 6 diagrams
  - [ ] Write implementation docs
  - [ ] Peer review
  - **Owner**: [NAME] | **Status**: ⏳ | **Hours Used**: 0/5

- [ ] **Wallet Service** (5h)
  - [ ] Parse source (WalletService, LedgerService, PointsCalculator)
  - [ ] Generate 6 diagrams
  - [ ] Write implementation docs
  - [ ] Peer review
  - **Owner**: [NAME] | **Status**: ⏳ | **Hours Used**: 0/5

**Status**: ⏳ Planning | **Hours Used**: 0/30 | **Assigned To**: [2 Backend Engs]

---

### Task 2b: Admin-Gateway Security Suite (15 hours)

**7 Diagrams** (2 hours each):

- [ ] **Authentication Flow** (2h) — Token acquisition, JWKS, validation
- [ ] **Authorization Model** (2h) — Audience scoping, role-based access
- [ ] **Token Lifecycle** (1.5h) — Generation, validation, expiration
- [ ] **Istio Integration** (2h) — RequestAuthentication, AuthorizationPolicy
- [ ] **Multi-Service Access** (2h) — Service-to-service token exchange
- [ ] **Incident Response** (2h) — Compromised token detection, audit logging
- [ ] **Implementation Details** (2h) — Code components, filter chain

**Status**: ⏳ Planning | **Hours Used**: 0/15 | **Assigned To**: [Infra/Security Eng]

---

### Task 2c: Consolidate Naming Convention (5 hours)

- [ ] Audit all 28 services for naming inconsistencies
- [ ] Standardize on: `00-all-diagrams.md`, `01-hld.md`, `02-lld.md`, `03-er.md`, `04-flowchart.md`, `05-sequence.md`
- [ ] Rename outliers (payment: `er_diagram.md` → `03-er.md`, etc.)
- [ ] Create `/docs/DIAGRAM_NAMING_STANDARD.md`
- [ ] Update service README files

**Status**: ⏳ Planning | **Hours Used**: 0/5 | **Assigned To**: [Doc Eng]

---

## Priority 3: Distributed Tracing

**Target**: 80-120 hours | **Owner**: [Senior Backend Engineer] | **Status**: 🔴 Not Started

### Task 3a: OpenTelemetry Framework (25 hours)

**Library**: `/services/libs/otel-instrumentation/`

- [ ] **Day 1-2**: Set up shared library structure (10h)
  - [ ] Create TraceInitializer (global TracerProvider, SpanExporter)
  - [ ] Create HttpClientInstrumentation (RestTemplate, WebClient)
  - [ ] Create DatabaseInstrumentation (JDBC pooling, query spans)
  - [ ] Create KafkaInstrumentation (producer/consumer spans)
  - [ ] Create GrpcInstrumentation (client/server interceptors)

- [ ] **Day 3**: Auto-configuration (5h)
  - [ ] OtelAutoConfiguration.java
  - [ ] spring.factories entry
  - [ ] Build test

- [ ] **Day 4**: Documentation (5h)
  - [ ] README.md with code examples
  - [ ] Instrumentation patterns (money-path, event-driven, external calls)
  - [ ] Configuration guide

- [ ] **Day 5**: Code review + approval (5h)

**Status**: ⏳ Planning | **Hours Used**: 0/25 | **Assigned To**: [Senior Eng]

---

### Task 3b: Span Propagation (40 hours, 5 phases)

**Phase 1: Ingress to Payment (8h)**
- [ ] Admin-Gateway (3h) — Capture request metadata in span attributes
- [ ] Checkout-Orchestrator (5h) — Temporal workflow spans, orchestration steps

**Phase 2: Payment Service (12h)**
- [ ] Payment Service (8h) — Top-level span, Stripe API child spans, recovery jobs
- [ ] Payment-Webhook Service (4h) — Webhook receipt, signature verification, dedup

**Phase 3: Order Service (10h)**
- [ ] Order Service (10h) — Order creation, event retrieval, Temporal workflow, CDC publish

**Phase 4: Fulfillment & Logistics (12h)**
- [ ] Fulfillment Service (5h) — Consume OrderCreated, create pick jobs
- [ ] Warehouse Service (3h) — Location query, cache lookup
- [ ] Dispatch-Optimizer Service (3h) — Route calculation, Haversine distance
- [ ] Routing-ETA Service (1h) — ETA calculation

**Phase 5: Cross-Service (8h)**
- [ ] Feature-Flag Service (2h) — Flag evaluation spans
- [ ] Notification Service (3h) — Multi-channel notification spans
- [ ] Audit Service (3h) — Audit logging spans

**Status**: ⏳ Blocked (waiting for 3a) | **Hours Used**: 0/40 | **Assigned To**: [3 Backend Engs]

---

### Task 3d: Integration Tests (15 hours)

**Test Suite**: `/services/libs/otel-test/`

- [ ] **Day 18-19**: Set up Testcontainers + Jaeger (5h)
- [ ] **Day 19-20**: Happy path tests (5h) — Money-path trace verification
- [ ] **Day 20**: Error path tests (5h) — Error span, retry attempt traces
- [ ] **Day 21**: Cross-service tests (2h) — W3C Trace Context propagation
- [ ] **Day 21**: Code review (1h)

**Status**: ⏳ Blocked (waiting for 3b) | **Hours Used**: 0/15 | **Assigned To**: [QA + Backend Eng]

---

## Priority 4: Commit Standards

**Target**: 4-8 hours | **Owner**: [DevOps Engineer] | **Status**: 🔴 Not Started

### Task 4a: Pre-Commit Hook (3 hours)

- [ ] **Day 1**: Create `/.git/hooks/commit-msg` script (72-char validation)
- [ ] **Day 1**: Create `/scripts/install-hooks.sh` (setup automation)
- [ ] **Day 2**: Test: 71-char title (success), 73-char title (rejected)
- [ ] **Day 2**: Integrate with CI/CD (post-checkout hook)
- [ ] **Day 3**: Documentation + merge

**Status**: ⏳ Planning | **Hours Used**: 0/3 | **Assigned To**: [DevOps Eng]

---

### Task 4b: Developer Guide (4-5 hours)

**File**: `/CONTRIBUTING.md` (new section or create new file)

- [ ] **Day 4**: Write format guide (commit types, scopes, examples)
- [ ] **Day 4-5**: Write best practices (atomic commits, imperative mood, etc.)
- [ ] **Day 5**: Add troubleshooting + hook installation instructions
- [ ] **Day 5**: Peer review + merge

**Status**: ⏳ Planning | **Hours Used**: 0/5 | **Assigned To**: [Tech Lead]

---

## Priority 5: Dependencies & Upgrades (Backlog)

**Target**: 20-40 hours | **Owner**: [Senior Engineer] | **Status**: ⏳ Backlog

### Task 5a: Dependabot Triage (12 hours)

- [ ] Review all 30 open alerts
- [ ] Categorize: CRITICAL (immediate), HIGH (P5b), MEDIUM (batch), LOW (defer)
- [ ] Create `/docs/wave-40/DEPENDABOT_TRIAGE.md` (output)
- [ ] Define upgrade path for each

**Status**: ⏳ Backlog | **Hours Used**: 0/12 | **Assigned To**: TBD

---

### Task 5b: Staged Releases (15 hours)

- [ ] Group HIGH-priority upgrades by service
- [ ] Test in isolated branch per library
- [ ] Run full test suite + compatibility checks
- [ ] Create separate commits per major upgrade

**Status**: ⏳ Backlog | **Hours Used**: 0/15 | **Assigned To**: TBD

---

## Weekly Summary

### Week 1 (2026-03-21 to 2026-03-28)

**Goals**:
- [ ] P1a: Complete libs.versions.toml (8h)
- [ ] P3a: Complete OTel framework (25h)
- [ ] P4a: Complete pre-commit hook (3h)
- [ ] P2c: Complete naming standard doc (5h)

**Estimated Hours**: 41h
**Assigned Team**: 4 people (1 FTE each, + overlap for review)

**Deliverables**:
- gradle/libs.versions.toml (merged)
- OTel library skeleton (PR ready)
- Pre-commit hook (CI integrated)
- Diagram naming standard (approved)

**Risks This Week**:
- Gradle version conflicts (mitigated: test incrementally)
- OTel framework design decisions (mitigated: architecture review Day 2)

---

## Notes & Issues

### Open Questions

1. **Which Jaeger deployment** for tracing? (local dev vs. staging vs. production)
2. **Feature flag for tracing** performance impact testing?
3. **Commit hook enforcement** — strict (reject all failures) or advisory (warnings only)?

### Decisions Made

- Use W3C Trace Context standard for propagation (vs. Jaeger propagation)
- ShedLock upgrade to v5.12.0 (latest stable, API compatible with v5.10.x)
- Naming convention: 00-all-diagrams.md (index) + 01-XX.md (numbered diagrams)

### Lessons Learned (from Wave 39)

- Parallel team execution (2-3 engineers on same task) reduces bottlenecks
- Peer review (2 reviewers per service) catches integration issues early
- Testcontainers integration tests critical for catching runtime issues

---

## Next Steps (For Week 1 Lead)

1. **By EOD 2026-03-21** (today):
   - [ ] Assign owners to each task (fill in [NAME] placeholders)
   - [ ] Create GitHub issues (label: wave-40-{priority})
   - [ ] Schedule daily standups (15 min per priority, Mon-Fri)
   - [ ] Set up shared dashboard/wiki for real-time updates

2. **By 2026-03-23** (Day 3):
   - [ ] P1a: libs.versions.toml draft ready for review
   - [ ] P3a: OTel framework architecture approved
   - [ ] P4a: Pre-commit hook tested locally
   - [ ] P2c: Naming standard doc published

3. **By 2026-03-28** (End of Week 1):
   - [ ] P1a: Merged to master
   - [ ] P3a: PR ready for final review
   - [ ] P4a: Deployed to CI/CD
   - [ ] First 2 P2a services (catalog, cart) diagrams drafted

---

**Document Created**: 2026-03-21
**Last Updated**: 2026-03-21
**Next Update**: 2026-03-28 (Weekly)
