# InstaCommerce — Data Platform & ML/AI Design Document

> **Version**: 1.0 | **Status**: Design Document
> **Scale Target**: 20M+ users, 500K+ daily orders, 10-minute delivery SLA
> **Benchmarks**: Zepto, Blinkit, Instacart, DoorDash

---

## 1. Executive Summary

### The Data Gap

InstaCommerce has 18 microservices generating events across every domain but **zero infrastructure** to capture, process, or learn from this data. Every Q-commerce competitor uses data/ML as a core competitive moat.

### Business Impact (Conservative Estimates at 500K orders/day, $7.50 AOV)

| ML Use Case | Expected Impact | Annual Revenue Impact |
|-------------|-----------------|----------------------|
| ML search ranking | +15% search conversion | +$46M |
| Demand forecasting | -30% stockouts | +$15M (reduced lost sales) |
| Dynamic pricing | +5% gross margin | +$15M |
| Fraud detection (ML) | Fraud 2% → 0.3% | +$7M (saved losses) |
| ETA prediction | ±5min → ±1.5min | Customer satisfaction |
| Personalization | +10% AOV | +$31M |
| Rider optimization | -2 min avg delivery | Operational efficiency |

**Total: $100M+ annually. Platform cost: $500K/year. ROI: 200:1**

### Recommended Approach

**GCP-native Lakehouse**: BigQuery (warehouse) + Cloud Storage (lake) + Dataflow (streaming) + Vertex AI (ML). Already on GKE; managed services reduce ops overhead.

---

## 2. Data Architecture

### 2.1 Lakehouse Architecture

```
 Operational          Streaming              Analytics             ML
 ──────────          ─────────              ─────────            ────
 18 PostgreSQL ─CDC─► Kafka ──Dataflow───► BigQuery ──────────► Vertex AI
 (Debezium)          Topics   (Beam)       (Warehouse)          (Models)
                       │                       │                    │
                       ▼                       ▼                    ▼
                  Cloud Storage            Looker/Grafana      Online Predict
                  (Data Lake)              (BI/Dashboards)     (Endpoints)
                       │
                       ▼
                  Feature Store
                  (Vertex AI)
```

### 2.2 Data Flow Patterns

| Pattern | Source | Pipeline | Sink | Latency | Use |
|---------|--------|----------|------|---------|-----|
| CDC | PostgreSQL WAL | Debezium→Kafka→Dataflow | BigQuery | <5 min | Analytics, reporting |
| Domain Events | Outbox pattern | Kafka→Dataflow | BigQuery | <1 min | Real-time metrics |
| Batch Training | BigQuery | Vertex AI Training | Model Registry | Daily | ML model training |
| Online Serving | Feature Store | Vertex AI Prediction | Service | <50ms | Real-time inference |

### 2.3 Service-to-Topic Mapping

| Service | CDC Topics | Domain Event Topics | Est. Events/Day |
|---------|-----------|---------------------|-----------------|
| order-service | orders, order_items, status_history | OrderPlaced, OrderCancelled | 2M |
| payment-service | payments, refunds, ledger | PaymentAuthorized, RefundCompleted | 1.5M |
| inventory-service | stock_levels, reservations | StockReserved, LowStockAlert | 5M |
| cart-service | carts, cart_items | CartUpdated, CartAbandoned | 10M |
| catalog-service | products, categories | ProductCreated, ProductUpdated | 50K |
| fulfillment-service | pick_tasks, deliveries | PickCompleted, DeliveryAssigned | 1.5M |
| rider-fleet-service | riders, shifts, earnings | RiderAssigned, DeliveryCompleted | 500K |

---

## 3. Data Ingestion Layer

### 3.1 CDC Pipeline (Debezium + Kafka Connect)

**Reference Debezium connector (order-service):**

```json
{
  "name": "order-db-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "order-db",
    "database.dbname": "orders",
    "plugin.name": "pgoutput",
    "slot.name": "debezium_orders",
    "topic.prefix": "cdc.orders",
    "table.include.list": "public.orders,public.order_items,public.order_status_history",
    "key.converter": "io.confluent.connect.avro.AvroConverter",
    "key.converter.schema.registry.url": "http://schema-registry:8081",
    "value.converter": "io.confluent.connect.avro.AvroConverter",
    "value.converter.schema.registry.url": "http://schema-registry:8081",
    "transforms": "unwrap",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "heartbeat.interval.ms": "10000",
    "snapshot.mode": "initial"
  }
}
```

**Deploy one connector per service database** — 18 total. Use Strimzi Kafka Connect on GKE for container-native management.

