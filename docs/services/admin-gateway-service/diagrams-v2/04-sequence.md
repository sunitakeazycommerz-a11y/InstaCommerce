# Admin Gateway - Sequence Diagrams

## Complete JWT Authentication Sequence

```mermaid
sequenceDiagram
    participant Client as Client<br/>(Browser)
    participant ALB as AWS ALB<br/>(TLS)
    participant Gateway as Admin Gateway
    participant Auth as AdminJwtAuthenticationFilter
    participant Identity as Identity Service
    participant JWKS as JWKS Cache<br/>(Redis 5min TTL)
    participant SecurityCtx as SecurityContext
    participant Authz as RoleBasedAuthStrategy

    Client->>ALB: 1. HTTPS Request<br/>GET /api/admin/dashboard
    ALB->>Gateway: 2. Forward (decrypted)
    Gateway->>Auth: 3. doFilterInternal()

    Auth->>Auth: 4. Extract Authorization header<br/>(Bearer: eyJ...)

    alt No Bearer token
        Auth-->>Client: 401 Unauthorized
    end

    Auth->>Auth: 5. Extract JWT claims<br/>(header + payload)
    Auth->>Auth: 6. Get kid from JWT header

    Auth->>JWKS: 7. Lookup kid in JWKS cache
    alt Cache hit (within 5min)
        JWKS-->>Auth: 8a. Return cached JWKS entry
    else Cache miss or expired
        JWKS->>Identity: 8b. GET /.well-known/jwks.json
        Identity-->>JWKS: 8c. Return JWKS (keys array)
        JWKS->>JWKS: 8d. Cache for 5 min
        JWKS-->>Auth: 8e. Return JWKS entry
    end

    Auth->>Auth: 9. Get public key from JWKS entry

    Auth->>Auth: 10. Verify RS256 signature<br/>using public key

    alt Signature invalid
        Auth-->>Client: 401 Unauthorized
    end

    Auth->>Auth: 11. Extract payload claims:<br/>sub, aud, exp, roles

    Auth->>Auth: 12. Validate aud == instacommerce-admin
    alt Wrong audience
        Auth-->>Client: 403 Forbidden<br/>(Wrong audience)
    end

    Auth->>Auth: 13. Check exp < now()
    alt Token expired
        Auth-->>Client: 401 Unauthorized<br/>(Token expired)
    end

    Auth->>Auth: 14. Extract roles array<br/>Check for ROLE_ADMIN

    alt No ADMIN role
        Auth-->>Client: 403 Forbidden<br/>(No ADMIN role)
    end

    Auth->>SecurityCtx: 15. Set principal + authorities<br/>in SecurityContext

    Auth->>Auth: 16. Log: JWT_VALIDATED_SUCCESS<br/>(user_id, roles)

    Gateway->>Authz: 17. Check authorization<br/>RoleBasedAuthStrategy

    Authz->>SecurityCtx: 18. Get authorities<br/>from context

    Authz->>Authz: 19. Verify endpoint<br/>requires ROLE_ADMIN

    alt Authorization passed
        Authz-->>Gateway: 20. Continue
    else Authorization failed
        Authz-->>Client: 403 Forbidden
    end

    Note over Auth,Authz: Subtotal auth overhead: ~29ms<br/>(JWT extraction, validation, extraction: ~27ms<br/>JWKS cache lookup & validation: ~2ms)
```

## Dashboard Query Sequence (Full Request)

