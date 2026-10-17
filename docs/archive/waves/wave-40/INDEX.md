# Wave 40 Planning Index

**Created**: 2026-03-21
**Status**: Planning Complete
**Total Scope**: 220-360 hours across 5 priority tracks
**Timeline**: 6 weeks (4 weeks critical path)

---

## Quick Navigation

### Core Documents

1. **IMPLEMENTATION_PLAN.md** ← START HERE
   - Comprehensive roadmap with all 5 priority tracks
   - Detailed task breakdown, resource allocation, risk assessment
   - 48,000+ words, fully detailed for execution

### Supporting Resources

- **TASK_TRACKER.md** (coming): Weekly progress tracking template
- **QUICK_REFERENCE.md**: 1-page priority summary

---

## Wave 40 Roadmap at a Glance

### Priority 1: Build System Hardening ✅ CRITICAL

**Why**: Eliminate dependency fragmentation (shedlock: 3 versions, gRPC outdated)

| Task | Hours | Status | Owner |
|------|-------|--------|-------|
| 1a: Create gradle/libs.versions.toml | 8 | ⏳ | Senior Backend Eng |
| 1b: Consolidate ShedLock versions | 12 | ⏳ | Backend Eng + QA |
| 1c: Upgrade gRPC 1.75.0 → 1.79.0 | 15 | ⏳ | Senior Eng + QA |
| 1d: Remove lettuce-core hardcoding | 5 | ⏳ | Backend Eng |
| **Total** | **40-60** | **⏳** | **1.5 FTE** |

**Success Metric**: All 28 services build with single-version dependency tree

---

### Priority 2: Complete Diagram Coverage ✅ HIGH

**Why**: Fill 6 missing Tier 4 services (catalog, cart, pricing, notification, feature-flag, wallet) + admin-gateway security suite

| Task | Hours | Diagrams | Owner |
|------|-------|----------|-------|
| 2a: 6 Tier 4 services | 30 | 36+ | 2 Backend Engs |
| 2b: Admin-gateway security suite | 15 | 7+ | Infra/Security Eng |
| 2c: Consolidate naming standards | 5 | — | Doc Eng |
| **Total** | **40-60** | **48+** | **1.5 FTE** |

**Success Metric**: 313+ diagrams total (306 existing + 48 new), 100% naming consistency

---

### Priority 3: Distributed Tracing ✅ MEDIUM-HIGH

**Why**: Production observability: payment → order → fulfillment tracing (money-path visibility)

| Task | Hours | Scope | Owner |
|------|-------|-------|-------|
| 3a: OTel framework library | 25 | Core instrumentation | Senior Eng |
| 3b: Span propagation (12 services) | 40 | 340+ points, 5 phases | 3 Backend Engs |
| 3d: Integration tests | 15 | 100+ tests, Testcontainers | QA + Backend |
| **Total** | **80-120** | **340+ spans** | **2.5 FTE** |

**Success Metric**: End-to-end trace visible in Jaeger, <100ms overhead per call

---

### Priority 4: Commit Standards ✅ MEDIUM (Parallel)

**Why**: Enforce quality gates (72-char titles, conventional commits)

| Task | Hours | Scope | Owner |
|------|-------|-------|-------|
| 4a: Pre-commit hook | 3 | 72-char validation | DevOps Eng |
| 4b: Developer guide | 4-5 | CONTRIBUTING.md | Tech Lead |
| **Total** | **4-8** | **All devs** | **0.25 FTE** |

**Success Metric**: 100% commit compliance within 30 days, 0 oversized titles merged

---

### Priority 5: Dependencies & Upgrades ⏳ LOW (Backlog)

**Why**: Resolve 30 Dependabot vulnerabilities, strategic upgrades

| Task | Hours | Scope | Owner |
|------|-------|-------|-------|
| 5a: Triage alerts | 12 | Categorize CRITICAL/HIGH/MEDIUM | Senior Eng |
| 5b: Staged releases | 15 | HIGH-priority upgrades | 2 Backend Engs |
| **Total** | **20-40** | **30 alerts** | **0.75 FTE** |

