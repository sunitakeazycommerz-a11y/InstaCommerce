# Fraud Detection Service - Sequence Diagrams

## Complete Checkout Fraud Check Sequence

```mermaid
sequenceDiagram
    participant Checkout as Checkout Orchestrator
    participant ALB as AWS ALB
    participant Fraud as Fraud Detection Service
    participant Auth as Auth Filter
    participant Orchestrator as FraudScoreOrchestrator
    participant Velocity as VelocityCheckService
    participant Rules as RuleEngineService
    participant ML as MLScoringService
    participant Device as DeviceFingerprintService
    participant Redis as Redis Cluster
    participant Drools as Drools Engine
    participant MLInference as ML Inference Service
    participant ThreatMetrix as ThreatMetrix API
    participant DB as PostgreSQL
    participant Kafka as Kafka

    Checkout->>ALB: 1. POST /api/fraud/check<br/>{user_id, amount, device_id, items[]}
    ALB->>Fraud: 2. Forward request
    Fraud->>Auth: 3. Validate service JWT
    Auth->>Auth: 4. Check token signature<br/>(internal service auth)
    
    alt Invalid JWT
        Auth-->>Checkout: 401 Unauthorized
    end
    
    Fraud->>Orchestrator: 5. FraudScoreOrchestrator.evaluate()
    
    Note over Orchestrator: Launch 4 parallel checks<br/>(timeout: 150ms each)
    
    par Parallel Fraud Checks
        Orchestrator->>Velocity: 6a. checkVelocity()
        Velocity->>Redis: 6a.1. MULTI/EXEC velocity checks
        Redis-->>Velocity: 6a.2. Counter values
        Velocity->>Velocity: 6a.3. Evaluate limits
        Velocity-->>Orchestrator: 6a.4. VelocityResult<br/>{score: 15, flags: []}
    and
        Orchestrator->>Rules: 6b. evaluateRules()
        Rules->>Drools: 6b.1. Create KIE session
        Drools->>Drools: 6b.2. Fire rules
        Drools-->>Rules: 6b.3. Triggered rules
        Rules-->>Orchestrator: 6b.4. RuleResult<br/>{score: 25, flags: [NEW_ACCOUNT]}
    and
        Orchestrator->>ML: 6c. getScore()
        ML->>ML: 6c.1. Extract 50+ features
        ML->>MLInference: 6c.2. gRPC FraudModel.predict()
        MLInference-->>ML: 6c.3. probability: 0.35
        ML->>ML: 6c.4. Normalize to 0-100
        ML-->>Orchestrator: 6c.5. MLResult<br/>{score: 35, model_version: v2.3}
    and
        Orchestrator->>Device: 6d. verifyDevice()
        Device->>ThreatMetrix: 6d.1. POST /device/assess
        ThreatMetrix-->>Device: 6d.2. {risk_score: 200, flags: []}
        Device->>Device: 6d.3. Normalize & check associations
        Device-->>Orchestrator: 6d.4. DeviceResult<br/>{score: 80, trust_level: HIGH}
    end
    
    Note over Orchestrator: All checks completed: ~85ms
    
    Orchestrator->>Orchestrator: 7. Aggregate scores<br/>velocity: 15 × 0.2 = 3<br/>rules: 25 × 0.3 = 7.5<br/>ml: 35 × 0.4 = 14<br/>device: (100-80) × 0.1 = 2<br/>TOTAL: 26.5
    
    Orchestrator->>Orchestrator: 8. Decision: AUTO_APPROVE<br/>(score < 30)
    
    Orchestrator->>DB: 9. Persist fraud_score
    DB-->>Orchestrator: 10. Saved
    
    Orchestrator->>Kafka: 11. Emit FraudCheckCompleted
    
    Orchestrator-->>Fraud: 12. FraudCheckResponse
    Fraud-->>ALB: 13. 200 OK
    ALB-->>Checkout: 14. {decision: APPROVE,<br/>score: 26.5, risk_level: LOW}
    
    Note over Checkout: Continue checkout flow<br/>Total latency: ~95ms
```

## Manual Review Queue Sequence

