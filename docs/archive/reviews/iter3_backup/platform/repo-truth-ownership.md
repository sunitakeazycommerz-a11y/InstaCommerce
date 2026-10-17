# Repo Truth, Ownership, and Governance — Concrete Implementation Guide

**Iteration:** 3  
**Date:** 2026-03-07  
**Audience:** Platform team, Principal Engineers, Staff Engineers, EMs  
**Status:** Wave 0 — pre-implementation (this document is the implementation specification)  
**Builds on:**
- `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md` §4.2, §5  
- `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-PLATFORM-WISE-2026-03-06.md` §4  
- `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-PROGRAM-2026-03-06.md` §5 (Wave 0)

---

## 1. Why this guide exists

Iteration 3 confirmed that documentation, CI path coverage, deployment manifests, and claimed capabilities diverge in at least nine concrete, enumerable ways. This document is not a retrospective — it is a migration specification that names every discrepancy, specifies the canonical source of truth for each surface, provides a step-by-step cleanup sequence, and defines guardrails that prevent the same drift from re-emerging.

The core principle:

> **A repo that cannot tell the truth about itself cannot safely govern the services it ships.**

Every subsequent wave (money-path hardening, contract enforcement, SRE maturity) depends on Wave 0 making the repo honest first.

---

## 2. Canonical sources of truth — definitive table

This table is the reference point. When any other file disagrees with a canonical source, the canonical source wins and the other file must be updated.

| Surface | Canonical source | What it governs |
|---------|-----------------|-----------------|
| Java service module identity | `settings.gradle.kts` | Module path, Gradle project name, build scope |
| Go service module identity | `services/<name>/go.mod` | Go module path, build entrypoint |
| Python service entrypoints | `services/<name>/app/main.py` + `requirements.txt` | FastAPI app shape, dependency set |
| CI validation scope | `.github/workflows/ci.yml` `detect-changes` job | Which paths trigger which build/test jobs |
| Helm deploy keys (what ArgoCD deploys) | `deploy/helm/values-dev.yaml` + `deploy/helm/values-prod.yaml` | Service names as Kubernetes workloads |
| Go-module → Helm-key mapping | `ci.yml` `deploy-dev` job `go_service_to_helm` map | What name a Go module is deployed under |
| Event envelope contract | `contracts/README.md` + `contracts/src/main/resources/schemas/` | Required fields, schema versioning rules |
| Proto/gRPC contracts | `contracts/src/main/proto/` | gRPC service interfaces |
| Architecture doc index | `docs/README.md` | What documents are authoritative |
| Infra topology | `infra/terraform/` | GCP resource declarations |
| GitOps sync config | `argocd/app-of-apps.yaml` | Which Helm releases ArgoCD manages |
| Local infrastructure | `docker-compose.yml` + `scripts/init-dbs.sql` | Dev-local bootstrap assumptions |

---

## 3. Current discrepancies — enumerated and categorised

### 3.1 CI path coverage gaps (high severity)

The `detect-changes` job in `ci.yml` contains path filters and build matrices only for Java services and Go services. The following surfaces change code, produce artifacts, or contain critical governance files, but trigger **no CI job** when modified:

| Missing coverage | Severity | Impact |
|-----------------|----------|--------|
| `services/ai-orchestrator-service/**` | P0 | Python FastAPI service ships to prod with no CI build or test |
| `services/ai-inference-service/**` | P0 | Python FastAPI service ships to prod with no CI build or test |
| `contracts/**` | P0 | Proto and JSON Schema changes are never compile-validated in CI |
| `data-platform/**` | P1 | dbt, Airflow, Beam changes are never linted or tested in CI |
| `data-platform-jobs/**` | P1 | Batch job changes are never validated in CI |
| `ml/**` | P1 | Training config and serving changes are never validated in CI |
| `infra/terraform/**` | P1 | Terraform changes are never validated (`terraform validate`) in CI |
| `deploy/helm/**` | P1 | Helm chart or values changes are never linted in CI |
| `.github/instructions/**` | P2 | Instruction-file changes are never reviewed or validated |
| `.github/workflows/**` | P2 | Workflow changes trigger no self-validation step |

**Consequence of current state:** `ai-orchestrator-service` and `ai-inference-service` are deployed to prod (present in `values-dev.yaml` and `values-prod.yaml`) but are never built or tested by CI. Any Python syntax error, broken import, or missing dependency will only surface at pod startup.

### 3.2 Service naming inconsistencies (medium severity)

Four Go services have different names in their source directory and `go.mod` versus their Helm deploy key. This mapping currently lives only inside the `deploy-dev` job shell script — it is not documented, tested, or enforced anywhere else.

| Source directory / Go module | Helm key (values-dev.yaml) | Mapping location |
|-----------------------------|---------------------------|-----------------|
| `cdc-consumer-service` | `cdc-consumer` | `ci.yml` deploy-dev inline map |
| `location-ingestion-service` | `location-ingestion` | `ci.yml` deploy-dev inline map |
| `payment-webhook-service` | `payment-webhook` | `ci.yml` deploy-dev inline map |
| `outbox-relay-service` | `outbox-relay` | `ci.yml` deploy-dev inline map |

