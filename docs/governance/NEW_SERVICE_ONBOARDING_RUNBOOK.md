# New Service Onboarding Runbook

## Purpose

This runbook guides engineering teams through setting up governance, ownership, and operational procedures for new services being added to the InstaCommerce platform.

## When to Use This Runbook

- **Trigger**: New service entering development (usually Wave planning)
- **Owner**: Platform team lead + Service owner
- **Timeline**: 2-3 weeks before production deployment

## Prerequisites

Before starting this runbook, ensure:

- [ ] Service is approved in quarterly steering (has OKR/roadmap commitment)
- [ ] Initial design review completed (architecture/contracts approved)
- [ ] Service repository created in GitHub
- [ ] Team assigned and named (e.g., `@instacommerce/[team-name]`)
- [ ] Service dependencies documented

---

## Phase 1: Ownership & Governance Setup (Week 1)

### Step 1.1: Define Service Team

**Owner**: Service owner + EM lead

**Actions**:

1. Identify primary service owner (engineer who understands service best)
2. Identify secondary owner (backup for on-call coverage)
3. Identify EM lead (engineering manager responsible for team)
4. Create GitHub team (format: `@instacommerce/[team-name]`)

**Verification**:

- [ ] GitHub team created and active
- [ ] All team members have GitHub access
- [ ] Team has appropriate permissions in repository

---

### Step 1.2: Update CODEOWNERS File

**Owner**: Platform team lead

**Actions**:

1. Add service directory to `.github/CODEOWNERS`:

   ```
   /services/[new-service]/                 @instacommerce/[team-name]
   ```

2. If applicable, add other teams for cross-ownership:

   ```
   /services/[new-service]/                 @instacommerce/[team-name] @instacommerce/platform
   ```

3. Create PR and get approval from platform lead

**Verification**:

- [ ] CODEOWNERS file updated and merged to master
- [ ] GitHub recognizes new team as code owners
- [ ] Team receives review requests for PRs in service directory

---

### Step 1.3: Add to Governance Framework

**Owner**: Platform team lead

**Actions**:

1. Add service to team directory in `docs/governance/OWNERSHIP_MODEL.md`:

   ```markdown
   | [Service Name] | @[team-name] | [Team Lead], [On-Call Lead] | [Service type] |
   ```

2. Create Slack channel: `#[service-name]-team`
   - Used for service-specific discussions
   - Linked in runbook

3. Add to service-ownership tracking spreadsheet (if maintained)

**Verification**:

- [ ] Service listed in governance model
- [ ] Slack channel created and team invited
- [ ] Documentation updated in master

---

## Phase 2: SLOs & Operational Readiness (Week 1-2)

### Step 2.1: Define Service SLOs

**Owner**: Service owner + SRE lead

**Actions**:

1. Determine service tier (Critical / Standard / Best-effort):
   - **Critical**: Revenue-impacting, customer-facing (99.9% SLO)
   - **Standard**: Important but not revenue-blocking (99.5% SLO)
   - **Best-effort**: Internal or batch services (99% SLO)

2. Create SLO document in `docs/slos/service-slos.md`:

   ```markdown
   ## [Service Name] SLOs

   **Tier**: Critical / Standard / Best-effort
   **Availability Target**: 99.9% / 99.5% / 99%
   **Latency P99**: [Xms]
   **Error Rate**: [X%]
   **Monthly Error Budget**: [X minutes]
   ```

3. Define SLO breach policy:
   - When budget exhausted: Reliability sprint required
   - Notification: SRE team + service owner

4. Create Grafana dashboard (see Step 4.1)

**Verification**:

- [ ] SLO document merged to master
- [ ] SLO values are realistic for service type
- [ ] Error budget calculations reviewed by SRE
- [ ] Dashboard created and metrics flowing

---

### Step 2.2: Create Service Runbooks

**Owner**: Service owner + on-call engineer

**Actions**:

1. Create `docs/runbooks/[service-name]-runbook.md` with:
   - **Quick facts**: Service description, dependencies, team contact
   - **Common issues**: Top 5 troubleshooting scenarios
   - **Escalation contacts**: On-call, EM, platform lead
   - **Dashboard links**: Grafana, logs, alerts
   - **Emergency playbooks**: How to handle critical issues

