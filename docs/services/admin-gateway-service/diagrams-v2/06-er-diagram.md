# Admin Gateway - ER Diagram & Storage Schema

## Audit & Observability Tables (Kafka + Logs)

```mermaid
erDiagram
    AUDIT_EVENTS ||--o{ ADMIN_ACTIONS : "contains"
    ADMIN_ACTIONS ||--o{ SESSION_EVENTS : "triggers"
    SESSION_EVENTS ||--o{ RATE_LIMIT_LOG : "recorded in"

    AUDIT_EVENTS {
        uuid id PK "Unique event ID"
        uuid admin_user_id FK "Identity from JWT sub claim"
        string action "JWT_VALIDATED, DASHBOARD_ACCESSED, FLAGS_QUERIED, etc."
        string resource "dashboard, flags, reconciliation, payments"
        string resource_id "specific resource queried (e.g., flag_name)"
        string status "SUCCESS, FAILED, RATE_LIMITED"
        int http_status_code "200, 401, 403, 429, 500"
        string user_agent "Browser/Client info"
        string ip_address "Client IP from X-Forwarded-For"
        int response_time_ms "End-to-end latency"
        boolean cache_hit "true if response from cache"
        timestamp created_at "Event timestamp (UTC)"
        string error_message "null on success"
    }

    ADMIN_ACTIONS {
        uuid id PK "Unique action ID"
        uuid admin_user_id FK "User who performed action"
        string action_type "VIEW, EXPORT, DRILL_DOWN, etc."
        json action_payload "Action-specific data"
        timestamp created_at "Action timestamp"
        timestamp completed_at "Null if still processing"
        string outcome "SUCCESS, FAILURE, CANCELLED"
    }

    SESSION_EVENTS {
        uuid id PK "Unique session event ID"
        uuid admin_user_id FK "User ID"
        string event_type "LOGIN, LOGOUT, TOKEN_REFRESH, SESSION_EXPIRED"
        string session_id "HTTP session or JWT jti"
        timestamp created_at "Event timestamp"
        int duration_seconds "Session duration if LOGOUT"
        string ip_address "Original login IP"
    }

    RATE_LIMIT_LOG {
        uuid id PK "Unique log entry"
        uuid admin_user_id FK "User who hit limit"
        string endpoint "e.g., GET /api/admin/dashboard"
        int request_count_in_window "e.g., 105 (limit: 100)"
        int window_size_seconds "60 for 1-min window"
        timestamp window_start "Start of time window"
        timestamp window_end "End of time window"
        timestamp logged_at "When rejection occurred"
    }
```

## Redis Cache Schema & Keys

```mermaid
graph TB
    Redis["Redis<br/>Cache Cluster<br/>(Replication: 2)"]

    Dashboard["📊 dashboard_summary<br/>Type: JSON<br/>TTL: 5 min<br/>Pattern: dashboard_summary"]
    DashContent["Value:<br/>{<br/>payment_slo: 99.95,<br/>fulfillment_p99: 450ms,<br/>order_p50: 200ms,<br/>timestamp: 1711000000<br/>}"]

    Flags["🚩 admin:flags:active<br/>Type: JSON<br/>TTL: 30s<br/>Pattern: admin:flags:*"]
    FlagsContent["Value:<br/>[<br/>{name, enabled, rollout%},<br/>...<br/>]"]

    Reconcile["⚖️ reconciliation:runs:7d:p{page}<br/>Type: JSON<br/>TTL: 10 min<br/>Pattern: reconciliation:runs:*"]
    ReconcileContent["Value:<br/>{<br/>runs: [{run_id, status, mismatch_count}],<br/>total_count: 150,<br/>current_page: 1,<br/>has_next: true<br/>}"]

    Payments["💳 admin:payments:{status}<br/>Type: JSON<br/>TTL: 5 min<br/>Pattern: admin:payments:*"]
    PaymentContent["Value:<br/>{<br/>total_revenue: 1000000,<br/>transaction_count: 5000,<br/>error_rate: 0.05,<br/>breakdown: {card: 60%, upi: 30%}<br/>}"]

    RateLimitKey["🚦 rate_limit:{user_id}<br/>Type: Integer Counter<br/>TTL: 60s<br/>Pattern: rate_limit:*"]
    RateLimitValue["Value: Request count<br/>(incremented per request)<br/>Max: 100 per 60s"]

    JWKS_Key["🔐 jwks_cache<br/>Type: JSON<br/>TTL: 5 min<br/>Pattern: jwks_cache"]
    JWKS_Value["Value:<br/>{<br/>keys: [<br/>{kid, kty, use, alg, x5c}<br/>],<br/>updated_at: timestamp<br/>}"]

    Redis --> Dashboard
    Redis --> Flags
    Redis --> Reconcile
    Redis --> Payments
    Redis --> RateLimitKey
    Redis --> JWKS_Key

    Dashboard --> DashContent
    Flags --> FlagsContent
    Reconcile --> ReconcileContent
    Payments --> PaymentContent
    RateLimitKey --> RateLimitValue
    JWKS_Key --> JWKS_Value

    style Redis fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style DashContent fill:#E8F5E9,color:#000
    style FlagsContent fill:#E8F5E9,color:#000
    style ReconcileContent fill:#E8F5E9,color:#000
    style PaymentContent fill:#E8F5E9,color:#000
    style RateLimitValue fill:#E8F5E9,color:#000
    style JWKS_Value fill:#E8F5E9,color:#000
```

