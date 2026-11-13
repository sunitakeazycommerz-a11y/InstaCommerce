package com.instacommerce.fulfillment.client;

import java.util.UUID;

public record InventoryAdjustRequest(
    UUID productId,
    UUID storeId,
    Integer delta,
    String reason,
    String referenceId
) {
}
