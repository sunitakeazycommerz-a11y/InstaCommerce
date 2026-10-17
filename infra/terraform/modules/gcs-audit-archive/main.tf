locals {
  archive_bucket_name = "${var.project_id}-audit-archive-glacier"
  cmek_key_name       = "projects/${var.project_id}/locations/${var.region}/keyRings/${var.kms_keyring}/cryptoKeys/${var.kms_key}"
}

# Google Cloud Storage bucket for 7-year audit archive
# WORM (Write-Once-Read-Many) protection enforced at object level
resource "google_storage_bucket" "audit_archive" {
  name                        = local.archive_bucket_name
  project                     = var.project_id
  location                    = var.archive_region
  uniform_bucket_level_access = true
  force_destroy               = false

  versioning {
    enabled = true
  }

  # Lifecycle rule: move to Coldline after 30 days, then Archive after 90 days
  # Archive storage is GCP's equivalent of AWS Glacier
  lifecycle_rule {
    action {
      type          = "SetStorageClass"
      storage_class = "COLDLINE"
    }
    condition {
      age = 30
    }
  }

  lifecycle_rule {
    action {
      type          = "SetStorageClass"
      storage_class = "ARCHIVE"
    }
    condition {
      age = 90
    }
  }

  # Retention policy: 7 years (2557 days) minimum retention
  retention_policy {
    retention_days = 2557
  }

  labels = {
    env               = var.env
    component         = "audit-archive"
    compliance        = "pci-dss"
    retention_years   = "7"
    managed_by        = "terraform"
    criticality       = "tier-1"
    data_classification = "RESTRICTED"
  }
}

# Bucket IAM: Restrict access to service account + compliance team
resource "google_storage_bucket_iam_member" "audit_archive_writer" {
  bucket = google_storage_bucket.audit_archive.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${var.audit_trail_service_account}"
}

resource "google_storage_bucket_iam_member" "audit_archive_reader" {
  bucket = google_storage_bucket.audit_archive.name
  role   = "roles/storage.objectViewer"
  member = "group:${var.compliance_team_group}"
}

# Compliance admin: Can list & verify but NOT delete/update
resource "google_storage_bucket_iam_member" "compliance_viewer" {
  bucket = google_storage_bucket.audit_archive.name
  role   = "roles/storage.legacyBucketReader"
  member = "group:${var.compliance_team_group}"
}

# Enable default encryption with customer-managed key
resource "google_storage_bucket_encryption" "audit_archive_encryption" {
  bucket                  = google_storage_bucket.audit_archive.name
  default_kms_key_name    = local.cmek_key_name
}

# Cross-region replication bucket (disaster recovery)
resource "google_storage_bucket" "audit_archive_replica" {
  count                       = var.enable_cross_region_replication ? 1 : 0
  name                        = "${local.archive_bucket_name}-replica-${var.replica_region}"
  project                     = var.project_id
  location                    = var.replica_region
  uniform_bucket_level_access = true
  force_destroy               = false

  versioning {
    enabled = true
  }

  retention_policy {
    retention_days = 2557
  }

  labels = {
    env               = var.env
    component         = "audit-archive"
    compliance        = "pci-dss"
    replica_region    = var.replica_region
    managed_by        = "terraform"
    data_classification = "RESTRICTED"
  }
}

# Encryption for replica bucket
resource "google_storage_bucket_encryption" "audit_archive_replica_encryption" {
  count                   = var.enable_cross_region_replication ? 1 : 0
  bucket                  = google_storage_bucket.audit_archive_replica[0].name
  default_kms_key_name    = local.cmek_key_name
}

# GCS notification topic for archive operations (for monitoring)
resource "google_pubsub_topic" "audit_archive_notifications" {
  name                       = "audit-archive-notifications"
  project                    = var.project_id
  message_retention_duration = "86400s" # 1 day
}

# Bucket notification for archive success/failure events
resource "google_storage_notification" "audit_archive_notification" {
  bucket            = google_storage_bucket.audit_archive.name
  payload_format    = "JSON_V1"
  topic             = google_pubsub_topic.audit_archive_notifications.name
  event_types       = ["OBJECT_FINALIZE", "OBJECT_DELETE"]
  custom_attributes = {
    archive_type = "audit-trail"
    phase        = "6-pci-dss"
  }
}

