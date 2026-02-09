output "secret_ids" {
  value = keys(google_secret_manager_secret.secret)
}
