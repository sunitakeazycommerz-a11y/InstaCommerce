package com.instacommerce.catalog.dto.response;

import java.util.List;
import java.util.UUID;

public record PricingItemResponse(
    UUID productId,
    String productName,
    int quantity,
    long unitPriceCents,
    long effectivePriceCents,
    long lineTotalCents,
    List<String> appliedRules
) {
}
