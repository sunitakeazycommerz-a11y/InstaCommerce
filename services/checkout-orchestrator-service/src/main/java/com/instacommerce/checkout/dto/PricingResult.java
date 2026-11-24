package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingResult(
    long subtotalCents,
    long discountCents,
    long deliveryFeeCents,
    long totalCents,
    String currency,
    String quoteId,
    String quoteToken,
    String appliedCoupon,
    long couponDiscountCents,
    List<String> appliedPromotions,
    List<AppliedPromotionDetail> appliedPromotionDetails
) {
    public static final String DEFAULT_CURRENCY = "INR";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AppliedPromotionDetail(String promotionId, String name, long discountCents) {
    }

    public PricingResult(
        long subtotalCents,
        long discountCents,
        long deliveryFeeCents,
        long totalCents,
        String currency,
        String quoteId,
        String quoteToken
    ) {
        this(subtotalCents, discountCents, deliveryFeeCents, totalCents, currency, quoteId, quoteToken,
            null, 0L, List.of(), List.of());
    }

    public PricingResult(
        long subtotalCents,
        long discountCents,
        long deliveryFeeCents,
        long totalCents,
        String currency,
        String quoteId,
        String quoteToken,
        String appliedCoupon,
        long couponDiscountCents,
        List<String> appliedPromotions
    ) {
        this(subtotalCents, discountCents, deliveryFeeCents, totalCents, currency, quoteId, quoteToken,
            appliedCoupon, couponDiscountCents, appliedPromotions, List.of());
    }

    @JsonCreator
    public PricingResult(
        @JsonProperty("subtotalCents") long subtotalCents,
        @JsonProperty("discountCents") long discountCents,
        @JsonProperty("deliveryFeeCents") Long deliveryFeeCents,
        @JsonProperty("totalCents") long totalCents,
        @JsonProperty("currency") String currency,
        @JsonProperty("quoteId") String quoteId,
        @JsonProperty("quoteToken") String quoteToken,
        @JsonProperty("appliedCoupon") String appliedCoupon,
        @JsonProperty("couponDiscountCents") Long couponDiscountCents,
        @JsonProperty("appliedPromotions") List<String> appliedPromotions,
        @JsonProperty("appliedPromotionDetails") List<AppliedPromotionDetail> appliedPromotionDetails
    ) {
        this(
            subtotalCents,
            discountCents,
            deliveryFeeCents != null ? deliveryFeeCents : 0L,
            totalCents,
            currency != null && !currency.isBlank() ? currency : DEFAULT_CURRENCY,
            quoteId,
            quoteToken,
            (appliedCoupon != null && !appliedCoupon.isBlank()) ? appliedCoupon : null,
            resolveCouponDiscountCents(discountCents, appliedCoupon, couponDiscountCents, appliedPromotions),
            appliedPromotions != null ? List.copyOf(appliedPromotions) : List.of(),
            appliedPromotionDetails != null ? List.copyOf(appliedPromotionDetails) : List.of()
        );
    }

    private static long resolveCouponDiscountCents(
        long discountCents,
        String appliedCoupon,
        Long couponDiscountCents,
        List<String> appliedPromotions
    ) {
        if (couponDiscountCents != null) {
            return Math.max(0L, couponDiscountCents);
        }
        boolean hasCoupon = appliedCoupon != null && !appliedCoupon.isBlank();
        boolean hasPromotions = appliedPromotions != null && !appliedPromotions.isEmpty();
        return (hasCoupon && !hasPromotions) ? Math.max(0L, discountCents) : 0L;
    }
}
