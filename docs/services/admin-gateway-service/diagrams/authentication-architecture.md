# Admin-Gateway Authentication Architecture

## JWT Validation Flow Diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         Admin User (Internal)                              │
│                                                                            │
│  - Identity: admin@instacommerce.com                                      │
│  - Role: ADMIN / OPERATOR                                                │
│  - Access: Dashboard, Flags, Reconciliation                              │
└─────────────────────────────┬──────────────────────────────────────────────┘
                              │
                              │ 1. Login Request
                              │    username=admin
                              │    password=***
                              ▼
                 ┌────────────────────────────────┐
                 │   Identity-Service             │
                 │   POST /jwt/issue              │
                 │                                │
                 │   Generate JWT:                │
                 │   {                           │
                 │     "sub": "admin-1",          │
                 │     "aud": "instacommerce-admin"│
                 │     "roles": ["ADMIN"],        │
                 │     "iat": 1711108400,         │
                 │     "exp": 1711194800          │
                 │   }                            │
                 │                                │
                 │   Signed with RSA private key  │
                 └────────────────┬───────────────┘
                                  │
                              2. JWT Token
                              (valid for 24h)
                                  │
                                  ▼
                    ┌──────────────────────────────────┐
                    │  Admin Client                    │
                    │                                  │
                    │ Authorization: Bearer {JWT}      │
                    │ GET /admin/v1/dashboard          │
                    └────────────┬─────────────────────┘
                                 │
                                 │ 3. HTTP Request with Bearer Token
                                 │
                                 ▼
        ┌────────────────────────────────────────────────┐
        │         Admin-Gateway-Service                  │
        │                                                │
        │  AdminJwtAuthenticationFilter:                │
        │                                                │
        │  1. Extract Bearer Token                      │
        │     header = "Authorization: Bearer {JWT}"    │
        │     token = JWT                               │
        │                                                │
        │  2. Request JWKS from Identity-Service        │
        │     (Cached, TTL = 5 min)                    │
        │     GET /jwt/jwks                             │
        │                                                │
        │  3. Parse JWT Header & Signature             │
        │     - alg: RS256                              │
        │     - kid: (key id)                           │
        │     - Find matching public key from JWKS      │
        │                                                │
        │  4. Verify Signature                          │
        │     RSA(publicKey, signature) ?= hash(payload)│
        │     ❌ Invalid → 401 Unauthorized             │
        │                                                │
        │  5. Validate Claims                           │
        │     a) issuer: "instacommerce-identity"       │
        │        ❌ Mismatch → 401 Unauthorized        │
        │     b) audience: ["instacommerce-admin"]      │
        │        ❌ Missing or wrong → 401 Unauthorized│
        │     c) expiry: exp > now (5min clock skew)    │
        │        ❌ Expired → 401 Unauthorized         │
        │                                                │
        │  6. Extract Authorities                       │
        │     roles[] → ROLE_ADMIN, ROLE_OPERATOR      │
        │                                                │
        │  7. Create SecurityContext                    │
        │     Principal: admin-1                        │
        │     Authorities: [ROLE_ADMIN]                 │
        │                                                │
        │  ✅ Authentication Success                   │
        │                                                │
        └──────────────┬─────────────────────────────────┘
                       │
                       │ 4. Authenticated Request (SecurityContext set)
                       │
                       ▼
        ┌────────────────────────────────────────────┐
        │    AdminDashboardController                │
        │                                            │
        │    @GetMapping("/admin/v1/dashboard")     │
        │    @PreAuthorize("hasRole('ADMIN')")      │
        │                                            │
        │    1. Check Principal: admin-1            │
        │    2. Check Authority: ROLE_ADMIN ✅     │
        │    3. Aggregate metrics:                  │
        │       - Order volume (24h)                │
        │       - Payment volume (24h)              │
        │       - Fulfillment rate (pending/done)   │
        │                                            │
        │    Response 200 Ok:                       │
        │    {                                       │
        │      "status": "ok",                       │
        │      "orderVolume": {"today": 1524},       │
        │      "paymentVolume": {"today": 85420.50}, │
        │      "fulfillmentRate": {"pending": 234}   │
        │    }                                        │
        └──────────────┬───────────────────────────┘
                       │
                       │ 5. Response to Client
                       ▼
                    Admin Client
                    Display Dashboard
