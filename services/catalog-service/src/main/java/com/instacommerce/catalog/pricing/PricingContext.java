package com.instacommerce.catalog.pricing;

import com.instacommerce.catalog.domain.model.Product;
import java.util.UUID;

public record PricingContext(
    Product product,
    String storeId,
    String zoneId,
    UUID userId
) {
}
