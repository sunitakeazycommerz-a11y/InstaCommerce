# Platform Security & Trust Boundaries — Implementation Guide

**Scope:** InstaCommerce polyglot microservice mesh (28 services: Java/Spring Boot, Go, Python/FastAPI)
**As-of:** Iter 3 deep inspection
**Audience:** Platform engineering, security leads, on-call SREs

---

## Table of Contents

1. [Current State Summary](#1-current-state-summary)
2. [Trust Boundary Map](#2-trust-boundary-map)
3. [Identity Service & JWT Architecture](#3-identity-service--jwt-architecture)
4. [mTLS / Istio Peer Authentication](#4-mtls--istio-peer-authentication)
5. [Istio Authorization Policies — Gap Analysis & Hardening](#5-istio-authorization-policies--gap-analysis--hardening)
6. [Shared-Token Risk: The `INTERNAL_SERVICE_TOKEN` Problem](#6-shared-token-risk-the-internal_service_token-problem)
7. [Admin Access Security](#7-admin-access-security)
8. [Service-to-Service Auth Patterns](#8-service-to-service-auth-patterns)
9. [Secret Management](#9-secret-management)
10. [PII / PCI Scoping](#10-pii--pci-scoping)
11. [Rollout Sequencing for Hardening](#11-rollout-sequencing-for-hardening)
12. [Appendix: Concrete YAML / Code Recipes](#12-appendix-concrete-yaml--code-recipes)

---

## 1. Current State Summary

### What is working well

| Control | Implementation | Status |
|---|---|---|
| Pod hardening | `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, `capabilities.drop: ALL`, `seccompProfile: RuntimeDefault` | ✅ Solid |
| mTLS in-mesh | `PeerAuthentication` STRICT mode namespace-wide | ✅ Correct |
| JWT signing | RSA-256, private key in GCP Secret Manager (`sm://jwt-rsa-private-key`) | ✅ Correct |
| Refresh-token storage | SHA-256 hash stored, plaintext never persisted | ✅ Correct |
| Rate limiting | Resilience4j per-IP on `/auth/login` (5/min) and `/auth/register` (3/min) | ✅ Present |
| Account lockout | 10 failed attempts → 30-min lock, tracked in `users` table | ✅ Present |
| Secret manager | All DB passwords and JWT keys via `sm://` prefix at startup | ✅ Correct |
| Webhook signatures | HMAC-SHA256 for Stripe/Razorpay/PhonePe with replay tolerance | ✅ Correct |
| Workload Identity | GKE configured with `workload_pool = "${project_id}.svc.id.goog"` | ✅ Enabled |
| CloudSQL | Private IP only (`ipv4_enabled: false`), no public endpoint | ✅ Correct |
| Response security headers | HSTS, X-Frame-Options, CSP, X-Content-Type-Options via EnvoyFilter | ✅ Present |

### Critical gaps (prioritised)

| # | Gap | Severity | Blast radius |
|---|---|---|---|
| G1 | Single flat `INTERNAL_SERVICE_TOKEN` shared across all ~28 services | 🔴 Critical | Full mesh on token compromise |
| G2 | `InternalServiceAuthFilter` (Java) uses `String.equals()` — timing attack | 🔴 Critical | Auth bypass via timing oracle |
| G3 | `InternalServiceAuthFilter` grants `ROLE_ADMIN` to every internal caller | 🔴 Critical | Any service can invoke admin endpoints |
| G4 | No namespace-wide Istio `DENY` default; only 2 services have `AuthorizationPolicy` | 🔴 Critical | Any pod can reach any internal service post-mTLS |
| G5 | `admin-gateway-service` has zero application-layer auth | 🔴 Critical | Admin routes accessible to any mesh-authenticated caller |
| G6 | `RequestAuthentication` only covers 4 of 28 services at Istio layer | 🟠 High | JWT validation not enforced mesh-wide |
| G7 | `DestinationRule` does not set `tls.mode: ISTIO_MUTUAL` | 🟠 High | Downstream TLS mode underdefined |
| G8 | Redis uses port 6379 (plaintext) — no TLS config in Helm values | 🟠 High | Cache data in cleartext within VPC |
| G9 | Default fallback token `dev-internal-token-change-in-prod` | 🟠 High | Known-default reachable if `INTERNAL_SERVICE_TOKEN` unset |
| G10 | `mobile-bff-service` has no auth — no Security config class | 🟡 Medium | BFF can forward unauthenticated requests downstream |
| G11 | `NetworkPolicy` allows all pods (`podSelector: {}`) in namespace | 🟡 Medium | Lateral movement within namespace |
| G12 | AI services (`ai-orchestrator-service`, `ai-inference-service`) not covered by any `AuthorizationPolicy` | 🟡 Medium | LLM endpoints reachable from any internal service |

---

## 2. Trust Boundary Map

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  INTERNET (untrusted)                                                        │
│                                                                              │
│  Mobile App / Browser          External PSPs (Stripe, Razorpay, PhonePe)    │
└──────────┬─────────────────────────────────────────┬────────────────────────┘
           │ HTTPS/TLS (SIMPLE termination)           │ HTTPS webhook callbacks
           ▼                                           ▼
┌──────────────────────────────┐        ┌─────────────────────────────────┐
│  BOUNDARY 1: Istio Ingress   │        │  BOUNDARY 2: Webhook Ingress    │
│  Gateway (TLS SIMPLE)        │        │  payment-webhook-service        │
│  Hosts: api, m, admin        │        │  HMAC-SHA256, replay tolerance  │
│  .instacommerce.dev          │        └────────────┬────────────────────┘
└──────────┬───────────────────┘                     │
           │ VirtualService routing                   │ Kafka publish
           ▼                                          ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  BOUNDARY 3: Internal Mesh (Istio mTLS STRICT, SPIFFE identities)           │
│                                                                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────────┐    │
│  │ mobile-bff      │  │ admin-gateway   │  │ identity-service         │    │
│  │ (public edge)   │  │ (admin edge) ❌ │  │ JWKS, /auth, /admin      │    │
│  │ ⚠️ no auth      │  │ ⚠️ no auth      │  │ JWT RS256, rate-limited   │    │
│  └────────┬────────┘  └────────┬────────┘  └──────────────────────────┘    │
│           │                    │                                              │
│           └────────────────────┼──────────────────────────────┐             │
│                                │                               │             │
│  ┌─────────────────────────────▼──────────────────────────┐   │             │
│  │ Domain Services (JWT + shared-token dual auth)         │   │             │
│  │ order, payment, inventory, fulfillment, cart,          │   │             │
│  │ checkout-orchestrator, pricing, wallet, fraud, ...     │   │             │
│  └────────────────────────────────────────────────────────┘   │             │
│                                                                 │             │
│  ┌──────────────────────────────────────────────────────────┐ │             │
│  │ Go Pipeline Services (shared-token only)                 │ │             │
│  │ cdc-consumer, outbox-relay, dispatch-optimizer,          │ │             │
│  │ location-ingestion, reconciliation-engine                │ │             │
│  └──────────────────────────────────────────────────────────┘ │             │
│                                                                 │             │
│  ┌──────────────────────────────────────────────────────────┐ │             │
│  │ AI Services (FastAPI, NO auth currently)                 │◄┘             │
│  │ ai-orchestrator-service, ai-inference-service            │               │
│  └──────────────────────────────────────────────────────────┘               │
└──────────────────────────────────────────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  BOUNDARY 4: Data Layer (GCP-managed, private IP only)                      │
│  CloudSQL PostgreSQL (Private IP)  │  Memorystore Redis (plaintext ⚠️)      │
│  GCP Secret Manager                │  Kafka (mTLS within GKE)               │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Boundary definitions

| Boundary | Enforcement mechanism | Current gaps |
|---|---|---|
| B1: Internet → Ingress | Istio Gateway TLS SIMPLE; rate-limiting on identity | No WAF; no DDoS mitigation at this layer |
| B2: PSP → Webhook | HMAC-SHA256 with replay protection | Correct; no gaps |
| B3: Internal mesh | Istio mTLS STRICT + shared token (app layer) | No deny-default; sparse AuthzPolicies; admin-gateway has no app auth |
| B4: Services → Data | Private IP Cloud SQL; Workload Identity for Secret Manager | Redis plaintext; no per-service DB user |

---

## 3. Identity Service & JWT Architecture

### Token model

```
Access Token:  RS256 JWT, 15-minute TTL
               Claims: sub (user UUID), roles[], aud="instacommerce-api",
                       iss="instacommerce-identity", jti (UUID), kid
               Signed with: RSA private key from sm://jwt-rsa-private-key

Refresh Token: 64-byte CSPRNG (SecureRandom), URL-safe base64
               Stored: SHA-256 hex in refresh_tokens table
               TTL: 7 days (configurable via IDENTITY_REFRESH_TTL)
               Limit: 5 per user (oldest rotated out)
```

### Key rotation procedure

The `JwtKeyLoader` derives `kid` from SHA-256 of the RSA public key modulus+exponent. Istio `RequestAuthentication` validates against `/.well-known/jwks.json`, which serves the live public key.

**To rotate the RSA keypair without downtime:**

1. Generate a new RSA-2048 (min) or RSA-4096 keypair.
2. Store **both** the old and new public keys in the JWKS endpoint during the transition window.
3. Update `sm://jwt-rsa-private-key` to the new private key; restart `identity-service`.
4. Wait until all valid access tokens (max 15 min TTL) signed with the old key expire.
5. Remove the old public key from the JWKS endpoint.

**Current gap:** The `JwksController` serves a single key. Add multi-key support:

```java
// JwksController — add key rotation buffer
@GetMapping("/jwks.json")
public Map<String, Object> jwks() {
    List<Map<String, Object>> keys = new ArrayList<>();
    keys.add(buildJwk(keyLoader.getCurrentPublicKey(), keyLoader.getCurrentKeyId()));
    keyLoader.getPreviousPublicKey().ifPresent(prevKey ->
        keys.add(buildJwk(prevKey, keyLoader.getPreviousKeyId())));
    return Map.of("keys", keys);
}
```

### JWKS endpoint caching risk

All services that validate JWTs independently (via `DefaultJwtService`) load the public key **at startup from GCP Secret Manager**. They do not poll the JWKS endpoint. Istio `RequestAuthentication` does poll the `jwksUri`, but only for the 4 services that have it configured.

**Action required:** In the target state, all services should validate JWTs solely via Istio `RequestAuthentication` (see §5). Application-layer JWT validation (`DefaultJwtService`) should remain as a defense-in-depth fallback for internal calls that carry user context, but the JWKS URI should be used — not the startup-injected secret — to enable key rotation without service restarts.

### Roles in circulation

```
CUSTOMER   — standard user (default at registration)
ADMIN      — platform operator; access to /admin/** endpoints
PICKER     — warehouse pick-and-pack operator
RIDER      — delivery rider
SUPPORT    — customer support agent
```

**Issue (G3):** `InternalServiceAuthFilter` grants `ROLE_ADMIN` to every internal service call (see §6). This means any service holding the shared token can call `GET /admin/users`, `GET /admin/users/{id}`, etc. on `identity-service` — and the equivalent admin endpoints on all other services.

---

## 4. mTLS / Istio Peer Authentication

### Current configuration

```yaml
# deploy/helm/templates/istio/peer-authentication.yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
spec:
  mtls:
    mode: STRICT
```

This is a namespace-scoped resource (rendered per Helm release) that enforces mutual TLS for all pods in the namespace. Every sidecar-injected pod has a SPIFFE identity of the form:

```
spiffe://cluster.local/ns/<namespace>/sa/<service-account-name>
```

The `serviceaccount.yaml` template creates a dedicated `ServiceAccount` per service, so every service gets a unique SPIFFE URI.

### What mTLS STRICT guarantees

- All inter-pod communication is encrypted and mutually authenticated.
- A pod cannot impersonate another service's identity.
- Traffic from outside the mesh (without a valid cert) is rejected at the sidecar.

### What mTLS alone does NOT guarantee

- It does not restrict **which** service can call **which** other service — that requires `AuthorizationPolicy`.
- Without a deny-default, any sidecar-authenticated pod can reach any other pod in the namespace.
- The SPIFFE cert rotation is automatic (handled by Istio CA), but the application-layer shared token is a long-lived static secret (see §6).

### DestinationRule gap (G7)

Current `DestinationRule` templates configure circuit breaking and connection pooling but do not explicitly set `trafficPolicy.tls.mode: ISTIO_MUTUAL`. While Istio's sidecar-to-sidecar traffic is automatically upgraded to mTLS in STRICT mode, explicit `ISTIO_MUTUAL` in the DestinationRule locks-in the expectation and prevents misconfiguration:

```yaml
# Add to deploy/helm/templates/istio/destination-rule.yaml
trafficPolicy:
  tls:
    mode: ISTIO_MUTUAL
```

---

## 5. Istio Authorization Policies — Gap Analysis & Hardening

### Current coverage

Only 2 services have Istio `AuthorizationPolicy` (both `action: ALLOW` based on SPIFFE principals):

| Service | Permitted callers |
|---|---|
| `payment-service` | `order-service`, `fulfillment-service` |
| `inventory-service` | `order-service`, `fulfillment-service` |

All other ~26 services have no `AuthorizationPolicy`. Without a deny-all default, Istio's implicit behavior is ALLOW for all mesh-authenticated traffic. This means `cdc-consumer-service`, `ai-orchestrator-service`, `reconciliation-engine`, etc. can all reach `payment-service`'s internal endpoints after clearing mTLS.

### Target policy model

Apply a **namespace-wide default deny**, then add per-service allow policies. This is the most important single hardening step.

**Step 1: Namespace-wide deny**

```yaml
# deploy/helm/templates/istio/authz-deny-default.yaml (new file)
{{- if .Values.istio.enabled }}
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: deny-all-default
  # No selector = applies to entire namespace
spec:
  {}  # empty spec with no rules = DENY all
{{- end }}
```

> ⚠️ **Do not apply this until all allow policies are in place** (see rollout plan in §11).

**Step 2: Structured allow policy map**

Define the full allow matrix in Helm values. Below is the authoritative target state based on the actual call graph:

```yaml
# In values.yaml — extend istio.authorizationPolicies
istio:
  authorizationPolicies:

    # Identity: only gateway and BFF need to reach the public auth endpoints.
    # Internal services should use the shared-token path.
    - name: identity-service-allow
      selector: { app: identity-service }
      principals:
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/mobile-bff-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/admin-gateway-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/checkout-orchestrator-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/notification-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/fulfillment-service"

    # Payment: only order, fulfillment, checkout-orchestrator
    - name: payment-service-allow
      selector: { app: payment-service }
      principals:
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/order-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/fulfillment-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/checkout-orchestrator-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/payment-webhook"

    # Inventory: order, fulfillment, checkout-orchestrator, cdc-consumer
    - name: inventory-service-allow
      selector: { app: inventory-service }
      principals:
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/order-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/fulfillment-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/checkout-orchestrator-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/cdc-consumer"

    # Order: checkout-orchestrator, fulfillment, reconciliation-engine
    - name: order-service-allow
      selector: { app: order-service }
      principals:
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/checkout-orchestrator-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/fulfillment-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/reconciliation-engine"

    # Notification: any domain service may trigger notifications
    - name: notification-service-allow
      selector: { app: notification-service }
      principals:
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/order-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/fulfillment-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/identity-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/wallet-loyalty-service"

    # Fraud: checkout-orchestrator, order-service, payment-service
    - name: fraud-detection-service-allow
      selector: { app: fraud-detection-service }
      principals:
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/checkout-orchestrator-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/order-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/payment-service"

    # AI services: only internal orchestration callers (not public edge)
    - name: ai-orchestrator-service-allow
      selector: { app: ai-orchestrator-service }
      principals:
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/mobile-bff-service"
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/fraud-detection-service"

    - name: ai-inference-service-allow
      selector: { app: ai-inference-service }
      principals:
        - "cluster.local/ns/{{ .Release.Namespace }}/sa/ai-orchestrator-service"

    # Admin gateway: only from ingress gateway (Istio ingress principal)
    - name: admin-gateway-allow
      selector: { app: admin-gateway-service }
      principals:
        - "cluster.local/ns/istio-system/sa/istio-ingressgateway-service-account"
```

**Step 3: Update the Helm template to support path-level conditions**

The current `authorization-policy.yaml` template only supports `source.principals`. Extend it to optionally add `operation.paths` for the admin gateway to restrict which URI prefixes are reachable:

```yaml
# In the loop in authorization-policy.yaml, add optional paths filter:
{{- if .paths }}
      to:
        - operation:
            paths:
              {{- range .paths }}
              - {{ . | quote }}
              {{- end }}
{{- end }}
```

---

## 6. Shared-Token Risk: The `INTERNAL_SERVICE_TOKEN` Problem

### Current implementation

Every service (Java and Go) authenticates internal calls with two headers:

```
X-Internal-Service: <calling-service-name>
X-Internal-Token:   <INTERNAL_SERVICE_TOKEN>
```

The token is a single shared secret injected as the env var `INTERNAL_SERVICE_TOKEN`. The default fallback value is `dev-internal-token-change-in-prod`.

**Java filter uses non-constant-time comparison (G2):**

```java
// InternalServiceAuthFilter.java — VULNERABLE
if (serviceName != null && token != null && token.equals(expectedToken)) {
```

`String.equals()` is not constant-time. An attacker with many concurrent requests and statistical timing analysis can infer the correct token character-by-character, bypassing the auth layer without brute force.

**Java filter grants ROLE_ADMIN (G3):**

```java
List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"),
        new SimpleGrantedAuthority("ROLE_ADMIN"))  // ← too broad
```

This means any service that presents the shared token — `notification-service`, `search-service`, `cdc-consumer`, etc. — is treated as an admin user.

### Fix 1: Immediate — constant-time comparison in all Java filters

Every `InternalServiceAuthFilter` across all services needs this change:

```java
// Replace token.equals(expectedToken) with:
import java.security.MessageDigest;

private static boolean safeEquals(String a, String b) {
    if (a == null || b == null) return false;
    byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
    byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(aBytes, bBytes);
}
```

`MessageDigest.isEqual()` is constant-time in the JDK (uses `java.security.MessageDigest.isEqual` which is documented as a constant-time comparison since Java 8).

### Fix 2: Remove `ROLE_ADMIN` from internal service principal

The internal service principal should have `ROLE_INTERNAL_SERVICE` only. Admin operations called by one service on behalf of another (e.g., fulfillment-service reading user data from identity-service) should use service-specific roles:

```java
// Replace the ROLE_ADMIN grant:
List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"))
```

Then update endpoint-level authorization on identity-service's `AdminController` to accept `ROLE_INTERNAL_SERVICE` only for the specific operations internal services legitimately need (e.g., `GET /admin/users/{id}` for fulfillment lookups), keeping human admin operations requiring `ROLE_ADMIN` (which comes from a user JWT).

### Fix 3: Medium-term — per-service token differentiation

The shared-token model's fundamental flaw is a single secret for all services. The target state is per-caller tokens using GCP Secret Manager:

```
sm://internal-token-order-service        → value used only by order-service
sm://internal-token-fulfillment-service  → value used only by fulfillment-service
sm://internal-token-checkout-service     → etc.
```

Receiving services validate incoming tokens against an allowlist keyed by service name:

```java
// InternalServiceAuthFilter — per-caller token validation
private final Map<String, String> allowedServiceTokens;

// Load from Secret Manager at startup:
// allowedServiceTokens = Map.of(
//   "order-service",       env("INTERNAL_TOKEN_ORDER_SERVICE"),
//   "fulfillment-service", env("INTERNAL_TOKEN_FULFILLMENT_SERVICE"),
//   ...
// )

String callingService = request.getHeader("X-Internal-Service");
String token = request.getHeader("X-Internal-Token");
String expected = allowedServiceTokens.get(callingService);
if (expected != null && MessageDigest.isEqual(
        token.getBytes(UTF_8), expected.getBytes(UTF_8))) {
    // Grant only the permissions this specific service needs
    SecurityContextHolder.getContext().setAuthentication(
        buildAuthentication(callingService));
}
```

**Rationale:** With per-caller tokens, compromising `search-service` does not grant access to `payment-service`. Each token can be independently rotated. This is the stepping stone toward full SPIFFE/SVID-based workload identity.

### Fix 4: Long-term — replace with SPIFFE SVIDs / workload identity

Since GKE already has Workload Identity enabled and Istio provides SPIFFE certs, the ultimate target is:

1. Remove `X-Internal-Token` entirely.
2. Have the receiving service validate the caller's SPIFFE identity from the Istio-authenticated `X-Forwarded-Client-Cert` (XFCC) header — or purely at the Istio `AuthorizationPolicy` level (as described in §5).
3. The Go shared `InternalAuthMiddleware` can be retired when the Istio `AuthorizationPolicy` deny-all is in place.

---

## 7. Admin Access Security

### Current state

`admin-gateway-service` is a near-empty stub with no application-layer authentication:

```java
// AdminGatewayController.java
@GetMapping("/admin/v1/dashboard")
public Map<String, Object> dashboard() {
    return Map.of("status", "ok");
}
```

There is no `SecurityConfig`, no JWT validation, no `@PreAuthorize`. The Helm `VirtualService` routes `/admin/v1` directly to `admin-gateway-service`. Anyone who can reach the Istio ingress (i.e., the public internet) can call these endpoints after passing TLS — there is no JWT requirement at either the Istio layer (no `RequestAuthentication` for admin-gateway) or the application layer.

### Required hardening for admin-gateway-service

**Layer 1: Istio RequestAuthentication (add to values.yaml)**

```yaml
requestAuthentications:
  - name: admin-gateway-service-jwt
    selector:
      app: admin-gateway-service
    jwtRules:
      - issuer: instacommerce-identity
        jwksUri: "http://identity-service.{{ .Release.Namespace }}.svc.cluster.local/.well-known/jwks.json"
        audiences:
          - instacommerce-api
        forwardOriginalToken: true
```

**Layer 2: Istio AuthorizationPolicy (require JWT + ROLE_ADMIN)**

Istio can check JWT claims directly:

```yaml
- name: admin-gateway-authz
  selector: { app: admin-gateway-service }
  action: ALLOW
  rules:
    - from:
        - source:
            principals:
              - "cluster.local/ns/istio-system/sa/istio-ingressgateway-service-account"
      when:
        - key: request.auth.claims[roles]
          values: ["ADMIN"]
```

**Layer 3: Application-layer Spring Security (add SecurityConfig)**

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class AdminGatewaySecurityConfig {
    @Bean
    public SecurityFilterChain chain(HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            RestAuthenticationEntryPoint entryPoint) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/error").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

**Layer 4: IP allowlisting at the gateway level**

Limit the `/admin/v1` routes to a known VPN or office CIDR range using an Istio `AuthorizationPolicy` with `remoteIpBlocks`:

```yaml
- name: admin-ip-restrict
  selector: { app: admin-gateway-service }
  action: ALLOW
  rules:
    - from:
        - source:
            remoteIpBlocks:
              - "10.0.0.0/8"       # VPN range
              - "203.0.113.0/24"   # Office egress (example)
```

### Admin routes in identity-service

`identity-service` exposes `/admin/**` protected by `@PreAuthorize("hasRole('ADMIN')")`. Since `InternalServiceAuthFilter` currently grants `ROLE_ADMIN` to all internal services (G3), `admin-gateway-service` → `identity-service` calls bypass the role check entirely once the shared token is presented. After fix G3 is applied:

- Direct user-facing admin calls (via admin-gateway) must carry a valid JWT with `ROLE_ADMIN`.
- Internal service calls to specific user-lookup operations should use a dedicated `ROLE_INTERNAL_SERVICE` endpoint or a separate controller path.

---

## 8. Service-to-Service Auth Patterns

### Go services (go-shared `InternalAuthMiddleware`)

The `services/go-shared/pkg/auth/middleware.go` implementation is more correct than the Java filters:

- Uses `subtle.ConstantTimeCompare` ✅
- Health and metrics endpoints are correctly excluded ✅
- Grants no explicit roles — returns 403/401 without granting ADMIN ✅

**Remaining issues:**
- Still a single shared `INTERNAL_SERVICE_TOKEN` (G1)
- Health endpoints (`/health`, `/health/ready`, `/health/live`, `/metrics`) skip auth entirely — correct for observability, but ensure these are not reachable from the internet (verified via VirtualService: no `/health` route is exposed to external hosts)

### Java services (InternalServiceAuthFilter + InternalServiceAuthInterceptor)

**Inbound** (filter):
- Non-constant-time comparison (fix in §6)
- Grants ROLE_ADMIN (fix in §6)
- Applied before `JwtAuthenticationFilter` — correct; internal service identity is established first

**Outbound** (interceptor):
```java
// InternalServiceAuthInterceptor.java — used by fulfillment, notification, etc.
request.getHeaders().set("X-Internal-Service", serviceName);
request.getHeaders().set("X-Internal-Token", serviceToken);
```

This interceptor is wired at the `RestTemplate` level per-service. **Ensure all `RestTemplate` / `WebClient` beans that make internal calls are configured with this interceptor.** Currently, `fulfillment-service` and `notification-service` have it; auditing other services is required (see §11, Step 3).

### Temporal workflows (checkout-orchestrator-service)

The `checkout-orchestrator-service` calls downstream services (order, payment, inventory, fulfillment) as part of Temporal workflow activities. These calls should flow through the same `InternalServiceAuthInterceptor`. If Temporal activity workers call services directly without the interceptor, those calls will arrive without auth headers and fail once the hardened filter is deployed.

**Action:** Audit `checkout-orchestrator-service` activity implementations to ensure all outbound HTTP clients carry the interceptor.

### Kafka consumers (cdc-consumer, outbox-relay)

Go pipeline services consume from Kafka and may call REST APIs to push state changes. These callers must also inject `X-Internal-Service` / `X-Internal-Token` headers. The `go-shared/pkg/httpclient` client does **not** inject these headers automatically — callers must add them:

```go
// Pattern for Go services making internal HTTP calls:
req.Header.Set(auth.HeaderService, "cdc-consumer-service")
req.Header.Set(auth.HeaderToken, cfg.InternalServiceToken)
resp, err := client.Do(ctx, req)
```

Add a `WithInternalAuth(serviceName, token string) Option` to `go-shared/pkg/httpclient/client.go` that injects these headers automatically on every request:

```go
// In httpclient/client.go
type Client struct {
    // existing fields...
    internalServiceName  string
    internalServiceToken string
}

func WithInternalAuth(name, token string) Option {
    return func(c *Client) {
        c.internalServiceName = name
        c.internalServiceToken = token
    }
}

// In Do(), before executing the request:
if c.internalServiceName != "" {
    req.Header.Set("X-Internal-Service", c.internalServiceName)
    req.Header.Set("X-Internal-Token", c.internalServiceToken)
}
```

---

## 9. Secret Management

### Current implementation

| Secret | Storage | Access mechanism |
|---|---|---|
| JWT RSA private key | GCP Secret Manager: `jwt-rsa-private-key` | `sm://` prefix in Spring Boot, loaded at startup |
| JWT RSA public key | GCP Secret Manager: `jwt-rsa-public-key` | Same |
| DB passwords (per-service) | GCP Secret Manager: `db-password-<service>` | `sm://` prefix; fallback to env var |
| `INTERNAL_SERVICE_TOKEN` | Kubernetes Secret or environment variable | Direct env var injection |
| PSP webhook secrets | GCP Secret Manager (inferred from `sm://` pattern) | Go config loader |
| Redis password | Not configured (port 6379, no auth) | N/A |

### GKE Workload Identity binding (required)

The Terraform `gke` module enables Workload Identity at the cluster level, but **the binding between Kubernetes ServiceAccount and GCP Service Account is not present** in the current Terraform or Helm templates. Without the annotation on the Kubernetes ServiceAccount, pods use the node's service account (too permissive) rather than a per-workload SA.

Add to the Helm `serviceaccount.yaml` template:

```yaml
{{- range $name, $svc := .Values.services }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ $name }}
  annotations:
    iam.gke.io/gcp-service-account: {{ $name }}@{{ $.Values.global.gcpProject }}.iam.gserviceaccount.com
---
{{- end }}
```

And in Terraform `iam/main.tf`, bind each Kubernetes SA to its GCP SA:

```hcl
resource "google_service_account_iam_binding" "workload_identity" {
  for_each           = toset(var.service_accounts)
  service_account_id = google_service_account.service[each.key].name
  role               = "roles/iam.workloadIdentityUser"
  members = [
    "serviceAccount:${var.project_id}.svc.id.goog[${var.namespace}/${each.key}]"
  ]
}
```

Grant only `roles/secretmanager.secretAccessor` to each service account scoped to the secrets that service legitimately needs — not `roles/secretmanager.secretAccessor` at the project level.

### `INTERNAL_SERVICE_TOKEN` — move to Secret Manager

Currently, `INTERNAL_SERVICE_TOKEN` is injected as a plain environment variable in `values.yaml`. It should be sourced from Secret Manager:

```yaml
# In the Helm deployment template, replace plain env with secretKeyRef or
# ExternalSecret (if using external-secrets operator):
- name: INTERNAL_SERVICE_TOKEN
  valueFrom:
    secretKeyRef:
      name: internal-service-token
      key: token
```

Or via the Spring Boot `sm://` mechanism the Java services already support:

```yaml
# In application.yml for each Java service:
internal:
  service:
    token: ${sm://internal-service-token:${INTERNAL_SERVICE_TOKEN:dev-internal-token-change-in-prod}}
```

### Redis in-transit encryption (G8)

Memorystore Redis supports `auth` (password) and `in-transit encryption` via TLS. Currently, the `SPRING_DATA_REDIS_PORT: "6379"` suggests plaintext connections.

Update the Memorystore Terraform module to enable TLS:

```hcl
# In modules/memorystore/main.tf
resource "google_redis_instance" "main" {
  # existing config...
  transit_encryption_mode = "SERVER_AUTHENTICATION"
  auth_enabled            = true
}
```

Update Helm values to use port 6380 (TLS):

```yaml
# values.yaml
SPRING_DATA_REDIS_PORT: "6380"
SPRING_DATA_REDIS_SSL: "true"
```

For Go services using Redis, add TLS config in their Redis client initialization.

### Secret rotation runbook

1. **JWT RSA key:** Follow key rotation procedure in §3.
2. **DB passwords:** Update in Secret Manager; rolling restart of affected service.
3. **Internal service token:** Update in Secret Manager; rolling restart of all services that share it (currently all 28). With per-service tokens (§6, Fix 3), rotation affects only the pair of services sharing that token.
4. **PSP webhook secrets:** Rotate in PSP dashboard first, update in Secret Manager, verify with a test webhook, remove old secret.

---

## 10. PII / PCI Scoping

### PCI-DSS scope

Services that touch cardholder data or payment flows:

| Service | PCI relevance | Risk |
|---|---|---|
| `payment-service` | Authorizes, captures, refunds, voids payments; stores payment intent state | High — cardholder data in DB |
| `payment-webhook-service` | Receives raw PSP webhook payloads; publishes to Kafka | Medium — transient raw payloads |
| `checkout-orchestrator-service` | Orchestrates payment steps; holds payment intent reference | Medium — orchestration state |
| `wallet-loyalty-service` | Wallet balance, transactions; no raw card data | Medium |
| `reconciliation-engine` | Reconciles transactions with PSP data | Low-Medium |

**PCI controls already in place:**
- `payment-service` DB on private CloudSQL — no public IP
- PSP webhook HMAC verification
- `/payments/webhook` endpoint is `permitAll()` — necessary for PSP delivery, correct
- Capture/void/refund endpoints require `ROLE_ADMIN`

**PCI gaps:**

1. **Network segmentation:** All services share the same Kubernetes namespace and `NetworkPolicy` rules. PCI DSS requires cardholder data environment (CDE) to be network-segmented from non-CDE services. Move `payment-service` to a dedicated namespace with strict `NetworkPolicy` allowing only known callers:

```yaml
# Separate namespace: instacommerce-pci
# NetworkPolicy for payment-service:
ingress:
  - from:
      - namespaceSelector:
          matchLabels:
            name: instacommerce-app
        podSelector:
          matchLabels:
            app: order-service  # only this pod
      - namespaceSelector:
          matchLabels:
            name: instacommerce-app
        podSelector:
          matchLabels:
            app: fulfillment-service
```

2. **Audit log for payment operations:** Verify that all state transitions in `payment-service` emit audit events to `audit-trail-service`. The outbox pattern is in place — confirm payment captures, voids, and refunds use it.

3. **No raw card data storage:** Confirm `payment-service` stores only PSP payment intent IDs and amounts, never PANs or CVVs. This should be enforced via column-level assertions in integration tests.

4. **TLS version enforcement:** Add an Istio `EnvoyFilter` to enforce TLS 1.2+ minimum and reject weak ciphers on the payment namespace ingress:

```yaml
# EnvoyFilter for TLS hardening on PCI services
configPatches:
  - applyTo: LISTENER
    match:
      context: GATEWAY
    patch:
      operation: MERGE
      value:
        filter_chains:
          - tls_context:
              common_tls_context:
                tls_params:
                  tls_minimum_protocol_version: TLSv1_2
```

### PII scope

| Service | PII held | Classification |
|---|---|---|
| `identity-service` | email, phone, firstName, lastName, password_hash, IP addresses (audit_log) | High — direct identifiers |
| `notification-service` | email/SMS recipients, message content | High |
| `order-service` | delivery address, order items | Medium |
| `wallet-loyalty-service` | transaction history tied to user UUID | Medium |
| `ai-orchestrator-service` | User prompts (may contain PII) | Medium — PII redactor in place |
| `fulfillment-service` | Picker assignments, delivery addresses | Medium |
| `rider-fleet-service` | Rider GPS location, personal info | Medium |

**PII controls already in place:**
- AI orchestrator has `PiiRedactor` that strips emails, SSNs, card numbers, and phone numbers from log output and LLM inputs.
- Refresh tokens stored as SHA-256 hashes.
- Account deletion support (`UserDeletionService`, `deleted_at` column).

**PII gaps:**

1. **`identity-service` audit_log stores IP addresses** (V5 migration changed to `VARCHAR(45)` for IPv6). Ensure these are subject to the same retention policy as other PII (90-day default cleanup via `AuditLogCleanupJob`). Verify the job runs on schedule via ShedLock metrics.

2. **Email not encrypted at rest in PostgreSQL.** For a high-security posture, consider application-level encryption or PostgreSQL column-level encryption for email/phone using `pgcrypto`. A pragmatic alternative is to ensure CloudSQL at-rest encryption (Google-managed keys are default; customer-managed keys via `disk_encryption_config` in the Terraform module for higher assurance).

3. **Data platform access to PII:** `data-platform/dbt` likely has access to raw tables for analytics. Ensure staging models do not copy PII into BigQuery without masking. Add column-level masking policies in BigQuery for email, phone, and IP fields.

4. **Right to erasure:** The `UserDeletionService` handles soft-delete, but confirm PII is scrubbed from Kafka topics (Kafka doesn't support selective deletion — ensure user events carrying PII are not retained beyond the legal window, e.g., 90 days).

---

## 11. Rollout Sequencing for Hardening

Work through these phases in order. Each phase is designed to be independently deployable and rollback-safe.

### Phase 1: Fix critical timing and privilege bugs (Days 1–3)

No behavioral changes — only correctness fixes. Zero downtime.

1. **[1a] Constant-time comparison in all Java `InternalServiceAuthFilter` instances.**
   Replace `token.equals(expectedToken)` with `MessageDigest.isEqual(...)` in every service:
   `identity-service`, `payment-service`, `pricing-service`, `fulfillment-service`, `notification-service`, `rider-fleet-service`, `routing-eta-service`, `wallet-loyalty-service`, `audit-trail-service`, `order-service`, `checkout-orchestrator-service`, `warehouse-service`, `search-service`, `fraud-detection-service`, `config-feature-flag-service`, `cart-service`, `catalog-service`, `inventory-service`.
   Deploy via rolling update — verify auth still works in dev, then prod.

2. **[1b] Remove `ROLE_ADMIN` from internal service principal.**
   Replace `ROLE_ADMIN` grant in all `InternalServiceAuthFilter` implementations with `ROLE_INTERNAL_SERVICE` only.
   **Before doing this:** audit every `@PreAuthorize` and `.hasRole("ADMIN")` rule that internal services legitimately call, and change those specific endpoint guards to accept `ROLE_INTERNAL_SERVICE` as well (or create a new `ROLE_INTERNAL_SERVICE` path). Key place to check: `identity-service` `/admin/users/{id}` GET, used by fulfillment.

3. **[1c] Remove default fallback token.**
   Change Spring `@Value` default from `dev-internal-token-change-in-prod` to `""` (empty) and make the filter reject all internal calls if `expectedToken` is blank. This forces explicit configuration and eliminates the known-default risk. Ensure `INTERNAL_SERVICE_TOKEN` is set in all environments before deploying.

### Phase 2: Admin gateway hardening (Days 3–5)

1. **[2a] Add `SecurityConfig` and `JwtAuthenticationFilter` to `admin-gateway-service`.**
   Copy the JWT validation infrastructure from `identity-service` or any Java service that already has it. Wire `ROLE_ADMIN` requirement on all admin routes.

2. **[2b] Add `RequestAuthentication` for `admin-gateway-service` to Helm values.**
   Deploy to dev, validate JWT-protected routes, then prod.

3. **[2c] Add `mobile-bff-service` JWT validation.**
   `mobile-bff-service` is a Spring WebFlux (Reactor) service. Add a `SecurityWebFilterChain` with `JwtAuthenticationFilter` (reactive variant). Public routes (`/bff/mobile/v1/home` health analogs) remain permissible; user-specific routes require a valid JWT.

### Phase 3: Extend Istio RequestAuthentication (Days 5–7)

Add Istio `RequestAuthentication` to every service that receives user-originated requests (not just the 4 currently covered). Priority order:

1. `admin-gateway-service`, `mobile-bff-service` (public edge)
2. `order-service`, `cart-service`, `catalog-service`, `search-service` (high-traffic user-facing)
3. `wallet-loyalty-service`, `rider-fleet-service`, `routing-eta-service`
4. `fraud-detection-service`, `audit-trail-service`, `config-feature-flag-service`
5. All remaining Java services

For Go and Python services that do not validate JWTs directly, the Istio `RequestAuthentication` + `AuthorizationPolicy` combination provides the enforcement layer.

### Phase 4: Istio deny-all + AuthorizationPolicy rollout (Days 7–14)

This is the highest-risk phase. Follow this exact sequence:

1. **[4a] Deploy all allow `AuthorizationPolicy` resources in `action: ALLOW` mode** *without* the deny-all. Each policy permits only the callers listed in §5. Test each service's callers in staging.
2. **[4b] Enable deny-all in staging namespace only.** Run the full integration test suite and all smoke tests. Fix any missing caller entries.
3. **[4c] Shadow mode in prod (optional):** Use Istio's `DRY_RUN` mode (Envoy RBAC dry-run) to log would-be denials without actually denying them. Analyze the shadow logs for 24–48 hours.
4. **[4d] Enable deny-all in prod.** Monitor error rates on every service. Have the deny-all policy deletion ready for immediate rollback (delete the `deny-all-default` policy to restore implicit allow).

**Rollback:** `kubectl delete authorizationpolicy deny-all-default -n instacommerce` instantly restores previous behavior.

### Phase 5: Per-service internal tokens (Days 14–28)

Prerequisite: Phase 1 complete.

1. Create one GCP Secret Manager secret per calling service (`sm://internal-token-<service-name>`).
2. Update `InternalServiceAuthFilter` to load a map of `{serviceName → token}` at startup.
3. Deploy to dev, run integration tests for each service pair.
4. Roll out to prod service-by-service (start with lowest-blast-radius: `config-feature-flag-service`, then add others).
5. After all services are on per-service tokens, decommission the old `INTERNAL_SERVICE_TOKEN` secret.

### Phase 6: Redis TLS + PCI namespace isolation (Days 28–42)

1. Enable Memorystore TLS and auth (update Terraform).
2. Update all Redis client configs in Helm values and Java application.yml.
3. Test connection pool behavior under load (TLS adds ~1ms per-connection overhead; pre-warmup connection pools are critical).
4. Begin `payment-service` namespace isolation — create `instacommerce-pci` namespace, migrate `payment-service` and `payment-webhook-service` Helm releases, update `AuthorizationPolicy` principals to reference the new namespace.

### Phase 7: Workload Identity binding completion (Days 28+)

1. Add `iam.gke.io/gcp-service-account` annotations to all Kubernetes ServiceAccounts (§9).
2. Create Terraform Workload Identity bindings per service.
3. Scope Secret Manager access per service (not project-level `secretAccessor`).
4. Remove any environment variable injection of secrets that are now accessible via Workload Identity.

---

## 12. Appendix: Concrete YAML / Code Recipes

### A. Constant-time Java filter (drop-in replacement)

```java
// InternalServiceAuthFilter.java — fixed version
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

private static boolean safeEquals(String a, String b) {
    if (a == null || b == null) return false;
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8),
        b.getBytes(StandardCharsets.UTF_8));
}

// In doFilterInternal:
if (serviceName != null && token != null && safeEquals(token, expectedToken)) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            serviceName, null,
            List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
}
```

### B. Deny-all AuthorizationPolicy (staging gate)

```yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: deny-all-default
  namespace: instacommerce
spec: {}
```

### C. DestinationRule with explicit ISTIO_MUTUAL

```yaml
# Add to destination-rule.yaml template:
trafficPolicy:
  tls:
    mode: ISTIO_MUTUAL
  connectionPool:
    tcp:
      maxConnections: 100
    http:
      http2MaxRequests: 1000
```

### D. Go httpclient with automatic internal auth injection

```go
// services/go-shared/pkg/httpclient/client.go — add to Client struct:
internalServiceName  string
internalServiceToken string

// New option:
func WithInternalAuth(name, token string) Option {
    return func(c *Client) {
        c.internalServiceName = name
        c.internalServiceToken = token
    }
}

// In Do(), before c.httpClient.Do():
if c.internalServiceName != "" && req.Header.Get("X-Internal-Service") == "" {
    req.Header.Set("X-Internal-Service", c.internalServiceName)
    req.Header.Set("X-Internal-Token", c.internalServiceToken)
}
```

### E. RequestAuthentication for all services (Helm snippet)

```yaml
# Add to values.yaml istio.requestAuthentications for every service:
- name: <service-name>-jwt
  selector:
    app: <service-name>
  jwtRules:
    - issuer: instacommerce-identity
      jwksUri: "http://identity-service.{{ .Release.Namespace }}.svc.cluster.local/.well-known/jwks.json"
      audiences:
        - instacommerce-api
      forwardOriginalToken: true
```

### F. Workload Identity ServiceAccount annotation (Helm)

```yaml
# serviceaccount.yaml template addition:
metadata:
  name: {{ $name }}
  annotations:
    iam.gke.io/gcp-service-account: "{{ $name }}@{{ $.Values.global.gcpProject }}.iam.gserviceaccount.com"
```

### G. Memorystore TLS Terraform

```hcl
resource "google_redis_instance" "main" {
  name           = "instacommerce-redis-${var.env}"
  memory_size_gb = var.memory_size_gb
  region         = var.region
  location_id    = "${var.region}-a"
  redis_version  = "REDIS_7_0"
  tier           = "STANDARD_HA"

  authorized_network = var.network_id

  transit_encryption_mode = "SERVER_AUTHENTICATION"
  auth_enabled            = true
}

output "redis_auth_string" {
  value     = google_redis_instance.main.auth_string
  sensitive = true
}
```

---

## Risk Summary

| Gap | Fix | Phase | Effort |
|---|---|---|---|
| G1: Single shared internal token | Per-service tokens (§6, Fix 3) | Phase 5 | Medium |
| G2: Java timing attack | `MessageDigest.isEqual()` (§6, Fix 1) | Phase 1 | Low |
| G3: ROLE_ADMIN granted to internal services | Remove ROLE_ADMIN (§6, Fix 2) | Phase 1 | Low |
| G4: No Istio deny-default | Deny-all + allow policies (§5) | Phase 4 | High |
| G5: Admin gateway no auth | Spring Security + Istio RequestAuthn (§7) | Phase 2 | Medium |
| G6: Sparse RequestAuthentication | Extend to all services (§5) | Phase 3 | Medium |
| G7: No ISTIO_MUTUAL in DestinationRule | Add tls.mode (Appendix C) | Phase 4 | Low |
| G8: Redis plaintext | Memorystore TLS (§9, Appendix G) | Phase 6 | Medium |
| G9: Known default token | Remove fallback default (§6, Phase 1c) | Phase 1 | Low |
| G10: Mobile BFF no auth | Spring WebFlux SecurityFilterChain (§7) | Phase 2 | Medium |
| G11: Overly broad NetworkPolicy | Tighten podSelector (§5) | Phase 4 | Low |
| G12: AI services no AuthzPolicy | Add to allow policy map (§5) | Phase 4 | Low |

**Total estimated engineering effort:** 4–6 weeks for full hardening. Phases 1–2 (the critical fixes) can be completed in one sprint by one engineer.
