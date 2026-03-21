# SF Canary VPC Firewall Rules
# Phase 2: Network security policies for dark-store canary

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
  network_name    = var.network_name
  region          = "us-west1"
}

# Allow internal traffic (VPC-internal)
resource "google_compute_firewall" "allow_internal" {
  name        = "sf-canary-allow-internal"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1000
  description = "Allow internal traffic within VPC"

  allow {
    protocol = "tcp"
    ports    = ["0-65535"]
  }

  allow {
    protocol = "udp"
    ports    = ["0-65535"]
  }

  allow {
    protocol = "icmp"
  }

  source_ranges = ["10.0.0.0/8"]
  target_tags   = ["dark-store", "internal"]
}

# Allow SSH from bastion/admin
resource "google_compute_firewall" "allow_ssh_admin" {
  name        = "sf-canary-allow-ssh"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1001
  description = "Allow SSH from admin networks only"

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = var.admin_cidrs
  target_tags   = ["bastion", "admin"]
}

# Allow HTTP to Kubernetes nodes (for health checks and ingress)
resource "google_compute_firewall" "allow_http_to_k8s" {
  name        = "sf-canary-allow-http-k8s"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1002
  description = "Allow HTTP to Kubernetes nodes"

  allow {
    protocol = "tcp"
    ports    = ["80", "8080", "8443"]
  }

  source_ranges = ["10.0.0.0/8"]
  target_tags   = ["dark-store", "k8s-nodes"]
}

# Allow Prometheus metrics scraping
resource "google_compute_firewall" "allow_prometheus_scrape" {
  name        = "sf-canary-allow-prometheus"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1003
  description = "Allow Prometheus metrics scraping"

  allow {
    protocol = "tcp"
    ports    = ["9090", "9091", "9092"]
  }

  source_ranges = ["10.0.0.0/8"]
  target_tags   = ["dark-store", "monitoring"]
}

# Allow GKE API server access (control plane to nodes)
resource "google_compute_firewall" "allow_gke_api" {
  name        = "sf-canary-allow-gke-api"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1004
  description = "Allow GKE API server communication"

  allow {
    protocol = "tcp"
    ports    = ["443", "10250"]
  }

  source_ranges = [
    "35.199.0.0/16",    # Google Cloud GKE
    "10.0.0.0/8",       # VPC internal
  ]
  target_tags = ["k8s-nodes"]
}

# Allow Cloud SQL Private IP connectivity
resource "google_compute_firewall" "allow_cloudsql" {
  name        = "sf-canary-allow-cloudsql"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1005
  description = "Allow private connectivity to Cloud SQL"

  allow {
    protocol = "tcp"
    ports    = ["5432"]
  }

  source_ranges = ["10.0.0.0/8"]
  target_tags   = ["cloudsql"]
}

# Allow Redis (Memorystore) connectivity
resource "google_compute_firewall" "allow_redis" {
  name        = "sf-canary-allow-redis"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1006
  description = "Allow private connectivity to Redis"

  allow {
    protocol = "tcp"
    ports    = ["6379"]
  }

  source_ranges = ["10.0.0.0/8"]
  target_tags   = ["redis"]
}

# Allow Kafka connectivity (if Kafka is used)
resource "google_compute_firewall" "allow_kafka" {
  name        = "sf-canary-allow-kafka"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1007
  description = "Allow Kafka connectivity"

  allow {
    protocol = "tcp"
    ports    = ["9092", "9093", "9094"]
  }

  source_ranges = ["10.0.0.0/8"]
  target_tags   = ["kafka"]
}

# Allow Google Cloud Health Checks
resource "google_compute_firewall" "allow_health_checks" {
  name        = "sf-canary-allow-health-checks"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 1008
  description = "Allow Google Cloud health checks"

  allow {
    protocol = "tcp"
    ports    = ["8080", "8443"]
  }

  source_ranges = [
    "35.191.0.0/16",    # Google Cloud health checks
    "130.211.0.0/22"
  ]
  target_tags = ["health-checks"]
}

# Allow egress to Google APIs
resource "google_compute_firewall" "allow_google_apis_egress" {
  name        = "sf-canary-allow-google-apis-egress"
  network     = local.network_name
  project     = local.project_id
  direction   = "EGRESS"
  priority    = 1000
  description = "Allow outbound traffic to Google Cloud APIs"

  allow {
    protocol = "tcp"
    ports    = ["443"]
  }

  destination_ranges = [
    "0.0.0.0/0"
  ]
  target_tags = ["dark-store", "k8s-nodes"]
}

# Deny all other traffic (default deny)
resource "google_compute_firewall" "deny_all_default" {
  name        = "sf-canary-deny-all-default"
  network     = local.network_name
  project     = local.project_id
  direction   = "INGRESS"
  priority    = 65534
  description = "Default deny all incoming traffic"

  deny {
    protocol = "all"
  }

  source_ranges = ["0.0.0.0/0"]
}

# Deny all egress (default deny)
resource "google_compute_firewall" "deny_all_egress" {
  name        = "sf-canary-deny-all-egress-default"
  network     = local.network_name
  project     = local.project_id
  direction   = "EGRESS"
  priority    = 65534
  description = "Default deny all outgoing traffic"

  deny {
    protocol = "all"
  }

  destination_ranges = ["0.0.0.0/0"]
}

# Allow DNS queries (egress)
resource "google_compute_firewall" "allow_dns_egress" {
  name        = "sf-canary-allow-dns-egress"
  network     = local.network_name
  project     = local.project_id
  direction   = "EGRESS"
  priority    = 999
  description = "Allow DNS queries"

  allow {
    protocol = "udp"
    ports    = ["53"]
  }

  destination_ranges = ["0.0.0.0/0"]
  target_tags        = ["dark-store", "k8s-nodes"]
}

# Outputs
output "firewall_rules" {
  description = "Firewall rules created"
  value = {
    allow_internal            = google_compute_firewall.allow_internal.name
    allow_ssh_admin           = google_compute_firewall.allow_ssh_admin.name
    allow_http_to_k8s         = google_compute_firewall.allow_http_to_k8s.name
    allow_prometheus_scrape   = google_compute_firewall.allow_prometheus_scrape.name
    allow_gke_api             = google_compute_firewall.allow_gke_api.name
    allow_cloudsql            = google_compute_firewall.allow_cloudsql.name
    allow_redis               = google_compute_firewall.allow_redis.name
    allow_kafka               = google_compute_firewall.allow_kafka.name
    allow_health_checks       = google_compute_firewall.allow_health_checks.name
    allow_google_apis_egress  = google_compute_firewall.allow_google_apis_egress.name
    deny_all_default          = google_compute_firewall.deny_all_default.name
    deny_all_egress           = google_compute_firewall.deny_all_egress.name
    allow_dns_egress          = google_compute_firewall.allow_dns_egress.name
  }
}