### 3.2 Real-Time Streaming (Cloud Dataflow / Apache Beam)

**Key streaming aggregations:**

| Aggregation | Window | Granularity | Output Table |
|------------|--------|------------|--------------|
| Orders per minute | Tumbling 1-min | Per store, zone, city | realtime_order_volume |
| Revenue (GMV) | Tumbling 1-min | Per store, total | realtime_revenue |
| Delivery SLA | Sliding 30-min | Per zone | realtime_sla_compliance |
| Cart abandonment | Session 15-min gap | Per user | realtime_cart_abandonment |
| Inventory velocity | Tumbling 1-hour | Per SKU, store | realtime_inventory_velocity |
| Rider utilization | Tumbling 5-min | Per zone | realtime_rider_utilization |
| Payment success | Tumbling 5-min | Per method | realtime_payment_health |

**Reference Beam pipeline (order events):**

```java
pipeline
    .apply("ReadKafka", KafkaIO.<String, String>read()
        .withBootstrapServers("kafka-bootstrap:9092")
        .withTopic("order.events")
        .withKeyDeserializer(StringDeserializer.class)
        .withValueDeserializer(StringDeserializer.class))
    .apply("ParseEvent", ParDo.of(new OrderEventParser()))
    .apply("WindowOneMin", Window.<OrderEvent>into(FixedWindows.of(Duration.standardMinutes(1))))
    .apply("CountByStore", Count.perKey())
    .apply("WriteBQ", BigQueryIO.writeTableRows()
        .to("analytics.realtime_order_volume")
        .withWriteDisposition(WriteDisposition.WRITE_APPEND)
        .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED));
```

### 3.3 Batch Processing (dbt + Cloud Composer)

**Example dbt model — Daily Revenue:**

```sql
-- models/marts/mart_daily_revenue.sql
SELECT
    DATE(placed_at) AS order_date,
    store_id,
    d.store_name,
    d.city,
    COUNT(DISTINCT order_id) AS total_orders,
    COUNT(DISTINCT user_id) AS unique_customers,
    SUM(total_cents) / 100.0 AS net_revenue,
    AVG(total_cents) / 100.0 AS avg_order_value,
    COUNTIF(delivery_time_minutes <= 10) / COUNT(*) * 100 AS sla_10min_pct,
    AVG(delivery_time_minutes) AS avg_delivery_minutes
FROM {{ ref('stg_orders') }} o
JOIN {{ ref('dim_stores') }} d USING (store_id)
GROUP BY 1, 2, 3, 4
```

**dbt layer structure:**

```
models/
  staging/          -- 1:1 with source tables, renamed/typed
    stg_orders.sql
    stg_payments.sql
    stg_products.sql
  intermediate/     -- business logic joins
    int_order_deliveries.sql
    int_user_order_history.sql
  marts/            -- final consumption layer
    mart_daily_revenue.sql
    mart_user_cohort_retention.sql
    mart_store_performance.sql
    mart_rider_performance.sql
```

**Cloud Composer (Airflow) DAG schedule:**

| DAG | Schedule | Duration | Dependencies |
|-----|----------|----------|--------------|
| dbt_staging | Every 2 hours | ~10 min | CDC landed |
| dbt_marts | Daily 02:00 UTC | ~45 min | Staging complete |
| ml_feature_refresh | Every 4 hours | ~20 min | Staging complete |
| ml_training | Daily 04:00 UTC | ~2 hours | Marts complete |
| data_quality | Every 2 hours | ~5 min | After staging |

---

## 4. Data Storage

### 4.1 BigQuery Star Schema

**Fact Tables** (append-only, partitioned by date):

