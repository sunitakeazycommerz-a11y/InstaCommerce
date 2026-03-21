# Routing ETA Service

## Overview

The routing-eta-service provides real-time estimated time of arrival (ETA) predictions for delivery orders in InstaCommerce. It ingests real-time traffic data, applies ML model inference, accounts for rider behavior and historical patterns, and emits ETA updates with confidence intervals for customer communication and operational orchestration.

**Service Ownership**: Fulfillment Platform Team - ETA Prediction
**Language**: Go 1.22 + Python/TensorFlow (model inference)
**Default Port**: 8084
**Status**: Tier 1 Critical (Customer-facing ETA)

## SLOs & Availability

- **Availability SLO**: 99% (7.2 hours downtime/month - higher tolerance due to graceful degradation)
- **P99 Latency**: < 500ms for ETA requests
- **ETA Accuracy (p50)**: ± 15 minutes (50% of predictions within 15min of actual)
- **ETA Accuracy (p95)**: ± 40 minutes (95% of predictions within 40min of actual)
- **Model Freshness**: ML models retrained weekly, deployed within 24 hours
- **Error Rate**: < 1% (errors trigger fallback to historical average)

## Key Responsibilities

1. **Real-time ETA Prediction**: ML model inference with traffic-adjusted routing calculations
2. **Confidence Interval Computation**: Return ± bounds based on historical prediction error distributions
3. **Traffic Model Integration**: Ingest real-time traffic data from location-ingestion-service
4. **Rider Behavior Modeling**: Factor in individual rider speed, preferences, break patterns
5. **Model Retraining**: Daily pipeline to retrain ML models based on historical ETA accuracy

## Deployment

### GKE Deployment
- **Namespace**: fulfillment
- **Replicas**: 3 (HA, stateless)
- **CPU Request/Limit**: 1000m / 2000m
- **Memory Request/Limit**: 1Gi / 2Gi (model inference memory intensive)
- **Horizontal Pod Autoscaler**: 3-8 replicas based on request latency (p99 < 500ms)

### Cluster Configuration
- **Ingress**: Internal + Mobile (behind Istio IngressGateway for mobile clients)
- **NetworkPolicy**: Deny-default; allow from checkout-orchestrator, mobile-bff-service, location-ingestion-service
- **Service Account**: routing-eta-service
- **Model Storage**: GCS bucket for ML model artifacts (weekly dumps)
- **GPUs** (Optional): 1 NVIDIA A10 per pod for inference acceleration (if cost-justified)

## Architecture

### ETA Prediction Flow

```
┌──────────────────────────────────────────────────────────────┐
│ Order in Transit                                              │
│ GET /eta/v1/prediction/{orderId}                              │
└────────────────────────┬─────────────────────────────────────┘
                         │
         ┌───────────────▼────────────────────────┐
         │ Routing ETA Service                    │
         │ 1. Fetch rider current location        │
         │ 2. Fetch destination location          │
         │ 3. Fetch real-time traffic data        │
         │ 4. Retrieve ML model (TensorFlow)      │
         │ 5. Compute ETA + confidence interval   │
         │ 6. Emit ETA update event               │
         └───────────────┬────────────────────────┘
                         │
    ┌────────────────────┼────────────────────────┐
    │                    │                        │
    ▼                    ▼                        ▼
Mobile Client       Notification          Order Service
(ETA display)     (customer update)      (system forecast)
```

### ML Model Architecture

```
Input Features:
  - Current distance (km)
  - Current speed (km/h)
  - Time of day (hour, day-of-week)
  - Real-time traffic congestion (%)
  - Rider experience level (years)
  - Weather conditions (rain, temperature)
  - Historical prediction error (distribution)

ML Model Pipeline:
  - Feature preprocessing (normalization, encoding)
  - Gradient Boosting Model (XGBoost)
  - Ensemble aggregation (3 models for robustness)
  - Prediction + quantile regression for ± bounds

Output:
  - ETA (minutes from now)
  - Confidence interval (p25, p50, p75, p95)
  - Model version (for debugging)
```

### Integrations

| Service | Endpoint | Timeout | Purpose |
|---------|----------|---------|---------|
| location-ingestion-service | http://location-ingestion-service:8087/locations/{riderId} | 2s | Get rider real-time location |
| pricing-service | http://pricing-service:8081/routes/eta-factor | 2s | Get route price adjustment factors |
| GCS (ML models) | gs://instacommerce-ml/models/eta | - | Load trained ML models (weekly refresh) |

