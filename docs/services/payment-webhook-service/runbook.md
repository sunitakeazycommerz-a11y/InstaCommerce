# Payment Webhook Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify payment-webhook-db connectivity and migrations status
  ```bash
  kubectl exec -n payment deploy/payment-webhook-service -- \
    curl -s http://localhost:8106/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check webhook credentials and signing keys
  ```bash
  kubectl get secrets -n payment payment-webhook-secrets -o yaml | grep -E "signing.key|gateway"
  ```

- [ ] Verify Kafka topic subscriptions (payment.events, payment.completed)
  ```bash
  kubectl exec -n payment deploy/payment-webhook-service -- \
    curl -s http://localhost:8106/actuator/health | jq '.components.kafka'
  ```

- [ ] Check payment-service connectivity for webhook re-triggering
  ```bash
  kubectl exec -n payment deploy/payment-webhook-service -- \
    curl -v http://payment-service:8080/health
  ```

- [ ] Validate webhook endpoint signature verification
  ```bash
  # Should have non-empty signing keys
  kubectl get secret -n payment payment-webhook-secrets -o jsonpath='{.data.signing-key-1}' | base64 -d | wc -c
  ```

---

## Deployment Procedures

### Pre-Deployment Health Baseline

```bash
# Check current replica count
kubectl get deploy payment-webhook-service -n payment -o jsonpath='{.spec.replicas}'

# Baseline queue depth (should be <100)
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/metrics/webhook-queue-depth | jq '.value'
```

### Blue-Green Deployment

```bash
# 1. Current version
kubectl get deploy -n payment payment-webhook-service -o jsonpath='{.spec.template.spec.containers[0].image}'

# 2. Stage update
helm upgrade payment-webhook-service deploy/helm/payment-webhook-service \
  -n payment \
  --values deploy/helm/values.yaml \
  --values deploy/helm/values-prod.yaml

# 3. Monitor rollout (webhook services are typically faster: 1-2 min)
kubectl rollout status deploy/payment-webhook-service -n payment --timeout=5m

# 4. Verify replicas
kubectl get pods -n payment -l app=payment-webhook-service
```

### Kafka Consumer Group Rebalancing

Webhook service consumes from payment topics and must handle rebalancing:

```bash
# Monitor consumer lag during deployment
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/metrics/kafka-consumer-lag | jq '.measurements'

# Expected behavior: Lag increases during deployment, then catches up
```

### Rollback

```bash
# Immediate rollback
helm rollback payment-webhook-service 1 -n payment

# Monitor
kubectl rollout status deploy/payment-webhook-service -n payment --timeout=5m

# Check if stuck processing a message
kubectl logs -n payment deploy/payment-webhook-service --tail=50 | grep -i "processing\|retry"
```

---

## Post-Deployment Validation

### Service Startup Verification

```bash
# Check Kafka consumer group registration
kubectl logs -n payment deploy/payment-webhook-service --tail=30 | grep -i "joined\|rebalance"

# Verify webhook port listening
kubectl exec -n payment deploy/payment-webhook-service -- \
  netstat -tlnp | grep 8106
```

### Endpoint Smoke Tests

```bash
POD=$(kubectl get pods -n payment -l app=payment-webhook-service -o jsonpath='{.items[0].metadata.name}')

# 1. Health check
kubectl exec -n payment pod/$POD -- \
  curl -s http://localhost:8106/actuator/health | jq '.status'

# 2. Webhook history endpoint
kubectl exec -n payment pod/$POD -- \
  curl -s http://localhost:8106/v1/webhooks?limit=10 \
  -H "Authorization: Bearer admin-token" | jq '.webhooks | length'

# 3. Test webhook re-send
kubectl exec -n payment pod/$POD -- \
  curl -X POST http://localhost:8106/v1/webhooks/latest/retry \
  -H "Authorization: Bearer admin-token" | jq '.status'
```

### Kafka Connectivity Validation

```bash
# Verify consumer group exists and is active
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/actuator/health/kafka | jq '.components.kafka'

# Check consumer lag
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/metrics/consumer-lag | jq '.value'
```

---

## Health Check Procedures

### Liveness Probe

Endpoint: `GET /actuator/health/live`

```bash
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/actuator/health/live | jq .
```

**Restart triggers**:
- Kafka consumer stuck (no message processed in 5 min)
- Database connection lost

### Readiness Probe

Endpoint: `GET /actuator/health/ready`

```bash
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/actuator/health/ready | jq .
```

**Load balancer removal triggers**:
- Consumer group not joined
- Webhook queue backing up (>1000 messages)

---

## Incident Response Procedures

### P0 - Webhook Delivery Completely Down

**Symptom**: Payment events not being delivered to webhooks (external merchants affected)

**Diagnosis**:
```bash
# Check Kafka consumer is connected
kubectl logs -n payment deploy/payment-webhook-service --tail=50 | grep -i "consumer\|kafka"

# Check webhook queue depth
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/metrics/webhook-queue-depth | jq '.value'

# Check database connection
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/actuator/health/db | jq '.status'

# Check outgoing HTTP connectivity
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -v http://example-merchant.com/webhooks/payment --max-time 5
```

**Resolution**:
1. Scale up replicas to handle backlog: `kubectl scale deploy payment-webhook-service -n payment --replicas=5`
2. If Kafka consumer stuck: `kubectl rollout restart deploy/payment-webhook-service -n payment`
3. If database down: Check payment-webhook-db cluster
4. If outbound connectivity issue: Check NetworkPolicy egress rules

### P1 - Webhook Delivery Degraded (>5% failure rate)

**Symptom**: Some webhooks failing, retry queue growing

**Diagnosis**:
```bash
# Check retry queue
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/metrics/webhook-retry-queue | jq '.value'

