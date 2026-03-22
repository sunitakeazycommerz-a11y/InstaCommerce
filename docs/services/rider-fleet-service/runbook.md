# Rider Fleet Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify rider-fleet-db (PostgreSQL) is accessible
  ```bash
  kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
    curl -s http://localhost:8087/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check Redis connectivity (rider location tracking)
  ```bash
  kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
    curl -s http://localhost:8087/actuator/health | jq '.components.redis'
  ```

- [ ] Verify Kafka connectivity (rider.location, rider.status topics)
  ```bash
  kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
    curl -s http://localhost:8087/actuator/health | jq '.components.kafka'
  ```

- [ ] Check fulfillment-service connectivity (delivery assignment)
  ```bash
  kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
    curl -v http://fulfillment-service:8084/health
  ```

- [ ] Validate rider availability (should be >50 active riders in production)
  ```bash
  kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
    curl -s http://localhost:8087/metrics/active-riders-count | jq '.value'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Active riders
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/active-riders-count | jq '.value'

# Average response time to assignments (should be <10 sec)
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/assignment-response-time-avg | jq '.value'

# Current replica count
kubectl get deploy rider-fleet-service -n rider-fleet -o jsonpath='{.spec.replicas}'
```

### Blue-Green Deployment

```bash
# 1. Version check
kubectl get deploy -n rider-fleet rider-fleet-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Upgrade
helm upgrade rider-fleet-service deploy/helm/rider-fleet-service \
  -n rider-fleet \
  --values deploy/helm/values.yaml \
  --values deploy/helm/values-prod.yaml

# 3. Monitor rollout
kubectl rollout status deploy/rider-fleet-service -n rider-fleet --timeout=10m

# 4. Verify Kafka consumer rebalancing complete
kubectl logs -n rider-fleet deploy/rider-fleet-service --tail=20 | grep -i "joined\|rebalance"

# 5. Verify active riders count still stable
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/active-riders-count | jq '.value'
```

### Rollback

```bash
helm rollback rider-fleet-service 1 -n rider-fleet
kubectl rollout status deploy/rider-fleet-service -n rider-fleet --timeout=5m

# Verify riders still online
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/active-riders-count | jq '.value'
```

---

## Post-Deployment Validation

### Service Startup

```bash
# Check Kafka consumer joined
kubectl logs -n rider-fleet deploy/rider-fleet-service --tail=30 | grep "joined\|rebalance"

