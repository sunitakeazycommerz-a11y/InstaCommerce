# Cart Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify cart-db (PostgreSQL) connectivity
  ```bash
  kubectl exec -n engagement deploy/cart-service -- \
    curl -s http://localhost:8111/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check Redis connectivity (cart session cache)
  ```bash
  kubectl exec -n engagement deploy/cart-service -- \
    curl -s http://localhost:8111/actuator/health | jq '.components.redis'
  ```

- [ ] Verify inventory-service, pricing-service connectivity
  ```bash
  kubectl exec -n engagement deploy/cart-service -- \
    curl -v http://inventory-service:8082/health && \
    curl -v http://pricing-service:8112/health
  ```

- [ ] Check Kafka connectivity (cart.events topic)
  ```bash
  kubectl exec -n engagement deploy/cart-service -- \
    curl -s http://localhost:8111/actuator/health | jq '.components.kafka'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Active carts in session
kubectl exec -n engagement deploy/cart-service -- \
  curl -s http://localhost:8111/metrics/active-carts-count | jq '.value'

# Session cache hit rate (should be >90%)
kubectl exec -n engagement deploy/cart-service -- \
  curl -s http://localhost:8111/metrics/cache-hit-rate | jq '.value'
```

### Blue-Green Deployment

```bash
# 1. Version check
kubectl get deploy -n engagement cart-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Upgrade
helm upgrade cart-service deploy/helm/cart-service -n engagement

# 3. Monitor rollout
kubectl rollout status deploy/cart-service -n engagement --timeout=10m

# 4. Verify session cache recovered
kubectl exec -n engagement deploy/cart-service -- \
  curl -s http://localhost:8111/metrics/cache-hit-rate | jq '.value'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

**Load balancer removal triggers**: Redis down, inventory/pricing service unreachable

---

## Incident Response Procedures

### P0 - Cart Service Down

**Symptom**: Users unable to add items to cart, checkout blocked

**Diagnosis**:
```bash
kubectl get pods -n engagement -l app=cart-service
kubectl logs -n engagement deploy/cart-service --tail=100 | grep -i "error"
kubectl exec -n engagement deploy/cart-service -- curl -s http://localhost:8111/actuator/health | jq '.'
```

**Resolution**:
1. Scale up: `kubectl scale deploy cart-service -n engagement --replicas=5`
2. If Redis down: Service degrades to database-only (slower)
3. Restart: `kubectl rollout restart deploy/cart-service -n engagement`

### P1 - Cart Operations Slow (>1s)

**Diagnosis**:
```bash
kubectl exec -n engagement deploy/cart-service -- \
  curl -s http://localhost:8111/metrics/cart-operation-latency-p99 | jq '.value'
```

**Resolution**:
1. Check inventory-service and pricing-service latency
2. Increase cache size
3. Scale up replicas

---

## Common Issues & Troubleshooting

### Issue: Cart Session Lost

```bash
# Check Redis
kubectl exec -n engagement deploy/cart-service -- \
  curl -s http://localhost:8111/actuator/health | jq '.components.redis'

# Check database backup
kubectl exec -n engagement deploy/cart-service -- \
  curl -s http://localhost:8111/v1/carts/USER-ID | jq '.items'
```

### Issue: Stale Prices in Cart

```bash
# Force price refresh
kubectl exec -n engagement deploy/cart-service -- \
  curl -X POST http://localhost:8111/v1/admin/refresh-prices \
  -H "Authorization: Bearer admin-token" | jq '.status'

# Check pricing-service latency
kubectl exec -n engagement deploy/cart-service -- \
  curl -s http://localhost:8111/metrics/pricing-service-latency | jq '.value'
```

---

## Monitoring & Alerting

### Key Metrics

```
# Cart abandonment rate
cart_abandonment_rate

# Average items per cart
cart_average_items_count

# Cart session duration
histogram_quantile(0.99, rate(cart_session_duration_seconds_bucket[5m]))
```

---

## On-Call Handoff

- [ ] Active carts? `kubectl exec -n engagement deploy/cart-service -- curl -s http://localhost:8111/metrics/active-carts-count`
- [ ] Cache hit rate? `kubectl exec -n engagement deploy/cart-service -- curl -s http://localhost:8111/metrics/cache-hit-rate`

---

## Related Documentation

- **Deployment**: `/deploy/helm/cart-service/`
- **Architecture**: `/docs/services/cart-service/README.md`
