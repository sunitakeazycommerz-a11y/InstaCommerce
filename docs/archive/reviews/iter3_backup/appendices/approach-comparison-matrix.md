# Appendix: Remediation Approach Comparison Matrix

**Document:** `docs/reviews/iter3/appendices/approach-comparison-matrix.md`  
**Date:** 2026-03-06  
**Audience:** Principal Engineers, Staff Engineers, EMs, SRE, Security, Data/ML, Platform  
**Scope:** Deep comparison of major remediation choices across ten production domains. Each domain covers 2–4 concrete approaches, implementation tradeoffs, latency impact, operational risk, migration cost, and the InstaCommerce-specific recommendation derived from iteration-2 and iteration-3 findings.

**Supporting docs:**
- `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-2-2026-03-06.md`
- `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md`
- `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-SERVICE-WISE-2026-03-06.md`
- `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-PLATFORM-WISE-2026-03-06.md`

---

## How to read this matrix

Each domain section follows a consistent structure:

1. **Current state summary** — one paragraph on what the repo actually does today
2. **Approach table** — 2–4 named options with brief summary
3. **Comparison matrix** — tradeoffs, latency impact, operational risk, and migration cost for each option
4. **InstaCommerce recommendation** — the preferred path and the minimum-viable next step
5. **Key references** — public best-practice pointers where available

Columns in the comparison table use a three-tier rating system:

| Rating | Meaning |
|--------|---------|
| 🟢 Low | Acceptable or minimal concern |
| 🟡 Medium | Notable tradeoff; plan explicitly |
| 🔴 High | Significant risk or cost; gate carefully |

---

## 1. Edge Authentication and Authorization

### Current state

`mobile-bff-service` and `admin-gateway-service` are scaffold-grade. A shared `INTERNAL_SERVICE_TOKEN` collapses all internal callers into a single privileged identity. Istio mTLS is present but `AuthorizationPolicy` coverage is thin. Edge routing paths in Helm/Istio do not match Spring controller mappings (`/api/v1/checkout` vs `/checkout`). Pod-local Caffeine rate limiting is bypassable under multi-replica traffic.

### Approaches

| ID | Name | Summary |
|----|------|---------|
| A1 | Istio-native JWT + AuthorizationPolicy | Offload all authn/authz to the mesh; BFF and admin become aggregation-only, not security-enforcement layers |
| A2 | Dedicated API Gateway product (Kong, Apigee, AWS API GW) | Replace custom BFF/admin with a managed gateway product for rate limiting, auth, and routing |
| A3 | Spring Security hardening in BFF + workload identity | Harden in-process security in existing services; pair with mesh policies |
| A4 | Hybrid: Istio for transport + dedicated policy layer | Istio handles mTLS and coarse authz; a lightweight Open Policy Agent or Envoy ext_authz handles fine-grained per-route decisions |

### Comparison matrix

| Criterion | A1 — Istio-native | A2 — Managed gateway | A3 — Spring hardening | A4 — Hybrid Istio + OPA |
|-----------|:-----------------:|:--------------------:|:---------------------:|:----------------------:|
| **Auth enforcement latency** | 🟢 ~0–2 ms sidecar | 🟡 5–15 ms external hop | 🟡 5–10 ms filter chain | 🟡 3–8 ms (OPA is fast in-cluster) |
| **Granularity of per-route policy** | 🟡 Medium (L7 path matching) | 🟢 High (declarative rules) | 🟢 High (code-level) | 🟢 High (policy language) |
| **Operational risk if misconfigured** | 🔴 Silent allow-all if policy absent | 🟡 Gateway blocks traffic on error | 🔴 Silent allow in legacy filters | 🟡 OPA deny-by-default is safer |
| **Migration cost** | 🟢 Low — config-only changes | 🔴 High — service cutover + traffic migration | 🟡 Medium — code changes per service | 🟡 Medium — OPA sidecar rollout |
| **Egress from shared token model** | 🟢 AuthorizationPolicy is direct replacement | 🟡 Requires gateway integration config | 🟡 Requires service-by-service refactor | 🟢 OPA handles it declaratively |
| **Operational burden long-term** | 🟢 Low — mesh-managed | 🔴 High — vendor dependency, routing drift | 🟡 Medium — code ownership | 🟡 Medium — policy files to maintain |
| **Rate limiting durability** | 🟡 Needs Redis-backed Envoy RLS | 🟢 Managed, durable | 🔴 Pod-local only | 🟡 OPA + Redis via Envoy |

### InstaCommerce recommendation

**A4 (Hybrid Istio + OPA) with A1 as immediate Wave 0 step.**

- Wave 0 now: apply `AuthorizationPolicy` deny-all + explicit allow-list for edge surfaces; deny admin traffic at the mesh until real RBAC exists; fix route path rewrites in VirtualService.
- Wave 1: annotate KSA/GSA workload identity per service; remove `INTERNAL_SERVICE_TOKEN` from critical paths.
- Wave 2: add OPA ext_authz for fine-grained per-route decisions on admin and payment paths where Spring granularity matters.
- Do not adopt a managed gateway product (A2) until the repo has stable service boundaries — the overhead of syncing routing config across both Helm and a gateway tier is currently not justified.

**Key references:**
- Istio AuthorizationPolicy best practices: https://istio.io/latest/docs/reference/config/security/authorization-policy/
- OPA for Kubernetes auth: https://www.openpolicyagent.org/docs/latest/envoy-introduction/
- Workload identity best practices (GKE): https://cloud.google.com/kubernetes-engine/docs/how-to/workload-identity

---

## 2. Checkout Authority

### Current state

