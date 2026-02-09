locals {
  staging_bucket_name = "${var.project_id}-dataflow-staging"
}

resource "google_service_account" "dataflow" {
  project      = var.project_id
  account_id   = "${var.service_account_name}-${var.env}"
  display_name = "instacommerce-dataflow-${var.env}"
}

resource "google_storage_bucket" "staging" {
  name     = local.staging_bucket_name
  project  = var.project_id
  location = var.location

  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  versioning {
    enabled = true
  }

  labels = {
    env        = var.env
    component  = "data-platform"
    managed_by = "terraform"
  }
}

resource "google_project_iam_member" "dataflow_roles" {
  for_each = toset(var.project_roles)

  project = var.project_id
  role    = each.key
  member  = "serviceAccount:${google_service_account.dataflow.email}"
}
