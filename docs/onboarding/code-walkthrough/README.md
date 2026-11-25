# InstaCommerce Whole-System Code Walkthrough Atlas

> [!NOTE]
> **Part of the InstaCommerce Onboarding Suite:** [Onboarding Hub](../README.md) · [Day 0–5 Curriculum](../01-day0-to-day5-curriculum.md) · [Hands-On Labs](../02-hands-on-labs.md) · [Role Learning Tracks](../03-role-learning-tracks.md) · [First-PR Playbook & Troubleshooting](../04-first-pr-and-troubleshooting.md) · [Domain Model & Event Catalog](../05-domain-model-and-event-catalog.md) · [Incident Drills & Readiness Exam](../06-incident-drills-and-readiness-exam.md) · [Dependency Matrix & Cheatsheet](../07-service-dependency-and-command-cheatsheet.md) · **Code Walkthrough Atlas (you are here)**

> **Authoritative Engineering Navigation:** This directory provides a ground-truth, code-verified walkthrough of the entire **InstaCommerce** polyglot monorepo—covering the **whole-system architecture**, **7 shared platform components**, **30 microservices** (20 Java 21 / Spring Boot 4.0.0, 8 Go 1.25, 2 Python 3.12 / FastAPI), and **11 rendered system diagrams** (plus component and per-service diagrams).
>
> Every claim, symbol, SQL invariant, port, Helm flag, and link is anchored directly to checked-in source files using portable repository-relative links. Where legacy READMEs or ADRs diverge from runtime execution, each walkthrough explicitly states what the code executes today.

---

## 1. Whole-System Foundations & Cross-Cutting Walkthroughs

Start here to build the end-to-end mental model before diving into individual libraries, pipelines, or microservices:

| Document | Scope & Deep-Dive Contents | Embedded System Diagrams |
|---|---|---|
| **[01-system-hld.md](./01-system-hld.md)** | First principles of 10-minute quick-commerce physics, `store_id` dark-store partitioning, Temporal sagas vs. 2PC, Transactional Outbox vs. Debezium WAL CDC, polyglot Java 21 / Go 1.25 / Python 3.12 boundaries, PgBouncer & Cloud SQL 500-connection ceiling, Istio zero-trust perimeter, ADR-015 SLO tiers, and candid repo-truth debt summary | [01-hld-bounded-contexts.png](./diagrams/system/01-hld-bounded-contexts.png) · [02-hld-trust-and-network.png](./diagrams/system/02-hld-trust-and-network.png) · [03-hld-data-ml-gitops.png](./diagrams/system/03-hld-data-ml-gitops.png) |
| **[02-system-lld.md](./02-system-lld.md)** | Low-level cross-cutting mechanics: `EventEnvelope.v1.json` / Go dual-emit / `outbox-relay` payload flattening, `TopicNames.java`, `.proto` vs. REST reality, `SharedInternalServiceAuthFilter` & `go-shared/pkg/auth` filter precedence, `Idempotency-Key` vs. JSON body normalization & `inventory` `VARCHAR(64)` bug, sorted `SELECT FOR UPDATE`, `SKIP LOCKED`, `pg_advisory_xact_lock`, 2-TX pending PSP saga & double-entry ledgers, `TwoLevelCacheManager` L1+L2+PubSub protocol, Resilience4j / Go circuit breakers, and `ProblemDetailFactory` vs. edge errors | [04-lld-contracts-and-auth-internals.png](./diagrams/system/04-lld-contracts-and-auth-internals.png) · [05-lld-concurrency-locking-outbox.png](./diagrams/system/05-lld-concurrency-locking-outbox.png) · [06-lld-resilience-and-error-topology.png](./diagrams/system/06-lld-resilience-and-error-topology.png) |
| **[03-system-end-to-end-flows.md](./03-system-end-to-end-flows.md)** | 5 exhaustive code-level execution journeys tracing exact controllers, classes, methods, SQL tables, Redis keys, and Kafka topics: (1) Browse → Search → Pricing → Cart → Inventory; (2) 10-Step Temporal Checkout Saga + 3-Tier LIFO Compensation + DLQ; (3) Order → Warehouse/Fulfillment → `OrderPacked` Capture → Dual GPS/Dispatch Split-Brain → ETA → Notification → T+1 Recon; (4) Outbox/Debezium/Stream/CDC → BigQuery/dbt/Airflow/Beam → ML/Reverse-ETL → AI LangGraph; (5) Local `dev-local.sh` → CI → ArgoCD → Helm/Istio/SLO Burn Rates | [07-flow-browse-cart.png](./diagrams/system/07-flow-browse-cart.png) · [08-flow-checkout-saga.png](./diagrams/system/08-flow-checkout-saga.png) · [09-flow-fulfillment-logistics-money.png](./diagrams/system/09-flow-fulfillment-logistics-money.png) · [10-flow-event-data-ml-ai.png](./diagrams/system/10-flow-event-data-ml-ai.png) · [11-flow-dev-to-prod-gitops.png](./diagrams/system/11-flow-dev-to-prod-gitops.png) |

