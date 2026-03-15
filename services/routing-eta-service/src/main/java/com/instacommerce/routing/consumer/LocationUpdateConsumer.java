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
        DeliveryStatus.RIDER_ASSIGNED, DeliveryStatus.PICKED_UP, DeliveryStatus.EN_ROUTE);

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

            if (routingProperties.getBreach().isRecalcOnLocationUpdateEnabled()) {
                recalculateETA(deliveryId, lat.doubleValue(), lng.doubleValue());
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

            ETAEstimate estimate = etaService.calculateETAWithConfidence(
                currentLat, currentLng,
                delivery.getDropoffLat().doubleValue(),
                delivery.getDropoffLng().doubleValue());

            int currentEta = delivery.getEstimatedMinutes() != null ? delivery.getEstimatedMinutes() : 0;
            int minDelta = routingProperties.getBreach().getEtaRecalcMinDeltaMinutes();

            if (Math.abs(estimate.etaMinutes() - currentEta) >= minDelta) {
                deliveryService.updateETA(delivery.getId(), estimate);
                log.info("Recalculated ETA for delivery {}: {}min -> {}min (breach_prob={})",
                    deliveryId, currentEta, estimate.etaMinutes(),
                    String.format("%.2f", estimate.breachProbability()));
            }
        } catch (Exception ex) {
            log.warn("ETA recalculation failed for delivery {}, continuing", deliveryId, ex);
        }
    }
}
