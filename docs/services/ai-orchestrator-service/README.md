# AI Orchestrator Service

## Overview

The AI orchestrator service is the central hub for AI/ML model selection, request routing, and batch optimization in InstaCommerce. It manages the lifecycle of AI inference requests, routes them to appropriate specialized models (ranking, recommendation, fraud detection), implements intelligent batching for cost optimization, and provides fallback mechanisms for reliability.

**Service Ownership**: AI Platform Team - Model Orchestration
**Language**: Python 3.11+ / FastAPI / LangGraph
**Default Port**: 8100
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
| ai-inference-service | http://ai-inference-service:8101/inference | 5s | Low-level ML model scoring (ETA, demand, pricing) |
| config-feature-flag-service | http://config-feature-flag-service:8098/flags | 3s | Feature flag evaluation and AI experiment rollouts |
| catalog-service | http://catalog-service:8082 | 2.5s | Product and category lookups |
| pricing-service | http://pricing-service:8089 | 2.5s | Live price calculations |
| inventory-service | http://inventory-service:8083 | 2.5s | Stock availability and perishable batch status |
| Redis | redis://redis-primary:6379 | 1s | Semantic RAG cache and rate limit token buckets |

## Endpoints

### Public & Monitoring
- `GET /health` - Basic health check
- `GET /health/ready` - Readiness probe (checks Redis and downstream HTTP dependencies)
- `GET /health/live` - Liveness probe
- `GET /metrics` - Prometheus metrics

### AI Agent Endpoints
- `POST /agent/assist` - Customer support AI assist workflow
- `POST /agent/substitute` - Out-of-stock item substitution recommendation
- `POST /agent/recommend` - Personalized basket and cart recommendations

### Example Requests

```bash
# Customer support assist
curl -X POST http://localhost:8100/agent/assist \
  -H "Content-Type: application/json" \
  -H "X-User-Id: user-12345" \
  -d '{
    "message": "Where is my order #1029?",
    "session_id": "sess-abc-123"
  }'

# Item substitution recommendation
curl -X POST http://localhost:8100/agent/substitute \
  -H "Content-Type: application/json" \
  -d '{
    "store_id": "00000000-0000-0000-0000-000000000001",
    "out_of_stock_product_id": "00000000-0000-0000-0000-000000000002"
  }'
```

## Guardrails & Multi-Agent Architecture

The service executes LangGraph state machines protected by a defense-in-depth guardrails pipeline:

1. **Prompt Injection Defense** (`injection.py`): Wraps untrusted user tokens in delimiter tags and blocks jailbreak signatures.
2. **PII Masking** (`pii.py`): Strips email, phone numbers, and payment details before model execution.
3. **Token Bucket Rate Limiting** (`rate_limiter.py`): Per-IP (120 req/min) and per-user (30 req/min) sliding limits.
4. **Semantic RAG Cache** (`semantic_cache.py`): Redis-backed TTL semantic cache (300s TTL) to prevent repeated LLM calls.
5. **Output Validator** (`output_validator.py`): Validates JSON schema adherence, hallucination thresholds, and toxic content.
6. **Human Escalation** (`escalation.py`): Detects sentiment triggers for immediate handoff to human support operators.

## Configuration

### Environment Variables
```env
AI_ORCHESTRATOR_SERVER_PORT=8100
AI_ORCHESTRATOR_LOG_LEVEL=INFO
AI_ORCHESTRATOR_REQUEST_TIMEOUT_SECONDS=3.0

# Downstream Services
AI_ORCHESTRATOR_CATALOG_SERVICE_URL=http://catalog-service:8082
AI_ORCHESTRATOR_PRICING_SERVICE_URL=http://pricing-service:8089
AI_ORCHESTRATOR_INVENTORY_SERVICE_URL=http://inventory-service:8083
AI_ORCHESTRATOR_ORDER_SERVICE_URL=http://order-service:8084
AI_ORCHESTRATOR_CART_SERVICE_URL=http://cart-service:8090

# Guardrails & Caching
AI_ORCHESTRATOR_PII_REDACTION_ENABLED=true
AI_ORCHESTRATOR_RAG_CACHE_TTL_SECONDS=300.0
AI_ORCHESTRATOR_AGENT_IP_RATE_LIMIT_PER_MINUTE=120
AI_ORCHESTRATOR_AGENT_USER_RATE_LIMIT_PER_MINUTE=30
```
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
