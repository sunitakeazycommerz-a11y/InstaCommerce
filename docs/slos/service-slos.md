# InstaCommerce Service-Level Objectives

All SLOs measured on a **calendar month** basis. See `docs/adr/015-slo-error-budget-policy.md` for burn-rate alert thresholds.

## Critical Money Path

### Checkout Orchestrator Service
- **Availability**: 99.9% (< 8.6 min downtime/month)
- **Latency**: <2s p99 (POST /checkout orchestration call)
- **Error Rate**: <0.1% (non-5xx errors)
- **Timeout SLO**: 100% calls complete within timeout (no orphaned requests)
- **Context**: Owns end-to-end checkout flow; any downtime impacts revenue

### Order Service
- **Availability**: 99.9% (< 8.6 min downtime/month)
- **Latency**: <500ms p99 (GET /orders/{id}, POST /orders)
- **Error Rate**: <0.1% (invalid input errors excluded)
- **Idempotency**: 100% (no double-billing)
- **Context**: Core business entity; downstream services depend on reliable reads

### Payment Service
- **Availability**: 99.95% (< 2.2 min downtime/month)
- **Latency**: <300ms p99 (POST /process-payment)
- **Error Rate**: <0.05% (transaction failures only; validation errors <0.2%)
- **Webhook Delivery**: 99.99% (no lost payment confirmations)
- **Compliance**: PCI DSS audit trail complete (100%)
- **Context**: Highest criticality; controls money flow; zero tolerance for data loss

### Fulfillment Service
- **Availability**: 99.5% (< 43 min downtime/month)
- **Latency**: <2s p99 (POST /assign-rider, GET /fulfillment/{id})
- **Error Rate**: <0.5% (failed assignments, missing rider data)
- **ETA Accuracy**: 95% within ±15 minutes (if ETA provided)
- **Context**: Operational impact; affects customer experience but not revenue

## Read Path (Search & Catalog)

### Search Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <200ms p99 (GET /search with all filters)
- **Freshness**: <5 min (Elasticsearch index lag)
- **Cache Hit Rate**: >95% (p50 latency <50ms for popular queries)
- **Fallback**: Switches to Catalog Service if Search down (graceful degradation)
- **Context**: Core UX; users tolerate temporary search unavailability

### Catalog Service
- **Availability**: 99.5% (< 43 min downtime/month)
- **Latency**: <500ms p99 (GET /products, GET /categories)
- **Error Rate**: <0.1%
- **Freshness**: <10 min (product updates propagate to Search)
- **Context**: Source-of-truth for product data; all product reads go here

### Cart Service
- **Availability**: 99.8% (< 17 min downtime/month)
- **Latency**: <300ms p99 (GET /cart, POST /cart/add, DELETE /cart/items)
- **Error Rate**: <0.1%
- **Persistence**: 100% (cart never lost due to service failure)
- **Context**: High-traffic, stateful service; affects checkout path

### Pricing Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <100ms p99 (GET /price-for-sku)
- **Cache Hit Rate**: >98%
- **Accuracy**: 100% (price never wrong, affects revenue)
- **Context**: Read-heavy, cached; low criticality if stale

## Platform Services

### Identity Service
- **Availability**: 99.9% (< 8.6 min downtime/month)
- **Latency**: <100ms p99 (JWT validation, token generation)
- **Error Rate**: <0.1% (auth failures; invalid input <0.2%)
- **Token Freshness**: <30 seconds (per-service token updates propagate)
- **Context**: Auth gate for all requests; cascading impact if down

### Admin Gateway Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <500ms p99 (admin API calls)
- **Error Rate**: <0.1% (after auth, service errors only)
- **JWT Validation**: <5s p99 response (SLO-critical: see ADR-011)
- **Context**: Internal tool; not customer-facing; lower criticality

### Config Feature Flag Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <10ms p99 (local cache hit); <100ms p99 with network
- **Cache Hit Rate**: >95% (L1 Caffeine + L2 Redis)
- **Invalidation Latency**: <500ms (cross-pod propagation, see ADR-013)
- **Context**: Used by all services; high call volume but low per-call importance

### Audit Trail Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <100ms p99 (async event logging; no user-facing impact)
- **Durability**: 99.99% (audit events never lost; stored in PostgreSQL)
- **Queryability**: 100% (audit queries complete within 30s)
- **Context**: Compliance-critical; eventual consistency acceptable

### CDC Consumer Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <5s (change propagation lag; eventual consistency)
- **Error Rate**: <0.1% (message processing failures)
- **Durability**: 99.99% (Kafka offset management; no message loss)
- **Context**: Batch processing; order-independent; transient downtime acceptable

