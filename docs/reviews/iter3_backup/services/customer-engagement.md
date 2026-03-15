# C6 — Customer & Engagement — Service-Wise Implementation Review

**Date:** 2026-03-08  
**Iteration:** 3 — Services  
**Scope:** `notification-service`, `wallet-loyalty-service`, `fraud-detection-service`  
**Cluster:** C6 — Customer & Engagement  
**Wave:** Wave 3  
**Status:** Implementation-ready

---

## 1. Executive Summary

The Customer & Engagement cluster handles three sensitive, business-critical capabilities: notification delivery (SMS/email/push), wallet/loyalty operations (cash balance + points), and fraud scoring (ML-assisted risk evaluation). These services are the trust interfaces between InstaCommerce and its customers — failures directly surface to users as duplicate notifications, incorrect balances, or blocked orders.

**Strengths worth preserving:**

1. **notification-service:** Database-backed deduplication via unique constraint on `(event_id, channel)`, Mustache template registry, user preference routing, GDPR erasure handling, DLQ for failed sends.
2. **wallet-loyalty-service:** Pessimistic locking for wallet mutations (`findByUserIdForUpdate`), double-entry ledger for audit, transactional outbox, referral reward system, loyalty tier auto-upgrade.
3. **fraud-detection-service:** Multi-stage pipeline (blocklist check, rule evaluation, velocity counters), Caffeine caching for rules/blocklist, admin-only rule/blocklist management, OpenTelemetry observability.

**Critical gaps that will cause production incidents:**

| ID | Title | Severity | Blast radius | Iter 2 ref |
|---|---|---|---|---|
| **C6-F1** | Notification deduplication relies on event_id from Kafka envelope, but event_id extraction has four fallback paths — inconsistent dedup semantics across producers | 🔴 Critical | Duplicate notifications reach users; SMS cost inflation | §4.9 |
| **C6-F2** | Wallet credit/debit uses pessimistic lock (`FOR UPDATE`), but loyalty points credit has no lock — concurrent `earnPoints` calls race | 🔴 Critical | Points double-credited; loyalty liability inflated | §4.9 |
| **C6-F3** | Fraud model scoring has no manual override or feature-flag kill switch — all decisions are automated | 🔴 Critical | Model bugs block legitimate orders with no human recourse | §4.10 |
| **C6-F4** | Wallet/loyalty outbox cleanup runs every 6 hours, but wallet-to-event lag can cause downstream consumers to miss cashback/points events if outbox row is deleted before relay | 🟠 High | Downstream analytics/ML miss financial events | §4.9 |
| **C6-F5** | Notification retry has exponential backoff (5s → 30s → 5min), but no circuit breaker — provider outage floods logs and blocks retry threads | 🟠 High | Retry queue exhaustion; operational overload | §4.9 |
| **C6-F6** | Fraud velocity counters use UPSERT on `(entity_type, entity_id, counter_type, window_start)`, but no cleanup job removes expired windows | 🟠 High | Velocity counter table unbounded growth | §4.10 |
| **C6-F7** | Wallet ledger double-entry creates two rows per transaction but has no integrity check — balance != ledger sum goes undetected | 🟠 High | Financial discrepancy undetectable until manual audit | §4.9 |
| **C6-F8** | Notification PII masking (`MaskingUtil`) masks phone/email in logs but stores plaintext in `notification_log.recipient` — GDPR exposure | 🟡 Medium | GDPR right-to-access surfaces plaintext PII | §4.9 |

---

## 2. Target State Definition

### 2.1 Notification Service — Privacy-Safe Delivery

#### Deduplication correctness

- **Current:** `DeduplicationService.createLog` constructs `eventId` using a four-tier fallback: `record.key()` → `envelope.id` → `aggregateId:eventType` → `eventType:partition:offset`. This is correct but undocumented, so producers may not understand that their choice of Kafka message key directly affects deduplication.
- **Target:** Enforce a canonical event ID strategy:
  1. All event producers MUST include `event_id` (UUID) in the event envelope per the `contracts/` standard.
  2. `DeduplicationService` logs a warning if `envelope.id` is missing and uses the fallback, but this is treated as a producer-side defect.
  3. CI contract validation rejects events without `event_id`.
- **Validation:** Run `grep -r 'envelope.id' contracts/` and confirm all schema examples include `event_id`. Test: publish an event without `event_id`, verify warning appears in logs.

#### Privacy-safe PII handling

- **Current:** `notification_log.recipient`, `notification_log.body`, `notification_log.subject` store plaintext until `UserErasureService.anonymizeByUserId` overwrites them with `[REDACTED]` after a GDPR erasure event. Between notification send and erasure, PII is queryable.
- **Target:**
  1. Store a salted SHA-256 hash of `recipient` in `notification_log.recipient_hash` for lookup/dedup; store actual recipient in a separate `pii_vault` table with TTL (90 days matching log retention).
  2. GDPR erasure deletes PII vault row immediately; `notification_log` retains hash for audit.
  3. For body/subject: encrypt with a per-tenant key stored in GCP Secret Manager, or store in `pii_vault` with FK; decrypt only when serving admin query (rare).
- **Rollout:** Requires schema migration + code change. Test with a GDPR erasure event, confirm PII is unretrievable.

#### Rate-limit abuse protection