Two live implementations compete for checkout ownership: `checkout-orchestrator-service` (Temporal workflow, compensating saga) and `order-service` (HTTP-synchronous, client-supplied price). Both have checkout endpoints registered in Helm routing. There is no traffic-routing rule that definitively routes all checkout traffic to one. The orchestrator is closer to production-grade, but its failure semantics still have gaps (synchronous Temporal RPC, weak duplicate-start handling, idempotency stored only after workflow completion).

### Approaches

| ID | Name | Summary |
|----|------|---------|
| B1 | Orchestrator as sole checkout authority | Route all checkout traffic to `checkout-orchestrator-service`; remove checkout from `order-service` |
| B2 | Order-service as sole checkout authority | Collapse orchestration logic into `order-service`; retire `checkout-orchestrator-service` |
| B3 | Keep both, route by use case | Orchestrator for new guest flow; order-service for internal/legacy flow |
| B4 | New shared checkout state machine in a neutral service | Extract checkout into a clean new bounded service |

### Comparison matrix

| Criterion | B1 — Orchestrator wins | B2 — Order-service wins | B3 — Dual path | B4 — New service |
|-----------|:---------------------:|:----------------------:|:--------------:|:----------------:|
| **Authority clarity** | 🟢 Single owner | 🟡 Order-service scope creep | 🔴 Two truths | 🟢 Clean but expensive |
| **p99 checkout latency** | 🟡 Temporal RPC overhead (+50–200 ms at tail) | 🟡 Similar sync overhead | 🔴 Risk of routing splits | 🟡 Temporal same as B1 |
| **Saga compensation correctness** | 🟢 Temporal durable | 🔴 No saga in order-service | 🔴 Inconsistent | 🟢 Designed-in |
| **Duplicate-checkout safety** | 🟡 Needs idempotency key fix | 🔴 None today | 🔴 No cross-path dedup | 🟢 By design |
| **Migration cost** | 🟢 Low — mostly policy/routing | 🔴 High — rebuild Temporal saga | 🔴 Medium but accrues debt | 🔴 Highest — new service |
| **Rollback posture** | 🟢 Dark-launch + traffic flag | 🔴 No safe rollback once collapsed | 🟡 Can revert routing | 🔴 Too long a runway |
| **Operational risk during migration** | 🟢 Low with feature flag | 🔴 High — one wrong cutover | 🟡 Medium | 🔴 High — timeline risk |

### InstaCommerce recommendation

**B1 is the only production-safe path.**

The orchestrator already implements Temporal compensating workflows. B2 would mean rebuilding the saga inside the order-service, which currently has no saga logic and no Temporal wiring — far more dangerous. B3 perpetuates the dual-truth problem identified as the most critical flaw in iterations 2 and 3. B4 introduces a new service that does not exist and cannot be delivered fast enough.

Minimum viable steps:
1. Add an idempotency key to orchestrator workflow start (idempotency key → workflow ID mapping persisted before workflow runs, not after).
2. Return `202 Accepted + workflowId` for async completion rather than holding the thread on Temporal RPC.
3. Add a feature flag in Istio VirtualService to route 100% checkout to orchestrator; gate the 100% rollout behind a dark-launch canary.
4. After 2 weeks clean, remove checkout code from `order-service`.

**Key references:**
- Temporal workflow idempotency: https://docs.temporal.io/workflows#workflow-id-reuse-policy
- Saga pattern (Martin Fowler): https://martinfowler.com/articles/break-monolith-into-microservices.html
- Checkout at scale (Shopify engineering blog): https://shopify.engineering/

---

## 3. Pricing and Cart Coupling

### Current state

`cart-service` calls `/api/v1/prices/{productId}` but `pricing-service` exposes `/pricing/products/{id}`. The base URL from configuration is overridden to a hardcoded value in the Feign client. `maxUses` promotion counting has an off-by-one bug that makes bounded promotions effectively unlimited. No quote or quote-lock token exists: a customer can see one price and pay a different one seconds later. Both services rely on pod-local Caffeine caches with no cross-pod invalidation strategy.

### Approaches

| ID | Name | Summary |
|----|------|---------|
| C1 | Fix contract mismatches + introduce quote tokens | Correct API paths, fix promo bug, add TTL-bounded cryptographic quote token signed by pricing |
| C2 | Merge cart and pricing into one service | Eliminate inter-service latency and contract mismatch by co-locating |
| C3 | Cart calls pricing at checkout only, not at add-to-cart | Defer pricing validation to checkout; cart stores only product IDs |
| C4 | Pricing via read-through cache layer + quote from cache | Replace per-request pricing calls with an inventory-aware cache layer that also issues quotes |

### Comparison matrix

| Criterion | C1 — Fix + quote tokens | C2 — Merge services | C3 — Late pricing | C4 — Cache layer |
|-----------|:----------------------:|:-------------------:|:-----------------:|:----------------:|
| **Add-to-cart latency impact** | 🟡 Adds ~5–10 ms for quote RPC | 🟢 No RPC | 🟢 No RPC at add | 🟢 <1 ms cache hit |
| **Checkout price accuracy** | 🟢 Quote signature enforced | 🟢 Same process | 🟡 Stale at add-to-cart | 🟡 Quote from cached value |
| **Promo correctness** | 🟢 Fixed at source | 🟢 Single codebase | 🟡 Evaluated later, harder | 🟡 Cache invalidation needed |
| **Price manipulation attack surface** | 🟢 Signed token prevents client override | 🟢 Server-side | 🔴 Client can hold stale price | 🟡 Signed cache response helps |
| **Migration cost** | 🟢 Low — config + one new token API | 🔴 High — service merger | 🟢 Low — but shifts complexity | 🟡 Medium — cache build-out |
| **Operational risk** | 🟢 Rollback by feature flag | 🔴 Irreversible merger | 🟡 UX: stale prices confuse users | 🟡 Cache inconsistency risk |
| **Scalability ceiling** | 🟢 Good with async invalidation | 🟡 Harder to scale independently | 🟢 Good | 🟢 Good for read scale |

