# Config & Feature Flag Service - Sequence Diagrams

## Sequence 1: Flag Evaluation Request (Cache Hit)

```mermaid
sequenceDiagram
    actor Client
    participant LB as Load Balancer
    participant Pod as config-service<br/>Pod 1
    participant Cache as Caffeine<br/>Cache
    participant DB as PostgreSQL

    Client->>LB: GET /flags/new_checkout_ui<br/>?userId=550e8400&platform=iOS
    LB->>Pod: Route to Pod 1

    Pod->>Pod: Validate JWT<br/>Extract userId, context

    Pod->>Pod: Generate cache key:<br/>"flags:new_checkout_ui:550e8400:hash123"

    Pod->>Cache: Check cache<br/>cache.getIfPresent(key)

    Cache-->>Pod: HIT<br/>FlagEvaluationResponse {<br/>  enabled: true,<br/>  variant: v2<br/>}

    Pod->>Pod: 📊 Increment<br/>cache.caffeine.hits

    Pod->>Pod: ⏱️ Record latency<br/>~5ms (cached)

    Pod-->>LB: 200 OK

    LB-->>Client: {<br/>  key: "new_checkout_ui",<br/>  enabled: true,<br/>  variant: "v2",<br/>  reason: "Cached",<br/>  latency_ms: 5<br/>}

    Note over Pod,Cache: Latency: <5ms ✅<br/>Cache hit rate improves 📈
```

---

## Sequence 2: Flag Evaluation Request (Cache Miss)

```mermaid
sequenceDiagram
    actor Client
    participant LB as Load Balancer
    participant Pod as config-service<br/>Pod 2
    participant Cache as Caffeine<br/>Cache
    participant DB as PostgreSQL
    participant Rules as RolloutRuleEngine

    Client->>LB: GET /flags/dark_mode<br/>?userId=aabbccdd

    LB->>Pod: Route to Pod 2

    Pod->>Pod: Generate cache key:<br/>"flags:dark_mode:aabbccdd:hash456"

    Pod->>Cache: Check cache

    Cache-->>Pod: MISS<br/>(entry expired or new flag)

    Pod->>Pod: 📊 Increment<br/>cache.caffeine.misses

    Pod->>DB: SELECT * FROM feature_flags<br/>WHERE key = 'dark_mode'

    DB-->>Pod: Flag {<br/>  id: uuid1,<br/>  key: "dark_mode",<br/>  enabled: true,<br/>  rollout_percentage: 75<br/>}

    Pod->>Rules: evaluateRules(<br/>flag, userId, context)

    Rules->>DB: SELECT * FROM flag_rollout_rules<br/>WHERE flag_id = uuid1<br/>ORDER BY order_index

    DB-->>Rules: [<br/>  {type: PERCENTAGE, pct: 75},<br/>  {type: USER_OVERRIDE, ...}<br/>]

    Rules->>Rules: Rule 1: hash(userId) % 100<br/>= 42, 42 < 75 ✓

    Rules-->>Pod: true<br/>(User eligible)

    Pod->>Pod: Check for A/B experiment<br/>(None active)

    Pod->>Cache: cache.put(key, response)<br/>TTL: 30s

    Pod->>Pod: ⏱️ Record latency<br/>~30ms (DB query + rules)

    Pod-->>LB: 200 OK

    LB-->>Client: {<br/>  key: "dark_mode",<br/>  enabled: true,<br/>  variant: null,<br/>  reason: "Gradual rollout 75%",<br/>  latency_ms: 30<br/>}

    Note over DB,Rules: Latency: 30-50ms<br/>Cached for next 30s ⏱️
```

---

## Sequence 3: Flag Update with Redis Pub/Sub (Wave 35)

### Admin Updates Flag → All Pods Invalidate Cache

