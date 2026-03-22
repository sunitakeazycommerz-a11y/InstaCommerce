# Identity Service - Low-Level Design

## Component Architecture

```mermaid
graph TB
    HTTPRequest["🌐 HTTP Request"]

    subgraph Controllers["REST Controllers"]
        AuthController["AuthController<br/>POST /auth/*"]
        UserController["UserController<br/>GET/PUT /users/*"]
        AdminController["AdminController<br/>GET/PUT /admin/*"]
        JwksController["JwksController<br/>GET /.well-known/jwks.json"]
    end

    subgraph Security["Security Layer"]
        RateLimitFilter["RateLimitService<br/>(per IP/user)"]
        JwtAuthFilter["JwtAuthenticationFilter<br/>(validate incoming JWT)"]
        SecurityContext["SecurityContext<br/>(principal + authorities)"]
    end

    subgraph Services["Business Services"]
        AuthService["AuthService<br/>(login, register, refresh)"]
        TokenService["TokenService<br/>(JWT generation, refresh tokens)"]
        UserService["UserService<br/>(profile management)"]
        AuditService["AuditService<br/>(action logging)"]
    end

    subgraph Domain["Domain Layer"]
        UserEntity["User Entity<br/>(id, email, roles, status)"]
        RefreshTokenEntity["RefreshToken Entity<br/>(tokenHash, expiresAt)"]
        AuditLogEntity["AuditLog Entity<br/>(action, details)"]
    end

    subgraph Infrastructure["Infrastructure"]
        PostgreSQL["🐘 PostgreSQL"]
        Redis["🔴 Redis"]
        JwtKeyLoader["JwtKeyLoader<br/>(RSA key pair)"]
    end

    HTTPRequest --> RateLimitFilter
    RateLimitFilter --> JwtAuthFilter
    JwtAuthFilter --> SecurityContext
    SecurityContext --> Controllers

    AuthController --> AuthService
    UserController --> UserService
    AdminController --> UserService
    JwksController --> JwtKeyLoader

    AuthService --> TokenService
    AuthService --> AuditService
    AuthService --> UserEntity
    AuthService --> RefreshTokenEntity

    TokenService --> JwtKeyLoader
    AuditService --> AuditLogEntity

    UserEntity --> PostgreSQL
    RefreshTokenEntity --> PostgreSQL
    AuditLogEntity --> PostgreSQL
    RateLimitFilter --> Redis

    style AuthController fill:#4A90E2,color:#fff
    style AuthService fill:#7ED321,color:#000
    style TokenService fill:#F5A623,color:#000
    style JwtKeyLoader fill:#9013FE,color:#fff
```

## TokenService Implementation

```mermaid
graph TD
    A["TokenService"]
    
    subgraph AccessToken["Access Token Generation"]
        B["generateAccessToken(User)"]
        C["Build JWT claims:<br/>- sub: user_id<br/>- email: user_email<br/>- roles: [CUSTOMER, ADMIN]<br/>- aud: instacommerce<br/>- iss: identity-service<br/>- iat: now<br/>- exp: now + 15min"]
        D["Sign with RS256<br/>using private key"]
        E["Add kid header<br/>(key identifier)"]
        F["Return signed JWT"]
    end
    
    subgraph RefreshToken["Refresh Token Management"]
        G["generateRefreshToken()"]
        H["Generate 32-byte<br/>secure random"]
        I["Base64 encode"]
        J["hashRefreshToken(token)"]
        K["SHA-256 hash"]
        L["Store hash in DB<br/>(never store raw token)"]
    end
    
    subgraph Validation["Token Validation"]
        M["validateAccessToken(token)"]
        N["Parse JWT header<br/>extract kid"]
        O["Lookup public key<br/>by kid"]
        P["Verify RS256 signature"]
        Q["Check expiration<br/>(exp > now)"]
        R["Extract claims"]
    end

    A --> B
    B --> C --> D --> E --> F
    
    A --> G
    G --> H --> I
    A --> J
    J --> K --> L
    
    A --> M
    M --> N --> O --> P --> Q --> R

    style B fill:#7ED321,color:#000
    style G fill:#F5A623,color:#000
    style M fill:#4A90E2,color:#fff
```

## AuthService - Login Flow

