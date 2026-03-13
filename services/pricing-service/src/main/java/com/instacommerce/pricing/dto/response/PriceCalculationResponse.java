package com.instacommerce.pricing.dto.response;

import java.util.List;
import java.util.UUID;

public record PriceCalculationResponse(
    List<PricedItem> items,
    long subtotalCents,
    long discountCents,
    long totalCents,
    List<String> appliedPromotions,
    String appliedCoupon,
    UUID quoteId,
    String quoteToken
) {
}
