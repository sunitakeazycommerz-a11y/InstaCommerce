# InstaCommerce — Observability & SRE Platform Review (Iteration 3)

**Date:** 2026-03-06  
**Audience:** SRE, Platform Engineering, Principal Engineers, Engineering Managers  
**Scope:** Deep implementation guide for SLIs/SLOs, burn-rate alerts, distributed trace coverage, correlation-ID propagation, runbooks, incident loops, synthetic checks, and operational readiness — anchored to q-commerce requirements (≤30-minute delivery, peak flash events, sub-second add-to-cart/checkout latency).  
**Depends on:**  
- `monitoring/prometheus-rules.yaml` + `monitoring/README.md`  
- `services/go-shared/pkg/observability/{tracing,metrics,logging}.go`  
- `services/go-shared/pkg/health/handler.go`  
- Java `management:` blocks in representative `application.yml` files  
- `contracts/README.md` (event envelope)  
- `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md`

---

## 0. Executive Verdict

The platform has **solid structural intent** — every Java service exports Prometheus metrics via Spring Actuator, every Go service registers `HTTPMetrics`, OTEL tracing is wired end-to-end, and the event envelope carries a `correlation_id`. However, the distance between **intent and operational reality** is large:

| Area | Current State | What Is Missing |
|---|---|---|
| Alert coverage | 5 coarse rules in one file | Burn-rate, per-service SLO, Kafka per-topic, Temporal, DB pool, cache hit rate |
| SLI/SLO definitions | None formalised | No recording rules, no error-budget tracking, no policy |
| Trace coverage | Wired but partial | OTLP inconsistency (HTTP vs gRPC), no Python tracing, no Kafka span propagation |
| Correlation IDs | In event envelope + TraceIdProvider | No HTTP propagation middleware, no MDC filter, not in Kafka headers |
| Runbooks | None | No runbook per alert, no incident checklist |
| Incident loop | Not defined | No postmortem process, no error-budget review cadence |
| Synthetic checks | None | No blackbox probes for critical user journeys |
| Operational readiness | No checklist | No launch gate criteria |

For a q-commerce platform where a 5-minute checkout degradation converts into lost GMV and churn, this gap is a **P0 risk**.

---

## 1. Q-Commerce Observability Requirements

Before defining any SLO number, anchor every decision to the user-visible SLAs that the business has committed to:

| User Journey | Latency Budget | Availability Target | Blast Radius |
|---|---|---|---|
| App load / catalog browse | p99 ≤ 300 ms | 99.95% | All users |
| Add to cart | p99 ≤ 150 ms | 99.95% | All active sessions |
| Checkout submit → confirmation | p99 ≤ 2 s | 99.9% | All orders |
| Payment processing | p99 ≤ 3 s | 99.95% | All paying users |
| Rider dispatch (first assignment) | p99 ≤ 10 s | 99.9% | All placed orders |
| Live ETA update (push) | p99 ≤ 5 s end-to-end | 99.5% | Tracking users |
| Order delivered (door-to-door) | ≤ 30 min | 99% (promise kept) | All orders |

These drive a **multi-tier observability model**: request-level (sub-second), pipeline-level (seconds-to-minutes), and fulfilment-level (minutes).

---

## 2. SLI Catalogue and PromQL Expressions

An SLI must be a ratio: *good events / total eligible events* within a rolling window. The table below maps each user-visible target to a concrete metric expression using the metrics that already exist in the repo.

### 2.1 Availability SLIs (Java services — Spring Actuator)

```
# SLI: fraction of non-5xx HTTP responses for service S
# Good = 2xx + 3xx + 4xx (client errors are not availability failures)
# Bad  = 5xx

sli:http_availability:rate5m{service="order-service"} =
  sum(rate(http_server_requests_seconds_count{service="order-service",
    outcome!="SERVER_ERROR"}[5m]))
  /
  sum(rate(http_server_requests_seconds_count{service="order-service"}[5m]))
```

Repeat per service by templating `service` label. Use `outcome!="SERVER_ERROR"` rather than `status!~"5.."` — Spring Actuator's `outcome` label is more reliable because it captures exceptions thrown before a status code is set.

### 2.2 Latency SLIs (Java services)

```
# SLI: fraction of requests completing within threshold T
# Checkout p99 ≤ 2 s

sli:http_latency_under_2s:rate5m{service="checkout-orchestrator-service"} =
  sum(rate(http_server_requests_seconds_bucket{service="checkout-orchestrator-service",
    uri="/api/v1/checkout",le="2.0"}[5m]))
  /
  sum(rate(http_server_requests_seconds_count{service="checkout-orchestrator-service",
    uri="/api/v1/checkout"}[5m]))
```

Define one latency SLI per critical URI, not per service overall — checkout at `/api/v1/checkout` has a tighter budget than `/actuator/health`.

### 2.3 Availability SLIs (Go services — `go-shared/pkg/observability/metrics.go`)

```
# Metric: {namespace}_http_requests_total{method,path,status}
# e.g. namespace = dispatch_optimizer

sli:dispatch_availability:rate5m =
  sum(rate(dispatch_optimizer_http_requests_total{status!~"5.."}[5m]))
  /
  sum(rate(dispatch_optimizer_http_requests_total[5m]))
```

All Go services using `NewHTTPMetrics` emit `{namespace}_http_requests_total` with `status` label. Map each service namespace: `dispatch_optimizer`, `cdc_consumer`, `payment_webhook`, `reconciliation_engine`, etc.

### 2.4 Pipeline SLIs (Kafka consumer lag)

```
# SLI: consumer lag below threshold
# q-commerce target: order events processed within 5 s → lag < 500 records at 100 msg/s

sli:kafka_lag_ok{group="order-service"} =
  (kafka_consumer_records_lag_max{group="order-service"} < 500)
```

Per consumer group, define the maximum acceptable lag based on throughput × acceptable processing delay.

### 2.5 Temporal Workflow SLIs

```
# Fraction of checkout workflows completing successfully within 60 s
sli:checkout_workflow_success:rate5m =
  sum(rate(temporal_workflow_completed_total{
    namespace="default",workflow_type="CheckoutWorkflow",status="Completed"}[5m]))
  /
  sum(rate(temporal_workflow_started_total{
    namespace="default",workflow_type="CheckoutWorkflow"}[5m]))
```

> **Gap:** Temporal SDK metrics are not yet surfaced. Temporal exposes a Prometheus endpoint at `:8000/metrics` on the server. Add a `ServiceMonitor` or a Prometheus scrape config for Temporal. The Java Temporal SDK emits `temporal_workflow_*` counters via Micrometer — wire `MicrometerClientStatsReporter` in the checkout-orchestrator.

### 2.6 Data Freshness SLI (Data Platform)

