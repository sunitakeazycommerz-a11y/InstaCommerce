# SLO Burn-Rate Alerts & Metrics Configuration

**Purpose**: Define Prometheus alert rules for multi-window burn-rate detection. See `docs/adr/015-slo-error-budget-policy.md` for policy.

## Alert Rule Structure

Each service gets three burn-rate alerts:
1. **Fast burn (5 min window)**: >10% error rate → Immediate page (SEV-1)
2. **Medium burn (30 min window)**: >5% error rate → Incident ticket (SEV-2)
3. **Slow burn (6 hour window)**: >1% error rate → SRE review (SEV-3)

Error rate measured as: `rate(http_requests_total{status=~"5.."}[window]) / rate(http_requests_total[window])`

## Prometheus Alert Rules

### Critical Money Path

#### Order Service
```yaml
- alert: OrderServiceFastBurn
  expr: |
    (
      rate(http_requests_total{service="order-service", status=~"5.."}[5m])
      /
      rate(http_requests_total{service="order-service"}[5m])
    ) > 0.1
  for: 5m
  labels:
    severity: critical
    service: order-service
    slo_window: fast_burn
  annotations:
    summary: "Order service error rate > 10% in 5min (SLO breach - fast burn)"
    runbook: "docs/runbooks/order-service-errors.md"

- alert: OrderServiceMediumBurn
  expr: |
    (
      rate(http_requests_total{service="order-service", status=~"5.."}[30m])
      /
      rate(http_requests_total{service="order-service"}[30m])
    ) > 0.05
  for: 30m
  labels:
    severity: warning
    service: order-service
    slo_window: medium_burn
  annotations:
    summary: "Order service error rate > 5% in 30min (SLO breach - medium burn)"
    runbook: "docs/runbooks/order-service-errors.md"

- alert: OrderServiceSlowBurn
  expr: |
    (
      rate(http_requests_total{service="order-service", status=~"5.."}[6h])
      /
      rate(http_requests_total{service="order-service"}[6h])
    ) > 0.01
  for: 2h
  labels:
    severity: info
    service: order-service
    slo_window: slow_burn
  annotations:
    summary: "Order service error rate > 1% in 6h (SLO review - slow burn)"
    runbook: "docs/runbooks/order-service-errors.md"
```

#### Payment Service
```yaml
- alert: PaymentServiceFastBurn
  expr: |
    (
      rate(http_requests_total{service="payment-service", endpoint="/process-payment", status=~"5.."}[5m])
      /
      rate(http_requests_total{service="payment-service", endpoint="/process-payment"}[5m])
    ) > 0.1
  for: 5m
  labels:
    severity: critical
    service: payment-service
    slo_window: fast_burn
  annotations:
    summary: "Payment service error rate > 10% in 5min (SLO breach - fast burn)"
    runbook: "docs/runbooks/payment-service-errors.md"
    pagerduty_severity: critical

- alert: PaymentServiceLatencyBreach
  expr: |
    histogram_quantile(0.99, rate(http_request_duration_seconds_bucket{service="payment-service", endpoint="/process-payment"}[5m]))
    > 0.3
  for: 10m
  labels:
    severity: warning
    service: payment-service
  annotations:
    summary: "Payment service p99 latency > 300ms (SLO breach)"
    runbook: "docs/runbooks/payment-service-latency.md"

- alert: PaymentWebhookDeliveryFailure
  expr: |
    rate(payment_webhook_delivery_failed_total[5m])
    /
    rate(payment_webhook_sent_total[5m])
    > 0.01  # >1% failure rate
  for: 5m
  labels:
    severity: critical
    service: payment-webhook-service
  annotations:
    summary: "Payment webhook delivery >1% failure rate (SLO breach)"
    runbook: "docs/runbooks/payment-webhook-delivery.md"
    pagerduty_severity: critical
```

