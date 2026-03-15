# Edge & Identity — Deep Implementation Guide

**Cluster:** C1 — Edge & Identity  
**Services:** `identity-service`, `mobile-bff-service`, `admin-gateway-service`  
**Iter3 Wave:** Wave 0 (auth hardening) → Wave 1 (BFF & admin hardening) → Wave 6 (per-service edge authz)  
**Review date:** 2026-03-06  
**Author:** Principal Engineering / Security Architect  
**Builds on:**
- `docs/reviews/identity-service-review.md`
- `docs/reviews/api-gateway-bff-design.md`
- `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md`

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Current Implementation Inventory](#2-current-implementation-inventory)
3. [Authentication Flows & Token Lifecycle](#3-authentication-flows--token-lifecycle)
4. [JWKS, Key Management & Rotation](#4-jwks-key-management--rotation)
5. [Rate Limiting — Current State & Production Fix](#5-rate-limiting--current-state--production-fix)
6. [Public / Internal Boundary Control](#6-public--internal-boundary-control)
7. [Mobile BFF Security Chain](#7-mobile-bff-security-chain)
8. [Admin Gateway Isolation & RBAC](#8-admin-gateway-isolation--rbac)
9. [Istio Edge: RequestAuthentication, AuthorizationPolicy & EnvoyFilter](#9-istio-edge-requestauthentication-authorizationpolicy--envoyfilter)
10. [Go Services: JWT Validation via go-shared](#10-go-services-jwt-validation-via-go-shared)
11. [Implementation Steps](#11-implementation-steps)
12. [Validation Checklist](#12-validation-checklist)
13. [Rollback Procedures](#13-rollback-procedures)
14. [Observability & Alerting](#14-observability--alerting)
15. [Open Risks & Follow-Up Work](#15-open-risks--follow-up-work)

---

## 1. Executive Summary

The identity-service has **strong cryptographic fundamentals** — RS256 JWT signing, JWKS endpoint, bcrypt(12), refresh-token rotation, per-IP rate limiting, audit logging, and Debezium outbox. These are genuinely production-ready building blocks.

However, four **critical structural problems** make the current edge unsafe for production at 20M+ users:

| # | Problem | Severity | Impact |
|---|---------|----------|--------|
| 1 | Rate limiting is **pod-local** (Caffeine in-memory per JVM); bypassed under any multi-replica deployment | CRITICAL | Brute-force login succeeds across pods |
| 2 | All internal services share **one flat `INTERNAL_SERVICE_TOKEN`**; any compromised pod can impersonate any other | CRITICAL | Lateral movement; privilege escalation |
| 3 | **Mobile BFF has no security chain** — no JWT filter, no upstream auth, no circuit breaking; is scaffold-level | HIGH | BFF routes bypass all authn/z |
| 4 | **Admin gateway has no dedicated security config** — same JWT/RBAC as consumer API, no network isolation | HIGH | Admin surface is insufficiently hardened |

A fifth concern — no JWT key rotation path — is HIGH severity but has a longer remediation window than the four above.

This guide provides a concrete, reversible implementation path for each problem.

---

## 2. Current Implementation Inventory

### 2.1 identity-service (well-implemented baseline)

| Component | Class / File | Notes |
|-----------|-------------|-------|
| JWT signing | `DefaultJwtService` | RS256, `kid` header, `iss`/`aud`/`jti`/`exp` claims |
| Key loading | `JwtKeyLoader` | PKCS8 private key + X509 public key from GCP Secret Manager via `sm://` |
| JWKS endpoint | `JwksController` | `GET /.well-known/jwks.json` — one key, unprotected, correct |
| Token lifecycle | `AuthService` + `TokenService` | register, login, refresh (rotation), revoke (single), logout (all) |
| Refresh token storage | `refresh_tokens` table | SHA-256 hash stored; 64-byte random raw value; rotation on use |
| Rate limiting | `RateLimitService` | Resilience4j + **Caffeine** — **pod-local, production risk** |
| Account lockout | `AuthService` | 10 failed attempts → 30 min lockout — stored in `users.locked_until` |
| Internal auth | `InternalServiceAuthFilter` | `X-Internal-Service` + `X-Internal-Token` header check |
| Security config | `SecurityConfig` | STATELESS, CSRF off, CORS from `identity.cors.allowed-origins` |
| Outbox events | `OutboxService` + `outbox_events` table | Debezium-relayed; not yet wired to auth events in AuthService |
| Metrics | `AuthMetrics` | Micrometer counters: login success/failure, register, refresh, revoke; timer on login |
| Admin endpoints | `AdminController` | `@PreAuthorize("hasRole('ADMIN')")` — protected, but `ROLE_ADMIN` granted to any internal service via filter |

**Key config (application.yml):**
```yaml
identity:
  token:
    access-ttl-seconds: 900      # 15 min
    refresh-ttl-seconds: 604800  # 7 days
    max-refresh-tokens: 5
  jwt:
    issuer: instacommerce-identity
    public-key:  ${sm://jwt-rsa-public-key}
    private-key: ${sm://jwt-rsa-private-key}
resilience4j.ratelimiter:
  instances:
    loginLimiter:    { limitForPeriod: 5, limitRefreshPeriod: 60s }
    registerLimiter: { limitForPeriod: 3, limitRefreshPeriod: 60s }
```

### 2.2 mobile-bff-service (scaffold-level)

```
src/main/java/com/instacommerce/mobilebff/
  MobileBffServiceApplication.java   — SpringApplication.run
  controller/MobileBffController.java — single stub GET /bff/mobile/v1/home → {"status":"ok"}
```

No security configuration, no JWT filter, no upstream call, no WebFlux security chain. The `INTERNAL_SERVICE_TOKEN` env var is wired but never enforced — there is no filter applying it. Port 8097.

### 2.3 admin-gateway-service (scaffold-level)

```
src/main/java/com/instacommerce/admingateway/
  AdminGatewayServiceApplication.java  — SpringApplication.run
  controller/AdminGatewayController.java — GET /admin/v1/dashboard → {"status":"ok"}
```

No `SecurityConfig`. No JWT validation. No RBAC annotations. Default Spring Security behavior (form login with basic auth). Port 8099.

### 2.4 Go services (go-shared auth)

`services/go-shared/pkg/auth/middleware.go` — `InternalAuthMiddleware`:
- Reads `INTERNAL_SERVICE_TOKEN` from env, compares with constant-time `subtle.ConstantTimeCompare`
- Skips `/health`, `/health/ready`, `/health/live`, `/metrics`
- Same single-token model as Java services — no per-service scoping

### 2.5 Istio / Helm

| Resource | File | Status |
|----------|------|--------|
| Gateway (HTTPS:443) | `templates/istio/gateway.yaml` | Hosts: `api.instacommerce.dev`, `m.instacommerce.dev`, `admin.instacommerce.dev` |
| VirtualService | `templates/istio/virtual-service.yaml` | 24 routes; no timeouts/retries; identity at `/api/v1/auth`, `/api/v1/users` |
| PeerAuthentication | embedded in `authorization-policy.yaml` | STRICT mTLS mesh-wide |
| RequestAuthentication | `templates/istio/request-authentication.yaml` | Only on: payment, order, checkout-orchestrator, inventory |
| AuthorizationPolicy | same file | Only payment + inventory, allow from order/fulfillment SAs |
| Security headers | `templates/istio/security-headers.yaml` | HSTS, X-Frame-Options, CSP — applied globally |
| Rate limiting | **not implemented** | No EnvoyFilter rate limit; no ratelimit-service |

**Critical gap:** No `RequestAuthentication` on `identity-service`, `mobile-bff-service`, or `admin-gateway-service` at the Istio layer. No `AuthorizationPolicy` blocking unauthenticated requests to the admin surface.

---

## 3. Authentication Flows & Token Lifecycle

### 3.1 Current Flow (what exists)

```
Client
  │
  ├─ POST /api/v1/auth/register  → identity-service /auth/register
  │     rate-check (IP, pod-local) → hash pw → save user → issue accessToken(RS256) + refreshToken(64B random)
  │     → return AuthResponse { accessToken, refreshToken, expiresIn, tokenType }
  │
  ├─ POST /api/v1/auth/login     → identity-service /auth/login
  │     rate-check (IP, pod-local) → lockout check → pw verify → reset failed_attempts
  │     → issue accessToken + refreshToken (rotation: old not revoked, new added)
  │     → enforceMaxRefreshTokens (keep newest 5, delete oldest)
  │
  ├─ POST /api/v1/auth/refresh   → identity-service /auth/refresh
  │     hash(refreshToken) → lookup → check revoked/expired/user-active
  │     → revoke old → issue new refreshToken + new accessToken (rotation)
  │
  ├─ POST /api/v1/auth/revoke    → identity-service /auth/revoke  [requires Bearer]
  │     hash(refreshToken) → lookup → verify ownership (currentUserId == token.user)
  │     → mark revoked
  │
  └─ POST /api/v1/auth/logout    → identity-service /auth/logout  [requires Bearer]
        revokeAllActiveByUserId → all refreshTokens set revoked=true
```

**Access token claims (RS256 JWT):**
```json
{
  "iss": "instacommerce-identity",
  "sub": "<user-uuid>",
  "aud": "instacommerce-api",
  "iat": <unix>,
  "exp": <iat + 900>,
  "jti": "<uuid>",
  "roles": ["CUSTOMER"],
  "kid": "<sha256-of-pubkey-modulus>"
}
```

### 3.2 Required Improvements

#### 3.2.1 OTP / Phone-Based Login (CRITICAL — Q-Commerce requirement)

Indian Q-commerce users authenticate >90% via phone OTP. Add to identity-service:

**Migration V11 — login_otps table:**
```sql
CREATE TABLE login_otps (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone       VARCHAR(20) NOT NULL,
    otp_hash    VARCHAR(64) NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_otps_phone ON login_otps (phone) WHERE used = false;
CREATE INDEX idx_login_otps_expiry ON login_otps (expires_at) WHERE used = false;
```

**New endpoints in `AuthController`:**
```java
@PostMapping("/otp/request")  // rate-limited: 3/min per phone
public ResponseEntity<Void> requestOtp(@Valid @RequestBody OtpRequest request)

@PostMapping("/otp/verify")   // rate-limited: 5/15min per phone
public AuthResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest request)
```

**`AuthService.requestOtp` logic:**
1. Normalize phone (E.164 format)
2. `rateLimitService.checkOtpRequest(phone)` — 3 requests per phone per minute
3. Generate 6-digit OTP, SHA-256 hash, store in `login_otps` (TTL 5 min)
4. Send via SMS gateway (MSG91/Twilio) — inject as `SmsGateway` interface for testability
5. If user does not exist, create stub user (phone + `UserStatus.PENDING`)

**`AuthService.verifyOtp` logic:**
1. `rateLimitService.checkOtpVerify(phone)` — 5 attempts per phone per 15 min
2. Load unexpired, unused OTP by phone; compare hash
3. Increment `attempts`; if ≥ 5 mark used and throw `OtpExhaustedException`
4. Mark OTP used; activate user if `PENDING`
5. Issue accessToken + refreshToken as per normal login

#### 3.2.2 JWT Blocklist for Immediate Access Token Revocation (HIGH)

Current logout revokes refresh tokens but access tokens remain valid until 15-min expiry. For stolen-device scenarios:

**Add Redis-backed `jti` blocklist in `AuthService.logout`:**
```java
// After revokeAllActiveByUserId:
String jti = extractJti(currentAccessToken);  // from SecurityContext principal
blocklist.block(jti, Duration.ofSeconds(identityProperties.getToken().getAccessTtlSeconds()));
```

**`JwtAuthenticationFilter.doFilterInternal` — add blocklist check:**
```java
Jws<Claims> jws = jwtService.validateAccessToken(token);
String jti = jws.getPayload().getId();
if (jtiBlocklist.isBlocked(jti)) {
    // respond 401 TOKEN_REVOKED
}
```

**`JtiBlocklistService` implementation:**
```java
@Service
public class JtiBlocklistService {
    private final StringRedisTemplate redis;
    
    public void block(String jti, Duration ttl) {
        redis.opsForValue().set("jti:blocked:" + jti, "1", ttl);
    }
    
    public boolean isBlocked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey("jti:blocked:" + jti));
    }
}
```

This requires Redis (already in `docker-compose.yml` and used by cart/pricing/search services). Add `spring-boot-starter-data-redis` to `identity-service/build.gradle.kts`.

#### 3.2.3 Reduce Failed Login Threshold (MEDIUM)

Change `MAX_FAILED_ATTEMPTS` from 10 → 5 to align with OWASP recommendations. The 30-minute lockout is appropriate.

```java
// AuthService.java
private static final int MAX_FAILED_ATTEMPTS = 5;  // was 10
```

---

## 4. JWKS, Key Management & Rotation

### 4.1 Current State

`JwtKeyLoader` loads a single RSA-2048 key pair at startup from GCP Secret Manager:
- Private key: `sm://jwt-rsa-private-key`
- Public key: `sm://jwt-rsa-public-key` (or derived from private if CRT format)
- `kid` = SHA-256 of (modulus || exponent), Base64URL-encoded
- JWKS endpoint: `GET /.well-known/jwks.json` — serves one key, no auth required

**Problem:** Zero-downtime key rotation is not possible. Rotating the secret requires restarting all pods simultaneously, causing a gap where old tokens (signed with old key) are rejected.

### 4.2 Multi-Key JWKS Architecture

#### Step 1 — Secret Manager versioning convention

Store keys with version suffixes in Secret Manager:
```
jwt-rsa-private-key-v1   (current signing key)
jwt-rsa-public-key-v1
jwt-rsa-public-key-v2    (next key — added before rotation, public only)
jwt-active-kid           (e.g., "v1" — controls which private key signs)
```

#### Step 2 — Refactor `JwtKeyLoader` to multi-key

```java
@Component
public class JwtKeyLoader {
    private final Map<String, RSAPublicKey> publicKeys;   // kid → pubkey
    private volatile String activeKid;
    private volatile RSAPrivateKey signingKey;
    
    // Load all public keys whose Secret Manager key matches pattern jwt-rsa-public-key-*
    // Load private key for activeKid
    // Expose: getActiveKid(), getSigningKey(), getPublicKey(kid), getAllPublicKeys()
    
    @Scheduled(fixedDelay = 21600000)  // refresh every 6 hours
    public void refreshKeys() { ... }
}
```

#### Step 3 — JWKS endpoint serves all active public keys

```java
@GetMapping("/jwks.json")
public Map<String, Object> jwks() {
    List<Map<String,Object>> jwkList = keyLoader.getAllPublicKeys().entrySet().stream()
        .map(e -> buildJwk(e.getKey(), e.getValue()))
        .collect(Collectors.toList());
    return Map.of("keys", jwkList);
}
```

#### Step 4 — `DefaultJwtService` uses active kid for signing, tries all keys for validation

```java
@Override
public Jws<Claims> validateAccessToken(String token) {
    // Extract kid from header without full parse
    String kid = extractKidFromHeader(token);
    RSAPublicKey pubKey = keyLoader.getPublicKey(kid);
    if (pubKey == null) throw new JwtException("Unknown kid: " + kid);
    return Jwts.parser()
        .verifyWith(pubKey)
        .requireIssuer(identityProperties.getJwt().getIssuer())
        .requireAudience("instacommerce-api")
        .build()
        .parseSignedClaims(token);
}
```

#### Step 5 — Rotation procedure (zero-downtime)

```
T-0:  Generate new RSA key pair (2048+ bits)
T-1:  Upload jwt-rsa-public-key-v2 to Secret Manager (public only)
T-2:  All pods reload JWKS every 6h — or trigger manual reload via /actuator/refresh
T-3:  Wait for all pods to include v2 in JWKS response (verify: curl /.well-known/jwks.json)
T-4:  Upload jwt-rsa-private-key-v2 + update jwt-active-kid to "v2"
T-5:  All new tokens signed with v2; all v1 tokens still valid (15-min TTL max)
T+15: v1 tokens expired; remove jwt-rsa-public-key-v1 from JWKS
T+30: Archive jwt-rsa-private-key-v1 as read-only secret version (disabled)
```

Downstream services using Istio `RequestAuthentication` with `jwksUri` will automatically pick up the new key since Istio's Pilot refreshes the JWKS cache periodically (default 20 min; configurable with `PILOT_JWT_PUB_KEY_REFRESH_INTERVAL`).

#### Step 6 — Istio JWKS cache alignment

```yaml
# deploy/helm/templates/istio/pilot-env.yaml (new)
apiVersion: v1
kind: ConfigMap
metadata:
  name: istio-env
  namespace: istio-system
data:
  PILOT_JWT_PUB_KEY_REFRESH_INTERVAL: "300s"  # 5 min refresh
```

This ensures that during key rotation, Istio picks up the new key within 5 minutes, before any old tokens expire.

---

## 5. Rate Limiting — Current State & Production Fix

### 5.1 The Problem

`RateLimitService` uses **Caffeine in-memory per pod**:

```java
private final Cache<String, RateLimiter> limiters = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterAccess(Duration.ofMinutes(5))
    .build();
```

With `identity-service` running 2 pods in dev and 3 in prod, each pod independently tracks `loginLimiter:1.2.3.4`. An attacker can submit 5 login attempts to pod-A, 5 to pod-B, and 5 to pod-C — 15 total attempts vs. the intended 5 per minute. Under Kubernetes load balancing, clients can hit all pods.

### 5.2 Short-term fix — Redis-backed rate limiting (Wave 0)

Replace Caffeine with Redis using Spring Data Redis + `RateLimiter` via Redis scripting (Lua atomic):

**`RedisRateLimitService`:**
```java
@Service
@ConditionalOnProperty("identity.rate-limit.backend", havingValue = "redis")
public class RedisRateLimitService implements RateLimitService {
    private final StringRedisTemplate redis;
    
    // Lua script for atomic increment-with-expiry
    private static final String RATE_LIMIT_SCRIPT = """
        local key = KEYS[1]
        local limit = tonumber(ARGV[1])
        local window = tonumber(ARGV[2])
        local current = redis.call('INCR', key)
        if current == 1 then
            redis.call('EXPIRE', key, window)
        end
        return current
        """;
    
    @Override
    public void checkLogin(String ipAddress) {
        check("login", ipAddress, 5, 60);
    }
    
    @Override
    public void checkRegister(String ipAddress) {
        check("register", ipAddress, 3, 60);
    }
    
    private void check(String operation, String key, int limit, int windowSeconds) {
        String redisKey = "ratelimit:" + operation + ":" + (key == null ? "unknown" : key);
        Long current = redis.execute(
            new DefaultRedisScript<>(RATE_LIMIT_SCRIPT, Long.class),
            List.of(redisKey),
            String.valueOf(limit),
            String.valueOf(windowSeconds));
        if (current != null && current > limit) {
            throw new RateLimitExceededException(operation);
        }
    }
}
```

**application.yml addition:**
```yaml
identity:
  rate-limit:
    backend: ${IDENTITY_RATE_LIMIT_BACKEND:redis}  # or "local" for dev fallback
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

**Feature flag for rollout:**
```yaml
# values-dev.yaml
env:
  IDENTITY_RATE_LIMIT_BACKEND: local  # keep Caffeine in dev; no Redis dependency
# values-prod.yaml
env:
  IDENTITY_RATE_LIMIT_BACKEND: redis
```

### 5.3 Medium-term fix — Envoy global rate limiting at the edge (Wave 1)

Add the `ratelimit-service` Helm templates per `api-gateway-bff-design.md` §3.3, with descriptor-based limits:

```yaml
# deploy/helm/templates/ratelimit/config.yaml
domain: instacommerce
descriptors:
  - key: header_match
    value: auth-login
    rate_limit:
      unit: MINUTE
      requests_per_unit: 50   # gateway-wide, per remote_address
  - key: header_match
    value: auth-register
    rate_limit:
      unit: MINUTE
      requests_per_unit: 20
```

This provides defense-in-depth: Envoy rate limits at the gateway, Redis rate limits at the service. Neither is a single point of failure.

### 5.4 `X-Forwarded-For` handling (related)

Current `RequestContextUtil.resolveIp` reads `X-Forwarded-For` without validating it. An attacker can spoof this header to bypass per-IP limits. Fix:

```java
// RequestContextUtil.java — use the rightmost IP in XFF chain (set by trusted proxy)
// or use X-Real-IP set by Istio/Envoy which cannot be spoofed from outside the mesh
public static String resolveIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null) {
        // Take LAST entry — Istio/Envoy appends the real client IP last
        String[] parts = xff.split(",");
        return parts[parts.length - 1].trim();
    }
    return request.getRemoteAddr();
}
```

---

## 6. Public / Internal Boundary Control

### 6.1 Current shared-token problem

Every service — Java and Go — reads the same `INTERNAL_SERVICE_TOKEN` environment variable. The `InternalServiceAuthFilter` grants `ROLE_INTERNAL_SERVICE` **and** `ROLE_ADMIN` to any caller that presents this token. The Go middleware does the same validation. This means:

- A compromised `payment-webhook-service` pod can call `identity-service /admin/users` with full admin rights
- A bug that leaks `INTERNAL_SERVICE_TOKEN` from one service compromises the entire mesh
- There is no audit trail distinguishing which internal service made a call

### 6.2 Target architecture — Workload Identity (Istio SPIFFE/X.509)

The correct long-term fix (ADR-002 from iter3 review) is to **remove `INTERNAL_SERVICE_TOKEN` entirely** and rely on Istio mTLS SPIFFE certificates for service identity. Each service has a Kubernetes ServiceAccount; Istio mints a certificate with SPIFFE URI `spiffe://cluster.local/ns/<ns>/sa/<sa-name>`. AuthorizationPolicies can then allow/deny by SPIFFE principal.

**Implementation path (Wave 1):**

#### Step 1 — Ensure all services have dedicated ServiceAccounts

Already templated in `deploy/helm/templates/serviceaccount.yaml`. Verify each service has a unique SA name in its Helm values.

#### Step 2 — Define per-service AuthorizationPolicies

```yaml
# deploy/helm/values.yaml — extend authorizationPolicies
authorizationPolicies:
  # identity-service: only allow requests from mobile-bff, admin-gateway, and internal services
  - name: identity-service-internal
    selector:
      app: identity-service
    principals:
      - "cluster.local/ns/{{ .Release.Namespace }}/sa/mobile-bff-service"
      - "cluster.local/ns/{{ .Release.Namespace }}/sa/admin-gateway-service"
      - "cluster.local/ns/{{ .Release.Namespace }}/sa/checkout-orchestrator-service"
      - "cluster.local/ns/{{ .Release.Namespace }}/sa/order-service"
      # add others as needed

  # admin-gateway-service: only reachable via Istio gateway (not from other services)
  - name: admin-gateway-external-only
    selector:
      app: admin-gateway-service
    principals:
      - "cluster.local/ns/istio-system/sa/istio-ingressgateway-service-account"
```

**Note:** AuthorizationPolicy with no `from` source defaults to DENY in STRICT mode. Add a pass-through for health/metrics probes from within the pod (via `localhost` or `127.0.0.1`).

#### Step 3 — Deprecate INTERNAL_SERVICE_TOKEN gradually

```
Phase A: Keep token in all services; add Istio AuthorizationPolicies as a parallel layer
Phase B: Validate that all inter-service calls succeed via mTLS principals
Phase C: Remove InternalServiceAuthFilter from Java services (one at a time, by cluster)
Phase D: Remove auth.InternalAuthMiddleware from Go services
Phase E: Remove INTERNAL_SERVICE_TOKEN from all Helm values and Secret Manager
```

#### Step 4 — Short-term mitigation before workload identity (Wave 0)

Until Workload Identity is fully operational, add per-caller scoping to the token:

```java
// InternalServiceAuthFilter.java — add caller-based role scoping
private static final Map<String, List<String>> SERVICE_ROLES = Map.of(
    "mobile-bff-service",        List.of("ROLE_INTERNAL_SERVICE"),
    "admin-gateway-service",     List.of("ROLE_INTERNAL_SERVICE", "ROLE_ADMIN"),
    "checkout-orchestrator-service", List.of("ROLE_INTERNAL_SERVICE"),
    "order-service",             List.of("ROLE_INTERNAL_SERVICE")
);

// Remove the blanket ROLE_ADMIN grant; only grant ROLE_ADMIN to specific callers
List<String> roles = SERVICE_ROLES.getOrDefault(serviceName,
    List.of("ROLE_INTERNAL_SERVICE"));
```

This is not a full fix but limits blast radius when a service is compromised.

### 6.3 Network-level boundary control

**NetworkPolicy** (in `deploy/helm/templates/network-policy.yaml`):

Add a dedicated NetworkPolicy for `admin-gateway-service` that allows ingress only from the Istio ingress gateway pod:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: admin-gateway-ingress
spec:
  podSelector:
    matchLabels:
      app: admin-gateway-service
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: istio-system
          podSelector:
            matchLabels:
              app: istio-ingressgateway
```

For `identity-service /.well-known/jwks.json`, ensure this endpoint is reachable from within the mesh (for Istio Pilot) but **not** from the public internet:

```yaml
# VirtualService route — no external route to /.well-known on identity-service
# The JWKS endpoint is called by Istio Pilot internally — no public Istio route needed
# If it must be externally reachable for native apps: add a separate route with
# ip-based restrictions via Cloud Armor
```

---

## 7. Mobile BFF Security Chain

### 7.1 What must be implemented

`mobile-bff-service` is currently a 2-class scaffold. For it to be a real security boundary for mobile clients, it needs:

1. **JWT validation filter** — validate Bearer token, reject 401 if missing or invalid
2. **Upstream auth header propagation** — forward `X-User-Id` and `X-User-Roles` to downstream services
3. **Circuit breaking** — protect the BFF from cascading failures in upstream services
4. **Structured security configuration** — `SecurityFilterChain` with WebFlux (reactive) patterns

### 7.2 WebFlux Security Configuration

```java
// src/main/java/com/instacommerce/mobilebff/security/MobileBffSecurityConfig.java
@Configuration
@EnableWebFluxSecurity
public class MobileBffSecurityConfig {
    
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/**").permitAll()
                .pathMatchers("/health", "/health/**").permitAll()
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(jwtDecoder))
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new BffAuthenticationEntryPoint())
                .accessDeniedHandler(new BffAccessDeniedHandler())
            )
            .build();
    }
    
    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${identity.jwks-uri}") String jwksUri) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
            .withJwkSetUri(jwksUri)
            .build();
        // validate issuer and audience
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(),
            new JwtIssuerValidator("instacommerce-identity"),
            jwt -> {
                if (jwt.getAudience().contains("instacommerce-api")) return OAuth2TokenValidatorResult.success();
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience"));
            }
        );
        decoder.setJwtValidator(validators);
        return decoder;
    }
}
```

**`application.yml` additions for BFF:**
```yaml
identity:
  jwks-uri: ${IDENTITY_JWKS_URI:http://identity-service.default.svc.cluster.local/.well-known/jwks.json}
```

### 7.3 Upstream Propagation Filter

```java
// src/.../mobilebff/filter/UserContextPropagationFilter.java
@Component
public class UserContextPropagationFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(Authentication::isAuthenticated)
            .cast(JwtAuthenticationToken.class)
            .flatMap(auth -> {
                Jwt jwt = auth.getToken();
                ServerWebExchange mutated = exchange.mutate()
                    .request(r -> r
                        .header("X-User-Id", jwt.getSubject())
                        .header("X-User-Roles", String.join(",", extractRoles(jwt)))
                        .header("X-Correlation-Id", resolveOrGenerate(exchange))
                    ).build();
                return chain.filter(mutated);
            })
            .switchIfEmpty(chain.filter(exchange));
    }
}
```

### 7.4 Circuit Breaking (WebClient with Resilience4j)

```java
// BFF upstream HTTP calls must use circuit breakers
@Bean
public WebClient identityClient(WebClient.Builder builder, CircuitBreakerRegistry cbr) {
    CircuitBreaker cb = cbr.circuitBreaker("identity-service");
    return builder
        .baseUrl(identityServiceUrl)
        .filter((request, next) ->
            next.exchange(request)
                .transform(CircuitBreakerOperator.of(cb))
        )
        .build();
}
```

---

## 8. Admin Gateway Isolation & RBAC

### 8.1 Required Security Configuration

```java
// src/.../admingateway/security/AdminSecurityConfig.java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class AdminSecurityConfig {
    
    @Bean
    public SecurityFilterChain adminFilterChain(
            HttpSecurity http,
            AdminJwtAuthenticationFilter jwtFilter) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/health/**").permitAll()
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .anyRequest().denyAll()  // default-deny for admin surface
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new AdminAuthEntryPoint())
                .accessDeniedHandler(new AdminAccessDeniedHandler())
            )
            .build();
    }
}
```

**Key differences from consumer API:**
- `anyRequest().denyAll()` — whitelist only, no implicit allow
- Only `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` can access `/admin/**`
- No public endpoints except actuator/health

### 8.2 Admin JWT Validation

Admin JWT tokens should require an additional claim to prevent a stolen consumer token from being replayed against the admin surface:

**Option A — Dedicated admin audience (recommended):**

Add admin-specific audience to tokens issued for admin users:
```java
// DefaultJwtService — for admin users, add "instacommerce-admin" audience
if (user.getRoles().contains("ADMIN")) {
    builder.claim("aud", List.of("instacommerce-api", "instacommerce-admin"));
}
```

Admin gateway validates against `instacommerce-admin` audience:
```java
// AdminJwtAuthenticationFilter
Jwts.parser()
    .verifyWith(publicKey)
    .requireIssuer("instacommerce-identity")
    .requireAudience("instacommerce-admin")  // consumer tokens rejected
    .build()
    .parseSignedClaims(token);
```

**Option B — Separate admin login endpoint:**

`POST /auth/admin/login` — only succeeds if user has `ROLE_ADMIN`; issues token with `aud: instacommerce-admin`. More operational overhead (separate credentials) but cleaner separation.

### 8.3 Admin Route Isolation in Istio

```yaml
# deploy/helm/values.yaml — add admin VirtualService with stricter routing
# Separate VirtualService for admin.instacommerce.dev host
# This host should NOT be in the same VirtualService as api.instacommerce.dev
```

Add an `AuthorizationPolicy` that allows `/admin/**` only from the admin gateway's service account, not directly from the ingress:

```yaml
- name: admin-endpoints-on-identity
  selector:
    app: identity-service
    # path: /admin/**  — Note: AuthorizationPolicy v1beta1 supports path matching
  rules:
    - from:
        - source:
            principals:
              - "cluster.local/ns/{{ .Release.Namespace }}/sa/admin-gateway-service"
      to:
        - operation:
            paths: ["/admin/*"]
```

### 8.4 Admin Audit Logging

Every admin action must be logged. The identity-service already has `AuditService`. Extend admin controllers to emit audit events on every mutating operation:

```java
@DeleteMapping("/users/{id}")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> deleteUser(@PathVariable UUID id, HttpServletRequest request) {
    UUID adminId = userService.getCurrentUserId();
    userDeletionService.deleteUser(id);
    auditService.logAction(adminId, "ADMIN_USER_DELETE", "User", id.toString(),
        Map.of("adminId", adminId.toString()), resolveIp(request), resolveUserAgent(request), traceId(request));
    return ResponseEntity.noContent().build();
}
```

---

## 9. Istio Edge: RequestAuthentication, AuthorizationPolicy & EnvoyFilter

### 9.1 Extend RequestAuthentication to cover the edge

Currently only `payment-service`, `order-service`, `checkout-orchestrator-service`, and `inventory-service` have `RequestAuthentication`. Add for the remaining consumer-facing services:

```yaml
# deploy/helm/values.yaml — additional requestAuthentications
requestAuthentications:
  # ... existing entries ...
  
  - name: catalog-service-jwt
    selector:
      app: catalog-service
    jwtRules:
      - issuer: instacommerce-identity
        jwksUri: "http://identity-service.{{ .Release.Namespace }}.svc.cluster.local/.well-known/jwks.json"
        audiences: ["instacommerce-api"]
        forwardOriginalToken: false

  - name: cart-service-jwt
    selector:
      app: cart-service
    jwtRules:
      - issuer: instacommerce-identity
        jwksUri: "http://identity-service.{{ .Release.Namespace }}.svc.cluster.local/.well-known/jwks.json"
        audiences: ["instacommerce-api"]

  - name: mobile-bff-service-jwt
    selector:
      app: mobile-bff-service
    jwtRules:
      - issuer: instacommerce-identity
        jwksUri: "http://identity-service.{{ .Release.Namespace }}.svc.cluster.local/.well-known/jwks.json"
        audiences: ["instacommerce-api"]

  # Admin gateway uses admin audience
  - name: admin-gateway-service-jwt
    selector:
      app: admin-gateway-service
    jwtRules:
      - issuer: instacommerce-identity
        jwksUri: "http://identity-service.{{ .Release.Namespace }}.svc.cluster.local/.well-known/jwks.json"
        audiences: ["instacommerce-admin"]
```

**Important:** `RequestAuthentication` in Istio does NOT deny requests without a JWT — it only validates JWTs that are present. An `AuthorizationPolicy` with `requestPrincipals: ["*"]` is required to deny unauthenticated requests:

```yaml
- name: require-jwt-on-consumer-services
  selector:
    app: cart-service  # repeat for each protected service
  rules:
    - from:
        - source:
            requestPrincipals: ["*"]  # any valid JWT principal
```

### 9.2 Gateway-level CORS via EnvoyFilter

Replace per-service CORS with a single gateway EnvoyFilter:

```yaml
# deploy/helm/templates/istio/gateway-cors.yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: gateway-cors
  namespace: istio-system
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    - applyTo: HTTP_FILTER
      match:
        context: GATEWAY
        listener:
          filterChain:
            filter:
              name: "envoy.filters.network.http_connection_manager"
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.cors
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.cors.v3.Cors
    - applyTo: ROUTE_CONFIGURATION
      match:
        context: GATEWAY
      patch:
        operation: MERGE
        value:
          cors_policy:
            allow_origin_string_match:
              - safe_regex:
                  regex: "https?://(.*\\.)?instacommerce\\.dev(:[0-9]+)?"
            allow_methods: "GET,POST,PUT,DELETE,PATCH,OPTIONS"
            allow_headers: "Authorization,Content-Type,X-Request-Id,X-Idempotency-Key,X-Correlation-Id"
            allow_credentials: true
            max_age: "3600"
```

### 9.3 Request ID / Correlation ID propagation

Add an EnvoyFilter to inject `X-Correlation-Id` if missing:

```yaml
# deploy/helm/templates/istio/correlation-id.yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: correlation-id-injector
  namespace: istio-system
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    - applyTo: HTTP_FILTER
      match:
        context: GATEWAY
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.lua
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.lua.v3.LuaPerRoute
            inline_code: |
              function envoy_on_request(request_handle)
                if not request_handle:headers():get("x-correlation-id") then
                  request_handle:headers():add("x-correlation-id",
                    string.format("%016x%016x",
                      math.random(0, 0xFFFFFFFF),
                      math.random(0, 0xFFFFFFFF)))
                end
              end
```

### 9.4 Strip internal headers at the gateway

Prevent external clients from injecting `X-Internal-Service`, `X-Internal-Token`, `X-User-Id`, or `X-User-Roles` headers:

```yaml
# deploy/helm/templates/istio/strip-internal-headers.yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: strip-internal-headers
  namespace: istio-system
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    - applyTo: HTTP_FILTER
      match:
        context: GATEWAY
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.lua
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.lua.v3.LuaPerRoute
            inline_code: |
              function envoy_on_request(request_handle)
                request_handle:headers():remove("x-internal-service")
                request_handle:headers():remove("x-internal-token")
                request_handle:headers():remove("x-user-id")
                request_handle:headers():remove("x-user-roles")
              end
```

This is a **critical security control**: without it, an external attacker who knows the internal header names can bypass application-level auth by sending `X-Internal-Service: admin-gateway-service` and `X-Internal-Token: <guessed or leaked token>`.

---

## 10. Go Services: JWT Validation via go-shared

### 10.1 Current state

The Go services (`cdc-consumer-service`, `outbox-relay`, `payment-webhook-service`, `reconciliation-engine`, `dispatch-optimizer-service`, `location-ingestion-service`, `stream-processor`) all use `go-shared/pkg/auth/middleware.go` for **internal service authentication only**. They do not directly handle user JWTs — they receive events or internal calls.

The `auth` package has no JWKS fetching, no JWT parsing, no user-context propagation.

### 10.2 If Go services need to validate user tokens (future)

Add `pkg/auth/jwt.go` to `go-shared`:

```go
// services/go-shared/pkg/auth/jwt.go
package auth

import (
    "context"
    "encoding/json"
    "net/http"
    "sync"
    "time"

    "github.com/lestrrat-go/jwx/v2/jwk"
    "github.com/lestrrat-go/jwx/v2/jwt"
)

type JWTValidator struct {
    jwksURI  string
    issuer   string
    audience string
    cache    jwk.Set
    mu       sync.RWMutex
    lastFetch time.Time
    refreshInterval time.Duration
}

func NewJWTValidator(jwksURI, issuer, audience string) *JWTValidator {
    v := &JWTValidator{
        jwksURI:  jwksURI,
        issuer:   issuer,
        audience: audience,
        refreshInterval: 5 * time.Minute,
    }
    v.refreshKeys(context.Background())
    return v
}

func (v *JWTValidator) Validate(tokenStr string) (jwt.Token, error) {
    v.mu.RLock()
    cache := v.cache
    stale := time.Since(v.lastFetch) > v.refreshInterval
    v.mu.RUnlock()
    
    if stale {
        v.refreshKeys(context.Background())
        v.mu.RLock()
        cache = v.cache
        v.mu.RUnlock()
    }
    
    return jwt.Parse([]byte(tokenStr),
        jwt.WithKeySet(cache),
        jwt.WithIssuer(v.issuer),
        jwt.WithAudience(v.audience),
        jwt.WithValidate(true),
    )
}

func (v *JWTValidator) refreshKeys(ctx context.Context) {
    set, err := jwk.Fetch(ctx, v.jwksURI, jwk.WithHTTPClient(&http.Client{Timeout: 5 * time.Second}))
    if err != nil {
        return  // keep existing cache on fetch failure
    }
    v.mu.Lock()
    v.cache = set
    v.lastFetch = time.Now()
    v.mu.Unlock()
}

// JWTMiddleware validates Bearer tokens for Go HTTP handlers
func JWTMiddleware(validator *JWTValidator, next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        header := r.Header.Get("Authorization")
        if header == "" || len(header) < 8 || header[:7] != "Bearer " {
            http.Error(w, `{"error":"missing token"}`, http.StatusUnauthorized)
            return
        }
        token, err := validator.Validate(header[7:])
        if err != nil {
            http.Error(w, `{"error":"invalid token"}`, http.StatusUnauthorized)
            return
        }
        ctx := context.WithValue(r.Context(), contextKeyUserID, token.Subject())
        next.ServeHTTP(w, r.WithContext(ctx))
    })
}
```

### 10.3 `go-shared` auth package dependency

If a Go service starts performing user-facing JWT validation, add `github.com/lestrrat-go/jwx/v2` to `services/go-shared/go.mod` and re-validate all Go modules (`go test ./...` in each service directory) per the CI instructions.

---

## 11. Implementation Steps

### Wave 0 — Immediate security hardening (2–4 weeks)

These address the two CRITICAL production risks.

#### Step 0.1 — Redis-backed rate limiting

1. Add `spring-boot-starter-data-redis` to `identity-service/build.gradle.kts`
2. Create `RedisRateLimitService` implementing `RateLimitService` interface
3. Extract `RateLimitService` as an interface (currently a concrete class)
4. Add `@ConditionalOnProperty("identity.rate-limit.backend", havingValue = "redis", matchIfMissing = false)` to `RedisRateLimitService`
5. Add `@ConditionalOnProperty("identity.rate-limit.backend", havingValue = "local", matchIfMissing = true)` to existing `RateLimitService` → rename to `LocalRateLimitService`
6. Add `IDENTITY_RATE_LIMIT_BACKEND=local` to `values-dev.yaml`; `redis` to `values-prod.yaml`
7. Update `RequestContextUtil.resolveIp` to use last XFF entry

**Validation:** `./gradlew :services:identity-service:test`
**Rollback flag:** Set `IDENTITY_RATE_LIMIT_BACKEND=local` in prod values; redeploy

#### Step 0.2 — Strip internal headers at the Istio gateway

1. Create `deploy/helm/templates/istio/strip-internal-headers.yaml` (content in §9.4)
2. Gate on `istio.enabled` and new flag `istio.securityHardening.stripInternalHeaders: true`
3. Add to `values.yaml` defaults; `values-prod.yaml` explicit `true`
4. `helm upgrade --dry-run` to validate template rendering
5. Apply to dev cluster; verify with `curl -H "X-Internal-Token: anything" https://api.instacommerce.dev/api/v1/users`

**Validation:** Internal header should be stripped; confirm with Istio access logs
**Rollback:** `helm upgrade` with `istio.securityHardening.stripInternalHeaders: false`

#### Step 0.3 — Reduce MAX_FAILED_ATTEMPTS from 10 to 5

One-line change in `AuthService.java`. No migration required (existing `failed_attempts` counters ≥ 5 will trigger lockout on next login attempt, which is intentional).

**Validation:** Integration test — 5 failed logins → account locked

#### Step 0.4 — Scope InternalServiceAuthFilter roles by caller name

Update `InternalServiceAuthFilter` to use `SERVICE_ROLES` map (see §6.2 Step 4). Remove blanket `ROLE_ADMIN` grant.

**Validation:** Confirm `admin-gateway-service` caller can still access `/admin/**`; confirm other internal callers cannot

### Wave 1 — BFF and admin hardening (4–8 weeks)

#### Step 1.1 — Mobile BFF security chain

1. Add `spring-boot-starter-oauth2-resource-server` to `mobile-bff-service/build.gradle.kts`
2. Add `spring-boot-starter-webflux` (already present per scaffold) and Resilience4j
3. Create `MobileBffSecurityConfig` (see §7.2)
4. Create `UserContextPropagationFilter` (see §7.3)
5. Add `IDENTITY_JWKS_URI` env var to BFF Helm values
6. Create at least one real upstream proxy call (catalog home screen) with circuit breaker

**Validation:** `./gradlew :services:mobile-bff-service:test`; end-to-end test with valid JWT; test with expired JWT → 401

#### Step 1.2 — Admin gateway security configuration

1. Add `spring-boot-starter-security` and JJWT to `admin-gateway-service/build.gradle.kts`
2. Create `AdminSecurityConfig` (see §8.1)
3. Create `AdminJwtAuthenticationFilter` (same pattern as identity-service's `JwtAuthenticationFilter`, but validate `instacommerce-admin` audience)
4. Add admin-audience token issuance in `DefaultJwtService` (see §8.2 Option A)
5. Add Flyway migration in identity-service to add `ADMIN` role to admin users (roles are already VARCHAR[], no schema change needed)
6. Add `IDENTITY_JWKS_URI` to admin gateway Helm values

**Validation:** `./gradlew :services:admin-gateway-service:test`; test with consumer token (should be rejected); test with admin token (should be accepted)

#### Step 1.3 — Istio RequestAuthentication for all consumer services

1. Extend `requestAuthentications` in `values.yaml` (see §9.1)
2. Add corresponding `AuthorizationPolicy` with `requestPrincipals: ["*"]` for each service
3. Apply to dev cluster with `helm upgrade --dry-run` first
4. Validate: unauthenticated request to cart → 401; valid JWT request → passes through

**Important:** Deploy `RequestAuthentication` first (allows existing traffic), then `AuthorizationPolicy` (begins denying unauthenticated traffic). These must be two separate Helm releases or a phased rollout.

#### Step 1.4 — Admin route isolation in Istio

1. Add separate VirtualService section for `admin.instacommerce.dev` host
2. Add `AuthorizationPolicy` blocking `/admin/**` on identity-service from non-admin-gateway callers (see §8.3)
3. Add NetworkPolicy for admin-gateway-service (see §6.3)

#### Step 1.5 — OTP / Phone login

1. Create Flyway migration V11 for `login_otps` table
2. Implement `OtpRequest`, `OtpVerifyRequest` DTOs
3. Implement `SmsGateway` interface + `NoOpSmsGateway` (dev) + `Msg91SmsGateway` (prod)
4. Implement `AuthService.requestOtp` and `AuthService.verifyOtp`
5. Add OTP rate limit keys to `RateLimitService` interface
6. Add Resilience4j config: `otpRequestLimiter` (3/min per phone), `otpVerifyLimiter` (5/15min per phone)
7. Add cleanup job `OtpCleanupJob` (ShedLock, runs every hour, deletes expired/used OTPs)

**Validation:** `./gradlew :services:identity-service:test`; verify OTP TTL expiry; verify lockout after 5 failed verifications

### Wave 6 — Full workload identity (8–16 weeks)

#### Step 6.1 — Workload identity migration

1. Audit all `INTERNAL_SERVICE_TOKEN` usages (Java + Go)
2. Verify each service has a unique, non-default ServiceAccount in Helm
3. Write `AuthorizationPolicy` for each service-to-service call (enumerate allowed callers)
4. Deploy policies in `AUDIT` mode first (`action: AUDIT` instead of `ALLOW`/`DENY`)
5. Observe Istio access logs for unexpected denies for 1 week
6. Switch to `ALLOW`+implicit deny
7. Remove `InternalServiceAuthFilter` from Java services one cluster at a time
8. Remove `auth.InternalAuthMiddleware` from Go services after Java is complete
9. Remove `INTERNAL_SERVICE_TOKEN` from Secret Manager

#### Step 6.2 — JWT key rotation

1. Implement multi-key `JwtKeyLoader` (see §4.2 Step 2)
2. Deploy new JWKS endpoint with multi-key response
3. Configure `PILOT_JWT_PUB_KEY_REFRESH_INTERVAL: 300s`
4. Run first rotation drill in staging
5. Document runbook and add to SRE oncall playbook

---

## 12. Validation Checklist

### Security boundary validation

```bash
# 1. External header stripping — internal headers must be stripped at gateway
curl -v -H "X-Internal-Token: dev-internal-token-change-in-prod" \
     -H "X-Internal-Service: admin-gateway-service" \
     https://api.instacommerce.dev/api/v1/users
# Expected: 401 (header stripped; token not present for internal auth filter)

# 2. Unauthenticated access to protected endpoint after RequestAuthentication + AuthorizationPolicy
curl -v https://api.instacommerce.dev/api/v1/cart
# Expected: 401 RBAC denied or JWT validation failure

# 3. Consumer token rejected by admin gateway
CONSUMER_TOKEN=$(curl -s -X POST https://api.instacommerce.dev/api/v1/auth/login \
  -d '{"email":"user@test.com","password":"..."}' | jq -r .accessToken)
curl -v -H "Authorization: Bearer $CONSUMER_TOKEN" \
     https://admin.instacommerce.dev/admin/v1/dashboard
# Expected: 401 (wrong audience)

# 4. Rate limit enforcement across pods (post-Redis migration)
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST https://api.instacommerce.dev/api/v1/auth/login \
    -d '{"email":"test@test.com","password":"wrong"}'
done
# Expected: first 5 → 401, requests 6-10 → 429

# 5. JWKS endpoint accessible from within mesh (Istio Pilot needs this)
kubectl exec -n default deploy/mobile-bff-service -- \
  curl http://identity-service/.well-known/jwks.json
# Expected: JSON with "keys" array containing at least one RSA key

# 6. Refresh token rotation — old token rejected after refresh
OLD_REFRESH=$(curl -s -X POST .../auth/login | jq -r .refreshToken)
NEW_TOKENS=$(curl -s -X POST .../auth/refresh -d "{\"refreshToken\":\"$OLD_REFRESH\"}")
curl -s -X POST .../auth/refresh \
  -d "{\"refreshToken\":\"$OLD_REFRESH\"}"
# Expected: 401 TOKEN_REVOKED
```

### Service-level tests

```bash
# identity-service unit + integration tests
./gradlew :services:identity-service:test

# mobile-bff-service tests
./gradlew :services:mobile-bff-service:test

# admin-gateway-service tests
./gradlew :services:admin-gateway-service:test

# Go shared auth tests
cd services/go-shared && go test -race ./pkg/auth/...
```

### Metrics validation (post-deploy)

```promql
-- Login failure rate (should spike when testing brute-force, then plateau)
rate(auth_login_total{result="failure"}[5m])

-- Rate limit triggers
rate(resilience4j_ratelimiter_not_permitted_calls_total{name=~".*Limiter.*"}[5m])

-- JWT validation failures at Istio (EnvoyAccessLog)
sum(rate(envoy_cluster_upstream_rq_4xx[5m])) by (cluster_name)
```

### Helm dry-run before any deploy

```bash
helm upgrade --dry-run --install instacommerce ./deploy/helm \
  -f deploy/helm/values.yaml \
  -f deploy/helm/values-dev.yaml \
  --namespace default
```

---

## 13. Rollback Procedures

### Rollback: Redis rate limiting

```bash
# Set env var back to local in Helm values
helm upgrade instacommerce ./deploy/helm \
  -f deploy/helm/values.yaml \
  -f deploy/helm/values-dev.yaml \
  --set 'services.identity-service.env.IDENTITY_RATE_LIMIT_BACKEND=local'

# No database migration to reverse — Caffeine is stateless
```

**Trigger:** P1 alert — Redis connection failures causing login latency > 500ms p99; OR increased error rate on auth endpoints > 1% of requests.

### Rollback: Istio RequestAuthentication + AuthorizationPolicy

```bash
# Remove the new policies — traffic falls back to application-level auth
# Step 1: Remove AuthorizationPolicy (restores unauthenticated access to Istio layer)
kubectl delete authorizationpolicy require-jwt-on-consumer-services -n default

# Step 2: Remove RequestAuthentication (no JWT validation at Istio layer)
kubectl delete requestauthentication catalog-service-jwt cart-service-jwt ... -n default

# Or via Helm: set the affected entries to empty in values.yaml and upgrade
```

**Important:** Always remove `AuthorizationPolicy` before `RequestAuthentication`. Removing `RequestAuthentication` while `AuthorizationPolicy` requires `requestPrincipals: ["*"]` will deny all traffic.

**Trigger:** Any 5xx spike > 0.5% on services that had `RequestAuthentication` added; OR latency increase > 20% p99 (Istio JWT validation adds ~1-2ms per request).

### Rollback: Internal header stripping

```bash
helm upgrade instacommerce ./deploy/helm \
  --set 'istio.securityHardening.stripInternalHeaders=false'
```

**Trigger:** Any service-to-service auth failure that can be traced to stripped headers (check Istio access logs for `X-Internal-Service` header absent on inter-service calls that need it). Note: this should not happen if internal calls stay within the mesh (headers are only stripped at ingress), but verify before rollout.

### Rollback: Admin gateway security config

```bash
# If admin gateway security config causes login loop or access denial for legitimate admins:
# 1. Scale down admin-gateway-service
kubectl scale deploy admin-gateway-service --replicas=0
# 2. Revert to previous image tag
kubectl set image deployment/admin-gateway-service \
  admin-gateway-service=<previous-image>
kubectl scale deploy admin-gateway-service --replicas=2
```

**Trigger:** All admin logins returning 401/403 including confirmed admin users.

### Rollback: JWKS key rotation

```bash
# If new key causes token validation failures:
# 1. Update Secret Manager — set jwt-active-kid back to previous version
gcloud secrets versions add jwt-active-kid --data-file=<(echo -n "v1")

# 2. Restart identity-service pods to reload keys
kubectl rollout restart deployment/identity-service

# 3. Wait for JWKS endpoint to serve only the old key
curl https://api.instacommerce.dev/.well-known/jwks.json
# Confirm only v1 kid present

# 4. Users with tokens signed by new key (v2) will need to re-login (max 15 min window)
```

**Trigger:** JWT validation failures > 0.1% of requests; P95 auth latency spike.

---

## 14. Observability & Alerting

### Critical alerts to add

```yaml
# Prometheus alert rules

- alert: AuthLoginFailureRateHigh
  expr: rate(auth_login_total{result="failure"}[5m]) > 10
  for: 2m
  labels: { severity: warning }
  annotations:
    summary: "High login failure rate — possible brute force"

- alert: RateLimitTriggered
  expr: rate(resilience4j_ratelimiter_not_permitted_calls_total[5m]) > 5
  for: 1m
  labels: { severity: warning }
  annotations:
    summary: "Rate limit triggers sustained — investigate source IPs"

- alert: TokenRefreshAnomalyHigh
  expr: rate(auth_refresh_total[5m]) / rate(auth_login_total{result="success"}[5m]) > 10
  for: 5m
  labels: { severity: warning }
  annotations:
    summary: "Refresh rate >> login rate — possible token theft pattern"

- alert: JwksEndpointDown
  expr: probe_success{job="jwks-probe"} == 0
  for: 1m
  labels: { severity: critical }
  annotations:
    summary: "JWKS endpoint unreachable — Istio JWT validation will fail within minutes"

- alert: IdentityServiceHighLatency
  expr: histogram_quantile(0.99, rate(auth_login_duration_seconds_bucket[5m])) > 1
  for: 3m
  labels: { severity: warning }
  annotations:
    summary: "Login p99 > 1s — rate limit Redis latency or DB connection pool exhausted"
```

### Dashboards

Add these panels to the identity-service Grafana dashboard:
- Login success/failure rate (per minute, with IP breakdown)
- Refresh token rotation rate
- Rate limit triggers per operation (login, register, OTP)
- `jti` blocklist hit rate (post Redis implementation)
- JWKS endpoint response time (synthetic probe)
- DB connection pool utilization (`hikaricp_connections_active`)

### Istio telemetry

Enable Istio access logs for identity-service and admin-gateway-service in production:
```yaml
# deploy/helm/templates/istio/telemetry.yaml
apiVersion: telemetry.istio.io/v1alpha1
kind: Telemetry
metadata:
  name: auth-services-access-log
spec:
  selector:
    matchLabels:
      app: identity-service
  accessLogging:
    - providers:
        - name: envoy
      filter:
        expression: "response.code >= 400"  # log only errors to reduce volume
```

---

## 15. Open Risks & Follow-Up Work

| Risk | Severity | Owner | Mitigation |
|------|----------|-------|------------|
| No OTP/phone login — 90% of Q-commerce users use phone auth | CRITICAL | Identity team | Implement in Wave 1 (see §3.2.1) |
| Redis rate limiter adds a new dependency — Redis unavailability impacts login | MEDIUM | SRE | Circuit breaker + fallback to local limiter with tighter bounds; Redis HA with Sentinel/Cluster |
| JWKS endpoint is HTTP (not HTTPS) within the mesh — acceptable for mTLS mesh, but verify Istio Pilot connectivity | LOW | Platform | Confirm Istio Pilot fetches JWKS via mTLS; add synthetic probe |
| `audit_log` table grows unbounded — `AuditLogCleanupJob` exists but retention policy not documented | MEDIUM | Identity team | Verify `AuditLogCleanupJob` ShedLock config and retention period; add alert on table size |
| `outbox_events` in identity-service — `AuthService` does not call `OutboxService` yet | MEDIUM | Identity team | Wire `OutboxService.publish` into register/login/logout to emit `user.registered`, `user.loggedin`, `user.loggedout` events for downstream consumers (fraud, notification) |
| Social login (Google, Apple) not implemented — needed for Q-commerce mobile | HIGH | Identity team | Follow `api-gateway-bff-design.md` §4.x pattern; add `POST /auth/oauth/callback` |
| No email verification — users can register with unverified emails | MEDIUM | Identity team | Add `email_verified` column to users; send verification link via notification-service outbox event |
| `InternalServiceAuthFilter` grants `ROLE_ADMIN` to any service with correct token — RBAC bypass | HIGH | Security | Implement scoped roles map (§6.2 Step 4); complete workload identity migration in Wave 6 |
| Admin gateway has no Helm `services.admin-gateway-service` entry in `values.yaml` yet | MEDIUM | Platform | Add entry with appropriate resource requests/limits, HPA, and env vars |

---

*This document is part of the Iteration 3 service-wise implementation program. Related files:*
- *`docs/reviews/identity-service-review.md` — deep identity-service security review*
- *`docs/reviews/api-gateway-bff-design.md` — BFF and API gateway architecture design*
- *`docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md` — C1 cluster findings table*
- *`docs/reviews/iter3/platform/` — cross-cutting platform guidance*
