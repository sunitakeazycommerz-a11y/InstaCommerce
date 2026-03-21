# Service Ownership Charter

## Purpose

This charter defines the responsibilities and expectations for service ownership at InstaCommerce. Every production service must have a clearly defined owning team responsible for its reliability, security, and evolution.

## Service Ownership Model

### Primary Owner Responsibilities

1. **On-Call Rotation**
   - Maintain 24/7 on-call coverage
   - Respond to pages within SLA (see [Escalation Matrix](./escalation-matrix.md))
   - Conduct post-incident reviews

2. **SLO Ownership**
   - Define and maintain service SLOs
   - Monitor error budget consumption
   - Prioritize reliability work when budgets are depleted

3. **Code Quality**
   - Review and approve all PRs to owned services
   - Maintain test coverage above 80%
   - Address security vulnerabilities within SLA

4. **Documentation**
   - Keep service README current
   - Maintain runbooks for operational procedures
   - Document API contracts and breaking changes

5. **Dependency Management**
   - Track upstream/downstream dependencies
   - Coordinate with dependent teams for changes
   - Maintain healthy dependency relationships

### Secondary Owner Responsibilities

Secondary owners provide backup coverage and expertise sharing:
- Participate in on-call rotation
- Review PRs when primary owners are unavailable
- Cross-train on service internals

## Weekly Service Ownership Forum

**Schedule**: Every Monday, 9 AM UTC

### Agenda Template

1. **Incident Review** (15 min)
   - P0/P1 incidents from past week
   - Action items from post-mortems

2. **Ownership Updates** (10 min)
   - Team changes affecting ownership
   - New services requiring owners

3. **On-Call Handoff** (10 min)
   - Outstanding issues from previous week
   - Upcoming maintenance windows

4. **Open Discussion** (10 min)
   - Cross-team coordination needs
   - Escalation requests

### Attendance Requirements

- At least one representative from each service-owning team
- On-call engineers from incoming and outgoing shifts
- SRE team representative

## Service Ownership Matrix

| Service | Primary Team | Secondary Team | Escalation |
|---------|--------------|----------------|------------|
| identity-service | team-identity | team-security | @identity-oncall |
| order-service | team-order | team-checkout | @order-oncall |
| payment-service | team-payments | team-compliance | @payments-oncall |
| fulfillment-service | team-fulfillment | team-warehouse | @fulfillment-oncall |
| checkout-orchestrator-service | team-checkout | team-order | @checkout-oncall |

*See [CODEOWNERS](../../.github/CODEOWNERS) for complete mapping*

## Ownership Transfer Process

1. **Initiate Transfer**
   - Current owner proposes transfer at Weekly Forum
   - Document reason and timeline

2. **Knowledge Transfer**
   - Minimum 2-week overlap period
   - Shadow on-call rotation
   - Documentation review and update

3. **Handoff Completion**
   - Update CODEOWNERS file
   - Transfer monitoring dashboards
   - Update escalation contacts

4. **Validation**
   - New owner handles at least one on-call shift
   - Confirm runbook completeness
   - Announce ownership change to stakeholders
