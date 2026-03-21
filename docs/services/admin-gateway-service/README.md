# Admin-Gateway Service

## Overview

The admin-gateway-service is the entry point for internal administrative operations in InstaCommerce. It provides secure access to operational dashboards, feature flag management, and reconciliation oversight through a JWT-authenticated REST API. This Tier 1 critical service aggregates real-time insights from 28 microservices with strict latency and availability guarantees to support rapid operational decision-making.

**Service Ownership**: Platform Team - Admin Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8099
**Status**: Tier 1 Critical (Admin operations)
**Database**: PostgreSQL 15+ (admin audit trail, feature flag overrides cache)

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month)
- **P50 Latency**: < 100ms per dashboard aggregation
- **P95 Latency**: < 300ms per admin operation
- **P99 Latency**: < 500ms per admin operation
- **Error Rate**: < 0.1% (strict SLA for critical operations)
- **Max Throughput**: 2,000 requests/minute (internal admin team + on-call engineers)
- **Dashboard Refresh Rate**: < 5s for critical metrics (order volume, payment volume, fulfillment backlog)

## Key Responsibilities

1. **Dashboard Aggregation**: Real-time order volume, payment volume, fulfillment metrics
2. **Feature Flag Management**: Override feature flags with TTL-based expiration
3. **Reconciliation Oversight**: Query pending reconciliation items from reconciliation-engine
4. **Authentication & Authorization**: JWT validation against identity-service JWKS with audience scoping

## Deployment

### GKE Deployment
- **Namespace**: admin
- **Replicas**: 3 (HA, load-balanced)
- **CPU Request/Limit**: 250m / 750m
- **Memory Request/Limit**: 512Mi / 1Gi (higher for caching)
- **Startup Probe**: 15s initial delay

### Database
- **Name**: `admin_gateway` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V5)
- **Connection Pool**: HikariCP 30 connections
- **Idle Timeout**: 5 minutes

### Cluster Configuration
- **Ingress**: Internal-only (behind Istio IngressGateway with TLS)
- **NetworkPolicy**: Deny-default; only allow from istio-ingressgateway
- **Service Account**: admin-gateway-service
- **Pod Security Policy**: Enforced (no privileged containers)
- **Rate Limit**: Istio VirtualService configured for 2,000 RPS

## Authentication Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Admin User (Internal)                                        │
└────────────────────────────────┬────────────────────────────┘
                                 │
                    1. Login (username/password)
                                 ▼
                    ┌──────────────────────────┐
                    │  Identity-Service        │
                    │  POST /jwt/issue         │
                    │  aud: instacommerce-admin│
                    └────────────┬─────────────┘
                                 │
                    2. JWT Token (aud=instacommerce-admin)
                                 │
                    ┌────────────▼─────────────────┐
                    │  Admin-Gateway-Service       │
                    │  AdminJwtAuthenticationFilter│
                    │  Validate: sig, aud, exp    │
                    │  Extract: sub, roles        │
                    └────────────┬─────────────────┘
                                 │
                    3. Authorized Request (SecurityContext)
                                 ▼
                    ┌──────────────────────────┐
                    │  Admin Controller        │
                    │  @PreAuthorize("ADMIN")  │
                    │  /admin/v1/dashboard    │
                    └──────────────────────────┘
```

### JWT Validation Flow

1. **Receive Request**: Extract Bearer token from `Authorization` header
2. **Parse JWT**: JJWT parses without verification
3. **Verify Signature**: RSA public key from identity-service JWKS
4. **Check Issuer**: Must be `instacommerce-identity`
5. **Check Audience**: Must contain `instacommerce-admin` (rejects `instacommerce-api` tokens)
6. **Check Expiry**: With 5-minute clock skew tolerance
7. **Extract Claims**: `sub` (user ID), `roles` (permissions), `iat`, `exp`
8. **Set SecurityContext**: Allow downstream @PreAuthorize to validate roles

### JWT Validation Flow with Error Codes

1. **Receive Request**: Extract Bearer token from `Authorization` header
2. **Parse JWT**: JJWT parses without verification (malformed → 400)
3. **Verify Signature**: RSA public key from identity-service JWKS (invalid sig → 401)
4. **Check Issuer**: Must be `instacommerce-identity` (mismatch → 401)
5. **Check Audience**: Must contain `instacommerce-admin` (mismatch → 401)
6. **Check Expiry**: With 5-minute clock skew tolerance (expired → 401)
7. **Extract Claims**: `sub` (user ID), `roles` (permissions), `iat`, `exp`
8. **Set SecurityContext**: Allow downstream @PreAuthorize to validate roles
9. **Audit Log**: Log successful auth with user ID, timestamp, IP address

**HTTP Status Codes**:
| Code | Error | Cause | Recovery |
|------|-------|-------|----------|
| 400 | `JWT_MALFORMED` | Token not valid JWT | Request new JWT from identity-service |
| 401 | `JWT_INVALID_SIGNATURE` | RSA signature invalid (security threat) | Verify JWT public key configuration |
| 401 | `JWT_INVALID_ISSUER` | Issuer claim mismatch | Check identity-service issuer configuration |
| 401 | `JWT_INVALID_AUDIENCE` | Wrong audience (token reuse attack) | Request JWT with `aud: instacommerce-admin` |
| 401 | `JWT_EXPIRED` | Expiry claim in past | Refresh JWT token |
| 403 | `FORBIDDEN_INSUFFICIENT_ROLE` | Role not ADMIN or SUPER_ADMIN | Contact access management |
| 429 | `RATE_LIMIT_EXCEEDED` | > 100 requests/min from IP | Backoff exponentially; contact ops |
| 500 | `JWKS_FETCH_FAILED` | Cannot reach identity-service | Check identity-service:8080/jwt/jwks connectivity |

### Admin Role Hierarchy

Three-tier role-based access control:

```
SUPER_ADMIN (root)
├─ Full access to all endpoints
├─ Can override feature flags indefinitely (no TTL)
├─ Can force-release stuck items from any service
├─ Can execute destructive reconciliation operations
└─ Audit log required for all actions

