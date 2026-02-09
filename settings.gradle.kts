pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "instacommerce"

include("contracts")
include("services:identity-service")
include("services:catalog-service")
include("services:inventory-service")
include("services:order-service")
include("services:payment-service")
include("services:fulfillment-service")
include("services:notification-service")
include("services:search-service")
include("services:pricing-service")
include("services:cart-service")
include("services:checkout-orchestrator-service")
include("services:warehouse-service")
include("services:rider-fleet-service")
include("services:routing-eta-service")
include("services:wallet-loyalty-service")
include("services:audit-trail-service")
include("services:fraud-detection-service")
include("services:config-feature-flag-service")
include("services:mobile-bff-service")
include("services:admin-gateway-service")
