package com.instacommerce.pricing.dto.response;

import java.util.UUID;

public record CouponResponse(
    UUID id,
    String code,
    UUID promotionId,
    String promotionName,
    boolean singleUse,
    int perUserLimit,
    Integer totalLimit,
    int totalRedeemed,
    boolean active
) {
}