### InstaCommerce recommendation

**C1 is the immediate Wave 1 step; C4 elements can be layered into Wave 3.**

Merging services (C2) is architecturally regressive for a platform that aims at q-commerce scale — pricing and cart have different read/write patterns and scale profiles. C3 defers correctness to a later point and creates user-experience problems (cart shows a price that changes at checkout). C1 is the cleanest path: fix the API contract today, fix the promo counting bug today, and introduce a signed quote token model.

The quote token design:
- Pricing issues a JWT or HMAC-signed opaque token: `{ price, productId, storeId, promoId, expiresAt }` with a 5-minute TTL.
- Checkout-orchestrator validates the token signature and `expiresAt` before proceeding.
- Expired or tampered tokens fail fast with a re-price RPC rather than accepting stale values.

**Key references:**
- Idempotent add-to-cart and quote locks (Stripe engineering): https://stripe.com/blog/idempotency
- Quote/price token pattern: see Shopify Storefront API checkout APIs

---

## 4. Inventory Reservation

### Current state

`checkout-orchestrator-service` calls a non-existent reservation endpoint (`/api/v1/inventory/reserve`; actual path is `/inventory/reserve`). Reservation confirm and cancel operations are not protected by optimistic locking or database-level versioning — a double-confirm scenario can decrement available stock twice. Emitted inventory events violate the `contracts/` schema (wrong field names for `orderId`, `storeId`). The ATP model is thin: only `on_hand` and `reserved`; no expiry tracking, no perishable signals, no store-type differentiation.

### Approaches

| ID | Name | Summary |
|----|------|---------|
| D1 | Fix current inventory service in-place (atomic reserve + versioning) | Add `@Version` optimistic locking, fix API paths, correct event payloads, extend ATP model |
| D2 | Extract reservation authority into a separate service | Move reserve/confirm/cancel into a new `reservation-service`; inventory becomes a stock ledger only |
| D3 | Warehouse-driven reservation authority | Warehouse-service owns reservation; inventory is a read projection |
| D4 | Redis-based optimistic reservation with async ledger reconciliation | Fast in-memory reserve; background job reconciles against DB ledger |

### Comparison matrix

| Criterion | D1 — Fix in-place | D2 — Extract reservation | D3 — Warehouse-driven | D4 — Redis + async ledger |
|-----------|:-----------------:|:------------------------:|:---------------------:|:------------------------:|
| **Reserve p99 latency** | 🟢 <10 ms DB | 🟡 Adds RPC hop (+5–15 ms) | 🔴 Extra hop through warehouse | 🟢 <2 ms Redis |
| **Concurrency correctness** | 🟡 Good with `@Version` | 🟢 Isolated domain | 🟢 Isolated domain | 🟡 Lua scripts needed for atomicity |
| **Oversell risk** | 🟢 Low with DB locking | 🟢 Low | 🟡 Medium — two systems coordinating | 🟡 Risk during ledger lag window |
| **ATP expressiveness** | 🟡 Extendable in-service | 🟢 Clean per-domain contract | 🟢 Warehouse already has context | 🟡 Harder to model perishables |
| **Migration cost** | 🟢 Low — DB migration + code | 🔴 High — new service, routing | 🔴 High — ownership transfer | 🔴 High — Redis infra + reconcile |
| **Dark-store operational depth** | 🟡 Sufficient for Wave 2 | 🟢 Best long-term | 🟢 Closest to physical ops | 🟡 Low operational visibility |
| **Rollback posture** | 🟢 DB migration rollback | 🟡 Traffic routing rollback | 🔴 Ownership hard to undo | 🟡 Fallback to DB path |

### InstaCommerce recommendation

**D1 immediately (Wave 2); D2 as a Wave 4+ evolution once D1 is stable.**

The current defects (wrong API path, no versioning, wrong event fields) are P0. Introducing a new service (D2, D3) before fixing the existing one is dangerous because it moves broken semantics into a new deployment surface. D4 (Redis-based) is useful at Blinkit/Zepto scale for 10-minute delivery SLAs, but it introduces a reconciliation window during which oversell is structurally possible — not appropriate until the lower layers are trustworthy.

Migration sequence for D1:
```sql
-- Flyway migration: add version column
ALTER TABLE reservations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```
```java
@Version
private Long version; // on Reservation entity
```
- Fix `/api/v1/inventory/reserve` → `/inventory/reserve` in orchestrator Feign client.
- Correct event emission: `orderId`, `storeId` field names to match `contracts/schemas/`.
- Add expiry-at field and a scheduled sweeper that releases expired reservations.

**Key references:**
- Optimistic locking in JPA: https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/version
- Inventory reservation semantics at scale (Shopify): https://shopify.engineering/building-resilient-payment-systems
- ATP modeling (SAP IBP reference): https://help.sap.com/docs/IBP/

---

## 5. Dispatch Assignment Authority

### Current state

Assignment authority is split between `fulfillment-service` (auto-assigns riders locally) and `rider-fleet-service` (expects to drive assignment via events). `dispatch-optimizer-service` exists as a Go service with solver logic but is not actually wired as the authoritative decision point. The `OrderPacked` event payload is too sparse for downstream geo-assignment. Location-ingestion emits rider-centric payloads; `routing-eta-service` expects delivery-centric payloads. There is no stuck-rider recovery sweeper. ETA recalculation does not form a closed control loop.

### Approaches

