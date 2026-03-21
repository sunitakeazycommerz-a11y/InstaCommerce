# Fraud Detection Service

**Real-time transaction risk scoring using rule-based heuristics, velocity checks, and pattern matching with event-driven updates and historical flagging.**

| Property | Value |
|----------|-------|
| **Module** | `:services:fraud-detection-service` |
| **Port** | `8091` |
| **Runtime** | Spring Boot 4.0 · Java 21 |
| **Database** | PostgreSQL 15+ (fraud_flags, user_risk_profile, transaction_history) |
| **Messaging** | Kafka consumer (orders.events, payments.events, identity.events) |
| **Resilience** | Graceful degradation (allow txn if service down) |
| **Auth** | JWT RS256 |
| **Owner** | Risk & Compliance Team |
| **SLO** | P50: 20ms, P99: 50ms, 99.9% availability (low latency for payment path) |

---

## Service Role & Ownership

**Owns:**
- `fraud_flags` table — blacklisted/whitelisted users and devices
- `user_risk_profile` table — historical risk assessment per user
- `transaction_history` table — last N transactions per user (velocity checks)
- `fraud_alerts` table — suspicious activity logging for compliance

**Does NOT own:**
- Payment authorization (→ `payment-service`)
- User profiles (→ `identity-service`)
- Order details (→ `order-service`)

**Consumes:**
- `orders.events` — OrderCreated (score transaction)
- `payments.events` — PaymentInitiated (score before authorization)
- `identity.events` — UserCreated (initialize risk profile)

**Publishes:**
- `fraud.events` — FraudAlertTriggered, HighRiskTransactionDetected (via outbox)

---

## Core Scoring API

### Score Transaction

**POST /fraud/score**
```
Request Body:
{
  "transactionId": "transaction-uuid",
  "userId": "user-uuid",
  "amount": 50000,
  "currency": "INR",
  "deviceId": "device-uuid",
  "ipAddress": "203.0.113.45",
  "deviceFingerprint": "hash",
  "previousTransactions": 5,
  "previousAmount": 100000,
  "orderDetails": {
    "itemCount": 3,
    "categories": ["Dairy", "Groceries"]
  }
}

Processing:
  1. Query user risk profile
  2. Query transaction history (last 10 txns)
  3. Apply scoring rules (see below)
  4. Calculate final risk score (0-100)
  5. Check blacklist/whitelist
  6. Return decision (APPROVE, REVIEW, BLOCK)

Response (200):
{
  "score": 35,
  "decision": "APPROVE",
  "reasoning": [
    "Returning customer (low risk)",
    "Amount within normal range"
  ],
  "confidence": 0.92,
  "rulesFired": ["RETURNING_CUSTOMER", "NORMAL_AMOUNT"]
}

Risk Score Bands:
  0-30: APPROVE (green)
  31-70: REVIEW (yellow, manual verification)
  71-100: BLOCK (red, flag for compliance)
```

### Report Suspicious Activity

**POST /fraud/report**
```
Request Body:
{
  "userId": "user-uuid",
  "transactionId": "transaction-uuid",
  "reason": "Unauthorized transaction",
  "evidence": "..."
}

Response (200):
{
  "status": "reported",
  "ticketId": "ticket-uuid"
}

Action:
  1. Create fraud alert
  2. Update user risk profile (increase risk score)
  3. Add to whitelist/blacklist if confirmed
```

---

## Scoring Rules

### Rule: Velocity Checks

```
Rule: Transaction frequency in past hour
  IF user has > 10 transactions in last hour: +25 points
  IF user has > 5 transactions in last hour: +15 points

Rule: Amount spike
  IF (amount > 2x avg transaction amount): +20 points
  IF (amount > 5x avg transaction amount): +40 points
```

### Rule: Geographic Anomalies

```
Rule: Impossible travel
  IF time_delta < 2 hours AND distance > 500 km: +50 points (block)

Rule: New location
  IF country != user's home country AND new location: +15 points
```

### Rule: User Profile

```
Rule: New user (< 7 days)
  IF user age < 7 days: +20 points

Rule: Verified user
  IF user has payment history > 1 month: -10 points

Rule: Returning customer (first transaction approved 30+ days ago)
  IF user_age > 30 days AND no fraud flags: -5 points
```

### Rule: Blacklist/Whitelist

```
IF user in blacklist: score = 100 (block immediately)
IF device in blacklist: score = 75 (high risk)
IF user in whitelist: score = 0 (auto-approve)
IF payment method flagged: +20 points
```

### Rule: Order Context

```
Rule: High-value categories
  IF order contains high-value items (electronics): +10 points

Rule: Bulk order
  IF item count > 20: +10 points
```

---

## Database Schema

### User Risk Profile

