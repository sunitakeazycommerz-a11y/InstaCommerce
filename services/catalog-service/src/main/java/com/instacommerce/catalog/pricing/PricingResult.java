package com.instacommerce.catalog.pricing;

public record PricingResult(
    long priceCents,
    String appliedRule
) {
    public static PricingResult unchanged(long currentPrice) {
        return new PricingResult(currentPrice, null);
    }
}
