# Fraud Detection Service - Low-Level Design

## Component Architecture

```mermaid
graph TB
    HTTPRequest["🌐 HTTP Request<br/>POST /api/fraud/check"]
    
    DispatcherServlet["DispatcherServlet<br/>(Spring MVC)"]
    AuthFilter["🔐 JwtAuthenticationFilter<br/>(validates service-to-service JWT)"]
    RateLimiter["⏱️ Rate Limiter<br/>(1000 req/sec per client)"]
    
    FraudController["🎯 FraudController<br/>(@RestController)"]
    
    subgraph scoring["Scoring Pipeline"]
        ScoreOrchestrator["📊 FraudScoreOrchestrator<br/>(coordinates all checks)"]
        VelocityService["⚡ VelocityCheckService<br/>(Redis sliding windows)"]
        RuleEngineService["📜 RuleEngineService<br/>(Drools evaluation)"]
        MLScoringService["🤖 MLScoringService<br/>(model inference)"]
        DeviceService["📱 DeviceFingerprintService<br/>(ThreatMetrix API)"]
    end
    
    subgraph rules["Rule Engine"]
        DroolsEngine["Drools Rule Engine<br/>(KIE container)"]
        RuleRepository["Rule Repository<br/>(PostgreSQL)"]
        RuleCache["Rule Cache<br/>(5min TTL)"]
    end
    
    subgraph ml["ML Scoring"]
        FeatureExtractor["Feature Extractor<br/>(50+ features)"]
        ModelClient["Model Client<br/>(gRPC to ml-inference)"]
        ScoreNormalizer["Score Normalizer<br/>(0-100 scale)"]
    end
    
    subgraph persistence["Data Layer"]
        FraudScoreRepo["FraudScoreRepository<br/>(JPA)"]
        ReviewQueueRepo["ReviewQueueRepository<br/>(JPA)"]
        RuleRepo["RuleRepository<br/>(JPA)"]
        OutboxRepo["OutboxRepository<br/>(CDC)"]
    end
    
    Redis["⚡ Redis Cluster<br/>(velocity counters)"]
    PostgreSQL["🐘 PostgreSQL<br/>(fraud_db)"]
    Kafka["📬 Kafka<br/>(fraud-events)"]
    
    HTTPRequest --> DispatcherServlet
    DispatcherServlet --> AuthFilter
    AuthFilter --> RateLimiter
    RateLimiter --> FraudController
    
    FraudController --> ScoreOrchestrator
    
    ScoreOrchestrator --> VelocityService
    ScoreOrchestrator --> RuleEngineService
    ScoreOrchestrator --> MLScoringService
    ScoreOrchestrator --> DeviceService
    
    VelocityService --> Redis
    
    RuleEngineService --> DroolsEngine
    DroolsEngine --> RuleCache
    RuleCache --> RuleRepository
    RuleRepository --> PostgreSQL
    
    MLScoringService --> FeatureExtractor
    FeatureExtractor --> ModelClient
    ModelClient --> ScoreNormalizer
    
    ScoreOrchestrator --> FraudScoreRepo
    ScoreOrchestrator --> ReviewQueueRepo
    ScoreOrchestrator --> OutboxRepo
    
    FraudScoreRepo --> PostgreSQL
    ReviewQueueRepo --> PostgreSQL
    OutboxRepo --> Kafka
    
    style ScoreOrchestrator fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style DroolsEngine fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style MLScoringService fill:#9013FE,color:#fff,stroke:#333,stroke-width:2px
    style VelocityService fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
```

## FraudScoreOrchestrator Implementation

