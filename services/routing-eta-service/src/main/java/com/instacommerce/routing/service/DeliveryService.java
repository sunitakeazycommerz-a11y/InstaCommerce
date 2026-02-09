package com.instacommerce.routing.service;

import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.response.DeliveryResponse;
import com.instacommerce.routing.exception.DeliveryNotFoundException;
import com.instacommerce.routing.exception.InvalidDeliveryStateException;
import com.instacommerce.routing.repository.DeliveryRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final DeliveryRepository deliveryRepository;
    private final ETAService etaService;
    private final OutboxService outboxService;

    public DeliveryService(DeliveryRepository deliveryRepository,
                           ETAService etaService,
                           OutboxService outboxService) {
        this.deliveryRepository = deliveryRepository;
        this.etaService = etaService;
        this.outboxService = outboxService;
    }

    @Transactional
    public DeliveryResponse createDelivery(UUID orderId, UUID riderId, UUID storeId,
                                           BigDecimal pickupLat, BigDecimal pickupLng,
                                           BigDecimal dropoffLat, BigDecimal dropoffLng) {
        var eta = etaService.calculateETA(
            pickupLat.doubleValue(), pickupLng.doubleValue(),
            dropoffLat.doubleValue(), dropoffLng.doubleValue());

        Delivery delivery = new Delivery();
        delivery.setOrderId(orderId);
        delivery.setRiderId(riderId);
        delivery.setStoreId(storeId);
        delivery.setPickupLat(pickupLat);
        delivery.setPickupLng(pickupLng);
        delivery.setDropoffLat(dropoffLat);
        delivery.setDropoffLng(dropoffLng);
        delivery.setEstimatedMinutes(eta.estimatedMinutes());
        delivery.setDistanceKm(eta.distanceKm());
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

        if (newStatus == DeliveryStatus.RIDER_ASSIGNED && delivery.getStartedAt() == null) {
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

        delivery.setStatus(DeliveryStatus.DELIVERED);
        delivery.setDeliveredAt(Instant.now());

        if (delivery.getStartedAt() != null) {
            long minutes = Duration.between(delivery.getStartedAt(), delivery.getDeliveredAt()).toMinutes();
            delivery.setActualMinutes((int) minutes);
        }

        delivery = deliveryRepository.save(delivery);
        log.info("Delivery {} completed for order {}", deliveryId, delivery.getOrderId());

        outboxService.publish("Delivery", deliveryId.toString(),
            "DELIVERY_COMPLETED", toResponse(delivery));

        return toResponse(delivery);
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
            case PENDING -> target == DeliveryStatus.RIDER_ASSIGNED || target == DeliveryStatus.FAILED;
            case RIDER_ASSIGNED -> target == DeliveryStatus.PICKED_UP || target == DeliveryStatus.FAILED;
            case PICKED_UP -> target == DeliveryStatus.EN_ROUTE || target == DeliveryStatus.FAILED;
            case EN_ROUTE -> target == DeliveryStatus.NEAR_DESTINATION || target == DeliveryStatus.FAILED;
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
            d.getCreatedAt(), d.getUpdatedAt());
    }
}
