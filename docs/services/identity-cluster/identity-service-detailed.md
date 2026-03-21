# Identity Service Documentation

## High-Level Design (HLD)

### System Context Diagram

```mermaid
flowchart TB
    subgraph Internet["Internet (Public)"]
        MobileApp["Mobile App<br/>(iOS/Android)"]
        WebApp["Web Browser<br/>(React SPA)"]
    end

    subgraph Mesh["InstaCommerce Service Mesh"]
        IGW["Istio Gateway<br/>TLS SIMPLE<br/>Rate Limit"]
        Identity["identity-service<br/>Port 8080<br/>Spring Boot 4"]
        Notification["notification-service<br/>Email delivery"]
    end

    subgraph Storage["Data Layer"]
        PG[("PostgreSQL 15+<br/>identity_db<br/>5 tables")]
        SM["GCP Secret Manager<br/>JWT RSA keys<br/>DB password"]
        KF["Kafka<br/>identity.events"]
    end

    MobileApp -->|HTTPS| IGW
    WebApp -->|HTTPS| IGW
    IGW -->|mTLS STRICT| Identity

    Identity --> PG
    Identity --> SM
    Identity -->|Outbox Pattern| KF

    KF -->|CDC| Notification

    style Identity fill:#4a90e2,color:#fff
    style IGW fill:#f5a623,color:#000
    style PG fill:#50e3c2,color:#000
```

### Trust Boundaries

| Boundary | Mechanism | Enforcement |
|----------|-----------|-------------|
| **Internet → Ingress** | TLS 1.3, rate limit (global) | Istio Gateway `SIMPLE` mode |
| **Ingress → Service** | mTLS mutual cert | Istio `PeerAuthentication` + sidecar |
| **Service → Service** | JWT + `X-Internal-Token` | Spring Security filters |
| **Service → Data** | Private VPC, IAM | GCP Workload Identity + CloudSQL socket factory |

### Deployment Topology

**Kubernetes manifest structure:**
```
deploy/helm/
  ├── Chart.yaml
  ├── values-dev.yaml      # identity-service replicas: 2
  ├── values-staging.yaml  # identity-service replicas: 3
  ├── values-prod.yaml     # identity-service replicas: 5-10 (HPA)
  └── templates/
      ├── deployment.yaml
      ├── service.yaml
      ├── ingress.yaml
      ├── configmap.yaml
      └── hpa.yaml
```

**Replicas & scaling:**
- **Dev**: 1 replica (cost optimization)
- **Staging**: 2 replicas (HA testing)
- **Prod**: 5-10 replicas (HPA on CPU 70%, mem 80%)
- **Graceful drain**: 30s timeout on pod termination

## Low-Level Design (LLD)

### Component Architecture

```mermaid
graph TB
    subgraph HTTP["HTTP Layer"]
        AC["AuthController<br/>/auth/*"]
        UC["UserController<br/>/users/*"]
        ADC["AdminController<br/>/admin/*"]
        JWKS["JwksController<br/>/.well-known/jwks.json"]
    end

    subgraph Services["Business Logic"]
        AuthSvc["AuthService<br/>login/register/refresh"]
        UserSvc["UserService"]
        TokenSvc["TokenService<br/>JWT + Refresh tokens"]
        RateLimitSvc["RateLimitService<br/>Resilience4j"]
        OutboxSvc["OutboxService<br/>Event publishing"]
        AuditSvc["AuditService<br/>@Async audit logs"]
    end

    subgraph Security["Security"]
        JwtAuth["JwtAuthenticationFilter<br/>Bearer token validation"]
        InternalAuth["InternalServiceAuthFilter<br/>X-Internal-Token"]
        JwtSvc["DefaultJwtService<br/>RS256 JJWT"]
        KeyLoader["JwtKeyLoader<br/>PEM parsing + SHA-256 kid"]
    end

    subgraph Persistence["Persistence Layer"]
        UserRepo["UserRepository<br/>@Entity User"]
        RefreshTokenRepo["RefreshTokenRepository<br/>Token hash + revoked"]
        AuditLogRepo["AuditLogRepository<br/>Immutable audit trail"]
        OutboxEventRepo["OutboxEventRepository<br/>Outbox events"]
    end

    subgraph Jobs["Scheduled Jobs"]
        RefreshTokenCleanup["RefreshTokenCleanupJob<br/>Daily 3:30 AM<br/>Delete expired tokens"]
        AuditLogCleanup["AuditLogCleanupJob<br/>Daily 3:00 AM<br/>Delete 2yr+ entries"]
        OutboxCleanup["OutboxCleanupJob<br/>Every 6h<br/>ShedLock protected"]
    end

    AC --> AuthSvc
    UC --> UserSvc
    ADC --> UserSvc

    AuthSvc --> TokenSvc
    AuthSvc --> RateLimitSvc
    AuthSvc --> AuditSvc

    TokenSvc --> JwtSvc
    JwtSvc --> KeyLoader

    JwtAuth --> JwtSvc
    InternalAuth -.-> JwtAuth

    AuthSvc --> UserRepo
    AuthSvc --> RefreshTokenRepo
    UserSvc --> UserRepo
    AuditSvc --> AuditLogRepo
    OutboxSvc --> OutboxEventRepo

    RefreshTokenCleanup --> RefreshTokenRepo
    AuditLogCleanup --> AuditLogRepo
    OutboxCleanup --> OutboxEventRepo
```

