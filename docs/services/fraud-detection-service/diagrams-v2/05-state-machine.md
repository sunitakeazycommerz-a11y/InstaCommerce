# Fraud Detection Service - State Machine Diagrams

## Review State Machine (Complete Lifecycle)

```mermaid
stateDiagram-v2
    [*] --> PENDING: Fraud score triggers<br/>MANUAL_REVIEW decision
    
    PENDING --> IN_PROGRESS: Analyst claims review<br/>POST /reviews/{id}/claim
    
    PENDING --> AUTO_EXPIRED: SLA breach +<br/>no escalation configured
    
    PENDING --> ESCALATED: SLA breach detected<br/>(scheduled job)
    
    IN_PROGRESS --> APPROVED: Analyst decision:<br/>APPROVE
    
    IN_PROGRESS --> REJECTED: Analyst decision:<br/>REJECT
    
    IN_PROGRESS --> ESCALATED: Analyst decision:<br/>ESCALATE
    
    IN_PROGRESS --> PENDING: Analyst releases review<br/>(unclaim)
    
    ESCALATED --> IN_PROGRESS: Senior analyst claims
    
    ESCALATED --> APPROVED: Senior decision:<br/>APPROVE
    
    ESCALATED --> REJECTED: Senior decision:<br/>REJECT
    
    ESCALATED --> MANAGER_REVIEW: Further escalation<br/>(fraud manager)
    
    MANAGER_REVIEW --> APPROVED: Manager: APPROVE
    MANAGER_REVIEW --> REJECTED: Manager: REJECT
    MANAGER_REVIEW --> COMPLIANCE_HOLD: Compliance issue<br/>detected
    
    COMPLIANCE_HOLD --> APPROVED: Compliance cleared
    COMPLIANCE_HOLD --> REJECTED: Compliance rejected
    
    APPROVED --> [*]
    REJECTED --> [*]
    AUTO_EXPIRED --> [*]
    
    note right of PENDING
        SLA timers:
        - HIGH: 15 min
        - MEDIUM: 1 hour
        - LOW: 4 hours
    end note
    
    note right of IN_PROGRESS
        Analyst actions:
        - View transaction
        - Check user history
        - Verify device
        - Make decision
    end note
    
    note right of ESCALATED
        Auto-escalation triggers:
        - SLA breach
        - Analyst escalation
        - High-value transaction
    end note
```

## Fraud Score Decision State Machine

```mermaid
stateDiagram-v2
    [*] --> SCORING: POST /api/fraud/check<br/>received
    
    SCORING --> VELOCITY_CHECK: Launch parallel<br/>scoring pipeline
    
    state scoring_fork <<fork>>
    VELOCITY_CHECK --> scoring_fork
    
    scoring_fork --> RULE_EVALUATION
    scoring_fork --> ML_INFERENCE
    scoring_fork --> DEVICE_VERIFICATION
    
    state scoring_join <<join>>
    RULE_EVALUATION --> scoring_join
    ML_INFERENCE --> scoring_join
    DEVICE_VERIFICATION --> scoring_join
    
    scoring_join --> AGGREGATING: All checks complete<br/>or timeout
    
    AGGREGATING --> SCORE_CALCULATED: Weighted average<br/>computed
    
    SCORE_CALCULATED --> AUTO_APPROVED: Score < 30<br/>LOW risk
    
    SCORE_CALCULATED --> PENDING_REVIEW: Score 30-70<br/>MEDIUM risk
    
    SCORE_CALCULATED --> AUTO_REJECTED: Score > 70<br/>HIGH risk
    
    AUTO_APPROVED --> PERSISTED: Save to fraud_scores
    PENDING_REVIEW --> PERSISTED: Save + queue review
    AUTO_REJECTED --> PERSISTED: Save to fraud_scores
    
    PERSISTED --> EVENT_EMITTED: Kafka event sent
    
    EVENT_EMITTED --> [*]
    
    note right of VELOCITY_CHECK
        Redis sliding windows:
        - Order frequency
        - Device usage
        - IP attempts
        - Payment amount
    end note
    
    note right of RULE_EVALUATION
        Drools rule engine:
        - Amount thresholds
        - Pattern detection
        - Category risk
    end note
    
    note right of ML_INFERENCE
        XGBoost model:
        - 50+ features
        - Real-time inference
        - Calibrated scores
    end note
    
    note right of AUTO_APPROVED
        Transaction proceeds
        No human intervention
        ~70% of transactions
    end note
    
    note right of PENDING_REVIEW
        Queued for analyst
        SLA timer starts
        ~25% of transactions
    end note
    
    note right of AUTO_REJECTED
        Transaction blocked
        User notified
        ~5% of transactions
    end note
```

## Transaction Fraud Status State Machine