#### Checkout Orchestrator Service
```yaml
- alert: CheckoutOrchestrationFastBurn
  expr: |
    (
      rate(http_requests_total{service="checkout-orchestrator-service", status=~"5.."}[5m])
      /
      rate(http_requests_total{service="checkout-orchestrator-service"}[5m])
    ) > 0.1
  for: 5m
  labels:
    severity: critical
    service: checkout-orchestrator-service
  annotations:
    summary: "Checkout orchestrator error rate > 10% (SLO breach - fast burn)"
    runbook: "docs/runbooks/checkout-orchestrator-errors.md"

- alert: CheckoutLatencyBreach
  expr: |
    histogram_quantile(0.99, rate(http_request_duration_seconds_bucket{service="checkout-orchestrator-service", endpoint="/checkout"}[5m]))
    > 2.0
  for: 10m
  labels:
    severity: warning
    service: checkout-orchestrator-service
  annotations:
    summary: "Checkout p99 latency > 2s (SLO breach)"
    runbook: "docs/runbooks/checkout-orchestrator-latency.md"
```

### Platform Services

#### Feature Flag Service (Cache Invalidation)
```yaml
- alert: FeatureFlagCacheInvalidationLatency
  expr: |
    histogram_quantile(0.99, rate(feature_flag_cache_invalidation_duration_seconds_bucket[5m]))
    > 0.5
  for: 5m
  labels:
    severity: warning
    service: config-feature-flag-service
  annotations:
    summary: "Feature flag cache invalidation > 500ms p99 (SLO breach)"
    runbook: "docs/runbooks/feature-flag-cache.md"

- alert: FeatureFlagServiceFastBurn
  expr: |
    (
      rate(http_requests_total{service="config-feature-flag-service", status=~"5.."}[5m])
      /
      rate(http_requests_total{service="config-feature-flag-service"}[5m])
    ) > 0.1
  for: 5m
  labels:
    severity: warning
    service: config-feature-flag-service
  annotations:
    summary: "Feature flag service error rate > 10% (SLO breach)"
    runbook: "docs/runbooks/feature-flag-service-errors.md"

- alert: FeatureFlagCacheMissRate
  expr: |
    (
      rate(feature_flag_cache_misses_total[5m])
      /
      (rate(feature_flag_cache_hits_total[5m]) + rate(feature_flag_cache_misses_total[5m]))
    ) > 0.05  # >5% miss rate (goal >95% hits)
  for: 10m
  labels:
    severity: info
    service: config-feature-flag-service
  annotations:
    summary: "Feature flag cache miss rate > 5% (below target >95%)"
    runbook: "docs/runbooks/feature-flag-cache.md"
```

#### Identity Service
```yaml
- alert: IdentityServiceFastBurn
  expr: |
    (
      rate(http_requests_total{service="identity-service", status=~"5.."}[5m])
      /
      rate(http_requests_total{service="identity-service"}[5m])
    ) > 0.1
  for: 5m
  labels:
    severity: critical
    service: identity-service
  annotations:
    summary: "Identity service error rate > 10% (SLO breach - cascading impact)"
    runbook: "docs/runbooks/identity-service-errors.md"

- alert: JwtValidationLatency
  expr: |
    histogram_quantile(0.99, rate(jwt_validation_duration_seconds_bucket[5m]))
    > 0.1
  for: 5m
  labels:
    severity: warning
    service: identity-service
  annotations:
    summary: "JWT validation latency > 100ms p99 (SLO breach)"
    runbook: "docs/runbooks/jwt-validation-latency.md"
```

#### Reconciliation Engine
```yaml
- alert: ReconciliationEngineJobFailure
  expr: reconciliation_job_failures_total > 0
  for: 1m
  labels:
    severity: critical
    service: reconciliation-engine
  annotations:
    summary: "Reconciliation job failed (financial impact)"
    runbook: "docs/runbooks/reconciliation-engine-failure.md"

- alert: ReconciliationMismatchesDetected
  expr: reconciliation_mismatches_count > 0
  for: 5m
  labels:
    severity: warning
    service: reconciliation-engine
  annotations:
    summary: "Reconciliation detected mismatches (requires review)"
    runbook: "docs/runbooks/reconciliation-mismatches.md"

- alert: ReconciliationAuditTrailAccuracy
  expr: reconciliation_audit_completeness_ratio < 1.0
  for: 1h
  labels:
    severity: critical
    service: reconciliation-engine
  annotations:
    summary: "Reconciliation audit trail < 100% complete (compliance risk)"
    runbook: "docs/runbooks/reconciliation-audit-trail.md"
```