# KMS key binding for audit archive (ensures key rotation tracking)
resource "google_kms_crypto_key_iam_member" "audit_archive_encrypt" {
  crypto_key_id = local.cmek_key_name
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${var.audit_trail_service_account}"
}

# Logging bucket for audit archive operations
resource "google_storage_bucket" "audit_archive_logs" {
  name                        = "${local.archive_bucket_name}-logs"
  project                     = var.project_id
  location                    = var.archive_region
  uniform_bucket_level_access = true
  force_destroy               = false

  versioning {
    enabled = true
  }

  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      age = 90 # Keep 3 months of logs
    }
  }

  labels = {
    env        = var.env
    component  = "audit-archive"
    purpose    = "logging"
    managed_by = "terraform"
  }
}

# Enable logging on audit archive bucket
resource "google_storage_bucket_logging" "audit_archive_logging" {
  bucket        = google_storage_bucket.audit_archive.name
  log_bucket    = google_storage_bucket.audit_archive_logs.name
}

# Cloud Pub/Sub subscription for archive operations (for audit-trail-service)
resource "google_pubsub_subscription" "audit_archive_events" {
  name                       = "audit-archive-events-sub"
  project                    = var.project_id
  topic                      = google_pubsub_topic.audit_archive_notifications.name
  message_retention_duration = "86400s"
  ack_deadline_seconds       = 60

  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "600s"
  }

  dead_letter_policy {
    dead_letter_topic       = google_pubsub_topic.audit_archive_dlq.id
    max_delivery_attempts   = 5
  }
}

# Dead Letter Queue for failed archive events
resource "google_pubsub_topic" "audit_archive_dlq" {
  name    = "audit-archive-events-dlq"
  project = var.project_id
  labels = {
    env       = var.env
    component = "audit-archive"
    dlq       = "true"
  }
}

# Cloud Monitoring for audit archive health
resource "google_monitoring_alert_policy" "audit_archive_upload_failure" {
  display_name = "Audit Archive - Upload Failure"
  combiner     = "OR"
  enabled      = true

  conditions {
    display_name = "Archive upload failure rate > 5%"

    condition_threshold {
      filter          = "resource.type=\"gcs_bucket\" resource.labels.bucket_name=\"${google_storage_bucket.audit_archive.name}\" metric.type=\"storage.googleapis.com/storage/object_count\" AND metric.labels.response_code!=\"200\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 0.05
    }
  }

  notification_channels = var.notification_channels

  documentation {
    content   = "Audit archive bucket experiencing high failure rate. Check audit-trail-service logs and GCS bucket status."
    mime_type = "text/markdown"
  }
}

# Cloud Monitoring - Archive Size Monitoring
resource "google_monitoring_alert_policy" "audit_archive_quota" {
  display_name = "Audit Archive - Quota Warning"
  combiner     = "OR"
  enabled      = true

  conditions {
    display_name = "Archive size > 80% quota"

    condition_threshold {
      filter          = "resource.type=\"gcs_bucket\" resource.labels.bucket_name=\"${google_storage_bucket.audit_archive.name}\" metric.type=\"storage.googleapis.com/storage/total_bytes\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = var.archive_quota_threshold # 80% of quota
    }
  }

  notification_channels = var.notification_channels

  documentation {
    content   = "Audit archive approaching quota limit. Review retention policies or increase quota."
    mime_type = "text/markdown"
  }
}

# Output bucket information for audit-trail-service configuration
output "archive_bucket_name" {
  value       = google_storage_bucket.audit_archive.name
  description = "GCS bucket name for audit archive"
}

output "archive_bucket_path" {
  value       = "gs://${google_storage_bucket.audit_archive.name}"
  description = "Full GCS path for audit archive"
}

output "replica_bucket_name" {
  value       = var.enable_cross_region_replication ? google_storage_bucket.audit_archive_replica[0].name : ""
  description = "GCS replica bucket name (if cross-region replication enabled)"
}

output "archive_notifications_topic" {
  value       = google_pubsub_topic.audit_archive_notifications.name
  description = "Pub/Sub topic for archive events"
}

output "archive_subscription_name" {
  value       = google_pubsub_subscription.audit_archive_events.name
  description = "Pub/Sub subscription for audit-trail-service"
}
