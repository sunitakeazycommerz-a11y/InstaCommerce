package com.instacommerce.routing.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.request.LocationUpdateRequest;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.repository.DeliveryRepository;
import com.instacommerce.routing.service.DeliveryService;
import com.instacommerce.routing.service.ETAService;
import com.instacommerce.routing.service.TrackingService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LocationUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(LocationUpdateConsumer.class);
    private static final List<DeliveryStatus> ACTIVE_STATUSES = List.of(
        DeliveryStatus.RIDER_ASSIGNED, DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP,
        DeliveryStatus.EN_ROUTE, DeliveryStatus.IN_TRANSIT, DeliveryStatus.NEAR_DESTINATION);

    private final TrackingService trackingService;
    private final ObjectMapper objectMapper;
    private final DeliveryRepository deliveryRepository;
    private final ETAService etaService;
    private final DeliveryService deliveryService;
    private final RoutingProperties routingProperties;

    public LocationUpdateConsumer(TrackingService trackingService,
                                   ObjectMapper objectMapper,
                                   DeliveryRepository deliveryRepository,
                                   ETAService etaService,
                                   DeliveryService deliveryService,
                                   RoutingProperties routingProperties) {
        this.trackingService = trackingService;
        this.objectMapper = objectMapper;
        this.deliveryRepository = deliveryRepository;
        this.etaService = etaService;
        this.deliveryService = deliveryService;
        this.routingProperties = routingProperties;
    }

    @KafkaListener(topics = TopicNames.RIDER_LOCATION_UPDATES, groupId = "routing-eta-service",
        concurrency = "3")
    public void handleLocationUpdate(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);

            BigDecimal lat = parseDecimal(event, "latitude", "lat");
            BigDecimal lng = parseDecimal(event, "longitude", "lng");

            if (lat == null || lng == null) {
                log.warn("Skipping location update missing coordinates: {}", message);
                return;
            }

            BigDecimal speed = parseDecimal(event, "speedKmh", "speed_kmh", "speed");
            BigDecimal heading = parseDecimal(event, "headingDegrees", "heading_degrees", "heading");

            List<UUID> deliveryIds = new ArrayList<>();
            String deliveryIdStr = event.hasNonNull("deliveryId") ? event.path("deliveryId").asText()
                : event.hasNonNull("delivery_id") ? event.path("delivery_id").asText() : null;

            if (deliveryIdStr != null && !deliveryIdStr.isBlank()) {
                try {
                    deliveryIds.add(UUID.fromString(deliveryIdStr));
                } catch (IllegalArgumentException ex) {
                    log.warn("Invalid deliveryId format in update: {}", deliveryIdStr);
                }
            } else {
                String riderIdStr = event.hasNonNull("rider_id") ? event.path("rider_id").asText()
                    : event.hasNonNull("riderId") ? event.path("riderId").asText() : null;
                if (riderIdStr != null && !riderIdStr.isBlank()) {
                    try {
                        UUID riderId = UUID.fromString(riderIdStr);
                        List<Delivery> active = deliveryRepository.findByRiderIdAndStatusIn(riderId, ACTIVE_STATUSES);
                        for (Delivery d : active) {
                            deliveryIds.add(d.getId());
                        }
                    } catch (IllegalArgumentException ex) {
                        log.warn("Invalid riderId format in update: {}", riderIdStr);
                    }
                }
            }

            if (deliveryIds.isEmpty()) {
                log.debug("No active deliveries found for location update: {}", message);
                return;
            }

            for (UUID deliveryId : deliveryIds) {
                LocationUpdateRequest request = new LocationUpdateRequest(
                    deliveryId, lat, lng, speed, heading);
                trackingService.recordLocation(request);

                log.debug("Recorded location update for delivery {}", deliveryId);

                if (routingProperties.getBreach().isRecalcOnLocationUpdateEnabled()) {
                    recalculateETA(deliveryId, lat.doubleValue(), lng.doubleValue());
                }
            }
        } catch (Exception ex) {
            log.error("Failed to process location update: {}", message, ex);
            throw new RuntimeException("Failed to process location update", ex);
        }
    }

    private void recalculateETA(UUID deliveryId, double currentLat, double currentLng) {
        try {
            var deliveryOpt = deliveryRepository.findById(deliveryId);
            if (deliveryOpt.isEmpty()) {
                return;
            }

            Delivery delivery = deliveryOpt.get();
            if (!ACTIVE_STATUSES.contains(delivery.getStatus())) {
                return;
            }
            if (delivery.getDropoffLat() == null || delivery.getDropoffLng() == null) {
                return;
            }

            int batchStops = deliveryService.countPrecedingStops(delivery, currentLat, currentLng);
            ETAEstimate estimate = etaService.calculateETAWithConfidence(
                currentLat, currentLng,
                delivery.getDropoffLat().doubleValue(),
                delivery.getDropoffLng().doubleValue(),
                delivery.getStoreId(),
                delivery.getVehicleType(),
                0.0,
                batchStops);

            int currentEta = delivery.getEstimatedMinutes() != null ? delivery.getEstimatedMinutes() : 0;
            int minDelta = routingProperties.getBreach().getEtaRecalcMinDeltaMinutes();
            boolean breachCrossed = !delivery.isCompensationTriggered()
                && (estimate.breachProbability() > routingProperties.getBreach().getBreachAlertThreshold()
                    || estimate.etaMinutes() > routingProperties.getBreach().getSlaThresholdMinutes());

            if (Math.abs(estimate.etaMinutes() - currentEta) >= minDelta || breachCrossed) {
                deliveryService.updateETA(delivery.getId(), estimate);
                log.info("Recalculated ETA for delivery {}: {}min -> {}min (breach_prob={})",
                    deliveryId, currentEta, estimate.etaMinutes(),
                    String.format("%.2f", estimate.breachProbability()));
            }
        } catch (Exception ex) {
            log.warn("ETA recalculation failed for delivery {}, continuing", deliveryId, ex);
        }
    }

    private BigDecimal parseDecimal(JsonNode event, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode node = event.path(field);
            if (!node.isMissingNode() && !node.isNull() && node.isValueNode()) {
                if (node.isNumber()) {
                    return node.decimalValue();
                }
                String raw = node.asText().trim();
                if (!raw.isEmpty() && !"null".equalsIgnoreCase(raw)) {
                    try {
                        return new BigDecimal(raw);
                    } catch (NumberFormatException ex) {
                        log.warn("Ignoring invalid decimal value for {}: {}", field, raw);
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
