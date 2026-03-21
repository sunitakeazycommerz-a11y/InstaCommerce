# Config & Feature Flag Service - State Machine & State Diagrams

## Flag Lifecycle State Machine

### States and Transitions

```mermaid
stateDiagram-v2
    [*] --> Draft: Admin creates flag

    Draft --> Active: Admin enables flag<br/>PUT /flags/{key}<br/>enabled: true

    Active --> Active: Admin updates rollout %<br/>Admin updates segments<br/>Admin updates rules

    Active --> Suspended: Admin suspends flag<br/>enabled: false<br/>(Emergency kill switch)

    Suspended --> Active: Admin re-enables flag<br/>enabled: true

    Suspended --> Retired: Admin marks as retired<br/>status: RETIRED

    Active --> Retired: Gradual deprecation<br/>rollout_pct: 0<br/>then delete

    Retired --> [*]: Archive completed

    note right of Draft
        State: DRAFT
        enabled: false
        Created but not active
        No users affected
    end

    note right of Active
        State: ACTIVE
        enabled: true
        Serving users
        Rules being evaluated
        A/B experiments running
    end

    note right of Suspended
        State: SUSPENDED
        enabled: false
        Quick kill-switch
        Cache invalidated across pods
        Users see consistent state
    end

    note right of Retired
        State: RETIRED
        No users affected
        Safe to delete after
        X days (audit window)
    end

    style Draft fill:#FFC107,color:#000
    style Active fill:#4CAF50,color:#fff
    style Suspended fill:#E74C3C,color:#fff
    style Retired fill:#9E9E9E,color:#fff
```

---

## Flag Update State (Cache Invalidation)

### Cache State Throughout Update Operation

```mermaid
stateDiagram-v2
    [*] --> CachedValid: Normal operation<br/>Caffeine holds<br/>valid flag

    CachedValid --> CacheClear: Admin updates flag<br/>DB write successful<br/>@CacheEvict triggered

    CacheClear --> InvalidationPublish: Publish to Redis<br/>flag-invalidation:{key}<br/>message sent

    InvalidationPublish --> PropagatingAllPods: Redis pub/sub<br/>broadcasts to<br/>all 3 pods

    PropagatingAllPods --> AllPodsCleared: All pods receive message<br/>Evict from Caffeine<br/>within 20-100ms

    AllPodsCleared --> CacheMiss: Next client request<br/>hits Pod 1, 2, or 3

    CacheMiss --> CacheMiss: All pods<br/>experiencing cache miss<br/>for this flag

    CacheMiss --> ReloadFromDB: Query PostgreSQL<br/>Load current flag<br/>Load rollout rules

    ReloadFromDB --> CachePopulated: Store result in Caffeine<br/>TTL: 30s

    CachePopulated --> CachedValid: Next N requests<br/>for 30s served<br/>from cache

    InvalidationPublish -.->|Redis unavailable| TTLFallback: Graceful fallback<br/>Wait for TTL<br/>expiration

    TTLFallback -->|After 30s| CacheMiss

    note right of CachedValid
        Pod A, B, C all have
        same flag in cache
        Consistent state
    end

    note right of CacheClear
        Pod A clears local cache
        immediately on update
    end

    note right of InvalidationPublish
        Wave 35 optimization
        <500ms SLO
    end

    note right of PropagatingAllPods
        Typical: 20-100ms
        Via Redis pub/sub
    end

    note right of AllPodsCleared
        All replicas synchronized
        Consistent cache state
    end

    note right of TTLFallback
        30s TTL ensures
        eventual consistency
        if Redis down
    end

    style CachedValid fill:#4CAF50,color:#fff
    style CacheClear fill:#2196F3,color:#fff
    style InvalidationPublish fill:#E74C3C,color:#fff
    style PropagatingAllPods fill:#FF9800,color:#fff
    style AllPodsCleared fill:#8BC34A,color:#fff
    style CacheMiss fill:#FFC107,color:#000
    style ReloadFromDB fill:#2196F3,color:#fff
    style CachePopulated fill:#4CAF50,color:#fff
    style TTLFallback fill:#FF9800,color:#fff
```

---

## Redis Pub/Sub Subscription Lifecycle

### Subscriber Connection States

