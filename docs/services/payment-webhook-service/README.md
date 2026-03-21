# Payment Webhook Service - Comprehensive Documentation

## Overview
**Ownership**: Platform Team - Money Path Integration
**Language**: Go 1.24
**Default Port**: 8120 (exposed as 8106 internally)
**Status**: Tier 1 Critical (Payment Processing)
**SLO**: 99.95% availability, P99 < 500ms

---

## Architecture
**Go Service** (not Java/Spring Boot) for high-performance webhook processing

**Key Files**:
- `main.go` - Server setup
- `handler/webhook.go` - Webhook endpoint
- `handler/dedup.go` - Deduplication logic
- `handler/verify.go` - Webhook signature verification
- `handler/metrics.go` - Prometheus metrics

---

## Endpoints
- `POST /webhook/payment` - Receive Stripe webhooks
- `POST /webhook/verify` - Verify webhook signature
- `GET /health` - Health check
- `GET /metrics` - Prometheus metrics

---

## Configuration
**Dockerfile Port**: Exposes 8120
**Spring Port Mapping**: 8106 (internal routing)
**Environment Variables**:
```
PORT=8120
STRIPE_WEBHOOK_SECRET=<secret>
KAFKA_BROKERS=kafka:9092
KAFKA_TOPIC=payment.webhooks
```

---

## Key Features
- **Webhook Deduplication**: Dedup table (in-memory or Redis) prevents duplicate processing
- **Signature Verification**: Validates Stripe webhook authenticity using HMAC-SHA256
- **Kafka Publishing**: Publishes payment events to `payment.webhooks` topic
- **High Throughput**: Go goroutines handle concurrent webhooks
- **Error Handling**: Automatic retries with exponential backoff

---

## Webhook Payload (from Stripe)
```json
{
  "id": "evt_1234567890",
  "type": "charge.succeeded",
  "created": 1234567890,
  "data": {
    "object": {
      "id": "ch_1234567890",
      "amount": 99999,
      "currency": "inr",
      "status": "succeeded",
      "metadata": {
        "order_id": "550e8400-e29b-41d4-a716-446655440000"
      }
    }
  }
}
```

---

## Deduplication
**Key**: `webhook_id` (Stripe event ID)
**Storage**: In-memory map with TTL cleanup
**TTL**: 24 hours

---

## Kafka Integration
**Topic**: `payment.webhooks`
**Format**: Same EventEnvelope as other services

**Events Published**:
- charge.succeeded
- charge.failed
- refund.created
- refund.updated

---

## Deployment
- Port: 8120
- Replicas: 3
- CPU: 200m request / 800m limit (lighter than Java)
- Memory: 128Mi request / 256Mi limit
- Namespace: money-path

---

## Health & Monitoring
- Liveness: `GET /health`
- Metrics: `GET /metrics` (Prometheus format)
- Key metric: `webhook_processing_duration_ms`

---

## Security
- **Webhook Secret**: Stored in Google Secret Manager
- **HMAC Verification**: All webhooks verified before processing
- **Rate Limiting**: Optional (configurable per Stripe limits)

---

## Documentation Files
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Database ERD](diagrams/erd.md)
- [Request/Response Flows](diagrams/flowchart.md)
- [Sequence Diagrams](diagrams/sequence.md)
- [REST API Contract](implementation/api.md)
- [Kafka Events](implementation/events.md)
- [Database Details](implementation/database.md)
- [Resilience & Retries](implementation/resilience.md)
- [Deployment Runbook](runbook.md)
