# Instacommerce Infrastructure Deep Review

**Reviewer:** Senior SRE/DevOps Architect  
**Date:** 2025-07-15  
**Scope:** Helm charts, CI/CD, Terraform/GCP, ArgoCD, Monitoring, Docker Compose — all 18 services  
**Scale Context:** 20M+ users, 18 microservices, GKE (asia-south1)

---

## Executive Summary

The infrastructure has a **solid foundation** — Istio service mesh, VPC-native GKE, Cloud SQL with PITR, PodDisruptionBudgets, security contexts, and GitOps via ArgoCD are all in place. However, there are **31 critical findings** and **22 high-priority gaps** that would cause production incidents at scale. The most urgent: single Cloud SQL instance for all services (single point of failure), no staging environment, 11 of 18 services missing from CI/CD, no canary deployments, and no Redis connection config despite Memorystore being provisioned.

### Severity Breakdown

| Severity | Count | Key Areas |
|----------|-------|-----------|
| 🔴 CRITICAL | 12 | Single DB instance, missing CI services, no staging, no rollback |
| 🟠 HIGH | 22 | Resource sizing, HPA gaps, security scanning, monitoring |
| 🟡 MEDIUM | 14 | Observability, cost management, DR strategy |
| 🔵 LOW | 6 | Documentation, labeling, minor config |

---

## 1. Helm Charts — Detailed Analysis

### 1.1 Deployment Strategy

**Template:** `deploy/helm/templates/deployment.yaml`

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

**Assessment: 🟡 MEDIUM — Needs tuning per service tier**

| Finding | Detail |
|---------|--------|
| ✅ Zero-downtime baseline | `maxUnavailable: 0` ensures no pod is killed before replacement is ready |
| 🟠 Slow rollouts for high-replica services | `maxSurge: 1` means cart-service (maxReplicas: 20) takes 20 update cycles to fully roll out |
| 🟠 Uniform strategy for all services | Payment-service (financial) and config-feature-flag-service (low-risk) use identical strategies |
| ❌ No canary/blue-green support | No Istio traffic splitting in VirtualService for progressive rollouts |

**Recommendations:**
1. **Hot-path services** (cart, search, pricing, routing-eta): Set `maxSurge: 25%` for faster rollouts
2. **Critical financial services** (payment, order, wallet-loyalty): Keep `maxSurge: 1` but add Istio canary with traffic splitting (10% → 25% → 50% → 100%)
3. **Low-risk services** (audit-trail, config-feature-flag): Use `maxSurge: 50%` for rapid deployment
4. Add Flagger or Argo Rollouts for automated canary analysis

### 1.2 Resource Requests & Limits

**Assessment: 🟠 HIGH — Multiple services under-provisioned for 20M users**

| Service | CPU Req/Limit | Mem Req/Limit | Verdict |
|---------|--------------|---------------|---------|
| identity-service | 250m/500m | 512Mi/1Gi | 🟠 Under-provisioned for auth at scale (JWT generation is CPU-intensive) |
| catalog-service | 250m/500m | 512Mi/1Gi | 🟠 Read-heavy service needs more memory for caching |
| inventory-service | 500m/1000m | 768Mi/1536Mi | ✅ Adequate |
| order-service | 500m/1000m | 768Mi/1536Mi | ✅ Adequate |
| payment-service | 250m/500m | 512Mi/1Gi | 🔴 CRITICAL — Payment at scale needs 500m/1000m minimum; crypto operations are CPU-heavy |
| fulfillment-service | 250m/500m | 512Mi/1Gi | 🟠 Orchestrates multiple downstream calls; needs more |
| notification-service | 200m/400m | 384Mi/768Mi | 🟠 Push notification bursts (order updates to 20M users) will OOM |
| search-service | 500m/1000m | 768Mi/1536Mi | ✅ Good, but needs more memory if using in-process indices |
| pricing-service | 500m/1000m | 512Mi/1Gi | 🟡 Memory low if caching pricing rules |
| cart-service | 500m/1000m | 768Mi/1536Mi | ✅ Adequate |
| checkout-orchestrator-service | 500m/1000m | 768Mi/1536Mi | ✅ Good — Temporal client is lightweight |
| warehouse-service | 250m/500m | 512Mi/1Gi | ✅ Low-traffic service |
| rider-fleet-service | 500m/1000m | 768Mi/1536Mi | ✅ Good for geospatial operations |
| routing-eta-service | 500m/1000m | 768Mi/1536Mi | 🟡 May need GPU/more CPU for route calculation at scale |
| wallet-loyalty-service | 250m/500m | 512Mi/1Gi | 🟡 Financial service — should match payment-service tier |
| audit-trail-service | 250m/500m | 512Mi/1Gi | ✅ Write-only, adequate |
| fraud-detection-service | 500m/1000m | 512Mi/1Gi | 🟠 ML inference needs more memory (1Gi/2Gi minimum) |
| config-feature-flag-service | 250m/384Mi | 500m/768Mi | ✅ Low traffic |

**Critical Issue — CPU Limit:Request Ratio:**
All services have a 2:1 limit:request ratio. This means:
- During burst traffic, pods compete for CPU, causing throttling
- The `CFS` quota can cause tail latency spikes

**Recommendation:** For latency-sensitive services (payment, order, checkout, search), consider removing CPU limits or widening to 4:1 ratio. Google's own guidance for GKE recommends no CPU limits for latency-sensitive workloads.

**Resource Quota Analysis:**
```yaml
resourceQuota:
  cpu: "24"         # Total CPU requests across all 18 services
  memory: "48Gi"    # Total memory requests
```