```mermaid
sequenceDiagram
    participant Fraud as Fraud Detection Service
    participant Queue as ReviewQueueService
    participant DB as PostgreSQL
    participant Kafka as Kafka
    participant Dashboard as Analyst Dashboard
    participant Analyst as Fraud Analyst
    participant Decision as DecisionService

    Note over Fraud: Score = 55 (MANUAL_REVIEW)
    
    Fraud->>Queue: 1. enqueueForReview(fraudScore)
    
    Queue->>Queue: 2. Calculate priority<br/>- score: 55 → MEDIUM<br/>- amount: ₹8,000 → MEDIUM<br/>- user_tier: regular → MEDIUM<br/>Result: MEDIUM priority
    
    Queue->>Queue: 3. Set SLA deadline<br/>MEDIUM = 1 hour
    
    Queue->>DB: 4. INSERT INTO fraud_reviews<br/>{id, score_id, priority, sla_deadline,<br/>status: PENDING}
    DB-->>Queue: 5. Review created
    
    Queue->>Kafka: 6. Emit ReviewQueued event<br/>{review_id, priority, sla_deadline}
    
    Kafka-->>Dashboard: 7. Consume ReviewQueued
    
    Dashboard->>Dashboard: 8. Update review queue UI<br/>Show new pending review
    
    Note over Dashboard: 10 minutes later...
    
    Analyst->>Dashboard: 9. Click "Claim Review"
    
    Dashboard->>Queue: 10. POST /api/fraud/reviews/{id}/claim<br/>{analyst_id}
    
    Queue->>DB: 11. UPDATE fraud_reviews<br/>SET status = IN_PROGRESS,<br/>analyst_id = {analyst_id},<br/>claimed_at = now()
    
    alt Review already claimed
        Queue-->>Dashboard: 409 Conflict
    end
    
    DB-->>Queue: 12. Updated
    Queue-->>Dashboard: 13. 200 OK {review details}
    
    Dashboard->>Dashboard: 14. Load review context:<br/>- Transaction details<br/>- User history (30 days)<br/>- Device history<br/>- Similar flagged txns<br/>- Risk factor breakdown
    
    Dashboard-->>Analyst: 15. Display review interface
    
    Note over Analyst: Analyst reviews for 3 minutes
    
    Analyst->>Dashboard: 16. Decision: APPROVE<br/>Notes: "Verified user, legitimate purchase"
    
    Dashboard->>Decision: 17. POST /api/fraud/decision<br/>{review_id, decision: APPROVE, notes}
    
    Decision->>DB: 18. UPDATE fraud_reviews<br/>SET status = APPROVED,<br/>decision_notes = "...",<br/>decided_at = now()
    
    Decision->>DB: 19. UPDATE fraud_scores<br/>SET final_decision = APPROVED
    
    Decision->>Kafka: 20. Emit ReviewCompleted<br/>{review_id, decision: APPROVE}
    
    Decision->>Kafka: 21. Emit TransactionApproved<br/>{transaction_id, fraud_score_id}
    
    Decision-->>Dashboard: 22. 200 OK
    Dashboard-->>Analyst: 23. "Review submitted"
    
    Note over Kafka: Downstream services notified
```

## Real-time Scoring with ML Model Sequence

