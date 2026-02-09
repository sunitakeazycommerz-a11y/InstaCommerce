resource "google_compute_network" "main" {
  name                    = "instacommerce-${var.env}"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "main" {
  name          = "instacommerce-${var.env}-subnet"
  ip_cidr_range = var.subnet_cidr
  region        = var.region
  network       = google_compute_network.main.id

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = var.pods_cidr
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = var.services_cidr
  }
}

resource "google_compute_router" "main" {
  name    = "instacommerce-${var.env}-router"
  region  = var.region
  network = google_compute_network.main.name
}

resource "google_compute_router_nat" "main" {
  name                               = "instacommerce-${var.env}-nat"
  router                             = google_compute_router.main.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}