```mermaid
sequenceDiagram
    actor Admin
    participant LB as Load Balancer
    participant Pod1 as config-service<br/>Pod 1
    participant DB as PostgreSQL
    participant Redis as Redis Pub/Sub
    participant Pod2 as config-service<br/>Pod 2
    participant Pod3 as config-service<br/>Pod 3
    participant Cache2 as Caffeine<br/>Pod 2
    participant Cache3 as Caffeine<br/>Pod 3

    Admin->>LB: PUT /flags/new_checkout_ui<br/>Request: {enabled: true, rollout_pct: 50}

    LB->>Pod1: Route to Pod 1

    Pod1->>Pod1: Validate JWT + ADMIN role

    Pod1->>DB: BEGIN TRANSACTION

    DB-->>Pod1: OK

    Pod1->>DB: UPDATE feature_flags<br/>SET enabled = true,<br/>rollout_percentage = 50,<br/>updated_at = NOW()<br/>WHERE key = 'new_checkout_ui'

    DB-->>Pod1: 1 row updated

    Pod1->>DB: COMMIT

    DB-->>Pod1: OK

    Pod1->>Pod1: 🗑️ @CacheEvict<br/>Clear local Caffeine<br/>for 'new_checkout_ui'

    Pod1->>Redis: 🔴 PUBLISH<br/>Channel: flag-invalidations<br/>Message: "flag-invalidation:new_checkout_ui"

    Redis-->>Pod1: ✅ Published to 3 subscribers

    Pod1->>Pod1: 📊 Metric:<br/>featureflag.redis.pub.count++

    activate Pod2
    activate Pod3

    Redis->>Pod2: 📨 Message received<br/>"flag-invalidation:new_checkout_ui"

    Redis->>Pod3: 📨 Message received<br/>"flag-invalidation:new_checkout_ui"

    Note over Redis: Wave 35: Pub/Sub<br/>Propagation: ~20ms

    Pod2->>Pod2: FlagCacheInvalidator<br/>handleInvalidationMessage()

    Pod3->>Pod3: FlagCacheInvalidator<br/>handleInvalidationMessage()

    Pod2->>Cache2: @CacheEvict<br/>Remove entry for<br/>'new_checkout_ui'

    Pod3->>Cache3: @CacheEvict<br/>Remove entry for<br/>'new_checkout_ui'

    Pod2->>Pod2: ⏱️ Record latency<br/>~15ms (received to evicted)

    Pod3->>Pod3: ⏱️ Record latency<br/>~12ms (received to evicted)

    deactivate Pod2
    deactivate Pod3

    Pod1-->>LB: 200 OK

    LB-->>Admin: ✅ Flag updated<br/>All 3 pods invalidated<br/>Total time: <100ms

    Note over Pod1,Pod3: Wave 35 SLO: <500ms ✅<br/>Actual: ~40ms
    Note over Redis: All replicas synchronized<br/>within Redis latency (20-50ms)
```

---

## Sequence 4: A/B Experiment Assignment (Deterministic)

```mermaid
sequenceDiagram
    participant Client
    participant Pod as config-service<br/>Pod
    participant ExperimentService as ExperimentService
    participant DB_Assign as PostgreSQL<br/>experiment_assignments
    participant DB_Flag as PostgreSQL<br/>feature_flags

    Client->>Pod: GET /flags/checkout_experiment<br/>?userId=user-123

    Pod->>DB_Flag: SELECT flag with active<br/>experiment

    DB_Flag-->>Pod: Flag with experiment:<br/>allocation_pct: 50

    Pod->>ExperimentService: getOrCreateAssignment(<br/>experimentId, userId, 50)

    ExperimentService->>DB_Assign: SELECT * FROM<br/>experiment_assignments<br/>WHERE experiment_id = X<br/>AND user_id = user-123

    DB_Assign-->>ExperimentService: RESULT: empty<br/>(First-time user)

    ExperimentService->>ExperimentService: Generate consistent hash:<br/>MD5(user-123:experimentX)<br/>hash % 100 = 35

    ExperimentService->>ExperimentService: Compare: 35 < 50?<br/>YES → Assign variant B<br/>(experimental)

    ExperimentService->>DB_Assign: INSERT INTO experiment_assignments<br/>({experiment_id: X,<br/>user_id: user-123,<br/>assigned_variant: 'B',<br/>assigned_at: NOW()})

    DB_Assign-->>ExperimentService: OK

    ExperimentService-->>Pod: ExperimentAssignment {<br/>variant: 'B'<br/>}

    Pod-->>Client: 200 OK {<br/>  key: "checkout_experiment",<br/>  enabled: true,<br/>  variant: "B",<br/>  reason: "A/B experiment"<br/>}

    Note over Client,ExperimentService: ✅ Deterministic:<br/>Same hash every time

    Client->>Pod: GET /flags/checkout_experiment<br/>?userId=user-123<br/>(after Pod restart)

    Pod->>ExperimentService: getOrCreateAssignment(<br/>experimentId, userId, 50)

    ExperimentService->>DB_Assign: SELECT * FROM<br/>experiment_assignments

    DB_Assign-->>ExperimentService: RESULT: found<br/>variant: 'B'

    ExperimentService-->>Pod: Return existing<br/>variant: 'B'

    Pod-->>Client: 200 OK {<br/>  variant: 'B'<br/>}

    Note over Client: Same user always gets<br/>same variant (consistency)
```

---

## Sequence 5: Cache Invalidation Fallback (Redis Down)