| ID | Name | Summary |
|----|------|---------|
| E1 | Fulfillment retains orchestrator role; optimizer is advisory | Fulfillment triggers assignment; dispatch optimizer provides ranked candidates only |
| E2 | Dispatch optimizer becomes the sole assignment authority | Fulfillment emits intent; optimizer makes the decision and fires RiderAssigned event |
| E3 | External routing/optimization engine (OR-Tools in a dedicated cluster) | Replace Go optimizer with a dedicated combinatorial solver cluster |
| E4 | Centralized fleet management service absorbs all state | New service owns all rider state, assignment, and ETA; others consume events |

### Comparison matrix

| Criterion | E1 — Fulfillment orchestrates | E2 — Optimizer owns | E3 — External solver | E4 — Fleet service |
|-----------|:-----------------------------:|:-------------------:|:--------------------:|:-----------------:|
| **Assignment latency (p99)** | 🟢 Local decision | 🟡 +10–30 ms optimizer RPC | 🔴 +50–100 ms external solver hop | 🟡 +15–30 ms new service |
| **Assignment quality** | 🔴 Greedy, no optimization | 🟢 VRP-aware, batching-capable | 🟢 Highest — commercial-grade solver | 🟡 Depends on algorithm |
| **Conflict risk (split authority)** | 🔴 High — two assignment sources | 🟢 None — single owner | 🟢 None | 🟢 None |
| **ETA loop closure** | 🔴 No loop today | 🟢 Optimizer updates ETA | 🟡 Need ETA feedback channel | 🟢 Can own ETA too |
| **Rider recovery on stuck state** | 🔴 No sweeper | 🟢 Can own sweeper | 🟡 Needs coordination | 🟢 Owns lifecycle |
| **Migration cost** | 🟢 Lowest change | 🟡 Medium — wire optimizer + deprecate fulfillment path | 🔴 Highest — new infra | 🔴 High — new service |
| **Operational blast radius** | 🟡 Contained to fulfillment | 🟢 Isolated optimizer failure | 🔴 Solver outage = no assignments | 🔴 New SPOF |

### InstaCommerce recommendation

**E2 is the recommended path (Wave 2).**

E1 perpetuates split authority, which is the root cause identified in iterations 2 and 3. E3 introduces significant operational infrastructure that is premature before the basic closed loop exists. E4 is the right long-term direction but requires building a new service from scratch — too expensive before E2 is proven.

E2 migration sequence:
1. Complete the `OrderPacked` schema in `contracts/` — add `pickupLat`, `pickupLng`, `storeCellId`, `prepTimeSec`.
2. Add a dispatch request queue in `dispatch-optimizer-service` with at-least-once Kafka consumption + DLQ.
3. Remove direct assignment code from `fulfillment-service`; emit `DispatchRequested` event only.
4. `dispatch-optimizer-service` consumes `DispatchRequested`, runs the VRP pass, emits `RiderAssigned`.
5. `rider-fleet-service` acts on `RiderAssigned` to update rider state machine.
6. Add a stuck-rider sweeper in `rider-fleet-service`: any rider in `ASSIGNED` with no location update in N minutes → emit `RiderStuck` → re-assignment.
7. Feed live ETA from `routing-eta-service` back to fulfillment and notification.

**Key references:**
- Vehicle routing problem (Google OR-Tools): https://developers.google.com/optimization/routing
- Dispatch at Blinkit scale (public interview transcript references): https://www.blinkit.com/engineering
- Event-driven logistics control loops: https://www.confluent.io/blog/event-driven-microservices/

---

## 6. Outbox and Event Delivery Guarantees

### Current state

`outbox-relay-service` is one of the stronger implementations in the repo. However, the platform is undermined by envelope inconsistency (snake_case vs camelCase, header-only vs body-embedded metadata), ghost events without schemas, singular/plural topic drift (`order.events` vs `orders.events`), and unsafe commit semantics in several Go consumers. `payment-webhook-service` acknowledges a webhook and then does async Kafka publish — the gap between acknowledgment and durable publish is a correctness window. `stream-processor-service` uses `LastOffset`, has no DLQ, and holds critical session state only in memory.

### Approaches

| ID | Name | Summary |
|----|------|---------|
| F1 | Shared publish/consume libraries per language stack + CI compatibility gates | Standardize in-process; enforce envelope and compatibility via `buf breaking` + JSON schema CI |
| F2 | External schema registry (Confluent Schema Registry or AWS Glue) | Registry-centric governance; all producers/consumers register and validate against central registry |
| F3 | Event mesh with broker-side routing and filtering (Kafka Streams topology) | Move routing/filtering to broker topology; reduce consumer-side coupling |
| F4 | Minimal: manual review + documentation enforcement | Keep current implementations, add documentation-only standards |

### Comparison matrix

| Criterion | F1 — Shared libraries + CI | F2 — External registry | F3 — Broker topology | F4 — Manual only |
|-----------|:--------------------------:|:---------------------:|:-------------------:|:----------------:|
| **Envelope consistency** | 🟢 Enforced at compile time | 🟢 Enforced at runtime + CI | 🟡 Enforced at topology | 🔴 Not enforced |
| **Breaking change prevention** | 🟢 `buf breaking` blocks merge | 🟢 Compatibility mode blocks publish | 🟡 Topology version management | 🔴 None |
| **Operational overhead** | 🟢 Low — libraries, no new infra | 🟡 Medium — registry cluster | 🔴 High — topology ops | 🟢 Lowest |
| **Schema discovery** | 🟡 Library documentation | 🟢 Registry UI and API | 🟡 Topology introspection | 🔴 Manual |
| **Latency impact of validation** | 🟢 Compile-time only | 🟡 +1–5 ms validation per message | 🟢 Async topology | 🟢 None |
| **Handles ghost events** | 🟡 CI will catch undeclared producers | 🟢 Unregistered schemas fail at runtime | 🟡 Topology must be updated | 🔴 No detection |
| **Migration cost** | 🟡 Medium — library adoption across stacks | 🔴 High — registry deployment + all producer updates | 🔴 High — topology redesign | 🟢 Lowest |