```mermaid
stateDiagram-v2
    [*] --> Initializing: Pod startup<br/>@PostConstruct method

    Initializing --> Connecting: Create Redis<br/>connection

    Connecting --> SubscribingToChannel: Send SUBSCRIBE<br/>command

    SubscribingToChannel --> ListeningForMessages: Listening for messages<br/>on channel<br/>flag-invalidations

    ListeningForMessages --> ListeningForMessages: Receive message<br/>(flag invalidation)

    ListeningForMessages --> MessageReceived: Message handler<br/>triggered

    MessageReceived --> ParseMessage: Extract flagKey<br/>from message

    ParseMessage --> EvictFromCache: Trigger @CacheEvict<br/>Remove from Caffeine

    EvictFromCache --> ListeningForMessages: Resume listening

    ListeningForMessages --> ConnectionLost: Network failure<br/>or timeout<br/>or Redis down

    ConnectionLost --> ReconnectAttempt: Retry connection<br/>exponential backoff

    ReconnectAttempt --> ReconnectAttempt: Retry every 5s

    ReconnectAttempt --> Connecting: Connection<br/>restored

    Connecting --> ListeningForMessages

    ListeningForMessages --> ChannelUnsubscribe: Admin unsubscribe<br/>or pod shutdown

    ChannelUnsubscribe --> Closed: Close connection

    Closed --> [*]

    note right of Initializing
        Pod boot
        Spring context loaded
    end

    note right of ListeningForMessages
        Blocking operation
        Separate thread
    end

    note right of MessageReceived
        Latency: ~5-20ms
        Record metric
    end

    note right of ConnectionLost
        Failover: local TTL
        graceful degradation
    end

    style Initializing fill:#FFC107,color:#000
    style Connecting fill:#2196F3,color:#fff
    style ListeningForMessages fill:#4CAF50,color:#fff
    style MessageReceived fill:#9C27B0,color:#fff
    style ConnectionLost fill:#E74C3C,color:#fff
    style ReconnectAttempt fill:#FF9800,color:#fff
    style Closed fill:#9E9E9E,color:#fff
```

---

## Experiment Status Lifecycle

### Experiment State Transitions

```mermaid
stateDiagram-v2
    [*] --> Planned: Admin creates<br/>experiment config

    Planned --> Running: Start date reached<br/>or admin manually<br/>starts

    Running --> Running: Users assigned to<br/>variants A or B<br/>based on hash

    Running --> Completed: End date reached<br/>or admin ends early

    Completed --> AnalysisInProgress: Collect results<br/>Segment A vs B users

    AnalysisInProgress --> AnalysisInProgress: Generate report<br/>Statistical significance<br/>Winner determined

    AnalysisInProgress --> Concluded: Analysis complete<br/>Publish results

    Concluded --> Archived: Keep for audit<br/>then delete after<br/>30 days

    Concluded --> Rolled_Out: Winner becomes<br/>default (rollout)<br/>or killed

    Rolled_Out --> [*]: Experiment lifecycle<br/>complete

    note right of Planned
        Created but not active
        No users assigned yet
        Configuration locked
    end

    note right of Running
        Active experiment
        Users deterministically
        assigned to A or B
    end

    note right of Running
        Hash-based:
        MD5(userId+experimentId)
        Consistent across
        pod restarts
    end

    note right of Completed
        No new assignments
        Existing users keep
        their assignment
    end

    note right of AnalysisInProgress
        Wait for
        statistical significance
        or time window
    end

    note right of Concluded
        Results published
        Decision made
    end

    note right of Rolled_Out
        Roll out winner
        to 100% users
        or kill loser
    end

    style Planned fill:#FFC107,color:#000
    style Running fill:#4CAF50,color:#fff
    style Completed fill:#2196F3,color:#fff
    style AnalysisInProgress fill:#9C27B0,color:#fff
    style Concluded fill:#FF9800,color:#fff
    style Rolled_Out fill:#8BC34A,color:#fff
    style Archived fill:#9E9E9E,color:#fff
```

---

## Rollout Rule Evaluation State

### Rule Processing State Machine

