# Fraud Detection Service — Architecture & Code Review

**Service**: `fraud-detection-service`
**Platform**: Instacommerce Q-commerce (20M+ users)
**Stack**: Java 21, Spring Boot, PostgreSQL, Kafka, Caffeine cache
**Port**: 8095
**Reviewed files**: 47 Java files, 6 SQL migrations, application.yml, Dockerfile, build.gradle.kts

---

## Executive Summary

The fraud-detection-service provides real-time transaction scoring on the checkout critical path. It implements a rule-based scoring engine with five rule types (VELOCITY, AMOUNT, DEVICE, GEO, PATTERN), a blocklist subsystem, velocity counters, and Kafka-based event processing with transactional outbox. The service has a solid foundation but contains **several critical bugs, performance risks, and significant missing capabilities** that need to be addressed before it can safely protect a 20M+ user Q-commerce platform.

### Severity Breakdown

| Severity | Count | Summary |
|----------|-------|---------|
| 🔴 Critical | 7 | Race conditions, scoring algorithm flaws, cache staleness, missing fraud patterns |
| 🟠 High | 8 | Performance risks, missing integrations, schema gaps |
| 🟡 Medium | 9 | Missing features, operational gaps |
| 🟢 Low | 5 | Style/hardening improvements |

---

## 1. Scoring Algorithm

### How It Works

**File**: `FraudScoringService.java`

The scoring algorithm is an **additive sum** (not a weighted average). For each active rule that matches:

```
totalScore += rule.getScoreImpact()
```

The score is clamped to `[0, 100]`. Risk level is derived from the score via fixed thresholds in `RiskLevel.fromScore()`. The final action is the **maximum** of the risk-level-derived action and the highest rule-triggered action (via `FraudAction.escalate()`).

### 🔴 CRITICAL: Scoring is Additive Sum, Not Weighted

The scoring uses a naive additive model. If 3 rules each contribute `scoreImpact = 40`, the total is `120` → clamped to `100`. This means:
- **No normalization**: Score meaning changes as rules are added/removed.
- **No weighting**: A `VELOCITY` rule with `scoreImpact=30` and an `AMOUNT` rule with `scoreImpact=30` are treated identically regardless of confidence.
- **No configurable weights**: Weights are baked into each rule's `scoreImpact`. There is no system-level weight multiplier per rule type.

**Impact**: As the rule set grows, scores will saturate at 100 very quickly, making risk differentiation impossible.

**Recommendation**:
```java
// Option A: Normalized weighted sum
double weightedSum = 0;
double totalWeight = 0;
for (FraudRule rule : triggeredRules) {
    weightedSum += rule.getScoreImpact() * rule.getWeight();
    totalWeight += rule.getWeight();
}
int score = (int)(weightedSum / totalWeight);

// Option B: Max-based scoring with decay
int score = triggeredRules.stream()
    .mapToInt(FraudRule::getScoreImpact)
    .reduce(0, (a, b) -> Math.max(a, b) + b / 3);
```

### 🟠 HIGH: `ALLOW` Action Not in DB CHECK Constraint

`FraudAction` enum includes `ALLOW`, but `V1__create_fraud_rules.sql` line 13 constrains:
```sql
CONSTRAINT chk_action CHECK (action IN ('FLAG', 'BLOCK', 'REVIEW'))
```
`ALLOW` is omitted. If a rule is configured with `action = "ALLOW"` the DB would reject the insert. The `FraudScoringService` starts with `FraudAction.ALLOW` as the default, but this is the Java-side default — never persisted to `fraud_rules.action`. The `FraudSignal.action_taken` CHECK does include `ALLOW`. This is inconsistent but not currently broken because rules default to `FLAG`.

**Recommendation**: Add `'ALLOW'` to `fraud_rules.chk_action` constraint for consistency and to allow future "whitelist" rules.

### 🟠 HIGH: No Score Impact Validation

`FraudRuleRequest.scoreImpact` is a bare `int` — no `@Min`/`@Max` validation. An admin could set `scoreImpact = -500` or `scoreImpact = 99999`.

**Recommendation**: Add `@Min(0) @Max(100)` on `scoreImpact` in `FraudRuleRequest`.

---

## 2. Rule Engine

### How It Works

**File**: `RuleEvaluationService.java`

Five rule types dispatched via `switch`:

| Type | Logic | Condition JSON Keys |
|------|-------|-------------------|
| `VELOCITY` | Checks velocity counter ≥ threshold | `entityType`, `counterType`, `threshold` |
| `AMOUNT` | Checks `totalCents > maxAmountCents` | `maxAmountCents`, `newUsersOnly` |
| `DEVICE` | Checks device order count ≥ `maxAccountsPerDevice` | `maxAccountsPerDevice` |
| `GEO` | Haversine distance > `maxDistanceKm` | `maxDistanceKm`, `expectedLat`, `expectedLng` |
| `PATTERN` | Hardcoded sub-patterns: `HIGH_VALUE_NEW_USER`, `HIGH_ITEM_COUNT`, `RAPID_RETRY` | `pattern`, `maxItems`, `maxFailures` |

