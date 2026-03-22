# Config Feature Flag Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify feature-flag-db (PostgreSQL) migrations current
  ```bash
  kubectl exec -n platform deploy/config-feature-flag-service -- \
    curl -s http://localhost:8095/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check Redis pub/sub connectivity (cache invalidation)
  ```bash
  kubectl exec -n platform deploy/config-feature-flag-service -- \
    curl -s http://localhost:8095/actuator/health | jq '.components.redis'
  ```

- [ ] Verify Kafka is accessible (flag.events topic for broadcasts)
  ```bash
  kubectl exec -n platform deploy/config-feature-flag-service -- \
    curl -s http://localhost:8095/actuator/health | jq '.components.kafka'
  ```

- [ ] Check admin-gateway-service connectivity (requires config-feature-flag endpoints)
  ```bash
  kubectl exec -n platform deploy/config-feature-flag-service -- \
    curl -v http://admin-gateway-service:8099/health
  ```

- [ ] Validate flag count loaded (should be 50+ flags in production)
  ```bash
  kubectl exec -n platform deploy/config-feature-flag-service -- \
    curl -s http://localhost:8095/metrics/flags-count | jq '.value'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Flag cache hit rate (typically 99%+ for internal lookups)
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/metrics/flag-cache-hit-rate | jq '.value'

# Current flag evaluation latency (p99 should be <10ms with cache)
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/metrics/flag-evaluation-latency-p99 | jq '.value'

# Current replica count
kubectl get deploy config-feature-flag-service -n platform -o jsonpath='{.spec.replicas}'
```

### Blue-Green Deployment

```bash
# 1. Version check
kubectl get deploy -n platform config-feature-flag-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Upgrade
helm upgrade config-feature-flag-service deploy/helm/config-feature-flag-service \
  -n platform \
  --values deploy/helm/values.yaml \
  --values deploy/helm/values-prod.yaml

# 3. Monitor rollout
kubectl rollout status deploy/config-feature-flag-service -n platform --timeout=10m

# 4. Verify cache hit rate recovered to 99%+
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/metrics/flag-cache-hit-rate | jq '.value'

# 5. Verify flag evaluation latency still <10ms
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/metrics/flag-evaluation-latency-p99 | jq '.value'
```

### Rollback

```bash
helm rollback config-feature-flag-service 1 -n platform
kubectl rollout status deploy/config-feature-flag-service -n platform --timeout=5m

# Verify cache recovered
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/metrics/flag-cache-hit-rate | jq '.value'
```

---

## Post-Deployment Validation

### Service Startup

```bash
# Check Spring Boot startup logs
kubectl logs -n platform deploy/config-feature-flag-service --tail=30

# Verify port listening
kubectl exec -n platform deploy/config-feature-flag-service -- \
  netstat -tlnp | grep 8095
