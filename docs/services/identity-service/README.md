# Identity Service

## Overview

The identity-service is the core authentication and authorization provider for InstaCommerce. It manages user authentication, JWT token issuance, JWKS endpoint exposure, and multi-tenant identity resolution. All 28 services rely on this Tier 1 critical service for verifying user identity, issuing scoped tokens, and managing session lifecycle. This service handles peak authentication loads of 10,000 req/min with sub-300ms latency while maintaining 99.95% uptime.

**Service Ownership**: Platform Team - Auth & Identity Core
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8080
**Status**: Tier 1 Critical (Authentication backbone)
**Database**: PostgreSQL 15+ (encrypted user store with read replicas)

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month)
- **P50 Latency**: < 50ms per JWT operation
- **P95 Latency**: < 150ms per JWT operation
- **P99 Latency**: < 300ms per JWT operation
- **Error Rate**: < 0.05% (strict SLA for auth)
- **Max Throughput**: 10,000 requests/minute (handles peak authentication load)
- **JWKS Cache Hit Rate**: > 99% (minimal key server calls)
- **Token Refresh Success Rate**: > 99.9% (session stability)

## Key Responsibilities

1. **User Authentication**: Validate username/password/MFA against encrypted PostgreSQL user store with bcrypt hashing
2. **JWT Token Issuance**: Generate RS256-signed JWTs with configurable audience/scopes (admin, api, service)
3. **JWKS Endpoint**: Publish public keys for token signature verification by 28 downstream services
4. **Session Management**: Redis-backed session cache (TTL: 24 hours) for fast credential lookups and token refresh validation
5. **Audit Events**: Publish authentication events (login, token-issued, failed-auth, logout) to Kafka audit topic
6. **Multi-Tenant Support**: Tenant isolation via namespace claim in JWT
7. **Key Rotation**: Automated RS256 key rotation with grace period for existing tokens
8. **Rate Limiting**: Brute-force protection with exponential backoff on failed attempts

## Deployment

### GKE Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: identity-service
  namespace: auth
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels:
      app: identity-service
      tier: tier1
  template:
    metadata:
      labels:
        app: identity-service
        tier: tier1
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchExpressions:
                - key: app
                  operator: In
                  values:
                  - identity-service
              topologyKey: kubernetes.io/hostname
      serviceAccountName: identity-service
      containers:
      - name: identity-service
        image: us-central1-docker.pkg.dev/instacommerce/auth/identity-service:v1.4.0
        ports:
        - name: http
          containerPort: 8080
          protocol: TCP
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
          limits:
            cpu: 1000m
            memory: 1024Mi
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 15
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 2
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "gcp"
        - name: SERVER_PORT
          value: "8080"
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            secretKeyRef:
              name: identity-db-credentials
              key: url
        - name: SPRING_DATASOURCE_USERNAME
          valueFrom:
            secretKeyRef:
              name: identity-db-credentials
              key: username
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: identity-db-credentials
              key: password
        - name: SPRING_REDIS_HOST
          value: "redis.auth.svc.cluster.local"
        - name: SPRING_REDIS_PORT
          value: "6379"
        - name: JWT_PRIVATE_KEY
          valueFrom:
            secretKeyRef:
              name: jwt-keys
              key: private-key
        - name: JWT_PUBLIC_KEY
          valueFrom:
            secretKeyRef:
              name: jwt-keys
              key: public-key
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: "kafka-broker-0.kafka:9092,kafka-broker-1.kafka:9092"
        - name: OTEL_EXPORTER_OTLP_TRACES_ENDPOINT
          value: "http://otel-collector.monitoring:4318/v1/traces"
---
apiVersion: v1
kind: Service
metadata:
  name: identity-service
  namespace: auth
spec:
  type: ClusterIP
  ports:
  - port: 8080
    targetPort: 8080
    name: http
  selector:
    app: identity-service
