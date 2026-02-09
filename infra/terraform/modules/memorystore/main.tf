resource "google_redis_instance" "main" {
  name           = "instacommerce-redis-${var.env}"
  tier           = "STANDARD_HA"
  memory_size_gb = 4
  region         = var.region
  authorized_network = var.network_id
}
