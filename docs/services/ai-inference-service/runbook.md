# AI Inference Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify GPU/accelerator availability (CUDA, TensorRT)
  ```bash
  kubectl exec -n ai deploy/ai-inference-service -- \
    curl -s http://localhost:8118/v1/admin/gpu-status | jq '.available_gpus'
  ```

- [ ] Check ML model cache (models should be pre-loaded)
  ```bash
  kubectl exec -n ai deploy/ai-inference-service -- \
    curl -s http://localhost:8118/v1/admin/model-cache-status | jq '.loaded_models'
  ```

- [ ] Verify connection to model registry / storage
  ```bash
  kubectl exec -n ai deploy/ai-inference-service -- \
    curl -s http://localhost:8118/v1/admin/model-registry-health | jq '.status'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Model inference latency (p99 should be <200ms)
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/metrics/inference-latency-p99 | jq '.value'

# GPU memory utilization (should be 70-90%)
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/v1/admin/gpu-memory-usage | jq '.percent'

# Current replica count
kubectl get deploy ai-inference-service -n ai -o jsonpath='{.spec.replicas}'
```

### Blue-Green Deployment

```bash
# Critical: GPU resources must be available
kubectl get nodes -L nvidia.com/gpu

# Upgrade
helm upgrade ai-inference-service deploy/helm/ai-inference-service -n ai

# Monitor rollout (slower due to model loading)
kubectl rollout status deploy/ai-inference-service -n ai --timeout=15m

# Verify models loaded and inference working
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/v1/admin/model-cache-status | jq '.loaded_models | length'

# Verify inference latency maintained
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/metrics/inference-latency-p99 | jq '.value'
```

---

## Incident Response Procedures

### P0 - AI Inference Service Down

**Symptom**: Fraud detection, recommendations not working, model inference failing

**Diagnosis**:
```bash
kubectl get pods -n ai -l app=ai-inference-service
kubectl logs -n ai deploy/ai-inference-service --tail=100 | grep -i "error\|exception\|cuda\|model"

# Check GPU availability
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/v1/admin/gpu-status | jq '.'

# Check model cache
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/v1/admin/model-cache-status | jq '.loaded_models'

# Check model registry
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/v1/admin/model-registry-health | jq '.status'
```

**Resolution**:
1. If GPU unavailable: Scale up nodes with GPU or adjust scheduling
2. If model missing: Restore from model registry
3. Restart pod: `kubectl rollout restart deploy/ai-inference-service -n ai`
4. Scale up: `kubectl scale deploy ai-inference-service -n ai --replicas=3` (CPU fallback available)

### P1 - Inference Latency High (>500ms)

**Diagnosis**:
```bash
# Check GPU memory
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/v1/admin/gpu-memory-usage | jq '.percent'

# Check if model swapping/thrashing
kubectl logs -n ai deploy/ai-inference-service | grep "model.*swap\|memory.*low"

# Check inference queue depth
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/metrics/inference-queue-depth | jq '.value'
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
  curl -s http://localhost:8118/v1/admin/model-version | jq '.versions'

# Check model registry version
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/v1/admin/model-registry-version | jq '.latest_version'
```

**Resolution**:
1. Pull new model: `kubectl exec deploy/ai-inference-service -n ai -- curl -X POST http://localhost:8118/v1/admin/reload-models`
2. Restart pods: `kubectl rollout restart deploy/ai-inference-service -n ai`

---

## Common Issues & Troubleshooting

### Issue: Out of GPU Memory (OOM on GPU)

```bash
# Check GPU memory
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/v1/admin/gpu-memory-usage | jq '.percent'

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
  curl -s http://localhost:8118/v1/admin/cuda-diagnostics | jq '.errors'

# If incompatible:
# 1. Update node GPU drivers
# 2. Or deploy CPU-only fallback: `kubectl set env deploy/ai-inference-service -n ai GPU_ENABLED=false`
# 3. Restart pod
```

### Issue: Model Inference Producing Wrong Results

```bash
# Test model with known input
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -X POST http://localhost:8118/v1/infer \
  -H "Content-Type: application/json" \
  -d '{"model":"fraud_model","input":[...]}' | jq '.result'

# Check model version matches expected
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -s http://localhost:8118/v1/admin/model-version | jq '.fraud_model'

# If wrong version: Reload models
kubectl exec -n ai deploy/ai-inference-service -- \
  curl -X POST http://localhost:8118/v1/admin/reload-models
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

- [ ] GPU memory usage? `kubectl exec -n ai deploy/ai-inference-service -- curl -s http://localhost:8118/v1/admin/gpu-memory-usage`
- [ ] Model versions loaded? `kubectl exec -n ai deploy/ai-inference-service -- curl -s http://localhost:8118/v1/admin/model-cache-status`
- [ ] Inference latency? `kubectl exec -n ai deploy/ai-inference-service -- curl -s http://localhost:8118/metrics/inference-latency-p99`

---

## Related Documentation

- **Deployment**: `/deploy/helm/ai-inference-service/`
- **Models**: `/deploy/ai-models/`
- **GPU Setup**: `/deploy/gpu-drivers/`