## Endpoints

### Public (Unauthenticated)
- `GET /health` - Liveness probe (model loading status)
- `GET /metrics` - Prometheus metrics (prediction latency, model accuracy)

### Mobile-Facing API
- `GET /eta/v1/prediction/{orderId}` - Get ETA for order (public, no auth required for customer app)
- `GET /eta/v1/prediction/{orderId}/confidence` - Get ETA with confidence intervals (no auth)

### Internal API (Requires X-Internal-Token)
- `GET /api/v1/prediction/{orderId}` - Internal ETA prediction (with audit logging)
- `POST /api/v1/predictions/batch` - Batch ETA predictions (for operational dashboards)
- `GET /api/v1/model/status` - ML model status (version, accuracy metrics, last retrain)
- `POST /api/v1/model/reload` - Force reload ML model from GCS (admin)
- `GET /api/v1/metrics/accuracy` - Historical prediction accuracy (for model evaluation)

### Example Requests

```bash
# Get ETA for customer-facing mobile app (no authentication)
curl -X GET http://routing-eta-service:8084/eta/v1/prediction/ord-12345

# Get ETA with confidence intervals
curl -X GET http://routing-eta-service:8084/eta/v1/prediction/ord-12345/confidence

# Get authorization token for internal API
TOKEN=$(curl -X POST http://identity-service:8080/token/internal \
  -H "Content-Type: application/json" \
  -d '{"service":"checkout-orchestrator-service"}' | jq -r '.token')

# Internal ETA prediction with audit logging
curl -X GET http://routing-eta-service:8084/api/v1/prediction/ord-12345 \
  -H "X-Internal-Token: $TOKEN"

# Batch ETA predictions for dashboard
curl -X POST http://routing-eta-service:8084/api/v1/predictions/batch \
  -H "X-Internal-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "orderIds":["ord-12345","ord-12346","ord-12347"],
    "includeConfidenceInterval":true
  }'

# Check ML model status
curl -X GET http://routing-eta-service:8084/api/v1/model/status \
  -H "X-Internal-Token: $TOKEN"
```

## Configuration

### Environment Variables
```env
PORT=8084

# Location and Traffic Services
LOCATION_SERVICE_URL=http://location-ingestion-service:8087
PRICING_SERVICE_URL=http://pricing-service:8081
SERVICE_CALL_TIMEOUT_SECONDS=3

# ML Model Configuration
ML_MODEL_PATH=/models/eta_xgboost_v2.pkl
ML_MODEL_GCS_BUCKET=gs://instacommerce-ml
ML_MODEL_GCS_PATH=models/eta_xgboost
ML_MODEL_RELOAD_INTERVAL_HOURS=168 (weekly)
ML_MODEL_FEATURE_VERSION=2 (schema compatibility)

# ETA Prediction Parameters
ETA_PREDICTION_TIMEOUT_MILLISECONDS=200
ETA_CONFIDENCE_QUANTILES=0.25,0.50,0.75,0.95 (for interval computation)
ETA_FALLBACK_ENABLED=true (use historical average if model fails)
ETA_ADJUSTMENT_FACTORS_ENABLED=true (apply pricing-service factors)

# Traffic Model
TRAFFIC_CONGESTION_WEIGHT=0.4 (importance in prediction)
TRAFFIC_DATA_FRESHNESS_SECONDS=300 (max staleness)

# Caching
MODEL_CACHE_TTL_SECONDS=3600
PREDICTION_CACHE_TTL_SECONDS=30
PREDICTION_CACHE_MAX_ENTRIES=50000

# Redis (for prediction caching)
REDIS_URL=redis://redis-primary.cache:6379
REDIS_PASSWORD=<from kubernetes secret>

INTERNAL_TOKEN_SECRET=<shared secret from kubernetes secret>
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
OTEL_SERVICE_NAME=routing-eta-service

# BigQuery (for analytics and model retraining)
GCP_PROJECT_ID=instacommerce-prod
BIGQUERY_TABLE_ETA_PREDICTIONS=eta_predictions
BIGQUERY_TABLE_HISTORICAL_ACCURACY=eta_accuracy_metrics
```

