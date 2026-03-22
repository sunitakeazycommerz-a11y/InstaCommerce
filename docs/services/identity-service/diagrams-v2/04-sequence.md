# Identity Service - Sequence Diagrams

## Complete Login Sequence

```mermaid
sequenceDiagram
    participant Client as Client<br/>(Mobile/Web)
    participant ALB as AWS ALB<br/>(TLS)
    participant RateLimit as RateLimitService<br/>(Redis)
    participant Auth as AuthController
    participant AuthSvc as AuthService
    participant UserRepo as UserRepository<br/>(PostgreSQL)
    participant TokenSvc as TokenService
    participant RefreshRepo as RefreshTokenRepository
    participant AuditSvc as AuditService
    participant Metrics as AuthMetrics

    Client->>ALB: 1. POST /auth/login<br/>{email, password, deviceInfo}
    ALB->>Auth: 2. Forward (decrypted)

    Auth->>RateLimit: 3. checkLogin(ipAddress)
    RateLimit->>RateLimit: 4. INCR rate:login:{ip}
    alt Rate limit exceeded
        RateLimit-->>Client: 429 Too Many Requests
    end

    Auth->>AuthSvc: 5. login(request, ip, userAgent, traceId)
    AuthSvc->>Metrics: 6. Start login timer

    AuthSvc->>AuthSvc: 7. Normalize email<br/>(lowercase, trim)
    AuthSvc->>UserRepo: 8. findByEmailIgnoreCase(email)
    UserRepo-->>AuthSvc: 9. Optional<User>

    alt User not found
        AuthSvc->>AuditSvc: Log: USER_LOGIN_FAILED
        AuthSvc-->>Client: 401 Unauthorized
    end

    AuthSvc->>AuthSvc: 10. Check lockedUntil
    alt Account locked
        AuthSvc->>AuditSvc: Log: USER_LOGIN_LOCKED
        AuthSvc-->>Client: 403 Forbidden
    end

    AuthSvc->>AuthSvc: 11. passwordEncoder.matches()
    alt Password incorrect
        AuthSvc->>UserRepo: 12. Increment failedAttempts
        alt Attempts >= 10
            AuthSvc->>UserRepo: Lock account (30min)
        end
        AuthSvc->>AuditSvc: Log: USER_LOGIN_FAILED
        AuthSvc-->>Client: 401 Unauthorized
    end

    AuthSvc->>AuthSvc: 13. Check status == ACTIVE
    alt User suspended/deleted
        AuthSvc->>AuditSvc: Log: USER_LOGIN_BLOCKED
        AuthSvc-->>Client: 403 Forbidden
    end

    AuthSvc->>UserRepo: 14. Reset failedAttempts, clear lock
    AuthSvc->>TokenSvc: 15. generateAccessToken(user)
    TokenSvc->>TokenSvc: 16. Build claims, sign RS256
    TokenSvc-->>AuthSvc: 17. JWT access token

    AuthSvc->>TokenSvc: 18. generateRefreshToken()
    TokenSvc-->>AuthSvc: 19. Opaque refresh token

    AuthSvc->>TokenSvc: 20. hashRefreshToken(token)
    TokenSvc-->>AuthSvc: 21. SHA-256 hash

    AuthSvc->>RefreshRepo: 22. save(RefreshToken)
    RefreshRepo-->>AuthSvc: 23. Saved

    AuthSvc->>RefreshRepo: 24. Enforce max tokens
    AuthSvc->>AuditSvc: 25. Log: USER_LOGIN_SUCCESS
    AuthSvc->>Metrics: 26. Record login latency
    AuthSvc->>Metrics: 27. incrementLoginSuccess()

    AuthSvc-->>Auth: 28. AuthResponse
    Auth-->>Client: 29. 200 OK<br/>{accessToken, refreshToken,<br/>expiresIn: 900, tokenType: Bearer}
```

## Token Refresh Sequence

