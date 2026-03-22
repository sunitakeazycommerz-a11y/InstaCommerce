# AI Inference Service

## Overview

The ai-inference-service provides ML model serving capabilities for InstaCommerce, handling real-time inference requests for ETA predictions, demand forecasting, and dynamic pricing. It supports batching, model versioning, and automatic cache invalidation for sub-500ms latency at p99.

**Service Ownership**: Platform Team - AI/ML Infrastructure Tier 3
**Language**: Python 3.13 (FastAPI + TensorFlow/XGBoost)
**Default Port**: 8090
**Status**: Tier 3 Strategic (ML-driven features)

## SLOs & Availability

- **Availability SLO**: 99% (7 hours downtime/month)
- **P99 Inference Latency**: < 500ms (single request)
- **Batch Throughput**: 1,000 predictions/second
- **Model Accuracy**: > 99% for ETA predictions (MAE < 2 minutes)
- **Model Update Frequency**: Daily (new models deployed by 6 AM UTC)

## Key Responsibilities

1. **Model Serving**: Load and serve TensorFlow/XGBoost models from GCS model registry
2. **Request Batching**: Accumulate requests over 50ms windows for efficient batch inference
3. **Model Versioning**: Support A/B testing via shadow models (10% traffic to candidate)
4. **Feature Transformation**: Real-time feature engineering from BigQuery feature store

## Deployment

### GKE Deployment
- **Namespace**: ai-infrastructure
- **Replicas**: 3 (GPU-enabled)
- **CPU Request/Limit**: 2 / 4
- **Memory Request/Limit**: 4Gi / 8Gi
- **GPU Request**: 1 NVIDIA A100 (shared across replicas via time-slicing)

### Cluster Configuration
- **Ingress**: Internal-only (accessed via ai-orchestrator-service)
- **NetworkPolicy**: Allow egress to BigQuery, GCS, Redis only
- **Service Account**: ai-inference-service (with GCP IAM for model access)
- **Affinity**: Pod anti-affinity (spread across nodes for inference resilience)

## Model Serving Architecture

```
AI Orchestrator → (ETA Request)
    ↓
Batch Accumulation (50ms window)
    ↓
Feature Transformation (BigQuery + Redis cache)
    ↓
├─ Model A (Primary, TensorFlow) [90% traffic]
│  └─ GPU-accelerated inference
├─ Model B (Shadow, XGBoost) [10% traffic]
│  └─ CPU execution, A/B testing
    ↓
Predictions + Confidence scores
    ↓
Response to callers (routing-eta, pricing services)
```

### Batching Strategy
- **Window Duration**: 50ms (latency/throughput tradeoff)
- **Max Batch Size**: 1,000 requests
- **Flush Triggers**: Timeout OR batch full
- **Timeout Fallback**: Single-request inference (no wait)

### Model Versioning
- **Active Model**: Production version (90% traffic)
- **Shadow Model**: Candidate version (10% traffic, A/B test only)
- **Promotion**: Automatic if shadow accuracy > active for 24 hours
- **Rollback**: Manual via feature flag override

## Integrations

### Synchronous Calls
| Service | Endpoint | Timeout | Purpose |
|---------|----------|---------|---------|
| BigQuery Feature Store | bigquery:cloud.google.com/v1/projects/instacommerce/datasets/feature_store | 3s | Real-time features (distance, surge, etc.) |
| GCS Model Registry | gs://instacommerce-ml-models/eta-model/v202603.1 | 30s | Load TensorFlow saved model |

### Cache Integration
| System | Purpose | TTL |
|--------|---------|-----|
| Redis (ai-inference) | Feature vector cache | 5 min |
| Kubernetes ConfigMap | Model version metadata | 24 hours |

### Downstream
- **AI Orchestrator Service**: Receives batch predictions, disseminates to callers
- **Routing ETA Service**: Consumes ETA predictions for delivery timing

## Endpoints

### Health & Metrics (Unauthenticated)
- `GET /health` - Liveness probe (model loaded, inference ready)
- `GET /metrics` - Prometheus metrics (prediction latency, accuracy, cache hit rate)
- `GET /models` - List loaded models (active + shadow versions)

### Inference API (Requires Service-to-Service Token)
- `POST /predict/eta` - Predict delivery ETA (request batching applied)
- `POST /predict/demand` - Predict demand score (1-10 scale)
- `POST /predict/price-multiplier` - Compute dynamic price adjustment factor

### Debug API (Admin Token Required)
- `POST /models/reload` - Force reload model from GCS (emergency use)
- `GET /features/cache` - Show cached feature vectors (for troubleshooting)
- `POST /shadow-model/{action}` - Enable/disable shadow model (A/B testing)

### Example Requests

```bash
# Check inference service health
curl -s http://ai-inference-service:8090/health | jq .

# List loaded models
curl -s http://ai-inference-service:8090/models | jq '.models[] | {name, version, accuracy}'

# Make ETA prediction (batching applied)
curl -X POST http://ai-inference-service:8090/predict/eta \
  -H "Authorization: Bearer $SERVICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "origin": {"lat": 37.7749, "lon": -122.4194},
    "destination": {"lat": 37.3382, "lon": -121.8863},
    "time_of_day": 18,
    "day_of_week": 3
  }' | jq '.predictions[0]'

# Check model metrics
curl -s http://ai-inference-service:8090/metrics | grep ai_inference_prediction_accuracy
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8090
MODEL_REGISTRY_BUCKET=gs://instacommerce-ml-models
ACTIVE_MODEL_PATH=eta-model/v202603.1
SHADOW_MODEL_PATH=eta-model/v202603-candidate
SHADOW_MODEL_ENABLED=true
SHADOW_TRAFFIC_PERCENT=10
BATCH_WINDOW_MS=50
MAX_BATCH_SIZE=1000
BIGQUERY_PROJECT=instacommerce
BIGQUERY_DATASET=feature_store
BIGQUERY_FEATURE_TABLE=live_features
REDIS_URL=redis://redis-cache.ai-infrastructure:6379
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
LOG_LEVEL=info
```

