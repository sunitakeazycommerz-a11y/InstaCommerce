# Fraud Detection Service - Fraud Scoring Flowchart

```mermaid
flowchart TD
    A["Incoming Request<br/>/fraud/check"] --> B["Parse Request"]
    B --> C["Extract Context<br/>(amount, device_id, ip_address)"]
    C --> D["Check Blocklist<br/>customer or payment method"]
    D --> E{Blocked?}
    E -->|Yes| F["Score = 100<br/>Action = BLOCK"]
    E -->|No| G["Load Active Rules<br/>from Caffeine Cache"]
    G --> H["Evaluate VELOCITY Rule<br/>1h and 24h windows"]
    H --> I["Evaluate AMOUNT Rule<br/>threshold checks"]
    I --> J["Evaluate DEVICE Rule<br/>fingerprint deviation"]
    J --> K["Evaluate GEO Rule<br/>Haversine distance"]
    K --> L["Evaluate PATTERN Rule<br/>anomaly detection"]
    L --> M["Aggregate Score<br/>sum of triggered rule impacts"]
    M --> N["Determine RiskLevel<br/>from score"]
    N --> O{RiskLevel?}
    O -->|0-25| P["LOW → ALLOW"]
    O -->|26-50| Q["MEDIUM → FLAG"]
    O -->|51-75| R["HIGH → REVIEW"]
    O -->|76-100| S["CRITICAL → BLOCK"]
    F --> T["Build Response"]
    P --> T
    Q --> T
    R --> T
    S --> T
    T --> U["Persist FraudSignal<br/>to Database"]
    U --> V["Publish to Outbox<br/>for CDC relay"]
    V --> W["Emit Metrics<br/>counters + latency"]
    W --> X["Return FraudCheckResponse"]
```

## Flow Details

1. **Blocklist Check**: Early exit if customer/payment method blocked
2. **Rule Loading**: Fetch from Caffeine cache (invalidated on admin update)
3. **5-Stage Rule Evaluation**: VELOCITY → AMOUNT → DEVICE → GEO → PATTERN
4. **Score Aggregation**: Sum impact scores from triggered rules
5. **Risk Level Mapping**: Score → RiskLevel (LOW/MEDIUM/HIGH/CRITICAL)
6. **Action Determination**: RiskLevel → Action (ALLOW/FLAG/REVIEW/BLOCK)
7. **Signal Persistence**: Store fraud_signal for audit and trend analysis
8. **Outbox Publishing**: Transactional fraud.events for downstream
9. **Metrics Emission**: Per-rule triggers and overall scoring latency
