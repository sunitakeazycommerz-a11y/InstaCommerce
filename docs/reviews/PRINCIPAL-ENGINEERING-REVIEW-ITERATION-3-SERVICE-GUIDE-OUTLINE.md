# InstaCommerce — Iteration 3 Principal Review  
## Service-Wise Implementation Guide: Section Structure, Depth Expectations, and Reusable Template

**Date:** 2026-03-07  
**Audience:** CTO, Principal Engineers, Staff Engineers, Engineering Managers, SRE, Platform, Data/ML  
**Scope:** Canonical guide outline governing how every service cluster section must be written, what depth is required at each sub-section, and the reusable template that authors instantiate for each cluster.  
**Depends on:** `PRINCIPAL-ENGINEERING-REVIEW-ITERATION-2-2026-03-06.md` — all findings, risk register, and wave assignments carry forward.  
**Purpose of this document:** Define the exact shape, content contract, and editorial standards for the per-cluster implementation guide sections that will follow. Each cluster section, when written to this template, must be self-contained enough for a staff engineer to pick it up without reading the full Iter 2 review.

---

## Document map

```
PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-SERVICE-GUIDE-OUTLINE.md   ← this file
│
├── Part I   — Purpose and scope of the service guide
├── Part II  — Service cluster taxonomy
├── Part III — Template specification (authoritative, reusable)
│               ├── Section-by-section depth contract
│               └── Blank template (copy-paste ready)
├── Part IV  — Cluster pre-fills (issue summary seeded from Iter 2 findings)
│               ├── Cluster 1  Edge & Identity
│               ├── Cluster 2  Transactional Core (Money Path)
│               ├── Cluster 3  Read & Decision Plane (Catalog/Search/Cart/Pricing)
│               ├── Cluster 4  Inventory & Dark Store
│               ├── Cluster 5  Fulfillment & Logistics
│               ├── Cluster 6  Customer & Engagement
│               ├── Cluster 7  Platform Foundations (Audit/Config/Feature Flags)
│               ├── Cluster 8  Event & Data Plane
│               └── Cluster 9  AI/ML Platform
├── Part V   — Cross-cluster governance map
└── Part VI  — Operating cadences
```

---

# Part I — Purpose and scope of the service guide

## 1.1 Why this guide exists

Iteration 2 established that InstaCommerce has the right macro-architecture but insufficient operational hardening. The gap is not a design problem — it is an implementation discipline and governance problem. The per-cluster implementation guide is the mechanism by which the Iter 2 findings become actionable, owned, and trackable at the individual service or service-group level.

This outline defines:

1. **What** sections every cluster chapter must contain and in what order.
2. **How deep** each section must go — what distinguishes a thorough section from a stub.
3. **What format** is required so chapters are scannable, comparable across clusters, and machine-parseable for tracking.
4. **What pre-fills** are already seeded from the Iter 2 risk register so authors do not start from a blank page.

## 1.2 Intended authoring workflow

Each cluster chapter is owned by the staff or principal engineer responsible for that cluster (see governance map in Part V). The workflow is:

1. Author copies the blank template from Part III.
2. Author references the Iter 2 findings and the per-service review files under `docs/reviews/` for the cluster's services.
3. Author fills in all sections to the depth contract.
4. Chapter is reviewed by a second principal engineer and the SRE owner for that cluster.
5. Approved chapter is committed under `docs/reviews/iter3/` and its ADR pointer is added to `docs/adr/`.
6. Todo in the implementation tracking database is marked `done`.

## 1.3 What this guide is not

- It is not a product requirements document.
- It is not a sprint plan or ticket backlog.
- It is not a replacement for ADRs — it references ADRs but does not substitute for them.
- It is not a code review — it maps to code but is written at the implementation-plan level.

---

# Part II — Service cluster taxonomy

The 20 Java, 8 Go, and 2 Python services are grouped into nine clusters. Clusters follow the Iter 2 wave assignments but are restructured to reflect operational ownership boundaries rather than pure implementation sequencing.

| Cluster | Display name | Services | Iter 2 wave(s) | Risk register items |
|---------|-------------|----------|----------------|---------------------|
| C1 | Edge & Identity | `identity-service`, `mobile-bff-service`, `admin-gateway-service` | Wave 0 / Wave 1 | 3, 12 |
| C2 | Transactional Core | `checkout-orchestrator-service`, `order-service`, `payment-service`, `payment-webhook-service`, `reconciliation-engine` | Wave 1 | 1, 2 |
| C3 | Read & Decision Plane | `catalog-service`, `search-service`, `cart-service`, `pricing-service` | Wave 3 | 5, 6 |
| C4 | Inventory & Dark Store | `inventory-service`, `warehouse-service` | Wave 2 | 7 |
| C5 | Fulfillment & Logistics | `fulfillment-service`, `rider-fleet-service`, `dispatch-optimizer-service`, `routing-eta-service`, `location-ingestion-service` | Wave 2 | 7, 8 |
| C6 | Customer & Engagement | `notification-service`, `wallet-loyalty-service`, `fraud-detection-service` | Wave 3 | — |
| C7 | Platform Foundations | `audit-trail-service`, `config-feature-flag-service` | Wave 5 | 12 |
| C8 | Event & Data Plane | `outbox-relay-service`, `cdc-consumer-service`, `stream-processor-service`, `contracts/` | Wave 4 | 4, 9 |
| C9 | AI/ML Platform | `ai-orchestrator-service`, `ai-inference-service`, `data-platform/`, `ml/` | Wave 4 / Wave 6 | 10, 11 |

### Cluster ownership matrix (to be filled at governance kick-off)

| Cluster | Engineering owner | SRE owner | Data/ML liaison | PM anchor |
|---------|------------------|-----------|-----------------|-----------|
| C1 | TBD | TBD | — | TBD |
| C2 | TBD | TBD | — | TBD |
| C3 | TBD | TBD | TBD | TBD |
| C4 | TBD | TBD | — | TBD |
| C5 | TBD | TBD | — | TBD |
| C6 | TBD | TBD | — | TBD |
| C7 | TBD | TBD | — | TBD |
| C8 | TBD | TBD | TBD | TBD |
| C9 | TBD | TBD | TBD | TBD |

---

# Part III — Template specification

## 3.1 Section-by-section depth contract

This contract defines the mandatory and optional sub-sections for each section, the required depth (line-count guidance is advisory, not prescriptive), and the failure modes that make a section unacceptable.

---

### SECTION 0 — Cluster header block

**Purpose:** Scannable identity block at the top of every chapter. Enables tooling to parse cluster metadata.

**Required fields:**
```
Cluster ID:          C<N>
Cluster name:        <Display name>
Services:            <comma-separated module paths from settings.gradle.kts or go.mod>
Iter 2 wave(s):      Wave <N>[, Wave <M>]
Risk register items: <item numbers from Iter 2 §6>
Engineering owner:   <name or TBD>
SRE owner:           <name or TBD>
ADR references:      ADR-<NNN> [, ADR-<NNN>]
Last updated:        <ISO date>
Status:              [DRAFT | REVIEW | APPROVED | IMPLEMENTED]
```

**Depth expectation:** This is a header block. No prose. Must be complete before a chapter enters REVIEW status.

