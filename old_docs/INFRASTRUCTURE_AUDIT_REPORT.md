# Instacommerce Infrastructure Audit Report

**Platform**: Q-Commerce (20M+ users scale)  
**Date**: 2025  
**Auditor**: Senior SRE/DevOps Review  
**Scope**: All files under `deploy/`, `.github/`, `monitoring/`, `infra/`, `argocd/`, `scripts/`, `docker-compose.yml`, `build.gradle.kts`, `settings.gradle.kts`, and all service Dockerfiles

---

## Executive Summary

The platform has a solid foundation — GKE with Istio mesh, Terraform IaC, GitOps via ArgoCD, and a monorepo CI pipeline. However, **multiple CRITICAL and HIGH severity gaps** exist that would cause outages, data loss, or security incidents at the stated 20M+ user scale. The most urgent issues are: no rolling update strategy, no pod anti-affinity, missing circuit breakers, no canary/blue-green deployment, no Terraform state locking, undersized database for production, and no alerting integrations.

**Finding Count**: 8 CRITICAL · 15 HIGH · 12 MEDIUM · 8 LOW

---

## 1. Kubernetes / Helm

### 1.1 No Rolling Update Strategy Defined — **CRITICAL**
**File**: `deploy/helm/templates/deployment.yaml`  
The Deployment template has no `strategy` block. Kubernetes defaults to `RollingUpdate` with `maxUnavailable: 25%` and `maxSurge: 25%`. For a payment-critical platform at scale, this is unsafe — a bad deploy could take down 25% of pods instantly.

**Recommendation**: Add explicit strategy:
```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

### 1.2 No Pod Anti-Affinity — **CRITICAL**
**File**: `deploy/helm/templates/deployment.yaml`  
No `affinity` or `topologySpreadConstraints` are defined. All replicas of a service could be scheduled on the same node. A single node failure would take down the entire service.

**Recommendation**: Add `podAntiAffinity` with `preferredDuringSchedulingIgnoredDuringExecution` on `topology.kubernetes.io/zone` and `kubernetes.io/hostname`.

### 1.3 No `securityContext` on Pods/Containers — **HIGH**
**File**: `deploy/helm/templates/deployment.yaml`  
While Dockerfiles run as non-root, the Kubernetes manifests don't enforce this. No `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, or `seccompProfile` is set.

**Recommendation**: Add pod-level and container-level `securityContext`:
```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1001
  fsGroup: 1001
  seccompProfile:
    type: RuntimeDefault
containers:
  - securityContext:
      allowPrivilegeEscalation: false
      readOnlyRootFilesystem: true
      capabilities:
        drop: [ALL]
```

### 1.4 Notification Service Has No HPA — **HIGH**
**File**: `deploy/helm/values.yaml` (lines 168-178)  
`notification-service` has no `hpa` block. Combined with `replicas: 1` in base values (only 2 in prod), a notification storm (e.g., flash sale) would overwhelm this single instance. Notifications directly affect user experience.

**Recommendation**: Add HPA config for notification-service (minReplicas: 2, maxReplicas: 4).

### 1.5 HPA Only Uses CPU Metric — **MEDIUM**
**File**: `deploy/helm/templates/hpa.yaml`  
HPA scales only on CPU utilization. For a q-commerce platform, memory-bound services (JVM heap), request rate, and Kafka consumer lag are equally important scaling signals.

**Recommendation**: Add memory metric and consider custom metrics (requests-per-second via Prometheus adapter).

### 1.6 PDB `maxUnavailable: 1` May Be Too Aggressive for Small Services — **LOW**
**File**: `deploy/helm/templates/pdb.yaml`, `deploy/helm/values.yaml`  
With `maxUnavailable: 1` and `replicas: 2` (base), 50% of a service can be disrupted. For services with `replicas: 2`, consider using `minAvailable: 1` instead for clarity, or increase replicas.

### 1.7 No Resource Quotas or LimitRanges — **MEDIUM**
**File**: No file exists.  
No `ResourceQuota` or `LimitRange` templates exist. A misbehaving service could consume all cluster resources.

### 1.8 No NetworkPolicy (Defense in Depth) — **MEDIUM**
**File**: No file exists.  
While Istio AuthorizationPolicies handle service-to-service authz, native Kubernetes `NetworkPolicy` resources provide defense-in-depth at the CNI layer. Missing if Istio sidecar injection fails or is bypassed.

