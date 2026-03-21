# InstaCommerce Service Documentation Index

## Overview

This directory contains comprehensive service documentation organized by service cluster. Each cluster represents a related set of microservices working together to deliver a specific domain capability.

**Last Updated**: 2026-03-21
**Status**: Phase 1 (Service Documentation) in progress
**Quality Gate**: Principal engineer review required before merge

---

## Service Clusters

### Cluster 1: Identity & Authentication (`identity-cluster/`)

**Services**:
- `identity-service` (Java/Spring Boot 4.0, Port 8080, PostgreSQL)
- `admin-gateway-service` (Java/Spring Boot 4.0, Port 8080, stateless)

**Responsibilities**:
- User credential management (registration, login, password reset)
- JWT token lifecycle (issue, refresh, revoke, GDPR erasure)
- Rate limiting per IP (login: 5 req/min, register: 3 req/min)
- Administrative API gateway with circuit breaker

**Documentation Files**:
- `README.md` — Cluster overview, deployment model, API contracts
- `identity-service-detailed.md` — Identity service deep dive (HLD, LLD, flows, database)
- `diagrams.md` — Mermaid diagrams (system context, trust boundaries, flows, ERD)
- `runbook.md` — Deployment procedures, troubleshooting, operations

**Key Metrics**:
```
auth.login.total{result=success|failure}
auth.refresh.total
auth.register.total
http.server.requests
hikaricp.connections.max
```

**Deployment Checklist**:
- [ ] Flyway migrations validated
- [ ] JWKS endpoint reachable
- [ ] Rate limit thresholds tuned
- [ ] Database backup created
- [ ] Readiness probes return 200

---

### Cluster 2: BFF & Real-Time Logistics (`bff-routing-dispatch-cluster/`)

**Services**:
- `mobile-bff-service` (Java/Spring Boot WebFlux 4.0, Port 8080, reactive)
- `routing-eta-service` (Java/Spring Boot 4.0, Port 8080, ETA calculation)
- `dispatch-optimizer-service` (Go 1.24, Port 8080, ML-backed assignment)

**Responsibilities**:
- Mobile/web API aggregation with response caching
- Real-time delivery ETA estimation (distance + traffic)
- Rider assignment optimization (load balancing + rebalancing)
- Location-based queries (geo-indexing via Redis)

**Documentation Files**:
- `README.md` — Cluster overview, deployment model, API contracts

**Key Metrics**:
```
mobile_bff_requests_total
cache_hit_ratio
circuit_breaker_calls_total
routing_eta_calculation_ms
dispatch_optimization_ms
```

**Deployment Checklist**:
- [ ] Redis cluster reachable
- [ ] Kafka location topics exist
- [ ] Circuit breaker thresholds validated
- [ ] Cache TTLs appropriate for traffic patterns
- [ ] Dispatch solver performance regression tested

---

### Cluster 3: Observability & Analytics (`audit-cdc-cluster/`)

**Services**:
- `audit-trail-service` (Java/Spring Boot 4.0, Port 8080, immutable audit log)
- `cdc-consumer-service` (Go 1.24, Port 8080, CDC → BigQuery)

**Responsibilities**:
- Centralized audit log (14 Kafka topics → PostgreSQL partitioned table)
- Compliance-grade event search and CSV export
- Debezium CDC logical decoding (PostgreSQL WAL → BigQuery data lake)
- Dead-letter queue routing for failed events

**Documentation Files**:
- `README.md` — Cluster overview, database schema, Kafka topics, API

**Key Metrics**:
```
audit_events_ingested_total
kafka_consumer_lag
cdc_batch_latency_seconds
cdc_dlq_total
```

**Deployment Checklist**:
- [ ] PostgreSQL range partitioning enabled
- [ ] Debezium CDC connector deployed
- [ ] BigQuery dataset & table schema match
- [ ] Retention policies validated (365 days)
- [ ] All 14 domain topics subscribed

---

### Cluster 4: Event Plane & Real-Time Metrics (`outbox-stream-reconciliation-cluster/`)

**Services**:
- `outbox-relay-service` (Go 1.24, Port 8080, reliable event publisher)
- `stream-processor-service` (Go 1.24, Port 8080, real-time aggregation)
- `reconciliation-engine` (Go 1.24, Port 8080, financial ledger reconciliation)

