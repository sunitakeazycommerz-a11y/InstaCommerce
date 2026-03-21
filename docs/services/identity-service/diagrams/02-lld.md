# Identity Service - Low-Level Design Diagram

## Request Handler Architecture

```mermaid
graph TB
    Request["HTTP Request"]
    RateLimit["RateLimitFilter<br/>(IP-based throttle)"]
    Validate["RequestValidationFilter<br/>(Format check)"]
    Auth["InternalServiceAuthFilter<br/>(Token validation)"]
    Handler["Route Handler"]

    Request -->|500ms timeout| RateLimit
    RateLimit -->|check cache| Validate
    Validate -->|schema| Auth
    Auth -->|per-service token| Handler

    style RateLimit fill:#FF6B6B,color:#fff
    style Validate fill:#F5A623,color:#000
    style Auth fill:#4A90E2,color:#fff
```

## Token Refresh Flow - Component Level

```mermaid
graph TD
    RefreshReq["POST /auth/refresh<br/>{refreshToken}"]
    ValidateReq["Validate Request<br/>(format, signature)"]
    LookupToken["Query RefreshTokenRepository"]
    CheckExpiry["Token Expired?"]
    CheckRevoked["Token Revoked?"]
    ReissueJWT["Generate new JWT<br/>(RS256, 1hr validity)"]
    SaveHistory["Save to AuditLogRepository"]
    PublishEvent["Publish TokenRefreshed<br/>to Kafka"]
    Response["200 OK<br/>{accessToken, refreshToken}"]

    RefreshReq -->|HS256 verify| ValidateReq
    ValidateReq -->|by token_hash| LookupToken
    LookupToken -->|check| CheckExpiry
    CheckExpiry -->|yes| Response
    CheckExpiry -->|no| CheckRevoked
    CheckRevoked -->|yes| Response
    CheckRevoked -->|no| ReissueJWT
    ReissueJWT -->|async| SaveHistory
    SaveHistory -->|async| PublishEvent
    PublishEvent -->|return| Response

    style ValidateReq fill:#F5A623
    style ReissueJWT fill:#7ED321
    style PublishEvent fill:#50E3C2
```

## State Machine: User Lifecycle

```mermaid
stateDiagram-v2
    [*] --> CREATED: register()
    CREATED --> ACTIVE: email_verified
    ACTIVE --> SUSPENDED: admin_action
    SUSPENDED --> ACTIVE: admin_reinstate
    SUSPENDED --> DELETED: gdpr_erasure
    ACTIVE --> DELETED: user_initiated_deletion
    DELETED --> [*]

    note right of CREATED
        Waiting for email verification
        Refresh tokens invalid
    end note

    note right of ACTIVE
        Can login, refresh tokens
        Full API access
    end note

    note right of SUSPENDED
        Cannot login
        Audit trail preserved
        Can be reinstated
    end note

    note right of DELETED
        PII scrubbed (GDPR)
        Audit trail retained
        No token re-issuance
    end note
```

## Database Connection Pool

```mermaid
graph LR
    App["Spring Boot App<br/>(Multiple Threads)"]
    HikariCP["HikariCP Connection Pool<br/>(min: 5, max: 20)"]
    ConnReady["Available<br/>(5)"]
    ConnBusy["In Use<br/>(15)"]
    DB["PostgreSQL"]

    App -->|borrow| HikariCP
    HikariCP -->|check| ConnReady
    HikariCP -->|if no free| ConnBusy
    ConnReady -->|execute| DB
    ConnBusy -->|wait 30s| ConnReady
    DB -->|return| HikariCP

    style HikariCP fill:#7ED321
    style ConnReady fill:#52C41A
    style ConnBusy fill:#FF4D4F
```

## JWT Token Structure (Decoded)

```
Header: {
  "alg": "RS256",
  "kid": "identity-service-key-1",
  "typ": "JWT"
}

Payload: {
  "sub": "user-uuid",
  "email": "user@example.com",
  "roles": ["CUSTOMER"],
  "aud": "instacommerce",
  "iss": "identity-service",
  "exp": 1700000000,
  "iat": 1700000000,
  "scope": "api:read api:write"
}

Signature: RS256(header.payload, private_key)
```

## Async Event Publishing Pipeline

```mermaid
graph LR
    AuthSvc["AuthService<br/>(Sync)"]
    OutboxSvc["OutboxService<br/>(Write event)"]
    OutboxTbl[("Outbox Table<br/>(PostgreSQL)")]
    PollingJob["Polling Job<br/>(100ms interval)"]
    KafkaProducer["Kafka Producer<br/>(Batch 100 events)"]
    Kafka["Kafka Topic<br/>(auth.events)"]

    AuthSvc -->|fire| OutboxSvc
    OutboxSvc -->|insert| OutboxTbl
    PollingJob -->|poll| OutboxTbl
    PollingJob -->|batch send| KafkaProducer
    KafkaProducer -->|ack| Kafka
    KafkaProducer -->|mark sent| OutboxTbl

    style OutboxSvc fill:#50E3C2
    style KafkaProducer fill:#50E3C2
```