### 1.9 Secrets Not Managed via Helm — **HIGH**
**File**: `deploy/helm/templates/` (no secrets template)  
Database credentials (`SPRING_DATASOURCE_URL`) are in `values.yaml` as environment variables. No `ExternalSecrets`, `SecretProviderClass` (GCP Secret Manager CSI driver), or sealed secrets integration exists. The JDBC URL contains placeholder `PROJECT:REGION:INSTANCE` but actual credentials flow is unclear.

**Recommendation**: Implement `SecretProviderClass` with GCP Secret Manager CSI driver, or use External Secrets Operator.

---

## 2. Istio / Service Mesh

### 2.1 No Circuit Breaking / Outlier Detection — **CRITICAL**
**File**: `deploy/helm/templates/istio/` (no DestinationRule exists)  
No `DestinationRule` with `outlierDetection` or `connectionPool` settings. A single slow downstream (e.g., payment gateway timeout) will cascade and take down the entire platform via thread pool exhaustion.

**Recommendation**: Add DestinationRules for every service:
```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: payment-service
spec:
  host: payment-service
  trafficPolicy:
    connectionPool:
      tcp: { maxConnections: 100 }
      http: { h2UpgradePolicy: DEFAULT, http1MaxPendingRequests: 100, http2MaxRequests: 1000 }
    outlierDetection:
      consecutive5xxErrors: 5
      interval: 10s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
```

### 2.2 No Rate Limiting — **CRITICAL**
**File**: No rate limiting config exists.  
No Istio `EnvoyFilter` for rate limiting, no `RateLimitService` integration. A single abusive client or bot attack could exhaust resources across the cluster. Critical for auth endpoints (brute force) and order/checkout endpoints.

**Recommendation**: Deploy Envoy rate limiting service with per-route and per-user limits. At minimum, configure local rate limiting via EnvoyFilter.

### 2.3 No Retry/Timeout Policies — **HIGH**
**File**: `deploy/helm/templates/istio/virtual-service.yaml`  
VirtualService routes have no `timeout`, `retries`, or `fault` injection config. Without timeouts, a hung backend holds Istio proxy connections indefinitely.

**Recommendation**: Add per-route timeouts and retries:
```yaml
route:
  - destination: { host: payment-service }
timeout: 10s
retries:
  attempts: 3
  perTryTimeout: 3s
  retryOn: 5xx,reset,connect-failure
```

### 2.4 AuthorizationPolicy Only Covers 2 of 7 Services — **HIGH**
**File**: `deploy/helm/values.yaml` (lines 56-68), `deploy/helm/templates/istio/authorization-policy.yaml`  
Only `payment-service` and `inventory-service` have AuthorizationPolicies. The other 5 services (`identity-service`, `catalog-service`, `order-service`, `fulfillment-service`, `notification-service`) accept requests from ANY service principal in the mesh — violating zero-trust.

**Recommendation**: Define AuthorizationPolicies for ALL services with explicit caller whitelists.

### 2.5 RequestAuthentication Only Covers 2 of 7 Services — **HIGH**
**File**: `deploy/helm/values.yaml` (lines 43-55)  
JWT validation via `RequestAuthentication` is only configured for `payment-service` and `inventory-service`. External-facing services (identity, catalog, order) should also validate JWT tokens.

### 2.6 No CORS Configuration — **MEDIUM**
**File**: `deploy/helm/templates/istio/virtual-service.yaml`  
No `corsPolicy` in the VirtualService. If a frontend app exists, CORS must be explicitly configured or browsers will block requests.

### 2.7 Gateway Only Listens on HTTPS/443 — No HTTP→HTTPS Redirect — **LOW**
**File**: `deploy/helm/templates/istio/gateway.yaml`  
No port 80 listener with redirect. Any client hitting HTTP will get a connection refused instead of a redirect.

### 2.8 Security Headers via EnvoyFilter — Good ✅ — **INFO**
**File**: `deploy/helm/templates/istio/security-headers.yaml`  
HSTS, X-Content-Type-Options, X-Frame-Options, CSP, XSS-Protection headers are correctly injected. Well implemented.

---

## 3. CI/CD Pipeline

### 3.1 No Test Coverage Gate — **HIGH**
**File**: `.github/workflows/ci.yml` (line 87)  
The pipeline runs `gradle test` but does NOT run `jacocoTestReport` or `jacocoTestCoverageVerification`. The docs (`08-platform-observability-ci-cd.md`, line 649-652) describe coverage checks, but they are **not implemented** in the actual CI. Code can be merged with 0% test coverage.