```mermaid
sequenceDiagram
    participant Client as Client
    participant Auth as AuthController
    participant AuthSvc as AuthService
    participant TokenSvc as TokenService
    participant RefreshRepo as RefreshTokenRepository
    participant AuditSvc as AuditService

    Client->>Auth: 1. POST /auth/refresh<br/>{refreshToken}

    Auth->>AuthSvc: 2. refresh(request, ip, userAgent, traceId)
    AuthSvc->>TokenSvc: 3. hashRefreshToken(token)
    TokenSvc-->>AuthSvc: 4. SHA-256 hash

    AuthSvc->>RefreshRepo: 5. findByTokenHash(hash)
    RefreshRepo-->>AuthSvc: 6. Optional<RefreshToken>

    alt Token not found
        AuthSvc-->>Client: 401 TokenInvalidException
    end

    AuthSvc->>AuthSvc: 7. Check revoked flag
    alt Token revoked
        AuthSvc-->>Client: 401 TokenRevokedException
    end

    AuthSvc->>AuthSvc: 8. Check expiresAt > now
    alt Token expired
        AuthSvc-->>Client: 401 TokenExpiredException
    end

    AuthSvc->>AuthSvc: 9. Check user.status == ACTIVE
    alt User inactive
        AuthSvc-->>Client: 403 UserInactiveException
    end

    Note over AuthSvc: Token Rotation (security best practice)

    AuthSvc->>RefreshRepo: 10. Mark old token revoked
    AuthSvc->>TokenSvc: 11. generateRefreshToken()
    TokenSvc-->>AuthSvc: 12. New refresh token

    AuthSvc->>TokenSvc: 13. hashRefreshToken(newToken)
    AuthSvc->>RefreshRepo: 14. save(new RefreshToken)<br/>(copy deviceInfo)

    AuthSvc->>TokenSvc: 15. generateAccessToken(user)
    TokenSvc-->>AuthSvc: 16. New JWT

    AuthSvc->>RefreshRepo: 17. Enforce max tokens
    AuthSvc->>AuditSvc: 18. Log: TOKEN_REFRESH

    AuthSvc-->>Auth: 19. AuthResponse
    Auth-->>Client: 20. 200 OK<br/>{accessToken, refreshToken}
```

## JWKS Validation Sequence (Consumer Service)

```mermaid
sequenceDiagram
    participant Client as Client
    participant Consumer as Consumer Service<br/>(Admin Gateway)
    participant JwksCache as JWKS Cache<br/>(5min TTL)
    participant Identity as Identity Service
    participant JwksCtrl as JwksController
    participant KeyLoader as JwtKeyLoader

    Client->>Consumer: 1. Request with JWT<br/>Authorization: Bearer <token>

    Consumer->>Consumer: 2. Parse JWT header<br/>extract kid

    Consumer->>JwksCache: 3. Lookup kid in cache
    alt Cache HIT (within 5min)
        JwksCache-->>Consumer: 4a. Return cached public key
    else Cache MISS
        Consumer->>Identity: 4b. GET /.well-known/jwks.json
        Identity->>JwksCtrl: 5. jwks()
        JwksCtrl->>KeyLoader: 6. getPublicKey()
        KeyLoader-->>JwksCtrl: 7. RSAPublicKey
        JwksCtrl->>JwksCtrl: 8. Build JWK<br/>{kty, use, alg, kid, n, e}
        JwksCtrl-->>Identity: 9. {keys: [jwk]}
        Identity-->>Consumer: 10. JWKS response
        Consumer->>JwksCache: 11. Cache for 5min
        JwksCache-->>Consumer: 12. Public key
    end

    Consumer->>Consumer: 13. Verify RS256 signature<br/>using public key

    alt Signature invalid
        Consumer-->>Client: 401 Unauthorized
    end

    Consumer->>Consumer: 14. Check exp > now
    alt Token expired
        Consumer-->>Client: 401 Unauthorized
    end

    Consumer->>Consumer: 15. Check aud claim
    alt Wrong audience
        Consumer-->>Client: 403 Forbidden
    end

    Consumer->>Consumer: 16. Extract claims<br/>(sub, roles, email)
    Consumer->>Consumer: 17. Set SecurityContext

    Consumer-->>Client: 18. Process request
```

