package com.instacommerce.order.client;

import java.util.UUID;

public record InventoryItemRequest(
    UUID productId,
    int quantity
) {
}