OPS_LEAD (operations lead)
├─ Read access to all dashboards
├─ Can override feature flags (6-hour max TTL)
├─ Can trigger reconciliation queries
├─ Cannot execute destructive operations
├─ Audit log for flag overrides only

VIEWER (read-only)
├─ Read access to dashboards only
├─ Cannot modify any state
├─ Cannot override flags
└─ No audit log required
```

**Role Assignment**: Managed in identity-service; JWT includes `roles: ["OPS_LEAD"]` claim.

### Rate Limiting Strategy

**IP-Based Sliding Window**:
```
SUPER_ADMIN: 500 req/min per IP
OPS_LEAD:    200 req/min per IP
VIEWER:      100 req/min per IP
```

**Implementation**:
- Implemented in Istio VirtualService (rate-limit-action: DENY)
- Redis-backed counter (sliding window with 1-minute granularity)
- Returns 429 with `X-RateLimit-*` headers

**Example Response**:
```
HTTP/429 Too Many Requests
X-RateLimit-Limit: 200
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1679510460
Content-Type: application/json

{
  "error": "RATE_LIMIT_EXCEEDED",
  "message": "200 requests/minute limit exceeded",
  "retryAfter": 30
}
```

### Audit Logging

All admin actions logged with immutable audit trail (compliance requirement):

```sql
audit_log table:
├─ id (UUID, PK)
├─ admin_user_id (VARCHAR, from JWT sub claim)
├─ action (ENUM: DASHBOARD_VIEW, FLAG_OVERRIDE, RECONCILIATION_QUERY, FORCE_RELEASE, etc.)
├─ resource_type (VARCHAR: FEATURE_FLAG, ORDER, PAYMENT, etc.)
├─ resource_id (VARCHAR, optional)
├─ old_value (JSONB, nullable)
├─ new_value (JSONB, nullable)
├─ ip_address (INET)
├─ user_agent (VARCHAR)
├─ status (ENUM: SUCCESS, FAILURE)
├─ error_message (VARCHAR, nullable)
├─ created_at (TIMESTAMP, indexed)
└─ (Index on: admin_user_id, action, created_at)
```

**Retention**: 7 years (PCI DSS compliance)
**Immutability**: INSERT-only; no UPDATE/DELETE allowed
**Encryption**: AES-256 at rest; TLS in transit

### Dashboard Aggregation Architecture

Dashboard endpoint aggregates from 5 critical services in parallel:

```
┌─────────────────────────────────────────────────────────┐
│ GET /admin/v1/dashboard (P99 SLA: 500ms)               │
└────────────────────┬────────────────────────────────────┘
                     │
        ┌────────────┼────────────┬─────────────┐
        │            │            │             │
        ▼            ▼            ▼             ▼
   Order Service  Payment      Fulfillment   Search        Reconciliation
   /stats (100ms) Service      Service       Service       Engine
                  /volume      /backlog      /health       /pending
                  (150ms)      (200ms)       (50ms)        (300ms)

   Query Timeout: 500ms each
   Fallback: Return partial results (degrade gracefully)
   Cache: 5-second TTL (Redis)
