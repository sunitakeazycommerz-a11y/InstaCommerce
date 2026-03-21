---
name: Weekly Service Ownership Review
about: Weekly service ownership review tracking critical PRs, incidents, and deployment readiness
title: "Week [WW] Service Ownership Review - [SERVICE_NAME]"
labels: ["governance", "service-ownership", "weekly"]
assignees: []
---

## Service: [SERVICE_NAME]

**Week of**: [YYYY-MM-DD]
**Service Owner**: @[owner]
**EM Lead**: @[em]
**On-Call SRE**: @[sre]

---

## Critical PRs In-Flight

- [ ] PR #123: [Description] - **Reviewer**: @[reviewer], **Status**: [Waiting for review / Ready to merge / Blocked]
- [ ] PR #124: [Description] - **Reviewer**: @[reviewer], **Status**: [Waiting for review / Ready to merge / Blocked]

*Leave empty if no critical PRs this week*

---

## CI/CD Status

### Last 7 Days
- **Passing builds**: [X/Y] (target: ≥99%)
- **Failed builds**: [Y] (root cause: ...)
- **Deploy success**: [X/Y] (target: ≥95%)

### Recent Failures
- [ ] Build: [timestamp] - `[error]` - **Status**: [Investigating / Fixed / Escalated]
- [ ] Deploy: [timestamp] - `[error]` - **Status**: [Investigating / Fixed / Escalated]

---

## P0/P1 Issues This Week

### P0 (Critical - Production Down)
- [ ] Issue #[num]: [Description] - **Severity**: [SEV-1/2], **Status**: [Open / In Progress / Resolved]
  - **Root Cause**: [TBD / Brief explanation]
  - **Fix**: [TBD / Deployed timestamp]
  - **Postmortem**: [Link to postmortem if completed]

### P1 (High - Significant Degradation)
- [ ] Issue #[num]: [Description] - **Severity**: [SEV-2], **Status**: [Open / In Progress / Resolved]
  - **Impact**: [Brief customer-facing impact]
  - **Fix ETA**: [Date or "In progress"]

*Leave empty if no P0/P1 issues this week*

---

## SLO Status

| Metric | Target | Current | Status | Notes |
|--------|--------|---------|--------|-------|
| Availability | [%] | [%] | 🟢 / 🟡 / 🔴 | [Budget remaining / On track] |
| Latency (p99) | [Xms] | [Xms] | 🟢 / 🟡 / 🔴 | [Improving / Degrading] |
| Error Rate | [X%] | [X%] | 🟢 / 🟡 / 🔴 | [Improving / Degrading] |

---

## Deployment Readiness

### Staging
- [ ] Staging environment stable (no ongoing incidents)
- [ ] Load testing completed (if applicable)
- [ ] Database migrations tested (if applicable)

### Production Readiness
- [ ] All critical PRs approved and merged
- [ ] No blocking incidents
- [ ] Documentation updated
- [ ] On-call rotation assigned
- [ ] **Deployment scheduled**: [Date/Time] or "On hold until [reason]"

---

## Known Blockers / Escalations

### Technical Blockers
- [ ] [Blocker description] - **Owner**: @[owner], **ETA**: [Date or "Investigating"]

### Cross-Team Dependencies
- [ ] [Dependency description] - **Depends on**: @[team], **Status**: [Waiting / In progress / Unblocked]

### Resource Constraints
- [ ] [Constraint description] - **Impact**: [Service impact], **Escalation**: [To whom]

*Leave empty if no blockers this week*

---

## Planned Activities This Week

- [ ] [Activity 1] - [Owner], [ETA]
- [ ] [Activity 2] - [Owner], [ETA]
- [ ] [Activity 3] - [Owner], [ETA]

---

## Notes & Decisions

**Key decisions made this week:**
- [Decision 1 with context]
- [Decision 2 with context]

**Follow-ups for next week:**
- [ ] [Follow-up 1] - Owner: @[owner]
- [ ] [Follow-up 2] - Owner: @[owner]

---

## Sign-Off

- **Service Owner**: @[owner] - ☐ Approved
- **EM Lead**: @[em] - ☐ Acknowledged
- **On-Call SRE**: @[sre] - ☐ Acknowledged

---

## Quick Links

- **Service Dashboard**: [Link to Grafana]
- **Runbook**: [Link to runbook]
- **Incidents**: [Link to incident channel]
- **Slack Channel**: #[service-team]
- **Repository**: [Link to repo]
