resource "google_container_cluster" "primary" {
  name     = "instacommerce-${var.env}"
  location = var.region

  remove_default_node_pool = true
  initial_node_count       = 1

  networking_mode = "VPC_NATIVE"
  network         = var.network_id
  subnetwork      = var.subnetwork_id

  ip_allocation_policy {
    cluster_secondary_range_name  = var.pods_range_name
    services_secondary_range_name = var.services_range_name
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  release_channel {
    channel = "REGULAR"
  }
}

resource "google_container_node_pool" "general" {
  name       = "general-pool"
  cluster    = google_container_cluster.primary.id
  node_count = 2

  autoscaling {
    min_node_count = 2
    max_node_count = 6
  }

  node_config {
    machine_type = "e2-standard-4"
    disk_size_gb = 50
    disk_type    = "pd-ssd"

    oauth_scopes = [
      "https://www.googleapis.com/auth/cloud-platform"
    ]

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    labels = {
      env = var.env
    }
  }
}
