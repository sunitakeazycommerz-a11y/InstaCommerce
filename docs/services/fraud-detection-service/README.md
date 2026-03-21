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

## Related Documentation

- **ADR-011**: Fraud Scoring Model & Rules
- **Runbook**: fraud-detection-service/runbook.md

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

