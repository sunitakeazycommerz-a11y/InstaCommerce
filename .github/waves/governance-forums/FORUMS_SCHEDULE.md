# InstaCommerce Governance Forums Schedule

**Activation Date**: 2026-03-21
**Wave**: 38 - Governance Framework
**Status**: ACTIVE

---

## Forum Calendar Overview

All forums are recurring and documented in the **InstaCommerce Governance Calendar**. Meeting links and dial-in details are provided in team Slack channels.

---

## 1. Weekly Service Ownership Review

### Cadence
- **Frequency**: Every Monday
- **Time**: 9:00 AM UTC
- **Duration**: 30 minutes
- **Time Zone Conversions**:
  - 9:00 AM UTC
  - 4:00 AM EST / 1:00 AM PST
  - 2:30 PM IST
  - 6:00 PM CET

### Attendees
- Service owner (DRI)
- Engineering Manager
- On-call SRE
- Platform team lead

### Agenda
1. **Status check** (5 min): Any P0/P1 incidents since last meeting
2. **Deployment readiness** (10 min): Critical PRs, CI blockers, failed deployments
3. **On-call feedback** (10 min): Pain points, runbook updates
4. **Action items** (5 min): Assign owners, track in GitHub issue

### Output
- GitHub issue template: `.github/ISSUE_TEMPLATE/service-ownership-review.md`
- Label: `governance/weekly-review`
- Stored in: GitHub project board **Service Health Tracking**

### Decision Gate
- CODEOWNERS approval required for any service change (branch protection)
- Service owner must approve PR within 24 hours of review request

### Next 4 Weeks (Starting March 24, 2026)
- **Monday, March 24**: Review cycle begins; all services baseline assessment
- **Monday, March 31**: Q1 close-out review + Q2 preparation
- **Monday, April 7**: Q2 kick-off
- **Monday, April 14**: Routine check-in

---

## 2. Biweekly Contract Review

### Cadence
- **Frequency**: Every other Wednesday
- **Time**: 2:00 PM UTC
- **Duration**: 45 minutes
- **Time Zone Conversions**:
  - 2:00 PM UTC
  - 9:00 AM EST / 6:00 AM PST
  - 7:30 PM IST
  - 3:00 PM CET

### Attendees
- All service owners (rotating attendance by cluster)
- Data platform lead
- Principal engineer / Tech lead
- Platform team representative

### Agenda
1. **Breaking changes RFC** (20 min): New proposals, migration plans
2. **Schema evolution** (15 min): Database migrations, compatibility assessment
3. **Event contract updates** (5 min): New event types, deprecations
4. **API versioning** (5 min): Endpoint changes, backwards compatibility

### Output
- GitHub Discussions: `discussions/contracts`
- Label: `governance/contract-review`
- ADR required for breaking changes before implementation

### Decision Gate
- Breaking changes require **ADR + consensus** (no single veto)
- Schema changes require **data platform sign-off**
- Event changes require **affected service owners' approval**

### Next 8 Weeks (Starting April 2, 2026)
- **Wednesday, April 2**: Payment schema review (reconciliation engine)
- **Wednesday, April 16**: Search + Catalog API contract refresh
- **Wednesday, April 30**: Logistics cluster schema assessment
- **Wednesday, May 14**: Fraud detection integration contracts

---

## 3. Monthly Reliability + Governance Review

### Cadence
- **Frequency**: First Friday of each month
- **Time**: 10:00 AM UTC
- **Duration**: 60 minutes
- **Time Zone Conversions**:
  - 10:00 AM UTC
  - 5:00 AM EST / 2:00 AM PST
  - 3:30 PM IST
  - 11:00 AM CET

### Attendees
- Platform engineering leads
- SRE team (incident commander, on-call lead)
- Engineering managers (all clusters)
- Security team representative
- Finance / Operations observer (for compliance)

### Agenda
1. **SLO performance review** (15 min): Error budgets, burn rates, trend analysis
2. **Incident retrospectives** (20 min): P0/P1 root causes, prevention measures
3. **Governance metrics** (10 min): CODEOWNERS enforcement, ADR backlog
4. **Risk assessment** (10 min): Dependency vulnerabilities, tech debt prioritization
5. **Action items** (5 min): Close-out and assignments

### Output
- GitHub Wiki: **Monthly Reliability Report** (linked in docs/observability/)
- Label: `governance/reliability-review`
- Incident postmortems published to internal wiki (48-hour target)

