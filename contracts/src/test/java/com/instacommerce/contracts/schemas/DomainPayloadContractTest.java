package com.instacommerce.contracts.schemas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DomainPayloadContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UUID_A = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_B = "22222222-2222-2222-2222-222222222222";
    private static final String UUID_C = "33333333-3333-3333-3333-333333333333";
    private static final String NOW_ISO = "2026-11-19T12:00:00Z";

    private static Path resolveSchemasRoot() {
        Path path = Path.of("src/main/resources/schemas");
        if (!Files.exists(path)) {
            path = Path.of("contracts/src/main/resources/schemas");
        }
        assertThat(path).exists();
        return path;
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
                                        "lineTotalCents", 798)),
                                "totalCents", 798,
                                "currency", "USD",
                                "placedAt", NOW_ISO)),
                Arguments.of(
                        "orders/OrderPartiallyRefunded.v1.json",
                        Map.of(
                                "orderId", UUID_A,
                                "paymentId", UUID_B,
                                "refundCents", 500,
                                "priceDeltaCents", -500,
                                "reason", "ORDER_MODIFIED_PARTIAL_REFUND")),
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

    @ParameterizedTest(name = "{0} accepts valid producer payload")
    @MethodSource("validPayloads")
    void shouldAcceptValidDomainPayload(String schemaRelPath, Map<String, Object> payload) throws IOException {
        JsonNode schema = MAPPER.readTree(resolveSchemasRoot().resolve(schemaRelPath).toFile());
        JsonNode instance = MAPPER.valueToTree(payload);
        List<String> errors = validateObjectPayload(schema, instance);
        assertThat(errors)
                .withFailMessage("Payload failed schema %s: %s", schemaRelPath, errors)
                .isEmpty();
    }

    @Test
    @DisplayName("Schemas reject missing required fields, bad UUIDs, negative money, and invalid enums")
    void shouldRejectInvalidDomainPayloads() throws IOException {
        Path root = resolveSchemasRoot();

        JsonNode refundSchema = MAPPER.readTree(root.resolve("orders/OrderPartiallyRefunded.v1.json").toFile());
        JsonNode negativeRefund = MAPPER.valueToTree(Map.of(
                "orderId", "not-a-uuid",
                "refundCents", -10,
                "priceDeltaCents", -10,
                "reason", ""));
        assertThat(validateObjectPayload(refundSchema, negativeRefund))
                .anyMatch(e -> e.contains("orderId"))
                .anyMatch(e -> e.contains("refundCents"))
                .anyMatch(e -> e.contains("reason"));

        JsonNode podSchema = MAPPER.readTree(root.resolve("fulfillment/ProofOfDeliveryUploaded.v1.json").toFile());
        JsonNode badFormatPod = MAPPER.valueToTree(Map.of(
                "orderId", UUID_A,
                "photoUrl", "https://cdn.instacommerce.internal/pod/ord-1.jpg",
                "lifecycleDays", 30,
                "format", "JPEG"));
        assertThat(validateObjectPayload(podSchema, badFormatPod))
                .anyMatch(e -> e.contains("enum"));

        JsonNode geofenceSchema = MAPPER.readTree(root.resolve("rider/RiderGeofenceTransition.v1.json").toFile());
        JsonNode missingH3 = MAPPER.valueToTree(Map.of("rider_id", "rider-1", "zone_id", "zone-1"));
        assertThat(validateObjectPayload(geofenceSchema, missingH3))
                .anyMatch(e -> e.contains("missing required field"));
    }

    private List<String> validateObjectPayload(JsonNode schema, JsonNode instance) {
        List<String> errors = new ArrayList<>();
        if (!instance.isObject()) {
            errors.add("expected JSON object");
            return errors;
        }
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode req : required) {
                if (!instance.has(req.asText())) {
                    errors.add("missing required field: " + req.asText());
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String field = entry.getKey();
                if (!instance.has(field)) {
                    continue;
                }
                JsonNode propSchema = entry.getValue();
                JsonNode value = instance.get(field);
                validateField(field, propSchema, value, errors);
            }
        }
        return errors;
    }

    private void validateField(String field, JsonNode propSchema, JsonNode value, List<String> errors) {
        JsonNode typeNode = propSchema.get("type");
        if (value.isNull()) {
            if (!allowsType(typeNode, "null")) {
                errors.add(field + ": null not allowed");
            }
            return;
        }
        if (typeNode != null) {
            if (allowsType(typeNode, "string") && value.isTextual()) {
                validateStringConstraints(field, propSchema, value.asText(), errors);
                return;
            }
            if (allowsType(typeNode, "integer") && value.isIntegralNumber()) {
                validateNumericBounds(field, propSchema, value.doubleValue(), errors);
                return;
            }
            if (allowsType(typeNode, "number") && value.isNumber()) {
                validateNumericBounds(field, propSchema, value.doubleValue(), errors);
                return;
            }
            if (allowsType(typeNode, "boolean") && value.isBoolean()) {
                return;
            }
            if (allowsType(typeNode, "array") && value.isArray()) {
                JsonNode itemsSchema = propSchema.get("items");
                if (itemsSchema != null && itemsSchema.isObject() && "object".equals(itemsSchema.path("type").asText())) {
                    int idx = 0;
                    for (JsonNode elem : value) {
                        for (String itemErr : validateObjectPayload(itemsSchema, elem)) {
                            errors.add(field + "[" + idx + "]." + itemErr);
                        }
                        idx++;
                    }
                }
                return;
            }
            if (allowsType(typeNode, "object") && value.isObject()) {
                if (propSchema.has("properties") || propSchema.has("required")) {
                    for (String childErr : validateObjectPayload(propSchema, value)) {
                        errors.add(field + "." + childErr);
                    }
                }
                return;
            }
            errors.add(field + ": unexpected type " + value.getNodeType() + " for schema " + typeNode);
        }
    }

    private boolean allowsType(JsonNode typeNode, String target) {
        if (typeNode == null) {
            return true;
        }
        if (typeNode.isTextual()) {
            return target.equals(typeNode.asText());
        }
        if (typeNode.isArray()) {
            for (JsonNode option : typeNode) {
                if (target.equals(option.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validateStringConstraints(String field, JsonNode propSchema, String value, List<String> errors) {
        if (propSchema.has("minLength") && value.length() < propSchema.get("minLength").asInt()) {
            errors.add(field + ": length below minLength");
        }
        if (propSchema.has("enum")) {
            boolean matched = false;
            for (JsonNode allowed : propSchema.get("enum")) {
                if (value.equals(allowed.asText())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(field + ": value '" + value + "' not in enum");
            }
        }
        if (propSchema.has("format")) {
            String format = propSchema.get("format").asText();
            if ("uuid".equals(format)) {
                try {
                    UUID.fromString(value);
                } catch (IllegalArgumentException ex) {
                    errors.add(field + ": invalid uuid format '" + value + "'");
                }
            } else if ("date-time".equals(format)) {
                try {
                    Instant.parse(value);
                } catch (Exception ex) {
                    errors.add(field + ": invalid date-time format '" + value + "'");
                }
            }
        }
    }

    private void validateNumericBounds(String field, JsonNode propSchema, double val, List<String> errors) {
        if (propSchema.has("minimum") && val < propSchema.get("minimum").asDouble()) {
            errors.add(field + ": value " + val + " below minimum " + propSchema.get("minimum").asDouble());
        }
        if (propSchema.has("maximum") && val > propSchema.get("maximum").asDouble()) {
            errors.add(field + ": value " + val + " above maximum " + propSchema.get("maximum").asDouble());
        }
    }
}