- **Current:** No per-user send rate limit. A malicious or buggy producer could flood a user with notifications.
- **Target:** Introduce `notification_rate_limiter` table tracking sends per `(user_id, channel, window)`. Before send, check if user has exceeded configured threshold (e.g., 10 SMS/hour). Log throttled attempts; optionally alert.
- **Validation:** Trigger 15 order events for the same user in 1 minute. Verify 10 SMS sent, 5 throttled.

#### Circuit breaker for provider outages

- **Current:** `RetryableNotificationSender` retries indefinitely if provider throws `ProviderTemporaryException`. A 10-minute SendGrid outage will queue thousands of retries.
- **Target:** Wrap provider calls in Resilience4j CircuitBreaker. After N consecutive failures (e.g., 10), open circuit for 60s, fail fast. Transition to half-open, allow probe request, close if successful.
- **Configuration:** `notification.circuit-breaker.failure-rate-threshold: 50%`, `wait-duration-in-open-state: 60s`, `permitted-calls-in-half-open-state: 3`.
- **Validation:** Mock SendGrid to return 503 for 20 requests. Verify circuit opens after threshold, logs indicate fail-fast mode.

---

### 2.2 Wallet-Loyalty Service — Consistency & Idempotency

#### Loyalty points concurrency fix

- **Current:** `LoyaltyService.earnPoints` reads `LoyaltyAccount`, increments `pointsBalance`, saves. No lock. Two concurrent `OrderDelivered` events for the same order (Kafka retry + rebalance) can both read balance=1000, both write balance=1000+500=1500, losing one increment.
- **Target:**
  1. Add `@Version` column to `loyalty_accounts` for optimistic locking, OR
  2. Use pessimistic lock: `SELECT … FOR UPDATE` via `@Lock(LockModeType.PESSIMISTIC_WRITE)` in `LoyaltyAccountRepository.findByUserIdForUpdate`.
  3. Catch `OptimisticLockException` (if optimistic) and retry up to 3 times with exponential backoff.
- **Idempotency:** `LoyaltyTransaction` should store `reference_id` (e.g., order ID) and have a unique constraint on `(account_id, reference_type, reference_id)`. Duplicate event inserts are caught by DB constraint.
- **Validation:** Simulate concurrent `earnPoints(userId, orderId, 500)`. Verify balance incremented once, duplicate throws `DataIntegrityViolationException`, second call returns existing transaction.

#### Wallet ledger integrity check

- **Current:** `WalletLedgerService.recordTopUp` inserts two ledger entries (debit PAYMENT_GATEWAY, credit USER_WALLET) but no query verifies `SUM(ledger credits) - SUM(ledger debits) == wallet.balanceCents`.
- **Target:**
  1. Add `WalletLedgerService.verifyIntegrity(walletId)`: sums ledger, compares to `wallet.balanceCents`, logs discrepancy, optionally raises alert.
  2. Run as a scheduled job (`WalletIntegrityJob`) daily at 04:00 for all wallets with activity in last 7 days. Flag discrepancies to SRE dashboard.
  3. Instrument as a Prometheus gauge: `wallet_ledger_integrity_errors_count`.
- **Validation:** Manually corrupt a wallet balance. Run integrity check, verify error logged, metric incremented.

#### Outbox cleanup lag protection

- **Current:** `OutboxCleanupJob` deletes `sent = true` rows older than 7 days every 6 hours. If `outbox-relay-service` lags beyond 7 days, rows are deleted before relay, events lost.
- **Target:**
  1. Relay sets `sent_at` timestamp when successfully relayed. Cleanup deletes `sent = true AND sent_at < NOW() - INTERVAL '7 days'`.
  2. Monitor `MAX(created_at) - MAX(sent_at)` per service. Alert if lag > 1 hour.
  3. Configurable retention via `OUTBOX_RETENTION_DAYS` (default 7).
- **Validation:** Insert outbox row, set `sent = true` but no `sent_at`. Run cleanup after 7 days, verify row not deleted.

#### Referral fraud prevention

- **Current:** `ReferralService.redeemReferral` checks `code.active && uses < maxUses && referredUserId != code.userId`. A user can create N accounts, redeem their own code N times with different emails.
- **Target:**
  1. Track `referral_redemptions.ip_address` and `referral_redemptions.device_fingerprint`. Alert if same device redeems same code >1 time.
  2. Integrate with `fraud-detection-service`: score referral redemption as a transaction. If risk > threshold, hold reward pending manual review.
  3. Rate-limit referral generation: 1 per user per day.
- **Validation:** Attempt to redeem same code from same device twice. Verify second attempt flagged or rejected.

---

### 2.3 Fraud Detection Service — Model Governance & Override

#### Manual override & feature-flag kill switch

- **Current:** All fraud decisions are automated. If the ML model degrades (e.g., fraud scoring model over-triggers after training on bad data), there is no fast-path override.
- **Target:**
  1. Introduce `FraudOperationalMode` enum: `AUTO_BLOCK`, `MANUAL_REVIEW`, `SHADOW`, `PASS_THROUGH`.
  2. Mode controlled by feature flag: `fraud-detection-mode` in `config-feature-flag-service`.
  3. In `MANUAL_REVIEW` mode: scores are computed but `BLOCK` actions are downgraded to `REVIEW` and routed to a manual review queue (Kafka topic `fraud.manual-review`, consumed by admin dashboard).
  4. In `SHADOW` mode: scoring runs, signals are logged/published, but response always returns `ALLOW` (for A/B testing new models).
  5. In `PASS_THROUGH` mode: emergency kill switch — all transactions return `ALLOW, score=0` (for total model failure).
