# InstaCommerce Data Platform

Analytics data warehouse (dbt), workflow orchestration (Airflow), real-time
streaming pipelines (Apache Beam on Dataflow), and data quality enforcement
(Great Expectations) for the InstaCommerce Q-commerce platform.

> **Source-of-truth note:** the detailed streaming contract lives in
> [`streaming/README.md`](streaming/README.md). This top-level README focuses on
> platform architecture and now mirrors the actual pipeline windows, sinks, and
> DAG schedules in the repository.

---

## High-Level Design (HLD)

```mermaid
flowchart LR
    subgraph Sources["Data Sources"]
        PG["PostgreSQL\n(Operational DBs)"]
        Kafka["Kafka\n(Domain Events)"]
        CDC["Debezium CDC\n(Change Capture)"]
        API["External APIs"]
    end

    subgraph Ingestion["Ingestion Layer"]
        Beam["Apache Beam\n(Dataflow)"]
        BatchLoader["Kafka → BigQuery\nBatch Loader"]
    end

    subgraph Lake["Data Lake (GCS)"]
        Raw["🗃️ raw/"]
        Processed["📂 processed/"]
        ML_Data["🤖 ml/"]
        Exports["📤 exports/"]
    end

    subgraph Warehouse["BigQuery Data Warehouse"]
        BQ_Raw["raw dataset"]
        BQ_Staging["staging dataset"]
        BQ_Intermediate["intermediate"]
        BQ_Marts["marts dataset"]
        BQ_Features["features dataset"]
    end

    subgraph Consumption["Consumers"]
        BI["📊 BI / Looker"]
        ML["🧠 ML Training"]
        FS["Feature Store"]
        Export["Data Exports"]
    end

    PG --> CDC --> Kafka
    Kafka --> Beam --> BQ_Raw
    Kafka --> BatchLoader --> BQ_Raw
    API --> BQ_Raw
    BQ_Raw --> BQ_Staging --> BQ_Intermediate --> BQ_Marts
    BQ_Raw --> BQ_Features
    BQ_Raw --> Raw
    BQ_Staging --> Processed
    BQ_Marts --> BI
    BQ_Marts --> Export
    BQ_Features --> ML & FS
    BQ_Marts --> ML_Data
```

---

## Low-Level Design (LLD)

### Repository Structure

```
data-platform/
├── README.md
│
├── dbt/                              # dbt analytics project
│   ├── dbt_project.yml
│   ├── profiles.yml
│   └── models/
│       ├── staging/                   # 1:1 source mirrors, light cleaning
│       │   ├── sources.yml
│       │   ├── schema.yml
│       │   ├── stg_orders.sql
│       │   ├── stg_payments.sql
│       │   ├── stg_users.sql
│       │   ├── stg_products.sql
│       │   ├── stg_deliveries.sql
│       │   ├── stg_inventory_movements.sql
│       │   ├── stg_searches.sql
│       │   └── stg_cart_events.sql
│       ├── intermediate/              # Business logic joins
│       │   ├── int_order_deliveries.sql
│       │   ├── int_user_order_history.sql
│       │   └── int_product_performance.sql
│       └── marts/                     # Consumption-ready analytics
│           ├── schema.yml
│           ├── mart_daily_revenue.sql
│           ├── mart_store_performance.sql
│           ├── mart_rider_performance.sql
│           ├── mart_search_funnel.sql
│           ├── mart_product_analytics.sql
│           ├── mart_user_cohort_retention.sql
│           └── mart_sponsored_opportunities.sql
│
├── airflow/                           # Cloud Composer DAGs
│   ├── requirements.txt
│   ├── dags/
│   │   ├── dbt_staging.py             # Refresh staging models
│   │   ├── dbt_marts.py               # Refresh mart models
│   │   ├── data_quality.py            # Post-load quality checks
│   │   ├── ml_feature_refresh.py      # Recompute ML features
│   │   ├── ml_training.py             # Trigger model training
│   │   └── monitoring_alerts.py       # SLA & freshness alerts
│   └── plugins/
│       ├── __init__.py
│       └── slack_alerts.py            # Slack notification hooks
│
├── streaming/                         # Apache Beam pipelines (Dataflow)
│   ├── README.md
│   ├── requirements.txt
│   ├── pipelines/
│   │   ├── order_events_pipeline.py
│   │   ├── payment_events_pipeline.py
│   │   ├── cart_events_pipeline.py
│   │   ├── inventory_events_pipeline.py
│   │   └── rider_location_pipeline.py
│   └── deploy/
│       └── dataflow_template.yaml
│
└── quality/                           # Data quality (Great Expectations)
    ├── run_quality_checks.py
    └── expectations/
        ├── orders_suite.yaml
        ├── payments_suite.yaml
        ├── users_suite.yaml
        └── inventory_suite.yaml
```

