# Inventory Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify inventory-db (PostgreSQL) is up and migrations current
  ```bash
  kubectl exec -n inventory deploy/inventory-service -- \
    curl -s http://localhost:8082/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Verify Redis cache is available (critical for high-throughput)
  ```bash
  kubectl exec -n inventory deploy/inventory-service -- \
    curl -s http://localhost:8082/actuator/health | jq '.components.redis'
  ```

- [ ] Check order-service, checkout-orchestrator connectivity (synchronous dependencies)
  ```bash
  kubectl exec -n inventory deploy/inventory-service -- \
    curl -v http://order-service:8080/health && \
    curl -v http://checkout-orchestrator-service:8091/health
  ```

- [ ] Validate warehouse-service connectivity (synchronous for SKU availability checks)
  ```bash
  kubectl exec -n inventory deploy/inventory-service -- \
    curl -v http://warehouse-service:8085/health
  ```

- [ ] Verify Kafka topic availability (inventory.reserved, inventory.released)
  ```bash
  kubectl exec -n inventory deploy/inventory-service -- \
    curl -s http://localhost:8082/actuator/health | jq '.components.kafka'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Cache hit rate (typically 70-90%)
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/metrics/cache-hit-rate | jq '.value'

# Database query latency (p99 should be <50ms)
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/metrics/db-query-latency-p99 | jq '.value'

# Current replica count
kubectl get deploy inventory-service -n inventory -o jsonpath='{.spec.replicas}'
```

### Blue-Green Deployment

```bash
# 1. Current version
kubectl get deploy -n inventory inventory-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Upgrade
helm upgrade inventory-service deploy/helm/inventory-service \
  -n inventory \
  --values deploy/helm/values.yaml \
  --values deploy/helm/values-prod.yaml

# 3. Monitor rollout
kubectl rollout status deploy/inventory-service -n inventory --timeout=10m

# 4. Verify cache is being used (should recover quickly to 70%+ hit rate)
for i in {1..10}; do
  kubectl exec -n inventory deploy/inventory-service -- \
    curl -s http://localhost:8082/metrics/cache-hit-rate | jq '.value'
  sleep 10
done
```

### Rollback

```bash
helm rollback inventory-service 1 -n inventory
kubectl rollout status deploy/inventory-service -n inventory --timeout=5m

# Verify cache recovered
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/metrics/cache-hit-rate | jq '.value'
```

---

## Post-Deployment Validation

### Service Startup Verification

```bash
# Check Spring Boot startup logs
kubectl logs -n inventory deploy/inventory-service --tail=50 | head -20

# Verify port listening
kubectl exec -n inventory deploy/inventory-service -- \
  netstat -tlnp | grep 8082
