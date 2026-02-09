# Identity Service — Exhaustive Code Review Report

**Reviewer**: Senior Staff Engineer  
**Date**: 2025-07-17  
**Scope**: All files under `services/identity-service/`  
**Reference docs**: `docs/01-identity-access.md`, `docs/10-security-compliance-guidelines.md`

---

## Executive Summary

The identity-service is well-structured for an early-stage implementation. It follows many best practices (RS256 JWT, BCrypt-12, refresh token rotation, outbox pattern, audit logging). However, the review uncovered **5 CRITICAL**, **9 HIGH**, **12 MEDIUM**, and **8 LOW** severity findings across security, scalability, and production-readiness dimensions. The most urgent issues are: the revoke endpoint is unauthenticated, access tokens cannot be revoked mid-TTL, the admin endpoint list has no pagination, CORS is unconfigured, and the rate limiter map is an unbounded memory leak.

---

## 1. Security Issues

### S-01 — `/auth/revoke` is unauthenticated — anyone can revoke tokens [CRITICAL]

**File**: `security/SecurityConfig.java:24`  
**File**: `controller/AuthController.java:60-68`

The security config permits all requests to `/auth/**`. This means `/auth/revoke` is publicly accessible without authentication. The doc spec (section 5.4 of `01-identity-access.md`) explicitly requires `Authorization: Bearer <accessToken>` for revoke. Any attacker who intercepts or guesses a refresh token can revoke it without proving identity.

**Recommendation**: Either move the revoke endpoint out of `/auth/**` (e.g., `/tokens/revoke`) or add per-path auth rules in `SecurityConfig` to require authentication on `/auth/revoke`.

---

### S-02 — No access token revocation / JWT blacklist mechanism [CRITICAL]

**Files**: `security/DefaultJwtService.java`, `security/JwtAuthenticationFilter.java`

Access tokens (15-min TTL) are validated purely by signature + expiry. There is no mechanism to invalidate a compromised access token before expiry. After a user is deleted (`UserDeletionService`), suspended, or has their role changed, the old access token remains valid for up to 15 minutes.

At 20M+ users, this is a significant window for privilege abuse after account compromise, erasure, or role demotion.

**Recommendation**: Implement a lightweight Redis-based JWT blacklist (checked in `JwtAuthenticationFilter`), or store a per-user `tokenInvalidBefore` timestamp and check `iat` against it during validation.

---

### S-03 — No CORS configuration [HIGH]

**File**: `security/SecurityConfig.java`

There is no CORS configuration anywhere in the codebase. The security guidelines doc (`10-security-compliance-guidelines.md`, defaults section) mandates: *"CORS: Restrict to known frontend origins only."* Without CORS headers, either the browser blocks legitimate frontend requests, or if a wildcard is somehow applied by a proxy, cross-origin attacks become possible.

**Recommendation**: Add an explicit `CorsConfigurationSource` bean in `SecurityConfig` restricting origins to the frontend domain(s).

---

### S-04 — JWT missing `aud` (audience) and `jti` (token ID) claims [HIGH]

**File**: `security/DefaultJwtService.java:33-41`

The doc spec (section 6 of `01-identity-access.md`) defines the JWT payload with `"aud": "instacommerce-api"` and `"jti": "unique-token-id-uuid"`. Neither is set in `generateAccessToken()`. Missing `aud` allows tokens issued for this service to be replayed against other services. Missing `jti` prevents any future token-level revocation or replay detection.

**Recommendation**: Add `.claim("aud", "instacommerce-api")` and `.id(UUID.randomUUID().toString())` to the JWT builder. Validate `aud` in `validateAccessToken()`.

---

### S-05 — JWT missing `email` claim per spec [MEDIUM]

**File**: `security/DefaultJwtService.java:33-41`

The doc spec (section 6) includes `"email": "user@example.com"` in the JWT payload, but `generateAccessToken()` does not include it. Downstream services relying on the email claim from the token will fail.

**Recommendation**: Add `.claim("email", user.getEmail())`.

---

### S-06 — Password policy lacks special character requirement [MEDIUM]