```sql
CREATE TABLE `analytics.fact_orders` (
    order_id STRING NOT NULL,
    user_id STRING NOT NULL,
    store_id STRING NOT NULL,
    rider_id STRING,
    order_date DATE NOT NULL,
    status STRING NOT NULL,
    item_count INT64,
    subtotal_cents INT64,
    delivery_fee_cents INT64,
    discount_cents INT64,
    total_cents INT64,
    payment_method STRING,
    coupon_code STRING,
    placed_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP,
    picked_at TIMESTAMP,
    delivered_at TIMESTAMP,
    delivery_time_minutes FLOAT64,
    sla_met BOOL,
    is_first_order BOOL,
    channel STRING,        -- app, web
    platform STRING        -- ios, android, web
) PARTITION BY order_date
  CLUSTER BY store_id, status;

CREATE TABLE `analytics.fact_deliveries` (
    delivery_id STRING NOT NULL,
    order_id STRING NOT NULL,
    rider_id STRING NOT NULL,
    store_id STRING NOT NULL,
    delivery_date DATE NOT NULL,
    assigned_at TIMESTAMP,
    picked_up_at TIMESTAMP,
    delivered_at TIMESTAMP,
    distance_meters INT64,
    delivery_time_minutes FLOAT64,
    rider_rating FLOAT64,
    delivery_fee_cents INT64,
    tip_cents INT64,
    sla_met BOOL
) PARTITION BY delivery_date
  CLUSTER BY store_id, rider_id;

CREATE TABLE `analytics.fact_payments` (
    payment_id STRING NOT NULL,
    order_id STRING NOT NULL,
    user_id STRING NOT NULL,
    payment_date DATE NOT NULL,
    method STRING,
    amount_cents INT64,
    currency STRING,
    status STRING,
    psp_provider STRING,
    authorized_at TIMESTAMP,
    captured_at TIMESTAMP,
    refund_amount_cents INT64,
    refund_reason STRING
) PARTITION BY payment_date
  CLUSTER BY method, status;

CREATE TABLE `analytics.fact_inventory_movements` (
    movement_id STRING NOT NULL,
    product_id STRING NOT NULL,
    store_id STRING NOT NULL,
    movement_date DATE NOT NULL,
    movement_type STRING,     -- RECEIVED, SOLD, EXPIRED, DAMAGED, TRANSFERRED
    quantity INT64,
    running_stock INT64,
    cost_cents INT64
) PARTITION BY movement_date
  CLUSTER BY store_id, product_id;

CREATE TABLE `analytics.fact_searches` (
    search_id STRING NOT NULL,
    user_id STRING,
    search_date DATE NOT NULL,
    query STRING,
    results_count INT64,
    clicked_position INT64,
    added_to_cart BOOL,
    purchased BOOL,
    latency_ms INT64
) PARTITION BY search_date
  CLUSTER BY query;

CREATE TABLE `analytics.fact_cart_events` (
    event_id STRING NOT NULL,
    user_id STRING NOT NULL,
    cart_id STRING NOT NULL,
    event_date DATE NOT NULL,
    event_type STRING,        -- ADD, REMOVE, UPDATE_QTY, CHECKOUT, ABANDON
    product_id STRING,
    quantity INT64,
    unit_price_cents INT64,
    cart_total_cents INT64
) PARTITION BY event_date
  CLUSTER BY user_id, event_type;
```

**Dimension Tables:**

```sql
CREATE TABLE `analytics.dim_users` (
    user_id STRING NOT NULL,
    registration_date DATE,
    city STRING,
    zone STRING,
    lifetime_orders INT64,
    lifetime_gmv_cents INT64,
    avg_basket_cents INT64,
    preferred_categories ARRAY<STRING>,
    clv_segment STRING,           -- platinum, gold, silver, bronze
    churn_risk_score FLOAT64,
    last_order_date DATE
);

CREATE TABLE `analytics.dim_products` (
    product_id STRING NOT NULL,
    sku STRING,
    name STRING,
    category STRING,
    subcategory STRING,
    brand STRING,
    unit_price_cents INT64,
    weight_grams INT64,
    is_perishable BOOL,
    shelf_life_days INT64,
    avg_rating FLOAT64,
    total_reviews INT64
);

CREATE TABLE `analytics.dim_stores` (
    store_id STRING NOT NULL,
    store_name STRING,
    city STRING,
    zone STRING,
    latitude FLOAT64,
    longitude FLOAT64,
    total_skus INT64,
    avg_prep_time_minutes FLOAT64,
    store_type STRING              -- dark_store, micro_warehouse
);

CREATE TABLE `analytics.dim_riders` (
    rider_id STRING NOT NULL,
    name STRING,
    city STRING,
    vehicle_type STRING,
    join_date DATE,
    avg_rating FLOAT64,
    total_deliveries INT64,
    avg_delivery_minutes FLOAT64,
    acceptance_rate FLOAT64,
    status STRING
);

CREATE TABLE `analytics.dim_time` (
    date DATE NOT NULL,
    day_of_week INT64,
    day_name STRING,
    month INT64,
    quarter INT64,
    year INT64,
    is_weekend BOOL,
    is_holiday BOOL,
    holiday_name STRING,
    is_ipl_match BOOL,
    is_festival BOOL,
    festival_name STRING
);
```

### 4.2 Data Lake (Cloud Storage)

