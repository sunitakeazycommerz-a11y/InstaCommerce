#!/usr/bin/env python3
"""
Issue definitions for Milestone 23 (Wave 6) and Milestone 24 (Wave 7).
Contains 52 staff-level issue specifications.
"""

WAVE_6_ISSUES = [
    {
        "milestone": 23,
        "title": "drift(data-platform): Beam streaming pipelines use processing time instead of event time watermarking",
        "labels": ["kind/bug", "priority/p1-high", "area/data-platform", "python"],
        "body": """### Summary
Apache Beam pipelines in `data-platform/streaming/pipelines/` aggregate metrics using processing time instead of event timestamp watermarking, skewing metrics when events arrive out-of-order.

### Evidence & Code Reference
- `data-platform/streaming/pipelines/order_events_pipeline.py`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (Data-P1-11).

### Blast Radius & Business Impact
Inaccurate hourly sales reporting and distorted ML feature store training metrics.

### Remediation Plan
1. Extract `event_timestamp` from Kafka message envelope.
2. Apply `beam.window.TimestampedValue` with `FixedWindows` based on event time.
3. Configure bounded out-of-orderness watermark with 1-hour allowed lateness."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): late arriving event drop without dead-letter side output in Order events Beam pipeline",
        "labels": ["kind/bug", "priority/p1-high", "area/data-platform", "python"],
        "body": """### Summary
Events arriving after the watermark allowed lateness window are silently dropped by Apache Beam without writing to a dead-letter BigQuery table or emitting a warning metric.

### Evidence & Code Reference
- `data-platform/streaming/pipelines/order_events_pipeline.py`.

### Blast Radius & Business Impact
Permanent data loss in analytical warehouses: revenue calculations undercount late-arriving orders.

### Remediation Plan
1. Configure `beam.pvalue.TaggedOutput` for late-arriving elements.
2. Write dropped elements to `analytics_dlq.late_order_events` BigQuery table.
3. Export Prometheus metric for late event count tagged by event type."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): dbt model test suite with Great Expectations assertions on mart_daily_sales",
        "labels": ["kind/feature", "priority/p1-high", "area/data-platform"],
        "body": """### Summary
Daily financial sales mart (`mart_daily_sales`) lacks automated data quality assertions, allowing corrupted or negative revenue values to enter executive Looker dashboards.

### Evidence & Code Reference
- `data-platform/dbt/models/marts/mart_daily_sales.sql`.
- `data-platform/quality/`.

### Blast Radius & Business Impact
Distorted corporate financial reporting and erroneous business decision-making.

### Remediation Plan
1. Define Great Expectations suite: non-null order IDs, positive total revenue, order count >= 0.
2. Assert daily sales volume is within 3 standard deviations of 30-day moving average.
3. Block downstream dashboard refresh if quality assertions fail."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): reverse-etl-orchestrator missing idempotency key during BigQuery to Redis sync",
        "labels": ["kind/bug", "priority/p1-high", "area/data-platform", "go"],
        "body": """### Summary
`reverse-etl-orchestrator` writes customer lifetime value (CLV) and category affinity scores to Redis using standard `HSET` without versioning or transaction isolation, causing partial updates on job restart.

### Evidence & Code Reference
- `services/reverse-etl-orchestrator/internal/subscription/worker.go`.
- Glossary Section 1 (Reverse ETL).

### Blast Radius & Business Impact
Inconsistent customer feature states served to search ranking models.

### Remediation Plan
1. Write synced features to a temporary Redis key (`user:features:temp:{jobId}`).
2. Atomically rename temporary key to production key (`RENAME user:features:temp:{jobId} user:features:{userId}`) upon completion.
3. Record sync timestamp and row count in metadata audit table."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): cart abandonment streaming pipeline session window trigger drops active sessions",
        "labels": ["kind/bug", "priority/p2-medium", "area/data-platform", "python"],
        "body": """### Summary
`cart_events_pipeline.py` uses a 15-minute fixed window trigger instead of a dynamic session window gap, prematurely classifying active shoppers as abandoned if they browse for 16 minutes.

### Evidence & Code Reference
- `data-platform/streaming/pipelines/cart_events_pipeline.py`.

### Blast Radius & Business Impact
Spamming active users with premature 'You left items in your cart!' push notifications while they are still actively browsing.

### Remediation Plan
1. Replace fixed window with Apache Beam `Sessions(gap_size=900)`.
2. Fire trigger only when 15 minutes of complete inactivity elapse since the last cart event.
3. Emit `CartAbandoned` event only after verifying no subsequent `OrderPlaced` event occurred."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): automated schema evolution handler in Debezium CDC for altered Postgres columns",
        "labels": ["kind/feature", "priority/p1-high", "area/data-platform", "go"],
        "body": """### Summary
When a microservice applies a Flyway migration altering column types or adding non-null columns, Debezium CDC and `cdc-consumer-service` fail deserialization on schema change events.

### Evidence & Code Reference
- `services/cdc-consumer-service/main.go`.

### Blast Radius & Business Impact
CDC pipeline halts, freezing downstream BigQuery real-time database replication.

### Remediation Plan
1. Configure Debezium Avro / JSON schema converter with schema registry compatibility.
2. In `cdc-consumer-service`, gracefully ignore unknown fields using tolerant deserialization.
3. Automatically propagate schema changes to BigQuery tables via `bigquery.SchemaUpdateOption`."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): payment events pipeline BigQuery insert errors silently swallowed on schema mismatch",
        "labels": ["kind/bug", "priority/p1-high", "area/data-platform", "python"],
        "body": """### Summary
`payment_events_pipeline.py` catches `WriteToBigQuery` errors in an empty exception block, silently dropping failed financial payment records without logging row details.

### Evidence & Code Reference
- `data-platform/streaming/pipelines/payment_events_pipeline.py`.

### Blast Radius & Business Impact
Missing payment ledger rows in BigQuery audit tables, causing discrepancies in end-of-month financial reconciliation.

### Remediation Plan
1. Configure `insert_retry_policy: NEVER` with explicit dead-letter side output PCollection.
2. Write failed insertion rows with error message to `analytics_errors.payment_insert_failures`.
3. Raise critical PagerDuty alert if insert failure rate exceeds 0.01%."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): dbt incremental model materialization strategy for mart_order_fulfillment",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform"],
        "body": """### Summary
`mart_order_fulfillment` executes as a full table scan table replacement (`materialized='table'`), reprocessing 50 million historical rows daily and consuming excessive BigQuery query slots.

### Evidence & Code Reference
- `data-platform/dbt/models/marts/mart_order_fulfillment.sql`.

### Blast Radius & Business Impact
High Google Cloud BigQuery slot consumption and bloated monthly cloud analytics costs.

### Remediation Plan
1. Migrate model to `materialized='incremental'` with `unique_key='order_id'`.
2. Add filter: `WHERE updated_at > (SELECT MAX(updated_at) FROM {{ this }})`.
3. Verify 80% reduction in daily slot time and query run duration."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): inventory velocity pipeline window accumulation mode causes duplicate counts",
        "labels": ["kind/bug", "priority/p1-high", "area/data-platform", "python"],
        "body": """### Summary
`inventory_events_pipeline.py` uses `AccumulationMode.ACCUMULATING` with multiple early pane triggers, writing cumulative partial counts to BigQuery rather than distinct delta counts.

### Evidence & Code Reference
- `data-platform/streaming/pipelines/inventory_events_pipeline.py`.

### Blast Radius & Business Impact
Inventory sales velocity metrics inflated by 300-500%, triggering false stockout warnings.

### Remediation Plan
1. Switch trigger accumulation mode to `AccumulationMode.DISCARDING`.
2. Write only incremental delta units sold per pane trigger.
3. Update downstream BigQuery queries to sum deltas over target time windows."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): Airflow DAG sensor for upstream Kafka topic partition health before daily dbt runs",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform", "python"],
        "body": """### Summary
Airflow DAG `dbt_marts.py` triggers at 02:00 AM regardless of whether upstream Kafka streaming ingestion pipelines have completed flushing midnight batch events.

### Evidence & Code Reference
- `data-platform/airflow/dags/dbt_marts.py`.

### Blast Radius & Business Impact
Incomplete daily analytics reports missing the final 30 minutes of midnight grocery orders.

### Remediation Plan
1. Add `KafkaLagSensor` verifying consumer lag on `orders.events` is < 100 records before running staging models.
2. Set sensor timeout to 30 minutes with 60-second poke interval.
3. Fail DAG gracefully if ingestion pipeline is stuck."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): rider location streaming pipeline OOM on high-cardinality zone sliding windows",
        "labels": ["kind/bug", "priority/p1-high", "area/data-platform", "python"],
        "body": """### Summary
`rider_location_pipeline.py` maintains stateful sliding windows over millions of raw GPS pings grouped by latitude/longitude bins, overflowing Dataflow worker JVM memory.

### Evidence & Code Reference
- `data-platform/streaming/pipelines/rider_location_pipeline.py`.

### Blast Radius & Business Impact
Dataflow pipeline crash loops during peak delivery hours, causing data backlogs.

### Remediation Plan
1. Pre-aggregate GPS pings by H3 spatial index at 10-second fixed intervals before windowing.
2. Emit compact summary structs (`rider_count`, `avg_speed`, `active_count`) instead of raw ping arrays.
3. Configure Dataflow autoscaling max workers to 20."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): data lineage graph visualization using OpenLineage and Marquez",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform"],
        "body": """### Summary
Tracing the path of data from transactional PostgreSQL outbox tables through Kafka, Beam pipelines, dbt staging, and BigQuery marts requires manual documentation lookup.

### Evidence & Code Reference
- `data-platform/`.

### Blast Radius & Business Impact
Slow root cause analysis during data quality incidents; inability to determine blast radius of schema migrations.

### Remediation Plan
1. Integrate OpenLineage Airflow provider emitting job and dataset lineage events to Marquez.
2. Instrument dbt-lineage plugin capturing column-level transformation DAGs.
3. Publish interactive data lineage portal for data engineering team."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): BigQuery partition expiration policy missing on raw streaming audit tables",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/data-platform"],
        "body": """### Summary
Raw event streaming destination tables in BigQuery (`analytics_raw.*`) lack partition expiration policies, retaining raw multi-terabyte JSON event payloads indefinitely.

### Evidence & Code Reference
- `infra/terraform/modules/bigquery/tables.tf`.

### Blast Radius & Business Impact
Escalating Google Cloud Storage costs for unindexed, raw historical JSON logs.

### Remediation Plan
1. Configure partition expiration of 90 days on all `analytics_raw` streaming tables.
2. Archive older partitions to Cloud Storage Coldline / Archive storage class.
3. Enforce partition expiration rules in Terraform BigQuery module."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): reverse ETL sync for customer fraud risk scores from BigQuery to Redis cache",
        "labels": ["kind/feature", "priority/p1-high", "area/data-platform", "go"],
        "body": """### Summary
Fraud risk scores computed by BigQuery daily ML models are not synced to the operational database, forcing `fraud-detection-service` to rely on stale in-memory heuristics during checkout.

### Evidence & Code Reference
- `services/reverse-etl-orchestrator/`.
- `services/fraud-detection-service/`.

### Blast Radius & Business Impact
Payment fraud and promo voucher abuse from accounts flagged in analytics but unblocked in operational paths.

### Remediation Plan
1. Author reverse ETL subscription in `reverse-etl-orchestrator` querying BigQuery model table `ml_models.customer_fraud_scores`.
2. Sync scores daily to Redis key `fraud:score:{userId}` with 36-hour TTL.
3. `fraud-detection-service` reads score during checkout and blocks transactions with risk score > 0.85."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): Airflow worker task timeout on heavy dbt compilation across 200+ models",
        "labels": ["kind/bug", "priority/p2-medium", "area/data-platform", "python"],
        "body": """### Summary
`dbt_marts.py` compiles the entire dbt project in a single `BashOperator` task. As model count grew, compilation time exceeded the default 600-second Airflow execution timeout.

### Evidence & Code Reference
- `data-platform/airflow/dags/dbt_marts.py`.

### Blast Radius & Business Impact
Airflow task failure terminating nightly analytics runs, causing stale morning reports.

### Remediation Plan
1. Split dbt execution into layered task groups: Staging -> Intermediate -> Marts.
2. Pre-compile dbt manifest (`dbt compile`) during CI image build and mount `manifest.json`.
3. Increase task timeout to 1800 seconds."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): data quality anomaly detection alerting on daily revenue discrepancies > 0.1%",
        "labels": ["kind/feature", "priority/p1-high", "area/data-platform", "python"],
        "body": """### Summary
Discrepancies between payment gateway settled amounts and order service revenue totals are currently reconciled manually in spreadsheets at the end of the month.

### Evidence & Code Reference
- `data-platform/airflow/dags/data_quality.py`.

### Blast Radius & Business Impact
Delayed detection of accounting bugs or transaction leakage for weeks after release.

### Remediation Plan
1. Add automated daily SQL reconciliation comparing sum(orders.total) vs sum(payments.captured_amount).
2. If delta exceeds 0.1% or $100, trigger automated Slack alert and generate audit ticket.
3. Block automated accounting journal entry creation pending review."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): CDC consumer group offset reset to latest on restart causing data gaps",
        "labels": ["kind/bug", "priority/p0-critical", "area/data-platform", "go"],
        "body": """### Summary
`cdc-consumer-service` Go Kafka consumer configures `auto.offset.reset = \"latest\"`. If the service is stopped for a deployment while database mutations occur, all intermediate CDC events are permanently skipped.

### Evidence & Code Reference
- `services/cdc-consumer-service/main.go` Kafka configuration.

### Blast Radius & Business Impact
Data loss in BigQuery operational replica; database tables permanently diverge from transactional PostgreSQL.

### Remediation Plan
1. Set `auto.offset.reset = \"earliest\"`.
2. Commit consumer offsets synchronously only after successfully persisting records to downstream sink.
3. Validate zero dropped events across service restart test."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): streaming join between Order and Payment Kafka topics in Apache Beam",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform", "python"],
        "body": """### Summary
Analytics requires real-time attribution of payment gateway provider (e.g. UPI vs Credit Card) to dark store order fulfillment speed, requiring a real-time streaming join.

### Evidence & Code Reference
- `data-platform/streaming/pipelines/`.

### Blast Radius & Business Impact
Inability to analyze payment-method-specific checkout conversion and drop-off in real time.

### Remediation Plan
1. Implement Apache Beam `CoGroupByKey` joining `orders.events` and `payments.events` on `order_id`.
2. Window join with 10-minute allowed skew.
3. Write unified order-payment records to BigQuery `analytics.realtime_order_payments`."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): BigQuery streaming buffer quota exhaustion during flash sale event bursts",
        "labels": ["kind/bug", "priority/p1-high", "area/data-platform", "python"],
        "body": """### Summary
During 10,000 req/sec flash sale campaigns, direct streaming inserts (`tabledata.insertAll`) exceed BigQuery regional project rate limits, resulting in HTTP 403 quota errors.

### Evidence & Code Reference
- `data-platform/streaming/pipelines/order_events_pipeline.py`.

### Blast Radius & Business Impact
Data ingestion failure and pipeline backpressure backing up Kafka broker disk storage.

### Remediation Plan
1. Migrate from legacy streaming API to BigQuery Storage Write API (`BigQueryIO.write().withMethod(STORAGE_WRITE_API)`).
2. Configure micro-batch commit intervals (2 seconds).
3. Utilize exactly-once stream semantics with automated retries."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): dbt documentation site automated generation and deployment to internal GCS bucket",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform"],
        "body": """### Summary
Data analysts and engineers lack an updated interactive data dictionary detailing column descriptions, upstream dependencies, and model SQL definitions.

### Evidence & Code Reference
- `data-platform/dbt/`.

### Blast Radius & Business Impact
Data engineering friction: analysts frequently write redundant SQL models or query outdated legacy tables.

### Remediation Plan
1. Add CI job running `dbt docs generate` on merge to `main`.
2. Deploy generated static site (`catalog.json`, `manifest.json`, `index.html`) to private GCS bucket.
3. Protect site with Google Identity-Aware Proxy (IAP) for internal staff access."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): Airflow DAG failure notifications missing Slack webhook payload formatting",
        "labels": ["kind/bug", "priority/p2-medium", "area/data-platform", "python"],
        "body": """### Summary
`data-platform/airflow/plugins/slack_alerts.py` formats error payloads using legacy Slack attachments API, resulting in HTTP 400 rejection from modern Slack incoming webhooks.

### Evidence & Code Reference
- `data-platform/airflow/plugins/slack_alerts.py`.

### Blast Radius & Business Impact
Silent Airflow DAG failures without alerting on-call data engineers.

### Remediation Plan
1. Migrate payload to modern Slack Block Kit JSON format.
2. Include direct links to Airflow task logs, execution date, and exception traceback.
3. Add unit test verifying Block Kit JSON serialization."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): automated backfill orchestration DAG for historical Kafka event replay",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform", "python"],
        "body": """### Summary
When an analytical dbt model logic bug is fixed, re-running historical data requires complex manual command-line scripts and ad-hoc SQL updates.

### Evidence & Code Reference
- `data-platform/airflow/dags/`.

### Blast Radius & Business Impact
High operational overhead and risk of introducing data corruption during manual historical backfills.

### Remediation Plan
1. Create parameterized Airflow backfill DAG accepting `start_date`, `end_date`, and `model_name`.
2. Incrementally process historical date partitions using dbt `--select <model>+ --vars '{\"is_backfill\": true}'`.
3. Validate row count parity post-backfill."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): data lake Parquet compression codec migration from gzip to zstd for 40% speedup",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/data-platform"],
        "body": """### Summary
Historical analytical tables exported to Cloud Storage use `gzip` compression, resulting in slow decompression speeds during large-scale Spark and BigLake queries.

### Evidence & Code Reference
- `data-platform/dbt/dbt_project.yml`.

### Blast Radius & Business Impact
Long query runtimes and higher cloud compute costs for machine learning model training jobs.

### Remediation Plan
1. Update Parquet export configuration to use `zstd` (level 3) compression codec.
2. Benchmark query performance: target 40% faster decompression with equivalent compression ratio.
3. Migrate existing historical partitions using automated Spark batch job."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): real-time dashboard query optimization using BigQuery BI Engine reservation",
        "labels": ["kind/feature", "priority/p2-medium", "area/data-platform"],
        "body": """### Summary
Executive real-time sales dashboards execute direct BigQuery SQL queries on every user refresh, incurring 5-8 second query latency and high on-demand analysis costs.

### Evidence & Code Reference
- `infra/terraform/modules/bigquery/`.

### Blast Radius & Business Impact
Slow dashboard responsiveness for operations and store leadership teams during peak hours.

### Remediation Plan
1. Provision 10GB BigQuery BI Engine in-memory acceleration reservation in Terraform.
2. Target acceleration reservation at `marts.mart_daily_sales` and `marts.mart_realtime_kpis`.
3. Achieve sub-second dashboard panel render times."""
    },
    {
        "milestone": 23,
        "title": "fix(data-platform): reverse ETL worker thread pool starvation during parallel dark store inventory sync",
        "labels": ["kind/bug", "priority/p1-high", "area/data-platform", "go"],
        "body": """### Summary
`reverse-etl-orchestrator` syncs 50 dark stores sequentially on a single thread. When store count expands, daily sync takes 4 hours and blocks downstream operational caching.

### Evidence & Code Reference
- `services/reverse-etl-orchestrator/internal/subscription/worker.go`.

### Blast Radius & Business Impact
Stale inventory intelligence in operational Redis caches at the start of daily business hours.

### Remediation Plan
1. Implement worker goroutine pool with configurable concurrency (e.g. 10 parallel stores).
2. Add rate limiting to avoid saturating Redis write throughput.
3. Export Prometheus metrics for sync duration per store."""
    },
    {
        "milestone": 23,
        "title": "feat(data-platform): automated data catalog tagging for GDPR/CCPA PII columns in BigQuery",
        "labels": ["kind/security", "priority/p2-medium", "area/data-platform"],
        "body": """### Summary
BigQuery tables containing customer personal data (phone numbers, GPS coordinates, delivery addresses) lack Google Cloud Data Catalog policy tags, allowing analysts to query raw PII.

### Evidence & Code Reference
- `infra/terraform/modules/bigquery/`.

### Blast Radius & Business Impact
Data privacy compliance violation (GDPR / Indian DPDP Act) and risk of customer data leakage.

### Remediation Plan
1. Define Cloud Data Catalog taxonomy with policy tags: `PII.Confidential`, `PII.Sensitive`.
2. Apply policy tags to column schemas in dbt `schema.yml` files.
3. Enforce BigQuery dynamic column-level data masking (mask phone to `+91-XXXXX-1234`) for non-privileged roles."""
    }
]

WAVE_7_ISSUES = [
    {
        "milestone": 24,
        "title": "test(audit-trail): zero test coverage in audit-trail-service (compileTestJava NO-SOURCE)",
        "labels": ["kind/tech-debt", "priority/p0-critical", "area/observability", "java"],
        "body": """### Summary
`audit-trail-service` has zero test files. Gradle executes `compileTestJava NO-SOURCE`, shipping untracked and unverified audit log ingestion logic to production.

### Evidence & Code Reference
- `services/audit-trail-service/src/test/` directory does not exist.
- Gradle build output: `:services:audit-trail-service:compileTestJava NO-SOURCE`.

### Blast Radius & Business Impact
Compliance violation: audit logging regressions or silent serialization failures remain undetected before release.

### Remediation Plan
1. Author comprehensive JUnit 5 unit test suite for `AuditTrailController` and `AuditTrailService`.
2. Add Testcontainers PostgreSQL test verifying immutable audit event persistence.
3. Enforce minimum 80% line coverage in service `build.gradle.kts`."""
    },
    {
        "milestone": 24,
        "title": "test(fraud-detection): zero test coverage in fraud-detection-service (compileTestJava NO-SOURCE)",
        "labels": ["kind/tech-debt", "priority/p0-critical", "area/checkout-saga", "java"],
        "body": """### Summary
`fraud-detection-service` has zero test files. Core velocity checking, device fingerprinting, and blacklisted card validation logic ship without automated regression tests.

### Evidence & Code Reference
- `services/fraud-detection-service/src/test/` directory does not exist.
- Gradle build output: `:services:fraud-detection-service:compileTestJava NO-SOURCE`.

### Blast Radius & Business Impact
Security regression risk: code modifications could disable fraud rules, permitting mass promo voucher abuse.

### Remediation Plan
1. Author JUnit 5 tests covering velocity rule evaluation and IP blacklist checks.
2. Mock external identity service calls using Mockito.
3. Add integration test verifying `FraudDetected.v1.json` event emission on rule violation."""
    },
    {
        "milestone": 24,
        "title": "test(go): dispatch-optimizer-service missing unit tests for optimizer and handler packages",
        "labels": ["kind/tech-debt", "priority/p1-high", "area/checkout-saga", "go"],
        "body": """### Summary
`go test ./...` in `services/dispatch-optimizer-service` reports `[no test files]` for both `handler` and `optimizer` packages, leaving core bipartite matching algorithms untested.

### Evidence & Code Reference
- `services/dispatch-optimizer-service/handler/` [no test files].
- `services/dispatch-optimizer-service/optimizer/` [no test files].

### Blast Radius & Business Impact
Algorithm bugs or edge cases (empty rider pools, zero distance) causing runtime panics and dispatch halts.

### Remediation Plan
1. Author table-driven unit tests for `matcher.go` verifying optimal bipartite assignment across edge cases.
2. Test handler HTTP request decoding and validation errors.
3. Run tests with `go test -race` in CI."""
    },
    {
        "milestone": 24,
        "title": "test(go): reconciliation-engine missing unit tests for reconciliation and api packages",
        "labels": ["kind/tech-debt", "priority/p1-high", "area/payment-ledger", "go"],
        "body": """### Summary
`go test ./...` in `services/reconciliation-engine` reports `[no test files]` for `pkg/reconciliation` and `api` packages. Double-entry balancing logic has zero unit test coverage.

### Evidence & Code Reference
- `services/reconciliation-engine/pkg/reconciliation/` [no test files].
- `services/reconciliation-engine/api/` [no test files].

### Blast Radius & Business Impact
Risk of undetected financial accounting bugs in ledger discrepancy detection routines.

### Remediation Plan
1. Author unit tests covering balanced ledgers (`debits == credits`) and deliberate discrepancy scenarios.
2. Test API endpoint response structures and error codes.
3. Assert 85%+ code coverage in CI pipeline."""
    },
    {
        "milestone": 24,
        "title": "test(go): stream-processor-service missing unit tests for dedup and processor packages",
        "labels": ["kind/tech-debt", "priority/p1-high", "area/data-platform", "go"],
        "body": """### Summary
`services/stream-processor-service` lacks unit tests for message deduplication and stream transformation logic (`dedup` and `processor` packages report `[no test files]`).

### Evidence & Code Reference
- `services/stream-processor-service/dedup/` [no test files].
- `services/stream-processor-service/processor/` [no test files].

### Blast Radius & Business Impact
Deduplication failure leading to duplicate event processing and skewed metrics.

### Remediation Plan
1. Add unit tests verifying Bloom filter and Redis sliding-window deduplication logic.
2. Test message parsing and serialization round-trips.
3. Verify race safety under concurrent goroutine execution."""
    },
    {
        "milestone": 24,
        "title": "test(go): reverse-etl-orchestrator missing unit tests for subscription and api packages",
        "labels": ["kind/tech-debt", "priority/p1-high", "area/data-platform", "go"],
        "body": """### Summary
`services/reverse-etl-orchestrator` reports `[no test files]` across `internal/subscription`, `internal/api`, and `internal/config`, shipping reverse ETL synchronization without test coverage.

### Evidence & Code Reference
- `services/reverse-etl-orchestrator/internal/subscription/` [no test files].
- `services/reverse-etl-orchestrator/internal/api/` [no test files].

### Blast Radius & Business Impact
Sync worker crashes or batching errors causing stale operational Redis caches.

### Remediation Plan
1. Author unit tests for subscription scheduling and worker pool management.
2. Mock BigQuery and Redis clients to test error handling and retry logic.
3. Enforce test coverage thresholds in CI."""
    },
    {
        "milestone": 24,
        "title": "test(integration): Testcontainers suite for transactional PostgreSQL services with Flyway migrations",
        "labels": ["kind/feature", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
Several transactional services rely on in-memory H2 databases for unit tests, masking PostgreSQL-specific syntax errors, index constraints, and Flyway migration failures.

### Evidence & Code Reference
- `services/*/src/test/resources/application-test.yml` (H2 database configuration).
- AGENTS.md Section 4 (Microservice Drift Prevention Checklist).

### Blast Radius & Business Impact
SQL syntax errors that pass CI tests but immediately crash on production PostgreSQL 16 instances.

### Remediation Plan
1. Replace H2 with Testcontainers `PostgreSQLContainer(\"postgres:16-alpine\")`.
2. Execute full Flyway migration chain (`V1...sql`) against real PostgreSQL during test execution.
3. Assert index constraints and JSONB column queries function correctly."""
    },
    {
        "milestone": 24,
        "title": "test(integration): embedded Kafka test harness for outbox-relay and cdc-consumer end-to-end flows",
        "labels": ["kind/feature", "priority/p1-high", "area/data-platform", "go", "java"],
        "body": """### Summary
Testing the transactional outbox pattern relies on isolated unit mocks without end-to-end verification that database commits result in correctly structured Kafka records.

### Evidence & Code Reference
- `services/outbox-relay-service/` and `services/cdc-consumer-service/`.

### Blast Radius & Business Impact
Regressions in outbox relay polling or serialization causing silent event delivery failure.

### Remediation Plan
1. Implement integration test using Testcontainers Kafka and PostgreSQL.
2. Insert domain event into `outbox_events` table, run `outbox-relay-service`, and assert message arrives on topic.
3. Validate payload schema against `EventEnvelope.v1.json`."""
    },
    {
        "milestone": 24,
        "title": "test(integration): Temporal TestWorkflowEnvironment suite for checkout and compensation sagas",
        "labels": ["kind/feature", "priority/p1-high", "area/checkout-saga", "java"],
        "body": """### Summary
`CheckoutWorkflowImpl` lacks automated tests exercising compensating activities when individual activities fail (e.g. inventory reserved but payment declined).

### Evidence & Code Reference
- `services/checkout-orchestrator-service/src/test/`.

### Blast Radius & Business Impact
Undetected compensation bugs leaving stock permanently reserved or orders in inconsistent states.

### Remediation Plan
1. Use Temporal `TestWorkflowEnvironment` in JUnit 5 test suite.
2. Mock activities to simulate payment failure, inventory shortage, and network timeouts.
3. Assert that compensating activities (`ReleaseInventoryActivity`) execute in reverse order."""
    },
    {
        "milestone": 24,
        "title": "drift(ci): enforce minimum 70% line coverage gate in CI build for all Tier 0 and Tier 1 services",
        "labels": ["kind/tech-debt", "priority/p1-high", "area/infrastructure"],
        "body": """### Summary
Currently, `.github/workflows/ci.yml` builds code without enforcing minimum test coverage gates, allowing PRs with zero tests to be merged.

### Evidence & Code Reference
- `.github/workflows/ci.yml`.
- Review reference: `docs/reviews/iter3/appendices/issue-register.md` (Testing-P0-14).

### Blast Radius & Business Impact
Progressive erosion of test coverage across the monorepo; regression vulnerabilities.

### Remediation Plan
1. Configure JaCoCo code coverage plugin across all Java services with 70% minimum threshold.
2. Configure Go coverage gate (`go test -coverprofile`) in CI.
3. Fail build if PR introduces net decrease in test coverage for Tier 0 services."""
    },
    {
        "milestone": 24,
        "title": "drift(lint): Spotless code formatter enforcement for all Java services in Gradle build",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure", "java"],
        "body": """### Summary
Java codebase exhibits inconsistent formatting (tabs vs spaces, import ordering, brace placement), causing noisy git diffs and review friction.

### Evidence & Code Reference
- `build.gradle.kts`.

### Blast Radius & Business Impact
Developer friction and messy commit histories obscuring actual code changes.

### Remediation Plan
1. Apply `com.diffplug.spotless` Gradle plugin to root project.
2. Configure Palantir Java Format or Google Java Format with standard import ordering.
3. Add `./gradlew spotlessCheck` to CI pull request validation."""
    },
    {
        "milestone": 24,
        "title": "drift(lint): golangci-lint integration in CI for all 8 Go microservices",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure", "go"],
        "body": """### Summary
Go services run `go vet` only, missing advanced static analysis checks for unhandled error returns (`errcheck`), race hazards, and memory copies.

### Evidence & Code Reference
- `.github/workflows/ci.yml`.

### Blast Radius & Business Impact
Silent bugs and unhandled error states escaping to production Go services.

### Remediation Plan
1. Add `.golangci.yml` configuration at monorepo root enabling `errcheck`, `govet`, `staticcheck`, `unused`, and `gosec`.
2. Add `golangci-lint run` step in CI workflow across all Go microservice directories.
3. Fix all existing lint warnings in `services/*/`."""
    },
    {
        "milestone": 24,
        "title": "drift(lint): ruff and pyright type-check enforcement in CI for Python AI services",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure", "python"],
        "body": """### Summary
Python services (`ai-inference-service`, `ai-orchestrator-service`, `ml/`) do not run automated type checking (`pyright`) in GitHub Actions CI.

### Evidence & Code Reference
- `.github/workflows/ci.yml` Python job.

### Blast Radius & Business Impact
Type errors and missing import bugs merge to `main` without detection.

### Remediation Plan
1. Add `pyright` and `ruff check` steps to CI Python workflow.
2. Enforce zero errors across `services/ai-*`, `ml/`, and `data-platform/`.
3. Cache pip/uv virtual environments for sub-minute CI turnaround."""
    },
    {
        "milestone": 24,
        "title": "drift(deps): automated Dependabot vulnerability alerts and PR grouping for monorepo dependencies",
        "labels": ["kind/security", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
`.github/dependabot.yml` is missing or unconfigured, leaving 30 microservices vulnerable to unpatched CVEs in third-party libraries (Spring Boot, Jackson, gRPC).

### Evidence & Code Reference
- `.github/dependabot.yml`.

### Blast Radius & Business Impact
Accumulation of known security vulnerabilities in container dependencies.

### Remediation Plan
1. Create `.github/dependabot.yml` configured for `gradle`, `gomod`, `pip`, and `docker`.
2. Group minor and patch dependency updates into weekly consolidated PRs.
3. Configure auto-merge for internal patch dependencies passing CI."""
    },
    {
        "milestone": 24,
        "title": "drift(gradle): centralized Gradle dependency catalog (libs.versions.toml) migration",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure", "java"],
        "body": """### Summary
Java microservices declare library versions independently in 20 separate `build.gradle.kts` files, causing version fragmentation (e.g. conflicting Jackson and Netty versions).

### Evidence & Code Reference
- `services/*/build.gradle.kts`.

### Blast Radius & Business Impact
`NoSuchMethodError` and runtime classpath conflicts when services share common contract stubs.

### Remediation Plan
1. Create `gradle/libs.versions.toml` centralizing all library and plugin versions.
2. Migrate all 20 Java microservice build scripts to reference `libs.*` version catalog.
3. Ensure Spring Boot 4 BOM governs all third-party transitive dependencies."""
    },
    {
        "milestone": 24,
        "title": "drift(go): harmonize Go toolchain versions across all go.mod files to Go 1.25",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure", "go"],
        "body": """### Summary
`go.mod` files across the 8 Go services specify varying Go versions (some specify `go 1.24`, others `go 1.25`), causing compiler flag warnings and linker version mismatches during Docker builds.

### Evidence & Code Reference
- `services/*/go.mod`.
- GLOSSARY.md Microservice Registry.

### Blast Radius & Business Impact
Build cache invalidation and inconsistent compiler optimization behaviors across services.

### Remediation Plan
1. Harmonize all 8 `go.mod` files to `go 1.25`.
2. Ensure consistent toolchain directive: `toolchain go1.25.1`.
3. Verify `services/go-shared` compiles cleanly across all dependent modules."""
    },
    {
        "milestone": 24,
        "title": "drift(docs): automated synchronization between service README ports and GLOSSARY.md registry",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
Service README port numbers occasionally drift from the authoritative `GLOSSARY.md` Section 3 table and `application.yml` configurations.

### Evidence & Code Reference
- `GLOSSARY.md` Section 3.
- `services/*/README.md`.
- Review reference: `docs/reviews/iter3/platform/repo-truth-ownership.md`.

### Blast Radius & Business Impact
Developer confusion when starting local services or configuring reverse proxy routing.

### Remediation Plan
1. Extend `./scripts/check-monorepo-consistency.sh` to extract server ports from `application.yml` and assert exact match with `GLOSSARY.md`.
2. Add validation check to CI workflow.
3. Automatically fail PRs introducing unregistered or conflicting ports."""
    },
    {
        "milestone": 24,
        "title": "drift(api): OpenAPI 3.1 contract generation and drift detection against controller endpoints",
        "labels": ["kind/feature", "priority/p2-medium", "area/edge-api", "java"],
        "body": """### Summary
REST API documentation is maintained manually without automated schema generation, causing frontend mobile developers to build against outdated request/response models.

### Evidence & Code Reference
- `services/*/src/main/java/com/instacommerce/*/controller/`.

### Blast Radius & Business Impact
Client-server integration bugs and unexpected validation errors in production mobile apps.

### Remediation Plan
1. Integrate `springdoc-openapi` into all REST-facing Java microservices.
2. Generate `openapi.json` specs automatically during `./gradlew build`.
3. Publish interactive Swagger UI endpoints at `/swagger-ui.html` in dev and staging environments."""
    },
    {
        "milestone": 24,
        "title": "drift(security): automated container image vulnerability scanning in CI using Trivy",
        "labels": ["kind/security", "priority/p1-high", "area/infrastructure"],
        "body": """### Summary
Container images built in CI are pushed to image registries without vulnerability scanning for OS package CVEs (OpenSSL, Alpine libcrypto).

### Evidence & Code Reference
- `.github/workflows/ci.yml`.

### Blast Radius & Business Impact
Deploying vulnerable base images containing critical remote code execution (RCE) flaws.

### Remediation Plan
1. Add Aquasec Trivy container scanning step to `.github/workflows/ci.yml`.
2. Fail CI build on any detected `CRITICAL` vulnerability with an available patch.
3. Generate SARIF security reports uploaded to GitHub Security tab."""
    },
    {
        "milestone": 24,
        "title": "drift(helm): Helm chart linting and dry-run template validation in CI matrix",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
PRs modifying Helm templates in `deploy/helm/` are not validated against Kubernetes API schemas, allowing invalid YAML or template syntax errors to merge.

### Evidence & Code Reference
- `deploy/helm/`.

### Blast Radius & Business Impact
ArgoCD sync failure blocking all deployment rollouts upon merge to `main`.

### Remediation Plan
1. Add `helm lint deploy/helm/charts/*` step in CI workflow.
2. Run `helm template ... | kubeconform -strict -kubernetes-version 1.30.0`.
3. Catch indentation errors and deprecated Kubernetes API versions prior to merge."""
    },
    {
        "milestone": 24,
        "title": "drift(scripts): monorepo consistency script expansion to check Flyway clean-on-validation-error",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
`./scripts/check-monorepo-consistency.sh` verifies Flyway version uniqueness, but does not check if any service accidentally enables `spring.flyway.clean-on-validation-error=true` in production configurations.

### Evidence & Code Reference
- `./scripts/check-monorepo-consistency.sh`.
- `services/*/src/main/resources/application.yml`.

### Blast Radius & Business Impact
Catastrophic accidental database wipe: Flyway drops all production tables if a migration checksum mismatch occurs.

### Remediation Plan
1. Add check in `check-monorepo-consistency.sh` scanning for `clean-on-validation-error`.
2. Assert that the property is either absent or strictly set to `false`.
3. Fail consistency scan if `true` is detected anywhere outside unit test profiles."""
    },
    {
        "milestone": 24,
        "title": "drift(contracts): automated validation that every domain topic in TopicNames.java has corresponding schema",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/data-platform", "java"],
        "body": """### Summary
`TopicNames.java` defines canonical topic constants, but there is no automated test asserting that each domain topic has a corresponding JSON Schema or Protobuf definition in `contracts/`.

### Evidence & Code Reference
- `contracts/src/main/java/com/instacommerce/contracts/topics/TopicNames.java`.
- `contracts/src/main/resources/schemas/`.

### Blast Radius & Business Impact
Orphan or undocumented topics created in Kafka without schema validation governance.

### Remediation Plan
1. Add unit test in `contracts` module reflecting over `TopicNames.java` public constants.
2. Verify that for every topic ending in `.events`, a corresponding schema directory exists in `schemas/`.
3. Fail `./gradlew :contracts:test` if a topic lacks a documented schema."""
    },
    {
        "milestone": 24,
        "title": "drift(ci): path filter verification script preventing silent untracked microservice additions",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
When an engineer adds a new service directory under `services/`, `.github/workflows/ci.yml` path filters must be manually updated; forgetting to update leaves the new service completely untested in CI.

### Evidence & Code Reference
- `.github/workflows/ci.yml`.
- `./scripts/check-monorepo-consistency.sh`.

### Blast Radius & Business Impact
New microservices ship to production without automated CI testing or container builds.

### Remediation Plan
1. Extend `check-monorepo-consistency.sh` Section 1 to verify that every directory in `services/` appears in `ci.yml` path filters and build matrix.
2. Ensure Helm deployment chart exists for every microservice.
3. Fail CI pull requests introducing untracked service folders."""
    },
    {
        "milestone": 24,
        "title": "test(benchmark): JMH microbenchmarks for high-frequency pricing and cart calculation loops",
        "labels": ["kind/feature", "priority/p2-medium", "area/edge-api", "java"],
        "body": """### Summary
Cart subtotal and tax calculation algorithms process thousands of calculations per second without performance regression benchmarks, risking latency degradation during major refactors.

### Evidence & Code Reference
- `services/pricing-service/` and `services/cart-service/`.

### Blast Radius & Business Impact
Subtle CPU utilization regressions increasing p99 cart calculation latency under peak load.

### Remediation Plan
1. Configure Java Microbenchmark Harness (JMH) in `pricing-service`.
2. Benchmark `calculateCartTotal()` and `applyTax()` with 50-item basket permutations.
3. Assert throughput > 50,000 ops/sec per core."""
    },
    {
        "milestone": 24,
        "title": "test(e2e): Playwright synthetic end-to-end browser checkout test suite in staging environment",
        "labels": ["kind/feature", "priority/p1-high", "area/checkout-saga"],
        "body": """### Summary
There is no automated end-to-end browser test simulating a customer landing on the web store, searching for milk, adding to cart, checking out, and verifying order status.

### Evidence & Code Reference
- `tests/e2e/`.

### Blast Radius & Business Impact
Production releases break critical user journeys despite individual microservice unit tests passing.

### Remediation Plan
1. Author Playwright TypeScript end-to-end test suite executing full shopping journey against staging cluster.
2. Mock payment gateway sandbox with test card numbers.
3. Run test suite as a required gate in CI promotion pipeline."""
    },
    {
        "milestone": 24,
        "title": "drift(governance): CODEOWNERS file establishment for cross-team service boundary enforcement",
        "labels": ["kind/tech-debt", "priority/p2-medium", "area/infrastructure"],
        "body": """### Summary
The monorepo lacks a `.github/CODEOWNERS` file, allowing any developer to modify core payment ledger logic, contracts, or infrastructure modules without domain lead review.

### Evidence & Code Reference
- `.github/CODEOWNERS`.
- AGENTS.md Section 1 (Staff-Level Engineering Rules).

### Blast Radius & Business Impact
Accidental architectural drift and violation of domain boundaries by uncoordinated cross-service changes.

### Remediation Plan
1. Author `.github/CODEOWNERS` mapping teams:
   - `@instacommerce/core-checkout`: `services/order-service/`, `services/checkout-orchestrator-service/`, `contracts/src/main/proto/order/`
   - `@instacommerce/payment-eng`: `services/payment-*/`, `services/reconciliation-engine/`
   - `@instacommerce/platform-infra`: `deploy/`, `infra/`, `.github/`
   - `@instacommerce/data-ml`: `data-platform/`, `ml/`, `services/ai-*/`
2. Require code owner approval on pull requests in GitHub branch protection settings."""
    }
]

print(f"Loaded {len(WAVE_6_ISSUES)} Wave 6 issues and {len(WAVE_7_ISSUES)} Wave 7 issues.")