```sql
user_risk_profile:
  id              UUID PRIMARY KEY
  user_id         UUID NOT NULL UNIQUE
  risk_score      INT NOT NULL DEFAULT 0
  transaction_count BIGINT DEFAULT 0
  fraud_count     INT DEFAULT 0
  first_txn_at    TIMESTAMP
  last_txn_at     TIMESTAMP
  last_txn_amount BIGINT
  avg_txn_amount  BIGINT
  home_country    VARCHAR(3) (ISO 3166)
  verified_phone  BOOLEAN DEFAULT FALSE
  verified_email  BOOLEAN DEFAULT FALSE
  created_at      TIMESTAMP NOT NULL
  updated_at      TIMESTAMP NOT NULL

Indexes:
  - user_id
  - risk_score (high-risk users query)
```

### Transaction History (Velocity checks)

```sql
transaction_history:
  id              UUID PRIMARY KEY
  user_id         UUID NOT NULL
  transaction_id  UUID NOT NULL
  amount          BIGINT NOT NULL
  device_id       VARCHAR(255)
  ip_address      VARCHAR(45)
  country         VARCHAR(3)
  outcome         ENUM('APPROVED', 'BLOCKED', 'REVIEWED')
  risk_score      INT
  created_at      TIMESTAMP NOT NULL DEFAULT now()

Indexes:
  - user_id, created_at DESC (get last N transactions)
  - ip_address, created_at (geographic patterns)
  - device_id, created_at (device patterns)

Retention: 12 months (rotating archive)
```

### Fraud Flags

```sql
fraud_flags:
  id              UUID PRIMARY KEY
  flag_type       ENUM('BLACKLIST_USER', 'BLACKLIST_DEVICE', 'WHITELIST_USER', 'WATCH_USER')
  target_id       UUID or VARCHAR (user_id, device_id, email, phone)
  target_type     ENUM('USER', 'DEVICE', 'EMAIL', 'PHONE')
  reason          VARCHAR(512)
  flagged_by      VARCHAR(255) (admin or system)
  expires_at      TIMESTAMP (NULL = permanent)
  created_at      TIMESTAMP NOT NULL

Indexes:
  - target_id, target_type (quick lookup)
  - created_at (recent flags)
```

### Fraud Alerts (Compliance)

```sql
fraud_alerts:
  id              UUID PRIMARY KEY
  user_id         UUID NOT NULL
  transaction_id  UUID NOT NULL
  alert_type      ENUM('HIGH_RISK', 'VELOCITY_SPIKE', 'NEW_LOCATION', 'BLACKLIST_HIT')
  description     TEXT
  rule_fired      VARCHAR(255)
  manual_review   BOOLEAN DEFAULT FALSE
  reviewed_by     VARCHAR(255)
  reviewed_at     TIMESTAMP
  action_taken    VARCHAR(255)
  created_at      TIMESTAMP NOT NULL

Indexes:
  - manual_review, created_at (pending review queue)
  - user_id, created_at (user alert history)
```

---

## Kafka Events

### Consumed

**orders.events**
```
OrderCreated → Score transaction
Payload: { orderId, userId, amount, orderDetails }
```

**payments.events**
```
PaymentInitiated → Real-time score
PaymentFailed → Update risk profile
```

**identity.events**
```
UserCreated → Initialize risk profile (score = 0)
```

### Published (Outbox)

**fraud.events**
```
HighRiskTransactionDetected: {
  userId, transactionId, score, decision
}

FraudAlertTriggered: {
  userId, transactionId, alertType, rulesFired
}
```

---

## Resilience

### Graceful Degradation

If fraud-detection service is down or slow:
- Payment service continues with fallback (allow transaction)
- Log warning: "Fraud service unavailable, approving for UX"
- Circuit breaker timeout: 1s
- Cache risk profiles with 5min TTL

### Timeout

- Database query timeout: 2s
- Scoring rule evaluation: 100ms max

---

## Testing

```bash
./gradlew :services:fraud-detection-service:test
./gradlew :services:fraud-detection-service:integrationTest
```

Test scenarios:
- Velocity spike detection
- Impossible travel detection
- Whitelist/blacklist hits
- New user scoring
- Risk profile updates

---

## Deployment

```bash
export FRAUD_DB_URL=jdbc:postgresql://localhost:5432/fraud
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
./gradlew :services:fraud-detection-service:bootRun
```

---

## Compliance & Auditing

- **PCI-DSS:** No card data stored (tokens only)
- **GDPR:** User can request fraud history deletion (soft-delete)
- **Retention:** Transaction history archived after 12 months
- **Alerts:** All high-risk flags logged for compliance review

---

## Real-Time Model Inference & Fallback

**Scoring Architecture:**

```
Score Request
  ↓
Check Whitelist → Return score = 0 (APPROVE)
  ↓ (not whitelisted)
Check Blacklist → Return score = 100 (BLOCK)
  ↓ (not blacklisted)
Call ML Model (timeout: 100ms)
  ↓ Success: Return ML score
  ✗ Timeout/Error: Fallback to Rule Engine
       ↓
Apply 50+ Rules (deterministic, fast)
  ↓
Return final score (0-100)
```

