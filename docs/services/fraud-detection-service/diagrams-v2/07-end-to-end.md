# Fraud Detection Service - End-to-End Flows

## Complete Order to Fraud Decision Flow (E2E)

```mermaid
graph TB
    Customer["👤 Customer<br/>(Mobile App)"]
    MobileBFF["📱 Mobile BFF"]
    CartService["🛒 Cart Service"]
    CheckoutOrch["🔄 Checkout Orchestrator<br/>(Temporal)"]
    
    ALB["⚖️ AWS ALB<br/>(SSL/TLS)"]
    
    subgraph FraudService["Fraud Detection Service"]
        FraudController["🎯 FraudController"]
        ScoreOrchestrator["📊 FraudScoreOrchestrator"]
        
        subgraph scoring["Parallel Scoring Pipeline"]
            VelocityCheck["⚡ VelocityCheckService"]
            RuleEngine["📜 RuleEngineService<br/>(Drools)"]
            MLScoring["🤖 MLScoringService"]
            DeviceCheck["📱 DeviceFingerprintService"]
        end
        
        DecisionEngine["⚖️ DecisionEngine"]
        ReviewQueue["👥 ReviewQueueService"]
    end
    
    subgraph DataStores["Data Stores"]
        Redis["⚡ Redis Cluster<br/>(velocity, cache)"]
        PostgreSQL["🐘 PostgreSQL<br/>(fraud_db)"]
        Kafka["📬 Kafka<br/>(events)"]
    end
    
    subgraph External["External Services"]
        MLInference["🤖 ML Inference<br/>(gRPC)"]
        ThreatMetrix["📱 ThreatMetrix<br/>(Device API)"]
        FeatureStore["📊 Feature Store"]
    end
    
    subgraph ReviewPath["Manual Review Path"]
        AnalystDash["👨‍💼 Analyst Dashboard"]
        Analyst["👤 Fraud Analyst"]
        DecisionAPI["⚖️ Decision API"]
    end
    
    PaymentService["💳 Payment Service"]
    OrderService["📋 Order Service"]
    
    Customer -->|1. Place Order| MobileBFF
    MobileBFF -->|2. Create checkout| CartService
    CartService -->|3. Start checkout saga| CheckoutOrch
    
    CheckoutOrch -->|4. POST /api/fraud/check<br/>{user, amount, device, items}| ALB
    ALB -->|5. Route| FraudController
    
    FraudController -->|6. Orchestrate scoring| ScoreOrchestrator
    
    ScoreOrchestrator -->|7a. Check velocity| VelocityCheck
    VelocityCheck -->|Redis MULTI/EXEC| Redis
    
    ScoreOrchestrator -->|7b. Evaluate rules| RuleEngine
    RuleEngine -->|Rule cache| Redis
    
    ScoreOrchestrator -->|7c. Get ML score| MLScoring
    MLScoring -->|Get features| FeatureStore
    MLScoring -->|gRPC predict| MLInference
    
    ScoreOrchestrator -->|7d. Verify device| DeviceCheck
    DeviceCheck -->|Device cache| Redis
    DeviceCheck -->|API call| ThreatMetrix
    
    ScoreOrchestrator -->|8. Aggregate scores| DecisionEngine
    
    DecisionEngine -->|9. Persist score| PostgreSQL
    DecisionEngine -->|10. Emit event| Kafka
    
    DecisionEngine -->|11a. Score < 30| AutoApprove["✅ AUTO_APPROVE"]
    DecisionEngine -->|11b. Score 30-70| ReviewQueue
    DecisionEngine -->|11c. Score > 70| AutoReject["❌ AUTO_REJECT"]
    
    ReviewQueue -->|12. Queue review| Kafka
    Kafka -->|13. Notify| AnalystDash
    AnalystDash -->|14. Display| Analyst
    Analyst -->|15. Decision| DecisionAPI
    DecisionAPI -->|16. Update| PostgreSQL
    DecisionAPI -->|17. Emit| Kafka
    
    AutoApprove -->|18a. Response| FraudController
    AutoReject -->|18b. Response| FraudController
    DecisionAPI -->|18c. Callback| CheckoutOrch
    
    FraudController -->|19. FraudCheckResponse| CheckoutOrch
    
    CheckoutOrch -->|20a. Approved| PaymentService
    CheckoutOrch -->|20b. Rejected| OrderCancelled["❌ Order Cancelled"]
    
    PaymentService -->|21. Process payment| PaymentService
    PaymentService -->|22. Payment confirmed| OrderService
    
    OrderService -->|23. Create order| OrderService
    OrderService -->|24. Order confirmed| CheckoutOrch
    
    CheckoutOrch -->|25. Success| MobileBFF
    MobileBFF -->|26. Order placed!| Customer
    
    style FraudService fill:#FFEBEE,color:#000,stroke:#333,stroke-width:2px
    style AutoApprove fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style AutoReject fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style DecisionEngine fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
```

