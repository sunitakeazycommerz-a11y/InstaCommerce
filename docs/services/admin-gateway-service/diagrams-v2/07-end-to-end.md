# Admin Gateway - End-to-End Diagram

## Complete Dashboard Query Flow

```mermaid
graph TB
    AdminUser["👨‍💼 Admin User<br/>(Browser)"]
    Browser["🌐 Browser"]
    CloudFront["🌍 CloudFront CDN"]
    ALB["⚖️ AWS ALB<br/>(SSL/TLS termination)"]
    AdminGW["🔐 Admin Gateway<br/>(Java Spring Boot)"]
    RateLimit["⏱️ Rate Limiter<br/>(Redis)"]
    AuthFilter["🔑 JWT Auth Filter"]
    IdentityService["🆔 Identity Service<br/>/.well-known/jwks.json"]
    PaymentSvc["💳 Payment Service<br/>/stats/summary"]
    FlagSvc["🚩 Flag Service<br/>/flags?status=active"]
    ReconcileSvc["⚖️ Reconciliation Service<br/>/reconciliation/runs"]
    ResponseCache["⚡ Redis Cache<br/>(Dashboard response)"]
    ResponseBuilder["📦 Response Builder"]

    AdminUser -->|1. Click Dashboard| Browser
    Browser -->|2. GET /api/admin/dashboard| CloudFront
    CloudFront -->|3. Cache miss, forward| ALB
    ALB -->|4. HTTPS connection| AdminGW
    AdminGW -->|5. Check rate limit| RateLimit
    RateLimit -->|6. Allowed (< 100 req/min)| AuthFilter
    AuthFilter -->|7. Extract JWT from header| AuthFilter
    AuthFilter -->|8. Verify signature against JWKS| IdentityService
    IdentityService -->|9. Return public keys| AuthFilter
    AuthFilter -->|10. Check aud & roles| AuthFilter
    AdminGW -->|11. Check cache| ResponseCache
    ResponseCache -->|12. Cache miss| AdminGW
    AdminGW -->|13. Query in parallel| PaymentSvc
    AdminGW -->|14. Query in parallel| FlagSvc
    AdminGW -->|15. Query in parallel| ReconcileSvc
    PaymentSvc -->|16. Return stats| AdminGW
    FlagSvc -->|17. Return flags| AdminGW
    ReconcileSvc -->|18. Return runs| AdminGW
    AdminGW -->|19. Aggregate responses| ResponseBuilder
    ResponseBuilder -->|20. Build JSON| ResponseBuilder
    ResponseBuilder -->|21. Cache for 5min| ResponseCache
    AdminGW -->|22. Send response| ALB
    ALB -->|23. HTTPS response| CloudFront
    CloudFront -->|24. Cache HTML/JS| Browser
    Browser -->|25. Render dashboard| AdminUser

    style AdminUser fill:#4A90E2,color:#fff
    style AuthFilter fill:#FF6B6B,color:#fff
    style PaymentSvc fill:#7ED321,color:#000
    style ResponseCache fill:#F5A623,color:#000

    classDef parallelCall fill:#50E3C2,color:#000
    class 13,14,15 parallelCall
```

## Multi-Tenant Request Flow with Audit

```mermaid
graph TB
    Request["Incoming Request<br/>GET /api/admin/dashboard"]
    ValidateJWT["Validate JWT<br/>(RS256 signature)"]
    ExtractTenant["Extract tenant_id from JWT claims"]
    RowLevelSecurity["Apply Row-Level Security<br/>(Filter by tenant_id)"]
    QueryServices["Query Downstream Services<br/>(with tenant context)"]
    AuditLog["Audit Log Entry<br/>admin_user_id, action, resource"]
    PublishKafkaEvent["Publish to Kafka<br/>AdminDashboardViewed event"]
    BuildResponse["Build Response<br/>(tenant-specific data)"]
    SendResponse["Send 200 OK"]

    Request --> ValidateJWT
    ValidateJWT --> ExtractTenant
    ExtractTenant --> RowLevelSecurity
    RowLevelSecurity --> QueryServices
    QueryServices --> AuditLog
    AuditLog --> PublishKafkaEvent
    PublishKafkaEvent --> BuildResponse
    BuildResponse --> SendResponse

    style ValidateJWT fill:#4A90E2,color:#fff
    style RowLevelSecurity fill:#FF6B6B,color:#fff
    style AuditLog fill:#F5A623,color:#000
```

## Resilience Patterns in Action

```mermaid
graph TB
    Request["Dashboard Request"]
    CircuitBreaker["🔌 Circuit Breaker<br/>(per downstream service)"]
    ServiceStatus{{"Service<br/>Healthy?"}}
    DirectCall["Direct Call<br/>to Service"]
    CachedResponse["📦 Fallback to Cache<br/>(stale data OK)"]
    DefaultResponse["🟢 Default Response<br/>(empty or partial)"]
    Response["HTTP Response"]

    Request --> CircuitBreaker
    CircuitBreaker --> ServiceStatus
    ServiceStatus -->|Yes| DirectCall
    ServiceStatus -->|No| CachedResponse
    CachedResponse --> Response
    DirectCall --> Response
    CachedResponse -.->|No cache| DefaultResponse
    DefaultResponse --> Response

    style CircuitBreaker fill:#FF6B6B,color:#fff
    style DirectCall fill:#52C41A,color:#fff
    style CachedResponse fill:#F5A623,color:#000
    style Response fill:#7ED321,color:#000
```

## SLA & Monitoring

```mermaid
graph LR
    AdminGateway["Admin Gateway"]

    LatencyTarget["📊 Latency SLA<br/>p99 < 500ms<br/>p99.9 < 1s"]
    AvailabilityTarget["✅ Availability<br/>99.9%<br/>(< 43 sec/day downtime)"]
    ErrorBudget["💰 Error Budget<br/>0.1% error rate<br/>= 8.6 sec/day allowance"]

    AdminGateway --> LatencyTarget
    AdminGateway --> AvailabilityTarget
    AdminGateway --> ErrorBudget

    Alerts["🚨 Alerts"]
    Dashboard["📈 Grafana Dashboard"]
    Pagerduty["🔔 PagerDuty"]

    LatencyTarget -->|p99 > 500ms| Alerts
    AvailabilityTarget -->|Down > 43s/day| Alerts
    ErrorBudget -->|Errors > 0.1%| Alerts

    Alerts --> Dashboard
    Alerts --> Pagerduty

    style AdminGateway fill:#4A90E2,color:#fff
    style LatencyTarget fill:#7ED321,color:#000
    style Alerts fill:#FF6B6B,color:#fff
```
