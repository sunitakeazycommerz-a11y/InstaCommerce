# BigQuery BI Engine reservation for sub-second executive dashboard queries (Issue #669)
# Reserves 10GB in-memory acceleration targeting mart_daily_sales and mart_realtime_kpis.

resource "google_bigquery_bi_reservation" "bi_reservation" {
  project  = var.project_id
  location = var.location
  size     = 10737418240 # 10 GB in bytes

  preferred_tables {
    project_id = var.project_id
    dataset_id = "marts"
    table_id   = "mart_daily_sales"
  }

  preferred_tables {
    project_id = var.project_id
    dataset_id = "marts"
    table_id   = "mart_realtime_kpis"
  }
}
