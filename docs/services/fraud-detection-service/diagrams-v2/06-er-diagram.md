# Fraud Detection Service - ER Diagram & Storage Schema

## Core Fraud Detection Tables

```mermaid
erDiagram
    FRAUD_SCORES ||--o{ FRAUD_REVIEWS : "may trigger"
    FRAUD_SCORES ||--o{ SCORE_COMPONENTS : "has"
    FRAUD_SCORES ||--o{ FRAUD_FLAGS : "has"
    FRAUD_REVIEWS ||--o{ REVIEW_ACTIONS : "has"
    RULES ||--o{ RULE_TRIGGERS : "triggers"
    FRAUD_SCORES ||--o{ RULE_TRIGGERS : "has"
    DEVICES ||--o{ DEVICE_ASSOCIATIONS : "has"
    USERS }o--|| DEVICE_ASSOCIATIONS : "uses"
    
    FRAUD_SCORES {
        uuid id PK "Primary key"
        uuid transaction_id FK "Reference to order/payment"
        uuid user_id FK "User being scored"
        string device_id "Device fingerprint ID"
        decimal amount "Transaction amount"
        string currency "INR, USD, etc."
        int composite_score "Final score (0-100)"
        string decision "AUTO_APPROVE, MANUAL_REVIEW, AUTO_REJECT"
        string final_decision "APPROVED, REJECTED (after review)"
        string risk_level "LOW, MEDIUM, HIGH, CRITICAL"
        jsonb metadata "Additional context"
        timestamp created_at "Score timestamp"
        timestamp updated_at "Last update"
        int latency_ms "Total scoring latency"
    }
    
    SCORE_COMPONENTS {
        uuid id PK "Primary key"
        uuid fraud_score_id FK "Parent score"
        string component_type "VELOCITY, RULES, ML, DEVICE"
        int raw_score "Component score (0-100)"
        decimal weight "Contribution weight (0.0-1.0)"
        int weighted_score "raw_score * weight"
        int latency_ms "Component latency"
        boolean timeout "True if component timed out"
        jsonb details "Component-specific details"
        timestamp created_at "Timestamp"
    }
    
    FRAUD_FLAGS {
        uuid id PK "Primary key"
        uuid fraud_score_id FK "Parent score"
        string flag_code "e.g., HIGH_ORDER_FREQUENCY"
        string flag_type "VELOCITY, RULE, ML, DEVICE"
        string severity "LOW, MEDIUM, HIGH, CRITICAL"
        string description "Human-readable description"
        jsonb context "Flag-specific context"
        timestamp created_at "Timestamp"
    }
    
    FRAUD_REVIEWS {
        uuid id PK "Primary key"
        uuid fraud_score_id FK "Score under review"
        string status "PENDING, IN_PROGRESS, APPROVED, REJECTED, ESCALATED"
        string priority "LOW, MEDIUM, HIGH"
        uuid analyst_id FK "Assigned analyst"
        timestamp sla_deadline "Review deadline"
        timestamp claimed_at "When analyst claimed"
        timestamp decided_at "When decision made"
        string decision_notes "Analyst notes"
        string escalation_reason "Reason for escalation"
        uuid escalated_to FK "Senior analyst/manager"
        timestamp created_at "Queue timestamp"
        timestamp updated_at "Last update"
    }
    
    REVIEW_ACTIONS {
        uuid id PK "Primary key"
        uuid review_id FK "Parent review"
        uuid analyst_id FK "Who took action"
        string action_type "CLAIM, RELEASE, DECIDE, ESCALATE, NOTE"
        string action_value "Decision or note content"
        jsonb metadata "Action context"
        timestamp created_at "Action timestamp"
    }
    
    RULES {
        uuid id PK "Primary key"
        string rule_code UK "Unique rule identifier"
        string name "Human-readable name"
        string description "Rule description"
        string rule_type "VELOCITY, AMOUNT, PATTERN, CATEGORY, GEO"
        text condition_drl "Drools rule definition"
        string action "FLAG, BLOCK, REVIEW"
        int severity_score "Score contribution (0-100)"
        decimal weight "Rule weight"
        boolean active "Is rule active"
        int priority "Execution priority"
        jsonb parameters "Configurable thresholds"
        timestamp effective_from "Rule start date"
        timestamp effective_to "Rule end date (null = forever)"
        timestamp created_at "Creation timestamp"
        timestamp updated_at "Last update"
        uuid created_by FK "Admin who created"
    }
    
    RULE_TRIGGERS {
        uuid id PK "Primary key"
        uuid fraud_score_id FK "Score where triggered"
        uuid rule_id FK "Rule that triggered"
        string rule_code "Rule code (denormalized)"
        jsonb matched_conditions "What conditions matched"
        int score_contribution "Points added to score"
        timestamp created_at "Trigger timestamp"
    }
    
    DEVICES {
        string device_id PK "ThreatMetrix device ID"
        int trust_score "Device trust (0-100)"
        string device_type "MOBILE, DESKTOP, TABLET"
        string os "iOS, Android, Windows, etc."
        string browser "Safari, Chrome, etc."
        boolean rooted "Jailbroken/rooted device"
        boolean proxy_detected "VPN/proxy detected"
        string risk_level "LOW, MEDIUM, HIGH"
        timestamp first_seen "First appearance"
        timestamp last_seen "Last appearance"
        int transaction_count "Total transactions"
        int fraud_count "Fraudulent transactions"
        jsonb attributes "Device attributes"
        timestamp created_at "Creation timestamp"
        timestamp updated_at "Last update"
    }
    
    DEVICE_ASSOCIATIONS {
        uuid id PK "Primary key"
        string device_id FK "Device reference"
        uuid user_id FK "User reference"
        timestamp first_seen "First association"
        timestamp last_seen "Last association"
        int transaction_count "Transactions on this combo"
        boolean primary_device "Is user's primary device"
        timestamp created_at "Creation timestamp"
    }
```