```mermaid
sequenceDiagram
    participant Client as Client
    participant Gateway as Admin Gateway
    participant Cache as Redis Cache<br/>(5min TTL)
    participant Payment as Payment Service<br/>(gRPC)
    participant Fulfillment as Fulfillment Service<br/>(gRPC)
    participant Order as Order Service<br/>(gRPC)
    participant Aggregator as Response Aggregator
    participant ResponseBuilder as ResponseBuilder

    Client->>Gateway: 1. GET /api/admin/dashboard
    Note over Gateway: Auth overhead: ~29ms

    Gateway->>Cache: 2. GET dashboard_summary
    alt Cache HIT (within 5min)
        Cache-->>Gateway: 3a. Return cached DashboardDTO
        Gateway-->>Client: 3b. Return 200 OK<br/>(from cache, 10ms total)
    else Cache MISS
        Gateway->>Gateway: 4. Launch 3 parallel requests<br/>(timeout: 300ms each)

        Gateway->>Payment: 5a. gRPC: GetPaymentStats()
        Gateway->>Fulfillment: 5b. gRPC: GetFulfillmentMetrics()
        Gateway->>Order: 5c. gRPC: GetOrderStats()

        par Parallel Service Calls
            Payment->>Payment: Calculate payment SLO<br/>from last 24h txns
            Payment-->>Gateway: 6a. {total_revenue,<br/>transaction_count, error_rate}
        and
            Fulfillment->>Fulfillment: Aggregate fulfillment<br/>metrics
            Fulfillment-->>Gateway: 6b. {avg_latency_ms,<br/>success_rate}
        and
            Order->>Order: Calculate order stats
            Order-->>Gateway: 6c. {p99_latency_ms,<br/>processed_count}
        end

        Note over Gateway: Service call latency: ~200-300ms (p99)

        Gateway->>Aggregator: 7. Merge 3 responses
        Aggregator->>Aggregator: 8. Calculate combined metrics:<br/>- Payment SLO %<br/>- Fulfillment p99<br/>- Order p50

        Aggregator->>ResponseBuilder: 9. Build DashboardDTO
        ResponseBuilder->>Cache: 10. SET dashboard_summary<br/>(TTL: 5 min)
        Cache-->>ResponseBuilder: 11. Cached

        ResponseBuilder-->>Gateway: 12. DashboardDTO

        Gateway-->>Client: 13. Return 200 OK<br/>with dashboard data
    end

    Note over Client: Total latency (cache miss): ~200-330ms p99<br/>Total latency (cache hit): ~10ms (within SLO <500ms)
```

## Feature Flags Query Sequence

```mermaid
sequenceDiagram
    participant Client as Client
    participant Gateway as Admin Gateway
    participant FlagCache as Redis Cache<br/>(30s TTL)
    participant FlagService as Feature Flag Service<br/>(REST)

    Client->>Gateway: 1. GET /api/admin/flags
    Note over Gateway: Auth: ~29ms

    Gateway->>FlagCache: 2. GET admin:flags:active

    alt Cache HIT (within 30s)
        FlagCache-->>Gateway: 3a. Return cached flags<br/>with ETag
        Gateway->>Gateway: 3b. Add header<br/>X-Cache: HIT, X-Cache-Age: 5s
        Gateway-->>Client: 3c. Return 200 OK<br/>(cached, ~10ms)
    else Cache MISS
        Gateway->>FlagService: 4. GET /flags?status=active
        Note over Gateway,FlagService: Flag Service latency: ~50-100ms
        FlagService-->>Gateway: 5. Return flags array<br/>[{name, enabled, rollout%}]

        Gateway->>Gateway: 6. Transform to FeatureFlagDTO
        Gateway->>Gateway: 7. Filter admin-queryable flags only
        Gateway->>Gateway: 8. Sort by name ASC

        Gateway->>FlagCache: 9. SET admin:flags:active<br/>(TTL: 30s)
        FlagCache-->>Gateway: 10. Cached

        Gateway->>Gateway: 11. Build response<br/>with metadata
        Gateway-->>Client: 12. Return 200 OK<br/>with flags list<br/>(~80ms)
    end
```

## Reconciliation Runs Query Sequence

