/**
 * Kubernetes secret containing per-service authentication tokens for Wave 34 Track B.
 * One token per service enables least-privilege service-to-service authentication.
 */

terraform {
  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.23"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.5"
    }
  }
}

# Generate unique per-service tokens
resource "random_password" "identity_service_token" {
  length  = 64
  special = true
}

resource "random_password" "catalog_service_token" {
  length  = 64
  special = true
}

resource "random_password" "inventory_service_token" {
  length  = 64
  special = true
}

resource "random_password" "order_service_token" {
  length  = 64
  special = true
}

resource "random_password" "payment_service_token" {
  length  = 64
  special = true
}

resource "random_password" "fulfillment_service_token" {
  length  = 64
  special = true
}

resource "random_password" "notification_service_token" {
  length  = 64
  special = true
}

resource "random_password" "search_service_token" {
  length  = 64
  special = true
}

resource "random_password" "pricing_service_token" {
  length  = 64
  special = true
}

resource "random_password" "cart_service_token" {
  length  = 64
  special = true
}

resource "random_password" "checkout_orchestrator_service_token" {
  length  = 64
  special = true
}

resource "random_password" "warehouse_service_token" {
  length  = 64
  special = true
}

resource "random_password" "rider_fleet_service_token" {
  length  = 64
  special = true
}

resource "random_password" "routing_eta_service_token" {
  length  = 64
  special = true
}

resource "random_password" "wallet_loyalty_service_token" {
  length  = 64
  special = true
}

resource "random_password" "audit_trail_service_token" {
  length  = 64
  special = true
}

resource "random_password" "fraud_detection_service_token" {
  length  = 64
  special = true
}

resource "random_password" "config_feature_flag_service_token" {
  length  = 64
  special = true
}

resource "random_password" "mobile_bff_service_token" {
  length  = 64
  special = true
}

resource "random_password" "admin_gateway_service_token" {
  length  = 64
  special = true
}

resource "random_password" "cdc_consumer_service_token" {
  length  = 64
  special = true
}

resource "random_password" "dispatch_optimizer_service_token" {
  length  = 64
  special = true
}

resource "random_password" "location_ingestion_service_token" {
  length  = 64
  special = true
}

resource "random_password" "outbox_relay_service_token" {
  length  = 64
  special = true
}

resource "random_password" "payment_webhook_service_token" {
  length  = 64
  special = true
}

resource "random_password" "reconciliation_engine_token" {
  length  = 64
  special = true
}

resource "random_password" "stream_processor_service_token" {
  length  = 64
  special = true
}

resource "random_password" "ai_orchestrator_service_token" {
  length  = 64
  special = true
}

resource "random_password" "ai_inference_service_token" {
  length  = 64
  special = true
}

# Kubernetes Secret containing all per-service tokens
# Services read their own token from this secret via Helm values
resource "kubernetes_secret" "service_tokens" {
  metadata {
    name      = "service-tokens"
    namespace = var.namespace
    labels = {
      app     = "instacommerce"
      wave    = "34"
      track   = "b"
      purpose = "per-service-token-scoping"
    }
  }

  type = "Opaque"

  data = {
    # Java Services (20)
    "identity-service-token"                  = random_password.identity_service_token.result
    "catalog-service-token"                   = random_password.catalog_service_token.result
    "inventory-service-token"                 = random_password.inventory_service_token.result
    "order-service-token"                     = random_password.order_service_token.result
    "payment-service-token"                   = random_password.payment_service_token.result
    "fulfillment-service-token"               = random_password.fulfillment_service_token.result
    "notification-service-token"              = random_password.notification_service_token.result
    "search-service-token"                    = random_password.search_service_token.result
    "pricing-service-token"                   = random_password.pricing_service_token.result
    "cart-service-token"                      = random_password.cart_service_token.result
    "checkout-orchestrator-service-token"     = random_password.checkout_orchestrator_service_token.result
    "warehouse-service-token"                 = random_password.warehouse_service_token.result
    "rider-fleet-service-token"               = random_password.rider_fleet_service_token.result
    "routing-eta-service-token"               = random_password.routing_eta_service_token.result
    "wallet-loyalty-service-token"            = random_password.wallet_loyalty_service_token.result
    "audit-trail-service-token"               = random_password.audit_trail_service_token.result
    "fraud-detection-service-token"           = random_password.fraud_detection_service_token.result
    "config-feature-flag-service-token"       = random_password.config_feature_flag_service_token.result
    "mobile-bff-service-token"                = random_password.mobile_bff_service_token.result
    "admin-gateway-service-token"             = random_password.admin_gateway_service_token.result

    # Go Services (7)
    "cdc-consumer-service-token"              = random_password.cdc_consumer_service_token.result
    "dispatch-optimizer-service-token"        = random_password.dispatch_optimizer_service_token.result
    "location-ingestion-service-token"        = random_password.location_ingestion_service_token.result
    "outbox-relay-service-token"              = random_password.outbox_relay_service_token.result
    "payment-webhook-service-token"           = random_password.payment_webhook_service_token.result
    "reconciliation-engine-token"             = random_password.reconciliation_engine_token.result
    "stream-processor-service-token"          = random_password.stream_processor_service_token.result

    # Python Services (2)
    "ai-orchestrator-service-token"           = random_password.ai_orchestrator_service_token.result
    "ai-inference-service-token"              = random_password.ai_inference_service_token.result
  }

  depends_on = [
    random_password.identity_service_token,
    random_password.catalog_service_token,
    random_password.inventory_service_token,
    random_password.order_service_token,
    random_password.payment_service_token,
    random_password.fulfillment_service_token,
    random_password.notification_service_token,
    random_password.search_service_token,
    random_password.pricing_service_token,
    random_password.cart_service_token,
    random_password.checkout_orchestrator_service_token,
    random_password.warehouse_service_token,
    random_password.rider_fleet_service_token,
    random_password.routing_eta_service_token,
    random_password.wallet_loyalty_service_token,
    random_password.audit_trail_service_token,
    random_password.fraud_detection_service_token,
    random_password.config_feature_flag_service_token,
    random_password.mobile_bff_service_token,
    random_password.admin_gateway_service_token,
    random_password.cdc_consumer_service_token,
    random_password.dispatch_optimizer_service_token,
    random_password.location_ingestion_service_token,
    random_password.outbox_relay_service_token,
    random_password.payment_webhook_service_token,
    random_password.reconciliation_engine_token,
    random_password.stream_processor_service_token,
    random_password.ai_orchestrator_service_token,
    random_password.ai_inference_service_token
  ]
}

# Output token values for validation and documentation
output "service_tokens_secret_name" {
  description = "Name of the Kubernetes secret containing per-service tokens"
  value       = kubernetes_secret.service_tokens.metadata[0].name
}

output "service_tokens_count" {
  description = "Number of service tokens generated"
  value       = length(kubernetes_secret.service_tokens.data)
}
