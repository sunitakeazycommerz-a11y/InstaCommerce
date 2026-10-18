package com.instacommerce.routing.dto.request;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateDeliveryRequest(
    @NotNull UUID orderId,
    UUID riderId,
    @NotNull UUID storeId,
    @NotNull BigDecimal pickupLat,
    @NotNull BigDecimal pickupLng,
    @NotNull BigDecimal dropoffLat,
    @NotNull BigDecimal dropoffLng,
    String vehicleType
) {
    public CreateDeliveryRequest(
        UUID orderId,
        UUID riderId,
        UUID storeId,
        BigDecimal pickupLat,
        BigDecimal pickupLng,
        BigDecimal dropoffLat,
        BigDecimal dropoffLng
    ) {
        this(orderId, riderId, storeId, pickupLat, pickupLng, dropoffLat, dropoffLng, "MOTORCYCLE");
    }
}
