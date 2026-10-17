# Wave 34 Track B: Per-Service Token Scoping Implementation

## Summary

This PR implements Phase 1 of per-service token scoping for least-privilege service-to-service authentication across all 28 InstaCommerce services. This is a critical security hardening initiative that reduces blast radius for token compromise while maintaining backward compatibility through a 2-week dual-mode rollout.

## What Changed

### 1. Identity Service Enhancements

**File: `services/identity-service/src/main/resources/application.yml`**
- Added `internal.service.allowed-callers` map with per-service token configuration for all 28 services
- Each service token reads from environment variable with empty string fallback
- Supports dual-mode acceptance during Phase 1 migration

**File: `services/identity-service/src/main/java/com/instacommerce/identity/security/InternalServiceAuthFilter.java`**
- Enhanced with SLF4J logging for audit trail
- Logs both token acceptance and rejection with service name, HTTP method, and path
- Improved error handling for incomplete auth headers
- Checks per-service tokens first, then falls back to shared token
- Maintains constant-time comparison using `MessageDigest.isEqual()`
- Continues to grant only `ROLE_INTERNAL_SERVICE` authority

**File: `services/identity-service/src/test/java/com/instacommerce/identity/security/InternalServiceAuthFilterTest.java`** (NEW)
- Comprehensive test suite with 11 test methods
- Tests per-service token acceptance/rejection
- Tests shared token fallback during migration
- Tests empty per-service token fallback
- Tests timing-safe comparison
- Tests authority granting (only ROLE_INTERNAL_SERVICE, never ROLE_ADMIN)
- Tests missing/incomplete headers
- All tests pass in Maven build

### 2. Kubernetes Secret Infrastructure

**File: `infra/terraform/modules/kubernetes-secrets/main.tf`** (NEW)
- Creates Kubernetes secret `service-tokens` with 28 unique tokens
- Uses `random_password` resource for cryptographically secure generation
- Generates 64-byte tokens (4x more secure than typical 16-byte UUID)
- Tokens stored with keys matching service names: `order-service-token`, `payment-service-token`, etc.
- Includes labels for Wave 34 Track B tracking and auditing

**File: `infra/terraform/modules/kubernetes-secrets/variables.tf`** (NEW)
- Namespace variable for flexibility across environments

**File: `infra/terraform/environments/dev/main.tf`**
- Added `kubernetes_secrets` module dependency on GKE
- Applies to dev environment; prod environment should follow same pattern

### 3. Helm Chart Updates

**File: `deploy/helm/values.yaml`**
- **identity-service**: Added 28 environment variables (one per service) that inject per-service caller tokens from `service-tokens` secret
- These environment variables are injected into `internal.service.allowed-callers` config
- Shows pattern for other services to follow

**File: `HELM_VALUES_UPDATE_GUIDE.md`** (NEW)
- Comprehensive guide for updating all 28 services
- Specific key names for each service
- Example YAML for token injection pattern
- Deployment and validation checklist
- Rollback procedures

**File: `scripts/wave34_helm_values_updater.py`** (NEW)
- Python automation script to update `deploy/helm/values.yaml`
- Injects `INTERNAL_SERVICE_TOKEN` secret injection for all 28 services
- Skips services that already have the configuration
- Can be run manually or as part of CI pipeline

### 4. Documentation

**File: `docs/adr/012-per-service-token-scoping.md`** (NEW)
- Architecture Decision Record for per-service token scoping
- Full context, decision, consequences, and risks
- 2-phase rollout plan with timeline
- Migration steps and rollback procedures
- Testing strategy and validation
- Alternatives considered (SPIFFE, OAuth2, Vault)

**File: `WAVE34_TOKENS.txt`** (NEW)
- Reference list of all 28 per-service tokens for documentation
- Format: `instacommerce-{service-name}-{uuid}`
- Can be used for manual verification

**File: `HELM_VALUES_UPDATE_GUIDE.md`** (NEW)
- Step-by-step guide for Helm values updates
- Deployment checklist
- Validation commands
- Rollback procedures

## How It Works

### Token Flow (Phase 1: Dual-Mode)

1. **Token Generation** (Terraform)
   ```
   terraform apply infra/terraform/modules/kubernetes-secrets/
   → Generates 28 random 64-byte tokens
   → Creates Kubernetes secret: service-tokens
   ```

2. **Token Injection** (Helm)
   ```
   helm upgrade instacommerce ./deploy/helm/
   → identity-service receives all 28 caller tokens as env variables
   → Each service receives its own token: INTERNAL_SERVICE_TOKEN
   → Tokens mounted from service-tokens secret
   ```

3. **Token Validation** (Runtime)
   ```
   Service A makes request to Service B:
   → POST /api/v1/auth/verify
   → Headers: X-Internal-Service: order-service
   →          X-Internal-Token: ${ORDER_SERVICE_TOKEN}

   Service B (receiving):
   → InternalServiceAuthFilter.doFilterInternal()
   → Check: allowedCallers.get("order-service") == X-Internal-Token?
   → YES: Log "token accepted", grant ROLE_INTERNAL_SERVICE
   → NO: Check shared token fallback
   →     YES: Log "shared token fallback", grant ROLE_INTERNAL_SERVICE
   →     NO: Log "token rejected", deny access
   ```

## Testing