### 🔴 CRITICAL: JSONB Condition Parsing Has No Type Safety

All condition values are extracted via unchecked casts:
```java
int threshold = ((Number) condition.getOrDefault("threshold", 10)).intValue();
boolean newUsersOnly = (boolean) condition.getOrDefault("newUsersOnly", false);
```

If an admin enters `"threshold": "ten"` in the JSONB, this throws a `ClassCastException` at runtime during scoring — on the **checkout critical path**. There is no schema validation on `conditionJson` when rules are created/updated.

**Impact**: A single malformed rule brings down fraud scoring for ALL transactions until the rule is fixed.

**Recommendation**:
1. Add a `RuleConditionValidator` that validates the JSON shape per `ruleType` on create/update.
2. Wrap rule evaluation in a try-catch per rule so one bad rule doesn't kill the entire scoring pipeline:
```java
for (FraudRule rule : rules) {
    try {
        if (ruleEvaluationService.evaluateRule(rule, request)) { ... }
    } catch (Exception ex) {
        log.error("Rule {} failed evaluation, skipping: {}", rule.getName(), ex.getMessage());
        // Emit metric: fraud.rule.evaluation.error
    }
}
```

### 🟠 HIGH: GEO Rule Uses Static Expected Location

`evaluateGeo()` compares delivery location against `expectedLat`/`expectedLng` from the rule condition JSON. This is a **per-rule static location**, not a per-user baseline.

For Q-commerce (delivery to home/office), the expected behavior is: "flag if delivery address is far from user's known addresses." This requires a per-user location history lookup, not a global lat/lng in the rule definition.

**Recommendation**: GEO rules should compare against the user's historical delivery addresses (from user-service or a local cache), not a static point in rule config.

### 🟡 MEDIUM: DEVICE Rule Hardcodes Counter Type

`evaluateDevice()` hardcodes `"ORDERS_24H"` regardless of the rule's `conditionJson`:
```java
long count = velocityService.getCount("DEVICE", request.deviceFingerprint(), "ORDERS_24H");
```
The `maxAccountsPerDevice` condition key implies "accounts per device" but the counter actually counts "orders per device in 24h" — these are semantically different. This is measuring device velocity, not device-to-account association.

**Recommendation**: Rename the condition key or implement a true accounts-per-device counter.

### 🟡 MEDIUM: PATTERN Rules Are Hardcoded

New patterns require a code change and redeployment. The `evaluatePattern()` switch only handles 3 patterns. There is no extensibility mechanism.

**Recommendation**: Consider a lightweight expression language (e.g., SpEL or MVEL) for pattern conditions, or a plugin system for custom evaluators.

---

## 3. Velocity Counters

### How It Works

**File**: `VelocityService.java`

Counters are stored in PostgreSQL with fixed time windows. Windows are aligned to hour boundaries via `Instant.now().truncatedTo(ChronoUnit.HOURS)`.

Counter types: `ORDERS_1H`, `ORDERS_24H`, `AMOUNT_24H`, `FAILED_PAYMENTS_1H`.

Counters are incremented by Kafka consumers (`OrderEventConsumer`, `PaymentEventConsumer`) and read during scoring.

### 🔴 CRITICAL: Sliding Window Is Actually a Tumbling Window

The window is **truncated to the hour boundary**:
```java
Instant windowStart = now.truncatedTo(ChronoUnit.HOURS);
Instant windowEnd = windowStart.plus(window);
```

For `ORDERS_1H`: at 14:59 a user places 9 orders. At 15:01 they place order #10. The counter resets to 1 at 15:00 because a new window `[15:00, 16:00)` starts. The velocity rule sees count=1, not count=10.

**Impact**: Fraudsters can evade velocity checks by timing orders around hour boundaries. For a Q-commerce platform with 10-minute delivery windows, this is easily exploitable.

**Recommendation**: Implement a true sliding window using either:
1. **Redis sorted sets** with `ZRANGEBYSCORE` for O(1) sliding window counts
2. **PostgreSQL SUM across overlapping windows**: query all windows that overlap `[now - duration, now]`
3. **Event-sourced counters**: store individual events and count on read

### 🔴 CRITICAL: Race Condition on Counter Increment

`incrementCounter()` does a read-then-write without pessimistic locking:
```java
velocityCounterRepository
    .findByEntityTypeAndEntityIdAndCounterTypeAndWindowContaining(...)
    .ifPresentOrElse(
        counter -> { counter.setCounterValue(counter.getCounterValue() + 1); ... },
        () -> { /* create new */ }
    );
```

