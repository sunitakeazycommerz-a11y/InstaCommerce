package com.instacommerce.routing.service;

import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.response.DeliveryResponse;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.exception.DeliveryNotFoundException;
import com.instacommerce.routing.repository.DeliveryRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final List<DeliveryStatus> NON_TERMINAL_STATUSES = List.of(
        DeliveryStatus.PENDING, DeliveryStatus.RIDER_ASSIGNED, DeliveryStatus.ASSIGNED,
        DeliveryStatus.PICKED_UP, DeliveryStatus.EN_ROUTE, DeliveryStatus.IN_TRANSIT,
        DeliveryStatus.NEAR_DESTINATION);

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

        Delivery delivery = DeliveryPayloadSupport.buildNewDelivery(
            orderId, riderId, storeId, pickupLat, pickupLng, dropoffLat, dropoffLng, effectiveVehicle, eta);

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
        DeliveryPayloadSupport.validateTransition(delivery.getStatus(), DeliveryStatus.DELIVERED);
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
        if (estimate.breachProbability() > threshold
            || estimate.etaMinutes() > routingProperties.getBreach().getSlaThresholdMinutes()) {
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
            messagingTemplate.convertAndSend(
                "/topic/deliveries/" + delivery.getId() + "/breach-risk", (Object) breachPayload);
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

    @Transactional
    public void markDeliveryFailed(UUID orderId) {
        markDeliveryFailed(orderId, null);
    }

    @Transactional
    public void markDeliveryFailed(UUID orderId, String reason) {
        String normalizedReason = (reason != null && !reason.isBlank()) ? reason.trim() : null;
        var deliveryOpt = deliveryRepository.findByOrderId(orderId);
        if (deliveryOpt.isEmpty()) {
            log.warn("Received failure/cancellation for unknown delivery order={}, reason={}",
                orderId, normalizedReason);
            return;
        }
        Delivery delivery = deliveryOpt.get();
        if (delivery.getStatus().isTerminal()) {
            log.debug("Ignoring failure/cancellation for terminal delivery {} order={} status={} reason={}",
                delivery.getId(), orderId, delivery.getStatus(), normalizedReason);
            return;
        }
        log.info("Marking delivery {} for order {} FAILED (reason={})",
            delivery.getId(), orderId, normalizedReason);
        applyTransition(delivery, DeliveryStatus.FAILED, normalizedReason);
    }

    @Transactional
    public List<UUID> handleRiderEnteredStore(UUID riderId, UUID zoneId, UUID orderId) {
        return handleRiderEnteredStore(riderId, zoneId, orderId, null);
    }

    @Transactional
    public List<UUID> handleRiderEnteredStore(UUID riderId, UUID zoneId, UUID orderId, UUID deliveryId) {
        List<Delivery> targets = DeliveryPayloadSupport.resolveStoreGeofenceTargets(
            deliveryRepository, NON_TERMINAL_STATUSES, riderId, zoneId, orderId, deliveryId);
        List<UUID> transitioned = new ArrayList<>();
        for (Delivery delivery : targets) {
            if (delivery.getStatus().isTerminal()) {
                continue;
            }
            if (delivery.getStatus() == DeliveryStatus.PENDING) {
                delivery = applyTransition(delivery, DeliveryStatus.RIDER_ASSIGNED);
            }
            if (delivery.getStatus() == DeliveryStatus.RIDER_ASSIGNED
                || delivery.getStatus() == DeliveryStatus.ASSIGNED) {
                delivery = applyTransition(delivery, DeliveryStatus.PICKED_UP);
                transitioned.add(delivery.getId());
            }
        }
        return transitioned;
    }

    @Transactional
    public List<UUID> handleRiderNearDelivery(UUID riderId, UUID orderId, UUID zoneId,
                                              BigDecimal lat, BigDecimal lng) {
        return handleRiderNearDelivery(riderId, orderId, null, zoneId, lat, lng);
    }

    @Transactional
    public List<UUID> handleRiderNearDelivery(UUID riderId, UUID orderId, UUID deliveryId, UUID zoneId,
                                              BigDecimal lat, BigDecimal lng) {
        List<Delivery> targets = DeliveryPayloadSupport.resolveNearDeliveryTargets(
            deliveryRepository, riderId, orderId, deliveryId, lat, lng);
        List<UUID> transitioned = new ArrayList<>();
        for (Delivery delivery : targets) {
            if (delivery.getStatus() == DeliveryStatus.EN_ROUTE
                || delivery.getStatus() == DeliveryStatus.IN_TRANSIT) {
                delivery = applyTransition(delivery, DeliveryStatus.NEAR_DESTINATION);
                transitioned.add(delivery.getId());
            }
        }
        return transitioned;
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
        List<Delivery> active = deliveryRepository.findByRiderIdAndStatusIn(
            delivery.riderId(), List.copyOf(ACTIVE_STATUSES));
        return DeliveryPayloadSupport.countPrecedingStops(
            active, delivery.id(), delivery.createdAt(),
            delivery.dropoffLat(), delivery.dropoffLng(), fromLat, fromLng);
    }

    @Transactional(readOnly = true)
    public int countPrecedingStops(Delivery delivery, double fromLat, double fromLng) {
        if (delivery == null || delivery.getRiderId() == null) {
            return 0;
        }
        List<Delivery> active = deliveryRepository.findByRiderIdAndStatusIn(
            delivery.getRiderId(), List.copyOf(ACTIVE_STATUSES));
        return DeliveryPayloadSupport.countPrecedingStops(
            active, delivery.getId(), delivery.getCreatedAt(),
            delivery.getDropoffLat(), delivery.getDropoffLng(), fromLat, fromLng);
    }

    private Delivery applyTransition(Delivery delivery, DeliveryStatus targetStatus) {
        return applyTransition(delivery, targetStatus, null);
    }

    private Delivery applyTransition(Delivery delivery, DeliveryStatus targetStatus, String reason) {
        DeliveryPayloadSupport.validateTransition(delivery.getStatus(), targetStatus);
        delivery.setStatus(targetStatus);
        if ((targetStatus == DeliveryStatus.RIDER_ASSIGNED || targetStatus == DeliveryStatus.ASSIGNED)
            && delivery.getStartedAt() == null) {
            delivery.setStartedAt(Instant.now());
        }
        Delivery saved = deliveryRepository.save(delivery);
        log.info("Delivery {} status updated to {}", saved.getId(), targetStatus);
        outboxService.publish("Delivery", saved.getId().toString(),
            "DeliveryStatusUpdated", DeliveryPayloadSupport.statusPayload(saved, reason));
        return saved;
    }

    private DeliveryResponse toResponse(Delivery d) {
        return DeliveryPayloadSupport.toResponse(d);
    }
}