**Success Metric**: <5 open security issues, all CRITICAL resolved

---

## Resource Allocation Summary

```
Total: 6.5 FTE for 220-360 hours (6-week sprint)

Team Composition:
├── 1 Tech Lead (20% coordination)
├── 1 DevOps Lead (20% infrastructure)
├── 3 Senior Backend Engineers (100% each on core work)
├── 2 Backend Engineers (100% on tracing/upgrades)
├── 1 QA Engineer (50% on testing)
├── 1 Infra/Security Engineer (50% on diagrams/security)
└── 1 Documentation Engineer (25% on naming standards)
```

---

## Critical Path Timeline

```
Week 1-2: Foundation
  ├── P1a: libs.versions.toml (8h) ✓ Blocker for P1b, P1c
  ├── P3a: OTel framework (25h) ✓ Blocker for P3b
  ├── P4a: Pre-commit hook (3h)
  └── P2c: Naming standard (2h) ✓ Enables P2a

Week 2-4: Core Implementation (Parallel)
  ├── P1b: ShedLock consolidation (12h)
  ├── P1c: gRPC upgrade (15h)
  ├── P1d: lettuce-core removal (5h)
  ├── P2a: 6 Tier 4 services (30h) ✓ 4x parallelization
  ├── P3b Phase 1-2: Payment flow (20h)
  ├── P3b Phase 3-4: Order/fulfillment (22h)
  └── P4b: Developer guide (4h)

Week 4-5: Scaling
  ├── P2b: Admin-gateway security (15h)
  ├── P3b Phase 5: Cross-service (11h)
  └── P3d: Integration tests (15h)

Week 6: Polish
  ├── Code review & merge (all PRs)
  ├── Final integration testing
  ├── P5a: Dependabot triage (12h, if time)
  └── Documentation updates
```

---

## Executive Summary

**Wave 40 delivers**:
1. ✅ Unified dependency management (0 conflicts, 150+ versions catalogued)
2. ✅ Complete service documentation (313+ diagrams, 100% naming consistency)
3. ✅ Production tracing (end-to-end visibility, 340+ instrumentation points)
4. ✅ Developer experience improvements (automated commit standards, pre-commit hooks)
5. ✅ Security hardening (30 vulnerabilities triaged, CRITICAL issues resolved)

**Investment**: 220-360 hours over 6 weeks
**ROI**:
- Build reliability: 30% fewer dependency conflicts
- Observability: 100% request tracing in production
- Developer velocity: 2-3 min saved per commit (pre-commit validation)
- Security posture: All CRITICAL CVEs resolved within 2 weeks

---

## How to Use This Roadmap

1. **For Execution**: Read IMPLEMENTATION_PLAN.md top-to-bottom
2. **For Tracking**: Use GitHub Projects with issues labeled `wave-40-{priority}`
3. **For Oversight**: Weekly sync on each priority track (15 min each)
4. **For Risk**: Review "Risk Assessment" section in main plan

---

## Key Contacts

- **Wave 40 Tech Lead**: [Assign from team]
- **P1 Owner (Build System)**: [Senior Backend Engineer]
- **P2 Owner (Diagrams)**: [Backend Engineer]
- **P3 Owner (Tracing)**: [Senior Backend Engineer]
- **P4 Owner (Standards)**: [DevOps Engineer]
- **P5 Owner (Dependencies)**: [Senior Engineer]

---

## Document Hierarchy

```
/docs/wave-40/
├── IMPLEMENTATION_PLAN.md ← Detailed execution guide (START HERE)
├── INDEX.md ← This file (quick reference)
├── TASK_TRACKER.md ← Weekly progress (coming)
├── QUICK_REFERENCE.md ← 1-page summary (coming)
├── DEPENDABOT_TRIAGE.md ← P5 alert analysis (coming)
└── POSTMORTEM.md ← Lessons learned (after completion)
```

---

**Last Updated**: 2026-03-21
**Next Review**: 2026-04-04 (1 week into execution)
