# Fraud Detection Service - Request Flowcharts

## Complete Fraud Check Flow

```mermaid
flowchart TD
    A["🌐 HTTP Request<br/>POST /api/fraud/check"]
    B["Extract transaction data<br/>(user_id, amount, device_id, etc.)"]
    C{{"Request<br/>valid?"}}
    D["❌ Return 400<br/>Bad Request"]
    E["Check service JWT<br/>(internal auth)"]
    F{{"JWT<br/>valid?"}}
    G["❌ Return 401<br/>Unauthorized"]
    H["Check rate limit<br/>(1000 req/sec per client)"]
    I{{"Rate limit<br/>OK?"}}
    J["❌ Return 429<br/>Too Many Requests"]
    
    K["🚀 Launch parallel fraud checks"]
    
    L1["⚡ Velocity Check<br/>(Redis)"]
    L2["📜 Rule Engine<br/>(Drools)"]
    L3["🤖 ML Scoring<br/>(gRPC)"]
    L4["📱 Device Check<br/>(ThreatMetrix)"]
    
    M["Await all results<br/>(timeout: 150ms)"]
    N{{"All checks<br/>completed?"}}
    O["Use partial results<br/>+ default scores"]
    
    P["Aggregate scores:<br/>velocity: 20%<br/>rules: 30%<br/>ml: 40%<br/>device: 10%"]
    
    Q["Calculate composite<br/>fraud score (0-100)"]
    
    R{{"Score < 30?"}}
    S["✅ Decision: AUTO_APPROVE<br/>risk_level: LOW"]
    
    T{{"Score 30-70?"}}
    U["👥 Decision: MANUAL_REVIEW<br/>risk_level: MEDIUM<br/>Queue for analyst"]
    
    V["❌ Decision: AUTO_REJECT<br/>risk_level: HIGH"]
    
    W["Persist fraud_score<br/>(PostgreSQL)"]
    X["Emit FraudCheckCompleted<br/>(Kafka)"]
    Y["Return 200 OK<br/>with FraudCheckResponse"]
    
    A --> B --> C
    C -->|No| D
    C -->|Yes| E
    E --> F
    F -->|No| G
    F -->|Yes| H
    H --> I
    I -->|No| J
    I -->|Yes| K
    
    K --> L1
    K --> L2
    K --> L3
    K --> L4
    
    L1 --> M
    L2 --> M
    L3 --> M
    L4 --> M
    
    M --> N
    N -->|Yes| P
    N -->|Timeout| O
    O --> P
    
    P --> Q --> R
    R -->|Yes| S
    R -->|No| T
    T -->|Yes| U
    T -->|No| V
    
    S --> W
    U --> W
    V --> W
    
    W --> X --> Y
    
    style S fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style U fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style V fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style Q fill:#4A90E2,color:#fff
    style D fill:#FF6B6B,color:#fff
    style G fill:#FF6B6B,color:#fff
    style J fill:#FF6B6B,color:#fff
```

## Velocity Check Flowchart

