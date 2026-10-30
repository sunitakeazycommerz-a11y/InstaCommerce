# Stream Processor Service - Operational Runbook

## Service Metadata
- **Module Path:** `services/stream-processor-service`
- **Canonical Port:** `8108` (Health and Metrics)
- **Readiness Probe:** `http://localhost:8108/health/ready`
- **Liveness Probe:** `http://localhost:8108/health/live`
- **Metrics Endpoint:** `http://localhost:8108/metrics`
- **Local Startup:** `cd services/stream-processor-service && go run main.go`
- **State Ownership:** Computes operational business metrics from Kafka domain topics and writes sliding-window aggregates to Redis and Prometheus.

---

## Common Incidents and Triage

### 1. Consumer Group Lag Growth
**Symptoms:** Real-time dashboards show delayed order velocity or rider telemetry.
**Diagnosis:**
```bash
# Check Kafka consumer group lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group stream-processor --describe

# Check stream processor throughput metrics
curl -s http://localhost:8108/metrics | grep -E "orders_total|payments_total|riders_by_status|inventory_stock_updates_total"

# Inspect pod logs for processing errors
kubectl logs -n instacommerce deploy/stream-processor-service --tail=100 | grep -i "error\|lag\|timeout"
```
**Resolution:**
1. Check Redis latency and connection pool saturation. If Redis writes are blocking, scale Redis or check CPU utilization.
2. Verify Kafka partition distribution across consumer replicas. Scale pod replicas up to match partition counts.
3. Check for malformed JSON payloads in specific topics.

### 2. Redis Connection Failures / Pool Exhaustion
**Symptoms:** Logs show `redis: connection pool timeout` or `dial tcp: connection refused`.
**Diagnosis:**
```bash
# Check Redis reachability
kubectl exec -it -n instacommerce deploy/stream-processor-service -- nc -zv redis 6379

# Inspect Redis server memory and client connections
redis-cli -h redis INFO clients
redis-cli -h redis INFO memory
```
**Resolution:**
1. Check Redis pod status in Kubernetes.
2. Verify `REDIS_ADDR` configuration and network policies.
3. If Redis memory is exhausted, verify that key expiration TTLs are active on sliding-window keys.