## User Registration Sequence

```mermaid
sequenceDiagram
    participant Client as Client
    participant Auth as AuthController
    participant RateLimit as RateLimitService
    participant AuthSvc as AuthService
    participant UserRepo as UserRepository
    participant TokenSvc as TokenService
    participant RefreshRepo as RefreshTokenRepository
    participant AuditSvc as AuditService
    participant Metrics as AuthMetrics

    Client->>Auth: 1. POST /auth/register<br/>{email, password}

    Auth->>RateLimit: 2. checkRegister(ipAddress)
    alt Rate limit exceeded
        RateLimit-->>Client: 429 Too Many Requests
    end

    Auth->>AuthSvc: 3. register(request, ip, userAgent, traceId)

    AuthSvc->>AuthSvc: 4. Normalize email
    AuthSvc->>UserRepo: 5. findByEmailIgnoreCase(email)
    UserRepo-->>AuthSvc: 6. Optional<User>

    alt Email exists
        AuthSvc-->>Client: 409 UserAlreadyExistsException
    end

    AuthSvc->>AuthSvc: 7. passwordEncoder.encode(password)
    Note over AuthSvc: bcrypt with cost=10

    AuthSvc->>AuthSvc: 8. Create User entity<br/>status=ACTIVE, roles=[CUSTOMER]

    AuthSvc->>UserRepo: 9. save(user)
    UserRepo-->>AuthSvc: 10. Saved user with ID

    AuthSvc->>Metrics: 11. incrementRegister()
    AuthSvc->>AuditSvc: 12. Log: USER_REGISTERED

    AuthSvc->>TokenSvc: 13. generateAccessToken(user)
    TokenSvc-->>AuthSvc: 14. JWT

    AuthSvc->>TokenSvc: 15. generateRefreshToken()
    AuthSvc->>TokenSvc: 16. hashRefreshToken(token)

    AuthSvc->>RefreshRepo: 17. save(RefreshToken)

    AuthSvc-->>Auth: 18. AuthResponse
    Auth-->>Client: 19. 201 Created<br/>{accessToken, refreshToken}
```

## Logout Sequence (Revoke All Sessions)

```mermaid
sequenceDiagram
    participant Client as Client
    participant Auth as AuthController
    participant JwtFilter as JwtAuthenticationFilter
    participant AuthSvc as AuthService
    participant UserSvc as UserService
    participant RefreshRepo as RefreshTokenRepository
    participant AuditSvc as AuditService

    Client->>Auth: 1. POST /auth/logout<br/>Authorization: Bearer <JWT>

    Auth->>JwtFilter: 2. Validate JWT
    JwtFilter->>JwtFilter: 3. Verify signature, expiry
    JwtFilter-->>Auth: 4. Set SecurityContext

    Auth->>AuthSvc: 5. logout(ip, userAgent, traceId)

    AuthSvc->>UserSvc: 6. getCurrentUserId()
    UserSvc->>UserSvc: 7. Extract from SecurityContext
    UserSvc-->>AuthSvc: 8. UUID userId

    AuthSvc->>RefreshRepo: 9. revokeAllActiveByUserId(userId)
    Note over RefreshRepo: UPDATE refresh_tokens<br/>SET revoked = true<br/>WHERE user_id = ? AND revoked = false
    RefreshRepo-->>AuthSvc: 10. Count of revoked tokens

    AuthSvc->>AuditSvc: 11. Log: USER_LOGOUT<br/>{revokedTokens: N}

    AuthSvc-->>Auth: 12. void
    Auth-->>Client: 13. 204 No Content
```

## Account Lockout Sequence

