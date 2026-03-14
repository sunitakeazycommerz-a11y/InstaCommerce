package com.instacommerce.contracts.topics;

/**
 * Canonical Kafka topic names for the InstaCommerce platform.
 *
 * <p>These constants are the single source of truth for topic names across all
 * Java microservices. The canonical names match what the outbox-relay-service
 * actually publishes (e.g. {@code orders.events}, {@code payments.events}).
 */
public final class TopicNames {

    private TopicNames() {
        // utility class
    }

    // ── Domain event topics ─────────────────────────────────────────────

    public static final String IDENTITY_EVENTS        = "identity.events";
    public static final String CATALOG_EVENTS         = "catalog.events";
    public static final String ORDERS_EVENTS          = "orders.events";
    public static final String PAYMENTS_EVENTS        = "payments.events";
    public static final String INVENTORY_EVENTS       = "inventory.events";
    public static final String FULFILLMENT_EVENTS     = "fulfillment.events";
    public static final String RIDER_EVENTS           = "rider.events";
    public static final String NOTIFICATION_EVENTS    = "notification.events";
    public static final String SEARCH_EVENTS          = "search.events";
    public static final String PRICING_EVENTS         = "pricing.events";
    public static final String PROMOTION_EVENTS       = "promotion.events";
    public static final String CUSTOMER_SUPPORT_EVENTS = "customer-support.events";
    public static final String RETURNS_EVENTS         = "returns.events";
    public static final String WAREHOUSE_EVENTS       = "warehouse.events";
    public static final String AI_ORCHESTRATOR_EVENTS = "ai-orchestrator.events";
    public static final String RECONCILIATION_EVENTS  = "reconciliation.events";
    public static final String WALLET_EVENTS          = "wallet.events";
    public static final String FRAUD_EVENTS           = "fraud.events";

    // ── Non-domain topics ───────────────────────────────────────────────

    public static final String RIDER_LOCATION_UPDATES = "rider.location.updates";
    public static final String PAYMENT_WEBHOOKS       = "payment.webhooks";

    // ── Dead-letter topics ──────────────────────────────────────────────

    public static final String DLT_SUFFIX = ".DLT";

    public static final String IDENTITY_DLT           = IDENTITY_EVENTS + DLT_SUFFIX;
    public static final String CATALOG_DLT            = CATALOG_EVENTS + DLT_SUFFIX;
    public static final String ORDERS_DLT             = ORDERS_EVENTS + DLT_SUFFIX;
    public static final String PAYMENTS_DLT           = PAYMENTS_EVENTS + DLT_SUFFIX;
    public static final String INVENTORY_DLT          = INVENTORY_EVENTS + DLT_SUFFIX;
    public static final String FULFILLMENT_DLT        = FULFILLMENT_EVENTS + DLT_SUFFIX;
    public static final String RIDER_DLT              = RIDER_EVENTS + DLT_SUFFIX;
    public static final String NOTIFICATION_DLT       = NOTIFICATION_EVENTS + DLT_SUFFIX;
    public static final String SEARCH_DLT             = SEARCH_EVENTS + DLT_SUFFIX;
    public static final String PRICING_DLT            = PRICING_EVENTS + DLT_SUFFIX;
    public static final String PROMOTION_DLT          = PROMOTION_EVENTS + DLT_SUFFIX;
    public static final String CUSTOMER_SUPPORT_DLT   = CUSTOMER_SUPPORT_EVENTS + DLT_SUFFIX;
    public static final String RETURNS_DLT            = RETURNS_EVENTS + DLT_SUFFIX;
    public static final String WAREHOUSE_DLT          = WAREHOUSE_EVENTS + DLT_SUFFIX;
    public static final String AI_ORCHESTRATOR_DLT    = AI_ORCHESTRATOR_EVENTS + DLT_SUFFIX;
    public static final String RECONCILIATION_DLT     = RECONCILIATION_EVENTS + DLT_SUFFIX;
    public static final String WALLET_DLT             = WALLET_EVENTS + DLT_SUFFIX;
    public static final String FRAUD_DLT              = FRAUD_EVENTS + DLT_SUFFIX;
}