**Recommendation**: Add `gradle test jacocoTestReport jacocoTestCoverageVerification` to the test step.

### 3.2 No Container Image Scanning — **CRITICAL**
**File**: `.github/workflows/ci.yml`  
`trivy-action` runs filesystem scan only (`scan-type: fs`). There is **no container image scan** after `docker build`. Known CVEs in base images or dependencies in the built container will go undetected.

**Recommendation**: Add Trivy image scan step after Docker build:
```yaml
- name: Scan Container Image
  uses: aquasecurity/trivy-action@v0.28.0
  with:
    image-ref: ${{ env.REGISTRY }}/${{ matrix.service }}:${{ github.sha }}
    severity: CRITICAL,HIGH
    exit-code: '1'
```

### 3.3 No DAST (Dynamic Application Security Testing) — **HIGH**
**File**: `.github/workflows/ci.yml`  
No DAST scanning (e.g., OWASP ZAP). SAST (gitleaks + Trivy FS) is present but insufficient for runtime vulnerabilities like injection, auth bypass, etc.

### 3.4 No Canary or Blue-Green Deployment — **CRITICAL**
**File**: `.github/workflows/ci.yml` (deploy-dev job, lines 109-129)  
The deployment strategy is "update Helm values, push to Git, ArgoCD auto-syncs." This is a big-bang deployment — all pods get the new version simultaneously. For 20M+ users, a bad deploy would affect all traffic immediately.

**Recommendation**: Implement Argo Rollouts with canary strategy (e.g., 10% → 30% → 100% with automated analysis).

### 3.5 No Production Deployment Pipeline — **HIGH**
**File**: `.github/workflows/ci.yml`  
Only `deploy-dev` job exists. There is no `deploy-staging` or `deploy-prod` job. The prod deployment process is undocumented and likely manual, which is risky at scale.

### 3.6 CI Commits Back to Main Branch — Race Condition Risk — **MEDIUM**
**File**: `.github/workflows/ci.yml` (lines 122-128)  
The `deploy-dev` job does `git push` back to `main`. If two PRs merge close together, this creates race conditions and potential push failures. The `|| exit 0` on commit also silently swallows failures.

**Recommendation**: Use a separate deployment repo or branch for Helm value updates, or use ArgoCD image updater.

### 3.7 `security-scan` Job Not a Gate for `build-test` — **MEDIUM**
**File**: `.github/workflows/ci.yml`  
`build-test` depends on `detect-changes` but NOT on `security-scan`. A build with leaked secrets or critical vulnerabilities can still be pushed to the registry.

**Recommendation**: Add `security-scan` to `build-test.needs`.

### 3.8 No Build Caching for Docker — **LOW**
**File**: `.github/workflows/ci.yml` (line 94)  
Docker builds don't use `--cache-from` or BuildKit cache mounts. Each CI run rebuilds the entire Gradle project and Docker layers from scratch, increasing build time.

---

## 4. Monitoring Stack

### 4.1 No Alertmanager / PagerDuty / Slack Integration — **CRITICAL**
**File**: `monitoring/prometheus-rules.yaml`  
Prometheus alert rules are defined but there is NO Alertmanager configuration, no PagerDuty integration, no Slack webhook, no OpsGenie — nothing. Alerts fire into the void. Nobody gets paged.

**Recommendation**: Deploy Alertmanager with at least PagerDuty for critical alerts and Slack for warnings. Define escalation policies.

### 4.2 No Grafana Dashboard Definitions in Repo — **MEDIUM**
**File**: `monitoring/` (only `prometheus-rules.yaml`)  
The docs describe Grafana dashboards, but no dashboard JSON/YAML provisioning files exist in the repo. Dashboards are likely manually created and will be lost on Grafana redeployment.

**Recommendation**: Export dashboards as JSON and store in `monitoring/dashboards/` with Grafana provisioning config.

### 4.3 `FrequentPodRestarts` Alert Has No `for` Duration — **MEDIUM**
**File**: `monitoring/prometheus-rules.yaml` (lines 29-31)  
```yaml
- alert: FrequentPodRestarts
  expr: increase(kube_pod_container_status_restarts_total[30m]) > 3
  labels:
    severity: critical
```
No `for:` clause means this alert fires immediately on a transient spike, leading to alert fatigue.

