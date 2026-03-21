# Config & Feature Flag Service - Flowchart Diagrams

## Flag Evaluation Flow (Read Path)

### Main Evaluation Process

```mermaid
flowchart TD
    Start([Client Request: GET /flags/{key}?userId=123&context={...}]) --> ValidateInput{Input Validation:<br/>- Flag key not null<br/>- Context valid JSON}

    ValidateInput -->|FAIL| InvalidInput["Return 400<br/>Bad Request"]
    ValidateInput -->|PASS| CheckAuth{Authentication &<br/>Authorization?}

    CheckAuth -->|FAIL| Unauthorized["Return 401<br/>Unauthorized"]
    CheckAuth -->|PASS| CacheLookup{"Check Caffeine Cache<br/>Key: {flagKey}:{userId}:{contextHash}"}

    CacheLookup -->|HIT| IncHitMetric["📊 Increment hit counter"]
    IncHitMetric --> RecordLatency["⏱️ Record latency &lt; 5ms"]
    RecordLatency --> ReturnCached["Return cached flag<br/>enabled: boolean<br/>variant: String"]

    CacheLookup -->|MISS| IncMissMetric["📊 Increment miss counter"]
    IncMissMetric --> LoadFromDB["🗄️ Query PostgreSQL<br/>SELECT * FROM feature_flags<br/>WHERE key = ?"]

    LoadFromDB --> DBFound{Flag<br/>Found?}

    DBFound -->|NO| FlagNotFound["Return 404<br/>Flag not found"]
    DBFound -->|YES| CheckEnabled{Flag<br/>enabled=true?}

    CheckEnabled -->|NO| ReturnDisabled["Return FlagEvaluationResponse<br/>enabled: false<br/>reason: 'Flag disabled globally'"]

    CheckEnabled -->|YES| LoadRules["Load rollout rules<br/>FROM flag_rollout_rules<br/>WHERE flag_id = ?<br/>ORDER BY order_index"]

    LoadRules --> EvalRules{"Evaluate rules<br/>(userId, context, rules)"}

    EvalRules -->|First rule matches<br/>and enabled=true| RuleMatched["Eligible for flag"]
    EvalRules -->|First rule matches<br/>and enabled=false| RuleExcluded["Not eligible for flag"]
    EvalRules -->|No rules match| DefaultFallback["Use flag.enabled<br/>= true"]

    RuleMatched --> CheckExperiment{Active A/B<br/>Experiment?}
    RuleExcluded --> ReturnIneligible["Return FlagEvaluationResponse<br/>enabled: false<br/>reason: 'Failed rollout rule'"]
    DefaultFallback --> CheckExperiment

    CheckExperiment -->|NO| BuildResponse["Build response<br/>enabled: boolean<br/>variant: flag.defaultVariant<br/>cacheKey: generated"]
    CheckExperiment -->|YES| GetAssignment["Get experiment assignment<br/>FOR userId in experiment"]

    GetAssignment --> AssignmentExists{Assignment<br/>exists?}

    AssignmentExists -->|YES| UseExistingVariant["Use existing variant (A or B)<br/>Deterministic: same user<br/>always gets same variant"]
    AssignmentExists -->|NO| GenerateAssignment["Generate new assignment<br/>hash(userId + experimentKey) % 100<br/>If hash &lt; allocation%: variant B<br/>Else: variant A"]

    GenerateAssignment --> PersistAssignment["PERSIST to experiment_assignments"]

    UseExistingVariant --> BuildResponseWithVariant["Build response<br/>enabled: true<br/>variant: assignedVariant<br/>reason: 'A/B experiment'"]
    PersistAssignment --> BuildResponseWithVariant

    BuildResponse --> CacheResult["💾 Cache result in Caffeine<br/>Key: {flagKey}:{userId}:{contextHash}<br/>TTL: 30s"]
    BuildResponseWithVariant --> CacheResult

    CacheResult --> ReturnSuccess["✅ Return 200 OK"]

    ReturnSuccess --> RecordMetrics["📊 Record metrics:<br/>- Latency (typical: 30ms)<br/>- Flag evaluation count"]
    RecordMetrics --> End([End])

    InvalidInput --> End
    Unauthorized --> End
    FlagNotFound --> End
    ReturnDisabled --> End
    ReturnIneligible --> End

    style Start fill:#2196F3,color:#fff
    style End fill:#4CAF50,color:#fff
    style CacheLookup fill:#FFC107,color:#000
    style LoadFromDB fill:#FF9800,color:#fff
    style EvalRules fill:#9C27B0,color:#fff
    style GetAssignment fill:#9C27B0,color:#fff
    style CacheResult fill:#8BC34A,color:#fff
    style ReturnSuccess fill:#4CAF50,color:#fff
```

