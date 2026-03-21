# Search Service - Deployment & Runbook

## Deployment Checklist

### Pre-Deployment

- [ ] All tests passing (`./gradlew test integrationTest`)
- [ ] Code review approved
- [ ] Database migrations verified (Flyway)
- [ ] Load test results acceptable (p99 < 200ms at 5K QPS)
- [ ] Rollback plan documented
- [ ] On-call engineer on standby

### Deployment Steps

#### 1. Build & Push Image

```bash
# Build JAR
./gradlew :services:search-service:build

# Build Docker image
cd services/search-service
docker build -t gcr.io/instacommerce/search-service:v1.2.3 .
docker push gcr.io/instacommerce/search-service:v1.2.3

# Tag as latest for convenience
docker tag gcr.io/instacommerce/search-service:v1.2.3 \
           gcr.io/instacommerce/search-service:latest
docker push gcr.io/instacommerce/search-service:latest
```

#### 2. Apply Database Migrations

```bash
# Flyway automatically applies migrations on startup
# Verify migrations in staging first:
kubectl port-forward -n staging search-service-0 5432:5432
psql -h localhost -U postgres -d search -c "SELECT * FROM flyway_schema_history;"

# Check for V-prefixed migration files
ls services/search-service/src/main/resources/db/migration/V*.sql
```

#### 3. Update Kubernetes Deployment

```bash
# Rolling update (default: 1 pod at a time)
kubectl set image deployment/search-service \
  search-service=gcr.io/instacommerce/search-service:v1.2.3 \
  -n default \
  --record  # Tracks deployment history

# Monitor rollout
kubectl rollout status deployment/search-service -n default
kubectl get pods -n default | grep search-service

# Expected output:
# search-service-abc123-xyz   1/1     Running   0     5m
# search-service-def456-uvw   1/1     Running   0     3m
# search-service-ghi789-rst   1/1     Running   0     1m
```

#### 4. Verify Deployment

```bash
# Check pod logs
kubectl logs -f deployment/search-service -n default --tail=100

# Expected: "Started SearchServiceApplication"

# Check health probes
for i in {1..3}; do
  kubectl port-forward -n default \
    search-service-$i 8086:8086 &>/dev/null &
  curl -s http://localhost:8086/actuator/health/ready | jq .status
  kill %1
done

# Expected: "UP" for all pods

# Verify metrics are being exported
kubectl port-forward -n default search-service-0 8086:8086 &>/dev/null &
curl -s http://localhost:8086/actuator/metrics | jq .names | head -10
kill %1
```

#### 5. Smoke Testing

```bash
# Test search endpoint
kubectl exec -it search-service-0 -n default -- bash -c \
  'curl -X GET http://localhost:8086/search?query=milk'

# Expected: 200 OK with search results

# Test autocomplete
curl -X GET "http://localhost:8086/search/autocomplete?prefix=mir"

# Test trending
curl -X GET "http://localhost:8086/search/trending?limit=10"

# Expected: All return 200 with valid responses
```

---

## Rollback Procedure

### Quick Rollback (< 5 min downtime)

```bash
# Get previous rollout revision
kubectl rollout history deployment/search-service -n default

# Revision 10  (current):  search-service:v1.2.3
# Revision 9   (previous): search-service:v1.2.2

# Rollback to previous revision
kubectl rollout undo deployment/search-service -n default

# Monitor rollback
kubectl rollout status deployment/search-service -n default

# Verify
kubectl get deployment search-service -n default
```

### Rollback to Specific Revision

```bash
# If multiple revisions back needed
kubectl rollout undo deployment/search-service -n default \
  --to-revision=8

# Verify old version running
kubectl get pods -o jsonpath='{.items[*].spec.containers[0].image}' \
  -l app=search-service
```

### Database Rollback (Migrations)

Flyway doesn't provide automatic rollback. If migration introduced breaking change:

```bash
# Option 1: Manual SQL fix (if DDL change)
kubectl exec -it search-service-0 -n default -- \
  psql -h postgres.default -U postgres -d search -c \
  "DROP COLUMN new_column FROM search_documents;"

# Option 2: Delete migration file, re-deploy (if not yet in prod)
rm services/search-service/src/main/resources/db/migration/V6__*.sql
git commit -am "Revert migration V6"

# Option 3: Restore from backup (if data corruption)
# Contact DBA team for backup recovery
```

---

## Monitoring & Alerting

### Key Metrics Dashboard