## Request Flow with All Decision Paths

```mermaid
graph TB
    Request["POST /api/fraud/check<br/>{user_id, amount, device_id}"]
    
    subgraph happy["Happy Path: AUTO_APPROVE (<200ms)"]
        H1["✅ Auth validated<br/>(~3ms)"]
        H2["✅ Rate limit OK<br/>(~2ms)"]
        H3["⚡ Parallel scoring<br/>(~80ms p99)"]
        H4["✅ Score: 25<br/>(LOW risk)"]
        H5["✅ AUTO_APPROVE<br/>Total: ~95ms"]
    end
    
    subgraph review["Review Path: MANUAL_REVIEW"]
        R1["⚠️ Score: 52<br/>(MEDIUM risk)"]
        R2["📝 Queue for review<br/>(async)"]
        R3["⏱️ SLA timer started<br/>(1 hour)"]
        R4["👤 Analyst reviews<br/>(~10 min avg)"]
        R5["⚖️ Decision: APPROVE<br/>or REJECT"]
        R6["📬 Callback to<br/>checkout-orchestrator"]
    end
    
    subgraph reject["Reject Path: AUTO_REJECT"]
        X1["❌ Score: 85<br/>(HIGH risk)"]
        X2["🚫 AUTO_REJECT"]
        X3["📝 Log: FRAUD_BLOCKED"]
        X4["📬 Emit<br/>TransactionRejected"]
        X5["❌ Return to checkout<br/>Total: ~100ms"]
    end
    
    subgraph errors["Error Paths"]
        E1["❌ Invalid request<br/>→ 400 Bad Request"]
        E2["❌ Auth failed<br/>→ 401 Unauthorized"]
        E3["❌ Rate limited<br/>→ 429 Too Many Requests"]
        E4["⚠️ Service timeout<br/>→ Fallback scoring"]
    end
    
    Request --> Validate{"Valid<br/>request?"}
    
    Validate -->|No| E1
    Validate -->|Yes| H1
    
    H1 --> Auth{"Auth<br/>OK?"}
    Auth -->|No| E2
    Auth -->|Yes| H2
    
    H2 --> RateLimit{"Rate<br/>OK?"}
    RateLimit -->|No| E3
    RateLimit -->|Yes| H3
    
    H3 --> Scoring{"Scoring<br/>OK?"}
    Scoring -->|Timeout| E4
    E4 --> ScoreCalc
    Scoring -->|OK| ScoreCalc{"Score<br/>range?"}
    
    ScoreCalc -->|< 30| H4 --> H5
    ScoreCalc -->|30-70| R1 --> R2 --> R3 --> R4 --> R5 --> R6
    ScoreCalc -->|> 70| X1 --> X2 --> X3 --> X4 --> X5
    
    EndNode([Response Sent])
    H5 --> EndNode
    R6 --> EndNode
    X5 --> EndNode
    
    style happy fill:#E8F5E9,color:#000,stroke:#333,stroke-width:2px
    style review fill:#FFF3E0,color:#000,stroke:#333,stroke-width:2px
    style reject fill:#FFEBEE,color:#000,stroke:#333,stroke-width:2px
    style H5 fill:#7ED321,color:#000,stroke:#333,stroke-width:3px
    style X5 fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:3px
```