- **Configuration:**
  ```yaml
  fraud:
    operational-mode: AUTO_BLOCK  # or env: FRAUD_OPERATIONAL_MODE
  ```
- **Rollout:** Add mode to `FraudScoringService.scoreTransaction()` before action decision. Test each mode with known-fraud transaction.
- **Validation:** Set mode to `PASS_THROUGH`, submit high-risk transaction, verify response is `ALLOW`. Switch to `MANUAL_REVIEW`, verify action is `REVIEW`.

#### ML model versioning & rollback

- **Current:** Fraud rules are versioned via `fraud_rules` table with CRUD, but if a new rule set is deployed and degrades quality, rollback requires manual DB changes.
- **Target:**
  1. Fraud rules export/import: `POST /admin/fraud/rules/export` → JSON snapshot with timestamp. `POST /admin/fraud/rules/import` → bulk replace with snapshot (soft-delete old, insert new, retain history).
  2. Store snapshots in GCS: `gs://instacommerce-fraud-config/rules-YYYY-MM-DD-HH-MM-SS.json`.
  3. Rollback script: `./scripts/rollback-fraud-rules.sh <snapshot-timestamp>` calls import endpoint.
- **Validation:** Export rules, modify a rule to trigger on all transactions, verify degraded scoring. Import snapshot, verify original behavior restored.

#### Model explainability & audit trail

- **Current:** `FraudSignal` stores `rulesTriggered: JSONB`, but no detail on *why* each rule triggered (e.g., velocity counter value, threshold).
- **Target:**
  1. Store `rule_details: JSONB` in `fraud_signals` with per-rule evaluation metadata: `{"high-velocity": {"counter": 5, "threshold": 3, "window": "1h"}}`.
  2. Admin endpoint: `GET /admin/fraud/signals/:id/explain` returns human-readable explanation.
  3. Integrate with audit trail: every `BLOCK` or `REVIEW` action publishes to `audit.events` with full context.
- **Validation:** Submit transaction triggering velocity rule. Query signal, verify `rule_details` contains counter value. Call explain endpoint, verify readable response.

#### Velocity counter cleanup job

- **Current:** No cleanup. `velocity_counters` table grows indefinitely.
- **Target:** `VelocityCounterCleanupJob` (already exists per README) should run hourly (currently `0 15 * * * *`), delete rows where `window_end < NOW()`. Index on `window_end` for efficient deletion.
- **Validation:** Insert expired counter (window_end = NOW() - 2 hours). Run job, verify row deleted. Check index exists: `CREATE INDEX idx_velocity_counters_window_end ON velocity_counters(window_end);`.

#### Abuse handling — adaptive thresholds

- **Current:** Fraud rules have static thresholds (e.g., `maxCents: 50000`). An attacker can stay under thresholds indefinitely.
- **Target:**
  1. Introduce `adaptive-rule` type: threshold adjusts based on user history. E.g., `max_order_amount = mean(user_orders) + 2*stddev`. Recompute daily.
  2. Store per-user stats in `user_fraud_profile` table: `avg_order_value`, `order_count`, `last_order_at`, `flagged_count`.
  3. Rules reference profile: `conditionJson: {"profile_deviation_multiplier": 2.0}`.
- **Rollout:** Add profile table migration, populate via batch job from order history. Deploy adaptive rule engine. Start with `SHADOW` mode to tune thresholds.
- **Validation:** User with avg order ₹500 attempts ₹5000 order. Verify adaptive rule triggers (deviation > 2σ).

---

## 3. Security & Privacy Review

### 3.1 Notification Service PII Exposure

**Current risk:** `notification_log.recipient` stores phone/email plaintext. GDPR right-to-access query returns plaintext until erasure event processed.

**Mitigations:**

1. **Encryption at rest:** PostgreSQL Transparent Data Encryption (TDE) via CloudSQL is enabled, but this protects disk, not query results.
2. **Column-level encryption:** Encrypt `recipient`, `body`, `subject` with AES-256-GCM using application-managed key from GCP Secret Manager. Store IV per row. Decrypt only when admin queries logs (rare).
3. **PII vault separation:** Move PII to `notification_pii` table with FK to `notification_log`. Set TTL (90 days) with partition pruning. GDPR erasure deletes PII row immediately.
4. **Access control:** Restrict `SELECT` on `notification_log` to `notification_reader` role. Only admin service uses this role, logs all access to audit trail.

**Rollout sequence:**

1. Add `notification_pii` table, `recipient_hash` column to `notification_log`.
2. Dual-write: new notifications write to both tables.
3. Backfill existing logs: move PII to vault, compute hashes.
4. Update `UserErasureService` to delete from vault.
5. Deploy, monitor for 7 days, remove plaintext columns.

**Validation:**

- Query `notification_log` after deployment, verify `recipient` is hash.
- Trigger GDPR erasure, verify PII row deleted, log row retained with hash.
- Attempt to query PII vault without admin role, verify denied.

### 3.2 Wallet-Loyalty Financial Integrity

**Current risk:** Wallet balance is updated in-memory (`wallet.setBalanceCents(…)`) before ledger write. If ledger write fails (e.g., unique constraint violation, disk full), balance is wrong but transaction commits.

**Mitigations:**

1. **Transaction atomicity check:** Wrap `credit`/`debit` in `@Transactional(propagation = MANDATORY)`. If ledger write fails, entire transaction rolls back.
2. **Pre-commit hook:** Before committing, run `verifyIntegrity(walletId)` within same transaction. If ledger sum != balance, throw exception, rollback.
3. **Optimistic locking on wallet:** Already uses `@Version`. If concurrent update, retry.
4. **Immutable audit:** Ledger entries are append-only. Add `CHECK (amount_cents > 0)` constraint. Add `created_by` column (service name + trace ID).

