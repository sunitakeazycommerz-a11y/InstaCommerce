# AI Orchestrator Service

## Overview

The AI orchestrator service is the central hub for AI/ML model selection, request routing, and batch optimization in InstaCommerce. It manages the lifecycle of AI inference requests, routes them to appropriate specialized models (ranking, recommendation, fraud detection), implements intelligent batching for cost optimization, and provides fallback mechanisms for reliability.

**Service Ownership**: AI Platform Team - Model Orchestration
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8089
**Status**: Tier 1 Critical (AI-driven business logic)

## SLOs & Availability

- **Availability SLO**: 99% (7.2 hours downtime/month - fallback degradation acceptable)
- **P99 Latency**: < 1 second for routing decisions
- **Model Selection Accuracy**: 95% (correct model chosen for request type)
- **Batch Optimization**: 50% reduction in API calls to AI inference service (target)
- **Error Rate**: < 1% (errors trigger fallback to non-AI path)
- **Request Deduplication**: 99% catch rate for duplicate requests within 5-minute window

## Key Responsibilities

1. **Request Routing**: Classify incoming AI requests and route to appropriate inference service
2. **Model Selection**: Select optimal model based on request attributes (cost, latency, accuracy trade-offs)
3. **Batch Optimization**: Aggregate requests into efficient batches for inference-service consumption
4. **Fallback Management**: Transparent fallback to non-AI defaults (e.g., random ranking if AI fails)
5. **Circuit Breaker**: Handle inference-service outages gracefully with request queuing

## Deployment

### GKE Deployment
- **Namespace**: ai-platform
- **Replicas**: 3 (HA, stateless)
- **CPU Request/Limit**: 500m / 1000m
- **Memory Request/Limit**: 768Mi / 1.5Gi
- **Horizontal Pod Autoscaler**: 3-8 replicas based on throughput (p99 < 1s)

### Cluster Configuration
- **Ingress**: Internal-only (behind Istio IngressGateway)
- **NetworkPolicy**: Deny-default; allow from search-service, recommendation-engine, fraud-service
- **Service Account**: ai-orchestrator-service
- **PersistentVolume**: Redis-backed request deduplication cache (5-minute retention)

## Request Routing Architecture

```
┌──────────────────────────────────────────────────────────┐
│ Incoming AI Request                                       │
│ POST /ai/v1/infer                                         │
└────────────────────┬─────────────────────────────────────┘
                     │
        ┌────────────▼──────────────────┐
        │ AI Orchestrator               │
        │ 1. Parse request attributes   │
        │ 2. Check deduplication cache  │
        │ 3. Select model + strategy    │
        │ 4. Aggregate with batch       │
        │ 5. Invoke inference-service   │
        └────────────────┬──────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
   Ranking          Recommendation    Fraud Detection
   (search)         (catalog)         (checkout)
```

### Model Selection Strategy

```
Request Features:
  - Request type (ranking, recommendation, fraud_score, etc.)
  - Context (product_id, user_segment, order_value)
  - Latency requirement (strict: <100ms, standard: <500ms, batch: <5s)
  - Accuracy target (high: 95%+, medium: 85%+, low: any)

Selection Logic:
  1. Check feature-flag for AI enablement (by request type)
  2. Filter models by latency + accuracy requirements
  3. Select lowest-cost model matching constraints
  4. Apply fallback if inference-service unavailable

Output:
  - Inference strategy (real-time, batched, fallback)
  - Model version
  - Expected latency bounds
```

### Integrations

| Service | Endpoint | Timeout | Purpose |
|---------|----------|---------|---------|
| ai-inference-service | http://ai-inference-service:8090/infer | 5s | Model inference |
| config-feature-flag-service | http://config-feature-flag-service:8095/flags | 5s | AI enablement flags |
| search-service | Kafka (ai.requests) | - | Consume ranking requests |
| Redis | redis://redis-primary.cache:6379 | 1s | Deduplication cache |

## Endpoints

### Public (Unauthenticated)
- `GET /actuator/health` - Liveness probe
- `GET /actuator/metrics` - Prometheus metrics
- `GET /ai/health` - AI orchestrator health (unprotected)
- `GET /ai/metrics` - Orchestration metrics (unprotected)

### Internal API (Requires X-Internal-Token)
- `POST /api/v1/infer` - Synchronous inference request
- `POST /api/v1/infer/batch` - Batch inference requests (for operational dashboards)
- `GET /api/v1/models/status` - Available models and their status
- `GET /api/v1/requests/{requestId}/status` - Check async request status
- `POST /api/v1/requests/{requestId}/cancel` - Cancel pending request
- `GET /api/v1/metrics/deduplication` - Cache hit rate and deduplication efficiency

### Example Requests

