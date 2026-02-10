variable "project_id" {
  type = string
}

variable "env" {
  type = string
}

variable "location" {
  type = string
}

variable "region" {
  type = string
}

variable "network_id" {
  type = string
}

variable "dataset_id" {
  type    = string
  default = "feature_store"
}

variable "tables" {
  type = map(string)
  default = {
    user_features      = "User feature snapshots"
    product_features   = "Product feature snapshots"
    store_features     = "Store feature snapshots"
    realtime_features  = "Real-time feature signals"
    training_snapshots = "Point-in-time training snapshots"
  }
}

variable "redis_memory_size_gb" {
  type    = number
  default = 4
}

variable "redis_tier" {
  type    = string
  default = "STANDARD_HA"
}

variable "redis_name" {
  type    = string
  default = null
}
