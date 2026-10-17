# Wave 40 Quick Reference Guide

**One-page summary of all 5 priorities. For detailed info, see IMPLEMENTATION_PLAN.md**

---

## Priority 1: Build System Hardening (P1) — 40-60 hours

**Problem**: 3 different shedlock versions, gRPC 6 months old, lettuce hardcoded

**4 Tasks** (1.5 FTE):

| Task | Hours | Deliverable | Status |
|------|-------|-------------|--------|
| 1a | 8 | `gradle/libs.versions.toml` (150+ deps) | ⏳ Planning |
| 1b | 12 | All 16 services → ShedLock 5.12.0 | ⏳ Planning |
| 1c | 15 | gRPC 1.75.0 → 1.79.0 (4 services) | ⏳ Planning |
| 1d | 5 | Remove lettuce-core hardcode | ⏳ Planning |

**Success**: `gradle build` reports 0 dependency conflicts, all 28 services compile

---

## Priority 2: Complete Diagram Coverage (P2) — 40-60 hours

**Problem**: 6 missing Tier 4 services (catalog, cart, pricing, notification, feature-flag, wallet), no admin-gateway security diagrams

**3 Tasks** (1.5 FTE):

| Task | Hours | Services | Diagrams | Status |
|------|-------|----------|----------|--------|
| 2a | 30 | 6 Tier 4 | 36 | ⏳ Planning |
| 2b | 15 | Admin-gateway | 7+ security | ⏳ Planning |
| 2c | 5 | All 28 | Naming standardization | ⏳ Planning |

**Success**: 313+ total diagrams (306 existing + 48 new), 100% consistency

**Services Documented**:
- Catalog: Product catalog, search, indexing
- Cart: Checkout workflow, multi-device sync
- Pricing: Dynamic pricing engine, promotions
- Notification: Multi-channel delivery (email/SMS/push)
- Feature Flag: Cache invalidation, Redis pub/sub
- Wallet: Ledger, loyalty points, transactions

---

## Priority 3: Distributed Tracing (P3) — 80-120 hours

**Problem**: No end-to-end request tracing; payment → order → fulfillment is invisible

**3 Tasks** (2.5 FTE):

| Task | Hours | Scope | Status |
|------|-------|-------|--------|
| 3a | 25 | OpenTelemetry framework library | ⏳ Planning |
| 3b | 40 | Span propagation (12 services, 340+ points) | ⏳ Planning |
| 3d | 15 | Integration tests (100+, Testcontainers) | ⏳ Planning |

**Instrumentation Points** (340+ total):
- Payment → Order → Fulfillment → Warehouse → Dispatch → Routing (money-path)
- Feature flags, notifications, audit logging

**Success**: Jaeger UI shows complete end-to-end trace, <100ms overhead/call

---

## Priority 4: Commit Standards (P4) — 4-8 hours (Parallel)

**Problem**: Inconsistent commit messages, no validation gates

**2 Tasks** (0.25 FTE):

| Task | Hours | Deliverable | Status |
|------|-------|-------------|--------|
| 4a | 3 | Pre-commit hook (72-char validation) | ⏳ Planning |
| 4b | 4-5 | Developer guide (CONTRIBUTING.md) | ⏳ Planning |

**Pre-Commit Checks**:
- Title ≤ 72 characters (reject if >72)
- Conventional commits encouraged (feat|fix|docs|...)
- Installed automatically on all developer machines

**Success**: 100% commit compliance, 0 oversized titles merged

---

## Priority 5: Dependencies & Upgrades (P5) — 20-40 hours (Backlog)

**Problem**: 30 open Dependabot alerts; some CRITICAL

**2 Tasks** (0.75 FTE):

| Task | Hours | Scope | Status |
|------|-------|-------|--------|
| 5a | 12 | Triage 30 alerts → CRITICAL/HIGH/MEDIUM | ⏳ Planning |
| 5b | 15 | Staged release of HIGH-priority upgrades | ⏳ Planning |

**Alert Categories**:
- **CRITICAL**: Security fixes in Spring Security, database drivers, crypto (update immediately)
- **HIGH**: Common utilities, logging, JSON processing (update in Priority 5b)
- **MEDIUM**: Dev-only dependencies (batch with next release)
- **LOW**: Minor version bumps (defer to Q2)

