# Identity Cluster - High-Level Design Diagrams

## System Context Diagram

```mermaid
graph TB
    subgraph internet["Internet (Public)"]
        MobileApp["📱 Mobile App<br/>(iOS/Android)"]
        WebApp["🌐 Web Browser<br/>(React)"]
        AdminConsole["👨‍💼 Admin Console"]
    end

    subgraph ingress["Istio Ingress"]
        TLS["🔒 TLS Termination<br/>Rate Limiting<br/>SIMPLE mode"]
    end

    subgraph mesh["Service Mesh (Istio mTLS)"]
        Identity["🔐 identity-service<br/>Port 8080<br/>Spring Boot 4.0<br/>Java 21"]
        AdminGW["🚪 admin-gateway-service<br/>Port 8080<br/>Spring Security<br/>JWT Validation"]
        DomainServices["🔄 Domain Services<br/>(order, payment, etc.)"]
    end

    subgraph data["Data Layer"]
        CloudSQL[("🗄️ Cloud SQL PostgreSQL<br/>identity_db<br/>Private VPC<br/>Multi-AZ")]
        Kafka["📨 Apache Kafka<br/>identity.events topic<br/>User erasure events"]
        SecretManager["🔑 GCP Secret Manager<br/>RSA private key<br/>DB password"]
    end

    MobileApp -->|HTTPS| TLS
    WebApp -->|HTTPS| TLS
    AdminConsole -->|HTTPS| TLS
    TLS -->|mTLS| Identity
    TLS -->|mTLS| AdminGW

    Identity -->|JWT validate| DomainServices
    AdminGW -->|Request proxy| DomainServices
    DomainServices -->|Service→Service<br/>X-Internal-Token| Identity

    Identity -->|Read/Write| CloudSQL
    Identity -->|Load RSA keys| SecretManager
    Identity -->|Publish UserErased| Kafka

    Kafka -->|CDC| DomainServices

    style Identity fill:#4a90e2,color:#fff
    style AdminGW fill:#f5a623,color:#000
    style TLS fill:#e74c3c,color:#fff
    style CloudSQL fill:#50e3c2,color:#000
    style SecretManager fill:#9b59b6,color:#fff
```

## Trust Boundaries & Security Layers

```mermaid
graph LR
    subgraph B1["B1: Internet → Ingress"]
        direction TB
        Internet["🌍 Public Internet"]
        TLS["🔒 TLS 1.3<br/>Rate Limiting"]
        Internet -->|HTTPS| TLS
    end

    subgraph B2["B2: Ingress → Service"]
        direction TB
        IGW["Istio Gateway"]
        mTLS["🔐 mTLS Sidecar<br/>Cert Verification"]
        Identity["identity-service"]
        IGW -->|mTLS| mTLS
        mTLS -->|Decrypt| Identity
    end

    subgraph B3["B3: Service → Service"]
        direction TB
        JwtAuth["JWT Validation<br/>RS256 signature<br/>Issuer + Audience"]
        InternalToken["X-Internal-Token<br/>Shared secret<br/>Defense in depth"]
        AuthFilter["SecurityConfig<br/>Filter Chain"]
        JwtAuth -.-> AuthFilter
        InternalToken -.-> AuthFilter
    end

    subgraph B4["B4: Service → Data"]
        direction TB
        Workload["🔑 GCP Workload Identity<br/>IAM attached to pod"]
        CloudSQL["Private VPC<br/>CloudSQL instance"]
        Workload -->|IAM auth| CloudSQL
    end

    TLS -->|Port 443| B2
    B2 -->|Port 8080 mTLS| B3
    B3 -->|Encrypted| B4

    style B1 fill:#fee,stroke:#c00
    style B2 fill:#ffe,stroke:#a80
    style B3 fill:#eef,stroke:#00a
    style B4 fill:#efe,stroke:#0a0
```

## Deployment Topology (Kubernetes)