```bash
# Get authorization token
TOKEN=$(curl -X POST http://identity-service:8080/token/internal \
  -H "Content-Type: application/json" \
  -d '{"service":"search-service"}' | jq -r '.token')

# Synchronous inference request (search ranking)
curl -X POST http://ai-orchestrator-service:8089/api/v1/infer \
  -H "X-Internal-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "requestType":"ranking",
    "context":{
      "userId":"user-12345",
      "query":"laptop",
      "productIds":["prod-001","prod-002","prod-003"]
    },
    "latencyRequirement":"strict",
    "accuracyTarget":"high"
  }'

# Batch inference (multiple requests)
curl -X POST http://ai-orchestrator-service:8089/api/v1/infer/batch \
  -H "X-Internal-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "requests":[
      {"requestType":"ranking","context":{"query":"phone"}},
      {"requestType":"recommendation","context":{"userId":"user-12345"}},
      {"requestType":"fraud_score","context":{"orderId":"ord-98765"}}
    ],
    "aggregationStrategy":"parallel"
  }'

# Check model status
curl -X GET http://ai-orchestrator-service:8089/api/v1/models/status \
  -H "X-Internal-Token: $TOKEN"

# Check deduplication efficiency
curl -X GET http://ai-orchestrator-service:8089/api/v1/metrics/deduplication \
  -H "X-Internal-Token: $TOKEN"
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8089

# AI Inference Service
AI_INFERENCE_SERVICE_URL=http://ai-inference-service:8090
AI_INFERENCE_TIMEOUT_SECONDS=5
AI_INFERENCE_MAX_BATCH_SIZE=32

# Feature Flag Service
FEATURE_FLAG_SERVICE_URL=http://config-feature-flag-service:8095
FEATURE_FLAG_CACHE_TTL_SECONDS=300

# Request Deduplication (Redis)
REDIS_URL=redis://redis-primary.cache:6379
REDIS_PASSWORD=<from kubernetes secret>
DEDUPLICATION_CACHE_TTL_SECONDS=300
DEDUPLICATION_ENABLED=true

# Model Configuration
AI_MODELS_AVAILABLE=ranking_v2,recommendation_v3,fraud_detection_v1
AI_RANKING_MODEL_ACCURACY_TARGET=0.95
AI_RECOMMENDATION_MODEL_ACCURACY_TARGET=0.88
AI_FRAUD_MODEL_ACCURACY_TARGET=0.99

# Batch Optimization
BATCH_AGGREGATION_ENABLED=true
BATCH_AGGREGATION_TIMEOUT_MILLISECONDS=200 (max wait for batch assembly)
BATCH_MIN_SIZE=5 (minimum requests to trigger batch)
BATCH_MAX_SIZE=32

# Circuit Breaker
CIRCUIT_BREAKER_FAILURE_THRESHOLD=5 (failures before opening)
CIRCUIT_BREAKER_TIMEOUT_SECONDS=30 (duration in open state)
CIRCUIT_BREAKER_SUCCESS_THRESHOLD=3 (successes before half-open closes)

# Fallback Strategy
FALLBACK_ENABLED=true
FALLBACK_DEFAULT_RANKING=random (fallback ranking strategy)
FALLBACK_DEFAULT_RECOMMENDATION=trending (fallback recommendation strategy)
FALLBACK_DEFAULT_FRAUD_SCORE=0.0 (default low fraud score)

SPRING_PROFILES_ACTIVE=gcp
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
```

### application.yml
```yaml
ai-orchestrator:
  inference:
    service-url: ${AI_INFERENCE_SERVICE_URL:http://ai-inference-service:8090}
    timeout-seconds: ${AI_INFERENCE_TIMEOUT_SECONDS:5}
    max-batch-size: ${AI_INFERENCE_MAX_BATCH_SIZE:32}

  feature-flag:
    service-url: ${FEATURE_FLAG_SERVICE_URL:http://config-feature-flag-service:8095}
    cache-ttl-seconds: ${FEATURE_FLAG_CACHE_TTL_SECONDS:300}

  models:
    available: ${AI_MODELS_AVAILABLE:ranking_v2,recommendation_v3}
    ranking:
      accuracy-target: ${AI_RANKING_MODEL_ACCURACY_TARGET:0.95}
      latency-requirement: strict
    recommendation:
      accuracy-target: ${AI_RECOMMENDATION_MODEL_ACCURACY_TARGET:0.88}
      latency-requirement: standard
    fraud-detection:
      accuracy-target: ${AI_FRAUD_MODEL_ACCURACY_TARGET:0.99}
      latency-requirement: standard

  batching:
    enabled: ${BATCH_AGGREGATION_ENABLED:true}
    timeout-milliseconds: ${BATCH_AGGREGATION_TIMEOUT_MILLISECONDS:200}
    min-size: ${BATCH_MIN_SIZE:5}
    max-size: ${BATCH_MAX_SIZE:32}

  circuit-breaker:
    failure-threshold: ${CIRCUIT_BREAKER_FAILURE_THRESHOLD:5}
    timeout-seconds: ${CIRCUIT_BREAKER_TIMEOUT_SECONDS:30}
    success-threshold: ${CIRCUIT_BREAKER_SUCCESS_THRESHOLD:3}

  fallback:
    enabled: ${FALLBACK_ENABLED:true}
    default-ranking: ${FALLBACK_DEFAULT_RANKING:random}
    default-recommendation: ${FALLBACK_DEFAULT_RECOMMENDATION:trending}
    default-fraud-score: ${FALLBACK_DEFAULT_FRAUD_SCORE:0.0}

redis:
  url: ${REDIS_URL:redis://redis-primary.cache:6379}
  password: ${REDIS_PASSWORD:}
  timeout: 1000ms
```