At minimum replicas (2 each), the 18 services consume:
- CPU requests: ~6.45 cores (sum of all minReplicas × CPU request)
- Memory requests: ~11.26 Gi

This leaves headroom for HPA scaling, but at max replicas the total would be ~42 cores — **exceeding the 24-core quota**. The quota will block HPA scaling.

**🔴 CRITICAL:** Resource quota of 24 CPU is too low. At max HPA scale, services need ~42 cores. Increase to at least `48` or remove in favor of namespace-level LimitRange.

### 1.3 HPA Configuration

**Template:** `deploy/helm/templates/hpa.yaml` — Uses `autoscaling/v2` ✅

| Service | Min/Max | CPU Target | Assessment |
|---------|---------|------------|------------|
| identity-service | 2/5 | 70% | 🟠 maxReplicas too low for 20M users auth |
| catalog-service | 2/8 | 70% | ✅ |
| inventory-service | 2/6 | 70% | ✅ |
| order-service | 2/6 | 70% | 🟠 Peak ordering (flash sales) needs max 10+ |
| payment-service | 2/5 | 70% | 🟠 Must scale with order-service; max should match |
| fulfillment-service | 2/5 | 70% | ✅ |
| notification-service | 2/8 | 70% | ✅ |
| search-service | 2/20 | 60% | ✅ Good — lower target for latency-sensitive |
| pricing-service | 2/10 | 65% | ✅ |
| cart-service | 3/20 | 60% | ✅ Highest min — appropriate for hot-path |
| checkout-orchestrator | 2/10 | 70% | ✅ |
| warehouse-service | 2/4 | 70% | ✅ |
| rider-fleet-service | 2/8 | 70% | ✅ |
| routing-eta-service | 2/10 | 65% | ✅ |
| wallet-loyalty-service | 2/6 | 70% | ✅ |
| audit-trail-service | 2/6 | 70% | ✅ |
| fraud-detection-service | 2/8 | 65% | ✅ |
| config-feature-flag-service | 2/6 | 65% | 🟡 Over-provisioned for a config service; max 3 sufficient |

**Findings:**

| # | Severity | Finding |
|---|----------|---------|
| 1 | ✅ | Memory-based scaling included with 80% default target — good |
| 2 | 🟠 | No custom metrics (Kafka consumer lag, queue depth, active connections) |
| 3 | 🟠 | No `behavior` block — scale-down could be too aggressive during traffic spikes |
| 4 | 🟡 | notification-service `replicas: 1` contradicts `hpa.minReplicas: 2` — HPA overrides but initial deploy starts at 1 |

**Recommendations:**
1. Add `behavior` block to prevent flapping:
   ```yaml
   behavior:
     scaleDown:
       stabilizationWindowSeconds: 300
       policies:
         - type: Percent
           value: 10
           periodSeconds: 60
     scaleUp:
       stabilizationWindowSeconds: 0
       policies:
         - type: Percent
           value: 100
           periodSeconds: 15
   ```
2. Add custom metrics for Kafka consumer lag (order-service, notification-service, inventory-service)
3. Set `minReplicas: 3` for all tier-1 services (identity, order, payment, cart, search, checkout)

### 1.4 Pod Anti-Affinity

**Assessment: ✅ GOOD — Well-structured with both zone and node spreading**

```yaml
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100  # Zone-level spreading (highest priority)
        topologyKey: topology.kubernetes.io/zone
      - weight: 50   # Node-level spreading (secondary)
        topologyKey: kubernetes.io/hostname
```

| Finding | Detail |
|---------|--------|
| ✅ Zone spreading prioritized | `weight: 100` on zone ensures multi-AZ resilience |
| ✅ Node spreading secondary | `weight: 50` distributes across nodes within same zone |
| 🟡 `preferred` not `required` | Under resource pressure, scheduler may co-locate pods. For payment-service and order-service, consider `requiredDuringSchedulingIgnoredDuringExecution` |
| ❌ No topology spread constraints | `topologySpreadConstraints` provides better guarantees than anti-affinity |

**Recommendation:** Add `topologySpreadConstraints` for tier-1 services:
```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: DoNotSchedule
    labelSelector:
      matchLabels:
        app: {{ $name }}
```

### 1.5 Security Context

**Assessment: ✅ EXCELLENT — Best-practice security posture**

```yaml
# Pod-level
securityContext:
  runAsNonRoot: true
  runAsUser: 1001
  fsGroup: 1001
  seccompProfile:
    type: RuntimeDefault

# Container-level
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop:
      - ALL
```

| Check | Status |
|-------|--------|
| runAsNonRoot | ✅ |
| Non-root UID (1001) | ✅ |
| Drop ALL capabilities | ✅ |
| readOnlyRootFilesystem | ✅ |
| allowPrivilegeEscalation: false | ✅ |
| seccompProfile: RuntimeDefault | ✅ |
| /tmp emptyDir mount | ✅ Smart — allows temp files without writable root |

**Minor gaps:**
- 🔵 No `runAsGroup` specified — add `runAsGroup: 1001` for completeness
- 🔵 No `AppArmor` or `SELinux` profile annotations
- 🟡 ServiceAccounts created but no `automountServiceAccountToken: false` on pods that don't need it

### 1.6 Health Probes

**Assessment: ✅ GOOD — All three probe types present**