**Risk:** Any engineer adding a new Go service must discover this convention by reading the deploy-dev shell script. There is no explicit registry or validation step. If the mapping is omitted, the service is skipped silently ("Skipping ${svc}; not present in deploy/helm/values-dev.yaml").

### 3.3 Services present in Helm but absent from CI (high severity)

`stream-processor-service` is present in the CI Go build matrix but **absent from both `values-dev.yaml` and `values-prod.yaml`**. It is never deployed. Conversely, `ai-orchestrator-service` and `ai-inference-service` are in both Helm values files but absent from the CI build matrix.

| Service | In CI build | In values-dev | In values-prod | Net state |
|---------|------------|--------------|----------------|-----------|
| `stream-processor-service` | ✅ Go matrix | ❌ | ❌ | Built but never deployed |
| `ai-orchestrator-service` | ❌ | ✅ | ✅ | Deployed but never CI-validated |
| `ai-inference-service` | ❌ | ✅ | ✅ | Deployed but never CI-validated |

### 3.4 Image registry name split (high severity)

Three distinct image registries are referenced across the repo:

| Registry path | Where referenced |
|--------------|-----------------|
| `asia-south1-docker.pkg.dev/instacommerce/images` | `ci.yml` build/push steps, `deploy/helm/values.yaml` (base chart) |
| `asia-south1-docker.pkg.dev/instacommerce/dev-images` | `deploy/helm/values-dev.yaml` |
| `asia-south1-docker.pkg.dev/instacommerce/prod-images` | `deploy/helm/values-prod.yaml` |

CI builds and pushes to `.../images` but `values-dev.yaml` expects `.../dev-images`. This means the image tag written into `values-dev.yaml` by the `deploy-dev` job points to a SHA that does not exist in the `dev-images` registry — the image exists in `images` only. ArgoCD pulling from `dev-images` with a fresh SHA will fail to pull.

### 3.5 Missing CODEOWNERS file (high severity)

`.github/CODEOWNERS` does not exist. Consequences:
- No required reviewer is enforced on any path.
- Contracts, infra, and security surfaces can be merged without subject-matter-expert review.
- No accountability trail links a service directory to a named team or person.
- GitHub branch-protection rules referencing CODEOWNERS have no effect.

### 3.6 Missing ADR directory (medium severity)

There is no `docs/adr/` directory and no Architecture Decision Record has been created. Decisions referenced throughout the principal engineering reviews (checkout authority consolidation, internal auth model, Kafka topic naming) are documented in review prose but have no formal record that can be linked from code, CODEOWNERS rules, or branch protection.

### 3.7 Docs governance — flat structure and unlabelled maturity (medium severity)

`docs/reviews/` is a flat directory with 30+ files mixed across time periods, scope levels, and maturity states. The `docs/reviews/iter3/` subdirectory was started but is inconsistently populated. Key issues:

- No file carries an explicit maturity marker (e.g., `status: authoritative`, `status: draft`, `status: superseded`).
- `docs/README.md` is a useful index but does not indicate which documents are current principal guidance versus historical snapshots.
- Old docs (e.g., `FLEET-ARCHITECTURE-REVIEW-2026-02-13.md`) are listed alongside current iteration 3 guidance without indicating supersession.
- Service review files in `docs/reviews/` follow inconsistent naming (`*-review.md`, `*-design.md`, `*-service-review.md`).

### 3.8 doc/README.md does not reference iter3/ subdocs (low severity)

The `iter3/` subdirectory exists (`docs/reviews/iter3/appendices/`, `benchmarks/`, `diagrams/`, `platform/`, `services/`) but none of the files in that tree are indexed in `docs/README.md`. New engineers cannot discover them from the docs index.

### 3.9 CI uses `actions/checkout@v6` (does not exist) (medium severity)

`ci.yml` references `actions/checkout@v6`, `actions/setup-java@v5`, `actions/setup-go@v6`, and `gradle/actions/setup-gradle@v5`. As of the latest GitHub Actions releases, `actions/checkout` is at v4, not v6. The CI may be running against a non-existent version tag, relying on a major-version float that resolves unexpectedly, or silently failing version pinning intent. This must be audited against GitHub's actual published release tags.

---

## 4. CODEOWNERS specification

### 4.1 Structure

Create `.github/CODEOWNERS` with the following ownership map. Team handles are placeholders — replace with actual GitHub team slugs before merging.