```
# Seconds since the last dbt run completed
sli:dbt_freshness_seconds =
  time() - dbt_last_run_completed_timestamp_seconds{status="success"}
# Breach threshold: > 3600 s (1 hour) for staging models
```

---

## 3. SLO Table and Error Budget Policy

### 3.1 Formal SLO Definitions

| SLO ID | Service / Component | SLI | Target | Window | Error Budget (30d) |
|---|---|---|---|---|---|
| SLO-01 | catalog-service (browse) | HTTP availability | 99.95% | 30d | 21.6 min |
| SLO-02 | cart-service | HTTP availability | 99.95% | 30d | 21.6 min |
| SLO-03 | checkout-orchestrator | HTTP availability | 99.9% | 30d | 43.2 min |
| SLO-04 | checkout-orchestrator | p99 latency ≤ 2 s | 99.5% | 30d | 3.6 h |
| SLO-05 | order-service | HTTP availability | 99.9% | 30d | 43.2 min |
| SLO-06 | payment-service | HTTP availability | 99.95% | 30d | 21.6 min |
| SLO-07 | identity-service | HTTP availability | 99.99% | 30d | 4.3 min |
| SLO-08 | inventory-service | HTTP availability | 99.9% | 30d | 43.2 min |
| SLO-09 | dispatch-optimizer | HTTP availability | 99.9% | 30d | 43.2 min |
| SLO-10 | rider-fleet-service | HTTP availability | 99.9% | 30d | 43.2 min |
| SLO-11 | routing-eta-service | p99 latency ≤ 500 ms | 99% | 30d | 7.2 h |
| SLO-12 | Kafka (order events lag) | lag < 500 records | 99.5% | 30d | 3.6 h |
| SLO-13 | Temporal checkout workflow | success rate | 99.5% | 30d | 3.6 h |
| SLO-14 | fraud-detection-service | HTTP availability | 99.9% | 30d | 43.2 min |
| SLO-15 | mobile-bff-service | HTTP availability | 99.95% | 30d | 21.6 min |

### 3.2 Recording Rules for SLI Windows

Add the following recording rules to `monitoring/prometheus-rules.yaml` under a new `instacommerce-sli-recording` group:

```yaml
groups:
  - name: instacommerce-sli-recording
    interval: 30s
    rules:
      # --- HTTP availability SLIs (Java, Spring Actuator) ---
      - record: sli:http_availability:rate5m
        expr: |
          sum by (service) (
            rate(http_server_requests_seconds_count{outcome!="SERVER_ERROR"}[5m])
          )
          /
          sum by (service) (
            rate(http_server_requests_seconds_count[5m])
          )

      - record: sli:http_availability:rate30m
        expr: |
          sum by (service) (
            rate(http_server_requests_seconds_count{outcome!="SERVER_ERROR"}[30m])
          )
          /
          sum by (service) (
            rate(http_server_requests_seconds_count[30m])
          )

      - record: sli:http_availability:rate1h
        expr: |
          sum by (service) (
            rate(http_server_requests_seconds_count{outcome!="SERVER_ERROR"}[1h])
          )
          /
          sum by (service) (
            rate(http_server_requests_seconds_count[1h])
          )

      - record: sli:http_availability:rate6h
        expr: |
          sum by (service) (
            rate(http_server_requests_seconds_count{outcome!="SERVER_ERROR"}[6h])
          )
          /
          sum by (service) (
            rate(http_server_requests_seconds_count[6h])
          )

      # --- Checkout p99 latency SLI ---
      - record: sli:checkout_latency_under_2s:rate5m
        expr: |
          sum(rate(http_server_requests_seconds_bucket{
            service="checkout-orchestrator-service",
            uri=~"/api/v1/checkout.*",
            le="2.0"}[5m]))
          /
          sum(rate(http_server_requests_seconds_count{
            service="checkout-orchestrator-service",
            uri=~"/api/v1/checkout.*"}[5m]))

      - record: sli:checkout_latency_under_2s:rate1h
        expr: |
          sum(rate(http_server_requests_seconds_bucket{
            service="checkout-orchestrator-service",
            uri=~"/api/v1/checkout.*",
            le="2.0"}[1h]))
          /
          sum(rate(http_server_requests_seconds_count{
            service="checkout-orchestrator-service",
            uri=~"/api/v1/checkout.*"}[1h]))

      # --- Error budget remaining (30-day window, 99.9% SLO) ---
      # error_budget_remaining = 1 - (1 - sli) / (1 - slo_target)
      # A value < 0 means budget exhausted
      - record: slo:error_budget_remaining:checkout_availability
        expr: |
          1 - (
            (1 - sli:http_availability:rate6h{service="checkout-orchestrator-service"})
            / 0.001
          )
```

### 3.3 Error Budget Policy

```
┌─────────────────────────────────────────────────────────────────────┐
│ ERROR BUDGET POLICY — all services on SLO table                     │
│                                                                     │
│ Budget remaining > 50%  → Normal operations, feature work OK        │
│ Budget remaining 25-50% → Increased review gate; no risky deploys   │
│ Budget remaining 10-25% → Freeze non-critical deploys; SRE review   │
│ Budget remaining < 10%  → Incident declared; feature freeze;        │
│                            post-mortem within 48 h                  │
│ Budget exhausted         → Production lock; CTO sign-off to release │
└─────────────────────────────────────────────────────────────────────┘
```

Store error-budget consumption as a Grafana panel driven by `slo:error_budget_remaining:*` recording rules. Automate the freeze gate via a CI/CD check that queries Prometheus before deploying to production.

---

## 4. Burn-Rate Alerts

The current `HighErrorRate` alert uses a fixed 5-minute window with `> 0.01` threshold. This generates both **false positives** (short spikes) and **false negatives** (slow leaks that exhaust the budget without breaching the threshold). The Google SRE Book multi-window, multi-burn-rate model solves this.

### 4.1 Burn-Rate Fundamentals

For a 30-day SLO with 99.9% target (error budget = 0.1% = 43.2 min):
- **1× burn rate** = budget consumed at the same rate the window is defined; budget lasts 30 days
- **14.4× burn rate** = budget consumed in 2 hours → page now
- **6× burn rate** = budget consumed in 5 hours → page now  
- **3× burn rate** = budget consumed in ~10 hours → ticket (non-urgent)
- **1× burn rate** = budget consumed in 30 days → no alert needed

Multi-window: use a short window (sensitivity) AND a long window (precision) — both must be true to fire.

### 4.2 Implementation

Add to `monitoring/prometheus-rules.yaml`:

