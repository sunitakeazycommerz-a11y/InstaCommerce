# InstaCommerce Principal Engineering Review - Iteration 2 Deep Dive

**Date:** 2026-03-06  
**Audience:** CTO, Principal Engineers, Staff Engineers, SRE, Security, Data/ML, Platform, Product Engineering  
**Scope:** Codebase-depth review of the production repository, web-referenced best practices, competitor-pattern comparison, implementation guide, and governance model  
**This document:** supersedes the lighter first-pass principal review in `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-2026-03-06.md`

---

## 1. Executive verdict

InstaCommerce has the **right macro-architecture** for a serious q-commerce platform and the **wrong level of operational hardening** for one.

At a high level, the repo is pointed in the right direction:

- domain-oriented microservices
- database-per-service boundaries
- Temporal for workflow orchestration
- outbox plus Kafka for asynchronous propagation
- separate logistics and data planes
- a real ML/AI ambition
- GitOps and infra-as-code posture

At the code and runtime level, however, the second review found a more uncomfortable truth:

- documentation frequently overstates reality
- the edge plane is partially stubbed and contract-broken
- checkout ownership is duplicated
- payment and webhook flows still have correctness risks
- inventory and warehouse primitives are usable but not yet dark-store-grade
- fulfillment, rider, dispatch, ETA, and location contracts do not yet form a reliable closed loop
- contracts and topics drift across services
- the data platform and ML platform are promising but materially behind the docs
- observability, testing, and governance are far weaker than a production q-commerce backend should tolerate

### Bottom-line judgment

This repository looks like a **strong architecture program** that has not yet crossed the line into a **fully trustworthy production operating system**.

If benchmarked against top-tier operators:

- **architecture direction:** good
- **implementation consistency:** mixed
- **production rigor:** weak to mixed
- **governance maturity:** weak
- **AI/agent foundations:** promising but not production-governed yet

The biggest difference between InstaCommerce and top operators such as Instacart, DoorDash, Zepto, Blinkit, and Swiggy Instamart is **not service count**. It is:

1. authority clarity  
2. latency discipline  
3. contract discipline  
4. operational loop tightness  
5. governance and validation depth

---

## 2. Review method

This second iteration intentionally went beyond the first pass.

### Review approach

- Reviewed repo docs against actual code and configuration
- Ran **23 parallel deep-dive sub-agents** across domain, platform, data, ML, AI, infra, and competitor/best-practice workstreams
- Cross-checked service READMEs against actual code in Java, Go, and Python
- Cross-checked CI, Helm, ArgoCD, Terraform, monitoring, contracts, and docs
- Added external reference points for:
  - cell-based architecture
  - SLOs and incident governance
  - idempotent API design
  - experimentation quality
  - AI governance
  - Instacart public engineering material
  - India q-commerce public company and public-site material

### Important limitations

- Some direct competitor engineering sources were blocked by anti-bot or returned 403/404 during fetches, especially DoorDash and some Zepto/Blinkit pages.
- Local Java execution was limited because the environment did not have a usable Java runtime; several agents therefore validated by source review rather than runnable Gradle tests.
- This review is still based on the repository and accessible public web material, not internal production telemetry.

That said, the code and config evidence is already strong enough to make high-confidence calls.

---

## 3. Production reality check: what the repo claims vs what the repo shows

This was one of the most important findings of the second pass.

| Area | Repo/document claim | What the repo actually shows | Principal verdict |
|---|---|---|---|
| Documentation fidelity | Strong, current, authoritative docs | Multiple broken paths, stale names, inconsistent service counts, and conflicting truth sources | Weak |
| Build/test maturity | Production-grade presentation | Java services have no committed `src/test`, Go modules have no `*_test.go`, Python services have no test directories found | Weak |
| BFF/admin gateway maturity | Mobile BFF and admin gateway described as real edge services | Both are mostly scaffold/stub implementations in code | Weak |
| Search maturity | Production-grade search stack | Postgres FTS plus sparse event sync and weak ranking depth | Mixed |
| Checkout authority | Checkout orchestrator as workflow owner | Checkout logic exists in both `checkout-orchestrator-service` and `order-service` | Weak |
| Eventing | Canonical event contracts and strong event flow | Singular/plural topic drift, envelope drift, schema mismatches, and producer/consumer ambiguity | Weak |
| ML/AI runtime | LangGraph/guardrail-rich orchestrator and broad model serving | Live runtime is thinner than docs; much of the richer architecture is not wired into execution paths | Mixed |
| Platform governance | Strong enterprise-style posture | Missing CODEOWNERS, ADRs, strong test gates, full-stack CI coverage, and mature SLO governance | Weak |