```

## Audience Scoping Model

```
┌────────────────────────────────────────────────────────────────────┐
│                    Identity-Service JWT Issuance                   │
│                                                                    │
│  Token audiences are scoped by use-case to prevent reuse attacks:  │
└────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────┐
│ instacommerce-api                │  Customer-facing operations
│ (Mobile App, Web BFF)            │  - User authentication
│ aud: instacommerce-api           │  - Order placement (→ checkout-orchestrator)
│                                  │  - Payment status queries
│ Duration: 1 hour                 │  - Cart operations
│ Refresh: Via refresh_token       │
├──────────────────────────────────┤
│ ❌ Cannot access /admin/v1/**    │
│ ❌ Rejected by AdminJwtFilter    │
│    (aud mismatch)                │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│ instacommerce-admin              │  Internal admin operations
│ (Admin Portal, Internal)          │  - Dashboard access
│ aud: instacommerce-admin         │  - Feature flag management
│                                  │  - Reconciliation queries
│ Duration: 24 hours               │
│ Issued by: Identity-Service      │
├──────────────────────────────────┤
│ ✅ Can access /admin/v1/**      │
│ ✅ Validated by AdminJwtFilter  │
│    (aud matches)                 │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│ instacommerce-internal           │  Inter-service communications
│ (Order → Payment, etc.)          │  - Service-to-service auth
│ aud: instacommerce-internal      │  - CDC connectors
│                                  │  - Scheduled jobs
│ Duration: 30 minutes             │
│ Issued by: Identity-Service      │
├──────────────────────────────────┤
│ ❌ Cannot access /admin/v1/**    │
│ ❌ Different audience scope      │
└──────────────────────────────────┘
```

## Error Scenarios & HTTP Status Codes

```
┌──────────────────────────────────────────────────────────────────┐
│              JWT Validation Error Scenarios                       │
└──────────────────────────────────────────────────────────────────┘

Scenario 1: Missing Authorization Header
──────────────────────────────────────────
  Request: GET /admin/v1/dashboard
           (No Authorization header)

  AdminJwtAuthenticationFilter: header == null → pass through
  SecurityConfig: anyRequest().authenticated() → 401

  Response: 401 Unauthorized
  Body: { "error": "JWT token is missing" }

Scenario 2: Expired JWT Token
──────────────────────────────────────────
  Token: JWT with exp: 1711108000 (past)

  AdminJwtAuthenticationFilter:
    1. Parse: ✅ OK
    2. Verify Signature: ✅ OK (RSA)
    3. Check issuer: ✅ OK
    4. Check audience: ✅ OK
    5. Check expiry: ❌ FAIL
       now (1711108400) > exp (1711108000)
       Even with 5-min clock skew, token is invalid

  JwtException: "Token claims have expired"

  Response: 401 Unauthorized
  Body: {
    "code": "TOKEN_INVALID",
    "message": "Token is invalid or expired: ...",
    "timestamp": "2026-03-21T..."
  }

Scenario 3: Wrong Audience
──────────────────────────────────────────
  Token: aud: "instacommerce-api"

  AdminJwtAuthenticationFilter:
    1. Parse: ✅ OK
    2. Verify Signature: ✅ OK (RSA)
    3. Check issuer: ✅ OK
    4. Check audience: ❌ FAIL
       Expected: "instacommerce-admin"
       Got: ["instacommerce-api"]
       Token intended for customer API, not admin

  JwtException: "Token audience 'instacommerce-api' does not match..."

  Response: 401 Unauthorized
  Body: {
    "code": "TOKEN_INVALID",
    "message": "Token audience ... does not match...",
    "timestamp": "2026-03-21T..."
  }

Scenario 4: Invalid Signature
──────────────────────────────────────────
  Token: Valid structure, but signed with attacker's key

  AdminJwtAuthenticationFilter:
    1. Parse: ✅ OK
    2. Verify Signature: ❌ FAIL
       RSA(publicKey, sig) ≠ hash(payload)
       Token modified or forged

  JwtException: "JWT signature does not match locally..."

  Response: 401 Unauthorized
  Body: {
    "code": "TOKEN_INVALID",
    "message": "Token is invalid or expired: JWT...",
    "timestamp": "2026-03-21T..."
  }

Scenario 5: Missing Audience Claim
──────────────────────────────────────────
  Token: { "sub": "admin", "iss": "instacommerce-identity" }
         (No "aud" field)

  AdminJwtAuthenticationFilter:
    4. Check audience: ❌ FAIL
       audiences == null || !audiences.contains("instacommerce-admin")

  JwtException: "Token audience 'null' does not match..."

  Response: 401 Unauthorized

Scenario 6: Insufficient Role
──────────────────────────────────────────
  Token: Valid for instacommerce-admin, but roles: ["OPERATOR"]

  AdminJwtAuthenticationFilter: ✅ PASS (all validations)

  SecurityConfig: Authorities = [ROLE_OPERATOR]

  AdminDashboardController:
    @PreAuthorize("hasRole('ADMIN')")
    ❌ FAIL: OPERATOR ≠ ADMIN

  Response: 403 Forbidden
  Body: {
    "status": 403,
    "error": "Forbidden",
    "message": "Access Denied",
    "timestamp": "2026-03-21T..."
  }
```

## Deployment Flow Diagram

```
┌────────────────────────────────────────────────────────────────┐
│                  Helm Deployment Flow                          │
└────────────────────────────────────────────────────────────────┘

1. Prerequisites
   ├─ GCP Service Account configured
   ├─ Kubernetes secrets created:
   │  ├─ JWT_PUBLIC_KEY (from identity-service)
   │  ├─ JWT_ISSUER
   │  └─ JWT_AUD
   └─ Namespace: admin

2. Helm Render (values.yaml + templates/)
   └─ Render RequestAuthentication policy:
      issuer: instacommerce-identity
      jwksUri: http://identity-service/jwt/jwks
      audiences: ["instacommerce-admin"]

3. Deploy to GKE
   ├─ Deployment: 2 replicas
   ├─ Service: ClusterIP (8099)
   ├─ ConfigMap: application.yml
   ├─ Secret: JWT_PUBLIC_KEY, JWT_ISSUER, JWT_AUD
   ├─ Istio RequestAuthentication: admin-gateway-service-jwt
   ├─ NetworkPolicy: Deny-default + allow-ingress
   └─ HPA: min=2, max=6, target=70% CPU

4. Istio VirtualService
   ├─ Host: admin-gateway-service
   ├─ Port: 8099
   ├─ Timeout: 30s
   └─ Retries: 3 (5xx errors)

5. Verification
   ├─ ✅ Pod starts successfully
   ├─ ✅ /admin/health returns 200
   ├─ ✅ Health check passes
   ├─ ✅ Prometheus scrapes metrics
   └─ ✅ Ready for traffic (Ingress routes requests)
```

## Clock Skew Tolerance

```
┌──────────────────────────────────────────────────────────────────┐
│                 JWT Expiry Validation Timeline                   │
└──────────────────────────────────────────────────────────────────┘

Token Lifetime:
  iat (issued at):      2026-03-21T12:00:00Z
  exp (expires at):     2026-03-22T12:00:00Z
  Duration: 24 hours

Clock Skew Tolerance: ±5 minutes (300 seconds)

Validity Window:
  iat - 300s = 2026-03-21T11:55:00Z  ← Token accepted before iat
  iat        = 2026-03-21T12:00:00Z  ← Normal issue time
  exp        = 2026-03-22T12:00:00Z  ← Normal expiry time
  exp + 300s = 2026-03-22T12:05:00Z  ← Token accepted after exp

┌─────────────────────────────────────────────────────┐
│  Token Valid Period (with clock skew)               │
│                                                     │
│  2026-03-21T11:55:00Z ───────────────────────       │
│                    ▲              ▲                 │
│         iat - 5min│    24h        │exp + 5min       │
│                    ────────────────                  │
│  2026-03-22T12:05:00Z                              │
│                                                     │
│  ❌ 2026-03-21T11:54:59Z (too early)               │
│  ❌ 2026-03-22T12:05:01Z (too late)                │
│  ✅ 2026-03-21T11:55:00Z (valid)                   │
│  ✅ 2026-03-22T12:04:59Z (valid)                   │
└─────────────────────────────────────────────────────┘

Why 5 minutes?
  - Allows for network latency (token in flight)
  - Covers cloud platform clock drift
  - Prevents tight clock coupling between services
  - Balances security vs. operational tolerance
```

## Monitoring & Observability

```
┌────────────────────────────────────────────────────────────────┐
│           Admin-Gateway Observability Dashboard                 │
└────────────────────────────────────────────────────────────────┘

Prometheus Metrics:
─────────────────────
  • http_server_requests_seconds_bucket
    - Method: GET, POST, PUT
    - Path: /admin/v1/dashboard, /admin/v1/flags, etc.
    - Status: 200, 401, 403, 500
    - Quantiles: p50, p95, p99

  • http_server_requests_total
    - Labels: method, path, status
    - Tracks total requests and errors

  • jvm_memory_usage_bytes
    - Labels: area (heap, nonheap)
    - Alert: heap > 80%

  • process_cpu_usage
    - Alert: CPU > 80%

Structured Logging (JSON):
──────────────────────────
  {
    "timestamp": "2026-03-21T12:30:45.123Z",
    "level": "WARN",
    "logger": "com.instacommerce.admingateway.security.AdminJwtAuthenticationFilter",
    "message": "JWT validation failed",
    "exception": "JwtException",
    "cause": "Token audience 'instacommerce-api' does not match 'instacommerce-admin'",
    "userId": "admin-1",
    "requestPath": "/admin/v1/dashboard",
    "requestMethod": "GET",
    "httpStatus": 401,
    "durationMs": 5
  }

OpenTelemetry Traces:
────────────────────
  Span: GET /admin/v1/dashboard
  ├─ Span: AdminJwtAuthenticationFilter
  │  ├─ Event: Extract Bearer token
  │  ├─ Event: Fetch JWKS from identity-service (cached)
  │  ├─ Event: Verify RSA signature
  │  ├─ Event: Validate audience claim
  │  └─ Event: Create SecurityContext
  └─ Span: AdminDashboardController.dashboard()
     ├─ Span: Call config-feature-flag-service
     ├─ Span: Query database (aggregation)
     └─ Event: Return 200 response

Alert Rules (Prometheus):
────────────────────────
  admin_gateway_jwt_validation_failures > 10/min
    → "Possible JWT attack; check audience/issuer/signature"

  admin_gateway_downstream_errors > 1%
    → "Feature-flag or reconciliation service down"

  admin_gateway_latency_p99 > 2000ms
    → "Admin-gateway performance degradation; check network"
```