```yaml
  - name: instacommerce-burn-rate-alerts
    rules:

      # ── TIER 1: Fast burn — 2-hour budget exhaustion (14.4×) ──────────────
      # Page immediately. Short window: 1h. Long window: 5m (confirms spike is real).
      - alert: SLOBurnRateCritical
        expr: |
          (
            (1 - sli:http_availability:rate1h) > (14.4 * 0.001)
          ) and (
            (1 - sli:http_availability:rate5m) > (14.4 * 0.001)
          )
        labels:
          severity: page
          slo_tier: "1"
        annotations:
          summary: >-
            {{ $labels.service }}: SLO burn rate CRITICAL —
            error budget exhausted in ~2 h if sustained
          runbook: "https://wiki.instacommerce.io/runbooks/slo-burn-rate-critical"
          dashboard: "https://grafana.instacommerce.io/d/slo-overview"

      # ── TIER 2: Fast burn — 5-hour budget exhaustion (6×) ─────────────────
      # Page. Short window: 1h. Long window: 30m.
      - alert: SLOBurnRateHigh
        expr: |
          (
            (1 - sli:http_availability:rate1h) > (6 * 0.001)
          ) and (
            (1 - sli:http_availability:rate30m) > (6 * 0.001)
          )
        for: 5m
        labels:
          severity: page
          slo_tier: "2"
        annotations:
          summary: >-
            {{ $labels.service }}: SLO burn rate HIGH —
            error budget exhausted in ~5 h if sustained
          runbook: "https://wiki.instacommerce.io/runbooks/slo-burn-rate-high"

      # ── TIER 3: Slow burn — 10-hour budget exhaustion (3×) ────────────────
      # Ticket, no page. Long window: 6h. Short window: 30m.
      - alert: SLOBurnRateMedium
        expr: |
          (
            (1 - sli:http_availability:rate6h) > (3 * 0.001)
          ) and (
            (1 - sli:http_availability:rate30m) > (3 * 0.001)
          )
        for: 15m
        labels:
          severity: warning
          slo_tier: "3"
        annotations:
          summary: >-
            {{ $labels.service }}: SLO burn rate elevated —
            budget draining; investigate before on-call shift ends

      # ── Checkout latency burn rate ─────────────────────────────────────────
      # SLO-04: 99.5% requests ≤ 2 s → error rate budget = 0.5%
      - alert: CheckoutLatencyBurnRateCritical
        expr: |
          (
            (1 - sli:checkout_latency_under_2s:rate1h) > (14.4 * 0.005)
          ) and (
            (1 - sli:checkout_latency_under_2s:rate5m) > (14.4 * 0.005)
          )
        labels:
          severity: page
          slo_tier: "1"
        annotations:
          summary: "checkout-orchestrator: p99 latency SLO burning fast — investigate immediately"
          runbook: "https://wiki.instacommerce.io/runbooks/checkout-latency"

      # ── Kafka consumer lag burn rate ───────────────────────────────────────
      - alert: KafkaLagBurnRateCritical
        expr: |
          kafka_consumer_records_lag_max{group="order-service"} > 2000
          and
          increase(kafka_consumer_records_lag_max{group="order-service"}[10m]) > 500
        labels:
          severity: page
          slo_tier: "1"
        annotations:
          summary: "Kafka order-service consumer lag escalating — orders at risk"
          runbook: "https://wiki.instacommerce.io/runbooks/kafka-consumer-lag"
```

> **Important:** The `0.001` multiplier assumes a 99.9% SLO target. Services with a different target (e.g., identity-service at 99.99% → `0.0001`) must use their own SLO-specific recording rule and burn factor. Parameterise these with a `slo_target` label on the recording rule or use separate named rules per service.

---

## 5. Distributed Trace Coverage

### 5.1 Current State Assessment

| Layer | Tracing Status | Gap |
|---|---|---|
| Java services (Spring Boot) | ✅ OTLP/HTTP to `otel-collector.monitoring:4318` at 100% sampling | No Kafka span propagation; MDC not injected via filter |
| Go services (go-shared) | ✅ `InitTracer` OTLP/HTTP at `localhost:4318` (env override) | `dispatch-optimizer-service` uses OTLP/gRPC — inconsistency |
| Go `InstrumentHandler` | ✅ HTTP spans via `otelhttp.NewHandler` in dispatch-optimizer | go-shared `InstrumentHandler` wraps spans but does not attach `otelhttp` |
| Python AI services | ❌ No OTEL tracing initialised | No spans, no context propagation |
| Kafka messages | ❌ No trace context in Kafka headers | Events cannot be linked to the originating HTTP trace |
| Temporal workflows | ⚠️ Partial | No `MicrometerClientStatsReporter` wired |
| gRPC (contracts stubs) | ❌ Not verified | gRPC interceptors for OTEL not confirmed |

### 5.2 Sampling Strategy for Q-Commerce

100% sampling at the current service scale is fine for pre-production and early production. At >1,000 rps across 20+ services, the OTEL collector becomes a bottleneck. Implement **head-based sampling with tail-based fallback**:

```
TRACING_PROBABILITY: 1.0     # pre-prod, staging
TRACING_PROBABILITY: 0.1     # prod normal load (10% random sample)
TRACING_PROBABILITY: 1.0     # prod during incidents (switch dynamically via feature flag)
```

For checkout and payment, **always sample** regardless of global probability:

```java
// In checkout-orchestrator: custom sampler to always sample money-path URIs
public class AlwaysSampleCheckoutSampler implements Sampler {
    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId,
        String name, SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
        // Always sample checkout and payment spans
        if (name.contains("checkout") || name.contains("payment")) {
            return SamplingResult.recordAndSample();
        }
        // 10% for everything else
        return parentProbability.shouldSample(parentContext, traceId, name,
            spanKind, attributes, parentLinks);
    }
}
```

Inject via:
```yaml
management:
  tracing:
    sampling:
      probability: ${TRACING_PROBABILITY:0.1}
```

And override the sampler bean for the checkout service.

### 5.3 Fixing Go OTLP Inconsistency

`dispatch-optimizer-service/main.go` uses `otlptracegrpc` while `go-shared/pkg/observability/tracing.go` uses `otlptracehttp`. This means the dispatch-optimizer cannot share the same OTEL collector pipeline config.

**Fix:** Migrate `dispatch-optimizer-service` to use `go-shared.InitTracer`, or change `go-shared.InitTracer` to accept a transport flag:

```go
// go-shared/pkg/observability/tracing.go — add transport option
type TracerConfig struct {
    ServiceName string
    UseGRPC     bool  // default false = HTTP
}

func InitTracerWithConfig(ctx context.Context, cfg TracerConfig, logger *slog.Logger) (func(context.Context) error, error) {
    endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
    if endpoint == "" {
        if cfg.UseGRPC {
            endpoint = "localhost:4317"
        } else {
            endpoint = "localhost:4318"
        }
    }
    // ... select exporter based on cfg.UseGRPC
}
```

Standardise all Go services on HTTP (`4318`) to align with the Java service configuration.