2. Template:

   ```markdown
   # [Service Name] Runbook

   ## Quick Facts
   - **Language**: Java / Go / Python
   - **Dependencies**: [List services]
   - **On-Call Team**: @[team-name]
   - **Team Channel**: #[service-name]-team

   ## Common Issues

   ### Issue 1: [Symptom]
   **Diagnosis**: [How to identify]
   **Resolution**: [Steps to fix]
   **Prevention**: [How to avoid]

   ## Escalation
   - Primary: @[service-owner]
   - Secondary: @[secondary-owner]
   - Manager: @[em-lead]
   - Platform: @instacommerce/platform

   ## Emergency Procedures
   - [Critical issue response]
   ```

3. Add to Service README:
   - Link to runbook
   - Quick setup instructions
   - Local development guide

**Verification**:

- [ ] Runbook in master branch
- [ ] All team members reviewed runbook
- [ ] Runbook links correct (no 404s)
- [ ] Common issues match actual service behavior

---

## Phase 3: Monitoring & Alerting (Week 2)

### Step 3.1: Configure Monitoring & Alerts

**Owner**: SRE team + service owner

**Actions**:

1. Set up Prometheus metrics:
   - Request rate (req/sec)
   - Error rate (errors/sec)
   - Latency (p50, p95, p99)
   - Business metrics (orders processed, etc.)

2. Create alert rules in `monitoring/prometheus/[service-name]-alerts.yaml`:

   ```yaml
   - alert: [ServiceName]HighErrorRate
     expr: rate(http_requests_total{job="[service]", status=~"5.."}[5m]) > 0.01
     for: 5m
     annotations:
       summary: High error rate for [Service Name]
   ```

3. Route alerts to service Slack channel via Alertmanager

4. Create on-call escalation:
   - First alert: Service Slack channel
   - After 10 min: Page on-call engineer

**Verification**:

- [ ] Metrics flowing to Prometheus
- [ ] Alerts triggering correctly (test with canary)
- [ ] Slack notifications working
- [ ] On-call pages triggering

---

### Step 3.2: Set Up Logging & Tracing

**Owner**: Platform team + service owner

**Actions**:

1. Configure structured logging:
   - All logs include: timestamp, service, trace_id, level, message
   - JSON format for parsing

2. Configure tracing (if not auto-instrumented):
   - Service name: `[service-name]`
   - Jaeger endpoint: `jaeger.instacommerce:6831`

3. Create Grafana Loki queries for logs:

   ```
   {job="[service-name]"}
   ```

4. Document in runbook:
   - How to find logs for an incident
   - How to correlate by trace_id
   - Retention policy

**Verification**:

- [ ] Logs appear in Grafana Loki
- [ ] Traces appear in Jaeger
- [ ] Correlation IDs working across services
- [ ] Retention policy meets compliance requirements

---

## Phase 4: On-Call & Incident Response (Week 2)

### Step 4.1: Set Up On-Call Rotation

**Owner**: EM lead + SRE team

**Actions**:

1. Create PagerDuty service:
   - Service name: `[Service Name]`
   - Alert routing: Service Slack channel
   - On-call schedule: Primary + secondary

2. Add service owner to on-call schedule:
   - Primary: Service owner (1 week)
   - Secondary: Second engineer on team (1 week backup)
   - Rotation: Every Monday

3. Create escalation policy:

   ```
   Level 1 (5 min) → Primary on-call
   Level 2 (10 min) → Secondary on-call
   Level 3 (15 min) → Service owner
   Level 4 (30 min) → EM lead
   ```

4. Add PagerDuty integration to GitHub (for incident links in issues)

**Verification**:

- [ ] PagerDuty service created
- [ ] On-call engineers confirmed in schedule
- [ ] Test page sent successfully
- [ ] Escalation policy verified

---

### Step 4.2: Create Incident Response Plan

**Owner**: Service owner + on-call engineer

**Actions**:

1. Document incident response procedures in runbook:
   - How to recognize critical issues
   - Immediate mitigation steps
   - Who to contact (escalation chain)
   - How to communicate with stakeholders

2. Create postmortem template:
   - Timeline of events
   - Root cause analysis
   - Contributing factors
   - Action items with owners

3. Link to escalation matrix: `docs/governance/ESCALATION_POLICY.md`

**Verification**:

- [ ] Team reviewed and understands procedures
- [ ] Links to escalation policy correct
- [ ] Postmortem template accessible

---

## Phase 5: Forum Integration (Week 2-3)

### Step 5.1: Register for Weekly Service Ownership Forum

**Owner**: Service owner

**Actions**:

1. Add to `#service-ownership` Slack channel
2. Schedule weekly 1:1 status pre-meeting (Friday EOD):
   - Provide status: Incidents, deployments, blockers
   - Shared in Slack thread before Monday forum