**ML Model Integration:**
- Framework: TensorFlow Lite (lightweight, < 100ms)
- Features: 30 features extracted (velocity, location, device, user profile)
- Output: Risk score (0-100) + feature importance (top 5 features)
- Retraining: Weekly (Monday 2 AM UTC) with production data

**Fallback Rules (if ML unavailable):**
- Velocity check (fast)
- Geographic anomaly
- Device anomaly
- User profile risk
- Whitelist/blacklist override

## Whitelist/Blacklist Management

**Whitelist (Auto-approve):**
- Manual Entry: Admin adds user (never fraud before)
- Auto-Generate: Score < 10 for 30+ consecutive transactions
- TTL: None (permanent unless removed)
- Impact: Score = 0, immediate APPROVE, no rules fired

**Blacklist (Auto-block):**
- Manual Entry: Fraud confirmed, add user
- Auto-Generate: Score > 90 for 2+ consecutive transactions
- TTL: 90 days default (can be permanent)
- Impact: Score = 100, immediate BLOCK, no further processing

**Watchlist (Monitor):**
- Users with recent fraud alerts (< 30 days)
- Higher threshold for automatic approval (score must be < 20)
- Manual review required for transactions

**Example Workflow:**

```
1. User Alice: 50 clean transactions
   → Auto-whitelist trigger: Add to whitelist
   → Future score for Alice always 0

2. User Bob: 2 confirmed fraud transactions
   → Manually blacklist: Add to blacklist (90-day TTL)
   → Future score for Bob always 100
   → After 90 days: Automatic removal, require re-verification

3. User Charlie: 1 suspicious transaction (manually reviewed)
   → Add to watchlist (30-day TTL)
   → Future thresholds: score < 20 for auto-approve (stricter)
```

## Transaction Scoring Explanation

**Feature Importance Breakdown:**

```json
{
  "transactionId": "txn-uuid",
  "score": 42,
  "decision": "REVIEW",
  "modelUsed": "ml_v2.3",
  "topFeatures": [
    {
      "name": "velocity_in_last_hour",
      "value": 8,
      "impact": 25,
      "interpretation": "User has 8 txns in last hour (high velocity)"
    },
    {
      "name": "geographic_anomaly",
      "value": 1,
      "impact": 12,
      "interpretation": "Transaction from new country (first time)"
    },
    {
      "name": "device_trust_score",
      "value": 0.6,
      "impact": 8,
      "interpretation": "Device seen only 2 times before (medium trust)"
    }
  ],
  "rulesFired": ["VELOCITY_HIGH", "NEW_LOCATION"],
  "confidenceLevel": "HIGH"
}
```

**Rules Explanation (8+ Rules):**

| Rule | Trigger | Points | Explanation |
|------|---------|--------|-------------|
| VELOCITY_EXTREME | > 20 txns/hour | +50 | Impossible human pace |
| VELOCITY_HIGH | > 10 txns/hour | +25 | Suspicious frequency |
| AMOUNT_SPIKE_5X | amount > 5x avg | +40 | Extreme outlier |
| AMOUNT_SPIKE_2X | amount > 2x avg | +20 | Significant jump |
| IMPOSSIBLE_TRAVEL | 500km in 2h | +50 | Physically impossible |
| NEW_LOCATION | Different country | +15 | Geographic anomaly |
| NEW_DEVICE | First use | +10 | New device trust issue |
| RETURNING_CUSTOMER | 30+ txns, clean | -5 | Reducing risk |
| NEW_USER | < 7 days old | +20 | Unproven customer |
| BLACKLIST_HIT | User blacklisted | +100 | Confirmed fraudster |

## Monitoring & Alerts (25+ Metrics)

### Key Metrics

