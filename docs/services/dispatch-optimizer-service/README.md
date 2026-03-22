# Dispatch Optimizer Service

## Overview

The dispatch-optimizer-service is responsible for optimal rider assignment and route planning in InstaCommerce. It ingests real-time location data from riders and inventory positions, calculates distances using Haversine formula, applies constraint-based optimization algorithms, and assigns delivery tasks to minimize ETA variance and cost.

**Service Ownership**: Fulfillment Platform Team - Route Optimization
**Language**: Go 1.22
**Default Port**: 8083
**Status**: Tier 1 Critical (Delivery routing)

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **P99 Latency**: < 2 seconds for optimization requests
- **P95 Accuracy**: 95% of assignments within 10% of optimal cost
- **Error Rate**: < 0.1% (all errors logged for post-hoc analysis)
- **Throughput**: 5,000 assignments/minute (surge capacity)

## Key Responsibilities

1. **Distance Calculation**: Haversine formula for great-circle distances between rider and warehouse locations
2. **Assignment Optimization**: Constraint-based solver (rider capacity, time windows, skill requirements) to minimize delivery cost
3. **Route Aggregation**: Combine multiple delivery tasks into efficient multi-stop routes
4. **Real-time Rebalancing**: React to location updates and reassign tasks if improvement > 5%

## Deployment

### GKE Deployment
- **Namespace**: fulfillment
- **Replicas**: 4 (HA, stateless)
- **CPU Request/Limit**: 1000m / 2000m
- **Memory Request/Limit**: 768Mi / 1.5Gi
- **Horizontal Pod Autoscaler**: 4-12 replicas based on request latency (p99 < 2s)

### Cluster Configuration
- **Ingress**: Internal-only (behind Istio IngressGateway, fulfillment team + checkout-orchestrator)
- **NetworkPolicy**: Deny-default; allow from location-ingestion-service, rider-fleet-service, checkout-orchestrator
- **Service Account**: dispatch-optimizer-service
- **Cache**: In-memory LRU cache for recent optimization requests (10K entries, 5-minute TTL)

## Architecture

### Optimization Flow

```
┌──────────────────────────────────────────────────────────────┐
│ Checkout Order Placement                                      │
│ POST /dispatch/v1/assignments                                 │
└────────────────────────┬─────────────────────────────────────┘
                         │
         ┌───────────────▼────────────────────┐
         │ Dispatch Optimizer                 │
         │ 1. Fetch active riders (Redis)     │
         │ 2. Fetch rider locations (recent)  │
         │ 3. Fetch warehouse inventory       │
         │ 4. Calculate distances (Haversine) │
         │ 5. Solve assignment problem        │
         │ 6. Emit optimization events        │
         └───────────────┬────────────────────┘
                         │
    ┌────────────────────┼────────────────────┐
    │                    │                    │
    ▼                    ▼                    ▼
 Rider Fleet      Warehouse           Location Ingestion
(assignment)      (inventory)         (acknowledgment)
```

### Integrations

| Service | Endpoint | Timeout | Purpose |
|---------|----------|---------|---------|
| location-ingestion-service | http://location-ingestion-service:8087/locations/riders | 2s | Get rider real-time locations |
| rider-fleet-service | http://rider-fleet-service:8085/riders/active | 3s | Get active riders + constraints |
| warehouse-service | http://warehouse-service:8088/inventory | 2s | Get inventory availability |
| checkout-orchestrator | Kafka (dispatch.assignments) | - | Publish assignment results |

## Endpoints

### Public (Unauthenticated)
- `GET /health` - Liveness probe (optimization engine status)
- `GET /metrics` - Prometheus metrics (distance calculations, optimization duration)

### Internal API (Requires X-Internal-Token)
- `POST /api/v1/assignments` - Request optimal assignment for new orders
- `GET /api/v1/assignments/{assignmentId}` - Get assignment details + rationale
- `GET /api/v1/riders/capacity` - Get active riders and current capacity
- `POST /api/v1/rebalance` - Trigger rebalancing of all active routes
- `GET /api/v1/metrics/optimization` - Optimization quality metrics (cost, distance, time)