```mermaid
graph TB
    subgraph gke["GKE Cluster (asia-south1)"]
        subgraph ns["Namespace: instacommerce"]
            subgraph deploy["Deployment: identity-service"]
                Pod1["Pod 1<br/>identity-service:1"]
                Pod2["Pod 2<br/>identity-service:1"]
                Pod3["Pod 3<br/>identity-service:1"]
            end

            subgraph deploy2["Deployment: admin-gateway"]
                Pod4["Pod 1<br/>admin-gateway:1"]
                Pod5["Pod 2<br/>admin-gateway:1"]
            end

            SVC1["Service: identity-service<br/>Cluster IP"]
            SVC2["Service: admin-gateway<br/>Cluster IP"]

            Pod1 --> SVC1
            Pod2 --> SVC1
            Pod3 --> SVC1
            Pod4 --> SVC2
            Pod5 --> SVC2
        end

        subgraph istio["Istio Control Plane"]
            IGW["Gateway<br/>TLS SIMPLE"]
            RA["RequestAuthentication<br/>JWKS"]
            AP["AuthorizationPolicy<br/>DENY_DEFAULT"]
        end

        IGW -->|Routes| SVC1
        IGW -->|Routes| SVC2
        RA -.->|Validates| Pod1
        AP -.->|Controls| Pod1
    end

    subgraph gcp["GCP (asia-south1 region)"]
        CloudSQL[("Cloud SQL<br/>PostgreSQL 15+<br/>HA: Primary + Replica")]
        SecretMgr["Secret Manager<br/>RSA keys"]
        Kafka["Cloud Pubsub<br/>(or self-managed Kafka)"]
    end

    SVC1 -->|Private IP| CloudSQL
    SVC1 -->|Workload Identity| SecretMgr
    SVC1 -->|Publish events| Kafka
```

## Request Flow: User Login

```mermaid
sequenceDiagram
    participant Client as 📱 Mobile Client
    participant IGW as 🚪 Istio Gateway
    participant Sidecar as 🔐 Envoy Sidecar
    participant Auth as 🔐 AuthController
    participant AuthSvc as 🔄 AuthService
    participant BCrypt as 🔑 PasswordEncoder
    participant JWT as 📜 JwtService
    participant DB as 🗄️ PostgreSQL

    Client->>IGW: POST /auth/login {email, password}
    Note over IGW: TLS termination, rate limit check
    IGW->>Sidecar: Forward request (encrypted connection)
    Sidecar->>Sidecar: Verify mutual TLS certs
    Sidecar->>Auth: Inject request

    Auth->>AuthSvc: login(email, password, ip, userAgent)

    AuthSvc->>AuthSvc: checkLoginRateLimit(ip)
    alt Rate limit exceeded
        AuthSvc-->>Auth: 429 Too Many Requests
    end

    AuthSvc->>DB: SELECT * FROM users WHERE email=?
    alt User not found
        AuthSvc->>AuthSvc: logAction(USER_LOGIN_FAILED)
        AuthSvc-->>Auth: 401 InvalidCredentials
    end

    AuthSvc->>AuthSvc: checkAccountLocked(user)
    alt locked_until > now
        AuthSvc-->>Auth: 403 UserInactive
    end

    AuthSvc->>BCrypt: matches(password, passwordHash)
    alt Invalid password
        AuthSvc->>DB: UPDATE users SET failed_attempts++
        alt failed_attempts >= 10
            AuthSvc->>DB: UPDATE users SET locked_until=now+30min
        end
        AuthSvc->>AuthSvc: logAction(USER_LOGIN_FAILED)
        AuthSvc-->>Auth: 401 InvalidCredentials
    end

    AuthSvc->>DB: UPDATE users SET failed_attempts=0, lockedUntil=NULL
    AuthSvc->>JWT: generateAccessToken(user)
    Note over JWT: RS256 sign, 15-min TTL
    AuthSvc->>DB: INSERT refresh_tokens (hashed token, +7 days)
    AuthSvc->>AuthSvc: logAction(USER_LOGIN_SUCCESS)

    Auth-->>Sidecar: 200 {accessToken, refreshToken}
    Sidecar-->>IGW: Forward response
    IGW-->>Client: 200 {accessToken, refreshToken}
```

## Data Model Relationships

