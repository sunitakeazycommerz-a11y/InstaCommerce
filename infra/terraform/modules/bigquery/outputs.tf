output "dataset_ids" {
  value = [for dataset in google_bigquery_dataset.datasets : dataset.dataset_id]
}
