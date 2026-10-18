package com.instacommerce.pricing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public record CreatePromotionRequest(
    @NotBlank String name,
    String description,
    @NotBlank String discountType,
    @NotNull BigDecimal discountValue,
    long minOrderCents,
    Long maxDiscountCents,
    @NotNull Instant startAt,
    @NotNull Instant endAt,
    Integer maxUses,
    Integer maxRedemptionsPerUser
) {
    public CreatePromotionRequest(
        String name,
        String description,
        String discountType,
        BigDecimal discountValue,
        long minOrderCents,
        Long maxDiscountCents,
        Instant startAt,
        Instant endAt,
        Integer maxUses
    ) {
        this(name, description, discountType, discountValue, minOrderCents, maxDiscountCents, startAt, endAt, maxUses, 1);
    }
}
