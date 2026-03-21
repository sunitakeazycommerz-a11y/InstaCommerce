# Warehouse Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify warehouse-db (PostgreSQL) is accessible
  ```bash
  kubectl exec -n warehouse deploy/warehouse-service -- \
    curl -s http://localhost:8085/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check inventory-service connectivity (synchronous SKU checks)
  ```bash
  kubectl exec -n warehouse deploy/warehouse-service -- \
    curl -v http://inventory-service:8082/health
  ```

- [ ] Verify geospatial queries are working (Redis GEO commands)
  ```bash
  kubectl exec -n warehouse deploy/warehouse-service -- \
    curl -s http://localhost:8085/actuator/health | jq '.components.redis'
  ```

- [ ] Validate capacity data loaded and current
  ```bash
  kubectl exec -n warehouse deploy/warehouse-service -- \
    curl -s http://localhost:8085/v1/warehouses?limit=5 \
    -H "Authorization: Bearer admin-token" | jq '.warehouses | length'
  ```

- [ ] Check all regions have at least one warehouse available
  ```bash
  kubectl exec -n warehouse deploy/warehouse-service -- \
    curl -s http://localhost:8085/v1/admin/warehouse-coverage | jq '.regions'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Current replica count
kubectl get deploy warehouse-service -n warehouse -o jsonpath='{.spec.replicas}'

# Warehouse availability check latency (should be <100ms)
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/metrics/availability-check-latency-p99 | jq '.value'

# Current load (requests/sec)
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/metrics/requests-per-sec | jq '.value'
```

### Blue-Green Deployment

```bash
# 1. Version check
kubectl get deploy -n warehouse warehouse-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Upgrade
helm upgrade warehouse-service deploy/helm/warehouse-service \
  -n warehouse \
  --values deploy/helm/values.yaml \
  --values deploy/helm/values-prod.yaml

# 3. Monitor rollout
kubectl rollout status deploy/warehouse-service -n warehouse --timeout=10m

# 4. Verify geospatial queries still working (no query plan regression)
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/metrics/geo-query-latency-p99 | jq '.value'

# 5. Verify coverage still 100%
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/v1/admin/warehouse-coverage | jq '.coverage_percent'
```

### Rollback

```bash
helm rollback warehouse-service 1 -n warehouse
kubectl rollout status deploy/warehouse-service -n warehouse --timeout=5m

# Verify coverage maintained
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/v1/admin/warehouse-coverage | jq '.coverage_percent'
```

---

## Post-Deployment Validation

### Service Startup

```bash
# Check Spring Boot startup logs
kubectl logs -n warehouse deploy/warehouse-service --tail=30

# Verify port listening
kubectl exec -n warehouse deploy/warehouse-service -- \
  netstat -tlnp | grep 8085
