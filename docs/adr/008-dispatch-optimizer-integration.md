# ADR-008: Dispatch Optimizer Integration Pattern

## Status

Accepted

## Date

2026-03-13

## Context

The InstaCommerce platform includes a `dispatch-optimizer-service` that implements a
multi-objective solver (v2) capable of optimizing rider assignments across multiple
dimensions: SLA compliance, rider idle time, and estimated delivery time. However, no
service currently calls it.

The existing assignment logic in `rider-fleet-service` uses a simple Haversine
nearest-neighbor algorithm that selects the geographically closest available rider.
While functional, this approach does not account for:

1. **SLA deadlines**: A closer rider may not be able to complete the delivery within
   the order's SLA window, while a slightly farther rider with a better route could.

2. **Rider idle time fairness**: The nearest-neighbor approach can starve riders in
   lower-demand zones while overloading riders near hotspots.

3. **Multi-order batching**: The Haversine algorithm evaluates one order at a time
   and cannot optimize across a batch of pending assignments.

ADR-002 designates `rider-fleet-service` as the sole dispatch authority for the
platform. Any optimizer integration must therefore flow through `rider-fleet-service`
rather than bypassing it.

## Decision

**`rider-fleet-service` MUST integrate with `dispatch-optimizer-service` as the
preferred assignment strategy, with Haversine nearest-neighbor as the fallback.**

Implementation requirements:

1. `rider-fleet-service` MUST call `dispatch-optimizer-service` via
   `POST /v2/optimize/assign` when making assignment decisions. The request payload
   includes the order's pickup/dropoff coordinates, SLA deadline, and a list of
   candidate rider positions.

2. The integration MUST be gated behind a feature flag:
   `rider-fleet.dispatch.optimizer-enabled` (default: `false`). When the flag is
   disabled, the service MUST use the existing Haversine nearest-available query
   without attempting to contact the optimizer.

3. A circuit breaker MUST wrap all optimizer HTTP calls with the following
   configuration:
   - **Timeout**: 5 seconds per request.
   - **Failure threshold**: 50% failure rate over the sliding window.
   - **Half-open duration**: 30 seconds before retrying after the circuit opens.

4. On optimizer failure (HTTP error, timeout, or open circuit breaker) or when the
   feature flag is disabled, `rider-fleet-service` MUST fall back to the existing
   Haversine nearest-available query. The fallback MUST be logged at `WARN` level
   with the reason (e.g., `optimizer-timeout`, `circuit-open`, `flag-disabled`).

5. `dispatch-optimizer-service` MUST remain stateless and horizontally scalable. It
   MUST NOT maintain assignment state between requests; all necessary context is
   provided in the request payload.

## Consequences

### Positive

- Enables multi-objective optimization for rider assignment, balancing SLA compliance,
  rider idle time fairness, and estimated delivery time.
- Safe, incremental rollout via feature flag: the optimizer can be enabled per
  environment (staging first, then production) or even per region.
- Circuit breaker ensures that optimizer downtime does not cascade into assignment
  failures; the system gracefully degrades to the proven Haversine algorithm.

### Negative

- Adds a network hop and additional latency to the assignment path. Under normal
  conditions, the optimizer call adds 50-200ms compared to the local Haversine
  calculation.

### Risks

- Optimizer downtime increases assignment latency by up to the circuit breaker timeout
  (5 seconds) before the fallback activates. During this window, riders and customers
  experience degraded assignment responsiveness.
- If the optimizer returns suboptimal results due to stale rider positions, assignments
  may be worse than the simple Haversine approach. Monitoring must compare assignment
  quality metrics (SLA hit rate, delivery time) between the two strategies.