| Metric | Type | Alert Threshold | Purpose |
|--------|------|-----------------|---------|
| `fraud_score_latency_ms` | Histogram (p99) | > 100ms | Scoring speed |
| `fraud_model_inference_latency_ms` | Histogram | > 80ms | ML model latency |
| `fraud_model_inference_errors` | Counter | > 5% = fallback | Model availability |
| `fraud_rules_evaluation_latency_ms` | Histogram | > 20ms | Rule engine speed |
| `fraud_decisions_total` | Counter (by decision) | N/A | Decision distribution |
| `fraud_decisions_approve_pct` | Gauge | < 50% = investigate | % approved (if too high: detection weak) |
| `fraud_decisions_block_pct` | Gauge | > 5% = investigate | % blocked (if too high: false positives) |
| `fraud_decisions_review_pct` | Gauge | N/A | Manual review rate |
| `fraud_alerts_triggered_total` | Counter | N/A | Alerts sent to compliance |
| `fraud_false_positive_rate` | Gauge | > 10% = retrain | ML model accuracy |
| `fraud_false_negative_rate` | Gauge | > 2% = SEV-1 | Missed fraud |
| `transaction_whitelist_hits` | Counter | N/A | Auto-approved transactions |
| `transaction_blacklist_hits` | Counter | N/A | Auto-blocked transactions |
| `transaction_watchlist_hits` | Counter | N/A | Monitored transactions |
| `user_risk_profile_updates` | Counter | N/A | Profile changes |
| `velocity_check_violations` | Counter | N/A | Velocity spike count |
| `geographic_anomaly_flags` | Counter | N/A | New location flags |
| `device_anomaly_flags` | Counter | N/A | New device flags |
| `user_creation_latency_ms` | Histogram | > 100ms | Risk profile init |
| `fraud_flag_cache_hit_rate` | Gauge | < 95% = investigate | Whitelist/blacklist cache |
| `transaction_history_cache_hit_rate` | Gauge | < 90% = investigate | Velocity check cache |
| `payment_service_integration_errors` | Counter | > 0 = investigate | Payment service sync |
| `wallet_service_integration_errors` | Counter | > 0 = investigate | Wallet blocking sync |
| `db_connection_pool_active` | Gauge | > 27/30 = alert | Connection pool usage |
| `http_5xx_errors_rate` | Gauge | > 1% = alert | Server errors |

### Alerting Rules

```yaml
alerts:
  - name: FraudScoringLatencySlow
    condition: histogram_quantile(0.99, fraud_score_latency_ms) > 100
    duration: 5m
    severity: SEV-2
    action: Check if ML model slow; may degrade to rule engine only

  - name: FraudFalsePositiveSpike
    condition: fraud_false_positive_rate > 0.10
    duration: 1h
    severity: SEV-2
    action: Review model feature weights; may be too aggressive

  - name: FraudFalseNegativeDetected
    condition: fraud_false_negative_rate > 0.02
    duration: 1h
    severity: SEV-1
    action: Page on-call; fraudsters slipping through; emergency retraining

  - name: BlacklistCacheStale
    condition: fraud_flag_cache_hit_rate < 0.95
    duration: 10m
    severity: SEV-3
    action: Check Redis cache; may have stale blacklist entries

  - name: MLModelDown
    condition: rate(fraud_model_inference_errors[5m]) > 0.05
    duration: 5m
    severity: SEV-2
    action: Model server offline; fallback to rule engine; retraining blocked
```

## Security Considerations

### Threat Mitigations

1. **ML Model Poisoning Prevention**: Model retrained weekly with validation set holdout
2. **Whitelist Abuse Prevention**: Only SUPER_ADMIN can modify (audit log required)
3. **Blacklist Evasion Prevention**: Scoring based on device + IP + user profile (multi-factor)
4. **Transaction Tampering Prevention**: Score stored immutable in fraud_alerts table
5. **Data Privacy**: PII minimized (no card numbers, tokens only; device fingerprint hashed)

### Known Risks

- **Model Drift**: ML model accuracy degrades over time (mitigated by weekly retraining)
- **Whitelist Abuse**: Admin adds friend to whitelist (mitigated by audit log + manual review)
- **False Positives**: Legitimate users blocked (mitigated by manual review + appeals process)
- **False Negatives**: Fraud slips through (mitigated by daily false-negative detection job)

## Troubleshooting (8+ Scenarios)

### Scenario 1: Fraud Scoring Timeout (100ms SLA breached)

**Indicators:**
- `fraud_score_latency_ms` p99 > 200ms
- Requests timing out before payment completion
- Users see "Transaction declined - try again"

**Root Causes:**
1. ML model inference slow (overloaded)
2. Database query slow (risk profile lookup)
3. Cache miss on whitelist/blacklist
4. Network latency

**Resolution:**
```bash
# Check ML model performance
curl http://fraud-detection-service:8091/admin/model/stats

# Check database query time
SELECT query, mean_exec_time FROM pg_stat_statements
WHERE mean_exec_time > 50
ORDER BY mean_exec_time DESC;

# Increase cache TTL for whitelist/blacklist
FRAUD_WHITELIST_CACHE_TTL=600 (from 300)

# Scale fraud-service if CPU high
kubectl scale deployment fraud-detection-service --replicas=5
```

### Scenario 2: ML Model Returns Errors (Fallback to rule engine)

**Indicators:**
- `fraud_model_inference_errors` counter increasing
- Transactions taking longer (fallback + rules)
- Alert: "ML Model Down"

**Root Causes:**
1. Model server pod crashed
2. Out of memory (model too large)
3. Network timeout to model
4. Model config mismatch

**Resolution:**
```bash
# Check model server pod
kubectl get pods -n services | grep fraud-ml-model

# Check model server logs
kubectl logs -f deployment/fraud-detection-ml-server

# Restart model server if needed
kubectl rollout restart deployment/fraud-detection-ml-server

# Check model size and memory usage
curl http://fraud-detection-service:8091/admin/model/memory-usage
```