The first thing leadership should internalize is this:

> The repo is materially more mature as a design narrative than as an implementation control plane.

---

## 4. Deep architecture findings by domain

## 4.1 Module topology and repository shape

### What is structurally right

- `settings.gradle.kts` cleanly defines 20 Java services.
- Go services are broken out as independent modules with `go-shared` for reuse.
- Python services exist separately for orchestration and inference.
- `data-platform/`, `data-platform-jobs/`, and `ml/` are separated, which is the correct high-level shape.

### What is materially wrong

- Deploy reality and repo reality are not aligned:
  - Helm does not cover every module cleanly
  - `stream-processor-service` appears in CI but is missing from Helm deployment keys
  - service names and port names drift between docs and deploy artifacts
- `mobile-bff-service` and `admin-gateway-service` are presented as core edge components but are effectively thin scaffolds.
- Data and ML boundaries are duplicated and partially inconsistent:
  - overlapping responsibilities across `data-platform/` and `data-platform-jobs/`
  - inconsistent feature path assumptions
  - schema/name drift like `delivery_eta` vs `eta_prediction`

### Principal conclusion

This is a **good monorepo shape with poor topology hygiene**. That matters because topology drift creates silent operational risk:

- wrong things get built or deployed
- the wrong teams believe they own things
- docs stop being trustworthy

---

## 4.2 Edge, BFF, admin gateway, and identity

### Strong design intent

- Edge segmentation via Istio plus BFF/admin entrypoints is the right architectural choice.
- Identity is treated as a first-class service.
- Internal-service auth exists conceptually.

### Hard findings

- **Routing contracts are broken at the edge**: Helm/Istio expose `/api/v1/auth`, `/api/v1/users`, and `/api/v1/checkout`, while controller paths are `/auth`, `/users`, and `/checkout`, and no rewrite is visible in `deploy/helm/templates/istio/virtual-service.yaml`.
- **Mobile BFF is not what the README says it is**: code is thin, there is no real security chain, and the advertised aggregation/caching/circuit-breaking behavior is not present as a serious implementation.
- **Admin gateway is also scaffold-level**: it likely falls back to Spring default security behavior rather than a real JWT/RBAC gateway stack because the code lacks a dedicated security configuration.
- **Internal trust is dangerously flattened**: the shared `INTERNAL_SERVICE_TOKEN` model and the identity internal auth filter effectively collapse multiple callers into the same trusted/admin identity.
- **Identity rate limiting is too weak**: in-memory, pod-local, and bypassable via forwarded headers.

### Why this matters in q-commerce

The edge plane is not a cosmetic layer in q-commerce. It is where:

- latency budgets are protected
- authn/authz is normalized
- retries and timeouts are controlled
- clients are decoupled from topology
- rollout safety is enforced

Right now, the edge plane is too thin to serve that role.

### Required changes

- Make the edge authoritative for external auth, quotas, error envelopes, and path normalization.
- Replace shared internal token trust with workload identity plus per-service authorization.
- Move rate limiting to a durable shared system.
- Implement real BFF/admin functionality or stop documenting them as production-grade.

---

## 4.3 Catalog and search

### Strong design intent

- Separate catalog and search is correct.
- Kafka/index synchronization is directionally right.
- Search as its own surface is correct for relevance and read scaling.

### Hard findings

- Search freshness is structurally suspect unless `catalog.events` are actually produced by the shared outbox relay in the intended way.
- Catalog and search are not aligned on event semantics:
  - sparse payload assumptions
  - `ProductDeactivated` vs `ProductDelisted`
  - producer/consumer ambiguity
- Search remains too shallow for leading q-commerce:
  - English-only Postgres FTS
  - `ILIKE`-style autocomplete
  - weak typo tolerance
  - weak multilingual/semantic readiness
  - no serious inventory-aware ranking loop
  - no clearly governed experimentation layer

