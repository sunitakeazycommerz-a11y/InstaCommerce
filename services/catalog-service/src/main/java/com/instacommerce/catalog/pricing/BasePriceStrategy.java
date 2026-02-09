package com.instacommerce.catalog.pricing;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class BasePriceStrategy implements PricingStrategy {
    @Override
    public PricingResult apply(PricingContext context, long currentPriceCents) {
        return new PricingResult(context.product().getBasePriceCents(), "BASE_PRICE");
    }

    @Override
    public int order() {
        return 1;
    }
}