### routing.env (Go Configuration)
```env
# ETA Service Parameters
ROUTING_ETA_MODEL_TYPE=xgboost (xgboost|linear_regression|neural_net)
ROUTING_ETA_DRY_RUN_ENABLED=false

# Logging
LOG_LEVEL=INFO
LOG_FORMAT=json (for structured logging)
```

## Monitoring & Alerts

### Key Metrics
- `eta_prediction_duration_milliseconds` (histogram) - End-to-end prediction latency
- `eta_prediction_error_minutes` (histogram) - Absolute error vs actual (for accuracy tracking)
- `eta_model_accuracy_p50` (gauge) - Median prediction error
- `eta_model_accuracy_p95` (gauge) - 95th percentile prediction error
- `eta_predictions_total` (counter) - Predictions generated (by endpoint, mobile vs internal)
- `eta_fallback_rate` (gauge) - % of predictions using historical fallback (should be < 1%)
- `eta_ml_model_load_duration_seconds` (histogram) - Model load time (on startup/reload)
- `go_goroutines` - Active goroutines (prediction workers)
- `process_resident_memory_bytes` - Memory usage (model in memory)

### Alerting Rules
- `eta_prediction_duration_p99 > 500ms` - Prediction latency SLO breach (SEV-2)
- `eta_ml_model_load_failed` - Model load failure (page immediately, use previous version)
- `eta_fallback_rate > 5%` - Excessive fallback to historical average (investigate model issues)
- `eta_model_accuracy_p50 > 30_minutes` - Degraded prediction accuracy (model retraining may be needed)
- `eta_service_call_failures > 10/min` - Downstream service failures (check location-service)
- `eta_prediction_cache_hit_rate < 40%` - Low cache efficiency (review cache TTL or customer patterns)

### Logging
- **ERROR**: Model load failures, prediction exceptions, service call timeouts
- **WARN**: Low cache hit rates, fallback activations, unusually high prediction errors
- **INFO**: Model reloads, accuracy metrics, high-error orders (for analysis)
- **DEBUG**: Feature extraction, model inference details, confidence interval computation (testing only)

## Security Considerations

### Threat Mitigations
1. **Rate Limiting**: Per-customer request limit (100 requests/minute) to prevent abuse
2. **Input Validation**: Order ID format validation, location coordinates bounds checking
3. **Model Integrity**: ML models loaded only from GCS (immutable, version-controlled)
4. **Encrypted Caches**: Redis connections use TLS 1.3, cached predictions are time-bounded
5. **Audit Logging**: All internal API predictions logged with caller service ID

### Known Risks
- **Model poisoning**: Retraining pipeline uses historical data (mitigated by data validation)
- **Location data staleness**: 30-second max cache may cause inaccurate ETA during rapid movement
- **ML model overfitting**: Models may overfit to peak hours/weekdays (mitigated by cross-validation)

## Troubleshooting

### High Prediction Error (p50 > 30 minutes)
1. **Check model version**: `curl http://routing-eta-service:8084/api/v1/model/status`
2. **Compare with previous model**: Check historical accuracy metrics in BigQuery
3. **Trigger manual retraining**: Notify data team or force reload with latest model
4. **Analyze outliers**: Check for seasonal patterns, traffic anomalies, or rider-specific issues

### Prediction Latency Spike
1. **Check model load status**: Verify model is fully loaded (not reloading)
2. **Monitor Redis cache**: Check cache hit rate and eviction metrics
3. **Profile ML inference**: Enable DEBUG logging for single request to see feature extraction time
4. **Reduce feature complexity**: Consider disabling non-critical features (weather, etc.)

### Model Load Failures
1. **Verify GCS access**: `gsutil ls gs://instacommerce-ml/models/eta_xgboost`
2. **Check GCS credentials**: Verify service account has GCS read permissions
3. **Fallback to cached model**: Service should use previous model if load fails
4. **Check disk space**: Model size should be < 500MB (verify on pod)

## Advanced ML Model Architecture

### Feature Engineering Pipeline