### InstaCommerce recommendation

**F1 immediately (Wave 0/Wave 4), with F2 as a Wave 5 evolution.**

The repo already has a polyglot stack (Java, Go, Python). Introducing a registry (F2) before the envelope is even consistent in code is operationally backwards — the registry will just formalize the existing inconsistency. F3 is a significant architectural shift that requires topology design discipline the platform does not yet have.

F1 action items:
- Java: create `com.instacommerce.contracts.EventEnvelope` DTO and publish/consume helper in `contracts/` as a shared library.
- Go: create `go-shared/kafka/envelope.go` with a typed `EventEnvelope` struct; all Go producers use it.
- Python: create `contracts/python/envelope.py` Pydantic model.
- CI: add `buf breaking` for proto changes and a JSON Schema compatibility check (AJV CLI) for event schemas in `.github/workflows/ci.yml`.
- Fix `payment-webhook-service`: persist webhook to outbox table first, return 200 only after commit, then relay — not before.
- Fix `stream-processor-service`: switch from `LastOffset` to explicit committed offset; add DLQ topic; externalize state to Redis or RocksDB.

**Key references:**
- Outbox pattern (Chris Richardson): https://microservices.io/patterns/data/transactional-outbox.html
- Buf CLI breaking change detection: https://buf.build/docs/breaking/overview
- Confluent Schema Registry: https://docs.confluent.io/platform/current/schema-registry/index.html
- Kafka exactly-once semantics: https://www.confluent.io/blog/exactly-once-semantics-are-possible-heres-how-apache-kafka-does-it/

---

## 7. CI Governance

### Current state

CI path filters in `.github/workflows/ci.yml` cover Java and Go matrices for PR/develop, with full runs on main/master. However: Python services (`ai-orchestrator-service`, `ai-inference-service`) have no automated test jobs. `contracts/` has no schema compatibility gate. `data-platform/`, `ml/`, `deploy/helm/`, and `infra/terraform/` lack CI validation steps. Some Go module names differ from their Helm deploy keys without explicit mapping documentation. CODEOWNERS is absent. There are no ADR process files.

### Approaches

| ID | Name | Summary |
|----|------|---------|
| G1 | Incremental CI expansion: add Python, contracts, infra jobs to existing workflow | Extend `.github/workflows/ci.yml` with new jobs; maintain single-workflow structure |
| G2 | Federated workflow architecture: separate workflow files per domain or stack | Split into `ci-java.yml`, `ci-go.yml`, `ci-python.yml`, `ci-contracts.yml`, `ci-infra.yml` |
| G3 | Platform engineering CI abstraction (reusable workflows, composite actions) | Create `.github/workflows/templates/` reusable workflows called from lightweight per-service callers |
| G4 | External CI platform (CircleCI, BuildKite, GitHub Actions at enterprise scale) | Migrate all CI to a richer external platform with better parallelism and caching |

### Comparison matrix

| Criterion | G1 — Extend single workflow | G2 — Federated workflows | G3 — Reusable/composite | G4 — External platform |
|-----------|:---------------------------:|:------------------------:|:-----------------------:|:---------------------:|
| **Implementation speed** | 🟢 Fast — add jobs to existing | 🟡 Medium — file split + test | 🟡 Medium — template authoring | 🔴 Slow — migration |
| **Blast radius of CI change** | 🔴 One bad merge breaks all | 🟢 Isolated per domain | 🟢 Template update + test | 🟢 Fully isolated |
| **Path filter accuracy** | 🟡 All in one filter block | 🟢 Natural per-file scoping | 🟢 Per-template scoping | 🟢 Native scoping |
| **Contract compatibility enforcement** | 🟡 Needs job added | 🟢 Own workflow | 🟢 Own workflow | 🟢 Configurable |
| **Onboarding new services** | 🟡 Edit growing workflow | 🟢 Copy workflow file | 🟢 Reference reusable template | 🟡 Platform config |
| **GitHub Actions minutes cost** | 🟢 Minimal change | 🟢 Natural path filtering | 🟡 Slight overhead per template call | 🔴 Increased cost |
| **Operational maturity needed** | 🟢 Low | 🟡 Medium | 🔴 High — requires workflow discipline | 🔴 Highest |

### InstaCommerce recommendation

**G1 immediately to close P0 gaps (Wave 0); G2 as a Wave 3 refactor once the service count is stable.**

The most important thing right now is that Python services, contracts, Helm, and Terraform have any CI at all. Splitting workflows (G2) before the existing workflow is honest creates organizational overhead without correctness gain. G3 is the right long-term architecture for a platform with 28+ services, but requires workflow authoring discipline that should come after the basics are covered.

G1 additions to `.github/workflows/ci.yml`:
```yaml
# Python services
python-ai-services:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-python@v5
      with: { python-version: '3.11' }
    - run: |
        cd services/ai-orchestrator-service && pip install -r requirements.txt && pytest -v
        cd ../ai-inference-service && pip install -r requirements.txt && pytest -v

# Contracts compatibility
contracts-compat:
  runs-on: ubuntu-latest
  steps:
    - uses: bufbuild/buf-action@v1
      with: { input: contracts/, breaking_against: HEAD~1 }
    - run: ./gradlew :contracts:build

# Helm lint
helm-lint:
  runs-on: ubuntu-latest
  steps:
    - uses: azure/setup-helm@v3
    - run: helm lint deploy/helm/instacommerce/

# Terraform validate
terraform-validate:
  runs-on: ubuntu-latest
  steps:
    - uses: hashicorp/setup-terraform@v3
    - run: cd infra/terraform && terraform init -backend=false && terraform validate
```

