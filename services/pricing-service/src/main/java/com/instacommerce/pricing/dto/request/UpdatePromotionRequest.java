package com.instacommerce.pricing.dto.request;

import java.math.BigDecimal;
import java.time.Instant;

public record UpdatePromotionRequest(
    String name,
    String description,
    String discountType,
    BigDecimal discountValue,
    Long minOrderCents,
    Long maxDiscountCents,
    Instant startAt,
    Instant endAt,
    Boolean active,
    Integer maxUses,
    Integer maxRedemptionsPerUser,
    Boolean stackableWithCoupon,
    Integer priority
) {
    public UpdatePromotionRequest(
        String name,
        String description,
        String discountType,
        BigDecimal discountValue,
        Long minOrderCents,
        Long maxDiscountCents,
        Instant startAt,
        Instant endAt,
        Boolean active,
        Integer maxUses
    ) {
        this(name, description, discountType, discountValue, minOrderCents, maxDiscountCents, startAt, endAt, active, maxUses, null, null, null);
    }

    public UpdatePromotionRequest(
        String name,
        String description,
        String discountType,
        BigDecimal discountValue,
        Long minOrderCents,
        Long maxDiscountCents,
        Instant startAt,
        Instant endAt,
        Boolean active,
        Integer maxUses,
        Integer maxRedemptionsPerUser
    ) {
        this(name, description, discountType, discountValue, minOrderCents, maxDiscountCents, startAt, endAt, active, maxUses, maxRedemptionsPerUser, null, null);
    }
}