### Unit Tests
```bash
mvn test -Dtest=InternalServiceAuthFilterTest
# Output: 11 tests passed
```

### Integration Tests (Manual - 2 week period)
```bash
kubectl logs -l app=identity-service --tail=100 | grep "token accepted"
# Expect: Lines showing per-service tokens being accepted after Helm deployment
```

### Validation Checklist
- [ ] Terraform: `terraform apply` succeeds, creates 28 secret keys
- [ ] Kubernetes: `kubectl get secret service-tokens` shows 28 keys
- [ ] Helm: `helm dry-run` shows all 28 env variables injected
- [ ] Pods: All services restart and receive tokens
- [ ] Logs: Identity-service shows "per-service token accepted" for all callers
- [ ] No 401/403 errors in internal service calls

## Files Changed Summary

```
docs/adr/012-per-service-token-scoping.md (NEW - 380 lines)
infra/terraform/modules/kubernetes-secrets/main.tf (NEW - 180 lines)
infra/terraform/modules/kubernetes-secrets/variables.tf (NEW - 5 lines)
infra/terraform/environments/dev/main.tf (+8 lines)
services/identity-service/src/main/resources/application.yml (+40 lines)
services/identity-service/src/main/java/com/instacommerce/identity/security/InternalServiceAuthFilter.java (+60 lines)
services/identity-service/src/test/java/com/instacommerce/identity/security/InternalServiceAuthFilterTest.java (NEW - 350 lines)
deploy/helm/values.yaml (+95 lines for identity-service, +script for 27 others)
scripts/wave34_helm_values_updater.py (NEW - 150 lines)
HELM_VALUES_UPDATE_GUIDE.md (NEW - 200 lines)
WAVE34_TOKENS.txt (NEW - 30 lines)
```

## Implementation Steps

### Phase 1: Deployment (This PR)

1. **Apply Terraform** (Cluster-level)
   ```bash
   cd infra/terraform/environments/dev
   terraform apply -target module.kubernetes_secrets
   # Creates service-tokens secret with 28 unique tokens
   ```

2. **Update Helm Values** (Application-level)
   ```bash
   # Option A: Manual (uses provided guide)
   # Follow HELM_VALUES_UPDATE_GUIDE.md for each service

   # Option B: Automated
   python3 scripts/wave34_helm_values_updater.py deploy/helm/values.yaml
   ```

3. **Deploy Updates**
   ```bash
   helm upgrade instacommerce ./deploy/helm/values.yaml
   # Rolling deployment of identity-service first (updated filter)
   # Then rolling deployment of all 28 services (updated helm values)
   ```

4. **Validate**
   ```bash
   # Check logs for per-service token acceptance
   kubectl logs -l app=identity-service | grep "token accepted"
   # Monitor for 2 weeks
   ```

### Phase 2: Enforcement (Wave 34 Follow-up PR - 2026-04-04)

- Remove shared token fallback from InternalServiceAuthFilter
- Require per-service tokens exclusively
- Remove INTERNAL_SHARED_TOKEN configuration
- Services only accept their scoped token

## Rollback

**During Phase 1 (anytime in 2-week window):**
```bash
git revert <commit-hash>
helm upgrade instacommerce ./deploy/helm/values.yaml
# Services revert to accepting shared token
# No downtime (Istio mTLS continues to work)
```

**After Phase 2 enforcement:**
```bash
# Same as Phase 1: revert commit and redeploy
# Will require adding back shared token configuration temporarily
```

## Security Implications

### Risk Reduction
- **Blast Radius**: Token compromise affects only that service, not all 28
- **Token Rotation**: Can rotate order-service token independently
- **Audit Trail**: Terraform + Git history shows per-service provisioning
- **Least Privilege**: Each service gets exactly its own token

### Maintained Protections
- Constant-time comparison (MessageDigest.isEqual)
- Minimal authority (ROLE_INTERNAL_SERVICE only)
- Istio mTLS peer authentication still applied
- Network policies still enforced

### Migration Safety
- Phase 1: Backward compatible (accepts both new and old)
- 2-week observation window before enforcement
- Easy rollback at any point during Phase 1
- No impact to external APIs (Istio gateway + JWT still required)

## Dependencies

- Terraform 1.x+
- Kubernetes 1.24+ (Secret support)
- Helm 3.x+
- Spring Boot 4.0 (identity-service already uses this)
- Maven 3.8+ (for unit test execution)

## Future Work

### Wave 34 Follow-up: Phase 2 Enforcement
- Remove shared token fallback
- Enforce per-service tokens exclusively
- Expected: 2026-04-04 (after 2-week observation)

### Wave 30+: SPIFFE/SPIRE Workload Identity
- Zero static tokens
- Automatic mTLS with workload certificates
- Perfect identity federation
- Requires Istio/cert-manager integration

## Sign-Off Checklist

- [ ] Code Review: Cloud Architect approved security design
- [ ] Tests: All 11 unit tests passing, manual integration tests successful
- [ ] Documentation: ADR-012 and guides reviewed by principal engineer
- [ ] Terraform: Module generates unique tokens, creates secret correctly
- [ ] Helm: All 28 services show token injection in dry-run
- [ ] Staging Validation: 2-week observation period completed
- [ ] Go-Live: Approved by Wave 34 Track B owner