```mermaid
sequenceDiagram
    participant Fraud as Fraud Detection Service
    participant ML as MLScoringService
    participant FeatureStore as Feature Store<br/>(Redis)
    participant Extractor as Feature Extractor
    participant Inference as ML Inference Service<br/>(gRPC)
    participant Model as XGBoost Model
    participant Calibrator as Score Calibrator

    Fraud->>ML: 1. getScore(transaction)
    
    ML->>Extractor: 2. Extract features
    
    par Feature Extraction (parallel)
        Extractor->>FeatureStore: 3a. Get user features<br/>Key: features:user:{id}
        FeatureStore-->>Extractor: {account_age: 180,<br/>order_count_30d: 12,<br/>avg_order_value: 850}
    and
        Extractor->>Extractor: 3b. Transaction features<br/>(from request)
        Note right of Extractor: amount: 2500<br/>payment_method: card<br/>item_category: electronics
    and
        Extractor->>FeatureStore: 3c. Get behavioral features<br/>Key: features:session:{id}
        FeatureStore-->>Extractor: {session_duration: 480,<br/>pages_visited: 12,<br/>cart_changes: 3}
    and
        Extractor->>FeatureStore: 3d. Get device features<br/>Key: features:device:{id}
        FeatureStore-->>Extractor: {device_age: 90,<br/>device_trust: 0.85,<br/>fingerprint_match: true}
    end
    
    Extractor->>Extractor: 4. Build feature vector<br/>[180, 12, 850, 2500, 1, 2,<br/>480, 12, 3, 90, 0.85, 1, ...]
    
    Extractor-->>ML: 5. Feature vector (50+ dims)
    
    ML->>Inference: 6. gRPC: FraudModel.predict(features)<br/>deadline: 100ms
    
    Inference->>Model: 7. XGBoost inference
    Model->>Model: 8. Ensemble prediction<br/>(10 trees)
    Model-->>Inference: 9. Raw probability: 0.32
    
    Inference-->>ML: 10. {probability: 0.32,<br/>model_version: v2.3.1,<br/>inference_time_ms: 45}
    
    ML->>Calibrator: 11. Calibrate score<br/>(Platt scaling)
    Calibrator->>Calibrator: 12. Apply calibration:<br/>calibrated = sigmoid(A * prob + B)<br/>A = 1.2, B = -0.1
    Calibrator-->>ML: 13. Calibrated prob: 0.35
    
    ML->>ML: 14. Normalize to 0-100<br/>score = 35
    
    ML-->>Fraud: 15. MLResult {<br/>score: 35,<br/>probability: 0.35,<br/>model_version: v2.3.1,<br/>latency_ms: 65,<br/>features_used: 52<br/>}
    
    Note over Fraud: ML score contributes 40%<br/>to composite fraud score
```

## Velocity Check with Redis Sequence

```mermaid
sequenceDiagram
    participant Fraud as Fraud Detection Service
    participant Velocity as VelocityCheckService
    participant Redis as Redis Cluster

    Fraud->>Velocity: 1. checkVelocity(context)<br/>{user_id: U123, device_id: D456,<br/>ip: 1.2.3.4, amount: 5000}
    
    Velocity->>Redis: 2. MULTI (start transaction)
    
    Note over Redis: Atomic velocity checks
    
    Velocity->>Redis: 3a. ZRANGEBYSCORE velocity:user:U123:orders<br/>{now - 3600} {now}
    Note right of Redis: Orders in last 1 hour
    
    Velocity->>Redis: 3b. ZRANGEBYSCORE velocity:device:D456:txns<br/>{now - 86400} {now}
    Note right of Redis: Device txns in 24 hours
    
    Velocity->>Redis: 3c. ZRANGEBYSCORE velocity:ip:1.2.3.4:attempts<br/>{now - 600} {now}
    Note right of Redis: IP attempts in 10 minutes
    
    Velocity->>Redis: 3d. GET velocity:user:U123:amount:24h
    Note right of Redis: Amount spent in 24 hours
    
    Velocity->>Redis: 4. EXEC (execute transaction)
    
    Redis-->>Velocity: 5. Results:<br/>orders: [t1, t2, t3] (count: 3)<br/>device_txns: [...] (count: 15)<br/>ip_attempts: [...] (count: 5)<br/>amount_24h: 12000
    
    Velocity->>Velocity: 6. Evaluate limits:<br/>✅ orders: 3 < 10 (OK)<br/>✅ device: 15 < 50 (OK)<br/>✅ ip: 5 < 20 (OK)<br/>⚠️ amount: 12000 + 5000 = 17000 < 50000 (OK)
    
    Velocity->>Redis: 7. MULTI (update counters)
    
    Velocity->>Redis: 8a. ZADD velocity:user:U123:orders<br/>{now} {txn_id}
    
    Velocity->>Redis: 8b. ZADD velocity:device:D456:txns<br/>{now} {txn_id}
    
    Velocity->>Redis: 8c. ZADD velocity:ip:1.2.3.4:attempts<br/>{now} {attempt_id}
    
    Velocity->>Redis: 8d. INCRBY velocity:user:U123:amount:24h 5000
    
    Velocity->>Redis: 9. EXPIRE commands<br/>(set TTLs for cleanup)
    
    Velocity->>Redis: 10. EXEC
    
    Redis-->>Velocity: 11. Counters updated
    
    Velocity->>Velocity: 12. Calculate velocity score<br/>No violations → score: 0
    
    Velocity-->>Fraud: 13. VelocityResult {<br/>score: 0,<br/>flags: [],<br/>checks: {<br/>user_orders: {count: 3, limit: 10},<br/>device_txns: {count: 15, limit: 50},<br/>ip_attempts: {count: 5, limit: 20},<br/>amount_24h: {sum: 17000, limit: 50000}<br/>}<br/>}
```

