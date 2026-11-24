package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.fulfillment.service.DeliveryService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RiderEventConsumer {
    private static final Logger logger = LoggerFactory.getLogger(RiderEventConsumer.class);

    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    public RiderEventConsumer(DeliveryService deliveryService, ObjectMapper objectMapper) {
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TopicNames.RIDER_EVENTS, groupId = "fulfillment-service")
    public void onRiderEvent(ConsumerRecord<String, String> record) throws Exception {
        JsonNode root = objectMapper.readTree(record.value());
        JsonNode payload = root.path("payload").isObject() ? root.path("payload") : root;
        String eventType = readText(root, "eventType", "event_type");
        if (eventType == null && payload != root) {
            eventType = readText(payload, "eventType", "event_type");
        }
        if (!"RiderAssigned".equals(eventType)) {
            logger.debug("Ignoring rider event type={}", eventType);
            return;
        }

        UUID orderId = parseUuid(payload, "orderId", "order_id");
        if (orderId == null && record.key() != null && !record.key().isBlank()) {
            orderId = parseUuidString(record.key().trim());
        }
        UUID riderId = parseUuid(payload, "riderId", "rider_id");
        if (orderId == null || riderId == null) {
            logger.warn("Skipping RiderAssigned event with missing/invalid orderId={} riderId={}", orderId, riderId);
            return;
        }

        UUID storeId = parseUuid(payload, "storeId", "store_id");
        String riderName = readText(payload, "riderName", "rider_name");
        Integer etaMinutes = parseInteger(payload, "etaMinutes", "eta_minutes", "estimatedMinutes", "estimated_minutes");
        deliveryService.syncRiderAssignment(orderId, riderId, storeId, riderName, etaMinutes);
        logger.info("Synced delivery projection from RiderAssigned event order={} rider={}", orderId, riderId);
    }

    private static JsonNode findNode(JsonNode container, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = container.path(fieldName);
            if (!node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private static String readText(JsonNode container, String... fieldNames) {
        JsonNode node = findNode(container, fieldNames);
        if (node == null || !node.isValueNode()) {
            return null;
        }
        String text = node.asText().trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private static UUID parseUuid(JsonNode container, String... fieldNames) {
        return parseUuidString(readText(container, fieldNames));
    }

    private static UUID parseUuidString(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Integer parseInteger(JsonNode container, String... fieldNames) {
        JsonNode node = findNode(container, fieldNames);
        if (node == null || !node.isValueNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        try {
            return Integer.valueOf(node.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
