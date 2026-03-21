# Identity Service - Entity-Relationship Diagram

```mermaid
erDiagram
    USERS ||--o{ REFRESH_TOKENS : creates
    USERS ||--o{ AUDIT_LOGS : generates
    USERS ||--o{ NOTIFICATION_PREFERENCES : has
    USERS ||--o{ OUTBOX_EVENTS : publishes
    REFRESH_TOKENS ||--o{ AUDIT_LOGS : tracked_by

    USERS {
        uuid id PK
        string email UK "UNIQUE"
        string password_hash "bcrypt(12 rounds)"
        string[] roles "ARRAY['CUSTOMER']"
        string status "ENUM: ACTIVE, SUSPENDED, DELETED"
        timestamp created_at
        timestamp updated_at
        bigint version "Optimistic locking"
    }

    REFRESH_TOKENS {
        uuid id PK
        uuid user_id FK "REFERENCES users(id)"
        string token_hash UK "SHA256(token)"
        string device_info "User-Agent or device name"
        timestamp expires_at "7 days from creation"
        boolean revoked "Default: false"
        timestamp created_at
    }

    AUDIT_LOGS {
        uuid id PK
        uuid user_id FK "REFERENCES users(id)"
        string event_type "ENUM: LOGIN, LOGOUT, REGISTER, TOKEN_REFRESH, PASSWORD_CHANGE, DELETION"
        string source_ip "IPv4/IPv6"
        string user_agent
        string status "SUCCESS, FAILURE"
        string reason "Failure reason if applicable"
        timestamp created_at
        timestamp updated_at
    }

    NOTIFICATION_PREFERENCES {
        uuid id PK
        uuid user_id FK "REFERENCES users(id), UNIQUE"
        boolean email_login_alerts "Default: true"
        boolean email_security_alerts "Default: true"
        boolean email_marketing "Default: false"
        timestamp created_at
        timestamp updated_at
    }

    OUTBOX_EVENTS {
        uuid id PK
        string event_type "e.g., UserRegistered, TokenRefreshed"
        json payload "Event data"
        string topic "Kafka topic name"
        boolean sent "Default: false"
        timestamp created_at "Index: (created_at) WHERE sent=false"
        timestamp sent_at
    }
```

## Database Indexes & Performance Tuning

```mermaid
graph LR
    Users["🔑 USERS Table"]
    UserEmail["idx_users_email<br/>Column: email<br/>Type: BTREE"]
    UserStatus["idx_users_status<br/>Column: status<br/>Filter: status='ACTIVE'<br/>Type: PARTIAL"]

    RefreshTokens["🔑 REFRESH_TOKENS Table"]
    RTHash["idx_refresh_token_hash<br/>Column: token_hash<br/>Type: BTREE (UK)"]
    RTExpiry["idx_refresh_tokens_expiry<br/>Columns: expires_at, revoked<br/>Filter: revoked=false<br/>Type: PARTIAL"]
    RTUser["idx_refresh_tokens_user<br/>Column: user_id<br/>Type: BTREE"]

    AuditLogs["🔑 AUDIT_LOGS Table"]
    ALUser["idx_audit_logs_user_ts<br/>Columns: user_id, created_at<br/>Type: BTREE"]
    ALType["idx_audit_logs_type<br/>Column: event_type<br/>Type: BTREE"]
    ALCreated["idx_audit_logs_created<br/>Column: created_at<br/>Type: BTREE"]

    OutboxEvents["🔑 OUTBOX_EVENTS Table"]
    OEUnsent["idx_outbox_unsent<br/>Columns: created_at<br/>Filter: sent=false<br/>Type: PARTIAL<br/>Usage: Polling job"]

    Users --> UserEmail
    Users --> UserStatus
    RefreshTokens --> RTHash
    RefreshTokens --> RTExpiry
    RefreshTokens --> RTUser
    AuditLogs --> ALUser
    AuditLogs --> ALType
    AuditLogs --> ALCreated
    OutboxEvents --> OEUnsent

    style UserEmail fill:#7ED321,color:#000
    style RTHash fill:#7ED321,color:#000
    style OEUnsent fill:#F5A623,color:#000
```

## Column-Level Constraints & Triggers

