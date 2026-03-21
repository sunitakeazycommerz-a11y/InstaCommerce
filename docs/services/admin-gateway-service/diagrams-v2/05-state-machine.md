# Admin Gateway - State Machine Diagrams

## Request Processing State Machine (Complete Lifecycle)

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: HTTP Request arrived<br/>GET /api/admin/dashboard

    RECEIVED --> HEADER_EXTRACTED: Extract Authorization header<br/>(Bearer token)

    HEADER_EXTRACTED --> HEADER_VALIDATED: Check Bearer prefix<br/>format

    HEADER_VALIDATED --> MALFORMED_ERROR: ❌ Malformed header
    HEADER_VALIDATED --> JWT_PARSED: ✅ Valid format

    JWT_PARSED --> KID_EXTRACTED: Extract kid from<br/>JWT header

    KID_EXTRACTED --> JWKS_LOOKUP: Check JWKS cache

    JWKS_LOOKUP --> JWKS_CACHED: ✅ Found in cache<br/>(within 5min)
    JWKS_LOOKUP --> JWKS_MISS: Cache miss

    JWKS_MISS --> JWKS_FETCHED: Fetch from identity-service

    JWKS_FETCHED --> JWKS_STORED: Cache JWKS entry<br/>(5min TTL)

    JWKS_CACHED --> KEY_LOOKUP: Get public key<br/>from JWKS

    JWKS_STORED --> KEY_LOOKUP

    KEY_LOOKUP --> KEY_NOT_FOUND: ❌ kid not in JWKS
    KEY_LOOKUP --> SIG_VERIFY: Get public key<br/>for RS256 verification

    SIG_VERIFY --> SIG_INVALID: ❌ Signature mismatch
    SIG_VERIFY --> CLAIMS_EXTRACTED: ✅ Signature valid

    CLAIMS_EXTRACTED --> AUD_CHECK: Check aud claim<br/>must equal instacommerce-admin

    AUD_CHECK --> AUD_INVALID: ❌ Wrong audience
    AUD_CHECK --> EXP_CHECK: ✅ Audience correct

    EXP_CHECK --> EXPIRED: ❌ Token expired<br/>(exp < now)
    EXP_CHECK --> ROLE_CHECK: ✅ Not expired

    ROLE_CHECK --> ROLE_DENIED: ❌ No ROLE_ADMIN
    ROLE_CHECK --> CONTEXT_SET: ✅ Has ROLE_ADMIN

    CONTEXT_SET --> AUTHZ_CHECK: Set SecurityContext<br/>(principal + authorities)

    AUTHZ_CHECK --> RATE_LIMIT: Check rate limit<br/>(100 req/min per user)

    RATE_LIMIT --> RATE_LIMITED: ❌ Rate limit exceeded<br/>(>100/min)
    RATE_LIMIT --> CACHE_CHECK: ✅ Within rate limit

    CACHE_CHECK --> CACHE_HIT: ✅ Found in Redis<br/>dashboard_summary

    CACHE_CHECK --> CACHE_MISS: Cache miss

    CACHE_HIT --> CACHED_RESPONSE: Return cached data<br/>(~10ms)

    CACHE_MISS --> SERVICE_CALLS: Launch 3 parallel<br/>gRPC calls

    SERVICE_CALLS --> AGGREGATING: Aggregate responses<br/>from Payment, Fulfillment, Order

    AGGREGATING --> METRICS_CALC: Calculate KPIs<br/>- Payment SLO %<br/>- Fulfillment p99<br/>- Order latency

    METRICS_CALC --> RESPONSE_BUILD: Build DashboardDTO

    RESPONSE_BUILD --> CACHE_STORE: Store in Redis<br/>(5min TTL)

    CACHE_STORE --> SERIALIZED: Serialize to JSON

    SERIALIZED --> RESPONSE_SENT: Send 200 OK<br/>response

    CACHED_RESPONSE --> RESPONSE_SENT

    RESPONSE_SENT --> [*]

    MALFORMED_ERROR --> UNAUTHORIZED
    KEY_NOT_FOUND --> UNAUTHORIZED
    SIG_INVALID --> UNAUTHORIZED
    AUD_INVALID --> FORBIDDEN
    EXPIRED --> UNAUTHORIZED
    ROLE_DENIED --> FORBIDDEN
    RATE_LIMITED --> TOO_MANY

    UNAUTHORIZED --> ERROR_LOGGED: Log JWT_VALIDATION_FAILED return 401

    FORBIDDEN --> ERROR_LOGGED: Log AUTHZ_DENIED return 403

    TOO_MANY --> ERROR_LOGGED: Log RATE_LIMIT_EXCEEDED return 429

    ERROR_LOGGED --> [*]

    note right of CONTEXT_SET
        SecurityContext contains:
        - principal (user_id)
        - authorities (ROLE_ADMIN)
        - scope (instacommerce-admin)
    end note

    note right of CACHE_HIT
        Cache hit saves ~200-300ms
        from downstream service calls
    end note

    note right of SERVICE_CALLS
        Timeout per call: 300ms
        Wait for all 3 in parallel
        Fall back to cache if timeout
    end note