```
Title: Search Service Health

Panels:
1. Query Latency (p50, p99, p99.9)
   - Alert if p99 > 200ms for 5min

2. Error Rate
   - Alert if > 1% for 2min

3. Cache Hit Rate
   - Alert if < 85% for 5min

4. Kafka Consumer Lag
   - Alert if > 5000 messages for 5min

5. Database Pool Usage
   - Alert if > 40 active connections for 5min

6. JVM Memory
   - Alert if heap > 1.5Gi for 2min

7. Pod CPU Usage
   - Alert if > 800m for 5min

8. Pod Memory Usage
   - Alert if > 1.8Gi for 5min
```

### Alerting Rules

```yaml
# prometheus_rules.yaml
groups:
- name: search-service
  interval: 30s
  rules:
  - alert: SearchServiceHighLatency
    expr: histogram_quantile(0.99, search_query_duration_ms) > 200
    for: 5m
    action: page

  - alert: SearchServiceHighErrorRate
    expr: rate(search_query_errors_total[5m]) > 0.01
    for: 2m
    action: page

  - alert: KafkaConsumerLagHigh
    expr: kafka_consumer_lag > 5000
    for: 5m
    action: page

  - alert: CacheHitRateLow
    expr: cache_caffeine_hitRate < 0.85
    for: 5m
    action: page

  - alert: PostgresConnectionPoolSaturation
    expr: db_hikari_connections_active > 40
    for: 5m
    action: page
```

---

## Scaling

### Horizontal Scaling (Add Replicas)

```bash
# Current: 3 replicas
# Need to scale to 5 during peak hours

kubectl scale deployment search-service \
  --replicas=5 \
  -n default

# Monitor pod startup
kubectl get pods -w -l app=search-service

# Verify load distribution
for i in {1..5}; do
  requests=$(kubectl logs -n default search-service-$i | wc -l)
  echo "Pod $i: $requests requests"
done
```

### Vertical Scaling (Increase Resources)

```bash
# If single pod can't handle load (CPU/memory limited)

# Edit deployment
kubectl edit deployment search-service -n default

# Change resources section:
# resources:
#   requests:
#     cpu: 500m      → 1000m
#     memory: 1Gi    → 2Gi
#   limits:
#     cpu: 1000m     → 2000m
#     memory: 2Gi    → 4Gi

# Save & auto-redeploy
```

---

## Maintenance Tasks

### Daily (Automated)

- Kafka lag monitoring
- Cache hit rate tracking
- Error rate baseline

### Weekly

```bash
# Check index fragmentation
psql search -c "SELECT round(100.0 * pg_relation_size('idx_search_documents_search_vector') / (pg_relation_size('search_documents') + pg_relation_size('idx_search_documents_search_vector')), 2) AS idx_ratio;"

# If > 30%: Consider REINDEX
```

### Monthly

```bash
# Update statistics
VACUUM ANALYZE search_documents;

# Purge trending queries older than 90 days
DELETE FROM trending_queries WHERE created_at < now() - interval '90 days';

# Check slow query log
SELECT query, mean_exec_time, calls FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 10;
```

### Quarterly

- Load testing (5K, 10K, 50K QPS)
- Disaster recovery drill (data restoration)
- Security audit (access logs)
- Cost analysis (resource optimization)

---

## Troubleshooting Quick Reference

| Issue | Command | Expected Output |
|-------|---------|-----------------|
| Pod stuck | `kubectl describe pod search-service-0` | Check Events section |
| DB connection | `kubectl logs search-service-0 \| grep -i "database"` | No "cannot connect" errors |
| Kafka lag | `kafka-consumer-groups --describe` | LAG column < 1000 |
| Cache effectiveness | `curl /actuator/metrics/cache.caffeine.hitRate` | > 0.90 |
| Memory leak | `kubectl top pods -l app=search-service` | Stable memory usage |

---

## On-Call Escalation

**Level 1: Application** (Search Service Team)
- High latency
- Cache ineffectiveness
- Search result quality issues

**Level 2: Infrastructure** (Database/Kafka Team)
- Database slowness
- Kafka lag spike
- PostgreSQL connection exhaustion

**Level 3: Management** (Escalation after 30min)
- Service degradation
- Data loss
- Security incident

---

## Contact & Support

- **On-call:** search-service-oncall@instacommerce.com
- **Slack:** #search-service
- **Docs:** /docs/services/search-service
- **Status page:** status.instacommerce.com/search-service