**Success**: <5 open security issues, all CRITICAL resolved

---

## Resource Allocation

```
Total: 6.5 FTE × 6 weeks = 220-360 hours

By Priority:
  P1: 1.5 FTE (build system hardening)
  P2: 1.5 FTE (diagrams)
  P3: 2.5 FTE (tracing) ← Most complex
  P4: 0.25 FTE (standards)
  P5: 0.75 FTE (dependencies, backlog)
  + Tech/DevOps leads: 0.5 FTE coordination
```

---

## Execution Timeline

| Week | P1 | P2 | P3 | P4 | P5 |
|------|----|----|----|----|-----|
| 1-2 | Setup (1a) | Planning | Framework (3a) | Hook (4a) | — |
| 2-4 | Core (1b,1c,1d) | **Services** (2a) | Spans (3b) | Guide (4b) | — |
| 4-5 | Testing | Security (2b) | Tests (3d) | — | — |
| 6 | Merge | Consolidate (2c) | Polish | — | Triage (5a) |

**Critical Path** (must complete on-time):
1. P1a (libs.versions.toml) → enables 1b, 1c, 1d
2. P3a (OTel framework) → enables 3b
3. P2a (6 services) → can run parallel with P1, P3

---

## Key Milestones

- **Day 1-2**: Teams formed, tools set up (P1a, P3a, P4a started)
- **Day 5**: libs.versions.toml complete, P1b/1c ready to start
- **Day 10**: 50% of P2a diagrams complete, P3b Phase 1-2 started
- **Day 21**: All P1 tasks complete, all P2 diagrams drafted
- **Day 30**: P3 tracing 100% complete, integration tests passing
- **Day 42**: All PRs merged, final testing, Wave 40 complete

---

## Success Criteria Checklist

### P1: Build System
- [ ] gradle/libs.versions.toml created (150+ dependencies)
- [ ] All 28 services use version catalog (0 hardcoded versions)
- [ ] ShedLock: 16 services upgraded to 5.12.0
- [ ] gRPC: Upgraded to 1.79.0 (0 breaking changes)
- [ ] Build succeeds: `gradle build` across all services

### P2: Diagrams
- [ ] 36 diagrams created (6 services × 6 diagrams each)
- [ ] 7+ admin-gateway security diagrams
- [ ] All 28 services use standardized naming (00-all-diagrams.md, 01-hld.md, etc.)
- [ ] 313+ total diagrams across platform

### P3: Tracing
- [ ] OTel library published and imported by all 12 services
- [ ] 340+ instrumentation points added
- [ ] End-to-end trace visible: payment → order → fulfillment
- [ ] Jaeger integration verified
- [ ] <100ms overhead per service call
- [ ] 100+ integration tests passing

### P4: Standards
- [ ] Pre-commit hook installed on 100% of machines
- [ ] 0 commits >72 characters merged
- [ ] Developer guide published (CONTRIBUTING.md)
- [ ] >90% compliance rate within 30 days

### P5: Dependencies
- [ ] 30 alerts triaged and categorized
- [ ] All CRITICAL vulnerabilities resolved
- [ ] HIGH-priority upgrades staged and tested
- [ ] Dependabot score: <5 open issues

---

## Risk Summary

| Priority | Risk | Mitigation |
|----------|------|-----------|
| P1 | Gradle plugin incompatibility | Test smallest service first |
| P1 | gRPC breaking changes | Codegen test before merge, rollback ready |
| P2 | Diagram inconsistency | Peer review (2 reviewers per service) |
| P3 | Trace propagation complexity | Start sync→async, test in Jaeger UI |
| P3 | Performance overhead >10% | Have fallback: disable tracing |
| P5 | Upgrade cascades | Test in isolation, use BOM for transitive deps |

---

## How to Access Full Details

- **Everything**: `/docs/wave-40/IMPLEMENTATION_PLAN.md` (48,000 words)
- **Index**: `/docs/wave-40/INDEX.md` (this file's longer cousin)
- **GitHub Issues**: Label all tasks `wave-40-{priority}` (automated tracking)

---

**Created**: 2026-03-21 | **Duration**: 6 weeks (220-360 hours) | **Team Size**: 6.5 FTE