```yaml
readinessProbe:
  httpGet: /actuator/health/readiness
  initialDelaySeconds: 15
  periodSeconds: 10

livenessProbe:
  httpGet: /actuator/health/liveness
  initialDelaySeconds: 30
  periodSeconds: 15

startupProbe:
  httpGet: /actuator/health/liveness
  initialDelaySeconds: 10
  periodSeconds: 5
  failureThreshold: 20    # = 10s + (20 × 5s) = 110s max startup time
```

| Finding | Detail |
|---------|--------|
| ✅ Startup probe prevents premature liveness kills | 110s max startup is good for Spring Boot apps |
| 🟠 Identical probes for all services | search-service on port 8086 uses same probe paths — verify actuator is configured |
| 🟡 No `timeoutSeconds` configured | Default is 1s, which may be too aggressive under load |
| 🟡 No gRPC probes | If any service uses gRPC health checking, HTTP probes won't work |
| ❌ No per-service probe customization | checkout-orchestrator (Temporal workflows) may need longer startup |

**Recommendations:**
1. Add `timeoutSeconds: 3` for all probes
2. Allow per-service probe override in values.yaml
3. For search-service, verify actuator port matches container port (8086)

### 1.7 PodDisruptionBudget

**Assessment: ✅ GOOD — PDB enabled for all services**

```yaml
pdb:
  enabled: true
  maxUnavailable: 1
```

| Finding | Detail |
|---------|--------|
| ✅ PDB for all 18 services | Good — prevents cluster upgrades from killing all pods |
| 🟡 `maxUnavailable: 1` is uniform | For services with `minReplicas: 2`, this means 50% can be unavailable |
| 🟡 Should use `minAvailable` for critical services | payment-service, order-service: `minAvailable: 2` is safer |

**Recommendation:** Use percentage-based for high-replica services:
- Tier-1 (payment, order, cart, search): `minAvailable: "50%"`
- Tier-2 (all others): `maxUnavailable: 1` (current — fine)

### 1.8 Service Mesh (Istio)

**Assessment: 🟠 HIGH — Good foundation but incomplete coverage**

**What's configured:**

| Component | Status | Detail |
|-----------|--------|--------|
| Gateway | ✅ | HTTPS/443 with TLS, single host `api.instacommerce.dev` |
| VirtualService | ✅ | All 18 services routed via path prefix |
| DestinationRule | ✅ | Circuit breaking (5xx errors), connection pooling, outlier detection |
| PeerAuthentication | ✅ | STRICT mTLS — excellent |
| RequestAuthentication | 🟠 | Only payment-service and inventory-service have JWT validation |
| AuthorizationPolicy | 🟠 | Only payment-service and inventory-service have policies |
| EnvoyFilter (security headers) | ✅ | HSTS, X-Frame-Options, CSP, X-Content-Type-Options |

**Critical Gaps:**

| # | Severity | Gap |
|---|----------|-----|
| 1 | 🔴 | **14 services have NO RequestAuthentication** — any pod in the mesh can call them |
| 2 | 🔴 | **14 services have NO AuthorizationPolicy** — no principal-based access control |
| 3 | 🟠 | **No rate limiting** — no EnvoyFilter or Istio ratelimit for API gateway |
| 4 | 🟠 | **No retry policies** in VirtualService — relies solely on DestinationRule retries |
| 5 | 🟠 | **No timeout configuration** in VirtualService routes |
| 6 | 🟠 | **VirtualService hard-codes port 8080** — search-service (8086), pricing-service (8087), etc. will fail |
| 7 | 🟡 | **No traffic mirroring** capability for shadow testing |
| 8 | 🟡 | **No fault injection** rules for chaos testing |

**🔴 CRITICAL — VirtualService Port Mismatch:**
```yaml
# virtual-service.yaml routes ALL services to port 8080
route:
  - destination:
      host: {{ .service }}
      port:
        number: 8080    # WRONG for 10 services!
```

Services on non-8080 ports: search (8086), pricing (8087), cart (8088), checkout (8089), warehouse (8090), rider-fleet (8091), routing-eta (8092), wallet-loyalty (8093), audit-trail (8094), fraud-detection (8095), config-feature-flag (8096).

**Fix:** Use `$svc.port` from values or define port per route.

**Recommendation:** Add AuthorizationPolicy for ALL services. Example for order-service:
```yaml
- name: order-service-authz
  selector:
    app: order-service
  principals:
    - cluster.local/ns/{{ .Release.Namespace }}/sa/checkout-orchestrator-service
    - cluster.local/ns/{{ .Release.Namespace }}/sa/fulfillment-service
    - cluster.local/ns/{{ .Release.Namespace }}/sa/payment-service
```

---

## 2. CI/CD Pipeline

### 2.1 Pipeline Stages

**File:** `.github/workflows/ci.yml`

| Stage | Present | Assessment |
|-------|---------|------------|
| Change detection | ✅ | `dorny/paths-filter` for monorepo — smart |
| Security scan (secrets) | ✅ | `gitleaks` for secret detection |
| Security scan (CVE) | ✅ | `trivy` filesystem scan (CRITICAL, HIGH) |
| Build | ✅ | Gradle bootJar |
| Unit test | ✅ | `gradle test` |
| Docker build | ✅ | Only on `main` branch |
| Docker push | ✅ | Artifact Registry with Workload Identity |
| Deploy (dev) | ✅ | GitOps — updates `values-dev.yaml` tags |
| Integration test | ❌ | **Missing** |
| Lint | ❌ | **Missing** — no `detekt`, `checkstyle`, or `spotbugs` |
| Contract test | ❌ | **Missing** — despite `contracts/` directory existing |
| Deploy (staging) | ❌ | **Missing** — no staging environment |
| Deploy (prod) | ❌ | **Missing** — no prod deployment workflow |
| Helm lint/template | ❌ | **Missing** — Helm chart changes not validated |
| Terraform plan | ❌ | **Missing** — infra changes not CI-validated |

