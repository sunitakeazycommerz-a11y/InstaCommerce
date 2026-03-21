# Reliability Forum

## Purpose

The Reliability Forum is a monthly gathering to review service-level objectives (SLOs), error budgets, and overall platform reliability. This forum drives accountability for reliability and ensures engineering investment aligns with reliability goals.

## Schedule

**When**: First Friday of each month, 10 AM UTC
**Duration**: 60 minutes
**Attendees**: SRE team, service owners, engineering leadership

## Forum Structure

### 1. SLO Compliance Report (20 min)

Review SLO performance across all services for the past month.

**Metrics Reviewed**:
- Availability (target: 99.9% for critical services)
- Latency P50, P95, P99
- Error rate
- Throughput

**Dashboard**: [Grafana SLO Dashboard](../observability/grafana-dashboards.md)

### 2. Error Budget Status (15 min)

Track error budget consumption and burn rate.

**Error Budget Calculation**:
```
Monthly Error Budget = 30 days × (1 - SLO Target)

Example (99.9% SLO):
Budget = 30 days × 0.001 = 43.2 minutes of downtime allowed
```

**Budget Status Categories**:
| Status | Budget Remaining | Action |
|--------|------------------|--------|
| 🟢 Healthy | >50% | Continue normal development |
| 🟡 Caution | 25-50% | Prioritize reliability work |
| 🔴 Critical | <25% | Freeze non-critical deployments |
| ⚫ Exhausted | 0% | Mandatory reliability sprint |

### 3. Incident Review Summary (10 min)

High-level summary of incidents and trends.

**Metrics**:
- Number of P0/P1 incidents
- MTTR (Mean Time to Recovery)
- Repeat incidents
- Post-mortem completion rate

### 4. Reliability Initiatives (10 min)

Progress on ongoing reliability improvement projects.

**Example Initiatives**:
- Circuit breaker implementation
- Chaos engineering exercises
- Capacity planning
- Disaster recovery testing

### 5. Open Discussion (5 min)

## SLO Definitions

### Critical Services (99.9% Availability)

| Service | Availability | Latency P99 | Error Rate |
|---------|--------------|-------------|------------|
| checkout-orchestrator | 99.9% | <2s | <0.1% |
| payment-service | 99.9% | <1s | <0.1% |
| order-service | 99.9% | <500ms | <0.1% |
| identity-service | 99.9% | <200ms | <0.1% |

### Standard Services (99.5% Availability)

| Service | Availability | Latency P99 | Error Rate |
|---------|--------------|-------------|------------|
| catalog-service | 99.5% | <500ms | <0.5% |
| search-service | 99.5% | <1s | <0.5% |
| notification-service | 99.5% | <5s | <1% |

## Error Budget Policy

### When Budget is Healthy (>50%)

- Normal development velocity
- Feature work prioritized
- Reliability improvements as capacity allows

### When Budget is Caution (25-50%)

- Review upcoming deployments for risk
- Prioritize reliability-related bugs
- Increase monitoring vigilance

### When Budget is Critical (<25%)

- Non-critical deployments require SRE approval
- Dedicate 50% of sprint to reliability
- Daily error budget review

### When Budget is Exhausted (0%)

- Feature freeze until budget recovers
- All hands on reliability
- Leadership escalation required

## Quarterly Reliability Goals

Set during the first Reliability Forum of each quarter.

**Example Goals**:
1. Reduce P0 incidents by 50%
2. Improve MTTR to <30 minutes
3. Achieve 100% post-mortem completion within 5 days
4. Complete disaster recovery test for all critical services

## Reporting

### Monthly Reliability Report

Distributed after each forum to:
- Engineering leadership
- Product management
- Operations team

**Report Contents**:
1. Executive summary
2. SLO compliance by service
3. Error budget status
4. Incident trends
5. Reliability initiative progress
6. Recommendations

### Dashboard Links

- [SLO Overview Dashboard](https://grafana.instacommerce.dev/d/slo-overview)
- [Error Budget Dashboard](https://grafana.instacommerce.dev/d/error-budget)
- [Incident Metrics Dashboard](https://grafana.instacommerce.dev/d/incidents)