## Velocity & Analytics Tables

```mermaid
erDiagram
    VELOCITY_EVENTS ||--o{ VELOCITY_AGGREGATES : "aggregated into"
    FRAUD_SCORES ||--o{ VELOCITY_EVENTS : "generates"
    
    VELOCITY_EVENTS {
        uuid id PK "Primary key"
        string event_type "ORDER, PAYMENT, LOGIN, DEVICE_CHANGE"
        uuid user_id FK "User reference"
        string device_id "Device identifier"
        string ip_address "Client IP"
        string card_hash "Hashed card number"
        decimal amount "Transaction amount"
        string currency "Currency code"
        timestamp event_time "Event timestamp"
        jsonb metadata "Event context"
        timestamp created_at "Record timestamp"
    }
    
    VELOCITY_AGGREGATES {
        uuid id PK "Primary key"
        string aggregate_type "USER_ORDERS_1H, DEVICE_TXNS_24H, etc."
        string entity_id "User ID, Device ID, IP, etc."
        string window_type "SLIDING, TUMBLING"
        int window_size_seconds "Window duration"
        timestamp window_start "Window start time"
        timestamp window_end "Window end time"
        int event_count "Count in window"
        decimal total_amount "Sum of amounts"
        decimal max_amount "Max single amount"
        jsonb breakdown "Detailed breakdown"
        timestamp calculated_at "Calculation timestamp"
    }
    
    FRAUD_METRICS {
        uuid id PK "Primary key"
        string metric_type "APPROVAL_RATE, REVIEW_TIME, FALSE_POSITIVE"
        string dimension "OVERALL, BY_CATEGORY, BY_AMOUNT_RANGE"
        string dimension_value "electronics, 1000-5000, etc."
        date metric_date "Date of metric"
        decimal value "Metric value"
        int sample_size "Number of transactions"
        jsonb breakdown "Detailed breakdown"
        timestamp calculated_at "Calculation timestamp"
    }
    
    ML_MODEL_VERSIONS {
        uuid id PK "Primary key"
        string model_name "fraud_detection_xgboost"
        string version "v2.3.1"
        string status "TRAINING, VALIDATING, ACTIVE, DEPRECATED"
        timestamp trained_at "Training completion"
        decimal auc_score "Model AUC on validation"
        decimal precision_score "Precision score"
        decimal recall_score "Recall score"
        decimal f1_score "F1 score"
        jsonb feature_importance "Feature weights"
        jsonb training_config "Hyperparameters"
        string artifact_path "S3 path to model"
        timestamp activated_at "When made active"
        timestamp deprecated_at "When deprecated"
        timestamp created_at "Record timestamp"
    }
    
    BLOCKLISTS {
        uuid id PK "Primary key"
        string list_type "IP, DEVICE, EMAIL_DOMAIN, CARD_BIN"
        string value "Blocked value"
        string reason "Why blocked"
        uuid added_by FK "Admin who added"
        timestamp effective_from "Start blocking"
        timestamp effective_to "Stop blocking (null = forever)"
        boolean active "Is currently active"
        int hits_count "Times matched"
        timestamp last_hit_at "Last match timestamp"
        timestamp created_at "Creation timestamp"
    }
```

