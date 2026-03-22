# Notification Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify notification-db (PostgreSQL) accessibility
  ```bash
  kubectl exec -n engagement deploy/notification-service -- \
    curl -s http://localhost:8090/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check Kafka connectivity (order.created, payment.completed, fulfillment.* topics)
  ```bash
  kubectl exec -n engagement deploy/notification-service -- \
    curl -s http://localhost:8090/actuator/health | jq '.components.kafka'
  ```

- [ ] Verify email provider connectivity (SendGrid/Mailgun)
  ```bash
  kubectl exec -n engagement deploy/notification-service -- \
    curl -s http://localhost:8090/v1/admin/email-provider-health | jq '.status'
  ```

- [ ] Check SMS provider connectivity (Twilio)
  ```bash
  kubectl exec -n engagement deploy/notification-service -- \
    curl -s http://localhost:8090/v1/admin/sms-provider-health | jq '.status'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Pending notifications queue
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/pending-notifications-count | jq '.value'

# Notification delivery success rate (should be >98%)
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/delivery-success-rate | jq '.value'

# Consumer lag (should be <60 sec)
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/kafka-consumer-lag | jq '.value'
```

### Blue-Green Deployment

```bash
helm upgrade notification-service deploy/helm/notification-service -n engagement
kubectl rollout status deploy/notification-service -n engagement --timeout=10m

# Monitor Kafka consumer rebalancing
kubectl logs -n engagement deploy/notification-service --tail=20 | grep -i "joined"

# Verify queue draining
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/pending-notifications-count | jq '.value'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

**Load balancer removal triggers**: Kafka consumer not joined, email/SMS provider down

---

## Incident Response Procedures

### P0 - Notifications Not Being Delivered

**Symptom**: Users not receiving order/payment notifications, queue backing up

**Diagnosis**:
```bash
kubectl get pods -n engagement -l app=notification-service
kubectl logs -n engagement deploy/notification-service --tail=100 | grep -i "error\|exception"

# Check Kafka consumer
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/kafka-consumer-lag | jq '.value'

# Check email provider
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/v1/admin/email-provider-health | jq '.status'

# Check SMS provider
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/v1/admin/sms-provider-health | jq '.status'

# Check pending notifications
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/pending-notifications-count | jq '.value'
```

**Resolution**:
1. Scale up: `kubectl scale deploy notification-service -n engagement --replicas=5`
2. If Kafka consumer stuck: `kubectl rollout restart deploy/notification-service -n engagement`
3. If email provider down: Enable SMS fallback or manual intervention
4. Drain queue: `kubectl exec deploy/notification-service -n engagement -- curl -X POST http://localhost:8090/v1/admin/drain-queue`

### P1 - Slow Notification Delivery (>5min lag)

**Diagnosis**:
```bash
# Check Kafka consumer lag
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/kafka-consumer-lag | jq '.value'

# Check notification processing latency
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/processing-latency-p99 | jq '.value'

# Check provider response time
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/email-provider-latency-p99 | jq '.value'
```

**Resolution**:
1. If consumer lag high: Increase concurrency `kubectl set env deploy/notification-service -n engagement KAFKA_CONCURRENCY=10`
2. If provider latency high: Enable async batching
3. Scale up replicas

### P2 - High Bounce/Delivery Failure Rate (>5%)

**Diagnosis**:
```bash
# Check bounce rate
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/bounce-rate | jq '.value'

# Check failed delivery reasons
kubectl logs -n engagement deploy/notification-service --tail=200 | grep "delivery failed"
```

**Resolution**:
1. Verify email addresses are valid
2. Check with email provider for issues
3. Enable retry with exponential backoff

---

## Common Issues & Troubleshooting

### Issue: Email Provider Rate Limited

```bash
# Check rate limit status
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/v1/admin/email-provider-health | jq '.rate_limit'

# Reduce sending rate
kubectl set env deploy/notification-service -n engagement \
  EMAIL_BATCH_SIZE=100 \
  EMAIL_BATCH_INTERVAL_MS=5000
```

### Issue: SMS Delivery Failures

```bash
# Check SMS provider connectivity
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/v1/admin/sms-provider-health | jq '.status'

# Check failed SMS messages
kubectl logs -n engagement deploy/notification-service | grep "SMS.*failed"

# Enable SMS fallback to email
kubectl set env deploy/notification-service -n engagement \
  SMS_FALLBACK_TO_EMAIL=true
```

### Issue: Notification Deduplication Issues

```bash
# Check duplicate detection
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/v1/admin/dedup-cache | jq '.size'

# Clear dedup cache if needed
kubectl exec -n engagement deploy/notification-service -- \
  curl -X POST http://localhost:8090/v1/admin/clear-dedup-cache \
  -H "Authorization: Bearer admin-token" | jq '.status'
```

---

## Rollback Procedures

```bash
helm rollback notification-service 1 -n engagement
kubectl rollout status deploy/notification-service -n engagement --timeout=5m

# Verify queue draining
kubectl exec -n engagement deploy/notification-service -- \
  curl -s http://localhost:8090/metrics/pending-notifications-count
```

---

## Performance Tuning

### Kafka Consumer

```bash
# For high-volume notifications (>10K/min):
kubectl set env deploy/notification-service -n engagement \
  KAFKA_CONCURRENCY=15 \
  KAFKA_MAX_POLL_RECORDS=1000 \
  BATCH_SIZE=500
```

### Email Batching

```bash
# Send emails in batches
kubectl set env deploy/notification-service -n engagement \
  EMAIL_BATCH_SIZE=500 \
  EMAIL_BATCH_INTERVAL_MS=10000
```

---

## Monitoring & Alerting

### Key Metrics

```
# Notification delivery success rate
notification_delivery_success_rate

# Processing latency
histogram_quantile(0.99, rate(notification_processing_seconds_bucket[5m]))

# Pending notifications queue
notification_queue_size

# Email bounce rate
notification_bounce_rate
```

---

## On-Call Handoff

- [ ] Pending notifications? `kubectl exec -n engagement deploy/notification-service -- curl -s http://localhost:8090/metrics/pending-notifications-count`
- [ ] Delivery success rate? `kubectl exec -n engagement deploy/notification-service -- curl -s http://localhost:8090/metrics/delivery-success-rate`
- [ ] Consumer lag? `kubectl exec -n engagement deploy/notification-service -- curl -s http://localhost:8090/metrics/kafka-consumer-lag`

---

## Related Documentation

- **Deployment**: `/deploy/helm/notification-service/`
- **Architecture**: `/docs/services/notification-service/README.md`
- **Alert Rules**: `/monitoring/prometheus/notification-rules.yaml`