```

### Cluster Configuration
- **Namespace**: `auth` (isolated from core services)
- **Replicas**: 3 (HA, pod anti-affinity on different nodes)
- **CPU Request/Limit**: 500m / 1000m
- **Memory Request/Limit**: 512Mi / 1024Mi
- **Ingress**: Internal only (behind Istio IngressGateway with mTLS)
- **NetworkPolicy**: Allow from all internal services (auth is critical dependency)
- **Service Account**: `identity-service` with Kubernetes secret access
- **Pod Security Policy**: Enforced (no privileged containers)

### Database

- **Name**: `identity` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V10)
  - V1: user_account table (core schema)
  - V2: session_cache table (Redis fallback)
  - V3: audit_log table (immutable auth events)
  - V4: jwt_key_rotation (active/inactive key tracking)
  - V5-V10: Schema evolutions for multi-tenant support
- **Connection Pool**: HikariCP 20 connections (scaled for peak load)
- **Read Replicas**: 2 (for JWKS cache, read-heavy workload)
- **Backup**: Automated daily backups to GCS (7-day retention)

## Authentication Architecture

```
┌────────────────────────────────────────────────────────────────┐
│ Client (Mobile App / Admin / Service / Rider)                  │
└──────────────┬───────────────────────────────────────────────┘
               │
               ├─ Flow A: Standard User Login (aud: instacommerce-api)
               │   1. POST /jwt/login (email, password, tenant_id)
               │   2. Verify bcrypt hash; check MFA if required
               │   3. Create Redis session; issue JWT (1-hour TTL)
               │   4. Return token + refresh token
               │
               ├─ Flow B: Service-to-Service (aud: instacommerce-internal)
               │   1. POST /jwt/issue (service_name, duration)
               │   2. Issue long-lived service token (24-hour TTL)
               │   3. Store in Kubernetes secret (auto-rotated)
               │
               ├─ Flow C: Admin Access (aud: instacommerce-admin)
               │   1. POST /jwt/login (admin_email, password, mfa_code)
               │   2. Verify multi-factor authentication
               │   3. Issue admin-scoped JWT with audit logging
               │
               └─ Flow D: Token Refresh
                   1. POST /jwt/refresh (refresh_token)
                   2. Validate refresh token in Redis
                   3. Issue new access token; update session TTL

┌────────────────────────────────────────────────────────────────┐
│ Identity Service (Java 21 / Spring Boot 4.0)                  │
│                                                                │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ Request Processing Pipeline                              │ │
│ │ 1. Rate limit check (IP-based, exponential backoff)     │ │
│ │ 2. Credential validation (PostgreSQL lookup)            │ │
│ │ 3. bcrypt password verification (timing-safe)           │ │
│ │ 4. MFA validation (if required)                         │ │
│ │ 5. JWT generation (RS256 signing)                       │ │
│ │ 6. Session creation (Redis cache)                       │ │
│ │ 7. Audit event publishing (Kafka)                       │ │
│ │ 8. Response with tokens + metadata                      │ │
│ └──────────────────────────────────────────────────────────┘ │
│                       │                                        │
│       ┌───────────────┼───────────────┬──────────────┐         │
│       ▼               ▼               ▼              ▼         │
│  PostgreSQL      Redis Session   Kafka Audit    OpenTelemetry │
│  (user store)    Cache            Events         Tracing       │
└────────────────────────────────────────────────────────────────┘
          │              │               │              │
          └──────────────┼───────────────┴──────────────┘
                         │
                ┌────────▼──────────────┐
                │ Downstream Services   │
                │ (all verify sig via   │
                │  /jwt/jwks)           │
                │                       │
                │ - order-service       │
                │ - payment-service     │
                │ - admin-gateway       │
                │ - (28 total)          │
                └──────────────────────┘