---

## dbt Layer Diagram

```mermaid
flowchart TB
    subgraph Sources["BigQuery Raw Sources"]
        orders["raw.orders"]
        payments["raw.payments"]
        users["raw.users"]
        products["raw.products"]
        deliveries["raw.deliveries"]
        inventory["raw.inventory_movements"]
        searches["raw.searches"]
        cart["raw.cart_events"]
    end

    subgraph Staging["Staging Layer (stg_*)"]
        stg_orders["stg_orders"]
        stg_payments["stg_payments"]
        stg_users["stg_users"]
        stg_products["stg_products"]
        stg_deliveries["stg_deliveries"]
        stg_inventory["stg_inventory_movements"]
        stg_searches["stg_searches"]
        stg_cart["stg_cart_events"]
    end

    subgraph Intermediate["Intermediate Layer (int_*)"]
        int_order_deliveries["int_order_deliveries"]
        int_user_history["int_user_order_history"]
        int_product_perf["int_product_performance"]
    end

    subgraph Marts["Marts Layer (mart_*)"]
        mart_revenue["mart_daily_revenue"]
        mart_store["mart_store_performance"]
        mart_rider["mart_rider_performance"]
        mart_search["mart_search_funnel"]
        mart_product["mart_product_analytics"]
        mart_cohort["mart_user_cohort_retention"]
        mart_sponsored["mart_sponsored_opportunities"]
    end

    orders --> stg_orders
    payments --> stg_payments
    users --> stg_users
    products --> stg_products
    deliveries --> stg_deliveries
    inventory --> stg_inventory
    searches --> stg_searches
    cart --> stg_cart

    stg_orders & stg_deliveries --> int_order_deliveries
    stg_orders & stg_users --> int_user_history
    stg_products & stg_orders --> int_product_perf

    int_order_deliveries --> mart_revenue & mart_store & mart_rider
    int_user_history --> mart_cohort
    int_product_perf --> mart_product
    stg_searches & stg_cart --> mart_search
    stg_searches & stg_cart --> mart_sponsored
```

### dbt Quick Start

```bash
cd data-platform/dbt
dbt deps
dbt run --select staging      # refresh staging models
dbt run --select intermediate # refresh intermediate
dbt run --select marts        # refresh marts
dbt test                      # run schema + data tests
```

---

## Airflow DAG Dependencies

```mermaid
flowchart TB
    subgraph DAGs["Cloud Composer DAGs"]
        staging["dbt_staging\n⏰ every 2h"]
        quality["data_quality\n⏰ every 2h"]
        marts["dbt_marts\n⏰ daily 02:00"]
        features["ml_feature_refresh\n⏰ every 4h"]
        training["ml_training\n⏰ daily 04:00"]
        alerts["monitoring_alerts\n⏰ every 15m"]
    end

    staging --> quality
    staging --> marts
    quality --> marts
    marts --> features
    features --> training
    alerts -. "monitors" .-> staging & quality & marts & features
```

| DAG | Schedule (UTC) | Purpose |
|-----|---------------|---------|
| `dbt_staging` | `0 */2 * * *` | Refresh staging tables from raw sources |
| `data_quality` | `0 */2 * * *` | Run Great Expectations suites post-load |
| `dbt_marts` | `0 2 * * *` | Rebuild analytics marts (daily) |
| `ml_feature_refresh` | `0 */4 * * *` | Recompute ML feature tables in BigQuery |
| `ml_training` | `0 4 * * *` | Trigger daily model retraining / evaluation pipeline |
| `monitoring_alerts` | `*/15 * * * *` | Check SLA freshness & alert on Slack |

---

## Streaming Pipeline Architecture

```mermaid
flowchart LR
    subgraph Kafka["Kafka Topics"]
        OE["order.events"]
        PE["payment.events"]
        CE["cart.events"]
        IE["inventory.events"]
        RL["rider.location"]
    end

    subgraph Dataflow["Apache Beam on Dataflow"]
        OP["Order Events\nPipeline"]
        PP["Payment Events\nPipeline"]
        CP["Cart Events\nPipeline"]
        IP["Inventory Events\nPipeline"]
        RLP["Rider Location\nPipeline"]
    end

    subgraph Sinks["Sinks"]
        BQ["BigQuery\n(Analytics)"]
    end

    OE --> OP --> BQ
    PE --> PP --> BQ
    CE --> CP --> BQ
    IE --> IP --> BQ
    RL --> RLP --> BQ
```

