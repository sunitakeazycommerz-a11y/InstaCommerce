# Fulfillment Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify fulfillment-db (PostgreSQL) migrations current
  ```bash
  kubectl exec -n fulfillment deploy/fulfillment-service -- \
    curl -s http://localhost:8084/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check order-service, inventory-service, warehouse-service connectivity
  ```bash
  for svc in order-service inventory-service warehouse-service; do
    kubectl exec -n fulfillment deploy/fulfillment-service -- \
      curl -v http://${svc}:8080/health
  done
  ```

- [ ] Verify Kafka connectivity (order.created, fulfillment.events topics)
  ```bash
  kubectl exec -n fulfillment deploy/fulfillment-service -- \
    curl -s http://localhost:8084/actuator/health | jq '.components.kafka'
  ```

- [ ] Validate rider-fleet-service connectivity (dispatch assignment)
  ```bash
  kubectl exec -n fulfillment deploy/fulfillment-service -- \
    curl -v http://rider-fleet-service:8087/health
  ```

- [ ] Check capacity: Available warehouse slots for new orders
  ```bash
  kubectl exec -n fulfillment deploy/fulfillment-service -- \
    curl -s http://localhost:8084/metrics/warehouse-capacity-available | jq '.value'
  ```

---

## Deployment Procedures

### Pre-Deployment State Capture

```bash
# Current fulfillment queue depth (should be processed < 30 sec)
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/fulfillment-queue-depth | jq '.value'

# SLA compliance rate (should be >95%)
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/sla-compliance-rate | jq '.value'
```

### Blue-Green Deployment

```bash
# 1. Version check
kubectl get deploy -n fulfillment fulfillment-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Upgrade
helm upgrade fulfillment-service deploy/helm/fulfillment-service \
  -n fulfillment \
  --values deploy/helm/values.yaml \
  --values deploy/helm/values-prod.yaml

# 3. Monitor rollout (5 min typical for order processing)
kubectl rollout status deploy/fulfillment-service -n fulfillment --timeout=15m

# 4. Verify Kafka consumer rebalancing completes
kubectl logs -n fulfillment deploy/fulfillment-service --tail=20 | grep -i "joined\|rebalance"

# 5. Verify SLA compliance rate recovered
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/sla-compliance-rate | jq '.value'
```

### Rollback

```bash
helm rollback fulfillment-service 1 -n fulfillment
kubectl rollout status deploy/fulfillment-service -n fulfillment --timeout=5m

# Check if any in-flight fulfillments are orphaned
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/v1/admin/orphaned-orders | jq '.count'
```

---

## Post-Deployment Validation

### Service Startup

```bash
# Check Kafka consumer joined successfully
kubectl logs -n fulfillment deploy/fulfillment-service --tail=30 | grep "joined\|rebalance"

