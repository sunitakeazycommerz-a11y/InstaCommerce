# ADR-011: Admin-Gateway Authentication Model

**Date**: 2026-03-21
**Status**: Accepted
**Context**: Securing admin-gateway-service endpoints with JWT validation

## Problem

Admin-gateway-service currently has no authentication layer, allowing unauthenticated access to sensitive operations including:
- Dashboard analytics aggregation
- Feature flag overrides
- Reconciliation engine access

This creates a **P0 security vulnerability** requiring immediate remediation.

## Solution

Implement JWT authentication using tokens issued by identity-service with explicit audience scoping to prevent token reuse across service boundaries.

### Key Design Decisions

#### 1. Why JWT from identity-service instead of external OIDC?

- **Internal tokens only**: Admin operations are restricted to internal admin team; external OIDC adds operational complexity.
- **Faster issuance**: Internal service tokens reduce latency vs. external OAuth2 calls.
- **Audience scoping**: We control the `aud` claim namespace; prevents accidental usage of `instacommerce-api` tokens for admin operations.
- **Rollback simplicity**: Token generation logic centralized in identity-service with single kill-switch.

#### 2. Audience Scoping Model

Three audience buckets:
- `instacommerce-api`: Customer/mobile app operations (order, payment, cart)
- `instacommerce-admin`: Admin portal operations (dashboard, flag overrides, reconciliation)
- `instacommerce-internal`: Inter-service communications (order → payment, fulfillment → inventory)

**Validation in AdminJwtAuthenticationFilter**: Tokens without `aud="instacommerce-admin"` are rejected with 401.

#### 3. Clock Skew Configuration

- **Default**: 300 seconds (5 minutes)
- **Rationale**: Allows for up to 2.5 min clock drift in either direction between services
- **Configurable**: Via `JWT_CLOCK_SKEW_SECONDS` environment variable for flexibility during deployment
- **Security note**: JJWT validates both `exp` and `iat` against this window; tokens outside the window fail early

#### 4. Certificate Rotation Without Downtime

- JWKS endpoint: `http://identity-service:8080/jwt/jwks`
- Caches public keys in-memory with TTL
- New keys added to JWKS before old key expiry
- Tokens signed with old keys remain valid during rotation window
- No redeployment needed during rotation

## Implementation

### Components

1. **AdminJwtAuthenticationFilter** (80 lines)
   - Validates signature against identity-service JWKS
   - Extracts `sub` (admin user ID) and `roles` (permissions)
   - Enforces `aud == "instacommerce-admin"` on every request
   - Rejects if audience missing or mismatch → 401
   - Allows 5-minute clock skew for `exp`/`iat`

2. **SecurityConfig**
   - Chain: `/actuator/**`, `/admin/health`, `/admin/metrics` → permitAll
   - Chain: `/admin/v1/**` → require ADMIN role
   - Stateless session (no cookies)

3. **AdminDashboardController** (60 lines)
   - `GET /admin/v1/dashboard` → aggregates order, payment, fulfillment volumes
   - `GET /admin/v1/flags` → HTTP call to config-feature-flag-service
   - `POST /admin/v1/flags/{id}/override` → temporary flag override (TTL 300s)
   - `GET /admin/v1/reconciliation/pending` → HTTP call to reconciliation-engine

4. **Configuration**
   ```yaml
   admin-gateway:
     jwt:
       issuer: instacommerce-identity
       aud: instacommerce-admin
       clock-skew-seconds: 300
   ```

## Rollout Strategy

### Phase 1: Shadow Mode (Day 1)
- Deploy with JWT validation enabled
- Set audit logging to WARN (not rejecting)
- Monitor failed audience validations in logs
- Verify all admin users can authenticate

### Phase 2: Enforcement (Day 2)
- Enable 401 rejection for invalid audience
- Create runbook for token troubleshooting
- Alert on spike in 401s

### Phase 3: Istio AuthorizationPolicy (Wave 34 Track B, future)
- Add mTLS enforcement
- Tie to Kubernetes RBAC groups
- Remove identity-service dependency from SecurityConfig

## Rollback Procedure

1. **Revert JWT validation**: Remove `requireAudience` from JJWT parser
2. **Allow all tokens**: Set `aud` to `null` in environment
3. **Redeploy**: `kubectl rollout restart deployment admin-gateway-service`
4. **Verify**: `curl -X GET http://admin-gateway:8099/admin/v1/dashboard` (no auth header)

**Estimated time**: 5 minutes

## Metrics

- **Token validation latency**: < 1ms (JJWT in-process, no network)
- **JWKS refresh**: TTL = 5 minutes (controlled by identity-service, cacheable)
- **Failure modes**:
  - Invalid token → 401 (no logging overhead)
  - Wrong audience → 401 (logged at WARN)
  - Expired token → 401 (logged at WARN)

## Testing

- ✅ Unit tests for audience validation
- ✅ Unit tests for expired token rejection
- ✅ Unit tests for invalid signature rejection
- ✅ Integration tests with admin-gateway controller
- ✅ E2E tests: Verify `/admin/health` allows unauthenticated access
- ✅ E2E tests: Verify `/admin/v1/dashboard` rejects unauthenticated access

## References

- JJWT Documentation: https://github.com/jwtk/jjwt
- RFC 7519: JSON Web Token (JWT): https://tools.ietf.org/html/rfc7519
- Previous ADR-010: Per-Service Token Rollout
- Previous ADR-006: Internal Service Authentication
