# Payment Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify payment-db (PostgreSQL) is accessible and migrations are up-to-date
  ```bash
  kubectl exec -n payment deploy/payment-service -- \
    curl -s http://localhost:8080/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check Secret availability (PCI-DSS credentials, payment gateway keys)
  ```bash
  kubectl get secrets -n payment payment-secrets -o yaml | grep -E "stripe|square|database"
  ```

- [ ] Verify connection pool capacity (should be 20-30 available)
  ```bash
  kubectl exec -n payment deploy/payment-service -- \
    curl -s http://localhost:8080/actuator/health | jq '.components.db'
  ```

- [ ] Validate capacity planning (current load vs max TPS quota)
  ```bash
  # Check current replicas
  kubectl get deploy payment-service -n payment -o jsonpath='{.spec.replicas}'
  # Expected: 3+ in production
  ```

- [ ] Verify wallet-loyalty-service connectivity
  ```bash
  kubectl exec -n payment deploy/payment-service -- \
    curl -v http://wallet-loyalty-service:8104/health
  ```

---

## Deployment Procedures

### Pre-Deployment Health Baseline

```bash
# Capture current state for comparison
kubectl top pod -n payment -l app=payment-service
kubectl get deploy payment-service -n payment -o jsonpath='{.status.availableReplicas}'
```

### Blue-Green Deployment

```bash
# 1. Check current version
kubectl get deploy -n payment payment-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Stage new version
helm upgrade payment-service deploy/helm/payment-service \
  -n payment \
  --values deploy/helm/values.yaml \
  --values deploy/helm/values-prod.yaml \
  --dry-run --debug

# 3. Apply update
helm upgrade payment-service deploy/helm/payment-service \
  -n payment \
  --values deploy/helm/values.yaml \
  --values deploy/helm/values-prod.yaml

# 4. Monitor rollout (typical: 2-3 min for 3 replicas)
kubectl rollout status deploy/payment-service -n payment --timeout=10m

# 5. Verify all pods healthy
kubectl get pods -n payment -l app=payment-service -o wide
kubectl wait --for=condition=ready pod -l app=payment-service -n payment --timeout=5m
```

### Health Probe Configuration

The deployment includes:

```yaml
# Liveness probe (pod restart if unhealthy)
livenessProbe:
  httpGet:
    path: /actuator/health/live
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3

# Readiness probe (remove from service if failing)
readinessProbe:
  httpGet:
    path: /actuator/health/ready
    port: 8080
  initialDelaySeconds: 15
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 2
```

### Rollback Procedure

```bash
# 1. Immediate rollback
helm rollback payment-service 1 -n payment

# 2. Monitor rollback progress
kubectl rollout status deploy/payment-service -n payment --timeout=5m

# 3. Verify previous version is stable
kubectl logs -n payment deploy/payment-service --tail=30 | grep -i "error\|warn"

# 4. Investigate what went wrong
helm history payment-service -n payment
```

---

## Post-Deployment Validation

### Service Startup Verification

```bash
# Check pod logs for startup errors
kubectl logs -n payment deploy/payment-service --tail=50 | grep -i "failed\|error\|exception"

# Verify port is listening
kubectl exec -n payment deploy/payment-service -- netstat -tlnp | grep 8080