### 2.2 Security Scanning

| Scanner Type | Tool | Status |
|-------------|------|--------|
| Secret detection | gitleaks | ✅ |
| SAST (source code) | — | ❌ Missing (add SpotBugs/Semgrep) |
| Container image scanning | — | ❌ Missing (add Trivy image scan post-build) |
| Dependency scanning (SCA) | — | ❌ Missing (add `gradle dependencyCheckAnalyze`) |
| DAST | — | ❌ Missing (add OWASP ZAP post-deploy) |
| IaC scanning | — | ❌ Missing (add `tfsec` or `checkov` for Terraform) |
| Helm validation | — | ❌ Missing (add `helm lint`, `kubeconform`) |

**🔴 CRITICAL:** Container images are pushed to prod without being scanned. Add:
```yaml
- name: Scan container image
  uses: aquasecurity/trivy-action@v0.28.0
  with:
    scan-type: image
    image-ref: ${{ env.REGISTRY }}/${{ matrix.service }}:${{ github.sha }}
    severity: CRITICAL,HIGH
    exit-code: '1'
```

### 2.3 Multi-Environment Promotion

**Assessment: 🔴 CRITICAL — No staging or prod promotion**

| Environment | Status | How |
|-------------|--------|-----|
| Dev | ✅ | Auto-deploy on `main` merge (GitOps via values-dev.yaml) |
| Staging | ❌ | **Does not exist** |
| Prod | ❌ | **No pipeline** — manual? |

**Current flow:** PR → main → build → auto-deploy to dev. No gates to production.

**Recommendations:**
1. Add `values-staging.yaml` and staging ArgoCD Application
2. Add GitHub Environment protection rules with required reviewers for prod
3. Implement promotion pipeline: dev (auto) → staging (auto after smoke tests) → prod (manual approval + canary)

### 2.4 Canary / Blue-Green

**Assessment: ❌ NOT IMPLEMENTED**

No canary, blue-green, or progressive delivery mechanism exists. All deployments are direct rolling updates.

**Recommendation:** Implement Argo Rollouts or Flagger:
- Tier-1 services: Canary (5% → 25% → 50% → 100%) with automated Prometheus analysis
- Tier-2 services: Blue-green with instant rollback
- Tier-3 services: Rolling update (current)

### 2.5 Rollback

**Assessment: 🔴 CRITICAL — No automated rollback**

| Check | Status |
|-------|--------|
| ArgoCD auto-rollback on sync failure | ❌ Not configured |
| Pipeline rollback on health check failure | ❌ Not implemented |
| Helm rollback history | ❌ ArgoCD manages releases, not Helm CLI |
| Feature flag kill switches | ❌ No integration with config-feature-flag-service |

**Recommendation:** Add ArgoCD rollback:
```yaml
syncPolicy:
  automated:
    selfHeal: true    # Already present
  retry:
    limit: 3
    backoff:
      duration: 30s
      maxDuration: 3m
```
Plus Argo Rollouts `analysisTemplate` with Prometheus error rate queries.

### 2.6 CI Change Detection Coverage

**🔴 CRITICAL — Only 7 of 18 services in CI pipeline**

Detected services:
1. ✅ identity-service
2. ✅ catalog-service
3. ✅ inventory-service
4. ✅ order-service
5. ✅ payment-service
6. ✅ fulfillment-service
7. ✅ notification-service

**Missing from CI (not built, tested, or deployed):**
8. ❌ search-service
9. ❌ pricing-service
10. ❌ cart-service
11. ❌ checkout-orchestrator-service
12. ❌ warehouse-service
13. ❌ rider-fleet-service
14. ❌ routing-eta-service
15. ❌ wallet-loyalty-service
16. ❌ audit-trail-service
17. ❌ fraud-detection-service
18. ❌ config-feature-flag-service

Similarly, `values-dev.yaml` and `values-prod.yaml` only define 7 services (same 7). The remaining 11 services have no environment-specific overrides, no image tags, and no prod replica counts.

**Dependabot also only covers Docker for identity-service.** All 17 other service Dockerfiles are unmonitored.

---

## 3. Terraform / GCP Infrastructure

### 3.1 GKE Configuration

**Assessment: 🟠 HIGH — Functional but needs hardening**

```hcl
# Single node pool: general-pool
machine_type = "e2-standard-4"   # 4 vCPU, 16 GB RAM
min_node_count = 2
max_node_count = 6
```

| Finding | Detail |
|---------|--------|
| ✅ VPC-native networking | Proper secondary ranges for pods/services |
| ✅ Workload Identity | Best practice for GCP auth |
| ✅ Release channel: REGULAR | Automatic GKE upgrades |
| 🔴 **Single node pool** | No separation between workload types (compute-heavy vs memory-heavy) |
| 🔴 **max 6 nodes × 4 vCPU = 24 cores** | Won't support 18 services at max HPA (needs ~42 cores) |
| 🟠 No maintenance window | GKE upgrades can happen during peak hours |
| 🟠 No pod density config | Default 110 pods/node — may need more |
| 🟠 e2-standard-4 for prod | e2 is cost-effective but burstable — n2-standard-4 recommended for latency-sensitive workloads |
| ❌ No private cluster config | `enable_private_nodes` not set — nodes may get public IPs |
| ❌ No shielded nodes | `enable_secure_boot` not configured |
| ❌ No binary authorization | No image signing/verification |
| ❌ No GKE Backup | No cluster backup configuration |

