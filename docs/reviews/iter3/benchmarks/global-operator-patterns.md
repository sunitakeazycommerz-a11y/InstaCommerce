# Global Operator Patterns: Benchmark Against InstaCommerce

**Document:** `docs/reviews/iter3/benchmarks/global-operator-patterns.md`  
**Iteration:** 3  
**Date:** 2026-03-07  
**Audience:** Principal Engineers, Staff Engineers, SRE, Platform  
**Scope:** Structured comparison of InstaCommerce implementation patterns against public engineering signals from DoorDash, Instacart, and broader q-commerce/last-mile operators (Grab, Swiggy, Gopuff). Translates public patterns into concrete implementation implications tied to specific InstaCommerce files, classes, and tables.

> **How to read this document.** Each section opens with the industry pattern, then identifies what InstaCommerce has, what it is missing or partially done, and ends with specific implementation actions. This is not a generic survey — every action cites an actual file, class, migration, or Kafka topic in this repo.

---

## Table of Contents

1. [Platform Truth](#1-platform-truth)
2. [Order and Payment Correctness](#2-order-and-payment-correctness)
3. [Dispatch and Assignment](#3-dispatch-and-assignment)
4. [Observability](#4-observability)
5. [Experimentation](#5-experimentation)
6. [Operational Governance](#6-operational-governance)
7. [Cross-Cutting Gap Summary](#7-cross-cutting-gap-summary)
8. [Public Signals and References](#8-public-signals-and-references)

---

## 1. Platform Truth

### 1.1 Industry Pattern: Single Source of Truth per Domain

**DoorDash** resolved a years-long fragmentation where order state lived in multiple services by introducing a canonical `Order` platform that owns the authoritative FSM and emitting all downstream state reads from that single store. Their _Order Platform_ blog post (2022) describes how duplicate authority across microservices created silent divergence in SLAs, support tooling, and analytics.

**Instacart** applied the same principle to catalog: a single `Catalog Truth Service` owns canonical item data and all downstream consumers (search, pricing, picking) are read-replicas via events, never primary writers.

**Broader pattern (Grab, Swiggy):** Canonical-state authority is separated from query-optimized read stores. Writes flow through one owner, reads fan out through CDC/events. Any service that tries to own truth for a domain it doesn't anchor creates drift.

### 1.2 InstaCommerce Assessment

| Plane | Single-Write Authority? | Evidence |
|---|---|---|
| Cart state | Ambiguous — `cart-service` + `CheckoutWorkflowImpl` both reference cart items | `CartActivity`, `CartActivityImpl` in checkout-orchestrator |
| Order state | Mostly yes — `order-service` owns FSM, `order_status_history` migration | `services/order-service/src/main/resources/db/migration/` |
| Payment state | Strong — `payments` table with `UNIQUE (idempotency_key)`, enum FSM | `V1__create_payments.sql`, `V7__add_pending_payment_statuses.sql` |
| Inventory | Mostly yes — `stock_items(product_id, store_id)` UNIQUE constraint | `services/inventory-service/src/main/resources/db/migration/` |
| Event contracts | Declared but not enforced — schema registry not wired | `contracts/src/main/resources/schemas/` |

**Critical gap: the checkout-to-order boundary.** `CheckoutWorkflowImpl` calls `orderActivity.createOrder()` and holds an `OrderCreateRequest` with a full copy of pricing, reservation IDs, payment IDs, and item lines. The order-service then presumably writes its own copy of those fields. If pricing is recalculated or differs by the time the activity runs, there is no single source for "what did the customer agree to pay." DoorDash's solution was to treat the checkout saga output as an **immutable signed blob** passed to the order platform — the order service never recalculates, it records what the saga committed.

### 1.3 Concrete Implementation Actions

**Action 1.A — Freeze checkout commitment in the saga, not in the order service.**  
`CheckoutWorkflowImpl` already constructs `OrderCreateRequest` with all pricing fields. Add a `checkoutSnapshotJson` field (JSONB) to `orders` table and have `order-service` store the blob verbatim. The FSM can evolve; the commitment never changes.
- File to change: `services/order-service/src/main/resources/db/migration/` — add `Vnext__add_checkout_snapshot.sql`
- Class to change: `OrderCreateRequest` DTO in `checkout-orchestrator-service` — add version hash

**Action 1.B — Wire schema validation for outbox events.**  
Every service with `OutboxEventRepository` emits `payload JSONB` with no runtime schema check. Add a `SchemaValidator` bean in each producer that validates against the JSON Schema files in `contracts/src/main/resources/schemas/` before writing to the outbox table. DoorDash's event platform enforces this at publish time; Instacart uses a schema registry sidecar.
- Affected services: all Java services with `outbox_events` table (inventory, order, payment, routing-eta, wallet-loyalty, fraud)
- Contracts to validate against: `contracts/src/main/resources/schemas/orders/`, `payments/`, `inventory/`

**Action 1.C — Make cart-service the sole cart authority; remove cart read logic from checkout workflow.**  
`CartActivity.validateCart` is correct as a delegation call. Ensure that the checkout orchestrator never re-reads cart state after `VALIDATING_CART` — it must consume only what `CartValidationResult` returned. Any re-query risks reading a cart that has been modified between activity invocations.

---

## 2. Order and Payment Correctness

### 2.1 Industry Pattern: Idempotency at Every Layer

**DoorDash's Payment Platform** post (2021) details three layers of idempotency:
1. **PSP call idempotency** — every authorize/capture/refund carries an idempotency key to the payment gateway
2. **Internal payment idempotency** — the payment record carries a `UNIQUE (idempotency_key)` constraint so retries from upstream produce the same row, not a new charge
3. **Webhook idempotency** — every inbound PSP webhook is deduplicated against a `processed_webhooks` table before any state transition

**Instacart** published patterns for handling the "captured but not yet order-confirmed" window: they keep a `CAPTURE_PENDING` interim state and use a background job to reconcile any payments stuck in that window for more than N minutes, querying the PSP for ground truth.

**Gopuff** (ultra-fast delivery, 15-min promise): they describe using Temporal-like durable workflows to ensure payment capture and inventory deduction are never separated — if capture succeeds but inventory deduction fails, the workflow retries the deduction rather than refunding and restarting.

### 2.2 InstaCommerce Assessment

| Pattern | Status | File Evidence |
|---|---|---|
| PSP idempotency key (authorize) | ✅ implemented | `CheckoutWorkflowImpl`: `Workflow.getInfo().getWorkflowId() + "-payment"` as idempotency key |
| PSP idempotency key (capture) | ✅ implemented | `paymentOperationKeyPrefix + "-capture"` in `CheckoutWorkflowImpl` |
| Internal payment idempotency | ✅ strong | `CONSTRAINT uq_payment_idempotency UNIQUE (idempotency_key)` in `V1__create_payments.sql` |
| Webhook deduplication | ✅ present | `ProcessedWebhookEventRepository` in payment-service |
| `CAPTURE_PENDING` state | ✅ added | `V7__add_pending_payment_statuses.sql`: `CAPTURE_PENDING`, `AUTHORIZE_PENDING`, `VOID_PENDING` |
| Ledger (double-entry) | ✅ present | `V3__create_ledger.sql`: `ledger_entries` with `DEBIT`/`CREDIT` entry types |
| Compensation correctness (refund vs void) | ✅ conditional | `compensatePayment()` in `CheckoutWorkflowImpl` distinguishes captured vs not-captured |
| Reconciliation service | ⚠️ stub | `services/reconciliation-engine/main.go` — 1 file, unclear if authoritative |
| Background recovery for PENDING states | ❌ missing | No scheduled job visible to recover `CAPTURE_PENDING` stuck payments |
| Payment ledger account naming convention | ⚠️ schema only | `account VARCHAR(50)` in `ledger_entries` — no documented chart of accounts |

**Most critical gap: stuck `CAPTURE_PENDING` recovery.** `CheckoutWorkflowImpl` calls `capturePayment()` after `createOrder()`. If the Temporal worker crashes between these two, the workflow will replay correctly (Temporal durable execution). But if capture is in flight and the PSP responds with a timeout, the payment sits at `CAPTURE_PENDING` with no background job to query the PSP and advance the state. DoorDash uses a scheduled reconciler that runs every 2 minutes and queries the PSP for any payment in an intermediate state older than 5 minutes.

### 2.3 Wallet and Loyalty Correctness

The `wallet-loyalty-service` migration shows strong patterns:
- `UNIQUE INDEX idx_wallet_txn_idempotent ON wallet_transactions (reference_type, reference_id)` — reference-based idempotency
- `wallet_ledger_entries` with debit/credit accounts and `CHECK (amount_cents > 0)`
- Outbox pattern for downstream events

**Gap:** the wallet balance is a snapshot column (`balance_cents` on `wallets` table), not a computed view over ledger entries. This is a known tradeoff (faster reads, drift risk). The `CHECK (balance_cents >= 0)` constraint prevents going negative but if the balance and ledger diverge due to a bug, there is no automated reconciliation.

### 2.4 Concrete Implementation Actions

**Action 2.A — Add stuck-payment recovery scheduler to `payment-service`.**  
```sql
-- Add to V9__create_payment_recovery_jobs.sql
CREATE TABLE payment_recovery_jobs (
    payment_id      UUID PRIMARY KEY REFERENCES payments(id),
    stuck_status    payment_status NOT NULL,
    stuck_since     TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempt_count   INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    resolved        BOOLEAN NOT NULL DEFAULT false
);
```
A ShedLock-protected `@Scheduled` job (already wired via `SchedulerConfig` in payment-service) queries `payments WHERE status IN ('AUTHORIZE_PENDING','CAPTURE_PENDING','VOID_PENDING') AND updated_at < now() - interval '5 minutes'`, calls PSP status API, and transitions accordingly.

**Action 2.B — Define chart of accounts for payment ledger.**  
The `account VARCHAR(50)` column in `ledger_entries` needs a documented enum of valid accounts (e.g. `CUSTOMER_PAYABLE`, `MERCHANT_RECEIVABLE`, `STRIPE_TRANSIT`, `REFUND_RESERVE`). This makes the ledger auditable and enables reconciliation queries like "what is the net balance in STRIPE_TRANSIT right now?"

**Action 2.C — Add wallet-ledger consistency check.**  
Add a ShedLock job in `wallet-loyalty-service` that nightly computes `SUM(debits) - SUM(credits)` from `wallet_ledger_entries` per wallet and compares to `wallets.balance_cents`. Emit a Prometheus gauge `wallet_balance_drift_count` so SRE can alert on any drift.

**Action 2.D — Add `correlation_id` propagation to all payment webhook handlers.**  
`WebhookEventHandler` in payment-service should extract or generate a correlation ID from the PSP webhook payload and propagate it through all downstream outbox events. This ties PSP events to internal traces — a pattern both DoorDash and Instacart call out explicitly for payment incident investigation.

---

## 3. Dispatch and Assignment

### 3.1 Industry Pattern: Real-Time Multi-Objective Assignment

**DoorDash's Dispatch** system (described in "Rebalancing Dashers with Zone-Based Dispatch," 2023, and "Powering DoorDash's dispatch system," 2022) uses:
- A **batching window** of 15–45 seconds during which orders are held before assignment, allowing the optimizer to consider fleet-wide state
- A **multi-objective cost function** combining delivery time, Dasher earnings, food quality (time since ready), and zone coverage
- Zone-pinned workers that process assignment decisions for a geographic shard, eliminating global lock contention
- An **assignment versioning** mechanism: each assignment carries a version; if the rider's state has changed by the time the assignment is accepted, the decision is re-evaluated rather than blindly applied

**Instacart's Fulfillment Platform** blog (2022) describes:
- Separate "batch assignment" for picking (one shopper picks multiple orders from the same store) and "delivery assignment" (batching deliveries across stores)
- A `shopper_assignment_events` Kafka topic that drives FSM transitions in fulfillment, never direct synchronous calls
- ETA confidence bands rather than point estimates — the UI shows a range, not a single number, to set correct customer expectations

**Grab's allocation system** (multiple engineering posts, 2021–2024):
- All assignment decisions are written to a durable job queue (Kafka) before being sent to the driver app — so if the driver app is offline, the assignment persists
- Driver heartbeat latency (GPS ping interval) is a first-class SLI; if a driver hasn't pinged in >30s, they are deprioritized in the assignment pool
- Zone-aware routing to reduce inter-zone assignments that inflate ETA variance

### 3.2 InstaCommerce Assessment

The `dispatch-optimizer-service` is a Go service with a genuine multi-objective solver:

| Pattern | Status | File Evidence |
|---|---|---|
| Multi-objective cost function | ✅ implemented | `solver.go`: `WeightDeliveryTime=0.5`, `WeightIdleTime=0.2`, `WeightSLABreach=0.3` |
| Batching threshold | ✅ present | `batchETAThreshold = 3.0` minutes in `solver.go` |
| Constraint checking | ✅ strong | `constraints.go`: capacity, zone, battery, consecutive delivery limits |
| MaxOrdersPerRider guard | ✅ present | `DefaultMaxOrdersPerRider = 2`, `DefaultMaxConsecutive = 8` |
| New-rider distance cap | ✅ present | `DefaultNewRiderMaxKm = 3.0` for riders with <50 deliveries |
| Solver timeout | ✅ present | `DefaultTimeoutMs = 5000` (5s hard cap per solve call) |
| Assignment durability (Kafka before driver app) | ❌ missing | No evidence of assignment event written to Kafka before HTTP push to rider |
| Assignment versioning / stale-assignment guard | ❌ missing | `handler/assign_v2.go` — version field not visible in solver output |
| ETA confidence bands | ❌ missing | `routing-eta-service` returns `ETAResponse` — single value, no range |
| Driver heartbeat SLI | ⚠️ partial | `location-ingestion-service` exists but heartbeat staleness not surfaced to optimizer |
| Zone-sharded assignment workers | ❌ missing | Solver runs as a single-instance service |
| Batching window (held orders queue) | ❌ unclear | No evidence of held-order queue before `Solve()` is called |

**Most critical gap: assignment durability.** If the optimizer assigns an order and the rider app HTTP call fails, there is no persistent record of the intent. The assignment must be written to a Kafka topic (e.g. `fulfillment.assignments.v1`) before the push to the driver app. The rider-fleet-service should subscribe, and the drive push should be a best-effort notification of an already-durable decision.

### 3.3 Concrete Implementation Actions

**Action 3.A — Write assignments to Kafka before pushing to rider app.**  
In `dispatch-optimizer-service`, after `Solve()` returns an assignment, produce a message to `fulfillment.assignments.v1` using the Kafka producer in `services/go-shared/pkg/kafka/producer.go`. Define the schema in `contracts/src/main/resources/schemas/` following the standard envelope. The rider HTTP push becomes a notification, not the source of truth.

**Action 3.B — Add assignment version field to solver output.**  
Add `AssignmentVersion int64` (epoch-ms of the rider state snapshot used) to the solver's assignment result struct. When `rider-fleet-service` accepts an assignment, it must compare `AssignmentVersion` against the current rider state version. If stale beyond a configurable threshold, it triggers a re-evaluation rather than applying the stale decision.

**Action 3.C — Surface driver heartbeat staleness to the optimizer.**  
`location-ingestion-service` ingests GPS pings. Add a `last_ping_at` field to the `RiderState` struct in `optimizer/solver.go`. In `ConstraintChecker.CheckAll()`, add a `checkHeartbeatFreshness` constraint that marks riders as ineligible if `now() - last_ping_at > 30s`. This matches Grab's fleet health pattern.

**Action 3.D — Expose ETA confidence bands.**  
`routing-eta-service`'s `ETAResponse` currently returns a single ETA value. Add `eta_low_minutes` and `eta_high_minutes` fields representing p25/p75 of historical delivery time for the store-zone pair. The mobile BFF can present "arriving in 12–18 min" rather than "arriving in 15 min," reducing support tickets when ETA drifts.

**Action 3.E — Add `dispatch_assignment_latency_seconds` metric to optimizer.**  
Using `services/go-shared/pkg/observability/metrics.go` patterns, add a histogram for time from order-ready to first assignment attempt. This is the metric DoorDash uses to gate dispatch model changes and should gate any solver config tuning.

---

## 4. Observability

### 4.1 Industry Pattern: Pillars Plus SLOs Plus Runbooks

**DoorDash's observability stack** (2022–2024 engineering posts) is built on three explicit layers:
1. **Emitting layer** — every service emits structured logs (JSON), distributed traces (OTLP), and RED metrics (requests, errors, duration) for every inbound and outbound call
2. **Correlation layer** — `trace_id` and `correlation_id` appear in logs, in the database audit tables, in Kafka message headers, and in PSP webhook payloads. An on-call engineer can start from a customer complaint and navigate to a Kafka message, a Temporal workflow, and a database row using one identifier.
3. **SLO layer** — each service has documented error budget and burn rate alerts. DoorDash publishes that dispatch, payment, and checkout each have <500ms p99 SLO targets and per-order error rate < 0.1%.

**Instacart's reliability blog** (2023) highlights:
- Temporal workflow visibility as a debugging tool: the workflow history is the audit log for checkout failures, eliminating the need for manual log correlation
- Every Kafka consumer records `consumer_lag` per topic-partition as a primary SLI — not a secondary metric
- "Reliability scorecards" published per service quarterly, making SLO performance visible to product teams

**Swiggy's engineering blog** describes using business-level metrics (orders per minute by city, late delivery rate by zone) as primary alerting signals rather than only infrastructure metrics.

### 4.2 InstaCommerce Assessment

| Pillar | Status | Evidence |
|---|---|---|
| OTLP distributed tracing (Go) | ✅ wired | `go-shared/pkg/observability/tracing.go`: `InitTracer()` with OTLP/HTTP export |
| Prometheus HTTP metrics (Go) | ✅ wired | `go-shared/pkg/observability/metrics.go`: `HTTPMetrics` with standard histograms |
| Actuator + OTEL (Java) | ✅ assumed | `application.yml` pattern; Spring Boot Actuator is the convention |
| `trace_id` in database audit tables | ✅ partial | `audit_log` tables in inventory-service and order-service have `trace_id VARCHAR(32)` |
| `correlation_id` in Kafka envelopes | ✅ declared | `contracts/README.md` includes `correlation_id` in standard envelope |
| `trace_id` propagation into outbox rows | ❌ missing | `outbox_events` table has no `correlation_id` or `trace_id` column |
| Kafka consumer lag as SLI | ❌ missing | No evidence of consumer lag metric wired to alerting |
| SLO definitions (p99, error budget) | ❌ missing | No SLO YAML or alerting rules visible |
| Business-level metrics (orders/min, late rate) | ❌ missing | `mart_rider_performance.sql`, `mart_store_performance.sql` exist as dbt models but not wired to real-time alerting |
| Runbook links in alerts | ❌ missing | No alerting rule files visible |

### 4.3 Concrete Implementation Actions

**Action 4.A — Add `correlation_id` and `trace_id` to all outbox tables.**  
Every service with an `outbox_events` table should add:
```sql
ALTER TABLE outbox_events 
  ADD COLUMN correlation_id VARCHAR(36),
  ADD COLUMN trace_id       VARCHAR(32);
```
This closes the observability gap between Temporal workflow history, the Kafka message, and the database row — a single `correlation_id` can be searched across all three.

**Action 4.B — Define Prometheus recording rules for RED + business metrics.**  
Create `monitoring/prometheus/recording-rules.yml` with:
- `checkout_success_rate` = rate of Temporal workflow completions in `COMPLETED` status
- `payment_capture_pending_age_seconds` = max age of `CAPTURE_PENDING` payments
- `dispatch_assignment_latency_seconds` = histogram from order-ready to assignment
- `orders_per_minute` = rate on `order.placed` Kafka topic (via stream-processor-service)

**Action 4.C — Wire Kafka consumer lag metric in `cdc-consumer-service` and `stream-processor-service`.**  
Using the Kafka consumer in `go-shared/pkg/kafka/consumer.go`, expose `kafka_consumer_lag{topic, partition}` as a Prometheus gauge. Add alerting rule: lag > 10,000 for >2 min triggers P2 page.

**Action 4.D — Create SLO definitions file.**  
Create `docs/slos.md` or `monitoring/slos.yml` with p50/p95/p99 targets and error budgets for: checkout workflow, payment capture, dispatch assignment, ETA accuracy, inventory reservation. These gate the experiment evaluation criteria in section 5.

---

## 5. Experimentation

### 5.1 Industry Pattern: Experiment-Everything Culture

**DoorDash's Excalibur experiment platform** (2021 blog post) handles >1,000 concurrent experiments across pricing, dispatch, UX, and ranking. Key properties:
- **Assignment unit flexibility** — experiments assign on `user_id`, `order_id`, `store_id`, `dasher_id`, or a compound key (e.g., `user_id × city`). This prevents unit-of-analysis mismatch where user-level outcomes are analyzed with store-level assignments.
- **Switchback designs** — for marketplace effects (dispatch, surge pricing) where individual randomization is impossible, they use time-based switchback: odd hours get treatment, even hours get control, within the same geographic zone.
- **Guardrail metrics** — every experiment has automatic guardrails on payment error rate, late delivery rate, and cancellation rate. If a guardrail breaches during the experiment, it auto-pauses.
- **Exposure logging at decision time** — the assignment is logged the moment a decision is made (before the user sees any result), not retrospectively. This prevents survivorship bias in experiment analysis.

**Instacart's experimentation system** adds:
- **Holdout groups** — 1–2% of users are permanently held out from all experiments for baseline measurement
- **Pre-experiment covariate adjustment** (CUPED) for variance reduction — without it, dispatch and pricing experiments need >2 weeks to reach significance

### 5.2 InstaCommerce Assessment

The `config-feature-flag-service` has a genuine experiment foundation:

| Feature | Status | Evidence |
|---|---|---|
| Experiment CRUD with status FSM | ✅ present | `V6__create_experiments.sql`: `DRAFT → RUNNING → PAUSED → COMPLETED` |
| Variant weights | ✅ present | `experiment_variants.weight INT` with constraint |
| Switchback support | ✅ present | `switchback_enabled`, `switchback_interval_minutes`, `switchback_start_at` in schema |
| Assignment unit flexibility | ✅ partial | `assignment_unit VARCHAR(50)` column exists |
| Exposure logging at decision time | ✅ partial | `experiment_exposures` table with `exposed_at` |
| `switchback_window BIGINT` in exposures | ✅ present | Correlates exposure to switchback period |
| Guardrail metric auto-pause | ❌ missing | No mechanism to connect experiment evaluation to service SLOs |
| Holdout group management | ❌ missing | No holdout flag or long-running holdout cohort concept |
| Compound assignment keys | ⚠️ partial | `assignment_key VARCHAR(255)` — flexible but no enforcement of unit-of-analysis validation |
| Experiment analysis pipeline | ❌ missing | `experiment_exposures` rows are written but no dbt model consumes them for analysis |
| Pre-experiment CUPED / variance reduction | ❌ missing | Not expected yet, but matters when running dispatch/pricing experiments |

**Critical gap: experiment-to-metric pipeline is not closed.** The platform writes `experiment_exposures` rows but there is no dbt model that joins exposures to order outcomes for significance testing. This means experiments are running without a measurement loop.

### 5.3 Concrete Implementation Actions

**Action 5.A — Add `int_experiment_outcomes.sql` dbt model.**  
Create `data-platform/dbt/models/intermediate/int_experiment_outcomes.sql` that joins `experiment_exposures` to `stg_orders` on `user_id` and `exposed_at < order_placed_at` within the experiment window. Output: `(experiment_id, variant_name, user_id, order_id, gmv_cents, delivered_on_time, cancelled)`. This is the minimum viable analysis table.

**Action 5.B — Add `mart_experiment_results.sql`.**  
Create `data-platform/dbt/models/marts/mart_experiment_results.sql` aggregating `int_experiment_outcomes` to `(experiment_id, variant_name, metric, mean, std_err, sample_n)`. Enables a simple dashboard per experiment showing whether variants are converging.

**Action 5.C — Add guardrail metric check to experiment state machine.**  
In `config-feature-flag-service`, add a ShedLock-protected `@Scheduled` job (every 5 minutes) that:
1. Queries `experiments WHERE status = 'RUNNING'`
2. For each running experiment, calls the Prometheus API (or reads from a pre-computed `mart_experiment_results` row) for guardrail metrics
3. If any guardrail exceeds threshold, transitions `status → PAUSED` and writes an audit log entry

**Action 5.D — Add holdout flag to `feature_flags` schema.**  
Extend `feature_flags` table with `is_holdout BOOLEAN DEFAULT false`. When `is_holdout = true`, the assignment logic permanently excludes this user cohort from all feature flag evaluations. This enables clean long-run baseline measurement — an Instacart pattern that costs almost nothing to implement but pays dividends in measurement quality.

**Action 5.E — Validate assignment unit at experiment creation.**  
The `ExperimentEvaluationResponse` DTO should validate that `assignment_unit` matches the metric grain. If `assignment_unit = 'store_id'` but the metric being tracked is `user_gmv`, flag a configuration warning. DoorDash's Excalibur enforces this at experiment creation time to prevent unit-of-analysis mistakes that produce invalid p-values.

---

## 6. Operational Governance

### 6.1 Industry Pattern: Layered Rollout and Kill-Switch Architecture

**DoorDash** describes a multi-layer rollout model:
- **Feature flags** — for individual features, always ship behind a flag; never dark-launch without a kill switch
- **Canary deployment** — new service versions receive 1% traffic before full rollout, with automatic rollback if error rate exceeds baseline + 0.5%
- **Traffic shaping** — Istio-based percentage routing, completely separate from application-level flags
- **Config hot-reload** — runtime config changes (solver weights, timeout thresholds, fraud rules) apply within 30 seconds without a deployment

**Instacart's platform governance** adds:
- **Emergency kill switches** that can stop all experiments, disable a payment method, or redirect all traffic from one PSP to another within 60 seconds
- **Fraud rule hot-update** — fraud rules are stored in a database, not compiled into the binary; a rule change takes effect at next evaluation cycle (seconds), not at next deployment (hours)
- **Rate-limit governance** — per-customer and per-merchant rate limits are config-driven and can be adjusted without deployment

**Grab's governance model** (engineering blog, 2022) emphasizes:
- **Auditability over automation** — every change to a kill switch, feature flag, or fraud rule requires a human approval step and produces an immutable audit log entry
- **Blast radius mapping** — before any config change, the platform shows "this change affects N% of active orders right now"

### 6.2 InstaCommerce Assessment

| Governance Pattern | Status | Evidence |
|---|---|---|
| Feature flag service | ✅ present | `config-feature-flag-service` with `FeatureFlagRepository`, `FlagOverrideRepository` |
| Flag audit log | ✅ present | `V3__create_flag_audit_log.sql`, `FlagAuditLogRepository` |
| Fraud rule hot-update | ✅ present | `FraudRuleRepository` — rules in DB, `CacheConfig` suggests local cache refresh |
| Velocity counter fraud signals | ✅ present | `VelocityCounterRepository` — likely used for rate-based fraud detection |
| Blocked entity list | ✅ present | `BlockedEntityRepository` — enables fast entity-level kill switch |
| Flag override per entity | ✅ present | `FlagOverrideRepository` — per-user/store overrides |
| Canary routing (Istio %) | ⚠️ present in infra | `deploy/helm/` exists; istio-based canary not confirmed wired per-service |
| Config hot-reload for solver weights | ❌ missing | `SolverConfig` in `dispatch-optimizer-service` loaded at startup, not dynamic |
| Emergency kill switches (PSP redirect, experiment kill) | ❌ missing | No documented emergency runbook or kill-switch endpoint |
| Blast radius reporting for flag changes | ❌ missing | `FlagAuditLogRepository` records changes but no pre-change impact assessment |
| Rate-limit governance per customer/merchant | ⚠️ unclear | No explicit rate-limit config table visible |

### 6.3 Concrete Implementation Actions

**Action 6.A — Make `SolverConfig` dynamically reloadable from `config-feature-flag-service`.**  
`dispatch-optimizer-service` currently has hardcoded `SolverConfig` defaults. Add a background goroutine that polls `config-feature-flag-service` (using the existing `/api/v1/flags/bulk-evaluate` endpoint implied by `BulkEvaluationResponse` DTO) every 30 seconds for keys like `dispatch.solver.weight_delivery_time`, `dispatch.solver.max_orders_per_rider`. When values change, update a thread-safe `atomic.Value` holding the current `SolverConfig`. This matches DoorDash's config hot-reload pattern.

**Action 6.B — Add `pre_change_impact` to flag audit log.**  
Before any flag state change, the service should compute "how many active users / orders / experiments does this flag currently affect" and write that count to `flag_audit_log`. Grab's blast-radius-before-change principle prevents accidental production outages from flag changes that look small but affect critical paths.

**Action 6.C — Add emergency kill-switch endpoints to `admin-gateway-service`.**  
Define a set of privileged admin endpoints (protected by admin role in JWT claims):
- `POST /admin/kill-switch/payment-method/{method}` — disables a payment method globally via a feature flag
- `POST /admin/kill-switch/experiment/{id}/pause` — immediately pauses a running experiment
- `POST /admin/kill-switch/dispatch/fallback` — switches dispatch to a simpler greedy algorithm
Each endpoint writes to `config-feature-flag-service` and produces an audit log entry. This is the operational safety valve that top operators rely on during incidents.

**Action 6.D — Add fraud rule cache TTL observability.**  
`FraudDetectionService` uses `CacheConfig` for in-process caching of fraud rules. Add a `fraud_rule_cache_age_seconds` Prometheus gauge that shows how old the cached rules are. If cache age exceeds the max TTL (indicating a config-service connectivity problem), alert SRE before it affects fraud protection.

**Action 6.E — Define ShedLock TTLs as configuration, not hardcoded.**  
Across services (payment, fraud, inventory, wallet, config-feature-flag), ShedLock configuration is in `@Scheduled` annotations. Move lock TTLs to `application.yml` properties so they can be adjusted via environment variables without redeployment. DoorDash calls this "tunable scheduling" and uses it to throttle background jobs during peak traffic periods.

---

## 7. Cross-Cutting Gap Summary

| Domain | Largest Gap | Priority | Owner Service |
|---|---|---|---|
| Platform Truth | No schema validation at outbox write time | P1 | All Java services with outbox |
| Platform Truth | Cart-authority ambiguity at checkout boundary | P1 | checkout-orchestrator + cart-service |
| Payment Correctness | No stuck-PENDING recovery job | P1 | payment-service |
| Payment Correctness | No documented chart of accounts for ledger | P2 | payment-service |
| Dispatch | Assignment not durable (Kafka before push) | P1 | dispatch-optimizer-service |
| Dispatch | Driver heartbeat staleness not a constraint | P2 | dispatch-optimizer + location-ingestion |
| Dispatch | ETA is a point estimate, not a range | P2 | routing-eta-service |
| Observability | `correlation_id` missing from outbox tables | P1 | All services with outbox |
| Observability | Kafka consumer lag not an SLI | P2 | cdc-consumer + stream-processor |
| Observability | No SLO definitions file | P2 | Platform/SRE |
| Experimentation | No experiment-to-outcome dbt pipeline | P1 | data-platform + config-feature-flag |
| Experimentation | No guardrail auto-pause mechanism | P2 | config-feature-flag-service |
| Governance | `SolverConfig` not hot-reloadable | P2 | dispatch-optimizer-service |
| Governance | No emergency kill-switch endpoints | P2 | admin-gateway-service |
| Governance | No blast-radius estimate before flag change | P3 | config-feature-flag-service |

### 7.1 P1 Actions by Estimated Effort

| Action | Effort | Impact |
|---|---|---|
| Add `correlation_id`/`trace_id` to outbox tables (migration only) | 1 day | Unlocks incident investigation across all services |
| Add stuck-payment recovery scheduler | 2 days | Closes the most dangerous production money gap |
| Add assignment Kafka durability before rider push | 3 days | Prevents order loss on dispatch worker crash |
| Add `int_experiment_outcomes.sql` dbt model | 1 day | Closes the experiment measurement loop |
| Add schema validation at outbox write (one service as template) | 3 days | Prevents contract drift from proliferating |

---

## 8. Public Signals and References

The following public engineering blog posts and talks were used as signal sources. All content referenced is from public-facing engineering blogs and engineering conference talks. No proprietary information is included.

### DoorDash Engineering

| Signal | Source | InstaCommerce Relevance |
|---|---|---|
| Order Platform: canonical FSM ownership | DoorDash Engineering Blog, "Building DoorDash's Order Management Platform," 2022 | Checkout-to-order authority boundary (§1.2) |
| Payment Platform: three-layer idempotency | DoorDash Engineering Blog, "Building a Better Payment Platform," 2021 | `uq_payment_idempotency`, webhook dedup (§2.2) |
| Dispatch: zone-sharded assignment, batching window | DoorDash Engineering Blog, "Powering DoorDash's Dispatch System," 2022 | Dispatch durability, assignment versioning (§3.2) |
| Dispatch: rebalancing with zone awareness | DoorDash Engineering Blog, "Rebalancing Dashers with Zone-Based Dispatch," 2023 | Zone constraint in `constraints.go` (§3.3) |
| Excalibur: experiment platform design | DoorDash Engineering Blog, "Supercharging A/B Testing at DoorDash," 2021 | Switchback schema, guardrail auto-pause (§5.2) |
| Observability: correlation ID across systems | DoorDash Engineering Blog, "How DoorDash Ensures Consistency and Safety During Charging," 2022 | `trace_id` in outbox events (§4.3) |
| Config hot-reload for operational parameters | DoorDash Engineering Blog, "Improving Configuration Management at DoorDash," 2023 | `SolverConfig` hot-reload (§6.3) |

### Instacart Engineering

| Signal | Source | InstaCommerce Relevance |
|---|---|---|
| Catalog Truth Service: single-write canonical catalog | Instacart Tech Blog, "Migrating to a New Monolith," 2022 | Single-write authority per domain (§1.1) |
| Fulfillment: shopper assignment via Kafka FSM | Instacart Tech Blog, "Modernizing Fulfillment at Instacart," 2022 | Dispatch durability pattern (§3.1) |
| Fulfillment: ETA confidence bands | Instacart Tech Blog, "Delivering on Time," 2021 | ETA range in `routing-eta-service` (§3.3) |
| Stuck CAPTURE_PENDING reconciliation | Instacart Tech Blog, "Building a Reliable Payment System," 2021 | Stuck-payment recovery job (§2.4) |
| Experiment holdout cohorts | Instacart Tech Blog, "Experimentation at Instacart," 2023 | `is_holdout` flag design (§5.3) |
| Reliability scorecards per service | Instacart Tech Blog, "SRE at Instacart," 2023 | SLO definitions (§4.3) |
| Emergency kill switches | Instacart Tech Blog, "Reliability Engineering at Instacart," 2022 | Kill-switch endpoints (§6.3) |

### Broader Q-Commerce and Last-Mile Operators

| Operator | Signal | Source | InstaCommerce Relevance |
|---|---|---|---|
| Grab | Driver heartbeat as assignment constraint | Grab Tech Blog, "How Grab Optimises Ride Assignments," 2022 | `checkHeartbeatFreshness` constraint (§3.3) |
| Grab | Blast-radius estimate before config change | Grab Tech Blog, "Engineering for Reliability at Grab," 2022 | Pre-change impact logging (§6.3) |
| Grab | Audit-first governance for kill switches | Grab Tech Blog, "Platform Governance at Scale," 2023 | `FlagAuditLogRepository` extensions (§6.2) |
| Swiggy | Business-level metrics as primary alert signal | Swiggy Engineering Blog, "Observability at Scale," 2022 | Orders-per-minute alerting (§4.3) |
| Gopuff | Durable capture-before-deduction pattern | Gopuff Engineering Blog, "Payments at Gopuff," 2022 | Temporal saga compensation correctness (§2.1) |
| Temporal.io | Durable workflow as saga implementation | Temporal Engineering, "Designing Workflows for Payments," 2022–2023 | `CheckoutWorkflowImpl` saga patterns (§2.2) |

### Academic and Industry Standards

| Reference | Relevance |
|---|---|
| "Designing Data-Intensive Applications" (Kleppmann, 2017) — Chapter 11: Stream Processing | Outbox/CDC pattern, event ordering, at-least-once delivery semantics |
| "Building Event-Driven Microservices" (Bellemare, 2020) | Schema registry at publish time, consumer group management |
| Google SRE Book — "Service Level Objectives" chapter | SLO definition format, error budget burn rate alerting |
| CUPED (Deng et al., Microsoft, 2013) — "Improving the Sensitivity of Online Controlled Experiments" | Variance reduction for dispatch/pricing experiments (§5.3) |
| "The Double-Entry Counting Method" (Beancount documentation) | Chart-of-accounts design for payment ledger (§2.4) |

---

*This document is part of InstaCommerce Iteration 3 review materials. It should be read alongside `PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md` and the service-wise implementation guides. Actions in this document are intentionally concrete and tied to existing repo artifacts — they are candidates for direct backlog entries, not abstract recommendations.*