```
gs://instacommerce-data-lake/
├── raw/                       # CDC snapshots (Avro, 90-day expiry)
│   ├── orders/dt=2026-02-07/
│   ├── payments/dt=2026-02-07/
│   └── ...
├── processed/                 # Cleaned + tokenized (Parquet)
│   ├── orders/dt=2026-02-07/
│   └── ...
├── ml/
│   ├── training-data/         # Versioned datasets
│   ├── models/                # Model artifacts
│   └── predictions/           # Batch prediction outputs
└── exports/                   # Ad-hoc analyst exports
```

### 4.3 Feature Store (Vertex AI)

| Feature Group | Key Features | Update Frequency | Serving Latency |
|--------------|-------------|-----------------|-----------------|
| User | order_count_30d, avg_basket_cents, preferred_categories, churn_risk, days_since_last_order | Per order event | <10ms |
| Product | sales_velocity_7d, conversion_rate, avg_rating, stockout_frequency, price_elasticity | Hourly batch | <10ms |
| Store | avg_prep_time_1h, current_hour_orders, utilization_pct, stockout_count, picker_count | Every 5 min | <5ms |
| Rider | avg_delivery_time_7d, acceptance_rate, current_deliveries, avg_rating, zone_familiarity | Per location ping | <5ms |
| Search | query_popularity, avg_results, conversion_rate, category_intent | Hourly batch | <10ms |

---

## 5. ML/AI Use Cases

### 5.1 Search Ranking (Learning to Rank)

**Model**: LambdaMART (Phase 1) → Two-Tower Neural (Phase 2)

**Features (30+):** BM25 text score, query-title cosine similarity, user purchase history co-occurrence, product popularity (7d/30d), price competitiveness vs category avg, stock availability, time-of-day relevance, user location→store distance, category affinity, product freshness score

**Serving architecture:**
```
User query → search-service (BM25 top 200)
           → Feature Store (user + product features)
           → Vertex AI Prediction (rank 200 → top 20)
           → Return to client
           p99 < 50ms
```

**Training data:** Click-through logs with graded relevance: impression=0, click=1, add-to-cart=5, purchase=10. Primary metric: NDCG@10.

**How Instacart does it:** 70% of sessions start with search. 20+ engineer team. Pipeline: query understanding → retrieval (BM25 + embedding ANN) → ranking (LTR with 200+ features) → re-ranking (business rules, sponsored slots). Deep user-level personalization drives 25%+ GMV uplift.

**Expected impact:** +15-20% search conversion rate

### 5.2 Demand Forecasting

**Model:** Prophet (Phase 1) → Temporal Fusion Transformer (Phase 2)

**Granularity:** Per-product × per-store × per-hour

**Features:** 52-week same-day sales history, day-of-week seasonality, Indian holidays and festival calendar (Diwali, Holi, Navratri), weather (temperature, rainfall, humidity), IPL/cricket match schedule, active promotions and discount campaigns, price change events, school/college calendar, local events

**Integration points:**
- **inventory-service**: Automated reorder when predicted demand > current stock + pipeline
- **warehouse-service**: Staff scheduling based on predicted order volume per hour
- **pricing-service**: Markdown perishable items approaching expiry if demand is low

**How Zepto does it:** Per dark store per SKU per hour forecasting. Integrates weather + IPL + festival calendar. Claims 92% accuracy. Automated reorder triggers from ML. Critical for their 10-min promise — wrong forecast = either stockout (lost sale) or waste (spoilage cost).

**Expected impact:** -30-40% stockouts, -15-20% food waste, -25% working capital

### 5.3 Dynamic Pricing

**Model:** Contextual Bandit (Phase 1) → Reinforcement Learning PPO (Phase 2)

**Pricing factors:** Demand elasticity per category, competitor pricing (scraped daily), time-of-day demand curve, stock urgency (perishable nearing expiry), zone demand density, weather impact, promotion cannibalization

**Guardrails (critical for trust):**
- Price floor: never below cost + minimum margin
- Price ceiling: max 2x base price, max 1.5x delivery fee surge
- No demographic discrimination (no pricing by user income/location tier)
- Full audit trail of every price decision
- A/B tested on 5% traffic first, then graduated rollout
- Automatic rollback if customer complaints spike >2x

**How DoorDash does it:** 10M+ pricing decisions/day. Contextual bandits for delivery fee optimization. Revenue per order +10%. Separate models for delivery fee vs item pricing vs promotions.

**Expected impact:** +5-10% gross margin

### 5.4 Fraud Detection (ML-Enhanced)

**Model:** XGBoost Ensemble (Phase 1) → Deep Neural Network with Attention (Phase 2)

