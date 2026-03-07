# Iteration-3 · Data Platform Dataflow Diagrams
**Principal Review — InstaCommerce**  
_All evidence sourced from repo artefacts in `data-platform/`, `ml/`, `services/ai-*`, `contracts/`, and `services/stream-processor-service/`._

---

## How to read these diagrams

| Symbol / style | Meaning |
|---|---|
| `⛨ GATE` label | Quality gate or governance checkpoint — failure blocks progress |
| `◈ GOVERN` label | Governance checkpoint — schema version, ownership, or compliance check |
| `→` solid arrow | Synchronous or near-real-time data movement |
| `-→` dashed arrow | Async, scheduled, or indirect signal |
| Red/`:::alert` nodes | Known gaps, risks, or unimplemented paths (Roadmap items) |

---

## Diagram 1 — End-to-End: Operational Events → Analytics Warehouse

> **Scope:** how every write in a transactional service eventually becomes a queryable row in BigQuery.  
> Two paths exist: a **streaming path** (sub-minute latency) and a **batch/dbt path** (hours).

```mermaid
flowchart LR
    subgraph ops["Operational Services (20 Java + 8 Go)"]
        svc1["order-service\nPayment/Inventory/Fulfillment\nRider/Catalog/Fraud …"]
    end

    subgraph cdc["Change Data Capture"]
        pg["PostgreSQL\n(per-service DB)"]
        outbox["Outbox Table\n(transactional write)"]
        deb["Debezium\nConnector"]
    end

    subgraph kafka["Kafka Topics (per contracts/README.md)"]
        t_ord["orders.events"]
        t_pay["payments.events"]
        t_inv["inventory.events"]
        t_cart["cart.events"]
        t_rider["rider.events + rider.location"]
        t_fraud["fraud.events"]
        t_cat["catalog.events · fulfillment.events\nwarehouse.events · identity.events"]
    end

    subgraph stream["⚡ Streaming Path — Apache Beam / Dataflow"]
        p_ord["order_events_pipeline\n1-min fixed window (GMV, vol)\n30-min sliding window (SLA)"]
        p_pay["payment_events_pipeline\n1-min fixed window"]
        p_cart["cart_events_pipeline\n30-sec sliding (funnel)"]
        p_inv["inventory_events_pipeline\n1-min fixed (stock levels)"]
        p_loc["rider_location_pipeline\n5-sec sliding"]
        dlq["Dead-Letter (GCS)\nmalformed events"]
    end

    subgraph gate1["⛨ GATE: Schema Validation"]
        gval["contracts/:contracts:build\nJSON Schema draft-07 validation\nBreaking-change diff vs main"]
    end

    subgraph bq["BigQuery Data Warehouse"]
        bqraw["raw dataset\n(Beam writes)"]
        bqstg["staging dataset\nstg_orders · stg_payments\nstg_users · stg_products\nstg_deliveries · stg_inventory\nstg_searches · stg_cart_events"]
        bqint["intermediate dataset\nint_order_deliveries\nint_user_order_history\nint_product_performance"]
        bqmart["marts dataset\nmart_daily_revenue\nmart_store_performance\nmart_rider_performance\nmart_search_funnel\nmart_product_analytics\nmart_user_cohort_retention\nmart_sponsored_opportunities"]
        bqfeat["features dataset\nuser · product · order\ndelivery features"]
    end

    subgraph gate2["⛨ GATE: Data Quality (Great Expectations)"]
        ge["data_quality DAG · every 2h\norders_suite · payments_suite\nusers_suite · inventory_suite\nBlocks marts DAG on failure\nAlerts Slack on critical fail"]
    end

    subgraph dbt["dbt Transformation (Cloud Composer)"]
        dbt_stg["dbt_staging DAG · every 2h\ndbt run --select staging"]
        dbt_mart["dbt_marts DAG · daily 02:00\nWaits for staging + GE pass\ndbt run --select marts\ndbt test --select marts"]
    end

    subgraph consume["Consumers"]
        bi["📊 Looker / BI"]
        export["📤 GCS exports/\nbi-extracts · partner-feeds"]
    end

    svc1 --> pg --> outbox --> deb
    deb --> t_ord & t_pay & t_inv & t_cart & t_rider & t_fraud & t_cat

    t_ord --> gate1
    gate1 -. "validated events" .-> t_ord

    t_ord --> p_ord
    t_pay --> p_pay
    t_cart --> p_cart
    t_inv --> p_inv
    t_rider --> p_loc

    p_ord & p_pay & p_cart & p_inv --> bqraw
    p_ord & p_pay --> dlq
    p_loc --> bqraw

    bqraw --> dbt_stg --> bqstg
    bqstg --> gate2
    gate2 -- "pass" --> dbt_mart
    gate2 -. "fail → Slack alert\nDAG paused" .-> dbt_mart
    dbt_mart --> bqint --> bqmart
    bqmart --> bi & export
    bqraw --> bqfeat
```

