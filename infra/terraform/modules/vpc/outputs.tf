output "network_id" {
  value = google_compute_network.main.id
}

output "subnetwork_id" {
  value = google_compute_subnetwork.main.id
}

output "pods_range_name" {
  value = "pods"
}

output "services_range_name" {
  value = "services"
}
