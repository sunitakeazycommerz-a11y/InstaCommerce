# Admin-Gateway Service Runbook

## Incident Response

### Symptom: 401 Unauthorized errors on /admin/v1 endpoints

**Possible Causes:**
1. JWT token expired
2. Wrong audience in token (instacommerce-api instead of instacommerce-admin)
3. Invalid RSA signature
4. Identity-service JWKS unreachable
5. Clock skew > 5 minutes

**Diagnosis:**
```bash
# Check logs for JWT validation errors
kubectl logs -n admin deploy/admin-gateway-service | grep "TOKEN_INVALID\|audience"

# Verify admin-gateway pod is running
kubectl get pods -n admin -l app=admin-gateway-service

# Check identity-service JWKS endpoint
kubectl exec -n identity deploy/identity-service -- curl -s http://localhost:8080/jwt/jwks | jq .

# Verify clock sync between services
kubectl exec -n admin deploy/admin-gateway-service -- date -u
kubectl exec -n identity deploy/identity-service -- date -u
```

**Resolution:**
1. If token expired: Issue new token from identity-service
2. If wrong audience: Admin user must request `aud=instacommerce-admin` token
3. If invalid signature: Restart admin-gateway (refresh JWKS cache)
4. If JWKS unreachable: Check identity-service connectivity
5. If clock skew: Sync cluster time with NTP

---

### Symptom: 500 Internal Server Error on /admin/v1/dashboard

**Possible Causes:**
1. config-feature-flag-service unreachable
2. Database connection pool exhausted
3. Out of memory (OOM)

**Diagnosis:**
```bash
# Check admin-gateway logs
kubectl logs -n admin deploy/admin-gateway-service --tail=50

# Check metrics
kubectl exec -n admin deploy/admin-gateway-service -- \
  curl -s http://localhost:8099/actuator/metrics | jq .

# Check pod memory
kubectl top pod -n admin -l app=admin-gateway-service

# Check config-feature-flag-service reachability
kubectl exec -n admin deploy/admin-gateway-service -- \
  curl -v http://config-feature-flag-service:8095/flags

# Check database connections (if applicable)
kubectl exec -n admin deploy/admin-gateway-service -- \
  curl -s http://localhost:8099/actuator/health | jq .
```

**Resolution:**
1. If flag service unreachable: Check NetworkPolicy and service DNS
2. If OOM: Scale up pod memory or reduce replicas temporarily
3. If database pool exhausted: Restart pod to reset connections

---

### Symptom: High latency on /admin/v1 endpoints (>2s)

**Possible Causes:**
1. Downstream service (flag/reconciliation) slow
2. Network congestion
3. High CPU utilization

**Diagnosis:**
```bash
# Check OpenTelemetry traces
kubectl logs -n admin deploy/admin-gateway-service | grep "duration"

# Check downstream service latency
kubectl exec -n admin deploy/admin-gateway-service -- \
  curl -s http://config-feature-flag-service:8095/flags \
  -w "Total: %{time_total}s\n" -o /dev/null

# Check CPU usage
kubectl top pod -n admin -l app=admin-gateway-service

# Check Istio VirtualService timeout
kubectl get vs -n admin admin-gateway-service -o yaml | grep timeout
```

**Resolution:**
1. If downstream slow: Debug and scale that service
2. If network congestion: Check cluster network policies
3. If CPU high: Increase replicas or optimize code

---

## Deployment Procedures

### Rolling Update

```bash
# Verify current version
kubectl get deploy -n admin admin-gateway-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# Update to new version (Helm)
helm upgrade admin-gateway-service deploy/helm/admin-gateway-service \
  -n admin \
  --values deploy/helm/values.yaml \
  --values deploy/helm/values-prod.yaml

# Monitor rollout
kubectl rollout status deploy/admin-gateway-service -n admin --timeout=5m

# Verify new pods are healthy
kubectl get pods -n admin -l app=admin-gateway-service
```

### Rollback

```bash
# Rollback Helm release
helm rollback admin-gateway-service 1 -n admin

# Monitor rollback
kubectl rollout status deploy/admin-gateway-service -n admin --timeout=5m
```

### Emergency Restart (Clear JWKS Cache)