**Failure modes:** Omitting service list, leaving risk items blank, not linking to an ADR number.

---

### SECTION 1 — Issue summary

**Purpose:** Precisely name what is broken, absent, or inconsistent in the current state of the cluster's services. This is the single most important section. If this section is vague, every downstream section is untrustworthy.

**Required sub-sections:**

#### 1.1 Critical findings (must-fix before any production hardening wave)

For each critical finding, provide:

- **Finding ID:** `C<cluster>-F<N>` (e.g., `C2-F1`)
- **Title:** one-sentence name
- **Severity:** `CRITICAL | HIGH | MEDIUM | LOW`
- **Affected services:** list
- **Evidence:** exact file path(s) or config keys with line-number references where possible
- **Production consequence:** what fails, degrades, or becomes untrustworthy at scale if not fixed
- **Iter 2 reference:** section number in Iter 2 review (e.g., `§4.5`)

#### 1.2 High findings (must-fix before next wave starts)

Same format as 1.1.

#### 1.3 Deferred / watch items (valid deferrals with explicit rationale)

For each deferred item:
- Finding ID, title, severity
- Why deferred (cost, dependency, wave sequencing)
- Trigger condition that would re-elevate it (e.g., "if daily order volume exceeds 50k, re-evaluate")

**Depth expectation:** CRITICAL section must have ≥ 1 finding with full evidence. Empty critical sections are not acceptable — use a note like "No critical findings at time of writing; re-examine after Wave 0 truth restoration." HIGH section must have ≥ 1 finding or an explicit "none identified." Every finding must have an evidence pointer — prose-only findings are not accepted.

**Failure modes:** Findings without file/line evidence. Findings copied from Iter 2 without being updated to reflect code reality. No severity assignment. Missing production consequence statement.

---

### SECTION 2 — Target state

**Purpose:** Define precisely what "done" looks like for this cluster. Target state drives options analysis, migration design, and validation gates. It must be specific enough that a staff engineer can write a PR checklist from it.

**Required sub-sections:**

#### 2.1 Functional target state

Prose + bullet list: What the cluster's services must do correctly, reliably, and observably. This is NOT a feature list — it is a correctness and reliability specification.

- Each bullet starts with an active verb and is testable.
- Example (C2): "Payment authorization is idempotent — retrying a `POST /payments/authorize` with the same `X-Idempotency-Key` returns the original result without a second charge, verified by integration test."
- Minimum: 5 bullets for a small cluster (2 services), 10 for a large cluster (4+ services).

#### 2.2 Non-functional target state

A table covering:

| Dimension | Current state | Target | Measurement method |
|-----------|--------------|--------|-------------------|
| p95 latency (hot path) | | | |
| p99 latency (hot path) | | | |
| Error rate (hot path) | | | |
| Availability SLO | | | |
| Idempotency coverage | | | |
| Contract schema coverage | | | |
| Test coverage (unit + integration) | | | |
| Observability completeness | | | |

"Current state" cells must be filled with honest assessments (even "unknown — not measured").

#### 2.3 Explicit out-of-scope items

What is explicitly NOT the target for this cluster chapter. This prevents scope creep during review. Each out-of-scope item should note which cluster or ADR it belongs to instead.

**Depth expectation:** The functional target state must be concrete enough to generate a definition-of-done checklist. Vague targets like "improve reliability" are not acceptable. The NFR table must have all rows filled — "TBD" in the current state column is acceptable; leaving measurement method blank is not.

**Failure modes:** No NFR table. Functional bullets that are not testable. Missing out-of-scope section (often causes scope creep during review).

---

### SECTION 3 — Options analysis

**Purpose:** Present 2–4 implementation paths, evaluated honestly. This section exists because most clusters have genuine tradeoffs that deserve explicit documentation before a direction is chosen.

**Required sub-sections:**

#### 3.1 Options table

A summary table preceding the detailed analysis:

| Option | Summary | Complexity | Risk | Time estimate | Recommended? |
|--------|---------|-----------|------|---------------|-------------|
| A — <name> | | Low/Med/High | Low/Med/High | | Yes/No/Conditional |
| B — <name> | | | | | |
| C — <name> | | | | | |

#### 3.2 Option detail per option

For each option (A, B, C, …):

- **Name and one-line description**
- **What it changes** (services, schema, contracts, infra)
- **What it preserves** (backward-compatible surface)
- **Failure modes and mitigations**
- **Competitor precedent** (if relevant — cite Instacart, Zepto, Blinkit, DoorDash, etc. or reference Iter 2 §5)
- **Principal recommendation** for or against, with rationale

#### 3.3 Chosen direction

Which option (or hybrid) is chosen and why. This is the authoritative direction and must be signed off by the cluster's engineering owner before the chapter reaches APPROVED status.

**Depth expectation:** Every option must have at least four bullets in its detail block. The chosen direction must be explicit — a chapter in APPROVED status cannot have "TBD" in this sub-section. Options must be meaningfully distinct — presenting three names for the same approach is not acceptable.

**Failure modes:** Only one option presented. No failure-modes analysis per option. No competitor precedent when one is relevant. Missing chosen direction.

---

### SECTION 4 — Migration path

**Purpose:** Define the sequence of steps to move from current state to target state. Must be phased, reversible where possible, and explicitly sequenced with dependency constraints.

**Required sub-sections:**

#### 4.1 Migration phases table

| Phase | Name | Duration | Owner | Entry gate | Exit gate | Rollback trigger |
|-------|------|----------|-------|-----------|----------|-----------------|
| P0 | | | | | | |
| P1 | | | | | | |
| P2 | | | | | | |

- **Entry gate:** what must be true before this phase begins (e.g., "ADR-012 approved", "Wave 0 CI gates green")
- **Exit gate:** what must be true before declaring this phase complete (must align with validation gates in §5)
- **Rollback trigger:** observable condition that triggers automatic or manual rollback

#### 4.2 Phase detail blocks

For each phase:

- **Objective:** one sentence
- **Code changes:** list of files/modules/services and what changes (new endpoint, schema migration, config key, etc.)
- **Schema changes:** Flyway migration filenames for Java services; SQL statement summary for Go/Python
- **Contract changes:** any protobuf or event schema changes; reference `contracts/` paths; note if additive or breaking
- **Config changes:** `application.yml` keys; Helm `values-*.yaml` keys; environment variable names
- **Dependency on other clusters:** explicit pointer (e.g., "Requires C8-P1 completion — outbox relay durability fix must be deployed first")
- **Feature flag:** which feature flag controls this change in production (must reference `config-feature-flag-service`)
- **Estimated LOC impact:** rough order of magnitude (< 200, 200–1000, 1000–3000, > 3000)

#### 4.3 Data migration considerations

For any phase that touches persistent data:

- Migration type: additive column / table rename / backfill / destructive drop
- Zero-downtime compatibility window (read both old and new, write new only, then drop old)
- Backfill strategy and estimated row count / duration
- Rollback procedure for each data migration step

**Depth expectation:** Every phase must have a complete entry gate and exit gate. Phases must be independently deployable where possible — a phase that requires deploying five services simultaneously is a design smell and must be called out explicitly. Data migration section must exist if any schema change is planned.

