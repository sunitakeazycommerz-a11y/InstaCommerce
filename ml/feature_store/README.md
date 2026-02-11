# InstaCommerce Feature Store

> **Platform**: Vertex AI Feature Store (GCP)  
> **Scale**: 20M+ users, 500K+ daily orders, 10-minute delivery SLA  
> **Owner**: ml-platform  

## Overview

The InstaCommerce Feature Store provides centralized, versioned, low-latency feature serving for all ML models across the platform. It bridges the analytics warehouse (BigQuery) and real-time ML inference (Vertex AI Prediction) with consistent feature definitions, point-in-time correctness, and online/offline parity.

### Why a Feature Store?

- **Consistency**: Same feature definitions for training and serving — no train/serve skew.
- **Low latency**: Online serving at <10ms p95 for real-time models (fraud, ETA, search ranking).
- **Reusability**: Features defined once, consumed by multiple models across teams.
- **Freshness**: Streaming ingestion for per-event updates; batch for hourly/daily aggregations.
- **Governance**: Ownership, access control, TTL, and data quality checks per feature group.

---

## Entity Map

Entities are the primary keys that features are organized around.

| Entity | Join Key | Description | Owner | Definition |
|--------|----------|-------------|-------|------------|
| `user` | `user_id` | InstaCommerce registered user | ml-platform | [`entities/user.yaml`](entities/user.yaml) |
| `product` | `product_id` | Product/SKU in the catalog | catalog-team | [`entities/product.yaml`](entities/product.yaml) |
| `store` | `store_id` | Dark store / micro-warehouse | operations-team | [`entities/store.yaml`](entities/store.yaml) |
| `rider` | `rider_id` | Delivery rider | fleet-team | [`entities/rider.yaml`](entities/rider.yaml) |
| `search_query` | `query_hash` | Normalized search query | search-team | [`entities/search_query.yaml`](entities/search_query.yaml) |

---

## Feature Groups

Each feature group contains related features for a single entity.

| Feature Group | Entity | # Features | Update Frequency | Online Serving | TTL |
|---------------|--------|-----------|-----------------|----------------|-----|
| [`user_features`](feature_groups/user_features.yaml) | user | 14 | per_event | ✅ | 30d |
| [`product_features`](feature_groups/product_features.yaml) | product | 12 | hourly | ✅ | 7d |
| [`store_features`](feature_groups/store_features.yaml) | store | 8 | 5min | ✅ | 1d |
| [`rider_features`](feature_groups/rider_features.yaml) | rider | 9 | per_location_ping | ✅ | 7d |
| [`search_features`](feature_groups/search_features.yaml) | search_query | 10 | hourly | ✅ | 7d |

---

## Feature Views (Model-Specific)

Feature views compose features from multiple groups for specific ML model use cases.

| Feature View | Entities | Model Use Case | Latency SLO | Fallback |
|-------------|----------|----------------|-------------|----------|
| [`search_ranking`](feature_views/search_ranking_view.yaml) | user, product | LambdaMART / Two-Tower search ranking | <50ms | popularity_based |
| [`fraud_detection`](feature_views/fraud_detection_view.yaml) | user | XGBoost fraud scoring at checkout | <100ms | rules_based |
| [`eta_prediction`](feature_views/eta_prediction_view.yaml) | store, rider | LightGBM three-stage ETA prediction | <30ms | zone_historical_avg |
| [`demand_forecast`](feature_views/demand_forecast_view.yaml) | product, store | Prophet / TFT demand forecasting | <50ms | 52_week_moving_avg |
| [`personalization`](feature_views/personalization_view.yaml) | user, product | Two-Tower NCF recommendations | <50ms | zone_popularity |

---

## Access Patterns

### Online Serving (Real-Time Inference)

```
Client Request
  → Service (e.g., search-service, fraud-detection-service)
  → Feature Store Online Lookup (user_id / product_id / store_id)
  → Vertex AI Prediction Endpoint
  → Response (<50ms p99)
```

**Typical online flow (search ranking):**
1. `search-service` receives user query, retrieves BM25 top-200 candidates.
2. Fetches `user_features` for the requesting `user_id` from Feature Store.
3. Fetches `product_features` for each candidate `product_id` from Feature Store.
4. Sends combined features to Vertex AI `search_ranking` endpoint.
5. Returns re-ranked top-20 results to client.

### Offline Serving (Training & Batch Inference)

```
BigQuery (analytics schema)
  → SQL feature computation (sql/*.sql)
  → Feature Store offline read with point-in-time join
  → Training dataset (versioned in Cloud Storage)
  → Vertex AI Training Pipeline
```

