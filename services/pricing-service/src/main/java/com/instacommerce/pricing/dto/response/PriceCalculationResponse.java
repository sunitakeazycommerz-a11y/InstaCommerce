package com.instacommerce.pricing.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PriceCalculationResponse(
    List<PricedItem> items,
    long subtotalCents,
    long discountCents,
    long deliveryFeeCents,
    long totalCents,
    String currency,
    List<String> appliedPromotions,
    String appliedCoupon,
    UUID quoteId,
    String quoteToken,
    long estimatedTaxCents,
    BigDecimal taxRate,
    long couponDiscountCents
) {
    public PriceCalculationResponse(
            List<PricedItem> items,
            long subtotalCents,
            long discountCents,
            long deliveryFeeCents,
            long totalCents,
            String currency,
            List<String> appliedPromotions,
            String appliedCoupon,
            UUID quoteId,
            String quoteToken,
            long estimatedTaxCents,
            BigDecimal taxRate) {
        this(
                items,
                subtotalCents,
                discountCents,
                deliveryFeeCents,
                totalCents,
                currency,
                appliedPromotions,
                appliedCoupon,
                quoteId,
                quoteToken,
                estimatedTaxCents,
                taxRate,
                0L);
    }

    public PriceCalculationResponse(
            List<PricedItem> items,
            long subtotalCents,
            long discountCents,
            long totalCents,
            List<String> appliedPromotions,
            String appliedCoupon,
            UUID quoteId,
            String quoteToken,
            long estimatedTaxCents,
            BigDecimal taxRate) {
        this(
                items,
                subtotalCents,
                discountCents,
                0L,
                totalCents,
                "INR",
                appliedPromotions,
                appliedCoupon,
                quoteId,
                quoteToken,
                estimatedTaxCents,
                taxRate,
                0L);
    }

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
                0L,
                totalCents,
                "INR",
                appliedPromotions,
                appliedCoupon,
                quoteId,
                quoteToken,
                0L,
                null,
                0L);
    }
}
