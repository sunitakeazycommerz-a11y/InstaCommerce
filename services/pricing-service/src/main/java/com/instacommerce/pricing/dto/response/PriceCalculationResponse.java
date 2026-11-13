package com.instacommerce.pricing.dto.response;

import java.math.BigDecimal;
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
    String quoteToken,
    long estimatedTaxCents,
    BigDecimal taxRate
) {
    public PriceCalculationResponse(
            List<PricedItem> items,
            long subtotalCents,
            long discountCents,
            long totalCents,
            List<String> appliedPromotions,
            String appliedCoupon,
            UUID quoteId,
            String quoteToken) {
        this(
                items,
                subtotalCents,
                discountCents,
                totalCents,
                appliedPromotions,
                appliedCoupon,
                quoteId,
                quoteToken,
                0L,
                null);
    }
}
