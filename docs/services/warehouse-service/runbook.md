# Warehouse Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify warehouse-db (PostgreSQL) is accessible
  ```bash
  kubectl exec deploy/warehouse-service -- \
    curl -s http://localhost:8092/actuator/health/readiness | jq '.components.db.status'
  ```

- [ ] Check inventory-service connectivity (synchronous dark store / SKU checks)
  ```bash
  kubectl exec deploy/warehouse-service -- \
    curl -v http://inventory-service:8083/actuator/health/readiness
  ```

- [ ] Verify JVM Caffeine near-cache metrics
  ```bash
  kubectl exec deploy/warehouse-service -- \
    curl -s http://localhost:8092/actuator/metrics/jvm.memory.used
  ```

- [ ] Validate dark store active count and capacity data
  ```bash
  kubectl exec deploy/warehouse-service -- \
    curl -s "http://localhost:8092/admin/stores?status=ACTIVE" \
    -H "Authorization: Bearer admin-token" | jq 'length'
  ```

- [ ] Verify Kafka topic availability (warehouse.events)
  ```bash
  kubectl exec deploy/warehouse-service -- \
    curl -s http://localhost:8092/actuator/health/readiness | jq '.components.kafka'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Current replica count
kubectl get deploy warehouse-service -o jsonpath='{.spec.replicas}'

# Nearest store query latency
kubectl exec deploy/warehouse-service -- \
  curl -s http://localhost:8092/actuator/metrics/http.server.requests | jq '.measurements'
```

### Blue-Green Deployment

```bash
# 1. Version check
kubectl get deploy warehouse-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Upgrade
helm upgrade warehouse-service deploy/helm/warehouse-service \
  --values deploy/helm/values.yaml

# 3. Monitor rollout
kubectl rollout status deploy/warehouse-service --timeout=10m

# 4. Verify nearest store query latency
kubectl exec deploy/warehouse-service -- \
  curl -s "http://localhost:8092/stores/nearest?lat=12.9716&lng=77.6412&radiusKm=10"
```

### Rollback

```bash
helm rollback warehouse-service 1
kubectl rollout status deploy/warehouse-service --timeout=5m
```

---

## Post-Deployment Validation

### Service Startup

```bash
# Check Spring Boot startup logs
kubectl logs deploy/warehouse-service --tail=30

# Verify port listening
kubectl exec deploy/warehouse-service -- \
  netstat -tlnp | grep 8092
```

### Smoke Tests

```bash
POD=$(kubectl get pods -l app=warehouse-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec pod/$POD -- \
  curl -s http://localhost:8092/actuator/health/readiness | jq '.status'

# 2. Test nearest dark store lookup (Haversine distance)
kubectl exec pod/$POD -- \
  curl -s "http://localhost:8092/stores/nearest?lat=12.9716&lng=77.6412&radiusKm=10" | jq '.[0].id'

# 3. Test dark store current capacity check
STORE_ID=$(kubectl exec pod/$POD -- curl -s "http://localhost:8092/stores/nearest?lat=12.9716&lng=77.6412&radiusKm=10" | jq -r '.[0].id')
kubectl exec pod/$POD -- \
  curl -s "http://localhost:8092/stores/$STORE_ID/capacity" | jq .
```

### Database & Cache Validation

```bash
# Database health
kubectl exec deploy/warehouse-service -- \
  curl -s http://localhost:8092/actuator/health/readiness | jq '.components.db'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/liveness`

### Readiness Probe: `/actuator/health/readiness`

**Load balancer removal triggers**: Database connection lost, Hikari connection pool exhausted

---

## Incident Response Procedures

### P0 - Warehouse Lookup Down

**Symptom**: Mobile BFF and fulfillment services unable to locate nearest dark stores

**Diagnosis**:
```bash
# Check pod status
kubectl get pods -l app=warehouse-service

# Check logs
kubectl logs deploy/warehouse-service --tail=100 | grep -i "error\|exception"

# Test nearest store query
kubectl exec deploy/warehouse-service -- \
  curl -s "http://localhost:8092/stores/nearest?lat=12.9716&lng=77.6412&radiusKm=10" -w "\n"

# Check database readiness
kubectl exec deploy/warehouse-service -- \
  curl -s http://localhost:8092/actuator/health/readiness | jq '.status'
```

**Resolution**:
1. Scale up: `kubectl scale deploy warehouse-service --replicas=5`
2. If database down: Check PostgreSQL cluster and HikariCP connection limits
3. If queries slow: Ensure composite index on (latitude, longitude) is healthy

### P1 - High Latency (>500ms for queries)

**Diagnosis**:
```bash
# Check query latency
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8092/metrics/geo-query-latency-p99 | jq '.value'

# Check database query performance
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8092/metrics/db-query-latency-p99 | jq '.value'

# Check if indices are present
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8092/v1/admin/db-indices | jq '.indices'
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
  curl -s http://localhost:8092/v1/admin/warehouse-coverage | jq '.regions'

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
  curl -s http://localhost:8092/v1/admin/geo-index-consistency | jq '.status'

# If inconsistent, rebuild:
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -X POST http://localhost:8092/v1/admin/rebuild-geo-index \
  -H "Authorization: Bearer admin-token" | jq '.status'

# Monitor rebuild progress
kubectl logs -n warehouse deploy/warehouse-service | grep "rebuild"
```

### Issue: Database Indices Missing

```bash
# Check indices
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8092/v1/admin/db-indices | jq '.missing_indices'

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
  curl -s http://localhost:8092/v1/admin/capacity-by-region | jq '.regions | map(select(.available_capacity == 0))'

# Alert other services to reduce load or reroute orders
# Activate backup warehouse
kubectl patch -n warehouse deploy warehouse-service \
  -p '{"spec":{"template":{"spec":{"env":[{"name":"BACKUP_WAREHOUSE_ENABLED","value":"true"}]}}}}'
```

### Issue: Nearest Warehouse Query Wrong Results

```bash
# Debug geospatial query
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s "http://localhost:8092/v1/admin/debug-nearest?lat=40.7128&lng=-74.0060" \
  -H "Authorization: Bearer admin-token" | jq '.query_plan'

# Check coordinate format (should be lat,lng in decimal degrees)
# Verify warehouse coordinates are valid
kubectl exec -n warehouse deploy/warehouse-service -- \
  curl -s http://localhost:8092/v1/admin/coordinates | jq '.warehouses[] | select(.lat > 90 or .lng > 180)'
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

- [ ] Coverage percentage? `kubectl exec -n warehouse deploy/warehouse-service -- curl -s http://localhost:8092/v1/admin/warehouse-coverage`
- [ ] Any GEO index issues? `kubectl exec -n warehouse deploy/warehouse-service -- curl -s http://localhost:8092/v1/admin/geo-index-health`
- [ ] Database indices present? `kubectl exec -n warehouse deploy/warehouse-service -- curl -s http://localhost:8092/v1/admin/db-indices`

---

## Related Documentation

- **Deployment**: `/deploy/helm/warehouse-service/`
- **Architecture**: `/docs/services/warehouse-service/README.md`