**Feature categories (80+ total):**
- **Velocity:** Orders last 1h/24h, new addresses last 7d, payment methods added
- **Basket anomaly:** Basket value vs user avg, high-resale items ratio, unusual quantities
- **Payment risk:** Card BIN country mismatch, prepaid card, multiple cards same device
- **Device:** Device fingerprint age, VPN/proxy detected, emulator detection
- **Network:** Delivery address clustering with known fraud addresses, referral chain depth
- **Account:** Account age, profile completeness, email domain risk

**Serving:** Real-time at checkout (<100ms p99). Score 0-100:
- <30: AUTO-APPROVE
- 30-70: SOFT-REVIEW (allow but flag for async review)
- >70: BLOCK (require manual approval)

**Feedback loop:** Support agent fraud labels → weekly model retraining. Precision monitoring: alert if precision <95%.

**How Instacart does it:** ML + rules hybrid system. ML catches novel fraud patterns that rules miss. Rules handle known patterns with zero latency. Ensemble approach saves $100M+/year. 15+ dedicated fraud ML engineers.

**Expected impact:** Fraud rate 2% → 0.3%, saving $7M+/year

### 5.5 ETA Prediction

**Model:** LightGBM Gradient Boosted Regression

**Three-stage prediction (Blinkit-inspired approach):**

1. **Pre-order (home screen):** Conservative estimate. Updated every 5 min per zone. Inputs: zone historical avg, current queue depth, rider availability, time-of-day.
2. **Post-order (committed ETA):** Real-time prediction with granular data. Inputs: specific store prep time (7d moving avg), specific rider assignment, live traffic data (Google Maps Directions API), order complexity (item count, pick difficulty), current store queue depth.
3. **In-transit (live countdown):** Updated per GPS ping (every 5-15 seconds). Remaining distance / rider speed model. Confidence-weighted blend of prediction and remaining-distance ETA.

**Key features:** Haversine distance, real-time traffic multiplier, time-of-day, store-specific prep time (7d rolling avg), rider-specific speed (personal historical avg), order item count, weather conditions (rain = +20%), current store queue depth

**Expected impact:** ETA accuracy ±5min → ±1.5min (70% reduction in error)

### 5.6 Personalization & Recommendations

**Model:** Two-Tower Neural Collaborative Filtering + Content-Based Hybrid

**Recommendation surfaces:**
- **Homepage "For You":** Personalized product ranking (top 50 products)
- **"Buy Again":** Repeat purchase prediction (recency × frequency model)
- **"Frequently Bought Together":** Association rules (item2vec embeddings)
- **"Similar Items":** Content-based (category, brand, price range, attributes)
- **Personalized categories:** Reorder category carousel by user affinity
- **Push notification targeting:** Right product, right time, right channel

**Cold start strategy:**
- 0 orders (new user): Popularity-based by zone (trending in your area)
- 1-3 orders: Content-based (similar items to purchased)
- 4+ orders: Full collaborative filtering with user embedding

**How Instacart does it:** User embedding updated per session interaction. "Buy It Again" is their #1 revenue feature. Uses item2vec for product embeddings trained on co-purchase data. Personalization drives 30%+ of GMV. Separate models per recommendation surface.

**Expected impact:** +10-15% AOV, +8% 30-day retention

### 5.7 Rider Assignment Optimization

**Model:** Google OR-Tools (constraint optimization) + LightGBM for travel time estimation

**Objective function:** Minimize weighted sum of: total delivery time (weight 0.5), rider idle time (weight 0.2), SLA breach probability (weight 0.3)

**Constraints:**
- Rider active order capacity ≤ 2
- Zone assignment (riders stay in assigned zone unless overflow)
- Battery/fuel level sufficient for round trip
- New rider distance limit (<3km for first 50 deliveries)
- Consecutive delivery limit (max 8 before mandatory 30-min break)

**Multi-order batching:** Combine 2 orders to same area if ETA impact <3 min per order. Reduces idle time 20%, increases rider earnings 15%.

**How Zepto does it:** 50K+ riders. Assignment considers rider skill level, vehicle type (bicycle vs scooter), real-time traffic, order weight/size, and rider familiarity with delivery area. Batches up to 3 orders in high-density zones during peak hours.

**Expected impact:** -2-3 min average delivery time, +15% rider utilization

### 5.8 Customer Lifetime Value (CLV)

**Model:** BG/NBD (frequency/recency) + Gamma-Gamma (monetary value)

**Segments:**
| Segment | Criteria | % Users | % Revenue | Strategy |
|---------|----------|---------|-----------|----------|
| Platinum | CLV > $500/yr | 5% | 35% | White-glove support, exclusive products |
| Gold | CLV $200-500 | 15% | 30% | Loyalty rewards, early access |
| Silver | CLV $50-200 | 30% | 25% | Growth incentives, gamification |
| Bronze | CLV < $50 | 50% | 10% | Reactivation campaigns, basic service |