## Monitoring & Alerts

### Key Metrics
- `ai_orchestrator_request_duration_seconds` (histogram) - End-to-end request latency
- `ai_orchestrator_requests_total` (counter) - Total requests by type and model
- `ai_orchestrator_batch_size` (histogram) - Requests per batch (for efficiency analysis)
- `ai_orchestrator_deduplication_cache_hit_rate` (gauge) - % requests found in dedup cache
- `ai_orchestrator_model_selection_accuracy` (gauge) - Correct model selection rate
- `ai_orchestrator_fallback_rate` (gauge) - % requests using fallback (should be < 1%)
- `ai_orchestrator_inference_service_error_rate` (gauge) - Errors from inference-service
- `circuit_breaker_state` (gauge) - Circuit breaker state (0=closed, 1=open, 2=half-open)
- `jvm_memory_usage_bytes` - Heap memory
- `process_cpu_usage` - CPU utilization

### Alerting Rules
- `ai_orchestrator_request_duration_p99 > 1000ms` - Latency SLO breach (SEV-2)
- `circuit_breaker_state == 1` - Circuit breaker open (inference-service down, page immediately)
- `ai_orchestrator_fallback_rate > 5%` - Excessive fallback activation (investigate inference issues)
- `ai_orchestrator_model_selection_accuracy < 90%` - Poor model selection (review feature-flag config)
- `ai_orchestrator_inference_service_error_rate > 2%` - High error rate from inference-service
- `ai_orchestrator_deduplication_cache_hit_rate < 30%` - Low dedup efficiency (review cache TTL)

### Logging
- **WARN**: Model selection failures, feature-flag fetches, deduplication misses
- **INFO**: Circuit breaker state changes, fallback activations, batch assembly times
- **ERROR**: Inference-service errors, Redis failures, request parsing errors
- **DEBUG**: Batch aggregation details, model selection reasoning (testing only)

## Security Considerations

### Threat Mitigations
1. **Token Scoping**: X-Internal-Token scoped to ai-orchestrator-service (not shareable)
2. **Request Validation**: All input parameters validated before forwarding to inference-service
3. **Rate Limiting**: Per-service request limit (1,000 requests/minute) to prevent abuse
4. **Deduplication Cache**: Prevents duplicate inference calls from flooding the system
5. **Circuit Breaker**: Fails fast if inference-service is unhealthy, preventing cascade failures

### Known Risks
- **Feature flag misconfiguration**: Incorrect enablement flags could disable AI for critical features
- **Model poisoning**: Inference-service compromise could return malicious results (mitigated by fallback)
- **Cache poisoning**: Redis compromise could return stale dedup cache entries (mitigated by TTL)

## Troubleshooting

### Circuit Breaker Stuck Open
1. **Check inference-service health**: `curl http://ai-inference-service:8090/health`
2. **Verify connectivity**: Ensure ai-orchestrator can reach inference-service
3. **Check error rate**: Review error logs from inference-service for issues
4. **Manual reset** (if needed): `curl -X POST http://ai-orchestrator-service:8089/api/v1/admin/circuit-breaker/reset`

### High Latency (p99 > 1s)
1. **Check batch aggregation timeout**: Verify `BATCH_AGGREGATION_TIMEOUT_MILLISECONDS` is not too high
2. **Monitor inference-service latency**: Check p99 latency of downstream inference requests
3. **Profile request volume**: High concurrent requests may saturate the inference service
4. **Review feature-flag config**: Ensure AI is not enabled for all requests (use gradual rollout)

### Low Deduplication Hit Rate
1. **Check cache TTL**: Verify `DEDUPLICATION_CACHE_TTL_SECONDS` matches request patterns
2. **Monitor Redis connectivity**: Check Redis availability and eviction rates
3. **Review request patterns**: Log actual request IDs to understand if dedup is effective
4. **Consider request windowing**: Adjust dedup window to match user behavior patterns

## Related Documentation

- **ADR-016**: AI Orchestration and Model Selection (design rationale)
- **Wave-39**: AI service orchestration implementation
- **HLD**: AI orchestrator architecture, batch optimization strategy
- **LLD**: Request routing logic, deduplication algorithm
- **Runbook**: ai-orchestrator-service/runbook.md
- **Model Selection Strategy**: Config documentation for model accuracy/latency trade-offs