Also add `CODEOWNERS` at repo root mapping service directories to owning teams.

**Key references:**
- GitHub Actions reusable workflows: https://docs.github.com/en/actions/using-workflows/reusing-workflows
- Buf action for CI: https://buf.build/docs/bsr/ci-cd/github-actions
- CODEOWNERS reference: https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners

---

## 8. Data Correctness (Event-Time vs Processing-Time)

### Current state

Beam pipelines in `data-platform-jobs/` use processing time rather than event time for windowing and aggregation. This produces semantically incorrect analytics under any late-data scenario (which is common: retried events, mobile clients with connectivity gaps, CDC lag bursts). The dbt layer lacks explicit timestamp discipline — joins across tables use `created_at` (wall-clock) rather than event-time fields. The Airflow DAG callback wiring is incomplete for several critical jobs. Feature store paths have SQL field name drift (`delivery_eta` vs `eta_prediction`).

### Approaches

| ID | Name | Summary |
|----|------|---------|
| H1 | Add event-time watermarks and allowed lateness to existing Beam pipelines | Minimal structural change; correct the windowing strategy in existing code |
| H2 | Rebuild analytics layer on Apache Flink with native event-time semantics | Replace Beam/Dataflow with Flink for streaming; cleaner event-time model |
| H3 | Move all streaming to BigQuery Streaming Inserts + MERGE upsert semantics | Skip Beam entirely; stream raw events to BQ, use MERGE for dedup |
| H4 | Dual write: Beam for analytics + Kafka Streams for operational decision features | Separate analytics and operational feature pipelines by tooling |

### Comparison matrix

| Criterion | H1 — Fix Beam event-time | H2 — Rebuild with Flink | H3 — BQ Streaming + MERGE | H4 — Dual write |
|-----------|:------------------------:|:-----------------------:|:-------------------------:|:--------------:|
| **Analytics correctness under late data** | 🟢 Correct with watermarks | 🟢 Native event-time | 🟡 Depends on MERGE logic | 🟢 If both correct |
| **Feature freshness for ML** | 🟡 Watermark lag adds delay | 🟢 Low latency Flink | 🟢 Near-real-time via BQ | 🟢 Kafka Streams is fast |
| **Implementation complexity** | 🟡 Medium — watermark tuning | 🔴 High — new stack | 🟡 Medium — MERGE SQL patterns | 🔴 High — two stacks to maintain |
| **Cost at q-commerce write volume** | 🟡 Dataflow autoscaling | 🟡 Flink cluster management | 🔴 BQ Streaming Insert cost at volume | 🔴 Highest infrastructure cost |
| **Migration cost** | 🟡 Medium — code change, no infra change | 🔴 Highest — new stack | 🟡 Medium — if BQ already exists | 🔴 High |
| **Operational burden** | 🟡 Dataflow managed | 🔴 Self-managed Flink | 🟢 BQ is fully managed | 🔴 Two systems |
| **Rollback posture** | 🟢 Pipeline redeployment | 🔴 Topology swap | 🟢 SQL-level rollback | 🟡 One pipeline can be paused |

### InstaCommerce recommendation

**H1 immediately (Wave 4); evaluate H3 as a Wave 5 evolution for the analytics tier.**

The platform already runs on GCP with Dataflow. Introducing Flink (H2) creates an operational burden that is not justified before Beam is corrected. H3 (BQ Streaming) is attractive for its managed simplicity, but BQ Streaming Insert costs can be significant at q-commerce event volume and require careful MERGE dedup design. H1 is the minimum viable fix.

H1 implementation pattern:
```java
// Before (processing-time window) — WRONG
events.apply(Window.into(FixedWindows.of(Duration.standardMinutes(5))));

// After (event-time window with watermark) — CORRECT
events
  .apply(WithTimestamps.of(e -> Instant.parse(e.getEventTime())))
  .apply(Window.into(FixedWindows.of(Duration.standardMinutes(5)))
    .withAllowedLateness(Duration.standardMinutes(30))
    .accumulatingFiredPanes())
  .apply(Combine.globally(new AggregationFn()).withoutDefaults());
```

Also:
- Fix dbt models to use `event_time` or `occurred_at` columns rather than `created_at` for temporal joins.
- Resolve `delivery_eta` vs `eta_prediction` field name drift in feature SQL — standardize to one name in `ml/features/`.
- Add BQ upsert semantics to prevent late-data duplicates in analytical tables.

**Key references:**
- Beam event-time processing: https://beam.apache.org/documentation/programming-guide/#watermarks-and-late-data
- dbt timestamp best practices: https://docs.getdbt.com/blog/when-to-use-incremental-materializations
- Event-time semantics (the morning paper / Akidau et al.): https://www.oreilly.com/library/view/streaming-systems/9781491983867/

---

## 9. ML Model Promotion

### Current state

`ml/` has training pipelines, evaluation scripts, and a model registry concept. However, iteration 3 found that production inference in `ai-inference-service` still runs stub implementations rather than the intended ONNX-serialized artifacts. Shadow inference runs inline (same request path, not a parallel path), so shadow disagreement does not survive pod restarts or cross-pod visibility. There are no explicit champion/challenger gates, no deployment A/B split mechanism, and the ONNX serving path is not the default live path.

### Approaches

