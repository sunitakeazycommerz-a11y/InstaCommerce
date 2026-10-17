#!/usr/bin/env python3
"""
Issue definitions for Milestone 17 (Wave 0) and Milestone 18 (Wave 1).
Contains 51 staff-level issue specifications.
"""

WAVE_0_ISSUES = [
    {
        "milestone": 17,
        "title": "sec(admin-gateway): missing SecurityConfig and RBAC enforcement on admin endpoints",
        "labels": ["kind/security", "priority/p0-critical", "area/edge-api", "java"],
        "body": """### Summary
`admin-gateway-service` runs on port 8099 without an application-layer `SecurityConfig` or JWT authentication filter. Any pod in the internal mesh can invoke admin routes without RBAC checks.

### Evidence & Code Reference
- `services/admin-gateway-service/src/main/java/` — No Spring Security configuration class exists.
- `AdminGatewayApplication.java` has no token filter or role evaluation.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C1-P0-02).

### Blast Radius & Business Impact
Full administrative surface exposed. Allows fraudulent refunds, inventory overrides, order cancellations, and sensitive data extraction if internal perimeter is breached.

### Remediation Plan
1. Create `AdminSecurityConfig` enforcing Spring WebFlux Security with RS256 JWT validation.
2. Require dedicated `ROLE_SUPER_ADMIN` claim for mutation routes (`POST`, `PUT`, `DELETE`).
3. Deploy an Istio `AuthorizationPolicy` network-isolating port 8099 to authenticated admin ingress only.
4. Implement structured security audit logging for all mutations."""
    },
    {
        "milestone": 17,
        "title": "sec(auth): deprecate single flat INTERNAL_SERVICE_TOKEN in favor of per-service SPIFFE/JWT",
        "labels": ["kind/security", "priority/p0-critical", "area/edge-api", "java", "go"],
        "body": """### Summary
All 28 microservices share a single static `INTERNAL_SERVICE_TOKEN` environment variable. A compromise of any single service pod exposes the key, allowing unauthorized lateral movement across the entire platform.

### Evidence & Code Reference
- `services/*/src/main/resources/application.yml` (`INTERNAL_SERVICE_TOKEN`).
- `services/go-shared/pkg/auth/internal.go` (single static token validator).
- ADR-006, ADR-010, ADR-012.

### Blast Radius & Business Impact
Catastrophic privilege escalation: a compromised low-privilege service (e.g. notification-service) can impersonate payment-service or warehouse-service.

### Remediation Plan
1. Introduce per-service JWT tokens with audience (`aud`) and subject (`sub`) scoping.
2. Configure Istio SPIFFE/SPIRE workload identities for automatic mTLS SAN validation.
3. Remove the flat token fallback across all Java and Go shared auth filters."""
    },
    {
        "milestone": 17,
        "title": "sec(mobile-bff): missing mutual TLS and rate-limit degradation circuit breaker for edge token issuance",
        "labels": ["kind/security", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
`mobile-bff-service` token validation chain lacks a circuit-breaker fallback when downstream `identity-service` JWKS endpoint experiences transient latency spikes or network partitions.

### Evidence & Code Reference
- `services/mobile-bff-service/src/main/java/com/instacommerce/bff/security/MobileBffSecurityConfig.java`
- Remote JWKS lookup does not configure resilience fallback or cached key degradation.

### Blast Radius & Business Impact
If identity-service degrades, all mobile ingress requests fail immediately with HTTP 500, causing a platform-wide checkout outage for mobile users.

### Remediation Plan
1. Add in-memory resilient JWKS caching with 24-hour stale-while-revalidate policy.
2. Wrap external JWKS calls with Resilience4j circuit breaker and Prometheus metrics.
3. Add contract test simulating identity-service JWKS endpoint network timeout."""
    },
    {
        "milestone": 17,
        "title": "sec(identity): multi-key JWKS key rotation path and overlapping grace period implementation",
        "labels": ["kind/security", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
`identity-service` exposes only a single RSA key in `/.well-known/jwks.json`. Rotating cryptographic signing keys invalidates all active user sessions and service tokens without a grace period.

### Evidence & Code Reference
- `services/identity-service/src/main/java/com/instacommerce/identity/controller/JwksController.java`
- `JwtKeyLoader.java` loads a single keypair from Secret Manager.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C1-P1-01).

### Blast Radius & Business Impact
Operational mass logout and token rejection across the entire user base during security rotation drills.

### Remediation Plan
1. Update `JwksController` to publish multiple keys identified by versioned `kid` (Key ID).
2. Support a 7-day overlapping rotation window where new tokens sign with key `N` while key `N-1` remains valid for verification.
3. Author automated key rotation runbook and alerting for key expiration."""
    },
    {
        "milestone": 17,
        "title": "sec(auth): remove ROLE_ADMIN overbroad grant in InternalServiceAuthFilter across 20 Java services",
        "labels": ["kind/security", "priority/p0-critical", "area/edge-api", "java"],
        "body": """### Summary
`InternalServiceAuthFilter.java` in Java microservices automatically grants `ROLE_ADMIN` to any caller presenting the internal service header, blurring service authentication with human administrator authorization.

### Evidence & Code Reference
- `services/*/src/main/java/com/instacommerce/*/security/InternalServiceAuthFilter.java`
- Grants `List.of(new SimpleGrantedAuthority(\"ROLE_ADMIN\"))`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C1-P0-01).

### Blast Radius & Business Impact
Internal services can access privileged administrative paths (`/admin/**`), bypass soft deletes, or trigger financial manual overrides.

### Remediation Plan
1. Replace `ROLE_ADMIN` with dedicated `ROLE_INTERNAL_SERVICE` authority.
2. Restrict `/admin/**` endpoints exclusively to `ROLE_SUPER_ADMIN` originating from `admin-gateway-service`.
3. Audit all Spring Security method annotations (`@PreAuthorize`) across the 20 Java services."""
    },
    {
        "milestone": 17,
        "title": "sec(mesh): Istio AuthorizationPolicy namespace-wide DENY default configuration",
        "labels": ["kind/security", "priority/p1-high", "area/infrastructure"],
        "body": """### Summary
The Kubernetes deployment manifests lack a namespace-wide default-deny `AuthorizationPolicy`. Once traffic enters the Istio service mesh, any pod can initiate TCP/HTTP connections to any other pod.

### Evidence & Code Reference
- `deploy/helm/templates/` — Only 2 services contain explicit `AuthorizationPolicy` definitions.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C1-P1-03).

### Blast Radius & Business Impact
Allows unrestricted lateral port scanning and communication between decoupled domain services.

### Remediation Plan
1. Create root `default-deny-all` AuthorizationPolicy in `deploy/helm/templates/security/default-deny.yaml`.
2. Verify Envoy access logs flag all unexpected east-west traffic.
3. Test in dev staging cluster to ensure legitimate gRPC/REST flows have corresponding allow rules."""
    },
    {
        "milestone": 17,
        "title": "sec(mesh): per-service Istio AuthorizationPolicy allowlist definitions for internal traffic",
        "labels": ["kind/security", "priority/p1-high", "area/infrastructure"],
        "body": """### Summary
After applying default-deny, each microservice must declare explicit caller principals (e.g., `checkout-orchestrator-service` -> `inventory-service:8083`).

### Evidence & Code Reference
- `deploy/helm/values.yaml` and service-specific Helm chart templates.

### Blast Radius & Business Impact
Zero-trust network architecture requirement. Prevents unauthorized callers from triggering sensitive APIs.

### Remediation Plan
1. Generate service-to-service communication matrix based on architectural call graphs.
2. Implement declarative `AuthorizationPolicy` templates in Helm with configurable principal lists.
3. Validate policies against end-to-end checkout and fulfillment flows."""
    },
    {
        "milestone": 17,
        "title": "sec(identity): brute-force protection and IP lockout for customer login endpoints",
        "labels": ["kind/security", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
`identity-service` lacks progressive delay or account lockout mechanisms after consecutive failed authentication attempts, relying solely on pod-local rate limiting.

### Evidence & Code Reference
- `services/identity-service/src/main/java/com/instacommerce/identity/service/AuthService.java`
- Does not increment failed login counters in PostgreSQL or Redis.

### Blast Radius & Business Impact
Vulnerability to distributed credential stuffing attacks targeting high-value user accounts.

### Remediation Plan
1. Maintain exponential backoff lockout counter in Redis per `(username, client_ip)`.
2. Lock account for 15 minutes after 5 consecutive failed attempts and emit `AccountLocked.v1.json` event.
3. Provide secure password reset and customer support unlock flow."""
    },
    {
        "milestone": 17,
        "title": "sec(admin-gateway): audit logging for privileged administrative mutations and role overrides",
        "labels": ["kind/security", "priority/p1-high", "area/edge-api", "java"],
        "body": """### Summary
Mutations executed through `admin-gateway-service` (catalog price updates, user bans, manual refund issuances) do not produce immutable audit log entries in `audit-trail-service`.

### Evidence & Code Reference
- `services/admin-gateway-service/src/main/java/com/instacommerce/admin/`
- Missing audit log filter sending event envelopes to `audit-trail-service`.

### Blast Radius & Business Impact
Non-compliance with financial and security audit standards (SOC2, PCI-DSS); inability to investigate insider threats.

### Remediation Plan
1. Add WebFilter in `admin-gateway-service` intercepting all mutating HTTP methods.
2. Publish audit event with administrator identity, IP, target resource, and JSON diff payload.
3. Ingest into Cloud Storage immutable WORM bucket via `audit-trail-service`."""
    },
    {
        "milestone": 17,
        "title": "sec(vault): PII decryption key rotation and GCP Secret Manager integration for AI services",
        "labels": ["kind/security", "priority/p2-medium", "area/ai-ml", "python"],
        "body": """### Summary
`ai-orchestrator-service` uses a local encryption key for PII vault redaction and restoration, without automated rotation or integration with GCP Secret Manager.

### Evidence & Code Reference
- `services/ai-orchestrator-service/app/guardrails/pii_vault.py`
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (AI-P2-07).

### Blast Radius & Business Impact
Risk of PII exposure across LLM prompt logs and Redis checkpoint states.

### Remediation Plan
1. Fetch PII encryption keys from GCP Secret Manager on service startup.
2. Implement key envelope encryption with key version tracking in vault tokens.
3. Automate 90-day rotation drill with backward compatibility for active customer sessions."""
    },
    {
        "milestone": 17,
        "title": "infra(docker): image registry tag mismatch between CI workflows and Helm deployment overlays",
        "labels": ["kind/bug", "priority/p0-critical", "area/infrastructure"],
        "body": """### Summary
`.github/workflows/ci.yml` builds and pushes container images with `:dev` or short commit tags, while `deploy/helm/values-prod.yaml` references an older tag format, breaking GitOps deployment lineage.

### Evidence & Code Reference
- `.github/workflows/ci.yml` line 45-60.
- `deploy/helm/values.yaml` and `deploy/helm/values-dev.yaml`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (Infra-P0-13).

### Blast Radius & Business Impact
ArgoCD sync failures or deployment of outdated container binaries despite successful CI build passes.

### Remediation Plan
1. Standardize on immutable semantic commit SHA tags (`ghcr.io/instacommerce/<service>:<git_sha>`).
2. Implement automated ArgoCD image updater or git commit step updating `values.yaml` during CI release.
3. Validate container digest verification prior to rolling restart."""
    },
    {
        "milestone": 17,
        "title": "infra(helm): dev values reference mutable :dev tags instead of immutable git commit SHAs",
        "labels": ["kind/tech-debt", "priority/p1-high", "area/infrastructure"],
        "body": """### Summary
`deploy/helm/values-dev.yaml` specifies `tag: dev` for all microservices, leading to non-deterministic Kubernetes pod rollouts and inability to roll back to exact commit states.

### Evidence & Code Reference
- `deploy/helm/values-dev.yaml` lines 1-150.

### Blast Radius & Business Impact
Inability to reproduce bugs in staging; silent drift between local development and continuous delivery pipelines.

### Remediation Plan
1. Replace static `:dev` tag placeholders with parameterized CI release tags.
2. Configure image pull policy to `IfNotPresent` with immutable digests.
3. Add Helm lint CI step verifying no chart uses mutable `latest` or `dev` tags."""
    },
    {
        "milestone": 17,
        "title": "infra(compose): Kafka ADVERTISED_LISTENERS inter-container and localhost dual-listener drift",
        "labels": ["kind/bug", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
`docker-compose.yml` Kafka configuration does not cleanly separate internal container network communication from host localhost port forwarding, causing connection timeouts for local microservices.

### Evidence & Code Reference
- `docker-compose.yml` (`KAFKA_ADVERTISED_LISTENERS`).
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (Infra-P2-04).

### Blast Radius & Business Impact
Developer environment friction; services running in docker containers cannot communicate with Kafka alongside host processes.

### Remediation Plan
1. Define dual listeners: `INTERNAL://kafka:9092` and `EXTERNAL://localhost:9093`.
2. Update local `application-local.yml` and Go environment variables to target corresponding listener ports.
3. Add connectivity verification script in `./scripts/test-local-kafka.sh`."""
    },
    {
        "milestone": 17,
        "title": "infra(gitops): missing ArgoCD staging application and values-staging.yaml overlay",
        "labels": ["kind/feature", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
ArgoCD configuration directory contains application manifests for `dev` and `prod`, but lacks a pre-production `staging` environment mirroring production multi-replica topology.

### Evidence & Code Reference
- `argocd/apps/` — contains only `instacommerce-dev.yaml` and `instacommerce-prod.yaml`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (Infra-P2-05).

### Blast Radius & Business Impact
Deploying untested changes directly from dev to prod increases incident rates and customer-facing bugs.

### Remediation Plan
1. Create `deploy/helm/values-staging.yaml` with production-parity resource limits, replicas, and Redis/Postgres topology.
2. Add `argocd/apps/instacommerce-staging.yaml` application manifest.
3. Configure automated CI promotion pipeline: Dev -> Staging -> Production approval gate."""
    },
    {
        "milestone": 17,
        "title": "infra(redis): Redis cluster plaintext communication migration to TLS-enabled endpoints",
        "labels": ["kind/security", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
Redis deployment in Kubernetes and docker-compose operates over unencrypted TCP port 6379, transmitting cached prices, customer cart contents, and feature flags in cleartext.

### Evidence & Code Reference
- `deploy/helm/values.yaml` (Redis connection URLs without rediss:// scheme).
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C1-P2-01).

### Blast Radius & Business Impact
PCI-DSS compliance violation for sensitive cardholder token caching and vulnerability to network packet inspection.

### Remediation Plan
1. Enable TLS on Redis cluster using cert-manager issued certificates.
2. Update Spring Boot `spring.data.redis.ssl=true` across all 8 Redis-dependent Java services.
3. Update Go services using `go-shared/pkg/config` to support rediss:// TLS connections."""
    },
    {
        "milestone": 17,
        "title": "infra(ci): missing path filters for contracts and data-platform in .github/workflows/ci.yml",
        "labels": ["kind/tech-debt", "priority/p1-high", "area/infrastructure"],
        "body": """### Summary
`.github/workflows/ci.yml` does not trigger validation jobs when changes are committed to `contracts/**`, `data-platform/**`, or `ml/**`, allowing broken schemas and pipelines to merge unnoticed.

### Evidence & Code Reference
- `.github/workflows/ci.yml` path filter triggers.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C8-P0-12).

### Blast Radius & Business Impact
Silent breakage of downstream event consumers and analytics data models.

### Remediation Plan
1. Update `ci.yml` path triggers to include `contracts/**`, `data-platform/**`, and `ml/**`.
2. Add dedicated verification jobs for Protobuf compilation, JSON Schema validation, and dbt tests.
3. Require passing contract CI status checks before PR merge."""
    },
    {
        "milestone": 17,
        "title": "infra(ci): flaky container startup timeout in CI integration test workflows",
        "labels": ["kind/bug", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
GitHub Actions runners intermittently fail during Testcontainers bootstrap for PostgreSQL and Kafka due to aggressive 30-second default container wait timeouts.

### Evidence & Code Reference
- `.github/workflows/ci.yml` integration test step logs.

### Blast Radius & Business Impact
Developer velocity drag; false-positive build failures requiring manual PR re-runs.

### Remediation Plan
1. Increase container startup timeout to 120 seconds with exponential health check retries.
2. Pre-pull common Testcontainers base images (`postgres:16-alpine`, `confluentinc/cp-kafka:7.5.0`) in GitHub Actions cache step.
3. Configure Docker daemon memory and swap allocations for runner environment."""
    },
    {
        "milestone": 17,
        "title": "infra(vault): secrets injection via external-secrets operator replacing hardcoded k8s manifests",
        "labels": ["kind/security", "priority/p1-high", "area/infrastructure"],
        "body": """### Summary
Kubernetes Secret manifests in Helm templates rely on base64-encoded strings committed to repository overlays instead of pulling dynamically from cloud secret managers.

### Evidence & Code Reference
- `deploy/helm/templates/secrets.yaml`.

### Blast Radius & Business Impact
Risk of secret leakage in git commit history and inability to rotate database passwords dynamically.

### Remediation Plan
1. Deploy `ExternalSecrets` operator targeting Google Cloud Secret Manager / HashiCorp Vault.
2. Define `SecretStore` and `ExternalSecret` custom resources per microservice namespace.
3. Remove plaintext secret templates from Helm repository."""
    },
    {
        "milestone": 17,
        "title": "infra(helm): missing memory request and limit definitions across 8 Go microservice charts",
        "labels": ["kind/bug", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
Several Go microservices (e.g. `stream-processor-service`, `dispatch-optimizer-service`) lack Kubernetes resource requests and limits, causing node-level OOM kills and pod eviction during traffic bursts.

### Evidence & Code Reference
- `deploy/helm/charts/services/*/values.yaml`.

### Blast Radius & Business Impact
Noisy neighbor problems on GKE worker nodes leading to cascading pod crashes.

### Remediation Plan
1. Benchmark Go service memory footprints under sustained 1,000 req/sec load.
2. Define baseline requests (`128Mi`, `100m`) and limits (`512Mi`, `1000m`) in Helm values.
3. Enable Vertical Pod Autoscaler (VPA) in recommendation mode to tune production requests."""
    },
    {
        "milestone": 17,
        "title": "infra(network): network policies restricting cross-namespace pod egress to Cloud SQL and Kafka",
        "labels": ["kind/security", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
Kubernetes namespaces lack Calico/Cilium `NetworkPolicy` objects, allowing pods to communicate with external IP ranges and unauthorized database ports.

### Evidence & Code Reference
- `deploy/helm/templates/networkpolicies/`.

### Blast Radius & Business Impact
Perimeter defense gap: egress data exfiltration risk from compromised containers.

### Remediation Plan
1. Author default-deny egress `NetworkPolicy` for service namespaces.
2. Whitelist egress only to internal DNS (kube-dns), Kafka broker IPs, and Cloud SQL proxy socket.
3. Verify policy enforcement with automated network penetration tests."""
    },
    {
        "milestone": 17,
        "title": "infra(gke): node pool auto-repair and release-channel alignment for production Kubernetes clusters",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
Terraform GKE cluster module configures rapid release channel and static node pools without automated repair and drain policies, causing uncoordinated node upgrades during peak trading hours.

### Evidence & Code Reference
- `infra/terraform/modules/gke/main.tf`.

### Blast Radius & Business Impact
Spontaneous node reboots disrupting active Temporal workflows and Kafka consumer partition assignments.

### Remediation Plan
1. Pin GKE cluster to `REGULAR` release channel with maintenance exclusion windows during peak trading (18:00 - 23:00 IST).
2. Enable `auto_repair = true` and `auto_upgrade = true` with surge upgrade parameters.
3. Define PodDisruptionBudgets (PDB) guaranteeing minAvailable=1 for all transactional services."""
    },
    {
        "milestone": 17,
        "title": "infra(logging): JSON log formatting standardization across Go, Java, and Python services",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/observability"],
        "body": """### Summary
Log formats diverge across runtimes: Java services emit Logstash JSON, Go services emit zap text logs, and Python services emit raw string stdout, breaking centralized log ingestion in Cloud Logging.

### Evidence & Code Reference
- `services/*/src/main/resources/logback-spring.xml`
- `services/go-shared/pkg/observability/logger.go`
- `services/ai-*/app/main.py`

### Blast Radius & Business Impact
Inability to query cross-service error traces in Google Cloud Logging using structured filters (`jsonPayload.service`, `jsonPayload.trace_id`).

### Remediation Plan
1. Align ECS / OpenTelemetry JSON logging schema across all 3 language ecosystems.
2. Ensure timestamp is ISO-8601 UTC and severity maps to standard syslog levels.
3. Inject active trace_id and span_id into every log record."""
    },
    {
        "milestone": 17,
        "title": "infra(cert-manager): automated TLS certificate renewal for public edge ingress gateways",
        "labels": ["kind/security", "priority/p1-high", "area/infrastructure"],
        "body": """### Summary
Edge ingress TLS certificates are managed manually via Kubernetes secrets without automated Let's Encrypt / Google Managed Certificate renewal controllers.

### Evidence & Code Reference
- `deploy/helm/templates/ingress.yaml`.

### Blast Radius & Business Impact
Outage risk on certificate expiration resulting in browser SSL security warnings and mobile app connection termination.

### Remediation Plan
1. Deploy `cert-manager` with Cloud DNS ACME challenge solver.
2. Create `Certificate` CRDs with automated 60-day renewal before expiration.
3. Set up Prometheus alert `CertManagerCertificateExpiringSoon` triggering at 14 days."""
    },
    {
        "milestone": 17,
        "title": "infra(terraform): state lock timeout and remote backend encryption key validation",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
Terraform remote state bucket lacks customer-managed KMS key encryption and object versioning retention lifecycle policies.

### Evidence & Code Reference
- `infra/terraform/backend.tf`.

### Blast Radius & Business Impact
Risk of state file corruption or unauthorized inspection of sensitive infrastructure credentials.

### Remediation Plan
1. Configure Cloud Storage bucket versioning and KMS customer-managed encryption key (CMEK).
2. Enable state locking via Google Cloud Storage native locks.
3. Restrict state bucket IAM permissions strictly to CI service accounts."""
    },
    {
        "milestone": 17,
        "title": "infra(cleanup): orphan cloud disk and ephemeral volume automated lifecycle reaper",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
Dynamic PVC creation during Testcontainers runs and developer test clusters leaves unattached Persistent Disks in GCP, accumulating unnecessary cloud infrastructure costs.

### Evidence & Code Reference
- `infra/scripts/gcp-cleanup.sh`.

### Blast Radius & Business Impact
Uncontrolled cloud spending and quota exhaustion in non-production GCP projects.

### Remediation Plan
1. Implement automated Cloud Function or cron job scanning for unattached disks older than 7 days.
2. Label all ephemeral test disks with `ttl` and owner metadata.
3. Send weekly FinOps cost report of deleted resources to platform engineering Slack channel."""
    }
]

WAVE_1_ISSUES = [
    {
        "milestone": 18,
        "title": "fix(checkout): deprecate and remove duplicate CheckoutWorkflow in order-service (dual authority)",
        "labels": ["kind/bug", "priority/p0-critical", "area/checkout-saga", "java"],
        "body": """### Summary
Both `checkout-orchestrator-service` and `order-service` maintain separate `CheckoutWorkflowImpl` definitions with diverging pricing logic. Order service computes prices inline from client-supplied parameters, bypassing promotions and delivery fees.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflowImpl.java` (calls `PricingActivity`).
- `services/order-service/src/main/java/com/instacommerce/order/workflow/CheckoutWorkflowImpl.java` (computes inline price).
- ADR-001 (Checkout Authority) & Issue Register C2-P0-04.

### Blast Radius & Business Impact
Direct financial revenue leakage: malicious or outdated mobile clients hitting order-service directly can dictate zero or manipulated order totals.

### Remediation Plan
1. Deprecate and remove `CheckoutController` and `CheckoutWorkflow` from `order-service`.
2. Route all checkout initiation traffic exclusively through `checkout-orchestrator-service:8091`.
3. Add feature flag `order.checkout.direct-saga.enabled=false` and reject direct checkout calls with HTTP 410 Gone."""
    },
    {
        "milestone": 18,
        "title": "fix(checkout): catch WorkflowExecutionAlreadyStartedException in CheckoutController to prevent 500s",
        "labels": ["kind/bug", "priority/p0-critical", "area/checkout-saga", "java"],
        "body": """### Summary
When a customer clicks checkout multiple times or mobile retries after a network blip, Temporal throws `WorkflowExecutionAlreadyStartedException`. The controller does not handle this, returning an unhandled HTTP 500.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/controller/CheckoutController.java` lines 60-75.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C2-P0-05).

### Blast Radius & Business Impact
Customer frustration and abandonment: users receive an error screen despite their order saga progressing in the background.

### Remediation Plan
1. Catch `WorkflowExecutionAlreadyStartedException` in `CheckoutController`.
2. Query existing workflow execution status or query cache and return HTTP 202 Accepted with existing `workflowId`.
3. Ensure client idempotency key maps to stable Temporal workflow ID."""
    },
    {
        "milestone": 18,
        "title": "fix(payment): append activityId to payment authorization idempotency key to prevent collision",
        "labels": ["kind/bug", "priority/p0-critical", "area/payment-ledger", "java"],
        "body": """### Summary
`PaymentActivityImpl` uses only the `orderId` or `workflowId` as the idempotency key when communicating with payment gateways. If the activity fails transiently and is retried by Temporal, the gateway may reject it or register a collision.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/activity/PaymentActivityImpl.java`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C2-P0-05).

### Blast Radius & Business Impact
Payment authorization failures during saga execution; potential duplicate charge authorization locks.

### Remediation Plan
1. Format payment idempotency key as `\"ord-\" + orderId + \"-auth-\" + activityContext.getActivityId()`.
2. Persist idempotency token in `payment-service` with 60-minute TTL.
3. Validate idempotency headers against external Stripe / Adyen sandbox tests."""
    },
    {
        "milestone": 18,
        "title": "fix(payment-webhook): stripe webhook replay window reduction from 5m to 30s with persistent deduplication",
        "labels": ["kind/bug", "priority/p1-high", "area/payment-ledger", "go"],
        "body": """### Summary
`payment-webhook-service` validates HMAC signatures with a 5-minute replay window, but lacks a persistent deduplication table in PostgreSQL or Redis, allowing replay attacks within the tolerance window.

### Evidence & Code Reference
- `services/payment-webhook-service/handler/stripe.go` lines 45-80.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (C2-P1-04).

### Blast Radius & Business Impact
Double-fulfillment of orders: an attacker intercepting a webhook payload can trigger multiple fulfillment dispatches.

### Remediation Plan
1. Create `processed_webhook_events` table in PostgreSQL with primary key on `(provider, event_id)`.
2. Record incoming webhook IDs within a database transaction before publishing to `payment.webhooks` Kafka topic.
3. Reduce timestamp tolerance window to 30 seconds."""
    },
    {
        "milestone": 18,
        "title": "fix(checkout): temporal saga compensation missing dead letter queue for poison pill cancellations",
        "labels": ["kind/bug", "priority/p0-critical", "area/checkout-saga", "java"],
        "body": """### Summary
If a compensating activity fails repeatedly (e.g. `ReleaseInventoryActivity` fails due to DB deadlock or bug), Temporal retries infinitely without dead-letter alerting or manual intervention queues.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflowImpl.java` compensation block.

### Blast Radius & Business Impact
Stock reserved permanently in inventory, preventing other users from buying available dark store goods.

### Remediation Plan
1. Configure maximum retry count (e.g. 5 attempts) on compensating activities.
2. Route failed compensations to `outbox.relay.dlq` and publish a high-severity PagerDuty alert.
3. Provide an administrative manual compensation override endpoint in `admin-gateway-service`."""
    },
    {
        "milestone": 18,
        "title": "fix(payment): handle partial refund failures without leaving ledger balance in inconsistent state",
        "labels": ["kind/bug", "priority/p1-high", "area/payment-ledger", "java"],
        "body": """### Summary
When an out-of-stock item is removed from an in-flight order, `PaymentRefundActivity` updates the internal payment record even if the external gateway refund API throws an HTTP 502/504 error.

### Evidence & Code Reference
- `services/payment-service/src/main/java/com/instacommerce/payment/service/PaymentService.java` refund method.

### Blast Radius & Business Impact
Accounting balance discrepancies: internal order ledger shows refunded amount, but the customer's credit card was never credited.

### Remediation Plan
1. Wrap gateway refund call in two-phase state transition: `REFUND_PENDING` -> `REFUNDED` or `REFUND_FAILED`.
2. Emit `PaymentRefundFailed.v1.json` event upon final retry failure.
3. Implement automated reconciliation audit between payment table and gateway settlement reports."""
    },
    {
        "milestone": 18,
        "title": "feat(order): order status transition validation rejecting terminal state modifications",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
`order-service` state machine lacks strict runtime enforcement preventing transitions out of terminal states (`CANCELLED`, `FAILED`, `DELIVERED`).

### Evidence & Code Reference
- `services/order-service/src/main/java/com/instacommerce/order/domain/model/OrderStatus.java`.
- `OrderService.java` allows updating status without checking `currentStatus.isTerminal()`.
- Glossary Section 2 Invariants.

### Blast Radius & Business Impact
Zombified orders: a cancelled order can be transitioned back to `PACKING` or `OUT_FOR_DELIVERY` by late-arriving async events.

### Remediation Plan
1. Implement state transition guard: `OrderStatus.canTransitionTo(newStatus)`.
2. Throw `IllegalOrderStateTransitionException` (HTTP 409) if attempting to mutate a terminal order.
3. Add unit test suite asserting transition matrix compliance."""
    },
    {
        "milestone": 18,
        "title": "fix(payment): duplicate webhook processing during payment provider network timeouts",
        "labels": ["kind/bug", "priority/p1-high", "area/payment-ledger", "go"],
        "body": """### Summary
Payment gateway retry logic sends identical authorization webhooks when `payment-webhook-service` takes longer than 2000ms to acknowledge during heavy traffic spikes.

### Evidence & Code Reference
- `services/payment-webhook-service/main.go`.

### Blast Radius & Business Impact
Duplicate payment confirmation events published to Kafka, potentially triggering double loyalty point accrual.

### Remediation Plan
1. Acknowledge HTTP 200 OK immediately after persisting the raw payload to PostgreSQL buffer.
2. Hand off Kafka publication to asynchronous worker goroutine pool.
3. Configure gateway timeout thresholds to 5000ms."""
    },
    {
        "milestone": 18,
        "title": "fix(checkout): pricing activity timeout causes silent order placement with zero total",
        "labels": ["kind/bug", "priority/p0-critical", "area/checkout-saga", "java"],
        "body": """### Summary
In `CheckoutWorkflowImpl`, if `CalculatePriceActivity` times out, a fallback catch block defaults total amount to zero cents instead of aborting the saga.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/activity/PricingActivityImpl.java`.

### Blast Radius & Business Impact
Direct zero-dollar checkout fraud: customers receive free orders during pricing service downtime.

### Remediation Plan
1. Remove zero-dollar fallback in pricing activity; pricing failure must be treated as a fatal saga error.
2. Abort workflow with `OrderPricingFailed` status and release reserved inventory.
3. Emit Prometheus metric `checkout.pricing.failure` with alert trigger."""
    },
    {
        "milestone": 18,
        "title": "fix(payment): authorization void activity missing exponential backoff retry policy",
        "labels": ["kind/bug", "priority/p1-high", "area/payment-ledger", "java"],
        "body": """### Summary
When checkout fails after payment authorization (e.g. inventory reservation failed), `VoidPaymentActivity` executes only once without a Temporal `RetryOptions` backoff configuration.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflowImpl.java`.

### Blast Radius & Business Impact
Card authorizations remain held on customer bank accounts for days, causing customer distress and support complaints.

### Remediation Plan
1. Configure Temporal `RetryOptions` on `VoidPaymentActivity`: initial interval 1s, backoff coefficient 2.0, max attempts 5.
2. If void fails after 5 attempts, enqueue to `payment.void.dlq` for automated operator reconciliation.
3. Add Testcontainers integration test simulating gateway 503 on void."""
    },
    {
        "milestone": 18,
        "title": "feat(order): transactional outbox event emission for OrderCancelled with reason taxonomy",
        "labels": ["kind/feature", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
`order-service` emits a generic `OrderStatusChanged` event on cancellation, missing structured cancellation reason taxonomy (e.g. `CUSTOMER_REQUEST`, `OUT_OF_STOCK`, `PAYMENT_DECLINED`).

### Evidence & Code Reference
- `contracts/src/main/resources/schemas/orders/OrderCancelled.v1.json`.
- `services/order-service/src/main/java/com/instacommerce/order/service/OrderService.java`.

### Blast Radius & Business Impact
Customer support and business intelligence cannot differentiate operational stockouts from customer-driven cancellations.

### Remediation Plan
1. Define `CancellationReason` enum in contracts: `CUSTOMER_REQUEST`, `PAYMENT_FAILED`, `INVENTORY_SHORTAGE`, `ETA_UNACCEPTABLE`, `FRAUD_SUSPECTED`.
2. Populate `OrderCancelled.v1.json` schema with `reasonCode` and `reasonDescription`.
3. Update outbox publisher to emit `OrderCancelled` to `orders.events`."""
    },
    {
        "milestone": 18,
        "title": "fix(checkout): multi-item checkout payload missing validation for zero and negative quantities",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
`CheckoutRequest` DTO in `checkout-orchestrator-service` does not annotate `OrderItemDto.quantity` with `@Min(1)`, allowing zero or negative item quantities to reach inventory reservation.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/dto/CheckoutRequest.java`.

### Blast Radius & Business Impact
Negative quantities can reverse inventory reservations, artificially inflating on-hand stock counts or causing checkout crashes.

### Remediation Plan
1. Add `@NotNull` and `@Min(1)` validation annotations to `OrderItemDto`.
2. Enable `@Valid` on `CheckoutController.createCheckout()` request body.
3. Return HTTP 400 Bad Request with field validation errors in `GlobalExceptionHandler`."""
    },
    {
        "milestone": 18,
        "title": "fix(payment): currency code mismatch between cart pricing and payment gateway charge request",
        "labels": ["kind/bug", "priority/p1-high", "area/payment-ledger", "java"],
        "body": """### Summary
`cart-service` defaults currency to `INR` as string, while `payment-service` assumes `USD` if ISO-4217 currency is omitted in the internal gRPC payload.

### Evidence & Code Reference
- `contracts/src/main/proto/common/v1/money.proto`.
- `services/payment-service/src/main/java/com/instacommerce/payment/service/PaymentService.java`.

### Blast Radius & Business Impact
Catastrophic billing error: charging customer accounts in USD instead of INR (85x overcharge).

### Remediation Plan
1. Enforce non-null ISO-4217 `currency_code` in `Money` Protobuf messages.
2. Throw `IllegalArgumentException` in `payment-service` if currency is absent or unsupported.
3. Validate currency code consistency across Cart -> Pricing -> Payment flow."""
    },
    {
        "milestone": 18,
        "title": "fix(order): concurrent cancel and fulfill race condition leading to orphan rider dispatch",
        "labels": ["kind/bug", "priority/p0-critical", "area/checkout-saga", "java"],
        "body": """### Summary
If a customer cancels an order at the exact millisecond dark store staff clicks 'Order Packed', both transactions commit simultaneously without row-level locking.

### Evidence & Code Reference
- `services/order-service/src/main/java/com/instacommerce/order/service/OrderService.java` lines 120-145.

### Blast Radius & Business Impact
Dark store packs and hands order to rider while customer card is already refunded, causing lost inventory and rider compensation waste.

### Remediation Plan
1. Acquire pessimistic write lock (`SELECT ... FOR UPDATE`) on the `orders` row during both cancellation and fulfillment status updates.
2. Reject cancellation if status has reached `PACKED` with HTTP 409 Conflict.
3. Add concurrency stress test with 50 parallel threads."""
    },
    {
        "milestone": 18,
        "title": "feat(payment): payment capture activity triggering only after physical package verification",
        "labels": ["kind/feature", "priority/p1-high", "area/payment-ledger", "java"],
        "body": """### Summary
Payment is currently captured immediately upon order placement (`PLACED`), requiring manual card reversal fees if warehouse picking reveals out-of-stock items.

### Evidence & Code Reference
- Glossary Section 2: 'Payment is captured upon delivery completion or dispatch per financial policy'.
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflowImpl.java`.

### Blast Radius & Business Impact
Credit card interchange fees on refunds for unfulfillable items; customer dissatisfaction over holding funds.

### Remediation Plan
1. Separate payment workflow into two phases: `AuthorizePayment` (at checkout) and `CapturePayment` (at `PACKED` event).
2. Temporal saga waits for `OrderPacked` signal or dispatches capture activity asynchronously on Kafka event.
3. Capture exact fulfilled total if partial substitutions occurred."""
    },
    {
        "milestone": 18,
        "title": "fix(checkout): temporal client connection pool exhaustion under flash sale spike traffic",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
`WorkflowClient` instance in `checkout-orchestrator-service` uses default gRPC channel settings with max 100 concurrent streams, exhausting connections during marketing push flash sales.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/config/TemporalConfig.java`.

### Blast Radius & Business Impact
HTTP 503 Service Unavailable errors on checkout initiation during peak commercial moments.

### Remediation Plan
1. Configure custom `WorkflowServiceStubsOptions` with HTTP/2 keep-alive, idle timeout 30s, and channel pool size of 8.
2. Implement backpressure rate limiter rejecting excess incoming requests with HTTP 429 and retry-after header.
3. Add load testing script verifying 1,500 checkouts/sec."""
    },
    {
        "milestone": 18,
        "title": "feat(order): dead letter topic routing for corrupted order event payloads",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/checkout-saga", "java"],
        "body": """### Summary
`orders.events` Kafka consumer in `fulfillment-service` and `notification-service` throws unhandled deserialization exceptions on corrupt payloads, stalling partition consumption.

### Evidence & Code Reference
- `services/fulfillment-service/src/main/java/com/instacommerce/fulfillment/listener/OrderEventListener.java`.
- ADR-003: Dead-Letter Topic (DLT) Naming.

### Blast Radius & Business Impact
Head-of-line blocking on Kafka partitions delaying order processing for all subsequent customers.

### Remediation Plan
1. Configure Spring Kafka `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`.
2. Divert failed records to `orders.events.DLT` after 3 failed retries.
3. Alert SRE team when DLT consumer lag exceeds zero."""
    },
    {
        "milestone": 18,
        "title": "fix(payment): payment method token expiry during protracted checkout saga execution",
        "labels": ["kind/bug", "priority/p2-medium", "area/payment-ledger", "java"],
        "body": """### Summary
Payment gateway ephemeral client tokens expire within 60 seconds. If inventory reservation or fraud check experiences queuing delays, authorization fails with `TOKEN_EXPIRED`.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflowImpl.java`.

### Blast Radius & Business Impact
Checkout failure on complex multi-item baskets requiring inventory checks across multiple dark store aisles.

### Remediation Plan
1. Prioritize payment authorization earlier in the saga or use customer recurring vault tokens (`payment_method_id`).
2. Implement client re-authentication challenge webhook if token expiration is detected.
3. Track duration between cart submission and authorization in Prometheus histogram."""
    },
    {
        "milestone": 18,
        "title": "fix(checkout): coupon code validation bypass during order service direct saga trigger",
        "labels": ["kind/bug", "priority/p0-critical", "area/checkout-saga", "java"],
        "body": """### Summary
Direct API calls to `order-service` `/orders` accept pre-calculated discount amounts in the JSON payload without re-verifying coupon validity against `pricing-service`.

### Evidence & Code Reference
- `services/order-service/src/main/java/com/instacommerce/order/controller/OrderController.java`.

### Blast Radius & Business Impact
Promo abuse: users can forge 99% discount payloads through API inspection tools.

### Remediation Plan
1. Remove discount amount field from client order submission payload.
2. Calculate discounts strictly server-side by querying `pricing-service:8089`.
3. Add integration test verifying arbitrary client discount amounts are ignored."""
    },
    {
        "milestone": 18,
        "title": "feat(order): order amendment window constraint preventing changes after picking initiated",
        "labels": ["kind/feature", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
There is no enforcement mechanism preventing customers from modifying basket items once a dark store picker has commenced warehouse picking (`PACKING` state).

### Evidence & Code Reference
- `contracts/src/main/resources/schemas/fulfillment/OrderModified.v1.json`.
- `services/order-service/src/main/java/com/instacommerce/order/service/OrderService.java`.

### Blast Radius & Business Impact
Physical inventory discrepancies: pickers pack the original items while invoice and payments reflect modified basket.

### Remediation Plan
1. Restrict order amendments strictly to `PENDING` and `PLACED` statuses.
2. Return HTTP 422 Unprocessable Entity with error code `ORDER_ALREADY_PACKING`.
3. Inform mobile client via WebSocket when amendment window closes."""
    },
    {
        "milestone": 18,
        "title": "fix(payment): double-entry ledger imbalance detection during asynchronous settlement reconciliation",
        "labels": ["kind/bug", "priority/p1-high", "area/payment-ledger", "go"],
        "body": """### Summary
`reconciliation-engine` detects unbalanced ledger entries but logs them as warnings without triggering an operational alert or automated discrepancy investigation workflow.

### Evidence & Code Reference
- `services/reconciliation-engine/pkg/reconciliation/engine.go`.
- ADR-014 (Reconciliation Authority).

### Blast Radius & Business Impact
Cumulative financial accounting drift between bank deposits, payment gateway statements, and internal database ledgers.

### Remediation Plan
1. Emit `reconciliation.events` with discrepancy details whenever debits do not match credits (`sum != 0`).
2. Enforce zero-tolerance automated alert threshold (`DiscrepancyAmountCents > 0`).
3. Freeze automated supplier payouts for affected dark store accounts pending review."""
    },
    {
        "milestone": 18,
        "title": "fix(checkout): workflow history size explosion on repeated payment gateway retries",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
Temporal workflow history exceeds recommended 10,000 events limit if payment authorization activities enter a tight retry loop against a degraded gateway endpoint.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflowImpl.java`.

### Blast Radius & Business Impact
Temporal server database bloat and workflow task execution timeouts, resulting in frozen checkout workflows.

### Remediation Plan
1. Cap activity retries to 5 in `ActivityOptions`.
2. Move high-frequency gateway polling to an external worker activity rather than workflow loop.
3. Set workflow execution timeout to 10 minutes."""
    },
    {
        "milestone": 18,
        "title": "feat(order): automated customer notification dispatch on saga compensation failure",
        "labels": ["kind/feature", "priority/p2-medium", "area/checkout-saga", "java"],
        "body": """### Summary
When an order saga fails and cannot be compensated cleanly, the customer app receives no push notification explaining the delay or refund timeline.

### Evidence & Code Reference
- `services/notification-service/src/main/java/com/instacommerce/notification/consumer/OrderEventConsumer.java`.

### Blast Radius & Business Impact
Customer confusion and influx of manual inbound customer support inquiries.

### Remediation Plan
1. Listen for `OrderFailed.v1.json` and compensation failure events in `notification-service`.
2. Dispatch SMS and push notification: 'We could not complete your order. Any reserved funds will be credited within 24 hours.'
3. Log notification delivery tracking ID in order history."""
    },
    {
        "milestone": 18,
        "title": "fix(payment): gateway timeout misinterpreted as payment failure instead of pending confirmation",
        "labels": ["kind/bug", "priority/p0-critical", "area/payment-ledger", "java"],
        "body": """### Summary
`PaymentService` catches HTTP read timeouts from Stripe/Adyen and immediately records the transaction as `FAILED`, triggering saga cancellation even though the gateway successfully charged the card.

### Evidence & Code Reference
- `services/payment-service/src/main/java/com/instacommerce/payment/gateway/StripePaymentGateway.java`.

### Blast Radius & Business Impact
Customer is charged on their bank card, but their grocery order is cancelled (double debit without goods).

### Remediation Plan
1. Map read timeouts to `PENDING_CONFIRMATION` status instead of `FAILED`.
2. Query gateway transaction status endpoint (`/v1/charges/{id}`) with exponential backoff before failing.
3. If status remains unknown after 60s, trigger explicit gateway void request."""
    },
    {
        "milestone": 18,
        "title": "fix(checkout): missing tenant context propagation in Temporal workflow child activities",
        "labels": ["kind/bug", "priority/p2-medium", "area/checkout-saga", "java"],
        "body": """### Summary
Multi-tenant headers (`X-Tenant-Id`, `X-Store-Id`) present in HTTP ingress are lost when `CheckoutWorkflow` triggers asynchronous child activities, causing queries to hit default tenant schema.

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/interceptor/TemporalHeaderInterceptor.java`.

### Blast Radius & Business Impact
Data isolation leak in multi-tenant or multi-region dark store deployments.

### Remediation Plan
1. Implement Temporal `WorkflowContext` header propagator copying MDC and HTTP headers into workflow metadata.
2. Restore tenant context in child activity thread locals before executing database operations.
3. Add unit test asserting tenant header round-trip across workflow activity boundary."""
    },
    {
        "milestone": 18,
        "title": "feat(order): distributed lock on order entity during concurrent checkout status updates",
        "labels": ["kind/bug", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
Simultaneous events from Kafka consumers (`PaymentCaptured`, `RiderAssigned`) and REST APIs can trigger concurrent updates to the same `orders` row in PostgreSQL, causing Hibernate optimistic locking exceptions (`StaleObjectStateException`).

### Evidence & Code Reference
- `services/order-service/src/main/java/com/instacommerce/order/service/OrderService.java`.

### Blast Radius & Business Impact
Unhandled HTTP 500 errors and discarded Kafka events requiring manual database patching.

### Remediation Plan
1. Use Redis distributed lock (`redisson.getLock(\"order:\" + orderId)`) or PostgreSQL advisory lock for the duration of state update.
2. Retry transaction up to 3 times on `OptimisticLockingFailureException`.
3. Measure lock contention duration via Micrometer timer."""
    }
]

print(f"Loaded {len(WAVE_0_ISSUES)} Wave 0 issues and {len(WAVE_1_ISSUES)} Wave 1 issues.")
