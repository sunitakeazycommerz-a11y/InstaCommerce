# Iteration 3 Review Index

This folder contains the regenerated, discoverable iteration-3 review set for InstaCommerce.

## Top-level documents

| Document | Path | Description |
|---|---|---|
| Master review | [`master-review.md`](master-review.md) | Principal/staff-level synthesis of the full iteration-3 review |
| Service-wise guide | [`service-wise-guide.md`](service-wise-guide.md) | Cross-cluster implementation guide built from the service docs |
| Platform-wise guide | [`platform-wise-guide.md`](platform-wise-guide.md) | Cross-cutting implementation guide built from the platform docs |
| Implementation program | [`implementation-program.md`](implementation-program.md) | Wave-based program, governance model, and rollout sequencing |

## Benchmarks

| Document | Path | Description |
|---|---|---|
| Global operator patterns | [`benchmarks/global-operator-patterns.md`](benchmarks/global-operator-patterns.md) | Comparison against DoorDash, Instacart, and global high-scale patterns |
| India operator patterns | [`benchmarks/india-operator-patterns.md`](benchmarks/india-operator-patterns.md) | Comparison against Zepto, Blinkit, and Swiggy Instamart |
| Public best practices | [`benchmarks/public-best-practices.md`](benchmarks/public-best-practices.md) | Public references and implementation implications |
| AI agent use cases | [`benchmarks/ai-agent-use-cases.md`](benchmarks/ai-agent-use-cases.md) | Safe AI-agent operating models and q-commerce use cases |

## Diagrams

| Document | Path | Description |
|---|---|---|
| HLD and system context | [`diagrams/hld-system-context.md`](diagrams/hld-system-context.md) | Refreshed iteration-3 HLD and system-context diagrams |
| Edge-to-checkout LLD | [`diagrams/lld-edge-checkout.md`](diagrams/lld-edge-checkout.md) | Detailed edge, BFF, checkout, order, payment, and inventory flows |
| Eventing and data LLD | [`diagrams/lld-eventing-data.md`](diagrams/lld-eventing-data.md) | Outbox, Debezium, Kafka, data platform, ML, and replay flows |
| Checkout/payment sequence | [`diagrams/sequence-checkout-payment.md`](diagrams/sequence-checkout-payment.md) | Happy path and failure-path sequence diagrams |
| Governance and rollout flow | [`diagrams/flow-governance-rollout.md`](diagrams/flow-governance-rollout.md) | CI/CD, GitOps, rollout, rollback, and ownership diagrams |
| Data/ML/AI flow | [`diagrams/flow-data-ml-ai.md`](diagrams/flow-data-ml-ai.md) | End-to-end data, feature, training, inference, and AI proposal loops |

## Service guides

| Document | Path |
|---|---|
| Edge & identity | [`services/edge-identity.md`](services/edge-identity.md) |
| Transactional core | [`services/transactional-core.md`](services/transactional-core.md) |
| Read & decision plane | [`services/read-decision-plane.md`](services/read-decision-plane.md) |
| Inventory & dark-store | [`services/inventory-dark-store.md`](services/inventory-dark-store.md) |
| Fulfillment & logistics | [`services/fulfillment-logistics.md`](services/fulfillment-logistics.md) |
| Customer & engagement | [`services/customer-engagement.md`](services/customer-engagement.md) |
| Platform foundations | [`services/platform-foundations.md`](services/platform-foundations.md) |
| Event & data plane | [`services/event-data-plane.md`](services/event-data-plane.md) |
| AI / ML platform | [`services/ai-ml-platform.md`](services/ai-ml-platform.md) |

## Platform guides

| Document | Path |
|---|---|
| Repo truth & ownership | [`platform/repo-truth-ownership.md`](platform/repo-truth-ownership.md) |
| Contracts & event governance | [`platform/contracts-event-governance.md`](platform/contracts-event-governance.md) |
| Security & trust boundaries | [`platform/security-trust-boundaries.md`](platform/security-trust-boundaries.md) |
| Infra, GitOps, and release | [`platform/infra-gitops-release.md`](platform/infra-gitops-release.md) |
| Observability & SRE | [`platform/observability-sre.md`](platform/observability-sre.md) |
| Testing & quality governance | [`platform/testing-quality-governance.md`](platform/testing-quality-governance.md) |
| Data platform correctness | [`platform/data-platform-correctness.md`](platform/data-platform-correctness.md) |
| ML productionization | [`platform/ml-platform-productionization.md`](platform/ml-platform-productionization.md) |
| AI agent governance | [`platform/ai-agent-governance.md`](platform/ai-agent-governance.md) |

## Appendices

| Document | Path |
|---|---|
| Issue register | [`appendices/issue-register.md`](appendices/issue-register.md) |
| Approach comparison matrix | [`appendices/approach-comparison-matrix.md`](appendices/approach-comparison-matrix.md) |
| Validation and rollout playbooks | [`appendices/validation-rollout-playbooks.md`](appendices/validation-rollout-playbooks.md) |