Two concurrent Kafka consumer threads processing events for the same user can:
1. Both read `counterValue = 5`
2. Both write `counterValue = 6`
3. Lost update: actual count should be 7

For the `ifPresentOrElse` empty branch, two threads can both find no counter and both try to insert, violating the `uq_velocity_window` unique constraint → exception thrown, event processing fails.

**Impact**: Under-counting allows fraudsters to exceed velocity limits. Constraint violations cause Kafka consumer failures.

**Recommendation**:
```sql
-- Use PostgreSQL UPSERT for atomic increment
INSERT INTO velocity_counters (entity_type, entity_id, counter_type, counter_value, window_start, window_end)
VALUES (?, ?, ?, 1, ?, ?)
ON CONFLICT ON CONSTRAINT uq_velocity_window
DO UPDATE SET counter_value = velocity_counters.counter_value + 1;
```
Or use `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the repository query. Better still, use Redis `INCR` which is atomic.

### 🟠 HIGH: No Velocity Counter Cleanup

Old velocity counter rows are never deleted. The `velocity_counters` table will grow unboundedly — with 20M users and 4 counter types, this means millions of expired rows per day.

**Recommendation**: Add a scheduled job (like `OutboxCleanupJob`) to delete counters where `window_end < now()`.

### 🟡 MEDIUM: 24H Window Aligned to Hour Creates 24 Rows

For `ORDERS_24H`, `windowStart = truncatedTo(HOURS)` and `windowEnd = windowStart + 24h`. But `getCount()` only queries the window containing `now`. If a user ordered at 10:00 (window `[10:00, next day 10:00)`) and the query runs at 11:00, it finds that window. But if the user also ordered at 11:00 (window `[11:00, next day 11:00)`), the 10:00 order is in a **different** window.

**Impact**: The 24H counter only counts events within the **same hour-aligned 24H block**, not a true rolling 24H. Counts will be lower than actual.

---

## 4. Blocklist Management

### How It Works

**File**: `BlocklistService.java`

Supports blocking by: `USER`, `DEVICE`, `IP`, `PHONE` (per DB constraint, though `PHONE` is not checked in `isAnyEntityBlocked()`).

Features:
- ✅ Expiry support (`expiresAt` field)
- ✅ Cache-backed lookups (Caffeine, 60s TTL)
- ✅ Block/unblock with audit trail (`blockedBy`, `reason`)
- ✅ Deactivates existing blocks before creating new ones
- ✅ Paginated listing of active blocks

### 🟠 HIGH: Phone Blocklist Never Checked

`FraudCheckRequest` has no `phone` field. The DB supports `PHONE` entity type, but `isAnyEntityBlocked()` only checks `USER`, `DEVICE`, and `IP`. Phone-based blocks are dead code.

**Recommendation**: Add `phone` to `FraudCheckRequest` and check it in `isAnyEntityBlocked()`.

### 🟡 MEDIUM: Expired Blocks Still Cached as "Blocked"

`isBlocked()` checks expiry at read time:
```java
if (blocked.getExpiresAt() != null && blocked.getExpiresAt().isBefore(Instant.now())) {
    return false;
}
```
But the `@Cacheable` annotation caches the **result** (true/false) for 60 seconds. The expiry check runs against the cached entity. This is fine — except the entity itself is cached, so if the cached `BlockedEntity` has `expiresAt` in the future when cached but expires 10 seconds later, the cache still returns `true` for up to 50 more seconds.

However, the Caffeine cache TTL is 60s, so the maximum staleness is 60 seconds. For a fraud system, **60 seconds of stale block status is acceptable** but should be documented.

**Deeper issue**: When a block **expires** naturally (not via `unblock()`), the cache entry is never explicitly evicted. The `@CacheEvict` only fires on `block()` and `unblock()` calls. An expired block will continue to be served from cache as `true` until the 60s TTL expires and the DB is re-queried (where the expiry check will return `false`). This is correct behavior but should be noted.

### 🟡 MEDIUM: No Bulk Import Endpoint

No bulk blocklist import (e.g., CSV of compromised device IDs from fraud intelligence feeds). Each block requires an individual `POST /admin/fraud/blocklist` call.

**Recommendation**: Add `POST /admin/fraud/blocklist/bulk` accepting a list of `BlockRequest` objects.

### 🟡 MEDIUM: Unique Constraint on `(entity_type, entity_value, active)`

```sql
CONSTRAINT uq_blocked_entity UNIQUE (entity_type, entity_value, active)
```
This means you can have at most ONE active and ONE inactive block per entity. If an entity is blocked, unblocked (active=false), then blocked again, the second unblock would create a second `active=false` row → **unique constraint violation**.

**Impact**: Multiple block/unblock cycles for the same entity will fail.

**Recommendation**: Remove `active` from the unique constraint or use a partial unique index:
```sql
CREATE UNIQUE INDEX uq_blocked_entity_active ON blocked_entities (entity_type, entity_value) WHERE active = true;
```

---

## 5. Risk Level Mapping

### How It Works

**File**: `RiskLevel.java`, `FraudAction.java`

| Score Range | Risk Level | Default Action |
|-------------|-----------|----------------|
| 0-25 | LOW | ALLOW |
| 26-50 | MEDIUM | FLAG |
| 51-75 | HIGH | REVIEW |
| 76-100 | CRITICAL | BLOCK |

### 🔴 CRITICAL: Thresholds Are Hardcoded in Enum

Risk level boundaries (25/50/75/100) are compile-time constants. Tuning requires a code change and redeployment.

**Impact**: Fraud ops cannot adjust sensitivity without engineering involvement. In a fast-moving fraud landscape, this can mean hours of delay.

**Recommendation**: Move thresholds to `application.yml` or a database-backed config table:
```yaml
fraud:
  risk-thresholds:
    low: 0-25
    medium: 26-50
    high: 51-75
    critical: 76-100