```mermaid
graph TD
    A["FraudScoreOrchestrator.evaluate<br/>(FraudCheckRequest)"]
    B["Extract transaction context<br/>(user_id, device_id, amount, etc.)"]
    
    C["Launch parallel checks<br/>(CompletableFuture.allOf)"]
    
    D1["VelocityCheckService<br/>.checkVelocity()"]
    D2["RuleEngineService<br/>.evaluateRules()"]
    D3["MLScoringService<br/>.getScore()"]
    D4["DeviceFingerprintService<br/>.verifyDevice()"]
    
    E["Await all results<br/>(timeout: 200ms)"]
    
    F{{"All checks<br/>completed?"}}
    
    G["Aggregate scores<br/>- velocity: 20%<br/>- rules: 30%<br/>- ml: 40%<br/>- device: 10%"]
    
    H["Calculate composite score<br/>(0-100)"]
    
    I{{"Score<br/>threshold?"}}
    
    J["Score < 30:<br/>AUTO_APPROVE"]
    K["Score 30-70:<br/>MANUAL_REVIEW"]
    L["Score > 70:<br/>AUTO_REJECT"]
    
    M["Persist FraudScore<br/>(PostgreSQL)"]
    
    N["Emit FraudScoreCalculated<br/>event (Kafka)"]
    
    O["Return FraudCheckResponse"]
    
    P["Timeout handling:<br/>Use default score (50)<br/>Flag for review"]
    
    A --> B --> C
    
    C --> D1
    C --> D2
    C --> D3
    C --> D4
    
    D1 --> E
    D2 --> E
    D3 --> E
    D4 --> E
    
    E --> F
    F -->|Yes| G
    F -->|Timeout| P
    P --> G
    
    G --> H --> I
    
    I -->|Low| J
    I -->|Medium| K
    I -->|High| L
    
    J --> M
    K --> M
    L --> M
    
    M --> N --> O
    
    style A fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style G fill:#4A90E2,color:#fff
    style J fill:#7ED321,color:#000
    style K fill:#F5A623,color:#000
    style L fill:#FF6B6B,color:#fff
```

## Rule Engine Service (Drools)

```mermaid
graph TD
    Request["RuleEngineService<br/>.evaluateRules(context)"]
    
    LoadRules["Load active rules<br/>from cache or DB"]
    
    CacheCheck{{"Rules<br/>cached?"}}
    
    CacheHit["Use cached rules<br/>(TTL: 5 min)"]
    CacheMiss["Query RuleRepository<br/>WHERE active = true"]
    
    BuildContext["Build Drools facts<br/>- TransactionFact<br/>- UserHistoryFact<br/>- DeviceFact"]
    
    CreateSession["Create KIE session<br/>(stateless)"]
    
    InsertFacts["Insert facts<br/>into working memory"]
    
    FireRules["Fire all rules<br/>(rule evaluation)"]
    
    CollectResults["Collect triggered rules<br/>- rule_id<br/>- severity<br/>- action"]
    
    CalculateScore["Calculate rule score<br/>SUM(triggered_severity * weight)"]
    
    LogRules["Log triggered rules<br/>(audit trail)"]
    
    ReturnResult["Return RuleEngineResult<br/>{score, triggered_rules, flags}"]
    
    Request --> LoadRules
    LoadRules --> CacheCheck
    CacheCheck -->|Yes| CacheHit
    CacheCheck -->|No| CacheMiss
    CacheHit --> BuildContext
    CacheMiss --> BuildContext
    
    BuildContext --> CreateSession
    CreateSession --> InsertFacts
    InsertFacts --> FireRules
    FireRules --> CollectResults
    CollectResults --> CalculateScore
    CalculateScore --> LogRules
    LogRules --> ReturnResult
    
    style Request fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style FireRules fill:#9013FE,color:#fff
    style CalculateScore fill:#F5A623,color:#000
```

## ML Scoring Service