```bash
# If JWKS cache is stale, restart the pod
kubectl rollout restart deploy/admin-gateway-service -n admin

# Wait for new pods to become ready
kubectl wait --for=condition=ready pod -l app=admin-gateway-service -n admin --timeout=2m
```

---

## Maintenance Tasks

### JWT Public Key Rotation

1. **Identity-service generates new key:**
   - Add to JWKS with new `kid`
   - Keep old key active for 24 hours

2. **Monitor admin-gateway:**
   - JWKS cache auto-refreshes every 5 minutes
   - No restart needed

3. **Verify rotation:**
   ```bash
   kubectl logs -n admin deploy/admin-gateway-service | grep "Fetching JWKS"
   ```

4. **Deactivate old key:**
   - Remove from JWKS after 24-hour grace period

---

## Monitoring & Dashboards

### Prometheus Metrics

```
# Request latency (p99)
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{job="admin-gateway-service"}[5m]))

# 401 Unauthorized rate
rate(http_server_requests_total{job="admin-gateway-service",status="401"}[5m])

# Downstream errors
rate(http_server_requests_total{job="admin-gateway-service",status=~"5.."}[5m])

# Pod memory usage
container_memory_usage_bytes{pod=~"admin-gateway-service.*"}

# Pod CPU usage
rate(container_cpu_usage_seconds_total{pod=~"admin-gateway-service.*"}[5m])
```

### Grafana Dashboard

Dashboard ID: admin-gateway-service
URL: https://grafana.internal.instacommerce.com/d/admin-gateway-service

**Panels:**
- Request latency histogram
- Error rate by status code
- Pod memory/CPU usage
- Downstream service response time
- JWT validation failure count

---

## Testing & Validation

### Manual Testing (Dev Environment)

```bash
# 1. Get a valid JWT token
JWT_TOKEN=$(curl -X POST http://identity-service:8080/jwt/issue \
  -H "Content-Type: application/json" \
  -d '{"userId":"admin-test","aud":"instacommerce-admin"}' | jq -r '.token')

# 2. Test dashboard endpoint
curl -X GET http://admin-gateway-service:8099/admin/v1/dashboard \
  -H "Authorization: Bearer $JWT_TOKEN"

# 3. Test feature flag override
curl -X POST http://admin-gateway-service:8099/admin/v1/flags/test-flag/override \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"value":true,"ttlSeconds":600}'

# 4. Test 401 with wrong audience
JWT_WRONG_AUD=$(curl -X POST http://identity-service:8080/jwt/issue \
  -H "Content-Type: application/json" \
  -d '{"userId":"admin-test","aud":"instacommerce-api"}' | jq -r '.token')

curl -X GET http://admin-gateway-service:8099/admin/v1/dashboard \
  -H "Authorization: Bearer $JWT_WRONG_AUD" \
  -w "\nStatus: %{http_code}\n"
```

### Integration Tests

```bash
# Run test suite
./gradlew :services:admin-gateway-service:integrationTest -Dspring.profiles.active=test

# Expected output
# AdminJwtAuthenticationFilterTest .................. PASSED
# AdminDashboardControllerTest ...................... PASSED
# BUILD SUCCESSFUL
```

---

## Emergency Procedures

### Circuit Breaker Tripped

If downstream services are failing:

```bash
# Check Resilience4j metrics
kubectl exec -n admin deploy/admin-gateway-service -- \
  curl -s http://localhost:8099/actuator/metrics/resilience4j.circuitbreaker.state

# Manually reset circuit breaker (if supported)
kubectl exec -n admin deploy/admin-gateway-service -- \
  curl -X POST http://localhost:8099/actuator/circuitbreakers/featureFlagService/reset
```

### Total Authentication Bypass (P0 Incident)

If JWT validation is compromised:

1. **Immediately disable admin-gateway:**
   ```bash
   kubectl scale deploy admin-gateway-service -n admin --replicas=0
   ```

2. **Investigate root cause**

3. **Patch and deploy fix**

4. **Re-enable:**
   ```bash
   kubectl scale deploy admin-gateway-service -n admin --replicas=2
   ```

---

## Related Documentation

- **Deployment**: `deploy/helm/admin-gateway-service/`
- **Architecture**: `docs/services/admin-gateway-service/diagrams/`
- **ADR-011**: `docs/adr/011-admin-gateway-auth-model.md`
- **Alert Rules**: `monitoring/prometheus/admin-gateway-rules.yaml`
