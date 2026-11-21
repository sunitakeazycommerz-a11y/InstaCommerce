package com.instacommerce.routing.service;

import com.instacommerce.platform.geo.GeoUtils;
import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.response.DeliveryResponse;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.exception.DeliveryNotFoundException;
import com.instacommerce.routing.exception.InvalidDeliveryStateException;
import com.instacommerce.routing.repository.DeliveryRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private final SimpMessagingTemplate messagingTemplate;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           ETAService etaService,
                           OutboxService outboxService,
                           RoutingProperties routingProperties,
                           SimpMessagingTemplate messagingTemplate) {
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
        if (pickupLat == null || pickupLng == null || dropoffLat == null || dropoffLng == null) {
            throw new IllegalArgumentException("Pickup and dropoff coordinates are required to create a delivery");
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
            "DeliveryCreated", toResponse(delivery));

        return toResponse(delivery);
    }

    @Transactional
    public DeliveryResponse updateStatus(UUID deliveryId, DeliveryStatus newStatus) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));
        return toResponse(applyTransition(delivery, newStatus));
    }

    @Transactional
    public DeliveryResponse completeDelivery(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));
        return completeDeliveryInternal(delivery);
    }

    private DeliveryResponse completeDeliveryInternal(Delivery delivery) {
        validateTransition(delivery.getStatus(), DeliveryStatus.DELIVERED);
        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(Instant.now());

        if (delivery.getStartedAt() != null) {
            long seconds = Duration.between(delivery.getStartedAt(), delivery.getDeliveredAt()).toSeconds();
            delivery.setActualMinutes((int) Math.round(seconds / 60.0));
        }

        delivery = deliveryRepository.save(delivery);
        log.info("Delivery {} completed for order {}", delivery.getId(), delivery.getOrderId());
        outboxService.publish("Delivery", delivery.getId().toString(), "DeliveryCompleted", toResponse(delivery));
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

        Map<String, Object> updatePayload = DeliveryPayloadSupport.etaUpdatePayload(delivery, estimate);
        outboxService.publish("Delivery", deliveryId.toString(), "EtaUpdated", updatePayload);

        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/deliveries/" + deliveryId + "/eta", (Object) updatePayload);
            messagingTemplate.convertAndSend("/topic/orders/" + delivery.getOrderId() + "/eta", (Object) updatePayload);
        }

        double threshold = routingProperties.getBreach().getBreachAlertThreshold();
        if (estimate.breachProbability() > threshold || estimate.etaMinutes() > routingProperties.getBreach().getSlaThresholdMinutes()) {
            recordBreachRiskIfFirst(delivery, estimate);
        }

        deliveryRepository.save(delivery);
    }

    private void recordBreachRiskIfFirst(Delivery delivery, ETAEstimate estimate) {
        delivery.setBreachRisk(true);
        if (delivery.isCompensationTriggered()) {
            return;
        }
        int creditCents = Math.max(0, routingProperties.getBreach().getApologyCreditCents());
        String currency = DeliveryPayloadSupport.resolveApologyCurrency(routingProperties);
        delivery.setCompensationTriggered(true);
        delivery.setCompensationAmountCents(creditCents);

        log.warn("ETA breach risk for delivery {}: probability={}, eta={}min, sla={}min",
            delivery.getId(), estimate.breachProbability(), estimate.etaMinutes(),
            routingProperties.getBreach().getSlaThresholdMinutes());

        Map<String, Object> breachPayload = DeliveryPayloadSupport.breachRiskPayload(
            delivery, estimate, routingProperties, creditCents, currency);
        outboxService.publish("Delivery", delivery.getId().toString(), "ETABreachRisk", breachPayload);

        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/deliveries/" + delivery.getId() + "/breach-risk", (Object) breachPayload);
        }
    }

    @Transactional
    public void advanceOrderDispatched(UUID orderId, UUID riderId) {
        var deliveryOpt = deliveryRepository.findByOrderId(orderId);
        if (deliveryOpt.isEmpty()) {
            log.warn("Received OrderDispatched for unknown delivery order={}", orderId);
            return;
        }
        Delivery delivery = deliveryOpt.get();
        if (delivery.getRiderId() == null && riderId != null) {
            delivery.setRiderId(riderId);
        }
        if (delivery.getStatus() == DeliveryStatus.EN_ROUTE
            || delivery.getStatus() == DeliveryStatus.IN_TRANSIT
            || delivery.getStatus() == DeliveryStatus.NEAR_DESTINATION
            || delivery.getStatus() == DeliveryStatus.DELIVERED) {
            return;
        }
        if (delivery.getStatus() == DeliveryStatus.PENDING) {
            delivery = applyTransition(delivery, DeliveryStatus.RIDER_ASSIGNED);
        }
        if (delivery.getStatus() == DeliveryStatus.RIDER_ASSIGNED || delivery.getStatus() == DeliveryStatus.ASSIGNED) {
            delivery = applyTransition(delivery, DeliveryStatus.PICKED_UP);
        }
        applyTransition(delivery, DeliveryStatus.EN_ROUTE);
    }

    @Transactional
    public void advanceOrderDelivered(UUID orderId) {
        var deliveryOpt = deliveryRepository.findByOrderId(orderId);
        if (deliveryOpt.isEmpty()) {
            log.warn("Received OrderDelivered for unknown delivery order={}", orderId);
            return;
        }
        Delivery delivery = deliveryOpt.get();
        if (delivery.getStatus() == DeliveryStatus.DELIVERED) {
            return;
        }
        if (delivery.getStatus() == DeliveryStatus.PENDING) {
            delivery = applyTransition(delivery, DeliveryStatus.RIDER_ASSIGNED);
        }
        if (delivery.getStatus() == DeliveryStatus.RIDER_ASSIGNED || delivery.getStatus() == DeliveryStatus.ASSIGNED) {
            delivery = applyTransition(delivery, DeliveryStatus.PICKED_UP);
        }
        if (delivery.getStatus() == DeliveryStatus.PICKED_UP) {
            delivery = applyTransition(delivery, DeliveryStatus.EN_ROUTE);
        }
        completeDeliveryInternal(delivery);
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

    @Transactional(readOnly = true)
    public int countPrecedingStops(DeliveryResponse delivery, double fromLat, double fromLng) {
        if (delivery == null || delivery.riderId() == null) {
            return 0;
        }
        return countPrecedingStopsForRider(delivery.id(), delivery.riderId(), delivery.createdAt(),
            delivery.dropoffLat(), delivery.dropoffLng(), fromLat, fromLng);
    }

    @Transactional(readOnly = true)
    public int countPrecedingStops(Delivery delivery, double fromLat, double fromLng) {
        if (delivery == null || delivery.getRiderId() == null) {
            return 0;
        }
        return countPrecedingStopsForRider(delivery.getId(), delivery.getRiderId(), delivery.getCreatedAt(),
            delivery.getDropoffLat(), delivery.getDropoffLng(), fromLat, fromLng);
    }

    private int countPrecedingStopsForRider(UUID targetDeliveryId, UUID riderId, Instant targetCreatedAt,
                                            BigDecimal targetDropoffLat, BigDecimal targetDropoffLng,
                                            double fromLat, double fromLng) {
        List<Delivery> active = deliveryRepository.findByRiderIdAndStatusIn(riderId, List.copyOf(ACTIVE_STATUSES));
        if (active == null || active.size() <= 1) {
            return 0;
        }
        double targetDistanceKm = (targetDropoffLat != null && targetDropoffLng != null)
            ? GeoUtils.haversineKm(fromLat, fromLng, targetDropoffLat.doubleValue(), targetDropoffLng.doubleValue())
            : Double.MAX_VALUE;
        int preceding = 0;
        for (Delivery other : active) {
            if (other.getId().equals(targetDeliveryId)) {
                continue;
            }
            if (other.getDropoffLat() != null && other.getDropoffLng() != null) {
                double otherDistanceKm = GeoUtils.haversineKm(
                    fromLat, fromLng, other.getDropoffLat().doubleValue(), other.getDropoffLng().doubleValue());
                if (otherDistanceKm < targetDistanceKm - 1e-6) {
                    preceding++;
                    continue;
                }
                if (Math.abs(otherDistanceKm - targetDistanceKm) <= 1e-6
                    && other.getCreatedAt() != null && targetCreatedAt != null
                    && other.getCreatedAt().isBefore(targetCreatedAt)) {
                    preceding++;
                }
            }
        }
        return preceding;
    }

    private Delivery applyTransition(Delivery delivery, DeliveryStatus targetStatus) {
        validateTransition(delivery.getStatus(), targetStatus);
        delivery.setStatus(targetStatus);
        if ((targetStatus == DeliveryStatus.RIDER_ASSIGNED || targetStatus == DeliveryStatus.ASSIGNED)
            && delivery.getStartedAt() == null) {
            delivery.setStartedAt(Instant.now());
        }
        Delivery saved = deliveryRepository.save(delivery);
        log.info("Delivery {} status updated to {}", saved.getId(), targetStatus);
        outboxService.publish("Delivery", saved.getId().toString(),
            "DeliveryStatusUpdated", DeliveryPayloadSupport.statusPayload(saved));
        return saved;
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
        return DeliveryPayloadSupport.toResponse(d);
    }
}
