package com.instacommerce.routing.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.routing.service.DeliveryService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentEventConsumer.class);

    private final DeliveryService deliveryService;
    private final ObjectMapper objectMapper;

    public FulfillmentEventConsumer(DeliveryService deliveryService, ObjectMapper objectMapper) {
        this.deliveryService = deliveryService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TopicNames.FULFILLMENT_EVENTS, groupId = "routing-eta-service")
    public void handleFulfillmentEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode payload = root.path("payload").isObject() ? root.path("payload") : root;
            String eventType = readText(root, "eventType", "event_type");
            if (eventType == null && payload != root) {
                eventType = readText(payload, "eventType", "event_type");
            }
            if (eventType == null) {
                return;
            }
            UUID orderId = parseUuid(payload, "orderId", "order_id");
            if (orderId == null) {
                orderId = parseUuid(root, "aggregateId", "aggregate_id");
            }
            if (orderId == null) {
                log.warn("Skipping fulfillment event {} missing orderId", eventType);
                return;
            }

            if ("OrderDispatched".equals(eventType) || "RiderHandoffCompleted".equals(eventType)) {
                UUID riderId = parseUuid(payload, "riderId", "rider_id");
                deliveryService.advanceOrderDispatched(orderId, riderId);
            } else if ("OrderDelivered".equals(eventType)) {
                deliveryService.advanceOrderDelivered(orderId);
            } else if ("DeliveryFailed".equals(eventType)
                || "PickTaskCancelled".equals(eventType)
                || "OrderCancelled".equals(eventType)) {
                deliveryService.markDeliveryFailed(orderId, readText(payload, "reason"));
            } else {
                log.debug("Ignoring fulfillment event type: {}", eventType);
            }
        } catch (Exception ex) {
            log.error("Failed to process fulfillment event: {}", message, ex);
            throw new RuntimeException("Failed to process fulfillment event", ex);
        }
    }

    private static String readText(JsonNode container, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = container.path(fieldName);
            if (!node.isMissingNode() && !node.isNull() && node.isValueNode()) {
                String value = node.asText().trim();
                if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static UUID parseUuid(JsonNode container, String... fieldNames) {
        String raw = readText(container, fieldNames);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
