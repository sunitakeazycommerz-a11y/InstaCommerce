# Documentation Index

Central navigation for InstaCommerce architecture docs, review programs, and
service/platform deep dives.

## Quick Start

| Need | Path | Description |
|------|------|-------------|
| Repo overview | [`../README.md`](../README.md) | Main repository overview, quick start, service inventory, and top-level architecture |
| Architecture canon | [`architecture/`](architecture/) | HLD, LLD, data-flow, infrastructure, and wave roadmaps |
| Review canon | [`reviews/`](reviews/) | Service reviews, principal programs, and implementation guides |
| Iteration 3 hub | [`reviews/iter3/README.md`](reviews/iter3/README.md) | Benchmarks, diagrams, service guides, platform guides, and appendices |
| Monitoring & alerts | [`../monitoring/README.md`](../monitoring/README.md) | Alert definitions, dashboard inventory, and escalation routing |

## Architecture & Design Documents

| Document | Path | Description |
|----------|------|-------------|
| High-Level Design | [`architecture/HLD.md`](architecture/HLD.md) | System context, domain grouping, service boundaries, and non-functional goals |
| Low-Level Design | [`architecture/LLD.md`](architecture/LLD.md) | Detailed service interactions, state machines, class flows, and persistence notes |
| Data Flow | [`architecture/DATA-FLOW.md`](architecture/DATA-FLOW.md) | Eventing, outbox/CDC, data platform, ML feature, and reverse-flow diagrams |
| Infrastructure | [`architecture/INFRASTRUCTURE.md`](architecture/INFRASTRUCTURE.md) | GCP, GKE, networking, stateful services, DR, and GitOps runtime posture |
| Iteration 3 HLD & System Context Diagrams (2026-03-06) | [`architecture/ITER3-HLD-DIAGRAMS.md`](architecture/ITER3-HLD-DIAGRAMS.md) | Refreshed target architecture with governance, ownership, and rollout context |
| Wave 2 Data/ML Roadmap B | [`architecture/WAVE2-DATA-ML-ROADMAP-B.md`](architecture/WAVE2-DATA-ML-ROADMAP-B.md) | Data activation, reverse ETL, ML platform evolution, and growth roadmap |
| API Gateway & BFF Design | [`reviews/api-gateway-bff-design.md`](reviews/api-gateway-bff-design.md) | Edge routing, BFF patterns, and gateway responsibilities |
| Data Platform & ML Design | [`reviews/data-platform-ml-design.md`](reviews/data-platform-ml-design.md) | Data lake, streaming, feature store, and ML platform architecture |

## Iteration 3 — Top-Level Synthesis

| Document | Path | Description |
|----------|------|-------------|
| Iteration 3 Folder Index (2026-03-07) | [`reviews/iter3/README.md`](reviews/iter3/README.md) | Discoverable index for the regenerated iteration-3 folder tree |
| Master review | [`reviews/iter3/master-review.md`](reviews/iter3/master-review.md) | Principal/staff-level synthesis of the full iteration-3 review |
| Service-wise guide | [`reviews/iter3/service-wise-guide.md`](reviews/iter3/service-wise-guide.md) | Cross-cluster implementation guide built from service deep dives |
| Platform-wise guide | [`reviews/iter3/platform-wise-guide.md`](reviews/iter3/platform-wise-guide.md) | Cross-cutting implementation guide built from platform deep dives |
| Implementation program | [`reviews/iter3/implementation-program.md`](reviews/iter3/implementation-program.md) | Wave-based program, governance model, and rollout sequencing |
| Principal Engineering Review - Iteration 3 (2026-03-06) | [`reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md`](reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md) | Third-pass principal review focused on implementation detail and delivery sequencing |
| Principal Engineering Implementation Program (2026-03-06) | [`reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-PROGRAM-2026-03-06.md`](reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-PROGRAM-2026-03-06.md) | Wave-based execution program translating review findings into owners and gates |
| Principal Engineering Implementation Guide - Service Wise (2026-03-06) | [`reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-SERVICE-WISE-2026-03-06.md`](reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-SERVICE-WISE-2026-03-06.md) | Nine-cluster implementation guide with migration, validation, and rollback detail |
| Principal Engineering Implementation Guide - Platform Wise (2026-03-06) | [`reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-PLATFORM-WISE-2026-03-06.md`](reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-PLATFORM-WISE-2026-03-06.md) | Contracts, security, SRE, CI/CD, data, ML, AI, and governance guidance |

## Iteration 3 — Deep-Dive Hubs

