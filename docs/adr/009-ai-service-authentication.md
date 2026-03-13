# ADR-009: AI Service Inbound Authentication

## Status
Accepted

## Date
2026-03-13

## Context
Both ai-orchestrator-service and ai-inference-service accept unauthenticated
requests on all endpoints. This allows any network-reachable client to invoke
inference and agent endpoints without identity verification.

The platform uses X-Internal-Service and X-Internal-Token headers with
constant-time comparison for service-to-service authentication across Java
(MessageDigest.isEqual) and Go (subtle.ConstantTimeCompare) services.

## Decision
Add ASGI middleware to both Python AI services that validates the same
X-Internal-Service / X-Internal-Token header protocol used by all other
platform services. Use hmac.compare_digest for constant-time comparison.

Health, readiness, liveness, and metrics endpoints are excluded from
authentication to preserve Kubernetes probe and Prometheus scrape
compatibility.

## Consequences
- All inference and agent endpoints require valid internal service credentials
- Existing callers already send these headers -- no client changes needed
- Future per-service token migration (ADR-006) applies equally to these services
- Prometheus /metrics and health probes remain unauthenticated