## Device Fingerprint Verification Sequence

```mermaid
sequenceDiagram
    participant Fraud as Fraud Detection Service
    participant Device as DeviceFingerprintService
    participant Cache as Redis Cache
    participant TM as ThreatMetrix API
    participant DB as PostgreSQL

    Fraud->>Device: 1. verifyDevice(deviceId: D456, userId: U123)
    
    Device->>Cache: 2. GET device:D456
    
    alt Cache Hit
        Cache-->>Device: 3a. {trust_score: 85,<br/>first_seen: 2024-01-15,<br/>users: [U123, U789]}
        Device->>Device: 4a. Check user association<br/>U123 in users: ✅ known
        Device-->>Fraud: 5a. DeviceResult {score: 85,<br/>trust_level: HIGH, flags: []}
    else Cache Miss
        Cache-->>Device: 3b. null (not cached)
        
        Device->>TM: 4b. POST /api/v1/device/assess<br/>{device_fingerprint: "...",<br/>session_id: "...",<br/>context: {...}}
        
        Note over TM: ThreatMetrix analysis:<br/>- Device attributes<br/>- Behavioral biometrics<br/>- Network analysis<br/>- Fraud consortium data
        
        TM-->>Device: 5b. {<br/>risk_score: 180 (out of 1000),<br/>device_id: "D456",<br/>attributes: {<br/>os: "iOS 17.2",<br/>browser: "Safari",<br/>screen: "1170x2532"<br/>},<br/>flags: [],<br/>true_ip: "1.2.3.4",<br/>proxy_detected: false,<br/>rooted: false<br/>}
        
        Device->>Device: 6b. Normalize score<br/>trust = (1000 - 180) / 10 = 82
        
        Device->>DB: 7b. Query device history<br/>SELECT * FROM device_associations<br/>WHERE device_id = 'D456'
        
        DB-->>Device: 8b. [{user_id: U789, first_seen: 2024-02-01}]
        
        Device->>Device: 9b. Check associations:<br/>U123 not in history → NEW_DEVICE_FOR_USER<br/>Device has 1 other user → OK
        
        Device->>Device: 10b. Apply adjustments:<br/>base: 82<br/>new_device_penalty: -5<br/>final: 77
        
        Device->>DB: 11b. INSERT device_associations<br/>(device_id, user_id, first_seen)
        
        Device->>Cache: 12b. SET device:D456<br/>{trust_score: 77, users: [U123, U789]}<br/>EX 86400 (24 hours)
        
        Device-->>Fraud: 13b. DeviceResult {<br/>score: 77,<br/>trust_level: MEDIUM,<br/>flags: [NEW_DEVICE_FOR_USER],<br/>attributes: {...}<br/>}
    end
```

## SLA Breach and Auto-Escalation Sequence

```mermaid
sequenceDiagram
    participant Scheduler as SLA Scheduler<br/>(ShedLock)
    participant Queue as ReviewQueueService
    participant DB as PostgreSQL
    participant Kafka as Kafka
    participant PagerDuty as PagerDuty
    participant Senior as Senior Analyst

    Note over Scheduler: Runs every 1 minute
    
    Scheduler->>Queue: 1. checkSLABreaches()
    
    Queue->>DB: 2. SELECT * FROM fraud_reviews<br/>WHERE status = 'PENDING'<br/>AND sla_deadline < now()
    
    DB-->>Queue: 3. Returns 2 breached reviews:<br/>[{id: R1, priority: HIGH, deadline: -5min},<br/>{id: R2, priority: MEDIUM, deadline: -2min}]
    
    loop For each breached review
        Queue->>Queue: 4. Calculate breach severity<br/>R1: HIGH priority, 5min over → CRITICAL<br/>R2: MEDIUM priority, 2min over → WARNING
        
        Queue->>DB: 5. UPDATE fraud_reviews<br/>SET status = 'ESCALATED',<br/>escalation_reason = 'SLA_BREACH',<br/>escalated_at = now()
        
        Queue->>Kafka: 6. Emit SLABreached event<br/>{review_id, breach_duration_minutes,<br/>original_priority, severity}
        
        alt Critical Breach (HIGH priority > 5min)
            Queue->>PagerDuty: 7a. Create incident<br/>{title: "Fraud Review SLA Breach",<br/>severity: P2,<br/>description: "Review R1 breached by 5min"}
            PagerDuty-->>Queue: 8a. Incident created
        end
        
        Queue->>Queue: 9. Determine escalation target<br/>HIGH → fraud-manager<br/>MEDIUM → senior-analyst
        
        Queue->>DB: 10. UPDATE fraud_reviews<br/>SET assigned_to = 'fraud-manager'
        
        Queue->>Kafka: 11. Emit ReviewEscalated<br/>{review_id, escalated_to, reason}
    end
    
    Kafka-->>Senior: 12. Consume ReviewEscalated
    
    Senior->>Senior: 13. Notification received<br/>"Escalated review requires attention"
    
    Queue->>Queue: 14. Emit metrics:<br/>sla_breaches_total{priority}<br/>sla_breach_duration_minutes
    
    Queue-->>Scheduler: 15. Processed 2 breaches
    
    Note over Scheduler: Next run in 1 minute
```

