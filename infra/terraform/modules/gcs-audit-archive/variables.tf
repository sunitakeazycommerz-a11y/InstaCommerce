variable "project_id" {
  type        = string
  description = "GCP Project ID"
}

variable "env" {
  type        = string
  description = "Environment (dev, staging, prod)"
  validation {
    condition     = contains(["dev", "staging", "prod"], var.env)
    error_message = "env must be dev, staging, or prod"
  }
}

variable "region" {
  type        = string
  description = "Primary GCP region for archive bucket"
  default     = "us-central1"
}

variable "archive_region" {
  type        = string
  description = "GCP region for archive storage"
  default     = "us-central1"
}

variable "replica_region" {
  type        = string
  description = "GCP region for replica archive bucket (for cross-region replication)"
  default     = "us-east1"
}

variable "enable_cross_region_replication" {
  type        = bool
  description = "Enable cross-region replication for disaster recovery"
  default     = true
}

variable "kms_keyring" {
  type        = string
  description = "KMS key ring name for customer-managed encryption keys"
  default     = "audit-archive-keyring"
}

variable "kms_key" {
  type        = string
  description = "KMS key name for customer-managed encryption"
  default     = "audit-archive-key"
}

variable "audit_trail_service_account" {
  type        = string
  description = "Service account email for audit-trail-service (writer)"
}

variable "compliance_team_group" {
  type        = string
  description = "Google Group email for compliance team (reader)"
}

variable "archive_quota_threshold" {
  type        = number
  description = "Threshold in bytes for archive quota warning (typically 80% of total quota)"
  default     = 8589934592 # 8TB (80% of 10TB)
}

variable "notification_channels" {
  type        = list(string)
  description = "List of Cloud Monitoring notification channel IDs for alerts"
  default     = []
}
