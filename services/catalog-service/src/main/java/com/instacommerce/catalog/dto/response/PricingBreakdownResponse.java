package com.instacommerce.catalog.dto.response;

import java.util.List;

public record PricingBreakdownResponse(
    List<PricingItemResponse> items,
    long subtotalCents,
    CouponDiscountResponse couponDiscount,
    long totalCents,
    String currency
) {
}
