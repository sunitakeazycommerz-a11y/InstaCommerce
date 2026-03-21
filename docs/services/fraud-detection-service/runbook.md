# Fraud Detection Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify fraud-detection-db (PostgreSQL) is accessible
  ```bash
  kubectl exec -n engagement deploy/fraud-detection-service -- \
    curl -s http://localhost:8100/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check ML model availability (fraud scoring model)
  ```bash
  kubectl exec -n engagement deploy/fraud-detection-service -- \
    curl -s http://localhost:8100/v1/admin/model-status | jq '.status'
  ```

- [ ] Verify Redis connectivity (rule caching)
  ```bash
  kubectl exec -n engagement deploy/fraud-detection-service -- \
    curl -s http://localhost:8100/actuator/health | jq '.components.redis'
  ```

- [ ] Check Kafka connectivity (order.created, payment.initiated topics)
  ```bash
  kubectl exec -n engagement deploy/fraud-detection-service -- \
    curl -s http://localhost:8100/actuator/health | jq '.components.kafka'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# ML model accuracy on recent data (should be >95%)
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/metrics/model-accuracy | jq '.value'

# False positive rate (should be <1%)
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/metrics/false-positive-rate | jq '.value'

# Fraud detection latency (p99 should be <500ms)
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/metrics/detection-latency-p99 | jq '.value'
```

### Blue-Green Deployment

```bash
helm upgrade fraud-detection-service deploy/helm/fraud-detection-service -n engagement
kubectl rollout status deploy/fraud-detection-service -n engagement --timeout=10m

# Verify model still loaded
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/v1/admin/model-status | jq '.status'

# Verify accuracy maintained
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/metrics/model-accuracy | jq '.value'
```

---

## Health Check Procedures

### Liveness Probe: `/actuator/health/live`

### Readiness Probe: `/actuator/health/ready`

**Load balancer removal triggers**: ML model not loaded, database down, Kafka consumer not joined

---

## Incident Response Procedures

### P0 - Fraud Detection Offline

**Symptom**: Transactions not being analyzed, fraud rules not evaluated

**Diagnosis**:
```bash
kubectl get pods -n engagement -l app=fraud-detection-service
kubectl logs -n engagement deploy/fraud-detection-service --tail=100

# Check ML model
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/v1/admin/model-status | jq '.'

# Check Kafka consumer
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/metrics/kafka-consumer-lag | jq '.value'
```

**Resolution**:
1. Scale up: `kubectl scale deploy fraud-detection-service -n engagement --replicas=5`
2. If ML model missing: Restore from model registry or use fallback rules
3. Restart pod: `kubectl rollout restart deploy/fraud-detection-service -n engagement`

### P1 - High False Positive Rate (>5%)

**Diagnosis**:
```bash
# Check false positive rate
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/metrics/false-positive-rate | jq '.value'

# Check model accuracy
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/metrics/model-accuracy | jq '.value'

# Analyze recent predictions
kubectl logs -n engagement deploy/fraud-detection-service --tail=100 | grep "fraud.*detected"
```

**Resolution**:
1. If model accuracy low: Retrain model with recent data
2. Adjust detection threshold: `kubectl set env deploy/fraud-detection-service FRAUD_THRESHOLD=0.85`
3. Review flagged transactions manually

### P2 - Detection Latency High (>1s)

**Diagnosis**:
```bash
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/metrics/detection-latency-p99 | jq '.value'
```

**Resolution**:
1. Increase cache for rule lookups
2. Scale up replicas
3. Check if model inference is slow (GPU availability)

---

## Common Issues & Troubleshooting

### Issue: ML Model Inference Slow

```bash
# Check model load time
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/v1/admin/model-stats | jq '.inference_time_ms'

# If >100ms, increase GPU resources or use quantized model
helm upgrade fraud-detection-service deploy/helm/fraud-detection-service \
  -n engagement \
  --set gpu.enabled=true \
  --set gpu.count=1
```

### Issue: Data Drift (Model Accuracy Degrading)

```bash
# Check model accuracy trend
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -s http://localhost:8100/metrics/model-accuracy-24h | jq '.hourly_accuracy'

# If declining: Trigger model retraining
kubectl exec -n engagement deploy/fraud-detection-service -- \
  curl -X POST http://localhost:8100/v1/admin/retrain-model \
  -H "Authorization: Bearer admin-token" | jq '.status'
```

### Issue: Threshold Too Aggressive/Lenient

```bash
# Adjust fraud detection threshold
kubectl set env deploy/fraud-detection-service -n engagement \
  FRAUD_THRESHOLD=0.75  # More sensitive

# Or less sensitive:
# FRAUD_THRESHOLD=0.95  # Less sensitive

# Restart pods
kubectl rollout restart deploy/fraud-detection-service -n engagement
```

---

## Performance Tuning

### Model Optimization

```bash
# Use quantized model for faster inference
helm upgrade fraud-detection-service deploy/helm/fraud-detection-service \
  -n engagement \
  --set model.quantized=true \
  --set model.precisionType=int8
```

### Cache Sizing

```bash
# Increase rule cache
helm upgrade fraud-detection-service deploy/helm/fraud-detection-service \
  -n engagement \
  --set cache.maxSize=50000 \
  --set cache.ttlMinutes=60
```

---

## Monitoring & Alerting

### Key Metrics

```
# Fraud detection accuracy
fraud_model_accuracy

# False positive rate
fraud_false_positive_rate

# Detection latency
histogram_quantile(0.99, rate(fraud_detection_seconds_bucket[5m]))

# Fraud transactions detected
rate(fraud_transactions_detected_total[5m])
```

---

## On-Call Handoff

- [ ] Model accuracy? `kubectl exec -n engagement deploy/fraud-detection-service -- curl -s http://localhost:8100/metrics/model-accuracy`
- [ ] False positive rate? `kubectl exec -n engagement deploy/fraud-detection-service -- curl -s http://localhost:8100/metrics/false-positive-rate`
- [ ] Detection latency? `kubectl exec -n engagement deploy/fraud-detection-service -- curl -s http://localhost:8100/metrics/detection-latency-p99`

---

## Related Documentation

- **Deployment**: `/deploy/helm/fraud-detection-service/`
- **Architecture**: `/docs/services/fraud-detection-service/README.md`
- **ML Model**: `/deploy/ml/fraud-detection-model/`