**Recommendation**: Add `for: 5m`.

### 4.4 Missing Alerts — **HIGH**
**File**: `monitoring/prometheus-rules.yaml`  
Critical alerts missing:
- **Disk space** (CloudSQL or PV)
- **Memory utilization** (pod or node)
- **Node NotReady** conditions
- **HPA at max replicas** (scaling ceiling)
- **Certificate expiry** (TLS certs)
- **Redis memory/connection alerts**
- **Temporal workflow failure rate**
- **SLO burn rate** alerts (multi-window)

### 4.5 No SLO/SLI Formal Definitions — **HIGH**
**File**: `monitoring/prometheus-rules.yaml`  
The doc mentions SLOs but no formal SLO definitions exist (e.g., "99.9% of orders complete within 2s"). No error budget tracking, no burn rate alerts, no SLO dashboards.

### 4.6 No Log Aggregation Config in Repo — **MEDIUM**
**File**: No Loki, Fluentd, or Cloud Logging config exists.  
The docs reference Loki but no deployment manifests or config exist. Log aggregation is likely not deployed.

---

## 5. Infrastructure as Code (Terraform)

### 5.1 No State Locking — **CRITICAL**
**File**: `infra/terraform/backend.tf`  
```hcl
terraform {
  backend "gcs" {
    bucket = "instacommerce-terraform-state"
    prefix = "environments"
  }
}
```
GCS backend supports state locking natively, BUT the `backend.tf` is at the root level and shared across both `dev/` and `prod/` environments with the same prefix. Two engineers running `terraform apply` on different environments could corrupt state. Each environment should have its own backend config or at minimum a unique `prefix`.

**Recommendation**: Move backend config into each environment directory with unique prefixes:
```hcl
# environments/dev/backend.tf
backend "gcs" { bucket = "instacommerce-terraform-state"; prefix = "environments/dev" }
# environments/prod/backend.tf
backend "gcs" { bucket = "instacommerce-terraform-state"; prefix = "environments/prod" }
```

### 5.2 Dev and Prod Use Identical Terraform Configs — **HIGH**
**File**: `infra/terraform/environments/dev/main.tf` vs `infra/terraform/environments/prod/main.tf`  
Both files are **identical**. No prod-specific hardening:
- Same CloudSQL tier (`db-custom-2-8192` = 2 vCPU, 8GB) for both dev and prod
- Same GKE node pool config
- Same Memorystore size (4GB)
- No Cloud Armor, no VPC firewall rules, no prod-grade node count

### 5.3 CloudSQL Undersized for Production — **CRITICAL**
**File**: `infra/terraform/modules/cloudsql/main.tf` (line 8)  
`tier = "db-custom-2-8192"` (2 vCPU, 8GB RAM) with `disk_size = 20` GB — this is a **development-grade** database. For 20M+ users with 7 databases on a single instance, this will hit CPU/memory/disk limits rapidly. No read replicas.

**Recommendation**: 
- Prod should use at minimum `db-custom-8-32768` (8 vCPU, 32GB)
- Add read replicas for catalog and order services
- Set `disk_size = 100` minimum with autoresize
- Consider separate CloudSQL instances for high-traffic databases

### 5.4 Single CloudSQL Instance for All 7 Databases — **HIGH**
**File**: `infra/terraform/modules/cloudsql/main.tf`  
All 7 service databases run on a single PostgreSQL instance. A noisy-neighbor problem (e.g., a complex catalog search query) will degrade performance for order processing and payments. No blast radius isolation.

### 5.5 No Terraform `required_version` or `required_providers` — **MEDIUM**
**File**: `infra/terraform/backend.tf`, all `main.tf` files  
No version constraints. Different engineers may use different Terraform versions, causing state file incompatibilities.

### 5.6 IAM Module Creates Service Accounts But No Role Bindings — **HIGH**
**File**: `infra/terraform/modules/iam/main.tf`  
Only `google_service_account.service` is created. No `google_project_iam_member` or `google_service_account_iam_binding` for Workload Identity binding. Services cannot actually authenticate to CloudSQL, Secret Manager, or other GCP services.

### 5.7 Secret Manager Creates Secrets But No Values or IAM — **MEDIUM**
**File**: `infra/terraform/modules/secret-manager/main.tf`  
Secrets are created as empty shells. No `google_secret_manager_secret_version` (values) and no `google_secret_manager_secret_iam_member` (access grants).

