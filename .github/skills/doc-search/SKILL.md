---
name: doc-search
description: Fast, token-efficient documentation indexing and search across ADRs, architecture diagrams, runbooks, and service guides.
---

# Doc Search Skill: Authoritative Documentation Index

Use this skill when researching architectural decisions, finding data flow specifications, locating runbooks, or discovering API contracts without scanning dozens of markdown files.

---

## 1. Documentation Authority Hierarchy

To prevent reading stale or redundant wave reports, follow the authority hierarchy:

```
  Tier 1: Canonical Truth
  ├── GLOSSARY.md                  (Ubiquitous language & service registry)
  ├── AGENTS.md                    (Staff-level instructions & commands)
  ├── docs/adr/*.md                (15 binding architectural decisions)
  └── contracts/README.md          (Canonical event schemas & topic definitions)
  
  Tier 2: System Architecture
  ├── docs/architecture/HLD.md      (High-Level Design & system topology)
  ├── docs/architecture/LLD.md      (Low-Level Design & data models)
  ├── docs/architecture/DATA-FLOW.md(Cross-service data flows & CDC)
  └── services/*/README.md         (Per-service architecture, ports, APIs)
  
  Tier 3: Operations & Runbooks
  ├── docs/observability/          (Grafana dashboards & Prometheus alerts)
  ├── docs/slos/                   (Service level objectives & error budgets)
  └── docs/runbooks/               (Incident response & onboarding runbooks)
  
  Tier 4: Historic Waves (Consult ONLY for historical context)
  └── WAVE_*.md, docs/wave-*       (Historical delivery reports and milestones)
```

---

## 2. ADR Quick Index (`docs/adr/`)

Before proposing architectural patterns, verify existing Architecture Decision Records:

| ADR | Topic | Binding Decision |
|---|---|---|
| **001** | Checkout Authority | `checkout-orchestrator-service` owns checkout saga; transactional state stays in order/inventory/payment. |
| **002** | Dispatch Authority | `dispatch-optimizer-service` owns rider assignment calculations; `fulfillment-service` executes assignments. |
| **003** | Kafka DLT Naming | Dead-letter topics must use `.DLT` suffix (e.g. `orders.events.DLT`), NOT `.dlq`. |
| **004** | Event Envelope | Standard envelope fields: `eventId`, `eventType`, `aggregate_id`, `event_time`, `correlation_id`, `payload`. |
| **005** | Durable Idempotency | Enforce `X-Idempotency-Key` header with Redis / PostgreSQL unique constraints. |
| **006** | Service Auth | Internal service-to-service communication uses scoped JWT tokens with short TTL. |
| **007** | Python CI Standard | Python AI services must pass pytest and lint in GitHub Actions. |
| **008** | Dispatch Optimizer | Integrates via async Kafka topics and low-latency HTTP RPC. |
| **009** | AI Service Auth | Dedicated bearer token authentication for `ai-*` services. |
| **011** | Admin Gateway Auth | Admin gateway uses RBAC claim verification with audit logging. |
| **013** | Feature Flags | Multi-tier cache invalidation for feature flags via Redis Pub/Sub. |
| **014** | Reconciliation | `reconciliation-engine` has final financial authority over ledger discrepancies. |
| **015** | SLO Error Budget | Automated alerting and freeze policy when error budget burns > 10% in 1 hour. |

---

## 3. High-Leverage Doc Search Recipes

```bash
# Search ADRs for a specific decision
git grep -n "idempotency" docs/adr/

# Find service architecture and endpoints
head -n 60 services/<service-name>/README.md

# Search cross-service data flows
git grep -n "PaymentAuthorized" docs/architecture/

# Check observability and SLO definitions
git grep -n "latency" docs/observability/ docs/slos/
```