```mermaid
flowchart TD
    A["VelocityCheckService<br/>.checkVelocity(context)"]
    B["Extract identifiers<br/>(user_id, device_id, ip, card_hash)"]
    
    C["🔄 Check user order frequency<br/>Key: velocity:user:{id}:orders<br/>Window: 1 hour"]
    D{{"Orders > 10<br/>in 1 hour?"}}
    E1["⚠️ Flag: HIGH_ORDER_FREQUENCY"]
    
    F["🔄 Check device transaction count<br/>Key: velocity:device:{id}:txns<br/>Window: 24 hours"]
    G{{"Txns > 50<br/>in 24 hours?"}}
    H1["⚠️ Flag: EXCESSIVE_DEVICE_USAGE"]
    
    I["🔄 Check IP attempts<br/>Key: velocity:ip:{ip}:attempts<br/>Window: 10 minutes"]
    J{{"Attempts > 20<br/>in 10 min?"}}
    K1["⚠️ Flag: IP_RATE_LIMIT_EXCEEDED"]
    
    L["🔄 Check payment amount<br/>Key: velocity:user:{id}:amount<br/>Window: 24 hours"]
    M{{"Total > ₹50,000<br/>in 24 hours?"}}
    N1["⚠️ Flag: HIGH_DAILY_SPEND"]
    
    O["🔄 Check card usage<br/>Key: velocity:card:{hash}:txns<br/>Window: 1 hour"]
    P{{"Card txns > 5<br/>in 1 hour?"}}
    Q1["⚠️ Flag: CARD_VELOCITY_EXCEEDED"]
    
    R["Update all counters<br/>ZADD with current timestamp"]
    
    S["Expire old entries<br/>ZREMRANGEBYSCORE"]
    
    T["Calculate velocity score<br/>violations * severity_weight"]
    
    U["Return VelocityResult<br/>{score, flags[], counters}"]
    
    A --> B
    B --> C --> D
    D -->|Yes| E1 --> F
    D -->|No| F
    
    F --> G
    G -->|Yes| H1 --> I
    G -->|No| I
    
    I --> J
    J -->|Yes| K1 --> L
    J -->|No| L
    
    L --> M
    M -->|Yes| N1 --> O
    M -->|No| O
    
    O --> P
    P -->|Yes| Q1 --> R
    P -->|No| R
    
    R --> S --> T --> U
    
    style A fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style E1 fill:#FF6B6B,color:#fff
    style H1 fill:#FF6B6B,color:#fff
    style K1 fill:#FF6B6B,color:#fff
    style N1 fill:#FF6B6B,color:#fff
    style Q1 fill:#FF6B6B,color:#fff
    style U fill:#7ED321,color:#000
```

## Rule Engine Evaluation Flowchart

```mermaid
flowchart TD
    A["RuleEngineService<br/>.evaluateRules(context)"]
    B["Load active rules<br/>from cache or DB"]
    C{{"Cache<br/>hit?"}}
    D["Return cached rules<br/>(TTL: 5 min)"]
    E["Query PostgreSQL<br/>SELECT * FROM rules<br/>WHERE active = true"]
    F["Cache rules<br/>(TTL: 5 min)"]
    
    G["Build Drools facts:<br/>- TransactionFact<br/>- UserHistoryFact<br/>- DeviceFact<br/>- GeoFact"]
    
    H["Create KIE session<br/>(stateless)"]
    I["Insert facts into<br/>working memory"]
    J["Fire all rules"]
    
    K["Collect triggered rules"]
    
    L["📜 Amount threshold rules"]
    L1{{"Amount > ₹10,000?"}}
    L2["Flag: HIGH_VALUE_TRANSACTION"]
    
    M["📜 New user rules"]
    M1{{"Account < 7 days?"}}
    M2["Flag: NEW_ACCOUNT_RISK"]
    
    N["📜 Pattern detection rules"]
    N1{{"Promo abuse<br/>pattern?"}}
    N2["Flag: PROMO_ABUSE_DETECTED"]
    
    O["📜 Category risk rules"]
    O1{{"High-risk<br/>category?"}}
    O2["Flag: HIGH_RISK_CATEGORY"]
    
    P["📜 Geo-velocity rules"]
    P1{{"Impossible<br/>travel?"}}
    P2["Flag: IMPOSSIBLE_TRAVEL"]
    
    Q["Calculate rule score<br/>SUM(severity * weight)"]
    R["Return RuleEngineResult<br/>{score, triggered_rules[], flags[]}"]
    
    A --> B --> C
    C -->|Yes| D --> G
    C -->|No| E --> F --> G
    
    G --> H --> I --> J --> K
    
    K --> L --> L1
    L1 -->|Yes| L2 --> M
    L1 -->|No| M
    
    M --> M1
    M1 -->|Yes| M2 --> N
    M1 -->|No| N
    
    N --> N1
    N1 -->|Yes| N2 --> O
    N1 -->|No| O
    
    O --> O1
    O1 -->|Yes| O2 --> P
    O1 -->|No| P
    
    P --> P1
    P1 -->|Yes| P2 --> Q
    P1 -->|No| Q
    
    Q --> R
    
    style A fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style L2 fill:#FF6B6B,color:#fff
    style M2 fill:#FF6B6B,color:#fff
    style N2 fill:#FF6B6B,color:#fff
    style O2 fill:#FF6B6B,color:#fff
    style P2 fill:#FF6B6B,color:#fff
    style R fill:#7ED321,color:#000
```