```

### Smoke Tests

```bash
POD=$(kubectl get pods -n warehouse -l app=warehouse-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec -n warehouse pod/$POD -- \
  curl -s http://localhost:8085/actuator/health | jq '.status'

# 2. Test nearest warehouse lookup (geospatial)
kubectl exec -n warehouse pod/$POD -- \
  curl -s "http://localhost:8085/v1/warehouses/nearest?lat=40.7128&lng=-74.0060" \
  -H "Authorization: Bearer test-token" | jq '.warehouse.id'

# 3. Test warehouse capacity check
kubectl exec -n warehouse pod/$POD -- \
  curl -s "http://localhost:8085/v1/warehouses/WH-001/capacity" \
  -H "Authorization: Bearer test-token" | jq '.available'
```

### Database & Cache Validation

```bash
# Database health
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/actuator/health/db | jq '.components.db'

# Redis geo index health
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/actuator/health | jq '.components.redis'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

**Load balancer removal triggers**: Redis unavailable, geospatial index corrupt

---

## Incident Response Procedures

### P0 - Warehouse Lookup Down

**Symptom**: Inventory and fulfillment services unable to find warehouses

**Diagnosis**:
```bash
# Check pod status
kubectl get pods -n warehouse -l app=warehouse-service

# Check logs
kubectl logs -n warehouse deploy/warehouse-service --tail=100 | grep -i "error\|exception"

# Test geospatial query
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s "http://localhost:8085/v1/warehouses/nearest?lat=0&lng=0" -w "\n"

# Check Redis GEO index
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/v1/admin/geo-index-health | jq '.status'

# Check database
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/actuator/health/db | jq '.status'
```

**Resolution**:
1. Scale up: `kubectl scale deploy warehouse-service -n warehouse --replicas=5`
2. If Redis GEO index corrupt: Rebuild from database
3. If database down: Check PostgreSQL cluster

### P1 - High Latency (>500ms for queries)

**Diagnosis**:
```bash
# Check query latency
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/metrics/geo-query-latency-p99 | jq '.value'

# Check database query performance
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/metrics/db-query-latency-p99 | jq '.value'

# Check if indices are present
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/v1/admin/db-indices | jq '.indices'
```

**Resolution**:
1. If database query slow: Add missing indices or update statistics
2. Scale up replicas
3. Check for long-running queries locking tables

### P2 - Partial Coverage Loss

**Diagnosis**:
```bash
# Check coverage percentage
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/v1/admin/warehouse-coverage | jq '.regions'

# Identify uncovered regions
jq '.regions[] | select(.coverage_percent < 100)' <coverage-output>
```

**Resolution**:
1. Activate backup warehouse for region
2. Check if warehouse is down or capacity exhausted
3. Scale up capacity or add new warehouse

---

## Common Issues & Troubleshooting

### Issue: Redis GEO Index Out of Sync

```bash
# Check consistency
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/v1/admin/geo-index-consistency | jq '.status'

# If inconsistent, rebuild:
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -X POST http://localhost:8085/v1/admin/rebuild-geo-index \
  -H "Authorization: Bearer admin-token" | jq '.status'

# Monitor rebuild progress
kubectl logs -n warehouse deploy/warehouse-service | grep "rebuild"
```

### Issue: Database Indices Missing

```bash
# Check indices
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/v1/admin/db-indices | jq '.missing_indices'

# Create indices (direct SQL or migration)
kubectl exec -n warehouse -i deploy/warehouse-service -- \
  psql -U postgres -d warehouse_db -c "CREATE INDEX idx_warehouse_location ON warehouses(ST_GeographyFromText(CONCAT('SRID=4326;POINT(', longitude, ' ', latitude, ')')));"

# Restart service to pick up indices
kubectl rollout restart deploy/warehouse-service -n warehouse
```

### Issue: Capacity Exhaustion in Region

```bash
# Check capacity by region
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/v1/admin/capacity-by-region | jq '.regions | map(select(.available_capacity == 0))'

# Alert other services to reduce load or reroute orders
# Activate backup warehouse
kubectl patch -n warehouse deploy warehouse-service \
  -p '{"spec":{"template":{"spec":{"env":[{"name":"BACKUP_WAREHOUSE_ENABLED","value":"true"}]}}}}'
```

### Issue: Nearest Warehouse Query Wrong Results

```bash
# Debug geospatial query
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s "http://localhost:8085/v1/admin/debug-nearest?lat=40.7128&lng=-74.0060" \
  -H "Authorization: Bearer admin-token" | jq '.query_plan'

# Check coordinate format (should be lat,lng in decimal degrees)
# Verify warehouse coordinates are valid
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8085/v1/admin/coordinates | jq '.warehouses[] | select(.lat > 90 or .lng > 180)'
```

---

## Performance Tuning

### Database Indices

```bash
# For frequently searched regions:
CREATE INDEX idx_warehouse_region ON warehouses(region_id);
CREATE INDEX idx_warehouse_capacity ON warehouses(available_capacity);
```

### Geospatial Query Optimization

```bash
# Enable PostGIS caching in Redis
helm upgrade warehouse-service deploy/helm/warehouse-service \
  -n warehouse \
  --set redis.cache.ttlMinutes=60 \
  --set geo.cache.maxSize=50000
```

---

## Monitoring & Alerting

### Key Metrics

```
# Warehouse lookup latency
histogram_quantile(0.99, rate(warehouse_lookup_seconds_bucket[5m]))

# Regional coverage percentage
warehouse_coverage_percent{region=~".*"}

# Geospatial query errors
rate(warehouse_geo_query_errors_total[5m])
```

---

## On-Call Handoff

- [ ] Coverage percentage? `kubectl exec -n warehouse deploy/warehouse-service -- curl -s http://localhost:8085/v1/admin/warehouse-coverage`
- [ ] Any GEO index issues? `kubectl exec -n warehouse deploy/warehouse-service -- curl -s http://localhost:8085/v1/admin/geo-index-health`
- [ ] Database indices present? `kubectl exec -n warehouse deploy/warehouse-service -- curl -s http://localhost:8085/v1/admin/db-indices`

---

## Related Documentation

- **Deployment**: `/deploy/helm/warehouse-service/`
- **Architecture**: `/docs/services/warehouse-service/README.md`
