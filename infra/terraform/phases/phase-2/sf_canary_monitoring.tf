# SF Canary Monitoring Infrastructure
# Phase 2: Prometheus scrape configs and monitoring setup

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
  project_id   = var.project_id
  region       = "us-west1"
  cluster_name = var.cluster_name
}

# Monitoring Workspace (Google Managed Prometheus)
resource "google_monitoring_monitored_resource" "dark_store_gke_cluster" {
  type   = "k8s_cluster"
  project = local.project_id

  labels = {
    project_id       = local.project_id
    location         = local.region
    cluster_name     = local.cluster_name
  }
}

# Alert Policy: High error rate
resource "google_monitoring_alert_policy" "dark_store_high_error_rate" {
  display_name = "Dark Store - High Error Rate"
  combiner     = "OR"
  project      = local.project_id

  conditions {
    display_name = "Error rate > 0.1%"

    condition_threshold {
      filter          = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"http.server.request.duration\" AND metric.labels.http_status_code=~\"5..\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 0.001
      trigger {
        count = 1
      }
    }
  }

  notification_channels = var.notification_channels

  alert_strategy {
    auto_close = "1800s"
  }

  documentation {
    content   = "Dark store error rate exceeded 0.1%. Check pod logs and metrics."
    mime_type = "text/markdown"
  }
}

# Alert Policy: High latency
resource "google_monitoring_alert_policy" "dark_store_high_latency" {
  display_name = "Dark Store - High Latency (p99 > 2s)"
  combiner     = "OR"
  project      = local.project_id

  conditions {
    display_name = "p99 latency > 2000ms"

    condition_threshold {
      filter          = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"http.server.request.duration\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 2000
      trigger {
        count = 1
      }
    }
  }

  notification_channels = var.notification_channels

  alert_strategy {
    auto_close = "1800s"
  }

  documentation {
    content   = "Dark store p99 latency exceeded 2000ms. Check resource utilization and database performance."
    mime_type = "text/markdown"
  }
}

# Alert Policy: Pod restart rate
resource "google_monitoring_alert_policy" "dark_store_pod_restarts" {
  display_name = "Dark Store - Excessive Pod Restarts"
  combiner     = "OR"
  project      = local.project_id

  conditions {
    display_name = "Pod restart rate"

    condition_threshold {
      filter          = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"kubernetes.io/pod/restart_count\""
      duration        = "600s"
      comparison      = "COMPARISON_GT"
      threshold_value = 5
      trigger {
        count = 1
      }
    }
  }

  notification_channels = var.notification_channels
}

# Alert Policy: Memory pressure
resource "google_monitoring_alert_policy" "dark_store_memory_pressure" {
  display_name = "Dark Store - Memory Pressure"
  combiner     = "OR"
  project      = local.project_id

  conditions {
    display_name = "Memory usage > 80%"

    condition_threshold {
      filter          = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"kubernetes.io/container/memory/used_bytes\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 3355443200  # 80% of 4Gi
      trigger {
        count = 1
      }
    }
  }

  notification_channels = var.notification_channels
}

# Alert Policy: CPU throttling
resource "google_monitoring_alert_policy" "dark_store_cpu_throttling" {
  display_name = "Dark Store - CPU Throttling Detected"
  combiner     = "OR"
  project      = local.project_id

  conditions {
    display_name = "CPU throttled duration"

    condition_threshold {
      filter          = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"kubernetes.io/container/cpu/throttled_periods\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 100
      trigger {
        count = 1
      }
    }
  }

  notification_channels = var.notification_channels
}

