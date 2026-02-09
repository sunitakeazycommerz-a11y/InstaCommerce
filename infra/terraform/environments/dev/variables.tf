variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "asia-south1"
}

variable "env" {
  type    = string
  default = "dev"
}

variable "subnet_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "pods_cidr" {
  type    = string
  default = "10.21.0.0/16"
}

variable "services_cidr" {
  type    = string
  default = "10.22.0.0/16"
}

variable "databases" {
  type = list(string)
  default = [
    "identity_db",
    "catalog_db",
    "inventory_db",
    "order_db",
    "payment_db",
    "fulfillment_db",
    "notification_db",
    "search_db",
    "pricing_db",
    "cart_db",
    "warehouse_db",
    "rider_db",
    "routing_db",
    "wallet_db",
    "audit_db",
    "fraud_db",
    "config_db"
  ]
}

variable "service_accounts" {
  type    = list(string)
  default = [
    "identity-service",
    "catalog-service",
    "inventory-service",
    "order-service",
    "payment-service",
    "fulfillment-service",
    "notification-service"
  ]
}

variable "secrets" {
  type    = list(string)
  default = [
    "sendgrid-api-key",
    "twilio-auth-token",
    "jwt-private-key",
    "jwt-public-key"
  ]
}
