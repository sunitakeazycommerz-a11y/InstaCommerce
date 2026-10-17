# Monthly Reliability Review Forum

## Overview

The Monthly Reliability Review Forum is a 60-minute forum held on the first Friday of each month at 10:00 AM UTC. This forum reviews service-level objectives (SLOs), error budgets, incident trends, and drives accountability for reliability across the platform.

## Meeting Details

- **When**: First Friday of every month, 10:00 AM UTC
- **Duration**: 60 minutes (strict timeboxing)
- **Attendees**:
  - Service owners (all 28 teams, or representatives)
  - SRE team lead
  - Platform team lead
  - Engineering leadership (EM, VP, CTO if possible)
  - Optional: Product leadership (for understanding impact)

- **Location**: #reliability-forum Slack channel + Zoom link
- **Calendar**: [Recurring invite to be created during implementation]
- **Slack Channel**: #reliability (daily alerts), #reliability-forum (monthly discussions)

## Pre-Meeting Preparation (Thursday, EOD)

Before the forum, the SRE team generates:
1. **SLO Compliance Report** (auto-generated from Grafana)
   - Per-service availability, latency, error rate vs. SLO targets
   - Burn rate (fast, medium, slow windows)
   - Trend analysis (improving/degrading)

2. **Incident Summary**
   - All P0/P1 incidents from past month
   - MTTR (mean time to resolution) metrics
   - Postmortem completion rate

3. **Error Budget Status**
   - Current budget remaining (by service)
   - Status indicator (🟢 Healthy / 🟡 Caution / 🔴 Critical / ⚫ Exhausted)
   - Predictions for next 30 days

Documents posted to #reliability-forum Slack channel for async review by Thursday 5 PM UTC.

## Agenda Template (60 min)

### 1. Opening & Dashboard Review (5 min)
- Confirm attendees and any urgent escalations
- Walk through Grafana dashboards on screen
- Call out any services trending poorly

### 2. SLO Compliance Report (15 min)
- **By service cluster** (5 clusters × 3 min each):
  1. Identity & Auth
  2. Order & Checkout
  3. Payments & Fulfillment
  4. Search & Engagement
  5. Platform & AI

- **Per cluster, review**:
  - Services meeting SLO ✅ (no discussion needed)
  - Services at risk 🟡 (discuss trend, root cause)
  - Services breached ❌ (immediate action items)

### 3. Error Budget Analysis (10 min)
- **Chart**: All 28 services' budget status
- **Deep dive on**:
  - Any services with <25% budget (⚫ or 🔴)
  - Services trending toward budget exhaustion
  - Services with improved budget recovery

### 4. Incident Review & Trends (15 min)
- **Metrics**:
  - P0 incidents: [X] (target: 0)
  - P1 incidents: [X] (target: ≤1 per month)
  - MTTR: [X] minutes (target: <30 min for P0, <90 min for P1)
  - Postmortem completion: [X]% (target: 100% within 5 days)

- **Incident deep dives** (if applicable):
  - Root cause analysis of top 2-3 incidents
  - Repeat incident patterns
  - Any systemic issues

### 5. Reliability Initiatives Status (10 min)
- Progress on ongoing projects (e.g., circuit breakers, chaos testing, capacity planning)
- Blockers or resource constraints
- Timeline adjustments needed

### 6. Action Items & Decisions (5 min)
- Summarize all action items from forum
- Confirm ownership and due dates
- Identify any escalations to leadership

## Meeting Output

### GitHub Issue Template

Each forum meeting creates a GitHub Issue tagged `governance`, `reliability`, and `monthly`.

**Issue Title**: `Monthly Reliability Review - [Month Year]`

**Issue Body** (template):