```

### 🟢 LOW: Action Escalation Logic Is Sound

`FraudAction.escalate()` correctly uses ordinal comparison to pick the more severe action. The dual-path (risk-level-derived + rule-specified) action computation ensures rules can override the score-based action upward but never downward. This is correct.

---

## 6. False Positive Handling

### 🔴 CRITICAL: No Manual Review Queue

When action is `REVIEW`, the fraud signal is persisted and an outbox event is published, but there is no:
- Review queue endpoint (no `GET /admin/fraud/reviews`)
- Review decision endpoint (no `POST /admin/fraud/reviews/{id}/approve` or `/reject`)
- Status tracking on `FraudSignal` (no `reviewStatus`, `reviewedBy`, `reviewedAt` fields)
- SLA tracking for review turnaround

The `REVIEW` action is effectively the same as `FLAG` — it's recorded but nobody can act on it.

**Recommendation**:
1. Add `review_status` (PENDING/APPROVED/REJECTED), `reviewed_by`, `reviewed_at` columns to `fraud_signals`
2. Add admin endpoints for review queue management
3. Add an auto-escalation job: if review is pending > X minutes, escalate to BLOCK

### 🟠 HIGH: `/fraud/report` Auto-Blocks Without Verification

`FraudController.reportSuspiciousActivity()` immediately blocks the reported user:
```java
blocklistService.block("USER", request.userId().toString(), ...);
```

Any authenticated user can report any other user and get them **immediately blocked**. This is a griefing vector.

**Recommendation**:
- Reports should go to a review queue, not trigger immediate blocks
- Add rate limiting on reports per reporter
- Require a minimum report threshold (e.g., 3 independent reports) before auto-blocking

### 🟡 MEDIUM: No Appeal/Unblock Self-Service

Blocked users have no way to appeal. The only unblock path is via `DELETE /admin/fraud/blocklist/{type}/{value}` which requires ADMIN role.

**Recommendation**: Add a user-facing appeal endpoint that creates a review ticket.

---

## 7. Scoring Latency (SLA: p99 < 100ms)

### Current Hot Path Analysis

```
POST /fraud/score
  → isAnyEntityBlocked()         // 3 cache lookups (Caffeine) — ~0.1ms
  → loadActiveRules()            // Cached (Caffeine) — ~0.1ms
  → for each rule:
      evaluateRule()             // Per-rule evaluation
        → VELOCITY: DB query     // PostgreSQL round-trip ~2-5ms
        → AMOUNT: in-memory      // ~0.01ms
        → DEVICE: DB query       // PostgreSQL round-trip ~2-5ms
        → GEO: in-memory calc    // ~0.01ms
        → PATTERN: may hit DB    // 0-5ms
  → persistAndRespond()          // DB write (fraud_signal) + outbox write — ~5-10ms
