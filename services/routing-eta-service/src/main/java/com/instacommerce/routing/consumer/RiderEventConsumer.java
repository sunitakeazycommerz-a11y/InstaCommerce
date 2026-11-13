package com.instacommerce.routing.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.routing.service.DeliveryService;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RiderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RiderEventConsumer.class);

    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    public RiderEventConsumer(DeliveryService deliveryService, ObjectMapper objectMapper) {
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TopicNames.RIDER_EVENTS, groupId = "routing-eta-service")
    public void handleRiderEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode payload = root.path("payload").isObject() ? root.path("payload") : root;
            String eventType = readText(root, "eventType", "event_type");
            if (eventType == null && payload != root) {
                eventType = readText(payload, "eventType", "event_type");
            }

            if ("RiderAssigned".equals(eventType)) {
                handleRiderAssigned(payload);
            } else {
                log.debug("Ignoring rider event type: {}", eventType);
            }
        } catch (Exception ex) {
            log.error("Failed to process rider event: {}", message, ex);
            throw new RuntimeException("Failed to process rider event", ex);
        }
    }

    private void handleRiderAssigned(JsonNode payload) {
        UUID orderId = parseUuid(payload, "orderId", "order_id");
        UUID riderId = parseUuid(payload, "riderId", "rider_id");
        if (orderId == null || riderId == null) {
            log.warn("Skipping RiderAssigned event with missing or invalid orderId/riderId: {}", payload);
            return;
        }

        UUID storeId = parseUuid(payload, "storeId", "store_id");
        BigDecimal pickupLat = parseCoordinate(payload, "pickupLat", "pickup_lat");
        BigDecimal pickupLng = parseCoordinate(payload, "pickupLng", "pickup_lng");
        BigDecimal dropoffLat = parseCoordinate(payload, "dropoffLat", "dropoff_lat");
        BigDecimal dropoffLng = parseCoordinate(payload, "dropoffLng", "dropoff_lng");

        if (pickupLat == null || pickupLng == null || dropoffLat == null || dropoffLng == null) {
            log.warn(
                "Skipping RiderAssigned event for order {} with incomplete coordinates (pickupLat={}, pickupLng={}, dropoffLat={}, dropoffLng={})",
                orderId, pickupLat, pickupLng, dropoffLat, dropoffLng);
            return;
        }

        deliveryService.createDelivery(orderId, riderId, storeId, pickupLat, pickupLng, dropoffLat, dropoffLng);
        log.info("Created delivery for order {} with rider {}", orderId, riderId);
    }

    private static JsonNode findNode(JsonNode container, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode candidate = container.path(fieldName);
            if (!candidate.isMissingNode() && !candidate.isNull()) {
                return candidate;
            }
        }
        return null;
    }

    private static String readText(JsonNode container, String... fieldNames) {
        JsonNode node = findNode(container, fieldNames);
        if (node == null || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    private static UUID parseUuid(JsonNode container, String... fieldNames) {
        String raw = readText(container, fieldNames);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid UUID for fields {}: {}", String.join("/", fieldNames), raw);
            return null;
        }
    }

    private static BigDecimal parseCoordinate(JsonNode container, String... fieldNames) {
        JsonNode node = findNode(container, fieldNames);
        if (node == null || !node.isValueNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String raw = node.asText().trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            log.warn("Invalid numeric coordinate for fields {}: {}", String.join("/", fieldNames), raw);
            return null;
        }
    }
}