```mermaid
graph TD
    Users["USERS Table"]
    UEmail["email<br/>NOT NULL, UNIQUE<br/>CHECK length(email) BETWEEN 5 AND 255"]
    UPassHash["password_hash<br/>NOT NULL<br/>CHECK length(password_hash) = 72"]
    URoles["roles<br/>PostgreSQL ARRAY<br/>Elements: CUSTOMER, ADMIN, FRAUD_ANALYST"]
    UStatus["status<br/>CHECK status IN<br/>ACTIVE, SUSPENDED, DELETED"]
    UVersion["version<br/>BIGINT DEFAULT 0<br/>Used for optimistic locking"]

    RefreshTokens["REFRESH_TOKENS Table"]
    RTHash["token_hash<br/>UNIQUE<br/>CHECK length = 64<br/>CHECK NOT NULL"]
    RTExpiry["expires_at<br/>NOT NULL<br/>CHECK expires_at > created_at"]
    RTCascade["ON DELETE CASCADE<br/>from USERS (id)"]

    AuditLogs["AUDIT_LOGS Table"]
    ALType["event_type<br/>CHECK event_type IN<br/>LOGIN, LOGOUT, REGISTER,<br/>TOKEN_REFRESH, DELETE"]

    Users --> UEmail
    Users --> UPassHash
    Users --> URoles
    Users --> UStatus
    Users --> UVersion

    RefreshTokens --> RTHash
    RefreshTokens --> RTExpiry
    RefreshTokens --> RTCascade

    AuditLogs --> ALType

    style RTCascade fill:#FF6B6B,color:#fff
```

## Data Flow Through Schema

```mermaid
graph LR
    Register["1. User Registration"]
    UserInsert["INSERT INTO users<br/>(email, password_hash, status)"]
    OutboxInsert["INSERT INTO outbox_events<br/>(event_type=UserRegistered)"]

    Login["2. User Login"]
    UserQuery["SELECT FROM users<br/>WHERE email=?"]
    PasswordVerify["Verify password_hash"]
    TokenInsert["INSERT INTO refresh_tokens<br/>(user_id, token_hash)"]
    AuditInsert["INSERT INTO audit_logs<br/>(event_type=LOGIN)"]

    Refresh["3. Token Refresh"]
    TokenQuery["SELECT FROM refresh_tokens<br/>WHERE token_hash=?"]
    CheckExpiry["Check expires_at > now()"]
    NewTokenInsert["INSERT INTO refresh_tokens"]
    AuditInsert2["INSERT INTO audit_logs<br/>(event_type=TOKEN_REFRESH)"]

    Register --> UserInsert
    UserInsert --> OutboxInsert
    Login --> UserQuery
    UserQuery --> PasswordVerify
    PasswordVerify --> TokenInsert
    TokenInsert --> AuditInsert
    Refresh --> TokenQuery
    TokenQuery --> CheckExpiry
    CheckExpiry --> NewTokenInsert
    NewTokenInsert --> AuditInsert2

    style UserInsert fill:#7ED321,color:#000
    style TokenInsert fill:#4A90E2,color:#fff
    style AuditInsert fill:#F5A623,color:#000
```

## Partitioning Strategy for Large Tables

```mermaid
graph TD
    AuditLogs["AUDIT_LOGS Table<br/>(Range Partitioned by DATE)<br/>Total rows: ~100M/year"]
    P2025Q1["Partition Q1-2025<br/>(Jan-Mar 2025)<br/>~25M rows"]
    P2025Q2["Partition Q2-2025<br/>(Apr-Jun 2025)<br/>~25M rows"]
    P2025Q3["Partition Q3-2025<br/>(Jul-Sep 2025)<br/>~25M rows"]
    P2025Q4["Partition Q4-2025<br/>(Oct-Dec 2025)<br/>~25M rows"]
    PDefault["Partition DEFAULT<br/>(Future dates)"]

    AuditLogs --> P2025Q1
    AuditLogs --> P2025Q2
    AuditLogs --> P2025Q3
    AuditLogs --> P2025Q4
    AuditLogs --> PDefault

    note right of AuditLogs
        Retention: 2 years
        Quarterly cleanup jobs
        Archival to cold storage after 1 year
    end note

    style P2025Q1 fill:#52C41A,color:#fff
    style P2025Q2 fill:#52C41A,color:#fff
    style P2025Q3 fill:#52C41A,color:#fff
    style P2025Q4 fill:#52C41A,color:#fff
```
