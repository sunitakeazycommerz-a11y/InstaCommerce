# Data → ML → AI Flow Diagrams

> **Iteration 3 · Diagram Series**  
> Scope: Operational events → BigQuery warehouse → dbt layers → feature store → model training/eval → inference serving → LangGraph AI-agent proposal loops → feedback and activation.  
> All folder references are relative to the repo root. All service names match `settings.gradle.kts` and `.github/workflows/ci.yml`.

---

## Contents

1. [Master End-to-End Flow](#1-master-end-to-end-flow)
2. [Event Ingestion and Streaming Pipeline](#2-event-ingestion-and-streaming-pipeline)
3. [dbt Warehouse Layers and Quality Gates](#3-dbt-warehouse-layers-and-quality-gates)
4. [Feature Store Pipeline](#4-feature-store-pipeline)
5. [Model Training and Evaluation Pipeline](#5-model-training-and-evaluation-pipeline)
6. [Inference Serving — Shadow Mode and A/B Routing](#6-inference-serving--shadow-mode-and-ab-routing)
7. [LangGraph AI Orchestrator State Machine](#7-langgraph-ai-orchestrator-state-machine)
8. [Safe Proposal-Only AI Loop](#8-safe-proposal-only-ai-loop)
9. [Feedback and Label Refresh Loop](#9-feedback-and-label-refresh-loop)
10. [Model and Feature Lineage Map](#10-model-and-feature-lineage-map)
11. [Control Points Summary](#11-control-points-summary)
12. [Late Data Handling Strategy](#12-late-data-handling-strategy)

---

## 1. Master End-to-End Flow

High-level view of every stage, with repo folder callouts. Subsequent sections zoom in on each numbered zone.

```mermaid
flowchart TD
    subgraph Z1["① Operational Services (services/)"]
        direction TB
        OS["order-service\nfulfillment-service\npayment-service\ninventory-service\nfraud-detection-service\nwarehouse-service\nrider-fleet-service"]
    end

    subgraph Z2["② CDC & Event Bus (services/go/)"]
        direction TB
        CDC["cdc-consumer-service\n(Debezium → Kafka)"]
        OBR["outbox-relay-service\n(outbox → Kafka)"]
        SP["stream-processor-service\n(Kafka → enriched topics)"]
        LOC["location-ingestion-service\n(rider GPS → Kafka)"]
    end

    subgraph Z3["③ Streaming Pipelines (data-platform/streaming/)"]
        direction TB
        BP1["order_events_pipeline.py\n(1-min tumbling / 30-min SLA)"]
        BP2["inventory_events_pipeline.py"]
        BP3["payment_events_pipeline.py"]
        BP4["cart_events_pipeline.py"]
        BP5["rider_location_pipeline.py"]
        DL["Dead-letter side-output\n→ BigQuery dead_letter.*"]
    end

    subgraph Z4["④ BigQuery Warehouse + dbt (data-platform/dbt/)"]
        direction TB
        STG["stg_* — staging layer\n(every 2 h, dbt_staging DAG)"]
        INT["int_* — intermediate layer\n(joins, sessionization)"]
        MART["mart_* — marts layer\n(daily 02:00 UTC, dbt_marts DAG)"]
        GE["Great Expectations quality gates\n(data-platform/quality/)"]
    end

    subgraph Z5["⑤ Feature Store (ml/feature_store/)"]
        direction TB
        FG["Feature Groups\nuser / product / store / rider / search"]
        FV["Feature Views\ndemand_forecast / fraud_detection /\npersonalization / search_ranking / eta"]
        FING["Ingestion Jobs\n(ml_feature_refresh DAG, every 1–6 h)"]
        FS_ONLINE["Online Store (Redis)\nTTL: 5–60 min per view"]
        FS_OFFLINE["Offline Store (BigQuery)\nfor training snapshots"]
    end

    subgraph Z6["⑥ Training & Evaluation (ml/train/ + ml/eval/)"]
        direction TB
        TR["Vertex AI training jobs\n(ml_training DAG, 04:00 UTC daily)"]
        EV["ModelEvaluator\n(ml/eval/evaluate.py)\npromotion gates + bias checks"]
        MR["Model Registry\n(ml/serving/model_registry.py)\nversions + kill-switches"]
        MLFLOW["MLflow\n(metrics / artifacts / lineage)"]
    end

    subgraph Z7["⑦ Inference Serving (services/ai-inference-service/)"]
        direction TB
        INF["FastAPI :8000\napp/models/\n{demand,fraud,ranking,personalization,\nclv,eta,dynamic_pricing}_model.py"]
        SM["ShadowRunner\n(ml/serving/shadow_mode.py)\nnew model alongside prod"]
        AB["A/B Router\n(model_registry.py)\nweighted traffic split"]
    end

    subgraph Z8["⑧ AI Orchestrator (services/ai-orchestrator-service/)"]
        direction TB
        LG["LangGraph :8100\napp/graph/graph.py\nclassify→policy→retrieve→tools→validate→respond"]
        GR["Guardrails\naudit/pii/injection/rate-limit/output-validator\n(app/guardrails/)"]
        TL["Tool Registry\n(app/graph/tools.py)\ncircuit-breaker HTTP to Java services"]
    end

    subgraph Z9["⑨ Activation & Feedback"]
        direction TB
        HUM["Human review queue\n(escalation.py → ops dashboard)"]
        ACT["Service activation\n(pricing-service / inventory-service /\nnotification-service via REST)"]
        LBL["Outcome labels collected\n(delivered, fraud confirmed,\nclick/purchase, refund)"]
    end

    OS -->|"Postgres WAL / outbox rows"| CDC
    OS -->|"outbox rows"| OBR
    CDC -->|"Kafka: orders.events\ninventory.events\npayments.events"| SP
    OBR -->|"Kafka: enriched events"| SP
    LOC -->|"Kafka: rider.locations"| BP5
    SP -->|"Kafka enriched topics"| BP1 & BP2 & BP3 & BP4
    BP1 & BP2 & BP3 & BP4 & BP5 -->|"BigQuery: analytics.*\nrealtime_order_volume\nsla_compliance\netc."| STG
    BP1 -->|"malformed events"| DL
    STG -->|"dbt run --select staging"| GE
    GE -->|"pass"| INT
    INT -->|"dbt run --select intermediate"| MART
    MART -->|"dbt test --select marts"| GE
    MART -->|"SQL feature queries\n(ml/feature_store/sql/)"| FING
    FING --> FG --> FV --> FS_OFFLINE & FS_ONLINE
    FS_OFFLINE -->|"training snapshot"| TR
    TR --> EV
    EV -->|"gates_passed = true"| MR
    EV -->|"gates_passed = false → skip promote"| MLFLOW
    MR --> INF
    INF --> SM
    INF --> AB
    FS_ONLINE -->|"online features\n(<50–100 ms TTL)"| INF
    INF -->|"fraud score / ETA / rank / demand"| LG
    LG --> GR
    GR -->|"policy pass"| TL
    TL -->|"REST → Java services"| ACT
    GR -->|"needs_escalation=true"| HUM
    HUM -->|"operator decision"| ACT
    ACT -->|"outcome events"| LBL
    LBL -->|"label refresh\n(ml_feature_refresh DAG)"| FING
```

---

## 2. Event Ingestion and Streaming Pipeline

Shows the Go services that bridge operational databases to Kafka, and the Beam/Dataflow pipelines that land events in BigQuery.

```mermaid
flowchart LR
    subgraph OPS["Operational Services (Java / Spring Boot)"]
        OS1["order-service\n(PostgreSQL)"]
        OS2["inventory-service\n(PostgreSQL)"]
        OS3["payment-service\n(PostgreSQL)"]
        OS4["fulfillment-service\n(PostgreSQL)"]
        OS5["rider-fleet-service\n(PostgreSQL)"]
    end

    subgraph OUTBOX["Outbox Pattern"]
        OT1["orders_outbox table"]
        OT2["inventory_outbox table"]
        OT3["payments_outbox table"]
        OT4["fulfillment_outbox table"]
    end

    subgraph CDC_LAYER["cdc-consumer-service (Go)"]
        DEB["Debezium Kafka Connect\n(WAL → change events)"]
        CDCG["cdc-consumer-service\nservices/cdc-consumer-service/"]
    end

    subgraph RELAY["outbox-relay-service (Go)"]
        OR["services/outbox-relay-service/\nShedLock-style polling\nevery 500 ms"]
    end

    subgraph KAFKA["Kafka Topics"]
        KO["orders.events"]
        KI["inventory.events"]
        KP["payments.events"]
        KF["fulfillment.events"]
        KRL["rider.locations"]
        KDL["*.dead-letter"]
    end

    subgraph STREAM["stream-processor-service (Go)"]
        SP["services/stream-processor-service/\nenriches events: zone lookup,\nstore metadata, SLA tagging"]
    end

    subgraph LOC_ING["location-ingestion-service (Go)"]
        LI["services/location-ingestion-service/\nGPS batching 100 ms flush"]
    end

    subgraph BEAM["Beam/Dataflow Pipelines\n(data-platform/streaming/pipelines/)"]
        BP1["order_events_pipeline.py\n• 1-min tumbling window (volume)\n• 30-min sliding window (SLA)\n• Dead-letter side output"]
        BP2["inventory_events_pipeline.py\n• Stockout alerts\n• Reorder signals"]
        BP3["payment_events_pipeline.py\n• Settlement aggregation\n• Failure rate rolling avg"]
        BP4["cart_events_pipeline.py\n• Abandonment funnel\n• Add-to-cart velocity"]
        BP5["rider_location_pipeline.py\n• Route polyline\n• Zone heatmap"]
    end

    subgraph BQ["BigQuery (analytics.*)"]
        BQ1["realtime_order_volume"]
        BQ2["sla_compliance"]
        BQ3["inventory_movements_raw"]
        BQ4["payment_settlements"]
        BQ5["cart_events_raw"]
        BQ6["rider_location_raw"]
        BQDL["dead_letter.*\n(malformed events)"]
    end

    OS1 --> OT1
    OS2 --> OT2
    OS3 --> OT3
    OS4 --> OT4

    OT1 & OT2 & OT3 & OT4 --> OR
    OS1 & OS2 & OS3 & OS4 --> DEB
    DEB --> CDCG

    CDCG --> KO & KI & KP & KF
    OR --> KO & KI & KP & KF
    OS5 --> LI --> KRL

    KO & KI & KP & KF --> SP
    SP --> KO & KI & KP & KF

    KO --> BP1
    KI --> BP2
    KP --> BP3
    KF --> BP4
    KRL --> BP5

    BP1 --> BQ1 & BQ2
    BP1 -->|"dead-letter"| BQDL
    BP2 --> BQ3
    BP3 --> BQ4
    BP4 --> BQ5
    BP5 --> BQ6

    style BQDL fill:#f9c,stroke:#c33
```

**Control points:**
- `OrderEventParser.DEAD_LETTER` side output catches malformed events — every pipeline tags unparseable records without dropping them.
- `stream-processor-service` attaches zone / store metadata before events fan out to downstream consumers; missing metadata is logged but not blocking.
- Kafka topic names (`orders.events`, `inventory.events`, `payments.events`) are canonical in `contracts/`; changing them requires a new schema version.

**Late data handling:**
- Beam pipelines use `AfterWatermark` + `AfterProcessingTime` triggers with `ACCUMULATING` mode for the SLA window — late arrivals up to 30 minutes refire the window.
- Events older than the allowed late-data horizon land in the dead-letter table rather than mutating already-emitted aggregates.

---

## 3. dbt Warehouse Layers and Quality Gates

```mermaid
flowchart TD
    subgraph SRC["Sources (data-platform/dbt/models/staging/sources.yml)"]
        SRC1["BigQuery: analytics.realtime_order_volume"]
        SRC2["BigQuery: analytics.inventory_movements_raw"]
        SRC3["BigQuery: analytics.payment_settlements"]
        SRC4["BigQuery: analytics.cart_events_raw"]
        SRC5["BigQuery: analytics.rider_location_raw"]
    end

    subgraph STG["Staging Layer — dbt_staging DAG (every 2 h)\n(data-platform/dbt/models/staging/)"]
        S1["stg_orders.sql"]
        S2["stg_inventory_movements.sql"]
        S3["stg_payments.sql"]
        S4["stg_cart_events.sql"]
        S5["stg_deliveries.sql"]
        S6["stg_searches.sql"]
        S7["stg_products.sql"]
        S8["stg_users.sql"]
        SSCHEMA["schema.yml — column-level tests\n(not_null, unique, accepted_values)"]
    end

    subgraph GE1["Quality Gate ① — Great Expectations\n(data-platform/quality/)"]
        GE_O["orders_suite.yaml\n• order_id unique + not_null\n• total_cents 0–10 M\n• status in allowed set\n• row count 100k–2M"]
        GE_I["inventory_suite.yaml"]
        GE_P["payments_suite.yaml"]
        GE_U["users_suite.yaml"]
    end

    subgraph INT["Intermediate Layer — dbt_staging DAG (every 2 h)\n(data-platform/dbt/models/intermediate/)"]
        I1["int_order_deliveries.sql\n(orders × fulfillment join,\nSLA flag, dispatch_to_delivered_min)"]
        I2["int_user_order_history.sql\n(order + payment + cart session)"]
        I3["int_product_performance.sql\n(order + search + cart, 7/30 d windows)"]
    end

    subgraph GE2["Quality Gate ② — dbt tests\n(dbt test --select intermediate)"]
        DBT_INT["relationship tests\ncustom freshness assertions\nnot_null on join keys"]
    end

    subgraph MART["Marts Layer — dbt_marts DAG (daily 02:00 UTC)\n(data-platform/dbt/models/marts/)"]
        M1["mart_daily_revenue.sql"]
        M2["mart_product_analytics.sql"]
        M3["mart_rider_performance.sql"]
        M4["mart_search_funnel.sql"]
        M5["mart_store_performance.sql"]
        M6["mart_user_cohort_retention.sql"]
        M7["mart_sponsored_opportunities.sql"]
    end

    subgraph GE3["Quality Gate ③ — dbt + schema.yml\n(data-platform/dbt/models/marts/schema.yml)"]
        DBT_MART["metric range checks\nprimary key uniqueness\nfreshness assertions"]
    end

    subgraph AIRFLOW["Airflow DAGs (data-platform/airflow/dags/)"]
        DAG1["dbt_staging.py\nschedule: '0 */2 * * *'\ndbt run + dbt test → Gate ①"]
        DAG2["data_quality.py\nGreat Expectations runner\nSlack alert on failure"]
        DAG3["dbt_marts.py\nschedule: '0 2 * * *'\nExternalTaskSensor waits for dbt_test_staging"]
    end

    SRC1 --> S1
    SRC2 --> S2
    SRC3 --> S3
    SRC4 --> S4
    SRC5 --> S5

    S1 & S2 & S3 & S4 & S5 & S6 & S7 & S8 --> GE1
    GE1 -->|"suite PASS"| INT
    GE1 -->|"suite FAIL → Slack alert + DAG abort"| FAIL1(["❌ pipeline halted"])
    INT --> GE2
    GE2 -->|"tests PASS"| MART
    GE2 -->|"tests FAIL → DAG abort"| FAIL2(["❌ pipeline halted"])
    MART --> GE3
    GE3 -->|"tests PASS → marts ready"| DOWNSTREAM(["Feature store ingestion ▶"])
    GE3 -->|"tests FAIL"| FAIL3(["❌ Slack alert, marts not promoted"])

    DAG1 -.->|"orchestrates"| S1
    DAG2 -.->|"orchestrates"| GE1
    DAG3 -.->|"orchestrates"| MART

    style FAIL1 fill:#f88,stroke:#c00
    style FAIL2 fill:#f88,stroke:#c00
    style FAIL3 fill:#f88,stroke:#c00
    style DOWNSTREAM fill:#8f8,stroke:#080
```

**Three quality gates in sequence:**

| Gate | Tool | Halts pipeline? | Notification |
|------|------|-----------------|--------------|
| ① Staging column tests | dbt schema.yml | Yes — DAG abort | Slack `data-alerts` |
| ① Row-level expectations | Great Expectations (`data_quality.py` DAG) | Yes | Slack `data-alerts` |
| ② Intermediate relationship tests | dbt test | Yes | Slack `data-alerts` |
| ③ Marts metric range + freshness | dbt schema.yml | Yes — marts not promoted | Slack `data-alerts` |

**Late data in dbt:** The `dbt_marts.py` DAG uses an `ExternalTaskSensor` with a 1-hour `timeout` and 2-minute `poke_interval`. If staging runs late, marts wait rather than running on stale data. Catchup is disabled (`catchup=False`) on all DAGs, so backfill must be triggered manually.

---

## 4. Feature Store Pipeline

```mermaid
flowchart TD
    subgraph MART_SRC["Mart & Intermediate Sources\n(data-platform/dbt/models/)"]
        M1["mart_daily_revenue\nmart_product_analytics\nmart_store_performance"]
        M2["mart_user_cohort_retention\nint_user_order_history"]
        M3["mart_rider_performance\nint_order_deliveries"]
        M4["mart_search_funnel\nint_product_performance"]
    end

    subgraph SQL_LAYER["Feature SQL Queries\n(ml/feature_store/sql/)"]
        SQL1["user_features.sql\n(order_count_30d, avg_basket_cents,\ndays_since_last_order, lifetime_gmv,\ncancellation_rate_90d, refund_rate_90d,\nclv_segment, preferred_payment_method)"]
        SQL2["product_features.sql\n(sales_velocity_7d, avg_rating,\nreview_count, price_cents,\nco_purchase_top5, embedding_vector)"]
        SQL3["store_features.sql\n(current_hour_orders, utilization_pct,\nstockout_count_today)"]
        SQL4["rider_features.sql\n(deliveries_7d, on_time_rate,\navg_delivery_min, zone_affinity)"]
        SQL5["search_features.sql\n(query_category_affinity,\nclick_through_rate_7d,\nconversion_rate_30d)"]
    end

    subgraph ENTITIES["Entities (ml/feature_store/entities/)"]
        E1["user.yaml"]
        E2["product.yaml"]
        E3["store.yaml"]
        E4["rider.yaml"]
        E5["search_query.yaml"]
    end

    subgraph FG["Feature Groups (ml/feature_store/feature_groups/)"]
        FG1["user_features.yaml"]
        FG2["product_features.yaml"]
        FG3["store_features.yaml"]
        FG4["rider_features.yaml"]
        FG5["search_features.yaml"]
    end

    subgraph FV["Feature Views (ml/feature_store/feature_views/)"]
        FV1["demand_forecast_view.yaml\nentities: product, store\nbatch: 0 */4 * * *\nonline TTL: 60 min\nfallback: 52-week moving avg"]
        FV2["fraud_detection_view.yaml\nentity: user\nbatch: 0 */1 * * *\nonline TTL: 5 min\nfallback: rules_based\nscoring thresholds: 30/70/100"]
        FV3["personalization_view.yaml\nentities: user, product\nbatch: 0 */6 * * *\nonline TTL: 30 min\nfallback: zone_popularity"]
        FV4["search_ranking_view.yaml\nentities: user, product\nbatch: 0 */4 * * *\nonline TTL: 15 min\nfallback: popularity_based"]
        FV5["eta_prediction_view.yaml\nentities: rider, store\nbatch: 0 */2 * * *\nonline TTL: 10 min"]
    end

    subgraph STORES["Feature Stores"]
        ONLINE["Online Store (Redis)\nPer-entity key-value\nTTL per view config\n(5–60 min)"]
        OFFLINE["Offline Store (BigQuery)\nPoint-in-time correct joins\nfor training snapshots"]
    end

    subgraph AIRFLOW_F["Airflow DAG\n(data-platform/airflow/dags/ml_feature_refresh.py)"]
        FDAG["ml_feature_refresh DAG\nschedule: '0 */1 * * *'\nRuns SQL jobs → backfills online + offline"]
        FING["user_features_job.yaml\n(ml/feature_store/ingestion/)"]
    end

    M1 --> SQL3
    M2 --> SQL1
    M1 --> SQL2
    M3 --> SQL4
    M4 --> SQL5

    SQL1 --> FG1
    SQL2 --> FG2
    SQL3 --> FG3
    SQL4 --> FG4
    SQL5 --> FG5

    E1 & FG1 --> FV1 & FV2 & FV3 & FV4
    E2 & FG2 --> FV1 & FV3 & FV4
    E3 & FG3 --> FV1
    E4 & FG4 --> FV5
    E5 & FG5 --> FV4

    FV1 & FV2 & FV3 & FV4 & FV5 --> ONLINE
    FV1 & FV2 & FV3 & FV4 & FV5 --> OFFLINE

    FDAG --> FING --> SQL1 & SQL2 & SQL3 & SQL4 & SQL5

    ONLINE -->|"online lookup\n<50–100 ms"| INF_SVC(["→ ai-inference-service"])
    OFFLINE -->|"training snapshot\n(point-in-time)"| TRAIN(["→ ml/train/"])
```

**Feature lineage:** Every feature view carries `model_refs` (e.g., `demand_prophet_v1`, `fraud_xgboost_v1`) that link the serving view back to the specific trained model version. Adding a feature or changing a SQL query in `ml/feature_store/sql/` must be version-controlled alongside the consuming `config.yaml` under `ml/train/`.

**Late data handling in the feature store:**
- Each feature view defines `online_ttl_minutes` and a `fallback` strategy. If Redis data is stale or absent, inference falls back to the view-specific fallback (e.g., `52_week_moving_avg` for demand, `rules_based` for fraud).
- The `ml_feature_refresh` DAG runs hourly; if it fails, the online TTL naturally expires and the fallback activates within the TTL window — no manual intervention required for short outages.

---

## 5. Model Training and Evaluation Pipeline

```mermaid
flowchart TD
    subgraph OFFLINE_FS["Offline Feature Store\n(BigQuery, point-in-time snapshots)"]
        T1["analytics.training_demand_history\n(365 days, hourly, min 168 rows)"]
        T2["analytics.training_fraud_labels\n(180 days, label: is_fraud,\nclass_weight: auto)"]
        T3["analytics.training_search_clicks\n(90 days, group: query_id,\nlabel: relevance_label)"]
        T4["analytics.training_user_interactions\n(180 days, min 3 interactions/user)"]
        T5["analytics.training_demand_history\n(ETA variant)"]
        T6["analytics.training_user_interactions\n(CLV variant)"]
    end

    subgraph CONFIGS["Training Configs (ml/train/*/config.yaml)"]
        C1["demand_forecast/config.yaml\nmodel: demand-forecast v1\nobjective: time_series_forecast\nmetric: mape\nhyperparams: Prophet changepoint / seasonality\ngates: max_mape=0.08, min_accuracy=92%"]
        C2["fraud_detection/config.yaml\nmodel: fraud-detection v1\nobjective: binary_classification\nmetric: auc\nhyperparams: XGBoost max_depth=8, n_est=1000\ngates: min_auc_roc=0.98, max_fpr=0.05"]
        C3["search_ranking/config.yaml\nmodel: search-ranking v1\nobjective: lambdarank\nmetric: ndcg@10\nhyperparams: LightGBM LambdaMART\ngates: min_ndcg@10=0.65"]
        C4["personalization/config.yaml\nmodel: personalization v1\nobjective: recommendation\nmetric: ndcg@10\nhyperparams: Two-Tower NCF, embedding_dim=64\ngates: min_hit@10=0.30, min_ndcg@10=0.20"]
        C5["eta_prediction/config.yaml"]
        C6["clv_prediction/config.yaml"]
    end

    subgraph VERTEX["Vertex AI Training (GCP)\nOrchestrated by ml_training DAG\n(data-platform/airflow/dags/ml_training.py)\nschedule: '0 4 * * *'"]
        V1["demand-forecast training image\nn1-standard-8 + T4 GPU"]
        V2["fraud-detection training image\nn1-standard-4"]
        V3["search-ranking training image\nn1-standard-8 + T4 GPU"]
        V4["personalization training image\nn1-standard-8 + T4 GPU"]
    end

    subgraph EVAL["ModelEvaluator (ml/eval/evaluate.py)\nMlflow tracking: mlflow.instacommerce.internal"]
        EV1["compute_metrics()\nClassification: AUC-ROC, F1, precision, recall\nRegression: MAE, RMSE, MAPE, R²\nRanking: NDCG@10, MAP@10, MRR, Hit@10"]
        EV2["check_gates()\nmin_* / max_* threshold checks\nALL gates must pass"]
        EV3["bias_check()\nDemographic parity\nEqualized odds (TPR/FPR per group)\nDisparate impact ≥ 0.8 (4/5 rule)\nGroups: gender, age_bucket, city_tier, language"]
    end

    subgraph GATE{"Promotion Gate\n(BranchPythonOperator\nml_training DAG)"}
        PASS["gates_passed = true\nAND bias_check.passed = true"]
        FAIL_G["gates_passed = false\nOR bias_report.passed = false"]
    end

    subgraph REGISTRY["Model Registry (ml/serving/model_registry.py)"]
        MR["register() → clears kill switch\nA/B routing weights configured\nKill switch → forces DEGRADED\n→ rule-based fallback"]
        MLFLOW_LOG["MLflow: log_metrics()\nlog_param(execution_date, job_id)\nartifact: gs://{project}-ml-artifacts/{model}/{date}/"]
    end

    subgraph SKIP["Skip Promote Path"]
        SK["Model stays at previous version\nSlack alert: ml-alerts@instacommerce.com\nMLflow run tagged 'REJECTED'\nManual investigation required"]
    end

    T1 --> C1 --> V1
    T2 --> C2 --> V2
    T3 --> C3 --> V3
    T4 --> C4 --> V4

    V1 & V2 & V3 & V4 --> EV1 --> EV2 --> EV3 --> GATE

    GATE --> PASS --> MR --> MLFLOW_LOG
    GATE --> FAIL_G --> SKIP

    MLFLOW_LOG -->|"Vertex AI Endpoint deploy\ntraffic_percentage=100\nmin_replicas=1, max_replicas=3"| PROD_ENDPOINT(["Production Endpoint\n→ ai-inference-service"])

    style PASS fill:#8f8,stroke:#080
    style FAIL_G fill:#f88,stroke:#c00
    style SKIP fill:#ffd,stroke:#aa0
```

**Promotion gate details per model:**

| Model | Primary metric | Gate threshold | Bias groups |
|-------|---------------|----------------|-------------|
| `demand-forecast` | MAPE | ≤ 0.08 (8%) + accuracy ≥ 92% | N/A (time-series) |
| `fraud-detection` | AUC-ROC | ≥ 0.98 + FPR ≤ 0.05 | gender, age_bucket, city_tier |
| `search-ranking` | NDCG@10 | ≥ 0.65 + min improvement 2% | N/A |
| `personalization` | Hit@10 + NDCG@10 | ≥ 0.30 / 0.20 | N/A |
| `eta-prediction` | MAE (minutes) | ≤ 3.0 min | N/A |

**Minimum improvement guard:** Every config includes `min_improvement_pct` (1–3%). A new model that meets absolute thresholds but regresses versus the current production version is still rejected. This prevents silent degradation from noisy retraining runs.

---

## 6. Inference Serving — Shadow Mode and A/B Routing

```mermaid
flowchart TD
    subgraph CALLER["Callers"]
        BFF["mobile-bff-service"]
        AGW["admin-gateway-service"]
        ORC["ai-orchestrator-service"]
        SVС["Java domain services\n(checkout, fraud, pricing, inventory)"]
    end

    subgraph INF_SVC["ai-inference-service (FastAPI :8000)\nservices/ai-inference-service/app/"]
        RT["Routes\nPOST /predict/{model_name}"]
        CACHE["LRU Cache\n(cache_ttl=300 s, max_items=1000)\nenabled via AI_INFERENCE_CACHE_ENABLED"]
        FEAT_FETCH["Feature hydration\nOnline store (Redis) or BigQuery\nAI_INFERENCE_FEATURE_STORE_BACKEND"]
    end

    subgraph MODELS["Model Predictors (app/models/)"]
        MD1["demand_model.py → demand-forecast"]
        MD2["fraud_model.py → fraud-detection\nauto_approve < 30\nsoft_review 30–70\nblock > 100"]
        MD3["ranking_model.py → search-ranking"]
        MD4["personalization_model.py\ncold-start: 0 orders → zone popularity\n1-3 orders → content-based\n4+ orders → collaborative"]
        MD5["eta_model.py"]
        MD6["clv_model.py"]
        MD7["dynamic_pricing_model.py"]
    end

    subgraph REGISTRY_SVC["Model Registry (ml/serving/model_registry.py)"]
        KS["Kill switch\n→ ModelStatus.DEGRADED\n→ rule-based fallback"]
        AB_R["A/B Router\nweighted traffic split\nper model_name + version"]
        VERSION["Version management\nhot-reload without restart"]
    end

    subgraph SHADOW["Shadow Mode (ml/serving/shadow_mode.py)"]
        SH_RUN["ShadowRunner.run_shadow()\nAsync ThreadPoolExecutor\ntimeout: 1.0 s\nresult NEVER returned to caller"]
        SH_LOG["Comparison metrics logged:\nagreement_rate, latency_delta\n→ MLflow / OTEL for offline analysis"]
    end

    subgraph MONITOR["Model Monitoring (ml/serving/monitoring.py)"]
        DRIFT["Feature drift detection\nPSI (Population Stability Index)"]
        PERF["Online performance tracking\nprediction distribution\nconfidence histograms"]
        ALERT["Alert on drift / degradation\n→ triggers retraining"]
    end

    subgraph FALLBACKS["Fallbacks (per feature view)"]
        FB1["demand: 52-week moving avg"]
        FB2["fraud: rules_based engine"]
        FB3["personalization: zone_popularity"]
        FB4["search: popularity_based"]
        FB5["eta: static zone-level avg"]
    end

    CALLER -->|"POST /predict/{model}"| RT
    RT --> CACHE
    CACHE -->|"cache miss"| FEAT_FETCH
    FEAT_FETCH -->|"Redis online features"| MODELS
    FEAT_FETCH -->|"Redis unavailable"| FALLBACKS

    MODELS --> REGISTRY_SVC
    REGISTRY_SVC -->|"KS active"| FALLBACKS
    REGISTRY_SVC -->|"A/B split"| MD1 & MD2 & MD3 & MD4
    REGISTRY_SVC -->|"shadow config"| SHADOW

    SHADOW --> SH_RUN --> SH_LOG
    MODELS --> MONITOR
    MONITOR -->|"drift detected"| ALERT
    ALERT -->|"retrain signal"| RETRAIN(["→ ml_training Airflow DAG"])

    MD2 -->|"score 30–70"| SOFT_REVIEW(["→ fraud-detection-service\nmanual review queue"])
    MD2 -->|"score > 100"| BLOCK(["→ checkout-orchestrator\norder blocked"])

    style BLOCK fill:#f88,stroke:#c00
    style SOFT_REVIEW fill:#ffd,stroke:#aa0
    style FALLBACKS fill:#e8f4fd,stroke:#369
```

**Shadow mode safety properties (`ml/serving/shadow_mode.py`):**
1. Shadow prediction runs in a background thread with a **1-second hard timeout** (`_SHADOW_TIMEOUT_S`). Timeout is silently swallowed; production result is unaffected.
2. Shadow result is **never returned** to the caller; only comparison metrics are logged.
3. Shadow thread pool is bounded (`max_workers=4`) to prevent unbounded resource consumption.
4. `ShadowRunner` tracks `agreement_rate` — if agreement drops below threshold, this surfaces in monitoring as a signal to investigate the candidate model before promotion.

---

## 7. LangGraph AI Orchestrator State Machine

```mermaid
stateDiagram-v2
    [*] --> classify_intent : User request arrives\n(POST /chat or /task)

    classify_intent --> check_policy : IntentType assigned\n(SUBSTITUTE / SUPPORT / RECOMMEND\nSEARCH / ORDER_STATUS / UNKNOWN)

    check_policy --> retrieve_context : needs_escalation = false\nrisk_level = LOW or MEDIUM
    check_policy --> escalate : needs_escalation = true\nOR risk_level = HIGH / CRITICAL

    retrieve_context --> execute_tools : RAG context loaded\n(vector search + order history)

    execute_tools --> validate_output : ToolResults appended\nto state.tool_results[]

    validate_output --> respond : ValidationStatus = PASSED\nOR WARNING (no error violations)
    validate_output --> escalate : ValidationStatus = FAILED\n(error-level violation)

    respond --> [*] : Final answer returned to caller
    escalate --> [*] : Human review ticket created\npartial response to caller

    note right of check_policy
        app/graph/nodes.py: check_policy()
        Reads: BudgetTracker (token + latency ceilings)
        RiskLevel enum: LOW / MEDIUM / HIGH / CRITICAL
        Settings: loaded from env via app/config.py
        Guardrails: rate_limiter.py (per user per minute)
                    injection.py (prompt injection scan)
    end note

    note right of validate_output
        app/guardrails/output_validator.py
        OutputPolicy checks (per intent):
        1. Schema: required fields present
        2. Business rules:
           refund ≤ $500 (50_000 cents)
           discount ≤ 30%
        3. Content safety: blocked phrases
        4. Citation: claims reference tool_results
    end note

    note right of execute_tools
        app/graph/tools.py — ToolRegistry
        Each tool = HTTP call to a Java service
        Circuit breaker: 3 failures → OPEN (30 s reset)
        Retry: exponential back-off, max_retries
        Idempotency: X-Idempotency-Key header on writes
        Timeout: 2.5 s per tool
    end note

    note right of escalate
        app/guardrails/escalation.py
        Creates human review record
        Logs: session_id, intent, risk_level
        Returns partial safe response to caller
    end note
```

**Node-to-file mapping:**

| Graph node | Source file | Key concern |
|------------|-------------|-------------|
| `classify_intent` | `app/graph/nodes.py` | IntentType enum assignment |
| `check_policy` | `app/graph/nodes.py` + `app/guardrails/rate_limiter.py`, `injection.py` | Budget, risk level, injection scan |
| `retrieve_context` | `app/graph/nodes.py` | RAG: vector search + order history fetch |
| `execute_tools` | `app/graph/nodes.py` + `app/graph/tools.py` | Circuit-broken HTTP to Java services |
| `validate_output` | `app/guardrails/output_validator.py` | Schema, business rules, content safety, citations |
| `respond` | `app/graph/nodes.py` | Assembles final response, PII scrub |
| `escalate` | `app/guardrails/escalation.py` | Human review ticket, partial response |

**Budget and cost tracking (`app/graph/budgets.py`):**  
`BudgetTracker` enforces hard ceilings on LLM token spend and total graph execution latency. If `BudgetExceededError` is raised inside any node, it is caught, appended to `state.errors`, and the graph routes to `escalate` rather than `respond`. This prevents runaway LLM costs from a single malformed request.

---

## 8. Safe Proposal-Only AI Loop

The orchestrator **never writes to production systems autonomously**. Every write action is a *proposal* that requires either automated policy validation or human activation.

```mermaid
flowchart TD
    subgraph TRIGGER["Trigger Sources"]
        T1["User request via mobile-bff-service\n(chat / task endpoint)"]
        T2["Scheduled job via admin-gateway-service\n(e.g. daily pricing review)"]
        T3["Inference alert\n(demand spike, fraud burst, drift detected)"]
    end

    subgraph ORCH["ai-orchestrator-service\n(LangGraph :8100)"]
        CI["classify_intent\n(SUBSTITUTE / SUPPORT / RECOMMEND\nORDER_STATUS / UNKNOWN)"]
        CP["check_policy\n• risk_level assessment\n• rate limit check\n• prompt injection scan\n• budget ceiling check"]
        RC["retrieve_context\n(RAG over order + product + policy docs)"]
        ET["execute_tools\n(read-only tool calls:\nGET order status, GET inventory,\nGET product info, GET pricing rules)"]
        VO["validate_output\n• schema conformance\n• refund ≤ $500\n• discount ≤ 30%\n• no blocked phrases\n• citations verified"]
        RS["respond\n(PROPOSAL only — no write operations\nmade by this node)"]
    end

    subgraph PROPOSAL["Proposal Object"]
        PR["Structured proposal:\n• intent: e.g. 'substitute_item'\n• action: e.g. 'replace SKU-123 with SKU-456'\n• confidence: 0.0–1.0\n• evidence: [tool_result_ids]\n• estimated_impact: {revenue_delta, risk_score}"]
    end

    subgraph ACTIVATION["Activation Gate (3 paths)"]
        AUTO["Auto-activate path\nConditions:\n• risk_level = LOW\n• confidence ≥ 0.95\n• action type in auto-allowed set\n• no budget violation\n• output validation PASSED"]
        HUMAN["Human review path\nConditions:\n• risk_level ≥ MEDIUM\n• confidence < 0.85\n• action type requires approval\n• output validation WARNING"]
        BLOCK_PATH["Block path\nConditions:\n• risk_level = CRITICAL\n• validation FAILED\n• injection detected\n• budget exceeded"]
    end

    subgraph SERVICES["Downstream Java Services (write operations)"]
        PS["pricing-service\n(POST /pricing/rules)"]
        IS["inventory-service\n(POST /inventory/reorder)"]
        NS["notification-service\n(POST /notifications/send)"]
        WS["wallet-loyalty-service\n(POST /wallet/credits)"]
    end

    subgraph AUDIT["Audit Trail"]
        AT["audit-trail-service\nLogs: proposal_id, action, activator,\ntimestamp, risk_level, confidence,\ntool_results, validation_result"]
    end

    TRIGGER --> CI
    CI --> CP
    CP -->|"needs_escalation = false"| RC
    CP -->|"needs_escalation = true"| ESC(["escalate → human queue"])
    RC --> ET
    ET --> VO
    VO --> RS
    RS --> PROPOSAL

    PROPOSAL --> AUTO
    PROPOSAL --> HUMAN
    PROPOSAL --> BLOCK_PATH

    AUTO -->|"system activates\nwithout human"| PS & IS & NS & WS
    HUMAN -->|"operator approves"| PS & IS & NS & WS
    HUMAN -->|"operator rejects"| REJECTED(["proposal archived\nfeedback logged"])
    BLOCK_PATH --> BLOCKED(["proposal discarded\nno write operations\nalert raised"])

    PS & IS & NS & WS --> AT
    REJECTED --> AT
    BLOCKED --> AT

    style AUTO fill:#dfd,stroke:#080
    style HUMAN fill:#ffd,stroke:#aa0
    style BLOCK_PATH fill:#fdd,stroke:#c00
    style BLOCKED fill:#f88,stroke:#c00
    style REJECTED fill:#eee,stroke:#888
```

**Why proposal-only is safe:**

1. **No write tools in the LangGraph:** `app/graph/tools.py` only registers read operations (GET endpoints). All write calls live outside the graph in the activation layer, which runs with explicit human or policy approval.
2. **Output validator as final check:** `OutputPolicy.validate()` in `app/guardrails/output_validator.py` catches policy violations (refund caps, discount caps, schema errors, blocked phrases) *before* the proposal reaches the activation gate.
3. **Risk-level gating:** `RiskLevel.HIGH` / `CRITICAL` always routes to `escalate`; they can never reach `respond` via the normal path.
4. **Audit trail:** Every proposal — accepted, rejected, or blocked — is logged to `audit-trail-service` with full context for forensic replay.
5. **Budget ceiling:** `BudgetTracker` prevents a single conversation from exhausting token budget and creating unbounded cost exposure.

---

## 9. Feedback and Label Refresh Loop

```mermaid
flowchart LR
    subgraph PREDICTIONS["Model Predictions Served"]
        P1["fraud_score\n(ai-inference-service)"]
        P2["demand_forecast\n(ai-inference-service)"]
        P3["eta_prediction\n(ai-inference-service)"]
        P4["search ranking\n(ai-inference-service)"]
        P5["personalization recs\n(ai-inference-service)"]
    end

    subgraph OUTCOMES["Outcome Events (Kafka)"]
        O1["order.delivered / order.cancelled\n(fulfillment-service → Kafka)"]
        O2["fraud.confirmed / fraud.cleared\n(fraud-detection-service → Kafka)"]
        O3["item.clicked / item.purchased\n(catalog-service / order-service → Kafka)"]
        O4["search.clicked / search.ignored\n(search-service → Kafka)"]
        O5["rider.delivered_on_time / late\n(rider-fleet-service → Kafka)"]
    end

    subgraph LABEL_COLLECTION["Label Collection Pipeline\n(data-platform/streaming/ + dbt)"]
        LC1["Join prediction log + outcome event\non prediction_id / order_id\n(Apache Beam, 24-h watermark)"]
        LC2["stg_fraud_labels.sql\nstg_delivery_outcomes.sql\nstg_search_click_labels.sql"]
        LC3["int_* joins for label quality\n(deduplication, late-arrival handling)"]
        LC4["analytics.training_fraud_labels ←updated\nanalytics.training_demand_history ←updated\nanalytics.training_search_clicks ←updated\nanalytics.training_user_interactions ←updated"]
    end

    subgraph DRIFT_MONITOR["Model Monitoring (ml/serving/monitoring.py)"]
        DM["PSI drift detection\nMonitor: prediction distribution\nAlert threshold: PSI > 0.2"]
        SHADOW_COMP["ShadowRunner agreement rate\nAlert if < 85% agreement"]
    end

    subgraph TRIGGERS["Retraining Triggers"]
        TR1["Scheduled: ml_training DAG\n04:00 UTC daily\n(data-platform/airflow/dags/ml_training.py)"]
        TR2["Drift alert: PSI > 0.2\n→ immediate retrain trigger\nvia Airflow API"]
        TR3["Shadow alert: agreement < 85%\n→ hold new model promotion"]
        TR4["Manual: operator-triggered\nvia Airflow web UI"]
    end

    subgraph RETRAIN["Retrain → Re-evaluate → Re-promote"]
        RT["ml/train/*/config.yaml\n→ Vertex AI job\n→ ml/eval/evaluate.py\n→ promotion gates\n→ ml/serving/model_registry.py"]
    end

    P1 -->|"prediction log event\nprediction_id + score"| LC1
    P2 --> LC1
    P3 --> LC1
    P4 --> LC1
    P5 --> LC1

    O1 & O2 & O3 & O4 & O5 --> LC1

    LC1 --> LC2 --> LC3 --> LC4

    LC4 -->|"fresh training data"| TR1
    P1 & P2 & P3 & P4 --> DM
    DM -->|"PSI > 0.2"| TR2
    SHADOW_COMP -->|"agreement < 85%"| TR3

    TR1 & TR2 & TR4 --> RT
    TR3 -->|"blocks candidate promotion"| REGISTRY_HOLD(["Model registry: candidate\nstays in shadow"])

    RT -->|"gates pass → new production version"| PROD_UPDATE(["ai-inference-service\nupdates to new model version"])
```

**Label quality controls:**
- The label-join Beam pipeline uses a **24-hour watermark** — outcomes arriving up to 24 hours after prediction are still joined. Outcomes beyond the watermark are collected in the dead-letter table for manual analysis.
- Fraud labels have a **chargeback lag** of 30–90 days. Training data covers 180 days to capture enough confirmed fraud labels; the model is retrained daily but the chargeback signal lags. This means the model lags on new fraud patterns by up to 30 days — a known limitation documented in `ml/mlops/model_card_template.md`.
- `min_interactions_per_user: 3` in `personalization/config.yaml` prevents sparse users from being included in the training dataset, reducing noise in collaborative filtering.

---

## 10. Model and Feature Lineage Map

```mermaid
graph LR
    subgraph FS["Feature Store (ml/feature_store/)"]
        UF["user_features.yaml\n(order_count_30d, avg_basket,\nclv_segment, cancellation_rate)"]
        PF["product_features.yaml\n(sales_velocity_7d, avg_rating,\nembedding_vector, price_cents)"]
        SF["store_features.yaml\n(current_hour_orders, utilization_pct)"]
        RF["rider_features.yaml\n(on_time_rate, avg_delivery_min)"]
        QF["search_features.yaml\n(ctr_7d, conversion_rate_30d)"]
    end

    subgraph FV_MAP["Feature Views → Models"]
        FV_D["demand_forecast_view\n(product + store features)\nrefs: demand_prophet_v1"]
        FV_F["fraud_detection_view\n(user features + request signals)\nrefs: fraud_xgboost_v1\nscoring: 30 / 70 / 100 thresholds"]
        FV_P["personalization_view\n(user + product features)\nrefs: personalization_two_tower_v1\ncold-start: 0/1-3/4+ orders"]
        FV_S["search_ranking_view\n(user + product features)\nrefs: search_ranking_lambdamart_v1\n      search_ranking_two_tower_v2"]
        FV_E["eta_prediction_view\n(rider + store features)"]
    end

    subgraph MODELS_MAP["Models (ml/train/ + ml/serving/)"]
        M_D["demand-forecast v1\nProphet (phase 1)\nTFT v2 (phase 2)\nml/train/demand_forecast/"]
        M_F["fraud-detection v1\nXGBoost\nml/train/fraud_detection/"]
        M_P["personalization v1\nTwo-Tower NCF\nml/train/personalization/"]
        M_S["search-ranking v1\nLambdaMART (phase 1)\nTwo-Tower (phase 2)\nml/train/search_ranking/"]
        M_E["eta-prediction v1\nml/train/eta_prediction/"]
        M_C["clv-prediction v1\nml/train/clv_prediction/"]
    end

    subgraph SERVING_MAP["Serving (services/ai-inference-service/app/models/)"]
        SV_D["demand_model.py"]
        SV_F["fraud_model.py"]
        SV_P["personalization_model.py"]
        SV_S["ranking_model.py"]
        SV_E["eta_model.py"]
        SV_C["clv_model.py"]
        SV_DP["dynamic_pricing_model.py"]
    end

    subgraph CONSUMERS["Downstream Service Consumers"]
        CS1["inventory-service\n(reorder when demand > stock + pipeline)"]
        CS2["warehouse-service\n(staff scheduling by demand forecast)"]
        CS3["pricing-service\n(markdown perishables on low demand)"]
        CS4["fraud-detection-service\n(block / soft-review / auto-approve)"]
        CS5["search-service\n(rerank results by LambdaMART score)"]
        CS6["mobile-bff-service\n(personalized home + buy-again)"]
        CS7["routing-eta-service\n(ETA on checkout + tracking)"]
        CS8["checkout-orchestrator-service\n(fraud gate during checkout saga)"]
    end

    UF --> FV_F & FV_P & FV_S
    PF --> FV_D & FV_P & FV_S
    SF --> FV_D & FV_E
    RF --> FV_E
    QF --> FV_S

    FV_D --> M_D --> SV_D --> CS1 & CS2 & CS3
    FV_F --> M_F --> SV_F --> CS4 & CS8
    FV_P --> M_P --> SV_P --> CS6
    FV_S --> M_S --> SV_S --> CS5
    FV_E --> M_E --> SV_E --> CS7
    UF --> M_C --> SV_C --> CS6
    M_D & M_F --> SV_DP --> CS3
```

**Lineage tracking in practice:**
- Every feature view YAML carries `model_refs` tying it to specific model versions. Changing a feature SQL file requires updating the `model_refs` to point to a new version.
- MLflow tracks `execution_date`, `job_id`, training data query, and all metrics per run. Artifact URI convention: `gs://{project}-ml-artifacts/{model_name}/{execution_date}/model`.
- `ml/mlops/model_card_template.md` defines the canonical metadata fields (intended use, training data, limitations, bias mitigations) that must be filled out at promotion time.

---

## 11. Control Points Summary

```mermaid
flowchart TD
    subgraph CP1["Control Point 1 — Event Parsing\n(data-platform/streaming/pipelines/)"]
        C1A["Dead-letter side output on parse error\nNo silent data loss"]
        C1B["Required fields checked\n(order_id not null)"]
    end

    subgraph CP2["Control Point 2 — dbt Staging Tests\n(data-platform/dbt/models/staging/schema.yml)"]
        C2A["not_null, unique, accepted_values\nPer-column assertions"]
        C2B["DAG aborts on failure\nSlack alert sent"]
    end

    subgraph CP3["Control Point 3 — Great Expectations Suites\n(data-platform/quality/expectations/)"]
        C3A["Row count bounds\nColumn range validation\nReferential integrity"]
        C3B["Blocks intermediate layer\nif suite fails"]
    end

    subgraph CP4["Control Point 4 — dbt Test (Intermediate + Marts)\n(data-platform/dbt/models/*/schema.yml)"]
        C4A["Relationship tests\nFreshness assertions\nMetric range checks"]
        C4B["Marts not promoted on test failure"]
    end

    subgraph CP5["Control Point 5 — Feature Store TTL + Fallbacks\n(ml/feature_store/feature_views/*.yaml)"]
        C5A["Online TTL: 5–60 min per view\nStale → fallback activates"]
        C5B["Fallback strategies:\ndemand: 52-week avg\nfraud: rules-based\npersonalization: zone popularity"]
    end

    subgraph CP6["Control Point 6 — Model Evaluation Gates\n(ml/eval/evaluate.py + ml/train/*/config.yaml)"]
        C6A["Primary metric threshold (per model)\nMin improvement vs current prod\nBias check (4/5 rule)"]
        C6B["Rejected models not deployed\nSlack alert, MLflow tagged REJECTED"]
    end

    subgraph CP7["Control Point 7 — Model Registry Kill Switch\n(ml/serving/model_registry.py)"]
        C7A["Per-model kill switch\nForces ModelStatus.DEGRADED\nRule-based fallback"]
        C7B["A/B routing weights\nGradual traffic shift\nShadow mode before promotion"]
    end

    subgraph CP8["Control Point 8 — Inference-Level Fraud Thresholds\n(ml/feature_store/feature_views/fraud_detection_view.yaml)"]
        C8A["Score < 30: auto-approve\nScore 30–70: soft review\nScore > 100: block"]
        C8B["Configurable via env vars\nNo code change required"]
    end

    subgraph CP9["Control Point 9 — LangGraph Policy Check\n(app/graph/nodes.py + app/guardrails/)"]
        C9A["Rate limit (per user per minute)\nPrompt injection scan\nBudget ceiling (token + latency)"]
        C9B["needs_escalation routes to human\nBudget exceeded → escalate"]
    end

    subgraph CP10["Control Point 10 — Output Validator\n(app/guardrails/output_validator.py)"]
        C10A["Schema: required fields per intent\nBusiness: refund ≤ $500, discount ≤ 30%\nContent safety: blocked phrases\nCitations: claims reference tool results"]
        C10B["FAILED → escalate\nWARNING → respond with caveat\nPASSED → respond"]
    end

    subgraph CP11["Control Point 11 — Proposal Activation Gate\n(Safe Proposal-Only Architecture)"]
        C11A["No write tools in LangGraph\nAll writes behind activation gate\nAudit trail for every action"]
        C11B["AUTO: LOW risk + high confidence\nHUMAN: MEDIUM/HIGH risk\nBLOCK: CRITICAL risk or FAILED validation"]
    end

    CP1 --> CP2 --> CP3 --> CP4 --> CP5 --> CP6 --> CP7 --> CP8 --> CP9 --> CP10 --> CP11
```

---

## 12. Late Data Handling Strategy

Late data is a first-class concern at every pipeline stage. The following table summarizes the strategy at each layer:

| Layer | Mechanism | Late window | Behavior on late data |
|-------|-----------|-------------|----------------------|
| **Beam streaming** (`data-platform/streaming/`) | `AfterWatermark` + `AfterProcessingTime`, `ACCUMULATING` mode | 30 min (SLA window) | Re-fires window; events beyond horizon → dead-letter table |
| **dbt staging** (`data-platform/dbt/models/staging/`) | `dbt_staging` DAG runs every 2 h; `catchup=False` | 2 h (next run) | Missed window not backfilled automatically; manual trigger required |
| **dbt marts** (`data-platform/dbt/models/marts/`) | `ExternalTaskSensor` waits for staging; `timeout=3600 s` | 1 h sensor wait | Marts wait for staging; if staging > 1 h late, DAG fails and alerts |
| **Feature store online** (`ml/feature_store/feature_views/`) | Redis TTL per view (5–60 min) + fallback | Per-view TTL | Stale features → per-view fallback strategy; no cascading failure |
| **Feature store offline** (`ml/feature_store/`) | Point-in-time correct joins; `ml_feature_refresh` hourly | 1 h | Training snapshot uses last-known state; late labels are excluded from current training window |
| **Fraud label join** (`data-platform/streaming/`) | Beam 24-h watermark | 24 h | Labels within 24 h joined; beyond → dead-letter for manual chargeback reconciliation |
| **Chargeback labels** (`ml/train/fraud_detection/config.yaml`) | Training data covers 180 days | 30–90 day chargeback lag | Model lags new fraud patterns by chargeback lag; documented in model card |
| **Shadow mode** (`ml/serving/shadow_mode.py`) | 1-second timeout per shadow call | 1 s | Timeout silently dropped; shadow metrics show missing comparisons but production is unaffected |
| **LangGraph tools** (`app/graph/tools.py`) | 2.5 s per-tool timeout + circuit breaker | 2.5 s / 30 s open | Timeout → tool error appended to `state.errors`; circuit opens after 3 failures; errors surface in `respond` node gracefully |

**Key design principle:** No layer silently discards late data. Every stage either re-processes (Beam accumulating windows, feature TTL fallback) or explicitly routes to a dead-letter/audit path. The combination of fallback strategies and dead-letter routing means a late-data event causes **graceful degradation** rather than incorrect predictions or missing records.

---

*Document generated for InstaCommerce Iteration 3 diagram series. Repo paths verified against `settings.gradle.kts`, `.github/workflows/ci.yml`, and source files as of this review pass.*
