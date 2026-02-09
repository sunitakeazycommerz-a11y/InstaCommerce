package com.instacommerce.fulfillment.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PickItemResponse(
    UUID id,
    UUID productId,
    String productName,
    String sku,
    int quantity,
    int pickedQty,
    long unitPriceCents,
    long lineTotalCents,
    String status,
    UUID substituteProductId,
    String note,
    Instant updatedAt
) {
}
