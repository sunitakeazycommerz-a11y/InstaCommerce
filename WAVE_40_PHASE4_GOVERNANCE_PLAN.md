# Wave 40 Phase 4: Governance Activation (Forums + CODEOWNERS)

## Executive Summary
**Objective**: Activate 4 standing forums + enforce CODEOWNERS branch protection
**Timeline**: Week 4 (April 7-11)
**Coverage**: All 28 services with clear ownership + escalation paths
**Success Metric**: 100% forum attendance, zero PRs merged without owner approval

---

## Forum Structure

### 1. Weekly Service Ownership Review
**When**: Monday 9 AM UTC
**Duration**: 30 minutes
**Attendees**: All service owners + platform lead

**Agenda** (rotating 2-3 services per week):
1. **Health Check** (5 min)
   - SLO status (p99 latency, error rate, availability)
   - Open incidents (count + age)
   - Deployment frequency (this week)

2. **Dependency Map** (5 min)
   - Upstream dependencies: Any changes?
   - Downstream dependents: Any concerns?
   - Alerting from deps: Any false positives?

3. **Backlog Review** (10 min)
   - Tech debt items
   - Architecture improvements needed
   - Capacity planning next quarter

4. **Q&A** (10 min)
   - Cross-service questions
   - Platform team blockers
   - Escalations

**Owner**: Platform Lead (facilitates)
**DRI per Service**: Service Owner (presents)

**Decision Authority**: Service owner has autonomy; escalate if cross-service impact

### 2. Biweekly Contract Review
**When**: Wednesday 2 PM UTC (every 2 weeks)
**Duration**: 45 minutes
**Attendees**: API owners + integration teams

**Agenda**:
1. **Contract Changes** (10 min)
   - New endpoints added to any service
   - Breaking changes planned
   - Version deprecation status

2. **Consumer Issues** (10 min)
   - Integration test failures
   - Timeout complaints
   - Schema mismatch issues

3. **API SLO Compliance** (10 min)
   - Latency: Are we meeting p99 targets?
   - Availability: Any outages to post-mortem?
   - Error types: Common failure modes

4. **Next Quarter Planning** (15 min)
   - API roadmap alignment
   - Consumer team priorities
   - Breaking change planning (with 3-month notice)

**Owner**: API Governance Lead
**Reference**: `docs/contracts/` (OpenAPI specs)

**Decision Authority**: If disagreement → 1 week negotiation period, then CTO decides

### 3. Monthly Reliability Review
**When**: First Friday 10 AM UTC
**Duration**: 60 minutes
**Attendees**: VP Eng + all service owners + SRE team

**Agenda**:
1. **Production Incidents** (15 min)
   - All incidents this month (P0, P1, P2)
   - Root causes (summary)
   - Resolved / ongoing

2. **Postmortem Review** (15 min)
   - Postmortems closed this month
   - Action items: Owner + due date
   - Blameless culture reminder

3. **SLO Trends** (15 min)
   - Which services trending down?
   - Error budget burn vs plan
   - Patterns (certain times, deployment-related?)

4. **Error Budget Trading** (10 min)
   - Services requesting budget for maintenance
   - Approval/denial + conditions

5. **Escalations** (5 min)
   - Unresolved issues from weekly reviews
   - Cross-team conflicts needing VP decision

**Owner**: VP Eng (chairs)
**DRI**: SRE Lead (presents SLO report)

**Decision Authority**: VP Eng makes final calls on trade-offs

### 4. Quarterly Steering Committee
**When**: First week of Q+1 (e.g., April 1 for Q2)
**Duration**: 90 minutes
**Attendees**: VP Eng, VP Product, CTO, Finance Lead, Service Leads (rotating)

**Agenda**:
1. **Quarterly Review** (20 min)
   - SLO achievements: vs target
   - Incident trends: improving?
   - Infrastructure spend: vs budget

2. **Architecture Evolution** (20 min)
   - Major changes completed this quarter
   - Major changes planned next quarter
   - ADR reviews (any that conflict?)

3. **Product-Engineering Alignment** (15 min)
   - Feature roadmap vs engineering roadmap
   - Capacity commitments
   - Trade-offs (speed vs reliability)

4. **Staffing & Growth** (15 min)
   - Services needing more engineers
   - Services trending up/down
   - Hiring plan for Q+2

5. **Strategic Initiatives** (20 min)
   - Waves (Q2: Phase 2-6 delivery)
   - Tech debt: Is our approach working?
   - Risk: Any threats?

**Owner**: VP Eng + VP Product (co-chairs)

**Decision Authority**: Steering committee votes; majority wins (CTO breaks tie)

---

## CODEOWNERS Enforcement

### File: `.github/CODEOWNERS`