```

### 🔴 CRITICAL: Velocity DB Queries Are Per-Rule, Not Batched

Each VELOCITY and DEVICE rule evaluation triggers a separate `SELECT` against `velocity_counters`. With 10 velocity rules, that's **10 sequential DB round-trips** on the checkout hot path.

**Estimated p99 with 10 velocity rules**: 10 × 5ms + 10ms (persist) = **60ms**. With 20 rules or DB pressure, this easily exceeds 100ms.

**Recommendation**:
1. **Batch velocity lookups**: Before rule evaluation, fetch ALL velocity counters for the user/device/IP in a single query, then evaluate rules in-memory.
2. **Redis-backed counters**: Migrate velocity counters to Redis for sub-millisecond reads.
3. **Async persistence**: Write `FraudSignal` and outbox event asynchronously (fire-and-forget) to remove the write from the hot path.

### 🟡 MEDIUM: `@Transactional` on Scoring Holds DB Connection

`scoreTransaction()` is `@Transactional`, which holds a HikariCP connection for the entire method duration (including all rule evaluations). With `maximum-pool-size: 20`, only 20 concurrent fraud checks can execute. At checkout peak, this will exhaust the pool.

**Recommendation**:
- Split read path (rule loading, velocity checks) from write path (signal persistence)
- Use `@Transactional` only around the `persistAndRespond()` call
- Or increase pool size, but that trades DB connections for throughput

---

## 8. Cache Strategy

### Current Setup

**File**: `CacheConfig.java`

| Cache | Max Size | TTL | Eviction |
|-------|----------|-----|----------|
| `fraudRules` | 1,000 entries | 5 minutes | Write-based |
| `blocklist` | 5,000 entries | 60 seconds | Write-based |

### 🟠 HIGH: Cache Invalidation Is Incomplete

`AdminFraudController.createRule()`, `updateRule()`, `deleteRule()` have `@CacheEvict(value = "fraudRules", allEntries = true)` — this is correct for single-instance deployments.

**But in a multi-instance deployment** (Kubernetes), cache eviction is **local only**. If instance A updates a rule, instance B still serves the stale cache for up to 5 minutes.

**Impact**: A rule update (e.g., disabling a false-positive-generating rule) takes up to 5 minutes to propagate across all instances. During this window, some instances block legitimate users while others don't.

**Recommendation**:
1. Replace Caffeine with **Redis** for shared caching across instances
2. Or publish a cache-invalidation event via Kafka when rules change
3. Or reduce `fraudRules` TTL to 30 seconds (trades latency for consistency)

### 🟢 LOW: Blocklist Cache Size May Be Insufficient

5,000 entries with 20M users. If 0.1% of users are blocked, that's 20,000 entities. Add devices and IPs and the cache will evict frequently, defeating its purpose.

**Recommendation**: Increase to 50,000 or use a Bloom filter for negative lookups.

---

## 9. Velocity Counter Performance

### 🟠 HIGH: PostgreSQL Is Wrong Store for Real-Time Counters

Velocity counters are on the **read AND write hot path**:
- **Read**: Every fraud score evaluation queries counters
- **Write**: Every order/payment Kafka event increments counters

PostgreSQL adds ~2-5ms per velocity read and requires a transaction per write. Under load (1000+ TPS checkout), this will:
- Create significant write amplification (WAL, indexes, MVCC)
- Cause lock contention on popular counters (same user ordering rapidly = same row)
- Increase replication lag on read replicas

**Recommendation**: Migrate to Redis:
```
INCR fraud:velocity:{entityType}:{entityId}:{counterType}
EXPIRE fraud:velocity:{entityType}:{entityId}:{counterType} {windowSeconds}
```
Redis `INCR` is atomic (no race conditions), O(1), and sub-millisecond.

### 🟡 MEDIUM: No Index Covering the Full Query

The velocity counter query:
```sql
WHERE entity_type = :entityType AND entity_id = :entityId
  AND counter_type = :counterType AND window_start <= :now AND window_end > :now
