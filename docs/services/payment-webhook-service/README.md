# Payment Webhook Service - Comprehensive Documentation

## Overview
**Ownership**: Platform Team - Money Path Integration
**Language**: Go 1.25
**Default Port**: 8106
**Status**: Tier 1 Critical (Payment Processing)
**SLO**: 99.95% availability, P99 < 500ms

---

## Architecture
**Go Service** for high-performance webhook ingestion and PSP signature verification

**Key Files**:
- `main.go` - Server setup, graceful shutdown, Kafka publisher
- `handler/webhook.go` - Webhook ingestion handler (`POST /payments/webhook`)
- `handler/dedup.go` - Idempotency store (in-memory TTL map or Redis SET NX EX)
- `handler/verify.go` - PSP signature verification (Stripe, Razorpay, PhonePe)
- `handler/metrics.go` - Prometheus metrics per PSP

---

## Endpoints
- `POST /payments/webhook` - Multi-PSP webhook callback intake
- `GET /health` - Overall dependency health probe
- `GET /health/ready` - Readiness probe (checks Redis and Kafka)
- `GET /health/live` - Liveness probe
- `GET /ready` - Legacy readiness alias
- `GET /metrics` - Prometheus metrics

---

## Configuration
**Port**: `8106` (`PORT: 8106`)
**Environment Variables**:
```
PORT=8106
STRIPE_WEBHOOK_SECRET=<secret>
RAZORPAY_WEBHOOK_SECRET=<secret>
PHONEPE_SALT_KEY=<secret>
KAFKA_BROKERS=kafka:9092
KAFKA_TOPIC=payment.webhooks
REDIS_ADDR=redis:6379
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
**Format**: Canonical `WebhookEvent` JSON envelope

**Events Published**:
- `payment.captured`
- `payment.failed`
- `refund.processed`
- `charge.dispute.created`

---

## Deployment
- Port: 8106
- Replicas: 3
- CPU: 200m request / 800m limit
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
