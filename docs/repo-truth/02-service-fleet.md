# Service Fleet

This section summarizes the service fleet by cluster and distinguishes between:

- `Implemented`: substantial checked-in source, config, and persistence/workflow surfaces
- `Scaffold`: docs/build/deploy shape exist, but runtime logic is minimal
- `Transitional`: implemented, but with competing patterns or partial target-state rollout

Implementation depth is based on repository structure, service READMEs, migrations, and representative source inspection.

## C1: Edge and Identity

| Service | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `identity-service` | Java | Implemented | 65 Java files, 11 migrations, strong auth focus in docs/code layout | Trust anchor for JWT/JWKS and user lifecycle |
| `mobile-bff-service` | Java | Implemented | WebFlux security chain, RS256 JWT validation, `HomeAggregator` parallel fan-out, Resilience4j timeouts/fallbacks, unit + integration tests | Hardened customer BFF gateway on `:8080`; Wave-5 adds product/cart/tracking/checkout aggregators |
| `admin-gateway-service` | Java | Implemented | Spring MVC security chain, RS256 JWT (`aud=instacommerce-admin`), `ROLE_ADMIN` RBAC, `AdminDashboardController`, unit + integration tests | Hardened admin gateway on `:8099`; Wave-5 adds flag/fraud/audit endpoints |

Main architectural takeaway: the identity core and both edge gateways implement JWT validation, resilience patterns, and downstream fan-out/proxying, while screen and admin endpoint coverage continues expanding iteratively.

## C2: Transactional Core

| Service | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `checkout-orchestrator-service` | Java + Temporal | Implemented | Workflow, activities, security, idempotency cleanup | One of the strongest modules in the repo |
| `order-service` | Java | Implemented | 59 Java files, 11 migrations, state machine, outbox, audit hooks | Owns lifecycle truth after checkout |
| `payment-service` | Java | Implemented | 88 Java files, 19 migrations, gateway abstractions, webhook/event handling | Mature money path with recovery jobs |
| `payment-webhook-service` | Go | Implemented | Signature validation, dedupe, Kafka publishing | Explicit ingress isolation for PSP callbacks |
| `reconciliation-engine` | Go | Implemented | Scheduler, DB reconciler, Kafka publisher, CDC hooks | Important financial correctness backstop |

Representative code:

- [`../services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflowImpl.java`](../../services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflowImpl.java)
- [`../services/order-service/src/main/java/com/instacommerce/order/service/OrderService.java`](../../services/order-service/src/main/java/com/instacommerce/order/service/OrderService.java)
- [`../services/payment-service/src/main/java/com/instacommerce/payment/service/PaymentService.java`](../../services/payment-service/src/main/java/com/instacommerce/payment/service/PaymentService.java)
- [`../services/payment-webhook-service/main.go`](../../services/payment-webhook-service/main.go)

## C3: Read and Decision Plane

| Service | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `catalog-service` | Java | Implemented | 80 Java files, 11 migrations | High implementation depth by file structure |
| `search-service` | Java | Implemented | Kafka consumers, cache, projection repo, FTS query layer | Uses PostgreSQL `tsvector`, not Elasticsearch |
| `pricing-service` | Java | Implemented | 60 Java files, 8 migrations | Critical dependency for checkout and cart |
| `cart-service` | Java | Implemented | 39 Java files, 5 migrations | Core pre-checkout state holder |

Search reality is especially clear in:

- [`../services/search-service/src/main/java/com/instacommerce/search/service/SearchService.java`](../../services/search-service/src/main/java/com/instacommerce/search/service/SearchService.java)

That code uses repository-backed full-text queries, autocomplete, trending writes, and cache annotations.

## C4: Inventory and Dark Store

| Service | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `inventory-service` | Java | Implemented | Reservation locking, low-stock events, outbox, cleanup jobs | Strong concurrency-aware implementation |
| `warehouse-service` | Java | Implemented | 41 Java files, 8 migrations, strong README detail | Important caveat: store identity consistency with inventory remains a known gap |

Inventory reservation logic is one of the better examples of deliberate consistency handling in the repo:

- sorted line-item locking
- pessimistic writes
- reservation TTL
- explicit release/expire/confirm transitions
- low stock event emission

## C5: Fulfillment and Logistics

| Service | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `fulfillment-service` | Java | Implemented | 84 Java files, consumers, pick/delivery services, outbox | Major state transition owner after order placement |
| `rider-fleet-service` | Java | Implemented | 56 Java files, 10 migrations | Core rider availability and assignment state |
| `routing-eta-service` | Java | Implemented | 40 Java files, 7 migrations | ETA and delivery/tracking APIs |
| `dispatch-optimizer-service` | Go | Implemented | Real HTTP API, constraints, metrics, tracing | Stateless optimization engine |
| `location-ingestion-service` | Go | Implemented | HTTP/WebSocket ingestion, Redis + Kafka batching | Actual ingest mode differs from some higher-level docs |

