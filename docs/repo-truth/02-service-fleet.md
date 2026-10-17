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
| `mobile-bff-service` | Java | Scaffold | README explicitly says scaffold; source tree is effectively absent | Deploy shape exists before feature-complete aggregator code |
| `admin-gateway-service` | Java | Scaffold | README explicitly says scaffold; no meaningful checked-in downstream logic | High-risk if treated as production-ready admin plane |

Main architectural takeaway: the identity core is real, but both edge gateways are ahead in docs and deploy artifacts relative to code.

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
| `go-shared` | Go library | Implemented | Common Kafka, auth, observability packages | Shared blast radius for Go services |

This plane is where many long-term maintenance costs live:

- message compatibility
- replay safety
- lag/backpressure
- cross-language observability consistency

## C9: AI / ML Platform

| Service / Area | Stack | Maturity | Evidence | Notes |
|---|---|---|---|---|
| `ai-orchestrator-service` | Python | Transitional | Legacy `app/main.py` plus `/v2` LangGraph handlers | Two orchestration styles coexist |
| `ai-inference-service` | Python | Implemented | FastAPI app, model registry, caching, feature-store hooks | Real online scoring gateway |
| `data-platform/` | Python + SQL | Implemented | dbt models, Airflow DAGs, Beam pipelines, GX suites | Real data engineering assets |
| `ml/` | Python | Implemented with roadmap elements | train/eval/serving/feature store code exists, README includes aspirational statements | Productionization is partially ahead of artifact reality |

Important AI detail:

- [`../services/ai-orchestrator-service/app/main.py`](../../services/ai-orchestrator-service/app/main.py) contains the active legacy flow and request models
- [`../services/ai-orchestrator-service/app/api/handlers.py`](../../services/ai-orchestrator-service/app/api/handlers.py) and [`../services/ai-orchestrator-service/app/graph/graph.py`](../../services/ai-orchestrator-service/app/graph/graph.py) implement the newer `/v2` LangGraph path

That is a transition, not yet a single settled architecture.

## Contracts Surface

The contract layer is not huge, but it is central:

- Protos for catalog, inventory, payment, and shared money types
- JSON schemas for orders, payments, inventory, fulfillment, identity, fraud, rider, routing, and warehouse events

Representative file:

- [`../contracts/src/main/proto/inventory/v1/inventory_service.proto`](../../contracts/src/main/proto/inventory/v1/inventory_service.proto)

Architectural implication: contract governance matters disproportionately because both synchronous and asynchronous integration surfaces depend on a relatively small shared source tree.