### 5.4 Kafka Trace Context Propagation

Kafka messages currently carry `correlation_id` in the event envelope but no W3C `traceparent`. This breaks the trace linkage: a slow order processing pipeline cannot be traced back to the HTTP request that placed the order.

**Implementation — Producer side (Java `outbox` relay):**
```java
// In OutboxEventPublisher.java (or wherever events are produced to Kafka)
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.propagation.TextMapSetter;

TextMapSetter<ProducerRecord<String, String>> setter =
    (record, key, value) -> record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));

GlobalOpenTelemetry.getPropagators()
    .getTextMapPropagator()
    .inject(Context.current(), producerRecord, setter);
```

**Implementation — Consumer side (Java Kafka listener):**
```java
@KafkaListener(topics = "orders.created")
public void consume(ConsumerRecord<String, String> record) {
    TextMapGetter<Headers> getter = new KafkaHeadersGetter();
    Context ctx = GlobalOpenTelemetry.getPropagators()
        .getTextMapPropagator()
        .extract(Context.current(), record.headers(), getter);
    try (Scope scope = ctx.makeCurrent()) {
        Span span = tracer.spanBuilder("consume " + record.topic())
            .setSpanKind(SpanKind.CONSUMER)
            .startSpan();
        // process event
        span.end();
    }
}
```

**Schema impact:** No change to the JSON Schema contracts — `correlation_id` remains in the payload. The trace context lives in Kafka **message headers** (binary), not the payload.

### 5.5 Python AI Services Tracing

Neither `ai-orchestrator-service` nor `ai-inference-service` initialises an OTEL tracer. The `main.py` in ai-orchestrator has structured JSON logging and PII redaction but no spans.

**Add to `services/ai-orchestrator-service/requirements.txt`:**
```
opentelemetry-api==1.24.0
opentelemetry-sdk==1.24.0
opentelemetry-exporter-otlp-proto-http==1.24.0
opentelemetry-instrumentation-fastapi==0.45b0
opentelemetry-instrumentation-httpx==0.45b0
prometheus-client==0.20.0
```

**Add to `app/telemetry.py`:**
```python
import os
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.sdk.resources import Resource, SERVICE_NAME, SERVICE_VERSION
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from prometheus_client import Counter, Histogram, make_asgi_app

def init_telemetry(app):
    resource = Resource({
        SERVICE_NAME: os.getenv("SERVICE_NAME", "ai-orchestrator-service"),
        SERVICE_VERSION: os.getenv("SERVICE_VERSION", "unknown"),
    })
    provider = TracerProvider(resource=resource)
    endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector.monitoring:4318")
    provider.add_span_processor(
        BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{endpoint}/v1/traces"))
    )
    trace.set_tracer_provider(provider)

    FastAPIInstrumentor.instrument_app(app)
    HTTPXClientInstrumentor().instrument()

    # Prometheus metrics endpoint
    metrics_app = make_asgi_app()
    app.mount("/metrics", metrics_app)
```

Expose `ai_orchestrator_requests_total`, `ai_orchestrator_llm_latency_seconds`, `ai_orchestrator_token_spend_total` as custom Prometheus counters.

### 5.6 MDC Filter for Java Services (Correlation ID → Log Context)

`TraceIdProvider` is only called during exception handling (in `GlobalExceptionHandler`), not as a request-scoped filter. This means logs emitted during normal processing do not carry `traceId`.

Spring Boot with Micrometer Tracing auto-injects `traceId` and `spanId` into MDC when the OTEL auto-instrumentation is active — verify this is working by checking a log line for `{"traceId":"...","spanId":"..."}`.

If the log aggregator (e.g., Cloud Logging) does not show trace correlation, add an explicit MDC filter:

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String MDC_CORRELATION_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_CORRELATION_KEY, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_KEY);
        }
    }
}
```

Add `correlationId` to the `logback-spring.xml` pattern:
```xml
<pattern>{"timestamp":"%d{ISO8601}","level":"%level","service":"${SERVICE_NAME}",
  "traceId":"%X{traceId}","spanId":"%X{spanId}","correlationId":"%X{correlationId}",
  "thread":"%thread","logger":"%logger","message":"%message"}%n</pattern>
```

### 5.7 Go Services: Correlation ID Propagation Middleware

`go-shared/pkg/auth/middleware.go` validates `X-Internal-Service` / `X-Internal-Token` but does not propagate `X-Correlation-ID`. Add a correlation middleware to `go-shared/pkg/observability/`:

```go
// go-shared/pkg/observability/correlation.go
package observability

import (
    "context"
    "net/http"

    "go.opentelemetry.io/otel/trace"
)

type contextKey string

const CorrelationIDKey contextKey = "correlationID"

// CorrelationMiddleware injects X-Correlation-ID into request context and
// ensures the header is forwarded in the response.
func CorrelationMiddleware(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        corrID := r.Header.Get("X-Correlation-ID")
        if corrID == "" {
            // Fall back to the W3C trace ID if present
            span := trace.SpanFromContext(r.Context())
            if span.SpanContext().IsValid() {
                corrID = span.SpanContext().TraceID().String()
            } else {
                corrID = generateID()
            }
        }
        ctx := context.WithValue(r.Context(), CorrelationIDKey, corrID)
        w.Header().Set("X-Correlation-ID", corrID)
        next.ServeHTTP(w, r.WithContext(ctx))
    })
}

// CorrelationIDFromContext extracts the correlation ID for use in log fields.
func CorrelationIDFromContext(ctx context.Context) string {
    if v, ok := ctx.Value(CorrelationIDKey).(string); ok {
        return v
    }
    return ""
}
```

Add it to the middleware chain in all Go services:
```go
mux.Handle("/", observability.CorrelationMiddleware(
    observability.CorrelationMiddleware(
        otelhttp.NewHandler(mux, "dispatch-optimizer", otelhttp.WithTracerProvider(tp)),
    ),
))
```

---

## 6. OTEL Collector: Deployment and Pipeline

The Java and Go services all reference `otel-collector.monitoring:4318` but there is no collector deployment configuration in the repository. This is a critical gap — without the collector, traces go nowhere.

### 6.1 Kubernetes Deployment (add to `deploy/helm/`)

```yaml
# deploy/helm/templates/otel-collector.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: otel-collector
  namespace: monitoring
spec:
  replicas: 2
  selector:
    matchLabels:
      app: otel-collector
  template:
    spec:
      containers:
        - name: otel-collector
          image: otel/opentelemetry-collector-contrib:0.96.0
          args: ["--config=/conf/otel-collector-config.yaml"]
          ports:
            - containerPort: 4317  # gRPC
            - containerPort: 4318  # HTTP
            - containerPort: 8889  # Prometheus metrics (self)
          resources:
            requests: {cpu: 200m, memory: 256Mi}
            limits: {cpu: 1, memory: 1Gi}
          volumeMounts:
            - name: config
              mountPath: /conf
      volumes:
        - name: config
          configMap:
            name: otel-collector-config