```

## JWT Token Lifecycle (Issued → Expired/Revoked)

```mermaid
stateDiagram-v2
    [*] --> ISSUED: Identity Service<br/>generates token<br/>GET /auth/token

    ISSUED --> SIGNED: Sign with RS256<br/>private key

    SIGNED --> STORED_CLIENT: Return to client<br/>(via TLS channel)

    STORED_CLIENT --> VALID: Within expiry window<br/>(exp > now)<br/>& not revoked

    VALID --> USED: Admin uses token<br/>in Authorization header<br/>for multiple requests

    USED --> VALIDATED: Admin Gateway<br/>validates token

    VALIDATED --> VALID: ✅ Valid<br/>continue using

    VALIDATED --> REVOKED_LOGOUT: ❌ User logged out
    VALIDATED --> REVOKED_SESSION: ❌ Session revoked<br/>(admin disabled)
    VALIDATED --> EXPIRED: ❌ exp < now()

    REVOKED_LOGOUT --> INVALID: Return 401
    REVOKED_SESSION --> INVALID: Return 401
    EXPIRED --> INVALID: Return 401

    VALID --> EXPIRED: Time passes expiry_time reached (default 1 hour)

    EXPIRED --> INVALID

    INVALID --> USER_MUST_REAUTH: User must login again<br/>GET /auth/token

    USER_MUST_REAUTH --> ISSUED

    note right of VALID
        Token can be used for
        multiple requests
        Each request validates
    end note

    note right of EXPIRED
        After expiration, token
        cannot be used
        Even if signature was valid
    end note

    note right of REVOKED_LOGOUT
        User click logout button
        Session deleted from backend
        All tokens for that session
        become invalid
    end note
```

## Authentication & Authorization State Flow

```mermaid
stateDiagram-v2
    [*] --> UNAUTHENTICATED: Initial request<br/>no JWT provided

    UNAUTHENTICATED --> AUTH_REQUIRED: Is endpoint<br/>protected?

    AUTH_REQUIRED --> REQUEST_DENIED: ❌ No JWT<br/>return 401

    AUTH_REQUIRED --> JWT_VALIDATION: ✅ JWT provided

    JWT_VALIDATION --> AUTH_SUCCESS: ✅ Valid JWT<br/>SecurityContext set<br/>principal + authorities

    JWT_VALIDATION --> AUTH_FAILED: ❌ Invalid JWT<br/>- bad signature<br/>- wrong audience<br/>- expired<br/>- missing role

    AUTH_FAILED --> REQUEST_DENIED

    AUTH_SUCCESS --> AUTHZ_CHECK: Check role-based<br/>access control

    AUTHZ_CHECK --> ADMIN_ONLY: Endpoint requires<br/>ROLE_ADMIN?

    ADMIN_ONLY --> HAS_ADMIN: ✅ Has<br/>ROLE_ADMIN

    ADMIN_ONLY --> NO_ADMIN: ❌ No<br/>ROLE_ADMIN

    NO_ADMIN --> AUTHZ_DENIED: return 403

    HAS_ADMIN --> AUTHENTICATED_AUTHORIZED: ✅ Both auth<br/>& authz passed<br/>Execute endpoint handler

    AUTHENTICATED_AUTHORIZED --> EXECUTE_HANDLER: Call<br/>AdminDashboardController<br/>method

    EXECUTE_HANDLER --> RESPONSE_OK: Return 200 OK<br/>with data

    RESPONSE_OK --> [*]

    REQUEST_DENIED --> [*]
    AUTHZ_DENIED --> [*]

    note right of JWT_VALIDATION
        Checks:
        1. Signature (RS256)
        2. Audience (instacommerce-admin)
        3. Expiration (exp > now)
        4. Roles array
    end note

    note right of AUTHZ_CHECK
        Check roles against
        endpoint @PreAuthorize
        or filter rules
    end note
