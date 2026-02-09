variable "project_id" {
  type = string
}

variable "env" {
  type = string
}

variable "location" {
  type = string
}

variable "raw_retention_days" {
  type    = number
  default = 90
}
