# Terraform Variables for SF Canary Phase 2
# Define all input variables for GKE, Ingress, Monitoring, and Firewall

variable "project_id" {
  description = "GCP Project ID"
  type        = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "Project ID must be a valid GCP project ID."
  }
}

variable "region" {
  description = "GCP Region for SF Canary (us-west1)"
  type        = string
  default     = "us-west1"
  validation {
    condition     = var.region == "us-west1"
    error_message = "SF Canary must be deployed in us-west1 region."
  }
}

variable "network_name" {
  description = "VPC network name"
  type        = string
  default     = "instacommerce-vpc"
}

variable "subnetwork_name" {
  description = "VPC subnetwork name"
  type        = string
  default     = "sf-canary-subnet"
}

variable "pods_range_name" {
  description = "Secondary IP range name for Kubernetes pods"
  type        = string
  default     = "pods-range"
}

variable "services_range_name" {
  description = "Secondary IP range name for Kubernetes services"
  type        = string
  default     = "services-range"
}

variable "network_dependency" {
  description = "Dependency on network resources (for proper ordering)"
  type        = any
  default     = null
}

# GKE Cluster variables
variable "cluster_name" {
  description = "GKE cluster name"
  type        = string
  default     = "sf-canary-cluster"
}

variable "dark_store_node_count" {
  description = "Initial number of nodes in dark-store pool"
  type        = number
  default     = 3
  validation {
    condition     = var.dark_store_node_count >= 1 && var.dark_store_node_count <= 10
    error_message = "Node count must be between 1 and 10."
  }
}

variable "dark_store_node_max_count" {
  description = "Maximum number of nodes in dark-store pool (for autoscaling)"
  type        = number
  default     = 10
  validation {
    condition     = var.dark_store_node_max_count >= var.dark_store_node_count
    error_message = "Max node count must be >= initial node count."
  }
}

variable "dark_store_machine_type" {
  description = "Machine type for dark-store nodes"
  type        = string
  default     = "n1-standard-4"  # 4 CPUs, 15GB memory, SSD-backed
  validation {
    condition     = can(regex("^n[1-2]-(standard|highmem|highcpu)-[0-9]+$", var.dark_store_machine_type))
    error_message = "Machine type must be a valid GCP machine type."
  }
}

variable "system_node_count" {
  description = "Initial number of nodes in system pool"
  type        = number
  default     = 2
  validation {
    condition     = var.system_node_count >= 1 && var.system_node_count <= 5
    error_message = "System node count must be between 1 and 5."
  }
}

# Ingress variables
variable "ilb_ip_address" {
  description = "Internal Load Balancer IP address (must be in VPC range)"
  type        = string
  default     = "10.0.1.100"
  validation {
    condition     = can(regex("^10\\.0\\..*", var.ilb_ip_address))
    error_message = "ILB IP address must be in 10.0.x.x range (VPC internal)."
  }
}

# Monitoring variables
variable "notification_channels" {
  description = "Notification channel IDs for alerting"
  type        = list(string)
  default     = []
}

variable "dark_store_endpoint" {
  description = "Dark store service endpoint for health checks"
  type        = string
  default     = "dark-store.instacommerce.internal"
}

# Firewall variables
variable "admin_cidrs" {
  description = "CIDR blocks for admin SSH access"
  type        = list(string)
  default     = []
  validation {
    condition     = alltrue([for cidr in var.admin_cidrs : can(cidrhost(cidr, 0))])
    error_message = "All admin_cidrs must be valid CIDR blocks."
  }
}

# Tags for resource organization
variable "common_labels" {
  description = "Common labels to apply to all resources"
  type        = map(string)
  default = {
    environment = "canary"
    region      = "us-west1"
    phase       = "2"
    deployment  = "dark-store"
    managed_by  = "terraform"
    created_by  = "wave-40-phase-2"
  }
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "canary"
}
