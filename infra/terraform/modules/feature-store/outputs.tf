output "dataset_id" {
  value = google_bigquery_dataset.feature_store.dataset_id
}

output "table_ids" {
  value = [for table in google_bigquery_table.feature_tables : table.table_id]
}

output "redis_instance" {
  value = google_redis_instance.online_store.name
}

output "redis_host" {
  value = google_redis_instance.online_store.host
}

output "redis_port" {
  value = google_redis_instance.online_store.port
}