**Recommendations:**
1. Add 3 node pools:
   - `general-pool`: e2-standard-4, min 3, max 8 (stateless services)
   - `compute-pool`: n2-standard-8, min 2, max 6 (search, fraud, routing-eta)
   - `system-pool`: e2-standard-2, min 1, max 2 (Istio, monitoring, ArgoCD)
2. Enable private cluster: `enable_private_nodes = true`
3. Set maintenance window: `maintenance_policy { recurring_window { ... } }` — weekday off-peak (02:00-06:00 IST)
4. Add node taints/tolerations for workload isolation

### 3.2 Cloud SQL (Database)

**Assessment: 🔴 CRITICAL — Single instance for all services**

```hcl
resource "google_sql_database_instance" "main" {
  name             = "instacommerce-pg-${var.env}"
  database_version = "POSTGRES_15"
  tier             = "db-custom-2-8192"     # 2 vCPU, 8 GB RAM
  disk_size        = 20                      # 20 GB
  availability_type = var.env == "prod" ? "REGIONAL" : "ZONAL"
}

# 7 databases on this single instance (dev variables)
databases = ["identity_db", "catalog_db", "inventory_db", "order_db",
             "payment_db", "fulfillment_db", "notification_db"]
```

| Finding | Severity | Detail |
|---------|----------|--------|
| 🔴 Single instance | CRITICAL | ALL 18 services share ONE Postgres instance. A single DB failure takes down the entire platform. |
| 🔴 Only 7 databases defined | CRITICAL | 11 services (search, pricing, cart, checkout, warehouse, rider, routing, wallet, audit, fraud, config) have no DB provisioned despite having `SPRING_DATASOURCE_URL` in values.yaml |
| 🔴 2 vCPU, 8 GB RAM for prod | CRITICAL | Grossly under-powered for 20M users across 18 services |
| 🔴 20 GB disk | CRITICAL | Order, audit, and event data for 20M users will exceed this in days |
| 🟠 No read replicas | HIGH | All read traffic hits primary — high latency during write-heavy operations |
| ✅ HA in prod | OK | `availability_type = "REGIONAL"` for prod |
| ✅ Backups enabled | OK | PITR with daily backups at 02:00 |
| ✅ Slow query logging | OK | `log_min_duration_statement = 1000ms` |
| 🟠 No connection pooling | HIGH | No PgBouncer or Cloud SQL Auth Proxy sidecar configuration |
| ❌ No DB users/roles | — | No Terraform for database users or least-privilege roles |

**Recommendations:**
1. **Minimum 3 Cloud SQL instances for prod:**
   - `pg-core`: identity, order, payment, wallet (transactional, HA critical)
   - `pg-catalog`: catalog, inventory, pricing, search, warehouse (read-heavy, add read replicas)
   - `pg-operational`: fulfillment, rider, routing, notification, audit, fraud, config (operational)
2. Upgrade tier: `db-custom-4-16384` minimum (4 vCPU, 16 GB) per instance
3. Disk: `disk_size = 100` with `disk_autoresize = true` (already enabled, but 20 GB starting is too small)
4. Add read replicas for catalog-db and search-db
5. Add all 18 databases to Terraform variables
6. Use Cloud SQL Auth Proxy as sidecar in deployments

### 3.3 Kafka

**Assessment: 🟡 MEDIUM — Local only, no production provisioning**

Docker Compose provides Confluent Kafka (KRaft mode) for local development. **No Terraform for production Kafka.**

| Question | Answer |
|----------|--------|
| Production Kafka? | ❌ Not provisioned |
| Confluent Cloud? | ❌ Not configured |
| Self-managed on GKE? | ❌ No Strimzi/Confluent operator |
| Topic configuration? | ❌ No topics defined in IaC |
| Schema Registry? | ❌ Not provisioned |
| Debezium (CDC)? | ✅ In docker-compose, ❌ not in prod |

**Recommendation:** Either:
- **Confluent Cloud** (recommended for managed): Add Confluent Terraform provider with topic definitions, ACLs, and Schema Registry
- **Self-managed**: Deploy Strimzi operator on GKE with dedicated node pool

### 3.4 Secrets Management

**Assessment: 🟠 HIGH — Partial implementation**

```hcl
# Only 4 secrets defined
secrets = ["sendgrid-api-key", "twilio-auth-token", "jwt-private-key", "jwt-public-key"]
```

| Finding | Detail |
|---------|--------|
| ✅ Google Secret Manager used | Secrets stored in GCP, not in Git |
| 🟠 Only 4 secrets for 18 services | Missing: DB passwords, Kafka credentials, Razorpay keys, Google Maps API key, Redis password |
| ❌ No Kubernetes integration | No `ExternalSecret` or `SecretProviderClass` to mount secrets into pods |
| ❌ No rotation policy | No `rotation_period` configured |
| ❌ Secrets in env vars | Services reference `SPRING_DATASOURCE_URL` as plain env var in values.yaml |
| 🔴 Placeholder DB URLs | `PROJECT:REGION:INSTANCE` in all datasource URLs — not parameterized per environment |

**Recommendations:**
1. Use External Secrets Operator (ESO) to sync GCP Secret Manager → Kubernetes Secrets
2. Move all credentials to Secret Manager: DB passwords, API keys, Kafka SASL credentials
3. Add rotation: `rotation_period = "7776000s"` (90 days)
4. Use Terraform variables for Cloud SQL connection names, not hardcoded placeholders

### 3.5 Networking

**Assessment: ✅ GOOD — Solid VPC design**

