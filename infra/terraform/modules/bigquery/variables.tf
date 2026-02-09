variable "project_id" {
  type = string
}

variable "env" {
  type = string
}

variable "location" {
  type = string
}

variable "datasets" {
  type    = list(string)
  default = ["analytics", "ml"]
}