# Check Spring Boot startup banner
kubectl logs -n payment deploy/payment-service --tail=20 | head -10
```

### Endpoint Smoke Tests

```bash
# Get payment service pod IP
POD=$(kubectl get pods -n payment -l app=payment-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec -n payment pod/$POD -- \
  curl -s http://localhost:8080/actuator/health | jq '.status'

# 2. Process payment endpoint
kubectl exec -n payment pod/$POD -- \
  curl -X POST http://localhost:8080/v1/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token" \
  -d '{"orderId":"smoke-test-001","amount":1000,"currency":"USD"}' | jq '.status'

# 3. Get payment status
kubectl exec -n payment pod/$POD -- \
  curl -s http://localhost:8080/v1/payments/smoke-test-001 \
  -H "Authorization: Bearer test-token" | jq '.status'
```

### Database Connection Validation

```bash
# Verify all replicas can connect to PostgreSQL
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health/db | jq '.components.db | {status, details}'

# Check connection pool stats
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/metrics/hikaricp.connections | jq '.measurements'
```

### External Service Connectivity

```bash
# Wallet loyalty service (synchronous dependency)
kubectl exec -n payment deploy/payment-service -- \
  curl -v http://wallet-loyalty-service:8104/health

# Payment gateway (Stripe/Square - async with retry)
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health | jq '.components'
```

---

## Health Check Procedures

### Liveness Probe (Pod Restart Trigger)

Endpoint: `GET /actuator/health/live`

```bash
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health/live | jq .

# Expected response (UP = pod stays running):
# { "status": "UP" }
```

**Failure conditions** (pod will restart):
- Database connection lost for >30 seconds
- JVM memory critical (>95%)
- Thread deadlock detected

### Readiness Probe (Load Balancer Removal)

Endpoint: `GET /actuator/health/ready`

```bash
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health/ready | jq .

# Expected response (UP = receives traffic):
# { "status": "UP", "components": { "wallet-loyalty": { "status": "UP" } } }
```

**Failure conditions** (pod removed from service):
- Wallet-loyalty-service unavailable
- Payment gateway client initialization failed

### Custom Health Indicators

```bash
# Payment gateway readiness (may be in GRACEFUL_SHUTDOWN if degraded)
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health | jq '.components."payment-gateway"'

# Database connection pool health
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health | jq '.components.db'
```

### Metrics Endpoint Verification

```bash
# Prometheus metrics
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/prometheus | head -20

# Key metrics to verify:
# - http_server_requests_seconds_count
# - payment_processing_seconds_bucket
# - db_connection_active
# - process_uptime_seconds (should be increasing)
```

---

## Incident Response Procedures

### P0 (Critical) - Payment Processing Down

**Symptom**: All payment requests return 500 or timeout

**Diagnosis**:
```bash
# 1. Check pod status
kubectl get pods -n payment -l app=payment-service

# 2. Check logs for errors
kubectl logs -n payment deploy/payment-service --tail=100 | grep -iE "exception|fatal|timeout"

# 3. Check database connectivity
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health/db | jq '.status'

# 4. Check payment gateway connectivity
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health | jq '.components."payment-gateway"'
```

**Resolution**:
1. If pods are not running: `kubectl scale deploy payment-service -n payment --replicas=3`
2. If database down: Check PostgreSQL cluster health in payment-db namespace
3. If payment gateway down: Enable circuit breaker fallback
4. If memory issue: Increase JVM heap and restart: `helm upgrade payment-service ... --set jvmHeapSize=2G`

### P1 (High) - Payment Processing Degraded (>1% error rate)

**Symptom**: Error rate exceeds 1%, latency >1 second

**Diagnosis**:
```bash
# Check error rate
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/metrics/http.server.requests | \
  jq '.measurements[] | select(.tags.status == "500")'

# Check latency
kubectl logs -n payment deploy/payment-service --tail=50 | grep "duration="

# Check circuit breaker status
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreaker'
```

**Resolution**:
1. If wallet-loyalty-service slow: Notify wallet team, circuit breaker will kick in after threshold
2. If database slow: Check database CPU/memory, connection pool size
3. If payment gateway slow: Increase timeout or enable async retry pattern

### P2 (Medium) - Intermittent Payment Failures

**Symptom**: Occasional errors (0.1-1%), but system recovering

**Diagnosis**:
```bash
# Check logs for transient errors
kubectl logs -n payment deploy/payment-service --tail=200 | grep -iE "connection.*refused|timeout|io\.exception"

# Check network policies
kubectl get networkpolicies -n payment

# Check pod resource constraints
kubectl top pod -n payment -l app=payment-service
```

**Resolution**:
1. If connection refused: Check network policies between namespaces
2. If timeouts: Increase timeout values in config-feature-flag-service
3. If pod resource constrained: Scale up replicas

### P3 (Low) - Non-Critical Issues

**Symptom**: Slow payment processing, no errors

**Diagnosis**:
```bash
# Check GC pauses (check logs for GC times >100ms)
kubectl logs -n payment deploy/payment-service | grep -i "gc pause"

# Check cache hit rates
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/metrics | grep -i cache
```

**Resolution**:
1. Tune GC settings (parallel vs G1GC)
2. Increase cache sizes for frequently accessed payment methods
3. Schedule during maintenance window

### Escalation Paths

- **P0**: 5 minutes → page on-call, notify team lead
- **P1**: 15 minutes → create SEV-2 incident, notify team
- **P2**: 1 hour → create SEV-3 incident, schedule debugging
- **P3**: Schedule during planned maintenance

---

## Common Issues & Troubleshooting

### Issue: "Connection refused" to wallet-loyalty-service

```bash
# 1. Check if wallet-loyalty-service is running
kubectl get pods -n wallet-loyalty -l app=wallet-loyalty-service

# 2. Check network policies
kubectl get networkpolicies -n payment | grep wallet

# 3. Test DNS resolution
kubectl exec -n payment deploy/payment-service -- \
  nslookup wallet-loyalty-service.wallet-loyalty.svc.cluster.local

# 4. Test raw TCP connection
kubectl exec -n payment deploy/payment-service -- \
  nc -zv wallet-loyalty-service.wallet-loyalty.svc.cluster.local 8104

# Resolution: Apply NetworkPolicy allowing payment→wallet-loyalty
kubectl apply -f deploy/k8s/network-policies/payment-wallet-egress.yaml
```

### Issue: Database Connection Pool Exhaustion

```bash
# Check pool status
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/metrics/hikaricp | jq '.measurements[] | select(.tags.pool | contains("idle"))'

# Increase pool size (in values.yaml)
# db.pool.size: 20 -> 30

# Or scale down traffic temporarily
kubectl scale deploy payment-service -n payment --replicas=2

# Then restart to rebuild connection pool
kubectl rollout restart deploy/payment-service -n payment
```

### Issue: Out of Memory (OOM)

```bash
# Check memory usage
kubectl top pod -n payment -l app=payment-service

# Check if pod is being killed by OOM killer
kubectl describe pod -n payment <pod-name> | grep -i "oom"

# Check Java heap size
kubectl exec -n payment deploy/payment-service -- java -XX:+PrintFlagsFinal | grep -i heapsize

# Resolution: Increase memory limit
helm upgrade payment-service deploy/helm/payment-service \
  -n payment \
  --set resources.requests.memory=1Gi \
  --set resources.limits.memory=2Gi
```

### Issue: Stuck Threads / Deadlock

```bash
# Generate thread dump
kubectl exec -n payment deploy/payment-service -- \
  jcmd <pid> Thread.print > /tmp/thread_dump.txt

# Look for BLOCKED threads
grep "BLOCKED" /tmp/thread_dump.txt

# Check for database lock contention
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/v1/payments/locks

# Resolution: Kill and restart the pod
kubectl delete pod -n payment <pod-name>
```

### Issue: Payment Gateway Timeout

```bash
# Check payment gateway circuit breaker
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/health | jq '.components."payment-gateway"'

# If OPEN, the circuit breaker is protecting the service
# Wait 30 seconds for automatic recovery attempt, or manually reset:
kubectl exec -n payment deploy/payment-service -- \
  curl -X POST http://localhost:8080/actuator/circuitbreakers/paymentGateway/reset

# Check and increase timeout
kubectl logs -n payment deploy/payment-service | grep "timeout\|exceeded"
```

---

## Rollback Procedures

### Standard Helm Rollback

```bash
# 1. View rollback history
helm history payment-service -n payment

# 2. Immediate rollback to previous revision
helm rollback payment-service 1 -n payment

# 3. Monitor rollback
kubectl rollout status deploy/payment-service -n payment --timeout=5m

# 4. Verify stability (wait 2 min before declaring success)
sleep 120
kubectl logs -n payment deploy/payment-service --tail=20 | grep -i "error\|exception"
```

### Data Consistency Verification

```bash
# Check if any pending payments are in uncertain state
kubectl exec -n payment deploy/payment-service -- \
  curl -X GET http://localhost:8080/v1/payments/pending-reconcile \
  -H "Authorization: Bearer admin-token" | jq '.count'

# Validate database consistency after rollback
kubectl exec -n payment deploy/payment-service -- \
  curl -X POST http://localhost:8080/v1/admin/verify-consistency \
  -H "Authorization: Bearer admin-token" | jq '.status'
```

### Client Cache Invalidation

```bash
# Clear payment method cache across clients
kubectl exec -n payment deploy/payment-service -- \
  curl -X POST http://localhost:8080/v1/admin/cache/clear \
  -H "Authorization: Bearer admin-token"

# Verify checkout-orchestrator is re-fetching
kubectl logs -n checkout deploy/checkout-orchestrator-service | grep "cache.*miss"
```

### Service Re-registration

```bash
# Force re-registration with service discovery
kubectl rollout restart statefulset/payment-service -n payment

# Wait for new pods to register
kubectl wait --for=condition=ready pod -l app=payment-service -n payment --timeout=2m
```

---

## Performance Tuning

### JVM Heap Sizing

```bash
# Current heap usage
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/metrics/jvm.memory.used | \
  jq '.measurements[] | select(.tags.area == "heap")'

# Recommendation: Heap = 60% of container memory limit
# For 1.5 GB container limit:
# -Xms900m -Xmx900m

helm upgrade payment-service deploy/helm/payment-service \
  -n payment \
  --set jvmOpts="-Xms900m -Xmx900m -XX:+UseG1GC"
```

### Connection Pool Tuning

```bash
# Current pool status
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/metrics/hikaricp.connections | jq .

# Tuning formula: connections = (threads) + 5
# If peak transactions = 50 concurrent:
# connections = 50 + 5 = 55
# But cap at 30 for resource efficiency

kubectl set env deploy/payment-service -n payment \
  DB_POOL_SIZE=25 \
  DB_POOL_MAX_IDLE_TIME=900000
```

### Cache Settings

```bash
# Check cache performance
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/metrics | grep -i cache

# Increase Caffeine cache size for payment methods
helm upgrade payment-service deploy/helm/payment-service \
  -n payment \
  --set cache.paymentMethods.size=5000 \
  --set cache.paymentMethods.ttlMinutes=30
```

### GC Tuning

```bash
# Monitor GC pauses
kubectl exec -n payment deploy/payment-service -- \
  curl -s http://localhost:8080/actuator/metrics/jvm.gc.pause | jq '.measurements'

# Switch to G1GC if pauses > 100ms
helm upgrade payment-service deploy/helm/payment-service \
  -n payment \
  --set jvmOpts="-Xms900m -Xmx900m -XX:+UseG1GC -XX:MaxGCPauseMillis=50"
```

---

## Monitoring & Alerting

### Key Metrics to Watch

```
# Payment processing latency (p99 should be <1s)
histogram_quantile(0.99, rate(payment_processing_seconds_bucket[5m]))

# Payment success rate (should be >99%)
sum(rate(payment_requests_total{status="success"}[5m])) / sum(rate(payment_requests_total[5m]))

# Database query latency (p95 should be <100ms)
histogram_quantile(0.95, rate(db_query_seconds_bucket{service="payment"}[5m]))

# Circuit breaker state (0=closed, 1=open)
resilience4j_circuitbreaker_state{name="paymentGateway"}

# Thread pool exhaustion
jvm_threads_live_threads

# Connection pool usage
hikaricp_connections_active / hikaricp_connections_max
```

### Alert Thresholds

```yaml
# SEV-1 Alert: Payment processing down
- alert: PaymentProcessingDown
  expr: rate(payment_requests_total{status="500"}[1m]) > 0.1
  for: 2m
  annotations:
    severity: critical
    runbook: payment-service/runbook.md#p0-critical

# SEV-2 Alert: High error rate
- alert: PaymentErrorRateHigh
  expr: rate(payment_requests_total{status=~"5.."}[5m]) > 0.01
  for: 5m
  annotations:
    severity: high

# SEV-3 Alert: Latency degradation
- alert: PaymentLatencyHigh
  expr: histogram_quantile(0.99, payment_processing_seconds_bucket) > 1.0
  for: 10m
  annotations:
    severity: medium
```

### Grafana Dashboard

**Dashboard ID**: `payment-service`
**URL**: `https://grafana.internal.instacommerce.com/d/payment-service`

**Panels**:
- Request rate by status code (SUCCESS / ERROR / TIMEOUT)
- Processing latency percentiles (p50, p95, p99)
- Database query performance
- Wallet-loyalty-service latency (circuit breaker status)
- Connection pool utilization
- JVM heap usage and GC pauses
- Thread count and deadlock detection

---

## On-Call Handoff Checklist

**Before shift ends, provide to incoming on-call:**

- [ ] Any ongoing incidents?
  ```bash
  kubectl get events -n payment --sort-by='.lastTimestamp' | tail -20
  ```

- [ ] Recent changes to payment service?
  ```bash
  helm history payment-service -n payment | head -5
  kubectl rollout history deploy/payment-service -n payment
  ```

- [ ] Known issues or degradation?
  ```bash
  # Check logs for warnings
  kubectl logs -n payment deploy/payment-service --tail=100 | grep -i "warn"
  ```

- [ ] Payment volumes (should spike during meal hours)
  ```bash
  kubectl exec -n payment deploy/payment-service -- \
    curl -s http://localhost:8080/metrics/payment-volume-1h | jq '.total'
  ```

- [ ] Next scheduled maintenance?
  - Check calendar integration or on-call notes

- [ ] Escalation contacts?
  - Payment team lead: [on-call contact]
  - Database team lead: [on-call contact]
  - Payment gateway support: [contact info]

---

## Related Documentation

- **Deployment**: `/deploy/helm/payment-service/`
- **Architecture**: `/docs/services/payment-service/`
- **API Specification**: `/docs/services/payment-service/README.md`
- **Alert Rules**: `/monitoring/prometheus/payment-rules.yaml`
- **SLOs**: `/docs/slos/service-slos.md#payment`
- **ADR-014**: Reconciliation Authority Model
