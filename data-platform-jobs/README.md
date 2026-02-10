# Data Platform Jobs

Python job module for data platform ETL, feature computation, and a Kafka→BigQuery batch loader skeleton, aligned with `docs/reviews/data-platform-ml-design.md`.

## Layout
- `data_platform_jobs/cli.py` - CLI runner (list jobs, run job, export schedules).
- `data_platform_jobs/jobs/etl.py` - staging + marts ETL query jobs.
- `data_platform_jobs/jobs/features.py` - feature refresh computation.
- `data_platform_jobs/jobs/kafka_to_bigquery.py` - Kafka batch loader skeleton.
- `data_platform_jobs/hooks/scheduler.py` - scheduling hooks (cron metadata export).

## Quick start
```bash
cd data-platform-jobs
python -m data_platform_jobs list
python -m data_platform_jobs run --job staging_etl --dry-run
python -m data_platform_jobs schedules --format json
```

## Configuration
Set environment variables as needed (defaults are provided):
- `DATA_PLATFORM_PROJECT_ID` (default: `instacommerce-dev`)
- `DATA_PLATFORM_BQ_DATASET_RAW` (default: `raw`)
- `DATA_PLATFORM_BQ_DATASET_STAGING` (default: `staging`)
- `DATA_PLATFORM_BQ_DATASET_MARTS` (default: `marts`)
- `DATA_PLATFORM_BQ_DATASET_FEATURES` (default: `features`)
- `DATA_PLATFORM_KAFKA_BOOTSTRAP` (default: `localhost:9092`)
- `DATA_PLATFORM_KAFKA_TOPICS` (default: `order.events,payment.events`)
- `DATA_PLATFORM_KAFKA_CONSUMER_GROUP` (default: `data-platform-batch-loader`)
- `DATA_PLATFORM_KAFKA_MAX_MESSAGES` (default: `5000`)
- `DATA_PLATFORM_BATCH_WINDOW_MINUTES` (default: `15`)
- `DATA_PLATFORM_TIMEZONE` (default: `UTC`)

## Schedules (default)
| Job | Cron (UTC) | Purpose |
| --- | --- | --- |
| `staging_etl` | `0 */2 * * *` | Refresh staging tables |
| `marts_etl` | `0 2 * * *` | Refresh marts |
| `feature_refresh` | `0 */4 * * *` | Recompute ML features |
| `kafka_to_bigquery_batch` | `*/15 * * * *` | Batch-load Kafka events into BigQuery |
| `data_quality` | `0 */2 * * *` | Post-load checks |

## Notes
The BigQuery and Kafka integrations are intentionally lightweight; install Google Cloud and Kafka client libraries in the runtime environment when wiring to production infrastructure.
