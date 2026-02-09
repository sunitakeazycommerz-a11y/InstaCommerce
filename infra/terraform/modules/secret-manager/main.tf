resource "google_secret_manager_secret" "secret" {
  for_each = toset(var.secrets)
  secret_id = each.key
  replication {
    automatic = true
  }
}