```mermaid
graph TD
    A["login(LoginRequest)"]
    B["Rate limit check<br/>(by IP address)"]
    C{{"Rate limit<br/>exceeded?"}}
    D["❌ Return 429<br/>Too Many Requests"]
    E["Normalize email<br/>(lowercase, trim)"]
    F["Find user by email"]
    G{{"User<br/>found?"}}
    H["Increment failed attempts<br/>(for brute force tracking)"]
    I["❌ Throw InvalidCredentialsException"]
    J{{"Account<br/>locked?"}}
    K["❌ Throw UserInactiveException<br/>(locked until timestamp)"]
    L["Verify password<br/>(bcrypt compare)"]
    M{{"Password<br/>matches?"}}
    N["Increment failed attempts"]
    O{{"Attempts >= 10?"}}
    P["Lock account<br/>(30 min lockout)"]
    Q{{"User status<br/>ACTIVE?"}}
    R["❌ Throw UserInactiveException<br/>(suspended/deleted)"]
    S["Reset failed attempts<br/>Clear lock"]
    T["Generate access token<br/>(JWT, 15min)"]
    U["Generate refresh token<br/>(opaque, 7d)"]
    V["Store refresh token hash<br/>(with device info)"]
    W["Enforce max tokens<br/>(remove oldest)"]
    X["Log audit: USER_LOGIN_SUCCESS"]
    Y["✅ Return AuthResponse<br/>(accessToken, refreshToken)"]

    A --> B --> C
    C -->|Yes| D
    C -->|No| E
    E --> F --> G
    G -->|No| H --> I
    G -->|Yes| J
    J -->|Yes| K
    J -->|No| L --> M
    M -->|No| N --> O
    O -->|Yes| P --> I
    O -->|No| I
    M -->|Yes| Q
    Q -->|No| R
    Q -->|Yes| S
    S --> T --> U --> V --> W --> X --> Y

    style Y fill:#7ED321,color:#000
    style D fill:#FF6B6B,color:#fff
    style I fill:#FF6B6B,color:#fff
    style K fill:#FF6B6B,color:#fff
    style R fill:#FF6B6B,color:#fff
```

## JwtKeyLoader - RSA Key Management

```mermaid
graph TD
    A["JwtKeyLoader<br/>(Bean initialization)"]
    B["Load private key<br/>from PEM file or env"]
    C["Extract public key<br/>from private key"]
    D["Generate kid<br/>(key identifier)"]
    E["Store in memory<br/>(singleton)"]

    subgraph JWKS["JWKS Endpoint Response"]
        F["GET /.well-known/jwks.json"]
        G["Build JWK object:<br/>- kty: RSA<br/>- use: sig<br/>- alg: RS256<br/>- kid: <key-id><br/>- n: <modulus base64url><br/>- e: <exponent base64url>"]
        H["Return {keys: [jwk]}"]
    end

    subgraph Signing["Token Signing"]
        I["Sign JWT with private key"]
        J["Include kid in header"]
    end

    A --> B --> C --> D --> E
    E --> F --> G --> H
    E --> I --> J

    style A fill:#9013FE,color:#fff
    style G fill:#7ED321,color:#000
```

## SecurityConfig - Filter Chain

```mermaid
graph TB
    Request["HTTP Request"]

    CorrelationFilter["CorrelationIdFilter<br/>(add trace ID)"]
    RateLimitCheck["RateLimitService<br/>(check IP/user limits)"]
    JwtFilter["JwtAuthenticationFilter<br/>(validate Bearer token)"]
    SecurityContext["Set SecurityContext<br/>(principal, authorities)"]

    subgraph PublicEndpoints["Public Endpoints (no auth)"]
        Register["/auth/register"]
        Login["/auth/login"]
        Refresh["/auth/refresh"]
        JWKS["/.well-known/jwks.json"]
        Health["/actuator/health"]
    end

    subgraph ProtectedEndpoints["Protected Endpoints (JWT required)"]
        Revoke["/auth/revoke"]
        Logout["/auth/logout"]
        UserProfile["/users/me"]
        ChangePassword["/users/me/password"]
        AdminUsers["/admin/users/*"]
    end

    Request --> CorrelationFilter
    CorrelationFilter --> RateLimitCheck
    RateLimitCheck --> JwtFilter

    JwtFilter -->|No token needed| PublicEndpoints
    JwtFilter -->|Token validated| SecurityContext
    SecurityContext --> ProtectedEndpoints

    style JwtFilter fill:#4A90E2,color:#fff
    style SecurityContext fill:#7ED321,color:#000
```

## RateLimitService Implementation

