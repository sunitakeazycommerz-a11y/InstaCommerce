# Risks, Gaps, and Readiness

## Highest-Signal Gaps

### 1. Edge tier implementation & hardening status

The edge services have transitioned from basic scaffolds to hardened gateways:

- [`services/mobile-bff-service/README.md`](../../services/mobile-bff-service/README.md): Implements reactive WebFlux security, JWT validation, downstream user context propagation (`X-User-Id`), parallel fan-out via `HomeAggregator` to catalog, search, and feature-flags with resilience timeouts/fallbacks, covered by 13 unit/integration tests.
- [`services/admin-gateway-service/README.md`](../../services/admin-gateway-service/README.md): Implements Spring Security MVC filter chain with RS256 JWT validation, admin RBAC (`hasRole('ADMIN')`), downstream feature-flag and reconciliation proxying, covered by 21 unit/integration tests.

Remaining edge considerations:
- Further expanding screen aggregations (cart summary, product detail, checkout proxy) as client requirements evolve.
- Adding Istio mesh-level `RequestAuthentication` and `AuthorizationPolicy` overlays in production Helm values.

### 2. Some architecture docs are ahead of code

Examples:

- HLD references search in Elasticsearch, while the implemented search service uses PostgreSQL FTS
- Some HLD narrative implies rider gRPC streaming, while the location ingestion service currently exposes HTTP/WebSocket ingestion
- AI orchestration docs read as settled, but the service still contains both legacy and newer graph-based flows

Risk:

- onboarding, design reviews, and production-readiness decisions can be skewed if teams read target-state prose as current-state fact

### 3. Event plane is powerful but operationally fragile

The event plane is one of the strongest parts of the design, but it also carries systemic risk:

- outbox lag can delay many downstream behaviors
- schema changes can break heterogeneous consumers
- DLQ and replay posture matter across business, analytics, and audit flows

Failure mode examples:

- delayed outbox relay stalls notifications, search projections, and downstream analytics simultaneously
- CDC failures create silent freshness gaps in BigQuery and dbt marts
- poison events can create persistent lag or replay storms

### 4. AI stack is mid-transition

The orchestrator has both legacy and LangGraph-oriented flows.

Risk:

- split debugging path
- duplicated request handling logic
- policy/guardrail divergence between versions

### 5. Documentation sprawl is itself a maintenance cost

The repository already contains extensive material under `docs/`, service READMEs, architecture docs, reviews, ADRs, and older doc sets.

Risk:

- duplicated architectural statements drift apart
- service owners may update code without updating the right prose layer
- senior reviews spend time reconciling docs instead of reviewing systems

## Security Readiness Notes

Positive signals:

- pervasive JWT/security classes in major Java services
- mesh-level request-authentication and authorization policies in Helm
- internal service auth filters/interceptors
- webhook signature verification and dedupe in `payment-webhook-service`

Main security caveat:

- having both mesh-layer and app-layer auth is good defense-in-depth, but it increases drift risk if issuer/audience/header conventions diverge

## Observability Readiness Notes

Positive signals:

- Actuator and Prometheus patterns in Java services
- Prometheus and OTEL in Go services
- burn-rate alert rules in the monitoring config
- metrics surfaces in AI services

Gaps to watch:

- scaffold services can satisfy deploy shape without delivering meaningful traces or domain metrics
- dual AI orchestrator paths may fragment telemetry
- multi-hop event debugging still depends on consistent correlation IDs everywhere

## Rollback and Recovery Considerations

The repo has the pieces for controlled rollback, but the complexity varies by layer:

### Easier to roll back

- stateless Go services such as `dispatch-optimizer-service`
- isolated API-layer behavior changes
- some Helm value changes under GitOps control

### Harder to roll back

- contract/schema changes across async consumers
- payment, reconciliation, or ledger semantics
- outbox/event interpretation changes
- AI behavior changes that span prompt, tool, and model-version surfaces

The safest path for cross-cutting work in this repo is still small, reversible, per-plane change sets with validation at the narrowest module level first.

## Performance and Scaling Notes

Strong performance-aligned choices:

- Go for relay/streaming/webhook/optimization tasks
- Temporal for workflow durability rather than ad hoc retry orchestration
- PostgreSQL FTS for local search projection simplicity
- Redis-backed operational paths for low-latency state and counters

Likely long-term scaling pressure points:

- payment and order data growth
- audit trail partition and query cost
- Kafka topic growth and replay duration
- service-mesh policy sprawl
- CI matrix and deploy coordination overhead as the fleet expands

## Recommended Operator Reading Path

If the goal is to understand the system quickly without losing the distinction between reality and aspiration, use this order:

1. [`README.md`](README.md)
2. [`01-repo-truth.md`](01-repo-truth.md)
3. [`02-service-fleet.md`](02-service-fleet.md)
4. [`03-data-ml-platform.md`](03-data-ml-platform.md)
5. The canonical repo docs under [`../docs/README.md`](../README.md) for deeper source material

## Bottom Line

This repository is not a toy architecture dump. The transactional core, event plane, data platform, and infra scaffolding are materially implemented. The biggest caution is not lack of ambition; it is uneven completion and documentation drift.

The strongest mental model is:

- the middle of the system is more real than the edges
- the async/data/ops plane is a first-class architecture element
- some high-level documents describe the destination, while the code shows a partially completed route to that destination
