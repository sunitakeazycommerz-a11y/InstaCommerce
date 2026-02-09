resource "google_service_account" "service" {
  for_each = toset(var.service_accounts)
  account_id   = each.key
  display_name = "instacommerce-${each.key}-${var.env}"
}