```markdown
## Forum Date
- **Date**: [First Friday date]
- **Attendees**: [List]
- **Facilitated by**: @[SRE lead]

## SLO Compliance Summary

### Overall Status
- **Services meeting SLO**: [X/28]
- **Services at risk (🟡)**: [X]
- **Services breached (❌)**: [X]
- **Trend**: [Improving / Stable / Degrading]

### By Cluster

#### Identity & Auth
| Service | Availability | Latency P99 | Error Rate | Status | Notes |
|---------|--------------|-------------|------------|--------|-------|
| identity-service | 99.9% | 150ms | 0.01% | ✅ | - |
| admin-gateway-service | 99.9% | 200ms | 0.02% | ✅ | - |

#### Order & Checkout
[Same table structure]

#### [Continue for all 5 clusters]

## Error Budget Status

| Service | Target | Budget Remaining | Status | Trend | Action |
|---------|--------|------------------|--------|-------|--------|
| payment-service | 99.95% | 15% | 🔴 | Degrading | 🚨 Reliability sprint required |
| order-service | 99.9% | 45% | 🟡 | Stable | 📊 Monitor closely |
| [Continue for services with <50% budget] | - | - | - | - | - |

### Legend
- 🟢 Healthy (>50%): Normal operations
- 🟡 Caution (25-50%): Prioritize reliability work
- 🔴 Critical (<25%): Freeze non-critical deployments
- ⚫ Exhausted (0%): Feature freeze until recovery

## Incident Analysis

### Month Statistics
- **P0 incidents**: [X]
- **P1 incidents**: [X]
- **Average MTTR (P0)**: [X] minutes (target: <30)
- **Average MTTR (P1)**: [X] minutes (target: <90)
- **Postmortem completion rate**: [X]% (target: 100% within 5 days)

### Top Incidents This Month

1. **[Incident Title]** (P0)
   - **Service**: [Service]
   - **Duration**: [Start] - [End] ([duration])
   - **Root Cause**: [Brief description]
   - **Impact**: [X users affected / [X% throughput loss]
   - **Postmortem**: [Link]
   - **Action Items**: [List]

2. **[Incident Title]** (P1)
   - [Same structure]

### Repeat Incidents
- [ ] [Repeat pattern 1] - Last occurred [Date], now [Date] (status: fixed/investigating)
- [ ] [Repeat pattern 2]

### Systemic Issues
- [Issue 1 affecting multiple services]
- [Issue 2]

## Reliability Initiatives Update

| Initiative | Status | Progress | Blocker | ETA |
|-----------|--------|----------|---------|-----|
| [Initiative] | [On-track/At-risk/Blocked] | [Progress description] | [Blocker or none] | [Date] |

## Decisions Made

- **Decision 1**: [Context and rationale]
- **Decision 2**: [Context and rationale]

### Policy Changes
- [ ] SLO modification: [Service] - [Change rationale]
- [ ] Error budget policy change: [Description]
- [ ] Escalation path update: [Description]

## Action Items

### Immediate (by end of week)
- [ ] [Action] - Owner: @[owner], Due: [EOW date]

### This Month (by next forum)
- [ ] [Action] - Owner: @[owner], Due: [date]
- [ ] [Action] - Owner: @[owner], Due: [date]

### Strategic (multi-month)
- [ ] [Action] - Owner: @[owner], Due: [date]

## Escalations to Leadership

- [ ] Budget exhaustion for [Service] - Escalated to: @[VP Engineering]
- [ ] Systemic incident pattern - Escalated to: @[CTO]
- [ ] Resource constraint - Escalated to: @[EM leads]

## Metrics & KPIs

### Platform-Wide KPIs (target vs. actual)
| KPI | Target | Actual | Trend |
|-----|--------|--------|-------|
| Uptime (all services) | 99.5% | [X]% | [↑/→/↓] |
| P0 incidents / month | ≤1 | [X] | [↑/→/↓] |
| MTTR P0 | <30 min | [X] min | [↑/→/↓] |
| MTTR P1 | <90 min | [X] min | [↑/→/↓] |
| Postmortem completion | 100% | [X]% | [↑/→/↓] |
| Error budget >50% | 27/28 | [X]/28 | [↑/→/↓] |

## Next Month Priorities

1. [Priority 1 with rationale]
2. [Priority 2 with rationale]
3. [Priority 3 with rationale]

## References

- **Grafana SLO Dashboard**: [Link]
- **Error Budget Dashboard**: [Link]
- **Incident Metrics Dashboard**: [Link]
- **Service SLOs**: docs/slos/service-slos.md
- **ADR-015 SLO Policy**: docs/adr/015-slo-error-budget-policy.md

---
**Meeting Recording**: [Link to recording if recorded]
**Meeting Notes**: [Link to Slack thread]
```