**Notes:**
- Every service uses the **outbox + Debezium** pattern — no direct DB coupling between services.
- `orders.events`, `payments.events`, `inventory.events`, `cart.events`, `rider.location` each have a dedicated Beam pipeline. Dead-letters land in `gs://instacommerce-dataflow/dead-letter/`.
- `dbt_marts` waits on `ExternalTaskSensor` for `dbt_staging` + Great Expectations gate; marts are never built on dirty data.
- `mart_sponsored_opportunities` computes a `monetization_readiness_score` — HIGH/MEDIUM/LOW opportunity tier per store+category.

---

## Diagram 2 — Feature Pipeline: Events → Feature Store → Online Serving

> **Scope:** how raw events are transformed into low-latency features available to ML models at inference time.

```mermaid
flowchart TB
    subgraph raw["BigQuery raw dataset\n(from Beam pipelines)"]
        r_ord["raw.orders"]
        r_pay["raw.payments"]
        r_users["raw.users"]
        r_deliver["raw.deliveries"]
    end

    subgraph sql_feat["Feature SQL (ml/feature_store/sql/)"]
        sq_u["user_features.sql\norder_count_30d · avg_basket_cents\ndays_since_last_order\ncancellation_rate · refund_rate\nlifetime_gmv_cents"]
        sq_p["product_features.sql\nclick_rate · add_to_cart_rate\nconversion_rate · avg_price"]
        sq_r["rider_features.sql\ncompletions · on_time_rate\ncancellation_rate"]
        sq_s["store_features.sql\navg_order_value · daily_volume\noperational_hours"]
        sq_srch["search_features.sql\nquery intent · zero-result rate\nclick-through rate"]
    end

    subgraph dag_feat["ml_feature_refresh DAG · every 4h"]
        compute["compute_user_features\ncompute_product_features\ncompute_order_features\ncompute_delivery_features"]
        push["push_to_online_store\n→ Vertex AI Feature Store\n(instacommerce_features)"]
        gate3["⛨ GATE: validate_feature_freshness\nthreshold: 60 min\nFails DAG + Slack alert if stale"]
    end

    subgraph fstore["Feature Store"]
        bqfeat["BigQuery features dataset\n(offline store)"]
        vertex["Vertex AI Feature Store\n(online serving)\nentities: user · product · rider\nstore · search_query\nfeature views: demand · eta\nfraud · personalization · search_ranking\nonline_ttl: 5–60 min per view"]
        redis["Redis\n(real-time features)\ncart signal · stock level\nrider location"]
    end

    subgraph gate4["◈ GOVERN: Feature View Ownership"]
        fv_owner["ml/feature_store/feature_views/*.yaml\nowner field per view\nserving_config.max_latency_ms\nfallback strategy defined\nbatch_schedule per view"]
    end

    subgraph beam_rt["Real-Time Feature Writes (Beam)"]
        cart_rt["cart_events_pipeline\n→ Redis (30-sec sliding)\ncart add/remove signals"]
        inv_rt["inventory_events_pipeline\n→ Redis (1-min fixed)\ncurrent stock levels"]
        loc_rt["rider_location_pipeline\n→ Redis (5-sec sliding)\nrider GPS coordinates"]
    end

    r_ord & r_pay & r_users & r_deliver --> sq_u & sq_p & sq_r & sq_s & sq_srch
    sq_u & sq_p & sq_r & sq_s & sq_srch --> compute
    compute --> bqfeat
    bqfeat --> push --> vertex
    push --> gate3
    gate3 -. "alert if stale" .-> push

    gate4 -. "governs" .-> vertex

    beam_rt --> redis
    cart_rt --> redis
    inv_rt --> redis
    loc_rt --> redis

    vertex & redis --> fstore
```