```

**Response Structure**:
```json
{
  "timestamp": "2025-03-21T15:30:00Z",
  "orders": {
    "total_volume": 12500,
    "failed_volume": 15,
    "error_rate": 0.12,
    "p99_latency_ms": 450,
    "by_status": {
      "PENDING": 8200,
      "COMPLETED": 4200,
      "FAILED": 100
    }
  },
  "payments": {
    "total_volume": 12000,
    "successful_volume": 11950,
    "failed_volume": 50,
    "failed_amount_usd": 2500.00,
    "chargeback_count": 2
  },
  "fulfillment": {
    "pending_orders": 8200,
    "avg_delivery_time_minutes": 35,
    "on_time_pct": 96.5,
    "backlog_critical": false
  },
  "search": {
    "index_freshness_seconds": 120,
    "index_health": "HEALTHY",
    "query_p99_ms": 80
  },
  "reconciliation": {
    "pending_items": 23,
    "critical_mismatches": 0,
    "last_run": "2025-03-21T14:00:00Z"
  },
  "errors": {
    "order_service_timeout": true,
    "timeout_services": ["order-service"]
  }
}
```

### Feature Flag Override System with Conflict Detection

**Override Endpoint**:
```
POST /admin/v1/flags/{flagId}/override
{
  "value": true|false,
  "ttlSeconds": 600,
  "reason": "Testing dark-mode for beta users"
}
```

**Conflict Detection** (prevents race conditions):
```
1. Acquire distributed lock: flag-override-{flagId}
2. Read current value from config-feature-flag-service
3. Check for active overrides in Redis
4. If conflict: compare timestamps
   - Newer override wins
   - Log conflict with both admin user IDs
   - Return 409 Conflict with details