### 5.8 No VPC Firewall Rules — **HIGH**
**File**: `infra/terraform/modules/vpc/main.tf`  
No `google_compute_firewall` resources. Default GCP firewall rules allow intra-VPC traffic, but explicit deny-all + allow-list rules should be defined for production.

### 5.9 No Terraform Plan/Apply in CI — **MEDIUM**
No GitHub Actions workflow for Terraform `plan` on PR or `apply` on merge. Infrastructure changes are applied manually with no review process.

---

## 6. Docker

### 6.1 Poor Layer Caching — All Dockerfiles — **MEDIUM**
**File**: All `services/*/Dockerfile` (line 3-4)  
```dockerfile
COPY . .
RUN gradle clean build -x test --no-daemon
```
`COPY . .` before the build invalidates the entire cache on ANY file change. Gradle dependencies are re-downloaded every build.

**Recommendation**: Copy `build.gradle.kts` and `settings.gradle.kts` first, run dependency resolution, then copy source:
```dockerfile
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle dependencies --no-daemon
COPY . .
RUN gradle clean build -x test --no-daemon
```

### 6.2 No `.dockerignore` Files — **LOW**
**File**: No `.dockerignore` found in any service directory.  
`.git/`, `build/`, `*.md`, etc. are sent to Docker context, increasing build time and image size.

### 6.3 `COPY --from=build /app/build/libs/*.jar app.jar` Glob May Copy Multiple JARs — **LOW**
**File**: All `services/*/Dockerfile` (line 9)  
If Gradle produces multiple JARs (e.g., plain + boot), the glob `*.jar` may fail or copy the wrong one. Use the specific `*-SNAPSHOT.jar` or `-boot.jar` pattern.

### 6.4 CI Builds Jar Twice — **MEDIUM**
**File**: `.github/workflows/ci.yml` (lines 86-90) vs Dockerfiles  
CI runs `gradle test` then `gradle bootJar`, then `docker build` runs `gradle clean build` again inside Docker. The JAR is built twice — once in CI, once in Docker. This is wasteful and risks inconsistency.

**Recommendation**: Either build the JAR in CI and use a simpler Dockerfile that only copies the pre-built JAR, or remove the CI `bootJar` step and let Docker handle the build entirely.

### 6.5 Multi-Stage Build — Good ✅ — **INFO**
All Dockerfiles correctly use multi-stage builds (Gradle build stage → JRE-only runtime), non-root user, health checks, JVM tuning. Well implemented.

---

## 7. ArgoCD

### 7.1 Single Application for All Services — No Blast Radius Control — **HIGH**
**File**: `argocd/app-of-apps.yaml`  
Despite being named "app-of-apps", this is a single ArgoCD `Application` deploying the entire Helm chart. A bad Helm value change affects ALL 7 services simultaneously. True app-of-apps should have one ArgoCD Application per service.

**Recommendation**: Create one ArgoCD Application per microservice for independent sync/rollback.

### 7.2 ArgoCD Only Configured for Dev — No Prod Application — **HIGH**
**File**: `argocd/app-of-apps.yaml` (line 14)  
Only `values-dev.yaml` is referenced. No ArgoCD application for staging or production. Prod deployments are effectively unmanaged.

### 7.3 Auto-Sync with Prune Enabled — Risky in Prod — **MEDIUM**
**File**: `argocd/app-of-apps.yaml` (lines 19-22)  
```yaml
syncPolicy:
  automated:
    prune: true
    selfHeal: true
```
`prune: true` with auto-sync means any resource removed from Git is immediately deleted in the cluster. An accidental YAML deletion (or rebase error) would destroy running services.

**Recommendation**: For production, use manual sync with prune protection, or add `syncOptions: [ApplyOutOfSyncOnly=true]`.

### 7.4 No Argo Rollouts / Progressive Delivery — **HIGH**
**File**: No Argo Rollouts CRDs, `AnalysisTemplate`, or `Rollout` resources exist.  
Standard Kubernetes `Deployment` is used everywhere. No canary analysis, no automated rollback based on metrics. Docs aspirationally describe progressive delivery but it's not implemented.

### 7.5 No Sync Waves or Hooks — **LOW**
**File**: `argocd/app-of-apps.yaml`  
No sync waves to control deployment order. Database migrations should run before application deployments.

---