**Failure modes:** Phases without entry/exit gates. No rollback triggers. No feature flag reference. Data migrations without a compatibility window plan. Phases that bundle too much (signs of "big bang" deployment).

---

### SECTION 5 — Validation gates

**Purpose:** Define the tests, checks, and signals that confirm a migration phase or the full cluster target state has been reached. Validation gates are the link between the implementation guide and CI/CD enforcement.

**Required sub-sections:**

#### 5.1 Automated test gates

| Gate ID | Type | Target | Pass criterion | CI enforcement |
|---------|------|--------|---------------|----------------|
| VG-<C><N>-01 | Unit | | | Yes/No |
| VG-<C><N>-02 | Integration | | | |
| VG-<C><N>-03 | Contract | | | |
| VG-<C><N>-04 | Smoke | | | |
| VG-<C><N>-05 | Load | | | |

Gate types: `Unit`, `Integration`, `Contract`, `Smoke`, `Load`, `Chaos`, `Compliance`.

#### 5.2 Manual / pre-production verification steps

Numbered checklist of manual steps that must be executed before production promotion for each phase. Each step must include: who executes it, what tool/command, what expected output.

#### 5.3 Canary and progressive delivery criteria

- Canary threshold (e.g., 5% of traffic)
- Soak duration before widening
- Go/no-go metrics (error rate, latency, DLQ count)
- Auto-rollback rule

#### 5.4 Definition of done

A final checklist that consolidates exit criteria. This is copied verbatim into the ADR and the implementation tracking ticket.

**Depth expectation:** The automated test gate table must have at least one Unit, one Integration, and one Smoke entry for every cluster. The manual verification checklist must name a human role, not just a step. Canary criteria must have numeric thresholds — "watch latency" is not acceptable.

**Failure modes:** No canary criteria. Validation only at unit level. Missing definition-of-done checklist. Gates not tied to CI enforcement.

---

### SECTION 6 — Metrics and SLOs

**Purpose:** Define what the cluster measures, what targets it commits to, and how those targets are monitored and alerted. This section bridges implementation and operations.

**Required sub-sections:**

#### 6.1 Service-level indicators (SLIs) table

| SLI ID | Name | Measurement expression | Metric source | Notes |
|--------|------|----------------------|---------------|-------|
| SLI-<C><N>-01 | | | Prometheus / OTEL | |
| SLI-<C><N>-02 | | | | |

Each SLI must reference the actual metric name emitted by the service (e.g., `http_server_requests_seconds_bucket{uri="/checkout"}` for Spring Actuator).

#### 6.2 Service-level objectives (SLOs) table

| SLO ID | SLI ref | Target | Window | Error budget | Alert threshold |
|--------|---------|--------|--------|-------------|-----------------|
| SLO-<C><N>-01 | | 99.9% | 30d | 43.2 min | burn > 5x |

#### 6.3 Error budget policy

- Freeze threshold: at what error budget burn rate do feature deployments freeze?
- Escalation path when budget is exhausted
- Recovery ceremony before deployments resume

#### 6.4 Business-critical metrics (beyond SLOs)

Metrics that leadership cares about that are not SLOs but must be on a dashboard:

| Metric | Definition | Acceptable range | Alert owner |
|--------|-----------|-----------------|-------------|
| | | | |

**Depth expectation:** Every SLI must have an actual metric expression, not a description. SLOs must have numeric targets — "high availability" is not acceptable. Error budget policy must state the freeze threshold explicitly. Business-critical metrics must exist for clusters on the money path (C1, C2) and the logistics loop (C4, C5).

**Failure modes:** SLOs without measurement expressions. Missing error budget policy. No business metrics for money-path clusters.

---

### SECTION 7 — Operating model

**Purpose:** Define how the cluster is operated in production: who is responsible, how incidents are handled, what runbooks exist, and how capacity is managed.

**Required sub-sections:**

#### 7.1 Ownership table

| Role | Name / team | Responsibilities | Escalation contact |
|------|------------|-----------------|-------------------|
| Engineering owner | | Feature delivery, architecture decisions | |
| SRE owner | | Reliability, capacity, incident command | |
| On-call primary | | First-line incident response | |
| On-call secondary | | Escalation within cluster | |
| Schema/contract owner | | Event schema and API contract changes | |
| Data/ML liaison | | Feature store, model rollout impact | |

#### 7.2 Runbook index

| Runbook ID | Scenario | Location | Last tested |
|-----------|---------|---------|------------|
| RB-<C><N>-01 | Service is unhealthy (readiness probe failing) | `docs/runbooks/<cluster>/service-unhealthy.md` | TBD |
| RB-<C><N>-02 | Kafka consumer lag exceeds threshold | | |
| RB-<C><N>-03 | Database connection pool exhausted | | |
| RB-<C><N>-04 | Temporal workflow stuck or timed out | | (C2 only) |
| RB-<C><N>-05 | Feature flag emergency kill switch | | |

Every cluster must have at minimum: service-unhealthy, DLQ/consumer-lag, and database runbooks. Additional cluster-specific runbooks are documented here.

#### 7.3 Capacity model

- Expected peak RPS per service (with source / basis)
- DB connection pool sizing rationale
- Kafka consumer group concurrency
- Redis/cache sizing
- HPA (Horizontal Pod Autoscaler) thresholds and pod min/max
- Cost implication of 2x, 5x, 10x traffic spike

#### 7.4 Dependency map

A prose or table description of:

