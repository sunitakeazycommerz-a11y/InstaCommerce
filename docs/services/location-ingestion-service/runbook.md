# Location Ingestion Service - Operational Runbook

## Service Metadata
- **Module Path:** `services/location-ingestion-service`
- **Canonical Port:** `8105` (API, Health, and Metrics)
- **Readiness Probe:** `http://localhost:8105/health/ready`
- **Liveness Probe:** `http://localhost:8105/health/live`
- **Metrics Endpoint:** `http://localhost:8105/metrics`
- **Local Startup:** `cd services/location-ingestion-service && go run main.go`
- **State Ownership:** Ephemeral Redis H3 spatial indexing and Kafka producer to `rider.location.updates`.

---

## Pre-Deployment Verification Checklist

- [ ] Check Redis connectivity (spatial indexing)
  ```bash
  kubectl exec -n instacommerce deploy/location-ingestion-service -- \
    curl -s http://localhost:8105/health/ready
  ```

- [ ] Verify Kafka broker reachability (`rider.location.updates` topic)
  ```bash
  kubectl exec -n instacommerce deploy/location-ingestion-service -- \
    curl -s http://localhost:8105/health/ready | jq '.details.kafka'
  ```

---

## Deployment Procedures

### Baseline Verification
```bash
# Check location ingestion rate and dropped updates
curl -s http://localhost:8105/metrics | grep -E "location_ingestion_updates_total|location_ingestion_drop_total"

# Check processing duration
curl -s http://localhost:8105/metrics | grep location_ingestion_duration_seconds
```

### Canary Rollout
```bash
helm upgrade location-ingestion-service deploy/helm/ -n instacommerce
kubectl rollout status deploy/location-ingestion-service -n instacommerce --timeout=5m

# Verify readiness after rollout
curl -s http://localhost:8105/health/ready | jq .
```

---

## Common Incidents and Triage

### 1. High Ingestion Drop Rate
**Symptoms:** `location_ingestion_drop_total` metric increases. Riders report stale location tracking.
**Diagnosis:**
```bash
# Check drop metrics broken down by reason (rate_limit, invalid, stale, redis, kafka)
curl -s http://localhost:8105/metrics | grep location_ingestion_drop_total

# Inspect application logs
kubectl logs -n instacommerce deploy/location-ingestion-service --tail=100 | grep -i "drop\|error\|stale"
```
**Resolution:**
1. If dropped due to `rate_limit`: check if rider mobile app GPS ping frequency exceeds configured threshold (`LOCATION_RATE_LIMIT_RIDER`).
2. If dropped due to `stale`: check for clock skew on rider mobile devices or delayed cellular data transmission.
3. If dropped due to `kafka buffer full`: scale pod replicas or increase `KAFKA_BUFFER` buffer size.

### 2. Redis Connection Saturation
**Symptoms:** Readiness probe fails with Redis error.
**Diagnosis:**
```bash
# Verify Redis ping and connection count
redis-cli -h redis INFO clients
redis-cli -h redis INFO memory
```
**Resolution:**
1. Check Redis memory pressure. Ensure H3 index keys expire according to configured `REDIS_TTL` (default 30 seconds).
2. Increase Redis client pool size if required.