---

## 2. Visual System Diagram Gallery (`diagrams/system/`)

All system diagrams are rendered from checked-in `.mmd` sources using [`diagrams/mermaid-config.json`](./diagrams/mermaid-config.json):

| # | Diagram | Source (`.mmd`) | Rendered PNG |
|---|---|---|---|
| 1 | Bounded Contexts, 30 Services & Pool Ceiling | [01-hld-bounded-contexts.mmd](./diagrams/system/01-hld-bounded-contexts.mmd) | [01-hld-bounded-contexts.png](./diagrams/system/01-hld-bounded-contexts.png) |
| 2 | Istio Zero-Trust Mesh & Internal Auth Perimeter | [02-hld-trust-and-network.mmd](./diagrams/system/02-hld-trust-and-network.mmd) | [02-hld-trust-and-network.png](./diagrams/system/02-hld-trust-and-network.png) |
| 3 | Data Lakehouse, ML/GenAI & GitOps Macro Topology | [03-hld-data-ml-gitops.mmd](./diagrams/system/03-hld-data-ml-gitops.mmd) | [03-hld-data-ml-gitops.png](./diagrams/system/03-hld-data-ml-gitops.png) |
| 4 | Wire Contracts, Envelope Bridges & Auth Precedence | [04-lld-contracts-and-auth-internals.mmd](./diagrams/system/04-lld-contracts-and-auth-internals.mmd) | [04-lld-contracts-and-auth-internals.png](./diagrams/system/04-lld-contracts-and-auth-internals.png) |
| 5 | Idempotency, Locking, 2-TX Pending PSP & Outbox | [05-lld-concurrency-locking-outbox.mmd](./diagrams/system/05-lld-concurrency-locking-outbox.mmd) | [05-lld-concurrency-locking-outbox.png](./diagrams/system/05-lld-concurrency-locking-outbox.png) |
| 6 | Two-Level Cache, Circuit Breakers & Error Topology | [06-lld-resilience-and-error-topology.mmd](./diagrams/system/06-lld-resilience-and-error-topology.mmd) | [06-lld-resilience-and-error-topology.png](./diagrams/system/06-lld-resilience-and-error-topology.png) |
| 7 | Flow 1: Discovery, Search, Pricing, Cart & Stock Check | [07-flow-browse-cart.mmd](./diagrams/system/07-flow-browse-cart.mmd) | [07-flow-browse-cart.png](./diagrams/system/07-flow-browse-cart.png) |
| 8 | Flow 2: 10-Step Temporal Checkout Saga & LIFO Unwind | [08-flow-checkout-saga.mmd](./diagrams/system/08-flow-checkout-saga.mmd) | [08-flow-checkout-saga.png](./diagrams/system/08-flow-checkout-saga.png) |
| 9 | Flow 3: Fulfillment, GPS/Dispatch Split-Brain, ETA & Money | [09-flow-fulfillment-logistics-money.mmd](./diagrams/system/09-flow-fulfillment-logistics-money.mmd) | [09-flow-fulfillment-logistics-money.png](./diagrams/system/09-flow-fulfillment-logistics-money.png) |
| 10 | Flow 4: Outbox/Debezium, Stream/BQ/dbt, ML & LangGraph | [10-flow-event-data-ml-ai.mmd](./diagrams/system/10-flow-event-data-ml-ai.mmd) | [10-flow-event-data-ml-ai.png](./diagrams/system/10-flow-event-data-ml-ai.png) |
| 11 | Flow 5: Local `dev-local.sh` → CI → ArgoCD → ADR-015 SLOs | [11-flow-dev-to-prod-gitops.mmd](./diagrams/system/11-flow-dev-to-prod-gitops.mmd) | [11-flow-dev-to-prod-gitops.png](./diagrams/system/11-flow-dev-to-prod-gitops.png) |