**Use cases:** Set max CPA by acquisition channel, trigger churn prevention (when predicted CLV drops >20%), assign loyalty tier and benefits, prioritize customer support queue.

**Expected impact:** +20% marketing ROI, -15% high-value customer churn

---

## 6. ML Platform Architecture

### 6.1 MLOps Pipeline

```
┌──────────┐    ┌───────┐    ┌──────────┐    ┌────────┐    ┌─────────┐    ┌────────────┐    ┌─────────┐
│Experiment│───►│ Train  │───►│ Validate │───►│ Shadow │───►│Canary 5%│───►│Production  │───►│ Monitor │
│Workbench │    │Vertex  │    │  Auto    │    │1 week  │    │ 48 hrs  │    │   100%     │    │  Drift  │
└──────────┘    └───────┘    └──────────┘    └────────┘    └─────────┘    └────────────┘    └─────────┘
                                                                                                 │
                                                                              Retrain trigger ◄──┘
```

**Promotion gates:**
- Validate: AUC >0.85, precision >0.90, latency <50ms, no bias regression
- Shadow: Predictions logged but not served. Compare vs production model.
- Canary: A/B test on 5% traffic. Primary metric must improve. Guardrail metrics must not degrade >2%.
- Production: Full rollout. Continuous monitoring.

### 6.2 A/B Testing Framework

- **Traffic splitting:** config-feature-flag-service with Murmur3 hash for consistent user bucketing
- **Primary metric:** Per use case (conversion rate for search, delivery time for ETA, etc.)
- **Guardrail metrics:** Refund rate, customer satisfaction score, support ticket rate
- **Statistical method:** Bayesian analysis, 95% credible interval, minimum 1-week duration
- **Minimum sample:** 10K users per variant (or 50K orders depending on use case)
- **Sequential testing:** Allow early stopping if effect size is clearly positive/negative (>99% probability)

### 6.3 Model Governance

- **Bias detection:** Demographic parity and equalized odds checks before promotion
- **Explainability:** SHAP values for tree-based models, attention weights for neural models
- **Model cards:** Standardized documentation per production model (purpose, data, metrics, limitations, owner)
- **Kill switch:** Feature flag to disable any ML model instantly, falling back to rule-based default
- **Audit trail:** All model predictions logged with model version, features used, and confidence score
- **Retraining triggers:** Data drift (PSI > 0.2), performance drop (metric < threshold), scheduled (weekly)

---

## 7. Dashboards & Analytics

### 7.1 Operational Dashboards (Grafana — Real-Time)

**Order Funnel:**
```
Browse → Search → Cart Add → Checkout Start → Payment → Order Confirmed → Picked → Delivered
  100%    65%       28%         18%              16%        15%              14.5%    14%
```
With conversion rates at each step, filterable by time, city, store, platform.

**Delivery SLA:** Heat map by zone showing % orders delivered in <10 min. Trend line (1h, 24h, 7d). Alert when zone drops below 90%.

**Store Operations:** Real-time queue depth per store, average prep time (rolling 1h), stockout count, picker utilization.

**Rider Fleet:** Map view showing active/idle/offline riders per zone. Utilization gauge per zone. Earnings tracker.

**Revenue:** Real-time GMV counter (today vs yesterday same hour), AOV trend, orders/minute graph.

### 7.2 Business Intelligence (Looker)

| Dashboard | Audience | Key Metrics |
|-----------|----------|-------------|
| Executive | C-Suite, Board | GMV, MoM growth, unit economics, burn rate, path to profitability |
| Product | Product Team | Top sellers, price elasticity, search conversion, feature adoption |
| User Analytics | Growth Team | DAU/MAU, cohort retention (D1/D7/D30), CLV distribution, acquisition cost |
| Store Performance | Operations | Per-store P&L, prep time trends, stockout rates, waste % |
| Rider Analytics | Fleet Ops | Delivery time distribution, earnings, acceptance rate, churn |
| Finance | Finance Team | P&L, payment reconciliation, refund rates, GST compliance |
| Marketing | Marketing | Campaign ROI, channel attribution, coupon redemption, referral chains |

### 7.3 Alerting Rules