### Database Schema

```sql
-- Users table (main identity store)
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone VARCHAR(30),
    password_hash VARCHAR(72) NOT NULL,      -- BCrypt
    roles VARCHAR[] DEFAULT '{CUSTOMER}',    -- {CUSTOMER}, {ADMIN}
    status VARCHAR(20) DEFAULT 'ACTIVE',     -- ACTIVE, SUSPENDED, DELETED
    failed_attempts INTEGER DEFAULT 0,       -- Login failure counter
    locked_until TIMESTAMPTZ,                -- Account lockout expiry
    deleted_at TIMESTAMPTZ,                  -- GDPR erasure timestamp
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT DEFAULT 1                 -- Optimistic locking
);

-- Refresh tokens (immutable once written)
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) UNIQUE NOT NULL,  -- SHA-256(secure_random_64_bytes)
    device_info VARCHAR(255),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL
);

-- Audit log (immutable, append-only)
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID,                            -- Nullable for failed logins
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50),
    entity_id VARCHAR(100),
    details JSONB,
    ip_address VARCHAR(45),
    user_agent TEXT,
    trace_id VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL
);

-- Outbox events (transactional outbox pattern)
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    sent BOOLEAN DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL
);

-- ShedLock for distributed job coordination
CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
```

## Authentication & Token Flows

### 1. Login Flow

```mermaid
sequenceDiagram
    participant Client
    participant AC as AuthController
    participant AS as AuthService
    participant RLS as RateLimitService
    participant UR as UserRepository
    participant BCrypt
    participant TS as TokenService
    participant JWT as DefaultJwtService
    participant RTR as RefreshTokenRepo
    participant AUD as AuditService

    Client->>AC: POST /auth/login {email, password}
    AC->>AS: login(email, password, ip, userAgent)

    AS->>RLS: checkLoginLimit(ip)
    RLS-->>AS: ✓ allowed or 429 Too Many Requests

    AS->>UR: findByEmailIgnoreCase(email)
    alt User not found
        AS->>AUD: logAction(USER_LOGIN_FAILED)
        AS-->>Client: 401 InvalidCredentials
    end

    alt Account locked
        AS-->>Client: 403 UserInactive (locked_until)
    end

    AS->>BCrypt: matches(password, passwordHash)
    alt Invalid password
        AS->>UR: failedAttempts++
        alt failedAttempts >= 10
            AS->>UR: lockedUntil = now + 30min
        end
        AS->>AUD: logAction(USER_LOGIN_FAILED)
        AS-->>Client: 401 InvalidCredentials
    end

    AS->>UR: failedAttempts = 0, lockedUntil = null
    AS->>JWT: generateAccessToken(user)
    Note over JWT: RS256, 15-min TTL
    AS->>TS: generateRefreshToken()
    Note over TS: SecureRandom(64) → Base64-URL
    AS->>TS: hashRefreshToken() → SHA-256
    AS->>RTR: save({token_hash, expires_at=+7d, deviceInfo})
    AS->>AS: enforceMaxRefreshTokens(user, max=5)
    AS->>AUD: logAction(USER_LOGIN_SUCCESS)
    AC-->>Client: 200 {accessToken, refreshToken, expiresIn: 900}
```

### 2. Token Refresh (Rotation) Flow