**Point-in-time correctness**: Offline reads use `feature_timestamp` to ensure training data reflects the feature values that would have been available at prediction time — preventing data leakage.

### Batch Scoring

```
Cloud Composer DAG (every 4 hours)
  → BigQuery SQL (sql/*.sql)
  → Feature Store batch ingestion
  → Batch prediction (e.g., churn_risk_score, CLV segment)
  → Write predictions back to BigQuery / dim_users
```

---

## Directory Structure

```
ml/feature_store/
├── README.md                              # This file
├── entities/                              # Entity definitions
│   ├── user.yaml
│   ├── product.yaml
│   ├── store.yaml
│   ├── rider.yaml
│   └── search_query.yaml
├── feature_groups/                        # Feature group definitions
│   ├── user_features.yaml
│   ├── product_features.yaml
│   ├── store_features.yaml
│   ├── rider_features.yaml
│   └── search_features.yaml
├── feature_views/                         # Model-specific feature compositions
│   ├── search_ranking_view.yaml
│   ├── fraud_detection_view.yaml
│   ├── eta_prediction_view.yaml
│   ├── demand_forecast_view.yaml
│   └── personalization_view.yaml
├── sql/                                   # BigQuery SQL for feature computation
│   ├── user_features.sql
│   ├── product_features.sql
│   ├── store_features.sql
│   ├── rider_features.sql
│   └── search_features.sql
└── ingestion/                             # Feature ingestion job configs
    └── user_features_job.yaml
```

---

## Data Sources (BigQuery Analytics Schema)

All feature SQL queries reference the following tables from `analytics.*`:

**Fact Tables:**
- `analytics.fact_orders` — Order lifecycle events (partitioned by `order_date`)
- `analytics.fact_deliveries` — Delivery tracking (partitioned by `delivery_date`)
- `analytics.fact_payments` — Payment transactions (partitioned by `payment_date`)
- `analytics.fact_inventory_movements` — Stock movements (partitioned by `movement_date`)
- `analytics.fact_searches` — Search events (partitioned by `search_date`)
- `analytics.fact_cart_events` — Cart interactions (partitioned by `event_date`)

**Dimension Tables:**
- `analytics.dim_users` — User profiles, CLV segments, churn scores
- `analytics.dim_products` — Product catalog with ratings, pricing
- `analytics.dim_stores` — Store locations, capacity, prep times
- `analytics.dim_riders` — Rider profiles, vehicle type, performance
- `analytics.dim_time` — Calendar with holidays, festivals, IPL schedule

See [`docs/reviews/data-platform-ml-design.md`](../../docs/reviews/data-platform-ml-design.md) for full schema definitions.

---

## Ingestion Pipeline

| Feature Group | Batch Schedule | Streaming Source | Ingestion Config |
|---------------|---------------|-----------------|-----------------|
| user_features | Every 4 hours | `order.events` (Kafka) | [`ingestion/user_features_job.yaml`](ingestion/user_features_job.yaml) |
| product_features | Hourly | — | TBD |
| store_features | Every 5 min | `fulfillment.events` | TBD |
| rider_features | Every 5 min | `rider.location.updates` | TBD |
| search_features | Hourly | — | TBD |

---

## SLOs & Monitoring

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| Online serving latency (p95) | <10ms | >15ms |
| Online serving latency (p99) | <50ms | >75ms |
| Feature freshness (batch) | <4 hours | >6 hours |
| Feature freshness (streaming) | <1 minute | >5 minutes |
| Missing feature rate | <0.1% | >1% |
| Online/offline parity | <1% divergence | >2% divergence |
| Ingestion job success rate | >99.9% | <99% |

---

## Adding New Features

1. **Define the entity** (if new) in `entities/<entity>.yaml`.
2. **Add features** to the appropriate feature group in `feature_groups/<group>.yaml`.
3. **Write the SQL** computation in `sql/<group>.sql` referencing `analytics.*` tables.
4. **Create/update a feature view** in `feature_views/<view>.yaml` if a model needs the new features.
5. **Configure ingestion** in `ingestion/<group>_job.yaml`.
6. **Backfill** historical features for training data.
7. **Validate** online/offline parity before enabling online serving.

---

## Ownership & Contacts

| Team | Responsibilities |
|------|-----------------|
| ml-platform | Feature Store infrastructure, ingestion pipelines, SLOs |
| search-team | Search features, search ranking model features |
| fraud-team | Fraud detection features and model |
| fleet-team | Rider features, ETA model features |
| operations-team | Store features, demand forecast features |
| catalog-team | Product features, product embeddings |