```mermaid
graph TD
    Request["MLScoringService<br/>.getScore(transaction)"]
    
    subgraph features["Feature Extraction (< 20ms)"]
        F1["User Features<br/>- account_age_days<br/>- order_count_30d<br/>- avg_order_value"]
        F2["Transaction Features<br/>- amount<br/>- payment_method<br/>- item_category"]
        F3["Behavioral Features<br/>- session_duration<br/>- pages_visited<br/>- cart_changes"]
        F4["Device Features<br/>- device_age<br/>- device_trust_score<br/>- fingerprint_match"]
        F5["Network Features<br/>- ip_reputation<br/>- geo_distance<br/>- proxy_detected"]
    end
    
    FeatureVector["Build feature vector<br/>(50+ dimensions)"]
    
    ModelCall["gRPC call to<br/>ml-inference-service<br/>FraudModel.predict()"]
    
    Timeout{{"Response<br/>within 100ms?"}}
    
    RawScore["Raw model output<br/>(probability 0.0-1.0)"]
    
    Normalize["Normalize to 0-100<br/>score = prob * 100"]
    
    Calibrate["Apply calibration<br/>(Platt scaling)"]
    
    Fallback["Fallback: Use rule-based<br/>score estimation"]
    
    Result["Return MLScoreResult<br/>{score, model_version, latency_ms}"]
    
    Request --> F1
    Request --> F2
    Request --> F3
    Request --> F4
    Request --> F5
    
    F1 --> FeatureVector
    F2 --> FeatureVector
    F3 --> FeatureVector
    F4 --> FeatureVector
    F5 --> FeatureVector
    
    FeatureVector --> ModelCall
    ModelCall --> Timeout
    
    Timeout -->|Yes| RawScore
    Timeout -->|No| Fallback
    
    RawScore --> Normalize
    Normalize --> Calibrate
    Calibrate --> Result
    Fallback --> Result
    
    style Request fill:#9013FE,color:#fff,stroke:#333,stroke-width:2px
    style ModelCall fill:#4A90E2,color:#fff
    style Fallback fill:#F5A623,color:#000
```

## Velocity Check Service (Redis)

```mermaid
graph TD
    Request["VelocityCheckService<br/>.checkVelocity(context)"]
    
    subgraph windows["Sliding Window Checks"]
        W1["User order frequency<br/>Key: velocity:user:{id}:orders<br/>Window: 1 hour<br/>Limit: 10 orders"]
        
        W2["Device usage<br/>Key: velocity:device:{id}:txns<br/>Window: 24 hours<br/>Limit: 50 transactions"]
        
        W3["IP address activity<br/>Key: velocity:ip:{ip}:attempts<br/>Window: 10 minutes<br/>Limit: 20 attempts"]
        
        W4["Payment amount<br/>Key: velocity:user:{id}:amount<br/>Window: 24 hours<br/>Limit: ₹50,000"]
        
        W5["Card usage<br/>Key: velocity:card:{hash}:txns<br/>Window: 1 hour<br/>Limit: 5 transactions"]
    end
    
    Redis["Redis MULTI/EXEC<br/>(atomic operations)"]
    
    CheckAll["Check all velocity limits<br/>ZRANGEBYSCORE with timestamps"]
    
    UpdateCounters["Update counters<br/>ZADD with current timestamp"]
    
    ExpireOld["Remove expired entries<br/>ZREMRANGEBYSCORE"]
    
    EvaluateBreaches["Evaluate breaches<br/>- count violations<br/>- calculate severity"]
    
    CalculateScore["Calculate velocity score<br/>violations * severity_weight"]
    
    Result["Return VelocityResult<br/>{score, breaches[], warnings[]}"]
    
    Request --> W1
    Request --> W2
    Request --> W3
    Request --> W4
    Request --> W5
    
    W1 --> Redis
    W2 --> Redis
    W3 --> Redis
    W4 --> Redis
    W5 --> Redis
    
    Redis --> CheckAll
    CheckAll --> UpdateCounters
    UpdateCounters --> ExpireOld
    ExpireOld --> EvaluateBreaches
    EvaluateBreaches --> CalculateScore
    CalculateScore --> Result
    
    style Request fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style Redis fill:#FF6B6B,color:#fff
    style CalculateScore fill:#4A90E2,color:#fff
```