```mermaid
sequenceDiagram
    participant Client
    participant AC as AuthController
    participant AS as AuthService
    participant TS as TokenService
    participant RTR as RefreshTokenRepo

    Client->>AC: POST /auth/refresh {refreshToken}
    AC->>AS: refresh(refreshToken, ip, userAgent)

    AS->>TS: hashRefreshToken(token) → SHA-256
    AS->>RTR: findByTokenHash(hash)

    alt Token not found
        AS-->>Client: 401 TokenInvalid
    else Token revoked
        AS-->>Client: 401 TokenRevoked
    else Token expired
        AS-->>Client: 401 TokenExpired
    else User status != ACTIVE
        AS-->>Client: 403 UserInactive
    end

    Note over AS: Rotate: revoke old, issue new pair
    AS->>RTR: oldToken.revoked = true; save()
    AS->>TS: generateRefreshToken() → new
    AS->>RTR: save(newToken)
    AS->>AS: enforceMaxRefreshTokens(user)
    AS->>TS: generateAccessToken(user)
    AC-->>Client: 200 {accessToken, refreshToken}
```

### 3. GDPR Erasure Flow

```mermaid
sequenceDiagram
    participant Client
    participant UC as UserController
    participant UDS as UserDeletionService
    participant UR as UserRepository
    participant RTR as RefreshTokenRepo
    participant OBS as OutboxService
    participant PG as PostgreSQL
    participant KF as Kafka

    Client->>UC: DELETE /users/me (Bearer JWT)
    UC->>UDS: initiateErasure(userId)

    Note over UDS: Single @Transactional boundary
    UDS->>UR: anonymize(userId)
    Note over UR: email→deleted-{id}@anon.local<br/>name→DELETED USER<br/>phone→null, password→[REDACTED]<br/>status→DELETED

    UDS->>RTR: deleteAllByUser_Id(userId)
    UDS->>OBS: publish("User", userId, "UserErased", {})
    OBS->>PG: INSERT outbox_events {sent=false}

    Note over PG: Debezium CDC picks up row
    PG-->>KF: identity.events topic
    KF-->>KF: Downstream consumers anonymize

    UDS-->>Client: 204 No Content
```

### 4. JWT Structure

```json
{
  "alg": "RS256",
  "kid": "550e8400-sha256-of-modulus-exponent"
}
.
{
  "iss": "instacommerce-identity",
  "sub": "550e8400-e29b-41d4-a716-446655440000",  // user ID
  "aud": "instacommerce-api",
  "roles": ["CUSTOMER"],
  "iat": 1705319400,
  "exp": 1705320300,                              // +15 minutes
  "jti": "550e8400-random-uuid"
}
```

## API Reference

### Public Endpoints

| Method | Path | Auth | Rate Limit | Description |
|--------|------|------|-----------|-------------|
| `POST` | `/auth/register` | None | 3/min/IP | Register new user |
| `POST` | `/auth/login` | None | 5/min/IP | Authenticate |
| `POST` | `/auth/refresh` | None | — | Rotate token pair |
| `POST` | `/auth/revoke` | Bearer | — | Revoke single token |
| `POST` | `/auth/logout` | Bearer | — | Revoke all tokens |
| `GET` | `/.well-known/jwks.json` | None | — | JWKS public key |
| `DELETE` | `/users/me` | Bearer | — | GDPR erasure |
| `GET` | `/users/me` | Bearer | — | Current user profile |

### Admin Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/admin/users` | Bearer + ADMIN | Paginated user listing |
| `GET` | `/admin/users/{id}` | Bearer + ADMIN | Get user by ID |

### Infrastructure

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/actuator/health` | Liveness probe |
| `GET` | `/actuator/health/readiness` | Readiness probe (includes DB) |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

## Configuration

### Environment Variables

```bash
# Server
SERVER_PORT=8081                    # HTTP listen port
SERVER_SERVLET_CONTEXT_PATH=/       # Context root

# Database
IDENTITY_DB_URL=jdbc:postgresql://localhost:5432/identity_db
IDENTITY_DB_USER=postgres
IDENTITY_DB_PASSWORD=postgres       # Fallback if sm:// unavailable

# JWT
IDENTITY_JWT_PRIVATE_KEY=-----BEGIN RSA PRIVATE KEY-----...
IDENTITY_JWT_PUBLIC_KEY=-----BEGIN PUBLIC KEY-----...
IDENTITY_JWT_ISSUER=instacommerce-identity
IDENTITY_ACCESS_TTL=900             # 15 minutes
IDENTITY_REFRESH_TTL=604800         # 7 days
IDENTITY_MAX_REFRESH_TOKENS=5

# CORS
IDENTITY_CORS_ORIGINS=http://localhost:3000,https://*.instacommerce.dev

# Internal Service
INTERNAL_SERVICE_TOKEN=prod-token-change-me

# Observability
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces
OTEL_EXPORTER_OTLP_METRICS_ENDPOINT=http://otel-collector:4318/v1/metrics
TRACING_PROBABILITY=1.0
ENVIRONMENT=dev
```

### HikariCP Connection Pool

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 5000ms
      max-lifetime: 1800000ms     # 30 min
      idle-timeout: 600000ms      # 10 min
      auto-commit: false
```

