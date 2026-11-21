package com.instacommerce.contracts.schemas;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

final class DomainPayloadFixtures {

    static final String UUID_A = "11111111-1111-1111-1111-111111111111";
    static final String UUID_B = "22222222-2222-2222-2222-222222222222";
    static final String UUID_C = "33333333-3333-3333-3333-333333333333";
    static final String NOW_ISO = "2026-11-19T12:00:00Z";

    private DomainPayloadFixtures() {
    }

    static Stream<Arguments> validPayloads() {
        return Stream.of(
                Arguments.of(
                        "orders/OrderPlaced.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "userId", UUID_B,
                                "storeId", UUID_C,
                                "items", List.of(Map.of(
                                        "productId", UUID_C,
                                        "productName", "Organic Milk 1L",
                                        "quantity", 2,
                                        "unitPriceCents", 399,
                                        "lineTotalCents", 798,
                                        "coldChain", true,
                                        "fragile", false,
                                        "storageTemperature", "CHILLED",
                                        "tareWeightGrams", 1050)),
                                "totalCents", 798,
                                "currency", "INR",
                                "placedAt", NOW_ISO)),
                Arguments.of(
                        "orders/OrderCancelled.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "userId", UUID_B,
                                "paymentId", UUID_C,
                                "totalCents", 1500,
                                "walletAmountCents", 500,
                                "walletRefundCents", 500,
                                "refundedCents", 1500,
                                "currency", "INR",
                                "reason", "Customer requested cancellation",
                                "reasonCode", "CUSTOMER_REQUEST")),
                Arguments.of(
                        "orders/OrderFailed.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "userId", UUID_B,
                                "reason", "Payment authorization expired",
                                "walletAmountCents", 250,
                                "walletRefundCents", 250,
                                "refundedCents", 250)),
                Arguments.of(
                        "orders/OrderRefunded.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "userId", UUID_B,
                                "paymentId", UUID_C,
                                "totalCents", 2000,
                                "currency", "INR",
                                "reason", "Order refunded",
                                "walletAmountCents", 600,
                                "walletRefundCents", 600,
                                "refundedCents", 2000)),
                Arguments.of(
                        "orders/OrderPartiallyRefunded.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "paymentId", UUID_B,
                                "refundCents", 500,
                                "walletAmountCents", 200,
                                "walletRefundCents", 200,
                                "pspRefundCents", 300,
                                "refundedCents", 500,
                                "priceDeltaCents", -500,
                                "reason", "ORDER_MODIFIED_PARTIAL_REFUND")),
                Arguments.of(
                        "fulfillment/OrderPacked.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "userId", UUID_B,
                                "storeId", UUID_C,
                                "packedAt", NOW_ISO,
                                "orderWeightGrams", 2400,
                                "coldChain", true,
                                "fragile", false)),
                Arguments.of(
                        "fulfillment/ItemSubstituted.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "storeId", UUID_B,
                                "originalProductId", UUID_C,
                                "substituteProductId", UUID_A,
                                "substitutedQuantity", 2)),
                Arguments.of(
                        "fulfillment/PickTaskCancelled.v1.json",
                        Map.of("orderId", UUID_A, "reason", "Customer requested cancellation")),
                Arguments.of(
                        "fulfillment/RiderHandoffCompleted.v1.json",
                        Map.of("orderId", UUID_A, "riderId", UUID_B, "handoffAt", NOW_ISO)),
                Arguments.of(
                        "fulfillment/ProofOfDeliveryUploaded.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "photoUrl", "https://cdn.instacommerce.internal/pod/ord-1.webp",
                                "lifecycleDays", 30,
                                "format", "WEBP")),
                Arguments.of(
                        "fulfillment/DeliveryFailed.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "deliveryId", UUID_B,
                                "riderId", UUID_C,
                                "reason", "Customer unreachable after 3 delivery attempts",
                                "failureCode", "CUSTOMER_UNREACHABLE",
                                "notes", "Left SMS and voicemail notice",
                                "failedAt", NOW_ISO)),
                Arguments.of(
                        "inventory/StockAdjusted.v1.json",
                        Map.of(
                                "productId", UUID_A,
                                "storeId", UUID_B,
                                "delta", -3,
                                "reason", "DAMAGED",
                                "newOnHand", 17,
                                "adjustedAt", NOW_ISO)),
                Arguments.of(
                        "inventory/PerishableExpiringSoon.v1.json",
                        Map.of(
                                "store_id", UUID_A,
                                "product_id", UUID_B,
                                "batch_number", "BATCH-2026-11",
                                "quantity", 12,
                                "expiry_date", "2026-11-20",
                                "discount_percent", 50,
                                "pricing_rule", "AUTOMATED_PERISHABLE_MARKDOWN",
                                "category", "SAVE_FOOD_QUICK_SALE",
                                "featured_section", "Save Food / Quick Sale",
                                "timestamp", NOW_ISO)),
                Arguments.of(
                        "inventory/ReorderPointReached.v1.json",
                        Map.of(
                                "store_id", UUID_A,
                                "product_id", UUID_B,
                                "available_stock", 4,
                                "safety_stock_buffer", 10,
                                "reorder_threshold", 15,
                                "recommended_order_quantity", 20,
                                "lead_time_days", 2,
                                "timestamp", NOW_ISO)),
                Arguments.of(
                        "inventory/InboundAsnReconciled.v1.json",
                        Map.of(
                                "asn_number", "ASN-9001",
                                "purchase_order_id", "PO-442",
                                "vendor_id", "VENDOR-1",
                                "store_id", UUID_A,
                                "pallet_barcode", "PALLET-1001",
                                "status", "RECONCILED",
                                "line_items_count", 24,
                                "received_by", "picker-01",
                                "received_at", NOW_ISO)),
                Arguments.of(
                        "inventory/StockDriftDetected.v1.json",
                        Map.of(
                                "productId", UUID_A,
                                "storeId", UUID_B,
                                "systemStock", 25,
                                "physicalStock", 21,
                                "driftQuantity", -4,
                                "onHand", 25,
                                "reserved", 2,
                                "detectedAt", NOW_ISO)),
                Arguments.of(
                        "inventory/DarkStoreCycleCountTaskCreated.v1.json",
                        Map.of(
                                "taskId", UUID_A,
                                "productId", UUID_B,
                                "storeId", UUID_C,
                                "missingQuantity", 3,
                                "pickerId", "picker-07",
                                "aisleLocation", "A4-S2",
                                "urgency", "CRITICAL",
                                "createdAt", NOW_ISO)),
                Arguments.of(
                        "inventory/StockReservedShortfallDetected.v1.json",
                        Map.of(
                                "productId", UUID_A,
                                "storeId", UUID_B,
                                "verifiedPhysicalQty", 1,
                                "appliedOnHand", 3,
                                "reserved", 3,
                                "shortfallBelowReserved", 2,
                                "unfrozenAt", NOW_ISO)),
                Arguments.of(
                        "inventory/CycleCountAuditCompleted.v1.json",
                        Map.of(
                                "productId", UUID_A,
                                "storeId", UUID_B,
                                "verifiedPhysicalQty", 15,
                                "appliedOnHand", 15,
                                "reserved", 3,
                                "shortfallBelowReserved", 0,
                                "unfrozenAt", NOW_ISO)),
                Arguments.of(
                        "rider/RiderAssignmentCancelled.v1.json",
                        Map.of("riderId", UUID_A, "orderId", UUID_B, "reason", "Order cancelled")),
                Arguments.of(
                        "rider/RiderGeofenceTransition.v1.json",
                        Map.of(
                                "rider_id", "rider-42",
                                "zone_id", "zone-sf-1",
                                "event_type", "ENTER",
                                "h3_index", "8928308280fffff",
                                "timestamp", NOW_ISO,
                                "lat", 37.7749,
                                "lng", -122.4194)),
                Arguments.of(
                        "delivery/DeliveryCreated.v1.json",
                        Map.of(
                                "id", UUID_A,
                                "orderId", UUID_B,
                                "storeId", UUID_C,
                                "pickupLat", 37.77,
                                "pickupLng", -122.41,
                                "dropoffLat", 37.78,
                                "dropoffLng", -122.42,
                                "status", "PENDING",
                                "estimatedMinutes", 12,
                                "distanceKm", 2.4)),
                Arguments.of(
                        "delivery/EtaUpdated.v1.json",
                        Map.of(
                                "deliveryId", UUID_A,
                                "orderId", UUID_B,
                                "riderId", UUID_C,
                                "estimatedMinutes", 11,
                                "etaLowMinutes", 9,
                                "etaHighMinutes", 14,
                                "distanceKm", 1.8,
                                "detourDetected", false,
                                "monsoonDelayActive", false,
                                "calculatedAt", NOW_ISO)),
                Arguments.of(
                        "wallet/TierUpgraded.v1.json",
                        Map.of(
                                "accountId", UUID_A,
                                "userId", UUID_B,
                                "previousTier", "SILVER",
                                "tier", "GOLD",
                                "points", 500,
                                "totalBalance", 500,
                                "lifetimePoints", 2500)),
                Arguments.of(
                        "warehouse/BinAssigned.v1.json",
                        Map.ofEntries(
                                Map.entry("id", UUID_A),
                                Map.entry("storeId", UUID_B),
                                Map.entry("aisle", "A1"),
                                Map.entry("rack", "R2"),
                                Map.entry("shelf", "S3"),
                                Map.entry("binNumber", "B4"),
                                Map.entry("sku", "MILK-1L"),
                                Map.entry("maxVolumeCm3", 10000),
                                Map.entry("currentVolumeCm3", 2500),
                                Map.entry("maxWeightGrams", 8000),
                                Map.entry("currentWeightGrams", 1200),
                                Map.entry("occupied", true))),
                Arguments.of(
                        "warehouse/ColdChainBreachAlert.v1.json",
                        Map.of(
                                "storeId", UUID_A,
                                "bayId", "BAY-COLD-2",
                                "sensorId", "SENS-91",
                                "storageType", "CHILLED",
                                "temperatureCelsius", 9.4,
                                "timestamp", NOW_ISO)),
                Arguments.of(
                        "warehouse/StoreCapacityPaused.v1.json",
                        Map.of("storeId", UUID_A, "currentPending", 100, "maxConcurrent", 100)),
                Arguments.of(
                        "warehouse/StoreCapacityThrottled.v1.json",
                        Map.of(
                                "storeId", UUID_A,
                                "currentPending", 90,
                                "maxConcurrent", 100,
                                "etaExtensionMinutes", 15)),
                Arguments.of(
                        "warehouse/StoreHoursUpdated.v1.json",
                        Map.of(
                                "storeId", UUID_A,
                                "dayOfWeek", 1,
                                "opensAt", "06:00",
                                "closesAt", "23:59",
                                "holiday", false)),
                Arguments.of(
                        "cart/CartMerged.v1.json",
                        Map.of("targetUserId", UUID_A, "guestUserId", UUID_B)));
    }
}
