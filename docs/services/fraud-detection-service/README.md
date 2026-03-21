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

## Known Limitations

1. No ML-based scoring (rule-based only)
2. No external fraud database integration (3rd-party lists)
3. No real-time graph analysis (user networks)
4. No time-series forecasting

**Roadmap (Wave 34+):**
- ML models (isolation forest, neural networks)
- Third-party fraud API integration
- Device fingerprinting ML model
- Real-time graph queries (social network fraud)