### Competitor gap

Instacart public engineering material shows a materially stronger direction:

- hybrid retrieval
- semantic recall
- adaptive retrieval controls
- high freshness at large write volume
- discovery surfaces augmented with richer ML and LLM tooling

### Required changes

- Fix the event producer/consumer contract first.
- Choose one authoritative search surface and deprecate overlapping alternatives.
- Add store-level availability semantics to ranking.
- Introduce hybrid retrieval plus learned re-ranking before calling search “competitive.”

---

## 4.4 Cart and pricing

### Strong design intent

- Cart is correctly isolated as its own stateful service.
- Pricing is separately modeled, which is necessary for promotions and dynamic pricing.

### Hard findings

- Cart add-item behavior is retry-unsafe and additive rather than robustly idempotent.
- Checkout validation does not truly reprice or validate inventory deeply enough.
- Both services rely too much on pod-local Caffeine caches.
- There is no durable quote or quote-lock token model.
- Promotions and coupons lack stronger usage/governance semantics.
- A direct integration bug appears likely:
  - `cart-service` calls `/api/v1/prices/{productId}`
  - `pricing-service` exposes `/pricing/products/{id}`
  - base URL configuration is ignored by the client
- Outbox support exists but publisher wiring is incomplete/unclear.

### Principal conclusion

This is not yet a trustworthy q-commerce price/quote plane. It is a useful service decomposition that still lacks:

- quote integrity
- inventory awareness
- experimentation discipline
- burst-safe caching

### Required changes

- Introduce explicit quote issuance and lock semantics.
- Fix cart-to-pricing contract mismatches.
- Make price validation part of a stronger checkout path.
- Add shared invalidation and experimentation governance.

---

## 4.5 Checkout and order

### Strong design intent

- Temporal is the right primitive.
- Saga thinking is correct.
- Order state machines exist and are directionally sensible.

### Hard findings

- **Checkout ownership is duplicated** across `checkout-orchestrator-service` and `order-service`.
- Both checkout endpoints are **synchronous Temporal RPCs**, which pushes orchestration latency directly into the customer request path.
- Idempotency is inconsistent:
  - orchestrator persistence only after workflow completion
  - duplicate start handling is weak
  - downstream confirm/cancel/capture flows are not consistently protected
- Finalization safety is poor:
  - payment can be captured
  - inventory can be confirmed
  - order can still remain `PENDING`
- Integration path mismatch is likely:
  - orchestrator calls `/api/orders`
  - visible order-service controllers expose `/orders`

### Principal conclusion

This is the most important architecture flaw in the repo.

You do not have a clean answer to:

> which service owns checkout truth in production?

Until that is fixed, the money path remains operationally fragile.

### Required changes

- Make `checkout-orchestrator-service` the single cross-domain workflow owner.
- Remove duplicated checkout orchestration from `order-service`.
- Move the public API toward async completion semantics where appropriate.
- Add failure-injection tests for duplicate-start, confirm-then-fail, and capture-after-confirm paths.

---

## 4.6 Payment and payment webhooks

### Strong design intent

- Pending-state workflow shape is good.
- PSP calls are modeled with idempotency intent.
- Ledger thinking exists.
- Payment and webhook concerns are separated.

### Hard findings

- **Partial capture corrupts ledger semantics** by not releasing uncaptured authorization remainder correctly.
- Pending states rely too heavily on webhook convergence with no convincing sweeper/recovery path.
- `payment-webhook-service` can accept a webhook and still lose it:
  - dedupe is set before durable publish
  - Kafka is async with `RequireOne`
- Access control is too coarse for payment-grade data.
- Operational architecture drifts between:
  - direct `payment-service` webhook endpoint
  - deployed `payment-webhook-service`
  - reconciliation engine scaffolding

### Principal conclusion

This is not yet payment-grade operationally.

The service has many of the right building blocks, but the implementation still has enough correctness ambiguity that it should not be treated as fully hardened.

### Required changes

- Fix partial capture accounting.
- Add payment-state sweeping and stale-pending recovery.
- Make webhook persistence durable before dedupe acceptance.
- Clarify the single production webhook ingress architecture.
- Add true end-to-end reconciliation proof.

---

