package com.instacommerce.routing.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.routing.dto.request.LocationUpdateRequest;
import com.instacommerce.routing.service.TrackingService;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LocationUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(LocationUpdateConsumer.class);

    private final TrackingService trackingService;
    private final ObjectMapper objectMapper;

    public LocationUpdateConsumer(TrackingService trackingService, ObjectMapper objectMapper) {
        this.trackingService = trackingService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "rider.location.updates", groupId = "routing-eta-service",
        concurrency = "3")
    public void handleLocationUpdate(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);

            UUID deliveryId = UUID.fromString(event.path("deliveryId").asText());
            BigDecimal lat = new BigDecimal(event.path("latitude").asText());
            BigDecimal lng = new BigDecimal(event.path("longitude").asText());
            BigDecimal speed = event.has("speedKmh")
                ? new BigDecimal(event.path("speedKmh").asText()) : null;
            BigDecimal heading = event.has("heading")
                ? new BigDecimal(event.path("heading").asText()) : null;

            LocationUpdateRequest request = new LocationUpdateRequest(
                deliveryId, lat, lng, speed, heading);
            trackingService.recordLocation(request);

            log.debug("Recorded location update for delivery {}", deliveryId);
        } catch (Exception ex) {
            log.error("Failed to process location update: {}", message, ex);
            throw new RuntimeException("Failed to process location update", ex);
        }
    }
}
