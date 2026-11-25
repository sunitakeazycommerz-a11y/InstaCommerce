variable "reconciliation_db_host" {
  description = "PostgreSQL hostname for reconciliation database"
  type        = string
  default     = "reconciliation-db.instacommerce.svc.cluster.local"
}

variable "reconciliation_db_port" {
  description = "PostgreSQL port for reconciliation database"
  type        = number
  default     = 5432
}

variable "reconciliation_db_user" {
  description = "PostgreSQL user for Debezium connector"
  type        = string
  sensitive   = true
}

variable "reconciliation_db_password" {
  description = "PostgreSQL password for Debezium connector"
  type        = string
  sensitive   = true
}

variable "kafka_brokers" {
  description = "List of Kafka broker addresses"
  type        = list(string)
  default     = ["kafka.default.svc.cluster.local:9092"]
}

variable "cdc_replication_factor" {
  description = "Replication factor for CDC topics"
  type        = number
  default     = 2
}

variable "cdc_retention_hours" {
  description = "Retention period for CDC topics in hours"
  type        = number
  default     = 24
}
