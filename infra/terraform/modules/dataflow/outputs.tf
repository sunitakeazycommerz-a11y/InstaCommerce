output "service_account_email" {
  value = google_service_account.dataflow.email
}

output "staging_bucket_name" {
  value = google_storage_bucket.staging.name
}
