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
  project_id      = var.project_id
  region          = var.region
  cluster_name    = var.cluster_name
  network_name    = var.network_name
  subnetwork_name = var.subnetwork_name
  labels = merge(
    var.common_labels,
    {
      environment = var.environment
      region      = var.region
    }
  )
}

resource "google_compute_address" "dark_store_ilb" {
  name          = "dark-store-ilb-address"
  address_type  = "INTERNAL"
  purpose       = "SHARED_LOADBALANCER_VIP"
  address       = var.ilb_ip_address
  region        = local.region
  network       = local.network_name
  subnetwork    = local.subnetwork_name
  project       = local.project_id
  description   = "Internal Load Balancer address for SF dark-store canary"

  labels = local.labels
}

resource "google_compute_health_check" "dark_store_health_check" {
  name                = "dark-store-health-check"
  project             = local.project_id
  check_interval_sec  = 10
  timeout_sec         = 5
  healthy_threshold   = 2
  unhealthy_threshold = 3

  http_health_check {
    port               = var.dark_store_service_port
    request_path       = var.dark_store_health_check_path
    proxy_header       = "NONE"
    port_specification = "USE_FIXED_PORT"
  }

  description = "Health check for dark-store pods"
}

resource "google_compute_backend_service" "dark_store_backend" {
  name                    = "dark-store-backend"
  project                 = local.project_id
  region                  = local.region
  load_balancing_scheme   = "INTERNAL"
  protocol                = "TCP"
  timeout_sec             = 30
  enable_cdn              = false
  session_affinity        = "CLIENT_IP"
  affinity_cookie_ttl_sec = 3600

  health_checks = [google_compute_health_check.dark_store_health_check.id]

  connection_draining_timeout_sec = 60

  log_config {
    enable      = true
    sample_rate = 0.1
  }

  iap {
    enabled = false
  }

  description = "Backend service for SF dark-store canary"

  labels = local.labels

  depends_on = [google_compute_health_check.dark_store_health_check]
}

# Automatically managed by GKE when using Ingress with BackendConfig.
resource "google_compute_network_endpoint_group" "dark_store_neg" {
  name                  = "dark-store-neg"
  project               = local.project_id
  region                = local.region
  network_endpoint_type = "GCE_VM_IP_PORT"
  network               = local.network_name
  default_port          = var.dark_store_service_port
  description           = "NEG for dark-store pods"

  labels = local.labels
}

resource "google_compute_forwarding_rule" "dark_store_ilb" {
  name                  = "dark-store-ilb-rule"
  project               = local.project_id
  region                = local.region
  load_balancing_scheme = "INTERNAL"
  backend_service       = google_compute_backend_service.dark_store_backend.id
  network               = local.network_name
  subnetwork            = local.subnetwork_name
  ip_address            = google_compute_address.dark_store_ilb.id
  ip_protocol           = "TCP"
  ports                 = [tostring(var.dark_store_service_port)]
  allow_global_access   = false

  service_label = "dark-store"
  description   = "Internal LB forwarding rule for dark-store canary (100% traffic)"

  labels = local.labels

  depends_on = [google_compute_backend_service.dark_store_backend]
}

resource "google_compute_firewall" "dark_store_ilb_allow" {
  name        = "dark-store-ilb-allow"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1000
  description = "Allow internal traffic to dark-store ILB"

  allow {
    protocol = "tcp"
    ports    = distinct([tostring(var.dark_store_service_port), "8443"])
  }

  source_ranges = [
    "10.0.0.0/8"
  ]

  target_tags = ["dark-store-ilb"]
}

resource "google_compute_firewall" "dark_store_health_check" {
  name        = "dark-store-health-check"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1001
  description = "Allow health checks to dark-store"

  allow {
    protocol = "tcp"
    ports    = distinct(concat(["8443"], var.dark_store_canary_service_ports, [tostring(var.dark_store_service_port)]))
  }

  source_ranges = [
    "35.191.0.0/16",      # Google Cloud health check ranges
    "130.211.0.0/22"
  ]

  target_tags = ["dark-store"]
}

output "ilb_ip_address" {
  description = "Internal Load Balancer IP address"
  value       = google_compute_address.dark_store_ilb.address
}

output "ilb_forwarding_rule" {
  description = "Forwarding rule name"
  value       = google_compute_forwarding_rule.dark_store_ilb.name
}

output "backend_service_id" {
  description = "Backend service ID"
  value       = google_compute_backend_service.dark_store_backend.id
}

output "backend_service_self_link" {
  description = "Backend service self link"
  value       = google_compute_backend_service.dark_store_backend.self_link
}

output "health_check_id" {
  description = "Health check ID"
  value       = google_compute_health_check.dark_store_health_check.id
}
