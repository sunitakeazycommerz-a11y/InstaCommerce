package com.instacommerce.routing.service;

import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.response.DeliveryResponse;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.exception.DeliveryNotFoundException;
import com.instacommerce.routing.exception.InvalidDeliveryStateException;
import com.instacommerce.routing.repository.DeliveryRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final Set<DeliveryStatus> ACTIVE_STATUSES = Set.of(
        DeliveryStatus.RIDER_ASSIGNED, DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP,
        DeliveryStatus.EN_ROUTE, DeliveryStatus.IN_TRANSIT, DeliveryStatus.NEAR_DESTINATION);

    private final DeliveryRepository deliveryRepository;
    private final ETAService etaService;
    private final OutboxService outboxService;
    private final RoutingProperties routingProperties;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           ETAService etaService,
                           OutboxService outboxService,
                           RoutingProperties routingProperties) {
        this(deliveryRepository, etaService, outboxService, routingProperties, (org.springframework.messaging.simp.SimpMessagingTemplate) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryService(DeliveryRepository deliveryRepository,
                           ETAService etaService,
                           OutboxService outboxService,
                           RoutingProperties routingProperties,
                           org.springframework.beans.factory.ObjectProvider<org.springframework.messaging.simp.SimpMessagingTemplate> messagingTemplateProvider) {
        this.deliveryRepository = deliveryRepository;
        this.etaService = etaService;
        this.outboxService = outboxService;
        this.routingProperties = routingProperties;
        this.messagingTemplate = messagingTemplateProvider != null ? messagingTemplateProvider.getIfAvailable() : null;
    }

    public DeliveryService(DeliveryRepository deliveryRepository,
                           ETAService etaService,
                           OutboxService outboxService,
                           RoutingProperties routingProperties,
                           org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate) {
        this.deliveryRepository = deliveryRepository;
        this.etaService = etaService;
        this.outboxService = outboxService;
        this.routingProperties = routingProperties;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public DeliveryResponse createDelivery(UUID orderId, UUID riderId, UUID storeId,
                                           BigDecimal pickupLat, BigDecimal pickupLng,
                                           BigDecimal dropoffLat, BigDecimal dropoffLng) {
        return createDelivery(orderId, riderId, storeId, pickupLat, pickupLng, dropoffLat, dropoffLng, "MOTORCYCLE");
    }

    @Transactional
    public DeliveryResponse createDelivery(UUID orderId, UUID riderId, UUID storeId,
                                           BigDecimal pickupLat, BigDecimal pickupLng,
                                           BigDecimal dropoffLat, BigDecimal dropoffLng,
                                           String vehicleType) {
        var existing = deliveryRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Delivery already exists for order {}", orderId);
            return toResponse(existing.get());
        }
        String effectiveVehicle = vehicleType != null ? vehicleType : "MOTORCYCLE";
        var eta = etaService.calculateETAWithConfidence(
            pickupLat.doubleValue(), pickupLng.doubleValue(),
            dropoffLat.doubleValue(), dropoffLng.doubleValue(),
            storeId, effectiveVehicle, 0.0);

        Delivery delivery = new Delivery();
        delivery.setOrderId(orderId);
        delivery.setRiderId(riderId);
        delivery.setStoreId(storeId);
        delivery.setPickupLat(pickupLat);
        delivery.setPickupLng(pickupLng);
        delivery.setDropoffLat(dropoffLat);
        delivery.setDropoffLng(dropoffLng);
        delivery.setVehicleType(effectiveVehicle);
        delivery.setEstimatedMinutes(eta.etaMinutes());
        delivery.setEtaLowMinutes(eta.etaLowMinutes());
        delivery.setEtaHighMinutes(eta.etaHighMinutes());
        delivery.setLastEtaUpdatedAt(eta.calculatedAt());
        delivery.setDistanceKm(eta.distanceKm());
        if (eta.monsoonDelayActive()) {
            delivery.setWeatherDelayActive(true);
        }
        if (riderId != null) {
            delivery.setStatus(DeliveryStatus.RIDER_ASSIGNED);
            delivery.setStartedAt(Instant.now());
        }

        delivery = deliveryRepository.save(delivery);
        log.info("Created delivery {} for order {}", delivery.getId(), orderId);

        outboxService.publish("Delivery", delivery.getId().toString(),
            "DELIVERY_CREATED", toResponse(delivery));

        return toResponse(delivery);
    }

    @Transactional
    public DeliveryResponse updateStatus(UUID deliveryId, DeliveryStatus newStatus) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        validateTransition(delivery.getStatus(), newStatus);
        delivery.setStatus(newStatus);

        if ((newStatus == DeliveryStatus.RIDER_ASSIGNED || newStatus == DeliveryStatus.ASSIGNED) && delivery.getStartedAt() == null) {
            delivery.setStartedAt(Instant.now());
        }

        delivery = deliveryRepository.save(delivery);
        log.info("Delivery {} status updated to {}", deliveryId, newStatus);

        outboxService.publish("Delivery", deliveryId.toString(),
            "DELIVERY_STATUS_UPDATED", toResponse(delivery));

        return toResponse(delivery);
    }

    @Transactional
    public DeliveryResponse completeDelivery(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        validateTransition(delivery.getStatus(), DeliveryStatus.DELIVERED);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(Instant.now());

        if (delivery.getStartedAt() != null) {
            long seconds = Duration.between(delivery.getStartedAt(), delivery.getDeliveredAt()).toSeconds();
            delivery.setActualMinutes((int) Math.round(seconds / 60.0));
        }

        delivery = deliveryRepository.save(delivery);
        log.info("Delivery {} completed for order {}", deliveryId, delivery.getOrderId());

        outboxService.publish("Delivery", deliveryId.toString(),
            "DELIVERY_COMPLETED", toResponse(delivery));

        return toResponse(delivery);
    }

    @Transactional
    public void updateETA(UUID deliveryId, ETAEstimate estimate) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        if (!ACTIVE_STATUSES.contains(delivery.getStatus())) {
            return;
        }

        delivery.setEstimatedMinutes(estimate.etaMinutes());
        delivery.setEtaLowMinutes(estimate.etaLowMinutes());
        delivery.setEtaHighMinutes(estimate.etaHighMinutes());
        delivery.setLastEtaUpdatedAt(estimate.calculatedAt());
        if (estimate.monsoonDelayActive()) {
            delivery.setWeatherDelayActive(true);
        }

        // 1. Continuous ETA update broadcast and event (Issue #595)
        Map<String, Object> updatePayload = new java.util.HashMap<>();
        updatePayload.put("deliveryId", deliveryId.toString());
        updatePayload.put("orderId", delivery.getOrderId().toString());
        updatePayload.put("riderId", delivery.getRiderId() != null ? delivery.getRiderId().toString() : "");
        updatePayload.put("estimatedMinutes", estimate.etaMinutes());
        updatePayload.put("etaLowMinutes", estimate.etaLowMinutes());
        updatePayload.put("etaHighMinutes", estimate.etaHighMinutes());
        updatePayload.put("distanceKm", estimate.distanceKm());
        updatePayload.put("detourDetected", estimate.detourDetected());
        updatePayload.put("monsoonDelayActive", estimate.monsoonDelayActive());
        updatePayload.put("delayBanner", estimate.delayBanner());
        updatePayload.put("vehicleRecommendation", estimate.vehicleRecommendation());
        updatePayload.put("calculatedAt", estimate.calculatedAt().toString());

        outboxService.publish("Delivery", deliveryId.toString(), "ETA_UPDATED", updatePayload);

        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/deliveries/" + deliveryId + "/eta", (Object) updatePayload);
            messagingTemplate.convertAndSend("/topic/orders/" + delivery.getOrderId() + "/eta", (Object) updatePayload);
        }

        // 2. Early-warning SLA breach risk and automated compensation (Issue #596)
        double threshold = routingProperties.getBreach().getBreachAlertThreshold();
        if (estimate.breachProbability() > threshold || estimate.etaMinutes() > routingProperties.getBreach().getSlaThresholdMinutes()) {
            boolean firstBreach = !delivery.isCompensationTriggered();
            delivery.setBreachRisk(true);
            if (firstBreach) {
                delivery.setCompensationTriggered(true);
                delivery.setCompensationAmountCents(200); // Automated $2 apology wallet credit per Issue #596
            }

            log.warn("ETA breach risk for delivery {}: probability={}, eta={}min, sla={}min",
                deliveryId, estimate.breachProbability(), estimate.etaMinutes(),
                routingProperties.getBreach().getSlaThresholdMinutes());

            Map<String, Object> breachPayload = new java.util.HashMap<>();
            breachPayload.put("deliveryId", deliveryId.toString());
            breachPayload.put("orderId", delivery.getOrderId().toString());
            breachPayload.put("riderId", delivery.getRiderId() != null ? delivery.getRiderId().toString() : "");
            breachPayload.put("currentEtaMinutes", estimate.etaMinutes());
            breachPayload.put("etaLowMinutes", estimate.etaLowMinutes());
            breachPayload.put("etaHighMinutes", estimate.etaHighMinutes());
            breachPayload.put("slaMinutes", routingProperties.getBreach().getSlaThresholdMinutes());
            breachPayload.put("breachProbability", estimate.breachProbability());
            breachPayload.put("timestamp", Instant.now().toString());
            breachPayload.put("autoCompensationCents", 200);
            breachPayload.put("customerMessage", "We're running slightly late! Here is a $2 apology wallet credit on us.");
            breachPayload.put("priorityEscalation", true);

            outboxService.publish("Delivery", deliveryId.toString(), "ETA_BREACH_RISK", breachPayload);

            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/deliveries/" + deliveryId + "/breach-risk", (Object) breachPayload);
            }
        }

        delivery = deliveryRepository.save(delivery);
    }

    @Transactional(readOnly = true)
    public DeliveryResponse getByOrderId(UUID orderId) {
        return deliveryRepository.findByOrderId(orderId)
            .map(this::toResponse)
            .orElseThrow(() -> new DeliveryNotFoundException("No delivery found for order " + orderId));
    }

    @Transactional(readOnly = true)
    public DeliveryResponse getById(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId)
            .map(this::toResponse)
            .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));
    }

    private void validateTransition(DeliveryStatus current, DeliveryStatus target) {
        boolean valid = switch (current) {
            case PENDING -> target == DeliveryStatus.RIDER_ASSIGNED || target == DeliveryStatus.ASSIGNED || target == DeliveryStatus.FAILED;
            case RIDER_ASSIGNED, ASSIGNED -> target == DeliveryStatus.PICKED_UP || target == DeliveryStatus.FAILED;
            case PICKED_UP -> target == DeliveryStatus.EN_ROUTE || target == DeliveryStatus.IN_TRANSIT || target == DeliveryStatus.FAILED;
            case EN_ROUTE, IN_TRANSIT -> target == DeliveryStatus.NEAR_DESTINATION || target == DeliveryStatus.DELIVERED || target == DeliveryStatus.FAILED;
            case NEAR_DESTINATION -> target == DeliveryStatus.DELIVERED || target == DeliveryStatus.FAILED;
            case DELIVERED, FAILED -> false;
        };
        if (!valid) {
            throw new InvalidDeliveryStateException(current, target);
        }
    }

    private DeliveryResponse toResponse(Delivery d) {
        return new DeliveryResponse(
            d.getId(), d.getOrderId(), d.getRiderId(), d.getStoreId(),
            d.getPickupLat(), d.getPickupLng(), d.getDropoffLat(), d.getDropoffLng(),
            d.getStatus(), d.getEstimatedMinutes(), d.getActualMinutes(),
            d.getDistanceKm(), d.getStartedAt(), d.getDeliveredAt(),
            d.getCreatedAt(), d.getUpdatedAt(),
            d.isBreachRisk(), d.isCompensationTriggered(), d.getCompensationAmountCents(),
            d.getVehicleType(), d.isWeatherDelayActive());
    }
}
