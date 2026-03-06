# Documentation Index

Central index for all InstaCommerce architecture documentation, review reports, and design documents.

## Document Inventory

### Architecture & Design Documents

| Document | Path | Description |
|----------|------|-------------|
| API Gateway & BFF Design | [`reviews/api-gateway-bff-design.md`](reviews/api-gateway-bff-design.md) | API gateway architecture, mobile BFF pattern, and routing strategy |
| Data Platform & ML Design | [`reviews/data-platform-ml-design.md`](reviews/data-platform-ml-design.md) | Data lake, streaming pipeline, feature store, and ML platform architecture |
| Fleet Architecture Review (2026-02-13) | [`reviews/FLEET-ARCHITECTURE-REVIEW-2026-02-13.md`](reviews/FLEET-ARCHITECTURE-REVIEW-2026-02-13.md) | Consolidated 20-agent codebase review with roadmap, LLD, and mermaid architecture/flow diagrams |
| AI Agent Fleet Plan (2026-02-13) | [`reviews/AI-AGENT-FLEET-PLAN-2026-02-13.md`](reviews/AI-AGENT-FLEET-PLAN-2026-02-13.md) | 24-agent production rollout plan with revenue opportunities and competitor-aligned architecture |
| Wave 2 Data/ML Roadmap B | [`architecture/WAVE2-DATA-ML-ROADMAP-B.md`](architecture/WAVE2-DATA-ML-ROADMAP-B.md) | DataOps, data mesh, reverse ETL, and activation roadmap for growth/retention/revenue |
| Master Review & Refactor Plan | [`reviews/MASTER-REVIEW-AND-REFACTOR-PLAN.md`](reviews/MASTER-REVIEW-AND-REFACTOR-PLAN.md) | Comprehensive review summary and prioritized refactoring roadmap |
| AI & Golang Opportunities | [`reviews/AI-GOLANG-OPPORTUNITIES-REVIEW.md`](reviews/AI-GOLANG-OPPORTUNITIES-REVIEW.md) | Analysis of AI integration and Go migration opportunities |

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
| Helm Chart | [`deploy/helm/README.md`](../deploy/helm/README.md) | Kubernetes deployment configuration |
| Terraform | [`infra/terraform/README.md`](../infra/terraform/README.md) | Cloud infrastructure as code |
| ArgoCD | [`argocd/README.md`](../argocd/README.md) | GitOps deployment configuration |
| Monitoring | [`monitoring/README.md`](../monitoring/README.md) | Prometheus alerting rules and SLOs |
| Contracts | [`contracts/README.md`](../contracts/README.md) | Protobuf & event schema contracts |
| CI/CD | [`.github/README.md`](../.github/README.md) | GitHub Actions workflows and Dependabot |
| ML Platform | [`ml/README.md`](../ml/README.md) | Machine learning models and serving |
| Data Platform | [`data-platform/streaming/README.md`](../data-platform/streaming/README.md) | Kafka streaming pipelines |
| Data Platform Jobs | [`data-platform-jobs/README.md`](../data-platform-jobs/README.md) | Batch data processing jobs |
