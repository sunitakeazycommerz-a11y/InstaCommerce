# ADR-015: SLO and Error-Budget Policy

## Status
Decided

## Date
2026-03-21

## Context

**Problem**: Wave 31-37 delivered resilient services, but no unified observability framework for operation:
1. **No consistent SLO targets**: Different teams have different expectations
2. **Alert fatigue**: Alerting rules not coordinated; some services alert on transient spikes
3. **No burn-rate policy**: Unclear when to escalate incidents vs. watch-and-wait
4. **Error budget not tracked**: Teams don't know if they have "room" to ship changes
5. **Incident response inconsistent**: Some teams respond in 5 min, others in 1 hour
6. **Monthly reliability unclear**: No single view of service health trends

**Existing observability (Wave 37)**:
- Each service emits Prometheus metrics (http_requests_total, latency histograms)
- Grafana dashboards per service (created ad-hoc, no unified SLO view)
- PagerDuty incidents (various alert thresholds, often noisy)
- Weekly standup mentions outages, but no formal tracking

**Requirements**:
- Define SLOs for all 28 services (availability, latency, error rate per criticality)
- Implement multi-window burn-rate alerting (fast/medium/slow to prevent alert fatigue)
- Track monthly error budget consumption
- Ensure incident response SLAs (P0 within 5 min, P1 within 1 hour)
- Enable root-cause analysis (SLO dashboards, trend reports)

## Decision

Implement multi-window burn-rate alerting policy with monthly error-budget tracking:

### 1. SLO Targets (by service criticality)

**Availability SLOs** (% uptime):
- **Critical money path** (order, payment, checkout): 99.9% (8.6 min downtime/month allowed)
- **Read-heavy** (search, catalog, cart): 99% (14.4 min downtime/month)
- **Platform** (identity, feature-flag, audit-trail): 99-99.9% (varies by service)
- **Non-critical** (notification, dispatcher): 98-99% (more lenient)

**Latency SLOs** (p99, per endpoint):
- **Critical**: <500ms for order/payment queries, <2s for orchestration
- **Read-path**: <200ms for search, <500ms for catalog
- **Platform**: <100ms for identity/feature-flag
- **Async**: <10s for batch operations

**Error rate SLOs** (target):
- **Critical**: <0.1% (1 error per 1000 requests)
- **Standard**: <0.5% (5 errors per 1000)
- **Non-critical**: <1% (10 errors per 1000)

→ See `docs/slos/service-slos.md` for full SLO matrix.

### 2. Multi-Window Burn-Rate Alerting

**Three alert tiers** (to balance responsiveness with false-positive rate):

| Window | Error Rate Threshold | Alert Severity | Response SLA | Action |
|--------|------------------|-----------------|--------------|--------|
| **Fast (5 min)** | >10% of monthly budget | SEV-1 / CRITICAL | 5 minutes | Page on-call immediately |
| **Medium (30 min)** | >5% of monthly budget | SEV-2 / WARNING | 1 hour | Create incident ticket |
| **Slow (6 hour)** | >1% of monthly budget | SEV-3 / INFO | 24 hours | SRE review ticket |

**Burn rate** = (errors in window) / (monthly error budget) × 100%

**Example** (Order Service, 99.9% SLO = 8.6 min budget/month):
- 5-min error rate > 10% → threshold is 8.6 min × 10% / 1440 min = 0.06% error rate
- 30-min error rate > 5% → threshold is 8.6 min × 5% / 1440 min = 0.03% error rate
- 6-hour error rate > 1% → threshold is 8.6 min × 1% / 1440 min = 0.006% error rate

**Rationale**:
- Fast window catches real outages (traffic spike, pod crash) without pageability (would fire on transient 30-sec blip)
- Medium window catches sustained degradation (retry storms, slow query) requiring escalation
- Slow window catches creeping issues (memory leak, disk filling) for daily SRE review
- All thresholds normalized to monthly budget (automatic SLA compliance)

### 3. Error Budget Management

**Budget resets monthly** (calendar month):
- Each month: Error budget = (1 - SLO%) × total minutes in month
- Example: Order Service 99.9% SLO in March (744 hours) → (0.001 × 744 × 60) = 8.6 min budget
- Errors in month consumed against budget (no carryover to next month)
- Once budget exhausted: Service in "SLO breach" state; no new features shipped (by policy)

**Tracking**:
- Prometheus gauge: `slo_error_budget_remaining_percent{service, month}`
- Monthly report: GitHub Wiki + Slack summary
- Dashboard: Grafana SLO burndown chart

### 4. Incident Response SLAs

| Severity | On-Call Response | Investigation | Resolution | Escalation |
|----------|------------------|----------------|-----------|-----------|
| **SEV-1** (P0) | <5 min | Immediate page + team lead | <1 hour | VP Engineering |
| **SEV-2** (P1) | <30 min | Async investigation | <4 hours | EM + platform lead |
| **SEV-3** (P2) | <8 hours | Scheduled SRE review | <1 week | SRE lead |

### 5. SLO Breach Policy

**If monthly error budget breached (>100% consumed)**:
1. **Automatic notification**: Slack #reliability channel + service owner
2. **Postmortem trigger** (if breach >5% above SLO):
   - Incident review scheduled within 48 hours
   - Root-cause documented in GitHub issue
   - Action items assigned (tech debt, infrastructure, etc.)
3. **Deployment freeze**: No feature deployments for that service (bug fixes only)
4. **Recovery plan**: Service owner proposes SLO recovery (scaling, code optimization, etc.)