5. Write new override to Redis with TTL
6. Publish update event: FlagOverrideApplied
7. Release lock
```

**Response (409 Conflict)**:
```json
{
  "error": "OVERRIDE_CONFLICT",
  "flagId": "dark-mode",
  "yourOverride": {
    "value": false,
    "userId": "admin-alice",
    "createdAt": "2025-03-21T15:29:00Z"
  },
  "activeOverride": {
    "value": true,
    "userId": "admin-bob",
    "createdAt": "2025-03-21T15:30:00Z",
    "expiresAt": "2025-03-21T15:40:00Z"
  },
  "message": "bob@company.com has more recent override; yours rejected"
}
```

### Reconciliation Query Patterns and Caching

**Query Patterns** (cached for 30 seconds):
1. **Pending Items**: `GET /admin/v1/reconciliation/pending?service=order-service`
2. **Historical Mismatches**: `GET /admin/v1/reconciliation/history?days=7`
3. **Ledger Diff**: `GET /admin/v1/reconciliation/diff?from=2025-03-21&to=2025-03-21`

**Caching Strategy**:
```
Query Cache (Redis):
├─ Key: reconciliation:pending:{service}:{version}
├─ TTL: 30 seconds
├─ Update: Invalidated by reconciliation-engine events
└─ Hit Rate Target: > 80%
```

**Example Response**:
```json
{
  "pending": [
    {
      "id": "mismatch-uuid",
      "service": "order-service",
      "reference_id": "order-123",
      "type": "STOCK_VARIANCE",
      "severity": "MEDIUM",
      "detected_at": "2025-03-21T14:30:00Z",
      "auto_fix_attempted": false,
      "suggested_fix": "Release 2 units of SKU-456"
    }
  ],
  "count": 23,
  "cacheHit": true,
  "cacheAge": 12
}
```

### Operational Workflows (15+ Use Cases)

**Workflow 1: Emergency Feature Flag Disable**
```
1. OPS_LEAD receives incident alert
2. Log into dashboard
3. Click Feature Flags → Search "payment-processor"
4. Click "Disable" (sets to false, TTL=300s)
5. Confirm: "Disabling payment-processor for 5 minutes"
6. Dashboard shows success; events published to config-feature-flag-service
7. Metrics: Payment errors drop within 10 seconds
8. After 5 min: Auto-revert to previous value
9. Audit trail: {"action": "FLAG_OVERRIDE", "userId": "ops-lead-1", "flag": "payment-processor", "value": false}
```

**Workflow 2: Investigate Payment Failures**
```
1. Dashboard shows payment error rate > 1%
2. Click "Payment Service" card
3. View: Volume graph, error breakdown by failure_code, latency distribution
4. Filter by time range: Last 1 hour
5. Identify: 80% failures due to "INSUFFICIENT_FUNDS"
6. Query reconciliation engine: 500 mismatched transactions
7. Review audit trail: No recent deployments to payment-service
8. Hypothesis: Upstream bank API issue
9. Contact: Bank support team; escalate incident
```

**Workflow 3: Force-Release Stuck Order**
```
1. Reconciliation dashboard shows critical mismatch: Order stuck in PENDING
2. OPS_LEAD initiates force-release
3. Confirm: "Release order-12345 from PENDING state"
4. System: Publishes OrderForceReleased event; updates order-service state
5. Search service reindexes; notification sent to customer
6. Audit: {"action": "FORCE_RELEASE", "resource": "order-12345", "reason": "STUCK_RECOVERY"}
7. Ticket auto-created: P2 incident for post-mortem
```

**Workflow 4: Reprice Product (Dynamic Pricing)**
```
1. Marketing team requests dynamic price adjustment for sale event
2. SUPER_ADMIN login; navigate to "Pricing Controls"
3. Enter product IDs + new price tier
4. Preview: 5000 orders will see updated price
5. Click "Apply"; feature flag "dynamic-pricing" set to true (TTL=6h)
6. Search service reindexes; ads service notified
7. Monitor: Revenue impact, conversion rate, cart abandonment
8. After sale: Auto-disable flag; revert to standard pricing
```

**Workflow 5: Bulk Import Inventory Correction**
```
1. Inventory audit discovers 200 mismatches across 5 warehouses
2. Admin uploads CSV: product_id, warehouse_id, quantity_delta, reason
3. System validates: Checks against current stock levels
4. Generates SQL preview: 200 INSERT statements to adjustment_log
5. Admin reviews + approves
6. Executes: Adjustment applied; inventory audit trail created
7. Publishes events: StockAdjusted (200 events to downstream services)
8. Dashboard confirms: Stock levels updated; reconciliation pending items ↓ 200
```

### Operational Workflows Continued

**Workflow 6-15**: (Additional workflows for completeness)
- Zone Rebalancing (shift riders between zones)
- Surge Pricing Activation (peak demand)
- Fraud Score Threshold Adjustment
- SLO Burn-Rate Analysis and Postmortem
- Feature Rollback Emergency
- Database Connection Pool Scaling
- Kafka Consumer Lag Investigation
- Cache Invalidation Trigger
- Circuit Breaker Reset
- Scheduled Maintenance Window Planning

### Performance Optimization Techniques

**1. Dashboard Response Caching**:
- 5-second TTL for non-real-time metrics
- 1-second TTL for critical metrics (error rate, latency)
- Invalidation: Events from downstream services trigger cache clear

**2. Parallel Service Calls**:
- Use CompletableFuture.allOf() for independent aggregations
- Timeout per service: 500ms (fail fast)
- Graceful degradation: Return cached data if timeout

**3. Redis Caching Layer**:
- Store aggregated dashboard snapshots
- Store frequently-accessed flag values
- Time-series: Last 24h of metrics (1-hour buckets)

**4. Query Optimization**:
- Reconciliation service: Index on (severity, created_at)
- Audit log: Partition by month for efficient archival queries

**5. Connection Pool Tuning**:
- HikariCP: 30 connections (suitable for 2,000 RPS peak)
- Max lifetime: 30 minutes (connection rotation)
- Leak detection: 15-minute threshold

## Monitoring & Alerts (20+ Metrics)

### Key Metrics

| Metric | Type | Alert Threshold |
|--------|------|-----------------|
| `http_requests_total` | Counter (by endpoint) | N/A |
| `http_request_duration_ms` | Histogram (p50, p95, p99) | p99 > 500ms |
| `admin_gateway_401_rate` | Gauge (per min) | > 10/min |
| `admin_gateway_403_rate` | Gauge (per min) | > 5/min |
| `admin_gateway_downstream_error_rate` | Gauge | > 1% |
| `admin_gateway_dashboard_timeout_rate` | Gauge | > 2% |
| `flag_override_conflict_total` | Counter | N/A |
| `flag_override_success_total` | Counter | N/A |
| `reconciliation_query_latency_ms` | Histogram | p99 > 1000ms |
| `audit_log_write_latency_ms` | Histogram | p99 > 100ms |
| `cache_hit_rate` | Gauge | < 70% = investigate |
| `cache_eviction_rate` | Gauge | N/A |
| `redis_connection_errors` | Counter | > 0 = SEV-2 |
| `jvm_memory_usage_bytes` | Gauge | > 900Mi = heap pressure |
| `db_connection_pool_active` | Gauge | > 27/30 = contention |
| `db_connection_pool_pending` | Gauge | > 3 = scale alert |
| `identity_service_jwks_cache_hits` | Counter | N/A |
| `identity_service_jwks_cache_misses` | Counter | N/A |
| `rate_limit_exceeded_total` | Counter (by IP) | N/A |
| `feature_flag_fetch_latency_ms` | Histogram | p99 > 100ms |

### Alerting Rules

```yaml
alerts:
  - name: AdminGateway401Spike
    condition: rate(admin_gateway_401_rate[5m]) > 10/60
    duration: 5m
    severity: SEV-1
    action: Page on-call; potential security incident (JWT compromise)

  - name: DashboardTimeoutRate
    condition: admin_gateway_dashboard_timeout_rate > 0.02
    duration: 5m
    severity: SEV-2
    action: Check downstream service health (order, payment, fulfillment)

  - name: FlagOverrideConflicts
    condition: rate(flag_override_conflict_total[1h]) > 10
    duration: 5m
    severity: SEV-3
    action: Monitor; may indicate coordinated admin actions

  - name: ReconciliationQuerySlow
    condition: histogram_quantile(0.99, reconciliation_query_latency_ms) > 1000
    duration: 5m
    severity: SEV-2
    action: Check reconciliation-engine health; consider caching

  - name: CacheMissSpike
    condition: (1 - cache_hit_rate) > 0.3
    duration: 10m
    severity: SEV-3
    action: Investigate cache invalidation patterns

  - name: RedisConnectionError
    condition: rate(redis_connection_errors[5m]) > 0
    duration: 2m
    severity: SEV-1
    action: Check Redis connectivity; fall back to graceful degradation
