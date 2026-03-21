# Fraud Detection Service - Low-Level Design

```mermaid
classDiagram
    class FraudController {
        -service: FraudScoringService
        +postCheck(request) FraudCheckResponse
    }

    class AdminFraudController {
        -ruleService: FraudRuleService
        -blocklistService: BlocklistService
        +getAllRules() List[FraudRule]
        +createRule(request) FraudRule
        +updateRule(id, request) FraudRule
        +deleteRule(id)
    }

    class FraudScoringService {
        -ruleEval: RuleEvaluationService
        -velocityService: VelocityService
        -blocklistService: BlocklistService
        -outboxService: OutboxService
        +scoreTransaction(request) FraudCheckResponse
        -persistSignal(signal)
    }

    class FraudRequest {
        -order_id: UUID
        -customer_id: UUID
        -amount: BigDecimal
        -device_id: String
        -ip_address: String
        -merchant_category: String
    }

    class FraudCheckResponse {
        -risk_level: RiskLevel
        -score: int
        -action: FraudAction
        -reasons: List[String]
    }

    class RuleEvaluationService {
        -repository: FraudRuleRepository
        -cache: Cache[List[FraudRule]]
        +evaluateRules(request) Dict[RuleName, Score]
        -evaluateVelocity(request) Score
        -evaluateAmount(request) Score
        -evaluateDevice(request) Score
        -evaluateGeo(request) Score
        -evaluatePattern(request) Score
    }

    class FraudRule {
        -id: UUID
        -rule_type: RuleType
        -name: String
        -condition: JSON
        -impact_score: int
        -enabled: boolean
    }

    class VelocityService {
        -repository: VelocityCounterRepository
        +incrementCounter(key, window) int
        +getCount(key, window) int
        +getCountDifference(old, new) int
    }

    class BlocklistService {
        -repository: BlockedEntityRepository
        +isBlocked(entity_id) boolean
        +blockEntity(entity_id, reason, expiry)
        +unblockEntity(entity_id)
    }

    class RiskLevel {
        -LOW: 0-25
        -MEDIUM: 26-50
        -HIGH: 51-75
        -CRITICAL: 76-100
    }

    class FraudAction {
        -ALLOW: green
        -FLAG: yellow
        -REVIEW: orange
        -BLOCK: red
        +fromRiskLevel(level) FraudAction
        +escalate() FraudAction
    }

    class OutboxService {
        -repository: OutboxEventRepository
        +publishFraudSignal(signal) void
    }

    class MetricsCollector {
        -check_counter: Counter
        -score_histogram: Histogram
        -rule_trigger_counter: Counter
        +recordCheck(outcome, latency)
        +recordRuleTrigger(rule_name)
    }

    FraudController --> FraudScoringService
    FraudScoringService --> RuleEvaluationService
    FraudScoringService --> VelocityService
    FraudScoringService --> BlocklistService
    FraudScoringService --> OutboxService
    RuleEvaluationService --> FraudRule
    RiskLevel --> FraudAction
    FraudScoringService --> MetricsCollector
    FraudScoringService --> FraudCheckResponse
```

## Component Responsibilities

| Component | Responsibility |
|-----------|-----------------|
| **FraudController** | HTTP POST /fraud/check endpoint |
| **AdminFraudController** | Rule CRUD operations (admin-only) |
| **FraudScoringService** | Orchestrate scoring pipeline |
| **RuleEvaluationService** | Evaluate all 5 rule types |
| **VelocityService** | UPSERT time-windowed counters |
| **BlocklistService** | Check/add/remove blocked entities |
| **OutboxService** | Transactional fraud.events publishing |
| **MetricsCollector** | Prometheus metrics emission |