3. Plan to attend Monday 9 AM UTC forum with:
   - Service owner or representative
   - Current on-call engineer
   - Ready to discuss: incidents, deployments, SLO status

**Verification**:

- [ ] Service owner added to #service-ownership
- [ ] Team member confirmed for Monday forum
- [ ] Calendar invite accepted

---

### Step 5.2: Register for Contract Review (if applicable)

**Owner**: Service architect

**Actions**:

1. If service produces/consumes events:
   - Add schemas to `contracts/` directory
   - Register with contract review team
   - Attend biweekly Wednesday 2 PM UTC forum if schema changes planned

2. If service has REST APIs:
   - Document in OpenAPI/Swagger
   - Link in service README
   - Notify contract team if planning breaking changes

**Verification**:

- [ ] Contracts documented and version-controlled
- [ ] API documentation complete
- [ ] Contract team aware of service

---

### Step 5.3: Add to Reliability Forum

**Owner**: SRE team

**Actions**:

1. Service automatically included in monthly Reliability Review
2. Metrics included in SLO compliance report
3. Service owner invited to attend (not mandatory but encouraged)

**Verification**:

- [ ] Service appears in SLO dashboard
- [ ] Metrics flowing 1 week before first forum

---

## Phase 6: Documentation & Knowledge Transfer (Week 3)

### Step 6.1: Complete Service Documentation

**Owner**: Service owner + team

**Actions**:

1. Service README must include:
   - [ ] Quick description (1 paragraph)
   - [ ] Purpose and responsibilities
   - [ ] Technology stack (language, frameworks, databases)
   - [ ] Dependencies (internal and external)
   - [ ] Team contact info
   - [ ] Links: runbook, dashboard, docs
   - [ ] Setup instructions (local dev)
   - [ ] API documentation (link)
   - [ ] Deployment procedures

2. API Documentation:
   - [ ] OpenAPI/Swagger spec
   - [ ] Example requests/responses
   - [ ] Error codes explained
   - [ ] Rate limiting (if applicable)

3. Database Schema Documentation (if applicable):
   - [ ] ERD diagram
   - [ ] Migration history (Flyway/Liquibase)
   - [ ] Key constraints and indexes
   - [ ] Data retention policy

4. Dependency Graph:
   - [ ] Services this service calls
   - [ ] Services that call this service
   - [ ] External systems (databases, message brokers)

**Verification**:

- [ ] All documentation links working
- [ ] README reviewed by at least 2 team members
- [ ] Documentation is findable from service runbook

---

### Step 6.2: Knowledge Transfer Sessions

**Owner**: Service owner + platform team

**Actions**:

1. Schedule knowledge transfer sessions (2-3 hours):
   - Session 1: Architecture and design decisions
   - Session 2: Operational procedures and alerts
   - Session 3: Common troubleshooting and incidents

2. Attendees:
   - Service owner + team
   - On-call engineers
   - Platform team representative
   - Secondary owner

3. Document learnings:
   - Record sessions (if permitted)
   - Create FAQ based on questions
   - Update runbook with clarifications

**Verification**:

- [ ] All team members attended at least one session
- [ ] Q&A documented and added to runbook
- [ ] Runbook updated with new information

---

## Phase 7: Pilot & Production Readiness (Week 3)

### Step 7.1: Pre-Production Verification

**Owner**: Service owner + SRE + Platform

**Actions**:

1. Security review:
   - [ ] Authentication implemented (via identity-service)
   - [ ] Authorization checks in place
   - [ ] Secrets management reviewed
   - [ ] Security team sign-off obtained

2. Operational readiness:
   - [ ] Runbook is complete and tested
   - [ ] SLOs defined and dashboards working
   - [ ] Alerts configured and tested
   - [ ] On-call schedule finalized
   - [ ] Postmortem procedures documented

3. Load testing:
   - [ ] Service handles expected traffic
   - [ ] No memory leaks or resource issues
   - [ ] Graceful degradation if downstream services slow

4. Disaster recovery:
   - [ ] Data backup/restore procedures tested
   - [ ] RTO/RPO targets defined
   - [ ] Failover procedures (if applicable) documented

**Verification**:

- [ ] All checklist items completed
- [ ] Platform team and SRE sign-off obtained
- [ ] VP Engineering awareness confirmed

---

### Step 7.2: Soft Launch / Canary Deployment

**Owner**: Service owner + DevOps

**Actions**:

1. Deploy to production in canary mode:
   - 10% traffic initially
   - Monitor metrics closely for 24 hours
   - Increase to 50% if stable
   - Final 100% rollout