## Redis Cache Schema & Keys

```mermaid
graph TB
    Redis["Redis Cluster<br/>(Velocity & Caching)"]
    
    subgraph velocity["Velocity Counters (Sorted Sets)"]
        V1["velocity:user:{id}:orders<br/>Type: ZSET<br/>Score: timestamp<br/>Value: txn_id<br/>TTL: 2 hours"]
        
        V2["velocity:device:{id}:txns<br/>Type: ZSET<br/>Score: timestamp<br/>Value: txn_id<br/>TTL: 25 hours"]
        
        V3["velocity:ip:{ip}:attempts<br/>Type: ZSET<br/>Score: timestamp<br/>Value: attempt_id<br/>TTL: 15 minutes"]
        
        V4["velocity:card:{hash}:txns<br/>Type: ZSET<br/>Score: timestamp<br/>Value: txn_id<br/>TTL: 2 hours"]
    end
    
    subgraph counters["Amount Counters (Strings)"]
        C1["velocity:user:{id}:amount:24h<br/>Type: STRING<br/>Value: total amount<br/>TTL: 25 hours"]
        
        C2["velocity:user:{id}:order_count:7d<br/>Type: STRING<br/>Value: count<br/>TTL: 8 days"]
    end
    
    subgraph cache["Caches"]
        Cache1["rules:active<br/>Type: JSON<br/>TTL: 5 min<br/>Value: [{rule_id, condition, action}]"]
        
        Cache2["device:{device_id}<br/>Type: JSON<br/>TTL: 24 hours<br/>Value: {trust_score, users[], flags}"]
        
        Cache3["features:user:{id}<br/>Type: JSON<br/>TTL: 1 hour<br/>Value: {account_age, order_count, avg_value}"]
        
        Cache4["blocklist:ip<br/>Type: SET<br/>TTL: 1 hour<br/>Value: blocked IPs"]
        
        Cache5["blocklist:device<br/>Type: SET<br/>TTL: 1 hour<br/>Value: blocked device IDs"]
    end
    
    subgraph locks["Distributed Locks"]
        L1["lock:review:{id}<br/>Type: STRING<br/>TTL: 30 min<br/>Value: analyst_id"]
        
        L2["lock:rule_reload<br/>Type: STRING<br/>TTL: 1 min<br/>Value: node_id"]
    end
    
    Redis --> velocity
    Redis --> counters
    Redis --> cache
    Redis --> locks
    
    style Redis fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
```

## Prometheus Metrics Schema

```
Fraud Detection Service Metrics
├─ fraud_check_duration_ms{endpoint}
│  └ Histogram: Total fraud check latency
│     - p50: 80ms, p99: <200ms
│
├─ fraud_score_distribution{decision}
│  └ Histogram: Score distribution by decision
│     - AUTO_APPROVE: 0-30
│     - MANUAL_REVIEW: 30-70
│     - AUTO_REJECT: 70-100
│
├─ scoring_component_duration_ms{component}
│  └ Histogram: Per-component latency
│     - component: velocity, rules, ml, device
│
├─ scoring_component_timeout_total{component}
│  └ Counter: Component timeout count
│     - Indicates service degradation
│
├─ velocity_breaches_total{limit_type}
│  └ Counter: Velocity limit breaches
│     - limit_type: order_frequency, device_usage, etc.
│
├─ rules_triggered_total{rule_code, severity}
│  └ Counter: Rules triggered by type
│     - Tracks rule effectiveness
│
├─ ml_inference_duration_ms
│  └ Histogram: ML model inference time
│     - Target p99: <100ms
│
├─ device_verification_duration_ms
│  └ Histogram: ThreatMetrix API latency
│     - Target p99: <100ms
│
├─ reviews_queued_total{priority}
│  └ Counter: Reviews added to queue
│     - priority: low, medium, high
│
├─ reviews_completed_total{decision}
│  └ Counter: Reviews completed
│     - decision: approved, rejected, escalated
│
├─ review_sla_breaches_total{priority}
│  └ Counter: SLA breach count
│     - Alert if increasing
│
├─ review_time_to_decision_ms{priority}
│  └ Histogram: Time from queue to decision
│     - SLA targets: HIGH 15min, MEDIUM 1hr
│
├─ circuit_breaker_state{service}
│  └ Gauge: Circuit breaker status
│     - service: ml_inference, threatmetrix
│     - 0: CLOSED, 1: OPEN, 2: HALF_OPEN
│
└─ decision_accuracy{decision_type}
   └ Gauge: Fraud detection accuracy
      - Calculated from chargebacks
      - Updated weekly
```

