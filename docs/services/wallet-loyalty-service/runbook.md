# Wallet Loyalty Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify wallet-loyalty-db (PostgreSQL) accessibility
  ```bash
  kubectl exec -n engagement deploy/wallet-loyalty-service -- \
    curl -s http://localhost:8104/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check Redis connectivity (wallet balance cache)
  ```bash
  kubectl exec -n engagement deploy/wallet-loyalty-service -- \
    curl -s http://localhost:8104/actuator/health | jq '.components.redis'
  ```

- [ ] Verify Kafka connectivity (payment.completed, order.created, refund.* topics)
  ```bash
  kubectl exec -n engagement deploy/wallet-loyalty-service -- \
    curl -s http://localhost:8104/actuator/health | jq '.components.kafka'
  ```

- [ ] Check payment-service connectivity (payment settlement)
  ```bash
  kubectl exec -n engagement deploy/wallet-loyalty-service -- \
    curl -v http://payment-service:8080/health
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Total wallet balance in system (should be consistent)
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/metrics/total-wallet-balance | jq '.value'

# Cache hit rate (should be 90%+)
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/metrics/cache-hit-rate | jq '.value'

# Balance accuracy check (DB vs cache)
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/v1/admin/balance-consistency | jq '.accuracy_percent'
```

### Blue-Green Deployment

```bash
helm upgrade wallet-loyalty-service deploy/helm/wallet-loyalty-service -n engagement
kubectl rollout status deploy/wallet-loyalty-service -n engagement --timeout=10m

# Verify balance consistency maintained
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/v1/admin/balance-consistency | jq '.accuracy_percent'

# Verify cache recovered
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/metrics/cache-hit-rate | jq '.value'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

**Load balancer removal triggers**: Database down, Redis unavailable, Kafka consumer not joined

---

## Incident Response Procedures

### P0 - Wallet Balance Unavailable

**Symptom**: Users cannot check wallet balance, payment settlement blocked

**Diagnosis**:
```bash
kubectl get pods -n engagement -l app=wallet-loyalty-service
kubectl logs -n engagement deploy/wallet-loyalty-service --tail=100

# Check database
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/actuator/health/db | jq '.status'

# Check Redis
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/actuator/health | jq '.components.redis'

# Test balance lookup
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/v1/wallets/USER-001/balance
```

**Resolution**:
1. Scale up: `kubectl scale deploy wallet-loyalty-service -n engagement --replicas=5`
2. If database down: Check PostgreSQL cluster
3. If Redis down: Service degrades to database-only (slower)
4. Restart: `kubectl rollout restart deploy/wallet-loyalty-service -n engagement`

### P1 - Balance Inconsistency (>0.1% discrepancy)

**Diagnosis**:
```bash
# Check balance accuracy
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/v1/admin/balance-consistency | jq '.accuracy_percent'

# Identify discrepancies
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/v1/admin/balance-discrepancies | jq '.discrepancies'
```

**Resolution**:
1. Investigate root cause (lost transaction, duplicate credit, etc.)
2. Manual reconciliation if needed
3. Rebuild cache: `kubectl exec deploy/wallet-loyalty-service -n engagement -- curl -X POST http://localhost:8104/v1/admin/rebuild-balance-cache`

### P2 - Loyalty Points Not Updating

**Diagnosis**:
```bash
# Check Kafka consumer lag
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/metrics/kafka-consumer-lag | jq '.value'

# Check last processed transaction
kubectl logs -n engagement deploy/wallet-loyalty-service --tail=50 | grep "transaction"
```

**Resolution**:
1. If consumer lag high: Scale up or increase concurrency
2. Restart consumer: `kubectl rollout restart deploy/wallet-loyalty-service -n engagement`

---

## Common Issues & Troubleshooting

### Issue: Balance Cache Out of Sync

```bash
# Check consistency
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/v1/admin/balance-consistency | jq '.status'

# Rebuild from database
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -X POST http://localhost:8104/v1/admin/rebuild-balance-cache \
  -H "Authorization: Bearer admin-token" | jq '.status'

# Monitor rebuild
kubectl logs -n engagement deploy/wallet-loyalty-service | grep "rebuild"
```

### Issue: Transaction Processing Lag

```bash
# Check Kafka consumer lag
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/metrics/kafka-consumer-lag | jq '.value'

# If lag > 60 seconds:
# 1. Check if processing a large batch
kubectl logs -n engagement deploy/wallet-loyalty-service --tail=20 | grep "processing"

# 2. Increase concurrency
kubectl set env deploy/wallet-loyalty-service -n engagement \
  KAFKA_CONCURRENCY=10 \
  KAFKA_MAX_POLL_RECORDS=1000

# 3. Restart
kubectl rollout restart deploy/wallet-loyalty-service -n engagement
```

### Issue: Duplicate Transaction Credits

```bash
# Check deduplication cache
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/v1/admin/dedup-cache-size | jq '.value'

# Look for duplicate processing
kubectl logs -n engagement deploy/wallet-loyalty-service --tail=200 | grep "duplicate"

# If found, investigate root cause and manual correction
```

---

## Rollback Procedures

```bash
helm rollback wallet-loyalty-service 1 -n engagement
kubectl rollout status deploy/wallet-loyalty-service -n engagement --timeout=5m

# Verify balance consistency
kubectl exec -n engagement deploy/wallet-loyalty-service -- \
  curl -s http://localhost:8104/v1/admin/balance-consistency | jq '.accuracy_percent'
```

---

## Performance Tuning

### Cache Settings

```bash
helm upgrade wallet-loyalty-service deploy/helm/wallet-loyalty-service \
  -n engagement \
  --set cache.maxSize=100000 \
  --set cache.ttlMinutes=60
```

### Database Connection Pool

```bash
# For high transaction volume
kubectl set env deploy/wallet-loyalty-service -n engagement \
  DB_POOL_SIZE=40 \
  DB_POOL_MAX_IDLE_TIME=600000
```

---

## Monitoring & Alerting

### Key Metrics

```
# Wallet balance cache hit rate
wallet_cache_hit_rate

# Balance consistency accuracy
wallet_balance_consistency_accuracy

# Total wallet balance in system
wallet_total_balance_cents

# Loyalty points issued per transaction
loyalty_points_per_transaction_rate
```

---

## On-Call Handoff

- [ ] Balance consistency? `kubectl exec -n engagement deploy/wallet-loyalty-service -- curl -s http://localhost:8104/v1/admin/balance-consistency`
- [ ] Cache hit rate? `kubectl exec -n engagement deploy/wallet-loyalty-service -- curl -s http://localhost:8104/metrics/cache-hit-rate`
- [ ] Kafka consumer lag? `kubectl exec -n engagement deploy/wallet-loyalty-service -- curl -s http://localhost:8104/metrics/kafka-consumer-lag`

---

## Related Documentation

- **Deployment**: `/deploy/helm/wallet-loyalty-service/`
- **Architecture**: `/docs/services/wallet-loyalty-service/README.md`
- **SLO**: `/docs/slos/service-slos.md#wallet`