| ID | Name | Summary |
|----|------|---------|
| I1 | Conservative staged rollout: shadow → canary → champion (within existing inference service) | Keep one inference service; add shadow comparison, then traffic-split canary, then promote |
| I2 | Blue/green model serving with separate Kubernetes deployments | Run old and new model as separate pods; shift traffic via Istio weight |
| I3 | Feature store + online serving platform (Feast, Tecton, or Vertex AI Feature Store) | Centralize feature serving with point-in-time correctness guarantees; decouple training from serving |
| I4 | LLM-augmented model decisions (prompt-engineering replaces traditional ML models) | Replace ONNX models with LLM tool calls for recommendation, ETA, and fraud |

### Comparison matrix

| Criterion | I1 — Staged shadow/canary | I2 — Blue/green deployments | I3 — Feature store platform | I4 — LLM replacement |
|-----------|:------------------------:|:---------------------------:|:---------------------------:|:--------------------:|
| **Promotion safety** | 🟢 Shadow disagree gates | 🟢 Hard traffic boundary | 🟡 Depends on serving correctness | 🔴 No deterministic gate |
| **Feature drift risk** | 🟡 Training/serving gap still possible | 🟡 Same gap | 🟢 Point-in-time correct | 🔴 Prompt drift not measurable |
| **Inference latency impact** | 🟡 Shadow adds ~2× compute per request | 🟢 No overhead on champion | 🟡 Feature lookup adds 2–10 ms | 🔴 LLM adds 50–500 ms |
| **Rollback speed** | 🟢 Feature flag revert | 🟢 Weight 0 in minutes | 🟡 Feature store rollback complex | 🔴 Prompt rollback unreliable |
| **Migration cost** | 🟢 Low — wire existing paths | 🟡 Medium — K8s manifest + Istio | 🔴 High — new platform | 🔴 Highest — model redesign |
| **Handles ONNX artifact gap** | 🟢 Step 1 is wiring artifacts | 🟢 Same | 🟡 Needs serving integration | 🔴 No ONNX required |
| **Operational visibility** | 🟢 Metrics from shadow comparison | 🟢 Per-deployment metrics | 🟢 Built-in | 🔴 Low — LLM responses opaque |

### InstaCommerce recommendation

**I1 immediately (Wave 4); I2 as parallel infrastructure rollout during Wave 4; defer I3 to Wave 5.**

The blocking issue is that inference stubs need to be replaced with actual ONNX-loaded artifacts before any promotion strategy matters. I4 (LLM replacement) is inappropriate for latency-sensitive paths like ETA and fraud scoring. I3 is the right architectural destination but requires platform investment that should follow — not precede — basic model serving correctness.

I1 implementation steps:
1. In `ai-inference-service`, load ONNX artifact from GCS/model registry at startup (fail-fast if unavailable).
2. Move shadow inference to a separate async task (not inline on the request path): `CompletableFuture.runAsync(() -> shadowInfer(input))`.
3. Persist shadow comparison results to a dedicated BigQuery table: `model_shadow_log(model_version, input_hash, champion_output, challenger_output, agree, ts)`.
4. Define promotion gate: challenger must match champion on ≥95% of shadow comparisons over 24-hour rolling window before any traffic split.
5. Gate the first canary split (5% traffic) behind a feature flag in `config-feature-flag-service`.
6. Add model rollback: any per-model error rate spike > 2× baseline triggers automatic weight revert.

**Key references:**
- ML model deployment patterns (Google MLOps): https://cloud.google.com/architecture/mlops-continuous-delivery-and-automation-pipelines-in-machine-learning
- ONNX Runtime serving: https://onnxruntime.ai/docs/tutorials/model-deployment.html
- Shadow testing for ML (Uber engineering): https://www.uber.com/blog/machine-learning-model-lifecycle/
- Champion/challenger testing (Seldon): https://docs.seldon.io/projects/seldon-core/en/latest/examples/

---

## 10. AI Agent Controls

### Current state

`ai-orchestrator-service` has a LangGraph-based agent design in documentation and in parts of the codebase. However, iteration 3 found that the live runtime path (`app/main.py` `/agent/*` route) bypasses the documented guardrails. LangGraph checkpoint persistence is not wired in the live path. Safety components (policy checks, tool-call budget limits) are documented but not invoked from the active execution path. No HITL (human-in-the-loop) gate exists for write actions. No kill switch tied to `config-feature-flag-service` exists at the agent dispatch layer.

### Approaches

| ID | Name | Summary |
|----|------|---------|
| J1 | Conservative read-only AI with explicit write-action HITL gate | AI stays on retrieval, recommendation, and summarization; any write action requires human approval via an async approval queue |
| J2 | Fully autonomous AI agents with runtime guardrails | AI can take write actions directly; safety is enforced by in-process policy evaluation and rate limits |
| J3 | Propose-only AI with batch execution | AI generates action proposals; a separate human review batch approves and executes them |
| J4 | Freeze AI agent capabilities; focus only on passive analytics | No agentic behavior; AI only serves read queries and dashboards |

### Comparison matrix

| Criterion | J1 — Read-only + HITL writes | J2 — Autonomous + guardrails | J3 — Propose-only batch | J4 — Passive analytics |
|-----------|:---------------------------:|:----------------------------:|:-----------------------:|:---------------------:|
| **Risk of incorrect write action** | 🟢 HITL prevents autonomous harm | 🔴 Runtime guardrail gap = production harm | 🟢 Human batch approval | 🟢 Zero write risk |
| **Operational leverage** | 🟢 High — copilot for all teams | 🟡 High but unsafe today | 🟡 Medium — batch latency | 🔴 Low |
| **Latency to action** | 🟡 Depends on HITL response time | 🟢 Immediate | 🔴 Hours for batch approval | 🟢 Read-only is fast |
| **Auditability** | 🟢 Approval queue is full log | 🟡 Depends on audit trail wiring | 🟢 Full proposal log | 🟢 Read-only trivially auditable |
| **Kill switch effectiveness** | 🟢 Feature flag disables agent tools | 🟡 Guardrail bypass → no kill switch | 🟢 Halt batch queue | 🟢 N/A |
| **Migration cost** | 🟡 Medium — wire guardrails + approval queue | 🔴 High — robust policy engine needed | 🟡 Medium — build proposal store | 🟢 Lowest |
| **Alignment to current maturity** | 🟢 Right for Wave 4–6 | 🔴 Wrong for current state | 🟢 Good bridge | 🔴 Wastes existing investment |

