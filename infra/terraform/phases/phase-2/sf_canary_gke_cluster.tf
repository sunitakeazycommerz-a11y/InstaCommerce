# GKE Cluster for SF Canary Deployment
# Phase 2 Infrastructure: us-west1 (San Francisco region)
# Wave 40 Phase 2 - Dark Store Canary

terraform {
  required_version = ">= 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }
}

locals {
  project_id = var.project_id
  region      = "us-west1"
  cluster_name = "sf-canary-cluster"
  environment  = "canary"

  labels = {
    environment = local.environment
    region      = local.region
    phase       = "2"
    deployment  = "dark-store"
    managed_by  = "terraform"
    created_by  = "wave-40-phase-2"
  }
}

# GKE Cluster
resource "google_container_cluster" "sf_canary" {
  name     = local.cluster_name
  location = local.region
  project  = local.project_id

  # Cluster configuration
  min_master_version       = "1.28"
  initial_node_count       = 1
  remove_default_node_pool = true
  network                  = var.network_name
  subnetwork               = var.subnetwork_name

  # Workload Identity
  workload_identity_config {
    workload_pool = "${local.project_id}.svc.id.goog"
  }

  # Network Policy
  network_policy {
    enabled  = true
    provider = "PROVIDER_UNSPECIFIED"
  }

  # Logging and Monitoring
  logging_service    = "logging.googleapis.com/kubernetes"
  monitoring_service = "monitoring.googleapis.com/kubernetes"

  # Security
  ip_allocation_policy {
    cluster_secondary_range_name  = var.pods_range_name
    services_secondary_range_name = var.services_range_name
  }

  # Addons
  addons_config {
    http_load_balancing {
      disabled = false
    }
    horizontal_pod_autoscaling {
      disabled = false
    }
    network_policy_config {
      disabled = false
    }
    cloudrun_config {
      disabled = true
    }
    dns_cache_config {
      enabled = true
    }
    gke_backup_agent_config {
      disabled = false
    }
  }

  # Maintenance Window
  maintenance_policy {
    daily_maintenance_window {
      start_time = "03:00"
    }
  }

  # Binary Authorization
  binary_authorization {
    evaluation_mode = "PROJECT_SINGLETON_POLICY_ENFORCE"
  }

  # Resource labels
  resource_labels = local.labels

  depends_on = [
    var.network_dependency
  ]
}

# Node Pool: Dark-Store Nodes (SSD-backed, high-performance)
resource "google_container_node_pool" "dark_store_pool" {
  name       = "dark-store-pool"
  location   = local.region
  cluster    = google_container_cluster.sf_canary.name
  node_count = var.dark_store_node_count
  project    = local.project_id

  autoscaling {
    min_node_count = var.dark_store_node_count
    max_node_count = var.dark_store_node_max_count
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  node_config {
    machine_type = var.dark_store_machine_type
    disk_size_gb = 100
    disk_type    = "pd-ssd"

    # GKE Service Account
    service_account = google_service_account.gke_nodes.email
    oauth_scopes = [
      "https://www.googleapis.com/auth/cloud-platform",
      "https://www.googleapis.com/auth/logging.write",
      "https://www.googleapis.com/auth/monitoring.write",
    ]

    # Workload Identity
    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    # Node Security
    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }

    # Labels
    labels = merge(
      local.labels,
      {
        node_pool = "dark-store-pool"
        workload  = "dark-store"
      }
    )

    # Taints
    taint {
      key    = "workload"
      value  = "dark-store"
      effect = "NO_SCHEDULE"
    }

    # Metadata
    metadata = {
      disable-legacy-endpoints = "true"
    }

    # Resource requests
    local_ssd_count = 1
  }

  depends_on = [
    google_container_cluster.sf_canary
  ]
}

# Node Pool: System Nodes
resource "google_container_node_pool" "system_pool" {
  name       = "system-pool"
  location   = local.region
  cluster    = google_container_cluster.sf_canary.name
  node_count = var.system_node_count
  project    = local.project_id

  autoscaling {
    min_node_count = var.system_node_count
    max_node_count = 5
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  node_config {
    machine_type = "n1-standard-2"
    disk_size_gb = 50
    disk_type    = "pd-standard"

    service_account = google_service_account.gke_nodes.email
    oauth_scopes = [
      "https://www.googleapis.com/auth/cloud-platform",
    ]

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    labels = merge(
      local.labels,
      {
        node_pool = "system-pool"
      }
    )

    taint {
      key    = "dedicated"
      value  = "system"
      effect = "NO_SCHEDULE"
    }
  }
}

# Service Account for GKE Nodes
resource "google_service_account" "gke_nodes" {
  account_id   = "sf-canary-gke-nodes"
  display_name = "SF Canary GKE Nodes"
  project      = local.project_id
}

# IAM Bindings for GKE Nodes
resource "google_project_iam_member" "gke_nodes_log_writer" {
  project = local.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.gke_nodes.email}"
}

resource "google_project_iam_member" "gke_nodes_metric_writer" {
  project = local.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.gke_nodes.email}"
}

# Workload Identity Binding for Dark-Store Service
resource "google_service_account" "dark_store_workload_identity" {
  account_id   = "dark-store-service"
  display_name = "Dark Store Service Workload Identity"
  project      = local.project_id
}

resource "google_service_account_iam_binding" "dark_store_workload_identity_binding" {
  service_account_id = google_service_account.dark_store_workload_identity.name
  role               = "roles/iam.workloadIdentityUser"

  members = [
    "serviceAccount:${local.project_id}.svc.id.goog[sf-canary/dark-store-sa]"
  ]
}

# Cluster Outputs
output "cluster_name" {
  description = "GKE cluster name"
  value       = google_container_cluster.sf_canary.name
}

output "cluster_endpoint" {
  description = "GKE cluster endpoint"
  value       = google_container_cluster.sf_canary.endpoint
  sensitive   = true
}

output "cluster_ca_certificate" {
  description = "GKE cluster CA certificate"
  value       = google_container_cluster.sf_canary.master_auth[0].cluster_ca_certificate
  sensitive   = true
}

output "region" {
  description = "GCP region"
  value       = local.region
}

output "project_id" {
  description = "GCP project ID"
  value       = local.project_id
}

output "node_pools" {
  description = "Node pools in the cluster"
  value = {
    dark_store_pool = google_container_node_pool.dark_store_pool.name
    system_pool     = google_container_node_pool.system_pool.name
  }
}

output "workload_identity_service_account" {
  description = "Workload Identity service account for dark-store"
  value       = google_service_account.dark_store_workload_identity.email
}
