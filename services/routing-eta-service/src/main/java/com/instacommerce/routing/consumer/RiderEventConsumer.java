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
            JsonNode event = objectMapper.readTree(message);
            String eventType = event.path("eventType").asText();

            if ("RiderAssigned".equals(eventType)) {
                handleRiderAssigned(event);
            } else {
                log.debug("Ignoring rider event type: {}", eventType);
            }
        } catch (Exception ex) {
            log.error("Failed to process rider event: {}", message, ex);
            throw new RuntimeException("Failed to process rider event", ex);
        }
    }

    private void handleRiderAssigned(JsonNode event) {
        UUID orderId = UUID.fromString(event.path("orderId").asText());
        UUID riderId = UUID.fromString(event.path("riderId").asText());
        UUID storeId = UUID.fromString(event.path("storeId").asText());
        BigDecimal pickupLat = new BigDecimal(event.path("pickupLat").asText());
        BigDecimal pickupLng = new BigDecimal(event.path("pickupLng").asText());
        BigDecimal dropoffLat = new BigDecimal(event.path("dropoffLat").asText());
        BigDecimal dropoffLng = new BigDecimal(event.path("dropoffLng").asText());

        deliveryService.createDelivery(orderId, riderId, storeId,
            pickupLat, pickupLng, dropoffLat, dropoffLng);

        log.info("Created delivery for order {} with rider {}", orderId, riderId);
    }
}
