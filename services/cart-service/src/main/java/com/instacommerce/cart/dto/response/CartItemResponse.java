package com.instacommerce.cart.dto.response;

import java.util.UUID;

public record CartItemResponse(
    UUID productId,
    String productName,
    long unitPriceCents,
    int quantity,
    long lineTotalCents
) {
}