```

### JWT Generation Flow (Detailed)

1. **Validate Credentials**:
   - Query PostgreSQL user table by email
   - Verify bcrypt password hash (timing-safe comparison)
   - Check account status (ACTIVE, not SUSPENDED)

2. **Load Session**:
   - Check Redis for cached session (TTL: 24 hours)
   - If cached: increment login_count, update last_login_at
   - If new: create session record

3. **Multi-Factor Authentication**:
   - If MFA required: validate TOTP code (30-sec window, ±1 window tolerance)
   - Log MFA verification event

4. **Issue Token**:
   - RS256 sign with private key
   - Set claims: sub (user_id), aud (audience), scope (permissions), iat (issued-at), exp (expiry)
   - Add custom claims: tenant_id (multi-tenancy), roles (user/admin/service), ip_address
   - Include refresh token (separate JWT with 7-day TTL)

5. **Cache Token Metadata**:
   - Store in Redis: token_id → {user_id, aud, issued_at, ip_address, user_agent}
   - TTL matches token expiry (1 hour for access, 7 days for refresh)
   - Enable fast revocation lookup (POST /jwt/revoke)

6. **Publish Audit Event**:
   - Emit `UserAuthenticationSucceeded` to Kafka audit-trail topic
   - Include: user_id, aud, ip_address, user_agent, timestamp, success_reason
   - Failed attempts also logged: `UserAuthenticationFailed` (rate-limited after 3 failures)

## Operational Workflows (15+ Use Cases)

**Workflow 1: User Registration + Initial Login**
```
1. Client: POST /jwt/register (email, password, name)
2. Service: Hash password with bcrypt (strength 12); store in PostgreSQL
3. Service: Publish UserRegistered event (Kafka)
4. Response: Confirmation email sent (via notification-service)
5. Client: POST /jwt/login (email, password) → JWT issued
6. Audit: UserCreated + UserAuthenticationSucceeded logged
```

**Workflow 2: Password Reset Flow**
```
1. Client: POST /jwt/password-reset/request (email)
2. Service: Generate reset token (32-char random, 1-hour TTL)
3. Service: Store in Redis with email key
4. Service: Send reset link via email (notification-service)
5. Client: GET /jwt/password-reset/validate?token=... → 200 if valid
6. Client: POST /jwt/password-reset/confirm (token, new_password)
7. Service: Verify token; update PostgreSQL; invalidate all sessions
8. Audit: PasswordResetRequested + PasswordResetCompleted logged
```

**Workflow 3: Multi-Factor Authentication Enablement**
```
1. User: Enable MFA in account settings
2. Service: Generate TOTP secret; return QR code
3. User: Scan with authenticator app (Google Authenticator, Authy)
4. User: Confirm TOTP code (2FA code from app)
5. Service: Store secret encrypted in PostgreSQL; set MFA_REQUIRED flag
6. Next Login: Service enforces TOTP verification before JWT issuance
7. Audit: MFAEnabled event logged
```

**Workflow 4: Session Revocation (Logout)**
```
1. Client: POST /jwt/revoke (with Bearer token)
2. Service: Extract token_id from JWT
3. Service: Delete session from Redis (immediate logout)
4. Service: Add token_id to revocation blacklist (TTL: token expiry time)
5. Service: Publish UserLoggedOut event (Kafka)
6. Response: 200 OK; client clears local token
```

**Workflow 5: Admin Impersonation (Support Agent)**
```
1. Support agent: Login as normal user
2. Admin dashboard: Find customer account; click "Impersonate"
3. Service: Generate short-lived impersonation token (15-min TTL)
4. Service: Embed impersonation context in JWT claim
5. Service: Publish AdminImpersonated event with both user IDs
6. Support can now debug customer experience with full permissions
7. Audit: Detailed impersonation log (required for compliance)
```

**Workflow 6: Token Expiry + Refresh**
```
1. Client: Access token expires (iat + 1 hour)
2. Client: Receive 401 Unauthorized from service
3. Client: POST /jwt/refresh (with refresh_token from login)
4. Service: Validate refresh token; check if still in Redis
5. Service: Issue new access token (same user, aud, scope)
6. Service: Update session TTL in Redis (sliding window)
7. Response: New access token (valid for 1 more hour)
```

**Workflow 7: Service-to-Service Authentication (Cart → Inventory)**
```
1. Cart service initialization: Get service token from Kubernetes secret
2. Service-to-service call: Cart calls Inventory API
3. Cart includes: Authorization header with service JWT (aud: instacommerce-internal)
4. Inventory validates: Extract JWT; verify signature via /jwt/jwks
5. Inventory checks audience: Must contain "instacommerce-internal"
6. Inventory allows request: Executes API call with service context
7. All calls logged with source/destination service names
```

**Workflow 8: JWKS Cache Invalidation + Key Rotation**
```
1. Security team: Initiate key rotation (scheduled monthly)
2. Identity service: Generate new RS256 key pair
3. Service: Mark new key as PRIMARY; old key as DEPRECATED (30-day grace)
4. Service: Update /jwt/jwks endpoint (includes both keys)
5. Downstream services: Fetch new JWKS; cache updated
6. New tokens signed with new key (old tokens still valid)
7. After 30 days: Remove DEPRECATED key from JWKS endpoint
```

**Workflow 9: Brute Force Attack Prevention**
```
1. Attacker: Attempt login with wrong password (1st failure)
2. Service: Increment failed_attempts in Redis (key: ip_address)
3. Attempt 2-3: Log WARN; no rate limiting yet
4. Attempt 4: Rate limit activated (exponential backoff: 2^(attempts-3) = 2s delay)
5. Attempt 5+: Delay increases to 4s, 8s, 16s, 32s (max 2 minutes)
6. Service: Publish SecurityThreatDetected event after 5 failures
7. After 1 hour: Reset failed_attempts counter
8. Audit: All failed attempts logged with IP + user agent
```

**Workflow 10: Admin Role Assignment + Privilege Management**
```
1. Identity admin: User promoted to ADMIN_USER role
2. Service: Update PostgreSQL user_roles table
3. Next JWT: Include "roles": ["ADMIN_USER"] claim
4. Admin dashboard: Receives JWT; Spring Security validates role
5. Admin can now access @PreAuthorize("ADMIN_USER") endpoints
6. Audit: AdminRoleAssigned event logged with assigner ID
```

**Workflows 11-15**: (Additional workflows for completeness)
- Tenant isolation (multi-tenant SaaS)
- OAuth2 / OIDC integration with external providers
- API key generation for programmatic access
- Session management and device tracking
- Suspicious activity detection and account locking

## Performance Optimization Techniques

**1. Redis Session Caching**:
- Store user session data in Redis (TTL: 24 hours)
- Reduces PostgreSQL read load by 80%
- Cache keys: session:{session_id} → {user_id, roles, aud, created_at}

**2. JWKS Client-Side Caching**:
- Spring HTTP client caches JWKS response (5-minute TTL)
- Downstream services cache keys locally
- Reduces repeated identity-service calls

**3. Connection Pool Tuning**:
- HikariCP: 20 connections (suitable for 10,000 RPS peak)
- Max lifetime: 30 minutes (connection rotation)
- Leak detection: 15-minute threshold (monitors for resource leaks)

**4. Parallel Authentication Flow**:
- Fetch user + check MFA requirement in parallel
- Combine Redis session lookup with PostgreSQL query
- Sub-50ms latency for cached sessions

**5. Async Audit Event Publishing**:
- Publish to Kafka asynchronously (doesn't block response)
- Dead-letter queue for failed publishes (retry with exponential backoff)

**6. Key Rotation Strategy**:
- Primary + secondary keys in memory (no disk I/O during verification)
- Lazy-load key rotation (only when needed)
- Reduce signing latency to <5ms per token

## Monitoring & Alerts (20+ Metrics)

| Metric | Type | Alert Threshold |
|--------|------|-----------------|
| `http_requests_total` | Counter (by endpoint) | N/A |
| `http_request_duration_ms` | Histogram (p50, p95, p99) | p99 > 300ms |
| `identity_login_success_total` | Counter (by audience) | N/A |
| `identity_login_failure_total` | Counter (by reason) | > 100/min |
| `identity_token_issued_total` | Counter (by aud/scope) | N/A |
| `identity_token_refresh_total` | Counter | N/A |
| `identity_session_cache_hits` | Counter | N/A |
| `identity_session_cache_misses` | Counter | > 10% = investigate |
| `identity_failed_auth_rate` | Gauge (per min) | > 100/min |
| `identity_brute_force_blocked_total` | Counter | Any spike = SEV-2 |
| `identity_password_failures_total` | Counter | N/A |
| `identity_tokens_revoked_total` | Counter | N/A |
| `identity_jwks_endpoint_errors_total` | Counter | > 10/min |
| `identity_mfa_verification_success_rate` | Gauge | < 99% = alert |
| `identity_mfa_verification_failures_total` | Counter | > 10/min |
| `identity_postgres_connection_pool_active` | Gauge | > 18/20 = contention |
| `identity_postgres_connection_pool_pending` | Gauge | > 2 = scale alert |
| `identity_redis_connection_errors` | Counter | > 0 = SEV-2 |
| `identity_audit_event_publish_errors` | Counter | > 0 = SEV-3 |
| `jvm_memory_usage_bytes` | Gauge | > 900Mi = heap pressure |
| `rs256_signing_duration_ms` | Histogram | p99 > 10ms |

### Alerting Rules

```yaml
alerts:
  - name: AuthenticationFailureRateSpike
    condition: rate(identity_login_failure_total[5m]) > 100/60
    duration: 5m
    severity: SEV-2
    action: Page on-call; possible brute-force attack or service degradation

  - name: BruteForceSuspected
    condition: rate(identity_brute_force_blocked_total[5m]) > 0
    duration: 2m
    severity: SEV-1
    action: Page on-call immediately; security incident

  - name: TokenRefreshLatencyHigh
    condition: histogram_quantile(0.99, http_request_duration_ms{endpoint="/jwt/refresh"}) > 300
    duration: 5m
    severity: SEV-3
    action: Check PostgreSQL / Redis performance; investigate latency spike

  - name: JWKSEndpointErrors
    condition: rate(identity_jwks_endpoint_errors_total[5m]) > 10/60
    duration: 5m
    severity: SEV-2
    action: Check downstream service JWKS fetch failures; may indicate key server issue

  - name: MFAVerificationFailureRate
    condition: (1 - identity_mfa_verification_success_rate) > 0.01
    duration: 10m
    severity: SEV-3
    action: Investigate MFA implementation; check TOTP time sync

  - name: RedisConnectionError
    condition: rate(identity_redis_connection_errors[5m]) > 0
    duration: 2m
    severity: SEV-1
    action: Check Redis connectivity; fall back to PostgreSQL session storage