## Scoring Pipeline Deep Dive

```mermaid
graph TB
    Request["FraudCheckRequest"]
    
    subgraph parallel["Parallel Execution (max 150ms)"]
        subgraph velocity["⚡ Velocity (20% weight)"]
            V1["Check user orders/hour<br/>Limit: 10"]
            V2["Check device txns/day<br/>Limit: 50"]
            V3["Check IP attempts/10min<br/>Limit: 20"]
            V4["Check amount/day<br/>Limit: ₹50,000"]
            VResult["Velocity Score: 15<br/>Latency: 18ms"]
        end
        
        subgraph rules["📜 Rules (30% weight)"]
            R1["Load active rules<br/>(cached 5min)"]
            R2["Build Drools facts"]
            R3["Fire all rules"]
            R4["Collect triggered:<br/>- NEW_ACCOUNT: +10<br/>- HIGH_VALUE: +15"]
            RResult["Rules Score: 25<br/>Latency: 32ms"]
        end
        
        subgraph ml["🤖 ML (40% weight)"]
            M1["Extract 50+ features"]
            M2["Get user history<br/>from feature store"]
            M3["gRPC to ML Inference<br/>XGBoost predict"]
            M4["Calibrate & normalize<br/>prob: 0.35 → score: 35"]
            MResult["ML Score: 35<br/>Latency: 78ms"]
        end
        
        subgraph device["📱 Device (10% weight)"]
            D1["Check device cache"]
            D2["Query ThreatMetrix<br/>(if cache miss)"]
            D3["Check user-device<br/>associations"]
            D4["Calculate trust<br/>(1000 - risk) / 10"]
            DResult["Device Score: 80<br/>(trust = high)<br/>Latency: 45ms"]
        end
    end
    
    Aggregation["Score Aggregation"]
    
    Calc1["velocity: 15 × 0.2 = 3.0"]
    Calc2["rules: 25 × 0.3 = 7.5"]
    Calc3["ml: 35 × 0.4 = 14.0"]
    Calc4["device: (100-80) × 0.1 = 2.0"]
    
    Total["Total Score: 26.5<br/>(rounded to 27)"]
    
    Decision{"Score<br/>threshold?"}
    
    Approve["✅ AUTO_APPROVE<br/>Score < 30"]
    Review["👥 MANUAL_REVIEW<br/>Score 30-70"]
    Reject["❌ AUTO_REJECT<br/>Score > 70"]
    
    Request --> parallel
    
    V1 --> V2 --> V3 --> V4 --> VResult
    R1 --> R2 --> R3 --> R4 --> RResult
    M1 --> M2 --> M3 --> M4 --> MResult
    D1 --> D2 --> D3 --> D4 --> DResult
    
    VResult --> Aggregation
    RResult --> Aggregation
    MResult --> Aggregation
    DResult --> Aggregation
    
    Aggregation --> Calc1
    Aggregation --> Calc2
    Aggregation --> Calc3
    Aggregation --> Calc4
    
    Calc1 --> Total
    Calc2 --> Total
    Calc3 --> Total
    Calc4 --> Total
    
    Total --> Decision
    Decision -->|< 30| Approve
    Decision -->|30-70| Review
    Decision -->|> 70| Reject
    
    style Approve fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style Review fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style Reject fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style Total fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
```

## Manual Review Complete Journey

