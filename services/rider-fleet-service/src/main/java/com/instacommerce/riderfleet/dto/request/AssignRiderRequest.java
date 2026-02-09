package com.instacommerce.riderfleet.dto.request;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record AssignRiderRequest(
    @NotNull UUID orderId,
    @NotNull UUID storeId,
    @NotNull BigDecimal pickupLat,
    @NotNull BigDecimal pickupLng
) {
}