---

## 3. Platform Components & Shared Libraries (`7` Deep Walkthroughs)

Each component guide inspects the actual build scripts, shared code, SQL/YAML definitions, and cross-service adoption seams, paired with 2 diagrams (`arch-lld` and `flow`) under `diagrams/components/`:

| # | Component Walkthrough | Repository Scope | Key Code Realities Covered |
|---|---|---|---|
| 1 | **[contracts-and-proto.md](./components/contracts-and-proto.md)** | [`contracts/`](../../../contracts/build.gradle.kts) | `EventEnvelope.java`, `EventEnvelope.v1.json`, `TopicNames.java`, 91 JSON schemas, 10 `.proto` files, `SchemaCompatibilityValidator`, `generateGoProto` (`pkg/proto` unused by Go) |
| 2 | **[platform-common-java.md](./components/platform-common-java.md)** | [`libs/platform-common/`](../../../libs/platform-common/build.gradle.kts) | `SharedInternalServiceAuthFilter` (11 svcs), `JwtKeyLoader` / `JwtTokenVerifier` (0 consumers), `TwoLevelCacheManager` (4 svcs), `ProblemDetailFactory` (3 svcs), observability |
| 3 | **[go-shared.md](./components/go-shared.md)** | [`services/go-shared/`](../../../services/go-shared/go.mod) | `pkg/auth`, `pkg/correlation`, `pkg/health`, `pkg/httpclient` (CB + 5s DNS cache + `WithInternalAuth`), `pkg/kafka` dual-emit, `pkg/observability`, `pkg/proto` |
| 4 | **[data-platform-lakehouse.md](./components/data-platform-lakehouse.md)** | [`data-platform/`](../../../data-platform/dbt/dbt_project.yml) & [`data-platform-jobs/`](../../../data-platform-jobs/README.md) | `dbt/` (8 staging → 3 int → 10 marts), `airflow/` (8 DAGs, `KafkaLagSensor`), `quality/` GE suites, `streaming/` (6 Beam pipelines), schema/column drift seams |
| 5 | **[ml-platform.md](./components/ml-platform.md)** | [`ml/`](../../../ml/README.md) | `feature_definitions.py` `[T-30d, T-1d]`, `ml/feature_store/` vs. `ml_feature_refresh.py`, 6 trainers (`fraud` test-leakage, `personalization` SGD), fail-closed eval gates, unused `ml/serving/` |
| 6 | **[infra-terraform-gitops.md](./components/infra-terraform-gitops.md)** | [`infra/terraform/`](../../../infra/terraform/environments/dev/main.tf), [`deploy/`](../../../deploy/helm/values.yaml), [`argocd/`](../../../argocd/app-of-apps.yaml), [`k8s/`](../../../k8s/reverse-etl-orchestrator-deployment.yaml) | VPC/GKE/CloudSQL/Memorystore modules, missing DBs/SAs/IAM bindings/secrets, Helm 43 Istio policies, `NetworkPolicy` scrape bug, literal placeholders, canary `.enabled` no-op |
| 7 | **[ci-observability-scripts.md](./components/ci-observability-scripts.md)** | [`.github/workflows/ci.yml`](../../../.github/workflows/ci.yml), [`monitoring/`](../../../monitoring/error-budget-policy.yaml), [`scripts/`](../../../scripts/dev-local.sh), [`tests/`](../../../tests/chaos/test_chaos_recovery.py) | `paths-filter` matrices, Go `GCP_SA_KEY` WIF gap, ADR-015 burn rates, `wave-40` metric drift, empty `grafana-dashboards/`, `dev-local.sh`, `check-monorepo-consistency.sh` |

