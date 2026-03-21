# ADR-012: Per-Service Token Scoping for Least-Privilege Service Auth

## Status
Proposed

## Date
2026-03-21

## Context

All 28 InstaCommerce services share a single `INTERNAL_SERVICE_TOKEN` for service-to-service authentication. While ADR-006 and ADR-010 improved the security posture (constant-time comparison, reduced privileges, per-service allowlists), the shared token remains a significant blast-radius risk.

**Problem Statement:**
- Compromise of any service's configuration or memory exposes the shared token
- Attackers gain immediate impersonation capability across all 28 services
- Token rotation requires coordination and deployment to all services simultaneously
- No audit trail of which service uses which token in production

**Scale:**
- 20 Java services
- 7 Go services
- 2 Python services
- Defense-in-depth needed on top of existing Istio mTLS

## Decision

Implement **per-service token scoping** with a structured 2-phase rollout:

### Phase 1: Dual-Mode Accept (Wave 34 Track B - This PR)
- Generate unique token for each of 28 services
- Store all tokens in Kubernetes secret `service-tokens`
- Update identity-service filter to check per-service token first, then fall back to shared token
- Update all 28 services' Helm charts to inject their own token
- Deploy: services accept both per-service tokens AND shared token
- Duration: 2 weeks (2026-03-21 to 2026-04-04)

### Phase 2: Per-Service Enforcement (Wave 34 Follow-up)
- Remove shared token fallback logic from InternalServiceAuthFilter
- Remove INTERNAL_SHARED_TOKEN from all environments
- Services only accept their own per-service token
- Enforced after 2-week observation period
- Rollback: revert to Phase 1 config (instant)

## Architecture

### Token Generation
```
Format: base64-encoded 64-byte random string (4x security vs 16-byte UUID)
Stored: Kubernetes secret "service-tokens" with 28 keys
Keys: "{service-name}-token"
Rotation: Independent per-service via Terraform
```

### Token Flow
```
1. Terraform/random_password generates unique tokens
2. Kubernetes secret created with all 28 tokens
3. Helm values mount secret as env variables
4. Service A → reads OWN_SERVICE_TOKEN from secret
5. Service A → sends X-Internal-Token: $OWN_SERVICE_TOKEN to Service B
6. Service B (identity-service in this arch, but pattern applies)
   a. Check: allowedCallers.get("service-a") == X-Internal-Token?
   b. If match: grant ROLE_INTERNAL_SERVICE (per ADR-006)
   c. If not: fallback to INTERNAL_SHARED_TOKEN for 2 weeks
```

### Configuration Example (Java Service)
```yaml
# services/order-service/src/main/resources/application.yml
internal:
  service:
    token: ${INTERNAL_SERVICE_TOKEN}  # $ORDER_SERVICE_TOKEN from secret
    allowed-callers: {}  # Only receiver (identity) has allowlist

# services/identity-service/src/main/resources/application.yml
internal:
  service:
    token: ${INTERNAL_SERVICE_TOKEN}  # Fallback shared token
    allowed-callers:
      order-service: ${ORDER_SERVICE_TOKEN:}
      payment-service: ${PAYMENT_SERVICE_TOKEN:}
      # ... 26 more services
```

## Deployment

### Kubernetes Secret Creation (Terraform)
```hcl
# infra/terraform/modules/kubernetes-secrets/main.tf
resource "kubernetes_secret" "service_tokens" {
  metadata {
    name = "service-tokens"
  }
  data = {
    "order-service-token" = random_password.order_service_token.result,
    "payment-service-token" = random_password.payment_service_token.result,
    # ... 26 more
  }
}
```

### Helm Chart Updates (All 28 Services)
```yaml
services:
  order-service:
    env:
      INTERNAL_SERVICE_TOKEN:
        secretKeyRef:
          name: service-tokens
          key: order-service-token
  # Repeat for all 28 services
```

### Service Client Updates (Minimal)
No code changes needed. Services already send X-Internal-Token header with their INTERNAL_SERVICE_TOKEN env variable.

## Consequences

### Positive
- **Least-privilege principle**: Each service has a unique credential
- **Reduced blast radius**: Compromise of Service A doesn't expose Service B's token
- **Independent token rotation**: Change order-service token without touching payment-service
- **Audit trail**: Terraform and Git history shows token provisioning per service
- **Gradual rollout**: 2-week dual-mode period allows safe monitoring