**Responsibilities**:
- Transactional outbox pattern (PostgreSQL → Kafka via idempotent producer)
- Real-time operational metrics (orders, payments, riders, inventory)
- SLA monitoring with sliding-window aggregation (30-min, 90% threshold)
- Financial reconciliation and anomaly detection

**Documentation Files**:
- `README.md` — Cluster overview, event publishing, Kafka routing, metrics schema
- `runbook.md` — Deployment procedures, failure scenarios, performance tuning

**Key Metrics**:
```
outbox.relay.count
outbox.relay.lag.seconds
orders_total
sla_compliance_ratio
payment_success_rate
inventory_cascade_alerts_total
```

**Deployment Checklist**:
- [ ] PostgreSQL `outbox_events` table created on all 13 producer services
- [ ] Partial index on `(created_at) WHERE sent=false` exists
- [ ] Kafka topics for all 13 aggregates exist (orders.events, etc.)
- [ ] Redis cluster reachable
- [ ] SLA thresholds configured per zone

---

## Documentation Structure

### Per-Service Template

Each service cluster directory includes:

```
cluster-name/
├── README.md                           # 1. Cluster overview
│   ├─ Service responsibilities
│   ├─ Architecture diagram
│   ├─ Deployment model
│   ├─ API contracts (endpoints, request/response)
│   ├─ Technology stack
│   ├─ Failure modes & recovery
│   └─ Known limitations & roadmap
│
├── diagrams.md                         # 2. Mermaid diagrams (optional)
│   ├─ System context diagram
│   ├─ Trust boundaries & security layers
│   ├─ Deployment topology (Kubernetes)
│   ├─ Request flow (sequence diagram)
│   ├─ Data model (ER diagram)
│   ├─ Component interactions
│   └─ Authentication filter chain
│
├── {service}-detailed.md               # 3. Service deep dive (optional)
│   ├─ HLD (High-Level Design)
│   ├─ LLD (Low-Level Design)
│   ├─ Component architecture
│   ├─ Database schema
│   ├─ Key flows (login, token refresh, GDPR erasure)
│   ├─ API reference (all endpoints)
│   ├─ Configuration (env vars, connection pools)
│   └─ Observability (metrics, logging, alerts)
│
└── runbook.md                          # 4. Operations guide
    ├─ Pre-deployment checklist
    ├─ Deployment procedures (rolling, blue-green, rollback)
    ├─ Health check procedures
    ├─ Troubleshooting guide
    ├─ Scaling operations
    ├─ Database maintenance
    ├─ Monitoring & alerting
    └─ Incident response playbook
```

---

## Reading Guide for Different Roles

### 👨‍💼 Engineering Manager

1. Start with: **Cluster README.md**
   - Understand high-level responsibilities
   - Review deployment model & replicas
   - Check known limitations & roadmap

2. Review: **runbook.md** → Failure Modes section
   - Understand operational risks
   - Review incident response procedures

3. Reference: **Deployment Checklist**
   - Ensure pre-req validation before deploys

---

### 👨‍💻 Backend Engineer (New to Service)

1. Start with: **README.md**
   - Service responsibilities & boundaries
   - Technology stack & versions

2. Review: **diagrams.md**
   - System context (how it fits in ecosystem)
   - Architecture diagram (components)
   - Trust boundaries (security model)

3. Deep dive: **{service}-detailed.md**
   - Understand HLD/LLD
   - Review database schema & migrations
   - Study request flows (login, token refresh, etc.)

4. Reference: **API Reference** section
   - Endpoint contracts
   - Request/response examples

---

### 🔧 DevOps / SRE

1. Start with: **runbook.md**
   - Pre-deployment checklist
   - Deployment procedures (rolling, rollback)
   - Health checks & monitoring

2. Review: **README.md** → Configuration section
   - Environment variables
   - Connection pools
   - Resource requests/limits

3. Reference: **Failure Modes** section
   - How to detect issues
   - Recovery procedures
   - Scaling operations

---

### 🔒 Security Engineer

1. Review: **diagrams.md** → Trust Boundaries section
   - Understand security layers (TLS, mTLS, JWT, firewalls)
   - Verify cryptographic controls

