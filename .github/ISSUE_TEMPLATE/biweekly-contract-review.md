---
name: Biweekly Contract Review Forum
about: Tracking forum for service contract changes, schema approvals, and breaking change coordination
title: "Biweekly Contract Review - [Date]"
labels: ["governance", "contract-review", "biweekly"]
assignees: []
---

## Forum Date
- **Date**: [Wednesday date]
- **Attendees**: [List names]
- **Principal Engineer Lead**: @[principal-engineer]

---

## RFCs Reviewed This Period

### RFC 1: [Contract Change Title]

- **Proposer**: @[proposer]
- **Status**: ✅ Approved / 🔄 Conditional / ❌ Rejected
- **Change Type**: [Additive / Backward-compatible / Breaking]
- **Affected Services**:
  - **Producer(s)**: [Service1, Service2]
  - **Consumer(s)**: [Service1, Service2, Service3]
- **Implementation Timeline**: [Week X - Week Y]
- **Issues/Discussions**: [Link to GitHub Discussion]

**Decision Rationale**: [Why was this decision made?]

---

### RFC 2: [Contract Change Title]
[Same format as RFC 1]

---

## Schema Registry Updates

| Schema Name | Version | Status | Sunset Date | Notes |
|-------------|---------|--------|-------------|-------|
| [SchemaName] | v[N] | [New/Active/Deprecated] | [Date or -] | [Notes] |

---

## Breaking Changes Coordination

### Change 1: [Title]
- **Migration Period**: [Start Date] - [End Date] ([X weeks])
- **Old Version Sunset**: [Date]
- **Consumer Readiness**: [# consumers ready / total consumers]
- **Rollback Plan**: [Brief description]

### Change 2: [Title]
[Same structure]

---

## Approval Summary

- **Total RFCs**: [X]
  - ✅ **Approved**: [X]
  - 🔄 **Conditional**: [X]
  - ❌ **Rejected**: [X]

- **Breaking Changes**: [X]
- **New Schema Versions**: [X]
- **Deprecated Schemas**: [X]

---

## Action Items

- [ ] Implement RFC 1 - Owner: @[proposer], Due: [date]
- [ ] Update consumers of breaking change - Owner: @[owner], Due: [date]
- [ ] Register new schemas in Schema Registry - Owner: @[owner], Due: [date]
- [ ] Remove deprecated schemas (post-sunset) - Owner: @[owner], Due: [date]

---

## Decisions Made

- **Decision 1**: [Context and reasoning]
- **Decision 2**: [Context and reasoning]

---

## Follow-Ups for Next Forum

- [ ] [Follow-up] - Owner: @[owner], Due: [date]

---

## Quick Links

- **Contract Review Process**: docs/governance/contract-review-process.md
- **Schema Registry**: contracts/
- **RFC Discussions**: [Link to GitHub Discussions]
- **Slack Channel**: #contract-reviews