```mermaid
sequenceDiagram
    participant Client as Client
    participant Gateway as Admin Gateway
    participant ReconcileCache as Redis Cache<br/>(10min TTL)
    participant ReconcileService as Reconciliation Service<br/>(gRPC)

    Client->>Gateway: 1. GET /api/admin/reconciliation<br/>?days=7&page=1
    Note over Gateway: Auth: ~29ms

    Gateway->>Gateway: 2. Validate params:<br/>days in 1-90, page >= 1

    Gateway->>Gateway: 3. Calculate date range<br/>from_date = now - 7 days

    Gateway->>ReconcileCache: 4. GET reconciliation:runs:7d:p1

    alt Cache HIT (within 10min)
        ReconcileCache-->>Gateway: 5a. Return cached runs list
        Gateway-->>Client: 5b. Return 200 OK<br/>(cached, ~10ms)
    else Cache MISS
        Gateway->>ReconcileService: 6. gRPC ListReconciliationRuns<br/>(from_date, to_date,<br/>page=1, limit=20)
        Note over Gateway,ReconcileService: Reconciliation Service: ~250-300ms p99

        ReconcileService->>ReconcileService: 7. Query PostgreSQL ledger<br/>WHERE timestamp BETWEEN...
        ReconcileService-->>Gateway: 8. Return runs list<br/>[{run_id, status, mismatch_count,<br/>duration_ms, timestamp}]

        Gateway->>Gateway: 9. Process response:<br/>- Filter by status<br/>- Calculate metrics:<br/>  * success_rate = successful/total<br/>  * avg_duration_ms = SUM(duration)/count<br/>  * auto_fix_rate = fixed/mismatches

        Gateway->>Gateway: 10. Build pagination info:<br/>- total_count<br/>- current_page<br/>- has_next = (page * limit) < total

        Gateway->>Gateway: 11. Sort by timestamp DESC
        Gateway->>Gateway: 12. Add metadata:<br/>- cached: false<br/>- retrieved_at: now

        Gateway->>ReconcileCache: 13. SET reconciliation:runs:7d:p1<br/>(TTL: 10 min)
        ReconcileCache-->>Gateway: 14. Cached

        Gateway-->>Client: 15. Return 200 OK<br/>with runs list, pagination, metrics<br/>(~280ms)
    end
```

## Payment Status Query Sequence

```mermaid
sequenceDiagram
    participant Client as Client
    participant Gateway as Admin Gateway
    participant PaymentCache as Redis Cache<br/>(5min TTL)
    participant PaymentService as Payment Service<br/>(gRPC)
    participant PaymentDB as PostgreSQL<br/>(payment_ledger)

    Client->>Gateway: 1. GET /api/admin/payments<br/>?status=completed
    Note over Gateway: Auth: ~29ms

    Gateway->>Gateway: 2. Validate status param<br/>allowed: [completed, failed,<br/>pending, all]

    Gateway->>PaymentCache: 3. GET admin:payments:completed

    alt Cache HIT (within 5min)
        PaymentCache-->>Gateway: 4a. Return cached stats
        Gateway-->>Client: 4b. Return 200 OK<br/>(cached, ~10ms)
    else Cache MISS
        Gateway->>PaymentService: 5. gRPC GetPaymentStats<br/>(status=completed, limit=50)
        Note over Gateway,PaymentService: Payment Service latency: ~150-200ms p99

        PaymentService->>PaymentDB: 6. SELECT * FROM payment_ledger<br/>WHERE status=completed<br/>ORDER BY created_at DESC<br/>LIMIT 50

        PaymentDB-->>PaymentService: 7. Return payment records

        PaymentService->>PaymentService: 8. Aggregate response:<br/>- SUM(amount) = total_revenue<br/>- COUNT(*) = transaction_count<br/>- COUNT(error)/count = error_rate %<br/>- AVG(latency_ms) = avg_latency<br/>- PERCENTILE_CONT(0.99) = p99

        PaymentService->>PaymentService: 9. Categorize by method:<br/>- card: 60%<br/>- upi: 30%<br/>- wallet: 10%

        PaymentService-->>Gateway: 10. Return PaymentStatsDTO

        Gateway->>Gateway: 11. Add time-series data<br/>(last 24h by hour):
        Gateway->>Gateway: [
        Gateway->>Gateway: {hour: 12, revenue: 10000, count: 150},
        Gateway->>Gateway: {hour: 13, revenue: 12000, count: 180},
        Gateway->>Gateway: ...
        Gateway->>Gateway: ]

        Gateway->>PaymentCache: 12. SET admin:payments:completed<br/>(TTL: 5 min)
        PaymentCache-->>Gateway: 13. Cached

        Gateway-->>Client: 14. Return 200 OK<br/>with stats, breakdown, time-series<br/>(~180ms)
    end
```