### Example Requests

```bash
# Get authorization token
TOKEN=$(curl -X POST http://identity-service:8080/token/internal \
  -H "Content-Type: application/json" \
  -d '{"service":"checkout-orchestrator-service"}' | jq -r '.token')

# Request optimal rider assignment
curl -X POST http://dispatch-optimizer-service:8083/api/v1/assignments \
  -H "X-Internal-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId":"ord-12345",
    "itemCount":3,
    "sourceWarehouseId":"wh-sf-01",
    "destLatitude":37.7749,
    "destLongitude":-122.4194,
    "deliveryWindow":"16:00-18:00",
    "skillsRequired":["fragile_handling"]
  }'

# Get current rider capacity
curl -X GET http://dispatch-optimizer-service:8083/api/v1/riders/capacity \
  -H "X-Internal-Token: $TOKEN"

# Trigger rebalancing to optimize all active routes
curl -X POST http://dispatch-optimizer-service:8083/api/v1/rebalance \
  -H "X-Internal-Token: $TOKEN"
```

## Configuration

### Environment Variables
```env
PORT=8083

# Location and Fleet Services
LOCATION_SERVICE_URL=http://location-ingestion-service:8087
RIDER_FLEET_SERVICE_URL=http://rider-fleet-service:8085
WAREHOUSE_SERVICE_URL=http://warehouse-service:8088
SERVICE_CALL_TIMEOUT_SECONDS=3

# Distance Calculation
EARTH_RADIUS_KM=6371.0 (for Haversine formula)
DISTANCE_PRECISION_METERS=10

# Optimization Algorithm
OPTIMIZATION_TIMEOUT_MILLISECONDS=500
OPTIMIZATION_SOLVER=constraint_satisfaction (cp or csp library)
MAX_ASSIGNMENTS_PER_RIDER=10
RIDER_REBALANCE_THRESHOLD=0.05 (rebalance if improvement > 5%)

# Cost Weighting (for multi-objective optimization)
COST_WEIGHT_DISTANCE=0.6
COST_WEIGHT_TIME=0.3
COST_WEIGHT_SKILL_MISMATCH=0.1

# Caching
LOCATION_CACHE_TTL_SECONDS=30
RIDER_CACHE_TTL_SECONDS=60
OPTIMIZATION_CACHE_TTL_SECONDS=300
CACHE_MAX_ENTRIES=10000

# Redis (for location caching)
REDIS_URL=redis://redis-primary.cache:6379
REDIS_PASSWORD=<from kubernetes secret>

INTERNAL_TOKEN_SECRET=<shared secret from kubernetes secret>
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
OTEL_SERVICE_NAME=dispatch-optimizer-service
```

### dispatch.env (Go Configuration)
```env
# Optimization Parameters
DISPATCH_OPTIMIZATION_ENABLED=true
DISPATCH_DRY_RUN_ENABLED=false

# Distance Calculation
DISPATCH_HAVERSINE_PRECISION=HIGH (LOW|MEDIUM|HIGH)

# Logging
LOG_LEVEL=INFO
LOG_FORMAT=json (for structured logging)
```

## Monitoring & Alerts

### Key Metrics
- `dispatch_assignment_duration_milliseconds` (histogram) - End-to-end optimization time
- `dispatch_distance_calculations_total` (counter) - Haversine calculations performed
- `dispatch_assignments_total` (counter) - Assignments generated per strategy
- `dispatch_rider_capacity_utilization` (gauge) - Average rider utilization %
- `dispatch_optimization_quality_cost_ratio` (gauge) - Achieved cost vs theoretical minimum
- `dispatch_rebalance_improvement_percent` (histogram) - Improvement from rebalancing
- `go_goroutines` - Active goroutines (optimization workers)
- `process_resident_memory_bytes` - Memory usage