---

## 4. Microservice Code Walkthroughs (`30` Services)

Every service guide contains package layout, startup/security chains, Flyway/SQL/Redis internals, 3 Mermaid diagrams (`arch-lld`, `flowchart`, `sequence` under `diagrams/services/`), method-by-method traces, narrow test commands, and verified code-level bugs or doc drift.

### 4.1 Edge, Identity, Governance & Feature Flags (`5` Java Services)

| Service | Port | Stack | Walkthrough | Primary Storage | Code Focus |
|---|---|---|---|---|---|
| `mobile-bff-service` | `8080` | Java 21 / WebFlux | **[mobile-bff-service.md](./services/mobile-bff-service.md)** | Stateless (+ Redis Pub/Sub) | `UserContextPropagationFilter`, `HomeAggregator`/`ScreenAggregator`, SSE `/cart/stream/{userId}` |
| `identity-service` | `8081` | Java 21 / Boot 4 | **[identity-service.md](./services/identity-service.md)** | PostgreSQL + Redis | `DefaultJwtService` RS256/JWKS, lockouts, refresh reuse defense, fail-closed `TokenBlocklistService` |
| `admin-gateway-service` | `8099` | Java 21 / MVC | **[admin-gateway-service.md](./services/admin-gateway-service.md)** | Stateless | `AdminJwtAuthenticationFilter` (`instacommerce-api` bridge), `AdminRateLimitFilter`, sync `AdminSecurityAuditFilter` |
| `audit-trail-service` | `8096` | Java 21 / Boot 4 | **[audit-trail-service.md](./services/audit-trail-service.md)** | PostgreSQL + GCS | 21-topic `DomainEventConsumer`, `pg_advisory_xact_lock` SHA-256 chain, monthly partitions + GCS archive |
| `config-feature-flag-service` | `8098` | Java 21 / Boot 4 | **[config-feature-flag-service.md](./services/config-feature-flag-service.md)** | PostgreSQL + Redis | Guava Murmur3 flags/switchback experiments, Caffeine + Redis Pub/Sub `flag-updates:*`, SSE `/flags/stream` |

### 4.2 Discovery, Pricing, Cart & Inventory (`5` Java Services)

| Service | Port | Stack | Walkthrough | Primary Storage | Code Focus |
|---|---|---|---|---|---|
| `catalog-service` | `8082` | Java 21 / Boot 4 | **[catalog-service.md](./services/catalog-service.md)** | PostgreSQL + Redis L2 | Materialized categories (`depth <= 5`), `(storeId OR NULL)`, in-process `OutboxPublishJob` |
| `search-service` | `8088` | Java 21 / Boot 4 | **[search-service.md](./services/search-service.md)** | PostgreSQL (`tsvector`) + Redis L2 | GIN `plainto_tsquery` + fuzzy `LIKE` (`0.5`), `text_pattern_ops` autocomplete, `product_id UNIQUE` trap |
| `pricing-service` | `8089` | Java 21 / Boot 4 | **[pricing-service.md](./services/pricing-service.md)** | PostgreSQL + Redis L2 | `PriceRule` (`HALF_UP`), exclusive vs. stackable promos, Redis `NX` coupon lock, HMAC `PriceQuote` |
| `cart-service` | `8090` | Java 21 / Boot 4 | **[cart-service.md](./services/cart-service.md)** | PostgreSQL + Redis L2 | `JpaCartStore` (`PESSIMISTIC_WRITE`), `If-Match` ETag, `409 STORE_MISMATCH`, fail-closed `PricingClient` |
| `inventory-service` | `8083` | Java 21 / Boot 4 | **[inventory-service.md](./services/inventory-service.md)** | PostgreSQL | Sorted `productId` `SELECT FOR UPDATE`, DB `CHECK` invariants, FEFO batches, `VARCHAR(64)` key bug |

### 4.3 Orders, Checkout Saga, Money & Risk (`5` Java Services)

