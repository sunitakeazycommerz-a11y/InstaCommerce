# Catalog Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify catalog-db (PostgreSQL) migrations current
  ```bash
  kubectl exec -n engagement deploy/catalog-service -- \
    curl -s http://localhost:8110/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check Elasticsearch connectivity (product search index)
  ```bash
  kubectl exec -n engagement deploy/catalog-service -- \
    curl -s http://localhost:8110/actuator/health | jq '.components.elasticsearch'
  ```

- [ ] Verify Redis cache is available
  ```bash
  kubectl exec -n engagement deploy/catalog-service -- \
    curl -s http://localhost:8110/actuator/health | jq '.components.redis'
  ```

- [ ] Check Kafka connectivity (product.events topic)
  ```bash
  kubectl exec -n engagement deploy/catalog-service -- \
    curl -s http://localhost:8110/actuator/health | jq '.components.kafka'
  ```

- [ ] Validate product count in index (should match database)
  ```bash
  kubectl exec -n engagement deploy/catalog-service -- \
    curl -s http://localhost:8110/metrics/elasticsearch-product-count | jq '.value'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Elasticsearch index sync status
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/metrics/elasticsearch-sync-lag | jq '.value'

# Current replica count
kubectl get deploy catalog-service -n engagement -o jsonpath='{.spec.replicas}'

# Cache hit rate (should be 80%+)
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/metrics/cache-hit-rate | jq '.value'
```

### Blue-Green Deployment

```bash
# 1. Version check
kubectl get deploy -n engagement catalog-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Upgrade
helm upgrade catalog-service deploy/helm/catalog-service \
  -n engagement \
  --values deploy/helm/values.yaml

# 3. Monitor rollout
kubectl rollout status deploy/catalog-service -n engagement --timeout=10m

# 4. Verify Elasticsearch sync recovered
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/metrics/elasticsearch-sync-lag | jq '.value'

# 5. Verify cache warmed up
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/metrics/cache-hit-rate | jq '.value'
```

### Rollback

```bash
helm rollback catalog-service 1 -n engagement
kubectl rollout status deploy/catalog-service -n engagement --timeout=5m
```

---

## Post-Deployment Validation

### Service Startup

```bash
kubectl logs -n engagement deploy/catalog-service --tail=30 | head -10
kubectl exec -n engagement deploy/catalog-service -- netstat -tlnp | grep 8110
```

### Smoke Tests

```bash
POD=$(kubectl get pods -n engagement -l app=catalog-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec -n engagement pod/$POD -- curl -s http://localhost:8110/actuator/health | jq '.status'

# 2. Get product by ID
kubectl exec -n engagement pod/$POD -- \
  curl -s "http://localhost:8110/v1/products/PROD-001" | jq '.id'

# 3. Search products
kubectl exec -n engagement pod/$POD -- \
  curl -s "http://localhost:8110/v1/search?q=test&limit=5" | jq '.products | length'

# 4. Create product (admin)
kubectl exec -n engagement pod/$POD -- \
  curl -X POST http://localhost:8110/v1/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer admin-token" \
  -d '{"id":"test-prod","name":"Test"}' | jq '.id'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

**Load balancer removal triggers**: Elasticsearch down, database down, Kafka consumer not joined

---

## Incident Response Procedures

### P0 - Product Search Down

**Symptom**: Search queries failing or returning empty, product listing broken

**Diagnosis**:
```bash
# Check Elasticsearch
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/actuator/health | jq '.components.elasticsearch'

# Check database
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/actuator/health/db | jq '.status'

# Test product lookup
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s "http://localhost:8110/v1/products/PROD-001"

# Check Elasticsearch cluster health
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/v1/admin/elasticsearch-health | jq '.'
```

**Resolution**:
1. Scale up: `kubectl scale deploy catalog-service -n engagement --replicas=5`
2. If Elasticsearch down: Check Elasticsearch cluster or failover
3. If database down: Check PostgreSQL cluster
4. Restart pod: `kubectl rollout restart deploy/catalog-service -n engagement`

### P1 - Search Latency High (>500ms) or Results Stale

**Diagnosis**:
```bash
# Check Elasticsearch sync lag
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/metrics/elasticsearch-sync-lag | jq '.value'

# Check search latency
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/metrics/search-latency-p99 | jq '.value'
```

**Resolution**:
1. If sync lag high: Trigger manual sync or increase indexing throughput
2. Scale up replicas
3. Check Elasticsearch cluster load

---

## Common Issues & Troubleshooting

### Issue: Elasticsearch Index Out of Sync

```bash
# Check sync status
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/metrics/elasticsearch-sync-lag | jq '.value'

# Trigger manual re-indexing
kubectl exec -n engagement deploy/catalog-service -- \
  curl -X POST http://localhost:8110/v1/admin/reindex \
  -H "Authorization: Bearer admin-token" | jq '.status'

# Monitor progress
kubectl logs -n engagement deploy/catalog-service | grep "reindex"
```

### Issue: Cache Eviction High

```bash
# Monitor cache
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8110/metrics/cache-evictions-total | jq '.value'

# Increase cache size
helm upgrade catalog-service deploy/helm/catalog-service \
  -n engagement \
  --set cache.maxSize=100000
```

---

## Performance Tuning

### Cache Configuration

```bash
helm upgrade catalog-service deploy/helm/catalog-service \
  -n engagement \
  --set cache.ttlMinutes=30 \
  --set cache.maxSize=50000
```

---

## Monitoring & Alerting

### Key Metrics

```
# Product search latency
histogram_quantile(0.99, rate(catalog_search_seconds_bucket[5m]))

# Elasticsearch sync lag
catalog_elasticsearch_sync_lag_seconds

# Search result accuracy (relevant results / total)
catalog_search_relevance_ratio
```

---

## On-Call Handoff

- [ ] Elasticsearch sync lag? `kubectl exec -n engagement deploy/catalog-service -- curl -s http://localhost:8110/metrics/elasticsearch-sync-lag`
- [ ] Cache hit rate? `kubectl exec -n engagement deploy/catalog-service -- curl -s http://localhost:8110/metrics/cache-hit-rate`

---

## Related Documentation

- **Deployment**: `/deploy/helm/catalog-service/`
- **Architecture**: `/docs/services/catalog-service/README.md`