### Scenario 3: False Positive Rate Too High (Legitimate users blocked)

**Indicators:**
- `fraud_false_positive_rate` > 10%
- User complaints: "My transaction was blocked"
- Support tickets spike

**Root Causes:**
1. ML model too aggressive (low confidence threshold)
2. Rules threshold too high (score cutoff too low)
3. New user cohort unusual patterns (not in training data)
4. Feature drift (user behavior changed)

**Resolution:**
```bash
# Analyze false positives
SELECT COUNT(*), score_range, decision, actual_outcome
FROM fraud_alerts
WHERE false_positive = true
GROUP BY score_range, decision
ORDER BY COUNT(*) DESC;

# Adjust decision thresholds
FRAUD_DECISION_REVIEW_THRESHOLD=50 (from 35) -- More review, less blocks
FRAUD_DECISION_BLOCK_THRESHOLD=80 (from 60) -- Higher threshold to block

# Retrain model with new data
curl -X POST http://fraud-detection-service:8091/admin/model/retrain

# Whitelist new user cohort temporarily
-- INSERT INTO fraud_flags (target_id, flag_type, reason)
-- VALUES ('new_cohort_id', 'WHITELIST_COHORT', 'Wave 40 expansion users')
```

### Scenario 4: False Negative Rate Spiking (Fraud Slipping Through)

**Indicators:**
- `fraud_false_negative_rate` > 2%
- Fraud losses increasing
- Chargebacks spike

**Root Causes:**
1. ML model underfitting (not learning patterns)
2. Fraudsters found bypass (new technique)
3. Model retraining data too old
4. Rules outdated

**Resolution:**
```bash
# Emergency alert (SEV-1)
Immediate actions:
1. Lower decision thresholds (stricter blocking)
   FRAUD_DECISION_BLOCK_THRESHOLD=50 (from 80, temporary)
2. Revert to previous model version
   curl -X POST http://fraud-detection-service:8091/admin/model/rollback
3. Page on-call engineer for investigation

# Investigation
SELECT user_id, score, decision, actual_fraud FROM fraud_alerts
WHERE false_negative = true
ORDER BY created_at DESC
LIMIT 20;

# Manual rule adjustment
-- Add new rule for detected fraud pattern
-- IF (pattern matches) THEN +25 points

# Retraining with fraud cases
Run full model retraining with additional fraud examples
```

### Scenario 5: Whitelist/Blacklist Cache Stale

**Indicators:**
- `fraud_flag_cache_hit_rate` < 95%
- Whitelisted users sometimes get BLOCK decision
- Blacklist entries not taking effect immediately

**Root Causes:**
1. Cache TTL too long (old entries persist)
2. Cache invalidation failed
3. Redis down (fallback to DB queries, slow)
4. Concurrent write race condition

**Resolution:**
```bash
# Check Redis health
redis-cli ping

# Clear cache manually
redis-cli DEL fraud:whitelist:* fraud:blacklist:*

# Reduce cache TTL
FRAUD_WHITELIST_CACHE_TTL=60 (from 600, shorter)

# Verify recent updates applied
curl http://fraud-detection-service:8091/admin/flags/user-uuid/status
```

### Scenario 6: Database Connection Pool Exhausted

**Indicators:**
- `db_connection_pool_active` = 30/30
- Fraud scoring requests queue
- Latency spikes

**Root Causes:**
1. Risk profile queries slow (N+1 query)
2. Transaction history queries lock (too many rows)
3. Model inference holding connection open

**Resolution:**
```bash
# Check for slow queries
SELECT query, mean_exec_time FROM pg_stat_statements
WHERE mean_exec_time > 100
ORDER BY mean_exec_time DESC;

# Optimize indexes
CREATE INDEX ON user_risk_profile(user_id);
CREATE INDEX ON transaction_history(user_id, created_at DESC);

# Increase pool size
SPRING_DATASOURCE_HIKARI_POOL_SIZE=50 (from 30)

# Restart with new config
kubectl rollout restart deployment/fraud-detection-service
```

### Scenario 7: Integration Error with Wallet-Service (Blocking failures)

**Indicators:**
- `wallet_service_integration_errors` counter increasing
- High-risk transactions not blocked in wallet
- Alert: "Wallet integration failed"

**Root Causes:**
1. Wallet-service down or unreachable
2. Network policy blocks traffic
3. API contract changed (schema mismatch)

**Resolution:**
```bash
# Check wallet-service health
kubectl get pods -n services | grep wallet

# Test connectivity
curl http://wallet-loyalty-service:8090/actuator/health/live

# Check network policy
kubectl get networkpolicies -n services | grep wallet

# Verify API contract
curl -X POST http://wallet-loyalty-service:8090/wallet/block \
  -d '{"userId":"test","reason":"FRAUD_DETECTED"}'
```