```mermaid
graph TB
    subgraph trigger["Review Triggered"]
        T1["Fraud score: 55<br/>Decision: MANUAL_REVIEW"]
        T2["Calculate priority<br/>→ MEDIUM"]
        T3["Set SLA: 1 hour"]
        T4["Insert into fraud_reviews<br/>status: PENDING"]
        T5["Emit ReviewQueued<br/>to Kafka"]
    end
    
    subgraph notify["Notification"]
        N1["Kafka consumer<br/>receives event"]
        N2["WebSocket push<br/>to analyst dashboard"]
        N3["Dashboard updates<br/>queue count: +1"]
        N4["🔔 Audio/visual alert"]
    end
    
    subgraph claim["Analyst Claims"]
        C1["👤 Analyst clicks 'Claim'"]
        C2["POST /reviews/{id}/claim"]
        C3["Check not already claimed<br/>(optimistic lock)"]
        C4["Update status: IN_PROGRESS<br/>analyst_id: {analyst}"]
        C5["Stop SLA timer<br/>(claimed within SLA ✓)"]
    end
    
    subgraph review["Review Process"]
        RE1["Load review context"]
        
        subgraph context["Review Context"]
            CTX1["📋 Transaction Details<br/>- Amount: ₹8,500<br/>- Items: Electronics<br/>- Payment: Card"]
            CTX2["👤 User History<br/>- Account age: 45 days<br/>- Orders: 12<br/>- Avg value: ₹2,100"]
            CTX3["🚨 Risk Factors<br/>- NEW_ACCOUNT: +10<br/>- HIGH_VALUE: +15<br/>- Score: 55"]
            CTX4["📱 Device Info<br/>- Trust: MEDIUM (77)<br/>- NEW_DEVICE_FOR_USER<br/>- OS: iOS 17.2"]
            CTX5["🔍 Similar Cases<br/>- 3 similar approved<br/>- 1 similar rejected<br/>- Pattern: legitimate"]
        end
        
        RE2["Analyst analyzes<br/>(avg 3-5 minutes)"]
    end
    
    subgraph decide["Decision"]
        D1["Analyst decision"]
        
        Approve["✅ APPROVE<br/>'Verified purchase, new device<br/>added recently but legitimate'"]
        Reject["❌ REJECT<br/>'Suspicious pattern,<br/>multiple declined cards'"]
        Escalate["⬆️ ESCALATE<br/>'Unusual pattern,<br/>need senior review'"]
    end
    
    subgraph complete["Completion"]
        COMP1["POST /api/fraud/decision<br/>{review_id, decision, notes}"]
        COMP2["Update fraud_reviews<br/>status: APPROVED"]
        COMP3["Update fraud_scores<br/>final_decision: APPROVED"]
        COMP4["Emit ReviewCompleted<br/>to Kafka"]
        COMP5["Callback to<br/>checkout-orchestrator"]
        COMP6["📊 Update metrics<br/>time_to_decision: 4min"]
    end
    
    subgraph outcome["Outcome"]
        OUT1["✅ Order proceeds<br/>Payment captured"]
        OUT2["❌ Order cancelled<br/>Payment voided"]
    end
    
    T1 --> T2 --> T3 --> T4 --> T5
    T5 --> N1 --> N2 --> N3 --> N4
    N4 --> C1 --> C2 --> C3 --> C4 --> C5
    C5 --> RE1 --> context
    context --> RE2 --> D1
    
    D1 -->|Approve| Approve --> COMP1
    D1 -->|Reject| Reject --> COMP1
    D1 -->|Escalate| Escalate --> COMP1
    
    COMP1 --> COMP2 --> COMP3 --> COMP4 --> COMP5 --> COMP6
    
    COMP6 -->|If APPROVED| OUT1
    COMP6 -->|If REJECTED| OUT2
    
    style Approve fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style Reject fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style Escalate fill:#9013FE,color:#fff,stroke:#333,stroke-width:2px
```

## Resilience & Degradation Patterns