```
Existing index: `idx_velocity_entity ON (entity_type, entity_id)` — this only covers the first two columns. The query still needs to filter by `counter_type` and window range from the heap.

**Recommendation**:
```sql
CREATE INDEX idx_velocity_lookup ON velocity_counters (entity_type, entity_id, counter_type, window_start, window_end);
```

---

## 10. Concurrent Scoring & Thread Safety

### 🟠 HIGH: Scoring Is Thread-Safe but Not Concurrent-Efficient

`FraudScoringService.scoreTransaction()` is stateless (no mutable shared state). Rule evaluation uses only method-local variables. The `loadActiveRules()` cache returns a shared `List<FraudRule>` — this is safe because `FraudRule` fields are only read during scoring.

However, the **database contention** issues are the real bottleneck:
- All scoring calls share a 20-connection HikariCP pool
- Velocity counter reads/writes contend on the same rows
- `@Transactional` holds connections for the full method duration

Under a checkout spike (e.g., flash sale), the connection pool will saturate and requests will queue on `connection-timeout: 5000ms`, causing p99 to spike to **5+ seconds**.

**Recommendation**:
- Increase `maximum-pool-size` to 50+ for the scoring workload
- Split read-only velocity lookups to a read replica
- Move velocity counters to Redis to eliminate DB contention entirely

### 🟢 LOW: Kafka Consumers Are Single-Threaded by Default

Spring Kafka listeners default to `concurrency = 1`. With high event volume, a single consumer thread may lag behind, causing stale velocity counts.

**Recommendation**: Set `concurrency = 3` on `@KafkaListener` annotations for order and payment consumers.

---

## 11-16. Missing Features Assessment

### 11. 🟡 ML Scoring Integration — NOT IMPLEMENTED

No feature extraction pipeline, no ML model serving integration. The rule-based approach is the only scoring mechanism.

**What's needed**:
- Feature extraction: user tenure, order history, device trust score, payment method age, delivery address novelty
- Model serving: gRPC call to ML model service (e.g., TensorFlow Serving, SageMaker endpoint)
- Score blending: `finalScore = α × ruleScore + (1-α) × mlScore`
- Model versioning and A/B comparison
- Feature store for real-time feature lookup

**Priority**: HIGH — Rule-based systems alone have 5-10x higher false positive rates than hybrid approaches.

### 12. 🟡 Device Fingerprinting — RUDIMENTARY

`FraudCheckRequest.deviceFingerprint` is a simple string. There is no:
- Client-side SDK for collecting device signals (screen resolution, installed fonts, WebGL hash, battery status, etc.)
- Server-side fingerprint computation or validation
- Device trust scoring
- Fingerprint spoofing detection

**What's needed**:
- Client SDK (JS/mobile) for signal collection
- Server-side fingerprint normalization and hashing
- Device reputation database
- Cross-session device linking

### 13. 🟡 Graph Analysis — NOT IMPLEMENTED

No mechanism to detect:
- Multiple accounts sharing the same device/IP/phone/payment method
- Ring fraud (group of connected accounts doing coordinated fraud)
- Account takeover chains

**What's needed**:
- Graph database (Neo4j/Neptune) or graph queries on PostgreSQL
- Entity resolution: link users by shared attributes
- Community detection algorithms for fraud ring identification
- Real-time graph updates on new signals

### 14. 🟡 Chargeback Integration — NOT IMPLEMENTED

No feedback loop from payment disputes/chargebacks. The system cannot:
- Learn from confirmed fraud (chargebacks)
- Auto-tune rule thresholds based on chargeback rates
- Escalate users with chargeback history

**What's needed**:
- Kafka consumer for `payment.chargebacks` topic
- Update `FraudSignal` with chargeback outcome
- Auto-adjust `scoreImpact` for rules that caught (or missed) chargebacks
- Chargeback rate metrics per rule for rule effectiveness tracking

### 15. 🟡 Fraud Analytics Dashboard — NOT IMPLEMENTED

No dashboard endpoints. No metrics beyond basic logging.

**What's needed**:
- `GET /admin/fraud/analytics/overview` — fraud rate, block rate, review rate over time
- `GET /admin/fraud/analytics/rules` — top triggered rules, false positive rate per rule
- `GET /admin/fraud/analytics/velocity` — velocity patterns, anomalies
- Time-series data in PostgreSQL or export to analytics warehouse
- Grafana dashboards for real-time monitoring

### 16. 🟡 A/B Testing / Shadow Mode — NOT IMPLEMENTED

No way to test new rules in shadow mode (evaluate but don't enforce). This means every new rule change is a production experiment.

**What's needed**:
- `shadow` flag on `FraudRule` — evaluate and log but don't include in scoring
- `experiment_group` field for A/B testing different rule sets
- Comparison dashboard: shadow rule would-have-blocked vs actual outcomes

---

## Q-Commerce Fraud Pattern Coverage

| Fraud Pattern | Current Coverage | Gap |
|---------------|-----------------|-----|
| **Account Takeover** | 🟡 Partial — GEO rule detects location change, velocity detects unusual order rate | No session anomaly detection, no impossible travel, no device change alerting |
| **New Account Fraud** | 🟡 Partial — `HIGH_VALUE_NEW_USER` pattern, `newUsersOnly` flag on AMOUNT rules | No referral abuse detection, no account age-based scoring, no signup velocity |
| **Return Fraud** | 🔴 None | No return/refund event consumption, no claim-not-delivered tracking |
| **Promotion Abuse** | 🔴 None | No coupon/promo code tracking, no multi-account promotion detection |
| **Payment Fraud** | 🟡 Partial — `FAILED_PAYMENTS_1H` velocity, amount thresholds | No BIN/card fingerprinting, no chargeback feedback, no 3DS risk scoring |

### Critical Gaps for Q-Commerce:

1. **No return/refund fraud detection**: Q-commerce platforms see high rates of "item not delivered" claims. Need to consume return/refund events and track claim rates per user.

2. **No promotion abuse detection**: Multiple accounts using the same device/IP/phone for referral bonuses or first-order discounts. The DEVICE velocity rule partially covers this, but there's no promo-specific logic.

3. **No delivery address clustering**: Multiple accounts delivering to the same address is a strong fraud signal in Q-commerce. Not implemented.

4. **No impossible travel detection**: User orders from Mumbai at 14:00 and from Delhi at 14:15. The GEO rule compares against a static point, not the user's last known location.

---

## Database Schema Issues

### 🟠 HIGH: `fraud_signals` Table Will Grow Unboundedly

No partition strategy, no archival, no retention policy. With 20M users and multiple daily orders, this table will reach hundreds of millions of rows within months.

**Recommendation**:
- Add range partitioning by `created_at` (monthly)
- Add a retention/archival job
- Create a `fraud_signals_archive` table or export to cold storage

### 🟡 MEDIUM: Missing Indexes

- `fraud_signals`: No index on `order_id`. Lookups by order (from order-service) will be slow.
- `fraud_signals`: No index on `action_taken` for review queue queries.
- `velocity_counters`: Index doesn't cover full query (see section 9).

**Recommendation**:
```sql
CREATE INDEX idx_fraud_signals_order ON fraud_signals (order_id);
CREATE INDEX idx_fraud_signals_review ON fraud_signals (action_taken, created_at) WHERE action_taken = 'REVIEW';
```

---

## Security Observations

### 🟢 Solid Security Posture

- ✅ JWT-based authentication with RSA public key validation
- ✅ Role-based access control (ADMIN for management endpoints)
- ✅ CORS configuration
- ✅ Stateless sessions
- ✅ Actuator endpoints properly excluded from auth
- ✅ Non-root container user in Dockerfile
- ✅ Secret Manager integration for credentials

### 🟡 MEDIUM: No Rate Limiting on `/fraud/score`

The scoring endpoint has no rate limiting. A malicious internal service could flood it, exhausting DB connections and affecting legitimate traffic.

**Recommendation**: Add rate limiting via Spring Cloud Gateway or a request interceptor.

### 🟡 MEDIUM: No Idempotency on `/fraud/score`

If the checkout service retries a failed fraud check, a second `FraudSignal` is created for the same order. There is no `orderId` uniqueness check.

**Recommendation**: Add a unique constraint on `fraud_signals.order_id` or implement idempotency via `X-Idempotency-Key` header (already allowed in CORS config but not implemented).

---

## Operational Concerns

### 🟢 GOOD: Outbox Pattern

The transactional outbox pattern (`OutboxEvent` + `OutboxCleanupJob`) ensures at-least-once event delivery without dual-write problems. The ShedLock-based cleanup job prevents duplicate cleanup across instances.

**Note**: There is no outbox **publisher** (CDC connector or polling publisher) visible in the codebase. The `sent` flag is never set to `true` by any code in this service. Either an external CDC tool (Debezium) is reading the outbox table, or the publisher is missing.

### 🟢 GOOD: Kafka Error Handling

Dead-letter topic routing with 3 retries at 1-second intervals. Failed events are preserved for investigation.

### 🟡 MEDIUM: No Circuit Breaker

If PostgreSQL becomes slow/unavailable, all scoring calls will hang until `connection-timeout` (5s). No circuit breaker to fast-fail and return a default "ALLOW" or "FLAG" decision.

**Recommendation**: Add Resilience4j circuit breaker with a fallback scoring strategy.

---

## Prioritized Action Plan

### Phase 1 — Critical Fixes (Week 1-2)
| # | Item | Severity | Effort |
|---|------|----------|--------|
| 1 | Fix velocity counter race condition (PostgreSQL UPSERT) | 🔴 Critical | 2h |
| 2 | Add per-rule try-catch in scoring loop | 🔴 Critical | 1h |
| 3 | Add JSONB condition validation on rule create/update | 🔴 Critical | 4h |
| 4 | Fix blocked_entities unique constraint | 🟠 High | 1h |
| 5 | Add scoreImpact validation (@Min/@Max) | 🟠 High | 30m |
| 6 | Add velocity counter cleanup job | 🟠 High | 2h |
| 7 | Fix `/fraud/report` to not auto-block | 🟠 High | 2h |

### Phase 2 — Performance (Week 2-4)
| # | Item | Severity | Effort |
|---|------|----------|--------|
| 8 | Batch velocity lookups (single query) | 🔴 Critical | 4h |
| 9 | Add covering index on velocity_counters | 🟡 Medium | 30m |
| 10 | Split @Transactional read/write path | 🟡 Medium | 3h |
| 11 | Evaluate Redis for velocity counters | 🟠 High | 1w |
| 12 | Add fraud_signals partitioning | 🟠 High | 1d |

### Phase 3 — Missing Features (Week 4-8)
| # | Item | Severity | Effort |
|---|------|----------|--------|
| 13 | Manual review queue endpoints | 🔴 Critical | 3d |
| 14 | Configurable risk thresholds | 🔴 Critical | 1d |
| 15 | Shadow mode for rules | 🟡 Medium | 2d |
| 16 | Bulk blocklist import | 🟡 Medium | 1d |
| 17 | Phone field in FraudCheckRequest | 🟠 High | 2h |
| 18 | Fraud analytics endpoints | 🟡 Medium | 1w |

### Phase 4 — Strategic (Quarter 2)
| # | Item | Severity | Effort |
|---|------|----------|--------|
| 19 | ML model integration | 🟡 Medium | 2-4w |
| 20 | Device fingerprinting SDK | 🟡 Medium | 2-3w |
| 21 | Chargeback feedback loop | 🟡 Medium | 1w |
| 22 | Graph-based fraud detection | 🟡 Medium | 3-4w |
| 23 | Return/refund fraud detection | 🟡 Medium | 1w |
| 24 | Promotion abuse detection | 🟡 Medium | 1w |

---

## Appendix: File Inventory

| Path | Purpose | Lines |
|------|---------|-------|
| `service/FraudScoringService.java` | Main scoring orchestrator | 127 |
| `service/RuleEvaluationService.java` | Rule evaluation per type | 138 |
| `service/VelocityService.java` | Velocity counter read/write | 93 |
| `service/BlocklistService.java` | Block/unblock management | 72 |
| `service/OutboxService.java` | Transactional outbox writes | 39 |
| `service/OutboxCleanupJob.java` | Scheduled outbox purge | 35 |
| `controller/FraudController.java` | Score + report endpoints | 44 |
| `controller/AdminFraudController.java` | Rule CRUD + blocklist admin | 113 |
| `domain/model/FraudRule.java` | Rule entity (JSONB conditions) | 140 |
| `domain/model/FraudSignal.java` | Scoring result entity | 134 |
| `domain/model/RiskLevel.java` | Score→risk mapping enum | 37 |
| `domain/model/FraudAction.java` | Action enum with escalation | 27 |
| `domain/model/VelocityCounter.java` | Counter entity | 93 |
| `domain/model/BlockedEntity.java` | Blocklist entity | 104 |
| `domain/model/OutboxEvent.java` | Outbox entity | 96 |
| `dto/request/FraudCheckRequest.java` | Scoring request DTO | 21 |
| `dto/request/FraudRuleRequest.java` | Rule CRUD DTO | 18 |
| `dto/request/FraudReportRequest.java` | Fraud report DTO | 15 |
| `dto/request/BlockRequest.java` | Block request DTO | 15 |
| `dto/response/FraudCheckResponse.java` | Scoring response DTO | 13 |
| `repository/FraudRuleRepository.java` | Rule queries | 13 |
| `repository/FraudSignalRepository.java` | Signal persistence | 8 |
| `repository/VelocityCounterRepository.java` | Counter queries | 21 |
| `repository/BlockedEntityRepository.java` | Blocklist queries | 21 |
| `repository/OutboxEventRepository.java` | Outbox queries | 16 |
| `config/CacheConfig.java` | Caffeine cache setup | 34 |
| `config/SecurityConfig.java` | Spring Security config | 64 |
| `config/KafkaConfig.java` | Kafka DLT error handler | 23 |
| `config/FraudProperties.java` | Config properties | 51 |
| `config/SchedulerConfig.java` | Enable scheduling | 9 |
| `config/ShedLockConfig.java` | Distributed lock config | 24 |
| `consumer/OrderEventConsumer.java` | Order event → velocity | 68 |
| `consumer/PaymentEventConsumer.java` | Payment event → velocity | 59 |
| `consumer/EventEnvelope.java` | Kafka event wrapper | 13 |
| `security/JwtAuthenticationFilter.java` | JWT filter | 78 |
| `security/DefaultJwtService.java` | JWT validation | 55 |
| `security/JwtKeyLoader.java` | RSA key parsing | 53 |
| `security/JwtService.java` | JWT interface | 12 |
| `security/RestAuthenticationEntryPoint.java` | 401 handler | 42 |
| `security/RestAccessDeniedHandler.java` | 403 handler | 40 |
| `exception/GlobalExceptionHandler.java` | Error handling | 92 |
| `exception/TraceIdProvider.java` | Trace ID resolution | 40 |
| `exception/ApiException.java` | Base exception | 23 |
| `exception/FraudRuleNotFoundException.java` | 404 rule | 11 |
| `exception/BlockedEntityNotFoundException.java` | 404 block | 10 |
| `V1-V6 migrations` | Schema DDL | ~55 |
| `application.yml` | Full config | 86 |
| `Dockerfile` | Multi-stage build | 22 |
| `build.gradle.kts` | Dependencies | 38 |

---

*Review completed. Total issues identified: 29 (7 critical, 8 high, 9 medium, 5 low).*