**Rollout sequence:**

1. Add integrity check to `WalletService.credit/debit` (defensive check, logs only, does not block).
2. Monitor for discrepancies. If none in 7 days, promote to blocking check (throw exception).
3. Add daily batch integrity scan for all wallets, alert on mismatch.

**Validation:**

- Simulate ledger write failure (mock DB exception). Verify balance unchanged, transaction rolled back.
- Run integrity scan against production replica, verify zero discrepancies.

### 3.3 Fraud Service Admin Endpoint Protection

**Current risk:** `/admin/fraud/rules` and `/admin/fraud/blocklist` require `ROLE_ADMIN`. `InternalServiceAuthFilter` grants `ROLE_ADMIN` to every internal caller with `INTERNAL_SERVICE_TOKEN` (see security-trust-boundaries.md G3).

**Mitigations:**

1. **Remove shared-token admin grant:** See platform-wide ADR-002 (workload identity). Fraud service should validate caller's service identity and role via JWT claims.
2. **Admin-only endpoints:** Use `@PreAuthorize("hasRole('ADMIN') and @fraudAuthz.isHumanUser(authentication)")` to ensure only human admins (not service accounts) can modify rules/blocklist.
3. **Audit every change:** `AdminFraudController` publishes to `audit.events` with `actor`, `action`, `entity_type`, `entity_id`, `old_value`, `new_value`.
4. **Change approval workflow:** High-impact changes (delete rule, block user) require two-person approval. First admin submits request to `fraud_change_requests` table, second admin approves via separate endpoint.

**Rollout sequence:**

1. Add audit logging to all admin endpoints (P0).
2. Deploy workload identity (requires C1 completion).
3. Add approval workflow (P1).

**Validation:**

- Attempt to call `/admin/fraud/rules` with service token, verify rejected (after workload identity deployed).
- Modify a rule, verify audit event published with actor, old/new values.
- Submit delete-rule request, verify pending state, second admin approves, verify executed.

---

## 4. Observability & Validation

### 4.1 Metrics to Instrument

#### Notification Service

| Metric | Type | Labels | Threshold |
|---|---|---|---|
| `notification_sent_total` | Counter | `channel`, `event_type` | — |
| `notification_failed_total` | Counter | `channel`, `event_type`, `failure_reason` | Alert if > 5% of sent |
| `notification_skipped_total` | Counter | `channel`, `reason` (opted_out, missing_recipient, duplicate) | — |
| `notification_retry_attempts_total` | Counter | `channel`, `attempt` (1, 2, 3) | — |
| `notification_provider_latency_seconds` | Histogram | `channel`, `provider` | p99 < 5s |
| `notification_deduplication_hits_total` | Counter | `channel` | — |
| `notification_circuit_breaker_state` | Gauge | `provider`, `state` (closed, open, half_open) | Alert on open |

#### Wallet-Loyalty Service

| Metric | Type | Labels | Threshold |
|---|---|---|---|
| `wallet_credit_total` | Counter | `reference_type` | — |
| `wallet_debit_total` | Counter | `reference_type` | — |
| `wallet_balance_cents` | Gauge | `user_id` (cardinality risk — use histogram instead) | — |
| `wallet_insufficient_balance_total` | Counter | — | — |
| `loyalty_points_earned_total` | Counter | `tier` | — |
| `loyalty_points_redeemed_total` | Counter | `tier` | — |
| `loyalty_tier_upgrades_total` | Counter | `from_tier`, `to_tier` | — |
| `wallet_ledger_integrity_errors_total` | Counter | — | Alert if > 0 |
| `wallet_optimistic_lock_retries_total` | Counter | — | Alert if > 1% of operations |

#### Fraud Detection Service

| Metric | Type | Labels | Threshold |
|---|---|---|---|
| `fraud_score_total` | Counter | `risk_level`, `action` | — |
| `fraud_rule_triggered_total` | Counter | `rule_name` | — |
| `fraud_blocklist_hits_total` | Counter | `entity_type` | — |
| `fraud_velocity_counter_value` | Gauge | `entity_type`, `counter_type` | — |
| `fraud_scoring_latency_seconds` | Histogram | — | p99 < 200ms |
| `fraud_manual_review_queue_size` | Gauge | — | Alert if > 100 |
| `fraud_model_prediction_errors_total` | Counter | `model_type` | Alert if > 0 |

### 4.2 SLIs/SLOs

| Service | SLI | SLO | Measurement |
|---|---|---|---|
| notification-service | Notification delivery success rate | 99.5% | `(sent / (sent + failed)) >= 0.995` over 7 days |
| notification-service | Notification delivery latency | p99 < 10s (end-to-end event → send) | Histogram from event consumed to provider success |
| wallet-loyalty-service | Wallet mutation success rate | 99.9% | `(credit + debit success / total) >= 0.999` |
| wallet-loyalty-service | Wallet read latency | p99 < 100ms | Histogram for `/wallet/balance` |
| fraud-detection-service | Fraud scoring latency | p99 < 200ms | Histogram for `/fraud/score` |
| fraud-detection-service | Fraud scoring availability | 99.95% | Non-5xx response rate |

### 4.3 Alerting Rules

#### Critical (PagerDuty)