```mermaid
graph TB
    Request["Fraud Check Request"]
    
    subgraph healthy["Healthy State"]
        HL["All services UP<br/>Full scoring pipeline<br/>P99: ~100ms"]
    end
    
    subgraph degraded["Degraded States"]
        subgraph ml_down["ML Service Down"]
            MLD1["Circuit breaker: OPEN"]
            MLD2["Fallback: Rule-based estimate"]
            MLD3["Flag: ML_SERVICE_UNAVAILABLE"]
            MLD4["Score adjusted: +10 penalty"]
        end
        
        subgraph device_down["ThreatMetrix Down"]
            DD1["Circuit breaker: OPEN"]
            DD2["Use cached device profile<br/>(if available)"]
            DD3["Default trust: 50 (neutral)"]
            DD4["Flag: DEVICE_SERVICE_DEGRADED"]
        end
        
        subgraph redis_down["Redis Cluster Down"]
            RD1["Fallback: In-memory Caffeine"]
            RD2["Reduced velocity accuracy"]
            RD3["Conservative limits applied"]
            RD4["Flag: VELOCITY_DEGRADED"]
        end
        
        subgraph rules_error["Rule Engine Error"]
            RE1["Skip rule evaluation"]
            RE2["Use ML + velocity only"]
            RE3["Flag: RULES_BYPASS"]
            RE4["Force MANUAL_REVIEW"]
        end
    end
    
    subgraph decisions["Decision Impact"]
        DEC1["Multiple degradations:<br/>Force MANUAL_REVIEW<br/>(conservative)"]
        DEC2["Single degradation:<br/>Continue with<br/>adjusted weights"]
        DEC3["Critical service down:<br/>Queue all for review<br/>(fail-safe)"]
    end
    
    subgraph monitoring["Monitoring & Alerts"]
        MON1["📊 Degradation metrics<br/>circuit_breaker_open_total"]
        MON2["🚨 PagerDuty alert<br/>if degraded > 5 min"]
        MON3["📝 Structured logs<br/>DEGRADED_SCORING"]
    end
    
    Request --> ServiceCheck{"All services<br/>healthy?"}
    
    ServiceCheck -->|Yes| healthy
    ServiceCheck -->|ML down| ml_down
    ServiceCheck -->|Device down| device_down
    ServiceCheck -->|Redis down| redis_down
    ServiceCheck -->|Rules error| rules_error
    
    ml_down --> decisions
    device_down --> decisions
    redis_down --> decisions
    rules_error --> decisions
    
    decisions --> monitoring
    
    style healthy fill:#E8F5E9,color:#000,stroke:#333,stroke-width:2px
    style degraded fill:#FFF3E0,color:#000,stroke:#333,stroke-width:2px
    style DEC1 fill:#F5A623,color:#000
    style DEC3 fill:#FF6B6B,color:#fff
```

## SLO & Monitoring Dashboard

```mermaid
graph LR
    FraudService["Fraud Detection Service"]
    
    subgraph slos["Service Level Objectives"]
        Latency["📊 Latency SLO<br/>P50: <100ms<br/>P99: <200ms"]
        Availability["✅ Availability SLO<br/>99.95%<br/>= 21.6 min/month downtime"]
        Accuracy["🎯 Accuracy SLO<br/>Precision: >95%<br/>Recall: >90%"]
        ReviewSLA["⏱️ Review SLA<br/>HIGH: 15 min<br/>MEDIUM: 1 hour<br/>LOW: 4 hours"]
    end
    
    subgraph metrics["Key Metrics"]
        M1["fraud_check_duration_ms<br/>(Histogram)"]
        M2["fraud_decisions_total<br/>(Counter by type)"]
        M3["review_queue_depth<br/>(Gauge)"]
        M4["review_sla_breaches<br/>(Counter)"]
        M5["false_positive_rate<br/>(Gauge, weekly)"]
        M6["chargeback_rate<br/>(Gauge, weekly)"]
    end
    
    subgraph alerts["Alert Rules"]
        A1["🔴 SEV-1<br/>P99 > 500ms<br/>OR queue > 100<br/>OR SLA breach > 10"]
        A2["🟠 SEV-2<br/>P99 > 300ms<br/>OR circuit breaker OPEN"]
        A3["🟡 SEV-3<br/>P99 > 200ms<br/>OR false_positive > 10%"]
    end
    
    subgraph dashboards["Grafana Dashboards"]
        D1["Real-time Ops<br/>- Score distribution<br/>- Decision breakdown<br/>- Latency percentiles"]
        D2["Review Operations<br/>- Queue depth<br/>- Time to decision<br/>- SLA compliance"]
        D3["Model Performance<br/>- Precision/Recall<br/>- Score calibration<br/>- Feature importance"]
        D4["Business Impact<br/>- Fraud blocked (₹)<br/>- False positives (₹)<br/>- Review cost"]
    end
    
    FraudService --> Latency
    FraudService --> Availability
    FraudService --> Accuracy
    FraudService --> ReviewSLA
    
    Latency --> M1
    Availability --> M2
    Accuracy --> M5
    ReviewSLA --> M4
    
    M1 --> A1
    M1 --> A2
    M1 --> A3
    
    M1 --> D1
    M3 --> D2
    M4 --> D2
    M5 --> D3
    M6 --> D4
    
    A1 --> Page["📱 PagerDuty<br/>Immediate page"]
    A2 --> Slack["💬 Slack<br/>#fraud-ops"]
    A3 --> JIRA["📋 JIRA ticket<br/>Review weekly"]
    
    style Latency fill:#7ED321,color:#000
    style Availability fill:#7ED321,color:#000
    style Accuracy fill:#7ED321,color:#000
    style A1 fill:#FF6B6B,color:#fff
    style A2 fill:#F5A623,color:#000
    style A3 fill:#FFC107,color:#000
```

