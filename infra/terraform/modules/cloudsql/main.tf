resource "google_sql_database_instance" "main" {
  name             = "instacommerce-pg-${var.env}"
  database_version = "POSTGRES_15"
  region           = var.region

  settings {
    tier              = "db-custom-2-8192"
    availability_type = var.env == "prod" ? "REGIONAL" : "ZONAL"
    disk_size         = 20
    disk_type         = "PD_SSD"
    disk_autoresize   = true

    ip_configuration {
      ipv4_enabled    = false
      private_network = var.network_id
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "02:00"
    }

    database_flags {
      name  = "log_min_duration_statement"
      value = "1000"
    }
  }
}

resource "google_sql_database" "databases" {
  for_each = toset(var.databases)

  name     = each.key
  instance = google_sql_database_instance.main.name
}
