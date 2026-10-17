# Biweekly Contract Review Forum

## Overview

The Biweekly Contract Review Forum is a 45-minute synchronous meeting held every other Wednesday at 2:00 PM UTC. This forum ensures all changes to service contracts (APIs, events, schemas) are reviewed, approved, and coordinated across teams before implementation.

## Meeting Details

- **When**: Every other Wednesday, 2:00 PM UTC (alternating weeks)
- **Duration**: 45 minutes (strict timeboxing)
- **Attendees**:
  - Service architects/tech leads (affected by contract changes)
  - Principal engineer (contract authority)
  - Data platform lead (for data contract changes)
  - Platform team representative (cross-service impact analysis)
  - Optional: Product managers (for API versioning discussions)

- **Location**: #contract-reviews Slack channel + Zoom link
- **Calendar**: [Recurring invite to be created during implementation]
- **Slack Channel**: #contract-reviews (RFCs and async discussion)

## Pre-Meeting Preparation (Monday, EOD)

All contract change proposals must be submitted by Monday EOD (before the Wednesday forum) to allow:
- RFC document created in GitHub Discussions or as markdown in PR
- Automated schema validation completed
- Initial review by contract owner

## Agenda Template (45 min)

### 1. Opening & Agenda Review (3 min)
- Confirm attendees
- Review list of RFCs to discuss
- Note any time-sensitive contracts

### 2. Pending Change Proposals (20 min)
- **Per RFC** (max 5 min each, 4 RFCs max):
  - **Proposer presents**:
    - Summary of change
    - Affected services (producers/consumers)
    - Backward compatibility assessment
    - Migration plan
  - **Group discusses**:
    - Risks and mitigations
    - Consumer impact
    - Timeline feasibility
  - **Decision**:
    - ✅ Approved (can proceed to implementation)
    - 🔄 Conditional approval (with specific requirements)
    - ❌ Rejected (with feedback for revision)

### 3. Breaking Change Deep-Dive (if applicable) (15 min)
- Only if a breaking change requires detailed discussion
- Review migration plan step-by-step
- Confirm coordination with all affected teams
- Define rollback procedure

### 4. Schema Registry & Version Management (5 min)
- New schema versions registered this period
- Deprecated schemas reaching sunset date
- Version cleanup (if applicable)

### 5. Closing (2 min)
- Summarize decisions
- Confirm timelines with proposers
- Preview agenda for next forum (2 weeks)

## Meeting Output

### GitHub Issue Template

Each forum meeting creates a GitHub Issue tagged `governance`, `contract-review`, and `biweekly`.

**Issue Title**: `Biweekly Contract Review - [Date]`

**Issue Body** (template):

```markdown
## Forum Date
- **Date**: [Wednesday date]
- **Attendees**: [List]

## RFCs Reviewed

### RFC 1: [Title]
- **Proposer**: @[name]
- **Status**: ✅ Approved / 🔄 Conditional / ❌ Rejected
- **Affected Services**: [List producers and consumers]
- **Change Type**: [Additive / Backward-compatible / Breaking]
- **Migration Period**: [Duration or N/A]
- **Approved By**: @[principal engineer]
- **Implementation Start**: [Date or TBD]
- **Issues/Comments**: [Link to GitHub Discussion]

### RFC 2: [Title]
- [Same structure as RFC 1]

## Schema Registry Updates

| Schema | Version | Status | Sunset Date |
|--------|---------|--------|-------------|
| [Schema] | v[N] | [New/Deprecated/Active] | [Date or -] |

## Breaking Changes Summary

| Change | Impact | Timeline | Mitigation |
|--------|--------|----------|-----------|
| [Change] | [Services affected] | [Duration] | [Rollback plan] |

## Decisions Made

- **Decision 1**: [Context and rationale]
- **Decision 2**: [Context and rationale]

## Action Items

- [ ] Implement RFC 1 - Owner: @[proposer], Due: [date]
- [ ] Update schema registry - Owner: @[owner], Due: [date]
- [ ] Notify consumers of breaking change - Owner: @[owner], Due: [date]

## Follow-Ups for Next Forum

- [ ] [Follow-up] - Owner: @[owner]

---
**Meeting Notes**: [Link to Slack thread]
```

### Slack Notification

```
📋 Biweekly Contract Review Complete

RFCs Reviewed: [X]
  ✅ Approved: [X]
  🔄 Conditional: [X]
  ❌ Rejected: [X]

Breaking Changes: [X] (with migration plan)
Schema Versions: [X] new registered

Next Forum: [Date in 2 weeks]
Full notes: [Link to GitHub Issue]
```

## Change Review Criteria

### For All Changes

- [ ] Schema changes documented
- [ ] Affected consumers identified
- [ ] Backward compatibility assessed
- [ ] Monitoring alerts planned
- [ ] Consumer notification planned

### For Backward-Compatible Changes

- [ ] Old and new versions both supported
- [ ] Migration period defined (if deprecating)
- [ ] Deployment order coordinated

### For Breaking Changes