## Cross-Service Integration Flow

```mermaid
graph TB
    subgraph upstream["Upstream Services (Callers)"]
        Checkout["🔄 Checkout Orchestrator<br/>Primary caller"]
        Payment["💳 Payment Service<br/>Pre-auth checks"]
        Order["📋 Order Service<br/>Post-order validation"]
        Identity["🆔 Identity Service<br/>Account verification"]
    end
    
    FraudService["🚨 Fraud Detection Service"]
    
    subgraph downstream["Downstream Dependencies"]
        MLInference["🤖 ML Inference Service<br/>Model predictions"]
        FeatureStore["📊 Feature Store<br/>User/transaction features"]
        ThreatMetrix["📱 ThreatMetrix<br/>Device intelligence"]
        IdentityLookup["🆔 Identity Service<br/>User history"]
    end
    
    subgraph data["Data Stores"]
        PostgreSQL["🐘 PostgreSQL<br/>fraud_db"]
        Redis["⚡ Redis Cluster<br/>Velocity counters"]
        Kafka["📬 Kafka<br/>Event streaming"]
    end
    
    subgraph consumers["Event Consumers"]
        Analytics["📈 Analytics Service<br/>fraud.score.calculated"]
        MLTraining["🎓 ML Training Pipeline<br/>fraud.review.completed"]
        Compliance["📋 Compliance Service<br/>fraud.transaction.rejected"]
        Notifications["📧 Notification Service<br/>fraud.review.queued"]
    end
    
    Checkout -->|POST /fraud/check| FraudService
    Payment -->|POST /fraud/check| FraudService
    Order -->|POST /fraud/validate| FraudService
    Identity -->|POST /fraud/account-check| FraudService
    
    FraudService -->|gRPC| MLInference
    FraudService -->|REST| FeatureStore
    FraudService -->|REST| ThreatMetrix
    FraudService -->|REST| IdentityLookup
    
    FraudService -->|Read/Write| PostgreSQL
    FraudService -->|Read/Write| Redis
    FraudService -->|Produce| Kafka
    
    Kafka -->|fraud.score.calculated| Analytics
    Kafka -->|fraud.review.completed| MLTraining
    Kafka -->|fraud.transaction.rejected| Compliance
    Kafka -->|fraud.review.queued| Notifications
    
    FraudService -->|Callback| Checkout
    FraudService -->|Event| Payment
    
    style FraudService fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:3px
    style Checkout fill:#4A90E2,color:#fff
    style Kafka fill:#50E3C2,color:#000
```
