# Identity & Admin Gateway Services Cluster

## Overview

This cluster comprises two critical services for user authentication, authorization, and administrative access:

1. **identity-service**: Single authority for JWT token issuance, credential management, and GDPR erasure
2. **admin-gateway-service**: API gateway for administrative operations with JWT validation and rate limiting

## Service Architecture

```
┌─────────────┐         ┌──────────────────┐
│ Mobile App  │──┐      │ Admin Console    │
└─────────────┘  │      └──────────────────┘
                 │                │
                 ├─────────────────┤
                 ▼                 ▼
           ┌────────────────────────────┐
           │  Istio Gateway (TLS)       │
           └────────────────────────────┘
                 │
        ┌────────┴────────┐
        ▼                 ▼
   ┌─────────────┐  ┌──────────────────┐
   │   identity  │  │  admin-gateway   │
   │  -service   │  │   -service       │
   │   :8081     │  │    :8082         │
   └─────────────┘  └──────────────────┘
        │
   ┌────┴────────────────┐
   ▼                     ▼
PostgreSQL 15+      Kafka identity.events
```

## Service Responsibilities

### Identity Service (Tier 1)
- **JWT token lifecycle**: Generate, validate, refresh, revoke
- **Credential management**: Registration, login, password reset
- **GDPR erasure**: Event-driven user anonymization
- **Rate limiting**: 5 req/min per IP for login, 3 req/min for registration
- **Port**: 8081 (dev), 8080 (container)

### Admin Gateway Service (Tier 1)
- **Request routing**: Proxy administrative requests to domain services
- **Authentication**: JWT validation + internal service token
- **Rate limiting**: Per-user and per-endpoint limits via Caffeine cache
- **CORS**: Configured for admin console origins
- **Port**: 8082 (dev), 8080 (container)

## Deployment Model

- **Infrastructure**: GKE asia-south1, Istio mesh, ArgoCD GitOps
- **Database**: Cloud SQL PostgreSQL 15+ (private VPC)
- **Secrets**: GCP Secret Manager (JWKS keys, DB credentials)
- **Messaging**: Apache Kafka for user erasure events
- **Replicas**: Stateless; horizontally scalable (min 2, max 10 recommended)

## API Contracts

### Identity Service (public endpoints)
```
POST   /auth/register              → Register new user
POST   /auth/login                 → Authenticate (email + password)
POST   /auth/refresh               → Rotate refresh token
POST   /auth/revoke                → Revoke single token
POST   /auth/logout                → Revoke all tokens
GET    /.well-known/jwks.json      → JWKS endpoint for JWT validation
DELETE /users/me                   → GDPR erasure
GET    /users/me/notification-preferences
PUT    /users/me/notification-preferences
GET    /admin/users                → Paginated user listing (ADMIN only)
GET    /admin/users/{id}           → Get user by ID (ADMIN only)
```

### Admin Gateway Service (administrative endpoints)
```
POST   /api/v1/admin/*             → Proxy to backend services
GET    /admin/health               → Health check
GET    /admin/metrics              → Prometheus metrics
```

## Key Technologies

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 21 | Runtime (identity, admin-gateway) |
| Spring Boot | 4.0 | Web framework |
| Spring Security | — | Authentication & CORS |
| JJWT | 0.12.5 | RS256 JWT signing/verification |
| PostgreSQL | 15+ | Primary datastore (identity) |
| Kafka | 2.8+ | Domain events (identity erasure) |
| Resilience4j | 2.2.0 | Circuit breaker + rate limiting |
| GCP Secret Manager | — | Key material, credentials |

## Security Boundaries

```
B1: Public Internet          ─────TLS─────  Istio Gateway
B2: Ingress Gateway          ─────mTLS────  identity-service
B3: identity-service         ─────JWT───    Domain services
B4: Domain services          ─────Firewall  PostgreSQL (private VPC)
```

## Failure Modes

| Scenario | Detection | Recovery |
|----------|-----------|----------|
| PostgreSQL unavailable | Readiness probe fails | Manual pod restart; DB failover |
| JWT key compromise | Not auto-detected | Manual key rotation via Secret Manager |
| Rate limiter exhausted | 429 responses spike | Pod-local; cross-pod attack via load balancing |
| Token refresh stale | Consumer-side JWT validation | Token re-issued on next successful auth |

## Observability

- **Metrics**: Prometheus via `/actuator/prometheus` (login/register/refresh/revoke counters)
- **Traces**: OpenTelemetry OTLP to collector
- **Logs**: JSON structured via logstash-logback-encoder
- **Audit trail**: `audit_log` table for all security events

## Rollout Checklist

- [ ] Review Flyway migration (if schema change)
- [ ] Verify JWKS endpoint reachability after deploy
- [ ] Monitor `auth.login.duration` timers (BCrypt latency)
- [ ] Check rate-limit metrics on new pod
- [ ] Validate GDPR erasure event flow end-to-end
- [ ] Confirm no readiness probe failures

## Known Limitations

1. **Pod-local rate limiting**: Attacker can bypass by hitting different pods
2. **Single shared internal token**: Any compromised pod can impersonate others
3. **No phone OTP**: Email-only login insufficient for Indian q-commerce market
4. **No JWT key rotation automation**: Manual process only
5. **Timing side-channel in token validation**: Uses `String.equals()` instead of constant-time compare

## References

- [Identity Service README](/services/identity-service/README.md) — Full technical specification
- [Admin Gateway Service README](/services/admin-gateway-service/README.md) — API proxy details
- [Iter 3 Security Review](/docs/reviews/iter3/services/edge-identity.md) — Known gaps & roadmap
- [ADR-006: Internal Service Auth](/docs/adr/006-internal-service-auth.md) — Token scoping strategy
- [ADR-010: Per-Service Token Rollout](/docs/adr/010-per-service-token-rollout.md) — Wave 34+ plan

## Support

- **On-call**: Check CODEOWNERS in `.github/CODEOWNERS`
- **Issues**: Create GitHub issue with label `identity` or `admin-gateway`
- **Deployment**: ArgoCD UI at `argocd.instacommerce.dev`