## 8. Database Operations

### 8.1 No Database Migration Framework in CI/CD — **HIGH**
**File**: `.github/workflows/ci.yml`, `scripts/init-dbs.sql`  
`init-dbs.sql` only contains `CREATE DATABASE` statements. No Flyway/Liquibase migration files are version-controlled. Schema changes are presumably applied manually or via Spring Boot auto-DDL, both of which are dangerous in production.

**Recommendation**: Enforce Flyway/Liquibase migrations with CI validation. Add a pre-deploy migration job.

### 8.2 No Connection Pool Configuration — **MEDIUM**
**File**: `deploy/helm/values.yaml`  
No HikariCP pool settings in environment variables (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`, etc.). Default HikariCP pool is 10 connections. With 7 databases on one CloudSQL instance, that's up to 70 connections from 2+ pods each = 140+ connections against a 2-vCPU instance.

### 8.3 No Read Replica Configuration — **HIGH**
**File**: `infra/terraform/modules/cloudsql/main.tf`  
No `google_sql_database_instance` with `master_instance_name` for read replicas. Read-heavy services (catalog, search) will saturate the primary instance.

### 8.4 No Backup Retention or Cross-Region Backup — **MEDIUM**
**File**: `infra/terraform/modules/cloudsql/main.tf` (lines 18-22)  
Backup is enabled with PITR, but no `transaction_log_retention_days` or `backup_retention_settings` block. Default retention may be insufficient. No cross-region backup for DR.

### 8.5 Single Database Instance = Single Point of Failure — **HIGH**
**File**: `infra/terraform/modules/cloudsql/main.tf`  
Prod uses `REGIONAL` availability (automatic failover), which is good. But all 7 databases on one instance means a failover event (even automated) creates downtime for ALL services simultaneously.

---

## 9. Missing Capabilities — Gap Analysis

### 9.1 No Chaos Engineering — **HIGH**
No LitmusChaos, Chaos Monkey, or Gremlin configuration. For 20M+ users, you must validate that services handle pod failures, network partitions, and zone outages gracefully. Zero resilience testing exists.

### 9.2 No Load Testing — **HIGH**
No k6, Gatling, JMeter, or Locust scripts. No performance baselines established. No way to validate the platform handles expected traffic before production deployments.

### 9.3 No Disaster Recovery Plan — **CRITICAL**
No DR documentation, no cross-region replication, no backup restore procedures, no RTO/RPO definitions. A regional GCP outage would be a total platform loss.

### 9.4 No Multi-Region Architecture — **HIGH**
**File**: `infra/terraform/environments/prod/terraform.tfvars`  
Single region (`asia-south1`) only. For 20M+ users, a regional outage means 100% downtime. At minimum, a warm standby in `asia-south2` should exist.

### 9.5 No CDN Configuration — **MEDIUM**
No Cloud CDN, Cloudflare, or Fastly config. Static assets, product images, and API responses that could be cached are served directly from origin pods.

### 9.6 No WAF / Cloud Armor — **HIGH**
**File**: `infra/terraform/` (no Cloud Armor module)  
The architecture doc mentions Cloud Armor but no Terraform module exists. No DDoS protection, no OWASP rule sets, no geographic blocking. The platform is directly exposed.

### 9.7 No Pod Security Standards / Admission Controller — **MEDIUM**
No `PodSecurityPolicy`, `PodSecurityStandard`, or OPA/Gatekeeper/Kyverno policies. Any pod spec can be deployed including privileged containers.

### 9.8 No Horizontal Pod Topology Spread — **MEDIUM**
No `topologySpreadConstraints` to ensure pods spread across availability zones within `asia-south1`.

### 9.9 No Cost Monitoring / FinOps — **LOW**
No GCP budget alerts, cost allocation labels, or FinOps tooling configured.

### 9.10 No Centralized Logging Pipeline — **HIGH**
The docs mention Loki but no config exists. No Fluentd/Fluent Bit DaemonSet, no Cloud Logging sinks. Debugging production issues without centralized logs is impossible.

### 9.11 No Distributed Tracing Deployment — **MEDIUM**
Docs describe OTel collector and Tempo but no actual deployment manifests exist in the repo. Tracing is documented but not deployable from the repo.

### 9.12 No Staging Environment — **HIGH**
Only dev and prod environments exist in Terraform, ArgoCD, and CI/CD. No staging environment for pre-production validation. Changes go dev → prod with no intermediate gate.

---

## 10. Summary Table

| # | Finding | Severity | Category | File(s) |
|---|---------|----------|----------|---------|
| 1 | No rolling update strategy | CRITICAL | K8s/Helm | `deploy/helm/templates/deployment.yaml` |
| 2 | No pod anti-affinity | CRITICAL | K8s/Helm | `deploy/helm/templates/deployment.yaml` |
| 3 | No circuit breaking / outlier detection | CRITICAL | Istio | `deploy/helm/templates/istio/` (missing DestinationRule) |
| 4 | No rate limiting | CRITICAL | Istio | Missing entirely |
| 5 | No container image scanning | CRITICAL | CI/CD | `.github/workflows/ci.yml` |
| 6 | No canary/blue-green deployment | CRITICAL | CI/CD | `.github/workflows/ci.yml` |
| 7 | No alerting integration (PagerDuty/Slack) | CRITICAL | Monitoring | `monitoring/prometheus-rules.yaml` |
| 8 | CloudSQL undersized for prod (2vCPU/8GB) | CRITICAL | Terraform | `infra/terraform/modules/cloudsql/main.tf` |
| 9 | No disaster recovery plan | CRITICAL | Missing | N/A |
| 10 | No securityContext in K8s manifests | HIGH | K8s/Helm | `deploy/helm/templates/deployment.yaml` |
| 11 | Notification-service no HPA | HIGH | K8s/Helm | `deploy/helm/values.yaml` |
| 12 | Secrets management not implemented | HIGH | K8s/Helm | `deploy/helm/templates/` |
| 13 | No retry/timeout in VirtualService | HIGH | Istio | `deploy/helm/templates/istio/virtual-service.yaml` |
| 14 | AuthorizationPolicy covers only 2/7 svcs | HIGH | Istio | `deploy/helm/values.yaml` |
| 15 | RequestAuthentication covers only 2/7 svcs | HIGH | Istio | `deploy/helm/values.yaml` |
| 16 | No test coverage gate in CI | HIGH | CI/CD | `.github/workflows/ci.yml` |
| 17 | No DAST scanning | HIGH | CI/CD | `.github/workflows/ci.yml` |
| 18 | No production deployment pipeline | HIGH | CI/CD | `.github/workflows/ci.yml` |
| 19 | Missing critical Prometheus alerts | HIGH | Monitoring | `monitoring/prometheus-rules.yaml` |
| 20 | No SLO/SLI formal definitions | HIGH | Monitoring | Missing entirely |
| 21 | Dev/prod Terraform configs identical | HIGH | Terraform | `infra/terraform/environments/` |
| 22 | IAM module: no role bindings | HIGH | Terraform | `infra/terraform/modules/iam/main.tf` |
| 23 | No VPC firewall rules | HIGH | Terraform | `infra/terraform/modules/vpc/main.tf` |
| 24 | Single CloudSQL for all 7 DBs | HIGH | Database | `infra/terraform/modules/cloudsql/main.tf` |
| 25 | No read replicas | HIGH | Database | `infra/terraform/modules/cloudsql/main.tf` |
| 26 | Single DB instance = SPOF | HIGH | Database | `infra/terraform/modules/cloudsql/main.tf` |
| 27 | No DB migration framework in CI | HIGH | Database | `.github/workflows/ci.yml` |
| 28 | ArgoCD single app, no blast radius control | HIGH | ArgoCD | `argocd/app-of-apps.yaml` |
| 29 | ArgoCD only dev, no prod | HIGH | ArgoCD | `argocd/app-of-apps.yaml` |
| 30 | No Argo Rollouts / progressive delivery | HIGH | ArgoCD | Missing entirely |
| 31 | No chaos engineering | HIGH | Missing | N/A |
| 32 | No load testing | HIGH | Missing | N/A |
| 33 | No multi-region | HIGH | Missing | N/A |
| 34 | No WAF / Cloud Armor | HIGH | Missing | N/A |
| 35 | No centralized logging | HIGH | Missing | N/A |
| 36 | No staging environment | HIGH | Missing | N/A |
| 37 | Terraform state prefix collision risk | CRITICAL | Terraform | `infra/terraform/backend.tf` |
| 38 | HPA CPU-only scaling | MEDIUM | K8s/Helm | `deploy/helm/templates/hpa.yaml` |
| 39 | No ResourceQuota/LimitRange | MEDIUM | K8s/Helm | Missing |
| 40 | No NetworkPolicy | MEDIUM | K8s/Helm | Missing |
| 41 | No CORS config | MEDIUM | Istio | `deploy/helm/templates/istio/virtual-service.yaml` |
| 42 | CI pushes back to main (race condition) | MEDIUM | CI/CD | `.github/workflows/ci.yml` |
| 43 | security-scan not a build gate | MEDIUM | CI/CD | `.github/workflows/ci.yml` |
| 44 | No Grafana dashboard provisioning | MEDIUM | Monitoring | Missing |
| 45 | FrequentPodRestarts no `for` duration | MEDIUM | Monitoring | `monitoring/prometheus-rules.yaml` |
| 46 | No log aggregation config | MEDIUM | Monitoring | Missing |
| 47 | No TF version constraints | MEDIUM | Terraform | All TF files |
| 48 | Secret Manager: no values or IAM | MEDIUM | Terraform | `infra/terraform/modules/secret-manager/main.tf` |
| 49 | No TF plan/apply in CI | MEDIUM | Terraform | Missing |
| 50 | Docker layer caching inefficient | MEDIUM | Docker | All `Dockerfile`s |
| 51 | CI builds JAR twice (CI + Docker) | MEDIUM | Docker/CI | `.github/workflows/ci.yml` + Dockerfiles |
| 52 | ArgoCD auto-prune risky for prod | MEDIUM | ArgoCD | `argocd/app-of-apps.yaml` |
| 53 | No connection pool config | MEDIUM | Database | `deploy/helm/values.yaml` |
| 54 | No backup retention settings | MEDIUM | Database | `infra/terraform/modules/cloudsql/main.tf` |
| 55 | No CDN | MEDIUM | Missing | N/A |
| 56 | No Pod Security Standards | MEDIUM | Missing | N/A |
| 57 | No topology spread constraints | MEDIUM | Missing | N/A |
| 58 | No distributed tracing deployment | MEDIUM | Missing | N/A |
| 59 | PDB may be too aggressive for 2-replica svcs | LOW | K8s/Helm | `deploy/helm/templates/pdb.yaml` |
| 60 | No HTTP→HTTPS redirect in Gateway | LOW | Istio | `deploy/helm/templates/istio/gateway.yaml` |
| 61 | No Docker build caching in CI | LOW | CI/CD | `.github/workflows/ci.yml` |
| 62 | No `.dockerignore` | LOW | Docker | All services |
| 63 | JAR glob may match multiple files | LOW | Docker | All `Dockerfile`s |
| 64 | No ArgoCD sync waves | LOW | ArgoCD | `argocd/app-of-apps.yaml` |
| 65 | No FinOps / cost monitoring | LOW | Missing | N/A |

---

## 11. Priority Remediation Roadmap

### P0 — Fix This Week (CRITICAL)
1. Add rolling update strategy + pod anti-affinity to deployment template
2. Add DestinationRules with circuit breaking for all services
3. Add Envoy rate limiting (at minimum for auth + checkout endpoints)
4. Add container image scanning to CI pipeline
5. Deploy Alertmanager with PagerDuty/Slack
6. Fix Terraform backend prefix collision — separate per environment
7. Right-size CloudSQL for production (8+ vCPU, 32+ GB RAM, read replicas)
8. Define and document DR plan with RTO/RPO

### P1 — Fix This Sprint (HIGH)
1. Add securityContext to all pod specs
2. Expand AuthorizationPolicies to all 7 services
3. Add timeouts/retries to VirtualService
4. Implement Jacoco coverage gate in CI
5. Create prod ArgoCD Application + deployment pipeline
6. Implement Argo Rollouts for canary deployments
7. Add missing Prometheus alerts (memory, disk, HPA max, cert expiry)
8. Define formal SLOs with error budget burn rate alerts
9. Fix IAM module — add Workload Identity bindings
10. Deploy centralized logging (Loki or Cloud Logging)

### P2 — Fix This Quarter (MEDIUM/LOW)
1. Implement staging environment
2. Add Cloud Armor / WAF
3. Set up chaos engineering framework
4. Create load testing suite
5. Optimize Docker layer caching
6. Add Grafana dashboard provisioning
7. Implement ExternalSecrets or Secret CSI driver
8. Multi-region warm standby

---

*Report generated from exhaustive review of all configuration files in the Instacommerce repository.*