```
# InstaCommerce CODEOWNERS
# Format: <path pattern>  <owner(s)>
# Owners are notified on every PR that touches matching paths.
# Required-reviewers branch-protection rule must reference CODEOWNERS.

# --- Platform / cross-cutting ---
/.github/                           @instacommerce/platform-team
/contracts/                         @instacommerce/platform-team @instacommerce/principal-engineers
/deploy/                            @instacommerce/platform-team @instacommerce/sre-team
/argocd/                            @instacommerce/platform-team @instacommerce/sre-team
/infra/                             @instacommerce/platform-team @instacommerce/sre-team
/monitoring/                        @instacommerce/sre-team
/scripts/                           @instacommerce/platform-team
/docker-compose.yml                 @instacommerce/platform-team

# --- Data & ML ---
/data-platform/                     @instacommerce/data-platform-team
/data-platform-jobs/                @instacommerce/data-platform-team
/ml/                                @instacommerce/ml-team

# --- Documentation ---
/docs/                              @instacommerce/principal-engineers
/docs/adr/                          @instacommerce/principal-engineers @instacommerce/platform-team

# --- Java services ---
/services/identity-service/         @instacommerce/identity-team
/services/catalog-service/          @instacommerce/catalog-team
/services/inventory-service/        @instacommerce/inventory-team
/services/order-service/            @instacommerce/order-team
/services/payment-service/          @instacommerce/payments-team
/services/fulfillment-service/      @instacommerce/fulfillment-team
/services/notification-service/     @instacommerce/platform-team
/services/search-service/           @instacommerce/catalog-team
/services/pricing-service/          @instacommerce/catalog-team
/services/cart-service/             @instacommerce/order-team
/services/checkout-orchestrator-service/ @instacommerce/order-team @instacommerce/payments-team
/services/warehouse-service/        @instacommerce/fulfillment-team
/services/rider-fleet-service/      @instacommerce/logistics-team
/services/routing-eta-service/      @instacommerce/logistics-team
/services/wallet-loyalty-service/   @instacommerce/growth-team
/services/fraud-detection-service/  @instacommerce/payments-team @instacommerce/ml-team
/services/audit-trail-service/      @instacommerce/platform-team
/services/config-feature-flag-service/ @instacommerce/platform-team
/services/mobile-bff-service/       @instacommerce/mobile-team
/services/admin-gateway-service/    @instacommerce/platform-team @instacommerce/sre-team

# --- Go services ---
/services/go-shared/                @instacommerce/platform-team
/services/cdc-consumer-service/     @instacommerce/platform-team @instacommerce/data-platform-team
/services/outbox-relay-service/     @instacommerce/platform-team
/services/dispatch-optimizer-service/ @instacommerce/logistics-team
/services/location-ingestion-service/ @instacommerce/logistics-team
/services/payment-webhook-service/  @instacommerce/payments-team
/services/reconciliation-engine/    @instacommerce/payments-team
/services/stream-processor-service/ @instacommerce/data-platform-team

# --- Python AI services ---
/services/ai-orchestrator-service/  @instacommerce/ai-team @instacommerce/ml-team
/services/ai-inference-service/     @instacommerce/ai-team @instacommerce/ml-team
```

### 4.2 Change classes and approver policy

Add the following as a comment block at the top of `CODEOWNERS` and wire into branch-protection:

| Change class | Paths | Required approvers |
|-------------|-------|-------------------|
| Contract change | `contracts/**` | platform-team + 1 principal engineer |
| Security surface | `services/identity-service/**`, `services/admin-gateway-service/**`, `infra/**`, `.github/workflows/**` | platform-team + sre-team |
| Deploy manifest | `deploy/**`, `argocd/**` | platform-team + sre-team |
| ADR | `docs/adr/**` | principal-engineers |
| Data contract | `data-platform/**`, `contracts/src/main/resources/schemas/**` | data-platform-team + platform-team |
| Go shared library | `services/go-shared/**` | platform-team + 2 reviewers (all Go services are affected) |

---

## 5. ADR directory and starter records

### 5.1 Create the ADR directory and index

```
docs/adr/
├── README.md          (index + conventions)
├── ADR-001-checkout-authority.md
├── ADR-002-internal-auth-model.md
├── ADR-003-kafka-topic-naming.md
└── ADR-004-go-helm-name-mapping.md
```

### 5.2 ADR template (minimum viable)

```markdown
# ADR-NNN: <title>

**Status:** proposed | accepted | deprecated | superseded by ADR-NNN  
**Date:** YYYY-MM-DD  
**Deciders:** <names or team>  
**Reviewed by:** <principal engineer names>

## Context
<what situation made this decision necessary>

## Decision
<the decision made>

## Consequences
<what changes, what new constraints, what is now easier or harder>

## Guardrails
<how to detect drift from this decision in CI or review>
```

### 5.3 Immediate ADRs to create (Wave 0)

**ADR-001 — Checkout authority**  
Decision: `checkout-orchestrator-service` is the sole authoritative orchestrator for the checkout saga. `order-service` must not initiate or advance checkout workflow state. Any PR touching `order-service` checkout paths requires `checkout-orchestrator-service` owner approval.

**ADR-002 — Internal auth model**  
Decision: The shared internal service token is temporary and cannot be expanded. Every service must migrate to per-call workload-identity tokens by Wave 2. `admin-gateway-service` is blocked from receiving production traffic until real auth exists.

