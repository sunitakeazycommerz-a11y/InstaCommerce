package com.instacommerce.catalog.pricing;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(3)
public class PromotionStrategy implements PricingStrategy {
    @Override
    public PricingResult apply(PricingContext context, long currentPriceCents) {
        return PricingResult.unchanged(currentPriceCents);
    }

    @Override
    public int order() {
        return 3;
    }
}