```

### Endpoint Smoke Tests

```bash
POD=$(kubectl get pods -n inventory -l app=inventory-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec -n inventory pod/$POD -- \
  curl -s http://localhost:8082/actuator/health | jq '.status'

# 2. Get SKU availability
kubectl exec -n inventory pod/$POD -- \
  curl -s http://localhost:8082/v1/skus/TEST-SKU-001/availability \
  -H "Authorization: Bearer test-token" | jq '.available'

# 3. Reserve inventory (test)
kubectl exec -n inventory pod/$POD -- \
  curl -X POST http://localhost:8082/v1/reservations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token" \
  -d '{"orderId":"test-001","skuId":"TEST-SKU","quantity":1}' | jq '.reservationId'
```

### Database & Cache Validation

```bash
# Database connectivity
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/actuator/health/db | jq '.components.db'

# Redis cache connectivity
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/actuator/health | jq '.components.redis'
```

---

## Health Check Procedures

### Liveness Probe

Endpoint: `GET /actuator/health/live`

```bash
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/actuator/health/live | jq .
```

**Restart triggers**:
- Database connection lost
- Memory exhaustion

### Readiness Probe

Endpoint: `GET /actuator/health/ready`

```bash
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/actuator/health/ready | jq .
```

**Load balancer removal triggers**:
- Cache (Redis) unavailable
- Warehouse-service unavailable
- Database connection pool exhausted

---

## Incident Response Procedures

### P0 - Inventory Checks Down (Checkout Impact)

**Symptom**: Checkout unable to verify inventory availability, order-service calling failing

**Diagnosis**:
```bash
# Check if service is responding
kubectl get pods -n inventory -l app=inventory-service

# Check logs for errors
kubectl logs -n inventory deploy/inventory-service --tail=100 | grep -i "exception\|error\|fatal"

# Check database connectivity
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/actuator/health/db | jq '.status'

# Check Redis connectivity
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/actuator/health | jq '.components.redis'

# Check warehouse-service availability
kubectl exec -n inventory deploy/inventory-service -- \
  curl -v http://warehouse-service:8085/health
```

**Resolution**:
1. Scale up pods: `kubectl scale deploy inventory-service -n inventory --replicas=5`
2. If database down: Failover to replica or check PostgreSQL cluster
3. If Redis down: Requests will degrade to database (slower but functional)
4. If warehouse-service down: Enable circuit breaker, operations degrade but don't fail

### P1 - Inventory Operations Degraded (>1% error rate, >500ms latency)

**Symptom**: Slow reservations, occasional failures

**Diagnosis**:
```bash
# Check latency
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/metrics/reservation-latency-p99 | jq '.value'

# Check error rate
kubectl logs -n inventory deploy/inventory-service --tail=200 | grep -i "error" | wc -l

# Check cache hit rate (if low, database is struggling)
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/metrics/cache-hit-rate | jq '.value'

# Check database connection pool
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/actuator/metrics/hikaricp | jq '.measurements[] | select(.tags.pool | contains("idle"))'
```

**Resolution**:
1. If cache hit rate < 50%: Increase cache TTL or size
2. If database latency high: Check PostgreSQL CPU/memory, add replicas
3. If warehouse-service slow: Enable async fallback
4. Scale up: `kubectl scale deploy inventory-service -n inventory --replicas=4`

### P2 - Cache Issues (Stale Data, Inconsistency)

**Symptom**: Inventory showing wrong quantities, cache not invalidating

**Diagnosis**:
```bash
# Check cache invalidation events
kubectl logs -n inventory deploy/inventory-service | grep -i "cache.*invalidat"

# Manual cache refresh
kubectl exec -n inventory deploy/inventory-service -- \
  curl -X POST http://localhost:8082/v1/admin/cache/rebuild \
  -H "Authorization: Bearer admin-token" | jq '.status'
```

**Resolution**:
1. Manual invalidation: See above
2. Check if Redis pub/sub is working (cache invalidation channel)
3. Force pod restart to clear in-memory cache: `kubectl rollout restart deploy/inventory-service -n inventory`

---

## Common Issues & Troubleshooting

### Issue: "Connection refused" to warehouse-service

```bash
# Check if warehouse-service is running
kubectl get pods -n warehouse -l app=warehouse-service

# Test DNS resolution
kubectl exec -n inventory deploy/inventory-service -- \
  nslookup warehouse-service.warehouse.svc.cluster.local

# Test connectivity
kubectl exec -n inventory deploy/inventory-service -- \
  nc -zv warehouse-service.warehouse.svc.cluster.local 8085

# Enable circuit breaker fallback (degrade gracefully)
kubectl set env deploy/inventory-service -n inventory \
  WAREHOUSE_CIRCUIT_BREAKER_ENABLED=true \
  WAREHOUSE_CIRCUIT_BREAKER_THRESHOLD=5
```

### Issue: Cache Hit Rate Low (<50%)

```bash
# Check cache size usage
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/metrics/cache-size | jq '.measurements'

# Increase cache size
helm upgrade inventory-service deploy/helm/inventory-service \
  -n inventory \
  --set cache.maxSize=10000 \
  --set cache.ttlMinutes=60

# Restart pods to pick up new cache size
kubectl rollout restart deploy/inventory-service -n inventory
```

### Issue: Reservation Conflicts (Deadlock)

```bash
# Check for stuck transactions
kubectl exec -n inventory deploy/inventory-service -- \
  curl -s http://localhost:8082/v1/admin/locks | jq '.locked_skus'

# Generate thread dump if suspected deadlock
kubectl exec -n inventory deploy/inventory-service -- \
  jcmd $(pidof java) Thread.print > /tmp/thread_dump.txt

# Look for BLOCKED threads
grep "BLOCKED" /tmp/thread_dump.txt

# If deadlock confirmed, restart pod
kubectl delete pod -n inventory $(kubectl get pods -n inventory -l app=inventory-service -o jsonpath='{.items[0].metadata.name}')
```

### Issue: Out of Memory

```bash
# Check memory usage
kubectl top pod -n inventory -l app=inventory-service

# Check if OOM killed
kubectl describe pod -n inventory <pod-name> | grep -i "oom"

# Increase memory limit
helm upgrade inventory-service deploy/helm/inventory-service \
  -n inventory \
  --set resources.limits.memory=2Gi \
  --set resources.requests.memory=1Gi
```

---

## Performance Tuning

### Cache Settings

```bash
# For fast-moving SKUs (fashion, daily deals):
helm upgrade inventory-service deploy/helm/inventory-service \
  -n inventory \
  --set cache.maxSize=20000 \
  --set cache.ttlMinutes=30 \
  --set cache.refreshInterval=5m
```

### Connection Pool

```bash
# For high concurrency (>1000 req/s):
kubectl set env deploy/inventory-service -n inventory \
  DB_POOL_SIZE=30 \
  DB_POOL_MAX_IDLE_TIME=600000
```

### Async Warehouse Checks

```bash
# Enable async fallback to improve latency
kubectl set env deploy/inventory-service -n inventory \
  WAREHOUSE_ASYNC_MODE=true \
  WAREHOUSE_CACHE_FALLBACK=true
```

---

## Monitoring & Alerting

### Key Metrics

```
# SKU availability check latency
histogram_quantile(0.99, rate(inventory_check_seconds_bucket[5m]))

# Reservation success rate
rate(inventory_reservations_total{status="success"}[5m]) / rate(inventory_reservations_total[5m])

# Cache hit rate
rate(cache_hits_total[5m]) / (rate(cache_hits_total[5m]) + rate(cache_misses_total[5m]))

# Database query latency
histogram_quantile(0.95, rate(db_query_seconds_bucket{service="inventory"}[5m]))
```

### Alert Rules

```yaml
- alert: InventoryServiceDown
  expr: up{job="inventory-service"} == 0
  for: 2m
  annotations:
    severity: critical

- alert: InventoryLatencyHigh
  expr: histogram_quantile(0.99, inventory_check_seconds) > 0.5
  for: 5m
  annotations:
    severity: high

- alert: CacheHitRateLow
  expr: cache_hit_rate < 0.5
  for: 10m
  annotations:
    severity: medium
```

---

## On-Call Handoff

- [ ] Cache hit rate trending? `kubectl exec -n inventory deploy/inventory-service -- curl -s http://localhost:8082/metrics/cache-hit-rate`
- [ ] Any warehouse-service issues affecting inventory? `kubectl logs -n warehouse deploy/warehouse-service --tail=20`
- [ ] Reservation conflicts or deadlocks? `kubectl exec -n inventory deploy/inventory-service -- curl -s http://localhost:8082/v1/admin/locks`
- [ ] Memory usage stable? `kubectl top pod -n inventory`

---

## Related Documentation

- **Deployment**: `/deploy/helm/inventory-service/`
- **Architecture**: `/docs/services/inventory-service/README.md`
- **Alert Rules**: `/monitoring/prometheus/inventory-rules.yaml`