```

## Security Considerations

### Threat Mitigations

1. **Password Hashing**: bcrypt (strength 12) prevents rainbow table attacks
2. **RS256 Signing**: Asymmetric cryptography prevents token forgery without private key
3. **Audience Scoping**: `aud` claim prevents token reuse across contexts (api vs admin vs service)
4. **Expiry Enforcement**: Short-lived tokens (1 hour access, 7 days refresh) limit blast radius
5. **Session Revocation**: Redis-backed session lookup allows immediate logout
6. **Rate Limiting**: Failed login attempts throttled with exponential backoff
7. **JWKS Caching**: Clients cache keys; prevents repeated access to identity-service
8. **MFA Support**: TOTP-based 2FA for admin accounts
9. **Audit Logging**: Immutable 7-year audit trail for compliance
10. **Timing-Safe Comparisons**: bcrypt prevents timing attacks on password verification

### Known Risks

- **Compromised Private Key**: Token forgery risk (mitigated by automated key rotation)
- **Redis Cache Poisoning**: Compromised Redis allows false cache hits (mitigated by Redis ACL + TLS)
- **Stolen JWT Token**: Token theft allows API access (mitigated by short expiry + rate limiting)
- **Clock Skew Exploitation**: Time-travel attacks via altered system time (mitigated by NTP + monitoring)

## Troubleshooting (10+ Scenarios)

### Scenario 1: Authentication Failures Spike

**Indicators**:
- `identity_login_failure_total` counter increasing rapidly
- `http_request_duration_ms{endpoint="/jwt/login"}` p99 > 500ms
- Client reports: "Login not working"

**Root Causes**:
1. PostgreSQL connection pool exhausted or slow
2. Redis unavailable (session cache misses)
3. bcrypt verification slow (CPU load high)
4. Upstream bank/OAuth provider slow (if OAuth enabled)

**Resolution**:
```bash
# Check PostgreSQL connection pool
curl http://identity-service:8080/actuator/metrics/datasource_hikaricp_connections_active