**Input Features (30 total)**:
```
Traffic Features:
  - current_congestion_pct (0-100%)
  - traffic_incident_count (near route)
  - avg_traffic_speed_kmh (historical for time/day)
  - traffic_trend (increasing, decreasing, stable)

Rider Features:
  - rider_experience_years (tenure)
  - rider_avg_speed_kmh (personal average)
  - rider_brake_count (smoothness metric)
  - rider_delivery_rating (customer satisfaction)
  - rider_on_time_pct (historical accuracy)

Order Features:
  - distance_km (great-circle)
  - route_complexity (turns, stops)
  - destination_zone (downtown, highway, rural)
  - time_of_day_hour (morning, midday, evening, night)
  - day_of_week (weekday vs weekend)

Weather Features:
  - rain_probability_pct (0-100%)
  - temperature_celsius (-10 to +50)
  - wind_speed_kmh (0-50+)
  - visibility_m (0-10000)

Historical Features:
  - similar_route_avg_time_min (lookup from DB)
  - prediction_error_distribution (p25, p50, p75)
  - seasonal_adjustment_factor (holiday, festival)
```

### Model Serving Infrastructure

**Model Load & Deployment**:
```
1. Weekly retraining (Monday 2 AM UTC):
   - Collect historical predictions + actuals from BigQuery
   - Train XGBoost ensemble (3 models)
   - Cross-validate (80/20 split)
   - Calculate feature importance

2. Model validation:
   - MAE (Mean Absolute Error) < 15 min → PASS
   - RMSE (Root Mean Squared Error) < 20 min → PASS
   - If either fails: REJECT, keep previous model

3. Model deployment:
   - Save to GCS: gs://instacommerce-ml/models/eta_xgboost_v{N}.pkl
   - Update model registry with version + metrics
   - Deploy to canary (10% traffic)
   - Monitor accuracy for 24 hours
   - Full rollout if no degradation

4. Fallback strategy:
   - If model load fails: Use previous version
   - If inference timeout: Use historical average
   - If both fail: Return 999 min (indicating uncertainty)
```

### Confidence Interval Computation

**Quantile Regression**:
```
Model outputs: p25, p50, p75, p95 estimates

Examples:
Order with 10km distance, morning rush:
  p25: 8 min (25% likely to complete in ≤ 8 min)
  p50: 12 min (50% likely to complete in ≤ 12 min)
  p75: 18 min (75% likely to complete in ≤ 18 min)
  p95: 35 min (95% likely to complete in ≤ 35 min)

Display to customer:
  "Est. delivery: 12-18 minutes (95% on time guarantee)"
  (p50 ± margin derived from p75-p50 range)

Accuracy tracking:
  - If actual > p75: Flag as "late delivery" (< 25% expected)
  - If actual > p95: Flag as "very late" (< 5% expected)
  - Aggregate late %, alert if > 10%
```

## Advanced Operational Patterns

### Real-Time Model Monitoring

**Model Drift Detection**:
```
Metrics collected per request:
  - actual_delivery_time_minutes
  - predicted_eta_minutes
  - prediction_error_minutes (|actual - predicted|)
  - confidence_interval_captured (was actual within [p25, p75]?)

Daily aggregation:
  MAE_today = AVG(|error|) for all deliveries today
  MAE_baseline = historical MAE (from training set)

Alert triggers:
  IF MAE_today > MAE_baseline * 1.5 AND duration > 3 hours:
    severity: SEV-2
    action: Investigate model drift, consider rollback
```

### Improving Accuracy Over Time

**Continuous Learning Loop**:
```
Phase 1: Collect ground truth data (2-3 weeks)
  - Log all predictions + actual outcomes
  - Store in BigQuery for analysis

Phase 2: Analyze prediction errors (1 week)
  - Segment by: time of day, zone, weather, rider
  - Identify systematic biases (e.g., underestimate during rain)

Phase 3: Model improvement (1 week)
  - Adjust feature weights
  - Add new features (e.g., rider_break_pattern)
  - Retrain with improved dataset

Phase 4: Validate improvements (1 week)
  - Cross-validate against holdout set
  - Confirm MAE improved by ≥ 5%
  - Deploy to canary

Phase 5: Monitor production (2 weeks)
  - Compare canary vs control accuracy
  - If improved: Full rollout
  - If worse: Rollback immediately
```

## Production Runbook Patterns

### Runbook 1: Emergency ETA Model Rollback

**Scenario**: Model accuracy degraded (p50 error > 25 min)