### Alerting Rules
- `dispatch_assignment_duration_p99 > 2000ms` - Optimization latency SLO breach (SEV-2)
- `dispatch_optimization_timeout_rate > 1%` - Solver timeout increasing (review constraints)
- `dispatch_rider_capacity_utilization > 95%` - Insufficient rider capacity (ops decision point)
- `dispatch_service_call_failures > 5/min` - Downstream service failures (check location-service, rider-fleet)
- `dispatch_assignment_error_rate > 0.5%` - Assignment validation failures (investigate constraints)

### Logging
- **ERROR**: Optimization failures, constraint violations, service call timeouts
- **WARN**: Solver timeouts, high rebalance rates, capacity warnings
- **INFO**: Assignments completed, rebalancing triggered, metrics snapshots
- **DEBUG**: Individual constraint evaluations, distance matrix computations (testing only)

## Security Considerations

### Threat Mitigations
1. **Token Scoping**: X-Internal-Token scoped to dispatch-optimizer-service (not shareable across services)
2. **Input Validation**: All location coordinates validated (latitude [-90, 90], longitude [-180, 180])
3. **Rate Limiting**: Per-rider assignment rate limited to prevent abuse (10 assignments/minute)
4. **Encrypted Caches**: Redis connections use TLS 1.3, cached locations are location data only
5. **Audit Logging**: All assignments logged with rider ID, order ID, cost, and timestamp

### Known Risks
- **Location data staleness**: 30-second cache may cause suboptimal assignments during rapid movement
- **Constraint solver failures**: Complex constraints may cause timeouts (mitigated by 500ms timeout)
- **Rider availability race condition**: Rider may accept assignment before fleet-service reflects status (rare edge case)

## Troubleshooting

### High Assignment Latency (p99 > 2s)
1. **Check solver timeout rate**: Monitor `dispatch_optimization_timeout_rate` (should be < 0.1%)
2. **Reduce constraint complexity**: Disable non-critical constraints (skill matching, time windows)
3. **Scale horizontally**: Increase HPA replica count if CPU > 70%
4. **Profile solver**: Set `LOG_LEVEL=DEBUG` for single request to see constraint evaluation time

### Inaccurate Assignments
1. **Verify distance calculations**: Manual verification: `curl -X POST .../api/v1/debug/distance`
2. **Check rider location staleness**: Compare `lastLocationUpdate` timestamps (should be < 30s)
3. **Review constraints**: Validate rider capacity, skill requirements, time windows
4. **Compare against manual assignment**: Run optimization in dry-run mode (`DISPATCH_DRY_RUN_ENABLED=true`)

### Service Call Failures
1. **Verify dependent services**: `curl http://location-ingestion-service:8087/health`
2. **Check network policies**: Ensure fulfillment namespace can reach location-service and rider-fleet
3. **Increase timeout**: Adjust `SERVICE_CALL_TIMEOUT_SECONDS` if services are slow

## Advanced Optimization Algorithms

### Constraint Satisfaction Problem (CSP) Formulation

**Variables**:
```
For each order (o) and rider (r):
  assignment[o,r] ∈ {0, 1}  (0=not assigned, 1=assigned)
```

**Constraints**:
```
1. Assignment uniqueness:
   ∑(r) assignment[o,r] = 1 for each order o
   (Each order assigned to exactly 1 rider)

2. Rider capacity:
   ∑(o) items[o] * assignment[o,r] ≤ capacity[r] for each rider r
   (Total items assigned ≤ rider capacity)

3. Time window:
   arrival_time[o,r] ≤ delivery_window_end[o] for each assigned order o
   (Must deliver within customer window)

4. Skill matching:
   skills_required[o] ⊆ skills_available[r] for each assigned order o
   (Rider must have all required skills)

5. Precedence:
   If order A before order B in route:
     arrival_time[A,r] + service_time[A] ≤ arrival_time[B,r]
```

**Objective Function** (minimize):
```
Total Cost =
  w_distance * ∑(routes) distance_traveled +
  w_time * ∑(routes) total_delivery_time +
  w_skill * ∑(orders) skill_mismatch_penalty +
  w_capacity * ∑(riders) capacity_violation_penalty

Typical weights:
  w_distance = 0.6 (cost per km)
  w_time = 0.3 (cost per minute)
  w_skill = 0.1 (penalty for suboptimal skill match)
```

