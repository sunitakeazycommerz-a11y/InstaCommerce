# Escalation Matrix

## Priority Definitions

| Priority | Definition | Example |
|----------|------------|---------|
| **P0** | Complete service outage or data loss affecting >10% of users | Payment processing down, checkout failures |
| **P1** | Significant degradation affecting user experience | High latency, partial functionality loss |
| **P2** | Limited impact, workaround available | Single feature broken, non-critical service issues |
| **P3** | Minor issue, no immediate impact | Documentation bugs, minor UI issues |

## Response Time Requirements

| Priority | Initial Response | Escalation | Resolution Target |
|----------|------------------|------------|-------------------|
| **P0** | 5 minutes | Immediate | 1 hour |
| **P1** | 15 minutes | 30 minutes | 4 hours |
| **P2** | 4 hours | 24 hours | 1 week |
| **P3** | 1 business day | Weekly review | Next sprint |

## Escalation Paths

### P0 Escalation Chain

```
1. Primary On-Call (5 min) 
   ↓
2. Secondary On-Call (10 min)
   ↓
3. Service Team Lead (15 min)
   ↓
4. Engineering Manager (30 min)
   ↓
5. VP Engineering (1 hour)
```

### P1 Escalation Chain

```
1. Primary On-Call (15 min)
   ↓
2. Secondary On-Call (30 min)
   ↓
3. Service Team Lead (1 hour)
   ↓
4. Engineering Manager (2 hours)
```

## Contact Methods

| Method | When to Use | Response Time |
|--------|-------------|---------------|
| PagerDuty | P0/P1 incidents | Immediate |
| Slack #sre-oncall | P1/P2 during business hours | 15 min |
| Slack #service-alerts | Automated alerts | N/A |
| Email oncall@instacommerce.com | P2/P3, non-urgent | 4 hours |

## On-Call Responsibilities

### During Incident

1. **Acknowledge** - Respond to page within SLA
2. **Assess** - Determine severity and impact
3. **Communicate** - Update incident channel
4. **Mitigate** - Apply fix or workaround
5. **Resolve** - Confirm service restored
6. **Document** - Create incident timeline

### Post-Incident

1. Schedule post-mortem within 48 hours for P0/P1
2. Document root cause and contributing factors
3. Create action items with owners and deadlines
4. Share learnings at Weekly Forum

## Escalation Decision Tree

```
Is the service completely down?
├── Yes → P0
└── No → Are >10% of requests failing?
    ├── Yes → P0
    └── No → Is user experience significantly degraded?
        ├── Yes → P1
        └── No → Is there a workaround?
            ├── No → P2
            └── Yes → P3
```

## Service-Specific Contacts

| Service | PagerDuty Service | Slack Channel |
|---------|-------------------|---------------|
| checkout-orchestrator | checkout-critical | #checkout-oncall |
| payment-service | payments-critical | #payments-oncall |
| order-service | orders-critical | #orders-oncall |
| fulfillment-service | fulfillment-critical | #fulfillment-oncall |
| identity-service | identity-critical | #identity-oncall |

## Incident Communication Templates

### P0 Initial Communication

```
🚨 P0 INCIDENT: [Service Name]
Status: Investigating
Impact: [Description of user impact]
ETA: [If known, otherwise "Assessing"]
Incident Commander: @[name]
Updates: #incident-[id]
```

### P0 Resolution Communication

```
✅ RESOLVED: [Service Name]
Duration: [Start time] - [End time] ([duration])
Root Cause: [Brief description]
Impact: [Number of affected users/requests]
Post-mortem: [Link to be shared within 48 hours]
```
