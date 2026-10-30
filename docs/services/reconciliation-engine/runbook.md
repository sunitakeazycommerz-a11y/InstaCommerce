# Reconciliation Engine - Operational Runbook

## Service Metadata
- **Module Path:** `services/reconciliation-engine`
- **Canonical Port:** `8107` (HTTP API, Health, and Metrics)
- **Readiness Probe:** `http://localhost:8107/health/ready`
- **Liveness Probe:** `http://localhost:8107/health/live`
- **Metrics Endpoint:** `http://localhost:8107/metrics`
- **Local Startup:** `cd services/reconciliation-engine && go run main.go`
- **State Ownership:** Financial mismatch detection and reconciliation between payment provider exports and internal ledgers. Publishes to `reconciliation.events`.

---

## Common Incidents and Triage

### 1. Reconciliation Job Failure (Alert: `ReconciliationEngineJobFailure`)
**Symptoms:** `reconciliation_job_failures_total > 0` or cron reconciliation fails to complete within timeout.
**Diagnosis:**
```bash
# Check reconciliation metrics
curl -s http://localhost:8107/metrics | grep -E "reconciliation_run|reconciliation_job_failures"

# Inspect application logs for file I/O or JSON parsing errors
kubectl logs -n instacommerce deploy/reconciliation-engine --tail=100 | grep -i "error\|fail\|timeout"
```
**Resolution:**
1. Check file mount accessibility for PSP exports and internal ledger snapshots. Ensure JSON input files are mounted and non-empty.
2. Verify Kafka broker connectivity for topic `reconciliation.events`.
3. If necessary, trigger an on-demand reconciliation run or review run statuses via `GET /reconciliation/runs`.

### 2. High Mismatch Count (Alert: `ReconciliationMismatchesDetected`)
**Symptoms:** Discrepancies between PSP payments and internal order ledger records exceed alert thresholds.
**Diagnosis:**
```bash
# Query the latest run ID from /reconciliation/runs
LATEST_RUN_ID=$(curl -s http://localhost:8107/reconciliation/runs | jq -r '.[0].id // 1')

# List mismatches for the specific run ID
curl -s http://localhost:8107/reconciliation/runs/${LATEST_RUN_ID}/mismatches
```
**Resolution:**
1. Classify mismatch types: amount mismatch, missing PSP record, missing internal ledger record, or status mismatch.
2. Check for payment gateway webhook delivery delays or clock skew between systems.
3. Apply idempotent safe auto-fixes or manually approve discrepancy resolutions via `POST /reconciliation/mismatches/{id}/fix`.