The logistics plane is broad and real, but it is operationally expensive because it spans:

- synchronous calls
- event consumers
- Redis live state
- rider location streaming
- solver-style dispatch optimization

## C6: Customer and Engagement

| Service | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `notification-service` | Java | Implemented | 50 Java files, 3 migrations | Broad fan-out dependency from many domains |
| `wallet-loyalty-service` | Java | Implemented | 64 Java files, 11 migrations, double-entry ledger in README/code structure | Strong ownership boundary around balance and points |
| `fraud-detection-service` | Java | Implemented | 50 Java files, 8 migrations | Important policy and risk gate |

`wallet-loyalty-service` is notable because it combines:

- REST APIs
- event consumers
- ledger accounting
- scheduled expiry/cleanup work
- downstream service lookups

That makes it business-critical and operationally subtle even if it is not always treated as a Tier 0 path.

## C7: Platform Foundations

| Service | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `audit-trail-service` | Java | Implemented | 29 Java files, Kafka + REST ingestion, partition maintenance | Strong compliance and forensic role |
| `config-feature-flag-service` | Java | Implemented | 59 Java files, 6 migrations | Central runtime influence over other services |

These services are cross-cutting force multipliers:

- if `audit-trail-service` breaks, forensic confidence degrades
- if `config-feature-flag-service` drifts or becomes inconsistent, behavior becomes hard to reason about fleet-wide

## C8: Event and Data Plane

| Service | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `outbox-relay-service` | Go | Implemented | Poll loop, Kafka producer, health/readiness, telemetry | Key bridge between OLTP and Kafka |
| `cdc-consumer-service` | Go | Implemented | Kafka readers, batcher, BigQuery inserter, DLQ handling | Key bridge into analytics |
| `stream-processor-service` | Go | Implemented | Topic consumers, dedupe, Redis, Prometheus | Operational metrics layer |
| `reverse-etl-orchestrator` | Go | Implemented | BigQuery export/activation pipelines to Redis and PostgreSQL | Data mesh reverse-ETL bridge (`:8109`) |
| `go-shared` | Go library | Implemented | Common Kafka, auth, observability packages | Shared blast radius for Go services |

This plane is where many long-term maintenance costs live:

- message compatibility
- replay safety
- lag/backpressure
- cross-language observability consistency

## C9: AI / ML Platform

| Service / Area | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `ai-orchestrator-service` | Python | Implemented | Unified `ExecutionService` + LangGraph serving `v1_router` and `v2_router` with `app/guardrails/` | Runtime guardrails (`InjectionDetector`, `PIIVault`, `OutputPolicy`, `EscalationPolicy`, `TokenBucketRateLimiter`) enforced on `:8100` |
| `ai-inference-service` | Python | Implemented | FastAPI app, model registry, caching, feature-store hooks | Real online scoring gateway on `:8101` |
| `data-platform/` | Python + SQL | Implemented | dbt models, Airflow DAGs, Beam pipelines, GX suites | Real data engineering assets |
| `ml/` | Python | Implemented with roadmap elements | train/eval/serving/feature store code exists, README includes aspirational statements | Productionization is partially ahead of artifact reality |

Important AI detail:

- [`../services/ai-orchestrator-service/app/main.py`](../../services/ai-orchestrator-service/app/main.py) mounts both `v1_router` ([`app/api/v1.py`](../../services/ai-orchestrator-service/app/api/v1.py)) and `v2_router` ([`app/api/handlers.py`](../../services/ai-orchestrator-service/app/api/handlers.py))
- [`../services/ai-orchestrator-service/app/api/execution.py`](../../services/ai-orchestrator-service/app/api/execution.py) and [`../services/ai-orchestrator-service/app/graph/graph.py`](../../services/ai-orchestrator-service/app/graph/graph.py) back both surfaces through a unified LangGraph + `ExecutionService` runtime and `app/guardrails/` pipeline.

## Contracts Surface

The contract layer is not huge, but it is central:

- Protos for catalog, inventory, payment, and shared money types
- JSON schemas for orders, payments, inventory, fulfillment, identity, fraud, rider, routing, and warehouse events

Representative file:

- [`../contracts/src/main/proto/inventory/v1/inventory_service.proto`](../../contracts/src/main/proto/inventory/v1/inventory_service.proto)

Architectural implication: contract governance matters disproportionately because both synchronous and asynchronous integration surfaces depend on a relatively small shared source tree.
