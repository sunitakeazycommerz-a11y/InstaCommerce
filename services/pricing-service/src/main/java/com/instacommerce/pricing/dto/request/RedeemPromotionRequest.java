package com.instacommerce.pricing.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RedeemPromotionRequest(
    @NotNull UUID promotionId,
    @NotNull UUID userId,
    UUID orderId,
    @Min(0) long discountCents
) {}