## Device Fingerprint Service

```mermaid
graph TD
    Request["DeviceFingerprintService<br/>.verifyDevice(deviceId, userId)"]
    
    LocalCheck["Check local device cache<br/>Key: device:{deviceId}"]
    
    CacheHit{{"Device<br/>known?"}}
    
    KnownDevice["Retrieve device profile<br/>- trust_score<br/>- first_seen<br/>- user_associations"]
    
    UnknownDevice["Query ThreatMetrix API<br/>POST /device/assess"]
    
    TMResponse["ThreatMetrix Response<br/>- risk_score (0-1000)<br/>- flags[]<br/>- device_attributes"]
    
    NormalizeScore["Normalize to 0-100<br/>trust_score = (1000 - risk) / 10"]
    
    CheckAssociations["Check user-device association<br/>- Is this user's device?<br/>- Multiple users on device?"]
    
    AssociationRisk{{"Association<br/>risk?"}}
    
    HighRisk["High risk factors:<br/>- First time device<br/>- Many users<br/>- Known fraud device"]
    
    LowRisk["Low risk factors:<br/>- Known device<br/>- Single user<br/>- Long history"]
    
    CalculateScore["Calculate device score<br/>- base_trust_score<br/>- association_penalty<br/>- history_bonus"]
    
    CacheResult["Cache device profile<br/>(TTL: 24 hours)"]
    
    Result["Return DeviceResult<br/>{score, trust_level, flags[]}"]
    
    Request --> LocalCheck
    LocalCheck --> CacheHit
    
    CacheHit -->|Yes| KnownDevice
    CacheHit -->|No| UnknownDevice
    
    UnknownDevice --> TMResponse
    TMResponse --> NormalizeScore
    KnownDevice --> CheckAssociations
    NormalizeScore --> CheckAssociations
    
    CheckAssociations --> AssociationRisk
    AssociationRisk -->|Yes| HighRisk
    AssociationRisk -->|No| LowRisk
    
    HighRisk --> CalculateScore
    LowRisk --> CalculateScore
    
    CalculateScore --> CacheResult
    CacheResult --> Result
    
    style Request fill:#9013FE,color:#fff,stroke:#333,stroke-width:2px
    style TMResponse fill:#4A90E2,color:#fff
    style HighRisk fill:#FF6B6B,color:#fff
    style LowRisk fill:#7ED321,color:#000
```

## Review Queue Service

```mermaid
graph TD
    subgraph queue["Review Queue Management"]
        Enqueue["ReviewQueueService<br/>.enqueueForReview(fraudScore)"]
        
        Priority["Calculate priority<br/>- Score severity<br/>- Transaction amount<br/>- User tier"]
        
        Assignment["Determine assignment<br/>- Round-robin<br/>- Skill-based<br/>- Load-balanced"]
        
        Persist["Persist to PostgreSQL<br/>fraud_reviews table"]
        
        Notify["Emit ReviewQueued event<br/>(Kafka)"]
    end
    
    subgraph process["Review Processing"]
        Fetch["AnalystService<br/>.fetchNextReview(analystId)"]
        
        Lock["Lock review<br/>(optimistic locking)"]
        
        Display["Load review context<br/>- Transaction details<br/>- User history<br/>- Risk factors"]
        
        Decision["Analyst decision<br/>- APPROVE<br/>- REJECT<br/>- ESCALATE"]
        
        Record["Record decision<br/>+ notes + evidence"]
        
        Update["Update fraud_score<br/>final_decision = X"]
        
        Emit["Emit ReviewCompleted<br/>event"]
    end
    
    subgraph sla["SLA Tracking"]
        SLACheck["Check SLA breaches<br/>- High priority: 15 min<br/>- Medium: 1 hour<br/>- Low: 4 hours"]
        
        Escalate["Auto-escalate if<br/>SLA breached"]
        
        Metrics["Emit SLA metrics<br/>- time_to_review<br/>- breach_count"]
    end
    
    Enqueue --> Priority
    Priority --> Assignment
    Assignment --> Persist
    Persist --> Notify
    
    Fetch --> Lock
    Lock --> Display
    Display --> Decision
    Decision --> Record
    Record --> Update
    Update --> Emit
    
    Persist --> SLACheck
    SLACheck --> Escalate
    SLACheck --> Metrics
    
    style Enqueue fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style Decision fill:#4A90E2,color:#fff
    style Escalate fill:#FF6B6B,color:#fff
```

