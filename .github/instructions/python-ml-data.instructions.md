---
applyTo: "services/ai-*/**/*.py,services/ai-*/requirements.txt,ml/**/*.py,ml/**/*.yaml,ml/**/*.yml,data-platform/**/*.py,data-platform/**/*.sql,data-platform/**/*.yml,data-platform/**/*.yaml,data-platform/**/requirements.txt"
---

- AI services are FastAPI apps with per-service `requirements.txt` and `uvicorn app.main:app` entrypoints. Preserve that runtime shape when adding endpoints or dependencies.
- ML training is config-driven under `ml/train/*/config.yaml`; prefer updating config, evaluation gates, and feature definitions instead of hardcoding model behavior in scripts.
- The data platform has clear layer boundaries: dbt `stg_` -> `int_` -> `mart_`, Airflow for orchestration, Beam/Dataflow for streaming, and Great Expectations for quality gates. Keep logic in the right layer instead of collapsing responsibilities.
- Validate Python service changes with targeted `pytest` runs, and validate data-platform changes with dbt selectors such as `dbt test --select <model-or-selector>`.
- When a change affects events, features, or analytics semantics, check upstream contracts and downstream feature/model/data-consumer impact before implementing it.