```hcl
# VPC with custom subnets
subnet_cidr   = "10.20.0.0/16"   (dev) / "10.30.0.0/16" (prod)
pods_cidr     = "10.21.0.0/16"   (dev) / "10.31.0.0/16" (prod)
services_cidr = "10.22.0.0/16"   (dev) / "10.32.0.0/16" (prod)

# NAT configured for outbound traffic
google_compute_router_nat: AUTO_ONLY
```

| Finding | Detail |
|---------|--------|
| ✅ Separate CIDR ranges for pods/services | Best practice for GKE |
| ✅ NAT for outbound | Pods can reach internet (dependency downloads, external APIs) |
| ✅ Non-overlapping dev/prod CIDRs | Enables VPC peering if needed |
| 🟠 No firewall rules | Only default GCP rules — no explicit ingress/egress rules |
| ❌ No Cloud Armor (WAF) | No DDoS protection or WAF for the API |
| ❌ No VPC Service Controls | No perimeter for sensitive data |
| ❌ Private cluster not configured | `enable_private_nodes` not in GKE module |

### 3.6 Multi-Region / Disaster Recovery

**Assessment: 🔴 CRITICAL — No DR strategy**

| Component | Multi-Region | DR Plan |
|-----------|-------------|---------|
| GKE | ❌ Single region (asia-south1) | ❌ |
| Cloud SQL | ✅ Regional HA (prod) | ❌ No cross-region replica |
| Redis (Memorystore) | ❌ Single region | ❌ |
| Artifact Registry | ❌ Single region | ❌ |
| DNS failover | ❌ Not configured | ❌ |
| Data backup to another region | ❌ Not configured | ❌ |

**Recommendation for 20M users:**
1. Active-passive setup: Primary in `asia-south1`, standby GKE in `asia-south2`
2. Cloud SQL cross-region read replica for failover
3. Multi-region Artifact Registry
4. Cloud DNS health-checked routing
5. Document RTO/RPO targets (suggest RTO < 15min, RPO < 5min)

---

## 4. ArgoCD / GitOps

**Assessment: 🟠 HIGH — Basic but needs hardening**

```yaml
# argocd/app-of-apps.yaml
spec:
  project: default              # ❌ Should use dedicated project
  source:
    repoURL: https://github.com/your-org/instacommerce  # ❌ Placeholder URL
    targetRevision: main
    path: deploy/helm
    helm:
      valueFiles:
        - values.yaml
        - values-dev.yaml       # ❌ Always dev — no env separation
  syncPolicy:
    automated:
      prune: true               # ✅ Removes orphaned resources
      selfHeal: true            # ✅ Corrects drift
    syncOptions:
      - CreateNamespace=true
```

| Finding | Severity | Detail |
|---------|----------|--------|
| 🔴 Single ArgoCD Application for all envs | CRITICAL | Always uses `values-dev.yaml` — prod not deployable |
| 🔴 Placeholder repo URL | CRITICAL | `your-org` not replaced — ArgoCD sync will fail |
| 🟠 `project: default` | HIGH | No RBAC restrictions — any ArgoCD user can modify |
| 🟠 No sync windows | HIGH | Deployments can happen anytime — need restricted prod windows |
| ❌ No ApplicationSet | — | Should use ApplicationSet for multi-env (dev/staging/prod) |
| ❌ No notifications | — | No ArgoCD Notification integration (Slack/PagerDuty) |
| ❌ No resource exclusions | — | CRDs and secrets should be excluded from pruning |
| ✅ selfHeal enabled | — | Drift correction — good |
| ✅ prune enabled | — | Orphan cleanup — good |

**Recommendation:** Replace single Application with ApplicationSet:
```yaml
apiVersion: argoproj.io/v1alpha1
kind: ApplicationSet
metadata:
  name: instacommerce
spec:
  generators:
    - list:
        elements:
          - env: dev
            cluster: dev-cluster
            valueFile: values-dev.yaml
          - env: staging
            cluster: staging-cluster
            valueFile: values-staging.yaml
          - env: prod
            cluster: prod-cluster
            valueFile: values-prod.yaml
  template:
    spec:
      source:
        helm:
          valueFiles:
            - values.yaml
            - '{{ valueFile }}'
```

---

## 5. Monitoring & Observability

### 5.1 Prometheus Rules

**Assessment: 🟠 HIGH — Minimal alerting for 18-service platform**

| Rule | Target | Threshold | Assessment |
|------|--------|-----------|------------|
| HighErrorRate | All services | >1% 5xx for 5m | ✅ Good threshold |
| HighLatency | All services | P99 >500ms for 5m | 🟡 500ms may be too lenient for cart/search (target <200ms) |
| KafkaConsumerLag | Kafka consumers | >1000 for 10m | ✅ But should be per-service |
| FrequentPodRestarts | All pods | >3 in 30m | ✅ Good |
| DatabaseHighCPU | Cloud SQL | >80% for 10m | ✅ Good |

**Missing alerts:**

| Category | Missing Alert |
|----------|--------------|
| SLO | ❌ No SLO burn-rate alerts (Google SRE best practice) |
| SLO | ❌ No availability SLO (99.9% / 99.95% targets) |
| Infra | ❌ No node disk pressure, memory pressure alerts |
| Infra | ❌ No PVC usage alerts |
| App | ❌ No payment failure rate alert (PCI-critical) |
| App | ❌ No order completion rate alert |
| App | ❌ No rider assignment latency alert |
| App | ❌ No delivery SLA breach alert |
| Kafka | ❌ No under-replicated partitions alert |
| Kafka | ❌ No broker offline alert |
| Redis | ❌ No Redis memory/connection alerts |
| Istio | ❌ No Istio control plane alerts |
| Security | ❌ No authentication failure spike alert |
| Cost | ❌ No resource utilization alerts (CPU < 20% = wasted spend) |

