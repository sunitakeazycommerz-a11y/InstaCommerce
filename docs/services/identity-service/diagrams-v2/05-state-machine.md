# Identity Service - State Machine Diagrams

## User Account State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING_VERIFICATION: User registers<br/>(email not verified)

    PENDING_VERIFICATION --> ACTIVE: Email verified<br/>(click verification link)
    PENDING_VERIFICATION --> DELETED: Account deletion<br/>(GDPR request)

    note right of PENDING_VERIFICATION
        Current implementation skips
        email verification and creates
        users directly as ACTIVE
    end note

    [*] --> ACTIVE: User registers<br/>(current flow)

    ACTIVE --> SUSPENDED: Admin suspends<br/>(policy violation)
    ACTIVE --> DELETED: User requests deletion<br/>(GDPR compliance)
    ACTIVE --> LOCKED: Too many failed logins<br/>(brute force protection)

    LOCKED --> ACTIVE: Lockout expires<br/>(after 30 minutes)
    LOCKED --> SUSPENDED: Admin intervention
    LOCKED --> DELETED: User deletion request

    SUSPENDED --> ACTIVE: Admin reactivates<br/>(appeal accepted)
    SUSPENDED --> DELETED: Permanent deletion<br/>(after 30 days)

    DELETED --> [*]: Account permanently removed<br/>(data purged after retention)

    note right of ACTIVE
        Normal operating state:
        - Can login
        - Can make purchases
        - Can update profile
    end note

    note right of SUSPENDED
        Restricted state:
        - Cannot login
        - All tokens revoked
        - Data preserved
    end note

    note right of DELETED
        Soft delete state:
        - Cannot login
        - All tokens revoked
        - PII anonymized
    end note

    note right of LOCKED
        Temporary protection:
        - Cannot login
        - Tokens still valid
        - Auto-unlocks
    end note
```

## Refresh Token Lifecycle

```mermaid
stateDiagram-v2
    [*] --> GENERATED: User logs in<br/>or token refreshed

    GENERATED --> STORED: Hash computed<br/>(SHA-256)

    STORED --> VALID: Within expiry<br/>(7 days default)

    VALID --> USED: Client sends<br/>refresh request

    USED --> ROTATED: New token issued<br/>old token revoked

    ROTATED --> REVOKED: Previous token<br/>marked revoked

    VALID --> EXPIRED: Time passes<br/>(expiresAt < now)

    VALID --> REVOKED: User logs out<br/>or explicit revoke

    REVOKED --> CLEANED_UP: Cleanup job runs<br/>(delete old tokens)

    EXPIRED --> CLEANED_UP: Cleanup job runs

    CLEANED_UP --> [*]: Token removed from DB

    note right of VALID
        Token can be used for:
        - Access token refresh
        - Session continuation
        Max 5 active tokens per user
    end note

    note right of ROTATED
        Token rotation prevents:
        - Replay attacks
        - Token theft continuation
        Old token immediately invalid
    end note

    note right of REVOKED
        Revoked tokens:
        - Cannot be used
        - Kept for audit trail
        - Deleted after 30 days
    end note
```

## JWT Access Token Lifecycle

```mermaid
stateDiagram-v2
    [*] --> ISSUED: TokenService generates<br/>on login/refresh

    ISSUED --> SIGNED: RS256 signature<br/>using private key

    SIGNED --> DISTRIBUTED: Sent to client<br/>in AuthResponse

    DISTRIBUTED --> VALID: Within expiry<br/>(15 minutes)

    VALID --> PRESENTED: Client sends<br/>to API

    PRESENTED --> VALIDATED: Consumer validates<br/>using JWKS public key

    VALIDATED --> AUTHORIZED: Claims extracted<br/>roles checked

    AUTHORIZED --> REQUEST_PROCESSED: SecurityContext set<br/>endpoint executed

    REQUEST_PROCESSED --> VALID: Token still valid<br/>can be reused

    VALID --> EXPIRED: Time passes<br/>(exp claim < now)

    EXPIRED --> NEEDS_REFRESH: Client must call<br/>/auth/refresh

    NEEDS_REFRESH --> [*]: New access token<br/>issued

    note right of VALID
        Stateless validation:
        - No DB lookup
        - JWKS cached (5min)
        - Signature verified
    end note

    note right of EXPIRED
        After expiration:
        - 401 Unauthorized
        - Use refresh token
        - Or re-login
    end note