## Error Handling & Resilience

```mermaid
graph TD
    A["ML Service Timeout"]
    B["ThreatMetrix API Down"]
    C["Redis Cluster Failure"]
    D["Rule Engine Error"]
    E["Database Write Failure"]
    
    A --> A1["Fallback to rule-based score<br/>(no ML component)"]
    A1 --> A2["Increment ml_service_timeouts_total"]
    A2 --> A3["Flag for post-review"]
    A3 --> A4["Continue with partial score"]
    
    B --> B1["Use cached device profile<br/>(if available)"]
    B1 --> B2["Circuit breaker OPEN<br/>for ThreatMetrix"]
    B2 --> B3["Default device score = 50<br/>(neutral)"]
    B3 --> B4["Alert: DEVICE_SERVICE_DEGRADED"]
    
    C --> C1["Fallback to in-memory counters<br/>(Caffeine cache)"]
    C1 --> C2["Reduced accuracy<br/>for velocity checks"]
    C2 --> C3["Conservative limits<br/>(fail-safe)"]
    C3 --> C4["Alert: REDIS_CLUSTER_DOWN"]
    
    D --> D1["Skip rule evaluation"]
    D1 --> D2["Use ML + velocity only"]
    D2 --> D3["Flag for review:<br/>RULE_ENGINE_BYPASS"]
    D3 --> D4["Log: RULE_ENGINE_ERROR"]
    
    E --> E1["Retry with exponential backoff<br/>(max 3 retries)"]
    E1 --> E2{"Retry<br/>exhausted?"}
    E2 -->|Yes| E3["Queue in memory<br/>for async persist"]
    E2 -->|No| E4["Try next attempt"]
    E3 --> E5["Return decision anyway<br/>(async durability)"]
    
    style A4 fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style B4 fill:#FF6B6B,color:#fff
    style C4 fill:#FF6B6B,color:#fff
    style D4 fill:#F5A623,color:#000
    style E5 fill:#F5A623,color:#000
```

## SLO: P99 Latency Target < 200ms

```
┌─────────────────────────────────────────────────────────────┐
│  Fraud Check Request Timeline (P99 target: <200ms)          │
├─────────────────────────────────────────────────────────────┤
│ Request parsing & validation:          ~5ms                 │
│ JWT authentication:                    ~3ms                 │
│ Rate limit check:                      ~2ms                 │
│ Subtotal (overhead):                   ~10ms                │
│                                                             │
│ Parallel scoring pipeline:                                  │
│   - Velocity check (Redis):            ~15ms p99            │
│   - Rule engine (Drools):              ~25ms p99            │
│   - ML scoring (gRPC):                 ~80ms p99            │
│   - Device fingerprint (API):          ~60ms p99            │
│   Max parallel time:                   ~80ms p99            │
│                                                             │
│ Score aggregation:                     ~5ms                 │
│ Decision logic:                        ~2ms                 │
│ Database persist (async):              ~0ms (non-blocking)  │
│ Response serialization:                ~3ms                 │
│                                                             │
│ TOTAL P99:                             ~100ms               │
│ BUFFER (200ms target):                 ~100ms               │
│ STATUS:                                ✅ WITHIN SLO        │
└─────────────────────────────────────────────────────────────┘
```