**ADR-003 — Kafka topic naming**  
Decision: All new topics follow `<domain>.<aggregate>.<event-type>` in snake_case. No new singular/plural ambiguity. CI lint checks new topic names against this pattern.

**ADR-004 — Go module to Helm key mapping**  
Decision: When a Go module's directory name does not match its Helm deploy key, the mapping is declared in `docs/adr/ADR-004-go-helm-name-mapping.md` and reflected in both `ci.yml`'s `go_service_to_helm` map and a comment in `values-dev.yaml` / `values-prod.yaml`. No unmapped service may be deployed.

Current approved mappings:

| Go module directory | Helm key | Rationale |
|--------------------|----------|-----------|
| `cdc-consumer-service` | `cdc-consumer` | shortened for k8s workload name limit |
| `location-ingestion-service` | `location-ingestion` | shortened |
| `payment-webhook-service` | `payment-webhook` | shortened |
| `outbox-relay-service` | `outbox-relay` | shortened |

---

## 6. CI path coverage remediation

### 6.1 Python AI services — add build and test jobs

Add a new `python-build-test` job to `ci.yml` with a path filter covering both Python services:

```yaml
# In detect-changes filters block — add:
ai-orchestrator-service: 'services/ai-orchestrator-service/**'
ai-inference-service: 'services/ai-inference-service/**'

# New job in ci.yml:
python-build-test:
  needs: detect-changes
  if: |
    contains(fromJson(needs.detect-changes.outputs.changed_services), 'ai-orchestrator-service') ||
    contains(fromJson(needs.detect-changes.outputs.changed_services), 'ai-inference-service')
  runs-on: ubuntu-latest
  timeout-minutes: 15
  strategy:
    fail-fast: false
    matrix:
      service: [ai-orchestrator-service, ai-inference-service]
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-python@v5
      with:
        python-version: '3.11'
    - name: Install dependencies
      working-directory: services/${{ matrix.service }}
      run: pip install -r requirements.txt
    - name: Run tests
      working-directory: services/${{ matrix.service }}
      run: pytest -v
    - name: Validate app import
      working-directory: services/${{ matrix.service }}
      run: python -c "from app.main import app"
```

Update the `deploy-dev` job's `needs` array to include `python-build-test`.

### 6.2 Contracts — add validation job

```yaml
# In detect-changes filters block — add:
contracts: 'contracts/**'

# New job:
contracts-validate:
  needs: detect-changes
  if: contains(toJson(needs.detect-changes.outputs), 'contracts')
  runs-on: ubuntu-latest
  timeout-minutes: 15
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        java-version: '21'
        distribution: temurin
        cache: gradle
    - name: Build and validate contracts
      run: ./gradlew :contracts:build
    - name: Check for breaking proto changes
      uses: bufbuild/buf-action@v1
      with:
        input: contracts/src/main/proto
        against: HEAD~1
```

### 6.3 Helm chart — add lint job

```yaml
# In detect-changes filters block — add:
helm: 'deploy/helm/**'

# New job:
helm-lint:
  needs: detect-changes
  if: contains(toJson(needs.detect-changes.outputs), 'helm')
  runs-on: ubuntu-latest
  timeout-minutes: 10
  steps:
    - uses: actions/checkout@v4
    - uses: azure/setup-helm@v4
    - name: Lint chart
      run: helm lint deploy/helm --values deploy/helm/values-dev.yaml
    - name: Template chart (dev)
      run: helm template instacommerce deploy/helm --values deploy/helm/values-dev.yaml > /dev/null
```

### 6.4 Terraform — add validate job

```yaml
# In detect-changes filters block — add:
terraform: 'infra/terraform/**'

# New job:
terraform-validate:
  needs: detect-changes
  if: contains(toJson(needs.detect-changes.outputs), 'terraform')
  runs-on: ubuntu-latest
  timeout-minutes: 10
  steps:
    - uses: actions/checkout@v4
    - uses: hashicorp/setup-terraform@v3
    - name: Terraform init (no backend)
      run: terraform -chdir=infra/terraform init -backend=false
    - name: Terraform validate
      run: terraform -chdir=infra/terraform validate
```

### 6.5 stream-processor-service — resolve orphan status

`stream-processor-service` is built and tested in CI but never deployed. Choose one:

- **Option A (intended):** Add `stream-processor-service` to `values-dev.yaml` and `values-prod.yaml` if it is a real service that should be running. Wire the `go_service_to_helm` map accordingly (`stream-processor-service → stream-processor`).
- **Option B (not yet ready):** Add a comment in `values-dev.yaml` explaining the service is not yet deployed and add a `TODO(stream-processor): add to deploy when ready` comment in `ci.yml`'s Go matrix.
- **Option C (dead code):** If the service is abandoned, remove it from the CI Go matrix and `services/` directory.

The correct option must be decided by the data-platform team owner before Wave 0 closes.

### 6.6 Fix CI action version references

