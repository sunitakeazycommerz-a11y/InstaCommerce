# Identity Service - Request Flowcharts

## User Registration Flow

```mermaid
flowchart TD
    A["🌐 POST /auth/register<br/>{email, password}"]
    B["Rate limit check<br/>(5 req/min per IP)"]
    C{{"Rate limit<br/>exceeded?"}}
    D["❌ Return 429<br/>Too Many Requests"]
    E["Normalize email<br/>(lowercase, trim)"]
    F["Check email uniqueness"]
    G{{"Email<br/>exists?"}}
    H["❌ Return 409<br/>UserAlreadyExistsException"]
    I["Hash password<br/>(bcrypt, cost=10)"]
    J["Create User entity:<br/>- status: ACTIVE<br/>- roles: [CUSTOMER]<br/>- failedAttempts: 0"]
    K["Save to PostgreSQL"]
    L["Generate access token<br/>(JWT RS256, 15min)"]
    M["Generate refresh token<br/>(32-byte random)"]
    N["Hash refresh token<br/>(SHA-256)"]
    O["Store RefreshToken entity"]
    P["Log audit:<br/>USER_REGISTERED"]
    Q["Emit metric:<br/>identity_register_total"]
    R["✅ Return 201 Created<br/>{accessToken, refreshToken,<br/>expiresIn: 900, tokenType: Bearer}"]

    A --> B --> C
    C -->|Yes| D
    C -->|No| E
    E --> F --> G
    G -->|Yes| H
    G -->|No| I
    I --> J --> K --> L --> M --> N --> O --> P --> Q --> R

    style R fill:#7ED321,color:#000
    style D fill:#FF6B6B,color:#fff
    style H fill:#FF6B6B,color:#fff
```

## User Login Flow

```mermaid
flowchart TD
    A["🌐 POST /auth/login<br/>{email, password, deviceInfo}"]
    B["Rate limit check<br/>(10 req/min per IP)"]
    C{{"Rate limit<br/>exceeded?"}}
    D["❌ Return 429"]
    E["Start login timer<br/>(for metrics)"]
    F["Normalize email"]
    G["Find user by email"]
    H{{"User<br/>found?"}}
    I["Log: USER_LOGIN_FAILED<br/>(no such user)"]
    J["❌ Return 401<br/>InvalidCredentialsException"]
    K{{"Account<br/>locked?"}}
    L["Log: USER_LOGIN_LOCKED"]
    M["❌ Return 403<br/>UserInactiveException"]
    N["Verify password<br/>(bcrypt)"]
    O{{"Password<br/>correct?"}}
    P["Increment failedAttempts"]
    Q{{"Attempts >= 10?"}}
    R["Lock account<br/>(30 min)"]
    S["Log: USER_LOGIN_FAILED"]
    T["❌ Return 401"]
    U{{"Status ==<br/>ACTIVE?"}}
    V["Log: USER_LOGIN_BLOCKED<br/>(suspended/deleted)"]
    W["❌ Return 403"]
    X["Reset failedAttempts = 0<br/>Clear lockedUntil"]
    Y["Generate access token"]
    Z["Generate refresh token"]
    AA["Store refresh token hash<br/>(with deviceInfo)"]
    AB["Enforce max tokens<br/>(delete oldest if > limit)"]
    AC["Log: USER_LOGIN_SUCCESS"]
    AD["Record login latency"]
    AE["✅ Return 200 OK<br/>{accessToken, refreshToken}"]

    A --> B --> C
    C -->|Yes| D
    C -->|No| E --> F --> G --> H
    H -->|No| I --> J
    H -->|Yes| K
    K -->|Yes| L --> M
    K -->|No| N --> O
    O -->|No| P --> Q
    Q -->|Yes| R --> S --> T
    Q -->|No| S
    O -->|Yes| U
    U -->|No| V --> W
    U -->|Yes| X --> Y --> Z --> AA --> AB --> AC --> AD --> AE

    style AE fill:#7ED321,color:#000
    style D fill:#FF6B6B,color:#fff
    style J fill:#FF6B6B,color:#fff
    style M fill:#FF6B6B,color:#fff
    style T fill:#FF6B6B,color:#fff
    style W fill:#FF6B6B,color:#fff
```

## Token Refresh Flow

```mermaid
flowchart TD
    A["🌐 POST /auth/refresh<br/>{refreshToken}"]
    B["Hash incoming token<br/>(SHA-256)"]
    C["Find RefreshToken by hash"]
    D{{"Token<br/>found?"}}
    E["❌ Return 401<br/>TokenInvalidException"]
    F{{"Token<br/>revoked?"}}
    G["❌ Return 401<br/>TokenRevokedException"]
    H{{"Token<br/>expired?"}}
    I["❌ Return 401<br/>TokenExpiredException"]
    J{{"User status<br/>ACTIVE?"}}
    K["❌ Return 403<br/>UserInactiveException"]
    L["Mark old token as revoked<br/>(token rotation)"]
    M["Generate new refresh token"]
    N["Hash new token"]
    O["Store new RefreshToken<br/>(copy deviceInfo)"]
    P["Generate new access token"]
    Q["Enforce max tokens"]
    R["Log: TOKEN_REFRESH"]
    S["✅ Return 200 OK<br/>{accessToken, refreshToken}"]

    A --> B --> C --> D
    D -->|No| E
    D -->|Yes| F
    F -->|Yes| G
    F -->|No| H
    H -->|Yes| I
    H -->|No| J
    J -->|No| K
    J -->|Yes| L --> M --> N --> O --> P --> Q --> R --> S

    style S fill:#7ED321,color:#000
    style E fill:#FF6B6B,color:#fff
    style G fill:#FF6B6B,color:#fff
    style I fill:#FF6B6B,color:#fff
    style K fill:#FF6B6B,color:#fff
```