## 4.7 Inventory and warehouse

### Strong design intent

- Inventory truth and warehouse ops are split correctly.
- Reservation/confirm/cancel/expire semantics exist.
- Audit and outbox concepts exist.

### Hard findings

- Inventory state is too shallow:
  - mostly `on_hand` and `reserved`
  - ATP remains simplistic
- Low-stock alerting is noisy and global-threshold based.
- Capacity and reservation are not clearly composed atomically.
- Store topology is flat and not yet dark-store-grade:
  - no richer hub/spoke or picker-capacity semantics
  - no visible substitution-aware planning hooks
- No visible hot-SKU partition/shard strategy

### Principal conclusion

Inventory/warehouse are closer to production than several other domains, but still not at the operating depth of strong q-commerce systems.

### Required changes

- Expand inventory state model beyond `on_hand`/`reserved`.
- Add atomic capacity-plus-reserve semantics where needed.
- Make substitution and picker-capacity first-class.
- Plan for store or SKU hot-spot partitioning.

---

## 4.8 Fulfillment, rider fleet, dispatch, ETA, and location

This is where the gap to India q-commerce leaders is most visible.

### Strong design intent

- The repo understands that last-mile operations require dedicated services.
- Go is used for high-throughput operational workloads.
- Solver-based dispatch thinking is present.

### Hard findings

- **Fulfillment and rider-fleet are split-brain on assignment**:
  - fulfillment auto-assigns locally
  - rider-fleet also expects assignment-driving events
- **Contracts are broken**:
  - `OrderPacked` payload assumptions differ
  - rider-fleet expects fields fulfillment does not provide
- **Rider state recovery is incomplete**:
  - rider can be set to `ON_DELIVERY`
  - no convincing end-to-end delivered/cancelled recovery loop exists
- **Geo-stream contract is broken**:
  - location-ingestion emits rider-centric payloads
  - routing-eta consumes delivery-centric payloads
- **Dispatch v2 is not actually wired**
- ETA and risk logic are too naive:
  - traffic factor `1.0`
  - simplistic batching
  - poor breach prediction
  - geofence/H3/cell logic still mostly stubbed

### Competitor gap

Blinkit, Swiggy Instamart, and Zepto succeed operationally by making this a closed loop:

- store cell
- picker state
- rider state
- dispatch feasibility
- ETA confidence
- breach management
- reassignment/substitution

InstaCommerce currently has pieces of that system, not the full operating loop.

### Required changes

- Define one assignment authority.
- Fix event payload contracts across fulfillment, fleet, and ETA.
- Build a real rider-state recovery loop.
- Separate operational hot-path location state from analytical streaming concerns.
- Introduce city/store/zone cells for dispatch and ETA.

---

## 4.9 Notification and loyalty

### Strong design intent

- Notification and wallet/loyalty are correctly split from transactional core flows.

### Hard findings

- Notification stores raw recipient PII and does not fully erase message content on GDPR flows.
- Kafka consumers swallow exceptions, which risks bypassing broker retry and DLQ logic.
- Loyalty operations are not sufficiently concurrency-safe:
  - no strong locking/versioning on balance mutations
  - loyalty redemption is not truly idempotent
- `OrderDelivered`-driven reward flows can commit partially and lose cashback semantics after loyalty credit succeeds.

### Principal conclusion

Retention systems are not harmless sidecars. In q-commerce they touch money, trust, and customer communication. These two services need ledger-grade and replay-grade thinking, not just business logic.

---

## 4.10 Fraud, audit, and feature flags

### Strong design intent

- Fraud, audit, and feature flags are modeled as separate control-plane services.

### Hard findings

- Fraud is still largely additive rule scoring with weak evidence of real ML operationalization.
- Audit is append-only, but not convincingly tamper-evident.
- Audit exports remain synchronous and heavyweight.
- Feature-flag governance is weak:
  - no strong approval workflow for experiments
  - weak environment scoping
  - 30-second cache TTL weakens kill-switch immediacy

### Principal conclusion

These are the services that should make the rest of the system safer. Right now, they are useful but not yet strong enough to serve as a mature control plane.

---

## 4.11 Contracts, events, and the Go data plane

### Strong design intent

- Contract-first design exists.
- Shared schemas exist.
- Outbox relay is one of the stronger components in the repo.

