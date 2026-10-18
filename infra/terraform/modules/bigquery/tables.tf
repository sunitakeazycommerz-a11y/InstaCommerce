# Partition expiration policy for raw streaming audit tables (Issue #658)
# Enforces 90-day partition expiration (7776000000 ms) to cap storage costs on raw JSON events.

resource "google_bigquery_table" "raw_streaming_tables" {
  for_each = toset([
    "analytics_raw_order_events",
    "analytics_raw_payment_events",
    "analytics_raw_inventory_events",
    "analytics_raw_rider_pings"
  ])
  dataset_id = "analytics"
  table_id   = each.key
  project    = var.project_id

  time_partitioning {
    type          = "DAY"
    expiration_ms = 7776000000 # 90 days retention in milliseconds
    field         = "event_time"
  }

  clustering = ["store_id", "event_type"]

  labels = {
    env              = var.env
    retention_policy = "90d_partition_expiration"
    tier             = "raw_streaming"
    managed_by       = "terraform"
  }
}