2. Active monitoring during rollout:
   - On-call engineer actively watching
   - Alert thresholds tuned for stability
   - Easy rollback procedure ready

3. Incident response team on standby:
   - Service owner available
   - Platform lead available
   - SRE on-call ready to escalate

**Verification**:

- [ ] Deployment completed successfully
- [ ] No anomalies in metrics during canary
- [ ] Incident response plan not needed
- [ ] Team confidence high for 100% traffic

---

## Phase 8: First Month Support (Ongoing)

### Step 8.1: Active Monitoring & Tuning

**Owner**: Service owner + on-call rotation

**Actions**:

1. Daily check-ins (Week 1):
   - Review overnight metrics
   - Check for any anomalies
   - Tune alert thresholds if needed

2. Weekly team sync:
   - Review incidents (if any)
   - Discuss lessons learned
   - Update runbook with new issues discovered

3. First month on-call rotation:
   - Ensure at least 2 team members have covered on-call
   - Debrief after each shift
   - Document new issues or scenarios

**Verification**:

- [ ] Team completing on-call rotations
- [ ] Runbook being updated with real issues
- [ ] Alert tuning progressing (fewer false positives)

---

### Step 8.2: Integration with Existing Services

**Owner**: Platform team

**Actions**:

1. If service has interdependencies:
   - Verify integration tests between services
   - Test failover scenarios
   - Document failure modes

2. Data consistency (if applicable):
   - Verify eventual consistency mechanisms
   - Test reconciliation procedures
   - Add to monthly reconciliation review

3. Cross-service monitoring:
   - Verify correlation IDs working
   - Check service dependency graph
   - Validate trace propagation

**Verification**:

- [ ] All integration tests passing
- [ ] Service dependency graph updated
- [ ] No orphaned data or failed dependencies

---

## Completion Checklist

Use this checklist to verify new service is fully onboarded:

### Governance & Ownership

- [ ] Service owner and team assigned
- [ ] GitHub team created and in CODEOWNERS
- [ ] Service listed in governance model
- [ ] Slack channel created
- [ ] On-call schedule configured in PagerDuty

### Operations & Monitoring

- [ ] SLOs defined and documented
- [ ] Grafana dashboard created and metrics flowing
- [ ] Prometheus alerts configured
- [ ] Logging and tracing working
- [ ] Runbook complete and tested

### Forum Integration

- [ ] Service on weekly ownership forum
- [ ] Service on monthly reliability review
- [ ] Contract team aware (if producing/consuming events)
- [ ] Quarterly steering committee aware (in OKRs)

### Documentation & Knowledge

- [ ] Service README complete
- [ ] API documentation published
- [ ] Runbook complete and linked
- [ ] Database schema documented
- [ ] Knowledge transfer completed

### Production Readiness

- [ ] Security review completed
- [ ] Load testing completed
- [ ] Disaster recovery plan tested
- [ ] Pre-production verification signed off
- [ ] Canary deployment successful

### Support & Monitoring

- [ ] First month on-call rotation underway
- [ ] Team comfortable with operations
- [ ] Integration with existing services verified
- [ ] Alert thresholds tuned

---

## Success Metrics

Track these metrics for first 30 days:

| Metric | Target | Result |
| -------- | -------- | -------- |
| Time to first deployment | <3 weeks | [X] |
| Documentation completeness | 100% checklist | [%] |
| First month uptime | >99% | [%] |
| Mean incident response time | <15 min | [X] min |
| On-call team confidence | 8/10 | [Score] |
| Alert tuning (false pos/day) | <2 | [X] |

---

## Post-Onboarding (Month 2+)

After successful first month:

- Service treated as any other service in platform
- Team participates in all governance forums
- Quarterly reviews of SLOs and staffing
- Continuous improvement of runbooks and procedures

---

## Support & Questions

For questions during onboarding:

- **Governance**: Contact @instacommerce/platform
- **Monitoring**: Contact @instacommerce/sre
- **On-Call**: Contact your EM lead

---

## References

- **CODEOWNERS**: .github/CODEOWNERS
- **Governance Model**: docs/governance/OWNERSHIP_MODEL.md
- **Escalation Policy**: docs/governance/ESCALATION_POLICY.md
- **Service SLOs**: docs/slos/service-slos.md
- **Weekly Forum**: docs/governance/forums/weekly-service-ownership-forum.md
- **Contract Review**: docs/governance/forums/biweekly-contract-review.md
- **Reliability Review**: docs/governance/forums/monthly-reliability-review.md