Audit and correct all action version pins. Replace `actions/checkout@v6`, `actions/setup-go@v6` with the correct latest stable major version tags (`@v4` for checkout as of this writing). Add Dependabot automation for action-version updates in `.github/dependabot.yml` under the `github-actions` ecosystem block.

---

## 7. Image registry name split — remediation

The three-registry split (`images/`, `dev-images/`, `prod-images/`) is architecturally reasonable (separate registries per environment) but the CI pipeline currently **builds and pushes to `images/`** while values files reference environment-specific registries. This creates a broken deploy lineage on every merge to main.

### 7.1 Intended target state

| Environment | Pushes to | Values file references |
|------------|-----------|----------------------|
| dev | `asia-south1-docker.pkg.dev/instacommerce/dev-images` | `values-dev.yaml` |
| prod | `asia-south1-docker.pkg.dev/instacommerce/prod-images` | `values-prod.yaml` |

### 7.2 Migration steps

1. In `ci.yml`, replace `REGISTRY: asia-south1-docker.pkg.dev/instacommerce/images` with per-environment targets:
   - For `develop` branch pushes: tag and push to `dev-images/<service>:<sha>`.
   - For `main`/`master` branch pushes: tag and push to `prod-images/<service>:<sha>`, then separately tag `dev-images/<service>:latest` as a promotion gate.
2. Alternatively (simpler): Use one registry but explicit path prefixes: `images/dev/<service>:<sha>` and `images/prod/<service>:<sha>`.
3. Update `values.yaml` (base chart), `values-dev.yaml`, and `values-prod.yaml` `global.image.registry` to match the chosen convention.
4. Add a CI step that validates that the image exists in the target registry before writing the tag into the values file.

### 7.3 Rollback

Keep the old registry path alive for 30 days after migration. Add a chart-level image pull error alert so any broken tag is caught within minutes of ArgoCD sync.

---

## 8. Docs governance model

### 8.1 Document status markers

Every document in `docs/` must carry a front-matter block:

```markdown
**Status:** authoritative | draft | superseded  
**Superseded by:** <path> (if applicable)  
**Maintained by:** <team>  
**Last reviewed:** YYYY-MM-DD
```

Documents without a status block are treated as `draft` by default.

### 8.2 Document taxonomy

| Category | Path convention | Naming convention |
|----------|----------------|-------------------|
| Architecture decisions | `docs/adr/ADR-NNN-<slug>.md` | ADR-NNN prefix |
| HLD and system context | `docs/architecture/*.md` | ALLCAPS or kebab-case |
| Per-service reviews | `docs/reviews/<service>-review.md` | `<service-name>-review.md` |
| Principal/program reviews | `docs/reviews/PRINCIPAL-*.md` | ALLCAPS with date suffix |
| Iteration-scoped platform guides | `docs/reviews/iter<N>/platform/*.md` | kebab-case |
| Iteration-scoped service guides | `docs/reviews/iter<N>/services/*.md` | kebab-case |
| Benchmarks | `docs/reviews/iter<N>/benchmarks/*.md` | kebab-case |
| Diagrams | `docs/reviews/iter<N>/diagrams/*.md` | kebab-case |
| Appendices | `docs/reviews/iter<N>/appendices/*.md` | kebab-case |

### 8.3 docs/README.md — required additions

Update `docs/README.md` to:

1. Add an **Iteration 3 platform and service guides** section that indexes every file under `docs/reviews/iter3/`.
2. Add a **Status** column to every table row using the defined markers.
3. Add a **superseded** annotation for documents in `docs/reviews/` that are fully replaced by iter3 equivalents.
4. Add a note at the top of the file: *"This file is the canonical doc index. If a document is not listed here, it is not authoritative."*

### 8.4 Supersession policy

When a new authoritative document replaces an old one:
1. Add `**Status:** superseded` + `**Superseded by:** <path>` to the old document's header.
2. Update `docs/README.md` to move the old entry to a *Superseded* section at the bottom.
3. Do not delete old documents — they are historical record.

### 8.5 Docs review cadence

| Cadence | Action |
|---------|--------|
| Every PR that changes a service | Author must check whether `docs/reviews/<service>-review.md` needs a `Last reviewed` update |
| Monthly | `docs/README.md` index audit — remove stale entries, promote drafts |
| Quarterly | CODEOWNERS review — team structure may have changed |
| Every architecture wave | Create a new `docs/reviews/iter<N>/` tree before shipping wave |

---

## 9. Service naming consistency — enforcement model

### 9.1 The naming problem

The repo has three naming layers that must stay consistent:

1. **Source directory and Go module name** (`services/<dir>/go.mod` `module` field)
2. **Helm deploy key** (the key under `services:` in `values-dev.yaml` / `values-prod.yaml`)
3. **CI deploy-dev mapping** (the `go_service_to_helm` associative array)

Any mismatch causes the `deploy-dev` job to either silently skip the service or deploy to the wrong workload name.

### 9.2 Canonical naming registry

Create `docs/service-registry.md` as the single file that lists every service with all three names:

