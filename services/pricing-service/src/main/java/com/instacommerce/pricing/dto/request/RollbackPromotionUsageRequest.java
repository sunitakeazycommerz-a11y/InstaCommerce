package com.instacommerce.pricing.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RollbackPromotionUsageRequest(
    @NotNull UUID promotionId,
    UUID userId,
    UUID orderId
) {}
