package com.instacommerce.routing.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.routing.dto.request.LocationUpdateRequest;
import com.instacommerce.routing.service.DeliveryService;
import com.instacommerce.routing.service.TrackingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class GeofenceEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(GeofenceEventConsumer.class);

    private final DeliveryService deliveryService;
    private final TrackingService trackingService;
    private final ObjectMapper objectMapper;

    public GeofenceEventConsumer(DeliveryService deliveryService, ObjectMapper objectMapper) {
        this(deliveryService, null, objectMapper);
    }

    @Autowired
    public GeofenceEventConsumer(DeliveryService deliveryService,
                                 TrackingService trackingService,
                                 ObjectMapper objectMapper) {
        this.deliveryService = deliveryService;
        this.trackingService = trackingService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TopicNames.RIDER_GEOFENCE_EVENTS, groupId = "routing-eta-service")
    public void handleGeofenceEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode payload = root.path("payload").isObject() ? root.path("payload") : root;
            if (payload != root) {
                String envelopeEventType = readText(root, "eventType", "event_type");
                if (envelopeEventType != null
                    && !"RiderGeofenceTransition".equals(envelopeEventType)
                    && !"ENTERED_STORE".equals(envelopeEventType)
                    && !"NEAR_DELIVERY".equals(envelopeEventType)) {
                    log.debug("Ignoring geofence envelope event type: {}", envelopeEventType);
                    return;
                }
            }

            String transitionType = readText(payload, "event_type", "eventType", "transition_type", "transitionType");
            if (transitionType == null && payload != root) {
                transitionType = readText(root, "event_type", "transition_type", "transitionType", "eventType");
            }
            if (transitionType == null) {
                return;
            }

            UUID riderId = parseUuid(payload, "rider_id", "riderId");
            if (riderId == null) {
                riderId = parseUuid(root, "aggregateId", "aggregate_id");
            }
            UUID zoneId = parseUuid(payload, "zone_id", "zoneId");
            UUID orderId = parseUuid(payload, "orderId", "order_id");
            BigDecimal lat = parseDecimal(payload, "lat", "latitude");
            BigDecimal lng = parseDecimal(payload, "lng", "longitude");

            List<UUID> transitionedIds;
            if ("ENTERED_STORE".equals(transitionType)) {
                transitionedIds = deliveryService.handleRiderEnteredStore(riderId, zoneId, orderId);
            } else if ("NEAR_DELIVERY".equals(transitionType)) {
                transitionedIds = deliveryService.handleRiderNearDelivery(riderId, zoneId, orderId);
            } else {
                log.debug("Ignoring geofence transition type: {}", transitionType);
                return;
            }

            if (trackingService != null && lat != null && lng != null && transitionedIds != null) {
                for (UUID deliveryId : transitionedIds) {
                    trackingService.recordLocation(new LocationUpdateRequest(deliveryId, lat, lng, null, null));
                }
            }
        } catch (Exception ex) {
            log.error("Failed to process geofence event: {}", message, ex);
            throw new RuntimeException("Failed to process geofence event", ex);
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
            log.debug("Non-UUID value for geofence field {}: {}", String.join("/", fieldNames), raw);
            return null;
        }
    }

    private static BigDecimal parseDecimal(JsonNode container, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode node = container.path(fieldName);
            if (!node.isMissingNode() && !node.isNull() && node.isValueNode()) {
                if (node.isNumber()) {
                    return node.decimalValue();
                }
                String raw = node.asText().trim();
                if (!raw.isEmpty() && !"null".equalsIgnoreCase(raw)) {
                    try {
                        return new BigDecimal(raw);
                    } catch (NumberFormatException ex) {
                        log.warn("Invalid coordinate value for {}: {}", fieldName, raw);
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