```

## Login Attempt State Machine

```mermaid
stateDiagram-v2
    [*] --> RATE_CHECK: Login request<br/>received

    RATE_CHECK --> RATE_LIMITED: Exceeds 10/min<br/>per IP
    RATE_CHECK --> USER_LOOKUP: Within rate limit

    RATE_LIMITED --> [*]: Return 429<br/>Too Many Requests

    USER_LOOKUP --> USER_NOT_FOUND: Email not<br/>in database
    USER_LOOKUP --> LOCK_CHECK: User found

    USER_NOT_FOUND --> FAILED: Return 401<br/>(don't reveal user exists)

    LOCK_CHECK --> ACCOUNT_LOCKED: lockedUntil > now
    LOCK_CHECK --> PASSWORD_CHECK: Account not locked

    ACCOUNT_LOCKED --> FAILED: Return 403<br/>UserInactiveException

    PASSWORD_CHECK --> PASSWORD_WRONG: bcrypt mismatch
    PASSWORD_CHECK --> STATUS_CHECK: Password correct

    PASSWORD_WRONG --> INCREMENT_FAILURES: failedAttempts++

    INCREMENT_FAILURES --> TRIGGER_LOCK: attempts >= 10
    INCREMENT_FAILURES --> FAILED: attempts < 10

    TRIGGER_LOCK --> ACCOUNT_LOCKED: Set lockedUntil<br/>(now + 30min)

    STATUS_CHECK --> USER_INACTIVE: status != ACTIVE
    STATUS_CHECK --> SUCCESS: status == ACTIVE

    USER_INACTIVE --> FAILED: Return 403<br/>(suspended/deleted)

    SUCCESS --> RESET_FAILURES: failedAttempts = 0<br/>lockedUntil = null

    RESET_FAILURES --> GENERATE_TOKENS: Create access<br/>and refresh tokens

    GENERATE_TOKENS --> STORE_REFRESH: Hash and store<br/>refresh token

    STORE_REFRESH --> AUDIT_LOG: Log<br/>USER_LOGIN_SUCCESS

    AUDIT_LOG --> [*]: Return 200<br/>with tokens

    FAILED --> AUDIT_LOG_FAIL: Log failure<br/>with reason
    AUDIT_LOG_FAIL --> [*]

    note right of TRIGGER_LOCK
        Brute force protection:
        - 10 failed attempts
        - 30 minute lockout
        - Auto-unlock
    end note
```

## User Session State Machine

```mermaid
stateDiagram-v2
    [*] --> NO_SESSION: User not<br/>authenticated

    NO_SESSION --> AUTHENTICATING: User submits<br/>credentials

    AUTHENTICATING --> AUTHENTICATION_FAILED: Invalid credentials<br/>or locked
    AUTHENTICATING --> SESSION_CREATED: Login successful

    AUTHENTICATION_FAILED --> NO_SESSION: Return error<br/>to client

    SESSION_CREATED --> ACTIVE_SESSION: Tokens stored<br/>client-side

    ACTIVE_SESSION --> MAKING_REQUEST: Client sends<br/>API request

    MAKING_REQUEST --> ACCESS_VALID: JWT not expired<br/>(< 15 min)
    MAKING_REQUEST --> ACCESS_EXPIRED: JWT expired<br/>(> 15 min)

    ACCESS_VALID --> REQUEST_SERVED: Request processed

    REQUEST_SERVED --> ACTIVE_SESSION: Return to<br/>active state

    ACCESS_EXPIRED --> REFRESHING: Client calls<br/>/auth/refresh

    REFRESHING --> REFRESH_VALID: Refresh token<br/>valid & not revoked
    REFRESHING --> REFRESH_INVALID: Refresh token<br/>expired/revoked

    REFRESH_VALID --> TOKENS_ROTATED: New tokens<br/>issued

    TOKENS_ROTATED --> ACTIVE_SESSION: Continue session

    REFRESH_INVALID --> NO_SESSION: Must re-login

    ACTIVE_SESSION --> LOGGING_OUT: User clicks<br/>logout

    LOGGING_OUT --> TOKENS_REVOKED: All refresh<br/>tokens revoked

    TOKENS_REVOKED --> NO_SESSION: Session ended

    note right of ACTIVE_SESSION
        Active session:
        - Access token valid
        - Refresh token stored
        - User can make requests
    end note

    note right of TOKENS_ROTATED
        Token rotation:
        - Old refresh revoked
        - New refresh issued
        - New access issued
    end note
```

## Rate Limiter State Machine

```mermaid
stateDiagram-v2
    [*] --> IDLE: No requests<br/>for this key

    IDLE --> FIRST_REQUEST: Request arrives<br/>for rate:login:{ip}

    FIRST_REQUEST --> COUNTER_CREATED: Redis INCR<br/>creates counter = 1

    COUNTER_CREATED --> TTL_SET: Redis EXPIRE<br/>(60 seconds)

    TTL_SET --> UNDER_LIMIT: counter < limit

    UNDER_LIMIT --> ALLOWED: Request proceeds

    ALLOWED --> INCREMENT: counter++

    INCREMENT --> UNDER_LIMIT: counter < limit
    INCREMENT --> AT_LIMIT: counter == limit

    AT_LIMIT --> NEXT_REQUEST: New request<br/>arrives

    NEXT_REQUEST --> EXCEEDED: counter > limit

    EXCEEDED --> REJECTED: Return 429<br/>Too Many Requests

    UNDER_LIMIT --> TTL_EXPIRES: 60 seconds pass

    TTL_EXPIRES --> IDLE: Counter deleted<br/>from Redis

    AT_LIMIT --> TTL_EXPIRES

    note right of EXCEEDED
        Rate limits:
        - Login: 10/min per IP
        - Register: 5/min per IP
        - Refresh: 30/min per user
    end note

    note right of TTL_EXPIRES
        Sliding window:
        - Counter resets
        - User can retry
        - No permanent block
    end note
```

## JWKS Cache State Machine

```mermaid
stateDiagram-v2
    [*] --> EMPTY: Service starts<br/>no cached keys

    EMPTY --> FETCHING: First JWT<br/>validation request

    FETCHING --> FETCH_SUCCESS: Identity service<br/>responds 200
    FETCHING --> FETCH_FAILED: Network error<br/>or timeout

    FETCH_SUCCESS --> CACHED: Store JWKS<br/>(5 min TTL)

    CACHED --> SERVING: Lookup kid<br/>in cache

    SERVING --> HIT: kid found

    HIT --> VALIDATE: Use public key<br/>for RS256

    SERVING --> MISS: kid not found

    MISS --> REFRESH: Fetch fresh<br/>JWKS

    REFRESH --> CACHED: Update cache

    CACHED --> STALE: TTL expires<br/>(5 min)

    STALE --> REFRESH: Next request<br/>triggers refresh

    FETCH_FAILED --> FALLBACK: Use stale cache<br/>if available

    FALLBACK --> SERVING: Serve from<br/>old cache

    FETCH_FAILED --> ERROR: No cache<br/>available

    ERROR --> [*]: Return 503<br/>Service Unavailable

    note right of CACHED
        Cache strategy:
        - TTL: 5 minutes
        - Background refresh
        - Stale-while-revalidate
    end note

    note right of FALLBACK
        Resilience:
        - Identity service down
        - Use extended TTL (24h)
        - Log warning
    end note
```

## Audit Log Event Flow

```mermaid
stateDiagram-v2
    [*] --> ACTION_TRIGGERED: Auth action<br/>occurs

    ACTION_TRIGGERED --> BUILD_AUDIT: Create AuditLog<br/>entity

    BUILD_AUDIT --> POPULATE_FIELDS: Set fields:<br/>userId, action, entityType,<br/>entityId, details, ipAddress,<br/>userAgent, traceId

    POPULATE_FIELDS --> PERSIST: Save to<br/>PostgreSQL

    PERSIST --> COMMITTED: Transaction<br/>commits

    COMMITTED --> QUERYABLE: Available for<br/>audit queries

    QUERYABLE --> RETENTION: Kept for<br/>compliance

    RETENTION --> CLEANUP: After 90 days<br/>(configurable)

    CLEANUP --> ARCHIVED: Move to cold<br/>storage (optional)

    CLEANUP --> DELETED: Remove from<br/>active DB

    ARCHIVED --> [*]
    DELETED --> [*]

    note right of ACTION_TRIGGERED
        Audit events:
        - USER_REGISTERED
        - USER_LOGIN_SUCCESS
        - USER_LOGIN_FAILED
        - USER_LOGIN_LOCKED
        - USER_LOGIN_BLOCKED
        - TOKEN_REFRESH
        - TOKEN_REVOKE
        - USER_LOGOUT
        - PASSWORD_CHANGED
        - USER_STATUS_CHANGED
    end note
```