### Hard findings

- Contract governance is inconsistent at runtime-critical levels:
  - snake_case vs camelCase envelope drift
  - local envelope DTO proliferation
  - singular vs plural topic drift
  - rider-location topic drift
  - consumer/schema mismatches across fraud, wallet, routing, search
- `outbox-relay-service` is solid relative to the rest of the platform, but even its README overclaims exactly-once semantics.
- `payment-webhook-service` can lose acknowledged events.
- `stream-processor-service` is not production-grade durable streaming:
  - `LastOffset`
  - no DLQ
  - critical state only in memory
  - no readiness
- `reconciliation-engine` is scaffold-level rather than finance-grade.

### Principal conclusion

The event plane is **directionally correct, operationally inconsistent**. That is a dangerous middle state because teams trust the abstraction while the implementation still leaks.

### Required changes

- Establish one canonical envelope and topic namespace.
- Add runtime schema enforcement and CI contract gates.
- Harden Go consumers around DLQ, readiness, offset semantics, and shutdown behavior.
- Treat reconciliation as a first-class production subsystem, not a file-backed tool.

---

## 4.12 Data platform and ML platform

### Strong design intent

- Correct macro stack: streaming, dbt, Airflow, feature work, ML plans.
- Good business coverage for q-commerce analytics and ML use cases.

### Hard findings

- Data platform semantics are materially behind the docs:
  - not truly event-time-safe
  - weak dedup/idempotency
  - likely wrong business joins and attribution
  - broken or mismatched Airflow callback wiring
  - missing feature SQL paths
  - overlapping job layers with conflicting assumptions
- ML platform is still thin operationally:
  - weak point-in-time correctness confidence
  - thin model registry semantics
  - generic evaluation and shadowing
  - direct 100% deployment assumptions
  - pricing ML path not convincingly implemented from repo evidence

### Principal conclusion

Data and ML are ahead at the roadmap level and behind at the operational data-contract level. This is common in ambitious platforms, but it must be corrected before ML drives business-critical decisions.

### Required changes

- Cleanly define the canonical data-product layer.
- Fix semantic correctness before scaling pipelines.
- Enforce feature freshness and offline/online parity.
- Add real model promotion, rollback, and evaluation governance.

---

## 4.13 AI services and agent runtime

### Strong design intent

- The AI orchestrator roadmap is strong.
- The repo shows awareness of policy checks, tool control, and budget limits.
- The first-pass AI strategy direction was correct.

### Hard findings

- Live runtime does not match docs:
  - legacy `/agent/*` flow in `app/main.py`
  - no real LangGraph-v2 runtime in the active path
  - no actual checkpoint persistence in the live route
  - documented safety components are not actually invoked in the live path
- Inference service runtime is thinner than the docs:
  - only a narrower subset of model behaviors is actually wired
  - shadow inference runs inline
  - readiness does not reflect degraded dependency states well

### Principal conclusion

AI services are promising prototypes with strong design instincts, not yet production-grade AI control planes.

### AI boundary recommendation

Keep AI read-only or recommendation-first for:

- support
- search/discovery assistance
- substitution suggestions
- merchandiser copilots
- incident and analytics copilots

Do not put AI directly on authority paths for:

- payment
- refunds
- inventory truth
- order-state mutation
- dispatch authority
- price finalization

---

## 5. Competitor and best-practice benchmark

## 5.1 US operator patterns

### Instacart

Instacart public engineering references show a materially stronger maturity in:

- search retrieval and hybrid recall
- discovery-quality investment
- dbt/data-platform enablement
- payment/platform sophistication
- experiment and ML culture

InstaCommerce is closest to Instacart in architectural ambition, but not yet in production depth.

### DoorDash

Direct engineering citations were limited by source blocking, but the public pattern remains clear:

- cell-based thinking
- logistics optimization and ETA confidence
- robust platform enablement
- ML and experimentation discipline

InstaCommerce is behind on:

- logistics loop maturity
- cell isolation
- operational SLO governance
- platform control systems

## 5.2 India q-commerce operator patterns

Across Zepto, Blinkit, and Swiggy Instamart, the strongest visible competitive patterns are:

- dark-store footprint depth
- operational tightness around store cells
- picker/packer/dispatch control loops
- inventory truth as an operating problem, not just a database problem
- low-latency customer path as a product and ops discipline

InstaCommerce has the service decomposition to aim at this, but not yet the same loop closure.

## 5.3 Best-practice references that matter most here

The most relevant external practices for this repo are:

- **cell-based architecture** for fault isolation and predictability
- **graceful degradation** for customer-facing flows
- **idempotent receiver/idempotency keys** for money and inventory safety
- **SLO and error-budget-driven alerting**
- **trustworthy experimentation**
- **risk-tiered AI governance**

---

## 6. Principal risk register

These are the highest-priority production risks, in order.

1. **Checkout truth is duplicated**
2. **Payment correctness is not yet fully trustworthy**
3. **Edge routing/auth contracts are broken or overly stubbed**
4. **Event contracts/topics are inconsistent**
5. **Catalog/search eventing and discovery quality are behind target**
6. **Cart and pricing do not yet provide a reliable quote plane**
7. **Fulfillment/fleet/ETA loop is not closed**
8. **Location and ETA contracts are mismatched**
9. **Data platform semantics are not trustworthy enough yet**
10. **ML platform lacks serious promotion/rollback controls**
11. **AI runtime is thinner than documentation claims**
12. **Observability and incident governance are too shallow**
13. **CI/deploy coverage misses important stacks**
14. **Testing depth is dramatically below production expectations**
15. **Docs and repo truth are too far apart**

---

## 7. Detailed implementation guide

The right program is not “more microservices.” It is **truth restoration, hot-path hardening, control-loop closure, and governed scale-out**.

## Wave 0 - Restore truth

### Goals

- align docs, code, and deployment reality
- establish ownership and confidence

### Concrete work

- Create `CODEOWNERS`
- Add ADR process under `docs/adr/`
- Fix docs and service count/name drift
- Fix edge route path mismatches
- Publish canonical service ownership and critical-flow ownership
- Add CI coverage for Python, contracts, data-platform, Helm, Terraform, and ArgoCD

### Primary repo surfaces

- `README.md`
- `docs/README.md`
- `.github/workflows/ci.yml`
- `deploy/helm/**`
- `docs/adr/**`

## Wave 1 - Hardening the money path

### Goals

- make checkout and payment trustworthy

### Concrete work

- Choose one checkout owner
- Remove duplicate orchestration paths
- Make idempotency durable across reserve/auth/capture/void/refund
- Fix partial capture ledger handling
- Add payment-state sweeping and true reconciliation closure
- Add failure-injection tests for confirm/capture/refund edge cases

### Primary repo surfaces

- `services/checkout-orchestrator-service/**`
- `services/order-service/**`
- `services/payment-service/**`
- `services/payment-webhook-service/**`
- `services/reconciliation-engine/**`

## Wave 2 - Close the store-to-door operating loop

### Goals

- make dark-store operations believable at 10-minute SLA scale

### Concrete work

- Fix fulfillment/rider event contracts
- Choose assignment authority
- Fix rider-state recovery
- Align location and ETA contracts
- Add breach prediction and re-optimization
- Expand inventory and warehouse operating models

### Primary repo surfaces

- `services/fulfillment-service/**`
- `services/rider-fleet-service/**`
- `services/location-ingestion-service/**`
- `services/routing-eta-service/**`
- `services/dispatch-optimizer-service/**`
- `services/inventory-service/**`
- `services/warehouse-service/**`

## Wave 3 - Build a real read and decision plane

### Goals

- improve conversion, margin, and customer experience

### Concrete work

- Fix catalog->search event model
- Move toward hybrid retrieval and learned ranking
- Add inventory-aware availability filters
- Introduce quote/lock tokens for pricing
- Add stronger experimentation and exposure logging
- Replace pod-local cache assumptions on critical read paths

### Primary repo surfaces

- `services/catalog-service/**`
- `services/search-service/**`
- `services/cart-service/**`
- `services/pricing-service/**`
- `services/config-feature-flag-service/**`
- `services/ai-inference-service/**`

## Wave 4 - Event, data, and ML governance

### Goals

- make platform decisions trustworthy and replayable

### Concrete work