## Manual Review Escalation Flowchart

```mermaid
flowchart TD
    A["FraudScore created<br/>decision: MANUAL_REVIEW"]
    B["Calculate review priority<br/>- score_severity<br/>- transaction_amount<br/>- user_tier"]
    
    C{{"Priority<br/>level?"}}
    
    D["🔴 HIGH Priority<br/>Score > 60 OR Amount > ₹25,000"]
    E["🟠 MEDIUM Priority<br/>Score 45-60"]
    F["🟢 LOW Priority<br/>Score 30-45"]
    
    G["Set SLA deadline<br/>HIGH: 15 min<br/>MEDIUM: 1 hour<br/>LOW: 4 hours"]
    
    H["Insert into fraud_reviews<br/>status: PENDING"]
    
    I["Emit ReviewQueued event<br/>(Kafka → analyst dashboard)"]
    
    J["Start SLA timer<br/>(scheduled job)"]
    
    K{{"Review picked up<br/>within SLA?"}}
    
    L["Analyst claims review<br/>status: IN_PROGRESS"]
    
    M["⚠️ SLA breach detected"]
    N["Auto-escalate to<br/>senior analyst"]
    O["Emit SLABreached event"]
    
    P["Analyst reviews:<br/>- Transaction details<br/>- User history<br/>- Risk factors<br/>- Similar cases"]
    
    Q["Analyst decision"]
    
    R["✅ APPROVE<br/>Transaction allowed"]
    S["❌ REJECT<br/>Transaction blocked"]
    T["⬆️ ESCALATE<br/>To fraud manager"]
    
    U["Record decision<br/>+ notes + evidence"]
    V["Update fraud_score<br/>final_decision = X"]
    W["Emit ReviewCompleted event"]
    X["Notify downstream services"]
    
    A --> B --> C
    C -->|High| D --> G
    C -->|Medium| E --> G
    C -->|Low| F --> G
    
    G --> H --> I --> J --> K
    
    K -->|Yes| L --> P
    K -->|No| M --> N --> O --> L
    
    P --> Q
    Q -->|Approve| R --> U
    Q -->|Reject| S --> U
    Q -->|Escalate| T --> U
    
    U --> V --> W --> X
    
    style A fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style D fill:#FF6B6B,color:#fff
    style E fill:#F5A623,color:#000
    style F fill:#7ED321,color:#000
    style R fill:#7ED321,color:#000
    style S fill:#FF6B6B,color:#fff
    style T fill:#9013FE,color:#fff
    style M fill:#FF6B6B,color:#fff
```

## Device Fingerprint Verification Flowchart