---

## Flag Update Flow (Write Path + Cache Invalidation)

### Admin Updates Flag with Wave 35 Redis Pub/Sub

```mermaid
flowchart TD
    Start([Admin: PUT /flags/{key}<br/>Request: {enabled, rollout%, segments}]) --> Auth{Authentication:<br/>JWT + ADMIN role?}

    Auth -->|FAIL| Forbidden["Return 403<br/>Forbidden"]
    Auth -->|PASS| Validate{Input Validation:<br/>- key not null<br/>- rollout: 0-100}

    Validate -->|FAIL| InvalidReq["Return 400<br/>Bad Request"]
    Validate -->|PASS| BeginTransaction["BEGIN PostgreSQL<br/>Transaction<br/>(Isolation Level: REPEATABLE_READ)"]

    BeginTransaction --> LockFlag["LOCK flag row<br/>FOR UPDATE<br/>(prevent concurrent updates)"]

    LockFlag --> LoadCurrentFlag["SELECT * FROM feature_flags<br/>WHERE key = ?"]

    LoadCurrentFlag --> FlagExists{Flag<br/>exists?}

    FlagExists -->|NO| InsertNewFlag["INSERT INTO feature_flags<br/>(id, key, enabled, rollout_percentage, ...)<br/>VALUES (...)"]
    FlagExists -->|YES| UpdateExistingFlag["UPDATE feature_flags<br/>SET enabled=?, rollout_percentage=?, updated_at=NOW()<br/>WHERE key=?"]

    InsertNewFlag --> RecordChange["INSERT INTO flag_audit_log<br/>(flag_id, action, old_value, new_value, changed_by, changed_at)"]
    UpdateExistingFlag --> RecordChange

    RecordChange --> CommitDB["COMMIT transaction"]

    CommitDB --> ClearLocalCache["🗑️ Spring @CacheEvict<br/>Clear local Caffeine cache<br/>for this flagKey"]

    ClearLocalCache --> PublishRedis["🔴 Publish to Redis Channel<br/>Channel: flag-invalidations<br/>Message: 'flag-invalidation:{flagKey}'<br/><br/>Redis pub/sub broadcasts<br/>to all subscribers"]

    PublishRedis --> RedisOk{Redis<br/>publish<br/>success?}

    RedisOk -->|YES| CountSubscribers["Log subscriber count<br/>📊 Metric: featureflag.redis.pub.count"]
    RedisOk -->|NO| RedisFailed["⚠️ Redis publish failed<br/>Graceful fallback active"]

    CountSubscribers --> BuildAuditEvent["Build audit event:<br/>- flagKey<br/>- old values<br/>- new values<br/>- timestamp<br/>- admin user"]
    RedisFailed --> BuildAuditEvent

    BuildAuditEvent --> PublishKafka["📨 OPTIONAL: Publish to Kafka<br/>Topic: flag-updates<br/>(for analytics/audit trail)"]

    PublishKafka --> ReturnSuccess["✅ Return 200 OK<br/>Response: {flagKey, enabled, rollout%, variant}"]

    ReturnSuccess --> End([End])
    Forbidden --> End
    InvalidReq --> End

    style Start fill:#2196F3,color:#fff
    style End fill:#4CAF50,color:#fff
    style BeginTransaction fill:#FF9800,color:#fff
    style PublishRedis fill:#E74C3C,color:#fff
    style ClearLocalCache fill:#8BC34A,color:#fff
    style CommitDB fill:#4CAF50,color:#fff
```

