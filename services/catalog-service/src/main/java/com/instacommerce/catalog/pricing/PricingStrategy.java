package com.instacommerce.catalog.pricing;

public interface PricingStrategy {
    PricingResult apply(PricingContext context, long currentPriceCents);

    int order();
}
