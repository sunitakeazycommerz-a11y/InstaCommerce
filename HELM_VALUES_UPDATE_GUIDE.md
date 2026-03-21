# Wave 34 Track B: Helm Values Service Injection Guide

This document provides the precise updates needed for each of the 28 services to inject their per-service token from the Kubernetes secret.

## Update Pattern

Each service configuration in `deploy/helm/values.yaml` should add the following to their `env` section:

```yaml
INTERNAL_SERVICE_TOKEN:
  secretKeyRef:
    name: service-tokens
    key: {service-name}-token
```

Where `{service-name}` matches the service name in the Helm values (e.g., `order-service`, `payment-service`).

## Services Requiring Updates

### Java Services (20)

1. **order-service**
   - Key: `order-service-token`
   - Already has env section; add INTERNAL_SERVICE_TOKEN

2. **payment-service**
   - Key: `payment-service-token`
   - Already has env section; add INTERNAL_SERVICE_TOKEN

3. **fulfillment-service**
   - Key: `fulfillment-service-token`
   - Already has env section; add INTERNAL_SERVICE_TOKEN

4. **inventory-service**
   - Key: `inventory-service-token`
   - Already has env section; add INTERNAL_SERVICE_TOKEN

5. **catalog-service**
   - Key: `catalog-service-token`
   - Already has env section; add INTERNAL_SERVICE_TOKEN

6. **search-service**
   - Key: `search-service-token`
   - Already has env section; add INTERNAL_SERVICE_TOKEN

7. **pricing-service**
   - Key: `pricing-service-token`
   - Already has env section; add INTERNAL_SERVICE_TOKEN

8. **cart-service**
   - Key: `cart-service-token`
   - Already has env section; add INTERNAL_SERVICE_TOKEN

9. **checkout-orchestrator-service**
   - Key: `checkout-orchestrator-service-token`
   - Already has env section; add INTERNAL_SERVICE_TOKEN

10. **notification-service**
    - Key: `notification-service-token`
    - Already has env section; add INTERNAL_SERVICE_TOKEN

11. **warehouse-service**
    - Key: `warehouse-service-token`
    - Already has env section; add INTERNAL_SERVICE_TOKEN

12. **rider-fleet-service**
    - Key: `rider-fleet-service-token`
    - Already has env section; add INTERNAL_SERVICE_TOKEN

13. **routing-eta-service**
    - Key: `routing-eta-service-token`
    - Already has env section; add INTERNAL_SERVICE_TOKEN

14. **wallet-loyalty-service**
    - Key: `wallet-loyalty-service-token`
    - Already configured with INTERNAL_SERVICE_TOKEN; UPDATE to use secret

15. **audit-trail-service**
    - Key: `audit-trail-service-token`
    - Already has env section; add INTERNAL_SERVICE_TOKEN

16. **fraud-detection-service**
    - Key: `fraud-detection-service-token`
    - Already has env section; add INTERNAL_SERVICE_TOKEN

17. **config-feature-flag-service**
    - Key: `config-feature-flag-service-token`
    - Already has env section; add INTERNAL_SERVICE_TOKEN

18. **mobile-bff-service**
    - Key: `mobile-bff-service-token`
    - Already has env section; add INTERNAL_SERVICE_TOKEN

19. **admin-gateway-service**
    - Key: `admin-gateway-service-token`
    - Already has env section; add INTERNAL_SERVICE_TOKEN

20. **identity-service**
    - Key: `identity-service-token`
    - **Already updated in this PR with per-service caller tokens**

### Go Services (7)

> Note: Go services may use environment variables or configuration files differently.
> Verify the specific deployment pattern for each.

1. **cdc-consumer-service**
   - Key: `cdc-consumer-service-token`
   - Add INTERNAL_SERVICE_TOKEN env injection

2. **dispatch-optimizer-service**
   - Key: `dispatch-optimizer-service-token`
   - Add INTERNAL_SERVICE_TOKEN env injection

3. **location-ingestion-service**
   - Key: `location-ingestion-service-token`
   - Add INTERNAL_SERVICE_TOKEN env injection