## Observability

### Metrics (Micrometer)

```
auth.login.total{result=success|failure}        # Counter
auth.login.duration{}                           # Timer (BCrypt latency)
auth.register.total{}                           # Counter
auth.refresh.total{}                            # Counter
auth.revoke.total{}                             # Counter
http.server.requests{}                          # Standard Spring
hikaricp.connections.max{}                      # Connection pool
jvm.memory.usage{}                              # JVM metrics
```

### Logging

JSON structured via `logstash-logback-encoder`:
```json
{
  "@timestamp": "2025-01-15T10:30:00.000Z",
  "service": "identity-service",
  "environment": "dev",
  "message": "User login successful",
  "user_id": "550e8400-...",
  "ip_address": "203.0.113.42",
  "trace_id": "abcd1234",
  "level": "INFO"
}
```

### Alerts (Recommended)

| Alert | Condition | Severity |
|-------|-----------|----------|
| High login failures | `rate(auth.login.total{result="failure"}[5m]) > 10` | Warning |
| Auth service errors | `rate(http.server.requests{service="identity", status=5xx}[5m]) > 0` | Critical |
| Database pool exhaustion | `hikaricp.connections.pending > 5` | Warning |
| JWT key unavailable | Readiness probe fails | Critical/Page |

## Testing

```bash
# Run all tests
./gradlew :services:identity-service:test

# Run specific test class
./gradlew :services:identity-service:test --tests AuthServiceTest

# Integration tests (requires PostgreSQL)
./gradlew :services:identity-service:test --tests "*IT"

# Test coverage
./gradlew :services:identity-service:test jacocoTestReport
```

## Deployment & Rollout

### Pre-Deployment

1. ✓ Flyway migrations reviewed and tested
2. ✓ JWKS endpoint reachable from all consumers
3. ✓ Database credentials rotated (if applicable)
4. ✓ Rate limit thresholds validated for expected load

### Rolling Deploy

```bash
# Helm values update
values-prod.yaml:
  identity-service:
    image:
      tag: v1.2.3

# ArgoCD sync (automatic via webhook)
kubectl apply -f deploy/helm/templates/

# Verify deployment
kubectl rollout status deployment/identity-service -n instacommerce
kubectl logs -f deployment/identity-service -n instacommerce
```

### Health Checks

```bash
# Liveness (10s after startup)
curl http://localhost:8081/actuator/health/liveness

# Readiness (includes DB connectivity)
curl http://localhost:8081/actuator/health/readiness

# Metrics
curl http://localhost:8081/actuator/prometheus
```

### Rollback

```bash
# Helm rollback to previous release
helm rollback identity-service -1 -n instacommerce

# Or via ArgoCD
argocd app rollback identity-service
```

## Troubleshooting

### Issue: 429 Too Many Requests

**Symptoms**: Login requests returning `429` with `Retry-After` header.

**Root cause**: Rate limiter exhausted (5 req/min per IP).

**Resolution**:
1. Check client IP in logs
2. Verify rate limit settings in `IDENTITY_CORS_ORIGINS`
3. If legitimate load, increase `RateLimiterRegistry` limits
4. Note: Rate limiting is pod-local; multi-replica deployments can be bypassed

### Issue: JWT Validation Failures (401 Unauthorized)

**Symptoms**: Consumers reject valid-looking tokens.

**Root cause**: JWKS endpoint stale or not reachable.

**Resolution**:
1. Verify JWKS endpoint: `curl http://localhost:8081/.well-known/jwks.json`
2. Check Istio configuration: `kubectl get vs identity-service`
3. Validate JWT signature manually using online JWT decoder

### Issue: GDPR Erasure Event Not Received by Consumers

**Symptoms**: User data still present downstream after deletion.

**Root cause**: Outbox relay down or Debezium CDC stalled.

**Resolution**:
1. Check outbox relay logs: `kubectl logs outbox-relay-service`
2. Verify Kafka topic exists: `kafka-topics.sh --list`
3. Check consumer lag: `kafka-consumer-groups.sh --group=consumer-group --describe`

## References

- [Identity Service Full README](/services/identity-service/README.md) — Comprehensive spec
- [Spring Security Documentation](https://spring.io/projects/spring-security) — Framework reference
- [JWT Best Practices](https://tools.ietf.org/html/rfc8725) — RFC standards
- [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- [ADR-004: Event Envelope](/docs/adr/004-event-envelope.md) — Event contract definition
