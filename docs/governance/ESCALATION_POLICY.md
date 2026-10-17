# Enhanced Escalation Policy

## Purpose

This policy defines escalation procedures for all incident severities (P0-P3), providing clear decision trees, response times, and communication procedures. All team members must understand and follow these escalation paths.

## Incident Severity Definitions

### P0: Critical Outage
**Definition**: Complete service outage or data loss affecting >10% of users; revenue-impacting

**Examples**:
- Payment processing down (customers cannot complete purchases)
- Checkout service completely unavailable
- Data loss or corruption in critical database
- Authentication service down (users locked out)
- Order service down (cannot place orders)

**Metrics**:
- >10% of requests failing
- >50% degradation in key service
- Revenue impact: Direct customer impact

**Response Requirement**: Immediate (within 5 minutes)

---

### P1: High-Priority Degradation
**Definition**: Significant degradation affecting user experience; major feature broken or service responding slowly

**Examples**:
- High latency (>5s p99 for critical service)
- Feature partially broken (some orders fail, but not all)
- Data inconsistency (sync delays >1 hour)
- Performance issue causing user complaints
- >5% of requests failing

**Metrics**:
- >5% of requests failing
- >200% increase in latency (vs. baseline)
- Affecting specific geography or user segment

**Response Requirement**: 15 minutes

---

### P2: Medium-Priority Issue
**Definition**: Limited impact with workaround available; not blocking critical user workflows

**Examples**:
- Single feature broken (reports page down, but order flow works)
- Non-critical service degraded
- Functionality working but slowly (acceptable but not ideal)
- One-off customer complaint with workaround
- <1% of requests failing

**Metrics**:
- <1% of requests failing
- Non-critical service
- Workaround exists

**Response Requirement**: 4 hours during business hours; next business day otherwise

---

### P3: Low-Priority Issue
**Definition**: Non-urgent; cosmetic or documentation issue; no customer impact

**Examples**:
- Documentation bug
- UI misalignment
- Minor typo in error message
- Feature request (not a bug)
- Test failure (not affecting production)

**Metrics**:
- Zero production impact
- No urgency
- Can be scheduled in backlog

**Response Requirement**: 1 business day

---

## P0 Escalation Procedure

### Initial Response (First 5 minutes)

**Who escalates**: On-call engineer, service owner, or anyone detecting the issue

**Immediate actions**:

1. **Page on-call** (all at once):
   - Service owner (via PagerDuty)
   - On-call SRE (via PagerDuty)
   - Platform lead (Slack @-mention + phone call)

2. **Create incident channel**:
   ```
   Slack: #incident-YYYYMMDD-[service-name]
   ```

3. **Post initial status**:
   ```
   🚨 P0 INCIDENT: [Service Name]
   Status: Investigating
   Impact: [Brief description]
   ETA for fix: Assessing
   Incident Commander: @[name]
   Updates: This channel
   ```

4. **Establish incident commander**:
   - First responder takes IC role
   - Can be service owner, SRE, or platform lead
   - IC coordinates and communicates

### Escalation Chain (if needed)

| Time | Action | Who | Contact Method |
|------|--------|-----|-----------------|
| 5 min | Initial response team mobilizing | Service owner, SRE, Platform lead | PagerDuty + Slack |
| 10 min | Engineering Manager + secondary on-call | EM, backup engineer | Phone + Slack |
| 15 min | Director/VP Engineering | Director | Phone call |
| 20 min | CTO (if data loss or security) | CTO | Phone call |
| 30 min | Executive escalation | VP Eng → VP Product → CEO | Phone call |

### P0 Communication Protocol

**Internal** (every 5-10 minutes):
- IC posts update to incident channel
- Format: Status / ETA / Current actions

**Broader team** (every 15 minutes):
- IC posts to #incidents channel
- Alert if escalating further

**Customers/External** (within 30 minutes):
- Status page update (status.instacommerce.com)
- Slack status message (if B2B customers)
- Email notification (if extended >1 hour)

### P0 Resolution & Handoff

1. **Incident stabilization**:
   - Service restored and stable (>10 minutes no errors)
   - IC confirms "All clear"

2. **Incident closure**:
   ```
   ✅ RESOLVED: [Service Name]
   Duration: [Start] - [End] ([X minutes])
   Root Cause: [Brief]
   Impact: [X users affected / $X revenue]
   Postmortem: [Scheduled for within 48 hours]
   ```

3. **Postmortem scheduling**:
   - Schedule within 48 hours
   - Invite: IC, on-call team, service owner, EM, SRE lead
   - Create GitHub issue with postmortem template