## Kafka Event Schema

```mermaid
graph TB
    Kafka["Kafka Cluster"]
    
    subgraph topics["Fraud Event Topics"]
        T1["fraud.score.calculated<br/>Partition: by user_id<br/>Retention: 30 days"]
        T1Content["Event: FraudScoreCalculated<br/>{<br/>  event_id: UUID,<br/>  transaction_id: UUID,<br/>  user_id: UUID,<br/>  score: int,<br/>  decision: string,<br/>  components: {...},<br/>  flags: [...],<br/>  timestamp: ISO8601<br/>}"]
        
        T2["fraud.review.queued<br/>Partition: by priority<br/>Retention: 7 days"]
        T2Content["Event: ReviewQueued<br/>{<br/>  event_id: UUID,<br/>  review_id: UUID,<br/>  fraud_score_id: UUID,<br/>  priority: string,<br/>  sla_deadline: ISO8601,<br/>  timestamp: ISO8601<br/>}"]
        
        T3["fraud.review.completed<br/>Partition: by user_id<br/>Retention: 90 days"]
        T3Content["Event: ReviewCompleted<br/>{<br/>  event_id: UUID,<br/>  review_id: UUID,<br/>  decision: string,<br/>  analyst_id: UUID,<br/>  time_to_decision_ms: int,<br/>  notes: string,<br/>  timestamp: ISO8601<br/>}"]
        
        T4["fraud.transaction.approved<br/>Partition: by transaction_id<br/>Retention: 30 days"]
        T4Content["Event: TransactionApproved<br/>{<br/>  event_id: UUID,<br/>  transaction_id: UUID,<br/>  fraud_score_id: UUID,<br/>  approval_type: AUTO|MANUAL,<br/>  timestamp: ISO8601<br/>}"]
        
        T5["fraud.transaction.rejected<br/>Partition: by transaction_id<br/>Retention: 90 days"]
        T5Content["Event: TransactionRejected<br/>{<br/>  event_id: UUID,<br/>  transaction_id: UUID,<br/>  fraud_score_id: UUID,<br/>  rejection_reason: string,<br/>  flags: [...],<br/>  timestamp: ISO8601<br/>}"]
    end
    
    Kafka --> T1
    Kafka --> T2
    Kafka --> T3
    Kafka --> T4
    Kafka --> T5
    
    T1 --> T1Content
    T2 --> T2Content
    T3 --> T3Content
    T4 --> T4Content
    T5 --> T5Content
    
    style Kafka fill:#50E3C2,color:#000,stroke:#333,stroke-width:2px
```

## Database Indexes & Constraints

