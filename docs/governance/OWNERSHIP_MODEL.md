# InstaCommerce Service Ownership Model

## Governance Structure

### Weekly Service Ownership Review (30 min)
- **Attendees**: Service owner, EM, on-call SRE
- **Cadence**: Every Monday 9 AM UTC
- **Topics**: Critical PRs, failed CI, P0/P1 issues, deployment readiness
- **Output**: Decision log (GitHub issue template: Service Ownership Review)
- **Escalation**: CODEOWNERS approval required for any service change
- **Meeting Link**: [TBD - add calendar link during implementation]

### Biweekly Contract Review (45 min)
- **Attendees**: Service owners, data platform, principal engineer
- **Cadence**: Every other Wednesday 2 PM UTC
- **Topics**: Schema changes, breaking changes, new event types, API contracts
- **Output**: Contract change log (GitHub discussions)
- **Gate**: ADR required for any breaking change
- **Meeting Link**: [TBD - add calendar link during implementation]

### Monthly Reliability + Governance Review (60 min)
- **Attendees**: Platform, SRE, EM leads, security team
- **Cadence**: First Friday of month 10 AM UTC
- **Topics**: SLO breach analysis, burn-rate trends, incident retrospectives, risk assessment
- **Output**: Reliability report (GitHub wiki link)
- **Gate**: >5% burn-rate breach triggers postmortem
- **Meeting Link**: [TBD - add calendar link during implementation]

### Quarterly Service Architecture Steering (90 min)
- **Attendees**: Principal engineer, technical leads, CTO
- **Cadence**: First week of Q+1
- **Topics**: Long-term roadmap, tech debt, refactoring priorities, hiring gaps
- **Output**: Quarterly roadmap (GitHub project board)
- **Meeting Link**: [TBD - add calendar link during implementation]

## Service Ownership Responsibilities

**Each service owner is responsible for:**
1. On-call support (rotation)
2. Incident response within 15 minutes of P0 alert
3. Code review approval on critical paths
4. SLO monitoring and breach response
5. Quarterly roadmap input
6. Monthly documentation updates

**Platform team responsibilities:**
1. Shared infrastructure (Kafka, PostgreSQL, Redis, Istio)
2. CI/CD pipeline maintenance
3. Cross-service contract governance
4. Emergency procedures and runbooks

**SRE team responsibilities:**
1. Infrastructure reliability and monitoring
2. On-call rotation and escalation
3. Incident triage and coordination
4. Observability platform (Prometheus, Grafana, Loki)

## Decision Records

All major decisions documented via ADRs (Architecture Decision Records):
- ADR-001 through ADR-015 in `docs/adr/`
- New ADRs required for:
  - Any breaking API change
  - New critical dependency (database, message broker, cache)
  - Security boundary changes
  - SLO modifications
  - Multi-service architectural changes

## Escalation Paths

**P0 Incident (Service Down)**
- Service owner → EM → VP Engineering (within 5 minutes)
- Notify on-call channel in Slack: #incidents
- Page on-call SRE via PagerDuty

**SLO Breach (>5% burn rate)**
- Service owner → Platform team → Reliability review
- Automatic Slack alert to #reliability
- Create SLO postmortem ticket
- Resolution target: 48 hours

**Security Issue**
- Service owner → Security team → CTO (within 1 hour)
- Notify: #security-incidents Slack channel
- Create security advisory (if customer-impacting)

**Data Loss / Financial Impact**
- Service owner → Finance + Security + CTO (immediate notification)
- All-hands incident response
- Timeline: Emergency communication within 15 minutes

## Service Cluster Ownership

See `.github/CODEOWNERS` for detailed service cluster mappings and team assignments.

## Team Directory

| Team | Services | Slack Channel | CODEOWNERS |
|------|----------|---------------|-----------|
| Identity & Auth | identity, admin-gateway | @instacommerce/identity | ADR-011, ADR-012 |
| Order & Checkout | order, checkout-orchestrator, cart | @instacommerce/platform | ADR-001 |
| Payments & Reconciliation | payment, payment-webhook, wallet-loyalty, reconciliation | @instacommerce/payments | ADR-014 |
| Fulfillment & Logistics | fulfillment, warehouse, rider-fleet, routing, dispatch, location | @instacommerce/logistics | ADR-002 |
| Search & Catalog | search, catalog, pricing | @instacommerce/search-catalog | [ADR] |
| Platform & Infrastructure | config-feature-flag, audit-trail, cdc-consumer, outbox-relay, stream-processor | @instacommerce/platform | ADR-004, ADR-013 |
| Engagement | notification, mobile-bff | @instacommerce/engagement | [ADR] |
| Risk & Fraud | fraud-detection | @instacommerce/payments | [ADR] |

## Communication

**Synchronous** (for urgent decisions):
- Slack: Team-specific channels (#payments-team, #platform-team, etc.)
- Incident response: #incidents (PagerDuty escalation)

**Asynchronous** (for non-urgent decisions):
- GitHub Discussions: Contract reviews, architecture questions
- GitHub Issues: Weekly ownership reviews, SLO tracking
- GitHub Wiki: Monthly reliability reports, runbooks

## Change Management

### Service Change Approval Flow
1. **Developer** opens PR with change (code + tests)
2. **Service owner** approves (via CODEOWNERS)
3. **Platform team** approves (infrastructure/contract impact) if cross-service
4. **Security team** approves if auth/secrets/compliance involved
5. **Merge** to master (automatic GitHub Actions)
6. **Staging deploy** (automatic)
7. **Prod deploy** via ArgoCD (manual trigger by on-call SRE)

### Breaking Change Approval Flow
1. **Developer** opens RFC (GitHub Discussions)
2. **Service owner** + stakeholders discuss (1 week minimum)
3. **Create ADR** documenting decision
4. **Implement** ADR in code + tests
5. **PR** approval (as above)
6. **Staging validation** (1 week minimum)
7. **Prod rollout** (staged: 10% → 50% → 100%)

## Metrics & KPIs

**Service Health** (tracked monthly):
- Uptime % (goal: ≥99.9% for critical, ≥99% for standard)
- P99 latency (goal: <500ms for critical, <2s for standard)
- Error rate (goal: <0.1% for critical, <0.5% for standard)
- SLO breach count (goal: 0 per month)

**Team Health** (tracked quarterly):
- Incident response time (goal: <15min for P0)
- PR review time (goal: <24 hours)
- On-call satisfaction (survey)
- Tech debt reduction (story points)

## References

- `.github/CODEOWNERS` - Service team assignments
- `docs/adr/` - Architecture decisions (001-015)
- `docs/slos/` - SLO definitions and burn-rate alerts
- `docs/observability/` - Monitoring and dashboards
- `docs/runbooks/` - Operational playbooks (per service)