| Alert | Condition | Severity | Action |
|-------|-----------|----------|--------|
| SLA Breach | <90% deliveries in 10 min (zone, 30-min window) | P1 | Page ops + auto-surge riders |
| Revenue Cliff | >20% drop vs same hour last week | P1 | Page product + engineering |
| Payment Failure | >5% failure rate (5-min window) | P1 | Page payments team |
| Fraud Spike | >3x normal flagged orders (1-hr window) | P1 | Page fraud team + auto-tighten rules |
| Stockout Cascade | >10 SKUs at zero in single store | P2 | Alert store ops + trigger emergency reorder |
| Rider Shortage | <2 available riders in zone | P1 | Auto-surge incentive + expand zone radius |
| Cart Abandonment | >40% increase vs baseline (1-hr) | P2 | Alert product team |
| Model Drift | PSI > 0.2 on any production model | P2 | Alert ML team + schedule retraining |

---

## 8. Data Governance

### 8.1 GDPR Right-to-Deletion Pipeline

```
User requests deletion
  → identity-service erases PII, publishes UserDeleted event
  → Each service Kafka consumer anonymizes local DB (user_id retained, PII → 'REDACTED')
  → CDC captures anonymized records
  → Dataflow propagates to BigQuery (UPDATE SET name='REDACTED', email='REDACTED', phone='REDACTED')
  → Feature Store: DELETE user feature group
  → Audit trail: Logged as compliance action (retained for regulatory proof)
  → Completion event published back to identity-service
  → SLA: 72 hours end-to-end
```

### 8.2 PII Handling by Layer

| Layer | PII Treatment | Access Control |
|-------|--------------|----------------|
| Operational DB | Full PII (encrypted at rest, AES-256) | Service account only |
| Kafka | Full PII (mTLS in transit) | Topic-level ACLs |
| Data Lake — Raw | Full PII (90-day auto-expiry) | IAM restricted to data eng |
| Data Lake — Processed | Tokenized (user_id hash only, no name/email/phone) | Broader analyst access |
| BigQuery | Column-level security (PII columns restricted) | Row-level access by team |
| Feature Store | No PII (behavioral features only) | ML team access |
| Looker/Dashboards | Aggregated only (no individual user data) | Role-based |

### 8.3 Data Quality (Great Expectations)

```yaml
# great_expectations/expectations/orders_suite.yml
expectations:
  - expect_column_values_to_not_be_null:
      column: order_id
  - expect_column_values_to_not_be_null:
      column: user_id
  - expect_column_values_to_be_between:
      column: total_cents
      min_value: 0
      max_value: 10000000    # $100K max sanity check
  - expect_column_values_to_be_in_set:
      column: status
      value_set: [CREATED, CONFIRMED, PICKING, PICKED, ASSIGNED, IN_TRANSIT, DELIVERED, CANCELLED]
  - expect_table_row_count_to_be_between:
      min_value: 100000       # At least 100K orders/day
      max_value: 2000000      # Sanity cap
```

Run after every batch load. Block downstream if critical expectations fail.

### 8.4 Data Retention Policy

| Data Type | Hot (BigQuery) | Warm (Cloud Storage) | Cold (Archive) |
|-----------|---------------|---------------------|----------------|
| Orders | 2 years | 5 years | 7 years (compliance) |
| Payments | 2 years | 7 years (PCI-DSS) | 10 years |
| User behavior | 1 year | 3 years | Delete |
| Logs | 90 days | 1 year | Delete |
| ML training data | 1 year | 2 years | Delete |
| Audit events | 3 years | 7 years | 10 years |

---

## 9. Competitive Analysis — Data & ML Capabilities

### Zepto (India, $5B valuation)
- **Data team:** 100+ engineers and scientists
- **Key ML:** Real-time demand forecasting per dark store per SKU per hour. Weather + IPL + festivals integrated. Automated reorder from ML predictions. Dark store layout optimization using purchase sequence mining. Delivery route optimization with live traffic.
- **Differentiator:** Zomato food delivery data cross-pollination (predicting grocery demand from food ordering patterns in same neighborhoods)

### Instacart (US, $12B public)
- **Data team:** 200+ data scientists and ML engineers
- **Key ML:** 100+ production ML models. Custom "Lore" ML platform for feature engineering and model serving. "Buy It Again" personalization drives 25%+ of GMV. Advanced item availability prediction. Shopper-order matching optimization. Ads ranking (growing revenue stream).
- **Platform:** Custom feature store, experimentation platform (100+ concurrent A/B tests), model serving with p99 <10ms
- **Investment:** $500M+ annual value attributed to ML