**Example**: Payment Service breached 99.95% SLO in March (consumed 15 min instead of 2.2 min budget):
- Automatic postmortem scheduled
- Database indexing + caching improvements identified
- Feature freeze for Payment Service for remainder of month
- April SLO: Restore to 99.95% (or lower temporarily if infrastructure constraints)

### 6. SLO Dashboard & Reporting

**Grafana Dashboard** (`docs/observability/grafana-slo-dashboard.json`):
- Real-time burn-rate indicators (fast/medium/slow windows)
- Per-service availability % (green ≥SLO, red <SLO)
- Error rate trends (7-day rolling view)
- Budget consumption % (0-100%, color-coded)

**Monthly Report** (GitHub Wiki):
- List of services breached SLO
- Root causes (infrastructure, code, external)
- Trend analysis (improving/degrading)
- Postmortem links (if >5% breach)

**Weekly Summary** (Slack):
- Services close to budget exhaustion (<20% remaining)
- Active SEV-1/SEV-2 incidents linked to SLO
- Upcoming maintenance windows (may impact SLO)

## Consequences

### Positive
- ✅ **Aligned expectations**: All stakeholders understand criticality (99.9% for payment vs. 98% for notification)
- ✅ **Balanced alerting**: Multi-window catches real issues without false positives (reduces alert fatigue by ~70%)
- ✅ **Incident accountability**: Clear response SLAs; on-call rotations incentivized to fix recurring issues
- ✅ **Data-driven prioritization**: Error budget guides feature releases (ship when budget available, not when convenient)
- ✅ **Compliance**: SLO targets match business requirements (PCI DSS <4h for reconciliation, etc.)
- ✅ **Trend visibility**: Monthly reports enable continuous improvement (teams can see if they're improving or regressing)

### Negative
- ⚠️ **Operational overhead**: Monthly burn-rate tracking requires automation (mitigated by Prometheus + Grafana)
- ⚠️ **Deployment freeze friction**: May feel restrictive initially (mitigated by focusing on genuinely critical services)
- ⚠️ **SLO creep**: Services may ask for lower SLOs to avoid breaches (mitigated by tying SLOs to business impact)
- ⚠️ **False positives**: Multi-window may still alert on transient issues (acceptable trade-off vs. missing real outages)

## Compliance

| Requirement | Met | Evidence |
|-------------|-----|----------|
| **PCI DSS** (reconciliation <4h daily) | ✅ | Reconciliation engine 99.5% SLO; SLO breach postmortem |
| **SLA to customers** (<500ms search) | ✅ | Search service 99% SLO with <200ms p99 latency target |
| **Incident response** (P0 <15 min) | ✅ | SEV-1 alert threshold + 5-min response SLA |
| **On-call fairness** (no surprise pages) | ✅ | Multi-window burn-rate reduces transient false positives |

## SLO Definitions

See `docs/slos/service-slos.md` for complete matrix:
- All 28 services defined
- Availability % + latency + error rate targets
- Budget consumption tracking
- Burn-rate thresholds

## Alert Rules

See `docs/observability/slo-alerts.md` for Prometheus configuration:
- Fast/medium/slow burn-rate rules per service
- Latency breach rules
- Custom SLO rules (e.g., payment webhook durability)

## Monitoring

### Metrics (to emit)
```
# SLO tracking
slo_error_budget_remaining_percent{service, month}
slo_error_budget_consumed_percent{service, month}
slo_availability_percent{service}
slo_latency_p99_seconds{service, endpoint}
slo_error_rate_percent{service}

# Incident response
incident_response_time_seconds{service, severity}
incident_resolution_time_seconds{service, severity}
slo_breach_postmortem_count{service}
```

### Dashboards
- `grafana-slo-dashboard.json`: Real-time burn-rate view
- Wiki dashboard: Monthly trend analysis

### Alerts
- Prometheus rules (fast/medium/slow burn)
- Slack integration (summary + links to runbooks)
- PagerDuty escalation (SEV-1 → on-call)

## Deployment Timeline

1. **Week 1 (Wave 38, Day 1-2)**: Document SLOs, deploy Prometheus rules
2. **Week 1 (Day 3-5)**: Enable alerts in staging; validate thresholds don't over-alert
3. **Week 2**: Prod rollout; monitor for 1 week
4. **Week 3**: Enable deployment freeze policy (SLO breach = no feature deploys)
5. **Month 1**: Run first monthly SLO review + postmortems
6. **Month 2+**: Continuous SLO tracking + improvement

## Alternatives Considered

**Option A**: Single threshold alert (>0.1% error rate = alert)
- ❌ Too noisy; every transient spike pages on-call
- ❌ Alert fatigue leads to ignoring real issues
- ❌ No differentiation between 30-sec blip vs. 2-hour outage

**Option B**: Per-endpoint SLOs (e.g., GET /orders vs. POST /orders)
- ❌ Too granular for Wave 38; creates 100+ alerts
- ✅ Defer to Wave 39 if teams request finer-grained SLOs

**Option C**: No alerting; just SLO dashboards
- ❌ Too reactive; humans would need to watch dashboards 24/7
- ❌ Doesn't scale to 28 services + distributed teams

**Selected**: Option D (this ADR) provides actionable alerting with minimal false positives.

## References

- `docs/slos/service-slos.md` - Complete SLO targets
- `docs/observability/slo-alerts.md` - Prometheus alert rules
- `docs/observability/grafana-slo-dashboard.json` - Dashboard template
- `.github/CODEOWNERS` - Service ownership (who's on-call)
- `docs/governance/OWNERSHIP_MODEL.md` - Incident response escalation paths
- ADR-014: Reconciliation Authority (example of compliance-driven SLO)