```

```yaml
# deploy/helm/templates/otel-collector-config.yaml (ConfigMap)
receivers:
  otlp:
    protocols:
      grpc: {endpoint: "0.0.0.0:4317"}
      http: {endpoint: "0.0.0.0:4318"}

processors:
  batch:
    timeout: 5s
    send_batch_size: 1000
  memory_limiter:
    limit_mib: 800
    spike_limit_mib: 200
    check_interval: 5s
  resource:
    attributes:
      - key: environment
        value: "${ENVIRONMENT}"
        action: upsert

exporters:
  # Traces → Grafana Tempo (or Jaeger)
  otlp/tempo:
    endpoint: "tempo.monitoring:4317"
    tls: {insecure: true}
  # Metrics → Prometheus remote write
  prometheusremotewrite:
    endpoint: "http://prometheus.monitoring:9090/api/v1/write"
  # Logs → Loki (optional)
  loki:
    endpoint: "http://loki.monitoring:3100/loki/api/v1/push"

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch, resource]
      exporters: [otlp/tempo]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [prometheusremotewrite]
```

### 6.2 ServiceMonitor for Prometheus Scraping

```yaml
# deploy/helm/templates/service-monitors.yaml
# For each Java service:
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: instacommerce-java-services
  namespace: monitoring
  labels:
    release: prometheus
spec:
  namespaceSelector:
    matchNames: [instacommerce]
  selector:
    matchLabels:
      monitoring: "true"     # add this label to all service Helm values
  endpoints:
    - path: /actuator/prometheus
      port: http
      interval: 15s
      scrapeTimeout: 10s
---
# For Go services:
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: instacommerce-go-services
  namespace: monitoring
spec:
  selector:
    matchLabels:
      monitoring: "true"
  endpoints:
    - path: /metrics
      port: http
      interval: 15s
```

Add `monitoring: "true"` to all service Helm `values-dev.yaml` entries.

---

## 7. Runbooks

Every alert rule must have a corresponding runbook. The following are implementation-ready runbooks for the platform's most critical alerts.

### 7.1 Runbook: `SLOBurnRateCritical`

**Alert trigger:** Error rate on service S is burning the 30-day error budget at 14.4× or faster.  
**Impact:** If not resolved within 2 hours, the monthly SLO is missed.

```
RUNBOOK: SLO Burn Rate Critical
================================
1. TRIAGE (< 5 min)
   a. Open the SLO dashboard: grafana.instacommerce.io/d/slo-overview
   b. Identify which service fired: check $labels.service
   c. Check error rate over last 15 min:
      query: rate(http_server_requests_seconds_count{service="$SERVICE",outcome="SERVER_ERROR"}[5m])
   d. Is this a single pod, AZ, or global?
      → If single pod: likely OOM/bad deploy → skip to step 4
      → If all pods: likely dependency or deploy → step 2

2. CHECK RECENT DEPLOYS
   a. `kubectl rollout history deployment/$SERVICE -n instacommerce`
   b. Cross-reference with CI/CD deploy log
   c. If deploy within last 30 min: go to step 4 (rollback)

3. CHECK DEPENDENCIES
   a. Downstream service errors:
      → Check payment-service, identity-service, inventory-service health pages
   b. Database connections:
      → Actuator: GET /actuator/health shows readinessState.db
      → Query: hikaricp_connections_active > hikaricp_connections_max * 0.9
   c. Kafka lag:
      → kafka_consumer_records_lag_max{group="$SERVICE"} > threshold

4. ROLLBACK (if deploy caused issue)
   a. `helm rollback $RELEASE -n instacommerce`
   b. Verify error rate drops within 2 min
   c. Page team lead; open incident

5. ESCALATION
   - After 15 min with no resolution: page engineering manager
   - After 30 min: declare SEV-1 incident
   - Notify: #incidents Slack channel with status update every 15 min

6. POST-INCIDENT
   - File postmortem within 48 h
   - Update error budget tracker
   - Add regression test if applicable
```

### 7.2 Runbook: `CheckoutLatencyBurnRateCritical`

**Alert trigger:** Checkout p99 latency SLO burning fast (requests not completing within 2s).

```
RUNBOOK: Checkout Latency Critical
====================================
1. IDENTIFY BOTTLENECK
   a. Open checkout trace: grafana.instacommerce.io/d/checkout-trace
   b. Find slowest spans in the last 30 min:
      → Check: Temporal activity timeouts, payment-service call, inventory reservation
   c. Latency histogram:
      histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{
        service="checkout-orchestrator-service",uri=~"/api/v1/checkout.*"}[5m]))

2. COMMON CAUSES
   a. Payment gateway timeout → check payment-service → gateway health
   b. Inventory lock contention → check inventory-service DB pool
   c. Temporal workflow queue depth → check Temporal UI for pending workflows
   d. GKE node pressure → check kube_node_status_condition{condition="MemoryPressure"}

3. MITIGATIONS
   a. If payment gateway: activate circuit breaker fallback (flag: payment.fallback.enabled)
   b. If Temporal backlog: scale checkout-orchestrator HPA manually
   c. If DB: increase HikariCP pool temporarily via config map

4. ROLLBACK
   Same as SLOBurnRateCritical step 4
```

### 7.3 Runbook: `KafkaConsumerLag` / `KafkaLagBurnRateCritical`

```
RUNBOOK: Kafka Consumer Lag
============================
1. IDENTIFY WHICH CONSUMER GROUP AND TOPIC
   kafka_consumer_records_lag_max by (group, topic)

2. DIAGNOSE
   a. Consumer down: check pod status for the consuming service
   b. Consumer slow: check processing time, GC pauses, DB write latency
   c. Producer spike: check producer throughput — peak flash sale?
   d. DLQ growing: check DLQ topic depth for poison messages

3. ACTIONS
   a. Pod down: Kubernetes will restart; monitor recovery
   b. Consumer slow: increase replica count for the consumer deployment
   c. Producer spike: this is expected during flash sales — pre-scale consumers
   d. DLQ growing: inspect dead-letter messages; fix or replay

4. FLASH SALE RUNBOOK VARIANT
   → Pre-scale: scale deployment/order-service --replicas=20 before flash event
   → Pre-scale: scale deployment/inventory-service --replicas=20
   → Pre-position: warm catalog and inventory caches
   → Monitor lag every 2 min during event
```

### 7.4 Runbook: `FrequentPodRestarts`

```
RUNBOOK: Frequent Pod Restarts
================================
1. IDENTIFY
   kubectl get pods -n instacommerce | grep -v Running
   kubectl describe pod $POD -n instacommerce | tail -30