**Notes:**
- `ml_feature_refresh` runs every 4 hours and pushes to **Vertex AI Feature Store** via `entity_type.ingest_from_bq()`.
- Redis holds the freshest signals (cart, stock, location) — read by `ai-inference-service` via `feature_store_backend=redis`.
- The `fraud_detection_view.yaml` defines `online_ttl_minutes: 5` and `max_latency_ms: 100` with a `rules_based` fallback — critical safety net if the feature store is unavailable.
- Five **feature view YAML files** carry explicit `owner`, `serving_config`, and `model_refs` — this is the governance anchor.

---

## Diagram 3 — ML Inference: Request-Time Scoring

> **Scope:** how a live request through `ai-inference-service` becomes a model score, with shadow mode, caching, and monitoring.

```mermaid
flowchart LR
    subgraph callers["Callers"]
        fraud_svc["fraud-detection-service\n(Java) at checkout"]
        pricing_svc["pricing-service\n(Java) demand signal"]
        search_svc["search-service\n(Java) ranking"]
        bff["mobile-bff-service\n(Java) personalization · ETA"]
    end

    subgraph inf["ai-inference-service (FastAPI :8000)"]
        cache["LRU Cache\nttl: 5 min · max 1000 items\n(env AI_INFERENCE_CACHE_*)"]
        feat_read["Feature Read\nVertex AI Feature Store (batch)\nor Redis (real-time)\nor BigQuery fallback"]

        subgraph models["Model Predictors"]
            eta["eta_predictor\nlinear regression\neta-linear-v1"]
            rank["ranking_predictor\nlearning-to-rank\nranking-linear-v1"]
            fraud["fraud_predictor\nlogistic / XGBoost\nfraud-logit-v1\nauto_approve: <30\nsoft_review: 30–70\nblock: >70"]
            person["personalization_predictor\ncollaborative filtering"]
            demand["demand_predictor\ntime-series / gradient boost"]
            clv["clv_predictor\nsurvival model"]
        end

        shadow["ShadowRunner\n(ml/serving/shadow_mode.py)\nruns candidate in background\ntimeout: 1s\nnever serves shadow result\nlogs agreement_rate"]
        monitor["ModelMonitor\n(ml/serving/monitoring.py)\nPrometheus: prediction_count\nprediction_latency_seconds\nmodel_error_rate\nmodel_drift_psi"]
        registry["ModelRegistry\n(ml/serving/model_registry.py)\nloads weights from GCS\nmanages model versions"]
    end

    subgraph gate5["⛨ GATE: Promotion Gate (Airflow ml_training)"]
        pg_demand["demand_forecast\nRMSE ≤ 0.15 (minimize)"]
        pg_eta["delivery_eta\nMAE ≤ 3.0 min (minimize)"]
        pg_rank["search_ranking\nNDCG@10 ≥ 0.75 (maximize)"]
        pg_fraud["fraud_detection\nF1 ≥ 0.90 (maximize)"]
    end

    subgraph prom["Prometheus / Grafana"]
        p_drift["model_drift_psi\nPSI > 0.10 → WARNING\nPSI > 0.20 → ALERT"]
        p_err["model_error_rate"]
        p_lat["prediction_latency_p99"]
    end

    callers --> cache
    cache -. "hit" .-> callers
    cache -. "miss" .-> feat_read
    feat_read --> models
    models --> shadow
    models --> monitor
    monitor --> prom
    shadow -. "comparison logged\nnot served" .-> monitor

    gate5 -- "promoted model\n→ Vertex AI Endpoint" --> registry
    registry --> models

    p_drift -. "PSI > 0.2 alert\n→ triggers retrain" .-> gate5
```