```sql
-- fraud_scores indexes
CREATE INDEX idx_fraud_scores_transaction ON fraud_scores(transaction_id);
CREATE INDEX idx_fraud_scores_user ON fraud_scores(user_id);
CREATE INDEX idx_fraud_scores_device ON fraud_scores(device_id);
CREATE INDEX idx_fraud_scores_decision ON fraud_scores(decision);
CREATE INDEX idx_fraud_scores_created ON fraud_scores(created_at DESC);
CREATE INDEX idx_fraud_scores_user_created ON fraud_scores(user_id, created_at DESC);

-- fraud_reviews indexes
CREATE INDEX idx_fraud_reviews_status ON fraud_reviews(status);
CREATE INDEX idx_fraud_reviews_priority ON fraud_reviews(priority);
CREATE INDEX idx_fraud_reviews_analyst ON fraud_reviews(analyst_id);
CREATE INDEX idx_fraud_reviews_sla ON fraud_reviews(sla_deadline) 
    WHERE status = 'PENDING';
CREATE INDEX idx_fraud_reviews_pending ON fraud_reviews(created_at) 
    WHERE status = 'PENDING';

-- rules indexes
CREATE UNIQUE INDEX idx_rules_code ON rules(rule_code);
CREATE INDEX idx_rules_active ON rules(active, priority) 
    WHERE active = true;
CREATE INDEX idx_rules_type ON rules(rule_type);

-- devices indexes
CREATE INDEX idx_devices_trust ON devices(trust_score);
CREATE INDEX idx_devices_risk ON devices(risk_level);
CREATE INDEX idx_devices_last_seen ON devices(last_seen DESC);

-- device_associations indexes
CREATE INDEX idx_device_assoc_user ON device_associations(user_id);
CREATE INDEX idx_device_assoc_device ON device_associations(device_id);
CREATE UNIQUE INDEX idx_device_assoc_unique ON device_associations(device_id, user_id);

-- velocity_events indexes (partitioned by month)
CREATE INDEX idx_velocity_events_user ON velocity_events(user_id, event_time DESC);
CREATE INDEX idx_velocity_events_device ON velocity_events(device_id, event_time DESC);
CREATE INDEX idx_velocity_events_ip ON velocity_events(ip_address, event_time DESC);

-- Constraints
ALTER TABLE fraud_scores ADD CONSTRAINT chk_score_range 
    CHECK (composite_score >= 0 AND composite_score <= 100);
ALTER TABLE fraud_scores ADD CONSTRAINT chk_decision 
    CHECK (decision IN ('AUTO_APPROVE', 'MANUAL_REVIEW', 'AUTO_REJECT'));
ALTER TABLE fraud_reviews ADD CONSTRAINT chk_status 
    CHECK (status IN ('PENDING', 'IN_PROGRESS', 'APPROVED', 'REJECTED', 'ESCALATED'));
ALTER TABLE rules ADD CONSTRAINT chk_severity 
    CHECK (severity_score >= 0 AND severity_score <= 100);
```

## Observability Pipeline

```mermaid
graph LR
    FraudService["Fraud Detection Service"]
    
    subgraph metrics["📊 Metrics Pipeline"]
        MPrometheus["Prometheus<br/>9090"]
        MGrafana["Grafana<br/>3000"]
        MAlerting["Alertmanager<br/>Alerts"]
    end
    
    subgraph logs["📝 Logging Pipeline"]
        LElasticsearch["Elasticsearch"]
        LKibana["Kibana<br/>Visualization"]
        LAlerts["Log-based Alerts"]
    end
    
    subgraph traces["📈 Tracing Pipeline"]
        TJaeger["Jaeger<br/>Query UI"]
        TCollection["Jaeger Collector"]
    end
    
    subgraph events["📬 Event Pipeline"]
        EKafka["Kafka<br/>Fraud Events"]
        EConsumer["Event Consumers<br/>(analytics, ML training)"]
    end
    
    subgraph dashboards["📊 Fraud Dashboards"]
        D1["Real-time Fraud Ops<br/>- Score distribution<br/>- Review queue depth<br/>- SLA compliance"]
        D2["Analyst Performance<br/>- Reviews/hour<br/>- Accuracy rate<br/>- Response time"]
        D3["Model Performance<br/>- Precision/Recall<br/>- Score calibration<br/>- Feature drift"]
    end
    
    FraudService -->|Micrometer| MPrometheus
    FraudService -->|Structured Logs| LElasticsearch
    FraudService -->|OpenTelemetry| TCollection
    FraudService -->|Events| EKafka
    
    MPrometheus --> MGrafana
    MPrometheus --> MAlerting
    
    LElasticsearch --> LKibana
    LElasticsearch --> LAlerts
    
    TCollection --> TJaeger
    
    EKafka --> EConsumer
    
    MGrafana --> D1
    MGrafana --> D2
    MGrafana --> D3
    
    style FraudService fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style MPrometheus fill:#9013FE,color:#fff
    style LElasticsearch fill:#F5A623,color:#000
    style TJaeger fill:#52C41A,color:#fff
    style EKafka fill:#50E3C2,color:#000
```