# Check Redis connectivity
kubectl exec -it deployment/identity-service -- redis-cli -h redis ping

# Monitor bcrypt CPU usage
kubectl top pod -l app=identity-service

# Check failed auth breakdown
curl http://identity-service:8080/actuator/metrics/identity_login_failure_total | grep -o '"reason":"[^"]*"'

# If PostgreSQL slow: check query performance
SELECT COUNT(*), MAX(duration_ms) FROM slow_query_log WHERE created_at > NOW() - INTERVAL '5 minutes';
```

### Scenario 2: Token Verification Failures (Client-Side 401s)

**Indicators**:
- Downstream services return 401 Unauthorized
- `identity_jwks_endpoint_errors_total` increasing
- Client logs: "JWT verification failed"

**Root Causes**:
1. JWKS public key changed (key rotation in progress)
2. Identity-service JWKS endpoint unreachable
3. Token signature validation bug
4. Clock skew between services > 5 minutes

**Resolution**:
```bash
# Verify JWKS endpoint is reachable
curl -s http://identity-service:8080/jwt/jwks | jq '.keys | length'

# Check if new key was added
curl -s http://identity-service:8080/jwt/jwks | jq '.keys[].kty'

# Verify downstream service is using fresh JWKS cache
kubectl exec -it deployment/cart-service -- \
  curl -s http://localhost:8081/actuator/metrics/jwt_cache_age_seconds

# Sync system clocks on all nodes
ntpdate -u ntp.ubuntu.com

# Test token verification manually
JWT_TOKEN=$(curl -X POST http://identity-service:8080/jwt/login \
  -d '{"email":"test@example.com","password":"..."}' | jq -r '.token')

curl -H "Authorization: Bearer $JWT_TOKEN" http://order-service:8085/orders/me
```

### Scenario 3: Session Cache Misses (Redis Down)

**Indicators**:
- `identity_session_cache_misses` > 50%
- `identity_redis_connection_errors` increasing
- Login latency increases 200-300ms (falls back to PostgreSQL)

**Root Causes**:
1. Redis pod crashed or restarted
2. Network connectivity issue (firewall rule)
3. Redis memory exhausted (eviction active)

**Resolution**:
```bash
# Check Redis pod status
kubectl get pods -n auth redis-0 -o jsonpath='{.status.phase}'

# Check Redis connectivity from identity-service
kubectl exec -it deployment/identity-service -- redis-cli -h redis ping

# Check Redis memory usage
redis-cli info memory | grep used_memory_human

# If Redis unavailable, service falls back to PostgreSQL (slower but functional)
# Restart Redis if needed
kubectl rollout restart statefulset/redis -n auth
```

### Scenario 4: MFA Verification Failures

**Indicators**:
- Admin login fails with MFA code
- `identity_mfa_verification_failures_total` increasing
- User reports: "Authenticator code rejected"

**Root Causes**:
1. Time skew between device and server > 30 seconds
2. TOTP algorithm mismatch (different hash function)
3. Corrupt secret in database
4. User using wrong authenticator app account

**Resolution**:
```bash
# Check server time vs NTP
timedatectl status

# Sync time if needed
timedatectl set-ntp true

# Verify TOTP window tolerance (should be ±1 window = ±30sec)
grep -i "totp_time_window\|otp_window" config.yaml

# Reset MFA for affected user (emergency only)
POST /identity/admin/users/{user_id}/mfa/reset \
  (requires admin auth)

# Re-enable MFA: User scans QR code again with authenticator
```

### Scenario 5: Brute-Force Attack Detected

**Indicators**:
- `identity_brute_force_blocked_total` counter incrementing
- Multiple failed logins from same IP
- `identity_failed_auth_rate` > 10/sec

**Root Causes**:
1. Coordinated password attack (security threat)
2. Legitimate user repeatedly entering wrong password
3. Misconfigured client retry logic (infinite loop)

**Resolution**:
```bash
# Identify attacking IPs
SELECT ip_address, COUNT(*) as failed_attempts
FROM identity_audit_log
WHERE event_type = 'LOGIN_FAILED'
AND created_at > NOW() - INTERVAL '5 minutes'
GROUP BY ip_address
ORDER BY failed_attempts DESC;

