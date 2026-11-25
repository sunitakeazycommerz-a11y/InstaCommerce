# Catalog Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify catalog-db (PostgreSQL) migrations current
  ```bash
  kubectl exec -n default deploy/catalog-service -- \
    curl -s http://localhost:8082/actuator/health/readiness | jq '.components.db.status'
  ```

- [ ] Verify Redis cache is available
  ```bash
  kubectl exec -n default deploy/catalog-service -- \
    curl -s http://localhost:8082/actuator/health/readiness | jq '.components.redis'
  ```

- [ ] Check Kafka connectivity (catalog.events topic)
  ```bash
  kubectl exec -n default deploy/catalog-service -- \
    curl -s http://localhost:8082/actuator/health/readiness | jq '.components.kafka'
  ```

- [ ] Validate product catalog & outbox health
  ```bash
  kubectl exec -n engagement deploy/catalog-service -- \
    curl -s http://localhost:8082/actuator/health/readiness | jq '.status'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Database and outbox connectivity
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8082/actuator/health/readiness | jq '.components.db.status'

# Current replica count
kubectl get deploy catalog-service -n engagement -o jsonpath='{.spec.replicas}'

# Cache metrics (Caffeine / Micrometer)
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8082/actuator/metrics/cache.gets | jq '.measurements'
```

### Blue-Green Deployment

```bash
# 1. Version check
kubectl get deploy -n engagement catalog-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Upgrade
helm upgrade instacommerce deploy/helm \
  -n engagement \
  --values deploy/helm/values.yaml

# 3. Monitor rollout
kubectl rollout status deploy/catalog-service -n engagement --timeout=10m

# 4. Verify readiness recovered
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8082/actuator/health/readiness | jq '.status'

# 5. Verify cache metrics active
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8082/actuator/metrics/cache.gets | jq '.measurements'
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
kubectl exec -n engagement deploy/catalog-service -- netstat -tlnp | grep 8082
```

### Smoke Tests

```bash
POD=$(kubectl get pods -n engagement -l app=catalog-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec -n engagement pod/$POD -- curl -s http://localhost:8082/actuator/health | jq '.status'

# 2. Get product by ID
kubectl exec -n engagement pod/$POD -- \
  curl -s "http://localhost:8082/v1/products/PROD-001" | jq '.id'

# 3. Search products
kubectl exec -n engagement pod/$POD -- \
  curl -s "http://localhost:8082/v1/search?q=test&limit=5" | jq '.products | length'

# 4. Create product (admin)
kubectl exec -n engagement pod/$POD -- \
  curl -X POST http://localhost:8082/v1/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer admin-token" \
  -d '{"id":"test-prod","name":"Test"}' | jq '.id'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

**Load balancer removal triggers**: database down, Redis cache unreachable, or readiness probe failure

---

## Incident Response Procedures

### P0 - Product Catalog / Search Read Path Down

**Symptom**: Product queries or search projections failing or returning empty

**Diagnosis**:
```bash
# Check catalog-service readiness & database
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8082/actuator/health/readiness | jq '.components.db'

# Test product lookup
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s "http://localhost:8082/v1/products/PROD-001"

# Check downstream search-service (PostgreSQL tsvector FTS)
kubectl exec -n engagement deploy/search-service -- \
  curl -s http://localhost:8082/actuator/health/readiness | jq '.'
```

**Resolution**:
1. Scale up: `kubectl scale deploy catalog-service -n engagement --replicas=5`
2. If database down: Check Cloud SQL PostgreSQL primary / read-replica health
3. If search projection stale: Check `outbox_events` backlog and `search-service` (`8088`) Kafka consumer lag
4. Restart pod: `kubectl rollout restart deploy/catalog-service -n engagement`

### P1 - Read Latency High (>500ms) or Search Results Stale

**Diagnosis**:
```bash
# Check HTTP request latency metrics
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8082/actuator/metrics/http.server.requests | jq '.measurements'

# Check search-service readiness (PostgreSQL FTS)
kubectl exec -n engagement deploy/search-service -- \
  curl -s http://localhost:8082/actuator/health/readiness | jq '.'
```

**Resolution**:
1. Check `outbox-relay-service` (`8103`) lag for `catalog.events` propagation to `search-service`
2. Scale up replicas
3. Inspect PostgreSQL query latency and Caffeine cache hit ratio

---

## Common Issues & Troubleshooting

### Issue: Search Projection Out of Sync

```bash
# Check outbox-relay health and Kafka consumer lag on catalog.events
kubectl exec -n engagement deploy/search-service -- \
  curl -s http://localhost:8082/actuator/health/readiness | jq '.'

# Check catalog-service logs for outbox writes
kubectl logs -n engagement deploy/catalog-service --tail=100 | grep -i "outbox\|catalog.events"
```

### Issue: Cache Eviction High

```bash
# Monitor Caffeine cache evictions
kubectl exec -n engagement deploy/catalog-service -- \
  curl -s http://localhost:8082/actuator/metrics/cache.evictions | jq '.measurements'

# Update Helm cache values via shared chart
helm upgrade instacommerce deploy/helm \
  -n engagement \
  --values deploy/helm/values.yaml
```

---

## Performance Tuning

### Cache Configuration

```bash
helm upgrade instacommerce deploy/helm \
  -n engagement \
  --values deploy/helm/values.yaml
```

---

## Monitoring & Alerting

### Key Metrics

```
# Catalog HTTP p99 latency
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{service="catalog-service"}[5m]))

# Outbox relay backlog (catalog_db)
outbox_events_pending{service="catalog-service"}

# Caffeine cache hit ratio
rate(cache_gets_total{service="catalog-service",result="hit"}[5m]) / rate(cache_gets_total{service="catalog-service"}[5m])
```

---

## On-Call Handoff

- [ ] Database & readiness OK? `kubectl exec -n engagement deploy/catalog-service -- curl -s http://localhost:8082/actuator/health/readiness`
- [ ] Cache metrics active? `kubectl exec -n engagement deploy/catalog-service -- curl -s http://localhost:8082/actuator/metrics/cache.gets`

---

## Related Documentation

- **Deployment**: [`deploy/helm/`](../../../deploy/helm/)
- **Architecture**: [`docs/services/catalog-service/README.md`](README.md)