## Token Revocation Flow

```mermaid
flowchart TD
    A["🌐 POST /auth/revoke<br/>{refreshToken}<br/>Authorization: Bearer <JWT>"]
    B["Validate JWT"]
    C{{"JWT<br/>valid?"}}
    D["❌ Return 401"]
    E["Extract user_id from JWT"]
    F["Hash refresh token"]
    G["Find RefreshToken by hash"]
    H{{"Token<br/>found?"}}
    I["❌ Return 401<br/>TokenInvalidException"]
    J{{"Token belongs<br/>to current user?"}}
    K["❌ Return 403<br/>AccessDeniedException"]
    L{{"Already<br/>revoked?"}}
    M["No-op (idempotent)"]
    N["Mark as revoked"]
    O["Log: TOKEN_REVOKE"]
    P["✅ Return 204 No Content"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F --> G --> H
    H -->|No| I
    H -->|Yes| J
    J -->|No| K
    J -->|Yes| L
    L -->|Yes| M --> P
    L -->|No| N --> O --> P

    style P fill:#7ED321,color:#000
    style D fill:#FF6B6B,color:#fff
    style I fill:#FF6B6B,color:#fff
    style K fill:#FF6B6B,color:#fff
```

## Logout Flow (Revoke All Tokens)

```mermaid
flowchart TD
    A["🌐 POST /auth/logout<br/>Authorization: Bearer <JWT>"]
    B["Validate JWT"]
    C{{"JWT<br/>valid?"}}
    D["❌ Return 401"]
    E["Extract user_id from JWT"]
    F["Revoke all active refresh tokens<br/>for user"]
    G["Count revoked tokens"]
    H["Log: USER_LOGOUT<br/>(revokedTokens: N)"]
    I["✅ Return 204 No Content"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F --> G --> H --> I

    style I fill:#7ED321,color:#000
    style D fill:#FF6B6B,color:#fff
```

## JWKS Endpoint Flow

```mermaid
flowchart TD
    A["🌐 GET /.well-known/jwks.json"]
    B["No authentication required<br/>(public endpoint)"]
    C["Load RSA public key<br/>from JwtKeyLoader"]
    D["Extract modulus (n)<br/>and exponent (e)"]
    E["Base64url encode<br/>n and e"]
    F["Build JWK object:<br/>{kty, use, alg, kid, n, e}"]
    G["✅ Return 200 OK<br/>{keys: [jwk]}"]

    A --> B --> C --> D --> E --> F --> G

    style G fill:#7ED321,color:#000
    style B fill:#9013FE,color:#fff
```

## Password Change Flow

```mermaid
flowchart TD
    A["🌐 PUT /users/me/password<br/>{currentPassword, newPassword}<br/>Authorization: Bearer <JWT>"]
    B["Validate JWT"]
    C{{"JWT<br/>valid?"}}
    D["❌ Return 401"]
    E["Extract user_id from JWT"]
    F["Load user from DB"]
    G["Verify current password<br/>(bcrypt)"]
    H{{"Current<br/>password correct?"}}
    I["❌ Return 401<br/>InvalidCredentialsException"]
    J["Hash new password<br/>(bcrypt)"]
    K["Update user.passwordHash"]
    L["Revoke all refresh tokens<br/>(force re-login)"]
    M["Log: PASSWORD_CHANGED"]
    N["✅ Return 204 No Content"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F --> G --> H
    H -->|No| I
    H -->|Yes| J --> K --> L --> M --> N

    style N fill:#7ED321,color:#000
    style D fill:#FF6B6B,color:#fff
    style I fill:#FF6B6B,color:#fff
```

## Admin User Status Update Flow

```mermaid
flowchart TD
    A["🌐 PUT /admin/users/{id}/status<br/>{status: SUSPENDED}<br/>Authorization: Bearer <JWT>"]
    B["Validate JWT"]
    C{{"JWT<br/>valid?"}}
    D["❌ Return 401"]
    E["Extract roles from JWT"]
    F{{"Has<br/>ADMIN role?"}}
    G["❌ Return 403"]
    H["Find target user by ID"]
    I{{"User<br/>found?"}}
    J["❌ Return 404<br/>UserNotFoundException"]
    K["Update user.status"]
    L{{"Status ==<br/>SUSPENDED?"}}
    M["Revoke all refresh tokens<br/>(kick user out)"]
    N["Log: USER_STATUS_CHANGED<br/>(by admin)"]
    O["✅ Return 200 OK"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F
    F -->|No| G
    F -->|Yes| H --> I
    I -->|No| J
    I -->|Yes| K --> L
    L -->|Yes| M --> N --> O
    L -->|No| N

    style O fill:#7ED321,color:#000
    style D fill:#FF6B6B,color:#fff
    style G fill:#FF6B6B,color:#fff
    style J fill:#FF6B6B,color:#fff
```
