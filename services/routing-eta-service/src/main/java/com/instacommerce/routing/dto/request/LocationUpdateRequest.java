package com.instacommerce.routing.dto.request;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record LocationUpdateRequest(
    @NotNull UUID deliveryId,
    @NotNull BigDecimal latitude,
    @NotNull BigDecimal longitude,
    BigDecimal speedKmh,
    BigDecimal heading
) {
}