```mermaid
stateDiagram-v2
    [*] --> UNCHECKED: Transaction<br/>created
    
    UNCHECKED --> CHECKING: Fraud check<br/>initiated
    
    CHECKING --> APPROVED: Decision:<br/>AUTO_APPROVE
    
    CHECKING --> PENDING_REVIEW: Decision:<br/>MANUAL_REVIEW
    
    CHECKING --> REJECTED: Decision:<br/>AUTO_REJECT
    
    CHECKING --> CHECK_FAILED: Service error<br/>or timeout
    
    CHECK_FAILED --> CHECKING: Retry attempt
    
    CHECK_FAILED --> MANUAL_FALLBACK: Max retries<br/>exceeded
    
    MANUAL_FALLBACK --> PENDING_REVIEW: Queue for<br/>manual review
    
    PENDING_REVIEW --> APPROVED: Analyst:<br/>APPROVE
    
    PENDING_REVIEW --> REJECTED: Analyst:<br/>REJECT
    
    PENDING_REVIEW --> ESCALATED: Analyst:<br/>ESCALATE
    
    ESCALATED --> APPROVED: Senior:<br/>APPROVE
    
    ESCALATED --> REJECTED: Senior:<br/>REJECT
    
    APPROVED --> FLAGGED: Post-transaction<br/>fraud detected
    
    FLAGGED --> CHARGEBACK_REVIEW: Chargeback<br/>received
    
    CHARGEBACK_REVIEW --> CHARGEBACK_APPROVED: Fraud confirmed<br/>refund issued
    
    CHARGEBACK_REVIEW --> CHARGEBACK_DENIED: Legitimate<br/>transaction
    
    REJECTED --> [*]
    APPROVED --> [*]
    CHARGEBACK_APPROVED --> [*]
    CHARGEBACK_DENIED --> [*]
    
    note right of APPROVED
        Transaction allowed
        Order proceeds
        Monitoring continues
    end note
    
    note right of REJECTED
        Transaction blocked
        Payment voided
        User notified
    end note
    
    note right of FLAGGED
        Post-facto detection:
        - ML model retrain flag
        - Similar pattern search
        - Account review
    end note
```

## Rule Engine State Machine

```mermaid
stateDiagram-v2
    [*] --> IDLE: Rule engine<br/>initialized
    
    IDLE --> LOADING_RULES: evaluateRules()<br/>called
    
    LOADING_RULES --> CACHE_CHECK: Check Redis<br/>rule cache
    
    CACHE_CHECK --> RULES_CACHED: Cache HIT<br/>(within 5 min)
    
    CACHE_CHECK --> DB_FETCH: Cache MISS
    
    DB_FETCH --> RULES_LOADED: Load from<br/>PostgreSQL
    
    RULES_LOADED --> CACHE_UPDATE: Store in Redis<br/>(TTL: 5 min)
    
    RULES_CACHED --> SESSION_CREATED: Create KIE<br/>session
    
    CACHE_UPDATE --> SESSION_CREATED
    
    SESSION_CREATED --> FACTS_INSERTED: Insert facts<br/>into working memory
    
    FACTS_INSERTED --> RULES_FIRING: fireAllRules()
    
    RULES_FIRING --> RULE_MATCHED: Rule condition<br/>matches
    
    RULE_MATCHED --> ACTION_EXECUTED: Execute rule<br/>action (flag)
    
    ACTION_EXECUTED --> RULES_FIRING: Check next rule
    
    RULES_FIRING --> EVALUATION_COMPLETE: No more<br/>rules to fire
    
    EVALUATION_COMPLETE --> RESULTS_COLLECTED: Collect triggered<br/>rules & flags
    
    RESULTS_COLLECTED --> SCORE_CALCULATED: Calculate<br/>rule score
    
    SCORE_CALCULATED --> SESSION_DISPOSED: Dispose<br/>KIE session
    
    SESSION_DISPOSED --> IDLE: Return result
    
    note right of RULES_FIRING
        Rule priority order:
        1. Blocklist rules
        2. Velocity rules
        3. Amount rules
        4. Pattern rules
    end note
    
    note right of RULE_MATCHED
        Rule types:
        - HIGH_VALUE_TXN
        - NEW_ACCOUNT
        - PROMO_ABUSE
        - IMPOSSIBLE_TRAVEL
    end note
```

## ML Scoring Pipeline State Machine