- Canonicalize event envelope and topic namespace
- Add CI schema compatibility gates
- Harden Go stream/data-plane durability
- Fix data-platform semantic issues and feature path drift
- Add ML promotion, rollback, and parity gates

### Primary repo surfaces

- `contracts/**`
- `services/outbox-relay-service/**`
- `services/stream-processor-service/**`
- `services/cdc-consumer-service/**`
- `data-platform/**`
- `data-platform-jobs/**`
- `ml/**`

## Wave 5 - Cells, SLOs, and production governance

### Goals

- reduce blast radius and make operations predictable

### Concrete work

- Introduce city/zone/store cells
- Define shard and cache locality strategy
- Add SLO dashboards and error-budget alerts
- Add progressive delivery and stronger rollback policy
- Harden workload identity and per-service authorization

### Primary repo surfaces

- `deploy/helm/**`
- `argocd/**`
- `infra/terraform/**`
- `monitoring/**`
- `.github/workflows/**`

## Wave 6 - Governed AI agent rollout

### Goals

- capture AI leverage without putting correctness at risk

### Concrete work

- Choose one orchestrator runtime
- Implement real policy decision point, HITL, audit, and evaluation pipelines
- Keep AI read-only first
- Add explicit write-action risk tiers and kill switches
- Tie AI deployments to cost, latency, and reversal metrics

### Primary repo surfaces

- `services/ai-orchestrator-service/**`
- `services/ai-inference-service/**`
- `services/audit-trail-service/**`
- `services/config-feature-flag-service/**`
- `services/identity-service/**`

---

## 8. AI agent operating model for this repo

### Recommended initial agent set

- Incident Copilot
- Analytics / BI Copilot
- Support / Order Status Copilot
- Substitution Assistant
- Merchandiser Copilot
- Fraud Analyst Copilot
- Developer Knowledge Copilot

### Prohibited initial autonomous actions

- payment capture
- refund approval
- inventory reserve/confirm/release
- order-state mutation
- rider dispatch or reassignment
- price writes
- feature-flag writes without approval

### Required controls

- default-deny tool access
- risk tiers
- immutable audit trail
- offline and online eval gates
- human approval for operational writes
- environment fencing
- kill switches
- rollbackable prompt/model releases

---

## 9. Governance model

## 9.1 Ownership

Every service must have:

- engineering owner
- operational owner
- schema/contract owner
- SLO owner
- on-call owner

Every cross-domain workflow must also have one **system owner**:

- browse/search/cart
- checkout/payment/order
- inventory/substitution
- fulfillment/dispatch/ETA
- AI/agent platform

## 9.2 Change policy

| Change class | Required governance |
|---|---|
| Local read-path change | tests, canary, SLO watch |
| Cross-service contract change | ADR, compatibility review, consumer signoff |
| Money or inventory change | dual review, rollback plan, failure test |
| Infra/platform change | staged rollout, smoke validation, incident owner |
| AI write action | policy approval, audit, HITL, kill switch |

## 9.3 Operating cadences

- Weekly service ownership and release review
- Biweekly contract review
- Monthly reliability review
- Monthly AI governance review
- Quarterly architecture review board

## 9.4 Metrics leadership should demand

- checkout success and p95/p99 latency
- payment authorization/capture/refund health
- inventory reservation conflict rate
- pick-to-pack and dispatch latency
- ETA breach rate
- consumer lag and DLQ rates
- deployment change failure rate
- error-budget burn
- feature freshness SLA
- model fallback and agent reversal rates

---

## 10. Final recommendation

Do not interpret this review as a call to slow down product ambition.

Interpret it as a call to:

1. **stop pretending the docs and runtime are equally mature**
2. **fix the money path and event path first**
3. **tighten the dark-store operating loop**
4. **build a real platform control system**
5. **use AI as a governed amplifier, not an autonomous authority**

InstaCommerce is closer to a serious q-commerce backend than many repos of this size. But it is still meaningfully short of top-tier operators because the production disciplines that matter most are not yet strong enough.

The next leap is not architectural creativity. It is **operational truthfulness and engineering discipline**.

---

## Appendix A - External references used

### Best practices and platform governance

- AWS Well-Architected - Reducing scope of impact with cell-based architecture  
  https://docs.aws.amazon.com/wellarchitected/latest/reducing-scope-of-impact-with-cell-based-architecture/reducing-scope-of-impact-with-cell-based-architecture.html