| Area | Path | Description |
|------|------|-------------|
| Benchmarks | [`reviews/iter3/README.md#benchmarks`](reviews/iter3/README.md#benchmarks) | Global/India operator patterns, public best practices, and AI-agent use cases |
| Diagrams | [`reviews/iter3/README.md#diagrams`](reviews/iter3/README.md#diagrams) | HLD, LLD, sequence, governance, and data/ML/AI diagram sets |
| Service guides | [`reviews/iter3/README.md#service-guides`](reviews/iter3/README.md#service-guides) | Nine service-cluster implementation guides |
| Platform guides | [`reviews/iter3/README.md#platform-guides`](reviews/iter3/README.md#platform-guides) | Contracts, security, infra/release, SRE, testing, data, ML, and AI governance |
| Appendices | [`reviews/iter3/README.md#appendices`](reviews/iter3/README.md#appendices) | Issue register, approach comparison matrix, and rollout playbooks |

### Service Review Reports

| Document | Path | Description |
|----------|------|-------------|
| Identity Service | [`reviews/identity-service-review.md`](reviews/identity-service-review.md) | Auth, user management, JWT/JWKS review |
| Catalog Service | [`reviews/catalog-service-review.md`](reviews/catalog-service-review.md) | Product catalog, categories, media management |
| Inventory Service | [`reviews/inventory-service-review.md`](reviews/inventory-service-review.md) | Stock tracking, reservation, multi-warehouse support |
| Order Service | [`reviews/order-service-review.md`](reviews/order-service-review.md) | Order lifecycle, state machine, outbox events |
| Payment Service | [`reviews/payment-service-review.md`](reviews/payment-service-review.md) | Payment processing, gateway integration, refunds |
| Fulfillment Service | [`reviews/fulfillment-service-review.md`](reviews/fulfillment-service-review.md) | Packing, dispatch, handoff orchestration |
| Notification Service | [`reviews/notification-service-review.md`](reviews/notification-service-review.md) | Push, SMS, email notifications, template management |
| Search Service | [`reviews/search-service-review.md`](reviews/search-service-review.md) | Full-text search, indexing, relevance tuning |
| Pricing Service | [`reviews/pricing-service-review.md`](reviews/pricing-service-review.md) | Dynamic pricing, surge, promotions engine |
| Cart Service | [`reviews/cart-service-review.md`](reviews/cart-service-review.md) | Cart management, merge strategies, Redis caching |
| Checkout Orchestrator | [`reviews/checkout-orchestrator-review.md`](reviews/checkout-orchestrator-review.md) | Saga orchestration via Temporal, checkout flow |
| Warehouse Service | [`reviews/warehouse-service-review.md`](reviews/warehouse-service-review.md) | Dark store management, zone configuration |
| Rider Fleet Service | [`reviews/rider-fleet-service-review.md`](reviews/rider-fleet-service-review.md) | Rider onboarding, availability, location tracking |
| Routing & ETA Service | [`reviews/routing-eta-service-review.md`](reviews/routing-eta-service-review.md) | Route optimization, ETA calculation, delivery tracking |
| Wallet & Loyalty Service | [`reviews/wallet-loyalty-service-review.md`](reviews/wallet-loyalty-service-review.md) | Digital wallet, loyalty points, referral programs |
| Fraud Detection Service | [`reviews/fraud-detection-service-review.md`](reviews/fraud-detection-service-review.md) | Real-time fraud scoring, rule engine, ML models |
| Audit Trail Service | [`reviews/audit-trail-service-review.md`](reviews/audit-trail-service-review.md) | Immutable audit logs, compliance, event sourcing |
| Config & Feature Flags | [`reviews/config-feature-flag-service-review.md`](reviews/config-feature-flag-service-review.md) | Feature toggles, dynamic configuration, A/B testing |

### Infrastructure Review

| Document | Path | Description |
|----------|------|-------------|
| Infrastructure Review | [`reviews/infrastructure-review.md`](reviews/infrastructure-review.md) | GKE, Terraform, Helm, networking, and security review |
| Contracts & Events Review | [`reviews/contracts-events-review.md`](reviews/contracts-events-review.md) | Event schemas, Protobuf contracts, CDC pipeline review |

## Related Documentation

| Area | Location | Description |
|------|----------|-------------|
| Root repository guide | [`../README.md`](../README.md) | Repo overview, quick start, and top-level architecture |
| Helm Chart | [`deploy/helm/README.md`](../deploy/helm/README.md) | Kubernetes deployment configuration |
| Terraform | [`infra/terraform/README.md`](../infra/terraform/README.md) | Cloud infrastructure as code |
| ArgoCD | [`argocd/README.md`](../argocd/README.md) | GitOps deployment configuration |
| Monitoring | [`monitoring/README.md`](../monitoring/README.md) | Prometheus alerting rules and SLOs |
| Contracts | [`contracts/README.md`](../contracts/README.md) | Protobuf & event schema contracts |
| CI/CD | [`.github/README.md`](../.github/README.md) | GitHub Actions workflows and Dependabot |
| ML Platform | [`ml/README.md`](../ml/README.md) | Machine learning models and serving |
| Data Platform | [`../data-platform/README.md`](../data-platform/README.md) | Data lake, dbt, orchestration, and platform architecture |
| Data Platform Streaming | [`../data-platform/streaming/README.md`](../data-platform/streaming/README.md) | Kafka/Dataflow-style streaming pipelines |
| Data Platform Jobs | [`data-platform-jobs/README.md`](../data-platform-jobs/README.md) | Batch data processing jobs |