| Pipeline | Source Topic | Sinks | Window | Purpose |
|----------|-------------|-------|--------|---------|
| `order_events_pipeline` | `order.events` | BigQuery | 1 min fixed | Order analytics and SLA tracking |
| `payment_events_pipeline` | `payment.events` | BigQuery | 1 min fixed | Payment reconciliation and success metrics |
| `cart_events_pipeline` | `cart.events` | BigQuery | 15 min session | Cart analytics and abandonment modeling |
| `inventory_events_pipeline` | `inventory.events` | BigQuery | 5 min fixed | Stock velocity and stockout tracking |
| `rider_location_pipeline` | `rider.location` | BigQuery | 1 min fixed | Rider utilization and zone-level tracking |

> The streaming README contains the canonical per-pipeline deployment and
> monitoring details. Use this top-level table as the architecture summary and
> [`streaming/README.md`](streaming/README.md) for execution specifics.

---

## Data Quality Gate Flow

```mermaid
flowchart LR
    Load["Data Load\nComplete"]
    GE["Great Expectations\nSuite Runner"]
    Pass["✅ Quality Pass"]
    Fail["❌ Quality Fail"]
    Downstream["Downstream\nDAGs Proceed"]
    Alert["🚨 Slack Alert\n+ DAG Pause"]
    Review["Manual Review\n+ Fix"]

    Load --> GE
    GE -- "all expectations pass" --> Pass --> Downstream
    GE -- "critical failure" --> Fail --> Alert --> Review
    Review --> Load
```

### Quality Suites

| Suite | Target Tables | Key Expectations |
|-------|--------------|------------------|
| `orders_suite` | `raw.orders`, `staging.orders` | Non-null order_id, valid amounts, referential integrity |
| `payments_suite` | `raw.payments`, `staging.payments` | Amount > 0, valid currency codes, status enum |
| `users_suite` | `raw.users`, `staging.users` | Valid email format, unique user_id |
| `inventory_suite` | `raw.inventory_movements` | Non-negative quantities, valid store references |

---

## Data Lake Structure

```mermaid
flowchart TB
    subgraph GCS["GCS: gs://instacommerce-data-lake/"]
        subgraph RawZone["raw/"]
            R1["orders/\ndt=YYYY-MM-DD/"]
            R2["payments/\ndt=YYYY-MM-DD/"]
            R3["events/\ntopic=*/dt=YYYY-MM-DD/"]
        end

        subgraph ProcessedZone["processed/"]
            P1["orders_enriched/"]
            P2["user_sessions/"]
            P3["delivery_metrics/"]
        end

        subgraph MLZone["ml/"]
            M1["datasets/\nmodel_name/version/"]
            M2["mlflow-artifacts/"]
            M3["feature-snapshots/"]
        end

        subgraph ExportsZone["exports/"]
            E1["bi-extracts/"]
            E2["partner-feeds/"]
        end
    end
```

| Zone | Path Pattern | Retention | Format |
|------|-------------|-----------|--------|
| `raw/` | `raw/{domain}/dt=YYYY-MM-DD/` | 90 days | Avro / JSON |
| `processed/` | `processed/{domain}/` | 1 year | Parquet |
| `ml/` | `ml/datasets/{model}/{version}/` | Indefinite | Parquet |
| `exports/` | `exports/{consumer}/` | 30 days | CSV / Parquet |

---

## Testing and Validation

Use the existing dbt and quality commands as the first-line validation loop for any platform change:

```bash
cd data-platform/dbt && dbt deps
cd data-platform/dbt && dbt test
cd data-platform/dbt && dbt run --select marts
python data-platform/quality/run_quality_checks.py
```

## Rollout and Rollback

- ship streaming, dbt, and DAG changes independently so ingestion, transformation, and orchestration can be rolled back without a full-platform revert
- prefer additive BigQuery/dbt changes over destructive schema rewrites
- canary new Beam/Dataflow jobs with side-by-side output validation before cutting consumers over
- pause downstream DAGs and alert analytics owners when Great Expectations finds critical contract failures

## Known Limitations

- some platform diagrams still describe target-state storage zones and activation flows that are only partially represented in checked-in code
- event-time correctness, late-data handling, and contract governance are called out in `docs/reviews/iter3/platform/data-platform-correctness.md` and remain active hardening areas
- the top-level README is intentionally high-signal; `streaming/README.md`, dbt models, and Airflow DAGs remain the executable source of truth