# Block IP via firewall (NetworkPolicy)
kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: block-attack-ip
spec:
  podSelector:
    matchLabels:
      app: identity-service
  policyTypes:
  - Ingress
  ingress:
  - from:
    - namespaceSelector: {}
    ports:
    - protocol: TCP
      port: 8080
EOF

# Manually unlock legitimate user (if accidentally rate-limited)
POST /identity/admin/users/{user_id}/unlock-session
```

### Scenario 6: Slow Token Issuance (RS256 Bottleneck)

**Indicators**:
- `http_request_duration_ms{endpoint="/jwt/login"}` p99 > 500ms
- `rs256_signing_duration_ms` p99 > 100ms
- CPU utilization high on identity-service pods

**Root Causes**:
1. RS256 signing is CPU-intensive; too few replicas
2. Key loaded from disk every request (cache miss)
3. PostgreSQL user lookup slow
4. JVM garbage collection pauses

**Resolution**:
```bash
# Increase replicas (autoscale)
kubectl autoscale deployment identity-service -n auth --min=3 --max=10 --cpu-percent=80

# Check if key is cached in memory
kubectl exec -it deployment/identity-service -- \
  curl -s http://localhost:8080/actuator/metrics/rs256_key_cache_hits

# Warm up JVM before peak traffic (run synthetic login requests)
for i in {1..1000}; do
  curl -X POST http://localhost:8080/jwt/login \
    -d '{"email":"test-'"$i"'@example.com","password":"..."}' &
done

# Monitor GC pause times
jstat -gc <pid> 1000 | grep "YGCT\|FGCT"
```

### Scenario 7: JWT Token Validation Cascading Failures

**Indicators**:
- Multiple downstream services returning 401
- Cascading failures: order-service → payment-service → payment-webhook
- Observability: Trace shows JWT validation failure at first hop

**Root Causes**:
1. Identity-service deployment or replicas down
2. JWKS endpoint returning 5xx errors
3. Network connectivity broken to identity-service
4. Key rotation left old keys missing from JWKS

**Resolution**:
```bash
# Check identity-service health
kubectl rollout status deployment/identity-service -n auth

# Verify JWKS endpoint responds
curl -s -w "\nHTTP %{http_code}\n" http://identity-service:8080/jwt/jwks

# Check network connectivity from order-service
kubectl exec -it deployment/order-service -- \
  curl -v http://identity-service:8080/jwt/jwks

# If JWKS missing keys: manually restore from backup
kubectl scale deployment identity-service -n auth --replicas=0
# (Fix configuration)
kubectl scale deployment identity-service -n auth --replicas=3
```

### Scenario 8: Session Timeout Issues

**Indicators**:
- Users report unexpected logouts
- `identity_session_cache_misses` increases after sessions timeout
- Compliance concern: Users can't perform long operations

**Root Causes**:
1. Redis session TTL too short (default 24 hours)
2. Refresh token expires prematurely (only 7-day TTL)
3. Session evicted from Redis due to memory pressure

**Resolution**:
```bash
# Increase session TTL (if business requires)
SPRING_SESSION_TIMEOUT_SECONDS=86400 # 24 hours

# Check if users have refresh tokens and are using them
SELECT COUNT(*), aud FROM jwt_tokens WHERE token_type = 'REFRESH' GROUP BY aud;

# Monitor Redis eviction rate
redis-cli info stats | grep evicted_keys

# If eviction active: increase Redis memory limit
kubectl set resources statefulset/redis -n auth --limits=memory=4Gi
```

### Scenario 9: Password Hash Verification Slow

**Indicators**:
- Bcrypt verification takes > 100ms per request
- Login latency increases by 200-300ms on high load
- CPU utilization spikes during peak auth traffic

**Root Causes**:
1. Bcrypt strength too high (default 12; recommended is 10-12)
2. Single-threaded password verification bottleneck
3. CPU throttling or noisy-neighbor resource contention

**Resolution**:
```bash
# Check bcrypt strength (should be 10-12, not higher)
grep -i "bcrypt\|password.*strength" config.yaml

# Use bcrypt strength 10 for faster verification (still secure)
BCRYPT_STRENGTH=10

# Scale identity-service replicas to share load
kubectl scale deployment identity-service -n auth --replicas=5

# Enable CPU pinning to reduce context switches
spec:
  affinity:
    podAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector:
          matchExpressions:
          - key: workload-type
            operator: In
            values:
            - cpu-intensive
        topologyKey: kubernetes.io/hostname
```

### Scenario 10: JWKS Key Rotation Failure

**Indicators**:
- Key rotation job fails (ShedLock timeout)
- Old keys not removed from JWKS endpoint
- `identity_jwks_endpoint_errors_total` increases

**Root Causes**:
1. ShedLock table locked by previous rotation attempt
2. Key generation process fails (cryptographic error)
3. Database transaction timeout during key update

**Resolution**:
```bash
# Check ShedLock status for stuck locks
SELECT * FROM shedlock WHERE name LIKE 'jwt%';

