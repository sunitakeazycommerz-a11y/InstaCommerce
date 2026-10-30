# Dispatch Optimizer Service - Operational Runbook

## Service Metadata
- **Module Path:** `services/dispatch-optimizer-service`
- **Canonical Port:** `8102` (HTTP API and Health)
- **Readiness Probe:** `http://localhost:8102/health/ready`
- **Liveness Probe:** `http://localhost:8102/health/live`
- **Metrics Endpoint:** `http://localhost:8102/metrics`
- **Local Startup:** `cd services/dispatch-optimizer-service && go run main.go`
- **State Ownership:** Stateless. It owns no database and produces no Kafka events.

---

## Common Incidents and Triage

### 1. High Latency During Batch Assignment (>500ms)
**Symptoms:** Callers such as `rider-fleet-service` log circuit breaker trips or timeouts on `POST /v2/optimize/assign`.
**Diagnosis:**
```bash
# Check solver latency metrics
curl -s http://localhost:8102/metrics | grep dispatch_solver_duration_seconds

# Inspect memory and CPU consumption
kubectl top pod -l app=dispatch-optimizer-service -n instacommerce
```
**Resolution:**
1. Check the matrix dimension in the request payload. Large batches with greater than 200 orders and 200 riders exceed typical Hungarian solver latency budgets.
2. Verify if order batching is enabled in `rider-fleet-service`.
3. If necessary, fail over to the greedy V1 endpoint `POST /optimize/assign`.

### 2. No Feasible Assignment Returned (Empty Assignment Array)
**Symptoms:** `POST /v2/optimize/assign` returns HTTP 200 with zero assigned pairs.
**Diagnosis:**
```bash
# Inspect application logs for constraint violations
kubectl logs -n instacommerce deploy/dispatch-optimizer-service --tail=100 | grep -i "infeasible\|unassigned"
```
**Resolution:**
1. Check rider status filters in the incoming request. Ensure riders have battery capacity and vehicle capability matching the order payload.
2. Verify maximum pickup distance parameters. If geo-fence radii are set too tightly, no riders satisfy the constraint mask.
3. Confirm rider coordinates are valid H3 indexes or lat/long pairs.