### DoorDash (US, $70B public)
- **Data team:** 300+ engineers
- **Key ML:** "Sibyl" ML platform — unified feature store (Flink streaming + Spark batch), model serving at p99 <10ms. Dynamic pricing (contextual bandits for delivery fee). 100+ simultaneous A/B tests. Merchant duration prediction. Dasher assignment optimization. Fraud detection ensemble.
- **Investment:** ML drives >$500M annual value. Custom Argo-based ML pipeline.

### Blinkit (India, Zomato subsidiary)
- **Key ML:** Per-store SKU assortment based on neighborhood demographics + ordering patterns. Multi-order rider batching algorithm. Dynamic delivery fee pricing. Demand-driven promotional pricing.
- **Differentiator:** Leverages Zomato's massive food delivery dataset for cross-category demand prediction.

### Key Takeaways for InstaCommerce
1. **Search + personalization = highest ROI** (Instacart's biggest ML investment)
2. **Demand forecasting = operational backbone** (Zepto's core competitive advantage)
3. **Custom ML platform pays off at scale** (DoorDash Sibyl, Instacart Lore)
4. **Start with managed services, graduate to custom** (all started with off-the-shelf, built custom at scale)
5. **A/B testing infrastructure is non-negotiable** (100+ concurrent experiments at scale)

---

## 10. Implementation Roadmap

### Phase 1: Data Foundation (Weeks 1-4)

| Week | Deliverable |
|------|-------------|
| 1 | Deploy Debezium CDC for order, payment, inventory services |
| 2 | BigQuery core schema (fact_orders, fact_payments, dim tables), basic dbt staging models |
| 3 | Cloud Dataflow streaming pipeline (order events → BigQuery), Grafana real-time dashboards |
| 4 | Debezium for remaining services, Cloud Composer DAGs, data quality checks |

**Exit criteria:** All 18 services streaming to BigQuery. Real-time order funnel dashboard live.

### Phase 2: ML Foundation (Weeks 5-8)

| Week | Deliverable |
|------|-------------|
| 5 | Vertex AI Feature Store setup, user + product feature groups |
| 6 | Search ranking model v1 (LambdaMART), shadow deployment |
| 7 | Fraud detection ML model v1 (XGBoost), shadow deployment |
| 8 | A/B testing framework integrated with config-feature-flag-service |

**Exit criteria:** Two ML models in shadow mode. Feature Store serving <10ms.

### Phase 3: Intelligence (Weeks 9-14)

| Week | Deliverable |
|------|-------------|
| 9-10 | Demand forecasting model (Prophet per store per SKU per hour) |
| 11 | ETA prediction model (LightGBM, three-stage serving) |
| 12 | Personalization model (Two-Tower NCF), homepage + buy-again |
| 13 | Looker BI dashboards (executive, product, finance) |
| 14 | Promote search ranking + fraud ML to canary, then production |

**Exit criteria:** 5 ML models in production. Looker dashboards live for all stakeholders.

### Phase 4: Optimization (Weeks 15-20)

| Week | Deliverable |
|------|-------------|
| 15-16 | Dynamic pricing (contextual bandit, A/B tested) |
| 17 | Rider assignment optimization (OR-Tools + LightGBM) |
| 18 | CLV model + churn prediction + marketing automation integration |
| 19 | Full MLOps pipeline (automated retraining, drift detection, model governance) |
| 20 | Production hardening, monitoring, documentation, runbooks |

**Exit criteria:** Full ML platform operational. All 8 use cases in production. Automated retraining.

---

## 11. Cost Estimation

### Monthly Infrastructure Cost

| Component | 100K orders/day | 500K orders/day | 1M orders/day |
|-----------|----------------|-----------------|----------------|
| BigQuery (storage + compute) | $1,200 | $4,800 | $9,000 |
| Cloud Dataflow (streaming) | $2,500 | $10,000 | $18,000 |
| Vertex AI (training + serving) | $5,000 | $15,000 | $28,000 |
| Cloud Storage (data lake) | $300 | $1,200 | $2,500 |
| Debezium/Kafka Connect (Strimzi) | $1,000 | $3,000 | $5,000 |
| Cloud Composer (Airflow) | $800 | $1,500 | $2,500 |
| Looker | $3,000 | $3,000 | $3,000 |
| Feature Store | $500 | $2,000 | $4,000 |
| **Total** | **$14,300** | **$40,500** | **$72,000** |

### ROI Analysis

At 500K orders/day with $7.50 AOV:
- **Monthly GMV:** $112M
- **Conservative ML impact (3% GMV uplift):** $3.4M/month
- **Monthly platform cost:** $40.5K
- **ROI: 84:1**

Even at 1% GMV uplift: $1.12M/month vs $40.5K cost = **28:1 ROI**

---

*End of Document*