- `notification_failed_total / notification_sent_total > 0.05` for 5 minutes → SMS provider outage or config issue
- `wallet_ledger_integrity_errors_total > 0` → financial discrepancy, immediate investigation
- `fraud_scoring_latency_seconds p99 > 500ms` for 10 minutes → fraud service degraded, may block checkouts
- `fraud_manual_review_queue_size > 500` → review queue backlog, orders stuck

#### Warning (Slack)

- `notification_circuit_breaker_state == open` → provider outage, failover needed
- `wallet_optimistic_lock_retries_total / wallet_mutations_total > 0.01` → high contention, consider pessimistic locking
- `fraud_rule_triggered_total` (specific rule) spikes >10x baseline → rule misconfigured or attack in progress
- `loyalty_points_earned_total` drops to zero for 1 hour → event consumption broken

---

## 5. Rollout Plan

### 5.1 Sequencing

**Phase 1: Safety (P0, blocking for Wave 3 start)**

1. Add optimistic/pessimistic locking to `LoyaltyService.earnPoints` with idempotency constraint.
2. Add fraud operational mode feature flag (default: `AUTO_BLOCK`).
3. Add wallet ledger integrity check (log-only mode).
4. Add notification circuit breaker for SendGrid/Twilio.
5. Fix velocity counter cleanup job (already exists, verify it runs).

**Phase 2: Privacy (P1, before GDPR audit)**

1. Add `notification_pii` vault table, dual-write.
2. Backfill existing logs.
3. Update GDPR erasure to delete from vault.
4. Remove plaintext columns.

**Phase 3: Governance (P1, before ML rollout)**

1. Add fraud rule export/import endpoints.
2. Add fraud signal explainability (rule_details JSONB).
3. Add manual review queue (Kafka topic + admin dashboard).
4. Deploy model versioning & rollback script.

**Phase 4: Hardening (P2, post-launch)**

1. Add wallet outbox lag monitoring.
2. Add referral fraud prevention (device fingerprinting, rate limits).
3. Add adaptive fraud thresholds (user profile-based).
4. Add two-person approval for high-impact fraud admin actions.

### 5.2 Per-Service Deployment

#### Notification Service

1. **Pre-deploy:** Run `./gradlew :services:notification-service:test` locally, verify all tests pass.
2. **Schema migration:** `V4__add_notification_pii_vault.sql` creates `notification_pii`, adds `recipient_hash` column, FK constraint.
3. **Deploy canary (10% traffic):** Monitor `notification_failed_total`, `notification_deduplication_hits_total`. Roll forward if < 1% error rate delta.
4. **Deploy full:** Switch to 100% after 24h canary soak.
5. **Backfill:** Run `NotificationPiiBackfillJob` as one-time batch job (off-peak).
6. **Cleanup:** After 7 days, drop `recipient`, `body`, `subject` plaintext columns.

#### Wallet-Loyalty Service

1. **Pre-deploy:** Add integration test simulating concurrent `earnPoints` calls. Verify optimistic lock exception caught, retry succeeds.
2. **Schema migration:** `V4__add_loyalty_idempotency.sql` adds unique constraint on `loyalty_transactions(account_id, reference_type, reference_id)`, adds `version` column to `loyalty_accounts`.
3. **Deploy canary (10% traffic):** Monitor `wallet_optimistic_lock_retries_total`, `loyalty_points_earned_total`. Expect lock retries < 1% of operations.
4. **Deploy full:** After 24h canary soak.
5. **Integrity scan:** Run `WalletIntegrityJob` daily for 7 days. If zero discrepancies, promote to production schedule.

#### Fraud Detection Service

1. **Pre-deploy:** Add feature flag `fraud-detection-mode` to config service with default `AUTO_BLOCK`.
2. **Deploy operational modes:** Code change + config. Test each mode in dev: `AUTO_BLOCK` → normal, `PASS_THROUGH` → all allow, `MANUAL_REVIEW` → downgrade blocks.
3. **Deploy canary (10% traffic):** Monitor `fraud_scoring_latency_seconds`, `fraud_score_total`. Verify no regression.
4. **Deploy full:** After 24h canary soak.
5. **Admin endpoints:** Add audit logging. Deploy. Monitor audit events in `audit-trail-service`.
6. **Model rollback:** Create rule snapshot, test import, verify rollback script works.

### 5.3 Validation Checkpoints

After each deployment:

1. **Smoke test:** Call primary API endpoints (`POST /wallet/topup`, `POST /fraud/score`, verify 200 OK).
2. **Load test:** Run 1000 req/s for 5 minutes, verify p99 latency within SLO.
3. **Chaos test:** Kill one replica, verify traffic shifts, no errors.
4. **GDPR test:** Trigger erasure event, verify PII removed from all stores.
5. **Financial integrity test:** Run wallet integrity scan, verify zero discrepancies.

---

## 6. Rollback Strategy

### 6.1 Code Rollback

All services support zero-downtime rollback:

1. **Helm/ArgoCD:** `helm rollback notification-service -n instacommerce --revision <prev>`.
2. **Database migrations:** Use reversible migrations (Flyway). If schema change is breaking, deploy code + migration together as atomic unit via blue-green.
3. **Feature flags:** Toggle `fraud-detection-mode` to `PASS_THROUGH` (emergency kill switch) or `SHADOW` (downgrade to log-only).

### 6.2 Schema Rollback