### Slack Notification

```
📊 Monthly Reliability Review Complete

📈 Results:
- Services meeting SLO: [X]/28
- Services at risk: [X]
- Services breached: [X]

🚨 Incidents:
- P0: [X] (avg MTTR: [X] min)
- P1: [X] (avg MTTR: [X] min)

💾 Error Budget:
- Healthy (>50%): [X]/28 services
- Caution (25-50%): [X]/28
- Critical (<25%): [X]/28

⚡ Action Items: [X] (see full notes)

Full report: [Link to GitHub Issue]
Next forum: [Date in 1 month]
```

## Decision Rights in This Forum

| Decision Type | Authority | Process |
|---------------|-----------|---------|
| Declare error budget exhausted | SRE lead + service owner | Can pause deployments immediately; confirm in forum |
| Approve reliability initiative | Platform lead + SRE lead | Consensus; escalate to leadership if resource impact |
| Modify SLO target | Principal engineer + service owner | Rare; requires ADR and discussion of implications |
| Escalate systemic issue to leadership | Forum lead | Immediate escalation if >3 services affected |
| Approve emergency reliability sprint | VP Engineering | If major incident requires dedicated focus |

## SLO Breach Response Workflow

When a service breaches SLO (error budget exhausted):

1. **Immediate** (within forum or if forum not active):
   - SRE lead notifies service owner and EM
   - Declare error budget status as ⚫ Exhausted
   - Pause non-critical deployments

2. **Within 24 hours**:
   - Service owner starts reliability sprint (50%+ team capacity)
   - Identify root causes for breaches
   - Create action plan

3. **Within 1 week**:
   - Present root cause analysis to forum
   - Discuss improvements and timeline

4. **Ongoing**:
   - Track budget recovery daily
   - Weekly updates to leadership
   - Resume normal deployments when budget >25%

## Calendar Invite Template

```
Title: Monthly Reliability Review Forum
When: First Friday of every month, 10:00 AM - 11:00 AM UTC
Recurring: First Friday, starting [Month/Year]
Location: Zoom [to be added] + #reliability-forum Slack
Attendees:
  - @instacommerce/sre
  - @instacommerce/platform
  - @instacommerce/identity
  - @instacommerce/search-catalog
  - @instacommerce/payments
  - @instacommerce/logistics
  - @instacommerce/engagement
  - @instacommerce/data-ml
  - @instacommerce/engineering-leadership

Description:
Monthly forum for reviewing SLO compliance, error budgets, incident
trends, and reliability priorities. All service owners and SRE team
required to attend. Leadership participation strongly encouraged.

Pre-forum materials posted to #reliability-forum by Thursday EOD.
Grafana dashboards: [Link]
Previous reports: [Link]
```

## Success Metrics

- **Attendance**: ≥90% of service teams each forum
- **Duration**: Completes within 60 minutes
- **SLO accuracy**: 100% of services tracked and reported
- **Postmortem completion**: ≥95% within 5 days of P0/P1 incident
- **Error budget exhaustion response**: <1 hour from breach to action items
- **Action item completion**: ≥90% of items completed by due date

## References

- **Service SLOs**: docs/slos/service-slos.md
- **Error Budget Policy**: docs/adr/015-slo-error-budget-policy.md
- **Escalation Matrix**: docs/governance/escalation-matrix.md
- **On-Call Guide**: docs/runbooks/on-call-guide.md
- **Incident Postmortem Template**: [Link to template]
