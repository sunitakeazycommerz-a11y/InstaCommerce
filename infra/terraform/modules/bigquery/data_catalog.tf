# Google Cloud Data Catalog Policy Tags & Taxonomy for GDPR / CCPA PII compliance (Issue #671)
# Defines taxonomy with Confidential and Sensitive tags and dynamic data masking.

resource "google_data_catalog_taxonomy" "pii_taxonomy" {
  project                = var.project_id
  region                 = var.location
  display_name           = "PII Data Taxonomy"
  description            = "Taxonomy for classifying customer PII under GDPR and Indian DPDP Act"
  activated_policy_types = ["FINE_GRAINED_ACCESS_CONTROL"]
}

resource "google_data_catalog_policy_tag" "confidential" {
  taxonomy     = google_data_catalog_taxonomy.pii_taxonomy.id
  display_name = "PII.Confidential"
  description  = "Highly sensitive customer PII: phone numbers, coordinates, delivery addresses"
}

resource "google_data_catalog_policy_tag" "sensitive" {
  taxonomy     = google_data_catalog_taxonomy.pii_taxonomy.id
  display_name = "PII.Sensitive"
  description  = "Customer identifiers and demographic attributes"
}

# Dynamic data masking rule for customer PII columns
resource "google_bigquery_datapolicy_data_policy" "phone_masking_policy" {
  project          = var.project_id
  location         = var.location
  data_policy_id   = "mask_customer_phone"
  policy_tag       = google_data_catalog_policy_tag.confidential.name
  data_policy_type = "DATA_MASKING_POLICY"

  data_masking_policy {
    predefined_datamask_type = "ALWAYS_NULL"
  }
}