```mermaid
stateDiagram-v2
    [*] --> IDLE: ML service ready
    
    IDLE --> FEATURE_EXTRACTION: getScore() called
    
    state feature_parallel <<fork>>
    FEATURE_EXTRACTION --> feature_parallel
    
    feature_parallel --> USER_FEATURES: Fetch from<br/>feature store
    feature_parallel --> TXN_FEATURES: Extract from<br/>request
    feature_parallel --> BEHAVIOR_FEATURES: Session<br/>analytics
    feature_parallel --> DEVICE_FEATURES: Device<br/>profile
    feature_parallel --> NETWORK_FEATURES: IP & geo<br/>data
    
    state feature_join <<join>>
    USER_FEATURES --> feature_join
    TXN_FEATURES --> feature_join
    BEHAVIOR_FEATURES --> feature_join
    DEVICE_FEATURES --> feature_join
    NETWORK_FEATURES --> feature_join
    
    feature_join --> VECTOR_BUILT: Feature vector<br/>assembled
    
    VECTOR_BUILT --> VALIDATION: Validate<br/>feature values
    
    VALIDATION --> INFERENCE_CALL: Features valid
    
    VALIDATION --> FEATURE_IMPUTATION: Missing/invalid<br/>features
    
    FEATURE_IMPUTATION --> INFERENCE_CALL: Imputed with<br/>defaults
    
    INFERENCE_CALL --> MODEL_PREDICTING: gRPC to<br/>ML Inference
    
    MODEL_PREDICTING --> RAW_SCORE: Model returns<br/>probability
    
    MODEL_PREDICTING --> TIMEOUT: Inference<br/>timeout (100ms)
    
    TIMEOUT --> FALLBACK_SCORE: Use rule-based<br/>estimate
    
    RAW_SCORE --> CALIBRATION: Platt scaling<br/>calibration
    
    CALIBRATION --> NORMALIZATION: Scale to 0-100
    
    FALLBACK_SCORE --> NORMALIZATION
    
    NORMALIZATION --> RESULT_READY: MLResult<br/>created
    
    RESULT_READY --> IDLE: Return to caller
    
    note right of VECTOR_BUILT
        50+ feature dimensions:
        - User history (15)
        - Transaction (12)
        - Behavioral (10)
        - Device (8)
        - Network (7)
    end note
    
    note right of CALIBRATION
        Platt scaling:
        - Learned on validation set
        - Converts to true probabilities
        - Updated with model
    end note
```

## Velocity Counter State Machine

```mermaid
stateDiagram-v2
    [*] --> INITIALIZED: Velocity check<br/>requested
    
    INITIALIZED --> BUILDING_KEYS: Build Redis keys<br/>for all windows
    
    BUILDING_KEYS --> BATCH_CHECK: Start MULTI<br/>transaction
    
    BATCH_CHECK --> WINDOW_CHECK_1: ZRANGEBYSCORE<br/>user:orders (1h)
    WINDOW_CHECK_1 --> WINDOW_CHECK_2: ZRANGEBYSCORE<br/>device:txns (24h)
    WINDOW_CHECK_2 --> WINDOW_CHECK_3: ZRANGEBYSCORE<br/>ip:attempts (10m)
    WINDOW_CHECK_3 --> WINDOW_CHECK_4: GET<br/>amount:24h
    WINDOW_CHECK_4 --> WINDOW_CHECK_5: ZRANGEBYSCORE<br/>card:txns (1h)
    
    WINDOW_CHECK_5 --> EXEC_READ: Execute batch
    
    EXEC_READ --> EVALUATING: Parse results
    
    EVALUATING --> LIMIT_OK: All within<br/>limits
    
    EVALUATING --> LIMIT_BREACHED: One or more<br/>limits exceeded
    
    LIMIT_OK --> COUNTER_UPDATE: Update counters<br/>for this txn
    
    LIMIT_BREACHED --> FLAG_GENERATION: Generate<br/>violation flags
    
    FLAG_GENERATION --> COUNTER_UPDATE
    
    COUNTER_UPDATE --> BATCH_WRITE: Start MULTI<br/>for updates
    
    BATCH_WRITE --> ZADD_ENTRIES: Add new entries<br/>to sorted sets
    
    ZADD_ENTRIES --> INCRBY_AMOUNT: Increment<br/>amount counter
    
    INCRBY_AMOUNT --> SET_EXPIRES: Set TTLs<br/>on all keys
    
    SET_EXPIRES --> EXEC_WRITE: Execute batch
    
    EXEC_WRITE --> SCORE_CALC: Calculate<br/>velocity score
    
    SCORE_CALC --> RESULT_READY: VelocityResult<br/>assembled
    
    RESULT_READY --> [*]
    
    note right of EVALUATING
        Limits checked:
        - Orders/hour: 10
        - Device txns/day: 50
        - IP attempts/10min: 20
        - Amount/day: ₹50,000
        - Card txns/hour: 5
    end note
    
    note right of LIMIT_BREACHED
        Flags generated:
        - HIGH_ORDER_FREQUENCY
        - EXCESSIVE_DEVICE_USAGE
        - IP_RATE_EXCEEDED
        - HIGH_DAILY_SPEND
        - CARD_VELOCITY_EXCEEDED
    end note
```

