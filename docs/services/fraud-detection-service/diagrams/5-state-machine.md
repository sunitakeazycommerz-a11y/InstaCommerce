# Fraud Detection Service - Fraud Scoring State Machine

```mermaid
stateDiagram-v2
    [*] --> Received

    Received --> Parsed: parse_json
    Parsed --> Validated: validate_request
    Validated --> BlocklistCheck: context_extracted

    Validated -->|Validation Error| Error400
    Error400 --> ErrorResponse
    ErrorResponse --> [*]

    BlocklistCheck --> Blocked: entity_in_blocklist
    BlocklistCheck --> NotBlocked: entity_not_blocked

    Blocked --> BlockedScore: score_100
    BlockedScore --> Response: action_block

    NotBlocked --> RuleEval: load_rules
    RuleEval --> VelocityEval: evaluate_velocity
    VelocityEval --> AmountEval: increment_counters

    AmountEval --> DeviceEval: evaluate_amount
    DeviceEval --> GeoEval: evaluate_device
    GeoEval --> PatternEval: evaluate_geo
    PatternEval --> ScoreAgg: evaluate_pattern

    ScoreAgg --> Aggregate: sum_impacts
    Aggregate --> RiskMap: determine_risk_level

    RiskMap --> RiskLow: score_0_to_25
    RiskLow --> ActionAllow: allow_action

    RiskMap --> RiskMed: score_26_to_50
    RiskMed --> ActionFlag: flag_action

    RiskMap --> RiskHigh: score_51_to_75
    RiskHigh --> ActionReview: review_action

    RiskMap --> RiskCrit: score_76_to_100
    RiskCrit --> ActionBlock: block_action

    ActionAllow --> SignalPersist: response_determined
    ActionFlag --> SignalPersist
    ActionReview --> SignalPersist
    ActionBlock --> SignalPersist

    SignalPersist --> OutboxPublish: signal_stored
    OutboxPublish --> MetricsRec: fraud_signal_published
    MetricsRec --> Response: metrics_recorded

    Response --> [*]

    note right of BlocklistCheck
        Customer or payment method
        in blocklist?
    end note

    note right of RuleEval
        Load rules from Caffeine
        cache (invalidate on admin update)
    end note

    note right of VelocityEval
        UPSERT 1h and 24h
        time-windowed counters
    end note

    note right of ScoreAgg
        Sum impact scores
        from all triggered rules
    end note

    note right of RiskMap
        Map score to RiskLevel:
        LOW/MEDIUM/HIGH/CRITICAL
    end note
```

## State Transitions

- **Received→Parsed**: JSON parsing initiated
- **Parsed→Validated**: Request structure validation
- **Validated→BlocklistCheck**: Context extraction complete
- **BlocklistCheck→Blocked**: Customer/payment method in list
- **BlocklistCheck→NotBlocked**: Not in list, proceed to rules
- **NotBlocked→RuleEval**: Load rules from cache
- **RuleEval→VelocityEval**: Velocity rule evaluation
- **VelocityEval→AmountEval**: Amount rule evaluation
- **AmountEval→DeviceEval**: Device rule evaluation
- **DeviceEval→GeoEval**: GEO/Haversine rule evaluation
- **GeoEval→PatternEval**: Pattern rule evaluation
- **PatternEval→ScoreAgg**: All rules evaluated
- **ScoreAgg→RiskMap**: Score aggregated, map to RiskLevel
- **RiskMap→ActionXxx**: Action determined from RiskLevel
- **ActionXxx→SignalPersist**: Persist fraud_signal
- **SignalPersist→OutboxPublish**: Publish to outbox
- **OutboxPublish→Response**: Metrics recorded, return response
