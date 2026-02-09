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
    Integer maxUses
) {
}
