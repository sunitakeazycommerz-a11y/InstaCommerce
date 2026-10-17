# Validation & Rollout Playbooks — InstaCommerce Q-Commerce Platform

**Appendix** · Iteration 3 Implementation Guide  
**Scope**: All production changes across Java services, Go services, Python/AI services, contracts, data platform, and ML/AI agents  
**Maintained by**: Platform Engineering · SRE  
**Last updated**: 2026-03-07

---

## Table of Contents

1. [Guiding Principles](#1-guiding-principles)
2. [CI Gates & Pre-Merge Validation](#2-ci-gates--pre-merge-validation)
3. [Feature Flags & Kill Switches](#3-feature-flags--kill-switches)
4. [Canary & Progressive Delivery](#4-canary--progressive-delivery)
5. [Soak Testing](#5-soak-testing)
6. [SLO Gates & Metric Watchlists](#6-slo-gates--metric-watchlists)
7. [Rollback Runbooks](#7-rollback-runbooks)
8. [Incident Handling](#8-incident-handling)
9. [Post-Release Review](#9-post-release-review)
10. [Change-Type Checklists](#10-change-type-checklists)
    - [10.1 Service Changes](#101-service-changes-java--go--python)
    - [10.2 Contract Changes](#102-contract-changes-protobuf--json-schema--kafka-topics)
    - [10.3 Data Platform Changes](#103-data-platform-changes-dbt--airflow--beam--great-expectations)
    - [10.4 ML Model Changes](#104-ml-model-changes)
    - [10.5 AI-Agent Changes](#105-ai-agent-changes-ai-orchestrator--ai-inference)

---

## 1. Guiding Principles

These playbooks are grounded in the actual InstaCommerce infrastructure:

| Principle | Repo anchor |
|-----------|-------------|
| **Every change is reversible in < 5 minutes** | ArgoCD `selfHeal` + Git revert → auto-sync |
| **Gates, not heroes** | CI path filters in `ci.yml` enforce per-service quality gates automatically |
| **Flag first, ship second** | `config-feature-flag-service` wraps every risky path before it hits prod traffic |
| **Observe, then promote** | Prometheus `instacommerce-slos` group defines the objective truth; never promote on a hunch |
| **Narrow blast radius** | Helm `values-dev.yaml` → `values-prod.yaml` promotion is the only deployment path; no ad hoc `kubectl apply` |
| **Contracts are public API** | `contracts/` event schemas and Protobuf stubs follow a 90-day dual-version window; no breaking changes in-place |

---

## 2. CI Gates & Pre-Merge Validation

### 2.1 Pipeline Overview

Every PR and `develop` push runs only the jobs that correspond to changed paths (controlled by the `detect-changes` job in `.github/workflows/ci.yml`). Merges to `main`/`master` run the full matrix.

```
PR opened / push to develop
  └── detect-changes          (dorny/paths-filter — per service path filters)
        ├── security-scan     (Gitleaks secret scan + Trivy fs CRITICAL/HIGH)
        ├── dependency-review (GitHub Advanced Security — HIGH+ CVE fail)
        ├── build-test        (per changed Java service: gradle test + bootJar)
        └── go-build-test     (per changed Go service: go test + go build)
            │  go-shared change → all 8 Go modules re-validated
            │
push to main/master
        ├── All of the above, plus:
        ├── docker build + Trivy image scan (CRITICAL/HIGH → exit 1)
        ├── Push to asia-south1-docker.pkg.dev/instacommerce/images
        └── deploy-dev: update deploy/helm/values-dev.yaml with new SHA tag
                └── ArgoCD auto-sync → GKE dev namespace
```

### 2.2 Adding a New Service to CI

When a new service is introduced, all three of the following must be updated **in the same PR** to preserve the gate:

1. `settings.gradle.kts` — add the Gradle subproject (Java) or `go.mod` (Go)
2. `.github/workflows/ci.yml` — add to `filters:`, the `set-matrix` array, and the `go_service_to_helm` map if the Go module name differs from its Helm deploy key
3. `deploy/helm/values-dev.yaml` — add the service entry so `deploy-dev` can update it

> **Known Go → Helm mappings** (ci.yml `go_service_to_helm`):  
> `cdc-consumer-service → cdc-consumer`  
> `location-ingestion-service → location-ingestion`  
> `payment-webhook-service → payment-webhook`

### 2.3 Contracts Validation Gate

```bash
# Run locally before opening a contract-changing PR
./gradlew :contracts:build   # compiles protos + validates JSON schemas
```

CI performs: proto compilation, JSON Schema draft-07 validation, breaking-change diff against `main`, and consumer compatibility test. A failing `:contracts:build` blocks all downstream service builds.

### 2.4 Security Gates

| Gate | Tool | Fail condition | Scope |
|------|------|----------------|-------|
| Secret scan | Gitleaks | Any secret detected | All commits (PR + push) |
| Filesystem scan | Trivy | CRITICAL or HIGH CVE | PR: `exit-code: 0` (warn); push to main: `exit-code: 1` (block) |
| Image scan | Trivy | CRITICAL or HIGH CVE | Push to main only — image must be clean before push to Artifact Registry |
| Dependency review | GitHub Advanced Security | HIGH+ CVE in new dependencies | PRs only |

> **Note**: Trivy filesystem scan is non-blocking on PRs but blocking on main to avoid blocking contribution while protecting production.

---

## 3. Feature Flags & Kill Switches

### 3.1 The `config-feature-flag-service`

InstaCommerce runs its own feature flag service (`config-feature-flag-service`, port 8096). It supports four flag types evaluated via consistent Murmur3 hashing:

| Type | Use case |
|------|----------|
| `BOOLEAN` | On/off kill switch |
| `PERCENTAGE` | Canary traffic split (0–100%) |
| `USER_LIST` | Internal/beta-user targeting |
| `JSON` | Runtime config delivery |

Flags are cached in-process (Caffeine, 30 s TTL, max 5000 entries). Propagation latency is therefore up to ~30 s after a flag write. Plan accordingly for time-sensitive kill switch activations.

### 3.2 Flag Naming Convention

```
{domain}.{service}.{feature}[.{env}]

Examples:
  checkout.checkout-orchestrator.new-saga-flow
  pricing.pricing-service.surge-v2
  ml.fraud-detection.model-v3-inference
  ai.ai-orchestrator.agent-dispatch-v2
```

### 3.3 Creating a Feature Flag

```bash
# Via Admin API (requires admin JWT)
POST /admin/flags
{
  "key": "checkout.checkout-orchestrator.new-saga-flow",
  "flagType": "PERCENTAGE",
  "enabled": true,
  "rolloutPercentage": 0,
  "description": "New Temporal saga v2 — canary rollout"
}
```

Start at `rolloutPercentage: 0`. Increment in steps: 1 → 5 → 10 → 25 → 50 → 100.

### 3.4 Kill Switch Pattern

A `BOOLEAN` flag with `enabled: false` is the canonical kill switch. Any risky code path must check its flag before execution:

```java
// Java (Spring — inject FlagEvaluationClient)
if (!flagClient.evaluate("checkout.checkout-orchestrator.new-saga-flow", userId)) {
    return legacyCheckoutFlow(request);
}
return newSagaFlow(request);
```

```go
// Go
if !flags.IsEnabled(ctx, "pricing.pricing-service.surge-v2", userID) {
    return legacySurge(ctx, req)
}
return surgeV2(ctx, req)
```

```python
# Python (FastAPI — ai-orchestrator-service, ai-inference-service)
if not await flag_client.is_enabled("ai.ai-orchestrator.agent-dispatch-v2", user_id):
    return await legacy_dispatch(request)
return await agent_dispatch_v2(request)
```

### 3.5 Emergency Kill Switch Activation

```bash
# Flip flag off via Admin API (< 30 s propagation with cache TTL)
PATCH /admin/flags/checkout.checkout-orchestrator.new-saga-flow
{ "enabled": false }

# Or for faster, cache-bypassing effect: add a USER_LIST override
# for the affected user segment, or restart the affected pods to
# flush the Caffeine cache immediately:
kubectl rollout restart deployment/checkout-orchestrator-service -n instacommerce
```

### 3.6 Flag Lifecycle

```
create (rollout%=0)
  → canary ramp (1% → 5% → 25% → 50% → 100%)
    → soak at 100% for ≥ 72 h
      → flag cleanup (remove code gate, delete flag)
        OR
      → rollback (flip to 0% or enabled=false)
```

Flag entries that have been at 100% without incident for > 2 weeks are technical debt. Schedule cleanup sprints per quarter.

---

## 4. Canary & Progressive Delivery

### 4.1 Delivery Path

InstaCommerce does not use a service mesh canary controller (e.g., Argo Rollouts, Flagger) in the current architecture. Progressive delivery is implemented via **feature flags + image tag promotion**:

```
PR merged to main
  → CI: build image (SHA tag) + push to Artifact Registry
  → CI: update deploy/helm/values-dev.yaml (SHA tag)
  → ArgoCD: auto-sync to dev (GKE instacommerce namespace)
  → Manual: validate in dev
  → Manual: update deploy/helm/values-prod.yaml (SHA tag) via PR
  → ArgoCD: auto-sync to prod
```

Traffic splitting within an environment is controlled by the `PERCENTAGE` flag type in `config-feature-flag-service`.

### 4.2 Canary Steps

| Step | Flag % | Hold time | Gate condition |
|------|--------|-----------|----------------|
| Smoke | 1% | 15 min | Zero new 5xx; p99 < 500 ms; no new pod restarts |
| Low canary | 5% | 30 min | Error rate ≤ baseline; Kafka consumer lag stable |
| Medium canary | 25% | 1 h | All SLO watchlist metrics within bounds |
| High canary | 50% | 2 h | Payment success rate ≥ 99.5%; order completion ≥ baseline |
| Full rollout | 100% | 72 h soak | No alerts firing; CloudSQL CPU < 80% |

Abort at any step by setting the flag to 0% or `enabled: false`.

### 4.3 Canary for Infrastructure/Helm Changes

For Helm chart changes (new env vars, resource limits, HPA thresholds) that do not have a feature-flag hook, the canary is done by promoting to dev first and observing for ≥ 1 h before raising the prod values PR.

```bash
# Verify ArgoCD sync status after dev promotion
argocd app get instacommerce --refresh
argocd app wait instacommerce --health --sync --timeout 300
```

### 4.4 Rollout Sequencing for Cross-Service Changes

When a change touches multiple services that share an event contract or gRPC interface, roll out **consumers before producers**:

```
1. Deploy updated consumer(s) — they must tolerate both old and new schema
2. Canary producer at 1% → validate consumers receive and process events
3. Ramp producer to 100%
4. After 90-day compatibility window, remove old schema support from consumers
```

This sequence is mandatory for any `contracts/` change that involves a new `vN` schema file.

---

## 5. Soak Testing

### 5.1 Soak Definition

A **soak** is 72 hours of full production traffic (flag at 100%) with the monitoring watchlist active, before the feature flag is cleaned up and the change is declared stable.

For changes to critical paths (checkout, payment, order creation, rider dispatch), the minimum soak is **7 days**.

### 5.2 Soak Watchlist — Automated

The following Prometheus rules in `monitoring/prometheus-rules.yaml` are the automated soak guards:

| Alert | Threshold | Action if firing |
|-------|-----------|-----------------|
| `HighErrorRate` | 5xx rate > 1% for 5 min | Page on-call; immediate rollback decision |
| `HighLatency` | p99 > 500 ms for 5 min | Investigate; consider rollback if not transient |
| `KafkaConsumerLag` | lag > 1000 for 10 min | Check producer throughput; may indicate schema mismatch |
| `FrequentPodRestarts` | > 3 in 30 min | Immediate rollback; check OOM or crash loop |
| `DatabaseHighCPU` | CloudSQL CPU > 80% for 10 min | Scale up or revert migration; check N+1 queries |

### 5.3 Soak Watchlist — Manual (per change type)

Beyond Prometheus alerts, review these manually during soak:

**Order pipeline:**
- Orders created / min vs. 7-day baseline (Grafana: Order Pipeline dashboard)
- Order-to-dispatch P50/P95/P99 (SLA: < 10 min for q-commerce)
- Fulfillment SLA breach rate

**Payment:**
- Payment authorization success rate ≥ 99.5%
- Refund processing latency
- `reconciliation-engine` discrepancy count (should be 0)

**Rider dispatch:**
- Rider assignment latency (P95 < 60 s)
- `dispatch-optimizer-service` scoring errors
- `location-ingestion-service` consumer lag

**ML / AI:**
- Prediction latency (P99 from ML Model Performance dashboard)
- Model fallback rate (should be < 1%)
- Drift PSI gauge (threshold: > 0.2 triggers retrain)

### 5.4 Soak Sign-Off

Before marking a soak complete, the on-call engineer records:

```
Soak sign-off for: <feature name>
SHA: <git sha>
Flag: <flag key> at 100%
Period: <start ISO> → <end ISO>
Alerts fired: [none | list with resolution]
Metric anomalies: [none | description]
Approved by: <engineer name>
```

---

## 6. SLO Gates & Metric Watchlists

### 6.1 Platform SLOs

| SLO | Objective | Measurement |
|-----|-----------|-------------|
| API availability | 99.9% (rolling 30-day window) | `1 - (5xx_count / total_requests)` |
| API p99 latency | < 500 ms | `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))` |
| Kafka consumer lag | < 1000 records per topic | `kafka_consumer_records_lag_max` |
| Pod stability | < 3 restarts / 30 min | `increase(kube_pod_container_status_restarts_total[30m])` |
| CloudSQL CPU | < 80% sustained | `cloudsql_database_cpu_utilization` |

All SLOs map directly to the `instacommerce-slos` Prometheus rule group in `monitoring/prometheus-rules.yaml`.

### 6.2 Promotion Gate: Dev → Prod

A change must satisfy **all** of the following before a prod values PR is raised:

- [ ] All `instacommerce-slos` alerts silent in dev for ≥ 1 h post-deployment
- [ ] No new `FrequentPodRestarts` for the changed service
- [ ] Feature flag validated in dev at target percentage
- [ ] Canary steps 1%–25% completed in dev (for user-visible features)
- [ ] Security scan (Trivy image) passed with exit 0
- [ ] No CRITICAL/HIGH CVEs in dependency review

### 6.3 Metric Watchlist by Domain

**Checkout / Order Critical Path:**
```promql
# Order creation rate (should be stable ±15% vs baseline)
rate(http_server_requests_seconds_count{service="order-service", uri="/api/v1/orders", status="2.."}[5m])

# Checkout p99
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{service="checkout-orchestrator-service"}[5m]))

# Temporal workflow failure rate
# (emitted as custom metric from checkout-orchestrator-service)
rate(temporal_workflow_failed_total[5m])
```

**Inventory / Warehouse:**
```promql
# Stock reservation failure rate
rate(http_server_requests_seconds_count{service="inventory-service", status="4.."}[5m])

# Out-of-stock events (custom metric)
increase(stock_reservation_failed_total[5m])
```

**Payment:**
```promql
# Authorization success rate (must be ≥ 99.5%)
sum(rate(http_server_requests_seconds_count{service="payment-service", uri=~".*/authorize", status="2.."}[5m]))
/
sum(rate(http_server_requests_seconds_count{service="payment-service", uri=~".*/authorize"}[5m]))

# Reconciliation discrepancies
reconciliation_discrepancy_count
```

**ML Inference:**
```promql
# Model prediction latency (P99 target < 200ms)
histogram_quantile(0.99, rate(model_prediction_seconds_bucket{service="ai-inference-service"}[5m]))

# Fallback rate (> 1% triggers investigation)
rate(model_prediction_fallback_total{service="ai-inference-service"}[5m])
/
rate(model_prediction_total{service="ai-inference-service"}[5m])
```

---

## 7. Rollback Runbooks

### 7.1 Standard Rollback (< 5 minutes)

The canonical rollback is a **Git revert** of the `values-dev.yaml` or `values-prod.yaml` SHA tag change. ArgoCD's `selfHeal: true` syncs the cluster within ~60 s of the push.

```bash
# 1. Identify the last good commit for the values file
git log --oneline deploy/helm/values-dev.yaml   # dev
git log --oneline deploy/helm/values-prod.yaml  # prod

# 2. Revert the merge commit (or the deploy-dev bot commit)
git revert <bad-commit-sha> --no-edit
git push origin main

# 3. Confirm ArgoCD picks up the change
argocd app get instacommerce --refresh
argocd app wait instacommerce --sync --health --timeout 300
```

### 7.2 Emergency In-Cluster Rollback (bypass Git — last resort)

Use only when Git push is blocked or when ArgoCD sync is not converging.

```bash
# Step 1: Pause ArgoCD auto-sync to prevent it from overwriting your fix
argocd app set instacommerce --sync-policy none

# Step 2: Roll back the specific deployment
kubectl rollout undo deployment/<service-name> -n instacommerce
kubectl rollout status deployment/<service-name> -n instacommerce

# Step 3: Verify the service is healthy
kubectl get pods -n instacommerce -l app=<service-name>
curl -f http://<service>/actuator/health/readiness   # Java services
curl -f http://<service>/health/ready                # Go services

# Step 4: Immediately raise a PR to revert values-prod.yaml to match
# the in-cluster state, then re-enable ArgoCD auto-sync
argocd app set instacommerce --sync-policy automated --self-heal --auto-prune
```

> **Never leave ArgoCD paused.** It is the cluster's source of truth. Re-enable within the same incident.

### 7.3 Feature Flag Rollback (traffic-level, < 30 s)

```bash
# Turn off a feature via Admin API
curl -X PATCH https://config-feature-flag-service/admin/flags/<flag-key> \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'

# For near-instant effect (flush Caffeine cache), restart the affected pod(s)
kubectl rollout restart deployment/<service-name> -n instacommerce
```

### 7.4 Database Migration Rollback

Flyway does not support automatic down-migrations. Apply these rules:

| Migration type | Rollback strategy |
|----------------|-------------------|
| New nullable column / new table | Drop the column/table via a new migration `V{N+1}__rollback_<name>.sql` |
| New non-nullable column | Cannot roll back without downtime; add nullable first, backfill, then add NOT NULL constraint in a separate migration |
| Index addition | Drop index via new migration — no data loss |
| Constraint addition | Drop constraint via new migration |
| Data mutation | Requires application-level data repair script; document in PR description |

All Flyway migrations must be tested against a local Postgres via `docker-compose up -d` before merge.

### 7.5 Kafka / Outbox Rollback

If a schema change causes consumer deserialization failures:

```bash
# 1. Flip the producer flag to 0% — stops emitting new-schema events
PATCH /admin/flags/<producer-flag-key>  { "rolloutPercentage": 0 }

# 2. Check consumer lag stabilizes
kubectl exec -n instacommerce deploy/cdc-consumer-service -- \
  kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group <consumer-group>

# 3. If DLQ has accumulated events, replay after reverting consumer:
kubectl rollout undo deployment/<consumer-service> -n instacommerce
# Then drain DLQ via admin tooling

# 4. Revert the schema PR — create a new vN schema file per contracts convention
./gradlew :contracts:build
```

### 7.6 ArgoCD Rollback to a Historical Revision

```bash
# List all sync history
argocd app history instacommerce

# Roll back to a specific ArgoCD revision (safe — reads from Git history)
argocd app rollback instacommerce <revision-id>

# Force sync to a specific Git commit SHA
argocd app sync instacommerce --revision <git-sha>
```

---

## 8. Incident Handling

### 8.1 Severity Levels

| Severity | Definition | Response time | Examples |
|----------|-----------|---------------|---------|
| **P0 — Critical** | Total service outage; payment/order creation failing; data loss risk | < 15 min | `HighErrorRate` firing on `order-service`, `payment-service`, or `checkout-orchestrator-service`; `FrequentPodRestarts` on core services |
| **P1 — High** | Partial outage; SLO breach; degraded checkout funnel | < 1 h | `HighLatency` sustained > 15 min; `KafkaConsumerLag` > 5000; rider dispatch errors |
| **P2 — Medium** | Non-critical feature degraded; background job failure | < 4 h | `DatabaseHighCPU`; notification delays; ML fallback elevated |
| **P3 — Low** | Minor; no user impact | Next business day | Audit log backlog; dashboard anomaly |

Alert routing mirrors `monitoring/README.md`:  
- Critical → PagerDuty + Slack `#incidents`  
- Warning → Slack `#alerts`

### 8.2 Incident Response Flow

```
1. PAGE RECEIVED
   └── Acknowledge in PagerDuty (< 15 min for P0/P1)

2. TRIAGE (5 min)
   ├── Check Grafana: Service Overview dashboard
   ├── Identify affected service(s) — look at HighErrorRate by {service}
   ├── Check recent deploys: git log --oneline deploy/helm/values-prod.yaml
   └── Check ArgoCD: argocd app get instacommerce

3. CONTAIN (immediate)
   ├── If recent deploy is suspect → Git revert (Section 7.1)
   ├── If feature flag related → flip kill switch (Section 7.3)
   └── If pod crash loop → kubectl rollout undo (Section 7.2)

4. DIAGNOSE (parallel with containment)
   ├── Logs: kubectl logs -n instacommerce deploy/<service> --tail=200
   ├── Traces: Grafana → Tempo (via OTEL/OTLP)
   ├── DB: Check CloudSQL slow query log; cpu_utilization metric
   └── Kafka: Check consumer lag, DLQ depth

5. RESOLVE
   ├── Apply fix (code / config / migration)
   ├── Validate in dev before prod
   └── Re-enable feature flag / re-deploy

6. POST-INCIDENT (within 24 h for P0/P1)
   └── See Section 9
```

### 8.3 Blast Radius Assessment

Before any production action during an incident, quickly assess blast radius:

| Question | Where to check |
|----------|---------------|
| Which services share the failing database? | `scripts/init-dbs.sql` — each service has its own DB |
| Which services consume the affected Kafka topic? | `contracts/README.md` — event types by domain |
| Which services call this service via gRPC? | `contracts/src/main/proto/` |
| Will a pod restart affect in-flight Temporal workflows? | Temporal UI (port-forward if needed); Temporal uses durable workflow state |
| Will a rollback break an in-progress DB migration? | Check Flyway migration history: `SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;` |

### 8.4 Communication Template

```
[P0 INCIDENT] <service-name> — <brief description>

Status: INVESTIGATING | CONTAINED | RESOLVED
Started: <ISO timestamp>
Affected: <user-visible impact>
Services: <service list>
Recent changes: <SHA and deploy time if applicable>

Actions taken:
- <action 1>
- <action 2>

Next update in: 15 min
```

Post to Slack `#incidents`. Update every 15 min for P0, every 30 min for P1.

---

## 9. Post-Release Review

### 9.1 When to Hold a Post-Release Review

| Change | Review required |
|--------|----------------|
| P0 or P1 incident during rollout | Yes — mandatory PIR within 24 h |
| P2 incident | Yes — lightweight review within 48 h |
| Any change to checkout, payment, or order creation | Yes — async review after 72-h soak |
| Schema version bump (new `vN` file) | Yes — async review after 90-day window opens |
| New ML model version in prod | Yes — after 7-day soak (see Section 10.4) |
| Routine service patch with no incidents | Optional — team discretion |

### 9.2 Post-Incident Review (PIR) Structure

```markdown
## PIR: <Incident title>

**Date**: <ISO date>
**Severity**: P0 / P1
**Duration**: <start> → <end> (<total minutes>)
**Affected services**: <list>
**Author(s)**: <names>

### Timeline
<chronological list of events, detections, actions>

### Root Cause
<technical root cause — one sentence>

### Contributing Factors
<list>

### Impact
- Users affected: <estimate>
- Orders impacted: <count or estimate>
- Revenue impact: <if known>

### What Went Well
<list>

### Action Items
| Item | Owner | Due date | Status |
|------|-------|----------|--------|
| <item> | <owner> | <date> | pending |

### Detection Gap
Was the issue detectable earlier? If not, what new alert or gate would have caught it?
Add to `monitoring/prometheus-rules.yaml` if applicable.
```

### 9.3 Post-Release Review (Non-Incident)

For successful but significant releases, a lighter async review answers:

1. Did SLO metrics remain within bounds throughout the soak?
2. Were there any unexpected patterns in the metric watchlist?
3. Did the feature flag ramp proceed at the expected pace, or did we pause? Why?
4. Are there follow-up items (flag cleanup, migration cleanup, dead code removal)?
5. Should any new Prometheus alert be added based on what we observed?

---

## 10. Change-Type Checklists

These checklists are concrete gate lists. Each item must be checked before the stated gate.

---

### 10.1 Service Changes (Java / Go / Python)

#### Pre-merge gates

- [ ] `./gradlew :services:<service-name>:test` passes (Java) OR `cd services/<service-name> && go test -race ./...` passes (Go) OR `pytest -v` passes (Python)
- [ ] `./gradlew :services:<service-name>:build -x test` succeeds (Java) OR `go build ./...` succeeds (Go)
- [ ] No new CRITICAL/HIGH CVEs introduced (Trivy FS scan in CI)
- [ ] No secrets committed (Gitleaks in CI)
- [ ] If `go-shared` is modified: all 8 Go modules re-validated (`go test ./... && go build ./...` in each)
- [ ] New/changed environment variables are backed by `application.yml` property definitions (Java) or `config` package usage (Go `go-shared/config`) — not hardcoded constants
- [ ] Health endpoints intact: `/actuator/health/readiness` + `/actuator/prometheus` (Java) or `/health/ready` + `/metrics` (Go)
- [ ] If new Flyway migration: tested locally against `docker-compose up -d` Postgres

#### Pre-prod promotion gates

- [ ] Deployed to dev; all `instacommerce-slos` alerts silent for ≥ 1 h
- [ ] Feature flag created in `config-feature-flag-service` if change is user-visible (set to 0%)
- [ ] Canary steps 1% → 5% completed in dev; no metric anomalies
- [ ] If the service is in the critical path (checkout, payment, order, inventory): load test at 2× peak RPS in dev
- [ ] `deploy/helm/values-dev.yaml` SHA tag matches the validated image
- [ ] Trivy image scan passed (exit 0) in CI

#### Prod promotion gates

- [ ] `deploy/helm/values-prod.yaml` PR reviewed by ≥ 1 engineer (not the author)
- [ ] Canary in prod at 1% for ≥ 15 min with zero new alerts
- [ ] Canary ramp to 25% for ≥ 1 h; SLO watchlist clean
- [ ] Flag ramped to 100%; soak period started
- [ ] Runbook link in PR description pointing to this document

#### Post-release

- [ ] 72-h soak completed with soak sign-off (Section 5.4)
- [ ] Feature flag scheduled for cleanup after ≥ 2 weeks at 100%
- [ ] PIR filed if any alert fired during rollout

---

### 10.2 Contract Changes (Protobuf / JSON Schema / Kafka Topics)

#### Additive changes (new optional field, new event type, new enum value)

- [ ] Change is confirmed additive (no existing required field removed or renamed)
- [ ] `./gradlew :contracts:build` passes locally
- [ ] CI `:contracts:build` passes (proto compilation + JSON Schema validation + consumer compatibility)
- [ ] `schema_version` unchanged (stays at `v1`) — document the addition in the schema file's description
- [ ] Downstream consumer services (check `contracts/README.md` event type table) can handle the new optional field gracefully (default/null handling)
- [ ] No changes to the 7 standard envelope fields (`event_id`, `event_type`, `aggregate_id`, `schema_version`, `source_service`, `correlation_id`, `timestamp`)

#### Breaking changes (field removal, type change, rename)

- [ ] New schema file created: `{EventName}.v{N+1}.json` — never overwrite the existing `vN` file
- [ ] 90-day deprecation window documented in a PR comment (start date, end date)
- [ ] Producer flag created: `contracts.<domain>.<event>.v{N+1}-producer` (default 0%)
- [ ] Consumer updated first (must tolerate both `vN` and `v{N+1}`)
- [ ] Consumer PR merged and deployed before producer ramp begins
- [ ] `./gradlew :contracts:build` passes with both schema versions present
- [ ] `contracts/README.md` updated: new event row + deprecation notice on old version
- [ ] After 90 days: PR to remove `vN` schema + consumer support + flag

#### New Kafka topic

- [ ] Topic naming follows convention: `{domain}.events` (e.g., `warehouse.events`)
- [ ] Topic created in Kafka (add to `scripts/init-dbs.sql` or Kafka provisioning script for local dev)
- [ ] Consumer group name registered and documented
- [ ] CDC/outbox pattern applied: new outbox table migration + Debezium connector configuration
- [ ] DLQ topic provisioned: `{domain}.events.dlq`

---

### 10.3 Data Platform Changes (dbt / Airflow / Beam / Great Expectations)

#### dbt model changes

- [ ] Layer boundary respected: `stg_` models only reference raw sources; `int_` models only reference `stg_`; `mart_` models only reference `int_`
- [ ] `dbt run --select <model>` completes without errors locally
- [ ] `dbt test --select <model>` passes (referential integrity, not-null, accepted-values tests)
- [ ] Breaking change (column removal, type change) → downstream `int_`/`mart_` models updated in same PR
- [ ] New `mart_` model: usage documented (which dashboards, reports, or ML features depend on it)
- [ ] Grain documented in model's `description` field in `schema.yml`

#### Airflow DAG changes

- [ ] DAG validated locally: `python <dag_file>.py` (no import errors)
- [ ] Schedule interval change: confirm downstream SLA impact (e.g., feature store refresh, ML training trigger)
- [ ] New connections/variables: documented in `data-platform/README.md` or DAG inline comment
- [ ] Idempotency verified: DAG can be re-run for the same execution_date without duplicating data
- [ ] Backfill strategy documented if historical data needs re-processing

#### Beam / Dataflow pipeline changes

- [ ] Unit tests pass: `cd data-platform/streaming && python -m pytest -v`
- [ ] Schema change in streaming pipeline aligned with `contracts/` event schemas
- [ ] Watermark and windowing logic reviewed for correctness at high throughput
- [ ] Pipeline upgrade strategy documented (drain vs. update vs. replace)
- [ ] Memory/worker configuration validated against expected event volume

#### Great Expectations changes

- [ ] New expectation suite: validated against a representative data sample
- [ ] Expectation breakage threshold aligned with SLO (e.g., 99% rows pass, not 100%, to allow for known edge cases)
- [ ] Checkpoint configuration updated if new suite is added
- [ ] Data quality failure → alert routing defined (Slack `#data-quality-alerts` at minimum)

#### Pre-prod gates (data platform)

- [ ] `dbt run --select staging && dbt run --select intermediate && dbt run --select marts` green
- [ ] `dbt test` passes on all changed models
- [ ] No new Great Expectations failures in staging run
- [ ] Airflow DAG run succeeded in staging environment
- [ ] Row counts / metric consistency checked against previous run (±5% threshold)

---

### 10.4 ML Model Changes

#### Model training & evaluation

- [ ] Config change in `ml/train/<model>/config.yaml` (prefer config over code)
- [ ] Evaluation gates defined in `ml/eval/`: offline metrics meet threshold before registering
- [ ] Feature definitions in `ml/feature_store/` updated if new features are used
- [ ] Feature pipeline upstream (`data-platform/dbt` or Beam) validated for the new features
- [ ] Model version registered in MLflow (or equivalent) with all experiment parameters logged
- [ ] Backward compatibility: model serving signature unchanged OR a new serving endpoint version added

#### Shadow / canary deployment

- [ ] New model deployed to `ai-inference-service` behind `PERCENTAGE` flag: `ml.<domain>.model-v{N}-inference` at 0%
- [ ] Shadow mode: new model runs in parallel with production model; predictions logged but not served (at 0% flag)
- [ ] Shadow period ≥ 48 h; prediction distribution compared to production model
- [ ] No statistically significant regression in offline metrics (AUC, F1, RMSE per model type)
- [ ] Drift PSI monitored: `monitoring/` ML Model Performance dashboard

#### Production ramp

- [ ] Flag ramped to 1%; prediction latency P99 < 200 ms
- [ ] Flag ramped to 10%; business KPI (e.g., fraud catch rate, ETA accuracy) stable vs. baseline
- [ ] Flag ramped to 50%; A/B statistical significance check (if applicable)
- [ ] Flag ramped to 100%; fallback rate < 1% (any prediction error falls back to previous model version)

#### 7-day soak (ML)

- [ ] Drift PSI < 0.2 throughout soak
- [ ] Model prediction latency P99 within budget
- [ ] Fallback rate trending toward 0%
- [ ] No `FrequentPodRestarts` on `ai-inference-service`
- [ ] Post-release review (Section 9.3) filed

#### Rollback (ML)

```bash
# Flip the ML model flag to 0% (falls back to previous model)
PATCH /admin/flags/ml.<domain>.model-v{N}-inference  { "rolloutPercentage": 0 }

# If model was at 100% and previous model container was replaced:
# Roll back the ai-inference-service deployment
kubectl rollout undo deployment/ai-inference-service -n instacommerce
```

---

### 10.5 AI-Agent Changes (ai-orchestrator-service / ai-inference-service)

AI-agent changes carry higher blast radius than typical service changes due to non-determinism, tool-call side effects, and LLM cost sensitivity.

#### Pre-merge gates

- [ ] `cd services/ai-orchestrator-service && pytest -v` and `cd services/ai-inference-service && pytest -v` pass
- [ ] Agent graph change (LangGraph): new node/edge validated with unit tests covering the full graph execution path
- [ ] Tool call side effects documented: list every external service the agent calls (order-service, payment-service, inventory-service, etc.)
- [ ] Idempotency: agent tool calls are idempotent or protected by deduplication keys
- [ ] Cost budget: estimate LLM token cost per invocation; document in PR if > 2× current baseline
- [ ] Timeout / circuit-breaker configured for every LLM call (avoid unbounded latency)
- [ ] Fallback path defined: if LLM is unavailable, agent falls back to deterministic logic

#### Feature flag gate

- [ ] Agent change wrapped in `BOOLEAN` or `PERCENTAGE` flag: `ai.ai-orchestrator.{feature}` at 0%
- [ ] Internal/beta users targeted first via `USER_LIST` flag before percentage rollout
- [ ] Kill switch tested in dev: flipping flag to `enabled: false` stops agent from entering new code path

#### Canary ramp for AI agents

| Step | Flag | Hold | Gate |
|------|------|------|------|
| Internal | USER_LIST (team) | 24 h | No unhandled exceptions; tool calls succeeding |
| Beta | 1% | 48 h | LLM error rate < 0.1%; no runaway cost |
| Low | 5% | 48 h | Token cost within budget; p99 agent latency < 5 s |
| Medium | 25% | 72 h | Business KPI stable; fallback rate < 2% |
| Full | 100% | 7-day soak | All metrics within bounds |

#### Soak (AI agents — 7 days minimum)

- [ ] Agent error rate (unhandled exceptions) < 0.1%
- [ ] LLM fallback rate < 2%
- [ ] Per-invocation LLM token cost stable (no runaway prompt expansion)
- [ ] No cascading side effects to downstream services (check order-service, payment-service error rates)
- [ ] `ai-orchestrator-service` + `ai-inference-service` pod restarts < 1/day

#### Rollback (AI agents)

```bash
# Primary: flip kill switch
PATCH /admin/flags/ai.ai-orchestrator.{feature}  { "enabled": false }

# Secondary: roll back deployment
kubectl rollout undo deployment/ai-orchestrator-service -n instacommerce
kubectl rollout undo deployment/ai-inference-service -n instacommerce

# Verify fallback is serving
curl -f http://ai-orchestrator-service/health/ready
```

#### Post-release (AI agents)

- [ ] 7-day soak sign-off (Section 5.4)
- [ ] LLM cost report filed (actual vs. estimate)
- [ ] Agent behavior review: sample 50 traces from OTEL/Tempo for correctness
- [ ] Any new tool calls added to the agent's documented side-effect list

---

## Appendix A: Quick-Reference Commands

```bash
# Check ArgoCD sync status
argocd app get instacommerce --refresh

# Watch all pods in the instacommerce namespace
kubectl get pods -n instacommerce -w

# Roll back a single service deployment
kubectl rollout undo deployment/<service-name> -n instacommerce

# Check a service's readiness
curl -f http://<service>/actuator/health/readiness    # Java
curl -f http://<service>/health/ready                  # Go

# Check Kafka consumer lag
kubectl exec -n instacommerce deploy/cdc-consumer-service -- \
  kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group <consumer-group>

# View recent Flyway migration history
kubectl exec -n instacommerce deploy/<service-name> -- \
  psql $DATABASE_URL -c \
  "SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"

# Flip a feature flag kill switch (requires admin JWT)
curl -X PATCH https://config-feature-flag-service/admin/flags/<flag-key> \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'

# Flush Caffeine cache on a service by restarting its pods
kubectl rollout restart deployment/<service-name> -n instacommerce

# Run the full Java test suite for a service
./gradlew :services:<service-name>:test

# Run Go tests with race detection
cd services/<service-name> && go test -race ./...

# Rebuild contracts after proto/schema changes
./gradlew :contracts:build

# Run targeted dbt test
cd data-platform/dbt && dbt test --select <model-or-selector>

# Run a single pytest
cd services/<python-service> && pytest path/to/test_file.py::test_name -v
```

---

## Appendix B: Escalation Matrix

| Situation | First responder | Escalation path |
|-----------|----------------|-----------------|
| P0 alert fires (HighErrorRate / FrequentPodRestarts) | On-call engineer (PagerDuty) | → Platform lead → VP Engineering (if > 30 min unresolved) |
| Payment system degraded | On-call engineer | → Payment team lead → Finance stakeholder |
| ML model drift PSI > 0.2 | ML on-call | → ML platform lead → Data scientist (model owner) |
| AI agent runaway cost | AI on-call | → AI platform lead → Flag off immediately, then investigate |
| Schema breaking change detected in prod | On-call + Contract owner | → Platform Engineering + affected service owners |
| Data quality failure (Great Expectations) | Data engineer on-call | → Data platform lead → Analytics stakeholders |

---

## Appendix C: Related Documents

| Document | Path |
|----------|------|
| Monitoring alert rules | `monitoring/prometheus-rules.yaml` |
| Monitoring README (dashboards, on-call) | `monitoring/README.md` |
| ArgoCD GitOps runbook | `argocd/README.md` |
| CI/CD pipeline definition | `.github/workflows/ci.yml` |
| Helm chart and values | `deploy/helm/` |
| Event contracts and schema versioning | `contracts/README.md` |
| Feature flag service review | `docs/reviews/config-feature-flag-service-review.md` |
| Principal Engineering Implementation Program | `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-PROGRAM-2026-03-06.md` |
| Iter 3 Service Cluster Implementation Guide | `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-SERVICE-WISE-2026-03-06.md` |
| Local infrastructure setup | `docker-compose.yml`, `scripts/init-dbs.sql` |