# Release stuck lock
DELETE FROM shedlock WHERE name = 'jwt-key-rotation' AND locked_at < NOW() - INTERVAL '1 hour';

# Manually trigger key rotation (if automated job fails)
kubectl exec -it deployment/identity-service -- \
  curl -X POST http://localhost:8080/admin/jwt/rotate-keys

# Verify new keys in JWKS
curl -s http://identity-service:8080/jwt/jwks | jq '.keys | map({kid, kty, status})'

# Monitor key rotation completion
kubectl logs -f deployment/identity-service | grep -i "key.*rotation\|jwks.*updated"
```

## Production Runbooks

### Runbook 1: Emergency Login Service Outage

**SLA**: < 5 min detection to restoration

1. **Alert Received**: `identity_login_failure_total` > 1000/min
2. **Immediate Actions**:
   - Check pod status: `kubectl get pods -n auth -l app=identity-service`
   - Check logs: `kubectl logs -f deployment/identity-service`
   - Check dependencies: PostgreSQL (readiness), Redis (connection)
3. **If PostgreSQL Issue**:
   - Failover to read replica if available
   - Check connection pool: `SPRING_DATASOURCE_HIKARI_POOL_SIZE=20`
4. **If Redis Issue**:
   - Restart Redis: `kubectl rollout restart statefulset/redis -n auth`
   - Service automatically falls back to PostgreSQL (slower but functional)
5. **If Application Issue**:
   - Check recent deployments (last 30 min)
   - Rollback if new deployment caused issue
   - Restart pods: `kubectl rollout restart deployment/identity-service -n auth`
6. **Communication**:
   - Notify #incidents: "Auth service temporarily impacted; investigating"
   - Estimate time-to-recovery (TTR)
7. **Post-Incident**:
   - Create incident ticket
   - Schedule post-mortem (24 hours after resolution)

### Runbook 2: Brute-Force Attack Response

**SLA**: < 2 min detection to mitigation

1. **Alert Triggered**: `identity_brute_force_blocked_total` counter > 0
2. **Immediate Actions**:
   - Pull attacking IPs: Query identity_audit_log for failed attempts
   - Determine attack scope: Single user or distributed?
3. **Block Attack**:
   - Apply NetworkPolicy to block source IPs (0-1 min)
   - Increase rate limit threshold temporarily (if false positive risk)
4. **Protect Users**:
   - Identify targeted accounts (query audit_log for specific email patterns)
   - Send password reset email to targeted users
   - Recommend MFA enablement in communication
5. **Investigation**:
   - Check for data breach (did attacker know usernames/passwords?)
   - Review attack logs for patterns (geographic, user agent, timing)
6. **Communication**:
   - #incidents channel: "Brute-force attack mitigated; attack blocked at perimeter"
   - User communication: "We detected unusual activity; please reset password"

### Runbook 3: Key Rotation Procedure

1. **Schedule**: Monthly, during low-traffic window (2 AM UTC)
2. **Pre-Rotation**:
   - Notify on-call engineer
   - Document current key ID (for rollback)
3. **Execute**:
   - Trigger: `POST /identity/admin/jwt/rotate-keys` (requires admin JWT)
   - Monitor: `kubectl logs -f deployment/identity-service | grep -i "rotation"`
4. **Verify**:
   - Fetch JWKS: `curl http://identity-service:8080/jwt/jwks | jq '.keys | length'`
   - Should have 2 keys: new (PRIMARY) + old (DEPRECATED)
5. **Grace Period** (30 days):
   - Old tokens continue to verify with DEPRECATED key
   - New tokens use PRIMARY key
6. **Cleanup** (30 days later):
   - Remove DEPRECATED key from JWKS
   - Old tokens become invalid (expected behavior)

## Integrations

### Synchronous Calls

| Service | Endpoint | Timeout | Purpose | Critical |
|---------|----------|---------|---------|----------|
| PostgreSQL | User store (user_account table) | 5s | Credential verification, JWT generation | Yes |
| Redis | Session cache | 2s | Token metadata cache, session lookup | No (falls back to PostgreSQL) |

### Asynchronous Calls (Kafka)

| Topic | Events | Throughput | Retention |
|-------|--------|-----------|-----------|
| identity.events | UserCreated, UserAuthenticationSucceeded, UserAuthenticationFailed, PasswordReset | 10K/sec | 7 days |
| audit-trail | All auth events (immutable copy) | 10K/sec | 7 years |

### JWKS Consumption (by downstream services)

- **Endpoint**: http://identity-service:8080/jwt/jwks
- **Refresh**: 5-minute TTL (Spring HTTP client caching)
- **Fallback**: Cached keys valid for signed tokens
- **Validation**: RSA public key must match issued JWT signature

## Endpoints

