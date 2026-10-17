package com.instacommerce.pricing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RollbackCouponUsageRequest(
    @NotBlank String code,
    @NotNull UUID userId
) {}