```
# Global
* @platform-team

# Money Path (Payment)
services/payment-service/ @payment-team @platform-team
services/payment-webhook-service/ @payment-team
services/reconciliation-engine/ @payment-team

# Order Management
services/order-service/ @order-team
services/checkout-orchestrator-service/ @order-team

# Fulfillment & Logistics
services/fulfillment-service/ @fulfillment-team
services/warehouse-service/ @fulfillment-team
services/rider-fleet-service/ @fulfillment-team
services/routing-eta-service/ @platform-team
services/dispatch-optimizer-service/ @platform-team
services/location-ingestion-service/ @platform-team

# Platform & Infrastructure
services/identity-service/ @platform-team
services/admin-gateway-service/ @platform-team
services/config-feature-flag-service/ @platform-team
services/cdc-consumer-service/ @platform-team
services/outbox-relay-service/ @platform-team
services/audit-trail-service/ @platform-team
services/stream-processor-service/ @platform-team

# Search & Catalog
services/search-service/ @catalog-team
services/catalog-service/ @catalog-team
services/cart-service/ @catalog-team
services/pricing-service/ @catalog-team

# Engagement
services/notification-service/ @engagement-team
services/mobile-bff-service/ @engagement-team
services/wallet-loyalty-service/ @engagement-team

# AI & ML
services/ai-inference-service/ @ml-team
services/ai-orchestrator-service/ @ml-team
services/fraud-detection-service/ @ml-team

# Shared
contracts/ @platform-team
build.gradle.kts @platform-team
.github/workflows/ @platform-team
docs/adr/ @platform-team
docs/governance/ @platform-team
```

### Branch Protection Rules

**File**: `.github/branch-protection.yml` (or use GitHub UI)

```yaml
branch: master
required_status_checks:
  - CodeQL
  - CI Pipeline
  - contract-validation

require_code_owner_reviews: true
dismissal_restrictions:
  users:
    - dependabot
  teams: []

require_status_checks_to_pass_before_merging: true
required_approving_review_count: 2
require_branches_to_be_up_to_date_before_merging: true

allow_force_pushes: false
allow_deletions: false
```

### Escalation Policy

**For PR Approval Blocking**:

1. **Service Owner Unavailable** (>4 hours)
   - Manager auto-approves if CI passing
   - Logged in SLA tracker

2. **Disagreement on Approach** (>24 hours)
   - Escalate to VP Eng
   - Decision made within 24 hours

3. **Cross-Service Conflict** (>48 hours)
   - CTO + both service owners discuss
   - Decision made within 48 hours

---

## Team Assignments

| Service | Primary Owner | Secondary | Backup |
|---------|---------------|-----------|--------|
| **payment-service** | Aisha (Sr Eng) | Raj (Eng) | VP Eng |
| **order-service** | Marcus (Sr Eng) | Jennifer (Eng) | VP Eng |
| **fulfillment-service** | Chen (Sr Eng) | David (Eng) | Fulfillment Lead |
| **identity-service** | Priya (Sr Eng) | Sam (Eng) | Platform Lead |
| **search-service** | Jamal (Principal) | - | Catalog Lead |
| **catalog-service** | Sophia (Sr Eng) | Alex (Eng) | Catalog Lead |
| ... | ... | ... | ... |

**Rotation**: Quarterly re-evaluation based on availability + interest

---

## Deployment

### Week 4 Activation Plan

**Mon (April 7)**:
- [ ] Add all service owners to `.github/CODEOWNERS`
- [ ] Enable branch protection on master
- [ ] Send calendar invites for all 4 forums

**Tue (April 8)**:
- [ ] First Weekly Service Ownership Review (3 services)
- [ ] Feedback collection from participants

**Wed (April 9)**:
- [ ] First Biweekly Contract Review
- [ ] API governance team readiness check

**Fri (April 11)**:
- [ ] First Monthly Reliability Review (FY-to-date postmortem summary)
- [ ] SLO report presentation

**Mon (April 14)**:
- [ ] Quarterly Steering meeting (Q2 planning for Waves 41-42)
- [ ] Governance retrospective: Is forum structure working?

---

## Success Metrics

| Metric | Target | Current | ETA |
|--------|--------|---------|-----|
| Forum attendance | >90% | 0% (not started) | After April 11 |
| CODEOWNERS enforcement | 100% PRs | 0% | After April 7 |
| Time to service owner review | <4 hours | N/A | April 8 |
| Escalations resolved | 100% in <48h | N/A | Ongoing |
| Incidents with postmortem | 100% P1+ | 80% | Post-Wave 40 |
| Tech debt backlog | Groomed/prioritized | TBD | April 11 |

---

## Communication

### Slack Channels
- `#forums-service-ownership`: Weekly meeting notes
- `#forums-contract-review`: API changes announced
- `#forums-incidents`: P0/P1 postmortem links
- `#forums-governance`: Process updates

### Wiki Pages
- `docs/governance/OWNERSHIP_MODEL.md`: All forums explained
- `docs/governance/ESCALATION_MATRIX.md`: When to escalate
- `docs/governance/SERVICE_OWNERS.md`: Current assignments + backup

### Runbooks
- `docs/runbooks/governance-forum-facilitation.md`: How to run each meeting
- `docs/runbooks/codeowners-conflict-resolution.md`: Handling approval blocks

---

## Feedback Loop

**After 30 Days** (May 1):
- Collect feedback from all 4 forums
- Adjust meeting times / agenda if needed
- Publish retrospective + action items

**After 90 Days** (July 1):
- Full governance review
- Expand forums if needed (e.g., "Quarterly Security Review")
- Evaluate on-call rotation effectiveness

---

## Alignment to Production Excellence

✅ **Ownership**: Clear DRI for each service (eliminates "not my team" incidents)
✅ **Escalation**: Defined paths prevent P1s from stalling
✅ **Visibility**: Quarterly steering ensures strategic alignment
✅ **Accountability**: CODEOWNERS + PR approval audit trail
✅ **Learning**: Postmortem culture baked into monthly review

**Status**: ✅ READY FOR ACTIVATION (Week 4)
