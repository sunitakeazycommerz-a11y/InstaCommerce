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
include("services:checkout-orchestrator-service")
