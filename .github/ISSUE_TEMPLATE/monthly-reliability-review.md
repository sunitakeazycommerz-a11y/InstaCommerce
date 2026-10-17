---
name: Monthly Reliability Review Forum
about: Monthly forum tracking SLO compliance, error budgets, incident trends, and reliability initiatives
title: "Monthly Reliability Review - [Month Year]"
labels: ["governance", "reliability-review", "monthly"]
assignees: []
---

## Forum Date
- **Date**: [First Friday date]
- **Attendees**: [List names]
- **Facilitated by**: @[sre-lead]

---

## SLO Compliance Status

### Overall Platform Health
- **Services Meeting SLO**: [X/28] ✅
- **Services At Risk**: [X] 🟡
- **Services Breached**: [X] ❌
- **Month-over-Month Trend**: [Improving / Stable / Degrading]

### Critical Services (99.9% SLO)
| Service | Availability | Latency P99 | Error Rate | Status | Notes |
|---------|--------------|-------------|------------|--------|-------|
| [Service] | [%] | [Xms] | [X%] | ✅/🟡/❌ | [Budget remaining / trend] |

### Standard Services (99.5% SLO)
[Same table structure]

---

## Error Budget Analysis

| Service | SLO Target | Budget Remaining | Status | Days to Exhaustion | Trend |
|---------|------------|------------------|--------|-------------------|-------|
| [Service] | 99.9% | [X%] | 🟢/🟡/🔴/⚫ | [X days] | ↑/→/↓ |

**Status Legend**:
- 🟢 Healthy (>50%): Normal development velocity
- 🟡 Caution (25-50%): Prioritize reliability work
- 🔴 Critical (<25%): Freeze non-critical deployments
- ⚫ Exhausted (0%): Feature freeze until recovery

---

## Incident Review Summary

### Month Statistics
- **P0 Incidents**: [X] (target: ≤1)
- **P1 Incidents**: [X] (target: ≤2)
- **Average MTTR (P0)**: [X] min (target: <30 min)
- **Average MTTR (P1)**: [X] min (target: <90 min)
- **Postmortem Completion Rate**: [X]% (target: 100% within 5 days)

### Major Incidents This Month

**Incident 1**: [Title]
- **Service**: [Service]
- **Priority**: P[0/1]
- **Duration**: [Start] - [End] ([X minutes])
- **Root Cause**: [Brief description]
- **Impact**: [X users / X% throughput]
- **Postmortem**: [Link]
- **Action Items**: [Count and summary]

**Incident 2**: [Title]
[Same structure]

### Incident Trends
- **Repeat Incidents**: [X] (compare to last month: [trend])
- **Systemic Issues Identified**: [List issues affecting multiple services]
- **On-Call Satisfaction**: [Survey result or comments]

---

## Reliability Initiatives Status

| Initiative | Status | Progress | Blocker | Timeline |
|-----------|--------|----------|---------|----------|
| [Initiative] | [On-track/At-risk/Blocked] | [% or description] | [Blocker or None] | [ETA] |

---

## Decisions & Policy Changes

### Escalations to Leadership
- [ ] [Escalation] - Escalated to: @[leader], Reason: [Context]

### Policy Changes
- [ ] SLO Modification: [Service/Description]
- [ ] Error Budget Policy Update: [Description]
- [ ] Escalation Path Change: [Description]

### Approved Reliability Initiatives
- [ ] [Initiative] - Owner: @[owner], Budget: [resources]

---

## Action Items

### Immediate (by EOW)
- [ ] [Action] - Owner: @[owner], Due: [date]

### This Month (by next forum)
- [ ] [Action] - Owner: @[owner], Due: [date]
- [ ] [Action] - Owner: @[owner], Due: [date]

### Strategic (multi-month)
- [ ] [Action] - Owner: @[owner], Due: [date]

---

## Platform KPIs

| KPI | Target | Actual This Month | Last Month | Trend |
|-----|--------|------------------|------------|-------|
| Uptime (all services) | 99.5% | [X]% | [X]% | ↑/→/↓ |
| P0 Incidents | ≤1 | [X] | [X] | ↑/→/↓ |
| MTTR P0 | <30 min | [X] min | [X] min | ↑/→/↓ |
| MTTR P1 | <90 min | [X] min | [X] min | ↑/→/↓ |
| Error Budget >50% | 27/28 | [X]/28 | [X]/28 | ↑/→/↓ |

---

## Next Month Priorities

1. [Priority 1 with rationale]
2. [Priority 2 with rationale]
3. [Priority 3 with rationale]

---

## References

- **SLO Definitions**: docs/slos/service-slos.md
- **Error Budget Policy**: docs/adr/015-slo-error-budget-policy.md
- **Grafana Dashboards**: [Link to SLO dashboard]
- **Previous Reports**: [Link to archive]
- **Slack Channel**: #reliability-forum

---

## Sign-Off

- **SRE Lead**: @[sre-lead] - ☐ Reviewed
- **VP Engineering**: @[vp-eng] - ☐ Acknowledged