- AWS Builders Library - Caching challenges and strategies  
  https://aws.amazon.com/builders-library/caching-challenges-and-strategies/
- Google SRE Book - Service Level Objectives  
  https://sre.google/sre-book/service-level-objectives/
- Google SRE Workbook - Alerting on SLOs  
  https://sre.google/workbook/alerting-on-slos/
- Google SRE Workbook - Implementing SLOs  
  https://sre.google/workbook/implementing-slos/
- Google SRE Book - Incident Response  
  https://sre.google/sre-book/incident-response/
- Google Cloud Architecture Framework - Graceful degradation  
  https://cloud.google.com/architecture/framework/reliability/graceful-degradation
- Stripe Engineering - Designing robust and predictable APIs with idempotency  
  https://stripe.com/blog/idempotency
- Martin Fowler - Idempotent Receiver  
  https://martinfowler.com/articles/patterns-of-distributed-systems/idempotent-receiver.html
- Microsoft ExP - Patterns of Trustworthy Experimentation  
  https://www.microsoft.com/en-us/research/group/experimentation-platform-exp/articles/patterns-of-trustworthy-experimentation-pre-experiment-stage/  
  https://www.microsoft.com/en-us/research/group/experimentation-platform-exp/articles/patterns-of-trustworthy-experimentation-during-experiment-stage/  
  https://www.microsoft.com/en-us/research/group/experimentation-platform-exp/articles/data-quality-fundamental-building-blocks-for-trustworthy-a-b-testing-analysis/
- NIST AI Risk Management Framework  
  https://www.nist.gov/itl/ai-risk-management-framework
- Anthropic - Building effective agents  
  https://www.anthropic.com/engineering/building-effective-agents

### US operator references

- Instacart - How Instacart built a modern search infrastructure on Postgres  
  https://tech.instacart.com/how-instacart-built-a-modern-search-infrastructure-on-postgres-c528fa601d54
- Instacart - Optimizing search relevance at Instacart using hybrid retrieval  
  https://tech.instacart.com/optimizing-search-relevance-at-instacart-using-hybrid-retrieval-88cb579b959c
- Instacart - Supercharging discovery in search with LLMs  
  https://tech.instacart.com/supercharging-discovery-in-search-with-llms-556c585d4720
- Instacart - Our early journey to transform discovery recommendations with LLMs  
  https://tech.instacart.com/our-early-journey-to-transform-instacarts-discovery-recommendations-with-llms-cf4591a8602b
- Instacart - Adopting dbt as the data transformation tool at Instacart  
  https://tech.instacart.com/adopting-dbt-as-the-data-transformation-tool-at-instacart-36c74bc407df
- Instacart - Enabling a seamless payment experience at Instacart through Marqeta  
  https://tech.instacart.com/enabling-a-seamless-payment-experience-at-instacart-through-marqeta-9d0e6ef39ad3

### India operator references

- Blinkit / Eternal business and investor pages  
  https://www.eternal.com/our-businesses/blinkit  
  https://www.zomato.com/webroutes/getPage?page_url=/investor-relations  
  https://www.zomato.com/webroutes/getPage?page_url=/investor-relations/financials
- Swiggy corporate and investor relations pages  
  https://www.swiggy.com/corporate/  
  https://www.swiggy.com/corporate/investor-relations/reports-and-publications/  
  https://www.swiggy.com/corporate/wp-content/uploads/2024/10/Swiggy-Limited-RHP-Final-Filing-Version-October-28-2024.pdf  
  https://www.swiggy.com/corporate/wp-content/uploads/2024/10/Annual-Report-FY-2023-24-1-1.pdf
- Zepto public pages  
  https://www.zeptonow.com/  
  https://www.zepto.com/s/press  
  https://www.zepto.com/s/del-areas  
  https://www.zepto.com/sitemap/static-pages.xml  
  https://www.zepto.com/sitemap/city-categories.xml

### Source-access caveat

Direct DoorDash engineering pages and some other vendor sources were partially inaccessible during this review, so competitor comparisons involving DoorDash rely on accessible public patterns, prior well-known engineering practices, and adjacent best-practice references rather than a complete direct citation set.
