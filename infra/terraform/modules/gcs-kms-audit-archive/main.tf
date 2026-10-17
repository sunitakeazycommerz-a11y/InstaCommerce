# Cloud KMS keyring for audit archive encryption
resource "google_kms_key_ring" "audit_archive" {
  name     = var.keyring_name
  location = var.region
  project  = var.project_id
}

# Primary encryption key for audit archive
resource "google_kms_crypto_key" "audit_archive_primary" {
  name                           = var.primary_key_name
  key_ring                       = google_kms_key_ring.audit_archive.id
  rotation_period                = var.key_rotation_period # 90 days
  skip_initial_version_creation  = false
  prevent_destroy                = true
  labels = {
    env               = var.env
    component         = "audit-archive"
    purpose           = "pci-compliance"
    data_classification = "RESTRICTED"
  }
}

# Secondary encryption key (for key rotation smooth transition)
resource "google_kms_crypto_key" "audit_archive_secondary" {
  count                          = var.enable_secondary_key ? 1 : 0
  name                           = var.secondary_key_name
  key_ring                       = google_kms_key_ring.audit_archive.id
  rotation_period                = var.key_rotation_period
  skip_initial_version_creation  = false
  prevent_destroy                = true
  labels = {
    env               = var.env
    component         = "audit-archive"
    purpose           = "pci-compliance"
    backup_key        = "true"
  }
}

# IAM: Grant audit-trail-service access to encrypt/decrypt with primary key
resource "google_kms_crypto_key_iam_member" "audit_service_encrypt_primary" {
  crypto_key_id = google_kms_crypto_key.audit_archive_primary.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${var.audit_trail_service_account}"
}

# IAM: Grant audit-trail-service access to secondary key (for seamless rotation)
resource "google_kms_crypto_key_iam_member" "audit_service_encrypt_secondary" {
  count         = var.enable_secondary_key ? 1 : 0
  crypto_key_id = google_kms_crypto_key.audit_archive_secondary[0].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${var.audit_trail_service_account}"
}

# IAM: Grant compliance team read-only access to key versions (for audit)
resource "google_kms_crypto_key_iam_member" "compliance_viewer_primary" {
  crypto_key_id = google_kms_crypto_key.audit_archive_primary.id
  role          = "roles/cloudkms.viewer"
  member        = "group:${var.compliance_team_group}"
}

# Cloud Audit Logs: Track all KMS operations
resource "google_project_iam_member" "audit_logs_viewer" {
  project = var.project_id
  role    = "roles/logging.privateLogViewer"
  member  = "group:${var.compliance_team_group}"
}

# Cloud Monitoring: Alert on key rotation failures
resource "google_monitoring_alert_policy" "kms_rotation_failure" {
  display_name = "KMS Audit Archive Key - Rotation Failure"
  combiner     = "OR"
  enabled      = true

  conditions {
    display_name = "Key rotation failure detected"

    condition_threshold {
      filter          = "resource.type=\"k8s_cluster\" AND protoPayload.methodName=\"cloudkms.projects.locations.keyRings.cryptoKeys.rotate\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 0
    }
  }

  notification_channels = var.notification_channels

  documentation {
    content   = "KMS key rotation failed for audit archive encryption. Review KMS and Audit Logs for details."
    mime_type = "text/markdown"
  }
}

# Outputs for audit-trail-service configuration
output "keyring_name" {
  value       = google_kms_key_ring.audit_archive.name
  description = "KMS keyring name"
}

output "keyring_id" {
  value       = google_kms_key_ring.audit_archive.id
  description = "KMS keyring full ID"
}

output "primary_key_name" {
  value       = google_kms_crypto_key.audit_archive_primary.name
  description = "Primary encryption key name"
}

output "primary_key_id" {
  value       = google_kms_crypto_key.audit_archive_primary.id
  description = "Primary encryption key full ID"
}

output "secondary_key_id" {
  value       = var.enable_secondary_key ? google_kms_crypto_key.audit_archive_secondary[0].id : ""
  description = "Secondary encryption key full ID (for rotation)"
}

output "rotation_period" {
  value       = var.key_rotation_period
  description = "Key rotation period"
}
