output "service_accounts" {
  value = { for k, v in google_service_account.service : k => v.email }
}