### Outbox Relay Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <2s (event relay lag from outbox table to Kafka)
- **Durability**: 99.99% (no events lost; PostgreSQL outbox is source-of-truth)
- **Error Rate**: <0.1% (non-retriable errors only)
- **Context**: Enables reliable event publishing; eventual consistency

### Stream Processor Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <10s (aggregate computation lag)
- **Exactly-once Semantics**: 100% (deduplication via Kafka consumer groups)
- **Data Completeness**: 100% (all Kafka messages processed)
- **Context**: Analytics & BI; eventual consistency acceptable

### Reconciliation Engine
- **Availability**: 99.5% (< 43 min downtime/month)
- **Latency**: <30s (daily reconciliation job must complete in <4 hours)
- **Accuracy**: 100% (financial reconciliation; zero tolerance for errors)
- **Auditability**: 100% (all reconciliation runs logged & queryable)
- **Context**: Financial compliance; most critical after payments

## Mobile & Engagement

### Mobile BFF Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <500ms p99 (aggregate API calls for mobile screens)
- **Error Rate**: <0.5% (network errors, timeouts excluded)
- **Cache Hit Rate**: >80% (request deduplication within 30s window)
- **Context**: Mobile user experience; eventual consistency acceptable

### Notification Service
- **Availability**: 98% (< 43 min downtime/month)
- **Latency**: <10s (notification delivery)
- **Delivery Rate**: 99% (best-effort; some loss acceptable)
- **Context**: Non-critical feature; users tolerate missed notifications

### Wallet Loyalty Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <500ms p99 (loyalty queries & updates)
- **Error Rate**: <0.1% (balance inconsistencies prevent)
- **Consistency**: Eventual (balance updates within 1 minute)
- **Context**: Feature; not part of critical checkout path

## Logistics & Dispatch

### Routing & ETA Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <2s p99 (route calculation)
- **Accuracy**: 90% within ±15 min ETA
- **Cache Hit Rate**: >85% (same routes cached)
- **Context**: Operational; high latency acceptable if accurate

### Dispatch Optimizer Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <5s p99 (optimization algorithm)
- **Optimality**: 80% (near-optimal solutions; not guaranteed optimal)
- **Context**: Batch processing; eventual consistency acceptable

### Warehouse Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <200ms p99 (stock queries)
- **Accuracy**: 99% (inventory counts match physical)
- **Freshness**: <2 min (stock updates propagate)
- **Context**: Operational; eventual consistency acceptable

### Rider Fleet Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <500ms p99 (rider status queries)
- **Availability (Rider App)**: 98% (mobile app uptime)
- **Context**: Operational; non-customer-facing

### Location Ingestion Service
- **Availability**: 98% (< 43 min downtime/month)
- **Latency**: <1s (location event ingestion)
- **Durability**: 99.99% (Kafka offset tracking; no loss)
- **Context**: Batch stream processing; eventual consistency

## Fraud Detection

### Fraud Detection Service
- **Availability**: 99% (< 14 min downtime/month)
- **Latency**: <200ms p99 (fraud scoring)
- **Accuracy**: >95% precision (minimize false positives)
- **Recall**: >90% (catch >90% of fraudulent txns)
- **Context**: High-importance but not blocking; can fail open (allow txn)

## Error Budget Allocation

**Monthly error budget** = (1 - Availability SLO) × monthly minutes

Examples:
- **Payment Service (99.95%)**: 2.2 minutes/month error budget
- **Order Service (99.9%)**: 8.6 minutes/month error budget
- **Feature Flag Service (99%)**: 14.4 minutes/month error budget
- **Warehouse Service (99%)**: 14.4 minutes/month error budget

**Burn rate thresholds** (trigger alerts; see ADR-015):
- **Fast burn (5 min)**: Error rate >10% → Page immediately (SEV-1)
- **Medium burn (30 min)**: Error rate >5% → Create incident ticket (SEV-2)
- **Slow burn (6 hour)**: Error rate >1% → SRE review ticket (SEV-3)

## SLO Review & Updates

- **Quarterly review**: Verify SLOs match team capacity & business priority
- **Change process**: ADR required for any SLO modification
- **Stakeholder approval**: Service owner + platform lead sign-off
- **Deployment**: Update in this document + Prometheus alert rules

## References

- `docs/adr/015-slo-error-budget-policy.md` - Burn-rate alerting policy
- `docs/observability/slo-alerts.md` - Prometheus alert rule definitions
- `docs/observability/grafana-slo-dashboard.json` - SLO dashboard template
- `.github/CODEOWNERS` - Service ownership assignments
- `docs/governance/OWNERSHIP_MODEL.md` - SLO monitoring responsibilities
