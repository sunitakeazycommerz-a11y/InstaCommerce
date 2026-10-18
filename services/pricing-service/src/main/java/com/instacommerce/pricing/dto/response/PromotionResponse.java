package com.instacommerce.pricing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PromotionResponse(
    UUID id,
    String name,
    String description,
    String discountType,
    BigDecimal discountValue,
    long minOrderCents,
    Long maxDiscountCents,
    Instant startAt,
    Instant endAt,
    boolean active,
    Integer maxUses,
    int currentUses,
    Integer maxRedemptionsPerUser,
    Instant createdAt
) {
    public PromotionResponse(
            UUID id,
            String name,
            String description,
            String discountType,
            BigDecimal discountValue,
            long minOrderCents,
            Long maxDiscountCents,
            Instant startAt,
            Instant endAt,
            boolean active,
            Integer maxUses,
            int currentUses,
            Instant createdAt
    ) {
        this(id, name, description, discountType, discountValue, minOrderCents,
             maxDiscountCents, startAt, endAt, active, maxUses, currentUses, 1, createdAt);
    }
}
