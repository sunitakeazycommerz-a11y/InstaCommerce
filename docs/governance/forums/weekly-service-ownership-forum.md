# Weekly Service Ownership Forum

## Overview

The Weekly Service Ownership Forum is a 30-minute synchronous meeting held every Monday at 9:00 AM UTC. This forum serves as the operational hub for service ownership, incident review, and deployment coordination across all 28 services.

## Meeting Details

- **When**: Every Monday, 9:00 AM UTC
- **Duration**: 30 minutes (strict timeboxing)
- **Attendees**:
  - One representative from each service-owning team
  - On-call engineers (outgoing and incoming shifts)
  - Platform team lead
  - SRE representative
  - Optional: EM leads for escalation discussions

- **Location**: #service-ownership-forum Slack channel + Zoom link
- **Calendar**: [Recurring invite to be created during implementation]
- **Slack Channel**: #service-ownership (notifications & async follow-ups)

## Pre-Meeting Preparation (Friday, EOD)

Each service owner should prepare a brief status update covering:

1. **Incidents since last meeting**: Any P0/P1 issues with brief status
2. **Deployment readiness**: Next planned deployment (if any)
3. **Known blockers**: Any cross-team dependencies or escalations needed

This should be posted to #service-ownership Slack channel 24 hours before the meeting to allow async review.

## Agenda Template (30 min)

### 1. Opening & Agenda Review (2 min)
- Confirm attendees and agenda items
- Call out any urgent escalations that need priority

### 2. Incident Review Round-Table (10 min)
- **Structure**: Quick round-robin of teams with active P0/P1 incidents
- **Format per incident**:
  - Service name
  - Incident title
  - Current status (Investigating / Mitigating / Resolved)
  - Escalations needed (if any)
  - Postmortem link (if resolved)
- **Action**: If incident is blocking deployments or requires cross-team coordination, assign owner and set follow-up date

### 3. On-Call Handoff (5 min)
- Outgoing on-call SRE: Outstanding issues, escalation contacts, hotspots to watch
- Incoming on-call SRE: Confirms understanding, raises questions
- Note: Full on-call briefing happens in dedicated Slack thread; this is high-level only

### 4. Deployment Coordination (8 min)
- Service owners share planned deployments for the coming week
- Identify deployment dependencies or risks
- Coordinate deployment order if needed
- **Action**: Document in deployment tracking (if applicable)

### 5. Escalations & Blockers (3 min)
- Resource constraints affecting teams
- Cross-team dependencies requiring coordination
- **Action**: Assign owners and follow-up dates

### 6. Closing (2 min)
- Confirm all action items and owners
- Preview agenda for next week
- Post meeting notes to GitHub

## Meeting Output

### GitHub Issue Template

Each week's forum meeting creates a new GitHub Issue tagged `governance`, `service-ownership`, and `weekly-forum`.

**Issue Title**: `Week [WW] Service Ownership Forum - [Date]`

**Issue Body** (template):

```markdown
## Forum Date
- **Date**: [Monday date]
- **Attendees**: [List of attendees]
- **Incident Commander (if active)**: [Name]

## Incident Review Summary

### Active Incidents
| Service | Priority | Status | Escalation | Postmortem Link |
|---------|----------|--------|------------|-----------------|
| [Service] | P[0/1] | [Status] | [Yes/No] | [Link or TBD] |

### Incident Actions
- [ ] [Action 1] - Owner: @[owner], Due: [date]
- [ ] [Action 2] - Owner: @[owner], Due: [date]

## On-Call Handoff Notes

**Outgoing on-call highlights**:
- [Issue 1]
- [Issue 2]

**Hotspots to watch**:
- [Hotspot 1]
- [Hotspot 2]

## Deployments Planned This Week

| Service | Type | Status | Dependencies | Risk |
|---------|------|--------|--------------|------|
| [Service] | [Feature/Fix/Hotfix] | [Planned/Blocked] | [List] | [Low/Med/High] |

## Cross-Team Escalations

- [ ] [Escalation 1] - Assigned to: @[owner], Due: [date]
- [ ] [Escalation 2] - Assigned to: @[owner], Due: [date]

## Decisions Made

- Decision 1: [Context and reasoning]
- Decision 2: [Context and reasoning]

## Follow-Ups for Next Week

- [ ] [Follow-up 1] - Owner: @[owner]
- [ ] [Follow-up 2] - Owner: @[owner]

---
**Meeting Notes**: [Link to Slack thread]
```

### Slack Notification

A bot should post the following summary to #service-ownership immediately after the meeting:

```
📋 Week [WW] Service Ownership Forum Complete

Incidents Discussed: [X]
Deployments Planned: [X]
Escalations: [X]
Action Items: [X] (due next week)

Full notes: [Link to GitHub Issue]
```

## Decision Rights in This Forum

| Decision Type | Authority | Process |
|---------------|-----------|---------|
| Deployment approval for single service | Service owner | Can approve immediately if no blockers |
| Deployment with cross-team impact | Platform lead + affected owners | Discuss and reach consensus in forum |
| Escalation to leadership (EM/VP) | Incident commander or forum lead | Escalate immediately; document in GitHub issue |
| Ownership transfer | Service owner + EM | Propose in forum; finalize next week with 2-week transition plan |
| SLO modification | Platform lead + service owner | Discuss; create ADR if approved |

## Success Metrics

- **Attendance**: ≥90% of service teams represented each week
- **Duration**: Completes within 30 minutes (strict timeboxing)
- **Action completion**: ≥95% of action items completed by due date
- **Incident response**: All P0/P1 incidents reviewed within 24 hours of occurrence
- **Follow-up**: All decisions documented in GitHub issue within 1 hour of forum end

## Incident Escalation During Forum

If a **P0 incident occurs during the forum**:
1. Pause forum
2. Incident commander takes over
3. Assemble incident response team in #incidents Slack channel
4. Resume forum after incident stabilizes OR skip remainder if incident is critical

## Attendance Policy

- **Expected**: Mandatory for service owners and on-call engineers
- **If unavailable**: Send a delegate with pre-prepared status update
- **Repeated absence** (>2 consecutive weeks): Escalate to EM for coverage planning

## Calendar Invite Template

```
Title: Weekly Service Ownership Forum
When: Every Monday, 9:00 AM - 9:30 AM UTC
Recurring: Every week, indefinitely
Location: Zoom [to be added] + #service-ownership Slack
Attendees:
  - @instacommerce/platform
  - @instacommerce/identity
  - @instacommerce/search-catalog
  - @instacommerce/payments
  - @instacommerce/logistics
  - @instacommerce/engagement
  - @instacommerce/data-ml
  - @instacommerce/sre

Description:
Weekly service ownership forum for incident review, on-call handoff,
deployment coordination, and cross-team escalations. All service owners
and on-call engineers are expected to attend.

Meeting Link: [Zoom link]
Slack Channel: #service-ownership
Pre-meeting status: Post to Slack by Friday EOD
GitHub issues: [Link to issues list]
```

## References

- **Escalation Matrix**: docs/governance/escalation-matrix.md
- **CODEOWNERS**: .github/CODEOWNERS (service team assignments)
- **SLO Definitions**: docs/slos/service-slos.md
- **On-Call Runbook**: docs/runbooks/on-call-guide.md