```

### Smoke Tests

```bash
POD=$(kubectl get pods -n platform -l app=config-feature-flag-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec -n platform pod/$POD -- \
  curl -s http://localhost:8095/actuator/health | jq '.status'

# 2. Evaluate a flag
kubectl exec -n platform pod/$POD -- \
  curl -s "http://localhost:8095/v1/flags/test-flag/evaluate?userId=test-001" | jq '.enabled'

# 3. List all flags
kubectl exec -n platform pod/$POD -- \
  curl -s http://localhost:8095/v1/flags?limit=5 | jq '.flags | length'

# 4. Test admin endpoint (override flag)
kubectl exec -n platform pod/$POD -- \
  curl -X POST http://localhost:8095/admin/v1/flags/test-flag/override \
  -H "Authorization: Bearer admin-token" \
  -H "Content-Type: application/json" \
  -d '{"value":true,"ttlSeconds":600}' | jq '.status'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

**Load balancer removal triggers**: Database unavailable, Redis pub/sub connection lost

---

## Incident Response Procedures

### P0 - Flag Service Evaluation Down

**Symptom**: All services unable to evaluate flags, feature gates not working

**Diagnosis**:
```bash
# Check pod status
kubectl get pods -n platform -l app=config-feature-flag-service

# Check logs
kubectl logs -n platform deploy/config-feature-flag-service --tail=100 | grep -i "error\|exception"

# Test flag evaluation
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s "http://localhost:8095/v1/flags/test/evaluate"

# Check database
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/actuator/health/db | jq '.status'

# Check Redis
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/actuator/health | jq '.components.redis'
```

**Resolution**:
1. Scale up: `kubectl scale deploy config-feature-flag-service -n platform --replicas=5`
2. If database down: Check PostgreSQL cluster
3. If Redis down: Service degrades to in-memory cache (slower but functional)
4. Restart pod: `kubectl rollout restart deploy/config-feature-flag-service -n platform`

### P1 - Flag Updates Not Propagating (<500ms expected, >2s actual)

**Symptom**: Flag changes visible in API but not in consuming services, cache invalidation slow

**Diagnosis**:
```bash
# Check Redis pub/sub traffic
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/metrics/redis-pubsub-latency | jq '.value'

# Check flag update frequency
kubectl logs -n platform deploy/config-feature-flag-service --tail=100 | grep "flag.*updated"

# Check Kafka flag.events topic lag
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/metrics/kafka-lag | jq '.value'
```

**Resolution**:
1. If Redis pub/sub slow: Check Redis load, may need scaling
2. Check network latency between pods
3. Increase consumer concurrency: `kubectl set env deploy/config-feature-flag-service -n platform KAFKA_CONCURRENCY=5`

### P2 - Cache Hit Rate Low (<95%)

**Diagnosis**:
```bash
# Check cache stats
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/metrics/flag-cache-stats | jq '.'

# Check if many unique flags being evaluated
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/metrics/unique-flags-evaluated | jq '.value'
```

**Resolution**:
1. Increase cache size: `helm upgrade ... --set cache.maxSize=10000`
2. Increase TTL: `helm upgrade ... --set cache.ttlSeconds=3600`
3. Analyze flag access patterns

---

## Common Issues & Troubleshooting

### Issue: Flag Override Not Taking Effect

```bash
# Check override exists
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/admin/v1/flags/test-flag/overrides | jq '.overrides'

# Clear override cache
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -X POST http://localhost:8095/admin/v1/cache/clear \
  -H "Authorization: Bearer admin-token" | jq '.status'

# Verify update propagated via pub/sub
kubectl logs -n platform deploy/config-feature-flag-service | grep "override.*published"
```

### Issue: Redis Connection Failures

```bash
# Test Redis connectivity
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -v http://localhost:6379

# Check Redis in-memory cache as fallback
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/v1/flags/test-flag/evaluate | jq '.cached_from'

# If Redis down, service still works but slower (in-memory cache only)
```

### Issue: Flag Cache Stale After Update

```bash
# Check cache TTL for this flag
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -s http://localhost:8095/admin/v1/flags/test-flag/cache-info | jq '.ttl_remaining'

# Force cache refresh
kubectl exec -n platform deploy/config-feature-flag-service -- \
  curl -X POST http://localhost:8095/admin/v1/flags/test-flag/refresh \
  -H "Authorization: Bearer admin-token" | jq '.status'
```

---

## Performance Tuning

### Cache Settings

```bash
# For very frequently evaluated flags (>10K req/s):
helm upgrade config-feature-flag-service deploy/helm/config-feature-flag-service \
  -n platform \
  --set cache.caffeine.maxSize=20000 \
  --set cache.caffeine.ttlSeconds=3600 \
  --set cache.refresh.intervalSeconds=300
```

### Kafka Consumer

```bash
# For high-frequency flag updates:
kubectl set env deploy/config-feature-flag-service -n platform \
  KAFKA_CONCURRENCY=5 \
  KAFKA_MAX_POLL_RECORDS=1000
```

---

## Monitoring & Alerting

### Key Metrics

```
# Flag evaluation latency
histogram_quantile(0.99, rate(flag_evaluation_seconds_bucket[5m]))

# Cache hit rate
rate(flag_cache_hits_total[5m]) / (rate(flag_cache_hits_total[5m]) + rate(flag_cache_misses_total[5m]))

# Cache invalidation latency (should be <500ms)
histogram_quantile(0.99, rate(cache_invalidation_seconds_bucket[5m]))

# Flag override count
config_flag_overrides_count
```

---

## On-Call Handoff

- [ ] Flag cache hit rate? `kubectl exec -n platform deploy/config-feature-flag-service -- curl -s http://localhost:8095/metrics/flag-cache-hit-rate`
- [ ] Any failed flag evaluations? `kubectl logs -n platform deploy/config-feature-flag-service --tail=50 | grep error`
- [ ] Redis pub/sub latency acceptable? `kubectl exec -n platform deploy/config-feature-flag-service -- curl -s http://localhost:8095/metrics/redis-pubsub-latency`

---

## Related Documentation

- **Deployment**: `/deploy/helm/config-feature-flag-service/`
- **Architecture**: `/docs/services/config-feature-flag-service/README.md`
- **Alert Rules**: `/monitoring/prometheus/config-feature-flag-rules.yaml`
