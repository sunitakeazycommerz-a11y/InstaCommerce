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
  description = "GCP region for KMS resources"
  default     = "us-central1"
}

variable "keyring_name" {
  type        = string
  description = "Name of the KMS keyring for audit archive"
  default     = "audit-archive-keyring"
}

variable "primary_key_name" {
  type        = string
  description = "Name of primary encryption key"
  default     = "audit-archive-key-primary"
}

variable "secondary_key_name" {
  type        = string
  description = "Name of secondary encryption key (for rotation)"
  default     = "audit-archive-key-secondary"
}

variable "enable_secondary_key" {
  type        = bool
  description = "Enable secondary key for smooth key rotation"
  default     = true
}

variable "key_rotation_period" {
  type        = string
  description = "Key rotation period (e.g., '7776000s' = 90 days)"
  default     = "7776000s" # 90 days in seconds
}

variable "audit_trail_service_account" {
  type        = string
  description = "Service account email for audit-trail-service"
}

variable "compliance_team_group" {
  type        = string
  description = "Google Group email for compliance team"
}

variable "notification_channels" {
  type        = list(string)
  description = "List of Cloud Monitoring notification channel IDs for alerts"
  default     = []
}
