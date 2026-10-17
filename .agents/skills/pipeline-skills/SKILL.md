---
name: pipeline-skills
description: Data platform and ML pipeline engineering for dbt layered modeling, Apache Airflow DAGs, Apache Beam/Dataflow, and Great Expectations.
---

# Pipeline Skills: Data Platform & ML Engineering

Use this skill when developing or modifying analytics models in `data-platform/`, Airflow orchestration DAGs, Beam streaming jobs, or ML pipelines in `ml/`.

---

## 1. dbt Layer Discipline (`data-platform/dbt`)

Never collapse transformation logic into a single monolithic model. Adhere strictly to the three-tier dbt architecture:

```
  Raw Sources (Kafka / Postgres CDC / BigQuery)
       │
       ▼
  Staging Layer (`models/staging/stg_*.sql`)
  • 1:1 with source tables
  • Column renaming, type casting, timezone normalization
  • No multi-table business joins
       │
       ▼
  Intermediate Layer (`models/intermediate/int_*.sql`)
  • Business calculations (e.g. order item aggregations, fulfillment duration)
  • Deduplication and status transition logic
  • Internal building blocks (ephemeral or materialized as views)
       │
       ▼
  Marts Layer (`models/marts/mart_*.sql`)
  • Dimensional star schemas (`fct_*` facts, `dim_*` dimensions)
  • Public data products consumed by dashboards, reverse ETL, and ML
  • Schema contracts and Great Expectations quality gates applied here
```

---

## 2. Airflow DAG Standards (`data-platform/airflow`)

1. **Idempotence**: Every DAG run must be re-runnable for any historical `execution_date` without corrupting downstream state or duplicating rows.
2. **Never Top-Level Connections**: Never establish database connections or call external APIs at the top level of a Python DAG file; only do so inside operator `execute()` or Python callables.
3. **Partitioned Ingestion**: Always partition queries by `{{ ds }}` or `{{ logical_date }}`.

---

## 3. Apache Beam / Dataflow Streaming (`data-platform/streaming`)

1. **Windowing & Watermarks**: Use fixed windows or sliding windows with explicit allowed lateness:
   ```python
   windowed_events = events | beam.WindowInto(
       beam.window.FixedWindows(60),
       allowed_lateness=beam.utils.timestamp.Duration(seconds=300),
       accumulation_mode=beam.transforms.trigger.AccumulationMode.DISCARDING
   )
   ```
2. **Dead-Letter Handling**: Any schema decoding or deserialization failure must route to a tagged dead-letter output (`TaggedOutput('dead_letter', error_record)`), never terminating the streaming pipeline.

---

## 4. Great Expectations Quality Gates (`data-platform/quality`)

Every `mart_*` table must have an Expectation Suite asserting:
- Primary key uniqueness (`expect_column_values_to_be_unique`)
- Not-null invariants on mandatory fields (`expect_column_values_to_not_be_null`)
- Value range sanity (e.g. `total_amount_cents >= 0`)
- Referencing integrity between fact and dimension keys

---

## 5. dbt & Pipeline Command Reference

```bash
# Install dbt dependencies
cd data-platform/dbt && dbt deps

# Run layered transformation pipeline
cd data-platform/dbt && dbt run --select staging
cd data-platform/dbt && dbt run --select intermediate
cd data-platform/dbt && dbt run --select marts

# Run data quality tests
cd data-platform/dbt && dbt test --select marts
```

