# CDC Consumer Service - Operational Runbook

## Service Metadata
- **Module Path:** `services/cdc-consumer-service`
- **Canonical Port:** `8104` (Health and Metrics)
- **Readiness Probe:** `http://localhost:8104/health/ready`
- **Liveness Probe:** `http://localhost:8104/health/live`
- **Metrics Endpoint:** `http://localhost:8104/metrics`
- **Local Startup:** `cd services/cdc-consumer-service && go run main.go`
- **State Ownership:** Sinks Debezium CDC Kafka topics into Google BigQuery `cdc_events` table and publishes failed envelopes to `cdc.dlq`.

---

## Common Incidents and Triage

### 1. High Kafka Consumer Lag on Debezium Topics
**Symptoms:** BigQuery `cdc_events` rows lag behind live PostgreSQL database transactions.
**Diagnosis:**
```bash
# Check consumer group lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group cdc-consumer-service --describe

# Check BigQuery insert latency and error metrics
curl -s http://localhost:8104/metrics | grep -E "cdc_consumer_lag|cdc_batch_latency_seconds|cdc_dlq_total"

# Check logs for BigQuery API rate limits
kubectl logs -n instacommerce deploy/cdc-consumer --tail=100 | grep -i "bigquery\|quota\|rateLimit"
```
**Resolution:**
1. Check Google BigQuery streaming insert quota. If quota is exceeded, adjust `BATCH_SIZE` and `FLUSH_INTERVAL` to aggregate larger payloads.
2. Verify Kafka partitions. Increase partition count and pod replicas if consumer CPU or network bandwidth is saturated.
3. Verify network egress from Kubernetes cluster to BigQuery endpoints.

### 2. Poison Pill Messages Routed to Dead-Letter Queue (`cdc.dlq`)
**Symptoms:** `cdc_dlq_total` metric increases. Messages fail schema normalization.
**Diagnosis:**
```bash
# Consume messages from DLQ to inspect failed payload
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic cdc.dlq --max-messages 5 --from-beginning
```
**Resolution:**
1. Inspect the error header `dlq_reason` on DLQ messages to understand why parsing failed (for example schema evolution in upstream Postgres).
2. Update the BigQuery table schema or schema parser if new columns were added upstream.
3. Replay DLQ messages once the parser or BigQuery schema is updated.
