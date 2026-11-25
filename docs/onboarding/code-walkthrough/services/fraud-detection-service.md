# `fraud-detection-service` — Code Walkthrough

> [!NOTE]
> **Part of the InstaCommerce Onboarding Suite:** [Onboarding Hub](../../README.md) · [Code Walkthrough Atlas](../README.md) · [Domain & Event Catalog (`05`)](../../05-domain-model-and-event-catalog.md) · [Incident Drills & Exam (`06`)](../../06-incident-drills-and-readiness-exam.md) · [Dependency Matrix & Cheatsheet (`07`)](../../07-service-dependency-and-command-cheatsheet.md)

> **Fail-Closed Rule Engine, Redis ML Score Cache, Aligned Velocity Buckets & Chargeback Auto-Blocklist**
>
> | Dimension | Verified Repo Truth |
> |---|---|
> | **Module & Runtime** | Java 21 / Spring Boot 4.0.0 ([build.gradle.kts](../../../../services/fraud-detection-service/build.gradle.kts#L1-L42)) · Spring Data Redis, Caffeine (`fraudRules`, `blocklist`), ShedLock · Dependencies: `:libs:platform-common`, `:contracts` |
> | **HTTP Port** | `8097` (`${SERVER_PORT:8097}` in [application.yml](../../../../services/fraud-detection-service/src/main/resources/application.yml#L1-L4)) |
> | **Primary Storage** | PostgreSQL `fraud` (`fraud_db` in Helm), Flyway `V1`–`V11` (`ddl-auto: validate`, `open-in-view: false`, Hikari `max=20`) + Redis (`fraud:score:{userId}`, **broken in Helm**!) + pod-local Caffeine |
> | **Kafka Topics** | **Produces** (via Go `outbox-relay-fraud-detection` `:8103`): `fraud.events` (`FraudDetected`)<br>**Consumes:** `orders.events` (`OrderPlaced`, group `fraud-detection-orders`) · `payments.events` (`PaymentFailed`, `PaymentDisputed`, `PaymentRefundFailed`, group `fraud-detection-payments`) · **DLT:** `{topic}.DLT` |
> | **Helm Keys** | `fraud-detection-service` ([values.yaml](../../../../deploy/helm/values.yaml#L1368-L1384)) · Relay: `outbox-relay-fraud-detection` ([values.yaml](../../../../deploy/helm/values.yaml#L1125)) · **Helm bug:** omits `REDIS_HOST`! |
> | **SLO Tier** | **Tier 2** (`99.0%` availability, `2000 ms` p99, `432 min/30d` budget) per [error-budget-policy.yaml](../../../../monitoring/error-budget-policy.yaml#L29-L44) *(doc drift: [service-slos.md](../../../../docs/slos/service-slos.md#L188) claims `fail open` while code explicitly fails closed!)* |

---

## 1. Service Boundaries & Package Tree

`fraud-detection-service` computes real-time risk scores (`0–100`), maintains entity blocklists and allowlists (`USER`, `DEVICE`, `IP`), aggregates time-bucketed velocity counters from `orders.events` and `payments.events`, and emits `FraudDetected` events to `fraud.events` whenever a check returns an action other than `ALLOW`.

**Crucial System-Level Reality:** Although `fraud-detection-service` exposes `POST /fraud/score` for synchronous pre-checkout risk checks, **neither `checkout-orchestrator-service` nor `order-service` calls `POST /fraud/score` today**. In live traffic, the active code path is asynchronous feedback ingestion (`OrderEventConsumer` + `PaymentEventConsumer`) plus administrative CRUD via `admin-gateway-service`.

### Package Layout (`com.instacommerce.fraud`)

```text
services/fraud-detection-service/src/main/java/com/instacommerce/fraud/
├── FraudDetectionServiceApplication.java     # @SpringBootApplication, @ConfigurationPropertiesScan
├── client/
│   └── RestOrderLookupClient.java            # RestClient -> order-service:8084/admin/orders/{id} (3s/5s, NO CB/Retry)
├── config/
│   ├── CacheConfig.java                      # Pod-local Caffeine: fraudRules (1K, 300s), blocklist (5K, 60s)
│   ├── FraudProperties.java                  # @ConfigurationProperties(prefix = "fraud") jwt, cors, order
│   ├── KafkaConfig.java                      # DefaultErrorHandler(FixedBackOff 1s, 3x) -> {topic}.DLT (no non-retryable exclusions)
│   ├── SecurityConfig.java                   # @Import(InternalServiceSecurityConfiguration) + JwtAuthenticationFilter
│   └── ShedLockConfig.java                   # JdbcTemplateLockProvider usingDbTime(), PT30M
├── consumer/
│   ├── OrderEventConsumer.java               # orders.events -> OrderPlaced velocity (+ count & amount)
│   └── PaymentEventConsumer.java             # payments.events -> PaymentFailed, PaymentRefundFailed, PaymentDisputed auto-block
├── controller/
│   ├── FraudController.java                  # POST /fraud/score (INTERNAL/ADMIN), POST /fraud/report
│   └── AdminFraudController.java             # /admin/fraud/{rules,blocklist,allowlist} (ADMIN/SUPER_ADMIN/ADMIN_GATEWAY)
├── domain/model/                             # FraudRule (@Version), FraudSignal, VelocityCounter, BlockedEntity, AllowlistedEntity
├── exception/
│   └── GlobalExceptionHandler.java           # Custom ErrorResponse (NOT ProblemDetailFactory)
├── repository/                               # VelocityCounterRepository (native PG ON CONFLICT UPSERT)
└── service/
    ├── FraudScoringService.java              # Fail-closed scoring pipeline, FraudOperationalMode, REQUIRES_NEW persistence
    ├── RuleEvaluationService.java            # VELOCITY, AMOUNT, DEVICE, GEO, PATTERN, COMBINATION + default combo heuristics
    ├── BlocklistService.java                 # @Cacheable("blocklist") + @CacheEvict (local pod only)
    ├── AllowlistService.java                 # Uncached DB lookup across USER / DEVICE / IP
    ├── VelocityService.java                  # Sub-bucket time alignment + increment + rolling window sum
    ├── RuleConditionValidator.java           # Strict schema validation on admin rule JSON before save
    ├── OutboxService.java                    # Propagation.MANDATORY outbox_events writer
    ├── VelocityCounterCleanupJob.java        # Hourly :15 ShedLock purge (window_end < now - 2d)
    └── OutboxCleanupJob.java                 # Daily 03:30 ShedLock purge (default 30d retention)
```

---

## 2. Startup, Configuration & Security Filter Chain

1. **Auth Rules ([SecurityConfig](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/config/SecurityConfig.java#L1-L73)):** Imports `InternalServiceSecurityConfiguration` (`SharedInternalServiceAuthFilter`) and registers `JwtAuthenticationFilter` ahead of `UsernamePasswordAuthenticationFilter`:
   - `POST /fraud/score`: requires `ROLE_INTERNAL_SERVICE`, `ROLE_ADMIN`, or `ROLE_SUPER_ADMIN`.
   - `/admin/**`: requires `ROLE_ADMIN`, `ROLE_SUPER_ADMIN`, or `ROLE_SERVICE_ADMIN_GATEWAY_SERVICE`.
   - `POST /fraud/report`: authenticated callers can report fraud (internal/admin callers can specify another `userId`; normal users report against themselves in [FraudController](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/controller/FraudController.java#L1-L75)).
2. **Dynamic Flag Caveat:** [FraudScoringService](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/FraudScoringService.java#L65) binds `FraudOperationalMode` via `@Value("${fraud.operational-mode:AUTO_BLOCK}")` once at startup (contrary to javadoc text claiming live flag switching without restart).
3. **Strict Rule Validation & Error DTO:** [AdminFraudController](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/controller/AdminFraudController.java#L1-L174) passes rule mutations through [RuleConditionValidator](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/RuleConditionValidator.java#L1-L83) (`VELOCITY`, `AMOUNT`, `DEVICE`, `GEO`, `PATTERN`, `COMBINATION`) and evicts `fraudRules` on the local pod. Errors format through [GlobalExceptionHandler](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/exception/GlobalExceptionHandler.java#L21-L92) (`ErrorResponse` DTO).

---

## 3. PostgreSQL Schema (`V1`–`V11`) & Aligned Velocity Sub-Buckets

| Migration | Schema & Constraint Details |
|---|---|
| `V1`, `V2`, `V7` | `fraud_rules` (JSONB `condition`, `score_impact`, `priority`, `version`; `V7` expands `rule_type` to add `COMBINATION`) and `fraud_signals` (`order_id`, `user_id`, `score` `0..100`, `risk_level`, `action`, `triggered_rules`). |
| `V3`, `V10`, `V11` | `velocity_counters` (`entity_type`, `entity_id`, `counter_type`, `counter_value`, `window_start`, `window_end`; `uq_velocity_window` constraint over `(entity_type, entity_id, counter_type, window_start)`). `V10` adds burst counter types (`*_5M`, `*_1H`) and `allowlisted_entities`; `V11` adds `CHARGEBACKS_30D` and `REFUND_FAILURES_24H`. |
| `V4`, `V7`, `V10` | `blocked_entities` (`V4` + `V7` partial unique index `WHERE active = true` on `(entity_type, entity_id)`) and `allowlisted_entities` (`V10` partial unique index `WHERE active = true`). |
| `V5`, `V6`, `V8`, `V9` | `outbox_events` (`V8` aligns `EventEnvelope` `schema_version = 'v1'`; `V9` adds retention index) and `shedlock` (`V6`). |

### Sub-Bucket Alignment Mechanics ([VelocityService](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/VelocityService.java#L66-L77))
Rather than maintaining a single counter row per rolling window or mutating raw event logs, `VelocityService.alignSubBucketStart` floors `Instant.now()` onto fixed sub-bucket boundaries and writes atomically via `INSERT ... ON CONFLICT ON CONSTRAINT uq_velocity_window DO UPDATE SET counter_value = velocity_counters.counter_value + :delta`:

| Counter Type | Sub-Bucket Size | Effective Window Span |
|---|---|---|
| `ORDERS_5M`, `FAILED_PAYMENTS_5M` | `60s` | `5 minutes` |
| `ORDERS_1H`, `FAILED_PAYMENTS_1H` | `300s` (`5m`) | `1 hour` |
| `ORDERS_24H`, `AMOUNT_24H`, `REFUND_FAILURES_24H`, `VELOCITY` | `3600s` (`1h`) | `24 hours` |
| `CHARGEBACKS_30D` | `86400s` (`1d`) | `30 days` |

Reads call [VelocityCounterRepository.sumCounterValueInWindow](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/repository/VelocityCounterRepository.java#L1-L36) (`WHERE window_end > :now`). Because `windowEnd = alignedStart + windowDuration`, `VelocityCounterCleanupJob` (`WHERE window_end < now - 2d`) automatically preserves `CHARGEBACKS_30D` sub-buckets until their 30-day window actually expires!

---

## 4. Architecture & Low-Level Component Topology

**fraud-detection-service Architecture LLD** — *[Rendered PNG](../diagrams/services/fraud-detection-service-arch-lld.png) · [Mermaid Source](../diagrams/services/fraud-detection-service-arch-lld.mmd)*

```mermaid
flowchart TB
    classDef caller fill:#F8FAFC,stroke:#475569,stroke-width:1.5px,color:#0F172A
    classDef sec fill:#FEF3C7,stroke:#D97706,stroke-width:1.6px,color:#78350F
    classDef ctrl fill:#EFF6FF,stroke:#2563EB,stroke-width:1.6px,color:#0F172A
    classDef svc fill:#EEF2FF,stroke:#4F46E5,stroke-width:1.6px,color:#0F172A
    classDef db fill:#ECFDF5,stroke:#059669,stroke-width:1.6px,color:#0F172A
    classDef warn fill:#FEF2F2,stroke:#DC2626,stroke-width:1.8px,stroke-dasharray:5 3,color:#7F1D1D

    subgraph CALLERS["Callers, Event Streams & Unwired Seam"]
        direction LR
        CHK["checkout / order-service<br/>NOT calling /fraud/score today!"]:::warn
        ADM["admin-gateway-service :8099<br/>CRUD rules / blocklist / allowlist"]:::caller
        K_IN["Kafka: orders.events (OrderPlaced)<br/>payments.events (PaymentFailed/Disputed/RefundFailed)"]:::caller
    end

    subgraph FRAUD["fraud-detection-service :8097 (Java 21 · Spring Boot 4.0.0 · Hikari max=20)"]
        direction TB
        SEC["SecurityConfig (@Import InternalServiceSecurityConfiguration)<br/>/fraud/score: INTERNAL_SERVICE | ADMIN | SUPER_ADMIN<br/>/admin/**: ADMIN | SUPER_ADMIN | SERVICE_ADMIN_GATEWAY_SERVICE"]:::sec

        subgraph CTRLS["controller/ & exception/"]
            direction LR
            FC["FraudController<br/>POST /fraud/score<br/>POST /fraud/report (self or privileged)"]:::ctrl
            AFC["AdminFraudController<br/>/admin/fraud/{rules,blocklist,allowlist}<br/>RuleConditionValidator + @CacheEvict(fraudRules)"]:::ctrl
            GEH["GlobalExceptionHandler<br/>custom ErrorResponse"]:::ctrl
        end

        subgraph ENGINE["service/ & Caching"]
            direction LR
            FSS["FraudScoringService (fail-closed)<br/>FraudOperationalMode (@Value at startup)<br/>TransactionTemplate REQUIRES_NEW signal persist"]:::svc
            RES["RuleEvaluationService<br/>VELOCITY · AMOUNT · DEVICE · GEO · PATTERN · COMBINATION<br/>+ evaluateDefaultCombinationScore"]:::svc
            BL_AL["BlocklistService (@Cacheable 60s)<br/>AllowlistService (uncached 3x DB reads!)<br/>VelocityService (UPSERT aligned buckets)"]:::svc
            CAF["CacheConfig (pod-local Caffeine!)<br/>fraudRules (1K, 300s) · blocklist (5K, 60s)<br/>no Redis Pub/Sub cross-pod invalidation"]:::warn
        end

        subgraph ASYNC["consumer/, client/ & Jobs"]
            direction LR
            OEC["OrderEventConsumer (fraud-detection-orders)<br/>OrderPlaced -> velocity buckets"]:::svc
            PEC["PaymentEventConsumer (fraud-detection-payments)<br/>KafkaConfig DLT (3x 1s; JsonProcessingEx wrapped!)"]:::svc
            ORD_CL["RestOrderLookupClient<br/>GET order-service:8084/admin/orders/{id}<br/>3s conn / 5s read (no CB/Retry)"]:::sec
            JOBS["ShedLock Jobs:<br/>VelocityCounterCleanupJob (:15 hourly, 2d retention)<br/>OutboxCleanupJob (03:30 daily, 30d retention)"]:::svc
        end
    end

    subgraph DATA["PostgreSQL fraud DB (V1..V11), Redis & Outbox"]
        direction LR
        PG["PostgreSQL Tables:<br/>fraud_rules (V1/V7/V10) · fraud_signals (V2)<br/>velocity_counters (V3/V10/V11 uq_velocity_window)<br/>blocked_entities (V4/V7 partial uq)<br/>allowlisted_entities (V10 partial uq)<br/>outbox_events (V8 v1) · shedlock"]:::db
        REDIS["Redis fraud:score:{userId}<br/>HELM OMISSION: REDIS_HOST missing in K8s!<br/>Pods hit localhost:6379 -&gt; FALLBACK_RULES"]:::warn
        RELAY["outbox-relay-fraud-detection :8103 (Go)<br/>polls outbox_events -&gt; fraud.events (FraudDetected)"]:::db
    end

    CHK -.-> SEC
    ADM --> SEC --> CTRLS
    FC --> FSS --> RES & BL_AL & REDIS
    FSS --> CAF
    AFC --> BL_AL & PG
    AFC --> CAF
    FSS --> PG
    BL_AL --> PG
    K_IN --> OEC & PEC
    OEC --> BL_AL
    PEC --> BL_AL & ORD_CL
    JOBS --> PG
    PG --> RELAY
```

**Architectural Reality Check:**
- **Broken Redis ML Cache Link in K8s:** `FraudScoringService` injects `StringRedisTemplate` to read pre-computed ML risk scores at `fraud:score:{userId}` (`application.yml` defaults `spring.data.redis.host` to `localhost:6379`). However, [deploy/helm/values.yaml](../../../../deploy/helm/values.yaml#L1368-L1384) and environment overlays omit `REDIS_HOST`, so every Kubernetes pod hits `localhost:6379`, gets connection refused, increments `fraud.scoring.errors{stage="redis_cache", fallback_action="FALLBACK_RULES"}`, and silently falls back to deterministic rules.
- **No CB/Retry on Order Lookup:** Unlike `wallet-loyalty-service` and `notification-service`, [RestOrderLookupClient](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/client/RestOrderLookupClient.java#L1-L79) has `3s/5s` timeouts with **zero** `@CircuitBreaker` or `@Retry`.

---

## 5. Scoring Pipeline & Fail-Closed Error Tree

**fraud-detection-service Flowchart** — *[Rendered PNG](../diagrams/services/fraud-detection-service-flowchart.png) · [Mermaid Source](../diagrams/services/fraud-detection-service-flowchart.mmd)*

```mermaid
flowchart TD
    classDef start fill:#EFF6FF,stroke:#2563EB,stroke-width:1.6px,color:#0F172A
    classDef dec fill:#FEF3C7,stroke:#D97706,stroke-width:1.6px,color:#78350F
    classDef ok fill:#ECFDF5,stroke:#059669,stroke-width:1.6px,color:#0F172A
    classDef err fill:#FEF2F2,stroke:#DC2626,stroke-width:1.8px,color:#7F1D1D
    classDef step fill:#EEF2FF,stroke:#4F46E5,stroke-width:1.5px,color:#0F172A

    REQ(["FraudScoringService.scoreTransaction(req)"]):::start --> MODE{"FraudOperationalMode?"}:::dec
    MODE -->|PASS_THROUGH| ALLOW0["Immediate ALLOW (score=0, unpersisted)"]:::ok
    MODE -->|AUTO_BLOCK / MANUAL_REVIEW / SHADOW| BLK{"1. BlocklistService.isBlocked<br/>(USER / DEVICE / IP)"}:::dec

    BLK -->|Hit| BLK_ACT["score=100 CRITICAL<br/>BLOCK (or REVIEW / SHADOW allow)"]:::err
    BLK -->|Exception| FC_ERR["failClosedOnDependencyError:<br/>score=100 CRITICAL BLOCK/REVIEW"]:::err
    BLK -->|Miss| ALW{"2. AllowlistService.isAllowlisted<br/>(uncached PG lookup)"}:::dec

    ALW -->|Hit| ALW_OK["score=0 LOW ALLOW<br/>(ALLOWLIST_BYPASS)"]:::ok
    ALW -->|Exception| FC_ERR
    ALW -->|Miss| REDIS{"3. Redis fraud:score:{userId}<br/>&gt; 0.85?"}:::dec

    REDIS -->|gt 0.85| ML_BLK["score=round(s*100) CRITICAL<br/>BLOCK / REVIEW (SHADOW allow)"]:::err
    REDIS -->|Err / Conn Refused| ML_FB["metric: redis_cache / FALLBACK_RULES<br/>continue to rule evaluation"]:::step
    REDIS -->|Miss / le 0.85| RULES["4. Load fraudRules (Caffeine 300s)<br/>Evaluate priority-ordered rules"]:::step

    RULES --> RLOOP["Sum scoreImpact + escalate action<br/>ALLOW rule decrements score;<br/>Rule exception -&gt; floor 51/76 (HIGH/CRITICAL)<br/>+ DefaultCombo (+35..80 COD/high-risk)"]:::step
    RLOOP --> CLAMP["clamp 0..100 -&gt; RiskLevel.fromScore<br/>escalate(fromRiskLevel, highestAction)<br/>REQUIRES_NEW save fraud_signals +<br/>(if action != ALLOW) outbox FraudDetected"]:::ok

    subgraph VEL["5. Async Velocity Feedback & Chargeback Auto-Block"]
        direction LR
        ORD_P["OrderPlaced -&gt; USER/DEVICE/IP<br/>ORDERS_5M(60s), 1H(300s), 24H(3600s)<br/>+ AMOUNT_24H(3600s)"]:::step
        PAY_E["PaymentFailed -&gt; FAILED_PAYMENTS_5M/1H<br/>PaymentRefundFailed -&gt; REFUND_FAILURES_24H<br/>PaymentDisputed -&gt; CHARGEBACKS_30D(86400s)"]:::step
        PAY_E --> CB_CHK{"CHARGEBACKS_30D &gt;= 2?"}:::dec
        CB_CHK -->|Yes| AUTO_BLK["BlocklistService.block('USER', userId,<br/>expiresAt=null, 'system:payment-disputed')"]:::err
    end
```

### Step-by-Step Scoring Logic ([FraudScoringService](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/FraudScoringService.java#L1-L405))
1. **Mode Gate:** If `FraudOperationalMode.PASS_THROUGH`, returns immediate `ALLOW` (`score=0`) without hitting DB or Redis and without writing `fraud_signals`.
2. **Step 1 — Blocklist Check:** Calls [BlocklistService.isBlocked](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/BlocklistService.java#L1-L75) for `USER`, `DEVICE`, and `IP` (Caffeine `60s`, caches both `true` and `false`). Any hit returns `score=100`, `CRITICAL`, `BLOCK` (`REVIEW` in `MANUAL_REVIEW`, `ALLOW` in `SHADOW`).
3. **Step 2 — Uncached Allowlist Bypass:** Calls [AllowlistService.isAllowlisted](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/AllowlistService.java#L1-L93), which executes **up to 3 uncached PostgreSQL queries** per check. A hit immediately persists `fraud_signals(score=0, ALLOW)` inside a `TransactionTemplate` (`REQUIRES_NEW`) and exits.
4. **Step 3 — Fail-Closed Dependency Semantics:** If *either* `BlocklistService` or `AllowlistService` throws an exception, `failClosedOnDependencyError` immediately returns `score=100`, `CRITICAL`, `BLOCK` (or `REVIEW`) and attempts to persist a best-effort audit signal. In contrast, Redis failures in `getCachedFraudRiskScore` degrade safely to `FALLBACK_RULES`; if Redis returns `score > 0.85`, scoring short-circuits to `round(s * 100)` (`CRITICAL` / `BLOCK`).
5. **Step 4 — Rule Chain + Default Combination Heuristic ([RuleEvaluationService](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/RuleEvaluationService.java#L1-L250)):**
   - Evaluates active `FraudRule` rows sorted by `priority` (cached `300s` per pod). `ALLOW` rules decrement score; non-`ALLOW` matches add `scoreImpact` and escalate `action`. Rule-level exceptions bump a failure floor (`51` `HIGH`, or `76` `CRITICAL` if `totalCents` exceeds threshold).
   - Appends `evaluateDefaultCombinationScore(request)` (e.g., high order value + velocity burst + COD).
   - Persists `FraudSignal` and writes `FraudDetected` to `outbox_events` inside `REQUIRES_NEW` whenever `action != ALLOW`; if persistence itself fails when `minimumErrorScore > 0`, `persistFailClosedSafe` still returns `BLOCK`/`REVIEW` to protect checkout!

---

## 6. Call-Stack Sequence: Sync Scoring & Chargeback Auto-Blocklist

**fraud-detection-service Sequence Diagram** — *[Rendered PNG](../diagrams/services/fraud-detection-service-sequence.png) · [Mermaid Source](../diagrams/services/fraud-detection-service-sequence.mmd)*

```mermaid
sequenceDiagram
    autonumber
    participant Caller as Caller / Admin / Kafka
    participant Ctrl as FraudController /<br/>PaymentEventConsumer
    participant FSS as FraudScoringService
    participant BL as BlocklistService<br/>(Caffeine 60s)
    participant AL as AllowlistService<br/>(DB uncached)
    participant Redis as StringRedisTemplate<br/>(fraud:score:{userId})
    participant RuleSvc as RuleEvaluationService /<br/>VelocityService
    participant PG as PostgreSQL<br/>(fraud DB)

    Note over Caller,PG: 1. Real-Time Fail-Closed Fraud Scoring (POST /fraud/score)
    Caller->>Ctrl: scoreTransaction(FraudCheckRequest)
    Ctrl->>FSS: scoreTransaction(request)
    FSS->>BL: isBlocked(USER/DEVICE/IP) [Caffeine hit or PG SELECT]
    alt Blocked
        FSS->>PG: TX [REQUIRES_NEW]: INSERT fraud_signals + outbox FraudDetected
        FSS-->>Caller: 200 {score:100, riskLevel:CRITICAL, action:BLOCK}
    else Not blocked
        FSS->>AL: isAllowlisted(USER/DEVICE/IP) [up to 3x PG queries]
        alt Allowlisted
            FSS->>PG: TX [REQUIRES_NEW]: INSERT fraud_signals(score=0, ALLOW)
            FSS-->>Caller: 200 {score:0, riskLevel:LOW, action:ALLOW}
        else Evaluate ML score + Rule Chain
            FSS->>Redis: GET fraud:score:{userId}
            Note right of Redis: In K8s, REDIS_HOST omitted →<br/>hits localhost:6379 → FALLBACK_RULES
            FSS->>FSS: loadActiveRules() [@Cacheable fraudRules 300s]
            loop Each active FraudRule by priority
                FSS->>RuleSvc: evaluateRule(rule, request)
                opt VELOCITY / DEVICE / RAPID_RETRY / COMBINATION
                    RuleSvc->>PG: VelocityCounterRepository.sumCounterValueInWindow(now)
                end
            end
            FSS->>RuleSvc: evaluateDefaultCombinationScore(request)
            FSS->>PG: TX [REQUIRES_NEW] persistAndRespond: INSERT fraud_signals + outbox FraudDetected
            Note right of PG: If DB fails when minimumErrorScore > 0,<br/>persistFailClosedSafe returns BLOCK/REVIEW anyway
            FSS-->>Caller: 200 FraudCheckResponse
        end
    end

    Note over Caller,PG: 2. Chargeback Auto-Blocklist Pipeline (payments.events → PaymentDisputed)
    Caller->>Ctrl: onPaymentEvent(PaymentDisputed)
    opt userId missing in payload
        Ctrl->>Ctrl: RestOrderLookupClient.findOrder(orderId) → GET :8084/admin/orders/{id}
    end
    Ctrl->>RuleSvc: incrementCounter(USER/DEVICE/IP, CHARGEBACKS_30D)
    RuleSvc->>PG: INSERT ... ON CONFLICT ON CONSTRAINT uq_velocity_window DO UPDATE (86400s sub-bucket)
    Ctrl->>RuleSvc: getCount(USER, userId, CHARGEBACKS_30D)
    alt count ≥ chargebackBlockThreshold (default 2)
        Ctrl->>BL: block("USER", userId, expiresAt=null, "system:payment-disputed")
        BL->>PG: deactivate old + INSERT blocked_entities + @CacheEvict(local pod only)
    end
```

Trace 2 in the sequence diagram illustrates [PaymentEventConsumer](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/consumer/PaymentEventConsumer.java#L1-L176) reacting to `PaymentDisputed` on `payments.events`:
- If `userId` is missing in the payload, it resolves it synchronously via `RestOrderLookupClient.findOrder(orderId)` (`GET order-service:8084/admin/orders/{id}`).
- Increments `CHARGEBACKS_30D` across available `USER`, `DEVICE`, and `IP` identifiers (`86400s` daily sub-bucket).
- Reads `getCount(USER, userId, CHARGEBACKS_30D)`. Once `count >= chargebackBlockThreshold` (default `2`), it invokes `BlocklistService.block("USER", userId, expiresAt=null, "system:payment-disputed")`, deactivating prior entries and inserting a permanent `blocked_entities` row.

---

## 7. Consumers, Scheduled Jobs & Outbox

- **[OrderEventConsumer](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/consumer/OrderEventConsumer.java#L1-L75):** Listens on `orders.events` (`fraud-detection-orders`). For `OrderPlaced`, increments `ORDERS_5M`, `ORDERS_1H`, `ORDERS_24H`, and `AMOUNT_24H` across `USER`, `DEVICE`, and `IP`.
- **[PaymentEventConsumer](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/consumer/PaymentEventConsumer.java#L1-L176):** Listens on `payments.events` (`fraud-detection-payments`) for `PaymentFailed` (`FAILED_PAYMENTS_5M`/`1H`), `PaymentRefundFailed` (`REFUND_FAILURES_24H`), and `PaymentDisputed` (`CHARGEBACKS_30D` + auto-block).
- **[VelocityCounterCleanupJob](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/VelocityCounterCleanupJob.java#L1-L35) & [OutboxCleanupJob](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/service/OutboxCleanupJob.java#L14-L35):** Run under ShedLock (`PT5M`/`PT30M`): `:15` past every hour for expired velocity counters (`window_end < now - 2d`) and `03:30` daily for outbox cleanup (`30d` default retention).

---

## 8. Configuration, Helm Overrides & Narrow Test Verification

- **Key Properties ([application.yml](../../../../services/fraud-detection-service/src/main/resources/application.yml#L1-L100)):** `fraud.operational-mode: AUTO_BLOCK`, `fraud.score-cache-ttl-seconds: 300`, `fraud.chargeback.auto-block-threshold: 2`, `outbox.retention: 30d`, `spring.data.redis.host: ${REDIS_HOST:localhost}`, `spring.data.redis.port: ${REDIS_PORT:6379}`.
- **Tests:** `10` test classes live under `services/fraud-detection-service/src/test` (including `FraudScoringServiceTest`, `FraudScoringTransactionBoundaryTest`, `FraudVelocityAllowlistAndComboTest`, `PaymentEventConsumerTest`, `FraudDetectionPostgresFlywayIntegrationTest`). Run narrow verification via:

```bash
./gradlew :services:fraud-detection-service:test --tests "com.instacommerce.fraud.service.FraudScoringTransactionBoundaryTest"
```

---

## 9. Must-Fix Code Risks & Documentation Drift

| Severity | Finding | Exact Evidence & Impact |
|---|---|---|
| **MUST-FIX** | **Checkout does NOT invoke `fraud-detection-service`** | Neither `checkout-orchestrator-service` nor `order-service` calls `POST /fraud/score` synchronously today; velocity counters and chargeback auto-blocks accumulate in DB without blocking fraudulent checkouts. |
| **MUST-FIX** | **Missing `REDIS_HOST` / `REDIS_PORT` in Helm chart** | [deploy/helm/values.yaml](../../../../deploy/helm/values.yaml#L1368-L1384) omits `REDIS_HOST`, forcing K8s pods to dial `localhost:6379` for `fraud:score:{userId}` and fall back to rules on every scoring request (`fraud.scoring.errors`). |
| **HIGH** | **Single-pod Caffeine cache split-brain (`blocklist` / `fraudRules`) & uncached allowlist** | [CacheConfig](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/config/CacheConfig.java#L1-L34) uses pod-local Caffeine (`fraudRules` 300s, `blocklist` 60s caching `false` too) with no Redis Pub/Sub invalidation—an auto-block or admin rule edit on Pod A leaves Pod B stale up to `60s`/`300s`. Meanwhile, `AllowlistService` runs up to 3 uncached DB queries per `/fraud/score`. |
| **MEDIUM** | **Malformed JSON retries 3x before DLT & static operational mode** | Both Kafka consumers wrap `JsonProcessingException` in `IllegalStateException`, and [KafkaConfig](../../../../services/fraud-detection-service/src/main/java/com/instacommerce/fraud/config/KafkaConfig.java#L1-L24) registers no `addNotRetryableExceptions`—wasting `3 x 1s` backoff per poison pill. `FraudOperationalMode` (`@Value`) also cannot switch dynamically at runtime without pod restart. |
| **DRIFT** | **SLO doc & service README diverge from code** | [service-slos.md](../../../../docs/slos/service-slos.md#L188) falsely states `fraud-detection-service` `fails open` (code fails closed in `FraudScoringService`). [fraud-detection-service/README.md](../../../../services/fraud-detection-service/README.md) omits `V10`/`V11`, `COMBINATION` rules, `AllowlistService`, `PaymentDisputed`, `RestOrderLookupClient`, and cites singular topic names. |

---

**Previous:** [wallet-loyalty-service](./wallet-loyalty-service.md) | **Atlas Hub:** [Code Walkthrough Atlas](../README.md) | **Next:** [warehouse-service](./warehouse-service.md)