2. CHECK CAUSE
   a. OOMKilled: increase memory limit or fix memory leak
   b. Liveness probe failing: check /actuator/health/liveness — hung thread?
   c. Bad startup config: check environment variable injection (sm://)
   d. Crash loop after deploy: rollback immediately

3. MEMORY LEAK FAST PATH
   a. jmap or heap dump: kubectl exec $POD -- jcmd 1 VM.native_memory
   b. Enable GC logging: add -XX:+PrintGCDetails temporarily
   c. Check Micrometer: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes
```

---

## 8. Incident Management Loop

### 8.1 SEV Classification

| SEV | Criteria | Initial Response | Resolution Target |
|---|---|---|---|
| SEV-1 | SLO missed; checkout/payment down; data loss risk | On-call + EM within 5 min | 30 min |
| SEV-2 | SLO burn > 3×; degraded UX for >10% users | On-call within 15 min | 2 h |
| SEV-3 | Warning alerts; single-service latency elevated | Team notified; next business hour | 8 h |
| SEV-4 | Non-customer-facing; infra advisory | Ticket; next sprint | Next sprint |

### 8.2 Incident Loop Process

```
Detect
  └─ Alert fires (Prometheus → Alertmanager → PagerDuty / Slack)
  └─ Synthetic check fails (Blackbox Exporter → alert)
  └─ Customer complaint (support escalation)

Triage (< 5 min for SEV-1/2)
  └─ On-call opens incident channel: #inc-YYYY-MM-DD-SERVICE
  └─ Assigns Incident Commander (IC)
  └─ Checks SLO dashboard + recent deploys + dependency health

Investigate
  └─ Distributed trace lookup (Tempo/Jaeger): find slow/error spans
  └─ Log correlation: grep correlationId across services
  └─ Dependency matrix check (Actuator /actuator/health)

Mitigate
  └─ Rollback, scale, circuit breaker, feature flag off
  └─ Status update every 15 min in incident channel
  └─ Customer communication if SLO breach visible

Resolve
  └─ Confirm metrics returning to baseline
  └─ Close PagerDuty alert
  └─ Post-incident message in #incidents

Postmortem (within 48 h for SEV-1, 1 week for SEV-2)
  └─ Timeline reconstruction (use traces + logs + CI/CD history)
  └─ Root cause (5-whys or fishbone)
  └─ Contributing factors
  └─ Action items with owners + due dates
  └─ Error budget impact recorded
```

### 8.3 Error Budget Review Cadence

| Cadence | Forum | Agenda | Owner |
|---|---|---|---|
| Weekly | SRE sync | Open incidents, error budget status, upcoming risky deploys | SRE Lead |
| Monthly | Reliability review | 30-day SLO report, budget burn by service, runbook gaps, capacity | Platform EM + SRE |
| Quarterly | Engineering all-hands | SLO trend, incident theme analysis, observability roadmap | Principal Engineer |

---

## 9. Synthetic Checks

Synthetic checks are the **first line of defence** for user-journey availability — they fire before real users are affected, and they catch issues that black-box Prometheus metrics miss (DNS, TLS, BGP, CDN).

### 9.1 Journey Coverage

| Journey | Check Type | Frequency | Alert Threshold |
|---|---|---|---|
| App homepage load | HTTP GET `https://instacommerce.io/` | 30s | Failure > 2 consecutive |
| Catalog API | HTTP GET `/api/v1/catalog/products?page=1` | 30s | p99 > 500ms or 5xx |
| Add to cart (synthetic user) | HTTP POST `/api/v1/cart/items` | 60s | Failure or latency > 2s |
| Checkout availability | HTTP GET `/api/v1/checkout/health` (custom endpoint) | 30s | 5xx or latency > 500ms |
| Payment service health | HTTP GET `/actuator/health/readiness` on payment-service | 30s | Not 200 |
| Identity service token | HTTP POST `/api/v1/auth/token` | 60s | Failure or latency > 500ms |
| Rider dispatch health | HTTP GET `/health/ready` on dispatch-optimizer | 30s | Not 200 |

### 9.2 Blackbox Exporter Configuration

```yaml
# monitoring/blackbox-exporter.yaml
modules:
  http_2xx_fast:
    prober: http
    timeout: 5s
    http:
      valid_http_versions: ["HTTP/1.1", "HTTP/2.0"]
      valid_status_codes: [200, 201, 202, 204]
      method: GET
      fail_if_ssl_error: true
      preferred_ip_protocol: "ip4"

  http_checkout_health:
    prober: http
    timeout: 3s
    http:
      valid_status_codes: [200]
      method: GET

  http_post_json:
    prober: http
    timeout: 5s
    http:
      method: POST
      body: '{"productId":"test-item-001","quantity":1}'
      headers:
        Content-Type: application/json
        Authorization: "Bearer ${SYNTHETIC_TEST_TOKEN}"
      valid_status_codes: [200, 201]
```

**Prometheus scrape config for Blackbox Exporter:**
```yaml
- job_name: 'blackbox-http'
  metrics_path: /probe
  params:
    module: [http_2xx_fast]
  static_configs:
    - targets:
        - https://api.instacommerce.io/api/v1/catalog/products
        - https://api.instacommerce.io/actuator/health
  relabel_configs:
    - source_labels: [__address__]
      target_label: __param_target
    - source_labels: [__param_target]
      target_label: instance
    - target_label: __address__
      replacement: blackbox-exporter:9115
```

**Alert rules for synthetic checks:**
```yaml
- alert: SyntheticCheckFailing
  expr: probe_success{job="blackbox-http"} == 0
  for: 2m
  labels:
    severity: page
  annotations:
    summary: "Synthetic check failing for {{ $labels.instance }}"
    runbook: "https://wiki.instacommerce.io/runbooks/synthetic-check-failure"

- alert: SyntheticCheckSlowResponse
  expr: probe_duration_seconds{job="blackbox-http"} > 2
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Synthetic check slow ({{ $value | humanizeDuration }}) for {{ $labels.instance }}"
```

### 9.3 Flash Sale Pre-Scaling Synthetic Check

Before flash events, run an elevated synthetic load test using k6:
```javascript
// scripts/synthetic/flash-sale-readiness.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '2m', target: 200 },  // ramp up to 200 vus
    { duration: '5m', target: 200 },  // sustain
    { duration: '1m', target: 0 },    // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(99)<2000'],  // checkout SLO
    http_req_failed: ['rate<0.01'],     // 1% error threshold
  },
};

export default function () {
  const res = http.post(`${__ENV.BASE_URL}/api/v1/checkout`, JSON.stringify({
    cartId: `synthetic-cart-${__VU}`,
  }), { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'checkout 2xx': (r) => r.status < 300 });
  sleep(1);
}
```

This script should be run by the SRE team 30 minutes before each scheduled flash event and must pass before the event launches.

---

## 10. Operational Readiness Checklist

Use this checklist before launching any new service or major feature to production.

### 10.1 Service Launch Gate

```
OPERATIONAL READINESS CHECKLIST — SERVICE LAUNCH
=================================================

OBSERVABILITY
[ ] /actuator/health, /actuator/health/liveness, /actuator/health/readiness all return 200
[ ] /actuator/prometheus endpoint returns metrics with service label
[ ] OTEL tracing initialised; test trace visible in Tempo/Grafana
[ ] Correlation ID propagated: verify X-Correlation-ID echoed in response
[ ] Structured logs in JSON with traceId, spanId, correlationId fields
[ ] Custom business metrics defined (at minimum: request count, latency, domain errors)

ALERTING
[ ] SLI recording rules defined for this service in prometheus-rules.yaml
[ ] Burn-rate alert rules added for this service
[ ] Runbook created and linked in alert annotation
[ ] Alert tested: fire the alert manually in staging and confirm PagerDuty receipt

SLOs
[ ] SLO ID assigned in the SLO table (Section 3.1)
[ ] Error budget policy acknowledged by service owner
[ ] Grafana SLO panel added to service dashboard

HEALTH CHECKS
[ ] Kubernetes readiness probe configured with /actuator/health/readiness
[ ] Kubernetes liveness probe configured with /actuator/health/liveness
[ ] Startup probe configured for services with slow init (Temporal, Flyway-heavy)
[ ] Graceful shutdown tested: SIGTERM drains in-flight requests before pod exits

SYNTHETIC CHECKS
[ ] At least one blackbox probe covers the service's primary endpoint
[ ] Synthetic check firing alert tested

DEPLOYMENT SAFETY
[ ] Helm chart includes podDisruptionBudget (minAvailable ≥ 1)
[ ] HPA configured with cpu/memory triggers and min=2 replicas
[ ] Rolling update maxUnavailable=0, maxSurge=1
[ ] Resource requests/limits set and validated (no OOM kills in load test)

ON-CALL
[ ] Service added to on-call rotation mapping
[ ] Team has been through the runbook for this service
[ ] Escalation path documented
```

### 10.2 Flash Sale Readiness Checklist

```
FLASH SALE READINESS CHECKLIST
================================
T-48h  [ ] Confirm peak load projections with product team
T-48h  [ ] Run load test at 2× expected peak; verify SLOs hold
T-24h  [ ] Pre-scale: increase replica counts for order, inventory, cart, checkout, catalog
T-24h  [ ] Warm caches: catalog + pricing + top-SKU inventory
T-24h  [ ] Enable enhanced tracing sampling (TRACING_PROBABILITY=1.0)
T-2h   [ ] All synthetic checks passing green
T-2h   [ ] Kafka consumer lag near zero
T-2h   [ ] Database connection pools at < 60% utilisation
T-2h   [ ] On-call engineer briefed; incident channel created: #flash-YYYY-MM-DD
T-1h   [ ] Fraud detection rules reviewed for expected volume spike
T-0    [ ] SRE engineer on live monitoring dashboard
T+0    [ ] Monitor every 5 min: error rate, checkout latency, Kafka lag, payment success rate
T+2h   [ ] Post-event review; scale down; capture peak metrics
```

---

## 11. Grafana Dashboard Specifications

Define dashboards as code (Grafonnet or JSON model checked into the repo). At minimum, implement:

### 11.1 SLO Overview Dashboard (Priority: P0)

**Panels:**
1. **Error Budget Gauge** per service — `slo:error_budget_remaining:*` — colour: green > 50%, yellow 10-50%, red < 10%
2. **Burn Rate Trend** — `(1 - sli:http_availability:rate1h) / slo_error_rate` — time-series, 72h window
3. **Current SLI vs Target** — gauge panel, one per service in the SLO table
4. **Active Burn Rate Alerts** — table panel from Alertmanager API
5. **Error Budget Consumption** — bar chart, 30-day cumulative

### 11.2 Service Health Dashboard

**Panels per service:**
1. Request rate (RPS)
2. Error rate (5xx fraction)
3. p50 / p95 / p99 latency
4. Active HTTP connections
5. JVM heap / GC pause (Java) or Go goroutine count (Go)
6. HikariCP pool utilisation (Java) — `hikaricp_connections_active / hikaricp_connections_max`
7. Cache hit rate — `cache_gets_hit_total / (cache_gets_hit_total + cache_gets_miss_total)`

### 11.3 Checkout Pipeline Dashboard (Priority: P0)

**Panels:**
1. Checkout submit rate (orders/min)
2. Checkout end-to-end p99 latency (target line at 2s)
3. Temporal workflow queue depth
4. Payment success / failure rate (breakdown by error code)
5. Inventory reservation success rate
6. DLQ message count (all checkout-related DLQs)
7. Active Temporal workflows (started vs completed vs timed-out)

### 11.4 Kafka & Messaging Dashboard

**Panels:**
1. Consumer lag by group + topic — heatmap
2. DLQ depth by topic
3. Producer throughput by topic
4. Consumer throughput by group
5. Replication lag (broker-level)

---

## 12. Q-Commerce–Specific Observability Patterns

### 12.1 Order Freshness SLI

For a ≤30-minute delivery promise, track time-in-state for each order:

```sql
-- This powers a Prometheus gauge via a scheduled query (e.g., Prometheus SQL Exporter)
SELECT 
  COUNT(*) AS stuck_orders,
  state
FROM orders
WHERE 
  EXTRACT(EPOCH FROM (NOW() - updated_at)) > 600  -- stuck > 10 min in any state
  AND state NOT IN ('DELIVERED', 'CANCELLED')
GROUP BY state;
```

Surface as: `instacommerce_orders_stuck_total{state="RIDER_ASSIGNED"}` — alert if > 0 for 5 min.

### 12.2 Rider Assignment Latency

```
# From dispatch-optimizer-service (Go):
# Add a custom histogram:
assignmentLatency = prometheus.NewHistogram(prometheus.HistogramOpts{
    Namespace: "dispatch_optimizer",
    Name:      "assignment_latency_seconds",
    Help:      "Time from order placed to rider assigned.",
    Buckets:   []float64{2, 5, 10, 20, 30, 60, 120},
})

# SLI: fraction of assignments completing within 10 s
sli:rider_assignment_under_10s:rate5m =
  sum(rate(dispatch_optimizer_assignment_latency_seconds_bucket{le="10.0"}[5m]))
  /
  sum(rate(dispatch_optimizer_assignment_latency_seconds_count[5m]))
```

### 12.3 Real-Time ETA Accuracy

Track the difference between predicted ETA and actual delivery time:

```python
# In routing-eta-service or a data platform job:
eta_accuracy_gauge = Gauge(
    'instacommerce_eta_accuracy_minutes',
    'Mean absolute error between predicted and actual ETA in minutes',
    ['city', 'hour_of_day']
)
# Feed from: orders table JOIN deliveries table WHERE delivered_at IS NOT NULL
```

Alert if mean absolute ETA error > 5 minutes over a 1-hour window.

### 12.4 Flash Sale Detection and Auto-Scaling

Add a recording rule that detects abnormal traffic spikes (flash sale signals):

```yaml
- record: instacommerce:catalog_rps:spike_factor
  expr: |
    rate(http_server_requests_seconds_count{service="catalog-service"}[5m])
    /
    rate(http_server_requests_seconds_count{service="catalog-service"}[1h] offset 5m)

- alert: FlashSaleTrafficSpike
  expr: instacommerce:catalog_rps:spike_factor > 5
  for: 2m
  labels:
    severity: info
    automation: pre_scale
  annotations:
    summary: "Flash sale traffic detected — 5× normal; pre-scaling triggered"
    action: "Trigger HPA override and cache warm-up via webhook"
```

Wire the `automation: pre_scale` label to an Alertmanager webhook that calls a scale-out runbook via GitHub Actions or a custom operator.

---

## 13. Gap Prioritisation and Implementation Roadmap

### P0 — Must fix before next production incident (Week 1-2)

| # | Gap | Effort | Owner |
|---|---|---|---|
| P0-1 | Deploy OTEL collector (without it traces are silently dropped) | 1 day | Platform |
| P0-2 | Add SLI recording rules to `prometheus-rules.yaml` | 2 days | SRE |
| P0-3 | Add multi-window burn-rate alert rules | 1 day | SRE |
| P0-4 | Add 1 runbook per existing alert + 2 new burn-rate runbooks | 3 days | SRE |
| P0-5 | Verify MDC traceId/spanId in production logs (may be free via Spring autoconfigure) | 1 day | Platform |

### P1 — Before next feature milestone (Week 3-6)

| # | Gap | Effort | Owner |
|---|---|---|---|
| P1-1 | Correlation ID middleware for Go services (`go-shared/pkg/observability/correlation.go`) | 2 days | Platform |
| P1-2 | Java `CorrelationIdFilter` and Logback JSON pattern update | 2 days | Platform |
| P1-3 | Kafka trace context injection (producer + consumer) | 3 days | Platform |
| P1-4 | Python AI services: OTEL + Prometheus (telemetry.py) | 2 days | AI Platform |
| P1-5 | ServiceMonitor CRDs + Prometheus scrape config for all services | 2 days | SRE |
| P1-6 | Blackbox Exporter deployment + 7 synthetic check probes | 2 days | SRE |
| P1-7 | SLO Overview Grafana dashboard | 3 days | SRE |
| P1-8 | Checkout Pipeline Grafana dashboard | 2 days | SRE |

### P2 — Operational maturity (Week 7-12)

| # | Gap | Effort | Owner |
|---|---|---|---|
| P2-1 | Error budget CI gate (block prod deploys when budget < 10%) | 3 days | Platform + CI |
| P2-2 | Temporal metrics scraping + Temporal-specific SLO | 3 days | Platform |
| P2-3 | Dispatch-optimizer OTLP consolidation (HTTP vs gRPC fix) | 1 day | Platform |
| P2-4 | `AlwaysSampleCheckoutSampler` for 100% money-path tracing | 2 days | Platform |
| P2-5 | Flash sale k6 script + pre-event runbook automation | 3 days | SRE |
| P2-6 | Order freshness + ETA accuracy custom metrics | 3 days | Analytics + SRE |
| P2-7 | Incident loop formalisation + postmortem template | 2 days | Engineering Managers |

---

## 14. Anti-Patterns to Avoid

1. **Alert-on-symptoms, not causes.** The current `HighErrorRate` rule alerts on the symptom. Burn-rate rules are better because they measure error *budget consumption* — a slow leak that won't miss your SLO is not worth waking someone up at 3 AM.

2. **Over-alerting on warnings.** The current config sends all warnings to Slack. At high volume, warning-flood causes alert fatigue. Route `severity: warning` to a Slack channel that is checked proactively but does not interrupt. Only `severity: page` should hit PagerDuty.

3. **Sampling traces in staging, not prod.** The inverse of what is needed. Use 10% sampling in prod normal operations but 100% for money-path and during incidents.

4. **Log aggregation without structure.** If any service is logging unstructured text, structured queries become impossible. All services (Java, Go, Python) must emit JSON logs with `traceId`, `spanId`, `correlationId`, `service_name`, `service_version`, `environment`.

5. **Kafka `correlation_id` without HTTP trace linkage.** The `correlation_id` field in the event envelope is only useful if it was populated from the originating HTTP request's `X-Correlation-ID` header. Verify the order-service outbox writer reads `correlationId` from the request context, not generates a new UUID.

6. **SLOs without error budget policy.** Defining 99.9% SLOs without a freeze policy creates no incentive to protect the budget. The policy in Section 3.3 must be enforced, not just documented.

7. **Health checks that always return 200.** The current `health/handler.go` runs dependency checks and returns 503 on failures — that is correct. Ensure Java services' `readinessState,db` group is configured to actually probe the database, not just check that the datasource bean exists.

---

## 15. Summary of Findings

| Domain | Score | Key Action |
|---|---|---|
| Metric collection | 7/10 | Consistent across Java+Go; Python missing; no ServiceMonitor CRDs |
| Alerting | 3/10 | 5 rules, no burn-rate, no per-service SLOs, no runbooks linked |
| Distributed tracing | 5/10 | Wired but collector not deployed; Kafka + Python gaps; OTLP inconsistency |
| Correlation IDs | 4/10 | In event envelope, in error responses; missing as request middleware |
| SLI/SLO governance | 2/10 | No formal SLOs, no recording rules, no error budget policy |
| Synthetic checks | 0/10 | None exist; critical gap for q-commerce |
| Incident loop | 2/10 | Ad hoc; no SEV classification, no postmortem process |
| Runbooks | 1/10 | None linked to alerts |
| Operational readiness | 3/10 | Implicit (Actuator + health probes); no formal gate |
| **Overall** | **3/10** | **Platform is instrumented but not operated** |

The platform is not blind — metrics and traces exist in code. The gap is that they are not **operationalised**: no one has defined what "good" looks like (SLOs), what "going wrong" looks like (burn-rate alerts), what to do when it goes wrong (runbooks), or how to verify the journey is healthy without real users (synthetics). Closing the P0 and P1 gaps above transforms this from an instrumented codebase into an **operated platform**.
