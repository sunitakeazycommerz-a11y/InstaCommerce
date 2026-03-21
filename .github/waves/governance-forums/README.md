# Wave 38 Governance Activation - Index

**Activation Date**: 2026-03-21

This directory contains the calendar and coordination documents for InstaCommerce's governance framework.

## Files

### 1. ACTIVATION_REPORT.md
**What**: Complete verification report of Wave 38 governance framework
**Contents**:
- Checklist of all governance components verified
- Service coverage (29 services across 8 teams)
- SLO targets for all services
- Alert threshold configuration
- Escalation paths
- CODEOWNERS enforcement status
- Integration points (GitHub, Slack, Prometheus, PagerDuty)

**Use When**: Need to verify governance framework status or audit compliance

---

### 2. FORUMS_SCHEDULE.md
**What**: Master calendar for all governance forums
**Contents**:
- Weekly Service Ownership Review (Monday 9:00 AM UTC)
- Biweekly Contract Review (Wednesday 2:00 PM UTC)
- Monthly Reliability + Governance Review (First Friday 10:00 AM UTC)
- Quarterly Service Architecture Steering (First week of Q+1, 3:00 PM UTC)

**For Each Forum**:
- Cadence and duration
- Attendee list
- Full agenda
- Decision gates
- Next 12 months of scheduled dates
- Time zone conversions (EST, PST, IST, CET)

**Use When**:
- Adding to team calendars
- Scheduling governance events
- Understanding forum responsibilities
- Checking meeting times in different time zones

---

## Key Documents (Master Files)

### Governance Model
- **File**: `docs/governance/OWNERSHIP_MODEL.md`
- **Contains**: Governance structure, responsibilities, forums, escalation paths, team directory

### Service Ownership Assignments
- **File**: `.github/CODEOWNERS`
- **Contains**: 29 services mapped to 8 team groups with branch protection rules

### SLO Targets
- **File**: `docs/slos/service-slos.md`
- **Contains**: SLO targets for all 29 services (availability, latency, error rate, accuracy)

### Alert Rules
- **File**: `docs/observability/slo-alerts.md`
- **Contains**: 50+ Prometheus alert rules with burn-rate thresholds

### SLO Policy
- **File**: `docs/adr/015-slo-error-budget-policy.md`
- **Contains**: Multi-window burn-rate policy, decision rationale

---

## Quick Reference

### Forum Calendar Summary

| Forum | When | Duration | Attendees |
|-------|------|----------|-----------|
| Weekly Ownership Review | Mon 9 AM UTC | 30 min | Service owner, EM, SRE |
| Biweekly Contract Review | Wed 2 PM UTC (alt weeks) | 45 min | Service owners, data platform, tech lead |
| Monthly Reliability Review | 1st Friday 10 AM UTC | 60 min | Platform, SRE, EM leads, security |
| Quarterly Steering | 1st week of Q+1, 3 PM UTC | 90 min | Principal engineer, tech leads, CTO |

### Service Teams (CODEOWNERS)

```
@instacommerce/identity          (2 services)
@instacommerce/platform          (8 services)
@instacommerce/search-catalog    (3 services)
@instacommerce/payments          (4 services)
@instacommerce/logistics         (6 services)
@instacommerce/engagement        (2 services)
@instacommerce/data-ml           (3 services)
@instacommerce/sre               (infrastructure)
```

### Critical SLOs

| Service | Availability | P99 Latency | Special |
|---------|--------------|-------------|---------|
| Payment | 99.95% | <300ms | Webhook 99.99% |
| Order | 99.9% | <500ms | Idempotency 100% |
| Checkout | 99.9% | <2s | Timeout SLO 100% |
| Search | 99% | <200ms | Freshness <5min |
| Reconciliation | 99.5% | <30s | Accuracy 100% |

---

## Wave 38 Status

### Activation Timeline
- ✅ **Admin JWT Auth** (Wave 34 Track A) - Complete
- ✅ **Per-Service Token Scoping** (Wave 34 Track B) - Complete
- ✅ **Feature Flag Cache Invalidation** (Wave 35) - Complete
- ✅ **Reconciliation Engine** (Wave 36) - Complete
- ✅ **Integration Tests** (Wave 37) - Complete
- ✅ **Governance Framework** (Wave 38) - ACTIVE (This activation)

### What's Active Now
- 4 standing governance forums
- Service ownership with CODEOWNERS enforcement
- SLO definitions for all 29 services
- 50+ Prometheus alert rules with burn-rate windows
- Escalation paths documented and ready
- Calendar schedule synchronized with all time zones

### First Meetings Scheduled
- **Monday, March 24, 2026**: Weekly Service Ownership Review #1
- **Wednesday, April 2, 2026**: Biweekly Contract Review #1
- **Friday, April 4, 2026**: Monthly Reliability Review #1
- **Monday, April 7, 2026**: Quarterly Steering #1 (Q2 planning)

---

## Communication Channels

### Governance-Specific
- `#governance` - Policy discussions and announcements
- `#reliability` - SLO breach notifications and monthly reviews
- `#incidents` - P0/P1 incident coordination

### Team-Specific
- `#payments-team`, `#platform-team`, `#logistics-team`, etc.

### Reporting
- GitHub Issues: Use `governance` or `slos` labels
- GitHub Discussions: Use `contracts` tag for breaking changes
- GitHub Project Boards: Service Health Tracking, Architecture Roadmap

---

## Contact

**Governance Framework Owner**: `@instacommerce/platform`
**SLO & Observability**: `@instacommerce/sre`
**Escalation**: CTO for strategic questions

---

**Last Updated**: 2026-03-21
**Status**: ACTIVE