### config.yaml
```yaml
ai-inference:
  models:
    active:
      path: ${ACTIVE_MODEL_PATH}
      type: tensorflow
      cache-dir: /tmp/models/active
    shadow:
      path: ${SHADOW_MODEL_PATH}
      type: xgboost
      enabled: ${SHADOW_MODEL_ENABLED:true}
      traffic-percent: ${SHADOW_TRAFFIC_PERCENT:10}
  batching:
    window-ms: ${BATCH_WINDOW_MS:50}
    max-batch-size: ${MAX_BATCH_SIZE:1000}
    flush-timeout-ms: 100
  features:
    bigquery:
      project: ${BIGQUERY_PROJECT}
      dataset: ${BIGQUERY_DATASET}
      table: ${BIGQUERY_FEATURE_TABLE}
      query-timeout-seconds: 3
    cache:
      url: ${REDIS_URL}
      ttl-seconds: 300
  serving:
    gpu-enabled: true
    cpu-fallback: true
    thread-pool-size: 8
```

## Monitoring & Alerts

### Key Metrics
- `ai_inference_prediction_latency_ms` (histogram) - Single request latency
- `ai_inference_batch_size` (histogram) - Requests per batch
- `ai_inference_batch_latency_ms` (histogram) - Batch inference time (GPU)
- `ai_inference_model_accuracy` (gauge) - Primary model accuracy (vs ground truth ETA)
- `ai_inference_shadow_model_accuracy` (gauge) - Candidate model accuracy (A/B testing)
- `ai_inference_feature_cache_hit_rate` (gauge) - BigQuery feature cache efficiency
- `ai_inference_model_load_time_seconds` (histogram) - Model initialization time

### Alerting Rules
- `ai_inference_prediction_latency_ms_p99 > 500` - Latency SLO violation (model too large, batch backlog)
- `ai_inference_model_accuracy < 0.98` - Primary model accuracy degradation (retrain recommended)
- `ai_inference_shadow_model_accuracy > ai_inference_model_accuracy` - Shadow model better than active (promotion candidate)
- `ai_inference_feature_cache_hit_rate < 0.80` - Low cache efficiency (reduce feature count or increase Redis TTL)
- `ai_inference_gpu_utilization > 90%` - GPU saturation (scale up replicas or increase batching window)

### Logging
- **WARN**: Feature missing from BigQuery, model load timeout, shadow model disabled due to errors
- **INFO**: Model loaded successfully, shadow model promoted, batch flushed (size, latency)
- **ERROR**: GCS model download failed, GPU out of memory, inference exception, authentication failures

## Security Considerations

### Threat Mitigations
1. **Model Versioning**: Signed model artifacts prevent tampering (GCS object versioning + integrity checksums)
2. **Feature Privacy**: Feature vectors never logged (only aggregated metrics)
3. **API Rate Limiting**: Per-service quota (routing-eta-service: 10k/min, pricing-service: 5k/min)
4. **Audit Logging**: All inference requests logged (anonymized: order_id only, no user PII)

### Known Risks
- **Model poisoning**: Compromised GCS account can inject malicious models (mitigated by service account RBAC)
- **Feature store compromise**: Stale/incorrect features degrade prediction accuracy (mitigated by BigQuery audit logs)
- **GPU memory exhaustion**: Malicious batch request can OOM pod (mitigated by max_batch_size limit)

## Troubleshooting

### High Inference Latency (> 500ms p99)
1. **Check GPU utilization**: `nvidia-smi` (if > 90%, scale replicas or reduce batch size)
2. **Check feature cache hit rate**: `ai_inference_feature_cache_hit_rate` (if < 0.8, BigQuery slow)
3. **Review batch window**: `BATCH_WINDOW_MS` too large (increase window for better throughput)

### Model Accuracy Degradation
1. **Verify active model version**: `curl http://ai-inference-service:8090/models` (wrong version?)
2. **Monitor shadow model**: Compare candidate accuracy vs. active over 24-hour window
3. **Check feature drift**: Compare feature distributions from 7 days ago vs. today

### Out of Memory Errors
1. **Reduce batch size**: Lower `MAX_BATCH_SIZE` (1000 -> 256) or disable shadow model
2. **Monitor peak load**: If `ai_inference_batch_size` p99 > 500, scale replicas
3. **Enable CPU fallback**: Set `cpu-fallback: true` for graceful inference degradation

## Related Documentation

- **ADR-013**: Feature Flag Cache Invalidation (similar pub/sub pattern for model updates)
- **ADR-016**: ML Model Governance (model versioning, A/B testing strategy)
- **Runbook**: ai-inference-service/runbook.md
- **ML Pipeline**: docs/ml/model-training-pipeline.md
- **AI Orchestrator**: docs/services/ai-orchestrator-service/README.md
