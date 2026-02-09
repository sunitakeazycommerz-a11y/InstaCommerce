variable "project_id" {
  type = string
}

variable "env" {
  type = string
}

variable "location" {
  type = string
}

variable "service_account_name" {
  type    = string
  default = "dataflow-worker"
}

variable "project_roles" {
  type = list(string)
  default = [
    "roles/dataflow.worker",
    "roles/bigquery.dataEditor",
    "roles/bigquery.jobUser",
    "roles/storage.objectAdmin"
  ]
}