### Decision Gate
- **>5% error budget burn** triggers formal postmortem ticket
- **>3 incidents/month** in same service triggers architecture review
- **<80% SLO adherence** forces action plan from service owner

### Next 12 Months (Starting April 4, 2026)
- **Friday, April 4, 2026**: March performance review (Wave 38 baseline)
- **Friday, May 2, 2026**: April review + Q2 preparation
- **Friday, June 6, 2026**: May review + mid-year assessment
- **Friday, July 3, 2026**: June review (quarterly SLO refresh)
- **Friday, August 1, 2026**: July review
- **Friday, September 5, 2026**: August review (quarterly steering prep)
- **Friday, October 3, 2026**: September review
- **Friday, November 7, 2026**: October review (quarterly steering)
- **Friday, December 5, 2026**: November review
- **Friday, January 2, 2027**: December review + Q1 2027 planning

---

## 4. Quarterly Service Architecture Steering

### Cadence
- **Frequency**: First week of quarter (Q+1 planning)
- **Time**: 3:00 PM UTC
- **Duration**: 90 minutes
- **Time Zone Conversions**:
  - 3:00 PM UTC
  - 10:00 AM EST / 7:00 AM PST
  - 8:30 PM IST
  - 4:00 PM CET

### Attendees
- Principal engineer / CTO
- All technical leads (service clusters)
- Platform engineering lead
- Data platform lead
- Security lead

### Agenda
1. **Roadmap review** (30 min): Q+1 priorities, capacity planning, dependency mapping
2. **Tech debt assessment** (20 min): Refactoring opportunities, deprecation timelines
3. **Architecture changes** (20 min): Major redesigns, new dependencies, migrations
4. **Hiring & onboarding** (15 min): Capacity gaps, skill gaps, ramp-up plans
5. **Strategy alignment** (5 min): Close-out and Q+2 preview

### Output
- GitHub Project Board: **Architecture Roadmap** (tracks quarter)
- ADRs: All major decisions documented before implementation
- Published to internal wiki with quarterly strategy document

### Decision Gate
- **New critical dependency** requires security + data platform review
- **Multi-service refactor** requires principal engineer sign-off
- **SLO changes** require monthly reliability review sign-off

### Next 5 Quarters (Starting April 7, 2026)
- **Monday, April 7, 2026**: Q2 Steering (Wave 39 planning)
- **Monday, July 6, 2026**: Q3 Steering (mid-year strategy reset)
- **Monday, October 5, 2026**: Q4 Steering (year-end planning)
- **Monday, January 5, 2027**: Q1 2027 Steering
- **Monday, April 6, 2027**: Q2 2027 Steering

---

## Escalation Paths & Severity Levels

### P0 Incident (Service Down) - 5 Minute SLA
- Service owner → EM → VP Engineering
- Notification channels: `#incidents` Slack + PagerDuty on-call
- Mandatory all-hands incident response
- Post-incident review within 24 hours

### SLO Breach (>5% burn rate) - 1 Hour SLA
- Service owner → Platform team → Schedule reliability review
- Notification channels: `#reliability` Slack + GitHub SRE board
- Automatic escalation to monthly review meeting
- Resolution target: 48 hours
- Postmortem publication: 72 hours

### Security Issue - 1 Hour SLA
- Service owner → Security team → CTO
- Notification channels: `#security-incidents` Slack
- Customer impact assessment
- Disclosure timeline per security policy

### Data Loss / Financial Impact - Immediate
- Service owner → Finance + Security + CTO
- Emergency communication within 15 minutes
- All-hands incident response
- Regulatory notification (if required)

---

## CODEOWNERS Enforcement

**Active since March 21, 2026**

### Requirements
- All service changes require **service owner approval** (CODEOWNERS)
- Cross-service changes require **platform team approval**
- Governance + ADR changes require **platform team review**

### Teams (28 services across 8 clusters)
- `@instacommerce/identity` (2 services)
- `@instacommerce/platform` (8 services)
- `@instacommerce/search-catalog` (3 services)
- `@instacommerce/payments` (4 services)
- `@instacommerce/logistics` (6 services)
- `@instacommerce/engagement` (2 services)
- `@instacommerce/data-ml` (3 services)
- `@instacommerce/sre` (infrastructure + CI/CD)

See `.github/CODEOWNERS` for complete mapping.