```mermaid
sequenceDiagram
    participant Attacker as Attacker
    participant Auth as AuthController
    participant AuthSvc as AuthService
    participant UserRepo as UserRepository
    participant AuditSvc as AuditService

    loop Brute Force Attempts (10x)
        Attacker->>Auth: POST /auth/login<br/>{email, wrongPassword}
        Auth->>AuthSvc: login()
        AuthSvc->>UserRepo: findByEmailIgnoreCase()
        UserRepo-->>AuthSvc: User
        AuthSvc->>AuthSvc: passwordEncoder.matches() = false
        AuthSvc->>UserRepo: user.failedAttempts++
        AuthSvc->>AuditSvc: Log: USER_LOGIN_FAILED
        AuthSvc-->>Attacker: 401 Unauthorized
    end

    Note over AuthSvc: failedAttempts >= 10

    Attacker->>Auth: POST /auth/login (11th attempt)
    Auth->>AuthSvc: login()
    AuthSvc->>UserRepo: findByEmailIgnoreCase()
    UserRepo-->>AuthSvc: User (failedAttempts = 10)
    AuthSvc->>UserRepo: user.lockedUntil = now + 30min
    AuthSvc->>AuditSvc: Log: USER_LOGIN_LOCKED
    AuthSvc-->>Attacker: 403 Forbidden

    Note over Attacker,AuthSvc: Account locked for 30 minutes

    Attacker->>Auth: POST /auth/login (during lockout)
    Auth->>AuthSvc: login()
    AuthSvc->>UserRepo: findByEmailIgnoreCase()
    UserRepo-->>AuthSvc: User
    AuthSvc->>AuthSvc: Check lockedUntil > now
    AuthSvc->>AuditSvc: Log: USER_LOGIN_LOCKED
    AuthSvc-->>Attacker: 403 Forbidden

    Note over Attacker,AuthSvc: After 30 minutes

    Attacker->>Auth: POST /auth/login<br/>{email, correctPassword}
    Auth->>AuthSvc: login()
    AuthSvc->>UserRepo: findByEmailIgnoreCase()
    UserRepo-->>AuthSvc: User
    AuthSvc->>AuthSvc: lockedUntil < now (expired)
    AuthSvc->>AuthSvc: passwordEncoder.matches() = true
    AuthSvc->>UserRepo: Reset failedAttempts, clear lockedUntil
    AuthSvc-->>Attacker: 200 OK with tokens
```

## Admin User Suspension Sequence

```mermaid
sequenceDiagram
    participant Admin as Admin User
    participant AdminCtrl as AdminController
    participant JwtFilter as JwtAuthenticationFilter
    participant UserSvc as UserService
    participant UserRepo as UserRepository
    participant RefreshRepo as RefreshTokenRepository
    participant AuditSvc as AuditService

    Admin->>AdminCtrl: 1. PUT /admin/users/{id}/status<br/>{status: SUSPENDED}<br/>Authorization: Bearer <JWT>

    AdminCtrl->>JwtFilter: 2. Validate JWT
    JwtFilter->>JwtFilter: 3. Extract roles
    JwtFilter->>JwtFilter: 4. Check ADMIN role
    alt No ADMIN role
        JwtFilter-->>Admin: 403 Forbidden
    end

    AdminCtrl->>UserSvc: 5. updateStatus(userId, SUSPENDED)

    UserSvc->>UserRepo: 6. findById(userId)
    UserRepo-->>UserSvc: 7. Optional<User>

    alt User not found
        UserSvc-->>Admin: 404 Not Found
    end

    UserSvc->>UserSvc: 8. user.setStatus(SUSPENDED)
    UserSvc->>UserRepo: 9. save(user)

    UserSvc->>RefreshRepo: 10. revokeAllActiveByUserId(userId)
    Note over RefreshRepo: Force logout suspended user

    UserSvc->>AuditSvc: 11. Log: USER_STATUS_CHANGED<br/>{adminId, targetUserId, newStatus}

    UserSvc-->>AdminCtrl: 12. Updated User
    AdminCtrl-->>Admin: 13. 200 OK
```
