# InstaCommerce Documentation Archive

> **Status:** ARCHIVED / HISTORICAL REFERENCE  
> **Retention Policy:** Permanent preservation for architectural context, compliance, and design genealogy.  
> **Active Documentation:** [`docs/README.md`](../README.md) · [`GLOSSARY.md`](../../GLOSSARY.md) · [`contracts/README.md`](../../contracts/README.md) · [`docs/repo-truth/README.md`](../repo-truth/README.md)

---

## 1. Overview & Archival Policy

This directory houses historical, superseded, and snapshot documentation from earlier development waves and architectural review cycles of the InstaCommerce monorepo.

To maintain repository cleanliness while preserving architectural lineage and institutional knowledge:
1. **Never delete** substantive design documents or historical audit trails.
2. **Move superseded documents** into this `docs/archive/` tree under appropriate subdirectories.
3. **Record mappings** from archived documents to their current canonical replacements in this index.
4. **Preserve links and allowlists** (e.g. CI scanners and security scanners in `.gitleaks.toml`).

---

## 2. Archive Inventory

```
docs/archive/
├── README.md               # This index and governance guide
├── legacy_specs/           # Phase 0/1 foundation specifications (formerly old_docs/)
│   ├── 01-identity-access.md
│   ├── 02-catalog-search-pricing.md
│   ├── 03-inventory-cart.md
│   ├── 04-order-temporal-saga.md
│   ├── 05-payment-refunds.md
│   ├── 06-fulfillment-delivery.md
│   ├── 07-notifications-templates.md
│   ├── 08-platform-observability-ci-cd.md
│   ├── 09-contracts-events-protobuf.md
│   ├── 10-security-compliance-guidelines.md
│   ├── CONSOLIDATED-REVIEW.md
│   ├── INFRASTRUCTURE_AUDIT_REPORT.md
│   ├── MICROSERVICE-REFACTOR-PLAN.md
│   ├── REVIEW-REPORT.md
│   ├── REVIEW-fulfillment-notification.md
│   ├── REVIEW_FINDINGS.md
│   ├── SECURITY-AUDIT-REPORT.md
│   ├── code-review-order-payment.md
│   └── contracts-review-findings.md
├── migrations/             # Platform upgrade specifications (Spring Boot 4, etc.)
│   ├── SPRING_BOOT_4_MIGRATION.md
│   └── SPRING_BOOT_4_UPGRADE_PLAN.md
├── reviews/
│   └── iter3_backup/       # Wave 30 snapshot of Iteration 3 review suite
│       ├── README.md
│       ├── WAVE-30-IMPLEMENTATION.md
│       ├── appendices/
│       ├── benchmarks/
│       ├── diagrams/
│       ├── implementation-program.md
│       ├── master-review.md
│       ├── platform/
│       ├── platform-wise-guide.md
│       ├── service-wise-guide.md
│       └── services/
├── gemini/                 # Early system documentation portal & service architecture guides
│   ├── README.md
│   ├── architecture-overview.md
│   ├── checkout-saga.md
│   ├── data-flow-and-events.md
│   ├── database-schema-guide.md
│   ├── deployment-guide.md
│   ├── development-guide.md
│   ├── go-services-architecture.md
│   ├── ml-and-ai-platform.md
│   ├── monitoring-and-observability.md
│   ├── order-lifecycle.md
│   ├── payment-system.md
│   ├── security-architecture.md
│   └── service-catalog.md
└── waves/                  # Completed delivery manifests, plans, and wave runbooks
    ├── wave-34/            # Per-service token scoping & secret mounting
    │   ├── HELM_VALUES_UPDATE_GUIDE.md
    │   ├── WAVE34_FILE_MANIFEST.md
    │   └── WAVE34_TRACK_B_SUMMARY.md
    ├── wave-36/            # CDC wiring & reconciliation engine
    │   └── WAVE_36_TRACK_B_SUMMARY.md
    ├── wave-39/            # Money Path & Tier 1-5 diagrams delivery & verification
    │   ├── DELIVERY_SUMMARY_MONEY_PATH.md
    │   ├── DELIVERY_SUMMARY_SERVICES.md
    │   ├── PRODUCTION_MERGE_CHECKLIST.md
    │   ├── TIER1_DIAGRAMS_DELIVERY.md
    │   ├── TIER1_FINAL_DELIVERY_REPORT.md
    │   ├── WAVE39_COVERAGE_VERIFICATION.md
    │   ├── WAVE39_DEPLOYMENT.md
    │   ├── WAVE39_DIAGRAMS_DELIVERY.md
    │   ├── wave-39-completion.md
    │   └── wave-39-final-delivery.md
    ├── wave-40/            # Dark-store pilot, SLO observability, governance, reverse ETL, audit
    │   ├── IMPLEMENTATION_PLAN.md
    │   ├── INDEX.md
    │   ├── QUICK_REFERENCE.md
    │   ├── REVERSE_ETL_IMPLEMENTATION_PLAN.md
    │   ├── SLO-MONITORING-TRAINING-GUIDE.md
    │   ├── TASK_TRACKER.md
    │   ├── WAVE_40_EXECUTION_MASTER_PLAN.md
    │   ├── WAVE_40_FINAL_DELIVERY_PACKAGE.md
    │   ├── WAVE_40_INFRASTRUCTURE.md
    │   ├── WAVE_40_PHASE2_DARKSTORE_COMPREHENSIVE.md
    │   ├── WAVE_40_PHASE2_DEPLOYMENT_REPORT.md
    │   ├── WAVE_40_PHASE2_INFRASTRUCTURE_ARCHITECTURE.md
    │   ├── WAVE_40_PHASE2_QUICK_DEPLOYMENT_CHECKLIST.md
    │   ├── WAVE_40_PHASE3_OBSERVABILITY_COMPREHENSIVE.md
    │   ├── WAVE_40_PHASE4_GOVERNANCE_COMPREHENSIVE.md
    │   ├── WAVE_40_PHASE5_DATA_MESH_COMPREHENSIVE.md
    │   ├── WAVE_40_PHASE6_AUDIT_TRAIL_COMPREHENSIVE.md
    │   ├── WAVE_40_ROADMAP.md
    │   ├── WAVE_40_SESSION4_REPORT.md
    │   └── WAVE_40_SESSION_COMPLETION_SUMMARY.md
    └── wave-41/            # Spring Boot 4 test suite migration plan
        └── WAVE_41_TEST_MIGRATION_PLAN.md
```