**Notes:**
- The inference service exposes a **single HTTP endpoint per model type** with `POST /predict/{model_name}`.
- `ShadowRunner` uses a `ThreadPoolExecutor(max_workers=4)` — shadow predictions timeout after 1 s and are discarded, never served.
- `ModelMonitor.check_drift()` uses **Population Stability Index (PSI)**; bins recent predictions against the training baseline distribution.
- Feature freshness is checked per-request via `check_freshness()` with a configurable `max_age_s` (default 1 h).
- The `fraud_predictor` has three scoring thresholds: **auto_approve < 30**, **soft_review 30–70**, **block > 70** — these map directly to the `fraud-detection-service` decision path.

---

## Diagram 4 — AI Agent Flow: LangGraph Orchestrator

> **Scope:** the `ai-orchestrator-service` LangGraph state machine — from inbound query to response or human escalation.

```mermaid
stateDiagram-v2
    [*] --> classify_intent : [POST /agent/invoke<br/>(user query, session_id)]

    classify_intent --> [(check_policy)] : intent classification<br/>(keyword-based, deterministic)
    state check_policy {
        [*] --> policy_eval
        policy_eval --> needs_escalation : risk=HIGH<br/>or UNKNOWN + confidence less than 0.1<br/>or budget_exceeded
        policy_eval --> continue_processing : risk LOW/MEDIUM
    }

    check_policy --> escalate : needs_escalation=true
    check_policy --> retrieve_context : needs_escalation=false

    state retrieve_context {
        [*] --> pii_redact : PIIVault.redact(query)<br/>reversible HMAC vault
        pii_redact --> fetch_context : call tool APIs
    }

    retrieve_context --> execute_tools : context + redacted query

    state execute_tools {
        [*] --> circuit_check : CircuitBreaker per tool<br/>open after 3 failures<br/>reset after 30s
        circuit_check --> tool_call : CLOSED / HALF_OPEN
        circuit_check --> fallback : OPEN
        tool_call --> retry_backoff : transient failure<br/>max_retries + exp backoff
    }

    execute_tools --> validate_output : tool_results

    state validate_output {
        [*] --> pii_scan : scan LLM output<br/>redact EMAIL·PHONE·SSN·CARD·ADDRESS
        pii_scan --> output_check : OutputValidator
        output_check --> pass_validation : passes
        output_check --> flag_unsafe : unsafe / low-confidence
    }

    validate_output --> respond : valid output<br/>PII restored via vault
    validate_output --> escalate : unsafe flag
    respond --> [*] : HTTP 200 + response
    escalate --> [*] : HTTP 200 + escalation ticket

    note right of classify_intent
        Keyword-based (deterministic)<br/>confidence = matched_keywords /<br/>total_intent_keywords<br/>Falls back to UNKNOWN (risk=HIGH)<br/>when no keywords match
    end note

    note right of execute_tools
        Tools call Java services via<br/>httpx with circuit breaker +<br/>X-Idempotency-Key header.<br/>BudgetTracker caps LLM token spend.
    end note

    note right of validate_output
        PIIVault uses reversible HMAC tokens.<br/>PII is redacted BEFORE the LLM sees it,<br/>restored AFTER in the final response.
    end note
```

**Governance checkpoints in the AI agent path:**

| Checkpoint | Where | What it enforces |
|---|---|---|
| `◈ GOVERN: PII Redaction` | `guardrails/pii.py` | PII stripped before LLM, restored after via HMAC vault |
| `◈ GOVERN: Injection Guard` | `guardrails/injection.py` | Prompt injection pattern detection on inbound query |
| `◈ GOVERN: Rate Limiter` | `guardrails/rate_limiter.py` | Per-user/IP request throttling |
| `◈ GOVERN: Output Validator` | `guardrails/output_validator.py` | Schema + safety check on LLM output before serving |
| `◈ GOVERN: Escalation` | `guardrails/escalation.py` | Routes high-risk / low-confidence to human agent |
| `⛨ GATE: Budget Tracker` | `graph/budgets.py` | Caps LLM token spend per session; raises `BudgetExceededError` → escalate |