```mermaid
erDiagram
    USERS ||--o{ REFRESH_TOKENS : generates
    USERS ||--o{ AUDIT_LOG : performs
    USERS ||--o{ OUTBOX_EVENTS : triggers
    REFRESH_TOKENS ||--o{ AUDIT_LOG : audits
    OUTBOX_EVENTS ||--o{ AUDIT_LOG : publishes

    USERS {
        uuid id PK
        string email UK
        string password_hash
        string[] roles
        string status
        int failed_attempts
        timestamp locked_until
        timestamp deleted_at
        bigint version "optimistic locking"
    }

    REFRESH_TOKENS {
        uuid id PK
        uuid user_id FK
        string token_hash UK
        string device_info
        timestamp expires_at
        boolean revoked
        timestamp created_at
    }

    AUDIT_LOG {
        bigserial id PK
        uuid user_id FK
        string action
        jsonb details
        string ip_address
        string trace_id
        timestamp created_at
    }

    OUTBOX_EVENTS {
        uuid id PK
        string aggregate_type
        string aggregate_id
        string event_type
        jsonb payload
        boolean sent
        timestamp created_at
    }
```

## Authentication Filter Chain

```mermaid
graph TD
    Request["HTTP Request"]

    Request -->|Check headers| InternalAuth["InternalServiceAuthFilter<br/>1️⃣ X-Internal-Service header?"]

    InternalAuth -->|✓ Valid| SetInternal["Set SecurityContext<br/>ROLE_INTERNAL_SERVICE<br/>ROLE_ADMIN"]
    InternalAuth -->|✗ Not present| JwtFilter["JwtAuthenticationFilter<br/>2️⃣ Authorization: Bearer?"]

    JwtFilter -->|✓ Present| ValidateJwt["Validate JWT<br/>• RS256 signature<br/>• iss=instacommerce-identity<br/>• aud=instacommerce-api<br/>• exp check"]
    JwtFilter -->|✗ Not present| AnonymousAuth["Anonymous Authentication<br/>Public endpoints only"]

    ValidateJwt -->|✓ Valid| ExtractRoles["Extract roles from JWT<br/>Set SecurityContext"]
    ValidateJwt -->|✗ Invalid| Reject401["401 Unauthorized"]

    ExtractRoles --> AuthorizeRequest["@PreAuthorize rules<br/>• /admin/* → hasRole('ADMIN')<br/>• /users/me/* → authenticated<br/>• /auth/* → permitAll"]
    AnonymousAuth --> AuthorizeRequest

    AuthorizeRequest -->|✓ Allowed| Controller["Route to Controller"]
    AuthorizeRequest -->|✗ Denied| Reject403["403 Forbidden"]

    Controller -->|Execute| Response["HTTP Response"]
    Reject401 -->|Execute| Response
    Reject403 -->|Execute| Response

    style Request fill:#4a90e2,color:#fff
    style SetInternal fill:#50e3c2,color:#000
    style ExtractRoles fill:#50e3c2,color:#000
    style AnonymousAuth fill:#f5a623,color:#000
    style Reject401 fill:#e74c3c,color:#fff
    style Reject403 fill:#e74c3c,color:#fff
```

## Component Interaction Diagram

```mermaid
graph TB
    subgraph HTTP["HTTP Layer"]
        AC["AuthController<br/>POST /auth/login<br/>POST /auth/register<br/>POST /auth/refresh"]
        UC["UserController<br/>GET /users/me<br/>DELETE /users/me"]
        AC -.-> UC
    end

    subgraph Business["Business Logic"]
        AS["AuthService<br/>• Rate limit check<br/>• Password verify<br/>• Token generation"]
        TS["TokenService<br/>• generateAccessToken<br/>• generateRefreshToken<br/>• hashToken"]
        US["UserService"]
        AS -.-> TS
    end

    subgraph Security["Security"]
        JWT["DefaultJwtService<br/>• RS256 signing<br/>• JWKS publication"]
        KL["JwtKeyLoader<br/>• PEM parsing<br/>• kid computation"]
        JWT -.-> KL
    end

    subgraph Persistence["Persistence"]
        UR["UserRepository<br/>@Query methods"]
        RTR["RefreshTokenRepository<br/>findByTokenHash<br/>deleteAllByUser_Id"]
    end

    subgraph CrossCutting["Cross-Cutting"]
        AUD["AuditService<br/>@Async audit logs"]
        OBS["OutboxService<br/>Transactional outbox"]
    end

    AC --> AS
    UC --> US
    AS --> JWT
    AS --> UR
    AS --> RTR
    AS --> AUD
    US --> OBS
    TS --> RTR

    style AC fill:#4a90e2,color:#fff
    style AS fill:#50e3c2,color:#000
    style JWT fill:#9b59b6,color:#fff
```

This concludes the Identity & Admin Gateway cluster documentation.