2. Review: **Security & Trust Boundaries** section (in detailed docs)
   - Authentication/authorization mechanisms
   - Rate limiting & account lockout
   - GDPR compliance (erasure flows)

3. Check: **Known Limitations** section
   - Security gaps & remediation roadmap
   - ADR references for cryptographic decisions

---

## Quality Checklist

### ✅ Before Merging Documentation

- [ ] All Mermaid diagrams render without errors
- [ ] Database schemas match Flyway migrations (or documented as intentional)
- [ ] API endpoints match actual code (run `grep -r "GET\|POST\|PUT\|DELETE"`)
- [ ] Kafka topics match `TopicNames.java` or code definitions
- [ ] Configuration examples use real environment variable names
- [ ] No broken internal links (check references to `/services/...`)
- [ ] Runbook procedures tested on staging environment
- [ ] Screenshots or artifacts linked correctly

### ✅ Principal Engineer Review

- [ ] Architecture decisions documented with ADR references
- [ ] Known security gaps listed with severity & remediation path
- [ ] Scalability assumptions validated (replicas, HPA, resource limits)
- [ ] Dependency graph accurate (external APIs, databases, message queues)
- [ ] Monitoring & alerting strategy appropriate for service SLA
- [ ] Incident response procedures match on-call runbook
- [ ] Roadmap (Wave items) aligned with platform strategy

---

## Related Documentation

### Architecture & Design Decisions

- [System Architecture (HLD)](/docs/architecture/HLD.md) — Platform-level design
- [Infrastructure](/docs/architecture/INFRASTRUCTURE.md) — GCP/GKE/Istio setup
- [Data Flow](/docs/architecture/DATA-FLOW.md) — Event pipeline & analytics
- [Architectural Decision Records (ADRs)](/docs/adr/README.md) — Design rationale

### Platform Governance

- [Principal Engineering Review (Iter 3)](/docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-2026-03-06.md)
- [Service Guide Outline](/docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-SERVICE-GUIDE-OUTLINE.md)
- [Security & Trust Boundaries](/docs/reviews/iter3/platform/security-trust-boundaries.md)
- [Testing & Quality Governance](/docs/reviews/iter3/platform/testing-quality-governance.md)

### Wave Implementation Tracking

- [Wave History](/docs/wave-history.md) — All completed waves
- [Wave 33 Completion Status](/docs/architecture/WAVE-30-IMPLEMENTATION.md) — Current wave

---

## Contributing

### Adding a New Service Cluster

1. Create directory: `docs/services/{cluster-name}/`

2. Start with template files:
   - `README.md` (required) — Overview & API contracts
   - `diagrams.md` (recommended) — Mermaid visualizations
   - `{service}-detailed.md` (optional) — Deep technical dive
   - `runbook.md` (recommended) — Operations procedures

3. Link from this index in appropriate section

4. Submit PR with label `documentation`

5. Principal engineer review required before merge

### Validation Script

```bash
#!/bin/bash
# Validate documentation

# 1. Check for broken links
grep -r "\[.*\](/" docs/services/ | grep -v "http" | while read line; do
  link=$(echo "$line" | grep -oP '\(/[^)]+' | tr -d '(')
  if [ ! -f ".${link}" ] && [ ! -d ".${link}" ]; then
    echo "BROKEN LINK: $link"
  fi
done

# 2. Check for code blocks with no language specified
grep -E "^\`\`\`$" docs/services/ && echo "WARNING: Code blocks missing language spec"

# 3. Validate Mermaid syntax (requires mermaid-cli)
# grep -r "^\\`\\`\\`mermaid" docs/services/ | while read line; do
#   file=$(echo "$line" | cut -d: -f1)
#   npx mmdc -i "$file" -o /tmp/test.svg 2>&1 | grep "error" && echo "INVALID MERMAID: $file"
# done
```

---

## Support & Questions

- **Documentation issues**: Create GitHub issue with label `docs`
- **Service-specific questions**: Check runbook; escalate to on-call engineer
- **Architecture questions**: Refer to `/docs/architecture/` or ADRs
- **Access issues**: Contact platform team (see CODEOWNERS)

---

## Document Versions

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-03-21 | Platform Team | Initial documentation (4 clusters) |

---

**Navigation**: [Home](/docs/README.md) | [Services](/docs/services/) | [Architecture](/docs/architecture/)