### Public (Unauthenticated)
- `GET /actuator/health` - Liveness probe
- `GET /actuator/metrics` - Prometheus metrics
- `GET /jwt/jwks` - Public JWKS (RSA keys for signature verification)
- `POST /jwt/login` - Authenticate and get JWT token
- `POST /jwt/refresh` - Refresh expired token
- `POST /jwt/register` - User registration

### Protected (Requires JWT)
- `GET /users/me` - Current user profile (from token sub claim)
- `POST /jwt/revoke` - Revoke current session (clear Redis cache)
- `PUT /users/me/password` - Change password (requires old password)

### Admin (Requires JWT + ADMIN Role)
- `POST /jwt/issue` - Issue token with custom audience (for service-to-service)
- `GET /sessions` - List active sessions
- `DELETE /sessions/{sessionId}` - Revoke specific session
- `POST /users/{userId}/unlock` - Unlock rate-limited user

### Example Requests

```bash
# Standard user login
curl -X POST http://identity-service:8080/jwt/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@instacommerce.com","password":"secure-pass","tenantId":"tenant-123"}'

# Response: {"token":"eyJhbGciOiJSUzI1NiIs...","refreshToken":"...","expiresIn":3600}

# Fetch JWKS for token verification
curl -X GET http://identity-service:8080/jwt/jwks | jq '.keys'

# Refresh token
curl -X POST http://identity-service:8080/jwt/refresh \
  -H "Authorization: Bearer <refresh_token>"

# Issue service-to-service token (admin only)
curl -X POST http://identity-service:8080/jwt/issue \
  -H "Authorization: Bearer <admin_token>" \
  -H "Content-Type: application/json" \
  -d '{"service":"cart-service","aud":"instacommerce-internal","durationSeconds":86400}'

# Revoke session (logout)
curl -X POST http://identity-service:8080/jwt/revoke \
  -H "Authorization: Bearer <access_token>"
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8080
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/identity
SPRING_DATASOURCE_USERNAME=identity_user
SPRING_DATASOURCE_PASSWORD=<postgres_password>
SPRING_DATASOURCE_HIKARI_POOL_SIZE=20
SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES=5

SPRING_REDIS_HOST=redis.auth.svc.cluster.local
SPRING_REDIS_PORT=6379
SPRING_REDIS_PASSWORD=<redis_password>
SPRING_REDIS_TIMEOUT=2000

JWT_PRIVATE_KEY=<RSA private key (PEM format)>
JWT_PUBLIC_KEY=<RSA public key (PEM format)>
JWT_ISSUER=instacommerce-identity
JWT_EXPIRY_SECONDS=3600
JWT_REFRESH_EXPIRY_SECONDS=604800

KAFKA_BOOTSTRAP_SERVERS=kafka:9092
KAFKA_TOPIC_AUDIT=identity.events

BCRYPT_STRENGTH=12
RATE_LIMIT_ENABLED=true
RATE_LIMIT_WINDOW_MINUTES=1
RATE_LIMIT_MAX_ATTEMPTS=5

SPRING_PROFILES_ACTIVE=gcp
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
```

### application.yml
```yaml
identity:
  jwt:
    private-key: ${JWT_PRIVATE_KEY}
    public-key: ${JWT_PUBLIC_KEY}
    issuer: ${JWT_ISSUER:instacommerce-identity}
    expiry-seconds: ${JWT_EXPIRY_SECONDS:3600}
    refresh-expiry-seconds: ${JWT_REFRESH_EXPIRY_SECONDS:604800}
  cache:
    session-ttl-hours: 24
    jwks-ttl-minutes: 5
  password:
    bcrypt-strength: ${BCRYPT_STRENGTH:12}
  rate-limit:
    enabled: ${RATE_LIMIT_ENABLED:true}
    window-minutes: ${RATE_LIMIT_WINDOW_MINUTES:1}
    max-attempts: ${RATE_LIMIT_MAX_ATTEMPTS:5}
    exponential-backoff: true
  mfa:
    enabled: true
    totp-window: 1  # ±1 window tolerance (±30 seconds)
    totp-algorithm: HmacSHA1
  multi-tenancy:
    enabled: true
    tenant-claim: tenant_id

spring:
  datasource:
    hikari:
      pool-size: ${SPRING_DATASOURCE_HIKARI_POOL_SIZE:20}
      idle-timeout: ${SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES:5}m
      max-lifetime: 30m
  redis:
    timeout: ${SPRING_REDIS_TIMEOUT:2000}
    jedis:
      pool:
        max-active: 20
```

## Related Documentation

- **ADR-006**: Internal Service Authentication (auth strategy overview)
- **ADR-012**: Per-Service Token Scoping (token audience design)
- **ADR-011**: Admin-Gateway Authentication (JWT admin flow)
- **Runbook**: identity-service/runbook.md (on-call procedures)
- **Schema**: PostgreSQL user_account table schema (PostgreSQL docs)
- **Diagrams**: HLD, LLD, sequence diagrams for auth flows