### Solver Algorithm

**Algorithm Choice**: Mixed Integer Programming (MIP) with heuristics

```
Phase 1: Heuristic Construction (< 100ms)
  - Greedy assignment: Each order → nearest available rider
  - Result: Feasible solution (not optimal)

Phase 2: Local Search (< 200ms)
  - 2-opt neighborhood: Try swapping assignments
  - 3-opt: Try moving orders between routes
  - Tabu search: Prevent cycling

Phase 3: Branching (remaining time up to 500ms)
  - If time available: MIP solver for exact optimization
  - Branch & bound: Prune suboptimal branches
  - Fallback: Use best heuristic solution if timeout

Total timeout: 500ms (hard limit for checkout flow)
```

### Haversine Distance Calculation

**Formula**:
```
a = sin²(Δφ/2) + cos(φ1) * cos(φ2) * sin²(Δλ/2)
c = 2 * atan2(√a, √(1−a))
d = R * c

Where:
  φ = latitude, λ = longitude
  R = Earth's radius ≈ 6,371 km
  Δφ = difference in latitude
  Δλ = difference in longitude

Precision: ±10 meters (sufficient for delivery routing)
```

**Optimization**:
- Precompute distance matrix for each optimization request
- Cache rider↔warehouse distances (slow-changing)
- Use squared distances for comparison (avoid sqrt)

### Rebalancing Strategy

**When to rebalance**:
```
Trigger conditions:
1. New order arrives with very long ETA
   IF calculated_eta > previous_avg * 1.5:
     Run optimization immediately

2. Periodic rebalancing
   Run optimization every 5 minutes
   If improvement > 5%: Apply new assignments

3. Rider location update
   IF rider moved > 5 km from last known position:
     Recalculate assignments for affected routes
```

**Improvement Calculation**:
```
Current cost = ∑(routes) cost(route_i)
New cost (proposed) = ∑(routes) cost(route_i')

Improvement % = (Current - New) / Current * 100

If improvement > 5%: Apply rebalancing
Otherwise: Keep current assignments (avoid churn)
```

## Production Deployment Patterns

### Load Testing & Capacity Planning

**Load Profile**:
```
Baseline (off-peak): 1,000 assignments/minute
Normal (business hours): 3,000 assignments/minute
Peak (7-9 PM): 5,000 assignments/minute
Surge capacity: 8,000 assignments/minute

Service sizing:
- 4 pods baseline (handle 5,000/min = 1,250/pod)
- HPA scales to 8 pods at peak (1,000-1,250/pod each)
- Latency p99: < 2s (500ms optimization + 1.5s margin)
```

**Metrics under load**:
```
CPU utilization: 60-70% (headroom for spikes)
Memory usage: 800-900MB (model data + request caches)
Solver timeout rate: < 0.1% (most requests complete within 500ms)
Assignment success rate: > 99.9% (rare feasibility failures)
```

## Advanced Troubleshooting

### Scenario 1: Optimization Timeouts Spike (> 1%)

**Indicators**:
- dispatch_optimization_timeout_rate alert triggered
- Some orders taking > 2s for assignment

**Root causes**:
```
1. Constraint complexity increased
   - Many riders with specific skills
   - Many time windows (narrow windows = harder to satisfy)
   - Rider capacity constraints very tight

2. Solver not converging
   - MIP solver hitting branch limit
   - Heuristic search stuck in local optimum

3. Resource contention
   - CPU throttled on pod
   - Memory pressure (GC pauses)
```

**Diagnosis**:
```bash
# Check solver statistics
curl http://dispatch-optimizer-service:8083/actuator/metrics/dispatch_optimization_duration_ms \
  | jq '.[] | select(.percentile == "0.99")'
# If > 500ms: Solver taking too long

# Check timeout rate by reason
SELECT constraint_type, count(*)
FROM solver_timeouts
WHERE timestamp > NOW() - INTERVAL '1 hour'
GROUP BY constraint_type
ORDER BY count DESC;
```