| Service | Port | Stack | Walkthrough | Primary Storage | Code Focus |
|---|---|---|---|---|---|
| `order-service` | `8084` | Java 21 / Boot 4 | **[order-service.md](./services/order-service.md)** | PostgreSQL | 9-state `OrderStatus` FSM, SHA-256 `idempotencyKey`, `OutboxService`, `410 GONE` `LegacyCheckoutController` |
| `checkout-orchestrator-service` | `8091` | Java 21 / Temporal | **[checkout-orchestrator-service.md](./services/checkout-orchestrator-service.md)** | Temporal + PostgreSQL | `Semaphore(1500)`, 10-step `CheckoutWorkflowImpl`, 3-tier payment comp, LIFO unwind, `compensation_dlq` |
| `payment-service` | `8085` | Java 21 / Boot 4 | **[payment-service.md](./services/payment-service.md)** | PostgreSQL | 2-TX pending PSP saga, `LedgerService` double-entry, `OrderPacked` capture, Helm disabled flags |
| `wallet-loyalty-service` | `8095` | Java 21 / Boot 4 | **[wallet-loyalty-service.md](./services/wallet-loyalty-service.md)** | PostgreSQL | `SELECT FOR UPDATE` wallet balance, double-entry ledger, loyalty cashback, `CHECKOUT_SAGA_ROLLBACK` guard |
| `fraud-detection-service` | `8097` | Java 21 / Boot 4 | **[fraud-detection-service.md](./services/fraud-detection-service.md)** | PostgreSQL + opt. Redis | Fail-closed `AUTO_BLOCK`, aligned velocity windows, `>= 2` 30d chargeback auto-blocklist |

### 4.4 Warehouse, Fulfillment, Fleet, ETA & Notifications (`5` Java Services)

| Service | Port | Stack | Walkthrough | Primary Storage | Code Focus |
|---|---|---|---|---|---|
| `warehouse-service` | `8092` | Java 21 / Boot 4 | **[warehouse-service.md](./services/warehouse-service.md)** | PostgreSQL | `PRIMARY_PINCODE` + `15km` resolution, slot locking (`100`), 10-aisle Dijkstra, cold-chain alerts |
| `fulfillment-service` | `8086` | Java 21 / Boot 4 | **[fulfillment-service.md](./services/fulfillment-service.md)** | PostgreSQL | S-curve pick routing, 4-tote wave batching, `OrderPacked`, 6-digit OTP handoff, `<= 200KB` WEBP POD |
| `rider-fleet-service` | `8093` | Java 21 / Boot 4 | **[rider-fleet-service.md](./services/rider-fleet-service.md)** | PostgreSQL + Redis GEO | `GEORADIUS riders:active` + `SKIP LOCKED` default dispatch, `optimizer-enabled=false`, rider earnings |
| `routing-eta-service` | `8094` | Java 21 / Boot 4 | **[routing-eta-service.md](./services/routing-eta-service.md)** | PostgreSQL + STOMP | `Haversine * 1.4` + barriers/weather/IST multipliers (no OSRM/GMaps), `/ws/tracking`, `ETABreachRisk` |
| `notification-service` | `8087` | Java 21 / Boot 4 | **[notification-service.md](./services/notification-service.md)** | PostgreSQL | 7-topic fanout, `(event_id, channel)` dedup, quiet hours, `PUSH -> SMS -> EMAIL` fallback |

### 4.5 High-Throughput Go Infrastructure & Solvers (`8` Go Services)