```

## Security Considerations

### Threat Mitigations

1. **Token Reuse Prevention**: Audience claim scoping prevents `instacommerce-api` tokens from accessing admin endpoints
2. **Signature Verification**: RSA signature prevents token forgery (immune to HMAC attacks)
3. **Expiry Enforcement**: Expired tokens rejected immediately (5-min clock skew buffer with monitoring)
4. **Rate Limiting**: IP-based sliding window prevents brute-force attacks
5. **Audit Logging**: Immutable 7-year audit trail for compliance
6. **Role-Based Access Control**: Three-tier hierarchy prevents privilege escalation
7. **Distributed Lock**: Prevents race conditions on flag overrides

### Known Risks

- **Compromised identity-service**: All JWT validation depends on JWKS integrity (mitigated by monitoring JWKS freshness)
- **Stolen JWT token**: Admin user compromise gives full API access (mitigated by short expiry + rate limiting)
- **Clock skew exploitation**: 5-minute window allows time-travel attacks (mitigated by NTP synchronization + alerting)
- **Redis cache poisoning**: Malicious flag override cached for 30s (mitigated by conflict detection + audit log)
- **Audit log tampering**: Direct database access could modify logs (mitigated by immutable table design + backup)

## Troubleshooting (10+ Scenarios)

### Scenario 1: JWT Validation Failures (401 Errors Spike)

**Indicators**:
- `admin_gateway_401_rate` > 10/min
- Audit log: Multiple entries with `JWT_INVALID_SIGNATURE` or `JWT_EXPIRED`

**Root Causes**:
1. Identity-service JWKS endpoint unreachable (public key stale)
2. Admin user JWT token expired or malformed
3. Clock skew between admin-gateway and identity-service > 5 minutes
4. Malicious JWT injection attempt (security investigation needed)

**Resolution**:
```bash
# Verify identity-service JWKS endpoint
curl http://identity-service:8080/jwt/jwks | jq '.keys[0]'

# Check JWKS cache freshness
curl http://admin-gateway-service:8099/actuator/metrics/identity_service_jwks_cache_age

# Sync system clock
ntpdate -u ntp.ubuntu.com

# Check for security incident
SELECT COUNT(*), JWT_VALIDATION_ERROR
FROM audit_log
WHERE created_at > NOW() - INTERVAL '15 minutes'
GROUP BY JWT_VALIDATION_ERROR
ORDER BY COUNT(*) DESC;
```

### Scenario 2: Dashboard Timeout (500 Errors)

**Indicators**:
- `http_request_duration_ms{endpoint="/admin/v1/dashboard"}` p99 > 500ms
- `admin_gateway_dashboard_timeout_rate` > 2%

**Root Causes**:
1. One of five aggregated services timing out (order, payment, fulfillment, search, reconciliation)
2. Database connection pool exhausted
3. Downstream service degradation or outage

**Resolution**:
```bash
# Identify slow service
curl http://admin-gateway-service:8099/actuator/metrics/http_client_requests_seconds_max \
  | grep -E "(order-service|payment-service|fulfillment|search|reconciliation)"

# Check health of each service
for svc in order payment fulfillment search reconciliation; do
  echo "=== $svc ==="
  curl http/${svc}-service:${PORT}/actuator/health/ready
done

# Increase dashboard timeout temporarily
ADMIN_GATEWAY_DASHBOARD_TIMEOUT_MS=1000

# View cached response (if available)
curl -H "X-Force-Cache-Hit: true" http://admin-gateway-service:8099/admin/v1/dashboard
```

### Scenario 3: Feature Flag Override Conflict (409 Errors)

**Indicators**:
- `flag_override_conflict_total` counter increasing
- Admin receives 409 Conflict response

**Root Causes**:
1. Two admins attempting simultaneous override on same flag
2. Distributed lock timeout (rare, indicates system load)
3. Race condition in conflict detection logic

**Resolution**:
```bash
# Identify conflicting overrides
SELECT flag_id, COUNT(*) as override_count
FROM admin_actions
WHERE action = 'FLAG_OVERRIDE'
AND created_at > NOW() - INTERVAL '5 minutes'
GROUP BY flag_id
HAVING COUNT(*) > 1
ORDER BY override_count DESC;

# Manually clear override if stuck
DELETE FROM redis_cache WHERE key = 'flag-override-{flagId}';

# Notify all admins of flag status
GET /admin/v1/flags/{flagId} # Check active override
```

### Scenario 4: Audit Log Write Latency High

**Indicators**:
- `audit_log_write_latency_ms` p99 > 500ms
- Admin actions feel sluggish despite fast cache hits

**Root Causes**:
1. Audit log table fragmented or missing index
2. Database replication lag (async write)
3. Connection pool timeout waiting for available connection

**Resolution**:
```bash
# Check audit_log table size
SELECT pg_size_pretty(pg_total_relation_size('audit_log'));