| Source directory | Go/Java module key | Helm deploy key | Stack | CI job |
|-----------------|-------------------|-----------------|-------|--------|
| `identity-service` | `identity-service` | `identity-service` | Java | `build-test` |
| `catalog-service` | `catalog-service` | `catalog-service` | Java | `build-test` |
| `inventory-service` | `inventory-service` | `inventory-service` | Java | `build-test` |
| `order-service` | `order-service` | `order-service` | Java | `build-test` |
| `payment-service` | `payment-service` | `payment-service` | Java | `build-test` |
| `fulfillment-service` | `fulfillment-service` | `fulfillment-service` | Java | `build-test` |
| `notification-service` | `notification-service` | `notification-service` | Java | `build-test` |
| `search-service` | `search-service` | `search-service` | Java | `build-test` |
| `pricing-service` | `pricing-service` | `pricing-service` | Java | `build-test` |
| `cart-service` | `cart-service` | `cart-service` | Java | `build-test` |
| `checkout-orchestrator-service` | `checkout-orchestrator-service` | `checkout-orchestrator-service` | Java | `build-test` |
| `warehouse-service` | `warehouse-service` | `warehouse-service` | Java | `build-test` |
| `rider-fleet-service` | `rider-fleet-service` | `rider-fleet-service` | Java | `build-test` |
| `routing-eta-service` | `routing-eta-service` | `routing-eta-service` | Java | `build-test` |
| `wallet-loyalty-service` | `wallet-loyalty-service` | `wallet-loyalty-service` | Java | `build-test` |
| `audit-trail-service` | `audit-trail-service` | `audit-trail-service` | Java | `build-test` |
| `fraud-detection-service` | `fraud-detection-service` | `fraud-detection-service` | Java | `build-test` |
| `config-feature-flag-service` | `config-feature-flag-service` | `config-feature-flag-service` | Java | `build-test` |
| `mobile-bff-service` | `mobile-bff-service` | `mobile-bff-service` | Java | `build-test` |
| `admin-gateway-service` | `admin-gateway-service` | `admin-gateway-service` | Java | `build-test` |
| `cdc-consumer-service` | `cdc-consumer-service` | `cdc-consumer` | Go | `go-build-test` |
| `location-ingestion-service` | `location-ingestion-service` | `location-ingestion` | Go | `go-build-test` |
| `payment-webhook-service` | `payment-webhook-service` | `payment-webhook` | Go | `go-build-test` |
| `outbox-relay-service` | `outbox-relay-service` | `outbox-relay` | Go | `go-build-test` |
| `dispatch-optimizer-service` | `dispatch-optimizer-service` | `dispatch-optimizer-service` | Go | `go-build-test` |
| `reconciliation-engine` | `reconciliation-engine` | `reconciliation-engine` | Go | `go-build-test` |
| `stream-processor-service` | `stream-processor-service` | *(unresolved — see §6.5)* | Go | `go-build-test` |
| `ai-orchestrator-service` | `ai-orchestrator-service` | `ai-orchestrator-service` | Python | `python-build-test` |
| `ai-inference-service` | `ai-inference-service` | `ai-inference-service` | Python | `python-build-test` |

### 9.3 Validation script (drift detection)

Add `scripts/check-service-registry.sh`:

```bash
#!/usr/bin/env bash
# Validates that every service directory has:
# 1. An entry in ci.yml path filters
# 2. An entry in values-dev.yaml (or is explicitly excluded)
# 3. An entry in CODEOWNERS
# Exits 1 if any gap is found.
set -euo pipefail
ERRORS=0

for dir in services/*/; do
  svc=$(basename "$dir")
  [[ "$svc" == "build" || "$svc" == "go-shared" ]] && continue

  if ! grep -q "$svc" .github/workflows/ci.yml; then
    echo "MISSING CI coverage: $svc"
    ERRORS=$((ERRORS+1))
  fi

  if ! grep -q "$svc" .github/CODEOWNERS; then
    echo "MISSING CODEOWNERS entry: $svc"
    ERRORS=$((ERRORS+1))
  fi
done

if [[ $ERRORS -gt 0 ]]; then
  echo "Found $ERRORS gap(s). Fix before merging."
  exit 1
fi
echo "All services have CI and CODEOWNERS coverage."
```

Wire this script into CI as a separate `repo-health` job that runs on every PR.

---

## 10. Artifact discoverability

### 10.1 Image registry discovery

Images built by CI should be discoverable by service name, environment, and commit SHA. The current setup is partially broken (§7). After the registry cleanup:

- Every image is tagged with both `<sha>` and `<branch>-latest` (e.g., `main-latest`) to allow human browsing.
- Add an `images.md` file under `deploy/` that lists every service, its registry path template, and the environment it targets.
- Trivy scans are recorded in CI artifacts (SARIF output) so vulnerability history is queryable.

### 10.2 Helm chart discoverability

`deploy/helm/README.md` already exists. Ensure it:

1. Lists every service key in `values.yaml` with a one-line description.
2. Explains the three-values-file convention (`values.yaml` → base, `values-dev.yaml` → dev override, `values-prod.yaml` → prod override).
3. Explains the Go-module-to-Helm-key naming gap and links to `docs/adr/ADR-004-go-helm-name-mapping.md`.
4. Lists the ArgoCD app-of-apps entry point (`argocd/app-of-apps.yaml`).

### 10.3 ArgoCD app discoverability

`argocd/app-of-apps.yaml` is the GitOps entry point but is not linked from `docs/README.md`. Add it as a first-class entry in the docs index under *Deployment and GitOps*.

---

## 11. Migration sequence

Execute in strict order. Each step has an entry gate, action, and exit gate.

### Step 1 — Establish canonical service registry (Day 1, ~2h)

**Entry gate:** None.  
**Actions:**
1. Create `docs/service-registry.md` using the table in §9.2.
2. Resolve the `stream-processor-service` orphan status (§6.5) — add a note or decision in the registry.
3. Confirm the four Go-to-Helm name mappings match current `values-dev.yaml` exactly.

**Exit gate:** Registry file committed and reviewed by platform-team + one principal engineer.

---

### Step 2 — Fix image registry split (Day 1–2, ~3h)

**Entry gate:** Step 1 done.  
**Actions:**
1. Decide on single-registry-with-path-prefixes vs multi-registry (§7.2).
2. Update `ci.yml` `REGISTRY` env var and push steps.
3. Update `deploy/helm/values.yaml`, `values-dev.yaml`, `values-prod.yaml` `global.image.registry`.
4. Add image-existence validation step in `deploy-dev`.

**Exit gate:** A test build on a feature branch pushes to the correct registry and `values-dev.yaml` updated tag resolves to an existing image.

---

### Step 3 — Create CODEOWNERS (Day 2, ~1h)

**Entry gate:** Steps 1–2 done, team GitHub handles confirmed.  
**Actions:**
1. Create `.github/CODEOWNERS` using §4.1.
2. Enable "Require review from code owners" in repository branch protection settings for `main`, `master`, `develop`.
3. Add CODEOWNERS to the `docs/` directory per §4.2.

**Exit gate:** A PR touching `contracts/` shows required reviewers from `@instacommerce/platform-team`.

---

### Step 4 — Add CI path coverage for Python, contracts, Helm, Terraform (Day 2–3, ~4h)

**Entry gate:** Step 3 done.  
**Actions:**
1. Add `python-build-test` job per §6.1.
2. Add `contracts-validate` job per §6.2.
3. Add `helm-lint` job per §6.3.
4. Add `terraform-validate` job per §6.4.
5. Fix all action version pins (§6.6).
6. Update `deploy-dev` `needs` array to include all new jobs.

**Exit gate:** A PR modifying each covered path triggers the corresponding new CI job. All new jobs pass on the unmodified codebase.

---

### Step 5 — Create ADR directory and bootstrap ADRs (Day 3, ~2h)

**Entry gate:** Steps 1–4 done.  
**Actions:**
1. Create `docs/adr/README.md` with the template from §5.2.
2. Create `docs/adr/ADR-001` through `ADR-004` per §5.3.
3. Add `docs/adr/**` CODEOWNERS rule pointing to `@instacommerce/principal-engineers`.

**Exit gate:** All four ADRs reviewed and marked `accepted` by at least one principal engineer.

---

### Step 6 — Update docs/README.md and add status markers (Day 3–4, ~2h)

**Entry gate:** Steps 1–5 done.  
**Actions:**
1. Add status front-matter block to every file in `docs/reviews/iter3/` and the top 10 most-referenced docs.
2. Add iter3 subdirectory index to `docs/README.md`.
3. Mark superseded reviews.
4. Add the note: *"This file is the canonical doc index. If a document is not listed here, it is not authoritative."*

**Exit gate:** `docs/README.md` index audit passes — no broken links, all iter3 files referenced.

---

### Step 7 — Add repo-health validation script (Day 4, ~1h)

**Entry gate:** Steps 1–6 done.  
**Actions:**
1. Create `scripts/check-service-registry.sh` per §9.3.
2. Add a `repo-health` job in `ci.yml` that runs this script on every PR.

**Exit gate:** Script passes on current repo state. A deliberate test (adding a new service directory without CI/CODEOWNERS) causes the script to exit 1.

---

### Step 8 — Resolve stream-processor-service orphan (Day 4–5, ~1h)

**Entry gate:** Data-platform team decision captured in `docs/service-registry.md` (Step 1).  
**Actions:** Execute Option A, B, or C from §6.5.  
**Exit gate:** CI and Helm values are consistent. No service is built but not deployed, or deployed but not built, without an explicit documented reason.

---

## 12. Guardrails against future drift

These controls prevent the problems in §3 from recurring after Wave 0.

### 12.1 Automated drift detection (ongoing)