**File**: `dto/request/RegisterRequest.java:17-18`

The regex `^(?=.*[A-Z])(?=.*\\d).{8,}$` requires uppercase + digit but no special character. For a platform handling 20M+ user accounts, NIST 800-63B recommends longer minimum length (and the current regex already does 8), but industry standard for high-value targets includes special characters or a minimum of 10+ characters.

**Recommendation**: Consider strengthening to require a special character or increasing minimum length to 10.

---

### S-07 — Deleted user's access tokens still valid post-erasure [HIGH]

**File**: `service/UserDeletionService.java:31-57`

When `initiateErasure()` runs, refresh tokens are deleted but the user's existing access tokens remain valid until expiry. The user's status is set to DELETED, but `JwtAuthenticationFilter` never checks user status — it only validates JWT signature/expiry. A deleted user retains access for up to 15 minutes.

**Recommendation**: Add a user-status check in the JWT filter (query DB or cache), or implement finding S-02 (token blacklist).

---

### S-08 — Rate limiter `ConcurrentHashMap` is an unbounded memory leak [CRITICAL]

**File**: `service/RateLimitService.java:14`

```java
private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();
```

A new `RateLimiter` is created per unique IP address and **never evicted**. An attacker can rotate through IPs (botnets, IPv6 rotation, proxy chains) to cause unbounded memory growth. At scale, this is a DoS vector.

**Recommendation**: Use a bounded cache (Caffeine/Guava with TTL and max size) instead of `ConcurrentHashMap`, or use a distributed rate limiter backed by Redis.

---

### S-09 — X-Forwarded-For header trusted without validation [MEDIUM]

**File**: `controller/AuthController.java:70-74`, `controller/UserController.java:62-66`

The `resolveIp()` method trusts `X-Forwarded-For` header blindly. An attacker can spoof this header to bypass IP-based rate limiting. While Istio/Cloud Armor should strip/set it correctly, the application code has no safeguard.

**Recommendation**: Configure Spring's `ForwardedHeaderFilter` or `server.forward-headers-strategy=NATIVE` to only trust forwarded headers from known proxies.

---

### S-10 — Refresh token family detection not implemented [MEDIUM]

**File**: `service/AuthService.java:129-163`

When a refresh token is reused after rotation (indicating token theft), the system simply throws `TokenRevokedException`. It does not revoke **all** tokens in the family (all descendant tokens for that user/device). A stolen pre-rotation token will be rejected, but the attacker's rotated token remains valid.

**Recommendation**: Implement refresh token family tracking. When a revoked token is presented, revoke all tokens for that user (or device family).

---

### S-11 — No account lockout after repeated failed logins [MEDIUM]

**Files**: `service/AuthService.java:80-95`, `service/RateLimitService.java`

Rate limiting is per-IP (5/min), but there is no per-account lockout. An attacker using a botnet can attempt 5 passwords/min from each of thousands of IPs against a single account — effectively unlimited brute force per account.

**Recommendation**: Implement per-account progressive lockout (e.g., lock after 10 failed attempts, exponential backoff).

---

## 2. Scalability Concerns

### SC-01 — No HikariCP connection pool tuning [HIGH]

**File**: `application.yml`

The doc spec (section 8.3 of `01-identity-access.md`) specifies explicit HikariCP settings (`maximum-pool-size: 20`, `minimum-idle: 5`, `connection-timeout: 5000`, `max-lifetime: 1800000`). None of these are configured in the actual `application.yml`. Spring Boot defaults (pool size 10, 30s connection timeout) may be insufficient at 20M users.

**Recommendation**: Add explicit HikariCP configuration matching the doc spec.

---

### SC-02 — `listUsers()` loads ALL users without pagination [HIGH]

**File**: `service/UserService.java:46-48`, `controller/AdminController.java:27-29`

```java
return userMapper.toResponses(userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")));
```

At 20M users, this will OOM the JVM instantly. No pagination, no limit. This is an admin-only endpoint but still a critical scalability and availability risk.

**Recommendation**: Implement cursor-based or offset pagination using `Pageable` and return `Page<UserResponse>`.

---