---

## 3. Migration & Canonical Mapping

| Archived Document Path | Subject / Topic | Canonical Active Replacement |
|---|---|---|
| `legacy_specs/01-identity-access.md` | Edge authentication, JWT, RBAC | [`services/identity-service/README.md`](../../services/identity-service/README.md), [`docs/reviews/iter3/diagrams/lld/identity-edge-auth.md`](../reviews/iter3/diagrams/lld/identity-edge-auth.md) |
| `legacy_specs/02-catalog-search-pricing.md` | Catalog data model & pricing engine | [`services/catalog-service/README.md`](../../services/catalog-service/README.md), [`services/pricing-service/README.md`](../../services/pricing-service/README.md), [`services/search-service/README.md`](../../services/search-service/README.md) |
| `legacy_specs/03-inventory-cart.md` | Dark-store stock reservations & cart state | [`services/inventory-service/README.md`](../../services/inventory-service/README.md), [`services/cart-service/README.md`](../../services/cart-service/README.md) |
| `legacy_specs/04-order-temporal-saga.md` | Temporal order checkout saga & state machine | [`services/order-service/README.md`](../../services/order-service/README.md), [`services/checkout-orchestrator-service/README.md`](../../services/checkout-orchestrator-service/README.md) |
| `legacy_specs/05-payment-refunds.md` | Payment webhooks, PSP adapters, refunds | [`services/payment-service/README.md`](../../services/payment-service/README.md), [`services/payment-webhook-service/README.md`](../../services/payment-webhook-service/README.md) |
| `legacy_specs/06-fulfillment-delivery.md` | Wave picking, dispatch, rider routing | [`services/fulfillment-service/README.md`](../../services/fulfillment-service/README.md), [`services/rider-fleet-service/README.md`](../../services/rider-fleet-service/README.md), [`services/routing-eta-service/README.md`](../../services/routing-eta-service/README.md) |
| `legacy_specs/07-notifications-templates.md` | Multi-channel notifications & templates | [`services/notification-service/README.md`](../../services/notification-service/README.md) |
| `legacy_specs/08-platform-observability-ci-cd.md` | OTEL tracing, Prometheus SLOs, CI pipelines | [`docs/observability/`](../observability/), [`deploy/helm/`](../../deploy/helm/), [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) |
| `legacy_specs/09-contracts-events-protobuf.md` | Early protobuf & outbox specifications | [`contracts/README.md`](../../contracts/README.md), [`contracts/src/main/resources/schemas/`](../../contracts/src/main/resources/schemas/) |
| `legacy_specs/10-security-compliance-guidelines.md` | Initial security baseline & secret handling | [`docs/reviews/iter3/platform/security-trust-boundaries.md`](../reviews/iter3/platform/security-trust-boundaries.md) |
| `legacy_specs/CONSOLIDATED-REVIEW.md` | Inception architectural audit | [`docs/reviews/iter3/master-review.md`](../reviews/iter3/master-review.md) |
| `legacy_specs/SECURITY-AUDIT-REPORT.md` | Phase 0 security audit | [`docs/reviews/iter3/platform/security-trust-boundaries.md`](../reviews/iter3/platform/security-trust-boundaries.md) |
| `reviews/iter3_backup/` | Pre-Wave-30 Iteration 3 review snapshot | [`docs/reviews/iter3/`](../reviews/iter3/) (canonical active suite) |
| `waves/wave-34/HELM_VALUES_UPDATE_GUIDE.md` | Wave 34 Track B: Token injection in Helm values | [`deploy/helm/values.yaml`](../../deploy/helm/values.yaml), [`docs/adr/010-per-service-token-rollout.md`](../adr/010-per-service-token-rollout.md), [`docs/adr/012-per-service-token-scoping.md`](../adr/012-per-service-token-scoping.md) |
| `waves/wave-34/WAVE34_FILE_MANIFEST.md` | Wave 34 Track B: File manifest for token scoping | [`docs/adr/012-per-service-token-scoping.md`](../adr/012-per-service-token-scoping.md), [`deploy/helm/values.yaml`](../../deploy/helm/values.yaml) |
| `waves/wave-34/WAVE34_TRACK_B_SUMMARY.md` | Wave 34 Track B: Implementation summary for token scoping | [`docs/adr/010-per-service-token-rollout.md`](../adr/010-per-service-token-rollout.md), [`docs/adr/012-per-service-token-scoping.md`](../adr/012-per-service-token-scoping.md) |
| `waves/wave-36/WAVE_36_TRACK_B_SUMMARY.md` | Wave 36 Track B: CDC wiring for reconciliation engine | [`services/reconciliation-engine/README.md`](../../services/reconciliation-engine/README.md), [`docs/adr/014-reconciliation-authority.md`](../adr/014-reconciliation-authority.md) |
| `waves/wave-39/PRODUCTION_MERGE_CHECKLIST.md` | Wave 39: Final delivery production checklist | [`docs/reviews/iter3/appendices/validation-rollout-playbooks.md`](../reviews/iter3/appendices/validation-rollout-playbooks.md), [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) |
| `waves/wave-39/WAVE39_DIAGRAMS_DELIVERY.md` | Wave 39: Read path diagrams delivery manifest | [`docs/services/DIAGRAMS_INDEX.md`](../services/DIAGRAMS_INDEX.md), [`docs/services/INDEX.md`](../services/INDEX.md) |
| `waves/wave-39/TIER1_DIAGRAMS_DELIVERY.md` | Wave 39: Tier 1 Money Path diagrams executive summary | [`docs/services/INDEX.md`](../services/INDEX.md), [`docs/architecture/LLD.md`](../architecture/LLD.md) |
| `waves/wave-39/TIER1_FINAL_DELIVERY_REPORT.md` | Wave 39: Tier 1 Money Path diagrams final report | [`docs/services/INDEX.md`](../services/INDEX.md), [`docs/architecture/LLD.md`](../architecture/LLD.md) |
| `waves/wave-39/DELIVERY_SUMMARY_MONEY_PATH.md` | Wave 39: Money-path documentation delivery summary | [`docs/services/INDEX.md`](../services/INDEX.md), [`docs/services/checkout-orchestrator-service/`](../services/checkout-orchestrator-service/) |
| `waves/wave-39/DELIVERY_SUMMARY_SERVICES.md` | Wave 39: Cluster 1 Identity & Auth delivery summary | [`docs/services/identity-cluster/`](../services/identity-cluster/), [`docs/services/INDEX.md`](../services/INDEX.md) |
| `waves/wave-39/WAVE39_COVERAGE_VERIFICATION.md` | Wave 39: Diagram coverage verification report | [`docs/services/DIAGRAMS_INDEX.md`](../services/DIAGRAMS_INDEX.md) |
| `waves/wave-39/WAVE39_DEPLOYMENT.md` | Wave 39: Production deployment runbook | [`docs/runbooks/`](../runbooks/), [`deploy/helm/README.md`](../../deploy/helm/README.md) |
| `waves/wave-39/wave-39-completion.md` | Wave 39: Diagrams & build hardening completion | [`docs/services/INDEX.md`](../services/INDEX.md), [`docs/services/DIAGRAMS_INDEX.md`](../services/DIAGRAMS_INDEX.md) |
| `waves/wave-39/wave-39-final-delivery.md` | Wave 39: Production delivery sign-off | [`docs/services/INDEX.md`](../services/INDEX.md) |
| `waves/wave-40/WAVE_40_EXECUTION_MASTER_PLAN.md` | Wave 40: Production execution master plan | [`docs/reviews/iter3/implementation-program.md`](../reviews/iter3/implementation-program.md) |
| `waves/wave-40/WAVE_40_FINAL_DELIVERY_PACKAGE.md` | Wave 40: Phase 2–6 delivery package signoff | [`docs/README.md`](../README.md) |
| `waves/wave-40/WAVE_40_INFRASTRUCTURE.md` | Wave 40: Phase 2–6 infrastructure delivery guide | [`docs/architecture/INFRASTRUCTURE.md`](../architecture/INFRASTRUCTURE.md), [`infra/terraform/README.md`](../../infra/terraform/README.md) |
| `waves/wave-40/WAVE_40_PHASE2_DARKSTORE_COMPREHENSIVE.md` | Wave 40 Phase 2: Dark-store pilot 3-city deployment | [`docs/runbooks/dark-store-pilot-deployment.md`](../runbooks/dark-store-pilot-deployment.md), [`services/warehouse-service/README.md`](../../services/warehouse-service/README.md) |
| `waves/wave-40/WAVE_40_PHASE2_DEPLOYMENT_REPORT.md` | Wave 40 Phase 2: SF canary deployment report | [`docs/runbooks/phase-2-sf-canary-deployment.md`](../runbooks/phase-2-sf-canary-deployment.md), [`docs/infrastructure/sf-canary-architecture.md`](../infrastructure/sf-canary-architecture.md) |
| `waves/wave-40/WAVE_40_PHASE2_INFRASTRUCTURE_ARCHITECTURE.md` | Wave 40 Phase 2: SF canary infrastructure architecture | [`docs/infrastructure/sf-canary-architecture.md`](../infrastructure/sf-canary-architecture.md) |
| `waves/wave-40/WAVE_40_PHASE2_QUICK_DEPLOYMENT_CHECKLIST.md` | Wave 40 Phase 2: SF canary deployment checklist | [`docs/runbooks/phase-2-sf-canary-deployment.md`](../runbooks/phase-2-sf-canary-deployment.md) |
| `waves/wave-40/WAVE_40_PHASE3_OBSERVABILITY_COMPREHENSIVE.md` | Wave 40 Phase 3: Grafana SLO observability guide | [`docs/observability/slo-alerts.md`](../observability/slo-alerts.md), [`docs/slos/service-slos.md`](../slos/service-slos.md), [`monitoring/README.md`](../../monitoring/README.md) |
| `waves/wave-40/SLO-MONITORING-TRAINING-GUIDE.md` | Wave 40 Phase 3: SLO monitoring & error budget training | [`docs/observability/slo-alerts.md`](../observability/slo-alerts.md), [`docs/adr/015-slo-error-budget-policy.md`](../adr/015-slo-error-budget-policy.md) |
| `waves/wave-40/WAVE_40_PHASE4_GOVERNANCE_COMPREHENSIVE.md` | Wave 40 Phase 4: Governance activation guide | [`docs/governance/OWNERSHIP_MODEL.md`](../governance/OWNERSHIP_MODEL.md), [`docs/governance/ESCALATION_POLICY.md`](../governance/ESCALATION_POLICY.md), [`docs/governance/README.md`](../governance/README.md) |
| `waves/wave-40/WAVE_40_PHASE5_DATA_MESH_COMPREHENSIVE.md` | Wave 40 Phase 5: Data mesh reverse ETL guide | [`services/reverse-etl-orchestrator/README.md`](../../services/reverse-etl-orchestrator/README.md), [`docs/reverse-etl/`](../reverse-etl/), [`data-platform/README.md`](../../data-platform/README.md) |
| `waves/wave-40/REVERSE_ETL_IMPLEMENTATION_PLAN.md` | Wave 40 Phase 5: Reverse ETL implementation plan | [`services/reverse-etl-orchestrator/README.md`](../../services/reverse-etl-orchestrator/README.md), [`docs/reverse-etl/`](../reverse-etl/) |
| `waves/wave-40/WAVE_40_PHASE6_AUDIT_TRAIL_COMPREHENSIVE.md` | Wave 40 Phase 6: PCI DSS 7-year audit trail guide | [`services/audit-trail-service/README.md`](../../services/audit-trail-service/README.md), [`docs/reviews/audit-trail-service-review.md`](../reviews/audit-trail-service-review.md) |
| `waves/wave-40/WAVE_40_ROADMAP.md` | Wave 40: Platform stabilization roadmap | [`docs/reviews/iter3/implementation-program.md`](../reviews/iter3/implementation-program.md) |
| `waves/wave-40/WAVE_40_SESSION_COMPLETION_SUMMARY.md` | Wave 40: Planning package completion summary | [`docs/README.md`](../README.md) |
| `waves/wave-40/WAVE_40_SESSION4_REPORT.md` | Wave 40: Phase 1 service documentation session 4 | [`docs/services/INDEX.md`](../services/INDEX.md) |
| `waves/wave-40/IMPLEMENTATION_PLAN.md` | Wave 40: Stabilization & tracing implementation plan | [`docs/reviews/iter3/implementation-program.md`](../reviews/iter3/implementation-program.md) |
| `waves/wave-40/INDEX.md` | Wave 40: Planning index & quick navigation | [`docs/archive/waves/wave-40/IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) |
| `waves/wave-40/QUICK_REFERENCE.md` | Wave 40: 1-page priority summary | [`docs/archive/waves/wave-40/IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) |
| `waves/wave-40/TASK_TRACKER.md` | Wave 40: Weekly task tracking template | [`docs/archive/waves/wave-40/IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) |
| `waves/wave-41/WAVE_41_TEST_MIGRATION_PLAN.md` | Wave 41: Spring Boot 4 test suite modernization | [`build.gradle.kts`](../../build.gradle.kts), [`settings.gradle.kts`](../../settings.gradle.kts) |
| `migrations/SPRING_BOOT_4_MIGRATION.md` | Spring Boot 4: Service migration & Jakarta EE baseline | [`build.gradle.kts`](../../build.gradle.kts), [`settings.gradle.kts`](../../settings.gradle.kts) |
| `migrations/SPRING_BOOT_4_UPGRADE_PLAN.md` | Spring Boot 4: Upgrade plan & breaking changes | [`build.gradle.kts`](../../build.gradle.kts), [`settings.gradle.kts`](../../settings.gradle.kts) |

---

## 4. Archival Lifecycle Process

When retiring or archiving documents:
1. Move files with `git mv <source> docs/archive/<category>/<destination>`.
2. Add an entry to the table in Section 3 of this document.
3. If an archived document was referenced in other active markdown files, update those links to point to the canonical replacement or to the new archive path.
4. Verify with `./scripts/check-monorepo-consistency.sh` to ensure no repository integrity checks are broken.