| Service | Port | Helm Key | Walkthrough | Primary Storage | Code Focus |
|---|---|---|---|---|---|
| `outbox-relay-service` | `8103` | `outbox-relay` (+13) | **[outbox-relay-service.md](./services/outbox-relay-service.md)** | Reads service Postgres | `SKIP LOCKED` polling -> open `pgx.Tx` across Sarama `WaitForAll`, `outbox.relay.dlq`, dual/flat envelope |
| `cdc-consumer-service` | `8104` | `cdc-consumer` | **[cdc-consumer-service.md](./services/cdc-consumer-service.md)** | BigQuery sink | `batchCh` backpressure, `insertID`, `SchemaEvolutionHandler`, `cdc.dlq`, missing Helm env vars |
| `stream-processor-service` | `8108` | `stream-processor` | **[stream-processor-service.md](./services/stream-processor-service.md)** | Redis + Prometheus | Bloom+Redis `SetNX` dedup, `800`/`300` watermarks, SLA/velocity/cascade windows (no BigQuery sink) |
| `location-ingestion-service` | `8105` | `location-ingestion` | **[location-ingestion-service.md](./services/location-ingestion-service.md)** | Redis (`rider:location:*`, `riders:h3:*`) | Kalman filter, Lua CAS, custom axial hex sets, empty runtime `zones` map |
| `payment-webhook-service` | `8106` | `payment-webhook` | **[payment-webhook-service.md](./services/payment-webhook-service.md)** | Redis / in-mem dedup | Stripe/Razorpay/PhonePe verification + replay check, Redis `SETNX` dedup -> `payment.webhooks` |
| `dispatch-optimizer-service` | `8102` | `dispatch-optimizer-service` | **[dispatch-optimizer-service.md](./services/dispatch-optimizer-service.md)** | Stateless + opt. Redis lock | Greedy sorted-edge + 1-opt (`hungarianAssign` misnomer), EV/cap checks, unused `/dispatch/assign` |
| `reconciliation-engine` | `8107` | `reconciliation-engine` | **[reconciliation-engine.md](./services/reconciliation-engine.md)** | PostgreSQL | Monotonic `source_lsn` CDC upsert, T+1 `SAVEPOINT` matcher, missing `psp_export_snapshot` writer |
| `reverse-etl-orchestrator` | `8109` | `reverse-etl-orchestrator` | **[reverse-etl-orchestrator.md](./services/reverse-etl-orchestrator.md)** | PostgreSQL + Redis | `WorkerPool(10)` + Lua `RENAME`/`PEXPIRE` swap (`HTTP` push only; Kafka/Temporal/BQ unconfigured) |

### 4.6 AI & ML Serving Services (`2` Python 3.12 / FastAPI Services)

| Service | Port | Stack | Walkthrough | Primary Storage | Code Focus |
|---|---|---|---|---|---|
| `ai-orchestrator-service` | `8100` | Python 3.12 / LangGraph | **[ai-orchestrator-service.md](./services/ai-orchestrator-service.md)** | Redis checkpoints | 7-node LangGraph, `InjectionDetector`, `PIIVault`, `0.50 USD` / 25-call budget, `tools.py` token mismatch |
| `ai-inference-service` | `8101` | Python 3.12 / FastAPI | **[ai-inference-service.md](./services/ai-inference-service.md)** | Optional Redis/ONNX | 7 model routes, feature parity/Z-score/shadow harness, linear/`_rule_based_fallback` defaults |

---

## 5. Canonical Repository References

- [Repository README](../../../README.md) · [Ubiquitous Language & Glossary](../../../GLOSSARY.md) · [Agent Operating System](../../../AGENTS.md)
- [Documentation Hub](../../README.md) · [Architecture Decision Records (`001`–`015`)](../../adr/README.md) · [Repo-Truth Audit](../../repo-truth/README.md)
- [Gradle Module Registry (`settings.gradle.kts`)](../../../settings.gradle.kts) · [Go Workspace (`go.work`)](../../../go.work) · [CI Workflows (`.github/workflows/ci.yml`)](../../../.github/workflows/ci.yml) · [Helm Values (`deploy/helm/values.yaml`)](../../../deploy/helm/values.yaml)
- [Local Dev Bootstrap (`scripts/dev-local.sh`)](../../../scripts/dev-local.sh) · [Consistency Scanner (`scripts/check-monorepo-consistency.sh`)](../../../scripts/check-monorepo-consistency.sh)

---

**Previous:** [07 — Dependency Matrix & Cheatsheet](../07-service-dependency-and-command-cheatsheet.md) | **Onboarding Hub:** [InstaCommerce Onboarding Hub](../README.md) | **Next:** [01 — Whole-System High-Level Design (HLD)](./01-system-hld.md)
