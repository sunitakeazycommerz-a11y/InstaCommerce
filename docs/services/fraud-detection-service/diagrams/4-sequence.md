# Fraud Detection Service - Fraud Scoring Sequence

```mermaid
sequenceDiagram
    actor Client as Order Service
    participant API as FraudController
    participant SCORING as FraudScoringService
    participant BLOCKLIST as BlocklistService
    participant RULES as RuleEvaluationService
    participant VELOCITY as VelocityService
    participant OUTBOX as OutboxService
    participant DB as PostgreSQL
    participant PROM as Prometheus

    Client ->> API: POST /fraud/check
    activate API

    API ->> SCORING: scoreTransaction(request)
    activate SCORING

    SCORING ->> BLOCKLIST: isBlocked(customer_id)
    activate BLOCKLIST
    BLOCKLIST ->> DB: SELECT from blocked_entities
    DB -->> BLOCKLIST: Optional[BlockedEntity]
    BLOCKLIST -->> SCORING: bool
    deactivate BLOCKLIST

    alt Blocked
        SCORING ->> SCORING: Score = 100, Action = BLOCK
    else Not Blocked
        SCORING ->> RULES: evaluateRules(request)
        activate RULES

        RULES ->> VELOCITY: incrementCounter(customer:1h)
        activate VELOCITY
        VELOCITY ->> DB: UPSERT velocity_counters
        VELOCITY -->> RULES: count
        deactivate VELOCITY

        RULES ->> RULES: calculateVelocityScore()
        RULES ->> RULES: calculateAmountScore()
        RULES ->> RULES: calculateDeviceScore()
        RULES ->> RULES: calculateGeoScore()
        RULES ->> RULES: calculatePatternScore()

        RULES -->> SCORING: Dict[rule_type, score]
        deactivate RULES

        SCORING ->> SCORING: aggregateScore()
        SCORING ->> SCORING: determineRiskLevel()
        SCORING ->> SCORING: mapToAction()
    end

    SCORING ->> DB: INSERT into fraud_signals
    SCORING ->> OUTBOX: publishFraudSignal(signal)
    activate OUTBOX
    OUTBOX ->> DB: INSERT into outbox_events
    OUTBOX -->> SCORING: void
    deactivate OUTBOX

    SCORING ->> PROM: check_counter++
    SCORING ->> PROM: score_histogram.observe(score)
    activate PROM
    PROM -->> SCORING: void
    deactivate PROM

    SCORING -->> API: FraudCheckResponse
    deactivate SCORING

    API -->> Client: 200 {risk_level, action, reasons}
    deactivate API
```

## Sequence Patterns

- **Blocklist Early Exit**: Immediate BLOCK if customer in list
- **Rule Evaluation**: Sequential evaluation of 5 rule types
- **Velocity UPSERT**: Increment time-windowed counters atomically
- **Score Aggregation**: Sum triggered rule impacts
- **Risk Level Mapping**: Deterministic mapping to RiskLevel
- **Transactional Outbox**: Fraud signal persisted atomically
- **Metrics Emission**: Per-rule triggers + overall latency
