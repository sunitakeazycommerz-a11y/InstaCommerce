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
    @NotNull BigDecimal dropoffLng
) {
}
