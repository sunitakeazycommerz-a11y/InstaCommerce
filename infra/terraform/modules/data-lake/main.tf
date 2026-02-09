locals {
  bucket_name = "${var.project_id}-data-lake"
}

resource "google_storage_bucket" "data_lake" {
  name     = local.bucket_name
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

  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      age            = var.raw_retention_days
      matches_prefix = ["raw/"]
    }
  }
}