# Verify port listening
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  netstat -tlnp | grep 8087
```

### Smoke Tests

```bash
POD=$(kubectl get pods -n rider-fleet -l app=rider-fleet-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec -n rider-fleet pod/$POD -- \
  curl -s http://localhost:8087/actuator/health | jq '.status'

# 2. Get active riders
kubectl exec -n rider-fleet pod/$POD -- \
  curl -s http://localhost:8087/v1/riders?status=AVAILABLE&limit=5 \
  -H "Authorization: Bearer admin-token" | jq '.riders | length'

# 3. Test assignment endpoint
kubectl exec -n rider-fleet pod/$POD -- \
  curl -X POST http://localhost:8087/v1/assignments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer admin-token" \
  -d '{"deliveryId":"test-001","riderId":"rider-001"}' | jq '.assignmentId'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

**Load balancer removal triggers**: Kafka consumer not joined, no riders available, Redis connection lost

---

## Incident Response Procedures

### P0 - Rider Assignments Down

**Symptom**: Fulfillment cannot assign riders, orders stuck

**Diagnosis**:
```bash
# Check pod status
kubectl get pods -n rider-fleet -l app=rider-fleet-service

# Check logs
kubectl logs -n rider-fleet deploy/rider-fleet-service --tail=100 | grep -i "error\|exception"

# Check active riders
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/active-riders-count | jq '.value'

# Check Kafka consumer
kubectl logs -n rider-fleet deploy/rider-fleet-service --tail=30 | grep -i "offset\|lag"

# Check database
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/actuator/health/db | jq '.status'
```

**Resolution**:
1. Scale up: `kubectl scale deploy rider-fleet-service -n rider-fleet --replicas=5`
2. If no riders available: Check with ops, riders may be offline
3. If Kafka consumer stuck: `kubectl rollout restart deploy/rider-fleet-service -n rider-fleet`
4. If database down: Check PostgreSQL cluster

### P1 - Assignment Latency High (>30 sec)

**Diagnosis**:
```bash
# Check assignment latency
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/assignment-response-time-p99 | jq '.value'

# Check Kafka consumer lag
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/kafka-consumer-lag | jq '.value'

# Check database query performance
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/db-query-latency-p99 | jq '.value'

# Check Redis operations
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/redis-op-latency-p99 | jq '.value'
```

**Resolution**:
1. If Kafka lag high: Scale up replicas, check if riders producing many location updates
2. If database slow: Check query plans, add indices
3. Scale up: 3+ replicas minimum during peak hours

### P2 - Intermittent Assignment Failures

**Diagnosis**:
```bash
# Check failed assignments
kubectl logs -n rider-fleet deploy/rider-fleet-service --tail=200 | grep "assignment.*failed"

# Check if some riders are offline
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/v1/admin/rider-connectivity | jq '.offline_riders'
```

**Resolution**:
1. Notify riders team of offline riders
2. Enable fallback to manual assignment
3. Check network connectivity between rider app and backend

---

## Common Issues & Troubleshooting

### Issue: No Riders Available

```bash
# Check active rider count
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/active-riders-count | jq '.value'

# Check rider status distribution
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/v1/admin/rider-status-distribution | jq '.statuses'

# If 0 riders: Check if rider app is having issues
# Check Kafka rider.status topic for heartbeats
# Restart rider-fleet service to reset connections
kubectl rollout restart deploy/rider-fleet-service -n rider-fleet
```

### Issue: Rider Location Updates Stale

```bash
# Check location update freshness
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/location-update-staleness | jq '.value'

# If > 60 seconds:
# 1. Check if riders are sending updates
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/v1/admin/location-update-rate | jq '.updates_per_sec'

# 2. Check Kafka topic
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/v1/admin/kafka-consumer-lag | jq '.lag'

# 3. Restart consumer if stuck
kubectl rollout restart deploy/rider-fleet-service -n rider-fleet
```

### Issue: Redis Location Cache Out of Sync

```bash
# Check consistency
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/v1/admin/location-cache-consistency | jq '.status'

# If inconsistent, rebuild:
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -X POST http://localhost:8087/v1/admin/rebuild-location-cache \
  -H "Authorization: Bearer admin-token" | jq '.status'

# Monitor rebuild
kubectl logs -n rider-fleet deploy/rider-fleet-service | grep "rebuild"
```

### Issue: Assignment Acceptance Timeout

```bash
# Check rider notification delivery
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/notification-delivery-rate | jq '.value'

# Check push notification service connectivity
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -v http://notification-service:8090/health

# Increase timeout if network latency is root cause
kubectl set env deploy/rider-fleet-service -n rider-fleet \
  ASSIGNMENT_TIMEOUT_SECONDS=60
```

---

## Rollback Procedures

```bash
# 1. Rollback
helm rollback rider-fleet-service 1 -n rider-fleet

# 2. Monitor
kubectl rollout status deploy/rider-fleet-service -n rider-fleet --timeout=5m

# 3. Verify riders still online
kubectl exec -n rider-fleet deploy/rider-fleet-service -- \
  curl -s http://localhost:8087/metrics/active-riders-count
```

---

## Performance Tuning

### Consumer Concurrency

```bash
# For high volume location updates (>5K/sec):
kubectl set env deploy/rider-fleet-service -n rider-fleet \
  KAFKA_CONCURRENCY=15 \
  KAFKA_MAX_POLL_RECORDS=1000
```

### Redis Caching

```bash
# For faster location lookups:
helm upgrade rider-fleet-service deploy/helm/rider-fleet-service \
  -n rider-fleet \
  --set redis.cache.ttlSeconds=300 \
  --set redis.cache.maxSize=100000
```

---

## Monitoring & Alerting

### Key Metrics

```
# Active rider count
rider_fleet_active_riders_count

# Assignment success rate
rate(rider_assignment_total{status="success"}[5m]) / rate(rider_assignment_total[5m])

# Assignment latency
histogram_quantile(0.99, rate(rider_assignment_seconds_bucket[5m]))

# Location update freshness
rider_location_update_staleness_seconds
```

---

## On-Call Handoff

- [ ] Active rider count? `kubectl exec -n rider-fleet deploy/rider-fleet-service -- curl -s http://localhost:8087/metrics/active-riders-count`
- [ ] Any offline riders? `kubectl exec -n rider-fleet deploy/rider-fleet-service -- curl -s http://localhost:8087/v1/admin/rider-connectivity`
- [ ] Kafka consumer lag? `kubectl exec -n rider-fleet deploy/rider-fleet-service -- curl -s http://localhost:8087/metrics/kafka-consumer-lag`

---

## Related Documentation

- **Deployment**: `/deploy/helm/rider-fleet-service/`
- **Architecture**: `/docs/services/rider-fleet-service/README.md`