---

## Redis Pub/Sub Invalidation Flow (Wave 35)

### Cache Invalidation Across All 3 Replicas

```mermaid
flowchart TD
    Pod1["Pod 1 (Admin)<br/>Updates flag"]
    Pod2["Pod 2 (Subscriber)"]
    Pod3["Pod 3 (Subscriber)"]

    Redis["🔴 Redis Pub/Sub<br/>Channel: flag-invalidations"]

    Pod1 --> Publish["1️⃣ Publish Message<br/>flag-invalidation:new_checkout_ui"]

    Publish --> Redis

    Redis --> Sub2["2️⃣ Pod 2 receives message"]
    Redis --> Sub3["2️⃣ Pod 3 receives message"]
    Redis --> SubPod1["2️⃣ Pod 1 also receives<br/>its own message"]

    Sub2 --> Handle2["3️⃣ FlagCacheInvalidator<br/>handleInvalidationMessage()"]
    Sub3 --> Handle3["3️⃣ FlagCacheInvalidator<br/>handleInvalidationMessage()"]
    SubPod1 --> HandlePod1["3️⃣ FlagCacheInvalidator<br/>handleInvalidationMessage()"]

    Handle2 --> Evict2["4️⃣ Spring @CacheEvict<br/>Clear Caffeine cache entry<br/>for 'new_checkout_ui'"]
    Handle3 --> Evict3["4️⃣ Spring @CacheEvict<br/>Clear Caffeine cache entry<br/>for 'new_checkout_ui'"]
    HandlePod1 --> EvictPod1["4️⃣ Spring @CacheEvict<br/>Clear Caffeine cache entry<br/>for 'new_checkout_ui'"]

    Evict2 --> RecordMetric2["📊 Record latency:<br/>Publish → Receive → Evict<br/>(typical: 20-100ms)"]
    Evict3 --> RecordMetric3["📊 Record latency:<br/>Publish → Receive → Evict<br/>(typical: 20-100ms)"]
    EvictPod1 --> RecordMetricPod1["📊 Record latency:<br/>Publish → Receive → Evict<br/>(typical: 5-20ms)"]

    RecordMetric2 --> TimeLine["⏱️ All cache cleared<br/>within 100ms total<br/>(SLO: &lt;500ms ✅)"]
    RecordMetric3 --> TimeLine
    RecordMetricPod1 --> TimeLine

    TimeLine --> NextRequest["5️⃣ Next client request<br/>to ANY pod"]

    NextRequest --> CacheMiss["Cache MISS<br/>(entry was evicted)"]

    CacheMiss --> LoadFromDB["Load from PostgreSQL<br/>(has latest values)"]

    LoadFromDB --> CacheNew["Cache new value<br/>for 30s TTL"]

    CacheNew --> ReturnNew["Return CURRENT flag value<br/>✅ All pods synchronized"]

    style Pod1 fill:#4CAF50,color:#fff
    style Pod2 fill:#4CAF50,color:#fff
    style Pod3 fill:#4CAF50,color:#fff
    style Redis fill:#E74C3C,color:#fff
    style TimeLine fill:#2196F3,color:#fff
    style ReturnNew fill:#8BC34A,color:#fff
```

---

## Fallback Behavior (Redis Unavailable)

### Grace Degradation When Redis Down