### Scenario 8: Scoring Model Retraining Job Fails (Weekly update blocked)

**Indicators:**
- Model version hasn't updated in 7+ days
- Alert: "Model retraining failed"
- `fraud_model_accuracy` may degrade over time

**Root Causes:**
1. Training data pipeline failed
2. Model validation failed (new model worse than old)
3. ShedLock stuck (previous job didn't release)

**Resolution:**
```bash
# Check retraining job status
SELECT * FROM shedlock WHERE name = 'FraudModelRetrainingJob';

# Clear stuck lock if needed
DELETE FROM shedlock
WHERE name = 'FraudModelRetrainingJob'
AND lock_at_most_until < NOW();

# Manually trigger retraining
curl -X POST http://fraud-detection-service:8091/admin/model/retrain

# Check training data pipeline
SELECT COUNT(*) FROM training_data
WHERE created_at > NOW() - INTERVAL '7 days';
```

## Production Runbook Patterns

### Runbook 1: Emergency Fraud Alert Response

**Scenario:** Fraud losses spike to $10K in 1 hour

**SLA:** < 5 min detection to blocking

1. **Alert Received:** FraudFalseNegativeDetected alert
2. **Immediate Actions:**
   - Identify common patterns (new rule? bypass found?)
   - Lower decision thresholds: `FRAUD_DECISION_BLOCK_THRESHOLD=40` (from 80)
3. **Containment:**
   - Manually block high-risk IPs: Add to fraud_flags
   - Freeze affected user accounts (requires user confirmation)
4. **Investigation:**
   - Export fraud_alerts for last 1 hour
   - Identify fraud vector (veloc, geographic, device, etc.)
5. **Recovery:**
   - Revert thresholds once contained
   - File incident ticket for model retrain

## Advanced Fraud Detection Patterns

### ML Model Ensemble Architecture

**Model 1: Gradient Boosting (XGBoost)**
```
Purpose: Primary scorer (80% weight)
Features: 30 features (velocity, geography, device, profile)
Output: Risk score (0-100)
Accuracy: 92% precision on fraud detection
```

**Model 2: Anomaly Detection (Isolation Forest)**
```
Purpose: Detect novel patterns (15% weight)
Features: Unsupervised clustering on user transaction profiles
Output: Anomaly score (0-1)
Strength: Catches new fraud techniques
```

**Model 3: Graph Network (Neo4j)**
```
Purpose: Network fraud detection (5% weight)
Nodes: Users, devices, IPs, payment methods
Edges: Transactions, shared attributes
Detects: Fraud rings, organized schemes
Example: 5 users all from IP 10.0.0.1 = suspicious network
```

**Ensemble Combination**:
```
final_score = 0.80 * xgboost_score + 0.15 * isolation_forest_score + 0.05 * graph_score
Decision: If final_score > threshold → BLOCK/REVIEW
```

### Advanced Velocity Checks

**Temporal Patterns**:
```
Rule: Micro-burst (extreme velocity)
  IF transactions in 1-min window > 5: +50 points
  IF transactions in 5-min window > 20: +40 points
  IF transactions in 1-hour window > 100: +30 points

Rule: Velocity slope
  IF rate increases > 300% in 1 hour: +25 points
  Example: 1 txn/min → 4 txn/min spike

Rule: Off-hours transactions
  IF transaction at 3 AM + user normally sleeps: +15 points
  (Learn user's activity pattern)
```

**Location Patterns**:
```
Rule: Impossible travel
  IF distance > 500 km AND time_delta < 2 hours: +50 points
  IF distance > 1000 km AND time_delta < 6 hours: +40 points

Rule: Unfamiliar location
  IF country not in user's last 10 transactions: +15 points
  IF city > 200 km from usual location: +10 points

Rule: High-risk geography
  IF country in fraud_hotspot_list: +20 points
```

### Device Fingerprinting & Trust

**Device Trust Score Calculation**:
```
Components:
  1. Device age (how long known to system)
     - New device (< 7 days): 0.3 trust
     - Known device (> 30 days): 0.9 trust

  2. Device consistency
     - Same device across transactions: +0.2
     - Different devices same user: -0.1

  3. Hardware indicators
     - High-end device (recent phone): +0.1
     - Emulated/test device: -0.3

  4. Geographic consistency
     - Device location matches IP location: +0.1
     - Mismatch (VPN/proxy): -0.2

Final trust = AVG(components), capped [0, 1]

If trust < 0.4: Device flagged as suspicious, +20 fraud points
```

## Advanced Operational Patterns

### Fraud Ring Detection (Graph-Based)

**Algorithm**:
```
1. Identify suspicious transactions (score > 60)
2. Extract connected components:
   - Shared IP addresses
   - Shared payment methods
   - Shared device IDs
   - Shared email domains

3. Build subgraph for each component
4. Analyze structure:
   - If N users connected with 1 IP: Likely ring
   - If N users connected with 1 payment method: Likely ring
   - If N IPs, same device: Proxy farm

5. Ring confidence score = function(connectivity, pattern)
   If confidence > 0.8: Flag as organized fraud ring
```

**Example Detection**:
```
Scenario: 20 users, all from IP 203.0.113.45, all low-risk scores (25)
Analysis:
  - Individual scores: LOW (passed thresholds)
  - Network structure: SUSPICIOUS (all connected)
  - Ring confidence: HIGH (98%)

Result: BLOCK all 20 users, flag as fraud ring
```

### Whitelist Auto-Generation

**Auto-Whitelist Conditions**:
```
Trigger: 30 consecutive clean transactions
  - No chargebacks
  - No disputes
  - No manual reviews
  - All within normal velocity/amount

Action:
  - Add user to whitelist
  - All future scores capped at 10 (very low risk)
  - Bypass velocity checks entirely
  - Still log transactions for monitoring

Bypass:
  - If watchlist hit recently: Don't auto-whitelist
  - If risk profile changed significantly: Don't auto-whitelist
```

**Auto-Blacklist Conditions**:
```
Trigger: 2 confirmed fraud cases
  - User confirmed fraudster (manual review)
  - Multiple chargebacks
  - Reported by victims

Action:
  - Add to blacklist
  - All future transactions: score = 100 (block immediately)
  - TTL: 90 days default (can be permanent)
  - Auto-review at 90 days (manual decision to renew)

Appeal Process:
  - User can request whitelist removal after 60 days
  - Review with risk team
  - If legitimate: Transition to watchlist (30-day monitoring)
```

## Advanced Monitoring Patterns

### Real-Time Fraud Detection Dashboard

**Metrics displayed**:
```
1. Transaction Volume
   - Today: 50,000 transactions
   - Approved: 48,500 (97%)
   - Reviewed: 1,200 (2.4%)
   - Blocked: 300 (0.6%)

2. Risk Distribution
   - APPROVE (score 0-30): 48,500 (97%)
   - REVIEW (score 31-70): 1,200 (2.4%)
   - BLOCK (score 71-100): 300 (0.6%)

3. Fraud Detection
   - Confirmed fraud (this week): 45 users
   - Fraud ring (this month): 3 rings (180 users)
   - Chargebacks: 23 (0.046%)

4. Model Performance
   - Precision: 96%
   - Recall: 91%
   - F1 Score: 93%
   - False positive rate: 3%
   - False negative rate: 8%
```

### Operational Health Checks (Hourly)

**Health Check 1: Model Staleness**
```
IF model version not updated for > 8 days:
  Alert: SEV-2 "Model stale, accuracy may degrade"
  Action: Manual trigger retraining job

IF model inference latency > 100ms:
  Alert: SEV-2 "Model slow, fallback to rules"
  Action: Check ML model pod CPU/memory
```

**Health Check 2: Decision Distribution Anomalies**
```
IF APPROVE rate drops below 90%:
  Alert: SEV-3 "Approval rate low"
  Possible causes: Rules too strict, model drift

IF BLOCK rate exceeds 1%:
  Alert: SEV-3 "Block rate high"
  Possible causes: False positives increasing

IF REVIEW rate exceeds 5%:
  Alert: SEV-3 "Manual review backlog growing"
  Action: Adjust decision thresholds
```

## Production Runbook Patterns

### Runbook 1: Fraud Epidemic Response (10x Normal Fraud Rate)

**Scenario**: Sudden spike in fraud (0.6% → 6%)

**Detection** (< 1 min):
- Alert: fraud_false_negative_rate spike
- Dashboard: Fraud transactions increasing

**Immediate Action** (< 5 min):
```bash
# Step 1: Lower decision thresholds (stricter blocking)
FRAUD_DECISION_BLOCK_THRESHOLD=40  # from 75 (block more aggressively)

# Step 2: Restart service
kubectl rollout restart deployment/fraud-detection-service

# Step 3: Monitor impact
# Approval rate should drop (more blocked)
# False positive rate will increase (acceptable in emergency)

# Step 4: Parallel investigation
# Identify common pattern (new IP? device? category?)
```

**Investigation** (5-30 min):
```bash
# Analyze recent frauds
SELECT COUNT(*) as count,
       ip_address, device_id, country, category
FROM fraud_alerts
WHERE created_at > NOW() - INTERVAL '1 hour'
  AND decision = 'BLOCK'
GROUP BY ip_address, device_id, country, category
ORDER BY count DESC;

# Example output: 500 frauds all from IP 203.0.113.45, category electronics
```

**Containment** (30-60 min):
```bash
# Add fraud ring to blacklist
INSERT INTO fraud_flags (flag_type, target_id, target_type, reason, expires_at)
SELECT 'BLACKLIST_USER', user_id, 'USER', 'Fraud ring - IP 203.0.113.45', NOW() + '90 days'
FROM users
WHERE last_ip = '203.0.113.45'
  AND created_at > NOW() - '24 hours';

# Add IP to blacklist
INSERT INTO fraud_flags (flag_type, target_id, target_type, reason)
VALUES ('BLACKLIST_IP', '203.0.113.45', 'IP', 'Proxy farm detected');

# Expected: Fraud rate drops back to normal
```

**Post-Incident** (Next day):
- Review fraud pattern
- Update ML model features (add IP-based detection)
- Retrain model with new examples
- Document incident for future reference

### Runbook 2: False Positive Crisis (4% False Positive Rate)

**Scenario**: Legitimate customers blocked at checkout

**Detection** (< 10 min):
- Alert: fraud_false_positive_rate > 3%
- Support tickets spike: "Transaction declined unfairly"

**Impact Assessment**:
```bash
# Find affected users
SELECT COUNT(DISTINCT user_id) as blocked_users,
       AVG(score) as avg_fraud_score,
       MAX(score) as max_fraud_score
FROM fraud_alerts
WHERE decision = 'BLOCK'
  AND false_positive = true
  AND created_at > NOW() - INTERVAL '2 hours';

# Example: 500 legitimate users blocked
# Revenue impact: ~$50K (assuming avg order $100)
```

**Immediate Mitigation** (< 15 min):
```bash
# Adjust decision thresholds (less aggressive)
FRAUD_DECISION_BLOCK_THRESHOLD=85     # from 40 (block fewer)
FRAUD_DECISION_REVIEW_THRESHOLD=70    # from 55 (review fewer)

# Expected: More transactions approved, false positive rate drops

# Notify affected users
# Option 1: Auto-retry their blocked transactions
# Option 2: Send notification: "Try checkout again"
```

**Root Cause Analysis** (1-2 hours):
```bash
# Identify what changed
# Was it:
#  1. New rule too strict?
#  2. Model retraining with bad data?
#  3. Feature distribution shift (new user cohort)?

# Find pattern in false positives
SELECT rule_fired, COUNT(*)
FROM fraud_alerts
WHERE false_positive = true
GROUP BY rule_fired
ORDER BY COUNT DESC;

# Example: VELOCITY_HIGH rule firing for legitimate new users
```

**Long-term Fix** (< 24 hours):
```bash
# Update rules to be less aggressive
# Example: Adjust VELOCITY_HIGH from > 10 txns/hour to > 20 txns/hour

# Retrain model with fixed dataset
# Remove false positive examples from training data

# A/B test new model on 10% traffic before full rollout
```

## Testing & Validation

### Integration Test Scenarios (15+)

**Test 1: Whitelist Override**
```
Setup: User in whitelist
Input: Transaction with very high score (95)
Expected: APPROVE (whitelist overrides everything)
```

**Test 2: Blacklist Hit**
```
Setup: User in blacklist
Input: Low-score transaction (5)
Expected: BLOCK (blacklist overrides everything)
```

**Test 3: Velocity Check**
```
Setup: User with 50 transactions in last hour
Input: New transaction
Expected: BLOCK (velocity > 10 txns/hour = +25 points)
```

**Test 4: Impossible Travel**
```
Setup: User in NYC, last transaction 1 hour ago
Input: Transaction from Mumbai (12,000 km away)
Expected: BLOCK (impossible travel = +50 points)
```

**Test 5: New User**
```
Setup: Account created 2 days ago
Input: Any transaction
Expected: Score >= 20 (new user bias)
```

**Tests 6-15**: Additional scenarios for edge cases, fallbacks, etc.

## Related Documentation

- **ADR-011**: Fraud Scoring Model & Rules
- **ADR-012**: ML Model Ensemble Strategy
- **Runbook**: fraud-detection-service/runbook.md
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Fraud Ring Detection Algorithm](fraud-ring-detection.md)
- [Model Retraining Pipeline](ml-training-pipeline.md)
- [False Positive Reduction Strategy](false-positive-analysis.md)

## Configuration

```env
FRAUD_DECISION_APPROVE_THRESHOLD=30
FRAUD_DECISION_REVIEW_THRESHOLD=60
FRAUD_DECISION_BLOCK_THRESHOLD=75
FRAUD_WHITELIST_CACHE_TTL=300
FRAUD_BLACKLIST_CACHE_TTL=600
FRAUD_MODEL_TIMEOUT_MS=80
FRAUD_RULES_TIMEOUT_MS=20
```

## Known Limitations

1. No ML-based scoring (rule-based only) ✓ Addressed with TensorFlow Lite
2. No external fraud database integration (3rd-party lists)
3. No real-time graph analysis (user networks)
4. No time-series forecasting (trend analysis)

**Roadmap (Wave 41+):**
- Third-party fraud API integration (Sift Science, etc.)
- Device fingerprinting ML model
- Real-time graph queries (social network fraud)
- Trend forecasting (predict future fraud patterns)