**Notes:**
- Intent classification is **fully deterministic** (keyword scoring), not LLM-based — latency predictable, debuggable, no hallucination risk at the routing layer.
- Circuit breakers wrap every downstream Java service call from the orchestrator tools — failure in one service (e.g., `order-service`) does not cascade to the AI agent.
- PII vault uses **HMAC-keyed token mapping** with a per-instance `threading.Lock` — thread-safe for the asyncio event loop + thread pool.

---

## Diagram 5 — Reverse-ETL and Activation Loops

> **Scope:** how warehouse insights flow back to operational services to close the product loop.  
> **Status:** partially roadmap — see notes.

```mermaid
flowchart LR
    subgraph wh["BigQuery Marts (Activation-Ready)"]
        m_cohort["mart_user_cohort_retention\nchurn risk signals"]
        m_rev["mart_daily_revenue\nGMV · margin"]
        m_prod["mart_product_analytics\ncategory opportunity"]
        m_spons["mart_sponsored_opportunities\nmonetization_readiness_score\nHIGH / MEDIUM / LOW tier"]
        m_search["mart_search_funnel\nzero-result-rate\nclick-through funnel"]
    end

    subgraph gate6["⛨ GATE: Activation Readiness (G1–G5)"]
        g1["G1: dbt schema tests\n(marts/schema.yml)"]
        g2["G2: Great Expectations\nactivation_suite.yaml (Roadmap)"]
        g3["G3: Freshness check\nstale > threshold → block publish"]
        g4["G4: Reverse ETL row-count reconciliation\n(data-platform-jobs/jobs/reverse_etl.py · Roadmap)"]
        g5["G5: Business guardrail\ncampaign-pressure cap\nmargin floor · churn false-positive cap"]
    end

    subgraph retl["Reverse ETL Layer (P1 Roadmap)"]
        retl_job["reverse_etl_activation.py DAG\n(airflow/dags/ · Roadmap)\nDelta snapshot: new | changed | expired\nper user_id + as_of_date"]
        seg["CustomerSegmentUpserted.v1\n(contracts/activation/ · Roadmap)"]
        churn["ChurnRiskScored.v1\n(contracts/activation/ · Roadmap)"]
        offer["RevenueOfferRecommended.v1\n(contracts/activation/ · Roadmap)"]
    end

    subgraph sinks["Operational Activation Sinks"]
        notif["notification-service\npush / email / SMS triggers"]
        flags["config-feature-flag-service\nsegment-targeted feature flags\nexperiment enrollment"]
        pricing_svc2["pricing-service\ndynamic offer injection"]
        wallet["wallet-loyalty-service\nloyal tier upgrades · promos"]
        catalog2["catalog-service\nsponsored slot allocation"]
    end

    subgraph govern["◈ GOVERN: Data Mesh Contracts"]
        dm["data product ownership in schema.yml\nproducer SLA per mart\nschema_version on every event\n90-day deprecation window"]
    end

    m_cohort & m_rev & m_prod & m_spons & m_search --> gate6
    gate6 -- "passes G1–G5" --> retl_job
    gate6 -. "fail → Slack + DAG pause" .-> retl_job

    retl_job --> seg & churn & offer
    seg --> flags & notif
    churn --> notif & wallet
    offer --> pricing_svc2 & catalog2 & wallet
    govern -. "governs contracts" .-> seg & churn & offer
```

> ⚠️ **Roadmap gap:** `reverse_etl_activation.py` DAG, `data-platform-jobs/jobs/reverse_etl.py`, and `contracts/activation/*.v1.json` schemas are defined in `docs/architecture/WAVE2-DATA-ML-ROADMAP-B.md` as P1 work but **do not yet exist in the repo**. The activation pathway currently ends at mart materialization and GCS exports only.

---

## Diagram 6 — Feedback Loops: Production → Retraining → Promotion

> **Scope:** the closed loop from live model performance back to training, covering drift detection, shadow mode, and CI/CD promotion gates.