## Circuit Breaker State Machine (External Services)

```mermaid
stateDiagram-v2
    [*] --> CLOSED: Service healthy<br/>normal operation
    
    CLOSED --> CALL_SUCCESS: Request succeeds<br/>(<100ms, 2xx response)
    
    CALL_SUCCESS --> RESET_FAILURES: error_count = 0
    
    RESET_FAILURES --> CLOSED
    
    CLOSED --> CALL_FAILURE: Request fails<br/>(timeout, 5xx, network)
    
    CALL_FAILURE --> INCREMENT_FAILURE: error_count++
    
    INCREMENT_FAILURE --> THRESHOLD_CHECK: Check failure<br/>threshold
    
    THRESHOLD_CHECK --> CLOSED: count < 5<br/>(threshold)
    
    THRESHOLD_CHECK --> OPEN: count >= 5<br/>OR error_rate > 50%
    
    OPEN --> FAIL_FAST: All requests<br/>fail immediately
    
    FAIL_FAST --> USE_FALLBACK: Return fallback<br/>value
    
    USE_FALLBACK --> EMIT_METRICS: circuit_breaker_open++<br/>fallback_used++
    
    EMIT_METRICS --> BACKOFF_TIMER: Wait 30 seconds
    
    BACKOFF_TIMER --> HALF_OPEN: Timer expires<br/>try test request
    
    HALF_OPEN --> PROBE_REQUEST: Send 1 test<br/>request
    
    PROBE_REQUEST --> PROBE_SUCCESS: Test succeeds
    
    PROBE_REQUEST --> PROBE_FAILURE: Test fails
    
    PROBE_SUCCESS --> CLOSED: Reset state<br/>service recovered
    
    PROBE_FAILURE --> OPEN: Extend backoff<br/>60 seconds
    
    note right of OPEN
        Services protected:
        - ML Inference (gRPC)
        - ThreatMetrix (REST)
        - Feature Store (Redis)
    end note
    
    note right of USE_FALLBACK
        Fallback values:
        - ML: rule-based score
        - Device: neutral score (50)
        - Features: cached values
    end note
    
    note right of HALF_OPEN
        Gradual recovery:
        - 1 test request
        - If OK: close
        - If fail: reopen + extend
    end note
```

## SLA Timer State Machine

```mermaid
stateDiagram-v2
    [*] --> TIMER_CREATED: Review queued<br/>SLA deadline set
    
    TIMER_CREATED --> TIMER_RUNNING: Timer started<br/>(deadline = now + SLA)
    
    TIMER_RUNNING --> CHECKING: Scheduler tick<br/>(every 1 min)
    
    CHECKING --> STILL_PENDING: Review still<br/>PENDING
    
    CHECKING --> CLAIMED: Review<br/>IN_PROGRESS
    
    CHECKING --> COMPLETED: Review<br/>APPROVED/REJECTED
    
    STILL_PENDING --> DEADLINE_CHECK: Check if<br/>deadline passed
    
    DEADLINE_CHECK --> TIMER_RUNNING: deadline > now<br/>(still time)
    
    DEADLINE_CHECK --> BREACH_DETECTED: deadline <= now<br/>(SLA breached)
    
    BREACH_DETECTED --> CALCULATE_SEVERITY: Determine<br/>breach severity
    
    CALCULATE_SEVERITY --> WARNING: 0-5 min<br/>over deadline
    
    CALCULATE_SEVERITY --> CRITICAL: 5-15 min<br/>over deadline
    
    CALCULATE_SEVERITY --> SEVERE: >15 min<br/>over deadline
    
    WARNING --> ESCALATE: Auto-escalate<br/>to senior
    
    CRITICAL --> ESCALATE_ALERT: Escalate +<br/>PagerDuty alert
    
    SEVERE --> ESCALATE_INCIDENT: Escalate +<br/>incident created
    
    ESCALATE --> TIMER_RESET: New SLA for<br/>escalated review
    
    ESCALATE_ALERT --> TIMER_RESET
    ESCALATE_INCIDENT --> TIMER_RESET
    
    TIMER_RESET --> TIMER_RUNNING
    
    CLAIMED --> SLA_MET: Claimed within<br/>SLA window
    
    SLA_MET --> TIMER_STOPPED: Emit<br/>sla_met metric
    
    COMPLETED --> TIMER_STOPPED: Review completed
    
    TIMER_STOPPED --> [*]
    
    note right of TIMER_CREATED
        SLA deadlines:
        - HIGH: 15 min
        - MEDIUM: 1 hour
        - LOW: 4 hours
    end note
    
    note right of BREACH_DETECTED
        Breach triggers:
        - Escalation
        - Alert/incident
        - Metric emission
        - Audit log
    end note
```
