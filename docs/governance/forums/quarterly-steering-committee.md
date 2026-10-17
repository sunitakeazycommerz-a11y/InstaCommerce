# Quarterly Steering Committee Forum

## Overview

The Quarterly Steering Committee Forum is a 90-minute strategic gathering held in the first week of each new quarter (April, July, October, January). This forum aligns on platform roadmap, major architecture initiatives, cross-team dependencies, and quarterly OKRs.

## Meeting Details

- **When**: First week of Q+1 (April, July, October, January)
- **Duration**: 90 minutes
- **Attendees**:
  - VP Engineering (executive sponsor)
  - CTO (technical authority)
  - Engineering managers (all teams)
  - Principal engineer / tech lead (platform)
  - Service owners (all 28 teams, or representatives)
  - Product leadership (for roadmap alignment)
  - Optional: Company leadership (e.g., CEO, CFO) for strategic alignment

- **Location**: Large conference room or video conference
- **Calendar**: [To be scheduled quarterly]
- **Slack Channel**: #steering-quarterly (async discussion)

## Pre-Meeting Preparation (Wednesday of first week)

Each team prepares:

1. **Quarterly OKRs** (Objectives & Key Results)
   - 3-5 objectives for the quarter
   - 2-3 measurable key results per objective
   - Clear linkage to platform strategy
   - Resource requirements

2. **Technical Roadmap**
   - Major features/initiatives for the quarter
   - Estimated effort (story points or staff-weeks)
   - Dependencies on other teams
   - Risk assessment

3. **Architecture Proposals**
   - Any major architectural changes
   - Breaking changes requiring multi-team coordination
   - Technology upgrades (frameworks, libraries, infrastructure)

4. **Capacity Plan**
   - Available engineer headcount
   - Known leaves/constraints
   - Hiring plans (if relevant)

These are consolidated into a **Quarterly Strategy Document** by VP Engineering and shared for async review by Tuesday EOD.

## Agenda Template (90 min)

### 1. Opening & Context (5 min)
- Welcome and executive priorities (VP Engineering)
- Recap of last quarter's OKRs achievement
- Strategic context for this quarter

### 2. Quarterly OKRs Alignment (25 min)
- **By platform area** (5 areas × 5 min each):
  1. Frontend & Engagement (Mobile BFF, Notification, etc.)
  2. Order Fulfillment (Order, Checkout, Fulfillment, Warehouse, Logistics)
  3. Payments & Compliance (Payment, Reconciliation, Audit)
  4. Search & Discovery (Catalog, Search, Pricing)
  5. Platform & Data (Infrastructure, Identity, Data Platform, AI)

- **Per area, review**:
  - Proposed OKRs
  - Key results measurability
  - Resource alignment
  - Cross-team dependencies
  - Leadership approval

### 3. Cross-Team Dependency Mapping (15 min)
- **Dependency matrix** displayed on screen
- Call out high-risk dependencies
- Sequence work to minimize blocking
- Identify which projects can run in parallel

### 4. Architecture & Technology Roadmap (20 min)
- **Major initiatives** this quarter (max 3-4):
  - What: [Initiative title]
  - Why: [Business/technical driver]
  - Who: [Owning team]
  - Timeline: [Phases and milestones]
  - Risk: [Key risks and mitigations]

- **Technology decisions**:
  - Framework/library upgrades
  - New infrastructure components
  - Breaking changes requiring coordination

### 5. Capacity & Resource Planning (15 min)
- Total headcount available across teams
- Known constraints (leaves, hiring gaps)
- Allocation against OKRs
- Risk indicators (understaffed areas)

### 6. Strategic Initiatives & Risks (10 min)
- Company-level strategic bets affecting engineering
- Market/competitive pressures
- Regulatory/compliance requirements
- Known risks to platform stability

### 7. Closing: Commitments & Next Steps (5 min)
- Confirm Q OKRs and ownership
- Identify follow-up meetings needed
- Schedule mid-quarter check-in (Week 7)

## Meeting Output

### Quarterly Strategy Document

A comprehensive 10-20 page document published to company wiki/Google Drive:

**Structure**:
```
# Q[N] [YEAR] Platform Strategy

## Executive Summary
[One-page summary of OKRs, resource plan, key risks]

## Platform OKRs
### Frontend & Engagement
- Objective 1: [Description]
  - KR 1: [Measurable outcome]
  - KR 2: [Measurable outcome]
- [Objective 2, 3, 4...]

### [Repeat for all 5 platform areas]

## Technical Roadmap
### Major Initiatives (Q[N])
1. **Initiative 1**: [Title and description]
   - Owning team: [Team]
   - Duration: [Weeks]
   - Key milestones: [List]
   - Dependencies: [List]
   - Risk: [High/Medium/Low - description]

2. [Initiative 2...]

### Capability Improvements
- [Ongoing improvements without major rollout]

## Technology Decisions
### Framework/Library Upgrades
- [Spring Boot 5.0 adoption - Phase 2]

### New Infrastructure
- [New component and integration]

## Capacity Allocation

| Team | FTE | OKR Work | Tech Debt | Risk Buffer |
|------|-----|----------|-----------|------------|
| Platform | [X] | [%] | [%] | [%] |
| [Team] | [X] | [%] | [%] | [%] |

## Risk Register

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|-----------|
| [Risk 1] | [High/Med/Low] | [High/Med/Low] | [Plan] |

## Cross-Team Dependencies

[Dependency matrix showing sequences and critical paths]

## Mid-Quarter Check-in
- Scheduled for: [Date]
- Focus: [Key uncertainties or risks]

## Key Success Metrics
- [KPI 1]: Target [X]
- [KPI 2]: Target [X]
```