**Resolution**:
```
Option 1: Relax constraints (short-term)
  - Increase time window tolerance (+15 min buffer)
  - Allow skill mismatches (rate penalty only)
  - Soften capacity constraints (allow overbooked + penalty)

Option 2: Increase timeout (risky)
  - Raise from 500ms → 1000ms
  - May impact checkout latency (not acceptable)

Option 3: Scale horizontally (long-term)
  - Add 2 more pods (8 → 10)
  - Reduces load per solver instance
  - Better concurrency
```

### Scenario 2: Suboptimal Assignments (Cost 15% worse than baseline)

**Indicators**:
- dispatch_optimization_quality_cost_ratio > 1.15
- Average delivery distance increased

**Root causes**:
```
1. Solver timeout kicking in frequently
   - Heuristic-only solutions (not MIP optimized)

2. Rider data stale
   - Location cache outdated (using > 1 min old location)
   - Capacity estimate wrong

3. Weight imbalance in objective function
   - w_distance too low (not prioritizing efficiency)
   - w_skill too high (avoiding optimal but lower-skill matches)
```

**Diagnosis**:
```bash
# Compare current vs baseline weights
echo "Current: w_distance=$(env | grep w_distance)"
echo "Baseline: w_distance=0.6"

# Check solver timeout rate
curl http://dispatch-optimizer-service:8083/actuator/metrics/dispatch_optimization_timeout_rate

# Analyze assignment quality
SELECT
  assignment_count,
  avg_distance_km,
  avg_delivery_time_min,
  quality_ratio
FROM dispatch_metrics
WHERE date = TODAY()
ORDER BY hour;
```

**Resolution**:
```
If timeout rate high:
  - Scale horizontally (add pods)

If weights imbalanced:
  - Review w_distance, w_time, w_skill
  - Align with business priorities
  - Restart service with updated weights

If rider data stale:
  - Reduce location cache TTL from 60s → 30s
  - Verify location-service is responsive
```

## Production Runbook Patterns

### Runbook 1: Surge Pricing Activation (Rider Shortage)

**Scenario**: Peak hour demand exceeds rider capacity by 20%

1. **Detection** (< 5 min):
   - Monitor: unassigned_order_count > 100
   - Alert: dispatch_rider_capacity_utilization > 95%

2. **Decision** (Checkout-Orchestrator or Ops):
   - Activate surge pricing: +15% delivery fee
   - Reduce order acceptance rate to 80%

3. **Recovery**:
   - More riders come online (incentivized by surge fee)
   - 15-30 min: Supply normalizes
   - Surge pricing removed

### Runbook 2: Emergency Manual Assignment (System Down)

**Scenario**: Dispatch optimizer pod crashed, need to manually assign

**Recovery** (< 10 min):
```bash
# Step 1: Fetch unassigned orders
SELECT COUNT(*) FROM orders WHERE assignment_id IS NULL;

# Step 2: Fetch available riders
SELECT * FROM riders WHERE status = 'ACTIVE' AND available_capacity > 0;

# Step 3: Manual CLI assignment (admin tool)
./dispatch-cli assign-order \
  --order-id ord-12345 \
  --rider-id rider-123 \
  --reason "SYSTEM_DOWN_MANUAL"

# Step 4: Restart dispatch pod
kubectl rollout restart deployment/dispatch-optimizer-service

# Step 5: Resume automated assignments
# All new orders will use optimizer again
```

## Related Documentation

- **Wave-37**: Integration tests for dispatch optimization algorithm
- **ADR-007**: Constraint Satisfaction Problem Formulation
- **ADR-008**: Multi-Objective Optimization Strategy
- **HLD**: Dispatch optimizer architecture and algorithm design
- **LLD**: Haversine distance calculation and optimization solver implementation
- **Runbook**: dispatch-optimizer-service/runbook.md
- **Algorithm Paper**: Multi-objective constraint satisfaction optimization
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Optimization Algorithm Pseudocode](algorithm-pseudocode.md)
