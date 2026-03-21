# Admin-Gateway Service

## Overview

The admin-gateway-service is the entry point for internal administrative operations in InstaCommerce. It provides secure access to operational dashboards, feature flag management, and reconciliation oversight through a JWT-authenticated REST API.

**Service Ownership**: Platform Team - Admin Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8099
**Status**: Tier 1 Critical (Admin operations)

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month)
- **P99 Latency**: < 500ms per admin operation
- **Error Rate**: < 0.5%
- **Max Throughput**: 1,000 requests/minute (adequate for internal admin team)

## Key Responsibilities

1. **Dashboard Aggregation**: Real-time order volume, payment volume, fulfillment metrics
2. **Feature Flag Management**: Override feature flags with TTL-based expiration
3. **Reconciliation Oversight**: Query pending reconciliation items from reconciliation-engine
4. **Authentication & Authorization**: JWT validation against identity-service JWKS with audience scoping

## Deployment

### GKE Deployment
- **Namespace**: admin
- **Replicas**: 2 (HA)
- **CPU Request/Limit**: 250m / 500m
- **Memory Request/Limit**: 384Mi / 768Mi

### Cluster Configuration
- **Ingress**: Internal-only (behind Istio IngressGateway)
- **NetworkPolicy**: Deny-default; only allow from istio-ingressgateway
- **Service Account**: admin-gateway-service

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

**Rejection Criteria**:
- Invalid signature → 401 (Security threat)
- Expired token → 401 (Expired credential)
- Wrong audience → 401 (Token reuse attack prevention)
- Missing audience → 401 (Misconfigured token)

## Integrations

### Synchronous Calls
| Service | Endpoint | Timeout | Purpose |
|---------|----------|---------|---------|
| config-feature-flag-service | http://config-feature-flag-service:8095/flags | 5s | Fetch feature flags |
| reconciliation-engine | http://reconciliation-engine:8098/pending | 5s | Query pending items |

### Identity-Service JWKS
- **Endpoint**: http://identity-service:8080/jwt/jwks
- **Refresh**: 5-minute TTL (via Spring HTTP client caching)
- **Fallback**: Cached keys valid for signed tokens

## Endpoints

### Public (Unauthenticated)
- `GET /actuator/health` - Liveness probe
- `GET /actuator/metrics` - Prometheus metrics
- `GET /admin/health` - Admin health (unprotected)
- `GET /admin/metrics` - Admin metrics (unprotected)

### Admin API (Requires JWT + ADMIN Role)
- `GET /admin/v1/dashboard` - Dashboard aggregation (200 Ok or 500 Error)
- `GET /admin/v1/flags` - Feature flags snapshot (delegates to config-service)
- `POST /admin/v1/flags/{id}/override` - Temporary flag override with TTL
- `GET /admin/v1/reconciliation/pending` - Pending reconciliation items (delegates to recon-engine)

### Example Requests

```bash
# Get JWT token from identity-service
JWT_TOKEN=$(curl -X POST http://identity-service:8080/jwt/issue \
  -H "Content-Type: application/json" \
  -d '{"userId":"admin-1","aud":"instacommerce-admin"}' \
  | jq -r '.token')

# Access admin dashboard
curl -X GET http://admin-gateway-service:8099/admin/v1/dashboard \
  -H "Authorization: Bearer $JWT_TOKEN"

# Override a feature flag for 600 seconds
curl -X POST http://admin-gateway-service:8099/admin/v1/flags/dark-mode/override \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"value":true,"ttlSeconds":600}'
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8099
JWT_PUBLIC_KEY=<RSA public key from identity-service>
JWT_ISSUER=instacommerce-identity
JWT_AUD=instacommerce-admin
JWT_CLOCK_SKEW_SECONDS=300
SPRING_PROFILES_ACTIVE=gcp
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
```

## Monitoring & Alerts

### Key Metrics
- `http_server_requests_seconds` (histogram) - Request latency
- `http_server_requests_total` (counter) - Request volume by status/method
- `jvm_memory_usage_bytes` - Heap memory
- `process_cpu_usage` - CPU utilization

### Alerting Rules
- `admin_gateway_401_rate > 10/min` - Excessive JWT validation failures (possible attack)
- `admin_gateway_downstream_error_rate > 1%` - Errors from feature-flag or reconciliation services
- `admin_gateway_request_latency_p99 > 2000ms` - Performance degradation

### Logging
- **WARN**: JWT validation failures (wrong audience, expired, invalid signature)
- **INFO**: Successful logins (audit trail)
- **ERROR**: Downstream service failures, unexpected exceptions

## Security Considerations

### Threat Mitigations
1. **Token Reuse Prevention**: Audience claim scoping prevents `instacommerce-api` tokens from accessing admin endpoints
2. **Signature Verification**: RSA signature prevents token forgery
3. **Expiry Enforcement**: Expired tokens rejected immediately (5-min clock skew buffer)
4. **Rate Limiting**: (Planned) IP-based rate limit at Istio VirtualService layer
5. **Audit Logging**: (Planned) Log all admin actions to centralized audit trail

### Known Risks
- **Compromised identity-service**: All JWT validation depends on JWKS integrity
- **Stolen JWT token**: Admin user compromise gives full API access (mitigated by short expiry)
- **Clock skew exploitation**: 5-minute window allows time-travel attacks (mitigated by monitoring)

## Troubleshooting

### JWT Validation Failures
1. **Verify issuer**: `JWT_ISSUER` matches identity-service configuration
2. **Verify audience**: Identity-service must issue `aud=instacommerce-admin` for admin users
3. **Check clock sync**: `date -u` on both admin-gateway and identity-service must be within 5 minutes
4. **Verify public key**: `JWT_PUBLIC_KEY` environment variable correctly set (no line breaks)

### Downstream Service Failures
1. **Feature flags unreachable**: Check connectivity to config-feature-flag-service:8095
2. **Reconciliation unreachable**: Check connectivity to reconciliation-engine:8098
3. **Graceful degradation**: API returns 500 with error details (does not reject request)

## Related Documentation

- **ADR-011**: Admin-Gateway Authentication Model (design rationale)
- **ADR-006**: Internal Service Authentication (broader auth strategy)
- **ADR-010**: Per-Service Token Rollout (token scoping precedent)
- **Runbook**: admin-gateway-service/runbook.md
