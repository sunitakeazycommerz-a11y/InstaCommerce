variable "project_id" {
  type = string
}

variable "env" {
  type = string
}

variable "region" {
  type = string
}

variable "network_id" {
  type = string
}

variable "databases" {
  type = list(string)
}