```mermaid
sequenceDiagram
    actor Admin
    participant Pod1 as config-service<br/>Pod 1
    participant DB as PostgreSQL
    participant Redis as Redis<br/>DOWN ❌
    participant Pod2 as config-service<br/>Pod 2
    participant Cache2 as Caffeine<br/>Pod 2

    Admin->>Pod1: PUT /flags/loyalty_program<br/>(Update flag)

    Pod1->>DB: UPDATE feature_flags

    DB-->>Pod1: OK ✅

    Pod1->>Pod1: 🗑️ Clear local cache

    Pod1->>Redis: PUBLISH flag-invalidation:loyalty_program

    Redis-->>Pod1: ❌ RedisConnectionFailureException<br/>Connection timeout

    Pod1->>Pod1: Catch exception

    Pod1->>Pod1: ⚠️ Log warning:<br/>'Redis unavailable'

    Pod1->>Pod1: 📊 Increment<br/>featureflag.redis.failures++

    Note over Pod1,Redis: Wave 35 Resilience:<br/>Graceful fallback active

    Pod1-->>Admin: ✅ 200 OK<br/>(Flag updated in DB)<br/>(Redis fallback: TTL)

    Admin->>Pod2: GET /flags/loyalty_program<br/>(within 5 seconds)

    Pod2->>Cache2: Check cache

    Cache2-->>Pod2: HIT<br/>(Entry still cached)<br/>30s TTL remaining

    Pod2-->>Admin: Returns STALE value<br/>⚠️ Stale flag (Redis down)

    Note over Pod2: Pod 2 serves stale<br/>for up to 30s

    rect rgb(255, 200, 0)
    Note over Pod1,Cache2: FALLBACK: TTL Expiration<br/>30s timeout ensures<br/>eventual consistency
    end

    Cache2->>Cache2: After 30s TTL<br/>Entry expires

    Admin->>Pod2: GET /flags/loyalty_program<br/>(after 35 seconds)

    Pod2->>Cache2: Check cache

    Cache2-->>Pod2: MISS (expired)

    Pod2->>DB: Query current flag

    DB-->>Pod2: Current value

    Pod2-->>Admin: ✅ Returns CURRENT value<br/>(Eventually consistent)

    Note over Pod2,DB: Consistency guaranteed<br/>within 30s TTL
```

---

## Sequence 6: Bulk Flag Evaluation (Mobile Optimization)

```mermaid
sequenceDiagram
    actor Mobile as Mobile App<br/>iOS
    participant LB as Load Balancer
    participant Pod as config-service
    participant Cache as Caffeine<br/>Cache
    participant DB as PostgreSQL

    Mobile->>LB: POST /flags/bulk<br/>Request: {<br/>  keys: [dark_mode,<br/>         new_checkout,<br/>         loyalty],<br/>  userId: user-uuid,<br/>  context: {platform: iOS}<br/>}

    LB->>Pod: Route request

    Pod->>Pod: Validate JWT

    Pod->>Pod: Build composite cache key:<br/>"bulk:user-uuid:hash"

    Pod->>Cache: Check if composite<br/>entry cached

    alt Cache Hit (All flags)
        Cache-->>Pod: HIT<br/>All 3 flags cached
        Pod->>Pod: 📊 cache.hits++
        Pod-->>Mobile: 200 OK {<br/>  flags: {<br/>    dark_mode: {...},<br/>    new_checkout: {...},<br/>    loyalty: {...}<br/>  },<br/>  cacheKey: "cache123"<br/>}
        Note over Mobile,Pod: Latency: <5ms<br/>Single request ✅
    else Cache Miss (Any flag)
        Cache-->>Pod: MISS
        Pod->>Pod: 📊 cache.misses++

        Pod->>DB: SELECT all flags<br/>WHERE key IN (dark_mode,<br/>new_checkout, loyalty)

        DB-->>Pod: 3 flag records

        Pod->>Pod: Evaluate each flag<br/>with user context

        Pod->>DB: Load rules & experiments

        Pod->>Pod: Build bulk response

        Pod->>Cache: Store composite entry<br/>TTL: 30s

        Pod-->>Mobile: 200 OK {<br/>  flags: {...},<br/>  cacheKey: "cache123"<br/>}
        Note over Pod,DB: Latency: 20-50ms<br/>All flags together ✅
    end

    Note over Mobile: Next request:<br/>Mobile can use cacheKey<br/>for delta updates
```

---

## Sequence 7: Circuit Breaker Activation (PostgreSQL Down)

```mermaid
sequenceDiagram
    participant Client
    participant Pod as config-service
    participant CB as Circuit Breaker
    participant DB as PostgreSQL<br/>DOWN ❌
    participant Cache as Caffeine<br/>Cache

    Client->>Pod: GET /flags/new_feature

    Pod->>CB: isOpen()? (5 failures tracked)

    alt Circuit CLOSED (Normal)
        CB-->>Pod: No, proceed
        Pod->>DB: Query feature_flags
        DB-->>Pod: ❌ Connection timeout<br/>Failure #1
        Pod->>CB: recordFailure()
    else Circuit OPEN (After 5 failures)
        CB-->>Pod: Yes, OPEN<br/>Skip DB call
        Pod->>Cache: Serve from cache<br/>(stale but available)
        Cache-->>Pod: Flag {<br/>  enabled: false,<br/>  stale: true<br/>}
        Pod-->>Client: 200 OK {<br/>  enabled: false,<br/>  reason: "Stale from cache"<br/>}
        Note over Pod,Cache: Graceful degradation<br/>SLO maintained 🟡
    else Circuit HALF_OPEN (Recovery)
        Note over CB: After 30s wait
        CB-->>Pod: Try DB again<br/>1 request allowed
        Pod->>DB: Query (retry)
        DB-->>Pod: ✅ Connection OK
        Pod->>CB: recordSuccess()
        CB->>CB: Reset failure count
        CB->>CB: CLOSED (normal)
    end

    Note over Pod,DB: Resilience Pattern:<br/>Fail fast + graceful fallback<br/>Maintains availability
```