```mermaid
flowchart TB
    subgraph prod_inf["Production Inference\n(ai-inference-service)"]
        live_model["Champion model\n(version N)"]
        shadow_cand["Shadow candidate\n(version N+1)\nShadowRunner — never served"]
        monitor2["ModelMonitor\nPrometheus metrics\nPSI drift score per feature\nerror_rate · p99 latency"]
    end

    subgraph alerts["Alerting"]
        psi_warn["PSI > 0.10 → WARNING log"]
        psi_alert["PSI > 0.20 → ALERT log\n+ Slack (ml-alerts@)"]
        stale_alert["Feature freshness fail\n→ ml_feature_refresh DAG alert"]
        shadow_log["Shadow comparison log\nagrees=true/false · score_delta\nagreement_rate metric"]
    end

    subgraph retrain_dag["ml_training DAG — daily 04:00 UTC"]
        train_job["Vertex AI CustomJob\nper model (GPU if needed)\n--execution-date passed as arg"]
        eval_job["Evaluate on held-out set\nMetrics written to\nGCS ml-artifacts/{model}/{date}/metrics.json"]
        mlflow_log["MLflow run log\nmetrics + params + job_id\nhttps://mlflow.instacommerce.internal"]

        subgraph gate7["⛨ GATE: Promotion Gate (per model)"]
            g_demand["demand_forecast: RMSE ≤ 0.15"]
            g_eta["delivery_eta: MAE ≤ 3.0 min"]
            g_rank["search_ranking: NDCG@10 ≥ 0.75"]
            g_fraud["fraud_detection: F1 ≥ 0.90"]
        end

        promote["promote_model\n→ Vertex AI Endpoint\ntraffic_percentage=100\nmin_replica=1 · max_replica=3"]
        skip["skip_promote\n(BranchPythonOperator)\nkeep champion in place\nlog reason to MLflow"]
    end

    subgraph feat_dag["ml_feature_refresh DAG — every 4h"]
        feat_refresh["Recompute features from BQ\npush to Vertex Feature Store\nvalidate freshness ≤ 60 min"]
    end

    subgraph govern2["◈ GOVERN: Model Card + MLOps"]
        mc["ml/mlops/model_card_template.md\nper-model: intended use\nperformance benchmarks\nknown limitations\nbias/fairness evaluation"]
        reg["ml/serving/model_registry.py\nversion manifest in GCS\nloads weights at startup"]
    end

    live_model -. "prediction + features" .-> monitor2
    monitor2 --> psi_warn & psi_alert & stale_alert
    shadow_cand -. "comparison" .-> shadow_log

    psi_alert -. "drift signal\ntriggers investigation" .-> retrain_dag
    stale_alert -. "freshness failure" .-> feat_refresh

    feat_refresh --> train_job
    train_job --> eval_job --> mlflow_log --> gate7
    gate7 -- "passes threshold" --> promote
    gate7 -- "fails threshold" --> skip

    promote --> live_model
    shadow_log -. "agreement_rate low\n→ promote shadow" .-> promote

    govern2 -. "governs" .-> promote
```

**Notes:**
- The feedback loop has **two trigger paths**: (a) scheduled daily retraining regardless of drift, (b) PSI > 0.20 alert that drives on-demand investigation and potentially an off-schedule retrain.
- `ShadowRunner.agreement_rate` is logged but **not yet wired to an automatic promotion trigger** — it requires manual review of shadow comparison logs. This is a gap.
- All model promotions go through `Vertex AI Endpoint.deploy()` with `traffic_percentage=100`; there is **no gradual canary rollout** in the current DAG — a gap for high-risk models like `fraud_detection`.
- `ml/mlops/model_card_template.md` exists as a governance artefact but **model cards are not yet auto-generated** as part of the promotion pipeline.
- MLflow at `https://mlflow.instacommerce.internal` is the experiment tracking store; all training runs log metric + job_id for lineage.

---

## Diagram 7 — Unified Quality Gate Map

> **Scope:** all quality gates and governance checkpoints across the full data + ML + AI stack, in execution order.

