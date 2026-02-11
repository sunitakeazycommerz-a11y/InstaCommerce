# Streaming Data Platform — InstaCommerce

## Architecture Overview

The InstaCommerce streaming platform ingests real-time event streams from
Kafka topics, applies windowed aggregations via Apache Beam / Google Cloud
Dataflow, and sinks results into BigQuery for analytics and alerting.

```
  Kafka Topics                 Beam Pipelines               Sinks
  ─────────────               ──────────────               ─────
  order.events       ──►  order_events_pipeline       ──►  BigQuery (analytics.realtime_order_volume)
  payment.events     ──►  payment_events_pipeline     ──►  BigQuery (analytics.payment_metrics)
  rider.location     ──►  rider_location_pipeline     ──►  BigQuery (analytics.rider_utilization)
  inventory.events   ──►  inventory_events_pipeline   ──►  BigQuery (analytics.inventory_velocity)
  cart.events        ──►  cart_events_pipeline         ──►  BigQuery (analytics.cart_abandonment)
```

## Pipelines

| Pipeline | Source Topic | Window | Key Metrics |
|---|---|---|---|
| `order_events_pipeline` | `order.events` | 1-min fixed / 30-min sliding | Orders per minute, GMV, SLA compliance |
| `payment_events_pipeline` | `payment.events` | 1-min fixed | Payment success rate, latency P95 |
| `rider_location_pipeline` | `rider.location` | 1-min fixed | Rider utilization per zone |
| `inventory_events_pipeline` | `inventory.events` | 5-min fixed | Inventory velocity, stockout detection |
| `cart_events_pipeline` | `cart.events` | 15-min session | Cart abandonment rate |

## Deployment

Pipelines are deployed as Dataflow Flex Templates. See `deploy/dataflow_template.yaml`
for configuration. Autoscaling is throughput-based with a cap of 10 workers per pipeline.

### Quick Start

```bash
pip install -r requirements.txt

# Local runner (DirectRunner)
python pipelines/order_events_pipeline.py \
  --runner=DirectRunner \
  --kafka_bootstrap=localhost:9092

# Dataflow
python pipelines/order_events_pipeline.py \
  --runner=DataflowRunner \
  --project=instacommerce-prod \
  --region=asia-south1 \
  --temp_location=gs://instacommerce-dataflow/temp
```

## Data Quality

Great Expectations suites in `../quality/expectations/` validate landed data.
`run_quality_checks.py` executes all suites and publishes results to the
monitoring dashboard.

## Monitoring

- **Dataflow Console**: pipeline lag, element counts, error rates
- **Grafana dashboards**: end-to-end latency, throughput per topic
- **PagerDuty alerts**: consumer lag > 5 min, error rate > 1%
