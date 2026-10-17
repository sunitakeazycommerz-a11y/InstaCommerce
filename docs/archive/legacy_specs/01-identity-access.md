# 01 — Identity & Access Service (identity-service)

> **Bounded Context**: Authentication, Authorization, User Management  
> **Owner team**: Platform / Identity  
> **Runtime**: Java 21 + Spring Boot 4.0.x on GKE  
> **Primary datastore**: Cloud SQL for PostgreSQL 15  
> **Port (local)**: 8081 | **gRPC port**: 9081

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    API Gateway (Istio)                   │
│   ┌──────────┐  JWT validation at gateway (optional)    │
│   │Rate Limit│  10 req/s per IP on /auth/**              │
│   └──────────┘                                          │
└─────────────┬───────────────────────────────────────────┘
              │ HTTPS / mTLS
┌─────────────▼───────────────────────────────────────────┐
│                 identity-service                         │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │  AuthController│ │UserController│  │AdminController│  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
│         │                 │                  │          │
│  ┌──────▼─────────────────▼──────────────────▼───────┐  │
│  │              Application Service Layer             │  │
│  │  AuthService  │  UserService  │  TokenService      │  │
│  └──────┬────────┴──────┬────────┴──────┬────────────┘  │
│         │               │               │               │
│  ┌──────▼───────┐ ┌─────▼──────┐ ┌──────▼───────────┐  │
│  │ Domain Model │ │ Spring     │ │ JwtTokenProvider  │  │
│  │ User, Role   │ │ Security   │ │ (RS256 JWKS)      │  │
│  └──────┬───────┘ │ FilterChain│ └──────┬────────────┘  │
│         │         └────────────┘        │               │
│  ┌──────▼──────────────────────────────▼─────────────┐  │
│  │            Repository Layer (Spring Data JPA)      │  │
│  │   UserRepository │ RefreshTokenRepository          │  │
│  │   AuditLogRepository                               │  │
│  └──────────────────┬────────────────────────────────┘  │
│                     │                                   │
└─────────────────────┼───────────────────────────────────┘
                      │ r2dbc or JDBC
               ┌──────▼──────┐
               │ Cloud SQL   │
               │ PostgreSQL  │
               │ (identity)  │
               └─────────────┘
```

---

## 2. Java Package Structure

```
com.instacommerce.identity
├── IdentityServiceApplication.java          # @SpringBootApplication
├── config/
│   ├── SecurityConfig.java                  # SecurityFilterChain bean
│   ├── JwtConfig.java                       # RSA key loading from Secret Manager
│   ├── CorsConfig.java
│   └── RateLimitConfig.java                 # Resilience4j rate limiter for auth endpoints
├── controller/
│   ├── AuthController.java                  # /auth/register, /auth/login, /auth/refresh, /auth/revoke
│   ├── UserController.java                  # /users/{id}, /users/me
│   └── AdminController.java                 # /admin/users (list, role changes)
├── dto/
│   ├── request/
│   │   ├── RegisterRequest.java             # @Valid: email, password (min 8 chars, 1 upper, 1 digit)
│   │   ├── LoginRequest.java
│   │   ├── RefreshRequest.java
│   │   └── RevokeRequest.java
│   ├── response/
│   │   ├── AuthResponse.java                # accessToken, refreshToken, expiresIn, tokenType
│   │   ├── UserResponse.java
│   │   └── ErrorResponse.java               # code, message, traceId, timestamp, details[]
│   └── mapper/
│       └── UserMapper.java                  # MapStruct mapper
├── domain/
│   ├── model/
│   │   ├── User.java                        # JPA @Entity
│   │   ├── RefreshToken.java                # JPA @Entity
│   │   ├── AuditLog.java                    # JPA @Entity (append-only)
│   │   └── Role.java                        # enum: CUSTOMER, ADMIN, PICKER, RIDER, SUPPORT
│   └── event/
│       └── UserRegisteredEvent.java         # Domain event (optional Kafka publish)
├── service/
│   ├── AuthService.java                     # register, login, refresh, revoke
│   ├── UserService.java                     # getUser, updateProfile
│   ├── TokenService.java                    # generateAccessToken, generateRefreshToken, validateToken
│   └── AuditService.java                    # logAction(actorId, action, targetId, metadata)
├── repository/
│   ├── UserRepository.java                  # extends JpaRepository<User, UUID>
│   ├── RefreshTokenRepository.java
│   └── AuditLogRepository.java
├── security/
│   ├── JwtAuthenticationFilter.java         # OncePerRequestFilter: extracts JWT, sets SecurityContext
│   ├── JwtTokenProvider.java                # issue & verify JWTs using RSA keys
│   └── UserDetailsServiceImpl.java          # loads User from DB for Spring Security
├── exception/
│   ├── AuthException.java
│   ├── UserNotFoundException.java
│   ├── TokenExpiredException.java
│   └── GlobalExceptionHandler.java          # @RestControllerAdvice, maps to ErrorResponse
└── infrastructure/
    ├── gcp/
    │   └── SecretManagerKeyLoader.java      # loads RSA keypair from GCP Secret Manager at startup
    └── metrics/
        └── AuthMetrics.java                 # Micrometer counters (auth_requests_total, etc.)
```

---

## 3. Domain Model (Entity Relationships)

```
┌──────────────────────────────────────────────┐
│ User                                          │
│──────────────────────────────────────────────│
│ id          : UUID (PK, gen_random_uuid())   │
│ email       : VARCHAR(255) UNIQUE NOT NULL    │
│ password_hash: VARCHAR(72) NOT NULL (BCrypt)  │
│ roles       : VARCHAR[] NOT NULL              │
│ status      : VARCHAR(20) DEFAULT 'ACTIVE'   │
│ created_at  : TIMESTAMPTZ DEFAULT now()      │
│ updated_at  : TIMESTAMPTZ DEFAULT now()      │
│ version     : BIGINT (optimistic locking)    │
└───────┬──────────────────────────────────────┘
        │ 1:N
┌───────▼──────────────────────────────────────┐
│ RefreshToken                                  │
│──────────────────────────────────────────────│
│ id          : UUID (PK)                      │
│ user_id     : UUID FK -> users(id)           │
│ token_hash  : VARCHAR(64) NOT NULL (SHA-256) │
│ device_info : VARCHAR(255)                   │
│ expires_at  : TIMESTAMPTZ NOT NULL           │
│ revoked     : BOOLEAN DEFAULT false          │
│ created_at  : TIMESTAMPTZ DEFAULT now()      │
└──────────────────────────────────────────────┘

┌──────────────────────────────────────────────┐
│ AuditLog (append-only, no UPDATE/DELETE)      │
│──────────────────────────────────────────────│
│ id          : BIGSERIAL (PK)                 │
│ actor_id    : UUID                           │
│ action      : VARCHAR(50) NOT NULL           │
│ target_type : VARCHAR(50)                    │
│ target_id   : VARCHAR(255)                   │
│ metadata    : JSONB                          │
│ ip_address  : INET                           │
│ trace_id    : VARCHAR(32)                    │
│ created_at  : TIMESTAMPTZ DEFAULT now()      │
└──────────────────────────────────────────────┘
```

---

## 4. Complete Database Migrations (Flyway)

### V1__create_users.sql
```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(72)  NOT NULL,
    roles          VARCHAR[]    NOT NULL DEFAULT '{CUSTOMER}',
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','SUSPENDED','DELETED'))
);

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_status ON users (status) WHERE status = 'ACTIVE';
```

### V2__create_refresh_tokens.sql
```sql
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL,
    device_info VARCHAR(255),
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expiry ON refresh_tokens (expires_at) WHERE revoked = false;
```

### V3__create_audit_log.sql
```sql
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID,
    action      VARCHAR(50)  NOT NULL,
    target_type VARCHAR(50),
    target_id   VARCHAR(255),
    metadata    JSONB,
    ip_address  INET,
    trace_id    VARCHAR(32),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Partition by month for retention management
-- For MVP, simple table; partition in production
CREATE INDEX idx_audit_log_actor   ON audit_log (actor_id);
CREATE INDEX idx_audit_log_action  ON audit_log (action);
CREATE INDEX idx_audit_log_created ON audit_log (created_at);

-- Prevent UPDATE/DELETE at DB level
REVOKE UPDATE, DELETE ON audit_log FROM identity_app_user;
```

---

## 5. REST API Contract (complete)

### 5.1 POST /auth/register
```yaml
Request:
  Content-Type: application/json
  Body:
    email: string (required, valid email, max 255)
    password: string (required, min 8, must contain uppercase + digit)

Response 201:
  Content-Type: application/json
  Body:
    userId: "550e8400-e29b-41d4-a716-446655440000"
    message: "Registration successful"

Response 409:
  Body:
    code: "USER_ALREADY_EXISTS"
    message: "A user with this email already exists"
    traceId: "abc123def456"
    timestamp: "2026-02-06T10:00:00Z"

Response 400:
  Body:
    code: "VALIDATION_ERROR"
    message: "Invalid input"
    details:
      - field: "password"
        message: "Password must be at least 8 characters with 1 uppercase and 1 digit"
```

### 5.2 POST /auth/login
```yaml
Request:
  Body:
    email: string (required)
    password: string (required)

Response 200:
  Body:
    accessToken: "<access-token-placeholder>"
    refreshToken: "<refresh-token-placeholder>"
    expiresIn: 900            # seconds (15 min)
    tokenType: "Bearer"

Response 401:
  Body:
    code: "INVALID_CREDENTIALS"
    message: "Email or password is incorrect"

Response 429:
  Body:
    code: "RATE_LIMIT_EXCEEDED"
    message: "Too many login attempts. Try again in 60 seconds."
    retryAfter: 60
```

### 5.3 POST /auth/refresh
```yaml
Request:
  Body:
    refreshToken: string (required)

Response 200:
  Body:
    accessToken: "<access-token-placeholder>"
    refreshToken: "<refresh-token-placeholder>"   # NEW token (rotation)
    expiresIn: 900

Response 401:
  Body:
    code: "TOKEN_EXPIRED" | "TOKEN_REVOKED" | "TOKEN_INVALID"
```

### 5.4 POST /auth/revoke
```yaml
Request:
  Headers:
    Authorization: Bearer <accessToken>
  Body:
    refreshToken: string (required)

Response 204: (no body)
```

### 5.5 GET /users/me
```yaml
Request:
  Headers:
    Authorization: Bearer <accessToken>

Response 200:
  Body:
    id: "550e8400-..."
    email: "user@example.com"
    roles: ["CUSTOMER"]
    status: "ACTIVE"
    createdAt: "2026-01-15T10:00:00Z"
```

### 5.6 GET /users/me/notification-preferences
```yaml
Request:
  Headers:
    Authorization: Bearer <accessToken>

Response 200:
  Body:
    userId: "550e8400-..."
    emailOptOut: false
    smsOptOut: false
    pushOptOut: false
    marketingOptOut: false
```

### 5.7 PUT /users/me/notification-preferences
```yaml
Request:
  Headers:
    Authorization: Bearer <accessToken>
  Body:
    emailOptOut: false
    smsOptOut: false
    pushOptOut: false
    marketingOptOut: false

Response 200:
  Body:
    userId: "550e8400-..."
    emailOptOut: false
    smsOptOut: false
    pushOptOut: false
    marketingOptOut: false
```

---

## 6. JWT Token Structure

### Access Token (RS256, 15 min TTL)
```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "key-2026-01"
}
{
  "iss": "https://auth.instacommerce.local",
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "aud": "instacommerce-api",
  "exp": 1738840000,
  "iat": 1738839100,
  "jti": "unique-token-id-uuid",
  "roles": ["CUSTOMER"],
  "email": "user@example.com"
}
```

### Key Management
- RSA 2048-bit keypair stored in **GCP Secret Manager** (secret name: `identity-jwt-rsa-keypair`)
- Key rotation: generate new keypair every 90 days. Old public key stays in JWKS for 30 days after rotation for graceful transition.
- JWKS endpoint: GET /.well-known/jwks.json (public, served by identity-service)

---

## 7. Spring Security Filter Chain

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/register", "/auth/login", "/auth/refresh").permitAll()
                .requestMatchers("/.well-known/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

---

## 8. GCP Cloud Guidelines

### 8.1 Cloud SQL (PostgreSQL 15)
- **Instance**: `identity-db` in same region as GKE cluster
- **Tier**: `db-custom-2-8192` (2 vCPU, 8 GB RAM) — scale based on load
- **HA**: Enable regional availability for prod; single-zone for dev
- **Connection**: Use **Cloud SQL Auth Proxy** as sidecar in pod (not public IP)
- **IAM**: Pod uses Workload Identity → binds to `identity-sa@<project>.iam.gserviceaccount.com` with `roles/cloudsql.client`

### 8.2 Secret Manager
- Store: RSA keypair, DB password, any API keys
- Access via Workload Identity: grant `roles/secretmanager.secretAccessor` to service account
- **Spring integration**: Use `spring-cloud-gcp-starter-secretmanager` to inject secrets as properties

### 8.3 application.yml (production profile)
```yaml
spring:
  application:
    name: identity-service
  datasource:
    url: jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${CLOUD_SQL_INSTANCE}&socketFactory=com.google.cloud.sql.postgres.SocketFactory
    username: ${DB_USER}
    password: ${sm://identity-db-password}  # resolved from Secret Manager
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 5000
      max-lifetime: 1800000

  jpa:
    hibernate:
      ddl-auto: validate   # Flyway handles migrations
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC

  flyway:
    enabled: true
    locations: classpath:db/migration

jwt:
  private-key: ${sm://identity-jwt-private-key}
  public-key: ${sm://identity-jwt-public-key}
  access-token-ttl: 900       # 15 min in seconds
  refresh-token-ttl: 604800   # 7 days in seconds

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    tags:
      application: identity-service
```

---

## 9. Resilience & Rate Limiting

```yaml
# application.yml — Resilience4j config
resilience4j:
  ratelimiter:
    instances:
      loginLimiter:
        limitForPeriod: 5
        limitRefreshPeriod: 60s
        timeoutDuration: 0s
      registerLimiter:
        limitForPeriod: 3
        limitRefreshPeriod: 60s
        timeoutDuration: 0s
```

Apply via annotation:
```java
@RateLimiter(name = "loginLimiter", fallbackMethod = "loginRateLimited")
public AuthResponse login(LoginRequest request) { ... }
```

---

## 10. Observability

### Metrics (Micrometer → Prometheus)
| Metric Name | Type | Labels | Description |
|---|---|---|---|
| `auth_register_total` | Counter | status={success,failure} | Registration attempts |
| `auth_login_total` | Counter | status={success,failure} | Login attempts |
| `auth_refresh_total` | Counter | status={success,failure} | Token refresh attempts |
| `auth_token_revoke_total` | Counter | | Token revocations |
| `auth_login_duration_seconds` | Histogram | | Login latency |

### Structured Log Format
```json
{
  "timestamp": "2026-02-06T10:00:00.123Z",
  "level": "INFO",
  "service": "identity-service",
  "traceId": "abc123def456789",
  "spanId": "def456",
  "userId": "550e8400-...",
  "message": "Login successful",
  "action": "LOGIN",
  "ip": "10.0.1.5"
}
```
**NEVER LOG**: passwords, password hashes, full tokens, refresh tokens.

### SLOs
| SLO | Target | Window |
|---|---|---|
| Login availability | 99.9% | 30 days |
| Login p95 latency | < 200ms | 30 days |
| Registration availability | 99.9% | 30 days |

---

## 11. Error Code Catalogue

| HTTP | Code | Message | When |
|---|---|---|---|
| 400 | VALIDATION_ERROR | see details[] | Input validation fails |
| 401 | INVALID_CREDENTIALS | Email or password incorrect | Wrong login |
| 401 | TOKEN_EXPIRED | Token has expired | JWT past exp |
| 401 | TOKEN_REVOKED | Token has been revoked | Refresh token revoked |
| 401 | TOKEN_INVALID | Token is malformed | Bad JWT |
| 403 | ACCESS_DENIED | Insufficient permissions | Missing role |
| 404 | USER_NOT_FOUND | User not found | GET /users/{id} miss |
| 409 | USER_ALREADY_EXISTS | Email already registered | Duplicate register |
| 429 | RATE_LIMIT_EXCEEDED | Too many requests | Rate limiter |
| 500 | INTERNAL_ERROR | An unexpected error occurred | Unhandled |

---

## 12. Testing Strategy

### Unit Tests
- `TokenServiceTest`: verify JWT generation, claims, expiry, signature
- `AuthServiceTest`: mock UserRepository and TokenService; test register, login, refresh flows
- `SecurityConfigTest`: verify endpoint access rules

### Integration Tests (Testcontainers)
- Spin up PostgreSQL container
- Full flow: register → login → access /users/me → refresh → revoke → refresh fails (401)
- Concurrent login test: 50 threads; all succeed without deadlocks

### Contract Tests
- Assert exact JSON shape of AuthResponse and ErrorResponse
- Assert JWT claims structure matches documented schema

### Load Test (Gatling/JMeter)
- 500 concurrent logins; p95 < 200ms; 0 errors
- 100 concurrent registrations; no duplicate users

---

## 13. Kubernetes Deployment Spec (Helm values)

```yaml
# values.yaml for identity-service
replicaCount: 2
image:
  repository: gcr.io/<project>/identity-service
  tag: ""  # set by CI
resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: 1000m
    memory: 1Gi
hpa:
  enabled: true
  minReplicas: 2
  maxReplicas: 8
  targetCPUUtilization: 70
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "prod"
  - name: DB_NAME
    value: "identity"
  - name: CLOUD_SQL_INSTANCE
    valueFrom:
      configMapKeyRef:
        name: identity-config
        key: cloud-sql-instance
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 20
  periodSeconds: 5
```

---

## 14. Agent Instructions (CRITICAL — read before writing any code)

### MUST DO
1. Use the **exact** package structure in section 2. Do not rename packages.
2. Use **MapStruct** for DTO mapping (never manual mapping in controllers).
3. All passwords MUST be hashed with **BCrypt** (strength 12). Never store plain text.
4. All refresh tokens MUST be hashed with **SHA-256** before storing in DB. The raw token is returned to the client only once.
5. Implement **refresh token rotation**: on every refresh call, invalidate the old refresh token and issue a new one.
6. Use **@Transactional** on service methods that write to DB (not on controllers).
7. Every endpoint must return the exact error format from section 11 (ErrorResponse with code, message, traceId, timestamp).
8. Use **Flyway** for migrations — DDL goes in `src/main/resources/db/migration/`. Never use `ddl-auto: create`.
9. RSA keys are loaded from GCP Secret Manager (or env vars for local dev). Never hardcode keys.
10. Add **audit log entries** for: LOGIN, LOGIN_FAILED, REGISTER, TOKEN_REFRESH, TOKEN_REVOKE, ROLE_CHANGE.

### MUST NOT DO
1. Do NOT create additional REST endpoints beyond those in section 5.
2. Do NOT log passwords, password hashes, or full JWT/refresh tokens.
3. Do NOT use symmetric keys (HS256) for JWTs — use RS256 only.
4. Do NOT store raw refresh tokens in the database.
5. Do NOT add Spring Session or session-based auth — this is stateless JWT only.
6. Do NOT add new database tables without a Flyway migration file.
7. Do NOT catch exceptions silently — all exceptions must bubble to GlobalExceptionHandler.

### DEFAULTS (when not specified)
- Access token TTL: 900 seconds (15 min)
- Refresh token TTL: 604800 seconds (7 days)
- BCrypt strength: 12
- Max refresh tokens per user: 5 (delete oldest when exceeded)
- Rate limit login: 10/min per IP
- Rate limit register: 5/min per IP
- Local dev database: `jdbc:postgresql://localhost:5432/identity_db` user=`postgres` pass=`devpass`

### ESCALATION (when uncertain)
If you encounter any of these, add a `// TODO(AGENT): <question>` comment and move on:
- Whether to support OAuth2/OIDC providers (Google, Apple login)
- Whether to support MFA/2FA
- Whether to implement account lockout after N failed attempts
- Any requirement not explicitly listed in this document

---

## 15. Dependencies (build.gradle excerpt)

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
    runtimeOnly    'io.jsonwebtoken:jjwt-impl:0.12.5'
    runtimeOnly    'io.jsonwebtoken:jjwt-jackson:0.12.5'
    implementation 'org.mapstruct:mapstruct:1.5.5.Final'
    annotationProcessor 'org.mapstruct:mapstruct-processor:1.5.5.Final'
    implementation 'com.google.cloud:spring-cloud-gcp-starter-secretmanager'
    implementation 'com.google.cloud.sql:postgres-socket-factory:1.15.0'
    implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
    
    runtimeOnly 'org.postgresql:postgresql'
    
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'org.testcontainers:postgresql:1.19.3'
    testImplementation 'org.testcontainers:junit-jupiter:1.19.3'
}
```

---

## 16. Sequence Diagrams

### Login Flow
```
Client              Gateway           IdentityService         DB
  │                    │                    │                   │
  │  POST /auth/login  │                    │                   │
  │───────────────────>│                    │                   │
  │                    │  rate limit check   │                   │
  │                    │───────────────────>│                   │
  │                    │                    │  SELECT user by    │
  │                    │                    │  email             │
  │                    │                    │──────────────────>│
  │                    │                    │  user row          │
  │                    │                    │<──────────────────│
  │                    │                    │                   │
  │                    │                    │  BCrypt.verify()   │
  │                    │                    │                   │
  │                    │                    │  generate JWT      │
  │                    │                    │  generate refresh  │
  │                    │                    │  hash refresh      │
  │                    │                    │                   │
  │                    │                    │  INSERT refresh    │
  │                    │                    │  token             │
  │                    │                    │──────────────────>│
  │                    │                    │  INSERT audit log  │
  │                    │                    │──────────────────>│
  │                    │                    │                   │
  │                    │  200 AuthResponse   │                   │
  │                    │<───────────────────│                   │
  │  200 AuthResponse  │                    │                   │
  │<───────────────────│                    │                   │
```

### Token Refresh Flow
```
Client              IdentityService         DB
  │                    │                     │
  │ POST /auth/refresh │                     │
  │───────────────────>│                     │
  │                    │ SHA-256(token)       │
  │                    │ SELECT by hash      │
  │                    │────────────────────>│
  │                    │ refresh_token row   │
  │                    │<────────────────────│
  │                    │                     │
  │                    │ check: !revoked     │
  │                    │ check: !expired     │
  │                    │                     │
  │                    │ UPDATE old: revoked │
  │                    │────────────────────>│
  │                    │ INSERT new refresh  │
  │                    │────────────────────>│
  │                    │ generate new JWT    │
  │                    │                     │
  │ 200 AuthResponse   │                     │
  │<───────────────────│                     │
```
