locals {
  redis_name = var.redis_name != null ? var.redis_name : "instacommerce-feature-store-${var.env}"
  feature_schema = jsonencode([
    {
      name = "entity_id"
      type = "STRING"
      mode = "REQUIRED"
    },
    {
      name = "feature_timestamp"
      type = "TIMESTAMP"
      mode = "REQUIRED"
    },
    {
      name = "features"
      type = "STRING"
      mode = "NULLABLE"
    },
    {
      name = "source"
      type = "STRING"
      mode = "NULLABLE"
    },
    {
      name = "ingested_at"
      type = "TIMESTAMP"
      mode = "NULLABLE"
    }
  ])
}

resource "google_redis_instance" "online_store" {
  name           = local.redis_name
  tier           = var.redis_tier
  memory_size_gb = var.redis_memory_size_gb
  region         = var.region

  authorized_network = var.network_id

  labels = {
    env        = var.env
    component  = "feature-store"
    managed_by = "terraform"
  }
}

resource "google_bigquery_dataset" "feature_store" {
  project    = var.project_id
  dataset_id = var.dataset_id
  location   = var.location

  delete_contents_on_destroy = false

  labels = {
    env        = var.env
    component  = "feature-store"
    managed_by = "terraform"
  }
}

resource "google_bigquery_table" "feature_tables" {
  for_each = var.tables

  project    = var.project_id
  dataset_id = google_bigquery_dataset.feature_store.dataset_id
  table_id   = each.key
  schema     = local.feature_schema
  description = each.value

  time_partitioning {
    type  = "DAY"
    field = "feature_timestamp"
  }

  labels = {
    env        = var.env
    component  = "feature-store"
    managed_by = "terraform"
  }
}