# Custom Monitoring Dashboard
resource "google_monitoring_dashboard" "dark_store_dashboard" {
  dashboard_json = jsonencode({
    displayName = "SF Dark Store Canary - Real-time Monitoring"
    mosaicLayout = {
      columns = 12
      tiles = [
        {
          width  = 6
          height = 4
          widget = {
            title = "Request Rate (per second)"
            xyChart = {
              dataSets = [{
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter          = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"http.server.request.count\""
                    aggregation = {
                      alignmentPeriod  = "60s"
                      perSeriesAligner = "ALIGN_RATE"
                    }
                  }
                }
              }]
            }
          }
        }
        {
          xPos   = 6
          width  = 6
          height = 4
          widget = {
            title = "Error Rate (%)"
            xyChart = {
              dataSets = [{
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"http.server.request.duration\" AND metric.labels.http_status_code=~\"5..\""
                    aggregation = {
                      alignmentPeriod  = "60s"
                      perSeriesAligner = "ALIGN_RATE"
                    }
                  }
                }
              }]
            }
          }
        }
        {
          yPos   = 4
          width  = 6
          height = 4
          widget = {
            title = "Latency p50/p95/p99 (ms)"
            xyChart = {
              dataSets = [
                {
                  timeSeriesQuery = {
                    timeSeriesFilter = {
                      filter = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"http.server.request.duration\""
                      aggregation = {
                        alignmentPeriod    = "60s"
                        perSeriesAligner   = "ALIGN_PERCENTILE_50"
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        {
          xPos   = 6
          yPos   = 4
          width  = 6
          height = 4
          widget = {
            title = "Memory Usage (Gi)"
            xyChart = {
              dataSets = [{
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"kubernetes.io/container/memory/used_bytes\""
                    aggregation = {
                      alignmentPeriod  = "60s"
                      perSeriesAligner = "ALIGN_MEAN"
                    }
                  }
                }
              }]
            }
          }
        }
        {
          yPos   = 8
          width  = 6
          height = 4
          widget = {
            title = "CPU Usage (%)"
            xyChart = {
              dataSets = [{
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"kubernetes.io/container/cpu/core_usage_time\""
                    aggregation = {
                      alignmentPeriod  = "60s"
                      perSeriesAligner = "ALIGN_RATE"
                    }
                  }
                }
              }]
            }
          }
        }
        {
          xPos   = 6
          yPos   = 8
          width  = 6
          height = 4
          widget = {
            title = "Pod Restart Count"
            xyChart = {
              dataSets = [{
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"kubernetes.io/pod/restart_count\""
                    aggregation = {
                      alignmentPeriod  = "60s"
                      perSeriesAligner = "ALIGN_MAX"
                    }
                  }
                }
              }]
            }
          }
        }
        {
          yPos   = 12
          width  = 12
          height = 3
          widget = {
            title = "Network I/O (bytes/sec)"
            xyChart = {
              dataSets = [
                {
                  timeSeriesQuery = {
                    timeSeriesFilter = {
                      filter = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"kubernetes.io/pod/network/received_bytes_count\""
                      aggregation = {
                        alignmentPeriod  = "60s"
                        perSeriesAligner = "ALIGN_RATE"
                      }
                    }
                  }
                }
              ]
            }
          }
        }
      ]
    }
  })

  project = local.project_id
}

# Uptime Check (synthetic monitoring)
resource "google_monitoring_uptime_check_config" "dark_store_health_check" {
  display_name = "Dark Store Health Check"
  timeout      = "5s"
  period       = "60s"
  project      = local.project_id

  http_check {
    path           = "/actuator/health"
    port           = 8080
    request_method = "GET"
    use_ssl        = false
  }

  monitored_resource {
    type = "uptime-url"
    labels = {
      host = var.dark_store_endpoint
    }
  }

  selected_regions = ["USA", "EUROPE", "ASIA_PACIFIC"]
}

# Service Level Objective (SLO)
resource "google_monitoring_slo" "dark_store_availability" {
  display_name        = "Dark Store - 99.5% Availability SLO"
  goal                = 0.995
  rolling_period_days = 30
  project             = local.project_id

  service_level_indicator {
    request_based_sli {
      good_service_filter = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"http.server.request.duration\" AND metric.labels.http_status_code!~\"5..\""
      total_service_filter = "resource.type=\"k8s_pod\" AND resource.labels.namespace_name=\"sf-canary\" AND metric.type=\"http.server.request.duration\""
    }
  }
}

# Output
output "dashboard_url" {
  description = "Monitoring dashboard URL"
  value       = "https://console.cloud.google.com/monitoring/dashboards/custom/${google_monitoring_dashboard.dark_store_dashboard.id}?project=${local.project_id}"
}

output "uptime_check_id" {
  description = "Uptime check ID for synthetic monitoring"
  value       = google_monitoring_uptime_check_config.dark_store_health_check.id
}

output "slo_id" {
  description = "Service Level Objective ID"
  value       = google_monitoring_slo.dark_store_availability.id
}
