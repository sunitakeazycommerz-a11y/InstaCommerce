# ADR-013: Feature Flag Cache Invalidation (Redis Pub/Sub)

## Status
Accepted

## Date
2026-03-21

## Context
The feature flag service uses Caffeine for in-memory L1 caching with a 30-second TTL.
In a Kubernetes deployment with multiple replicas, this creates a problematic stale flag window:
- Pod A receives flag update, evicts Caffeine cache immediately
- Pod B is unaware of the change, continues serving stale flag for up to 30 seconds
- This violates SLAs for feature control, especially for critical flags (payment, fraud detection)

Current state (Wave 33):
- Caffeine-only cache with 30s TTL
- No cross-pod synchronization
- Manual workaround: emergency force-disable via API (30s delay)

## Decision
Implement a two-tier cache with Redis pub/sub for cluster-wide invalidation:

1. **Tier 1 (L1)**: Caffeine - local in-process cache with 30s TTL
2. **Tier 2 (L2 sync)**: Redis pub/sub for cluster-wide invalidation signals

When a flag is updated on any pod:
- FlagManagementService evicts local Caffeine entry (via @CacheEvict)
- FlagCacheEventListener publishes to Redis topic: `flag-updates:{flagKey}`
- All pods' FlagCacheInvalidators subscribe to `flag-updates:*` pattern
- On message receipt: invalidate corresponding Caffeine entry in <100ms

Fallback: If Redis is unavailable >5 seconds:
- Circuit breaker opens
- Service continues with Caffeine-only (30s stale window)
- Admin warned via logs
- On next restart, subscriptions re-enabled

## Architecture

### Components
- **FlagCacheEventListener**: Publishes flag updates to Redis topics
  - Channels: `flag-updates:{flagKey}` (single-flag), `flag-updates:all` (bulk)
  - Payload: JSON with {flagId, flagKey, value, timestamp, bulkUpdate}

- **FlagCacheInvalidator**: Subscribes to Redis, invalidates Caffeine
  - Metrics: invalidations (counter), redis.failures (counter), staleness_window (gauge)
  - Circuit breaker: Stops subscribing after 5s+ consecutive failures
  - Lifecycle: Subscribes on ApplicationReadyEvent

- **FlagManagementService**: Integrates publisher calls
  - @CacheEvict on flag updates
  - publishFlagUpdate() for single-flag events
  - publishBulkUpdate() for override adds/removes

## Consequences
- **Positive**:
  - Cross-pod cache propagation <500ms (vs 30s stale window)
  - Graceful fallback if Redis unavailable
  - Observability: cache metrics + staleness gauge
  - No architectural changes to flag evaluation logic

- **Negative**:
  - Redis dependency (adds operational complexity)
  - Network latency if Redis is remote (mitigated by Kubernetes locality)
  - Circuit breaker requires manual pod restart to re-enable (acceptable trade-off)

## SLO
- Cache invalidation propagation: <500ms (99.9th percentile)
- Redis pub/sub latency: <100ms per message
- Fallback time (Redis discovery failure): <5s

## Monitoring
- `feature_flag.cache.invalidations` (counter) - total messages processed
- `feature_flag.cache.redis.failures` (counter) - Redis connectivity failures
- `feature_flag.cache.staleness_window_seconds` (gauge) - max observed staleness (should be <1s post-deployment)
- Logs: circuit breaker state transitions, subscription errors

## Deployment
- Helm values.yaml: REDIS_HOST, REDIS_PORT, FEATURE_FLAG_REDIS_ENABLED
- Local dev: `docker-compose up redis`
- Requires: redis-cluster in cluster or managed Redis (GCP Memorystore)

## References
- Wave 31 Track F: Previous feature flag cache fix (Caffeine-only, incomplete)
- ADR-004: Event envelope pattern (related pub/sub architecture)
