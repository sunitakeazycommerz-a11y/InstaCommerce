---
name: cd-skills
description: Continuous Delivery, GitOps deployment with Helm values overlays, ArgoCD sync waves, and canary rollout/rollback strategies.
---

# CD Skills: GitOps Delivery, Helm & ArgoCD

Use this skill when modifying deployment configurations, authoring Helm templates, managing ArgoCD applications, or executing canary rollouts and rollbacks across staging and production clusters.

---

## 1. GitOps Architecture

```
  Git Commit to `main`
       │
       ▼
  CI Workflow (`.github/workflows/ci.yml`)
  • Builds multi-arch Docker image
  • Pushes to Artifact Registry: asia-south1-docker.pkg.dev/instacommerce/images
  • Updates image tag in `deploy/helm/values-*.yaml`
       │
       ▼
  ArgoCD Reconciliation (`argocd/`)
  • Detects git commit drift
  • Applies manifests in Sync Waves
       │
  ┌────┴───────────────────────────┐
  ▼                                ▼
Dev / Staging Cluster          Canary Deployment (Austin / Seattle / SF)
(Immediate sync)               (5% -> 25% -> 100% traffic shift via Istio)
```

---

## 2. Helm Configuration Hierarchy (`deploy/helm`)

Never duplicate common deployment boilerplate across services. Use the umbrella chart with values overlays:

1. **`values.yaml`**: Default resource requests, liveness/readiness probes, and port definitions for all 30 microservices.
2. **`values-dev.yaml`**: Development environment overrides (reduced replicas, debug logging, local database endpoints).
3. **`values-prod.yaml`**: Production baseline (high replica count, strict PDBs, HPA enabled).
4. **`values-canary-*.yaml`**: Regional canary overlays (`sf`, `seattle`, `austin`) pinning canary image tags and weighted Istio traffic splits.

---

## 3. ArgoCD Sync Waves

To prevent race conditions during deployments (e.g. pods starting before database migrations finish), annotate Kubernetes manifests with sync waves:

| Wave | Component Type | Examples |
|---|---|---|
| **Wave 0** | Prerequisites | Namespaces, ServiceAccounts, RBAC, SealedSecrets |
| **Wave 1** | Schema & Storage | PostgreSQL schema jobs, Flyway migration jobs |
| **Wave 2** | Backend Domain Core | Stateful Java services, Kafka consumers, outbox relays |
| **Wave 3** | Ingress & Routing | BFFs, Admin Gateway, Istio `VirtualService`, routes |

```yaml
metadata:
  annotations:
    argocd.argoproj.io/sync-wave: "1"
    argocd.argoproj.io/hook: Sync
    argocd.argoproj.io/hook-delete-policy: HookSucceeded
```

---

## 4. Canary Verification & Rollback Playbook

When rolling out changes to canary dark stores (e.g., San Francisco hub):

```bash
# 1. Execute canary deploy script
./scripts/phase-2-sf-canary-deploy.sh

# 2. Run automated SLO verification gates
./scripts/phase-2-sf-verify.sh

# 3. If error rates exceed 0.5% or p99 latency spikes > 500ms, trigger instant rollback:
./scripts/phase-2-sf-rollback.sh
```

---

## 5. Helm Values Update Rule

When adding or updating a service in `deploy/helm/values.yaml`:
- Verify the service name matches the deploy key mapping in `.github/workflows/ci.yml`.
- Ensure readiness and liveness probes target the correct port and path:
  - Spring Boot Java: `/actuator/health/readiness` on `${SERVER_PORT}`
  - Go Services: `/health/ready` on `${HEALTH_ADDR}` or port `:8080`
  - Python Services: `/health` on port `:8100` / `:8000`