| Guardrail | Mechanism | Triggers on |
|-----------|-----------|-------------|
| New service directory without CI coverage | `scripts/check-service-registry.sh` in `repo-health` CI job | Every PR |
| New service directory without CODEOWNERS | Same script | Every PR |
| Helm values key without CI path filter | Same script (extend to parse values-dev.yaml) | Every PR |
| Proto breaking changes | `buf breaking` in `contracts-validate` job | Every PR touching `contracts/**` |
| Helm chart templating failure | `helm lint` + `helm template` in `helm-lint` job | Every PR touching `deploy/helm/**` |
| Python import breakage | `python -c "from app.main import app"` in `python-build-test` | Every PR touching Python services |
| Action version drift | Dependabot `github-actions` ecosystem in `.github/dependabot.yml` | Weekly |

### 12.2 Human process guardrails

| Guardrail | When | Who |
|-----------|------|-----|
| CODEOWNERS review | Quarterly or when team structure changes | Platform-team |
| ADR review — mark deprecated/superseded | Every architecture wave | Principal engineers |
| docs/README.md index audit | Monthly | Assigned doc owner (platform-team) |
| Service registry reconciliation | Every time a new service is added or renamed | PR author + platform-team reviewer |
| Wave entry gate checklist | Before each wave begins | Platform DRI + principal engineers |

### 12.3 The "new service" contract

Any PR that adds a new service directory must, in the same PR:

1. Add the service to `settings.gradle.kts` (Java) or create `go.mod` / `requirements.txt` (Go / Python).
2. Add a path filter in `ci.yml` `detect-changes`.
3. Add the service to the relevant build matrix (`build-test`, `go-build-test`, or `python-build-test`).
4. Add a Helm key in `values-dev.yaml` with `tag: dev`, and in `values-prod.yaml` with appropriate replicas.
5. Add a Go-to-Helm mapping in `ci.yml` deploy-dev if the name differs.
6. Add a CODEOWNERS entry.
7. Add a row to `docs/service-registry.md`.

The `repo-health` CI job validates items 2, 3, and 6 automatically. Items 1, 4, 5, 7 are reviewed by CODEOWNERS.

### 12.4 Branch protection requirements (set in GitHub repo settings)

```
Branch: main, master, develop
Required checks:
  - repo-health
  - security-scan
  - contracts-validate (if contracts/** changed)
  - helm-lint (if deploy/helm/** changed)
Required reviewers:
  - Code owners (CODEOWNERS file)
Dismiss stale reviews: true
Require up-to-date branch: true
```

---

## 13. Metrics and Wave 0 exit gate

Track these metrics until all are at target before Wave 1 begins.

| Metric | Current | Target |
|--------|---------|--------|
| % of service directories with CI path filter | ~83% (29/35 — missing Python, stream-processor excluded intentionally) | 100% (or 100% with explicit documented exceptions) |
| % of service directories with CODEOWNERS entry | 0% | 100% |
| # of ADRs in `docs/adr/` with status `accepted` | 0 | ≥ 4 (ADR-001 through ADR-004) |
| # of CI jobs with broken action version pins | ≥ 3 | 0 |
| Image registry consistency (CI push = Helm pull) | Broken | All environments consistent |
| # of docs files with status front-matter | 0 | ≥ all iter3 docs + top 10 referenced docs |
| `stream-processor-service` orphan resolved | No | Yes |
| `repo-health` CI job exists and passes | No | Yes |

Wave 0 is complete when all targets are met and a principal engineer signs off on the exit gate checklist.

---

## 14. Rollback and safety notes

Wave 0 changes are governance-layer changes, not runtime changes. Rollback is safe and fast:

- **CODEOWNERS:** Revert or amend the file. Required-reviewers enforcement is advisory until branch protection is enabled.
- **CI path filters / new jobs:** Revert the `ci.yml` change. Old CI behavior is immediately restored on the next PR.
- **Docs changes:** Revert markdown edits. No runtime impact.
- **Registry fix:** Maintain old registry path live for 30 days. ArgoCD can be pointed back to old registry in minutes.
- **CODEOWNERS required reviewers blocking incident response:** Temporarily exempt incident PRs from required-reviewer rule using the GitHub emergency bypass mechanism. Restore after incident.

**Do not roll back** by deleting `docs/adr/` entries — use `status: deprecated` instead to preserve the decision record.

---

## 15. Related documents

| Document | Relationship |
|----------|-------------|
| `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-PROGRAM-2026-03-06.md` §5 | Wave 0 source — this guide implements that program |
| `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-PLATFORM-WISE-2026-03-06.md` §4 | Platform guide §4 — this guide expands it into concrete steps |
| `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md` §4.2 | Truth drift diagnosis |
| `docs/adr/ADR-001-checkout-authority.md` | Downstream ADR created by this guide |
| `docs/adr/ADR-004-go-helm-name-mapping.md` | Downstream ADR created by this guide |
| `docs/service-registry.md` | Registry file created by migration Step 1 |
| `scripts/check-service-registry.sh` | Drift-detection script created by migration Step 7 |
| `.github/CODEOWNERS` | Ownership file created by migration Step 3 |