### Read Path Services

#### Search Service
```yaml
- alert: SearchServiceFastBurn
  expr: |
    (
      rate(http_requests_total{service="search-service", status=~"5.."}[5m])
      /
      rate(http_requests_total{service="search-service"}[5m])
    ) > 0.1
  for: 5m
  labels:
    severity: warning
    service: search-service
  annotations:
    summary: "Search service error rate > 10% (SLO breach)"
    runbook: "docs/runbooks/search-service-errors.md"

- alert: SearchIndexStaleness
  expr: (time() - elasticsearch_indices_last_indexed_timestamp{index="products"}) / 60 > 5
  for: 10m
  labels:
    severity: warning
    service: search-service
  annotations:
    summary: "Search index > 5min stale (SLO breach)"
    runbook: "docs/runbooks/search-index-staleness.md"
```

#### Catalog Service
```yaml
- alert: CatalogServiceFastBurn
  expr: |
    (
      rate(http_requests_total{service="catalog-service", status=~"5.."}[5m])
      /
      rate(http_requests_total{service="catalog-service"}[5m])
    ) > 0.1
  for: 5m
  labels:
    severity: warning
    service: catalog-service
  annotations:
    summary: "Catalog service error rate > 10% (SLO breach)"
    runbook: "docs/runbooks/catalog-service-errors.md"
```

### Logistics Services

#### Fulfillment Service
```yaml
- alert: FulfillmentServiceFastBurn
  expr: |
    (
      rate(http_requests_total{service="fulfillment-service", status=~"5.."}[5m])
      /
      rate(http_requests_total{service="fulfillment-service"}[5m])
    ) > 0.1
  for: 5m
  labels:
    severity: warning
    service: fulfillment-service
  annotations:
    summary: "Fulfillment service error rate > 10% (SLO breach)"
    runbook: "docs/runbooks/fulfillment-service-errors.md"

- alert: FulfillmentAssignmentFailureRate
  expr: |
    rate(fulfillment_assignment_failures_total[5m])
    /
    rate(fulfillment_assignments_total[5m])
    > 0.05
  for: 10m
  labels:
    severity: warning
    service: fulfillment-service
  annotations:
    summary: "Fulfillment assignment failure rate > 5% (SLO breach)"
    runbook: "docs/runbooks/fulfillment-assignment-failures.md"
```

## Common Alert Annotations

All alerts should include:
```yaml
annotations:
  summary: "Brief description of SLO breach"
  runbook: "Link to docs/runbooks/{service}-{issue}.md"
  pagerduty_severity: "critical|warning|info"
  dashboard: "Link to Grafana dashboard"
  context: "Business impact explanation"
```

## Alert Evaluation

- **Evaluation interval**: 1 minute
- **Severity escalation**:
  - `critical` (SEV-1): Page on-call immediately
  - `warning` (SEV-2): Create incident ticket, notify team
  - `info` (SEV-3): Log to SRE review dashboard
- **Alert grouping**: By service + SLO window
- **Silencing**: Only by service owner or on-call lead (prevent alert fatigue)

## Metrics to Emit

Each service should emit these standard metrics:
```
# HTTP requests
http_requests_total{service="...", method="...", status="..."}
http_request_duration_seconds{service="...", method="...", endpoint="..."}

# Service-specific SLO metrics
{service}_cache_hit_rate
{service}_latency_p50 / p95 / p99
{service}_error_rate
{service}_availability_percent
```

## Dashboard Integration

Grafana dashboard `docs/observability/grafana-slo-dashboard.json` displays:
- Error rate over time (7-day view)
- Burn-rate status (fast/medium/slow)
- P50, P95, P99 latency by service
- Availability % (green if >SLO, red if breach)
- Links to runbooks and incident channel

## References

- `docs/adr/015-slo-error-budget-policy.md` - SLO policy & burn-rate rationale
- `docs/slos/service-slos.md` - SLO targets by service
- `.github/CODEOWNERS` - Service owners responsible for runbook maintenance
- `docs/governance/OWNERSHIP_MODEL.md` - SLO monitoring responsibilities