```mermaid
flowchart TD
    A["DeviceFingerprintService<br/>.verifyDevice(deviceId, userId)"]
    B["Check local device cache<br/>Key: device:{deviceId}"]
    
    C{{"Device<br/>cached?"}}
    
    D["Retrieve cached profile<br/>- trust_score<br/>- first_seen<br/>- user_associations"]
    
    E["Query ThreatMetrix API<br/>POST /device/assess<br/>{device_fingerprint, context}"]
    
    F{{"API<br/>response OK?"}}
    
    G["Parse ThreatMetrix response<br/>- risk_score (0-1000)<br/>- device_attributes<br/>- flags[]"]
    
    H["Circuit breaker fallback<br/>Use default score = 50"]
    
    I["Normalize score<br/>trust_score = (1000 - risk) / 10"]
    
    J["Check user-device association"]
    
    K{{"Device seen<br/>with this user<br/>before?"}}
    
    L["✅ Known association<br/>bonus: +10 to trust"]
    
    M["❓ New device for user"]
    
    N{{"Device has<br/>multiple users?"}}
    
    O["⚠️ Multi-user device<br/>penalty: -15 to trust"]
    
    P["🆕 First-time device<br/>penalty: -5 to trust"]
    
    Q["Check device flags"]
    
    R{{"Known fraud<br/>device?"}}
    S["🚫 BLOCKLIST<br/>trust_score = 0"]
    
    T{{"Rooted/<br/>jailbroken?"}}
    U["⚠️ ROOTED_DEVICE<br/>penalty: -20"]
    
    V{{"VPN/<br/>proxy?"}}
    W["⚠️ PROXY_DETECTED<br/>penalty: -10"]
    
    X["Calculate final device score"]
    Y["Cache device profile<br/>(TTL: 24 hours)"]
    Z["Return DeviceResult<br/>{score, trust_level, flags[]}"]
    
    A --> B --> C
    C -->|Yes| D --> J
    C -->|No| E --> F
    F -->|Yes| G --> I --> J
    F -->|No| H --> J
    
    J --> K
    K -->|Yes| L --> Q
    K -->|No| M --> N
    N -->|Yes| O --> Q
    N -->|No| P --> Q
    
    Q --> R
    R -->|Yes| S --> X
    R -->|No| T
    T -->|Yes| U --> V
    T -->|No| V
    V -->|Yes| W --> X
    V -->|No| X
    
    X --> Y --> Z
    
    style A fill:#9013FE,color:#fff,stroke:#333,stroke-width:2px
    style L fill:#7ED321,color:#000
    style S fill:#FF6B6B,color:#fff
    style O fill:#F5A623,color:#000
    style U fill:#F5A623,color:#000
    style W fill:#F5A623,color:#000
    style Z fill:#7ED321,color:#000
```

## Fraud Decision Callback Flowchart

```mermaid
flowchart TD
    A["POST /api/fraud/decision<br/>(from analyst dashboard)"]
    B["Validate request<br/>- review_id<br/>- analyst_id<br/>- decision<br/>- notes"]
    
    C{{"Valid<br/>request?"}}
    D["❌ Return 400<br/>Bad Request"]
    
    E["Fetch fraud_review<br/>by review_id"]
    
    F{{"Review<br/>exists?"}}
    G["❌ Return 404<br/>Not Found"]
    
    H{{"Review status<br/>= IN_PROGRESS?"}}
    I["❌ Return 409<br/>Conflict (already decided)"]
    
    J["Parse decision:<br/>APPROVE / REJECT / ESCALATE"]
    
    K{{"Decision<br/>type?"}}
    
    L["✅ APPROVE"]
    L1["Update review status<br/>= APPROVED"]
    L2["Update fraud_score<br/>final_decision = APPROVED"]
    L3["Emit TransactionApproved<br/>event"]
    L4["Notify checkout-orchestrator<br/>Continue order flow"]
    
    M["❌ REJECT"]
    M1["Update review status<br/>= REJECTED"]
    M2["Update fraud_score<br/>final_decision = REJECTED"]
    M3["Emit TransactionRejected<br/>event"]
    M4["Notify payment-service<br/>Cancel authorization"]
    
    N["⬆️ ESCALATE"]
    N1["Update review status<br/>= ESCALATED"]
    N2["Assign to fraud manager<br/>or specialized team"]
    N3["Emit ReviewEscalated<br/>event"]
    N4["Notify senior analyst<br/>via Slack/PagerDuty"]
    
    O["Record analyst notes<br/>+ evidence attachments"]
    P["Calculate review metrics<br/>- time_to_decision<br/>- accuracy (later)"]
    Q["Return 200 OK<br/>with updated review"]
    
    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F
    F -->|No| G
    F -->|Yes| H
    H -->|No| I
    H -->|Yes| J --> K
    
    K -->|APPROVE| L --> L1 --> L2 --> L3 --> L4 --> O
    K -->|REJECT| M --> M1 --> M2 --> M3 --> M4 --> O
    K -->|ESCALATE| N --> N1 --> N2 --> N3 --> N4 --> O
    
    O --> P --> Q
    
    style L fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style M fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style N fill:#9013FE,color:#fff,stroke:#333,stroke-width:2px
    style Q fill:#7ED321,color:#000
```