# Verify port listening
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  netstat -tlnp | grep 8084
```

### Smoke Tests

```bash
POD=$(kubectl get pods -n fulfillment -l app=fulfillment-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec -n fulfillment pod/$POD -- \
  curl -s http://localhost:8084/actuator/health | jq '.status'

# 2. Get active fulfillments
kubectl exec -n fulfillment pod/$POD -- \
  curl -s http://localhost:8084/v1/fulfillments?status=PENDING&limit=5 \
  -H "Authorization: Bearer admin-token" | jq '.count'

# 3. Test fulfillment assignment
kubectl exec -n fulfillment pod/$POD -- \
  curl -X POST http://localhost:8084/v1/fulfillments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer admin-token" \
  -d '{"orderId":"test-001","warehouse":"main","items":1}' | jq '.fulfillmentId'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

```bash
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/actuator/health/live | jq .
```

**Restart triggers**: Kafka consumer stuck, database down

### Readiness Probe: `/actuator/health/ready`

```bash
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/actuator/health/ready | jq .
```

**Load balancer removal triggers**: Consumer lag > 60 sec, fulfillment queue overflowing

---

## Incident Response Procedures

### P0 - Fulfillment Processing Stalled

**Symptom**: Orders not being fulfilled, queue backing up, SLA breach imminent

**Diagnosis**:
```bash
# Check Kafka consumer status
kubectl logs -n fulfillment deploy/fulfillment-service --tail=50 | grep -i "consumer\|offset"

# Check queue depth and age
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/fulfillment-queue-depth | jq '.value'

# Check oldest pending fulfillment (should be <30 min)
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/v1/fulfillments/oldest-pending | jq '.createdAt'

# Check database
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/actuator/health/db | jq '.status'

# Check warehouse-service availability
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -v http://warehouse-service:8085/health
```

**Resolution**:
1. Scale up to 5+ replicas to process backlog faster
2. If database down: Failover or check PostgreSQL cluster
3. If warehouse-service down: Enable degraded mode (manual assignment)
4. Kill stuck consumer and restart: `kubectl rollout restart deploy/fulfillment-service -n fulfillment`

### P1 - Fulfillment Processing Degraded (>5% late SLA)

**Symptom**: Some fulfillments missing SLA, error rate increasing

**Diagnosis**:
```bash
# SLA compliance rate
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/sla-compliance-rate | jq '.value'

# Processing latency
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/fulfillment-latency-p99 | jq '.value'

# Check warehouse capacity
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/warehouse-capacity-available | jq '.value'

# Check rider availability (dispatch impact)
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/available-riders | jq '.value'
```

**Resolution**:
1. If warehouse capacity low: Prioritize high-margin items
2. If rider availability low: Alert rider-fleet-service team
3. Increase fulfillment service replicas
4. Check if warehouse-service is slow (circuit breaker may activate)

### P2 - Intermittent Assignment Failures

**Diagnosis**:
```bash
# Check warehouse assignment errors
kubectl logs -n fulfillment deploy/fulfillment-service --tail=200 | grep -i "assignment.*failed"

# Check rider-fleet-service latency
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/rider-fleet-latency-p99 | jq '.value'
```

**Resolution**:
1. Enable circuit breaker fallback for rider-fleet
2. Implement manual assignment queue for failures
3. Scale up rider-fleet-service

---

## Common Issues & Troubleshooting

### Issue: Order Fulfillment Stuck (Consumer Lag Growing)

```bash
# Check consumer group lag
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/metrics/kafka-consumer-lag | jq '.value'

# If lag > 60 seconds and not recovering:
# 1. Check pod logs
kubectl logs -n fulfillment deploy/fulfillment-service --tail=100 | grep -i "error\|exception"

# 2. Check if processing a large order (normal, wait)
kubectl logs -n fulfillment deploy/fulfillment-service --tail=20 | grep "processing"

# 3. If truly stuck, restart consumer
kubectl rollout restart deploy/fulfillment-service -n fulfillment

# 4. Monitor consumer group rebalancing
kubectl logs -n fulfillment deploy/fulfillment-service --tail=30 | grep "rebalance"
```

### Issue: Warehouse Assignment Always Fails

```bash
# Test warehouse-service connectivity
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -v http://warehouse-service:8085/v1/capacity

# Check network policy
kubectl get networkpolicies -n fulfillment | grep warehouse

# If blocked, apply policy
kubectl apply -f deploy/k8s/network-policies/fulfillment-warehouse-egress.yaml

# Restart fulfillment service
kubectl rollout restart deploy/fulfillment-service -n fulfillment
```

### Issue: Rider Fleet Integration Down

```bash
# Check rider-fleet-service health
kubectl get pods -n rider-fleet -l app=rider-fleet-service

# Check deployment status
kubectl get deploy -n rider-fleet rider-fleet-service

# Enable manual fulfillment mode (admin operation)
kubectl set env deploy/fulfillment-service -n fulfillment \
  RIDER_FLEET_CIRCUIT_BREAKER_ENABLED=true \
  MANUAL_FULFILLMENT_MODE=true

# Notify riders team for investigation
```

### Issue: Database Connection Pool Exhausted

```bash
# Check pool status
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/actuator/metrics/hikaricp.connections | jq '.measurements'

# Increase pool size
kubectl set env deploy/fulfillment-service -n fulfillment \
  DB_POOL_SIZE=40

# Restart pods
kubectl rollout restart deploy/fulfillment-service -n fulfillment
```

---

## Rollback Procedures

```bash
# 1. Rollback
helm rollback fulfillment-service 1 -n fulfillment

# 2. Monitor
kubectl rollout status deploy/fulfillment-service -n fulfillment --timeout=5m

# 3. Check for orphaned fulfillments
kubectl exec -n fulfillment deploy/fulfillment-service -- \
  curl -s http://localhost:8084/v1/admin/orphaned-orders | jq '.orphanedOrders'

# 4. Manually complete any in-flight fulfillments
```

---

## Performance Tuning

### Consumer Concurrency

```bash
# For 1000+ orders/min:
kubectl set env deploy/fulfillment-service -n fulfillment \
  KAFKA_CONCURRENCY=10 \
  KAFKA_MAX_POLL_RECORDS=500
```

### Connection Pool

```bash
# For high warehouse assignment frequency:
kubectl set env deploy/fulfillment-service -n fulfillment \
  DB_POOL_SIZE=40 \
  WAREHOUSE_HTTP_POOL_SIZE=20
```

---

## Monitoring & Alerting

### Key Metrics

```
# SLA compliance rate (should be >95%)
fulfillment_sla_compliance_rate

# Fulfillment queue depth
fulfillment_queue_depth

# Processing latency
histogram_quantile(0.99, rate(fulfillment_processing_seconds_bucket[5m]))

# Warehouse assignment success rate
rate(warehouse_assignment_total{status="success"}[5m]) / rate(warehouse_assignment_total[5m])
```

---

## On-Call Handoff

- [ ] Fulfillment queue depth? `kubectl exec -n fulfillment deploy/fulfillment-service -- curl -s http://localhost:8084/metrics/fulfillment-queue-depth`
- [ ] SLA compliance rate? `kubectl exec -n fulfillment deploy/fulfillment-service -- curl -s http://localhost:8084/metrics/sla-compliance-rate`
- [ ] Kafka consumer lag trending?
- [ ] Warehouse capacity status?

---

## Related Documentation

- **Deployment**: `/deploy/helm/fulfillment-service/`
- **Architecture**: `/docs/services/fulfillment-service/README.md`
- **SLO**: `/docs/slos/service-slos.md#fulfillment`
