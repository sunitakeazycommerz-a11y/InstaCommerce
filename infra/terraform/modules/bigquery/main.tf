resource "google_bigquery_dataset" "datasets" {
  for_each   = toset(var.datasets)
  project    = var.project_id
  dataset_id = each.key
  location   = var.location

  delete_contents_on_destroy = false

  labels = {
    env        = var.env
    component  = "data-platform"
    managed_by = "terraform"
  }
}
