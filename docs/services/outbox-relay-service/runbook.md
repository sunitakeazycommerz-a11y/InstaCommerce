# Outbox Relay Service - Operational Runbook

## Service Metadata
- **Module Path:** `services/outbox-relay-service`
- **Canonical Port:** `8103` (Health and Metrics)
- **Readiness Probe:** `http://localhost:8103/health/ready`
- **Liveness Probe:** `http://localhost:8103/health/live`
- **Metrics Endpoint:** Exported via OpenTelemetry OTLP HTTP to collector (no local `/metrics` endpoint)
- **Local Startup:** `cd services/outbox-relay-service && go run main.go`
- **State Ownership:** Relays rows from PostgreSQL `outbox_events` to Apache Kafka domain topics.

---

## Common Incidents and Triage

### 1. High Outbox Publishing Lag
**Symptoms:** Downstream consumers report delayed domain events. `outbox_events` table shows growing un-relayed rows where `sent = false`.
**Diagnosis:**
```bash
# Query un-relayed row count in PostgreSQL
SELECT count(*) FROM outbox_events WHERE sent = false;

# Inspect relay metrics via OpenTelemetry collector or Prometheus remote write
# Metrics: outbox_relayed_total, outbox_relay_failures_total, outbox_dlq_total, outbox_relay_lag_seconds

# Check logs for Kafka publish timeouts or backpressure
kubectl logs -n instacommerce deploy/outbox-relay-service --tail=100 | grep -i "kafka\|publish\|timeout"
```
**Resolution:**
1. Check Kafka broker health and partition leader availability.
2. Confirm that the partial index `CREATE INDEX idx_outbox_unsent ON outbox_events (created_at) WHERE sent = false;` is present and healthy.
3. Check PostgreSQL connection pool saturation. If database connections are exhausted, increase `DB_MAX_OPEN_CONNS`.

### 2. Kafka Broker Unreachable / Producer Errors
**Symptoms:** Relay logs `kafka: dial tcp: connect: connection refused` or `WriteMessage failed`.
**Diagnosis:**
```bash
# Verify Kafka connectivity from inside pod
kubectl exec -it -n instacommerce deploy/outbox-relay-service -- nc -zv kafka-broker-1 9092
```
**Resolution:**
1. Check the `KAFKA_BROKERS` environment variable configuration.
2. Verify Kubernetes NetworkPolicy allows egress from `outbox-relay-service` to the Kafka broker pods.
3. Once connectivity recovers, the relay automatically retries unsent rows in chronological order.