| Migration | Forward | Rollback |
|---|---|---|
| `V4__add_notification_pii_vault.sql` | Create table + FK | Drop table, FK (safe, PII is backfilled, old code reads plaintext) |
| `V4__add_loyalty_idempotency.sql` | Add unique constraint + version | Drop constraint, column (safe, duplicate inserts fail gracefully) |
| `V5__add_fraud_rule_details.sql` | Add JSONB column | Drop column (safe, nullable, old code ignores) |

**Critical path:** If rollback required mid-migration (e.g., backfill failed), leave new schema in place but revert code. New columns are nullable/ignored by old code.

### 6.3 Data Rollback

**Wallet balance corruption:** If ledger integrity check detects discrepancy post-deployment:

1. Halt all wallet mutations (set fraud mode to `PASS_THROUGH`, disable checkout).
2. Export wallet + ledger state to GCS: `pg_dump --table=wallets --table=wallet_ledger_entries`.
3. Recompute balances from ledger: `UPDATE wallets SET balance_cents = (SELECT SUM…)`.
4. Verify integrity, re-enable.

**Notification PII leak:** If plaintext accidentally logged post-vault migration:

1. Redact logs: run log scrubbing job (GCP Logging API `entries.write` with redacted payload).
2. Audit who accessed logs (GCP Audit Logs for Logging API reads).
3. Report to privacy team.

---

## 7. Alternative Approaches & Tradeoffs

### 7.1 Notification Deduplication

**Considered:** Redis-based deduplication with TTL (e.g., `SET event_id:channel NX EX 86400`).

**Pros:** Lower latency (no DB write until send succeeds), scales horizontally.

**Cons:** Not durable — Redis restart loses dedup state, duplicate sends possible. No audit trail.

**Decision:** Database-backed deduplication is correct for financial/compliance notifications where "exactly once" is mandatory. Redis is acceptable for non-critical notifications (marketing). **Recommendation:** Keep DB for transactional notifications (order, payment, delivery), use Redis for promotional notifications.

### 7.2 Loyalty Points Locking

**Considered:** Event-sourced points ledger (append-only event log, balance derived from events).

**Pros:** No lock contention, natural audit trail, easy to replay.

**Cons:** Higher complexity, read path requires full event scan (mitigated by CQRS/snapshot).

**Decision:** Pessimistic locking with idempotency constraint is simpler and correct for current scale (< 10K orders/sec). **Recommendation:** Re-evaluate event sourcing if lock contention becomes bottleneck (monitor `wallet_optimistic_lock_retries_total`).

### 7.3 Fraud Model Governance

**Considered:** External feature store (Feast, Tecton) for feature serving + model registry (MLflow).

**Pros:** Industry-standard, built-in versioning, A/B testing, rollback.

**Cons:** Operational overhead (new services to manage), cost, migration effort.

**Decision:** Current rule-based system is sufficient for MVP. **Recommendation:** Migrate to managed feature store when fraud team scales to >3 models or when real-time feature freshness < 1s is required.

### 7.4 Manual Review Queue

**Considered:** In-service manual review (fraud service stores pending reviews, exposes admin API).

**Cons:** Tight coupling, fraud service must handle review lifecycle, retries.

**Decision:** Decouple via Kafka topic `fraud.manual-review`. Admin dashboard consumes, presents to reviewers, publishes decision to `fraud.review-decisions`. Fraud service subscribes, updates signal. **Recommendation:** Use Kafka to avoid blocking fraud service on human latency.

---

## 8. Dependencies & Cross-Cluster Coordination

### 8.1 Inbound Dependencies

| This cluster depends on | Reason | ADR |
|---|---|---|
| C1 (identity-service) | Notification service fetches user preferences/contact via REST | — |
| C2 (order-service, payment-service) | Wallet/fraud services consume order/payment events | ADR-004 (envelope) |
| C7 (config-feature-flag-service) | Fraud operational mode feature flag | ADR-010 (TTL) |
| C8 (outbox-relay-service, contracts) | All services publish outbox events, rely on relay | ADR-003, ADR-004 |

### 8.2 Outbound Dependencies (downstream consumers)

| This cluster produces | Consumed by | Contract |
|---|---|---|
| `notifications.dlq` | Manual ops (alert on depth) | — |
| `wallet.events` (WalletCredited, WalletDebited) | C9 (data-platform, analytics) | `contracts/schemas/wallet/` |
| `fraud.events` (FraudDetected) | C2 (order-service blocks on BLOCK action) | `contracts/schemas/fraud/FraudDetected.v1.json` |
| `fraud.manual-review` | Admin dashboard (human review) | Internal topic, no external schema |

### 8.3 Required ADRs (must be drafted before C6 can reach APPROVED)

| ADR | Title | Reason |
|---|---|---|
| ADR-004 | Event envelope standard and validation library | Notification deduplication relies on canonical `event_id` |
| ADR-005 | Idempotency key durability standard | Wallet/loyalty idempotency must align with checkout-orchestrator |
| ADR-010 | Feature flag fast-path and TTL policy | Fraud mode override must propagate within 1s for kill-switch |

---

## 9. Testing Strategy

### 9.1 Unit Tests (per service)

- **notification-service:** Mock SendGrid/Twilio, verify retry logic, circuit breaker transitions, deduplication uniqueness.
- **wallet-loyalty-service:** Simulate concurrent credit/debit, verify lock behavior, idempotency constraint, ledger integrity.
- **fraud-detection-service:** Mock rule evaluation, verify score calculation, blocklist priority, operational mode overrides.

**Coverage target:** 85% line coverage, 90% branch coverage for financial/fraud logic.