### Negative
- **Increased secret surface**: 28 tokens instead of 1 (but Kubernetes secret encryption mitigates)
- **Helm complexity**: 28 env variable injections per identity-service (templating can reduce)
- **Terraform state size**: Per-service random_password resources (negligible impact)

### Risks
- **Missing token env var**: If Helm injection fails, service falls back to shared token (safe)
- **Token expiry in memory**: Service must restart to pick up rotated token
- **Phased transition**: Services in old pods (pre-Phase 2) still accept shared token (intentional)

## Migration Steps

1. **Terraform Apply** (Deploy per-service tokens to cluster)
   ```bash
   terraform apply -target module.kubernetes_secrets
   ```

2. **Helm Dry-Run** (Validate secret injection)
   ```bash
   helm install instacommerce ./deploy/helm --dry-run --values deploy/helm/values.yaml
   ```

3. **Rolling Deployment** (2-week observation window)
   - Deploy identity-service first (updated filter)
   - Deploy all 28 services (updated Helm values)
   - Monitor: logs for token acceptance patterns
   - Validate: X-Internal-Service audit logs show per-service tokens accepted

4. **Phase 2 Deployment** (2026-04-04, after 2-week monitoring)
   - Remove shared-token fallback from InternalServiceAuthFilter
   - Update identity-service application.yml to remove INTERNAL_SHARED_TOKEN
   - Helm redeploy

## Rollback

### Phase 1 Rollback (Within 2 weeks)
```bash
# Revert Helm values to remove per-service token env vars
git revert <commit-hash>
helm upgrade instacommerce ./deploy/helm
```
Services continue working with shared token. No downtime.

### Phase 2 Rollback (After 2 weeks enforcement)
```bash
# Same as Phase 1: revert Helm + identity-service filter
# Services gracefully accept shared token again
```

## Alternatives Considered

### Option A: SPIFFE/SPIRE Workload Identity
- **Pro**: Zero static tokens, perfect identity, automatic rotation
- **Con**: Requires Istio cert-manager integration, 3+ month timeline
- **Decision**: Deferred to Wave 30+; per-service tokens are stepping stone

### Option B: OAuth2 Client Credentials Flow
- **Pro**: Industry standard, time-bound access
- **Con**: Adds OAuth server, complexity for 28 callers
- **Decision**: Rejected; too heavyweight for internal services

### Option C: Hashicorp Vault Agent Sidecar
- **Pro**: Automatic rotation, better auditability
- **Con**: Sidecar overhead, Vault HA setup required
- **Decision**: Deferred to Wave 31+; static tokens sufficient for now

## Testing

### Unit Tests (InternalServiceAuthFilterTest.java)
- ✓ Per-service token accepted (correct caller)
- ✓ Per-service token rejected (wrong caller)
- ✓ Shared token fallback accepted (missing per-service config)
- ✓ Empty per-service token uses fallback
- ✓ Both tokens wrong: rejected
- ✓ Timing-safe comparison verified
- ✓ ROLE_INTERNAL_SERVICE authority only (never ROLE_ADMIN)

### Integration Tests
```gherkin
Scenario: Order-service can call identity-service with per-service token
  Given order-service is deployed with order-service-token
  When order-service calls POST /api/v1/auth/verify
  And X-Internal-Service: order-service
  And X-Internal-Token: order-service-token
  Then response is 200 with ROLE_INTERNAL_SERVICE
```

### Staging Validation (2-week window)
1. Monitor: `log level=INFO filter=InternalServiceAuthFilter` in identity-service
2. Expect: All callers show "per-service token accepted" (after helm deploy completes)
3. Alert: Any "shared token fallback" logs after day 3 (indicates missing env injection)
4. Verify: No 401/403 errors for internal service calls

## References
- ADR-006: Internal Service Authentication Hardening
- ADR-010: Per-Service Token Rollout (planning)
- [Kubernetes Secrets](https://kubernetes.io/docs/concepts/configuration/secret/)
- [Terraform random_password](https://registry.terraform.io/providers/hashicorp/random/latest/docs/resources/password)

## Sign-Off
- Principal Engineer (Security): TBD
- Platform Lead: TBD
- Wave 34 Track B Owner: TBD
