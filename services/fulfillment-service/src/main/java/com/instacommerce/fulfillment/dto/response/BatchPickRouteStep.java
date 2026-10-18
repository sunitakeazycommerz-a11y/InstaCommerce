package com.instacommerce.fulfillment.dto.response;

import java.util.UUID;

public record BatchPickRouteStep(
    int stepNumber,
    UUID pickItemId,
    UUID orderId,
    String productName,
    String sku,
    int quantity,
    String aisle,
    Integer shelf,
    String bin,
    String targetToteBarcode,
    String targetToteColor
) {
}
