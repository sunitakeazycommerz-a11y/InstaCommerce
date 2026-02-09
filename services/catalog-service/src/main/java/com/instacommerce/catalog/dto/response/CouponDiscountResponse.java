package com.instacommerce.catalog.dto.response;

public record CouponDiscountResponse(
    String code,
    long discountCents,
    boolean valid
) {
}