### SC-03 — No caching strategy anywhere [MEDIUM]

**Files**: All service files

There is no caching layer (Redis, Caffeine, etc.) for user lookups, public key loading, or other frequently accessed data. Every `/users/me` call hits the database. The readiness health check references Redis (`redis` in health group at `application.yml:72`) but Redis is not configured or used.

**Recommendation**: Add `spring-boot-starter-cache` with Redis/Caffeine for user profile lookups. Remove `redis` from health group if not used, or add Redis config.

---

### SC-04 — `spring.jpa.open-in-view` not explicitly disabled [LOW]

**File**: `application.yml`

The doc spec (section 8.3) explicitly sets `open-in-view: false`, but the actual config omits it. Spring Boot defaults to `true`, which holds DB connections for the entire request lifecycle and can cause connection pool exhaustion under load.

**Recommendation**: Add `spring.jpa.open-in-view: false`.

---

### SC-05 — AuditLogCleanupJob does unbounded DELETE [MEDIUM]

**File**: `service/AuditLogCleanupJob.java:24-27`

```java
auditLogRepository.deleteByCreatedAtBefore(cutoff);
```

After 2 years of operation with high audit volume, this single `DELETE` query could affect millions of rows, causing long-running transactions, table bloat, and potential connection pool exhaustion.

**Recommendation**: Use batch deletion with a `LIMIT` clause (e.g., delete 10,000 rows per iteration in a loop).

---

### SC-06 — Audit logging is synchronous within the transaction [MEDIUM]

**File**: `service/AuditService.java:18-37`

`AuditService.logAction()` is `@Transactional` and executes synchronously during login/register flows. The security-compliance doc (section 5) uses `@Async` for audit logging. Synchronous audit writes increase login latency and couple auth availability to the audit_log table's write performance.

**Recommendation**: Make audit logging `@Async` or use the outbox pattern for audit events.

---

## 3. Code Quality

### CQ-01 — Duplicate `resolveIp()` and `resolveUserAgent()` methods [MEDIUM]

**Files**: `controller/AuthController.java:70-80`, `controller/UserController.java:62-72`

Identical helper methods are duplicated across two controllers.

**Recommendation**: Extract to a shared utility class or a `RequestContext` component.

---

### CQ-02 — `GlobalExceptionHandler` does not log exceptions [HIGH]

**File**: `exception/GlobalExceptionHandler.java:73-77`

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleFallback(Exception ex, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(buildError("INTERNAL_ERROR", "An unexpected error occurred", List.of(), request));
}
```

The fallback handler silently swallows all unexpected exceptions without logging them. In production, unhandled errors will return 500 but leave **zero trace** in logs for debugging.

**Recommendation**: Add `log.error("Unhandled exception", ex)` before returning the response. Same for other exception handlers as appropriate.

---

### CQ-03 — `NotificationPreferenceRequest` lacks `@JsonIgnoreProperties` and `@Valid` [MEDIUM]

**File**: `dto/request/NotificationPreferenceRequest.java`  
**File**: `controller/UserController.java:49-51`

The request DTO lacks `@JsonIgnoreProperties(ignoreUnknown = true)` (required by security guidelines doc section 12, rule 4), and the controller method does not use `@Valid` on the `@RequestBody` (required by guidelines rule 1). While boolean fields have no validation constraints currently, the annotations should be present for consistency and future-proofing.

**Recommendation**: Add `@JsonIgnoreProperties(ignoreUnknown = true)` to the record and `@Valid` to the controller parameter.

---

### CQ-04 — DB column mismatch: AuditLog `id` is `Long`/`IDENTITY` but migration uses `BIGSERIAL` [LOW]

**File**: `domain/model/AuditLog.java:20-21`, `migration/V3__create_audit_log.sql:2`

The entity uses `@GeneratedValue(strategy = GenerationType.IDENTITY)` which works with `BIGSERIAL`, but the field type is `Long`. This is correct but the `id` column is declared without explicit `NOT NULL` in the migration (implicit from `BIGSERIAL PRIMARY KEY`). Minor consistency issue.

---

### CQ-05 — `UserResponse` omits `firstName`, `lastName`, `phone` fields [LOW]

**File**: `dto/response/UserResponse.java`

The `User` entity has `firstName`, `lastName`, and `phone` fields (added in V4 migration), but `UserResponse` only returns `id`, `email`, `roles`, `status`, `createdAt`. This may be intentional for the current API, but the `/users/me` endpoint should likely return the user's name.

---

### CQ-06 — `RegisterResponse` does not auto-login (no tokens returned) [LOW]

**File**: `controller/AuthController.java:31-39`, `service/AuthService.java:57-78`

Registration returns only `userId` and `message`. Users must make a separate login call. This adds unnecessary friction for a Q-commerce app where conversion speed matters. Not a bug per se — the spec dictates this — but worth noting.

---

## 4. Production Readiness

### PR-01 — No custom Micrometer metrics for auth operations [HIGH]

**Files**: All service files

The doc spec (section 10 of `01-identity-access.md`) defines required metrics: `auth_register_total`, `auth_login_total`, `auth_refresh_total`, `auth_token_revoke_total`, `auth_login_duration_seconds`. None are implemented. Without these, the Grafana dashboards referenced in the production readiness checklist will have no data, and SLO monitoring is impossible.

**Recommendation**: Add a `MeterRegistry`-based metrics component with counters and timers for all auth operations.

---

### PR-02 — Health check references Redis but Redis is not configured [MEDIUM]

**File**: `application.yml:72`

```yaml
readiness:
  include: readinessState,db,redis