### GitHub Issues (per team)

Each team creates a GitHub Issue for quarterly tracking:

**Issue Title**: `Q[N] OKRs - [Team Name]`

**Issue Body**:

```markdown
## Quarterly Objectives & Key Results

### Objective 1: [Description]
- **KR 1**: [Measurable outcome] (Target: [X])
- **KR 2**: [Measurable outcome] (Target: [X])
- **Owner**: @[owner]
- **Status**: 🟢 On-track / 🟡 At-risk / 🔴 Off-track

### Objective 2: [Description]
[Same structure]

## Key Initiatives
1. [Initiative] - Lead: @[owner], Effort: [Story points], Due: [Date]
2. [Initiative] - ...

## Dependencies
- Depends on: [Team/Initiative]
- Enables: [Team/Initiative]

## Risks
- [Risk 1]: [Mitigation plan]
- [Risk 2]: [Mitigation plan]

## Resource Plan
- FTE Allocation: [X] engineers
- Known constraints: [Leaves, hiring gaps, etc.]
- Capacity buffer: [%] for incidents/ad-hoc work

## Mid-Quarter Status
[Updated at Week 7 of quarter]

## End-of-Quarter Results
[Updated at quarter end with achievement rate]
```

### Slack Announcement

```
🎯 Q[N] Steering Committee Complete

Platform OKRs: [Summary of 3-5 key objectives]
Key Initiatives: [Count and brief descriptions]
Resource Allocation: [FTE/team allocation]
Major Risks: [Count and severity]

Full strategy: [Link to doc]
Team OKRs: [Link to GitHub issues]

Mid-quarter check-in: [Date]
```

## Decision Rights in This Forum

| Decision Type | Authority | Process |
|---------------|-----------|---------|
| Approve OKRs | VP Engineering + leadership | Consensus on objectives; may negotiate key results |
| Approve major initiative | CTO + VP Engineering | Technical feasibility + resource availability |
| Prioritize when conflicts arise | VP Engineering | Final decision on sequencing and resource allocation |
| Escalate resource gap | VP Engineering | May require hiring plan or executive decision |
| Defer initiative to later quarter | CTO + VP Engineering | Acceptable if dependencies not met or risks too high |
| Approve breaking changes/upgrades | CTO + affected teams | Consensus required; impacts multiple teams |

## Strategic Planning Process

### Pre-Forum Preparation (1-2 weeks before)

1. **Product inputs**: Product leadership shares strategic drivers and market priorities
2. **Finance inputs**: Budget constraints and hiring plans (if any)
3. **Engineering leadership**: Consolidates team inputs and creates draft strategy
4. **Async review**: Shared document for team comment by Friday before

### During Forum

1. Present consolidated strategy and proposed OKRs
2. Discuss cross-team dependencies and sequencing
3. Make final decisions on priorities and resource allocation
4. Identify follow-up meetings needed

### Post-Forum Execution (by Friday of first week)

1. Publish final Quarterly Strategy Document
2. Create GitHub issues for tracking per team
3. Schedule mid-quarter check-in (2-3 weeks)
4. Communicate OKRs to all teams

### Mid-Quarter Check-In (Week 7)

- Lightweight forum (30-45 min)
- Review progress on OKRs
- Adjust if major blockers emerged
- Confirm on-track vs at-risk status

### Quarter-End Review

- Full retrospective in first forum of next quarter
- OKR achievement rate
- Lessons learned
- Course correction for next quarter

## Calendar Invite Template

```
Title: Quarterly Steering Committee - Q[N] [YEAR]
When: [First week of quarter], [Day] [Time] UTC
Duration: 90 minutes
Location: [Conference Room/Video Link]
Attendees:
  - @VP_Engineering (organizer)
  - @CTO
  - @VP_Product
  - All Engineering Managers
  - Principal Engineer
  - Tech leads (1 per team)

Description:
Strategic planning forum for Q[N]. Reviews quarterly OKRs, technical
roadmap, cross-team dependencies, and resource allocation.

Pre-forum materials: [Link to Google Drive/wiki]
Strategy document: [Will be shared Tuesday before]
GitHub tracking: [Link to OKR issues]

This is a mandatory forum for engineering leadership and team leads.
Service owner attendance is strongly encouraged.
```

## Success Metrics

- **Attendance**: 100% of engineering leadership; ≥80% of team leads
- **Duration**: Completes within 90 minutes
- **OKR clarity**: 100% of OKRs have measurable key results
- **Dependency mapping**: 100% of cross-team dependencies identified
- **OKR achievement**: ≥80% of committed OKRs completed within quarter
- **Mid-quarter adjustment**: ≤20% change to OKRs mid-quarter (indicates good planning)

## Integration with Other Forums

- **Monthly Reliability Forum**: Reviews platform stability against quarterly OKRs
- **Biweekly Contract Reviews**: Implements technical roadmap decisions from Steering
- **Weekly Service Ownership**: Executes on quarterly OKRs and reports blockers

## References

- **OKR Framework**: [Link to company OKR documentation]
- **Strategic Context**: [Link to company strategy/roadmap]
- **ADRs**: docs/adr/ (major architecture decisions)
- **Roadmap Tracking**: [GitHub Projects or Jira board]
- **Capacity Planning**: [Link to resource management doc]