**Symptoms**:
- Customer complaints: "ETA way off"
- Metrics alert: eta_model_accuracy_p50 > 25

**Recovery** (< 5 min):
```bash
# Step 1: Verify model is problematic
curl http://routing-eta-service:8084/api/v1/model/status | jq '.accuracy_p50'

# Step 2: Check available models
gsutil ls gs://instacommerce-ml/models/eta_xgboost_v*.pkl

# Step 3: Rollback to previous version
curl -X POST http://routing-eta-service:8084/admin/model/rollback \
  -d '{"toVersion":"v2","reason":"Accuracy degradation"}'

# Step 4: Verify rollback
curl http://routing-eta-service:8084/api/v1/model/status | jq '.version'
# Should show: v2

# Step 5: Monitor metrics
# Accuracy should improve within 5 min (traffic re-routes)
```

### Runbook 2: ETA Prediction Timeout (100ms SLA Breached)

**Symptom**: eta_prediction_duration_p99 > 500ms

**Root cause analysis**:
```bash
# Check if issue is inference or data fetch
curl http://routing-eta-service:8084/actuator/metrics/eta_ml_model_inference_latency_ms
# If > 100ms: Model slow (possible)

curl http://routing-eta-service:8084/actuator/metrics/eta_service_call_latency_ms | grep location-service
# If > 200ms: Location service slow (likely)

curl http://routing-eta-service:8084/actuator/metrics/eta_prediction_cache_hit_rate
# If < 30%: Cache misses causing repeated calculations
```

**Resolution**:
- If model slow: Check if CPU throttled, restart pod
- If location-service slow: Alert that team
- If low cache hit: Increase cache TTL from 30s to 60s

### Runbook 3: Location Data Staleness Issue

**Scenario**: Riders moving rapidly (e.g., highway), ETA becomes inaccurate

**Problem**:
- Rider location cache: 30s TTL
- Rider location updates: Every 5s
- Stale data used in ETA calc → Inaccurate

**Mitigation** (< 10 min):
```bash
# Option 1: Reduce cache TTL (risky: increases load)
PREDICTION_CACHE_TTL_SECONDS=10 (from 30)

# Option 2: Increase location freshness requirement
# When location > 10s old, don't use cache, fetch fresh

# Option 3: Adjust confidence interval to account for staleness
# Display: "12-22 minutes (+ 5 min uncertainty)"
# vs "12-18 minutes"
```

## Testing & Validation

### Integration Test Coverage

**Test Categories**:
```
1. Feature Extraction (5 tests)
   - Numeric normalization (0-1 range)
   - Categorical encoding (one-hot, label)
   - Missing value handling (imputation)

2. Model Inference (8 tests)
   - Basic inference (get ETA + bounds)
   - Confidence interval correctness (p25 ≤ p50 ≤ p75)
   - Timeout handling (returns fallback after 200ms)

3. API Contract (6 tests)
   - GET /eta/v1/prediction/{orderId} (public)
   - GET /api/v1/prediction/{orderId} (internal with token)
   - Batch predictions
   - Error responses (404, 400)

4. Fallback Scenarios (5 tests)
   - Model load fails → Use previous version
   - Inference timeout → Use historical average
   - Location service down → Use last known location
   - All fail → Return 999 min with uncertainty flag

5. Accuracy Validation (5 tests)
   - MAE against test set < 15 min
   - Prediction interval covers actual 90% of time
   - No systematic bias (errors normally distributed)
```

**Load Testing**:
```
Scenario: Peak hour (7-9 PM)
- 5,000 ETA requests/minute
- Burst to 8,000 RPS
- Expected behavior:
  - p99 latency stays < 500ms
  - p99 prediction error stays < 25 min
  - No model reload during peak
```

## Related Documentation

- **Wave-37**: Integration tests for ETA prediction accuracy
- **ADR-006**: ML Model Serving & Rollback Strategy
- **ADR-009**: Confidence Interval Computation
- **HLD**: Routing ETA architecture and ML model design
- **LLD**: Feature engineering, model inference pipeline
- **ML Model Retraining**: Weekly pipeline documentation (BigQuery → XGBoost training)
- **Runbook**: routing-eta-service/runbook.md
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Model Training Pipeline](ml-training-pipeline.md)