```

Redis health indicator is included in the readiness probe, but there is no Redis dependency or configuration. This will cause the readiness probe to either fail (blocking pod traffic) or be ignored.

**Recommendation**: Remove `redis` from the readiness group, or add Redis configuration if caching is planned.

---

### PR-03 — No circuit breaker configuration [MEDIUM]

**File**: `build.gradle.kts:27` (Resilience4j is included)

Resilience4j is a dependency and rate limiters are configured, but no circuit breakers are defined. The production readiness checklist (doc section 10, item 7) requires: *"Circuit breaker configured for all external calls."* While identity-service has few external calls, DB access under degradation would benefit from a circuit breaker.

**Recommendation**: Configure Resilience4j circuit breakers for database operations and any outgoing service calls.

---

### PR-04 — No `@EnableConfigurationProperties` validation on `IdentityProperties` [LOW]

**File**: `config/IdentityProperties.java`

The properties class has no `@Validated` annotation or `@NotNull`/`@NotBlank` constraints on critical fields like `privateKey`. If the JWT private key fails to load from Secret Manager, the failure message will come from `JwtKeyLoader` at runtime rather than at startup configuration validation.

**Recommendation**: Add `@Validated` to `IdentityProperties` and `@NotBlank` to `privateKey` and `publicKey`.

---

### PR-05 — Dockerfile `HEALTHCHECK` uses `wget` but not all alpine images include it [LOW]

**File**: `Dockerfile:15-16`

The health check uses `wget` which may not be available in all slim Alpine images. Also, Docker-level HEALTHCHECK is redundant when Kubernetes liveness/readiness probes are configured (as they are in the Helm values).

**Recommendation**: Consider using `curl` or removing the Docker-level HEALTHCHECK in favor of k8s probes only.

---

### PR-06 — Scheduling runs in all pods (no leader election) [MEDIUM]

**File**: `config/SchedulerConfig.java`, `service/AuditLogCleanupJob.java`

`@EnableScheduling` is active on all pod replicas. With `replicaCount: 2+`, the `AuditLogCleanupJob` runs simultaneously on all pods, causing redundant deletes and potential race conditions.

**Recommendation**: Implement leader election using `spring-integration-jdbc` or ShedLock to ensure the cleanup job runs on only one pod.

---

## 5. Data Model Issues

### DM-01 — Audit log is not append-only at the DB level [HIGH]

**File**: `migration/V3__create_audit_log.sql`

The doc spec includes `REVOKE UPDATE, DELETE ON audit_log FROM identity_app_user;` to enforce append-only at the DB level, but the actual migration omits this statement. The `AuditLogCleanupJob` performs `deleteByCreatedAtBefore()`, which contradicts the append-only design but is needed for retention.

**Recommendation**: Either add the `REVOKE` statements and use a privileged role for cleanup, or document that audit_log is not truly append-only.

---

### DM-02 — Missing index on `refresh_tokens.token_hash` for lookup [LOW]

**File**: `migration/V2__create_refresh_tokens.sql`

There is a `UNIQUE` constraint on `token_hash` (`uq_refresh_token_hash`) which implicitly creates an index. This is adequate — this is actually well-designed.

---

### DM-03 — `outbox_events` table lacks `aggregate_type`+`event_type` index [LOW]

**File**: `migration/V6__create_outbox.sql`

Only the `sent = false` partial index exists. If the outbox poller ever queries by `aggregate_type` or `event_type`, it will scan. For MVP this is fine.

---

### DM-04 — Audit log `ip_address` column is `INET` type but Java entity maps it as `String` [MEDIUM]

**File**: `domain/model/AuditLog.java:39`, `migration/V3__create_audit_log.sql:7`

PostgreSQL `INET` type requires valid IP address format. If an invalid IP string is passed (e.g., from a spoofed `X-Forwarded-For`), the INSERT will fail with a DB exception, causing the entire transaction (including the business operation) to fail.

**Recommendation**: Validate IP format before persisting, or change the column type to `VARCHAR(45)` as specified in the security-compliance doc (section 5).

---

### DM-05 — V5 migration renames columns but doesn't update old index names [LOW]

**File**: `migration/V5__update_audit_log_schema.sql`

V3 creates indexes `idx_audit_log_actor`, `idx_audit_log_action`, `idx_audit_log_created`. V5 renames the columns and creates new indexes `idx_audit_user_id`, `idx_audit_action`, `idx_audit_created_at` with `IF NOT EXISTS`. The old indexes (`idx_audit_log_actor` etc.) are never dropped, leaving duplicate indexes that waste space and slow writes.

**Recommendation**: Add `DROP INDEX IF EXISTS idx_audit_log_actor;` etc. in V5 before creating new indexes.

---

## 6. Missing Features

### MF-01 — No password change / password reset flow [HIGH]

**Files**: None exist

The doc spec (section 10 of `10-security-compliance-guidelines.md`) lists `PASSWORD_CHANGED` as a mandatory audit event, but there are no endpoints or service methods for password change or password reset. For 20M users, this is essential.

---

### MF-02 — No MFA / 2FA support [MEDIUM]

The doc's escalation section acknowledges this as an open question. For a platform at Zepto/Blinkit scale handling financial transactions, TOTP or SMS-based 2FA should be on the roadmap.

---

### MF-03 — No OAuth2 / social login support [MEDIUM]

No Google/Apple/Facebook login. The doc explicitly defers this. For Q-commerce conversion rates, social login is near-mandatory.

---

### MF-04 — No session invalidation (logout all devices) [MEDIUM]

There is no endpoint to revoke all refresh tokens for a user (logout everywhere). The `revoke` endpoint only handles a single token. Password change/compromise scenarios require this.

---

### MF-05 — No expired refresh token cleanup job [MEDIUM]

**File**: `migration/V2__create_refresh_tokens.sql`

Expired and revoked refresh tokens accumulate indefinitely. The `refresh_tokens` table will grow without bounds. There is an `AuditLogCleanupJob` but no equivalent for refresh tokens.

**Recommendation**: Add a scheduled job to delete expired/revoked refresh tokens.

---

### MF-06 — No role management endpoints [LOW]

**File**: `controller/AdminController.java`

The admin controller only has read endpoints. The doc mentions "role changes" as an admin capability, and `ROLE_CHANGE` is listed as a required audit event, but there is no endpoint to change a user's role.

---

## 7. API Design

### API-01 — Inconsistent API path versioning [MEDIUM]

**File**: All controllers

The doc references `/api/v1/auth/login`, `/api/v1/users/me` etc. in the security-compliance doc (section 7), but the actual controllers use unversioned paths (`/auth/login`, `/users/me`). This makes future API evolution impossible without breaking clients.

**Recommendation**: Add `/api/v1` prefix via `server.servlet.context-path` or controller-level `@RequestMapping`.

---

### API-02 — No rate limiting on `/auth/refresh` endpoint [MEDIUM]

**File**: `service/AuthService.java:129-163`

Login and register have rate limits, but the refresh endpoint does not. An attacker with a stolen refresh token could rapidly generate access tokens.

**Recommendation**: Add a rate limiter for the refresh endpoint.

---

### API-03 — Rate limit mismatch vs. doc spec [LOW]

**File**: `application.yml:36-43`

Config: login=5/min, register=3/min. Doc spec (`01-identity-access.md` section 14): login=10/min, register=5/min. The stricter config is arguably better, but the mismatch should be documented.

---

### API-04 — 429 response missing `Retry-After` header [LOW]

**File**: `exception/GlobalExceptionHandler.java:54-57`

The doc spec (section 5.2) shows `retryAfter: 60` in the 429 response. The actual handler returns a generic error without `Retry-After` header or body field, violating RFC 6585.

**Recommendation**: Add `response.setHeader("Retry-After", "60")` to the rate limit handler.

---

## 8. Testing Gaps

### T-01 — No test directory exists [CRITICAL]

**Directory**: `src/test/` does not exist

There are zero tests. No unit tests, no integration tests, no contract tests. The doc specifies `TokenServiceTest`, `AuthServiceTest`, `SecurityConfigTest`, and full integration tests with Testcontainers. The build.gradle.kts includes test dependencies (`spring-boot-starter-test`, `spring-security-test`, `testcontainers`) but no test classes exist.

For a service handling authentication for 20M+ users, this is a critical gap.

**Recommendation**: Implement the full testing strategy from doc section 12 before any production deployment.

---

### T-02 — No JaCoCo coverage enforcement [LOW]

**File**: `build.gradle.kts`

The production readiness checklist requires "Minimum 70% test coverage (line), JaCoCo enforcement." JaCoCo is not configured in the build.

---

## Summary Table

| # | Finding | Severity | Category |
|---|---------|----------|----------|
| S-01 | `/auth/revoke` is unauthenticated | **CRITICAL** | Security |
| S-02 | No access token revocation mechanism | **CRITICAL** | Security |
| S-08 | Rate limiter unbounded memory leak | **CRITICAL** | Security/Scalability |
| T-01 | No tests exist at all | **CRITICAL** | Testing |
| SC-02 | `listUsers()` loads all 20M users | **CRITICAL** | Scalability |
| S-03 | No CORS configuration | **HIGH** | Security |
| S-04 | JWT missing `aud` and `jti` claims | **HIGH** | Security |
| S-07 | Deleted user tokens still valid | **HIGH** | Security |
| SC-01 | No HikariCP pool tuning | **HIGH** | Scalability |
| CQ-02 | Exception handler swallows errors silently | **HIGH** | Code Quality |
| DM-01 | Audit log not append-only at DB level | **HIGH** | Data Model |
| PR-01 | No Micrometer auth metrics | **HIGH** | Production Readiness |
| MF-01 | No password change/reset flow | **HIGH** | Missing Features |
| SC-06 | Audit logging synchronous in transaction | **HIGH** | Scalability |
| S-05 | JWT missing `email` claim | **MEDIUM** | Security |
| S-06 | Weak password policy | **MEDIUM** | Security |
| S-09 | X-Forwarded-For trusted blindly | **MEDIUM** | Security |
| S-10 | No refresh token family detection | **MEDIUM** | Security |
| S-11 | No per-account lockout | **MEDIUM** | Security |
| SC-03 | No caching strategy | **MEDIUM** | Scalability |
| SC-05 | Unbounded audit log DELETE | **MEDIUM** | Scalability |
| CQ-01 | Duplicate utility methods | **MEDIUM** | Code Quality |
| CQ-03 | Missing `@JsonIgnoreProperties` / `@Valid` | **MEDIUM** | Code Quality |
| DM-04 | INET column type mismatch risk | **MEDIUM** | Data Model |
| PR-02 | Redis in health check but unconfigured | **MEDIUM** | Production Readiness |
| PR-03 | No circuit breakers | **MEDIUM** | Production Readiness |
| PR-06 | Scheduler has no leader election | **MEDIUM** | Production Readiness |
| MF-02 | No MFA/2FA | **MEDIUM** | Missing Features |
| MF-03 | No OAuth2/social login | **MEDIUM** | Missing Features |
| MF-04 | No "logout all devices" | **MEDIUM** | Missing Features |
| MF-05 | No refresh token cleanup job | **MEDIUM** | Missing Features |
| API-01 | No API versioning in paths | **MEDIUM** | API Design |
| API-02 | No rate limiting on /auth/refresh | **MEDIUM** | API Design |
| SC-04 | `open-in-view` not disabled | **LOW** | Scalability |
| CQ-04 | Minor ID type consistency | **LOW** | Code Quality |
| CQ-05 | UserResponse missing name fields | **LOW** | Code Quality |
| CQ-06 | Register doesn't auto-login | **LOW** | Code Quality |
| DM-03 | Outbox missing secondary index | **LOW** | Data Model |
| DM-05 | Duplicate indexes after V5 migration | **LOW** | Data Model |
| PR-04 | No config property validation | **LOW** | Production Readiness |
| PR-05 | Docker HEALTHCHECK redundant w/ k8s | **LOW** | Production Readiness |
| MF-06 | No role management endpoints | **LOW** | Missing Features |
| API-03 | Rate limit values mismatch docs | **LOW** | API Design |
| API-04 | Missing Retry-After header | **LOW** | API Design |
| T-02 | No JaCoCo enforcement | **LOW** | Testing |

---

## Priority Remediation Order

### P0 — Fix before any production traffic
1. **T-01**: Write tests (unit + integration with Testcontainers)
2. **S-01**: Authenticate the `/auth/revoke` endpoint
3. **SC-02**: Add pagination to `listUsers()`
4. **S-08**: Replace unbounded `ConcurrentHashMap` with Caffeine/Redis rate limiter
5. **CQ-02**: Add logging to `GlobalExceptionHandler`

### P1 — Fix before GA launch
6. **S-02 + S-07**: Implement JWT blacklist or per-user token invalidation
7. **S-03**: Configure CORS
8. **S-04**: Add `aud`, `jti` to JWT
9. **SC-01**: Tune HikariCP connection pool
10. **PR-01**: Add Micrometer auth metrics
11. **MF-01**: Implement password change/reset
12. **DM-01**: Enforce audit log append-only at DB level

### P2 — Fix before scale-up
13. **SC-03**: Add caching (Redis/Caffeine)
14. **SC-06**: Make audit logging async
15. **PR-06**: Leader election for scheduled jobs
16. **MF-05**: Add refresh token cleanup job
17. All remaining MEDIUM findings

---

## Positive Observations

| Area | What's done right |
|------|-------------------|
| JWT Algorithm | RS256 with RSA-2048 — correct per spec, not HS256 |
| Password Hashing | BCrypt with strength 12 — meets guidelines |
| Refresh Token Storage | SHA-256 hashed, raw never stored — correct |
| Token Rotation | Old refresh token revoked on refresh — correct |
| Max Tokens per User | Enforces limit of 5, deletes oldest — good |
| Outbox Pattern | Events published via outbox table — correct |
| GDPR Erasure | Anonymizes rather than deletes — correct |
| Optimistic Locking | `@Version` on User entity — correct |
| Stateless Sessions | `SessionCreationPolicy.STATELESS` — correct |
| Structured Logging | LogstashEncoder for JSON logs — correct |
| Graceful Shutdown | `server.shutdown=graceful` with 30s timeout — correct |
| Dockerfile | Non-root user, JVM tuning flags, ZGC — correct |
| Error Response Format | Consistent `ErrorResponse` with trace IDs — well done |
| Exception Hierarchy | Clean `ApiException` base class with HTTP status — good pattern |
| JWKS Endpoint | `/.well-known/jwks.json` properly exposed — correct |