## Prometheus Metrics Schema

```
Admin Gateway Metrics
├─ http_server_requests_seconds{endpoint, method, status}
│  └ Histogram: latency distribution
│     - p50: 50ms, p99: <500ms, p99.9: <1s
│
├─ jwt_validation_duration_ms{endpoint}
│  └ Histogram: JWT validation latency
│     - RS256 verification
│     - JWKS lookup
│
├─ jwt_validations_total{status}
│  └ Counter: Total validations
│     - status: success, malformed_token, invalid_signature,
│               wrong_audience, expired, no_admin_role
│
├─ rate_limit_checks_total{endpoint, status}
│  └ Counter: Rate limit check results
│     - status: allowed, rejected
│
├─ rate_limit_rejections_total{user_id}
│  └ Counter: Total rate limit rejections
│     - per user tracking
│
├─ downstream_service_calls_duration_ms{service, endpoint}
│  └ Histogram: Service call latency
│     - service: payment-service, flag-service, reconciliation-service
│     - p99: <300ms per service
│
├─ cache_hits_total{endpoint}
│  └ Counter: Cache hits
│     - reduces downstream calls
│
├─ cache_misses_total{endpoint}
│  └ Counter: Cache misses
│     - triggers upstream fetch
│
├─ circuit_breaker_state{service}
│  └ Gauge: Circuit breaker status
│     - 0: CLOSED (healthy)
│     - 1: OPEN (degraded)
│     - 2: HALF_OPEN (testing)
│
├─ response_serialization_duration_ms
│  └ Histogram: JSON serialization time
│     - typically <20ms
│
└─ authentication_header_validation_duration_ms
   └ Histogram: Authorization header parse time
      - typically <2ms
```

## No Persistent Database (Stateless Gateway)

```mermaid
graph TB
    AdminGateway["🔐 Admin Gateway<br/>(Stateless Service)"]

    Note1["❌ NO Local Database<br/>- No PostgreSQL schema<br/>- No MySQL tables<br/>- No Oracle storage"]

    Note2["✅ Cache Only (Redis)<br/>- Performance optimization<br/>- Transient data<br/>- No persistence between restarts"]

    Note3["✅ Audit Trail via Events<br/>- Kafka: AdminDashboardViewed, AdminFlagQueried<br/>- ELK Stack: Structured logs<br/>- Jaeger: Distributed traces"]

    UpstreamServices["Upstream Services<br/>(source of truth)<br/>- Payment Service (PostgreSQL)<br/>- Reconciliation Service (PostgreSQL)<br/>- Flag Service (PostgreSQL)<br/>- Order Service (PostgreSQL)"]

    AdminGateway --> Note1
    AdminGateway --> Note2
    AdminGateway --> Note3
    AdminGateway --> UpstreamServices

    style AdminGateway fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style Note1 fill:#FF6B6B,color:#fff
    style Note2 fill:#7ED321,color:#000
    style Note3 fill:#9013FE,color:#fff
    style UpstreamServices fill:#52C41A,color:#fff
```

## Observability Pipeline

```mermaid
graph LR
    AdminGateway["Admin Gateway"]

    subgraph metrics["📊 Metrics Pipeline"]
        MPrometheus["Prometheus<br/>9090"]
        MGrafana["Grafana<br/>3000"]
        MAlerting["Alertmanager<br/>Alerts"]
    end

    subgraph logs["📝 Logging Pipeline"]
        LElasticsearch["Elasticsearch"]
        LKibana["Kibana<br/>Visualization"]
        LAlerts["Log-based Alerts"]
    end

    subgraph traces["📈 Tracing Pipeline"]
        TJaeger["Jaeger<br/>Query UI"]
        TCollection["Jaeger Collector"]
    end

    subgraph events["📬 Event Pipeline"]
        EKafka["Kafka<br/>Event Stream"]
        EConsumer["Event Consumers<br/>(audit, compliance)"]
    end

    AdminGateway -->|Micrometer| MPrometheus
    AdminGateway -->|Structured Logs| LElasticsearch
    AdminGateway -->|OpenTelemetry| TCollection

    MPrometheus --> MGrafana
    MPrometheus --> MAlerting

    LElasticsearch --> LKibana
    LElasticsearch --> LAlerts

    TCollection --> TJaeger

    AdminGateway -->|Events| EKafka
    EKafka --> EConsumer

    style AdminGateway fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style MPrometheus fill:#9013FE,color:#fff
    style MGrafana fill:#9013FE,color:#fff
    style MAlerting fill:#FF6B6B,color:#fff
    style LElasticsearch fill:#F5A623,color:#000
    style TJaeger fill:#52C41A,color:#fff
    style EKafka fill:#50E3C2,color:#000
```