- Upstream callers (what calls this cluster's services)
- Downstream dependencies (what this cluster calls)
- Kafka topics produced and consumed
- External dependencies (payment gateways, SMS providers, etc.)
- Circuit breaker and timeout posture per dependency

**Depth expectation:** Ownership table must have real names or team names — "TBD" is acceptable only in DRAFT. Runbook index must exist with file paths even if files are not yet written (creates a tracked gap). Capacity model must have numeric peak RPS estimates, even if rough. Dependency map must list Kafka topics by name.

**Failure modes:** No runbook index. Capacity model without numeric estimates. No circuit breaker posture documented for external dependencies.

---

### SECTION 8 — Governance

**Purpose:** Define the rules that govern change to this cluster — who approves what, what ADRs are required, how contract changes are managed, and how the cluster participates in platform-wide review cadences.

**Required sub-sections:**

#### 8.1 Change classification table

| Change class | Examples | Approvers | Process | ADR required? |
|-------------|---------|----------|---------|--------------|
| Local implementation change | refactor, bug fix, new endpoint (same contract) | PR owner + reviewer | Standard PR | No |
| Read-path performance change | index, cache strategy, query tuning | Engineering owner | PR + SRE review | If >20% latency impact |
| Cross-service contract change | new Kafka topic, proto field, REST shape | Engineering owner + Contract owner + consumer signoff | ADR + compatibility review | Yes |
| Money path change | payment logic, idempotency, ledger | Engineering owner + second principal + SRE | Dual review + rollback plan + failure test | Yes |
| Infra / platform change | HPA config, DB sizing, Helm values | SRE owner | Staged rollout + smoke validation | If cross-cluster |
| AI write action | agent tool that mutates production state | Policy owner + HITL gate | Policy approval + audit + kill switch | Yes |

#### 8.2 Required ADRs for this cluster

List of ADRs that must exist (or be created) before the cluster's migration phases can reach APPROVED status:

| ADR ID | Title | Status | Owner |
|--------|-------|--------|-------|
| ADR-<NNN> | | Needed / Draft / Approved | |

#### 8.3 Contract change protocol

Step-by-step protocol for making a breaking or additive change to any contract owned by this cluster (REST API, Kafka topic, protobuf definition, or event schema):

1. Author drafts ADR and notifies all known consumers.
2. Consumers have N working days to signal objection (default: 5).
3. Additive change: dual-write window of M days (default: 30).
4. Breaking change: new `vN` schema file; old version maintained for N days (default: 90).
5. CI schema compatibility gate must pass before merge.
6. Release notes entry required.

#### 8.4 Review cadence

| Cadence | Forum | Participants | Agenda items for this cluster |
|---------|-------|-------------|-------------------------------|
| Weekly | Service ownership review | EM, tech lead | Deployment health, open incidents, error budget |
| Biweekly | Contract review | Principal, contract owners | Schema drift, consumer signoff queue |
| Monthly | Reliability review | SRE, engineering owners | SLO burn, capacity, runbook gaps |
| Monthly | AI governance review | Principal, AI/ML owner | Model rollout, agent action audit | (C9 only) |
| Quarterly | Architecture review board | CTO, principals | Wave progress, ADR backlog, cost |

**Depth expectation:** Change classification table must have all rows filled, including ADR-required column. Required ADRs must be listed even if status is "Needed." Contract change protocol must specify numeric windows (days). Review cadence must identify specific participants by role.

**Failure modes:** No change classification table. Empty required ADRs list. Contract protocol without numeric windows. Review cadence without participant roles.

---

## 3.2 Blank template (copy-paste ready)

> Copy this block to create a new cluster chapter. Replace `<CN>` with the cluster number and `<CLUSTER NAME>` with the display name.

```markdown
# Cluster <CN> — <CLUSTER NAME>

---

## Cluster header

| Field | Value |
|-------|-------|
| Cluster ID | C<N> |
| Cluster name | |
| Services | |
| Iter 2 wave(s) | |
| Risk register items | |
| Engineering owner | TBD |
| SRE owner | TBD |
| ADR references | |
| Last updated | |
| Status | DRAFT |

---

## 1. Issue summary

### 1.1 Critical findings

| Finding ID | Title | Severity | Affected services | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|------------------|----------|----------------------|-----------|

### 1.2 High findings

| Finding ID | Title | Severity | Affected services | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|------------------|----------|----------------------|-----------|

### 1.3 Deferred / watch items

| Finding ID | Title | Severity | Reason deferred | Re-elevation trigger |
|-----------|-------|---------|----------------|---------------------|

---

## 2. Target state

### 2.1 Functional target state

- [ ] <testable correctness requirement 1>
- [ ] <testable correctness requirement 2>
- [ ] <testable correctness requirement 3>
- [ ] <testable correctness requirement 4>
- [ ] <testable correctness requirement 5>

### 2.2 Non-functional target state

| Dimension | Current state | Target | Measurement method |
|-----------|--------------|--------|-------------------|
| p95 latency (hot path) | | | |
| p99 latency (hot path) | | | |
| Error rate (hot path) | | | |
| Availability SLO | | | |
| Idempotency coverage | | | |
| Contract schema coverage | | | |
| Test coverage | | | |
| Observability completeness | | | |

### 2.3 Explicit out-of-scope items

- <item 1 — note which cluster/ADR owns it>

---

## 3. Options analysis

### 3.1 Options table

| Option | Summary | Complexity | Risk | Time estimate | Recommended? |
|--------|---------|-----------|------|---------------|-------------|
| A | | | | | |
| B | | | | | |
| C | | | | | |

### 3.2 Option detail

#### Option A — <name>
- **Changes:** 
- **Preserves:** 
- **Failure modes:** 
- **Competitor precedent:** 
- **Recommendation:** 

#### Option B — <name>
- **Changes:** 
- **Preserves:** 
- **Failure modes:** 
- **Competitor precedent:** 
- **Recommendation:** 

### 3.3 Chosen direction

> **Decision:** Option <X> [or hybrid A+B]. Rationale: …
> **Signed off by:** <engineering owner name>, <date>

---

## 4. Migration path

### 4.1 Migration phases table

| Phase | Name | Duration | Owner | Entry gate | Exit gate | Rollback trigger |
|-------|------|----------|-------|-----------|----------|-----------------|
| P0 | | | | | | |
| P1 | | | | | | |
| P2 | | | | | | |

### 4.2 Phase detail

#### Phase P0 — <name>
- **Objective:** 
- **Code changes:** 
- **Schema changes:** 
- **Contract changes:** 
- **Config changes:** 
- **Dependencies on other clusters:** 
- **Feature flag:** 
- **Estimated LOC impact:** 

#### Phase P1 — <name>
- **Objective:** 
- **Code changes:** 
- **Schema changes:** 
- **Contract changes:** 
- **Config changes:** 
- **Dependencies on other clusters:** 
- **Feature flag:** 
- **Estimated LOC impact:** 

### 4.3 Data migration considerations

- Migration type: 
- Compatibility window: 
- Backfill strategy: 
- Rollback procedure: 

---

## 5. Validation gates

### 5.1 Automated test gates

| Gate ID | Type | Target | Pass criterion | CI enforcement |
|---------|------|--------|---------------|----------------|
| VG-C<N>-01 | Unit | | | |
| VG-C<N>-02 | Integration | | | |
| VG-C<N>-03 | Contract | | | |
| VG-C<N>-04 | Smoke | | | |

### 5.2 Manual / pre-production verification steps

1. **Role:** … **Command:** … **Expected output:** …
2. **Role:** … **Command:** … **Expected output:** …

### 5.3 Canary and progressive delivery criteria

- Canary threshold: <N>% of traffic
- Soak duration: <N> minutes / hours
- Go/no-go metrics: error rate < X%, p99 < Yms, DLQ count = 0
- Auto-rollback rule: 

### 5.4 Definition of done

- [ ] All automated test gates pass in CI
- [ ] Manual verification checklist signed off by SRE owner
- [ ] Canary soak completed with no alert fires
- [ ] SLOs measured and within target
- [ ] Runbooks reviewed and up to date
- [ ] ADR(s) committed and linked
- [ ] Feature flag state documented

---

## 6. Metrics and SLOs

### 6.1 SLIs

| SLI ID | Name | Measurement expression | Metric source | Notes |
|--------|------|----------------------|---------------|-------|
| SLI-C<N>-01 | | | | |
| SLI-C<N>-02 | | | | |

### 6.2 SLOs

| SLO ID | SLI ref | Target | Window | Error budget | Alert threshold |
|--------|---------|--------|--------|-------------|-----------------|
| SLO-C<N>-01 | | | 30d | | |

### 6.3 Error budget policy

- **Freeze threshold:** error budget burn > <X>x for <N> minutes
- **Escalation path:** on-call → SRE owner → engineering owner → CTO
- **Recovery ceremony:** post-incident review + sign-off before deployments resume

### 6.4 Business-critical metrics

| Metric | Definition | Acceptable range | Alert owner |
|--------|-----------|-----------------|-------------|
| | | | |

---

## 7. Operating model

### 7.1 Ownership

| Role | Name / team | Responsibilities | Escalation contact |
|------|------------|-----------------|-------------------|
| Engineering owner | TBD | | |
| SRE owner | TBD | | |
| On-call primary | TBD | | |
| On-call secondary | TBD | | |
| Schema/contract owner | TBD | | |

### 7.2 Runbook index

| Runbook ID | Scenario | Location | Last tested |
|-----------|---------|---------|------------|
| RB-C<N>-01 | Service unhealthy | `docs/runbooks/c<n>/service-unhealthy.md` | — |
| RB-C<N>-02 | Consumer lag | `docs/runbooks/c<n>/consumer-lag.md` | — |
| RB-C<N>-03 | DB pool exhausted | `docs/runbooks/c<n>/db-pool.md` | — |

### 7.3 Capacity model

| Service | Peak RPS (est.) | DB connections | Kafka consumers | Cache (MB) | Pod min/max |
|---------|----------------|---------------|----------------|-----------|------------|
| | | | | | |

### 7.4 Dependency map

**Upstream callers:** 
**Downstream dependencies:** 
**Kafka topics produced:** 
**Kafka topics consumed:** 
**External dependencies:** 
**Circuit breaker posture:** 

---

## 8. Governance

### 8.1 Change classification

| Change class | Examples | Approvers | Process | ADR required? |
|-------------|---------|----------|---------|--------------|
| Local implementation | | | | No |
| Contract change | | | | Yes |
| Money path change | | | | Yes |
| Infra / platform | | | | If cross-cluster |
| AI write action | | | | Yes |

### 8.2 Required ADRs

| ADR ID | Title | Status | Owner |
|--------|-------|--------|-------|
| ADR-<NNN> | | Needed | TBD |

### 8.3 Contract change protocol

1. Author drafts ADR and notifies known consumers.
2. Consumers have **5 working days** to signal objection.
3. Additive change: dual-write window **30 days**.
4. Breaking change: new `vN` schema file; old version maintained **90 days**.
5. CI schema compatibility gate must pass before merge.
6. Release notes entry required.

### 8.4 Review cadence participation

| Cadence | Forum | Cluster agenda items |
|---------|-------|---------------------|
| Weekly | Service ownership review | |
| Biweekly | Contract review | |
| Monthly | Reliability review | |
| Quarterly | Architecture review board | |
```

---

# Part IV — Cluster pre-fills

Each sub-section below seeds the Issue Summary (§1) and key Target State pointers (§2.1) for each cluster, drawn directly from Iter 2 findings. These pre-fills are the starting content for authors — they must be verified against current code before a chapter reaches REVIEW status.

---

## C1 — Edge & Identity

**Services:** `services/identity-service`, `services/mobile-bff-service`, `services/admin-gateway-service`  
**Iter 2 reference sections:** §4.1, §4.2

### Pre-filled critical findings

| Finding ID | Title | Severity | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|---------|----------------------|-----------|
| C1-F1 | Edge routing paths broken — Helm/Istio expose `/api/v1/*` but controllers serve `/*` without rewrite | CRITICAL | `deploy/helm/templates/istio/virtual-service.yaml` (no rewrite rule); controller mappings | All external traffic reaches wrong paths; requests return 404 in production | §4.2 |
| C1-F2 | Shared INTERNAL_SERVICE_TOKEN collapses all callers to one trusted identity | CRITICAL | `identity-service` internal auth filter; all services using `INTERNAL_SERVICE_TOKEN` env var | Any compromised internal service can impersonate any other; no per-service authorization | §4.2 |
| C1-F3 | Rate limiting is pod-local and bypassable via forwarded headers | HIGH | `identity-service` rate limiter configuration; Spring in-memory store | Rate limit bypass under load-balanced traffic; brute-force attacks succeed | §4.2 |
| C1-F4 | Mobile BFF has no real security chain, aggregation, circuit breaking | HIGH | `mobile-bff-service/src/` (scaffold-level implementation) | Edge offers no latency protection, retry normalization, or client decoupling | §4.2 |
| C1-F5 | Admin gateway lacks dedicated JWT/RBAC security configuration | HIGH | `admin-gateway-service/src/` (no dedicated security config found) | Likely falls through to Spring default security behavior; admin surface under-protected | §4.2 |

### Pre-filled target state pointers (§2.1)

- Istio virtual service rewrites are in place and tested; external paths correctly map to controller paths
- Internal service-to-service auth uses workload identity; no single shared token exists
- Rate limiting is centralized and durable (Redis-backed or Envoy-level)
- Mobile BFF implements real aggregation, circuit breaking, and response shaping for at least checkout and catalog flows
- Admin gateway enforces JWT validation and RBAC for all admin operations with dedicated security config

---

## C2 — Transactional Core (Money Path)

**Services:** `services/checkout-orchestrator-service`, `services/order-service`, `services/payment-service`, `services/payment-webhook-service`, `services/reconciliation-engine`  
**Iter 2 reference sections:** §4.5, §4.6

### Pre-filled critical findings

| Finding ID | Title | Severity | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|---------|----------------------|-----------|
| C2-F1 | Checkout logic is duplicated between orchestrator and order-service | CRITICAL | `checkout-orchestrator-service` workflow + `order-service` checkout path (both have checkout authority) | Inconsistent checkout behavior, race conditions; double-apply risk on retries | §4.5 |
| C2-F2 | Payment idempotency key stored in-memory (Caffeine) — lost on pod restart | CRITICAL | `checkout-orchestrator-service` Caffeine idempotency config; `PaymentActivityImpl` | Payment double-charge on pod restart or rolling deploy; no durable deduplication | §4.5, §4.6 |
| C2-F3 | Confirm step (inventory + payment confirmation) has no compensation | CRITICAL | `CheckoutWorkflowImpl.java` — confirm step missing rollback/compensation | Partial confirmation leaves inventory reserved and payment authorized without a cancel path | §4.5 |
| C2-F4 | InventoryReservationResult.reserved field not checked before proceeding | CRITICAL | `CheckoutWorkflowImpl.java` inventory activity call site | Checkout proceeds on failed reservation; orders created with un-reserved stock | §4.5 |
| C2-F5 | Reconciliation engine does not close the loop on capture/void/refund outcomes | HIGH | `reconciliation-engine/` — sweep and reconcile logic; missing payment-state reconciliation | Payment states diverge between gateway and ledger; unresolved holds accumulate | §4.6 |
| C2-F6 | Payment webhook handler has no durable idempotency; webhook replay causes duplicate processing | HIGH | `payment-webhook-service/` — no idempotency table or deduplication gate | Webhook retries from gateway double-credit or double-refund | §4.6 |
| C2-F7 | Partial capture ledger handling is not implemented | HIGH | `payment-service/` — partial capture path; ledger update logic | Multi-item orders with partial fulfillment cannot reconcile payment correctly | §4.6 |

### Pre-filled target state pointers (§2.1)

- Single checkout authority: `checkout-orchestrator-service` owns the saga; `order-service` receives an already-validated order creation command, never drives checkout
- Idempotency keys for all payment operations are persisted to PostgreSQL before the operation is attempted
- Every Temporal compensation step covers every activity that can have a side effect, including confirm
- Reconciliation engine sweeps all terminal and intermediate payment states on a configurable schedule and closes open holds
- Payment webhook handler writes idempotency fingerprint to DB before processing; duplicate deliveries are detected and no-oped

---

## C3 — Read & Decision Plane (Catalog / Search / Cart / Pricing)

**Services:** `services/catalog-service`, `services/search-service`, `services/cart-service`, `services/pricing-service`  
**Iter 2 reference sections:** §4.3, §4.4

### Pre-filled critical findings

| Finding ID | Title | Severity | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|---------|----------------------|-----------|
| C3-F1 | Catalog→search event model has producer/consumer ambiguity — `ProductDeactivated` vs `ProductDelisted` mismatch | HIGH | `contracts/` catalog event schema; `search-service` consumer topic subscription | Deactivated products remain searchable; search index staleness | §4.3 |
| C3-F2 | Search uses Postgres FTS + `ILIKE` autocomplete — no typo tolerance, no multilingual, no semantic readiness | HIGH | `search-service/src/` — search query implementation | Poor conversion for non-English queries, misspellings, and synonym matching | §4.3 |
| C3-F3 | No inventory-aware ranking loop from search results | HIGH | `search-service/` — no availability or stock-level signal in ranking | OOS items appear high in results; frustrating UX for 10-minute delivery promise | §4.3 |
| C3-F4 | Cart uses pod-local Caffeine for idempotency; merge strategy on session restoration is underdefined | HIGH | `cart-service/` — idempotency and merge logic | Cart data loss on pod eviction; unresolved merge conflicts at checkout entry | §4.4 |
| C3-F5 | Pricing service provides no quote/lock token — price can change between browsing and checkout | HIGH | `pricing-service/` — no quote token or TTL-bounded price lock | Users charged different price than displayed; trust-eroding customer experience | §4.4 |

### Pre-filled target state pointers (§2.1)

- Catalog and search share one authoritative event vocabulary: `ProductCreated`, `ProductUpdated`, `ProductDeactivated`, `ProductRestored` — all defined in `contracts/` with v1 JSON schemas
- Search returns zero OOS results for critical SKUs based on real-time inventory signal from `inventory-service`
- Pricing returns a TTL-bounded quote token that checkout validates before payment authorization
- Cart idempotency is durable (Redis or DB-backed); merge strategy on anonymous→authenticated session is explicitly specified and tested

---

## C4 — Inventory & Dark Store

**Services:** `services/inventory-service`, `services/warehouse-service`  
**Iter 2 reference sections:** §4.7

### Pre-filled critical findings

| Finding ID | Title | Severity | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|---------|----------------------|-----------|
| C4-F1 | Inventory reservation model lacks soft-hold / hard-commit distinction | HIGH | `inventory-service/` reservation logic — no two-phase reserve/confirm | Races between concurrent checkouts on same SKU; oversell risk | §4.7 |
| C4-F2 | Warehouse zone and slot model is not dark-store-grade (no pick path, no zone capacity) | HIGH | `warehouse-service/` zone config; pick-list generation logic | Fulfillment SLA broken because warehouse cannot route pickers efficiently | §4.7 |
| C4-F3 | No replenishment signal from inventory to warehouse — reorder point not automated | MEDIUM | No linkage between inventory low-stock events and warehouse/procurement flow | Manual replenishment causes OOS gaps that violate 10-minute delivery promise | §4.7 |

### Pre-filled target state pointers (§2.1)

- Inventory exposes soft-hold (reserve) and hard-commit (confirm) operations; only confirmed units are decremented from available count
- Warehouse supports zone-based pick path routing with capacity constraints per zone and per slot
- Low-stock events are published via outbox when reorder threshold is crossed; downstream consumers (warehouse, procurement) react automatically

---

## C5 — Fulfillment & Logistics

**Services:** `services/fulfillment-service`, `services/rider-fleet-service`, `services/dispatch-optimizer-service`, `services/routing-eta-service`, `services/location-ingestion-service`  
**Iter 2 reference sections:** §4.8

### Pre-filled critical findings

| Finding ID | Title | Severity | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|---------|----------------------|-----------|
| C5-F1 | Dispatch authority is split between fulfillment-service and dispatch-optimizer-service — no single dispatch decision owner | CRITICAL | Both services have dispatch/assignment logic; unclear event contract between them | Duplicate dispatch events, rider double-assignment, and conflicting order state | §4.8 |
| C5-F2 | Rider state has no recovery path — crashed or stuck rider transitions are not handled | CRITICAL | `rider-fleet-service/` state machine — no stuck-state sweeper or timeout handler | Riders stuck in non-terminal states block future assignments indefinitely | §4.8 |
| C5-F3 | Location ingestion contract mismatches ETA contract (`delivery_eta` vs `eta_prediction` field name drift) | HIGH | `location-ingestion-service/` event schema; `routing-eta-service/` contract; `contracts/` | ETA consumers receive null or unmapped fields; customer ETAs are wrong | §4.8 |
| C5-F4 | No breach prediction — ETA is calculated once at dispatch, not updated during delivery | HIGH | `routing-eta-service/` — no in-flight re-optimization or breach probability signal | 10-minute SLA breaches are discovered at delivery, not predicted 3–5 minutes before | §4.8 |
| C5-F5 | Fulfillment/rider event contracts are not closed-loop — pick-to-pack and handoff are not confirmed by counter-event | HIGH | `fulfillment-service/` → `rider-fleet-service/` event flow; no acknowledgment event defined | Order status updates are based on optimistic time estimates, not confirmed transitions | §4.8 |

### Pre-filled target state pointers (§2.1)

- `dispatch-optimizer-service` is the single source of dispatch decisions; `fulfillment-service` consumes assignment events, does not make dispatch choices
- `rider-fleet-service` has a stuck-state sweeper on a configurable timeout; all rider states have explicit timeout-triggered compensation
- `location-ingestion-service` and `routing-eta-service` share one canonical location event schema in `contracts/`; field names match across all consumers
- ETA is continuously recalculated from live location updates; breach probability signal is published when breach risk exceeds configurable threshold
- Every fulfillment transition (pick-start, pack-complete, handoff) is confirmed by a counter-event from the receiving service before order status updates

---

## C6 — Customer & Engagement

**Services:** `services/notification-service`, `services/wallet-loyalty-service`, `services/fraud-detection-service`  
**Iter 2 reference sections:** §4.9, §4.10

### Pre-filled critical findings

| Finding ID | Title | Severity | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|---------|----------------------|-----------|
| C6-F1 | Notification service has no deduplication — same event consumed twice causes duplicate push/SMS | HIGH | `notification-service/` consumer — no idempotency key check before send | Users receive duplicate order confirmations, OTP messages, and delivery updates | §4.9 |
| C6-F2 | Wallet/loyalty has no distributed lock on points credit — concurrent reward events may double-credit | HIGH | `wallet-loyalty-service/` points-credit path; no optimistic or pessimistic lock | Points balance inflated by race condition; financial liability | §4.9 |
| C6-F3 | Fraud detection ML model has no production kill switch or manual override — all fraud decisions are automated | HIGH | `fraud-detection-service/` — no feature-flag-gated override or manual review queue | Fraud model errors block legitimate orders with no human review path | §4.10 |

### Pre-filled target state pointers (§2.1)

- Notification service checks idempotency fingerprint (event_id hash) before every outbound send; duplicate events produce no additional messages
- Wallet credit and debit operations use optimistic locking (version column) or advisory DB lock; no double-credit under concurrent processing
- Fraud service supports three operational modes: `auto-block`, `manual-review`, `pass-through`; mode is controlled by feature flag in `config-feature-flag-service`

---

## C7 — Platform Foundations (Audit / Config / Feature Flags)

**Services:** `services/audit-trail-service`, `services/config-feature-flag-service`  
**Iter 2 reference sections:** §4.10

### Pre-filled critical findings

| Finding ID | Title | Severity | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|---------|----------------------|-----------|
| C7-F1 | Feature flag service has no emergency kill-switch fast path — flag reads go through full HTTP round-trip | HIGH | `config-feature-flag-service/` — no local cache with background refresh | Flag-guarded emergency rollbacks are too slow under incident conditions | §4.10 |
| C7-F2 | Audit trail is append-only but has no tamper-evidence hash chain | MEDIUM | `audit-trail-service/` — no Merkle-style chain or WORM storage | Audit logs can be modified without detection; compliance risk for PCI/SOC2 | §4.10 |
| C7-F3 | No CODEOWNERS file exists — ownership is implicit | HIGH | `.github/CODEOWNERS` — absent | PRs merge without required reviewer; no automated ownership enforcement | §4.1 |

### Pre-filled target state pointers (§2.1)

- Feature flag client uses local cache with configurable TTL and background refresh; emergency kill-switch propagates within 1 second for TTL ≤ 1s flags
- Audit records include a cryptographic chain (SHA-256 of previous record's hash + current payload); chain integrity is verifiable with a CLI tool
- `CODEOWNERS` file exists and is enforced via branch protection rules; every service directory has a named team owner

---

## C8 — Event & Data Plane

**Services:** `services/outbox-relay-service`, `services/cdc-consumer-service`, `services/stream-processor-service`, `contracts/`  
**Go services:** all Go modules in the data and CDC path  
**Iter 2 reference sections:** §4.11

### Pre-filled critical findings

| Finding ID | Title | Severity | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|---------|----------------------|-----------|
| C8-F1 | Kafka topic naming has singular/plural drift — `order.event` vs `order.events` inconsistency across producers and consumers | HIGH | `contracts/README.md` topic list; service producer configs; consumer `@KafkaListener` topic values | Consumers subscribe to wrong topics; events are silently dropped | §4.11 |
| C8-F2 | Event envelope fields are inconsistent across services — `event_id` vs `id`, `schema_version` sometimes absent | HIGH | Multiple service event producers; `contracts/` schemas | Consumers that require envelope fields fail silently; schema validation cannot be enforced | §4.11 |
| C8-F3 | No CI schema compatibility gate — breaking proto or JSON schema changes merge without consumer notification | CRITICAL | `.github/workflows/ci.yml` — no contract compatibility step | Breaking contract changes deploy silently; consumer services fail in production | §4.11 |
| C8-F4 | `stream-processor-service` appears in CI but has no Helm deployment key | HIGH | `.github/workflows/ci.yml` matrix; `deploy/helm/values-dev.yaml` | Stream processor cannot be deployed via GitOps; deployment is manual or absent | §4.1, §4.11 |
| C8-F5 | outbox-relay-service has no dead-letter queue handling for relay failures | HIGH | `outbox-relay-service/` — relay error path; no DLQ configuration | Relay failures silently drop events; downstream consumers miss domain events | §4.11 |

### Pre-filled target state pointers (§2.1)

- All Kafka topics follow `<domain>.<aggregate>.<event-type>` naming convention; canonical list is in `contracts/README.md` and is CI-enforced
- All events include the standard envelope (`event_id`, `event_type`, `aggregate_id`, `schema_version`, `source_service`, `correlation_id`, `timestamp`); envelope is validated by a shared library before publish
- CI runs `buf breaking` for proto changes and a JSON schema compatibility check for event schemas before merge
- `stream-processor-service` has a Helm values key and ArgoCD app entry; deploys via GitOps like all other services
- Outbox relay has a DLQ for failed relay attempts; DLQ depth is an alert metric

---

## C9 — AI/ML Platform

**Services:** `services/ai-orchestrator-service`, `services/ai-inference-service`  
**Directories:** `data-platform/`, `ml/`  
**Iter 2 reference sections:** §4.12, §4.13

### Pre-filled critical findings

| Finding ID | Title | Severity | Evidence | Production consequence | Iter 2 ref |
|-----------|-------|---------|---------|----------------------|-----------|
| C9-F1 | AI orchestrator runtime is thinner than documentation claims — LangGraph and guardrail descriptions are not fully wired | HIGH | `ai-orchestrator-service/app/` — actual implemented agents vs documented agent set | Agent capabilities promised in docs are not available; reliability audit misleading | §4.13 |
| C9-F2 | No ML model promotion gate — models can be promoted to production without parity check against champion | CRITICAL | `ml/` — model promotion scripts; no champion/challenger comparison gate | Degraded models reach production without detection; fraud/search/ETA quality regresses silently | §4.12 |
| C9-F3 | Feature path name drift — `delivery_eta` vs `eta_prediction` — between feature store and model training configs | HIGH | `ml/train/*/config.yaml`; `data-platform/` feature definitions | Models train on wrong features; prediction quality degrades in ways that are hard to trace | §4.12 |
| C9-F4 | No AI write-action kill switch or human-in-the-loop gate for any operational mutation | CRITICAL | `ai-orchestrator-service/` tool definitions; no tool access control or HITL flow | Autonomous agent mutations reach production systems with no reversal path | §4.13 |
| C9-F5 | No model rollback mechanism — once a model is deployed, reverting requires manual infra intervention | HIGH | `ml/` serving config; no versioned rollback script | A degraded model cannot be rolled back during an incident; mean time to recovery is unacceptably long | §4.12 |

### Pre-filled target state pointers (§2.1)

- AI orchestrator's documented agent capabilities are implemented and verified by integration tests; any undocumented capability gap is tracked as a named finding
- Model promotion requires champion/challenger parity check: new model must not degrade primary metric by more than X% before promotion is allowed
- Feature store canonical names are defined in `data-platform/` and referenced by all model training configs; drift is detected by CI
- All agent tool calls that mutate production state (payment, inventory, order, dispatch) go through a policy decision point with HITL or kill-switch capability
- Model rollback is automated: `ml/scripts/rollback.sh <model_id>` redeploys the previous champion version within 5 minutes

---

# Part V — Cross-cluster governance map

## 5.1 Cluster interaction matrix

The following matrix identifies which clusters must coordinate for each class of cross-cutting change. A ● indicates the cluster is a required participant; ○ indicates an optional/informational participant.

| Change type | C1 Edge | C2 Money | C3 Read | C4 Inventory | C5 Logistics | C6 Engagement | C7 Platform | C8 Events | C9 AI/ML |
|-------------|---------|---------|--------|-------------|-------------|--------------|------------|----------|---------|
| New Kafka topic | ○ | ○ | ○ | ○ | ○ | ○ | ● | ● | ○ |
| Event schema change (breaking) | ○ | ● | ● | ● | ● | ● | ● | ● | ● |
| Payment flow change | ● | ● | ● | ● | ○ | ○ | ● | ● | ○ |
| Order state transition change | ○ | ● | ○ | ● | ● | ● | ○ | ● | ○ |
| Feature flag rollout | ● | ○ | ○ | ○ | ○ | ○ | ● | ○ | ○ |
| ML model promotion | ○ | ● | ● | ● | ● | ● | ● | ● | ● |
| AI write action addition | ● | ● | ○ | ● | ● | ○ | ● | ○ | ● |
| Infra/Helm change (cross-service) | ● | ● | ● | ● | ● | ● | ● | ● | ● |

## 5.2 Cross-cluster ADR dependency map

These ADRs span multiple clusters and must be drafted before any affected cluster reaches APPROVED status:

| ADR | Title | Affects | Priority |
|-----|-------|---------|---------|
| ADR-001 | Checkout authority — single orchestrator pattern | C2, C3, C4 | P0 |
| ADR-002 | Internal service auth — workload identity model | C1, all clusters | P0 |
| ADR-003 | Kafka topic naming convention | C8, all event producers | P0 |
| ADR-004 | Event envelope standard and validation library | C8, all event producers | P0 |
| ADR-005 | Idempotency key durability standard | C2, C3, C6 | P1 |
| ADR-006 | Dispatch authority — single decision owner | C5 | P1 |
| ADR-007 | ML model promotion gate standard | C9 | P1 |
| ADR-008 | AI write-action risk tiers and kill-switch protocol | C9, C2, C4, C5 | P1 |
| ADR-009 | SLO definition and error budget policy | All clusters | P1 |
| ADR-010 | Feature flag fast-path and TTL policy | C7, all clusters | P2 |
| ADR-011 | Contract versioning and compatibility window | C8, all contract owners | P2 |
| ADR-012 | Cell-based deployment topology | All clusters, infra | P3 |

## 5.3 Wave-to-cluster delivery sequence

This sequence captures the intended delivery order. Clusters within a wave may proceed in parallel unless a cross-cluster ADR dependency applies.

```
Wave 0 (truth restoration)
  └── C7: CODEOWNERS, ADR process, CI coverage gaps
  └── C8: Topic naming, envelope standard, CI schema gate
  └── C1: Edge route fix (Helm/Istio rewrite)

Wave 1 (money path hardening)
  └── C2: Checkout authority, idempotency durability, compensation completeness
  └── C2: Webhook idempotency, reconciliation closure
  └── C1: Workload identity (prerequisite: ADR-002)

Wave 2 (dark-store operating loop)
  └── C4: Inventory soft-hold/hard-commit, warehouse zone model
  └── C5: Dispatch authority, rider state recovery, ETA re-optimization
  └── C5: Location/ETA contract alignment (prerequisite: C8 envelope standard)

Wave 3 (read and decision plane)
  └── C3: Catalog→search event model fix
  └── C3: Pricing quote token
  └── C3: Cart idempotency durability
  └── C6: Notification deduplication, wallet locking, fraud override

Wave 4 (event, data, and ML governance)
  └── C8: Outbox DLQ, stream-processor deploy alignment
  └── C9: ML promotion gate, feature path de-drift, model rollback
  └── C9: AI write-action kill switch (prerequisite: ADR-008)

Wave 5 (cells, SLOs, platform governance)
  └── All: SLO instrumentation, error budget alerts
  └── C7: Audit hash chain, feature flag fast path
  └── Infra: Cell-based topology (prerequisite: ADR-012)

Wave 6 (governed AI rollout)
  └── C9: HITL pipeline, full agent capability verification
  └── C1: Per-service authorization at edge
```

---

# Part VI — Operating cadences

## 6.1 Cluster chapter review cadence

Once cluster chapters are drafted, they follow this review cycle:

| Step | Activity | Who | Timing |
|------|---------|-----|--------|
| 1 | Author drafts chapter using template | Cluster engineering owner | Before wave start |
| 2 | Internal cluster review | Tech lead + SRE owner | Within 3 working days of draft |
| 3 | Cross-cluster ADR check | Principal engineer | Before REVIEW status |
| 4 | Second principal review | Reviewer from another cluster | Within 5 working days |
| 5 | Chapter reaches APPROVED | Engineering owner signs | Before implementation begins |
| 6 | Chapter updated with implementation notes | Author, ongoing | During and after each phase |
| 7 | Chapter archived post-implementation | Author + principal | After cluster target state verified |

## 6.2 Ongoing platform review cadences

These cadences govern the platform during and after the implementation program:

| Forum | Frequency | Mandatory attendees | Agenda |
|-------|-----------|--------------------|----|
| Service ownership review | Weekly | All EMs, tech leads | Deployment health, open incidents, error budget status per cluster |
| Contract review | Biweekly | Principals, all contract owners | Schema drift, consumer signoff queue, ADR backlog |
| Reliability review | Monthly | SRE owners, engineering owners | SLO burn rates, capacity model review, runbook coverage |
| AI governance review | Monthly | Principal, AI/ML owner, SRE | Model rollout, agent action audit, cost/latency parity |
| Architecture review board | Quarterly | CTO, all principals | Wave progress, ADR completions, topology health, cost |

## 6.3 Iteration gate criteria

Before the engineering program advances from one wave to the next, the following gates must be satisfied:

| Wave transition | Gate |
|----------------|------|
| Wave 0 → Wave 1 | CODEOWNERS committed; CI schema gate active; edge route fix deployed and smoke-tested; ADR process documented |
| Wave 1 → Wave 2 | Checkout has single owner (ADR-001 approved and implemented); payment idempotency durable; reconciliation closure verified; no open CRITICAL findings in C2 |
| Wave 2 → Wave 3 | Dispatch authority resolved (ADR-006 approved); rider stuck-state sweeper live; location/ETA contracts aligned; no open CRITICAL findings in C4/C5 |
| Wave 3 → Wave 4 | Catalog→search event contract canonicalized; pricing quote token live; cart idempotency durable; no open CRITICAL findings in C3 |
| Wave 4 → Wave 5 | Outbox DLQ live; stream processor deployable via GitOps; ML promotion gate enforced; feature path drift resolved; no open CRITICAL findings in C8/C9 |
| Wave 5 → Wave 6 | SLOs instrumented and alarmed for all clusters; error budget dashboards live; cell topology ADR approved |
| Wave 6 complete | HITL gate live for all AI write actions; model rollback verified; agent audit trail immutable; no undocumented agent capabilities |

---

## Document metadata

**Version:** 1.0 (Iteration 3 — initial outline)  
**Status:** APPROVED — ready for cluster chapter authoring  
**Next action:** Cluster engineering owners instantiate the blank template (§3.2) for their assigned cluster and seed with pre-fills from §4. First cluster chapter target: C2 (Transactional Core) — highest risk, Wave 1 urgency.  
**Tracking:** `iter3-service-guide-outline` todo — see implementation tracking database  
**Supersedes:** Nothing (this is the first Iter 3 document; Iter 2 review remains authoritative for findings)  
**Referenced by:** `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-2-2026-03-06.md` (wave plan), `docs/README.md` (document inventory)