### InstaCommerce recommendation

**J1 immediately (Wave 4/6); J3 as an optional bridge for operational copilots before J1 is fully proven.**

J2 (fully autonomous) requires a robust, battle-tested policy enforcement layer that does not yet exist in the repo. The current guardrail code is documented but not on the live execution path — making J2 effectively unguarded automation today. J4 abandons real leverage. J3 (propose-only batch) is a pragmatic bridge for use cases like substitution suggestions and fraud review, where human review latency is acceptable.

J1 implementation steps:
1. Wire `PolicyCheckNode` and `ToolBudgetNode` onto the live `app/main.py` execution path; fail agent start if these nodes are not reachable in the LangGraph DAG.
2. Implement kill switch: agent tool dispatch checks `config-feature-flag-service` for `ai.agent.tools.{toolName}.enabled` before executing. Default: disabled.
3. Add HITL approval queue: write-action tool calls emit an `AgentActionProposal` event to Kafka; a `human-review-service` (or Slack integration) approves/rejects; agent waits on approval callback with a 10-minute timeout.
4. Wire LangGraph checkpoint persistence: use Redis or Postgres-backed checkpointer so agent state survives pod restarts.
5. Define tool risk tiers:
   - **Tier 0 (read-only):** catalog lookup, order status, analytics query — always allow.
   - **Tier 1 (low-risk write):** notification draft, substitution suggestion — allow with audit log.
   - **Tier 2 (high-risk write):** inventory mutation, order state change, payment action — require HITL approval.
   - **Tier 3 (prohibited):** direct DB writes, infrastructure changes — deny always.

Prohibited initial autonomous actions:
- payment capture or refund
- inventory reserve/confirm/release
- order-state mutation
- rider dispatch or reassignment
- price writes
- feature-flag writes without approval

**Key references:**
- LangGraph checkpointing and persistence: https://langchain-ai.github.io/langgraph/concepts/persistence/
- Human-in-the-loop for agents: https://langchain-ai.github.io/langgraph/concepts/human_in_the_loop/
- AI safety for production systems (Anthropic): https://www.anthropic.com/research/core-views-on-ai-safety
- Risk-tiered AI governance (NIST AI RMF): https://airc.nist.gov/RMF_Overview

---

## Summary: Recommended choices at a glance

| Domain | Approach | Wave | P0 action |
|--------|----------|------|-----------|
| Edge auth | A4 — Hybrid Istio + OPA | Wave 0 + Wave 2 | Deny admin traffic at mesh; fix route path rewrites |
| Checkout authority | B1 — Orchestrator wins | Wave 1 | Fix idempotency key; add 202 async; add feature flag; remove order-service checkout |
| Pricing/cart coupling | C1 — Fix contract + quote tokens | Wave 1 | Fix API path mismatch; fix promo `maxUses` bug; add signed quote JWT |
| Inventory reservation | D1 — Fix in-place + `@Version` | Wave 2 | Fix reservation API path; add `@Version`; correct event field names |
| Dispatch assignment | E2 — Optimizer as sole authority | Wave 2 | Complete `OrderPacked` schema; remove assignment from fulfillment; wire optimizer |
| Outbox/eventing | F1 — Shared libraries + CI | Wave 0 + Wave 4 | Fix webhook durability; add CI `buf breaking` check; fix stream-processor DLQ |
| CI governance | G1 — Extend existing workflow | Wave 0 | Add Python, contracts, Helm, Terraform jobs; add CODEOWNERS |
| Data correctness | H1 — Fix Beam event-time | Wave 4 | Add watermarks + allowed lateness; fix dbt timestamp discipline |
| ML promotion | I1 — Staged shadow/canary | Wave 4 | Load real ONNX artifacts; move shadow async; add promotion gate |
| AI agent controls | J1 — Read-only + HITL | Wave 4–6 | Wire guardrails to live path; add kill switch; define tool risk tiers |

---

## Migration cost summary

| Domain | Short (days) | Medium (weeks) | Long (months) |
|--------|:-----------:|:--------------:|:-------------:|
| Edge auth | A1 mesh policy fixes | A4 OPA rollout | Managed gateway if ever |
| Checkout authority | Feature flag routing | Idempotency + async | Remove dead code |
| Pricing/cart | API path fix + promo bug | Quote token | Cache invalidation at scale |
| Inventory | Path fix + migration | `@Version` + event fix | Store-type depth |
| Dispatch | Schema completion | Optimizer wiring | ETA closed loop |
| Outbox/eventing | Webhook durability fix | Shared library rollout | Schema registry |
| CI governance | Job additions | CODEOWNERS + ADR | Federated workflows |
| Data correctness | Watermark code change | dbt timestamp audit | BQ upsert semantics |
| ML promotion | Artifact wiring | Shadow async | Feature store |
| AI agent controls | Guardrail wiring | HITL queue | Autonomous tier gradual |

---

*This appendix is a living document. It should be updated as the implementation program progresses through waves and as approach choices are validated or revised by production evidence.*