## Fraud Check with Timeout Handling Sequence

```mermaid
sequenceDiagram
    participant Checkout as Checkout Orchestrator
    participant Fraud as Fraud Detection Service
    participant Orchestrator as FraudScoreOrchestrator
    participant Velocity as VelocityCheckService
    participant Rules as RuleEngineService
    participant ML as MLScoringService
    participant Device as DeviceFingerprintService
    participant DB as PostgreSQL

    Checkout->>Fraud: 1. POST /api/fraud/check
    
    Fraud->>Orchestrator: 2. evaluate(request)
    
    Note over Orchestrator: Start parallel checks<br/>Global timeout: 150ms
    
    par Parallel checks with individual timeouts
        Orchestrator->>Velocity: 3a. checkVelocity()
        Note over Velocity: Completes in 20ms
        Velocity-->>Orchestrator: 3a.result: {score: 10}
    and
        Orchestrator->>Rules: 3b. evaluateRules()
        Note over Rules: Completes in 35ms
        Rules-->>Orchestrator: 3b.result: {score: 20}
    and
        Orchestrator->>ML: 3c. getScore()
        Note over ML: ⚠️ ML Inference slow...<br/>Exceeds 100ms timeout
        ML--XOrchestrator: 3c. TIMEOUT
    and
        Orchestrator->>Device: 3d. verifyDevice()
        Note over Device: ThreatMetrix API slow...<br/>Exceeds 100ms timeout
        Device--XOrchestrator: 3d. TIMEOUT
    end
    
    Orchestrator->>Orchestrator: 4. Handle partial results<br/>✅ Velocity: 10<br/>✅ Rules: 20<br/>❌ ML: TIMEOUT → default 50<br/>❌ Device: TIMEOUT → default 50
    
    Orchestrator->>Orchestrator: 5. Apply timeout penalties<br/>ML timeout: +10 to score<br/>Device timeout: +5 to score
    
    Orchestrator->>Orchestrator: 6. Aggregate with adjustments<br/>velocity: 10 × 0.2 = 2<br/>rules: 20 × 0.3 = 6<br/>ml: 50 × 0.4 = 20<br/>device: 50 × 0.1 = 5<br/>timeout_penalty: 15<br/>TOTAL: 48
    
    Orchestrator->>Orchestrator: 7. Decision: MANUAL_REVIEW<br/>(score 30-70 AND timeouts occurred)
    
    Orchestrator->>Orchestrator: 8. Add flags:<br/>- ML_SERVICE_TIMEOUT<br/>- DEVICE_SERVICE_TIMEOUT<br/>- REQUIRES_MANUAL_VERIFICATION
    
    Orchestrator->>DB: 9. Persist fraud_score<br/>with timeout flags
    
    Orchestrator->>Orchestrator: 10. Emit metrics:<br/>ml_service_timeout_total++<br/>device_service_timeout_total++
    
    Orchestrator-->>Fraud: 11. FraudCheckResponse
    
    Fraud-->>Checkout: 12. 200 OK {<br/>decision: MANUAL_REVIEW,<br/>score: 48,<br/>risk_level: MEDIUM,<br/>flags: [ML_SERVICE_TIMEOUT,<br/>DEVICE_SERVICE_TIMEOUT],<br/>requires_review: true,<br/>latency_ms: 155<br/>}
    
    Note over Checkout: Transaction queued<br/>for manual review
```