```mermaid
flowchart TD
    UpdateFlag["Admin updates flag<br/>in PostgreSQL"]

    UpdateFlag --> LocalEvict["Clear local Caffeine cache<br/>(Pod A only)"]

    LocalEvict --> PublishRedis{"Try to publish<br/>to Redis pub/sub?"}

    PublishRedis -->|RedisConnectionFailureException| CatchError["🔴 Redis unavailable<br/>Circuit breaker trips"]

    PublishRedis -->|SUCCESS| NormalFlow["Proceed with normal<br/>Wave 35 flow"]

    CatchError --> LogWarning["⚠️ Log warning:<br/>'Redis pub/sub unavailable'"]

    LogWarning --> FallbackMetric["📊 Increment<br/>featureflag.redis.failures"]

    FallbackMetric --> FallbackMode["Enter GRACEFUL FALLBACK MODE"]

    FallbackMode --> Pod2Stale["Pod 2 still has cached flag<br/>(stale for next 30s)"]
    FallbackMode --> Pod3Stale["Pod 3 still has cached flag<br/>(stale for next 30s)"]

    Pod2Stale --> TTLExpire["After 30s TTL expiration<br/>Caffeine auto-evicts entry"]
    Pod3Stale --> TTLExpire

    TTLExpire --> Pod2Reload["Pod 2 reloads from DB<br/>on next request<br/>(guaranteed within 30s)"]
    TTLExpire --> Pod3Reload["Pod 3 reloads from DB<br/>on next request<br/>(guaranteed within 30s)"]

    Pod2Reload --> Synchronized["✅ All pods eventually<br/>synchronized<br/>(within 30s worst case)"]
    Pod3Reload --> Synchronized

    NormalFlow --> QuickSync["✅ All pods synchronized<br/>within 100ms<br/>(Redis pub/sub)"]

    CatchError --> MonitorRecovery["🔄 Background monitor<br/>checks Redis connectivity<br/>every 5s"]

    MonitorRecovery --> RedisBetter{Redis<br/>recovered?}

    RedisBetter -->|YES| RestoreNormal["Restore normal<br/>Wave 35 operation"]
    RedisBetter -->|NO| ContinueFallback["Continue TTL-based<br/>fallback"]

    RestoreNormal --> End1([Back to normal<br/>Wave 35 SLO &lt;500ms])
    ContinueFallback --> End2([Degraded mode SLO<br/>eventual consistency 30s])
    Synchronized --> End3([End: Synchronized<br/>via TTL expiration])
    QuickSync --> End4([End: Fast sync<br/>via Redis])

    style UpdateFlag fill:#2196F3,color:#fff
    style CatchError fill:#E74C3C,color:#fff
    style FallbackMode fill:#FF9800,color:#fff
    style Synchronized fill:#4CAF50,color:#fff
    style RestoreNormal fill:#4CAF50,color:#fff
```

---

## Experiment Assignment Logic

### Deterministic Variant Assignment

