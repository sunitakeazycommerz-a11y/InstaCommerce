output "redis_instance" {
  value = google_redis_instance.main.name
}

output "redis_host" {
  value = google_redis_instance.main.host
}

output "redis_port" {
  value = google_redis_instance.main.port
}

output "redis_auth_string" {
  value     = google_redis_instance.main.auth_string
  sensitive = true
}
