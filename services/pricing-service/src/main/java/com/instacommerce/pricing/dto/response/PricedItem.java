package com.instacommerce.pricing.dto.response;

import java.util.UUID;

public record PricedItem(
    UUID productId,
    long unitPriceCents,
    int quantity,
    long lineTotalCents
) {
}
