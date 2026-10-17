# AI-Agent Operating Models for Q-Commerce

**Document scope:** Operating model taxonomy, safety boundaries, governance requirements,
and implementation gaps for AI agents across InstaCommerce's q-commerce platform.  
**Audience:** Principal engineers, AI platform leads, product/risk owners.  
**Status:** Iteration 3 — benchmark series.

---

## Table of Contents

1. [Why This Document Matters](#1-why-this-document-matters)
2. [Operating-Model Taxonomy](#2-operating-model-taxonomy)
3. [Use-Case Map: Where Each Mode Belongs](#3-use-case-map-where-each-mode-belongs)
   - 3.1 Read-Only Copilots
   - 3.2 Recommender Agents
   - 3.3 Anomaly Triage Agents
   - 3.4 Support-Assist Agents
   - 3.5 Fraud Investigation Agents
   - 3.6 Demand and Rider Planning Agents
   - 3.7 Catalog and Content-Ops Agents
4. [Where Autonomous Writes Are Unsafe](#4-where-autonomous-writes-are-unsafe)
5. [Governance Layer: Approvals, Tool Scoping, Audit, Evaluation, Rollback](#5-governance-layer)
   - 5.1 Approval Gates
   - 5.2 Tool Scoping and Allowlists
   - 5.3 Audit and Observability
   - 5.4 Evaluation Pipelines
   - 5.5 Rollback and Recovery
6. [Propose-Only vs Guarded Automation vs Autonomous Control](#6-mode-comparison)
7. [InstaCommerce Service Mapping and Gaps](#7-instacommerce-service-mapping-and-gaps)
8. [Competitive Signals and Public References](#8-competitive-signals-and-public-references)
9. [Recommended Sequencing](#9-recommended-sequencing)

---

## 1. Why This Document Matters

Quick-commerce operates in a narrow decision window — typical delivery SLAs of 10–30 minutes mean
that slow human reviews block value. Yet the same compressed timeline magnifies errors: a wrong
substitution, a mis-priced surge, an auto-approved fraudulent order, or a mislabeled allergen in
catalog all cause **immediate, customer-visible, legally consequential outcomes**. The pressure to
automate clashes with the cost of automation failures.

Most agent deployments fail not because the model is wrong but because the **operating model** is
undefined: no one drew the line between what the agent can decide alone, what it proposes for
human review, and what it is never allowed to touch. This document draws that line for every
relevant use-case in InstaCommerce and provides the governance framework to enforce it.

The three canonical operating modes are:

| Mode | Agent acts | Human role | Latency tolerance | Error surface |
|---|---|---|---|---|
| **Propose-only** | Generates recommendation/draft | Reviews and approves/rejects | Minutes to hours | Model errors are filtered before any write |
| **Guarded automation** | Acts autonomously within policy envelope; escalates outside | Reviews exceptions | Seconds to minutes | Policy violations, edge cases, high-value actions |
| **Autonomous control** | Fully autonomous, no routine human step | Reviews aggregate metrics / anomalies | Milliseconds to seconds | Model failures propagate directly to production |

---

## 2. Operating-Model Taxonomy

```
                          ┌─────────────────────────────────────────────────────┐
                          │               Decision Autonomy Spectrum             │
                          │                                                     │
  Human does everything ◄─┤ Copilot │ Propose-only │ Guarded Auto │ Autonomous ├─► Agent does everything
                          │                                                     │
                          │   ← Safer but slower          Faster but riskier → │
                          └─────────────────────────────────────────────────────┘
```

### Mode 1: Read-Only Copilot
The agent reads production data, surfaces insights or drafts, and presents them to a human who
takes action through normal product tooling. The agent has **zero write access** to any
transactional system.

Appropriate when: the action has high irreversibility, regulatory exposure, or requires context
the agent cannot safely infer (e.g., the reason a customer is angry, nuanced contract terms).

### Mode 2: Propose-Only Agent
The agent generates a structured proposal (substitution list, pricing adjustment, restock PO)
and writes it to a **staging/inbox** surface. A human approves, edits, or rejects. Only approved
proposals are applied to transactional systems via a gated write path.

Distinguishing property: the agent's output is structurally isolated from the live system until
a human commits it. The human is not writing from scratch — they are reviewing AI output — but
the commit is human-initiated.

### Mode 3: Guarded Automation
The agent acts autonomously within a policy envelope defined by explicit bounds (price floor/
ceiling, refund cap, tool allowlist, confidence threshold, risk-level gate). Requests that fall
outside the envelope are escalated to a human queue. The agent never silently degrades — it
escalates visibly.

Distinguishing property: policy guards are **code-level constraints**, not prompt instructions.
The guard layer runs independently of the LLM and cannot be overridden by prompt injection.

### Mode 4: Autonomous Control
The agent closes the full feedback loop: perceives state, decides, and writes to production with
no routine human step. Typical for high-frequency, low-stakes, well-modeled decisions where
latency requirements preclude human review and the error cost is bounded and recoverable (e.g.,
adjusting surge multiplier, re-ranking a search result).

Distinguishing property: the system must have **automated self-healing** (circuit breakers,
automated rollback, real-time drift alerting) because no human is watching each decision.

---

## 3. Use-Case Map: Where Each Mode Belongs

### 3.1 Read-Only Copilots

**What they do:** Surface structured summaries, dashboards, and contextual alerts to internal
users (ops, category managers, support supervisors) without any write path to transactional
systems.

**Q-commerce examples:**
- **Ops incident copilot** — monitors Kafka consumer lag, Debezium connector health, and SLA
  breach rates across dark stores; surfaces a ranked alert card with likely root cause, affected
  orders, and suggested runbook links. No automatic remediation.
- **Dark-store performance digest** — reads warehouse throughput, picker idle-time, and order-
  batch completion metrics from `warehouse-service` and produces shift-start briefings for store
  managers.
- **Substitution acceptance copilot** — reads substitution event outcomes from Kafka, computes
  per-SKU acceptance rate trends, and surfaces declining-acceptance products to category managers
  for proactive assortment decisions. Does not change any pricing or catalog record.
- **Churn signal dashboard** — reads CLV model scores from `ai-inference-service` and cohorts
  users by churn risk tier; surfaces to CRM team for manual campaign assignment.

**Safety profile:** Lowest risk. Agent reads from read replicas or event streams; write path
is entirely removed. PII guardrails are the primary concern — ensure output redaction before
any response leaves the trust boundary.

**InstaCommerce hook:** `ai-orchestrator-service` already redacts PII in output via `PIIVault`
(redact → LLM → restore). A dedicated read-only tool subset (catalog reads, order reads, no
cart/payment) could power these copilots today with no new infrastructure.

---

### 3.2 Recommender Agents

**What they do:** Generate ranked lists, substitution candidates, upsell bundles, or promotional
targets. Output is either surfaced to end users as suggestions (they choose) or sent to an
internal approval inbox (ops team approves before publishing).

**Subclasses by autonomy:**

| Sub-type | Mode | Why |
|---|---|---|
| In-app "You may also like" (real-time, per-user) | Guarded Automation | Low per-decision stake; user has ultimate choice; ranking model has production monitoring |
| Out-of-stock substitution in active cart | Guarded Automation within policy | Customer explicitly accepts/rejects; refund fallback if wrong |
| Promotional bundle suggestion (marketing copy) | Propose-only | Marketing copy has brand and legal exposure; requires human review |
| Category-level assortment rebalancing | Propose-only | Multi-SKU, multi-supplier action; high GMV impact |
| Dynamic cross-sell inside checkout | Guarded Automation | Short latency window; bounded revenue impact |

**Key guardrails for substitution (most sensitive sub-type):**
- Allergen/dietary compliance check must be a hard rule, not an LLM judgment. The substitution
  agent should call `catalog.get_product` and compare allergen field arrays before proposing any
  food substitution. If the comparison fails or is uncertain, the substitution is blocked, not
  proposed.
- Substitution confidence threshold: only propose if cosine similarity of product embeddings
  ≥ 0.85 AND price differential ≤ 15%.
- Log every proposed substitution with `{original_product_id, substitute_id, score, reason,
  accepted_by_customer, outcome}` for continuous evaluation.

**InstaCommerce hook:** `ai-orchestrator-service` implements `substitute` intent via
`catalog.search` + `inventory.check`. The intent flows into `RiskLevel.MEDIUM`. Current gap:
no allergen check, no price-differential guard, no substitution outcome feedback loop back to
the model. The `ranking_model.py` in `ai-inference-service` is the right home for embedding-
based similarity ranking.

---

### 3.3 Anomaly Triage Agents

**What they do:** Continuously or periodically scan operational metrics and event streams for
anomalies; classify root cause; recommend or (for safe classes) auto-execute a remediation.

**Q-commerce anomaly classes and appropriate modes:**

| Anomaly | Detection source | Recommended mode | Reasoning |
|---|---|---|---|
| Inventory count mismatch (system vs physical) | CDC events from `inventory-service` | Propose-only → ops review | Triggers recount workflows; incorrect auto-reconcile has supply impact |
| Kafka consumer lag spike | Monitoring metrics | Read-only copilot alert | Infrastructure action required; scope unclear without context |
| Payment reconciliation gap | `reconciliation-service` Kafka consumer | Propose-only → finance review | Financial records; requires human attestation |
| Rider assignment SLA breach (>15 min pickup) | Rider fleet events | Guarded automation (re-assign within policy) | Time-sensitive; bounded action; fallback: manual dispatch |
| Demand spike (weather/event driven) | Demand model + incoming order velocity | Guarded automation (adjust restock priority) | Pre-approved action space; bounded inventory impact |
| Dark-store temperature alert (cold chain) | IoT / warehouse sensors | Autonomous → alert + lock affected SKUs | Speed critical; reversible action; human review of locked SKUs |

**Architecture note:** anomaly triage agents must distinguish **detection** from **action**. The
detection layer (model inference, rule engine) should run independently of the action layer
(tool calls). The bridge between them is the risk classification: anomalies classified as
`critical` should page a human; anomalies classified as `low`/`medium` with a known, bounded
remediation can flow to guarded automation.

**InstaCommerce hook:** The `check_policy` node in `ai-orchestrator-service` already classifies
`RiskLevel` and routes `critical` to `escalate`. The escalation queue, however, is not yet
wired to a dedicated ops alerting channel — `escalation_reason` is set in state but not emitted
as a Kafka event to `notification-service`. This is a key gap.

---

### 3.4 Support-Assist Agents

**What they do:** Handle customer support queries end-to-end or hand off to a human agent with
a structured context packet. The primary mode is **guarded automation** for tier-1 queries
(order status, return initiation, FAQ) and **read-only copilot** for tier-2/3 (disputes,
complaints, chargebacks).

**Decision matrix:**

| Query type | Agent action | Hard escalation triggers |
|---|---|---|
| "Where is my order?" | Retrieve `order.get` + format ETA | None — always auto-resolvable |
| "I want to return item X" | Initiate return if within policy window; confirm with user | Expired return window → human; value > ₹500 → human review |
| "Wrong item delivered" | Log complaint; offer replacement or refund ≤ ₹500 | Refund > ₹500; controlled item (alcohol/medicine) |
| "I was charged twice" | Read payment records; surface to agent if duplicate detected | All payment disputes → human review only |
| "I want to cancel my subscription" | Flag in CRM; schedule cancellation per policy | — |
| Chargeback / fraud claim | Read-only context packet to human queue | All → human only |

**Critical safety rule: the support agent should never autonomously modify payment records,
adjust a wallet balance, or issue a refund above the configured cap without human
confirmation.** The current `OutputPolicy` in `ai-orchestrator-service` enforces a
`MAX_REFUND_AMOUNT_CENTS` cap (default: ₹500/50000 paise). Every refund above this cap triggers
`ValidationStatus.FAILED` and must be escalated.

**EscalationPolicy integration:** `payment_dispute` trigger (fires on `intent=support` +
"chargeback" keyword) and `high_value_refund` trigger (fires when `tool_result.data.amount_cents
> 50000`) are already coded. Gap: triggers for controlled items (alcohol, medicines) and for
repeat escalations by the same user within a session are not implemented.

**Self-service rate:** Industry benchmark (Intercom, Zendesk AI) reports 60-70% tier-1
deflection. Q-commerce specifics: Zepto and Blinkit publicly target 80%+ bot resolution for
"where is my order" queries given the narrow delivery window. The remaining 20% (complaints,
refunds, edge cases) require human+agent co-operation, not fully autonomous resolution.

---

### 3.5 Fraud Investigation Agents

**What they do:** Score transactions, investigate suspicious patterns, recommend or execute
block/review/approve decisions, and generate structured evidence packets for analyst review.

**Three-layer architecture:**

```
Layer 1: Real-time scoring (< 100 ms, inline with checkout)
   FraudModel.predict() → auto_approve | soft_review | block
   Runs in ai-inference-service. Pure ML/rule-based, no agent.

Layer 2: Review queue agent (seconds, async, human-in-the-loop)
   For soft_review decisions: agent collects additional signals
   (device history, account velocity, linked accounts), generates
   evidence packet, routes to analyst queue.
   Mode: Propose-only (evidence + recommended decision to analyst)

Layer 3: Pattern investigation agent (minutes to hours, ops-driven)
   For detected fraud rings, chargebacks clusters, or anomalous
   refund patterns: agent reads transaction graph, surfaces connected
   accounts, proposes account-level actions (suspend, step-up auth).
   Mode: Propose-only with approval gate before any account action.
```

**Autonomous write boundary for fraud:**
- Layer 1 `block` decisions are autonomous — they prevent transaction completion, which is
  reversible (customer can contact support). This is already implemented.
- Layer 2 and 3 actions (account suspension, payment method blocking, wallet freeze) must never
  be autonomous. These are high-irreversibility actions affecting the customer's ability to
  transact. A false positive at this level causes customer harm and reputational risk.
- Rule: **any action that reduces a customer's ability to purchase requires human approval**.

**SHAP explainability requirement:** Every fraud `block` decision emitted to external systems
(e.g., notification to customer, chargeback to payment processor) must include top-3 SHAP
feature contributions from `FraudModel._feature_contributions_ml()`. This is implemented in
`fraud_model.py` but not yet surfaced in the API response or audit log.

**InstaCommerce hook:** `ai-inference-service` `FraudModel` correctly implements the three-
threshold band (auto-approve <30, soft-review 30-70, block >70). Gap: no Layer-2 investigation
agent exists — `soft_review` decisions currently reach no queue, meaning they are either
silently auto-approved or dropped. This is an operational hole for the 30-70 score band.

---

### 3.6 Demand and Rider Planning Agents

**What they do:** Translate demand forecasts and supply signals into operational plans —
inventory restock priorities, rider shift recommendations, zone coverage adjustments, and surge
pricing triggers.

**Sub-use-cases and modes:**

#### A. Demand-Driven Restock Recommendations
- **What:** Uses `DemandModel` (Prophet + TFT per store×SKU×hour) to predict stockout risk;
  generates restock priority list for warehouse team.
- **Mode:** Propose-only for SKUs above threshold GMV; guarded automation for fast-movers
  with pre-approved reorder logic (standing POs with supplier).
- **Why not fully autonomous:** Restock has supplier lead times, shelf-life constraints, and
  cold-chain logistics. A wrong auto-restock for perishables creates waste cost; for slow-movers
  it creates working capital lock-up. Human merchandising review remains essential.

#### B. Rider Shift Planning
- **What:** Recommends shift schedules, zone assignments, and incentive targets based on
  predicted demand curves.
- **Mode:** Propose-only. Rider schedules involve labor contracts, geo-regulatory constraints,
  and individual preferences. Agents generate the draft; ops team publishes.
- **Why not autonomous:** Labor decisions have legal and contractual dimensions outside the
  model's scope. A recommendation that under-staffs a zone during a cricket final triggers
  cascade SLA failures.

#### C. Real-Time Zone Surge Trigger
- **What:** Autonomously adjusts surge multiplier (delivery fee) within pre-approved bounds
  (0.8× floor, 2.0× ceiling per `dynamic_pricing_model.py`) based on supply-demand imbalance
  in a zone.
- **Mode:** Autonomous control. Latency window precludes human approval. Bounded, reversible.
- **Required guardrails:** Price floor (cost + minimum margin), demographic non-discrimination
  check (surge must not correlate with pincode income tier), hard ceiling, audit log of every
  adjustment, real-time dashboard for ops visibility.

#### D. Rider Dispatch Optimization
- **What:** Assigns incoming orders to riders using ETA model + routing optimization
  (`routing-eta-service`).
- **Mode:** Autonomous control. This is a latency-critical, high-frequency, well-modeled problem
  in q-commerce — all major players (Blinkit, Zepto, DoorDash) run fully autonomous dispatch.
- **Required guardrails:** Fallback to FIFO assignment if optimizer confidence is low; SLA
  breach alerting; circuit breaker if dispatch failure rate spikes.

**Demand model accuracy context:** InstaCommerce targets 92% forecast accuracy (Zepto benchmark).
At <90% accuracy, autonomous restock decisions start generating either stockout or overstock at
a rate that likely exceeds the cost of human review. Accuracy should be continuously measured
per store×SKU cohort, not as a global average.

---

### 3.7 Catalog and Content-Ops Agents

**What they do:** Generate, enrich, moderate, and publish product catalog content — titles,
descriptions, nutritional information, images, category tags, SEO metadata.

**Sub-use-cases and modes:**

#### A. New SKU Onboarding Draft
- **What:** Given a supplier data sheet or product image, agent drafts title, description,
  category tags, and allergen flags.
- **Mode:** Propose-only with mandatory category-manager review before publish. Allergen
  information in particular must be human-verified — a misclassified allergen is a safety and
  legal liability, not just a product quality issue.
- **Public reference:** Instacart published a CV + LLM pipeline for shoppable flyers and
  promotion digitization (tech.instacart.com, Feb 2026). Their pipeline generates catalog
  enrichments but routes them through a review queue before pushing to the live catalog.

#### B. Automated A/B Copy Variant Generation
- **What:** Generate alternative product title/description variants for conversion testing.
- **Mode:** Guarded automation. Agent generates variants; variant goes live in feature-flagged
  experiment; experiment parameters (max exposure %, kill threshold) are human-configured;
  agent cannot expand experiment scope autonomously.

#### C. Price Discrepancy Detection and Correction
- **What:** Detects mismatches between supplier price feeds and catalog prices; proposes
  corrections.
- **Mode:** Propose-only. Any price change in the live catalog has immediate revenue and
  margin impact. Even a "correction" that fixes a wrong decimal point should be confirmed by
  a pricing analyst.

#### D. Offensive/Inaccurate Content Moderation
- **What:** Screens newly onboarded or user-generated content for prohibited content, spam,
  or inaccurate claims.
- **Mode:** Guarded automation for clear violations (auto-quarantine); propose-only for
  ambiguous cases. A content quarantine is reversible — this is a safe autonomous write.

#### E. SEO Metadata and Tag Generation
- **What:** Generates search-optimized metadata for catalog items.
- **Mode:** Guarded automation with quality gate (embedding similarity to existing high-
  performing items in same category ≥ 0.8). Fully autonomous publish for items that pass
  the gate; human review queue for items below threshold.

**InstaCommerce gap:** `ai-orchestrator-service` tools cover `catalog.search`,
`catalog.get_product`, and `catalog.list_products` (read-only). There are no tools for
`catalog.create_product`, `catalog.update_product`, or `catalog.publish`. This is actually
correct for the current state — no catalog write tools should be exposed until the Propose-only
approval workflow (staging inbox + review UI) is built. Adding write tools before the approval
workflow is an operational risk.

---

## 4. Where Autonomous Writes Are Unsafe

The following action categories should **never** be in the autonomous control mode in
InstaCommerce's current maturity level, regardless of model performance:

### 4.1 Payment and Financial Records
Any action that modifies `payment-service` records, `wallet-loyalty-service` balances, or
interacts with payment gateway APIs must be human-approved. This includes:
- Refund issuance above the configured cap (currently ₹500/50000 paise)
- Wallet credit or debit
- Payment method blocking or unblocking
- Subscription charge adjustment

**Reason:** Financial records are subject to audit, regulatory reporting (RBI's payment
regulations for Indian q-commerce), and have legal standing. Autonomous errors here create
liability that cannot be resolved by a model rollback.

### 4.2 Account and Identity Actions
Any action that affects a customer's `identity-service` record is unsafe for autonomous
execution:
- Account suspension or deactivation
- KYC status changes
- Address verification overrides
- Login credential or MFA changes

**Reason:** High irreversibility, identity theft risk, and regulatory exposure (DPDP Act,
India). A wrongly suspended account means the customer cannot purchase — direct, immediate
business and legal harm.

### 4.3 Controlled Substance Catalog Operations
Any catalog write touching items categorized as alcohol, tobacco, or over-the-counter
medicines requires human review of the action before publish, plus compliance with local
licensing regulations.

### 4.4 Pricing Outside Pre-Approved Bounds
Price changes by autonomous agents must be strictly bounded by a pre-approved, code-enforced
envelope. Dynamic pricing operates within a `[floor, ceiling]` range. Any action that modifies
the bounds themselves (not prices within bounds) requires ops/pricing-team approval.

**Reason:** The DoorDash engineering team has documented that unconstrained surge multipliers,
even when technically correct per model, can generate regulatory scrutiny and customer backlash
that costs more than the revenue they protect. The floor/ceiling envelope is a business policy
that must be set by humans and enforced by code.

### 4.5 Bulk Inventory or Catalog Operations
Any agent action affecting more than a configurable threshold of SKUs in a single operation
(e.g., >50 SKUs) should require human approval even if individual SKU-level actions would be
permissible autonomously.

**Reason:** Bulk errors propagate at the scale of the bulk action. A pricing bug that auto-
applies to 500 SKUs is 500x worse than one that applies to 1 SKU. Bulk approval gates are a
blast-radius control, not a quality gate.

### 4.6 Supply Chain Commitments
Any agent that would commit InstaCommerce to a supplier obligation (PO generation, order
confirmation) must route through procurement approval regardless of SKU count. Supplier
contracts, minimum order quantities, and penalties for over-ordering are outside the model's
scope.

---

## 5. Governance Layer

### 5.1 Approval Gates

Approval gates must be **structural, not advisory**. An agent that "recommends" but can also
autonomously commit if the human doesn't respond is not a propose-only agent — it is an
autonomous agent with a notification mechanism. The structural distinction is:

- **Hard gate:** Agent writes to staging/inbox only. The write path to production is only
  accessible via a separate approval API endpoint that requires human-authenticated token.
- **Timeout escalation:** If a proposal in the inbox is not reviewed within SLA, it escalates
  to a supervisor, then to a default safe action (reject or hold) — never to auto-commit.

**Implementation for InstaCommerce:**

```
Agent Proposal Flow:
  ai-orchestrator-service
      │
      ▼
  [Staging Inbox Service]   ← new service needed (or extend config-service)
      │
      ├── GET /proposals?status=pending        (reviewer polls)
      ├── POST /proposals/{id}/approve          (reviewer commits to production)
      ├── POST /proposals/{id}/reject           (reviewer discards)
      └── Kafka event: proposal.created / approved / rejected
```

The `ai-orchestrator-service` must emit proposals as **structured events** to Kafka
(`ai.proposals.v1`) rather than directly calling write APIs. The downstream service consumes
from the Kafka topic only when a human-approved event is confirmed.

**Risk-tiered approval:**

| Risk level | Approver | SLA | Auto-expire action |
|---|---|---|---|
| Low (catalog tag, copy variant) | Any team member | 4 hours | Auto-reject |
| Medium (substitution, restock) | Category manager | 1 hour | Escalate to senior |
| High (pricing bound, bulk update) | Pricing lead + ops director | 30 min | Hold + alert |
| Critical (account action, refund > cap) | Named individual only | 15 min | Escalate to VP |

### 5.2 Tool Scoping and Allowlists

Tools exposed to the agent must be scoped to the minimum required for the use case. The
`ToolRegistry` already enforces an allowlist (`tool_allowlist` setting in `config.py`). This
allowlist must be **per-agent-type**, not global.

**Recommended allowlist partitioning:**

```python
TOOL_ALLOWLISTS = {
    "support_agent":        ["order.get", "catalog.get_product"],
    "substitution_agent":   ["catalog.search", "catalog.get_product", "inventory.check"],
    "recommender_agent":    ["catalog.list_products", "catalog.search", "pricing.get_product"],
    "demand_agent":         ["catalog.list_products", "inventory.check"],  # read-only
    "fraud_agent":          ["order.get"],                                  # read-only
    # Write tools — none currently; add only after approval workflow is built
}
```

**Tool capability taxonomy:**

| Category | Examples | Write risk | Notes |
|---|---|---|---|
| Safe reads | catalog.search, order.get, inventory.check | None | Always permissible |
| Aggregating reads | catalog.list_products, pricing.calculate | None | Watch for data volume / cost |
| Idempotent writes | cart.add_item (with idempotency key) | Low | Already has idempotency header in ToolDescriptor |
| State-changing writes | order.create, refund.issue | High | Require guarded automation with policy envelope |
| Irreversible writes | account.suspend, wallet.debit | Critical | Never in autonomous mode |

**Tool call budget:** `tool_calls_remaining` defaults to 10 in `AgentState`. This is a correct
safety bound. A support agent that burns 10 tool calls on a single query is either in a loop or
misclassified intent — both should trigger escalation, not retry.

### 5.3 Audit and Observability

Every agent decision that affects or proposes to affect a production record must generate a
durable, queryable audit event. This is the foundational requirement for compliance, debugging,
and evaluation.

**Required audit event fields (extend existing envelope):**

```json
{
  "event_id": "uuid",
  "event_type": "ai.agent.decision",
  "aggregate_id": "session_id or request_id",
  "schema_version": "v1",
  "source_service": "ai-orchestrator-service",
  "correlation_id": "user_request_correlation_id",
  "timestamp": "ISO-8601",
  "payload": {
    "agent_type": "support_agent | substitution_agent | ...",
    "intent": "support | substitute | ...",
    "intent_confidence": 0.87,
    "risk_level": "medium",
    "mode": "propose_only | guarded_automation | autonomous",
    "tools_called": ["order.get"],
    "tool_latency_ms": {"order.get": 45.2},
    "decision": "escalate | auto_resolve | propose",
    "escalation_reason": null,
    "proposal_id": "uuid if proposal created",
    "policy_violations": [],
    "model_version": "gpt-4o-mini | fraud-xgb-v1",
    "token_cost_usd": 0.0012,
    "user_id": "REDACTED if PII-sensitive context",
    "completed_nodes": ["classify_intent", "check_policy", "retrieve_context", "execute_tools", "validate_output", "respond"]
  }
}
```

The `completed_nodes` field from `AgentState` already provides the execution path audit trail.
It needs to be emitted to Kafka (`ai.audit.v1`) at the end of every request, not just logged
to stdout.

**Observability gaps in current implementation:**
- `escalation_reason` is set in state but not emitted to Kafka or to `notification-service`
- `tool_results` latency is logged per tool but not correlated to the overall request trace
  (OTEL spans should cover this if the OTLP exporter is configured)
- No per-intent success rate metric — only raw tool call success/failure metrics exist
- No session replay capability — conversations stored in Redis checkpoint expire; no
  durable conversation history for post-hoc investigation

### 5.4 Evaluation Pipelines

An agent in production without an evaluation pipeline is an unmonitored system. The evaluation
architecture for InstaCommerce AI agents should operate at three cadences:

**Cadence 1: Real-time guardrail compliance (every request)**
- Policy violation rate (should be < 1% for well-tuned agents)
- Escalation rate (baseline by intent type — a spike indicates prompt regression or scope creep)
- Tool call budget consumption (alert if >8/10 on average — indicates agent is confused)
- Latency p50/p95/p99 per intent type

**Cadence 2: Outcome evaluation (daily/weekly, requires labeled data)**
- Substitution acceptance rate (customer accepted the AI-proposed substitute)
- Support resolution rate (was the issue resolved without human escalation?)
- Fraud decision accuracy (precision/recall on labeled fraud events)
- Demand forecast MAPE per store×SKU×hour cohort
- False positive rate for fraud block decisions (customer contact rate for blocked orders)

**Cadence 3: Adversarial / red-team evaluation (monthly or pre-release)**
- Prompt injection tests (does `injection.py` catch novel injection patterns?)
- PII leakage tests (does PII vault correctly restore and redact in all code paths?)
- Policy bypass tests (can a crafty query get the agent to propose a refund above the cap?)
- Tool scope tests (can the agent be prompted to call tools outside its allowlist?)

**InstaCommerce gap:** No evaluation pipeline exists. The `ai-inference-service` has `pytest`
unit tests for individual models. The `ai-orchestrator-service` has no integration test suite
that evaluates end-to-end agent decisions against labeled scenarios. This is the most
operationally dangerous gap — agents running in production without outcome feedback loops will
drift silently.

**Minimum viable evaluation setup:**
1. A `golden_set` table in the data platform (store `{query, expected_intent, expected_tool_calls,
   expected_outcome}` pairs manually curated per agent type).
2. A daily Airflow DAG that replays the golden set through the orchestrator (using a shadow
   mode endpoint that does not commit any writes) and computes pass rate.
3. Alert if pass rate drops below threshold (e.g., <90%) — triggers manual review before next
   deployment.

### 5.5 Rollback and Recovery

**Model rollback (inference changes):**
`ai-inference-service` models are version-tagged (`fraud-xgb-v1`). Each model endpoint should
support a `?model_version=` query parameter to enable traffic splitting (90/10 canary) via
`config-feature-flag-service` before full rollout. If the canary version shows metric
regression, the feature flag routes 100% traffic back to the stable version without a
deployment.

**Agent behavior rollback (orchestrator changes):**
LangGraph graph topology changes and prompt changes should be treated as deployments. Blue/green
the `ai-orchestrator-service` pod with a feature flag controlling which version handles
traffic. If the new version's escalation rate or policy violation rate exceeds baseline + 2σ
within 30 minutes, auto-rollback via ArgoCD.

**Tool allowlist rollback:**
`tool_allowlist` is a runtime config value (should be backed by `config-feature-flag-service`,
not hardcoded). If a new write tool is added to the allowlist and causes unexpected state
changes, the allowlist can be modified via a feature flag without a deployment.

**Data rollback for approved proposals:**
Any proposal committed to production via the approval workflow must be reversible. The
downstream service consuming `ai.proposals.approved` events must implement a compensating
transaction (outbox pattern). For example, an approved catalog update emits an outbox event;
if the update is later determined to be wrong, a catalog-revert event can be published with
the same outbox infrastructure.

---

## 6. Mode Comparison

### Detailed Comparison: Propose-Only vs Guarded Automation vs Autonomous Control

| Dimension | Propose-Only | Guarded Automation | Autonomous Control |
|---|---|---|---|
| **Human role** | Decision maker | Exception handler | Metrics monitor |
| **Agent role** | Draft generator | Policy executor | Full controller |
| **Latency** | Minutes to hours | Seconds to minutes | Milliseconds to seconds |
| **Error visibility** | Pre-commit (human sees errors before they matter) | At escalation (errors inside envelope are invisible until metrics move) | Post-hoc (errors in production before human sees them) |
| **Policy encoding** | Implicit (human judgment) | Explicit code (policy envelope, allowlist) | Explicit code + automated self-healing |
| **Rollback** | No write = no rollback needed | Compensating transaction or feature flag | Automated rollback + circuit breaker |
| **Compliance audit** | Human approval is the audit record | Every agent decision + escalation logged | Every decision logged with SHAP/trace |
| **Required maturity** | Any | Production-tested policy layer, labeled evaluation data | High-frequency tested, continuous eval, automated recovery |
| **Blast radius** | None (staging only) | Bounded by policy envelope | Unbounded within tool scope |
| **Cost of being wrong** | Low — human catches before commit | Medium — errors within envelope reach production | High — errors propagate immediately |

### Mode Selection Heuristic

```
                    ┌─────────────────────────────────────┐
                    │  Is the action reversible?           │
                    │  (can it be undone in < 5 min?)      │
                    └─────────┬───────────────────────────┘
                              │
                   YES        │         NO
              ┌───────────────┘         └──────────────────┐
              ▼                                             ▼
  ┌─────────────────────────┐               ┌─────────────────────────────┐
  │  Is there a well-defined │               │  Use Propose-Only.          │
  │  policy envelope that    │               │  Never autonomous for       │
  │  covers 95%+ of cases?   │               │  irreversible actions.      │
  └───────────┬─────────────┘               └─────────────────────────────┘
              │
     YES      │     NO
  ┌───────────┘     └───────────────────────┐
  ▼                                         ▼
  ┌─────────────────────────────┐  ┌─────────────────────────────┐
  │  Is latency < 1 second      │  │  Use Propose-Only until      │
  │  a hard requirement?         │  │  policy envelope is defined. │
  └───────────┬─────────────────┘  └─────────────────────────────┘
              │
   YES        │      NO
  ┌───────────┘      └──────────────────────┐
  ▼                                         ▼
  ┌────────────────────┐        ┌─────────────────────────────┐
  │  Autonomous Control │        │  Guarded Automation          │
  │  (+ automated       │        │  (escalate outside envelope) │
  │   self-healing)     │        └─────────────────────────────┘
  └────────────────────┘
```

### Mode Assignment for InstaCommerce Use Cases

| Use case | Recommended mode | Current mode | Gap? |
|---|---|---|---|
| Order status lookup | Guarded Automation | Guarded Automation ✓ | No |
| Substitution proposal | Guarded Automation | Guarded Automation (no allergen check) | Partial |
| Support refund ≤ ₹500 | Guarded Automation | Guarded Automation ✓ | No |
| Support refund > ₹500 | Propose-only | Escalation exists but no staging inbox | Yes |
| Fraud block decision | Autonomous (layer 1) | Autonomous ✓ | No |
| Fraud soft-review (30-70) | Propose-only | Not implemented | Yes — critical gap |
| Demand forecast | Read-only copilot / Propose-only | Not connected to orchestrator | Yes |
| Rider dispatch | Autonomous | Separate service (routing-eta) | No |
| Surge pricing | Autonomous within bounds | Autonomous ✓ (dynamic_pricing_model) | No |
| Catalog enrichment | Propose-only | Not implemented (no write tools) | Yes |
| Restock recommendation | Propose-only | Not implemented | Yes |
| Anomaly triage escalation | Read-only copilot | Escalation state set, not emitted | Yes |
| Content moderation | Guarded Automation (quarantine) | Not implemented | Yes |
| Churn risk alert | Read-only copilot | CLV model exists, not surfaced | Yes |

---

## 7. InstaCommerce Service Mapping and Gaps

### 7.1 Current AI Service Architecture

```
ai-inference-service (port 8000)
├── FraudModel        — XGBoost, 15 features, 3-band decision (auto/review/block)
├── DemandModel       — Prophet + TFT, per store×SKU×hour
├── ETAModel          — delivery time prediction
├── DynamicPricingModel — Contextual bandit, surge optimization
├── RankingModel      — product ranking for search/recommendations
├── PersonalizationModel — user-specific recommendations
└── CLVModel          — customer lifetime value

ai-orchestrator-service (port 8100)
├── LangGraph state machine (classify → policy → RAG → tools → validate → respond/escalate)
├── Tools (read-only): catalog.search, catalog.get_product, catalog.list_products,
│                      pricing.calculate, pricing.get_product, inventory.check,
│                      cart.get, order.get
├── Guardrails: PII vault, injection detection, output validation, rate limiter, escalation
└── Budget: $0.50 cost cap, 10s latency cap, 10 tool call cap
```

### 7.2 Identified Gaps

**Gap 1: No write tools exist, but no approval workflow exists either.**  
The current tool set is read-only, which is safe. But the roadmap to guarded automation
requires both (a) adding write tools with idempotency and (b) building the staging inbox
approval workflow before any write tool is exposed to the orchestrator. Neither exists today.

**Gap 2: Fraud soft-review (score 30-70) has no downstream path.**  
`FraudModel` correctly classifies `SOFT_REVIEW` but there is no queue, no investigation agent,
and no analyst workflow for these decisions. At scale, the 30-70 band contains real fraud
that auto-approves and real legitimate orders that get degraded experience. This is an active
revenue and loss exposure.

**Gap 3: Escalation events are not emitted to Kafka.**  
`needs_escalation=True` and `escalation_reason` are set in `AgentState` but only logged to
stdout. There is no `ai.escalations.v1` Kafka topic, no consumer in `notification-service`,
and no ops queue. Escalations are effectively silent.

**Gap 4: No evaluation pipeline or golden set.**  
Zero automated outcome measurement for any agent behavior. Model unit tests exist in
`ai-inference-service` but there is no integration-level evaluation of orchestrator behavior
against labeled scenarios. Silent regressions in intent classification, policy routing, or
RAG retrieval cannot be detected.

**Gap 5: RAG knowledge base is hardcoded with 4 stub documents.**  
Production RAG requires a vector store (Pinecone, Weaviate, pgvector) with regularly updated
embeddings from live policy documents, product FAQs, and support playbooks. The `CachedRetrievalProvider`
interface is stubbed but not implemented. Until this is replaced, RAG-dependent agent
responses (returns policy, delivery FAQ) are based on stale hardcoded content.

**Gap 6: No per-agent-type tool allowlist partitioning.**  
All agents share the same `tool_allowlist` setting. A support agent and a substitution agent
can call the same tools. When write tools are added, this becomes a lateral movement risk —
a prompt-injected support query could call a write tool intended only for catalog ops.

**Gap 7: Dynamic pricing and demand models are not connected to the orchestrator.**  
`DynamicPricingModel` and `DemandModel` are inference-only services. There is no agent loop
that reads their output, interprets it in context, and takes action (even propose-only).
The models run but their signals don't drive any automated or semi-automated operational
workflow.

**Gap 8: No session replay or durable conversation history.**  
Conversations are checkpointed in Redis with implicit TTL. Post-hoc investigation of a
disputed agent decision (e.g., "why did the agent offer this substitution?") is impossible
once the Redis entry expires. Durable audit requires the `completed_nodes` + `tool_results`
snapshot to be written to a persistent store (PostgreSQL or Kafka → data platform).

### 7.3 Priority Order for Closing Gaps

| Priority | Gap | Why it's first | Estimated effort |
|---|---|---|---|
| P0 | Gap 3: Escalation Kafka emission | Safety-critical; current escalations are silent | Small — add Kafka producer to `respond`/`escalate` node |
| P0 | Gap 2: Fraud soft-review queue | Active loss exposure | Medium — build analyst queue consumer |
| P1 | Gap 4: Evaluation pipeline | Required before any new agent feature ships | Medium — golden set + Airflow DAG |
| P1 | Gap 6: Per-agent tool allowlist | Required before any write tool is added | Small — config change + test |
| P2 | Gap 5: Production RAG | Quality gap; current RAG is demonstrably wrong | Large — vector store integration |
| P2 | Gap 1: Approval workflow | Required before propose-only agents ship | Large — new service + Kafka topic |
| P3 | Gap 7: Model-to-agent loop | Value add after foundation is solid | Medium — new agent types |
| P3 | Gap 8: Durable session history | Compliance/audit improvement | Medium — outbox to data platform |

---

## 8. Competitive Signals and Public References

### Instacart
- **Shoppable flyers via CV + LLM** (tech.instacart.com, Feb 2026): Computer vision pipeline
  extracts promotions from printed flyers; LLM generates structured catalog entries (SKU, price,
  discount, dates). Critically, the pipeline writes to a **review queue**, not directly to the
  live catalog. Human review rate dropped from 100% to ~20% for high-confidence extractions.
  This is the exact Propose-Only with guarded automation escalation model described in §3.7.
- **Substitution ML evolution** (referenced in DoorDash context): TF-IDF → LightGBM →
  deep learning embeddings. Key learning: embedding-based similarity dramatically reduces
  allergen mismatch rate because semantic distance captures product type, not just text overlap.

### DoorDash
- **Substitution agent architecture**: DoorDash Engineering Blog documents a multi-stage
  substitution pipeline where the ML model proposes substitutes ranked by acceptance probability,
  but customer confirmation is always required for cross-category substitutions. Same-category
  (e.g., different brand of same product) can be auto-applied with customer pre-approval.
  This is a clean example of risk-tiered autonomous decision making within the substitution use case.
- **Dynamic pricing guardrails**: DoorDash has documented the regulatory and reputational risk
  of unconstrained surge. Their pricing envelope uses a floor (cost-based) and a ceiling (2x
  base fee) with a non-discrimination check against demographic proxies. InstaCommerce
  `dynamic_pricing_model.py` implements the same envelope architecture.
- **ML Workbench for platform velocity**: Internal tool for model iteration without full
  infrastructure deploys. Analogous to what InstaCommerce should build on top of the
  `ai-inference-service` model versioning + `config-feature-flag-service` feature flag pattern.

### Zepto
- **92% forecast accuracy benchmark**: Zepto TechXPress has referenced 92% demand forecast
  accuracy as their production target for dark store replenishment. At this accuracy level,
  autonomous restock triggers (within pre-approved supplier POs) become economically justified.
  Below 90%, human review provides more value than it costs.
- **CDC at scale**: Zepto's CDC optimization work (Debezium + reduction buffer) is directly
  applicable to InstaCommerce's event pipeline. Fresh CDC events are what powers near-real-time
  feature store updates for fraud and demand models.

### Blinkit / Zomato
- **Dark-store scale**: Blinkit operates 1000+ dark stores with near-fully automated dispatch
  and demand planning. Their architecture (as inferred from engineering blog posts) runs
  dispatch optimization at autonomous control mode — no human in the per-order dispatch loop.
  Demand planning operates at propose-only for weekly/monthly plans, guarded automation for
  same-day restocks.

### OpenAI / Anthropic on Agent Safety
- **Anthropic's Responsible Scaling Policy (RSP)** and Claude's Constitutional AI framework
  both emphasize that autonomous agents should have **minimal footprint** — request only
  necessary permissions, prefer reversible actions, err on the side of doing less when uncertain.
  This maps directly to the tool allowlist partitioning and `tool_calls_remaining` budget in
  InstaCommerce's orchestrator.
- **OpenAI's agent best practices** recommend treating the tool set as the primary safety lever,
  not the prompt. Any tool that should not be called by an agent should not be registered in
  its tool set — prompt instructions alone are insufficient to prevent tool calls.

### Google DeepMind / Research Signals
- **Agentic loop failure modes** (DeepMind, 2024 agent benchmark paper): The most common
  production failure modes for tool-calling agents are (1) intent misclassification leading to
  wrong tool sequence, (2) tool call loops when downstream service returns unexpected schema,
  and (3) context window overflow causing tool result truncation. All three are addressed by
  InstaCommerce's `check_policy` node (budget gate), circuit breaker (breaks loops on 5xx),
  and `tool_calls_remaining` counter (limits runaway iteration).

---

## 9. Recommended Sequencing

The following implementation sequence moves InstaCommerce from its current state (read-only
guarded automation for support/search) to a full multi-mode agent fleet, ordered by
safety criticality:

### Phase 1: Safety Foundations (Weeks 1-4)
1. **Emit escalation events to Kafka** (`ai.escalations.v1`) from `escalate` node. Wire to
   `notification-service` for ops paging. This closes the silent escalation gap immediately.
2. **Build fraud soft-review analyst queue** — Kafka consumer for `SOFT_REVIEW` decisions,
   analyst UI with approve/block/escalate actions, outcome feedback to fraud model training.
3. **Per-agent tool allowlist** — partition `tool_allowlist` by agent type in config. Test that
   support agent cannot access pricing tools and vice versa.
4. **Durable audit log** — emit `AgentState.completed_nodes` + `tool_results` snapshot to
   `ai.audit.v1` Kafka topic at request completion. Data platform team can then build a mart.

### Phase 2: Evaluation and Visibility (Weeks 5-10)
5. **Golden set + daily eval DAG** — 100+ labeled scenarios per agent type, Airflow DAG,
   pass-rate metric with alert threshold.
6. **Production RAG integration** — replace stub knowledge base with pgvector or Pinecone,
   seeded with actual policy docs, FAQ content, and product policies.
7. **SHAP exposure in fraud API** — surface `feature_contributions` and `top_risk_factors`
   in the fraud service API response for analyst tooling.
8. **Intent success rate metrics** — add Prometheus counter per `(intent, outcome)` tuple so
   per-intent resolution rate is visible on the monitoring dashboard.

### Phase 3: Guarded Automation Expansion (Weeks 11-20)
9. **Staging inbox service** — new service (or extend `config-feature-flag-service`) with
   proposal storage, reviewer UI, and approval Kafka event.
10. **Catalog write tools (propose-only)** — add `catalog.draft_update` tool that writes to
    staging inbox. No direct production writes. Catalog-ops agent can now draft enrichments.
11. **Substitution allergen guard** — add hard allergen-compatibility check in substitution
    agent before proposing any food item substitution.
12. **Demand-to-restock agent** — propose-only agent that reads `DemandModel` output and
    generates restock priority proposals for warehouse team review.

### Phase 4: Autonomous Control Extension (Weeks 21+)
13. **Model-versioned canary rollout** — `config-feature-flag-service` routing for
    `ai-inference-service` model versions. Required before any new model version goes to 100%.
14. **Auto-rollback policy** — ArgoCD + Prometheus alerting rule that reverts
    `ai-orchestrator-service` if escalation rate or policy violation rate exceeds threshold.
15. **Churn prevention agent** — autonomous wallet credit / loyalty nudge within pre-approved
    bounds for CLV model's high-churn-risk cohort. Requires wallet write tool + policy envelope
    + evaluation pipeline running first.

---

## Appendix A: Tool Risk Classification Reference

| Tool | Service | Write? | Reversible? | PII? | Recommended min. mode |
|---|---|---|---|---|---|
| catalog.search | catalog-service | No | — | No | Autonomous |
| catalog.get_product | catalog-service | No | — | No | Autonomous |
| catalog.list_products | catalog-service | No | — | No | Autonomous |
| pricing.calculate | pricing-service | No (calc only) | — | No | Autonomous |
| pricing.get_product | pricing-service | No | — | No | Autonomous |
| inventory.check | inventory-service | No | — | No | Autonomous |
| cart.get | cart-service | No | — | Yes (user_id) | Guarded (with PII vault) |
| order.get | order-service | No | — | Yes | Guarded (with PII vault) |
| *refund.issue* | payment-service | Yes | Partially | Yes | Guarded + cap + human for >cap |
| *catalog.draft_update* | catalog-service | Staging only | Yes | No | Propose-only |
| *catalog.publish* | catalog-service | Yes | Yes (versioned) | No | Propose-only + approval |
| *wallet.credit* | wallet-service | Yes | Yes (reverse tx) | Yes | Guarded + policy envelope |
| *account.suspend* | identity-service | Yes | Yes (reinstate) | Yes | Never autonomous |

*Italicized tools do not yet exist in the registry — shown for planning purposes.*

---

## Appendix B: Guardrail Layer Summary

| Guardrail | Implementation | What it stops |
|---|---|---|
| Injection detection | `guardrails/injection.py` | Prompt injection attempts |
| PII vault | `guardrails/pii.py` (PIIVault) | PII leakage in LLM context and output |
| Output validation | `guardrails/output_validator.py` (OutputPolicy) | Policy violations in generated output |
| Rate limiter | `guardrails/rate_limiter.py` | Request flooding, cost runaway |
| Escalation policy | `guardrails/escalation.py` (EscalationPolicy) | High-risk decisions reaching autonomous path |
| Budget tracker | `graph/budgets.py` (BudgetTracker) | Cost and latency overruns |
| Circuit breaker | `graph/tools.py` (CircuitBreaker) | Cascading failures to downstream services |
| Tool allowlist | `config.py` (tool_allowlist) | Unauthorized tool calls |
| Per-agent allowlist | **Not implemented** | Lateral tool access across agent types |
| Allergen guard | **Not implemented** | Unsafe food substitutions |
| Bulk operation gate | **Not implemented** | Blast-radius amplification |
| Durable audit emit | **Not implemented** | Silent agent decisions |

---

*Document version: 1.0 — Iteration 3 benchmark series.*  
*Related documents: `docs/reviews/AI-AGENT-FLEET-PLAN-2026-02-13.md`,*  
*`docs/reviews/fraud-detection-service-review.md`,*  
*`docs/reviews/iter3/platform/`, `services/ai-orchestrator-service/README.md`.*