### 9.2 Integration Tests (Testcontainers)

- **notification-service:** Spin up PostgreSQL, Kafka (Redpanda), publish order event, verify notification logged, dedup prevents second send.
- **wallet-loyalty-service:** Testcontainers PostgreSQL, concurrent transaction test, verify no double-credit, ledger sum == balance.
- **fraud-detection-service:** Testcontainers PostgreSQL, Redis, test full scoring pipeline, verify velocity counters upserted, blocklist cached.

**Target:** 1 happy-path integration test + 3 edge-case tests per service.

### 9.3 Contract Tests (Pact / Spring Cloud Contract)

- **wallet-loyalty-service → order-service event:** Verify `OrderDelivered` event consumed correctly, points credited, cashback applied.
- **fraud-detection-service → order-service event:** Verify `FraudDetected` event with `action=BLOCK` causes order-service to reject order.
- **notification-service → identity-service REST:** Verify `/users/:id/preferences` response parsed correctly, email/SMS opt-out respected.

**Target:** 1 contract test per external dependency.

### 9.4 End-to-End Tests (staging environment)

1. **Order → Notification flow:** Place order, verify order confirmation SMS sent within 10s, delivery notification sent after fulfillment.
2. **Order → Wallet cashback:** Place order, verify 2% cashback credited to wallet, ledger entries created, outbox event published.
3. **High-risk order → Fraud block:** Submit order with high velocity (10 orders in 1 min), verify fraud score > 75, action = BLOCK, order rejected.
4. **GDPR erasure:** Submit erasure request, verify notification PII redacted, wallet transactions anonymized, fraud signals retained with hashed IDs.

**Target:** 4 critical paths, run on every staging deploy.

### 9.5 Chaos Tests

- **notification-service:** Kill SendGrid (mock 503 for 2 min), verify circuit breaker opens, retries after cooldown, recovers.
- **wallet-loyalty-service:** Inject 100ms latency on PostgreSQL queries, verify lock timeouts handled, retries succeed.
- **fraud-detection-service:** Kill Redis (cache layer), verify rules fetched from DB, performance degrades but requests succeed.

**Target:** 1 chaos scenario per service, run weekly in staging.

---

## 10. Cost & Performance Impact

### 10.1 Notification Service

**Current cost:**

- SendGrid: $0.001/email → ~$100/month at 100K emails
- Twilio: $0.05/SMS → ~$5K/month at 100K SMS
- PostgreSQL: notification_log table ~10GB/month (300B/row × 30M rows/month with 90-day retention)

**Post-hardening cost delta:**

- PII vault: +20% storage (separate table, same row count)
- Circuit breaker: negligible (in-memory state)
- Retry job: already present

**Performance:**

- Deduplication query: indexed on `(event_id, channel)`, < 5ms p99
- Circuit breaker: adds < 1ms per send

**Recommendation:** Acceptable. PII vault storage cost is compliance-justified.

### 10.2 Wallet-Loyalty Service

**Current cost:**

- PostgreSQL: wallet/loyalty tables ~5GB (10M users × 500B/user)
- Redis (Caffeine cache): in-process, negligible

**Post-hardening cost delta:**

- Optimistic locking: +1 read query per transaction (version check), < 5ms
- Integrity check job: 1 daily batch scan, < 5 min runtime, < $1/month

**Performance:**

- Lock contention: if lock retry rate > 1%, consider pessimistic lock (already implemented in wallet, replicate for loyalty)
- Ledger write: +2 INSERT per transaction, < 5ms total

**Recommendation:** Acceptable. Integrity checks are cheap, correctness is worth the cost.

### 10.3 Fraud Detection Service

**Current cost:**

- PostgreSQL: fraud_signals ~2GB/month (10M orders × 200B/signal)
- Redis: blocklist + rules cache ~10MB (small)

**Post-hardening cost delta:**

- Manual review queue: Kafka topic, ~100MB/day if 1% of orders flagged, ~$3/month
- Rule snapshots: GCS, ~1MB/snapshot × 30/month = ~$0.01/month

**Performance:**

- Operational mode check: < 1ms (in-memory feature flag)
- Rule explainability (rule_details JSONB): +50B/row, negligible query impact

**Recommendation:** Acceptable. Manual review queue cost is offset by fraud loss prevention.

---

## 11. Success Criteria & Exit Gates

### 11.1 Definition of Done (per phase)

**Phase 1 (Safety):**

- [ ] Loyalty points idempotency constraint deployed, integration test passes.
- [ ] Fraud operational mode feature flag exists, all modes tested in staging.
- [ ] Wallet ledger integrity check runs daily, zero discrepancies in 7-day window.
- [ ] Notification circuit breaker tested with mock provider outage, recovers within 60s.
- [ ] Velocity counter cleanup job verified running hourly, old counters deleted.

**Phase 2 (Privacy):**

- [ ] `notification_pii` vault created, dual-write enabled, backfill complete.
- [ ] GDPR erasure deletes PII vault row, notification_log retains hash.
- [ ] Manual audit: query notification_log, verify no plaintext PII.
- [ ] Column drop: plaintext `recipient`, `body`, `subject` removed after 7-day soak.

**Phase 3 (Governance):**

- [ ] Fraud rule export/import endpoints tested, snapshot stored in GCS.
- [ ] Rollback script tested: modify rule, export, rollback, verify original behavior.
- [ ] Fraud signal `rule_details` populated, explain endpoint returns readable response.
- [ ] Manual review queue topic created, admin dashboard consumes, decision published.

