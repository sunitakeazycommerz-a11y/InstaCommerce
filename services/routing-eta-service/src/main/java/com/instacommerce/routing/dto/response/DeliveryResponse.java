package com.instacommerce.routing.dto.response;

import com.instacommerce.routing.domain.model.DeliveryStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
    UUID id,
    UUID orderId,
    UUID riderId,
    UUID storeId,
    BigDecimal pickupLat,
    BigDecimal pickupLng,
    BigDecimal dropoffLat,
    BigDecimal dropoffLng,
    DeliveryStatus status,
    Integer estimatedMinutes,
    Integer actualMinutes,
    BigDecimal distanceKm,
    Instant startedAt,
    Instant deliveredAt,
    Instant createdAt,
    Instant updatedAt,
    boolean isBreachRisk,
    boolean compensationTriggered,
    int compensationAmountCents,
    String vehicleType,
    boolean weatherDelayActive
) {
    public DeliveryResponse(
        UUID id,
        UUID orderId,
        UUID riderId,
        UUID storeId,
        BigDecimal pickupLat,
        BigDecimal pickupLng,
        BigDecimal dropoffLat,
        BigDecimal dropoffLng,
        DeliveryStatus status,
        Integer estimatedMinutes,
        Integer actualMinutes,
        BigDecimal distanceKm,
        Instant startedAt,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(id, orderId, riderId, storeId, pickupLat, pickupLng, dropoffLat, dropoffLng,
             status, estimatedMinutes, actualMinutes, distanceKm, startedAt, deliveredAt,
             createdAt, updatedAt, false, false, 0, "MOTORCYCLE", false);
    }
}