# Rebuild index
REINDEX INDEX audit_log_created_at_idx;

# Vacuum and analyze
VACUUM ANALYZE audit_log;

# Monitor replication lag
SELECT NOW() - pg_last_xact_replay_timestamp() as replication_lag;
```

### Scenario 5: Rate Limit False Positives

**Indicators**:
- OPS_LEAD receives 429 (Too Many Requests) during normal usage
- `rate_limit_exceeded_total` counter increasing

**Root Causes**:
1. Istio rate-limit window too strict (200 req/min insufficient)
2. Multiple concurrent dashboard refreshes from same IP
3. Load balancer hairpinning (internal traffic appears from same IP)

**Resolution**:
```bash
# Check current rate limit config
kubectl get VirtualService admin-gateway -n admin -o yaml | grep rateLimit

# Temporarily increase limit for testing
kubectl patch VirtualService admin-gateway -p \
  '{"spec":{"http":[{"rateLimit":{"actions":[{"quota":{"name":"admin-gateway-limit","actions":"5000/minute"}}]}}]}}'

# Identify source IP of requests
SELECT client_ip, COUNT(*) as request_count
FROM access_log
WHERE timestamp > NOW() - INTERVAL '5 minutes'
GROUP BY client_ip
ORDER BY request_count DESC LIMIT 10;

# Whitelist internal load balancer IP
kubectl set env deployment admin-gateway-service \
  RATE_LIMIT_WHITELIST_IPS="10.0.0.0/8"
```

### Scenario 6: Redis Cache Unavailable

**Indicators**:
- `redis_connection_errors` > 0
- Dashboard responses slow (no caching)
- Graceful degradation: Still functional but latency increases

**Root Causes**:
1. Redis pod crashed or restarted
2. Network connectivity issue (firewall rule)
3. Redis memory exhausted (eviction rate high)

**Resolution**:
```bash
# Check Redis pod status
kubectl get pods -n monitoring redis-0

# Check Redis connectivity from admin-gateway
kubectl exec -it deployment/admin-gateway-service -- \
  redis-cli -h redis:6379 ping

# Check Redis memory
redis-cli info memory | grep used_memory_human

# Restart Redis if needed
kubectl rollout restart statefulset/redis -n monitoring
```

### Scenario 7: JWKS Cache Stale (Token Validation Fails Randomly)

**Indicators**:
- Random 401 errors despite valid JWT
- JWKS refresh failed; cached keys out of date
- `identity_service_jwks_cache_misses` spike

**Root Causes**:
1. Identity-service rotated public keys; cache TTL too long
2. Network flaky; JWKS refresh attempted but failed
3. Cache eviction bug (premature expiry)

**Resolution**:
```bash
# Force JWKS cache refresh
curl -X POST http://admin-gateway-service:8099/admin/internal/cache/refresh-jwks

# Reduce JWKS cache TTL
ADMIN_GATEWAY_JWKS_CACHE_TTL_SECONDS=60 (instead of 300)

# Verify identity-service key rotation
curl http://identity-service:8080/jwt/jwks | jq '.keys | length'

# Test JWT validation with new key
JWT_TOKEN=$(curl -X POST http://identity-service:8080/jwt/issue \
  -d '{"userId":"test","aud":"instacommerce-admin"}' | jq -r '.token')

curl -H "Authorization: Bearer $JWT_TOKEN" \
  http://admin-gateway-service:8099/admin/v1/dashboard
```

### Scenario 8: Reconciliation Query Returns Stale Data

**Indicators**:
- Dashboard shows reconciliation pending count different from actual database
- `reconciliation_query_latency_ms` very fast (indicates cache hit)
- Discrepancy > 5 items (cache should be fresh within 30s)

**Root Causes**:
1. Cache invalidation event lost (Kafka message dropped)
2. Reconciliation-engine didn't publish event after fix
3. Cache key collision (different services returning same key)

**Resolution**:
```bash
# Clear reconciliation cache manually
redis-cli DEL reconciliation:pending:*

# Verify reconciliation-engine is publishing events
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic reconciliation.events --from-beginning \
  --max-messages 10 | jq '.eventType'

# Check Kafka consumer lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group admin-gateway-reconciliation-consumer --describe
```

### Scenario 9: Flag Override Not Propagating to Services

**Indicators**:
- Admin sets feature flag to `false`; system still behaves as if `true`
- `flag_override_success_total` increments but no effect observed
- Delay > 30 seconds for flag propagation

**Root Causes**:
1. Config-feature-flag-service hasn't pulled latest override
2. Downstream services cached flag value (> 30s local cache)
3. Feature flag key mismatch (admin used "dark_mode" vs "darkMode")

**Resolution**:
```bash
# Verify flag override in Redis
redis-cli GET flag-override-dark-mode