### P0 Postmortem Requirements

**Deadline**: 5 business days after incident

**Must include**:
- Timeline of events
- Root cause analysis
- Contributing factors
- Impact summary
- Preventive measures
- Follow-up action items with owners and deadlines

---

## P1 Escalation Procedure

### Initial Response (First 15 minutes)

**Who escalates**: On-call engineer or service owner

**Immediate actions**:

1. **Acknowledge** (within 5 minutes):
   - Service owner responds to page or Slack mention
   - Confirm they're investigating

2. **Create ticket**:
   - GitHub issue or incident tracking system
   - Tag: `severity-p1`, `incident`

3. **Notify team**:
   - Post to service team's Slack channel
   - Notify dependent services (downstream impact?)

4. **Begin investigation**:
   - Service owner starts troubleshooting
   - Assess root cause, mitigation options

### Escalation (if not improving)

| Time | Action | Condition |
|------|--------|-----------|
| 15 min | Service owner investigating | Initial acknowledgment |
| 30 min | Escalate to EM or secondary | No progress toward fix |
| 60 min | Escalate to Platform lead | Still no fix after 1 hour |
| 2 hours | Escalate to VP Engineering | Unresolved after 2 hours |

### P1 Communication

**Internal**:
- Service team Slack channel (every 15-30 min)
- Format: "Status: [Investigating/Mitigating/Identifying fix], ETA: [Time]"

**Broader team**:
- #incidents channel (hourly or if escalating)
- Alert if requiring cross-team support

**Customers** (if extended >2 hours):
- Status page update
- Slack/email if customer-facing

### P1 Resolution

1. **Fix deployed**:
   - Service owner confirms fix in production
   - Monitor for 15 minutes to ensure stable

2. **Incident closure**:
   ```
   ✅ RESOLVED: [Issue]
   Duration: [X hours/minutes]
   Root Cause: [Brief description]
   Mitigation: [How we fixed it]
   Follow-up: [Any further investigation needed?]
   ```

3. **Postmortem** (if >2 hours):
   - Schedule within 1 week
   - Lightweight postmortem (30 min)
   - Document root cause and preventive measures

---

## P2 Escalation Procedure

### Initial Response (First 4 hours)

**Who escalates**: Anyone (customer, engineer, QA)

**Actions**:

1. **Create GitHub issue**:
   - Clear title and description
   - Severity tag: `severity-p2`
   - Assign to service owner

2. **Notify service team**:
   - Post to Slack (not urgent, no page)
   - Add to sprint backlog for planning

3. **Assessment**:
   - Service owner assesses within 4 hours
   - Provide initial estimate (this sprint / next sprint / backlog)

### P2 Escalation (if impact increases)

If situation worsens or becomes P1, escalate per P1 procedure.

### P2 Resolution

- **Target**: Resolved within 1 week
- **Updates**: Weekly status in sprint meetings
- **Communication**: GitHub issue updates (no urgent Slack)

---

## P3 Escalation Procedure

### Initial Response (First 1 business day)

**Who escalates**: Anyone (typically engineer discovers during work)

**Actions**:

1. **Create GitHub issue** (low priority)
   - Tag: `severity-p3`, `backlog`
   - Can be picked up in future sprints

2. **No immediate notification**:
   - Not discussed in standups
   - No paging/escalation

### P3 Resolution

- **Target**: Include in next sprint or backlog
- **Communication**: GitHub issue only

---

## Emergency Escalation Procedures

### Security Issue

**Response time**: 1 hour

**Escalation chain**:
1. Security contact immediately
2. CTO + VP Engineering
3. Legal (if customer data involved)
4. CEO (if major breach)

**Actions**:
- Isolate affected systems if needed
- Preserve evidence
- Initiate incident response
- Notify affected customers (per policy)

### Data Loss Incident

**Response time**: Immediate

**Escalation chain**:
1. Service owner + SRE + Platform lead (immediately)
2. CTO + VP Engineering (within 5 min)
3. Finance + Legal (within 15 min)
4. CEO (within 30 min)

**Actions**:
- Assess data loss scope
- Determine recovery options
- Preserve audit trail
- Prepare customer communication
- Launch investigation

### Compliance/Regulatory Issue

**Response time**: 1 hour

**Escalation chain**:
1. Platform lead + Compliance (immediately)
2. CTO + VP Engineering
3. Legal team

**Actions**:
- Document timeline
- Assess compliance impact
- Notify regulatory bodies (if required)
- Create remediation plan

---

