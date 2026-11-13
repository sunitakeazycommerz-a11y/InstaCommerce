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

variable "enable_auth" {
  type    = bool
  default = false
}

variable "enable_tls" {
  type    = bool
  default = false
}