---

## SLO Targets (All 28 Services)

### Critical Money Path
| Service | Availability | P99 Latency | Error Rate |
|---------|--------------|-------------|-----------|
| Payment | 99.95% | <300ms | <0.05% |
| Order | 99.9% | <500ms | <0.1% |
| Checkout | 99.9% | <2s | <0.1% |
| Fulfillment | 99.5% | <2s | <0.5% |

### Read Path (Search & Catalog)
| Service | Availability | P99 Latency | Freshness |
|---------|--------------|-------------|-----------|
| Search | 99% | <200ms | <5 min |
| Catalog | 99.5% | <500ms | <10 min |
| Cart | 99.8% | <300ms | N/A |
| Pricing | 99% | <100ms | <5 min |

### Platform Services
| Service | Availability | P99 Latency | Special SLO |
|---------|--------------|-------------|-----------|
| Identity | 99.9% | <100ms | Token <30s |
| Feature Flag | 99% | <10ms | Invalidate <500ms |
| Audit Trail | 99% | <100ms | Durability 99.99% |
| Reconciliation | 99.5% | <30s | Accuracy 100% |

See `docs/slos/service-slos.md` for complete SLO definitions.

---

## Alert Thresholds (Prometheus)

### Burn-Rate Windows
1. **Fast burn (5 min)**: >10% error rate → **SEV-1 page** (immediate)
2. **Medium burn (30 min)**: >5% error rate → **SEV-2 incident** (create ticket)
3. **Slow burn (6h)**: >1% error rate → **SEV-3 review** (SRE review)

### Example: Payment Service
- Error budget: 2.2 min/month (99.95% SLO)
- Fast burn: 10% × 2.2 min = 13 seconds to exhaust budget
- Page threshold: Error rate >10% sustained 5 minutes

See `docs/observability/slo-alerts.md` for all 50+ alert rules.

---

## Documentation Links

- **Governance Model**: `docs/governance/OWNERSHIP_MODEL.md`
- **Service Ownership**: `.github/CODEOWNERS` (28 services, 8 teams)
- **SLO Definitions**: `docs/slos/service-slos.md` (all 28 services)
- **Alert Rules**: `docs/observability/slo-alerts.md` (50+ Prometheus alerts)
- **SLO Policy**: `docs/adr/015-slo-error-budget-policy.md` (burn-rate rationale)
- **Reconciliation Authority**: `docs/adr/014-reconciliation-authority-model.md`
- **Admin Auth**: `docs/adr/011-admin-gateway-jwt-auth.md`
- **Token Scoping**: `docs/adr/012-per-service-token-scoping.md`
- **Cache Invalidation**: `docs/adr/013-feature-flag-cache-invalidation.md`

---

## Key Metrics (Tracked Monthly)

### Service Health KPIs
- Uptime % (goal: ≥99.9% critical, ≥99% standard)
- P99 latency (goal: <500ms critical, <2s standard)
- Error rate (goal: <0.1% critical, <0.5% standard)
- SLO breach count (goal: 0 per month)

### Team Health KPIs
- Incident response time (goal: <15min for P0)
- PR review time (goal: <24 hours)
- On-call satisfaction (quarterly survey)
- Tech debt reduction (story points)

---

## Calendar Integration

### iCalendar Export (for Outlook/Google Calendar)
All forums are documented in the **InstaCommerce Governance Calendar**. To add to your calendar:

1. **Weekly Review**: Monday 9:00 AM UTC (recurring)
2. **Biweekly Contract**: Wednesday 2:00 PM UTC (every 2 weeks)
3. **Monthly Reliability**: First Friday 10:00 AM UTC (recurring, skip weekends)
4. **Quarterly Steering**: First business day of Q+1, 3:00 PM UTC

Contact: `@instacommerce/platform` for calendar invites and meeting links.

---

## Change History

| Date | Event | Status |
|------|-------|--------|
| 2026-03-21 | Wave 38 governance activation | ACTIVE |
| 2026-03-21 | Forums schedule published | ACTIVE |
| 2026-03-21 | CODEOWNERS enforcement enabled | ACTIVE |
| 2026-03-21 | SLO monitoring begins | ACTIVE |
| 2026-03-24 | First weekly ownership review | Scheduled |

---

**Last Updated**: 2026-03-21
**Maintained By**: `@instacommerce/platform`
**Escalation**: CTO (for governance questions)