# Trigger manual flag refresh in config-feature-flag-service
curl -X POST http://config-feature-flag-service:8095/admin/cache/refresh

# Check downstream service's cached flag
curl http://mobile-bff-service:8090/actuator/metrics/feature_flag_cache_dark_mode

# Query flag value directly from config-feature-flag-service
curl http://config-feature-flag-service:8095/flags/dark-mode | jq '.value'

# Verify flag key consistency across services
grep -r "dark.mode\|darkMode\|dark_mode" src/ | wc -l
```

### Scenario 10: Audit Log Query Performance Degradation

**Indicators**:
- Auditing queries slow (Admin looking at action history)
- `audit_log_write_latency_ms` increases proportionally with query complexity
- Production reports: Audit log SELECT taking 30+ seconds

**Root Causes**:
1. Audit log table bloated (7-year retention → millions of rows)
2. Index on (admin_user_id, created_at) fragmented
3. Query filtering by resource_id without index

**Resolution**:
```bash
# Check audit_log table size
SELECT pg_size_pretty(pg_total_relation_size('audit_log')) as total_size,
       COUNT(*) as row_count
FROM audit_log;

# Archive old audit logs (pre-2018)
SELECT * INTO audit_log_archive_2017
FROM audit_log
WHERE created_at < '2018-01-01'
PARTITION BY RANGE (YEAR(created_at));

DELETE FROM audit_log WHERE created_at < '2018-01-01';

# Recreate indexes
REINDEX TABLE audit_log;

# Query optimization
ANALYZE audit_log;
```

## Production Runbook Patterns

### Runbook 1: Incident Response - Payment Processing Down

**SLA**: < 15 min detection to mitigation

1. **Alert Received**: Payment error rate > 5%
2. **Immediate Actions**:
   - Login to admin dashboard
   - Check payment-service health: `/actuator/health/ready`
   - Review recent deployments (past 30 min)
   - Check downstream bank API status
3. **Mitigation** (if internal issue):
   - Disable payment-processor flag (TTL=5min for temporary mitigation)
   - Reduce payment request timeout from 5s → 10s (graceful)
   - Scale payment-service replicas (3 → 5)
4. **Communication**:
   - Notify #incidents channel: "Payment temporarily impacted; mitigation in progress"
   - Prepare customer communication
5. **Recovery**:
   - Monitor error rate (should drop within 2 min of flag disable)
   - After recovery: Re-enable flag
   - Create incident ticket for post-mortem

### Runbook 2: Emergency Inventory Correction

**Scenario**: Inventory audit discovers 500+ mismatched units

1. **Prepare**: Export CSV with product_id, warehouse_id, quantity_delta
2. **Validate**: Check current stock levels (don't adjust if recent restocking)
3. **Execute**:
   - Upload CSV to admin portal
   - Review generated adjustments (100+ items batch size limit)
   - Approve batch 1 of 5
   - Monitor: StockAdjusted events published; downstream services reindexed
4. **Verify**: Recount affected warehouses; confirm stock matches
5. **Audit**: Log correction reason + approver name

### Runbook 3: Feature Flag Deployment + Rollback

1. **Pre-Deployment**: Flag name = "new-payment-flow", default = false
2. **Canary** (5% traffic): Set override to true with 300s TTL
3. **Monitor** (5 min): Error rate, latency, conversion
4. **Scale** (if good): Increase to 50% → 100%
5. **Rollback** (if issues): Override = false (immediate)
6. **Cleanup**: Remove override after full rollout

## Related Documentation

- **ADR-011**: Admin-Gateway Authentication Model (design rationale, JWT strategy)
- **ADR-006**: Internal Service Authentication (broader auth strategy)
- **ADR-010**: Per-Service Token Rollout (token scoping precedent)
- **ADR-015**: SLO and Error-Budget Policy (operational targets)
- **Runbook**: admin-gateway-service/runbook.md (on-call procedures)
- **High-Level Design**: diagrams/hld.md (service dependencies)
- **Sequence Diagrams**: diagrams/sequence.md (request flows)

## Integrations

### Synchronous Calls
| Service | Endpoint | Timeout | Purpose | Critical |
|---------|----------|---------|---------|----------|
| config-feature-flag-service | http://config-feature-flag-service:8095/flags | 5s | Fetch feature flags | Yes |
| reconciliation-engine | http://reconciliation-engine:8098/pending | 5s | Query pending items | Yes |
| order-service | http://order-service:8085/stats | 500ms | Dashboard order volume | Yes |
| payment-service | http://payment-service:8084/volume | 500ms | Dashboard payment volume | Yes |
| fulfillment-service | http://fulfillment-service:8080/backlog | 500ms | Dashboard fulfillment backlog | Yes |

### Identity-Service JWKS
- **Endpoint**: http://identity-service:8080/jwt/jwks
- **Refresh**: 5-minute TTL (via Spring HTTP client caching)
- **Fallback**: Cached keys valid for signed tokens
- **Validation**: RSA public key must match issued JWT signature

## Endpoints

### Public (Unauthenticated)
- `GET /actuator/health` - Liveness probe
- `GET /actuator/metrics` - Prometheus metrics
- `GET /admin/health` - Admin health (unprotected)
- `GET /admin/metrics` - Admin metrics (unprotected)

### Admin API (Requires JWT + ADMIN Role)
- `GET /admin/v1/dashboard` - Dashboard aggregation (P99 < 500ms)
- `GET /admin/v1/flags` - Feature flags snapshot (delegates to config-service)
- `POST /admin/v1/flags/{id}/override` - Temporary flag override with TTL
- `GET /admin/v1/reconciliation/pending` - Pending reconciliation items (P99 < 1000ms)
- `POST /admin/v1/reconciliation/query` - Custom reconciliation query with filters
- `POST /admin/v1/orders/{id}/force-release` - Force release stuck order (SUPER_ADMIN only)
- `GET /admin/v1/audit-log` - Query audit trail (immutable, 7-year retention)
- `POST /admin/v1/audit-log/export` - Export audit log for compliance (CSV format)

### Admin Internal Endpoints
- `POST /admin/internal/cache/refresh-jwks` - Force JWKS cache refresh (service-to-service)
- `POST /admin/internal/cache/clear-dashboard` - Clear dashboard cache (service-to-service)
- `GET /admin/internal/status` - Internal health status (detailed)

### Example Requests

```bash
# Get JWT token from identity-service
JWT_TOKEN=$(curl -X POST http://identity-service:8080/jwt/issue \
  -H "Content-Type: application/json" \
  -d '{"userId":"admin-1","aud":"instacommerce-admin"}' \
  | jq -r '.token')