```

## Rate Limiter State Machine

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: Service running<br/>Redis connected

    ACTIVE --> CHECK_LIMIT: Request arrives<br/>for user_id

    CHECK_LIMIT --> GET_COUNTER: Get user's<br/>request counter<br/>from Redis<br/>Key rate_limit_user_id

    GET_COUNTER --> WITHIN_LIMIT: counter < 100

    GET_COUNTER --> EXCEEDED: counter >= 100

    WITHIN_LIMIT --> INCREMENT: Increment counter<br/>INCR rate_limit_user_id

    INCREMENT --> SET_TTL: Set expiry<br/>EXPIRE 60s

    SET_TTL --> ALLOWED: ✅ Request allowed<br/>pass to next filter

    ALLOWED --> CONTINUE_CHAIN: Continue<br/>filter chain

    EXCEEDED --> LOG_EXCEEDED: Log<br/>RATE_LIMIT_EXCEEDED<br/>user_id, endpoint

    LOG_EXCEEDED --> REJECT: ❌ Request rejected<br/>return 429<br/>Too Many Requests

    REJECT --> RETRY_AFTER: Send header<br/>Retry-After 60

    CONTINUE_CHAIN --> METRIC_OK: Emit metric<br/>rate_limit_checks_passed

    RETRY_AFTER --> METRIC_FAILED: Emit metric<br/>rate_limit_rejections_total

    CONTINUE_CHAIN --> [*]
    METRIC_FAILED --> [*]

    ACTIVE --> REDIS_DOWN: Redis connection<br/>fails

    REDIS_DOWN --> FALLBACK: Fall back to<br/>in-memory counter<br/>(Caffeine cache)

    FALLBACK --> CHECK_LIMIT: Continue checking<br/>with Caffeine

    REDIS_DOWN --> REDIS_RECOVER: Redis reconnects

    REDIS_RECOVER --> SYNC_STATE: Sync in-memory<br/>state back to Redis

    SYNC_STATE --> ACTIVE

    note right of WITHIN_LIMIT
        Each user has limit:
        100 requests / minute
        Sliding window (60s)
    end note

    note right of FALLBACK
        Graceful degradation:
        if Redis down,
        use local cache
        (less accurate, no sharing)
    end note
```

## Circuit Breaker State Machine (Downstream Services)

```mermaid
stateDiagram-v2
    [*] --> CLOSED: Service healthy<br/>normal operation

    CLOSED --> CALL_SUCCESS: Request succeeds<br/>response OK

    CALL_SUCCESS --> RESET_FAILURE_COUNTER: error_count = 0

    RESET_FAILURE_COUNTER --> CLOSED

    CLOSED --> CALL_FAILURE: Request fails<br/>timeout, 5xx error

    CALL_FAILURE --> INCREMENT_FAILURE: error_count += 1

    INCREMENT_FAILURE --> THRESHOLD_CHECK: error_count<br/>below threshold?<br/>(threshold = 5)

    THRESHOLD_CHECK --> CLOSED: Counter not<br/>reached yet

    THRESHOLD_CHECK --> OPEN: ❌ Threshold reached<br/>or error_rate > 50%

    OPEN --> FAIL_FAST: All requests<br/>fail immediately<br/>return cached response<br/>or default value

    FAIL_FAST --> METRICS: Emit<br/>circuit_breaker_open_total<br/>circuit_breaker_open_duration

    METRICS --> BACKOFF: Wait timeout<br/>60 seconds

    BACKOFF --> HALF_OPEN: Try 1-2 test<br/>requests to<br/>service

    HALF_OPEN --> TEST_SUCCESS: ✅ Test request<br/>succeeds

    HALF_OPEN --> TEST_FAILURE: ❌ Test request<br/>fails

    TEST_SUCCESS --> CLOSED: Service recovered<br/>reset failure counter

    TEST_FAILURE --> OPEN: Service still down<br/>reopen circuit<br/>extend backoff

    note right of CLOSED
        Normal operation
        - success_count > 90%
        - latency < SLA
    end note

    note right of OPEN
        Fast fail mode
        - reject all requests
        - serve from cache
        - reduce load on service
    end note

    note right of HALF_OPEN
        Testing if service
        has recovered
        - 1-2 test probes
        - if OK: close
        - if fail: reopen
    end note
```

## Cache Entry State Machine

```mermaid
stateDiagram-v2
    [*] --> MISS: Cache miss<br/>key not in Redis

    MISS --> QUERYING: Fetch from<br/>upstream service

    QUERYING --> TIMEOUT: ⏱️ Service timeout

    TIMEOUT --> STALE: Return last<br/>cached value<br/>if available

    QUERYING --> SUCCESS: ✅ Response<br/>received

    SUCCESS --> STORING: Store in Redis

    STORING --> CACHED: Cached with TTLs<br/>dashboard_summary 5min<br/>admin_flags 30s<br/>reconciliation_runs 10min

    CACHED --> SERVING: Serve from cache<br/>to client

    SERVING --> EXPIRING: TTL countdown

    EXPIRING --> EXPIRED: After TTL expires<br/>key removed from Redis

    EXPIRED --> MISS

    CACHED --> INVALIDATED: Cache invalidation<br/>triggered manually<br/>or due to upstream change

    INVALIDATED --> MISS

    STALE --> MISS: Concurrent request<br/>triggers refresh

    MISS --> [*]

    note right of CACHED
        Cache reduces:
        - downstream calls
        - response latency
        - load on services
    end note

    note right of SERVING
        Serve from cache for
        - dashboard: 5min stale acceptable
        - flags: 30s stale acceptable
        - reconciliation: 10min stale
    end note

    note right of INVALIDATED
        Manual invalidation via
        admin API endpoint
        or event from upstream
    end note
```
