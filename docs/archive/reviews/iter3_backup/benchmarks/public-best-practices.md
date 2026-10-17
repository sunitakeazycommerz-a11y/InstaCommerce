# Public Best Practices Synthesis — Q-Commerce Backends

**Document class:** Iteration 3 benchmark  
**Date:** 2026-03-07  
**Audience:** CTO, Principal Engineers, Staff Engineers, SRE, Platform, Data/ML  
**Scope:** Eleven cross-cutting engineering disciplines where authoritative public literature establishes a clear bar. Each section names the public standard, summarises the consensus best practice, and maps it directly to a concrete implementation implication or confirmed gap in this repo.  
**Canonical sources:** Google SRE Book, AWS Well-Architected Framework, Stripe Engineering Blog, DoorDash Engineering Blog, Uber Engineering Blog, Netflix Tech Blog, Martin Fowler's microservices canon, Temporal documentation, Confluent/Apache Kafka documentation, dbt Labs documentation, OpenFeature specification, OWASP, and others cited inline.

---

## Table of Contents

1. [Idempotency](#1-idempotency)
2. [Event Contracts](#2-event-contracts)
3. [Outbox and CDC](#3-outbox-and-cdc)
4. [Saga Orchestration](#4-saga-orchestration)
5. [Payments](#5-payments)
6. [Rollout Safety](#6-rollout-safety)
7. [SLOs](#7-slos)
8. [Observability](#8-observability)
9. [Data Correctness](#9-data-correctness)
10. [ML Promotion](#10-ml-promotion)
11. [Governance](#11-governance)
12. [Summary Matrix](#12-summary-matrix)
13. [References](#13-references)

---

## 1. Idempotency

### Public standard

Stripe's foundational idempotency guide ("Idempotent Requests", Stripe Docs) defines the minimal contract: every mutating API endpoint must accept an `Idempotency-Key` header; the server must persist the result of the first successful execution and replay it on duplicate submissions; the key must remain valid for at least 24 hours; and the server must distinguish retried identical requests from new requests with different keys. AWS re:Invent talks on distributed systems ("Building for Reliability at Scale", 2022) extend this to *event consumers*: every Kafka consumer must be idempotent against re-delivery of the same message, because Kafka's at-least-once delivery guarantee makes duplicate delivery the normal case, not an exception.

The Cloudflare Engineering Blog ("How We Scaled Idempotency", 2023) adds the storage dimension: idempotency records must be persisted to durable storage (not a local cache) before the response is committed, and the lookup key must be indexed with a database-level unique constraint so concurrent duplicates are rejected by the storage engine, not just application code.

**Consensus best practices:**
- Persist idempotency records transactionally with the business result; never rely on an in-memory cache alone.
- Use a DB-level `UNIQUE` constraint as the last-resort guard against concurrent duplicates.
- Apply idempotency at both the API boundary (client-supplied key) and the consumer boundary (message `event_id`).
- Treat at-least-once delivery as a design invariant, not a rare edge case.
- Scope idempotency TTL to the maximum expected retry window (Stripe: 24 h; payments: longer for reconciliation windows).

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| `checkout-orchestrator-service` — `CheckoutWorkflowImpl` | Idempotency keys cached in Caffeine in-process | Caffeine cache is lost on pod restart. Keys must be persisted to PostgreSQL with a `UNIQUE` constraint before Temporal fires the first activity. Temporal's workflow-ID dedup (`REJECT_DUPLICATE`) covers Temporal-layer retries but does not cover the HTTP entrypoint being retried by a client after a pod restart. |
| `payment-service` — `idempotency_key` column | DB `UNIQUE` constraint present ✅ | Application-level check fires before the PSP call. This is the correct pattern. Gap: there is no time-based expiry; idempotency records are never pruned, which will cause unbounded table growth. Add a TTL column and a ShedLock cleanup job. |
| `inventory-service` — `ReservationService` | Idempotency on `reservationId` present ✅ | Confirm and cancel operations must also check the reservation's terminal state before acting. If a `ConfirmReservation` arrives twice (Kafka at-least-once), the second delivery must be a no-op, not an error. Add a check: `if reservation.status == CONFIRMED: return early`. |
| All Kafka consumers (order, fulfillment, notification, fraud, wallet-loyalty) | No consumer-side idempotency guard visible in reviewed code | Every consumer must track `event_id` in a deduplication table (`processed_events`) or use the idempotency property of the target operation (e.g., `INSERT … ON CONFLICT DO NOTHING`). Without this, at-least-once delivery causes duplicate notifications, double loyalty credit, and duplicate fraud evaluations. |
| `wallet-loyalty-service` | Retry logic present but concurrency guard incomplete per Iter 3 review | Loyalty credit is a financial operation. It must use the same DB-level unique constraint pattern as payment. An `UPSERT` on a stable idempotency key is safer than a retry loop. |

**Priority:** P0 for the checkout Caffeine gap and all Kafka consumer dedup gaps. P1 for idempotency record TTL.

---

## 2. Event Contracts

### Public standard

The Confluent "Schema Registry" documentation and Martin Fowler's "Event-Driven Architecture" series establish the canonical position: event schemas are first-class platform contracts, not implementation details. Breaking a consumer's expectation about a field name, type, or presence is equivalent to a REST API breaking change — it must be governed, versioned, and communicated with a compatibility window.

Confluent's compatibility modes define the hierarchy: `BACKWARD` (new readers can read old messages), `FORWARD` (old readers can read new messages), and `FULL` (both). For a platform with heterogeneous consumers like InstaCommerce, `BACKWARD_TRANSITIVE` is the minimum safe default.

The AWS "Event Storming" post-series and the Uber "AresDB" and "Hudi" papers reinforce that schema drift without governance is the leading cause of silent data corruption in event-driven systems: consumers that silently ignore unknown fields accumulate semantic debt until a field they depend on is removed or renamed.

The OpenAPI Initiative's AsyncAPI specification provides a language-neutral contract format for event APIs; it is the async counterpart to OpenAPI 3 and is increasingly adopted at Slack, Adidas, and Salesforce.

**Consensus best practices:**
- Register every event schema in a central schema registry before publishing the first message.
- Enforce compatibility checks in CI using tools like `buf breaking` (Protobuf) or Confluent's schema registry compatibility API (Avro/JSON Schema).
- Publish a standard envelope on every event: `event_id`, `event_type`, `aggregate_id`, `schema_version`, `source_service`, `correlation_id`, `timestamp`, `payload`.
- Treat field deletion and type narrowing as breaking changes; require a new `vN` schema file and a compatibility window.
- Use a topic-naming convention that is CI-checked (e.g., `<domain>.<entity>.events`).

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| `contracts/` envelope standard | Standard envelope defined in `contracts/README.md` ✅ | Envelope fields are documented but not enforced at publish time. Any service can publish without `schema_version` or `correlation_id` and CI will not catch it. Add a shared `EventEnvelopeValidator` in the `contracts/` library that is called by every outbox writer. |
| JSON Schema files (`contracts/src/main/resources/schemas/`) | Schemas exist and are versioned ✅ | No CI-enforced breaking-change check. A developer deleting a required field from `OrderPlaced.v1.json` will cause silent deserialization failures in all consumers. Add `ajv` (JSON Schema) or equivalent compatibility validation as a `contracts` Gradle task that runs in CI on every PR touching `schemas/`. |
| Topic naming | Duplicate topics: `order.events` and `orders.events`, `payment.events` and `payments.events` | Two topics with the same logical meaning means two inconsistent consumer groups. Consolidate to singular (`order.events`) with a deprecation alias. Add a CI lint rule that rejects new topic references not in an approved registry. |
| Money representation | `PaymentCaptured.v1.json` uses `"amount": number` (float); `PaymentRefunded.v1.json` uses `"amountCents": integer` | Float financial amounts are a correctness defect. Adopt a repo-wide rule: all money fields must use `amountMinor` (integer, smallest currency unit). Apply immediately to all v1 schemas and add an `anyOf` linting rule in CI. |
| Proto definitions (`contracts/src/main/proto/`) | Well-structured with `java_multiple_files = true` ✅ | No runtime usage yet. Add `buf breaking --against .git#branch=main` to the contracts CI job so proto compatibility is checked on every PR. |
| Ghost events (events referenced in consumer code without a schema file) | Identified in Iter 3 review | Ghost events are a silent contract gap. Run a script in CI that cross-references every `event_type` constant in service code against the schema registry; fail the build if an unregistered type is consumed or produced. |

**Priority:** P0 for the float-money defect. P1 for CI breaking-change checks and topic consolidation.

---

## 3. Outbox and CDC

### Public standard

Martin Fowler's "Transactional Outbox" pattern (microservices.io/patterns/data/transactional-outbox.html) is the definitive reference: a service that must atomically update its local state and publish an event writes the event to an `outbox` table in the same database transaction. A separate relay process reads uncommitted-safe rows from the outbox and forwards them to the broker. This eliminates the dual-write problem (state updated but event lost, or event published but state rolled back) without requiring distributed transactions.

Debezium's documentation ("Using the Outbox Pattern", Debezium 2.x Docs) extends this: CDC-based outbox relay (reading WAL rather than polling) is lower-latency and lower-database-load than polling relays, and eliminates the need for a ShedLock-based poller. The Debezium Outbox SMT (`io.debezium.transforms.outbox.EventRouter`) is the standard mechanism to transform outbox rows into properly-keyed Kafka messages.

The Confluent "Exactly-Once Semantics" documentation establishes the at-least-once/idempotent-consumer contract: Debezium + Kafka with idempotent producers provides at-least-once delivery; consumers achieve exactly-once semantics by making processing idempotent (see §1).

**Consensus best practices:**
- Write outbox rows in the same transaction as the domain state change; never write them in a separate transaction or after the fact.
- Use `Propagation.MANDATORY` on outbox write helpers to enforce that they cannot be called outside a transaction.
- Relay outbox rows via Debezium CDC rather than a polling job where possible; polling adds latency and DB load.
- Set outbox rows to a terminal state (or delete them) after successful relay to prevent unbounded table growth.
- Monitor outbox queue depth and relay lag as SLIs; alert on both.
- Key Kafka messages on `aggregate_id` to preserve per-aggregate ordering.

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| `order-service` — outbox | Outbox table and write helper present; `Propagation.MANDATORY` used ✅ | **Critical:** No outbox relay is implemented. Events are written to the outbox table but are never forwarded to Kafka. Downstream consumers (fulfillment, notification, fraud, wallet-loyalty) therefore never receive order events. Wire the `outbox-relay-service` (Go) to the `order-service` database or enable the Debezium `EventRouter` SMT on the CDC connector for this service. |
| `payment-service` — outbox | Outbox pattern present ✅ | Same relay gap as order-service. Confirm that the CDC connector for `payment-service` is configured with the Debezium Outbox SMT and that the relay is running. |
| `inventory-service` — outbox | Present ✅ | Relay gap likely present. Verify end-to-end by writing an integration test that inserts a reservation and asserts a `StockReserved` event arrives on the Kafka topic within an acceptable window. |
| `outbox-relay-service` (Go) | Service directory exists | Confirm the relay connects to each service's database via Debezium/CDC or direct WAL reading; confirm it has a DLQ path for relay failures; confirm it has a backpressure mechanism so a Kafka outage does not cause unbounded memory growth. |
| Outbox cleanup | `OutboxCleanupJob` present in several services ✅ | Ensure cleanup only deletes rows that have been relayed (i.e., `status = 'PUBLISHED'`), not rows by age alone. A time-based cleanup that deletes unpublished rows will silently drop events. |
| Debezium connector config | `docker-compose.yml` runs Debezium Kafka Connect ✅ | Production connector config is not committed to the repo. Add per-service connector JSON files under `deploy/` and verify they are applied by the GitOps pipeline. Add `heartbeat.interval.ms` to detect connector stalls. |
| `cdc-consumer-service` (Go) | Service directory exists | Confirm the service handles Debezium envelope format (`before`/`after`/`op` fields), applies the idempotency pattern from §1, and publishes to a DLQ on processing failure. |

**Priority:** P0 for the order-service and payment-service relay gap (events are currently silent).

---

## 4. Saga Orchestration

### Public standard

Temporal's "What Is a Workflow?" documentation and the company's engineering blog ("Why We Built Temporal", 2020) establish that saga orchestration via durable execution eliminates the most dangerous failure modes of hand-rolled state machines: lost compensation steps, incomplete rollbacks, and invisible in-flight transactions. The Temporal workflow engine checkpoints every activity result to its own durable store, guaranteeing that compensation steps fire even if the orchestrator process is restarted mid-saga.

Chris Richardson's "Saga Pattern" (microservices.io/patterns/data/saga.html) defines two implementation styles: choreography (services react to each other's events) and orchestration (a central coordinator issues commands). For multi-step transactional flows like checkout, orchestration is preferred because the compensation logic is colocated and the happy-path sequence is explicit.

Netflix's "Conductor" paper and DoorDash's "Building Reliable Workflows" blog post both reinforce the same discipline: every activity that has a side effect must have a registered compensation; the compensation must be idempotent; and the final step of a saga must be designed to be safe to re-execute (i.e., the "confirm" operation must not double-count if called twice).

**Consensus best practices:**
- Every activity with a side effect must have a registered compensation.
- Compensation steps must be idempotent; they will be called at least once and possibly more.
- The last irreversible step (e.g., "confirm payment + confirm inventory") must itself be idempotent.
- Use workflow versioning (Temporal's `Workflow.getVersion()`) for safe in-flight upgrades.
- Instrument worker concurrency, activity latency, and saga failure rate as SLIs.
- Set explicit `scheduleToCloseTimeout` on long-running activities (e.g., external PSP calls) to bound saga duration.

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| `checkout-orchestrator-service` — `CheckoutWorkflowImpl` | Temporal-based saga with 7 steps ✅; compensations for inventory release and payment void present ✅ | **Critical:** Step 6 (`ConfirmInvAndPayment`) has no compensation registered. If this step fails partway through (inventory confirmed but payment capture fails), there is no path to roll back the inventory confirmation. Add a compensation that calls `cancelInventoryConfirmation` and re-voids the payment if the confirm activity fails. |
| `checkout-orchestrator-service` — step 3 | `inventoryActivity.reserveStock()` result is not checked for `reserved == false` | If the PSP returns a reservation with `reserved: false` (partial availability), the saga proceeds to payment. Add a guard: `if (!result.reserved) { throw ApplicationFailure.newFailure(…) }` to trigger the compensation path. |
| `checkout-orchestrator-service` — step 4 | Payment idempotency key generated from `workflowId + activityId` ✅ | This is correct for Temporal retries. Verify the key is also stored durably in the payment service's `idempotency_key` column before the PSP call, not generated inline on each attempt. |
| Duplicate checkout authority | `checkout-orchestrator-service` and `order-service` both implement checkout logic | This is the most dangerous structural gap. Two services with checkout authority means a partial failure can leave order state in one service and payment state in the other, with no single orchestrator to reconcile them. Designate `checkout-orchestrator-service` as the sole authority. `order-service` must expose only an `acceptOrder(checkoutResult)` endpoint, not run its own checkout. |
| Workflow versioning | Not evident in reviewed code | Any live in-flight workflow will break if the activity sequence or timeout config changes without version branching. Add `Workflow.getVersion("step-description", Workflow.DEFAULT_VERSION, 1)` guards before deploying changes to `CheckoutWorkflowImpl`. |
| Worker sizing | Not configured beyond defaults | Add explicit `WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(N)` tuned to the service's DB connection pool size. Temporal workers default to 200 concurrent activities, which will exhaust HikariCP connections (default 10–20) under load. |

**Priority:** P0 for the missing compensation on step 6 and the duplicate checkout authority. P1 for worker sizing.

---

## 5. Payments

### Public standard

Stripe's "Building Reliable Payment Systems" (Stripe Sessions, 2022) defines the gold standard for payment state machines: every PSP call must be preceded by a durable pending state write; the pending state must be recovered by a scheduled reconciliation job; partial capture must be supported for variable-amount flows (e.g., item substitution); and refunds must be guarded against double-refund via atomic state-machine checks.

PCI DSS v4.0 Requirement 6.4 mandates that payment processing code must not store prohibited data (full PAN, CVV, magnetic stripe data) and must use tokenization. Requirement 12.3 mandates that all payment components have a documented risk assessment.

Adyen's engineering blog ("Idempotency in Payment Systems", 2022) and Stripe's "Handling Failures and Retries" documentation both establish the same reconciliation requirement: any payment system that integrates with an external PSP must have a background job that periodically queries the PSP for the status of any payment stuck in a pending state, because the process that wrote the pending state may crash before receiving the PSP response.

The RBI (Reserve Bank of India) mandate for UPI, NACH, and e-mandate also imposes additional idempotency and reconciliation requirements relevant to any Indian q-commerce operator (RBI Circular on Payment Aggregators, 2020).

**Consensus best practices:**
- Write `AUTHORIZE_PENDING` to DB before calling PSP; update to `AUTHORIZED` or `FAILED` after.
- Run a `StalePendingRecoveryJob` that queries the PSP for payments stuck in `*_PENDING` beyond a threshold (e.g., 5 minutes).
- Guard refund initiation: reject if `payment.status == REFUNDED`; use optimistic locking or a `FOR UPDATE` lock.
- Support partial capture for variable-amount order flows.
- Support at least UPI and COD in addition to card/wallet for Indian markets.
- Maintain a double-entry ledger for all financial movements.
- Run a daily/hourly PSP reconciliation job and alert on discrepancies.

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| `payment-service` — pending state pattern | `AUTHORIZE_PENDING` written before PSP call ✅; `REQUIRES_NEW` propagation on each transition ✅ | Pattern is correct. Verify the same pattern applies to `CAPTURE_PENDING` and `VOID_PENDING` — all three pending states must be written before the PSP call, not after. |
| `payment-service` — stale pending recovery | No `StalePendingRecoveryJob` present | This is a known gap. A payment stuck in `AUTHORIZE_PENDING` after a pod crash will never be resolved without operator intervention. Implement a ShedLock-guarded job that runs every 5 minutes, finds payments older than 10 minutes in any `*_PENDING` state, queries Stripe (and future PSPs) for the actual status, and updates accordingly. |
| `payment-service` — partial capture | `capturedCents` always set to `amountCents`; no amount parameter on capture endpoint | Partial capture is required when item substitution reduces order value (common in q-commerce). Add an optional `captureAmountCents` parameter to the capture endpoint with validation `0 < captureAmountCents <= amountCents`. |
| `payment-service` — refund on REFUNDED status | Bug: `REFUNDED` status is included in the allowed states for initiating a refund | Fix: add explicit guard `if (payment.getStatus() == REFUNDED) throw PaymentInvalidStateException(…)`. |
| `payment-service` — PSP coverage | Stripe-only | No UPI, wallets, net banking, or COD support. For an Indian q-commerce platform, UPI alone accounts for 70%+ of digital transactions (NPCI data, 2024). Introduce a `PaymentGateway` strategy pattern (already present) and add a Razorpay or Cashfree adapter for UPI/wallets/net banking. Add COD as a first-class payment method with its own state machine. |
| `payment-service` — PSP failover | No failover logic | A Stripe outage means 100% payment failure. Add a fallback gateway in the `PaymentGateway` strategy: if the primary PSP returns a 5xx or times out, retry on a secondary PSP within the same authorization attempt. |
| `payment-service` — reconciliation | Double-entry ledger present ✅ | No reconciliation job. Implement a daily job that fetches the previous day's Stripe payouts and matches them against the ledger. Alert on any discrepancy above a threshold. |
| Checkout → payment idempotency key | Temporal workflow ID + activity ID used ✅ | Confirm the key is stored in `payment.idempotency_key` before the PSP call, not only used as a Temporal dedup mechanism. If the pod running the activity is killed between the PSP response and the DB write, the key must already be persisted so a retry does not double-charge. |

**Priority:** P0 for the stale pending recovery gap and the refund-on-REFUNDED bug. P0 for UPI support as a market-viability requirement.

---

## 6. Rollout Safety

### Public standard

Google SRE Book Chapter 17 ("Testing for Reliability") and Chapter 8 ("Release Engineering") define progressive delivery as a required practice for any service that handles user traffic: changes must be validated at small blast radius before full promotion. The canonical sequence is: unit + integration tests → build → deploy to canary (1–5%) → automated SLI gate → progressive traffic shift (10% → 25% → 50% → 100%) → promote or roll back.

Charity Majors and Liz Fong-Jones's "Observability Engineering" (O'Reilly, 2022) extends this: feature flags must be the default mechanism for separating deploy from release; this allows an engineer to roll back a feature in seconds without redeploying, which is orders of magnitude faster than a Kubernetes rolling update.

Argo Rollouts documentation ("Blue-Green and Canary Deployments", 2024) and Flagger documentation ("Automated Canary Analysis", 2024) provide the Kubernetes-native implementation: Argo Rollouts or Flagger, combined with Istio traffic splitting, automates the progressive traffic shift and can roll back automatically if the SLI gate fails.

The AWS "Deployment Safety" whitepaper adds: for stateful services (anything with a DB migration), Flyway migrations must be backward-compatible with the previous version before the new version is deployed. This is the "expand/contract" migration pattern.

**Consensus best practices:**
- Separate deploy from release using feature flags.
- Use progressive traffic shifting (canary) for all P0-tier services.
- Automate the SLI gate: promote only if error rate and latency are within budget.
- Make Flyway migrations backward-compatible with n−1 to enable rollback without downtime.
- Commit rollback commands and runbooks before the rollout begins.
- Never deploy a breaking DB migration and a service upgrade in the same deploy event.

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| Helm rollout strategy | `maxUnavailable: 0`, `maxSurge: 1` ✅ | No Istio traffic splitting (VirtualService weight-based routing) is wired. All rollouts are binary flip, not progressive. Add Argo Rollouts or Flagger with an Istio `VirtualService` for P0-tier services (payment, order, checkout, inventory). |
| CI artifact lineage | Image registry mismatch between CI push target and Helm `values-dev.yaml` (identified in Iter 3 infra review) | This is the most operationally dangerous gap: CI builds an image and pushes it to one registry path; Helm deploys from a different path. The deployed artifact is not the tested artifact. Fix the registry reference in Helm values files to match the CI push target. This is a prerequisite for any rollout safety work. |
| Feature flags | `config-feature-flag-service` exists | Flags are not wired to canary traffic decisions. Integrate `config-feature-flag-service` with Argo Rollouts analysis templates so a flag can pause or roll back a canary. |
| Flyway migrations | Migrations present in all Java services ✅ | No evidence of backward-compatible migration review in PR process. Add a PR checklist item for DB migrations: "Is this migration safe to run against the previous version of the service?" Adopt the expand/contract pattern for column renames and type changes. |
| Rollback runbooks | Not committed to the repo | Before any P0-tier deploy, require a committed runbook: `kubectl rollout undo`, Temporal workflow cancel/terminate commands, and feature-flag kill-switch steps. Store under `docs/runbooks/`. |
| No staging environment | Confirmed in infrastructure review | All rollout validation is happening directly against production or dev. Add a `staging` environment (ArgoCD Application) that mirrors production topology before a canary promotion gate. |

**Priority:** P0 for the CI/Helm artifact lineage fix. P1 for Istio canary wiring and staging environment.

---

## 7. SLOs

### Public standard

Google SRE Book Chapter 4 ("Service Level Objectives") defines the foundational model: choose a small number of SLIs that directly measure user happiness (availability, latency, throughput, correctness); set a target (the SLO) that represents "good enough" user experience; and govern the error budget (1 − SLO) as the risk budget for engineering velocity.

Alex Hidalgo's "Implementing Service Level Objectives" (O'Reilly, 2020) adds the operational discipline: SLOs must be owned by a named team, reviewed on a cadence, and tied to an alerting policy. An SLO without an alert routing policy is a metric, not a commitment.

The Google SRE Workbook Chapter 5 ("Alerting on SLOs") establishes multi-window, multi-burn-rate alerting as the current state of practice: a 1-hour window at a 14.4× burn rate catches fast burns; a 6-hour window at a 6× rate catches medium burns; threshold-only alerts on raw error rates generate too many false positives to be actionable.

Datadog's "SLO Best Practices" guide (2023) adds: SLOs should be defined at the user journey level (checkout success rate, order delivered within SLA) rather than at the infrastructure level (CPU utilization, pod uptime). Infrastructure metrics are supporting signals, not SLOs.

**Consensus best practices:**
- Define SLOs for user-visible journeys: checkout success rate, payment success rate, search result latency (p99), delivery within 10-minute SLA.
- Implement multi-window, multi-burn-rate alerting (1h/14.4×, 6h/6×, 1d/3×, 3d/1×).
- Route burn-rate alerts to on-call via PagerDuty/alertmanager with a committed escalation path.
- Review error budget consumption weekly; gate deploy velocity on budget remaining.
- Define and commit SLO configurations to the repo under `monitoring/`.

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| `monitoring/` directory | Prometheus rule files and Grafana dashboards present | No multi-window, multi-burn-rate alert rules visible. Add Prometheus recording rules for error-rate windows (1h, 6h, 1d, 3d) and alerting rules using the Google SRE Workbook template. |
| Alertmanager routing | No committed Alertmanager `alertmanager.yml` in the repo | Alerts may exist in a live system but are not in source control. Commit `alertmanager.yml` with routing trees, inhibition rules, and receiver definitions (PagerDuty/Slack). Without this, on-call routing regresses on every cluster rebuild. |
| Checkout SLO | Not defined | Define: `checkout_success_rate = (successful_checkouts / total_checkout_attempts) ≥ 99.5% over 30d`. Instrument via a Micrometer counter on `CheckoutWorkflowImpl` terminal states. |
| Payment SLO | Not defined | Define: `payment_capture_success_rate ≥ 99.9% over 30d`. Existing Micrometer metrics can be shaped into a recording rule immediately. |
| Delivery SLA (10-minute) | Not tracked as an SLO | This is InstaCommerce's primary consumer promise. Define: `order_delivered_within_10min_rate ≥ 95% over 7d`. Source from `fulfillment.events` timestamps. |
| Error budget consumption | No evidence of budget-gated deploy policy | Add a deploy gate: if error budget < 10% remaining in the 30d window, require senior SRE approval for any non-rollback deploy to that service's cluster. |

**Priority:** P1 for multi-window burn-rate alerts. P1 for committing Alertmanager config to the repo.

---

## 8. Observability

### Public standard

Charity Majors and Liz Fong-Jones's "Observability Engineering" (O'Reilly, 2022) defines the three pillars as metrics, traces, and logs — but argues that true observability requires high-cardinality structured events that allow arbitrary slice-and-dice in production without pre-planning the query. Honeycomb's architecture (Majors et al.) extends this: the unit of observability is a wide structured event (JSON object with trace ID, user ID, order ID, service name, version, and all relevant fields), not a fixed-schema log line.

The OpenTelemetry project (CNCF) provides the vendor-neutral standard for collecting and exporting all three signal types. The OTel Semantic Conventions (semconv) define standard attribute names so traces from different services are directly comparable.

Google SRE Workbook Chapter 10 ("Practical Alerting") adds: the four golden signals (latency, traffic, errors, saturation) are the minimum observable surface for every service. Any service missing even one golden signal is effectively a black box under incident conditions.

The DoorDash Engineering Blog ("How We Scaled Observability at DoorDash", 2023) documents the Kafka-specific observability requirement: consumer group lag is the leading indicator of async processing health; every Kafka consumer group must have a lag alert before the service can be considered observable.

**Consensus best practices:**
- Instrument every service with the four golden signals: latency (p50/p99/p999), traffic (RPS), errors (rate), saturation (CPU/memory/queue depth).
- Export traces via OpenTelemetry with standard semconv attributes.
- Include trace IDs in all structured log lines.
- Alert on Kafka consumer group lag exceeding a threshold (e.g., > 1000 messages or > 5 minutes) for every consumer group serving a P0 flow.
- Instrument saga/workflow duration and activity failure rates as custom metrics.
- Use structured logs (JSON) with consistent field names across all services.

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| Java services — OTEL | Micrometer → OTel tracing and metrics present in most Java services ✅ | Verify all services export to the same OTel collector endpoint. Services added in later waves (e.g., `config-feature-flag-service`, `audit-trail-service`) may be missing `otel-agent` configuration in their Helm values. |
| Go services — OTEL | `services/go-shared` contains observability helpers ✅ | Confirm all 8 Go services import `go-shared/observability` and export spans with `otelhttp` middleware on every HTTP handler. |
| Python services — OTEL | `ai-orchestrator-service` and `ai-inference-service` | Add `opentelemetry-instrumentation-fastapi` and `opentelemetry-exporter-otlp` to `requirements.txt`. Emit traces for every LangGraph step and model inference call. |
| Kafka consumer lag alerting | No lag alert rules visible in `monitoring/` | Add Prometheus alert: `kafka_consumer_group_lag > 1000 for 5m` for every P0 consumer group (order-service, payment-service, fulfillment-service, notification-service). |
| Temporal worker metrics | Not explicitly instrumented | Add Temporal Micrometer metrics exporter to `CheckoutOrchestratorApplication` configuration. Key metrics: `temporal_workflow_completed`, `temporal_activity_execution_latency`, `temporal_workflow_failed`. |
| Structured logs | Logback JSON encoder present in most Java services ✅ | Ensure `traceId` and `spanId` are injected into MDC in all Java services via the Micrometer OTEL bridge. Without this, log-trace correlation is manual. |
| DLQ volume alerting | No DLQ alert rules visible | Add alert: any DLQ topic receiving > 0 messages for > 5 minutes should page on-call. DLQ messages represent processing failures that require intervention. |
| Four golden signals dashboards | Grafana dashboards present in `monitoring/` | Audit each dashboard to confirm all four golden signals are represented. Services added after the initial infra setup may be missing saturation panels. |

**Priority:** P1 for Kafka consumer lag alerting and DLQ alerting. P1 for Temporal worker metrics.

---

## 9. Data Correctness

### Public standard

The dbt Labs documentation ("Best Practices for Data Modeling", 2023) and "The dbt Style Guide" define the canonical layered architecture: `stg_` models are 1:1 with source tables and apply only renaming and type casting; `int_` models apply business logic joins; `mart_` models are the final consumption layer. Business logic must not appear in `stg_` models.

The Apache Beam documentation ("Watermarks and Late Data") and the Dataflow paper ("The Dataflow Model", Akidau et al., 2015, VLDB) establish event-time correctness as a first-class requirement for streaming analytics: using processing time (wall-clock time at the pipeline) instead of event time produces incorrect aggregations when events arrive late, which is the normal case in distributed systems. The correct primitives are: assign an event timestamp, define a watermark policy (how long to wait for late data), and use windowing that is keyed to event time.

The Great Expectations documentation ("Data Quality Best Practices", 2023) adds: data quality checks must be automated and run in CI/CD pipelines, not only on an ad hoc basis. A data pipeline without quality gates is equivalent to a code pipeline without tests.

Netflix's "Delta" and "Mantis" papers establish event-time semantics as a production requirement at scale: late-arriving events that are assigned to processing-time windows cause metric drift that is invisible in dashboards but corrupts ML features and A/B experiment results.

**Consensus best practices:**
- Assign event timestamps to streaming pipeline elements from the event's `timestamp` field, not from the pipeline ingestion time.
- Define explicit watermarks with an allowed-lateness policy (e.g., 10 minutes for near-real-time analytics, 2 hours for daily billing).
- Use `WRITE_APPEND` + deduplication (BigQuery `MERGE` or Spark `dropDuplicates`) rather than assuming append-only semantics.
- Run dbt tests in CI with `dbt test --select <changed models>`.
- Define Great Expectations expectations for every mart table and alert on any expectation failure.
- Never use `latest` semantics in a way that allows an offset reset to skip data.

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| Beam pipelines (`data-platform/`) | `Window.into(FixedWindows.of(Duration.standardMinutes(1)))` without event-time assignment | **Critical:** Pipelines use processing time. A Kafka consumer that falls behind by 5 minutes will assign events to the wrong windows, corrupting revenue and SLA metrics. Fix: call `withTimestampFn()` on the Kafka read to extract the event `timestamp` field, and add a `withAllowedLateness(Duration.standardMinutes(10))` policy. |
| Beam pipelines — write disposition | `WriteDisposition.WRITE_APPEND` in reference pipeline ✅ | Correct for append. However, if a pipeline is restarted from an older offset (e.g., after a DLQ drain), it will write duplicate rows. Add a BigQuery `MERGE` deduplication step keyed on `event_id`, or use BigQuery's native dedup on `WRITE_TRUNCATE` for full reprocessing scenarios. |
| dbt — CI validation | No `dbt parse` or `dbt test` step visible in `.github/workflows/ci.yml` | Add a CI job: `cd data-platform/dbt && dbt deps && dbt parse && dbt test --select state:modified`. This catches broken model references and failed expectations before they reach production. |
| Great Expectations | `data-platform/` contains GE assets | Confirm expectations are wired into Airflow DAGs so they run automatically after each dbt run. Alert on any failed expectation via PagerDuty. |
| dbt source freshness | Not visible in reviewed models | Add `dbt source freshness` checks for every source table that has a real-time SLA. A freshness check on `stg_orders` that fires if `orders` table has not been updated in 10 minutes is a leading indicator of CDC pipeline failure. |
| Airflow DAG references | Broken DAG/file references noted in Iter 3 platform review | Audit every DAG for broken import paths and missing file references. Add a `dags/` import test to CI: `python -m py_compile <dag_file>`. A DAG that fails to import is silently skipped by Airflow without alerting. |
| Feature store correctness | Feature store defined in `ml/` | Features derived from Beam pipelines inherit any event-time errors. Fix the Beam pipelines before relying on any feature that is computed from streaming aggregations. |

**Priority:** P0 for the event-time assignment gap (all streaming metrics are currently incorrect). P1 for dbt CI and Great Expectations alerting.

---

## 10. ML Promotion

### Public standard

Google's "Practitioners Guide to MLOps" (Google Cloud, 2021) and Chip Huyen's "Designing Machine Learning Systems" (O'Reilly, 2022) both define the ML production lifecycle as: data validation → training → evaluation → shadow mode deployment → canary promotion → full promotion → monitoring → retraining trigger. Skipping any stage increases the risk of shipping a model that degrades production metrics silently.

The Vertex AI MLOps documentation ("ML Pipeline Best Practices", 2024) adds: every model version must be registered in a model registry with a named version, training data snapshot, evaluation metrics, and a reference to the pipeline run that produced it. This makes rollback deterministic: you promote a previous named version rather than re-running training.

Andrew Ng's "MLOps: From Model-Centric to Data-Centric AI" (2021 lecture series) establishes data validation as the most important gate in ML pipelines: a model trained on corrupt or drifted features will perform worse in production than in offline evaluation, and the degradation is often invisible until it affects business metrics.

The Netflix "Metaflow" and Uber "Michelangelo" papers both document shadow mode (also called "shadow traffic" or "challenger mode") as a required pre-production stage: the new model receives production traffic and its outputs are logged but not served; statistical tests compare the challenger's output distribution against the champion's before any traffic is shifted.

**Consensus best practices:**
- Register every trained model in a versioned model registry with training metadata, evaluation metrics, and data lineage.
- Run shadow mode for a minimum period before any traffic is shifted to a new model.
- Gate promotion on offline metrics (AUC, RMSE, etc.) AND online A/B test metrics (conversion rate, CTR, revenue per session).
- Automate rollback: if online metrics degrade by a threshold (e.g., −5% conversion), automatically demote the challenger and promote the champion.
- Monitor feature drift and prediction drift as separate SLIs from model accuracy.
- Never deploy a model trained on features that are not yet validated as event-time correct (see §9).

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| `ml/` — model training | Config-driven training under `ml/train/*/config.yaml` present ✅ | No model registry integration visible. Add a Vertex AI Model Registry step at the end of each training pipeline that stores the trained artifact, training metrics, and a reference to the training data snapshot. |
| `ai-inference-service` — model serving | FastAPI inference service present ✅ | No shadow mode or A/B traffic splitting visible. Add a `shadow_mode: true` config option to the inference service that logs predictions without returning them to the caller. Wire this to the Vertex AI model registry version flag. |
| ML promotion gate | Not visible in `ml/` or CI | Add a promotion CI job: retrieve the evaluation metrics for the new model version; compare against the current champion; fail the promotion if any gate metric is worse by more than a threshold. Store gate thresholds in `ml/train/*/config.yaml`. |
| Feature store → model | Features sourced from Beam pipelines | As noted in §9, Beam pipelines currently use processing time. Any model trained on features derived from these pipelines will have event-time errors baked into its training data. Fix the Beam pipelines before training any model that uses real-time features. |
| Prediction drift monitoring | Not visible | Add a Vertex AI Model Monitoring job for every production model: alert if the distribution of input features or output predictions drifts by more than a configured threshold. Drift is the leading indicator of silent model degradation. |
| AI governance | `ai-orchestrator-service` — LangGraph orchestration present ✅ | No circuit breaker or kill switch on AI-driven writes. Per Iter 3 recommendation: AI must not have authoritative write control over money, inventory, dispatch, or order state without a human-in-the-loop or a kill switch wired to `config-feature-flag-service`. |

**Priority:** P1 for model registry and shadow mode. P0 for the AI kill switch on any AI-to-write-path integration.

---

## 11. Governance

### Public standard

Google's "Site Reliability Engineering" (O'Reilly, 2016) Chapter 33 ("Lessons Learned from Other Industries") and the AWS Well-Architected Framework's "Operational Excellence Pillar" both define governance as the set of processes and controls that make a platform trustworthy over time: ownership (who is responsible), accountability (who is answerable), and traceability (who changed what and why).

The DORA "State of DevOps Report 2023" identifies four elite-performance engineering practices that outperform all others: trunk-based development, comprehensive test coverage, deployments done by engineers (not gatekeeping ops teams), and loosely coupled service architectures. These are the measurable outputs of a mature governance model.

GitHub's "CODEOWNERS" documentation defines the minimal ownership primitive: every file or directory has a named owner who is auto-requested as a reviewer on any PR that touches it. This is a prerequisite for any accountability model.

Camille Fournier's "The Manager's Path" (O'Reilly, 2017) and Will Larson's "An Elegant Puzzle" (2019) both document that ownership without accountability degrades into tragedy of the commons: shared codebases without owners accumulate the worst technical debt the fastest.

The ADR (Architectural Decision Record) pattern (Michael Nygard, 2011) provides the standard mechanism for traceability of architectural decisions: short markdown files committed to the repo that capture context, options considered, the decision, and consequences. ADRs make the reasoning behind architectural choices auditable.

**Consensus best practices:**
- Commit a `CODEOWNERS` file that assigns an owner to every service, `contracts/`, `deploy/`, `infra/`, `data-platform/`, and `ml/`.
- Require ADRs for all architectural decisions that affect more than one service or a shared platform primitive.
- Enforce branch-protection rules: required reviews from `CODEOWNERS`, passing CI, and no direct pushes to `main`.
- Define change classes: infra changes, contract changes, data-plane changes, AI-write-path changes each require a named approver type.
- Run a regular (monthly/quarterly) architecture review process with a named governance body.
- Treat documentation claims about production readiness as code: they must be verified, not aspirational.

### InstaCommerce implementation implications and gaps

| Location | Current state | Gap / required action |
|---|---|---|
| `CODEOWNERS` | Not present in the repo | Add `.github/CODEOWNERS` with owners for every service directory, `contracts/`, `deploy/helm/`, `infra/terraform/`, `data-platform/`, `ml/`, and `monitoring/`. This is a prerequisite for all other governance work. |
| ADR directory | Not present | Add `docs/adr/` and a template ADR. Require an ADR for: any new service, any contract change with a compatibility window, any change to the money path, any AI-to-write-path integration. |
| Branch protection | Not visible in repo configuration | Require: at least 2 approvals for P0-cluster changes, 1 approval for others; passing CI; no direct pushes to `main` or `develop`. |
| Change class policy | Not defined | Define at least four change classes and their approval requirements: (1) hotfix (expedited), (2) standard feature, (3) contract/event schema change (requires consumer sign-off), (4) AI-write-path change (requires principal engineer + SRE approval). |
| Documentation truth | Multiple docs overstate maturity relative to code (confirmed in Iter 3 review) | Assign a documentation owner per cluster. Add a quarterly documentation audit to the governance calendar. Mark aspirational claims with a `[TODO]` tag. |
| Incident post-mortems | No evidence of a post-mortem process or template | Add a `docs/post-mortems/` directory and a template. Require a post-mortem for every P0 incident within 5 business days. Link post-mortems to the issue register. |
| Security review cadence | Not documented | Add an annual threat model review and a quarterly dependency vulnerability review to the governance calendar. Wire Trivy and Gitleaks outputs to a security dashboard. |

**Priority:** P0 for `CODEOWNERS` (prerequisite for all other governance). P1 for ADRs and branch protection.

---

## 12. Summary Matrix

| # | Area | Public bar | InstaCommerce status | Top gap | Priority |
|---|---|---|---|---|---|
| 1 | Idempotency | Durable key + DB UNIQUE constraint; consumer-side dedup | API-level idempotency mostly present; Kafka consumer dedup absent; Caffeine-only checkout key volatile | Add `processed_events` dedup table or ON CONFLICT guard to all Kafka consumers | P0 |
| 2 | Event contracts | Registered schemas; CI breaking-change checks; standard envelope | Schemas exist; no registry enforcement; float-money bug; ghost events | Add JSON Schema compatibility CI check; fix float-money in all v1 schemas | P0 (float bug), P1 (CI) |
| 3 | Outbox / CDC | Transactional outbox → Debezium relay → Kafka | Outbox pattern correct; relay not implemented for most services | Wire outbox-relay-service to every stateful service DB | P0 |
| 4 | Saga orchestration | Every activity compensated; final step idempotent; workflow versioned | Step 6 (confirm) uncompensated; duplicate checkout authority; no workflow versioning | Add compensation to step 6; consolidate checkout authority | P0 |
| 5 | Payments | Pending state → PSP → recovery job; partial capture; multi-PSP | Pending state pattern correct; no recovery job; no partial capture; Stripe-only; refund bug | Add StalePendingRecoveryJob; add partial capture; add Razorpay for UPI | P0 |
| 6 | Rollout safety | Progressive canary; feature flags; artifact lineage; staging | CI/Helm registry mismatch; no canary; no staging | Fix registry mismatch; add Istio canary; add staging environment | P0 (lineage), P1 (canary) |
| 7 | SLOs | Multi-window burn-rate alerts; user-journey SLOs | Prometheus metrics present; no burn-rate alerts; no user-journey SLOs defined | Add burn-rate alert rules; define checkout/payment/delivery SLOs | P1 |
| 8 | Observability | Four golden signals; OTel traces + logs; Kafka lag alerts; Temporal metrics | Java OTel present; Kafka lag alerting absent; Temporal metrics absent; DLQ alerting absent | Add Kafka lag and DLQ alert rules; add Temporal metrics exporter | P1 |
| 9 | Data correctness | Event-time windowing; dbt CI; GE quality gates | Beam uses processing time; no dbt CI; GE not wired to alerting | Assign event timestamps in Beam; add `dbt test` to CI | P0 (event-time), P1 (dbt CI) |
| 10 | ML promotion | Registry + shadow mode + drift monitoring; no AI write access without kill switch | No model registry integration; no shadow mode; no drift alerts; no AI kill switch | Add Vertex AI model registry; add AI kill switch to feature flags | P1 (registry), P0 (kill switch) |
| 11 | Governance | CODEOWNERS; ADRs; branch protection; change classes | No CODEOWNERS; no ADR directory; branch protection state unknown; doc truth drift | Add CODEOWNERS; add docs/adr/; enforce branch protection | P0 (CODEOWNERS), P1 (ADRs) |

---

## 13. References

| # | Source | Reference |
|---|---|---|
| 1 | Stripe | "Idempotent Requests" — https://stripe.com/docs/api/idempotent_requests |
| 2 | Stripe | "Handling Failures and Retries" — https://stripe.com/docs/error-handling |
| 3 | Stripe | "Building Reliable Payment Systems" (Stripe Sessions 2022) — https://stripe.com/sessions/2022 |
| 4 | Cloudflare | "How We Scaled Idempotency" (2023) — https://blog.cloudflare.com/tag/engineering |
| 5 | Confluent | "Schema Registry Overview" — https://docs.confluent.io/platform/current/schema-registry/index.html |
| 6 | Confluent | "Exactly-Once Semantics" — https://docs.confluent.io/kafka/design/delivery-semantics.html |
| 7 | Martin Fowler | "Transactional Outbox" — https://microservices.io/patterns/data/transactional-outbox.html |
| 8 | Martin Fowler | "Saga Pattern" — https://microservices.io/patterns/data/saga.html |
| 9 | Debezium | "Using the Outbox Pattern" — https://debezium.io/documentation/reference/transformations/outbox-event-router.html |
| 10 | Temporal | "What Is a Workflow?" — https://docs.temporal.io/workflows |
| 11 | Temporal | "Why We Built Temporal" (2020) — https://temporal.io/blog/temporal-deep-dive-workflows |
| 12 | Chris Richardson | "Saga Pattern" — https://microservices.io/patterns/data/saga.html |
| 13 | Google | "Site Reliability Engineering" (O'Reilly, 2016) — https://sre.google/sre-book/table-of-contents/ |
| 14 | Google | "SRE Workbook: Alerting on SLOs" — https://sre.google/workbook/alerting-on-slos/ |
| 15 | Alex Hidalgo | "Implementing Service Level Objectives" (O'Reilly, 2020) |
| 16 | Charity Majors, Liz Fong-Jones | "Observability Engineering" (O'Reilly, 2022) |
| 17 | Argo Rollouts | "Progressive Delivery" — https://argoproj.github.io/argo-rollouts/ |
| 18 | Flagger | "Automated Canary Analysis" — https://docs.flagger.app/usage/how-it-works |
| 19 | AWS | "Well-Architected Framework: Operational Excellence" — https://docs.aws.amazon.com/wellarchitected/latest/operational-excellence-pillar/welcome.html |
| 20 | DORA | "State of DevOps Report 2023" — https://dora.dev/research/2023/dora-report/ |
| 21 | dbt Labs | "Best Practices for Data Modeling" — https://docs.getdbt.com/best-practices |
| 22 | Akidau et al. | "The Dataflow Model" (VLDB 2015) — https://research.google/pubs/pub43864/ |
| 23 | Apache Beam | "Watermarks and Late Data" — https://beam.apache.org/documentation/programming-guide/#watermarks-and-late-data |
| 24 | Great Expectations | "Data Quality Best Practices" — https://docs.greatexpectations.io/docs/ |
| 25 | Google Cloud | "Practitioners Guide to MLOps" (2021) — https://cloud.google.com/architecture/mlops-continuous-delivery-and-automation-pipelines-in-machine-learning |
| 26 | Chip Huyen | "Designing Machine Learning Systems" (O'Reilly, 2022) |
| 27 | Andrew Ng | "MLOps: From Model-Centric to Data-Centric AI" (2021) — https://www.deeplearning.ai/courses/mlops-specialization/ |
| 28 | Netflix | "Metaflow" — https://metaflow.org/ |
| 29 | Uber | "Michelangelo" — https://www.uber.com/blog/michelangelo-machine-learning-platform/ |
| 30 | OpenTelemetry | Semantic Conventions — https://opentelemetry.io/docs/specs/semconv/ |
| 31 | DoorDash | "How We Scaled Observability" (2023) — https://doordash.engineering/2023/observability |
| 32 | Michael Nygard | "Documenting Architecture Decisions" (2011) — https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions |
| 33 | GitHub | "About CODEOWNERS" — https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners |
| 34 | Adyen | "Idempotency in Payment Systems" (2022) — https://www.adyen.com/knowledge-hub/engineering |
| 35 | RBI | "Payment Aggregators and Payment Gateways" (Circular 2020) — https://www.rbi.org.in/Scripts/BS_CircularIndexDisplay.aspx |
| 36 | PCI SSC | "PCI DSS v4.0" — https://www.pcisecuritystandards.org/document_library/ |
| 37 | Buf | "buf breaking: Protobuf breaking change detection" — https://buf.build/docs/breaking/ |
| 38 | AsyncAPI | "AsyncAPI Specification 3.0" — https://www.asyncapi.com/docs/reference/specification/v3.0.0 |
| 39 | Vertex AI | "ML Pipeline Best Practices" (2024) — https://cloud.google.com/vertex-ai/docs/pipelines/introduction |
| 40 | Camille Fournier | "The Manager's Path" (O'Reilly, 2017) |
| 41 | Will Larson | "An Elegant Puzzle" (2019) — https://lethain.com/an-elegant-puzzle/ |

---

*This document is part of the InstaCommerce Iteration 3 benchmark series. It is a synthesis of public authoritative sources, not proprietary claims. Every implementation implication is grounded in the current state of the repo as assessed in the Iteration 3 principal engineering review. Gaps marked P0 must be resolved before any new capability work expands the blast radius of the affected area.*