4. **outbox-relay-service**
   - Key: `outbox-relay-service-token`
   - Add INTERNAL_SERVICE_TOKEN env injection

5. **payment-webhook-service**
   - Key: `payment-webhook-service-token`
   - Add INTERNAL_SERVICE_TOKEN env injection

6. **reconciliation-engine**
   - Key: `reconciliation-engine-token`
   - Add INTERNAL_SERVICE_TOKEN env injection

7. **stream-processor-service**
   - Key: `stream-processor-service-token`
   - Add INTERNAL_SERVICE_TOKEN env injection

### Python Services (2)

1. **ai-orchestrator-service**
   - Key: `ai-orchestrator-service-token`
   - Already configured with INTERNAL_SERVICE_TOKEN; UPDATE to use secret

2. **ai-inference-service**
   - Key: `ai-inference-service-token`
   - Already configured with INTERNAL_SERVICE_TOKEN; UPDATE to use secret

## Example YAML Additions

### For Java Spring Boot Services

```yaml
services:
  order-service:
    replicas: 2
    image: order-service
    port: 8080
    resources:
      requests: { cpu: 500m, memory: 768Mi }
      limits: { cpu: 1000m, memory: 1536Mi }
    env:
      SPRING_PROFILES_ACTIVE: gcp
      SERVER_PORT: "8080"
      SPRING_DATASOURCE_URL: jdbc:postgresql:///order_db?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory
      ORDER_CHECKOUT_DIRECT_SAGA_ENABLED: "true"
      ORDER_CHOREOGRAPHY_FULFILLMENT_CONSUMER_ENABLED: "false"
      INVENTORY_SERVICE_URL: "http://inventory-service:8080"
      CART_SERVICE_URL: "http://cart-service:8088"
      PAYMENT_SERVICE_URL: "http://payment-service:8080"
      PRICING_SERVICE_URL: "http://pricing-service:8087"
      # NEW: Per-service token from Wave 34 Track B
      INTERNAL_SERVICE_TOKEN:
        secretKeyRef:
          name: service-tokens
          key: order-service-token
    hpa:
      minReplicas: 2
      maxReplicas: 10
      targetCPU: 70
```

## Deployment Checklist

- [ ] Terraform: `infra/terraform/modules/kubernetes-secrets/` files created
- [ ] Terraform: Module added to `environments/dev/main.tf` and `environments/prod/main.tf`
- [ ] Helm: identity-service values updated with per-service caller tokens
- [ ] Helm: All 28 service values updated with INTERNAL_SERVICE_TOKEN secret injection
- [ ] Identity Filter: Logging added for audit trail
- [ ] Tests: InternalServiceAuthFilterTest created with comprehensive scenarios
- [ ] ADR-012: Documentation completed
- [ ] PR: Ready for review with all changes

## Validation After Deployment

```bash
# Check secret creation
kubectl get secret service-tokens -o yaml | grep key:

# Expected: 28 keys (one per service)
# Example:
#   order-service-token: <base64>
#   payment-service-token: <base64>
#   ... (26 more)

# Check pod env variable injection
kubectl exec -it pod/order-service-xxxx -- env | grep INTERNAL_SERVICE_TOKEN
# Expected: INTERNAL_SERVICE_TOKEN=<base64-decoded-token>

# Check logs for token acceptance
kubectl logs -l app=identity-service --tail=100 | grep "token accepted"
# Expected: Lines showing "Internal service authentication accepted: service=order-service"
```

## Rollback Procedure

If issues arise during Phase 1 (dual-mode period):

```bash
# Revert Helm values to remove secret injections
git revert <commit-hash>
helm upgrade instacommerce ./deploy/helm

# Services will revert to INTERNAL_SHARED_TOKEN if configured
# Or fail to authenticate (if no shared token available)
# No downtime for user-facing APIs (mTLS still applies)
```

## Notes

- This update maintains backward compatibility during Phase 1
- Phase 1: Accept both per-service tokens AND shared token fallback
- Phase 2 (2-4 weeks after this deployment): Remove shared token fallback
- All services use MessageDigest.isEqual() for constant-time comparison (ADR-006)
- All services grant only ROLE_INTERNAL_SERVICE (not ROLE_ADMIN)
