package com.instacommerce.routing.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record LocationUpdateRequest(
    @NotNull UUID deliveryId,
    @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
    @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
    @DecimalMin("0") @DecimalMax("200") BigDecimal speedKmh,
    @DecimalMin("0") @DecimalMax("360") BigDecimal heading
) {
}
