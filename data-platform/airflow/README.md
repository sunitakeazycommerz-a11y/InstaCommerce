# InstaCommerce Airflow Orchestration Engine

Authoritative documentation for Apache Airflow DAGs, scheduling cadence, sensors, and data lineage in InstaCommerce.

---

## 1. Runtime & Environment

- **Runtime:** Apache Airflow 3.x / Google Cloud Composer in `asia-south1`.
- **Lineage:** OpenLineage integration enabled via `openlineage.yml` emitting operational metadata to Marquez (`http://marquez:5000/api/v1/lineage`).
- **Slack Alerting:** `plugins/slack_alerts.py` automatically posts formatted Slack Block Kit failure cards on task failures.

---

## 2. DAG Inventory (8 Production DAGs)

| DAG ID | Schedule | Owner | Key Tasks & Sensor Dependencies | Purpose |
|---|---|---|---|---|
| `dbt_staging` | `0 */2 * * *` | Data Engineering | `BashOperator(dbt run --select staging)` | Bi-hourly staging view refresh |
| `data_quality` | `0 */2 * * *` | Data Quality | `ExternalTaskSensor(dbt_staging)` → Great Expectations | Data contract and boundary validation |
| `dbt_marts` | `0 2 * * *` | Analytics | `KafkaLagSensor(orders.events)` → `dbt run --select marts` | Daily analytical fact and dimension build |
| `ml_feature_refresh` | `0 */4 * * *` | MLOps | Feature computation SQL queries in BigQuery | 4-hourly Vertex AI feature group hydration |
| `ml_training` | `0 4 * * *` | MLOps | Vertex AI CustomJob trigger + promotion gate eval | Daily automated model retraining & evaluation |
| `monitoring_alerts` | `*/15 * * * *` | Platform SRE | SLA threshold checks across marts | Proactive alerting on delivery/revenue dips |
| `historical_backfill` | `None` (Manual) | Data Platform | Partition-aware dbt parameter sweeps | Manual backfills with `conf: {"start_date", "end_date", "model_name"}` |
| `search_ranking_retraining` | `0 2 * * 0` | Search & ML | Sunday weekly LambdaMART retrain | Weekly retraining on recent search query logs |

---

## 3. Local Development & Testing

```bash
# Run DAG syntax and integrity tests
cd data-platform/airflow
pytest tests/test_airflow_dags.py -v
```