**Phase 4 (Hardening):**

- [ ] Wallet outbox lag alert configured, threshold < 1 hour.
- [ ] Referral fraud prevention: device fingerprint tracked, same-device redemptions flagged.
- [ ] Adaptive fraud thresholds: user profile table populated, adaptive rule tested in shadow mode.
- [ ] Two-person approval: change request workflow tested for high-impact admin actions.

### 11.2 Wave 3 Gate Criteria

Before Wave 3 can be marked complete, C6 must satisfy:

1. **Safety:** Zero wallet ledger discrepancies, zero loyalty double-credit incidents in production.
2. **Availability:** All services meet SLO (99.5% notification delivery, 99.9% wallet mutation success, 99.95% fraud scoring availability) for 30 days.
3. **Privacy:** GDPR audit passes — no plaintext PII in logs/DB outside retention window, erasure completes within 24h.
4. **Governance:** Fraud model rollback tested, manual review queue operational, audit trail complete.
5. **Runbooks:** On-call runbook covers fraud kill-switch, wallet balance recovery, notification provider failover.

---

## 12. Appendix: File References

### 12.1 Notification Service

- **Deduplication logic:** `services/notification-service/src/main/java/com/instacommerce/notification/service/DeduplicationService.java` line 28–65
- **Event ID extraction:** `services/notification-service/src/main/java/com/instacommerce/notification/consumer/BaseEventConsumer.java` (common parent for all consumers)
- **GDPR erasure:** `services/notification-service/src/main/java/com/instacommerce/notification/service/UserErasureService.java` line 21–42
- **Retry logic:** `services/notification-service/src/main/java/com/instacommerce/notification/infrastructure/retry/RetryableNotificationSender.java` line 35–67
- **PII masking:** `services/notification-service/src/main/java/com/instacommerce/notification/service/MaskingUtil.java` line 12–28
- **Schema:** `services/notification-service/src/main/resources/db/migration/V1__create_notification_log.sql`, `V2__create_audit_log.sql`, `V3__add_retry_columns.sql`
- **Config:** `services/notification-service/src/main/resources/application.yml` line 45–78

### 12.2 Wallet-Loyalty Service

- **Wallet locking:** `services/wallet-loyalty-service/src/main/java/com/instacommerce/wallet/repository/WalletRepository.java` `findByUserIdForUpdate` method (pessimistic lock)
- **Loyalty points (NO lock):** `services/wallet-loyalty-service/src/main/java/com/instacommerce/wallet/service/LoyaltyService.java` line 38–65
- **Ledger service:** `services/wallet-loyalty-service/src/main/java/com/instacommerce/wallet/service/WalletLedgerService.java` line 22–78
- **Outbox cleanup:** `services/wallet-loyalty-service/src/main/java/com/instacommerce/wallet/job/OutboxCleanupJob.java` line 18–35
- **Schema:** `services/wallet-loyalty-service/src/main/resources/db/migration/V1__create_wallet_tables.sql`, `V2__create_loyalty_tables.sql`, `V3__create_referral_tables.sql`
- **Config:** `services/wallet-loyalty-service/src/main/resources/application.yml` line 50–90

### 12.3 Fraud Detection Service

- **Scoring pipeline:** `services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/FraudScoringService.java` line 43–76
- **Rule evaluation:** `services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/RuleEvaluationService.java` line 28–120
- **Blocklist cache:** `services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/BlocklistService.java` line 35–56 (`@Cacheable("blocklist")`)
- **Velocity counters:** `services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/VelocityService.java` line 30–78 (UPSERT logic)
- **Admin controller:** `services/fraud-detection-service/src/main/java/com/instacommerce/fraud/controller/AdminFraudController.java` (all endpoints require `ROLE_ADMIN`)
- **Schema:** `services/fraud-detection-service/src/main/resources/db/migration/V1__create_fraud_rules.sql`, `V2__create_fraud_signals.sql`, `V3__create_blocked_entities.sql`, `V4__create_velocity_counters.sql`
- **Config:** `services/fraud-detection-service/src/main/resources/application.yml` line 40–85

### 12.4 Contracts

- **Fraud event schema:** `contracts/src/main/resources/schemas/fraud/FraudDetected.v1.json`
- **Wallet event schemas:** `contracts/src/main/resources/schemas/wallet/` (expected, not yet present — TODO for Wave 3)
- **Notification event schemas:** `contracts/src/main/resources/schemas/notification/` (expected, not yet present — TODO for Wave 3)

### 12.5 Platform Guidance

- **Security review:** `docs/reviews/iter3/platform/security-trust-boundaries.md` § 3 (JWT), § 6 (shared-token problem)
- **AI governance:** `docs/reviews/iter3/platform/ai-agent-governance.md` § 3.1 (agent policy), § 4 (model registry)
- **Service guide outline:** `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-SERVICE-GUIDE-OUTLINE.md` § C6 (pre-filled findings)

---

## 13. Ownership & Review Sign-Off

| Role | Name | Sign-off | Date |
|---|---|---|---|
| **Engineering owner (C6)** | TBD | ☐ | — |
| **SRE owner (C6)** | TBD | ☐ | — |
| **Security reviewer** | TBD | ☐ | — |
| **Principal reviewer** | TBD | ☐ | — |
| **Data/ML liaison** | TBD | ☐ | — |

**Change log:**

- 2026-03-08: Initial draft (automated, no prior session context)
- Awaiting first human review

---

**End of C6 — Customer & Engagement Implementation Review**