# Check last N failures
kubectl logs -n payment deploy/payment-webhook-service --tail=200 | grep "delivery failed"

# Check merchant endpoint availability
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -v http://merchant-endpoint.com --max-time 10 -w "Status: %{http_code}\n"
```

**Resolution**:
1. Check merchant endpoint status (not service issue)
2. Increase retry exponential backoff timeout
3. If internal services affected: Check network policies

### P2 - Intermittent Delivery Issues

**Diagnosis**:
```bash
# Check connection pool
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/actuator/metrics/hikaricp.connections | jq '.measurements'

# Check thread pool
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/actuator/metrics/jvm.threads | jq '.measurements'
```

**Resolution**:
1. Increase HTTP connection pool for outgoing requests
2. Scale replicas to reduce per-pod load

---

## Common Issues & Troubleshooting

### Issue: Webhook Queue Backing Up

```bash
# Check queue depth
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/metrics/webhook-queue-depth | jq '.value'

# If > 5000:
# 1. Check why webhooks aren't being sent
kubectl logs -n payment deploy/payment-webhook-service --tail=100 | grep -i "error\|failed"

# 2. Check if merchant endpoints are responding
kubectl exec -n payment deploy/payment-webhook-service -- \
  timeout 5 bash -c 'for i in {1..5}; do curl -s -o /dev/null -w "%{http_code}" http://merchant-endpoint.com/webhooks; echo; done'

# 3. Scale up to process faster
kubectl scale deploy payment-webhook-service -n payment --replicas=5

# 4. Temporarily disable failed endpoints (manual intervention)
kubectl patch -n payment deploy payment-webhook-service \
  -p '{"spec":{"template":{"spec":{"env":[{"name":"WEBHOOK_BACKOFF_THRESHOLD","value":"10"}]}}}}'
```

### Issue: Signing Key Rotation Failed

```bash
# Check current signing keys
kubectl get secret -n payment payment-webhook-secrets -o yaml | grep -E "signing-key-[0-9]"

# If only old key exists:
# 1. Generate new key
openssl genrsa -out signing-key-new.pem 2048

# 2. Update secret
kubectl create secret generic payment-webhook-secrets-new \
  --from-file=signing-key-1=signing-key-old.pem \
  --from-file=signing-key-2=signing-key-new.pem \
  -n payment --dry-run=client -o yaml | kubectl apply -f -

# 3. Restart pods to pick up new keys
kubectl rollout restart deploy/payment-webhook-service -n payment
```

### Issue: Consumer Group Rebalancing Loops

```bash
# Check consumer group status
kubectl logs -n payment deploy/payment-webhook-service --tail=50 | grep -i "rebalance\|joined"

# If rebalancing repeatedly:
# 1. Check for pod crashes
kubectl get events -n payment --field-selector involvedObject.name=payment-webhook-service

# 2. Increase session timeout
kubectl set env deploy/payment-webhook-service -n payment \
  KAFKA_SESSION_TIMEOUT_MS=30000 \
  KAFKA_HEARTBEAT_INTERVAL_MS=10000

# 3. Restart
kubectl rollout restart deploy/payment-webhook-service -n payment
```

---

## Rollback Procedures

```bash
# 1. Rollback
helm rollback payment-webhook-service 1 -n payment

# 2. Monitor rebalancing
kubectl logs -n payment deploy/payment-webhook-service --tail=50 | grep "rebalance"

# 3. Verify webhook queue draining
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/metrics/webhook-queue-depth | jq '.value'

# Wait for queue to return to baseline (<100)
```

---

## Performance Tuning

### HTTP Connection Pool

```bash
# For high-volume webhook delivery (>10K/sec):
kubectl set env deploy/payment-webhook-service -n payment \
  HTTP_CONNECTION_POOL_SIZE=100 \
  HTTP_CONNECTION_TIMEOUT_SECONDS=5
```

### Kafka Consumer Threads

```bash
# Current thread count
kubectl exec -n payment deploy/payment-webhook-service -- \
  curl -s http://localhost:8106/actuator/metrics/jvm.threads.live | jq '.measurements'

# Tune consumer concurrency
kubectl set env deploy/payment-webhook-service -n payment \
  KAFKA_CONCURRENCY=5
```

---

## Monitoring & Alerting

### Key Metrics

```
# Webhook delivery latency
webhook_delivery_seconds_p99

# Webhook queue depth
webhook_queue_depth

# Retry rate
rate(webhook_retry_attempts_total[5m])

# Kafka consumer lag
kafka_consumer_lag{consumer_group="payment-webhook-service"}
```

### Alert Rules

```yaml
- alert: WebhookQueueBackup
  expr: webhook_queue_depth > 5000
  for: 5m
  annotations:
    severity: high
    runbook: payment-webhook-service/runbook.md#p1---webhook-delivery-degraded
```

---

## On-Call Handoff

- [ ] Any backed-up webhooks? `kubectl exec -n payment deploy/payment-webhook-service -- curl -s http://localhost:8106/metrics/webhook-queue-depth`
- [ ] Recent merchant endpoint failures?
- [ ] Signing key expiration dates?
- [ ] Kafka consumer lag trending?

---

## Related Documentation

- **Deployment**: `/deploy/helm/payment-webhook-service/`
- **API**: `/docs/services/payment-webhook-service/README.md`
- **Alert Rules**: `/monitoring/prometheus/payment-webhook-rules.yaml`