**Recommendations:**
1. Implement SLO-based alerting using burn rate:
   ```yaml
   - alert: ErrorBudgetBurnRate
     expr: |
       (1 - (sum(rate(http_server_requests_seconds_count{status!~"5.."}[1h])) by (service)
       / sum(rate(http_server_requests_seconds_count[1h])) by (service)))
       / (1 - 0.999) > 14.4
     for: 5m
     labels:
       severity: critical
       window: 1h
   ```
2. Add business-metric alerts (order success rate, payment success rate, delivery SLA)
3. Add per-service latency budgets (search < 200ms, payment < 1s, cart < 300ms)

### 5.2 Dashboards

**Assessment: ❌ NOT FOUND**

No Grafana dashboard definitions found in the repository. No `dashboards/` directory, no ConfigMaps with dashboard JSON, no GrafanaDashboard CRDs.

**Recommendation:** Create dashboards for:
1. **Platform overview:** Total RPS, error rate, latency P50/P95/P99, pod count, node count
2. **Per-service:** RPS, latency histogram, error rate, CPU/memory, pod restarts
3. **Business KPIs:** Orders/min, payment success rate, delivery time, search latency
4. **Infrastructure:** GKE node utilization, Cloud SQL metrics, Redis hit rate, Kafka lag

### 5.3 Logging

**Assessment: ❌ NOT CONFIGURED**

No centralized logging configuration found:
- No Fluentd/Fluent Bit DaemonSet
- No Cloud Logging sink configuration
- No structured logging format enforcement
- No log-based metrics

**Note:** GKE automatically sends stdout/stderr to Cloud Logging, but there's no:
- Log filtering/exclusion (cost management)
- Log-based alerting
- Log export to BigQuery for analysis
- Structured JSON logging enforcement

### 5.4 Distributed Tracing

**Assessment: ❌ NOT CONFIGURED**

No tracing configuration found:
- No OpenTelemetry Collector deployment
- No Jaeger or Zipkin
- No trace sampling configuration
- No Cloud Trace integration

For 18 microservices, distributed tracing is **essential** for debugging request flows.

**Recommendation:** Deploy OpenTelemetry Collector with:
- Istio automatic span injection (already enabled via sidecar)
- Cloud Trace exporter
- Trace-to-log correlation
- 1% sampling for production, 100% for errors

---

## 6. Missing Infrastructure Components

### 6.1 Redis — 🔴 CRITICAL Gap

| Component | Status |
|-----------|--------|
| Terraform Memorystore module | ✅ Provisioned (4GB, STANDARD_HA) |
| Kubernetes connection config | ❌ No Redis host/port in service env vars |
| Services that need Redis | cart-service (session), search-service (cache), pricing-service (cache), config-feature-flag (flags), rider-fleet (geospatial) |

**Impact:** Services are provisioned without Redis connection details. Cart service likely using PostgreSQL for session state (performance disaster at scale).

**Fix:** Add Redis env vars to values.yaml for applicable services:
```yaml
SPRING_DATA_REDIS_HOST: instacommerce-redis-{{ .env }}.redis.cache.goog
SPRING_DATA_REDIS_PORT: "6379"
```

### 6.2 OpenSearch / Elasticsearch — ❌ Not Provisioned

search-service uses PostgreSQL (`search_db`) which won't scale for full-text search at 20M users.

**Recommendation:** Add OpenSearch (managed) or AlloyDB with `pgvector` for semantic search. Terraform module needed.

### 6.3 CDN — ❌ Not Configured

No Cloud CDN, Cloud Storage buckets for static assets, or image optimization pipeline.

**Impact:** Product images served directly from application servers — high latency, high cost.

**Recommendation:**
1. Cloud Storage bucket for product images
2. Cloud CDN with signed URLs
3. Image transformation pipeline (Cloud Functions for resize/optimize)

### 6.4 Load Testing — ❌ Not Present

No `k6`, `Locust`, `Gatling`, or `JMeter` scripts found. For a Q-commerce platform with 20M users, load testing is essential for:
- Capacity planning (how many pods/nodes needed for peak?)
- Identifying bottlenecks before production
- Validating HPA behavior

**Recommendation:** Add k6 scripts in `tests/load/` with scenarios:
- Steady state: 10K concurrent users browsing/searching
- Flash sale: 100K concurrent users ordering simultaneously
- Rider surge: 5K riders updating location every 5 seconds

### 6.5 Chaos Engineering — ❌ Not Present

No Chaos Monkey, Litmus Chaos, or Gremlin configuration.

**Recommendation:** Add Litmus Chaos experiments:
- Pod kill for each tier-1 service
- Network partition between order-service and payment-service
- Cloud SQL failover test
- Zone failure simulation

### 6.6 Cost Management — ❌ Not Configured

| Gap | Impact |
|-----|--------|
| No resource labels/tags | Cannot attribute costs to teams/services |
| No GKE cost allocation | Cannot measure per-service spend |
| No committed use discounts | Paying on-demand for predictable base load |
| No spot/preemptible nodes | Missing 60-80% savings on fault-tolerant workloads |
| No idle resource detection | No alerts for under-utilized pods |

**Recommendation:**
1. Add labels to all Terraform resources: `team`, `service`, `cost-center`
2. Enable GKE Usage Metering
3. Use spot nodes for dev, preemptible for non-critical prod workloads
4. Committed use discounts for base node count