```mermaid
flowchart TD
    Request["GET /flags/new_checkout_ui<br/>?userId=550e8400-e29b-41d4<br/>&context={platform:iOS}"]

    Request --> HasExperiment{Flag has active<br/>A/B experiment?}

    HasExperiment -->|NO| ReturnDefaultVariant["Return default variant<br/>(e.g., v1)"]

    HasExperiment -->|YES| QueryAssignment["Query experiment_assignments<br/>WHERE experiment_id = ?<br/>AND user_id = ?"]

    QueryAssignment --> AssignmentExists{Assignment<br/>exists?}

    AssignmentExists -->|YES| ReturnExisting["Return existing assignment<br/>variant: A or B<br/><br/>✅ Deterministic:<br/>Same user always gets<br/>same variant on retry"]

    AssignmentExists -->|NO| GenerateHash["Generate consistent hash:<br/>MD5(userId + experimentId)<br/>= abc123def456<br/><br/>hash % 100 = 45"]

    GenerateHash --> CompareAllocation["allocation_pct = 50<br/>Compare: 45 < 50?"]

    CompareAllocation -->|YES| AssignB["Assign variant B<br/>(experimental)"]
    CompareAllocation -->|NO| AssignA["Assign variant A<br/>(control)"]

    AssignA --> PersistA["INSERT INTO<br/>experiment_assignments<br/>(experiment_id, user_id,<br/>assigned_variant='A',<br/>assigned_at=NOW())"]

    AssignB --> PersistB["INSERT INTO<br/>experiment_assignments<br/>(experiment_id, user_id,<br/>assigned_variant='B',<br/>assigned_at=NOW())"]

    PersistA --> RecordAssignment["✅ Assignment recorded<br/>for analytics<br/>and consistency"]
    PersistB --> RecordAssignment

    RecordAssignment --> ReturnVariant["Return FlagEvaluationResponse<br/>enabled: true<br/>variant: A or B"]

    ReturnVariant --> NextRequest["📱 Next request from<br/>same user (after pod restart)"]

    NextRequest --> QueryAgain["Query assignment again<br/>(within same pod or<br/>different pod)"]

    QueryAgain --> CacheHit["✅ Found existing assignment<br/>Return same variant<br/>DETERMINISTIC"]

    ReturnDefaultVariant --> End1([End])
    CacheHit --> End2([End])

    style Request fill:#2196F3,color:#fff
    style GenerateHash fill:#9C27B0,color:#fff
    style RecordAssignment fill:#4CAF50,color:#fff
    style CacheHit fill:#8BC34A,color:#fff
```

---

## Caching Strategy: Hit vs Miss

```mermaid
flowchart TD
    ClientReq["Client: GET /flags/new_checkout_ui<br/>?userId=550e8400"]

    ClientReq --> CacheKey["Generate cache key:<br/>featureFlags:<br/>new_checkout_ui:550e8400:<br/>contextHash123"]

    CacheKey --> Lookup{"Caffeine Cache<br/>Lookup"}

    Lookup -->|HIT| RecordHit["📊 metric:<br/>cache.caffeine.hits++"]
    Lookup -->|MISS| RecordMiss["📊 metric:<br/>cache.caffeine.misses++"]

    RecordHit --> ReturnCached["⚡ Return cached flag<br/>Latency: &lt; 5ms"]

    RecordMiss --> QueryDB["🗄️ Query PostgreSQL<br/>SELECT * FROM feature_flags<br/>WHERE key = 'new_checkout_ui'"]

    QueryDB --> EvaluateRules["Evaluate rollout rules<br/>&lt;= 30ms typically"]

    EvaluateRules --> CheckExp["Check A/B experiment"]

    CheckExp --> BuildResp["Build response<br/>enabled: boolean<br/>variant: String"]

    BuildResp --> PopulateCache["💾 Populate Caffeine<br/>cache[cacheKey] = response<br/>with 30s TTL"]

    PopulateCache --> RecordLatency["⏱️ Record latency<br/>(typical: 30-50ms)"]

    RecordLatency --> ReturnFromDB["✅ Return to client<br/>Latency: 30-50ms"]

    RecordHit --> UpdateMetrics["Update metrics:<br/>- hitRate: hits/(hits+misses)<br/>- Target: &gt; 95%"]
    ReturnFromDB --> UpdateMetrics

    UpdateMetrics --> Alert{"hitRate<br/>&lt; 80%?"}

    Alert -->|YES| AlertOps["🚨 Alert on-call:<br/>investigate cache churn<br/>or burst traffic"]
    Alert -->|NO| End([End])

    AlertOps --> End

    style Lookup fill:#FFC107,color:#000
    style ReturnCached fill:#4CAF50,color:#fff
    style QueryDB fill:#FF9800,color:#fff
    style PopulateCache fill:#8BC34A,color:#fff
    style AlertOps fill:#E74C3C,color:#fff
```