```mermaid
flowchart LR
    subgraph ci["CI (every PR — .github/workflows/ci.yml)"]
        ci1["◈ GOVERN: contracts:build\nProto compilation + JSON Schema validation\nBreaking-change diff vs main\nConsumer deserialization test"]
        ci2["⛨ GATE: Gitleaks\nSecret scanning"]
        ci3["⛨ GATE: Trivy\nContainer + dependency CVEs"]
    end

    subgraph ingest["Data Ingestion"]
        g_dlq["⛨ GATE: Beam Dead-Letter\nMalformed events → GCS DLQ\nDoes not block pipeline"]
        g_schema["◈ GOVERN: Event Envelope\nevent_id · aggregate_id · schema_version\nsource_service · correlation_id required"]
    end

    subgraph warehouse["Warehouse Build"]
        g_stg["⛨ GATE: dbt test (staging)\nschema + not_null + unique tests\nBlocks marts DAG via ExternalTaskSensor"]
        g_ge["⛨ GATE: Great Expectations\norders · payments · users · inventory suites\nFreshness + volume + enum + range\nSlack alert + DAG pause on critical fail"]
        g_mart["⛨ GATE: dbt test (marts)\nrelationships + accepted_values\nBlocks BI + activation publish"]
    end

    subgraph features["Feature Pipeline"]
        g_fresh["⛨ GATE: Feature Freshness\n≤ 60 min threshold\nFails DAG + Slack if stale\nPer-feature row-count validation"]
        g_fv["◈ GOVERN: Feature View YAML\nowner · serving_config · max_latency_ms\nfallback strategy required\nml/feature_store/feature_views/*.yaml"]
    end

    subgraph ml_train["ML Training"]
        g_promo["⛨ GATE: Promotion Gate\nPer-model metric threshold\ndirection: minimize | maximize\nBranchPythonOperator → promote | skip"]
        g_mc["◈ GOVERN: Model Card\nml/mlops/model_card_template.md\n(Manual today · auto-gen gap)"]
    end

    subgraph serving["ML Serving"]
        g_psi["⛨ GATE: PSI Drift\nPSI > 0.10 WARNING\nPSI > 0.20 ALERT + retrain signal\nml/serving/monitoring.py"]
        g_shadow["◈ GOVERN: Shadow Mode\nShadowRunner — agreement_rate logged\n(Manual promotion review today)"]
        g_feat_age["⛨ GATE: Feature Staleness\ncheck_freshness() per request\nmax_age_s: 3600"]
    end

    subgraph ai_agent["AI Agent"]
        g_pii["◈ GOVERN: PII Vault\nredact before LLM · restore after\nguardrails/pii.py"]
        g_inj["⛨ GATE: Injection Guard\nguardrails/injection.py"]
        g_budget["⛨ GATE: Budget Tracker\nLLM token cap per session\ngraph/budgets.py → BudgetExceededError"]
        g_out["⛨ GATE: Output Validator\nguardrails/output_validator.py"]
    end

    subgraph activation["Activation / Reverse-ETL (Roadmap P1)"]
        g_act1["⛨ GATE: G4 Row-Count Reconciliation\n(data-platform-jobs · not yet implemented)"]
        g_act2["⛨ GATE: G5 Business Guardrail\ncampaign pressure cap · margin floor\n(prometheus-rules.yaml · partial)"]
    end

    ci --> ingest --> warehouse --> features --> ml_train --> serving --> ai_agent
    warehouse --> activation
```

---

## Assessment

### ✅ Done / Solid

