# Identity Service - Flowchart (Token Refresh Read Path)

```mermaid
flowchart TD
    A["Client sends POST /auth/refresh<br/>with refreshToken in body"]
    B{"Request validation<br/>passed?"}
    C["Extract token from request"]
    D["Hash the token using SHA256"]
    E{"Token hash<br/>in DB?"}
    F{"Token<br/>expired?"]
    G{"Token<br/>revoked?"}
    H["Fetch user from DB by user_id"]
    I{"User status<br/>= ACTIVE?"}
    J["Generate RS256 JWT<br/>with 1hr expiration"]
    K["Generate new refresh token"]
    L["Store refresh token hash<br/>in DB"]
    M["Create audit log entry"]
    N["Publish TokenRefreshed<br/>event to Kafka"]
    O["Return 200 OK<br/>with new tokens"]
    P["Return 400 Bad Request<br/>Invalid token format"]
    Q["Return 404 Not Found<br/>Token not found"]
    R["Return 401 Unauthorized<br/>Token expired"]
    S["Return 401 Unauthorized<br/>Token revoked"]
    T["Return 403 Forbidden<br/>User not active"]

    A --> B
    B -->|No| P
    B -->|Yes| C
    C --> D
    D --> E
    E -->|No| Q
    E -->|Yes| F
    F -->|Yes| R
    F -->|No| G
    G -->|Yes| S
    G -->|No| H
    H --> I
    I -->|No| T
    I -->|Yes| J
    J --> K
    K --> L
    L --> M
    M --> N
    N --> O

    style A fill:#4A90E2,color:#fff
    style O fill:#7ED321,color:#000
    style P fill:#FF6B6B,color:#fff
    style Q fill:#FF6B6B,color:#fff
    style R fill:#FF6B6B,color:#fff
    style S fill:#FF6B6B,color:#fff
    style T fill:#FF6B6B,color:#fff
    style J fill:#F5A623,color:#000
    style N fill:#50E3C2,color:#000
```

## Login Flow - Read Path

```mermaid
flowchart TD
    A["Client sends POST /auth/login<br/>with email, password"]
    B["Rate limit check<br/>per source IP"]
    C{"Rate limit<br/>exceeded?"}
    D["Retrieve user by email<br/>from DB"]
    E{"User<br/>found?"}
    F["Verify password hash<br/>using BCrypt"]
    G{"Password<br/>match?"}
    H["Check user status"]
    I{"Status<br/>= ACTIVE?"}
    J["Issue JWT (access token)<br/>RS256, 1hr validity"]
    K["Create refresh token<br/>and store hash"]
    L["Create audit log"]
    M["Publish LoginSuccess event"]
    N["Return 200 OK<br/>with tokens"]
    O["Return 429 Too Many Requests"]
    P["Return 404 Not Found<br/>User doesn't exist"]
    Q["Return 401 Unauthorized<br/>Password mismatch"]
    R["Return 403 Forbidden<br/>User account suspended"]
    S["Increment login attempt counter<br/>for source IP"]

    A --> B
    B --> C
    C -->|Yes| O
    C -->|No| S
    S --> D
    D --> E
    E -->|No| P
    E -->|Yes| F
    F --> G
    G -->|No| Q
    G -->|Yes| H
    H --> I
    I -->|No| R
    I -->|Yes| J
    J --> K
    K --> L
    L --> M
    M --> N

    style A fill:#4A90E2,color:#fff
    style N fill:#7ED321,color:#000
    style O fill:#FF6B6B,color:#fff
    style P fill:#FF6B6B,color:#fff
    style Q fill:#FF6B6B,color:#fff
    style R fill:#FF6B6B,color:#fff
```

## Token Validation Read Path (JWKS)

```mermaid
flowchart TD
    A["External service<br/>requests GET /.well-known/jwks.json"]
    B["Check cache<br/>for JWKS"]
    C{"JWKS<br/>in cache?"}
    D["Fetch public keys<br/>from SecureKeyStore"]
    E["Build JWK Set response"]
    F["Cache for 24 hours"]
    G["Return 200 OK<br/>with JWK Set"]
    H["Return cached JWKS<br/>with ETag"]
    I{"ETag<br/>match?"}
    J["Return 304 Not Modified"]

    A --> B
    B --> C
    C -->|Yes| H
    C -->|No| D
    D --> E
    E --> F
    F --> G
    H --> I
    I -->|Yes| J
    I -->|No| G

    style A fill:#4A90E2,color:#fff
    style G fill:#7ED321,color:#000
    style J fill:#7ED321,color:#000
    style D fill:#F5A623,color:#000
```