```mermaid
graph TD
    A["RateLimitService"]

    subgraph LoginLimit["Login Rate Limit"]
        B["checkLogin(ipAddress)"]
        C["Key: rate:login:{ip}"]
        D["Limit: 10 requests/min"]
        E["Redis INCR + EXPIRE"]
    end

    subgraph RegisterLimit["Register Rate Limit"]
        F["checkRegister(ipAddress)"]
        G["Key: rate:register:{ip}"]
        H["Limit: 5 requests/min"]
        I["Redis INCR + EXPIRE"]
    end

    subgraph RefreshLimit["Refresh Rate Limit"]
        J["checkRefresh(userId)"]
        K["Key: rate:refresh:{userId}"]
        L["Limit: 30 requests/min"]
        M["Redis INCR + EXPIRE"]
    end

    Check{{"Count ><br/>limit?"}}
    Allow["✅ Allow request"]
    Reject["❌ Throw RateLimitException<br/>Return 429"]

    A --> B --> C --> D --> E --> Check
    A --> F --> G --> H --> I --> Check
    A --> J --> K --> L --> M --> Check

    Check -->|No| Allow
    Check -->|Yes| Reject

    style Allow fill:#7ED321,color:#000
    style Reject fill:#FF6B6B,color:#fff
```

## AuditService - Action Logging

```mermaid
graph LR
    Action["Auth Action<br/>(login, register, etc.)"]
    AuditService["AuditService"]
    AuditLog["AuditLog Entity"]
    PostgreSQL["PostgreSQL<br/>audit_log table"]

    subgraph LoggedFields["Logged Fields"]
        UserId["user_id"]
        ActionType["action<br/>(USER_LOGIN_SUCCESS, etc.)"]
        EntityType["entity_type<br/>(User, RefreshToken)"]
        EntityId["entity_id"]
        Details["details (JSON)<br/>(reason, status, etc.)"]
        IpAddress["ip_address"]
        UserAgent["user_agent"]
        TraceId["trace_id"]
        Timestamp["created_at"]
    end

    Action --> AuditService
    AuditService --> AuditLog
    AuditLog --> LoggedFields
    AuditLog --> PostgreSQL

    style AuditService fill:#9013FE,color:#fff
    style LoggedFields fill:#E8F5E9,color:#000
```

## Metrics Collection

```mermaid
graph LR
    AuthMetrics["AuthMetrics<br/>(Micrometer)"]

    subgraph Counters["Counters"]
        RegisterCounter["identity_register_total"]
        LoginSuccessCounter["identity_login_success_total"]
        LoginFailureCounter["identity_login_failure_total"]
        RefreshCounter["identity_refresh_total"]
        RevokeCounter["identity_revoke_total"]
    end

    subgraph Timers["Timers"]
        LoginTimer["identity_login_duration_ms<br/>(histogram)"]
    end

    subgraph Gauges["Gauges"]
        ActiveUsers["identity_active_users<br/>(from DB query)"]
        ActiveTokens["identity_active_refresh_tokens"]
    end

    AuthMetrics --> Counters
    AuthMetrics --> Timers
    AuthMetrics --> Gauges

    Prometheus["📊 Prometheus<br/>/actuator/prometheus"]
    Counters --> Prometheus
    Timers --> Prometheus
    Gauges --> Prometheus

    style AuthMetrics fill:#9013FE,color:#fff
    style Prometheus fill:#E6522C,color:#fff
```

## Error Handling

```mermaid
graph TD
    Exception["Exception thrown"]

    subgraph AuthExceptions["Auth Exceptions"]
        InvalidCreds["InvalidCredentialsException<br/>→ 401 Unauthorized"]
        UserInactive["UserInactiveException<br/>→ 403 Forbidden"]
        UserExists["UserAlreadyExistsException<br/>→ 409 Conflict"]
    end

    subgraph TokenExceptions["Token Exceptions"]
        TokenInvalid["TokenInvalidException<br/>→ 401 Unauthorized"]
        TokenExpired["TokenExpiredException<br/>→ 401 Unauthorized"]
        TokenRevoked["TokenRevokedException<br/>→ 401 Unauthorized"]
    end

    subgraph UserExceptions["User Exceptions"]
        UserNotFound["UserNotFoundException<br/>→ 404 Not Found"]
    end

    GlobalHandler["GlobalExceptionHandler<br/>(@ControllerAdvice)"]
    ErrorResponse["ErrorResponse<br/>{code, message, traceId}"]

    Exception --> GlobalHandler
    GlobalHandler --> AuthExceptions
    GlobalHandler --> TokenExceptions
    GlobalHandler --> UserExceptions
    GlobalHandler --> ErrorResponse

    style GlobalHandler fill:#FF6B6B,color:#fff
    style ErrorResponse fill:#F5A623,color:#000
```
