# Pricing Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify pricing-db (PostgreSQL) is accessible
  ```bash
  kubectl exec -n engagement deploy/pricing-service -- \
    curl -s http://localhost:8112/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check Redis cache for pricing rules
  ```bash
  kubectl exec -n engagement deploy/pricing-service -- \
    curl -s http://localhost:8112/actuator/health | jq '.components.redis'
  ```

- [ ] Verify Kafka connectivity (pricing.events topic)
  ```bash
  kubectl exec -n engagement deploy/pricing-service -- \
    curl -s http://localhost:8112/actuator/health | jq '.components.kafka'
  ```

- [ ] Check pricing rules engine loaded
  ```bash
  kubectl exec -n engagement deploy/pricing-service -- \
    curl -s http://localhost:8112/metrics/pricing-rules-count | jq '.value'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Pricing calculation latency (p99 should be <50ms)
kubectl exec -n engagement deploy/pricing-service -- \
  curl -s http://localhost:8112/metrics/pricing-calculation-latency-p99 | jq '.value'

# Cache hit rate (should be 95%+)
kubectl exec -n engagement deploy/pricing-service -- \
  curl -s http://localhost:8112/metrics/cache-hit-rate | jq '.value'
```

### Blue-Green Deployment

```bash
helm upgrade pricing-service deploy/helm/pricing-service -n engagement
kubectl rollout status deploy/pricing-service -n engagement --timeout=10m

# Verify pricing accuracy still OK
kubectl exec -n engagement deploy/pricing-service -- \
  curl -s http://localhost:8112/metrics/pricing-accuracy-rate | jq '.value'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

---

## Incident Response Procedures

### P0 - Pricing Calculation Down

**Symptom**: Cart unable to calculate prices, checkout blocked

**Diagnosis**:
```bash
kubectl get pods -n engagement -l app=pricing-service
kubectl logs -n engagement deploy/pricing-service --tail=100
kubectl exec -n engagement deploy/pricing-service -- \
  curl -s http://localhost:8112/actuator/health | jq '.'
```

**Resolution**:
1. Scale up: `kubectl scale deploy pricing-service -n engagement --replicas=5`
2. Clear pricing rule cache if stuck: `kubectl rollout restart deploy/pricing-service -n engagement`

### P1 - Incorrect Pricing

**Diagnosis**:
```bash
# Check if rules loaded correctly
kubectl exec -n engagement deploy/pricing-service -- \
  curl -s http://localhost:8112/v1/admin/rules-validation | jq '.errors'

# Test pricing calculation
kubectl exec -n engagement deploy/pricing-service -- \
  curl -s http://localhost:8112/v1/calculate \
  -H "Content-Type: application/json" \
  -d '{"itemId":"test","quantity":1}'
```

**Resolution**:
1. Verify pricing rules in database
2. Trigger rule re-load: `kubectl rollout restart deploy/pricing-service`

---

## Common Issues & Troubleshooting

### Issue: Pricing Rules Not Updating

```bash
# Check rule sync lag
kubectl exec -n engagement deploy/pricing-service -- \
  curl -s http://localhost:8112/metrics/rule-sync-lag | jq '.value'

# Force rule refresh
kubectl exec -n engagement deploy/pricing-service -- \
  curl -X POST http://localhost:8112/v1/admin/refresh-rules \
  -H "Authorization: Bearer admin-token" | jq '.status'
```

### Issue: Incorrect Tax Calculation

```bash
# Verify tax rules
kubectl exec -n engagement deploy/pricing-service -- \
  curl -s http://localhost:8112/v1/admin/tax-rules | jq '.rules'

# Test calculation
curl -s http://pricing-service:8112/v1/calculate/tax \
  -d '{"state":"CA","amount":100}'
```

---

## Monitoring & Alerting

### Key Metrics

```
# Pricing calculation accuracy
pricing_accuracy_rate

# Calculation latency
histogram_quantile(0.99, rate(pricing_calculation_seconds_bucket[5m]))
```

---

## On-Call Handoff

- [ ] Pricing accuracy rate? `kubectl exec -n engagement deploy/pricing-service -- curl -s http://localhost:8112/metrics/pricing-accuracy-rate`
- [ ] Rule sync lag? `kubectl exec -n engagement deploy/pricing-service -- curl -s http://localhost:8112/metrics/rule-sync-lag`

---

## Related Documentation

- **Deployment**: `/deploy/helm/pricing-service/`
- **Architecture**: `/docs/services/pricing-service/README.md`
