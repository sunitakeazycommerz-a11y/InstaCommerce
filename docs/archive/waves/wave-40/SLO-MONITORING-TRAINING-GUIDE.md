# Wave 40 Phase 3: SLO Monitoring & Error Budget Training Guide
**Target Audience**: SRE team, Service owners, Platform team leads
**Duration**: 4-hour comprehensive training program
**Delivery**: 2 hours workshop + 2 hours hands-on lab
**Generated**: 2026-03-22

---

## Table of Contents
1. [SLO Fundamentals](#slo-fundamentals)
2. [Error Budget Model](#error-budget-model)
3. [Multi-Window Burn-Rate Alerting](#multi-window-burn-rate-alerting)
4. [Dashboard Navigation & Interpretation](#dashboard-navigation)
5. [Incident Response Procedures](#incident-response)
6. [Lab Exercises](#lab-exercises)
7. [Reference Materials](#reference-materials)

---

## SLO Fundamentals

### What is an SLO?

**Service Level Objective (SLO)**: A specific, measurable target for service reliability over a calendar month.

**Key Formula**:
```
SLO = Target Availability %
Error Budget = (100 - SLO) % of all minutes in a month

Examples:
- 99.95% SLO → 2.2 minutes error budget/month (Payment Service)
- 99.9% SLO → 8.6 minutes error budget/month (Order Service)
- 99% SLO → 14.4 minutes error budget/month (Feature Flag Service)
```

### InstaCommerce SLO Tiers

**Tier 1 - Critical Money Path** (5 services)
| Service | Availability | Error Budget | P99 Target | Criticality |
|---------|--------------|-------------|-----------|-------------|
| payment-service | 99.95% | 2.2 min | 300ms | **CRITICAL** |
| payment-webhook-service | 99.99% | 0.43 min | 200ms | **CRITICAL** |
| order-service | 99.9% | 8.6 min | 500ms | **CRITICAL** |
| checkout-orchestrator | 99.9% | 8.6 min | 2s | **CRITICAL** |
| fulfillment-service | 99.5% | 43 min | 2s | **HIGH** |

**Tier 2 - Read Path** (7 services)
| Service | Availability | Error Budget | P99 Target |
|---------|--------------|-------------|-----------|
| search-service | 99% | 14.4 min | 200ms |
| catalog-service | 99.5% | 43 min | 500ms |
| cart-service | 99.8% | 17 min | 300ms |
| pricing-service | 99% | 14.4 min | 100ms |
| inventory-service | 99.5% | 43 min | 300ms |
| fraud-detection | 99% | 14.4 min | 200ms |
| feature-flag-service | 99% | 14.4 min | 100ms |

**Tier 3-5 - Platform, Logistics, Mobile/AI** (16 services)
- Availability range: 98% - 99.9%
- Error budgets: 14.4 - 43+ minutes/month
- Latency targets: 100ms - 10s p99

### SLO vs SLA vs SLI

```
SLI (Service Level Indicator)
  ↓ (measured by)
SLO (Service Level Objective) ← Team commits to this target
  ↓ (backed by)
SLA (Service Level Agreement) ← Legal/contractual commitment
```

**Example**:
- **SLI**: "99.92% of payment API requests completed without error"
- **SLO**: "We target 99.95% availability for payment service"
- **SLA**: "If we fall below 99.5%, customers get 10% service credit"

---

## Error Budget Model

### How Error Budget Works

**Concept**: Each service gets a "budget" of allowed errors/downtime. Once spent, feature freeze kicks in.

### Monthly Error Budget Calculation

```
Days per month: 30
Minutes per month: 30 × 24 × 60 = 43,200

For 99.95% SLO:
  Error Budget = (100 - 99.95) × 43,200 / 100 = 21.6 minutes

Interpretation:
  ✓ If payment service is down 10 min → 10 min spent from 21.6 min budget
  ✓ Remaining budget: 11.6 minutes
  ✗ If it goes down another 15 min → Budget exhausted
  ✗ → FEATURE FREEZE activated
```

### Error Budget Consumption Tracking

In Grafana SLO Dashboard for each service:

```
Error Budget Widget Example (payment-service):
┌─────────────────────────────────────┐
│ Error Budget Remaining: 18.2 minutes │
│ (of 21.6 min/month)                 │
│ ██████████████░░░░░░  84% remaining  │
│                                      │
│ Consumed: 3.4 min (16%)              │
│ Time in month: 12 days              │
│ Burn rate: 0.28 min/day             │
│ Projected exhaustion: Day 77 (OVR)   │
└─────────────────────────────────────┘
```

### Feature Freeze Policy

When error budget **>75% consumed**:
```
1. Warning alert triggered (SEV-2)
2. Service owner notified
3. New features paused (bug fixes OK)
4. Focus shifts to reliability improvement
```

When error budget **100% consumed**:
```
1. Critical alert triggered (SEV-1)
2. On-call page triggered
3. MANDATORY feature freeze in effect
4. Only reliability & hotfix work allowed
```

### Example Scenario

**Month timeline: Day 1-30**

```
Day 1-10 (Normal operation):
  - 0.5 min downtime
  - Budget remaining: 21.1 min (98% OK)
  - Status: GREEN ✓

Day 11-15 (Database issues):
  - 3 min downtime
  - Budget remaining: 18.1 min (84% OK)
  - Status: YELLOW ⚠

Day 16-20 (Deployment problem):
  - 8 min downtime
  - Budget remaining: 10.1 min (47% OK)
  - Alert triggered: "Error budget >50% consumed"
  - Status: ORANGE ⚠

Day 21-25 (Cascading failures):
  - 12 min downtime
  - Budget remaining: -1.9 min (NEGATIVE!)
  - Budget EXHAUSTED
  - Alert: "CRITICAL: Feature freeze in effect"
  - Status: RED ✗

Day 26-30 (Stabilization):
  - All hands on deck to fix reliability
  - New features blocked
  - Must pass through next month SLO

Next month (Month 2):
  - Budget resets to 21.6 min
  - Incident post-mortem required
  - Process improvements implemented
```

---

## Multi-Window Burn-Rate Alerting

### The Google SRE Approach

Instead of "service is down = alert", we use **multiple time windows** to detect SLO breaches:

```
Fast Burn (5 min window)   → SEV-1: Page immediately
Medium Burn (30 min)       → SEV-2: Create incident ticket
Slow Burn (6 hour window)  → SEV-3: Schedule SRE review
```

### Why Multiple Windows?

**Problem with single window alerts**:
```
❌ Alert on >10% error rate (5 min)
   - Too noisy (spikes happen)
   - False positives exhaust on-call
   - Cry wolf → ignored alerts

✓ Multi-window approach
   - Fast burn: Catches real issues quickly
   - Medium burn: Confirms sustained problems
   - Slow burn: Early warning for trends
```

### Alert Thresholds & Actions

#### Fast Burn (5 min window) → SEV-1 CRITICAL

```
Trigger: Error rate > 10% for 1 minute (5min window)

Interpretation:
  - Payment service: 99.95% SLO × 10% burn = exhausts 2.2 min budget in ~22 minutes
  - Order service: 99.9% SLO × 10% burn = exhausts 8.6 min budget in ~86 minutes

Action: PAGE ON-CALL IMMEDIATELY
  → PagerDuty sends alert to on-call engineer
  → Expected response: <5 minutes
  → Runbook link included: docs/runbooks/payment-service-errors

Example Prometheus Query:
```yaml
(rate(http_requests_total{service="payment-service", status=~"5.."}[5m])
 / rate(http_requests_total{service="payment-service"}[5m])) > 0.1
```
```

#### Medium Burn (30 min window) → SEV-2 WARNING

```
Trigger: Error rate > 5% for 5 minutes (30min window)

Interpretation:
  - Payment service: At 5% burn, would exhaust budget in 7.2 hours
  - Indicates sustained problem, not spike

Action: CREATE INCIDENT TICKET
  → Team Slack channel notified
  → Incident commander assigned
  → Expected resolution: <1 hour
  → Includes dashboard link for investigation

Example:
  SEV-2 Incident: "payment-service sustained high error rate"
  Duration: 30 minutes at >5% errors
  Impact: ~1.8 minutes of monthly budget consumed
```

#### Slow Burn (6 hour window) → SEV-3 INFO

```
Trigger: Error rate > 1% for 30 minutes (6h window)

Interpretation:
  - Indicates unhealthy trend that will exhaust budget in ~28 days
  - Quality degradation happening

Action: SCHEDULE SRE AUDIT REVIEW
  → Added to weekly SRE retrospective
  → Service owner to investigate root cause
  → Trend analysis: Why is baseline error rate elevated?
  → Expected fix: Within 1 week
```

### Burn Rate Calculation

```
Burn Rate = Current Error Rate / SLO Error Budget Rate

Example for payment-service (99.95% SLO):
  SLO Error Budget Rate = 0.05% per month

  If current error rate = 10% (fast burn window):
    Burn Rate = 10% / 0.05% = 200x normal
    Meaning: Exhausts 200× monthly budget per minute
    Impact: Budget gone in 2.2 min / 200 = 0.66 seconds

  If current error rate = 5% (medium burn window):
    Burn Rate = 5% / 0.05% = 100x normal
    Meaning: Exhausts monthly budget in ~2.16 minutes

  If current error rate = 1% (slow burn window):
    Burn Rate = 1% / 0.05% = 20x normal
    Meaning: Exhausts monthly budget in ~18 minutes
```

### Real Example: Payment Service Alert Cascade

```
[15:30 UTC] Payment service experiences database connection pool exhaustion

[15:31 UTC] Error rate spikes to 15% for 1 minute
  ├─ ALERT: FastBurnHighErrorRate triggered
  ├─ Severity: CRITICAL (SEV-1)
  ├─ PagerDuty: Pages on-call payment engineer
  ├─ Slack: #incidents channel notification
  └─ Dashboard: Link opens payment-service SLO dashboard

[15:31-15:36] On-call investigates:
  ├─ Checks Grafana dashboard
  ├─ Sees: p99 latency spiked to 2s (normal: 300ms)
  ├─ Sees: Database connections at 100% (max: 20)
  ├─ Root cause: Cascading slowness from downstream service

[15:37 UTC] Error rate drops to 3% (medium burn window now firing)
  ├─ ALERT: MediumBurnHighErrorRate triggered
  ├─ Still SEV-2, sustained elevated error rate
  ├─ On-call starts mitigation (circuit breaker, reduced traffic)

[15:45 UTC] Error rate normalized to 0.2% (back to healthy)
  ├─ FastBurn alert auto-resolves (no longer >10%)
  ├─ MediumBurn alert auto-resolves (no longer >5%)
  ├─ Dashboard shows error budget usage: 2.1 min consumed (out of 2.2!)
  ├─ Slow burn alert may trigger if trend continues

[16:00 UTC] Post-incident review:
  ├─ Error budget: 0.1 min remaining (CRITICAL!)
  ├─ Feature freeze activated (no new deployments)
  ├─ Incident severity: SEV-1 due to budget exhaustion
  ├─ Root cause: Downstream service not respecting timeout
  ├─ Action items: Circuit breaker implementation, timeout tuning
```

---

## Dashboard Navigation & Interpretation

### Service SLO Dashboard Overview

Each of 28 services has identical dashboard structure (payment-service example):

```
┌─────────────────────────────────────────────────────────────────┐
│  Payment Service - SLO Dashboard (Week view: Mar 22-28)         │
└─────────────────────────────────────────────────────────────────┘

┌─ ROW 1: SLO STATUS ─────────────────────────────────────────────┐
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ Availability │  │ Error Budget │  │  SLO Summary Table   │  │
│  │    99.94%    │  │   84.2%      │  ├──────────────────────┤  │
│  │              │  │   Remaining  │  │ Target: 99.95%       │  │
│  │ ████████░░░░ │  │ ████████░░░░ │  │ Current: 99.94%      │  │
│  │ (HEALTHY)    │  │ (84% OK)     │  │ Status: HEALTHY ✓    │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘

┌─ ROW 2: LATENCY ANALYSIS ──────────────────────────────────────┐
│                                                                  │
│  Latency Percentiles (p50/p95/p99) - 7 day view                 │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                                                          │   │
│  │  p99: 287ms (red line, target: 300ms) ✓ HEALTHY        │   │
│  │  p95:  145ms (orange line)                             │   │
│  │  p50:   45ms (green line)                              │   │
│  │                                                          │   │
│  │  Day:  Mon Tue Wed Thu Fri Sat Sun                      │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘

┌─ ROW 3: TRAFFIC & ERRORS ──────────────────────────────────────┐
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐                            │
│  │ Request Rate │  │  Error Rate  │                            │
│  │              │  │              │                            │
│  │  Peak: 2.4K  │  │  Peak: 0.8%  │                            │
│  │  Avg:  1.8K  │  │  Avg:  0.1%  │                            │
│  │  RPS         │  │  5xx only    │                            │
│  └──────────────┘  └──────────────┘                            │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘

┌─ ROW 4: ERROR BUDGET BURN RATE ────────────────────────────────┐
│                                                                  │
│  Burn Rate Lines (lower is better):                             │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                                                          │   │
│  │  ╔════════════════════════════════════════════════════╗ │   │
│  │  ║ Fast Burn (5min)  [RED]    : 2.1x normal         ║ │   │
│  │  ║ Medium Burn (30m) [ORANGE] : 0.8x normal         ║ │   │
│  │  ║ Slow Burn (6h)    [YELLOW] : 0.3x normal         ║ │   │
│  │  ╚════════════════════════════════════════════════════╝ │   │
│  │                                                          │   │
│  │  Meaning: On average, consuming 0.3x of monthly budget  │   │
│  │  per day → Budget will last ~3.3 months               │   │
│  │                                                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### How to Read Each Panel

#### 1. Availability Gauge

```
Display: Current availability % (calculated from last 5 min)
Formula: 100 × (1 - error_rate)
Colors:
  - GREEN:  ≥ SLO target (e.g., ≥99.95%)
  - YELLOW: 1-2% below target
  - RED:    >2% below target

Interpretation:
  99.94% shown → You're 0.01% below target
  This is 1 hour of budget consumed already (of 2.2 min)

  Action if RED:
  - Check "Error Rate" panel for 5xx errors
  - Check "Latency" for timeout errors
  - Check service logs for exceptions
```

#### 2. Error Budget Gauge

```
Display: % of monthly budget remaining
Formula: (Remaining Budget / Total Budget) × 100
Colors:
  - GREEN:  >50% remaining
  - YELLOW: 30-50% remaining
  - RED:    <30% remaining

Interpretation:
  84% shown → 84% of 2.2 min = 1.85 min remaining

  If RED (<30%):
  - Feature freeze may be imminent
  - Review "Slow Burn" trends
  - Schedule reliability sprint
```

#### 3. Latency Percentiles

```
How to interpret:
  p99 = 99th percentile (worst 1% of requests)
  p95 = 95th percentile (worst 5% of requests)
  p50 = median (typical request)

Example lines:
  p99: 287ms (close to 300ms target) ✓ ACCEPTABLE
  p95: 145ms (well below p99)
  p50: 45ms (typical performance)

If p99 > target (red zone):
  1. Check if traffic spike happened
  2. Look for GC pauses in service logs
  3. Check database query performance
  4. Review for dogpiling/cache misses
```

#### 4. Error Rate

```
Display: % of 5xx errors over time
Formula: 100 × (5xx_errors / total_requests)
Window: Last 7 days, 30-second resolution

Good vs Bad:
  <0.1%: Excellent (green)
  0.1-0.5%: Acceptable (yellow)
  >0.5%: Investigate (orange)
  >1%: Alert territory (red)

Spikes indicate:
  - Upstream service down
  - Database connection pool exhausted
  - Resource limits hit
  - Configuration error deployed
```

#### 5. Burn Rate Indicators

```
Display: How fast you're consuming monthly error budget
Formula: (Current error rate) / (SLO error budget rate)

Example for payment (99.95% SLO = 0.05% budget rate):
  If current error = 0.05%: Burn rate = 1.0x (normal burn)
  If current error = 0.5%:  Burn rate = 10x (fast burn)
  If current error = 5%:    Burn rate = 100x (very fast)

Lines show:
  - Fast Burn (5min): Spikes only
  - Medium Burn (30m): Trends visible
  - Slow Burn (6h): Overall health

Healthy state:
  All lines ≤1x (burning at normal rate)
  Budget lasts for full month

Alert state:
  Fast/Medium Burn spike → Page triggered
  Slow Burn climb → Review alert triggered
```

### Platform Dashboards

#### 1. Cross-Service SLO Overview

```
Single dashboard showing all 28 services:

Service              │ Availability │ Budget │ Status
─────────────────────┼──────────────┼────────┼────────
payment-service      │ 99.94%       │ 84%    │ ✓
checkout-orchestr.   │ 99.89%       │ 72%    │ ✓
order-service        │ 99.89%       │ 88%    │ ✓
fulfillment-service  │ 99.48%       │ 95%    │ ✓
search-service       │ 99.02%       │ 98%    │ ✓
...                  │              │        │

Color coding:
  ✓ Green: SLO met, budget >50%
  ⚠ Yellow: SLO met, budget 30-50%
  ⚠ Orange: SLO borderline, budget <30%
  ✗ Red: SLO breach or budget exhausted
```

#### 2. Error Budget Burn Analysis

```
30-day burndown chart for all services:

Budget consumption over month:
            Day 1  Day 10  Day 20  Day 30
Payment     5%     18%     52%     98% ← CRITICAL
Order       3%     12%     28%     45% ← HEALTHY
Fulfillment 2%     8%      15%     22% ← HEALTHY
Search      1%     4%      10%     18% ← HEALTHY

Steep lines = high burn rate
Early exhaustion = likely feature freeze coming

Action if line crosses >75% before day 25:
  - Schedule reliability sprint
  - Reduce feature velocity
  - Focus on incident fixes
```

#### 3. Incident Timeline & Correlation

```
Shows relationships between services during incidents:

Timeline view (7 days):
Mon │ Tue │ Wed │ Thu │ Fri │ Sat │ Sun
    │ 14:00            │               │
    │ Search down      │               │
    │ (catalog fallback)               │
    │     │ 15:30                      │
    │     │ Order latency spike        │
    │     │ (waiting for search)       │
    │     │     │ 17:00                │
    │     │     │ Payment errors       │
    │     │     │ (order queue backup) │

Helps identify:
  - Cascading failures (A down → B slows → C errors)
  - Correlated incidents (simultaneous issues)
  - Root cause (search was the trigger)
```

---

## Incident Response Procedures

### Alert Severity & Response

#### SEV-1 (CRITICAL) - Page Immediately

**Trigger**: Fast burn alert fires (e.g., >10% error rate for 5min)

**Your Actions (0-5 minutes)**:
```
1. Acknowledge PagerDuty alert (silence noise)
2. Open service dashboard from alert link
3. Scan "Error Rate" and "Latency" panels for spike
4. Check if traffic spike happened (check "Request Rate")
5. Decide: Is this real or false positive?
```

**Investigation (5-15 minutes)**:
```
HEALTHY STATE: Error rate normal, latency normal
  ✓ Likely false positive
  ✓ Check Prometheus metrics directly
  ✓ Close alert if confirmed false positive

DEGRADED STATE: Error rate OR latency elevated
  → Look for patterns in error logs
  → Check downstream services (running?)
  → Review recent deployments
  → Check infrastructure (disk, memory, CPU)
```

**Escalation (>15 minutes)**:
```
If still investigating after 15 min:
  - Page service owner (get expertise)
  - Notify incident commander (#incidents channel)
  - If payment service: VP Engineering also pages
  - Start collecting metrics for post-mortem
```

#### SEV-2 (WARNING) - Create Incident Ticket

**Trigger**: Medium burn alert (e.g., >5% error rate for 30min)

**Your Actions (0-30 minutes)**:
```
1. Incident ticket auto-created in Jira
2. Team Slack channel notified
3. You have 30 minutes to triage
4. Actions:
   - Is the issue resolving? (error rate trending down) → monitor
   - Is it sustained? (flat or trending up) → investigate
   - Is it cascading? (other services affected) → escalate to SEV-1
```

#### SEV-3 (INFO) - Schedule Review

**Trigger**: Slow burn alert (e.g., >1% error rate for 6h)

**Your Actions (within 24 hours)**:
```
1. Added to weekly SRE backlog
2. Review during team meeting
3. Investigate root cause:
   - Why is baseline error rate elevated?
   - Is this new behavior or chronic?
   - What changed recently?
4. File improvement ticket
```

### Using Runbooks

Each alert includes a `runbook_url` link. Example:

**Alert**: PaymentServiceFastBurn

```yaml
annotations:
  runbook_url: "https://docs.instacommerce.dev/docs/runbooks/payment-service-errors"
```

**Runbook structure**:
```
# Payment Service Error Rate High - Runbook

## Symptoms
- SEV-1 alert firing
- Error rate >10% for payment API
- Customers seeing "Payment failed" messages

## Check (5 minutes)
1. Dashboard: https://grafana.dev/d/slo-payment-service
   - Is latency spiked? (check "Latency Percentiles")
   - Is error rate climbing or stable?

2. Logs: Check last 15 minutes in DataDog
   ```bash
   service:payment-service status:error
   ```
   - Look for exception messages
   - Check for database connection errors
   - Check for rate limit errors

3. Dependencies: Are they healthy?
   - postgres-primary: Reachable?
   - redis: Memory usage <85%?
   - payment-provider-api: Responding?

## Mitigation (15 minutes)
Option 1: Circuit breaker (if provider down)
  - kubectl set env deployment/payment service CIRCUIT_BREAKER=true

Option 2: Reduce batch size (if throughput too high)
  - Scale down to 1 replica temporarily

Option 3: Rollback (if recent deploy)
  - Trigger previous version canary

## Escalation (>30 minutes)
- Page on-call payment team lead
- VP Engineering if still unresolved
```

### Post-Incident Review

After any SEV-1 or SEV-2 incident:

```
1. Impact Assessment (within 1 hour)
   - How long was service degraded?
   - How much error budget consumed?
   - Customer impact: orders lost? payments failed?

2. Root Cause Analysis (within 24 hours)
   - What was the root cause?
   - How did it slip past testing?
   - Was this a known risk?

3. Action Items (define improvements)
   - Better monitoring? (add another metric)
   - Better testing? (add scenario to staging)
   - Better documentation? (update runbook)

4. Timeline Update
   - Error Budget Dashboard automatically updated
   - Incident marked closed when error budget recovered
```

---

## Lab Exercises

### Exercise 1: Dashboard Navigation (15 min)

**Objective**: Become comfortable navigating SLO dashboards

**Steps**:
1. Open Grafana: https://grafana.instacommerce.dev
2. Find "Payment Service - SLO Dashboard"
3. Change time range from 7-day to 24-hour view
4. Find the Availability gauge - what's the current value?
5. Open the dashboard "Error Budget Burn Analysis"
6. Which service has burned the most budget this month?
7. Find "Latency Percentiles" panel - is p99 within target?

**Success Criteria**:
- [ ] Can locate all 3 panel types
- [ ] Understand what each color means
- [ ] Can identify healthy vs concerning metrics

### Exercise 2: Alert Simulation (30 min)

**Objective**: Practice responding to SEV-1 alert

**Setup**:
```bash
# In staging environment, trigger test alert:
kubectl port-forward -n payment svc/payment-service 8080:8080
# In another terminal:
for i in {1..100}; do
  curl -s http://localhost:8080/api/invalid | grep -q "500" &
done
```

**Tasks**:
1. Monitor Prometheus for alert triggering
2. When SEV-1 fires:
   - Open dashboard link
   - Identify the error rate value
   - Look for root cause in "Error Rate" panel spike
   - Document findings

**Success Criteria**:
- [ ] Alert recognized within 2 minutes
- [ ] Dashboard opened and interpreted
- [ ] Root cause identified (injected errors)

### Exercise 3: Error Budget Calculation (20 min)

**Objective**: Manually calculate error budget for a service

**Scenario**:
```
Service: Order Service
SLO Target: 99.9%
Month: March (31 days)

Today: March 15 (middle of month)
Incidents so far:
  - Mar 5: 5 minutes downtime
  - Mar 10: 2 minutes downtime
  - Mar 12: 0.5 minute downtime
```

**Calculate**:
1. Monthly error budget = ?
2. Budget consumed = ?
3. Budget remaining = ?
4. Days remaining in month = ?
5. Current burn rate (budget/day) = ?
6. Will budget last until month-end? = ?
7. What's the recommendation? = ?

**Answers**:
1. (100 - 99.9) × 43,200 / 100 = 43.2 minutes/month
2. 5 + 2 + 0.5 = 7.5 minutes
3. 43.2 - 7.5 = 35.7 minutes remaining
4. 31 - 15 = 16 days
5. 35.7 / 16 = 2.23 minutes/day
6. Yes, 2.23 × 16 = 35.6 < 43.2
7. Budget is healthy, continue normal velocity

### Exercise 4: Multi-Window Alert Logic (25 min)

**Objective**: Understand when different alerts fire

**Scenario**: Payment service experiencing 2% error rate

**Question 1**: Fast Burn Alert (>10% error rate in 5min)?
- No, 2% < 10%
- Alert will NOT fire

**Question 2**: Medium Burn Alert (>5% error rate in 30min)?
- No, 2% < 5%
- Alert will NOT fire

**Question 3**: Slow Burn Alert (>1% error rate in 6h)?
- Yes, 2% > 1%
- Alert WILL fire after 30 minutes of sustained 2% error rate

**Question 4**: What's the burn rate?
- SLO = 99.95%, so error budget rate = 0.05%
- Current error rate = 2%
- Burn rate = 2% / 0.05% = 40x normal
- At this rate, monthly budget exhausted in 43.2 / 40 = 1.08 days

**Discussion**:
- Should this trigger urgent action? Maybe not (not SEV-1)
- Should we watch closely? Definitely (40x burn rate)
- If sustained for 6 hours→SEV-3→review
- If spikes to 5%→SEV-2→incident

---

## Reference Materials

### Quick Reference Card

```
SLO Response Checklist
═════════════════════════════════════════════════════════

□ SEV-1 Alert Fires (Fast Burn)
  Actions:
  □ Acknowledge PagerDuty
  □ Open dashboard (link in alert)
  □ Check "Error Rate" and "Latency" panels
  □ If infrastructure issue: notify ops
  □ If code issue: page service owner

  Success: Resolved <5 min or escalated

□ SEV-2 Alert Fires (Medium Burn)
  Actions:
  □ Review Jira ticket auto-created
  □ Run query: service:{{ service }} status:error
  □ Compare current metrics to baseline
  □ Identify trend (improving? worsening?)

  Success: Root cause identified <30 min

□ SEV-3 Alert Fires (Slow Burn)
  Actions:
  □ Added to weekly SRE meeting agenda
  □ Investigate root cause within week
  □ File improvement ticket

  Success: Action plan defined

Error Budget Thresholds
═════════════════════════════════════════════════════════

Remaining    │ Status     │ Action
─────────────┼────────────┼──────────────────────────
>50%         │ ✓ HEALTHY  │ Continue normal velocity
30-50%       │ ⚠ WARNING  │ Monitor closely
<30%         │ ⚠ ALERT    │ Prepare feature freeze
>100% spent  │ ✗ CRITICAL │ Feature freeze active

Dashboard Panel Colors
═════════════════════════════════════════════════════════

Panel           │ GREEN        │ YELLOW      │ RED
────────────────┼──────────────┼─────────────┼────────────
Availability    │ > SLO        │ SLO ± 1%    │ < SLO - 1%
Error Budget    │ > 50%        │ 30-50%      │ < 30%
P99 Latency     │ < target     │ 70-100%     │ > target
Error Rate      │ < 0.1%       │ 0.1-0.5%    │ > 0.5%
Burn Rate       │ < 1x normal  │ 1-5x        │ > 5x
```

### Key Contacts

```
Team              │ Slack Channel      │ On-Call Rotation
──────────────────┼────────────────────┼────────────────────
Payment Team      │ #payment-service   │ pagerduty.com/teams/payment
Order Team        │ #order-service     │ pagerduty.com/teams/order
Platform Team     │ #platform          │ pagerduty.com/teams/platform
SRE Team          │ #sre               │ pagerduty.com/teams/sre
Incident Mgmt     │ #incidents         │ Use /incident command
```

### Useful Queries

```
# Current availability (30-day window)
100 * (1 - (sum(increase(http_requests_total{status=~"5.."}[30d]))
 / sum(increase(http_requests_total[30d]))))

# Error budget consumed %
100 * (1 - (
  (sum(increase(http_requests_total{status!~"5.."}[30d])) by (service))
  / sum(increase(http_requests_total[30d])) by (service)
)) / (100 - SLO_TARGET)

# Services over SLO
# (shows services currently healthy)
sum by (service) (rate(http_requests_total{status!~"5.."}[5m]))
  / sum by (service) (rate(http_requests_total[5m])) > (SLO_TARGET/100)

# Services under SLO
# (shows services currently degraded)
sum by (service) (rate(http_requests_total{status!~"5.."}[5m]))
  / sum by (service) (rate(http_requests_total[5m])) < (SLO_TARGET/100)
```

### Training Completion Certification

```
Upon completing this training, you will:

✓ Understand SLO basics (targets, budgets, windows)
✓ Calculate error budgets manually
✓ Navigate all 31 SLO dashboards
✓ Interpret multi-window burn-rate alerts
✓ Respond appropriately to SEV-1/2/3 incidents
✓ Use runbooks for troubleshooting
✓ Conduct post-incident reviews

Certification Requirements:
[ ] Pass 4 lab exercises (>80% score)
[ ] Shadow one on-call rotation (2 hours)
[ ] Lead one post-incident review
[ ] Quiz: 15 questions on SLO concepts (>90%)

Recertification: Annually or after major changes
```

---

## Appendix: Glossary

| Term | Definition |
|------|-----------|
| **SLO** | Service Level Objective - the reliability target |
| **Error Budget** | Allowed downtime/errors before feature freeze |
| **Burn Rate** | How fast error budget is being consumed |
| **p99** | 99th percentile - worst 1% of requests |
| **SEV-1** | Critical severity - page immediately |
| **SEV-2** | Warning severity - create incident |
| **SEV-3** | Info severity - schedule review |
| **Fast Burn** | 5-minute window alert (>10% error) |
| **Medium Burn** | 30-minute window alert (>5% error) |
| **Slow Burn** | 6-hour window alert (>1% error) |
| **Cascading Failure** | One service down causes others to fail |
| **Feature Freeze** | No new features when budget exhausted |

---

## Next Steps

1. **Schedule Training Session**
   - 4 hours total (2h workshop + 2h hands-on lab)
   - Target: All SRE team + service owners
   - When: Week of Mar 31 (after Phase 2 stabilization)

2. **Access Dashboards**
   - Bookmark: https://grafana.instacommerce.dev/d/slo-overview
   - All service dashboards under "SLO Dashboards" folder

3. **Get On-Call Rotation**
   - Register for SRE team's PagerDuty rotation
   - Start with "shadow" mode (no pages for 1 week)
   - Full rotation begins after training certification

4. **Review Runbooks**
   - Each service has dedicated runbook
   - Add to browser favorites for quick access during incidents

---

**Generated**: 2026-03-22 (Wave 40 Phase 3)
**Version**: 1.0
**Last Updated**: 2026-03-22
