# InstaCommerce Governance

This directory contains governance documentation for the InstaCommerce platform, including service ownership, contract review processes, reliability standards, and escalation procedures.

## Quick Links

| Document | Purpose | Cadence |
|----------|---------|---------|
| [Service Ownership Charter](./service-ownership-charter.md) | Team responsibilities, on-call rotation | Weekly (Monday 9 AM UTC) |
| [Contract Review Process](./contract-review-process.md) | Schema changes, API versioning | Biweekly (Wednesday 2 PM UTC) |
| [Reliability Forum](./reliability-forum.md) | SLO review, error budgets | Monthly (First Friday 10 AM UTC) |
| [Escalation Matrix](./escalation-matrix.md) | P0-P3 response times | On-demand |

## Governance Forums

### 1. Weekly Service Ownership Forum
- **When**: Monday 9 AM UTC
- **Who**: Service owners, on-call engineers
- **Purpose**: Review incidents, ownership changes, on-call handoffs

### 2. Biweekly Contract Review
- **When**: Wednesday 2 PM UTC  
- **Who**: Architecture team, affected service owners
- **Purpose**: Review schema changes, approve breaking changes

### 3. Monthly Reliability Forum
- **When**: First Friday 10 AM UTC
- **Who**: SRE team, service owners, leadership
- **Purpose**: SLO compliance review, error budget tracking

### 4. Quarterly Steering Committee
- **When**: First week of each quarter
- **Who**: Engineering leadership, architecture, product
- **Purpose**: Strategic roadmap, major architecture decisions

## Code Ownership

See [CODEOWNERS](../../.github/CODEOWNERS) for the authoritative mapping of code paths to responsible teams.

## Contact

- Platform Team: #platform-team (Slack)
- Architecture: #architecture (Slack)
- SRE: #sre-oncall (Slack)