```mermaid
stateDiagram-v2
    [*] --> LoadRules: Get all rules for flag<br/>ORDER BY order_index

    LoadRules --> NoRules: Any rules<br/>exist?

    NoRules -->|NO| UseDefault: Return flag.enabled<br/>(default)

    NoRules -->|YES| RuleIteration: Loop through<br/>rules in order

    RuleIteration --> CurrentRule: Get current rule<br/>rule_type = ?

    CurrentRule --> PercentageRule: PERCENTAGE<br/>type

    CurrentRule --> SegmentRule: SEGMENT<br/>type

    CurrentRule --> UserOverrideRule: USER_OVERRIDE<br/>type

    PercentageRule --> EvalPercentage: hash(userId) % 100<br/>< rule.percentage?

    EvalPercentage --> RuleMatched: YES<br/>Return rule.enabled

    EvalPercentage --> NextRule: NO<br/>Try next rule

    SegmentRule --> EvalSegment: userId.segments<br/>in rule.segments?

    EvalSegment --> RuleMatched: YES<br/>Return rule.enabled

    EvalSegment --> NextRule: NO<br/>Try next rule

    UserOverrideRule --> EvalOverride: userId<br/>in rule.target_users?

    EvalOverride --> RuleMatched: YES<br/>Return rule.enabled

    EvalOverride --> NextRule: NO<br/>Try next rule

    NextRule --> MoreRules: More rules<br/>to evaluate?

    MoreRules -->|YES| RuleIteration

    MoreRules -->|NO| UseDefault: No rules matched<br/>Use flag.enabled

    RuleMatched --> Eligible: Rule matched<br/>and enabled=true

    RuleMatched --> NotEligible: Rule matched<br/>and enabled=false

    UseDefault --> Eligible: flag.enabled<br/>= true

    UseDefault --> NotEligible: flag.enabled<br/>= false

    Eligible --> [*]: Return enabled: true

    NotEligible --> [*]: Return enabled: false

    note right of LoadRules
        Select all rules
        sorted by order_index
    end

    note right of RuleIteration
        Short-circuit on
        first match
    end

    note right of PercentageRule
        Consistent hash
        Same user always
        gets same result
    end

    note right of SegmentRule
        Check user's
        segment membership
    end

    note right of UserOverrideRule
        Explicit user list
        Force enable/disable
    end

    style LoadRules fill:#2196F3,color:#fff
    style RuleIteration fill:#9C27B0,color:#fff
    style PercentageRule fill:#FF9800,color:#fff
    style SegmentRule fill:#FF9800,color:#fff
    style UserOverrideRule fill:#FF9800,color:#fff
    style RuleMatched fill:#4CAF50,color:#fff
    style Eligible fill:#4CAF50,color:#fff
    style NotEligible fill:#E74C3C,color:#fff
    style UseDefault fill:#8BC34A,color:#fff
```

---

## Circuit Breaker State Machine (PostgreSQL)

### Resilience Pattern States

```mermaid
stateDiagram-v2
    [*] --> Closed: Normal operation<br/>Database healthy

    Closed --> Closed: Request succeeds<br/>Reset failure count

    Closed --> Open: Failure count >= 5<br/>or failure rate > 50%

    Open --> Open: Requests rejected<br/>Serve from cache<br/>(stale)

    Open --> HalfOpen: After 30s<br/>wait duration

    HalfOpen --> HalfOpen: Allow 1 request<br/>to test DB

    HalfOpen --> Closed: Request succeeds<br/>DB recovered

    HalfOpen --> Open: Request fails<br/>DB still down

    note right of Closed
        NORMAL STATE
        Query PostgreSQL directly
        Cache results
    end

    note right of Open
        FAILURE STATE
        Skip DB calls
        Serve from Caffeine
        Stale data
        Error rate: 0%
        (Still available!)
    end

    note right of HalfOpen
        RECOVERY STATE
        Test if DB is back
        1 permitted call
    end

    note right of Closed
        SLO: 99.99%
        Availability
        maintained
    end

    style Closed fill:#4CAF50,color:#fff
    style Open fill:#E74C3C,color:#fff
    style HalfOpen fill:#FFC107,color:#000
```

---

## Cache Entry Lifecycle (Caffeine)

### Cache Entry State from Creation to Expiration

```mermaid
stateDiagram-v2
    [*] --> CacheEntry: First evaluation<br/>or miss<br/>Load from DB<br/>Store in Caffeine

    CacheEntry --> CacheValid: Entry valid<br/>TTL not expired<br/>Serve to clients

    CacheValid --> CacheValid: Subsequent requests<br/>within 30s<br/>Hit rate increases

    CacheValid --> CacheInvalidated: Admin updates flag<br/>Redis pub/sub message<br/>@CacheEvict

    CacheValid --> CacheExpired: 30s TTL<br/>passes

    CacheInvalidated --> CacheEmpty: Entry removed<br/>from Caffeine

    CacheExpired --> CacheEmpty: Entry auto-expired<br/>by Caffeine

    CacheEmpty --> CacheMiss: Next request<br/>for same flag

    CacheMiss --> LoadFromDB: Query PostgreSQL<br/>Rule evaluation

    LoadFromDB --> CacheEntry: Store result<br/>Reset TTL to 30s

    note right of CacheEntry
        Initial population
        DB latency: 30-50ms
        Entry created with TTL
    end

    note right of CacheValid
        Serving clients
        <5ms latency
        No DB load
    end

    note right of CacheInvalidated
        Wave 35 optimization
        Redis pub/sub broadcast
        All pods synchronized
        <100ms propagation
    end

    note right of CacheExpired
        TTL fallback
        Graceful expiration
        30s eventual consistency
    end

    note right of CacheEmpty
        Entry removed
        No memory waste
    end

    style CacheEntry fill:#2196F3,color:#fff
    style CacheValid fill:#4CAF50,color:#fff
    style CacheInvalidated fill:#E74C3C,color:#fff
    style CacheExpired fill:#FF9800,color:#fff
    style CacheEmpty fill:#FFC107,color:#000
    style CacheMiss fill:#9E9E9E,color:#fff
    style LoadFromDB fill:#2196F3,color:#fff
```

