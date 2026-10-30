# Reverse ETL Orchestrator - Operational Runbook

## Service Metadata
- **Module Path:** `services/reverse-etl-orchestrator`
- **Canonical Ports:** `8109` (API), `8110` (Health), `9090` (Metrics)
- **Readiness Probe:** `http://localhost:8110/health/ready`
- **Liveness Probe:** `http://localhost:8110/health/live`
- **Metrics Endpoint:** `http://localhost:9090/metrics`
- **Local Startup:** `cd services/reverse-etl-orchestrator && go run ./cmd/server`

---

## Common Incidents & Triage

### 1. High Sync Lag (>60 minutes)
**Symptoms:** Operational caches (e.g. user tier or recommendation scores) are stale.
**Diagnosis:**
```bash
# Check metrics
curl -s http://localhost:9090/metrics | grep reverse_etl_sync_lag_seconds

# Check logs for BigQuery query timeouts
kubectl logs -n instacommerce deploy/reverse-etl-orchestrator --tail=100 | grep -i "timeout\|quota"
```
**Resolution:**
1. Check Google BigQuery slot utilization.
2. Verify Redis operational cluster memory capacity (`INFO memory`).
3. If necessary, trigger manual sync:
   ```bash
   curl -X POST http://localhost:8109/api/v1/subscriptions/{id}/activate
   ```

### 2. BigQuery Rate Limit / Quota Exceeded (429)
**Symptoms:** Worker pool errors: `googleapi: Error 403: Exceeded rate limits`.
**Resolution:**
1. Adjust sync interval from continuous to batch window (`--sync-interval=30m`).
2. Scale down worker concurrency via `WORKER_POOL_CONCURRENCY=5`.