## Escalation Decision Tree

Use this tree to determine incident severity:

```
Is the service completely down?
├─ Yes → P0 (proceed to P0 escalation)
└─ No → Are >10% of requests failing?
    ├─ Yes → P0 (proceed to P0 escalation)
    └─ No → Is user experience significantly degraded?
        ├─ Yes → P1 (proceed to P1 escalation)
        └─ No → Is there a workaround?
            ├─ Yes → P3 (create GitHub issue, backlog)
            └─ No → P2 (proceed to P2 escalation)
```

---

## On-Call Engineer Responsibilities

### Before On-Call Shift
- [ ] Download PagerDuty app
- [ ] Confirm phone number for alerts
- [ ] Test Slack notifications
- [ ] Review recent incidents and postmortems
- [ ] Confirm understanding of escalation paths

### During On-Call Shift
- [ ] Keep device within arm's reach 24/7
- [ ] Respond to pages within SLA (5 min for P0, 15 min for P1)
- [ ] Acknowledge page, even if delegating to service owner
- [ ] Provide status updates every 5-15 minutes
- [ ] Coordinate escalation if needed
- [ ] Document incident timeline

### During Incident
- **5 minutes**: Initial response / "I'm working on it"
- **10 minutes**: Preliminary assessment
- **15 minutes**: Root cause or escalation plan
- **30 minutes**: Fix plan or escalation decision

### After On-Call Shift
- [ ] Handoff critical items to next on-call engineer
- [ ] Document any ongoing issues
- [ ] Attend postmortem if involved in P0/P1

---

## Communication Templates

### P0 Initial Alert
```
🚨 P0 INCIDENT: [Service Name]
Status: Investigating
Impact: [Description of user impact]
Affected: [Geography / user segment]
ETA: Assessing
Incident Commander: @[name]
Updates: #incident-YYYYMMDD-[service]
```

### P0 Status Update (every 5-10 min)
```
⏱ Update [Time]:
Status: [Investigating / Mitigating / Fixing]
Current actions: [What team is doing]
ETA: [Time estimate or "Assessing"]
Escalation: [Yes/No - if yes, describe]
```

### P0 Resolution
```
✅ RESOLVED: [Service Name]
Duration: [Start] - [End] ([X minutes])
Root Cause: [Brief technical description]
Impact: [X users / $X revenue]
Timeline:
  - [Time]: Issue started
  - [Time]: Detected
  - [Time]: Mitigation applied
  - [Time]: Fully resolved
Postmortem: [Scheduled for date]
```

### P1 Status (hourly)
```
📋 Status Update - P1: [Issue]
Time: [Current time]
Progress: [Brief update]
ETA for fix: [Time or "Assessing"]
Escalation: [Yes/No]
```

### P2 Status (daily)
```
📝 Status Update - P2: [Issue]
Status: [Investigating / Scheduled / In progress / Blocked]
Estimate: [Target completion date]
Notes: [Any blockers or updates]
```

---

## Escalation Metrics & Tracking

Monitor these metrics monthly:

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| P0 MTTR | <30 min | >45 min |
| P1 MTTR | <90 min | >2 hours |
| P0 acknowledgement time | <5 min | >10 min |
| P1 acknowledgement time | <15 min | >30 min |
| P0 postmortem completion | 100% within 5 days | <95% |
| Repeat P0 incidents | <1 per month | ≥2 in month |

**Reporting**: SRE team tracks and reports monthly in Reliability Review forum.

---

## Escalation Policy Exceptions

### On-Call Engineer Unavailable
- Try secondary on-call for service (usually listed in runbook)
- Escalate to platform on-call
- If unable to reach, escalate to EM/VP immediately

### Conflicting Priorities
- If handling one incident and another P0 occurs:
  - IC for first incident stays with it
  - On-call SRE joins second incident
  - Escalate first incident to secondary team

### After-Hours Concerns
- Never wait for business hours to escalate P0/P1
- Escalations are 24/7
- There is always someone on-call

---

## Training & Compliance

### Required Training
- All engineers: Annual escalation policy training
- On-call engineers: Policy review before first shift
- Managers: Escalation procedures and decision trees

### Auditing
- SRE reviews escalation adherence quarterly
- Post-mortems assess escalation effectiveness
- Trends reported to leadership

---

## References

- **On-Call Runbook**: docs/runbooks/on-call-guide.md
- **Incident Response Playbook**: docs/runbooks/incident-response.md
- **Service SLOs**: docs/slos/service-slos.md
- **Contact Directory**: [Link to on-call schedules]
