# Payment Webhook Service - Operational Runbook

## Service Metadata
- **Module Path:** `services/payment-webhook-service`
- **Canonical Port:** `8106` (Webhook Ingestion, Health, and Metrics)
- **Readiness Probe:** `http://localhost:8106/health/ready`
- **Liveness Probe:** `http://localhost:8106/health/live`
- **Metrics Endpoint:** `http://localhost:8106/metrics`
- **Local Startup:** `cd services/payment-webhook-service && go run main.go`
- **State Ownership:** Ingests external PSP webhooks (Stripe/Razorpay/PhonePe), verifies HMAC/RSA signatures, deduplicates via Redis, and forwards normalized events to Kafka `payment.webhooks`.

---

## Pre-Deployment Verification Checklist

- [ ] Verify webhook secret configuration (`STRIPE_WEBHOOK_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `PHONEPE_SALT_KEY`)
- [ ] Check Redis connectivity for idempotency deduplication
  ```bash
  kubectl exec -n instacommerce deploy/payment-webhook-service -- \
    curl -s http://localhost:8106/health | jq '.checks[] | select(.name=="redis")'
  ```

- [ ] Verify Kafka broker reachability (`payment.webhooks` topic)
  ```bash
  kubectl exec -n instacommerce deploy/payment-webhook-service -- \
    curl -s http://localhost:8106/health | jq '.checks[] | select(.name=="kafka")'
  ```

---

## Deployment Procedures

### Baseline Verification
```bash
# Check webhook receipt rate and signature validation errors
curl -s http://localhost:8106/metrics | grep -E "payment_webhook_events_total|payment_webhook_signature_failures_total"

# Check delivery latency
curl -s http://localhost:8106/metrics | grep payment_webhook_duration_seconds
```

### Canary Rollout
```bash
helm upgrade payment-webhook-service deploy/helm/ -n instacommerce
kubectl rollout status deploy/payment-webhook-service -n instacommerce --timeout=5m

# Verify readiness after rollout
curl -s http://localhost:8106/health/ready | jq .
```

---

## Common Incidents and Triage

### 1. High Signature Failure Rate (Alert: `PaymentWebhookSignatureFailure`)
**Symptoms:** `payment_webhook_signature_failures_total` spikes. PSP webhooks return HTTP 401/400.
**Diagnosis:**
```bash
# Check signature failure logs
kubectl logs -n instacommerce deploy/payment-webhook-service --tail=100 | grep -i "signature\|unauthorized\|hmac"
```
**Resolution:**
1. Verify if the PSP rotated its signing secret without updating Kubernetes secret `payment-webhook-secret`.
2. Inspect clock drift on the server against the timestamp header (`Stripe-Signature` or `X-Adyen-Signature`).
3. Update `WEBHOOK_SECRET` in Kubernetes secrets and trigger a rollout restart.

### 2. High Webhook Delivery Failure (Alert: `PaymentWebhookDeliveryFailure`)
**Symptoms:** Webhook delivery to Kafka fails (>1% failure rate).
**Diagnosis:**
```bash
# Check Kafka publish errors
curl -s http://localhost:8106/metrics | grep payment_webhook_delivery_failed_total

kubectl logs -n instacommerce deploy/payment-webhook-service --tail=100 | grep -i "kafka\|publish\|timeout"
```
**Resolution:**
1. Check Kafka broker health and partition availability for topic `payments.events`.
2. Verify Redis deduplication is not failing closed. If Redis is down, verify in-memory fallback behavior.
3. Once Kafka recovers, PSP webhooks will be re-delivered automatically by the payment gateway's retry schedule.