---

## 7. Docker Compose (Local Development)

**Assessment: ✅ GOOD for development**

| Service | Status | Notes |
|---------|--------|-------|
| PostgreSQL 15 | ✅ | Alpine, with init script |
| Redis 7 | ✅ | Alpine |
| Kafka (KRaft) | ✅ | Confluent 7.5.0, no ZooKeeper |
| Kafka Connect (Debezium) | ✅ | CDC for event sourcing |
| Temporal | ✅ | Workflow engine for sagas |
| Temporal UI | ✅ | Dev visibility |
| Kafka UI | ✅ | Dev visibility |

**Issues:**
- 🟠 `POSTGRES_PASSWORD: devpass` — should use `.env` file, not hardcoded
- 🟡 No health checks on containers — dependent services may start before PostgreSQL is ready
- 🟡 No OpenSearch for search-service development
- 🟡 No application services in compose — each service runs standalone

---

## 8. Priority Action Items

### P0 — Fix Before Next Production Deployment

| # | Item | Effort |
|---|------|--------|
| 1 | **Fix VirtualService port mismatch** — 11 services routing to wrong port | 1 hour |
| 2 | **Add 11 missing services to CI pipeline** | 2 hours |
| 3 | **Add 11 missing databases to Terraform** | 1 hour |
| 4 | **Fix ArgoCD repo URL placeholder** | 5 minutes |
| 5 | **Add Redis connection env vars** for cart, search, pricing services | 1 hour |
| 6 | **Increase Resource Quota** from 24 to 48+ CPU | 5 minutes |
| 7 | **Add container image scanning** to CI | 30 minutes |

### P1 — Within 2 Weeks

| # | Item | Effort |
|---|------|--------|
| 8 | Add AuthorizationPolicy for all 18 services | 4 hours |
| 9 | Add RequestAuthentication for all 18 services | 4 hours |
| 10 | Create staging environment (Terraform + ArgoCD + values-staging.yaml) | 2 days |
| 11 | Upgrade Cloud SQL: 3 instances, larger tier, read replicas | 1 day |
| 12 | Add prod deployment pipeline with manual approval | 1 day |
| 13 | Add values-prod.yaml entries for all 18 services | 2 hours |
| 14 | Increase GKE node pool max and add compute-pool | 4 hours |
| 15 | Add HPA scale-down stabilization (`behavior` block) | 2 hours |
| 16 | Enable private cluster in GKE module | 2 hours |

### P2 — Within 1 Month

| # | Item | Effort |
|---|------|--------|
| 17 | Implement canary deployments (Argo Rollouts or Flagger) | 3 days |
| 18 | Deploy OpenTelemetry Collector + Cloud Trace | 2 days |
| 19 | Add Grafana dashboards (platform + per-service) | 3 days |
| 20 | Implement SLO-based alerting with burn rates | 2 days |
| 21 | Add load testing scripts (k6) | 3 days |
| 22 | Provision Kafka for production (Confluent Cloud or Strimzi) | 3 days |
| 23 | Add Cloud Armor WAF | 1 day |
| 24 | External Secrets Operator integration | 2 days |
| 25 | Add IaC scanning (tfsec/checkov) to CI | 4 hours |

### P3 — Within 1 Quarter

| # | Item | Effort |
|---|------|--------|
| 26 | Multi-region DR (active-passive in asia-south2) | 2 weeks |
| 27 | OpenSearch for search-service | 1 week |
| 28 | CDN for product images | 3 days |
| 29 | Chaos engineering (Litmus Chaos) | 1 week |
| 30 | Cost management (labels, CUDs, spot nodes) | 1 week |
| 31 | DAST scanning (OWASP ZAP) | 2 days |

---

## 9. Architecture Risk Matrix

| Risk | Likelihood | Impact | Current Mitigation | Needed Mitigation |
|------|-----------|--------|-------------------|-------------------|
| Single Cloud SQL failure | Medium | 🔴 Total outage | Regional HA | 3 separate instances + read replicas |
| CI deploys untested services | High | 🔴 Broken prod | None | Add all 18 services to CI |
| No staging validation | High | 🟠 Bugs in prod | Dev testing | Staging environment |
| Istio port mismatch | Certain | 🔴 11 services unreachable | None | Fix VirtualService |
| No container scanning | High | 🟠 CVE in prod | Trivy filesystem | Trivy image scan |
| No prod rollback | Medium | 🔴 Extended outage | Manual revert | Argo Rollouts + auto-rollback |
| GKE node exhaustion | Medium | 🟠 Pods pending | 6-node max | Increase max, add node pools |
| No tracing | Certain | 🟠 Blind debugging | Logs only | OpenTelemetry |
| No rate limiting | Medium | 🟠 DDoS/abuse | None | Istio rate limiting + Cloud Armor |

---

## 10. Compliance & Regulatory Notes

For a Q-commerce platform handling payments:

| Requirement | Status |
|-------------|--------|
| PCI-DSS (payment data) | 🟠 mTLS ✅, encryption at rest ❌ not verified, audit logging ✅, but no network segmentation for payment-service |
| Data residency (India) | ✅ All infra in asia-south1 |
| GDPR/DPDPA | 🟠 No data deletion pipeline, no consent management |
| Audit trail | ✅ audit-trail-service exists |
| Access control | 🟠 Only 2/18 services have AuthorizationPolicy |

---

*Review generated by Senior SRE/DevOps Architect. All findings based on actual file contents in the repository as of review date.*