- [ ] RFC discusses motivation
- [ ] All affected teams have reviewed and approved
- [ ] Migration plan with concrete timeline (30-90 days minimum)
- [ ] Rollback procedure documented
- [ ] Consumer compliance verified before sunset
- [ ] Communication plan to all stakeholders

## Decision Rights in This Forum

| Decision Type | Authority | Process |
|---------------|-----------|---------|
| Approve additive change | Principal engineer | Can approve if no concerns raised |
| Approve backward-compatible change | Principal engineer + affected team leads | Consensus required |
| Approve breaking change | Principal engineer + all consumer teams | Must have explicit approval from all affected |
| Reject change | Principal engineer | With feedback for revision |
| Extend migration period | Principal engineer + EM | Rare; only if data consistency risk exists |

## Escalation Scenarios

### Scenario: Consumer team cannot meet migration deadline

1. Escalate to service owner and EM
2. Options:
   - Extend migration period
   - Split migration into phases
   - Provide backward compatibility layer temporarily
3. Document decision in GitHub issue

### Scenario: Change affects multiple dependent services

1. Ensure all affected teams are in forum
2. Define deployment order carefully
3. Create kanban board to track implementation across teams
4. Link all PRs to GitHub issue for visibility

### Scenario: Breaking change needed urgently (security/compliance)

1. Emergency contract review (can occur outside normal cadence)
2. Fast-track process: 48 hours notice to all teams
3. Mandatory attendance for affected service owners
4. Document emergency justification in ADR

## Schema Versioning Rules

All changes must follow [Contract Review Process](../contract-review-process.md) versioning rules:

- **Additive changes only**: Same version (no bump needed)
- **Breaking changes**: New version (v1 → v2)
- **Deprecation**: Mark old version with sunset date
- **Cleanup**: Remove deprecated versions 90 days after sunset

### Schema Naming Convention

```
contracts/src/main/resources/schemas/{domain}/{EventName}.v{N}.json
```

Example:
```
OrderCreated.v1.json   # Original
OrderCreated.v2.json   # Added required field (breaking)
```

## Calendar Invite Template

```
Title: Biweekly Contract Review Forum
When: Every other Wednesday, 2:00 PM - 2:45 PM UTC
Recurring: Every 2 weeks, alternating weeks
Location: Zoom [to be added] + #contract-reviews Slack
Attendees:
  - @instacommerce/principal-engineer
  - @instacommerce/platform
  - @instacommerce/search-catalog
  - @instacommerce/payments
  - @instacommerce/logistics
  - @instacommerce/engagement
  - @instacommerce/data-ml
  - @instacommerce/product

Description:
Biweekly forum for reviewing and approving service contract changes:
- API schema changes
- Event payload changes
- Breaking change approvals
- Migration plan coordination

RFCs must be submitted by Monday EOD.
Slack Channel: #contract-reviews
RFC Template: [Link to RFC template]
```

## RFC Template for Contract Changes

```markdown
# RFC: [Change Title]

## Summary
[One paragraph overview of the contract change]

## Motivation
[Why is this change needed? Business driver, technical debt, etc.]

## Affected Services

### Producers
- [Service]: [Current behavior]

### Consumers
- [Service 1]: [Current usage]
- [Service 2]: [Current usage]

## Contract Change Details

### Current Schema (v[N])
[Link to schema or inline JSON]

### Proposed Schema (v[N+1])
[Link to proposed schema or inline JSON]

### Change Type
- [ ] Additive (new optional fields)
- [ ] Backward-compatible (field deprecation with migration)
- [ ] Breaking (field removal, type change, required field change)

## Backward Compatibility Assessment

[Explain how this change affects consumers. If breaking, explain why it's necessary.]

## Migration Plan

[If breaking or deprecating, provide step-by-step migration plan with timeline.]

1. Phase 1 (Week 1): [Step]
2. Phase 2 (Week 2): [Step]
3. Phase 3 (Week 3): [Step]

## Timeline

- RFC Submitted: [Date]
- Contract Review Forum: [Date]
- Implementation Start: [Date]
- Consumers Updated: [Date]
- Old Version Sunset: [Date]

## Rollback Plan

[Describe how to revert if issues arise during or after migration.]

## Monitoring & Alerts

[What metrics/logs will we monitor? What alerts should be configured?]

## References

- [Link to related issues/discussions]
- [Link to schema file]
- [Link to affected service docs]
```

## Success Metrics

- **Attendance**: ≥80% of affected service leads each forum
- **Duration**: Completes within 45 minutes
- **RFC review time**: ≤1 week from submission to decision
- **Consumer notification**: ≥95% before migration starts
- **Breaking changes approved**: 100% compliance with 60+ day migration period
- **Schema accuracy**: 100% of registered versions validated against consumers

## References

- **Full Contract Review Process**: docs/governance/contract-review-process.md
- **Schema Registry**: contracts/
- **ADRs on contracts**: docs/adr/ (search for "contract" or "schema")
- **Service APIs**: [Link to API documentation]