| Area | Evidence |
|---|---|
| Event contract governance | `contracts/README.md`: envelope standard, JSON Schema draft-07, proto compilation, breaking-change detection in CI, 90-day deprecation window |
| Streaming pipelines | 5 Beam/Dataflow pipelines with fixed/sliding windows, dead-letter sinks, correct schema parsing |
| dbt layering | `stg_*` → `int_*` → `mart_*` strictly separated; `ExternalTaskSensor` dependency chain enforced in Airflow |
| Great Expectations gates | 4 suites (orders, payments, users, inventory); DAG fails and Slack-alerts on critical expectation failure |
| Feature store structure | 5 entities, 5 feature groups, 5 feature views with owner + serving config + fallback defined in YAML |
| Promotion gates | Per-model metric thresholds in `ml_training` DAG; `BranchPythonOperator` blocks bad models from reaching production |
| Model monitoring | `ModelMonitor`: PSI drift (warning/alert thresholds), prediction count, latency histogram, error rate — all Prometheus-backed |
| Shadow mode | `ShadowRunner` — async, timeout-bounded, never serves shadow result, logs agreement rate |
| AI agent guardrails | PII vault, injection guard, rate limiter, output validator, escalation, budget tracker — all wired into LangGraph nodes |
| Fraud scoring thresholds | `fraud_detection_view.yaml`: auto_approve/soft_review/block thresholds; `rules_based` fallback |
| MLflow experiment tracking | `ml_training` DAG logs metrics + job_id + params for lineage |
| Reverse-ETL roadmap | `docs/architecture/WAVE2-DATA-ML-ROADMAP-B.md` has detailed P0/P1/P2 LLD including gate IDs G1–G5 |

### 🔶 Needs More Work

| Gap | Risk | Recommendation |
|---|---|---|
| Reverse-ETL DAG and jobs not implemented | Analytics insights cannot currently reach operational services (notification, pricing, loyalty) systematically | Implement `reverse_etl_activation.py` DAG + `data-platform-jobs/jobs/reverse_etl.py` per roadmap P1 |
| `activation/*.v1.json` contract schemas missing | `CustomerSegmentUpserted`, `ChurnRiskScored`, `RevenueOfferRecommended` exist only in roadmap doc — not in `contracts/` | Create schemas, add to contracts CI validation |
| Shadow mode not wired to auto-promotion | `ShadowRunner.agreement_rate` is logged but not read by any DAG or alert | Add Prometheus `shadow_agreement_rate` gauge; alert when < threshold, trigger human review |
| Fraud model canary rollout absent | `fraud_detection` promotion deploys at `traffic_percentage=100` — no blue/green or canary | Add staged rollout: 10% → 50% → 100% with auto-rollback on F1 regression |
| Model card auto-generation not implemented | `ml/mlops/model_card_template.md` exists as template but is never populated by the training DAG | Add a `generate_model_card` task after `promote_model` in `ml_training` DAG |
| `monitoring_alerts` DAG content unknown | `monitoring_alerts.py` exists but was not fully available — coverage of drift → retrain trigger unclear | Verify drift alert → `ml_training` DAG trigger is wired (not just manual investigation) |
| Great Expectations activation suite missing | `activation_suite.yaml` referenced in roadmap but absent in `data-platform/quality/expectations/` | Required before reverse-ETL publish path is trustworthy |
| No streaming quality gate | Dead-letter sink exists but there is no alert on DLQ volume growth | Add `monitoring_alerts` check on DLQ row count + alert threshold |

### 🚫 Blockers / Questions

| Item | Question |
|---|---|
| **Reverse-ETL ownership** | Who owns `data-platform-jobs/jobs/reverse_etl.py`? Data Engineering or ML Engineering? The roadmap lists both. Needs explicit CODEOWNERS assignment before implementation starts. |
| **Identity events → feature pipeline** | `identity.events` (`UserErased`) must propagate GDPR erasure to the feature store and BigQuery. There is no current diagram or implementation of this deletion path. Is this handled? |
| **`mart_sponsored_opportunities` consumer** | This mart produces `monetization_readiness_score` but there is no consuming service or activation job. Is this feeding a DSP or an internal ad-serving product? |
| **Vertex AI Feature Store billing region** | `FEATURE_STORE_REGION = "us-central1"` in `ml_feature_refresh.py`. If users/orders are EU-resident, this may conflict with GDPR data-residency requirements. |
| **MLflow availability** | `MLFLOW_TRACKING_URI = "https://mlflow.instacommerce.internal"` — is this a managed service or self-hosted? If self-hosted, it is a single point of failure for the training DAG. |
