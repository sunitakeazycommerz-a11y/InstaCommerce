package com.instacommerce.order.dto.response;

import java.util.UUID;

public record OrderItemResponse(
    UUID productId,
    String productName,
    String productSku,
    int quantity,
    long unitPriceCents,
    long lineTotalCents
) {
}
