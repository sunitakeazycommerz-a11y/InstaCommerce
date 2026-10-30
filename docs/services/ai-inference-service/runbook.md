# AI Inference Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify AI inference service readiness probe
  ```bash
  kubectl exec deploy/ai-inference-service -- \
    curl -s http://localhost:8101/health/ready | jq .
  ```

- [ ] Check loaded ML models and weights registry
  ```bash
  kubectl exec deploy/ai-inference-service -- \
    curl -s http://localhost:8101/models | jq .
  ```

- [ ] Verify Prometheus metrics endpoint
  ```bash
  kubectl exec deploy/ai-inference-service -- \
    curl -s http://localhost:8101/metrics | head -20
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Model inference latency metrics
kubectl exec deploy/ai-inference-service -- \
  curl -s http://localhost:8101/metrics | grep -E "inference_requests_total|inference_latency_seconds"

# Current replica count
kubectl get deploy ai-inference-service -o jsonpath='{.spec.replicas}'
```

### Rolling Deployment

```bash
# Upgrade
helm upgrade ai-inference-service deploy/helm/ai-inference-service

# Monitor rollout
kubectl rollout status deploy/ai-inference-service --timeout=10m

# Verify models loaded and inference working
kubectl exec deploy/ai-inference-service -- \
  curl -s http://localhost:8101/models | jq '.models | keys'

# Smoke test ETA inference
kubectl exec deploy/ai-inference-service -- \
  curl -s -X POST http://localhost:8101/inference/eta \
  -H "Content-Type: application/json" \
  -d '{"distance_km": 3.5, "item_count": 4, "traffic_factor": 1.1}' | jq .
```

---

## Incident Response Procedures

### P0 - AI Inference Service Down

**Symptom**: AI Orchestrator reporting model inference timeouts, ETA or ranking unavailable

**Diagnosis**:
```bash
kubectl get pods -l app=ai-inference-service
kubectl logs deploy/ai-inference-service --tail=100 | grep -i "error\|exception\|weights"

# Check readiness probe
kubectl exec deploy/ai-inference-service -- \
  curl -s http://localhost:8101/health/ready | jq .

# Check loaded model registry
kubectl exec deploy/ai-inference-service -- \
  curl -s http://localhost:8101/models | jq .
```

**Resolution**:
1. If weights missing: Check `AI_INFERENCE_WEIGHTS_PATH` and ConfigMap mount
2. Restart pod: `kubectl rollout restart deploy/ai-inference-service`
3. Scale up: `kubectl scale deploy ai-inference-service --replicas=5`

### P1 - Inference Latency High (>500ms)

**Diagnosis**:
```bash
# Check GPU memory
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8101/v1/admin/gpu-memory-usage | jq '.percent'

# Check if model swapping/thrashing
kubectl logs -n ai deploy/ai-inference-service | grep "model.*swap\|memory.*low"

# Check inference queue depth
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8101/metrics/inference-queue-depth | jq '.value'
```

**Resolution**:
1. If GPU memory high: Reduce batch size or model precision
2. If queue deep: Scale up replicas or increase GPU count
3. Use lower-precision model (int8 vs float32)

### P2 - Model Not Updated After Release

**Diagnosis**:
```bash
# Check model version
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8101/v1/admin/model-version | jq '.versions'

# Check model registry version
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8101/v1/admin/model-registry-version | jq '.latest_version'
```

**Resolution**:
1. Pull new model: `kubectl exec deploy/ai-inference-service -n ai -- curl -X POST http://localhost:8101/v1/admin/reload-models`
2. Restart pods: `kubectl rollout restart deploy/ai-inference-service -n ai`

---

## Common Issues & Troubleshooting

### Issue: Out of GPU Memory (OOM on GPU)

```bash
# Check GPU memory
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8101/v1/admin/gpu-memory-usage | jq '.percent'

# If > 95%:
# 1. Reduce inference batch size
kubectl set env deploy/ai-inference-service -n ai \
  INFERENCE_BATCH_SIZE=8  # from 32

# 2. Use lower precision (faster and smaller)
kubectl set env deploy/ai-inference-service -n ai \
  MODEL_PRECISION=int8  # from float32

# 3. Restart
kubectl rollout restart deploy/ai-inference-service -n ai
```

### Issue: CUDA Driver/Library Issues

```bash
# Check CUDA version compatibility
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8101/v1/admin/cuda-diagnostics | jq '.errors'

# If incompatible:
# 1. Update node GPU drivers
# 2. Or deploy CPU-only fallback: `kubectl set env deploy/ai-inference-service -n ai GPU_ENABLED=false`
# 3. Restart pod
```

### Issue: Model Inference Producing Wrong Results

```bash
# Test model with known input
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -X POST http://localhost:8101/v1/infer \
  -H "Content-Type: application/json" \
  -d '{"model":"fraud_model","input":[...]}' | jq '.result'

# Check model version matches expected
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8101/v1/admin/model-version | jq '.fraud_model'

# If wrong version: Reload models
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -X POST http://localhost:8101/v1/admin/reload-models
```

---

## Performance Tuning

### Model Precision

```bash
# Use int8 quantized models for 4-8x faster inference
kubectl set env deploy/ai-inference-service -n ai \
  MODEL_PRECISION=int8 \
  QUANTIZATION_ENABLED=true
```

### Batch Processing

```bash
# For throughput optimization
kubectl set env deploy/ai-inference-service -n ai \
  INFERENCE_BATCH_SIZE=64 \
  BATCH_TIMEOUT_MS=100
```

### GPU Utilization

```bash
# Check and tune GPU memory
kubectl describe node -l nvidia.com/gpu | grep "nvidia.com/gpu"
```

---

## Monitoring & Alerting

### Key Metrics

```
# Inference latency
histogram_quantile(0.99, rate(ai_inference_latency_seconds_bucket[5m]))

# GPU memory utilization
ai_gpu_memory_usage_percent

# Model cache hit rate
ai_model_cache_hit_rate

# Inference throughput
rate(ai_inferences_total[5m])
```

---

## On-Call Handoff

- [ ] GPU memory usage? `kubectl exec -n ai deploy/ai-inference-service -- curl -s http://localhost:8101/v1/admin/gpu-memory-usage`
- [ ] Model versions loaded? `kubectl exec -n ai deploy/ai-inference-service -- curl -s http://localhost:8101/v1/admin/model-cache-status`
- [ ] Inference latency? `kubectl exec -n ai deploy/ai-inference-service -- curl -s http://localhost:8101/metrics/inference-latency-p99`

---

## Related Documentation

- **Deployment**: `/deploy/helm/ai-inference-service/`
- **Models**: `/deploy/ai-models/`
- **GPU Setup**: `/deploy/gpu-drivers/`