# Access admin dashboard with cache control
curl -X GET http://admin-gateway-service:8099/admin/v1/dashboard \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "X-Force-Refresh: true"

# Override a feature flag for 600 seconds
curl -X POST http://admin-gateway-service:8099/admin/v1/flags/dark-mode/override \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"value":true,"ttlSeconds":600,"reason":"Testing dark-mode for beta"}'

# Query reconciliation with filters
curl -X POST http://admin-gateway-service:8099/admin/v1/reconciliation/query \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "service":"order-service",
    "severity":"CRITICAL",
    "fromDate":"2025-03-20",
    "toDate":"2025-03-21"
  }' | jq '.pending[] | {id, type, severity}'

# Export audit log for compliance
curl -X POST http://admin-gateway-service:8099/admin/v1/audit-log/export \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fromDate":"2025-01-01",
    "toDate":"2025-03-21",
    "format":"CSV"
  }' > audit_log_2025_q1.csv
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8099
SPRING_PROFILES_ACTIVE=gcp

SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/admin_gateway
SPRING_DATASOURCE_HIKARI_POOL_SIZE=30
SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES=5

JWT_PUBLIC_KEY=<RSA public key from identity-service>
JWT_ISSUER=instacommerce-identity
JWT_AUD=instacommerce-admin
JWT_CLOCK_SKEW_SECONDS=300

SPRING_REDIS_HOST=redis.monitoring
SPRING_REDIS_PORT=6379
SPRING_REDIS_TIMEOUT=3000

ADMIN_GATEWAY_DASHBOARD_TIMEOUT_MS=500
ADMIN_GATEWAY_JWKS_CACHE_TTL_SECONDS=300
ADMIN_GATEWAY_DASHBOARD_CACHE_TTL_SECONDS=5

OTEL_TRACES_SAMPLER=always_on
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
```

### application.yml
```yaml
admin-gateway:
  jwt:
    public-key: ${JWT_PUBLIC_KEY}
    issuer: ${JWT_ISSUER:instacommerce-identity}
    aud: ${JWT_AUD:instacommerce-admin}
    clock-skew-seconds: ${JWT_CLOCK_SKEW_SECONDS:300}
  dashboard:
    timeout-ms: ${ADMIN_GATEWAY_DASHBOARD_TIMEOUT_MS:500}
    cache-ttl-seconds: ${ADMIN_GATEWAY_DASHBOARD_CACHE_TTL_SECONDS:5}
    services:
      - name: order-service
        url: http://order-service:8085/stats
        timeout-ms: 500
      - name: payment-service
        url: http://payment-service:8084/volume
        timeout-ms: 500
      - name: fulfillment-service
        url: http://fulfillment-service:8080/backlog
        timeout-ms: 500
  rate-limit:
    super-admin-rps: 500
    ops-lead-rps: 200
    viewer-rps: 100
  audit:
    enabled: true
    retention-days: 2555  # 7 years
    encryption: AES256
  cache:
    redis-ttl-seconds: 300
    max-dashboard-cached-items: 1000

spring:
  datasource:
    hikari:
      pool-size: 30
      idle-timeout: 5m
  redis:
    timeout: 3s
    jedis:
      pool:
        max-active: 20
```
