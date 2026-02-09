package com.instacommerce.riderfleet.dto.request;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record LocationUpdateRequest(
    @NotNull BigDecimal lat,
    @NotNull BigDecimal lng
) {
}
